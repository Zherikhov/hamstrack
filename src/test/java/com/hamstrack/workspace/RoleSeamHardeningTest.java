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
import com.hamstrack.workspace.entity.WorkspaceInvite;
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

import static org.assertj.core.api.Assertions.assertThat;

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
     * <strong>The list endpoints apply the same assertion as the detail endpoints — and
     * DEGRADE rather than refusing</strong> (HD-127 §3b).
     *
     * <p>Both batch their membership rows into one query to dodge an N+1, so they resolve
     * {@code role_id}s themselves instead of going through {@code projectContext} /
     * {@code requireMember} — which is exactly how they came to be the two paths that read a
     * role without checking it. The <em>assertion</em> must therefore be identical; what S4
     * changed is only what happens when it fails.
     *
     * <p><strong>Why the blast radius moved.</strong> S1 failed the whole list, on the
     * argument that skipping a row silently is the "control that stopped appearing" failure.
     * That argument still holds — and this is not skipping. The entry stays, with
     * {@code myPermissions: []} and {@code myRole: null}: strictly the floor, so nothing that
     * was refused becomes permitted, and visibly wrong rather than silently absent. What it
     * buys is that one corrupt row no longer 404s a caller's entire project list, which is a
     * denial of service on data they cannot fix and often did not create.
     *
     * <p>Three things this must prove, and each is a separate way to get the degrade wrong:
     * <ol>
     *   <li>the permissions are <strong>empty</strong> — not the workspace role's, and
     *       emphatically not a fall-through to the §5.2 default, which would WIDEN a member
     *       whose narrow explicit row is exactly what the corruption destroyed;</li>
     *   <li>the refused role's <strong>key and name appear nowhere</strong> in the response —
     *       that name is the one genuine leak in the vicinity, and rendering it would defeat
     *       the whole check;</li>
     *   <li>the <strong>detail</strong> read of the same project still refuses, which is the
     *       intended asymmetry: a list is a directory, a detail read is an authorization.</li>
     * </ol>
     */
    @Test
    @Transactional
    void theProjectListDegradesACorruptRoleInsteadOfFailingTheWholeList() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");
        var project = project(ws);

        var wrongScope = customRole(ws, RoleScope.WORKSPACE, "list-sneaky", "Workspace-scoped");
        wrongScope.getPermissions().add(grant(Permission.WORKSPACE_EDIT));
        em.flush();
        projectMemberRow(project, actor, wrongScope);

        var listed = projectService.list(actor, ws.getId(), false);

        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().myPermissions())
                .as("a refused role must contribute NOTHING — not the workspace role's grants, "
                    + "and not the project-access default, which would widen the very member "
                    + "whose narrow row was corrupted")
                .isEmpty();
        assertThat(listed.getFirst().myRole())
                .as("the role the assertion just refused must never be rendered")
                .isNull();
        assertThat(listed.toString())
                .doesNotContain("list-sneaky")
                .doesNotContain("Workspace-scoped");

        // …and the detail path still refuses the same row. The asymmetry is the design.
        try {
            workspaceAccess.resolveProject(actor, ws.getId(), project.getId());
            assert false : "the detail path must keep failing closed on a corrupt role row";
        } catch (WorkspaceNotFoundException expected) {
            // as required
        }
    }

    /**
     * The workspace list has the same shape of gap, on {@code workspace_members} — and the
     * same degrade.
     *
     * <p><strong>The single-resource read must NOT degrade, and that is not a
     * conservatism.</strong> Degrading {@code requireMember} would not be "empty
     * permissions": in an {@code OPEN} workspace the project-role fallback is independent of
     * the workspace role, so an empty workspace role would still leave this caller inheriting
     * <strong>Contributor in every project of the workspace</strong>. Turning a 404 into
     * Contributor-everywhere is a widening, and an invisible one — which is why the workspace
     * appears in the list and 404s on {@code get}.
     */
    @Test
    @Transactional
    void theWorkspaceListDegradesACorruptRoleWhileTheDetailReadStillRefuses() {
        var ws = workspace();
        var actor = member(ws, "MEMBER");

        var wrongScope = customRole(ws, RoleScope.PROJECT, "ws-list-sneaky", "Project-scoped");
        em.flush();
        var membership = workspaceMemberRepository.findByWorkspaceAndUser(ws, actor).orElseThrow();
        membership.setRole(wrongScope);
        workspaceMemberRepository.flush();

        var listed = workspaceService.listForUser(actor);

        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().myPermissions()).isEmpty();
        assertThat(listed.getFirst().myRole()).isNull();
        assertThat(listed.toString())
                .doesNotContain("ws-list-sneaky")
                .doesNotContain("Project-scoped");

        try {
            workspaceService.get(actor, ws.getId());
            assert false : "the detail read must keep failing closed — degrading it would leave "
                           + "the caller inheriting Contributor in every project of an OPEN "
                           + "workspace, which is a widening, not an empty permission set";
        } catch (WorkspaceNotFoundException expected) {
            // as required
        }
    }

    /**
     * <strong>The single-resource WRITE paths refuse a third party's corrupt {@code role_id}
     * — they do not degrade, and they do not dereference it bare</strong> (HD-127 tenancy
     * review, round 2).
     *
     * <p>{@code ProjectService.updateMember} and {@code removeMember} both read <em>somebody
     * else's</em> {@code project_members.role_id} to evaluate the {@code ACTING_ON} half of
     * the grant ceiling. Round 1 read it with a bare {@code roleCatalog.view} — the exact
     * pattern {@code listMembers} was moved away from in the same diff — and two things follow
     * from a foreign or wrong-scope row: the refused role's <strong>name</strong> is rendered
     * into the {@code ProjectGrantCeilingException} detail the caller reads, and a
     * WORKSPACE-scoped role there supplies {@code workspace.*} as the comparand, because a
     * {@code PermissionSet} does not remember which scope its grants came from.
     *
     * <p>Unreachable through the API today — every write door resolves role ids through
     * {@code RoleRepository.findAssignable} — which is precisely the argument for asserting
     * rather than trusting: the guard costs one cache hit, and the thing protecting it
     * otherwise is that nobody has written the eleventh door yet.
     */
    @Test
    @Transactional
    void aCorruptRoleOnAnotherMembersRowRefusesTheProjectMemberWrites() {
        var ws = workspace();
        var project = project(ws);
        var actor = member(ws, "MEMBER");
        projectMemberRow(project, actor, roleCatalog.reference(RoleScope.PROJECT, "MANAGER"));

        var target = member(ws, "MEMBER");
        var wrongScope = customRole(ws, RoleScope.WORKSPACE, "pm-sneaky", "Workspace-scoped impostor");
        wrongScope.getPermissions().add(grant(Permission.WORKSPACE_EDIT));
        em.flush();
        projectMemberRow(project, target, wrongScope);

        try {
            projectService.updateMember(actor, ws.getId(), project.getId(), target.getId(),
                    new com.hamstrack.project.dto.UpdateProjectMemberRequest(
                            BuiltInRoles.PROJECT_MEMBER));
            assert false : "a WORKSPACE-scoped role_id on another member's row was dereferenced "
                           + "as their current PROJECT role. Its name reaches the ceiling 403 "
                           + "and its workspace.* grants become the ACTING_ON comparand.";
        } catch (WorkspaceNotFoundException expected) {
            // 404, and it says nothing about the role it refused.
        }

        try {
            projectService.removeMember(actor, ws.getId(), project.getId(), target.getId());
            assert false : "removeMember reads the same row for the same reason and must refuse "
                           + "identically — one of the two guarded is not a guard";
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

    // ============================================== the invite seam (round-3 review)

    /**
     * <strong>An invite's {@code role_id} is judged when it is used, not only when it was
     * issued.</strong>
     *
     * <p>{@code workspace_invites} is the one place a role id is copied from one table to
     * another without ever being re-judged: it goes through {@code requireAssignable} at
     * {@code POST /invites} and is then written straight onto {@code workspace_members} on
     * acceptance, possibly weeks later. That failed <em>closed</em> either way — every
     * subsequent request runs {@code requireRole} inside {@code requireMember}, so a
     * wrong-scope id yields 404 rather than {@code workspace.*} grants in a
     * {@code ProjectContext} — but the row it wrote is <strong>unremovable through the
     * API</strong>: {@code WorkspaceMemberService.requireWorkspaceRole} refuses rather than
     * degrades, so nobody can DELETE the member the invite let in. Refusing before the insert
     * is what makes the bad row unwritable rather than merely inert.
     */
    @Test
    @Transactional
    void acceptingAnInviteWhoseRoleIsWrongScopeIsRefusedRatherThanWritten() {
        var ws = workspace();
        var invitee = user();
        var wrongScope = customRole(ws, RoleScope.PROJECT, "invite-sneaky", "Project-scoped");
        wrongScope.getPermissions().add(grant(Permission.ISSUE_CREATE));
        var invite = invite(ws, invitee, wrongScope);
        em.flush();

        try {
            workspaceService.acceptInvite(invitee, invite.getId());
            assert false : "the invite path wrote an unjudged role_id onto workspace_members — "
                           + "and that membership row cannot then be removed through the API, "
                           + "because every read of it refuses rather than degrades";
        } catch (WorkspaceNotFoundException expected) {
            // as required — and the ERROR line names workspace_invites, not workspace_members
        }
        assert workspaceMemberRepository.findByWorkspaceAndUser(ws, invitee).isEmpty()
                : "a refused acceptance must leave no membership behind";
    }

    /**
     * …and the <em>list</em> half degrades, like every other collection read.
     *
     * <p>This is the only bare role read in the product performed for somebody who is not yet
     * a member of the workspace that owns the role: pending invites are fetched by email
     * across every workspace, so a corrupt or foreign {@code role_id} would print a role name
     * a stranger has no relationship to onto their onboarding screen. The entry survives with
     * {@code role: null} — one bad invite must not empty the "join a team" list — and the
     * refused role's key is exactly what must not appear.
     */
    @Test
    @Transactional
    void thePendingInviteListDegradesACorruptRoleInsteadOfNamingIt() {
        var ws = workspace();
        var invitee = user();
        var wrongScope = customRole(ws, RoleScope.PROJECT, "invite-list-sneaky", "Project-scoped");
        invite(ws, invitee, wrongScope);
        em.flush();

        var pending = workspaceService.listPendingInvites(invitee);

        assert pending.size() == 1 : "one bad invite must not empty the whole list";
        assert pending.getFirst().role() == null
                : "the refused role's key was rendered anyway: " + pending.getFirst().role();
        assert !pending.toString().contains("invite-list-sneaky")
               && !pending.toString().contains("Project-scoped")
                : "the response leaked the role we just refused: " + pending;
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

    /**
     * A live, unaccepted invitation carrying {@code role} — written through the
     * {@link EntityManager} because {@code POST /invites} would (correctly) refuse the
     * wrong-scope role these tests are about.
     */
    private WorkspaceInvite invite(Workspace ws, User invitee, Role role) {
        var invite = new WorkspaceInvite();
        invite.setWorkspace(ws);
        invite.setEmail(invitee.getEmail());
        invite.setRole(role);
        invite.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        invite.setInvitedBy(ws.getCreatedBy());
        invite.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
        em.persist(invite);
        em.flush();
        return invite;
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
