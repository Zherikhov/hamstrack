package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * <strong>How many expensive reads may be running at once</strong> (HD-182) — per principal, and
 * across the whole expensive-read surface.
 *
 * <p>The property these four numbers deliver, phrased so it survives every one of them changing:
 *
 * <blockquote>
 * Through the expensive-read surface, no principal may occupy more than
 * {@code app.expensive-read.max-in-flight-per-principal} of a replica's connections and no set of
 * principals more than {@code app.expensive-read.max-in-flight}, so the rest of the API always
 * retains {@code DB_POOL_MAX_SIZE − app.expensive-read.max-in-flight} of them. The per-minute
 * budgets bound throughput; they do not bound occupancy and never did.
 * </blockquote>
 *
 * <p>{@link PoolShareConsistency} checks the relation at startup rather than describing it in
 * prose, and {@code ADR-0030} records why this is a counted share of the one Hikari pool rather
 * than a second {@code DataSource}.
 *
 * <p><strong>Both ceilings ship as {@link #DERIVE_FROM_POOL}, so the numbers below are what an
 * unconfigured install gets on the shipped pool rather than literals it always gets.</strong>
 * {@link ExpensiveReadShare} sizes them to the pool it finds; the numbers a deployment TYPES are
 * obeyed exactly and checked hard. A literal default could not be both — the check that makes this
 * a bulkhead refuses a share the pool cannot leave anything behind, so a shipped 6 crash-looped
 * every upgrade of an install running a pool of 6 or less having configured nothing at all.
 *
 * <h2>The arithmetic that made an occupancy bound necessary — kept once, here, as history</h2>
 *
 * <p>This is the derivation {@code docs/self-hosting.md} used to carry as a live relation, and it
 * is written down in exactly one place now because it explains why a <em>rate</em> could not
 * deliver the property above, not how to size anything today.
 *
 * <p>Two per-minute budgets bound this surface: {@code app.reports.requests-per-minute} (60) and
 * {@code app.search.requests-per-minute} (120). Neither bounds concurrency, and Tomcat offers 200
 * worker threads, so one principal's 180 entitled requests a minute could all be in flight at once
 * against {@code DB_POOL_MAX_SIZE} (default 10) connections, each held for up to
 * {@code DB_STATEMENT_TIMEOUT_MS} (10 s) — twice that on {@code POST …/search}, which runs its
 * predicate for the count and again for the page. A replica supplies 600 connection-seconds a
 * minute; one principal was entitled to 1800–3600 of them. Everything else on the replica then
 * waited out the pool's acquisition bound — 30 s and unset at the time — and failed, including
 * interactive endpoints with nothing to do with reports. (The bound is 3 s and chosen since
 * HD-233, and this paragraph keeps its number because it is a measurement rather than a rule:
 * re-parameterising it to today's value would falsify the evidence it reports.) Probe P1 of
 * {@code ops/loadtest/RESULTS-2026-08-31.md} measured
 * it: a single principal, violating nothing, saturated the instance, and the probe could not even
 * reach its intended arrival rate.
 *
 * <p><strong>And the property that makes occupancy the right instrument rather than merely an
 * additional one.</strong> A rate bound spends the same unit whether a request takes 8 ms or 8 s,
 * so its protection evaporates precisely as the instance slows down. An occupancy bound tightens
 * as the instance slows: at 3 permits and the measured 3.9 s median, one principal's achievable
 * rate is ~0.77/s against an entitlement of 2/s, so the entitlement becomes self-limiting under
 * exactly the conditions where it mattered. That is why "just lower the per-minute budgets" was
 * rejected — a lower rate is still a rate, and it would have to be sized for the worst tenant on
 * the worst hardware while being wrong for everyone else.
 *
 * <h2>What this does NOT bound, stated flatly</h2>
 * <ul>
 *   <li><strong>Duration — SERVER-side, and only that.</strong> A permit is held for as long as
 *       the request runs; a report that assembles its body in Java holds one for time
 *       {@code statement_timeout} does not govern. Occupancy × duration is bounded above only by
 *       the permit watchdog ({@code PerPrincipalInFlightLimit.sweepStalePermits}), which is a
 *       backstop and not a budget.
 *       <p><strong>The half that is CLIENT-controlled is bounded elsewhere, and it had to be.</strong>
 *       A permit is taken in {@code preHandle}, i.e. before the request body is read and long
 *       before the response is written, so a client that trickles a body — or reads a CSV one byte
 *       at a time — holds a share of this bulkhead for as long as it likes, at the cost of a
 *       socket. That is not a hole in the numbers above; it is what makes them worth attacking,
 *       since 6 sockets are cheaper to hold than 200 Tomcat threads were. Three layers bound it,
 *       in decreasing order of how many deployments they reach:
 *       {@code TomcatUploadTimeoutCustomizer} (in the application, so every deployment has it),
 *       the watchdog above (likewise), and {@code read_body} in the bundled {@code Caddyfile} (our
 *       edge only). Any change to acquisition or release owes those three a re-read.</li>
 *   <li><strong>Heap per request — and it is not ONE dial that bounds it.</strong> A permit
 *       bounds how many assemblies may be alive at once; how large one of them is belongs to
 *       whichever property caps the ROWS of the surface being read, and <em>every surface on
 *       this share brings its own such cap</em>. So the figure is
 *       {@code max-in-flight × (the largest row cap on the share) × ~1.9 KB per shipped row},
 *       against a 512 MB reference heap — a form a third surface cannot falsify.
 *       <p>Phrased over the category because the member form was false one slice after it was
 *       written: this bullet used to name {@code app.reports.max-rows} as "the only dial that
 *       does", and HD-174 put a second one on these same six permits while containing no word a
 *       grep for it would have found. Today there are two, and they ceiling at the SAME 20 000
 *       assembled rows: {@code app.reports.max-rows} (20 000 by default, ~38 MB) and
 *       {@link AgileProperties#sectionMaxIssues()} × ({@code app.agile.max-open-sprints-per-project}
 *       + 1), whose product {@link AgileProperties#isPlanningViewBounded()} holds to
 *       {@code MAX_PLANNING_VIEW_ROWS} (6300 at stock, ~12 MB; ~38 MB at the maximum an operator
 *       may configure).
 *       <p><strong>What this bound DID change is the MULTIPLIER on that per-request figure, and
 *       that is the memory half of the case for putting a surface here at all</strong> (ADR-0031,
 *       which argues it explicitly): concurrent materialisation used to be bounded by Tomcat
 *       threads (200), because a permit spans the whole request including the serialisation that
 *       happens after the connection is returned. It is now bounded by {@link #maxInFlight()}.
 *       6 × ~38 MB ≈ 228 MB is a share several surfaces divide; 200 × anything was not a ceiling
 *       at all. Adding a surface to this share therefore LOWERS the worst case it joins.</li>
 *   <li><strong>Anything off the throttled path set.</strong> A surface that is expensive and is
 *       not registered in <em>a</em> {@code *RateLimitConfig} is outside this bound exactly as it
 *       is outside the rate budgets — the gap {@code ThrottleCoverageTest}'s propagation checklist
 *       names. Phrased over the category and not over today's configurers on purpose: this bullet
 *       used to name two of them by hand, and it was stale the moment
 *       {@code PlanningRateLimitConfig} became the third to carry a bound (HD-174). The claim that
 *       survives a fourth is <em>registered or unbounded</em>.
 *       <p><strong>The planning surface used to be the headline example here, and no longer
 *       is.</strong> {@code GET …/backlog} assembles up to {@code MAX_PLANNING_VIEW_ROWS} issues —
 *       6300 at stock defaults — inside ONE read-only transaction spanning {@code 12 + N}
 *       statements, i.e. 32 at {@code AGILE_MAX_OPEN_SPRINTS=20}, so its worst-case connection hold
 *       is ~320 s where {@code statement_timeout} bounds only each statement. It is <em>inside</em>
 *       this bound since HD-174, spending these same permits from this same share (ADR-0031) with
 *       no second ceiling and no second number an operator has to size. What remains outside is
 *       whatever nobody has registered — unpaged {@code GET …/versions} among them.</li>
 *   <li><strong>Bytes on the wire — recorded here as a known gap, not closed by this class.</strong>
 *       Every budget on the READ side of this product is denominated in requests or in rows; its
 *       byte-denominated budgets are all on the WRITE side ({@code app.write.upload-bytes-per-minute}
 *       is one). And read egress is uncompressed: there is no {@code encode} directive in the
 *       bundled {@code Caddyfile} and no {@code server.compression.enabled} in
 *       {@code application.properties}, so a 12 MB planning response is 12 MB on the wire. At three
 *       permits one principal sustains roughly 36 MB/s of it, which on Cloud is a bill rather than
 *       an outage. Deliberately left as an asymmetry somebody wrote down rather than one somebody
 *       discovers: enabling compression touches the {@code Caddyfile} — one of the two artefacts a
 *       deploy does not sync — and deserves its own measurement rather than a line in an occupancy
 *       change.</li>
 *   <li><strong>Abuse.</strong> A distributed set of principals still sums to
 *       {@link #maxInFlight()} of legitimate work, which is what it is for. Abuse stays the rate
 *       budgets' job.</li>
 * </ul>
 *
 * <p><strong>Identical in {@code dc} and {@code cloud}, with no profile override</strong>, the
 * posture of every cap in this product: a deployment that wants a different share sets the
 * environment variable, visibly, in the one place an operator reads. The bound must never vary by
 * tenant or plan — a per-tenant occupancy share would be a licence check wearing a resource
 * guard's clothes.
 *
 * <p><strong>Per process, and here that is exactly correct rather than a weakening.</strong> For a
 * per-minute budget, counting per node lets N replicas allow N × the budget for one user. For an
 * occupancy bound the protected resource — the pool — is per replica too, so the ratio
 * {@code max-in-flight : DB_POOL_MAX_SIZE} is invariant as replicas are added and the guarantee
 * holds on every replica without coordination. What is <em>not</em> scale-neutral is the database:
 * the instance's aggregate ceiling against a shared {@code max_connections} is
 * {@code max-in-flight × replicas}, which is the number to check when scaling out, beside the
 * {@code work_mem × nodes × backends} arithmetic in {@code .env.prod.example}.
 */
@Validated
@ConfigurationProperties(prefix = "app.expensive-read")
public record ExpensiveReadProperties(
        /*
         * Whether the occupancy bound applies at all.
         *
         * DELIBERATELY OUTSIDE app.rate-limit.enabled, and with its own switch, for the reason
         * the workspace storage quota is outside it: removing a bound on your connection pool
         * must not require disabling brute-force protection on your login page, and debugging a
         * limiter must not require removing the bulkhead. Two kinds of control, two switches.
         *
         * Two consequences, both wanted. RATE_LIMIT_ENABLED=false no longer re-opens the hole
         * this closes. And the bound is ON in the test suite, where dozens of contexts disable
         * rate limiting — which is the cheapest possible leak detector: a leaked permit is not
         * silent, it makes a later SEQUENTIAL expensive request in that context fail, so a
         * release bug breaks many tests loudly instead of degrading production quietly.
         */
        @DefaultValue("true") boolean limitEnabled,
        /*
         * How many requests on the expensive-read surface ONE PRINCIPAL may have in flight.
         *
         * 3 is one above the largest concurrent burst a correct client makes here — the search
         * results page mounts searchSchema, search and savedFilters in parallel, all three on
         * this surface, and a page that 429s its own mount is a bug however correct the
         * arithmetic is. Two would be tighter and would refuse a legitimate page load whenever
         * the acquire wait expired; four buys nothing. Three of a default pool of 10 is 30% for
         * any one caller, against an entitlement that was previously the whole pool.
         *
         * ADDING A SURFACE TO THIS SHARE OWES THIS NUMBER ONE CHECK: does the new surface raise
         * the largest concurrent burst a correct client makes? HD-174's did not, and it was
         * checked rather than assumed — the Backlog page's mount puts ONE request on the bound
         * (its /sprints, /config and /project queries are not on it) and refreshSections iterates
         * with `for (const id of unique) await refreshSection(id)`, so even a cross-section drag,
         * which is two requests, is never two IN FLIGHT. If a change ever parallelises those
         * fetches, the planning surface goes from a burst of 1 to a burst of 2 and this number
         * has to be re-argued in the same commit.
         *
         * -1 (the shipped value) means DERIVE IT: DEFAULT_MAX_IN_FLIGHT_PER_PRINCIPAL, clamped
         * to the derived surface ceiling so a small pool cannot produce a pair that refuses the
         * boot. ExpensiveReadShare owns that arithmetic and logs the numbers it chose.
         *
         * An EXPLICIT value MUST be <= the effective max-in-flight or the boot fails
         * (PoolShareConsistency): above it, this number is dead configuration.
         *
         * There is no "unlimited": 0 fails startup (see the compact constructor below), and a blank
         * EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL= aborts the boot rather than quietly
         * restoring the default.
         */
        @DefaultValue("-1") @Min(-1) @Max(100) int maxInFlightPerPrincipal,
        /*
         * How many requests on the expensive-read surface may be in flight ACROSS EVERY
         * PRINCIPAL. This number is the bulkhead: a counted share of the single Hikari pool, and
         * the thing that guarantees the interactive surface always retains
         * DB_POOL_MAX_SIZE - max-in-flight connections no matter how many principals ask.
         *
         * 6 is 60% of the default pool of 10, leaving four connections the expensive surface can
         * never hold; two principals at their own ceiling reach it. Lower it to 4 only with a
         * measurement; raising it above 6 wants the pool raised first, and PoolShareConsistency
         * warns when it passes 60% of whatever the pool actually is.
         *
         * -1 (the shipped value) means DERIVE IT FROM THE POOL — see ExpensiveReadShare. On the
         * default pool of 10 the derivation yields exactly the 6 described below, so nothing an
         * operator reads changes; on a pool of 6 or 4 it yields 3 or 2 rather than refusing the
         * boot on an install that configured none of this.
         *
         * AN EXPLICIT VALUE IS ABSOLUTE, NOT A SHARE OF THE POOL. A `pool-share=0.6` property
         * would track DB_POOL_MAX_SIZE for a number the operator TYPED, and would silently
         * change this surface's capacity whenever somebody tuned the pool for an unrelated
         * reason (work_mem arithmetic, max_connections). Every other cap in this product is a
         * number an operator reads and types; the coupling is made visible by the startup check
         * instead of invisible by arithmetic. Deriving the DEFAULT is the opposite case and not
         * that mistake: it applies only while the operator has expressed no intent at all, the
         * numbers it chose are logged at every boot, and it is what stops an upgrade refusing to
         * start on an install whose pool is smaller than the share this file ships.
         *
         * AN EXPLICIT VALUE MUST be < the actual Hikari maximum pool size or the boot fails: at
         * or above it the expensive surface can hold every connection, and the property this
         * feature exists to deliver would be absent while every document said it was present. A
         * derived one satisfies that by construction, which is the point of deriving it.
         */
        @DefaultValue("-1") @Min(-1) @Max(1000) int maxInFlight,
        /*
         * How long a request may WAIT for a permit before it is refused, in milliseconds. 0 means
         * refuse immediately, and it is the one number in this record that legitimately accepts
         * zero.
         *
         * A waiting thread holds NO connection and NO heap — strictly cheaper than the status
         * quo, where it would hold both — and it leaves on its own after the bound, so waiters
         * are self-limiting. The wait is what lets the per-principal number be tight without
         * refusing a legitimate page load: a client's parallel mount serialises instead of
         * being refused.
         *
         * It is a bound on the WHOLE acquisition, not on each half: a request that spends 900 ms
         * waiting for its per-principal permit has 100 ms left to wait for the surface permit,
         * so a refusal is never slower than this number plus scheduling.
         *
         * MAX 2000, AND THE CEILING IS THE ONE RELATION THIS NUMBER HAS. A waiting request holds
         * no connection and no heap, but it does hold a TOMCAT WORKER — so this is the only dial
         * in this record denominated in server.tomcat.threads.max (200 by default) rather than in
         * DB_POOL_MAX_SIZE, and it was the one number here that related to nothing. Raising it
         * multiplies the worker-thread cost of every refusal, and refusals are the cheap,
         * high-volume outcome by design: under app.rate-limit.enabled=false — which deliberately
         * leaves THIS bound on — arrivals are unbounded, so a long wait lets one principal park
         * every worker at zero database cost. 2000 keeps the worst case at ~2 thread-seconds per
         * refused request; a client needing more patience than that should retry rather than hold
         * a thread, which is what Retry-After: 1 asks of it.
         */
        @DefaultValue("1000") @Min(0) @Max(2000) int acquireWaitMs
) {

    /**
     * The per-principal ceiling this product ships, used when the operator has configured none.
     * {@link ExpensiveReadShare} clamps it to a pool smaller than the one it was sized for.
     */
    public static final int DEFAULT_MAX_IN_FLIGHT_PER_PRINCIPAL = 3;

    /** The surface ceiling this product ships, on the same terms as the one above. */
    public static final int DEFAULT_MAX_IN_FLIGHT = 6;

    /**
     * Either ceiling as {@code -1}: <strong>derive it from the pool</strong> instead of taking a
     * literal. It is what the shipped {@code application.properties} placeholder falls back to, so
     * an operator who has never typed the variable gets a share sized for THEIR pool — see
     * {@link ExpensiveReadShare} for why a literal default was a self-inflicted outage.
     */
    public static final int DERIVE_FROM_POOL = -1;

    /**
     * <strong>{@code 0} is refused here rather than by {@code @Min}</strong>, because {@code @Min}
     * now has to admit the {@link #DERIVE_FROM_POOL} sentinel and would admit zero with it.
     *
     * <p>Zero is what an operator writes to mean "unlimited", and there is no unlimited on this
     * surface: the off switch is {@code EXPENSIVE_READ_LIMIT_ENABLED}, which is visible, greppable
     * and separate. Taken literally a ceiling of zero means the opposite — every expensive read
     * refused, on a whole surface, with nothing in the configuration that reads like "off".
     *
     * <p>A compact constructor rather than a check further downstream: this is a property of these
     * values alone, and the binder turns the throw into a startup failure naming the property,
     * which is the shape a {@code @Min} violation had.
     */
    public ExpensiveReadProperties {
        if (maxInFlight == 0) {
            throw new IllegalArgumentException(
                    "app.expensive-read.max-in-flight (maxInFlight) is 0, which is not "
                    + "'unlimited' — it would refuse every expensive read on this instance. There "
                    + "is no unlimited value: EXPENSIVE_READ_LIMIT_ENABLED=false removes the "
                    + "bound, a positive number sizes it, and -1 (the shipped default) has it "
                    + "derived from the connection pool.");
        }
        if (maxInFlightPerPrincipal == 0) {
            throw new IllegalArgumentException(
                    "app.expensive-read.max-in-flight-per-principal (maxInFlightPerPrincipal) is "
                    + "0, which is not 'unlimited' — it would refuse every expensive read on this "
                    + "instance. There is no unlimited value: EXPENSIVE_READ_LIMIT_ENABLED=false "
                    + "removes the bound, a positive number sizes it, and -1 (the shipped default) "
                    + "has it derived from the connection pool.");
        }
    }
}
