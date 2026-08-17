package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * <strong>The measurement behind roles-permissions-proposal §9.2</strong> — that adding a
 * whole permission model costs a bounded, tiny, <em>constant</em> number of statements per
 * request, and that checking a permission at a call site costs nothing at all.
 *
 * <p>The claim is easy to break by accident and impossible to notice by hand. Three things
 * would silently turn it into an N+1:
 * <ul>
 *   <li>dropping {@code JOIN FETCH m.role} from a membership finder — the role is a lazy
 *       {@code @ManyToOne} and the resolver reads its id immediately, so a proxy
 *       initialisation would add one SELECT to <em>every authenticated request</em>;</li>
 *   <li>looking a built-in role up by {@code (scope, key)} instead of by its fixed id;</li>
 *   <li>calling the {@code @Cacheable} method from inside the resolver bean, where Spring's
 *       proxy does not apply and the cache is silently bypassed.</li>
 * </ul>
 *
 * <p>The absolute numbers are asserted deliberately, unlike {@code LabelQueryCountTest}'s
 * relative comparison: here the exact count <em>is</em> the design claim (§9.2 promises 2
 * and 4), and a tripwire that fires when it moves is the point.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class PermissionResolutionQueryCountTest {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired WorkspaceAccessService workspaceAccess;
    @Autowired RoleCatalog roleCatalog;

    @Test
    void resolvingPermissionsCostsTwoStatementsPerWorkspaceAndFourPerProject() {
        var actor = user();
        var ws = workspace(actor);
        member(ws, actor, WorkspaceRole.OWNER);
        var project = project(ws, actor);
        projectMember(project, actor, ProjectRole.MANAGER);

        // Warm the role cache. The first resolution in a fresh process also loads the two
        // built-in roles (one statement each, permissions JOIN FETCHed) — bounded by the
        // number of DISTINCT ROLES IN THE INSTALL, not by traffic, which is the whole
        // point of caching by role id rather than by member.
        workspaceAccess.resolveProject(actor, ws.getId(), project.getId());

        long workspaceScoped = count(() -> workspaceAccess.requireMember(actor, ws.getId()));
        assert workspaceScoped == 2
                : "a workspace-scoped resolution took " + workspaceScoped + " statements, not 2 "
                  + "(workspace + membership). §9.2 promises the permission model adds ZERO: if this "
                  + "is 3, the membership finder lost its JOIN FETCH on m.role, or the role -> "
                  + "permissions cache is being bypassed (@Cacheable does not apply to a "
                  + "self-invocation — that is why RolePermissionCache is a separate bean).";

        long projectScoped = count(() -> workspaceAccess.resolveProject(actor, ws.getId(), project.getId()));
        assert projectScoped == 4
                : "a project-scoped resolution took " + projectScoped + " statements, not 4 "
                  + "(workspace + membership + project + explicit project membership). See above.";

        // A member with NO explicit project row is the common case (§2.3) and takes the
        // default-role branch. It must not cost more: the default chain reads ids off rows
        // already in hand and resolves them through the cache.
        var noRow = user();
        member(ws, noRow, WorkspaceRole.MEMBER);
        workspaceAccess.resolveProject(noRow, ws.getId(), project.getId()); // warm
        long inherited = count(() -> workspaceAccess.resolveProject(noRow, ws.getId(), project.getId()));
        assert inherited == 4
                : "resolving the INHERITED project role took " + inherited + " statements, not 4. "
                  + "The default chain (project -> workspace -> built-in Contributor) must read ids "
                  + "off entities already loaded and resolve them from the cache — never navigate "
                  + "the defaultProjectRole @ManyToOne, which is mapped read-only for exactly this "
                  + "reason.";
    }

    @Test
    void checkingAPermissionAtACallSiteCostsNothing() {
        var actor = user();
        var ws = workspace(actor);
        member(ws, actor, WorkspaceRole.OWNER);
        var project = project(ws, actor);
        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());

        long checks = count(() -> {
            for (var p : com.hamstrack.common.security.Permission.values()) {
                ctx.permissions().has(p);
                ctx.permissions().has(p, true);
            }
            ctx.permissions().asWireStrings();
        });
        assert checks == 0
                : "checking all 29 permissions cost " + checks + " statements. Rule P1 (§5.1) is that "
                  + "a handler checking six permissions costs exactly what one checking none costs; "
                  + "a PermissionSet is an in-memory value and must never touch the database.";
    }

    // ------------------------------------------------------------------ plumbing

    private long count(Runnable body) {
        var stats = statistics();
        stats.clear();
        body.run();
        return stats.getPrepareStatementCount();
    }

    private Statistics statistics() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

    private User user() {
        var u = new User();
        u.setEmail(("qc-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Query count");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("QC");
        w.setSlug("qc-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User user, WorkspaceRole role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(role));
        workspaceMemberRepository.save(m);
    }

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("QC");
        p.setKey("Q" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    private void projectMember(Project project, User user, ProjectRole role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(role));
        projectMemberRepository.save(m);
    }
}
