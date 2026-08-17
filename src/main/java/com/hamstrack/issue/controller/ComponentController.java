package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.ComponentResponse;
import com.hamstrack.issue.dto.ComponentUsageResponse;
import com.hamstrack.issue.dto.CreateComponentRequest;
import com.hamstrack.issue.dto.UpdateComponentRequest;
import com.hamstrack.issue.service.ComponentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project components (HD-31) — curated modules of one project with an optional lead
 * and an optional create-time auto-assign. Components are <em>content</em>, not bound
 * taxonomy: they never appear in {@code GET …/projects/{p}/config} and have their own
 * cache key in the SPA (§3.2).
 *
 * <p>Every endpoint resolves through workspace→project membership: a missing
 * workspace, a missing project or a non-member all yield <strong>404</strong> (never
 * 403 — no existence leak, the project's #1 bug class). <strong>403</strong> is
 * reserved for a member who lacks the permission — {@code component.manage}, held by
 * the built-in project MANAGER and, in every project of their workspace, by a workspace
 * OWNER/ADMIN through {@code project.curate.all} (HD-123 §10.2).
 *
 * <ul>
 *   <li>{@code GET    …/components?includeArchived=&withUsage=} — any project member;</li>
 *   <li>{@code POST   …/components} — curator; 409 on a duplicate name
 *       (case-insensitive within the project, archived rows included);</li>
 *   <li>{@code GET    …/components/{id}} — any project member;</li>
 *   <li>{@code PATCH  …/components/{id}} — curator;</li>
 *   <li>{@code POST   …/components/{id}/archive|unarchive} — curator;</li>
 *   <li>{@code DELETE …/components/{id}?force=} — curator; 409 when in use without
 *       {@code force}, otherwise the component is nulled on every affected issue;</li>
 *   <li>{@code GET    …/components/{id}/usage} — any project member.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/components")
@RequiredArgsConstructor
public class ComponentController {

    private final ComponentService componentService;

    @GetMapping
    public List<ComponentResponse> list(@AuthenticationPrincipal User actor,
                                        @PathVariable UUID workspaceId,
                                        @PathVariable UUID projectId,
                                        @RequestParam(defaultValue = "false") boolean includeArchived,
                                        @RequestParam(defaultValue = "false") boolean withUsage) {
        return componentService.list(actor, workspaceId, projectId, includeArchived, withUsage);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentResponse create(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID projectId,
                                    @Valid @RequestBody CreateComponentRequest req) {
        return componentService.create(actor, workspaceId, projectId, req);
    }

    @GetMapping("/{componentId}")
    public ComponentResponse get(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId,
                                 @PathVariable UUID projectId,
                                 @PathVariable UUID componentId) {
        return componentService.get(actor, workspaceId, projectId, componentId);
    }

    @PatchMapping("/{componentId}")
    public ComponentResponse update(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID projectId,
                                    @PathVariable UUID componentId,
                                    @Valid @RequestBody UpdateComponentRequest req) {
        return componentService.update(actor, workspaceId, projectId, componentId, req);
    }

    /** Archive: always allowed, even in use — the recommended alternative to delete. */
    @PostMapping("/{componentId}/archive")
    public ComponentResponse archive(@AuthenticationPrincipal User actor,
                                     @PathVariable UUID workspaceId,
                                     @PathVariable UUID projectId,
                                     @PathVariable UUID componentId) {
        return componentService.archive(actor, workspaceId, projectId, componentId);
    }

    @PostMapping("/{componentId}/unarchive")
    public ComponentResponse unarchive(@AuthenticationPrincipal User actor,
                                       @PathVariable UUID workspaceId,
                                       @PathVariable UUID projectId,
                                       @PathVariable UUID componentId) {
        return componentService.unarchive(actor, workspaceId, projectId, componentId);
    }

    /**
     * Delete. In use → 409 unless {@code force=true}, which nulls the component on
     * every issue carrying it (the story's acceptance criterion) and then drops the
     * row. No per-issue history is written (§5.4) — the 409 dialog states the count.
     */
    @DeleteMapping("/{componentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID projectId,
                       @PathVariable UUID componentId,
                       @RequestParam(defaultValue = "false") boolean force) {
        componentService.delete(actor, workspaceId, projectId, componentId, force);
    }

    @GetMapping("/{componentId}/usage")
    public ComponentUsageResponse usage(@AuthenticationPrincipal User actor,
                                        @PathVariable UUID workspaceId,
                                        @PathVariable UUID projectId,
                                        @PathVariable UUID componentId) {
        return componentService.usage(actor, workspaceId, projectId, componentId);
    }
}
