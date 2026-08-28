package com.hamstrack.auth;

import com.hamstrack.auth.service.EmailUniqueness;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-167 — the {@code 23505} → 409 translation, forced rather than reviewed.</strong>
 *
 * <p>This file exists because of a rule the same commit added to {@code CLAUDE.md}: <em>a
 * translation {@code catch} that is never entered looks identical to one that works, so seal it
 * with a test that forces a real violation, not with a code review.</em> The rule is stated about
 * a category and this file honours it that way: <strong>every branch of the decision is exercised,
 * including the ones no reviewer could reach.</strong> The one that matters most is
 * {@code namesEmailUniqueConstraint} — it decides whether a non-English server answers 409 or 500,
 * and nothing in the ticket, at any round, had run it.
 *
 * <p><strong>Why the whole decision is synthesised here instead of being provoked from a
 * database.</strong> The real 23505 <em>is</em> forced against PostgreSQL, in
 * {@code EmailUniquenessEnforcementTest}, and that is where the wiring at each writer is proved.
 * What cannot be built against this project's database is the input the fallback exists for:
 * {@code lc_messages} on the container is {@code en_US.utf8} and the image ships no other message
 * catalogue — measured, {@code SET lc_messages = 'ru_RU.UTF-8'} is accepted and the very next
 * error still comes back in English. So a server whose dialect extraction returns {@code null}
 * cannot be produced, and a cause chain that spells one is the only honest vehicle.
 *
 * <p><strong>What the two foreign messages below are, stated exactly.</strong> They are localised
 * renderings of {@code duplicate key value violates unique constraint "%s"} written to have the
 * property under test, and that property is <em>not</em> their wording: it is that
 * <strong>the identifier is quoted verbatim while every surrounding word has changed</strong>, and
 * that neither contains the literal fragment {@code violates unique constraint "} Hibernate's
 * extractor scans for. Nothing here depends on them matching {@code pgsql}'s catalogue
 * word-for-word — which is fortunate, because this project's container cannot produce that
 * catalogue to check against. What would invalidate them is a server that <em>translated the
 * identifier too</em>, and PostgreSQL does not: it quotes identifiers verbatim in every locale,
 * which is the entire reason the name is the part worth matching.
 *
 * <p><strong>What each case protects, stated as the wrong answer it prevents:</strong>
 * <ul>
 *   <li>a duplicate answered <strong>500</strong> — the outcome this ticket exists to remove, and
 *       the one the fallback owns on any non-English server;</li>
 *   <li>an unrelated fault answered <strong>409 "Email is already registered"</strong> — worse
 *       than the 500, because it is a plausible refusal on the authentication path that nobody
 *       will investigate. Two different failures reach that door: a non-unique integrity violation
 *       whose name the dialect DID extract (Hibernate's PostgreSQL delegate routes 23502, 23503
 *       and 23514 through the <em>same</em> extractor), and a lock error or statement dump that
 *       merely <em>quotes</em> the failing statement, index name and all.</li>
 * </ul>
 *
 * <p>The second of those is the gap the HD-167 review found in HD-133's shipped shape and fixed in
 * both copies: the SQLSTATE test lived inside the <em>fallback</em>, so the primary branch matched
 * on a bare constraint name — a sentence about the class that was true of only half of it. Every
 * case below is therefore stated for <strong>both</strong> branches, because that is the shape of
 * the defect: one of two twins, silently.
 *
 * <p>No Spring context and no database — the subject is a pure decision over an exception chain,
 * so the test costs milliseconds and fails at the moment the branch changes.
 */
class EmailUniquenessTranslationTest {

    private static final String FOLDED = "users_email_lower_uk";
    private static final String EXACT = "users_email_key";

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String CHECK_VIOLATION = "23514";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /** What PostgreSQL says on this project's own container. */
    private static String english(String constraint) {
        return "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"";
    }

    /**
     * The same message as a German server renders it. The identifier is quoted with guillemets
     * rather than ASCII quotes and <em>every</em> surrounding word differs — which is exactly why
     * Hibernate's extractor (it scans for the literal English fragment
     * {@code violates unique constraint "}) hands back {@code null} here, and why the name is the
     * only part worth matching. Note that the guillemets defeat a fallback written to look for
     * {@code "name"} rather than for {@code name}.
     */
    private static String german(String constraint) {
        return "FEHLER: doppelter Schlüsselwert verletzt Unique-Constraint »" + constraint + "«";
    }

    /** And as a Russian server renders it, for the same reason — ASCII quotes, everything else new. */
    private static String russian(String constraint) {
        return "ОШИБКА: повторяющееся значение ключа нарушает ограничение уникальности \""
               + constraint + "\"";
    }

    // ================================================================ the duplicate

    /**
     * <strong>The ordinary path: the dialect found the name, and it is one of ours.</strong>
     *
     * <p>Both names are translated, and that is the point rather than an economy.
     * {@code users_email_key} (byte-exact) and {@code users_email_lower_uk} (folded) are two
     * spellings of one answer — "this address is taken" — and the caller must not be able to tell
     * which fired. A caller who could would learn whether the occupying row's spelling matches
     * theirs, which is a property of somebody else's account.
     */
    @Test
    void bothConstraintsOnTheAddressAreOneAnswer() {
        assertThat(EmailUniqueness.isDuplicateEmail(extracted(FOLDED, UNIQUE_VIOLATION)))
                .as("""
                        the constraint V23 adds. If this is false the ticket ships an index that \
                        turns a lost signup race into a 500 — which is the outcome the whole \
                        translation layer exists to remove.""")
                .isTrue();
        assertThat(EmailUniqueness.isDuplicateEmail(extracted(EXACT, UNIQUE_VIOLATION)))
                .as("""
                        and the one from V1, which 500s TODAY on a lost race. Leaving it out \
                        would let a caller tell the two apart from the STATUS CODE — 409 when the \
                        occupying row's spelling differs from theirs, 500 when it matches — which \
                        is a property of somebody else's account, disclosed by an error page.""")
                .isTrue();
    }

    /**
     * <strong>The name is compared case-insensitively, because it does not arrive from us.</strong>
     * PostgreSQL reports the identifier and Hibernate hands it through untouched; a quoted or
     * differently-cased identifier in some future migration must not silently stop being
     * translated.
     */
    @Test
    void theExtractedNameIsMatchedWithoutRegardToCase() {
        assertThat(EmailUniqueness.isDuplicateEmail(
                extracted(FOLDED.toUpperCase(java.util.Locale.ROOT), UNIQUE_VIOLATION))).isTrue();
    }

    // ================================================================ the fallback

    /**
     * <strong>THE BRANCH NOTHING HAD RUN</strong> — a well-formed {@code 23505} on a server whose
     * {@code lc_messages} is not English (AC 10).
     *
     * <p>Hibernate's {@code PostgreSQLDialect} finds the constraint name by matching the literal
     * English fragment {@code violates unique constraint "}. On a German or Russian server that
     * fragment is not in the message, so the extractor returns {@code null} for a perfectly
     * ordinary duplicate — and without this fallback that duplicate is a <strong>500</strong>,
     * deterministically, on every request, for the whole life of that deployment. Nothing about
     * the deployment looks unusual; it is one line in {@code postgresql.conf}.
     *
     * <p>Both realistic shapes of "the dialect found nothing" are covered, because they are
     * produced by different Hibernate versions and neither is ours to choose: a
     * {@link ConstraintViolationException} carrying a {@code null} name, and no
     * {@code ConstraintViolationException} in the chain at all.
     */
    @Test
    void aDuplicateOnANonEnglishServerIsStillTheSameAnswer() {
        assertThat(EmailUniqueness.isDuplicateEmail(unnamed(german(FOLDED), UNIQUE_VIOLATION)))
                .as("""
                        German lc_messages, folded index, dialect extraction null. PostgreSQL \
                        quotes the identifier VERBATIM in every locale — that is the entire \
                        reason the name is the part worth matching and the surrounding words are \
                        not — so the fallback finds it in a message where every other word has \
                        changed.""")
                .isTrue();
        assertThat(EmailUniqueness.isDuplicateEmail(unnamed(russian(EXACT), UNIQUE_VIOLATION)))
                .as("Russian lc_messages, byte-exact index — the pre-V23 constraint, which is the "
                    + "one a lost race hits today")
                .isTrue();
        assertThat(EmailUniqueness.isDuplicateEmail(bare(german(FOLDED), UNIQUE_VIOLATION)))
                .as("""
                        the same failure with no ConstraintViolationException in the chain at \
                        all. Which of the two shapes Hibernate produces is a function of its \
                        version and of the delegate that ran, and neither is ours to choose — so \
                        a fallback that only handled one of them would be a version-dependent \
                        500.""")
                .isTrue();
    }

    // ================================================================ what must NOT be a 409

    /**
     * <strong>The name alone is not sufficient, and the primary branch is where that was
     * wrong.</strong>
     *
     * <p>Hibernate's PostgreSQL delegate routes {@code 23502}, {@code 23503} and {@code 23514}
     * through the <em>same</em> constraint-name extractor, so a not-null, foreign-key or check
     * violation that happened to bear one of our two names reaches the primary branch. Answering
     * it "Email is already registered" is worse than the 500 it replaces: it is a plausible
     * refusal, on the authentication path, that nobody will investigate — the shape that makes an
     * incident hard to diagnose.
     *
     * <p>This is the gap the HD-167 review found: {@code isDuplicateEmail}'s own javadoc claimed
     * the SQLSTATE was required of every branch while the code asked it only inside the fallback.
     * A claim about a category, true of one member.
     */
    @Test
    void anIntegrityViolationThatIsNotAUniqueOneKeepsIts500EvenBearingOurName() {
        for (var state : new String[]{FOREIGN_KEY_VIOLATION, NOT_NULL_VIOLATION, CHECK_VIOLATION}) {
            assertThat(EmailUniqueness.isDuplicateEmail(extracted(FOLDED, state)))
                    .as("""
                            SQLSTATE %s bearing the name %s must NOT be translated. The name \
                            alone is not sufficient in the primary branch either: users carries \
                            other constraints and several foreign keys, and Hibernate routes all \
                            of these through the same extractor. A genuine fault answered "Email \
                            is already registered" is a plausible refusal nobody investigates.""",
                            state, FOLDED)
                    .isFalse();
        }
    }

    /**
     * <strong>And the same halving in the other branch: a message that merely QUOTES the
     * index.</strong>
     *
     * <p>A lock timeout, a statement cancellation or any error that dumps the failing statement
     * mentions the index name too. Answering those a 409 tells a caller "this address is taken"
     * when nobody took it — and on the registration path that is a live account-existence oracle
     * built out of an unrelated database hiccup.
     */
    @Test
    void aLockErrorThatQuotesTheIndexIsNotADuplicate() {
        var quoting = "ERROR: canceling statement due to lock timeout\n"
                      + "  while running: INSERT INTO users ... ON CONFLICT ON CONSTRAINT "
                      + FOLDED;
        assertThat(EmailUniqueness.isDuplicateEmail(bare(quoting, LOCK_NOT_AVAILABLE))).isFalse();
        assertThat(EmailUniqueness.isDuplicateEmail(unnamed(quoting, LOCK_NOT_AVAILABLE))).isFalse();
    }

    /**
     * <strong>A real duplicate, on a constraint that is not ours.</strong> {@code users} is not
     * the only table an INSERT on this path can touch, and neither writer may answer "Email is
     * already registered" for a collision on a primary key or on another table's index.
     */
    @Test
    void aUniqueViolationOnSomebodyElsesConstraintIsNotOurs() {
        assertThat(EmailUniqueness.isDuplicateEmail(extracted("users_pkey", UNIQUE_VIOLATION)))
                .isFalse();
        assertThat(EmailUniqueness.isDuplicateEmail(
                unnamed(german("workspace_invites_pending_email_uk"), UNIQUE_VIOLATION)))
                .as("V22's index, reached through the fallback. The two translations are twins "
                    + "and each must decline the other's constraint")
                .isFalse();
    }

    /**
     * <strong>Neither half on its own is an answer</strong> — stated as the two degenerate inputs,
     * so that deleting either test above cannot be compensated for by the other.
     */
    @Test
    void neitherHalfAloneQualifies() {
        assertThat(EmailUniqueness.isDuplicateEmail(
                new DataIntegrityViolationException(english(FOLDED))))
                .as("a chain that names the index but carries no SQLException at all — there is "
                    + "no SQLSTATE to prove it was a uniqueness violation, so it is not one")
                .isFalse();
        assertThat(EmailUniqueness.isDuplicateEmail(bare("ERROR: duplicate key value", UNIQUE_VIOLATION)))
                .as("and a 23505 that names nothing. On the signup INSERT that could be any "
                    + "constraint on the table; guessing is how a genuine fault gets a plausible "
                    + "sentence")
                .isFalse();
    }

    /**
     * <strong>A cause CYCLE must not hang the thread that is already handling a failure —
     * and TODAY IT DOES.</strong> Measured 2026-08-28: this input wedged Surefire until the
     * JVM was killed by hand.
     *
     * <p><strong>The claim, and why it is not true of the class that makes it.</strong> Two of
     * {@code EmailUniqueness}'s three cause-chain walks are depth-bounded by
     * {@code MAX_CAUSE_DEPTH}, and {@code namesEmailUniqueConstraint} explains why in the class's
     * own words: <em>"depth-bounded rather than merely self-reference-guarded … a two-step cause
     * cycle would otherwise spin forever on a thread that is already handling a failure."</em> The
     * third — {@code constraintNameOf} — is guarded only by {@code t != t.getCause()}, which stops
     * a one-step self-reference and nothing else. <strong>And it is the one that runs
     * first</strong>, on every single call, before either bounded walk is reached. So the two
     * bounds buy nothing against the input they were written for: the class spins before it gets
     * to them. A claim phrased about one member, where the property that matters belongs to the
     * class. ({@code WorkspaceService.constraintNameOf} is the same code with the same hole —
     * fixing one and leaving the other is how an idiom becomes two idioms, which is the sentence
     * the HD-167 review already had to write once about these twins.)
     *
     * <p><strong>The vehicle is a daemon thread rather than {@code @Timeout}, and that is the
     * lesson this case cost.</strong> JUnit's {@code @Timeout} fails the test and then
     * <em>interrupts</em> the thread; a loop that never checks the interrupt flag keeps running,
     * the non-daemon Surefire worker cannot exit, and the whole build hangs — a red test that
     * takes the suite with it teaches nobody anything. On a daemon thread the spin is contained:
     * this case reds in ten seconds with the reason attached, the JVM can still exit, and the
     * moment {@code constraintNameOf} is bounded the probe returns in microseconds and the thread
     * dies.
     */
    @Test
    void aTwoStepCauseCycleTerminates() throws Exception {
        var first = new SQLException("first", UNIQUE_VIOLATION);
        var second = new SQLException("second", UNIQUE_VIOLATION, first);
        first.initCause(second);
        var wrapped = new DataIntegrityViolationException("wrapped", first);

        var returned = new java.util.concurrent.CountDownLatch(1);
        var probe = new Thread(() -> {
            try {
                EmailUniqueness.isDuplicateEmail(wrapped);
            } catch (Throwable ignored) {
                // The ANSWER is incidental — a cycle carries no honest one. Returning is the
                // assertion.
            } finally {
                returned.countDown();
            }
        }, "email-uniqueness-cycle-probe");
        probe.setDaemon(true);
        probe.start();

        assertThat(returned.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("""
                        isDuplicateEmail did not return on a two-step cause cycle \
                        (A -> B -> A). constraintNameOf walks with `t != t.getCause()` only, \
                        which catches a one-step self-reference and nothing else, and it runs \
                        BEFORE the two walks that are bounded by MAX_CAUSE_DEPTH — so their \
                        bounds are unreachable for this input and the class spins on a thread \
                        that is already handling a failure. Give constraintNameOf the same \
                        depth-bounded loop its two neighbours have, in EmailUniqueness AND in \
                        WorkspaceService, which carries a byte-identical copy.""")
                .isTrue();
    }

    // ================================================================ chain shapes

    /**
     * The shape Hibernate produces when its dialect DID extract a name: a
     * {@link ConstraintViolationException} carrying it, over the driver's {@link SQLException}.
     */
    private static DataIntegrityViolationException extracted(String constraint, String sqlState) {
        var root = new SQLException(english(constraint), sqlState);
        return new DataIntegrityViolationException(root.getMessage(),
                new ConstraintViolationException(root.getMessage(), root, constraint));
    }

    /**
     * The shape on a server whose {@code lc_messages} is not English: the same wrapper, with the
     * name it could not find left {@code null}.
     */
    private static DataIntegrityViolationException unnamed(String message, String sqlState) {
        var root = new SQLException(message, sqlState);
        return new DataIntegrityViolationException(message,
                new ConstraintViolationException(message, root, null));
    }

    /** And the shape with no {@link ConstraintViolationException} in the chain at all. */
    private static DataIntegrityViolationException bare(String message, String sqlState) {
        return new DataIntegrityViolationException(message, new SQLException(message, sqlState));
    }
}
