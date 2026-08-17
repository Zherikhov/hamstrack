package com.hamstrack.workspace.entity;

/**
 * How a workspace treats someone who has never been added to one of its projects
 * (roles-permissions-proposal §5.2, Rule P2).
 *
 * <p><strong>This is not an "enforcement off" switch, and no such switch exists.</strong>
 * Permissions always mean exactly what they say, in both modes, from day one: a project
 * Viewer is read-only the instant they are given that role either way. What the mode
 * decides is only whether people with <em>no</em> project role inherit the project's
 * default one. That framing is deliberate (§17.1) — a flag that bypassed checks would be
 * a second authorization code path, which is the same reason DC/Cloud differences must be
 * profile-gated behaviour and never forked logic.
 *
 * <p>Stored as {@code workspaces.project_access_mode VARCHAR(20)}; every existing
 * workspace is set to {@link #OPEN} by V14, which is precisely today's behaviour.
 * Flipping to {@link #STRICT} changes <strong>no data</strong>, unassigns nothing and
 * loses no read — and flipping back restores everything. That reversibility is the point.
 */
public enum ProjectAccessMode {

    /**
     * Everyone in the workspace can work in every project, using each project's default
     * role ({@code projects.default_project_role_id} → {@code workspaces
     * .default_project_role_id} → built-in Contributor). Add someone to a project only to
     * give them a <em>different</em> role.
     */
    OPEN,

    /**
     * Only people explicitly added to a project can change anything in it. Everyone can
     * still <em>see</em> every project: {@code STRICT} narrows writes only. Narrowing
     * reads would be private projects by the back door — an explicit non-goal (§12) that
     * would silently break {@code SearchScope}, Home, My Work and every cross-project
     * query.
     */
    STRICT
}
