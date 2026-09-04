package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <strong>How many requests of one kind may be running AT ONCE</strong> — per principal, and
 * across the whole surface (HD-182). The occupancy sibling of {@link PerPrincipalMinuteBudget},
 * which bounds how <em>often</em> a caller may ask and says nothing about how many they may have
 * running.
 *
 * <p>Two counters, one primitive, one pool. A request acquires (a) one of
 * {@link #maxPerPrincipal()} permits held for its own principal and (b) one of {@link #maxTotal()}
 * permits held for the whole surface. The second counter is the bulkhead — <strong>a counted share
 * of the one Hikari pool, not a second physical pool</strong> (ADR-0030) — and it is what
 * guarantees the rest of the API always retains {@code poolSize − maxTotal} connections no matter
 * how many principals ask. The first is what stops one principal being the whole cause.
 *
 * <p><strong>Shared rather than copied</strong>, for {@link PerPrincipalMinuteBudget}'s reason: a
 * throttle whose counting, release, refusal shape and {@code Retry-After} arithmetic exist twice is
 * a throttle where a fix lands in one of them. What differs between two occupancy bounds is a set
 * of numbers, two metric tags and two nouns, so those are the abstract methods and nothing else is.
 *
 * <p><strong>The primitive is general; the BINDING is not.</strong> HD-251 (upload concurrency) is
 * expected to reuse this class with its own properties, its own {@link RateLimitKind}s, its own
 * denomination and its own seal — and must <em>not</em> be folded into the expensive-read
 * registration. The scarce resource there is a parsed multipart, a Tomcat worker and an S3 socket
 * held outside any transaction, i.e. the one resource an upload was engineered <em>not</em> to
 * hold; the binding site is inside {@code AttachmentService.upload}, because multipart resolution
 * is eager and by the time an interceptor runs the bytes are already parsed; and the seal is
 * {@code AttachmentDoorsTest}'s axis of {@code FileStorage.store} call sites, not
 * {@code ThrottleCoverageTest}'s axis of paths. Coupling the primitive is reuse; coupling the
 * binding would hand it the wrong resource.
 *
 * <h2>The release obligation — the one way this can be worse than the problem it fixes</h2>
 *
 * <p><strong>A leaked permit is a permanent, silent capacity loss on that replica</strong>: the
 * surface degrades to refusing everything until a restart, and the gauge is the only thing that
 * would say so. That is why {@link #release(Permit)} is idempotent, why the caller
 * ({@link PrincipalThrottleInterceptor}) releases from every terminal callback rather than from
 * one, and why the bound stays ON in the test suite — a leak then breaks many tests loudly instead
 * of degrading production quietly.
 *
 * <h2>Rules, each of which is a failure mode if inverted</h2>
 * <ol>
 *   <li><strong>Per-principal first, then the surface permit; on failure to get the second,
 *       release the first.</strong> Fixed order, and no thread ever waits for a per-principal
 *       permit while holding a surface permit, so there is no cycle and no deadlock.</li>
 *   <li><strong>A bounded wait, then refuse</strong> — {@link #acquireWaitMs()} across the whole
 *       acquisition, not per half. A waiting thread holds no connection and no heap.</li>
 *   <li><strong>{@code enabled() == false} or {@code principal == null} → no permit, no
 *       counting.</strong> Anonymous requests cannot reach these paths ({@code /api/**} is
 *       {@code authenticated()}), and treating a null principal as a shared key would let one
 *       request exhaust everyone — {@link PerPrincipalMinuteBudget}'s rule, unchanged.</li>
 *   <li><strong>The counters cost no connection and no statement.</strong> A limiter that needed
 *       the resource it protects in order to decide would be self-defeating. Asserted by
 *       {@code ExpensiveReadConcurrencyTest}, in the {@code PermissionResolutionQueryCountTest}
 *       shape.</li>
 *   <li><strong>The per-principal map drops an entry when its last user leaves</strong>, so it is
 *       bounded by <em>concurrent</em> principals rather than by principals ever seen — no
 *       eviction sweep and no scheduled job to starve. Note this is the opposite of the
 *       minute-window map, which needs one.</li>
 *   <li><strong>No permit is held indefinitely, whatever the reason</strong> —
 *       {@link #sweepStalePermits()} takes back one older than {@link #maxPermitAgeMs()} and
 *       counts it. Without it the ceiling bounds only how many requests may be in flight, not for
 *       how long, and the half of a request's life that belongs to the CLIENT (the body read, the
 *       response write) is paced by that client: a servlet container bounds the GAP between two
 *       reads, never their number, so a socket kept barely alive is a hold with no end in it.</li>
 * </ol>
 *
 * <p><strong>Fairness is not offered.</strong> Whoever asks when a permit frees gets it, so under
 * sustained contention a principal can be unlucky repeatedly. Accepted: a fair queue is a queue,
 * and a queue of Tomcat threads is the failure mode this replaces. The per-principal ceiling is
 * what stops one caller being systematically <em>lucky</em>.
 *
 * <p><strong>A permit is not a connection.</strong> A request may hold a permit while holding no
 * connection (JSON serialisation, CSV assembly in Java) and may hold a connection under a permit
 * for less than the permit's life. The invariant is one-directional and that is the useful
 * direction: at most {@link #maxTotal()} requests on this surface exist at once, therefore at most
 * that many can be holding a connection. Do not write the converse anywhere.
 */
@Slf4j
public abstract class PerPrincipalInFlightLimit {

    private final ProductMetrics metrics;

    /**
     * The request attribute the permit is parked on, so two interceptors in front of one handler
     * take ONE permit rather than two. Keyed on the concrete subclass, so a second occupancy bound
     * on the same request would carry its own permit rather than silently reusing this one.
     */
    private final String requestAttribute =
            PerPrincipalInFlightLimit.class.getName() + "#" + getClass().getSimpleName();

    /** key: user id → the permits that principal holds and waits for. */
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Every permit currently out, so {@link #sweepStalePermits()} can find one that is too old.
     * A set rather than a queue: release order is not acquisition order, and the only question
     * ever asked of this collection is "which of these is older than the ceiling?".
     */
    private final Set<Permit> outstanding = ConcurrentHashMap.newKeySet();

    /**
     * The surface-wide counter — the bulkhead. Built in {@link #initialiseSurfacePermits()} rather
     * than in the constructor because {@link #maxTotal()} reads a subclass field that is not
     * assigned until the subclass constructor has run.
     */
    private Semaphore surface;

    protected PerPrincipalInFlightLimit(ProductMetrics metrics) {
        this.metrics = metrics;
    }

    @PostConstruct
    void initialiseSurfacePermits() {
        this.surface = new Semaphore(maxTotal());
    }

    /** Whether this bound applies at all — its OWN switch, never the rate-limit master switch. */
    protected abstract boolean enabled();

    /** How many requests one principal may have in flight on this surface. */
    protected abstract int maxPerPrincipal();

    /** How many requests every principal together may have in flight on this surface. */
    protected abstract int maxTotal();

    /** How long a request may wait for a permit before being refused; {@code 0} refuses at once. */
    protected abstract long acquireWaitMs();

    /**
     * How old a permit may get before {@link #sweepStalePermits()} takes it back — a backstop, not
     * a request timeout, so it belongs well above any duration a working request can have.
     */
    protected abstract long maxPermitAgeMs();

    /**
     * Count one forced release. Abstract for {@link #perPrincipalKind()}'s reason: the meter names
     * belong to a surface and live in {@link ProductMetrics}, while the mechanism lives here.
     */
    protected abstract void countForcedRelease();

    /** The metric tag a "your own share is full" refusal is counted under. */
    protected abstract RateLimitKind perPrincipalKind();

    /** The metric tag a "the whole surface is full" refusal is counted under. */
    protected abstract RateLimitKind surfaceKind();

    /**
     * What the CALLER's own in-flight work is called in the refusal — {@code "requests"} here,
     * {@code "uploads"} for a future upload bound. It names the reader's own conduct, which is the
     * only thing that refusal is allowed to prescribe an action about.
     */
    protected abstract String callerNoun();

    /**
     * What the SURFACE's work is called in the refusal — {@code "expensive requests"}. It names a
     * condition of the instance and prescribes nothing but a retry.
     */
    protected abstract String surfaceNoun();

    /** The request attribute this limit parks its permit on. Read by the interceptor. */
    public String requestAttribute() {
        return requestAttribute;
    }

    /**
     * Take one permit for {@code principal}, waiting up to {@link #acquireWaitMs()} in total, or
     * refuse.
     *
     * @return the permit to hand back to {@link #release(Permit)}, or {@code null} when this bound
     *         is off or there is no principal to key on — in which case there is nothing to release
     * @throws ConcurrencyLimitedException 429 {@code TOO_MANY_IN_FLIGHT} when the principal's own
     *         share is full, 429 {@code EXPENSIVE_SURFACE_BUSY} when the whole surface is
     */
    public Permit acquire(UUID principal) {
        if (!enabled() || principal == null) {
            return null;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(acquireWaitMs());

        var bucket = attach(principal);
        if (!tryAcquire(bucket.permits, deadline)) {
            detach(principal);
            metrics.rateLimitHit(perPrincipalKind());
            log.debug("{} in-flight ceiling reached for user {}", surfaceNoun(), principal);
            throw ConcurrencyLimitedException.tooManyInFlight(callerNoun());
        }
        // Rule 1: the surface permit is taken SECOND and released on failure, so no thread ever
        // waits for a per-principal permit while holding a surface one.
        if (!tryAcquire(surface, deadline)) {
            bucket.permits.release();
            detach(principal);
            metrics.rateLimitHit(surfaceKind());
            log.debug("{} surface full ({} in flight) — refusing user {}",
                      surfaceNoun(), maxTotal(), principal);
            throw ConcurrencyLimitedException.surfaceBusy(surfaceNoun());
        }
        var permit = new Permit(this, principal);
        outstanding.add(permit);
        return permit;
    }

    /**
     * <strong>Take back a permit whose request has been holding it too long</strong> — the bound on
     * a duration this application does not control.
     *
     * <p>A permit is acquired in {@code preHandle}, before the request body is read and long before
     * the response is written, so its life includes two stretches that belong to the CLIENT: the
     * upload of the body and the download of the answer. <strong>Nothing else bounds how long
     * either takes.</strong> {@code statement_timeout} bounds a statement, not a socket; the
     * container's read timeout — Tomcat's inherited {@code connectionTimeout}, or the 20 s
     * {@code TomcatUploadTimeoutCustomizer} pins in its place — bounds the GAP between two reads
     * and not their number, so a client that sends one byte inside every gap holds a share of the
     * bulkhead for as long as it likes at the price of one TCP connection. As few as two accounts
     * fill the whole default surface that way. <strong>That is a cost the bound itself
     * created</strong>: the same requests used to occupy a handful of 200 Tomcat threads, so this
     * re-denominated the surface from threads to permits and cut the price of denying it by well
     * over an order of magnitude.
     *
     * <p>{@code TomcatUploadTimeoutCustomizer} and the edge's {@code read_body} timeout price that
     * conduct in the two places that can see a socket — the first inside the application, so every
     * deployment has it; the second only where the bundled edge runs. <strong>Neither ENDS the
     * hold, and this does</strong>: a per-read ceiling is a packet rate and an absolute edge
     * deadline is absent behind somebody else's nginx. That is what makes this the layer the
     * property rests on rather than the last of three equals.
     *
     * <p><strong>Counted, never silent.</strong> A forced release means either a hostile client or
     * a request that genuinely ran for minutes, and both are things an operator must be able to
     * see — a gauge pinned at the ceiling has two explanations now (a leaked permit and a held
     * one), and this counter is what tells them apart.
     *
     * <p>It cannot over-issue capacity: {@link #release(Permit)} is idempotent, so the request's
     * own release later is a no-op. What it can do is let the surface hold one more request than
     * the ceiling says, since the forced-out request is still running. That is the deliberate
     * trade — a bounded, counted, logged over-issue against an unbounded, silent capacity loss.
     *
     * <p><strong>And that trade is only affordable because every handler on this surface is
     * SYNCHRONOUS.</strong> The over-issued permit costs a Tomcat worker and some heap but never a
     * CONNECTION, and the reason is not the watchdog's arithmetic: it is that a synchronous handler
     * has closed its transaction and returned its connection to the pool long before the request is
     * old enough to be swept — what remains at that age is the client-paced response write. The
     * moment a streaming handler lands here, a force-release hands out a permit while a connection
     * is genuinely still held, and the bulkhead over-issues the resource it exists to reserve. The
     * two are coupled: {@code ThrottleCoverageTest.noExpensiveReadHandlerIsAsynchronous} is what
     * keeps this paragraph true, and whoever makes that test fail owes this trade a new answer.
     *
     * <p><strong>A forced release is counted only when this sweep actually performed one.</strong>
     * The request's own {@code afterCompletion} can win the {@code compareAndSet} in between, and
     * counting that would put a WARN naming a user id against a client that did nothing wrong — in
     * a counter that is the whole discriminator between a leaked permit (flat) and a held one
     * (climbing), i.e. between "restart this replica" and "go and look at that client".
     */
    public void sweepStalePermits() {
        long ceiling = maxPermitAgeMs();
        if (ceiling <= 0) {
            return;
        }
        long now = System.nanoTime();
        for (var permit : outstanding) {
            long ageMs = TimeUnit.NANOSECONDS.toMillis(now - permit.acquiredAtNanos);
            if (ageMs < ceiling) {
                continue;
            }
            if (permit.released.get()) {
                continue;
            }
            // The check above is an early-out, not the decision: between it and here the request's
            // own afterCompletion can win the CAS, and a sweep that counted anyway would report a
            // forced release it did not perform — against a counter whose whole job is to tell a
            // LEAKED permit (flat) from a HELD one (climbing), and whose WARN names a user id.
            if (!release(permit)) {
                continue;
            }
            countForcedRelease();
            log.warn("Force-released a {} permit held for {} ms by user {} (ceiling {} ms). The "
                     + "request still holds a Tomcat worker, so this is a bound on the SHARE it "
                     + "occupies and not on the request: a permit that spans a client-controlled "
                     + "body read or response write can otherwise be held indefinitely for the "
                     + "price of one socket. If this is not rare, look for slow clients before "
                     + "raising any ceiling.",
                     surfaceNoun(), ageMs, permit.principal, ceiling);
        }
    }

    /**
     * Hand a permit back. <strong>Idempotent</strong>, and it must be: the interceptor releases
     * from whichever terminal callback runs first, and a double release would hand the surface
     * more capacity than it has.
     *
     * @return {@code true} when THIS call is the one that gave the permit back, {@code false} when
     *         it had already been released (or there was nothing to release). The boolean is not
     *         decoration: the {@code compareAndSet} below is the only thing that knows which caller
     *         won, and {@link #sweepStalePermits()} must count and log a forced release only when
     *         it actually performed one — otherwise a request whose own {@code afterCompletion}
     *         wins the race is reported as a stalled client, and the force-release counter is the
     *         sole discriminator the runbook has between a leaked permit and a held one.
     */
    public boolean release(Permit permit) {
        if (permit == null || permit.owner != this || !permit.released.compareAndSet(false, true)) {
            return false;
        }
        outstanding.remove(permit);
        surface.release();
        var bucket = buckets.get(permit.principal);
        if (bucket != null) {
            bucket.permits.release();
        }
        detach(permit.principal);
        return true;
    }

    /**
     * Current surface occupancy — the number behind {@code hamstrack.expensive_read.in_flight}.
     * Without it, "is the bulkhead ever full?" is unanswerable, and a leak is invisible until the
     * surface refuses everything.
     */
    public int inFlight() {
        return surface == null ? 0 : maxTotal() - surface.availablePermits();
    }

    /** How many permits one principal is currently holding. For assertions, not for decisions. */
    public int inFlightFor(UUID principal) {
        var bucket = buckets.get(principal);
        return bucket == null ? 0 : maxPerPrincipal() - bucket.permits.availablePermits();
    }

    private boolean tryAcquire(Semaphore semaphore, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        try {
            return semaphore.tryAcquire(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // A thread interrupted mid-acquisition is a request being torn down; refuse rather
            // than swallow, and leave the flag for whoever is unwinding it.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Join (or create) the principal's bucket, counting this thread as a user of it — a HOLDER or
     * a WAITER, which is why the entry cannot be evicted out from under a thread that is still
     * blocked on its semaphore.
     */
    private Bucket attach(UUID principal) {
        return buckets.compute(principal, (id, existing) -> {
            var bucket = existing != null ? existing : new Bucket(maxPerPrincipal());
            bucket.users++;
            return bucket;
        });
    }

    /** Leave the bucket, dropping the map entry when the last user goes (rule 5). */
    private void detach(UUID principal) {
        buckets.compute(principal, (id, existing) -> {
            if (existing == null) {
                return null;
            }
            existing.users--;
            return existing.users <= 0 ? null : existing;
        });
    }

    /**
     * One principal's share. {@code users} counts holders AND waiters and is mutated only inside
     * {@link ConcurrentHashMap#compute}, which is atomic per key — so no lock of our own.
     */
    private static final class Bucket {
        final Semaphore permits;
        int users;

        Bucket(int maxPerPrincipal) {
            this.permits = new Semaphore(maxPerPrincipal);
        }
    }

    /**
     * A taken permit. Carries its owner so a release cannot be attributed to the wrong limit, and
     * a released flag so releasing twice is a no-op rather than a capacity leak in the other
     * direction.
     */
    public static final class Permit {
        private final PerPrincipalInFlightLimit owner;
        private final UUID principal;
        private final AtomicBoolean released = new AtomicBoolean();

        /**
         * {@code nanoTime}, not the wall clock: this is a duration and the only correct clock for
         * one is the monotonic one. A permit taken across an NTP step would otherwise be swept
         * early or never.
         */
        private final long acquiredAtNanos = System.nanoTime();

        private Permit(PerPrincipalInFlightLimit owner, UUID principal) {
            this.owner = owner;
            this.principal = principal;
        }
    }
}
