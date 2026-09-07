# ADR-0009: The user "inbox" is scoped by workspace, and revoking access hides rows rather than deleting them

Record date: 2026-08-25
Status: Accepted
Source: `docs/design/notification-workspace-scoping-proposal.md` (HD-135); the context of access
revocation — `WorkspaceMemberService` (HD-132), `SseRegistry.disconnectUser`

## Context

The `notifications` table is addressed to a **user** (`user_id`), not to a workspace, and before HD-135 it did
not carry `workspace_id` at all (`V1__init_schema.sql:435-445`). And yet `title`/`body` are a
**denormalised copy of workspace content**: the name of whoever mentioned you and up to 120 characters of the
comment body, written into the row at the moment of delivery.

As long as the only way to lose access to a workspace was deleting the account, the schema added up:
`user_id ON DELETE CASCADE` removed the rows. HD-132 added the product's first real access-revocation
event — removing a member from a workspace — and the gap became visible: the rows survive the removal,
every repository finder is keyed by the user alone, and no membership check can redact text that already
lies in the row.

The same applies to any future table of the same class: the row is addressed to a user but contains
tenant content (the activity feed, digest emails, "my mentions"). What is needed is a rule for the class, not
a patch for one table.

## Decision

**1. A row of this class carries the tenant.** `workspace_id UUID NOT NULL`, a foreign key to
`workspaces(id) ON DELETE CASCADE`. The key is **single-column** — unlike the composite
`(id, workspace_id)` keys of `components`/`sprints`: there the parent is itself a child entity
of the workspace and the composite key is needed so that two facts about the tenant agree. Here the parent is
`workspaces` itself, and `(workspace_id) -> workspaces(id)` already names the tenant precisely and completely. The
argument of ADR/migration `V19` (a composite key is impossible because of the global catalog) **does not carry
over** here: there the key was impossible, here it is unnecessary.

**2. It is read through current membership, and the predicate lives in the query.** Every read and write path
carries one and the same uncorrelated subquery
`workspace_id IN (SELECT m.workspace.id FROM WorkspaceMember m WHERE m.user = :user)` — including
the bulk `UPDATE` ("mark everything read") and the single `markRead`, which returns the full DTO with
`title`/`body` and is therefore a **read of content**, not only a write. The filter does **not** live in
the service and is **not** applied to an already selected page: the list has a `LIMIT`, and filtering after
the limit hides visible rows. The predicate consists of **two halves, and both are mandatory**: `n.user =
:user` ("whose row") and the membership subquery ("which tenant") — a query that has only the second one
hands out other people's inboxes in shared workspaces, that is, exactly the same denormalised excerpts, only
addressed to someone else. The set of finders is sealed by a test whose failure text is a checklist; the seal
stands on the **table**, not on the interface: the repository's only supertype is the `Repository` marker, the
walk goes over `getMethods()` (and not `getDeclaredMethods()`, which does not see inheritance), and not one
file outside the repository mentions `Notification`/`notifications` as a query target.

**3. Revoking access hides, it does not delete.** The row survives the removal of the member; on the return to
the workspace the inbox is restored in full, including the read/unread state. The promise is
phrased exactly so: *"we stopped showing this"*, not *"we deleted this"*.

**4. A row that cannot be resolved during the backfill is removed from the table, the column stays `NOT NULL`.**
A row whose tenant cannot be recovered can never be shown under the new rule and there is nothing
to repair it from. But "cannot be repaired" is not the same as "can never be seen again": before deletion
such rows are copied into `notifications_unresolvable_v20`, and that table is created **only if
there are more than zero of them**, otherwise every clean installation would forever get an empty artefact of a
condition it never had. The copy is not a surface: no entity describes it, nobody reads it,
it can be reached only from the DB shell — that is, from where the whole `notifications` table
is visible anyway. The operator is told to drop it as soon as they have got their answer.

A non-zero pre-flight counter has **two causes with opposite actions**: the link parsed
but the workspace has since been deleted (before `V20` there was no foreign key, such orphans accumulated
silently — this is a routine cause, the deploy continues), or the link did not parse at all (which means there
is a producer the migration does not know about — the deploy is safe too, but the number must be reported into a ticket).
The first edition of the instruction named only the second cause and demanded "revisiting the design
decision" — an action unavailable to a DC operator.

## Consequences

+ The leak of content to a departed member is closed on all surfaces at once — the list, the counter
  of unread, the single mark, the bulk mark.
+ There is now a foundation for "mute a workspace", per-workspace settings and digests: previously the tenant
  would have had to be pulled out by parsing the URL in `link`.
+ Removing a member by mistake is reversible: put them back — the inbox is there.
+ The rule covers a class of tables, not one; a new producer physically cannot forget the
  tenant, because the creation method takes a `Workspace` entity and the column is `NOT NULL`.
− Hidden rows keep occupying space. Real retention is a separate feature with its own spec;
  it must not be made a side effect of removing a member.
− A returning member gets a batch of old **unread** notifications: hiding a row and at the
  same time silently mutating it is a contradiction.
− This schema has no room for an account-level notification ("password changed"). That is a deliberate
  limitation: lifting it needs a separate spec, not a weakening of `NOT NULL`.

## Alternatives

- **Delete the rows when a member is removed (purge).** The promise is stronger ("they are gone"), but it is irreversible,
  it adds writes to a removal transaction already loaded with locks, it destroys user-visible data
  **on access revocation** — exactly what all the neighbouring HD-132 decisions explicitly refuse
  to do (`components.lead_id`, `saved_filters.owner_id`, the whole historical attribution survive the removal),
  and it is unauditable: an inbox you cannot look at, you cannot check either.
  Rejected.
- **Keep the key on the user alone and "redact" the content at render time.** Impossible:
  the content is denormalised into the row at the moment of delivery, there is nothing to redact. Rejected.
- **A nullable `workspace_id`.** It requires a read rule for NULL whose honest form is
  "invisible always", that is, a dead row plus a nullable column inviting the very first
  `workspace_id IS NULL OR ...` to reopen the defect. Rejected.
- **A composite key `(id, workspace_id)` after the pattern of `components`/`sprints`.** Not applicable: the parent is
  `workspaces` itself, a second fact about the tenant does not exist. Rejected as meaningless, not as
  impossible.
- **Filter by permission rather than by membership.** The HD-123 permissions (including the default-access chain:
  project → workspace → the built-in Contributor while `OPEN`) describe what a workspace member can
  do inside a project, and cannot make a non-member a member. There is no permission to ask about here:
  "read your own inbox" is a permission that can be refused to nobody. Rejected.
