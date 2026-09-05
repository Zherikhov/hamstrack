# The database timeout family: lock, statement, acquisition (HD-233)

**Status:** proposal / design review. Written to be built from, so it is arranged as decisions rather
than as a survey: §4 fixes the three numbers and the chain they come from, §5 says what the chain
costs, §10 says which of the five load-bearing javadoc sentences becomes a derivation and which
stays, §12 lists what is genuinely the owner's to choose, and §14 names the assumption most likely to
be wrong.

**Related:** `docs/design/statement-timeout-proposal.md` (HD-151 — the bound this completes),
`docs/design/expensive-read-concurrency-proposal.md` + ADR-0030/ADR-0031 (the occupancy bulkhead,
without which none of this is safe), ADR-0021 (HD-207/HD-208 — where the problem was written down and
deferred, three times), `docs/design/flyway-squash-procedure.md` §15 (the shape of §12 here).

---

## 1. Problem & goal

`spring.datasource.hikari.connection-timeout` is unset, so every connection acquisition in this
application is bounded only by Hikari's 30 s default. That single unchosen number is quoted as a
load-bearing premise in **five** configuration classes, and it is the sole bound on three surviving
harms that three separate tickets now point at:

1. an unauthenticated `POST /api/auth/register` or `/api/auth/forgot-password` can park a Tomcat
   worker for up to 30 s inside its after-commit effect, when the mail queue is full **and** the
   database is the slow thing;
2. that park lands on the **known-address branch of `forgot-password` only**, so it is an
   address-correlated timing signal — a re-amplification, through a different resource, of the very
   oracle ADR-0021 exists to close;
3. it is the one thing that can make the shutdown residue write outrun its stop-grace budget, which
   `MailAsyncProperties.Async` acknowledges in prose and cannot assert against.

(The fourth harm ADR-0021 names — the application-wide stall from `inEffect=1 → inRequiresNew=2` —
was closed by `FailedEmailWriter.poolIsStarved()` and is not re-opened here.)

The number **cannot be lowered on its own**, and that is the actual content of this ticket. Two
existing rules bind it: `StatementTimeoutProperties` derives its 10 s default as "roughly a third of
Hikari's 30 s", and `DatabaseTimeoutConsistency.warnIfTheBoundOutlastsTheWait` WARNs whenever the
statement bound exceeds **half** the acquisition bound. So an acquisition bound of 3 s WARNs on every
boot at the shipped defaults, and silencing that honestly needs a 1.5 s statement bound — below
`check()`'s hard floor of `2 × DB_LOCK_TIMEOUT_MS`. The three numbers are a family and have to move,
or explicitly not move, together.

**Goal.** All three bounds carry a stated derivation that lives beside them; the acquisition bound is
chosen rather than inherited; `DatabaseTimeoutConsistency` passes silently at the shipped defaults
because the rules it enforces are true, not because a rule was routed around; and the three harms
above are re-measured against the new numbers with the outcome recorded, including the one that is
**not** closed.

**Success looks like:** an operator reading any one of the three properties finds a derivation that
survives the other two changing, and a starved pool answers a named, retryable refusal in seconds
instead of parking a worker for half a minute.

---

## 2. Scope

**In scope**

- One new shipped value: `spring.datasource.hikari.connection-timeout`, with an env var and the full
  wiring checklist.
- A re-statement of `DatabaseTimeoutConsistency`: one hard rule kept, one soft rule **deleted**, two
  hard rules and one soft rule added.
- A named refusal for a failed connection acquisition (today a bare 500).
- `spring.datasource.hikari.validation-timeout`, which is not a fourth decision but the same number
  spelled twice (§5.4).
- The javadoc/doc propagation: five configuration classes, `FailedEmailWriter`, ADR-0021, two test
  classes, `.env.prod.example`, `docs/self-hosting.md`, the observability rule set, and four frontend
  comments that quote the 30 s.

**Out of scope, named so nobody reads the shipped family as covering them**

- **Bounding how long a connection is HELD.** Nothing here changes that. `statement_timeout` bounds
  one statement; one planning aggregate holds one connection across `12 + N` statements (32 at
  `AGILE_MAX_OPEN_SPRINTS=20`, a ~320 s worst case) and a report CSV assembles its body in Java while
  the transaction is open. The control for that is the occupancy bulkhead (ADR-0030/0031), which
  already exists, and `idle_in_transaction_session_timeout`, which deliberately does not.
- **Closing the `forgot-password` timing oracle.** This attenuates it by 10× and does not close it;
  §6.2 gives the arithmetic and §12 D-6 files the follow-up. A release note that claims closure would
  be false.
- Re-sizing the statement bound downward. §4.3 rejects it explicitly and decisively.
- Any change to the mail retry/dead-letter policy, the rate limiters, or the bulkhead shares.

**Non-goals**

- This does not make the pool exhaustion-proof. It converts "everybody parks for 30 s in
  self-amplifying waves" into "everybody sheds in 3 s with a named status", which is a different
  failure, not the absence of one.

---

## 3. Actors & permissions

None. No endpoint, no role, no workspace scoping, no tenant-visible resource, no data.

Three tenancy-adjacent notes, because "no permissions" is not "nothing to check":

- **The new refusal must disclose nothing.** It says the instance is busy and that a retry is
  appropriate. It must not carry a pool size, a queue depth, a tenant id, SQL, or a table name —
  `hikaricp_connections_pending` is an operator metric and belongs in the log and the metric store,
  not on the wire.
- **The bound must not vary by tenant, plan or workspace**, for the reason `LockingProperties` and
  `ExpensiveReadProperties` both state: a per-tenant resource bound is a licence check wearing a
  resource guard's clothes.
- **The refusal is reachable unauthenticated** (`/api/auth/*` opens transactions), so its body is
  read by anonymous callers. It must be identical for every caller, and in particular identical on
  both branches of `forgot-password`.

---

## 4. The three numbers, and the chain they come from

### 4.1 The shipped family

| Property | Env var | Value | Moves? |
|---|---|---|---|
| `app.locking.lock-timeout-ms` | `DB_LOCK_TIMEOUT_MS` | **3000** | no |
| `app.persistence.statement-timeout-ms` | `DB_STATEMENT_TIMEOUT_MS` | **10000** | no |
| `spring.datasource.hikari.connection-timeout` | `DB_CONNECTION_TIMEOUT_MS` | **3000** | **new** |

Two of the three keep their values, and **that is a decision, not an omission** — AC-1 asks for the
three to be set together from one derivation, and a derivation that concludes "these two were already
right, for reasons that have nothing to do with the third" is a stronger answer than three new
numbers. What changes is that all three are now *chosen*, and that the derivation lives in one place
(`DatabaseTimeoutConsistency`) with the property javadocs pointing at it rather than each restating a
version of it.

### 4.2 The derivation, one clause per constraint

**The lock bound — 3000 ms.** Its constraint lives entirely inside the database and this change does
not touch it: it must be comfortably longer than the whole locked section takes uncontended (a
handful of indexed reads, one bulk `UPDATE`, one batched insert — `LockingProperties`), short enough
that a pathological pile-up drains rather than exhausts the pool, and non-zero because PostgreSQL
reads `lock_timeout = 0` as *disabled*. Nothing about the connection pool appears in that list, so
nothing here re-derives it.

**The statement bound — 10000 ms.** It has exactly two surviving constraints, and both are properties
of the workload rather than of the pool:

- **≥ 2 × the lock bound.** Mandatory. `statement_timeout` measures total statement time *including*
  the lock wait, so the smaller bound always fires first; at or below the lock bound the retryable
  `409 + Retry-After` contract silently becomes a `422` that forbids the retry which would have
  worked. The `2×` margin is so that a transaction which waited nearly its whole lock budget still
  has as much time again to do its work.
- **~100× an ordinary request**, so it fires on pathology rather than on a cold cache.

Its **third** clause — *"roughly a third of Hikari's 30 s `connectionTimeout` (unset, so the default
applies)"* — was never a constraint on this number at all. It was a *reading* of a number nobody had
chosen, written into the derivation of a number that had been chosen, and it is the single clause
that made the family circular: the statement bound was justified by the acquisition bound, and the
acquisition bound was then forbidden from moving because the statement bound depended on it. **It is
deleted, not re-derived at the new scale.** Deleting the unset default deletes the clause; it does
not change the value, because the value never came from it.

**The acquisition bound — 3000 ms.** It is a **queueing** budget, not a database budget, and that is
the whole reason it can be short while the statement bound stays long. A thread waiting for a
connection does no work: it holds a Tomcat worker, and inside an `AfterCommit` effect it holds a
connection as well (measured, not reasoned — `FailedEmailWriter`'s probe reads
`inEffect=1 → inRequiresNew=2`). Its bounds:

- **Floor: the longest hold this product actually bounds, which is a lock wait —
  `app.locking.lock-timeout-ms`.** A waiter that gives up sooner than a holder is *entitled* to wait
  converts ordinary row contention into connection-acquisition refusals, i.e. it takes a condition
  the product answers with a retryable 409 and re-labels it as a pool incident. 3000 is the smallest
  value that meets this floor at the shipped lock bound.
- **Ceiling: the price of a parked worker.** This product already prices a request parked on an
  internal queue — `app.expensive-read.acquire-wait-ms`, `@Max(2000)`, argued as "~2 thread-seconds
  per refused request" because a waiter holds a Tomcat worker. A connection waiter is *strictly more
  expensive* than that one (it may hold a connection too) and *strictly rarer* (it queues only when
  the pool is genuinely full, where the expensive-read wait is reached by an ordinary parallel page
  mount), so the same worker budget buys a higher per-occurrence cost. 3000 is one and a half times
  that price, not an order of magnitude above it. The ceiling is a sanity check; the floor is what
  picks the number.
- **Hikari's own floor: 250 ms**, verified against `HikariConfig`: `setConnectionTimeout` throws
  `IllegalArgumentException` below `SOFT_TIMEOUT_FLOOR` (250). Never binding here, and named because
  a future tightening would meet it.

So: **3000 / 10000 / 3000**, and the relation that survives all three changing is —

> The lock bound is how long a transaction may wait for a **row**; the statement bound is how long one
> statement may **run**, and must be at least twice the lock bound because it counts that wait; the
> acquisition bound is how long a request may wait for a **connection**, and must be at least the lock
> bound because a connection held by a lock-waiting transaction is legitimately unavailable for that
> long. None of the three is derived from the size of the pool, and the acquisition bound is not
> derived from the statement bound in either direction.

### 4.3 Why the statement bound is not re-sized down — rejected decisively

The fully re-sized family ADR-0021 names as expressible (lock 500 / statement 1000 / acquisition
2000) is legal — `lockTimeoutMs`'s `@Min` really is 100 — and it is wrong to ship.

`statement-timeout-ms` has `@Min(1000)`, so 1000 is the **floor of the range**, and the product's own
measurements sit above it: `ops/loadtest/RESULTS-2026-08-31.md` P1 measured a 3.92 s median and a
16.26 s p95 for an expensive read, and the single largest *write* statement in the product (the
workspace-wide unassign on member removal) is O(tenant data) with nothing the caller can narrow.
HD-151's own §14 already names "10 seconds is above every legitimate statement on every existing
install" as the highest-risk assumption in that feature; a 1 s bound makes the same assumption
near-certainly false on every install with real data, on a *write* path, with no remedy the caller
can apply. It would trade a 30 s park on one unauthenticated endpoint for 422s across the product.

The move that makes a short acquisition bound available is not shrinking the statement bound. It is
deleting the false relation between them (§4.4).

### 4.4 `POOL_TURNOVER_SHARE` is deleted, and the concern it protected is re-homed

`DatabaseTimeoutConsistency.POOL_TURNOVER_SHARE = 0.5` encodes: *the statement bound must not exceed
half the acquisition bound, so a saturated pool turns over inside the window a waiting request will
wait.* Its premise is that one statement ≈ one connection hold. **That premise was never true in this
product**, and two files already say so in as many words:

- `BoundedJpaTransactionManager`: *"A transaction of 100 statements at half the budget holds a
  connection for fifty times it."*
- `PlanningProperties`: *"the worst-case connection hold for one planning aggregate is ~320 seconds
  against a default pool of 10 and a 30 s `connectionTimeout` for everybody else."*

320 s against a 30 s wait is the same check certifying a turnover that cannot happen. So the rule was
not merely inconvenient — it warranted a property the code did not deliver, which is the shape this
codebase treats as worse than no check at all. **Delete it.**

Two things replace it:

- **Soft rule A (new WARN): `connection-timeout ≥ lock-timeout`.** Same register as the deleted rule
  — a sizing judgement, not a contract, so it warns rather than refusing the boot. It fires on the
  operator action that genuinely invalidates the acquisition bound: raising `DB_LOCK_TIMEOUT_MS`
  without raising the acquisition bound with it. Silent at the shipped 3000/3000.
- **Soft rule B (new WARN): `statement-timeout ≤ app.mail.async.stop-grace-seconds`.** This carries
  the *concern* the deleted rule protected — a very large `DB_STATEMENT_TIMEOUT_MS` letting one
  request hold one connection for minutes — onto an anchor that is real instead of circular: a
  statement that outlasts the grace the platform gives the process cannot finish inside a shutdown
  anyway, so a bound above the grace is a bound the environment does not honour, and its remedy is
  nameable (`APP_STOP_GRACE_SECONDS`, or the acceptance that a deploy kills it). Silent at
  10000 ≤ 30000; fires at 60000 and 300000, which are exactly the values `docs/self-hosting.md`
  already labels diagnostic. This is better guidance than "anything above 15000 warns", which is what
  the deleted rule produced.

The residual — a long statement bound letting the *write* surface (which is not on the bulkhead) hold
connections — is not left unattended: the expensive-read surface is bounded by ADR-0030/0031, and
`ExpensiveReadConcurrencyLimit.maxPermitAgeMs()` already derives its watchdog as
`statementTimeoutMs + 60 s`, so the statement bound drags its own occupancy backstop with it. **That
method is the exemplar for everything §10 asks for**: a number that moves with the bound an operator
actually tuned, rather than a second number to keep in step.

### 4.5 Two new hard rules

**H-1 (kept, unchanged):** `statement ≥ 2 × lock`, boot failure, message unchanged.

**H-2 (new): refuse `connection-timeout = 0`.** Hikari's `setConnectionTimeout` maps `0` to
`Integer.MAX_VALUE` — approximately 24.8 days, i.e. *unbounded*. That is the identical
"plausible-looking zero" trap `@Min` refuses for the other two members of the family, and it is the
only one Hikari cannot refuse for us: below 250 ms it throws, at 0 it accepts silently. The check
belongs in `DatabaseTimeoutConsistency`, which already reads this exact key from `Environment`. The
message must say that 0 means *no bound*, not *no wait*.

**H-3 (new): the shutdown residue write must fit inside the stop grace, acquisition included.**

```
drain + acquisition + commitAllowance + queueCapacity × perRow  ≤  stopGrace
15000 + 3000       + 1000            + 100 × 1                  =  19100  ≤  30000   ✓
```

This moves the term `MailAsyncProperties.Async.RESIDUE_WRITE_FIXED_MS` (a literal `1000`, documented
as "one connection acquisition plus a transaction begin/commit") onto the number that actually
decides it, and it is what closes harm 3 properly rather than by hope. The acquisition property is
not visible inside a nested record's `@AssertTrue`, so the wider check lives in
`DatabaseTimeoutConsistency` (which already injects two property records and the `Environment`);
`isShutdownWithinTheStopGrace()` **stays** as the local, binding-time triple, and the wider message
states that it is the same relation plus the acquisition term, so an operator never gets two
unrelated-looking refusals for one misconfiguration.

### 4.6 The refusal a failed acquisition gets

Today a failed acquisition raises `SQLTransientConnectionException` → Spring
`CannotGetJdbcConnectionException` / `CannotCreateTransactionException` and lands on **no handler** —
verified: `GlobalExceptionHandler` declares 22 handlers and none of them is this, and
`handleQueryTimeout` deliberately declares three narrow types precisely so it does *not* swallow pool
exhaustion. So it is a bare 500 today. At 30 s that was rare enough to leave alone. At 3 s it becomes
the ordinary shape of saturation, so it needs a status.

**`503 Service Unavailable`, `Retry-After: 1`, `errorType: "DATABASE_BUSY"`.**

- **5xx is right here for exactly the reason it was wrong for `STATEMENT_BUDGET_EXCEEDED`, and the
  two must be documented as a contrast so nobody harmonises them.** HD-151 rejected 5xx because
  intermediaries auto-retry and an automatic retry of a statement-budget failure re-spends the whole
  budget. A failed *acquisition* is transient by construction — the obstacle is somebody else's
  transaction, which will end — and one retry costs one acquisition attempt, not a re-run of an
  expensive query. Auto-retry is the correct behaviour, so the status that invites it is the correct
  status.
- **Not 504**: indistinguishable from a gateway timeout on the wire, so an operator cannot tell
  whether the app refused or the proxy gave up (HD-151 §5.4, unchanged).
- **Not 429**: the caller has consumed no budget of theirs, and on `/api/auth/*` a 429 is already the
  per-IP throttle's answer — reusing it there makes an incident indistinguishable from rate limiting
  for both the client's backoff logic and the operator. (The bulkhead's `EXPENSIVE_SURFACE_BUSY` is a
  429 for a superficially similar condition; the difference is that it is a *deliberate* refusal on a
  designated surface, while this one can arrive from any endpoint including an unauthenticated one.)
- **Not 500**: it is a decision rather than a fault, and a 500 renders in the SPA as a crash rather
  than as a sentence. It is also the one status the SPA *retries* (`queryClient.ts`: 422/429/502/503/504
  are never retried, 500 deliberately is, because a 500 is a bug on one request). So leaving this
  condition as a 500 does not merely mislabel it — it makes every open tab re-issue its failed
  queries at the moment the pool is empty, which is why the coverage question below is not cosmetic.
- **Body**: one sentence, no numbers about the pool. *"The instance is at its database capacity right
  now. This is temporary — retry in a moment."* No property name, no env var (those go in the log,
  per HD-151's split), no queue depth.
- **Log (WARN)**: method, mapped pattern, the acquisition bound, and the property/env var to raise;
  plus `DB_POOL_MAX_SIZE`, which is the other dial — phrased as *a* dial, never "the only other one",
  which is the sentence ADR-0030 already had to correct once in this same class.
- **Metric**: `hamstrack.db.connection_acquisition_failed`, tagged method + **mapped pattern** (never
  a raw URI — tenant ids and cardinality). Hikari's `hikaricp_connections_timeout_total` already
  exists and is not a substitute: it says the pool refused somebody, not which route.
- **Consequence to state, because it is the inverse of the 422's**: a 503 *does* reach the
  `HighErrorRate` 5xx alert, and that is correct — pool exhaustion is an incident. The 422 needed a
  rule of its own precisely because it could not.

---

## 5. Edge cases, and what a tighter acquisition bound costs

Named, not asserted away. These are the legitimately-slow-but-fine paths I found.

### 5.1 Flyway — unaffected, and this is the one bound where sharing the datasource is harmless

Flyway shares the `DataSource` and therefore shares the acquisition bound; `LockTimeout` and
`BoundedJpaTransactionManager` both argue at length that the datasource is the wrong place for a
bound, and a reader applying that rule mechanically will try to "fix" this with a second datasource.
They are both arguing about bounds on **duration** (`lock_timeout`, `statement_timeout`), where
Flyway legitimately needs minutes. This one bounds **acquisition**, and Flyway acquires its
connections during startup, from a pool nothing else is using yet. A migration that runs for ten
minutes acquires its connection instantly and then holds it; nothing in this change touches the hold.

**Write this down in the property comment**, or the next reviewer re-opens a settled question.

### 5.2 Application startup — verify, do not assume

Reasoning from `HikariPool.checkFailFast`, boot-time patience is governed by
`initializationFailTimeout` (Boot leaves Hikari's default of 1 ms, i.e. one attempt) and the socket
connect by pgjdbc's own `connectTimeout` (10 s), **not** by `connectionTimeout` — so an application
started before its database should fail no faster than it does today. **This is the one claim in this
document I could not verify from the repository**, and the confirmation is cheap: start the app
against a stopped Postgres before and after the change and compare the time to failure. If it turns
out `connectionTimeout` does govern it, the fix is one explicit
`spring.datasource.hikari.initialization-fail-timeout`, not a larger acquisition bound.

### 5.3 The test suite

- **Do not add the acquisition bound to the surefire `systemPropertyVariables` block.** Said
  explicitly because silence there is indistinguishable from an omission: that block pins
  `maximum-pool-size=4`, `minimum-idle=0` and the two expensive-read numbers, and the shipped
  acquisition bound is a number every context *should* exercise. Pinning it would mean the suite
  never runs the value the product ships.
- **Interaction with the pinned pool of 4.** A context that legitimately holds all four connections
  now fails at 3 s instead of 30. The suite is single-threaded per class (`forkCount` 1, no parallel
  execution) and `open-in-view=false`, so this converts a 30 s hang into a fast, named failure — an
  improvement, and it turns a leaked connection from "the suite is mysteriously slower" into a loud
  503. The first full unfiltered run is the review gate, not a formality (and per HD-265, the class
  count is checked with it).
- `ExpensiveReadBulkheadSaturationTest` / `ExpensiveReadBulkheadControlTest` **keep** their explicit
  `connection-timeout` override. A class whose subject is a starved acquisition must not depend on
  the shipped default. Their javadoc sentence *"rather than in Hikari's default thirty"* becomes
  false and is rewritten (§10).
- **New:** `DatabaseTimeoutFamilyTest`, whose failure message **is** the derivation of §4.2 — the
  `ThrottleCoverageTest` pattern. It pins: the three shipped values, that H-1/H-2/H-3 each refuse a
  boot, that both soft rules are silent at the defaults, and that the family is identical under the
  `dc` and `cloud` profiles.

### 5.4 `validationTimeout` — the acquisition bound is not the bound unless this moves too

Verified against `HikariConfig`: `validationTimeout` defaults to 5000 ms, `setValidationTimeout`
refuses only values below 250, and **nothing anywhere in that class relates it to
`connectionTimeout`**. A borrowed connection idle past `aliveBypassWindowMs` is aliveness-checked
using the validation timeout, so under a 3 s acquisition bound a single `getConnection()` can still
cost ~3 s + 5 s of wall clock in the worst case — precisely the case (a degraded database) where the
bound is supposed to hold.

**Set `spring.datasource.hikari.validation-timeout` from the same variable**
(`${DB_CONNECTION_TIMEOUT_MS:3000}`). It is not a fourth decision; it is the same number spelled
twice, and the alternative is a bound that overruns by 5 s exactly when it matters.

### 5.5 Everything else I checked and found unaffected

- **Attachment upload / S3**: `AttachmentService` is deliberately non-transactional and its blob
  write is outside the database entirely.
- **Report CSV assembly**: holds an idle-in-transaction connection; not an acquisition.
- **The planning aggregate's ~320 s hold**: still ~320 s. What changed is that everybody else sheds
  in 3 s instead of parking for 30 — and the reason that is acceptable rather than reckless is that
  the bulkhead guarantees the interactive surface `DB_POOL_MAX_SIZE − EXPENSIVE_READ_MAX_IN_FLIGHT`
  connections the planning surface can never hold. **Before HD-182 this change would have been
  reckless.** That dependency is the ADR (§13).
- **`FailedEmailWriter.writeAll` at shutdown**: bounded at 3 s instead of 30, which is what makes H-3
  arithmetic instead of hope.

---

## 6. The three harms, re-examined against the new numbers (AC-3)

### 6.1 Harm 1 — the unauthenticated park

An after-commit dead-letter write on a committing thread now waits at most **3 s** for its second
connection instead of 30, and only when `poolIsStarved()` answered false. The worker-seconds an
unauthenticated caller can consume through this path fall by 10×, against a per-IP budget of 15
auth requests a minute that does not change. **Materially reduced; not eliminated** — a park is a
park, and the guard removes the avalanche rather than the park (`FailedEmailWriter#poolIsStarved`'s
own wording, which stays correct).

### 6.2 Harm 2 — the `forgot-password` timing oracle. **The honest answer is: still an oracle.**

The arithmetic.

*Known address*: spends the per-address ceiling, finds the account, mints a `SecureRandom` token,
SHA-256s it, inserts into `password_resets`, commits, and runs an `AfterCommit` effect. If the mail
queue is full and `poolIsStarved()` answers false, `FailedEmailWriter.write` opens a `REQUIRES_NEW`
transaction and parks for up to the acquisition bound.
*Unknown address*: spends the same ceiling, misses the lookup, publishes no effect, returns.

| | today | after |
|---|---|---|
| maximum branch delta | **30 000 ms** | **3 000 ms** |
| conditions | queue full ∧ pool contended ∧ this thread beat the starvation probe's read-to-borrow window (tens–hundreds of µs) | unchanged |

**Is 3 s below anything measurable across a network? No — and not close.** Internet RTT jitter is
tens of milliseconds, and p99 jitter on a poor mobile link is a few hundred; 3 s is one to two orders
of magnitude above that and separable at **n = 1**, over the internet, not merely on a LAN. Shrinking
the number further does not fix this: even Hikari's 250 ms floor is separable at small n over a
decent link, and 250 ms is far below the family's own floor. **A conditional park on one branch
cannot be made unmeasurable by making it shorter.**

What actually bounds sampling here is what `api.ts` and `ForgotPasswordPage.tsx` already say it is —
`app.auth-mail.max-per-recipient-per-window` (5) plus the per-IP `15/min` — and this change does not
improve that by one request. **So the claim in those two files stays exactly as strong as it is
today, with only the number updated**, and the words *"still open"* must remain. The reduction is
real (a 10× smaller signal against an unchanged sampling budget), and it is a reduction, not a
closure.

**Verdict: attenuated 10×, not closed.** Recorded as such in §12 D-6, whose recommended follow-up is
structural (a matched bounded cost on the unknown branch, or moving the dead-letter write off the
committing thread entirely — ADR-0021's own rejected alternative 4, the persistent outbox, is the
shape). **The release note must not claim the oracle is closed.**

### 6.3 Harm 3 — the shutdown residue write. **Closed.**

It stops being "a budget that cannot be guaranteed, paired with a log line that does not depend on
it" and becomes arithmetic checked at startup (H-3, §4.5), with every term a bound value rather than
a hand-copy. The `MailAsyncProperties` paragraph that accepts the gap becomes false on the day this
lands and is rewritten (§10).

---

## 7. Data model impact

**None.** No table, no column, no migration, no entity. Stated explicitly because "database timeouts"
reads like a schema change and is not: all three are session/pool settings, and the guard is entirely
application configuration.

---

## 8. API surface

No new endpoint and no changed request or response shape. One new **status code on existing
endpoints**:

- **`503 Service Unavailable`**, `errorType: "DATABASE_BUSY"`, **with** `Retry-After: 1` — reachable
  from **any** endpoint that needs a connection, including unauthenticated ones.
  **It takes two writers to be that wide, and the second one is not optional**: a
  `@RestControllerAdvice` sees only what a handler raises, so an acquisition that fails before the
  dispatcher — every authenticated request, whose token is resolved to a user inside the filter
  chain — needs a near-outermost servlet filter answering the same status, `detail` and headers. (Not
  the same *document*: the filter's body omits the optional `instance` member, because MVC fills
  that from the request URI and hand-escaping a caller-supplied string into a hand-built JSON body
  is a worse thing to own than an optional member no client here reads.) That half was
  deferred once and pulled back in, because a bare `500` there is not merely unnamed: the SPA
  declines to retry a `503` and deliberately *does* retry a `500`, so the uncovered half was also
  the amplifying one, and the counter the alert is built on counted only the anonymous minority.
  **"Near-outermost" is two relations rather than a preference**: it must be inside
  `AuthRateLimitFilter`, which reads the response status after its own `chain.doFilter` in order to
  refund a token the instance spent on itself, and inside Boot's `ServerHttpObservationFilter`, or
  the refusal unwinds through the observation while the response still says `200` and every
  authenticated refusal is recorded as a success — which would put this ticket's own defect, an
  alert quietest during the incident it watches, back on the framework counter. Both were first
  given the same order constant Boot uses (`HIGHEST_PRECEDENCE + 1`), which resolves by
  bean-registration order and resolved the wrong way; the relations are sealed by a test, not by
  the constant.
  What has no status even so is a failure with no response left to change — an `ASYNC`/`ERROR`
  dispatch, a committed stream, or no request at all (a scheduled job, the shutdown residue write,
  Flyway).
  Document it once as a shared response in `openapi.yaml` and describe it as a property of the API,
  not attached to a list of endpoints (the list would be a list of paths we measured — HD-151 §9's
  argument, one layer up).
- The API docs must separate the refusals that all mean "busy" and are told apart by `errorType`
  rather than by code alone, because each calls for different client behaviour. Written without a
  count on purpose: this list grew by one in the writing of this very section, and a number goes
  stale one entry before the list does.
  `409 + Retry-After` — somebody else holds this row, a retry works;
  `429 EXPENSIVE_SURFACE_BUSY` — the instance is doing as much expensive work as it can;
  `503 DATABASE_BUSY` — the instance could not obtain a database connection in time;
  `422 STATEMENT_BUDGET_EXCEEDED` — this request itself is too expensive, and an identical retry
  fails identically.

`api-docs-sync` owns `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md`.

---

## 9. Frontend impact

Small, and mostly already handled — but not entirely, and the gap is the interesting part.

- `request()` already renders `detail` from a problem detail and falls back to
  `Request failed (<status>)`, so a 503 surfaces as a readable sentence with no change.
- **`apiError.ts` / `queryClient.ts` already model this exact condition** — both carry comments about
  "the rest of the pool exhausted by writes, a Hikari `connectionTimeout` expiring, an edge that gave
  up" as a 5xx. That reasoning is now *realised* rather than hypothetical: the same condition arrives
  as a deliberate 503 with a `Retry-After`. Check whether the query client's retry policy treats 503
  as retryable — it should, and it should honour `Retry-After` rather than its own backoff, exactly
  as it does for the 429s.
- `sprints.tsx` / `sprints.refusal.test.tsx` classify a 5xx on the planning surface as an
  indistinguishable server fault, quoting "Hikari's 30 s `connectionTimeout`". The classification
  stays correct; the number in the comment does not (§10).
- No new component and no `DESIGN.md` decision — reuse the existing error banner.

---

## 10. The five sentences: which becomes a derivation, which stays

This is the part of the ticket most likely to rot, and the rule that decides each case is
`CLAUDE.md`'s: **a claim phrased about a category outlives a claim phrased about a member.** A
sentence that names *30 000* is a member; a sentence that names *the acquisition bound* is a
category. The exemplar to copy is already in the tree:
`ExpensiveReadConcurrencyLimit.maxPermitAgeMs()` returns `statementTimeoutMs + STALE_PERMIT_SLACK_MS`
"so the backstop moves with the bound an operator actually tuned rather than being a second number to
keep in step."

| # | Where | Verdict |
|---|---|---|
| 1 | `StatementTimeoutProperties` — *"ROUGHLY A THIRD of Hikari's 30 s connectionTimeout (unset, so the default applies)"* | **Delete the clause.** Not "make it a derivation" — it was never a constraint on this number (§4.2), and keeping it in any form preserves the circularity that made this ticket necessary. Replace with one sentence phrased over the category: *the acquisition bound is sized against queueing and is not a term in this number; `DatabaseTimeoutConsistency` owns the one relation this bound has, which is to the lock bound.* A future change to the acquisition bound then never re-opens this file. |
| 2 | `DatabaseTimeoutConsistency` — the `30_000` literal fallback and `POOL_TURNOVER_SHARE` | **Becomes the single place the family is derived.** The share is deleted (§4.4); the two new hard rules and two new soft rules land here; the `30_000` constant **stays** but its sentence changes from *"Hikari's own default, applied when nothing overrides it — nothing does in this tree"* to *"Hikari's default, which the shipped `application.properties` now overrides — reaching this fallback means that line was removed."* Renaming it to `HIKARI_DEFAULT_CONNECTION_TIMEOUT_MS` makes the distinction unmissable. |
| 3 | `ExpensiveReadProperties` — *"waited out Hikari's 30 s connectionTimeout and failed, including interactive endpoints"* | **Stays, with only its framing corrected.** This is a **dated measurement** — probe P1, `ops/loadtest/RESULTS-2026-08-31.md`, taken when the bound really was 30 s and unset. Re-parameterising it to the new number would falsify evidence. Change *"Hikari's 30 s `connectionTimeout`"* to *"the pool's acquisition bound — 30 s and unset at the time"*. A measurement is history and is written in the past tense; a rule is a category and is written without a number. This row is the one that shows the two are different obligations. |
| 4 | `MailAsyncProperties.Async` — *"`connection-timeout` is unset, so an acquisition … is below that property's own hard floor of twice `DB_LOCK_TIMEOUT_MS`"* | **Delete the paragraph and replace it with the arithmetic.** The whole passage exists to explain why a gap is *accepted*; H-3 closes the gap, so it would describe a problem that no longer exists — which is worse than a stale number, because it teaches the next reader to stop looking. `RESIDUE_WRITE_FIXED_MS`'s javadoc becomes: this covers the transaction begin/commit; the **acquisition** term is checked against the stop grace by `DatabaseTimeoutConsistency`, which can see both. |
| 5 | `PlanningProperties` — *"against a default pool of 10 and a 30 s `connectionTimeout` for everybody else"* | **Becomes a derivation by losing the number.** The sentence's argument is *the hold is unbounded and everybody else queues behind it*, and the 30 s is incidental colour. Rewrite as *"…against a default pool of 10, while everybody else waits out the pool's acquisition bound and then fails."* True at any value, and no future change to this family touches this file again. |

**Beyond the five.** The grep that found them also finds sentences that need **no** edit — and they
are worth naming as the control group, because they are all phrased over the category: *"Hikari's
`connectionTimeout` bounds only checking a connection out, never a query already running"* in
`LockTimeout`, `BoundedJpaTransactionManager`, `ProjectAdminGuard`, `StatementBoundTest` and
`MembershipLockTimeoutTest`. Five sentences about the same setting that survive it changing, in the
same tree as five that do not. That is the rule, demonstrated.

**The full propagation list** (a builder should treat an unticked box here as an unfinished change):

- `FailedEmailWriter` — four occurrences of "30 s", the four-harm list (harm 3 moves to *closed*,
  harm 2 stays open with a new number), and the "the shortest consistent acquisition bound is around
  20 s" paragraph, which is the argument this ticket answers.
- ADR-0021 — see §12 D-5.
- `PlanningConcurrencyTest` javadoc; `ExpensiveReadBulkheadSaturationTest` javadoc ("Hikari's default
  thirty").
- `ops/loadtest/RESULTS-TEMPLATE.md` — *"Pool exhaustion past the 30 s `connectionTimeout`"* in the
  triage table. `RESULTS-2026-08-31.md` is a dated measurement and is **not** edited.
- `src/main/frontend/src/api.ts`, `pages/ForgotPasswordPage.tsx` — the "bounded only by the unset
  Hikari connection-timeout" clause in both; keep *still open*.
- `src/main/frontend/src/components/sprints.tsx`, `sprints.refusal.test.tsx` — the 30 s in the 5xx
  classification comments.
- `.env.prod.example` — the new block, plus *"Past ~30s, raise the pool too"* (line ~352) and
  *"failed after waiting out the pool's 30s acquisition timeout"* (line ~765).
- `src/main/resources/application.properties` — the new lines, plus *"failed on connection
  acquisition after 30s"* (line ~534).
- `docs/self-hosting.md` — the sizing-WARN block (~1653–1659) and the value table (~1635–1637), whose
  every row is written against the deleted rule.

---

## 11. DC vs Cloud, and the wiring checklist

**Identical in `dc` and `cloud`, no profile override.** This is the posture of every resource bound in
the product (`LockingProperties`, `StatementTimeoutProperties`, `ExpensiveReadProperties`,
`BoardProperties`), and the reason is unchanged: **the environment variable already is the
per-deployment knob**, so a profile default would be a second, invisible one, and the same code would
behave differently in the two modes for a reason nobody can see in the `.env`.

I looked for a genuine asymmetry and did not find one that survives. Cloud has more replicas and more
tenants, so acquisition contention is likelier there; DC is likelier to run one large tenant on small
hardware, so the *cost* of shedding is higher there. Those point in opposite directions and cancel;
neither is a reason for two defaults. **Stated per AC-4: no divergence, and this paragraph is the
reason.**

| Target | Change |
|---|---|
| `src/main/resources/application.properties` | `spring.datasource.hikari.connection-timeout=${DB_CONNECTION_TIMEOUT_MS:3000}` and `spring.datasource.hikari.validation-timeout=${DB_CONNECTION_TIMEOUT_MS:3000}`, placed next to the two existing bounds, with a house-style comment block: what it bounds, what it does *not* (the hold), **why sharing the datasource with Flyway is harmless here specifically** (§5.1), the 503 contract, the zero trap, and the two soft rules. |
| `application-dc.properties` / `application-cloud.properties` | **No change.** Deliberate; see above. |
| `docker-compose.prod.yml` | **No change needed** — the app service already carries `env_file: .env`. |
| `.env.prod.example` | A commented `#DB_CONNECTION_TIMEOUT_MS=3000` next to `DB_LOCK_TIMEOUT_MS` / `DB_STATEMENT_TIMEOUT_MS`, with the family stated once in the block that already introduces the other two, and **the same "comment it out, never blank" rule** — note that here the mechanism differs: a blank binds an empty string that fails conversion, while `0` binds successfully and means *no bound*, which is why the app refuses it. |
| `docs/self-hosting.md` | New config-table row; **rewrite** the sizing-WARN block and the `DB_STATEMENT_TIMEOUT_MS` value table (both are written against the deleted rule); a new `## Upgrading` section; a `## Contents` entry; every added link clicked once on the rendered page. |
| `docs/observability.md` + `observability/grafana/provisioning/alerting/rules.yml` | Metric-reference row for `hamstrack_db_connection_acquisition_failed_total` and a provisioned rule. Rate-based, not `> 0`: a single failed acquisition is one unlucky request. Note in the doc that unlike the 422, this **does** also raise `HighErrorRate`, and that the pair `hikaricp_connections_pending` + this counter is what separates "the pool is tight" from "the pool is gone". |
| `README` | **No change — checked.** It says configuration is via environment variables and enumerates none. |
| `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md` | The 503 as a shared response, plus §8's table of the refusals that all mean "busy" and are told apart by `errorType`. (Written without a count on purpose — §8 says why, and this row said "four" until it disagreed with the section it points at.) |

**Release class.** This is a *"Releases that change a resource default"* change, and more sharply
than HD-151 was: that one turned a slow request into an error, this one can turn a request that is
not slow at all into an error, because the failure lands on whoever asks *while* something else is
slow. The hand-written GitHub Release line is the mitigation, not paperwork.

**The blurb is written and it is NOT here.** It lives in `docs/release-checklist.md`, under the
0.18.0 upgrade notes, and that is the copy to use. The draft this section carried has been deleted
rather than updated, for the reason a draft beside a shipped text always deserves: it is the version
an author reaches for, it had already fallen behind on what the release says, and one of the ways it
had fallen behind was a *factual* error the shipped text does not contain — it claimed `0` is refused
because "PostgreSQL and Hikari both read it as no bound", and PostgreSQL is not a party to the
acquisition bound at all (where it *is* a party, for the lock and statement bounds, it reads `0` as
*disabled*, which is a third meaning again). Two copies of a release note is the same defect as two
copies of a JSON body, and the remedy is the same one: keep one.

---

## 12. Open questions — owner decisions, with a recommendation each

**D-1 — The acquisition bound: 3000, or 2000?** *Recommendation: **3000**.* It is the smallest value
that meets the derived floor (a waiter should not give up before a lock holder's own bound expires),
so it is defensible without appeal to taste. 2000 matches the existing worker-parking price exactly
and buys one further second off the timing oracle — which §6.2 shows does not change the verdict, so
it buys nothing that matters. Pick 2000 only if the owner would rather shed slightly earlier than
absorb contention, and if so, expect and accept the new soft-rule WARN, or lower `DB_LOCK_TIMEOUT_MS`
with it.

**D-2 — Do the lock and statement bounds really stay?** *Recommendation: **yes**, and the ticket's own
example family (lock 500 / statement 1000 / acquisition 2000) is rejected on evidence, not taste
(§4.3).* The counter-argument is that a smaller family is *more* consistent with "shed rather than
queue" everywhere. It is, and it is unshippable: a 1 s statement bound is the `@Min` floor and sits
below the measured median expensive read, on a write path with nothing the caller can narrow.

**D-3 — Delete `POOL_TURNOVER_SHARE` outright, or replace it with soft rule B (statement ≤ stop
grace)?** *Recommendation: **replace**.* Deleting outright is defensible — the rule certified a
property the code never delivered — but it leaves nothing at all warning about
`DB_STATEMENT_TIMEOUT_MS=600000`, and `docs/self-hosting.md` currently teaches operators to expect a
WARN there. Soft rule B keeps that guidance with an anchor that is true. It is new scope relative to
the ticket, which is why it is the owner's call.

**D-4 — Is `503` the right code, given `EXPENSIVE_SURFACE_BUSY` is a `429` for a similar-sounding
condition?** *Recommendation: **503**.* §4.6 gives the four-way argument; the decisive clause is that
this refusal is reachable from unauthenticated `/api/auth/*`, where a 429 already means "you are
being rate-limited" to every client and every operator. If the owner prefers one code for "instance
busy", the cost is that a pool incident and a rate-limit refusal become indistinguishable on the two
most-attacked endpoints in the product.

**D-5 — How ADR-0021 is updated, given `docs/adr/README.md` says an `Accepted` ADR is not
rewritten.** *Recommendation: **add one status line and change nothing else** —
`Частично заменён ADR-0034 (граница получения соединения)` under its `Статус`, leaving every
paragraph intact.* Its `connection-timeout` passage then reads correctly as the record of a
constraint that held at the time, which is what an ADR is for. The alternative — editing the
paragraph to match the new world — is the rewrite the README forbids, and it would destroy the
evidence that the question was raised three times before it was answered.

**D-6 — The `forgot-password` oracle is attenuated, not closed. Ticket it now or accept it?**
*Recommendation: **file it now, with the two candidate shapes named** (a matched bounded cost on the
unknown branch, or moving the dead-letter write off the committing thread onto an outbox — ADR-0021's
rejected alternative 4).* Accepting it silently is what turned this into a three-ticket residue in
the first place; the point of §6.2's arithmetic is that the next reader meets the verdict rather than
the omission.

**D-7 — Should `hikaricp_connections_pending` get an alert of its own?** *Recommendation: **no, not
in this ticket**.* The new counter plus the existing Hikari gauges cover the incident, and an alert
sized without a measurement is an alert an operator learns to ignore. Revisit after the first
production window that produces a `DATABASE_BUSY` at all.

---

## 13. Acceptance criteria

Mapped to the ticket's own five, then broken into checkable items.

**AC-1 — one stated derivation, beside the values**

1. `DatabaseTimeoutConsistency` carries the §4.2 chain as its class javadoc, and each of the three
   property sources points at it rather than restating a version of it.
2. `StatementTimeoutProperties` no longer contains the word `connectionTimeout` in its derivation,
   and no file in `src/main/java` derives one member of the family from the acquisition bound.
3. `grep -c "30 s"` / `"30000"` against the connection pool returns nothing outside dated
   measurements (`RESULTS-2026-08-31.md`) and the renamed Hikari-default constant.

**AC-2 — the check passes, silently, at the shipped defaults**

4. A default boot logs **no** WARN from `DatabaseTimeoutConsistency`. (Assert the absence, with a log
   capture — a check that "passes" because it was routed around looks identical.)
5. `DB_STATEMENT_TIMEOUT_MS=5999` (below `2 × lock`) fails the boot with the existing message.
6. `DB_CONNECTION_TIMEOUT_MS=0` fails the boot with a message saying 0 means *no bound*.
7. `DB_CONNECTION_TIMEOUT_MS=` (blank) fails the boot.
8. `DB_CONNECTION_TIMEOUT_MS=100` fails the boot (Hikari's 250 ms floor), and the failure names the
   variable an operator typed.
9. `DB_LOCK_TIMEOUT_MS=5000` with the default acquisition bound logs soft rule A's WARN and starts.
10. `DB_STATEMENT_TIMEOUT_MS=60000` logs soft rule B's WARN and starts (if D-3 is accepted).
11. A drain/queue/grace/acquisition combination that does not fit fails the boot naming the
    acquisition term (H-3), and the shipped defaults fit with room (19 100 ≤ 30 000).

**AC-3 — the three paths re-examined, outcome recorded**

12. A saturated pool answers `503` + `Retry-After: 1` + `errorType: DATABASE_BUSY` within
    ~`connection-timeout`, on an interactive endpoint, with no pool internals in the body.
13. The WARN and the `hamstrack.db.connection_acquisition_failed` counter fire, tagged by method and
    **mapped pattern**, never a raw URI.
14. The shutdown residue write completes inside the stop grace with the pool contended (the H-3
    arithmetic, exercised rather than asserted).
15. **The `forgot-password` finding is written into the code, not only into this document**: the
    javadoc in `api.ts`, `ForgotPasswordPage.tsx` and `FailedEmailWriter` states the new maximum
    delta, keeps *still open*, and does **not** upgrade the "what bounds sampling is the per-address
    ceiling, not equal work" claim. A test or review gate that catches an upgrade of that claim is
    worth more than one that measures the delta.

**AC-4 — env checklist, and identical in both modes**

16. Every row of §11's table is done, and `dc`/`cloud` resolve the same three values (asserted in
    `DatabaseTimeoutFamilyTest`, not by reading the two profile files).

**AC-5 — the record does not outlive the problem**

17. All five sentences of §10 are edited as that table says, and the "beyond the five" list is
    complete: no occurrence of the unset 30 s survives except in a dated measurement.
18. ADR-0021 carries its pointer line (D-5) and is otherwise untouched; ADR-0034 exists and is
    indexed.

**Non-regression**

19. Pool exhaustion still does **not** answer 422 — `handleQueryTimeout` keeps its three narrow
    types, and the new handler is bound to the acquisition failure alone.
20. `PermissionResolutionQueryCountTest` still passes with 2 / 4 / 0; the family adds no statement.
21. A full unfiltered suite run is green **and** the HD-265 class-count guard passes on it.

---

## 14. The highest-risk assumption, stated plainly

**That 3 seconds is longer than every legitimate connection acquisition on every install, under load
that is not yet an incident.**

Everything else here is checkable by reading code or by a startup test. This one is a claim about
other people's hardware and other people's traffic shape, and it cannot be verified from this
repository. If it is wrong, the failure is loud and wide: an install that today absorbs a daily peak
by queueing for four seconds begins answering 503 across every endpoint at once, including login,
with nothing tying it to the upgrade — and unlike HD-151's failure mode, the request that fails is
not the slow one. That is exactly the release-checklist's *"would the change look like a fault to
somebody who was not told"* test, which is why §11's Release line names `DB_POOL_MAX_SIZE` as the
usual remedy before it names this variable at all.

Two things reduce the blast radius and neither eliminates it. The bulkhead (ADR-0030) guarantees the
interactive surface a reserve the expensive surface can never hold, so the common cause of a four-
second queue in the first place — one principal's reports — no longer reaches the whole pool. And the
remedy is one `.env` line applicable without a code change. **The one measurement worth taking before
merge**, if any is taken, is `hikaricp_connections_acquire_seconds` p99 on production across a normal
business day: if that number is anywhere near 3 s today, this change ships an incident, and the
correct response is to raise `DB_POOL_MAX_SIZE` first and land this second.

---

## 15. Architectural decisions

One, and it is the kind a future contributor will ask "why?" about while looking straight at the
evidence: *why is the connection-acquisition bound three seconds when a single statement may run for
ten?*

1. **The connection-acquisition bound is a queueing budget, independent of the statement bound; a
   saturated pool sheds load with a named, retryable refusal rather than absorbing it by parking.**
   Chosen over: leaving it unset at Hikari's 30 s (rejected — it is not a choice, it is the absence
   of one, and it is the sole bound on three named harms); obeying `POOL_TURNOVER_SHARE` at ~20 s
   (rejected — consistent and useless, bounding nothing that mattered); re-sizing the whole family
   downward to lock 500 / statement 1000 / acquisition 2000 (rejected — the statement bound would sit
   at its `@Min` floor and below the measured median expensive read, on write paths with nothing to
   narrow); a second physical pool for the mail path (rejected for ADR-0030's reasons, unchanged).
   Trade-off: saturation stops degrading to latency and starts degrading to refusals, so an install
   that used to absorb a peak now shows errors during it — acceptable **only because** the occupancy
   bulkhead already guarantees the interactive surface a reserve, which is what makes shedding a
   policy rather than a symptom. → **ADR-0034** (drafted, `Proposed`).
