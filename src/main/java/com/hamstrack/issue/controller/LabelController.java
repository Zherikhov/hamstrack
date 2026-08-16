package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateLabelRequest;
import com.hamstrack.issue.dto.LabelResponse;
import com.hamstrack.issue.dto.LabelUsageResponse;
import com.hamstrack.issue.dto.MergeLabelsRequest;
import com.hamstrack.issue.dto.MergeLabelsResponse;
import com.hamstrack.issue.dto.UpdateLabelRequest;
import com.hamstrack.issue.service.LabelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Workspace labels (HD-30) — free-form, colored tags reusable across every project of
 * the workspace. Labels are <em>content</em>, not bound taxonomy: they never appear in
 * {@code GET …/projects/{p}/config} and have their own cache key in the SPA (§3.2).
 *
 * <p>Every endpoint is gated by workspace membership: a missing workspace or a
 * non-member both yield <strong>404</strong> (never 403 — no existence leak, the
 * project's #1 bug class). <strong>403</strong> is reserved for a member who lacks the
 * curation role.
 *
 * <ul>
 *   <li>{@code GET    …/labels?includeArchived=&withUsage=} — any member;</li>
 *   <li>{@code POST   …/labels} — any member (self-serve); 409 + {@code existingId}
 *       on a duplicate name (case-insensitive, archived rows included);</li>
 *   <li>{@code PATCH  …/labels/{id}} — OWNER/ADMIN or the label's creator;</li>
 *   <li>{@code POST   …/labels/{id}/archive|unarchive} — OWNER/ADMIN;</li>
 *   <li>{@code POST   …/labels/{id}/merge} — OWNER/ADMIN; sources merge INTO {id};</li>
 *   <li>{@code DELETE …/labels/{id}?force=} — OWNER/ADMIN; 409 when in use without
 *       {@code force};</li>
 *   <li>{@code GET    …/labels/{id}/usage} — any member.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/labels")
@RequiredArgsConstructor
public class LabelController {

    private final LabelService labelService;

    @GetMapping
    public List<LabelResponse> list(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @RequestParam(defaultValue = "false") boolean includeArchived,
                                    @RequestParam(defaultValue = "false") boolean withUsage) {
        return labelService.list(actor, workspaceId, includeArchived, withUsage);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LabelResponse create(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId,
                                @Valid @RequestBody CreateLabelRequest req) {
        return labelService.create(actor, workspaceId, req);
    }

    @PatchMapping("/{labelId}")
    public LabelResponse update(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId,
                                @PathVariable UUID labelId,
                                @Valid @RequestBody UpdateLabelRequest req) {
        return labelService.update(actor, workspaceId, labelId, req);
    }

    /** Archive: always allowed, even in use — the recommended alternative to delete. */
    @PostMapping("/{labelId}/archive")
    public LabelResponse archive(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId,
                                 @PathVariable UUID labelId) {
        return labelService.archive(actor, workspaceId, labelId);
    }

    @PostMapping("/{labelId}/unarchive")
    public LabelResponse unarchive(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @PathVariable UUID labelId) {
        return labelService.unarchive(actor, workspaceId, labelId);
    }

    /**
     * Merge the request's {@code sourceIds} INTO {@code labelId}: every issue carrying
     * a source ends up carrying the target, duplicates collapse, and the source rows
     * are deleted. 422 when a source equals the target or lives in another workspace.
     * No per-issue history is written (§4.4) — the response is the record.
     */
    @PostMapping("/{labelId}/merge")
    public MergeLabelsResponse merge(@AuthenticationPrincipal User actor,
                                     @PathVariable UUID workspaceId,
                                     @PathVariable UUID labelId,
                                     @Valid @RequestBody MergeLabelsRequest req) {
        return labelService.merge(actor, workspaceId, labelId, req);
    }

    @DeleteMapping("/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID labelId,
                       @RequestParam(defaultValue = "false") boolean force) {
        labelService.delete(actor, workspaceId, labelId, force);
    }

    @GetMapping("/{labelId}/usage")
    public LabelUsageResponse usage(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID labelId) {
        return labelService.usage(actor, workspaceId, labelId);
    }
}
