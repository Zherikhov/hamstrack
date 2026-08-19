package com.hamstrack.workspace.service;

import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.workspace.dto.ProjectAccessImpactResponse;
import com.hamstrack.workspace.dto.ProjectAccessImpactResponse.ProjectImpact;
import com.hamstrack.workspace.dto.ProjectAccessImpactResponse.State;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>What a project-access change would do, counted once</strong> (HD-130, S7 §4).
 *
 * <p>{@link #of(Workspace, ProjectAccessProposal)} is called from exactly two places — the
 * preview endpoint and {@code GET …/project-access} — and the numbers it produces are
 * <em>advisory</em>: a snapshot of {@code workspace_members} × {@code project_members}, which
 * is not the row being written, so no optimistic check could make them exact. The one number
 * that must be exact, {@code strandedProjects}, is re-derived by
 * {@code ProjectAdminGuard.projectsLosingLastAdministrator} inside the write's own transaction
 * and enforced there whether or not the caller ever previewed (§4.3).
 *
 * <p><strong>Four statements, independent of project count</strong> (§4.2, AC 28):
 * <ol>
 *   <li>the workspace's live projects with their {@code default_project_role_id};</li>
 *   <li>ACTIVE workspace members grouped by workspace role
 *       ({@link WorkspaceMemberRepository#countActiveMembersByRole} — an ACTIVE-filtered
 *       sibling of the S4 usage count, which deliberately counts everybody because a role held
 *       by a deactivated member is still <em>in use</em>; a deactivated account is not a person
 *       who <em>loses access</em>);</li>
 *   <li>ACTIVE explicit project memberships grouped by (project, project role, workspace role)
 *       ({@link ProjectMemberRepository#countActiveExplicitMembersByRolePair});</li>
 *   <li>{@code RoleRepository.findIdsGranting(ws, PROJECT, project.member.manage)}, inside the
 *       guard, feeding §5.</li>
 * </ol>
 * Everything else is arithmetic over cached {@code RoleView}s. Per-candidate
 * {@code existsActiveMemberWithoutProjectRole} calls happen only for stranding candidates, of
 * which there are <strong>zero</strong> in the shipped configuration (§5.4). A workspace with
 * 200 projects costs the same four statements as one with two.
 *
 * <p><strong>How many, never who.</strong> Nothing here reads a user's name, email or id: the
 * three aggregates return role ids and counts. The response answers <em>how many</em>, which
 * is what a decision needs, and a per-person list would be a workspace-wide access report on
 * an endpoint gated by one permission (§8).
 *
 * <p><strong>Every read carries a workspace filter.</strong> Built-in roles are shared rows
 * ({@code workspace_id IS NULL}), so an unfiltered {@code GROUP BY role_id} would count another
 * tenant's headcount — in a class that counts people for a living.
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessImpact {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccessService workspaceAccess;
    private final ProjectAdminGuard projectAdminGuard;

    /**
     * @param workspace the already-resolved workspace — the caller has been proved a member
     *                  and has passed {@code workspace.edit} before this runs
     * @param after     the post-state; {@link ProjectAccessProposal#current} for "no change"
     */
    @Transactional(readOnly = true)
    public ProjectAccessImpactResponse of(Workspace workspace, ProjectAccessProposal after) {
        // (1) live projects. Archived ones are frozen — there is nothing to lose access to.
        var projects = projectRepository.findAllByWorkspaceAndArchivedAtIsNull(workspace);

        // (2) ACTIVE workspace members, per workspace role.
        var activeByWorkspaceRole = counts(
                workspaceMemberRepository.countActiveMembersByRole(workspace.getId()));
        long activeMembers = activeByWorkspaceRole.values().stream().mapToLong(Long::longValue).sum();

        // (3) ACTIVE explicit project memberships, per (project, project role, workspace role).
        Map<UUID, List<Explicit>> explicitByProject = new HashMap<>();
        for (var row : projectMemberRepository.countActiveExplicitMembersByRolePair(workspace.getId())) {
            explicitByProject
                    .computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add(new Explicit((UUID) row[1], (UUID) row[2], ((Number) row[3]).longValue()));
        }

        // (4) inside the guard — and it is the authoritative half of this response.
        var stranded = projectAdminGuard.projectsLosingLastAdministrator(workspace, projects, after);

        var rows = new ArrayList<ProjectImpact>(projects.size());
        int noExplicitMembers = 0;
        int noWriters = 0;
        for (var project : projects) {
            var explicit = explicitByProject.getOrDefault(project.getId(), List.of());
            var row = impactOf(workspace, project, after, activeByWorkspaceRole, explicit);
            if (row.explicitMembers() == 0) {
                noExplicitMembers++;
            }
            if (row.noWritersAfter()) {
                noWriters++;
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing(ProjectImpact::key));

        return new ProjectAccessImpactResponse(
                Instant.now(),
                new State(workspace.getProjectAccessMode(), workspace.getDefaultProjectRoleId()),
                new State(after.mode(), after.workspaceDefaultRoleId()),
                activeMembers,
                projects.size(),
                noExplicitMembers,
                noWriters,
                List.copyOf(rows),
                stranded);
    }

    /**
     * One project's row.
     *
     * <p>{@code membersOnDefault} is subtraction, not a query: the members standing on the
     * fallback are the ACTIVE workspace members minus the ones with an explicit row here. The
     * subtraction is done <em>per workspace role</em> — which is why statement (3) groups by
     * both role ids — because {@code membersLosingEverything} is precisely the part of that
     * remainder whose <em>workspace</em> role carries neither {@code project.curate.all} nor
     * {@code project.administer.all}. Collapsing the grouping would make the number an
     * estimate, and AC 24 applies the same body and recounts from the database.
     */
    private ProjectImpact impactOf(Workspace workspace, Project project, ProjectAccessProposal after,
                                   Map<UUID, Long> activeByWorkspaceRole, List<Explicit> explicit) {
        var explicitByWorkspaceRole = new LinkedHashMap<UUID, Long>();
        long explicitMembers = 0;
        for (var e : explicit) {
            explicitByWorkspaceRole.merge(e.workspaceRoleId(), e.count(), Long::sum);
            explicitMembers += e.count();
        }

        // The role every member without an explicit row would inherit AFTER the change —
        // null in a STRICT post-state, which is the whole mechanism.
        var fallbackAfter = workspaceAccess.defaultProjectRoleUnder(workspace, project, after);

        long onDefault = 0;
        long losingEverything = 0;
        boolean writerOnDefault = false;
        for (var entry : activeByWorkspaceRole.entrySet()) {
            long remaining = entry.getValue()
                             - explicitByWorkspaceRole.getOrDefault(entry.getKey(), 0L);
            if (remaining <= 0) {
                continue;
            }
            onDefault += remaining;
            var effective = effective(workspace.getId(), entry.getKey(), fallbackAfter);
            if (effective.isEmpty()) {
                losingEverything += remaining;
            }
            writerOnDefault |= effective.has(Permission.ISSUE_CREATE);
        }

        // "Nobody can file an issue here afterwards" — the honest headline (§4.1). It is about
        // issue.create and not about membership: a project whose only explicit member holds
        // Viewer counts, and the workspace Owner does NOT rescue it, because
        // project.curate.all carries no issue or comment permission.
        boolean writerExplicit = explicit.stream().anyMatch(e ->
                effective(workspace.getId(), e.workspaceRoleId(),
                        workspaceAccess.resolveRoleOrDegrade(e.projectRoleId(), RoleScope.PROJECT,
                                        workspace.getId(), RoleScopeViolationSource.PROJECT_MEMBERS)
                                .orElse(null))
                        .has(Permission.ISSUE_CREATE));

        return new ProjectImpact(project.getId(), project.getKey(), project.getName(),
                onDefault, explicitMembers, losingEverything,
                !(writerOnDefault || writerExplicit));
    }

    /**
     * The effective project-scoped set of somebody holding {@code workspaceRoleId} at workspace
     * scope and {@code projectRole} in the project — the same
     * {@code WorkspaceAccessService.effectiveProjectPermissions} the resolver uses, so the
     * preview cannot describe a permission set the request path would not produce.
     *
     * <p><strong>Degrades rather than refusing</strong> on a stored {@code role_id} that fails
     * the scope+ownership assertion (HD-127 §3b), like every other collection read: one corrupt
     * row must not 404 the whole preview. It logs and counts, and the degrade can only narrow —
     * an empty set is the floor — so the worst outcome is a number that over-states the impact
     * of a change on a row that is already broken.
     */
    private PermissionSet effective(UUID workspaceId, UUID workspaceRoleId, RoleView projectRole) {
        var workspacePermissions = workspaceAccess
                .resolveRoleOrDegrade(workspaceRoleId, RoleScope.WORKSPACE, workspaceId,
                        RoleScopeViolationSource.WORKSPACE_MEMBERS)
                .map(RoleView::permissions)
                .orElseGet(PermissionSet::empty);
        return workspaceAccess.effectiveProjectPermissions(workspacePermissions, projectRole);
    }

    private static Map<UUID, Long> counts(List<Object[]> rows) {
        var out = new LinkedHashMap<UUID, Long>();
        for (var row : rows) {
            out.merge((UUID) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        return out;
    }

    /** One {@code (project role, workspace role) -> headcount} bucket of one project. */
    private record Explicit(UUID projectRoleId, UUID workspaceRoleId, long count) {
    }
}
