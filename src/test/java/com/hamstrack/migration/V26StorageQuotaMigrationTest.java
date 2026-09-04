package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>{@code V26__storage_quota.sql} (HD-191) replayed against a real pre-V26 database that
 * ALREADY HAS ATTACHMENTS</strong> — the backfill, the {@code SET NOT NULL} over populated data,
 * the seed, the composite foreign key and its cascade, the index, the trigger's
 * {@code UPDATE OF} column list, and what its size-only {@code UPDATE} branch actually computes.
 *
 * <h2>Why this file has to exist at all</h2>
 * Every other {@code StorageQuota*} test in this repository runs against a schema where V26 ran
 * <em>before</em> any attachment existed, so every counter value they observe was produced by the
 * TRIGGER. Nothing anywhere asserted the one-shot half of this migration: the
 * {@code UPDATE … FROM issues} backfill, the {@code SET NOT NULL} against rows that already
 * exist, the seed's {@code LEFT JOIN} (including an empty workspace getting a zero row), the FK
 * and its cascade type, the index, or which columns the trigger fires on. That code runs
 * <strong>exactly once per instance, on production, irreversibly</strong> — and
 * {@code ddl-auto=validate} sees none of it: it compares tables and columns and does not look at
 * indexes, triggers, foreign keys or column widths, so a V26 edited into something that no longer
 * backfills would boot perfectly clean and be discovered by the first upload after the deploy,
 * with the migration already applied.
 *
 * <p><strong>An empty database is the one fixture that proves nothing here.</strong> The backfill
 * updates zero rows, the {@code SET NOT NULL} has nothing to reject, and the seed's
 * {@code LEFT JOIN} degenerates to its outer side. So this file inserts the pre-existing
 * population first — two workspaces, one of them deliberately empty, and three attachments across
 * two projects — and only then migrates.
 *
 * <h2>How it runs</h2>
 * The shape of {@link V25MailAnonymousIndexTest} and {@link V23EmailUniquenessMigrationTest}: the
 * suite's own database is already at head, so a migration can only be watched happening by
 * building a pre-V26 database from scratch in a THROWAWAY SCHEMA of the same database. No
 * migration in this repository is schema-qualified, so Flyway can point a whole run at one; it is
 * dropped before and after, so the shared {@code public} schema is never touched and no
 * {@code CREATE DATABASE} privilege is needed. Nothing this file writes outlives it.
 */
class V26StorageQuotaMigrationTest {

    /** Dropped and recreated on every run — never the schema the suite itself uses. */
    private static final String SCHEMA = "v26_migration_test";

    private static final String COUNTER_TABLE = "workspace_storage_usage";
    private static final String FK = "issue_attachments_issue_ws_fk";
    private static final String INDEX = "issue_attachments_workspace_idx";
    private static final String TRIGGER = "trg_issue_attachments_storage_usage";

    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String FK_VIOLATION = "23503";

    /** The three pre-existing attachments, so the expected seed is arithmetic and not a re-query. */
    private static final long A1_BYTES = 1000;
    private static final long A2_BYTES = 2500;
    private static final long A3_BYTES = 7;

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    /**
     * <strong>The one-shot half: an existing population is carried across correctly.</strong>
     *
     * <p>Four things happen in this order and each of them is load-bearing — the column arrives
     * nullable, the backfill fills it from the issue, {@code SET NOT NULL} then succeeds
     * <em>because</em> the backfill left nothing empty, and only afterwards is the counter seeded
     * from those same rows. Reorder any two and the migration either fails on production data or
     * silently seeds zeros over an instance that holds terabytes.
     */
    @Test
    void v26BackfillsTheTenantSeedsTheCountersAndLeavesNoWorkspaceWithoutARow() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            var f = freshAt25(conn);
            try {
                assertThat(columnExists(conn, "issue_attachments", "workspace_id"))
                        .withFailMessage("""
                        the pre-V26 schema already carries issue_attachments.workspace_id, so this \
                        file is watching nothing happen. Either V26 was folded into an earlier \
                        migration (it must not be — V26 has shipped) or the target below is wrong.""")
                        .isFalse();
                assertThat(tableExists(conn, COUNTER_TABLE))
                        .withFailMessage("the pre-V26 schema already carries " + COUNTER_TABLE)
                        .isFalse();

                flyway("26").migrate();

                // ---- the backfill ----
                assertThat(rowsOf(conn, """
                        SELECT count(*) FROM issue_attachments a JOIN issues i ON i.id = a.issue_id
                         WHERE a.workspace_id IS DISTINCT FROM i.workspace_id
                        """))
                        .as("""
                        AN ATTACHMENT'S DENORMALISED TENANT DISAGREES WITH ITS ISSUE'S. The \
                        backfill is UPDATE issue_attachments SET workspace_id = i.workspace_id \
                        FROM issues WHERE i.id = a.issue_id, and it is the only writer of this \
                        column for every row that predates the migration. A wrong value here is \
                        not a cosmetic problem: the trigger reads NEW.workspace_id, so the bytes \
                        would be counted against a workspace that does not hold the file, and the \
                        quota would then refuse uploads in one tenant for storage occupied by \
                        another.""")
                        .isEqualTo(0);
                assertThat(rowsOf(conn, "SELECT count(*) FROM issue_attachments WHERE workspace_id = '"
                                    + f.workspaceA + "'"))
                        .as("all three pre-existing attachments must belong to workspace A")
                        .isEqualTo(3);

                // ---- SET NOT NULL, against a table that already had rows ----
                assertThat(scalar(conn, """
                        SELECT is_nullable FROM information_schema.columns
                         WHERE table_schema = '%s' AND table_name = 'issue_attachments'
                           AND column_name = 'workspace_id'
                        """.formatted(SCHEMA)))
                        .as("""
                        issue_attachments.workspace_id is still NULLABLE after V26. The ALTER \
                        succeeding at all is half the proof (it runs against populated data and \
                        fails outright if the backfill above missed a row); this is the other \
                        half. Nullable, a future insert that forgets the tenant is accepted, the \
                        trigger counts it against NULL, and the row is invisible to every \
                        workspace-keyed query including the reconciler's.""")
                        .isEqualTo("NO");
                var noTenant = refusalOf(() -> exec(conn, """
                        INSERT INTO issue_attachments (id, issue_id, filename, storage_key,
                                                       size_bytes, content_type, uploaded_by)
                        VALUES ('%s', '%s', 'no-tenant.pdf', 'k-no-tenant', 10,
                                'application/pdf', '%s')
                        """.formatted(UUID.randomUUID(), f.issue1, f.user)));
                assertThat(noTenant != null && NOT_NULL_VIOLATION.equals(noTenant.getSQLState()))
                        .withFailMessage(() -> """
                        an INSERT that omits workspace_id must be refused by the DATABASE. This is \
                        asserted behaviourally as well as from information_schema because \
                        ddl-auto=validate does not compare nullability the way it does not compare \
                        widths, so the entity and the column are kept in agreement by hand, and \
                        the loadtest fixture (ops/loadtest/fixture/10-generate.sql) is a writer \
                        outside the application that this constraint is what catches. Actual:\s"""
                        + (noTenant == null ? "accepted" : noTenant.getSQLState()))
                        .isTrue();

                // ---- the seed ----
                assertThat(bytesUsed(conn, f.workspaceA))
                        .as("""
                        THE SEED DOES NOT MATCH THE ROWS THAT WERE ALREADY THERE. Nothing else in \
                        the product ever recomputes this from scratch except the nightly \
                        reconciler, so a wrong seed is a quota enforced against a wrong number \
                        from the moment of the upgrade until that pass runs — and if it seeded \
                        LOW, an instance that is already over its ceiling is not refused, which \
                        nobody notices at all. Expected\s"""
                        + (A1_BYTES + A2_BYTES + A3_BYTES) + ", actual " + bytesUsed(conn, f.workspaceA))
                        .isEqualTo(A1_BYTES + A2_BYTES + A3_BYTES);
                assertThat(attachmentCount(conn, f.workspaceA))
                        .as("the seeded attachment_count must be 3, was " + attachmentCount(conn, f.workspaceA))
                        .isEqualTo(3);

                assertThat(rowsOf(conn, "SELECT count(*) FROM " + COUNTER_TABLE
                                    + " WHERE workspace_id = '" + f.workspaceB + "'"))
                        .as("""
                        THE EMPTY WORKSPACE GOT NO COUNTER ROW, so the seed's LEFT JOIN was \
                        written as an inner one. The migration's own header says every workspace \
                        gets a row INCLUDING the empty ones, so that the ordinary summary read is \
                        a primary-key hit rather than a miss — and, less obviously, so that the \
                        summary answers a FRESHNESS. asOf is the counter row's updated_at and \
                        nothing else: with no row the endpoint reports a total of zero with \
                        asOf = null, and the client then renders a number it cannot date. (It is \
                        NOT that the reconciler would report the tenant — its drift query \
                        COALESCEs both sides to 0, so an empty workspace with no counter row \
                        matches nothing and is never named. That is the same absence read the \
                        other way round.)""")
                        .isEqualTo(1);
                assertThat(bytesUsed(conn, f.workspaceB))
                        .as("the empty workspace's row must be zeroed, not seeded from another tenant")
                        .isEqualTo(0);
                assertThat(attachmentCount(conn, f.workspaceB))
                        .as("the empty workspace's row must be zeroed, not seeded from another tenant")
                        .isEqualTo(0);
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>The structures the header promises, none of which {@code validate} can see</strong>
     * — the composite FK and its cascade, the counter table's own cascade to {@code workspaces},
     * the index, and the trigger's {@code UPDATE OF} list.
     *
     * <p>Each is asserted as a property of the catalog rather than by exercising it, because each
     * has a failure mode that no behavioural test on a healthy database would reach: an FK with
     * the wrong {@code ON DELETE} action only shows up when somebody deletes, a missing index only
     * as a slow reconciler, and a trigger that lost {@code workspace_id} from its {@code UPDATE OF}
     * list only on a path that does not exist yet.
     */
    @Test
    void v26AddsTheCompositeCascadeTheIndexAndATriggerOnBothMutableColumns() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            var f = freshAt25(conn);
            try {
                flyway("26").migrate();

                // ---- the composite FK ----
                var fk = scalar(conn, """
                        SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                          JOIN pg_namespace n ON n.oid = c.connamespace
                         WHERE n.nspname = '%s' AND c.conname = '%s'
                        """.formatted(SCHEMA, FK));
                assertThat(fk != null && fk.contains("FOREIGN KEY (issue_id, workspace_id)")
                       && fk.contains("REFERENCES issues(id, workspace_id)"))
                        .withFailMessage("""
                        the COMPOSITE foreign key is missing or is not composite. A plain FK on \
                        workspace_id alone would prove the workspace exists and say nothing about \
                        whether it is the issue's — which is the entire claim this column makes, \
                        and the one the trigger relies on when it counts NEW.workspace_id. It is \
                        the shape sprint_scope_events (V18) already uses against \
                        issues_id_workspace_id_key. Actual:\s""" + fk)
                        .isTrue();
                assertThat(scalar(conn, """
                        SELECT c.confdeltype FROM pg_constraint c
                          JOIN pg_namespace n ON n.oid = c.connamespace
                         WHERE n.nspname = '%s' AND c.conname = '%s'
                        """.formatted(SCHEMA, FK)))
                        .as("""
                        the composite FK is not ON DELETE CASCADE. An attachment has no meaning \
                        once its issue is gone, so this one cascades where sprint_scope_events \
                        deliberately does not (that ledger keeps its rows). NO ACTION here would \
                        make deleting any issue with an attachment fail against this constraint \
                        even though the single-column FK is happy to cascade — two FKs to the \
                        same parent disagreeing about deletion.""")
                        .isEqualTo("c");
                // ---- the counter table's own FK to workspaces ----
                assertThat(scalar(conn, """
                        SELECT c.confdeltype FROM pg_constraint c
                          JOIN pg_class t ON t.oid = c.conrelid
                          JOIN pg_class r ON r.oid = c.confrelid
                          JOIN pg_namespace n ON n.oid = t.relnamespace
                         WHERE n.nspname = '%s' AND t.relname = '%s'
                           AND r.relname = 'workspaces' AND c.contype = 'f'
                        """.formatted(SCHEMA, COUNTER_TABLE)))
                        .as("""
                        workspace_storage_usage.workspace_id is not ON DELETE CASCADE to \
                        workspaces. The migration header and the entity both promise that a purged \
                        tenant takes its counter with it, and nothing else can check it: \
                        ddl-auto=validate does not look at foreign keys. Without the cascade, \
                        deleting a workspace is refused by this constraint — a counter row nobody \
                        reads would block the delete of the tenant it counts, and the remedy would \
                        be a hand-written DELETE in whatever purge path meets it first.""")
                        .isEqualTo("c");

                var crossTenant = refusalOf(() -> exec(conn, """
                        INSERT INTO issue_attachments (id, issue_id, filename, storage_key,
                                                       size_bytes, content_type, uploaded_by,
                                                       workspace_id)
                        VALUES ('%s', '%s', 'wrong-tenant.pdf', 'k-wrong', 10,
                                'application/pdf', '%s', '%s')
                        """.formatted(UUID.randomUUID(), f.issue1, f.user, f.workspaceB)));
                assertThat(crossTenant != null && FK_VIOLATION.equals(crossTenant.getSQLState()))
                        .withFailMessage("""
                        A ROW WHOSE TENANT DISAGREES WITH ITS ISSUE'S WAS ACCEPTED. That is the \
                        whole reason the FK is composite: "this attachment's workspace IS its \
                        issue's workspace" has to be a database fact, because the application \
                        writes the column from a resolved context and a future writer (a fixture, \
                        a restore, an import) will not. Accepted, those bytes are counted against \
                        a tenant that does not hold the file.""")
                        .isTrue();

                // ---- the index ----
                assertThat(indexColumns(conn))
                        .as("""
                        %s is missing or is not on (workspace_id). ddl-auto=validate does not look \
                        at indexes at all, so nothing else in this build fails without it — the \
                        symptom is that the reconciler's instance-wide drift query and the \
                        per-project breakdown both degrade to sequential scans over every \
                        attachment row on the instance, on a job that is supposed to be cheap \
                        enough that nobody switches it off. Actual:\s""".formatted(INDEX)
                        + indexColumns(conn))
                        .isEqualTo("workspace_id");

                // ---- the trigger, and WHICH COLUMNS it watches ----
                var trigger = scalar(conn, """
                        SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t
                          JOIN pg_class c ON c.oid = t.tgrelid
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = '%s' AND t.tgname = '%s'
                        """.formatted(SCHEMA, TRIGGER));
                assertThat(trigger).as("%s", TRIGGER + " does not exist after V26").isNotNull();
                assertThat(trigger)
                        .as("""
                        THE TRIGGER'S UPDATE OF COLUMN LIST IS NOT (size_bytes, workspace_id). \
                        Both halves matter and they fail differently. Drop size_bytes and a \
                        changed file size never reaches the counter. Drop workspace_id and the \
                        move branch loses its only way in — a branch written for a path that does \
                        not exist today, and that is blocked by the DATABASE rather than by the \
                        entity: issue_attachments_issue_ws_fk is a non-deferrable composite FK, so \
                        moving an attachment between workspaces is refused 23503 and moving its \
                        issue is refused from the parent side. It becomes reachable only if that \
                        FK is dropped or made DEFERRABLE and issues ever move between workspaces, \
                        which is exactly when nobody will think of this column list. The failure \
                        mode of being wrong about it is a permanently overstated counter in one \
                        tenant and an understated one in another, in opposite directions, which no \
                        single-workspace recount explains. Actual:\s""" + trigger)
                        .contains("AFTER INSERT OR DELETE OR UPDATE OF size_bytes, workspace_id");
                assertThat(trigger)
                        .as("""
                        the trigger is not FOR EACH ROW. A statement-level trigger has no NEW/OLD \
                        row, so it cannot know the bytes — and, more importantly, row triggers are \
                        what ON DELETE CASCADE fires. A statement trigger would leave the counter \
                        untouched by every cascaded delete, i.e. a one-way ratchet. Actual:\s"""
                        + trigger)
                        .contains("FOR EACH ROW");
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>After the upgrade, a cascade still moves the seeded number</strong> — the assumption
     * the migration header states in words, checked on a database that was migrated with content
     * rather than born with it.
     *
     * <p>{@code StorageQuotaTest.deletingAProjectReturnsTheCounterToItsPreUploadValue} proves the
     * same property through the API on a schema where V26 ran first. What is different here is the
     * starting number: it came from the SEED, not from the trigger, so this is the only place that
     * shows the two agreeing — a seeded counter that a cascade then decrements correctly.
     */
    @Test
    void aProjectDeletedAfterTheUpgradeTakesItsSeededBytesWithIt() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            var f = freshAt25(conn);
            try {
                flyway("26").migrate();

                assertThat(bytesUsed(conn, f.workspaceA))
                        .as("precondition: the seed is the sum of the pre-existing rows")
                        .isEqualTo(A1_BYTES + A2_BYTES + A3_BYTES);

                exec(conn, "DELETE FROM projects WHERE id = '" + f.project2 + "'");

                assertThat(bytesUsed(conn, f.workspaceA))
                        .as("""
                        DELETING A PROJECT DID NOT RETURN ITS BYTES. The counter's correctness \
                        through project delete, issue delete and any future purge rests entirely \
                        on one assumption the migration header states in prose: a row-level AFTER \
                        DELETE trigger fires for rows removed by ON DELETE CASCADE. If that were \
                        false the counter would be a one-way ratchet — every workspace's usage \
                        would only ever rise, tenants would be refused uploads for space they \
                        long ago freed, and the only thing that would ever notice is the nightly \
                        reconciler. Expected\s"""
                        + (A1_BYTES + A2_BYTES) + ", actual " + bytesUsed(conn, f.workspaceA))
                        .isEqualTo(A1_BYTES + A2_BYTES);
                assertThat(attachmentCount(conn, f.workspaceA))
                        .as("the count must fall with the bytes, was " + attachmentCount(conn, f.workspaceA))
                        .isEqualTo(2);
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>The size-only UPDATE branch, in both of its directions</strong> — the branch this
     * round rewrote from an upsert into a plain {@code UPDATE}, and the one nothing exercised.
     *
     * <p>{@code V26StorageQuotaMigrationTest} asserted from {@code pg_get_triggerdef} that the
     * trigger FIRES on {@code UPDATE OF size_bytes}, and {@code StorageQuotaTest} only ever
     * inserts and deletes — so what the branch COMPUTES had no coverage at all, in a product where
     * the number it computes is what refuses an upload.
     *
     * <p>Two halves, and the second is the claim the rewrite rests on. With a counter row present,
     * a changed {@code size_bytes} moves {@code bytes_used} by the delta and leaves
     * {@code attachment_count} alone — nothing arrived and nothing left. With no counter row,
     * <strong>matching nothing is the correct outcome</strong>: this branch knows a delta and not a
     * count, so creating a row here would have to invent an {@code attachment_count}, and a
     * fabricated count is worse than an absent row — an absent row reads as the zero it honestly
     * is and the reconciler corrects it, while a wrong count is a number the quota trusts.
     */
    @Test
    void aSizeOnlyUpdateMovesTheCounterByTheDeltaAndCreatesNoRowWhenThereIsNone() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            var f = freshAt25(conn);
            try {
                flyway("26").migrate();

                assertThat(bytesUsed(conn, f.workspaceA))
                        .as("precondition: the seed is the sum of the pre-existing rows")
                        .isEqualTo(A1_BYTES + A2_BYTES + A3_BYTES);

                // A1 grows by 1 000. The counter must follow, and the COUNT must not move.
                exec(conn, "UPDATE issue_attachments SET size_bytes = " + (A1_BYTES * 2)
                           + " WHERE size_bytes = " + A1_BYTES);

                long expected = A1_BYTES * 2 + A2_BYTES + A3_BYTES;
                assertThat(bytesUsed(conn, f.workspaceA))
                        .as("""
                        A CHANGED FILE SIZE DID NOT REACH THE COUNTER. The trigger's size-only \
                        branch adds NEW.size_bytes - OLD.size_bytes, and it is the only writer \
                        that can: nothing arrives and nothing leaves, so neither the INSERT nor \
                        the DELETE branch is entered. Without it the quota enforces a number that \
                        is right about the files that exist and wrong about how big they are, \
                        until the nightly reconciler notices. Expected\s""" + expected
                        + ", actual " + bytesUsed(conn, f.workspaceA))
                        .isEqualTo(expected);
                assertThat(attachmentCount(conn, f.workspaceA))
                        .as("""
                        THE ATTACHMENT COUNT MOVED ON A SIZE-ONLY UPDATE. No row arrived and none \
                        left; a count that drifts on an ordinary size change is a count no reader \
                        can trust, and it drifts in whichever direction the branch was written \
                        wrong. Actual:\s""" + attachmentCount(conn, f.workspaceA))
                        .isEqualTo(3);

                // ...and with the counter row gone, the same UPDATE must match NOTHING rather than
                // upsert a row carrying an invented attachment_count.
                exec(conn, "DELETE FROM " + COUNTER_TABLE + " WHERE workspace_id = '"
                           + f.workspaceA + "'");
                exec(conn, "UPDATE issue_attachments SET size_bytes = " + (A1_BYTES * 3)
                           + " WHERE size_bytes = " + (A1_BYTES * 2));

                assertThat(rowsOf(conn, "SELECT count(*) FROM " + COUNTER_TABLE
                                    + " WHERE workspace_id = '" + f.workspaceA + "'"))
                        .as("""
                        THE SIZE-ONLY BRANCH CREATED A COUNTER ROW. It must be a plain UPDATE that \
                        quietly affects nothing when there is none — it knows a DELTA and not a \
                        count, so an upsert here would write attachment_count = 1 for a workspace \
                        whose rows nobody has counted, and the quota would then enforce a number \
                        that was invented by a trigger rather than measured. An absent row reads \
                        as the zero it honestly is and the reconciler restores it. (The same shape \
                        matters inside a multi-level cascade, where an INSERT would fail the FK \
                        against a workspace that is going away and break the delete outright.)""")
                        .isEqualTo(0);
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ fixture

    /** The ids of the pre-V26 population, so every assertion above names a row it inserted. */
    private record Fixture(UUID user, UUID workspaceA, UUID workspaceB,
                           UUID project1, UUID project2, UUID issue1, UUID issue2) {}

    /**
     * Rebuilds the throwaway schema at exactly V25 and fills it with the population V26 has to
     * carry across: two workspaces (the second deliberately EMPTY — it is the one the seed's
     * LEFT JOIN exists for), two projects, two issues and three attachments with distinct sizes.
     *
     * <p>Raw SQL is the legitimate exception here and it is the subject rather than a shortcut:
     * these rows must be written the way a pre-V26 instance wrote them, i.e. with no
     * {@code workspace_id} column to write at all.
     */
    private Fixture freshAt25(Connection conn) throws SQLException {
        dropSchema(conn);
        flyway("25").migrate();
        exec(conn, "SET search_path TO " + SCHEMA);

        var f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        exec(conn, """
                INSERT INTO users (id, email, display_name, status, system_role)
                VALUES ('%s', 'v26-fixture@example.test', 'V26 fixture', 'ACTIVE', 'USER')
                """.formatted(f.user));
        workspace(conn, f.workspaceA, "v26-a", f.user);
        workspace(conn, f.workspaceB, "v26-b-empty", f.user);
        project(conn, f.project1, f.workspaceA, "V26A", f.user);
        project(conn, f.project2, f.workspaceA, "V26B", f.user);
        issue(conn, f.issue1, f.workspaceA, f.project1, 1, f.user);
        issue(conn, f.issue2, f.workspaceA, f.project2, 1, f.user);
        attachment(conn, f.issue1, A1_BYTES);
        attachment(conn, f.issue1, A2_BYTES);
        attachment(conn, f.issue2, A3_BYTES);
        return f;
    }

    private void workspace(Connection conn, UUID id, String slug, UUID owner) throws SQLException {
        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by)
                VALUES ('%s', '%s', 'V26 %s', '%s')
                """.formatted(id, slug, slug, owner));
    }

    private void project(Connection conn, UUID id, UUID workspace, String key, UUID owner)
            throws SQLException {
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by)
                VALUES ('%s', '%s', 'V26 %s', '%s', '%s')
                """.formatted(id, workspace, key, key, owner));
    }

    private void issue(Connection conn, UUID id, UUID workspace, UUID project, int number, UUID user)
            throws SQLException {
        exec(conn, """
                INSERT INTO issues (id, workspace_id, project_id, number, title,
                                    type_id, status_id, reporter_id, priority_id)
                VALUES ('%s', '%s', '%s', %d, 'V26 fixture issue',
                        (SELECT id FROM issue_types ORDER BY position LIMIT 1),
                        (SELECT id FROM statuses ORDER BY position LIMIT 1),
                        '%s',
                        (SELECT id FROM priorities ORDER BY position LIMIT 1))
                """.formatted(id, workspace, project, number, user));
    }

    /** A pre-V26 attachment row: no workspace_id, because the column does not exist yet. */
    private void attachment(Connection conn, UUID issue, long sizeBytes) throws SQLException {
        exec(conn, """
                INSERT INTO issue_attachments (id, issue_id, filename, storage_key, size_bytes,
                                               content_type, uploaded_by)
                SELECT '%s', '%s', 'v26-%d.pdf', 'ws/legacy/%s', %d, 'application/pdf', i.reporter_id
                  FROM issues i WHERE i.id = '%s'
                """.formatted(UUID.randomUUID(), issue, sizeBytes, UUID.randomUUID(), sizeBytes, issue));
    }

    // ================================================================ inspection

    private long bytesUsed(Connection conn, UUID workspace) throws SQLException {
        return rowsOf(conn, "SELECT COALESCE(MAX(bytes_used), -1) FROM " + COUNTER_TABLE
                            + " WHERE workspace_id = '" + workspace + "'");
    }

    private long attachmentCount(Connection conn, UUID workspace) throws SQLException {
        return rowsOf(conn, "SELECT COALESCE(MAX(attachment_count), -1) FROM " + COUNTER_TABLE
                            + " WHERE workspace_id = '" + workspace + "'");
    }

    /** The index's key columns in declaration order, or {@code null} when it does not exist. */
    private String indexColumns(Connection conn) throws SQLException {
        return scalar(conn, """
                SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                  FROM pg_class c
                  JOIN pg_index i ON i.indexrelid = c.oid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                 WHERE n.nspname = '%s' AND c.relname = '%s'
                """.formatted(SCHEMA, INDEX));
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        return rowsOf(conn, """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = '%s' AND table_name = '%s' AND column_name = '%s'
                """.formatted(SCHEMA, table, column)) == 1;
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        return rowsOf(conn, """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = '%s' AND table_name = '%s'
                """.formatted(SCHEMA, table)) == 1;
    }

    // ================================================================ plumbing

    private interface SqlAction {
        void run() throws SQLException;
    }

    /**
     * The exception a statement was refused with, or {@code null} if it succeeded. The connection
     * stays in autocommit, so each probe is its own transaction and a refusal poisons nothing —
     * the arrangement {@link V23EmailUniquenessMigrationTest} uses, for the same reason.
     */
    private SQLException refusalOf(SqlAction action) {
        try {
            action.run();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

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

    /** {@code null} when there is no row. */
    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return null;
            var value = rs.getString(1);
            assertThat(rs.next()).withFailMessage("expected at most one row from: " + sql).isFalse();
            return value;
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
