package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.dto.IssueResponse;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.service.IssueService;
import com.hamstrack.issue.service.ComponentService;
import com.hamstrack.issue.service.LabelService;
import com.hamstrack.issue.service.VersionService;
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
                .setFirstResult(page * size)
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
        boolean hasNext = (long) (page + 1) * size < total;
        return new PageResponse<>(rows, page, size, total, totalPages, hasNext);
    }

    @Transactional(readOnly = true)
    public SearchSchemaResponse schema(User actor, UUID workspaceId) {
        var ws = resolveWorkspace(actor, workspaceId);
        var ctx = resolutionContextFactory.build(actor, ws);

        var fields = new ArrayList<SearchSchemaResponse.Field>();
        registry.availableFields().forEach(f -> fields.add(new SearchSchemaResponse.Field(
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
        values.put("VERSION", capped(labels(ctx.versionNames()), VERSION_PICKLIST_LIMIT));

        // Custom fields (HD-52): append after the system fields, hidden system-name
        // collisions aside. SELECT/MULTI_SELECT publish their options under a per-field
        // value key so autocomplete offers the labels; USER fields reuse /suggest.
        for (CustomFieldMeta meta : ctx.customFieldsByKey().values()) {
            // A key that shadows a system field is not queryable (system wins) — skip it.
            if (registry.find(meta.key()).filter(FieldDescriptor::available).isPresent()) continue;
            String valueSuggest = customValueSuggest(meta);
            fields.add(new SearchSchemaResponse.Field(
                    meta.key(), meta.type().name(), customOperatorTokens(meta),
                    CustomFieldOps.nullable(meta.type()), false,
                    valueSuggest, CustomFieldOps.functions(meta.type())));
            if (valueSuggest != null && !meta.optionsById().isEmpty()) {
                values.put(valueSuggest, optionOptions(meta));
            }
        }

        return new SearchSchemaResponse(fields, keywords(), values);
    }

    @Transactional(readOnly = true)
    public SuggestResponse suggest(User actor, UUID workspaceId, String fieldName, String q) {
        var ws = resolveWorkspace(actor, workspaceId);
        var ctx = resolutionContextFactory.build(actor, ws);

        // label (HD-30): a bounded prefix search straight over the workspace's
        // non-archived labels — the fallback when the /schema LABEL picklist is capped.
        boolean isLabel = registry.find(fieldName)
                .filter(FieldDescriptor::available)
                .filter(f -> f.dataType() == FieldDataType.LABEL_REF)
                .isPresent();
        if (isLabel) {
            var suggestions = labelService.suggestNames(ws, q, SUGGEST_LIMIT).stream()
                    .map(name -> new SuggestResponse.Suggestion(name, name))
                    .toList();
            return new SuggestResponse(fieldName, suggestions);
        }

        // component (HD-31): a bounded prefix search over the non-archived components
        // of the caller's VISIBLE projects — the fallback when the /schema COMPONENT
        // picklist is capped. Matched on the descriptor's canonical name, so the
        // `components` alias resolves here too.
        boolean isComponent = registry.find(fieldName)
                .filter(FieldDescriptor::available)
                .filter(f -> "component".equals(f.name()))
                .isPresent();
        if (isComponent) {
            var suggestions = componentService
                    .suggestNames(ctx.visibleProjectIds(), q, SUGGEST_LIMIT).stream()
                    .map(name -> new SuggestResponse.Suggestion(name, name))
                    .toList();
            return new SuggestResponse(fieldName, suggestions);
        }

        // fixVersion / affectsVersion (HD-32): a bounded prefix search over the
        // non-archived versions of the caller's VISIBLE projects — the fallback when the
        // /schema VERSION picklist is capped. Both roles share one catalog, so the
        // suggestions are identical for either field name.
        boolean isVersion = registry.find(fieldName)
                .filter(FieldDescriptor::available)
                .filter(f -> f.dataType() == FieldDataType.VERSION_REF)
                .isPresent();
        if (isVersion) {
            var suggestions = versionService
                    .suggestNames(ctx.visibleProjectIds(), q, SUGGEST_LIMIT).stream()
                    .map(name -> new SuggestResponse.Suggestion(name, name))
                    .toList();
            return new SuggestResponse(fieldName, suggestions);
        }

        // A user-valued field: a system USER_REF (assignee/reporter) OR a visible USER
        // custom field (HD-52). Both resolve via the same member typeahead.
        boolean systemUser = registry.find(fieldName)
                .filter(FieldDescriptor::available)
                .filter(f -> f.dataType() == FieldDataType.USER_REF)
                .isPresent();
        boolean customUser = !systemUser && ctx.customField(fieldName)
                .filter(m -> m.type() == com.hamstrack.issue.entity.FieldType.USER)
                .isPresent();
        if (!systemUser && !customUser) {
            throw new HqlSemanticException(
                    "Field '" + fieldName + "' has no value suggestions", fieldName);
        }

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

    // ---- helpers ----

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
