package com.hamstrack.workspace.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.dto.WorkspaceStorageByProjectResponse;
import com.hamstrack.workspace.dto.WorkspaceStorageResponse;
import com.hamstrack.workspace.service.WorkspaceStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * How much attachment storage a workspace occupies, and where it went (HD-191 §8.1, §8.2).
 *
 * <p>Two reads with deliberately different gates, and the asymmetry is the design rather than an
 * accident:
 *
 * <ul>
 *   <li><strong>{@code GET /{id}/storage}</strong> — <em>any member</em>. It returns the same
 *       figures a quota refusal already hands the same person, so gating it would hide from a
 *       member the one number that tells "I am blocked" apart from "the server is broken", while
 *       disclosing nothing new: a single tenant-wide aggregate with no per-project resolution.
 *       <strong>200</strong> · <strong>401</strong> unauthenticated · <strong>404</strong> unknown
 *       workspace or non-member, indistinguishably.</li>
 *   <li><strong>{@code GET /{id}/storage/projects}</strong> — {@code workspace.edit}. Real
 *       disclosure: it names projects and their volumes, including projects the caller may not be
 *       a member of. <strong>200</strong> · <strong>403</strong> a proven member without the
 *       permission · <strong>404</strong> non-member · <strong>429</strong> + {@code Retry-After}
 *       past the reports budget.</li>
 * </ul>
 *
 * <p><strong>The breakdown is on the REPORTS budget and the summary is on none</strong>
 * ({@code ReportRateLimitConfig.STORAGE_BREAKDOWN_PATH}). The breakdown is a grouped aggregate
 * over every attachment row in the workspace — O(workspace content), unbounded by what it returns,
 * which is that budget's exact denomination. The summary is one primary-key read plus two
 * properties, cheaper than the handler mapping that routes it, and it is the figure the SPA shows
 * beside the upload control. The asymmetry runs the safe way: a caller refused on the expensive
 * read who falls back to the cheap one gets <em>less</em> information, never a way round the bound.
 *
 * <p>Separate from {@code WorkspaceController} because that class is about administering a
 * workspace and this is about measuring one; nothing here reads or writes membership, roles or
 * invitations.
 */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceStorageController {

    private final WorkspaceStorageService storageService;

    @GetMapping("/{id}/storage")
    public WorkspaceStorageResponse summary(@AuthenticationPrincipal User user,
                                            @PathVariable UUID id) {
        return storageService.summary(user, id);
    }

    @GetMapping("/{id}/storage/projects")
    public WorkspaceStorageByProjectResponse byProject(@AuthenticationPrincipal User user,
                                                       @PathVariable UUID id) {
        return storageService.byProject(user, id);
    }
}
