# ADR-0023: Account erasure is anonymisation of the `users` row in place, not its deletion

Record date: 2026-09-02
Status: Proposed
Source: `docs/design/account-deletion-proposal.md` (HD-193) §5.1, §6.1;
the schema — `V1__init_schema.sql`, `V5__saved_filters.sql`, `V8__labels.sql`,
`V9__components.sql`, `V11__sprints.sql`, `V18__reports_foundations.sql`

## Context

`PrivacyPage` promises to delete or anonymise personal data on request. There is no mechanism of
any kind: no endpoint, no UI, no hand-written procedure. And "just delete the row" in this schema
is **physically impossible**.

Nine columns reference `users` with no `ON DELETE` clause, i.e. with the default
`NO ACTION`: `workspaces.created_by`, `projects.created_by`, `issues.reporter_id`,
`issues.assignee_id`, `issue_comments.author_id`, `issue_history.changed_by`,
`issue_attachments.uploaded_by`, `saved_filters.owner_id`, `workspace_invites.invited_by`.
`DELETE FROM users WHERE id = …` fails on the very first of them.

So the fork is real, and it has exactly three coherent branches: hand those FKs `CASCADE`,
re-point them at a shared tombstone row and delete the real one, or not delete the row at all and
erase the identity inside it.

It matters separately that some of the FKs to `users` are already declared `ON DELETE SET NULL` —
and declared **deliberately, so that the history outlives the account**: the comment on
`components.lead_id` (V9) says outright "survives deletion of the lead's account", and the one on
`sprint_scope_events.actor_id` (V18) — "the event belongs to the sprint and must outlive the
account that caused it". That is, the intent "the team's history is not destroyed together with
the person" is already recorded in the schema, it has simply never been carried through into a
procedure.

## Decision

**The `users` row is never deleted. It is anonymised in place with a single `UPDATE`:**

- `email` → `deleted+<user_id>@deleted.invalid` (the `.invalid` domain is reserved by RFC 2606 and
  cannot receive mail; the value is unique by construction, so both uniqueness constraints —
  `users_email_key` and `users_email_lower_uk` from V23 — are satisfied);
- `display_name` → `Deleted user`;
- `avatar_url` → `NULL`;
- `password_hash` → `NULL`;
- `status` → `DISABLED`;
- `system_role` → `USER`;
- `demo_seeded_at` **is not touched** — a `NULL` there would re-arm demo seeding and create a new
  Demo Workspace for the dead account on the next authentication attempt.

All authorship references (`reporter_id`, `author_id`, `changed_by`, `uploaded_by`, `created_by`,
`invited_by`, `actor_id`, …) stay as they are and point at the anonymised row. **Not a single
`UPDATE` is run over those tables** — and that is the decision's main win.

**No mapping "anonymised id → former address" is created by any long-lived store of this
procedure** — not a table, not a file, not the erasure ledger (ADR-0025). It is precisely the
absence of such a mapping that makes the result anonymisation from the operator's side rather than
pseudonymisation.

The claim is exactly that narrow: **one mapping exists while the procedure is running, and it does
not live in the database — it is a mailbox**. The request email carries the address in its headers
and, because of the `mailto:` template on the account page, the `User id:` line next to it. That is
why phase 10 of the runbook destroys the correspondence, the email with the code and its copy in
"Sent", together with the local working files. Until that is done — or if the operator's
mail-retention rules forbid deletion — the mailbox remains a residue, and the wording above holds
with a caveat that is recorded in the ticket.

Separate branches — deletion, not anonymisation — apply to rows that are the person's own
instrument or inbox and mean nothing to anybody else:
`refresh_tokens`, `email_verifications`, `password_resets`, `oauth_accounts`,
`notifications`, `comment_mentions`, private `saved_filters`, membership rows. The full
table of verdicts is in the spec, §6.1.

## Consequences

+ The promise in the policy becomes deliverable: "delete or anonymise" now has a mechanism.
+ The team's history is intact: issues, comments and the audit trail outlive the author's departure
  and stay readable.
+ Everyone who has left stays **distinguishable from the others who left** without being
  identifiable: two different "Deleted user"s in one discussion thread do not merge into one.
+ The cost of an erasure is one `UPDATE` on `users` plus pinpoint deletions; there are no bulk
  rewrites of foreign keys.
+ `JwtAuthenticationFilter` re-reads the user on every request and filters by
  `User::isEnabled()` (`status == ACTIVE`), so `DISABLED` kills live sessions on the very
  next request — there is no need to wait for the access token to expire. **This is true for
  requests and false for SSE streams that are already open:** membership is checked at subscription
  time and never again, and the only thing that closes an emitter is the `WorkspaceMemberRemoved`
  event, which raw SQL does not publish — which is why the runbook restarts the application as a
  separate step.
− **`DISABLED` is a consequence of the anonymisation, not a marker of it.** The same value is
  written by an administrator suspending a live person, and the same value remains after an
  interrupted erasure. Reading `DISABLED` as "this is no longer a person" is the mistake that sends
  a workspace with a suspended colleague down the deletion branch.
− **The marker "this row has been erased" is the whole address, and it must contain the row's own
  id:** `email = 'deleted+' || id::text || '@deleted.invalid'`. Not `LIKE '%@deleted.invalid'`: that
  is a claim about *who* writes such a domain, and nothing in the product enforces it — there is no
  domain blacklist either at registration or in `AdminUserService.create` (which on top of that
  sets `status = ACTIVE` without verification), and `@Email` accepts the `deleted.invalid` domain.
  A live member with such an address would be invisible to the broadest liveness check, and his
  workspace would go down the deletion branch together with all of its content. The self-referential
  form is unforgeable **by construction**: to satisfy it you must know an id that does not exist
  before the row is inserted, and there is no address-change door in the product at all (`setEmail`
  is called at registration, in admin creation and in the seeder — always before the id exists and
  never after). The property is phrased this way rather than through authorship history: the claim
  "written by exactly one procedure" goes stale on any new registration path, while "the value
  contains the row's own id" does not.
− **The row physically remains.** The word "deletion" has to be explained rather than implied;
  the wording in the policy must say what exactly disappears.
− **The anonymisation is reliable only from the operator's side.** In a small workspace the
  remaining colleagues will recognise the departed from context, and his name may survive in other
  people's comments (mentions are stored as the display-name text). The residue is disclosed in the
  policy rather than hushed up; fixing it by editing other people's texts is forbidden.
− The verdict table in §6.1 is an artefact that goes stale **one entry earlier** than anyone
  notices: a new column referencing `users` must be given a verdict. Until the endpoint exists
  (1.0.0) this is a checklist rule, not a test.
− After an erasure the address is released, and the person can register again as a new account. No
  suppression list is kept — it would be a long-lived store of exactly what the procedure destroys.

## Alternatives

- **`ON DELETE CASCADE` on the nine FKs and a real `DELETE`** — rejected: it destroys
  **other people's** data. It would take out the issues raised by the departed together with all
  the discussion in them, the audit rows and the attachments that are the team's work product.
  Erasing a person is not deleting the team's history.
- **A shared "Deleted user" tombstone row and a `DELETE` of the real one** — rejected for two
  reasons: it is eight bulk `UPDATE`s per erasure, and everyone who has left the installation
  merges into one indistinguishable identity, which makes an audit trail with two departed authors
  unreadable.
- **Keep the row whole and only revoke access** — rejected: that is deactivation, it already
  exists (`status = DISABLED` through the admin console) and is not an erasure: the address, the
  name and the password hash remain.
- **Introduce a new `UserStatus.ERASED` value instead of `DISABLED`** — rejected for 0.18.0:
  a new constant silently changes the meaning of every existing branch on `status`, including the
  `ACTIVE` filters in the last-administrator guards. When an explicit marker is needed — it is an
  additive `users.deleted_at` column, which changes nothing that does not ask for it.
