package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.ProjectAccessMode;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.service.RoleView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param myRole        the caller's workspace role <em>key</em>. <strong>Display
 *                      only</strong> — no UI gate may read it, because
 *                      {@code myRole === 'ADMIN'} cannot express a custom role and is
 *                      therefore wrong by construction the moment custom roles exist
 *                      (§5.3); S5 removed the last SPA gate that did. A plain string
 *                      since HD-126 (S3) deleted the ordinal enum, with the identical
 *                      wire values the enum serialised as.
 *                      <p><strong>{@code null} on the LIST endpoint only</strong>, when
 *                      the membership's {@code role_id} fails the scope/ownership
 *                      assertion (HD-127 §3b): the entry stays so the caller can still see
 *                      their workspace, and the role we just refused is emphatically not
 *                      rendered in its place.
 * @param myPermissions the caller's effective workspace permissions, as flat keys
 *                      ({@code ["workspace.edit", "label.manage:own"]} — §6.4). This is
 *                      the <strong>only</strong> permitted input to a UI gate. It rides a
 *                      response the SPA already fetches, so gating costs zero extra
 *                      requests, and it is <strong>never absent</strong>: an empty list
 *                      is a real answer, a missing field would make a client fall back to
 *                      permissive rendering (§12). A degraded row yields {@code []}, which
 *                      is the floor and can only narrow.
 * @param projectAccessMode    whether members who were never added to a project inherit its
 *                      default role (HD-130, S7 §6). <strong>This is the only place it is
 *                      published.</strong> It is a workspace-level fact with one source of
 *                      truth, and the project People card reads it from the
 *                      {@code ['workspace', wsId]} query every surface already caches;
 *                      mirroring it onto {@code DefaultRoleResponse} would be a second copy
 *                      that can then disagree with the first, for no request saved.
 * @param defaultProjectRoleId the workspace's declared default project role, or {@code null}
 *                      for the built-in Contributor. A raw <strong>id, never a name</strong> —
 *                      the rule {@code DefaultRoleResponse}'s javadoc carries: an id is not a
 *                      name, a client resolves it against the workspace-scoped
 *                      {@code GET /roles} and renders a placeholder if it finds nothing, and
 *                      the assertion that decides access still runs in
 *                      {@code WorkspaceAccessService.defaultProjectRole} on the value that
 *                      actually matters. Emitting a name here would reach every member of the
 *                      workspace
 */
public record WorkspaceResponse(
        UUID id,
        String slug,
        String name,
        ProjectAccessMode projectAccessMode,
        UUID defaultProjectRoleId,
        String myRole,
        List<String> myPermissions,
        Instant createdAt
) {
    public static WorkspaceResponse of(Workspace w, RoleView role) {
        return new WorkspaceResponse(w.getId(), w.getSlug(), w.getName(),
                w.getProjectAccessMode(), w.getDefaultProjectRoleId(),
                role.key(), role.permissions().asWireStrings(), w.getCreatedAt());
    }

    /**
     * The degraded form — the workspace, with no claim about what the caller may do in it.
     *
     * <p>Reachable only from {@code WorkspaceService.listForUser}. The detail read
     * ({@code GET /workspaces/{id}}) still 404s the same row on purpose: <em>a list is a
     * directory, a detail read is an authorization</em>, and degrading the caller's own
     * single-resource resolution would leave them inheriting Contributor in every project
     * of an OPEN workspace rather than nothing.
     */
    public static WorkspaceResponse degraded(Workspace w) {
        // The access mode and the declared default are properties of the WORKSPACE, not
        // claims about the caller, so they survive the degrade: what is withheld is the role
        // the assertion just refused and any permission derived from it.
        return new WorkspaceResponse(w.getId(), w.getSlug(), w.getName(),
                w.getProjectAccessMode(), w.getDefaultProjectRoleId(),
                null, List.of(), w.getCreatedAt());
    }
}
