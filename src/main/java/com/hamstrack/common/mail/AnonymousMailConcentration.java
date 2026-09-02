package com.hamstrack.common.mail;

import com.hamstrack.common.observability.ProductMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * <strong>The only witness to a sustained, targeted attack on the anonymous auth mailers</strong>
 * (HD-202 review) — it refreshes {@code hamstrack.mail.anonymous_recipient_max}.
 *
 * <h2>Why a gauge exists at all, when there is already a refusal counter</h2>
 * {@code hamstrack.ratelimit.hit} is emitted <em>only on refusal</em>. An optimal attacker produces
 * <strong>zero</strong> refusals: to keep a victim's bucket full they send exactly at the rate
 * slots age out, spaced past the cooldown, and every one of those requests is <em>allowed</em>. The
 * only refusals such an attack generates are the victim's own one or two attempts to reset their
 * password — a couple of hits against a threshold in the tens. So the refusal-based rule fires for
 * the noisy mail-bomb shape and never for the quiet denial-of-recovery shape, which is the one
 * {@code AuthMailProperties} names as the failure mode these ceilings exist to bound. A control
 * whose only evidence cannot see its own worst case has no evidence.
 *
 * <p>What is true whichever ceiling did or did not fire is that <em>one inbox is receiving an
 * abnormal share of a flow nobody authenticated asked for</em>. That is what this measures. It is
 * the same reasoning {@code rules.yml} already applies to the invitation mailer, where the two most
 * valuable rules are volume and acceptance ratio and the refusal rule is ranked last: a rule built
 * on refusals can only see an attacker who hits a ceiling.
 *
 * <h2>What it costs an attacker to stay under it — the arithmetic, because a shrug is not a bound</h2>
 * {@code maxAnonymousSendsToOneRecipient} groups by {@code recipient_key} with <strong>no
 * {@code email_type} filter</strong>, so it sums all three anonymous budgets at that inbox, and
 * {@code register}'s rows are anonymous like the rest. The threshold is 20 rows per key per six
 * hours ({@code MailRecipientConcentration}). Against that:
 *
 * <ul>
 *   <li>holding the <strong>registration</strong> bucket full (5 per hour) needs at least 5/h,
 *       i.e. <strong>30</strong> rows in six hours;</li>
 *   <li>holding the <strong>resend-verification</strong> bucket full — same width — needs the
 *       same <strong>30</strong>;</li>
 *   <li>holding the <strong>password-reset</strong> bucket full (5 per quarter-hour) needs 20/h,
 *       i.e. <strong>120</strong>.</li>
 * </ul>
 *
 * <p>So <strong>the minimum traffic that sustains a denial exceeds this rule's threshold in every
 * case</strong>, with 1.5× of margin on the loosest of the three and six times on the tightest.
 * That is the whole claim the "the gauge sees the attacker the meters cannot" argument rests on,
 * and it is arithmetic rather than optimism.
 *
 * <p><strong>What it does NOT catch, stated as the accepted cost:</strong> a hit-and-run. One
 * capful fired once is five rows — a quarter of the threshold — and it buys a full window of
 * lockout. Nothing here sees that, and nothing built on a six-hour aggregate could: it is
 * indistinguishable from a person with a typo and two devices. The refusal meters see the burst
 * shape; this sees the sustained shape; neither sees a single cheap capful, and that is the
 * residual {@code AuthMailProperties} states rather than hides.
 *
 * <h2>What it deliberately does NOT carry</h2>
 * The address. Not because it is unavailable — {@code mail_send_events.recipient_key} is right
 * there — but because a metric may not carry one, and a Grafana alert carries its series labels
 * into alert state and out through the email contact point. A {@code recipient_key} label would
 * therefore mail recipient addresses to the operator. The number answers "is somebody being
 * targeted"; {@code docs/self-hosting.md} carries the SQL that answers "who", run against the
 * database where an address legitimately lives.
 *
 * <h2>Scheduled rather than evaluated at scrape</h2>
 * The other gauges in {@code ProductMetrics} are single {@code count(*)}s and are computed when
 * Prometheus asks. This one is a {@code GROUP BY} over hours of rows, and a scrape — which happens
 * every 30 s, from every replica — is not the place for one. Five minutes is far finer than the
 * six-hour window it reports and than any alert built on it.
 *
 * <p><strong>Every replica runs it and they all publish the same number.</strong> The query is
 * instance-wide, not node-local, so the series are duplicates rather than shards: an alert takes
 * {@code max()} over them, never {@code sum()}. Read-only, idempotent, and bounded by the
 * anonymous retention sweep — so it needs none of the single-execution argument
 * {@link MailSendEventRetention} has to make for its DELETE.
 *
 * <h2>A last-write-wins gauge fails SILENT, so it publishes its own freshness</h2>
 * The backing value is an {@code AtomicLong}. If this query starts failing or timing out the gauge
 * does not go to zero and does not disappear — it <strong>freezes at its last value</strong>, most
 * likely a quiet one, and a frozen number is indistinguishable from a calm instance. The population
 * that makes the query slow is precisely the attack it exists to see, so the failure correlates
 * with the event. Three things answer that, and none of them is "assume it worked":
 *
 * <ul>
 *   <li>the exception is caught and logged at {@code WARN} rather than killing the scheduler
 *       thread — {@code @Scheduled} suppresses nothing, and a thrown task on a single-threaded
 *       scheduler is a stall the other jobs inherit;</li>
 *   <li>{@code hamstrack.mail.anonymous_recipient_max_age_seconds} carries how long ago the value
 *       was last <em>successfully</em> refreshed, so a rule can distinguish "quiet" from "stale";
 *       {@code MailConcentrationGaugeStale} in {@code rules.yml} is that rule;</li>
 *   <li>the statement is bounded by the {@code statement_timeout} that
 *       {@code BoundedJpaTransactionManager} applies to every transaction this application opens —
 *       nothing extra is needed here, but the bound is load-bearing rather than incidental: it is
 *       what turns a runaway scan into a caught exception and a rising age instead of a scheduler
 *       thread parked for minutes.</li>
 * </ul>
 *
 * <p>The rule's {@code noDataState}/{@code execErrState} were both {@code OK} for the same
 * reason every other rule in that file uses them — a missing series should not page an operator
 * who never deployed the app. For this rule that reasoning inverts, because absence and error are
 * the failure modes rather than the noise.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousMailConcentration {

    /**
     * The lookback. Six hours because that is the width at which a 5-per-window attacker becomes
     * unmistakable — at the shipped numbers an honest inbox tops out at a couple of requests in six
     * hours, while somebody holding a bucket full accumulates twenty to thirty. Well inside
     * {@code AuthMailProperties.ANONYMOUS_EVENT_RETENTION}, which is what makes the number
     * meaningful rather than truncated by the sweep.
     */
    static final Duration WINDOW = Duration.ofHours(6);

    private final MailSendEventRepository repository;
    private final ProductMetrics metrics;

    /**
     * Every five minutes, starting fifteen seconds after the context is ready.
     *
     * <p>The delay is short rather than absent on purpose, and the prose used to claim the opposite
     * of both halves. Spring's default {@code initialDelay} for a {@code fixedDelay} task is
     * <em>zero</em>, not the delay's own value, so leaving it out would have run this during
     * startup — one {@code GROUP BY} over hours of rows, on the connection pool, while every other
     * bean is still warming and Flyway may only just have finished. Fifteen seconds is late enough
     * to be out of that window and early enough that the gauge is real long before the first
     * {@code for: 15m} alert evaluation could care.
     *
     * <p>Five minutes between runs is far finer than the six-hour window it reports and than any
     * alert built on it.
     *
     * <p><strong>Not {@code @Transactional} itself</strong>, and that is what makes the
     * {@code catch} below work rather than merely look like it does. Hibernate marks a transaction
     * rollback-only when a statement fails, so catching the exception INSIDE a transactional method
     * only swaps it for an {@code UnexpectedRollbackException} thrown by the commit on the way out —
     * a handler that appears to swallow and does not. The read-only transaction and its
     * {@code statement_timeout} live on the repository method instead, one layer down, so the
     * failure is fully unwound before this frame sees it.
     */
    @Scheduled(initialDelay = 15_000, fixedDelay = 5 * 60 * 1000)
    public void refresh() {
        try {
            metrics.anonymousMailConcentration(
                    repository.maxAnonymousSendsToOneRecipient(Instant.now().minus(WINDOW)));
            metrics.anonymousMailConcentrationRefreshed();
        } catch (RuntimeException e) {
            // NOT rethrown, and the WARN is the whole of the handling. Rethrowing would leave the
            // gauge frozen at its last value exactly as this catch does — @Scheduled has nothing to
            // retry with — while additionally spending the single scheduler thread on a stack
            // trace the other seven @Scheduled classes wait behind. What actually makes the failure
            // visible is that the age gauge stops being reset, which is a monotonically rising
            // number a rule can see; this line is for the operator who then asks why.
            log.warn("anonymous mail concentration gauge not refreshed; "
                     + "hamstrack.mail.anonymous_recipient_max is now STALE, not low — it holds its "
                     + "last value and a frozen number reads exactly like a quiet instance. "
                     + "Watch hamstrack.mail.anonymous_recipient_max_age_seconds", e);
        }
    }
}
