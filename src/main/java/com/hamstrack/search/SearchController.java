package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.search.dto.SearchRequest;
import com.hamstrack.search.dto.SearchResultRow;
import com.hamstrack.search.dto.SearchSchemaResponse;
import com.hamstrack.search.dto.SuggestResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Advanced Search (HQL) endpoints — a workspace-scoped query language over issues
 * (HD-21, Advanced Search proposal §8). Every request is bound to one workspace and
 * gated by membership: a non-member gets <strong>404</strong> (never 403 — no
 * existence leak). Results are further restricted, in the compiler, by the
 * server-built {@link SearchScope} predicate (workspace + visible non-archived
 * projects) ANDed as the outermost conjunction, so no query text can widen the
 * tenant boundary.
 *
 * <ul>
 *   <li>{@code POST …/search} — run an HQL query; paginated ({@code size} ≤ 100);
 *       422 with a highlight {@code position} on a parse error, 422 with a
 *       {@code field} on a semantic error;</li>
 *   <li>{@code GET …/search/schema} — fields, operators-per-field and small static
 *       value picklists (statuses/types/priorities reachable by visible projects,
 *       plus the workspace's labels and the visible projects' components and
 *       versions, each capped at 200 entries) for autocomplete; the member list is
 *       NOT embedded. Fields belonging to a delivery capability ({@code sprint},
 *       {@code fixVersion}, {@code affectsVersion}, {@code storyPoints}) are listed
 *       only when ≥1 visible project has that capability on, and the SPRINT/VERSION
 *       picklists only draw from capability-on projects (HD-107 §9.1) — a
 *       <strong>suggestion</strong> narrowing exclusively: every field keeps parsing,
 *       compiling and running on every project, so no saved filter can break because a
 *       curator flipped a toggle;</li>
 *   <li>{@code GET …/search/suggest?field=&q=} — bounded typeahead for user-valued
 *       fields ({@code assignee}/{@code reporter} or a USER custom field), for
 *       {@code label}/{@code labels} (HD-30), for {@code component}/
 *       {@code components} (HD-31) and for {@code fixVersion}/{@code affectsVersion}
 *       (HD-32), capped at 20 suggestions. The version suggestions are the overflow
 *       of the {@code /schema} VERSION picklist, so they are scoped the same way —
 *       visible projects with {@code releases} on (HD-107 §9.1), suggestion-only
 *       again: a version whose project has releases off still resolves by name.</li>
 * </ul>
 *
 * <p>Retired HQL keys stay resolvable on {@code POST …/search}: {@code story_points}
 * falls back to the native {@code storyPoints} ({@link RetiredFieldAliases}, HD-107
 * §9.2) <em>after</em> the caller's own custom fields have been tried, so a tenant
 * field keyed {@code story_points} always wins. Aliases are compatibility, not
 * vocabulary — {@code /schema} never advertises them.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/search")
@RequiredArgsConstructor
@Validated
public class SearchController {

    private final SearchService searchService;

    @PostMapping
    public PageResponse<SearchResultRow> search(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId,
                                                @Valid @RequestBody SearchRequest req) {
        return searchService.search(actor, workspaceId, req);
    }

    @GetMapping("/schema")
    public SearchSchemaResponse schema(@AuthenticationPrincipal User actor,
                                       @PathVariable UUID workspaceId) {
        return searchService.schema(actor, workspaceId);
    }

    @GetMapping("/suggest")
    public SuggestResponse suggest(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @RequestParam String field,
                                   @RequestParam(required = false) @Size(max = 100) String q) {
        return searchService.suggest(actor, workspaceId, field, q);
    }
}
