package com.hamstrack.admin.scope;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.exception.InsufficientProjectRoleException;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceRole;
import com.hamstrack.workspace.exception.InsufficientWorkspaceRoleException;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves and authorizes a <em>delegated</em> admin scope for the current
 * actor. The global scope is guarded by {@code hasRole("ADMIN")} in
 * SecurityConfig; the workspace and project scopes are membership-based and
 * enforced here (the {@code /api/workspaces/**} endpoints are only
 * {@code authenticated()} at the filter level).
 *
 * <p>Tenancy rule: non-membership and non-existence both return 404 (never
 * reveal a resource exists across tenants); being a member <em>without</em> the
 * required role — where the caller already knows the resource exists — returns
 * 403.
 */
@Service
@RequiredArgsConstructor
public class ScopeResolver {

    private final WorkspaceAccessService workspaceAccess;
    private final ProjectMemberRepository projectMemberRepository;
    /**
     * HD-123 S1 bridge: {@code project_members.role} is now a {@code roles} row, so the
     * legacy ordinal comparison reads the role's key through the cached view (0 queries).
     * S3 deletes this class outright — its three methods become
     * {@code workspace.taxonomy.manage}, {@code project.taxonomy.manage} and the curator
     * trio (§10.4).
     */
    private final RoleCatalog roleCatalog;

    /** Workspace whose config the actor may administer (OWNER or ADMIN). */
    @Transactional(readOnly = true)
    public Workspace requireWorkspaceAdmin(User actor, UUID workspaceId) {
        // Delegate the resolve+membership half (404 for missing/non-member) to the
        // tenancy primitive; keep the delegated-admin 403-on-insufficient-role rule.
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        if (!ctx.role().isAtLeast(WorkspaceRole.ADMIN)) {
            throw new InsufficientWorkspaceRoleException();
        }
        return ctx.workspace();
    }

    /**
     * Project whose config the actor may administer (MANAGER). Requires the
     * actor to be a member of the enclosing workspace as well, so a project id
     * from another tenant is never resolvable.
     */
    @Transactional(readOnly = true)
    public Project requireProjectAdmin(User actor, UUID workspaceId, UUID projectId) {
        // Delegate the resolve workspace+membership+project half (404s) to the
        // primitive; keep the delegated-admin 403-on-insufficient-role rule.
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var member = projectMemberRepository.findByProjectAndUser(project, actor)
                .orElseThrow(ProjectNotFoundException::new);
        if (!roleCatalog.view(member.getRole().getId()).asProjectRole().isAtLeast(ProjectRole.MANAGER)) {
            throw new InsufficientProjectRoleException();
        }
        return project;
    }

    /**
     * <strong>No production call sites since HD-125 (S2).</strong> All 16 of them —
     * {@code ProjectService.update}, {@code ComponentService} ×4, {@code VersionService}
     * ×6, {@code SprintService} ×5 — now check {@code project.edit} /
     * {@code component.manage} / {@code version.manage} / {@code sprint.manage} instead,
     * which is exactly this predicate spelled out ({@code Permission.projectCuration()},
     * held workspace-wide by the built-in Owner/Admin as {@code project.curate.all}). It
     * survives only as {@code PermissionParityTest}'s "today" column — the harness invokes
     * the real bean rather than transcribing it — and dies with this class in S3 (§10.4).
     * Do not add a caller.
     *
     * <p>Project whose <em>content catalog</em> (components, versions — HD-6 §3.3) the
     * actor may curate: a project <strong>MANAGER</strong>, <em>or</em> an
     * OWNER/ADMIN of the enclosing workspace who may not be a project member at all.
     *
     * <p>Semantics, in order:
     * <ol>
     *   <li>{@code resolveProject} — a missing workspace, a missing project or a
     *       non-member of the workspace all yield <strong>404</strong>, never 403 (no
     *       existence leak across tenants);</li>
     *   <li>workspace role ≥ ADMIN → pass;</li>
     *   <li>else project role ≥ MANAGER → pass;</li>
     *   <li>else <strong>403</strong> {@link InsufficientProjectRoleException} — the
     *       caller already knows the project exists, so a role failure is honest.</li>
     * </ol>
     *
     * <p>Rationale for the workspace-admin bypass: a workspace admin already edits
     * that project's <em>bindings</em> through
     * {@code PATCH /workspaces/{ws}/admin/projects/{p}/bindings} without being a
     * project member, so refusing them the component list would be arbitrary.
     * Differs from {@link #requireProjectAdmin} (which is MANAGER-only and 404s a
     * non-project-member) exactly in that bypass. {@code SystemRole.ADMIN} gets
     * nothing extra — instance admins act through their workspace membership, as they
     * do for every workspace-scoped resource.
     */
    @Transactional(readOnly = true)
    public Project requireProjectCurator(User actor, UUID workspaceId, UUID projectId) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        if (ctx.role().isAtLeast(WorkspaceRole.ADMIN)) {
            return ctx.project();
        }
        boolean manager = projectMemberRepository.findByProjectAndUser(ctx.project(), actor)
                .map(m -> roleCatalog.view(m.getRole().getId()).asProjectRole())
                .filter(r -> r.isAtLeast(ProjectRole.MANAGER))
                .isPresent();
        if (!manager) {
            throw new InsufficientProjectRoleException();
        }
        return ctx.project();
    }
}
