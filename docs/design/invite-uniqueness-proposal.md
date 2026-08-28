# Duplicate pending invitations — one standing offer per address per workspace (HD-133)

**Status:** proposal / design review. **Date:** 2026-08-27. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness), priority **High**.
**Migration:** **V22** (`V22__invite_uniqueness.sql`) — one data cleanup and one partial unique index.
**Related:** HD-158 (pending-invitation list + withdrawal — **shipped**, `docs/design/pending-invitations-proposal.md`),
HD-190 (invitation budget — **shipped**, V21, ADR-0015), HD-132 (member removal withdraws that member's
invites), HD-120 (fold once at the boundary, compare exactly ever after).
**Touches:** `V22__invite_uniqueness.sql`, `WorkspaceInvite` (javadoc only), `WorkspaceInviteRepository`,
`WorkspaceService.inviteMember`, a new exception, `InviteThrottleBehaviourTest`,
`WorkspacePeoplePage.tsx` (one line), `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`.
**No new configuration property. No new environment variable. No profile gating.**

---

## 0. The premise, corrected before anything is built on it

The ticket's core claim is **confirmed**: `workspace_invites` (`V1__init_schema.sql:127-137`) carries
`UNIQUE` on `token_hash` and nothing else, and `WorkspaceService.inviteMember` refuses only an invitee
who is already a member. Duplicate pending invitations accumulate normally, each with its own TTL and
its own role. Four corrections follow, and the first two change what gets built.

**Correction 1 — the ticket's second option is not implementable, so the "does an expired invitation
block?" question is already settled by PostgreSQL.** The ticket asks whether the predicate should be
narrowed so that expired rows do not block. It cannot be: a partial index predicate must be
`IMMUTABLE`, `now()` is `STABLE`, and `CREATE UNIQUE INDEX … WHERE accepted_at IS NULL AND expires_at
> now()` is refused by the server. A predicate over a *fixed* instant is worse than useless (it stops
being true the next day). So the only enforceable predicate is `WHERE accepted_at IS NULL`, **expired
rows do occupy the slot**, and the design question collapses into "what must be true so that this is
acceptable?" — answered in §4.4: the refusal has to name the withdrawal, the withdrawal has to exist,
and the blocking row has to be on screen. HD-158 shipped all three yesterday. Anyone who wants
expired rows *not* to block is asking for a sweep, not for a different index (§10.1).

**Correction 2 — this ticket kills a sentence HD-190 shipped and a test that pins it, and that is a
gain rather than a regression.** The cooldown's optional addendum (*"That invitation is still valid —
ask them to check their inbox, including spam."*) fires only when a live, unaccepted invitation to
*this exact address* exists in *this workspace*. The duplicate refusal sits above the throttle and its
matching set is a strict superset of that (same workspace, `lower(email)`, unaccepted, expiry
irrelevant). **The addendum therefore becomes unreachable through the API** — every request that could
produce it is refused one step earlier, by a 409 that says the same thing better and names a remedy
the reader can perform. `InviteThrottleBehaviourTest.theCooldownOnlyClaimsTheEarlierInviteIsWaitingWhileItActuallyIs`
(line 288) asserts the addendum *is* emitted for exactly that call sequence and **will fail**. §5.3
says what to do with it. This is the kind of consequence that is only visible from the ordering rule,
so it is stated up front rather than left to the test run.

**Correction 3 — "the accept path already matches case-insensitively" is not right, and the
conclusion survives anyway.** `acceptInvite` compares addresses with `equals`, deliberately and at
length (HD-120): an extra match there lets the wrong person accept. What *is* case-insensitive is the
invitee's own list (`findByEmailIgnoreCase…`) and HD-132's removal delete, both `lower()` on both
sides. And new rows cannot differ in case anyway — `inviteMember` folds with `Locale.ROOT` before the
insert. So `lower(email)` in the index is not needed to stop `Bob@` and `bob@` both standing *today*;
it is needed because (a) rows written before that fold existed may still be mixed-case, and (b) the
index must not silently stop enforcing anything if the boundary fold is ever changed or moved. Keep
`lower(email)`, for the durable reason rather than the stated one — and see §4.2 for the constraint
this places on the pre-check.

> **Correction 3a (review round 1) — reason (a) above is not real, and reason (b) carries the
> decision alone.** `git log -S toLowerCase` places the invite-side fold in the **initial commit**, so
> no release has ever written a mixed-case row through `inviteMember`; production and dev both measured
> zero (2026-08-27, over SSM: zero unaccepted mixed-case invites, zero unaccepted invites at all, zero
> mixed-case `users` rows). Reason (b) is a *property* and survives; reason (a) was a claim about
> history that was never true. **This matters beyond the wording**, because the migration inherited it:
> the de-duplication keeps the newest unaccepted row per `(workspace, lower(email))` regardless of that
> row's own casing, so a mixed-case row that happened to be newest would survive as *the* standing
> offer — and `acceptInvite` compares with `equals` against a folded account address, so **nobody could
> ever redeem it**, while from V22 onward it would occupy the slot and get the corrective re-invitation
> refused with `DUPLICATE_INVITE` until an admin withdrew it.
>
> **The fix is a DELETE, not a fold.** `UPDATE … SET email = lower(email)` looks gentler and is worse:
> that row was *mailed to the mixed-case spelling*, and `Bob@x` and `bob@x` are two different mailboxes
> on any RFC-compliant server, so folding hands a standing offer of workspace access to whoever owns
> the lowercase address — the exact hazard HD-120's exact-match accept exists to prevent. **Changing
> the address changes who the offer goes to**, and a migration may not do that silently. Deleting an
> already-unredeemable row costs nothing and frees a live address. V22 therefore runs
> `DELETE FROM workspace_invites WHERE accepted_at IS NULL AND email <> lower(email)` **before** the
> de-duplication (order is load-bearing) and leaves accepted rows alone, since a mixed-case accepted
> row is a historical fact rather than a live offer. Verified on seeded data: with the step, the
> redeemable `good@example.com` survives and the mixed-case-only addresses are freed; replaying the
> de-duplication *without* it leaves `GOOD@example.com` standing and deletes the good row.

**Correction 4 — a duplicate pre-check does not close the duplicate-insert race, and nothing except
the index can.** Two concurrent requests both pass the pre-check and both insert. The index is what
makes one of them fail; the pre-check only decides whether the *common* case gets a sentence or a
stack trace. Stated as the property this spec is built on: **the index is the invariant, the
pre-check is the sentence.** §6.3 works out when that race is actually reachable and what it costs.

---

## 1. Problem & goal

A workspace can offer the same person access several times over, and the offers do not know about
each other. A re-send, a double-clicked submit, two admins inviting the same candidate, a "did you
get it?" follow-up — each writes another `workspace_invites` row with its own seven-day TTL and its
own role. Three consequences, in descending order of severity: **the role becomes a coin flip** (two
live offers at MEMBER and ADMIN, and the access the person ends up with depends on which link they
click); **revocation is incomplete by construction** (withdrawing "the invitation" is meaningless
when there are four, and HD-158's list shows four rows an admin has to clear one at a time); and
**the invitee's onboarding screen lists the same workspace several times**, which reads as a bug. It
is also the enabling condition for the re-entry hole HD-132 had to close: several pending invites
mean that accepting one and later being removed leaves the others live.

**Success:** at most one unaccepted invitation exists per `(workspace, address)` at any instant,
enforced by the database rather than by a check somebody can forget; a second invitation to a pending
address is refused with a defined status that names a remedy its reader can perform; existing
duplicates are resolved by the migration without dropping the accepted history; and case-only
variants of an address collide.

## 2. Scope

**In scope**

- `V22__invite_uniqueness.sql` — dedupe existing rows, then
  `CREATE UNIQUE INDEX … ON workspace_invites (workspace_id, lower(email)) WHERE accepted_at IS NULL`.
- A duplicate pre-check in `WorkspaceService.inviteMember`, **above**
  `inviteThrottle.requireRecipientCeilings` and **below** `inviteThrottle.requireSenderVolume`
  (§4.1 — the throttle became two methods for this reason).
- A new `409 DUPLICATE_INVITE` refusal with two wordings (live / lapsed).
- A translation of the constraint violation to the same 409, as the backstop for the race the
  pre-check cannot close.
- Removing the now-unreachable cooldown addendum and its repository finder, and repairing the test
  that pins it (§5.3).
- One line of frontend (invalidate the pending list when the refusal names it), `openapi.yaml`,
  `docs/api-cloud.md`, `docs/api-dc.md`.

**Out of scope — named so nothing here reads as covering them**

- **A sweep for expired invitations.** Still absent, still growing, and this ticket makes its absence
  slightly more visible (an expired row now blocks). §10.1 files it; §4.4 explains why the product is
  correct without it.
- **Resending / refreshing an invitation as its own verb.** §4.5 recommends against it and says what
  it would have to carry.
- **Cross-workspace uniqueness.** Uniqueness is per workspace by definition; the cross-workspace
  dimension belongs to HD-190's cooldown and is already bounded there.
- **A per-workspace cap on outstanding invitations** (HD-190 §6.4). Unchanged: still filed, still not
  justified (HD-158 §12.2).
- Any change to how an invitation is accepted, declined, listed or withdrawn.

**Non-goals.** No change to `inviteThrottle`'s ceilings, keys, wording or ordering. No change to
`mail_send_events`. No new permission — `workspace.member.manage` already covers everything here.

## 3. Actors & permissions

Only one endpoint changes: `POST /api/workspaces/{workspaceId}/invites`.

| Actor | Answer |
|---|---|
| Not authenticated | 401 |
| Authenticated, not a member (or workspace unknown) | **404**, indistinguishably |
| Member without `workspace.member.manage` | **403**, naming the permission |
| Member with `workspace.member.manage`, address free | 201 |
| Member with `workspace.member.manage`, address already invited here | **409 `DUPLICATE_INVITE`** |

Nothing is added to the permission catalog and nothing is re-queried from `workspace_members` — the
check order below runs entirely on facts already resolved onto `WorkspaceContext`.

**The refusal is only ever seen by a proven member of this workspace**, which is what makes it safe to
say anything at all about a row: the address was submitted by the caller, and the blocking row is one
they can already read in full through `GET /api/workspaces/{id}/invites`. There is no cross-tenant
bit here — uniqueness is scoped to one workspace, so the 409 reports nothing about traffic elsewhere.
(That is the sharp contrast with HD-190's daily cap, whose refusal necessarily carries one bit about
workspaces the caller cannot see. Do not carry that argument across; this refusal has no such cost.)

## 4. Behaviour & rules

### 4.1 Check order — and the one line that must not move

```
workspace resolved (404 non-member / unknown)
  → workspace.member.manage (403)
  → role assignable / OWNER-not-grantable / grant ceiling (422, 403)
  → already a member (409)
  → inviteThrottle.requireSenderVolume (429)                  ← in memory, no rollback returns it
  → *** duplicate pending invitation (409 DUPLICATE_INVITE)  ← NEW, HERE ***
  → inviteThrottle.requireRecipientCeilings (429)             ← records mail_send_events
  → insert workspace_invites, AfterCommit → send mail
```

**Above `inviteThrottle.requireRecipientCeilings`, never below it.** HD-190 left this warning in three
places (`InviteThrottle` javadoc, `V21__mail_send_events.sql` header lines 57-61, and a long comment at
`WorkspaceService.inviteMember` naming this ticket by number). The reason: the recipient half of the
throttle **records** its `mail_send_events` row inside this transaction, before the invite insert. Any
refusal that rolls the transaction back afterwards unwrites that row while the caller has already
observed the refusal — a free way to probe another tenant's mail ceilings. Invite a victim's address,
catch the 409, pay nothing. The ceiling was observed; the count was not spent.

**And below `inviteThrottle.requireSenderVolume` — which is why the throttle is two methods now, and
this is round 2's correction.** The first build of this ticket moved the whole of
`InviteThrottle.require` below the duplicate check, and `require` spent the **sender** budget first.
The consequence was invisible in the diff and real in production: a repeat POST to a pending address
stopped costing the caller anything at all, on an endpoint with no `PrincipalThrottleInterceptor`, so
an account holding `workspace.member.manage` could loop unbounded cheap 409s. The rule the two halves
obey is not the same rule: the ordering warning above is about **transactional** state a rollback can
unwrite, and the sender budget is an in-memory counter (ADR-0015) that no rollback returns. So it is
spent **above** every refusal and the recorded event stays **below** them all. Recombining the halves
forces one of the two to lose, and it is the generalisation of the bug rather than the bug: under
"every refusal goes above the throttle", each new refusal this path acquires makes one more response
free.

**Below the already-a-member check**, because a person can be both a member and the addressee of a
leftover unaccepted row (HD-158 §4.1 lists exactly those), and "they are already in this workspace" is
the more useful answer to the more important question. Only one of the two 409s is emitted; the member
one wins.

### 4.2 The pre-check must use the same expression as the index

```java
@Query("SELECT i FROM WorkspaceInvite i WHERE i.workspace.id = :workspaceId "
     + "AND lower(i.email) = lower(:email) AND i.acceptedAt IS NULL")
Optional<WorkspaceInvite> findPendingByWorkspaceAndFoldedEmail(UUID workspaceId, String email);
```

Not `equals` on the already-folded Java string, and **not** `MailAddresses.throttleKey`. The reason is
narrow and load-bearing: Java's `toLowerCase(Locale.ROOT)` and PostgreSQL's `lower()` are two different
functions, and the DTO only guarantees an **ASCII local part** — the *domain* may be internationalised.
Where the two folds disagree on a character, a pre-check written in Java says "free" and the index says
"taken": a constraint violation at flush, a rollback, and precisely the free ceiling probe §4.1 exists
to prevent. Asking the database the same question the index answers removes that class entirely, and it
is index-backed for free (the partial unique index *is* the access path for this predicate).

`Optional` rather than `List` is safe only *because* the index exists — before V22 this query could
match several rows. Flyway runs before the application serves traffic, so by the time this code can be
called the invariant holds.

### 4.3 The decision: refuse with 409, do not replace the row

The ticket calls replacing "what a user clicking invite a second time actually wants". **Recommendation:
refuse.** Four reasons, and the last one is the one that decides it.

1. **Replacing silently revokes.** The outstanding link dies the moment the row is replaced (new token,
   or the old row deleted). That is a withdrawal performed by an endpoint named "invite", with no
   confirmation, no metric (`hamstrack.invites.revoked` would not fire), and no log line — three things
   HD-158 deliberately attached to withdrawal three days earlier because *withdrawing a standing grant
   is a security-relevant act*. A path that does it as a side effect undoes that.
2. **Replacing renews a TTL, and the TTL is a control.** Seven days is the bound on how long
   anyone-who-holds-this-link-and-that-mailbox stands. A verb that refreshes it without being named
   "refresh" means an invitation can be kept alive indefinitely by a caller who never has to think
   about it.
3. **Replacing changes the role on an offer already in somebody's inbox.** Deterministic (last wins),
   but it re-creates a softer version of the ambiguity this ticket exists to delete — the role the
   person gets still depends on timing, only now on the timing of the *click* against the *re-invite*.
4. **The difference between the two only appears where the case for replacing is weakest.** A
   duplicate refusal sits above the throttle, so it pre-empts the cooldown entirely: the honest
   "double-click", "did you get it?" and "two admins" cases — all inside
   `app.invites.recipient-cooldown-minutes`, default 60 — would under *replace* have got a **429**
   from HD-190 anyway, telling the caller to wait an hour, because replacing sends mail. Replacing
   only differs from refusing **outside** the cooldown window, which is the "I have changed my mind
   about the role" and "please resend" cases. Those are honest needs, and both are already served:
   withdraw, then invite. Two clicks, on a screen that shipped yesterday, with the revocation
   recorded.

So: replacing buys nothing in the cases that motivate it, and costs a silent revocation, a renewable
TTL and a lost audit line in the cases where it would apply. **409.**

### 4.4 Expired rows block, and that is the same rule this codebase already applies four times

Forced by Correction 1, but it would be the recommendation regardless, because it is the shape already
chosen for every other "name is taken" in the product:

> `labels_workspace_name_uk`, `components_project_name_uk`, `versions_project_name_uk`,
> `sprints_project_name_uk` — *"archived rows keep their slot on purpose … reusing the name is a 409
> that nudges toward unarchive/rename."*

An expired invitation is the same object: a row that is no longer live, that still holds the slot, and
whose removal is a deliberate act the owner performs. The general form: **a dead row keeps its slot
until somebody clears it, and the refusal's job is to say who clears it and where.**

For that to be honourable, the refusal must name an action its reader can perform, and — since HD-158
— it can:

- **withdrawal exists** (`DELETE /api/workspaces/{ws}/invites/{id}`, 204, hard delete);
- **it works on expired rows** (HD-158 §4.4: *"the list offers the control on every row it shows, so it
  must work on every row it shows"*);
- **the blocking row is visible** — `findUnacceptedForWorkspace` filters on `accepted_at IS NULL` and
  nothing else, which is exactly the index predicate. The list predicate and the uniqueness predicate
  agree **by construction**, which is HD-158 §12.1's first requirement on this ticket, discharged;
- **the same permission** the caller just proved (`workspace.member.manage`) is what withdrawal
  requires.

Do not narrow either predicate without re-reading both. The property, which survives the next filter
somebody is tempted to add: **the set of rows that can block an invitation and the set of rows an
administrator can see must be the same set.**

### 4.5 The wording

One `errorType`, two details, chosen from the blocking row's `expiresAt`:

- **Live** — *"There is already an invitation to `<address>` waiting in this workspace. Ask them to
  check their inbox, including spam. To change the role or send a fresh link, withdraw it under
  Workspace settings → People and invite again."*
- **Lapsed** — *"An earlier invitation to `<address>` in this workspace has lapsed and is still on
  file. Withdraw it under Workspace settings → People, then invite again."*

The address is echoed because the caller submitted it. The **role of the blocking row is deliberately
not named**: it can be `null` on a degraded row (`resolveRoleOrDegrade`), the list beside the form
already renders it, and a refusal should not grow a field whose absent case needs its own sentence.

`errorType: "DUPLICATE_INVITE"`, following the existing convention (`STRANDED_PROJECTS`,
`ROLE_LIMIT_REACHED`, `INVITE_ALREADY_ACCEPTED`): only 409s a client must branch on carry one, and
this one it must (§8 — the SPA refreshes the pending list on it).

## 5. Consequences for the neighbouring mechanisms

### 5.1 The two normalisations are different on purpose, and neither is a bug

This is the question the next reader will ask, so it is answered here rather than in a comment.

| | key | folds | why |
|---|---|---|---|
| **Uniqueness (this ticket)** | `lower(email)` | case only | the **offer**: which address was invited, as written |
| **Mail ceilings (HD-190)** | `MailAddresses.throttleKey` | case, `+tag`, Gmail dots, punycode | the **delivery**: which inbox a human opens |

`victim@x.com` and `victim+a@x.com` are **two index keys and one throttle key**, and that is correct in
both directions:

- A **uniqueness constraint is about the offer**, and the offer is redeemed by exact-match against a
  `users.email` (`acceptInvite`, HD-120). `victim+a@x.com` is a different account and a different
  redeemable invitation; folding it onto `victim@x.com` would let one workspace's invitation to one
  person block a genuinely different person's, and would make the constraint a claim the accept path
  does not honour.
- A **mail ceiling is about the delivery**, and both spellings ring one doorbell. Over-folding is the
  fail-safe direction for a ceiling (an extra match refuses sooner) and the *hole* for an offer (an
  extra match refuses someone who was entitled).

The single rule that generates both, and that should be quoted rather than re-derived:
**fold as far as the harm points. A control that decides who may be reached folds onto the inbox; a
control that decides which offer stands folds onto the address.** HD-120 is the third member of the
same family (redemption compares exactly, because there an extra match lets the wrong person in), and
`MailAddresses.throttleKey`'s javadoc already forbids carrying its argument across.

**The consequence to accept knowingly:** an admin can hold pending invitations to `victim@x.com` and
`victim+a@x.com` in one workspace. Bounded by HD-190 — the second one costs a cooldown wait and a slot
of the recipient's daily cap, because the ceiling *does* fold them together. So the uniqueness gap is
covered by the throttle and the throttle's over-fold is covered by the uniqueness constraint's
exactness. That is the pairing, and it is why neither should be changed to match the other.

### 5.2 Withdrawal frees the slot, and nothing else

HD-158 §5.3 already wrote this ticket's half of the contract: *"a revocation may free only a resource
whose count it actually reduces — outstanding rows, a uniqueness slot, a stock cap. It may never free
a resource measured over time."* The uniqueness slot is stock, it is freed by the delete with no code,
and `MailSendEventRepositorySealTest.noDeleteMayBeKeyedOnAnythingButAge` keeps the other half honest.
Nothing in this ticket touches `mail_send_events`, and **nothing in this ticket may**.

Three existing paths free the slot: withdrawal (HD-158), decline (the invitee's own), and member
removal (HD-132). All three delete. The fourth, acceptance, frees it by setting `accepted_at` — the row
leaves the partial index and becomes history, which is exactly why the index is partial.

### 5.3 The cooldown addendum becomes unreachable — remove it

Established in Correction 2. The addendum's condition (same workspace, exact address, unaccepted,
unexpired) is strictly implied by the 409's condition (same workspace, `lower()` address, unaccepted),
so after this ticket no request can produce it.

**Recommendation: remove it.** Concretely — pass no addendum at the `inviteMember` call site (the
`requireAndRecord` overload without a supplier already exists, `RecipientMailThrottle:266`), delete
`WorkspaceInviteRepository.existsByWorkspaceAndEmailAndAcceptedAtIsNullAndExpiresAtAfter` (this is its
only caller), and **keep the `Supplier` parameter on `InviteThrottle`/`RecipientMailThrottle`** — HD-202
will have paths of its own and the mechanism is sound.

The alternative — keep the supplier, document it as unreachable — was considered and rejected: a
sentence that cannot be emitted is a claim a future reader will trust, and this project's own house
rule is that a refusal must not describe state its reader cannot act on. Leave a one-line note at the
call site saying **why** the addendum was removed and under what change it would want reviving (any
narrowing of the duplicate refusal), phrased as the condition rather than as this ticket's number.

**Test repair.** `InviteThrottleBehaviourTest.theCooldownOnlyClaimsTheEarlierInviteIsWaitingWhileItActuallyIs`
(line 288) asserts *both* halves — that the sentence appears for a same-workspace re-invite and
disappears after a decline. The first half is now a `409`. Replace the method with one that asserts the
strictly better property: **a second invitation to a pending address in the same workspace is refused
before the ceilings are consulted, with a 409 that names the withdrawal, and no `mail_send_events` row
is written.** That last clause is the §4.1 ordering rule, pinned. The sibling tests
`withdrawingTheInvitationDoesNotLiftTheCooldownAndTheEventRowKeepsItsTimestamp` (withdraws before
re-inviting, so the row is gone and the pre-check passes) and
`withdrawingEveryInvitationDoesNotRestoreTheRecipientsDailySlots` (a different workspace per send) are
unaffected — verified by reading their sequences, not assumed.

## 6. Edge cases & failure modes

### 6.1 The four ways the slot is freed
Withdraw, decline, member removal, acceptance (§5.2). After any of the first three, a fresh invitation
is allowed by *this* ticket and may still be refused by HD-190's cooldown with a 429 — correctly, and
now without the stale addendum.

### 6.2 Already a member, plus a leftover row
Refused by the already-a-member 409 first (§4.1). The leftover row remains and is visible in the
pending list; withdrawing it is the cleanup, and HD-132's removal path deletes it automatically if the
member is ever removed.

### 6.3 The concurrent-insert race — reachable only where it is cheap
Two simultaneous invitations to the same free address in one workspace both pass the pre-check.

With rate limiting **on** (the default, `app.rate-limit.enabled=true`), they cannot both reach the
insert: `RecipientMailThrottle` takes `pg_advisory_xact_lock(hashtext(recipientKey))` and holds it to
commit, and two addresses that collide in the index necessarily share a throttle key (the throttle's
fold is strictly coarser than `lower()`). The second transaction therefore waits, then counts the
first's event, then is refused by the cooldown — **429, not a collision**.

With rate limiting **off**, no lock is taken and the two inserts race. One gets a
`DataIntegrityViolationException` at flush. **Translate it to the same 409** — the ticket's "must not
500 on the constraint" — by catching the violation on the constraint name and rethrowing
`DuplicateInviteException`. The rollback unwrites the `mail_send_events` row it recorded, and here that
costs nothing: with the master switch off there are no ceilings to probe, only the forensic row, and
`AfterCommit` means the rollback delivers no mail. Phrased as the property rather than as the two
configurations, because a list of configurations goes stale one profile before it does: **whatever
serialises the two inserts is also whatever makes the rollback expensive, so wherever the rollback
would cost something the collision cannot happen.**

> **Correction 6 (review round 1) — that last sentence is a universal, and it does not hold across
> senders.** The cooldown that closes the rate-limited case keys on `samePair()`, i.e. on
> **(sender, recipient)**. Two *different* principals, both holding `workspace.member.manage` in the
> same workspace, inviting the same address: the advisory lock still serialises them, but for the
> second `samePair()` is 0, so no cooldown fires; if the recipient's daily cap also has room, that
> sender records its event, collides at the flush, and the rollback **unwrites the event it just
> wrote**. It has learned one bit — *"this recipient's daily cap had room"* — without spending a slot.
> So the collision **is** reachable with rate limiting on, and there the rollback **does** cost
> something.
>
> **Accepted, not closed, and the reason is that the structural fix costs more than the bit.** Every
> round needs a concurrent *winner* who does spend a slot and does send real mail; the loser still
> burns a unit of its own in-memory sender volume; and the unwritten row corresponds to no delivery,
> because the send is registered `AfterCommit`. The close would be `@Transactional(REQUIRES_NEW)` on
> `RecipientMailThrottle.record`, so the append outlives the rollback. **Rejected**, for two reasons
> each worse than the leak: (1) it takes a **second pooled connection** while the outer transaction
> holds the recipient advisory lock, putting a connection-pool wait inside the one lock this path's own
> rule says nothing slow may sit inside — and that lock is shared with every other tenant inviting the
> same person; (2) it makes **every** rollback on this path permanently spend a recipient slot for mail
> that was never sent, so a lock timeout or a statement-budget cancellation would silently consume a
> stranger's daily allowance. A second advisory lock keyed on `(workspace, address)` remains the wrong
> fix for the reason §6.3 already gives.
>
> **The claim, narrowed to what is true:** the cooldown serialises **same-sender** collisions into a
> 429; a **cross-sender** collision reaches the insert, and its rollback unwrites one
> `mail_send_events` row. The property that survives all three routes and is the one to rely on:
> **the rollback can unwrite an event row whose refusal the caller has already seen, and nothing on
> this path prevents that — the pre-check only makes it rare. So no check that can refuse the request
> may be added between the throttle and the commit.**

> **Correction 7 (review round 1) — the constraint translation had a dead branch and a locale bug.**
> The fallback used when the dialect reports no constraint name tested
> `e instanceof DuplicateKeyException`. That is a product of `SQLErrorCodeSQLExceptionTranslator`;
> under JPA, `HibernateJpaDialect` translates a constraint violation into a plain
> `DataIntegrityViolationException`, so **the branch could never fire**. Meanwhile the trigger it
> claimed to cover ("a driver that reports no constraint name") is not the real one: Hibernate 7.4.1's
> `PostgreSQLDialect` extractor matches the **literal English fragment** `constraint "` against
> `SQLException.getMessage()` (confirmed in the bytecode), so it returns null on any server whose
> `lc_messages` is not English — and the race would then answer **500 instead of 409**, in a
> configuration nobody would notice until it happened. Replaced with a fallback that matches the
> **index's own name** (PostgreSQL quotes an identifier verbatim in every locale) **and** requires
> SQLSTATE `23505`, so a lock error or a statement dump that merely mentions the index cannot qualify.
> Verified against synthesized Russian and German messages (both → 409) and against a `token_hash`
> collision, a lock timeout naming the index, a 23503, and a null SQLSTATE (all → unchanged 500).
> The five sibling services (`Component`/`Label`/`Sprint`/`Version`) carry the identical dead branch;
> pre-existing, out of scope here, worth a follow-up ticket.

> **Correction 8 (review round 1) — the invitee's address reached the logs on the race path.**
> `WorkspaceService` logs only the constraint name, but that is a claim about the *class*, not the
> *request*: Hibernate's `SqlExceptionHelper` logs the driver exception **before** the catch is
> entered, and PgJDBC folds PostgreSQL's DETAIL into the message. Reproduced end to end against the
> running application —
> `Key (workspace_id, lower(email::text))=(<uuid>, victim27612@example.com) already exists` at WARN,
> a third party's address, against this project's own domain-only rule for mail logging. Fixed at the
> only layer that can fix it, the driver:
> `spring.datasource.hikari.data-source-properties.logServerErrorDetail=${DB_LOG_SERVER_ERROR_DETAIL:false}`.
> Identical in `dc` and `cloud`, because the **default** is a privacy floor rather than a tuning knob —
> but it takes a variable, and round 2 corrected which one. The first draft told an operator to append
> `?logServerErrorDetail=true` to `DB_URL`; that wins at the driver (verified — PgJDBC ranks URL
> parameters above the Hikari properties) and is **unreachable on a real deployment**, because
> `docker-compose.prod.yml` sets `DB_URL` as a compose `environment:` literal so `.env` cannot override
> it, and that file is synced and replaced wholesale by a deploy — HD-122's silent-revert trap.
> `DB_LOG_SERVER_ERROR_DETAIL` lives in `.env`, which no deploy touches. It is threaded through
> `${...}` rather than left to relaxed binding on purpose: PgJDBC matches `logServerErrorDetail`
> case-sensitively and relaxed binding would lower-case the key, so the bare spelling is accepted and
> ignored. **And the loss is wider than this path** — the mask drops DETAIL, HINT, POSITION, WHERE and
> INTERNAL QUERY from *every* server error on this pool, Flyway's included (no separate
> `spring.flyway.url`), so a failed migration names its index and not the colliding rows. That is why
> `.env.prod.example` and `docs/self-hosting.md` (configuration table **and** troubleshooting) all
> carry it. It masks only the **message**: `getServerErrorMessage()`
> still carries detail, the constraint **name** stays in the primary message (so the dialect extractor
> and every 409 translation still work — re-verified by staging a real insert race), and a foreign-key
> violation still states its direction, because *"update or delete on table X"* / *"insert or update on
> table X"* is the primary message rather than the DETAIL. `GlobalExceptionHandler`'s FK log line
> previously keyed its operator guidance on the DETAIL phrases *"is still referenced from"* /
> *"is not present in"* and was re-worded to the surviving pair.

Do **not** close this with a second advisory lock keyed on `(workspace, address)`. It would add a
lock-ordering obligation against the recipient lock, on a path whose whole design rule is that nothing
slow may sit between the throttle and the commit, to improve a status code in a configuration that has
already switched its own protections off.

### 6.4 Double-click / double-submit
First 201, second 409. The submit button is already `loading`-disabled while the request is in flight,
so a literal double-click does not reach the server twice; a double *submit* (two tabs, two admins)
gets the refusal, which is the honest answer — an invitation was sent and the caller is told so.

### 6.5 Case-only and near variants
`Bob@x.com` after `bob@x.com` → 409 (both fold to the same index key; the boundary fold means they were
already the same string, and the index covers the historical rows where they were not).
`bob+2@x.com` after `bob@x.com` → **201 or 429**, never 409 — different offer, same inbox, so HD-190's
cooldown is what governs it (§5.1).

### 6.6 Optimistic locking
`WorkspaceInvite` extends `CreatedOnlyEntity` and has no `@Version`; this ticket adds no update path to
the table, so no new stale-state surface. The lock discipline HD-158 introduced (bound, then lock, on
all three by-id/by-token load sites) is untouched — the pre-check takes no lock and does not need one
(§6.3).

### 6.7 Archived / soft-deleted
Nothing here is soft-deleted. Accepted rows are the only rows kept as history and they sit outside the
index by design (§7).

### 6.8 Cross-tenant
The index and the pre-check both lead on `workspace_id`; the pre-check is a two-key query, never a
`findByEmail` filtered in Java. A pending invitation in workspace B never blocks, and is never named
by a refusal, in workspace A.

### 6.9 The migration on a live database
`workspace_invites` is small (one row per invitation ever issued, and three paths delete). The dedupe
is a single `DELETE … USING`; the index is built with a plain `CREATE UNIQUE INDEX` inside Flyway's
transaction, holding a `SHARE` lock on the table for its duration — measured in milliseconds at this
size. No `CONCURRENTLY` (it cannot run in a transaction, and Flyway runs migrations in one).

## 7. Data model impact

**One migration, no column change, no entity mapping change.**

`V22__invite_uniqueness.sql`:

```sql
-- 1. Resolve existing duplicates: keep the NEWEST unaccepted row per (workspace, folded address).
--    Accepted rows are never touched — they are the only record that someone was invited at all,
--    and they sit outside the index by design.
DELETE FROM workspace_invites wi
 USING (
     SELECT id,
            row_number() OVER (PARTITION BY workspace_id, lower(email)
                               ORDER BY created_at DESC, id DESC) AS rn
       FROM workspace_invites
      WHERE accepted_at IS NULL
 ) ranked
 WHERE wi.id = ranked.id
   AND ranked.rn > 1;

-- 2. One standing offer per address per workspace.
CREATE UNIQUE INDEX workspace_invites_pending_email_uk
    ON workspace_invites (workspace_id, lower(email))
 WHERE accepted_at IS NULL;
```

Notes the migration header must carry:

- **Why newest wins.** It carries the most recent intent and the most recent role. It is also the last
  to lapse, because `expires_at` is a fixed seven-day offset from creation — so keeping the newest can
  never delete a live row in favour of a lapsed one. **If the TTL ever becomes configurable, this
  sentence stops being true and this rule must be re-read.**
- **`id DESC` as tie-break**, not decoration: `created_at` defaults to `NOW()`, which is
  transaction-time, so rows written in one transaction (seeding, a scripted import) share it exactly.
  UUID v7 is time-ordered, so `id DESC` continues the same ordering and is unique, making the cleanup
  deterministic instead of "whichever the planner returns".
- **The losing rows' tokens die.** That is the intent: their links 404 through `findByTokenHashForUpdate`
  with no extra code, exactly as a withdrawal does. Nobody is notified, consistent with HD-158 §6.6.
- **Partial, so accepted rows keep no slot.** A person invited, joined, removed, and invited again is a
  normal sequence and must stay one.
- **`lower()` is `IMMUTABLE` and therefore indexable**, unlike `now()` — the reason the predicate is
  `accepted_at IS NULL` and cannot mention expiry (Correction 1). An expression index over text is
  collation-sensitive in the usual PostgreSQL way: a glibc/ICU collation change wants a `REINDEX`, the
  same as every other text index in this schema.
- No PG `ENUM`, no `CHAR(n)`, no new column, so no UUID-v7 or `@CreatedDate` obligation arises.

**Entity:** `WorkspaceInvite` gains **javadoc only**. Do **not** add
`@Table(uniqueConstraints = …)` — JPA cannot express a partial index, and a full unique constraint
declared there would describe a rule the schema does not have. The javadoc should say that the
constraint exists, that it is partial, and that the pre-check in `inviteMember` is the message rather
than the enforcement.

## 8. API surface

`POST /api/workspaces/{workspaceId}/invites` — one new status.

**409** `application/problem+json`:

```jsonc
{
  "status": 409,
  "title": "Conflict",
  "detail": "There is already an invitation to name@company.com waiting in this workspace. …",
  "errorType": "DUPLICATE_INVITE"
}
```

No request or response shape changes. No new endpoint. The existing 409
(`AlreadyWorkspaceMemberException`, no `errorType`) is unchanged and still wins when both apply.

`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` must follow (`api-docs-sync`); the behaviour is
identical in both deployment modes. Rate limiting is unchanged: this endpoint's budgets are the ones
HD-190 set, and no path joins or leaves the sealed throttled set (`ThrottleCoverageTest`), because
nothing new is mounted.

## 9. Frontend impact

**Almost nothing, and that is a property of HD-158 having shipped first.**
`src/main/frontend/src/pages/settings/WorkspacePeoplePage.tsx` already renders
`classifyConflict(err).detail` verbatim under the invite form (`InviteRow`, line 261) and already
shows the pending list, with a Withdraw control on every row, immediately below it. The refusal's
remedy is therefore on screen when the refusal appears.

One change worth making: in `InviteRow`'s catch, when `classifyConflict(err).errorType ===
'DUPLICATE_INVITE'`, also call `onInvited()` (which invalidates `invitesKey(wsId)`) so the row the
sentence names is refreshed into view — a stale list is exactly how an admin ends up believing the
refusal is wrong. Keep the typed address in the box, as the existing comment requires.

Do **not** add a client-side pre-check against the loaded invites list. The client's copy is stale by
construction, the server owns the rule, and a form that greys out a button on stale data produces a
refusal with no explanation at all. `DESIGN.md` tokens only; no new component, no new colour.

## 10. DC / Cloud implications

**No profile gating, no new property, no new environment variable, no compose change, no README or
`.env.prod.example` line.** The constraint is schema, applied by Flyway in both modes; the refusal is
the same code path in both. The wiring checklist for this ticket is **empty**, stated explicitly so
`dc-cloud-guard` can confirm rather than hunt.

One note with no code consequence: the interaction with `app.rate-limit.enabled` (§6.3) is the only
place a *configuration* changes which refusal a duplicate produces (409 from the pre-check either way;
409-from-the-constraint versus 429-from-the-cooldown in the concurrent case). That is a property of an
operator switch that is documented as turning off brute-force protection, not a DC/Cloud fork.

### 10.1 The follow-up this ticket makes slightly more pressing

Nothing sweeps expired invitations. Before this ticket an expired row was clutter; after it, an
expired row blocks a re-invitation until somebody withdraws it. The refusal is performable and the row
is visible, so the product is correct — but a retention sweep for
`accepted_at IS NULL AND expires_at < now() - interval` is now worth its own ticket, and it must be
written knowing that deleting the row is *also* freeing a uniqueness slot (stock, permitted by §5.2's
rule) and never a `mail_send_events` row (flow, forbidden).

## 11. Acceptance criteria

Migration:

1. On a database seeded **before** V22 with three unaccepted invitations to `bob@x.com` in one
   workspace and one accepted one, the migration succeeds; exactly one unaccepted row survives (the
   newest by `created_at`, tie-broken by `id`), and the accepted row is untouched. Shape the test on
   `V15RoleBackfillMigrationTest`.
2. Rows differing only in case (`Bob@x.com` / `bob@x.com`) are treated as duplicates by the cleanup.
3. Two unaccepted invitations to the same address in **different** workspaces both survive.
4. After V22, a direct SQL insert of a second unaccepted row for the same `(workspace_id,
   lower(email))` is refused by the database; the same insert with `accepted_at` set succeeds.

Backend:

5. Invite `bob@x.com`, then invite `bob@x.com` again in the same workspace → **409** with
   `errorType: "DUPLICATE_INVITE"` and a detail naming the withdrawal and the People screen.
6. Same, with the first invitation **expired** → **409**, with the "has lapsed" wording.
7. **The ordering test.** The 409 in AC 5 writes **no** `mail_send_events` row: the count for that
   recipient key is the same before and after the refused call. (This is the free-probe guard; assert
   the count, not the status.)
8. Withdraw the blocking invitation, then invite the same address again → the duplicate check passes.
   The result is a 201 or HD-190's 429, and in the 429 case the detail does **not** contain
   *"still valid"* (the row is gone).
9. `bob+2@x.com` after `bob@x.com` in the same workspace is **not** a 409 (different offer); it is a
   201 or a 429 from the cooldown (same inbox).
10. Invite, accept, remove the member, invite again → **201**. The accepted row does not block, and
    HD-132 deleted the unaccepted one.
11. A pending invitation in workspace B does not block, and is not named by any refusal, in workspace
    A; a non-member still gets 404 from the endpoint before any of this is evaluated.
12. Someone who is already a member with a leftover unaccepted row gets the **already-a-member** 409,
    not `DUPLICATE_INVITE`.
13. A constraint violation reaching the service (simulated) surfaces as the same 409, never a 500.
14. `theCooldownOnlyClaimsTheEarlierInviteIsWaitingWhileItActuallyIs` is replaced per §5.3, and the
    two sibling throttle tests still pass unchanged.

Frontend:

15. Inviting an already-invited address shows the server's sentence under the form, keeps the typed
    address, and the pending list below refreshes so the blocking row is on screen.

Docs:

16. The 409 and its `errorType` are in `openapi.yaml` (validates) and in both `docs/api-cloud.md` and
    `docs/api-dc.md`.

## 12. Open questions — with the recommended default

**Q1. Refuse (409) or replace the row?**
*Recommendation: refuse.* §4.3. Replacing silently revokes an outstanding grant, renews a TTL that is
itself a control, and differs from refusing only outside the cooldown window — where "withdraw, then
invite" is two clicks on a screen that already exists. Overrule this only together with a decision
about what records the implicit revocation.

**Q2. Should an expired invitation block a re-invitation?**
*Recommendation: yes — it is the only enforceable predicate (Correction 1) and it matches the
labels/components/versions/sprints precedent.* The refusal names withdrawal, withdrawal works on
expired rows, and the list shows them. Overrule only together with the sweep in §10.1, and note that
even then the index predicate cannot change.

**Q3. `lower(email)` or `MailAddresses.throttleKey(email)` as the index key?**
*Recommendation: `lower(email)`.* §5.1 — uniqueness is about the offer, a ceiling is about the
delivery, and the offer is redeemed by exact match. Folding `+tag` here would refuse an invitation to a
genuinely different account. The gap it leaves is covered by the cooldown.

**Q4. Remove the cooldown addendum, or keep it as unreachable code?**
*Recommendation: remove it and its finder, keep the `Supplier` parameter for HD-202.* §5.3. A sentence
that cannot be emitted is a claim a reader will trust.

**Q5. Translate the constraint violation to the 409, or let it 500?**
*Recommendation: translate.* §6.3. It is reachable only with rate limiting off, where the translation
costs nothing, and the ticket's acceptance criteria require it.

**Q6. Should the refusal name the blocking invitation's role?**
*Recommendation: no.* §4.5 — it can be `null` on a degraded row and the list beside the form renders
it anyway.

**Q7. Should the migration delete the losing duplicates, or expire them (`expires_at = now()`)?**
*Recommendation: delete.* Expiring them would leave rows that still occupy the uniqueness slot — the
migration would not achieve its own goal — and deletion is what all three existing revocation paths do.

## 13. Architectural decisions (ADR)

**None.** Two candidates were weighed and both fall below the bar:

- *"Uniqueness folds the address, ceilings fold the inbox."* This is the **application** of two
  already-recorded decisions (ADR-0015 for the throttle key, HD-120's "fold once, compare exactly" for
  redemption), not a new fork. What it owes the next reader is the *generating rule* — fold as far as
  the harm points — which §5.1 states in a form that can be quoted, and which is more useful than a
  third document restating two accepted ones.
- *"A dead row keeps its uniqueness slot until somebody clears it."* Consistency with four existing
  partial/expression unique indexes in this schema (`labels`, `components`, `versions`, `sprints`),
  each with the same 409-nudges-toward-cleanup shape. A contributor asking "why does an expired
  invitation block?" is answered by §4.4 and by the precedent, and — as Correction 1 shows — by
  PostgreSQL, which offers no alternative.

## 14. The highest-risk assumption, stated plainly

**That `WorkspaceService.inviteMember` is, and remains, the only writer of `workspace_invites` rows.**
Everything above depends on it: the pre-check's placement above the throttle, the wording of the
refusal, and the §6.3 argument that a collision is reachable only where the rollback is free — that
last one leans on the recipient advisory lock, which exists only because the write goes through
`inviteThrottle.requireRecipientCeilings`. A future bulk-invite endpoint, an SSO auto-provisioning
path, or an admin
import that inserts directly inherits **none** of that: it gets a raw constraint violation, a 500, and
a rolled-back `mail_send_events` row it never meant to write.

The mitigation is structural rather than remembered: the invariant is the index, so such a path fails
*closed* — it cannot create the duplicate, it can only report it badly. What the builder owes is a
sentence on the entity and on the repository saying that any new insert into this table must go
through the same pre-check-then-throttle order, phrased as the requirement rather than as a list of
today's callers.

The second-highest: **that no legitimate workflow needs two live offers to one address in one
workspace at once.** I found none — the role is the only thing that would vary, and the product's
answer is now "withdraw, then invite", which is deliberate rather than accidental. If bulk onboarding
ever imports a list containing the same address twice, that import needs to dedupe its own input; the
API will refuse the second row rather than silently producing an ambiguous grant, which is the correct
failure but not a silent one.
