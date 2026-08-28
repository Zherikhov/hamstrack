package com.hamstrack.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * <strong>Turns a {@code users} email uniqueness violation into the 409 the product already
 * has</strong> (HD-167), for the two paths that insert an account:
 * {@link AuthService#register} and {@code AdminUserService.create}.
 *
 * <p><strong>Why this has to exist at all.</strong> A {@code 23505} that reaches
 * {@code GlobalExceptionHandler.handleDataIntegrityViolation} is answered <strong>500</strong>,
 * deliberately and in writing — a unique violation with no sentence written for it is a genuine
 * fault, and inventing one there would be worse. V23 adds a <em>second</em> constraint on
 * {@code users} that can fire on the signup INSERT, so every way of reaching it needs a sentence.
 *
 * <p><strong>A mixed-case squatter is not one of those ways, and naming the way that IS is the
 * point of this paragraph.</strong> {@code existsByFoldedEmail} asks
 * {@code lower(stored) = lower(:typed)}; the index enforces uniqueness of {@code lower(stored)};
 * the value inserted is {@code :typed}. Both sides go through the <em>same</em> PostgreSQL
 * {@code lower()}, so they cannot disagree — for any input, under any {@code LC_CTYPE}, on any
 * collation provider. Java's {@code Locale.ROOT} fold is on neither side of that comparison. A
 * squatter is therefore refused by the <strong>pre-check</strong>, with a 409 and no INSERT
 * attempted (measured 2026-08-28: {@code Bob@x.com} inserted by direct SQL, {@code bob@x.com}
 * registered — 409, and the log shows the SELECT and nothing after it). <strong>The pre-check and
 * the index ask the same question of the same function, so they can differ only by the window
 * between them — and a window is a race, not a fold.</strong>
 *
 * <p>So the reachable trigger is two concurrent registrations of one address, which 500s
 * <em>today</em> on {@code users_email_key}; this class is what turns it into the 409 the product
 * already has, on either name. (Also measured, with a {@code pg_sleep} BEFORE INSERT trigger and
 * two concurrent registrations: real 23505s on both constraint names, both answered 409, bodies
 * byte-identical to the pre-check's.) <strong>The constraint still could not ship without this
 * translation</strong> — not because a squatter needs it, but because a second unique index on the
 * same INSERT is a second name that race can fail under, and it was already reaching
 * {@code GlobalExceptionHandler} under the first.
 *
 * <p><strong>Both names are translated, and that is the point rather than an economy.</strong>
 * {@code users_email_key} (byte-exact) and {@code users_email_lower_uk} (folded) are two
 * spellings of one answer — "this address is taken" — and the caller must not be able to tell
 * which fired. A caller who could would learn whether the occupying row's spelling matches
 * theirs, which is a property of somebody else's account.
 *
 * <p><strong>The two hard-won properties of HD-133's shape.</strong> The first is stated here as
 * that ticket stated it; it was not, until the HD-167 review, what either class <em>did</em>.
 * <ul>
 *   <li><strong>The name AND SQLSTATE {@code 23505} are both required — of every branch.</strong>
 *       Neither alone is sufficient: {@code users} carries other constraints and several foreign
 *       keys, so a bare SQLSTATE match would answer 409 to an unrelated fault; and a lock error or
 *       statement cancellation that merely <em>quotes the failing statement</em> would mention the
 *       index name too, so a bare name match would tell a caller "this address is taken" when
 *       nobody took it. The SQLSTATE half sat inside the <em>fallback</em> in HD-133's shipped
 *       shape, so the primary branch matched on the name alone — a sentence about the class that
 *       was true of only half of it. It is now {@link #isUniqueViolation}, asked first, of both.
 *       ({@code WorkspaceService.isDuplicateInvite} is the same code and was corrected in the same
 *       pass: fixing one and leaving the other is how an idiom becomes two idioms.)</li>
 *   <li><strong>Do not depend on Hibernate's dialect having found the name.</strong>
 *       {@code PostgreSQLDialect}'s extractor matches the literal English fragment
 *       {@code violates unique constraint "}, so on a server whose {@code lc_messages} is
 *       anything else it returns {@code null} for a perfectly well-formed {@code 23505}. The
 *       fallback matches the constraint's own <em>name</em> against the cause-chain messages,
 *       because PostgreSQL quotes an identifier verbatim in every locale — which is exactly why
 *       the name is the part worth matching and the surrounding words are not.</li>
 * </ul>
 *
 * <p>Do <strong>not</strong> add an {@code instanceof DuplicateKeyException} branch: under JPA
 * that branch is unreachable, because the persistence-exception translator produces the wider
 * {@link DataIntegrityViolationException} for a constraint violation raised by Hibernate.
 *
 * <p><strong>The caller must flush.</strong> A bare {@code save()} inside a transaction defers
 * the INSERT to commit, where the violation is raised <em>after</em> the service method has
 * returned and no {@code catch} of ours can see it. Both writers use {@code saveAndFlush} for
 * that reason, and a future writer of this table owes the same.
 *
 * <p><strong>Not applied to the seeder's admin write, and the reason is a mechanism rather
 * than a population.</strong> (That write is inline in {@code DataSeeder.run(ApplicationArguments)};
 * there is no {@code seedAdmin} method to grep for.) {@code DataSeeder.run} is not
 * {@code @Transactional}, so
 * {@code SimpleJpaRepository.save} opens and commits its own transaction: a 23505 surfaces from
 * the {@code save()} call itself, out of the {@code ApplicationRunner}, and the boot fails loudly
 * — which is the outcome a 409 there would have to be argued against, and it is the better one at
 * boot. Adding {@code @Transactional} to {@code run} later moves where that exception lands, so it
 * is a change that owes this paragraph a re-read. The seeder's find-or-create folds to <em>find</em>
 * and then compares exactly before it <em>grants</em>, for reasons that are its own.
 */
@Slf4j
public final class EmailUniqueness {

    /**
     * <strong>The names of the two constraints on {@code users.email}</strong> —
     * {@code users_email_key} from {@code V1__init_schema.sql} and
     * {@code users_email_lower_uk} from {@code V23__users_email_uniqueness.sql}.
     *
     * <p>Not derived from anything: PostgreSQL reports the constraint name and Hibernate hands it
     * through, so a rename in a future migration must be mirrored here — at which point the
     * duplicate insert stops being translated and starts 500-ing, which is loud rather than
     * silent.
     */
    private static final Set<String> EMAIL_UNIQUE_CONSTRAINTS =
            Set.of("users_email_key", "users_email_lower_uk");

    /** PostgreSQL {@code unique_violation}. Not localised, unlike the message that carries it. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** Generous enough that no real chain reaches it, small enough that a cycle cannot hang. */
    private static final int MAX_CAUSE_DEPTH = 20;

    private EmailUniqueness() {
    }

    /**
     * Does this integrity violation mean <em>the address is already registered</em>?
     *
     * <p>Anything else is a genuine fault and must keep its 500 rather than masquerade as a
     * plausible conflict — the shape that makes an incident hard to diagnose. Only the constraint
     * <em>name</em> is logged: no SQL, no exception message, no user input. That is a claim about
     * these two log lines and not about the request — Hibernate's {@code SqlExceptionHelper} logs
     * the {@code PSQLException} at ERROR before this method is even called, which is why
     * {@code logServerErrorDetail=false} is set on the datasource. Without it PostgreSQL's DETAIL
     * puts a full address in the log:
     * {@code Key (lower(email::text))=(victim@example.com) already exists}.
     */
    public static boolean isDuplicateEmail(DataIntegrityViolationException e) {
        String constraint = constraintNameOf(e);
        // SQLSTATE FIRST, AND FOR BOTH BRANCHES. The name alone is not sufficient in either — see
        // isUniqueViolation. (The primary branch did not ask for it until the HD-167 review; the
        // class javadoc said it did, which is how a gap of this shape survives.)
        boolean duplicate = isUniqueViolation(e)
                && (constraint != null
                        ? EMAIL_UNIQUE_CONSTRAINTS.contains(constraint.toLowerCase(Locale.ROOT))
                        : namesEmailUniqueConstraint(e));
        if (duplicate) {
            log.debug("Account insert lost the address race on constraint [{}]",
                    constraint != null ? constraint : "users_email_*");
        } else {
            log.warn("Account insert failed on an unexpected constraint [{}] — rethrowing",
                    constraint != null ? constraint : "unknown");
        }
        return duplicate;
    }

    /**
     * <strong>Depth-bounded, like every other cause-chain walk here — and this is the walk that
     * makes the others' bounds reachable</strong>, because it runs first on every call. Why a
     * {@code t != t.getCause()} guard is not enough: see {@link #namesEmailUniqueConstraint}.
     * Same shape as {@code GlobalExceptionHandler.sqlStateOf}, deliberately — one idiom.
     */
    private static String constraintNameOf(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof ConstraintViolationException cve) return cve.getConstraintName();
        }
        return null;
    }

    /**
     * The locale-proof fallback: does this failure name one of <em>our</em> two email constraints?
     *
     * <p>Reached only when Hibernate's dialect did not extract a name — its PostgreSQL extractor
     * finds one by matching the literal English fragment {@code violates unique constraint "}, so a
     * server whose {@code lc_messages} is anything else returns {@code null} for a perfectly
     * well-formed 23505. PostgreSQL quotes an identifier verbatim in every locale, which is exactly
     * why the name is the part worth matching and the surrounding words are not.
     *
     * <p><strong>The SQLSTATE half is absent here because it is no longer this branch's half.</strong>
     * It is asked once, of both branches, in {@link #isUniqueViolation} — which is the correction
     * this method used to embody and the primary branch used to skip.
     *
     * <p><strong>Every cause-chain walk in this class is depth-bounded</strong> — this one,
     * {@link #isUniqueViolation} and {@link #constraintNameOf} alike — for the reason
     * {@code GlobalExceptionHandler.sqlStateOf} gives and in the same shape: a {@code t !=
     * t.getCause()} guard catches a one-step self-reference and nothing else, so a two-step cause
     * cycle (A → B → A) would spin forever on a thread that is already handling a failure.
     *
     * <p><strong>A bound is worth only what the FIRST walk is worth.</strong> This paragraph used
     * to be written about this method, and was true of it — while {@code constraintNameOf}, which
     * runs first on every single call, was merely self-reference-guarded. The two bounds here and
     * in {@link #isUniqueViolation} were therefore unreachable for the one input they were written
     * for: the class spun before it reached them, and this sentence described a protection the
     * code did not have (measured 2026-08-28 — the cycle wedged Surefire until the JVM was killed
     * by hand). Stated about the class rather than about a member, because that is the property
     * that has to hold: a new walk added here inherits the obligation, and a bound skipped on a
     * walk that runs earlier silently disarms every bound after it. Sealed by
     * {@code EmailUniquenessTranslationTest.aTwoStepCauseCycleTerminates}, whose twin lives in
     * {@code WorkspaceService} — fixed in the same pass, because fixing one and leaving the other
     * is how an idiom becomes two idioms.
     */
    private static boolean namesEmailUniqueConstraint(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            String message = t.getMessage();
            if (message != null && EMAIL_UNIQUE_CONSTRAINTS.stream().anyMatch(message::contains)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <strong>Is this a uniqueness violation at all?</strong> — asked of <em>both</em> branches
     * above, which is the whole point of it being a method.
     *
     * <p>The name alone is not sufficient in either branch, and the two branches fail differently
     * on it. Hibernate's PostgreSQL delegate routes {@code 23502}, {@code 23503} and {@code 23514}
     * through the <em>same</em> constraint-name extractor, so a not-null, foreign-key or check
     * violation that happened to bear one of our two names would reach the primary branch and be
     * answered "Email is already registered" — a misleading refusal, on the authentication path.
     * The fallback matches a name against free text, where a lock error or a statement dump that
     * merely <em>quotes</em> the failing statement mentions the name too.
     *
     * <p>Nothing on {@code users} carries either name in any other role today. This gate is what
     * keeps that a fact about the schema rather than a dependency of this class on it.
     */
    private static boolean isUniqueViolation(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof SQLException se && SQLSTATE_UNIQUE_VIOLATION.equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
