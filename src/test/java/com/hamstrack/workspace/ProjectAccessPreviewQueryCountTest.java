package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.ProjectAccessMode;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.ProjectAccessImpact;
import com.hamstrack.workspace.service.ProjectAccessProposal;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-130 (S7) — <strong>AC 28 and AC 21: the impact preview costs the same four statements for
 * a workspace with two projects and one with sixty, and the shipped configuration issues no
 * inheritance existence query at all.</strong>
 *
 * <p>Both are properties nothing behavioural can see. The preview is aggregate arithmetic over
 * three grouped reads plus one role-id lookup; written the obvious way — a per-project member
 * count, or a {@code cannotBeStranded} probe per project — it would answer identically and turn
 * an admin screen into an N+1 over the whole workspace, on an endpoint the confirm dialog
 * re-fetches on every open. The statement count is the only place that difference shows.
 *
 * <p>Properties match {@code PermissionResolutionQueryCountTest}'s exactly so the two share one
 * application context.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class ProjectAccessPreviewQueryCountTest {

    /**
     * The four reads of §4.2: live projects, ACTIVE workspace members grouped by role, ACTIVE
     * explicit project memberships grouped by (project, project role, workspace role), and the
     * role ids that grant {@code project.member.manage}.
     */
    private static final int EXPECTED_STATEMENTS = 4;

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired RoleCatalog roleCatalog;
    @Autowired ProjectAccessImpact impact;

    /**
     * <strong>AC 28 + AC 21.</strong> Two projects and sixty projects cost the same, and in the
     * shipped configuration — workspace default NULL → Contributor, which does not grant
     * {@code project.member.manage} — a flip to {@code STRICT} asks nobody who would inherit
     * anything, because the cheap bit test has already answered.
     */
    @Test
    void thePreviewCostsFourStatementsWhateverTheProjectCount() {
        var small = statementsForFlipToStrict(workspaceWith(2));
        var large = statementsForFlipToStrict(workspaceWith(60));

        assertThat(small)
                .as("the shipped configuration must not reach existsActiveMemberWithoutProjectRole "
                    + "at all: the workspace default is Contributor, which does not manage "
                    + "members, so doors 7-9 are one bit test per project and zero queries")
                .isEqualTo(EXPECTED_STATEMENTS);
        assertThat(large)
                .as("the impact preview grew with the project count — a per-project count or a "
                    + "per-project stranding probe turns an admin screen into an N+1 over the "
                    + "whole workspace, on a body the confirm dialog re-fetches on every open")
                .isEqualTo(small);
    }

    /**
     * The control, without which the test above would also pass if the stranding check were
     * deleted outright: with an <em>administering</em> default the guard does reach its
     * per-candidate proof, and the count then does grow — which is exactly why §5.4 documents
     * it as the rare, contrived shape rather than hiding it.
     */
    @Test
    void anAdministeringDefaultDoesReachThePerCandidateProof() {
        var workspaceId = workspaceWith(2);
        txTemplate.executeWithoutResult(s -> {
            var ws = workspaceRepository.findById(workspaceId).orElseThrow();
            ws.setDefaultProjectRoleId(BuiltInRoles.PROJECT_TEAM_LEAD);
            workspaceRepository.save(ws);
        });

        assertThat(statementsForFlipToStrict(workspaceId))
                .as("with a default that DOES manage members, both halves of the proof must run "
                    + "— 'the default grants it' alone is wrong in the dangerous direction")
                .isGreaterThan(EXPECTED_STATEMENTS);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Counted with the workspace already resolved, because that is the state the endpoints call
     * the model in: {@code requireMember} has run and its two statements are charged to the
     * tenancy check, not to the preview.
     */
    private long statementsForFlipToStrict(UUID workspaceId) {
        // Warm the role caches and the JPA metamodel in a throwaway transaction: a built-in
        // role's permissions load once per process, and charging that to the first caller
        // measures nothing.
        txTemplate.executeWithoutResult(s -> impactOf(workspaceId));
        return txTemplate.execute(s -> {
            var ws = workspaceRepository.findById(workspaceId).orElseThrow();
            var stats = statistics();
            stats.clear();
            impact.of(ws, ProjectAccessProposal.workspace(
                    ProjectAccessMode.STRICT, ws.getDefaultProjectRoleId()));
            return stats.getPrepareStatementCount();
        });
    }

    private void impactOf(UUID workspaceId) {
        var ws = workspaceRepository.findById(workspaceId).orElseThrow();
        impact.of(ws, ProjectAccessProposal.workspace(
                ProjectAccessMode.STRICT, ws.getDefaultProjectRoleId()));
    }

    private Statistics statistics() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

    /** A workspace with three ACTIVE members and {@code projectCount} live projects. */
    private UUID workspaceWith(int projectCount) {
        var owner = user();
        var ws = new Workspace();
        ws.setName("PAQ");
        ws.setSlug("paq-" + UUID.randomUUID().toString().substring(0, 12));
        ws.setCreatedBy(owner);
        workspaceRepository.save(ws);
        member(ws, owner, "OWNER");
        member(ws, user(), "ADMIN");
        member(ws, user(), "MEMBER");
        for (int i = 0; i < projectCount; i++) {
            var p = new Project();
            p.setWorkspace(ws);
            p.setName("PAQ " + i);
            p.setKey("Q" + Math.abs(UUID.randomUUID().hashCode() % 1000000));
            p.setCreatedBy(owner);
            projectRepository.save(p);
        }
        return ws.getId();
    }

    private void member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
    }

    private User user() {
        var u = new User();
        u.setEmail(("paq-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Preview query count");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }
}
