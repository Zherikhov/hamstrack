package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateVersionRequest;
import com.hamstrack.issue.dto.ReleaseVersionRequest;
import com.hamstrack.issue.dto.UpdateVersionRequest;
import com.hamstrack.issue.dto.VersionResponse;
import com.hamstrack.issue.dto.VersionUsageResponse;
import com.hamstrack.issue.service.VersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project versions (HD-32) — release targets of one project with a reversible
 * lifecycle, linked to issues as <em>fix</em> and/or <em>affects</em> versions.
 * Versions are <em>content</em>, not bound taxonomy: they never appear in
 * {@code GET …/projects/{p}/config} and have their own cache key in the SPA (§3.2).
 *
 * <p>Every endpoint resolves through workspace→project membership: a missing
 * workspace, a missing project or a non-member all yield <strong>404</strong> (never
 * 403 — no existence leak, the project's #1 bug class). <strong>403</strong> is
 * reserved for a member who lacks the curation role (project MANAGER, or workspace
 * OWNER/ADMIN — {@code ScopeResolver.requireProjectCurator}).
 *
 * <ul>
 *   <li>{@code GET    …/versions?includeArchived=&includeReleased=} — any project
 *       member; the Releases page's data source, so per-version progress
 *       ({@code issueCount}/{@code doneIssueCount}/{@code affectsIssueCount}) is
 *       always included — it costs ONE grouped query for the whole list;</li>
 *   <li>{@code POST   …/versions} — curator; 409 on a duplicate name
 *       (case-insensitive within the project, archived rows included);</li>
 *   <li>{@code GET    …/versions/{id}} — any project member;</li>
 *   <li>{@code PATCH  …/versions/{id}} — curator (name/description/release date
 *       only; the lifecycle moves through release/unrelease);</li>
 *   <li>{@code POST   …/versions/{id}/release} — curator; 409 when already released;
 *       optional {@code moveUnresolvedToVersionId};</li>
 *   <li>{@code POST   …/versions/{id}/unrelease} — curator; 409 when not released;
 *       preserves {@code releaseDate};</li>
 *   <li>{@code POST   …/versions/{id}/archive|unarchive} — curator;</li>
 *   <li>{@code DELETE …/versions/{id}?force=&remapToId=} — curator; 409 when linked
 *       to issues without either escape hatch;</li>
 *   <li>{@code GET    …/versions/{id}/usage} — any project member.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/versions")
@RequiredArgsConstructor
public class VersionController {

    private final VersionService versionService;

    /**
     * The Releases page list. {@code includeReleased} defaults to <strong>true</strong>
     * (a release page without its shipped releases is useless); {@code includeArchived}
     * defaults to false, like every other catalog in this epic. Ordered unreleased
     * first, then by planned date (undated last), then by name.
     */
    @GetMapping
    public List<VersionResponse> list(@AuthenticationPrincipal User actor,
                                      @PathVariable UUID workspaceId,
                                      @PathVariable UUID projectId,
                                      @RequestParam(defaultValue = "false") boolean includeArchived,
                                      @RequestParam(defaultValue = "true") boolean includeReleased) {
        return versionService.list(actor, workspaceId, projectId, includeArchived, includeReleased);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse create(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @PathVariable UUID projectId,
                                  @Valid @RequestBody CreateVersionRequest req) {
        return versionService.create(actor, workspaceId, projectId, req);
    }

    @GetMapping("/{versionId}")
    public VersionResponse get(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId,
                               @PathVariable UUID projectId,
                               @PathVariable UUID versionId) {
        return versionService.get(actor, workspaceId, projectId, versionId);
    }

    @PatchMapping("/{versionId}")
    public VersionResponse update(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @PathVariable UUID projectId,
                                  @PathVariable UUID versionId,
                                  @Valid @RequestBody UpdateVersionRequest req) {
        return versionService.update(actor, workspaceId, projectId, versionId, req);
    }

    /**
     * Release. Idempotent-looking success is deliberately NOT offered: a second call
     * is a <strong>409</strong>, because it would otherwise hide a double-click that
     * runs the destructive {@code moveUnresolvedToVersionId} twice (§6.4).
     *
     * <p>The body is optional — {@code POST} with no body means "release it now, keep
     * the planned date (or stamp today if there is none)".
     */
    @PostMapping("/{versionId}/release")
    public VersionResponse release(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @PathVariable UUID projectId,
                                   @PathVariable UUID versionId,
                                   @Valid @RequestBody(required = false) ReleaseVersionRequest req) {
        return versionService.release(actor, workspaceId, projectId, versionId, req);
    }

    /**
     * Un-release — the story's reversibility criterion. Clears {@code released} and
     * {@code releasedAt} but keeps {@code releaseDate} (it stays the plan), so nothing
     * is lost. 409 when the version is not released.
     */
    @PostMapping("/{versionId}/unrelease")
    public VersionResponse unrelease(@AuthenticationPrincipal User actor,
                                     @PathVariable UUID workspaceId,
                                     @PathVariable UUID projectId,
                                     @PathVariable UUID versionId) {
        return versionService.unrelease(actor, workspaceId, projectId, versionId);
    }

    /** Archive: always allowed, even in use — the recommended alternative to delete. */
    @PostMapping("/{versionId}/archive")
    public VersionResponse archive(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @PathVariable UUID projectId,
                                   @PathVariable UUID versionId) {
        return versionService.archive(actor, workspaceId, projectId, versionId);
    }

    @PostMapping("/{versionId}/unarchive")
    public VersionResponse unarchive(@AuthenticationPrincipal User actor,
                                     @PathVariable UUID workspaceId,
                                     @PathVariable UUID projectId,
                                     @PathVariable UUID versionId) {
        return versionService.unarchive(actor, workspaceId, projectId, versionId);
    }

    /**
     * Delete. Linked to issues → 409 unless {@code force=true} (drop the links) or
     * {@code remapToId} (re-point them, both roles, to another version of the same
     * project — 422 if that version is archived, foreign or the one being deleted).
     * {@code remapToId} wins when both are supplied: it is the more specific,
     * non-destructive intent.
     */
    @DeleteMapping("/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID projectId,
                       @PathVariable UUID versionId,
                       @RequestParam(defaultValue = "false") boolean force,
                       @RequestParam(required = false) UUID remapToId) {
        versionService.delete(actor, workspaceId, projectId, versionId, force, remapToId);
    }

    @GetMapping("/{versionId}/usage")
    public VersionUsageResponse usage(@AuthenticationPrincipal User actor,
                                      @PathVariable UUID workspaceId,
                                      @PathVariable UUID projectId,
                                      @PathVariable UUID versionId) {
        return versionService.usage(actor, workspaceId, projectId, versionId);
    }
}
