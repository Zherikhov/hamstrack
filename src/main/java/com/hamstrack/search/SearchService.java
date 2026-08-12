package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.dto.IssueResponse;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.service.IssueService;
import com.hamstrack.search.dto.SearchRequest;
import com.hamstrack.search.dto.SearchResultRow;
import com.hamstrack.search.dto.SearchSchemaResponse;
import com.hamstrack.search.dto.SuggestResponse;
import com.hamstrack.search.parser.HqlParser;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
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

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResolutionContextFactory resolutionContextFactory;
    private final HqlValidator validator;
    private final HqlCompiler compiler;
    private final FieldRegistry registry;
    private final IssueService issueService;
    private final EntityManager em;

    /** Bounded typeahead cap for {@code /suggest} (§16.4). */
    private static final int SUGGEST_LIMIT = 20;

    @Transactional(readOnly = true)
    public PageResponse<SearchResultRow> search(User actor, UUID workspaceId, SearchRequest req) {
        var ws = resolveWorkspace(actor, workspaceId);

        // Parse (422 PARSE_ERROR on failure — mapped by GlobalExceptionHandler).
        Query query = HqlParser.parse(req.queryOrEmpty());
        // Semantic validation (unknown field / illegal op / non-sortable order-by / …).
        validator.validate(query);

        var ctx = resolutionContextFactory.build(actor, ws);

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

        var fields = registry.availableFields().stream()
                .map(f -> new SearchSchemaResponse.Field(
                        f.name(), f.dataType().name(), operatorTokens(f),
                        f.nullable(), f.sortable(), f.valueSuggest(), f.functions()))
                .toList();

        // Small, embeddable value picklists — distinct names reachable by visible
        // projects (deduped), so the picklist matches what name-resolution accepts.
        Map<String, List<SearchSchemaResponse.ValueOption>> values = new TreeMap<>();
        values.put("STATUS", labels(ctx.statusNames()));
        values.put("TYPE", labels(ctx.typeNames()));
        values.put("PRIORITY", labels(ctx.priorityNames()));

        return new SearchSchemaResponse(fields, keywords(), values);
    }

    @Transactional(readOnly = true)
    public SuggestResponse suggest(User actor, UUID workspaceId, String fieldName, String q) {
        var ws = resolveWorkspace(actor, workspaceId);
        var descriptor = registry.find(fieldName)
                .filter(FieldDescriptor::available)
                .filter(f -> f.dataType() == FieldDataType.USER_REF)
                .orElseThrow(() -> new HqlSemanticException(
                        "Field '" + fieldName + "' has no value suggestions", fieldName));

        var ctx = resolutionContextFactory.build(actor, ws);
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

        return new SuggestResponse(descriptor.name(), suggestions);
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
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return workspace;
    }
}
