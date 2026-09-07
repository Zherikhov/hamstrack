# ADR-0021: An overflowed mail queue is a dead-letter, not a send on the request thread

Record date: 2026-09-01
Status: Accepted
Partially superseded by ADR-0034 (the connection-acquisition bound)
Source: HD-208 (an SMTP timeout on a Tomcat thread from an unauthenticated endpoint) and HD-207 (loss
of the queue at shutdown); `src/main/java/com/hamstrack/common/async/AsyncConfig.java` — the rejection
policy; `src/main/java/com/hamstrack/common/mail/UndeliverableMail.java` — the only recording
mechanism; ADR-0015 (mail throttling), HD-78 (retries + the `failed_email` table), HD-76 (SMTP
timeouts), HD-181 (sending after commit), HD-186/HD-189 (the measured capacity of the production box)

## Context

The `mailExecutor` pool was created bounded (HD-78) with a `CallerRunsPolicy`. That was a
**deliberate decision** and, at the moment it was taken, the right one: the queue is full, so the choice
is between "slow the caller down" and "throw the mail away", and a mail on which completing a
registration or recovering a password depends must not be thrown away.

Since then both halves of that comparison have changed.

**Throwing away has stopped meaning losing.** HD-78 gave critical mail retries and the `failed_email`
table. A refusal can now be **recorded**: a dead-letter row is an artefact that outlives the process,
reaches the operator and is fit for re-sending. What we compare is no longer "a slow request against a
lost mail" but "a slow request against a row in a table".

**What backpressure was buying turned out to be a negative quantity.** The pressure fell on Tomcat
threads. At connect 5 s / read 10 s and three attempts with pauses that is up to ~34 s of a worker per
request, and the path is reachable without authentication: `POST /api/auth/register` — 15/min/IP, mail
critical. The mechanism is self-reinforcing: the slower the mail host, the faster the queue fills, the
more workers are occupied with SMTP instead of serving. For this box the price is higher than the
seconds alone imply: the measured capacity (HD-186) is ~45 concurrent users and ~43 rps at 512 MB of
heap and SerialGC, so a held worker is a scarce resource.

As a side effect the same mechanism gave **a timing oracle on `forgot-password`**: with a full queue a
known address cost an inline send with retries, while an unknown one returned instantly, because the
"unknown" branch does nothing. Several seconds of difference on an endpoint whose whole point is that
the two cases are indistinguishable.

HD-181 had already removed the two worst consequences of the same property (the send moved outside the
commit, so it no longer holds a Hikari connection and no longer sits inside `pg_advisory_xact_lock` on
the recipient's address). The request thread remained.

## Decision

**The pool's rejection handler always throws; it never runs the task on the calling thread.**
`MailDispatcher` turns that throw into a record:

- critical mail (verification, password reset) → a `failed_email` row with `attempts = 0` and a
  `last_error` of the form `NEVER ATTEMPTED [<REASON>] — <phrase for a human>`: the enum constant
  in brackets is needed separately from the phrase, because the phrase is written for whoever reads one
  row and the token for whoever parses a hundred (`GROUP BY left(last_error, 40)`), and it is the token
  the `EmailFailures` alert refers to when it asks to tell a spike during a deploy (`SHUTDOWN_RESIDUE`)
  from a saturated pool (`QUEUE_FULL`);
- best-effort (invites) → there is nothing to record, so the exception is propagated further and lands
  in the ERROR line of the `AfterCommit` effect, which has a description carrying the workspace id.

The rule in one sentence: **refuse loudly what cannot be recorded, and record what can.**

The same mechanism closes HD-207. The remainder of the queue that the drain did not reach at shutdown
is returned by `shutdownNow()`, and it goes through the same `UndeliverableMail` with a different
reason. This is one reason why the mail tasks in the queue are `MailTask` and not a `FutureTask` from
`@Async`: both the rejection handler and `shutdownNow()` see only a `Runnable`, and "which mail" can be
recorded only if the task names itself.

## Alternatives

1. **Keep `CallerRunsPolicy`, shrink the queue.** Does not cure it: any non-empty queue still overflows
   under the same scenario, and a small one does so faster. And it makes the normal mode worse: a spike
   of registrations that a healthy host clears in a second starts being discarded.
2. **`CallerRunsPolicy` + a bound on the total time of the inline send.** It gives the "the maximum is
   bounded and named" of HD-208's criteria, but keeps the self-reinforcing path and the timing oracle,
   and adds a second retry budget separate from `app.mail.critical.*`.
3. **`DiscardPolicy` / `DiscardOldestPolicy`.** Exactly the silent loss that `CallerRunsPolicy` was
   chosen because of.
4. **A persistent queue (an outbox table + a worker).** Strictly better, and it is the same
   `failed_email` plus a re-send job. A separate decision: it needs a scheduler, idempotency, a bound on
   a mail's age and an admin surface. The current decision does not stand in its way — a row with
   `attempts = 0` is precisely a candidate for a retry, and it is distinguishable from "we tried and
   could not".

## Consequences

- No request thread makes an SMTP round trip. HD-208's criterion is met in the first of the two
  formulations ("none at all"), not in the second ("bounded and named").
- The timing oracle on `forgot-password` closes as a side effect: both branches are either an enqueue
  or a bounded refusal.
- **A `failed_email` row now means two different things and says which.** `attempts >= 1` — we tried
  and could not; `attempts = 0` + the `NEVER ATTEMPTED` prefix — we did not try at all. For a human and
  for a future re-send job these are not the same thing: an unattempted mail will most likely go out on
  the first attempt. No migration was needed — both columns were already there.
- The metric stays on the existing tag `outcome="failure"`: a send that did not happen is a send
  failure, and the `EmailFailures` alert must fire on it. A new tag would mean a silent loss with the
  counter turned aside.
- `@Async` has been removed from `MailService`. Not a matter of taste: the interceptor puts a
  `FutureTask` into the queue, from which neither the rejection handler nor `shutdownNow()` will recover
  the recipient, and the whole point is to record which mail was lost. The old caveat "`@Async` here
  promises no asynchrony" is no longer needed: now it does promise it. But the reason the send is safe
  with respect to transactions and locks is still the ordering (`AfterCommit`), not the handoff.
- Shutdown has become a triple of related numbers (`app.mail.async.shutdown-drain-seconds`,
  `queue-capacity` and `stop-grace-seconds`), checked at startup. The grace is **a property, not a
  constant**: `APP_STOP_GRACE_SECONDS` is read both by `stop_grace_period` in the compose file and by
  the application. Otherwise a refusal at startup would prescribe an action unavailable to its reader
  (editing a constant in Java, for someone who runs a published image), and lowering the value in
  compose would leave the application confident in the old number — a drift the code could not notice.
- **An invite is now lost when the queue is full, and that is a deliberate trade, not an
  oversight.** Before this decision an invite under saturation went out slowly (by an inline send);
  now it is discarded, the `workspace_invites` row is committed, the endpoint has already answered 201,
  and the only trace is a server-side ERROR the inviter does not see. Recording invites in
  `failed_email` was considered and rejected: the growth of that table is the largest new risk of the
  whole change (see below), and INVITE is the highest-volume best-effort type, so such a "fix"
  would aggravate the more serious problem for the sake of the less serious one. A separate ticket has
  been filed; the dead-letter must not be extended to best-effort mail within HD-208.
- **`failed_email` had to be bounded, and that had not been required before.** A row used to cost one
  exhausted retry cycle — the pool set the write rate. A "not attempted" row costs one
  enqueue, that is, the write rate has become the request rate (and that is bounded per IP, not
  globally). Two limits, because each on its own leaves a hole:
  `app.mail.dead-letter.retention-days` (90, a daily sweep) bounds the table by time,
  `max-never-attempted-per-hour` (500 per instance) by rate. Above the limit the loss stays
  loud (an ERROR with the type and the recipient's domain, plus the ERROR of the `AfterCommit` effect),
  but stops being durable. **What the limit actually bounds is one of the table's two writers,
  not the table**: only the "not attempted" rows and only in this process (N replicas → N × cap into one
  shared table). The "tried and could not" half is deliberately unbounded, and its worst case is
  larger: with a fast-failing SMTP (connection refused, NXDOMAIN — the typical misconfiguration of a
  self-hosted install) a send costs only two backoffs, ~4 s, so five workers write on the order of 4500
  rows an hour — roughly ninefold above the limit. Hence the sweep's failure mode too: it is
  a single DELETE with no index, and past some size it stops fitting into
  `DB_STATEMENT_TIMEOUT_MS`, after which the daily sweep deletes nothing and the table grows
  with no bounds at all. An index on `created_at` is a separate ticket; its trigger is
  `cap × replicas` or a drawn-out outage of a fast-failing SMTP, not `cap` on its own.
- **The rule "what a log line SAYS" has to be checked against "what it CARRIES".** Every
  `log.*` call on this path passes the mail type and the recipient's domain — and the full address
  reached the shipped log all the same, as an ARGUMENT: `ThreadPoolTaskExecutor.execute` wraps the
  rejection into a `TaskRejectedException` whose message is `"… did not accept task: " + task`, and a
  record's generated `toString` prints every component. `MailDispatcher` propagates that exception
  every time nothing has been recorded (best-effort and above the hourly limit), and
  `AfterCommit.runQuietly` logs it together with the stack trace. The redaction lives on
  `MailTask.toString()`, not in what `MailDispatcher` throws: that method is reachable from any code
  that ever logs an enqueued task, including code not yet written.
- **`spring.datasource.hikari.connection-timeout` is deliberately NOT bound, and this is the third time
  the question has come up.** Left unset, it remains the only bound (30 s by default) on
  FOUR harms at once: the parking of a Tomcat worker on an unauthenticated endpoint; the second
  connection of the dead-letter write on the committing thread (taken only for a KNOWN address — the
  `forgot-password` timing oracle again); the write of the remainder at shutdown, whose budget inside
  the stop grace makes sense only while that wait is short; **and a fourth one, which is the real one —
  it is not a delay on the mail path but a halt of the whole application.** The measurement in
  `FailedEmailWriter` (`inEffect=1` → `inRequiresNew=2`) means that the committing thread HOLDS the
  first connection while it parks for the second. With a saturated mail queue, N Tomcat workers inside
  the `AfterCommit` window each hold a connection and wait up to 30 s for another; at
  `DB_POOL_MAX_SIZE=10` and 200 Tomcat threads, ten of those are enough to drain the pool — and then
  ANY connection acquisition in the whole application parks, not only a mail one. The effect is
  self-reinforcing (a parked thread holds the first connection for all 30 s) and self-dissolving in
  waves of 30 s.
  **The fourth harm is closed, not accepted**: `FailedEmailWriter.poolIsStarved()` is a cheap check of
  `HikariPoolMXBean.getThreadsAwaitingConnection() > 0` before the write, on the same pattern as the
  hourly limit: the path is already broken anyway, so durability degrades into visibility rather than
  into silence. What that counter actually means: `ConcurrentBag.borrow` does
  `waiters.incrementAndGet()` BEFORE walking the shared list and decrements the counter in a `finally`,
  so it also catches threads that will get a free connection right away and never park. That is, the
  condition reads as "somebody is already inside the path of acquiring a connection from the pool",
  not "somebody is already queueing" — it overstates, and every overstatement costs one unwritten row.
  Here that is deliberate and cheap: the check is reached only after a send has already been rejected,
  so on a healthy instance it is not called at all, and the price of a false positive is one row
  degraded into a log, on a path that has already lost the message. It stops being free at the
  obvious next reuse — if the same check were used to close the `MailService.deadLetter` write
  after the attempts are exhausted: those are the table's most valuable rows, and there the
  overstatement would have to be justified separately rather than inherited from here.
  The honest statement of what the check gives: what is removed is not the parking but its AVALANCHE —
  a small CONSTANT number of threads parks, not N, and not "the first one". The window between reading
  the counter and acquiring the connection is not an instant: into it fit the `@Transactional` proxy,
  `getTransaction` with the suspension, `createEntityManager` and the eager `getJdbcConnection` inside
  `JpaTransactionManager.doBegin`, that is tens to hundreds of microseconds, and every committing
  thread that managed to enter that window before the first one became visible in the counter also
  passes the check. The conclusion does not change because of that and survives a future where
  refusals arrive in a correlated batch: a constant instead of a self-reinforcing wave. Each of those
  threads still holds its own first connection for up to the default 30 s — the per-thread price is
  exactly the same, only the multiplication has been removed — and connection acquisitions unrelated to
  mail still queue up all the same: what is closed is MAIL's contribution to the avalanche,
  not the delay itself. If the `DataSource` underneath is not Hikari, the check answers "no" and the
  write proceeds as before — it cannot become a cause of a lost record.
  The other three remain. The obvious answer (3 s) is **unavailable** for them:
  `StatementTimeoutProperties` derives its 10 s as roughly a third of those same 30 s, so that a
  saturated pool has time to turn around inside the waiting window, and
  `DatabaseTimeoutConsistency.warnIfTheBoundOutlastsTheWait` logs a WARN as soon as the statement
  budget exceeds half the waiting budget. That is, 3 s gives a WARN on every startup with the
  defaults, and the only honest way to silence it is a statement budget of 1.5 s — below its own
  hard minimum (2× `DB_LOCK_TIMEOUT_MS` at ITS default of 3000 ms). Hence "the shortest
  self-consistent bound is about 20 s", but that is a claim about THE BOUNDARIES OF ONE TICKET, not a
  proof of impossibility, and the previous edition read as the latter: `lockTimeoutMs` has its own
  `@Min` equal to 100, so a fully reassembled family (lock 500 / statement 1000 / acquisition
  2000) is expressible today and violates none of the three rules. It is out of the scope of this change
  precisely because it is the WHOLE family: the trade of "saturation degrades into delay" for
  "saturation degrades into a 500" for every endpoint, as a separate decision with an env variable, a
  compose file and two documents. The argument is written down here and in the `FailedEmailWriter`
  javadoc so that the next reviewer meets a counter-argument rather than emptiness, and so that the
  ticket that does reassemble the family knows that these paths are among its beneficiaries.
