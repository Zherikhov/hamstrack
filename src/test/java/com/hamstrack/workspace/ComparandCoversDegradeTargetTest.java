package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.SettableRoles;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-130 (S7) — <strong>the invariant that lets
 * {@code ProjectAccessService.requireReactivatedDefaultsAreSettable} skip a degraded
 * default</strong> (security review round 3, the first Low).
 *
 * <p>That guard bounds every per-project declared default a {@code STRICT → OPEN} flip is about
 * to make live, and it <em>skips</em> the ones whose stored {@code role_id} fails the
 * scope+ownership assertion. The comment used to justify the skip by claiming a degraded default
 * "grants nothing at read time". It does not: {@code WorkspaceAccessService.fallback} returns
 * {@code roleCatalog.defaultProjectRole()} — the <strong>built-in Contributor</strong> — on
 * exactly that branch, which is what {@code RoleSeamHardeningTest}'s
 * {@code aBadWorkspaceDefaultRoleFallsBackToContributorInsteadOfBeingHonoured} already pins and
 * what {@code ProjectAccessService.defaultRoleName} renders.
 *
 * <p>The skip is nonetheless safe, for a different reason, and this is it:
 *
 * <blockquote><strong>{@code SettableRoles.workspaceComparand(anySet)} always covers
 * {@code roleCatalog.defaultProjectRole().permissions()}.</strong></blockquote>
 *
 * <p>True by construction today — the comparand <em>is</em>
 * {@code effectiveProjectPermissions(actorWorkspacePermissions, builtInContributor)}, and that
 * method only ever unions onto the role's own set — but "by construction" spans two classes and
 * neither of them says so. Either half moving alone re-opens the hole silently:
 * <ul>
 *   <li>a comparand that stopped starting from Contributor — the curator four alone, say, which
 *       §3.1 already records as a tempting and wrong alternative — would make the skip bypass a
 *       ceiling that would otherwise have bitten (case 1);</li>
 *   <li>a degrade fallback that resolved to the <em>workspace</em> default instead of the
 *       built-in Contributor would make the skipped role an arbitrary one (case 2).</li>
 * </ul>
 * Nothing else in the suite fails in either case: the skip has no observable behaviour of its
 * own until the two disagree, and by then it is a silent grant.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class ComparandCoversDegradeTargetTest {

    @Autowired SettableRoles settableRoles;
    @Autowired RoleCatalog roleCatalog;
    @Autowired WorkspaceAccessService workspaceAccess;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;

    @PersistenceContext EntityManager em;

    /**
     * <strong>Case 1 — the comparand covers the degrade target, whoever the actor is.</strong>
     *
     * <p>Quantified over the workspace permission sets that can reach the guard rather than over
     * one of them: the caller holds {@code workspace.edit} or they never got here, but nothing
     * says what else they hold, and the two workspace-scoped project grants
     * ({@code project.curate.all} / {@code project.administer.all}) are exactly what makes the
     * comparand vary. The empty set is the strongest case and the one that breaks first — it is
     * the comparand of a custom workspace role holding {@code workspace.edit} and nothing about
     * projects at all.
     */
    @Test
    void theWorkspaceComparandCoversTheDegradeTarget() {
        var degradeTarget = roleCatalog.defaultProjectRole();
        var actors = List.of(
                PermissionSet.empty(),
                PermissionSet.granting(Set.of(Permission.WORKSPACE_EDIT)),
                PermissionSet.granting(Set.of(Permission.PROJECT_CURATE_ALL)),
                PermissionSet.granting(Set.of(Permission.PROJECT_ADMINISTER_ALL)),
                PermissionSet.allOf(RoleScope.WORKSPACE));

        for (var actor : actors) {
            assertThat(settableRoles.workspaceComparand(actor)
                    .firstNotCovered(degradeTarget.permissions()))
                    .as("workspaceComparand(%s) does not cover the built-in %s, which is what a "
                        + "degraded per-project default actually grants. "
                        + "requireReactivatedDefaultsAreSettable skips those, and that skip "
                        + "becomes a silent bypass of the STRICT->OPEN ceiling the moment this "
                        + "stops holding", actor.asWireStrings(), degradeTarget.name())
                    .isEmpty();
        }
    }

    /**
     * <strong>Case 2 — the degrade target really is that role, on the exact column the guard
     * skips.</strong>
     *
     * <p>{@code RoleSeamHardeningTest} pins the same fallback for the <em>workspace</em>
     * default; the guard's skip is about the <em>project</em> one, and the two are separate
     * arguments to {@code WorkspaceAccessService.fallback}. Asserted as set equality rather than
     * as "holds issue.create", and against a deliberately wider workspace default: a fallback
     * that quietly read the other end of the chain would pass a containment check whenever that
     * end happened to be wider, which is precisely the change this case exists to catch.
     */
    @Test
    @Transactional
    void aBadProjectDefaultDegradesToExactlyTheRoleTheComparandCovers() {
        var ws = workspace();
        var actor = member(ws);
        var project = project(ws);

        ws.setDefaultProjectRoleId(BuiltInRoles.PROJECT_MANAGER);
        project.setDefaultProjectRoleId(wrongScopeRole(ws).getId());
        em.flush();

        var ctx = workspaceAccess.resolveProject(actor, ws.getId(), project.getId());

        assertThat(ctx.permissions().asWireStrings())
                .as("a corrupt project default must degrade to the built-in Contributor — the "
                    + "role requireReactivatedDefaultsAreSettable's skip assumes it degrades to, "
                    + "and the only role its comparand is guaranteed to cover")
                .containsExactlyInAnyOrderElementsOf(
                        roleCatalog.defaultProjectRole().permissions().asWireStrings());
        assertThat(ctx.permissions().has(Permission.PROJECT_ARCHIVE))
                .as("the wider WORKSPACE default was honoured for a project whose own default is "
                    + "corrupt: the degrade target is then an arbitrary role and the guard's skip "
                    + "no longer has a ceiling behind it")
                .isFalse();
    }

    // ------------------------------------------------------------------ fixture

    private Workspace workspace() {
        var w = new Workspace();
        w.setName("Comparand");
        w.setSlug("cmp-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(user());
        return workspaceRepository.save(w);
    }

    private Project project(Workspace ws) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Comparand");
        p.setKey("CM" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(user());
        return projectRepository.save(p);
    }

    private User member(Workspace ws) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user());
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "MEMBER"));
        return workspaceMemberRepository.save(m).getUser();
    }

    /** Persisted through the {@link EntityManager}: no write path would accept this row. */
    private Role wrongScopeRole(Workspace ws) {
        var role = new Role();
        role.setWorkspaceId(ws.getId());
        role.setScope(RoleScope.WORKSPACE);
        role.setKey("bad-project-default");
        role.setName("Workspace-scoped");
        role.setBuiltIn(false);
        em.persist(role);
        em.flush();
        return role;
    }

    private User user() {
        var u = new User();
        u.setEmail(("cmp-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Comparand");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }
}
