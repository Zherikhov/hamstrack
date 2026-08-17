package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.ProjectAccessMode;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single tenancy chokepoint (HD-82) and, since HD-123, the single
 * <strong>authorization</strong> chokepoint too. Encapsulates the resolve-workspace →
 * verify-membership → <strong>404-not-403</strong> dance that was copy-pasted across ~12
 * services, and resolves the caller's effective permissions <em>once per request</em>
 * into the returned context (§5.1, Rule P1).
 *
 * <p><strong>Invariant (byte-identical to every former inline copy):</strong> a
 * non-existent workspace AND a workspace the caller isn't a member of both throw
 * {@link WorkspaceNotFoundException} (HTTP 404) — never a 403, which would disclose
 * existence across tenants. The project/issue compositions add
 * {@link ProjectNotFoundException} / {@link IssueNotFoundException} on top, matching the
 * former inline resolvers exactly. <strong>Permission evaluation happens strictly after
 * that</strong>, so a permission failure (403) can only ever reach a caller who has
 * already been proved a member — the rule §12 exists to protect.
 *
 * <p>Every method is {@code @Transactional(readOnly = true)} and participates in the
 * caller's transaction via {@code REQUIRED} propagation (no new tx boundary), so the
 * returned entities stay managed for the caller and no extra flush is introduced that
 * could bump an {@code @Version} — matching the previous inline behavior.
 *
 * <p><strong>Query cost</strong> (§9.2, warm role cache): a workspace-scoped resolution
 * is <strong>2</strong> statements (workspace, membership) and a project-scoped one is
 * <strong>4</strong> (+ project, + explicit project membership). Resolving roles to
 * permissions costs <strong>0</strong>: built-in role ids are compile-time constants
 * ({@code BuiltInRoles}) and {@code RolePermissionCache} holds the permission sets. The
 * memberships are {@code JOIN FETCH}ed with their role, which is why the count does not
 * grow. Authorization is then free at the call site — a handler that checks six
 * permissions costs exactly what one that checks none costs.
 *
 * <p><strong>Not folded in here (yet):</strong> the admin delegated-scope
 * {@code ScopeResolver}, which deliberately returns <em>403</em> for a member without the
 * required role. S3 absorbs it: keeping two authorization services after this epic would
 * recreate the exact condition — two predicates for one question — that HD-123 exists to
 * remove (§10.4).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceAccessService {

    /** {@link Permission#projectCuration()} as a set, resolved once — it is immutable. */
    private static final PermissionSet PROJECT_CURATION =
            PermissionSet.granting(Permission.projectCuration());

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueRepository issueRepository;
    private final RoleCatalog roleCatalog;

    /**
     * Resolve the workspace and verify the actor's membership. Throws
     * {@link WorkspaceNotFoundException} (404) for both a non-existent workspace and a
     * non-member — identical to every former inline {@code resolveWorkspace}.
     */
    @Transactional(readOnly = true)
    public WorkspaceContext requireMember(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        var membership = workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        var role = requireRole(membership.getRole().getId(),
                RoleScope.WORKSPACE, workspace.getId(), "workspace_members");
        return new WorkspaceContext(workspace, membership, role, role.permissions());
    }

    /**
     * {@link #requireMember} then resolve the project within that workspace, and resolve
     * the caller's <em>effective project role</em>. Throws
     * {@link ProjectNotFoundException} (404) if the project isn't in the workspace.
     *
     * <p><strong>Renamed from {@code requireProjectMember} (§9.1).</strong> The old name
     * was a documented lie: it has never read {@code project_members}, it verifies
     * <em>workspace</em> membership, and the mismatch cost the project one CLAUDE.md
     * gotcha and an epic's worth of confusion about what "project role" meant. It still
     * does not <em>require</em> a project role — reads are open to every workspace member
     * in v1 (§12) — it resolves one.
     */
    @Transactional(readOnly = true)
    public ProjectContext resolveProject(User actor, UUID workspaceId, UUID projectId) {
        var ws = requireMember(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, ws.workspace())
                .orElseThrow(ProjectNotFoundException::new);
        return projectContext(actor, ws, project);
    }

    /**
     * {@link #resolveProject} then resolve the issue by number within the project. Throws
     * {@link IssueNotFoundException} (404) if no such issue exists.
     */
    @Transactional(readOnly = true)
    public IssueContext requireIssue(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var pc = resolveProject(actor, workspaceId, projectId);
        var issue = issueRepository.findByProjectAndNumber(pc.project(), issueNumber)
                .orElseThrow(IssueNotFoundException::new);
        return new IssueContext(pc.workspace(), pc.membership(), pc.project(), issue,
                pc.workspaceRole(), pc.workspacePermissions(), pc.projectRole(), pc.permissions());
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Builds the project-scoped half of a {@link ProjectContext} — the effective project
     * role (§5.2) and the permission set it yields.
     *
     * <p>Public so a caller holding an already-resolved workspace and project can get the
     * same answer without re-resolving either.
     *
     * <p><strong>{@code ProjectService.list} deliberately does NOT call this</strong> — it
     * would be an N+1, because this reads {@code project_members} for one project and the
     * list batches all of them into a single query. What must not diverge is the
     * <em>rule</em>, so the three pieces of it are separately public and the list composes
     * exactly those: {@link #requireRole} (scope + ownership on the explicit row),
     * {@link #defaultProjectRole} (the §5.2 fallback chain) and
     * {@link #effectiveProjectPermissions} (the union with the workspace-scoped bypass).
     * There is one implementation of each; a second one is how a list view and a detail
     * view start disagreeing — the HD-98 / HD-116 bug class this epic exists to close.
     */
    @Transactional(readOnly = true)
    public ProjectContext projectContext(User actor, WorkspaceContext ws, Project project) {
        // T1: this is public and takes an already-resolved pair, so it must not simply
        // trust that they belong together — "my caller passes matching arguments" is not a
        // tenancy control, it is a hope, and this is the class the whole codebase relies on
        // for the 404-not-403 rule. The check costs no SELECT: `project.workspace` is a
        // lazy proxy and reading its id does not initialise it.
        if (!project.getWorkspace().getId().equals(ws.workspace().getId())) {
            throw new ProjectNotFoundException();
        }
        var explicit = projectMemberRepository.findByProjectAndUser(project, actor);
        var projectRole = explicit
                .map(m -> requireRole(m.getRole().getId(),
                        RoleScope.PROJECT, ws.workspace().getId(), "project_members"))
                .orElseGet(() -> defaultProjectRole(ws.workspace(), project));
        return new ProjectContext(ws.workspace(), ws.membership(), project,
                ws.workspaceRole(), ws.permissions(),
                projectRole, explicit.isPresent(),
                effectiveProjectPermissions(ws.permissions(), projectRole));
    }

    /**
     * The project role a workspace member inherits when they have <em>no</em> explicit
     * {@code project_members} row (§5.2, step 2):
     * {@code projects.default_project_role_id} → {@code workspaces.default_project_role_id}
     * → the built-in <strong>Contributor</strong> — but only while the workspace is
     * {@link ProjectAccessMode#OPEN}. In {@code STRICT} the answer is {@code null}: no
     * project role at all.
     *
     * <p>This is the mechanism that makes the upgrade a no-op. V14 leaves every workspace
     * {@code OPEN} with a NULL default, so every workspace member falls through to
     * Contributor — whose permission set is verbatim what they can do in a project today.
     */
    public RoleView defaultProjectRole(Workspace workspace, Project project) {
        if (workspace.getProjectAccessMode() != ProjectAccessMode.OPEN) {
            return null;
        }
        boolean fromProject = project.getDefaultProjectRoleId() != null;
        var defaultId = fromProject
                ? project.getDefaultProjectRoleId()
                : workspace.getDefaultProjectRoleId();
        if (defaultId == null) {
            return roleCatalog.defaultProjectRole();
        }
        // T2: `default_project_role_id` is a plain FK to roles(id) — the database cannot
        // express "…and it must be a PROJECT role of THIS workspace", so this is the only
        // place that can. Unreachable in S1 (V14 leaves both columns NULL and no write path
        // exists), and live the moment S4/S7 ships the picker: a foreign or workspace-scoped
        // role id written here would otherwise be honoured for EVERY member of this
        // workspace who has no explicit project row — which is nearly everyone (§2.3).
        var role = roleCatalog.view(defaultId);
        if (!isUsableAs(role, RoleScope.PROJECT, workspace.getId())) {
            log.error("{} {} names default_project_role_id {} which is not a PROJECT role of "
                      + "workspace {} (scope={}, owner={}). Falling back to the built-in "
                      + "Contributor. This is a data error: the picker must only offer roles from "
                      + "RoleRepository.findAssignable(id, workspaceId, PROJECT).",
                    fromProject ? "Project" : "Workspace",
                    fromProject ? project.getId() : workspace.getId(),
                    defaultId, workspace.getId(), role.scope(), role.workspaceId());
            return roleCatalog.defaultProjectRole();
        }
        return role;
    }

    /**
     * <strong>The one way to turn an assigned {@code role_id} into a usable
     * {@link RoleView}</strong> — resolve it through the cache, then enforce the tenancy +
     * scope invariant (H3/T2): it must be of the expected {@link RoleScope}, and it must be
     * either a built-in template ({@code workspaceId == null}) or owned by this workspace.
     *
     * <p>Scope matters as much as ownership, and less obviously. A {@link PermissionSet}
     * is a flat {@code EnumSet} that does not remember which scope its grants came from,
     * so a WORKSPACE role stored in {@code project_members.role_id} would place
     * {@code workspace.edit} and {@code workspace.member.manage} straight into
     * {@code ProjectContext.permissions()} — and every {@code require(...)} downstream
     * would be satisfied honestly. Nothing upstream can catch it: the role belongs to the
     * right workspace and the row is otherwise valid.
     *
     * <p><strong>Public because the two list endpoints cannot call
     * {@link #projectContext}</strong> and must not therefore skip the check.
     * {@code ProjectService.list} and {@code WorkspaceService.listForUser} batch their
     * membership rows into one query to avoid an N+1, so they resolve role ids themselves;
     * before this was exposed they were the two paths that read a {@code role_id} without
     * asserting anything, which is how a list view and a detail view start disagreeing
     * about what someone may do — the HD-98/HD-116 bug class this epic exists to close.
     * Costs no query on a warm cache, so a page of 50 projects pays nothing for it.
     *
     * <p>Throws {@link WorkspaceNotFoundException} rather than a 500 because it is a
     * tenancy failure and the caller must not learn more than "no".
     *
     * @param source the table the id came from, for the ERROR log — the operator's only
     *               clue as to which row is wrong
     */
    @Transactional(readOnly = true)
    public RoleView requireRole(UUID roleId, RoleScope expected, UUID workspaceId, String source) {
        var role = roleCatalog.view(roleId);
        if (!isUsableAs(role, expected, workspaceId)) {
            log.error("{} references role {} (key={}, scope={}, owner={}) which is not a {} role "
                      + "of workspace {}. Refusing the request.",
                    source, role.id(), role.key(), role.scope(), role.workspaceId(),
                    expected, workspaceId);
            throw new WorkspaceNotFoundException();
        }
        return role;
    }

    private boolean isUsableAs(RoleView role, RoleScope expected, UUID workspaceId) {
        return role.scope() == expected
                && (role.workspaceId() == null || role.workspaceId().equals(workspaceId));
    }

    /**
     * {@code permissionsOf(effectiveProjectRole)} ∪ the two workspace-scoped
     * "in every project of this workspace" grants, if the workspace set holds them (§9.1):
     * <ul>
     *   <li>{@link Permission#PROJECT_CURATE_ALL} → the curator set
     *       {@link Permission#projectCuration()}. <strong>This is the one the built-in
     *       Owner and Admin hold</strong>, and it is today's implicit workspace-admin
     *       bypass made explicit (§17.2) — the same four permissions
     *       {@code ScopeResolver.requireProjectCurator} lets them through today, and not
     *       one more;</li>
     *   <li>{@link Permission#PROJECT_ADMINISTER_ALL} → every project permission. Held by
     *       no built-in; grantable to a custom role in S4.</li>
     * </ul>
     *
     * <p>Both honour {@code ownRequired}, so neither hands anyone unrestricted
     * {@code comment.edit}: administering a project is not permission to edit someone
     * else's words (§17.3).
     */
    public PermissionSet effectiveProjectPermissions(PermissionSet workspacePermissions,
                                                     RoleView projectRole) {
        var base = projectRole == null ? PermissionSet.empty() : projectRole.permissions();
        if (workspacePermissions.has(Permission.PROJECT_CURATE_ALL)) {
            base = base.union(PROJECT_CURATION);
        }
        if (workspacePermissions.has(Permission.PROJECT_ADMINISTER_ALL)) {
            base = base.union(PermissionSet.allOf(RoleScope.PROJECT));
        }
        return base;
    }
}
