package com.hamstrack.workspace;

import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>The test {@link BuiltInRoles} says exists</strong> — "{@code
 * RoleIdsMatchMigrationTest} pins the two lists together so they cannot drift". It did not,
 * until now.
 *
 * <p>The seven built-in role ids are written out three times: in {@code V13__roles.sql},
 * in {@link BuiltInRoles}, and (deliberately, so a change has to be made twice) in
 * {@link com.hamstrack.migration.V15RoleBackfillMigrationTest}. Nothing in the database
 * ties the constant to the row: {@code roles.id} is seeded, not generated, and
 * {@code RolePermissionCache.byId} throws {@code IllegalStateException} for an id that
 * resolves to nothing — a 500 on <em>every authenticated request naming a workspace</em>,
 * which is the loudest failure in the epic and also the least useful one to debug at 3am.
 * This states the correspondence directly.
 *
 * <h2>Why the permission sets are asserted as exact key sets, not counts</h2>
 * {@code V15RoleBackfillMigrationTest} asserts {@code grants(P_MEMBER) == 12}. A count
 * cannot see a <em>swap</em>: drop {@code issue.rank} from Contributor and add
 * {@code project.edit}, and the count is still 12 while every workspace member in every
 * install silently loses backlog ranking and silently gains project settings. Contributor
 * is the row §7.2 calls load-bearing — its contents ARE the no-op promise — so its
 * contents are what this asserts, own-qualifiers included.
 *
 * <h2>The VIEWER half of the §8.4 no-op proof</h2>
 * V14 rewrites every pre-upgrade {@code project_members.role = 'VIEWER'} row to the
 * built-in Contributor, because a legacy VIEWER row granted <em>everything</em>
 * ({@code isAtLeast(VIEWER)} is true for all three legacy roles). The migration test proves
 * the rewrite lands on the id {@code …012}; {@link PermissionParityTest}'s
 * {@code P_VIEWER_MIGRATED} archetype proves a Contributor keeps every legacy verdict.
 * Neither knows that {@code …012} <em>is</em> Contributor — that link is what
 * {@link #theRoleAPreUpgradeViewerIsMigratedToGrantsExactlyTodaysAbilities()} closes.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class RoleIdsMatchMigrationTest {

    @Autowired RoleCatalog roleCatalog;
    @PersistenceContext EntityManager em;

    /**
     * §7.2's load-bearing set: everything a workspace member can do in a project TODAY.
     * Named so the failure message can point at it, and declared <strong>before</strong>
     * {@link #EXPECTED_GRANTS} — static initialisers run in textual order, and
     * {@code expected()} reads this (the same trap {@code Permission.KEY_FORMAT} documents).
     */
    private static final List<String> CONTRIBUTOR = List.of(
            "issue.create", "issue.edit", "issue.transition", "issue.assign", "issue.assignable",
            "issue.rank", "comment.create", "comment.edit:own", "comment.delete:own",
            "attachment.create", "attachment.delete:own", "sprint.assign");

    /** role id → the wire form of its seeded grants, in catalog order. §7.1 / §7.2, verbatim. */
    private static final Map<UUID, List<String>> EXPECTED_GRANTS = expected();

    private static Map<UUID, List<String>> expected() {
        var m = new LinkedHashMap<UUID, List<String>>();
        // §7.1 — Owner and Admin are IDENTICAL on purpose: `isAtLeast(ADMIN)` is the only
        // workspace role test in the codebase, so encoding a difference would be dishonest.
        // The ninth workspace permission, `project.administer.all`, is held by NOTHING.
        var workspaceAdmin = List.of(
                "workspace.edit", "workspace.member.manage", "workspace.role.manage",
                "workspace.taxonomy.manage", "project.create", "project.curate.all",
                "label.create", "label.manage");
        m.put(BuiltInRoles.WORKSPACE_OWNER, workspaceAdmin);
        m.put(BuiltInRoles.WORKSPACE_ADMIN, workspaceAdmin);
        m.put(BuiltInRoles.WORKSPACE_MEMBER, List.of(
                "project.create", "label.create", "label.manage:own"));
        m.put(BuiltInRoles.PROJECT_MANAGER, List.of(
                "project.edit", "project.archive", "project.member.manage",
                "project.taxonomy.manage", "issue.create", "issue.edit", "issue.transition",
                "issue.assign", "issue.assignable", "issue.rank", "issue.delete",
                "comment.create", "comment.edit:own", "comment.delete", "attachment.create",
                "attachment.delete", "sprint.manage", "sprint.assign", "version.manage",
                "component.manage"));
        m.put(BuiltInRoles.PROJECT_MEMBER, CONTRIBUTOR);
        m.put(BuiltInRoles.PROJECT_COMMENTER, List.of(
                "issue.assignable", "comment.create", "comment.edit:own", "comment.delete:own",
                "attachment.create", "attachment.delete:own"));
        m.put(BuiltInRoles.PROJECT_VIEWER, List.of());
        return Map.copyOf(m);
    }

    @Test
    @Transactional
    void everyBuiltInConstantNamesTheRowV13Seeded() {
        for (var scoped : BuiltInRoles.all().entrySet()) {
            for (var entry : scoped.getValue().entrySet()) {
                var view = roleCatalog.view(entry.getValue());
                assert view.key().equals(entry.getKey())
                        : "BuiltInRoles." + scoped.getKey() + "/" + entry.getKey() + " points at "
                          + entry.getValue() + ", whose key is '" + view.key() + "'. The constant "
                          + "and V13__roles.sql have drifted, so every membership assigned through "
                          + "RoleCatalog.reference(...) now carries the WRONG role.";
                assert view.scope() == scoped.getKey()
                        : entry.getKey() + " is seeded at scope " + view.scope() + ", not "
                          + scoped.getKey() + ". A workspace role reachable as a project role puts "
                          + "workspace.edit into a ProjectContext (§12).";
                assert view.builtIn()
                        : entry.getKey() + " is not built_in. The legacy bridges refuse a custom "
                          + "role, so every isAtLeast(...) call site S2/S3 has not converted would "
                          + "start throwing IllegalStateException.";
                assert view.workspaceId() == null
                        : entry.getKey() + " is owned by workspace " + view.workspaceId()
                          + ". A built-in template is shared by every install and every tenant; "
                          + "an owned one is invisible to all the others.";
            }
        }
    }

    @Test
    @Transactional
    void thereAreExactlySevenBuiltInRolesAndNothingElseIsGlobal() {
        var builtIns = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM roles WHERE built_in").getSingleResult()).longValue();
        assert builtIns == 7
                : "the install holds " + builtIns + " built-in roles, not the 7 of §7. A built-in "
                  + "is offered in every workspace of the instance and cannot be edited or deleted "
                  + "through the S4 editor, so seeding an eighth is a product decision.";

        var globalButCustom = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM roles WHERE workspace_id IS NULL AND NOT built_in")
                .getSingleResult()).longValue();
        assert globalButCustom == 0
                : "a role belongs to no workspace and is not built_in. roles_builtin_ck is meant "
                  + "to make that unrepresentable — such a row would be assignable in every "
                  + "workspace and editable through the S4 editor (§11.4).";
    }

    @Test
    @Transactional
    void everyBuiltInGrantsExactlyTheSetSectionSevenDeclares() {
        for (var expected : EXPECTED_GRANTS.entrySet()) {
            var view = roleCatalog.view(expected.getKey());
            assert view.permissions().asWireStrings().equals(expected.getValue())
                    : "the built-in '" + view.key() + "' (" + view.name() + ") grants\n    "
                      + view.permissions().asWireStrings() + "\nbut §7 declares\n    "
                      + expected.getValue() + "\nA COUNT would not have seen this: the "
                      + "V15 migration test asserts how MANY grants each built-in has, and a swap "
                      + "keeps the count. Every entry here is a decision — an added one widens "
                      + "what that role may do in every install, a removed one silently takes an "
                      + "ability away from everybody who holds it (§18).";
        }
    }

    /**
     * The link between the two halves of the §8.4 no-op proof. V15's migration test proves
     * a legacy {@code VIEWER} row is rewritten to the role id {@code …012};
     * {@code PermissionParityTest} proves a Contributor keeps every legacy verdict. Only
     * this says that {@code …012} is Contributor, and that Contributor is also the fallback
     * a member with no project row inherits — i.e. that the migrated Viewer and the
     * never-added member land on the same, correct set.
     */
    @Test
    @Transactional
    void theRoleAPreUpgradeViewerIsMigratedToGrantsExactlyTodaysAbilities() {
        var migrated = roleCatalog.view(BuiltInRoles.PROJECT_MEMBER);
        assert migrated.scope() == RoleScope.PROJECT && migrated.key().equals("MEMBER")
                : "V14 rewrites every pre-upgrade VIEWER row to " + BuiltInRoles.PROJECT_MEMBER
                  + ", which is supposed to be the project-scoped Contributor. It is now "
                  + migrated.scope() + "/" + migrated.key() + ".";
        assert migrated.permissions().asWireStrings().equals(CONTRIBUTOR)
                : "the role a pre-upgrade VIEWER is migrated to grants "
                  + migrated.permissions().asWireStrings() + ", not today's abilities. A legacy "
                  + "VIEWER row granted EVERYTHING (isAtLeast(VIEWER) is true for all three legacy "
                  + "roles), so anything narrower here takes abilities away from exactly the users "
                  + "an admin marked read-only — the precise inversion of this epic's goal (§8.4).";

        assert roleCatalog.defaultProjectRole().id().equals(BuiltInRoles.PROJECT_MEMBER)
                : "the fallback default project role is no longer Contributor. A member with no "
                  + "project_members row — nearly every real user (§2.3) — inherits it, so this is "
                  + "the single row that makes the upgrade a no-op for them.";
        assert roleCatalog.view(BuiltInRoles.PROJECT_VIEWER).permissions().isEmpty()
                : "the NEW Viewer must grant nothing — 'read-only' has to be expressible (§11.4), "
                  + "and no existing row is ever migrated onto it";
    }
}
