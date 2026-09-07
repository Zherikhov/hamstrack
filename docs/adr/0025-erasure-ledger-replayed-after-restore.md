# ADR-0025: Backups are not edited; an erasure outlives a restore by replaying the ledger

Record date: 2026-09-02
Status: Proposed
Source: `docs/design/account-deletion-proposal.md` (HD-193) §8;
backups — `docs/ops-prod-hardening.md` §6.1, §6.5, §6.6 and ADR-0012 (HD-187)

## Context

Erasing an account in the live database does not make it erased everywhere. The backup mechanics
are these:

- a daily `pg_dump -Fc` + `pg_dumpall --globals-only` into a write-only bucket: the instance
  can write, but can neither read, nor delete, nor overwrite (ADR-0012); the period is set by
  the bucket's lifecycle rule, not by the script;
- daily EBS snapshots of the root volume;
- versioning on the attachments bucket with expiry of noncurrent versions.

Two facts follow from this, and both are unpleasant. An erased account **lives inside the backup
objects** for a limited time, and **a restore from a backup taken before the erasure resurrects the
person in full** — the address, the password hash, the memberships. And a `pg_dump` archive in the
custom format cannot be edited in any sense one could lean on, while making the backup objects
mutable for that purpose means destroying the property that makes them backups at all.

The trap is operational, not theoretical: a restore drill has already been run
(2026-08-26, about 4 minutes), and the restore procedure is in the repository. That is, a
restore is a real action that somebody will one day perform under pressure.

## Decision

**Backup objects are never edited. The durability of an erasure is provided by replaying
it.**

**The erasure ledger** is an append-only record that lives **outside the database being restored**,
one line per erasure:

`erased_at` (UTC) · `user_id` · `request_id` · deleted workspaces (ids) · kept
workspaces (ids) · **transferred ownership** (workspace id → appointee id) · **appointed
project administrators** (project id → appointee id) · the number of deleted attachment keys
(the list itself is in an adjacent file) · the operator.

The last two items are not bookkeeping but a condition of replayability: a dump taken before the
erasure contains the subject as the only Owner, and the successor chosen by the operator does not
exist in it anywhere. Without these fields the replay either leaves the workspace with no owner or
invents a different one.

**The ledger holds no address, no display name and no IP** — only identifiers. A ledger
carrying the address would be a permanent store of exactly what the procedure destroys — that is,
it would turn a compliance artefact into a leak.

**The storage location is `s3://<backup-bucket>/manual/erasures/`.** That prefix is already exempt
from the lifecycle rule of the daily backups (§6.1), the instance can neither read there, nor
overwrite, nor delete, while the owner credentials can read. Hence a consequence that is easy to
miss during an incident: **the replay is run from a workstation under the owner credentials**,
because the instance role has no `s3:GetObject` — from the box a listing of the records goes
through, but the very first body download answers 403, at exactly the step the gate exists for.

**The record has an end.** No lifecycle rule touches `manual/`, so the record is eternal, while its
meaning is not: it can be applied again only to a dump taken **before** the erasure, and such
dumps do not outlive the `daily/` retention window. As soon as `erased_at` is older than that
window there is nothing to reproduce and no reason to; the record is then **reduced to `erased_at` /
`user_id` / `request_id` / `operator`**, and the key list is deleted. What remains is accountability
(an erasure happened, at such a time, under such a request, by such an operator); what goes is the
management graph (who got which workspace and project), which would otherwise accumulate
indefinitely and with no reader. The reduction is done by the owner: the keys are write-once, and the
box has no `DeleteObject` at all. The mechanics are in §6.7.

**The replay is a gate, not a footnote.** `docs/ops-prod-hardening.md` §6.7:
after a dump has been restored into production and **before the application starts serving
traffic**, every ledger record with `erased_at` later than the restored dump's mark is applied
again. A row already erased by the self-referential marker of ADR-0023 (`email = 'deleted+' ||
id::text || '@deleted.invalid'`, not a domain match) is skipped: the procedure is idempotent.

**The replay is a run of the runbook in the runbook's order, not a selection from it.**
The tempting shortcut — "phases 3–6 and 8, then the check" — puts the anonymisation **ahead of its
own gate**, on a database that is about to start serving production: after phase 8 there is neither
an address nor a name left to find anything by, and unlike a live erasure nobody is watching.
The order: the appointments from the record itself → **the whole of phase 1, read-only, together
with the counters** (1(g3)/1(g3b) — the only guard against rewriting a namesake's headers, and a
restore is exactly the moment when nobody is counting) → phases 3–6 → **phase 8a (the checks) → 8b
(the anonymisation)** → phase 9. The address and the display name are read **from the restored row
itself** immediately before it is anonymised — which is exactly why the ledger does not need to
carry them. The subject here is taken from the record's `user_id` — the only place where `:uid` is
not derived from a confirmed address, and it requires no confirmation: the ledger is our own, not a
letter from a stranger.

The §6.5 drill runs against a throwaway container that serves nobody, and requires no replay;
this is stated in §6.7 — where the gate is — because the dangerous reading is precisely the
opposite one: carrying the absence of a replay over from the drill into a restore of
production.

## Consequences

+ The backups' properties are preserved in full: write-only, immutability, the period set by
  a lifecycle rule. None of this is sacrificed to compliance.
+ The resurrection of an erased account stops being silent: it has a mandatory step that
  undoes it, and an artefact that proves the step happened.
+ The wording in the policy becomes verifiable: it describes a **procedure**, not a period —
  and therefore spends none of the numbers behind which HD-192 is blocked.
+ The ledger in `manual/` is protected by the same three bucket properties as the backups, and
  cannot be corrupted by a compromised instance.
− **Between the restore and the replay the account exists.** The gate must come
  before traffic, and that is runbook discipline, not a mechanism.
− An erasure stops being a single moment: it becomes "applied to the live database and required to
  be applied again to any restored copy". This has to be both understood and disclosed.
− The ledger lives outside the application, so nobody validates it. A missed line
  will be discovered only by someone noticing a resurrected account.
− Attachment objects deleted together with a solo workspace are not brought back by a database
  restore, so that part of the replay is a no-op, and the only record that it
  was done is the key list next to the ledger.

## Alternatives

- **Edit or re-issue the dumps** — rejected: on a `pg_dump` archive in the
  custom format this is not feasible, and mutable objects destroy the point of a backup and three
  properties from §6.1.
- **Shorten the backup retention so that an erasure "completes" sooner** — rejected:
  it trades a real restore guarantee for the appearance of compliance, and on top of that it would
  require naming a period that this ticket has no right to name (those are HD-192's inputs).
- **Keep the ledger in the application's own database** — rejected, and this is the decision's main
  argument: restoring a dump taken before the erasure will also restore the ledger as it was before
  the erasure, i.e. the record disappears at exactly the moment it is needed.
- **Turn on S3 Object Lock for the backup bucket** — already rejected earlier and for a
  compatible reason (§6.2): an immutable bucket that outlives an erasure request trades a
  security problem for a legal one.
- **Do nothing and rely on the backups expiring** — rejected: a restore in production
  then silently resurrects erased people, and the only way to learn of it is from those people
  themselves.
