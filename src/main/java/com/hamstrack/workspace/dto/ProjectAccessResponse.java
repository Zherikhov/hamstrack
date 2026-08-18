package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.ProjectAccessMode;

import java.util.UUID;

/**
 * <strong>{@code GET /api/workspaces/{ws}/project-access}</strong> (HD-130, S7 §7.1 W1) —
 * one request that powers the whole Workspace settings → General page: the current mode, the
 * current declared default, which roles the caller may set it to, and what the workspace
 * looks like right now.
 *
 * @param mode                 the workspace's {@link ProjectAccessMode}
 * @param defaultProjectRoleId the declared workspace default; {@code null} means the built-in
 *                             Contributor. An <strong>id, never a name</strong> — see
 *                             {@code DefaultRoleResponse}, which carries the rule and the
 *                             reason a name would have to go through
 *                             {@code resolveRoleOrDegrade} or not be emitted at all
 * @param settable             the grant ceiling, rendered: which PROJECT roles this actor may
 *                             make the workspace default, and the first missing permission for
 *                             each one they may not
 * @param impact               {@link ProjectAccessImpactResponse} for the <em>current</em>
 *                             state, so the page can say what the workspace looks like before
 *                             anything is proposed. The confirm dialog re-fetches
 *                             {@code POST …/preview} for the proposed state rather than
 *                             reusing this
 */
public record ProjectAccessResponse(
        ProjectAccessMode mode,
        UUID defaultProjectRoleId,
        SettableRolesView settable,
        ProjectAccessImpactResponse impact
) {
}
