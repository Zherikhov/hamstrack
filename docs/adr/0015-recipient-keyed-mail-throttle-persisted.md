# ADR-0015: Throttling outgoing mail by recipient address — the state lives in its own append-only table, not in memory and not derived from a business row

Record date: 2026-08-27
Status: Accepted
Source: `docs/design/invite-budget-proposal.md` §6.2, §7, §13 (HD-190);
the existing limiters — `common/ratelimit/PerPrincipalMinuteBudget.java`,
`common/ratelimit/RateLimitService.java`; the precedent for a journal table — `V7__failed_email.sql`

## Context

`WorkspaceService.inviteMember` sends mail to the address the caller typed, without a single limit.
Public registration is enabled in production, so an account costs one disposable mailbox, and
workspace creation is unbounded. The attack the ticket was filed for is **one victim, many
workspaces the attacker creates for them**: to "ring the doorbell" as many times as they like.

There is exactly one key that sees this attack: **the recipient address**, with no tie to a
workspace. A workspace-keyed limit looks at the wrong dimension (there is exactly one invite per
workspace), and the partial functional index `workspace_invites_pending_email_uk` from HD-133 —
`UNIQUE (workspace_id, lower(email)) WHERE accepted_at IS NULL` — looks at the same place.

What makes this a fork is not the choice of key but **where its state lives**. The application has
five limiters, and all five keep their counters in a `ConcurrentHashMap` inside the process. A sixth
suggests itself in the same shape. But a control against harassment has a requirement a load budget
does not:

- The state must survive a deploy. A cooldown that a deploy resets is a cooldown the attacker waits
  out.
- The state **must not be resettable by the parties' actions**. And here the obvious cheap option —
  "add nothing, derive the cooldown from `workspace_invites`" — turns out not merely weak but
  harmful: three existing paths delete the invite row, and one of them is pressed by **the victim
  themselves**. `declineInvite` does a `DELETE`. That is, the product would punish exactly the action
  it itself offers: decline an unwanted invite and you unblock the attacker's next send. The second
  path is `deleteUnacceptedByWorkspaceAndEmail` on member removal (available to the attacker in his
  own workspace). The third is `ON DELETE CASCADE` from `workspaces`: there is no workspace deletion
  endpoint today, but correctness that rests on the absence of an endpoint breaks in a future
  ticket, and silently.

On top of that: HD-202 (throttling forgot-password and resend-verification by the address sent to) is
literally the same key and the same problem, "the caller chose someone else's address and we are the
ones sending the mail".

## Decision

The state of address-keyed throttling **is persisted in its own append-only table**
`mail_send_events`, shared by every outgoing-mail path.

- A row is written on every send: `email_type`, `recipient_email` (exactly what was typed, in lower
  case — the refusal echoes it back and the operator investigates by it), `recipient_key`
  (`MailAddresses.throttleKey` — **the only thing the ceilings count**), `sender_user_id` (NULL for
  the anonymous senders of HD-202), `workspace_id`, `created_at`.
- **The key is the MAILBOX, not the spelling of the address.** On the raw address the whole
  mechanism is decorative: `victim+1@`, `victim+2@`, `v.i.c.t.i.m@googlemail.com` are different
  strings landing on one person, so the attack from the ticket is rewritten at the price of one
  keystroke while both counters read zero. Hence the key folds away `+tag` and — for
  `gmail.com`/`googlemail.com` — the dots and the domain. **For a ceiling, over-folding is safe** (an
  extra match raises the counter and refuses earlier) while under-folding is a hole; on the invite
  **redemption** path (HD-120) it is the other way round, there an extra match would let someone
  accept another person's invite, and addresses are compared exactly. The arguments must not be
  carried between these paths. The mail always goes to `recipient_email`, never to the key.
- **The daily per-recipient ceiling counts not sends but "your own one by one, other people's one
  per sender".** Counting raw sends turned the guard into a weapon: one account with five lawful
  sends blocked invites to the named person **from the whole instance** for a day, ~5 requests per
  day, indefinitely and past both alerts. Counting only unique senders would have gone to the other
  extreme — one account would ring the doorbell all day within its own cooldown. The two halves
  together preserve the original arithmetic of §6.3 (five disposable accounts with one invite each
  trip on the sixth) and return the price of the attack to "one mailbox per slot".
- **There is not a single foreign key** — neither to `users` nor to `workspaces`. The point of the
  table is to survive the deletion of what it describes; `ON DELETE CASCADE` would recreate exactly
  the hole the table exists for, and `RESTRICT` would break user deletion against throttling
  bookkeeping. The precedent for the shape is `failed_email` (V7): an install-level journal with a
  recipient address and no FK.
- **The table is not workspace-scoped and is never read through a user-facing surface.** The
  repository returns only aggregate counters; there are no methods returning entities or addresses,
  and that is pinned down by a test. `workspace_id` is written and never queried — it is there for
  the operator investigating a fired alert, because by the bounded-cardinality rule metrics cannot
  carry identifiers.
- Precision where it is needed: before counting, `pg_advisory_xact_lock(hashtext(recipient))` is
  taken after `LockTimeout.applyToCurrentTransaction()` — only requests aimed at one address are
  serialized.
- Retention is a property (`app.invites.event-retention-days`, 7 days by default, minimum 2). It
  **must** cover the longest ceiling window: a swept row is a row a ceiling will not count, so a
  retention shorter than the window silently shortens the ceiling itself. **Not all of these windows
  are configurable, and there are not two of them — there are as many as there are mail types:** the
  configurable cooldown and the window of each volume ceiling, fixed in code and distinct per policy
  (HD-202 made them differ in width). So the start compares retention not against the list of today's
  windows but against a *bound* — the widest width a policy is allowed to declare at all
  (`MailThrottlePolicy.MAX_CEILING_WINDOW`, 24 hours; a policy wider than it does not get built). The
  comparison is against the *greater* of the cooldown and that bound (`InviteProperties.isRetentionLongerThanWidestCeilingWindow`), not against the cooldown alone, and
  that is exactly why the minimum is 2 days: a single day is *exactly* the daily window, and
  "exactly" is not headroom (a replica whose sweep clock is a second ahead will delete a row that
  another replica's daily count still needs — and the only symptom is a send that should have been
  refused). Everything longer than that bound is forensics: after an alert this table is the only
  place that holds the answer to "who".

**A split by the kind of control.** Not everything is persisted. The **per-sender volume** budget
(hourly and daily) stays in memory like the other five limiters: it has a different job (spending a
quota, not harassment), and its degradation across N replicas and after a restart is acceptable for
exactly the reasons written in the javadoc of `PerPrincipalMinuteBudget`. What is persisted is what
is keyed on the **victim**.

## Consequences

+ The cooldown on the pair "sender → address" works across workspaces, survives a deploy and is
  reset neither by declining an invite, nor by removing a member, nor by deleting a workspace.
+ This is the first limiter in the product that does **not** get divided by the number of replicas:
  the state is in Postgres, not in the process. The asymmetry with the other limiters is deliberate
  and is written down in `docs/self-hosting.md`.
+ HD-202 comes down to two policies and two calls — instead of a second, half-overlapping mechanism.
  **That came true, but not literally, and both divergences are dictated by the caller here being
  anonymous**, not by the mail being different:
  - The refusal is **silent** (`MailThrottlePolicy.Refusal.SILENT`, the entry point
    `allowAnonymousSend`). `forgot-password` and `resend-verification` must answer identically for a
    registered and an unregistered address — that is precisely their guard against account
    enumeration. A `429` would not disclose the existence of an account (the ceiling is spent
    **before** `users` is consulted and is written in any case), but it would disclose to anyone, for
    free, that **somebody** requested a reset to that address a minute ago. So the ceiling is spent,
    the mail does not go out, the answer does not change.
  - The window of the volume ceiling became a **property of the policy**: a day for invites, an
    **hour** for anonymous mail. The reason is not volume: a ceiling on the **account recovery path**
    is at the same time a ban on it. Anyone can type the victim's address, so whoever chose the
    window also chose how long the named person will be unable to reset their own password — and
    silently. A day would have sold 24 hours of such a ban for five anonymous requests; an hour costs
    a weaker daily volume (the ceiling × 24) and that is the better half of the trade.
    A side gain: the copy of the window width in `InviteProperties` was deleted — both sides now read
    `MailThrottlePolicy.MAX_CEILING_WINDOW`, and a policy wider than it does not get built.
+ After an alert fires, the operator has something to answer the question "who" with, without putting
  identifiers into Prometheus.
− The daily ceiling is forced to hand back a `Retry-After` computed from someone else's send, so
  **on this path only** the number is coarsened. And what is coarsened is the **deadline, not the
  remainder**: rounding the remainder (`ceil((deadline − now) / 900) * 900`) ties the quantum to
  `now`, the number drops in steps as time passes, and the moment of the step is the hidden deadline
  itself (a dozen probes by bisection, or the minimum over a day's probes, recover it to the
  second). Rounding the deadline makes `now + Retry-After` the same on any probe. A visible
  consequence, because it looks like a regression: the number then decreases second by second and is
  **not** a multiple of 900 — being a multiple of 900 is precisely the fingerprint of the vulnerable
  variant. This raises the price of someone else's moment, but it does not confine the disclosure to
  one bit: whoever waits for the refusal to lift learns the deadline anyway, and that is irreducible
  for any sliding window with a right to retry. The one bit is held by the **wording** of the
  refusal, not by the header. The cooldown must not be coarsened and need not be — there the moment
  belongs to the caller themselves.
− The first limiter that goes to the database: +1 `SELECT` and +1 `INSERT` per invite. Acceptable for
  a rare write; on hot paths it must not be done this way, and this is not a precedent for them.
− A table with no workspace scope holding recipient addresses is the shape that produces this
  project's main class of bugs. It is held by the "aggregate counters only" invariant and by a test
  on the repository; if the invariant is ever broken, it will be a leak.
− One more table that HD-188 will have to fold into the baseline migration.
− The limiter lives in a service, not in an interceptor — that is, outside `ThrottleCoverageTest`.
  Compensated by a separate seal on another axis (`MailThrottleCoverageTest`: every outgoing mail
  type is either throttled or in `EXEMPT` with a written reason; after HD-202 `EXEMPT` is empty).
− A seal on the **mailers** axis does not see a path that sends a throttled mail type without
  spending a ceiling. There was exactly one such path and it was a mistake, not a decision:
  `POST /api/auth/register` (the written justification "an address yields at most one mail, the
  second time it is a `409`" is true about the SPELLING of the address, while the ceiling counts the
  MAILBOX). Closed in the HD-202 review: register has its own budget
  (`EmailType.REGISTRATION_VERIFICATION`), its own refusal shape
  (`Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES`) and its own door. The paths axis is now sealed by
  `AuthMailDoorsTest` — down to the enclosing METHOD, not the file.

## Alternatives

- **In memory, like the other five limiters** — rejected: a restart resets the cooldown, and across N
  replicas it gets divided by N. For a volume budget that is fine (and it stayed that way), for a
  control against harassment it is not: the value of a victim-keyed key is precisely that it does not
  depend on the attacker's circumstances.
- **Derive the cooldown from `workspace_invites`** — rejected: it is free, it uses the existing index
  and it is reset by three existing paths, one of which is pressed by the victim (`declineInvite`
  does a `DELETE`). A throttle the victim lifts by doing what the product asks of them is worse than
  no throttle at all, because it reads as a guard.
- **A global key on the recipient alone (without the sender) for the cooldown** — rejected as the
  primary key: the refusal would tell the admin of workspace A that this address was recently invited
  somewhere else — a cross-tenant disclosure, paid on every honest re-entry. The global key is used,
  but for a much higher daily ceiling (5), where the same disclosure is paid practically never, and
  the spec names that an accepted compromise.
- **A key on the IP** — rejected: it is bypassed with a phone, and after HD-199 a per-IP key is about
  something else anyway.
- **A separate table for invites only (`invite_send_events`)** — rejected: HD-202 makes it the home of
  three mail types, and renaming a table afterwards is a migration nobody will want to write. Hence
  `mail_send_events` and the `email_type` column from day one.
- **Redis / a shared store for all the limiters** — not considered as a solution for this ticket:
  that is a separate fork about horizontal scaling as a whole, whereas here what was needed was
  persistent state in the single place the product already has.
