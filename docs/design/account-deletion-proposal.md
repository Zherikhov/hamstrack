# Account deletion — the decisions, the runbook, and the one path a user can reach (HD-193)

**Status:** proposed (spec only — no application code written by this document)
**Ticket:** HD-193 · release 0.18.0 · Story · High · 3 sp
**Related:** HD-192 (legal pages, BLOCKED on the owner) · HD-195 / ADR-0022 (durability promise + `PublishedClaimsTest` seal) · HD-232 (`failed_email` retains addresses longer than `mail_send_events`) · HD-187 / ADR-0012 (write-once backup bucket) · HD-123 / HD-132 / HD-136 / HD-127 / HD-130 (the last-owner and last-administrator invariants) · ADR-0009 (tenant-scoped inbox)

---

## 1. Problem & goal

`PrivacyPage` promises that a user can delete their account and that we will delete or
anonymise their personal data. Nothing behind that sentence exists: no endpoint, no UI
affordance, no runbook, no rehearsal. The promise is the whole implementation. Users will be
in the EU, the data sits in `eu-north-1`, and an erasure request carries a statutory clock —
so the first one arrives as an email against a procedure nobody has written, on a schema
where a bare `DELETE FROM users` **fails on a foreign key** (§6.1) and where the guards this
codebase built to stop a tenant becoming unadministerable are, in raw SQL, not present at all.

Success for 0.18.0 is: every decision written down and argued; a runbook an operator can
execute under time pressure without inventing anything; that runbook rehearsed end to end
against a restored copy of production; a reachable, honest request path in the product; and
the legal page describing the mechanism in the same words the runbook implements. The
self-serve `DELETE` endpoint is 1.0.0 — it is these decisions plus code, and shipping the
decisions first is what makes it reviewable.

It also settles a debt HD-195 just took on. The published paragraph says *"Your workspaces,
projects and issues stay until they are deleted."* Until this ticket lands there is no
mechanism by which they are ever deleted, so the sentence's escape clause is aspirational.
**What makes it true is §6 and §11 of this document: "deleted" now names a rehearsed
procedure with a defined blast radius, an owner-of-last-resort rule, and a replay step that
survives a restore.** The paragraph's wording does not change, and must not — it is carried
byte-identical by three surfaces plus a test constant (`PublishedClaimsTest`), and changing
it is a four-file commit for no gain.

---

## 2. Scope

**In scope (0.18.0)**

- The decisions: what is deleted, what is anonymised, what is retained, and on what
  reasoning (§5–§7).
- The owner-of-last-resort rule and how the procedure works *with* the existing guards (§5).
- A complete sweep of every table referencing a user, with a verdict each (§6.1).
- Attachment objects, for both storage backends, including S3 object versions (§7).
- The mail-log sweep (`mail_send_events`, `failed_email`) — the mechanism, never a period (§6.2).
- The backup/restore trap and its answer: an address-free erasure ledger, replayed after a
  restore (§8).
- A **request path in the product**: a new authenticated Account page reached from the user
  menu, which states the mechanism and gives the address to write to. No form, no request
  table, no reply-time promise (§9).
- One new property + one new `/api/meta` field, **empty by default in every profile** — the
  hosted deployment's address lives in that deployment's own `.env`, never in a properties
  file (§10).
- Precise, argued edits to `PrivacyPage.tsx` §5 (§4).
- An executable operator runbook (§11) and its rehearsal plan (§12).
- Three ADR drafts (§15).

**Out of scope / non-goals**

- **No self-serve `DELETE /api/auth/me`.** 1.0.0; sketched in §9.5 only so the later ticket
  inherits the decisions rather than re-litigating them.
- **No in-app deletion-request table or admin queue.** Argued in §9.2 — a row nobody watches
  is this ticket's own defect with a migration attached.
- **No retention period anywhere**, in the product, in the policy, or in this file's proposed
  copy. That is HD-192's, and §4.3 draws the line.
- **No response-time commitment**, no new mailbox, no statement that any mailbox is monitored.
- **No change to the HD-195 canonical paragraph, `TermsPage.tsx`, or `CookiesPage.tsx`.**
- No change to `README.md`, `docs/api-cloud.md` or the landing page.
- No account-profile editing (rename, change email, change password from the Account page).
  The page is created for the deletion affordance; profile editing is a separate ticket.
- No workspace-delete endpoint. The solo-workspace deletion in §5.3 is runbook SQL.
- No suppression list of erased addresses (§6.4 — it would be a durable store of exactly the
  datum being destroyed).
- No sweep of application logs per subject (§6.3).
- **No per-deployment gating of the legal routes.** `/privacy`, `/terms` and `/cookies` are
  served by every installation and describe the **hosted** Service; making a self-hosted
  install serve its own (or none) is a config toggle over routing, is a whole ticket of its
  own, and is not answered here. What this ticket does instead is keep the *deletion* pointer
  installation-neutral: the policy points at the Account page, which resolves the local
  configured address (§4.3, §9.3). **Do not answer the divergence by adding a self-hosted
  paragraph to a legal page** — that publishes a second, unreviewed representation about a
  controller the reader is not, on a page whose whole subject is the one they are. The
  argument is carried in `PrivacyPage.tsx`'s comment beside §5 so it survives the next edit.

---

## 3. Actors & permissions

| Actor | What they can do | How they are authorised |
|---|---|---|
| **Account holder** | See the Account page, read what deletion does, obtain the address to write to. | Authenticated session. The page is account-scoped, not workspace-scoped — it lives outside `/w/{wsId}` and reads nothing tenant-scoped. |
| **Instance operator** (Cloud: the owner; DC: whoever runs the install) | Execute the runbook. Direct database and object-store access. | Out of band. Not an API role, deliberately — §9.4. |
| **System `ADMIN`** | Nothing new. `PATCH /api/admin/users/{id}` can already set `DISABLED`, which is deactivation, not erasure. | Existing `/api/admin/**` guard. |
| **Workspace Owner / Admin of a workspace the subject belongs to** | Nothing new, and nothing removed. They cannot request or perform another member's erasure. | Existing permission model. |

**Tenancy note that governs the whole runbook.** Everything in §11 is keyed by
`users.id`, or by a list of workspace ids that the pre-flight *derived* from that id — never
by an id an operator retyped. **And that `users.id` is itself derived**, by a `SELECT` on the
address the confirmation code was sent to and returned from (§5.5, phase 1): the request's own
`User id:` line is attacker-chosen and is never typed into the procedure. Two sweeps are necessarily keyed by something else — the
display-name rewrite in `issue_history` and the notification-title rewrite — and **both are
scoped to the pre-flight's workspace list**, because an unscoped match on a display name
over `issue_history` rewrites other tenants' audit rows. That is this project's top bug class
with the blast radius of a `DELETE`. The mail-log sweeps are address-keyed and unscoped by
design, and that is safe for one specific reason: those two tables are install-level
operational logs with no `workspace_id` predicate and no tenant-facing reader (V7 and V21
both state this as an invariant), so "unscoped" there is the table's shape, not a missing
filter.

Three properties follow, and every statement in §11 is written to satisfy them. They are
stated as properties rather than as steps because a step can be skipped by a tired operator
and a property cannot:

- **A statement scoped by workspace must refuse a wrong id by construction.** Not "paste
  carefully": a mis-pasted id has to report `DELETE 0`, because the statement itself asserts
  the tie between that workspace and the subject. An instruction to be careful is not a
  control; a predicate is.
- **Nothing that scopes by a workspace id may run after the evidence that ties that id to
  the subject has been deleted.** The subject's `workspace_members` row *is* that evidence,
  so it is the last thing to go. This is why the membership deletes sit at the end of phase
  5 rather than in the middle of phase 4, where an earlier draft put them — and where they
  made the self-check on the most destructive statement in the document impossible rather
  than merely absent.
- **A sweep keyed by a display name rewrites; it never deletes.** Over-matching a rewrite
  costs legibility in a row that already survives; over-matching a delete destroys a third
  party's data with no undo. Both name-keyed sweeps are therefore `UPDATE`s, and the fail-safe
  argument in open question 3 is true only because of that (§6.5).

---

## 4. The legal-page edit: what ships now, what is HD-192's

### 4.1 First, a correction the builder needs

The ticket cites "`PrivacyPage` §6". In the file today the deletion promise is in **§5
Retention** (`PrivacyPage.tsx:59-65`); §6 is "Your rights". **Edit §5.** §6 is left
byte-identical — it concerns rights, lawful bases and supervisory authorities, which is
HD-192's territory in full.

### 4.2 The line between a mechanism and a representation

A sentence may ship in HD-193 if it is a **factual description of a mechanism this ticket
builds and rehearses**. It may not ship if it is a **representation about retention, timing,
lawful basis, or who the controller is** — those need inputs HD-192 is blocked on, and
spending one of them here would answer HD-192's question without HD-192's lawyer.

The test is not "does it mention deletion" but "**would a lawyer have to supply a fact for
this sentence to be written?**" A sentence describing what the SQL does needs no external
fact. A sentence containing a number of days, a deadline, an entity name or an Article
reference needs one.

Two corollaries, both of which decided a line below:

- **A disclosure of a limitation is never a commitment.** "Your name may remain where another
  member typed it" and "a workspace where you are the only member is deleted with its
  contents" are both *adverse* to the subject. Disclosing an adverse fact creates no
  obligation the operator did not already have; withholding it is the defect.
- **Do not tighten or loosen an existing representation.** "within a reasonable period"
  already stands in the published policy. Replacing it with a number is HD-192's; deleting it
  leaves the policy silent on timing, which is also HD-192's. It is therefore carried through
  **unchanged**, only repositioned.

### 4.3 The proposed §5, in exact words

Replace the single paragraph at `PrivacyPage.tsx:60-65` with three paragraphs:

> We keep your data for as long as your account exists. You can ask us to delete your
> account from the Account page in the app — it tells you where to write. We confirm the
> request by sending a code to the email address on the account before acting on it. We then
> delete or anonymize your personal data within a reasonable period, except where we must
> retain it to comply with legal obligations.
>
> Deletion removes the personal data in your account record — your email address, display name
> and sign-in credentials — and removes your address from our mail delivery logs. Work you
> created inside a workspace (issues, comments and uploaded files) stays with that
> workspace and is re-attributed to "Deleted user", so the team's history stays readable; text
> other members wrote is not edited, and your name may remain where they typed it. A workspace
> in which you are the only remaining member is deleted with its contents. Content shared into
> a workspace may remain visible to other members of that workspace as part of their data.
>
> Backups of the whole database are taken routinely and are not edited. If we ever restore
> one, we re-apply every completed deletion to the restored copy before it is used again.

**The deletion pointer names the Account page and nothing else, and the earlier draft of this
section was wrong to add "or by writing to the address in section 10".** That was written as a
convenience and is a routing defect: §10 is the **hosted operator's** support inbox, while this
page is routed in **every** installation. A self-hoster's user following it would send their
name, address and user id to a mailbox nobody can service on their behalf, and would receive
either silence or an answer from a controller they have no relationship with. The Account page
resolves that installation's **own** configured address and names the local administrator when
none is set (§9.3), so the pointer is correct in both deployments with one wording. The
argument lives here **and** in a comment beside the paragraph in `PrivacyPage.tsx`, because the
shorter sentence reads like an omission and the obvious "improvement" is to put the address
back. The equally wrong repair is a self-hosted carve-out paragraph on a legal page — see §2's
non-goal.

Clause by clause, and why each is on the shippable side:

| Clause | Kind | Why it ships |
|---|---|---|
| "You can ask us … from the Account page in the app — it tells you where to write" | mechanism | Describes §9's affordance, which this ticket builds. Names no address, so it is true in a hosted and a self-hosted install alike; the address itself is resolved per installation by the Account page. No claim any mailbox is monitored, no reply time. |
| "We confirm the request by sending a code to the email address on the account" | mechanism | §5.5's identity check. States no duration. |
| "We then delete or anonymize your personal data within a reasonable period, except where we must retain it to comply with legal obligations." | **existing representation, unchanged** | Byte-identical to today's second half, only moved. Not a new fact. |
| "removes the personal data in your account record — email address, display name and sign-in credentials" | mechanism | §6.1's `users` scrub, named precisely — and phrased about the *data* rather than the record, because ADR-0023 keeps the row. "Removes your account record" would have been the one clause in this paragraph the procedure does not do. |
| "removes your address from our mail delivery logs" | mechanism | §6.2. **No period** — HD-232's asymmetry stays entirely with HD-192. Accurate as written, and only as written: see below. |
| "re-attributed to 'Deleted user'" | mechanism | §6.1's anonymisation-in-place, in the literal string the UI renders. |
| "text other members wrote is not edited, and your name may remain where they typed it" | disclosure of a limitation | Adverse to the subject; §6.5 is the residual it discloses. |
| "A workspace in which you are the only remaining member is deleted with its contents" | disclosure of a limitation | Adverse to the subject; §5.3's rule. |
| "Content shared into a workspace may remain visible to other members …" | **existing sentence, unchanged** | Kept verbatim; the new sentences make it concrete rather than replacing it. |
| "Backups … are not edited. If we ever restore one, we re-apply every completed deletion …" | mechanism | §8 + §11 phase 7. States a **procedure**, never a duration. This is the only genuinely borderline line — see §14 open question 9. |

**The mail-log clause is accurate, and it is accurate because it is singular.** §6.2's sweep
deletes rows whose `recipient_email` is *the address on the account* — the same address the
clause has just finished naming as part of the account record — so "removes **your address**
from our mail delivery logs" describes exactly what runs. What would make it false is a
rewrite into a *category*: "removes every trace of you from our mail logs", "removes any
address that reaches you", or a plural "your addresses". Those would claim the `recipient_key`
sweep this ticket deliberately rejects (§6.2), and a row addressed to a different spelling that
lands in the same inbox would then be a published claim the procedure does not honour. **Keep
the singular. It is load-bearing.**

### 4.4 The part of AC#2 that cannot ship until HD-192 unblocks

AC#2 is *"the legal pages describe what the procedure actually does, in the same words."*

**Ships in full: what the procedure does.** Every mechanism sentence above is a description
of §11, written in the same vocabulary the runbook uses.

**Does not ship: when.** The policy will describe the act and stay silent on how fast, on how
long a deleted account's residue persists, on how long mail logs and server logs are kept,
and on how long a backup can still resurrect a deleted account. Those four numbers belong to
HD-192 and are enumerated for it in §14. So AC#2 is met for *what*, and is **explicitly
deferred for *when*** — and that deferral must be written on HD-193 when it closes, or the
next reader will treat the criterion as fully discharged.

### 4.5 Two mechanical constraints the edit must respect

1. **`LegalLayout lastUpdated` must be bumped** to the ship date. §9 of the policy promises
   exactly that, and an edit that leaves the date is the policy breaking its own terms.
2. **`PublishedClaimsTest` does NOT scan this file — it scans where the copy LANDS.** Its javadoc
   excludes `docs/design` deliberately (alongside `docs/project-state.md` and
   `docs/ops-prod-hardening.md`), so nothing here is checked by it. The constraint is real
   anyway, and that is the point: this wording ships into `PrivacyPage.tsx`, and the SPA source
   under `src/main/frontend/src` **is** in the scanned set. So the assertions below bind the
   moment the copy is pasted, not while it sits in this document — which is the worst possible
   time to discover them. Three bear on the new copy:
   - the negative regex `(data|workspaces?|projects?|issues?) (may|might|can|will) be
     (reset|wiped|erased|cleared)` — so **write "is deleted", never "may be erased" or
     "will be wiped"**. The proposed wording clears it; a reviewer "improving" it may not.
   - the bare word `beta` must not appear.
   - assertion 4 fails a surface containing the fragment **`backed up daily`** without the
     full canonical paragraph. The proposed line therefore says *"Backups of the whole
     database are taken routinely"* — **this is a load-bearing wording choice, not a stylistic
     one.** Writing "backed up daily" on `PrivacyPage` reds the build.

---

## 5. The governance decisions

### 5.1 What erasure *is*: anonymisation in place, not deletion of the row

**Decision: the `users` row survives and is scrubbed. It is never deleted, and no tombstone
user is introduced.** (ADR-0023.)

Scrub, in one `UPDATE`:

| Column | New value | Why |
|---|---|---|
| `email` | `deleted+<user_id>@deleted.invalid` | `.invalid` is RFC 2606 reserved — it can never receive mail. Unique by construction, so both `users_email_key` and `users_email_lower_uk` (V23) are satisfied. Within `VARCHAR(255)`. |
| `display_name` | `Deleted user` | The literal string the SPA renders everywhere a user is named. |
| `avatar_url` | `NULL` | |
| `password_hash` | `NULL` | Also the OAuth-only/pending shape, so nothing downstream is surprised. |
| `status` | `DISABLED` | `JwtAuthenticationFilter` re-reads the user and filters on `User::isEnabled()` (`status == ACTIVE`) **on every request**, so this takes effect on the next request with no token-TTL wait. |
| `system_role` | `USER` | A tombstone must never be an `ADMIN`. |
| `demo_seeded_at` | left as-is | `NULL` would re-arm demo seeding and mint a fresh Demo Workspace for a dead account on any future auth attempt. |
| *every remaining column* | left as-is | What is left after the four identity columns above are gone is timestamps — `created_at`, `updated_at`, `terms_accepted_at`, `onboarded_at`, `demo_seeded_at` — plus `id`. They are deliberately kept: each records that *something happened to this row*, none of them names a person once the row names nobody, and clearing them would break the auditing and onboarding branches that read them. This row exists so the table reads as **complete** rather than as a selection somebody stopped writing. |

**`status = 'DISABLED'` is a consequence of the scrub and is NOT the marker for "this row was
erased".** That column has more than one producer: an administrator suspends a live human
being with the same value (`AdminUserService.update`), and an erasure that is interrupted
after phase 3 leaves it set on an account that still has its address. So `DISABLED` answers
"can this account act?" and nothing else.

**The tombstone predicate is the whole address, and it must name the row's own id:**

```sql
u.email = 'deleted+' || u.id::text || '@deleted.invalid'
```

It is what every branch of §5.3, every pre-flight query in §11 and the replay's skip test use
to decide whether a member is still a person. Reading `DISABLED` as "not a person any more" is
the mistake that routes a workspace containing a suspended colleague's work into the branch
that deletes it.

**Why the self-referential form rather than `email LIKE '%@deleted.invalid'`, which an earlier
draft used.** The domain match is a claim about who *writes* that value, and this document
asserted it was "written by exactly one procedure". That was false and unfixable by asserting
it harder: nothing in the product denylists the domain, Hibernate Validator's `@Email` accepts
`deleted.invalid`, and `AdminUserService.create` sets `status = ACTIVE` with no verification —
so a live member could hold `x@deleted.invalid`, be invisible to the widest liveness test, and
have their workspace routed to §5.3(c) and deleted with everything in it. **State the property
that makes a predicate safe instead of the history you hope holds: the value contains the row's
own `id`, so satisfying it requires knowing an id that does not exist until the row is
inserted.** There is no address-change door anywhere in the product — `setEmail` is called at
registration, by `AdminUserService.create`, and by the seeder, all of them *before* the id
exists and none of them afterwards — so no account holder and no administrator can put their
own row into the erased set. That is unforgeable by construction rather than by convention, and
it survives a future contributor adding a signup path, which the domain match did not.

*The one edge it leaves, and it fails loudly:* somebody may register `deleted+<another user's
id>@deleted.invalid` as a squat. It never makes **their** row read as erased (the predicate
compares against *their* id), but it does occupy the address the victim's phase 8 will try to
write, so the scrub aborts on `users_email_lower_uk`. A failed `UPDATE` with a unique-violation
is the correct outcome: it is visible, it changes nothing, and the remedy — deal with the
squatting account first — is available to the operator reading it.

**Why not hard delete.** Because the schema refuses it, and because the refusal is right.
Nine columns reference `users` with no `ON DELETE` clause at all — PostgreSQL's default is
`NO ACTION`, so `DELETE FROM users WHERE id = …` raises a foreign-key violation from
`workspaces.created_by`, `projects.created_by`, `issues.reporter_id`, `issues.assignee_id`,
`issue_comments.author_id`, `issue_history.changed_by`, `issue_attachments.uploaded_by`,
`saved_filters.owner_id` and `workspace_invites.invited_by`. Making the delete succeed would
require either (a) `ON DELETE CASCADE` on those, which destroys **other people's history** —
every comment thread, every audit row, every issue a departed colleague filed — or (b)
repointing all nine at a shared "Deleted user" row, which is eight bulk `UPDATE`s per
erasure and collapses every departed person in an install into one indistinguishable
identity, so an audit trail with two departed authors becomes unreadable.

Anonymisation in place costs **zero** of those updates: every one of those references keeps
pointing at a row that no longer identifies anybody, and each departed person stays distinct
from every other departed person while being identifiable as none of them. That is the whole
argument, and it is why this is an ADR and not a mechanic.

**No mapping is kept in any durable store this procedure creates.** No table, no file, no
ledger field maps the scrubbed `user_id` back to the original address (§6.4, §8.2). Once the
`UPDATE` commits, nothing the erasure *wrote* can answer "which account was this?" — and that
is the property that makes the result anonymisation rather than pseudonymisation *on the
controller's side*. §13 states the residual on the *community's* side, which is the real risk
in this design.

**The claim is exactly that narrow, because one mapping exists for as long as the procedure
runs and it is not in the database: the mailbox.** The intake thread carries the address in its
headers and, thanks to the `mailto:` template, the `User id:` line beside it — which is the
address↔id pair this section says does not exist. It is destroyed in phase 10 along with the
local working files, for the same reason and at the same moment. **Until phase 10 has run, or
if the operator's own mail-retention rules forbid deleting the thread, the mailbox is a
residual and this paragraph is qualified by it** — say so on the ticket rather than leaving the
unqualified sentence standing, because a compliance claim that is true of the database and
false of the inbox it was conducted through is the worst of the three states available.

### 5.2 Do the existing guards already refuse? Yes — through the API, and not at all in SQL

Read `WorkspaceMemberService.lockOwners` / `requireNotLastOwner` and `ProjectAdminGuard`.

- Removing the **last workspace Owner** is refused today with `409
  LastWorkspaceOwnerException`, decided from a *locked* owner set (`FOR UPDATE`, `ORDER BY
  id`), unconditionally, before the target row is read.
- Removing a workspace member who is the **last administrator of a project** is refused with
  `409 StrandedProjectsException`, with `adoptAll` as the satisfiable retry. Doors 1–3 are
  locked; doors 4–9 are advisory because an aggregate cannot take `FOR UPDATE`. Door 6
  covers administrators who exist only through the §5.2 inheritance chain.
- `project.member.manage` is deliberately outside `Permission.projectCuration()`, so **a
  project with no holder of it cannot be repaired through the API by anyone, including a
  workspace Owner.** Recovery is a hand-written `UPDATE`.
- **An instance must retain a signable-in system administrator.**
  `AdminUserService.guardLastAdmin` refuses with `409` any disable or demotion whose target is
  the last `ACTIVE` `ADMIN`, and the freeze performs both of those writes in one statement
  (`status = 'DISABLED'`, `system_role = 'USER'`). This guard is not tenant-shaped, which is
  why no branch of §5.3 can see it: what is lost is `/api/admin/**` for the whole install, and
  nothing in the product can mint a replacement administrator without one — the recovery is a
  hand-written `UPDATE`, exactly as in the case above. The runbook therefore counts the other
  `ACTIVE` administrators in phase 1(a) and refuses to start phase 3 while that count is zero,
  naming a remedy its reader can still perform at the moment they read it: appoint another
  administrator, then re-run.

**So the guards refuse; and the runbook is SQL, where nothing refuses.** That is the
operative fact. The procedure therefore does not "work around" the guards — it **reproduces
their checks as explicit read-only queries in the pre-flight (§11 phase 1) and refuses to
proceed until they pass.** A runbook that goes straight to `DELETE FROM workspace_members`
reaches, in one statement and with no error, the exact state HD-132/HD-136/HD-127/HD-130
were four tickets' worth of work to prevent.

Two consequences worth stating as properties rather than as steps:

- **Anything the API refuses, the runbook refuses too**, and for the same reason. The
  operator's extra power is to *resolve* the refusal (transfer ownership, appoint an
  administrator, delete an empty workspace), never to ignore it.
- **A disabled-but-still-present membership row is not a resolution.** Every guard's candidate
  query filters on `ACTIVE`, so a project whose sole administrator is no longer `ACTIVE`
  becomes silently unmanageable with no 409 and no receipt. `ProjectAdminGuard`'s class javadoc
  records this exact direction of the DISABLED approximation. **Therefore governance is
  resolved before the statement that first sets `status = 'DISABLED'` — which is the freeze
  (phase 3), not the scrub (phase 8).** That distinction cost this document a review round: the
  scrub is merely the *second* writer of that value, and gating on it protects nothing, because
  by then the subject has been invisible to every `ACTIVE` filter for as long as the erasure has
  been running. This ordering is the single most important line in the runbook, and the phase
  it names is 3.

### 5.3 The owner-of-last-resort rule

**Two predicates run through this section and they are not interchangeable. Getting them the
wrong way round is how a suspended colleague's work gets deleted.**

| Question | Predicate | Why that width |
|---|---|---|
| *May this workspace be destroyed?* | **not erased** — `email <> 'deleted+' \|\| id::text \|\| '@deleted.invalid'` (§5.1) | The widest liveness there is. A suspended member, a `PENDING` signup and an account whose own erasure was interrupted all count as people, and each of them keeps the workspace alive. Over-counting here refuses a deletion; under-counting destroys somebody's work. |
| *Whom may we appoint?* | **`status = 'ACTIVE'`** | The narrowest. An appointee must be able to sign in — promoting a suspended account to Owner produces a workspace whose only administrator cannot open it, which is the stranding this whole family of guards exists to prevent. |

State it as the category, because the next reader will meet a predicate this document did not
write: **a test that decides whether to destroy uses the widest liveness; a test that decides
whom to hand power to uses the narrowest.** Neither is `status <> 'DISABLED'`, which is the
value an administrator writes when suspending a live human being (§5.1).

Evaluate **per workspace the subject belongs to**, in this order. The subject's *identity* is
erased in every case; what varies is what happens to the workspace.

**(a) The subject is not the workspace's only `ACTIVE` Owner** (they are not an Owner, or
another Owner exists who can sign in). Nothing special. Resolve any project-level stranding
(below), then remove their `project_members` and `workspace_members` rows.

**(b) The subject is the only `ACTIVE` Owner, and at least one other member of the workspace
has not been erased.** **Ownership transfers; the workspace survives.**

- The operator asks the subject to nominate a successor as part of the intake reply.
- If they nominate one who is an `ACTIVE` member of that workspace, promote that member to the
  built-in Owner role (`00000000-0000-7000-8000-000000000001`).
- If they decline or do not answer, promote the **longest-tenured `ACTIVE` member (`joined_at`
  ASC) who holds `workspace.member.manage`**; if none holds it, the longest-tenured `ACTIVE`
  member.
- **Email every remaining member** stating that ownership moved and who holds it.
- Record the transfer in the ledger (§8.2) — a restore replay has to redo it, and nothing in
  the restored database says who was chosen.

  *Why auto-promote rather than refuse.* Refusing until the subject nominates hands a
  statutory clock to a person who has just asked to stop dealing with us, and a solo-Owner
  who simply stops replying makes their own erasure impossible. Leaving the workspace
  ownerless is worse still: it is permanently unadministerable, which is the failure mode this
  codebase spent four tickets guarding. Between "a colleague is promoted and told" and
  "a team's workspace is bricked", the first is plainly the smaller harm — and the
  notification is what makes it honest rather than silent.

**(b′) The subject is the only `ACTIVE` Owner and there is no `ACTIVE` member to promote, but
some other member exists who has not been erased** (every one of them is suspended, or
mid-signup, or mid-erasure). **This branch blocks: the workspace is neither deleted nor
transferred automatically, and it is escalated to a human before the freeze.**

  Removing the subject's membership here orphans the workspace, which is refused outright; and
  leaving the membership row while the account stops being `ACTIVE` is *worse than* orphaning,
  because every guard's candidate query filters on `ACTIVE` and the workspace becomes silently
  unmanageable with no 409 and no receipt. Deleting the workspace instead would destroy the work
  of somebody who has asked for nothing and cannot even log in to object. So the resolutions are
  the two a human can take: reactivate the suspended member — a decision belonging to whoever
  suspended them, not to this erasure — and then run branch (b); or obtain the workspace's
  agreement to delete it, and run branch (c). Branch (c)'s delete carries its own predicate —
  *nobody un-erased is left* — which by construction does not hold while this branch applies:
  the un-erased member is what made this b′ rather than (c). So the agreement is not by itself
  the mechanism; §11's **b′ close-out** says what has to become true first, and that the guard
  is never edited to make the statement fire. **And the two resolutions are less independent
  than they look**: every member here is non-ACTIVE by this branch's definition and a non-ACTIVE
  account cannot sign in, so the member cannot *leave* either — (c) needs the same reactivation
  (b) does, after which (b) is available and is the cheaper answer. Take (c) only when deletion
  is what the workspace actually asked for.

  **The gate is on phase 3, not on phase 8, and an earlier draft of this document had it in the
  wrong place.** It argued that the scrub is what sets `status = 'DISABLED'` and blinds the
  `ACTIVE` filters, so blocking phase 8 was enough. Phase 3 does that already, instance-wide,
  in its first statement — §5.1 says so two sections earlier — so a b′ that lets phases 3–7 run
  inflicts the entire harm it exists to prevent and then congratulates itself for withholding
  four columns of identity. **Decide it before phase 3, in the open:**

  - **Default, and it will be the usual answer: the freeze proceeds.** The subject has asked to
    be locked out; refusing to disable their account until a *third party* is reactivated makes
    one workspace's governance a veto over another person's request, and leaves live sessions
    and refresh tokens standing for the duration. So run phase 3 knowingly — and know what it
    costs: **from that moment the workspace has no administrator who can sign in**, and no API
    call will say so.
  - Because that is a real harm to people who asked for nothing, proceeding requires all three:
    **notify every remaining member of that workspace** (the same message branch (b) sends,
    minus a successor's name — they must be told the workspace needs an Owner and how to get
    one), **record the escalation with a review date** on the ticket, and **re-check it on that
    date** rather than when the next erasure happens to notice. An escalation with no clock is
    a workspace nobody comes back to.
  - **Holding the whole erasure instead** — running nothing, not even the freeze — is the right
    call only when the operator can resolve b′ within hours (the suspended member's reactivation
    is already agreed, say). It is the exception, and it is chosen deliberately, not by drifting.

  Either way, that workspace's `workspace_members` row stays and **phase 8 does not run**: the
  scrub is what destroys the address and display name the operator still needs, and there is no
  reason to spend it while the request is unfinished. Everything else in the erasure (phases 4,
  6, 7, and the other workspaces' phase 5) proceeds. **This workspace's own phase 5 is deferred,
  not skipped.** Both resolutions above end with the subject holding no row in it, and the step
  that gets there — re-derive the lists from the database, then re-run phases 2, 5 and 7 for the
  released workspace, then the scrub and the verification — is §11's **b′ close-out**, written
  out there rather than improvised on the day the escalation resolves.

**(c) Every other member of the workspace has been erased, or there are none** (a solo
workspace). **The workspace and everything under it is deleted, including its attachment
objects.**

  *Why delete rather than keep.* Nobody else's history is destroyed — by construction there
  is nobody else *left*: every other row belongs to an account that has already been through
  this same procedure. Every issue, comment and file in it was written by the person being
  erased, so retaining it is retaining their personal data for no purpose and with no reader.
  Note that this is the **common** case, not the exception: `DemoDataService` seeds a "Demo
  Workspace" for every account on first authentication, so essentially every erasure has at
  least one solo workspace to delete.

  *The predicate is "not erased", never "`ACTIVE`", and never "not `DISABLED`".* A `PENDING`
  member is mid-signup and treating them as absent would delete a workspace out from under
  someone who is about to arrive; a suspended member is a live human being whose account an
  administrator closed for reasons that have nothing to do with this erasure, and treating
  *them* as absent deletes their work — the whole of it, silently, with the justification
  "there is nobody else" being false at the moment it is written. Counting both keeps the
  workspace alive and routes to (b) or (b′), which are the non-destructive directions. This
  answers open question 2 as well: `PENDING` needs no rule of its own once the predicate is
  "has this account been erased?".

**(d) Project-level stranding, in every surviving workspace.** Before the membership rows go,
run the pre-flight's stranded-projects query (§11). For each project the subject's departure
would leave with no `ACTIVE` holder of `project.member.manage`: the operator appoints the
longest-tenured **`ACTIVE`** project member to the built-in **Team lead** role
(`TEAM_LEAD`, V16), which is what `ProjectAdminGuard.adoptAll` already grants and which was
chosen precisely because it carries nothing destructive. If the project has no other `ACTIVE`
member at all, appoint the workspace's (possibly newly promoted) Owner. **Do not appoint
Project admin** — V16 and `ADOPTION_ROLE_KEY`'s javadoc carry that argument, and repeating the
wider grant here would silently overturn it. Record each appointment in the ledger, for the
same reason as the ownership transfer: the replay after a restore has to redo it.

  **A project in a workspace routed to (c) is outside this branch entirely.** Phase 5 deletes
  the workspace, its projects and the membership rows together, so there is nothing to appoint
  anybody to — and by construction nobody un-erased to appoint. The pre-flight flags those rows
  `workspace_deleted` precisely so the gate does not read them as stranded (§11 phase 1(c)); on
  a shipped install the seeded demo workspace produces one on every erasure.

  **In a workspace routed to (b′) this branch has no candidate, and that is not an oversight to
  work around.** There is by definition no other `ACTIVE` member, and the "appoint the
  workspace's Owner" fallback names the subject. So the projects there stay in the pre-flight's
  stranded-projects result for the duration of the escalation — the same cost (b′) already
  states, at project granularity — and the runbook's gate on that query is a partition, with
  `:ws_blocked` among the fates it excuses, for exactly this reason (§11 phase 2). Appointing a
  member who cannot sign in is
  refused above; the resolution is (b′)'s, not this branch's.

**Rejected outright: orphaning.** Leaving a workspace with no Owner, or a project with no
`project.member.manage` holder, is the state the whole guard family exists to prevent and is
recoverable only by hand. It is not an option, in any branch.

### 5.4 Concurrency, idempotency and the freeze

- **Freeze first — but "first" means after governance, not before it.** The freeze is the step
  that first writes `status = 'DISABLED'`, so §5.3's branches are decided *ahead of* it, not
  ahead of the scrub (§5.2, §5.3(b′)). **And know which half of the freeze the SQL cannot
  reach.** Phase 3 sets `status = 'DISABLED'` and deletes `refresh_tokens`. For **every request-bound path** that is
  enough and takes effect immediately: `JwtAuthenticationFilter` re-reads the user and filters
  on `isEnabled()` per request, and `AuthService.refresh` rejects a non-`ACTIVE` user, so there
  is no TTL to wait out. **It is not enough for an already-open SSE stream.** Membership and
  status are checked at *subscribe* time and never again; the only thing that closes a live
  emitter early is the `WorkspaceMemberRemoved` domain event published by
  `WorkspaceMemberService.remove`, and raw SQL publishes nothing — so an erased user's browser
  keeps receiving live workspace activity (project/issue metadata, not content) until the
  emitter hits its 30-minute timeout. **Phase 3 therefore ends the streams explicitly**: restart
  the application process, which is blunt, reliable, already a deploy primitive, and costs
  every other user one automatic `EventSource` reconnect. Do not talk yourself out of it
  because the payload is only metadata — "their access was revoked" being false for half an
  hour is the claim that matters, and it is the same argument `SseRegistry.disconnectUser`
  carries for the API path.
- **Optimistic locking.** `issues.version` is a JPA `@Version`. A raw `UPDATE issues SET
  assignee_id = NULL` that does not bump it lets a browser tab holding a stale entity save the
  old assignee back with no conflict — silently undoing an erasure step. The runbook writes
  `version = version + 1`. (`issues.position` is untouched; `issues.issue_seq` on `projects`
  is `updatable=false` and is never written here.)
- **Idempotency.** Every phase is re-runnable. The scrub is a no-op on an already-scrubbed row
  (it writes the value the row already holds), and §5.1's self-referential predicate is both the
  pre-flight's detector for "this account has already been erased" and the test the ledger
  replay (§8) uses to skip work.
- **One transaction per workspace, not one for everything.** Deleting a large workspace and
  scrubbing the account in a single transaction holds locks on `workspaces` for the duration.
  Take the workspace deletions one at a time; the scrub is its own short transaction, last.
- **`FOR NO KEY UPDATE`, never `FOR UPDATE`, on `workspaces`** if the operator locks anything
  there — a plain `FOR UPDATE` blocks every FK child insert in the tenant.

### 5.5 Identity verification

An erasure request anyone can send for anyone else is an account-deletion oracle *and* an
account-deletion weapon. Two facts settle the design:

1. **Sending mail from an address proves nothing** — it is trivially spoofable, and no
   existing flow in this product trusts it. Every flow that establishes control of an inbox
   does so by **sending a secret to it**.
2. **The message the operator receives carries no evidence of where it came from.** By design
   (§9.2) there is no request row and no token, so what arrives is an ordinary SMTP message
   and **every field in it is forgeable, including the ones that look like proof.** The
   `mailto:` the Account page composes embeds `Account:` and `User id:` — and both of those
   are readable by any co-member of any workspace the subject belongs to, because
   `WorkspaceMemberResponse` returns `userId` and `email` to the member list. A colleague can
   therefore compose a byte-identical, correctly-keyed request. **The template converts
   "well-formed" from weak evidence into an attacker-reproducible artefact**, so a
   well-formed message must never be read as a corroborating signal. There is no "confirm the
   session origin" step because there is no signal that could confirm it.

**Decision — one identity check, and the affordance's placement:**

- The affordance is authenticated-only. It is never linked from the landing page, the login
  page or the legal pages, and there is no unauthenticated "request deletion" form. (Which
  also means the *product* cannot be used to probe whether an address has an account — see
  the intake reply below for the mailbox side of the same oracle.)
- **The only identity check is a confirmation code sent to the address stored on the account
  and returned from it.** Never to the `Reply-To` or `From` of the request; never to an
  address named in its body. That single rule defeats both spoofing and the oracle: an
  impostor who has neither the inbox nor the session gets nothing but a mail the real owner
  will notice. **No phase after the read-only pre-flight begins without the code back.**

**And the check binds one inbox to one row, so the row it verifies is the row that gets
destroyed.** This is the half an earlier draft left out, and leaving it out was a complete
exploit rather than a gap. The request carries **two independent identifiers** — the `Account:`
address and the `User id:` line — and a co-member of any of the subject's workspaces can read
both off the member list, so an attacker can pair *their own* address with *the victim's* id.
Verify the address, mail the code to it, receive it back correctly, mark identity proved, then
type the id from the message into the procedure, and every downstream guard agrees with itself
while scrubbing the wrong person and deleting their solo workspaces.

- **The subject is whichever row holds the address the code was returned from.** It is obtained
  by `SELECT`, once, and it is the only way `:uid` may be produced:

  ```sql
  SELECT id AS uid FROM users WHERE lower(email) = lower('<address the code went to>') \gset
  ```

- **The `User id:` line is never typed into anything.** It is a hint about which row to look up
  first, and nothing more. If it names a *different* row than the address resolves to, that
  disagreement is itself the signal: **proceed against neither**, treat the request as
  unverified, and answer it with the fixed reply below.
- **The code goes out in a newly composed message addressed from `users.email`, never as a
  reply.** State it against the mail client's default, because that is what it has to beat: the
  operator is already in a reply flow — the fixed acknowledgment below *is* a reply — and one
  more press of "Reply" addresses the forged `From`/`Reply-To` and hands the code to the
  attacker with every other control still nominally in place. Compose new; paste the address
  from the row you looked up; do not use the thread.

**The code, specified — because it is the sole control over an irreversible destruction.**
"Short random code" is how a six-digit PIN ends up in the reply, and the return channel is
spoofable too, so an attacker can guess repeatedly while the victim dismisses one unexpected
mail as spam. The properties, in the shape a reviewer can check:

- **At least 10 characters from a CSPRNG**, drawn from a mixed alphabet — never a digit-only
  PIN, never a word, never derived from the user id, the address or the date. `openssl rand
  -base64 12` is a fine generator; so is any password manager's random string.
- **Single use, one live code per request.** Issuing a second invalidates the first, so a
  reply quoting an old code is a failed attempt rather than a second chance.
- **An internal expiry** the operator keeps. It is deliberately an operational rule and not
  product copy, because a duration in the policy is HD-192's to state (§4.2).
- **Three returned attempts, then abandon the request.** An attempt is **a message that quotes
  a code** — right or wrong. A message that quotes none is not a guess and cannot be counted as
  one: `From` is spoofable, so counting "everything the operator receives" hands any co-member a
  denial-of-service against a legitimate request in three forged messages, and an uncapped
  reissue loop turns that into a treadmill the real owner can never finish. A code returned from
  an address other than the one it was sent to **is** a failed attempt, not a clerical error.
- **Reissue at most once, then stop looping and change channel.** If a second issued code also
  fails its three attempts, the request is not answered by a third code — something is wrong
  that a fourth mail cannot fix (a hostile third party, a compromised inbox, or a subject who
  has lost access to the address). Escalate to a channel the attacker is not on: the workspace
  Owner of a workspace the subject belongs to, or another contact the account already carries.
  Never a channel supplied by the request itself.
- **Never echoed** — not in the reply, not in the ledger, not in the ticket.

The code is generated and checked by the operator out of band. **No new token table** — the
existing `password_resets` / `email_verifications` tables are for their own flows and
reusing one would make a deletion confirmation redeemable at a different endpoint.

**One fixed reply to every incoming request, whether or not the address has an account.**
Otherwise the privacy mailbox becomes the account-existence oracle the automated side closes:
an operator who helpfully answers "we have no account for that address" has answered the
question the product refuses to answer. The reply is:

> *"If an account exists at this address, a confirmation code has been sent to it."*

Send it for an unknown address too, and send nothing else. The internal handling differs; the
outgoing message does not.

---

## 6. Data model impact

**No migration in 0.18.0.** Every verdict below is expressible with existing columns; the
marker for "this row is a tombstone" is §5.1's **self-referential** address — the row's own id
inside a reserved domain — which is a queryable predicate today and forgeable by nobody.

**And the 1.0.0 column is worth more than convenience, which is the part to carry forward.**
When the self-serve endpoint ships, add `users.deleted_at TIMESTAMPTZ` (nullable, additive, no
default) rather than a new `UserStatus` value — a new enum constant would silently change the
meaning of every existing `status`-based branch, `DISABLED` included, while a new column changes
nothing that does not ask for it. A column also ends the whole class of problem the predicate
has to work around: "was this row erased?" stops being an inference from a value somebody else
might be able to write, and becomes a fact only the erasure path sets.

### 6.1 Every table referencing a user, and its verdict

`ON DELETE` column states what the schema declares **today**. "Anonymise (by scrub)" means
*no statement is executed at all* — the reference keeps pointing at the scrubbed row, which is
§5.1's entire payoff.

| Table.column | `ON DELETE` today | Verdict | Note |
|---|---|---|---|
| `users` (the row) | — | **Scrub** | §5.1. One `UPDATE`. |
| `oauth_accounts.user_id` | CASCADE | **Delete** | Holds the provider identity plus access/refresh tokens. |
| `refresh_tokens.user_id` | CASCADE | **Delete** | Phase 3, first — kills live sessions. |
| `email_verifications.user_id` | CASCADE | **Delete** | Token hashes bound to the address. |
| `password_resets.user_id` | CASCADE | **Delete** | Same. |
| `workspaces.created_by` | NO ACTION | **Anonymise (by scrub)** | A workspace is not owned by its creator's account (ADR-0024). |
| `workspace_members.user_id` | CASCADE | **Delete** | Only after §5.3 resolves ownership. An access grant for a non-existent person is a smell, and a surviving row shows a ghost in every member list. |
| `workspace_invites.invited_by` | NO ACTION | **Anonymise (by scrub)** | |
| `workspace_invites.email` | *(not an FK)* | **Delete rows** where `lower(email) = lower(<address>)` | Invitations *addressed to* the subject carry their address, accepted or not. Safe to delete: V21 exists precisely so no throttle depends on these rows. |
| `projects.created_by` | NO ACTION | **Anonymise (by scrub)** | |
| `project_members.user_id` | CASCADE | **Delete** | Only after §5.3(d). |
| `issues.reporter_id` | NO ACTION, NOT NULL | **Anonymise (by scrub)** | The team's record of who filed what. Deleting it would delete the issue. |
| `issues.assignee_id` | NO ACTION, nullable | **Clear to NULL**, `version = version + 1`, one `issue_history` row each | Matches what `WorkspaceMemberService.remove` already does on offboarding — consistency with the existing path beats novelty. Work assigned to a dead account is never done and never triaged. |
| `issue_comments.author_id` | NO ACTION | **Anonymise (by scrub)**; body retained | Erasure of a person is not deletion of the team's discussion. |
| `comment_mentions.user_id` | CASCADE | **Delete** | The structured link naming the subject. The `@Name` *text* in the body is content — §6.5. |
| `issue_history.changed_by` | NO ACTION | **Anonymise (by scrub)** | |
| `issue_history.old_value` / `new_value` | *(not FKs)* | **Rewrite** to `Deleted user` where the value equals the subject's display name, **scoped to the pre-flight's workspaces** | `IssueService` writes *display names* here for assignee changes. This is product-generated text about the person — §6.5's rule. |
| `issue_attachments.uploaded_by` | NO ACTION | **Anonymise (by scrub)**; the object is retained | The file is the workspace's content, on the same basis as a comment. It goes only when its whole workspace goes (§5.3(c)). |
| `notifications.user_id` | CASCADE | **Delete** | The subject's own inbox. (ADR-0009's "revocation hides rather than deletes" is about losing *access*; erasure is not that.) |
| `notifications.title` | *(not FK)* | **Rewrite** the leading display name to `Deleted user`, **scoped to the pre-flight's workspaces**. Never a delete, never a substring match | A title is composed by the application with the actor's name at the front, so the name is erasable without touching the row that another member's inbox owns. Rewriting shortens the value, so `VARCHAR(255)` is never at risk. |
| `notifications.body` | *(not FK)* | **Retain untouched** | A body may carry text a member wrote — the mention path copies up to 120 characters of the comment — and §6.5 forbids editing that. Deleting the row instead would destroy a third party's record of their own work over a name that merely appears in it. |
| `saved_filters.owner_id` | NO ACTION, NOT NULL | **Delete where `shared = FALSE`; retain + anonymise where `shared = TRUE`** | A private filter is the person's own tooling and nobody can ever see it again. A shared one is workspace tooling the team uses. `uq_saved_filter_owner_name` is per-owner, so keeping the shared ones has no constraint risk. |
| `labels.created_by` | SET NULL | **Anonymise (by scrub)** | The `SET NULL` never fires, because the row is never deleted. It was written for the delete-the-row design and is now belt-and-braces. |
| `components.lead_id` | SET NULL | **Anonymise (by scrub)** — no statement runs | Reversed in review, and the reversal is the whole point of the column. `WorkspaceMemberService`'s javadoc decides this explicitly for offboarding — "a lead who merely leaves the workspace keeps the row" — because `ComponentService.autoAssignee` re-checks membership and skips a departed lead, so the module *degrades by design*. Clearing it would be **the only path in the product able to produce `auto_assign = true` with no lead**, and `ComponentService.update` calls `requireAutoAssignHasLead` on the *effective* pair, so afterwards a plain rename of that component answers 422. The `SET NULL` never fires either, because the row is never deleted (same as `labels.created_by`). The scrub already anonymises the reference, which is what erasure requires. |
| `sprints.created_by` | SET NULL | **Anonymise (by scrub)** | |
| `sprint_scope_events.actor_id` | SET NULL | **Anonymise (by scrub)** | The event is a fact about the sprint and must outlive the account, as V18 says. **A reader of this table must never inner-join `issues`** — `issue_id` is nullable by design. |
| `mail_send_events.sender_user_id` | *(no FK at all)* | **Set to NULL** | §6.2. |
| `mail_send_events.recipient_email` | *(no FK)* | **Delete rows** where `lower(recipient_email) = lower(<address>)`. **The predicate is the address and never `recipient_key`** | §6.2, which carries the argument. One `recipient_key` can span two different accounts, so keying the delete on it destroys a third party's rows. The fold is `lower()` for the same reason the two sibling address sweeps use it: the column's own writers happen to store what `users.email` held, so an exact match is correct *by another table's invariant* rather than by this statement's own terms — and there is no index on it to lose. |
| `failed_email.recipient` | *(no FK)* | **Delete rows** where `lower(recipient) = lower(<address>)` | HD-232's table. The one with the longer window, so the one that matters. |

**Retained untouched, as a category:** every table that names no person — the taxonomy catalog
and its sets, `roles` / `role_permissions`, `versions`, `issue_versions`, `issue_labels`,
`issue_field_values`, `workflow_*`, `flyway_schema_history`. They are removed only as a
consequence of a workspace deletion under §5.3(c), by cascade.

**One table is in neither the list nor that category, because it exists on some installs and
not others: `notifications_unresolvable_v20`.** V20 creates it — `CREATE TABLE … AS SELECT *
FROM notifications WHERE workspace_id IS NULL` — **only when that count was non-zero**, so a
clean install has never seen it and an upgraded one may hold it forever. It is a full copy of
notification rows: `user_id`, `title`, `body`, `link`. Nothing in the application maps or reads
it, which is exactly why it is easy to miss here and impossible for any later verdict to reach
by category ("every table with no user reference" is false about it, and so is "every table an
entity maps").

**Verdict: probe for it, and prefer dropping it.** V20's own comment and `docs/self-hosting.md`
both tell the operator to read it, report the split on the issue tracker, and then `DROP` it —
so the erasure's recommendation is the product's existing one, brought forward: if the operator
has already answered the question it was created for, `DROP TABLE notifications_unresolvable_v20`
and the verdict is discharged for every future erasure at once. If they have not, and want to
keep it, it is handled in phase 4 as an ordinary copy of `notifications`: **delete rows where
`user_id = :'uid'`**, and leave titles alone — its rows are precisely the ones whose workspace
could not be determined, so there is no `:ws_all` to scope a name-keyed rewrite by, and §3's
rule is that an unscopable name sweep does not run. The residual is then a display name inside
an operator-only artefact with no reader and a standing instruction to drop it, which is
recorded rather than swept.

**And the general form, because the next such table will not be this one:** a quarantine or
backfill artefact created *conditionally* by a migration is invisible to every verdict written
as a property of the schema, because on the install that is being reasoned about it does not
exist. The pre-flight therefore **probes** rather than assumes (`to_regclass`), and any
migration that leaves a conditional copy of a user-bearing table owes this document a row.

### 6.2 The mail logs, and the one split that is not obvious

`mail_send_events` is not a log — it is the **state behind an anti-harassment ceiling** whose
whole point (V21) is to outlive the rows it describes. A naive "delete every row mentioning
this user" resets, for every address the subject ever mailed, the per-recipient daily cap that
protects *those* people.

**Decision, split by which side of the mail the subject is on:**

- **Subject as recipient** → `DELETE`, matched on **`recipient_email` and nothing else**.
  That column holds the subject's own address, it is personal data, and the ceiling it holds
  protects an inbox whose owner is leaving. (A stranger who was flooding that address regains
  their budget against an address that is about to stop existing. Accepted.)

  **Why `recipient_key` must not be in the predicate, even though it looks like the more
  thorough choice.** `recipient_key` is `MailAddresses.throttleKey(...)`, which folds `+tag`
  and Gmail dots deliberately, because a ceiling has to count *inboxes* rather than spellings.
  The consequence is that one key can belong to **two different accounts** — `alice+work@…`
  and `alice@…` are separate registrations that fold to one key — so a delete keyed on it
  destroys a third party's rows and, worse, resets the anti-harassment ceiling that was
  protecting *them*. The residue this leaves is a row addressed to a literally different
  address that happens to reach the same inbox; the harm it avoids is irreversible and lands
  on somebody who asked for nothing. **Do not "tidy" this into a key match to make it agree
  with some other sweep** — the asymmetry is the decision, and the direction of harm is what
  decides it (which is the same reasoning `throttleKey`'s own javadoc applies in the opposite
  direction for ceilings, and the invite-redemption path applies in this one).
- **Subject as sender** → `UPDATE … SET sender_user_id = NULL`, keeping the row. That column
  is a forensic breadcrumb naming the subject; the row's counted key (`recipient_key`) belongs
  to somebody else and must survive. V21's own indexes confirm the sender column is never a
  ceiling predicate, so nulling it costs no control.

`failed_email` has only a `recipient` column and no user reference: **delete the matching
rows.** Nothing reads it for a decision, so there is no ceiling to preserve.

Both are execution details of the mechanism. **Their retention windows —
`app.mail.dead-letter.retention-days` (90) versus `app.invites.event-retention-days` (7) — are
HD-232's subject and HD-192's disclosure, and are deliberately not stated in any user-facing
copy this ticket ships.**

### 6.3 Application logs

Not swept per subject. Logs are keyed by time and request, not by subject; a per-subject
rewrite of a log stream destroys the integrity of the very artefact kept for security. They
expire on their own schedule, and **the length of that schedule is HD-192's number, not
this ticket's.** Prometheus and Grafana hold aggregates with deliberately bounded cardinality
and no identifiers — nothing to sweep.

### 6.4 What is deliberately *not* created

- **No mapping from the scrubbed id to the old address.** It would be a durable store of the
  exact datum the procedure exists to destroy, and it would make the result pseudonymisation.
- **No suppression list of erased addresses.** Same reason. The consequence is intended and
  should be stated to the subject on request: **after erasure the address is free, and the
  person may sign up again and receive a brand-new account.**
- **No ability to answer "did you erase me?" by email — once phase 10 has run.** After the
  scrub the database cannot resolve the address, and the ledger (§8.2) is keyed by `user_id`
  only. **The intake mailbox can, until it is emptied**: the request thread holds the address
  and the `User id:` line together, which is the mapping this section exists to say does not
  exist. Phase 10 destroys the thread, the code message and its Sent copy for exactly that
  reason. An operator whose mail retention or legal-hold policy forbids that deletion does not
  get to keep the sentence: they record the mailbox as a residual beside §13's and say so on
  the ticket.

### 6.5 The rule that decides every "text mentions their name" case

> **Erase text the product generated about the person. Never edit text *another* person wrote.**

It sorts every case cleanly: `issue_history`'s assignee values and a notification's *title* are
strings the application composed from a display name, and the controller may unmake its own
output. A comment body containing `@Anna Petrova` — mentions are stored as plain display-name
text, resolved by a longest-prefix scan over member names — is another member's writing, and
rewriting it silently falsifies their record. A notification *body* is on the writing side of
that line whenever it carries copied text, which is why it is left alone entirely.

**The rule has a second half, and the first half is unsafe without it: erase by rewriting the
value, never by deleting the row.** Both name-keyed sweeps run against rows that belong to
*other people* — an audit trail the team reads and an inbox item another member owns — and a
display name is chosen by its holder and validated only against control characters, so any
predicate built from it can match far more than intended (`%`, `_` and a two-character name
all pass registration). A rewrite that over-matches costs legibility in a row that still
exists; a delete that over-matches destroys a third party's data with no undo. That is the
whole of open question 3's fail-safe claim, and it is true only in the rewrite direction.

**The word "another" is load-bearing, and the rule was written without it for a round.** The
one text-editing case that is plainly *permitted* is the subject's **own** comments — "delete
the address I pasted into a comment" is a real request, it is their writing, and no third
party's record is falsified by honouring it. It is not part of the standard sweep, because the
procedure cannot know which text the subject means and a blanket rewrite of every comment they
ever wrote destroys a team's discussion over a datum nobody named: it is done **on a specific
request that names the comment**, by hand, and recorded. Everything about *another* member's
writing stays out of bounds.

**The residual, stated plainly because it is the honest position:** a person's name can remain
in other members' comments after erasure. That is disclosed in §4.3's copy. A data subject who
wants their name removed from another person's writing is making a different request, which
the operator handles case by case with the workspace's owner. **Do not build a
content-editing power for it** — see open question 3.

---

## 7. Attachments

Attachment rows cascade from `issues` → `projects` → `workspaces`; the **objects do not**.
Two consequences, and the second is the trap.

1. **On an ordinary erasure, nothing happens to attachment objects.** The file is the
   workspace's content, retained on exactly the same basis as a comment (§6.1). The
   `uploaded_by` reference is anonymised by the scrub. `users.avatar_url` is a URL string, not
   a stored object — storage keys are only ever `ws/{wsId}/issues/{issueId}/{uuid}` — so there
   is no avatar blob to delete.
2. **On a solo-workspace deletion (§5.3(c)), the objects must be collected *before* the rows
   go.** `storage_key` is server-generated and **the original filename lives only in the
   database**, so once `issue_attachments` is deleted the objects are unfindable through any
   application path and undeletable except by prefix scan. The runbook therefore `\copy`s the
   key list to a file in phase 1 and deletes the objects in phase 7, after the rows — and
   **derives the prefixes to delete from that file rather than from a retyped workspace id**,
   because the object store has no membership row to check a prefix against and no undo to
   reach for.

**Both backends, because the procedure must exist for self-hosters too:**

- **`LocalFileStorage` (DC default).** Keys are paths under `app.storage.local.base-dir`
  (default `./data/attachments`). Delete the whole `ws/{wsId}/` subtree for each deleted
  workspace, then verify it is gone. Nothing versions anything; a deleted file is deleted.
- **`S3FileStorage` (Cloud default).** The attachments bucket is **versioned**, with a 30-day
  noncurrent-version expiry (`ops-prod-hardening.md` §6.1 layer 4). A plain `DeleteObject`
  writes a delete marker and leaves the prior version fully readable to anyone holding a
  version id. **Delete every version explicitly** — `list-object-versions` for the prefix,
  then `delete-objects` with each `VersionId`, including the delete markers — and then re-list
  to prove the prefix is empty. Relying on the lifecycle rule to finish the job would make
  erasure depend on a retention window, which is precisely the thing this ticket may not
  assert.
- **The backup bucket is never touched.** The instance cannot delete or overwrite there by
  design (ADR-0012), and the owner deliberately does not. §8 is why that is safe.

---

## 8. Backups, and the resurrection trap

### 8.1 The problem, stated as it actually is

Daily `pg_dump` objects live in a write-once bucket the instance can neither read nor delete,
expiring by lifecycle rule; EBS snapshots cover the box; the attachments bucket versions its
objects. So **a deleted account survives inside backup objects for a bounded period, and a
restore taken from before the erasure brings the person back** — email, password hash,
memberships and all. Editing a `pg_dump` custom-format archive is not possible in any sense
worth relying on, and making the backup objects mutable to permit it would destroy the
property that makes them backups.

**Decision: backups are never edited. Erasure is made durable by replay.** (ADR-0025.)

### 8.2 The erasure ledger

An append-only record, held **outside the database that gets restored**, containing per
erasure:

`erased_at` (UTC) · `user_id` · `request_id` (an opaque id the operator assigns) ·
`workspaces_deleted` (ids) · `workspaces_retained` (ids) · `ownership_transfers`
(workspace id → promoted `user_id`) · `admin_appointments` (project id → appointed `user_id`)
· `attachment_keys_deleted` (count, with the key list in a sibling file) · `operator`

**`attachment_keys_deleted` is a SUM and the key list is a UNION, because 1(e) runs once per
round.** A b′ close-out re-runs it for the workspaces that have just released, and it does so
once per resolution, so a multi-workspace escalation produces `erase-<request_id>-keys-1.txt`,
`-2.txt` and so on. Every one of them is uploaded, the count is their total, and none of them is
merged into or overwritten by another: an object version is not restored by a database restore,
so a round's key file is the only record that that round's objects were deleted, and a file
replaced by a later round's is a deletion with no evidence left that it happened.

The two appointment fields are there because **the replay has to redo the governance, not only
the deletion.** A dump taken before the erasure restores the subject as the workspace's only
Owner, and the successor the operator chose is a fact that exists nowhere in that dump; without
it the replay either strands the workspace or invents a second, different Owner. They carry
ids, never names or addresses.

**It contains no email address, no display name and no IP.** A ledger carrying the address
would be a permanent store of exactly what was destroyed — the failure mode that turns a
compliance artefact into a breach.

**Where it lives: `s3://<backup-bucket>/manual/erasures/`.** That prefix is already carved out
of the daily lifecycle rule (`ops-prod-hardening.md` §6.1), and the instance can neither read,
overwrite nor delete anything in the bucket. Owner credentials can read it. Keeping it inside
the application database would defeat its purpose: restoring a pre-erasure backup would
restore a pre-erasure ledger.

**And because nothing expires `manual/`, the entry needs its own end.** No lifecycle rule
matches that prefix — deliberately, and `ops-prod-hardening.md` §6.1 is written to keep it that
way — so an entry written today is otherwise permanent, while the *reason* it exists expires:
an entry is replayable only against a dump that predates the erasure, and no such dump survives
the `daily/` retention window. There is no disclosure objection to the fields themselves
(`ownership_transfers` and `admin_appointments` carry ids of people who were *promoted*, not of
the person erased), but a permanent, growing map of who was handed which workspace and which
project, in one place, is a governance graph nobody chose to keep. **Past the longest backup
horizon in §6.1, an entry may be pruned to `erased_at` / `user_id` / `request_id` / `operator`,
and its `-keys-<n>.txt` siblings deleted, all of them** — accountability survives (that this
erasure happened, on
this date, by this operator, for this request), and the replay payload goes when it stops being
replayable. The mechanism and the exact horizon are in `ops-prod-hardening.md` §6.7, beside the
gate that consumes it; pruning is an owner action, because the instance holds no `DeleteObject`.

### 8.3 The replay

**`docs/ops-prod-hardening.md` §6.7 is the gate, and it is written** — this section is the
argument for it, not a substitute. In short: after restoring a database dump into the
production instance, and **before the application is allowed to serve traffic**, re-apply every
ledger entry whose `erased_at` is later than the restored dump's timestamp. A row whose account
is already erased (§5.1's predicate) is skipped, which is what makes the replay idempotent and
safe to re-run.

**A replay is a run of §11, not a subset of it, and the order is the same order.** The one
tempting abbreviation — "phases 3–6 and 8, then verify" — puts the scrub *before its own gate*
on a database that is about to serve production traffic, which is the one context where a
missed row can never be found again: after phase 8 there is no address and no name left to
search for, and unlike a live erasure nobody is watching. So:

1. The entry's `ownership_transfers` and `admin_appointments` (phase 2's outcome, which no
   pre-erasure dump can contain).
2. **§11 phase 1 in full, read-only, counts included** — they are not paperwork: what protects
   a name-keyed sweep from over-matching a namesake is a number read *before* it ran and a probe
   that names the colliding accounts, neither of which any later phase can reconstruct; and a
   replay is precisely the moment (an incident, out of hours, one person) when nobody counts
   first. The subject here is the ledger's `user_id`, which is the one place `:uid` is *not*
   derived from a verified address (§5.5) and needs no such derivation: the ledger is our own
   record, not a message from a stranger. `:addr` and `:name` are read from the restored row.
3. Phases 3–6.
4. **Phase 8a's checks, until each reads the expected value 8a itself states** — zero for the
   address-keyed ones, and for the name-keyed ones **the formula written at 8a**, computed from
   this replay's own 1(g2)/1(g3) figures and read in both directions per phase 9's general
   reading. Read it there; it is not restated here. It was, once — as a plain 1(g3) out-of-scope
   total — and it went stale inside a single commit when 8a grew its own-inbox subtraction, so
   this step told a 2am replay that a correct run had failed. A formula written in two places is
   a formula that disagrees with itself, and the copy the operator finds first wins.
   *This replay's own* figures, recomputed against the restored database: the restored copy is a
   different install-state, so a total carried over from the original erasure is an expectation
   nothing here can meet, and an expectation nothing can meet is what sends an operator — alone,
   out of hours — to widen a statement.
5. Phase 8b (the scrub), then phase 9.

Phase 7 has nothing to do (below), and phase 3's SSE step is already satisfied — `app` is
stopped, which is the property that step buys during a live erasure.

**Attachment objects of a workspace deleted under §5.3(c) do not come back** (the object store
is not restored by a database restore), so that part of the replay is a no-op and the ledger's
key list is the record that it was done.

`ops-prod-hardening.md` §6.5 is a **drill** against a scratch container that serves nobody, so
no replay applies to it; that document says so in §6.7, beside the gate, because the failure mode is
somebody reading the drill as the production-restore procedure and skipping the gate that only
the production one carries. **§6.2's "30 days is short enough that a GDPR erasure request needs
no backup surgery at all" is corrected there too** — the retention window is why there is no
*surgery*; it is not why there is nothing to do. The replay is the thing to do, and until it
exists in the ops document the published sentence about re-applying deletions has no mechanism
behind it, which is this ticket's own opening defect in miniature.

---

## 9. What the user sees

### 9.1 The affordance

- **New route `/account`**, authenticated, rendered inside `AppShell`. Account-scoped, not
  workspace-scoped: it is deliberately outside `/w/{wsId}` because it is about the account,
  reads nothing tenant-scoped, and must be reachable when the user has no workspace at all.
- **New user-menu item "Account"** in `NavRail.tsx`, above "System administration".
- Page content: a read-only identity block (display name, email, member-since) and one
  **"Delete account"** section, styled as an ordinary bordered section per `DESIGN.md`,
  using existing tokens — teal `--color-brand`, slate neutrals, no new hex, no red alarm
  chrome for a page nobody has yet acted on.

The Delete account section carries, in this order:

1. Three sentences of mechanism — **the same three sentences as `PrivacyPage` §5**, not a
   second wording: your account record is removed; your work stays with the team and is
   re-attributed to "Deleted user"; a workspace where you are the only remaining member is
   deleted with its contents.
2. "This cannot be undone."
3. A link to the Privacy Policy for the rest.
4. **How to ask**: the configured contact address rendered as text *and* as a `mailto:` link
   pre-filled with subject `Account deletion request` and a body containing the account's own
   email and user id. A copy-to-clipboard control, because a browser without a mail client
   handler turns a bare `mailto:` button into a dead end.

   **Both pre-filled lines are conveniences for looking a row up, and neither is ever an
   authorisation input.** They are readable off any shared workspace's member list, so the
   template makes a *correct-looking* request cheap to forge — and, crucially, lets an attacker
   pair an inbox they control with an id they do not. §5.5 answers that by resolving the subject
   from the verified address alone; nothing on this page can, and nothing on it should try.
5. "We will email a confirmation code to `<the account's email>` before anything is deleted."

**What it does not say:** nothing about how long a reply takes, nothing about how long
deletion takes, no "we will get back to you", no ticket number, no status.

### 9.2 Why there is no form, and no request row

The ticket's own defect is a promise with nothing behind it. An in-app "Request deletion"
button that inserts a row into a `deletion_requests` table is that defect with a migration
attached: nothing watches the table, nothing alerts on it, no SLA governs it, and the user
receives a receipt implying all three. It would also need an admin queue, a state machine, and
a notification path — none of which is in a 3-point ticket and none of which makes the
erasure happen any faster than an email does.

**The rule this ticket must not violate: an affordance may only promise what a rehearsed
procedure delivers.** An email to an address the Privacy Policy already publishes promises
exactly one thing — that the message arrives at an inbox — and that is true today. A code sent
back to the account's address is a step in a procedure §12 rehearses. Everything else is
deferred to the endpoint, where the promise becomes mechanical.

### 9.3 Not hiding it when unconfigured

If no contact address is configured (the DC default), the page still renders in full and the
"how to ask" block reads: *"Account deletion on this installation is handled by its
administrator."* **The section is never hidden.** A deletion affordance visible only where
someone remembered to set a property is unreachable for exactly the operators who did not know
they had to — the same reachability rule the delivery-capabilities work states as Rule C.

### 9.4 Why the operator's power is not an API role

Erasure is destructive, irreversible, crosses tenant boundaries, and has no plausible
delegate. Exposing it as a permission would create a role that can destroy any account in the
install, guarded by the same session model as everything else — a far larger attack surface
than a procedure requiring database and object-store credentials. It stays out of band until
the self-serve endpoint exists, at which point the only actor is the account holder acting on
themselves.

### 9.5 The 1.0.0 endpoint, sketched only

`DELETE /api/auth/me`, authenticated, body `{ "password": "…" }` (or a re-auth step for
OAuth-only accounts), executing §5–§7 in one transaction per workspace, writing the ledger
entry through a `FileStorage`-adjacent sink, and answering `409` with the same shapes the
guards already use — `LastWorkspaceOwnerException` and `StrandedProjectsException` — when
§5.3 cannot be resolved automatically, naming the workspaces and projects the caller must fix
first. Nothing here is decided by HD-193 beyond the data verdicts it inherits.

---

## 10. API & frontend surface

**Backend — one field, one property.**

- `MetaController.MetaResponse` gains `String privacyContactEmail` (empty string when unset).
  `/api/meta` is unauthenticated; the Cloud value is already published on `/privacy`, so this
  exposes nothing new there. A DC operator who sets it publishes it — say so where the
  property is documented, and default it empty.
- **The default is empty in `application.properties` and is not overridden in any
  profile-specific properties file, `application-cloud.properties` included.** The reason is
  concrete rather than stylistic: `docker-compose.prod.yml` defaults `SPRING_PROFILES_ACTIVE`
  to `cloud` and `.env.prod.example` ships `cloud`, so the cloud profile is a
  **self-hoster-reachable** configuration, not a private one. A Cloud address baked in there
  would make every self-hoster who took the default publish *our* address as their own, and
  route their users' erasure requests — with their users' addresses and user ids — into our
  inbox. The hosted deployment's address is one line in **that deployment's own
  `/opt/hamstrack/.env`**, which no deploy syncs. Leave this paragraph in place: the next
  reader will see a property with no per-profile default, read it as an omission, and "fix" it.
- `AppProperties` gains `Privacy(String contactEmail)`. `@Validated` on the
  `@ConfigurationProperties` class is correct and stays (ADR-0018) — it validates binding at
  startup, where no dispatch is involved. **Do not put `@Validated` on `MetaController`.**
- `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` all move together (`api-docs-sync`),
  and `npx @apidevtools/swagger-cli validate` must pass.

**Frontend.**

- New `src/main/frontend/src/pages/AccountPage.tsx`; route registered alongside the other
  authenticated non-workspace routes; splat-route paths written absolute.
- `NavRail.tsx` gains the menu item.
- `PrivacyPage.tsx` §5 rewritten per §4.3, `lastUpdated` bumped.
- The meta store/hook that already supplies `termsAcceptanceRequired` and
  `publicSignupEnabled` supplies `privacyContactEmail`; rendering is config-driven (§9.3), no
  build-time constant.
- `npm run build` is the gate — i.e. **`npx tsc -b`**, never `tsc --noEmit`, which
  type-checks nothing in this tree.

---

## 11. The runbook

Written for an operator under time pressure. Run with `psql`, one phase at a time, reading the
output of each before starting the next — **and in one session**, because the working values
live in psql variables that a reconnect loses.

> **A reconnect cannot re-derive them, and the two halves fail differently.** `:addr` and
> `:name` are destroyed by phase 8; **`:ws_all`, `:ws_delete`, `:ws_release` and `:ws_blocked`
> are destroyed by phase 5**, whose last statements delete the `workspace_members` rows every
> one of those queries reads. So an operator who reconnects between phase 5 and phase 6 and
> re-runs the derivation gets `NULL` — silently, with no error, and `IN (NULL)` then matches
> nothing while looking exactly like a clean run. **Phase 1 therefore writes all six values into
> the working file at the moment it derives them, and a reconnect restores them from that file
> with `\set` — never by re-running the derivation.**

> **The ordering is the safety property, and it has two halves.**
> **(1)** Governance is resolved before the **freeze**, every membership row is removed before
> the scrub, and no membership row is removed before governance is resolved. The freeze is
> phase 3, and it is phase 3 — not phase 8 — that first sets `status = 'DISABLED'` and makes
> the subject invisible to every guard's `ACTIVE` filter, after which a project can be stranded
> with no error anywhere (`ProjectAdminGuard`'s class javadoc records this exact direction).
> Gating on the scrub instead protects nothing: by then the harm has been standing for as long
> as the erasure has been running.
> **(2)** Nothing that scopes by a workspace id runs after the row that ties that id to the
> subject is gone. The subject's `workspace_members` row is the evidence every guarded
> workspace statement checks itself against, so the membership deletes are the **last**
> statements of phase 5, after the workspace deletions — not, as an earlier draft had it, in
> the middle of phase 4, where they silently made the self-check on the most destructive
> statement in this document impossible.
> Do not reorder either half.

**Phase 0 — intake.** Record the request and assign a `request_id`.

**Read the incoming message as unauthenticated input.** Every field in it is forgeable — the
`From`, and the `Account:` and `User id:` lines the in-app `mailto:` pre-fills, both of which
any co-member of any workspace the subject belongs to can read off the member list. A
well-formed request is therefore *not* corroboration; it is the shape an impostor's request has
too. There is no "confirm the session origin" step because there is no signal that could
confirm one.

**The message carries two identifiers, and only one of them may be believed after the code
comes back.** Look the account up **by the address**. Send the code to the address **on the row
you looked up**, in a **newly composed message** — not a reply, because you are already in a
reply thread and one press of "Reply" mails the code to the forged `From` (§5.5). Verify the
request for **that row only**. If the message's `User id:` line names a different row than the
address resolved to, that is not a clerical error to reconcile: **proceed against neither**,
answer with the fixed reply, and treat the request as unverified.

Send **one fixed reply to every request**, known address or not: *"If an account exists at this
address, a confirmation code has been sent to it."* Then, if an account does exist, send a
confirmation code meeting §5.5's specification **to the address stored on the account** and wait
for it back. Never to the `Reply-To`, the `From`, or an address named in the body. Three
code-quoting attempts fail the request; at most one reissue, then change channel (§5.5).
**Phase 1 is read-only and may run before the code returns; no later phase may.**

**Phase 1 — pre-flight (read-only). Produce the whole report before changing anything.**

Set the working values **once**, from the database, and never retype them. The only thing typed
by hand is **the address the confirmation code was sent to and returned from**; everything else
is derived, so an apostrophe in a name (`O'Brien`) and a `%` in it are psql's problem rather
than yours — `:'var'` emits a properly escaped literal.

```sql
-- The ONE hand-entered value: the address that proved control of an inbox. NOT the message's
-- `Account:` line as such, and NEVER its `User id:` line — both are attacker-chosen, both are
-- readable off any shared workspace's member list, and the pair need not describe one person.
-- The inbox that returned the code is the only thing this procedure has proof about, so the
-- row it belongs to is the only row it may touch.
\set addr_in 'someone@example.com'

-- The subject is DERIVED from that address. Everything downstream keys on :uid, so this SELECT
-- is what ties every later guard to the inbox that was actually verified.
SELECT id AS uid, lower(email) AS addr, display_name AS name
  FROM users WHERE lower(email) = lower(:'addr_in') \gset
-- No row: nothing to erase (and phase 0 already sent the fixed reply, so say nothing further).
-- Read :uid back and compare it with the `User id:` line in the request. If they DISAGREE, stop
-- and abandon the request — that mismatch is somebody pairing their own inbox with another
-- person's id, and the correct response is to erase nobody.
SELECT :'uid' AS uid, :'addr' AS addr;

-- WRITE THESE INTO THE WORKING FILE NOW, and every workspace list derived below with them.
-- None of them can be re-derived later: phase 5 deletes the membership rows every list query
-- reads, and phase 8 destroys the address and the name. A reconnect restores them from the file
-- with \set; it never re-runs a derivation, which by then returns NULL without erroring — and
-- `IN (NULL)` matches nothing while looking exactly like a clean run.
-- RE-DERIVATION IS PERMITTED BY A PROPERTY, NOT BY BEING ONE NAMED STEP: it is available
-- wherever BOTH (i) phase 8 has not run, so the address still resolves to the subject, and (ii)
-- the membership rows these queries read have not been deleted — that is, before phase 5's two
-- membership deletes, or in a workspace phase 5 deliberately held. It is REQUIRED wherever the
-- database has been observed to disagree with the file's routing, because a list that describes
-- a routing that has changed is worse than no list. Two steps meet both halves today: the b'
-- close-out, which resumes a run that stopped for a 5.3(b') escalation, and phase 5's `DELETE 0`
-- branch, where a workspace routed to 5.3(c) turns out to have gained an un-erased member. A
-- third will meet them one day; the property is what to check, not the pair. Everywhere else —
-- and after those membership deletes, always — restore from the file, because the derivation
-- then returns NULL without erroring.

-- Phase 4 inserts one issue_history row per unassigned issue and therefore has to mint ids.
-- This project's stated invariant is UUID v7 everywhere, PostgreSQL has no built-in generator
-- for it, and `gen_random_uuid()` is v4 — so the runbook builds a v7 rather than making a
-- silent exception to an invariant in the one place nothing reviews. 48-bit millisecond
-- timestamp, version nibble 7, variant nibble 8-b. Prove it here, once, before anything writes:
SELECT substr(x::text, 15, 1) AS version_expect_7,
       substr(x::text, 20, 1) AS variant_expect_8_9_a_or_b
  FROM (SELECT (lpad(to_hex((extract(epoch from clock_timestamp())*1000)::bigint), 12, '0')
             || '7' || substr(md5(random()::text), 1, 3)
             || to_hex(8 + (random()*3)::int) || substr(md5(random()::text), 1, 3)
             || substr(md5(random()::text), 1, 12))::uuid AS x) t;
-- Two exact values, and there is no partial pass: `7`, and one of `8`/`9`/`a`/`b`. Anything
-- else means the expression is wrong, and phase 4 mints its issue_history ids with this exact
-- expression — so a wrong nibble here is a v4 (or worse, a malformed) id written into an audit
-- table under an invariant that says v7 everywhere, which nothing downstream will ever report.
-- Fix the expression before phase 4; do not run it and inspect the ids afterwards.
```

```sql
-- (a) The subject, and whether this account was already erased. The erased test is
--     SELF-REFERENTIAL — the address must name this row's own id (§5.1). A bare
--     `LIKE '%@deleted.invalid'` is a claim about who writes that domain, nothing enforces it,
--     and a live member holding such an address would be read as a tombstone.
SELECT id, email, display_name, status, created_at,
       (email = 'deleted+' || id::text || '@deleted.invalid') AS already_erased
  FROM users WHERE id = :'uid';
--     `already_erased` reads FALSE on a live request, and it does so by construction rather
--     than by luck: this row was found by the address a confirmation code was returned from,
--     and a `.invalid` address can never receive one. TRUE therefore belongs to the §8.3
--     replay, where :uid comes from the ledger instead of from an address, and there it means
--     the restored dump already postdates this erasure — SKIP the entry (§5.4's idempotency).
--     TRUE on a live request means the address in :addr_in was itself a tombstone address:
--     erase nobody, and re-read the request.

--     Does the INSTANCE keep a signable-in system administrator without this account? Phase 3
--     writes `status = 'DISABLED'` and `system_role = 'USER'` in one statement, which is both
--     halves of what AdminUserService.guardLastAdmin answers 409 to; in SQL nothing refuses, and
--     the loss is install-wide rather than tenant-shaped, so no branch of §5.3 can see it (§5.2).
--     Counts the OTHERS on purpose: excluding the subject makes the number mean the same thing
--     before and after the freeze, so a re-run mid-procedure does not silently change its answer.
SELECT count(*) AS other_active_admins
  FROM users WHERE system_role = 'ADMIN' AND status = 'ACTIVE' AND id <> :'uid';
--     This is a FLOOR and not a target, so its two directions are not symmetrical. `>= 1`
--     proceeds and a larger number carries no further signal — there is nothing to read into
--     it and nothing to do about it. `0` stops the procedure at phase 2, with "appoint another
--     administrator first" as the remedy. Nothing in this runbook changes the value, so a
--     number that DROPS between here and phase 2's re-check means somebody else disabled or
--     demoted an administrator while this ran: re-read it rather than trusting the first
--     reading, and do not average the two.

--     Is the subject's tombstone address already taken by a squat (§5.1)? Expect NO ROW. A row
--     is somebody holding `deleted+<subject's id>@deleted.invalid` — free to register, because
--     WorkspaceMemberResponse hands the subject's `userId` to every co-member — and it blocks
--     phase 8b's UPDATE on `users_email_lower_uk`. IT IS READ HERE, IN THE READ-ONLY PHASE,
--     BECAUSE EVERYTHING BETWEEN HERE AND THERE IS IRREVERSIBLE: by 8b the solo workspaces are
--     deleted and the object versions are gone, so discovering it at the scrub means discovering
--     it after the last step that could have been reconsidered. A row found here is resolved
--     before phase 3 — deal with the squatting account first — and costs nothing but a delay.
SELECT id, status FROM users
 WHERE lower(email) = lower('deleted+' || :'uid' || '@deleted.invalid');

-- (b) Every workspace they belong to, routed. Two different liveness tests on purpose (§5.3):
--     "may we destroy this?" counts everyone NOT ERASED; "whom may we appoint?" counts ACTIVE.
--     Neither is `status <> 'DISABLED'` — an administrator writes that value when suspending a
--     live person, and an interrupted erasure leaves it behind too.
SELECT w.id, w.slug, w.name,
       (m.role_id = '00000000-0000-7000-8000-000000000001') AS subject_is_owner,
       -- Owners OTHER than the subject who can sign in. Excluding the subject is what makes
       -- this query re-runnable after phase 3: the freeze sets the subject to DISABLED, and a
       -- count that included them would silently change its answer mid-procedure.
       (SELECT count(*) FROM workspace_members o JOIN users uo ON uo.id = o.user_id
         WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
           AND o.role_id = '00000000-0000-7000-8000-000000000001'
           AND uo.status = 'ACTIVE')                                        AS other_owners_active,
       (SELECT count(*) FROM workspace_members o
         WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
           AND o.role_id = '00000000-0000-7000-8000-000000000001')          AS other_owners_rows,
       (SELECT count(*) FROM workspace_members a JOIN users u2 ON u2.id = a.user_id
         WHERE a.workspace_id = w.id AND a.user_id <> :'uid'
           AND u2.email <> 'deleted+' || u2.id::text || '@deleted.invalid') AS others_not_erased,
       (SELECT count(*) FROM workspace_members a JOIN users u2 ON u2.id = a.user_id
         WHERE a.workspace_id = w.id AND a.user_id <> :'uid'
           AND u2.status = 'ACTIVE')                                        AS others_active
  FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
 WHERE m.user_id = :'uid'
 ORDER BY w.name;
-- Route each row, in this order:
--   others_not_erased = 0                            -> 5.3(c), DELETE the workspace (phase 5).
--   subject_is_owner AND other_owners_active = 0
--        AND others_active >= 1                      -> 5.3(b), transfer to an ACTIVE member.
--   subject_is_owner AND other_owners_active = 0
--        AND others_active = 0                       -> 5.3(b'), escalate BEFORE phase 3, and
--                                                       never delete. These are :ws_blocked.
--   otherwise                                        -> 5.3(a).
-- other_owners_rows > other_owners_active means an Owner exists who cannot sign in (suspended,
-- or an erasure aborted after phase 3). That is not a second Owner; it is a workspace one step
-- from being unadministerable, and it routes to (b) exactly as if they were absent.

-- Derive the workspace lists ONCE, from the same data, into psql variables. Every later
-- statement interpolates these — no id is ever hand-copied into a scoped statement. An empty
-- result yields `NULL`, so `IN (:ws_list)` matches nothing instead of raising a syntax error.
-- EVERY list is written to be used with `IN`, never `NOT IN`: `x NOT IN (NULL)` is NULL, which
-- is falsy, so a NOT-IN exclusion silently deletes NOTHING on the common install where the
-- excluded set is empty. Where an exclusion is wanted, its COMPLEMENT is derived instead —
-- then an empty or mis-derived list under-deletes, which is the recoverable direction.
SELECT coalesce(string_agg(quote_literal(workspace_id::text), ','), 'NULL') AS ws_all
  FROM workspace_members WHERE user_id = :'uid' \gset

-- 5.3(c): nobody un-erased is left besides the subject. These get deleted in phase 5.
SELECT coalesce(string_agg(quote_literal(w.id::text), ','), 'NULL') AS ws_delete
  FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
 WHERE m.user_id = :'uid'
   AND NOT EXISTS (SELECT 1 FROM workspace_members o JOIN users u2 ON u2.id = o.user_id
                    WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
                      AND u2.email <> 'deleted+' || u2.id::text || '@deleted.invalid') \gset

-- 5.3(b'): the subject is the only ACTIVE Owner, somebody un-erased remains, and none of them
-- is ACTIVE. Derived rather than typed, because nothing bounds this to one workspace and an
-- earlier draft wrote the exclusion for exactly one.
SELECT coalesce(string_agg(quote_literal(w.id::text), ','), 'NULL') AS ws_blocked
  FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
 WHERE m.user_id = :'uid'
   AND m.role_id = '00000000-0000-7000-8000-000000000001'
   AND EXISTS     (SELECT 1 FROM workspace_members o JOIN users u2 ON u2.id = o.user_id
                    WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
                      AND u2.email <> 'deleted+' || u2.id::text || '@deleted.invalid')
   AND NOT EXISTS (SELECT 1 FROM workspace_members o JOIN users u2 ON u2.id = o.user_id
                    WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
                      AND u2.status = 'ACTIVE') \gset

-- The COMPLEMENT: every workspace whose membership rows phase 5 may remove. One statement
-- serves the blocked and the unblocked case, so there is no "if something is blocked, edit the
-- DELETE" branch to get wrong under pressure. With nothing blocked this equals :ws_all.
SELECT coalesce(string_agg(quote_literal(workspace_id::text), ','), 'NULL') AS ws_release
  FROM workspace_members
 WHERE user_id = :'uid'
   AND workspace_id NOT IN (SELECT m.workspace_id FROM workspace_members m
                              JOIN workspaces w ON w.id = m.workspace_id
                             WHERE m.user_id = :'uid'
                               AND m.role_id = '00000000-0000-7000-8000-000000000001'
                               AND EXISTS (SELECT 1 FROM workspace_members o JOIN users u2 ON u2.id = o.user_id
                                            WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
                                              AND u2.email <> 'deleted+' || u2.id::text || '@deleted.invalid')
                               AND NOT EXISTS (SELECT 1 FROM workspace_members o JOIN users u2 ON u2.id = o.user_id
                                                WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
                                                  AND u2.status = 'ACTIVE')) \gset

-- Read all four back and compare with (b) before using them, then copy them into the working
-- file. This is the one place a mis-derived list would go unnoticed, because everything
-- downstream trusts it. Expect ws_release = ws_all minus ws_blocked, and ws_delete ⊆ ws_all.
SELECT :'ws_all' AS ws_all, :'ws_delete' AS ws_delete,
       :'ws_blocked' AS ws_blocked, :'ws_release' AS ws_release;
-- Both directions of a mismatch, because they fail differently and only one of them is
-- recoverable. TOO WIDE acts outside what phase 1(b) routed and has no undo: a :ws_release
-- wider than ws_all minus ws_blocked removes the membership row a 5.3(b') escalation is
-- holding and orphans that workspace; a :ws_delete carrying an id outside :ws_all names a
-- workspace for deletion that nothing here routed to 5.3(c). TOO NARROW under-acts: rows are
-- left behind and 8a/phase 9 report them as unexpected numbers, which is the direction to
-- prefer. Either way, RE-DERIVE the list from the queries above. Never hand-edit one of these
-- variables to make a later count agree — a list edited to satisfy a check is a check that has
-- stopped verifying anything.

-- (c) Projects the departure would leave with no ACTIVE administrator (doors 1-3).
--     The workspace predicate is in the WHERE and is load-bearing: admin_roles includes the
--     built-in PROJECT roles, which are shared across every tenant (workspace_id IS NULL), so
--     without it this query's only tenant boundary would be the HAVING on the last line — and
--     the ordinary "move this HAVING into WHERE" edit would turn a governance pre-flight into
--     a report on every project in the install.
--     THE FIRST THREE COLUMNS EXIST SO THE GATE CAN BE READ OFF THIS REPORT. Phase 2's gate is
--     a PARTITION of this result, not a row count: a project is stranded only if its workspace
--     is going to survive the erasure AND is not held by an escalation. So each row carries its
--     workspace and both of the fates that excuse it —
--       held_by_escalation: a b' workspace has no ACTIVE member to appoint, so its projects
--         stay in this result by construction until the escalation closes (see below).
--       workspace_deleted:  a 5.3(c) workspace is deleted whole in phase 5, taking its projects
--         with it. THIS IS THE COMMON CASE, NOT AN EXOTIC ONE — DemoDataService seeds every
--         account a solo workspace containing a project, whoever creates a project holds the
--         Project admin row in it, and the subject is that project's only ACTIVE admin. An
--         unqualified "zero rows" gate is therefore unsatisfiable on a shipped install, and
--         the only keystrokes that turn it green — archive the project, or appoint somebody
--         into a workspace that is about to be deleted — are both damage.
--     Without the workspace and the two fates on each row, the operator can only partition this
--     by hand-typed ad-hoc query — the retyped id phase 7 forbids for the same reason — and the
--     cheap reading under pressure ("some rows came back, they must be the demo one") passes
--     the gate over a genuinely stranded project in a workspace that is about to SURVIVE, which
--     is the orphaning §5.3 exists to refuse. Both flags FALSE sorts first, so every row that
--     must stop the run is at the top of the output. Both are coalesced because an empty list
--     interpolates as `NULL` and `x IN (NULL)` is NULL rather than FALSE.
--     THE ADMIN COUNT EXCLUDES THE SUBJECT, for the reason 1(a) and 1(b) already give in as
--     many words. All three of these queries are read on BOTH sides of the freeze — 1(a) at
--     phase 2's re-check, 1(b) in full at the b' close-out's step 1, 1(c) at both — and the
--     other two were written for it while this one was not. That is the whole defect; it is
--     one line of arithmetic and it has been excused twice with a new flag instead.
--     Counting the subject makes the predicate ask "is the subject one of at most one
--     ACTIVE administrator" before phase 3 and "is there at most one ACTIVE administrator
--     BESIDES the subject" after it — the same SQL, two different questions, and the second is
--     satisfied by a project that has just been REPAIRED. At close-out step 3 an ACTIVE Team
--     lead has been appointed, the subject is DISABLED since phase 3 and so is not counted, the
--     count is 1, `<= 1` holds, and `bool_or` is still true because step 4 has not yet removed
--     the held row — so the gate would stop the run over a project it fixed one line earlier,
--     with every reachable repair forbidden and one of them (deleting the held project_members
--     row) exactly what step 4 is about to do. Excluding the subject and asking for `= 0`
--     states the property directly — nobody else here can sign in and administer this — and it
--     then reads the same at phase 1, at phase 2, at close-out step 3 and at phase 9.
--     It changes NO row at phase 1 on a live request: the subject is ACTIVE there and is
--     counted by construction (`bool_or` requires their own row), so `<= 1` already meant "no
--     OTHER ACTIVE administrator". On a subject who was already suspended when they asked it is
--     strictly narrower and strictly more correct — the old form reported a project that still
--     had one administrator who could sign in.
WITH admin_roles AS (
    SELECT r.id FROM roles r
      JOIN role_permissions rp ON rp.role_id = r.id
     WHERE r.scope = 'PROJECT'
       AND rp.permission = 'project.member.manage'
       AND rp.own_only = FALSE
       AND (r.workspace_id IS NULL OR r.workspace_id IN (:ws_all))
)
SELECT p.workspace_id,
       coalesce(p.workspace_id IN (:ws_blocked), FALSE) AS held_by_escalation,
       coalesce(p.workspace_id IN (:ws_delete),  FALSE) AS workspace_deleted,
       p.id, p.key, p.name,
       count(*) FILTER (WHERE u.status = 'ACTIVE'
                          AND pm.user_id <> :'uid') AS other_active_project_admins
  FROM project_members pm
  JOIN projects p ON p.id = pm.project_id
  JOIN users    u ON u.id = pm.user_id
 WHERE pm.role_id IN (SELECT id FROM admin_roles)
   AND p.archived_at IS NULL
   AND p.workspace_id IN (:ws_all)
 GROUP BY p.workspace_id, p.id, p.key, p.name
HAVING bool_or(pm.user_id = :'uid')
   AND count(*) FILTER (WHERE u.status = 'ACTIVE'
                          AND pm.user_id <> :'uid') = 0
 ORDER BY workspace_deleted, held_by_escalation, p.workspace_id, p.key;
--     A row with BOTH flags FALSE is the finding: a project that will outlive this erasure with
--     no administrator who can sign in. Those are the rows §5.3(d) appoints a Team lead for,
--     and they are the only rows phase 2's gate counts.
--     A row leaves this result by ONE of two independent facts, and after the freeze they are
--     not the same fact: somebody else who can sign in administers the project (the `= 0` fails
--     — what an appointment does), or the subject no longer administers it (`bool_or` fails —
--     what phase 5, and the close-out's step 4, do). Both are true of a finished workspace; a
--     step that says "the row is gone" should name which one it relied on.

-- (d) Are the INHERITED-administrator doors (6-9) live on this install at all?
--     In the shipped configuration every default is NULL -> Contributor, which does not
--     grant project.member.manage, so this returns nothing and doors 6-9 cost nothing.
--     If it returns rows, resolve them by hand against
--     ProjectAdminGuard.projectsAdministeredOnlyByInheritance before proceeding.
SELECT w.id AS ws, w.project_access_mode, w.default_project_role_id AS ws_default,
       p.id AS project, p.default_project_role_id AS project_default
  FROM workspaces w LEFT JOIN projects p ON p.workspace_id = w.id
 WHERE w.id IN (SELECT workspace_id FROM workspace_members WHERE user_id = :'uid')
   AND (w.default_project_role_id IS NOT NULL OR p.default_project_role_id IS NOT NULL);

-- (e) Attachment keys of every workspace about to be DELETED. Capture BEFORE anything else:
--     once the rows go, the objects are unfindable (filenames live only in the DB).
--     This file is also what phase 7 derives its object prefixes from — the workspace id is
--     never retyped into a command that talks to the object store.
--     THE FILENAME CARRIES A ROUND NUMBER, and `<n>` is `1` here. This step is re-run by the b'
--     close-out — once per resolution, so several times on a multi-workspace escalation — and a
--     fixed name means each re-run OVERWRITES the previous round's keys. That file is not a
--     convenience: object versions are not restored by a database restore, so once phase 7 has
--     run, the key list is the only surviving evidence that those objects were deleted (§7,
--     §8.2). Overwriting it destroys the record of a deletion that already happened, silently,
--     with no query that could notice. Never reuse a round's filename.
--     ONE LINE: \copy is a meta-command and does not continue across a newline.
\copy (SELECT a.storage_key FROM issue_attachments a JOIN issues i ON i.id = a.issue_id WHERE i.workspace_id IN (:ws_delete)) TO 'erase-<request_id>-keys-<n>.txt'
--     If your psql leaves `:ws_delete` uninterpolated here, do not hand-widen the query:
--     paste the ids from the read-back above, then check every line of the resulting file
--     against phase 1(b)'s routing before phase 7 deletes anything with it.

-- (f) Mail-log footprint. Address-keyed, unscoped by the table's shape (§3). lower() on both
--     address columns: an exact match on recipient_email is correct only by ANOTHER table's
--     invariant (what users.email happened to hold when the row was written), and there is no
--     index on it to lose by folding.
SELECT count(*) FILTER (WHERE lower(recipient_email) = :'addr') AS as_recipient,
       count(*) FILTER (WHERE sender_user_id = :'uid')          AS as_sender
  FROM mail_send_events;
SELECT count(*) FROM failed_email WHERE lower(recipient) = :'addr';

-- (g) COUNT WHAT LATER PHASES WILL CHANGE, per workspace, INCLUDING rows outside :ws_all.
--     THE PROPERTY: every statement in this runbook that can change a row in a workspace
--     outside :ws_all is counted here first, before anything changes it. Three kinds exist and
--     only the last is a hazard —
--       * keyed on the subject's own user_id (their inbox, their mentions, their private
--         filters, their memberships): deliberately unscoped, because every row it can reach
--         belongs to the subject. Nothing to pre-count; phase 9 re-checks them by the same key.
--       * keyed on the address (mail logs, invitations): unscoped by the tables' shape and by
--         design — counted in (f) and (h), which is where their out-of-tenant reach is listed.
--       * keyed on a workspace or on a DISPLAY NAME: scoped to :ws_all, and counted below
--         WITH their out-of-scope rows, because phase 9 cannot detect a mis-scoped list on its
--         own — it verifies user_id- and address-keyed facts, and a wrong workspace list
--         produces a perfectly clean report from all of them.
--     Do not write this as "the N statements below are the only ones that can reach another
--     tenant". That sentence was here, it was a count, and it was wrong the moment a statement
--     was added elsewhere in the runbook.

--     g1. Assignments, split by scope. RECORD BOTH TOTALS, including a zero: the in-scope one
--     is what phase 4 clears, and the OUT-OF-SCOPE one is the value phase 9's unscoped count
--     asserts. On a modern install the out-of-scope total is usually 0 — a common shape, not a
--     requirement, and never on its own a reason to change a statement.
SELECT workspace_id, count(*) AS assigned,
       coalesce(workspace_id IN (:ws_all), FALSE) AS in_scope
  FROM issues WHERE assignee_id = :'uid'
 GROUP BY workspace_id ORDER BY in_scope, 2 DESC;
--     Every `IN (:ws_…)` boolean in this pre-flight is COALESCED, and none of them may be left
--     bare: an empty list interpolates as `NULL`, `x IN (NULL)` is NULL rather than FALSE, and
--     under `ORDER BY in_scope` a NULL sorts LAST — exactly where the in-scope rows are, so the
--     bare form files the row under the one heading it does not belong to. An empty :ws_all is
--     not hypothetical: it is what a subject with no workspace_members row and a stray issue or
--     project_members row looks like, which is the legacy shape these queries exist to find.
--     An out-of-scope row is an issue assigned to a NON-MEMBER: possible on any instance
--     predating HD-132, and the reason phase 4's UPDATE is scoped rather than keyed on the
--     user alone. Phase 4 will NOT touch those rows. Decide each one deliberately, with the
--     workspace's owner — do not widen the sweep to "fix" the count.

--     g2. issue_history rows carrying the display name, split by workspace and by scope.
SELECT i.workspace_id, count(*) AS rows_to_rewrite,
       coalesce(i.workspace_id IN (:ws_all), FALSE) AS in_scope
  FROM issue_history h JOIN issues i ON i.id = h.issue_id
 WHERE h.old_value = :'name' OR h.new_value = :'name'
 GROUP BY i.workspace_id ORDER BY in_scope, 2 DESC;
--     Out-of-scope rows are a namesake in another tenant. They stay. In-scope rows are
--     rewritten, not deleted, so an over-match costs legibility and nothing else (§6.5).

--     g3. Notification titles opening with the display name FOLLOWED BY A SEPARATOR. The
--     trailing space is not decoration: the application composes a title by putting the actor's
--     display name at the front and continuing in words, so requiring it costs nothing and
--     removes the whole class of collisions between a name and a LONGER name that starts with
--     it ('Ann' vs 'Anna'). A title that does not have that shape is simply not rewritten,
--     which is the fail-safe direction — a name left in one row, rather than a stranger's row
--     mangled into 'Deleted usera mentioned you'.
SELECT workspace_id, count(*) AS titles_to_rewrite,
       count(*) FILTER (WHERE user_id = :'uid') AS in_subjects_own_inbox,
       coalesce(workspace_id IN (:ws_all), FALSE) AS in_scope
  FROM notifications
 WHERE left(title, length(:'name') + 1) = :'name' || ' '
 GROUP BY workspace_id ORDER BY in_scope, 2 DESC;
--     The third column is what makes 8a's expected number computable instead of approximate.
--     Phase 4 deletes the subject's OWN notifications by user_id, deliberately unscoped (every
--     such row is theirs), so an out-of-scope row that is also in their inbox — a workspace
--     they left, whose notifications ADR-0009 keeps — is DELETED rather than left standing to
--     be counted. 8a therefore expects (out-of-scope total) MINUS (out-of-scope rows in the
--     subject's own inbox). Record both parts; the difference is not an anomaly to explain
--     later, it is the expected value.
--     READ THIS NUMBER BEFORE PHASE 4. A display name is chosen by its holder and validated
--     only against control characters, and two characters is a legal name — so a short or
--     common name matches titles about other people. A three-digit count here, or a count
--     close to the workspace's whole notification table, means the name is a common prefix:
--     stop and hand-inspect the sample before rewriting anything. The sample matches LOOSELY
--     and flags what the tight predicate will actually take, so both errors are visible in one
--     read: rows it would over-take, and rows carrying the name that it will leave behind.
SELECT id, workspace_id,
       (left(title, length(:'name') + 1) = :'name' || ' ') AS will_rewrite,
       title
  FROM notifications
 WHERE left(title, length(:'name')) = :'name' LIMIT 20;

--     g3b. THE COLLISION PROBE, and it is the guard the count above only hints at: does any
--     OTHER member of :ws_all have a display name that begins with the subject's? If this
--     returns a row, the leading-name rewrite WILL touch that person's titles wherever their
--     name is followed by a space ('Ann' erased, 'Ann Smith' still here). Do not run the
--     notification rewrite blind in that case: rewrite by hand-picked notification id, from the
--     sample above. A count cannot tell you this — a workspace where the subject is simply
--     busy produces the same number.
SELECT DISTINCT u.id, u.display_name
  FROM workspace_members m JOIN users u ON u.id = m.user_id
 WHERE m.workspace_id IN (:ws_all) AND u.id <> :'uid'
   AND left(u.display_name, length(:'name')) = :'name';

--     g4. Project memberships the subject holds, split by scope AND by escalation. RECORD ALL
--     THREE GROUPS, one line each and including the zeros:
--       (1) RELEASED — in_scope true, held_by_escalation false. What phase 5 removes.
--       (2) OUT OF :ws_all — in_scope false. Survives; decided by hand with its own owner.
--       (3) HELD BY ESCALATION — held_by_escalation true. Survives until the escalation closes.
--     Three, not two, because phase 9 needs them apart: its project_members expectation is
--     (2) + (3) while an escalation is open, (2) alone at every other reading, and its HIGHER
--     remedy re-adds (3) on its own. Phase 5 removes project_members scoped to :ws_release, so
--     a row in group (2) or (3) survives the erasure BY DESIGN, and phase 9 is a comparison
--     against a number that cannot be made at all if it was never written down.
--     RECORDING ONE OVERALL TOTAL IS NOT A SMALLER VERSION OF THIS — it is the loud failure:
--     phase 9 then reads LOWER than the recorded figure, which this document calls the
--     unrecoverable direction, over rows that are all present and all deliberately held.
--     Out-of-:ws_all rows are usually none (the API cannot produce such a row), but "cannot
--     happen" is what the assignee sweep assumed too. A blocked-workspace row, by contrast, is
--     ORDINARY: whoever creates a project gets a project_members row in it (the built-in
--     Project admin), so the Owner of a workspace that routes to 5.3(b′) normally holds one.
--     A row outside :ws_all is decided by hand with its own workspace's owner; a row inside a
--     :ws_blocked workspace is held until that escalation resolves. Neither is swept, and
--     neither is what an unexpected non-zero later means.
SELECT p.workspace_id, count(*) AS project_memberships,
       coalesce(p.workspace_id IN (:ws_all), FALSE)     AS in_scope,
       coalesce(p.workspace_id IN (:ws_blocked), FALSE) AS held_by_escalation
  FROM project_members pm JOIN projects p ON p.id = pm.project_id
 WHERE pm.user_id = :'uid'
 GROUP BY p.workspace_id ORDER BY in_scope, 2 DESC;
--     Both booleans are coalesced, for g1's reason and with the same consequence: :ws_all is
--     empty exactly when the subject holds a project_members row and no workspace_members row,
--     which is the shape group (2) exists to find, and a bare `in_scope` would sort it in with
--     the in-scope rows.

--     g5. The conditional quarantine table (§6.1). It exists only on installs where V20 found
--     unattributable notifications, so it must be PROBED, never assumed either way. Prefer
--     dropping it — V20's comment and docs/self-hosting.md already tell the operator to, once
--     they have read it — which discharges this row for every future erasure.
SELECT to_regclass('public.notifications_unresolvable_v20') AS quarantine_table;
--     If NOT NULL, count the subject's rows in it (phase 4 deletes them; titles are left
--     alone, because these rows have no workspace and §3 does not run an unscopable name sweep):
--     SELECT count(*) FROM notifications_unresolvable_v20 WHERE user_id = :'uid';

-- (h) Invitations addressed to the subject, by workspace. Note these reach tenants the
--     subject was never a member of, which is why phase 6 keys them on the address and why
--     they are counted here rather than assumed to be inside :ws_all.
SELECT workspace_id, count(*) FROM workspace_invites
 WHERE lower(email) = :'addr' GROUP BY 1;

-- (i) Baselines for the unscoped checks in 8a: every occurrence of the name anywhere in the
--     install, in scope or not. WRITE BOTH NUMBERS DOWN, together with the out-of-scope totals
--     from (g1), (g2), (g3) — and (g3)'s own-inbox split — and ALL THREE of (g4)'s groups.
--     Not one unscoped check in this runbook expects zero: each expects exactly the part of
--     the install this procedure is not allowed to touch, and without the recorded number that
--     assertion cannot be made at all. What is left instead is an operator looking at a red
--     number with one obvious way to make it green, which in every case here is a statement
--     widened out of its tenant or a deliberately held row deleted. Write the numbers down
--     even when they are 0: three phases later, "it was zero so I did not note it" and "I did
--     not run the query" read identically.
SELECT count(*) FROM issue_history
 WHERE old_value = :'name' OR new_value = :'name';
SELECT count(*) FROM notifications
 WHERE left(title, length(:'name') + 1) = :'name' || ' ';
```

**Phase 2 — resolve governance.** Apply §5.3. Prefer the API where one exists (promoting a
member to Owner, appointing a Team lead) — it takes the locks and runs the guards. Where no
endpoint exists (deleting a workspace), it is SQL in phase 5. Appoint only `ACTIVE` accounts: an
appointee who cannot sign in is the stranding this phase exists to prevent, wearing the costume
of a fix. **Write down every promotion and appointment as you make it** — workspace id → new
Owner, project id → new Team lead — because phase 10 puts them in the ledger and a restore
replay has no other way to learn who was chosen (§8.2).

**Do not start phase 3 until phase 1(c) re-run returns *no row whose `held_by_escalation` and
`workspace_deleted` are both FALSE*, every workspace routed to 5.3(b) has a new `ACTIVE` Owner,
every workspace in `:ws_blocked` has been decided, phase 1(a)'s `other_active_admins` reads at
least one, and phase 1(a)'s tombstone probe returns no row.**

**Why 1(c)'s gate is a partition and not a row count.** A project only needs an administrator if
it is going to exist and be reachable after this erasure, and two kinds of row here are neither.
**`workspace_deleted`** is the ordinary one and it is why an unqualified "zero rows" gate cannot
be met on a shipped install: the demo workspace every account is seeded with routes to 5.3(c),
the subject created its project and therefore holds the only Project admin row in it, so the
project appears here at phase 1 and stops the run — while phase 5 is about to delete the
workspace, the project and the row together. An operator facing that gate has exactly two ways
to make it green, and both are damage: archive a live project, or appoint somebody into a
workspace that is being deleted. **`held_by_escalation`** is the other, and it is the harder
one. A b′ workspace has, by its own definition, no other `ACTIVE` member — so §5.3(d)'s
appointment has
no candidate: not the longest-tenured `ACTIVE` project member, and not the workspace's Owner,
who *is* the subject. Any project there that the subject administers therefore stays in 1(c)'s
result, now and at phase 9, and every keystroke that would clear it is one this document
forbids: appointing a member who cannot sign in (§5.3(d) refuses exactly that), archiving a
live project to drop it out of the query, or deleting the held `project_members` row — the
orphaning b′ exists to prevent. This is not rare in that branch: whoever creates a project holds
a Project admin row in it, so a b′ workspace usually contains one. An unqualified "zero rows"
here would also contradict §5.3(b′)'s own stated default that **the freeze proceeds**. So the
gate is **no row with both flags FALSE**; a row carrying either flag is a fate this document
already decided — deletion in phase 5, or the escalation's known cost, which §5.3(b′) states in
prose and which is written on the ticket beside the escalation and cleared when the escalation
closes, never by making the query green.

**Read the partition off 1(c)'s own two flag columns**, which is the reason they are there.
Splitting the result by hand means typing a workspace id into an ad-hoc query — the retyped id
this runbook avoids everywhere else — and the reading it invites under pressure, *"some rows
came back, they must be the demo one"* or *"…the blocked one"*, passes the gate over a genuinely
stranded project in a workspace that is about to **survive**. And "cleared when the escalation
closes" is a step rather than a hope: the **b′ close-out** re-runs phase 2 for the released
workspace, this gate included. **Two different facts clear the row, at two different steps, and
merging them hides the defect this gate had for three rounds.** The close-out's **step 3** makes
the gate pass by *appointing* an ACTIVE administrator — the row drops out because
`other_active_project_admins` is no longer 0, and it does so only because that count excludes
the subject; counted in, a DISABLED subject is invisible to it and the appointment moves the old
`<= 1` predicate not at all. The close-out's **step 4** is what makes 1(c) return *nothing* at
phase 9: it removes the subject's held `project_members` row, so `bool_or(pm.user_id = :'uid')`
is false and the project is out of the result entirely, appointment or no appointment.

**`other_active_admins = 0` stops the procedure**, in the same shape as the governance gates
above and for the same reason: phase 3 clears `system_role` as well as `status`, which is
exactly what the API refuses with `409` when the target is the last `ACTIVE` `ADMIN`. The
remedy is one the operator reading this can perform — **appoint another system administrator
first** (`/api/admin/users`, or promote an existing account), then re-run the count. Do not
proceed and repair afterwards: after the freeze there is no administrator left to make the
appointment with, `/api/admin/**` is unreachable install-wide, and nothing in the product mints
a replacement — recovery is a hand-written `UPDATE` (§5.2). On a default self-hosted install
this count is the whole guard: such an install has one system administrator, and if that is the
subject, this reads zero.

**A tombstone row stops it too**, and is resolved here rather than at 8b: it costs a delay now
and an irreversible half-erasure later.

**Phase 3 is the gate, not phase 8** — it is phase 3 that sets `status = 'DISABLED'`
instance-wide and blinds every guard's `ACTIVE` filter, so a b′ workspace that is still
undecided when the freeze runs has already taken the whole of the harm (§5.3(b′)). A workspace
in `:ws_blocked` does not clear by waiting; it clears by one of §5.3's two human resolutions.
Deciding it means choosing, in the open and on the ticket, between:

- **proceeding with the freeze anyway** — the usual answer, because the subject asked to be
  locked out — which requires notifying that workspace's remaining members, recording the
  escalation **with a review date**, and coming back on that date; or
- **holding the entire erasure**, freeze included, which is right only when the resolution is
  hours away.

Either way that workspace's membership row stays in phase 5 (it is excluded from `:ws_release`
by construction) and **phase 8 does not run** until the escalation resolves. The other phases
proceed.

**Phase 3 — freeze.**

```sql
BEGIN;
UPDATE users SET status = 'DISABLED', system_role = 'USER' WHERE id = :'uid';
DELETE FROM refresh_tokens      WHERE user_id = :'uid';
DELETE FROM email_verifications WHERE user_id = :'uid';
DELETE FROM password_resets     WHERE user_id = :'uid';
DELETE FROM oauth_accounts      WHERE user_id = :'uid';
COMMIT;
```

Sessions and refresh are dead on the next request — `JwtAuthenticationFilter` re-reads the
user and filters on `isEnabled()`, and `AuthService.refresh` rejects a non-`ACTIVE` user.

**The three `DELETE`s above are not sealed, and nothing in this runbook counts what comes
back.** `password_resets` and `email_verifications` are written by *unauthenticated* endpoints
keyed on an address, so any stranger who types the subject's address into the public
forgot-password or resend-verification form mints a fresh row minutes after the freeze — no
later phase re-reads either table, and phase 9 has no check that would see it. **It is harmless
for one reason, and the reason is a property of a method rather than of this document:
`AuthService.resetPassword` writes `password_hash` and never touches `status`**, so completing
a reset on a frozen account produces a password that `login`'s `DISABLED` refusal still rejects
— the freeze is not undone by the one flow that could plausibly undo it. Recorded here because
nothing enforces it: a future edit that "helpfully" activates an account on a successful reset
(a plausible sibling of the verification flow, which *does* set `ACTIVE`) would silently thaw
every erasure in progress, and this paragraph is the only place that would then be wrong. Phase
8's scrub sets `password_hash = NULL` and re-asserts `DISABLED`, so a completed run is not
exposed either way; the window is between phase 3 and phase 8, which a b′ escalation can hold
open indefinitely.

**Then end the open SSE streams, which the SQL above cannot reach.** Membership and status are
checked at subscribe time and never again; only the `WorkspaceMemberRemoved` event closes an
emitter early, and raw SQL publishes no events — so without this step the erased account keeps
receiving live workspace activity for up to 30 minutes (§5.4):

```bash
cd /opt/hamstrack && docker compose -f docker-compose.prod.yml restart app
```

Blunt on purpose: it is reliable, it is already a deploy primitive, and it costs every other
user one automatic `EventSource` reconnect.

**Phase 4 — detach and sweep. Every statement here is scoped to `:ws_all`, or keyed on the
subject's own `user_id`, and nothing in it removes a membership row.**

```sql
BEGIN;
-- Assignments. SCOPED TO :ws_all, never `WHERE assignee_id = :'uid'` alone: a User is global,
-- issues.assignee_id carries no membership constraint, and rows assigned to a non-member
-- exist on any instance predating HD-132 — so the unscoped form unassigns a departing
-- member's work in every tenant they have ever touched. That is the exact string
-- IssueRepository.findAssignedRefsInWorkspace's javadoc names as the project's top bug class
-- "wearing a very ordinary disguise". Phase 1(g1) is where the out-of-scope rows were counted.
-- version+1 or a stale browser tab silently saves the old assignee back. updated_at is
-- stamped by the trigger on purpose — this is a change the team should see, so
-- hamstrack.skip_updated_at is deliberately NOT set anywhere in this runbook.
WITH cleared AS (
    UPDATE issues SET assignee_id = NULL, version = version + 1
     WHERE assignee_id = :'uid'
       AND workspace_id IN (:ws_all)
    RETURNING id
)
INSERT INTO issue_history (id, issue_id, changed_by, field, old_value, new_value, created_at)
SELECT (lpad(to_hex((extract(epoch from clock_timestamp())*1000)::bigint), 12, '0')
     || '7' || substr(md5(random()::text), 1, 3)
     || to_hex(8 + (random()*3)::int) || substr(md5(random()::text), 1, 3)
     || substr(md5(random()::text), 1, 12))::uuid,          -- UUID v7, per phase 1's check
       c.id, :'uid', 'assignee', 'Deleted user', NULL, NOW()
  FROM cleared c;
-- RETURNING, because "the ids updated above" cannot be re-derived: the UPDATE has already
-- nulled the column the second query would have to match on. And the history row writes
-- 'Deleted user' as old_value, NEVER the real name — an erasure that INSERTS the subject's
-- display name into an audit table is writing the datum it exists to destroy.

-- NOTHING happens to components.lead_id, and the statement that used to be here was removed in
-- review rather than merely narrowed. It was unscoped (V9's own comment makes an out-of-scope
-- lead the DOCUMENTED steady state: "a lead who merely leaves the workspace keeps the row"), it
-- reversed an explicit decision in WorkspaceMemberService, and it was the only way in the
-- product to produce auto_assign = true with no lead — after which ComponentService.update's
-- requireAutoAssignHasLead refuses a plain RENAME of that component (422) until somebody
-- reconfigures it. The scrub anonymises the reference, which is what erasure requires. §6.1.

DELETE FROM saved_filters  WHERE owner_id = :'uid' AND shared = FALSE;
DELETE FROM comment_mentions WHERE user_id = :'uid';
DELETE FROM notifications    WHERE user_id = :'uid';
-- Only if phase 1(g5) found it. A conditional artefact of V20, mapped by no entity: it is a
-- plain copy of notifications, so the subject's own rows go by the same key as their inbox.
-- DELETE FROM notifications_unresolvable_v20 WHERE user_id = :'uid';

-- Product-generated text naming the subject. SCOPED to :ws_all — an unscoped match here
-- rewrites other tenants' audit rows — and REWRITTEN, never deleted: these rows belong to the
-- team, not to the subject, and an over-match must cost legibility rather than data (§6.5).
UPDATE issue_history SET old_value = 'Deleted user'
 WHERE old_value = :'name'
   AND issue_id IN (SELECT id FROM issues WHERE workspace_id IN (:ws_all));
UPDATE issue_history SET new_value = 'Deleted user'
 WHERE new_value = :'name'
   AND issue_id IN (SELECT id FROM issues WHERE workspace_id IN (:ws_all));

-- Notification TITLES only, and by exact leading match plus a separator — never
-- `LIKE '%' || :'name' || '%'`. A display name passes registration with `%`, `_` or `'` in it
-- and may be two characters long, so a LIKE built from it is a user-supplied pattern: a member
-- who renames to `%%` matches every row in the table. `left(title, length(name) + 1) =
-- name || ' '` compares text, not a pattern; it matches where the application actually puts an
-- actor's name (the front); and the space is what stops 'Ann' from rewriting 'Anna mentioned
-- you' into 'Deleted usera mentioned you'. It does NOT stop 'Ann' from matching a colleague
-- called 'Ann Smith' — nothing in a prefix match can — which is what phase 1(g3b) probes for.
-- IF g3b RETURNED A ROW, DO NOT RUN THIS STATEMENT: rewrite by hand-picked id from g3's sample.
-- The BODY is not touched at all: it can carry text a member wrote (the mention path copies
-- up to 120 characters of the comment), which §6.5 puts out of bounds. Compare the row count
-- against phase 1(g3) before committing.
UPDATE notifications
   SET title = 'Deleted user' || substr(title, length(:'name') + 1)
 WHERE workspace_id IN (:ws_all)
   AND left(title, length(:'name') + 1) = :'name' || ' ';
COMMIT;
```

**Phase 5 — delete the solo workspaces (one transaction each), then remove the memberships.**
Order matters twice: `issues.workspace_id` has **no** cascade, and the subject's
`workspace_members` row is the evidence the guards below check themselves against, so it is the
last thing to go.

```sql
-- ONE workspace per transaction. :ws is pasted from phase 1(b)'s routing output — and both
-- statements are written so that a WRONG id deletes NOTHING. The guard is the same in both:
-- the subject must still be a member of that workspace, and no un-erased other member may
-- exist. A transposed character reports `DELETE 0`, which is the check working.
\set ws '00000000-0000-0000-0000-000000000000'

BEGIN;
DELETE FROM issues
 WHERE workspace_id = :'ws'
   AND EXISTS (SELECT 1 FROM workspace_members m
                WHERE m.workspace_id = :'ws' AND m.user_id = :'uid')
   AND NOT EXISTS (SELECT 1 FROM workspace_members o JOIN users u2 ON u2.id = o.user_id
                    WHERE o.workspace_id = :'ws' AND o.user_id <> :'uid'
                      AND u2.email <> 'deleted+' || u2.id::text || '@deleted.invalid');

DELETE FROM workspaces w
 WHERE w.id = :'ws'
   AND EXISTS (SELECT 1 FROM workspace_members m
                WHERE m.workspace_id = w.id AND m.user_id = :'uid')
   AND NOT EXISTS (SELECT 1 FROM workspace_members o JOIN users u2 ON u2.id = o.user_id
                    WHERE o.workspace_id = w.id AND o.user_id <> :'uid'
                      AND u2.email <> 'deleted+' || u2.id::text || '@deleted.invalid');
-- EXPECT `DELETE 1` from the second statement. `DELETE 0` means the id is not one of the
-- subject's solo workspaces, OR that it is and the workspace has gained an un-erased member
-- since phase 1. ROLL BACK either way, then read the two causes below BEFORE the membership
-- deletes: they have different remedies and only one of them ends at "re-read phase 1(b)".
-- Do not remove the guard.
COMMIT;
```

**`DELETE 0` has two causes and only one of them is a typo, and the other one is not finished by
rolling back.** The guard is a *prediction* — "no un-erased member besides the subject
remains" — made at phase 1 and executed here, and the window between them is not quiet:
`workspace_invites` rows outstanding at phase 1 are addressed to **other people**, nothing in
phases 2-4 clears them,
and **phase 6, which does clear invites, runs after this and only for invites addressed to the
subject**. One of them accepted in that window puts an un-erased member in the workspace, the
`NOT EXISTS` fails, and the workspace **survives** — while it is still in `:ws_release`, so the
very next block strips the subject's `workspace_members` and `project_members` rows there. That
leaves a live workspace with no Owner and a project with no administrator: precisely the
orphaning §5.3(b′) exists to refuse, arrived at by following the runbook, and 1(c) excused that
project at phase 2 on the strength of `workspace_deleted` — a flag that has just turned out
false.

So, on `DELETE 0`: **ROLL BACK, then find out which cause it is before running the membership
deletes.**

- **A transposed id** — the id is not in `:ws_delete` at all. Re-read the working file's routing
  and repeat with the right id. Nothing else changes.
- **The right id, and the workspace has gained a member** — re-read 1(b)'s routing row for it
  (`others_not_erased` is now ≥ 1). **Re-derive the routing and all four lists from the database**
  before going any further; this is one of the two steps that meets the re-derivation property
  above, and it meets it precisely here — phase 8 has not run and the membership deletes are
  still ahead. Then **re-read 1(c)'s gate** against the new lists, because the workspace now
  routes to 5.3(a), (b) or (b′) and its projects have lost the `workspace_deleted` excuse: a
  project of the subject's there is a finding now, and §5.3(d) appoints for it. A workspace that
  re-derives into `:ws_blocked` also leaves `:ws_release` by construction, which is what stops
  the membership deletes from orphaning it. Record the re-derived lists in the working file as a
  new round, keep the previous round, and read the caveats the b′ close-out's step 1 states about
  a narrowed `:ws_all` — they apply here for the same reason.

Do not delete the new member's row, do not widen the statement, and do not proceed to the
membership deletes on the theory that the workspace "was going to be deleted anyway".

Repeat for each id `:ws_delete` listed. **Only when every one of them is done**, remove what is
left of the subject's access — the deleted workspaces took their own membership rows with them,
so this reaches only the retained ones:

```sql
BEGIN;
-- Scoped to :ws_release, which phase 1 derived as "every workspace of the subject that is NOT
-- blocked by a 5.3(b') escalation". With nothing blocked it equals :ws_all and these are the
-- same two statements they always were — there is no branch to remember and no exclusion to
-- hand-edit under pressure, which is how the previous draft's single-workspace `<> :'blocked_ws'`
-- would have failed on the second blocked workspace.
-- It is an IN over the COMPLEMENT and never a NOT IN over the blocked list: `NOT IN (NULL)` is
-- falsy, so on the common install (nothing blocked) that form deletes NOTHING and reports it as
-- a clean run. This form's failure direction is the other one — an under-derived list leaves
-- rows behind, which phase 9 sees.
DELETE FROM project_members
 WHERE user_id = :'uid'
   AND project_id IN (SELECT id FROM projects WHERE workspace_id IN (:ws_release));
DELETE FROM workspace_members
 WHERE user_id = :'uid'
   AND workspace_id IN (:ws_release);
COMMIT;
```

**If `:ws_blocked` is non-empty** — one or more workspaces routed to 5.3(b′) — those rows stay
by construction, and **phase 8 does not run at all** until every one of them resolves. Removing
the row orphans the workspace; scrubbing while it survives spends the address and display name
the operator still needs and buys nothing, because the freeze has already disabled the account
(§5.3(b′)). **Note what stays here besides the workspace_members row: the subject's
`project_members` rows inside that workspace stay too**, because the DELETE above is scoped to
`:ws_release` and a blocked workspace is not in it — and on the usual b′ shape there is at least
one, since whoever creates a project holds a Project admin row in it. Phase 1(g4) counts both
groups that survive these statements, its out-of-`:ws_all` rows and its blocked-workspace rows,
and phase 9's project_members expectation is their sum. A row outside `:ws_all` is decided by
hand; a blocked-workspace row is held until the escalation resolves. Neither may be discovered
as a surprise non-zero in phase 9, and neither is deleted to make that count agree.

The workspace delete cascades `projects`, `project_members`, `workspace_members`,
`workspace_invites`, `roles`, `sprints`, `sprint_scope_events`, `versions`, `components`,
`labels`, `saved_filters`, `notifications` and the workspace-scoped taxonomy. The
`role_id` foreign keys added by V14 are `NO ACTION`, which defers to end of statement, so the
members are already gone when the check runs — V14's own header states this and it is why they
must never be "tightened" to `RESTRICT`.

**Phase 6 — mail logs and invitations, address-keyed.**

```sql
BEGIN;
UPDATE mail_send_events SET sender_user_id = NULL WHERE sender_user_id = :'uid';
-- recipient_email, and NEVER recipient_key. The key folds +tag and Gmail dots, so one key can
-- belong to two different accounts; deleting by it destroys a third party's rows and resets
-- the anti-harassment ceiling protecting them (§6.2). Do not "widen" this to match §6.1.
-- lower() on all three address predicates in this phase, for one reason stated once: an exact
-- match here would be correct only because of what ANOTHER table's writers happened to store,
-- and none of these columns carries an index a fold would cost.
DELETE FROM mail_send_events  WHERE lower(recipient_email) = :'addr';
DELETE FROM failed_email      WHERE lower(recipient) = :'addr';
-- Invitations reach tenants the subject was never a member of, so this is address-keyed and
-- deliberately outside :ws_all. Phase 1(h) is where those workspaces were listed.
DELETE FROM workspace_invites WHERE lower(email)    = :'addr';
COMMIT;
```

**Phase 7 — objects.** **Derive the prefixes from THIS ROUND's phase-1(e) key file; do not
retype a workspace id, and do not reach back into an earlier round's file** — its keys name
objects an earlier run of this phase already deleted, and re-deriving prefixes from it re-lists
prefixes that are supposed to be empty, which is the one reading this phase treats as a failure.
This is the one step with no undo at all — an object store has no membership
row to check a prefix against, and the Cloud branch removes every version *and* the delete
markers.

```bash
# The prefixes, from the key file THIS round's 1(e) produced — `<n>` is `1` on the first pass
# and the close-out's round number after that. Keys are ws/{wsId}/issues/{issueId}/{uuid}.
cut -d/ -f1,2 erase-<request_id>-keys-<n>.txt | sort -u > erase-<request_id>-prefixes-<n>.txt
cat erase-<request_id>-prefixes-<n>.txt
# Read it, in both directions. A line that is NOT a workspace you routed to 5.3(c) is the one
# mistake in this document with no undo at all: stop, and do not run the loops below. FEWER
# lines than :ws_delete has ids is not a mistake — a workspace with no attachments produces no
# key and therefore no prefix. Reconcile the missing ids against this round's 1(e) key file, never
# by appending a prefix by hand: a prefix typed here is exactly the retyped workspace id this
# phase exists to avoid.

# DC (LocalFileStorage): remove each subtree under app.storage.local.base-dir, then verify.
while read -r p; do rm -rf "$STORAGE_BASE_DIR/$p"; done < erase-<request_id>-prefixes-<n>.txt
while read -r p; do test ! -e "$STORAGE_BASE_DIR/$p" || echo "STILL PRESENT: $p"; done < erase-<request_id>-prefixes-<n>.txt

# Cloud (S3, versioned): every version AND every delete marker, then re-list to prove empty.
while read -r p; do
  aws s3api list-object-versions --bucket "$ATTACH_BUCKET" --prefix "$p/" \
    --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' > /tmp/v.json
  aws s3api delete-objects --bucket "$ATTACH_BUCKET" --delete file:///tmp/v.json
  aws s3api list-object-versions --bucket "$ATTACH_BUCKET" --prefix "$p/" \
    --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}' > /tmp/m.json
  aws s3api delete-objects --bucket "$ATTACH_BUCKET" --delete file:///tmp/m.json
  aws s3api list-object-versions --bucket "$ATTACH_BUCKET" --prefix "$p/"   # expect empty
done < erase-<request_id>-prefixes-<n>.txt
# Empty is the floor on both backends, so there is one direction to read: anything still listed
# (a version, or a delete marker) is a version that survived the loop — re-run it for that
# prefix and re-list. Do not accept a non-empty listing on the grounds that the 30-day
# noncurrent-version rule will finish the job; that is the retention dependency this phase
# exists to avoid, and the DC branch's `STILL PRESENT:` line reads the same way.
```

A plain `DeleteObject` only writes a delete marker and leaves the prior version fully readable
to anyone holding a version id, and relying on the 30-day noncurrent-version lifecycle rule to
finish the job would make erasure depend on a retention window — which is exactly what this
ticket may not assert (§7).

**The b′ close-out — how a blocked run resumes, and where the held rows finally go.** Skip this
on any run where `:ws_blocked` was empty. A run where it was not stops after phase 7 until the
escalation resolves, and resumes **here**, not at phase 8. §5.3(b′) offers exactly two
resolutions and **both end with the subject holding no row in that workspace**, so the phase 5
this document deferred is performed now — in phase 5's own order, with its guards intact.

**Re-derive the lists from the current database first, and this is the one step in the runbook
that does.** The working file's `:ws_blocked` names the workspace as it was routed *then*, and
the entire content of this step is that it is not any more; the file's `:ws_release` was derived
to exclude it. Re-derivation is available here, and nowhere else, precisely because phase 8 has
not run: the address still resolves to the subject, and the membership rows every list query
reads are still present for exactly the workspaces still held. Do not reuse the file's lists,
and do not hand-type the released id into a statement.

1. **Re-run phase 1(b) in full** — the routing query, all four `\gset` derivations, and the
   read-back. `:ws_all` now names only what the first pass left standing — the workspaces that
   were blocked *then*, since phase 5 removed the membership rows of every other one — while
   `:ws_blocked` names whatever is **still** blocked and `:ws_release` is the complement:
   exactly the workspaces that have just released. Every routing decision comes from that query,
   never from a memory of what was decided weeks ago.
   **All four variables — `:ws_all`, `:ws_delete`, `:ws_blocked`, `:ws_release` — are replaced,
   and `:ws_all` NARROWS.** It now names only the workspaces still standing, so every check
   scoped by it verifies strictly less than it did on the first pass: phase 9's scoped assignee
   count (`workspace_id IN (:ws_all)`) asserts `0` over a smaller set of workspaces than the
   never-blocked reading of the same line does, and that is correct — the workspaces it no
   longer covers were deleted or swept rounds ago and cannot be re-read. Write the re-derived
   four into the working file as a **new round**, beside the previous round rather than over it;
   the earlier lists are what phase 9's closing `:ws_delete` bullet is read against. That bullet
   — *every id in `:ws_delete` is gone from `workspaces`, and no id outside it is* — is read
   against the **union of every round's `:ws_delete`**, never this round's alone: pass 1's
   deleted ids are absent from the re-derived list, and reading the bullet literally against it
   would report every one of them as a workspace deleted that nothing routed for deletion.
2. **Re-run phase 1(a)'s tombstone probe, and 1(c), 1(e), 1(f), 1(g2), 1(g3) — its collision
   probe g3b included — and 1(g4)** — with this round's `<n>` in 1(e)'s filename.
   The probe, because 8b is
   about to run and the address has sat unscrubbed for the length of the escalation — a squat
   registered in that window blocks the scrub on `users_email_lower_uk`, and the first pass's
   reading is stale. 1(e), because a workspace that now routes to 5.3(c) has its attachment keys
   captured **here, before anything deletes the rows that name them**: its keys are not in the
   first pass's file, since it was not in `:ws_delete` then. 1(g4), because phase 9's post-close
   expectation is read against *these* totals and not against the first pass's.
   **1(f), 1(g2) and 1(g3), because 8a is the next hard gate and every expectation it compares
   against comes from those three — none of them from 1(a), 1(c), 1(e) or 1(g4).** Re-reading
   them is not bookkeeping: **the escalation window is not quiet.** The subject stayed a member
   of the held workspace throughout it, and the member reactivated in order to resolve it can
   assign them issues and generate notification titles and `issue_history` rows carrying their
   display name — in a workspace phase 4 has not been re-run over. Without a fresh reading, 8a's
   name-keyed counts read HIGHER than the first pass recorded, and 8a's HIGHER branch names one
   cause and puts *fix the list* first, at the one reading where the list is right.
3. **Re-run phase 2 for the released workspaces**, against the routing step 1 has just produced
   rather than the one this workspace had weeks ago — normally branch (b)'s promotion to the
   reactivated member, plus §5.3(d)'s project-admin appointments, both written down for the
   ledger — **and re-read its gate**: 1(c) must now return no row with both flags FALSE. A
   workspace resolving to 5.3(c) reads `workspace_deleted` TRUE here and is not a finding —
   step 4 is about to delete it — and one that is still blocked reads `held_by_escalation` TRUE.

   **Phase 2's preferred mechanism is not available here, and its absence is guaranteed rather
   than likely.** Phase 2 says *prefer the API where one exists*, and in a b′ workspace neither
   API exists: the only Owner is the subject, `DISABLED` since phase 3, so nobody can call the
   promote endpoint; the reactivated member is a plain member, and the shipped default is
   Contributor without `workspace.member.manage` — which is exactly what 1(d) exists to confirm.
   §5.3(d)'s appointment is worse off still: the project has no holder of
   `project.member.manage`, so it is outside `projectCuration()` and the *new* Owner cannot make
   the appointment either. **Both are therefore SQL here, and the statements are given rather
   than left to be improvised at 2am.** Each is guarded so a wrong id changes nothing, in phase
   5's shape, and each is read back for `UPDATE 1`.

   ```sql
   -- The released workspace, from step 1's re-derived :ws_release — never retyped from memory.
   \set ws '00000000-0000-0000-0000-000000000000'

   -- The candidates, DERIVED. Appoint only an ACTIVE account: an appointee who cannot sign in
   -- is the stranding this whole branch exists to prevent, wearing the costume of a fix.
   SELECT m.user_id, u.email, u.display_name, u.status
     FROM workspace_members m JOIN users u ON u.id = m.user_id
    WHERE m.workspace_id = :'ws' AND m.user_id <> :'uid' AND u.status = 'ACTIVE';
   \set new_owner '00000000-0000-0000-0000-000000000000'

   BEGIN;
   -- (b)'s promotion. The built-in Owner role id is the one 1(b) routes on. The guard re-asserts
   -- both facts the SELECT above showed, so a stale id or an account that was re-suspended
   -- between the two statements reports `UPDATE 0` rather than appointing somebody who cannot
   -- sign in.
   UPDATE workspace_members
      SET role_id = '00000000-0000-7000-8000-000000000001'
    WHERE workspace_id = :'ws' AND user_id = :'new_owner'
      AND EXISTS (SELECT 1 FROM users u WHERE u.id = :'new_owner' AND u.status = 'ACTIVE');
   COMMIT;
   -- EXPECT `UPDATE 1`. `UPDATE 0` means the appointee is not a member of this workspace or is
   -- not ACTIVE: re-read the SELECT, do not relax the guard.
   ```

   **§5.3(d)'s appointment, for each project 1(c) still returns here — and the role is
   `TEAM_LEAD`, never Project admin.** V16 seeded that built-in for exactly this act and
   `ProjectAdminGuard.ADOPTION_ROLE_KEY`'s javadoc argues why the wider grant is wrong;
   appointing Project admin here would overturn that decision in the one place nothing reviews.
   It is nonetheless enough to clear 1(c): V16 grants it `project.member.manage` with
   `own_only = FALSE`, which is the membership `admin_roles` tests. Read the role out of `roles`
   rather than typing V16's id — a built-in is shared (`workspace_id IS NULL`) and a typed id is
   a guess at a seed row — and read it back before using it.

   ```sql
   \set proj '00000000-0000-0000-0000-000000000000'   -- from step 3's re-read of 1(c)

   -- §5.3(d)'s candidate, in its stated order: the longest-tenured ACTIVE member OF THAT
   -- PROJECT, and only if there is none, the workspace's (now promoted) Owner. Derive it; do
   -- not assume the reactivated member is a project member, and do not assume they are not.
   SELECT pm.user_id, pm.joined_at, u.display_name, u.status
     FROM project_members pm JOIN users u ON u.id = pm.user_id
    WHERE pm.project_id = :'proj' AND pm.user_id <> :'uid' AND u.status = 'ACTIVE'
    ORDER BY pm.joined_at;
   \set lead '00000000-0000-0000-0000-000000000000'   -- that row, or :new_owner if there is none

   -- The built-in Team lead. Expect exactly ONE row, with workspace_id NULL. No row means this
   -- install predates V16 — stop, and resolve §5.3(d) against ProjectAdminGuard.adoptAll's
   -- actual role rather than inventing one. More than one row means a workspace-scoped role has
   -- taken the key; take the one whose workspace_id IS NULL.
   SELECT r.id, r.workspace_id, r.key, r.name FROM roles r
    WHERE r.scope = 'PROJECT' AND r.key = 'TEAM_LEAD' AND r.built_in;
   \set admin_role '00000000-0000-0000-0000-000000000000'

   BEGIN;
   -- The appointee usually already has a project_members row; this promotes it in place.
   UPDATE project_members pm
      SET role_id = :'admin_role'
     FROM projects p
    WHERE p.id = pm.project_id AND pm.project_id = :'proj' AND pm.user_id = :'lead'
      AND p.workspace_id = :'ws'
      AND EXISTS (SELECT 1 FROM users u WHERE u.id = :'lead' AND u.status = 'ACTIVE');

   -- If that reported `UPDATE 0` because they hold no row yet, insert one — same guards, plus
   -- the workspace-membership check the API would have made, and phase 1's v7 id expression
   -- because `project_members.id` is a UUID the application generates.
   INSERT INTO project_members (id, project_id, user_id, role_id, joined_at)
   SELECT (lpad(to_hex((extract(epoch from clock_timestamp())*1000)::bigint), 12, '0')
        || '7' || substr(md5(random()::text), 1, 3)
        || to_hex(8 + (random()*3)::int) || substr(md5(random()::text), 1, 3)
        || substr(md5(random()::text), 1, 12))::uuid,
          p.id, :'lead', :'admin_role', NOW()
     FROM projects p
    WHERE p.id = :'proj' AND p.workspace_id = :'ws'
      AND EXISTS (SELECT 1 FROM users u WHERE u.id = :'lead' AND u.status = 'ACTIVE')
      AND EXISTS (SELECT 1 FROM workspace_members m
                   WHERE m.workspace_id = :'ws' AND m.user_id = :'lead')
      AND NOT EXISTS (SELECT 1 FROM project_members pm
                       WHERE pm.project_id = :'proj' AND pm.user_id = :'lead');
   COMMIT;
   -- EXPECT exactly one of `UPDATE 1` / `INSERT 0 1`. Both reporting 0 means the appointee is
   -- not an ACTIVE member of this workspace — re-read the candidate SELECT. Then re-read 1(c):
   -- the project drops out because `other_active_project_admins` is no longer 0, which is the
   -- appointment working, and NOT because the subject's held row went anywhere (step 4 does
   -- that, and it has not run yet).
   ```

   Write both the promotion and every appointment down as you make them — phase 10's ledger row
   carries them, and a restore replay has no other way to learn who was chosen (§8.2).
4. **Re-run phase 5**, in its own order: the per-workspace transaction for every id now in
   `:ws_delete`, then the two `:ws_release`-scoped membership deletes. The held `project_members`
   and `workspace_members` rows go here — and only because step 1 moved that workspace into
   `:ws_release`, never because a list was edited to include it.
5. **Run phase 7** for any workspace deleted in step 4, from step 2's key file.
6. Then **phase 8** (8a's checks, then 8b), then **phase 9 at its post-close reading**, then
   **phase 10**, whose ledger row carries the appointments step 3 made.

**Phases 3, 4 and 6 are not re-run as a matter of course.** 3 and 6 are install-wide and already
done. Phase 4's sweeps are scoped or subject-keyed and idempotent, and a blocked workspace kept
the subject a member throughout, so it can have accrued notifications and assignments since.
**Two steps ask for it, and 8a asks first** — 8a's name-keyed counts are a hard gate that runs
before 8b, and phase 9 catches the rest afterwards. Do it when one of them asks, not
pre-emptively, and when it asks, run phase 4 **with the re-derived `:ws_all` from step 1** — the
accrued rows are in the released workspace, which the narrowed list still covers.

**The agreed-deletion resolution needs one thing to become true first.** Phase 5's workspace
delete carries 5.3(c)'s predicate — *no un-erased member besides the subject remains* — and it
does not hold in a b′ workspace by construction, so that statement reports `DELETE 0` and **that
is the guard working, not a step to edit**. The agreement is executed by making the predicate
true — and **the obvious way of doing that is not one its subject can perform.** Every member of
a b′ workspace is non-ACTIVE by that branch's own definition, and a non-ACTIVE account cannot
sign in, so "the member who agreed leaves the workspace" names an API call they cannot reach.
The unstated prerequisite is **reactivation**, and it has to be stated, because once it has
happened the cheaper resolution is also on the table: an ACTIVE member is exactly what branch
(b) needs, so **reactivate, then prefer (b)'s promotion** — it keeps the workspace, its data and
its objects, and it is a single guarded `UPDATE` against a deletion, a phase 7 and a key file.
Take the deletion only when that is what was actually agreed. Either way the predicate becomes
true the same way: **the member is reactivated and then leaves the workspace themselves, or
their own account goes through its own erasure** — after which step 1's re-derivation puts the
workspace in `:ws_delete` and step 4 deletes it with the guard untouched. If neither is
available, this deletion is not the runbook's to perform: take branch (b), or record on the
ticket that the workspace stays and treat it as still blocked.

**If several workspaces were blocked and only some resolved**, this step runs once per
resolution: the re-derived `:ws_blocked` is still non-empty, **phase 8 still does not run**, and
phase 9 stays at its blocked-open reading for what remains. The close-out is finished when
`:ws_blocked` re-derives empty.

**Phase 8 — the scrub, last, and gated. The gate is the first half of this phase, not a
forward reference to the next one**: a runbook is read top to bottom under pressure, and a
precondition printed after the statement it guards is a precondition nobody meets.

**8a — the verification that stops being possible afterwards.** The scrub destroys `:addr` and
`:name` in the database, and the ledger deliberately carries neither, so a row phase 6 missed or
a sweep later found incomplete becomes **unfindable** — there is nothing left to search for.

```sql
-- ADDRESS-KEYED. These read 0, and 0 is reachable here for a reason the name-keyed checks
-- below do not share: every row they can see belongs to the subject, so phase 6 was allowed to
-- delete all of them unscoped. 0 is also their floor, so only one direction of deviation
-- exists — ABOVE 0 means a phase-6 statement did not run, or a row was written after it did.
-- The second is genuinely reachable, and the cause is a PROPERTY rather than one actor:
-- ANYTHING THAT SPENDS AN ADDRESS-KEYED MAIL BUDGET WRITES A ROW IN mail_send_events — the
-- throttle records the address as submitted when it allows a send, with no status filter and no
-- account lookup between the two. An invite re-sent by another member is only the door that
-- needs one; a forgot-password and a resend-verification are the other two, AND BOTH ARE
-- UNAUTHENTICATED — any stranger who types the address into the public form drives this count
-- above 0 with no member and no workspace involved. Say so, because 8a is a hard gate: an
-- operator told the cause is a re-invite audits workspace_invites and the member list, finds
-- nothing, and is stuck on a check that blocks the procedure. There is nothing to find there.
-- Re-run phase 6 and re-read; it is address-keyed and idempotent. Do not enter 8b on a non-zero
-- — after the scrub :addr is gone from the database and these rows are unfindable by anything.
SELECT count(*) FROM mail_send_events  WHERE lower(recipient_email) = :'addr'
                                          OR sender_user_id = :'uid';      -- 0
SELECT count(*) FROM failed_email      WHERE lower(recipient) = :'addr';   -- 0
SELECT count(*) FROM workspace_invites WHERE lower(email) = :'addr';       -- 0

-- NAME-KEYED and UNSCOPED, which is what makes them able to see a mis-scoped workspace list at
-- all: a check keyed by user_id or by the address returns a perfectly clean answer no matter
-- what :ws_all contains, so a verification suite made only of those cannot detect the mistake
-- this runbook is most likely to make.
-- THESE DO NOT EXPECT 0. They expect the numbers phase 1 recorded for the part of the install
-- this procedure is not allowed to touch: a namesake in another tenant is not swept (1(g2) says
-- those rows stay), so on any install with one, demanding 0 here is unsatisfiable — and the
-- single edit that satisfies it is deleting the `workspace_id IN (:ws_all)` scoping from phase
-- 4, which is this project's top bug class arrived at by following the runbook. If this will
-- not go to zero, THAT IS EXPECTED. Do not widen the sweep.
SELECT count(*) FROM issue_history
 WHERE old_value = :'name' OR new_value = :'name';
-- Expect 1(g2)'s out-of-scope total, and read a deviation in EITHER direction:
--   HIGHER — in-scope rows were never rewritten, and there are TWO causes with OPPOSITE
--     remedies, so read which of this document's three readings you are at before touching
--     anything. On a never-blocked run: a workspace is missing from :ws_all — fix the list,
--     re-run phase 4's two issue_history statements (scoped and idempotent), re-read.
--     AT THE POST-CLOSE READING the list is RIGHT and fixing it is the wrong move: the subject
--     stayed a member of the held workspace for the length of the escalation and the member
--     reactivated to resolve it can have written rows carrying the display name in a workspace
--     phase 4 was never re-run over. Those rows are the excess. Re-run phase 4's two
--     issue_history statements with the close-out's RE-DERIVED :ws_all — which covers the
--     released workspace — and re-read against the totals the close-out's step 2 recorded, not
--     the first pass's. Do not touch the list.
--   LOWER — rows counted as out-of-scope are gone. Nothing in this runbook can delete them
--     (:ws_delete ⊆ :ws_all, and the rewrites never delete), so something outside it did: a
--     tenant deleting its own issue or workspace while this ran, or the namesake renaming
--     themselves. Not a failure of the erasure. Check it against 1(g2)'s per-workspace
--     breakdown, record it, and leave the recorded expectation as it was written — an expected
--     value edited to match what came back has verified nothing.
SELECT count(*) FROM notifications
 WHERE left(title, length(:'name') + 1) = :'name' || ' ';
-- Expect 1(g3)'s out-of-scope total, MINUS the part of it that sat in the subject's own inbox
-- (1(g3)'s third column), PLUS anything deliberately left because 1(g3b) found a namesake and
-- the rewrite was done by hand. That subtraction is ordinary arithmetic, not an anomaly: phase
-- 4 deletes `notifications WHERE user_id = :'uid'` unscoped, because every such row is the
-- subject's own, so a title leading with their name in a workspace they had LEFT goes with
-- their inbox instead of being counted here. Both directions again:
--   HIGHER — exactly as above, both causes and both remedies: a workspace missing from :ws_all
--     on a never-blocked run, and at the post-close reading titles accrued in the released
--     workspace during the escalation, cleared by re-running phase 4's notification statement
--     with the re-derived :ws_all and read against step 2's totals. Re-run 1(g3b) first if you
--     re-run the rewrite: the collision probe is a fact about the CURRENT member list, and the
--     member reactivated to close the escalation was not necessarily in it on the first pass.
--   LOWER than even that figure — more of their own inbox was out of scope than 1(g3) split
--     out, or another tenant deleted rows while this ran. Reconcile against 1(g3), record it,
--     and do not treat it as a step that failed.
-- The prohibition covers both directions and every reading in between: the edit that drives
-- either of these counts to 0 is deleting phase 4's `workspace_id IN (:ws_all)`, which rewrites
-- other tenants' rows. A number that cannot reach 0 is not a failing check.
```

**Do not run 8b until 8a has been read and each number matched against what phase 1 recorded**
— zero for the address-keyed ones, and for the name-keyed ones the number phase 1 wrote down,
with a deviation in **either** direction accounted for in writing before you continue. And not
at all while `:ws_blocked` is non-empty (§5.3(b′)) — a run that stopped there resumes at the
b′ close-out above, which **re-derives** `:ws_blocked` rather than trusting the working file's
copy of it, and matches 8a against the numbers that re-derivation recorded.

**8b — the scrub.**

```sql
BEGIN;
-- A unique violation on users_email_lower_uk here means somebody registered this exact address
-- as a squat (§5.1). Nothing is written, which is correct: deal with that account first, then
-- re-run. It cannot make the squatter's own row read as erased — the predicate compares each
-- row against ITS OWN id — so this is a blocked scrub, never a corrupted liveness test.
UPDATE users
   SET email         = 'deleted+' || id::text || '@deleted.invalid',
       display_name  = 'Deleted user',
       avatar_url    = NULL,
       password_hash = NULL,
       status        = 'DISABLED',
       system_role   = 'USER'
 WHERE id = :'uid';
COMMIT;
```

**Phase 9 — verify.** The address- and name-keyed half runs **before** the scrub and lives in
phase 8a, where it is a precondition rather than a reference. What follows is the rest: the
identity- and membership-keyed checks, which stay answerable afterwards.

**Phase 9 is read at up to three points in one run, and the same check has a different correct
value at each.** A check here is not a fact about the install; it is a fact about the install
*at the reading this document mandates of it*, and a value that is correct at one reading is a
failure at another. The three readings are:

- **Never-blocked** — `:ws_blocked` was empty throughout. The erasure is complete, and every
  expectation below is the completed one.
- **Blocked-open** — `:ws_blocked` is non-empty, so phase 8 deliberately has not run and the
  subject's row still holds their address and display name (§5.3(b′)). Phase 9 is still worth
  running on everything that did run, and every deliberately held row is expected to be
  **present and counted**.
- **Post-close** — the b′ close-out has run: the escalation resolved, the lists were re-derived,
  the released workspace went through phases 2 and 5 (and 7, if it was the deletion resolution),
  and phase 8 followed. `:ws_blocked` now
  re-derives **empty**, and every expectation returns to the never-blocked one — because both of
  §5.3(b′)'s resolutions end with the subject holding no row in that workspace. If some other
  workspace is still blocked, this is not the post-close reading: it stays blocked-open for what
  remains, and the close-out runs again when that escalation resolves.

Which reading you are at is a fact about the run — re-derive `:ws_blocked` and read it, rather
than judging it from the numbers. Each check below says what it reads at each.

```sql
-- The account row. NEVER-BLOCKED and POST-CLOSE: expect §5.1's scrubbed values — the tombstone
-- address containing this row's own id, `Deleted user`, DISABLED. BLOCKED-OPEN: expect the REAL
-- address and name still present, because 8b has not run and that is the b' branch working. A
-- scrubbed row while a workspace is still blocked means 8b ran past its own gate, and the
-- address and display name the operator still needs are gone. An unscrubbed row after a
-- close-out means step 6 of it did not finish: 8a, then 8b, then re-read this.
SELECT email, display_name, status FROM users WHERE id = :'uid';

-- Memberships. THE EXPECTED VALUE IS NOT 0 ON EVERY INSTALL AND NOT AT EVERY READING, which is
-- why neither line carries a bare `0`. Phase 5 is scoped to :ws_release, so while an escalation
-- is open every row this procedure deliberately held is still here. Expect, from the numbers
-- phase 1 wrote down — and after a close-out, from the numbers ITS re-run of 1(g4) wrote down:
--   BLOCKED-OPEN:
--     workspace_members = one row per workspace in :ws_blocked
--     project_members   = the SUM of 1(g4)'s two held groups — its out-of-:ws_all total (2)
--       PLUS its held_by_escalation total (3). Both are columns on the same 1(g4) report, which
--       is why that step records a breakdown and not one number: (3) is normally NON-ZERO
--       whenever :ws_blocked is, because whoever creates a project holds a project_members row
--       in it, and a blocked workspace's Owner is usually its project creator.
--   NEVER-BLOCKED and POST-CLOSE — the same two values, and that they are the same is the whole
--   point of the close-out:
--     workspace_members = 0
--     project_members   = 1(g4)'s out-of-:ws_all total (2) ALONE. The held_by_escalation term
--       is gone because the close-out's re-derivation moved that workspace into :ws_release and
--       phase 5's re-run removed the rows. A 0 at these two readings is the CORRECT value, not
--       the unrecoverable direction below — including when (2) is itself 0, which is the usual
--       modern shape.
--   HIGHER — TEST THE EXPECTED VALUE BEFORE TOUCHING ANY LIST, because a held row that was
--     never counted reads exactly like a delete that never ran, and their remedies are
--     opposites. Check the reading first, then the arithmetic: re-add 1(g4)'s
--     held_by_escalation total to the expectation, and if the count now matches, nothing failed
--     and the arithmetic was short. Only when the count exceeds BOTH held groups did phase 5
--     fail to reach a workspace it should have: re-derive :ws_release from phase 1 (it is
--     :ws_all minus :ws_blocked, never wider), re-run phase 5's two deletes, re-read. That much
--     is recoverable, and the deletes are idempotent.
--     WHILE THE ESCALATION IS OPEN, a :ws_release widened to cover a :ws_blocked workspace is
--     NOT that remedy — it is the one keystroke that deletes the held rows the next paragraph
--     says nothing puts back, and it is the only edit that can move this number when the cause
--     was the arithmetic. Once the escalation CLOSES, that same workspace enters :ws_release
--     legitimately — by the close-out's re-derivation from the database, never by an edit to a
--     saved list.
--   LOWER, AT THE BLOCKED-OPEN READING — a row that was being held on purpose is gone, and
--     nothing in this runbook puts it back. The subject's workspace_members row in a
--     :ws_blocked workspace IS what keeps that workspace from being orphaned, and its only
--     remaining Owner cannot sign in; removing it inflicts precisely the harm the b' branch
--     exists to refuse. A 0 at that reading, on an install with a blocked workspace, is not a
--     clean run.
--   LOWER, AT THE NEVER-BLOCKED AND POST-CLOSE READINGS — reachable exactly when 1(g4)'s group
--     (2) is non-zero, which is the whole reason the expectation is that number and not `0`.
--     workspace_members has 0 as its floor there and nothing below it; project_members does
--     NOT, and the direction is BENIGN: group (2) is the legacy rows outside :ws_all, and 1(g4)
--     says they are decided one at a time with their own workspace's owner — an act that
--     REMOVES them, and that this procedure neither performs nor prevents. It is the same
--     outcome the scoped assignee count already writes up as "not a step that failed".
--     Reconcile against 1(g4)'s three groups, record it, and leave the recorded expectation as
--     it was written. Only where group (2) is itself 0 is the expectation its own floor, and
--     that is the usual modern shape rather than a property of the check.
-- So: at the never-blocked and post-close readings workspace_members reads 0, and
-- project_members reads 1(g4)'s out-of-:ws_all total — which is itself 0 on the usual modern
-- install, so two zeros there are a pass. At the blocked-open reading a 0 on either line is the
-- failure above. The one keystroke that turns a non-zero green while an escalation is open is
-- deleting the held row.
-- DO NOT. Record the number and why on the ticket instead: an unexplained non-zero and a
-- deliberately held one look identical, and only the note tells them apart.
SELECT count(*) FROM workspace_members WHERE user_id = :'uid';
SELECT count(*) FROM project_members   WHERE user_id = :'uid';

-- The subject's own inbox and sessions. Keyed on user_id and swept unscoped — every row they
-- can see is the subject's — so 0 is reachable and is the floor, and only one direction exists.
--   notifications ABOVE 0: rows arrived after phase 4, by either of two routes, and the second
--     needs no :ws_blocked at all. (i) A blocked workspace still carries the subject's
--     membership. (ii) Between phase 4 and phase 5 the subject is still a member of every
--     workspace in :ws_all, and ADR-0009 keeps a notification row after its owner leaves — so
--     a row written in that window is still here at phase 9 on a run where :ws_blocked was
--     empty throughout. Re-running phase 4's `DELETE FROM notifications WHERE user_id = :'uid'`
--     is safe, idempotent and the whole remedy for both.
--   refresh_tokens ABOVE 0: the account obtained a session after the freeze, which phase 3 makes
--     impossible (`isEnabled()` per request, `AuthService.refresh` refuses a non-ACTIVE user).
--     Do not re-run and move on — find out why before the request is closed.
SELECT count(*) FROM notifications     WHERE user_id = :'uid';             -- 0
SELECT count(*) FROM refresh_tokens    WHERE user_id = :'uid';             -- 0

-- Assignments, and this one is TWO counts on purpose. Phase 4's sweep is scoped to :ws_all —
-- deliberately, because an unscoped UPDATE would unassign the subject's work in every tenant
-- they ever touched — so a single unscoped `assignee_id = :'uid'` count cannot be 0 on any
-- install where 1(g1) reported out-of-scope rows, and asserting 0 would send the operator to
-- widen the sweep. Scoped must be 0; unscoped must equal what 1(g1) recorded, and those rows
-- STAY (they are decided one at a time with their workspace's owner, not swept).
SELECT count(*) FROM issues WHERE assignee_id = :'uid' AND workspace_id IN (:ws_all);  -- 0
SELECT count(*) FROM issues WHERE assignee_id = :'uid';   -- = 1(g1)'s out-of-scope total
--   The scoped one: 0, and 0 is its floor — above it, either phase 4's UPDATE did not reach
--     rows in :ws_all, or an issue in one was assigned to the subject after it ran (possible
--     until phase 5 removes the memberships, and for as long as a :ws_blocked workspace keeps
--     one). Both causes have the same remedy: re-run it; it is scoped and idempotent.
--   The unscoped one, HIGHER than 1(g1) recorded: a workspace is missing from :ws_all, or an
--     issue in one was assigned to the subject after phase 4. Either way rows exist that phase
--     4 should have cleared — fix the list, re-run phase 4, re-read.
--   The unscoped one, LOWER: somebody cleared one of those out-of-scope rows themselves. That
--     is the ordinary outcome of the conversation 1(g1) sends the operator into — each such row
--     is decided with its own workspace's owner — and it is not this procedure's doing. Record
--     it against 1(g1)'s breakdown; it is not a step that failed.
-- and: every workspace routed to 5.3(b) has >= 1 ACTIVE Owner;
-- and: phase 1(c), read off its own two flag columns. By this point no row can carry
--   workspace_deleted: those workspaces, and their projects, were deleted in phase 5, so that
--   column reads FALSE for everything that is left. BLOCKED-OPEN — no row whose
--   held_by_escalation is FALSE; inside a blocked workspace it legitimately still returns a
--   project, because phase 5 holds the subject's project_members row there (`bool_or` true) and
--   a b′ workspace has, by that branch's own definition, no OTHER member who can sign in
--   (`other_active_project_admins = 0`) — so the project genuinely has no administrator who can
--   sign in, which is the escalation's cost and not an artefact of the subject's own status.
--   Both halves are facts about OTHER people, which is why this reads the same before and after
--   the freeze and why the same row is a finding at neither. That is the
--   escalation's cost, recorded with it and re-read when it closes (phase 2's gate carries the
--   argument, §5.3(b′) the harm). Demanding zero of it at that reading asks for the held row to
--   be deleted, which is the orphaning that branch exists to refuse. NEVER-BLOCKED and
--   POST-CLOSE — no rows at all, and by the SECOND of the two facts that clear a row: phase 5
--   (and the close-out's step 4) removed the subject's project_members rows, so `bool_or` is
--   false and no project of theirs is in the result at all. The appointment step 3 made is what
--   satisfied the GATE at step 3, one reading earlier, and is why the project still has an
--   administrator who can sign in — it is not what empties this query;
-- and: every id in :ws_delete is gone from `workspaces`, and no id outside it is.
```

**The general reading. It governs anywhere this document tells a reader to read a value and
compare it** — an annotated count, an `Expect …`, a `DELETE 1`, a re-listing that must come back
empty, **a gate of the form "do not start phase N until X returns zero rows"**, a rehearsal
fixture's assertion — **in whatever section, phase and form it is written.** It is deliberately a
property and not a list, because a list of the steps that have needed fixing is what keeps
failing here: each round of this work has found an instance in a step the previous round's list
did not name, and the one that survived longest — phase 2's gate, unsatisfiable since HD-193 —
was in no list at all. A trailing "and not only the ones listed here" carries no load, because a
reader checks what is named. **It is what an operator gets wrong at 2am, so it is a short set of
rules and no counts:**

- **A check has as many expected values as this document has readings of it, and the reading is
  part of the expectation.** The same count is read at up to three points in one b′ run — while
  the escalation is open, after the close-out, and on a run where nothing was ever blocked — and
  a value that is correct at one is a failure at another: a `0` that is "the unrecoverable
  direction" at the blocked-open reading is the correct outcome at the post-close one. So a step
  that can be read more than once **names each reading and the value that belongs to it**, and a
  step written for a single reading is a defect even when every word of it is true. This is the
  failure this document has now shipped three times, and it survives review because the step
  reads correctly — for the reading its author had in mind.
- **An expected value is a number this procedure recorded, never the number that looks tidy.** A
  check may assert `0` only where everything it can see is the subject's — the row itself, *or
  the one column of it this procedure owns* — and a statement in this runbook was permitted to
  **clear or remove** all of them. Both widenings are deliberate: the scoped assignee count
  asserts `0` over the *team's* issues, which phase 4 never removes and is only permitted to
  null a column of, and a rule that said "the row belongs to the subject and a statement removed
  it" would invite a future editor to strip that correct `0`. Every other check asserts what
  phase 1 wrote down. A `0` annotated on a check that can legitimately see somebody else's row —
  another tenant's namesake, a workspace held open by a 5.3(b′) escalation, a legacy membership
  outside `:ws_all` — is a defect in this document, not a finding about the install, and it is
  fixed here rather than in the database.
- **Read both directions, and know which one is recoverable.** *Too high* nearly always means
  work was not done, and the remedy is to re-derive a list and re-run a scoped, idempotent
  statement. *Too low* means a row that was meant to stay is gone, and nothing in this document
  puts it back. Where only one direction is reachable — a count whose expected value is its own
  floor — the step says so, so the other direction is never left to be improvised.
- **The reachable repair for a red number is usually the destructive one, which is why a red
  number is recorded and not chased.** Deleting phase 4's `workspace_id IN (:ws_all)` drives a
  name-keyed count to zero by rewriting another tenant's rows; deleting the membership row a b′
  escalation is holding drives a membership count to zero by orphaning the workspace that branch
  exists to protect. Both are one keystroke, both look like fixing the check, and both are the
  harm. **A number that will not go green is a fact for the ticket, never a statement to widen
  or a held row to delete** — and a check that a correct run cannot satisfy is a bug in the
  check, to be reported as one.

Then boot or hit the running application: the subject cannot log in; an issue they reported
renders "Deleted user"; a comment they wrote is intact under "Deleted user"; a project they
administered still has an administrator. **These describe the completed erasure**, so they
belong to the never-blocked and post-close readings — with a 5.3(b′) escalation still open the
scrub has not run, the subject still renders by name, and the project in the blocked workspace
still has no administrator who can sign in. Make them once the b′ close-out has run phase 8.

**Phase 10 — close.** Append the ledger row (§8.2) — including the ownership transfers and
project-admin appointments phase 2 made, **and the ones every b′ close-out's step 3 made** —
upload it and **every round's key file** to `s3://<backup-bucket>/manual/erasures/`, and reply
to the subject confirming the deletion is done, **with no statement about periods**.

**The ledger's key list is the UNION of `erase-<request_id>-keys-1.txt` and every close-out
round's file, and `attachment_keys_deleted` is their SUM** (§8.2). Upload the files as they are
rather than concatenating them: which round deleted which objects is the only thing that says
*when* they went, and a run that stopped for an escalation deleted them weeks apart.

**Then destroy every remaining copy of the address, and there are two kinds.**

- **The local working files**: they hold `:addr`, `:name`, the derived workspace lists of every
  round, and every round's key and prefix file. Phase 9 is what established they are no longer
  needed, and phase 10 has just uploaded the key files — destroy the local copies only after
  that upload, not before.
- **The mailbox.** Delete the request thread, the message carrying the confirmation code, and
  its **Sent** copy — then empty the mail client's Trash of them. This is not tidiness: the
  intake thread carries the address and, because the in-app `mailto:` pre-fills it, the
  `User id:` line beside it. That pair is precisely the address↔id mapping §5.1 and §6.4 say
  does not exist, and it is the only surviving one after phase 8. Leaving the thread turns
  "erased" into "pseudonymised, with the key in the operator's inbox".

If the operator's own mail retention or legal-hold rules forbid that deletion, the erasure is
still complete on the database side, but §5.1's and §6.4's claims are qualified: **record the
mailbox as a residual on the ticket** rather than leaving those sentences standing unqualified.

---

## 12. Rehearsal plan (AC#1)

AC#1: *"The procedure has been executed once end to end, against a restored copy of
production."*

### 12.1 The warning that must not be buried

> **From step 3 of `ops-prod-hardening.md` §6.5 onward, the scratch container holds a full
> copy of production — every user's email address and password hash, and, if the globals file
> is applied, the production SCRAM verifiers. Rehearsing on it means handling real users'
> personal data on a workstation.**
>
> **That is the owner's decision to authorise, explicitly, and it is not something the
> pipeline does silently.** Concretely: no agent is given owner AWS credentials; no automated
> step fetches a production dump; nothing about this rehearsal runs unattended. The container
> is bound to `127.0.0.1` with a drill-only password (§6.5 steps 2 and 4b), and both the dump
> files and the container are destroyed afterwards (§6.5 step 8). A ledger row produced during
> a rehearsal is **discarded**, never appended to the real ledger.

### 12.2 Recommended sequence: synthetic first, then one confirmatory run

**Stage A — synthetic (no production data, iterate freely).** Bring up a fresh database
(`docker compose` on a scratch volume), register three accounts through the app, and build the
fixtures by hand: one solo workspace per account (the demo seeder gives you these for free);
one shared workspace where account A is the only Owner and B and C are members; one project
where A is the only Project admin; issues reported by A and assigned to B and vice versa;
comments by A mentioning B; an attachment uploaded by A; a shared and a private saved filter
owned by A; a pending invite addressed to A; a component led by A **with `auto_assign = true`**.
Then run §11 against A. Afterwards that component must still hold `lead_id = A` with
`auto_assign` still `true`, and **a plain rename of it through the API must still succeed** —
that is the assertion §6.1's reversed `components` verdict buys, and the earlier draft's
`UPDATE components SET lead_id = NULL` fails it with a 422.

**These fixtures exist specifically to fail the procedure if a guard is dropped, and a run that
does not carry them proves less than it looks. Each names the route it must take, because "it
survived" is satisfied by runs that did the wrong thing for the wrong reason:**

- **b′ — a workspace whose only other member is `DISABLED`** (suspend C through the admin
  console: a suspension, not an erasure), with A as its only `ACTIVE` Owner. It must route to
  **§5.3(b′)**, appear in `:ws_blocked`, and *not* in `:ws_release`. Three assertions, and the
  weak version of this fixture ("it must survive") passes a run that failed all but the first:
  the workspace still exists; **A's `workspace_members` row is still there**; and **phase 8 did
  not run** (A's address and display name are intact). If the workspace is deleted, the liveness
  predicate has reverted to `status <> 'DISABLED'`. If A is scrubbed, the b′ gate is missing.
  **Give this workspace a project, created by A** — one line of setup, and without it neither
  of the two paragraphs below is reachable at all. Creating a project writes its creator a
  Project admin `project_members` row, which is the ordinary b′ shape rather than an exotic one.
  **This fixture is also the one that exercises phase 9's non-zero expectations**: with it in
  play `workspace_members` reads 1, **`project_members` reads at least 1** (phase 5 holds it,
  and phase 1(g4)'s `held_by_escalation` column is where that 1 was recorded — a rehearsal that
  recorded only g4's overall total reads phase 9 as LOWER than expected and calls a correct run
  a failure), and the account row is unscrubbed. A rehearsal that records any of the three as a
  failure — or a check that demands 0 there — is asserting the orphaning.
  **And phase 1(c) must still return that project, at phase 2 and again at phase 9, with
  `held_by_escalation` TRUE on that row and without stopping the run**: there is no `ACTIVE`
  member in this workspace to appoint, so §5.3(d) has no candidate and the project genuinely has
  no signable-in administrator for the duration of the escalation. That is the cost §5.3(b′)
  accepts in the open. A rehearsal that "fixes" it by
  archiving the project, promoting C, or deleting A's `project_members` row has performed the
  harm and scored it as a pass.
  **Then close the escalation and run the close-out, because the blocked-open reading is only
  one of phase 9's three.** Reactivate C, then run the b′ close-out as written: re-derive (this
  workspace must now leave `:ws_blocked` and appear in `:ws_release`), re-run phase 2 for it
  (C promoted to Owner, and appointed to the project under §5.3(d)), then phase 5, phase 8,
  phase 9.
  **Assert step 3's gate BETWEEN the appointment and step 4, which is the reading that has to be
  taken on purpose:** with C appointed and A's held `project_members` row still present, 1(c)
  must return **no row with both flags FALSE**. This is where the gate had been wrong since it
  was written — A is `DISABLED` by then, so a count that includes them sees one ACTIVE
  administrator and reads the repaired project as stranded — and a rehearsal that jumps from the
  appointment to step 4 never takes it, because step 4 removes the row and empties the query for
  an unrelated reason. Neither the blocked-open nor the post-close reading can see this;
  only the one between them can.
  The post-close reading must be `workspace_members` **0**, `project_members` back to
  1(g4)'s out-of-`:ws_all` total (**0** in this fixture), **1(c) returning no rows at all**, and
  A's row **scrubbed**. A rehearsal that stops at the blocked-open reading proves the hold and
  never the release — and the release is where the held rows are supposed to go. It is also the
  only way to rehearse a step that re-derives the working lists instead of restoring them.
- **b — a workspace with a second Owner who is `DISABLED` *and* a third member C who is
  `ACTIVE` and not an Owner.** It must route to **transfer, to C** — not to "there are two
  Owners, nothing to do", and not to the suspended Owner B, who cannot sign in. The two-member
  version of this fixture is unsatisfiable and was in an earlier draft: with only A and a
  disabled B, `other_owners_active` and `others_active` are both 0, which is b′ and STOPs, so
  the fixture could never demonstrate a transfer at all.
- **Name over-match, both halves.** (i) *Anti-regression:* a display name containing `%` and one
  containing `_`. These are inert against `left(title, length(name)) = name`, which is the
  point — keep them as the tripwire that catches a future edit back to `LIKE '%' || name ||
  '%'`, where a member who renames to `%%` wipes every title in the table. (ii) *The live
  collision, which the first half does not exercise:* two members whose names are prefixes of
  one another — **`Ann` and `Anna`** — each with notifications and `issue_history` rows. Erase
  `Ann`. Only `Ann`'s rows may be rewritten; `Anna mentioned you` must be untouched. **Phase
  1(g3b) must surface the collision before phase 4 runs**, and 1(g3)'s sample must show
  `Anna`'s rows with `will_rewrite = false`. A run that produces `Deleted usera mentioned you`
  has the separator missing from the predicate.
- **Phase 5's guard, both of its halves, because they are not equally easy to trip.** (i) A
  workspace the subject does not belong to at all — this trips the first `EXISTS` and is the
  *easy* half. (ii) **A workspace id pasted out of 1(b) that is shared with a live member** —
  the realistic error, since every id in that report belongs to the subject, so only the `NOT
  EXISTS (un-erased other member)` half stands between a mis-paste and a live team's data. Both
  must report `DELETE 0`.
- **Route (c) actually firing.** A workspace whose only other member has already been through
  this procedure (scrub them first, or seed the tombstone address in its self-referential form).
  It must appear in `:ws_delete` and be deleted with its contents. Without this fixture the
  suite proves only that workspaces are *not* deleted, which every broken predicate also
  achieves. **Give it a project the subject created**, and check phase 2's gate against it: the
  project appears in 1(c) with `workspace_deleted` TRUE and **does not stop the run**. That row
  is not exotic — the demo workspace produces one on every real erasure — and a gate that reads
  1(c) as a row count instead of a partition fails here, on the most ordinary install there is.

*What Stage A proves:* the SQL is correct and ordered correctly; the FK behaviour on the
workspace delete is what §11 phase 5 claims; the guards' invariants survive; the pre-flight
report is accurate; the procedure is idempotent on a second run; both storage backends delete
what they should; the application behaves correctly afterwards.

*What Stage A cannot prove:* anything about production's actual data shape — the real
distribution of sole-ownerships and inherited-administrator configurations, rows written by
older code versions that today's entities no longer produce, and the runtime of the scoped
`LIKE` sweeps at production size. It also never exercises `pg_restore`, the globals file, or
the drill's own step 4b.

**Stage B — one confirmatory run on a restored copy.**

1. Beforehand, on production, the owner registers a **throwaway account of their own** and
   gives it a realistic footprint: accept an invite into an existing workspace, file two
   issues, get assigned one, write a comment mentioning somebody, upload an attachment, save a
   shared and a private filter. Wait for the next daily dump (03:15 UTC).
2. Restore that dump per `ops-prod-hardening.md` §6.5 steps 1–5, **including step 4 (globals)
   and step 4b (restore the drill password)** — the half of the drill the 2026-08-26 run did
   not walk. This rehearsal is therefore also the first full walk of §6.5, which is worth
   recording as such.
3. Run §11 phases 1–9 against the throwaway account. Boot the application against the scratch
   database (§6.5 step 6, `--spring.docker.compose.enabled=false`) and check phase 9's
   behavioural assertions in a browser.
4. Tear down per §6.5 step 8. Discard the rehearsal ledger row.

**The subject of the rehearsal is always an account the owner created.** Never another
person's. The copy still contains everyone — that is what §12.1 is about — but nothing in the
procedure is *aimed* at a third party's data.

5. Record the run in **`ops-prod-hardening.md` §6.8 "Erasure drill log"** — which exists and is
   empty — in the same
   shape as the restore-drill log: past tense, dated, with the counts before and after, the
   workspaces deleted, the object keys removed, and any step that did not run. **A row written
   in advance is not a row.**

### 12.3 The synthetic-only fallback, and exactly what it costs

If the owner declines to authorise Stage B, Stage A alone is a defensible pass **provided the
deferral is written on the ticket**. It gives full confidence in the *correctness* of the
procedure and none in its *fit to production*: nobody will have seen the pre-flight run
against real membership data, and the first production erasure becomes the first real
execution — which is precisely the "no runbook behind the email" state this ticket exists to
leave. The recommendation is to do Stage B, because the throwaway-account trick makes its
marginal privacy cost small and its evidentiary value large.

---

## 13. Highest-risk assumption

**That re-attributing content to "Deleted user" makes it anonymous.**

On the controller's side it does: the address, name and credentials are destroyed, no mapping
is kept, and the remaining UUID identifies nobody. On the **community's** side it may not. In
a five-person workspace, one departed colleague's comments remain individually
distinguishable, sit in threads whose other participants remember the conversation, and are
surrounded by other members' comments that type the departed person's name in plain text
(mentions are stored as display-name text, and §6.5 forbids editing another person's writing).
Re-identification by a remaining member is not merely possible; in a small team it is
automatic.

This cannot be engineered away without destroying the team's history, which the ticket rightly
rules out. What is done about it: the product-generated occurrences are swept
(`issue_history`, `notifications`), and the residual is **disclosed** in the policy copy
(§4.3) rather than glossed. What is not done about it is a decision the owner should take
knowingly, and it is open question 10 — because whether "anonymisation the controller cannot
reverse but a colleague can" satisfies an erasure obligation is a legal question, not an
engineering one.

**The highest-risk *operational* assumption, which is a different thing:** that §11's phases are
run in order, in one session, by somebody who reads each output before starting the next.
Nothing enforces that — there is no application layer here to refuse. The document answers it
the only way a document can: by moving the safety out of the instructions and into the
statements, so that the two mistakes an operator will actually make (pasting the wrong id,
running a step too early) produce `DELETE 0` and a missing psql variable rather than a deleted
tenant. Every place where that could not be done — phase 2's governance work, phase 7's object
deletion — is called out as needing a second pair of eyes rather than trusted to care.

**Second-order risk:** the procedure is manual, and a manual procedure that is executed once
becomes stale the moment a table gains a user reference. §6.1's table is the artefact that
goes stale, and it goes stale **one entry before anyone notices**. The mitigation for 1.0.0 is
that the endpoint makes the sweep code, which a test can seal; until then the mitigation is a
line in the migration checklist: *a new column referencing `users` requires a verdict in the
account-deletion table.*

---

## 14. Open questions

### Blocks the build

1. **Auto-promotion of a new Owner (§5.3(b)).** Does the owner accept that the procedure
   promotes a colleague when the subject declines or does not answer?
   *Recommendation: yes, with mandatory notification to every remaining member.* A stalled
   erasure is a statutory risk and a bricked workspace is permanent; the length of the "we
   asked first" window is deliberately unstated (a number there is HD-192-shaped).
2. **Which members keep a workspace alive?** *Answered, not open:* the ones who have **not been
   erased** — `email <> 'deleted+' || id::text || '@deleted.invalid'`, the self-referential form
   §5.1 argues for, never the domain match an earlier draft used (nothing stops a live account
   holding an address in that domain). That predicate subsumes the `PENDING`
   question this entry used to ask, and it replaces `status <> 'DISABLED'`, which was wrong in
   a way that mattered: `DISABLED` is also what an administrator writes when suspending a live
   human being, so the old test deleted a suspended colleague's entire workspace while
   justifying it with "by construction there is nobody else". Left in the list only so the
   owner sees the change (§5.3).
3. **Sweeping product-generated text.** Confirm §6.5's rule — erase text the product generated
   about the person, never edit text a person wrote — **and its second half: erase by rewriting
   the value, never by deleting the row.** *Recommendation: adopt both as stated.* The
   fail-safe argument splits, and the split is the point: over-matching a **rewrite**
   (`issue_history` values, notification titles) costs legibility in a row that still exists,
   so over-matching is the safe direction there; over-matching a **delete** destroys a third
   party's data irreversibly, so there is no safe direction and the sweep must not be a delete.
   The notification **body** is untouched entirely — it can carry another member's writing, and
   deleting their inbox item about their own work was never required by §6.5.
4. **`mail_send_events` split (§6.2)** — delete rows where the subject is the recipient, matched
   on `recipient_email` **only**; null `sender_user_id` where they were the sender.
   *Recommendation: as specified.* Matching on `recipient_key` as well looks more thorough and
   is worse: the key folds `+tag` and Gmail dots, so it can span two different accounts, and
   the residue it would remove (a row addressed to a literally different address) is smaller
   than the harm it would cause (a third party's rows destroyed and their anti-harassment
   ceiling reset).
5. **Shared saved filters.** *Recommendation: keep them* (workspace tooling), delete private
   ones. The alternative silently removes a team's saved views on an unrelated person's
   departure.

### Blocks the publish (HD-192)

6. **Every period.** Deleted-account residue, server logs, `failed_email` (90 d) versus
   `mail_send_events` (7 d), backup retention (30 d). None may appear in product copy until
   HD-192 supplies the policy.
7. **Response time and mailbox.** Whether `support@hamstrack.com` is monitored, whether a
   separate privacy address is wanted, and what response time is stated. The copy in §4.3
   states none — confirm that is acceptable as an interim.
8. **Lawful basis** for retaining anonymised content and mail-delivery logs after an erasure
   request.
9. **May the backup-replay sentence (§4.3, third paragraph) ship?** *Recommendation: yes* — it
   states a procedure, not a duration, and it is the honest answer to a real gap. It is
   nonetheless the one line in this ticket's copy that a lawyer should see; if the owner
   prefers zero new statements about backups, drop that paragraph and record AC#2 as partially
   deferred.
10. **Is anonymisation-in-place accepted as erasure** for this controller's jurisdiction,
    given §13's residual? The engineering answer is that hard deletion is impossible without
    destroying third parties' data; the legal answer is not ours.

---

## 15. Architectural decisions (ADR-worthy)

Each of the following settles a fork that is expensive to reverse and that a future contributor
would otherwise ask "why?" about.

**ADR-0023 — Erasure is anonymisation in place; the `users` row is scrubbed, never deleted.**

- *Chosen:* overwrite identity columns on the existing row, disable it, and leave every
  authorship reference pointing at it. The tombstone predicate is **self-referential** — the
  address must contain the row's own id — because "written by exactly one procedure" is a claim
  about history that nothing enforces, and a live account holding an address in the reserved
  domain would otherwise read as erased and get its workspace deleted.
- *Rejected — `ON DELETE CASCADE` on the authorship FKs:* destroys other people's issues,
  comments and audit rows; erasure of a person is not deletion of a team's history.
- *Rejected — a shared "Deleted user" tombstone plus a real `DELETE`:* eight bulk `UPDATE`s per
  erasure, and it collapses every departed person in an install into one identity, making a
  multi-author audit trail unreadable.
- *Rejected — leaving the row wholly intact and only revoking access:* that is deactivation,
  which already exists and is not erasure.
- *Trade-off:* the row persists, so "deleted" must be explained rather than assumed, and the
  residual in §13 is real and disclosed.

**ADR-0024 — A workspace outlives its creator's account; erasure never orphans a tenant.**

- *Chosen:* transfer ownership when other members remain (nomination, else longest-tenured
  eligible member, with notification); delete the workspace only when nobody else is left;
  never leave a workspace without an Owner or a project without a `project.member.manage`
  holder. Governance is resolved **before the freeze**, because the freeze is the step that
  first writes `status = 'DISABLED'` and blinds every guard's `ACTIVE` filter — gating the
  scrub instead looks like the same protection and lets the harm through.
- *Rejected — refuse the erasure until the subject nominates:* hands a statutory clock to
  someone who has asked to stop dealing with us, and is unsatisfiable for a solo Owner.
- *Rejected — delete every workspace the subject owns:* destroys other members' work over one
  person's departure.
- *Rejected — orphan it:* the exact failure mode HD-132/HD-136/HD-127/HD-130 exist to prevent,
  recoverable only by hand.
- *Trade-off:* the product sometimes chooses who owns a team's data. Mitigated by asking first
  and telling everybody afterwards.

**ADR-0025 — Backups are never edited; erasure is made durable by replaying an address-free
ledger after a restore.**

- *Chosen:* leave backup objects immutable; keep an append-only ledger outside the restorable
  database (`manual/` in the write-once bucket) carrying ids only — the erased `user_id`, the
  workspaces deleted and retained, and the successors appointed — and never an address or a
  name; make the replay a gate on any production restore, run **in the runbook's own order**
  (pre-flight, sweeps, checks, scrub, verify) rather than as a subset that reaches the scrub
  before its gate. An entry is pruned to its accountability fields once no restorable dump
  predates it, because nothing expires `manual/` but the entry's purpose expires anyway.
- *Rejected — editing or re-writing dumps:* not feasible on `pg_dump` custom-format archives,
  and making them mutable destroys the property that makes them backups.
- *Rejected — shortening backup retention so erasure "completes" sooner:* trades a durability
  guarantee for a compliance appearance, and would state a period this ticket may not state.
- *Rejected — putting the ledger in the application database:* restoring a pre-erasure backup
  restores a pre-erasure ledger, so the record vanishes exactly when it is needed.
- *Trade-off:* a deleted account is resurrectable by a restore until the replay runs, so the
  replay is mandatory and must be gated *before* the instance serves traffic.

Drafts written to `docs/adr/0023-erasure-by-anonymisation-in-place.md`,
`docs/adr/0024-workspace-outlives-its-owner.md` and
`docs/adr/0025-erasure-ledger-replayed-after-restore.md`, all `Status: Proposed`; the
orchestrator flips them to `Accepted` once the change ships.

---

## 16. Acceptance criteria

**Decisions & documentation**

1. This document exists and states, for every table referencing `users`, the declared
   `ON DELETE` and one of delete / anonymise / retain (§6.1).
2. The owner-of-last-resort rule is stated for every branch of §5.3 — including the blocked
   one, which is the branch a reader is most likely to improvise past — and the runbook
   resolves governance **before the freeze** (phase 3, the first statement that writes
   `status = 'DISABLED'`), before any membership row is removed, and before the scrub.
2ʹ. **Phase 3's preconditions cover every guard the API applies to the writes phase 3 makes**,
   not only the tenant-shaped ones: because the freeze clears `system_role` as well as `status`,
   phase 1(a) counts the `ACTIVE` system administrators other than the subject and phase 2
   refuses the freeze while that count is zero, with "appoint another administrator first" as
   the stated, still-performable remedy (§5.2). Phase 1(a) also probes the subject's tombstone
   address, and a row there is resolved before phase 3 rather than discovered by 8b's unique
   violation, after the irreversible phases have run.
3. `docs/ops-prod-hardening.md` carries §6.7 (erasure ledger + the restore gate) and §6.8
   (erasure drill log, empty until §12's run); §6.7 states that a drill against a scratch
   container is not a production restore, and §6.2's "a GDPR erasure request needs no backup
   surgery" sentence is reconciled with it rather than left to contradict it.
3ʹ. **§6.7's replay runs §11 in §11's own order** — phase 1 in full including its counts, then
   3–6, then **8a's checks, then 8b**, then phase 9 — never the scrub ahead of its own gate, and
   never without the pre-flight. It names owner credentials from a workstation for reading the
   ledger bodies (the instance role holds no `s3:GetObject`), and it states when a ledger entry
   stops being replayable and may be pruned.

**The runbook's tenant guards** (these are what the `tenancy-reviewer` gate re-reads, and each
one is checkable by reading §11 alone)

3a. No statement in §11 scopes by a workspace id that an operator typed into it, except phase
   5's `:ws` — which carries an `EXISTS`/`NOT EXISTS` guard on the subject's membership, so a
   wrong id reports `DELETE 0` rather than deleting a tenant.
3b. The workspace deletions run **before** the subject's `workspace_members` row is removed.
3c. No sweep keyed on the subject's display name is a `DELETE`, and none builds a `LIKE`
   pattern from that name.
3d. Every statement that can change a row in a workspace outside `:ws_all` is pre-counted in
   phase 1 *including its out-of-scope rows*, and phase 8a carries at least one **unscoped**
   check whose unexpected result names both of its possible causes. The claim in phase 1(g) is
   written as a property over statements, never as a count of them.
3e. The assignee sweep is scoped by workspace, not by `assignee_id` alone.
3f. **Every verification step in this document asserts a value a correct run can actually
   produce — at every point in a run where this document has it read — and states what a
   deviation in *each* direction means.** The criterion is over the
   category: **anywhere this document tells a reader to read a value and compare it** — an
   annotated count, an "Expect …", a `DELETE 1`, a re-listing that should come back empty, a
   rehearsal fixture's assertion — in whatever form and whatever section it is written in. It is
   deliberately *not* a list of the steps that have needed fixing, and it carries no count of
   them: every round of this work found instances that had survived a criterion naming the
   previous round's, and the next round will survive an enumeration of these. Read as a set of
   properties — **and they are not checked the same way**, which is the trap this criterion set
   for its own reviewer:
   - **Satisfiability.** No step asserts a value that another statement in this document
     guarantees it cannot hold. A step whose expected value is a number the pre-flight recorded
     names that number and never `0`; `0` appears only where everything the check can see is
     the subject's — the row, or the one column of it this procedure owns — and a statement here
     was permitted to **clear or remove** all of them.
     **This property is inherently cross-referential and CANNOT be checked by reading a step in
     isolation**, because the statement that defeats a step is written somewhere else — which is
     why a review pass that walks the steps one at a time returns clean over it. The instance
     that survived longest here was a phase-2 gate demanding zero rows from a query that two
     other sections guarantee will return one — §5.3(b′), and (found a round later, in the same
     gate) §5.3(c), whose deleted workspaces are the ordinary case rather than the exotic one.
     Check it the other way round, and over
     **every expected value** rather than only the zeros: take each `0`, each "returns zero
     rows" **and each "equals what phase 1 recorded"**, and ask — **for each reading this
     document mandates of it** — which branch of §5.3, which deliberately held row or which
     other tenant's data could put something there, and which later step could take it away.
     The load-bearing word is *reading*: one b′ run reads the same check while the escalation is
     open and again after the close-out, and a method that takes only the zeros returns clean
     over a recorded NON-zero expectation that a later step legitimately empties — which is
     exactly the instance this criterion missed last.
   - **Both directions** — and this one *is* checkable by reading the step in isolation. Each
     step says what *higher* and what *lower* mean, and which of the two is recoverable. Where
     only one direction is reachable — a count whose expected value is its own floor — the step
     says that, rather than leaving the other direction to be guessed.
   - **The repair is named and, where it is destructive, forbidden** — also checkable by reading
     the step in isolation. Wherever the obvious way to turn a red number green is widening a
     workspace-scoped statement or deleting a deliberately held row, the step names that action
     and refuses it, and says the number is recorded on the ticket instead. A verification
     step that cannot go green teaches an operator to satisfy it the wrong way, under time
     pressure, at this runbook's most destructive moment — so an unsatisfiable check is a
     defect of the same class as a missing tenant predicate, not a wording nit.
3g. Every exclusion for a 5.3(b′) escalation is expressed as an `IN` over a **derived
   complement** (`:ws_release`), never as a `NOT IN` over the blocked list and never for a
   single hand-typed workspace — and when the escalation closes, that complement is
   **re-derived from the database** by the b′ close-out, never edited to admit the released id.
3h. The liveness predicate is **self-referential** everywhere it appears (`email = 'deleted+' ||
   id::text || '@deleted.invalid'`), in §5, §8, §11 and all three ADRs. `rg "LIKE '%@deleted"`
   finds nothing outside the paragraphs explaining why that form was rejected.

**The procedure's own guards** (these are what `security-officer` re-reads)

3i. **The subject is derived, never typed.** §11 phase 1's only hand-entered value is the
   address the confirmation code was returned from; `:uid` comes from a `SELECT` on it. The
   request's `User id:` line appears in the runbook only as a value to *compare* against, with
   "proceed against neither" as the stated outcome of a mismatch.
3j. **The code is sent in a newly composed message**, addressed from `users.email` on the row
   the address resolved to — stated against the mail client's Reply default, in both §5.5 and
   phase 0.
3k. **Governance blocks the freeze.** §5.3(b′) and §11 phase 2 gate **phase 3**, not phase 8,
   and where the freeze proceeds anyway the runbook requires notifying the workspace's remaining
   members and recording the escalation with a review date.
3l. **Phase 10 destroys the mailbox copy** — request thread, code message, Sent copy — and §5.1
   and §6.4 state the mapping claim as qualified until it has.

**Rehearsal**

4. §12 Stage A has been run on a synthetic database and every phase-9 assertion passed,
   including a second (idempotent) run, and the fixture set in §12.2 is carried in full — each
   fixture asserting the **route taken**, not merely that something survived. "Passed" means
   each check read the value phase 1 recorded for it: a fixture set that exercises the branches
   which deliberately hold rows makes some of those values non-zero, and a rehearsal that scores
   itself on zeros is scoring the orphaning as a pass (3f).
5. §12 Stage B has been run against a restored copy of production with the owner's explicit
   authorisation, using a throwaway account the owner created; the run is logged in §6.8 in the
   past tense with its date, and any step not walked is named. *(If the owner declines, the
   deferral is recorded on HD-193 and criterion 5 is marked deferred — never silently passed.)*
6. The drill artefacts are gone: `docker rm -f`, the dump directory removed, no rehearsal row
   in the real ledger.

**Product**

7. `/account` exists, is reachable from the NavRail user menu, renders for an authenticated
   user with no workspace, and renders its Delete account section **whether or not**
   `privacyContactEmail` is configured.
8. The page states the three mechanism sentences, "This cannot be undone", a link to
   `/privacy`, the contact address as text and as a `mailto:` with a copy control, and the
   confirmation-code sentence — and states **no** reply time, **no** duration and **no**
   request status.
9. No new endpoint, table, migration or permission ships beyond the `/api/meta` field.
10. `/api/meta` returns `privacyContactEmail`; the default is **empty in every profile**, and no
    `application-*.properties` file carries a value — the hosted deployment's address is set in
    that deployment's own `.env` (§10). `rg PRIVACY_CONTACT_EMAIL src/main/resources` finds the
    one line in `application.properties` and nothing in a profile file. `openapi.yaml` and both
    `docs/api-*.md` carry the field and swagger-cli validates.

**Legal copy**

11. `PrivacyPage.tsx` §5 reads as §4.3 specifies; `lastUpdated` is bumped to the ship date.
12. `PrivacyPage` §6, `TermsPage.tsx` and `CookiesPage.tsx` are byte-identical to `main`.
13. No period, deadline, entity name or Article reference appears in any copy this ticket
    ships; `rg -i "within [0-9]+|[0-9]+ days|business days"` finds nothing new under
    `src/main/frontend/src/pages/legal/`.
14. `PublishedClaimsTest` is green: the new copy contains no `beta`, no
    `… (may|will) be (reset|wiped|erased|cleared)` construction, and **not** the fragment
    `backed up daily` (§4.5).

**Build**

15. `npm run build` (`tsc -b && vite build`) passes; the Account page uses existing `DESIGN.md`
    tokens with no hardcoded hex.
16. `./mvnw -B verify` is green.

---

## 17. Reviewer routing

- **`tenancy-reviewer`** — applies, and **the artefact to read hardest is §11, not the code.**
  The code half of this ticket is small and scoped by the framework; §11 is executed by a human
  against production with no application-layer guard, so it *is* the implementation. Every
  sweep keyed by anything other than `users.id` must carry the pre-flight's derived workspace
  list, and every statement that names a workspace must refuse a wrong id by construction
  (AC 3a–3e).
- **`security-officer`** — applies: identity verification and the confirmation code's
  specification (§5.5), the forgeability of the intake message, the unauthenticated
  `/api/meta` field, and the absence of an erasure oracle on both the product side and the
  mailbox side. **Read §5.5 and phase 1's first statement together**: the check is only worth
  anything if the row it verifies is the row that gets destroyed, and a request carrying two
  independent identifiers can name two different people. Also §5.1's liveness predicate, which
  is a security control (it decides whether a workspace may be deleted) and must be unforgeable
  by a value any account holder can choose.
- **`dc-cloud-guard`** — applies: one new property, **no profile-specific default in any
  profile**, and the wiring chain `application.properties` → compose → `.env.prod.example` →
  `README` / `docs/self-hosting.md`. Note what is deliberately *absent* from that chain:
  `application-cloud.properties`. The cloud profile is what `docker-compose.prod.yml` selects
  by default, so it is a self-hoster-reachable file, and a Cloud address in it would route
  other installations' erasure requests to us (§10).
- **`api-docs-sync`** — applies (one response field).
- **`migration-reviewer`** — `n/a` for 0.18.0; required for the 1.0.0 `users.deleted_at`
  column.
- **`frontend-builder`** — the Account page and the `PrivacyPage` edit. **`backend-builder`** —
  the property, the `AppProperties` record and the `/api/meta` field, and nothing else.
