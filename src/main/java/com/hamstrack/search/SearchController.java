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
 * <p>Retired HQL keys stay resolvable at every entry point that reads a field name, a
 * query being only one of them: {@code story_points} falls back to the native
 * {@code storyPoints} ({@link RetiredFieldAliases}, HD-107 §9.2) <em>after</em> the
 * caller's own custom fields have been tried, so a tenant field keyed
 * {@code story_points} always wins. The precedence is defined once, in
 * {@link FieldResolver} (HD-114), so it cannot hold on one endpoint and not another.
 * Aliases are compatibility, not vocabulary — {@code /schema} never advertises them.
 *
 * <p><strong>No {@code @Validated} on this class — and none on any bean Spring MVC
 * dispatches to</strong> (ADR-0018, HD-214). It reads as "turn on validation" and does
 * the opposite: {@code HandlerMethod.shouldValidateArguments()} returns {@code false}
 * exactly when the bean type carries it, so MVC hands parameter validation to the AOP
 * proxy, which throws {@code jakarta.validation.ConstraintViolationException} instead of
 * the {@code HandlerMethodValidationException} {@code GlobalExceptionHandler} renders as
 * a 400 with an {@code errors} map. This class carried it from HD-3 until HD-214, and
 * for that whole time the {@code @Size(max = 100)} on {@code q} below answered
 * <strong>500</strong>. There is a backstop handler now, so the annotation would no
 * longer crash — it would still be wrong, because it gives one controller a different
 * refusal shape and an ERROR line for a plain client mistake. Nothing on this class
 * needs a proxy for any other reason (no {@code @Transactional}, {@code @Cacheable} or
 * {@code @Async}), and the only capability {@code @Validated} adds over MVC's own method
 * validation is return-value validation, which no method here declares.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/search")
@RequiredArgsConstructor
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

    // BOTH parameters are bounded, and `field` is the one that was missing (HD-214). An
    // unknown name is not rejected on sight: FieldResolver falls through to
    // FieldRegistry.suggest, which runs Levenshtein against every registry entry, so the
    // input's length is a multiplier on work the caller does not pay for. Tomcat's ~8 KB
    // request line was the only cap — roughly seven million integer operations for one
    // authenticated GET, on a surface budgeted per minute — and that is on top of the
    // workspace resolution and full ResolutionContext build (~8 statements) that
    // `suggest` performs BEFORE it looks at the name. Bounding it here refuses during
    // argument resolution, so an over-long name costs neither.
    //
    // 100 is derived, not chosen: the widest name that can legitimately resolve is a
    // tenant custom field's key, and field_defs.key is VARCHAR(50). Every system name in
    // FieldRegistry is far shorter. A bare numeral, per ADR-0017. An unknown name that
    // fits still gets the field-anchored 422 with its "did you mean" hint — the bound
    // refuses the absurd, not the mistaken.
    @GetMapping("/suggest")
    public SuggestResponse suggest(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @RequestParam @Size(max = 100) String field,
                                   @RequestParam(required = false) @Size(max = 100) String q) {
        return searchService.suggest(actor, workspaceId, field, q);
    }
}
