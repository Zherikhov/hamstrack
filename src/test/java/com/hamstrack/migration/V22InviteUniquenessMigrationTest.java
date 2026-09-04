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
import static org.assertj.core.api.Assertions.assertThatObject;

/**
 * {@code V22__invite_uniqueness.sql} (HD-133) replayed against a real pre-V22 database — its
 * <strong>two cleanup steps</strong>, and the index they clear the way for.
 *
 * <p><strong>Why this file has to exist at all.</strong> Both steps are {@code DELETE}s, and both
 * were measured to remove <em>nothing</em> in every database their authors could see: production
 * held zero unaccepted invitations, development held 1528 and not one of them mixed-case. So the
 * only environment in which either statement does anything is a self-hosted install whose
 * {@code workspace_invites} some other writer touched — i.e. one nobody will ever watch it run in.
 * A migration whose interesting behaviour is unobservable everywhere it is deployed is instrumented
 * or it is unverified.
 *
 * <p><strong>The case the matrix below exists for, stated as the thing that happens without step
 * 1.</strong> Step 2 keeps the NEWEST unaccepted row per {@code (workspace, lower(email))}. Given
 * {@code PAIR@…} written after {@code pair@…} in one workspace, step 2 alone does not merely leave
 * the mixed-case row standing — <em>it hands it the slot and deletes the lowercase one</em>, which
 * is a live, redeemable invitation destroyed by a migration in favour of one that
 * {@code acceptInvite} (an exact {@code equals} against a folded {@code users.email}, HD-120)
 * cannot redeem. That single ordering is what the {@code pairLower}/{@code pairMixed} assertion
 * pins, and every other row in the fixture is there to say what the two steps must <em>not</em>
 * touch while doing it.
 *
 * <p><strong>Accepted rows are the boundary.</strong> They are never deleted, they keep their
 * casing (a mixed-case accepted row is a historical fact, not a live offer), and they sit outside
 * the index by design — which is what makes invited → joined → removed → invited again a normal
 * sequence. An accepted row that kept a slot would be indistinguishable from this migration
 * working, right up until somebody was re-invited.
 *
 * <p><strong>An expired row survives on purpose and is asserted twice</strong> — once as a
 * survivor of the cleanup, once as a row that <em>refuses</em> a fresh insert for its address. A
 * partial index predicate must be {@code IMMUTABLE} and {@code now()} is not, so
 * {@code accepted_at IS NULL} is the only enforceable form: "an expired invitation blocks" is
 * PostgreSQL's answer rather than a product choice, and the product's half of the bargain
 * (withdrawal exists, works on expired rows, and lists them) is held by
 * {@code PendingInvitationsTest} and {@code DuplicateInviteRefusalTest}.
 *
 * <p><strong>How it runs</strong> — same shape as {@link V15RoleBackfillMigrationTest} and
 * {@code V20NotificationsWorkspaceScopeTest}: the suite's own database is already at head, so a
 * migration can only be observed by building a pre-V22 database from scratch in a THROWAWAY SCHEMA
 * of the same database. No migration in this repository is schema-qualified, so Flyway can point a
 * whole run at it; it is dropped afterwards, so the shared {@code public} schema is never touched
 * and no {@code CREATE DATABASE} privilege is needed.
 *
 * <p>The fixture rows are raw SQL — the legitimate exception to "set data up through the service
 * layer", and here it is the subject rather than a shortcut: every interesting row is one
 * {@code inviteMember} cannot write. It has folded with {@code Locale.ROOT} since the initial
 * commit, and since V22 the index refuses the duplicates outright.
 */
class V22InviteUniquenessMigrationTest {

    private static final String SCHEMA = "v22_migration_test";

    /** The index the migration adds; PostgreSQL quotes the identifier verbatim in every locale. */
    private static final String INDEX = "workspace_invites_pending_email_uk";

    /** V13's built-in workspace Member — any valid {@code roles} row will do. */
    private static final String WS_MEMBER = "00000000-0000-7000-8000-000000000003";

    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * Two ids that differ only in their last digit, so the {@code created_at} tie-break has
     * something deterministic to break. Fixed rather than random: {@code UUID.randomUUID()} is v4
     * and unordered, so a random pair would make this assertion a coin flip.
     */
    private static final String TIE_LOWER_ID = "00000000-0000-7000-8000-0000000a0101";
    private static final String TIE_HIGHER_ID = "00000000-0000-7000-8000-0000000a0102";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    @Test
    void theCleanupKeepsTheOfferTheApplicationCanServeAndTheIndexRefusesTheRest() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                // ---- a database as it stood the moment before HD-133 ----
                flyway("21").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);
                var inviter = user(conn, "inviter@example.test");
                var wsA = workspace(conn, inviter, "A");
                var wsB = workspace(conn, inviter, "B");

                assertThat(hasIndex(conn))
                        .withFailMessage("the fixture schema already carries " + INDEX + " — nothing to migrate")
                        .isFalse();

                // (1) A LONE mixed-case unaccepted row. Nobody this application created can accept
                //     it (acceptInvite compares with equals against a folded address), and from
                //     V22 onward it would occupy the slot, so the corrective re-invitation to the
                //     right spelling would be refused until an administrator withdrew it.
                var lone = invite(conn, wsA, inviter, "LONE@example.test", "2026-01-01 10:00:00+00");

                // (2) THE CASE THE ORDER OF THE TWO STEPS EXISTS FOR. The mixed-case row is the
                //     NEWER of the pair, so under step 2 alone it wins "newest per folded address"
                //     and takes the lowercase row with it.
                var pairLower = invite(conn, wsA, inviter, "pair@example.test", "2026-01-02 10:00:00+00");
                var pairMixed = invite(conn, wsA, inviter, "PAIR@example.test", "2026-01-03 10:00:00+00");

                // (3) Two ordinary duplicates, both already folded: newest survives.
                var dupeOld = invite(conn, wsA, inviter, "dupe@example.test", "2026-01-04 10:00:00+00");
                var dupeNew = invite(conn, wsA, inviter, "dupe@example.test", "2026-01-05 10:00:00+00");

                // (4) The tie-break, which the migration header calls "not decoration": created_at
                //     defaults to NOW() (transaction time), so rows written by one seeding
                //     transaction or a scripted import share it exactly.
                invite(conn, wsA, inviter, "tie@example.test", "2026-01-06 10:00:00+00", null,
                        TIE_LOWER_ID);
                invite(conn, wsA, inviter, "tie@example.test", "2026-01-06 10:00:00+00", null,
                        TIE_HIGHER_ID);

                // (5) An ACCEPTED mixed-case row: history, and outside the index by design.
                var acceptedMixed = invite(conn, wsA, inviter, "HIST@example.test",
                        "2026-01-07 10:00:00+00", "2026-01-08 10:00:00+00", null);

                // (6) Accepted AND pending for one address — invited, joined, removed, invited
                //     again is a normal sequence, so both rows must survive.
                var bothAccepted = invite(conn, wsA, inviter, "both@example.test",
                        "2026-01-09 10:00:00+00", "2026-01-10 10:00:00+00", null);
                var bothPending = invite(conn, wsA, inviter, "both@example.test",
                        "2026-01-11 10:00:00+00");

                // (7) An EXPIRED unaccepted row. It holds the slot on purpose.
                var lapsed = invite(conn, wsA, inviter, "lapsed@example.test",
                        "2026-01-12 10:00:00+00", null, null, "NOW() - INTERVAL '1 day'");

                // (8) ANOTHER WORKSPACE, same addresses — and THE TWO STEPS DO NOT AGREE ABOUT
                //     TENANCY, on purpose. Step 2 is partitioned by workspace_id because
                //     uniqueness is per workspace by definition; step 1 is deliberately unscoped,
                //     because "this row is addressed to a spelling nobody can redeem" is a
                //     property of the row and not of the tenant that holds it. So the lowercase
                //     row below must survive and the mixed-case one must not, and asserting both
                //     is what keeps the difference from being read as an oversight in either
                //     direction. Note the newest-wins arithmetic: this dupe row is NEWER than
                //     either of wsA's, so dropping workspace_id from step 2's PARTITION BY would
                //     let it kill dupeNew above — a tenant's invitation deleted by another
                //     tenant's traffic, which is this codebase's top bug class in a migration.
                var foreignDupe = invite(conn, wsB, inviter, "dupe@example.test", "2026-01-13 10:00:00+00");
                var foreignMixed = invite(conn, wsB, inviter, "PAIR@example.test", "2026-01-14 10:00:00+00");

                // ---- the migration under test ----
                flyway("22").migrate();

                // === step 1 ============================================================
                assertThat(exists(conn, lone))
                        .withFailMessage("""
                        a lone mixed-case UNACCEPTED row survived step 1. It is unredeemable by \
                        anyone this application created — acceptInvite matches invite.email \
                        against users.email with equals, and every users row written by \
                        AuthService/AdminUserService/DataSeeder is folded at signup — and from \
                        V22 onward it occupies the slot, so the corrective re-invitation to the \
                        correct spelling is refused with DUPLICATE_INVITE until an administrator \
                        withdraws it.""")
                        .isFalse();
                assertThat(exists(conn, pairLower) && !exists(conn, pairMixed))
                        .withFailMessage("""
                        THE ONE ORDERING THIS FILE EXISTS FOR. With step 1 removed, or moved \
                        below step 2, the NEWER mixed-case row wins "newest per (workspace, \
                        lower(email))" and step 2 DELETES the lowercase one — a live, redeemable \
                        invitation destroyed by a migration, silently, in favour of one nobody \
                        can accept. The survivor must be the lowercase row. Note that the gentler \
                        looking repair, UPDATE ... SET email = lower(email), is the one step 1 \
                        refuses to perform: that row was mailed to the mixed-case spelling, and \
                        CHANGING THE ADDRESS CHANGES WHO THE OFFER GOES TO.""")
                        .isTrue();
                assertThat(emailOf(conn, acceptedMixed))
                        .as("""
                        an ACCEPTED mixed-case row was deleted or re-cased. It is the only record \
                        that a person was invited at all, and it sits outside the partial index \
                        by design, so it costs the live population nothing.""")
                        .isEqualTo("HIST@example.test");

                // === step 2 ============================================================
                assertThat(exists(conn, dupeNew) && !exists(conn, dupeOld))
                        .withFailMessage("""
                        of two unaccepted rows for one address the NEWEST must survive: it \
                        carries the most recent intent and the most recent role, and — while \
                        expires_at stays a fixed seven-day offset from creation — it is also the \
                        last to lapse, so "keep the newest" can never drop a live row in favour \
                        of a lapsed one. If the TTL ever becomes configurable, re-read this.""")
                        .isTrue();
                assertThat(survivorId(conn, wsA, "tie@example.test"))
                        .as("""
                        two rows sharing created_at to the microsecond (one seeding transaction, \
                        a scripted import) must be resolved by id DESC, not by whichever row the \
                        planner happened to return first. UUID v7 is time-ordered, so the higher \
                        id continues the same ordering created_at was expressing.""")
                        .isEqualTo(TIE_HIGHER_ID);
                assertThat(exists(conn, bothAccepted) && exists(conn, bothPending))
                        .withFailMessage("""
                        an accepted row and a pending row for the same address must BOTH survive: \
                        the index is partial precisely so invited -> joined -> removed -> invited \
                        again stays a normal sequence.""")
                        .isTrue();
                assertThat(exists(conn, lapsed))
                        .withFailMessage("""
                        an EXPIRED unaccepted row must survive. It keeps its slot on purpose — \
                        the same shape as labels/components/versions/sprints, where an archived \
                        row keeps its name — and the refusal's job is to name who clears it. \
                        Deleting it here would be the retention sweep this ticket explicitly does \
                        not ship.""")
                        .isTrue();
                assertThat(exists(conn, foreignDupe))
                        .withFailMessage("""
                        step 2's de-duplication reached into another workspace. It is PARTITIONed \
                        by workspace_id because uniqueness is per workspace by definition, and \
                        this row is the newest of the three sharing this folded address — so a \
                        PARTITION BY that forgot workspace_id would keep exactly this one and \
                        delete workspace A's, which is one tenant's invitation destroyed by \
                        another tenant's traffic.""")
                        .isTrue();
                assertThat(exists(conn, foreignMixed))
                        .withFailMessage("""
                        step 1 is deliberately NOT workspace-scoped, and this is the assertion \
                        that says so rather than leaving it to look like an oversight. "Addressed \
                        to a spelling nobody this application created can redeem" is a property \
                        of the ROW, not of the tenant holding it, so the repair is global while \
                        the de-duplication above it is per workspace. Scoping step 1 would leave \
                        unredeemable rows standing in every workspace the fixture did not name, \
                        each one occupying a slot from V22 onward.""")
                        .isFalse();

                assertThat(unacceptedFor(conn, wsA, "pair@example.test"))
                        .as("the cleanup left more than one unaccepted row for some address — which "
                          + "is a state the CREATE UNIQUE INDEX after it could not have built on, "
                          + "so this assertion can only fail together with a migration that threw")
                        .isEqualTo(1);
                assertThat(unacceptedFor(conn, wsA, "dupe@example.test"))
                        .as("the cleanup left more than one unaccepted row for some address — which "
                          + "is a state the CREATE UNIQUE INDEX after it could not have built on, "
                          + "so this assertion can only fail together with a migration that threw")
                        .isEqualTo(1);
                assertThat(unacceptedFor(conn, wsA, "tie@example.test"))
                        .as("the cleanup left more than one unaccepted row for some address — which "
                          + "is a state the CREATE UNIQUE INDEX after it could not have built on, "
                          + "so this assertion can only fail together with a migration that threw")
                        .isEqualTo(1);
                assertThat(unacceptedFor(conn, wsA, "both@example.test"))
                        .as("the cleanup left more than one unaccepted row for some address — which "
                          + "is a state the CREATE UNIQUE INDEX after it could not have built on, "
                          + "so this assertion can only fail together with a migration that threw")
                        .isEqualTo(1);

                // === step 3: the invariant itself ======================================
                // pg_indexes renders the expression with the cast PostgreSQL inserted for a
                // varchar column — lower((email)::text) — so the cast is normalised away rather
                // than pinned: it is an artefact of the column type, not part of the decision.
                var definition = indexDefinition(conn);
                var normalised = definition.replace("(email)::text", "email");
                assertThat(normalised.contains("(workspace_id, lower(email))")
                       && normalised.contains("WHERE (accepted_at IS NULL)"))
                        .withFailMessage("""
                        the constraint is PARTIAL and FUNCTIONAL, and neither half is decoration. \
                        Without lower() the index stops enforcing anything the moment the \
                        boundary fold in inviteMember is moved, weakened, or forgotten on a new \
                        write path — a rule that holds only while every writer remembers to \
                        pre-fold is not an invariant. Without the WHERE it would be a plain \
                        UNIQUE(workspace_id, email), and accepted rows would keep their slots for \
                        ever: invited -> joined -> removed -> invited again would stop working. \
                        And workspace_id leads, so the index is also the access path for the \
                        two-key pre-check. Actual:\s""" + definition)
                        .isTrue();

                var collision = refusalOf(() ->
                        invite(conn, wsA, inviter, "dupe@example.test", "2026-02-01 10:00:00+00"));
                assertThatObject(collision)
                        .as("a second UNACCEPTED row for an address already pending in this "
                          + "workspace must be refused by the DATABASE, not merely by the "
                          + "pre-check in inviteMember. The pre-check is the sentence; two "
                          + "concurrent requests both pass it and only the index arbitrates")
                        .isNotNull();
                assertThat(collision.getSQLState())
                        .as("a second UNACCEPTED row for an address already pending in this "
                          + "workspace must be refused by the DATABASE, not merely by the "
                          + "pre-check in inviteMember. The pre-check is the sentence; two "
                          + "concurrent requests both pass it and only the index arbitrates")
                        .isEqualTo(UNIQUE_VIOLATION);
                assertThat(namesTheIndex(collision))
                        .withFailMessage("""
                        the violation must name the index in its PRIMARY message. That is what \
                        WorkspaceService.isDuplicateInvite translates into the 409 — through \
                        Hibernate's dialect extractor, and through the fallback that matches the \
                        name itself for servers whose lc_messages is not English — and it is the \
                        half that survives logServerErrorDetail=false, which drops the DETAIL \
                        line carrying the colliding address.""")
                        .isTrue();
                assertThat(stateOf(() ->
                        invite(conn, wsA, inviter, "DUPE@example.test", "2026-02-02 10:00:00+00")))
                        .as("a case-only variant must collide. lower() in the index is what makes "
                          + "that true independently of the boundary fold in inviteMember")
                        .isEqualTo(UNIQUE_VIOLATION);
                assertThat(stateOf(() -> invite(conn, wsA, inviter, "dupe@example.test",
                        "2026-02-03 10:00:00+00", "2026-02-03 11:00:00+00", null)))
                        .as("an ACCEPTED row for the same address must still insert: the index is "
                          + "partial, so accepting is the fourth way the slot is freed, alongside "
                          + "withdraw, decline and member removal")
                        .isNull();
                assertThat(stateOf(() ->
                        invite(conn, wsA, inviter, "lapsed@example.test", "2026-02-04 10:00:00+00")))
                        .as("an EXPIRED row blocks. now() is STABLE, so no index predicate can "
                          + "exempt it — which is why DuplicateInviteException carries a second, "
                          + "'has lapsed' wording instead of a narrower index")
                        .isEqualTo(UNIQUE_VIOLATION);
                assertThat(stateOf(() -> invite(conn, wsB, inviter, "lapsed@example.test",
                        "2026-02-05 10:00:00+00")))
                        .as("the index leads on workspace_id: an address pending in workspace A "
                          + "must not block the same address in workspace B")
                        .isNull();
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ fixture

    private UUID user(Connection conn, String email) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO users (id, email, display_name, status, system_role)
                VALUES ('%s', '%s', 'V22 fixture', 'ACTIVE', 'USER')
                """.formatted(id, email));
        return id;
    }

    private UUID workspace(Connection conn, UUID createdBy, String label) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO workspaces (id, name, slug, created_by)
                VALUES ('%s', 'V22 %s', 'v22-%s', '%s')
                """.formatted(id, label, id, createdBy));
        return id;
    }

    private UUID invite(Connection conn, UUID workspace, UUID by, String email, String createdAt)
            throws SQLException {
        return invite(conn, workspace, by, email, createdAt, null, null);
    }

    private UUID invite(Connection conn, UUID workspace, UUID by, String email, String createdAt,
                        String acceptedAt, String forcedId) throws SQLException {
        return invite(conn, workspace, by, email, createdAt, acceptedAt, forcedId,
                "NOW() + INTERVAL '7 days'");
    }

    private UUID invite(Connection conn, UUID workspace, UUID by, String email, String createdAt,
                        String acceptedAt, String forcedId, String expiresAt) throws SQLException {
        var id = forcedId == null ? UUID.randomUUID().toString() : forcedId;
        exec(conn, """
                INSERT INTO workspace_invites
                       (id, workspace_id, email, role_id, token_hash, invited_by, expires_at,
                        accepted_at, created_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', %s, %s, TIMESTAMPTZ '%s')
                """.formatted(id, workspace, email, WS_MEMBER,
                UUID.randomUUID().toString().replace("-", ""), by, expiresAt,
                acceptedAt == null ? "NULL" : "TIMESTAMPTZ '" + acceptedAt + "'", createdAt));
        return UUID.fromString(id);
    }

    // ================================================================ assertions

    private boolean exists(Connection conn, UUID id) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM workspace_invites WHERE id = '" + id + "'") == 1;
    }

    private String emailOf(Connection conn, UUID id) throws SQLException {
        return scalar(conn, "SELECT email FROM workspace_invites WHERE id = '" + id + "'");
    }

    private long unacceptedFor(Connection conn, UUID workspace, String email) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM workspace_invites"
                            + " WHERE workspace_id = '" + workspace + "'"
                            + " AND lower(email) = lower('" + email + "') AND accepted_at IS NULL");
    }

    private String survivorId(Connection conn, UUID workspace, String email) throws SQLException {
        return scalar(conn, "SELECT id FROM workspace_invites"
                            + " WHERE workspace_id = '" + workspace + "'"
                            + " AND lower(email) = lower('" + email + "') AND accepted_at IS NULL");
    }

    private boolean hasIndex(Connection conn) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM pg_indexes WHERE schemaname = '" + SCHEMA
                            + "' AND indexname = '" + INDEX + "'") == 1;
    }

    private String indexDefinition(Connection conn) throws SQLException {
        return scalar(conn, "SELECT indexdef FROM pg_indexes WHERE schemaname = '" + SCHEMA
                            + "' AND indexname = '" + INDEX + "'");
    }

    /**
     * The exception an insert was refused with, or {@code null} if it succeeded.
     *
     * <p>The connection stays in autocommit, so each probe is its own transaction and a refusal
     * poisons nothing. Savepoints would need a transaction block this connection deliberately does
     * not open — Flyway runs on connections of its own, and a half-open one here would hold locks
     * across the {@code CREATE UNIQUE INDEX} above.
     */
    private SQLException refusalOf(SqlAction action) {
        try {
            action.run();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private String stateOf(SqlAction action) {
        var refusal = refusalOf(action);
        return refusal == null ? null : refusal.getSQLState();
    }

    private boolean namesTheIndex(SQLException refusal) {
        for (Throwable t = refusal; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains(INDEX)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ plumbing

    private interface SqlAction {
        void run() throws SQLException;
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

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).withFailMessage("expected a row from: " + sql).isTrue();
            var value = rs.getString(1);
            assertThat(rs.next()).withFailMessage("expected exactly one row from: " + sql).isFalse();
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
