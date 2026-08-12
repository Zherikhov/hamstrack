package com.hamstrack.search.filter.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.search.filter.dto.CreateSavedFilterRequest;
import com.hamstrack.search.filter.dto.SavedFilterResponse;
import com.hamstrack.search.filter.dto.SavedFilterUsageResponse;
import com.hamstrack.search.filter.dto.UpdateSavedFilterRequest;
import com.hamstrack.search.filter.service.SavedFilterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Saved-filter CRUD — workspace-scoped, reusable HQL data sources (HD-26, Advanced
 * Search proposal §8.3). Every endpoint is gated by workspace membership: a
 * non-member gets <strong>404</strong> (never 403 — no existence leak). The owner may
 * create/edit/delete; a filter with {@code shared=true} is visible read-only to every
 * other workspace member. A non-owner reaching a private filter, or attempting to
 * edit/delete even a shared filter, also gets 404 (§16.5 — never confirm the id).
 *
 * <ul>
 *   <li>{@code GET    …/filters} — own + shared filters in this workspace;</li>
 *   <li>{@code POST   …/filters} — create (owner = caller); HQL validated at save time
 *       (422 on parse/semantic error), name unique per (workspace, owner) (409);</li>
 *   <li>{@code GET    …/filters/{id}} — own or shared;</li>
 *   <li>{@code PATCH  …/filters/{id}} — owner only; partial; re-validates changed HQL;</li>
 *   <li>{@code GET    …/filters/{id}/usage} — delete-warning hook (empty in MVP, §10.4);</li>
 *   <li>{@code DELETE …/filters/{id}} — owner only.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/filters")
@RequiredArgsConstructor
public class SavedFilterController {

    private final SavedFilterService savedFilterService;

    @GetMapping
    public List<SavedFilterResponse> list(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId) {
        return savedFilterService.list(actor, workspaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedFilterResponse create(@AuthenticationPrincipal User actor,
                                      @PathVariable UUID workspaceId,
                                      @Valid @RequestBody CreateSavedFilterRequest req) {
        return savedFilterService.create(actor, workspaceId, req);
    }

    @GetMapping("/{filterId}")
    public SavedFilterResponse get(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @PathVariable UUID filterId) {
        return savedFilterService.get(actor, workspaceId, filterId);
    }

    @PatchMapping("/{filterId}")
    public SavedFilterResponse update(@AuthenticationPrincipal User actor,
                                      @PathVariable UUID workspaceId,
                                      @PathVariable UUID filterId,
                                      @Valid @RequestBody UpdateSavedFilterRequest req) {
        return savedFilterService.update(actor, workspaceId, filterId, req);
    }

    /**
     * Delete-with-usage warning hook (§10.4). Returns the places that would block a
     * delete; always empty in MVP so the SPA can build the confirm-dialog flow now.
     * Owner-only, so it matches {@code DELETE}'s authorization.
     */
    @GetMapping("/{filterId}/usage")
    public SavedFilterUsageResponse usage(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId,
                                          @PathVariable UUID filterId) {
        return savedFilterService.usage(actor, workspaceId, filterId);
    }

    @DeleteMapping("/{filterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID filterId) {
        savedFilterService.delete(actor, workspaceId, filterId);
    }
}
