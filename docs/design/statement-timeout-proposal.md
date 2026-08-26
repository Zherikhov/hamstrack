# Bounding how long a statement may run (HD-151)

**Status:** proposal / design review. Written *while* the implementation is in flight, so it is
deliberately arranged as a set of decisions rather than a plan: §2 answers the shape question the
ticket contains and does not resolve, §5–§7 answer the four questions attached to it, and §13 names
the assumption most likely to be wrong.

**Branch:** `feat/release-0.17.0`. **Related:** `docs/design/roles-permissions-s4-spec.md` §5 (the
lock bound this extends), `docs/release-checklist.md` → *Releases that change a resource default*
(this release is in that class), `docs/design/delivery-paths-proposal.md` (unrelated, cited only for
the "a guard narrows what the UI offers, never what the server resolves" posture).

---

## 1. Problem & goal

`application.properties:25` bounds `lock_timeout` and nothing bounds `statement_timeout`. PostgreSQL
reads an unset `statement_timeout` as **disabled** ("A value of zero (the default) disables the
timeout" — PostgreSQL 17 docs, *Client Connection Defaults*), so today **every statement this
application issues may run for ever**. `DB_POOL_MAX_SIZE` defaults to 10
(`application.properties:16`) and Hikari's `connectionTimeout` is left at its 30 s default (nothing
in `application.properties` overrides it), so ten simultaneous multi-second statements pin the whole
pool and every other endpoint on the instance begins failing on connection acquisition. Nothing else
in the stack shortens a statement: a client that disconnects does not abort the server-side query,
and `connectionTimeout` bounds *checking out* a connection, never one already in flight — the same
argument `LockTimeout`'s javadoc makes for locks (`LockTimeout.java:22-25`) applies unchanged to
statements.

**Goal.** Every statement this application issues has a finite bound; exceeding it is a deliberate,
named, logged refusal rather than a 500; the value is operator-configurable with the full DC/Cloud
wiring; and Flyway keeps the unbounded budget a schema change legitimately needs.

**Success looks like:** a pathological query on a large tenant costs one request and ≤ *T*
milliseconds of one connection, instead of one connection until somebody restarts the instance.

---

## 2. The shape question: is per-call-site opt-in right?

**No. Recommendation: bound by default at the transaction boundary, and enumerate the exemptions
instead of the participants.**

The ticket's own motivation is the refutation of its own scope: *"There is no request that cannot
become that query on a large enough tenant — the reports aggregates are simply the first ones
measured."* A shape that bounds the measured paths ships a guard whose coverage is a property of our
measurement history, not of the risk. It will also *look* complete, which is worse than looking
absent.

### 2.1 The lock javadoc's argument, and why it does not transfer

`LockTimeout.java:69-74` states the rule for lock bounds and then declines to generalise it:

> Not applied everywhere: ordinary requests take no row locks at all, and a round-trip added to
> every transaction to bound a wait that cannot happen is a cost with no matching risk.

That sentence has two clauses and **statement bounds fail both**:

- *"a wait that cannot happen"* — a lock wait is **structural**: a transaction that takes no lock and
  writes no contended row cannot queue, and that is provable by reading the code. A slow statement is
  **volumetric**: its cost is a function of how much data a tenant has, and no static property of a
  query proves it fast. There is no code-reading procedure that produces the set of statements that
  will never be slow, so the opt-in list cannot be derived — only guessed from what has been
  measured.
- *"a round-trip added to every transaction"* — true of the `SET LOCAL` spelling, and avoidable. §5.1
  places the bound where it costs one statement per *transaction* rather than one per call site, and
  §5.1.1 names the variant that costs zero per transaction if that ever measures.

The codebase has **already drifted off the opt-in rule for locks, for exactly this reason**.
`RoleService.java:413-417`:

> Bound first, then lock — the standing rule (HD-136). This path takes no row lock of its own today,
> but it is a write inside the same tables the locking paths hold, so it can queue behind one; and
> the bound has to be the FIRST statement or it bounds nothing. **Applying it uniformly is what keeps
> "which transactions are bounded" from being a list somebody has to maintain.**

That is the argument of this section, written by the previous author about the previous bound. The
"ordinary requests take no row locks at all" sentence is already softer than it reads: *every* UPDATE
takes a row lock, and `GlobalExceptionHandler.java:284-286` records that a member removal bumps
`@Version` on every issue it unassigns (`IssueRepository.unassignAllInWorkspace`), so an ordinary
`PATCH /issues/{id}` can queue behind it while calling no locking API of its own.

**And the enumeration rotted, as predicted.** `LockTimeout.java:76-81` deleted its own census of call
sites ("It was wrong within one ticket and wronger within two"). The census then reappeared in the
operator manual: `docs/self-hosting.md:165` lists five bounded paths ("workspace member role change,
workspace member removal, project member role change, project member removal, and a custom-role
duplicate") while `applyToCurrentTransaction` has **seven** call sites today —
`WorkspaceMemberService.java:171,234`, `RoleService.java:346,418,572`,
`ProjectService.java:378,459`. A list deleted in one file grew back in another within one release.
That is the empirical answer to "what stops the list going stale": nothing did.

### 2.2 The candidate shapes

| # | Shape | Verdict |
|---|---|---|
| A | Per-call-site `SET LOCAL` opt-in (the ticket) | **Reject.** Coverage = measurement history. The list rots; this class has the receipts (§2.1). |
| B | A + a sealed coverage test (the `ThrottleCoverageTest` pattern) | **Reject, but for a subtler reason** — see below. |
| C | Session default via `connection-init-sql` / JDBC `options`, plus a dedicated Flyway datasource | **Hold in reserve.** Zero per-transaction cost, but requires splitting Flyway onto its own `DataSource` and I could not verify Boot 4's `spring.flyway.url` behaviour from here. §5.1.1. |
| **D** | **Bound at the transaction manager: every application transaction, one place, exemptions enumerated** | **Recommended.** |
| E | Global Hibernate query timeout (`jakarta.persistence.query.timeout`) | **Reject.** Seconds granularity; pgjdbc implements JDBC query timeout by opening a *second connection* to send a cancel, which is a new failure mode precisely when the database is under pressure; and it covers only Hibernate-issued statements, so it is a default-on shape with an invisible hole. |
| F | Operator-side `ALTER ROLE … SET statement_timeout` | **Reject as the product answer.** Same login role serves Flyway, and it is not shipped configuration — a self-hosted install with no DBA gets nothing. Fine as an *additional* operator hardening; document it, do not depend on it. |

**Why B is rejected even though the project's own precedent is B.** The sealed-set test works for
throttles because "which surfaces are expensive enough to need a budget" is a *judgement* about a
handful of handlers, and the judgement is what the test records. Here the correct answer is not a
judgement: it is **every transaction that serves a request**, which is not a set anyone should
enumerate. When the right answer is "all of them", the maintainable artefact is the **complement** —
the exemptions — because that set is small, deliberate, and defined by a property ("does not serve an
interactive request") rather than by a census. Enumerate the smaller set; make the larger one the
default.

**The failure modes point the same way.** Under A, a path nobody bounded is silently unbounded — no
log line, no metric, no test, discovered when the pool is gone. Under D, a path that legitimately
needs longer than *T* fails **loudly**, with a named exception, a WARN naming the knob, and a metric.
Loud-and-wrong beats silent-and-unbounded, and that is already this project's stated posture:
`LockingProperties.java:44-48` chooses a boot failure over an ambiguous config line because "a loud
refusal is the better answer".

### 2.3 What keeps the exemption list honest

1. The exemption is **an API call with a mandatory reason argument**, not a property file entry
   (§5.5). It appears in the code that needs it and in the log when it fires.
2. Flyway is exempt **by construction** under shape D — it does not use the transaction manager — so
   the one exemption that matters most is not maintained by anyone. A test pins the mechanism rather
   than the outcome (§12.4).
3. The javadoc states the **rule**, never the roster: *"every transaction this application opens is
   bounded; a transaction that legitimately outlives the bound says so at its own call site."* Grep
   for the exemption method to find today's set — the same instruction `LockTimeout.java:78-81`
   already gives.
4. `docs/self-hosting.md` gets a row that describes the **category**, not the members. The existing
   `DB_LOCK_TIMEOUT_MS` row (line 165) should be corrected to match while we are there; it is
   currently a stale census.

---

## 3. Scope

**In scope**

- A `statement_timeout` applied to every transaction the application opens.
- One new property + env var, validated, with a cross-check against the lock bound.
- An exemption API for transactions that legitimately run long, with today's exemption set (which is
  empty in application code — see §5.5).
- An exception handler for `QueryTimeoutException`, a status code, a body, a log line, a metric.
- Documentation: `application.properties`, `.env.prod.example`, `docs/self-hosting.md` (config row +
  an `## Upgrading` section), the GitHub Release body line, `openapi.yaml` + `docs/api-*.md` for the
  new status code.

**Out of scope, named so nobody reads the shipped guard as covering them**

- **Bounding how long a *connection* is held.** `statement_timeout` bounds **one statement**, not the
  transaction. A transaction of 100 statements at *T*/2 holds a connection for 50*T*, and a
  transaction that spends its time in *Java* — `ReportCsvService` assembles the whole CSV inside
  `@Transactional(readOnly = true)` (`ReportCsvService.java:70-113`) — is `idle in transaction` and
  is not touched by this setting at all. The complementary control is
  `idle_in_transaction_session_timeout`; it is **not** shipped here, because it would also kill
  legitimate long app-side assembly and needs its own measurement. This is the exact shape of
  `LockTimeout.java:50-59` ("It bounds *waiting* for a lock, not *holding* one") and the new class's
  javadoc must carry the analogous paragraph.
- Query optimisation, indexes, or making any specific report faster.
- Per-report or per-endpoint budgets. One number plus exemptions (§5.2); a second knob is a second
  thing to get wrong, and `LockTimeout`'s narrowing mechanism already exists if one is ever needed.
- Changing the report/search rate limiters. They remain a per-minute count and remain per-process.

**Non-goals**

- This does not make the pool exhaustion-proof. It converts "pinned until restart" into "pinned for
  ≤ *T*", which is what turns saturation into latency instead of errors.

---

## 4. Actors & permissions

None. This is infrastructure: no endpoint, no role, no workspace scoping, no tenant-visible resource.

Two tenancy-adjacent notes for the reviewer, because "no permissions" is not the same as "nothing to
check":

- **The refusal must not disclose anything the caller could not already see.** The body says what
  happened and how long it was allowed to take; it must not carry SQL, a table name, a row count, or
  another tenant's anything. §5.4.
- **The bound must not vary by tenant, plan or workspace.** A per-tenant budget would be a licence
  check wearing a resource guard's clothes, which `application.properties:206-210` explicitly
  forbids for the roles cap and which applies identically here.

---

## 5. Behaviour & rules

### 5.1 Where the bound is applied — recommended: the transaction manager

**Rule:** every transaction opened by the application's `PlatformTransactionManager` gets
`SET LOCAL statement_timeout = <T>` as part of `doBegin`, before any application statement runs.

Concretely:

- `common.persistence.BoundedJpaTransactionManager extends JpaTransactionManager`, overriding
  `doBegin` to call `super.doBegin(...)` and then issue the `SET LOCAL` on the connection now bound
  to the thread (`TransactionSynchronizationManager.getResource(dataSource)` →
  `ConnectionHolder.getConnection()`), via a plain JDBC `Statement`.
- Registered as the `PlatformTransactionManager` bean. Boot's `JpaBaseConfiguration#transactionManager`
  is `@ConditionalOnMissingBean(TransactionManager.class)`, so declaring ours backs it off — **and
  the replacement must still apply `TransactionManagerCustomizers`**, or Boot-level transaction
  settings silently stop being applied. That is the one easy-to-miss step of this shape.

**Why this boundary and not another.** The transaction manager is *already* the line that separates
"work the application does" from "work Flyway does" — Flyway acquires its own connections from the
`DataSource` and runs its own transactions, and never touches this bean. So the sentence "everything
the application does is bounded, and migrations are not" becomes expressible **without a list**,
which is the whole point of §2. Every other candidate boundary (the `DataSource`, the JDBC URL, the
role, `postgresql.conf`) sits *below* that line and therefore catches Flyway too — which is exactly
what `LockTimeout.java:31-36` rejects:

> The obvious spelling — `spring.datasource.hikari.connection-init-sql=SET lock_timeout = …` — is
> rejected: Flyway shares this datasource, so every migration would inherit the bound.

Three secondary properties of this placement, all of which matter:

- **`TransactionTemplate` is covered.** `AttachmentService.java:47,80` uses one; it resolves through
  the same bean.
- **Bare repository calls are covered.** `SimpleJpaRepository` is `@Transactional`, so even a
  repository call made outside a service transaction opens one through this manager and is bounded.
  There is no "outside" to forget.
- **It does not perturb Hibernate's statement statistics.** `PermissionResolutionQueryCountTest`
  asserts exact counts via `Statistics.getPrepareStatementCount()`
  (`PermissionResolutionQueryCountTest.java:129-138`), which counts Hibernate's prepared statements.
  A raw-JDBC `SET` at `doBegin` is invisible to it, so the "2 statements workspace-scoped, 4
  project-scoped, 0 to check" claim in `CLAUDE.md` stays true and that test stays green. Issuing the
  bound as `entityManager.createNativeQuery(...)` — the way `LockTimeout.java:103` does it — would
  break it, and would break it as a *count* change that reads like a regression.

**Cost.** One extra statement per transaction. Honest caveat: a service method that is *not*
`@Transactional` and makes five repository calls now pays five, not one. That is a real amplifier and
the reason §5.1.1 exists.

#### 5.1.1 The reserve shape, if the round trip ever measures

Session-level default (`spring.datasource.hikari.connection-init-sql`, or `options=-c
statement_timeout=…` as a connection parameter), which costs **zero** per transaction because it runs
once per physical connection — **conditional on giving Flyway its own datasource**
(`spring.flyway.url`/`user`/`password`, fed from the same `DB_*` env vars so no new operator surface
appears). Two rules come with it, and they are why it is not the primary recommendation:

- Application code may then use `SET LOCAL` only. A plain `SET statement_timeout` would poison a
  pooled connection permanently.
- The Flyway exemption stops being by-construction and becomes by-configuration, so it needs a test
  that fails if the split is ever undone.

I could not verify from here that Boot 4 still builds a separate `DataSource` from `spring.flyway.url`.
Verify that before adopting; do not adopt it on the strength of this paragraph.

### 5.2 The value

One property. **`app.persistence.statement-timeout-ms`, default `10000`, env `DB_STATEMENT_TIMEOUT_MS`.**

Why 10 s and not something smaller or larger — each clause is a constraint, not a preference:

- **> `app.locking.lock-timeout-ms` (3000).** Mandatory, not stylistic. PostgreSQL 17 docs: *"if
  `statement_timeout` is nonzero, it is rather pointless to set `lock_timeout` to the same or larger
  value, since the statement timeout would always trigger first."* At or below 3 s, the existing
  retryable `409 + Retry-After` lock contract (`GlobalExceptionHandler.java:347-357`) silently stops
  firing and its clients start getting the statement refusal instead. See §7.
- **≈ ⅓ of Hikari's 30 s `connectionTimeout`.** With every statement ≤ 10 s, a fully saturated pool
  turns over roughly three times inside the window a waiting request is prepared to wait, so
  saturation degrades to *latency*. At 30 s a saturated pool guarantees connection-acquisition
  failures, i.e. the bound would be too loose to deliver the thing it exists for.
- **~100× an ordinary request**, so it fires on pathology and not on a cold cache or a slow disk.

Validation, mirroring `LockingProperties.java:30-51` exactly:

- `@Validated @ConfigurationProperties(prefix = "app.persistence")`, record component
  `@DefaultValue("10000") @Min(1000) @Max(600000) int statementTimeoutMs`.
- **Primitive `int` on purpose** — copy the reasoning verbatim from `LockingProperties.java:38-48`:
  `DB_STATEMENT_TIMEOUT_MS=` binds an *empty* value, which must abort the boot rather than quietly
  restore the default.
- **No "unlimited" value.** `0` means *disabled* to PostgreSQL, i.e. the behaviour this ticket
  deletes, so `@Min(1000)` refuses it at startup — the same trap `LockingProperties.java:22-26`
  documents for `lock_timeout`.
- **Max 600000 (10 min)**, not unbounded: above that the request is long dead at every intermediary
  and the connection hold — which this setting does *not* bound (§3) — is the real problem.

### 5.3 Cross-property validation (fail fast, at startup)

`app.persistence.statement-timeout-ms` **must** exceed `app.locking.lock-timeout-ms`. Enforced at
startup, following the joint-validation precedent in `AgileProperties.java:88` (`@AssertTrue` on the
product of two caps). Because the two values live in different records here, use a small
`@Component` whose `@PostConstruct` throws — the outcome (context fails to start) is identical, and
the failure message is the documentation:

> `app.persistence.statement-timeout-ms (2000) must be greater than app.locking.lock-timeout-ms
> (3000). PostgreSQL applies both; the smaller one always fires first, so a statement bound at or
> below the lock bound makes the lock bound dead configuration and turns every lock-wait timeout —
> today a retryable 409 with Retry-After — into a statement-budget refusal that is not retryable.
> Raise DB_STATEMENT_TIMEOUT_MS or lower DB_LOCK_TIMEOUT_MS.`

Recommend a margin rather than bare `>`: require `statement ≥ 2 × lock`, so that a transaction which
waits nearly the full lock budget still has time to *do* its work before the statement bound fires.
At the defaults (3000 / 10000) this holds with room.

### 5.4 The refusal

**Status: `422 Unprocessable Content`.** With `errorType: "STATEMENT_BUDGET_EXCEEDED"` and
**deliberately no `Retry-After`**.

The reasoning, since this is the question most likely to be re-litigated:

- **The event is not retryable.** The same query over the same data costs the same time, so
  `409 + Retry-After` — right for a lock, where the obstacle is another transaction that will
  commit — is an instruction that cannot work here. `429` is wrong for the same reason plus a second:
  the caller is not over a budget of *requests*.
- **5xx is the class that intermediaries and SDKs retry automatically.** This is the decisive
  argument. An automatic retry of a statement-budget failure re-spends the entire budget on a
  connection pool that is already under stress — it converts one expensive request into an unbounded
  series of them, which is the failure this ticket exists to prevent. `503` and `504` are the two
  most commonly auto-retried codes in the wild. Rule out both.
- **`504` has a second defect:** a Caddy/ALB gateway timeout is indistinguishable from ours on the
  wire, so an operator debugging a report cannot tell whether the app refused or the proxy gave up.
  (Our `Caddyfile:1-5` sets no proxy timeout today, which makes the collision *latent* rather than
  absent — a future `reverse_proxy` timeout would create it.)
- **`500` is honest about "we failed" and wrong about "we decided".** This is a deliberate refusal,
  and a 500 also renders in the SPA as a crash rather than as a sentence
  (`CLAUDE.md` → *Admin error messages invisible in the UI*).
- **`422` is what the codebase already means by "well-formed, understood, and we will not process
  it"** — HQL semantic errors (`GlobalExceptionHandler.java:200-211`), permission-scope mismatch,
  the classification caps. RFC 9110's definition ("the server understands the content type … and the
  syntax … is correct, but was unable to process the contained instructions") is a literal
  description of what happened.
- **The monitoring argument for 5xx is answered without borrowing the status code.** Yes, an
  operator must learn about this — and the precedent for exactly that trade is in this file already:
  `handlePessimisticLock` logs WARN *because* "turning a 500 into a clean 409 also removed the only
  signal an operator had" (`GlobalExceptionHandler.java:336-342`). Same solution here: WARN + a
  metric (below).

**Body — name the number, never the knob.**

```json
{
  "type": "about:blank",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "This request was stopped after 10 seconds — it is too expensive to complete on this instance. If it takes a date range or filters, narrow them; otherwise this request is larger than this instance is configured to answer. Retrying it unchanged will take just as long.",
  "errorType": "STATEMENT_BUDGET_EXCEEDED"
}
```

The split between body and log is the answer to "should the refusal name the budget so an operator
knows which knob to turn":

- The **value** goes in the body, in human units. This matches what the project already does with
  caps — `REPORTS_MAX_WINDOW_DAYS` is quoted back to callers inside its 400
  (`application.properties:242-245`), and `meta.cap` ships in every report body.
- The **property and env var** go in the **log**, not the body. `app.persistence.statement-timeout-ms`
  / `DB_STATEMENT_TIMEOUT_MS` are meaningless to an end user, are configuration detail on a
  multi-tenant Cloud instance, and the operator's copy of this refusal *is* the log line. Reading the
  acceptance criterion as "put the env var in the JSON" would put the instance's tuning surface in
  front of every authenticated tenant to no benefit.
- The message must not prescribe an action its reader cannot perform (`CLAUDE.md`, HD-123), and
  that rule turned out to have **two** edges rather than one. The first is the one this section
  saw: several bounded paths take no narrowing parameters at all (`GET …/reports/aging` takes
  none — `ReportCsvController.java:143-149`), so the sentence *offers* narrowing conditionally
  rather than instructing a user to shrink a window that does not exist.

  The second is the one it got wrong, and security review caught it. This section originally
  proposed the fallback "otherwise ask your administrator", which is sound on DC — where the
  administrator is the operator who owns the `.env` — and a **dead end on Cloud**, where the
  reader is a tenant whose administrator is a workspace owner with no access to
  `DB_STATEMENT_TIMEOUT_MS` and no way to obtain it. A refusal that dispatches its reader to
  somebody who cannot help breaks the very rule this bullet cites.

  **What shipped stops dispatching anybody.** The sentence states that the request is larger than
  the instance is configured to answer: true in both deployment models, and it asks nothing of a
  reader who can do nothing. Deliberately not profile-branched — one sentence true in both modes
  beats two that can drift, and a refusal is not the place to teach a reader which deployment
  model they are in. `StatementBudgetRefusalTest` asserts the body does **not** contain the word
  administrator, so this cannot regress quietly.

**Log (WARN, one line, the operator's copy):**

> `Statement budget exceeded on GET /api/workspaces/…/reports/flow after 10000ms — answering 422. Raise app.persistence.statement-timeout-ms (DB_STATEMENT_TIMEOUT_MS) if this request is legitimate, or narrow the query. SQLSTATE 57014.`

**Metric:** `hamstrack.db.statement_budget_exceeded` counter (**shipped name** — this document said `hamstrack.db.statement_timeout`; renamed during implementation to match the `errorType`, so the metric, the status body and the log all use one word for one thing), following `ProductMetrics.java:195,256`
(`registry.counter(name, tag, value)`). Tag by HTTP method + the *mapped pattern*, never the raw URI
— a raw URI carries workspace and project UUIDs and would explode Prometheus cardinality while
putting tenant ids in a metrics store.

**Handler placement.** A new `@ExceptionHandler(QueryTimeoutException.class)` in
`GlobalExceptionHandler`. Two constraints:

- `org.springframework.dao.QueryTimeoutException`, **not** its parent
  `TransientDataAccessException`. The parent would also swallow Hikari's pool-exhaustion failure
  (`SQLTransientConnectionException` → `TransientDataAccessResourceException`), which is a *different*
  fault with a different remedy, and folding the two together destroys exactly the signal this
  feature exists to produce. Say so in the javadoc, or somebody will "simplify" it.
- Not on Boot's `ResponseEntityExceptionHandler` list — it is a `org.springframework.dao` type — so
  the only behaviour that changes is 500 → 422. Same note the two neighbouring handlers carry
  (`GlobalExceptionHandler.java:293-296`, `344-345`).

Confirmed as of this branch: **no handler catches `QueryTimeoutException` today.** The complete
handler set in `GlobalExceptionHandler.java` is `AppException`, `RateLimitedException`,
`LabelNameConflictException`, `StrandedProjectsException`, `ReactivatedProjectDefaultsException`,
`RoleInUseException`, `SelfHeldRoleException`, `RoleLimitReachedException`, `HqlParseException`,
`HqlSemanticException`, `DateTimeException`, `MaxUploadSizeExceededException`,
`OptimisticLockingFailureException`, `PessimisticLockingFailureException`,
`MethodArgumentNotValidException` — `QueryTimeoutException` is a sibling of the pessimistic one under
`TransientDataAccessException`, not a subclass, so it falls through to a bare 500.

### 5.5 The three exemptions the ticket names — one each, and they are not the same kind

The ticket says *"reports are the obvious first; demo seeding, migrations and admin exports must
not"*. Under shape D the answers differ, and two of the three are not what the sentence implies:

**Flyway / migrations — a genuine exemption, satisfied by construction.** This is the only one of the
three that really needs unbounded time, and the need is concrete: a single migration statement can
legitimately run for minutes. `docs/release-checklist.md:284-289` inventories exactly that — a
`UNIQUE` index build on `issues` (~55 MB and 1–3 s at 1M rows, growing linearly), a FK validation
under `SHARE ROW EXCLUSIVE`, and `V11`'s `position` rescale which "rewrites every row". Under shape
D, Flyway never touches the transaction manager, so it is exempt with no code, no property and no
list. **Under shape C it would not be**, which is the trap: verify by test, not by reading (§12.4).

**Demo seeding — exempt by construction, and for a reason the ticket's phrasing hides.**
`DemoDataService.seedOnFirstLogin` is one `@Transactional` method (`DemoDataService.java:99-100`)
that creates a workspace, a project, ~20 issues, labels, components, versions and sprints. It is a
**long transaction made of short statements**, and `statement_timeout` bounds a *statement*, not a
transaction (PostgreSQL docs: "the timeout is applied to each statement separately"). Nothing in it
is a single large statement, so it needs no exemption. Do **not** grant it one: an exemption here
would be a permanently unbounded transaction on the first-login path, added to defend against a
failure mode that cannot occur. If it is ever suspected, measure it — that is a five-minute
experiment, not a design decision.

**Admin exports — nothing to exempt; the phrase does not correspond to code.** There is no admin
export feature in the tree: a search for `export` across `src/main/java` matches 14 files, none of
them an admin export path (they are the report CSV package, `FieldRegistry`, and DTO field names).
The only export surface that exists is the **report** CSV — `ReportCsvController` +
`ReportCsvService` — which is not an exemption candidate but the *heaviest* report path, since it
materialises the entire series into a `String` before responding (`ReportCsvController.java:203-214`).
**Risk flagged:** a builder implementing "admin exports must not be bounded" who goes looking for a
match may exempt `ReportCsvService`, which is the exact opposite of the intent. There is nothing to
do here — say so explicitly rather than leaving the phrase unresolved.

**Therefore: the exemption set in application code is empty at ship time.** That is a feature of
shape D, not an oversight, and it should be stated in the javadoc in those words. The API still
ships, because the first legitimate long-running job (a future backfill, an async export, a
scheduled recompute) must have a supported way to say so instead of raising the global number.

**The exemption API:**

```java
statementTimeout.exemptCurrentTransaction("V-next backfill: rewrites every issue in the workspace");
```

- `@Transactional(propagation = MANDATORY)` + the `Assert.state(...isActualTransactionActive())`
  guard, copied from `LockTimeout.java:98-102` — outside a transaction PostgreSQL downgrades
  `SET LOCAL` to a WARNING and the call silently does nothing.
- Issues `SET LOCAL statement_timeout = 0`, reverted at COMMIT/ROLLBACK, so it can never leak into
  the pooled connection.
- The `reason` argument is **required and logged at INFO**. An exemption that does not explain itself
  is how the set stops being reviewable.

---

## 6. Edge cases & failure modes

**6.1 What a cancellation leaves behind — verified, path by path.** A statement cancelled at the
budget aborts and the transaction rolls back; there is no partial commit. The question is whether any
bounded path produces an effect *outside* the database, or emits bytes, before that point:

- **Report JSON and CSV: no partial output.** Every report service is
  `@Transactional(readOnly = true)` at its entry (`FlowReportService.java:79`,
  `CycleTimeReportService.java:78`, `AgingReportService.java:80`, `VelocityService.java:162`,
  `SprintBurnupService.java:121`, `SprintReviewService.java:109`, `SprintLedgerReader.java:85`,
  `InsightsService.java:223`, `LifetimeCycleTimeCache.java:113`, `ReportCsvService.java:70-113`) and
  returns a value; nothing is written to the response until the handler returns. The CSV path builds
  the whole body in memory and hands it over as `.body(document.body())`
  (`ReportCsvController.java:203-214`). **There is no `StreamingResponseBody` and no streaming
  report** — the only `SseEmitter` in the tree is the notification stream
  (`SseRegistry.java:32-33`), which carries no report data. So the client sees the 422 problem
  detail, never a truncated chart or a half CSV.
- **Cached values cannot be poisoned.** `LifetimeCycleTimeCache` is `@Cacheable(sync = true)`
  (`LifetimeCycleTimeCache.java:111-113`); a loader that throws stores nothing, and `sync = true`
  propagates the same exception to threads waiting on that key rather than caching it. Side benefit:
  those waiters are now bounded too.
- **Notifications and SSE cannot fire on a cancelled transaction.** Every domain event is
  `@TransactionalEventListener(AFTER_COMMIT)` (`SseEventListener.java:38-95`), and a cancelled
  transaction never commits.
- **The one path with an outside-the-DB effect is attachment upload, and it already compensates.**
  `AttachmentService.upload` is deliberately not `@Transactional`
  (`AttachmentService.java:56-124`): a short transaction reserves the row, the blob is written after
  that commit, and a third short transaction compensates on failure. A cancellation in the reserve
  transaction rolls back before any blob exists. A cancellation in the *compensation* transaction
  leaves an orphaned row — which is the already-documented "if compensation itself fails" branch that
  logs the orphan for out-of-band cleanup (`AttachmentService.java:110-115`). This adds a new *cause*
  to an existing branch, not a new class of half-done state.

**Statement of the rule, for the javadoc:** a bounded transaction that has already produced an effect
outside the database can be left half-done by a cancellation exactly as it can by any other rollback.
The bound adds a cause, never a class. A future path that writes outside the database inside a bounded
transaction inherits that obligation.

**6.2 The aborted-transaction trap.** After SQLSTATE `57014` the transaction is aborted; any
subsequent statement on it fails with `25P02 in_failed_sql_transaction`. So **nothing may catch
`QueryTimeoutException` below the exception advice** and continue working in the same transaction —
it would produce a second, confusing error that names neither the cause nor the budget. Worth one
sentence in the class javadoc.

**6.3 The write paths that can plausibly exceed the bound in a single statement.** Not a census — a
category, with the two examples I can point at today. **Single statements whose cost is O(tenant
data)**:

- The workspace-wide unassign on member removal (`IssueRepository.unassignAllInWorkspace`, cited at
  `GlobalExceptionHandler.java:284-286`) — one bulk `UPDATE` over every issue assigned to the
  departing member, holding locks while it runs. If this exceeds *T*, a member removal that used to
  succeed slowly now fails, and the caller **cannot narrow it**. This is the highest-value thing to
  measure before shipping.
- The project-wide rank renumber (`IssueRankService`, `CLAUDE.md` → `issues.position`) — one native
  whole-project rewrite, already throttled per project with a 429.

Both are *correctly* bounded — they are the statements that pin a connection — but each is a
behaviour change on a write path, which is why the release note in §11 is mandatory rather than
courteous.

**6.4 Read paths whose work is unbounded while their output is bounded.** The evidence for §2's
"reports are merely the first ones measured", all documented in the repo already: `POST …/search`
runs up to 50 leaf predicates, each `text ~` leaf being two unanchored unindexable `LIKE`s, **twice**
per request (count + page) — `application.properties:283-290`; the planning view assembles up to
`(max-open-sprints + 1) × section-max` issues, 6300 at the defaults —
`application.properties:188-192`; HQL name resolution loads a workspace's whole label catalog (cap
1000) and each visible project's component and version catalogs (cap 500 each) on **every**
`/search`, `/search/schema` and `/search/suggest` — `application.properties:163-182`; and
`GET …/versions` is unpaged (`docs/self-hosting.md:182`). Every one of these is bounded by *response
size* or by *requests per minute*, and none by *time*. An opt-in list that starts with reports covers
none of them.

**6.5 Interaction with the rate limiters.** The report and search budgets are fixed per-minute
counters, per process (`ReportRateLimiter.java:27-38`), so on Cloud the real budget is
60 × replicas. They limit *how often*, never *how long*; this feature limits *how long*, never how
often. Neither substitutes for the other, and the 429 and the 422 are separable by status.

**6.6 Concurrency, idempotency, optimistic locking.** Unchanged. A cancelled transaction takes no
`@Version` bump and leaves nothing to reconcile. Retrying a cancelled *write* is safe in exactly the
way retrying any rolled-back write is safe; the API just does not advertise a retry, because an
identical one fails identically.

**6.7 Test-suite blast radius.** Every integration test now runs bounded. A test that was quietly
relying on a slow query completing will fail with 422 rather than hanging — which is desirable, but
it means the first full run after this change is the real review gate, not a formality.

---

## 7. Interaction with `lock_timeout` — the direct answer

- **Neither overrides the other.** They are independent GUCs. Both are set with `SET LOCAL`, both are
  in effect for every subsequent statement of that transaction, and both are reverted at
  COMMIT/ROLLBACK.
- **Order of the two `SET`s does not matter.** What matters is their *relative magnitude*, because
  `statement_timeout` measures total statement time **including time spent waiting for a lock**,
  while `lock_timeout` measures only the wait. PostgreSQL 17 docs, `lock_timeout`: *"if
  `statement_timeout` is nonzero, it is rather pointless to set `lock_timeout` to the same or larger
  value, since the statement timeout would always trigger first."* So a statement bound ≤ the lock
  bound makes `lock_timeout` dead configuration and silently converts the retryable
  `409 + Retry-After` contract into a non-retryable 422. §5.3 makes that a startup failure instead of
  a discovery.
- **A transaction can have both, and as shipped every application transaction does.** The
  section planned for the lock bound to stay at `LockTimeout`'s seven call sites; security review
  showed that could not stand, because `statement_timeout` counts lock-wait time and would
  therefore have converted every *other* contended write in the product into a non-retryable 422.
  **As shipped, both bounds are issued from `doBegin` in one round trip** — the optimisation this
  bullet listed as optional was taken, spelled `SET LOCAL statement_timeout = n; SET LOCAL
  lock_timeout = m` (pgjdbc runs a multi-statement string in simple query mode). `LockTimeout`
  survives as an explicit re-assert on the seven paths that lock deliberately.
- **One invariant needs restating, not changing.** `LockTimeout.java:61-67` says "Call it as the
  first statement of the transaction". After this change it is no longer literally first — the
  manager's `SET` precedes it. The *reasons* survive intact (a bound applied after a locking read
  bounds nothing; a Hibernate native query flushes the persistence context ahead of itself, harmless
  only when nothing is pending, and nothing can be pending at `doBegin`). Amend the sentence to "the
  first statement the service issues", or the next reader will conclude the guard is broken. This is
  the `CLAUDE.md` category-versus-member rule biting a sentence that was correct when written.

---

## 8. Data model impact

**None.** No table, no column, no migration. Explicitly noted because "bounded by the database" reads
like a schema change and is not: `statement_timeout` is a session GUC, and the guard lives entirely
in application configuration.

Flyway is affected only in that it must remain **unaffected** — §5.5, §12.4.

---

## 9. API surface

No new endpoint, no changed request or response shape. One new **status code on existing endpoints**:

- **`422 Unprocessable Content`** with `errorType: "STATEMENT_BUDGET_EXCEEDED"` — reachable, in
  principle, from **any** endpoint that touches the database. Document it once, as a shared response
  in `openapi.yaml`, and describe it as a property of the API rather than attaching it to the
  handful of endpoints where it is likeliest. Attaching it to a list is the same mistake as §2, one
  layer up: the list would be a list of paths we measured.
- No `Retry-After` on this response. Say so in the docs — the absence is a contract, and a client
  author who assumes 4xx-with-a-time-limit means "retry later" would do the harmful thing.

`api-docs-sync` must update `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md`. Both API
documents already describe the `409 + Retry-After` lock contract; the new paragraph belongs next to
it and must state the distinction in one line: **the 409 means somebody else is busy with this row
and a retry works; the 422 means this request itself is too expensive and an identical retry fails
identically.**

---

## 10. Frontend impact

Small, and mostly free.

- The SPA's `request()` already renders `detail` from a problem detail and falls back to
  `Request failed (<status>)` (`CLAUDE.md` → *Admin error messages invisible in the UI*), so a 422
  surfaces as a readable sentence with no change at all.
- **Check that no client path treats 422 as a field-validation error.** The HQL search box does — it
  underlines a span from `position`/`length` on `PARSE_ERROR`/`SEMANTIC_ERROR`
  (`GlobalExceptionHandler.java:186-211`). A `STATEMENT_BUDGET_EXCEEDED` body carries neither, so it
  must fall through to the plain-message branch and not to a highlighter with no span. Branch on
  `errorType`, never on the status alone.
- Where a refused request **has** narrowing parameters (the reports panel, insights, search), render
  the message beside the controls that narrow it — window, filters, sprint count — so the offered
  action is next to the control that performs it. Where it has none (aging), render the message
  alone and offer nothing; do not invent an affordance to make the copy symmetrical.
- No new component, no `DESIGN.md` decision. Reuse the existing error banner.

---

## 11. DC vs Cloud, wiring, and the release-checklist obligation

**One value in both modes — no profile override.** Recommended and deliberate. The precedent is the
whole `app.locking` / caps family: `LockingProperties.java:17-21` states it as a posture ("A DoS
guard, not a mode switch … identical in DC and Cloud, no profile override"), and
`application.properties:156-158, 206-210, 233-239` repeat it for the classification, roles and report
caps.

I considered the genuine asymmetry — on Cloud one tenant's pathological query starves other tenants,
while on DC the only victim is the same organisation, and DC installs are likelier to run one large
tenant on small hardware. It does not justify a per-profile default, for one decisive reason:
**the env var already is the per-deployment knob.** If Cloud wants a tighter bound, Cloud sets
`DB_STATEMENT_TIMEOUT_MS` in its own environment — visibly, in one place an operator can read. A
profile default would be a *second*, invisible knob, so the same code would behave differently in the
two modes for a reason nobody could see in the `.env`. State the decision in the property comment so
it is not re-derived: *identical in `dc` and `cloud`; a deployment that wants a different bound sets
the environment variable.*

**Wiring checklist (`dc-cloud-guard`'s list):**

| Target | Change |
|---|---|
| `src/main/resources/application.properties` | `app.persistence.statement-timeout-ms=${DB_STATEMENT_TIMEOUT_MS:10000}` + a comment block in the house style: what it bounds, what it does *not* bound (the connection), why not on the datasource (Flyway), the 422 contract, the valid range, and the "no zero" trap. |
| `application-dc.properties` / `application-cloud.properties` | **No change.** Deliberate; see above. |
| `docker-compose.prod.yml` | **No change needed** — the app service already carries `env_file: .env` (line 10), so any new `.env` key reaches the container. |
| `.env.prod.example` | A commented `#DB_STATEMENT_TIMEOUT_MS=10000` next to `DB_LOCK_TIMEOUT_MS` (lines 67-75), with the same "comment it out, never blank" warning — the blank-binds-to-empty trap applies identically. |
| `docs/self-hosting.md` | New config-table row after `DB_LOCK_TIMEOUT_MS` (line 165), **and** an `## Upgrading` section (below), **and** a `## Contents` entry, **and** the fix to line 165's stale call-site census (§2.1). |
| `README` | **No change — checked.** It says configuration is "via environment variables" and enumerates none, so there is no list to fall out of date. |
| `docs/self-hosting.md` -> `## Requirements` | **No change, deliberately — recorded because the release checklist names it as a routing target, and silence there is indistinguishable from an omission.** That section sizes the *host* (RAM, disk, CPU), while this bound is a function of how much data a tenant has accumulated: a 16 GB server holding a huge workspace meets it and a 1 GB server holding a demo project never does. Routing an upgrader there would point them at the one thing that cannot answer the question. The heap change belongs there for exactly the inverse reason, and does appear there. |
| `docs/observability.md` + `observability/grafana/provisioning/alerting/rules.yml` | Metric-reference row, a provisioned **StatementBudgetExceeded** rule, and the rationale for both. Missing from this table originally and it should not have been: the refusal is a deliberate `422`, so it can never reach the `HighErrorRate` 5xx alert — without a rule of its own an instance can refuse every report it is asked for while every dashboard stays green. Rate-based (`> 5` in 15m, `for: 5m`) rather than the `> 0` its RoleScopeViolation neighbour uses, because a single `422` is one user asking a large tenant for too much, and paging on that teaches an operator to ignore the rule. |

**This release is in the "Releases that change a resource default" class** (`docs/release-checklist.md:212-253`)
and the classification is not marginal: it changes a default the operator never set, on installs whose
`.env` says nothing about it, and the symptom is that something which worked yesterday errors today
with nothing tying it to the upgrade. All three steps of that section apply, and **step 3 is the one
that gets skipped**:

1. **Operator section in `docs/self-hosting.md` under `## Upgrading`**, following the shape of *The
   heap is bounded from 0.17.0* (line 638ff). Say which *direction* it moves for which *size of
   install*: for a small install nothing changes ever; for a large one, reports, searches and
   whole-workspace writes that used to take a minute now fail at ten seconds. Give the remedy as **a
   value they can type**, not a method: a short table of install size → suggested
   `DB_STATEMENT_TIMEOUT_MS`. State the floor (must stay above `DB_LOCK_TIMEOUT_MS`, and the boot
   fails if it does not — which is friendly, not hostile).
2. **Route to it from where the reader already is**: the new config-table row, and — the one always
   forgotten — the `## Upgrading` prose that carries the `docker compose pull` command, because that
   command is what an upgrader copies *instead of* reading on.
3. **Write the line into the GitHub Release body by hand.** Draft:

> **Statements are now bounded (`DB_STATEMENT_TIMEOUT_MS`, default 10 s).** Before this release a
> single query could run for ever and hold one of the ten pooled database connections while it did;
> ten of them stopped the instance. From now on any statement still running after 10 seconds is
> cancelled and the request answers **`422`** with `errorType: STATEMENT_BUDGET_EXCEEDED` — it is not
> retryable, because an identical retry costs the same time. **On a large install this can turn a
> slow report, search or bulk member removal into an error.** If that happens, raise
> `DB_STATEMENT_TIMEOUT_MS` in `.env` (it must stay above `DB_LOCK_TIMEOUT_MS`) and
> `docker compose up -d`. Database migrations are deliberately not bounded. Details:
> [Statements are bounded from 0.17.0](https://github.com/Zherikhov/hamstrack/blob/main/docs/self-hosting.md#statements-are-bounded-from-0170).

---

## 12. Acceptance criteria

A reviewer or `test-runner` can check each of these.

**Behaviour**

1. A transaction that runs `SELECT pg_sleep(<T+1s>)` is cancelled at approximately *T* with SQLSTATE
   `57014`, and the endpoint answers **422** with `errorType: "STATEMENT_BUDGET_EXCEEDED"` and **no**
   `Retry-After` header.
2. The response body names the budget in human units and does **not** contain the property name, the
   env var name, any SQL, or any table name — **nor the word administrator**. That last clause is
   not decoration: as first drafted, §5.4 ended the sentence with "otherwise ask your
   administrator", which passes every other clause of this criterion while sending a Cloud tenant
   to somebody who cannot reach the setting.
3. A WARN log line is emitted naming the HTTP method, the mapped path pattern, the elapsed budget and
   the property/env-var to change.
4. The `hamstrack.db.statement_budget_exceeded` counter increments, tagged by method and **mapped pattern**
   (no raw URI, no tenant id).
5. A normal request (well under the budget) is unaffected, and `PermissionResolutionQueryCountTest`
   still passes with its existing counts (2 / 4 / 0) — i.e. the bound is invisible to Hibernate's
   statement statistics.

**Coverage — the point of the whole design**

6. A transaction opened through an arbitrary, *unmodified* `@Transactional` service method reports a
   non-zero `statement_timeout` from `current_setting('statement_timeout')`. Sample at least one
   read service, one write service, one `TransactionTemplate` caller (`AttachmentService`) and one
   bare Spring Data repository call made with no surrounding transaction. **The failure message of
   this test is the propagation checklist** — the `ThrottleCoverageTest` pattern
   (`CLAUDE.md` → *A throttle is earned by the work a handler does*).
7. `SHOW statement_timeout` inside an exempted transaction reports `0`, and reports the bound again
   in the *next* transaction on the same pooled connection — proving `SET LOCAL` reverts and nothing
   leaks into the pool.
8. Calling the exemption API outside a transaction fails loudly (the `MANDATORY` + `Assert.state`
   pair), never silently.

**Flyway**

9. Migrations run unbounded. Assert the *mechanism*, not the symptom: Flyway does not resolve the
   application `PlatformTransactionManager`, **and** `spring.datasource.hikari.connection-init-sql`
   is unset — with a failure message explaining that a session-level bound would be inherited by
   every migration. (A whole-suite run against a fresh database is the integration-level check: all
   migrations apply.)

**Configuration**

10. `DB_STATEMENT_TIMEOUT_MS=0`, `=500`, `=700000` and `=` (blank) each **fail startup**, with the
    blank case failing because the component is a primitive `int`.
11. A statement bound at or below `app.locking.lock-timeout-ms` fails startup with the §5.3 message.
12. With the defaults (3000 / 10000), a lock-wait timeout still answers **409 + Retry-After** — i.e.
    the existing lock contract is demonstrably still reachable and was not shadowed.

**Non-regression on the refusal taxonomy**

13. Pool exhaustion (Hikari `connectionTimeout`) does **not** answer 422 — the new handler is bound
    to `QueryTimeoutException`, not to `TransientDataAccessException`.
14. A cancelled write leaves no partial row and no `@Version` bump; a cancelled read emits no SSE
    event and stores no cache entry.

**Docs**

15. `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` describe the 422, its `errorType`, and
    the "no `Retry-After`, identical retries fail identically" contract, and distinguish it from the
    lock 409.
16. `docs/self-hosting.md` carries the config row, the `## Upgrading` section and a `## Contents`
    entry; every added link is clicked once on the rendered page (the lazy-continuation trap,
    `docs/release-checklist.md:171-177`).
17. `docs/self-hosting.md:165`'s stale enumeration of lock-bound call sites is replaced by a
    category.

---

## 13. Open questions, with the answer I recommend taking

1. **Shape D (transaction manager) vs shape C (session default + Flyway datasource split)?**
   → **Take D.** It is verifiable from the code we have, exempts Flyway by construction, needs no
   datasource split, and adds no operator surface. Revisit C only with a measurement in hand, and
   only after confirming Boot 4's `spring.flyway.url` still yields a separate `DataSource`.
2. **10 s, or something else?** → **10 s.** It satisfies three independent constraints
   simultaneously (§5.2). If it must move, move it *up* first: a bound that is too tight produces
   user-visible failures on legitimate work, while one that is slightly loose still deletes the
   unbounded case.
3. **422, or 503/504/500?** → **422.** The decisive argument is that 5xx is the class that
   intermediaries auto-retry, and auto-retrying this is actively harmful (§5.4).
4. **Should the body name the env var?** → **No.** Value in the body, knob in the log (§5.4). If the
   acceptance criterion is read as requiring the knob on the wire, push back — the operator's copy of
   the refusal is the log line, and it names the knob literally.
5. **Should DC and Cloud differ?** → **No.** Identical default; a deployment that wants a different
   bound sets the environment variable (§11).
6. **Rename `LockTimeout`?** → **No, and keep the two concerns in separate classes.** Under shape D
   the statement bound lives in the transaction manager plus a small `StatementTimeout` exemption
   helper, and `LockTimeout` keeps exactly one true claim. A class called `LockTimeout` that also set
   statement timeouts would be the `CLAUDE.md` naming-rot failure by construction. Do amend
   `LockTimeout.java:56-59` (which currently *prescribes* the rejected opt-in shape) and
   `:61-67` ("the first statement of the transaction" → "the first statement the service issues").
7. **Do reports deserve a *tighter* bound than everything else?** → **Not now.** One number plus
   exemptions. The narrowing mechanism (`SET LOCAL` from the helper) already exists if evidence ever
   demands it; a second knob shipped without evidence is a second thing to misconfigure.

---

## 14. The highest-risk assumption, stated plainly

**That 10 seconds is above every legitimate statement on every existing install.**

Everything else here is reversible by reading code. This one is a claim about *other people's data*,
and it cannot be verified from this repository. If it is wrong, the failure is not subtle: on the
largest self-hosted tenant, a report, a search, or — worst — a **workspace member removal** starts
returning 422 where it used to return a slow 200, and the caller of a member removal has nothing to
narrow. That is precisely the release-checklist's *"would the change look like a fault to somebody
who was not told"* test, and it is why §11's step 3 (the hand-written Release line) is not optional
paperwork but the mitigation itself.

Two things reduce the blast radius and neither eliminates it: the bound is generous relative to any
measured request, and the remedy is one `.env` line an operator can apply without a code change. The
one measurement worth taking *before* merge, if any is taken at all, is the workspace-wide unassign
(§6.3) on the largest workspace anyone can produce — it is the only *write* in the product whose cost
is a single statement over a whole tenant, and it is the one refusal a user could not have avoided.
