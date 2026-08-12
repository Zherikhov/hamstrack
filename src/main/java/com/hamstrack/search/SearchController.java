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
 *       value picklists (statuses/types/priorities reachable by visible projects)
 *       for autocomplete; the member list is NOT embedded;</li>
 *   <li>{@code GET …/search/suggest?field=&q=} — bounded prefix typeahead for
 *       user-valued fields ({@code assignee}/{@code reporter}).</li>
 * </ul>
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
