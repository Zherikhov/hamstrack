package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>{@code V25__mail_send_events_anonymous_index.sql} (HD-202 review) replayed against a real
 * pre-V25 database</strong> — the index exists, it is on the right table, it is PARTIAL, and its
 * columns are in the order the query needs.
 *
 * <h2>Why this file has to exist at all</h2>
 * <strong>{@code ddl-auto=validate} does not look at indexes.</strong> It compares tables and
 * columns; a foreign key, a check constraint or an index can be absent and the application boots
 * perfectly clean — the point {@link V19IssuesTaxonomyFkTest}'s header makes, and HD-13's
 * precedent. So nothing in the build fails if this migration is dropped, renamed, or edited into
 * something that no longer answers the query it was written for.
 *
 * <p>That would be a quiet gap for any index. For this one it is a gap in the product's only
 * witness to an attacker who is never refused. {@code AnonymousMailConcentration} runs
 * {@code MailSendEventRepository.maxAnonymousSendsToOneRecipient} every five minutes on every
 * replica; without this index that aggregate is a scan whose cost grows with the table, and the
 * population that makes it grow is the flood it exists to detect. It then times out, the gauge
 * FREEZES at its last quiet value, and {@code MailRecipientConcentration} reads a frozen quiet
 * number as a quiet instance.
 *
 * <p><strong>And the index is already named to operators as something to check.</strong>
 * {@code MailConcentrationGaugeStale}'s summary in {@code rules.yml} and the metric table in
 * {@code docs/observability.md} both tell a paged reader to verify that
 * {@value #INDEX} (V25) exists. Two operator surfaces send somebody looking for this object by
 * name, so its existence is a shipped promise rather than an implementation detail.
 *
 * <h2>What is asserted, and why each half</h2>
 * <ul>
 *   <li><strong>It exists, on {@code mail_send_events}.</strong> The bare existence claim the two
 *       operator surfaces make.</li>
 *   <li><strong>It is PARTIAL</strong> ({@code pg_index.indpred IS NOT NULL}, and the predicate
 *       names {@code sender_user_id}). A full index over the same two columns would still answer
 *       the query, so a test that only checked the columns would pass through the edit that costs
 *       the partiality — and the partiality is what keeps the relation small on an instance whose
 *       table is mostly authenticated invitation traffic.</li>
 *   <li><strong>Both columns, in order</strong> — {@code created_at} then {@code recipient_key}.
 *       Leading on {@code created_at} is what makes the {@code > :since} window a range scan;
 *       carrying {@code recipient_key} second is what answers the {@code GROUP BY} from the index
 *       instead of from the heap, and it is the column V21's {@code (sender_user_id, created_at)}
 *       lacks. Reversed, this is a differently-shaped index that happens to have the right name.</li>
 * </ul>
 *
 * <p><strong>How it runs</strong> — the shape of {@link V23EmailUniquenessMigrationTest} and
 * {@link V22InviteUniquenessMigrationTest}: the suite's own database is already at head, so the
 * only way to watch V25 <em>happen</em> is to build a pre-V25 database from scratch in a THROWAWAY
 * SCHEMA of the same database. No migration in this repository is schema-qualified, so Flyway can
 * point a whole run at it; it is dropped before and after, so the shared {@code public} schema is
 * never touched and no {@code CREATE DATABASE} privilege is needed. Nothing this file writes
 * outlives it.
 */
class V25MailAnonymousIndexTest {

    /** Dropped and recreated on every run — never the schema the suite itself uses. */
    private static final String SCHEMA = "v25_migration_test";

    /** The index V25 adds, by the name two operator surfaces hand a paged reader. */
    static final String INDEX = "idx_mail_send_events_anonymous";

    private static final String TABLE = "mail_send_events";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    @Test
    void v25AddsThePartialIndexTheConcentrationGaugeReadsThrough() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("24").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);

                assertThat(tableOf(conn, INDEX))
                        .as("""
                        the pre-V25 schema already carries the index, so this file is watching \
                        nothing happen. Either V25 was folded into an earlier migration (it must \
                        not be — V21..V24 are applied everywhere) or the target above is wrong.""")
                        .isNull();

                flyway("25").migrate();

                assertThat(tableOf(conn, INDEX))
                        .as("""
                        %s does not exist on %s after V25. ddl-auto=validate does NOT look at \
                        indexes, so nothing else in this build fails when it is gone: the \
                        application boots clean, and the only symptom is that \
                        maxAnonymousSendsToOneRecipient degrades to a full scan every five \
                        minutes on every replica, times out, and leaves \
                        hamstrack_mail_anonymous_recipient_max FROZEN at its last quiet value — \
                        which reads exactly like a calm instance. Both \
                        MailConcentrationGaugeStale (rules.yml) and docs/observability.md tell a \
                        paged operator to check for this object BY NAME, so a rename is a \
                        three-file edit, not a one-file one. Found on table:\s"""
                                .formatted(INDEX, TABLE) + tableOf(conn, INDEX))
                        .isEqualTo(TABLE);

                var predicate = predicateOf(conn, INDEX);
                assertThat(predicate)
                        .as("""
                        the index is not PARTIAL. A full index on the same two columns still \
                        answers the query, so this assertion is the only thing standing between \
                        that edit and a silent cost increase: without WHERE sender_user_id IS \
                        NULL the index carries every authenticated invitation row on the \
                        instance, which is the majority of the table anywhere real work happens, \
                        and the relation the five-minute aggregate walks grows with traffic that \
                        has nothing to do with what it measures.""")
                        .isNotNull();
                assertThat(predicate)
                        .as("""
                        the index is partial on something other than the sender. The predicate \
                        has to be the query's own constant — sender_user_id IS NULL — or the \
                        planner cannot prove the index covers the rows being asked for and will \
                        not use it at all. Actual:\s""" + predicate)
                        .contains("sender_user_id");

                var columns = columnsOf(conn, INDEX);
                assertThat(columns)
                        .as("""
                        the index columns are not (created_at, recipient_key) in that order. \
                        Leading on created_at is what turns "created_at > :since" into a range \
                        scan; carrying recipient_key SECOND is what answers the GROUP BY from the \
                        index instead of the heap, and it is precisely the column V21's \
                        (sender_user_id, created_at) lacks — which is why that index did not \
                        already solve this. Reversed, this is a different index wearing the right \
                        name, and every operator surface that names it would still find it. \
                        Actual:\s""" + columns)
                        .isEqualTo("created_at,recipient_key");
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ inspection

    /** The table an index sits on, or {@code null} when the index does not exist. */
    private String tableOf(Connection conn, String index) throws SQLException {
        return scalar(conn, """
                SELECT t.relname
                  FROM pg_class c
                  JOIN pg_index i ON i.indexrelid = c.oid
                  JOIN pg_class t ON t.oid = i.indrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = '%s' AND c.relname = '%s'
                """.formatted(SCHEMA, index));
    }

    /**
     * The rendered {@code WHERE} of a partial index, or {@code null} when it has none —
     * {@code pg_index.indpred IS NOT NULL} is the "is it partial" question, and the rendered text
     * is what says partial on WHAT.
     */
    private String predicateOf(Connection conn, String index) throws SQLException {
        return scalar(conn, """
                SELECT pg_get_expr(i.indpred, i.indrelid)
                  FROM pg_class c
                  JOIN pg_index i ON i.indexrelid = c.oid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = '%s' AND c.relname = '%s' AND i.indpred IS NOT NULL
                """.formatted(SCHEMA, index));
    }

    /**
     * The index's key columns joined in declaration order — the order is the assertion, so it is
     * read out of {@code indkey}'s ordinality rather than out of {@code attnum}, which is the
     * table's column order and not the index's.
     */
    private String columnsOf(Connection conn, String index) throws SQLException {
        return scalar(conn, """
                SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                  FROM pg_class c
                  JOIN pg_index i ON i.indexrelid = c.oid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                 WHERE n.nspname = '%s' AND c.relname = '%s'
                """.formatted(SCHEMA, index));
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

    /** {@code null} when there is no row — which is how an absent index is expressed. */
    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return null;
            var value = rs.getString(1);
            assertThat(rs.next()).withFailMessage("expected at most one row from: " + sql).isFalse();
            return value;
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
