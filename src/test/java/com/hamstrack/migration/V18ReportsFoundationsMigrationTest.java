package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V18__reports_foundations.sql} — the {@code issues.started_at} backfill of
 * reports-proposal §5.1, replayed against a REAL pre-V18 database.
 *
 * <p>The backfill is <strong>approximate by construction</strong>, and that is the whole
 * reason it needs a test: it joins {@code issue_history} to {@code statuses} by DISPLAY
 * NAME, because a display name is all history stores. So the interesting assertions are
 * not "it filled the column in" — they are the four places it must deliberately give up
 * and leave {@code NULL}:
 *
 * <ul>
 *   <li>an issue whose status was later RENAMED (its history names a status that no
 *       longer exists) — the documented blind spot;</li>
 *   <li>an issue with no status history at all (filed and never moved);</li>
 *   <li>a NON-status history row that happens to carry the words "In Progress" (an
 *       assignee change, a comment field) — the {@code field = 'status'} predicate;</li>
 *   <li>a status name that only exists in ANOTHER tenant's workspace — the scope
 *       predicates, i.e. this project's top bug class in the one place a migration can
 *       commit it;</li>
 *   <li>a status name that only exists as another PROJECT's private status. This one is
 *       adversarial in a second direction: {@code statuses_scope_ck} means a
 *       project-scoped status has {@code scope_workspace_id IS NULL}, so it sails through
 *       the workspace half of the predicate and ONLY the project half refuses it. Without
 *       this fixture the project predicate could be deleted and the whole suite would
 *       stay green while every other project's vocabulary got a vote on whether your
 *       issue had started.</li>
 * </ul>
 *
 * <p>Every one of those must stay NULL and be counted later as {@code missingStartCount}.
 * <strong>None of them may fall back to {@code created_at}</strong>: filing time is not
 * start time, and substituting it turns a cycle-time report into a lead-time report
 * wearing a false name (§2.2). A future "improvement" that made the column non-null
 * everywhere would pass no assertion here and break the report's honesty — which is why
 * the NULL cases outnumber the filled ones below.
 *
 * <p>Two further properties are asserted because both are silent when broken:
 * <strong>idempotency</strong> (the statement is re-run, from the migration file itself,
 * against a value the application has since written — it must not be overwritten) and the
 * {@code hamstrack.skip_updated_at} guard (a blanket UPDATE on {@code issues} without it
 * stamps every touched issue as "just edited", poisoning Home / My work / ORDER BY
 * updated, with no error anywhere).
 *
 * <p><strong>How it runs</strong> — the {@link V12DeliveryCapabilitiesMigrationTest}
 * shape: the suite's own database is already at head, so a pre-V18 database is built from
 * scratch in a THROWAWAY SCHEMA ({@code v18_migration_test}) and dropped again, leaving
 * the shared {@code public} schema untouched and needing no CREATE DATABASE privilege.
 * Fixture rows are raw SQL on purpose — today's entity model cannot write an issue row
 * without {@code started_at}.
 */
class V18ReportsFoundationsMigrationTest {

    private static final String SCHEMA = "v18_migration_test";

    /** A timestamp no test run can accidentally produce (the V12 technique). */
    private static final String FIXED_UPDATED_AT = "2020-01-02 03:04:05+00";

    private static final String STARTED = "2026-02-03 10:00:00+00";
    private static final String FINISHED = "2026-02-05 16:00:00+00";
    private static final String STRAIGHT_TO_DONE = "2026-02-06 08:30:00+00";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    @Test
    void theBackfillIsHonestAboutWhatItCannotKnow() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                // ---- a database as it stood BEFORE the reports foundations ----
                flyway("17").migrate();
                var ids = seedPreV18Fixture(conn);

                assertThat(hasColumn(conn, "issues", "started_at"))
                        .withFailMessage("the fixture schema already has started_at — nothing was migrated")
                        .isFalse();

                // ---- the migration under test ----
                flyway("18").migrate();

                // === 1) what it CAN know ==============================================
                assertThat(startedAt(conn, ids.workedThenDone()))
                        .as("the FIRST move into an in-progress status is the start, not the last")
                        .isEqualTo(Timestamp.valueOf("2026-02-03 10:00:00"));
                assertThat(startedAt(conn, ids.straightToDone()))
                        .as("an issue dragged straight to Done was started then finished — DONE "
                          + "counts as a start, or the fastest work is silently excluded")
                        .isEqualTo(Timestamp.valueOf("2026-02-06 08:30:00"));
                assertThat(startedAt(conn, ids.doneThenReopened()))
                        .as("a re-open must not move the start date backwards or forwards")
                        .isEqualTo(Timestamp.valueOf("2026-02-03 10:00:00"));

                // === 2) what it must ADMIT it cannot know =============================
                for (var unknowable : ids.unknowable()) {
                    assertThat(startedAt(conn, unknowable))
                            .as(() -> """
                            Issue %s came out of the backfill with a start date it cannot \
                            possibly know. The backfill is name-keyed and therefore \
                            approximate; every issue it cannot resolve keeps NULL and is \
                            reported as missingStartCount. Falling back to created_at (or \
                            to closed_at) turns cycle time into something else wearing its \
                            name.""".formatted(unknowable))
                            .isNull();
                }

                // === 3) it is a backfill, not an edit ================================
                for (var issue : ids.all()) {
                    assertThat(updatedAt(conn, issue))
                            .as(() -> """
                            V18 stamped issues.updated_at on %s — the \
                            hamstrack.skip_updated_at guard is not in effect. It only works \
                            inside a transaction; check Flyway's transaction settings. \
                            Without it a release marks every touched issue as freshly \
                            edited and poisons Home / My work / ORDER BY updated.""".formatted(issue))
                            .isEqualTo(Timestamp.valueOf("2020-01-02 03:04:05"));
                }

                // === 4) idempotent ====================================================
                // The statement is read back OUT of the migration file rather than
                // re-typed here, so this cannot drift into testing a different query.
                exec(conn, "UPDATE " + SCHEMA + ".issues SET started_at = TIMESTAMPTZ '2026-06-06 06:06:06+00'"
                        + " WHERE id = '" + ids.workedThenDone() + "'");
                exec(conn, "SET search_path TO " + SCHEMA);
                exec(conn, backfillStatementFromTheMigration());
                assertThat(startedAt(conn, ids.workedThenDone()))
                        .as("re-running the backfill overwrote a value the application had "
                          + "since written — `AND i.started_at IS NULL` is what makes a "
                          + "migration safe to replay, and a later better backfill safe to add")
                        .isEqualTo(Timestamp.valueOf("2026-06-06 06:06:06"));

                // === 5) the ledger's vocabulary is closed =============================
                assertThat(hasColumn(conn, "sprint_scope_events", "occurred_at"))
                        .withFailMessage("the ledger must carry BOTH when-it-happened and when-it-was-written")
                        .isTrue();
                assertThat(hasColumn(conn, "sprint_scope_events", "created_at"))
                        .withFailMessage("the ledger must carry BOTH when-it-happened and when-it-was-written")
                        .isTrue();
                assertThat(rejectsUnknownEventType(conn, ids))
                        .withFailMessage("sprint_scope_events accepted a third event kind — scope is computed "
                          + "as count(ADDED) - count(REMOVED), so a third value is not a new "
                          + "feature, it is a wrong number")
                        .isTrue();

                // === 6) the three ids belong to ONE tenant, and the DB says so ========
                var accepted = crossTenantRowsAccepted(conn, ids);
                assertThat(accepted)
                        .as(() -> """
                        sprint_scope_events accepted %s. Since V8 §3.8 every \
                        workspace-denormalised table references BOTH parents by \
                        (id, workspace_id), so a cross-tenant row is unrepresentable rather \
                        than merely unwritten — and here it matters twice over, because \
                        idx_sprint_scope_events_ws exists so reports can sweep by workspace \
                        alone.

                        BOTH composite FKs are probed because each can be lost on its own: \
                        a squash that carries sprint_scope_events_sprint_fk forward and \
                        degrades sprint_scope_events_issue_fk to a single-column reference \
                        leaves a schema that still refuses a foreign SPRINT and happily \
                        files a foreign ISSUE into this tenant's velocity sweep. That is \
                        precisely the "quietly forgot to carry it forward" failure this \
                        test exists for.""".formatted(accepted))
                        .isEmpty();
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ the fixture

    private record Fixture(UUID user, UUID workspace, UUID project,
                           UUID workedThenDone, UUID straightToDone, UUID doneThenReopened,
                           UUID neverMoved, UUID renamedStatus, UUID nonStatusHistory,
                           UUID otherTenantsStatus, UUID otherProjectsStatus) {

        java.util.List<UUID> unknowable() {
            return java.util.List.of(neverMoved, renamedStatus, nonStatusHistory,
                    otherTenantsStatus, otherProjectsStatus);
        }

        java.util.List<UUID> all() {
            var out = new java.util.ArrayList<>(unknowable());
            out.add(workedThenDone);
            out.add(straightToDone);
            out.add(doneThenReopened);
            return out;
        }
    }

    private Fixture seedPreV18Fixture(Connection conn) throws SQLException {
        exec(conn, "SET search_path TO " + SCHEMA);
        var user = UUID.randomUUID();
        var workspace = UUID.randomUUID();
        var otherWorkspace = UUID.randomUUID();
        var project = UUID.randomUUID();

        exec(conn, """
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'v18@example.test', 'V18 fixture', 'ACTIVE')
                """.formatted(user));
        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by)
                VALUES ('%s', 'v18-fixture', 'V18 fixture', '%s'),
                       ('%s', 'v18-other',   'Another tenant', '%s')
                """.formatted(workspace, user, otherWorkspace, user));
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by)
                VALUES ('%s', '%s', 'V18 fixture', 'V18', '%s')
                """.formatted(project, workspace, user));
        // Another tenant's private status, named like a real in-progress one. Nothing
        // about it may reach this project's issues.
        exec(conn, """
                INSERT INTO statuses (id, scope_workspace_id, name, category, position)
                VALUES ('%s', '%s', 'Triage', 'IN_PROGRESS', 1)
                """.formatted(UUID.randomUUID(), otherWorkspace));

        var workedThenDone = issue(conn, workspace, project, user, 1, "worked, then finished");
        history(conn, workedThenDone, user, "status", "To Do", "In Progress", STARTED);
        history(conn, workedThenDone, user, "status", "In Progress", "Done", FINISHED);

        var straightToDone = issue(conn, workspace, project, user, 2, "dragged straight to Done");
        history(conn, straightToDone, user, "status", "To Do", "Done", STRAIGHT_TO_DONE);

        var doneThenReopened = issue(conn, workspace, project, user, 3, "done, then reopened");
        history(conn, doneThenReopened, user, "status", "To Do", "In Progress", STARTED);
        history(conn, doneThenReopened, user, "status", "In Progress", "Done", FINISHED);
        history(conn, doneThenReopened, user, "status", "Done", "To Do", "2026-02-07 09:00:00+00");

        var neverMoved = issue(conn, workspace, project, user, 4, "filed and forgotten");

        // The documented blind spot: the status this issue moved into has since been
        // renamed, so no statuses row matches the name history recorded.
        var renamedStatus = issue(conn, workspace, project, user, 5, "moved into a since-renamed status");
        history(conn, renamedStatus, user, "status", "To Do", "Kicked off", STARTED);

        // Adversarial: the right words, the wrong field.
        var nonStatusHistory = issue(conn, workspace, project, user, 6, "an assignee named like a status");
        history(conn, nonStatusHistory, user, "assignee", null, "In Progress", STARTED);

        // Adversarial: the right words, another tenant's status.
        var otherTenantsStatus = issue(conn, workspace, project, user, 7, "a foreign tenants vocabulary");
        history(conn, otherTenantsStatus, user, "status", "To Do", "Triage", STARTED);

        // Adversarial in the OTHER direction: a sibling project of the SAME workspace with
        // a private status. statuses_scope_ck forces scope_workspace_id NULL on a
        // project-scoped row, so the workspace half of the predicate accepts it and only
        // the project half refuses — delete that line and this is the issue that starts
        // getting a start date from a project it has nothing to do with.
        var siblingProject = UUID.randomUUID();
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by)
                VALUES ('%s', '%s', 'V18 sibling', 'V18B', '%s')
                """.formatted(siblingProject, workspace, user));
        exec(conn, """
                INSERT INTO statuses (id, scope_project_id, name, category, position)
                VALUES ('%s', '%s', 'Grooming', 'IN_PROGRESS', 1)
                """.formatted(UUID.randomUUID(), siblingProject));

        var otherProjectsStatus = issue(conn, workspace, project, user, 8,
                "a sibling projects vocabulary");
        history(conn, otherProjectsStatus, user, "status", "To Do", "Grooming", STARTED);

        return new Fixture(user, workspace, project, workedThenDone, straightToDone,
                doneThenReopened, neverMoved, renamedStatus, nonStatusHistory, otherTenantsStatus,
                otherProjectsStatus);
    }

    /** An issue stamped with {@link #FIXED_UPDATED_AT} so the guard is checkable. */
    private UUID issue(Connection conn, UUID workspace, UUID project, UUID user, int number,
                       String title) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO issues (id, workspace_id, project_id, number, title, type_id,
                                    status_id, reporter_id, priority_id, updated_at)
                VALUES ('%s', '%s', '%s', %d, '%s',
                        (SELECT id FROM issue_types WHERE name = 'Task'
                          AND scope_workspace_id IS NULL AND scope_project_id IS NULL LIMIT 1),
                        (SELECT id FROM statuses WHERE name = 'To Do'
                          AND scope_workspace_id IS NULL AND scope_project_id IS NULL LIMIT 1),
                        '%s',
                        (SELECT id FROM priorities WHERE name = 'Medium'
                          AND scope_workspace_id IS NULL AND scope_project_id IS NULL LIMIT 1),
                        TIMESTAMPTZ '%s')
                """.formatted(id, workspace, project, number, title, user, FIXED_UPDATED_AT));
        return id;
    }

    private void history(Connection conn, UUID issue, UUID user, String field, String oldValue,
                         String newValue, String at) throws SQLException {
        exec(conn, """
                INSERT INTO issue_history (id, issue_id, changed_by, field, old_value, new_value, created_at)
                VALUES ('%s', '%s', '%s', '%s', %s, '%s', TIMESTAMPTZ '%s')
                """.formatted(UUID.randomUUID(), issue, user, field,
                        oldValue == null ? "NULL" : "'" + oldValue + "'", newValue, at));
    }

    // ================================================================ assertions

    private Timestamp startedAt(Connection conn, UUID issue) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT started_at AT TIME ZONE 'UTC' FROM " + SCHEMA
                     + ".issues WHERE id = '" + issue + "'")) {
            assertThat(rs.next()).withFailMessage("issue row vanished: " + issue).isTrue();
            return rs.getTimestamp(1);
        }
    }

    private Timestamp updatedAt(Connection conn, UUID issue) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT updated_at AT TIME ZONE 'UTC' FROM " + SCHEMA
                     + ".issues WHERE id = '" + issue + "'")) {
            assertThat(rs.next()).withFailMessage("issue row vanished: " + issue).isTrue();
            return rs.getTimestamp(1);
        }
    }

    /** The CHECK is the closed vocabulary: anything but ADDED/REMOVED must be refused. */
    private boolean rejectsUnknownEventType(Connection conn, Fixture ids) throws SQLException {
        var sprint = UUID.randomUUID();
        // FUTURE, not ACTIVE: sprints_active_ck demands a start date the moment the state
        // flips, and the ledger's CHECK has nothing to do with a sprint's lifecycle.
        exec(conn, """
                INSERT INTO %s.sprints (id, workspace_id, project_id, name, state, sequence, created_by)
                VALUES ('%s', '%s', '%s', 'Sprint 1', 'FUTURE', 1, '%s')
                """.formatted(SCHEMA, sprint, ids.workspace(), ids.project(), ids.user()));
        try {
            // issue_key is NOT NULL, so it has to be supplied here or this probe would
            // "pass" on the wrong constraint and stop testing the CHECK at all.
            exec(conn, """
                    INSERT INTO %s.sprint_scope_events
                        (id, workspace_id, sprint_id, issue_id, issue_key, event)
                    VALUES ('%s', '%s', '%s', '%s', 'V18-1', 'RESIZED')
                    """.formatted(SCHEMA, UUID.randomUUID(), ids.workspace(), sprint,
                            ids.workedThenDone()));
            return false;
        } catch (SQLException expected) {
            return true;
        }
    }

    /**
     * The three ids on a ledger row must belong to ONE tenant, and the DATABASE has to be
     * what says so — the V8 §3.8 rule for every workspace-denormalised table. Asserted
     * against a schema built from scratch, because a composite FK is exactly the kind of
     * constraint a later migration can quietly forget to carry forward.
     *
     * <p><strong>Both</strong> composite FKs are probed, one per call, and each probe
     * keeps every OTHER id native to this tenant so that exactly one constraint can be
     * the thing refusing. A single probe carrying a foreign sprint (which is all this
     * checked until HD-137 R3) leaves {@code sprint_scope_events_issue_fk} entirely
     * unexercised here — a later squash could degrade it to a single-column reference and
     * this test would stay green.
     *
     * <p><strong>A probe that can pass vacuously proves nothing</strong>, and there are two
     * ways this one could. It opens with a CONTROL insert — all three ids native to one
     * tenant — which is not caught: if the statement itself has stopped being valid (a
     * renamed column, a new NOT NULL, a changed vocabulary), this test must fail loudly
     * there rather than return an empty list because both probes threw for a reason that
     * has nothing to do with tenancy. And each probe names the constraint it expects, so a
     * refusal by anything other than {@code 23503} on THAT foreign key is an
     * {@link AssertionError} rather than a pass — see {@link #refused}.
     *
     * @return a description of every cross-tenant row the schema ACCEPTED; empty is the
     *         passing case
     */
    private List<String> crossTenantRowsAccepted(Connection conn, Fixture ids) throws SQLException {
        var otherWorkspace = UUID.randomUUID();
        var otherUser = UUID.randomUUID();
        var otherProject = UUID.randomUUID();
        var otherSprint = UUID.randomUUID();
        exec(conn, """
                INSERT INTO %s.users (id, email, display_name, status)
                VALUES ('%s', 'v18-fk@example.test', 'V18 FK fixture', 'ACTIVE')
                """.formatted(SCHEMA, otherUser));
        exec(conn, """
                INSERT INTO %s.workspaces (id, slug, name, created_by)
                VALUES ('%s', 'v18-fk', 'Another tenant entirely', '%s')
                """.formatted(SCHEMA, otherWorkspace, otherUser));
        exec(conn, """
                INSERT INTO %s.projects (id, workspace_id, name, key, created_by)
                VALUES ('%s', '%s', 'V18 FK', 'V18C', '%s')
                """.formatted(SCHEMA, otherProject, otherWorkspace, otherUser));
        exec(conn, """
                INSERT INTO %s.sprints (id, workspace_id, project_id, name, state, sequence, created_by)
                VALUES ('%s', '%s', '%s', 'Sprint 1', 'FUTURE', 1, '%s')
                """.formatted(SCHEMA, otherSprint, otherWorkspace, otherProject, otherUser));
        // The mirror image of the ids above: this tenant needs a sprint of its own, so the
        // issue probe can hold everything else native. Sequence 2 because
        // rejectsUnknownEventType has already taken 1 in this project.
        var ownSprint = UUID.randomUUID();
        exec(conn, """
                INSERT INTO %s.sprints (id, workspace_id, project_id, name, state, sequence, created_by)
                VALUES ('%s', '%s', '%s', 'Sprint 2', 'FUTURE', 2, '%s')
                """.formatted(SCHEMA, ownSprint, ids.workspace(), ids.project(), ids.user()));
        var otherIssue = issue(conn, otherWorkspace, otherProject, otherUser, 1,
                "another tenants issue entirely");

        // The control. Three ids from ONE tenant must be ACCEPTED, and it is asserted
        // rather than probed: without it both probes below could be throwing for a reason
        // that has nothing to do with either FK — a renamed column, a new NOT NULL, a
        // vocabulary CHECK — and this test would go green having exercised neither. A
        // refusal you did not attribute proves nothing; nor does one you never compared
        // against an acceptance.
        exec(conn, """
                INSERT INTO %s.sprint_scope_events
                    (id, workspace_id, sprint_id, issue_id, issue_key, event)
                VALUES ('%s', '%s', '%s', '%s', 'V18-0', 'ADDED')
                """.formatted(SCHEMA, UUID.randomUUID(), ids.workspace(), ownSprint,
                        ids.workedThenDone()));

        var accepted = new java.util.ArrayList<String>();
        // Probe 1 — this tenant's workspace + issue, THAT tenant's sprint. Only
        // sprint_scope_events_sprint_fk can refuse this.
        if (!refused(conn, ids.workspace(), otherSprint, ids.workedThenDone(),
                "sprint_scope_events_sprint_fk")) {
            accepted.add("a row whose SPRINT belongs to another workspace "
                         + "(sprint_scope_events_sprint_fk must be the composite "
                         + "(sprint_id, workspace_id) -> sprints (id, workspace_id))");
        }
        // Probe 2 — this tenant's workspace + sprint, THAT tenant's issue. Only
        // sprint_scope_events_issue_fk can refuse this.
        if (!refused(conn, ids.workspace(), ownSprint, otherIssue,
                "sprint_scope_events_issue_fk")) {
            accepted.add("a row whose ISSUE belongs to another workspace "
                         + "(sprint_scope_events_issue_fk must be the composite "
                         + "(issue_id, workspace_id) -> issues (id, workspace_id))");
        }
        return accepted;
    }

    /**
     * One ledger INSERT; {@code true} when the database refused it <strong>for the
     * expected reason</strong>.
     *
     * <p>The attribution is the whole point and it is the same note that created these
     * probes, moved one level out: "two constraints at once" was the first way a refusal
     * could be unattributed, "some other constraint entirely" is the second. So a refusal
     * only counts when its SQLSTATE is {@code 23503} (foreign_key_violation) AND the
     * message names {@code constraint}. Anything else — a NOT NULL added by a later squash
     * ({@code 23502}), a renamed column ({@code 42703}), the event CHECK ({@code 23514}) —
     * is re-thrown as an {@link AssertionError} that says which constraint was supposed to
     * be under test, instead of quietly counting as a pass.
     */
    private boolean refused(Connection conn, UUID workspace, UUID sprint, UUID issue,
                            String constraint) {
        try {
            exec(conn, """
                    INSERT INTO %s.sprint_scope_events
                        (id, workspace_id, sprint_id, issue_id, issue_key, event)
                    VALUES ('%s', '%s', '%s', '%s', 'V18-1', 'ADDED')
                    """.formatted(SCHEMA, UUID.randomUUID(), workspace, sprint, issue));
            return false;
        } catch (SQLException e) {
            var message = String.valueOf(e.getMessage());
            if ("23503".equals(e.getSQLState()) && message.contains(constraint)) return true;
            throw new AssertionError("""
                    The cross-tenant probe for %s was refused by something ELSE: SQLSTATE \
                    %s, "%s".

                    That is not a pass. This probe exists to prove ONE composite foreign \
                    key refuses ONE mismatched row; a refusal from an unrelated constraint \
                    (a NOT NULL, a renamed column, a CHECK) makes the probe pass while the \
                    FK it names goes entirely unexercised — which is exactly how a later \
                    squash quietly degrades it to a single-column reference.""".formatted(
                            constraint, e.getSQLState(), message), e);
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table + "'"
                + " AND column_name = '" + column + "'") > 0;
    }

    /**
     * The backfill statement, lifted out of the migration file itself. Re-typing it here
     * would test a copy, and a copy is exactly the thing that stops being the statement
     * that ships.
     */
    private String backfillStatementFromTheMigration() throws Exception {
        var url = getClass().getClassLoader().getResource("db/migration/V18__reports_foundations.sql");
        assertThat(url).as("V18 is not on the test classpath").isNotNull();
        var sql = new String(url.openStream().readAllBytes(), StandardCharsets.UTF_8);
        int from = sql.indexOf("UPDATE issues i");
        assertThat(from).as("the backfill statement is no longer recognisable in V18").isGreaterThanOrEqualTo(0);
        int to = sql.indexOf(';', from);
        assertThat(to).as("the backfill statement is not terminated").isGreaterThan(from);
        return sql.substring(from, to + 1);
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
            assertThat(rs.next()).withFailMessage("expected a row from: " + sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
