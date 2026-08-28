package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <strong>{@code V23__users_email_uniqueness.sql} (HD-167) replayed against a real pre-V23
 * database</strong> — its blocking pre-flight, its non-blocking notice, and the index they clear
 * the way for. Acceptance criteria 1–6 of {@code docs/design/email-uniqueness-proposal.md} §12.
 *
 * <p><strong>Why this file has to exist at all, in the terms the ticket itself used.</strong>
 * The pre-flight was measured to refuse <em>nothing</em> in every database its author could see:
 * production held 5 users, 0 of them unfolded, 0 non-ASCII. So the only environment in which
 * step 1 or step 2 does anything is a self-hosted install whose {@code users} table some other
 * writer touched — i.e. one nobody will ever watch it run in. Both branches were reproduced by
 * hand during review; a hand reproduction is a measurement, not a seal, and it is not repeated on
 * the next change to this file.
 *
 * <p><strong>THE CLAIM THIS FILE EXISTS TO KEEP HONEST IS THE ONE ABOUT THE HISTORY TABLE.</strong>
 * The migration's header says a refusal leaves <em>no</em> {@code flyway_schema_history} row at
 * all — not a {@code success = false} one — because PostgreSQL writes that row inside the same
 * transaction the {@code RAISE EXCEPTION} aborts, and therefore that a re-run after the remedy
 * needs no {@code flyway repair}. That is the difference between an operator who restarts and one
 * who reads a stack trace about a failed migration they cannot clear, so it is asserted twice
 * (once per refusing branch) as an <em>absence</em>: {@code version = '23'} appearing at all is
 * the failure, whatever its {@code success} column says.
 *
 * <p><strong>Two vehicles, and which claim each one can carry.</strong>
 * <ul>
 *   <li><strong>Flyway</strong> for everything transactional: the two refusals, the rows they
 *       leave standing, the absent index, the absent history row, and the re-run after the
 *       remedy. Only Flyway wraps the file the way a real upgrade does.</li>
 *   <li><strong>A direct execution of the shipped file on a statement of our own</strong> for the
 *       two claims about <em>messages</em>. A PostgreSQL {@code RAISE NOTICE} arrives as a
 *       {@link SQLWarning} on the statement that ran it, and Flyway's connections are its own —
 *       nothing we can reach drains them. The file is read from the classpath
 *       ({@code /db/migration/V23__users_email_uniqueness.sql}), so it is the shipped bytes and
 *       not a paraphrase; what the direct run gives up is only the enclosing transaction, which
 *       is exactly the property the two notice cases do not depend on — they succeed.</li>
 * </ul>
 *
 * <p><strong>Why the non-English half of the story is NOT here.</strong> {@code lc_messages} on
 * this project's container is {@code en_US.utf8} and the image carries no other message catalogue
 * — measured: {@code SET lc_messages = 'ru_RU.UTF-8'} is accepted and the very next error still
 * comes back in English. A server whose dialect extraction returns {@code null} therefore cannot
 * be built here at all, which is why the fallback branch of {@code EmailUniqueness} is sealed by
 * synthesised cause chains in {@code EmailUniquenessTranslationTest} rather than by a database.
 *
 * <p><strong>How it runs</strong> — the same shape as {@link V22InviteUniquenessMigrationTest}:
 * the suite's own database is already at head, so a migration can only be observed by building a
 * pre-V23 database from scratch in a THROWAWAY SCHEMA of the same database. No migration in this
 * repository is schema-qualified, so Flyway can point a whole run at it; it is dropped before and
 * after every case, so the shared {@code public} schema is never touched and no
 * {@code CREATE DATABASE} privilege is needed. <strong>Nothing this file writes outlives it.</strong>
 *
 * <p>The fixture rows are raw SQL — the legitimate exception to "set data up through the service
 * layer", and here it is the subject rather than a shortcut: every interesting row is one no
 * writer in this application can produce: every one of them folds with {@code Locale.ROOT} before
 * it inserts, which is precisely the convention V23 exists to stop depending on — and naming the
 * writers rather than the property is how that sentence would go stale one writer early.
 */
class V23EmailUniquenessMigrationTest {

    private static final String SCHEMA = "v23_migration_test";

    /** The index the migration adds; PostgreSQL quotes the identifier verbatim in every locale. */
    private static final String FOLDED_INDEX = "users_email_lower_uk";

    /** {@code V1__init_schema.sql}'s byte-exact one, which V23 deliberately does not replace. */
    private static final String EXACT_INDEX = "users_email_key";

    private static final String UNIQUE_VIOLATION = "23505";

    /** The shipped file, read rather than paraphrased. */
    private static final String MIGRATION = "/db/migration/V23__users_email_uniqueness.sql";

    /**
     * A dotless i (U+0131), written as an escape ON PURPOSE. The whole point of this row is that
     * it is invisible in a diff and indistinguishable from an ordinary lowercase address; a
     * literal would also make the case depend on the compiler's source encoding, which is exactly
     * the class of accident HD-120 was.
     */
    private static final String LOCALE_FOLDED = "\u0131t-admin@corp.example";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    // ================================================================ accepted

    /**
     * <strong>A clean database takes the index, says nothing, and keeps the byte-exact
     * constraint</strong> (AC 4, AC 5, AC 6).
     *
     * <p>The silence is asserted rather than assumed. Step 2 is a {@code RAISE NOTICE} whose whole
     * contract is that it fires only on a population that exists, and a notice printed on every
     * upgrade would train operators to ignore the one time it matters — an alert that is worse
     * than no alert.
     *
     * <p><strong>Then the guarantee itself, by direct SQL rather than through a service</strong>
     * (AC 5, and the ticket says "direct SQL" for a reason): going through {@code register} would
     * prove only that the folded pre-check works, which is a property of Java. The claim under
     * test is that the DATABASE refuses, which is what stays true for the writers this ticket was
     * filed about — LDAP/SSO provisioning, an admin bulk import, a support script.
     *
     * <p><strong>And both indexes are exercised, because the argument for keeping
     * {@code users_email_key} is that they do two different jobs.</strong> A case-only variant can
     * only be refused by the folded index; a byte-identical one is refused by the exact one. If a
     * future edit dropped {@code users_email_key} as "redundant", a probe that only checked the
     * SQLSTATE would still pass — so the second one asserts the NAME, which is the only thing that
     * tells the two apart.
     */
    @Test
    void aCleanDatabaseTakesTheIndexInSilenceAndKeepsBothJobs() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt22(conn);
            try {
                user(conn, "ivan@example.test");
                user(conn, "petr@example.test");

                var warnings = runMigrationDirectly(conn);

                assert warnings.isEmpty() : """
                        V23 printed something on a database with nothing wrong with it. Step 2's \
                        notice is gated on a population that exists precisely so an upgrade is \
                        quiet when there is nothing to say; a line printed on every upgrade is \
                        one every operator learns to skip, including the time it is the locale \
                        break HD-120 shipped. Printed:\s""" + warnings;

                var definition = indexDefinition(conn, FOLDED_INDEX);
                // pg_indexes renders the cast PostgreSQL inserts for a varchar column —
                // lower((email)::text) — so it is normalised away rather than pinned: an artefact
                // of the column type, not part of the decision.
                assert definition != null
                       && definition.replace("(email)::text", "email").contains("(lower(email))") : """
                        the index must be FUNCTIONAL and it must be lower(). Without lower() it \
                        enforces exactly what users_email_key already enforces and the whole \
                        ticket is a no-op; with upper() it would ask a different question than \
                        UserRepository.existsByFoldedEmail asks, which is the one defect the \
                        write-side check was folded to avoid. Actual:\s""" + definition;
                assert indexDefinition(conn, EXACT_INDEX) != null : """
                        users_email_key was dropped. It is redundant as a CONSTRAINT and NOT \
                        redundant as an INDEX: it is the access path for WHERE email = ?, which \
                        is the comparison login/forgotPassword/acceptInvite keep exact on purpose \
                        (an extra match on a RESOLUTION admits the wrong person), and an index on \
                        lower(email) cannot serve it.""";

                var folded = refusalOf(() -> user(conn, "IVAN@example.test"));
                assert folded != null && UNIQUE_VIOLATION.equals(folded.getSQLState()) : """
                        a second row differing only in case must be refused BY THE DATABASE, and \
                        it is inserted BY DIRECT SQL — not through AuthService.register, whose \
                        pre-check is Java and is exactly the convention this migration exists to \
                        stop depending on. The writers this ticket names (LDAP/SSO provisioning, \
                        admin bulk import, a support script) all insert from outside those three \
                        methods.""";
                assert names(folded, FOLDED_INDEX) : """
                        the violation must name the folded index in its PRIMARY message: that is \
                        what Hibernate's dialect extractor reads and what EmailUniqueness's \
                        fallback matches on a server whose lc_messages is not English. It is also \
                        the half that survives logServerErrorDetail=false, which drops the DETAIL \
                        line carrying the colliding address. Actual:\s""" + folded.getMessage();

                var exact = refusalOf(() -> user(conn, "ivan@example.test"));
                assert exact != null && names(exact, EXACT_INDEX) : """
                        A BYTE-IDENTICAL duplicate must be refused by users_email_key, and the \
                        NAME is the assertion rather than the status code. Both indexes answer \
                        23505 here, so a case that only checked the SQLSTATE would stay green \
                        with users_email_key dropped as "redundant" — and the exact index is what \
                        serves every WHERE email = ? lookup on the authentication path. Actual:\s"""
                        + (exact == null ? "no refusal at all" : exact.getMessage());

                assert refusalOf(() -> user(conn, "somebody-else@example.test")) == null
                        : "an unrelated address must still insert — the index refuses collisions, "
                          + "not writes";
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>The locale-broken row is a DIFFERENT population from the case-broken one, is warned
     * about, and is not touched</strong> (AC 3).
     *
     * <p>This is the failure HD-120 actually hit: a Turkish JVM folded {@code I} to a dotless
     * {@code i} (U+0131), which is <em>already lowercase</em>, so {@code lower()} leaves it alone
     * and step 1's predicate is false. The row reads clean while the application — folding the
     * typed {@code I} with {@code Locale.ROOT} — never matches it. Both halves of that are
     * asserted here, because either alone reads as an accident: step 1's count is zero AND the
     * notice fires.
     *
     * <p><strong>It must not block, and that is a decision rather than an oversight.</strong> The
     * predicate is a proxy — the fingerprint of a locale-dependent fold <em>and</em> of a
     * perfectly legitimate internationalised address, and no query can tell those apart. Refusing
     * an upgrade over one would be a refusal whose only performable remedy is "stop using your own
     * alphabet".
     */
    @Test
    void aLocaleFoldedRowIsWarnedAboutAndLeftExactlyAsItStands() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt22(conn);
            try {
                user(conn, LOCALE_FOLDED);
                user(conn, "ordinary@example.test");

                assert rowsOf(conn, "SELECT count(*) FROM users WHERE email <> lower(email)") == 0 : """
                        THE PREMISE, AND IT IS THE POINT OF THE CASE. U+0131 is already lowercase, \
                        so this row is its own fold and step 1's predicate cannot see it. If this \
                        ever fails, the fixture has stopped standing in for the locale break and \
                        everything below it is asserting nothing.""";

                var warnings = runMigrationDirectly(conn);

                assert warnings.size() == 1 : """
                        exactly one notice, from step 2. Zero means the advisory was deleted or \
                        its predicate stopped matching a non-ASCII address — and an account that \
                        cannot log in and cannot be mailed a reset link then stays unreachable in \
                        complete silence, which is what made HD-120 take a support ticket to \
                        find. Printed:\s""" + warnings;
                var notice = warnings.get(0);
                assert notice.contains("HD-167") && notice.contains("non-ASCII")
                       && notice.contains("does not block") : """
                        the notice must say which ticket it comes from, what it saw, and that it \
                        is NOT a verdict — an operator who reads "non-ASCII address" as an error \
                        goes and "fixes" a legitimate internationalised account. Actual:\s"""
                        + notice;
                assert notice.contains("self-hosting.md") : """
                        a flag whose reader cannot act on it is noise. The one thing that CAN be \
                        done — the pair query and the three remedies — lives in one document, and \
                        naming it is the notice's entire job.""";

                assert indexDefinition(conn, FOLDED_INDEX) != null : """
                        THE NOTICE MUST NOT BLOCK. A non-ASCII address is legal, Hamstrack \
                        accepts it, and the index neither repairs such a row nor is obstructed by \
                        one. Turning step 2 into a refusal would stop an upgrade over an address \
                        whose only performable remedy is "stop using your own alphabet".""";
                assert LOCALE_FOLDED.equals(scalar(conn,
                        "SELECT email FROM users WHERE email LIKE '%t-admin@corp.example'")) : """
                        the row must come out byte-identical. NOTHING IS REPAIRED, FOLDED OR \
                        DELETED by this migration: a migration may repair what its own \
                        application can recreate and must refuse what it cannot, and nothing \
                        recreates an account. Rewriting an address also changes WHICH MAILBOX CAN \
                        RESET THAT ACCOUNT'S PASSWORD.""";
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ refused

    /**
     * <strong>A colliding pair aborts the upgrade, keeps both accounts, and records nothing</strong>
     * (AC 1).
     *
     * <p>Every clause of that sentence is a separate assertion, because each is a separate way
     * this could go wrong and two of them are silent. <em>Both rows survive</em> is the whole
     * design: {@code UPDATE users SET email = lower(email)} is the gentler-looking option and the
     * worse one, because {@code Ivan@} and {@code ivan@} are two different mailboxes on any
     * RFC-compliant server, so folding in place changes who can reset that account's password.
     * <em>No history row at all</em> is what makes the re-run need no {@code flyway repair}.
     *
     * <p>The counts in the message are asserted, and they count different things on purpose:
     * {@code unfolded} counts ROWS (one here — {@code ivan@} is already its own fold) and
     * {@code collisions} counts GROUPS (one). A refusal that got those the wrong way round sends
     * an operator looking for two broken rows and finding one.
     *
     * <p>The last third of the case is the remedy actually being performed — the only way to
     * assert "no repair step" at all, since a repair step is something you notice by <em>not</em>
     * needing it.
     */
    @Test
    void aCollidingPairAbortsAndLeavesNothingBehindToRepair() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt22(conn);
            try {
                var mixed = user(conn, "Ivan@example.test");
                var lower = user(conn, "ivan@example.test");

                var abort = migrationRefusal();

                assert abort != null : """
                        V23 ran to completion on a database holding Ivan@ and ivan@ as two \
                        accounts. It cannot have built the index (users_email_key already forbids \
                        byte-equal values, so every colliding fold group contains an unfolded \
                        row), so the pre-flight was skipped and the index build failed for the \
                        operator instead — naming THE INDEX AND NOT THE ROWS, because \
                        logServerErrorDetail=false drops PostgreSQL's DETAIL line. A refusal may \
                        only prescribe an action its reader can perform.""";
                assert abort.contains("HD-167 V23 aborted")
                       && abort.contains("NO ACCOUNT HAS BEEN CHANGED") : """
                        the first thing the reader of a failed upgrade needs is which change \
                        aborted and whether it half-applied. Actual:\s""" + abort;
                assert abort.contains("1 row(s)") && abort.contains("1 colliding fold group(s)") : """
                        THE TWO COUNTS COUNT DIFFERENT THINGS, and swapping them sends an \
                        operator looking for rows that are not there. unfolded counts ROWS whose \
                        stored address is not its own fold (here: Ivan@ only — ivan@ is already \
                        folded), collisions counts GROUPS that fold together (here: one group of \
                        two). Actual:\s""" + abort;
                assert abort.contains("FIND THE COLLISIONS")
                       && abort.contains("RESOLVE EVERY COLLISION FIRST")
                       && abort.contains("THE ORDER IS LOAD-BEARING") : """
                        with collisions present the refusal owes BOTH halves of the remedy AND \
                        their order. Folding before resolving does not fail safely: a blind fold \
                        over a colliding pair SUCCEEDS in producing two identical addresses and \
                        is only then refused by users_email_key, which reads like a different \
                        bug. Actual:\s""" + abort;
                assert !abort.contains("@example.test") : """
                        THE MESSAGE CARRIES COUNTS AND QUERIES, NEVER ADDRESSES. Its reader is at \
                        a database prompt by definition, so a SELECT is a performable remedy, \
                        while third-party addresses written into a shipped log are not something \
                        this project does — the same rule as WorkspaceService's domain-only \
                        invite line, and the same reason logServerErrorDetail=false shipped one \
                        release earlier. A count also scales where an enumeration does not: an \
                        operator with 400 broken rows needs a query, not a 400-address sentence.""";

                assert exists(conn, mixed) && exists(conn, lower) : """
                        BOTH ACCOUNTS MUST STILL BE THERE. Nothing recreates an account — it owns \
                        issues, comments, memberships, sessions and history — and the \
                        gentler-looking `UPDATE users SET email = lower(email)` is not the kinder \
                        option: Ivan@ and ivan@ are two different mailboxes on any RFC-compliant \
                        server, so folding in place changes WHICH MAILBOX CAN RESET THAT \
                        ACCOUNT'S PASSWORD.""";
                assert indexDefinition(conn, FOLDED_INDEX) == null
                        : "the transaction aborted, so the index must not exist";
                assertNothingRecorded(conn);

                // ---- the remedy, and then the re-run with no repair in between ----
                exec(conn, "DELETE FROM users WHERE id = '" + mixed + "'");
                assert migrationRefusal() == null : """
                        THE RE-RUN AFTER THE REMEDY MUST JUST WORK. Nothing was recorded, so \
                        there is no failed row to clear and no `flyway repair` step — and \
                        performing it is the only way to state that, because a repair step is \
                        something you notice by not needing it. If this fails with "detected \
                        failed migration", the history row this migration's header says is \
                        impossible was written after all.""";
                assert indexDefinition(conn, FOLDED_INDEX) != null
                        : "the re-run succeeded but built no index";
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>A LONE mixed-case row refuses too, and is handed the shorter remedy</strong>
     * (AC 2).
     *
     * <p>This is deliberately stricter than "would the index build succeed" — the build would
     * succeed here — and the reason is that such a row is not harmless. Its owner already cannot
     * log in (every lookup folds the typed address first) and already cannot receive a reset mail;
     * and from V23 onward it SQUATS the folded key, so the correct spelling becomes unregisterable
     * — a 409 for an address nobody holds, which no operator would ever connect to this row. The
     * upgrade is the only moment anyone will look.
     *
     * <p><strong>The wording is asserted as an exclusion, which is the half worth having.</strong>
     * An operator who runs a query the refusal itself offered and gets an empty result reads that
     * as "the tool is broken", not as "there is nothing here" — so the collision half of the
     * remedy must be ABSENT, not merely present-and-unused.
     */
    @Test
    void aLoneMixedCaseRowRefusesAndIsNotOfferedAQueryThatReturnsNothing() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt22(conn);
            try {
                var lone = user(conn, "Ivan@example.test");
                user(conn, "petr@example.test");

                var abort = migrationRefusal();

                assert abort != null : """
                        A LONE MIXED-CASE ROW MUST BLOCK, AND THIS IS THE ASSERTION THAT SAYS THE \
                        STRICTNESS IS DELIBERATE. The index would build perfectly well over this \
                        database, so a pre-flight written as "would CREATE UNIQUE INDEX succeed" \
                        lets it through — and the row then silently occupies the folded key, \
                        making the correct spelling unregisterable with a 409 for an address \
                        nobody holds. Its owner already cannot log in and already cannot receive \
                        a reset mail. The upgrade is the only moment anyone will look.""";
                assert abort.contains("1 row(s)") && abort.contains("0 colliding fold group(s)")
                        : "one unfolded row, no collisions. Actual: " + abort;
                assert abort.contains("NO TWO STORED ADDRESSES FOLD TOGETHER")
                       && abort.contains("UPDATE users SET email = lower(email)") : """
                        with no collisions the whole remedy is one statement, and the refusal has \
                        to say so — otherwise the reader goes looking for a decision to make \
                        about two accounts that do not exist. Actual:\s""" + abort;
                assert !abort.contains("FIND THE COLLISIONS") : """
                        THE HALF THAT MUST BE ABSENT. An operator handed a query by the refusal \
                        itself, who runs it and gets an empty result, reads that as "the tool is \
                        broken" rather than as "there is nothing here" — so the collision block \
                        is printed only when there ARE collisions. Actual:\s""" + abort;

                assert exists(conn, lone) : "the row must survive — nothing recreates an account";
                assert "Ivan@example.test".equals(emailOf(conn, lone)) : """
                        and it must survive UNFOLDED. The fold is offered to the operator as a \
                        remedy, in a message, and never taken by this file: it changes which \
                        mailbox can reset that account's password, which is the operator's \
                        decision and not a migration's.""";
                assert indexDefinition(conn, FOLDED_INDEX) == null
                        : "the transaction aborted, so the index must not exist";
                assertNothingRecorded(conn);

                // ---- the one-statement remedy the message named, then the re-run ----
                exec(conn, "UPDATE users SET email = lower(email) WHERE email <> lower(email)");
                assert migrationRefusal() == null : """
                        the remedy the refusal printed must be the one that actually clears it. \
                        If this fails, the message prescribes something that does not work — \
                        which is worse than printing no remedy at all.""";
                assert indexDefinition(conn, FOLDED_INDEX) != null
                        : "the re-run succeeded but built no index";
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ assertions

    /**
     * <strong>No {@code flyway_schema_history} row for V23 AT ALL</strong> — the claim the
     * migration header makes, and the one an operator's next five minutes depend on.
     *
     * <p>Asserted as an absence rather than as {@code success = false}, because a test written the
     * other way would be asserting a falsehood: PostgreSQL writes the history row inside the same
     * transaction the {@code RAISE EXCEPTION} aborts, so it rolls back with it. If a future Flyway
     * (or a future engine) starts recording the failure out of band, the re-run stops working
     * without a {@code repair} and the migration's own header becomes wrong — and this is where
     * that is noticed.
     */
    private void assertNothingRecorded(Connection conn) throws SQLException {
        assert rowsOf(conn, "SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history"
                            + " WHERE version = '23'") == 0 : """
                a schema-history row for V23 exists after a refusal. The migration's header \
                promises there is none — PostgreSQL writes that row INSIDE the migration's \
                transaction, so RAISE EXCEPTION takes it with it — and the operator-facing \
                consequence of that promise is the whole point: "re-run the upgrade once the \
                query returns no rows; nothing was recorded, so there is no repair step". With a \
                row here the next boot dies on "Detected failed migration to version 23", and the \
                remedy printed in the refusal is incomplete.""";
        assert rowsOf(conn, "SELECT coalesce(max(version::int), 0) FROM " + SCHEMA
                            + ".flyway_schema_history") == 22
                : "the history must still end at 22 — the database is unchanged, not half-migrated";
    }

    // ================================================================ vehicles

    /** Rebuilds the throwaway schema at exactly V22 — the moment before HD-167. */
    private void freshAt22(Connection conn) throws SQLException {
        dropSchema(conn);
        flyway("22").migrate();
        exec(conn, "SET search_path TO " + SCHEMA);
        assert indexDefinition(conn, FOLDED_INDEX) == null
                : "the fixture schema already carries " + FOLDED_INDEX + " — nothing to migrate";
        assert indexDefinition(conn, EXACT_INDEX) != null
                : "the pre-V23 schema must already carry " + EXACT_INDEX + ", or the "
                  + "'two indexes, two jobs' assertions below prove nothing";
    }

    /**
     * Runs {@code flyway migrate} up to V23 and returns the refusal's message, or {@code null} if
     * it succeeded. The whole cause chain is joined, because Flyway wraps PostgreSQL's message a
     * couple of levels deep and which level it lands on is not something this test should pin.
     */
    private String migrationRefusal() {
        try {
            flyway("23").migrate();
            return null;
        } catch (Exception e) {
            var text = new StringBuilder();
            for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
                text.append(t.getMessage()).append('\n');
            }
            return text.toString();
        }
    }

    /**
     * Executes the shipped V23 file on a statement of our own and returns the notices it raised.
     *
     * <p>The only reason this exists is that a {@code RAISE NOTICE} is delivered as a
     * {@link SQLWarning} on the statement that ran the script, and Flyway's connections belong to
     * Flyway. The bytes are the shipped ones — read from the classpath — so what this gives up
     * relative to the Flyway vehicle is the enclosing transaction, and both callers are cases that
     * succeed.
     */
    private List<String> runMigrationDirectly(Connection conn) throws SQLException, IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            assert in != null : "the shipped migration is not on the test classpath: " + MIGRATION;
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            var notices = new ArrayList<String>();
            for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
                notices.add(w.getMessage());
            }
            return notices;
        }
    }

    // ================================================================ fixture

    private UUID user(Connection conn, String email) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO users (id, email, display_name, status, system_role)
                VALUES ('%s', '%s', 'V23 fixture', 'ACTIVE', 'USER')
                """.formatted(id, email));
        return id;
    }

    private boolean exists(Connection conn, UUID id) throws SQLException {
        return rowsOf(conn, "SELECT count(*) FROM users WHERE id = '" + id + "'") == 1;
    }

    private String emailOf(Connection conn, UUID id) throws SQLException {
        return scalar(conn, "SELECT email FROM users WHERE id = '" + id + "'");
    }

    /** {@code null} when the index is absent — the assertion in both refusing cases. */
    private String indexDefinition(Connection conn, String index) throws SQLException {
        return scalar(conn, "SELECT indexdef FROM pg_indexes WHERE schemaname = '" + SCHEMA
                            + "' AND indexname = '" + index + "'");
    }

    /**
     * The exception an insert was refused with, or {@code null} if it succeeded.
     *
     * <p>The connection stays in autocommit, so each probe is its own transaction and a refusal
     * poisons nothing — the same arrangement, for the same reason, as
     * {@link V22InviteUniquenessMigrationTest}.
     */
    private SQLException refusalOf(SqlAction action) {
        try {
            action.run();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private boolean names(SQLException refusal, String index) {
        for (Throwable t = refusal; t != null && t != t.getCause(); t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains(index)) return true;
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

    /** {@code null} when there is no row — which is how an absent index is expressed. */
    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return null;
            var value = rs.getString(1);
            assert !rs.next() : "expected at most one row from: " + sql;
            return value;
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
