# P2 Cluster 2 — Scale-out Prep — Proposal

Status: **spec (build-ready for HD-80 + HD-82; HD-81 BLOCKED-ON-DECISION on transport)**.
Source: handover audit `docs/design/handover-assessment.md` §3.6 (A-1, A-2) and §3.7 (R-1, R-2),
remediation items **P2-5** (event bus), **P2-6** (tenancy primitive), **P2-1** (multi-node backplane).
Tracking: **HD-80** (domain-event bus), **HD-81** (multi-node backplane), **HD-82** (tenancy access primitive).

## 0. Why these three together, and the sequencing

The audit clustered these because they share one seam: **how a state change fans out**
(HD-80/HD-81) and **how every state change first proves the caller may touch the tenant**
(HD-82). They are being specced together but land in a strict order:

1. **HD-80 FIRST (foundation, fully build-ready).** Introduce a typed domain-event layer so
   state changes stop calling `sseRegistry.broadcast(...)` / `notificationService.create(...)`
   inline. This is behavior-preserving — same SSE events, same notifications — and creates the
   substrate the backplane (and, later, Phase-5 agent triggers) plug into. **No decision needed.**
2. **HD-82 IN PARALLEL (independent, fully build-ready, large but mechanical).** Extract one
   `WorkspaceAccess` primitive and route the ~50 copy-pasted resolve→verify→404 blocks through it,
   no behavior change. Independent of HD-80/81; can run concurrently. **No decision needed.**
3. **HD-81 LAST (blocked on a transport decision).** A shared pub/sub backplane so SSE emitters and
   rate-limit counters work behind >1 replica. Built **on top of** HD-80 (events already flow
   through the bus; the backplane is one more publisher/subscriber). Requires a Postgres-vs-Redis
   transport decision (§HD-81). **Do not start until HD-80 has merged and the transport is chosen.**

**Highest-risk assumption across the cluster:** that HD-80 is truly behavior-preserving. The manual
`afterCommit` deferral in `SseRegistry` (the reason clients don't refetch before commit) must be
reproduced exactly by `@TransactionalEventListener(phase = AFTER_COMMIT)` — including the case where
there is **no active transaction** (today `SseRegistry.afterCommit` runs the action inline; the event
listener must still fire). This is called out in HD-80 acceptance criteria and must be covered by a test.

---

# HD-80 — Domain-event bus (FOUNDATION, build-ready)

## 1. Problem & goal

State changes today publish side effects **inline** with untyped `String` + `Map<String,Object>`
payloads: `IssueService.create/update/delete` call `sseRegistry.broadcast(workspaceId, "ISSUE_CREATED",
Map.of(...))` (IssueService.java:144/374/416), `CommentService` does the same for `COMMENT_*`
(CommentService.java:61/91/107) and calls `notificationService.create(...)` for mentions
(CommentService.java:138), `AttachmentService` for `ATTACHMENT_*` (AttachmentService.java:127/190),
and `NotificationService.create` couples row-insert to `sseRegistry.sendToUser(...)`
(NotificationService.java:67). There is **zero** `ApplicationEvent` / `@EventListener`. Every new
consumer (Phase-5 agent triggers, webhooks, audit) would have to be threaded through these call sites
by hand.

**Goal:** introduce a typed domain-event layer. Services **publish** typed events via
`ApplicationEventPublisher`; **listeners** consume them `@TransactionalEventListener(AFTER_COMMIT)`
and perform the SSE broadcast + notification creation. This replaces the manual `afterCommit`
deferral in `SseRegistry`. **Behavior-preserving:** the exact same SSE event names + payloads and the
exact same notifications as today. Success = the SSE/notification integration tests are green with
zero client-observable change, and adding a new consumer means adding a `@TransactionalEventListener`,
not editing a service.

## 2. Scope

**In scope:**
- A `com.hamstrack.common.event` package: a marker interface + one record per emit point.
- Publishing those events from the existing emit points in `IssueService`, `CommentService`,
  `AttachmentService`, and `NotificationService` (mention path).
- `@TransactionalEventListener(AFTER_COMMIT)` listeners that do the SSE broadcast + notification
  creation — replacing the inline calls and the `SseRegistry.afterCommit` deferral.
- Preserving the exact wire payload for every SSE event.

**Out of scope / non-goals:**
- The multi-node backplane (HD-81) — listeners stay single-node in-memory here.
- Persisting events / an outbox table — no DB change in HD-80.
- Any new event types not backing a current side effect (no speculative "IssueViewed" etc.).
- Async/threaded listeners — listeners run synchronously on the committing thread's post-commit
  callback, exactly as `afterCommit` does today.
- Rewriting `NotificationService.markRead/list` — only the `create(...)` side effect is touched.

## 3. Actors & permissions

No actor-facing change. Events are internal. Authorization/tenant scoping is unchanged — it still
happens in the service **before** the event is published (the event only carries a `workspaceId`
already proven accessible). No new endpoints, no new roles.

## 4. Behavior & rules

### 4.1 Event package & types (`com.hamstrack.common.event`)

A sealed marker interface plus one immutable record per emit point. Every event carries the
`workspaceId` (the SSE routing key) and the minimal payload the current SSE `Map` carries — **no
entity references** (events must be safe to hand to an AFTER_COMMIT listener and, later, serialize
across a backplane; passing a managed `Issue` would risk lazy-load-after-commit with
`open-in-view=false`).

```
package com.hamstrack.common.event;

public sealed interface DomainEvent permits
        IssueCreated, IssueUpdated, IssueDeleted,
        CommentAdded, CommentUpdated, CommentDeleted,
        AttachmentAdded, AttachmentDeleted,
        NotificationRaised {
    UUID workspaceId();
}
```

Records (fields chosen to reproduce today's SSE payloads exactly — see §4.3):

| Event | Fields | Replaces inline call |
|---|---|---|
| `IssueCreated` | `workspaceId, projectId, issueNumber` | `broadcast(ws,"ISSUE_CREATED",{projectId,issueNumber})` |
| `IssueUpdated` | `workspaceId, projectId, issueNumber` (+ optional `changeSet`, see note) | `broadcast(ws,"ISSUE_UPDATED",{projectId,issueNumber})` |
| `IssueDeleted` | `workspaceId, projectId, issueNumber` | `broadcast(ws,"ISSUE_DELETED",{projectId,issueNumber})` |
| `CommentAdded` | `workspaceId, projectId, issueNumber` | `broadcast(ws,"COMMENT_ADDED",{...})` |
| `CommentUpdated` | `workspaceId, projectId, issueNumber` | `broadcast(ws,"COMMENT_UPDATED",{...})` |
| `CommentDeleted` | `workspaceId, projectId, issueNumber` | `broadcast(ws,"COMMENT_DELETED",{...})` |
| `AttachmentAdded` | `workspaceId, projectId, issueNumber` | `broadcast(ws,"ATTACHMENT_ADDED",{...})` |
| `AttachmentDeleted` | `workspaceId, projectId, issueNumber` | `broadcast(ws,"ATTACHMENT_DELETED",{...})` |
| `NotificationRaised` | `workspaceId, recipientUserId, NotificationResponse payload` | `sendToUser(ws,recipient,"NOTIFICATION",payload)` |

**`changeSet` note (recommendation):** the audit (A-1) mentions `IssueUpdated(changeSet)`. Today the
SSE payload does **not** carry a changeset (the client just refetches). To stay behavior-preserving
for SSE, **carry `changeSet` as an OPTIONAL, non-serialized-to-SSE field** — populate it from the
`historyEntries` list `IssueService.update` already builds (field name, old, new), so Phase-5
triggers have it, but the SSE listener ignores it and emits the same `{projectId, issueNumber}` map.
Recommendation: include `List<FieldChange> changeSet` on `IssueUpdated` now (cheap, already
computed); do **not** widen the SSE payload. Flag: if we'd rather not build the field the first
consumer doesn't use, ship `IssueUpdated` without `changeSet` and add it in the Phase-5 ticket —
either is fine; recommended is to include it since the data is already in hand.

### 4.2 Publish points (exact)

Replace each inline `sseRegistry.broadcast(...)` / `notificationService.create(...)` /
`sseRegistry.sendToUser(...)` with a single `eventPublisher.publishEvent(new X(...))`, called at the
**same line, inside the same @Transactional method, after the DB writes** (so the event is published
while the tx is open and the AFTER_COMMIT listener fires only on commit). Inject
`ApplicationEventPublisher` (constructor, via `@RequiredArgsConstructor`).

- `IssueService.create` — IssueService.java:144 → `publishEvent(new IssueCreated(workspaceId, projectId, issue.getNumber()))`
- `IssueService.update` — IssueService.java:374 → `publishEvent(new IssueUpdated(workspaceId, projectId, number, changeSetFromHistory))`
- `IssueService.delete` — IssueService.java:416 → `publishEvent(new IssueDeleted(workspaceId, projectId, number))`
- `CommentService.create` — CommentService.java:61 → `CommentAdded`
- `CommentService.update` — CommentService.java:91 → `CommentUpdated`
- `CommentService.delete` — CommentService.java:107 → `CommentDeleted`
- `AttachmentService.upload` — AttachmentService.java:127 → `AttachmentAdded`
- `AttachmentService.delete` — AttachmentService.java:190 → `AttachmentDeleted`
- `CommentService.applyMentions` — CommentService.java:138: keep calling `notificationService.create(...)`
  (see §4.4 — the notification row must be written in the *same tx* as the comment). The SSE push
  that `NotificationService.create` currently does inline moves to a `NotificationRaised` event.

**Special case — `AttachmentService`.** `upload` is deliberately **not** `@Transactional` (blob I/O
runs off the tx via `TransactionTemplate`, HD-76). The `ATTACHMENT_ADDED` broadcast currently happens
*after* the row-commit and the blob store, on the request thread with no active tx — so today
`SseRegistry.afterCommit` runs it **inline** (no synchronization active). To preserve this,
`AttachmentService.upload` must publish `AttachmentAdded` at the same point (after successful store),
and the listener must broadcast immediately when there is no active transaction. This is exactly the
"no active tx → run inline" branch that must be reproduced (§4.4, and the highest-risk item in §0).
`AttachmentService.delete` **is** `@Transactional`, so its event defers to AFTER_COMMIT normally.

### 4.3 Listeners (`com.hamstrack.common.event` or a `sse`/`notification` sub-package)

Two listener beans, each `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,
fallbackExecution = true)`:

- **`SseEventListener`** (in `common.sse` or `common.event`) — handles the eight issue/comment/attachment
  events. For each, calls `sseRegistry.broadcast(e.workspaceId(), "<EVENT_NAME>", Map.of("projectId",
  e.projectId().toString(), "issueNumber", e.issueNumber()))` — producing the identical wire payload.
  Also handles `NotificationRaised` → `sseRegistry.sendToUser(e.workspaceId(), e.recipientUserId(),
  "NOTIFICATION", e.payload())`.
- The `SseRegistry.broadcast/sendToUser` methods **drop their internal `afterCommit` wrapper** — they
  become plain "send now to connected emitters" methods, because the listener already guarantees
  AFTER_COMMIT timing. (Keep `SseRegistry.send`'s broadened `catch (Exception)` from P1-b intact.)

**`fallbackExecution = true` is mandatory** — it makes `@TransactionalEventListener` run the listener
inline when the event is published with **no active transaction** (the `AttachmentService.upload`
case). This is the single-line replacement for `SseRegistry.afterCommit`'s `else { action.run(); }`
branch. Without it, attachment-added SSE would silently never fire.

### 4.4 Notification creation — keep the row in-tx, defer only the push

`NotificationService.create` currently does two things in one `@Transactional` method: (1) INSERT the
notification row, (2) `sseRegistry.sendToUser(...)` push. Today (2) is deferred by `afterCommit`; (1)
commits with the surrounding tx.

Rule after HD-80: `NotificationService.create` still **synchronously inserts the row inside the
caller's transaction** (so a mention notification and its comment commit atomically — unchanged), but
**publishes a `NotificationRaised` event** instead of pushing SSE directly. The `SseEventListener`
does the AFTER_COMMIT push. Net behavior identical: row persisted iff tx commits; SSE pushed once,
after commit, only if committed.

## 5. Edge cases & failure modes

- **No active transaction (attachment upload).** Covered by `fallbackExecution = true` — listener runs
  inline. Must be tested (upload → assert `ATTACHMENT_ADDED` delivered).
- **Rollback.** A publish inside a tx that rolls back → AFTER_COMMIT listener never fires → no SSE, no
  duplicate. Identical to today (deferral discarded on rollback).
- **Listener throws.** An SSE send failure must not corrupt anything — it's post-commit, the DB write
  already succeeded. `SseRegistry.send` already swallows per-emitter failures (P1-b). A listener-level
  exception in AFTER_COMMIT is logged by Spring and does not roll back (tx already committed) — same
  as today.
- **Ordering.** Multiple events from one tx fire in publish order on the same thread; matches the
  sequential inline calls today.
- **Self-invocation.** Publishing via injected `ApplicationEventPublisher` (not `this`) — no proxy
  pitfalls.
- **Idempotency/races** — unchanged; no new persistence, no dedup needed at this layer.

## 6. Data model impact

**None.** No new tables or columns. No Flyway migration. (An outbox table is explicitly deferred to
HD-81 if the chosen backplane needs it — see HD-81 §Data model.)

## 7. API surface

**None.** No endpoint changes. `openapi.yaml` / `docs/api-*.md` untouched → `api-docs-sync` gate is
**n/a** for HD-80.

## 8. Frontend impact

**None** — the SSE wire contract (event names + payload shape) is byte-for-byte preserved. The SPA's
EventSource handlers (`TopSearchBar` / notification bell / board refresh) require no change. This is
the central acceptance guarantee.

## 9. DC/Cloud implications

**None** — no profile/property gating, no new env var. Single-node in-memory listener behavior is
identical in `dc` and `cloud`. `dc-cloud-guard` gate is **n/a** for HD-80 (no properties/profile/
compose/env touched).

## 10. Acceptance criteria

- [ ] New package `com.hamstrack.common.event` with a sealed `DomainEvent` interface + the nine
      records listed in §4.1; all immutable, carry `workspaceId()`.
- [ ] Every inline `sseRegistry.broadcast(...)` in `IssueService`, `CommentService`, `AttachmentService`
      is replaced by `eventPublisher.publishEvent(...)` at the same site.
- [ ] `NotificationService.create` inserts the row synchronously in-tx and publishes `NotificationRaised`
      instead of calling `sseRegistry.sendToUser` directly.
- [ ] `SseRegistry.broadcast/sendToUser` no longer wrap in `afterCommit`; the `afterCommit` helper is
      removed (or made private-unused → removed). `send`'s broad `catch (Exception)` retained.
- [ ] One or more `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`
      listeners produce the **identical** SSE event name + JSON payload for every event type.
- [ ] Attachment-upload SSE (`ATTACHMENT_ADDED`) fires even though `upload` is non-transactional
      (fallbackExecution path) — covered by a test.
- [ ] A transaction that rolls back produces no SSE and no notification (covered by a test).
- [ ] Existing SSE/notification integration behavior unchanged: a member connected to the workspace
      SSE stream receives the same events on create/update/delete/comment/attachment/mention as before.
- [ ] Adding a second no-op listener to any event compiles and runs without touching the publishing
      service (demonstrates the seam) — optional smoke test.
- [ ] `tenancy-reviewer` confirms no scoping regression (events carry only ids already proven
      accessible; no new query path).
- [ ] Backend suite green.

## 11. Open questions

- **`IssueUpdated.changeSet` now or later?** Recommend **now** (data already computed in
  `historyEntries`; SSE payload unaffected). Low risk, saves a Phase-5 re-touch. Decision is
  reversible either way.

---

# HD-82 — WorkspaceAccess primitive (independent, build-ready — LARGE)

## 1. Problem & goal

The tenancy invariant (resolve workspace → verify membership → **404 not 403** for
non-member/non-existent) is enforced by **copy-paste across ~12 services**. Verified sites (identical
shape): `IssueService.resolveWorkspace` (IssueService.java:580), `AttachmentService.resolveIssue`
(AttachmentService.java:268), `CommentService.resolveIssue` (CommentService.java:184),
`SearchService.resolveWorkspace` (SearchService.java:227), `SavedFilterService.resolveWorkspace`
(SavedFilterService.java:193), `ProjectService.resolveWorkspace`, `WorkspaceService.requireMembership`
(WorkspaceService.java:218), plus the project/issue-nested variants and the SSE controller
(SseController.java:33) and the admin `ScopeResolver` (which is already a partial primitive).

Each copy is one `workspaceRepository.findById(...).orElseThrow` + one
`workspaceMemberRepository.findByWorkspaceAndUser(...).orElseThrow`. Safe today by discipline, but
every new feature multiplies the surface and every copy is a place the 404-not-403 rule can be gotten
wrong. **The audit's top bug class is exactly a query that forgets to scope by membership.**

**Goal:** extract **one** `WorkspaceAccessService` primitive that returns a resolved,
membership-checked context, and route all callers through it — **no behavior change** (still
`WorkspaceNotFoundException` → 404 for both non-member and non-existent). Success = every
resolve→verify block is one call; a reviewer can audit tenancy at one chokepoint; behavior is
byte-identical.

## 2. Scope

**In scope:**
- New `WorkspaceAccessService` (in `com.hamstrack.workspace.service`) exposing the resolve+verify
  primitive and its common compositions (workspace-only; workspace+project; workspace+project+issue).
- A returned `WorkspaceContext` value object carrying the resolved `Workspace` (+ the caller's
  `WorkspaceMember`/role, so role-gated callers stop re-querying).
- **Incremental** migration: route callers through it **one feature package at a time**
  (issue → project → comment → attachment → search → saved-filter → workspace → admin scope), each a
  separate reviewable diff.

**Out of scope / non-goals:**
- Changing any HTTP status, message, or exception type (must stay `WorkspaceNotFoundException` /
  `ProjectNotFoundException` / `IssueNotFoundException` exactly where they are today).
- Merging role checks that are genuinely per-feature (e.g. `ProjectRole.MANAGER` gate in
  `IssueService.delete`) — the primitive returns the context; callers keep their own role assertions
  (though the primitive MAY offer `requireWorkspaceRole(...)` helpers mirroring `ScopeResolver`).
- Folding in `ScopeResolver` (admin delegated scope) — it already exists and encodes a **different**
  rule (member-without-role → **403**, not 404). Recommendation: keep `ScopeResolver` as-is, but have
  it *delegate its resolve+membership step* to the new primitive to remove its own copy (small, last
  step). Do **not** unify their 403-vs-404 semantics.
- Any DB or API change.

**Flagged risk — size.** This is a large mechanical refactor touching ~12 services and ~50 call
sites. It is low-conceptual-risk but high-surface. **It MUST be done incrementally (one package per
PR/commit) and `tenancy-reviewer` MUST gate every step** — because a mistake here is precisely the
project's top bug class (cross-tenant leak). Do not land it as one mega-diff.

## 3. Actors & permissions

No actor-facing change. The primitive encodes the existing membership rule; role gates stay with
callers. Tenant scoping semantics are preserved exactly: 404 for non-member and non-existent alike.

## 4. Behavior & rules

### 4.1 API

```
@Service @RequiredArgsConstructor
public class WorkspaceAccessService {

    // Resolve workspace + verify actor membership. 404 (WorkspaceNotFoundException)
    // for both non-existent and non-member — identical to every current copy.
    @Transactional(readOnly = true)
    WorkspaceContext requireMember(User actor, UUID workspaceId);

    // Composition: workspace membership + project-in-workspace resolution.
    // 404 WorkspaceNotFoundException (ws) then ProjectNotFoundException (project).
    @Transactional(readOnly = true)
    ProjectContext requireProjectMember(User actor, UUID workspaceId, UUID projectId);

    // Composition: the above + issue-by-number-in-project.
    @Transactional(readOnly = true)
    IssueContext requireIssue(User actor, UUID workspaceId, UUID projectId, long issueNumber);

    // Optional role helper (mirrors ScopeResolver semantics, member-without-role → 403).
    // Use ONLY where a caller wants workspace-role gating; NOT the default path.
    WorkspaceContext requireWorkspaceRole(User actor, UUID workspaceId, WorkspaceRole min);
}
```

Context value objects (records) — carry the already-loaded managed entities so callers don't re-query:

```
record WorkspaceContext(Workspace workspace, WorkspaceMember membership) {
    WorkspaceRole role() { return membership.getRole(); }
}
record ProjectContext(Workspace workspace, WorkspaceMember membership, Project project) { ... }
record IssueContext(..., Issue issue) { ... }
```

### 4.2 Exact preserved semantics (the invariant that must not drift)

- `requireMember`: `workspaceRepository.findById(id).orElseThrow(WorkspaceNotFoundException::new)`
  then `workspaceMemberRepository.findByWorkspaceAndUser(ws, actor).orElseThrow(WorkspaceNotFoundException::new)`.
  Byte-identical to `IssueService.resolveWorkspace`, `SearchService.resolveWorkspace`, etc.
- `requireProjectMember`: the above **then**
  `projectRepository.findByIdAndWorkspace(projectId, ws).orElseThrow(ProjectNotFoundException::new)`
  — matches every `create/get/update` in `IssueService`/`ProjectService`.
- `requireIssue`: the above **then**
  `issueRepository.findByProjectAndNumber(project, number).orElseThrow(IssueNotFoundException::new)`
  — matches `CommentService.resolveIssue` / `AttachmentService.resolveIssue`.
- Must run inside the caller's transaction (methods are `@Transactional`; the primitive is
  `readOnly = true` and participates in the caller's tx via `REQUIRED` propagation — no new tx
  boundary, so entities stay managed for the caller).

### 4.3 Migration plan (incremental, one package per step; each gated by tenancy-reviewer)

1. Add `WorkspaceAccessService` + context records; **no caller changes** (compiles green, tests green).
2. `issue` package: replace `IssueService.resolveWorkspace` and the inline project/issue resolves;
   replace `resolveAssignee`'s workspace membership check stays (different rule). Review.
3. `comment` (`CommentService.resolveIssue`) → `requireIssue`. Review.
4. `attachment` (`AttachmentService.resolveIssue`, incl. the two `TransactionTemplate` blocks —
   the primitive is called *inside* the `txTemplate.execute(...)` so it participates in that tx). Review.
5. `search` + `saved-filter` (`resolveWorkspace`). Review.
6. `project` (`ProjectService.resolveWorkspace` + `resolveProject`). Review.
7. `workspace` (`WorkspaceService.requireMembership` — note this one *returns the member* to read the
   role; `requireMember` already returns it via the context). Review.
8. `SseController` inline resolve → `requireMember`. Review.
9. `admin/scope` (`ScopeResolver`) delegates its resolve+membership step to the primitive but keeps
   its **403-on-insufficient-role** behavior. Review carefully (different semantics).

Each step is independently shippable and independently reviewable.

## 5. Edge cases & failure modes

- **404 vs 403 must not flip.** The default path is 404 for member-or-not. Only the optional
  `requireWorkspaceRole`/`ScopeResolver` path returns 403 (member, wrong role) — and only where that
  was already the behavior. A regression here is a tenancy leak or an existence-disclosure — the exact
  thing `tenancy-reviewer` guards.
- **Managed-entity lifetime.** Because the primitive participates in the caller's tx (no `REQUIRES_NEW`),
  returned entities stay managed and lazy associations remain navigable in the caller — same as inline
  today. Verify no accidental new tx boundary.
- **AttachmentService's off-tx orchestration.** `upload`/`download` resolve inside
  `txTemplate.execute(...)` blocks, not a `@Transactional` method. The primitive must be callable there
  (it is — `@Transactional(readOnly=true)` joins the template's active tx via propagation). Keep the
  resolve inside the template block, unchanged in timing.
- **Concurrency / optimistic locking** — unaffected; the primitive only reads.
- **Archived/soft-deleted** — the primitive does NOT add archived checks (those are separate,
  per-feature `requireNotArchived`); it only does resolve + membership, matching today.

## 6. Data model impact

**None.** No migration. `migration-reviewer` gate **n/a** unless a step touches an `@Entity` (it does
not). Repository method signatures are reused as-is.

## 7. API surface

**None.** No endpoint/DTO/status change. `api-docs-sync` gate **n/a**.

## 8. Frontend impact

**None.**

## 9. DC/Cloud implications

**None** — pure internal refactor, no profile/property/env. `dc-cloud-guard` gate **n/a**.

## 10. Acceptance criteria

- [ ] `WorkspaceAccessService` exists with `requireMember` / `requireProjectMember` / `requireIssue`
      (+ optional `requireWorkspaceRole`) and the context records.
- [ ] Every listed service's resolve→verify copy is replaced by a primitive call (tracked per step);
      no remaining inline `workspaceRepository.findById(...).orElseThrow(...)` +
      `findByWorkspaceAndUser(...).orElseThrow(...)` pair outside the primitive (grep-verified,
      excluding `ScopeResolver`'s role branch).
- [ ] Non-member and non-existent workspace **both** still return 404 (`WorkspaceNotFoundException`);
      wrong-role admin scope still returns 403 (`ScopeResolver`) — unchanged.
- [ ] No HTTP status, message, or exception-type change in any migrated path (diff the responses).
- [ ] Each migration step is a separate commit/PR reviewed by `tenancy-reviewer` and green on the
      backend suite before the next step.
- [ ] `AttachmentService` off-tx resolve still works (upload/download resolve inside the template block).
- [ ] Backend suite green after every step; the tenancy tests (issue-hierarchy 409, delegated-admin,
      cross-tenant) remain green.

## 11. Open questions

- **Fold `ScopeResolver` into the primitive fully, or just delegate its resolve step?** Recommend
  **delegate only** — keep `ScopeResolver` as the admin-scope façade with its 403 semantics; have it
  call `WorkspaceAccessService` for the resolve+membership half. Unifying the 403-vs-404 rules would be
  a behavior change and is out of scope.
- **Return type ergonomics.** Recommend concrete records (`WorkspaceContext`/`ProjectContext`/
  `IssueContext`) over generics — simplest for callers, clearest for reviewers.

---

# HD-81 — Multi-node backplane (BLOCKED-ON-DECISION)

> **Status: BLOCKED-ON-DECISION.** Build only after (a) HD-80 has merged and (b) the transport is
> chosen (§ Transport decision below). The rest of the spec is ready; the transport choice determines
> the concrete infra bean and env vars.

## 1. Problem & goal

Two subsystems hold per-JVM state that breaks behind >1 replica:

- **SSE fan-out is single-node in-memory** — `SseRegistry.connections` is a
  `ConcurrentHashMap<UUID, List<UserEmitter>>` (SseRegistry.java:25). Behind N replicas, a state change
  handled on node A only reaches the emitters connected to node A; `(N-1)/N` of a workspace's connected
  users never get the live event (R-1, Critical-for-scale).
- **Rate-limit / login-backoff counters are single-node** — `RateLimitService` holds
  `ipWindows` and `loginFailures` as per-JVM `ConcurrentHashMap`s (RateLimitService.java:37-39). Behind
  N replicas the effective limit is `N ×` the configured cap; abuse protection is divided by replica
  count (R-2 / S-2, Critical-for-scale).

**Goal:** a shared pub/sub **backplane** that fans SSE events across nodes, and a shared **atomic
counter store** for rate limiting — both **profile/property-gated** so DC single-node keeps the exact
in-memory path it has today. Built **on top of HD-80**: events flow through the domain-event bus, then
a backplane publisher fans them to other nodes, where a subscriber re-delivers them to that node's
local `SseRegistry`. Success = with ≥2 replicas, a member connected to any node receives every
workspace event and the rate-limit cap is global; with the feature off (DC), behavior is byte-identical
to today.

## 2. Scope

**In scope:**
- A `Backplane` abstraction (publish/subscribe of `DomainEvent`s keyed by workspace) with **two**
  interchangeable, property-gated implementations: an in-memory no-op (default; single-node) and the
  chosen distributed transport.
- Wiring the HD-80 `SseEventListener` so that, when the backplane is active, an event is **published to
  the backplane** instead of (or in addition to) being delivered only to the local registry; a
  backplane **subscriber** delivers received events to the local `SseRegistry`.
- A shared **rate-limit store** abstraction with an in-memory default (today's behavior) and a
  distributed atomic implementation (INCR/TTL semantics), gated by the same or a sibling property.
- Env vars + per-profile defaults + full wiring (properties → compose → `.env.prod.example` → README).

**Out of scope / non-goals:**
- Cross-node **presence** (who is online where) — not needed; each node keeps its own emitter set and
  simply re-broadcasts received events to its local emitters.
- Guaranteed delivery / replay — SSE is already best-effort (EventSource reconnects and the client
  refetches). No durable event log required for correctness.
- Sticky sessions — the design must NOT require them (any node can serve any SSE connection).
- Replacing the domain-event bus — HD-81 consumes it, does not change it.

## 3. Actors & permissions

No actor-facing change. Membership is still checked on SSE subscribe (per node, `SseController`) and
before publish (in the service). The backplane only moves already-authorized events between nodes; it
must carry the `workspaceId` so each receiving node routes to the correct local emitters (and only
those — a node never broadcasts a workspace's event to emitters of another workspace).

## 4. Behavior & rules

### 4.1 SSE fan-out with a backplane

1. Service publishes a `DomainEvent` (HD-80) → AFTER_COMMIT.
2. The AFTER_COMMIT listener, **when the backplane is active**, hands the event to
   `Backplane.publish(event)` instead of broadcasting only locally.
3. Every node (including the origin) runs a `Backplane` **subscriber** that, on receiving an event,
   calls the **local** `SseRegistry.broadcast/sendToUser` for its own connected emitters.
4. Net effect: an event reaches every emitter for that workspace across all nodes, exactly once per
   emitter (the origin node delivers via the subscriber too, so it must **not** also deliver locally —
   avoid double-send; see §5).

When the backplane is the **in-memory no-op** (DC / single node), step 2 delivers straight to the
local registry (identical to HD-80 single-node behavior; no serialization, no round-trip).

### 4.2 Shared rate-limit counters

`RateLimitService`'s two maps become an interface (`RateLimitStore`) with:
- in-memory implementation = today's `ConcurrentHashMap` + `@Scheduled` eviction (default);
- distributed implementation = atomic increment with expiry (per-IP fixed-window key
  `rl:ip:{ip}:{epochMinute}` with a 2-minute TTL; per-account failure counter
  `rl:login:{emailLower}` with a TTL ≥ `loginBackoffMaxSeconds`). The window/backoff **math stays in
  `RateLimitService`**; only the counter storage is swapped. The distributed store must provide
  atomic increment-and-read and TTL — this is required **regardless of the SSE transport choice**
  (Postgres and Redis both can, but the ergonomics differ — see decision).

Gating: reuse `app.rate-limit.enabled` for on/off, add `app.rate-limit.store` (`memory` | `<distributed>`)
defaulting to `memory` on all profiles; the distributed value is meaningful only when the chosen
backend is configured.

### 4.3 Profile/property gating (mandatory, both subsystems)

- `app.backplane.type` = `none` (default, in-memory single-node) | `<postgres|redis>` (chosen transport).
- Profile defaults: `dc` → `none` (single artifact, no extra infra); `cloud` → still `none` **until
  Cloud actually runs >1 replica**, then flipped by env. **Never** default Cloud to a distributed
  backplane implicitly — it must be an explicit operator opt-in with the backing infra provisioned.
- `app.rate-limit.store` mirrors this (`memory` default everywhere).
- All distributed beans are `@ConditionalOnProperty` — mirrors the `FileStorage`
  `LocalFileStorage`/`S3FileStorage` pattern exactly. Inject the `Backplane` / `RateLimitStore`
  interface, never a concrete class.

## 5. Edge cases & failure modes

- **Double-send on the origin node.** The origin publishes to the backplane AND its own subscriber
  receives it → must deliver only via the subscriber path (not also locally), or de-dup by an event id.
  Recommendation: **always** route through the backplane (origin included); the local no-op backplane
  short-circuits to local delivery, the distributed one delivers only via subscriber. One code path,
  no double-send.
- **Backplane down / unreachable.** SSE is best-effort; a publish failure must be logged and swallowed
  (never fail the already-committed request). Clients reconnect + refetch. Rate-limit store down is
  more sensitive: **fail-closed vs fail-open** is a policy call — recommend **fail-open with a loud
  metric/log** (don't lock out all logins because the counter store blipped), matching the current
  `enabled=false` degradation.
- **Payload size (Postgres LISTEN/NOTIFY).** `NOTIFY` payloads are capped (8 KB). Our events are tiny
  (`{projectId, issueNumber}` + workspaceId), well under the cap — but `NotificationRaised` carries a
  `NotificationResponse`; keep backplane payloads to **ids only** and let each node re-render, or send
  the small notification DTO (still < 8KB). Must be validated if Postgres is chosen.
- **Connection considerations (Postgres LISTEN/NOTIFY).** LISTEN needs a **dedicated long-lived
  connection per node** outside the Hikari request pool (a listener thread). Must not consume a pooled
  connection. Requires a separate `PGConnection`/driver-level listener or a library.
- **Redis dependency (Redis option).** Adds a required external service; must have a **DC self-hosted
  story** (bundled Redis container in the DC compose, or the `none` default so DC never needs it).
- **Ordering across nodes.** Best-effort; SSE clients tolerate reordering (they refetch). No global
  ordering guarantee required.
- **Eviction (rate-limit).** The in-memory `@Scheduled` eviction is replaced by TTL in the distributed
  store — no cross-node scheduled cleanup needed.
- **Security.** The backplane channel is trusted infra (same VPC / same DB); events carry no secrets
  beyond ids + a notification title the recipient may already see. No new authz on the channel itself,
  but the receiving node still only delivers to emitters whose membership was verified at subscribe.

## 6. Data model impact

- **Postgres LISTEN/NOTIFY option:** no table needed for SSE (NOTIFY is transient). If we want the
  rate-limit counters in Postgres too, that needs either an `UPSERT ... RETURNING` on a small
  `rate_limit_counter(key, window_start, count)` table with periodic cleanup, or an unlogged table —
  **a Flyway migration** (`migration-reviewer` gate applies). Trade-off noted below.
- **Redis option:** no schema change at all (counters + pub/sub both in Redis).

## 7. API surface

**None** (no new endpoints). SSE wire contract unchanged from HD-80. `api-docs-sync` **n/a** for
runtime API, but the **operator docs** (self-hosting / `.env.prod.example` / README) must gain the new
toggles (that's the `dc-cloud-guard` wiring checklist, not `api-docs-sync`).

## 8. Frontend impact

**None** — the SPA sees the same SSE stream regardless of how many nodes back it.

## 9. DC/Cloud implications

- New env vars (names indicative, finalized with the transport decision):
  - `BACKPLANE_TYPE` → `app.backplane.type` (default `none`; `dc` profile default `none`).
  - `RATE_LIMIT_STORE` → `app.rate-limit.store` (default `memory`).
  - Transport-specific (Redis): `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` (+ a DC compose service if
    bundled). (Postgres: reuses the existing datasource for the listener connection; no new creds.)
- Per-profile defaults: DC = single-node (`none`/`memory`), Cloud = `none`/`memory` **until** the
  operator provisions the backend and flips the env. **No cloud-only fork** — the same JAR runs both;
  the difference is entirely env-driven, mirroring `FileStorage`.
- Full wiring checklist (dc-cloud-guard): `*.properties` defaults → `docker-compose*` → `.env.prod.example`
  → `README`/`docs/self-hosting.md`. The Redis option adds a compose service + a documented DC path;
  the Postgres option adds nothing but the two toggles.

## Transport decision — OPEN QUESTION (recommendation below)

Both options are **profile/property-gated** so DC single-node keeps the in-memory path. Both are built
**on top of HD-80** (events already flow through the bus; the backplane is one publisher/subscriber
plugged into the AFTER_COMMIT listener). The rate-limit counters need a shared **atomic** store
**regardless** of the SSE transport chosen — that requirement is what tips the recommendation.

### Option A — Postgres `LISTEN/NOTIFY`

- **Pros:** **no new infrastructure dependency** — reuses the database we already require, which fits
  the DC/Cloud single-artifact rule best (nothing extra for a self-hoster to run). SSE fan-out maps
  cleanly to `NOTIFY channel, payload` / `LISTEN channel`. No new creds.
- **Cons:**
  - **Payload cap** — `NOTIFY` payload ≤ 8 KB; fine for our id-only events but a hard constraint to
    respect (must keep `NotificationRaised` payloads small / id-only).
  - **A dedicated long-lived connection per node** for `LISTEN` (a listener thread outside Hikari) —
    added operational moving part and driver-level plumbing (raw `PGConnection` or a small library).
  - **Rate-limit counters don't fit LISTEN/NOTIFY at all** (that's pub/sub, not a counter). Shared
    counters would need a **separate Postgres mechanism** — an atomic `UPSERT ... RETURNING` on a
    counter table (a Flyway migration + periodic cleanup, or an unlogged table). So Option A means
    **two different Postgres mechanisms** (NOTIFY for SSE, a counter table for rate limits), plus the
    migration and cleanup job. More surface, and per-request counter writes add DB load on the hot auth
    path.

### Option B — Redis (pub/sub for SSE + atomic INCR/TTL for rate limits)

- **Pros:**
  - **One dependency solves both problems** — Redis pub/sub for SSE fan-out **and** `INCR` + `EXPIRE`
    (atomic, TTL-native) for the shared rate-limit counters. The counter semantics we need (fixed
    window increment, exponential-backoff failure count with expiry) are **exactly** Redis's sweet spot
    — no schema, no cleanup job, no DB load on the auth path.
  - Clean, standard, well-trodden pattern; no payload cap concern; no wrestling a long-lived listener
    connection out of the JDBC pool.
  - Spring Data Redis gives both `RedisMessageListenerContainer` (pub/sub) and atomic ops out of the box.
- **Cons:**
  - **Adds a required external dependency** for multi-node Cloud — must have a real **DC self-hosted
    story**. Mitigation: it's only required when `BACKPLANE_TYPE=redis` (Cloud multi-node); DC defaults
    to `none`/`memory` and never needs Redis. For a DC operator who *wants* multi-node, ship a bundled
    Redis service in the DC compose (mirrors how S3-compatible MinIO is the DC story for `FileStorage`).

### Recommendation — **Option B (Redis)**

**Recommend Redis**, gated by `app.backplane.type=redis` + `app.rate-limit.store=redis`, defaulting to
`none`/`memory` on both profiles. Rationale:

1. **The rate-limit half decides it.** R-2/S-2 need a **shared atomic counter with TTL** no matter what
   we pick for SSE. Redis does this natively (`INCR`/`EXPIRE`); Postgres would require a bespoke counter
   table + migration + cleanup + per-request writes on the hot auth path. Choosing Postgres for SSE
   still leaves us building a second, awkward Postgres mechanism for counters — so Postgres doesn't
   actually avoid new complexity, it just moves it.
2. **One backend, two problems.** Redis solves SSE fan-out and rate-limit counters with one dependency
   and one operational story, versus Postgres's two mechanisms (NOTIFY + counter table).
3. **DC parity is preserved cleanly.** Redis is required **only** for the opt-in multi-node path; DC
   and single-node Cloud keep the in-memory beans via the `none`/`memory` defaults. The self-hosted
   story is the same shape as `FileStorage`'s MinIO story — an optional bundled container, not a
   cloud-only assumption. Differentiator (a) (DC+Cloud parity from one artifact) is intact.

**Trade-off accepted:** one more optional infra component. **Trade-off rejected (Postgres):** avoiding
that component but paying with a second Postgres counter mechanism, a Flyway migration, an auth-path DB
write, a payload cap, and a hand-rolled LISTEN connection — more total complexity for a worse
rate-limit fit.

**If the user prefers zero new infra over the cleaner rate-limit story**, Option A (Postgres) is viable
for SSE, but then the rate-limit shared store should still be its own decision (Postgres counter table),
and we accept the auth-path DB write. This is the trade-off to take to the user.

## 10. Acceptance criteria (once transport is chosen)

- [ ] `Backplane` interface with a `none` in-memory default bean (single-node, byte-identical to
      HD-80) and the chosen distributed bean, both `@ConditionalOnProperty` on `app.backplane.type`.
- [ ] `RateLimitStore` interface with a `memory` default (today's maps + `@Scheduled` eviction) and the
      distributed atomic implementation, gated by `app.rate-limit.store`.
- [ ] With `type=none`/`store=memory`: behavior byte-identical to HD-80 (no serialization, no round-trip)
      — DC and single-node Cloud unaffected; existing SSE + rate-limit tests green.
- [ ] With the distributed backend + ≥2 nodes: an event published on node A reaches emitters on node B;
      no double-send on the origin node; a rate-limit cap is global (a two-node integration/manual test).
- [ ] Backplane publish failure is logged and swallowed (request already committed); rate-limit store
      failure fails **open** with a metric (documented policy).
- [ ] `SseController` subscribe still checks membership per node; a node only delivers a workspace's
      event to that workspace's local emitters.
- [ ] Full env-var wiring: `*.properties` defaults + `docker-compose*` + `.env.prod.example` +
      `README`/`docs/self-hosting.md` (dc-cloud-guard gate). Redis option adds a compose service + DC path.
- [ ] `dc-cloud-guard` passes (no forked logic; every toggle has an env var + per-profile default).
- [ ] `migration-reviewer` passes **iff** the Postgres-counter-table path is chosen.

## 11. Open questions

- **Transport: Redis (recommended) vs Postgres LISTEN/NOTIFY** — the one blocking decision (above).
- **Rate-limit store failure policy: fail-open vs fail-closed** — recommend **fail-open + loud metric**.
- **When does Cloud actually flip to multi-node?** The backplane is inert until an operator sets the
  env; recommend building it now (unblocks scale) but leaving Cloud on `none` until the scale event.
- **Bundle Redis in the DC compose, or document "bring your own"?** Recommend bundling an optional
  Redis service (commented/off by default), mirroring MinIO — only relevant to DC operators who want
  multi-node.
