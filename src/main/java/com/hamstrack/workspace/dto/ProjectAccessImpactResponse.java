package com.hamstrack.workspace.dto;

import com.hamstrack.project.dto.ProjectRef;
import com.hamstrack.workspace.entity.ProjectAccessMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * <strong>What a project-access change would do</strong> (HD-130, S7 §4) — the body of
 * {@code POST /api/workspaces/{ws}/project-access/preview}, and the {@code impact} block of
 * {@code GET …/project-access} computed for the current state.
 *
 * <p><strong>Counts are advisory; refusals are authoritative.</strong> Flipping to
 * {@code STRICT} on a live workspace and silently removing access from people who had it is
 * the failure this epic exists to prevent, so every number here has a definition a reviewer
 * can check against a query — but only one of them is a guarantee. {@link #strandedProjects}
 * is <em>re-derived inside the write's transaction</em> and enforced there, whether or not
 * the caller ever previewed; a client that skips the preview gets the same 409. The other
 * four are a snapshot of a population — {@code workspace_members} × {@code project_members} —
 * that is not the row being written, so no optimistic check on the workspace could make them
 * exact. There is deliberately no token, no echo and no {@code expectedCount} field: inventing
 * one would be ceremony that <em>looks</em> like a guarantee (§4.3).
 *
 * <p><strong>One implementation, two entry points.</strong> The preview endpoint and the write
 * call the same {@code ProjectAccessImpact.of}, so the numbers cannot drift from the rule —
 * only from time. A second count written for the preview would be the HD-98/HD-116 bug class
 * with an audience.
 *
 * <p><strong>How many, never who.</strong> The response names projects
 * ({@link ProjectRef} — id/key/name of the caller's own workspace, the same payload
 * {@code StrandedProjectsException} already ships) and no people at all: no names, no emails,
 * no user ids. A per-person list would be a workspace-wide access report on an endpoint gated
 * by one permission (§8).
 *
 * @param computedAt          when this snapshot was taken. The SPA re-fetches on opening the
 *                            confirm dialog and never renders a preview older than that
 *                            interaction
 * @param from                the workspace's state now
 * @param to                  the state the previewed body would produce
 * @param activeMembers       ACTIVE members of the workspace. DISABLED accounts are excluded
 *                            throughout: a deactivated account cannot log in, so it is not a
 *                            person who loses access
 * @param projects            live (non-archived) projects of the workspace
 * @param projectsWithNoExplicitMembers how many of those nobody has ever been added to
 * @param projectsWithNoWriters <strong>the honest headline.</strong> Live projects where,
 *                            after the change, <em>nobody at all</em> holds
 *                            {@code issue.create} — no explicit member's role grants it and
 *                            the fallback is gone. It replaces the epic's "only workspace
 *                            Owners/Admins will be able to change anything there", which is
 *                            false: Owner and Admin hold {@code project.curate.all} —
 *                            {@code project.edit}, {@code component.manage},
 *                            {@code version.manage}, {@code sprint.manage} — and no issue or
 *                            comment permission, so in a restricted project with no explicit
 *                            members nobody can file or edit an issue, <strong>including the
 *                            Owner</strong>. A warning and not a refusal (§14 OQ 3): a
 *                            workspace may legitimately restrict a project nobody is working in
 * @param perProject          one row per live project, in key order
 * @param strandedProjects    the projects this write will <strong>409</strong> on — they have
 *                            administrators only by inheritance and the change takes it away
 *                            (§5.3). Empty is the normal answer
 */
public record ProjectAccessImpactResponse(
        Instant computedAt,
        State from,
        State to,
        long activeMembers,
        int projects,
        int projectsWithNoExplicitMembers,
        int projectsWithNoWriters,
        List<ProjectImpact> perProject,
        List<ProjectRef> strandedProjects
) {

    /**
     * A workspace's project-access configuration, on either side of the change.
     *
     * <p><strong>Ids, never names</strong> — the rule {@code DefaultRoleResponse} carries at
     * length. A client resolves the id against {@code GET /roles}, which is workspace-scoped
     * and therefore cannot return a foreign role, and renders a placeholder when it finds
     * nothing. {@code null} means the built-in Contributor.
     */
    public record State(ProjectAccessMode mode, UUID defaultProjectRoleId) {
    }

    /**
     * @param membersOnDefault        ACTIVE workspace members with no explicit
     *                                {@code project_members} row in this project — the people
     *                                the mode is about
     * @param explicitMembers         ACTIVE members who do have one, and are therefore
     *                                unaffected
     * @param membersLosingEverything of the members on the default, those whose workspace role
     *                                grants <strong>neither</strong> {@code project.curate.all}
     *                                <strong>nor</strong> {@code project.administer.all} — i.e.
     *                                who would hold the empty set here afterwards
     * @param noWritersAfter          whether this project would be one of the
     *                                {@code projectsWithNoWriters}
     */
    public record ProjectImpact(
            UUID id,
            String key,
            String name,
            long membersOnDefault,
            long explicitMembers,
            long membersLosingEverything,
            boolean noWritersAfter
    ) {
    }
}
