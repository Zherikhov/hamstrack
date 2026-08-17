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
 * {@code V12__delivery_capabilities.sql} — the upgrade policy of
 * {@code docs/design/delivery-paths-proposal.md} §7, replayed against a REAL pre-V12
 * database.
 *
 * <p>The rule under test is one sentence: <strong>an upgrade never takes away a
 * capability a project already has access to.</strong> Every project that existed before
 * this release could reach the Releases page and could type a story-point estimate, so
 * every one of them must come out of V12 with {@code releases_enabled} and
 * {@code estimation_enabled} ON — the exact opposite of the lean defaults the same
 * migration installs as the column defaults for NEW projects. Those two policies
 * deliberately disagree, and that disagreement is the thing most likely to be "tidied"
 * later by someone who notices the column default and the backfill contradict each other.
 *
 * <p>The second rule is narrower and easier to get wrong: the <strong>mid-adoption
 * rescue</strong>. A project that already owns ≥1 sprint row is demonstrably using
 * iterations whatever its {@code board_mode} says (V11 defaulted everything to KANBAN,
 * and a team could have created a sprint through the API or flipped back afterwards), so
 * it is moved to {@code SCRUM}; a project with no sprint keeps its stored mode
 * <em>exactly</em>. The correlated {@code EXISTS} is what makes that per-project rather
 * than per-workspace — the fixture therefore puts a sprintless project in the SAME
 * workspace as a sprint-owning one, because a mistakenly uncorrelated subquery would flip
 * both and no other assertion here would notice. On the dev database this moved 163 of
 * 786 projects; this test is that evidence made repeatable.
 *
 * <p>Third: the migration must change <strong>no</strong> {@code projects.updated_at}. Both
 * backfills are blanket UPDATEs over every row in the install, and {@code projects} carries
 * V1's {@code trg_projects_updated_at} trigger — without V11's
 * {@code SET LOCAL hamstrack.skip_updated_at = 'on'} guard the release would stamp every
 * project as "just edited", with no error anywhere. The guard is only effective inside a
 * transaction (Flyway supplies one), which is precisely why it can be broken later by an
 * unrelated change to Flyway's transaction settings.
 *
 * <p><strong>How it runs</strong> — the same shape as {@link V11StoryPointsMigrationTest}:
 * the suite's own database is already at head, so the only way to observe a migration is
 * to build a pre-V12 database from scratch in a THROWAWAY SCHEMA of the same database
 * ({@code v12_migration_test}). No migration in the repository is schema-qualified, so
 * Flyway can point a whole run at that schema; it is dropped again afterwards, so the
 * shared {@code public} schema the rest of the suite runs against is never touched and no
 * {@code CREATE DATABASE} privilege is needed.
 *
 * <p>The fixture rows are raw SQL on purpose — the legitimate exception to "set data up
 * through the service layer". The state being reproduced is that of a PRE-V12 install,
 * which no current service can produce: today's entity model cannot write a project row
 * without the two capability columns.
 */
class V12DeliveryCapabilitiesMigrationTest {

    /** Dropped and recreated on every run — never the schema the suite itself uses. */
    private static final String SCHEMA = "v12_migration_test";

    /**
     * A timestamp no test run can accidentally produce. Every fixture project is stamped
     * with it so "the migration did not touch updated_at" is an exact equality rather than
     * a fuzzy "recent enough".
     */
    private static final String FIXED_UPDATED_AT = "2020-01-02 03:04:05+00";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    @Test
    void theUpgradeKeepsEveryCapabilityAndRescuesOnlySprintOwningProjects() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                // ---- a database as it stood BEFORE the delivery-capabilities release ----
                flyway("11").migrate();

                var ids = seedPreV12Fixture(conn);

                // Sanity: the fixture really is pre-V12 (otherwise every assertion below
                // would be vacuously true against an already-migrated schema).
                assert !hasColumn(conn, "releases_enabled")
                        : "the fixture schema already has the V12 columns — nothing was migrated";

                // ---- the migration under test ----
                flyway("12").migrate();

                // === 1) §7: an upgrade never takes away a capability ===================
                // Not "most projects" — EVERY project, including the sprintless Kanban one
                // that a data-driven rule ("only projects that already have versions")
                // would have quietly stripped of a nav item on upgrade.
                for (var project : ids.all()) {
                    assert flag(conn, project, "releases_enabled")
                            : "project " + project + " lost the Releases page on upgrade";
                    assert flag(conn, project, "estimation_enabled")
                            : "project " + project + " lost the story-points input on upgrade";
                }
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".projects"
                        + " WHERE NOT releases_enabled OR NOT estimation_enabled") == 0
                        : "the backfill is not blanket — some pre-existing project came out lean";

                // === 2) §7: the mid-adoption rescue, per PROJECT ========================
                // Owning a sprint is the evidence; board_mode is not.
                assert boardMode(conn, ids.kanbanWithSprint()).equals("SCRUM")
                        : "a KANBAN project that already owns a sprint was left on a Backlog whose "
                          + "sprint sections are hidden — the production dead end V12 exists to fix";

                // …and nothing else moves. This is the assertion that catches an
                // uncorrelated EXISTS: kanbanNoSprint shares a workspace with
                // kanbanWithSprint, so a subquery that forgot `s.project_id = p.id` would
                // flip it too.
                assert boardMode(conn, ids.kanbanNoSprint()).equals("KANBAN")
                        : "a project with no sprint had its board_mode changed — the rescue is "
                          + "supposed to be evidence-driven, not a blanket flip";
                assert boardMode(conn, ids.scrumNoSprint()).equals("SCRUM")
                        : "an existing SCRUM project was demoted";
                assert boardMode(conn, ids.scrumWithSprint()).equals("SCRUM")
                        : "a SCRUM project that owns a sprint was disturbed";
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".projects"
                        + " WHERE board_mode = 'SCRUM'") == 3
                        : "exactly the two pre-existing SCRUM projects plus the one rescued Kanban "
                          + "project should be SCRUM";

                // === 3) §14.2: the migration changes no projects.updated_at ============
                // Two blanket UPDATEs over a table with an updated_at trigger; only
                // V11's `SET LOCAL hamstrack.skip_updated_at` keeps the whole install from
                // looking freshly edited on the morning after a release.
                for (var project : ids.all()) {
                    assert updatedAt(conn, project).equals(Timestamp.valueOf("2020-01-02 03:04:05"))
                            : "V12 stamped projects.updated_at on " + project + " — the "
                              + "hamstrack.skip_updated_at guard is not in effect (it only works "
                              + "inside a transaction; check Flyway's transaction settings)";
                }

                // === 4) the column defaults carry the NEW-project policy ===============
                // The other half of §7: the two policies are deliberately opposite, and the
                // DB default is the lean one. A future "tidy-up" that made the default TRUE
                // to match the backfill would silently un-lean every project created after
                // it — with the picker still showing the toggles off.
                var afterUpgrade = UUID.randomUUID();
                exec(conn, """
                        INSERT INTO %s.projects (id, workspace_id, name, key, created_by)
                        VALUES ('%s', '%s', 'Created after the upgrade', 'NEW', '%s')
                        """.formatted(SCHEMA, afterUpgrade, ids.workspace(), ids.user()));
                assert !flag(conn, afterUpgrade, "releases_enabled")
                        && !flag(conn, afterUpgrade, "estimation_enabled")
                        : "a project created AFTER the upgrade inherited the existing-project "
                          + "policy from the column default instead of the lean new-project one";

                // === 5) additive only — no data was destroyed on the way ===============
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".sprints") == 2
                        : "V12 touched the sprints it only reads";
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ the fixture

    /** The four projects the §7 rules have to tell apart, plus their tenancy scaffolding. */
    private record Fixture(UUID user, UUID workspace,
                           UUID kanbanNoSprint, UUID kanbanWithSprint,
                           UUID scrumNoSprint, UUID scrumWithSprint) {
        java.util.List<UUID> all() {
            return java.util.List.of(kanbanNoSprint, kanbanWithSprint, scrumNoSprint, scrumWithSprint);
        }
    }

    /**
     * A minimal but REAL pre-V12 install: one user, one workspace and the four projects
     * that span the §7 decision table — {board_mode} × {owns a sprint}. All four live in
     * the SAME workspace on purpose (see the class doc: it is what makes the correlated
     * EXISTS observable).
     */
    private Fixture seedPreV12Fixture(Connection conn) throws SQLException {
        exec(conn, "SET search_path TO " + SCHEMA);
        var user = UUID.randomUUID();
        var workspace = UUID.randomUUID();

        exec(conn, """
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'v12@example.test', 'V12 fixture', 'ACTIVE')
                """.formatted(user));
        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by)
                VALUES ('%s', 'v12-fixture', 'V12 fixture', '%s')
                """.formatted(workspace, user));

        var kanbanNoSprint = project(conn, workspace, user, "KAN", "KANBAN");
        var kanbanWithSprint = project(conn, workspace, user, "KANS", "KANBAN");
        var scrumNoSprint = project(conn, workspace, user, "SCR", "SCRUM");
        var scrumWithSprint = project(conn, workspace, user, "SCRS", "SCRUM");

        sprint(conn, workspace, kanbanWithSprint, user);
        sprint(conn, workspace, scrumWithSprint, user);

        return new Fixture(user, workspace, kanbanNoSprint, kanbanWithSprint, scrumNoSprint,
                scrumWithSprint);
    }

    /** A project stamped with {@link #FIXED_UPDATED_AT} so §14.2's guard is checkable. */
    private UUID project(Connection conn, UUID workspace, UUID user, String key, String boardMode)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, board_mode, created_by, updated_at)
                VALUES ('%s', '%s', 'Project %s', '%s', '%s', '%s', TIMESTAMPTZ '%s')
                """.formatted(id, workspace, key, key, boardMode, user, FIXED_UPDATED_AT));
        return id;
    }

    /**
     * A FUTURE sprint — the cheapest row that satisfies V12's {@code EXISTS}. Its state is
     * irrelevant to the rescue on purpose: the rule is "has ever planned an iteration",
     * not "is running one".
     */
    private void sprint(Connection conn, UUID workspace, UUID project, UUID user) throws SQLException {
        exec(conn, """
                INSERT INTO sprints (id, workspace_id, project_id, name, state, sequence, created_by)
                VALUES ('%s', '%s', '%s', 'Sprint 1', 'FUTURE', 1, '%s')
                """.formatted(UUID.randomUUID(), workspace, project, user));
    }

    // ================================================================ assertions

    private boolean flag(Connection conn, UUID project, String column) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT " + column + " FROM " + SCHEMA + ".projects"
                     + " WHERE id = '" + project + "'")) {
            assert rs.next() : "project row vanished: " + project;
            return rs.getBoolean(1);
        }
    }

    private String boardMode(Connection conn, UUID project) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT board_mode FROM " + SCHEMA + ".projects"
                     + " WHERE id = '" + project + "'")) {
            assert rs.next() : "project row vanished: " + project;
            return rs.getString(1);
        }
    }

    private Timestamp updatedAt(Connection conn, UUID project) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT updated_at AT TIME ZONE 'UTC' FROM " + SCHEMA
                     + ".projects WHERE id = '" + project + "'")) {
            assert rs.next() : "project row vanished: " + project;
            return rs.getTimestamp(1);
        }
    }

    private boolean hasColumn(Connection conn, String column) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'projects'"
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

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
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
