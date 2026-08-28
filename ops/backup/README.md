# `ops/backup` — the scheduled database backup (HD-187)

A daily logical dump of the production database, taken out of the running PostgreSQL
container by a **host systemd timer** (not a compose service), uploaded to object
storage, and published as node-exporter textfile metrics so a failure reaches a person.

| File | Installed to | Mode |
|---|---|---|
| `hamstrack-backup.sh` | `/usr/local/bin/hamstrack-backup` | `0750 root:root` |
| `hamstrack-backup.service` | `/etc/systemd/system/` | `0644` |
| `hamstrack-backup.timer` | `/etc/systemd/system/` | `0644` |
| `backup.env.example` | `/etc/hamstrack/backup.env` | `0640 root:root` |

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

Five properties are load-bearing and easy to erase by accident:

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

A backup nobody has restored is a belief. The restore drill is the procedure that
converts it, and its log table is in `docs/ops-prod-hardening.md` §6.
