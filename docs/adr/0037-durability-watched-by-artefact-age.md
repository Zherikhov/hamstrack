# ADR-0037: A durability layer is checked by artefact age, not by the state of the mechanism

Record date: 2026-09-06
Status: Accepted (implemented in HD-262, shipped in 0.18.0)
Source: `docs/design/root-volume-snapshot-age-proposal.md` (HD-262); measurements over the account
and over policy `policy-0ee1644759462e1f7` — 2026-09-06; layer 3 and its purpose —
`docs/ops-prod-hardening.md` §6.1

## Context

`docs/ops-prod-hardening.md` §6.1 has four backup layers. Layer 3 — the daily
EBS snapshot of the root volume through AWS Data Lifecycle Manager — is the only one that
covers the box itself: `/opt/hamstrack/.env` (**the only copy of `JWT_SECRET`**),
the hand-edited `Caddyfile`, the `caddy_data` certificates, the observability volumes. Neither the database
(layers 1–2) nor the attachments (layer 4) contain them.

HD-262 recorded two consecutive failures of this layer with one shared root cause, and both
slipped past every check that was running:

- **2026-08-29 → 2026-09-03.** The root volume was replaced with an encrypted one; the tag
  `Backup=hamstrack`, by which DLM selects volumes, was not carried over to the new volume. For five nights
  the schedule ran **successfully** and snapshotted a volume detached since 08-29. The live volume
  `vol-0867f8d73630ca5a1` had no daily snapshots at all.
- **2026-09-04 → open (measured 2026-09-06).** After the tag was added the very first run
  failed: `State: ERROR`, `StatusMessage: Duplicate tag key 'Name' specified.`,
  `DateModified 2026-09-04T04:42:30Z`. The schedule carries both `CopyTags: true` and
  `TagsToAdd: [{Key: Name, …}]`, and the new volume has a `Name` tag. The old volume had no `Name`
  tag — that is, **adding `Backup=hamstrack` on 09-03 is what armed this mine**.

The fork: what to catch such a thing with. Two fundamentally different checks are available — "did the
mechanism run" and "did the artefact appear".

| | "did the schedule run?" | "is there a fresh snapshot of the live volume?" |
|---|---|---|
| Failure 1 (wrong volume) | **yes, green** | **no**, 5 days |
| Failure 2 (`ERROR`) | visible only in the `State` field, which nobody reads | **no**, 2+ days |

The mechanism check misses the first failure entirely and catches the second only on condition that
someone reads a field that nobody reads. The result check catches both.

## Decision

**Every durability layer is observed by the age of the artefact it is supposed to
produce, not by the state of the mechanism that produces it.**

Mandatory properties of such a check:

- **The subject is resolved afresh on every run from the live system.** No stored
  identifier: the volume is determined from the instance itself (IMDS → `DescribeVolumes` by
  `attachment.instance-id`), not taken from config. A stored id is exactly the same
  class of fact as the tag `Backup=hamstrack`: true on the day it was written and silently false from
  the next volume replacement on. A check that has inherited the staleness it is obliged to catch
  is worse than no check at all — it is a green light over the failure itself.
- **An absolute timestamp is published, not a computed age.** A frozen `.prom` with
  `age 3600` looks eternally fresh; a frozen timestamp ages by itself, and a stopped
  collector *converges on an alert* instead of hiding behind one. The project has already paid for the
  inverse shape: a metric that only the deploy published looked healthy, and nothing caught it,
  because no rule read its timestamp (`hamstrack_config_drift` before
  `ConfigDriftCheckStale` appeared).
- **"There are no artefacts" is a successful answer of `0`, not an absent series.** An absent series
  under `noDataState: OK` is silence; a zero is a firing alert. That is exactly what makes the 08-29 case
  (a fresh volume with not a single snapshot) visible the same evening.
- **An artefact counts regardless of who created it.** A snapshot taken by hand
  closes the question just as one taken by DLM: the rule asks whether a restorable copy exists,
  not who ordered it. As a side effect this is also what makes the refusal actionable — an operator fixing DLM can
  clear the alert with an action available to them right now.

The state of the mechanism (`aws dlm get-lifecycle-policy`) remains **text in the alert annotation**
as the first step of triage — it costs nothing and requires no new IAM grant — but it does not
turn into a second automatic signal.

## Consequences

+ The check is blind to the cause and therefore catches "it ran over the wrong thing", and "it failed into a field
  that nobody reads", and a failure mode nobody has invented yet.
+ The same question carries over to any layer: the remote state of layers 1–2 (the backup metrics
  measure the local run and never the object in the bucket, §6.2) and the versioning of the attachments
  bucket (layer 4) are covered by nothing today — and this is now phrased as one
  missing member of a category rather than as four independent tasks.
+ The rule outlives the repair of the mechanism: after DLM is fixed nothing in the check changes.
− A result check is **later** than a mechanism check: until the artefact is overdue, a broken
  schedule is invisible. The threshold must therefore be a little larger than the worst normal interval —
  for layer 3 that is 30 h (a 24 h interval + the documented DLM start window of up to an hour + the snapshot
  completion time + slack), and this threshold is **reasoned, not tuned**: there is no snapshot history
  on the live volume yet, re-check in a week.
− A read grant is needed on the system that holds the artefact (for layer 3 —
  `ec2:DescribeVolumes` + `ec2:DescribeSnapshots`; see ADR-0038).
− An unclosed hole remains, and it is named: under `noDataState: OK` deleting the `.prom` or losing
  node-exporter's bind-mount extinguishes the rule instead of lighting it. The "check
  stale" check suffers from the same. Only a rule on `absent()` closes it, and it must be solved
  for all metric families at once, not for one.

## Alternatives

- **Read the DLM policy's `State`** (`dlm:GetLifecyclePolicies`) — rejected: that is precisely the
  "did the mechanism run" question. It would have caught failure 2 and would not have caught failure 1, while
  requiring a new grant. The result check catches both and requires nothing beyond what is already needed.
- **Alert on the job's return code / on a success flag** — rejected for the same reason,
  plus a job that did not start reports nothing: that is exactly the argument by
  which `BackupStale` exists alongside `BackupRunFailed`.
- **Publish `..._age_seconds`** — rejected: an age in a frozen file does not age.
- **Store the volume id in the check's config** — rejected: that is the root cause of failure 1, repeated
  in the remedy for it.
