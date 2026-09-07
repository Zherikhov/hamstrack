# ADR-0026: Workspace storage used — a counter in a separate table maintained by a database trigger, not a `SUM` on the hot path and not a column in `workspaces`

Record date: 2026-09-03
Status: Proposed
Source: `docs/design/write-budget-and-storage-quota-proposal.md` §5.1–5.3, §7 (HD-191);
the existing upload path — `issue/service/AttachmentService.java`;
the scar about a counter clobbered by a stale entity — `CLAUDE.md` and `V9` (`projects.issue_seq`);
the precedent for persistent limiter state — ADR-0015

## Context

A workspace has no attachment quota. `spring.servlet.multipart.max-file-size` bounds **one**
file at 25 megabytes; the total volume is bounded by nothing — not per issue, not per project, not per
workspace. In Cloud the backend is S3, where you pay both for the stored byte and for the request, so a
member with an "attach file" button is today an unbounded spend from the operator's card; in DC — an
unbounded claim on disk.

A quota needs **a number to compare against**, and that is the fork. There are three candidates, and
they break in different ways.

- **`SUM(size_bytes)` on every upload.** Correct and unboundedly expensive. `issue_attachments`
  today has no `workspace_id` column, so the aggregate is a join through `issues` and `projects`
  up to `workspaces`, on the hot upload path, and its cost grows with the number of files the
  tenant has ever kept.
- **A counter column in `workspaces`.** Cheap, and it drifts. The project has a direct scar on this
  shape: a counter maintained by native SQL must be `@Column(updatable = false)` on the entity, or a
  stale managed copy silently clobbers it. This has already happened — `projects.issue_seq`, repaired
  by migration `V9`, and the symptom was "duplicate issue numbers". The `Workspace` entity is read,
  mutated and saved on several paths, so the trap here is not theoretical. The second, less obvious
  one: a quota reservation requires a row lock, and `CLAUDE.md` flatly forbids `FOR UPDATE` on
  `workspaces` — every FK child-row insert in the whole tenant would queue behind it.
- **Decrementing the counter from the service only, with no trigger.** Covers `AttachmentService.delete`
  and nothing else. `issue_attachments` cascades from `issues` → `projects` → `workspaces`, and none of
  those deletes goes through application code. The counter would only ever move up, and the quota would
  turn into a ratchet.

A separate question that has to be settled here too: **rows get deleted, and the objects in storage
outlive them.** `AttachmentService.deleteFromStorageAfterCommit` is built not to fail the request when
the blob delete errors (it logs ERROR and continues), and the compensation for a failed upload may not
fire. So the accounted space and the actually occupied space diverge — permanently and in the tenant's
favour. The same asymmetry is described from the other side in `account-deletion-proposal.md` §7.

## Decision

The occupied bytes live in **a separate counter table**, written by **a database trigger**, not by the
application.

```
workspace_storage_usage (workspace_id PK → workspaces ON DELETE CASCADE,
                         bytes_used BIGINT NOT NULL DEFAULT 0,
                         attachment_count BIGINT NOT NULL DEFAULT 0,
                         updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())
```

- **The clobbering scar is closed by construction, not by memory.** No JPA entity maps these
  columns as writable (`insertable = false, updatable = false`); the application reads them through
  a projection and never assigns them. A stale managed copy that could clobber the counter simply
  does not exist — the guarantee does not depend on whether the next author remembers the annotation
  on a new field.
- **This is a row you can lock, and it is not `workspaces`.** Nothing references
  `workspace_storage_usage`, so `SELECT … FOR UPDATE` on it serialises exactly what it is taken for:
  concurrent uploads into one workspace. That is the answer to the classic race "two uploads, each
  fits, together they do not". Before the lock, `LockTimeout.applyToCurrentTransaction()` is mandatory —
  bound first, then lock, the project's standard rule.
- **A trigger, not a service, because the cascade does not go through the application.**
  `AFTER INSERT OR DELETE OR UPDATE OF size_bytes ON issue_attachments FOR EACH ROW`. A cascading
  delete in PostgreSQL is an ordinary row delete, and those fire per-row triggers, so the counter
  follows the rows by construction, including paths nobody has written yet. **This is the central
  assumption of the whole construction, and a test proves it** (delete a project with attachments and
  check the counter came back to its previous value), not a reading of the documentation.
- **`issue_attachments` gains a `workspace_id` column** with the composite foreign key
  `(issue_id, workspace_id) → issues(id, workspace_id)` — the same shape as `sprint_scope_events`
  (V18). The trigger does not need to walk to the parents (and walking to them from inside a cascade is
  a bet on the order of RI cascades), reconciliation and the per-project breakdown become single-table
  index aggregates, and the table finally gets a tenant scope of its own.
- **There is no `CHECK (bytes_used >= 0)`.** A constraint on a trigger-maintained counter can fire
  only when the counter is already wrong, and it will fire on the statement that **decrements** it — that
  is, it turns a harmless drift into an inability to delete an attachment. The trigger clamps with
  `GREATEST(0, …)` instead, and drift becomes a metric rather than a blocked delete.
- **What makes the number true is the reconciliation, not the mere existence of a number.** A periodic
  `WorkspaceStorageReconciler` recomputes `SUM`/`COUNT` from the rows, one workspace per transaction,
  under the same row lock a live upload takes, and publishes the largest absolute delta of the pass as
  `hamstrack.storage.drift_bytes`. Beside it — a freshness gauge seeded with **the process start time**
  and **with no sentinel branch**: a last-write-wins gauge fails silently, and a stopped reconciliation
  would leave the drift frozen at a calm value. This is word for word the same trap described at
  `ProductMetrics.anonymousMailConcentrationRefreshedAt`.
- **The quota counts ROWS, not objects in storage.** An object with no row (a failed compensation, a
  blob that would not delete) is invisible to the quota and is billed anyway. This is admitted, not
  papered over: reconciling objects stays an operator runbook per backend, `FileStorage` does **not** get
  a `list(prefix)` method — an interface method invites an online call that lists the bucket on the
  request thread, and the procedure for both backends is already written in
  `account-deletion-proposal.md` §7.
- **State in PostgreSQL, not in the process.** This is the product's second limiter with that property,
  after the per-address mail ceilings (ADR-0015), and for the same reason: a bound that is reset by a
  deploy is a bound people wait out, and a bound that divides by the number of replicas is not a bound
  for a storage bill.

## Consequences

+ The number is correct at every commit boundary, including the deletion of an issue, a project and a
  workspace, and including paths that do not exist yet.
+ The quota reservation serialises on a row nobody else locks — the cost of the race is not smeared
  across the whole tenant.
+ The quota check costs one PK read under the lock instead of an aggregate over all the tenant's
  attachments, and therefore it can be placed **before** `fileStorage.store`, which is exactly the
  ticket's acceptance criterion ("a refusal before a single byte goes to S3").
+ Reconciliation became cheap and exact: `GROUP BY workspace_id` on an indexed column instead of a
  three-level join.
+ `issue_attachments.workspace_id` is a tenancy improvement in its own right on a table that had no
  scope at all; it also simplifies phase 1 of the erasure runbook (HD-193).
− The product's first business trigger. `updated_at` triggers already exist as a safety net for raw SQL,
  but this is the first trigger a **decision** depends on (refuse or let through). The logic is in two
  places — in the schema and in the spec — and the next author must know that the counter is not
  maintained from Java.
− A row lock on every upload into a workspace. Uploads are rare, so the price is acceptable, but a wait
  beyond the bound answers 409 + `Retry-After` through the existing `handlePessimisticLock`.
− The accounted and the actually occupied diverge in the tenant's favour, forever, and that is closed
  only by a hand pass by the operator. This is written down both in the spec and in
  `docs/self-hosting.md`; it must not be silent.
− One more table and one more trigger, which HD-188's baseline migration must fold in.
− The quota lives outside `ThrottleCoverageTest` (it is not a path binding at all). It is compensated by
  a seal on a third axis — `AttachmentDoorsTest`: every `FileStorage.store` call in the same **method**
  is preceded by a quota reservation. The project now has three questions when adding an expensive
  surface: path, mail, bytes.

## Alternatives

- **`SUM(size_bytes)` on every upload** — rejected: correct and unbounded in cost, on the hot path,
  and today by a three-level join on top of that. It survives as the **reconciliation** mechanism,
  where it belongs: once a day, outside the request, on an indexed column.
- **A counter column in `workspaces`** — rejected twice. First on the `projects.issue_seq` scar: the
  `Workspace` entity is actively read-mutated-saved, so a stale copy will clobber the counter, and the
  guard would come down to an annotation somebody has to remember. Second on the lock: a reservation
  requires `FOR UPDATE`, and `FOR UPDATE` on `workspaces` queues every FK child-row insert in the
  tenant.
- **Decrementing the counter in the service, with no trigger** — rejected: it does not see
  `ON DELETE CASCADE`, that is, not a single issue, project or workspace deletion. The quota would
  become a ratchet, and it would be discovered as "the tenant is refused even though they deleted
  everything".
- **Count the objects in storage rather than the rows** — rejected: the only way to learn the real
  volume is a bucket listing, which cannot be put on the request path, and on a versioned Cloud bucket
  it must also enumerate versions and delete markers. Good for a periodic operator reconciliation,
  not good for a check at the door.
- **`CHECK (bytes_used >= 0)`** — rejected: a constraint that can block a **delete** turns drift from
  an observable problem into an unfixable one.
- **Redis / a shared store for all limiters** — not considered as a solution for this ticket: that is
  a separate fork about horizontal scaling as a whole, and what was needed here was persistent exact
  state in the single place the product already has — PostgreSQL.
