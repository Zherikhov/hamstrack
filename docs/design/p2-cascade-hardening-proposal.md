# P2 Cluster 1 — Cascade / Correctness Hardening (Spec)

**Tracking:** parent P2-2 + P2-3 from `docs/design/handover-assessment.md §4`. Child tickets:
**HD-76** (outbound timeouts), **HD-77** (blob I/O outside tx), **HD-78** (bounded async mail +
critical-mail durability), **HD-79** (board issue-list cap). Source findings: §3.7 **R-3 / R-4 / R-6**
(and the cross-references H2/H4/H6). **Date:** 2026-08-15. **Baseline:** backend suite GREEN.

## 0. Cluster problem & goal

Under a slow or hung outbound dependency, the app has no containment: S3 `putObject`/`getObject` and
SMTP calls have **no timeouts**, S3 `putObject` runs **inside the DB transaction** (so a stalled S3
pins Hikari connections until the whole pool — default 10 — is exhausted and every request 503s), the
`@Async` mail path uses an **unbounded** default executor (thread-per-task under a mail-server stall),
and **critical mail failures are silently lost** (a failed verification/reset email leaves a user
unable to complete signup with no record). Separately, the board issue list (`GET …/issues` with no
`size`) returns **every** issue in the project — an OOM/latency risk on large projects.

**Goal:** make one-node behaviour resilient to outbound-dependency failure and to large projects —
timeouts everywhere, blob I/O off the DB connection, a bounded mail executor with durability for
account-critical mail, and a bounded board query. HD-76/77/78 are build-ready. HD-79 changes a
client-facing contract and carries **one product-owner UX decision** (isolated below).

**Non-goals (explicitly out of scope for this cluster):** multi-node backplane (SSE/rate-limit shared
store — P2-1); pre-signed download URLs (R-11); search N+1/trigram (P2-4); domain-event bus (P2-5);
generic non-auth rate limiting (R-9). Do not fork any behaviour by mode — everything here is
profile/property-gated per the single-codebase rule.

---

## HD-76 — Outbound timeouts (S3 + SMTP) — BUILD-READY

### Scope
Config-only, both modes. Add explicit connect/read/overall timeouts to the S3 client and to the SMTP
transport so a hung remote endpoint fails fast instead of blocking a worker (and, combined with
HD-77/78, never pins the DB pool or an unbounded thread). No behavioural change on the happy path.

In scope: `S3FileStorage` client build; `spring.mail.properties.mail.smtp.*` timeouts; new env-var
knobs with per-profile-safe defaults. Out of scope: ret/queue logic (HD-78), moving I/O off the tx
(HD-77).

### Actors & permissions
None — infrastructure/config. No API or tenancy surface.

### Exact change

**A. SMTP timeouts (`application.properties`)** — add three JavaMail properties, env-overridable.
The current file sets `mail.smtp.auth`/`starttls` only; append:

```
spring.mail.properties.mail.smtp.connectiontimeout=${MAIL_SMTP_CONNECT_TIMEOUT_MS:5000}
spring.mail.properties.mail.smtp.timeout=${MAIL_SMTP_READ_TIMEOUT_MS:10000}
spring.mail.properties.mail.smtp.writetimeout=${MAIL_SMTP_WRITE_TIMEOUT_MS:10000}
```

Values in **milliseconds** (JavaMail contract). Defaults: connect **5s**, read **10s**, write **10s**
— generous enough for MailHog/real SMTP under normal load, short enough that a black-holed mail host
fails a worker in ≤10s rather than hanging indefinitely. Identical for both profiles (no per-profile
override needed).

**B. S3 timeouts (`S3FileStorage` + `StorageProperties`)** — the AWS SDK v2 sync client has **no
default request timeout**; a stalled connection blocks the calling thread until the socket's OS-level
timeout (effectively forever for a slow-drip). Add both HTTP-client socket timeouts and SDK-level
overall/attempt timeouts.

Add a nested `Timeouts` record to `StorageProperties.S3` (or a sibling record under `app.storage.s3`):

```java
public record S3(
        String bucket, String region, String endpoint, boolean pathStyleAccess,
        String accessKey, String secretKey,
        Timeouts timeouts) {
    public record Timeouts(
            long connectMs,        // TCP connect
            long readMs,           // socket read (per read)
            long apiCallMs,        // overall call incl. retries
            long apiCallAttemptMs  // single attempt
    ) {}
}
```

In `S3FileStorage`, wire them onto the builder (SDK v2, `apache-client` or `url-connection-client` —
use whichever is already on the classpath; the URL-connection client covers connect/read, Apache adds
connection-pool timeouts). Overall-call + per-attempt timeouts go via `ClientOverrideConfiguration`:

```java
builder.httpClientBuilder(
    software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder()
        .connectionTimeout(Duration.ofMillis(t.connectMs()))
        .socketTimeout(Duration.ofMillis(t.readMs())));
builder.overrideConfiguration(
    software.amazon.awssdk.core.client.config.ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofMillis(t.apiCallMs()))
        .apiCallAttemptTimeout(Duration.ofMillis(t.apiCallAttemptMs()))
        .build());
```

> **Classpath note for the builder:** confirm which SDK HTTP client artifact is present
> (`software.amazon.awssdk:url-connection-client` vs `:apache-client`). If neither is an explicit
> dependency, the SDK pulls Apache by default — set the socket/connect timeouts on
> `ApacheHttpClient.builder()` instead. This is a build-time lookup, not a spec ambiguity: use the
> one already resolved. Do **not** add a new HTTP-client dependency solely for this.

**`application.properties`** additions (env-overridable, `app.storage.s3.timeouts.*`):

```
app.storage.s3.timeouts.connect-ms=${STORAGE_S3_CONNECT_TIMEOUT_MS:3000}
app.storage.s3.timeouts.read-ms=${STORAGE_S3_READ_TIMEOUT_MS:20000}
app.storage.s3.timeouts.api-call-ms=${STORAGE_S3_API_CALL_TIMEOUT_MS:30000}
app.storage.s3.timeouts.api-call-attempt-ms=${STORAGE_S3_API_CALL_ATTEMPT_TIMEOUT_MS:10000}
```

Defaults: connect **3s**, read **20s** (large attachments stream through a single socket read window;
25 MB cap ÷ a slow-but-alive link must not trip it — 20s per read is the ceiling, not per transfer),
overall **30s**, per-attempt **10s**. These bound a hung S3 at ≤30s total. `local` storage ignores
them entirely (the beans are `@ConditionalOnProperty`-gated; `LocalFileStorage` is unaffected).

### Edge cases & failure modes
- A timeout surfaces as an `SdkClientException`/`ApiCallTimeoutException` from `store`/`open`/`delete`.
  On **upload** this propagates and — with HD-77 in place — rolls back / triggers compensation; today
  (pre-HD-77) it rolls back the tx (no row), which is acceptable interim behaviour.
- On **download** a read timeout mid-stream aborts the response after headers are sent — unavoidable
  with streaming; acceptable (client sees a truncated download / connection reset).
- SMTP write-timeout on a large HTML body: 10s is ample for our ~2 KB emails.
- MailHog (local/CI) is fast; defaults never trip in the test suite.

### DC/Cloud implications
- Both timeout sets ship in the **base** `application.properties` (not profile files) with env
  overrides — identical instrumentation both modes, per the single-codebase rule.
- S3 timeouts only take effect when `app.storage.type=s3` (Cloud default; DC-with-MinIO opt-in). DC
  local FS is unaffected.
- **Env-var wiring checklist (dc-cloud-guard):** add all 7 new vars to `.env.prod.example`,
  `compose*.yml` (Cloud), `docs/self-hosting.md`, and the README env table with the defaults above.

### Files to change
- `src/main/resources/application.properties` (SMTP + S3 timeout properties)
- `src/main/java/com/hamstrack/common/config/StorageProperties.java` (nested `Timeouts` record)
- `src/main/java/com/hamstrack/common/storage/S3FileStorage.java` (wire timeouts onto builder)
- `.env.prod.example`, `docs/self-hosting.md`, README env table, compose (dc-cloud-guard)
- `pom.xml` **only if** an explicit SDK HTTP-client artifact must be pinned (verify first)

### Acceptance criteria
- [ ] SMTP `connectiontimeout`/`timeout`/`writetimeout` set from env with the documented ms defaults.
- [ ] S3 client built with `apiCallTimeout` + `apiCallAttemptTimeout` **and** HTTP connect/read
      (socket) timeouts, all from `app.storage.s3.timeouts.*`.
- [ ] With a black-holed S3 endpoint, an upload fails within ≤ `api-call-ms` (default 30s), not
      indefinitely (manual/integration check against a non-routable endpoint).
- [ ] `local` storage path unchanged; `LocalFileStorage` untouched; app still boots on `dc` profile.
- [ ] All 7 env vars appear in `.env.prod.example` + README + `docs/self-hosting.md` with defaults.
- [ ] Backend suite still GREEN.

---

## HD-77 — Move attachment blob I/O outside the DB transaction — BUILD-READY

### Scope
`AttachmentService.upload` currently calls `fileStorage.store(...)` **inside** `@Transactional`
(`AttachmentService.java:85–89`), so a slow/hung S3 `putObject` holds the Hikari connection for the
whole upload → pool exhaustion → app-wide 503. Move the blob write off the DB transaction while
preserving (a) tenant scoping, (b) server-generated keys, (c) the current all-or-nothing guarantee
(no dangling row without a blob, no blob without a discoverable row) as closely as possible.

Also in scope: document/annotate the **download** path (streams under `@Transactional(readOnly=true)`,
so a slow S3 read holds a read connection for the response duration). Delete path is already
after-commit and fine.

### Actors & permissions
Uploader must be a workspace member + resolve the issue (unchanged). No permission change.

### Decision — chosen strategy

**Persist-and-commit the row first, then upload after commit; compensate by deleting the row if the
upload fails.** Rejected alternative (reserve-row / upload / flip-a-`committed`-flag) below.

**Why:** the reserve/flip approach needs a new nullable `committed`/`upload_state` column, a filter on
every read path (list/download/board never join attachments, but any future one would), and a sweeper
for stranded reservations — significant surface for a single-node correctness fix. The commit-first +
compensating-delete approach needs **no schema change**, keeps the existing "row is the source of
truth" model, and the only residual failure (row-delete after a failed upload also fails) leaves an
attachment row whose blob is missing — which the **download path already tolerates** (streaming a
missing key throws, surfaces as an error to the one caller who opens it; it never corrupts the issue
or leaks cross-tenant). That residual is strictly better than today's alternative failure (a committed
row with the connection pinned during the write).

### Exact change (`AttachmentService.upload`)

Restructure so the transaction only does DB work; the blob write runs **after commit**, synchronously
on the request thread (so the client still blocks until the file is durably stored and gets a
meaningful error), with a compensating row delete on failure:

1. **Inside a short `@Transactional` method** (`reserveAttachmentRow`): resolve issue (tenant scoping
   unchanged — `resolveIssue`), `requireNotArchived`, validate, build the `IssueAttachment` with the
   **server-generated** key `ws/{wsId}/issues/{issueId}/{uuid}` (unchanged — no user input), `save`,
   and return a small holder (attachment id + key + content-type + size + a materialized
   `AttachmentResponse`, assembled inside the tx so no lazy access happens after commit). **Commit.**
2. **After the tx returns**, on the request thread, call `fileStorage.store(key, in, size, type)`.
3. **On store failure** (`IOException` or `SdkException`/timeout): call a second short
   `@Transactional` `compensateFailedUpload(attachmentId)` that deletes the row **scoped to the same
   issue** (re-resolve issue by id + verify `attachment.issue.id` — reuse `findAttachmentOnIssue`
   semantics so compensation can't touch another tenant's row), then throw
   `ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to store file")` (unchanged status).
   If the compensating delete itself fails, log a WARN with the orphaned attachment id + key (a
   cleanup concern, mirroring the existing `deleteFromStorageAfterCommit` philosophy) and still throw.
4. **On store success:** emit the existing `ATTACHMENT_ADDED` SSE broadcast and record the metric
   (both currently in-tx — moving them after the successful store is fine and slightly more correct,
   since we now only signal "added" once the blob truly exists), and return the pre-assembled
   `AttachmentResponse`.

The public `upload(...)` method itself becomes **non-`@Transactional`** (an orchestrator) and calls
the two transactional helpers; because a Spring self-invocation would bypass the proxy, the two
`@Transactional` steps must be **either separate beans or the same-bean methods invoked via an
injected self-reference / `TransactionTemplate`.** Recommended: use a `TransactionTemplate`
(cleanest, no self-injection). `store` is called between the two template executions, off any tx.

**Tenant scoping / key invariants preserved:**
- Issue resolution (workspace membership → project-in-workspace → issue) runs unchanged inside step 1.
- The storage key is still server-generated from the resolved `workspaceId`/`issue.id` — no user input.
- Compensation re-verifies the attachment belongs to the resolved issue before delete (no global
  `deleteById`), so a race cannot delete another issue's/tenant's row.

### Download path note
`download(...)` opens the stream inside `@Transactional(readOnly=true)` and hands the open
`InputStream` to the controller, which writes it to the response **after** the service method returns
(and thus after the tx has been chosen to commit) — but the connection is released only when the tx
scope ends. In practice the read connection is held while the controller streams. **Change:** the
service should resolve the attachment metadata in a short read tx, then open the stream **outside** the
tx (open `FileStorage.open(key)` after the tx boundary, or annotate the method so the connection is
released before streaming). Recommended minimal change: split `download` into (a) a `@Transactional`
metadata lookup returning `(filename, contentType, size, storageKey)`, then (b) `fileStorage.open(key)`
called **outside** the tx in the service method, returning the `AttachmentDownload`. This releases the
DB connection before the (potentially slow, HD-76-bounded) S3 read streams to the client.

### Edge cases & failure modes
- **Empty/invalid file / archived project / too-large / bad type:** rejected in step 1 before any row
  or blob is created (unchanged behaviour, all inside the reserve tx).
- **Store timeout (HD-76):** triggers compensation → row deleted → 500 to client, no orphan row, at
  most an orphan-free state. Pool never pinned (store is off-tx).
- **Compensation delete fails:** orphan row with missing blob — download of *that* attachment errors;
  logged WARN for out-of-band cleanup. No cross-tenant/data-integrity impact.
- **Concurrency:** two uploads to the same issue get distinct UUID keys — no collision. Optimistic
  locking on the issue is not involved (attachment insert doesn't bump issue `@Version`).
- **Client disconnect mid-upload:** the multipart is already fully read by the servlet before the
  service runs; store proceeds; if the client is gone the SSE/response is dropped — harmless.
- **`removeStoredFilesForIssue`** (issue delete) is unchanged — already after-commit best-effort.

### Data model impact
**None.** No schema change (the deciding advantage of the chosen strategy). No migration.

### API surface
Unchanged — same endpoint, same request/response DTO, same status codes
(`201`/`400`/`409`/`413`/`415`/`500`). No `openapi.yaml`/api-docs change.

### DC/Cloud implications
Applies to both storage backends via the injected `FileStorage` interface (never a concrete class).
On `local` FS the store is fast so the change is a no-op in practice, but the structure is identical —
no mode fork. No new env var.

### Files to change
- `src/main/java/com/hamstrack/issue/service/AttachmentService.java` (restructure `upload` +
  `download`; add `TransactionTemplate` or split beans; compensation method)
- (test) new `AttachmentServiceTest` covers the compensation path — feeds P2-8 (Q-1), see AC.

### Acceptance criteria
- [ ] `upload` performs **no** `fileStorage.store` call inside a `@Transactional` scope (verified by
      review + a test that stubs a slow/failing `FileStorage` and asserts the DB connection isn't held
      during the store).
- [ ] On a store failure, **no** attachment row remains for that upload (compensating delete), and the
      client receives `500 "Failed to store file"`.
- [ ] On success, exactly one row + one blob exist; `ATTACHMENT_ADDED` SSE fires once, after the blob
      is stored.
- [ ] Storage key remains server-generated `ws/{wsId}/issues/{issueId}/{uuid}`; compensation deletes
      only the row belonging to the resolved issue (tenant-scoped) — regression test for
      cross-issue/cross-tenant safety.
- [ ] `download` releases the DB connection before streaming the blob (metadata in tx, `open` outside).
- [ ] Existing upload/list/download/delete behaviour and status codes unchanged; suite GREEN.
- [ ] `tenancy-reviewer` sign-off (touches a workspace-scoped service).

---

## HD-78 — Bounded async mail executor + durability for critical mail — BUILD-READY

### Scope
Two problems: (1) `@EnableAsync` with no configured executor uses Spring's default
`SimpleAsyncTaskExecutor` — **unbounded, thread-per-task** — so a stalled SMTP host (now bounded by
HD-76 to ≤10s per send) spawns a thread per queued email under burst; (2) a failed
verification/password-reset email is **silently lost** (`MailService.send*` rethrows onto the async
executor, whose uncaught exception only logs — the user never gets the email and there's no record or
retry). Fix both: a **bounded** executor, and **retry + durable failure record** for CRITICAL mail
(verification + reset; invite is best-effort and excluded).

### Actors & permissions
Infrastructure. Failure records are operator-visible only (no new user-facing API in this ticket).

### Decision — chosen approach

**(a) Bounded `ThreadPoolTaskExecutor`** dedicated to mail (not virtual threads), **plus (b)
in-application retry with a persisted dead-letter row for CRITICAL mail.**

- **Executor over virtual threads:** virtual threads (`spring.threads.virtual.enabled=true`) would fix
  unboundedness but remove backpressure (unbounded task admission) and are a broader app-wide switch
  than this ticket should flip. A dedicated bounded pool gives an explicit queue + rejection policy
  and is trivially profile-tunable. (A future ticket may adopt virtual threads globally; out of scope.)
- **Retry + dead-letter over a full outbox/queue:** account-critical mail must not vanish. A full
  transactional-outbox is P2-5-adjacent (event bus) and over-scoped here. Minimal durable approach:
  synchronous in-executor retry (bounded attempts with short backoff), and on final failure **persist
  a `failed_email` row** (dead-letter) so an operator/alert can see and re-drive it. Invite mail keeps
  the current fire-and-forget (log-only) behaviour.

### Exact change

**A. Dedicated bounded executor.** Add a config class (e.g. `common.async.AsyncConfig`) defining a
`ThreadPoolTaskExecutor` bean named **`mailExecutor`**, and annotate the mail methods
`@Async("mailExecutor")` so mail can't starve any other future `@Async` work. Properties
(env-overridable, base `application.properties`):

```
app.mail.async.core-pool-size=${MAIL_ASYNC_CORE_POOL:2}
app.mail.async.max-pool-size=${MAIL_ASYNC_MAX_POOL:5}
app.mail.async.queue-capacity=${MAIL_ASYNC_QUEUE_CAPACITY:100}
```

Rejection policy: `CallerRunsPolicy` (backpressure — if the queue is full, the request thread sends
inline rather than dropping mail). `setThreadNamePrefix("mail-")`. `setWaitForTasksToCompleteOnShutdown(true)`
+ a bounded `awaitTerminationSeconds` (e.g. 15) so in-flight mail flushes on graceful shutdown. Bind
these via a new `MailProperties`-style record (`app.mail.async.*`) or `AppProperties` nested record.

> Naming caution: `spring.mail.*` already binds Boot's `MailProperties`; put ours under
> `app.mail.async.*` with a distinct `@ConfigurationProperties(prefix="app.mail.async")` record to
> avoid clashing with Boot's mail auto-config.

**B. Retry + dead-letter for CRITICAL mail.** Introduce a criticality notion in `MailService`:
- `EmailType.VERIFICATION` and `EmailType.PASSWORD_RESET` = **CRITICAL**; `INVITE` = best-effort.
- For CRITICAL sends, wrap the actual SMTP send in a bounded retry (e.g. **3 attempts**, short fixed
  backoff ~2s; use Spring Retry if already available, else a small manual loop — do **not** add a new
  dependency just for this if a loop suffices). Config:

```
app.mail.critical.max-attempts=${MAIL_CRITICAL_MAX_ATTEMPTS:3}
app.mail.critical.retry-backoff-ms=${MAIL_CRITICAL_RETRY_BACKOFF_MS:2000}
```

- On **final** failure (all attempts exhausted), persist a **`failed_email`** dead-letter row and log
  ERROR (so the existing `EmailFailures` alert fires). The row records enough to re-drive or diagnose
  without storing secrets in plaintext beyond what the token already is.

**Dead-letter table (`failed_email`)** — new Flyway migration `V7__failed_email.sql` (next in the
un-edited chain; confirm number at build time). Respect Flyway rules (`VARCHAR` not ENUM/`CHAR`, UUID
v7 app-generated, `@CreatedDate`):

| column | type | notes |
|---|---|---|
| `id` | `UUID` PK | app-generated UUID v7 (`@UuidGenerator(style=TIME)`) |
| `email_type` | `VARCHAR(40)` | maps to `EmailType` enum name (validated app-side) |
| `recipient` | `VARCHAR(320)` | the `to` address |
| `subject` | `VARCHAR(255)` | for operator context |
| `last_error` | `VARCHAR(1000)` | truncated exception message |
| `attempts` | `INT` | attempts made |
| `created_at` | `TIMESTAMPTZ` DEFAULT NOW() | `@CreatedDate` + trigger safety-net (project pattern) |

Entity `FailedEmail` extends `CreatedOnlyEntity` (id + createdAt). Repository `FailedEmailRepository`.
**Do not store the raw verification/reset token in the dead-letter row** — a leaked dead-letter table
would then be a token store. Record type/recipient/error only; re-driving a critical mail means the
user re-requests (resend verification / re-request reset), which is the existing enumeration-safe
flow. (If a future ticket wants true auto-redrive, that needs the outbox/event-bus — out of scope.)

**Scoping note:** `failed_email` is **not** workspace-scoped — it's an install-level operational log
(mail is sent at account boundaries, e.g. verification before any workspace exists). It carries no
tenant data beyond an email address and is only reachable by operators/DB, never via a tenant API.
State this explicitly so a future admin surface scopes it correctly (system-admin only).

### Edge cases & failure modes
- **Queue full under burst:** `CallerRunsPolicy` sends on the request thread (bounded by HD-76
  timeouts) rather than dropping — the triggering request slows but mail isn't lost.
- **All retries fail (SMTP down):** `failed_email` row + ERROR log + `EmailFailures` metric; user can
  re-request via the existing resend flow.
- **SMTP recovers between attempts:** succeeds on a later attempt, no dead-letter row, `SUCCESS` metric.
- **Graceful shutdown mid-send:** `setWaitForTasksToCompleteOnShutdown(true)` flushes the queue within
  `awaitTerminationSeconds`; anything still failing dead-letters.
- **Invite mail failure:** unchanged (log + FAILURE metric, no retry, no dead-letter) — best-effort by
  design.
- **Test suite:** MailHog is up in local/CI; the retry loop never triggers. Add a unit test that stubs
  `JavaMailSender` to throw and asserts a `failed_email` row after exhausting attempts (mock the sleep
  or set backoff to 0 in the test profile to keep it fast).

### Data model impact
One new table `failed_email` (migration `V7` — confirm next number). Requires `migration-reviewer`.

### API surface
No new endpoint in this ticket (dead-letter is operator/DB-visible). If a future admin console lists
it, that's a separate P3 item; note it as system-admin-scoped when built.

### DC/Cloud implications
- Executor + retry properties in base `application.properties`, env-overridable, identical both modes
  (no fork). Defaults are sensible for a small single node.
- `failed_email` exists in both modes (same schema). No mode-specific behaviour.
- **Env-var wiring (dc-cloud-guard):** add the 5 new vars (`MAIL_ASYNC_*`, `MAIL_CRITICAL_*`) to
  `.env.prod.example` + README + `docs/self-hosting.md`.

### Files to change
- `src/main/java/com/hamstrack/HamstrackApplication.java` — no change needed (`@EnableAsync` stays);
  the named executor is picked up by `@Async("mailExecutor")`.
- new `src/main/java/com/hamstrack/common/async/AsyncConfig.java` (or `common/mail/`) — `mailExecutor`
  bean + `app.mail.async` properties record.
- `src/main/java/com/hamstrack/common/mail/MailService.java` — `@Async("mailExecutor")`; CRITICAL
  retry + dead-letter on final failure.
- new `common/mail/FailedEmail.java` (entity), `FailedEmailRepository.java`, `MailProperties`-style
  config record (`app.mail.critical.*`).
- new `src/main/resources/db/migration/V7__failed_email.sql` (confirm number).
- `application.properties` (executor + critical retry props).
- `.env.prod.example`, README, `docs/self-hosting.md` (dc-cloud-guard).

### Acceptance criteria
- [ ] Mail runs on a **bounded** `ThreadPoolTaskExecutor` (`mailExecutor`), core/max/queue from
      `app.mail.async.*`; `CallerRunsPolicy`; thread prefix `mail-`; graceful-shutdown drain.
- [ ] Verification + reset mail retries up to `max-attempts` with backoff on SMTP failure.
- [ ] On exhausted CRITICAL retries, a `failed_email` row is written (no raw token stored) + ERROR log
      + FAILURE metric; a stubbed-failing-sender test asserts the row.
- [ ] Invite mail behaviour unchanged (no retry/dead-letter).
- [ ] `failed_email` migration follows Flyway rules (`VARCHAR`, UUID v7, `@CreatedDate`); entity⇄schema
      parity passes `validate`; `migration-reviewer` sign-off.
- [ ] 5 env vars documented; both profiles boot; suite GREEN.

---

## HD-79 — Board issue-list pagination / cap — **BLOCKED-ON-DECISION**

### Scope & why it's blocked
`GET /api/workspaces/{ws}/projects/{p}/issues` **without `size`** returns the **entire** project's
issues (`IssueService.list` → `IssueRepository.findByProjectFiltered`, no `Pageable`,
`IssueController.list:79–82`). This is the documented "board needs every card" exception. On a large
project it is an OOM/latency risk (R-4). Fixing it **changes a client-facing contract** the board
relies on (the board renders columns by client-side `issues.filter(status)`), so the safe cap size and
the truncation UX are a **product-owner decision** — this item ships only after that decision.

Everything below the "Backend contract change" heading is **conditional on the chosen option**;
HD-76/77/78 do not depend on it.

### Actors & permissions
Workspace member resolving the project (unchanged). No permission change.

### The decision (for the product owner) — three options

**Option A — Per-status-column pagination + "Load more" per column.**
Each board column requests its own page (`?statusId=…&size=N`), with a per-column "Load more" when a
column has more. Columns stay independently bounded.
- **Pros:** never loads more than N×(#columns) cards; matches how users actually scan a board (column
  by column); backend already supports `statusId` + paging (`findByProjectFilteredPaged`).
- **Cons:** biggest frontend rewrite (board currently does one query + client-side column split;
  Option A means one query *per column* + per-column state, "Load more", and drag-and-drop across
  partially-loaded columns needs care — an issue can be dragged into a not-fully-loaded column). Cross-
  column counts (the little per-column totals) need a separate count call or a `totalElements` per
  column.

**Option B — Hard global cap (e.g. 500–1000) with a truncation banner. (RECOMMENDED)**
Keep the single board query but cap it server-side; when the project exceeds the cap, return the first
N (by the existing `position, createdAt` order) and signal truncation; the board shows a banner:
"Showing first N of M — refine with filters or use the Backlog/Search."
- **Pros:** smallest change — one bounded query, board logic (client-side column split) unchanged;
  removes the OOM risk immediately; the banner nudges heavy users to filters/search/backlog (which are
  already paginated). Drag-and-drop unaffected (all loaded cards are real).
- **Cons:** a truncated board is an incomplete view — but a board with >500 open cards is already
  unusable as a kanban, so this is an acceptable, honest degradation. Doesn't give infinite reach on
  huge projects (by design — that's what Backlog/Search are for).

**Option C — Virtualized infinite scroll per column.**
Per-column windowed rendering (react-virtual/tanstack-virtual) with incremental fetch on scroll.
- **Pros:** best UX for genuinely large columns; no visible cap.
- **Cons:** by far the most work (virtualization + infinite-fetch + drag-and-drop through a virtualized
  list is notoriously fiddly), and still needs a backend cap as a backstop. Overkill for the actual
  need (containment), and drag-reorder inside a virtualized+paged column is a real hazard.

**Recommendation: Option B (hard global cap + truncation banner).** It removes the OOM/latency risk
with the least change and no drag-and-drop risk, keeps DC/Cloud parity trivially, and routes power
users to the already-paginated Backlog and Search for exhaustive views. Recommended cap default:
**`BOARD_MAX_ISSUES=500`**, env-overridable, clamped hard server-side (a client can't raise it). Revisit
to Option A only if real projects routinely exceed 500 open issues on the board.

> **Highest-risk assumption in this cluster:** that a global cap on the board is acceptable product
> behaviour (Option B). If the product owner considers a silently-capped board unacceptable, this
> becomes Option A (per-column) and the frontend cost rises materially. This is the single open
> question — everything else in the cluster is decided.

### Backend contract change (applies to whichever option; written for Option B)
- Add a **hard server cap** to the no-`size` board path. `IssueController.list`: when `size == null`,
  do **not** return an unbounded list — call a capped variant.
- New service method `listCapped(...)` (or extend `list`) using a `Pageable` of size `cap+1` (fetch
  one extra to detect truncation) via a paged/limited repository query, then:
  - Return a small wrapper `BoardIssuesResponse { List<IssueResponse> issues; boolean truncated; long totalAvailable; int cap; }`
    — **this changes the response shape** of the no-`size` call from a bare array to an object.
  - `truncated = (found > cap)`; trim to `cap`; `totalAvailable` from a cheap `count` (reuse
    `countByProject`-style scoped count with the same filters) so the banner can say "N of M".
- Add `app.board.max-issues=${BOARD_MAX_ISSUES:500}` (new `BoardProperties` or `AppProperties` nested).
- **Contract-change management:** because the bare-array response is consumed by `apiListIssues`
  today, either (a) bump to the wrapper shape and update the client in the same change (preferred —
  single atomic change, no versioning), or (b) keep the array and put `truncated`/`total` in response
  **headers** (`X-Total-Count`, `X-Truncated`) to avoid changing the body shape. **Recommend (a)** —
  the client is in-repo and updated together; headers are easy to miss. `openapi.yaml` + `api-cloud.md`
  + `api-dc.md` must document the new shape (api-docs-sync).

For **Option A** instead: the board would call `apiListIssuesPaged` per column with `statusId`, and the
"full list" endpoint variant could be retired for the board; the contract change is then "board stops
using the array endpoint" rather than "array becomes wrapper."

### Frontend impact (Option B)
- `src/main/frontend/src/api.ts` — `apiListIssues` returns `BoardIssuesResponse` (or reads
  `{ issues, truncated, totalAvailable }`); update the `Issue[]` typing to the wrapper.
- `src/main/frontend/src/pages/BoardPage.tsx` — read `data.issues` instead of `data` (array);
  render a **truncation banner** in the filter bar / above the columns when `truncated` is true
  ("Showing first {cap} of {totalAvailable} — refine with filters, or open the Backlog / Search for the
  full list"). Keep the existing client-side column split. Follow `DESIGN.md` (Beacon tokens; a
  muted/attention banner style, no hardcoded hex).
- The per-column count badges already count only loaded cards — with a cap they show the capped counts;
  the banner explains the discrepancy. Acceptable for Option B.
- No change to Backlog (already paginated) or Search.

For **Option A**: `BoardPage` restructures to per-column queries + "Load more" + per-column loading
state; larger change (noted as the cost of choosing A).

### Edge cases & failure modes
- Project with exactly `cap` issues → `truncated=false` (fetch `cap+1`, found `cap`).
- Filters applied (priority) → cap applies to the filtered set; `totalAvailable` reflects the filter.
- Drag-and-drop within the capped set → unaffected (all cards real). Moving an issue that would belong
  to a truncated tail → not shown until filtered/backlog — acceptable and explained by the banner.
- Empty project → `truncated=false`, empty array, no banner (unchanged empty-state).
- The cap is clamped server-side; a client passing a huge `size` still routes through the paged path
  (`Paging.MAX_SIZE=100`) and is independently bounded.

### Data model impact
None. Query/`Pageable` + a count only. No migration.

### API surface
`GET …/issues` (no `size`) response body changes from `IssueResponse[]` to
`{ issues: IssueResponse[], truncated: boolean, totalAvailable: number, cap: number }` (Option B).
Paged variant (`?size=`) unchanged. `openapi.yaml` + both `api-*.md` updated (api-docs-sync).
New env var `BOARD_MAX_ISSUES` (default 500).

### DC/Cloud implications
Identical both modes; `app.board.max-issues` env-overridable in base properties. Add `BOARD_MAX_ISSUES`
to `.env.prod.example` + README + `docs/self-hosting.md` (dc-cloud-guard).

### Files to change (Option B)
- `src/main/java/com/hamstrack/issue/controller/IssueController.java` (no-`size` branch → capped)
- `src/main/java/com/hamstrack/issue/service/IssueService.java` (`listCapped` + count + wrapper)
- `src/main/java/com/hamstrack/issue/repository/IssueRepository.java` (a limited/paged fetch for the
  board — can reuse `findByProjectFilteredPaged` with `excludeDone=false`, or a dedicated capped query)
- new `BoardIssuesResponse` DTO (`issue/dto`)
- `src/main/resources/application.properties` (+ `app.board.max-issues`) and `BoardProperties`
- `src/main/frontend/src/api.ts`, `src/main/frontend/src/pages/BoardPage.tsx`, `types.ts`
- `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, `.env.prod.example`, README, self-hosting docs

### Acceptance criteria (Option B — finalize after decision)
- [ ] No-`size` board request never returns more than `app.board.max-issues` issues; the cap is
      enforced server-side and not client-overridable.
- [ ] When the project exceeds the cap, the response reports `truncated=true` + `totalAvailable=M`.
- [ ] Board shows a truncation banner (Beacon-styled) pointing to filters / Backlog / Search; hidden
      when not truncated.
- [ ] Existing drag-and-drop, filters, per-column counts still work on the capped set.
- [ ] `BOARD_MAX_ISSUES` documented in `.env.prod.example` + README + self-hosting; both profiles boot.
- [ ] `openapi.yaml` + both `api-*.md` reflect the new response shape (api-docs-sync).
- [ ] `tenancy-reviewer` sign-off (scoped list path); suite GREEN.

---

## Cross-cutting notes
- **Ordering for the builder:** HD-76 first (pure config; unblocks HD-77/78 by giving them bounded
  outbound calls), then HD-77 and HD-78 in parallel, then HD-79 **after the product-owner decision**.
- **Gates:** all four → `tenancy-reviewer` (any backend diff) + `test-runner`. HD-78 also →
  `migration-reviewer` (V7). HD-76/78/79 → `dc-cloud-guard` (new env vars). HD-79 → `api-docs-sync`.
  HD-77/78 touch security-adjacent surfaces (uploads/mail/tokens) → `security-officer`.
- **Test debt tie-in:** HD-77's compensation test and HD-78's dead-letter test also start paying down
  P2-8 (Q-1 attachments, auth-flow mail) — write them as real regression nets, not smoke tests.
```
