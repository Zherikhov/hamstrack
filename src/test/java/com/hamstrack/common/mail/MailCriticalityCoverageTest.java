package com.hamstrack.common.mail;

import com.hamstrack.common.observability.ProductMetrics.EmailType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Every {@link EmailType} is account-critical or best-effort, and it says which.</strong>
 *
 * <h2>The failure this file exists for</h2>
 * {@link MailService#isCritical} is a fork whose own comment says a new {@code EmailType} must land
 * on one side or the other exactly once — and a boolean fork <em>cannot enforce that</em>, because
 * its else-branch absorbs every constant anybody adds without needing an edit. So the rule was a
 * sentence, and the sentence was already broken when it was written down:
 * {@link EmailType#REGISTRATION_VERIFICATION} shipped on best-effort <em>by omission</em>.
 *
 * <p>That was not a live bug — no mailer dispatches that type, it is a throttle-budget tag and the
 * mail {@code POST /api/auth/register} actually sends is dispatched as {@link EmailType#VERIFICATION}
 * — which is exactly what makes it worth a seal rather than a fix. An omission on this fork is
 * invisible until the day a mailer emits the type, and on that day the mail becomes log-only, with
 * no retry and no {@code failed_email} dead-letter row, silently. The two consumers
 * ({@link MailService} for a send that failed, {@link UndeliverableMail} for a send that never
 * happened) share the one predicate on purpose, so an omission is wrong in both places at once.
 *
 * <h2>How the seal works</h2>
 * The two sets below are the <em>declaration</em>; {@code isCritical} is the implementation. A new
 * constant is in neither set, so {@link #everyEmailTypeIsPlacedOnExactlyOneSide()} fails at the
 * commit that adds it and the placement becomes a deliberate, reviewable edit. The sets are then
 * checked <em>against</em> {@code isCritical}, so they cannot drift into being a wish-list: a
 * constant declared critical here and forgotten there fails too.
 *
 * <p>Plain JUnit, no Spring context: the question is about an enum and a static predicate, and a
 * seal that costs a context start is a seal somebody eventually moves.
 */
class MailCriticalityCoverageTest {

    /**
     * Mail whose loss leaves a person unable to complete signup or recover their account. It
     * retries {@code app.mail.critical.max-attempts} times and then writes a {@code failed_email}
     * row — the durable record, because there is no caller left to return an error to (every
     * mailer is dispatched from an {@code AfterCommit} effect).
     *
     * <p>{@link EmailType#REGISTRATION_VERIFICATION} is here rather than below even though nothing
     * emits it yet. The budget is spent for a verification link, and the fail-safe direction on
     * this fork is durability: a type placed critical and never sent costs nothing, while a type
     * placed best-effort and later sent loses mail with only a WARN to say so.
     */
    private static final Set<EmailType> CRITICAL = EnumSet.of(
            EmailType.VERIFICATION,
            EmailType.REGISTRATION_VERIFICATION,
            EmailType.PASSWORD_RESET);

    /**
     * Mail that is dropped with a log line and no row. {@link EmailType#INVITE} is best-effort by
     * design — the loss reaches an ERROR naming the workspace an operator would have to open, and
     * the invitation can be re-sent by the admin who made it, which is not true of a verification
     * or reset link nobody else can reissue.
     */
    private static final Set<EmailType> BEST_EFFORT = EnumSet.of(EmailType.INVITE);

    private static final String CHECKLIST = """

            A NEW EmailType MUST BE PLACED, NOT DEFAULTED. MailService.isCritical is a boolean \
            fork, so a constant nobody names falls through to BEST-EFFORT: log-only, no retry, no \
            failed_email dead-letter row, and no signal that the decision was ever made. That is \
            the shape REGISTRATION_VERIFICATION shipped in, under a comment forbidding exactly it.

              CRITICAL if losing the message leaves somebody unable to finish signing up or to \
              recover their account — i.e. if nobody else can reissue it. Add the constant to \
              MailService.isCritical AND to CRITICAL above.

              BEST-EFFORT if a human can simply do it again. Add it to BEST_EFFORT above and leave \
              isCritical alone.

            Both consumers read the one predicate — MailService (a send that failed) and \
            UndeliverableMail (a send that never happened) — so the placement is one decision, and \
            a second copy of this fork would let a type be critical on one path and best-effort on \
            the other.""";

    /**
     * The seal. Polarity is placement-by-default-fails: a constant in neither set is the failure,
     * so forgetting is not an option.
     */
    @Test
    void everyEmailTypeIsPlacedOnExactlyOneSide() {
        var unplaced = EnumSet.allOf(EmailType.class);
        unplaced.removeAll(CRITICAL);
        unplaced.removeAll(BEST_EFFORT);

        assertThat(unplaced)
                .as("these mail types are on neither side of MailService.isCritical's fork, so "
                    + "their durability is whatever the else-branch happens to give them."
                    + CHECKLIST)
                .isEmpty();

        var both = EnumSet.copyOf(CRITICAL);
        both.retainAll(BEST_EFFORT);
        assertThat(both)
                .as("these mail types are declared on BOTH sides. The fork has to be a partition — "
                    + "'exactly once' is what stops a type being retried after a failed send and "
                    + "dropped after a refused dispatch." + CHECKLIST)
                .isEmpty();
    }

    /**
     * <strong>The declaration above is checked against the implementation, in both directions.</strong>
     * Without this the sets would be a comment with an assertion around it: they would keep passing
     * while {@code isCritical} said something else entirely.
     */
    @Test
    void theDeclaredSidesAreTheOnesIsCriticalActuallyTakes() {
        for (var type : CRITICAL) {
            assertThat(MailService.isCritical(type))
                    .as("%s is declared account-critical here but MailService.isCritical returns "
                        + "false for it, so a lost message of this kind is dropped with a WARN "
                        + "instead of retried and dead-lettered.%s", type, CHECKLIST)
                    .isTrue();
        }
        for (var type : BEST_EFFORT) {
            assertThat(MailService.isCritical(type))
                    .as("%s is declared best-effort here but MailService.isCritical returns true "
                        + "for it, so every failure of this kind now writes a failed_email row and "
                        + "an ERROR the EmailFailures alert fires on.%s", type, CHECKLIST)
                    .isFalse();
        }
    }
}
