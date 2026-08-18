package com.hamstrack.project;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-136 review round 4 — <strong>{@code ProjectAdminGuard.lockAdmins} must not ask the
 * inheritance question when there is nobody to exclude from it.</strong>
 *
 * <p>The guard used to gate its "somebody would inherit {@code project.member.manage}
 * anyway" proof on {@code userIds.size() <= 1} and pass
 * {@code userIds.stream().findFirst().orElse(null)} as the member being removed. With
 * <em>zero</em> administrators that bind is null, and in JPQL {@code m.user.id <>
 * :excludedUserId} is UNKNOWN for every row against a null — so
 * {@code existsActiveMemberWithoutProjectRole} answered {@code false} unconditionally. The
 * predicate silently meant "nobody inherits" instead of "exclude nobody", and the answer
 * came out right only because a project with no administrator is already an empty answer
 * two branches earlier.
 *
 * <p>Outcome-equivalent, therefore invisible to every behavioural test in the suite — the
 * cost is a wasted round trip and a correctness argument that has to be re-derived from
 * another class's null semantics. <strong>The statement count is the only place the
 * difference shows</strong>, which is why this is a query-count test and why it asserts
 * both directions: the question must disappear when it cannot matter, and must still be
 * asked when it can.
 *
 * <p>Properties match {@code PermissionResolutionQueryCountTest}'s exactly so the two share
 * one application context.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class ProjectAdminGuardQueryCountTest {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired RoleCatalog roleCatalog;
    @Autowired ProjectAdminGuard guard;

    /**
     * <strong>No administrators, no inheritance query.</strong> Two statements: which roles
     * grant {@code project.member.manage}, and the locking read of the members holding one.
     * A third would be the null-excluded existence check, asked about a removal that cannot
     * happen — the project has nobody to lose.
     *
     * <p>The fixture makes the fallback grant {@code project.member.manage} on purpose: with
     * the shipped default (Contributor, which does not) {@code cannotBeStranded}
     * short-circuits before the query and the regression would hide.
     */
    @Test
    void aProjectWithNoAdministratorsAsksNobodyWhoWouldInheritTheRole() {
        var f = fixture();

        assertThat(statementsFor(f.projectId()))
                .as("a null-excluded 'does anybody inherit this?' is a query whose answer is "
                    + "fixed by the null, asked about a stranding that cannot occur")
                .isEqualTo(2);
        // …and the answer is unchanged: a project with no administrator is not protected.
        Set<UUID> admins = txTemplate.execute(s ->
                guard.lockAdmins(projectRepository.findById(f.projectId()).orElseThrow()).userIds());
        assertThat(admins).isEmpty();
    }

    /**
     * The control, without which the test above would also pass if the check were deleted
     * outright. <strong>One administrator is exactly when the question matters</strong> —
     * their removal is the one this answer can be about — so the third statement reappears,
     * this time with a real member to exclude.
     */
    @Test
    void aProjectWithOneAdministratorStillAsksIt() {
        var f = fixture();
        projectMember(f.projectId(), f.member(), "MANAGER");

        assertThat(statementsFor(f.projectId()))
                .as("the inheritance proof is what turns 'the default grants it' from a "
                    + "plausible approximation into a proof — it must survive")
                .isEqualTo(3);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Counted inside one transaction, with the project loaded and its workspace proxy
     * initialised first — that is the state {@code ProjectService.removeMember} calls the
     * guard in (the project comes from an already-resolved {@code ProjectContext}), so a
     * lazy load that production has already paid for is not charged to the guard.
     */
    private long statementsFor(UUID projectId) {
        // Warm the role caches in a throwaway transaction: a built-in role's permissions are
        // loaded once per process, and charging that to the first caller measures nothing.
        txTemplate.executeWithoutResult(s ->
                guard.lockAdmins(projectRepository.findById(projectId).orElseThrow()));
        return txTemplate.execute(s -> {
            var project = projectRepository.findById(projectId).orElseThrow();
            project.getWorkspace().getProjectAccessMode();
            var stats = statistics();
            stats.clear();
            guard.lockAdmins(project);
            return stats.getPrepareStatementCount();
        });
    }

    private Statistics statistics() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

    private record Fixture(UUID projectId, User member) {}

    /**
     * A workspace whose projects fall back to the built-in Project admin. Contrived — no
     * real install would hand member management to every member by default — and that is
     * the point: it is the only configuration in which {@code cannotBeStranded} reaches its
     * second half at all.
     */
    private Fixture fixture() {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        var other = user();
        member(ws, other, "MEMBER");
        var project = project(ws, owner);
        project.setDefaultProjectRoleId(roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        projectRepository.save(project);
        return new Fixture(project.getId(), other);
    }

    private User user() {
        var u = new User();
        u.setEmail(("gqc-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Guard query count");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("GQC");
        w.setSlug("gqc-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
    }

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("GQC");
        p.setKey("G" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    private void projectMember(UUID projectId, User user, String role) {
        var m = new ProjectMember();
        m.setProject(projectRepository.findById(projectId).orElseThrow());
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, role));
        projectMemberRepository.save(m);
    }
}
