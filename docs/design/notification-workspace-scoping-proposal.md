# Workspace-scoping the notification inbox

**Ticket:** HD-135 · Story · **5 points** · medium · backend · branch `feat/release-0.17.0`
**Status:** proposed, ready to build
**Touches:** one new migration (`V20`), `Notification`, `NotificationRepository`,
`NotificationService`, `NotificationResponse`, `CommentService` (one call site),
`src/main/frontend/src/types.ts` (one field), `openapi.yaml` + both `docs/api-*.md`,
`docs/project-state.md`. All tests are new.

---

## 1. Problem & goal

`notifications` has no `workspace_id` (`V1__init_schema.sql:435-445`) and every finder on
`NotificationRepository` is keyed on the user alone. A notification's `title` and `body` are
**denormalised copies of workspace content** — the mentioning user's display name and up to 120
characters of the comment body — written into the row at delivery time. So when a member is removed
from a workspace, the rows survive, the list endpoint still returns them because it filters only by
user, and no membership check anywhere can redact text that is already sitting in the row. HD-132
is what turned this from latent to live: until removal existed, the only way to lose access to a
workspace was to have your account deleted, and `notifications.user_id ON DELETE CASCADE` handled
that. Now the product has a revocation event, and this table does not observe it.

The quieter consequence is that for a user in several workspaces the rows are **indistinguishable at
the data layer**. Any per-workspace notification concept — mute a workspace, digest per workspace,
a per-workspace preference row — has nothing to key on and would have to reverse-engineer the tenant
out of a URL string.

**Success:** every notification row names exactly one workspace; every read and every write path on
that table resolves through workspace membership; a removed member's inbox for that workspace is
empty in every surface, and comes back intact if they rejoin.

---

## 2. Scope

**In scope**

1. `V20__notifications_workspace_scope.sql` — add `workspace_id UUID NOT NULL`, backfill it, FK to
   `workspaces(id) ON DELETE CASCADE`, one index.
2. `Notification.workspace` as a `@ManyToOne`, and `NotificationService.create` taking a
   `Workspace` instead of a `UUID` so a notification cannot be raised without one (§6.4 — this is a
   **deliberate deviation from the ticket's "no call-site signature change"**).
3. The membership predicate on **all four** paths — `list`, `countUnread`, `markRead`,
   `markAllRead` — expressed in the query, not in the caller.
4. `NotificationResponse.workspaceId` (additive), and the resulting doc updates.
5. Tests: the four filtered paths, the rejoin case, the backfill, and a **sealed-set test** that
   fails if any finder on `NotificationRepository` is added without the predicate.

**Out of scope / non-goals**

- **Purging rows on removal.** Decided against; §4.1.
- **Filtering by project.** The column is `workspace_id`, not `project_id`. §4.5 records the
  residual and why closing it is a different ticket.
- **A retention policy** for notifications. Leave-and-filter means hidden rows keep occupying
  storage. If that ever matters, "delete notifications older than N days" is the right tool and is
  its own ticket — it is not this one, and doing it here would smuggle a purge in under a filter.
- **Account-scoped notifications** ("your password changed"). §4.6 states the rule this schema
  establishes and what a future one would have to do; nothing is built for it.
- **Fixing the mention picker's project blindness** (§9.2). Real, pre-existing, intra-tenant, and a
  separate ticket.
- Any change to the SSE fan-out. §5 shows the live half is already closed and specifies no fix for
  it.

---

## 3. Actors & permissions

| Who | Surface | Rule |
|---|---|---|
| Any authenticated user | `GET /api/notifications`, `/unread-count`, `POST /{id}/read`, `/read-all` | Their own rows only — unchanged — **and only those whose workspace they are currently a member of** |
| Anyone holding `workspace.member.manage` | `DELETE /api/workspaces/{ws}/members/{userId}` | Removal is the event that makes the filter bite. Unchanged by this ticket; it writes nothing to `notifications`. |
| Flyway | `V20` | No human actor |

These four endpoints are **not** workspace-scoped in their path and stay that way: the feed spans
every workspace the caller is currently in. What changes is that "currently" becomes true at the
data layer instead of being assumed.

**Membership, not a permission** — the ticket's word, and it is the right one:

- A notification is a **mention**. The reader was a member when it was written, and the row is
  addressed to them personally. There is no permission that means "may read my own inbox"; adding
  one would be a permission nobody could ever be refused.
- **HD-123's default-access chain is deliberately not consulted.** That chain (project default →
  workspace default → built-in Contributor, only while the workspace is `OPEN`) decides what a
  **workspace member** may do inside a project. It cannot manufacture workspace membership, so for
  a non-member it has no input and no output. The predicate is `workspace_members`, full stop.
- A member whose role is narrowed to nothing still sees their own inbox. That is intended: a role
  change is not a revocation of access to the workspace, and HD-132's posture is that only removal
  revokes.

**Tenancy shape.** No new 403 exists anywhere in this ticket. A row in a workspace the caller has
left is **invisible**, and on the single-resource path that means **404** — the same answer as an
unknown id and as another user's id, which is the standing rule (non-existence and
non-visibility are indistinguishable).

---

## 4. Decisions

### 4.1 Leave-and-filter, not purge — **decided: leave**

The row survives removal and is hidden by the read predicate. Rejoining restores the inbox exactly
as it was, read/unread state included.

**The promise this makes:** *we stopped showing you that workspace's notifications.* Not *we deleted
them.* Say it that way in the release note, because the two are different claims and a user who is
re-added the next day will notice which one was true.

Why:

- It is the posture HD-132 already argued and shipped. Its class javadoc lists what survives a
  removal — `components.lead_id`, `saved_filters.owner_id`, and all historical attribution
  (`reporter_id`, `comments.author_id`, `issue_history.changed_by`, `attachments.uploaded_by`) —
  against a deliberately short list of what does not. **A removal revokes access; it does not edit
  data.** A purge here would make this the only place in the product where revoking access destroys
  user-visible rows, and it would do it on the one table where the destruction is invisible to
  everybody (nobody can audit an inbox they cannot see).
- Removals are reversible and sometimes wrong — a mis-clicked target, a contractor re-engaged, a
  workspace reorganisation. Hiding is reversible; deleting is not.
- The purge argument that actually has force is *"the content is denormalised into the row, so a
  filter is a promise that only holds while every reader remembers it."* That is answered
  structurally rather than by trust: the predicate lives **in the query**, the repository exposes no
  unfiltered finder, and AC-8 seals the set with a test whose failure message is the checklist. A
  reader who forgets does not compile a working query — they fail a test.
- The same argument taken seriously would demand purging every comment the departing member could
  have read, which is obviously not the product.

**Rejected: purge on removal.** It buys a stronger promise ("the rows are gone") at the cost of
irreversibility, an extra write set inside an already lock-heavy removal transaction
(`WorkspaceMemberService.remove` holds two lock sets across `writeUnassignHistory`), and an
inconsistency with every neighbouring decision. If a customer contract ever demands actual erasure,
that is a **data-retention** feature with its own spec and its own audit trail, not a side effect of
a member removal.

### 4.2 `NOT NULL`, and the backfill is a `link` parse joined to `workspaces`

**Decided: `NOT NULL`.** A nullable `workspace_id` would need a read rule for NULL, and the only
honest rule is "invisible to everyone forever" — i.e. a dead row that also permanently weakens the
column, because the first `workspace_id IS NULL OR …` written by a future contributor reopens the
whole ticket. `NOT NULL` is what makes the acceptance criterion *"no row left orphaned"* a property
of the data instead of a property of today's single producer.

**Backfill vector: parse `link`, validated by a join to `workspaces`.** Rejected alternatives:

- **Join through `comment_mentions` → `issue_comments` → `issues.workspace_id`.** *Not usable, and
  the reason is structural rather than a preference:* there is no key from a notification to the
  mention that produced it. `comment_mentions` has `(comment_id, user_id)` and no notification id;
  the only correlate is `user_id` plus two `@CreatedDate` timestamps set by two separate
  `@PrePersist` calls, so they are close but never equal. Matching on timestamp proximity is a guess
  that can attribute a row to the wrong workspace — which in this ticket is the exact defect being
  fixed. Not a fallback, not a cross-check.
- **Parse without the join.** `substring(...)::uuid` on a loosely-matched string can raise `22P02`
  and abort the deploy, and an id that parses is not an id that exists. The join gives both
  guarantees for free: a row only gets a value if that value is a real workspace, so the FK added
  three statements later cannot fail.

The regex is strict UUID shape, so the cast cannot raise:

```
'^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/'
```

**An unresolvable row is deleted, with the count raised as a notice** (§6.1). Alternatives and why
not: aborting the migration turns a zero-expected-row condition into a failed deploy whose only
remedy is an operator running that same `DELETE` by hand at a psql prompt — *a refusal must
prescribe an action its reader can perform, and "repair the row" is not performable: there is no
information anywhere from which the missing workspace could be recovered.* Keeping the row NULL is
§4.2's first paragraph.

**This delete is a backstop, not a data-loss plan**, and it does not destroy: the rows are copied
into `notifications_unresolvable_v20` first — created only when there is something to copy, so a
clean install never sees it. That was added after review pointed out that the design as written made
the question *permanently* unanswerable the instant the migration committed, which turns a missed
pre-flight into a missed fact rather than a delayed one. The copy is not a surface: nothing maps or
reads that table, its only reader is an operator with a database shell, and the docs tell them to
drop it once they have their answer.

**Unverified, and it is the one number in this document that is not from the tree:** the actual row
count and the count of unparseable links in the live development database. The Bash tool was
disabled for this session, so no query was run. This is AC-0 — run the §6.3 pre-flight before
building, and read §6.3's two-cause split before concluding anything from a non-zero answer.

### 4.3 FK to `workspaces(id) ON DELETE CASCADE`, single-column — and **not** for HD-13's reason

The project has two established FK shapes and they are not interchangeable:

```
issues_component_fk   FOREIGN KEY (component_id, workspace_id) REFERENCES components(id, workspace_id)
issues_sprint_fk      FOREIGN KEY (sprint_id,    workspace_id) REFERENCES sprints(id,    workspace_id)
issues_status_id_fkey FOREIGN KEY (status_id)                  REFERENCES statuses(id)
```

The composite pattern exists because `components` and `sprints` are **themselves children of a
workspace**, referenced from another child: the composite key is the only way to force the two
tenancy facts to agree. `V19`/HD-13 could not use it for statuses and types because the parent has
no `workspace_id` at all and one global catalog row is referenced from every tenant at once —
cardinality, not key shape.

**Neither argument applies here, and a reader must not transplant HD-13's.** The referenced table
*is* `workspaces`. `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)` **already carries tenancy
exactly and completely** — there is no second tenancy fact for it to disagree with. This is the same
shape as `workspace_members.workspace_id`, `workspace_invites.workspace_id`, `projects.workspace_id`
and every other direct child of `workspaces`. Composite here would mean referencing `(id, id)`,
which is not a thing.

**`ON DELETE CASCADE`**, matching every direct child of `workspaces` in `V1`. A notification whose
workspace is gone is unrenderable (its `link` resolves to nothing) and unreachable (the predicate
can never match). `NO ACTION` would make a future workspace purge fail on a table nobody would think
to look at — the failure mode `V19`'s header spends four paragraphs on. Nothing in the application
deletes a workspace today; this is about the day something does.

Index: `idx_notifications_workspace ON notifications(workspace_id)` — for the CASCADE's RI scan and
for the per-workspace queries §1 says are currently impossible. **It is not the index the read
filter uses** (§6.5 says which is), and saying so here is the point: an index justified by the wrong
query is an index nobody dares drop.

### 4.4 The predicate lives in the query, and it is the same clause four times

```jpql
AND n.workspace.id IN (SELECT m.workspace.id FROM WorkspaceMember m WHERE m.user = :user)
```

**Uncorrelated `IN`, not correlated `EXISTS`**, for one reason that decides it: `markAllRead` is a
bulk `@Modifying` JPQL `UPDATE`, and an uncorrelated subquery in its `WHERE` is unambiguously
supported, whereas a correlated one referencing the update alias is the kind of thing that works
until a Hibernate minor. Postgres plans both as the same semi-join, so nothing is paid for the
choice. Using one shape in all four places is worth more than micro-optimising any of them.

**Filter in the query, never after the page.** `list` caps at 30 rows
(`PageRequest.of(0, 30)`). A Java-side filter applied to the fetched page would show a user with 30
hidden rows an empty bell while unhidden rows sat on page 2. The predicate must be inside the
statement that applies the limit.

**Never re-query membership in the service.** `WorkspaceAccessService` resolves membership once per
request onto the context, and these endpoints name no workspace, so there is no context to consult
and nothing to reuse. The subquery is the resolution; do not add a second one in Java and then
`IN`-list it — an empty list is a broken JPQL `IN ()`, and a caller who forgets is a caller who
leaks.

### 4.5 `markRead` is filtered too, and the ticket's judgement about it needs correcting

The ticket says *"an unfiltered `markRead` leaks nothing by itself."* **It does.**
`NotificationService.markRead` returns `NotificationResponse.of(n)` — the full DTO, `title` and
`body` included. So `POST /api/notifications/{id}/read` is a **content read** wearing a write's
clothes: a removed member holding an id from a still-open tab gets the comment excerpt back in the
200 body. That is precisely the disclosure this ticket exists to close, on the one path a reviewer
would be most likely to wave through.

**Decision: 404**, indistinguishable from an unknown id and from another user's id — which is what
the existing not-yours branch already answers, for the same reason.

**`markAllRead` is filtered too.** "Mark all read" means everything I can see, and a row I cannot see
is not part of *all*. The consequence — a rejoining member's old notifications come back **unread**
rather than silently pre-cleared — is intended and is the honest half of the leave-and-filter
promise: hiding a row must not quietly mutate it. The burst is bounded in practice by the list's
30-row cap.

**Residual, stated rather than hidden:** a **current** workspace member sees a notification from a
project they may not be able to open. Under `project_access_mode != OPEN`, a member with no
`project_members` row inherits nothing, yet `CommentService.applyMentions` builds its candidate set
from *every workspace member* (§9.2) and will notify them anyway. Closing that needs a `project_id`
column, a per-row permission resolution on an endpoint hit on every page load, and an answer for
issues that move between projects — a different ticket with a different shape. **This ticket's
claim is exactly "workspace membership", and it does not pretend to be project-level redaction.**
The column added here is deliberately not `project_id`; adding an unused one now would be inventing
the answer to a question nobody has specified.

### 4.6 `NotificationResponse` gains `workspaceId` — **yes**

The docs gate arms regardless (the list narrows, `markRead` gains a 404 case), so "avoid an API
change" buys nothing here. Given that, the field is nearly free and closes a real trap:

- **A client that recovers a domain field by parsing a URL breaks the day the URL changes.** The
  bell would have to regex `/w/([^/]+)/` out of `link` to label or group by workspace. `link`'s
  shape is built at `CommentService:177` and is a rendering detail, not a contract.
- **The second producer is where it bites.** Any notification type whose link is shaped differently
  — or has no link at all — silently yields "no workspace" to a parsing client, while the row itself
  is correctly tenanted. The field states the fact the row already knows.
- Additive and optional to consume: the SPA needs a type field and no rendering change.

### 4.7 What a second producer must do

There is **one producer today** — `CommentService.applyMentions` (`CommentService:178`), emitting
type `MENTIONED` — and that is a fact about the current census, not a property to rely on. The
properties that must survive a second one:

- **Every notification belongs to exactly one workspace.** `create` takes a `Workspace`, the column
  is `NOT NULL`, and there is no overload that omits it. A producer physically cannot forget.
- **A notification whose content is not workspace content has no home in this schema**, and that is
  the decision, not an oversight. An account-scoped notification ("your password changed", "your
  trial ends") needs either a nullable workspace with an explicit read rule for NULL, or a separate
  surface. Whoever wants one writes a spec; nobody smuggles a NULL past `NOT NULL` by relaxing the
  column.
- **A producer must not invent the workspace from a string.** Pass the entity that the resolved
  domain object already carries (`issue.getWorkspace()`), not an id re-derived from a path variable
  or a link — that is how the two can disagree.

---

## 5. The SSE half is already closed — verified, no fix specified

The ticket asks whether the live stream leaks. **It does not**, and shipping a fix for it would be
shipping a fix for a hole that is not there. The chain, each link checked in the tree:

| Link | Where | What it guarantees |
|---|---|---|
| The event is workspace-keyed | `NotificationRaised(workspaceId, recipientUserId, payload)` | The push names a workspace |
| Delivery is per-workspace | `SseRegistry.sendToUser(workspaceId, userId, …)` reads `connections.get(workspaceId)` | Only emitters registered **under that workspace** are reachable |
| Subscribing requires membership | `SseController.subscribe` → `workspaceAccess.requireMember` | A non-member cannot register one |
| Removal closes open ones | `SseEventListener.onWorkspaceMemberRemoved` → `SseRegistry.disconnectUser`, `@TransactionalEventListener(AFTER_COMMIT)` | Existing streams die on removal, after commit |
| Reconnect re-checks | `EventSource` auto-reconnect re-enters `SseController` | The retry 404s |

**Races, and why none of them is a leak.** The removal's disconnect and a notification's push are
both after-commit side effects of different transactions. If the notification commits first, the
push may reach a browser that is about to be disconnected — the recipient was a member at that
instant, so this is delivery, not leakage. If the removal commits first, the emitters are already
completed and `send` fails into `completeWithError`, which cleans up. There is no ordering in which
a non-member's *live* stream receives a payload, and no lock is needed to keep it that way.

**What is not closed is everything durable**, which is this ticket: the row, the list, the
unread count, and `markRead`'s response body.

**The bell.** `NotificationBell.tsx:55` computes its unread badge from the fetched list
(`notifications.filter(n => !n.read).length`), so it inherits the list filter for free.
`apiGetUnreadCount` (`api.ts:881`) exists and — today — has no caller in the SPA. It is still
filtered, because it is public API and "no caller" is a census.

---

## 6. Data model impact

### 6.1 The migration

`src/main/resources/db/migration/V20__notifications_workspace_scope.sql` — `V19` is the highest
applied version.

**The file itself is the specification here, not this section.** V20's header carries the whole
argument — the FK shape, the locks each statement takes, the two-language coupling with
`CommentService`'s link format, the quarantine, and the operator's pre-flight with its two-cause
split — and it changed after review. Reproducing it here would give the project two spellings of one
argument, of which only one is executed. Read the file; what follows is the statement order and why
it is the only one that works.

```
1. ALTER TABLE … ADD COLUMN workspace_id UUID          -- nullable, so it can be filled
2. UPDATE … FROM workspaces … substring(link) ::uuid   -- backfill, validated by the join
3. DO $$ … IF unresolvable > 0 THEN CREATE TABLE
        notifications_unresolvable_v20 AS SELECT … $$  -- quarantine, only if non-empty
4. DELETE FROM notifications WHERE workspace_id IS NULL -- backstop
5. ALTER COLUMN workspace_id SET NOT NULL
6. ADD CONSTRAINT … FOREIGN KEY … ON DELETE CASCADE
7. CREATE INDEX idx_notifications_workspace
```

Each ordering constraint is load-bearing: the quarantine must precede the delete or it copies
nothing; the delete must precede `SET NOT NULL` or that aborts; the FK must follow both or it fails
on a dangling value; and the index goes last so it is not maintained during the `UPDATE`.
`NOT VALID` + `VALIDATE` is deliberately **not** used — Flyway runs the script in one transaction
and step 1's `ACCESS EXCLUSIVE` is held to commit anyway, so the lock-window reduction it exists to
buy is already forfeited.

Migration-rule compliance: `UUID` not `CHAR`/ENUM, no `CREATE TYPE … AS ENUM`, no new id generation
(no new rows), `created_at` untouched, everything inside Flyway's transaction, and `V1` is **not**
edited (Flyway checksums the whole file — the correction lives in this header and in
`docs/project-state.md`).

### 6.2 Entity

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "workspace_id", nullable = false, updatable = false)
private Workspace workspace;
```

- **`updatable = false`** because a notification's tenant is immutable — a row can never legitimately
  move workspaces, and making that a mapping fact costs nothing. This is a *different* motivation
  from the `projects.issue_seq` rule (which is about columns written by native SQL behind the ORM's
  back); both land on the same annotation and neither conflicts with the other.
- `FetchType.LAZY` — no read path in this ticket dereferences the association. The predicate is
  `n.workspace.id IN (…)`, which Hibernate answers from the FK column without initialising a proxy,
  and `NotificationResponse.of` reads `n.getWorkspace().getId()`, which is likewise available on the
  proxy without a SELECT. **Do not add a `JOIN FETCH`** — there is nothing to fetch.
- No `cascade` attribute, and none may be added: `REMOVE`/`ALL` would try to delete the *workspace*
  when a notification is deleted.
- `ddl-auto=validate` covers the column's existence and type. It does **not** validate foreign keys
  or indexes, so AC-2 checks `pg_constraint` directly rather than treating a clean startup as proof.

### 6.3 Pre-flight, before the release (**AC-0, blocking**)

Run against the live development database *and* production. **Expected: 0.** A non-zero answer does
not by itself stop the release — it asks one further question, because this query tests two things
at once (that `link` *parses*, and that the workspace it names *still exists*) and those come apart:

- **the link parsed, the workspace is gone** — orphans from a workspace deleted outside the app, a
  partial restore or a dump reload; before `V20` there was no FK, so nothing noticed them. They were
  already unrenderable. Deleting them is correct; **deploy**.
- **the link did not parse** — a producer wrote a shape `V20` cannot read. Deploy is still safe
  (the rows are quarantined, below), but the count and the split go on the ticket: it is the one
  answer no environment the authors could reach was able to give.

The split query, and the reasoning behind attaching two actions to one number, are in
`V20__notifications_workspace_scope.sql`'s header and in `docs/release-checklist.md`. Either way
`V20` copies the rows into `notifications_unresolvable_v20` before deleting them — created **only**
when there is something to copy, mapped by nothing, and the operator's to drop.

```sql
SELECT count(*) AS unresolvable
  FROM notifications n
  LEFT JOIN workspaces w
    ON w.id = substring(
         n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/'
       )::uuid
 WHERE w.id IS NULL;
```

Advisory, run at the same time and paste the output into the ticket — it tells you whether the
single-producer assumption is still true in *data*, which is stronger than a grep:

```sql
SELECT type, count(*), count(*) FILTER (WHERE link IS NULL) AS no_link
  FROM notifications GROUP BY type ORDER BY 2 DESC;
```

Local: `docker start hamstrack-postgres`, then
`docker exec hamstrack-postgres psql -U hamstrack -d hamstrack -f …`.

### 6.4 Producer signature — a deliberate deviation from the ticket

The ticket says the write side is "a column plus one setter, with no call-site signature change",
because `create` already receives `UUID workspaceId` (used today only for the SSE envelope).
**Change the signature anyway:**

```java
public void create(User recipient, Workspace workspace, String type,
                   String title, String body, String link)
```

`CommentService.applyMentions` already holds the entity — it reads
`comment.getIssue().getWorkspace()` at line 167 to list mentionable members — so the change is free
at the one call site. What it buys is that the id and the entity **cannot disagree**: today the
method takes a `UUID` from the URL path while the row it inserts is derived from the issue, and
those being equal is a property of `requireIssue` two classes away. Pass the resolved entity and it
is a property of the type. `workspace.getId()` still feeds `NotificationRaised`, so the SSE wire
contract is byte-for-byte unchanged.

### 6.5 Query plans and cost

| Path | Statement | Driving index | Bound |
|---|---|---|---|
| `list` | `… WHERE n.user = ? AND n.workspace.id IN (…) ORDER BY n.createdAt DESC` + `LIMIT 30` | `idx_notifications_user (user_id, created_at DESC)` (V1) | 30 rows, each semi-joined against `workspace_members`'s `UNIQUE(workspace_id, user_id)` index |
| `countUnread` | `… WHERE n.user = ? AND n.readAt IS NULL AND n.workspace.id IN (…)` | `idx_notifications_unread (user_id) WHERE read_at IS NULL` (V1) | the user's unread count — small, and no `LIMIT` is possible on a count |
| `markRead` | `… WHERE n.id = ? AND n.user = ? AND n.workspace.id IN (…)` | PK | one row |
| `markAllRead` | bulk `UPDATE … WHERE n.user = ? AND n.readAt IS NULL AND n.workspace.id IN (…)` | `idx_notifications_unread` | the user's unread count |

The `IN` subquery is a semi-join over `workspace_members` restricted to one user — bounded by *how
many workspaces the caller belongs to*, which is single digits, and served by an existing unique
index. **No new index is needed for the filter**, and `idx_notifications_workspace` (§4.3) exists
for the CASCADE, not for these.

Neither V1 index is invalidated: the filter adds a predicate on a column that appears in neither, so
both keep driving their query and the semi-join runs on top.

---

## 7. API surface

No new endpoints, no changed paths, no new status *codes* — one new *case* and one additive field.

**`GET /api/notifications`** → `200`, array of:

```json
{ "id": "…", "workspaceId": "…", "type": "MENTIONED",
  "title": "Jane Doe mentioned you", "body": "…", "link": "/w/…/p/…?issue=18",
  "read": false, "createdAt": "2026-08-25T10:12:00Z" }
```

Only rows whose `workspaceId` is a workspace the caller is **currently** a member of.

**`GET /api/notifications/unread-count`** → `200 {"count": n}`, same filter.

**`POST /api/notifications/{id}/read`** → `200` with the object above, or **`404`** when the id is
unknown, belongs to another user, **or belongs to a workspace the caller has left**. The three are
one indistinguishable answer, on purpose.

**`POST /api/notifications/read-all`** → `204`. Marks only the notifications the caller can see.

**Docs to update** (`api-docs-sync`, then validate with swagger-cli):

- `src/main/frontend/public/openapi.yaml` — `NotificationResponse` schema gains
  `workspaceId: {type: string, format: uuid}` (required); the `/notifications` `description` changes
  from *"Across all workspaces"* to *"Across the workspaces you are currently a member of"*;
  `/notifications/{id}/read` gains the third `404` cause. Note `src/main/resources/static/openapi.yaml`
  is a **build artifact** of the `public/` copy — edit `public/`.
- `docs/api-cloud.md:2830-2843` and the matching block in `docs/api-dc.md` — same three edits.
- **Doc drift to fix while you are there:** the example body in both files shows
  `"type": "ISSUE_ASSIGNED"` with an *assignment* title. No producer emits that type; the only type
  the product produces is `MENTIONED`. Correct the example rather than leaving it describing a
  feature that does not exist.

**Release note.** One line, and it must say *hid*, not *deleted* (§4.1):

> **Notifications are now scoped to the workspace they came from.** If you are removed from a
> workspace, its notifications stop appearing in your inbox, in the unread count and in the live
> stream. They are hidden, not deleted — rejoin the workspace and they come back.

---

## 8. Frontend impact

**Minimal, and `DESIGN.md` is not engaged** — no element is added, removed or restyled.

1. `src/main/frontend/src/types.ts:712` — `Notification` gains `workspaceId: string`.
2. Nothing else is required. `NotificationBell.tsx` derives its unread badge from the fetched list
   (line 55), so it inherits the filter; `apiGetUnreadCount` has no caller today.

**Known residual, with an honest bound.** A user removed while a tab is open keeps the already-fetched
array in React state until the page reloads. That is data the browser had already legitimately
received, not a new disclosure — the server will not serve it again, `handleClick`'s `markRead` now
404s, and `navigate(n.link)` lands on a 404'd workspace. A tidier behaviour (refetch `/notifications`
when the workspace SSE stream errors, since the reconnect is exactly the removal signal) is a nice
follow-up and explicitly **not required** by this ticket's acceptance criteria.

---

## 9. DC / Cloud implications

**Nothing differs, and that is the decision, not an omission.**

- No new property, no new env var, no profile gate, no `@ConditionalOnProperty`. The schema is one
  Flyway history shared by both modes and the predicate is unconditional. A behavioural toggle here
  would mean "leak in one mode", which is not a configuration.
- The `dc-cloud-guard` checklist (properties → compose → `.env.prod.example` → README) is **n/a**.
  Record it as `n/a` in the gate file rather than leaving it unrun.
- **Why the leak matters more in Cloud, even though the defect is identical.** In DC an instance is
  usually one organisation and its workspaces sit inside one trust boundary, so a removed member is
  typically someone whose employment ended — bad, bounded, and often already covered by an offboarding
  process. In Cloud the workspaces on one instance belong to **different paying customers**, so the
  same surviving row is one customer's comment text still readable by an account they revoked. Same
  bug class, different blast radius — which is the standing reason this project treats missing
  workspace scoping as its highest-severity class.
- `docs/self-hosting.md` needs nothing: no default moves, no stored value changes derivation.

### 9.2 Findings recorded here, out of scope, for follow-up tickets

- **`CommentService.applyMentions`'s candidate set is workspace-wide, and today that discloses
  nothing — which is exactly why it is worth writing down.** The set is
  `workspaceMemberRepository.findAllByWorkspaceWithUser(...)`: every workspace member, with no
  project-access check. **This was first filed as a leak and the premise was wrong.** The
  `Permission` catalog contains **no read/view/browse constant at all**;
  `WorkspaceAccessService.resolveProject` does not refuse a non-OPEN project, it resolves it and
  `fallback` merely yields an empty `PermissionSet`. So a workspace member can already read every
  project's issues and comments regardless of `project_access_mode` — what they lack is the ability
  to **act** — and a mention hands them nothing they could not fetch directly.

  The residual is therefore a **latent coupling, not a defect**: the day a read permission enters
  the catalog, a workspace-wide mention candidate set becomes a disclosure, and the notification row
  this ticket just made workspace-scoped would carry the excerpt to someone the new permission was
  written to exclude. Whoever adds the first read permission owns narrowing this picker in the same
  change.
- **The `ISSUE_ASSIGNED` example in both API references documents a notification type nothing
  produces.** Fixed in passing as part of §7.

### 9.3 `/api/notifications*` gets no rate-limit budget — decided, not overlooked

The throttled path set is **sealed by `ThrottleCoverageTest`**, whose failure message is the
propagation checklist, so an unthrottled endpoint in this project is either a deliberate
absence or a test failure. This one is deliberate, and the reasons are these:

- **The work per request is below the cheapest thing that already has a budget.** The four
  statements in §6.5 are a PK or index lookup plus a semi-join against a unique index,
  capped at 30 rows. Saved-filter CRUD was put on the search budget because *validating* a
  filter builds the same `ResolutionContext` `/search/schema` pays for — a notification read
  builds nothing.
- **Nothing polls it.** `NotificationBell.tsx` fetches once on mount and is fed by SSE
  afterwards, so the endpoint carries no ambient load at all; the live path is the SSE
  stream, which is membership-gated at subscribe time and closed on removal.
- **A throttle is earned by the work a handler does, not by where it is mounted** (CLAUDE.md,
  learned from Insights). These paths are not mounted under anything throttled and must not
  be assumed to inherit one.

**What would flip this**, and each is findable as a diff rather than as a slow realisation:
a producer that raises notifications faster than one per user action; any endpoint here
growing a filter, a search term, a date range or a page cursor the caller supplies; a
polling client replacing the SSE feed; or an unread count that stops being a single indexed
aggregate. Any of those makes the endpoint a workload rather than a lookup, and the budget
becomes a deliberate edit to `ThrottleCoverageTest`'s path set — never an omission.

---

## 10. Acceptance criteria

Numbered so a reviewer can tick them; AC-4 and AC-5 are the ones that **fail against today's code**,
and the stated reason is that today every finder is keyed on the user alone.

0. **AC-0 — pre-flight run and recorded.** §6.3's blocking query returned 0 against the development
   database and against production; the advisory type breakdown is pasted into the ticket. Done
   **before** the migration is written.
1. **AC-1 — the column exists and is populated.** After migration, `SELECT count(*) FROM
   notifications WHERE workspace_id IS NULL` returns 0, and `information_schema.columns` shows
   `workspace_id`, `uuid`, `NOT NULL`.
2. **AC-2 — the FK exists with the right delete action.** `SELECT confdeltype FROM pg_constraint
   WHERE conname = 'notifications_workspace_id_fkey'` returns `'c'` (CASCADE) and `convalidated` is
   true. Asserted by a test, not by a manual query — `ddl-auto=validate` does not check FKs.
3. **AC-3 — the backfill attributes rows correctly.** A test seeds notifications in two workspaces
   via the real producer (post a comment mentioning a member), runs against the migrated schema, and
   asserts each row's `workspace_id` equals the workspace of the issue the comment is on — not
   merely non-null.
4. **AC-4 — a removed member's inbox for that workspace is empty in every surface. FAILS TODAY.**
   User U is a member of A and B and holds mention notifications in both. Remove U from A. Then:
   `GET /api/notifications` returns only B's rows and **no `title` or `body` string from A appears
   anywhere in the response**; `GET /api/notifications/unread-count` equals B's unread count;
   `POST /api/notifications/{aRowId}/read` returns **404** with no `title`/`body` in the body;
   `POST /api/notifications/read-all` leaves A's rows **unread** in the database. B's rows are
   untouched throughout.
5. **AC-5 — `markRead` does not hand back the content. FAILS TODAY**, and separately from AC-4
   because it is the path most likely to be waved through: assert the 404 body contains neither the
   comment excerpt nor the mentioning user's display name.
6. **AC-6 — rejoin restores, byte for byte.** Re-add U to A; A's rows reappear in `list` with the
   same ids and the **same read/unread state** they had before the removal.
7. **AC-7 — a non-member never sees another user's row at all.** A user who was never in A and holds
   no notification there gets an empty list — i.e. the filter is not merely "hide rows you used to
   own".
8. **AC-8 — the finder set is sealed.** A test reflects over `NotificationRepository`, asserts every
   query method carries the membership subquery (and that no derived, un-`@Query`'d query method
   exists), with a failure message that **is the checklist**: *"a finder on this table that does not
   join membership returns rows from workspaces the reader has left; the predicate belongs in the
   query, not in the caller — see docs/design/notification-workspace-scoping-proposal.md §4.4."*
9. **AC-9 — the filter is inside the limit.** A user with more than 30 hidden rows plus visible ones
   sees the visible ones. (Guards the "filter after the page" trap in §4.4.)
10. **AC-10 — the producer cannot omit the workspace.** `NotificationService.create` takes a
    `Workspace`; a test asserts the row's workspace equals `issue.getWorkspace()`, and the
    `NotificationRaised` SSE envelope still carries the same workspace id as before.
11. **AC-11 — SSE behaviour is unchanged and still correct.** A removed member's stream is closed
    after commit (existing `SseRegistryDisconnectTest` behaviour still passes) and a `NOTIFICATION`
    push for A never reaches a non-member of A. No new SSE code.
12. **AC-12 — cascade.** Deleting a workspace row deletes its notifications and nothing else.
13. **AC-13 — `ddl-auto=validate` passes** and `migration-reviewer` confirms entity⇄schema parity.
14. **AC-14 — docs.** `openapi.yaml` (the `public/` copy), `docs/api-dc.md`, `docs/api-cloud.md`
    carry `workspaceId`, the narrowed list description, the third `404` cause and the corrected
    `MENTIONED` example; swagger-cli validates; `docs/project-state.md` records the change.
15. **AC-15 — no `403` was introduced anywhere.** A grep-level check plus the AC-4 assertions: every
    invisible row answers 404 or is silently absent.

---

## 11. Highest-risk assumption, stated plainly

**That `link` is present and well-formed on every existing row.** Everything downstream of the
backfill rests on it: the `NOT NULL`, the FK, and the `DELETE` that fires when the parse misses.
The claim is well-founded — `CommentService:177` builds the link unconditionally and there is one
producer — but it is a claim about *data that already exists*, and unlike the code claim it **has
not been executed** in this session (the Bash tool was disabled, so no query ran; §4.2). If the
development or production table holds rows written by a path nobody remembers, the migration deletes
them silently apart from a `RAISE NOTICE` that Flyway will not surface prominently.

AC-0 is the mitigation and it is blocking, not advisory. Run it first; if it returns non-zero, the
answer is not "the migration handles it" — it is that the single-producer premise is false and this
document needs a paragraph before the code does.

**Second-highest: that `markAllRead`'s bulk `UPDATE` accepts the subquery as written.** §4.4 chose
the uncorrelated `IN` form specifically to keep this boring, but "Hibernate 7 accepts this JPQL" is
a prediction until a test runs it. If it does not, the fallback is a native query with the same
predicate — **not** dropping the predicate from that one path, which is exactly how a sealed set
develops a hole.

---

## 12. Architectural decisions (ADR)

One, because it settles a pattern rather than a mechanism and a future contributor will ask why.

**ADR-0009 — A user-addressed row that carries tenant content is tenant-scoped, and revocation hides
it rather than deleting it.**

- **Chosen:** every row in a user-addressed inbox carries a `NOT NULL workspace_id` referencing
  `workspaces(id) ON DELETE CASCADE`; every read and write path filters on **current workspace
  membership**, expressed inside the query; a removal **hides** rows and never deletes them, so
  rejoining restores the inbox with its read state intact.
- **Rejected — purge on removal.** Stronger promise ("gone"), but irreversible, adds writes to an
  already lock-heavy removal transaction, destroys user-visible rows on a *revocation* — which every
  neighbouring HD-132 decision explicitly refuses to do — and is unauditable because nobody can
  inspect an inbox they cannot see.
- **Rejected — keep filtering by user only and rely on redaction at render time.** Impossible: the
  content is denormalised into the row at delivery, so there is nothing left to redact.
- **Rejected — nullable `workspace_id`.** Needs a read rule for NULL whose only honest form is
  "invisible forever", and the nullable column is what invites the first
  `workspace_id IS NULL OR …` that reopens the defect.
- **Trade-off accepted:** hidden rows keep occupying storage (a retention policy is the tool for
  that, if it ever matters); a rejoining member can get a burst of old unread notifications; and
  this schema has no room for an account-scoped notification, which is a deliberate constraint a
  future spec must lift explicitly rather than by relaxing the column.

Drafted as `docs/adr/0009-tenant-scoped-user-inbox.md`, `Status: Proposed`.

---

## 13. Open questions

1. **Does anything unresolvable actually exist in the live data?** *Recommended default:* assume
   zero, and make AC-0 blocking so the assumption is checked rather than trusted. Unverifiable in
   this session (Bash disabled). **This is the one item that should be answered before a line is
   written.**
2. **Should `notifications` also carry `project_id` now, so a future project-level filter is a
   predicate change rather than a second backfill?** *Recommended default:* **no.** A project-level
   rule needs decisions this ticket does not make (which permission; what happens to a notification
   whose issue moved projects; whether being mentioned grants a peek), and an unused column added
   "for later" is how a schema acquires fields nobody can explain. The cost of being wrong is one
   more migration with the same `link`-parse backfill — cheap, and by then the rule will be known.
3. **Should a rejoining member's restored notifications arrive unread (as specified) or be marked
   read on the way back in?** *Recommended default:* **unread, as specified.** Hiding a row must not
   mutate it, and the 30-row cap bounds the burst. Revisit only if a real user complains, and then
   as a UX decision with its own note.
4. **Should the SPA refetch the notification list when the workspace SSE stream errors?** *Recommended
   default:* **yes eventually, no in this ticket** (§8). It is a stale-tab tidy-up, not a disclosure,
   and bundling it would put frontend work on a backend ticket's critical path.
5. **Should `list` gain a `?workspaceId=` filter now that the column exists?** *Recommended default:*
   **no.** Nothing consumes it; `workspaceId` on the response is enough for a client to group. Add it
   when a screen needs it.
