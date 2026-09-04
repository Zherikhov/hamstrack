package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <strong>No request may hold a share of the bulkhead indefinitely</strong> (HD-182 review) — the
 * watchdog, and the counter that stops it being a silent correction.
 *
 * <h2>The hole it closes, which the bound itself opened</h2>
 *
 * <p>A permit is taken in {@code preHandle}, i.e. before the request body is deserialised and long
 * before the response is written, so part of its life is paced by the CLIENT. Nothing in the
 * request pipeline bounds that by itself: {@code statement_timeout} bounds a statement, not a
 * socket. A client trickling a body — or reading a CSV a byte at a time — could therefore own a
 * permit for as long as it liked, and six of those are the whole default surface.
 *
 * <p>It is worth being precise about why that is a regression rather than a pre-existing hole. The
 * same six trickled requests used to cost six of Tomcat's 200 worker threads; the occupancy bound
 * re-denominated the surface from threads to permits, so the price of denying it fell by more than
 * an order of magnitude. A bound introduced so that one surface cannot take an instance down must
 * not become the cheapest way to take it down.
 *
 * <p>A plain unit test over the primitive rather than a request-level one: the property is about
 * permit bookkeeping, and driving it through MockMvc would need a client that stalls mid-body,
 * which is a test of Tomcat rather than of this.
 */
class PermitWatchdogTest {

    private final AtomicInteger forcedReleases = new AtomicInteger();
    private final ProductMetrics metrics = mock(ProductMetrics.class);

    /** A permit older than a millisecond is stale here; production uses statement_timeout + 60 s. */
    private final TestLimit limit = new TestLimit(metrics, 1, forcedReleases);

    @Test
    void aPermitHeldPastTheCeilingIsTakenBackAndCounted() throws Exception {
        var principal = UUID.randomUUID();
        var permit = limit.acquire(principal);

        assertThat(limit.inFlight()).isEqualTo(1);
        Thread.sleep(20);

        limit.sweepStalePermits();

        assertThat(limit.inFlight())
                .as("the watchdog must hand the surface permit back, or one slow client "
                    + "permanently owns a share of the bulkhead")
                .isZero();
        assertThat(limit.inFlightFor(principal))
                .as("and the per-principal permit with it — otherwise the caller is locked out of "
                    + "their own share while the surface has room")
                .isZero();
        assertThat(forcedReleases)
                .as("a force-release nobody counted is indistinguishable from nothing happening, "
                    + "and it is the metric that tells a LEAKED permit from a HELD one when the "
                    + "occupancy gauge is pinned")
                .hasValue(1);

        // The request's own release still runs afterwards, and must not hand back a second permit.
        limit.release(permit);
        assertThat(limit.inFlight())
                .as("release is idempotent, so a swept permit released again must not over-issue "
                    + "capacity — the failure mode in the other direction")
                .isZero();
    }

    @Test
    void aYoungPermitIsLeftAlone() {
        var principal = UUID.randomUUID();
        var patient = new TestLimit(metrics, 60_000, forcedReleases);

        patient.acquire(principal);
        patient.sweepStalePermits();

        assertThat(patient.inFlight())
                .as("the watchdog is a backstop against an unbounded hold, not a request deadline: "
                    + "sweeping a working request would refuse capacity the instance really is "
                    + "using")
                .isEqualTo(1);
        assertThat(forcedReleases).hasValue(0);
    }

    /**
     * <strong>A permit the REQUEST already gave back is not swept again, and not counted</strong>
     * (HD-182 review).
     *
     * <p>Nothing about capacity depends on this: {@code release} is idempotent either way. What
     * depends on it is the runbook. {@code hamstrack_expensive_read_permit_force_released_total} is
     * the only thing that distinguishes a pinned occupancy gauge caused by a LEAKED permit (flat
     * counter) from one caused by permits being HELD (climbing counter), and the WARN beside it
     * names a user id. An increment for a release this sweep did not perform sends an operator
     * hunting slow clients that do not exist, and does it while naming somebody.
     *
     * <p><strong>Read what this test can and cannot see.</strong> The release below RUNS TO
     * COMPLETION before the sweep starts, and a completed release removes the permit from the
     * outstanding set — so the sweep never iterates it at all. What is pinned here is therefore the
     * already-given-back case, not the interleave: this assertion holds identically against a sweep
     * that counts on its staleness check alone. The genuine race (the request winning the
     * {@code compareAndSet} <em>after</em> the sweep has iterated the permit and passed that check)
     * has no deterministic hook between the two statements and is simulated nowhere; the reachable
     * half of that seal — that {@code release} reports which caller actually performed the
     * hand-back, which is the boolean the sweep counts on — is asserted directly by
     * {@link #releaseReportsWhichCallerActuallyGaveThePermitBack()}.
     */
    @Test
    void aPermitTheREQUESTReleasedFirstIsNotCountedAsForced() throws Exception {
        var principal = UUID.randomUUID();
        var permit = limit.acquire(principal);
        Thread.sleep(20);

        limit.release(permit);
        limit.sweepStalePermits();

        assertThat(limit.inFlight())
                .as("the request gave its own permit back, so the surface is free either way")
                .isZero();
        assertThat(forcedReleases)
                .as("""
                    THE WATCHDOG COUNTED A FORCE-RELEASE FOR A PERMIT ALREADY HANDED BACK.

                    A permit whose release has COMPLETED is gone from the sweep's view, and one
                    still mid-release is caught by the released flag; either way this sweep
                    performed no hand-back and must count none. The counter is the SOLE
                    discriminator in the runbook between a leaked permit (gauge pinned, counter
                    flat -> restart the replica and file a bug) and permits being held by slow or
                    hostile clients (gauge pinned, counter climbing -> go and look at the clients),
                    and the WARN beside it names a user id, so a false increment sends an operator
                    hunting a client that did nothing. NOTE this test does not reach the CAS race;
                    that seal is releaseReportsWhichCallerActuallyGaveThePermitBack.""")
                .hasValue(0);
    }

    /**
     * <strong>{@code release} reports whether THIS caller is the one that gave the permit
     * back</strong> — the contract {@link PerPrincipalInFlightLimit#sweepStalePermits()} counts on,
     * asserted where it can actually be observed.
     *
     * <p>The sweep's rule is "count a forced release only when this sweep performed one", and the
     * only thing that knows which of two racing callers performed it is the {@code compareAndSet}
     * inside {@code release}. Its boolean is load-bearing, and nothing else in the suite looks at
     * it — {@code ExpensiveReadConcurrencyTest.releasingAPermitTwiceDoesNotWidenTheBulkhead}
     * discards both return values, because it is asking about capacity rather than about who won.
     * A {@code release} that returned {@code true} unconditionally would leave every other
     * assertion about this class green while turning the sweep's counter back into one that fires
     * for hand-backs it did not perform.
     *
     * <p>Both directions of the race are covered by their OUTCOME rather than by their timing: the
     * request winning (a repeat release reports {@code false}) and the sweep winning (the request's
     * later release reports {@code false}). What is deliberately not attempted is the interleave
     * itself, which would need a hook inside the sweep loop or a racing thread — and a flaky
     * concurrency test is a worse seal than a deterministic one over the primitive it turns on.
     */
    @Test
    void releaseReportsWhichCallerActuallyGaveThePermitBack() throws Exception {
        var principal = UUID.randomUUID();
        var permit = limit.acquire(principal);

        assertThat(limit.release(permit))
                .as("the first release is the one that performed the hand-back and must say so — "
                    + "the sweep counts a forced release ONLY on this boolean, so a release that "
                    + "cannot report defeat cannot report victory either")
                .isTrue();
        assertThat(limit.release(permit))
                .as("a second release performed nothing and must report false. This is the "
                    + "request-wins direction of the race: when afterCompletion has already given "
                    + "the permit back, the sweep's own release() IS this call, and a true here is "
                    + "a counted force-release and a WARN naming a user who did nothing wrong")
                .isFalse();
        assertThat(forcedReleases)
                .as("no sweep has run yet, so nothing can have been force-released")
                .hasValue(0);

        var swept = limit.acquire(principal);
        Thread.sleep(20);
        limit.sweepStalePermits();
        assertThat(forcedReleases)
                .as("the sweep took this permit back, which is the sweep-wins direction")
                .hasValue(1);
        assertThat(limit.release(swept))
                .as("the sweep already performed the hand-back, so the request's own release "
                    + "performed nothing and must report false — the same boolean read from the "
                    + "other side of the race, and what lets the interceptor release from every "
                    + "terminal callback without over-issuing capacity")
                .isFalse();

        assertThat(limit.release(null))
                .as("there was nothing to release, so nothing was performed — acquire() returns "
                    + "null when the bound is off or there is no principal, and the interceptor "
                    + "hands that straight back")
                .isFalse();
    }

    /** A limit with no live properties behind it — the numbers are the test's subject. */
    private static final class TestLimit extends PerPrincipalInFlightLimit {

        private final long maxAgeMs;
        private final AtomicInteger forcedReleases;

        TestLimit(ProductMetrics metrics, long maxAgeMs, AtomicInteger forcedReleases) {
            super(metrics);
            this.maxAgeMs = maxAgeMs;
            this.forcedReleases = forcedReleases;
            initialiseSurfacePermits();
        }

        @Override protected boolean enabled() { return true; }
        @Override protected int maxPerPrincipal() { return 2; }
        @Override protected int maxTotal() { return 2; }
        @Override protected long acquireWaitMs() { return 0; }
        @Override protected long maxPermitAgeMs() { return maxAgeMs; }
        @Override protected void countForcedRelease() { forcedReleases.incrementAndGet(); }
        @Override protected ProductMetrics.RateLimitKind perPrincipalKind() {
            return ProductMetrics.RateLimitKind.EXPENSIVE_READ_IN_FLIGHT;
        }
        @Override protected ProductMetrics.RateLimitKind surfaceKind() {
            return ProductMetrics.RateLimitKind.EXPENSIVE_READ_SURFACE_FULL;
        }
        @Override protected String callerNoun() { return "requests"; }
        @Override protected String surfaceNoun() { return "expensive requests"; }
    }
}
