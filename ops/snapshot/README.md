# `ops/snapshot` — the root-volume snapshot age check (HD-262)

An hourly **host `systemd` timer** (not a compose service) that asks one question — *how old
is the newest completed EBS snapshot of the volume this instance is running on right now?* —
and publishes the answer as node-exporter textfile metrics, so that the next time backup
**layer 3** stops producing artefacts a person is told within a day.

| File | Installed to | Mode |
|---|---|---|
| `hamstrack-volume-snapshot.sh` | `/usr/local/bin/hamstrack-volume-snapshot` | `0750 root:root` |
| `hamstrack-volume-snapshot.service` | `/etc/systemd/system/` | `0644` |
| `hamstrack-volume-snapshot.timer` | `/etc/systemd/system/` | `0644` |
| `snapshot.env.example` | `/etc/hamstrack/snapshot.env` | `0640 root:root` |

**A deploy PLACES these files at `/opt/hamstrack/ops/snapshot/` and INSTALLS none of them**
(ADR-0011: `ops/` is in [`ops/deploy/synced-paths.txt`](../deploy/synced-paths.txt), and the
applier writes to nothing outside the compose project). So a change here reaches the box with
the next release and still does not change what runs on a schedule until somebody re-runs the
`install` commands — which is deliberate, and is why the gap has a metric of its own:
`hamstrack_config_drift{scope="installed-ops"}` goes to `1` while an installed copy differs
from the one under `/opt/hamstrack/ops`. **That coverage is inherited and needs no change to
the drift check**: `installed_path_for` already maps `*.sh` → `/usr/local/bin/<name minus
.sh>` and `*.service`/`*.timer` → `/etc/systemd/system/`, and `check_installed_ops` walks
`find "$TARGET/ops" -type f` over all three suffixes — so this unit is covered the day it
ships, under **no new `scope` value** (that label is a closed enum whose every addition costs
a series per box and an arm in `ConfigDrift`'s summary).

**The fourth row is read by systemd and not by the script.** The unit carries
`EnvironmentFile=-/etc/hamstrack/snapshot.env`; `hamstrack-volume-snapshot.sh` **does not
source it** (unlike `hamstrack-backup.sh`, which does — a wrong value there produces a wrong
*artefact*, and one here only a wrong *reading* that the metrics then report). So the
consequence to know **before** you debug: `sudo /usr/local/bin/hamstrack-volume-snapshot` run
by hand does **not** see `/etc/hamstrack/snapshot.env`, and neither does it get the unit's
`ReadWritePaths=` or its memory ceiling. Start it the way the timer does —
`sudo systemctl start hamstrack-volume-snapshot.service` — or pass the variables on the
command line.

**Host prerequisites**, all of them checked or needed before the script can publish anything:
**AWS CLI v2** (AL2023 ships it; a stock Ubuntu or Debian does not), `curl`, `flock`
(util-linux), and **`/var/lib/node_exporter/textfile_collector` existing before the unit
starts** — `ProtectSystem=strict` plus `ReadWritePaths=` makes systemd refuse to set up the
namespace otherwise, and the unit then fails before the script runs. The install step creates
it. There are **no credentials to configure**: the two IAM actions arrive with the instance
role over IMDS.

The install step, the two IAM actions and the verification commands are in
[`docs/ops-prod-hardening.md` §6](../../docs/ops-prod-hardening.md).

## What it publishes

| Metric | Labels | Meaning |
|---|---|---|
| `hamstrack_volume_snapshot_newest_timestamp_seconds` | `volume` | Unix time (`StartTime`) of the newest **completed** snapshot of the resolved volume. **`0` means "resolved successfully, and there are none"** — an answer, not a missing value. Absent means the run could not find out |
| `hamstrack_volume_snapshot_check_status` | `stage` (`resolve` / `describe`) | whether this run completed that stage (`1`) or not (`0`) |
| `hamstrack_volume_snapshot_check_timestamp_seconds` | — | when this check last ran, so a fresh answer can be told from a frozen one |

Read by three provisioned rules — `VolumeSnapshotStale` (critical, 30 h),
`VolumeSnapshotCheckFailing` (warning), `VolumeSnapshotCheckStale` (warning, 3 h) — in
[`observability/grafana/provisioning/alerting/rules.yml`](../../observability/grafana/provisioning/alerting/rules.yml),
documented in [`docs/observability.md`](../../docs/observability.md).

## Permissions

Two actions, both read-only, on the instance role `hamstrack-ec2` (inline policy
`ec2-snapshot-read`, `Resource: "*"` because EC2 `Describe*` supports no resource ARNs,
conditioned on `aws:RequestedRegion`):

```
ec2:DescribeVolumes
ec2:DescribeSnapshots
```

**It will never hold a write** — a monitor that can create a snapshot can be made to delete
one. `ec2:DescribeInstances` is deliberately absent, and the design is shaped around that
(the instance id comes from IMDS, the volume from a `DescribeVolumes` filter); do not add it
to make a more convenient query work. The region condition means the region is never
implicit: every call passes `--region` explicitly, resolved from this instance's own
placement.

## The properties that are load-bearing, and easy to erase by accident

1. **It is an OUTCOME check, not a mechanism check** (ADR-0037). It never asks whether the
   DLM schedule ran; it asks whether a restorable image of *this volume* exists. That is what
   catches a schedule running green over the wrong subject — which is what happened from
   2026-08-29 to 09-03, nightly, successfully, against a volume that had been detached since
   the 29th. Reading DLM's own `State` is rejected: it needs another IAM grant and it would
   have caught one of the two outages. It survives as a first move *named in the alert
   summary*, which costs nothing.
2. **The metric is a TIMESTAMP, never a pre-computed age.** An age gauge frozen in a `.prom`
   nobody rewrites reads as permanently fresh; a frozen timestamp gets older on its own, so a
   dead collector converges on the alert instead of hiding behind it.
3. **There is no stored, configured or overridable volume id.** The volume is resolved from
   the instance on every run, matched against the root device name IMDS reports — **not**
   `BlockDeviceMappings[0]` and **not** `Attachments[0]`, neither of which is guaranteed to be
   the thing you meant. Ambiguity is a failure, not a guess. A pinned id would be the same
   class of stale fact as the tag that caused the outage.
4. **Filtered by volume id, never by tag, and any snapshot counts whoever took it.** A tag is
   somebody's intention about a volume; a snapshot is the fact. It is also what makes the
   refusal actionable — an operator who cannot fix DLM right now can clear the alert by taking
   a snapshot by hand.
5. **An unreachable IMDS is a published failure, never a silent stand-down** (ADR-0038).
   Auto-detection was rejected because it makes a production box with broken IMDS
   byte-identical to a self-hoster's bare metal: both silent, both looking like health, which
   is this ticket's own failure one layer down. Opting in is *installing the unit*, and
   `SNAPSHOT_SOURCE` therefore defaults to `ebs` rather than to `off`.
6. **Turning it off means DELETING the `.prom`, not declining to write one.**
   `SNAPSHOT_SOURCE=none` removes the file and exits 0. node-exporter scrapes whatever is in
   the textfile directory for ever, and because the default is `ebs` the off switch is only
   ever *reached* by a box that has already been publishing — so a branch that exited without
   touching the file would **freeze** it: `check_timestamp` stops advancing,
   `VolumeSnapshotCheckStale` fires within 3 h and never clears, and the frozen snapshot age
   takes the **critical** `VolumeSnapshotStale` with it within 30 h. The escape hatch would
   hand a permanent critical alert to the exact operator it was written for. The
   `BACKUP_TARGET=local` analogy holds only in this direction: that script rewrites its whole
   `.prom` every run and *omits* the `upload` series, so the switch actively takes it off the
   air. **`systemctl disable --now` on the timer deletes nothing and freezes the file the same
   way** — run once with `SNAPSHOT_SOURCE=none` first
   ([`docs/ops-prod-hardening.md` §6.9](../../docs/ops-prod-hardening.md)).
7. **The `.prom` is written atomically** (temp file in the same directory, then `mv -f`)
   **and is `0644` on purpose.** node-exporter runs as `nobody`: a metric it cannot read is an
   alert that never fires, and `noDataState: OK` makes that failure completely silent.

## Deliberately NOT here

- **No `ExecStopPost=` handler**, unlike `hamstrack-backup.service`. A SIGKILL runs no trap
  and freezes the `.prom` — but the primary gauge here is a timestamp that ages by itself, so
  a killed run converges on `VolumeSnapshotStale` anyway and `VolumeSnapshotCheckStale`
  reaches the operator first with the right diagnosis. The cost is named rather than glossed:
  for the three hours between the kill and that threshold, `check_status` still reads `1`.
- **Not on the deploy path.** `apply-config.sh` runs the drift check at the end of every
  deploy and deliberately does not run this one: it would make a deploy depend on the EC2
  control plane and on IMDS, and it would publish a fresh `check_timestamp` at deploy time —
  recreating the "only ever as fresh as the last deploy" gap that `ConfigDriftCheckStale` had
  to be invented to cover. The consequence worth knowing: **nothing this collector prints
  travels off the box.**
- **Nothing that survives the run.** It writes `$SNAPSHOT_TEXTFILE_DIR`, its lock file, and
  two scratch files removed by the EXIT trap: the AWS CLI's captured stderr (never echoed —
  an `AccessDenied` quotes the assumed-role ARN, which carries the account id) and the IMDSv2
  token's header file (`curl -H @file`, `0600`, under the unit's `PrivateTmp=`, so the token
  never appears in `/proc/<pid>/cmdline` — node-exporter runs with `pid: host`). It holds no
  **state** between runs — §4.2 of the spec forbids it — so every run recomputes its answer
  from scratch and there is nothing to make idempotent.

## Read before changing anything

- **Design and every decision behind it** —
  [`docs/design/root-volume-snapshot-age-proposal.md`](../../docs/design/root-volume-snapshot-age-proposal.md),
  plus [ADR-0037](../../docs/adr/0037-durability-watched-by-artefact-age.md) and
  [ADR-0038](../../docs/adr/0038-ops-collectors-may-query-the-cloud-control-plane.md).
- **Running it yourself (DC / self-hosted)** —
  [`docs/self-hosting.md#backups`](../../docs/self-hosting.md#backups). It is **EC2-only**:
  on any other host you do not install it and nothing fires.
- **The metrics and the three alert rules** —
  [`docs/observability.md`](../../docs/observability.md).

`snapshot.env.example` is installed **and edited**, never replaced by a shorter file — though
the normal case is that nothing in it is edited at all, and that is the point: a check with
nothing to configure has nothing to configure wrongly.
