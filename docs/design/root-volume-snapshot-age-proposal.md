# Root-volume snapshot age — an outcome check for backup layer 3 (HD-262)

> **Status: proposal.** Nothing below has been built. The DLM repair itself is an owner
> action already in flight and is **out of scope** — this document specifies the *detection*,
> not the fix.
>
> **Spec date: 2026-09-06.** Every figure attributed to production in §1 was read off the
> account on that date or on the dates named beside it. Where a number is reasoned rather
> than measured, it says so — there is no snapshot history on the live volume to fit
> anything to yet.

Related: [`docs/design/production-backups-proposal.md`](production-backups-proposal.md)
(HD-187, layers 1/2/4 and the metric shape this reuses),
[`docs/design/config-delivery-proposal.md`](config-delivery-proposal.md) (HD-199, the drift
check whose collector shape this follows),
[`docs/ops-prod-hardening.md`](../ops-prod-hardening.md) §6 (the runbook this edits).

---

## 1. Problem & goal

Backup layer 3 — the daily EBS snapshot of the production root volume — has been producing
**nothing usable since 2026-08-29**, in two consecutive outages with one shared root cause,
and every mechanism that watches this deployment reported success throughout both.

**Outage 1, 2026-08-29 → 2026-09-03 (five nights).** The root volume was replaced with an
encrypted one. DLM policy `policy-0ee1644759462e1f7` selects volumes by tag
`Backup=hamstrack`; the tag was not carried onto the new volume, so it stayed on the old,
now-detached one. The nightly schedule ran, succeeded, and snapshotted a volume that had
been detached since 08-29. The live root volume `vol-0867f8d73630ca5a1` had no daily
snapshots at all.

**Outage 2, 2026-09-04 → open (measured 2026-09-06).** The tag was added to the live volume
on 09-03 and one manual snapshot was taken. The first DLM run against the newly tagged
volume failed and the policy has been in `ERROR` since:

```
State:          ERROR
StatusMessage:  Duplicate tag key 'Name' specified.
DateModified:   2026-09-04T04:42:30Z      <- the 04:30 run, the morning after the repair
```

The schedule carries `CopyTags: true` **and** `TagsToAdd: [{Key: Name, Value:
hamstrack-auto}]`; the volume created on 08-29 carries `Name=hamstrack-root-encrypted`. The
old volume had no `Name` tag, so the collision could not occur while the policy was pointed
at the wrong volume — **adding `Backup=hamstrack` on 09-03 is what armed it.** As of
2026-09-06 the newest snapshot of `vol-0867f8d73630ca5a1` is still the manual one from
2026-09-03.

**What was unprotected for those days**, per `docs/ops-prod-hardening.md` §6.1: layer 3 is
the only layer covering `/opt/hamstrack/.env` — **the only copy of `JWT_SECRET` anywhere** —
the hand-edited `Caddyfile` with its Cloudflare `trusted_proxies` block, the `caddy_data`
certificates and the observability volumes. Layers 1/2 cover the database, layer 4 covers
attachments. Neither covers the box.

**The generalisable defect is not the tag and not the duplicate key.** It is that this layer
had *no outcome check at all*. Both failures are invisible to a mechanism check and both are
obvious to an outcome check:

| | did the schedule run? | is there a recent snapshot of the live volume? |
|---|---|---|
| Outage 1 (wrong volume) | **yes, green** — DLM ran and succeeded nightly | **no** — 5 days with none |
| Outage 2 (`ERROR`) | reported only in the policy's `State`, which nothing reads | **no** — 2+ days with none |

A "did it run" check would have missed outage 1 entirely and caught outage 2 only if
somebody had been reading a field nobody reads. **One question catches both: how old is the
newest snapshot of the volume this instance is actually running on right now.**

**Goal.** A host collector publishes that age as a Prometheus timestamp, and a provisioned
Grafana rule fires when it exceeds a threshold — so that the *next* time layer 3 stops
producing artefacts, for any reason including one nobody has thought of, a person is told
within a day rather than after a week of someone happening to look.

**Success looks like:** on 2026-08-30 04:35 UTC the alert would have been firing, and it
would have gone on firing until a real snapshot of the real volume existed.

---

## 2. Scope

### In scope

1. A new host `systemd` timer + oneshot unit under `ops/snapshot/`, following `ops/backup/`
   and `ops/drift/` exactly: script + `.service` + `.timer` + `snapshot.env.example` +
   `README.md`, publishing node-exporter textfile metrics to
   `/var/lib/node_exporter/textfile_collector`.
2. Three provisioned Grafana alert rules in
   `observability/grafana/provisioning/alerting/rules.yml`.
3. Documentation: the metric + rule tables in `docs/observability.md`; the install step, the
   verification commands and a **past-tense record of both gaps** in
   `docs/ops-prod-hardening.md` §6; a "carry the tags" step in the volume-replacement
   procedure; a short self-hoster-facing note.
4. Two ADRs (§12).

### Out of scope — explicit non-goals

- **The DLM repair itself.** `policy-0ee1644759462e1f7`'s `CopyTags` / `TagsToAdd`
  collision is the owner's, already in flight. This spec must not describe the fix as
  though it were a step here, and the collector must work identically before and after it —
  the whole point is a check that does not know or care why a snapshot is missing.
- **Reading the DLM policy `State`.** Rejected on the evidence in §1: it is the "did it run"
  question, it needs a new IAM grant (`dlm:GetLifecyclePolicies`), and it would have caught
  one of the two outages. It survives as a *first move* named in the alert summary, which
  costs nothing.
- **Taking snapshots.** This collector holds two `Describe*` actions and will never hold a
  write. A monitor that can create a snapshot can be made to delete one.
- **Watching non-root volumes.** There is one attached volume today. The metric is shaped
  for more (§4) and this slice watches the root device only.
- **An outcome check for layer 4** (attachments-bucket versioning) or for the *remote* state
  of layers 1/2 — `docs/ops-prod-hardening.md` §6.2 already notes that the backup metrics
  measure the local run and never the remote object. Both are real gaps of the same family;
  see §11 open question 2.
- **Any application change.** No entity, no migration, no endpoint, no Spring property, no
  `.env` variable read by the app.

---

## 3. Actors & permissions

### Tenancy: it does not arise, stated rather than left implied

Nothing in this feature is workspace-scoped. The collector runs as a host `systemd` unit
outside the JAR, opens no database connection, reads no `workspace_id`, serves no request
and has no caller. There is no membership to check, no resource to 404, and no
403-versus-404 question. The project's top bug class is structurally absent here — not
handled, absent.

### Who can do what

| Actor | Action | Requires |
|---|---|---|
| Owner (AWS account credentials) | attach the IAM policy, install the unit, arm the timer | account credentials + SSM |
| The instance (role `hamstrack-ec2`) | `ec2:DescribeVolumes`, `ec2:DescribeSnapshots` | the inline policy below |
| Anyone reading Grafana over the SSM port-forward | see the metric and the alerts | §4 of the hardening runbook |
| The application | — | **nothing.** The app never calls the EC2 API and gains no permission from this. |

### The grant, which already exists

An inline policy `ec2-snapshot-read` was attached to role `hamstrack-ec2` on **2026-09-06**:
`ec2:DescribeVolumes` + `ec2:DescribeSnapshots`, `Resource: "*"` (EC2 `Describe*` supports
no resource ARNs), condition `aws:RequestedRegion = eu-north-1`. It was **verified working
from the instance** — not from the console — resolving the instance's own attached root
volume and that volume's snapshot history.

Two consequences the implementation must respect:

- **The region condition means the region is never implicit.** A call that inherits a region
  from anywhere other than this instance's own placement is denied. The script resolves the
  region from IMDS (`/latest/meta-data/placement/region`) and passes `--region` explicitly on
  every call. An `AWS_DEFAULT_REGION` leaking in from somewhere is not a supported input.
- **`ec2:DescribeInstances` is deliberately absent**, and the design below is shaped around
  that: the instance id comes from IMDS and the volume from
  `DescribeVolumes --filters Name=attachment.instance-id`. Do not add `DescribeInstances`
  to make a more convenient query work.

Both grants are read-only and the account is single-owner, so the disclosure question is
"what can an attacker who already owns the box learn" — the answer is volume and snapshot
ids in one region, which they could enumerate from the block devices anyway.

---

## 4. Behaviour & rules

### 4.1 The metric set

Three series, published atomically to
`/var/lib/node_exporter/textfile_collector/hamstrack_volume_snapshot.prom`.

```
# HELP hamstrack_volume_snapshot_newest_timestamp_seconds Unix time (StartTime) of the newest completed snapshot of a volume attached to this instance. 0 means no snapshot exists.
# TYPE hamstrack_volume_snapshot_newest_timestamp_seconds gauge
hamstrack_volume_snapshot_newest_timestamp_seconds{volume="vol-0867f8d73630ca5a1"} 1757000000

# HELP hamstrack_volume_snapshot_check_status Whether this run completed the stage (1) or not (0).
# TYPE hamstrack_volume_snapshot_check_status gauge
hamstrack_volume_snapshot_check_status{stage="resolve"} 1
hamstrack_volume_snapshot_check_status{stage="describe"} 1

# HELP hamstrack_volume_snapshot_check_timestamp_seconds Unix time this check last ran. Distinguishes a fresh answer from a frozen one.
# TYPE hamstrack_volume_snapshot_check_timestamp_seconds gauge
hamstrack_volume_snapshot_check_timestamp_seconds 1757160000
```

**A TIMESTAMP, NEVER A PRE-COMPUTED AGE. This is the load-bearing choice of the whole
design.** A `..._age_seconds` gauge frozen in a `.prom` that nobody rewrites reads as
permanently fresh: the collector dies, the file keeps saying `age 3600`, and the rule never
fires. A timestamp in the same frozen file gets older on its own — `time() - ts` grows
between runs — so a stopped collector *converges on the alert* instead of hiding behind it.
This project has already been bitten by the inverse shape: a metric published only by the
deploy, looking healthy, which nothing detected because no rule read its timestamp
(`hamstrack_config_drift` before `ConfigDriftCheckStale` existed). It also decouples
detection latency from collector cadence entirely: an hourly check still fires the alert at
the correct wall-clock minute.

**`0` means "resolved successfully, and there are none".** It is not a missing value. This is
what makes the 08-29 case fire: a freshly swapped volume with no snapshot history publishes
`0`, and `time() - 0` is far past any threshold, so the alert is up the same evening.
Omitting the series instead would be `noData`, which is `OK`, which is silence.

**Why `volume` is a label and `snapshot` is not.** The `volume` label is what lets the alert
annotation name the subject, which is the operator's first question. Its cardinality is
bounded by a real-world action — one new series per root-volume replacement, roughly one a
year — and the series *break* is itself the evidence of a swap, which is the event this
ticket exists for. A snapshot id label would produce a new series every night and is
forbidden. Same rule the drift check states for its `sha`/`tag` labels: this repository
otherwise forbids unbounded labels, so an exception is written down where it is taken.

**Why `check_status` carries no `volume` label.** At `stage="resolve"` there is no volume
yet, and a status series that sometimes carries a label and sometimes does not is two
metrics wearing one name.

**The two stages, because they have opposite first moves:**

| stage | covers | a failure here means |
|---|---|---|
| `resolve` | IMDSv2 token → instance id → region → root device name → `DescribeVolumes` → one volume id | IMDS is unreachable or refusing v2, the metadata hop limit was lowered, the instance identity is unavailable, or the attached volumes are ambiguous |
| `describe` | `DescribeSnapshots` for that volume → newest completed `StartTime` | the `ec2-snapshot-read` policy was detached or its region condition no longer matches, the API is throttling, or a `StartTime` could not be parsed |

`rules.yml` already carries the convention for this and it must be honoured: the summary
branches on `$labels.stage` with a total `if/else`, and **a stage added to the `.prom` gets
its arm in the same commit** or an unnamed stage is described as the wrong one.

### 4.2 Resolving the volume — the rule that cannot be relaxed

> **The volume is resolved from the instance on every run. There is no stored id, no
> configured id and no override.**

This is the ticket's own requirement and it generalises: a pinned volume id is the same
class of stored fact as the `Backup=hamstrack` tag that caused outage 1 — correct on the day
it is written and silently wrong from the moment a volume is replaced. A check that inherits
the staleness it exists to catch is worse than no check, because it is a green light over
the exact failure. **Therefore `snapshot.env` deliberately has no `SNAPSHOT_VOLUME_ID`.**

Resolution order, all of it per run:

1. **IMDSv2 token.** `PUT http://169.254.169.254/latest/api/token` with
   `X-aws-ec2-metadata-token-ttl-seconds: 60`. The instance runs `--http-tokens required`
   (hardening runbook §1), so a v1 `GET` is refused — the token flow is not optional. Short
   connect and total timeouts (2 s / 5 s): the link-local address does not answer on a box
   that is not EC2, and a monitor must not hang on that.
2. **Instance id** `/latest/meta-data/instance-id`, **region** `/latest/meta-data/placement/region`,
   **root device** `/latest/meta-data/block-device-mapping/root`.
3. `aws ec2 describe-volumes --region "$REGION" --filters
   Name=attachment.instance-id,Values="$IID"` → for each volume, the attachment **whose
   `InstanceId` equals `$IID`** (never `Attachments[0]` — a Multi-Attach volume has several,
   and taking the first is a coin toss nobody would notice), and select the one whose
   `Device` matches the root device from step 2, compared on the basename so `/dev/xvda`,
   `xvda` and `/dev/sda1` versus `sda1` agree.
4. **Ambiguity is a failure, not a guess.** If no attachment matches the root device, or more
   than one does, the run publishes `stage="resolve" 0` and stops. This follows
   `hamstrack-backup.sh`'s refusal to choose between two postgres containers: the ambiguity
   itself is the failure. The one permitted fallback is *exactly one* attached volume with
   the root device name unavailable from IMDS — then that volume is used and the journal says
   it was chosen by elimination.

Note for whoever edits the runbook: §6.2 step 4 resolves the volume as
`BlockDeviceMappings[0].Ebs.VolumeId`. Index `0` is not guaranteed to be the root device.
That is a latent version of the same bug and should be corrected to a `RootDeviceName` match
while §6 is open (§8).

### 4.3 Finding the newest snapshot

```
aws ec2 describe-snapshots --region "$REGION" --owner-ids self \
  --filters Name=volume-id,Values="$VOL" Name=status,Values=completed \
  --query 'Snapshots[].StartTime' --output text
```

then split on tabs, `sort`, take the last (ISO-8601 UTC sorts lexicographically), and convert
with `date -u -d`.

- **Filtered by volume id, never by tag.** A tag filter is what failed on 08-29. The question
  is "does a restorable image of *this volume* exist", and a tag is somebody's intention
  about that, not the fact.
- **`status=completed` only.** A `pending` snapshot is not yet restorable and may still end
  in `error`; counting it would report health for an artefact that never arrives. The cost is
  that a snapshot in flight does not clear the alert until it completes, which the threshold
  in §4.4 absorbs.
- **Any snapshot counts, whoever took it.** A manual snapshot satisfies the rule exactly as a
  DLM one does, because the alert asks whether a recent restorable image exists and not who
  asked for it. This is also the property that makes the refusal actionable: an operator
  fixing DLM can clear the alert *now* by taking a snapshot, which is an action its reader can
  actually perform.
- Zero rows → `0`, and `stage="describe"` is still `1`. "There are none" is an answer.
- Pagination is the CLI's default behaviour and needs nothing; `AWS_PAGER=""` is set, because
  AWS CLI v2 pipes through a pager and a monitor that can block on `less` is a monitor that
  can wedge a systemd unit.

### 4.4 The alert rules

Three rules, appended to
`observability/grafana/provisioning/alerting/rules.yml`. `noDataState: OK` and
`execErrState: OK` throughout, matching every rule in that file — reasoning and its price in
§9.

| uid (len) | title | expression | for | severity |
|---|---|---|---|---|
| `hamstrack-volume-snapshot-stale` (31) | `VolumeSnapshotStale` | `time() - hamstrack_volume_snapshot_newest_timestamp_seconds > 108000` | 15m | critical |
| `hamstrack-snapshot-check-failing` (32) | `VolumeSnapshotCheckFailing` | `hamstrack_volume_snapshot_check_status < 1` | 5m | warning |
| `hamstrack-snapshot-check-stale` (30) | `VolumeSnapshotCheckStale` | `time() - hamstrack_volume_snapshot_check_timestamp_seconds > 10800` | 1h | warning |

All three uids are well under the **40-character** ceiling Grafana enforces when it *stores*
a provisioned rule. This is not style: an over-long uid makes the provisioner exit 1 and
takes down **all** alerting on the instance, which happened here on 2026-09-05 (HD-283) and
is now sealed by `everyUidIsShortEnoughForGrafanaToStore` in
`GrafanaProvisioningContractTest`. The uid deliberately does not mirror the title on two of
the three — `hamstrack-config-check-stale`/`ConfigDriftCheckStale` already sets that
precedent — because the mirrored spellings run to 39 characters and a uid one character from
a crash-loop ceiling is not a margin.

Queries are **unaggregated**, like `ConfigDrift` and the backup pair: the annotation renders
per instance and branches on `$labels.volume` / `$labels.stage`, and any aggregation that
drops the label renders the branch against an instance that has none.

**Why 30 hours (108 000 s), and what it is made of.** The DLM schedule is `Interval: 24
HOURS, Times: ["04:30"]`, and AWS documents a policy starting *within about an hour* of its
scheduled time. So the worst-case interval between two consecutive successful `StartTime`s is
**25 h**, plus the completion of a `completed`-filtered snapshot, which after a volume
replacement is a full copy rather than an incremental delta. 30 h is that worst case plus a
deliberate margin, and it is chosen with the failure mode of the *rule* in mind: this is the
only `critical` of the three, a critical that cries wolf on a Sunday morning gets muted, and
**a muted detector is worse than none** — the drift check has already paid that lesson once.
It is also strictly below 48 h, so a single missed night is always caught inside the same day
and never blurs into two.

**It is reasoned, not fitted, and that is stated rather than hidden.** `HostMemoryLow`'s
200 MiB sits below a measured 7-day minimum; nothing comparable exists here, because the live
volume has exactly one snapshot in its entire history. This is `HostSwapInUse`'s situation —
a threshold with no backtest — and it is handled the same way: **re-check it once a week of
real DLM history exists on this volume.** The falsifier is explicit: if a genuine
inter-snapshot gap ever exceeds ~26 h in the recorded history, the number moves, not the rule.

**Why `VolumeSnapshotCheckStale` at 3 h** — the timer is hourly and one missed run on a busy
box is not an incident. Same number and same reasoning as `ConfigDriftCheckStale`, so there
is one convention here rather than two. Its value is diagnostic and this should be said
plainly: `VolumeSnapshotStale` fires on its own when the collector stops, because the
timestamp ages — but it fires with the *wrong diagnosis*, sending the reader to DLM when the
fault is a dead timer, and DLM is exactly what they would then wrongly "fix".

### 4.5 Cadence, and where the check is NOT run

`OnCalendar=hourly`, `RandomizedDelaySec=300`, `Persistent=true`, `AccuracySec=1m` —
copied from `hamstrack-config-drift.timer`, off the top of the hour and away from the scrape.
Two EC2 `Describe` calls an hour are free, unbillable and orders below any throttle. The
answer only changes once a day, so hourly buys two things: the volume identity is re-resolved
often (a swap is noticed within an hour, not a day), and the stale-check threshold can reuse
the drift convention.

> **`apply-config.sh` deliberately does NOT run this check at the end of a deploy**, unlike
> the drift check. Three reasons, and the second is the one that matters: it would make a
> deploy depend on the EC2 control plane and on IMDS being reachable, turning a
> read-only monitor into a deploy failure mode; it would publish a fresh
> `check_timestamp` at deploy time, recreating exactly the "fresh-looking, only ever as fresh
> as the last deploy" gap that `ConfigDriftCheckStale` had to be invented to cover; and the
> value changes once a day, not once a merge.
>
> A consequence worth recording because it decides §10's disclosure question: **nothing this
> collector prints travels off the box.** Its output goes to the journal only, never through
> SSM into a public Actions log.

---

## 5. Edge cases & failure modes

Failure modes of the collector itself, which is the half a monitor usually gets wrong.

| # | Situation | Behaviour |
|---|---|---|
| 1 | IMDS unreachable / v2 token refused / hop limit lowered to 1 | `stage="resolve" 0`, `CheckFailing` in 5m. **Not** a stand-down — see §9. Worth having for a second reason: the app resolves its S3 credentials from IMDS too. |
| 2 | Role detached, `ec2-snapshot-read` removed, or region condition no longer matches | `stage="describe" 0` (or `resolve`, depending which call is refused) |
| 3 | `aws` not installed / not on PATH | fatal in pre-flight, **after** the EXIT trap is installed, so both stages publish `0`. `need_tool` precedent from `hamstrack-backup.sh` |
| 4 | EC2 API throttling | `stage="describe" 0`; two calls an hour will not cause it, and a transient one clears at the next run — `for: 5m` plus an hourly cadence means a single blip does not page |
| 5 | **Zero snapshots for the resolved volume** | `newest_timestamp 0`, **both stages `1`**. This is a successful answer of "none", and it is the 08-29 case. `VolumeSnapshotStale` fires |
| 6 | Several attached volumes, none matching the root device, or several matching | `stage="resolve" 0`. Refuses to guess (§4.2) |
| 7 | `StartTime` unparseable, or a value in the future | rejected → treated as no answer, `stage="describe" 0`. A future timestamp makes `time() - ts` negative and switches the rule off permanently while looking healthy — the exact bound `hamstrack-backup.sh`'s `read_state` already carries, for the same reason |
| 8 | Run killed by SIGTERM (`TimeoutStartSec`) | `trap 'exit 143' TERM` routes it through the EXIT trap, which publishes pessimistic `0`s |
| 9 | Run SIGKILLed (OOM, `kill -9`) | no trap runs; the `.prom` freezes. **Deliberately no `ExecStopPost` handler**, unlike the backup job — see below |
| 10 | `.prom` written but unreadable by node-exporter | the file is `chmod 0644` explicitly, wider than the umask. node-exporter runs as `nobody`; a metric it cannot read is an alert that never fires, and `noDataState: OK` makes that silent. Same exemption, same reason, as the backup and drift collectors |
| 11 | A label value containing a quote or backslash | `sanitize_label` (drift's, `[^A-Za-z0-9._-]` stripped). A single malformed line makes node-exporter drop **every** series in the file at once. Volume ids are `vol-[0-9a-f]+` today; the guard is about the category |
| 12 | Scrape lands mid-write | temp file in the same directory then `mv -f` — atomic. Leftover `*.prom.<pid>` files from a killed run are swept at the start of the next one |
| 13 | Two runs overlap (hand run vs timer) | `flock -n`; the loser exits 0 **without touching the metrics**, so it cannot forge freshness, and a permanently stuck lock still surfaces as staleness. `need_tool flock` runs *before* the lock, because `flock`'s exit 1 and the shell's 127 are indistinguishable at the call site |
| 14 | Root volume replaced | the old series stops, a new one appears at `0`, and the alert fires the same evening. **This is the feature, not an edge case** |
| 15 | Not on EC2 at all (bare metal, a VPS with no EBS) | the unit is never installed; no series exists; `noDataState: OK` keeps all three rules dormant. §9 |

**On #9, and why this unit is cheaper than the backup one.** `hamstrack-backup.sh` needs an
`ExecStopPost` handler because a frozen `.prom` there asserts `last_status 1` — a stale claim
of success that never decays. Here the primary gauge is a timestamp that ages by itself, so a
SIGKILLed run converges on `VolumeSnapshotStale` regardless, and `VolumeSnapshotCheckStale`
reaches the operator first with the correct diagnosis. The cost is named rather than glossed:
between the kill and the 3 h stale threshold, `check_status` still reads `1` and
`CheckFailing` does not fire. That is an accepted three-hour window, not an oversight.

**Idempotency and concurrency** are otherwise trivial here: the collector holds no state
between runs (§4.2 forbids it), takes no lock on anything but itself, and every run recomputes
its answer from scratch. There is nothing to make idempotent.

---

## 6. Data model impact

**None.** No table, no column, no Flyway migration, no entity. The Flyway rules
(`VARCHAR` over ENUM, UUID v7, `@CreatedDate`) have nothing to apply to. This is stated
explicitly so a reviewer does not go looking: `migration-reviewer` is `n/a` for this ticket.

The only persistent artefact is a `.prom` file that is fully rewritten on every run and
carries no history.

---

## 7. API surface

**None.** No endpoint, no DTO, no status code. `openapi.yaml`, `docs/api-cloud.md` and
`docs/api-dc.md` are unchanged — `api-docs-sync` is `n/a`.

The metric names in §4.1 are, however, a public contract in the same sense: they are read by
provisioned alert rules and documented in `docs/observability.md`, so renaming one is a
breaking change for any box that has installed the unit. Treat the names as fixed once
shipped.

---

## 8. Frontend impact

**None.** No page, no component, no store, no `DESIGN.md` question. The only human surface is
Grafana, which is provisioned from YAML and reached over an SSM port-forward
(`docs/ops-prod-hardening.md` §4).

---

## 9. DC / Cloud implications — the fork, decided

This is the decision the ticket asked to have settled rather than assumed, so it is settled
here with its reasoning and its cost.

### 9.1 Does the check stand down silently when IMDS is unreachable? **No.**

**Decision: the check is opted into by the act of installing the unit, and once installed it
treats an unreachable IMDS as a failure, not as a reason to be quiet.**

The tempting alternative is auto-detection: probe IMDS, and if nothing answers, conclude
"not on EC2, nothing to do", exit 0 and publish nothing. It is rejected, and the reason is
this ticket's own thesis pointed one layer down. Under that design, a **production box whose
IMDS broke** — hop limit reset to 1 by an
`ec2 modify-instance-metadata-options`, IMDS disabled, a local firewall rule — is
byte-for-byte indistinguishable from a self-hoster's bare-metal server: both publish nothing,
both are silent, both look exactly like health. That is a mechanism failing silently, which is
the class of bug HD-262 *is*. A control whose absence is silent is a control nobody has.

The DC path is therefore expressed as **installation**, which is already how every host ops
unit in this repository works (ADR-0011): a deploy places `/opt/hamstrack/ops/` and installs
nothing; an operator installs. A self-hoster on bare metal never runs the install step, no
series ever exists, and the three rules stay dormant because they are `noDataState: OK`. A
self-hoster *on EC2* installs it and it works with no configuration at all.

**`SNAPSHOT_SOURCE` defaults to `ebs`, not to `off`,** and that direction is deliberate. If
the default were `off`, the likeliest field outcome is: unit installed, `snapshot.env`
forgotten, collector runs and does nothing, layer 3 unwatched, and a green-looking box — HD-262
reproduced by the mechanism built to prevent it. Installing this unit *is* the declaration of
intent. `SNAPSHOT_SOURCE=none` exists only as an explicit off switch for a box that installed
it and later moved off EC2; in that state the collector **deletes the `.prom` it previously
published**, logs one line naming the setting, and exits 0 — after which the three rules go
dormant through `noDataState: OK`.

**It deletes rather than simply not writing, and the difference is the whole switch.**
node-exporter scrapes whatever is in the textfile directory for ever, and because the default
is `ebs`, this branch is only ever *reached* by a box that has already been publishing. A run
that exited without touching the file would therefore **freeze** it rather than turn it off:
`check_timestamp_seconds` stops advancing and `VolumeSnapshotCheckStale` fires at 3 h and
never clears, and a frozen `newest_timestamp_seconds` takes the **critical**
`VolumeSnapshotStale` with it at 30 h — an escape hatch handing a permanent critical alert to
the exact operator it was written for. **The `BACKUP_TARGET=local` analogy only holds for a
branch that removes:** `hamstrack-backup.sh` rewrites its whole `.prom` on every run and
*omits* the `upload` series, so flipping that switch actively takes the series off the air.
Not writing removes nothing. Sealed by `VolumeSnapshotCollectorContractTest`, which stages a
stale `.prom` in the work directory before running the branch — against an empty directory
"the file is absent" is true either way, and the assertion cannot see the bug.

**`systemctl disable --now` on the timer has the same shape and no such branch**, so
decommissioning is: run once with `SNAPSHOT_SOURCE=none` (or remove the `.prom` by hand),
*then* disable the timer. `docs/ops-prod-hardening.md` §6.9 carries the commands.

Validated the way `BACKUP_TARGET` is, and for the reason recorded there: **one** validated
value with both branches derived from it, refusing anything that is not exactly `ebs` or
`none` — two independent equality tests are not complementary, and `EBS`, or `ebs` with the
trailing CR a Windows checkout leaves behind, is precisely how a check ends up taking neither
branch while looking configured.

### 9.2 What does the alert rule do on an instance that will never publish this metric?

**`noDataState: OK` and `execErrState: OK`**, matching every rule in `rules.yml`, for the
reason `BackupStale` and `ConfigDrift` already carry: the rules must be dormant on an install
that runs the observability stack without this collector, or the product ships an alert that
fires on every self-hoster for a thing they do not have.

**The price is real and is the highest-risk item in this spec** (§11): deleting the `.prom`,
or losing the node-exporter bind mount, **silences all three rules** rather than firing any of
them. `VolumeSnapshotCheckStale` does not close this — it is itself `noDataState: OK`, so if
the file disappears its series disappears with the others. There is a residual, unclosed hole,
and the honest description of what this spec buys is: it closes *silent absence of the
artefact* and does **not** close *silent absence of the collector's output*. The only thing
that would close it is a rule written on `absent()`, and that is a decision about **all three
metric families at once** (backup, drift, snapshot) rather than about this one — a one-family
fix would be exactly the member-shaped answer this project keeps paying for. Open question 1.

### 9.3 New variables and where they are wired

There is **no new application environment variable**, and therefore no `application.properties`
key, no compose entry and no `docker-compose.prod.yml` change. Everything lives in
`/etc/hamstrack/snapshot.env`, read by the unit's `EnvironmentFile=-`, exactly as
`backup.env` does and for the reasons `.env.prod.example` already states about backups
(settings passed wholesale into the app container have no business holding ops config, and
systemd's parser is not Compose's).

| Variable | Default | Meaning |
|---|---|---|
| `SNAPSHOT_SOURCE` | `ebs` | `ebs` or `none`. `none` DELETES the published `.prom` and exits 0 — not writing one would freeze it, see §9.1 |
| `SNAPSHOT_REGION` | from IMDS `placement/region` | override only; must satisfy the policy's `aws:RequestedRegion` condition |
| `SNAPSHOT_TEXTFILE_DIR` | `/var/lib/node_exporter/textfile_collector` | where the `.prom` goes |
| `SNAPSHOT_IMDS_BASE` | `http://169.254.169.254` | **testing seam only** — the way `CONFIG_DRIFT_UNIT_DIR` exists so the drift comparison can be exercised off a production box |
| `SNAPSHOT_AWS_BIN` | `aws` | **testing seam only** — lets a fixture stand in for the CLI |

Deliberately absent: **`SNAPSHOT_VOLUME_ID`** (§4.2) and any threshold. Both alert thresholds
are provisioned in `rules.yml` and are not environment-driven, following `HostMemoryLow`,
`HostSwapInUse` and `MailDailyVolumeHigh`: if 30 h is wrong for your host, change it in the
file — there is no variable to set.

The wiring targets `dc-cloud-guard` would normally check therefore reduce to one pointer
comment: `.env.prod.example` already carries a "Backups — NOT CONFIGURED HERE, AND ON PURPOSE"
block; this adds a sibling sentence naming `ops/snapshot/snapshot.env.example` and
`/etc/hamstrack/snapshot.env`, with no variable.

---

## 10. What changes in which files

### New

| Path | Installed to | Mode |
|---|---|---|
| `ops/snapshot/hamstrack-volume-snapshot.sh` | `/usr/local/bin/hamstrack-volume-snapshot` | `0750 root:root` |
| `ops/snapshot/hamstrack-volume-snapshot.service` | `/etc/systemd/system/` | `0644` |
| `ops/snapshot/hamstrack-volume-snapshot.timer` | `/etc/systemd/system/` | `0644` |
| `ops/snapshot/snapshot.env.example` | `/etc/hamstrack/snapshot.env` | `0640 root:root` |
| `ops/snapshot/README.md` | — | — |
| `docs/adr/0037-durability-watched-by-artefact-age.md` | — | `Status: Proposed` |
| `docs/adr/0038-ops-collectors-may-query-the-cloud-control-plane.md` | — | `Status: Proposed` |

Unit shape, copied from `hamstrack-config-drift.service` and not re-derived:
`Type=oneshot`, `EnvironmentFile=-/etc/hamstrack/snapshot.env`, `MemoryHigh`/`MemoryMax`
sized for bash + curl + the AWS CLI (a PyInstaller bundle, 120–180 MB baseline — so **not**
the drift unit's 96/128 MB; start at `MemoryHigh=256M` / `MemoryMax=384M` and measure it the
way §6.3 step (f) measures the backup unit, by reading the cgroup **while it runs**, because
`systemctl show -p MemoryPeak` does not exist on AL2023's systemd 252),
`TimeoutStartSec=120`, `Nice=10` / `CPUWeight=20` / `IOWeight=20` / `IOSchedulingClass=idle`,
`NoNewPrivileges` / `PrivateTmp` / `ProtectHome` / `ProtectSystem=strict` with
`ReadWritePaths=/var/lib/node_exporter/textfile_collector`, and **no `[Install]` section** —
the timer is what gets enabled. Unlike the other two units this one needs **no docker socket**,
so `ProtectSystem=strict` here is a real reduction in capability rather than only a bound on
mistakes, and the unit should say so.

**And having said it, it must then finish the argument** — and finish it honestly, because
"needs no docker socket" is a claim about what the script *requires* and not about what it can
*reach*. The sandbox is carried further here than on the sibling units: an empty
`CapabilityBoundingSet=`/`AmbientCapabilities=`, `RestrictAddressFamilies=AF_UNIX AF_INET
AF_INET6` (curl to a link-local address, the CLI to an HTTPS endpoint, and `AF_UNIX` for
glibc's NSS path), `PrivateDevices=`, `RestrictSUIDSGID=`, `RestrictRealtime=`,
`ProtectKernelModules=`, `ProtectClock=`, `LockPersonality=` and `SystemCallFilter=@system-service`
with `SystemCallArchitectures=native`. **`User=` is considered and rejected, and the reason is
recorded in the unit rather than left to inference:** the publish is a `mv` into
`/var/lib/node_exporter/textfile_collector`, which is root-owned `0755` and shared with the
backup and drift units, so a non-root user needs write permission on a *directory two other
units own* — a change to three install procedures and an ownership fact nothing checks. Nothing
the script does needs a capability, and both paths it writes are root-owned with owner-write,
i.e. plain DAC.

**What bounds that `uid 0` is `ProtectSystem=strict` and the empty bounding set, doing two
different jobs, and neither of them makes it equivalent to a service user.** The tempting
shorthand — *"to remove a privilege the empty bounding set already removes, since root's DAC
bypass is `CAP_DAC_OVERRIDE`"* — is false, and this document carried it for a review round:
`CAP_DAC_OVERRIDE` only decides accesses **the owner bits deny**, and `uid 0` owns nearly
everything under `/etc`, `/usr` and `/var` with owner-write set, so a capability-less `uid 0`
still reads and writes all of it by ordinary DAC. What actually stops that is
`ProtectSystem=strict`. Capabilities also do not gate the kernel's **same-uid** checks at all,
so the residue is named rather than glossed: signalling other `uid 0` processes,
`PTRACE_MODE_READ` against them — i.e. `/proc/<pid>/environ` of the app and of every container,
which is where `DB_PASSWORD` and `JWT_SECRET` live — and **any root-owned socket**.
`/run/docker.sock` is `srw-rw---- root:docker` and `uid 0` is its *owner*, so `connect()` needs
neither a capability nor the group, and a read-only mount does not close it either: the
`-EROFS` check covers regular files, directories and symlinks and never a socket inode, which
is the same reason a `:ro` bind-mounted docker.sock still gives a container the full API. **The
socket one is closed by a setting; the other two are not, and are accepted.**
`InaccessiblePaths=-/run/docker.sock -/var/run/docker.sock` does the closing.
`ProtectProc=invisible` is set too, but be exact about what it buys, because this document
claimed more for it for a review round: it hides processes running as **other** uids
(postgres under `gosu postgres`, node-exporter as `nobody`), where the empty bounding set then
denies `CAP_SYS_PTRACE` — and it hides **no** other `uid 0` process, because
`hidepid=invisible` is enforced through `ptrace_may_access(…, PTRACE_MODE_READ_FSCREDS)` and
`__ptrace_may_access()` short-circuits to *allowed* when the caller's fsuid/fsgid equal the
target's uid/euid/suid and gid/egid/sgid, reaching the `ptrace_has_cap()` branch only when they
differ. `noaccess` and `ptraceable` take the same path, so there is no value of the setting that
closes it. The app's `environ` therefore stays readable from this unit while the app runs as
host `uid 0`, which it does (no `USER` in the `Dockerfile`, no `user:` for `app` in
`docker-compose.prod.yml`) — tracked as **HD-286**, a hardening gap rather than a live
vulnerability, since reading another root process's `environ` already requires root on the host,
and filed against the container rather than against this unit because one `USER` change closes
the residue for every root-capable unit on the box at once. `ProcSubset=pid` is **not** set: it
would also hide `/proc/meminfo` and `/proc/cpuinfo` from the PyInstaller-packaged AWS CLI.
`AF_NETLINK` stays out:
glibc's address-family probe opens one and falls back safely when it cannot, so admitting it
would buy nothing. Revisit `User=` if that textfile directory ever grows a group of its own.

A sandbox that is too tight does not degrade, it **fails the unit** — and a `SIGSYS` runs no
exit trap, so it *freezes* the `.prom` rather than publishing a failure, and nothing fires for
about three hours. §6.9 therefore gains a step that reads `systemd-analyze security` and the
unit's `Result`/`ExecMainStatus` **before the timer is armed**, `VolumeSnapshotCheckStale`'s
summary names that same command for the case where the timer is running fine and the unit is
being killed on every fire, and `docs/self-hosting.md` gains the pre-enable check too — this
is the first unit in the repo to run the AWS CLI under a seccomp filter, so the AL2023 evidence
does not transfer to a self-hoster's distribution.

### Changed

- **`observability/grafana/provisioning/alerting/rules.yml`** — the three rules of §4.4, with
  the comment block above them carrying: the two-outage evidence in one paragraph, why the
  metric is a timestamp and not an age, why DLM's `State` is not read, and the "add a stage,
  add its arm in the same commit" rule.
- **`docs/observability.md`** — new rows in the metric table (beside the
  `hamstrack_backup_*` rows, ~L233) and in the alert table (~L409); and an entry in the
  "fire it deliberately" section (~L659) — hand-write a `.prom` with
  `hamstrack_volume_snapshot_newest_timestamp_seconds{volume="vol-test"} 1`, which is how the
  rule is proved without AWS.
- **`docs/ops-prod-hardening.md`**, four edits:
  1. **§5.5 is now factually stale and must be corrected.** It records, as OPEN and undecided,
     that `vol-02d8251fd45b62472` is unencrypted and that nothing has been done. The volume was
     replaced with an encrypted one on **2026-08-29**. Rewrite it as a past-tense record with
     that date and the new volume id, keeping the reasoning about *why* encryption was the
     control.
  2. **New §5.6, "Replacing the root volume — and the tags that do not come with it."** This is
     the "carry the tags" step the ticket asks for, placed beside the encryption-swap notes,
     which is where the omission happened. Its content is stated as a property, not a
     checklist item: **a tag is a capability, and detaching a volume does not move it.** Steps:
     enumerate the old volume's tags before detaching; re-apply every one of them to the new
     volume; verify by *selection* rather than by inspection — `describe-volumes --filters
     Name=tag:Backup,Values=hamstrack` must return the **new** id and only the new id; then
     verify the next scheduled snapshot actually appeared. Plus the precondition outage 2
     supplies: **before adding a selection tag to a volume, check whether the volume carries a
     tag key that the DLM schedule also adds** — `CopyTags: true` together with
     `TagsToAdd: [{Key: Name, …}]` is a duplicate-key failure the moment the source volume has a
     `Name` tag, and it fails the whole schedule, not one snapshot. (The repair to
     `policy-0ee1644759462e1f7` itself stays out of this document per §2.)
  3. **§6.1** — the past-tense gap record, in the way §6.6 records drills, **covering both
     windows**: 08-29 → 09-03 (wrong volume, schedule green) and 09-04 → open (policy `ERROR`,
     duplicate tag key), with the shared root cause, the two volume ids, the policy id, the
     `DateModified`, and the sentence that matters — *layer 3 protected nothing for that
     period, and the artefacts it is the only layer covering are named in the table directly
     above*. Add, beside the layer table, which alert watches which layer: 1+2 by
     `BackupStale`/`BackupRunFailed`, 3 by `VolumeSnapshotStale` (new), **4 by nothing** —
     named as a known gap rather than left to inference.
  4. **The install step** — a new subsection in §6 in the same shape as §6.3: one SSM
     `send-command` with the `install` lines, `mkdir -p /etc/hamstrack
     /var/lib/node_exporter/textfile_collector`, `test -f /etc/hamstrack/snapshot.env ||
     install …example`, then a hand run through systemd (`systemctl start`, never a bare
     command), `cat` the `.prom`, confirm the series is in Prometheus, and only then
     `systemctl enable --now …timer`. Plus a line in §6.4's verification block:
     `aws ec2 describe-snapshots --owner-ids self --filters Name=volume-id,Values=$VOL` run
     with owner credentials, as the second party's version of the same question.
- **`docs/self-hosting.md`** — one paragraph under **Backups → On a schedule** (or as a short
  sibling subsection): this unit exists, it is **EC2-only**, it needs `ec2:DescribeVolumes` +
  `ec2:DescribeSnapshots` on the instance role, it is installed the same way as the backup
  timer, and on any other host you do not install it and nothing fires.
  **No `## Upgrading` subsection is added, and that is a decision.** Nothing here changes the
  behaviour of an existing install — a self-hoster who does nothing is unaffected — so there
  is no upgrade note to write, and inventing one would either name a version it does not
  belong to or require a deliberate exemption in `UpgradeNotesCoverageTest`'s
  `UNVERSIONED_SUBSECTIONS` for a section that has no reason to exist. The release notes are
  where this is announced.
- **`.env.prod.example`** — one pointer sentence in the existing "Backups" block (§9.3). No
  variable.
- **`docs/adr/README.md`** — two index rows, `Proposed`.

### Deliberately NOT changed, with the reason

- **`ops/deploy/synced-paths.txt`** — `ops/` is already synced wholesale, so `ops/snapshot/`
  travels to the box with the next deploy for free. Adding a line would be a second, narrower
  copy of a rule that already covers it.
- **`ops/deploy/apply-config.sh`** — §4.5. The collector is not on the deploy path.
- **`ops/drift/hamstrack-config-drift.sh`** — no change needed, and this is worth checking
  rather than assuming: `installed_path_for` maps `*.sh` → `/usr/local/bin/<name minus .sh>`
  and `*.service`/`*.timer` → `/etc/systemd/system/`, and `check_installed_ops` walks
  `find "$TARGET/ops" -type f` over all three suffixes. So the new unit is covered by
  `hamstrack_config_drift{scope="installed-ops"}` the day it ships, with **no new `scope`
  value** — which matters, because that label is a closed enum whose every addition costs a
  series per box and an arm in `ConfigDrift`'s summary.
- **`src/test/java/com/hamstrack/ops/GrafanaProvisioningContractTest.java`** — `MIN_ALERT_RULES`
  is a floor on the scan and not an inventory, so three new rules pass it unchanged; uid
  length, uid uniqueness and annotation-template validity are already category assertions
  covering whatever the file contains.
- Application code, migrations, `application*.properties`, `docker-compose*.yml`, the SPA.

### The one new test worth writing

`VolumeSnapshotAlertStageCoverageTest` (or an added case in the existing provisioning
contract test): assert that the set of `stage="…"` literals emitted by
`ops/snapshot/hamstrack-volume-snapshot.sh` is exactly the set of arms
`VolumeSnapshotCheckFailing`'s summary branches on. That rule currently exists only as a
comment above the backup rules — *"if you add a stage to the .prom, add its arm here in the
same commit"* — and a comment is not a mechanism. Phrase the failure message as the
propagation checklist, the way the throttled-path-set test does.

### Disclosure: what this prints, decided deliberately

`apply-config.sh` runs ops scripts at deploy time and their output travels through SSM into a
GitHub Actions log of a **public** repository — which is why the drift script's header states
its bound. **This collector is not on that path (§4.5), so its output reaches the journal
only.** The bound is stated anyway, because a future change could put it there:

- **Volume ids and snapshot ids are not secrets** and may be logged and labelled. They are
  identifiers within one account, not capability tokens; an attacker holding `vol-0867f8…`
  and no IAM credentials can do nothing with it, and anyone who can read the box can read the
  block devices anyway.
- **The AWS account id must not be printed**, and it is the one value that can arrive by
  accident: an `AccessDenied` message quotes the assumed-role ARN
  (`arn:aws:sts::<account>:assumed-role/hamstrack-ec2/i-…`). So a failed AWS call is reported
  as *which call failed and its error code*, never as the CLI's raw stderr. This is the first
  thing to redact if the check is ever moved onto the deploy path.
- Nothing reads `/opt/hamstrack/.env`, and there is no path by which a secret reaches this
  script's stdout.

---

## 11. Acceptance criteria

Checkable by a reviewer or `test-runner`. Items 1–7 need no AWS.

1. `ops/snapshot/` contains the five files of §10 with the modes stated, and
   `ops/snapshot/README.md` names the metric, the two IAM actions and the install step —
   matching `ops/backup/README.md`'s shape.
2. `hamstrack-volume-snapshot.sh` contains **no** `SNAPSHOT_VOLUME_ID`, no other way to
   configure a volume id, and no state file: the only writes are `$SNAPSHOT_TEXTFILE_DIR`,
   the lock file, and two scratch files the EXIT trap removes (the AWS CLI's captured stderr
   and the IMDSv2 token's `curl -H @file` header file, `0600`, so the token is never in an
   argv). Nothing survives a run.
3. The `.prom` is written to a temp file in the same directory, `chmod 0644`, then `mv -f`;
   leftover `*.prom.<pid>` files are swept at the start of a run.
4. An EXIT trap is installed **above** all configuration validation, and every `die` below it
   publishes `check_status{stage="resolve"} 0` and `{stage="describe"} 0`.
   `trap 'exit 143' TERM` is present.
5. `SNAPSHOT_SOURCE` is validated by **one** `case` with both branches derived from it;
   `SNAPSHOT_SOURCE=EBS` and `SNAPSHOT_SOURCE=$'ebs\r'` are both refused with a message
   naming the CR. `SNAPSHOT_SOURCE=none` **removes** an existing `.prom` (and any
   `*.prom.<pid>`) and exits 0 — asserted against a directory that HAS one, because against
   an empty one "absent" is true of a branch that does nothing.
6. `rules.yml` gains exactly the three rules of §4.4. `GrafanaProvisioningContractTest`
   passes, and every new uid is ≤ 40 characters (assert the actual lengths, not the intent).
7. **The rules fire without AWS.** Hand-write into the textfile directory:
   `hamstrack_volume_snapshot_newest_timestamp_seconds{volume="vol-test"} 1` →
   `VolumeSnapshotStale` fires within `for: 15m` and its summary names `vol-test`;
   `hamstrack_volume_snapshot_check_status{stage="describe"} 0` → `VolumeSnapshotCheckFailing`
   fires and its summary names the `describe` stage and its remedy, not the `resolve` one;
   `hamstrack_volume_snapshot_check_timestamp_seconds` set 4 h in the past →
   `VolumeSnapshotCheckStale` fires.
8. **On the instance**, a hand run through systemd (`systemctl start
   hamstrack-volume-snapshot.service`) exits 0, and the `.prom` names
   **`vol-0867f8d73630ca5a1`** — the currently attached root volume — and not
   `vol-02d8251fd45b62472`. This is the acceptance test for the whole ticket: the collector
   resolves the *live* volume, and the volume the DLM policy was wrongly pointed at is
   absent from its output.
9. **The 08-29 state is reproduced and detected.** Either against the real state (until a
   DLM snapshot succeeds, `newest_timestamp` is the 2026-09-03 manual snapshot, and once it
   is more than 30 h old the rule fires — i.e. **it is firing today**), or by pointing a test
   fixture at a volume with no snapshots and observing `newest_timestamp 0` with both stages
   at `1`.
10. **The `AccessDenied` path is exercised, not reviewed.** Temporarily narrow or detach
    `ec2-snapshot-read`, run once, observe `check_status{stage="describe"} 0`, confirm the
    journal names the failing call and its error code and **does not contain the account id**,
    then restore the policy. A translation path nobody forced is indistinguishable from one
    that works.
11. `journalctl -u hamstrack-volume-snapshot` for a successful run contains the volume id, the
    snapshot count and the newest `StartTime`, and contains no `.env` value and no credential.
12. `hamstrack_config_drift{scope="installed-ops"}` reads `1` when the installed copy of the
    script differs from `/opt/hamstrack/ops/snapshot/` and `0` after the install step —
    verifying that the new unit is picked up by the existing drift scope with no change to
    that script.
13. `docs/ops-prod-hardening.md` §6.1 carries a past-tense record naming **both** windows
    (08-29→09-03 and 09-04→open) with the policy id, the `StatusMessage` and the two volume
    ids; §5.5 no longer describes the root volume as unencrypted and undecided; §5.6 exists
    and contains the tag-carry step **and** the `CopyTags`/`TagsToAdd` duplicate-key
    precondition.
14. `docs/self-hosting.md` gains the EC2-only paragraph and **no new `## Upgrading`
    subsection**; `UpgradeNotesCoverageTest` passes unchanged.
15. `docs/observability.md` lists the three metrics and the three rules, and its
    "fire it deliberately" section carries the recipe from item 7.
16. Both ADRs exist under `docs/adr/`, `Status: Proposed`, dated with the record date, and are
    indexed in `docs/adr/README.md`.

---

## 12. Open questions

Recommended default given for each; none is invented.

1. **Should `absent()` replace `noDataState: OK` — for all three metric families at once?**
   This is the residual hole of §9.2: delete a `.prom` and every rule reading it goes quiet.
   **Recommendation: not in this ticket, and not for this family alone.** A one-family fix is
   the member-shaped answer; the question is whether `hamstrack_backup_*`,
   `hamstrack_config_*` and `hamstrack_volume_snapshot_*` should all gain an
   `absent()`-based companion rule that is dormant until the series has existed once. File it
   as its own ticket, because getting it wrong pages every self-hoster.
2. **Layer 4, and the remote state of layers 1/2, have no outcome check either.** §6.2 already
   records that the backup metrics measure the local run and never the remote object, so an
   archive silently emptied in S3 reads as green. **Recommendation: a follow-up ticket
   ("outcome checks for every durability layer"), scoped by the same principle**, not an
   expansion of this one.
3. **The 30 h threshold has no backtest** (§4.4). **Recommendation: ship 30 h, and re-check
   after a week of real DLM history on the live volume**, exactly as `HostSwapInUse`'s 128 MiB
   is flagged for re-check.
4. **Should a `pending` snapshot count?** **Recommendation: no** (§4.3). Revisit only if a
   real completion time is ever observed to approach the threshold's margin.
5. **Should `VolumeSnapshotStale`'s summary name `aws dlm get-lifecycle-policy` as a first
   move?** It would have printed the exact cause of outage 2 in one command.
   **Recommendation: yes, as text in the annotation — and no, as a call the collector makes.**
   Naming a command costs nothing and needs no grant; making the call is the mechanism check
   this ticket rejects.
6. **Does the volume label's cardinality-on-swap need bounding?** **Recommendation: no.** One
   new series per replacement, and the break is the signal. Revisit if volumes are ever
   replaced routinely.

### The highest-risk assumption, flagged

**That `noDataState: OK` is the right setting for `VolumeSnapshotStale`.** It is the one
choice in this spec that reintroduces, in miniature, the failure the spec exists to fix: a
collector whose output disappears is silent, and silence reads as health. It is chosen anyway
because the alternative pages every self-hoster who will never run this unit, and because it
is the settled convention of every other rule in the file — but the compensating control
(`VolumeSnapshotCheckStale`) shares the same blind spot, so **the hole is narrowed, not
closed**, and open question 1 is the only thing that closes it. If one item in this spec is
wrong, it is this one.

---

## 13. Architectural decisions (ADR)

Two decisions here are hard to reverse and will make a future contributor ask "why?". Both
are drafted as `Proposed`; the orchestrator flips them at finalize.

### ADR-0037 — a durability layer is watched by the AGE OF THE ARTEFACT it produces, never by the state of the mechanism that produces it

**Chosen:** every backup/durability layer gets an outcome check — "does a recent artefact of
the thing we mean to protect exist?" — resolved fresh from the live system, published as an
absolute timestamp, and alerted on `time() - ts`.

**Rejected:** (a) reading the scheduler's own status (DLM policy `State`, a timer's exit
code, a job's success flag); (b) publishing a pre-computed age.

**Trade-off:** an outcome check is later than a mechanism check — it cannot tell you a
schedule broke until an artefact is overdue — and it costs a read permission against the
system holding the artefact. In exchange it is blind to nothing: it catches a mechanism that
runs green over the wrong subject (outage 1), a mechanism that fails in a field nobody reads
(outage 2) and a failure mode nobody has imagined yet, because it never asks *why*.
Evidence: HD-262, two consecutive outages, each invisible to one of the two mechanism checks
available and both visible to this one.

### ADR-0038 — a host ops collector may query the cloud provider's control plane; the application never does, and the capability is enabled by INSTALLING the unit, not by a profile or an auto-probe

**Chosen:** cloud-specific monitoring dependencies live in host `systemd` units under `ops/`,
installed by an operator, holding their own narrowly-scoped read grants on the instance role.
The unit's default is "do the work" (`SNAPSHOT_SOURCE=ebs`); an unreachable control plane is a
published failure, not a silent stand-down.

**Rejected:** (a) putting the check in the application behind a Spring profile — it would make
the JAR carry an AWS dependency that a DC build can never use, which is the cloud-only
assumption `ADR-0006` forbids; (b) auto-detecting EC2 at run time and standing down quietly —
it makes a broken production check byte-identical to an uninterested self-hoster, which is
HD-262's own failure one layer down; (c) defaulting the unit to `off` — an installed,
unconfigured collector that silently does nothing is the same failure again.

**Trade-off:** the feature is unavailable to non-EC2 self-hosters with no equivalent path, and
this is accepted rather than papered over — a box with no volume snapshots has no snapshot age
to watch, and its durability story is layers 1/2 plus whatever its own hypervisor offers. The
cost inside the hosted deployment is a new permission class on the instance role and one more
thing an operator must remember to install; the `installed-ops` drift scope is what remembers
it for them.
