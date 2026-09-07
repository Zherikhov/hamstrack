# ADR-0030: Expensive reads are bounded in concurrency by a logical bulkhead over the shared pool, not by a second pool

Record date: 2026-09-04
Status: Accepted (implemented in HD-182)
Source: HD-182 (a single principal is entitled to more connection-seconds per minute than the
replica's pool has); the HD-186 measurement — `ops/loadtest/RESULTS-2026-08-31.md` §4 (probe P1 — "confirmed, and
worse than predicted") and finding No. 2 §7; `docs/design/expensive-read-concurrency-proposal.md`;
HD-151 (`docs/design/statement-timeout-proposal.md` — the bound this decision completes);
HD-191 (the limiter family the new one joins); ADR-0020 (capacity is measured in production);
HD-251 (upload concurrency — the same primitive, a different binding)

## Context

Every limiter in the product bounds **how often** you may ask and none bounds **how many requests may
be executing at once**. `ReportRateLimiter` (60/min) and `SearchRateLimiter` (120/min) are windowed
counters over `PerPrincipalMinuteBudget`; Tomcat gives 200 threads, so all 180 requests per
minute of one principal can be in flight simultaneously against `DB_POOL_MAX_SIZE` (10 by
default) connections, each for up to `DB_STATEMENT_TIMEOUT_MS` (10 s), and `POST …/search` runs the
predicate twice (count + page). The replica gives 600 connection-seconds per minute; one principal is
entitled to 1800–3600.

HD-151 made this arithmetic **possible**: before it, holding time was unbounded and the inequality
had no left-hand side. The caveat in `docs/self-hosting.md` covers the operator who **raises** the
bound, and does not cover the default values, where the inequality already fails to balance.

And it has stopped being arithmetic. Probe P1 (one principal exactly within its quota, a second one
beside it simply paging) did not reach the target rate: k6 aborted after 32 s on dropped iterations,
because responses were taking 16 s (median 3.92 s, p95 16.26 s, max 21.95 s). **One principal, breaking
nothing, saturated the instance.** The measurement also named the second scarce resource that the
connection arithmetic does not see: `hs_response_bytes` p90 and max are both 1 277 880 bytes, i.e.
~2.6 MB/s of JSON serialisation against a 512 MB heap on a box whose bottleneck is memory.

The ticket's requirements contradict each other just enough that neither of the two obvious answers
will do on its own: "one principal does not occupy more than a bounded share of the pool" is satisfied
only by a per-principal semaphore, and "the interactive endpoints keep answering while the expensive
surface is saturated" only by a bulkhead.

## Decision

**Two numbers, one primitive, one pool.** A request on an expensive surface takes a permit (a) from
its own principal's quota (`app.expensive-read.max-in-flight-per-principal`, 3 by default) and (b) from
the surface's shared quota (`app.expensive-read.max-in-flight`, 6 by default). The second number is the
bulkhead — **a computed share of the single Hikari pool, not a second physical pool** — and it is what
guarantees that the rest of the API is always left `DB_POOL_MAX_SIZE − max-in-flight` connections,
however many principals are asking.

The permit is taken in `PrincipalThrottleInterceptor` — **in the same registrations** as the minute
budgets, so the path set stays the same and `ThrottleCoverageTest.theThrottledPathSetIsSealed`
does not change. One permit per request, not per interceptor: `…/search/insights` hangs off both
configurers and occupies one connection. Release is in `afterCompletion`.

The relationship of the three dials is checked at startup (`PoolShareConsistency`), not described in
prose: `max-in-flight-per-principal ≤ max-in-flight` and `max-in-flight < poolSize` are hard rules, a
startup failure; more than 60 % of the pool is a WARN. The property is phrased so as to survive a
change of any of the numbers: *through the expensive surface no principal occupies more than
`max-in-flight-per-principal` of the replica's connections, and all of them together no more than
`max-in-flight`, so the rest of the API is always left the difference.*

The limit has **its own** switch (`app.expensive-read.limit-enabled`), deliberately outside
`RATE_LIMIT_ENABLED` — for the same reason the storage quota lives outside it: lifting the bound off
the connection pool must not require turning off the login's brute-force protection.

The key property for which **concurrency** was chosen rather than a lower rate: a rate budget debits
the same unit whether the request took 8 ms or 8 s, so its protection disappears exactly as the
instance slows down. An occupancy budget, on the contrary, **tightens itself** as things slow: at
3 permits and the measured median of 3.9 s the achievable rate of a single principal is ~0.77/s against
a quota of 2/s.

## Alternatives

1. **A second physical pool (`AbstractRoutingDataSource` or a second `EntityManagerFactory`).** It
   gives interactive-traffic isolation unconditionally and **does not give** a per-principal bound: one
   user fills the whole expensive pool and starves everyone else's search. More expensive than "one more
   number": `BoundedJpaTransactionManager` sets `SET LOCAL statement_timeout` + `lock_timeout` on the
   single transaction manager, while `LockTimeout`, `DatabaseTimeoutConsistency`, the Hikari metrics,
   the pool's test bar in `pom.xml` and the `work_mem × nodes × backends` arithmetic in
   `.env.prod.example` all proceed from one pool per replica. Routing means a thread-local set by an
   interceptor — and the question "which pool did this execute on" becomes a list somebody
   maintains, while a path that forgot to set the flag silently goes to the interactive pool. This is
   exactly the shape §2 of `statement-timeout-proposal.md` already rejected for the statement bound.
2. **A per-principal semaphore only.** It satisfies the first half of the ticket's criteria and not the
   second: N principals at K each add up past the pool (at K = 3, four of them take everything).
3. **A per-surface bulkhead only.** The mirror failure: it protects the interactive endpoints
   unconditionally and does not bound a single principal, i.e. it does not close the measured P1 finding.
4. **Lower `REPORTS_REQUESTS_PER_MINUTE` / `SEARCH_REQUESTS_PER_MINUTE`.** A rate stays a rate:
   it would have to be tuned for the worst tenant on the worst hardware and would be wrong for everyone
   else, and the protection would still disappear as the instance slows down.
5. **A cluster-wide occupancy counter (a shared store).** It would cure a problem that does not exist: the
   resource being protected — the pool — belongs to the replica by itself, so a per-process counter here
   is **exact**, not weakened (unlike the minute budgets, where N replicas give N × the quota). The
   price is a shared store on the hot path of every expensive read.
6. **An off-the-shelf bulkhead (Resilience4j and the like).** The same primitive plus a dependency, its
   own configuration model, its own metrics and its own refusal shape alongside the existing one —
   against ~a hundred lines that repeat the shape of `PerPrincipalMinuteBudget` and land in the same
   seal test.

## Consequences

- **The expensive surface can starve itself, and that is the price of the bulkhead, not a side
  effect.** Above `max-in-flight` expensive reads are refused in milliseconds while interactive traffic
  is served. Before, saturation showed up as everything on the replica failing after 30 s
  (`connectionTimeout`).
- **There are now three refusals on one path, and they differ by `errorType`, not only by code.** The
  minute budget is the former 429 with `Retry-After` until the end of the window;
  `TOO_MANY_IN_FLIGHT` is "too many of your own requests are executing right now";
  `EXPENSIVE_SURFACE_BUSY` is "the instance is executing as many expensive queries as it can". The two
  new ones carry `Retry-After: 1`, like the 409 on lock contention: the obstacle is somebody else's
  request, which will end, not a clock. A `Retry-After` computed for the window would be wrong here by
  a factor of 60. `EXPENSIVE_SURFACE_BUSY` does not prescribe that the reader lower **their own**
  concurrency (they may hold no permit at all) and does not send them to an administrator who is
  unreachable in Cloud.
- **A release obligation has appeared that the rate counter did not have.** A leaked permit is an
  irrecoverable loss of replica capacity until a restart. It is reduced but not eliminated: the paths
  are enumerated, four of them are checked by a test, the surface has a tripwire on asynchronous
  handlers (`afterCompletion` is not called when async starts), and the dedicated switch
  leaves the limit enabled across the whole test suite — so a leak breaks many tests loudly
  rather than degrading production quietly.
- **A permit is not a connection.** It lives for the whole request, including the JSON serialisation,
  when the connection has already been returned. The invariant is one-directional and is useful in
  exactly that direction: no more than `max-in-flight` requests exist at once, therefore no more than
  that many can hold a connection. As a side effect this lowers the heap ceiling, which used to be
  bounded by the number of Tomcat threads (200) rather than by the pool.
- **Against the pool the limit is scale-neutral, against the database it is not.** The ratio
  `max-in-flight : DB_POOL_MAX_SIZE` is invariant when replicas are added, so the guarantee holds on
  each of them without coordination; while the instance's aggregate ceiling against the shared
  `max_connections` is `max-in-flight × replicas`, and it is exactly that number one checks when scaling,
  next to the `work_mem` arithmetic.
- **One sentence in `DatabaseTimeoutConsistency` has become false** — "raise `DB_POOL_MAX_SIZE`
  (currently the only other dial)". It is a uniqueness claim of exactly the shape `CLAUDE.md` demands
  not be written; it is fixed in the same change, together with the copies in
  `docs/self-hosting.md` and `docs/observability.md`.
- **The load rig's standing prediction is deliberately refuted.** `ops/loadtest/k6/probes.js` and
  `RESULTS-TEMPLATE.md` treat P1 as "confirmed" if the in-quota principal receives no 429 while the
  victim degrades; after this decision such an outcome is a regression, and both wordings are fixed,
  or else the rig documents a prediction the product has deliberately made wrong.
- **The primitive is shared, the binding is not.** `PerPrincipalInFlightLimit` is written so that HD-251
  can reuse it for uploads, but they must not be bound by one registration: the scarce resource there
  is different (the parsed multipart, the thread, the S3 socket — while the connection is deliberately
  not held), the debit point is different (inside `AttachmentService.upload`, as with
  `UploadByteBudget`, because multipart is parsed before any interceptor), and the seal is different
  (`AttachmentDoorsTest`, the axis of `FileStorage.store` calls).
