# Bounding how many expensive reads may be in flight at once (HD-182)

**Status:** proposal / design review. **Date:** 2026-09-04. **Author:** systems-analyst.
**Evidence:** `ops/loadtest/RESULTS-2026-08-31.md` §4 (probe P1 — *confirmed, and worse than
predicted*), finding #2 of §7.
**Related:** `docs/design/statement-timeout-proposal.md` (HD-151, the bound this one completes),
`docs/design/load-capacity-measurement-proposal.md` (HD-186, which produced the evidence),
`docs/design/write-budget-and-storage-quota-proposal.md` (HD-191, the limiter family this joins),
ADR-0020 (capacity is measured on production in a declared window), HD-251 (upload concurrency —
§2.4), HD-189 (the resource change; this is the code change beside it).

**One sentence:** every limiter in this product bounds *how often* a caller may ask and none bounds
*how many they may have running*, so this adds an occupancy bound — per principal and per surface —
and converts an entitlement that provably exceeded supply into one that cannot.

---

## 1. Problem & goal

The expensive-read surface (reports, HQL search, insights, saved-filter validation, the storage
breakdown) is throttled by two per-minute fixed-window counters — `ReportRateLimiter` at 60/min and
`SearchRateLimiter` at 120/min, both `PerPrincipalMinuteBudget`. Neither bounds concurrency. Tomcat
offers 200 worker threads, so one principal's 180 entitled requests per minute may all be in flight
at once against a pool of `DB_POOL_MAX_SIZE` (default 10) connections, each holding one for up to
`DB_STATEMENT_TIMEOUT_MS` (10 s) — twice that on `POST …/search`, which runs its predicate for the
count and again for the page. A replica supplies 600 connection-seconds a minute and one principal is
entitled to 1800–3600 of them. Everything else on the replica then waits out Hikari's 30 s
`connectionTimeout` and fails, including interactive endpoints with nothing to do with reports.

**HD-151 is what made this measurable and it is not a partial fix of it.** Before it the hold was
unbounded, so the arithmetic had no left-hand side; after it the pool provably turns over and the
gap is finite. Its operator note covers the operator who *raises* the bound. It does not cover the
default, where the arithmetic already does not close.

**And it is not arithmetic any more.** Probe P1 put one principal at exactly their documented
allowance with a second principal browsing beside them. It never reached the intended arrival rate:
k6 aborted after 32 s on dropped iterations because responses were taking 16 s (median 3.92 s, p95
16.26 s, max 21.95 s), and the victim's numbers are empty because the probe could not run long
enough to produce them. *A single principal, violating nothing, saturated the instance.* The
measurement also named a second scarce resource the connection arithmetic does not see:
`hs_response_bytes` p90 **and** max were both 1 277 880 bytes, so the entitled endpoints return
~1.3 MB per response and the run was ~2.6 MB/s of JSON serialisation against a 512 MB heap on a box
whose binding constraint was memory.

**Goal.** No principal, and no set of principals, can occupy more than a stated share of a replica's
connection pool through the expensive-read surface; the share is one number an operator can read; the
relation between it and the pool is checked at startup rather than described in prose; and the
refusal tells its reader something they can act on.

**Success looks like:** probe P1 re-run against the fix produces a completed run in which the
entitled principal receives refusals of the new kind while the victim's `browse` class stays inside
its target — i.e. the harness's own standing prediction is deliberately falsified, and the file that
states it is edited to say so.

---

## 2. The four decisions the ticket asks for, answered first

### 2.1 Semaphore or bulkhead → **both, as one mechanism: a logical bulkhead over the pool we already have**

The ticket's two options each fail exactly half of its own acceptance criteria, and the halves are
disjoint:

| | AC-1 *"one principal cannot occupy more than a bounded share"* | AC-2 *"interactive endpoints keep answering while the expensive surface is saturated"* |
|---|---|---|
| per-principal semaphore only | **yes** | **no** — N principals × K each still sums past the pool. At K=3, four principals take it all. |
| second physical pool only | **no** — one principal can fill the whole expensive pool and starve every other tenant's search | **yes**, unconditionally |

**Recommendation: two counters, one primitive, one pool.** A request on the expensive-read surface
must acquire (a) one of `app.expensive-read.max-in-flight-per-principal` permits held for its own
principal, and (b) one of `app.expensive-read.max-in-flight` permits held for the whole surface.
The second counter *is* the bulkhead — a counted share of the single Hikari pool rather than a second
physical pool — and it is what guarantees the interactive surface always retains
`DB_POOL_MAX_SIZE − app.expensive-read.max-in-flight` connections, no matter how many principals ask.

**Why logical rather than physical.** A second `DataSource` is not a second number, it is a second
everything: `BoundedJpaTransactionManager` applies `SET LOCAL statement_timeout` + `lock_timeout` at
`doBegin` on the one transaction manager; `LockTimeout`, `DatabaseTimeoutConsistency`, the Hikari
metrics, the surefire test cap and the `work_mem × nodes × backends` arithmetic in
`.env.prod.example` and `docs/self-hosting.md` all assume one pool per replica. Routing to a second
one means a thread-local set by an interceptor and consulted by an `AbstractRoutingDataSource` — at
which point *"which pool did this run on"* becomes a list somebody maintains, and a path that forgets
to set the flag silently uses the interactive pool. That is precisely the shape
`statement-timeout-proposal.md` §2 rejected for the statement bound, for the same reason: when the
right answer is a property, do not ship a census. A counter has no such failure mode — the guard is
in the same interceptor chain that already decides "is this handler expensive", and that question is
already sealed by a test.

**What the recommendation does NOT buy — stated flatly, because this project's failures are guards
believed to cover more than they did:**

1. **It does not give the expensive surface a guaranteed share.** Above `max-in-flight`, expensive
   reads are refused fast while interactive traffic is served. The expensive surface starves itself
   under contention, deliberately. That is the bulkhead's cost and it is not hidden by combining the
   two counters — combining them only means one principal cannot be the whole cause.
2. **It does not bound duration — and half of that duration belongs to the CLIENT.** A permit is
   held for as long as the request runs. A CSV report that assembles its body in Java inside a
   read-only transaction holds a permit and a connection for time `statement_timeout` does not
   govern at all (`statement-timeout-proposal.md` §3). What is bounded is occupancy.
   **Review round:** the permit is taken in `preHandle`, i.e. before the `@RequestBody` is
   deserialised and long before the response is written, so a client that trickles a body — or reads
   a CSV slowly — holds a share of the bulkhead for as long as it likes at the price of one socket.
   Six of those are the whole default surface, which makes this a cost the bound itself created: the
   same six requests used to occupy six of 200 Tomcat threads. **The layer that makes the hold
   finite is the permit watchdog** (`sweepStalePermits`, force-releases past `statement_timeout` +
   60 s and counts it in `hamstrack_expensive_read_permit_force_released_total`, and counts only a
   release it actually performed). Two more layers raise the price without ending the hold:
   `TomcatUploadTimeoutCustomizer` (`disableUploadTimeout=false` plus a 20 s
   `connectionUploadTimeout`, inside the application, so every deployment has it) is a
   **tightening** rather than a new bound — measured in `tomcat-embed-core` 11.0.22 and by a
   runtime probe, Tomcat's default leaves the body's read timeout at the connector's
   `connectionTimeout` (60 s, operator-settable), so this pins the gap at 20 s independently of that
   dial — and `read_body` in the bundled `Caddyfile` is an absolute deadline at our own edge only.
   The watchdog's over-issue is affordable **only because every handler here is synchronous**
   (`noExpensiveReadHandlerIsAsynchronous`): a streaming one would be handed a permit back while
   still holding a connection. No `write` timeout at the edge: Caddy applies one per response and this product
   serves 30-minute SSE streams, so the watchdog is what bounds the slow-reader half.
3. **It does not bound heap per request.** `REPORTS_MAX_ROWS` remains the only dial that does. What
   changes is the multiplier: concurrent materialisation was bounded by *Tomcat threads* (200),
   because a report's JSON is serialised after the connection is returned; a permit spans the whole
   request including serialisation, so the ceiling drops to `max-in-flight × maxRows × ~1.9 KB`
   ≈ 228 MB at the proposed defaults, and to ~114 MB for any one principal. Lower, not safe.
4. **It does not cover expensive reads that are not on the throttled path set.** The planning view
   (up to 6300 issues), unpaged `GET …/versions` and anything else outside
   `ThrottleCoverageTest`'s sealed patterns are outside this bound exactly as they are outside the
   rate budgets. §2.3 says what happens when one arrives.
5. **It is per replica.** §7.

**And the property that makes an occupancy bound the right instrument rather than merely an
additional one:** a rate bound spends the same unit whether a request takes 8 ms or 8 s, so its
protection *evaporates precisely as the instance slows down*. An occupancy bound tightens as the
instance slows — at 3 permits and a 3.9 s median (the measured P1 figure) one principal's achievable
rate is ~0.77/s against an entitlement of 2/s, so the entitlement becomes self-limiting under exactly
the conditions where it mattered. This is the argument for doing it at all, and it is why "just lower
`SEARCH_REQUESTS_PER_MINUTE`" (the ticket's option 3, as a fix rather than as a documented relation)
is rejected: a lower rate is still a rate, and it would have to be sized for the worst tenant on the
worst hardware while being wrong for everyone else.

### 2.2 One primitive for uploads too (HD-251) → **share the primitive, do not share the binding**

*Caveat on sourcing: HD-251 is a tracker ticket and is not present in this repository. What follows
is reasoned from its one-line description as given (upload concurrency is unbounded — the same shape
on a different resource) and from the upload path itself (`AttachmentService.upload`,
`UploadByteBudget`). If the ticket contains constraints beyond that, re-check §2.2 against it.*

**Build the primitive general; bind it here only.** Ship `PerPrincipalInFlightLimit` in
`common.ratelimit` beside `PerPrincipalMinuteBudget`, carrying the counting, the release, the
surface-wide ceiling, the refusal shape, the `Retry-After` and the metric; the subclass supplies the
numbers, the metric tags and the noun. That is the exact split the minute-budget family already
uses, and its javadoc already states the reason — *a throttle whose counting, eviction, refusal
shape and `Retry-After` arithmetic exist twice is a throttle where a fix lands in one of them.*

**Do not extend this ticket's binding to uploads, for three reasons that are about the resource and
not about effort:**

- **The scarce resource is different.** An upload's dominant cost is a parsed multipart of up to
  25 MB (heap/temp disk), a Tomcat worker, and a socket held for up to
  `STORAGE_S3_API_CALL_TIMEOUT_MS` (30 s) inside `fileStorage.store` — which runs *deliberately
  outside any transaction* so it can never pin a Hikari connection. A bound sized against the
  connection pool would be sized against the one resource an upload was engineered not to hold.
- **The binding site is different, and it is not a path.** `UploadByteBudget` is spent inside
  `AttachmentService.upload` because its cost is `MultipartFile.getSize()`, which no interceptor can
  see. Upload concurrency is bounded at the same place for the same reason, and — as
  `AttachmentService`'s javadoc already records — multipart resolution is eager, so by the time any
  interceptor runs the bytes are already parsed. An interceptor-mounted concurrency bound on the
  upload path would bound the wrong instant.
- **The seal is different.** Path-shaped controls are sealed by `ThrottleCoverageTest`; storage-door
  controls are sealed by `AttachmentDoorsTest` on the axis of `FileStorage.store` call sites. HD-251
  belongs on the second axis and the propagation checklist already says so.

So: HD-251 reuses the class, its own `@ConfigurationProperties`, its own `RateLimitKind`, its own
denomination (concurrent uploads, and plausibly concurrent *bytes* in flight rather than requests),
and its own seal. Coupling the primitive is reuse; coupling the binding would hand HD-251 the wrong
resource and would make `ThrottleCoverageTest`'s sealed pattern set the answer to a question it does
not ask.

### 2.3 The sealed path set → **the new bound rides the existing registrations; the seal is extended, not duplicated**

The standing rule is *a throttle is earned by the work a handler does, not by where it is mounted*,
and the artefact that enforces it is `ThrottleCoverageTest`, whose failure message carries the
propagation checklist.

**Decision: the concurrency bound is spent by the same `PrincipalThrottleInterceptor` instances, on
the same registrations, in `ReportRateLimitConfig` and `SearchRateLimitConfig`.** No new
configurer, no new pattern list. Consequences, each deliberate:

- `theThrottledPathSetIsSealed()` is **unchanged and still exactly right** — the same three patterns
  for reports, the same two for search. There is no second list to go stale, which is the whole point
  of not creating one.
- A new expensive read added to either configurer inherits **both** controls in one edit. That is
  strictly better than the status quo, where a new surface needs one edit per control.
- **But "there is a `PrincipalThrottleInterceptor` in front of this handler" stops implying "this
  handler is concurrency-bounded",** because the write budget's interceptor is the same type and is
  deliberately *not* getting a concurrency bound (§3, out of scope). So the type question must be
  refined into a *which-controls* question: `PrincipalThrottleInterceptor` exposes
  `concurrencyBound()` (nullable, beside the existing `budget()`), and a new test
  `everyExpensiveReadHandlerIsAlsoConcurrencyBounded` asks it of every handler under the same two
  packages with the same inverted polarity — covered unless explicitly exempt with a written reason.
  Without that refinement the coverage assertion would keep passing while covering half of what its
  name claims, which is the failure this test file exists to have deleted.
- The `PROPAGATION_CHECKLIST` gains the new artefacts (§9.2). Its closing instruction — *"if you are
  adding an expensive surface, ask THREE questions… and if it is a WRITE, ask the fourth"* — gains a
  fifth: **does it hold a connection while it works, and if so is it inside the occupancy share?**

**A surface that is expensive but not on a throttled path is outside both controls, identically.**
That is not weakened by this change and is not fixed by it; it is the same gap the checklist already
names, and the same test fires when a new path is registered.

### 2.4 The refusal → two statuses of the same code, two `errorType`s, one `Retry-After` value that is not the rate limiter's

There are now **three** ways to be refused on one path and a caller must be able to tell them apart,
because the remedies differ and are not interchangeable:

| refusal | meaning | what the caller can do | `Retry-After` |
|---|---|---|---|
| `429` (existing, no `errorType`) | you have spent your minute's budget | stop asking for up to a minute | seconds until the window rolls (1–60) |
| `429 TOO_MANY_IN_FLIGHT` (new) | **your own** requests are already occupying your share | let one finish — close the tab, stop the loop; a UI may retry once | **1** |
| `429 EXPENSIVE_SURFACE_BUSY` (new) | the instance's expensive-read share is full; you may hold none of it | retry shortly; there is nothing else to do | **1** |

- **429 for both**, not 503. 5xx is the class intermediaries and SDKs auto-retry, and auto-retrying a
  saturation refusal re-spends the resource that is already scarce — the decisive argument
  `GlobalExceptionHandler.handleStatementBudget` already records, and it applies unchanged. 429 is
  also what the codebase already means by "you asked for more of this than you may have".
- **`Retry-After: 1`, and computing it the way the minute budget does would be wrong by up to 60×.**
  The obstacle here is another in-flight request that ends in at most `statement_timeout` and
  typically in tens of milliseconds; the obstacle there is a clock. `1` is the value the lock
  contention 409 already uses for exactly this reason ("the rival commits and the retry succeeds"),
  and it is the one honest hint here. A minute-window `Retry-After` on a queue that clears in 50 ms
  would make a correct client sit out a whole minute.
- **The two new details prescribe different actions and neither prescribes one its reader cannot
  perform.** `TOO_MANY_IN_FLIGHT` may name the caller's own concurrency, because that is the caller's
  own conduct: *"Too many of your requests are running at once — wait for one to finish."* It must
  **not** say "the server is busy", which would be false and would send the reader to an operator.
  `EXPENSIVE_SURFACE_BUSY` must **not** tell the reader to reduce their own concurrency — they may
  hold exactly one permit, or none — and must not tell them to contact an administrator, which on
  Cloud is a dead end (`statement-timeout-proposal.md` §5.4 shipped that mistake once and the test
  that catches it asserts the body does not contain the word *administrator*). Its sentence states a
  condition and offers a retry: *"This instance is running as many expensive requests as it can at
  once. Try again in a moment."*
- **`EXPENSIVE_SURFACE_BUSY` discloses that somebody else is busy, and that is an accepted trade.**
  It is a load signal weaker than the latency the same caller can already measure; it carries no
  count, no tenant, no principal and no number. The tenancy contract is untouched for the reason
  `PerPrincipalMinuteBudget` gives: the refusal is byte-for-byte identical for a real workspace, a
  nonexistent one and somebody else's, so it cannot answer a question about a resource the caller
  cannot see. Named here rather than discovered by `security-officer`.

---

## 3. Scope

**In scope**

- A general `PerPrincipalInFlightLimit` primitive in `common.ratelimit`, and one concrete bound over
  the expensive-read surface.
- Four properties + env vars, validated, with a startup cross-check against the actual pool size.
- Two refusal shapes on existing endpoints, two metric counters, one gauge, one provisioned alert.
- The coverage/seal extension in `ThrottleCoverageTest` and the checklist edits it prints.
- The full documentation set (§9.2), including the two places that currently state the relation as
  three numbers and the one place that states a uniqueness claim this change falsifies.
- Inverting probe P1's stated prediction in the load harness.

**Out of scope, named so the shipped guard is not read as covering them**

- **The write surface** (`/api/workspaces/*/projects/*/issues/**`). Deliberate, with a reason rather
  than by omission: writes are short, are already bounded by `lock_timeout` + `statement_timeout`,
  materialise almost nothing, and an occupancy bound there would refuse an SPA that saves several
  inline edits at once. The primitive is available the day evidence arrives; today there is none.
- **Uploads** — HD-251, §2.2.
- **Expensive reads outside the sealed path set** — §2.3.
- **Changing any rate budget.** 60 and 120 stay. This ticket changes what the numbers *have to
  carry*, not the numbers.
- **A second connection pool**, now or later, unless a measurement says the counter is insufficient.
- **A cluster-wide in-flight count.** §7 argues it would be wrong, not merely expensive.

**Non-goals**

- This does not make the instance fast, and it does not make saturation invisible: it converts
  "everything on the replica fails on connection acquisition after 30 s" into "the expensive surface
  refuses in milliseconds while everything else is served". Under sustained overload some legitimate
  reports and searches will be refused. That is the trade, stated once.

---

## 4. Actors & permissions

None. This is infrastructure: no endpoint, no role, no workspace scoping, no tenant-visible resource,
no data model.

Three tenancy-adjacent notes, because "no permissions" is not "nothing to check":

- **The per-principal counter is keyed on the caller**, so it is spent safely in an interceptor —
  before the controller resolves anything — for the reason `PerPrincipalMinuteBudget` states: the
  refusal is identical for a real workspace, a nonexistent one and somebody else's, and the
  404-for-all-three contract is untouched. Never re-key it on a workspace or a project: a
  resource-keyed refusal spent before resolution answers an existence question.
- **The surface-wide counter is keyed on nothing**, which is what lets it be spent in the same place.
  Its refusal must carry no figure that varies with other tenants' behaviour (§2.4).
- **The bound must not vary by tenant or plan.** A per-tenant occupancy share would be a licence
  check wearing a resource guard's clothes — the same prohibition `application.properties` states for
  the roles cap and the statement bound.

---

## 5. Behaviour & rules

### 5.1 The primitive

`common.ratelimit.PerPrincipalInFlightLimit` (abstract), with the subclass supplying `enabled()`,
`maxPerPrincipal()`, `maxTotal()`, `acquireWaitMs()`, the two `RateLimitKind`s and the surface noun.
`common.ratelimit.ExpensiveReadConcurrencyLimit` is the one concrete instance.

```
Permit acquire(UUID principal)      // throws ConcurrencyLimitedException, or returns a releasable token
void   release(Permit)              // idempotent
int    inFlight()                   // for the gauge
```

Rules, each of which is a failure mode if inverted:

1. **Acquire per-principal first, then the surface permit; on failure to get the second, release the
   first.** Fixed order, and no thread ever waits for a per-principal permit while holding a surface
   permit, so there is no cycle and no deadlock.
2. **A bounded wait, then refuse.** `acquireWaitMs` (default 1000, `0` = refuse immediately). A
   waiting thread holds **no connection and no heap** — strictly cheaper than the status quo, where
   it would hold both — and it leaves on its own after the bound, so waiters are self-limiting. The
   wait is what lets the per-principal number be *tight* without refusing a legitimate page load:
   the search results page mounts `searchSchema`, `search` and `savedFilters` concurrently, all three
   on this surface, and a page that 429s its own mount is a bug however correct the arithmetic is.
3. **Release exactly once, from a `finally`, on every exit path.** §6.1.
4. **One permit per request, never one per interceptor.** `POST …/search/insights` is behind both
   configurers; it occupies one connection, so it takes one permit. The first interceptor to acquire
   sets a request attribute; the second sees it and skips; the one that set it releases it.
5. **`enabled() == false` or `principal == null` → no permit, no counting.** Anonymous requests
   cannot reach these paths (`/api/**` is `authenticated()`), and treating a null principal as a
   shared key would let one request exhaust everyone (`PerPrincipalMinuteBudget`'s rule, unchanged).
6. **The counters cost no connection and no statement.** A limiter that needed the resource it
   protects in order to decide would be self-defeating. Asserted (§10).
7. **The per-principal map removes an entry when its count reaches zero** (`ConcurrentHashMap
   .compute` returning `null`), so it is bounded by *concurrent* principals rather than by principals
   ever seen — no eviction sweep, and no scheduled job to starve. Note this is the opposite of the
   minute-window map, which needs one.

### 5.2 Where it is spent

`PrincipalThrottleInterceptor` gains an optional `PerPrincipalInFlightLimit`. Order inside
`preHandle`: **spend the rate budget first, acquire the permit last.** A request the rate budget will
refuse must never have taken a permit, and the budget's throw must happen before anything is held.
`afterCompletion` releases.

Ordering across interceptors is already fixed and load-bearing (`ReportRateLimitConfig.ORDER = 0`,
search `+1`) so insights spends reports-then-search; the permit is taken by whichever runs first and
is released by that same one.

### 5.3 The numbers

| property | env var | default | range | why |
|---|---|---|---|---|
| `app.expensive-read.limit-enabled` | `EXPENSIVE_READ_LIMIT_ENABLED` | `true` | — | §5.4 |
| `app.expensive-read.max-in-flight-per-principal` | `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` | **derived → 3** | 1–100, or `-1` | above the largest concurrent burst a correct client makes on this surface (the search page's three parallel mount queries), and 3 of a default pool of 10 is 30 % for any one caller — against an entitlement that was previously the whole pool |
| `app.expensive-read.max-in-flight` | `EXPENSIVE_READ_MAX_IN_FLIGHT` | **derived → 6** | 1–1000, or `-1` | 60 % of the default pool, leaving four connections the expensive surface can never hold; two principals at their own ceiling reach it |
| `app.expensive-read.acquire-wait-ms` | `EXPENSIVE_READ_ACQUIRE_WAIT_MS` | **1000** | 0–2000 | long enough for a fast sibling request to finish and free a permit, short enough that a refused caller is refused promptly rather than parked. The ceiling is 2000 because a waiting request holds a **Tomcat worker**, not a connection — the one number in this table denominated in `server.tomcat.threads.max` |

All four `@Validated`, primitive types, **no "unlimited" value** — `0` is out of range on the two
ceilings for the reason every other cap gives, and `EXPENSIVE_READ_MAX_IN_FLIGHT=` (empty) aborts the
boot rather than quietly restoring the default. `acquire-wait-ms` is the one that legitimately accepts
`0`, meaning *refuse immediately*.

**Both ceilings ship as `-1`, meaning "derive from the pool" (review round).** The shipped literals
were a self-inflicted outage: §5.5's hard rule refuses the boot unless the share is strictly below
`DB_POOL_MAX_SIZE`, so a literal `6` crash-looped every upgrade of an install running a pool of 6 or
less — a size this project's own `.env.prod.example` recommends — on an install that had set none of
these variables. `ExpensiveReadShare` derives `min(60 % of the pool, 6)` and clamps the per-principal
number to it; the hard rules then apply to numbers an operator TYPED, which is the only kind anyone
can be told to correct. The sentinel is a number rather than an empty value precisely so the blank
keeps aborting the boot — and `-1` is documented to operators as a value they may write, since an
environment assembled from a systemd `EnvironmentFile`, an ECS task definition or a ConfigMap has
no line to comment out.

**The MIXED configuration is narrowed, not refused (second review round).** Deriving both ceilings
satisfies rule 1 by construction, but a *typed* per-principal ceiling against a *derived* share does
not: on `DB_POOL_MAX_SIZE=4` the derived share is 2, and an operator who uncomments exactly the line
`.env.prod.example` shows them (`EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL=3`) would have met
`3 > 2` and a crash loop — the hazard moved rather than removed. `ExpensiveReadShare` therefore
clamps that number down to the derived share and WARNs, naming both numbers and the pool. The
refusal survives where refusing is right: **both numbers typed** is an operator stating a relation,
and a stated relation can be wrong. A consequence worth stating rather than discovering: on a pool
of 6 or smaller the two derived ceilings are *equal* (3/3 at 6, 2/2 at 4), so one caller can reach
the whole share alone and only the reservation for the rest of the API still binds — the
alternative, a per-user ceiling below the share on a small pool, would serialise the search page's
own three mount queries one at a time.

**Absolute numbers, not a share of the pool.** Considered and rejected: a `pool-share=0.6` would
track `DB_POOL_MAX_SIZE` automatically and would silently change the expensive surface's capacity
whenever somebody tuned the pool for an unrelated reason (`work_mem` arithmetic, `max_connections`).
Every other cap in this product is a number an operator reads and types; the coupling is made visible
by the startup check instead of being made invisible by arithmetic. §11 records this as an open
question because it changes production capacity behaviour.

### 5.4 Its own off switch, deliberately outside `RATE_LIMIT_ENABLED`

`app.rate-limit.enabled` is the master switch for every limiter that has one. This one is **outside
it**, with its own, for the reason the workspace storage quota is outside it: *removing a bound on
your connection pool must not require disabling brute-force protection on your login page, and
debugging a limiter must not require removing the bulkhead.* Two kinds of control, two switches.

Two consequences, both wanted:

- `RATE_LIMIT_ENABLED=false` no longer re-opens the hole this ticket closes.
- The bound is **on in the test suite**, where dozens of contexts set `app.rate-limit.enabled=false`.
  That is deliberate and it is the cheapest possible leak detector: a leaked permit is not silent, it
  makes the fourth *sequential* expensive request in that context fail, so a release bug breaks many
  tests loudly instead of degrading production quietly.

The `app.rate-limit.enabled` comment block in `application.properties` currently says its
"deliberately outside it" list *"now has two entries"*. It becomes three. **Remove the count rather
than incrementing it** — a number goes stale one entry before the list does, and this repository has
shipped that twice.

### 5.5 The startup relation — where the three dials stop being three independently-tuned numbers

A new `common.config.PoolShareConsistency` `@Component`, sibling to `DatabaseTimeoutConsistency` and
deliberately **not** folded into it: that class is about timeouts, and one that also asserted
occupancy would be the naming rot `CLAUDE.md` records for `LockTimeout`.

It reads the pool's *actual* maximum (`HikariDataSource.getMaximumPoolSize()`, the authoritative
value, not the property string) and asserts, following `DatabaseTimeoutConsistency`'s own split
between a hard ordering rule and a soft sizing judgement:

**Hard — the boot fails:**

1. `max-in-flight-per-principal ≤ max-in-flight`, **when both were typed**. Otherwise the
   per-principal number is dead configuration, which is precisely the shape of the
   lock-versus-statement rule. A typed per-principal ceiling above a *derived* share is clamped down
   to it with a WARN instead — half of that pair was chosen by this application, and a bound that
   exists so one surface cannot take an instance down must not take the instance down.
2. `max-in-flight < poolSize`. Otherwise the expensive surface can hold every connection and the
   bulkhead is not a bulkhead; the property this feature exists to deliver would be absent while
   every document said it was present.

**Soft — one WARN at startup, naming the interactive share left:**

3. `max-in-flight > poolSize × 0.6`. A deployment with a large pool and an operator who knows their
   traffic may legitimately want more; refusing to start would be this file overruling them on a
   number it cannot see the context for. The WARN states the residue in connections, not in percent.

**The property, phrased so it survives every number changing** — this sentence is what goes in the
javadoc, in `docs/self-hosting.md`, and in the failure messages:

> Through the expensive-read surface, no principal may occupy more than
> `app.expensive-read.max-in-flight-per-principal` of a replica's connections and no set of
> principals more than `app.expensive-read.max-in-flight`, so the rest of the API always retains
> `DB_POOL_MAX_SIZE − app.expensive-read.max-in-flight` of them. The per-minute budgets bound
> throughput; they do not bound occupancy and never did.

That replaces — not amends — the `requests-per-minute × statement-timeout-seconds ≤ pool-size × 60 ×
share` block in `docs/self-hosting.md`. The connection-seconds derivation stays exactly once, in the
`ExpensiveReadProperties` javadoc, as the *history*: why a rate bound could not deliver the property
and what the numbers were when it did not.

---

## 6. Edge cases & failure modes

**6.1 The release obligation — the one new way this feature can be worse than the problem.** A leaked
permit is a permanent capacity loss on that replica: the surface degrades to refusing everything until
restart, and the gauge is the only thing that would say so. Every path is enumerated:

- **Normal completion, handler exception, 4xx/5xx from any `@ExceptionHandler`** — Spring calls
  `afterCompletion` for every interceptor whose `preHandle` returned `true`, from
  `HandlerExecutionChain.triggerAfterCompletion`, including when the dispatch ends in an exception.
- **A later interceptor throws** (insights refused by the search budget after the reports
  interceptor took the permit). `applyPreHandle` only advances its index for interceptors that
  returned true, and `doDispatch` triggers completion for those. **Verify this by test rather than by
  reading** — it is the least obvious path and it is exercised on a real product route.
- **Async dispatch.** `afterCompletion` is *not* called when a handler starts async processing;
  `afterConcurrentHandlingStarted` is. There is no async handler on this surface today (the only
  `SseEmitter` in the tree is the notification stream, on a different path), so the correct answer is
  to keep it that way and say so loudly: a category test asserts no handler under the two throttled
  packages returns `StreamingResponseBody`, `DeferredResult`, `Callable` or `SseEmitter`. A future
  streaming report is then a deliberate edit that must also decide what a permit means across the
  async gap, instead of a silent leak. (This is the same class of trap as the SSE/`DispatcherType
  .ASYNC` scar already in `CLAUDE.md`.)
- **Client disconnect mid-response.** The servlet still completes on the worker thread; the write
  fails, the exception propagates, `afterCompletion` runs.
- **`OutOfMemoryError` / thread death.** Not recoverable and not guarded; the process is already lost.

**6.2 A permit is not a connection.** A request may hold a permit while holding no connection (JSON
serialisation, CSV assembly in Java) and may hold a connection under a permit for less than the
permit's life. The invariant is one-directional and that is the useful direction: *at most
`max-in-flight` requests on this surface exist at once, therefore at most `max-in-flight` of them can
be holding a connection.* Do not write the converse anywhere.

**6.3 Interaction with the two refusals that already live here.** The rate 429 is spent first, so a
caller over their minute budget never takes a permit. The statement-budget 422 releases its permit
through `afterCompletion` like any other exception. The three refusals stay separable by status and
`errorType`, and the docs must say which is which — §2.4's table is the canonical form.

**6.4 Fairness.** The surface permit is not fair: whoever asks when one frees gets it. Under sustained
contention a principal can be unlucky repeatedly. Accepted — a fair queue is a queue, and a queue of
Tomcat threads is the failure mode this replaces. The per-principal ceiling is what stops one caller
being systematically lucky.

**6.5 The bound is not a security control.** It bounds capacity, not abuse: a distributed set of
principals still sums to `max-in-flight` of legitimate work, which is exactly what it is for. Abuse
remains the rate budgets' job.

**6.6 Behaviour when the surface is full and the caller holds nothing.** They get
`EXPENSIVE_SURFACE_BUSY`, not `TOO_MANY_IN_FLIGHT`. Getting this backwards would tell a caller with
one request in flight to reduce their concurrency — a refusal prescribing an action its reader cannot
perform, which this project has shipped three times.

**6.7 Test-suite blast radius.** Every existing context now carries the bound (§5.4). MockMvc tests
are single-threaded per class, so nothing legitimate should hit it; anything that does is either a
leak or a genuinely concurrent test that must set its own numbers.

**6.8 Idempotency, optimistic locking, tenancy.** Untouched. Nothing is written, nothing is locked, no
resolution happens before the permit is taken.

---

## 7. DC vs Cloud

**Identical code, identical defaults, no profile override** — the posture of every cap in this
product. A deployment that wants a different share sets the environment variable, visibly, in the one
place an operator reads.

**The per-process scope is not a degradation here, and this is the one sentence to get the right way
round.** For the per-minute budgets, counting per node is a *weakening*: N replicas allow N × the
budget for one user. For an occupancy bound it is **exactly correct against the pool**, because the
pool is per replica too — the ratio `max-in-flight : DB_POOL_MAX_SIZE` is invariant as replicas are
added, so the guarantee "the interactive surface retains four connections on this replica" holds on
every replica without coordination.

**It is not scale-neutral against the database.** One account's aggregate ceiling against a shared
PostgreSQL `max_connections` is `max-in-flight-per-principal × replicas` in the worst case, and the
instance's is `max-in-flight × replicas` — 6 × R, which is the number to check against
`max_connections` when scaling out, alongside the existing `work_mem × nodes × backends` arithmetic
in `.env.prod.example`. A cluster-wide counter would fix a problem that does not exist (the pool is
per replica) at the cost of a shared store on the hot path of every expensive read; rejected, and
recorded so it is not re-proposed as an improvement.

**Wiring checklist** (`dc-cloud-guard`'s list): `application.properties` (four lines + comment block
in house style), `application-dc.properties` / `application-cloud.properties` **no change,
deliberately**, `docker-compose.prod.yml` **no change needed** (the app service carries `env_file:
.env`), `.env.prod.example` (a new commented block beside the `DB_POOL_MAX_SIZE` one, with the
"comment it out, never blank" warning), `docs/self-hosting.md` (config rows + the rewritten relation
+ a `## Upgrading` entry + a `## Contents` entry), `README` **no change — checked** (it enumerates no
variables).

**This release is in the "Releases that change a resource default" class** of
`docs/release-checklist.md`: it changes behaviour on installs whose `.env` says nothing about it, and
the symptom on a small overloaded box is a 429 where yesterday there was a slow 200. All three steps
apply, including the hand-written Release-body line, which is the mitigation and not paperwork.

---

## 8. Data model impact

**None.** No table, no column, no migration. Stated explicitly because "bounded share of the pool"
reads like infrastructure that must be persisted, and it must not be: the state is two integers per
process, the resource is per process, and §7 argues that is the correct scope rather than a
compromise.

---

## 9. Surfaces to change

### 9.1 API

No new endpoint, no changed request or response shape. Two new refusal shapes on **existing**
endpoints — the reports surface, `…/search/**`, `…/filters/**`, `…/storage/projects` — described once
as a property of the expensive-read surface rather than attached to a list of today's paths:

```json
{ "type": "about:blank", "title": "Too Many Requests", "status": 429,
  "detail": "Too many of your requests are running at once — wait for one to finish.",
  "errorType": "TOO_MANY_IN_FLIGHT" }
```

```json
{ "type": "about:blank", "title": "Too Many Requests", "status": 429,
  "detail": "This instance is running as many expensive requests as it can at once. Try again in a moment.",
  "errorType": "EXPENSIVE_SURFACE_BUSY" }
```

Both carry `Retry-After: 1`. The existing budget 429 keeps its wording and its window-derived
`Retry-After`, and gains **no** `errorType` (adding one is a separate, harmless improvement; not
doing it in this change keeps the diff honest — clients discriminate on presence).

`api-docs-sync` updates `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md`: one shared 429
description distinguishing the three refusals in one line each, and the note that `Retry-After: 1`
here means *shortly*, not *the window has a second left*.

### 9.2 Documentation and the propagation checklist

`ThrottleCoverageTest.PROPAGATION_CHECKLIST` is where this list lives after this change; the entries
below are what gets added to it (deliberately not introduced by a count):

- `common/config/ExpensiveReadProperties.java` — the four numbers, the relation, and the
  connection-seconds derivation kept once as history.
- `common/config/PoolShareConsistency.java` — the two hard rules and the WARN.
- `application.properties` — the `app.expensive-read.*` block, **and** the `app.rate-limit.enabled`
  block's "deliberately outside it" list, whose leading count must be deleted rather than
  incremented.
- `.env.prod.example` — the new block, and the `DB_POOL_MAX_SIZE` block, which must now mention that
  a share of the pool is reserved.
- `docs/self-hosting.md` — the rate-limit table rows; the numbered "*A third is…* / *A fourth is…*"
  node-local prose (ordinals that go stale exactly as counts do — prefer a category sentence); the
  `DB_STATEMENT_TIMEOUT_MS` upgrading section, whose `requests-per-minute × statement-timeout-seconds`
  relation and its "tracked as HD-182" sentence are **replaced** by §5.5's property.
- `DatabaseTimeoutConsistency.warnIfTheBoundOutlastsTheWait` — its message says *"raise
  `DB_POOL_MAX_SIZE` (currently the only other dial)"*. That is a **uniqueness claim this change
  falsifies**, in the exact shape `CLAUDE.md` warns about, and it must be corrected in the same
  commit. The same sentence appears in `docs/self-hosting.md` and `docs/observability.md`.
- `docs/api-dc.md`, `docs/api-cloud.md`, `openapi.yaml`, `src/main/frontend/src/api.ts` (the 429
  comments on the search, insights, saved-filter and storage-breakdown callers).
- `docs/project-state.md` — the limiter list.
- `docs/observability.md` + `observability/grafana/provisioning/alerting/rules.yml` — the gauge, the
  two counters, one provisioned rule (below).
- `ops/loadtest/k6/probes.js` + `ops/loadtest/RESULTS-TEMPLATE.md` — **P1's stated prediction is
  inverted by this change.** Both say the prediction *holds* if the entitled principal receives no
  429 while the victim degrades; after this ticket that outcome is a regression and the intended
  result is the opposite. A harness that keeps documenting a prediction the product deliberately
  falsified will be read as the product being wrong.

### 9.3 Observability

- `hamstrack.ratelimit.hit{kind="expensive_read_in_flight"}` and `{kind="expensive_read_surface_full"}`
  — two constants rather than one, following the enum's own rule: a rate on the first is one client
  fanning out, a rate on the second is an under-provisioned instance, and the difference decides what
  to do.
- `hamstrack.expensive_read.in_flight` — a **gauge** of current surface occupancy. This is the number
  that makes the guard legible; without it "is the bulkhead ever full?" is unanswerable.
- One provisioned rule, rate-based rather than `> 0`: sustained `expensive_read_surface_full`
  refusals mean the instance is under-provisioned for its traffic, which is an operator decision
  (raise `DB_POOL_MAX_SIZE` and the share, or add a replica), not a page-at-3am event.

### 9.4 Frontend

- `api.ts`: on `429` with `errorType: "TOO_MANY_IN_FLIGHT"`, retry **once** after the hinted delay,
  then surface. This is the one refusal a correct UI can provoke by mounting a page with parallel
  queries, and a single retry of a read is safe and bounded.
- **Never auto-retry `EXPENSIVE_SURFACE_BUSY`** — retrying a busy shared resource amplifies it. Show
  the sentence with a manual *Try again*.
- **Never auto-retry the budget 429** — retrying it is the abuse it exists to refuse.
- Branch on `errorType`, never on the status alone; three different 429s now share one code. No new
  component, no `DESIGN.md` decision — reuse the existing error banner and the existing search-error
  affordance.

---

## 10. The test that saturates rather than inspects

The AC asks for a demonstration, and the honest answer is in three layers, of which two are in the
suite and one is not. **Say which is which**: this project already carries two known flakes (HD-220,
HD-240) and a third would be paid for by everyone.

### 10.1 Layer 1 — permit accounting, deterministic, no timing race (`ExpensiveReadConcurrencyTest`)

Requests are held in flight by a **test-only `HandlerInterceptor` registered after the throttle
interceptors** that blocks on a `CountDownLatch`. No sleeps, no pool, no clock. Asserts:

1. With `max-in-flight-per-principal=2`, two held requests from one principal and a third from the
   same principal → **429 `TOO_MANY_IN_FLIGHT`, `Retry-After: 1`**, returned in well under
   `acquire-wait-ms + margin`.
2. A **different** principal's request in the same state is admitted — the ceiling is per principal
   and not global.
3. With `max-in-flight=2` and two different principals holding one each, a third principal →
   **429 `EXPENSIVE_SURFACE_BUSY`**, and the detail contains neither a count nor the word
   *administrator*.
4. Releasing the latch returns the permits: a follow-up request succeeds. **This is the leak
   assertion and it is the important one.**
5. Permits are released when the handler throws, when the statement budget answers 422, and when a
   **later interceptor** refuses — the last one run on `POST …/search/insights`, which is the real
   two-interceptor route (§6.1).
6. `POST …/search/insights` holds **one** permit, not two, while it is in flight (read from the
   gauge).
7. **The bound itself costs nothing it is protecting** — the `PermissionResolutionQueryCountTest`
   precedent, asserting a *cost* rather than a behaviour: a refused request issues zero Hibernate
   prepared statements (`Statistics.getPrepareStatementCount()` unchanged) and takes no connection.

### 10.2 Layer 2 — a real pool, both directions (`ExpensiveReadBulkheadSaturationTest`)

This is the "by saturating" clause, and it is affordable because a `@SpringBootTest(properties = …)`
context owns **its own Hikari pool** — `spring.datasource.hikari.maximum-pool-size` in the annotation
sits above the surefire system property (4) that caps every other context, so this class can pin a
pool of **4** and a `connection-timeout` of **1500 ms** without adding pressure to the shared local
PostgreSQL (it uses *fewer* connections than the suite default, not more).

The blocking interceptor here **checks out a real connection from the `DataSource`** before blocking,
so a held request genuinely occupies a permit *and* a connection. With `max-in-flight=2`, pool 4:

- **Positive:** two expensive requests are held (2 permits, 2 connections). A third expensive request
  is refused in milliseconds. An **interactive** request — `GET /api/workspaces` — answers **200**
  from the two remaining connections, well inside the 1500 ms acquisition window.
- **Negative control, and without it the positive proves nothing:** the same scenario in a second
  context with `max-in-flight=4` (the cap disabled in effect) holds four connections, and the same
  interactive request **fails to acquire** within 1500 ms. Two directions, one mechanism, and the
  control fails fast rather than after 30 s because `connection-timeout` is pinned.

Why this should not flake: the context is single-threaded apart from the test's own threads; nothing
else competes for its pool; the blocking is latch-driven rather than time-driven; and the one timing
assertion has a ~1.4 s margin. `@Timeout` on both methods, and the held connections are closed in a
`finally`/`@AfterEach` so a failure cannot poison the context.

### 10.3 Layer 3 — the outcome, and it is not in the suite

**The honest statement:** the suite proves the *mechanism* (10.1) and the *pool consequence at a
scale of four connections* (10.2). It does not and cannot prove "a fully entitled principal no longer
degrades a real instance" — that is a property of a deployment under real load, and this project
already has the instrument and the precedent for it (ADR-0020: capacity is measured on production, in
a declared window, not on a clone). **Probe P1 re-run is the acceptance evidence**, with its
prediction inverted (§9.2), and it is the one criterion in §11 whose artefact is a load-test result
file rather than a test class.

Anyone reading this section for a shortcut: the weaker guarantee, stated plainly, is *the accounting
is right and the arithmetic is checked at startup*. That is strictly more than the current state and
strictly less than the AC's words, and the gap is closed by a measurement, not by a green build.

---

## 11. Acceptance criteria

Each names the artefact that checks it.

**Behaviour**

1. A principal at `max-in-flight-per-principal` receives `429` + `errorType: TOO_MANY_IN_FLIGHT` +
   `Retry-After: 1`, and a different principal is unaffected — `ExpensiveReadConcurrencyTest`.
2. A full surface refuses a principal holding no permits with `EXPENSIVE_SURFACE_BUSY`, whose body
   carries no count, no tenant and not the word *administrator* — `ExpensiveReadConcurrencyTest`.
3. Permits are released on normal completion, handler exception, 422, and refusal by a later
   interceptor; a leaked permit would fail the follow-up assertion —
   `ExpensiveReadConcurrencyTest`.
4. `POST …/search/insights` holds one permit, not two — `ExpensiveReadConcurrencyTest`.
5. A refused request issues no statement and takes no connection —
   `ExpensiveReadConcurrencyTest` (the `PermissionResolutionQueryCountTest` shape).

**Saturation**

6. With the cap on, two held expensive requests leave an interactive endpoint answering 200; with it
   effectively off, the same scenario starves it — `ExpensiveReadBulkheadSaturationTest`, both
   directions.
7. Probe P1, re-run on the declared window, completes; the entitled principal receives refusals of
   the new kind and the victim's `browse` class stays inside its target — a dated
   `ops/loadtest/RESULTS-*.md`, and the harness's stated prediction is edited to match reality
   (`ops/loadtest/k6/probes.js`, `RESULTS-TEMPLATE.md`).

**Coverage & seal**

8. Every handler under `com.hamstrack.report.controller` and `com.hamstrack.search` is behind an
   interceptor that carries the concurrency bound, unless exempt with a written reason —
   `ThrottleCoverageTest.everyExpensiveReadHandlerIsAlsoConcurrencyBounded`.
9. The registered pattern sets are unchanged and still exactly the documented five —
   `ThrottleCoverageTest.theThrottledPathSetIsSealed`, whose failure message carries the extended
   checklist.
10. No handler under those packages returns `StreamingResponseBody`, `DeferredResult`, `Callable` or
    `SseEmitter` — the async-leak tripwire, with a failure message that says what a future async
    report owes the permit.

**Configuration**

11. `max-in-flight-per-principal > max-in-flight` fails startup **when both are typed**, and is
    narrowed with a WARN when the share is derived; `max-in-flight ≥ DB_POOL_MAX_SIZE` fails startup;
    `max-in-flight > 60 % of the pool` logs one WARN naming the connections left —
    `PoolShareConsistencyTest`.
12. `EXPENSIVE_READ_MAX_IN_FLIGHT=` (blank) and `=0` fail startup — `PoolShareConsistencyTest` /
    properties test.
13. `EXPENSIVE_READ_LIMIT_ENABLED=false` removes the bound and `RATE_LIMIT_ENABLED=false` does
    **not** — a two-case test, because the second half is the whole point of the separate switch.

**Docs**

14. Every artefact in §9.2 is edited in the same change, including the two stale-uniqueness sentences
    (`DatabaseTimeoutConsistency`'s "the only other dial", `self-hosting.md`'s connection-seconds
    relation) and the leading count in the `app.rate-limit.enabled` block — reviewed against the
    checklist the seal prints.
15. `openapi.yaml` + both API documents describe three distinct 429s and their `Retry-After`
    semantics — `api-docs-sync`.

---

## 12. Open questions — with the answer I recommend taking

Flagged rather than decided silently, because each changes production capacity behaviour.

1. **`max-in-flight = 6` of a default pool of 10?** → **Take 6.** It leaves four connections the
   expensive surface can never hold, which is more than the interactive mix used at the measured
   45-VU capacity. Lower it to 4 only with a measurement; raising it above 6 needs the pool raised
   first, and the WARN says so.
2. **`max-in-flight-per-principal = 3`?** → **Take 3.** It is one above the largest concurrent burst
   a correct client makes on this surface today (search page mount). Two would be tighter and would
   refuse a legitimate page load whenever the wait expired; four buys nothing.
3. **A 1 s wait, or refuse immediately?** → **Wait.** A waiting thread holds no connection and no
   heap and leaves on its own; refusing immediately turns a UI's own parallel mount into a visible
   error and forces the per-principal number upward to compensate.
4. **Absolute numbers or a share of `DB_POOL_MAX_SIZE`?** → **Absolute**, with the coupling made
   visible by the startup check. A derived share silently changes the expensive surface's capacity
   when the pool is tuned for `work_mem` reasons, and nothing would say so.
5. **Should the existing budget 429 gain an `errorType` too?** → **Not in this change.** Clients
   discriminate on presence; adding it is a small, separate, harmless improvement and mixing it in
   makes this diff's API surface larger than its subject.
6. **Should the write surface get an occupancy bound?** → **No, not now** (§3). The primitive is
   there when evidence arrives.
7. **Should `REPORTS_REQUESTS_PER_MINUTE` / `SEARCH_REQUESTS_PER_MINUTE` be lowered as well?** →
   **No.** With occupancy bounded they are throughput bounds and no longer load-bearing for pool
   safety; lowering them would degrade the search box for a property this change already delivers.

---

## 13. The highest-risk assumptions, ranked

**First: that every exit path releases its permit.** This is the one assumption whose failure makes
the cure worse than the disease — a leaked permit is a permanent, silent capacity loss on that
replica, and the surface eventually refuses everything until a restart. It is *reducible* rather than
eliminable: §6.1 enumerates the paths, §10.1 asserts four of them, the async tripwire closes the one
Spring genuinely handles differently, the suite-wide switch (§5.4) turns a leak into loud test
failures, and the gauge makes it visible in production. The residual risk is a future path nobody
enumerated, which is why the async assertion is a *category* test rather than a list.

**Second: that three concurrent expensive requests is above every legitimate client burst.** This is
a claim about client behaviour, including clients we have not written — the shape of HD-151's own
highest-risk assumption, which was a claim about other people's data. If it is wrong the symptom is
ugly and looks like a bug: a page 429s its own load. Mitigated by the acquire wait (a burst
serialises instead of being refused), by the single client-side retry, and by the fact that the
remedy is one `.env` line. The one measurement worth taking before merge, if any is: watch
`hamstrack.expensive_read.in_flight`'s maximum in production for a day at a deliberately high
`max-in-flight-per-principal`, and set the ceiling above what real clients were observed to reach.

---

## 14. Architectural decisions

One, and it is the fork a future contributor will ask about — *why is there no second connection
pool?*

**ADR-0030 — the expensive-read surface is bulkheaded logically (a counted share of the one pool),
not physically (a second `DataSource`).** Chosen because a counter lives in the interceptor chain
that already answers "is this handler expensive", a question sealed by an existing test, while a
second pool would duplicate the transaction manager's bounds, the Flyway boundary, the metrics, the
`max_connections` arithmetic and the test-suite cap — and would make "which pool did this run on" a
list somebody maintains. Rejected alternatives: a second physical pool (`AbstractRoutingDataSource` or
a second `EntityManagerFactory`); a per-principal semaphore alone (fails the interactive-traffic half
of the AC); a surface-wide bulkhead alone (fails the per-principal half); lowering the rate budgets
(a rate is blind to how long work takes, so its protection evaporates exactly when needed); a
cluster-wide in-flight count (the resource is per replica, so a shared store on the hot path would buy
nothing). Trade-off: the expensive surface can starve itself under contention and refuses in
milliseconds instead of degrading everything on the replica after 30 s.

Drafted at `docs/adr/0030-logical-bulkhead-over-shared-pool.md`, `Status: Proposed`.
