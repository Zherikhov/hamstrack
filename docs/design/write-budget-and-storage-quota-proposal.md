# Write budget and workspace storage quota (HD-191)

**Status:** Proposed · **Release:** 0.18.0 · **Story, High, 5 sp**
**Supersedes nothing. Does not close HD-174 (§14.1).**

---

## 1. Problem & goal

Every limiter in Hamstrack exists because somebody noticed one expensive path. Three do exist —
the per-IP auth window, the per-principal reports budget, the per-principal search budget — and
between them they cover reads and authentication. **The write surface has no budget of any kind,
and no workspace has a storage bound.** `spring.servlet.multipart.max-file-size` refuses one
25 MB part; nothing refuses the ten-thousandth. On Cloud the backend is S3, where the bill is
per byte stored *and* per request made, so an authenticated member with an upload control is
today an unmetered charge on the operator's card; on DC the same member is an unmetered claim on
the operator's disk.

Success is three things a reviewer can check: a mutating request costs the caller a unit of a
finite budget, a workspace has a byte ceiling it cannot cross, and both are visible — as numbers
an owner can read before the ceiling arrives, and as metrics an operator can alert on.

**The rule this ticket is an instance of, already learned twice and recorded in `CLAUDE.md`:** *a
throttle is earned by the work a handler does, not by where it is mounted.* Handing 25 MB to S3
is more work than a search, and a search is throttled.

---

## 2. Scope

### In scope

- A **per-principal request budget** over the mutating content surface (issue, comment,
  attachment and rank writes), spent in an interceptor, shaped exactly like the two budgets that
  already exist.
- A **per-principal upload-byte budget**, spent at the upload door, because a request count does
  not bound bytes and bytes are the thing that costs money.
- A **per-workspace storage quota** in bytes, enforced before any byte is handed to
  `FileStorage`, refusing with a status that names the limit and the current usage.
- **Usage readable** by workspace members (the summary) and by holders of `workspace.edit` (the
  per-project breakdown), plus the SPA surfaces that render both.
- A **reconciler** that makes the accounted number true rather than merely present, and a drift
  metric that says when it is not.
- **Metrics and alert rules** for refusals, fill level and drift.
- Config wired through the full chain: `application.properties` → profile overrides → compose →
  `.env.prod.example` → `docs/self-hosting.md` → `docs/api-*.md` → `openapi.yaml`.

### Out of scope (non-goals)

- **Per-issue and per-project sub-quotas.** The ticket asks for the workspace bound; a nested
  hierarchy of ceilings multiplies the refusal vocabulary without bounding anything the workspace
  ceiling does not already bound.
- **Billing, tiers, or a paid quota upgrade path.** The quota is an instance property set by the
  operator. There is no product surface for buying more.
- **Deleting anything to make room.** A quota bounds growth. Nothing in this ticket deletes,
  archives or expires an attachment, and lowering the quota below current usage does not
  retro-delete (§6.9).
- **`FileStorage.list(prefix)`.** Orphan-blob reconciliation stays an operator runbook per
  backend (§7.4, OQ-B2).
- **Upload idempotency.** A retried upload creates a second attachment and is charged twice, as
  it is today.
- **Bounding workspace creation.** `POST /api/workspaces` is unbudgeted and stays unbudgeted —
  but this ticket stops it being *unnoticed* (§9.3).
- **HD-174** (the planning-read budget). Different surface, different denomination — §14.1.

---

## 3. Actors & permissions

| Actor | What they can do | Gate |
|---|---|---|
| Any workspace member | Read the workspace storage **summary** (`GET …/storage`) | `requireMember` only — no permission beyond membership. The numbers are the same ones the refusal already hands them (§6.6). |
| A member holding `attachment.create` in a project | Upload, and be refused by quota or budget | Existing `Permission.ATTACHMENT_CREATE`, checked before the quota (§6.4) |
| A member holding `workspace.edit` | Read the **per-project breakdown** (`GET …/storage/projects`) | `ctx.permissions().require(Permission.WORKSPACE_EDIT)` |
| Operator (DC) / platform operator (Cloud) | Set the quota, the budgets, the warn threshold and the reconcile schedule | Environment variables (§10) |

**No new permission constant.** `WORKSPACE_EDIT` is what the built-in Owner and Admin hold, is
what already gates Settings → General in the SPA (`WorkspaceSettingsArea.SECTIONS`), and is the
grant whose meaning is "workspace-wide settings". A permission key is wire format and permanent
(`Permission`'s javadoc); minting one for a single read is a permanent commitment bought for
nothing. The catalog stays at its current size and no migration is involved either way.

**Tenancy.** Both new endpoints resolve through `WorkspaceAccessService.requireMember`, so a
non-existent workspace and a non-member are **both 404**, never 403. The usage aggregate is read
by `workspace_id` — never by a project id taken from the request and never by joining outward
from an unscoped row. A 403 appears on exactly one door: a proven member without
`workspace.edit` asking for the breakdown.

---

## 4. What exists today (read this before building)

Stated with file references, because the ticket's whole risk is building beside the existing
machinery instead of on it.

### 4.1 The three limiters, and what each is keyed on

| Limiter | Key | State | Where it is spent | Refusal |
|---|---|---|---|---|
| Auth abuse | **client IP** (or the rightmost `X-Forwarded-For` when `app.rate-limit.trust-forwarded-for`) | `ConcurrentHashMap` in `RateLimitService`, per node | `AuthRateLimitFilter`, six literal URLs registered in `RateLimitConfig` | 429 + `Retry-After` |
| Login backoff | **submitted email**, existing or not | `ConcurrentHashMap` in `RateLimitService`, per node | `AuthService.login` | 429 + `Retry-After` |
| Reports | **principal (user id)** | `ConcurrentHashMap` in `PerPrincipalMinuteBudget`, per node | `PrincipalThrottleInterceptor`, patterns in `ReportRateLimitConfig` | 429 + `Retry-After` |
| Search | **principal (user id)** | same base class, own instance | same interceptor type, patterns in `SearchRateLimitConfig` | 429 + `Retry-After` |
| Rank rebalance | **project** | `IssueRankService`, per node, outside the master switch | inside the service | 429 + `Retry-After` |
| Invitation / auth-mail ceilings | **recipient mailbox key** | `mail_send_events` **in PostgreSQL** — cluster-wide and exact | inside `RecipientMailThrottle` / `InviteSenderVolumeBudget` | 429, or silently dropped where the endpoint must not disclose |

So the ticket's phrasing is precise and matters: **the auth limiter is per-IP and this ticket asks
for per-principal.** The per-principal mechanism already exists and is
`common.ratelimit.PerPrincipalMinuteBudget` — a fixed epoch-minute window in a
`ConcurrentHashMap<UUID, Window>`, with `enabled()` / `limit()` / `kind()` / `surface()` as its
only abstract members. Everything else — the window arithmetic, the eviction sweep, the
`Retry-After` computation, the decision to meter rather than log — is shared and must not be
copied.

**None of it survives more than one replica** except the recipient-keyed mail ceilings. The
in-memory budgets are per node, so N replicas allow N× the configured number, and a restart
resets them. That is written down and accepted (`PerPrincipalMinuteBudget`'s javadoc,
`docs/self-hosting.md`) because these are bounds on abuse, not invariants. **The storage quota is
not in that category** and must not inherit that property — §5.2.

### 4.2 The refusal shape

`RateLimitedException extends AppException` (429) carries `retryAfterSeconds`;
`GlobalExceptionHandler.handleRateLimited` renders a `ProblemDetail` and sets the `Retry-After`
header. `spring.mvc.problemdetails.enabled=true`, so `detail` reaches the client and the SPA's
`request()` renders it. Extensions are set with `problem.setProperty(...)`; the established
discriminator convention is a string property named **`errorType`**
(`STATEMENT_BUDGET_EXCEEDED`, `REFERENCE_CONSTRAINT_VIOLATION`, `ROLE_LIMIT_REACHED`, …).

### 4.3 The seal, and exactly what this ticket adds to it

`src/test/java/com/hamstrack/report/ThrottleCoverageTest.java` holds **two opposite properties**:

- `everyReportAndSearchHandlerSitsBehindAPerPrincipalThrottle()` — coverage. Every handler in
  `THROTTLED_PACKAGES` (`com.hamstrack.report.controller`, `com.hamstrack.search`) must have a
  `PrincipalThrottleInterceptor` in the chain the real `RequestMappingHandlerMapping` builds for
  its own mapped pattern. `EXEMPT` is empty today.
- `theThrottledPathSetIsSealed()` — the documentation property. Asserts the *exact* set of
  patterns each configurer registers, and its failure message is `PROPAGATION_CHECKLIST`, the
  nine-artefact list a maintainer must edit when the set changes.

**This ticket changes that set, so it edits that test deliberately.** Concretely:

1. `registeredPatterns(reportRateLimitConfig)` gains **`/api/workspaces/*/storage/projects`**
   (§9.2 — the breakdown is O(attachments in the workspace), which is the reports budget's
   denomination).
2. A **third** configurer assertion appears: `registeredPatterns(writeRateLimitConfig)` must be
   exactly `/api/workspaces/*/projects/*/issues/**`.
3. `PROPAGATION_CHECKLIST` gains the new artefacts (`WriteProperties`, `StorageQuotaProperties`,
   the `app.write.*` and `app.storage.quota.*` blocks in `application.properties` and
   `.env.prod.example`, the `docs/self-hosting.md` rows, the `docs/api-*.md` sections, the
   `openapi.yaml` 409/429 responses, `api.ts`), and its closing paragraph gains the **third**
   axis this ticket introduces — see 4 below.
4. A new sibling seal, `WriteThrottleCoverageTest`, on the **method** axis rather than the path
   axis: *every mutating handler under `/api/workspaces/**` is either behind a
   `PrincipalThrottleInterceptor` that applies to its HTTP method, or listed in `EXEMPT` with a
   written reason.* Path-shaped coverage cannot express this, because the write budget is
   method-conditioned and the packages it lives in are full of deliberately unbudgeted reads
   (`BacklogController` — HD-174, and `PlanningThrottleParityTest` defends that emptiness on
   purpose). The `EXEMPT` set is populated by **category**, each with its own sentence:
   administrative and taxonomy writes (permission-gated, low frequency, bounded by the catalog
   they edit); membership and invitation writes (already bounded by the recipient-keyed ceilings
   on a different axis); saved-filter writes (already on the search budget); workspace creation
   (§9.3 — unbudgeted, and this is where that fact becomes visible).
5. `PerPrincipalMinuteBudget` gains a second seal obligation of its own: the upload-byte budget
   and the quota are **not** path bindings (they need `MultipartFile.getSize()` and a resolved
   workspace), exactly as the invitation ceilings are not. Their seal is a door test —
   `AttachmentDoorsTest`: *every call site of `FileStorage.store` is preceded, in the same
   method, by a quota reservation and a byte-budget spend.* Asserted down to the enclosing
   **method**, the shape `AuthMailDoorsTest` already uses.

If you are adding an expensive surface after this ticket, there are now **three** questions, and
the checklist must say so: is it a path that needs a budget, does it send mail to an address the
caller chose, and does it hand bytes to `FileStorage`?

### 4.4 The upload path as it actually runs

`AttachmentService.upload` is deliberately **not** `@Transactional` (HD-76: blob I/O must not pin
a Hikari connection):

1. cheap pre-checks with no DB — `file.isEmpty()` → 400, `validateUpload` → 413 over
   `app.attachments.max-file-size`, 415 for a disallowed extension;
2. a short `txTemplate` transaction resolves the issue through `WorkspaceAccessService.requireIssue`,
   requires `ATTACHMENT_CREATE`, refuses an archived project with 409, persists the
   `IssueAttachment` row with a **server-generated** key `ws/{wsId}/issues/{issueId}/{uuid}` and
   commits;
3. `fileStorage.store(...)` runs **after** that commit, on the request thread;
4. on store failure a second short transaction deletes only the reserved row, then 500.

Two facts follow and both are load-bearing. The row is committed **before** the blob is written,
so a quota check placed in step 2 is unambiguously before any byte reaches S3. And
`attachment.setSizeBytes(file.getSize())` is the **parsed** size, not a client-declared header —
so the accounting cannot be understated by a lying `Content-Length`.

---

## 5. The decisions

### 5.1 Where the byte total lives → **a counter row in its own table, maintained by a database trigger**

The three candidates fail differently.

- **`SUM(size_bytes)` on every upload.** Correct and unbounded: `issue_attachments` has no
  `workspace_id` column today, so the aggregate is a three-level join out to `workspaces`, on the
  hot upload path, growing with every file the tenant has ever kept. Rejected as the online check.
- **A counter column on `workspaces`.** Cheap and drifts, and `CLAUDE.md` has a scar directly on
  the shape: a DB-maintained counter written by native SQL must be `@Column(updatable = false)`,
  or a stale managed copy clobbers it — which shipped once as duplicate issue numbers
  (`projects.issue_seq`, repaired by V9). Rejected: the `Workspace` entity is loaded, mutated and
  saved on several paths, so the trap is live rather than theoretical.
- **A counter in a dedicated table, written only by a trigger.** Chosen.

```
workspace_storage_usage (workspace_id PK → workspaces ON DELETE CASCADE,
                         bytes_used BIGINT NOT NULL DEFAULT 0,
                         attachment_count BIGINT NOT NULL DEFAULT 0,
                         updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())
```

Four properties earn it:

1. **The clobber scar is answered structurally, not by remembering an annotation.** No JPA entity
   maps this column as writable; the application reads it through a projection and never assigns
   it. There is no managed copy to go stale.
2. **It is a row that can be locked, and it is not `workspaces`.** `CLAUDE.md` forbids
   `FOR UPDATE` on `workspaces` (every FK child insert in the tenant queues behind it). Nothing
   references `workspace_storage_usage`, so locking it serialises only what it is meant to
   serialise: concurrent uploads into one workspace (§6.7).
3. **The trigger makes the number true for row lifecycles the application does not participate
   in.** `issue_attachments` cascades from `issues` → `projects` → `workspaces`. A
   service-level decrement covers `AttachmentService.delete` and nothing else — every issue
   delete, project delete and future workspace purge would ratchet the counter upward for ever.
   PostgreSQL's referential cascade performs ordinary row deletes, which fire row-level triggers,
   so the counter follows the rows by construction, including through paths nobody has written
   yet. **This is the spec's highest-risk assumption — see §15.**
4. **It is reconcilable exactly and cheaply**, once `issue_attachments` carries `workspace_id`
   (§8.1): `SELECT workspace_id, SUM(size_bytes), COUNT(*) … GROUP BY workspace_id` over an
   indexed column, off the hot path.

**No `CHECK (bytes_used >= 0)`.** A check constraint on a trigger-maintained counter can only
fire when the counter is already wrong, and it fires on the statement that is trying to *reduce*
it — i.e. it would convert a benign drift into an inability to delete. The trigger clamps with
`GREATEST(0, …)` instead, and drift is surfaced as a metric (§11.3) rather than as a blocked
delete.

### 5.2 What makes the number true

The counter is an accounting of **rows**, not of objects in the store. It is exactly
`SUM(issue_attachments.size_bytes)` for the workspace, and three mechanisms keep it that way:

- the trigger, for every INSERT / DELETE / UPDATE of `size_bytes` (written for all three, not for
  today's two, because "attachments are never updated" is a claim about today);
- the **reconciler** (§7.3), which recomputes from the rows per workspace, writes the corrected
  value under the same row lock a live upload takes, and publishes the largest absolute delta it
  saw as `hamstrack.storage.drift_bytes`;
- the seed in the migration, which computes the initial value from the rows.

Unlike the in-memory budgets, this state is in PostgreSQL: it is **cluster-wide, exact, and
survives a restart**. It is the second limiter in the product with that property, and for the
same reason as the first (ADR-0015): a bound that resets on deploy is a bound the other side
waits out — and a bound that divides by replica count is not a bound on a bill.

### 5.3 Deletion, and the blobs that outlive their rows

**The quota counts rows, and the divergence is real and permanent in the tenant's favour.** Rows
cascade; objects do not. Two paths create unaccounted objects:

- an upload whose blob was stored and whose *compensating* delete then failed (logged at WARN,
  `AttachmentService.compensateFailedUpload`) — actually the *opposite* direction: the row is
  gone, the blob remains, so it is unaccounted;
- `AttachmentService.deleteFromStorageAfterCommit`, which by design never fails a request: the
  row is deleted, the blob delete is attempted after commit and a failure is logged at ERROR and
  swallowed.

Both leave an object with no row. The quota cannot see them, the store still charges for them,
and `account-deletion-proposal.md` §7 documents the same asymmetry from the erasure end.

**Decision: the quota counts rows; orphan objects are reconciled out of band, by the operator,
per backend.** `FileStorage` gains **no** `list(prefix)` method in this ticket. Reasons: an
interface method invites an online caller that pages a bucket on a request thread; the procedure
already exists in prose for both backends in `account-deletion-proposal.md` §7 (walk
`ws/{wsId}/` under `app.storage.local.base-dir` on DC; `list-object-versions` + `delete-objects`
including delete markers on the versioned Cloud bucket, because a plain `DeleteObject` only
writes a marker); and the number the runbook needs — the live key list — is already obtainable
from `issue_attachments.storage_key`. What this ticket adds is a **runbook section in
`docs/self-hosting.md`**: list the store's keys under `ws/{wsId}/`, subtract
`SELECT storage_key FROM issue_attachments WHERE workspace_id = …`, delete the difference. See
OQ-B2 for when that should become code.

A deletion of an attachment therefore **decrements the counter immediately and in the same
transaction as the row delete** (the trigger), before the blob delete is even attempted. That is
the right order: the tenant is credited for space they asked to release even if the store is
temporarily unreachable, and the reconciler agrees with that view because it counts the same rows.

### 5.4 Where the refusal happens, and what is and is not guaranteed

**The guarantee this ticket delivers: no byte of a refused upload is ever passed to
`FileStorage`.** The reservation runs inside step 2 of §4.4 — the same short transaction that
resolves tenancy and persists the row — and `fileStorage.store` is only reached after that
transaction commits. This is provable by construction and is proved by a test that injects a
`FileStorage` test double and asserts `store` was never invoked (§13, AC-9).

**The guarantee this ticket does not deliver: "before any byte reaches the server".** Multipart is
resolved in `DispatcherServlet.checkMultipart`, which runs **before** handler mapping and
therefore before every interceptor and every controller;
`spring.servlet.multipart.file-size-threshold` is unset (Boot's default 0), so Tomcat has already
streamed the part to a temp file on the app server's disk by the time any Hamstrack code sees it.
Nothing expressible in Spring MVC can refuse earlier.

What the earlier guarantee would cost, stated honestly so nobody re-derives it from a surprise:
a servlet `Filter` ahead of the `DispatcherServlet`, keyed on `Content-Length`, would have to
parse the JWT, resolve workspace membership and read the usage row **itself** — i.e. a second
authorization resolution outside `WorkspaceAccessService`, which is precisely the "two predicates
for one question" that HD-123 exists to have deleted, and a place where the 404-not-403 rule
would have to be re-implemented. It would also not stop the client from *sending* the bytes: an
early refusal on an undrained body hits Tomcat's `maxSwallowSize` and the client sees a
connection reset instead of the JSON refusal, which turns a clear 409 into "the upload broke".
And it would key on a header the client controls, where the current check keys on the parsed
size. **Recommendation: do not build it.** The transient disk cost is already bounded by
`spring.servlet.multipart.max-request-size` (25 MB) × concurrency, and the *rate* at which that
bound is paid is exactly what the write budget now bounds.

### 5.5 Which status, and what the refusal may say

**409 Conflict**, with `errorType: "STORAGE_QUOTA_EXCEEDED"` and **no `Retry-After`.**

- **413 `CONTENT_TOO_LARGE`** is about this representation being too large, and the file is very
  often well inside `app.attachments.max-file-size`. It is also already taken: `validateUpload`
  and `GlobalExceptionHandler.handleMaxUploadSize` both answer 413 with "File is too large". Two
  refusals with opposite remedies must not share a status *and* a shape.
- **429** implies that waiting helps. Waiting never frees a byte. A `Retry-After` here would be
  an instruction that cannot work — the argument `handleQueryTimeout` already makes for its own
  422.
- **422 `UNPROCESSABLE_CONTENT`** is this codebase's "well-formed, understood, and we will not
  process it", and it is close. It is rejected because it says nothing about *state*, and this
  refusal is entirely about the state of a resource other than the one in the request — which is
  what 409 is defined as.
- **507 Insufficient Storage** (RFC 4918) is semantically the closest of all and is a **5xx**:
  automatically retried by intermediaries and SDKs, and it renders in the SPA as a crash rather
  than as a sentence. It says "we failed" where we decided. Rejected for the reasons
  `handleQueryTimeout` rejects 503/504.
- **409** carries no retry semantics, is what this codebase already answers for state-blocked
  writes (archived project, in-use-on-delete, invitation already accepted), and reuses the
  `errorType` convention so a client can branch without parsing prose.

**What it may say.** The project's rule is that a refusal may only prescribe an action its reader
can perform, and this refusal has no reader who is guaranteed to be able to act:

- a contributor cannot delete other people's attachments (`ATTACHMENT_DELETE` is own-only for
  most roles), and the space is very often in a project they cannot even see;
- "ask your administrator" is a dead end on **both** models, not one: on Cloud a workspace owner
  cannot raise an instance property, and on DC the reader may well not know who owns the `.env`;
- the space that must be freed may be in a project the reader has no access to, so even a manager
  cannot be told "delete some files" truthfully.

So the sentence **describes the situation and dispatches nobody** — the shape
`handleQueryTimeout` settled on, one sentence true in both deployment models, deliberately not
profile-branched:

> This workspace has used all of its attachment storage (9.8 GB of 10 GB). This file needs
> 12.4 MB. Storage is freed by deleting attachments that are no longer needed.

**The numbers are a deliberate disclosure decision, not a UX one.** `usedBytes`, `quotaBytes`,
`availableBytes` and `fileBytes` are all in the body, for every caller who reaches the refusal.
The argument: the reader is a workspace member who holds `attachment.create` in a project of this
workspace; the figure is a single tenant-wide aggregate with **no per-project resolution**, so
the most it discloses across a project boundary is that somebody, somewhere in a workspace the
reader belongs to, has uploaded a lot. A member who cannot be told how full the workspace is
cannot tell "I am blocked" from "the server is broken", which is the failure mode a quota nobody
can see produces. The **breakdown** — which names projects and their volumes, and is real
disclosure — is behind `workspace.edit` (§9.2). Precedent for publishing workspace-scoped
aggregates in a refusal body: `handleRoleInUse`.

### 5.6 Per-principal and per-workspace defend different things and are not substitutes

| | Write / upload budget | Storage quota |
|---|---|---|
| Keyed on | the **actor** | the **tenant** |
| Defends | the instance: connection pool, CPU, S3 **request** count, SSE and notification fan-out, log volume | the bill and the disk: cumulative **bytes** stored |
| State | in memory, per node — divides by replica count, resets on restart | PostgreSQL — cluster-wide, exact, survives restart |
| Denomination | requests/minute and bytes/minute | bytes, cumulative, no window |
| Resets | every minute | never |

They interact, and the interaction must be stated rather than hoped away: **one member can
exhaust a shared workspace quota entirely within their own budget.** At the proposed defaults a
principal may upload 250 MB/minute, so a 10 GB Cloud quota is reachable by one well-behaved-looking
member in roughly forty minutes of sustained effort. That is the correct outcome — the quota is
what stops it, permanently, and the budget is what stops the *pool* from being the thing that
notices. Conversely the quota never sees churn: upload → delete → upload leaves the total
unchanged and bills every PUT and every stored byte in between, which is exactly what the
byte-rate budget bounds. Neither substitutes for the other; removing either leaves a real hole.

---

## 6. Behaviour & rules

### 6.1 Write budget (per principal, per minute, request-denominated)

- Mechanism: a new `WriteRateLimiter extends PerPrincipalMinuteBudget`, `kind()` =
  `RateLimitKind.WRITE_REQUESTS`, `surface()` = `"write requests"`, `limit()` =
  `app.write.requests-per-minute`.
- Bound by `WriteRateLimitConfig implements WebMvcConfigurer` to the single pattern
  `/api/workspaces/*/projects/*/issues/**`, which covers issue create/update/delete, comment
  create/update/delete, attachment upload/delete and rank — i.e. **the mutating content
  surface**, as a category rather than as a list of today's three endpoints.
- **Method-conditioned.** `PrincipalThrottleInterceptor` gains an optional immutable
  `Set<String> methods` (empty = every method, which is what the two existing registrations
  pass). The write registration passes `POST, PUT, PATCH, DELETE`. Keeping the same interceptor
  *type* is what lets `ThrottleCoverageTest`'s "is this handler throttled?" stay a type question.
- **All mutating methods, not only creation.** The ticket names creation; a budget that covered
  only `POST` would leave the surface half-budgeted, which `PlanningThrottleParityTest` already
  names as worse than either whole answer — a client refused on the create retries with the
  patch. An issue update writes history rows, bumps `@Version`, fans out SSE and notifications;
  it is not cheaper.
- Refusal: 429 + `Retry-After`, detail `Too many write requests — retry in Ns`.
- Off switch: `app.rate-limit.enabled`, the existing master switch, which gains this budget in
  its enumeration (§10.2).

### 6.2 Upload-byte budget (per principal, per minute, byte-denominated)

- `PerPrincipalMinuteBudget` gains `require(UUID userId, long cost)`; the existing
  `require(userId)` becomes `require(userId, 1)`. `Window.count` becomes an `AtomicLong` and
  `limit()` returns `long`. One mechanism, two denominations — the class's own argument for not
  copying the loop applies verbatim.
- `UploadByteBudget extends PerPrincipalMinuteBudget`, `limit()` =
  `app.write.upload-bytes-per-minute` in bytes, `kind()` = `RateLimitKind.UPLOAD_BYTES`,
  `surface()` = `"uploaded bytes"`.
- **Spent inside `AttachmentService.upload`**, not in an interceptor, because the cost is
  `file.getSize()` and an interceptor has neither the parsed part nor a reason to look at it.
  Spent in step 1 of §4.4 — after the cheap file-policy checks, before any DB work — so a
  refused upload takes no lock and touches no row.
- Refusal: 429 + `Retry-After`, detail `Too many uploaded bytes — retry in Ns`. Discloses
  nothing: the budget is the caller's own.
- Because it is not a path binding, it is sealed by `AttachmentDoorsTest` (§4.3 item 5), not by
  `ThrottleCoverageTest`.

### 6.3 Storage quota — the happy path

Inside step 2's transaction, after `requireIssue`, after
`permissions().require(ATTACHMENT_CREATE)`, after the archived-project 409, and **before**
`attachmentRepository.save`:

1. `lockTimeout.applyToCurrentTransaction()` — bound first, then lock. The standing rule.
2. `INSERT INTO workspace_storage_usage (workspace_id) VALUES (?) ON CONFLICT DO NOTHING` —
   idempotent; the row need not pre-exist, so nothing couples to workspace creation.
3. `SELECT bytes_used FROM workspace_storage_usage WHERE workspace_id = ? FOR UPDATE`.
4. If `quotaEnabled && bytes_used + file.getSize() > quotaBytes` → throw
   `StorageQuotaExceededException` (409). The transaction rolls back; no row, no blob, no
   counter change.
5. Otherwise `save(attachment)`. The row insert fires the trigger, which upserts the counter,
   **inside the lock**. Commit releases the lock; the next reserver reads the new total.

### 6.4 Order of refusals on the upload door

`400 empty` → `413 too large` → `415 bad extension` → **429** byte budget (all four before any DB
work) → **404** non-member or unknown issue → **403** missing `attachment.create` →
**409 archived project** → **409 quota**.

The byte budget is **fourth**, not last: it is spent in the cheap pre-check phase, textually
before tenancy resolution, because that is where the parsed size is known and where a refusal
costs no lock and no row. That is safe for the reason `PerPrincipalMinuteBudget` gives — the key
is the caller, so the 429 is identical for a real workspace, a nonexistent one and somebody
else's, and a refusal that cannot vary with the target discloses nothing about it, leaving the
404-for-everything contract untouched.

Three rules pin the rest and all already exist in the codebase: a 403 must never depend on
project state (so the permission precedes the archived check — `AttachmentService.delete`'s
javadoc, §10.3.6 of the roles spec), a refused request must not have paid for a lock (so every
cheap check precedes the reservation), and between two 409s archived comes first: it is about
*this* project, it needs no lock, and a quota message on an archived project would send the
reader to fix the wrong thing.

The order is asserted behaviourally by
`WriteBudgetTest.theByteBudgetIsSpentBeforeTenancyAndItsRefusalNamesNoTarget` — the same list
appears in `AttachmentService.upload`'s javadoc, and both were written with the 429 last while
the code spent it fourth.

### 6.5 Invariants

- `bytes_used` equals `SUM(size_bytes)` over live `issue_attachments` rows of that workspace, at
  every commit boundary.
- `bytes_used` is never written by application code. Only the trigger and the reconciler write it.
- The quota is checked against the **parsed** size, never a client-supplied length.
- A quota refusal happens strictly before `FileStorage.store`.
- `quotaBytes >= app.attachments.max-file-size` — enforced **at startup**, because a quota that
  can never admit a single legal file is a misconfiguration, not a policy.
- The quota is a bound on **growth**. No path in this ticket deletes, archives or expires
  anything.
- Reads and downloads are never quota-gated. A full workspace stays fully readable.

### 6.6 Reading usage

`GET /api/workspaces/{workspaceId}/storage` — any member. One indexed single-row read plus two
properties. Returns the same figures a refusal would, which is why it needs no permission beyond
membership: it discloses nothing the upload door does not already disclose to the same person.

`GET /api/workspaces/{workspaceId}/storage/projects` — `workspace.edit`. A grouped aggregate over
`issue_attachments` by `project_id`, restricted to the workspace. This is the owner's answer to
"where did the space go", and it is real disclosure (project names and volumes), hence the gate
and hence the budget (§9.2).

### 6.7 Concurrency

Two uploads that each fit can jointly exceed — the classic check-then-write. The `FOR UPDATE` on
the single usage row is what forbids it: the second reserver blocks until the first commits, then
reads a total that includes the first file. Serialisation is **per workspace**, on a row nothing
else references, so it costs nothing to any other tenant and blocks no FK child insert.

A wait that exceeds `app.persistence.statement-timeout-ms`' lock bound surfaces as the existing
409 + `Retry-After: 1` from `handlePessimisticLock` — which is correct here: unlike the quota
refusal, a lock loss really is fixed by retrying.

### 6.8 Failure modes on the store

- **Store fails after the row commits.** The compensating delete removes the row; the trigger
  decrements; the caller gets the existing 500. Net effect on the quota: none.
- **Compensation itself fails.** The row survives, the blob does not. The workspace is charged
  for bytes that are not stored — **conservative**, self-consistent (the reconciler counts the
  same row), and already logged at WARN with the key for out-of-band cleanup.
- **Blob delete fails after a row delete.** The tenant is credited immediately; the object is an
  orphan and is invisible to the quota (§5.3). This is the direction that favours the tenant and
  is the one the runbook exists for.

### 6.9 Changing the quota

- **Lowered below current usage.** Existing content is untouched and fully readable; every new
  upload is refused. The SPA's Storage page shows a bar over 100% rather than clamping it,
  because clamping would hide the state that explains the refusals.
- **Raised.** Effective immediately, next request. Nothing is cached.
- **Disabled** (`app.storage.quota.enabled=false`). Usage is still counted, still reported and
  still reconciled; nothing is refused. The reasoning is `RecipientMailThrottle`'s: a switch that
  stops the bookkeeping means an instance that turns it back on resumes with a blank window, and
  the operator loses the number that would tell them what to set.

### 6.10 Edge cases

| Case | Behaviour |
|---|---|
| First upload in a workspace, no usage row | The `ON CONFLICT DO NOTHING` insert creates it; a missing row reads as 0 everywhere. |
| Zero-byte file | Already 400 before any of this (`file.isEmpty()`). |
| File exactly fills the quota | Allowed — the comparison is `>`, not `>=`. |
| Quota already exceeded and the new file is 1 byte | Refused. The message reports the actual figures, so it does not claim the file is the problem. |
| Issue deleted with attachments | Cascade fires the trigger per row; counter falls. Blobs are deleted after commit by `removeStoredFilesForIssue`, unchanged. |
| Project or workspace deleted | Same cascade chain. The workspace's usage row cascades away with it. |
| Concurrent delete and upload in one workspace | Both take the row lock; they queue. |
| Reconciler runs during an upload | It takes the same row lock per workspace, so it can only observe committed state. |
| Two replicas, both scheduled to reconcile | One `pg_try_advisory_lock` per pass; the loser skips and logs at DEBUG. |
| Counter would go negative | Clamped at 0 by the trigger; the reconciler corrects it and `hamstrack.storage.drift_bytes` reports it. |
| `@Version` double-flush | Not reachable: the reservation is reads → native lock → single `save`, and the counter is not on a mapped entity's writable state. |
| Archived project | 409 archived, before the quota — no lock taken. |
| Storage quota enabled but `FileStorage` is `local` | Identical behaviour. The mechanism is backend-agnostic; only the default number differs (§10.1). |

---

## 7. Data model impact

Next unused migration number, verified against `src/main/resources/db/migration/`: **V26**
(V25 is `mail_send_events_anonymous_index`). Never edit an applied migration.

### 7.1 `V26__storage_quota.sql`

**(a) `issue_attachments.workspace_id`.** Added for three reasons at once: the trigger must know
the tenant without walking two parents (and walking them from inside a cascade is a bet on RI
ordering); the reconciler and the breakdown become indexed single-table aggregates; and it is a
tenancy improvement on a table that currently has none.

```
ALTER TABLE issue_attachments ADD COLUMN workspace_id UUID;
UPDATE issue_attachments a SET workspace_id = i.workspace_id FROM issues i WHERE i.id = a.issue_id;
ALTER TABLE issue_attachments ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE issue_attachments
  ADD CONSTRAINT issue_attachments_issue_ws_fk
  FOREIGN KEY (issue_id, workspace_id) REFERENCES issues (id, workspace_id) ON DELETE CASCADE;
CREATE INDEX issue_attachments_workspace_idx ON issue_attachments (workspace_id);
```

The **composite** FK is the shape `sprint_scope_events` already uses (V18) against the existing
`issues (id, workspace_id)` unique key: it makes "this attachment's workspace is its issue's
workspace" a database fact rather than an application habit. `ON DELETE CASCADE` here, unlike
`sprint_scope_events`, because an attachment has no meaning after its issue is gone.

**(b) The counter table.**

```
CREATE TABLE workspace_storage_usage (
    workspace_id     UUID        PRIMARY KEY REFERENCES workspaces (id) ON DELETE CASCADE,
    bytes_used       BIGINT      NOT NULL DEFAULT 0,
    attachment_count BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

No `CHECK` (§5.1). `BIGINT` — a workspace can legitimately hold more than 2 GB.

**(c) The seed**, computed from the rows, one row per workspace including empty ones.

**(d) The trigger.** `AFTER INSERT OR DELETE OR UPDATE OF size_bytes ON issue_attachments FOR EACH
ROW`, upserting `(+size, +1)` / `(-size, -1)` / the delta, with `GREATEST(0, …)` on both totals
and `updated_at = NOW()`. It must **not** consult `hamstrack.skip_updated_at` — that GUC belongs
to the `issues` rank rebalance and to `updated_at` triggers on business rows; a counter that
skipped its own update because an unrelated bulk operation set a GUC would be a silent drift
generator. Verify at build time that no existing bulk path sets it while touching
`issue_attachments` (it does not today).

### 7.2 Entities

- `IssueAttachment` gains `workspaceId` as a plain `UUID` column mapped
  `@Column(name = "workspace_id", nullable = false, updatable = false)` — set once at creation
  from the resolved context, never re-derived. **Not** a `@ManyToOne` to `Workspace`: nothing
  needs to navigate it, and a lazy association here would be an N+1 waiting for a list endpoint.
- `WorkspaceStorageUsage` — a new read-oriented entity in `workspace.entity`, PK
  `workspace_id`, with `bytesUsed` / `attachmentCount` / `updatedAt` all
  `@Column(insertable = false, updatable = false)`. **This is the structural answer to the
  `issue_seq` scar**: JPA cannot write these columns, so no stale managed copy can clobber them,
  and the guarantee does not depend on anybody remembering an annotation on a future field.
- Entity/schema **width parity is kept by hand**: `ddl-auto=validate` does not compare column
  lengths. Nothing here is a `VARCHAR`, so the exposure is limited to `BIGINT` vs `long`, but the
  migration header must not claim validate will catch anything.
- No PG `ENUM` anywhere; no `CHAR(n)`; ids stay UUID v7 generated by the application;
  `updated_at` on the counter table is written by the trigger, not by `@LastModifiedDate`.

### 7.3 The reconciler

`WorkspaceStorageReconciler` in `workspace.service`, `@Scheduled(cron = "${app.storage.quota.reconcile-cron}")`
(empty string disables the schedule). Per pass:

1. `pg_try_advisory_lock` on a fixed install-wide key; a replica that does not get it skips.
2. Page workspaces. Per workspace, in its own short transaction:
   `lockTimeout.applyToCurrentTransaction()` → `SELECT … FOR UPDATE` on the usage row →
   `SELECT COALESCE(SUM(size_bytes),0), COUNT(*) FROM issue_attachments WHERE workspace_id = ?`
   → write if different, and record `|delta|`.
3. Publish `hamstrack.storage.drift_bytes` = the largest absolute delta of the pass, and stamp
   `hamstrack.storage.drift_refreshed_at`.

**The freshness gauge is not optional and its seed is not zero.** `ProductMetrics`'
`anonymousMailConcentrationRefreshedAt` documents the exact trap: a last-write-wins gauge fails
*silent*, a reconciler that never succeeds leaves the drift gauge frozen at a calm value, and a
sentinel below the alert threshold makes the worst state the one nobody hears about. Seed the
timestamp with **process start**, exactly as that field does, and alert on the age (§11.4).

### 7.4 Orphan objects

No schema, no code. A `docs/self-hosting.md` runbook section per backend, deriving the live key
set from `SELECT storage_key FROM issue_attachments WHERE workspace_id = …` and the stored key set
from the backend's own listing (local subtree walk; `list-object-versions` on the versioned Cloud
bucket, deleting every version and every delete marker). See §5.3 and OQ-B2.

---

## 8. API surface

All paths workspace-scoped. `openapi.yaml` + `docs/api-dc.md` + `docs/api-cloud.md` must follow
(`api-docs-sync`).

### 8.1 `GET /api/workspaces/{workspaceId}/storage`

Any workspace member. **200:**

```json
{
  "quotaEnabled": true,
  "quotaBytes": 10737418240,
  "usedBytes": 8321499136,
  "availableBytes": 2415919104,
  "attachmentCount": 1842,
  "percentUsed": 77.5,
  "warnAtPercent": 80,
  "maxFileBytes": 20971520,
  "asOf": "2026-09-03T10:14:22Z"
}
```

`quotaBytes`, `availableBytes` and `percentUsed` are **`null`** when `quotaEnabled` is false —
absent numbers rather than a sentinel, so a client cannot render `-1 GB remaining`. Bytes are
raw integers everywhere; formatting is the client's business. `asOf` is the counter row's
`updated_at`.

**404** — workspace unknown or caller not a member (never 403). **401** — unauthenticated.

### 8.2 `GET /api/workspaces/{workspaceId}/storage/projects`

Requires `workspace.edit`. **200:**

```json
{
  "asOf": "2026-09-03T10:14:22Z",
  "totalBytes": 8321499136,
  "unattributedBytes": 0,
  "projects": [
    { "projectId": "0198…", "key": "HD", "name": "Hamstrack", "bytes": 5348024320, "attachmentCount": 902 }
  ]
}
```

Sorted by `bytes` descending. `unattributedBytes` = counter total − sum of the breakdown; it is
**published rather than hidden** because a non-zero value is exactly the drift the operator needs
to see, and silently normalising it would make the page lie in the one state it exists for.

**403** `MissingPermissionException` for a proven member without `workspace.edit`. **404** for a
non-member. **429** + `Retry-After` when the reports budget is exhausted (§9.2).

### 8.3 `POST /api/workspaces/{ws}/projects/{p}/issues/{n}/attachments` — new refusals

**409** (new):

```json
{
  "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "This workspace has used all of its attachment storage (9.8 GB of 10 GB). This file needs 12.4 MB. Storage is freed by deleting attachments that are no longer needed.",
  "errorType": "STORAGE_QUOTA_EXCEEDED",
  "quotaBytes": 10737418240,
  "usedBytes": 10510925824,
  "availableBytes": 226492416,
  "fileBytes": 13002342
}
```

**429** (new, two causes, told apart by `detail`): the write budget
(`Too many write requests — retry in Ns`) and the upload-byte budget
(`Too many uploaded bytes — retry in Ns`). Both carry `Retry-After`.

Unchanged: 400 empty, 413 over the per-file limit, 415 disallowed extension, 403 missing
`attachment.create`, 404 tenancy, 409 archived project, 500 store failure.

### 8.4 Every other mutating endpoint under `/api/workspaces/*/projects/*/issues/**`

Gains **429 + `Retry-After`** as a documented response. No body or status change otherwise.

### 8.5 New exception + handler

`StorageQuotaExceededException extends AppException` (409) carrying the four figures and
`errorType`. `GlobalExceptionHandler` gains a handler publishing them as `ProblemDetail`
extensions, in the shape `handleRoleInUse` / `handleStrandedProjects` already use. It is **not**
on `ResponseEntityExceptionHandler`'s list, so the only behaviour that changes is the body of a
status this class did not previously produce here — that sentence belongs in the class javadoc,
which counts its takeovers.

---

## 9. Throttling changes, precisely

### 9.1 New: `WriteRateLimitConfig`

`common.ratelimit.WriteRateLimitConfig implements WebMvcConfigurer`, `@Order` after
`SearchRateLimitConfig` (its pattern overlaps neither today; the order is declared so that a
future overlap is deterministic rather than dependent on bean registration). Registers
`PrincipalThrottleInterceptor(writeRateLimiter, methods = POST/PUT/PATCH/DELETE)` on exactly
`/api/workspaces/*/projects/*/issues/**`.

### 9.2 Changed: `ReportRateLimitConfig` gains `/api/workspaces/*/storage/projects`

Earned by the work: the breakdown is a grouped aggregate over every attachment row in the
workspace — O(workspace content), unbounded by what it returns, which is the reports budget's
exact denomination. It is charged to the reports pot rather than given a fourth one because it is
the same person doing the same kind of thing (looking at an aggregate of their own tenant), and
60/minute is far above any human reading a settings page.

**`GET …/storage` (the summary) is deliberately NOT budgeted**, and this is an exemption with a
reason rather than an omission: it is one primary-key read plus two configuration properties —
cheaper than any endpoint currently behind a budget, cheaper than the handler mapping that routes
it — and it is the number the SPA shows beside the upload control, so starving it would hide the
quota from the person about to hit it. The asymmetry is safe in the direction that matters:
a caller refused on the expensive breakdown who retries the cheap summary gets **less**
information, not a way around the bound. (The reverse asymmetry — refused on the cheap read,
falls back to the expensive one — is the one `PlanningThrottleParityTest` forbids, and it does
not exist here.)

### 9.3 A finding this ticket surfaces without fixing

`WriteThrottleCoverageTest`'s polarity forces every mutating handler under `/api/workspaces/**`
to be named. Doing that will put **`POST /api/workspaces`** into `EXEMPT` with a written reason —
and there is no good one. Workspace creation is unbudgeted, public signup is on in production, and
ADR-0015 already records that "creating workspaces is bounded by nothing" is half of the
invitation-abuse attack. This ticket does not fix it: a workspace-creation budget is a different
key (creation is not under a workspace), a different denomination and a different set of
legitimate-use questions (onboarding creates one per user on first login). **File it as a
follow-up.** What changes here is that it stops being invisible.

---

## 10. DC / Cloud implications and configuration

The mechanism is identical in both modes: same code, same table, same trigger, same refusal.
Only numbers differ, and they differ by property, never by branch — the rule
`SearchProperties` and `ReportProperties` already state.

### 10.1 New properties

| Property | Env var | Base default | `cloud` override | Notes |
|---|---|---|---|---|
| `app.write.requests-per-minute` | `WRITE_REQUESTS_PER_MINUTE` | `180` | — | `@Min(1) @Max(10_000)`. 3/s sustained; the SPA's inline-edit saves and board drags are the traffic this is sized against. No "unlimited": 0 fails startup. |
| `app.write.upload-bytes-per-minute` | `WRITE_UPLOAD_BYTES_PER_MINUTE` | `250MB` | — | `DataSize`. Must be ≥ `app.attachments.max-file-size`, or no legal file can ever be uploaded — startup check. |
| `app.storage.quota.enabled` | `STORAGE_QUOTA_ENABLED` | `true` | — | **Deliberately not under `app.rate-limit.enabled`** — see §10.2. |
| `app.storage.quota.workspace-bytes` | `STORAGE_QUOTA_WORKSPACE_BYTES` | `100GB` | `10GB` | `DataSize`. Must be ≥ `app.attachments.max-file-size` — startup check. |
| `app.storage.quota.warn-at-percent` | `STORAGE_QUOTA_WARN_PERCENT` | `80` | — | `@Min(1) @Max(99)`. Drives the SPA banner and the fill alert. |
| `app.storage.quota.reconcile-cron` | `STORAGE_QUOTA_RECONCILE_CRON` | `0 20 3 * * *` | — | Empty string disables the schedule; the drift gauge then ages and `StorageDriftGaugeStale` fires, which is the intended signal. |

**Why those two defaults.** `100GB` on DC: the operator owns the disk and usually the whole
install, signup is locked down by default (`app.onboarding.enabled=false`), and there are no
strangers — so the number should be a *safety net against runaway growth*, generous enough that
no real team meets it by accident, finite enough that a bug or an abusive account cannot fill the
volume. `10GB` on Cloud: signup is public, the backend is S3, every byte and every request is
billed, and the workspace is the unit a stranger gets for the price of one disposable mailbox.
Both are single env vars; neither is a product promise (there is no billing model behind them —
OQ-D1).

The `cloud` override is placed in `application-cloud.properties`, mirroring exactly how
`app.storage.type` is already profile-defaulted (`dc` → local, `cloud` → s3).

### 10.2 The quota has its own off switch, and that is deliberate

`app.rate-limit.enabled` is documented as "THE master switch for every limiter that HAS an off
switch". **The storage quota is not joined to it.** Folding them would mean an operator who wants
to remove a disk bound has to disable auth brute-force protection, and an operator debugging a
limiter has to remove the disk bound. Two different kinds of control, two switches. Both the
`application.properties` master-switch block and the `.env.prod.example` block must say so
explicitly, in the same place they already say the rank-rebalance cooldown is outside it — the
list of exceptions is where the next reader looks.

The two **budgets** (§6.1, §6.2) *are* under the master switch, like every other per-principal
budget, and are added to its enumeration.

### 10.3 Full wiring chain (`dc-cloud-guard` checks all of it)

`application.properties` (new `app.write.*` and `app.storage.quota.*` blocks, plus the
master-switch enumeration and its "not this" list) → `application-cloud.properties` (the 10 GB
override) → `docker-compose*.yml` (pass-through of the six env vars) → `.env.prod.example` (a
Storage-quota section and a Write-budget section, each stating the range, that an *empty* value
aborts the boot rather than restoring the default, and where the off switch is) →
`docs/self-hosting.md` (operator table rows, the "which limiters are node-local" prose — the
quota is **not**, it is cluster-wide like the mail ceilings — and the orphan-blob runbook) →
`docs/api-dc.md` + `docs/api-cloud.md` → `src/main/frontend/public/openapi.yaml` (not the copy
under `src/main/resources/static`, which the Vite build overwrites).

---

## 11. Metrics & alerts

Cardinality rule is absolute: **no meter carries a workspace id, user id or any tenant-identifying
value.** An operator who needs "which workspace" queries `workspace_storage_usage`, and
`docs/self-hosting.md` carries that query — the same division ADR-0015 uses for mail.

### 11.1 New `RateLimitKind` constants

`WRITE_REQUESTS("write_requests")` and `UPLOAD_BYTES("upload_bytes")`, emitted through the
existing `ProductMetrics.rateLimitHit`. Separate constants because they mean different things to
whoever reads the alert: a rate on the first is a client that lost its debounce or a script; a
rate on the second is somebody moving volume.

### 11.2 `hamstrack.storage.quota_refused`

Counter, no labels. Incremented at the 409. **This is the only signal that a tenant is stuck** —
the refusal is a clean 4xx and appears in no error rate.

### 11.3 `hamstrack.storage.bytes_used_total`, `hamstrack.storage.quota_fill_max`

Gauges. The first is `SUM(bytes_used)` over the usage table (one row per workspace — cheap at
scrape). The second is `MAX(bytes_used / quota)` as a 0..1 fraction, refreshed **on the reconcile
schedule, not at scrape**, and — like `hamstrack.mail.anonymous_recipient_max` — every replica
computes the same install-wide number, so an alert takes `max()`, never `sum()`.

### 11.4 `hamstrack.storage.drift_bytes` + `hamstrack.storage.drift_refreshed_at_age_seconds`

The largest absolute counter-vs-rows delta the last reconcile pass found, and the age of that
number. The age gauge is seeded at **process start** and has **no sentinel branch**, for the
reason `ProductMetrics` spells out at length: a gauge that publishes "never refreshed" as a value
below its own threshold is silent in exactly the state an operator most needs to hear from it.

### 11.5 Alert rules (`observability/grafana/provisioning/alerting/rules.yml`)

| uid / title | Condition | Severity | Why |
|---|---|---|---|
| `StorageQuotaRefusals` | `rate(hamstrack_storage_quota_refused_total[15m]) > 0` for 15m | warning | A tenant has been blocked for a quarter of an hour and nobody told the operator. |
| `StorageFillHigh` | `max(hamstrack_storage_quota_fill_max) > 0.9` for 1h | warning | Fires *before* the refusals, which is the whole point of having a threshold. |
| `StorageUsageCounterDrift` | `max(hamstrack_storage_drift_bytes) > 1048576` for 10m | warning | The quota is enforcing a number that is not true. |
| `StorageDriftGaugeStale` | `max(hamstrack_storage_drift_refreshed_at_age_seconds) > 90000` for 30m | warning | 25 h — one missed daily pass. A reconciler that stopped running freezes the drift gauge at a calm value; without this the two above go silent together. |

**Annotations are Go templates.** No `{{` may appear in any `summary` except the legitimate
`{{ $labels.x }}` / `{{ $values.x }}`; a literal brace destroys the *whole* annotation at render
and the alert fires with no text, which is the moment the guidance was for. Example commands go
in prose with the JSON field named rather than interpolated. `GrafanaProvisioningContractTest`
parses the YAML and does **not** render templates, so this is a review obligation, not a CI one.

---

## 12. Frontend impact

`DESIGN.md` first; tokens only (`var(--color-brand)`, `var(--color-border)`, …), never a hardcoded
hex.

### 12.1 Issue attachment panel (`pages/IssueDetail.tsx`)

- Fetches the summary (`apiGetWorkspaceStorage`, TanStack Query, workspace-keyed, shared with the
  settings page so it is one request).
- **At or above `warnAtPercent`**, and only then, a quiet line beside the upload control:
  *"2.4 GB of 10 GB remaining."* Below the threshold there is no chrome at all — a storage figure
  on every issue page is noise.
- When the chosen file exceeds `availableBytes`, the control is **disabled with an inline
  sentence naming both numbers**, never a silent no-op.
- A 409 `STORAGE_QUOTA_EXCEEDED` renders inline from the body's own figures (not from the cached
  summary, which may be stale), and invalidates the summary query.
- A 429 renders the `detail` and disables the control for `Retry-After` seconds.

### 12.2 Workspace Settings → **Storage** (new section)

New entry in `WorkspaceSettingsArea.SECTIONS`, gated `permission: 'workspace.edit'` — the same
mechanism People and Roles already use, so the tab is conditionally mounted and can only pop in,
never flash and vanish.

- A fill bar with the warn threshold marked, `used / quota`, attachment count, `asOf`.
- The per-project table (bytes descending), with `unattributedBytes` shown as its own row when
  non-zero rather than folded away.
- Over 100% renders as over 100%.

### 12.3 Rule C — the affordance while the capability is off

When `quotaEnabled` is false the Storage page **still renders**, showing usage, the attachment
count and the per-project breakdown, with one line: *"This instance does not enforce a storage
quota."* A page that only appears once a limit exists is unreachable for exactly the operator
deciding whether to set one. Same rule for the warn line in the issue panel: with no quota there
is no threshold, so no line — but the settings page is where the number lives either way.

### 12.4 `api.ts`

`apiGetWorkspaceStorage`, `apiGetWorkspaceStorageByProject`, plus 429 comments on the breakdown
caller and on the attachment upload caller, per the propagation checklist's item 9.

---

## 13. Acceptance criteria

**Quota**

1. A workspace whose `bytes_used + fileSize` exceeds the quota refuses the upload with **409**,
   `errorType: "STORAGE_QUOTA_EXCEEDED"`, and a body carrying `quotaBytes`, `usedBytes`,
   `availableBytes`, `fileBytes`.
2. The refusal's `detail` names both numbers and **prescribes no action addressed to the reader**
   (no "ask your administrator", no "delete files").
3. **An over-quota upload is refused before any byte reaches storage:** with a `FileStorage`
   test double injected, `store(...)` is never invoked, and no `issue_attachments` row survives
   the request.
4. A file that exactly fills the quota is accepted; the next one-byte file is refused.
5. Deleting an attachment decrements `bytes_used` by exactly its `size_bytes` in the same
   transaction as the row delete.
6. **Deleting a project with attachments returns `bytes_used` to its pre-upload value** — the
   cascade case, and the one that proves the trigger rather than the service.
7. A store failure after the row commits leaves `bytes_used` unchanged from before the upload.
8. Two concurrent uploads that each fit but jointly exceed: exactly one succeeds, one is refused
   409, and `bytes_used` equals the accepted file's size.
9. Lowering the quota below current usage refuses new uploads and deletes nothing; downloads and
   reads still succeed.
10. `app.storage.quota.enabled=false`: nothing is refused, usage is still counted and still
    reported, and `GET …/storage` returns `quotaEnabled:false` with `quotaBytes:null`.
11. Startup **fails** when `workspace-bytes < app.attachments.max-file-size`, and when
    `upload-bytes-per-minute < app.attachments.max-file-size`.

**Budgets**

12. The `(limit+1)`-th mutating request under `…/issues/**` in one minute from one principal is
    **429 + `Retry-After`**; the same principal's `GET` is unaffected; a second principal is
    unaffected.
13. Uploads whose sizes sum past `upload-bytes-per-minute` are refused 429 even though the
    request count is inside the request budget.
14. `app.rate-limit.enabled=false` disables both budgets and does **not** disable the quota.

**Tenancy & permissions**

15. `GET …/storage` for a non-member and for an unknown workspace both answer **404**, and the
    two responses are byte-identical.
16. `GET …/storage/projects` answers **403** for a member without `workspace.edit`, **404** for a
    non-member, and never lists a project of another workspace.
17. The reported total for workspace A is unaffected by any upload in workspace B.

**Seals & docs**

18. `ThrottleCoverageTest.theThrottledPathSetIsSealed()` passes with the new pattern sets, and its
    `PROPAGATION_CHECKLIST` names every artefact this ticket touched.
19. `WriteThrottleCoverageTest` passes, and its `EXEMPT` entries each carry a written reason.
20. `AttachmentDoorsTest` fails if a call site reaches `FileStorage.store` without a quota
    reservation in the same method.
21. `GrafanaProvisioningContractTest` passes; no alert annotation contains a literal `{{` other
    than `$labels` / `$values`.
22. `openapi.yaml` validates (swagger-cli); `docs/api-dc.md` and `docs/api-cloud.md` document the
    two endpoints, the 409 and the 429s.

**Reconciler**

23. A counter corrupted by hand is corrected on the next pass, and `hamstrack.storage.drift_bytes`
    reports the delta that was corrected.
24. With `reconcile-cron` empty, `…drift_refreshed_at_age_seconds` rises from process start (it
    does not sit at a value below the alert threshold).

---

## 14. Relationship to other tickets

### 14.1 HD-174 — different budget, stays open

HD-174 records that the **planning read** surface has no budget, earned by the *cap-blind grouped
stats query* in `BacklogService.section` — an unconditional aggregation that reads and groups a
whole section regardless of filters or caps (`BacklogController`'s javadoc, and
`PlanningThrottleParityTest`, which exists to defend the *sameness* of the section and aggregate
chains, not their emptiness).

**This ticket's budget covers `POST/PUT/PATCH/DELETE` and HD-174's surface is `GET`.** Nothing
here touches it: the patterns do not overlap (`…/backlog/**` vs `…/projects/*/issues/**`), the
denomination is different (writes cost fan-out and storage, that read costs an aggregation), and
the two are sized for different traffic. **HD-174 must not be closed by this ticket.** It does
gain a natural home, which is worth writing into it: `…/backlog/**` belongs on the **reports**
budget, being O(project content) like every report — a one-line addition to
`ReportRateLimitConfig` plus the same propagation checklist. When it lands,
`PlanningThrottleParityTest` keeps passing, exactly as its javadoc predicts.

### 14.2 HD-193 (erasure)

Shares the orphan-blob problem from the other end and already documents the key-listing procedure
for both backends (`account-deletion-proposal.md` §7). This ticket reuses that prose for the
reconciliation runbook and adds nothing to `FileStorage`. The new `issue_attachments.workspace_id`
column makes the erasure runbook's phase-1 key export a single-table query instead of a
three-level join — a small gift in that direction.

---

## 15. The highest-risk assumption

**That a row-level `AFTER DELETE` trigger on `issue_attachments` fires for rows removed by
`ON DELETE CASCADE` from `issues` / `projects` / `workspaces`.**

Everything in §5.1 rests on it. PostgreSQL's referential-integrity cascade performs ordinary row
deletions, which fire row-level triggers, so this should hold — but if it does not, the counter
ratchets upward on every issue and project delete, the quota becomes a one-way valve, and the
*only* thing that would catch it is the nightly reconciler, i.e. up to 24 h of a tenant being
wrongly refused. **It must be proven by AC-6 — a test that creates attachments, deletes the
project, and asserts the counter returned to its prior value — not by reading this paragraph.**

Second-order: the same assumption applied to a *multi-level* cascade
(`workspaces` → `projects` → `issues` → `issue_attachments`) where the counter's own row is being
cascaded away in the same statement. Order of RI cascades across levels is not something to rely
on; the counter row's disappearance makes the final value irrelevant, but a trigger that errors
mid-cascade would break workspace deletion. The trigger's upsert must therefore tolerate a
missing/deleted usage row without raising (`ON CONFLICT DO UPDATE` on an insert, never a bare
`UPDATE` that assumes a row).

---

## 16. Open questions

### Blocks the build

- **B1 — Does the write budget cover `PATCH`/`DELETE`, or only creation?**
  *Recommendation: all mutating methods.* The ticket names creation, but a half-budgeted surface
  is the shape `PlanningThrottleParityTest` calls worse than either whole answer, and an issue
  update is not cheaper than an issue create. Cost of being wrong: a slightly tighter bound on
  inline editing, fixed by one number.
- **B2 — Is the per-principal upload-byte budget in scope, or is the request budget enough?**
  *Recommendation: in scope.* A request budget does not bound bytes, and the quota does not see
  churn (upload → delete → upload bills every PUT and every stored byte and never moves the
  total). It is one subclass over a `long`-widened base — the cheapest piece of this ticket.
- **B3 — Does `GET …/storage` need a budget?**
  *Recommendation: no*, with the reason written into `ThrottleCoverageTest`'s exemption set
  (§9.2). Revisit if the SPA ever polls it rather than fetching it per view.
- **B4 — `FileStorage.list(prefix)` now, or a runbook?**
  *Recommendation: runbook.* An interface method invites an online caller that pages a bucket on
  a request thread. When orphan sweeping becomes a scheduled job rather than an incident
  response, it becomes a fork worth its own ADR.

### Blocks the deploy

- **D1 — Is 10 GB the right Cloud default?** It is a guess: there is no billing model, no tier
  and no measured distribution behind it. *Recommendation: ship 10 GB and treat it as
  provisional* — it is one env var, `STORAGE_QUOTA_WORKSPACE_BYTES`, and the fill gauge will say
  within a week whether it is wrong.
- **D2 — Existing production workspaces above the chosen default start refusing uploads the
  moment the deploy completes, with no warning to anybody.** *Recommendation:* before deploying,
  run the reconciler's read-only aggregate against production, set the initial
  `STORAGE_QUOTA_WORKSPACE_BYTES` above the largest existing workspace, deploy, confirm the fill
  gauge, and lower it deliberately in a second change. A quota introduced silently at a value
  somebody is already past is indistinguishable from an outage.
- **D3 — Does any existing bulk path set `hamstrack.skip_updated_at` while touching
  `issue_attachments`?** Today the GUC belongs to the `issues` rank rebalance only.
  *Recommendation: verify at build time and state the answer in the migration header*, because a
  counter trigger that silently skipped itself would be the drift generator §11.4 exists to
  catch.
- **D4 — Is one nightly reconcile pass frequent enough on Cloud?** *Recommendation: yes to start.*
  Drift is expected to be zero (the trigger is the mechanism, the reconciler is the witness); if
  `StorageUsageCounterDrift` ever fires, the frequency question answers itself and it is a cron
  string.

---

## 17. Architectural decisions (ADR)

One decision here is a hard-to-reverse fork a future contributor will ask "why?" about:

**ADR-0026 — Where a workspace's occupied bytes live: a counter row in its own table, maintained
by a database trigger.**

- **Chosen:** a dedicated `workspace_storage_usage` table, written only by an
  `AFTER INSERT/DELETE/UPDATE` row trigger on `issue_attachments` and by a reconciler; the quota
  accounts for **rows**, and objects with no row are reconciled out of band.
- **Rejected:** `SUM` over the attachment rows on every upload (correct, unbounded, on the hot
  path, and today a three-level join); a counter column on `workspaces` (cheap, and directly on
  the `projects.issue_seq` clobber scar, plus it would make the reservation lock a lock on
  `workspaces`, which blocks every FK child insert in the tenant); a service-level decrement
  without a trigger (invisible to `ON DELETE CASCADE`, so every issue and project delete would
  ratchet the counter upward).
- **Trade-off:** one more table and the product's first business trigger, plus a row lock per
  upload per workspace; bought with a number that is true through code paths that do not exist
  yet, a lockable row that is not `workspaces`, and a cheap exact reconciliation.

Draft written to `docs/adr/0026-storage-usage-counter-by-trigger.md`, `Status: Proposed`, with a
row added to `docs/adr/README.md`. The orchestrator flips it to `Accepted` once it ships.

Everything else here — the 409, the property names, the interceptor's method condition, the
budget numbers — is routine feature mechanics and is not ADR-worthy.
