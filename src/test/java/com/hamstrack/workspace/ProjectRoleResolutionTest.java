package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.ProjectAccessMode;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * <strong>The effective-project-role chain</strong> (roles-permissions-proposal §5.2) —
 * the rule that decides what a caller may do in a project when they have no
 * {@code project_members} row, which is nearly every real user (§2.3).
 *
 * <p>{@code RoleSeamHardeningTest} pins the two hostile inputs the chain has to survive
 * (a wrong-scope role on a membership row, a bad {@code default_project_role_id} on the
 * workspace). What was left untested is the chain <em>working</em>: the precedence order
 * {@code project → workspace → built-in Contributor}, the project-level default that S7's
 * picker will write, and {@link ProjectAccessMode#STRICT} — a whole branch of
 * {@code WorkspaceAccessService.defaultProjectRole} with no coverage at all, and the one
 * that returns a {@code null} role for every {@code ProjectContext} it touches.
 *
 * <p>The STRICT cases matter more than "S7 has not shipped" suggests. The moment the mode
 * is switchable, {@code projectRole == null} flows into every project-scoped response and
 * every not-yet-converted legacy call site; if that path throws, or if it accidentally
 * keeps the workspace-admin bypass, the failure lands on an install that just tightened
 * its access policy — the least forgiving moment possible.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class ProjectRoleResolutionTest {

    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired WorkspaceAccessService workspaceAccess;
    @Autowired RoleCatalog roleCatalog;

    @PersistenceContext EntityManager em;

    // ==================================================== the default chain (§5.2)

    /**
     * Step 3 of the chain, and the one V14 relies on: both defaults NULL → Contributor.
     * This is the state every existing install is left in, so it is the state that has to
     * reproduce today's abilities exactly.
     */
    @Test
    @Transactional
    void noDefaultAnywhereMeansContributor() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.projectRole())
                .as("a member with no project row and no configured default did not land on the "
                  + "built-in Contributor. V14 leaves EVERY workspace in exactly this state, so "
                  + "this branch is the upgrade's no-op promise (§8.4). Got " + ctx.projectRole())
                .isNotNull();
        assertThat(ctx.projectRole().id())
                .as("a member with no project row and no configured default did not land on the "
                  + "built-in Contributor. V14 leaves EVERY workspace in exactly this state, so "
                  + "this branch is the upgrade's no-op promise (§8.4). Got " + ctx.projectRole())
                .isEqualTo(BuiltInRoles.PROJECT_MEMBER);
        assertThat(ctx.explicitProjectRole())
                .withFailMessage("the inherited role was reported as an explicit membership. §8.4 refused to "
                  + "backfill a row per (member x project) precisely to keep 'added to this "
                  + "project' distinguishable from 'inherits the default'.")
                .isFalse();
    }

    /**
     * Step 1 beats step 2: a project's own default wins over the workspace's. Untested until
     * now — {@code RoleSeamHardeningTest} only exercises the workspace-level column, and the
     * {@code fromProject} branch is the one S7's per-project picker writes.
     */
    @Test
    @Transactional
    void aProjectsOwnDefaultBeatsTheWorkspaceDefault() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var workspaceDefault = customRole(ws, RoleScope.PROJECT, "ws-default", Permission.ISSUE_CREATE);
        var projectDefault = customRole(ws, RoleScope.PROJECT, "p-default", Permission.VERSION_MANAGE);
        ws.setDefaultProjectRoleId(workspaceDefault.getId());
        project.setDefaultProjectRoleId(projectDefault.getId());
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.projectRole().id())
                .as("the workspace default was applied to a project that names its own (§5.2 step 1 "
                  + "precedes step 2). A project that deliberately tightens or loosens its default "
                  + "would silently inherit the workspace's instead. Got " + ctx.projectRole())
                .isEqualTo(projectDefault.getId());
        assertThat(ctx.permissions().has(Permission.VERSION_MANAGE))
                .withFailMessage(() -> "the resolved permissions came from the wrong default: " + ctx.permissions())
                .isTrue();
        assertThat(ctx.permissions().has(Permission.ISSUE_CREATE))
                .withFailMessage(() -> "the resolved permissions came from the wrong default: " + ctx.permissions())
                .isFalse();
    }

    /**
     * An explicit {@code project_members} row beats both defaults — step 0. Cheap to state
     * and the thing every "why can this person do that?" support ticket starts with.
     */
    @Test
    @Transactional
    void anExplicitMembershipBeatsEveryDefault() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);
        var ignored = customRole(ws, RoleScope.PROJECT, "should-be-ignored", Permission.VERSION_MANAGE);
        ws.setDefaultProjectRoleId(ignored.getId());
        project.setDefaultProjectRoleId(ignored.getId());
        // Commenter has no legacy enum constant — it is one of the two built-ins §7.2 adds,
        // which is also why it is the honest choice here: the resolution chain must not care.
        projectMemberRow(project, actor, roleCatalog.reference(BuiltInRoles.PROJECT_COMMENTER));
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.explicitProjectRole())
                .withFailMessage("an explicit project_members row lost to the default chain: " + ctx.projectRole())
                .isTrue();
        assertThat(ctx.projectRole().key())
                .as("an explicit project_members row lost to the default chain: " + ctx.projectRole())
                .isEqualTo("COMMENTER");
        assertThat(ctx.permissions().has(Permission.VERSION_MANAGE))
                .withFailMessage(() -> "the Commenter got the default role's grants: " + ctx.permissions())
                .isFalse();
        assertThat(ctx.permissions().has(Permission.COMMENT_CREATE))
                .withFailMessage(() -> "the Commenter got the default role's grants: " + ctx.permissions())
                .isTrue();
    }

    /**
     * The {@code fromProject} half of the T2 guard. {@code RoleSeamHardeningTest} covers a
     * bad <em>workspace</em> default; the project column is a separate read with its own
     * {@code fromProject} branch in the log line, and a copy-paste there would leave the
     * per-project picker unguarded while the workspace one looked tested.
     */
    @Test
    @Transactional
    void aBadProjectDefaultFallsBackToContributorAndDoesNotUseTheWorkspaceDefault() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var wrongScope = customRole(ws, RoleScope.WORKSPACE, "bad-project-default",
                Permission.WORKSPACE_MEMBER_MANAGE);
        var workspaceDefault = customRole(ws, RoleScope.PROJECT, "ws-fallback", Permission.VERSION_MANAGE);
        ws.setDefaultProjectRoleId(workspaceDefault.getId());
        project.setDefaultProjectRoleId(wrongScope.getId());
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.permissions().has(Permission.WORKSPACE_MEMBER_MANAGE))
                .withFailMessage(() -> "a WORKSPACE-scoped role was honoured as a project's default, so every member "
                  + "with no project row in this project now holds workspace.member.manage inside "
                  + "a ProjectContext: " + ctx.permissions().asWireStrings())
                .isFalse();
        assertThat(ctx.projectRole().id())
                .as("a bad project default must degrade to the built-in Contributor — the same safe, "
                  + "documented answer as a bad workspace default, not silently to the workspace's "
                  + "own default (which is a DIFFERENT configured policy and would mask the data "
                  + "error indefinitely). Got " + ctx.projectRole())
                .isEqualTo(BuiltInRoles.PROJECT_MEMBER);
    }

    /** A foreign workspace's role as a project default: same fallback, tenancy flavour (§12). */
    @Test
    @Transactional
    void aForeignWorkspacesRoleAsAProjectDefaultFallsBackToContributor() {
        var ws = workspace();
        var other = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var foreign = customRole(other, RoleScope.PROJECT, "their-default", Permission.PROJECT_EDIT);
        project.setDefaultProjectRoleId(foreign.getId());
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.permissions().has(Permission.PROJECT_EDIT))
                .withFailMessage(() -> "another tenant's role was applied as this project's default, to every member "
                  + "with no explicit row: " + ctx.permissions().asWireStrings())
                .isFalse();
        assertThat(ctx.projectRole().id())
                .as("the fallback is the built-in Contributor, resolved by role id and not by a key that two scopes share")
                .isEqualTo(BuiltInRoles.PROJECT_MEMBER);
    }

    /** A legitimate custom project-scoped default is honoured — the guard must not be a wall. */
    @Test
    @Transactional
    void aValidCustomProjectRoleIsHonouredAsTheDefault() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);
        var readOnlyish = customRole(ws, RoleScope.PROJECT, "reporter-only", Permission.ISSUE_CREATE);
        ws.setDefaultProjectRoleId(readOnlyish.getId());
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.projectRole().id())
                .as("a valid, workspace-owned, PROJECT-scoped custom role was refused as the "
                  + "default. The T2 guard must reject only what is unusable — falling back for a "
                  + "legitimate role would make the S7 picker look broken and quietly restore "
                  + "Contributor's twelve grants to a workspace that deliberately narrowed them.")
                .isEqualTo(readOnlyish.getId());
        assertThat(ctx.permissions().has(Permission.ISSUE_CREATE))
                .withFailMessage(() -> "the custom default's own set must be what applies, not Contributor's: "
                  + ctx.permissions().asWireStrings())
                .isTrue();
        assertThat(ctx.permissions().has(Permission.ISSUE_TRANSITION))
                .withFailMessage(() -> "the custom default's own set must be what applies, not Contributor's: "
                  + ctx.permissions().asWireStrings())
                .isFalse();
    }

    // ======================================================= STRICT (§5.2, ProjectAccessMode)

    /**
     * STRICT is the whole point of the mode: a member who was never added to the project
     * gets <em>no</em> project role and an empty permission set — not Contributor, and not
     * an error.
     */
    @Test
    @Transactional
    void inAStrictWorkspaceAMemberWithNoProjectRowHoldsNothing() {
        var ws = workspace();
        ws.setProjectAccessMode(ProjectAccessMode.STRICT);
        var actor = member(ws, "MEMBER");
        var project = project(ws);
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.projectRole())
                .as("STRICT still handed out a default project role (" + ctx.projectRole() + "). "
                  + "The mode exists so a workspace can say 'membership of a project is explicit'; "
                  + "if the default chain still runs, the setting does nothing.")
                .isNull();
        assertThat(ctx.permissions().isEmpty())
                .withFailMessage(() -> "a member with no project role must hold the EMPTY set — a real answer, never "
                  + "null (§12), and never Contributor's. Got " + ctx.permissions().asWireStrings())
                .isTrue();
        assertThat(ctx.explicitProjectRole())
                .withFailMessage("the member holds nothing because they inherit nothing, not because a project_members row said so")
                .isFalse();
    }

    /**
     * …but STRICT is not a way to lock an Owner out. The workspace-level
     * {@code project.curate.all} grant is unioned on top of a {@code null} project role, so
     * today's {@code requireProjectCurator} bypass survives — and stays exactly four
     * permissions wide, which is the §17.2 invariant that a null base must not disturb.
     */
    @Test
    @Transactional
    void strictDoesNotRevokeTheWorkspaceCuratorBypassNorWidenIt() {
        var ws = workspace();
        ws.setProjectAccessMode(ProjectAccessMode.STRICT);
        var owner = member(ws, "OWNER");
        var project = project(ws);
        em.flush();

        var ctx = workspaceAccess.resolveProject(owner, ws.getId(), project.getId());
        assertThat(ctx.projectRole()).as("STRICT must still yield no project ROLE for an Owner").isNull();
        for (var p : Permission.projectCuration()) {
            assertThat(ctx.permissions().has(p))
                    .withFailMessage(() -> "a workspace Owner lost " + p.key() + " in a STRICT workspace. The curator "
                      + "bypass is workspace-scoped (project.curate.all) and must union on top of "
                      + "a null project role — ScopeResolver.requireProjectCurator lets them "
                      + "through today regardless of any project membership.")
                    .isTrue();
        }
        assertThat(ctx.permissions().asWireStrings())
                .as(() -> "the Owner's STRICT project set is " + ctx.permissions().asWireStrings()
                  + ", which is more than the four curator permissions. With no project role to "
                  + "union against, the implied grant is the ONLY source of permissions here, so "
                  + "anything extra means project.curate.all has quietly grown (§17.2).")
                .hasSize(Permission.projectCuration().size());
    }

    // ======================================================= the other scope direction

    /**
     * {@code RoleSeamHardeningTest} refuses a WORKSPACE role found in
     * {@code project_members}. The mirror image — a PROJECT role in
     * {@code workspace_members.role_id} — goes through a different {@code requireRole(...)}
     * call, in {@code requireMember}, on the path taken by <em>every authenticated request
     * that names a workspace</em>. Nothing tested it.
     */
    @Test
    @Transactional
    void aProjectScopedRoleIsRefusedAsAWorkspaceMembersRole() {
        var ws = workspace();
        var actor = user();
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(actor);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "MEMBER"));
        workspaceMemberRepository.save(m);
        workspaceMemberRepository.flush();

        // Control: the SAME row with a correctly scoped role resolves. Without this the test
        // would pass just as happily on a fixture that never persisted the membership at all —
        // a missing membership throws WorkspaceNotFoundException too, from two lines earlier.
        assertThat(workspaceAccess.requireMember(actor, ws.getId()).permissions()
                .has(Permission.PROJECT_CREATE))
                .withFailMessage("fixture: the membership must resolve normally before its role is swapped")
                .isTrue();

        m.setRole(roleCatalog.reference(RoleScope.PROJECT, "MANAGER")); // a PROJECT-scoped built-in
        workspaceMemberRepository.save(m);
        workspaceMemberRepository.flush();

        try {
            var ctx = workspaceAccess.requireMember(actor, ws.getId());
            fail("a PROJECT role was accepted as a workspace role, and the caller now holds "
                      + ctx.permissions().asWireStrings() + " AT WORKSPACE SCOPE. A PermissionSet "
                      + "is a flat EnumSet with no memory of scope, so every workspace-scoped "
                      + "require(...) downstream would be satisfied honestly — and the role is a "
                      + "legitimate built-in belonging to no wrong workspace, so no tenancy check "
                      + "upstream can catch it.");
        } catch (WorkspaceNotFoundException expected) {
            // 404, never 403: a caller learns nothing from a consistency failure (§12).
        }
    }

    // =============================================== the converter's drop path, end to end

    /**
     * {@code RoleSeamHardeningTest} proves an unknown key is dropped when the role is read
     * directly. This proves the <em>request path</em> survives it: the drop happens inside
     * {@code RoleView.of} ← {@code RolePermissionCache} ← {@code requireMember}, so if the
     * null ever reached {@code PermissionSet.of} the failure would be an NPE on every
     * authenticated request naming the workspace — the outcome
     * {@code PermissionConverter}'s javadoc chose "drop and log" specifically to avoid.
     *
     * <p>Also covers the two shapes the direct test does not: an unknown key stored
     * {@code own_only = TRUE}, and a role whose grants are <em>all</em> unknown (the
     * downgrade-past-a-whole-feature case), which must resolve to the empty set rather than
     * to nothing at all.
     */
    @Test
    @Transactional
    void anUnknownStoredKeyIsDroppedOnTheLiveRequestPathAndNeverWidens() {
        var ws = workspace();
        var actor = user();
        var project = project(ws);

        var partiallyUnknown = customRole(ws, RoleScope.PROJECT, "half-future", Permission.ISSUE_CREATE);
        insertRawGrant(partiallyUnknown.getId(), "issue.teleport", true);
        insertRawGrant(partiallyUnknown.getId(), "sprint.summon", false);

        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(actor);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "MEMBER"));
        workspaceMemberRepository.save(m);
        projectMemberRow(project, actor, partiallyUnknown);
        em.flush();
        em.clear();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
        assertThat(ctx.permissions().asWireStrings())
                .as(() -> "resolving a role with unknown stored keys produced "
                  + ctx.permissions().asWireStrings() + ". The known grants must survive intact "
                  + "and the unknown ones must vanish: dropping can only ever NARROW (§ the "
                  + "PermissionConverter javadoc), and an own_only unknown row must not leak in "
                  + "as an own-only grant of anything.")
                .isEqualTo(java.util.List.of("issue.create"));
        for (var p : Permission.values()) {
            assertThat(p == Permission.ISSUE_CREATE || !ctx.permissions().hasAtAll(p))
                    .withFailMessage(() -> "dropping an unknown key WIDENED the role: it also granted " + p.key())
                    .isTrue();
        }

        // A role whose every grant is unknown — a downgrade past a whole feature. Resolution
        // must yield an empty set, which is a legitimate Viewer-shaped answer, not a 500.
        var allUnknown = customRole(ws, RoleScope.PROJECT, "all-future", null);
        insertRawGrant(allUnknown.getId(), "issue.teleport", false);
        em.flush();
        em.clear();

        var view = roleCatalog.view(allUnknown.getId());
        assertThat(view.permissions().isEmpty())
                .withFailMessage(() -> "a role whose grants are ALL unknown resolved to " + view.permissions()
                  + " instead of the empty set")
                .isTrue();
    }

    // ------------------------------------------------------------------ fixture

    private Workspace workspace() {
        var w = new Workspace();
        w.setName("Resolution");
        w.setSlug("res-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(user());
        return workspaceRepository.save(w);
    }

    private Project project(Workspace ws) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Resolution");
        p.setKey("RS" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(user());
        return projectRepository.save(p);
    }

    private User member(Workspace ws, String role) {
        var u = user();
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(u);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
        return u;
    }

    private void projectMemberRow(Project project, User user, Role role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(role);
        projectMemberRepository.save(m);
        projectMemberRepository.flush();
    }

    /** No roles CRUD until S4, and {@code RoleRepository} deliberately has no {@code save}. */
    private Role customRole(Workspace ws, RoleScope scope, String key, Permission grant) {
        var role = new Role();
        role.setWorkspaceId(ws.getId());
        role.setScope(scope);
        role.setKey(key);
        role.setName(key);
        role.setBuiltIn(false);
        if (grant != null) {
            role.getPermissions().add(new RolePermission(grant, false));
        }
        em.persist(role);
        em.flush();
        return role;
    }

    /** A {@code role_permissions} row the entity model cannot express: an unknown key. */
    private void insertRawGrant(UUID roleId, String key, boolean ownOnly) {
        em.createNativeQuery("INSERT INTO role_permissions (role_id, permission, own_only) "
                        + "VALUES (:id, :key, :own)")
                .setParameter("id", roleId)
                .setParameter("key", key)
                .setParameter("own", ownOnly)
                .executeUpdate();
    }

    private User user() {
        var u = new User();
        u.setEmail(("res-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Resolution");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }
}
