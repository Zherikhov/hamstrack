# ADR-0034: The connection-acquisition bound is a queue-wait budget, not a derivative of the statement budget

Record date: 2026-09-05
Status: Accepted
Source: HD-233 (`spring.datasource.hikari.connection-timeout` is not set, and it is the only bound on
three harms at once); `docs/design/database-timeout-family-proposal.md`; ADR-0021 §"connection-timeout
is deliberately NOT tied" — the same question raised for the third time and deferred; HD-151
(`docs/design/statement-timeout-proposal.md` — the bound on a statement, whose third premise is
deleted here); ADR-0030 / ADR-0031 (the occupancy bulkhead, without which this decision would be
reckless); HD-207 / HD-208 (the queue residue at shutdown and the timing oracle on
`forgot-password`); the HD-186 measurement — `ops/loadtest/RESULTS-2026-08-31.md`

## Context

`spring.datasource.hikari.connection-timeout` has never once been set in the whole history of the
project, so every connection acquisition is bounded by Hikari's default — 30 s. That number was **not
chosen**, yet it was cited as a fact about the world in five configuration classes
(`StatementTimeoutProperties`, `DatabaseTimeoutConsistency`, `ExpensiveReadProperties`,
`MailAsyncProperties`, `PlanningProperties`), and it remained the only bound on three harms that three
different tickets point at: parking a Tomcat worker on an unauthenticated endpoint; an
address-correlated timing signal on the known `forgot-password` branch; and writing the queue residue
at shutdown, whose budget inside the stop grace makes sense only while the wait is short.

Lowering it on its own was impossible, and that was established not by reasoning but by two attempts
(HD-207/HD-208) and an independent check against the sources. Two rules were in the way:
`StatementTimeoutProperties` derives its 10 s as "roughly a third of those same 30 s", and
`DatabaseTimeoutConsistency.warnIfTheBoundOutlastsTheWait` logs a WARN as soon as the statement budget
exceeds half the wait budget. That is, 3 s gives a WARN at every start on defaults, and the only honest
way to silence it is a statement budget of 1.5 s — below the hard minimum of 2×
`DB_LOCK_TIMEOUT_MS`. The shortest consistent bound came out at about 20 s, and it bounds
nothing useful.

HD-233's key finding: **the premise of the "pool turnover" rule is wrong and always was.** It assumes
that one statement ≈ one connection hold. In this tree that is refuted by two files written earlier:
`BoundedJpaTransactionManager` ("a transaction of 100 statements at half the budget each holds the
connection fifty times longer") and `PlanningProperties` (the worst hold of a single planning
aggregate is ~320 s against 30 s of waiting for everyone else). The rule certified a property the code
did not give — that is, it was worse than its absence.

## Decision

**The connection-acquisition bound is set explicitly and derived from the queue wait, not from the
statement budget. A saturated pool sheds load with a named retryable refusal rather than absorbing it
by parking.**

The family is three numbers, each with its own single scope:

- `app.locking.lock-timeout-ms` = **3000** (unchanged) — how long a transaction waits for a **row**;
- `app.persistence.statement-timeout-ms` = **10000** (unchanged) — how long one statement
  **runs**; no less than 2× lock, because it counts the lock wait too;
- `spring.datasource.hikari.connection-timeout` = **3000** (new, `DB_CONNECTION_TIMEOUT_MS`) —
  how long a request waits for a **connection**; no less than lock, because a connection held by a
  transaction that is waiting for a lock is legitimately unavailable for exactly that long.

None of the three is derived from the pool size, and the acquisition bound is not derived from the
statement budget in either direction. The clause "roughly a third of 30 s" is **deleted**, not
recomputed: it was not a bound but a reading of an unchosen number, and it is what closed the family
into a circle.

`POOL_TURNOVER_SHARE` is deleted. It is replaced by: the hard rule "0 is forbidden" (Hikari turns 0 into
`Integer.MAX_VALUE`, that is, into the absence of a bound, and it is the only member of the family for
which Hikari will not refuse on our behalf — below 250 ms it throws, at 0 it silently agrees); the hard
rule "drain + acquisition + commit + residue ≤ stop grace", which moves the `RESIDUE_WRITE_FIXED_MS`
literal onto the number that actually decides; and two soft rules — "acquisition ≥ lock" and
"statement ≤ stop grace".

A failed acquisition stops being a nameless 500 and becomes a **503 + `Retry-After: 1` +
`errorType: DATABASE_BUSY`**. A 5xx is right here for exactly the reason it was wrong for
`STATEMENT_BUDGET_EXCEEDED`: that refusal is not retryable (an identical retry costs identical time, and
an intermediary's auto-retry amplifies the harm), whereas this one is retryable by construction — the
obstacle is somebody else's transaction, which will end, and one retry costs one acquisition attempt,
not a run of an expensive query.

## Alternatives

1. **Leave it unset (30 s).** That is not a choice but the absence of one; and it is the only bound on
   the three named harms. Rejected by the very fact that the ticket exists.
2. **Obey `POOL_TURNOVER_SHARE` and take ~20 s.** Consistent and useless: 20 s of worker parking is
   the same harm at the same price, the timing oracle on `forgot-password` stays almost as it was,
   and the shutdown budget can still be overrun by a single connection acquisition.
3. **Rebuild the whole family downwards: lock 500 / statement 1000 / acquisition 2000.** Expressible
   (`lockTimeoutMs` has its own `@Min` equal to 100) and unshippable: 1000 is the **floor** of the
   `statement-timeout-ms` range, below the measured median of an expensive read (3.92 s, p95 16.26 s,
   probe P1) and below the cost of the product's single large **write** statement (clearing the
   assignee from every issue in the workspace when a member is deleted), whose calling side cannot
   narrow anything. Trading 30 s of parking on one endpoint for 422s across the whole product is not a deal.
4. **A second physical pool for the mail path.** Rejected for ADR-0030's reasons, which have not changed:
   `BoundedJpaTransactionManager`, `DatabaseTimeoutConsistency`, the Hikari metrics, the pool cap in
   `pom.xml` and the `work_mem × nodes × backends` arithmetic all assume one pool per replica, and
   routing turns the question "which pool did this run on" into a list somebody maintains.
5. **Close the timing oracle by lowering the number.** It does not work and is therefore not an argument
   for any value whatsoever: 3 s is distinguishable at n = 1 over the internet, 250 ms (Hikari's floor)
   at a modest n on a decent link. Conditional parking on one branch does not become unmeasurable by
   being shortened. The oracle is weakened tenfold and **not closed**; closing it takes a structural
   change (a symmetric bounded cost on the unknown branch, or moving the dead-letter write off the
   committing thread — that is rejected alternative 4 from ADR-0021).

## Consequences

- **Saturation stops degenerating into latency and starts degenerating into refusals.** An installation
  that used to survive a peak by standing in the queue for four seconds now shows errors during the
  peak — and on an endpoint that is not itself slow. This is the decision's main trade-off and the
  release's main risk; the usual remedy is `DB_POOL_MAX_SIZE`, not this variable, and the release note
  names them in exactly that order.
- **This is admissible only because the occupancy bulkhead already exists.** Before HD-182 (ADR-0030/0031)
  nothing prevented one principal from occupying the whole pool, and a short acquisition bound would have
  turned that into an instant refusal to the whole instance. The guarantee "`DB_POOL_MAX_SIZE −
  EXPENSIVE_READ_MAX_IN_FLIGHT` connections always remain for the rest of the API" is a premise of this
  ADR, not a neighbouring improvement. Weakening the bulkhead is obliged to revisit this number too.
- **There are now four refusals meaning "busy", and they differ by `errorType`, not by code.**
  `409 + Retry-After` — somebody else's transaction is holding the row; `429 EXPENSIVE_SURFACE_BUSY` — the
  instance is doing as much expensive work as it can; `503 DATABASE_BUSY` — a connection could not be
  acquired; `422 STATEMENT_BUDGET_EXCEEDED` — the query itself is too expensive, and an identical retry
  will fail identically. The fourth and the third are obliged to be described next to each other,
  otherwise the next reader will "harmonise" their codes.
- **The 503 lands in the `HighErrorRate` alert, and that is right** — pool exhaustion is an incident.
  Exactly the opposite property of the 422 (it could not land there) was the reason for giving it a rule
  of its own.
- **Five sentences in the configuration classes lose their number, but not in the same way.** Three become
  a derivation (a phrasing about a category that survives any value), one is deleted together with the
  paragraph that described an already closed gap, and **one keeps its number** — because it is a dated
  measurement (probe P1 of 2026-08-31), and rewriting a measurement to fit a new value means forging the
  witness. The rule: a rule is written without a number, a measurement in the past tense and with one.
- **Flyway shares the datasource and inherits this bound — and here that is harmless, unlike the two
  neighbouring bounds.** `LockTimeout` and `BoundedJpaTransactionManager` explain in detail why the
  datasource is the wrong place for a bound; both speak of bounds on **duration**, where migrations
  legitimately run for minutes. This one bounds **acquisition**, and Flyway takes its connections at
  startup from an empty pool. Recorded explicitly, otherwise the next reviewer will "fix" this with a
  second datasource.
- **The bound is honest only together with `validation-timeout`.** Checked against `HikariConfig`:
  `validationTimeout` (5000 by default) is not tied to `connectionTimeout` in either direction, while the
  liveness check of a taken connection runs on it — that is, at a bound of 3 s one `getConnection()`
  costs ~3 s + 5 s in the worst case. Both lines read one variable; this is not a fourth number but one
  and the same number written twice.
- **The test suite gets this number, not an override.** The pool cap in `pom.xml` is deliberately
  **not** supplemented with an acquisition bound: the suite must run the value that ships. As a side
  effect a connection leak turns from "the suite is somehow slower" into a loud 503.
- **The "0 is forbidden" rule is reachable only because the check runs BEFORE the pool is created.**
  Measured at build time, not inferred: without an explicit `depends-on` the datasource is built first
  (`EntityManagerFactory` requires it), and `DB_CONNECTION_TIMEOUT_MS=0` brings the start down on the
  binding of **`validation-timeout`** — "validationTimeout cannot be less than 250ms", that is, about a
  property the operator did not set, and about the wrong problem (`setValidationTimeout` has no zero
  case, whereas `setConnectionTimeout` does). So `DatabaseTimeoutConsistency` adds a `depends-on` edge
  through a `BeanFactoryPostProcessor` — the same mechanism by which Boot puts Flyway before the readers
  of the schema. A refusal that fires only in a configuration nobody runs is faith, not a guard.
- **The hard rule about the stop grace gives the acquisition bound a CEILING that not one `@Max`
  declares.** On the mail defaults (drain 15 s, queue 100, grace 30 s) what remains for connection
  acquisition is `30000 − 15000 − 1000 − 100 = 13900 ms`, and anything above that stops the start.
  Reachable by accident: it is exactly the operator who read the release note, decided "better to wait
  than to refuse", and took the former 30 s. The refusal is right — 30 s of acquisition physically does
  not fit inside a 30 s grace — and therefore is obliged to name all four knobs, including
  `APP_STOP_GRACE_SECONDS`, which is what buys that wait. Recorded in `.env.prod.example` and
  `docs/self-hosting.md`: the bound an operator arrives at by following the release note is one he must
  meet earlier than a refusal to start.
- **The 503 is answered by the exception handler, and the exception handler sees only what the handler
  threw.** An acquisition that failed BEFORE the dispatcher — the user lookup in
  `JwtAuthenticationFilter`, that is, on every authenticated request — flies past every
  `@ControllerAdvice` and stays a 500 from the container. This is recorded in the handler's javadoc and
  in a test; it is closed by a filter, not by a handler, and that is a separate change.
- **ADR-0021 is partially superseded and not rewritten.** Its paragraph saying that `connection-timeout`
  is deliberately not tied remains a true record of the constraint in force at that moment, and remains
  a witness that the question was raised three times. One status line changes — a pointer to here.
