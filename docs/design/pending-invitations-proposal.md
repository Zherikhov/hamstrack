# Pending invitations — a workspace cannot see, or withdraw, the access it has offered (HD-158)

**Status:** proposal / design review. **Date:** 2026-08-27. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness), priority **High**.
**Carved out of:** HD-8 (closed 2026-08-20). **Related:** HD-133 (`UNIQUE (workspace_id, lower(email))
WHERE accepted_at IS NULL` — filed, not built), HD-190 (invitation budget — **built**, V21, ADR-0015),
HD-132 (member removal already withdraws invites as a side effect).
**Touches:** `WorkspaceController`, `WorkspaceService`, `WorkspaceInviteRepository`, a new response
DTO, `ProductMetrics`, `WorkspacePeoplePage.tsx`, `api.ts`, `types.ts`, `openapi.yaml`,
`docs/api-cloud.md`, `docs/api-dc.md`. **No migration. No new configuration property.**

---

## 0. The premise, corrected before anything is built on it

**Confirmed.** There is no endpoint that lists a workspace's invitations.
`WorkspaceController` (`src/main/java/com/hamstrack/workspace/controller/WorkspaceController.java`)
carries `POST /{id}/invites` and `POST /accept-invite` and nothing else; `InviteController`
(`/api/invites`) is the *invitee's* surface — list-mine / accept / decline.
`WorkspacePeoplePage.tsx:166-179` renders the dashed "Pending invitations · coming soon" block whose
own text says why. `Permission.WORKSPACE_MEMBER_MANAGE` already documents itself as *"Invite people,
change a member's workspace role, **revoke pending invites**"* — the catalog entry was written for
this endpoint before it existed, so no permission is added by this ticket.

**Correction 1 — "read-only without the permission" and "must not become a way to enumerate
addresses" cannot both be honoured, and the second one wins.** The pending list is *nothing but*
addresses; there is no residue left after removing them that is worth rendering read-only. A member
without `workspace.member.manage` therefore gets **no section at all**, and the endpoint answers
**403** for them. §3 argues that this does not contradict the ticket's 404 posture: the 404 rule is
about **tenancy** (a non-member, and a workspace that does not exist, are indistinguishable), and 403
is what this project answers a **proven member** who lacks a permission — `ctx.permissions().require(…)`
does exactly that on every other gated call in the codebase.

**Correction 2 — "outstanding" is the wrong set to return, by a small and load-bearing margin.**
The endpoint should return **every unaccepted invitation row of the workspace**, expired ones
included and labelled, not just the live ones. An expired row is not a standing grant of access — but
it is a row that HD-133's uniqueness will refuse a re-invite over, that member removal deletes, and
that nothing in this product ever sweeps. A list narrower than the table is a list that cannot
explain the next refusal, which is this project's recurring failure mode rather than a hypothetical.
The general form, which survives the next predicate somebody adds: **the list must be the complete
set of rows that can still block, grant, or be cleaned up — anything hidden from it is something no
admin can clear and no refusal can point at.** §4.1.

**Correction 3 — the ticket's "decide between 404 and idempotent 204" frames one question where
there are two, and it says so itself.** An *accepted* invitation and an *already-withdrawn* one are
different states with different remedies, and the design gives them different answers (409 and 404).
§4.4.

**Correction 4 — the ticket calls the "withdraw" behaviour new, but a *third* deletion path already
exists.** `declineInvite` deletes, `deleteUnacceptedByWorkspaceAndEmail` (member removal, HD-132)
deletes, and `ON DELETE CASCADE` from `workspaces` deletes. What this ticket adds is not the first
deletion — it is the first deletion **performed by an actor other than the invitee or the invitee's
own offboarding**, and therefore the first that can race a concurrent `acceptInvite`. §6.3 handles
that race; it is the one genuinely new hazard in the ticket.

---

## 1. Problem & goal

A workspace can hand out access and then lose sight of it. An invitation is a **standing grant** —
seven days of anyone-who-holds-this-link-and-that-mailbox — and today the only person who can see it
is the person it was sent to. A workspace owner cannot answer "who has been offered access here and
has not taken it up", cannot audit a departing admin's outstanding offers, and cannot undo a mistyped
or regretted invitation except by waiting out its expiry or by removing a member who does not exist
yet. Withdrawal exists in this product only as a *side effect* of removing somebody who has already
joined (HD-132) — the case where it matters least.

**Success:** Workspace settings → People shows every invitation this workspace has issued and not had
accepted, with who sent it, when, which role it carries and when it lapses; any holder of
`workspace.member.manage` can withdraw one in a click; a withdrawn invitation cannot be accepted by
link or by id; and none of this is visible to anyone who could not already administer membership.

## 2. Scope

**In scope**

- `GET /api/workspaces/{workspaceId}/invites` — the workspace's unaccepted invitations.
- `DELETE /api/workspaces/{workspaceId}/invites/{inviteId}` — withdraw one.
- The People page section that replaces the "coming soon" stub, plus its invalidation wiring.
- A row lock on the invite row shared with the accept paths, because this ticket creates the first
  cross-actor write race on `workspace_invites` (§6.3).
- One new counter (`hamstrack.invites.revoked`) and one new seal test on
  `MailSendEventRepository` (§5.4).
- `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md`.

**Out of scope — named so nothing here reads as covering them**

- **Resending an invitation.** The ticket excludes it and it should stay excluded: it is a mail path,
  so it needs its own `EmailType` policy or an explicit exemption in `MailThrottleCoverageTest`, and
  the honest version of it is "invite the same address again", which the existing endpoint already
  does under the existing ceilings.
- **Notifying the invitee that their invitation was withdrawn.** §6.6 — recommended *against*, not
  merely deferred.
- **A per-workspace cap on outstanding invitations** (HD-190 §6.4 / Q1). This ticket removes the
  objection that killed it and does **not** build it; §12.2 says why, and what it must count if it is
  ever built.
- **HD-133's uniqueness constraint.** §12.1 states what this ticket requires of it and in which order
  the two should land.
- **A sweep for expired invitations.** None exists; `workspace_invites` grows forever. Out of scope,
  filed as a follow-up in §12.3 — the list is what makes its absence visible, which is a feature.
- **Pagination.** §8.1.
- Soft-deleting invitations / an invitation audit trail. §7.

**Non-goals.** No change to `inviteMember`'s check order, its ceilings, its wording, or the
`mail_send_events` schema. No change to how the invitee accepts or declines, beyond the lock.

## 3. Actors & permissions

| Actor | `GET …/invites` | `DELETE …/invites/{id}` |
|---|---|---|
| Not authenticated | 401 | 401 |
| Authenticated, not a member of this workspace (or workspace does not exist) | **404** | **404** |
| Member without `workspace.member.manage` | **403**, naming the permission | **403**, naming the permission |
| Member with `workspace.member.manage` | 200 | 204 / 409 / 404 (§4.4) |

Both handlers resolve tenancy exactly as every other workspace read does —
`workspaceAccess.requireMember(actor, workspaceId)`, which answers 404 for a non-member and for an
unknown workspace indistinguishably — and then authorize with the single primitive,
`ctx.permissions().require(Permission.WORKSPACE_MEMBER_MANAGE)`. Nothing is re-queried from
`workspace_members`; the answer is already on the context.

**Why 403 and not 404 for the member who lacks the permission, when the ticket says 404.** The
ticket's sentence is about non-members and is right about them. Extending it to permission failures
would contradict the rule it is quoting: *non-existence and non-membership are both 404, 403 only for
a proven member*. A member already knows this workspace exists — a 404 would conceal nothing and
would make one endpoint disagree with the ~29 others that answer 403 through `PermissionSet.require`.

**Why not an empty list for that member (the alternative the ticket floats).** An empty array is a
statement we know to be false, rendered as "No invitations are waiting for a reply" on a screen
belonging to a workspace that has ten. This project already refuses the same shape elsewhere — a
preview that succeeds while describing a refusal "teaches a client to ignore it". A 403 the client
never provokes (the query is `enabled: canManage`) costs the same and lies about nothing.

**Why not a count without addresses.** It discloses ("three people have been offered access here")
without granting any ability to act on the disclosure, needs a second response shape and a second
gate, and creates a DTO whose *purpose* is to be incomplete — the kind of shape the next contributor
adds a field to. Nobody asked for the count; refusing it is free.

**Who may withdraw whose invitation.** Anyone holding `workspace.member.manage` in the workspace,
including invitations they did not send. The grant ceiling (§11.2 of the roles spec) is deliberately
**not** applied: withdrawing is subtraction. A ceiling exists to stop somebody handing out authority
they do not hold, and revoking an Admin-role invitation grants the revoker nothing. Applying it here
would produce a refusal ("you may not withdraw this invitation because it carries a permission you
lack") that protects nobody and leaves an unrevokable standing grant in the workspace — which is the
state this ticket exists to end. Note the invariant that makes this safe: an invitation can never
carry the built-in Owner role (`OwnerIsNotGrantableException`), so no invitation can outrank every
holder of the permission.

## 4. Behaviour & rules

### 4.1 The list

`GET /api/workspaces/{workspaceId}/invites` returns **every `workspace_invites` row of this
workspace with `accepted_at IS NULL`**, newest first (`created_at DESC`).

- Expired rows are **included**, carrying `status: "EXPIRED"`. Correction 2 above is the argument;
  the short version is that an invisible row is one no admin can clear and no future refusal can
  point at, and expiry does not delete anything today.
- Rows whose address belongs to somebody who is *already a member* are included too, for the same
  reason and by the same rule. (They are rare — `inviteMember` refuses an existing member with 409 —
  but they occur when the invitee joined by another route, and they are exactly what member removal
  silently deletes.) The list does not special-case them; the roster above it already shows that
  person is in.
- Accepted rows are **excluded**. They are history, not a grant: the membership row is the live fact,
  and the person appears in the roster. Including them would make the list grow without bound and
  would put a "withdraw" control next to something withdrawal cannot affect.
- The role is rendered through `workspaceAccess.resolveRoleOrDegrade(…,
  RoleScopeViolationSource.WORKSPACE_INVITES)`, exactly as `listPendingInvites` and `listMembers`
  already do. A corrupt or foreign `role_id` **degrades the row** to `role: null` **and**
  `roleId: null` — both, because emitting the id hands back the withheld name by proxy — rather than
  failing the request. One bad row must not empty an admin screen.
- One statement, with `JOIN FETCH i.role` and `JOIN FETCH i.invitedBy`: both are rendered on every
  row and both are `LAZY`, so omitting either makes this N+1.
- `invitedByName` renders the inviter's display name even if that account has since left the
  workspace or been deactivated. Historical attribution stays, consistently with every other surface
  in the product ("what they wrote stays").

### 4.2 The withdrawal — happy path

`DELETE /api/workspaces/{workspaceId}/invites/{inviteId}` → **204**, and the row is **hard-deleted**.

Deletion rather than a `revoked_at` tombstone, and this is the one data-model choice in the ticket:

1. It is what "an invitation goes away" already means here — `declineInvite` deletes, HD-132's
   member-removal deletes. A fourth verb with a fifth lifecycle state would make
   `workspace_invites` mean different things depending on which path last touched it.
2. It requires **no migration**, which means this ticket cannot collide with HD-133's or HD-188's.
3. A tombstone would have to be excluded from HD-133's partial unique index
   (`WHERE accepted_at IS NULL` would have to become `AND revoked_at IS NULL`), silently coupling two
   tickets that are otherwise independent — and getting that wrong makes a withdrawn invitation
   permanently block re-inviting the person, which is precisely the mistake case this ticket exists
   to fix.
4. There is no audit-log subsystem in this product for a tombstone to serve. The forensic question
   *"did we mail this person?"* is answered by `mail_send_events`, which is deliberately independent
   of this table and is unaffected by the delete (§5).

**What the deletion is sufficient for, by construction and with no extra code:** the emailed token
link resolves through `findByTokenHash`, and accept-by-id through `findById`; both `orElseThrow` a
`WorkspaceNotFoundException` (404). So "the withdrawn invite can no longer be accepted" — the
ticket's acceptance criterion — needs nothing beyond the delete itself, and the invitee's own
`GET /api/invites` stops listing it on the next fetch.

### 4.3 Tenancy of the withdrawal

The invite is resolved with a **two-key finder** —
`findByIdAndWorkspaceId(inviteId, workspaceId)` returning `Optional` — never a bare `findById`
followed by comparing `invite.getWorkspace().getId()`. `WorkspaceInviteRepository` extends
`JpaRepository`, so `findById` compiles and is the shape this project's top bug class takes; the
finder must make the workspace part of the question rather than of a follow-up `if`. A miss is a 404
whether the id is fabricated, belongs to another tenant, or was withdrawn a second ago.

### 4.4 The refusals, and which state gets which

| State of `{inviteId}` | Answer | Why |
|---|---|---|
| Unaccepted, in this workspace (live **or** expired) | **204** | Deleted. Withdrawing an expired one is harmless cleanup, and the list offers the control on every row it shows, so it must work on every row it shows. |
| Not in this workspace / never existed / **already withdrawn** | **404** | §4.4a |
| In this workspace, **already accepted** | **409** `INVITE_ALREADY_ACCEPTED` | §4.4b |

**4.4a — already withdrawn is 404, not an idempotent 204.** Because withdrawal deletes, this state is
*physically identical* to "never existed" and to "belongs to another tenant"; answering anything but
404 would require inventing a tombstone whose only purpose is to make a second DELETE feel nicer.
The house style already settled the same question one endpoint away: *"a second DELETE for an
already-removed member is a clean 404, not a 500"*.

The project rule the ticket invokes — *a refusal may only prescribe an action its reader can
perform* — is satisfied, and it is worth being precise about why, because the rule is about
**prescriptions**, not about status codes. A 404 here prescribes nothing and asserts nothing false:
there is no such invitation. The reader's actual goal ("this invitation must not be acceptable") is
already true. What must not happen is the *client* presenting that 404 as a failure — so:
**the SPA treats 404 from the revoke call as success**, refetches the list, and shows no error. The
API states the truth about the resource; the client states the truth about the intent. (It must not
extend that to the 409 — see below — which is why the two states get different codes.)

**4.4b — an accepted invitation is a 409, and it is the one refusal here that names a remedy.**
It is reachable only as a race: the invitee accepts between the list rendering and the click. The row
exists, it is in this workspace, and it is not withdrawable — but unlike every other refusal on this
endpoint, its reader *can* do something about it, because "this person now has access" is exactly the
problem withdrawal was aimed at. Detail, in the shape this project uses:

> *That invitation was accepted — <name> is now a member of this workspace. Withdrawing it would
> change nothing. Remove them from People if that was not intended.*

The remedy is reachable by its recipient: removing a member requires `workspace.member.manage`, the
same permission they just proved. (The removal path has guards of its own — the grant ceiling,
last-Owner, stranded projects — which produce their own actionable messages; this 409 promises a
door, not that nothing lies behind it. An invitation can never have carried Owner, so the common
case is clean.) Answering 404 here instead — pretending the accepted row is not there, as
`listPendingInvites` does for the invitee — would turn a real, actionable state into a phantom and
leave the admin with no direction at all.

## 5. The HD-190 question: what a withdrawal may and may not free

This is the section that must be got right, because the mechanism it interacts with was designed
against a bug of exactly this shape.

### 5.1 The answer: a withdrawal frees **nothing** measured over time

**Withdrawing an invitation resets neither the per-(sender, inbox) cooldown nor the global per-inbox
daily cap, and touches no `mail_send_events` row.**

`V21__mail_send_events.sql` exists because the first design derived the cooldown from
`workspace_invites`, and review killed it on the observation that three paths delete that row — one
of them pressed by the *victim*, since `declineInvite` does a `DELETE`. The header goes further and
names the future hazard directly: *"the workspace_id CASCADE (no delete endpoint exists today —
correctness that depends on the continued ABSENCE of an endpoint breaks silently in a future
ticket)"*. **This ticket is that future ticket.** If withdrawal refunded a ceiling, then
`invite → revoke → invite` would defeat HD-190 completely, using two legitimate calls and no
exploit, at a cost of one extra HTTP request per message.

Two further reasons, either of which is independently sufficient:

- **It would be a cross-tenant write triggered by a tenant action.** The daily cap counts one
  *inbox* across the whole instance. A refund performed in workspace A would hand a send slot back
  that belongs to the recipient's global budget — i.e. workspace A's admin would decide how much mail
  workspace B may send to a stranger.
- **The state is a record of mail, and mail cannot be unsent.** The row means *this instance decided
  to send a message to this inbox*, which stays true no matter what happens to the access the message
  offered. The message is already in the mailbox.

The rule to carry forward, phrased as a property rather than a list of paths, because a list goes
stale one path before it does:

> **A revocation may free only a resource whose count it actually reduces — outstanding rows, a
> uniqueness slot, a stock cap. It may never free a resource measured over time: sends, cooldowns,
> daily ceilings. Deleting the record of an offer does not delete the record of a delivery.**

That rule sorts every future control without anybody re-deriving this argument, and §5.4 seals it
with a test rather than with prose.

### 5.2 Is the mistyped-address cost acceptable? Yes — and it is mostly not the cost it looks like

The scenario: an admin types `bob@exmaple.com`, sends, notices the typo, withdraws, and re-invites
`bob@example.com`. With `app.invites.recipient-cooldown-minutes` at 60, is the correct person
blocked for an hour?

**No — and the reason is that the cooldown is keyed on the inbox, not on the request.** The key is
`MailAddresses.throttleKey(address)`; a typo is a *different inbox*, its count is zero, and the
corrected invitation goes out immediately. The correction case that the ticket worries about does not
arise.

The cooldown bites only when the re-invited address folds to the **same** key as the withdrawn one —
`Bob@example.com` after `bob@example.com`, or a plus-tag variant of a folding provider. In that case
the earlier message *reached the same human*, so a wait is exactly what the cooldown is for: the
remedy is "ask them to look again", not "send it twice". That is a wait an honest sender did not
earn only in the sense that any cooldown is; it expires, it is their own history, and
`RecipientMailThrottle` already argues that this is the cheap direction of a fail-safe fold.

One property of the existing design deserves an explicit note, because a reviewer will ask and
because it must not be "improved":

> After a withdrawal, the cooldown's optional addendum — *"That invitation is still valid — ask them
> to check their inbox, including spam."* — is **automatically suppressed**, because the supplier at
> `WorkspaceService.inviteMember` evaluated
> `existsByWorkspaceAndEmailAndAcceptedAtIsNullAndExpiresAtAfter(…)`, and the row is gone.

**Superseded by HD-133, and the addendum went with it.** That ticket's duplicate pre-check sits one
step *above* the cooldown and matches a strict superset of the condition the addendum tested (same
workspace, same address folded, unaccepted, expiry irrelevant), so no request can reach the cooldown
while such a row still stands. The sentence became unreachable — and an unreachable sentence is a
claim a future reader would trust — so both it and its finder were deleted;
`existsByWorkspaceAndEmailAndAcceptedAtIsNullAndExpiresAtAfter` no longer exists, and
`findPendingByWorkspaceAndFoldedEmail` answers the 409 in its place. The `Supplier` parameter on
`InviteThrottle` survives deliberately: **if the duplicate refusal is ever narrowed**, an addendum
becomes reachable again, and the paragraph below is why it would then still have to be checked
against the row before being printed.

HD-190 built that check for the *remove-a-member-then-re-invite* workflow. Withdrawal is a third
workflow with the same shape and it inherits the correct behaviour with no code change: the 429 still
fires (the ceiling is intact) but it no longer sends the admin looking for an email that would not
work. **Do not add a withdrawal-specific sentence there** — a refusal that says "you withdrew this
yourself" would be one more bit about state the caller can already read from the list, and the
supplier would then have to distinguish "withdrawn here" from "expired" from "never existed",
recreating exactly the row-state coupling ADR-0015 removed.

### 5.3 Is there anything a withdrawal *should* free? Yes, and it is stock, not flow

Two things, both counts of rows that the withdrawal genuinely reduces:

1. **HD-133's uniqueness slot.** Once `UNIQUE (workspace_id, lower(email)) WHERE accepted_at IS NULL`
   exists, withdrawing frees the address for a fresh invitation in that workspace — automatically,
   because the row is gone and the index has nothing to collide with. This is the *point* of
   withdrawal and is why hard delete beats a tombstone (§4.2).
2. **The per-workspace outstanding cap, if it is ever built.** It counts live rows; a withdrawal
   reduces them; its refusal ("withdraw some") becomes performable for the first time (§12.2).

Both are stock. Neither is a ceiling on mail. The §5.1 rule already covers them, which is the test of
whether the rule was worth writing.

### 5.4 Seal it, because the existing seal does not cover this

`MailSendEventRepositorySealTest` asserts that the repository returns no entities, extends no
`JpaRepository`, answers only aggregates, and never filters on the submitted address. **None of those
would fail a refund method.** A `@Modifying int deleteByRecipientKeyAndSenderUserId(…)` returns an
aggregate (`int`), returns no entity, and filters on `recipient_key` — every existing assertion stays
green while the ceilings become resettable on demand.

Add one assertion to that test:

> **`noDeleteMayBeKeyedOnAnythingButAge`** — every `@Modifying` query on `MailSendEventRepository`
> whose statement is a `DELETE` must have a `WHERE` clause mentioning `createdAt` and nothing else.
> Only the retention sweep may remove a row, and it removes rows for being *old*, never for being
> *about* somebody. Failure message: *a delete keyed on a recipient, a sender or a workspace is a
> refund; the ceilings then reset on demand and HD-190 is defeated by `invite → revoke → invite`.*

This is the cheapest place to make §5.1 structural instead of remembered.

## 6. Edge cases & failure modes

### 6.1 Empty and last-of-kind
No invitations → `200 []`, and the section renders a one-line empty state (only for callers who can
see it). There is no "last invitation" invariant — a workspace with zero outstanding invitations is
the normal state.

### 6.2 Expired, and the row nobody sweeps
An expired invitation is listed with `status: "EXPIRED"` and is withdrawable (204). Nothing deletes
expired invitations today, so a long-lived workspace accumulates them; the list is the first surface
that makes this visible, which is a reason to ship it rather than an objection. §12.3.

### 6.3 The accept/withdraw race — the one new hazard, and it needs a lock

Before this ticket, `workspace_invites` had one writer per row at a time in practice: the invitee
(accept or decline) or a member-removal that presupposes the invitee is already a member. Withdrawal
introduces a **second actor writing the same row concurrently**.

Without a lock the interleaving is not merely a bad message, it is a 500 and a corrupt outcome:
`acceptInvite` reads the row, then sets `acceptedAt` and inserts a `workspace_members` row; if the
withdrawal commits in between, the flush issues an `UPDATE` affecting zero rows. `WorkspaceInvite`
extends `CreatedOnlyEntity` and has **no `@Version`**, so what surfaces is Hibernate's unexpected
row-count `StaleStateException` — a 500 for the invitee, after the transaction has already decided to
make them a member.

**Requirement:** both the withdrawal and every accept path load the invite **`FOR UPDATE`**
(`@Lock(PESSIMISTIC_WRITE)`), and each transaction calls `LockTimeout.applyToCurrentTransaction()`
**before** taking it — bound, then lock, the standing rule. Three load sites move to locking finders:
`acceptInvite(User, String rawToken)` (by token hash), `acceptInvite(User, UUID inviteId)`, and the
new `revokeInvite`. The loser of the race then reads a definite state and answers definitely:

- accept lost → the row is gone → the existing `WorkspaceNotFoundException` → **404**, unchanged
  behaviour and no membership is created;
- withdraw lost → the row has `accepted_at` → **409** per §4.4b.

Two notes for the builder: plain `FOR UPDATE` is correct here — the *"use `FOR NO KEY UPDATE`, not
`FOR UPDATE`"* rule applies to `workspaces`, whose row every FK child insert in the tenant touches,
and **no table has a foreign key to `workspace_invites`**. And this is a single-row lock on a row
identified by primary key or by a unique token hash; it contends with nothing and needs no ordering
argument against the membership locks, because the accept transaction takes it first and the
withdrawal takes nothing else.

`declineInvite` is left alone deliberately: it is the invitee acting on their own row, it cannot race
their own accept, and widening this ticket into it buys nothing.

### 6.4 Double-click / double-submit on withdraw
First call 204, second 404, and the client renders success for both (§4.4a). No idempotency key, no
tombstone.

### 6.5 A withdrawal during a member removal
HD-132 deletes all unaccepted invites for the removed member's address in that workspace. If a
withdrawal targets one of those rows concurrently, one of the two deletes wins and the other finds
nothing — 404, rendered as success. Nothing is inconsistent afterwards: the desired end state
(no live invitation) is reached either way.

### 6.6 Should the invitee be told? No
A withdrawal sends **no mail**. It would be a new `EmailType`, which `MailThrottleCoverageTest`
would rightly fail until it was given a recipient-keyed policy or a written exemption — and giving it
one is not a formality: a "your invitation was withdrawn" message *doubles the number of messages one
invitation can put in a stranger's mailbox*, on a path with no cooldown of its own, three days after
HD-190 bounded exactly that. The invitee's own view (`GET /api/invites`) simply stops listing it,
which is the correct amount of noise for an offer that was retracted.

### 6.7 Concurrency of the list
The list is a plain read with no optimistic locking and no `computedAt` token: it describes a
population, not a row being written, and every action taken from it re-resolves its target under a
lock and answers 404/409 if the world moved. A stale row in a browser produces a correct refusal, not
a wrong write.

### 6.8 Cross-tenant
Both endpoints resolve the workspace first. The list's query filters on `workspace_id` in the
statement (never in Java, never by post-filtering a global fetch); the withdrawal's finder takes the
workspace id as a second key (§4.3). An invite id from workspace B, presented under workspace A by a
member of A, is a 404 — the id is not an existence oracle.

## 7. Data model impact

**None. No migration, no new column, no entity change.**

`workspace_invites` already carries everything the list renders: `email`, `role_id`, `invited_by`,
`created_at`, `expires_at`, `accepted_at`. Withdrawal is a `DELETE` of an existing row. This is the
strongest argument for the hard-delete design (§4.2) and it means HD-158 cannot conflict with
HD-133's migration or with HD-188's Flyway squash.

Repository additions (`WorkspaceInviteRepository`), all workspace-scoped in the statement:

- `List<WorkspaceInvite> findUnacceptedForWorkspace(UUID workspaceId)` — `JOIN FETCH i.role
  JOIN FETCH i.invitedBy`, `WHERE i.workspace.id = :workspaceId AND i.acceptedAt IS NULL`,
  `ORDER BY i.createdAt DESC`.
- `Optional<WorkspaceInvite> findByIdAndWorkspaceIdForUpdate(UUID id, UUID workspaceId)` —
  `@Lock(PESSIMISTIC_WRITE)`.
- Locking variants of the two accept lookups (§6.3).

## 8. API surface

`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` must follow (`api-docs-sync`); the two
endpoints are identical in both deployment modes.

### 8.1 `GET /api/workspaces/{workspaceId}/invites`

**200** — a JSON array, newest first. No pagination and no envelope, consistent with
`GET /{id}/members`, which is unbounded for the same population and has never needed one; if the
per-workspace cap of §12.2 is ever built it bounds this list as a side effect.

```jsonc
[
  {
    "id": "0198…",
    "email": "name@company.com",
    "roleId": "0191…",          // null when the row's role could not be resolved
    "role": "MEMBER",           // null in the same case — both are withheld together
    "invitedById": "0190…",
    "invitedByName": "Vlad Z.",
    "createdAt": "2026-08-25T09:14:02Z",
    "expiresAt": "2026-09-01T09:14:02Z",
    "status": "PENDING"         // or "EXPIRED"
  }
]
```

`status` is server-computed even though a client could derive it from `expiresAt`: expiry is decided
by the server clock (`WorkspaceInvite.isExpired()`), and a browser with a skewed clock must not
disagree with the endpoint that will accept or refuse the accept.

**401** unauthenticated · **403** member without `workspace.member.manage` · **404** non-member or
unknown workspace.

New DTO `WorkspaceInviteResponse` (`workspace/dto`). It is **not** a reuse of `PendingInviteResponse`
— that record is the invitee's view of *their* invitations across all workspaces (it carries
`workspaceName`, no address, and no inviter id). Different audience, different disclosure, different
fields; sharing one record would put a workspace's addresses one field-addition away from the
onboarding screen.

### 8.2 `DELETE /api/workspaces/{workspaceId}/invites/{inviteId}`

**204** withdrawn (no body) · **401** · **403** · **404** unknown/foreign/already withdrawn ·
**409** `INVITE_ALREADY_ACCEPTED` with the §4.4b detail.

`errorType: "INVITE_ALREADY_ACCEPTED"` follows the existing convention (`STRANDED_PROJECTS`,
`ROLE_LIMIT_REACHED`): only 409s a client must branch on carry one, and this one it must — 404 is
success for this client and 409 is not.

### 8.3 Rate limiting

**Neither endpoint joins the sealed throttled-path set** (`ThrottleCoverageTest` §"exactly four
patterns, two per budget"), and that is an answer, not an omission. That test's own guidance asks two
questions of a new surface — *is it a path that needs a budget?* and *does it send mail to an address
the caller chose?* The list is one indexed query over one workspace's rows; the withdrawal is one
primary-key delete; neither sends mail. Both answers are no, so nothing changes in either seal.

## 9. Frontend impact

`src/main/frontend/src/pages/settings/WorkspacePeoplePage.tsx` — replace the dashed stub
(lines 166-179) with a real section; `api.ts` and `types.ts` gain the two callers and the type.
`DESIGN.md` tokens only; no new colour, no hardcoded hex.

- **Mounting.** The section is a *conditionally mounted* control per the `usePermissions` rule: not
  mounted while `isLoading`, mounted only when `can('workspace.member.manage')`. It can pop in, it
  can never flash in and vanish. The query is `enabled: canManage`, so a member without the
  permission never fires a request and never sees an error banner — the 403 exists for the API's
  sake, not the UI's.
- **Query key** `['workspace-invites', wsId]`. Invalidate it after: a successful invite
  (`InviteRow.onInvited`), a successful withdrawal, and a **member removal** — HD-132 deletes that
  member's pending invites, so `RemoveMemberDialog`'s `onRemoved` must refresh this list or it will
  show rows the server has already deleted. Do this in the page rather than by widening
  `useRoleInvalidation`, which is about roles.
- **Row.** Address (plain, `<bdi>`-wrapped), a `RoleLabel` for the invited role, "invited by X ·
  <relative time>", an expiry line, an `EXPIRED` `Chip` where applicable, and a ghost **Withdraw**
  button. A row whose role degraded to null shows the placeholder the roster already uses — never a
  guess.
- **Confirmation.** A lightweight inline two-step confirm on the row, not a modal. It carries one
  sentence the admin cannot otherwise know: *"You can invite this address again later, though a
  repeat invitation to the same address may have to wait — invitations to one address are rate
  limited."* That is HD-190's cooldown told truthfully and without naming a number the server owns.
- **Errors.** `classifyConflict(err).detail` rendered verbatim on the row, exactly as the role-change
  and invite paths do. **404 is not an error here** — the mutation's error handler checks for it
  first, invalidates, and shows nothing.
- **Empty state.** *"No invitations are waiting for a reply."*
- Nothing here is capability-gated (`board`/`releases`/`estimation` are project capabilities and this
  is a workspace screen), and nothing is config-driven rendering.

## 10. Observability

Add `hamstrack.invites.revoked` beside `sent` / `accepted` / `declined` in `ProductMetrics`
(`metrics.inviteRevoked()`), incremented on a successful 204 only. It is the missing term in the
invitation lifecycle: without it, an invitation that is withdrawn is indistinguishable from one that
is ignored, and the acceptance-ratio signal that HD-190 leans on cannot tell a workspace cleaning up
from a workspace being ignored. No new alert rule is proposed; the counter exists so an operator
investigating one has the term available.

No new log line. The withdrawal writes no mail and needs no domain-only breadcrumb; the row it
deleted is described entirely by ids the operator already has.

## 11. DC / Cloud implications

**No profile gating, no new property, no new environment variable, no compose change.** Both
endpoints behave identically in `dc` and `cloud`; the gate is one permission that exists in both, and
the feature adds no storage, mail, auth or billing surface. The wiring checklist
(`application.properties` → `.env.prod.example` → `docs/self-hosting.md` → README) is therefore
**empty for this ticket** — deliberately, and stated so `dc-cloud-guard` can confirm rather than
hunt.

One Cloud-specific *note* with no code consequence: the list is a tenant's own submitted addresses,
returned only to that tenant's membership administrators, which is the same disclosure class as the
roster's `email` field that every member can already read.

## 12. Interactions with the tickets around it

### 12.1 HD-133 — and yes, there is an order

**HD-158 changes nothing about HD-190's ordering warning.** That warning says a duplicate pre-check
must sit **above** the half of the throttle that records — `inviteThrottle.requireRecipientCeilings`
as HD-133 shipped it — because it writes its `mail_send_events` row inside `inviteMember`'s
transaction and any later rollback unwrites it while the refusal was already observed: a free probe
of another tenant's ceilings. (The sender-volume half is spent *above* that pre-check, being an
in-memory counter no rollback returns; that is why `InviteThrottle` is two methods.) Withdrawal is a
*different transaction on a
different endpoint* and never runs inside `inviteMember`; it neither weakens nor strengthens that
rule. It stands exactly as written, in all three of its places.

**HD-158 does place two requirements on HD-133:**

1. **The uniqueness predicate and the list predicate must agree on what an invitation is.** If the
   index blocks on any `accepted_at IS NULL` row, then an *expired* row blocks a re-invite — and the
   list must show expired rows, or the 409 refers to something the admin cannot see or clear. §4.1
   already returns every unaccepted row, so this holds whichever predicate HD-133 chooses. Do not
   narrow the list later without re-reading this paragraph.
2. **HD-133's 409 may finally prescribe an action.** Once this ships, *"there is already an
   invitation for this address — withdraw it on Workspace settings → People, then send a new one"* is
   performable by the person who receives it (the same `workspace.member.manage`). Before this ticket
   it was not, which is the same objection that killed the cap.

**Order: HD-158 first, HD-133 second.** Neither blocks the other for *correctness* — HD-133 can ship
alone and be correct while being unhelpful — but HD-133's refusal is only actionable after this, and
this ticket carries no migration, so landing it first costs nothing and removes a conflict surface
from HD-133's migration work.

### 12.2 The deferred cap (HD-190 §6.4 / Q1) — still no, and now for two reasons instead of three

HD-190 recommended against a per-workspace cap on outstanding invitations for three reasons; the
owner agreed. This ticket kills **reason 3** (its refusal was unperformable). **Reasons 1 and 2
survive intact:** the cap does nothing against the named attack — one invitation in each of many
attacker-created workspaces never approaches a per-workspace number — and it is redundant against the
attack it does address, which `app.invites.max-per-sender-per-day` already bounds at 100/day, far
below any cap anybody would choose. A control that is inert against one shape and redundant against
the other does not become worth building because its error message got better.

**Recommendation: do not build it here, and do not build it in this release.** It remains its own
ticket, now *unblocked* but still not *justified*. Building it inside HD-158 would also mean this
ticket ships a refusal on a *different* endpoint (`POST …/invites`), a new configuration property, and
a full env-var wiring chain — scope creep on a launch gate whose own surface needs no configuration
at all.

If the owner overrules that, HD-190 §6.4 already specifies the design (`app.invites.max-outstanding-
per-workspace`, default 500, **409** not 429, deliberately **outside** `app.rate-limit.enabled`
because that switch turns off brute-force protection and not unrelated stock caps). HD-158 adds one
requirement to it: **the cap must count exactly the set this list makes visible as live** (`accepted_at
IS NULL AND expires_at > now()`), so a refusal saying "500 invitations are awaiting a reply" and the
screen the admin then opens can never disagree.

### 12.3 Follow-ups this ticket deliberately does not do

- A retention sweep for expired invitations (`workspace_invites` grows forever; nothing deletes an
  expired row). This list is what makes that visible.
- Resending an invitation (needs its own mail policy or a written exemption).
- The per-workspace outstanding cap (§12.2).

## 13. Acceptance criteria

Backend:

1. An owner of workspace W gets `200` from `GET /api/workspaces/W/invites` listing **every** row of
   `workspace_invites` for W with `accepted_at IS NULL`, newest first, each carrying address, role,
   inviter name, `createdAt`, `expiresAt` and `status`.
2. An expired invitation appears with `status: "EXPIRED"`; an accepted one does not appear at all.
3. An invitation whose `role_id` is corrupt or belongs to another workspace renders with
   `role: null` **and** `roleId: null`, and the rest of the list is unaffected.
4. The list runs a bounded number of statements regardless of row count (no N+1 on role or inviter).
5. `DELETE …/invites/{id}` → `204`; the row is gone; the emailed token link then answers 404; a
   `POST /api/invites/{id}/accept` for it answers 404; the invitee's `GET /api/invites` no longer
   lists it.
6. A second `DELETE` of the same id → `404`.
7. `DELETE` of an **accepted** invitation → `409` with `errorType: "INVITE_ALREADY_ACCEPTED"` and a
   detail naming the member and the People screen.
8. A member of workspace W **without** `workspace.member.manage` gets `403` from both endpoints,
   with the permission named in the detail, and can still read the roster.
9. A member of a **different** workspace gets `404` from both endpoints; an invite id belonging to
   workspace B, requested under workspace A by a member of A, gets `404` from the `DELETE`.
10. **The headline test.** With `app.invites.recipient-cooldown-minutes` in force: invite
    `victim@example.com`, withdraw the invitation, invite the same address again → still **429**, and
    `mail_send_events` still holds the original row. The same for the daily cap: withdrawing N
    invitations does not restore N slots.
11. The cooldown refusal after a withdrawal does **not** include the *"that invitation is still
    valid"* addendum (the row is gone), while still refusing.
12. `MailSendEventRepositorySealTest` gains `noDeleteMayBeKeyedOnAnythingButAge` and it passes.
13. A concurrent accept and withdrawal of the same invitation produce exactly one of: the invitee
    joins and the withdrawal answers 409, or the invitation is withdrawn and the accept answers 404.
    Neither produces a 500, and no `workspace_members` row is created for a withdrawn invitation.

Frontend:

14. The "coming soon" stub is gone. An admin sees the pending list on Workspace settings → People,
    can withdraw a row, and the row disappears without a full reload.
15. A member without the permission sees the roster and **no invitations section at all** — no
    heading, no count, no addresses, and no failed request in the network log.
16. A withdrawal that races another admin's withdrawal (404) is rendered as success; a withdrawal of
    an accepted invitation (409) renders the server's sentence on the row.
17. Removing a member refreshes the invitations list (their pending invitations vanish from it).

Docs:

18. Both endpoints, all status codes and the DTO are in `openapi.yaml` (validates) and in both
    `docs/api-cloud.md` and `docs/api-dc.md`.

## 14. Open questions — with the recommended default

**Q1. Does the list include expired invitations?**
*Recommendation: yes, labelled `EXPIRED`.* §4.1 / Correction 2. The alternative (live rows only) is
narrower than the table and cannot explain HD-133's future refusal, and nothing sweeps expired rows
today. Overrule this only together with a sweep and with HD-133's predicate.

**Q2. 404 or idempotent 204 for an already-withdrawn invitation?**
*Recommendation: 404, with the client treating it as success.* §4.4a. The state is physically
identical to "never existed", the house style already answers 404 for a repeat member removal, and
making the *client* absorb the race keeps the API honest about the resource.

**Q3. Does an accepted invitation get the same 404?**
*Recommendation: no — 409 `INVITE_ALREADY_ACCEPTED`.* §4.4b. It is the only state on this endpoint
where the reader can act, and the action it names needs exactly the permission they hold.

**Q4. What does a member without `workspace.member.manage` see?**
*Recommendation: 403 from the endpoint, and no section rendered.* §3. Not an empty list (a falsehood
we render), not a count (disclosure with no matching ability), and not 404 (which would contradict
the 403 every other gated endpoint answers a proven member).

**Q5. Should withdrawal notify the invitee by email?**
*Recommendation: no.* §6.6 — it doubles the messages one invitation can deliver to a stranger, on a
path with no cooldown of its own.

**Q6. Build the per-workspace outstanding cap now that its refusal is performable?**
*Recommendation: no, and not in 0.18.0.* §12.2 — two of HD-190's three objections are untouched. Keep
it filed; if it is built, it must count exactly the live set this list shows.

**Q7. Hard delete or `revoked_at` tombstone?**
*Recommendation: hard delete.* §4.2 — consistent with the three existing deletion paths, needs no
migration, and keeps HD-133's partial index free of a second predicate. Revisit only if an
invitation audit trail is ever wanted, at which point it is a table, not a column.

## 15. Architectural decisions (ADR)

**None.** The two candidates were considered and both fail the bar:

- *"A revocation never refunds a recipient-keyed mail ceiling."* This is a **corollary of ADR-0015**,
  not a new fork. That ADR already decided that the throttle's state lives outside the rows it
  describes precisely so deletions of those rows cannot reset it, and its migration header names a
  future delete endpoint as the hazard. Writing a second ADR would restate an accepted one. What this
  ticket owes it instead is the *generalised phrasing* (§5.1 — stock may be freed, flow may not) and
  a **test** (§5.4), which is stronger than a document either way.
- *"Withdrawal is a hard delete, not a tombstone."* Consistency with three existing deletion paths in
  the same table, not a new pattern. A future contributor asking "why is there no revoked invitation
  history?" is answered by §4.2 and by the absence of any audit-log subsystem to hold it.

## 16. The highest-risk assumption, stated plainly

**That nothing else in this product derives a limit, a cooldown or an eligibility from the presence
of a `workspace_invites` row.** §5 is built on it. I verified the paths that read this table today —
the throttle's *addendum* (a wording decision, not a ceiling), role-usage counting, the invitee's own
list, and member removal — and none of them is a ceiling. But this is a **negative** claim about the
whole codebase, and it is exactly the claim HD-190 found to be false the first time somebody made it
about the cooldown. The mitigation is §5.4: after this ticket the property is enforced from the other
end — the only way to remove a `mail_send_events` row is for being old — so a future control that
tries to derive a ceiling from an invitation row has to add a delete this test refuses.

The second-highest: **that the accept/withdraw race is the only new concurrency surface.** If a
future path mutates `workspace_invites` outside the three load sites §6.3 converts to locking
finders, it inherits the 500, not the 409.
