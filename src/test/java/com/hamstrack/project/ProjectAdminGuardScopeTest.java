package com.hamstrack.project;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.ComponentTestBase;
import com.hamstrack.project.dto.ProjectRef;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import com.hamstrack.workspace.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-136 — the two properties of {@code ProjectAdminGuard} that <strong>cannot be seen
 * through the HTTP surface</strong>, and which nothing else therefore pins.
 *
 * <ul>
 *   <li><strong>The tenant scope of the new workspace-wide locking read.</strong> The 409
 *       body is built from a <em>second</em>, separately scoped load
 *       ({@code ProjectRepository.findAllByWorkspaceAndIdIn}), which silently launders a
 *       foreign hit out of the response — so an unscoped locking query is invisible
 *       end-to-end: the removal still answers 204 and still looks correct from outside.
 *       What it left behind is real all the same: this tenant's transaction holding
 *       {@code FOR UPDATE} locks on another tenant's {@code project_members} rows, and this
 *       workspace's invariant decided from data over there. Verified by asking the query
 *       directly, because that is the only place the difference shows.
 *       <em>(Confirmed by mutation: dropping the workspace predicate from
 *       {@code lockAllByWorkspaceAndRoleIdIn} leaves the whole suite green without this
 *       test.)</em></li>
 *   <li><strong>The read being a lock at all.</strong> Two concurrent removals of the two
 *       administrators of one project must not each see the other and each proceed — the
 *       exact race the {@code PESSIMISTIC_WRITE} exists to close, and one that no
 *       single-threaded test can tell apart from an ordinary read.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ProjectAdminGuardScopeTest extends ComponentTestBase {

    @Autowired ProjectAdminGuard guard;
    @Autowired RoleRepository roleRepository;

    // ==================================================== tenant scope

    /**
     * <strong>The workspace-wide locking read must not cross the tenant boundary.</strong>
     * One person is the sole administrator of a project in each of two workspaces; the read
     * issued for one workspace may only ever see that workspace's row, and the guard built
     * on it may only ever name that workspace's project.
     *
     * <p>A {@code User} is global, which is what makes this the top bug class here: the
     * predicate that keeps the two apart is the workspace subquery, and nothing in the
     * response would show its absence.
     */
    @Test
    void theWorkspaceWideLockOnlyEverSeesThisWorkspacesRows() throws Exception {
        var here = newProject();                    // its own project keeps the ctx owner
        var mia = actorWith(here, "MEMBER", null);  // a workspace member, no project row yet
        var hereWs = workspaceRepository.findById(here.wsId()).orElseThrow();
        // A project of THIS workspace that only Mia administers…
        var hereProject = project(hereWs, here.owner());
        projectMember(hereProject, mia.user(), "MANAGER");

        // …and the same person, sole administrator of a project in a DIFFERENT workspace.
        var thereWs = workspace(mia.user());
        member(thereWs, mia.user(), "OWNER");
        var thereProject = project(thereWs, mia.user());
        projectMember(thereProject, mia.user(), "MANAGER");

        var roleIds = List.copyOf(roleRepository.findIdsGranting(
                hereWs.getId(), RoleScope.PROJECT, Permission.PROJECT_MEMBER_MANAGE));

        var locked = txTemplate.execute(s ->
                projectMemberRepository.lockAllByWorkspaceAndRoleIdIn(hereWs, roleIds).stream()
                        .map(ProjectMember::getId).toList());
        var hereAdminRowIds = List.of(
                rowId(projectRepository.findById(here.projectId()).orElseThrow(), here.owner()),
                rowId(hereProject, mia.user()));
        assertThat(locked)
                .as("a workspace-wide FOR UPDATE that forgot its workspace predicate locks "
                    + "another tenant's membership rows, and no response would ever show it")
                .containsExactlyInAnyOrderElementsOf(hereAdminRowIds);

        // …and the guard built on it answers about this workspace only.
        var stranded = txTemplate.execute(s -> guard.lockStrandedProjects(hereWs, mia.user().getId()));
        assertThat(ids(stranded)).containsExactly(hereProject.getId());

        // The mirror, so neither direction is asserted by accident.
        var strandedThere = txTemplate.execute(s ->
                guard.lockStrandedProjects(thereWs, mia.user().getId()));
        assertThat(ids(strandedThere)).containsExactly(thereProject.getId());
    }

    /**
     * The role lookup is scoped for the same reason (§12): built-in role templates are
     * shared, a workspace's custom roles are not. A guard that counted holders of
     * <em>another</em> workspace's role would be reading one tenant's catalog to decide
     * another tenant's invariant.
     */
    @Test
    void theRoleLookupSeesBuiltInsAndThisWorkspacesCustomRolesOnly() throws Exception {
        var here = newProject();
        var thereWs = workspace(here.owner());

        var mine = customProjectRole(here.wsId(), "local-lead", Permission.PROJECT_MEMBER_MANAGE);
        var theirs = customProjectRole(thereWs.getId(), "foreign-lead", Permission.PROJECT_MEMBER_MANAGE);
        var unrelated = customProjectRole(here.wsId(), "local-dev", Permission.ISSUE_CREATE);

        var granting = roleRepository.findIdsGranting(
                here.wsId(), RoleScope.PROJECT, Permission.PROJECT_MEMBER_MANAGE);

        assertThat(granting)
                .as("the built-in Project admin and this workspace's own team lead both grant it")
                .contains(roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id(), mine);
        assertThat(granting)
                .as("another workspace's role, and a role that does not grant it, are not administrators here")
                .doesNotContain(theirs, unrelated);
    }

    // ==================================================== the lock itself

    /**
     * <strong>Two administrators, two overlapping removals, one survivor.</strong> The
     * project has exactly two administrators and one removal is in flight, holding its
     * transaction open, when the second arrives. Without the {@code PESSIMISTIC_WRITE}
     * read the second sees two administrators — the first's delete is not committed yet —
     * concludes it is not taking the last one, and the project ends with none. That is the
     * whole reason the guard's read is a lock, and is taken before anything else.
     *
     * <p>Deterministic in the green direction: whether the second request blocks on the
     * lock or arrives after the commit, the correct answer is the same 409. It can only
     * fail if the read stops being a lock.
     */
    @Test
    @Timeout(60)
    void aConcurrentRemovalCannotSlipPastTheLastAdministratorGuard() throws Exception {
        var ctx = newProject();                            // owner: administrator #1
        var second = actorWith(ctx, "MEMBER", "MANAGER");  // administrator #2
        var project = projectRepository.findById(ctx.projectId()).orElseThrow();

        var locked = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var slowRemoval = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(s -> {
                    // Exactly what ProjectService.removeMember does first, on a project
                    // read inside the transaction as that method's does.
                    var managed = projectRepository.findById(ctx.projectId()).orElseThrow();
                    guard.lockAdmins(managed);
                    var owner = projectMemberRepository
                            .findByProjectAndUser(managed, ctx.owner()).orElseThrow();
                    projectMemberRepository.delete(owner);
                    projectMemberRepository.flush();
                    locked.countDown();
                    // …and then holds the transaction open long enough for a second removal
                    // to run to completion, if nothing is stopping it.
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
            }
        });
        slowRemoval.start();
        if (!locked.await(20, TimeUnit.SECONDS)) {
            slowRemoval.join();
            throw new AssertionError("the in-flight removal never reached its lock", failure.get());
        }

        mockMvc.perform(delete("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                                + "/members/" + second.user().getId())
                        .header("Authorization", "Bearer " + second.token()))
                .andExpect(status().isConflict());

        slowRemoval.join();
        assertThat(failure.get()).isNull();

        assertThat(projectMemberRepository.findByProjectAndUser(project, ctx.owner()))
                .as("the in-flight removal itself must still have gone through")
                .isEmpty();
        assertThat(projectMemberRepository.findByProjectAndUser(project, second.user()))
                .as("both administrators are gone — the project is unmanageable, which is "
                    + "the race the lock exists to close")
                .isPresent();
    }

    // ==================================================== helpers

    private static List<UUID> ids(ProjectAdminGuard.StrandedProjects stranded) {
        return stranded.projects().stream().map(ProjectRef::id).toList();
    }

    /** The id of one {@code project_members} row. */
    private UUID rowId(com.hamstrack.project.entity.Project project, com.hamstrack.auth.entity.User user) {
        return projectMemberRepository.findByProjectAndUser(project, user).orElseThrow().getId();
    }

    /** A workspace-owned PROJECT role granting exactly {@code permissions}. */
    private UUID customProjectRole(UUID workspaceId, String key, Permission... permissions) {
        return txTemplate.execute(s -> {
            var role = new Role();
            role.setWorkspaceId(workspaceId);
            role.setScope(RoleScope.PROJECT);
            role.setKey(key + "-" + UUID.randomUUID().toString().substring(0, 8));
            role.setName(key);
            role.setBuiltIn(false);
            var grants = new LinkedHashSet<RolePermission>();
            for (var p : permissions) grants.add(new RolePermission(p, false));
            role.setPermissions(grants);
            entityManager.persist(role);
            entityManager.flush();
            return role.getId();
        });
    }
}
