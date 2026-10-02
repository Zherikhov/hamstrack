# `ops/backup` — the scheduled database backup (HD-187)

A daily logical dump of the production database, taken out of the running PostgreSQL
container by a **host job** (not a compose service), uploaded to object storage — or kept on
a local disk, which is what `BACKUP_TARGET=local` is for and the only mode that asks nothing
of the host beyond the tools below — and published as node-exporter textfile metrics so a
failure reaches a person.

| File | Installed to | Mode |
|---|---|---|
| `hamstrack-backup.sh` | `/usr/local/bin/hamstrack-backup` | `0750 root:root` |
| `hamstrack-backup.service` | `/etc/systemd/system/` | `0644` |
| `hamstrack-backup.timer` | `/etc/systemd/system/` | `0644` |
| `backup.env.example` | `/etc/hamstrack/backup.env` | `0640 root:root` |

**The two unit files are the hosted instance's scheduler, not the mechanism** — see "the
scheduler is not part of the mechanism" below. An install without systemd skips exactly those
two rows and runs the same script from
[cron](../../docs/self-hosting.md#without-systemd-one-cron-line).

**What the script needs is a tool set, not a distribution.** `docker` with the Compose plugin,
`bash`, `md5sum`, `flock` and `find` always; GNU `find`/`tail`/`cut` and `aws` (v2 ≥ 2.19) only
when `BACKUP_TARGET=s3`, which is also the only mode whose pre-flight asks for `aws` at all.
Measured on BusyBox 1.37.0: `local` mode completes, `s3` mode refuses in pre-flight because
`find -printf` and `tail -z`/`cut -z` are GNU-only and `keep_newest` needs all three.

**What makes root a requirement rather than a convention is the three directories the script
writes** — `BACKUP_LOCAL_DIR`, `BACKUP_STATE_DIR` and `BACKUP_TEXTFILE_DIR`, all `root:root` from
the documented install — together with `/etc/hamstrack/backup.env` at `0640 root:root`. It is
*not* the lock, on most of this audience: `/var/lock` is `1777` on Debian 13 and Ubuntu 24.04 and
`0755` only on Alpine 3.21 (measured), so the lock blocks an unprivileged run on some
distributions and on plenty of others it does not. The script never checks who it runs as, and the
textfile directory is where its failure stops reaching anybody: under the documented permissions a
non-root run dies at `install -d` *above* pre-flight, and the `EXIT` trap's `write_metrics` cannot
create its temp file in that root-owned `0755` directory — so it exits 1 with two `install:` lines
on stderr and **publishes no `.prom` at all**, leaving the previous run's
`hamstrack_backup_last_status{stage="dump"} 1` standing and `BackupRunFailed` silent until
`BackupStale` fires 26 hours later (measured in `debian:stable-slim`). A rig whose three
directories are all writable by that user publishes `{stage="dump"} 0` instead; that is the rig
talking, not the install.

**A deploy PLACES these files at `/opt/hamstrack/ops/backup/` and INSTALLS none of them**
(HD-199: `ops/` is in [`ops/deploy/synced-paths.txt`](../deploy/synced-paths.txt), and the
applier writes to nothing outside the compose project). So a change here reaches the box
with the next release and still does not change what runs on a schedule until somebody
re-runs the `install` commands — which is deliberate, and is why the gap has a metric of its
own: `hamstrack_config_drift{scope="installed-ops"}` goes to `1` while an installed copy
differs from the one under `/opt/hamstrack/ops`. The install commands, the AWS-side
bucket/IAM/lifecycle/DLM setup and the restore drill live in
[`docs/ops-prod-hardening.md` §6](../../docs/ops-prod-hardening.md).

**Read before changing anything:**

- **Design and every decision behind it** —
  [`docs/design/production-backups-proposal.md`](../../docs/design/production-backups-proposal.md).
- **Running it yourself (DC / self-hosted)** —
  [`docs/self-hosting.md#backups`](../../docs/self-hosting.md#backups), including
  `BACKUP_S3_ENDPOINT` for an S3-compatible store and `BACKUP_TARGET=local` for no
  object store at all.
- **The metrics and the two alert rules** —
  [`docs/observability.md`](../../docs/observability.md).

`backup.env.example` is installed **and edited**, never replaced by a shorter file: its
comments are the only place several of these settings are explained.

These properties are load-bearing and easy to erase by accident (the list carries no count
on purpose — a number goes stale one entry before the list does):

1. **The script never deletes a remote object, and cannot overwrite one either.** Retention
   is an S3 lifecycle rule; the instance role has no `s3:DeleteObject` anywhere; and every
   upload carries `--if-none-match "*"` (AWS CLI ≥ 2.19) against a bucket policy that
   refuses a `PutObject` without it, **anywhere in the bucket** — `manual/` included, since
   fix round 2. Without that last part, `PutObject` + `ListBucket` is enough to destroy the
   archive — overwrite every key with an empty body and let the lifecycle rule expire the
   good versions. (`BACKUP_KEEP_LOCAL` and `BACKUP_KEEP_LOCAL_DAYS` sweep *staged local
   copies* only — a different thing, and intended.)
2. **The `.prom` file is written atomically** (temp file in the same directory, then
   `mv`) **and is deliberately `0644` while everything else the script writes is `0600`.**
   node-exporter runs as `nobody`: a metric it cannot read is an alert that never fires,
   and `noDataState: OK` makes that failure completely silent.
3. **The ETag check is only valid for a single PUT into an SSE-S3 bucket.** Switching to
   multipart or to SSE-KMS silently turns the integrity check into a comparison that can
   never match — see §6.5 of the spec before changing either. It is `auto` by default and
   downgrades to a warning behind `BACKUP_S3_ENDPOINT`, because a non-AWS store may return
   something else entirely and a hard failure there would store the object and then report
   the run failed forever.
4. **The environment wins over the config file.** The script re-applies any `BACKUP_*` /
   `AWS_*` it was invoked with after sourcing, which is what makes
   `BACKUP_S3_PREFIX=manual BACKUP_LABEL=… /usr/local/bin/hamstrack-backup` land in
   `manual/` instead of in the 30-day `daily/`.
5. **A run that stops for any reason leaves the metrics saying so — and the more specific
   answer wins.** The `EXIT` trap publishes on every ordinary exit, including a `die` in
   config validation — which is why the trap is installed *above* that block and above the
   `install -d` calls (a hand run tripping the `ReadWritePaths=` footgun has no
   `ExecStopPost=` behind it), and why the `--stop-post` branch sits above them too.
   `trap 'exit 143' TERM` routes the graceful half of `TimeoutStartSec=` through the same
   trap, since bash runs no `EXIT` trap for an untrapped fatal signal; and `ExecStopPost=`
   covers SIGKILL (OOM, the ungraceful half of the timeout), where no trap runs at all and
   the previous run's success would otherwise stand as the current answer. `ExecStopPost=`
   fires on *any* non-success result, so it stamps **only when the trap did not** — it
   compares a marker `cleanup` writes as its last act, the systemd `$INVOCATION_ID`, with
   its own. It has to be the invocation and not a timestamp: `run_start` is written by the
   lock winner, so a timestamp marker and the recorded start were BOTH the previous run's
   value throughout a run's prologue and matched each other before this run had done
   anything. Otherwise the handler's coarse `dump=0 upload=0 size=0` erases the trap's
   `dump=1 upload=0 size=<real>`, which is the exact asymmetry the two-stage metric exists
   for. The one deliberate silence is the `flock` loser: it exits 0 touching nothing, so it
   cannot forge freshness, and a permanently stuck lock still surfaces as staleness.
   The same rule is why the config file is RUN IN A CHILD SHELL first and then sourced with
   its return value TESTED: a `backup.env` that does not *parse* used to abort the script
   above both the trap and the `--stop-post` branch, and then abort `ExecStopPost=` at the
   identical line, so nothing was published at all and the previous run's success stood. It
   now degrades to defaults, reports the failure, and refuses to take a backup with them.
   The child shell is what extends that to a file which parses and then *fails*
   (`BACKUP_S3_PREFIX=$(some-tool)`): `set -e` is suppressed inside `. "$CONF" || …`, so the
   source would run to EOF and the run would upload to the DEFAULT prefix and call itself a
   success. A broken config is always a refusal, whatever the environment happens to hold.

6. **The scheduler is not part of the mechanism, and nothing here may make it one.** Freshness
   leaves this script as `hamstrack_backup_last_success_timestamp_seconds` in a textfile, which
   is the one series `BackupStale` reads; both rules read that file, and neither can tell — nor
   may be taught to tell — what started the run. That is what lets a host without systemd run the same script from cron and
   keep the same alarm, which is the only answer available to an operator who cannot install
   host units. So a freshness signal must never be moved onto something only the unit knows
   (`$INVOCATION_ID`, `systemctl show`, a journal field): the `--stop-post` handler is allowed
   to read the invocation because it only decides whether to *overwrite* a stamp the trap may
   already have written, and it is reached by nothing else. The documented schedules — the
   `.timer` and the crontab line in `docs/self-hosting.md` — are held against the alert bound
   together, by `OpsWitnessContractTest`, so a second scheduler cannot be prescribed at a
   period the 26-hour rule could not absorb. That arm demands a schedule for **this script by
   name** rather than counting prescriptions, because a count is satisfied by any sibling while
   this one walks out of the scan re-pathed or rewritten `@daily`; a period it cannot read, a
   shortcut form and a command resolving to no script here are each a red build saying "teach
   the parser", never a silent pass.

A backup nobody has restored is a belief. The restore drill is the procedure that
converts it, and its log table is in `docs/ops-prod-hardening.md` §6.
