package com.hamstrack.workspace.service;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.ProjectAccessMode;
import com.hamstrack.workspace.entity.Workspace;

import java.util.UUID;

/**
 * <strong>The post-state of a project-access write</strong> (HD-130, S7 §4.2) — mode +
 * workspace default + optionally one project's override — as a value the impact preview,
 * the write and the stranding guard can all be handed.
 *
 * <p>It exists so that the three of them cannot disagree. The preview endpoint and the
 * write call the <em>same</em> {@code ProjectAccessImpact.of}, and doors 7–9 call the same
 * {@code ProjectAdminGuard.projectsLosingLastAdministrator}, with the same proposal — so
 * the numbers a user was shown and the refusal they then receive are computed from one
 * description of what they asked for. A second, endpoint-local copy of "what the state
 * would be" is the HD-98/HD-116 bug class with an audience (§4.3).
 *
 * <p><strong>It is a description, never an authorization.</strong> Nothing here has been
 * validated: {@code workspaceDefaultRoleId} and {@code projectDefaultRoleId} must already
 * have passed {@code RoleCatalog.requireAssignable(PROJECT, workspaceId, id)} (422) and the
 * grant ceiling (403) before a proposal is built from them.
 *
 * @param mode                  the access mode <em>after</em> the write. Read by
 *                              {@link WorkspaceAccessService#defaultProjectRoleUnder}, which
 *                              is what makes doors 7–9 one-way: a change that widens (or a
 *                              flip back to {@code OPEN}) can never strand
 * @param workspaceDefaultRoleId {@code workspaces.default_project_role_id} after the write;
 *                              {@code null} means "fall through to the built-in Contributor"
 * @param projectId             the one project whose own default this write changes, or
 *                              {@code null} for a workspace-level write. There is
 *                              deliberately no way to express several: no endpoint changes
 *                              more than one project override at a time
 * @param projectDefaultRoleId  that project's {@code default_project_role_id} after the
 *                              write; {@code null} means "inherit the workspace default",
 *                              which is a real, distinct choice and not "unset"
 */
public record ProjectAccessProposal(
        ProjectAccessMode mode,
        UUID workspaceDefaultRoleId,
        UUID projectId,
        UUID projectDefaultRoleId
) {

    /** The workspace switch and the workspace picker (doors 7 and 8). */
    public static ProjectAccessProposal workspace(ProjectAccessMode mode, UUID workspaceDefaultRoleId) {
        return new ProjectAccessProposal(mode, workspaceDefaultRoleId, null, null);
    }

    /**
     * One project's own default (door 9), against the workspace's <em>current</em> mode and
     * default — neither of which this write touches.
     */
    public static ProjectAccessProposal project(Workspace workspace, UUID projectId,
                                                UUID projectDefaultRoleId) {
        return new ProjectAccessProposal(workspace.getProjectAccessMode(),
                workspace.getDefaultProjectRoleId(), projectId, projectDefaultRoleId);
    }

    /** The workspace exactly as it is — the "no change" proposal the W1 read previews. */
    public static ProjectAccessProposal current(Workspace workspace) {
        return workspace(workspace.getProjectAccessMode(), workspace.getDefaultProjectRoleId());
    }

    /**
     * The project-level half of the §5.2 chain for {@code project} under this proposal: its
     * own override if this write sets one, otherwise the override it already carries.
     *
     * <p>Reading {@code project.getDefaultProjectRoleId()} for every <em>other</em> project
     * is the point — a workspace-level write leaves per-project overrides standing, so a
     * project that names its own default is unaffected by a change to the workspace's
     * (§5.3, door 8: "for every project that does not override it").
     */
    public UUID defaultRoleIdFor(Project project) {
        return project.getId().equals(projectId) ? projectDefaultRoleId : project.getDefaultProjectRoleId();
    }
}
