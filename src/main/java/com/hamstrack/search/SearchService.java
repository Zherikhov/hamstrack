package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.dto.IssueResponse;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.service.IssueService;
import com.hamstrack.issue.service.ComponentService;
import com.hamstrack.issue.service.LabelService;
import com.hamstrack.issue.service.VersionService;
import com.hamstrack.report.dto.InsightsDimension;
import com.hamstrack.report.dto.InsightsMeasure;
import com.hamstrack.search.FieldResolver.Resolved;
import com.hamstrack.search.dto.SearchRequest;
import com.hamstrack.search.dto.SearchResultRow;
import com.hamstrack.search.dto.SearchSchemaResponse;
import com.hamstrack.search.dto.SuggestResponse;
import com.hamstrack.search.parser.HqlParser;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Orchestrates HQL search (Advanced Search proposal §8) — parse → validate →
 * resolve → compile → execute, all inside one read transaction scoped to the
 * caller's workspace. Membership is verified up front: a non-member gets 404 (never
 * 403 — no existence leak, the project's #1 bug class). Page size is capped ≤ 100.
 *
 * <p>The tenancy boundary is enforced by {@link SearchScope} inside
 * {@link HqlCompiler}; this service only resolves the workspace and builds the
 * {@link ResolutionContext} from server-side state.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final WorkspaceAccessService workspaceAccess;
    private final ResolutionContextFactory resolutionContextFactory;
    private final HqlValidator validator;
    private final HqlCompiler compiler;
    private final FieldRegistry registry;
    private final FieldResolver fieldResolver;
    private final IssueService issueService;
    private final LabelService labelService;
    private final ComponentService componentService;
    private final VersionService versionService;
    private final EntityManager em;

    /** Bounded typeahead cap for {@code /suggest} (§16.4). */
    private static final int SUGGEST_LIMIT = 20;

    /** Max entries embedded in the {@code /schema} LABEL picklist (HD-30 §3.5). */
    private static final int LABEL_PICKLIST_LIMIT = 200;

    /** Max entries embedded in the {@code /schema} COMPONENT picklist (HD-31 §3.5). */
    private static final int COMPONENT_PICKLIST_LIMIT = 200;

    /** Max entries embedded in the {@code /schema} VERSION picklist (HD-32 §3.5). */
    private static final int VERSION_PICKLIST_LIMIT = 200;

    /** Max entries embedded in the {@code /schema} SPRINT picklist (HD-22 §4.7). */
    private static final int SPRINT_PICKLIST_LIMIT = 200;

    /** Max entries embedded in the {@code /schema} PROJECT picklist (HD-101). */
    private static final int PROJECT_PICKLIST_LIMIT = 200;

    @Transactional(readOnly = true)
    public PageResponse<SearchResultRow> search(User actor, UUID workspaceId, SearchRequest req) {
        var ws = resolveWorkspace(actor, workspaceId);

        // Parse (422 PARSE_ERROR on failure — mapped by GlobalExceptionHandler).
        Query query = HqlParser.parse(req.queryOrEmpty());

        // Build the per-request context first (it carries the caller's visible custom
        // fields), then validate against it — a custom field is only known here (HD-52).
        var ctx = resolutionContextFactory.build(actor, ws);
        // Semantic validation (unknown field / illegal op / non-sortable order-by / …).
        validator.validate(query, ctx);

        int page = (req.page() == null || req.page() < 0) ? 0 : req.page();
        int size = Math.min(Math.max(req.size() == null ? Paging.DEFAULT_SIZE : req.size(), 1), Paging.MAX_SIZE);

        // Count first (predicate-only), then the fetched page.
        long total = em.createQuery(compiler.buildCountQuery(query, actor, ws, ctx))
                .getSingleResult();

        List<Issue> issues = em.createQuery(compiler.buildPageQuery(query, actor, ws, ctx))
                // Paging.offsetOf, not `page * size`: setFirstResult takes an int, so the
                // multiplication has to be checked somewhere, and the @Max on
                // SearchRequest.page is the only thing that would otherwise stand between a
                // caller and an overflow (400 either way — see PageOffsetTooLargeException).
                .setFirstResult(Paging.offsetOf(page, size))
                .setMaxResults(size)
                .getResultList();

        // Reuse the board/backlog row assembly (batched rollup + parent summaries).
        List<IssueResponse> responses = issueService.toResponsesBatched(issues);
        List<SearchResultRow> rows = new ArrayList<>(responses.size());
        for (int i = 0; i < issues.size(); i++) {
            var issue = issues.get(i);
            var project = issue.getProject();
            rows.add(new SearchResultRow(responses.get(i),
                    project.getId(), project.getKey(), project.getName()));
        }

        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        // `(long) page + 1`, not `(long) (page + 1)`: the cast has to happen BEFORE the
        // addition or the increment overflows in int first and the widening preserves a
        // negative. Unreachable now that SearchRequest.page carries @Max(Paging.MAX_PAGE)
        // (HD-163) — kept correct anyway. This is arithmetic on the RESULT, not a guard on
        // the offset: the offset is bounded above by Paging.offsetOf, which is the line that
        // refuses.
        boolean hasNext = ((long) page + 1) * size < total;
        return new PageResponse<>(rows, page, size, total, totalPages, hasNext);
    }

    @Transactional(readOnly = true)
    public SearchSchemaResponse schema(User actor, UUID workspaceId) {
        var ws = resolveWorkspace(actor, workspaceId);
        var ctx = resolutionContextFactory.build(actor, ws);

        var fields = new ArrayList<SearchSchemaResponse.Field>();
        registry.availableFields().stream()
                .filter(f -> suggested(f, ctx))
                .forEach(f -> fields.add(new SearchSchemaResponse.Field(
                        f.name(), f.dataType().name(), operatorTokens(f),
                        f.nullable(), f.sortable(), f.valueSuggest(), f.functions())));

        // Small, embeddable value picklists — distinct names reachable by visible
        // projects (deduped), so the picklist matches what name-resolution accepts.
        Map<String, List<SearchSchemaResponse.ValueOption>> values = new TreeMap<>();
        values.put("STATUS", labels(ctx.statusNames()));
        values.put("TYPE", labels(ctx.typeNames()));
        values.put("PRIORITY", labels(ctx.priorityNames()));
        // LABEL (HD-30): a workspace can accumulate thousands of labels, so this
        // picklist is CAPPED (§3.5). When it is truncated the client falls back to
        // /suggest?field=label&q= — the same bounded typeahead users get for members.
        values.put("LABEL", capped(labels(ctx.labelNames()), LABEL_PICKLIST_LIMIT));
        // COMPONENT (HD-31): the non-archived component names of the caller's VISIBLE
        // projects, capped for the same reason — beyond the cap the client falls back
        // to /suggest?field=component&q=.
        values.put("COMPONENT", capped(labels(ctx.componentNames()), COMPONENT_PICKLIST_LIMIT));
        // VERSION (HD-32): the non-archived version names of the caller's VISIBLE
        // projects, capped for the same reason — beyond the cap the client falls back
        // to /suggest?field=fixVersion&q=. ONE picklist backs both fixVersion and
        // affectsVersion (both descriptors declare valueSuggest = "VERSION"): the two
        // roles draw from the same catalog, only the link differs.
        //
        // HD-107 §9.1: additionally scoped to visible projects with `releases` ON. That
        // is a SUGGESTION narrowing only — `fixVersion = "2.4.0"` still resolves and
        // still runs against a project with releases off, so a saved filter can never
        // break because a curator flipped the toggle.
        values.put("VERSION", capped(labels(ctx.versionNames()), VERSION_PICKLIST_LIMIT));
        // SPRINT (HD-22): the OPEN sprint names of the caller's VISIBLE projects. Capped
        // like the others, though the open-sprint cap already bounds it per project —
        // "visible projects" is what makes the total unbounded. COMPLETED sprints are
        // deliberately absent: they are excluded from name resolution too, so offering
        // them would suggest values that then 422. HD-107 §9.1 narrows this to the
        // projects with `board = SCRUM`, on the same suggestion-only terms as VERSION.
        values.put("SPRINT", capped(labels(ctx.sprintNames()), SPRINT_PICKLIST_LIMIT));
        // PROJECT (HD-101): the caller's VISIBLE projects — the same set the scope predicate
        // restricts every search to, so the picklist can only ever offer what a query already
        // reaches. Read straight off the context, which loaded those projects to build the
        // scope: this picklist costs no query of its own. Capped like the others, with
        // /suggest?field=project as the overflow.
        //
        // Label and value differ here, as they do for USER: the label shows the project's name
        // (with its key, which is what the UI shows elsewhere) and the value IS the key — the
        // only string the language resolves. That split is what lets a user find a project by
        // its human name without the language having to match names, which it must not: project
        // names carry no uniqueness constraint.
        //
        // Not capability-narrowed, and not an oversight: `suggested(...)` narrows the fields
        // tied to a delivery capability, and every project has a project.
        values.put("PROJECT", capped(projectOptions(ctx), PROJECT_PICKLIST_LIMIT));

        // Custom fields (HD-52): append after the system fields, hidden system-name
        // collisions aside. SELECT/MULTI_SELECT publish their options under a per-field
        // value key so autocomplete offers the labels; USER fields reuse /suggest.
        for (CustomFieldMeta meta : ctx.customFieldsByKey().values()) {
            // A key the registry has claimed is a system name, so a query against it means the
            // system field and not this workspace's — advertising it here would offer a name
            // whose answers come from somewhere else. Registration is what claims a name;
            // availability only says WHEN it starts answering (see FieldResolver), so a
            // reserved-but-not-yet-queryable entry claims its key just as firmly, and reading
            // that any other way puts a key in this list that every query against it refuses.
            // Vocabulary omits rather than refuses: /schema is a list of what exists, so a
            // claimed key is simply absent here and the refusal is the query's to give.
            if (registry.find(meta.key()).isPresent()) continue;
            String valueSuggest = customValueSuggest(meta);
            fields.add(new SearchSchemaResponse.Field(
                    meta.key(), meta.type().name(), customOperatorTokens(meta),
                    CustomFieldOps.nullable(meta.type()), false,
                    valueSuggest, CustomFieldOps.functions(meta.type())));
            if (valueSuggest != null && !meta.optionsById().isEmpty()) {
                values.put(valueSuggest, optionOptions(meta));
            }
        }

        return new SearchSchemaResponse(fields, keywords(), values, insights(ctx));
    }

    /**
     * Bounded value typeahead for one field (§16.4) — the fallback for value sets too large,
     * or too caller-specific, for a {@code /schema} picklist to carry whole.
     *
     * <p><strong>What the name means is not decided here</strong> (HD-114): it resolves through
     * {@link FieldResolver} exactly as a query does, and the suggester follows from the
     * <em>kind</em> that comes back. So a name this endpoint offers values for is a name a query
     * accepts, and a name the resolver refuses — unknown, or reserved and not yet queryable — is
     * refused here in the resolver's own words. A vocabulary surface that judged for itself which
     * names the product has claimed is how a key came to be offered by one surface and rejected
     * by another (HD-107 §9.2); nothing is left here to judge differently.
     *
     * <p>The response echoes the name <em>as the caller wrote it</em>, aliases included: a client
     * matches the answer to the request it made, not to a canonical spelling it never sent.
     */
    @Transactional(readOnly = true)
    public SuggestResponse suggest(User actor, UUID workspaceId, String fieldName, String q) {
        var ws = resolveWorkspace(actor, workspaceId);
        var ctx = resolutionContextFactory.build(actor, ws);

        // Exhaustive over Resolved on purpose: a kind of field added later cannot become
        // silently unsuggestable here, it stops this from compiling.
        return switch (fieldResolver.resolve(fieldName, ctx)) {
            case Resolved.SystemField(FieldDescriptor sys) -> systemSuggestions(sys, fieldName, q, ws, ctx);
            case Resolved.CustomField(CustomFieldMeta cf) -> customSuggestions(cf, fieldName, q, ctx);
        };
    }

    // ---- helpers ----

    /**
     * Values for a field the product ships, chosen from the resolved <strong>descriptor</strong>
     * and never from the spelling that arrived — an alias shares its field's descriptor instance,
     * so it reaches the same suggester for free.
     *
     * <p>A system field with no bounded lookup behind it (a numeric, a date, a picklist small
     * enough for {@code /schema} to embed whole) declines with {@link #noSuggestions}: the field
     * exists, and the value question is the one being refused.
     */
    private SuggestResponse systemSuggestions(FieldDescriptor sys, String fieldName, String q,
                                              Workspace ws, ResolutionContext ctx) {
        // label (HD-30): a bounded prefix search straight over the workspace's
        // non-archived labels — the fallback when the /schema LABEL picklist is capped.
        if (sys.dataType() == FieldDataType.LABEL_REF) {
            return names(fieldName, labelService.suggestNames(ws, q, SUGGEST_LIMIT));
        }
        // component (HD-31): a bounded prefix search over the non-archived components
        // of the caller's VISIBLE projects — the fallback when the /schema COMPONENT
        // picklist is capped.
        if ("component".equals(sys.name())) {
            return names(fieldName, componentService.suggestNames(
                    ctx.visibleProjectIds(), q, SUGGEST_LIMIT));
        }
        // fixVersion / affectsVersion (HD-32): a bounded prefix search over the
        // non-archived versions of the caller's VISIBLE projects — the fallback when the
        // /schema VERSION picklist is capped. Both roles share one catalog, so the
        // suggestions are identical for either field name.
        //
        // HD-107 §9.1: this endpoint IS the overflow of that picklist, so it is scoped
        // the same way — visible projects with `releases` on. Still a strict subset of
        // the visible set (narrowing only, never widening), and still suggestion-only:
        // a version name that no longer appears here keeps resolving in a query.
        if (sys.dataType() == FieldDataType.VERSION_REF) {
            return names(fieldName, versionService.suggestNames(
                    ctx.capabilities().releaseProjectIds(), q, SUGGEST_LIMIT));
        }
        // assignee / reporter: the member typeahead, the same one user-valued custom fields get.
        if (sys.dataType() == FieldDataType.USER_REF) {
            return members(fieldName, q, ctx);
        }
        // project (HD-101): filtered in memory off the context, like the member typeahead and
        // unlike component/version, which page a project-owned catalog. The visible project set
        // is already loaded — it is what the scope predicate is built from — so the overflow of
        // the PROJECT picklist costs no query either.
        if ("project".equals(sys.name())) {
            return projects(fieldName, q, ctx);
        }
        throw noSuggestions(fieldName);
    }

    /**
     * Values for a custom field the caller can see (HD-52). A user-valued one draws on the same
     * member typeahead its system counterparts do — the value a caller pastes back is a member
     * either way; any other type declines exactly like an unsuggestable system field.
     */
    private SuggestResponse customSuggestions(CustomFieldMeta cf, String fieldName, String q,
                                              ResolutionContext ctx) {
        if (cf.type() == FieldType.USER) {
            return members(fieldName, q, ctx);
        }
        throw noSuggestions(fieldName);
    }

    /** The bounded member typeahead behind every user-valued field, whoever defined the field. */
    private SuggestResponse members(String fieldName, String q, ResolutionContext ctx) {
        String prefix = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        var suggestions = ctx.members().stream()
                .filter(m -> prefix.isEmpty()
                        || (m.displayName() != null && m.displayName().toLowerCase(Locale.ROOT).contains(prefix))
                        || (m.email() != null && m.email().toLowerCase(Locale.ROOT).startsWith(prefix)))
                .sorted(Comparator.comparing(m -> m.displayName() == null ? "" : m.displayName(),
                        String.CASE_INSENSITIVE_ORDER))
                .limit(SUGGEST_LIMIT)
                .map(m -> new SuggestResponse.Suggestion(m.displayName(), m.email()))
                .toList();

        return new SuggestResponse(fieldName, suggestions);
    }

    /**
     * The bounded project typeahead (HD-101). Prefix-matches the key AND the name — a caller
     * typing {@code HD} and one typing {@code Hams} are looking for the same project — but the
     * value it offers is always the KEY, because the key is the only string the query language
     * resolves. This is the surface that makes name-based discovery work without making the
     * language ambiguous: being wrong here costs a redundant dropdown row, whereas matching a
     * name in the language would cost a wrong result set.
     */
    private SuggestResponse projects(String fieldName, String q, ResolutionContext ctx) {
        String prefix = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        var suggestions = ctx.projects().stream()
                .filter(p -> prefix.isEmpty()
                        || p.key().toLowerCase(Locale.ROOT).startsWith(prefix)
                        || p.name().toLowerCase(Locale.ROOT).startsWith(prefix))
                .limit(SUGGEST_LIMIT)
                .map(p -> new SuggestResponse.Suggestion(projectLabel(p), p.key()))
                .toList();
        return new SuggestResponse(fieldName, suggestions);
    }

    /** The {@code /schema} PROJECT picklist — see the note at its {@code values.put}. */
    private List<SearchSchemaResponse.ValueOption> projectOptions(ResolutionContext ctx) {
        return ctx.projects().stream()
                .map(p -> new SearchSchemaResponse.ValueOption(projectLabel(p), p.key()))
                .toList();
    }

    /**
     * One rendering of a project for both value surfaces, so the dropdown a caller sees is the
     * same whether the picklist fit or the typeahead answered.
     */
    private String projectLabel(ResolutionContext.ProjectRef p) {
        return p.name() + " (" + p.key() + ")";
    }

    /** Name-valued suggestions: label and value are the same string a query would carry. */
    private SuggestResponse names(String fieldName, List<String> names) {
        return new SuggestResponse(fieldName, names.stream()
                .map(name -> new SuggestResponse.Suggestion(name, name))
                .toList());
    }

    /**
     * The refusal for a field that resolves but has no bounded value lookup behind it — a 422
     * anchored on the name as the caller wrote it, which is the one they can see in their own
     * request.
     */
    private HqlSemanticException noSuggestions(String fieldName) {
        return new HqlSemanticException(
                "Field '" + fieldName + "' has no value suggestions", fieldName);
    }

    /**
     * Whether {@code /schema} <em>suggests</em> a system field to this caller
     * (delivery-paths proposal §9.1, HD-107). A field that belongs to a delivery
     * capability is listed when <strong>at least one visible project</strong> has that
     * capability on; every other field is always listed.
     *
     * <p><strong>This is autocomplete vocabulary, never a contract.</strong> An omitted
     * field still parses, still compiles and still runs — typed by hand or loaded from
     * a saved filter — because a capability is a presentation preference (Rule A, §5.1)
     * and a filter that stopped working when a colleague flipped a project toggle is
     * exactly the failure this model exists to prevent. Nothing here reaches
     * {@link HqlValidator} or {@link HqlCompiler}, and omitting a field never changes a
     * status code: an unknown reference is still a field-anchored 422, never a 404 and
     * never a silent empty result.
     *
     * <p>Keyed on the descriptor's canonical name, so the {@code sprints}/{@code points}
     * aliases follow their field automatically (they share the descriptor instance).
     */
    private boolean suggested(FieldDescriptor f, ResolutionContext ctx) {
        var caps = ctx.capabilities();
        return switch (f.name()) {
            case "sprint" -> caps.iterations();
            case "fixVersion", "affectsVersion" -> caps.releases();
            case "storyPoints" -> caps.estimation();
            default -> true;
        };
    }

    /**
     * The Insights panel's vocabulary for this caller (HD-140 R6, reports-proposal §2.6),
     * narrowed by the visible projects' delivery capabilities on <strong>exactly</strong> the
     * terms {@link #suggested} applies to fields — and for the same reason.
     *
     * <p>{@code SPRINT} is offered when at least one visible project plans in sprints, and
     * {@code POINTS} when at least one estimates. Everything else is always offered. This is
     * autocomplete vocabulary and nothing else: {@code POST …/search/insights} resolves every
     * dimension and every measure whatever is in this list, so a panel state saved beside a
     * filter cannot break because a curator flipped a project toggle, and no status code
     * anywhere depends on a capability (Rule A, delivery-paths §5.1).
     *
     * <p>Built by filtering the enums rather than by listing strings, so a dimension added to
     * {@link InsightsDimension} is offered here without a second edit — the failure mode a
     * hand-written list has is that it silently stops matching what the endpoint accepts.
     */
    private SearchSchemaResponse.Insights insights(ResolutionContext ctx) {
        var caps = ctx.capabilities();
        var measures = Arrays.stream(InsightsMeasure.values())
                .filter(m -> m != InsightsMeasure.POINTS || caps.estimation())
                .map(Enum::name)
                .toList();
        var dimensions = Arrays.stream(InsightsDimension.values())
                .filter(d -> d != InsightsDimension.SPRINT || caps.iterations())
                .map(Enum::name)
                .toList();
        return new SearchSchemaResponse.Insights(measures, dimensions);
    }

    private List<String> operatorTokens(FieldDescriptor f) {
        var tokens = new ArrayList<String>();
        // Deterministic order for the SPA: comparison ops, then IN, then IS [NOT] EMPTY.
        for (var op : List.of("=", "!=", ">", "<", ">=", "<=", "~")) {
            if (f.operators().stream().anyMatch(o -> o.symbol().equals(op))) tokens.add(op);
        }
        if (f.supportsIn()) tokens.add("IN");
        if (f.nullable()) {
            tokens.add("IS EMPTY");
            tokens.add("IS NOT EMPTY");
        }
        return tokens;
    }

    private List<String> customOperatorTokens(CustomFieldMeta meta) {
        var ops = CustomFieldOps.comparisonOps(meta.type());
        var tokens = new ArrayList<String>();
        for (var op : List.of("=", "!=", ">", "<", ">=", "<=", "~")) {
            if (ops.stream().anyMatch(o -> o.symbol().equals(op))) tokens.add(op);
        }
        if (CustomFieldOps.supportsIn(meta.type())) tokens.add("IN");
        if (CustomFieldOps.nullable(meta.type())) {
            tokens.add("IS EMPTY");
            tokens.add("IS NOT EMPTY");
        }
        return tokens;
    }

    /** The value-source token for a custom field: a per-field key for selects, USER, or null. */
    private String customValueSuggest(CustomFieldMeta meta) {
        return switch (meta.type()) {
            case SELECT, MULTI_SELECT -> "CUSTOM:" + meta.key();
            case USER -> "USER";
            default -> null;
        };
    }

    /** SELECT/MULTI_SELECT options as {label, value=optionId} picklist entries. */
    private List<SearchSchemaResponse.ValueOption> optionOptions(CustomFieldMeta meta) {
        var list = new ArrayList<SearchSchemaResponse.ValueOption>();
        meta.optionsById().forEach((id, label) ->
                list.add(new SearchSchemaResponse.ValueOption(label, id)));
        return list;
    }

    /** Truncate an embedded picklist; the client falls back to /suggest beyond this. */
    private List<SearchSchemaResponse.ValueOption> capped(List<SearchSchemaResponse.ValueOption> options,
                                                          int limit) {
        return options.size() <= limit ? options : List.copyOf(options.subList(0, limit));
    }

    private List<SearchSchemaResponse.ValueOption> labels(Iterable<String> names) {
        var list = new ArrayList<SearchSchemaResponse.ValueOption>();
        for (String n : names) list.add(SearchSchemaResponse.ValueOption.label(n));
        list.sort(Comparator.comparing(SearchSchemaResponse.ValueOption::label, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private List<String> keywords() {
        return List.of("AND", "OR", "NOT", "IN", "IS", "EMPTY", "ORDER BY", "ASC", "DESC");
    }

    /** Membership gate — 404 (not 403) whether the ws is missing or the caller isn't a member. */
    private Workspace resolveWorkspace(User actor, UUID workspaceId) {
        return workspaceAccess.requireMember(actor, workspaceId).workspace();
    }
}
