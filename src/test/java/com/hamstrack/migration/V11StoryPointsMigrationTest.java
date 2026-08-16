package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * {@code V11__sprints.sql} §3.4 — the story-point promotion, replayed against a REAL
 * pre-V11 database.
 *
 * <p>Why this test exists at all: V11 is the first migration in the codebase that
 * <strong>deletes</strong> data. Everything before it (V8/V9/V10) archived a placeholder
 * field that held nothing, so a mistake there was invisible; here the backfill copies
 * {@code issue_field_values.value} into the native {@code issues.story_points} column and
 * then drops the rows it copied. The two statements carry <em>identical</em> predicates,
 * and that identity is the whole safety argument:
 *
 * <ul>
 *   <li>a value the backfill migrated ({@code jsonb_typeof(value) = 'number'}) is deleted
 *       — otherwise the same estimate would render twice on one issue, once natively and
 *       once as a legacy custom-field value;</li>
 *   <li>a value it <em>skipped</em> (a string, a null, an object left by an older client)
 *       is left exactly where it is, and its issue's {@code story_points} stays NULL —
 *       nothing is deleted that was not first copied;</li>
 *   <li>only the GLOBAL system field is the source: a workspace-scoped field that happens
 *       to reuse the {@code story_points} key is a DIFFERENT field and must not be
 *       swallowed.</li>
 * </ul>
 *
 * <p>The other two rewrites in the script are asserted in passing: the position rescale
 * (×{@code RANK_STEP}) and the archival of the {@code story_points} / {@code sprint}
 * placeholders.
 *
 * <p><strong>How it runs.</strong> The suite's own database is already migrated to head,
 * so the only way to observe a migration is to build a pre-V11 database from scratch.
 * That happens in a THROWAWAY SCHEMA of the same database ({@code v11_migration_test}),
 * not a throwaway database: Flyway points its whole run at that schema, no migration in
 * the repository is schema-qualified, and the schema is dropped again afterwards — so
 * this never touches the shared {@code public} schema the rest of the suite runs against,
 * and it needs no {@code CREATE DATABASE} privilege.
 *
 * <p>The fixture rows are raw SQL on purpose (the one legitimate exception to
 * "set data up through the service layer"): the whole point is to reproduce the state a
 * PRE-V11 installation is in, which no current service can produce — the entity model no
 * longer has a way to write a {@code story_points} custom-field value.
 */
class V11StoryPointsMigrationTest {

    /** Dropped and recreated on every run — never the schema the suite itself uses. */
    private static final String SCHEMA = "v11_migration_test";

    /** {@code IssueRankService.RANK_STEP} — V11's position rescale factor. */
    private static final long RANK_STEP = 67_108_864L;

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    @Test
    void theBackfillPromotesNumericValuesAndDeletesExactlyTheRowsItCopied() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                // ---- a database as it stood BEFORE the sprints release ----
                flyway("10").migrate();

                var ids = seedPreV11Fixture(conn);

                // ---- the migration under test ----
                flyway("11").migrate();

                // === 1) a numeric value is promoted AND its legacy row is gone ==========
                assertPoints(conn, ids.numeric(), new BigDecimal("5.00"));
                assert legacyRows(conn, ids.numeric()) == 0
                        : "the migrated story-point value was left behind — the same estimate now "
                          + "renders twice on that issue (natively and as a legacy field value)";

                // …including the values only the clamp/round make representable:
                // LEAST/GREATEST clamp to the column's 0…999 domain, round() to its scale.
                assertPoints(conn, ids.oversized(), new BigDecimal("999.00"));
                assert legacyRows(conn, ids.oversized()) == 0 : "a clamped value kept its legacy row";
                assertPoints(conn, ids.overPrecise(), new BigDecimal("1.24"));
                assert legacyRows(conn, ids.overPrecise()) == 0 : "a rounded value kept its legacy row";

                // === 2) a value the backfill SKIPPED survives untouched ================
                // jsonb_typeof(value) <> 'number' — a string estimate left by an older
                // client. It could not be copied, so it must not be deleted, and the
                // native column must stay NULL rather than guess.
                assert pointsOf(conn, ids.stringValued()) == null
                        : "a non-numeric legacy value was coerced into the native column";
                assert legacyRows(conn, ids.stringValued()) == 1
                        : "a value the backfill could NOT migrate was deleted anyway — that row is "
                          + "the only remaining copy of the data";
                assert pointsOf(conn, ids.nullValued()) == null : "a JSON null became a number";
                assert legacyRows(conn, ids.nullValued()) == 1 : "a JSON-null value row was deleted";

                // === 3) only the GLOBAL system field is the source =====================
                // A workspace-scoped field that reuses the key is a different field: its
                // values are neither promoted nor deleted.
                assert pointsOf(conn, ids.workspaceScoped()) == null
                        : "a workspace-scoped field that merely reuses the `story_points` key "
                          + "was swallowed into the native column";
                assert legacyRows(conn, ids.workspaceScoped()) == 1
                        : "a workspace-scoped field's value was deleted by the global backfill";

                // === 4) the placeholders are ARCHIVED, never deleted ===================
                assert archivedAt(conn, "story_points") != null
                        : "the global story_points placeholder must be archived, not left live";
                assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".field_defs"
                        + " WHERE key = 'story_points'") == 2
                        : "a field def was DELETED — V11 archives placeholders, it never drops them";
                assert archivedAt(conn, "sprint") != null
                        : "the never-usable `sprint` SELECT placeholder must be archived too";

                // === 5) the rank rescale ==============================================
                // Positions were 1..N with zero room between neighbours; V11 multiplies
                // them by RANK_STEP so every pair has ~26 midpoints of headroom.
                assert positionOf(conn, ids.numeric()) == RANK_STEP
                        : "V11 did not rescale issues.position by RANK_STEP";
                assert positionOf(conn, ids.stringValued()) == 4 * RANK_STEP
                        : "the position rescale did not preserve the order";
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ the fixture

    /** The issues the assertions are written against, by the shape of their legacy value. */
    private record Fixture(UUID numeric, UUID oversized, UUID overPrecise, UUID stringValued,
                           UUID nullValued, UUID workspaceScoped) {}

    /**
     * A minimal but REAL pre-V11 install: one user, one workspace, one project and six
     * issues, each carrying one {@code story_points} custom-field value of a different
     * shape. Positions are 1..6, exactly as the pre-V11 sequence seeding left them.
     */
    private Fixture seedPreV11Fixture(Connection conn) throws SQLException {
        exec(conn, "SET search_path TO " + SCHEMA);
        var user = UUID.randomUUID();
        var workspace = UUID.randomUUID();
        var project = UUID.randomUUID();

        exec(conn, """
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'v11@example.test', 'V11 fixture', 'ACTIVE')
                """.formatted(user));
        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by)
                VALUES ('%s', 'v11-fixture', 'V11 fixture', '%s')
                """.formatted(workspace, user));
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by)
                VALUES ('%s', '%s', 'V11 fixture', 'V11', '%s')
                """.formatted(project, workspace, user));

        // The GLOBAL system field V1 seeded — the backfill's only source…
        var globalField = single(conn, """
                SELECT id FROM field_defs
                 WHERE key = 'story_points' AND scope_workspace_id IS NULL AND scope_project_id IS NULL
                """);
        // …and a workspace-scoped namesake, which must be left completely alone.
        var scopedField = UUID.randomUUID();
        exec(conn, """
                INSERT INTO field_defs (id, scope_workspace_id, key, name, type)
                VALUES ('%s', '%s', 'story_points', 'Team points', 'NUMBER')
                """.formatted(scopedField, workspace));

        var numeric = issue(conn, workspace, project, user, 1, "a plain numeric estimate");
        var oversized = issue(conn, workspace, project, user, 2, "beyond the column domain");
        var overPrecise = issue(conn, workspace, project, user, 3, "more decimals than the column");
        var stringValued = issue(conn, workspace, project, user, 4, "a string estimate");
        var nullValued = issue(conn, workspace, project, user, 5, "an explicit JSON null");
        var workspaceScoped = issue(conn, workspace, project, user, 6, "a different field entirely");

        value(conn, numeric, globalField, "5");
        value(conn, oversized, globalField, "1500");     // clamped to 999 by LEAST/GREATEST
        value(conn, overPrecise, globalField, "1.239");  // rounded to 1.24 by round(…, 2)
        value(conn, stringValued, globalField, "\"8\""); // jsonb_typeof = 'string' → skipped
        value(conn, nullValued, globalField, "null");    // jsonb_typeof = 'null'   → skipped
        value(conn, workspaceScoped, scopedField, "13"); // a different field       → skipped

        return new Fixture(numeric, oversized, overPrecise, stringValued, nullValued,
                workspaceScoped);
    }

    private UUID issue(Connection conn, UUID workspace, UUID project, UUID user, int number,
                       String title) throws SQLException {
        var id = UUID.randomUUID();
        // type_id/status_id deliberately carry no FK (V1); priority_id does, so it is read
        // from the globally seeded set.
        var priority = single(conn,
                "SELECT id FROM priorities WHERE scope_workspace_id IS NULL LIMIT 1");
        var status = single(conn,
                "SELECT id FROM statuses WHERE scope_workspace_id IS NULL LIMIT 1");
        var type = single(conn,
                "SELECT id FROM issue_types WHERE scope_workspace_id IS NULL LIMIT 1");
        exec(conn, """
                INSERT INTO issues (id, workspace_id, project_id, number, title, type_id, status_id,
                                    reporter_id, priority_id, position)
                VALUES ('%s', '%s', '%s', %d, '%s', '%s', '%s', '%s', '%s', %d)
                """.formatted(id, workspace, project, number, title, type, status, user, priority,
                number));
        return id;
    }

    private void value(Connection conn, UUID issue, UUID field, String json) throws SQLException {
        exec(conn, """
                INSERT INTO issue_field_values (id, issue_id, field_id, value)
                VALUES ('%s', '%s', '%s', '%s'::jsonb)
                """.formatted(UUID.randomUUID(), issue, field, json));
    }

    // ================================================================ assertions

    private void assertPoints(Connection conn, UUID issue, BigDecimal expected) throws SQLException {
        var actual = pointsOf(conn, issue);
        assert actual != null && actual.compareTo(expected) == 0
                : "story_points for issue " + issue + " is " + actual + ", expected " + expected;
    }

    private BigDecimal pointsOf(Connection conn, UUID issue) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT story_points FROM " + SCHEMA + ".issues WHERE id = '" + issue + "'")) {
            assert rs.next() : "issue row vanished: " + issue;
            return rs.getBigDecimal(1);
        }
    }

    private long positionOf(Connection conn, UUID issue) throws SQLException {
        return rowsOf(conn,
                "SELECT position FROM " + SCHEMA + ".issues WHERE id = '" + issue + "'");
    }

    /** How many legacy {@code issue_field_values} rows this issue still has. */
    private long legacyRows(Connection conn, UUID issue) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".issue_field_values"
                + " WHERE issue_id = '" + issue + "'");
    }

    private Object archivedAt(Connection conn, String key) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT archived_at FROM " + SCHEMA + ".field_defs"
                     + " WHERE key = '" + key + "'"
                     + " AND scope_workspace_id IS NULL AND scope_project_id IS NULL")) {
            assert rs.next() : "the " + key + " placeholder field def is gone entirely";
            return rs.getObject(1);
        }
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

    private static UUID single(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assert rs.next() : "expected a row from: " + sql;
            return (UUID) rs.getObject(1);
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
