package com.hamstrack.workspace.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.dto.DuplicateRoleRequest;
import com.hamstrack.workspace.dto.PreviewRoleRequest;
import com.hamstrack.workspace.dto.RoleAssignmentPreviewResponse;
import com.hamstrack.workspace.dto.RoleResponse;
import com.hamstrack.workspace.dto.RoleUsageResponse;
import com.hamstrack.workspace.dto.UpdateRoleRequest;
import com.hamstrack.workspace.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The custom-role editor (HD-127, S4). All paths are workspace-scoped, and every one
 * resolves membership <strong>before</strong> any permission is evaluated — so an unknown
 * workspace and a non-member are one indistinguishable <strong>404</strong>, and a
 * <strong>403</strong> is reachable only by a proven member.
 *
 * <p><strong>Gating, in one table:</strong>
 * <ul>
 *   <li>{@code GET /roles} — any member. Role names are needed by every member for display;
 *       the sensitive half is {@code includeUsage}, which additionally requires
 *       {@code workspace.role.manage} (403 otherwise).</li>
 *   <li>{@code GET /roles/{id}} — any member.</li>
 *   <li>everything else — {@code workspace.role.manage}. Deliberately separate from
 *       {@code workspace.member.manage}: defining what a role may do is strictly more
 *       dangerous than handing out an existing one.</li>
 * </ul>
 *
 * <p><strong>There is no {@code POST /roles}.</strong> Creation is duplication only — see
 * {@link DuplicateRoleRequest} for why that is deliberate.
 *
 * <p><strong>404 vs 422 on a role id is a rule, not a taste.</strong> Every id in a
 * <em>path</em> here (including the duplicate source) answers 404 when this workspace cannot
 * address it; every id in a <em>body</em> or query <em>value</em>
 * ({@code reassignToRoleId}) answers 422. See {@code RoleNotFoundException}.
 *
 * <p><strong>Every write can also answer 409 with {@code Retry-After}</strong> — a lock-wait
 * timeout, bounded by {@code app.locking.lock-timeout-ms}. It is separable from the other
 * 409s by that header (they carry an {@code errorType} instead). See {@code LockTimeout}.
 *
 * <p><strong>Where the refusals are documented: on {@link RoleService}, not here.</strong>
 * Each method below states its success code and its permission gate — the two facts that are
 * genuinely about the HTTP surface — and points at the service method for <em>which</em>
 * conditions produce a 403/404/409/422 and why. That list was previously duplicated here and
 * drifted behind the service twice in three review rounds, once per guard added; a second
 * list that disagrees with the first is worse than no second list, because a reader cannot
 * tell which one is stale. Keep this file free of refusal enumerations.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /**
     * 200 · any member; {@code includeUsage=true} additionally needs
     * {@code workspace.role.manage} · refusals: {@link RoleService#list}.
     *
     * @param scope optional; both scopes when absent
     */
    @GetMapping
    public List<RoleResponse> list(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @RequestParam(required = false) RoleScope scope,
                                   @RequestParam(defaultValue = "false") boolean includeUsage) {
        return roleService.list(actor, workspaceId, scope, includeUsage);
    }

    /** 200 · any member · refusals: {@link RoleService#get}. */
    @GetMapping("/{roleId}")
    public RoleResponse get(@AuthenticationPrincipal User actor,
                            @PathVariable UUID workspaceId,
                            @PathVariable UUID roleId) {
        return roleService.get(actor, workspaceId, roleId);
    }

    /**
     * Duplicate a role — <strong>the only way to create one</strong>.
     *
     * <p>201 · {@code workspace.role.manage} · refusals: {@link RoleService#duplicate}.
     */
    @PostMapping("/{sourceRoleId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse duplicate(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @PathVariable UUID sourceRoleId,
                                  @Valid @RequestBody DuplicateRoleRequest req) {
        return roleService.duplicate(actor, workspaceId, sourceRoleId, req);
    }

    /**
     * Rename, re-describe or re-compose a custom role.
     *
     * <p>200 · {@code workspace.role.manage} · refusals: {@link RoleService#update}.
     */
    @PatchMapping("/{roleId}")
    public RoleResponse update(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId,
                               @PathVariable UUID roleId,
                               @Valid @RequestBody UpdateRoleRequest req) {
        return roleService.update(actor, workspaceId, roleId, req);
    }

    /**
     * Delete a custom role, optionally moving every holder to another one.
     *
     * <p>204 · {@code workspace.role.manage} · refusals: {@link RoleService#delete}.
     *
     * @param reassignToRoleId optional; a <em>value</em>, so an unusable one is 422, not 404
     */
    @DeleteMapping("/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID roleId,
                       @RequestParam(required = false) UUID reassignToRoleId) {
        roleService.delete(actor, workspaceId, roleId, reassignToRoleId);
    }

    /** 200 · {@code workspace.role.manage} · refusals: {@link RoleService#usage}. */
    @GetMapping("/{roleId}/usage")
    public RoleUsageResponse usage(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId,
                                   @PathVariable UUID roleId) {
        return roleService.usage(actor, workspaceId, roleId);
    }

    /**
     * Dry-run the derived assignment block for a permission list — <strong>persists
     * nothing</strong>.
     *
     * <p>Mapped <em>before</em> {@code /{roleId}} would be a concern only if {@code preview}
     * were a valid UUID; it is not, so {@code @PathVariable UUID} never matches it and
     * Spring's pattern comparator prefers the literal segment regardless.
     *
     * <p>200 · {@code workspace.role.manage} · refusals: {@link RoleService#preview}.
     */
    @PostMapping("/preview")
    public RoleAssignmentPreviewResponse preview(@AuthenticationPrincipal User actor,
                                                 @PathVariable UUID workspaceId,
                                                 @Valid @RequestBody PreviewRoleRequest req) {
        return roleService.preview(actor, workspaceId, req);
    }
}
