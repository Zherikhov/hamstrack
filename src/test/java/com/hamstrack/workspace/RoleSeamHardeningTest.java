package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.RoleRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * <strong>The seams that are safe today only because a slice has not shipped yet</strong>
 * — the latent findings from the S1 tenancy and security reviews, pinned so that ordering
 * discipline is not the thing protecting them (roles-permissions-proposal §12, §11.2).
 *
 * <p>Every case below is <em>unreachable</em> through the S1 API surface: there is no way
 * to create a custom role (S4), and nothing writes {@code default_project_role_id} (S7).
 * That is exactly why they are worth a test — the code that will make them reachable is in
 * a different slice, written later, and the review that would have caught it is this one.
 * A guard with no test is a comment.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class RoleSeamHardeningTest {

    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired WorkspaceAccessService workspaceAccess;
    @Autowired RoleCatalog roleCatalog;
    @Autowired RoleRepository roleRepository;
    @Autowired com.hamstrack.project.service.ProjectService projectService;
    @Autowired com.hamstrack.workspace.service.WorkspaceService workspaceService;

    @PersistenceContext EntityManager em;

    // ============================================================ H1: the impostor role

    /**
     * <strong>The privilege escalation S4 would otherwise ship — closed by construction in
     * S3.</strong> {@code roles_scope_key_uk} is {@code UNIQUE NULLS NOT DISTINCT
     * (workspace_id, scope, key)}, so a workspace may legally own a role keyed
     * {@code ADMIN} next to the built-in one. Give it <em>zero</em> permissions and it
     * passes the §11.2 grant ceiling (which compares permission sets — the empty set is
     * covered by anything) and looks like the most harmless object in the system.
     *
     * <p>Until HD-126 the danger was the legacy bridges: any surviving
     * {@code WorkspaceRole.valueOf("ADMIN").isAtLeast(ADMIN)} answered <em>true</em> for
     * this role's holder. S3 deleted the bridges and both enums, so the escalation is now
     * unrepresentable rather than merely guarded — but the <em>fixture</em> is still worth
     * keeping, because what has to stay true is that an impostor grants exactly what its
     * {@code role_permissions} rows say and not one thing more. This asserts that end to
     * end, through the real resolver, at both scopes.
     *
     * <p>If a future slice reintroduces any key-to-privilege mapping — an
     * {@code isAtLeast}, a {@code switch (role.key())}, a {@code Set.of("ADMIN","OWNER")}
     * — this test is where it will surface.
     */
    @Test
    @Transactional
    void aCustomRoleKeyedLikeABuiltInGrantsOnlyWhatItActuallyHolds() {
        var ws = workspace();
        var project = project(ws);

        var impostor = customRole(ws, RoleScope.WORKSPACE, "ADMIN", "Totally normal role");
        var view = roleCatalog.view(impostor.getId());
        assert view.permissions().isEmpty() : "fixture: the impostor is meant to grant nothing";
        assert !view.builtIn();
        assert !view.isBuiltIn(BuiltInRoles.WORKSPACE_ADMIN)
                : "a CUSTOM role keyed 'ADMIN' answered isBuiltIn(WORKSPACE_ADMIN). That check is "
                  + "what the invite/role-change Owner guardrail keys on, and it must compare "
                  + "identity, never the key string.";

        var actor = user();
        var membership = new WorkspaceMember();
        membership.setWorkspace(ws);
        membership.setUser(actor);
        membership.setRole(roleRepository.getReferenceById(impostor.getId()));
        workspaceMemberRepository.save(membership);
        workspaceMemberRepository.flush();

        var ctx = workspaceAccess.requireMember(actor, ws.getId());
        assert ctx.permissions().isEmpty()
                : "a workspace member holding a zero-permission role keyed 'ADMIN' resolved to "
                  + ctx.permissions().asWireStrings() + ". A role's KEY must never confer "
                  + "anything — that is the escalation HD-123 deleted the ordinal ladder to "
                  + "prevent, and it would open every workspace-scoped gate for its holder.";

        var projectImpostor = customRole(ws, RoleScope.PROJECT, "MANAGER", "Also normal");
        var pm = new com.hamstrack.project.entity.ProjectMember();
        pm.setProject(project);
        pm.setUser(actor);
        pm.setRole(roleRepository.getReferenceById(projectImpostor.getId()));
        projectMemberRepository.save(pm);
        projectMemberRepository.flush();

        var projectCtx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assert projectCtx.permissions().isEmpty()
                : "a project member holding a zero-permission role keyed 'MANAGER' resolved to "
                  + projectCtx.permissions().asWireStrings() + " — project taxonomy, issue "
                  + "deletion and attachment moderation, all opened by a name.";
    }

    // ====================================================== H3/T2: scope and ownership

    /**
     * A {@link com.hamstrack.common.security.PermissionSet} is a flat {@code EnumSet} with
     * no memory of the scope its grants came from. So a WORKSPACE role in
     * {@code project_members.role_id} does not fail — it <em>succeeds</em>, and puts
     * {@code workspace.edit} into {@code ProjectContext.permissions()}, where every
     * {@code require(...)} downstream honours it. No workspace-id check catches this: the
     * role belongs to the right workspace.
     */
    @Test
    @Transactional
    void aWorkspaceScopedRoleIsRefusedAsAProjectMembersRole() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var wrongScope = customRole(ws, RoleScope.WORKSPACE, "sneaky-lead", "Workspace-scoped");
        wrongScope.getPermissions().add(grant(Permission.WORKSPACE_EDIT));
        em.flush();
        projectMemberRow(project, actor, wrongScope);

        try {
            var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
            assert false
                    : "a WORKSPACE role was accepted as a project role, and the caller now holds "
                      + ctx.permissions().asWireStrings() + " IN A PROJECT. workspace.edit reaching "
                      + "ProjectContext is privilege escalation assembled from ids that are each "
                      + "individually legitimate.";
        } catch (WorkspaceNotFoundException expected) {
            // 404, not 403: a tenancy/consistency failure tells the caller nothing.
        }
    }

    /**
     * <strong>The list endpoints must apply the same assertion as the detail endpoints.</strong>
     * Both batch their membership rows into one query to dodge an N+1, so they resolve
     * {@code role_id}s themselves instead of going through {@code projectContext} /
     * {@code requireMember} — which is exactly how they came to be the two paths that read a
     * role without checking it. A list that is more permissive than the detail view renders
     * controls that then 403 on click; a list that is less permissive hides work that is
     * actually allowed. Either way the two disagree, which is the HD-98/HD-116 bug class.
     *
     * <p>Note the deliberate blast radius: one bad row fails the <em>whole</em> list rather
     * than being skipped. Dropping the offending row silently is the "control that stopped
     * appearing" failure §18 names as the worst outcome, and the row cannot be created
     * through any API — it would take a role picker with a scope bug (S4) to produce one.
     */
    @Test
    @Transactional
    void theListEndpointsApplyTheSameScopeAssertionAsTheDetailEndpoints() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var wrongScope = customRole(ws, RoleScope.WORKSPACE, "list-sneaky", "Workspace-scoped");
        wrongScope.getPermissions().add(grant(Permission.WORKSPACE_EDIT));
        em.flush();
        projectMemberRow(project, actor, wrongScope);

        try {
            var listed = projectService.list(actor, ws.getId(), false);
            assert false
                    : "ProjectService.list resolved a WORKSPACE role as a project role and served "
                      + listed.getFirst().myPermissions() + " as myPermissions. The detail endpoint "
                      + "refuses the same row, so the list is now strictly more permissive than the "
                      + "thing it links to.";
        } catch (WorkspaceNotFoundException expected) {
            // as required
        }
    }

    /** The workspace list has the same shape of gap, on {@code workspace_members}. */
    @Test
    @Transactional
    void theWorkspaceListAppliesTheSameScopeAssertionAsWorkspaceGet() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");

        var wrongScope = customRole(ws, RoleScope.PROJECT, "ws-list-sneaky", "Project-scoped");
        em.flush();
        var membership = workspaceMemberRepository.findByWorkspaceAndUser(ws, actor).orElseThrow();
        membership.setRole(wrongScope);
        workspaceMemberRepository.flush();

        try {
            workspaceService.listForUser(actor);
            assert false
                    : "WorkspaceService.listForUser built myPermissions from a PROJECT-scoped role "
                      + "in workspace_members, while WorkspaceService.get refuses the same row.";
        } catch (WorkspaceNotFoundException expected) {
            // as required
        }
    }

    /** The same guard against the plain cross-tenant case: another workspace's role. */
    @Test
    @Transactional
    void aForeignWorkspacesRoleIsRefusedAsAProjectMembersRole() {
        var ws = workspace();
        var other = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var foreign = customRole(other, RoleScope.PROJECT, "their-lead", "Another tenant's role");
        foreign.getPermissions().add(grant(Permission.PROJECT_EDIT));
        em.flush();
        projectMemberRow(project, actor, foreign);

        try {
            workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
            assert false : "a role owned by another workspace was honoured in this one (§12)";
        } catch (WorkspaceNotFoundException expected) {
            // as required
        }
    }

    /**
     * T2 — {@code default_project_role_id} is a plain FK to {@code roles(id)}: the database
     * cannot say "…and a PROJECT role of THIS workspace". Unlike an explicit membership row
     * (which is one person), a bad default applies to <em>every</em> member with no project
     * row — nearly everyone — so this one degrades to the built-in Contributor and logs,
     * rather than refusing the workspace.
     */
    @Test
    @Transactional
    void aBadWorkspaceDefaultRoleFallsBackToContributorInsteadOfBeingHonoured() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var wrongScope = customRole(ws, RoleScope.WORKSPACE, "bad-default", "Workspace-scoped");
        wrongScope.getPermissions().add(grant(Permission.WORKSPACE_MEMBER_MANAGE));
        ws.setDefaultProjectRoleId(wrongScope.getId());
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assert !ctx.permissions().has(Permission.WORKSPACE_MEMBER_MANAGE)
                : "a workspace-scoped default role was honoured as the project default, handing "
                  + "workspace.member.manage to every member with no project row: "
                  + ctx.permissions().asWireStrings();
        assert ctx.permissions().has(Permission.ISSUE_CREATE)
                && ctx.permissions().has(Permission.SPRINT_ASSIGN)
                : "the fallback must be the built-in Contributor — falling back to NOTHING would "
                  + "lock every member out of every project over one bad config row. Got "
                  + ctx.permissions().asWireStrings();
    }

    /** The scoped finder is the S4 front door for a role id from a request body. */
    @Test
    @Transactional
    void findAssignableFiltersByScopeAndByWorkspace() {
        var ws = workspace();
        var other = workspace();
        var role = customRole(ws, RoleScope.PROJECT, "lead", "A project role");
        em.flush();

        assert roleRepository.findAssignable(role.getId(), ws.getId(), RoleScope.PROJECT).isPresent();
        assert roleRepository.findAssignable(role.getId(), ws.getId(), RoleScope.WORKSPACE).isEmpty()
                : "findAssignable ignored the scope — the caller would assign a project role as a "
                  + "workspace role, or vice versa";
        assert roleRepository.findAssignable(role.getId(), other.getId(), RoleScope.PROJECT).isEmpty()
                : "findAssignable leaked a role across workspaces (§12)";
        assert roleRepository.findAssignable(
                BuiltInRoles.PROJECT_MEMBER, ws.getId(), RoleScope.PROJECT).isPresent()
                : "built-in templates must stay assignable in every workspace";
    }

    // ================================================================= T1: the pair

    /**
     * {@code projectContext} is public and takes an already-resolved workspace and project.
     * Both current callers pair them correctly — which is precisely the argument that gets
     * a check like this dropped, and precisely why the tenancy chokepoint should not rely
     * on it.
     */
    @Test
    @Transactional
    void projectContextRefusesAProjectFromAnotherWorkspace() {
        var ws = workspace();
        var other = workspace();
        var actor = member(ws, "OWNER");
        member(other, actor, "OWNER"); // a member of both, so only the pairing is wrong
        var foreignProject = project(other);
        em.flush();

        var ctx = workspaceAccess.requireMember(actor, ws.getId());
        try {
            workspaceAccess.projectContext(actor, ctx, foreignProject);
            assert false
                    : "projectContext accepted a project from a different workspace than the "
                      + "WorkspaceContext it was handed. Nothing else in the chain re-checks the "
                      + "pairing, so any future caller that resolves the two separately leaks.";
        } catch (ProjectNotFoundException expected) {
            // 404, never 403.
        }
    }

    // ============================================================ H2 / H4: the catalog

    /**
     * H2 — a stored key this build does not know must be <em>dropped and logged</em>, not
     * thrown on. Throwing happens inside {@code requireMember}, i.e. on every authenticated
     * request naming a workspace; for a built-in role that is the whole instance, including
     * for the admin who has to fix it. Dropping can only ever narrow (see
     * {@code PermissionConverter}), so it is the strictly better failure.
     */
    @Test
    @Transactional
    void anUnknownStoredPermissionKeyIsDroppedNotThrown() {
        var ws = workspace();
        var role = customRole(ws, RoleScope.PROJECT, "future", "Written by a newer release");
        role.getPermissions().add(grant(Permission.ISSUE_CREATE));
        em.flush();
        em.createNativeQuery("INSERT INTO role_permissions (role_id, permission, own_only) "
                        + "VALUES (:id, 'issue.teleport', false)")
                .setParameter("id", role.getId())
                .executeUpdate();
        em.clear();

        var view = roleCatalog.view(role.getId());
        assert view.permissions().has(Permission.ISSUE_CREATE)
                : "the known grants must survive alongside the unknown one";
        assert view.permissions().asWireStrings().size() == 1
                : "the unknown key leaked into the resolved set: " + view.permissions();
    }

    /**
     * H4 — {@code asWireStrings} encodes an own-only grant as {@code key + ":own"}, so a
     * key containing {@code ':'} would be ambiguous, and a client splitting on it resolves
     * the ambiguity by <em>widening</em>. The enum's own class-init enforces the grammar
     * (a bad key fails every test at once); this states it where a reader will find it.
     */
    @Test
    void everyPermissionKeyIsUnambiguousOnTheWire() {
        for (var p : Permission.values()) {
            assert p.key().matches("[a-z]+(\\.[a-z]+)+")
                    : p.name() + " has key '" + p.key() + "', which is not area.action lowercase";
            assert !p.key().contains(":")
                    : p.name() + " has a ':' in its key, which collides with the \":own\" suffix";
            assert p.key().length() <= 64 : p.name() + " exceeds the VARCHAR(64) column";
        }
    }

    // ------------------------------------------------------------------ fixture

    private Workspace workspace() {
        var w = new Workspace();
        w.setName("Seam");
        w.setSlug("seam-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(user());
        return workspaceRepository.save(w);
    }

    private Project project(Workspace ws) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Seam");
        p.setKey("SM" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(user());
        return projectRepository.save(p);
    }

    private User member(Workspace ws, String role) {
        return member(ws, user(), role);
    }

    private User member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
        return user;
    }

    private void projectMemberRow(Project project, User user, Role role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(role);
        projectMemberRepository.save(m);
        projectMemberRepository.flush();
    }

    /**
     * A custom role, persisted through the {@link EntityManager} because there is no roles
     * CRUD until S4 — and because {@code RoleRepository} deliberately no longer exposes
     * {@code save} (T3).
     */
    private Role customRole(Workspace ws, RoleScope scope, String key, String name) {
        var role = new Role();
        role.setWorkspaceId(ws.getId());
        role.setScope(scope);
        role.setKey(key);
        role.setName(name);
        role.setBuiltIn(false);
        em.persist(role);
        em.flush();
        return role;
    }

    private com.hamstrack.workspace.entity.RolePermission grant(Permission permission) {
        return new com.hamstrack.workspace.entity.RolePermission(permission, false);
    }

    private User user() {
        var u = new User();
        u.setEmail(("seam-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Seam");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }
}
