package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * {@code V13__roles.sql} + {@code V14__role_assignments.sql} +
 * {@code V15__drop_legacy_role_columns.sql} replayed against a REAL pre-V13 database —
 * <strong>the other half of the §8.4 no-op proof</strong>.
 *
 * <p>{@code PermissionParityTest} asserts that the new predicates agree with the old ones
 * <em>given a correctly migrated role</em>. It cannot assert the migration itself, because
 * V15 drops the legacy {@code role} columns, so after the upgrade there is no second
 * source of truth left to compare against. This test is that comparison, run at the one
 * moment it is still possible: with a pre-upgrade database in hand.
 *
 * <p>The rules under test, in the order they can break:
 * <ol>
 *   <li><strong>Every membership and invite keeps its role.</strong> {@code OWNER} maps to
 *       the built-in Owner, {@code ADMIN} to Admin, {@code MEMBER} to Member,
 *       {@code MANAGER} to Project admin. A row that lands on the wrong template silently
 *       promotes or demotes a real person.</li>
 *   <li><strong>{@code VIEWER} becomes MEMBER, not Viewer.</strong> The one line in the
 *       migration that changes a value rather than translating it, and the easiest to
 *       "correct" later by someone who sees VIEWER on both sides and assumes a typo.
 *       Today a VIEWER row grants everything ({@code isAtLeast(VIEWER)} is true for all
 *       three roles); the new Viewer grants nothing. Mapping like-for-like would take
 *       every ability away from exactly the users who were marked read-only.</li>
 *   <li><strong>No {@code project_members} rows are invented.</strong> §8.4 refuses to
 *       backfill a row per (member × project): it would turn an implicit rule into N*M
 *       rows and destroy the distinction between "explicitly added" and "inherited".</li>
 *   <li><strong>Every workspace comes out OPEN with a NULL default role</strong> — which
 *       is precisely "any workspace member may work in any project", i.e. today.</li>
 *   <li><strong>V15's pre-flight guard actually guards.</strong> A NULL {@code role_id}
 *       must abort the migration <em>before</em> anything is dropped, with a message that
 *       names the table — not with PostgreSQL's bare NOT NULL violation.</li>
 *   <li><strong>The upgrade stamps no {@code updated_at}.</strong> V14 runs blanket
 *       UPDATEs over {@code workspaces} and {@code projects}, both of which carry V1's
 *       trigger; only V11's {@code SET LOCAL hamstrack.skip_updated_at} keeps the whole
 *       install from looking freshly edited the morning after a release.</li>
 *   <li><strong>The seven built-in templates carry the §7 permission sets</strong>, and in
 *       particular Contributor carries all twelve grants — the load-bearing row, the one
 *       whose contents ARE the no-op promise.</li>
 * </ol>
 *
 * <p><strong>How it runs</strong> — same shape as {@link V12DeliveryCapabilitiesMigrationTest}:
 * the suite's own database is already at head, so a migration can only be observed by
 * building a pre-V13 database from scratch in a THROWAWAY SCHEMA of the same database. No
 * migration is schema-qualified, so Flyway can point a whole run at it; it is dropped
 * afterwards, so the shared {@code public} schema is never touched and no
 * {@code CREATE DATABASE} privilege is needed.
 *
 * <p>The fixture rows are raw SQL on purpose — the legitimate exception to "set data up
 * through the service layer". The state being reproduced is that of a PRE-V13 install,
 * which no current service can produce: today's entity model cannot write a membership row
 * without a {@code role_id}, and cannot write one WITH a legacy {@code role} string.
 */
class V15RoleBackfillMigrationTest {

    private static final String SCHEMA = "v15_migration_test";

    /** A timestamp no test run can accidentally produce, so rule 6 is an exact equality. */
    private static final String FIXED_UPDATED_AT = "2020-01-02 03:04:05+00";

    // The seven built-in ids, duplicated from BuiltInRoles/V13 ON PURPOSE. If a future
    // change edits one of the three copies, this test is what notices.
    private static final String WS_OWNER = "00000000-0000-7000-8000-000000000001";
    private static final String WS_ADMIN = "00000000-0000-7000-8000-000000000002";
    private static final String WS_MEMBER = "00000000-0000-7000-8000-000000000003";
    private static final String P_MANAGER = "00000000-0000-7000-8000-000000000011";
    private static final String P_MEMBER = "00000000-0000-7000-8000-000000000012";
    private static final String P_VIEWER = "00000000-0000-7000-8000-000000000014";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    @Test
    void theUpgradeTranslatesEveryRoleAndInventsNothing() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                // ---- a database as it stood BEFORE the roles release ----
                flyway("12").migrate();
                var f = seedPreV13Fixture(conn);

                assert hasColumn(conn, "workspace_members", "role")
                        : "the fixture schema has no legacy role column — nothing to migrate";
                assert !hasColumn(conn, "workspace_members", "role_id")
                        : "the fixture schema is already migrated";

                // ---- the migrations under test ----
                flyway("15").migrate();

                // === 7) the built-in templates exist with the §7 sets =================
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".roles WHERE built_in") == 7
                        : "expected exactly the 7 built-in role templates of §7";
                assert grants(conn, WS_OWNER) == 8 && grants(conn, WS_ADMIN) == 8
                        : "Owner and Admin must hold the same EIGHT of the nine workspace "
                          + "permissions — `isAtLeast(ADMIN)` is the only workspace role test in "
                          + "the codebase, so encoding a difference between them would be "
                          + "dishonest (§7.1)";
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".role_permissions"
                        + " WHERE permission = 'project.administer.all'") == 0
                        : "a built-in role was seeded with project.administer.all. The ninth "
                          + "workspace permission is deliberately held by NOTHING: today's "
                          + "workspace-admin bypass is requireProjectCurator only, so Owner/Admin "
                          + "get `project.curate.all` (project.edit + component/version/sprint."
                          + "manage). Seeding the wide one hands every Owner in every install "
                          + "project.archive, project.member.manage, project.taxonomy.manage, "
                          + "issue.delete and attachment moderation on upgrade (§7.1, §17.2).";
                assert grants(conn, WS_MEMBER) == 3
                        : "the built-in workspace Member holds exactly {project.create, "
                          + "label.create, label.manage:own}";
                assert ownOnly(conn, WS_MEMBER, "label.manage")
                        : "Member's label.manage must be OWN-ONLY. Unrestricted would let any "
                          + "member rename anyone's label; dropping the row entirely would take "
                          + "away the right they have TODAY to rename a label they created "
                          + "(LabelService.requireEditor short-circuits for the creator).";
                assert grants(conn, P_MANAGER) == 20
                        : "Project admin must hold all 20 project permissions";
                assert grants(conn, P_MEMBER) == 12
                        : "Contributor is the load-bearing row (§7.2): its 12 grants ARE everything "
                          + "a workspace member can do in a project today, and they are what makes "
                          + "the upgrade a no-op. A grant removed here silently removes an ability "
                          + "from every user in every install.";
                assert grants(conn, P_VIEWER) == 0
                        : "Viewer grants nothing — 'read-only' must be expressible (§11.4)";
                assert ownOnly(conn, P_MANAGER, "comment.edit")
                        : "even Project admin edits only their OWN comments (§17.3)";
                assert !ownOnly(conn, P_MANAGER, "comment.delete")
                        : "Project admin moderates comments — that is the intended widening (§10.3.5)";

                // === 1) every membership and invite kept its role =====================
                assert memberRole(conn, f.owner()).equals(WS_OWNER) : "an OWNER was not mapped to Owner";
                assert memberRole(conn, f.admin()).equals(WS_ADMIN) : "an ADMIN was not mapped to Admin";
                assert memberRole(conn, f.plain()).equals(WS_MEMBER) : "a MEMBER was not mapped to Member";
                assert inviteRole(conn, f.inviteAdmin()).equals(WS_ADMIN)
                        : "a pending ADMIN invite would grant the wrong role on acceptance";
                assert inviteRole(conn, f.inviteMember()).equals(WS_MEMBER);
                assert projectRole(conn, f.pManager()).equals(P_MANAGER)
                        : "a project MANAGER was not mapped to Project admin";
                assert projectRole(conn, f.pMember()).equals(P_MEMBER);

                // === 2) VIEWER -> MEMBER, not Viewer =================================
                assert projectRole(conn, f.pViewer()).equals(P_MEMBER)
                        : "a pre-upgrade VIEWER row was mapped to the new (empty) Viewer role. Today "
                          + "a VIEWER row grants EVERYTHING (§2.2 — ProjectRole.isAtLeast(VIEWER) is "
                          + "true for every role, so no gate anywhere distinguishes it from MEMBER). "
                          + "Mapping it like-for-like takes every ability away from exactly the users "
                          + "who were marked read-only — the precise inversion of this epic's goal.";
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".project_members"
                        + " WHERE role_id = '" + P_VIEWER + "'") == 0
                        : "no existing row may land on the new Viewer role";

                // === 3) nothing invented ============================================
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".project_members") == 3
                        : "the migration inserted project_members rows. §8.4 forbids it: a row per "
                          + "(workspace member x project) would turn an implicit rule into N*M rows "
                          + "and destroy the 'explicitly added' vs 'inherits the default' distinction "
                          + "that private projects will need.";
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".workspace_members") == 3;

                // === 4) every workspace is OPEN with a NULL default ==================
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".workspaces"
                        + " WHERE project_access_mode <> 'OPEN' OR default_project_role_id IS NOT NULL") == 0
                        : "an existing workspace did not come out OPEN with the Contributor default — "
                          + "that combination IS today's behaviour, and anything else tightens on "
                          + "upgrade";
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".projects"
                        + " WHERE default_project_role_id IS NOT NULL") == 0;

                // === legacy storage is gone =========================================
                for (var table : new String[]{"workspace_members", "project_members", "workspace_invites"}) {
                    assert !hasColumn(conn, table, "role")
                            : table + " still has the legacy role column after V15";
                    assert rowsOf(conn, "SELECT count(*) FROM information_schema.columns"
                            + " WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table + "'"
                            + " AND column_name = 'role_id' AND is_nullable = 'NO'") == 1
                            : table + ".role_id is not NOT NULL — V15's second step did not run";
                }

                // === 6) no updated_at was stamped ===================================
                assert updatedAt(conn, "workspaces", f.workspace())
                        .equals(Timestamp.valueOf("2020-01-02 03:04:05"))
                        : "V14 stamped workspaces.updated_at — the hamstrack.skip_updated_at guard is "
                          + "not in effect (it only works inside a transaction; check Flyway's "
                          + "transaction settings)";
                assert updatedAt(conn, "projects", f.project())
                        .equals(Timestamp.valueOf("2020-01-02 03:04:05"))
                        : "V14 stamped projects.updated_at — see above";
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * Rule 5: V15 must refuse to run — and drop nothing — if V14 left a NULL. Built by
     * migrating to 14, then re-introducing the state V14 cannot produce but a botched
     * repair could.
     */
    @Test
    void v15RefusesToDropAnythingWhileARoleIsUnassigned() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("12").migrate();
                seedPreV13Fixture(conn);
                flyway("14").migrate();

                exec(conn, "UPDATE " + SCHEMA + ".workspace_members SET role_id = NULL"
                        + " WHERE role_id = '" + WS_MEMBER + "'");

                boolean aborted = false;
                try {
                    flyway("15").migrate();
                } catch (RuntimeException e) {
                    aborted = true;
                    assert rootMessage(e).contains("V14")
                            : "V15 failed, but not with the pre-flight message that tells an operator "
                              + "which migration to look at. Got: " + rootMessage(e);
                }
                assert aborted : "V15 dropped the legacy columns while a role_id was still NULL. The "
                        + "pre-flight DO block is what makes a half-migrated database impossible.";
                assert hasColumn(conn, "workspace_members", "role")
                        : "V15 aborted but had already dropped a column — the guard must run FIRST";
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ the fixture

    private record Fixture(UUID workspace, UUID project,
                           UUID owner, UUID admin, UUID plain,
                           UUID pManager, UUID pMember, UUID pViewer,
                           UUID inviteAdmin, UUID inviteMember) {}

    /** One workspace, one project, one member per legacy role, and two pending invites. */
    private Fixture seedPreV13Fixture(Connection conn) throws SQLException {
        exec(conn, "SET search_path TO " + SCHEMA);
        var workspace = UUID.randomUUID();
        var project = UUID.randomUUID();
        var owner = user(conn, "owner");
        var admin = user(conn, "admin");
        var plain = user(conn, "plain");

        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by, updated_at)
                VALUES ('%s', 'v15-fixture', 'V15 fixture', '%s', TIMESTAMPTZ '%s')
                """.formatted(workspace, owner, FIXED_UPDATED_AT));
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by, updated_at)
                VALUES ('%s', '%s', 'Fixture', 'FIX', '%s', TIMESTAMPTZ '%s')
                """.formatted(project, workspace, owner, FIXED_UPDATED_AT));

        var mOwner = wsMember(conn, workspace, owner, "OWNER");
        var mAdmin = wsMember(conn, workspace, admin, "ADMIN");
        var mPlain = wsMember(conn, workspace, plain, "MEMBER");

        // The three legacy project roles, one each — VIEWER is the interesting one.
        var pManager = projectMember(conn, project, owner, "MANAGER");
        var pMember = projectMember(conn, project, admin, "MEMBER");
        var pViewer = projectMember(conn, project, plain, "VIEWER");

        var inviteAdmin = invite(conn, workspace, owner, "ADMIN", "invited-admin@example.test");
        var inviteMember = invite(conn, workspace, owner, "MEMBER", "invited-member@example.test");

        return new Fixture(workspace, project, mOwner, mAdmin, mPlain,
                pManager, pMember, pViewer, inviteAdmin, inviteMember);
    }

    private UUID user(Connection conn, String name) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'v15-%s-%s@example.test', 'V15 %s', 'ACTIVE')
                """.formatted(id, name, id.toString().substring(0, 8), name));
        return id;
    }

    private UUID wsMember(Connection conn, UUID workspace, UUID user, String role) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO workspace_members (id, workspace_id, user_id, role)
                VALUES ('%s', '%s', '%s', '%s')
                """.formatted(id, workspace, user, role));
        return id;
    }

    private UUID projectMember(Connection conn, UUID project, UUID user, String role) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO project_members (id, project_id, user_id, role)
                VALUES ('%s', '%s', '%s', '%s')
                """.formatted(id, project, user, role));
        return id;
    }

    private UUID invite(Connection conn, UUID workspace, UUID by, String role, String email)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO workspace_invites (id, workspace_id, email, role, token_hash, invited_by, expires_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', NOW() + INTERVAL '7 days')
                """.formatted(id, workspace, email, role, id.toString().replace("-", ""), by));
        return id;
    }

    // ================================================================ assertions

    private String memberRole(Connection conn, UUID id) throws SQLException {
        return scalar(conn, "SELECT role_id FROM " + SCHEMA + ".workspace_members WHERE id = '" + id + "'");
    }

    private String projectRole(Connection conn, UUID id) throws SQLException {
        return scalar(conn, "SELECT role_id FROM " + SCHEMA + ".project_members WHERE id = '" + id + "'");
    }

    private String inviteRole(Connection conn, UUID id) throws SQLException {
        return scalar(conn, "SELECT role_id FROM " + SCHEMA + ".workspace_invites WHERE id = '" + id + "'");
    }

    private long grants(Connection conn, String roleId) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".role_permissions"
                + " WHERE role_id = '" + roleId + "'");
    }

    private boolean ownOnly(Connection conn, String roleId, String permission) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".role_permissions"
                + " WHERE role_id = '" + roleId + "' AND permission = '" + permission + "'"
                + " AND own_only") == 1;
    }

    private Timestamp updatedAt(Connection conn, String table, UUID id) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT updated_at AT TIME ZONE 'UTC' FROM " + SCHEMA + "."
                     + table + " WHERE id = '" + id + "'")) {
            assert rs.next() : "row vanished: " + table + " " + id;
            return rs.getTimestamp(1);
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table + "'"
                + " AND column_name = '" + column + "'") > 0;
    }

    // ================================================================ plumbing

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private void dropSchema(Connection conn) throws SQLException {
        exec(conn, "SET search_path TO public");
        exec(conn, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private static String rootMessage(Throwable t) {
        var sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assert rs.next() : "expected a row from: " + sql;
            return rs.getString(1);
        }
    }

    private static long rowsOf(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assert rs.next() : "expected a row from: " + sql;
            return rs.getLong(1);
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
