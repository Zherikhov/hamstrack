# Production backups — a second copy of the data, and proof it can be read back (HD-187)

**Status:** proposal / design review. **Date:** 2026-08-26. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness). **Gates:** HD-188 (Flyway chain squash — it rewrites
`flyway_schema_history` on the live production database and must not be the first time anybody finds
out whether a restore works).
**Related:** `docs/ops-prod-hardening.md` (§1 S3 + instance role, §3 SSM-only, §5 memory),
`docs/observability.md` (metric + alert reference), `observability/grafana/provisioning/alerting/rules.yml`,
`docs/self-hosting.md#backups`, `docker-compose.prod.yml`, `docker-compose.observability.yml`,
HD-122 (config auto-sync never shipped), HD-180 (mem limits on the other containers), HD-189 (box resize).

---

## 1. Problem & goal

Production holds the only copy of every workspace, issue, comment and attachment reference in the
product, and there is no second copy of any of it. `docker-compose.prod.yml` declares `app`,
`postgres` and `caddy`; `.github/workflows/` holds `build.yml` and `deploy.yml`; there is no
`pg_dump` schedule, no EBS snapshot policy, no WAL archiving, and nothing that copies a byte off the
instance. PostgreSQL runs as a container on a single EC2 instance on a single EBS volume, so the loss
of that volume — a corrupted filesystem, a mistaken `docker volume rm`, a bad migration, an
instance-store event — is the loss of the product's entire history. The only backup material in the
repository is the `pg_dump` recipe in `docs/self-hosting.md#backups`, which is advice we give to
*other people* and have never once followed ourselves.

**Goal.** Every day, a consistent logical copy of the production database leaves the box and lands in
storage the box cannot read back or erase; the whole machine has a short-horizon volume snapshot; a
failure of either is visible to a person within hours rather than at the moment somebody needs the
backup; and there is a written procedure — already executed once, with the date in the docs — that
turns one of those copies back into a running application.

**Success looks like:** the sentence "we have backups" is replaced by a dated row in a restore-drill
log that names the object restored, the row counts it produced and the application version that
booted against it under `ddl-auto=validate`.

**The distinction this spec is organised around:** a backup nobody has restored is a belief. Every
acceptance criterion in §17 is phrased so that a reviewer can tell the difference between the two.

---

## 2. Scope

### 2.1 The layers, and what each one actually protects against

| # | Layer | Protects against | RPO | In scope? |
|---|---|---|---|---|
| 1 | **Logical `pg_dump` (custom format) → separate S3 bucket, daily** | bad migration, dropped table, application bug that deletes rows, accidental `DELETE`, ransomware/compromise of the box, total loss of the instance **and** total loss of the volume | 24 h | **Yes — the core of this ticket** |
| 2 | **`pg_dumpall --globals-only` alongside it** | a restore that comes up but whose login roles (`hamstrack`, a `pg_monitor` exporter role) do not exist | 24 h | **Yes** |
| 3 | **EBS snapshot of the root volume via DLM, daily** | loss of the *box*: `/opt/hamstrack/.env` (which holds `JWT_SECRET` and the DB password and exists in **no** other place), the hand-maintained `Caddyfile`, Caddy's certificate store, the observability volumes, and any local attachments left over | 24 h | **Yes — pure AWS-side config, zero footprint on the instance** |
| 4 | **Versioning + noncurrent-version expiry on the *attachments* bucket** | an accidental or malicious delete/overwrite of an attachment; today the app's instance role holds `s3:DeleteObject` on that bucket and nothing undoes a delete | n/a | **Yes — three commands, owner-side** |
| 5 | **PITR / continuous WAL archiving** (pgBackRest, WAL-G, `archive_command`) | the last 24 hours of work that layer 1 loses | minutes | **No — out of scope, named below** |
| 6 | **Cross-region copy of the backup bucket** | an `eu-north-1` region event | 24 h | **No — out of scope, named below** |

### 2.2 Out of scope, named so nobody reads the shipped mechanism as covering them

- **Point-in-time recovery.** Layer 1 loses up to 24 hours. Closing that needs a permanently running
  archiver process, a `postgresql.conf` change (`wal_level`, `archive_mode`, `archive_command`), a
  restore procedure an order of magnitude more involved, and memory this box does not have (§4.2).
  It is a separate ticket, and the honest framing for a product with its first real user is that a
  24-hour RPO with a *proven* restore beats a 5-minute RPO with an unproven one.
- **Cross-region durability.** The backup bucket is in `eu-north-1`, the same region as the instance.
  S3 in one region is already 11-nines durable across three availability zones, so this protects
  against everything except a region-scale event. A follow-up ticket adds S3 Replication to a second
  region; it is a bucket setting, not a code change, and it can be added later without touching
  anything in this spec.
- **Backing up Grafana/Loki/Prometheus data.** Observability data is derived and disposable; the
  dashboards and rules are in the repository. Layer 3 sweeps the volumes up incidentally, which is
  enough.
- **A restore *button*, an admin UI, or any API surface.** This ticket adds no endpoint (§12).
- **Automating the restore drill.** The drill is a written human procedure (§15). Automating it
  needs a runner with 2 GB of RAM and credentials, which is a bigger ticket than the backup itself.
- **Backing up an operator's own DC install.** DC gets documentation and a script that *works* there;
  it gets no scheduled job it did not ask for (§14).

### 2.3 Non-goals

This does not make production highly available and does not shorten downtime. It bounds *data loss*,
not *outage*. Restoring is a manual, tens-of-minutes operation, and §15 measures how long rather than
claiming a number.

---

## 3. Actors & permissions

No application actor. This is infrastructure: there is no endpoint, no role, no workspace scoping and
no tenant-visible resource — §11 and §12 say so in the terms `tenancy-reviewer` reads.

The principals that do exist:

| Principal | What it may do | What it deliberately may **not** do |
|---|---|---|
| **The instance role `hamstrack-ec2`** (already attached, already carries `AmazonSSMManagedInstanceCore` and the attachments-bucket policy) | `s3:PutObject` under `daily/` and `manual/` in the backup bucket; `s3:ListBucket` on those two prefixes | **read any backup object** (`s3:GetObject`), **delete any backup object** (`s3:DeleteObject`), touch any other prefix, touch any other bucket |
| **The DLM service role** `AWSDataLifecycleManagerDefaultRole` | create/delete/tag EBS snapshots | anything else |
| **The owner** (AWS account credentials, from CloudShell or a laptop) | create the bucket and policies, read backups back, run the restore drill, delete `manual/` objects | — |
| **`root` on the instance** (the systemd unit) | `docker exec` into the postgres container, write `/var/backups/hamstrack` and the textfile directory, call the AWS CLI with the instance role | — |

**Why the box may write but never read or erase.** A backup an attacker who owns the instance can
download is an exfiltration channel for every tenant's data at once, and a backup they can delete is
no backup at all — the standard second move of a ransomware operator is to erase the backups with the
credentials the victim helpfully attached to the machine. Write-only is achievable here at zero cost
because the only reader is a human doing a restore, and that human has account credentials. Retention
follows from the same rule: it is enforced by an **S3 lifecycle rule**, which is a property of the
bucket evaluated by AWS, and never by the script deleting its own old objects — a script that can
delete one backup can be made to delete all of them (§6.4).

The instance role has one privilege escalation worth stating: `root` on the box can already read the
live database, so granting it `PutObject` on a backup bucket adds no *read* access to tenant data it
did not have. What it adds is a write channel into a new bucket, which is why that bucket holds
nothing but backups.

---

## 4. Decision 1 — where the job runs

### 4.1 Recommendation: a host **systemd timer** running a repo-owned script, not a compose service

| Option | Verdict |
|---|---|
| **A. `systemd` timer + oneshot service on the host, script `docker exec`s the running postgres container** | **Recommended.** |
| B. A new compose service (cron/supercronic sidecar) in `docker-compose.prod.yml` | Reject — §4.2. |
| C. A GitHub Actions schedule firing an SSM `send-command` | Reject — §4.3. |
| D. Move Postgres to RDS and use automated backups | Reject for this ticket — it is an infrastructure migration, costs roughly the price of the instance again, and would fork the DC and Cloud database story. Worth its own ticket someday; it does not gate HD-188. |

**Why A.**

1. **Memory.** `docs/ops-prod-hardening.md` §5, measured 2026-08-26: 1909 MB total, ~335 MB
   available, **no swap**, and only the app container has a `mem_limit`.
   > **Two of those facts were re-measured 2026-08-28 (HD-189) and one of them was
   > backwards. The decision is unchanged and better supported.** There was no `mem_limit`
   > on `app`, `postgres` *or* `caddy` (`HostConfig.Memory = 0` on all three); the only
   > bounded containers were the seven observability ones. And swap now exists — a 1023 MB
   > file at `vm.swappiness=10`. Neither correction helps a resident sidecar: an unbounded
   > `app` means a permanent sidecar competes with a JVM that has *no* ceiling, and swap is
   > an emergency buffer whose only use here would be to make the box slow instead of dead.
   > Current numbers: `docs/ops-prod-hardening.md` §5.
   A compose sidecar costs its
   resident set *permanently* — a cron container idles at 5–30 MB and 24 h a day to do 30 seconds of
   work. A systemd timer costs **nothing** until it fires: `systemd` already runs, and a timer is a
   record in it. The requirement in the ticket is "must be small"; the smallest thing that can
   schedule work on this box is the scheduler that is already running.
2. **The transient cost is *boundable*, and that is the safety property.** `MemoryMax=` on the
   service unit puts the run in its own cgroup. If the dump ever outgrows the budget, the kernel kills
   **the backup**, not the app, and the failure shows up as a stale freshness metric and an alert
   (§8) rather than as an application OOM at 03:15. A compose sidecar can carry a `mem_limit` too, so
   this is not decisive on its own — but combined with (1) it means the job's *entire* memory cost is
   paid only while it runs.
3. **No new image.** The dump is taken by `pg_dump` **inside the postgres container that is already
   running** (`docker exec`), so the client version can never drift from the server version — the
   classic "`pg_dump: server version 16.4; pg_dump version 15.2` refused" failure is structurally
   impossible. The upload uses the AWS CLI v2, which ships preinstalled on Amazon Linux 2023 (and is
   already used by hand in `docs/ops-prod-hardening.md` §1). Nothing is pulled, nothing is built.
4. **It reaches S3 the way the app already does.** The host resolves the instance role from IMDS
   directly — no hop-limit concern (the limit is already 2), no static keys, the same proven path as
   attachments.

**The one real cost of A, stated plainly.** Alloy collects logs **through the Docker socket**, so a
systemd unit's output goes to journald and is **invisible in Loki**. The mitigations: (a) the signal
that must reach a person is the metric + alert, not the log line, and that is by design (§8);
(b) the text survives on the box for post-mortem — `journalctl -u hamstrack-backup -n 200`;
(c) the fix, if the gap ever bites, is a `loki.source.journal` component in
`observability/alloy/config.alloy`, which is a follow-up ticket and not needed for this one to be
correct. A compose service would have had Loki for free; that is the single thing option B wins, and
it does not outweigh a permanent resident set on a 1909 MB box.

### 4.2 Why not a compose service

Beyond the idle memory: a compose service needs a scheduler inside it, which is either a second
process in a Postgres image (a `while sleep` loop, unsupervised) or a new pinned image
(`supercronic`, `ofelia`, `prodrigestivill/postgres-backup-local`) — a new supply-chain dependency
with a database superuser password in its environment, for a job the host can already do. It would
also need either the Docker socket (the highest-privilege grant in the stack, which
`docker-compose.observability.yml` already flags for Alloy) or its own `pg_dump` client, reintroducing
version drift. And it would be a fourth ticket in this release editing compose files (§4.4).

### 4.3 Why not a GitHub Actions schedule

It would put production's data-durability guarantee behind GitHub's scheduler (documented to skip runs
under load), a third-party outage, and the `hamstrack-deploy` IAM user's keys. It also makes the
backup stop the day the repository is archived, renamed or made private. Backups must not depend on
CI being healthy — the day you need them is disproportionately likely to be a day something else is
also broken.

### 4.4 The HD-122 question — decided: **no hard dependency; carry a one-time operator step**

The deploy syncs nothing. `deploy.yml` runs exactly one SSM command (`cd /opt/hamstrack && docker
compose … pull && … up -d --remove-orphans && docker image prune -f`); the config auto-sync described
in `docs/ops-prod-hardening.md` was never shipped (that is HD-122, now in this release), and
`docs/observability.md` §"Running it" step 1 used to assert it does — corrected in HD-197, which
needed the true answer for a different reason (a fail-fast guard that a deploy will never install for
you). Anything else that says config ships from the repo is still wrong until HD-122 lands.

**HD-187 must not wait for HD-122.** Three reasons, in order of weight:

1. **HD-122 could not deliver this ticket's main artifacts anyway.** The sync it describes copies
   exactly three paths: `docker-compose.prod.yml`, `docker-compose.observability.yml` and
   `observability/`. A systemd unit, a timer, a shell script and `/etc/hamstrack/backup.env` are none
   of those. Under HD-122 as designed, the manual step still exists — so making HD-187 wait buys
   nothing it needs.
2. **HD-187 gates HD-188, which is the release's schema squash.** Serialising the release's gating
   ticket behind an unrelated pipeline change lengthens the critical path for no durability benefit.
3. **The operator is already at the console.** The bucket, the IAM policy and the DLM policy require
   the owner's AWS credentials in one sitting (§16). Placing four files on the box in that same
   sitting is one extra `aws ssm send-command`, not a new occasion.

**But two of this ticket's artifacts *do* ride HD-122's channel**, and they must be called out:

| Artifact | Path | Delivered by |
|---|---|---|
| node-exporter textfile flag + mount | `docker-compose.observability.yml` | HD-122 once it ships; **until then, the one-time operator step copies it** |
| `BackupStale` / `BackupRunFailed` alert rules | `observability/grafana/provisioning/alerting/rules.yml` | same |
| script, units, `backup.env` | `ops/backup/*` (new dir) | **always** the operator step — no sync covers it |

**Recommendation to the owner:** ship HD-122 in this release, and extend it by one path — add `ops/`
to the synced set — so that from 0.18.0 onward the script is repo-delivered like everything else and
only `/etc/hamstrack/backup.env` and the systemd units stay hand-placed. That extension is a
follow-up note on HD-122, not a blocker here. **HD-180 and HD-189 also edit compose files in this
release**; if all four land, the operator's single hand-copy of the two compose files at the end of
the release covers all of them at once. Sequence the manual copy **last**, after the final compose
change of the release, or it will be done twice.

---

## 5. Decision 2 — what is backed up

### 5.1 Layer 1 & 2: the logical dump (this ticket's mechanism)

Per run, two objects, one timestamp:

```
daily/hamstrack-2026-08-27T031500Z.dump          # pg_dump -Fc  (custom format, self-compressed)
daily/hamstrack-2026-08-27T031500Z.globals.sql   # pg_dumpall --globals-only
```

**Custom format (`-Fc`), not plain SQL.** It compresses itself (no external `gzip` in the pipeline, so
the object's MD5 — and therefore its ETag — is the MD5 of exactly what `pg_dump` produced, which is
what makes the end-to-end integrity check in §6.5 free), it supports `pg_restore --list` for a
structural inspection without a database, it supports selective and parallel restore, and it is the
format `pg_restore --exit-on-error` is designed for. `docs/self-hosting.md` keeps documenting plain
SQL for self-hosters, deliberately: a self-hoster wants a file they can `grep` and pipe into `psql`,
and the two audiences do not have to use the same format.

**Consistency.** `pg_dump` takes an MVCC snapshot; the database keeps serving and nothing is
quiesced. The dump is consistent as of its start time. The globals dump is a separate connection and
therefore a separate snapshot — an irrelevant difference, because roles change roughly never.

**Why globals are worth a second object.** A restore into a fresh Postgres recreates the `hamstrack`
login role from `POSTGRES_USER`/`POSTGRES_PASSWORD` in compose, but not a `hamstrack_exporter`
`pg_monitor` role, nor any role an operator added by hand. Cost: a file of a few kilobytes. Benefit:
the restore comes up *complete* instead of coming up and then quietly failing postgres-exporter's
login. **Security note:** `pg_dumpall --globals-only` includes SCRAM password verifiers. The bucket
already holds every user's password hash by way of the `users` table, so this does not change the
sensitivity class of the bucket's contents — but it is why the bucket is private, encrypted, and
readable by nobody with the instance role (§7).

**Not in the dump, and not lost:** `flyway_schema_history` **is** in the dump (it is an ordinary table
in the `hamstrack` database) — which is precisely what makes this backup HD-188's safety net.

### 5.2 Layer 3: EBS snapshots (this ticket's owner-side config)

A daily Amazon Data Lifecycle Manager policy on the instance's volume, retaining **7** snapshots.

**What it is for, and it is not "the database again".** It is for the things that exist in exactly one
place and are not in the repository: `/opt/hamstrack/.env` (which holds `JWT_SECRET` — lose it and
every session and every outstanding verification/reset token is void — plus `DB_PASSWORD`,
`SEED_ADMIN_PASSWORD`, the SMTP credentials and `GF_SECURITY_ADMIN_PASSWORD`), the hand-edited
`Caddyfile` with its Cloudflare `trusted_proxies` block (`docs/ops-prod-hardening.md` §2, explicitly
divergent from the repo copy), the `caddy_data` volume holding issued certificates, and — should
`STORAGE_TYPE` ever be `local` — the attachments volume. It also turns "the box is gone" from a
rebuild-from-documentation exercise into a volume restore.

**Crash consistency is sufficient here.** A snapshot of a running Postgres is crash-consistent, and
PostgreSQL is designed to come up from a crash by replaying WAL. The whole data directory is on the
**one** volume, so there is no torn-across-volumes hazard. This is a real, usable recovery path — but
it is the *second* one, and the logical dump remains the primary because a snapshot cannot rescue you
from a bad migration that a snapshot faithfully preserves.

**Retention 7 days, not 30.** A snapshot answers a short-horizon question ("the box died", "I broke
`/opt/hamstrack` yesterday"); those are noticed within hours. A full-volume snapshot also costs
meaningfully more than a few megabytes of dump, and the long-horizon question ("last month's data was
already wrong") is answered by layer 1's 30 days. Two horizons, two costs, deliberately different.

### 5.3 Layer 4: attachments bucket hygiene

The attachments bucket was created by `docs/ops-prod-hardening.md` §1 with public access blocked and
**no versioning**. The app's instance role holds `s3:DeleteObject` on it. So an application bug, or an
attacker with the instance role, can delete attachments irreversibly, and the daily dump does not help
— the dump holds the attachment *rows*, whose storage keys would then point at nothing.

Fix, owner-side, three commands (`docs/ops-prod-hardening.md` §6.2 step 5): enable
**versioning**, add a lifecycle rule expiring **noncurrent** versions after **30 days**, and abort
incomplete multipart uploads after 7 days.
Deletes become tombstones recoverable for a month; storage cost grows only by the size of things that
were deleted or overwritten.

---

## 6. Decision 3 — where it lands, and for how long

### 6.1 A separate bucket, not a prefix in the attachments bucket

**Recommended: a new private bucket, `hamstrack-backups-<something-unique>`, in `eu-north-1`.**

- The instance role holds `s3:GetObject` **and** `s3:DeleteObject` on the attachments bucket, because
  the application legitimately needs both. A backup prefix inside that bucket would inherit the blast
  radius of every application storage bug and of any compromise of the app. A separate bucket is what
  makes "write, never read, never delete" expressible at all (§7).
- The two have different lifecycles (attachments: forever; backups: 30 days), different versioning
  needs, and different audit stories.
- Same region as the instance: uploads are free and fast, and the region is where the restore will be
  performed. Cross-region replication is the named follow-up (§2.2).
- **Name it something globally unique** — S3 bucket names are global. Suggested shape:
  `hamstrack-backups-<aws-account-id>`.

### 6.2 Key layout

```
s3://<BACKUP_BUCKET>/daily/hamstrack-<ISO8601Z>.dump
s3://<BACKUP_BUCKET>/daily/hamstrack-<ISO8601Z>.globals.sql
s3://<BACKUP_BUCKET>/manual/<label>-<ISO8601Z>.dump          # e.g. manual/pre-hd188-squash-2026-09-02T101500Z.dump
```

Timestamps are UTC, basic ISO-8601 with no colons (`2026-08-27T031500Z`) so the key is safe in every
shell and filesystem. Lexicographic order **is** chronological order, so `aws s3 ls | tail -1` finds
the newest without parsing anything.

**`manual/` exists so that a backup someone deliberately took is not deleted by a rule written for
routine ones.** The *expiry* rule in §6.3 is scoped to `daily/` alone, and — the claim worth writing,
because it is the one that can silently stop being true — **no rule carrying
`NoncurrentVersionExpiration` matches `manual/`**. Not "no rule matches `manual/`": the
multipart-abort rule is declared `"Filter":{"Prefix":""}` and matches every key in the bucket, so the
looser phrasing is false already and was being used as a verification step (§17 criterion 11).
HD-188's pre-squash dump goes to `manual/` and stays until the owner removes it — which the instance
cannot do, because the instance role has no `s3:DeleteObject` anywhere, and since fix round 2 cannot
overwrite either (§7.2).

### 6.3 Bucket settings — decided values

| Setting | Value | Why |
|---|---|---|
| Public access block | all four **on** | same posture as the attachments bucket |
| Versioning | **Enabled** | a PUT that overwrites a good object with a truncated one is recoverable; combined with "the instance role cannot delete", the object history is beyond the reach of anything running on the box |
| Default encryption | **SSE-S3 (AES256)** | at-rest encryption with no key policy to misconfigure and no per-request KMS cost. Deliberately **not** SSE-KMS: a KMS key policy is one more thing that can silently break a restore *on the day you need it*, and it would also destroy the free integrity check in §6.5 (ETag ceases to be the body's MD5 under KMS). Revisit only if a compliance requirement demands a customer-managed key. |
| Bucket policy | **deny `s3:*` when `aws:SecureTransport` is false**, and **deny `s3:PutObject` anywhere in the bucket without `If-None-Match: *`** | the first closes plaintext access; the second makes every key write-once, which is what stops an overwrite-then-wait-for-the-lifecycle-rule destruction of the archive (§7.2) |
| Lifecycle: `daily/` | **expire after 30 days**; noncurrent versions after **30** | §6.4, and §7.3 for why the noncurrent window may not be shorter than the current one |
| Lifecycle: whole bucket | abort incomplete multipart uploads after 7 days | prevents paying for orphaned parts from failed uploads — note this rule **does** match `manual/`, which is why the property below is phrased about `NoncurrentVersionExpiration` and not about rules in general |
| Lifecycle: `manual/` | **no `Expiration` and no `NoncurrentVersionExpiration`** | deliberate; kept until deleted by hand |
| Object Lock | **No** — see §18 Q1 | it can only be enabled **at bucket creation**, so it is the one setting the owner must decide *before* running step 2 |

### 6.4 Retention: **30 days**, chosen

Not "as long as feels safe" — 30 days, for five reasons, each of which is a constraint rather than a
preference:

1. **It brackets the detection window for silent logical corruption.** The failures a dump rescues you
   from that a snapshot cannot are exactly the slow ones — a bad migration whose damage is noticed
   when someone opens a report, a bug that has been mangling one column since last week. On a product
   with a handful of users those are noticed in *weeks*, not days. Seven days would routinely be too
   short; 30 is past the point where "we would have noticed" becomes true.
2. **It comfortably outlives a release cycle.** The pre-upgrade backup of release N is still there
   during release N+1. (And a backup that must outlive that goes to `manual/`, which never expires.)
3. **Cost is not a consideration at this size and would not be at 100×.** This database is a few
   megabytes compressed; 30 daily copies is measured in cents per year. Retention was chosen on
   recovery value, not on price — and if the database ever reaches a gigabyte, 30 copies is ~30 GB,
   still under a euro a month in `eu-north-1` standard storage.
4. **Longer retention of production data is a liability, not an asset.** The dump contains every
   user's email address, display name and password hash. A GDPR erasure request has to reach backups
   eventually; "backups age out within 30 days" is a sentence you can put in a privacy policy and
   keep, and it means erasure requires no backup surgery at all. Indefinite retention would mean the
   opposite.
5. **A longer horizon, if ever wanted, is a second lifecycle rule on a `weekly/` prefix — not a bigger
   number here.** Say so now, so nobody "fixes" it by changing 30 to 365 and quietly triples the
   erasure problem.

Retention is enforced **by the S3 lifecycle rule** and never by the script (§3).

### 6.5 Integrity: verified end-to-end, for free

The script uploads with `aws s3api put-object --body <file>`, a **single** PUT (not multipart — the
dump is far below the 5 GB single-PUT ceiling). For a non-multipart PUT into an SSE-S3 bucket, the
returned **ETag is the MD5 of the object body**. The script computes the local MD5 before uploading
and compares.

**What a mismatch does is `BACKUP_S3_VERIFY_ETAG`, not a constant.** This section originally said
flatly that a mismatch fails the run; it does so only where the equality is actually guaranteed. On
AWS, ETag == MD5 for a single PUT into an SSE-S3 bucket. On an S3-compatible store it is not
guaranteed by anything, and a hard failure there would store the object correctly and then report the
run failed — every night, forever. So the setting is `auto` (default) | `on` | `off`: `auto` enforces
against real AWS and **downgrades to a warning whenever `BACKUP_S3_ENDPOINT` is set**, `on` enforces
everywhere, `off` skips the comparison. The authoritative description of the three values is the
comment on `BACKUP_S3_VERIFY_ETAG` in
[`ops/backup/backup.env.example`](../../ops/backup/backup.env.example) — including how to find out
whether your store returns the MD5, and what `warn` mode does and does not leave behind.

Even in `warn` mode this is a genuine end-to-end check where it fires — it proves the bytes S3 stored
are the bytes `pg_dump` produced —
and it needs **no** additional IAM permission, because the ETag comes back in the `put-object`
response rather than from a `HeadObject` (which would require `s3:GetObject` and thus break the
write-only property). It is also the second reason SSE-S3 beats SSE-KMS in §6.3.

If the dump ever exceeds the single-PUT ceiling the script must switch to multipart and this check
must be replaced with a `--checksum-algorithm SHA256` upload. The script logs the object size on every
run, so the day that becomes relevant is visible in advance rather than as a sudden failure.

---

## 7. Decision 4 — access (paste-ready IAM)

### 7.1 Instance role: a new inline policy `backups-s3` on `hamstrack-ec2`

An **additional** inline policy, added alongside the existing `attachments-s3`. Do not edit
`attachments-s3` — the two grants have different lifetimes and different justifications, and a single
merged policy is how a future reader loses track of which grant exists for what.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "WriteBackupsOnly",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:AbortMultipartUpload"],
      "Resource": [
        "arn:aws:s3:::<BACKUP_BUCKET>/daily/*",
        "arn:aws:s3:::<BACKUP_BUCKET>/manual/*"
      ]
    },
    {
      "Sid": "ListOwnPrefixesForVerification",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<BACKUP_BUCKET>",
      "Condition": {
        "StringLike": { "s3:prefix": ["daily/*", "manual/*"] }
      }
    }
  ]
}
```

**What is absent is the design.** No `s3:GetObject` — the instance cannot read back a single byte of
any backup, so owning the box does not hand over the archive. No `s3:DeleteObject` and no
`s3:PutBucketLifecycleConfiguration` — the instance cannot erase history or shorten retention. No
`s3:PutBucketPolicy`/`PutBucketVersioning` — it cannot weaken the bucket. `ListBucket` is kept only so
that the operator can verify from the box during install (`aws s3 ls s3://…/daily/`); it discloses key
names, which are timestamps, and nothing else. `AbortMultipartUpload` is present so that a switch to
multipart later does not silently leak paid-for orphaned parts.

### 7.2 Bucket policy: deny plaintext

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyInsecureTransport",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": [
        "arn:aws:s3:::<BACKUP_BUCKET>",
        "arn:aws:s3:::<BACKUP_BUCKET>/*"
      ],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } }
    },
    {
      "Sid": "DenyOverwriteOfBackups",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::<BACKUP_BUCKET>/*",
      "Condition": { "StringNotEquals": { "s3:if-none-match": "*" } }
    }
  ]
}
```

**`DenyOverwriteOfBackups` makes every key in the bucket write-once**, and it is the missing
half of §7.1: "may write, may not read, may not delete" still allowed *overwrite*, which
combined with the lifecycle rule (§7.3) destroys the archive using only granted permissions,
while every freshness metric stays green — they measure the local run and never the remote
state. The uploader always sends `If-None-Match: *`; a request without it is refused, so the
box can add tomorrow's backup and cannot touch yesterday's. **This requires AWS CLI ≥ 2.19**,
verified on the box before the timer is armed (an older CLI exits 252 on the unknown option).

**It covers `/*` since fix round 2, not `daily/*`.** `manual/` used to be exempt, on the
reasoning that no lifecycle rule matches that prefix so an overwrite would leave a noncurrent
version that never expires. The conclusion held; the premise did not —
`abort-incomplete-multipart-uploads` in §7.3 is declared `"Filter":{"Prefix":""}` and matches
every key. The real protection is narrower: **no rule carrying `NoncurrentVersionExpiration`
matches `manual/`** — and that is a property one ordinary edit away from being false, because
the attachments bucket hardened in `docs/ops-prod-hardening.md` §6.2 step 5 already uses
`Filter:{"Prefix":""}` + `NoncurrentVersionExpiration:{"NoncurrentDays":30}`, and copying that
here would re-arm the full attack against the one prefix nothing else guards. `manual/` is where
`docs/release-checklist.md` puts the pre-migration copy an incident depends on; fetching an
attacker's empty object mid-incident reads as a corrupt backup. The exemption also bought
nothing: every basename carries a second-precision timestamp, so a legitimate write never
collides with an existing key, and an owner who does want to replace a hand-taken object
deletes it first — they hold `DeleteObject`, the box does not. **Together with §7.3's 30-day
noncurrent window this is what replaces S3 Object Lock, which the owner has decided against**
(§6.3, §18 Q1).

### 7.3 Lifecycle configuration

```json
{
  "Rules": [
    {
      "ID": "expire-daily-after-30-days",
      "Status": "Enabled",
      "Filter": { "Prefix": "daily/" },
      "Expiration": { "Days": 30 },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 30 }
    },
    {
      "ID": "abort-incomplete-multipart-uploads",
      "Status": "Enabled",
      "Filter": { "Prefix": "" },
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
    }
  ]
}
```

**`manual/` is matched by no rule that expires anything.** The second rule *does* match it —
`"Filter":{"Prefix":""}` is every key — but it only aborts incomplete multipart uploads, which
deletes no completed object. State the property that way and keep stating it that way: "no rule
matches `manual/`" is both false and unfalsifiable-looking, and it is what a verification step was
written against (§17 criterion 11). The day a well-meaning `NoncurrentVersionExpiration` is added
bucket-wide, `manual/` loses its protection with nothing to announce it.

**`NoncurrentDays` is 30, revised up from the 7 this section first carried** (security review,
fix round 1). A noncurrent version is what survives an overwrite, so 7 days made the real
retention of anything overwritten one week rather than the thirty the archive advertises —
and it was the second half of a live destruction path: with `PutObject` + prefix `ListBucket`
and nothing else, list `daily/`, overwrite every key with an empty body, wait a week, and the
lifecycle rule deletes every good version for you. 30 days matches `Expiration.Days` and the
attachments bucket. The first half of that path is closed in §7.2.

### 7.4 DLM

`aws dlm create-default-role --resource-type snapshot` creates `AWSDataLifecycleManagerDefaultRole`
with the snapshot permissions DLM needs (verified against the AWS CLI reference; the console creates
the same role implicitly on first policy creation). The policy then targets the volume by tag; the commands are in
`docs/ops-prod-hardening.md` §6.2 step 4 — §16 is a table of pointers now, and lists DLM as step 4
there as well.

---

## 8. Decision 5 — how failure becomes visible

### 8.1 The metric, and why the textfile collector

node-exporter is already deployed (`prom/node-exporter:v1.9.0`), already scraped by Prometheus as job
`node`, and its **textfile collector** turns "a file on disk" into "a Prometheus metric" with no new
process, no new port, no new image and no new scrape target. For a job that runs outside any container
and once a day, that is exactly the right instrument: a push gateway would be a new service on a box
with 335 MB free, and instrumenting the app would be a lie — the app knows nothing about backups.

The script writes, atomically (`write to .tmp` → `mv` in the same directory, so a scrape never sees a
half-written file):

```
# HELP hamstrack_backup_last_success_timestamp_seconds Unix time of the last successful completion of a backup stage.
# TYPE hamstrack_backup_last_success_timestamp_seconds gauge
hamstrack_backup_last_success_timestamp_seconds{stage="dump"} 1756264500
hamstrack_backup_last_success_timestamp_seconds{stage="upload"} 1756264523
# HELP hamstrack_backup_last_status Whether the most recent run completed this stage (1) or not (0).
# TYPE hamstrack_backup_last_status gauge
hamstrack_backup_last_status{stage="dump"} 1
hamstrack_backup_last_status{stage="upload"} 1
# HELP hamstrack_backup_size_bytes Size in bytes of the dump produced by the most recent run.
# TYPE hamstrack_backup_size_bytes gauge
hamstrack_backup_size_bytes 4210233
# HELP hamstrack_backup_duration_seconds Wall-clock duration of the most recent run.
# TYPE hamstrack_backup_duration_seconds gauge
hamstrack_backup_duration_seconds 23
```

The `last_success_*` values come from small state files under `/var/lib/hamstrack-backup/`, so a
failed run **preserves** the previous success timestamps instead of clearing them — the difference
between "this run failed" and "nothing has succeeded since" is the whole point of §8.3.

**No tenant data, no unbounded labels.** The only label is `stage`, a two-valued enum — the same
cardinality rule `docs/observability.md` states for every other metric here.

### 8.2 Wiring the textfile collector — the compose change

```yaml
  node-exporter:
    image: prom/node-exporter:v1.9.0
    command:
      - --path.rootfs=/host
      # Backup freshness (HD-187) is published as a .prom file written by the host
      # systemd unit hamstrack-backup.service. The bind mount below uses the SAME path
      # inside and outside the container ON PURPOSE: node-exporter prefixes some paths
      # with --path.rootfs and this directory resolves to the same host directory under
      # either behaviour, so the flag cannot be subtly wrong.
      - --collector.textfile.directory=/var/lib/node_exporter/textfile_collector
    pid: host
    volumes:
      - /:/host:ro,rslave
      - /var/lib/node_exporter/textfile_collector:/var/lib/node_exporter/textfile_collector:ro
    mem_limit: 64m
    restart: unless-stopped
```

Two notes for the builder: the host directory must **exist before** the container starts, or Docker
creates it root-owned at mount time (harmless here, but the installer creates it explicitly anyway);
and a `command`/`volumes` change requires `docker compose … up -d node-exporter` to **recreate** the
container — a `restart` will not pick it up.

**The dev stack gets the same flag, pointed at a repo-relative directory** —
`docker-compose.observability.dev.yml`, `./observability/textfile` (gitkeep'd, `*.prom` gitignored).
Not symmetry for its own sake: that file mounts the *same* `observability/grafana/provisioning`
directory as prod, so both backup rules are provisioned into dev the moment they exist. Without a
textfile collector there they sit at noData→OK and cannot be made to fire even deliberately — the two
rules whose input comes from outside the application would be the only two nobody could test before
production. With it, dropping a hand-written `.prom` into that directory exercises them.

### 8.3 The two failures, and why they need two rules

**"The dump failed" and "the copy never left the box" are different failures, and only one of them is
loud.** A failed `pg_dump` returns non-zero within seconds and is obvious in the same run. A *succeeded*
dump whose upload failed — expired credentials, a typo'd bucket, an IAM change, a network partition,
an S3 outage — leaves a perfectly good file sitting on the volume that is about to be lost, and every
local check passes. That is the failure that quietly turns a backup system into a folder.

Hence the stage label, and hence two rules with two speeds. **Both live in
[`observability/grafana/provisioning/alerting/rules.yml`](../../observability/grafana/provisioning/alerting/rules.yml)**,
which is the copy Grafana provisions and therefore the copy that is true; the annotated
reasoning is in the comment above them there. What matters here is the shape:

| Rule | Query | Fires when | `for` | Severity |
|---|---|---|---|---|
| `BackupStale` | `time() - hamstrack_backup_last_success_timestamp_seconds` | `> 93600` (26 h), **per `stage`** | 15m | critical |
| `BackupRunFailed` | `hamstrack_backup_last_status` | `< 1`, per `stage` | 5m | warning |

**`BackupStale` is per-stage and is deliberately not scoped to `stage="upload"`** (revised in
fix round 1). That series is never written when the job runs with `BACKUP_TARGET=local` — the
mode §14 offers a self-hoster with no object store — so scoping to it left the entire DC
configuration with *no* rule that could see the timer stop, while `docs/self-hosting.md` told
that same reader both rules "start working with no extra configuration". Unaggregated, Grafana
raises one instance per stage and the annotation names which; on Cloud the only visible change
is that a fully stopped timer raises two instances instead of one.

**Threshold: 26 hours (93600 s).** The timer fires daily at 03:15 UTC with up to 10 minutes of
randomised delay; 26 hours is one full period plus ~1h50m of slack, so a single slow run, a reboot, or
a `Persistent=true` catch-up run does not page anybody, while two consecutive missed days cannot hide.
`for: 15m` guards against a scrape gap turning a step function into a spike. **Severity `critical`**,
matching AppDown/PostgresDown/DiskFilling: "there is no second copy of the data" belongs in the same
class as "there is no first copy".

**Both** backup rules are deliberately **unaggregated**, so Grafana raises one alert instance per
`stage` and each annotation names which one via `{{ $labels.stage }}`. Annotation templating is a
departure for this rule file; it is small, contained, and worth the specificity — an alert that says
"a backup stage failed" without saying which one sends its reader to the journal to find out.

Both rules route to the existing single email contact point fed by `OBS_ALERT_EMAIL_TO` — no new
contact point, no new notification policy.

### 8.4 What is deliberately **not** alerted

- **Dump *size* anomalies** (`hamstrack_backup_size_bytes` collapsing to a fraction of yesterday's).
  A real signal for silent data loss, but it needs a baseline this instance does not have yet, and a
  premature threshold on a growing dataset is an alert that cries wolf during normal growth. The gauge
  is published now so the baseline accumulates; the rule is a follow-up once there are weeks of data.
- **EBS snapshot failures.** DLM emits CloudWatch metrics, and wiring CloudWatch into this
  self-hosted Prometheus is a disproportionate amount of machinery for the *secondary* layer. Instead,
  §15's drill includes "confirm today's snapshot exists" as a checklist line. Named here so the gap is
  a decision and not an oversight.
- **`node_textfile_scrape_error`.** node-exporter sets it when a `.prom` file is malformed. Worth
  knowing about; documented in `docs/observability.md`'s troubleshooting rather than given a rule,
  because the atomic-write pattern makes a malformed file nearly impossible and a rule per
  near-impossibility is how a rule set becomes background noise.

---

## 9. The mechanism in detail

### 9.1 Files, and where they live

| Repository path (new) | Installed to | Mode |
|---|---|---|
| `ops/backup/hamstrack-backup.sh` | `/usr/local/bin/hamstrack-backup` | `0750 root:root` |
| `ops/backup/hamstrack-backup.service` | `/etc/systemd/system/` | `0644` |
| `ops/backup/hamstrack-backup.timer` | `/etc/systemd/system/` | `0644` |
| `ops/backup/backup.env.example` | `/etc/hamstrack/backup.env` | `0640 root:root` |
| `ops/backup/README.md` | — | points at this spec and at `docs/self-hosting.md#backups` |

**Configuration lives in `/etc/hamstrack/backup.env`, not in `/opt/hamstrack/.env`.** Three reasons:
`/opt/hamstrack/.env` is passed wholesale into the app container by `env_file:`, so backup settings
would end up in the application's environment for no reason; the script needs none of `.env`'s secrets
(it does not connect to Postgres — it `docker exec`s into the container and uses the container's own
`$POSTGRES_USER`/`$POSTGRES_DB`); and systemd's `EnvironmentFile=` parser is not Compose's, so sharing
one file couples two parsers to one syntax. The example file ships in the repo and
`.env.prod.example` gains a pointer comment to it (§14.2) — the reader looking for "where do I
configure backups" starts at `.env.prod.example` like everything else and is routed in one line.

### 9.2 Configuration reference

| Variable | Default | Meaning |
|---|---|---|
| `BACKUP_TARGET` | `s3` | Exactly `s3` or `local`; **anything else aborts the run**, because two independent equality tests let `S3` (or `s3` with a trailing CR) upload while never emitting the `upload` metric. `local` keeps copies in `BACKUP_LOCAL_DIR` and skips the upload stage entirely — the `upload` stage is then not emitted at all, which is why `BackupStale` is **per-stage** rather than scoped to `upload` (§8.3): in `local` mode the `dump` stage is what makes a stopped timer visible. |
| `BACKUP_S3_BUCKET` | *(required when `s3`)* | destination bucket |
| `BACKUP_S3_PREFIX` | `daily` | key prefix. The environment beats the config file, so `BACKUP_S3_PREFIX=manual /usr/local/bin/hamstrack-backup` really does write to `manual/` |
| `BACKUP_S3_REGION` | `eu-north-1` | always sent; must be right for a non-AWS store too (B2 `us-west-004`, MinIO usually `us-east-1`) — a mismatch is an opaque SigV4 rejection, not a message about regions |
| `BACKUP_S3_ENDPOINT` | *(empty)* | S3-compatible endpoint (MinIO, Backblaze B2, Wasabi) — passed as `--endpoint-url`. **This is what keeps the mechanism from being cloud-only.** Must be `https://`: an `http://` endpoint ships the database in cleartext and the bucket policy's `aws:SecureTransport` deny cannot help, because the request never reaches AWS. Setting it also exports `AWS_REQUEST_CHECKSUM_CALCULATION`/`AWS_RESPONSE_CHECKSUM_VALIDATION=when_required`, which CLI ≥ 2.23 otherwise sends and several stores reject |
| `BACKUP_S3_PATH_STYLE_ACCESS` | `false` | `https://host/bucket/key` instead of `https://bucket.host/key`; most MinIO deployments need it. Deliberately the application's own vocabulary (`app.storage.s3.path-style-access`). The AWS CLI takes it from a config file only, so the script writes a throwaway one — credentials must then come from the environment or `~/.aws/credentials` |
| `BACKUP_S3_VERIFY_ETAG` | `auto` | `auto` \| `on` \| `off`. ETag == body MD5 holds for a single PUT into an SSE-S3 bucket **on AWS** and nowhere guaranteed else, where a hard failure would store the object and report the run failed forever. `auto` enforces on AWS and warns behind an endpoint override. In `warn` mode a store that never returns the MD5 leaves **only a log line** — the run reports `upload 1` and no metric records that the bytes went unverified, and this unit's journal is not shipped to Loki. `backup.env.example` carries the one-command probe for deciding whether to set `on` |
| `BACKUP_LABEL` | *(empty)* | names one hand-taken backup apart from another: `<prefix>/<label>-<ISO8601Z>.dump` (§6.2). `A-Za-z0-9._-` only. A labelled staged file is exempt from the **count-based** sweep, for the same reason `manual/` is expired by no lifecycle rule — but not from the **age-based** one, because "deliberate" is not "forever" and an unbounded plaintext copy of the whole database on the root volume rides into every EBS snapshot |
| `BACKUP_COMPOSE_DIR` | `/opt/hamstrack` | where the compose file lives — and, unless `BACKUP_COMPOSE_PROJECT` is set, what the compose project name is derived from |
| `BACKUP_COMPOSE_FILE` | `docker-compose.prod.yml` | used only with `ps -q` to find the container; never with `up`, so `--remove-orphans` cannot be involved |
| `BACKUP_COMPOSE_PROJECT` | *(derived from `BACKUP_COMPOSE_DIR`)* | passed explicitly with `-f`, so an implicitly-loaded `docker-compose.override.yml` cannot redefine the service under us. A stack brought up with `-p something-else` is not found — loudly |
| `BACKUP_PG_SERVICE` | `postgres` | compose service name of the database. Resolving to more than one container is refused rather than guessed |
| `BACKUP_LOCAL_DIR` | `/var/backups/hamstrack` | staging directory (and destination when `BACKUP_TARGET=local`). Created `0700`; every file in it is `0600` |
| `BACKUP_KEEP_LOCAL` | `2` | how many previous staged copies survive the sweep, under `s3` only — in `local` mode these files are the backup rather than a copy of one, so nothing counts them and `BACKUP_KEEP_LOCAL_DAYS` alone decides how long they live. The sweep runs in **pre-flight**, not on the success path (§10 case 7), so the run then adds one more: expect `BACKUP_KEEP_LOCAL + 1` files of each kind on disk while a run is in flight |
| `BACKUP_KEEP_LOCAL_DAYS` | `30` | age bound on staged files, applied by **both** modes and scoped by **suffix** rather than by name: every `*.dump` and `*.globals.sql` older than this goes, whoever named it, because each is a plaintext copy of the whole database on the root volume that rides into every disk snapshot taken afterwards. Any other suffix is untouched, so a file an operator parked there is not collateral. Under `BACKUP_TARGET=local` it is the **whole** retention — that mode runs no count sweep at all — and under `s3` it is the upper bound behind one |
| `BACKUP_MIN_BYTES` | `50000` | sanity floor — a dump smaller than this is treated as a failed dump |
| `BACKUP_MIN_TOC_ENTRIES` | `50` | minimum restorable objects `pg_restore -l` must list. The `PGDMP` magic is five bytes at the front and says nothing about the rest; this reads the whole TOC, so an archive truncated at 90% fails here rather than during the restore that needed it |
| `BACKUP_MIN_FREE_MB` | `2048` | pre-flight free-space requirement |
| `BACKUP_TEXTFILE_DIR` | `/var/lib/node_exporter/textfile_collector` | where the `.prom` is written |
| `BACKUP_STATE_DIR` | `/var/lib/hamstrack-backup` | last-success state files |

**Credentials are configuration too, and they had no documented home.** On EC2 there is
nothing to set — the instance role arrives through IMDS. Everywhere else (MinIO, B2, Wasabi,
a laptop) the AWS CLI needs keys, and there are exactly two places for them: `AWS_ACCESS_KEY_ID`
/ `AWS_SECRET_ACCESS_KEY` in `/etc/hamstrack/backup.env` (which is `0640 root:root`, is
exported to the child process because the script sources with `set -a`, and is read by systemd
through `EnvironmentFile=`), or `~/.aws/credentials` (readable because the unit sets
`ProtectHome=read-only` rather than `yes`). Off EC2, also set `AWS_EC2_METADATA_DISABLED=true`
so every invocation does not first wait for `169.254.169.254` to time out. All three are in
`backup.env.example` and in the self-hosting destination table (§14).

### 9.3 The script and the units — the repository is the copy that runs

This section used to embed the whole of `hamstrack-backup.sh` and both unit files. It no
longer does, and that is a fix rather than a trim: fix round 1 changed both, and a
1500-line design document is where a second copy goes stale silently. The files that run
are the files in the repository:

| What | Where |
|---|---|
| the script | [`ops/backup/hamstrack-backup.sh`](../../ops/backup/hamstrack-backup.sh) |
| the unit | [`ops/backup/hamstrack-backup.service`](../../ops/backup/hamstrack-backup.service) |
| the timer | [`ops/backup/hamstrack-backup.timer`](../../ops/backup/hamstrack-backup.timer) |
| every setting, annotated | [`ops/backup/backup.env.example`](../../ops/backup/backup.env.example) |

**The shape, which is what this section was for.** One `flock`ed run at a time; a
pre-flight that checks its tools, sweeps old staged copies, checks free space and resolves
the postgres container by compose project + service name; a dump stage
(`pg_dump -Fc` + `pg_dumpall --globals-only` through `docker exec`, then a size floor, the
`PGDMP` magic and a `pg_restore -l` table-of-contents count); an upload stage (one
`s3api put-object` per file, write-once via `If-None-Match: *`, `--server-side-encryption
AES256`, ETag-vs-MD5 verification); and an `EXIT` trap that publishes the freshness metrics
whatever happened, backed by an `ExecStopPost=` that publishes them **only** when no trap
ran at all — the SIGKILL case. The trap's answer is the more specific one (it knows the dump
succeeded and only the upload failed), so where both could speak, the trap wins; the handler
recognises that by comparing a marker cleanup writes as its last act — the systemd
`$INVOCATION_ID`, which is the same string in `ExecStart=` and `ExecStopPost=` and different in
every other invocation — against its own. A timestamp cannot do this job: the run's recorded
start is written only by the lock winner, so it still holds the PREVIOUS run's value while this
one is in its prologue.

**What the shipped versions do that this document originally did not say**, each one a
review finding rather than a later idea — read the comments in the files for the reasoning,
which is where it now lives:

- the config file is sourced with `set -a` **and** the environment wins over it, so
  `BACKUP_S3_PREFIX=manual …` on the command line is not silently overwritten by the file —
  and it is *run in a child bash first*, so a file that bash cannot parse **or cannot run**
  ends the job in a refusal rather than in a backup taken under defaults (round 4);
- `BACKUP_TARGET` is validated once and both branches derive from it, instead of two
  non-complementary equality tests that let `S3` or `s3\r` upload while never reporting it;
- `umask 077`, `install -d -m 0700`, and one deliberate exception (the `.prom` is `0644`,
  because node-exporter runs as `nobody` and a metric it cannot read is an alert that never
  fires);
- the staged-copy sweep runs in **pre-flight**, not on the success path, so a full disk
  cannot wedge the mechanism permanently;
- `BACKUP_S3_ENDPOINT` must be `https://`, and setting it also relaxes the AWS CLI's
  default request checksums, which several S3-compatible stores reject;
- `BACKUP_S3_PATH_STYLE_ACCESS` (the application's own vocabulary),
  `BACKUP_S3_VERIFY_ETAG=auto|on|off`, `BACKUP_LABEL`, `BACKUP_COMPOSE_PROJECT` and
  `BACKUP_MIN_TOC_ENTRIES` exist — see §9.2;
- the unit runs `ProtectSystem=strict` + `ReadWritePaths=`, `ProtectHome=read-only` (not
  `yes`, which hides `/root/.aws` from every self-hoster with keys), `UMask=0077`,
  `LimitFSIZE=`, `TimeoutStartSec=1800`, and memory limits sized for the AWS CLI rather
  than for a `pg_dump` that never runs in this cgroup.

**And what fix round 2 changed, all of it in the ordering rather than in the steps:**

- the `--stop-post` branch now runs **before** any validation, so a bad value in
  `backup.env` — or an `install -d` that fails because `BACKUP_LOCAL_DIR` was repointed
  without updating `ReadWritePaths=` — publishes a failure instead of dying twice at the
  same line and leaving yesterday's `last_status 1` standing;
- and it stands **down** when the run's `EXIT` trap already published, so
  `ExecStopPost=`'s coarse `dump=0 upload=0 size=0` can no longer erase the trap's
  `dump=1 upload=0 size=<real>` — the one asymmetry the two-stage metric exists to show;
- `trap cleanup EXIT` moved above the `install -d` calls **and** the validation block — the
  directories matter as much as the ten `die`s, because a hand run that trips the
  `ReadWritePaths=` footgun has no `ExecStopPost=` behind it to speak for it; `need_tool`
  above the `flock` block (a missing `flock` exits 127, which the loser branch read as
  "someone else holds it" and reported as success), and the orphan `.prom.<pid>` sweep below
  it (from outside the lock it could delete a live temp file);
- `trap 'exit 143' TERM`, because bash runs no `EXIT` trap for an untrapped fatal signal
  and `TimeoutStartSec=`'s SIGTERM therefore skipped `cleanup` entirely;
- the staged-copy sweep gained an **age** bound scoped by file SUFFIX rather than by name, so
  nothing it writes outlives `BACKUP_KEEP_LOCAL_DAYS` whoever named it — which is what lets a
  labelled file be exempt from the count sweep without being kept forever (fix round 3 gave
  the `local` branch the same predicate: there it is the whole retention);
- `read_state` rejects a *future* timestamp as well as a non-numeric one — `time() - ts`
  hugely negative silences `BackupStale` permanently;
- the config round-trip is a NUL-delimited `env -0` snapshot replayed with `export`
  instead of a line-based `grep` replayed with `eval`.

**And what fix round 3 changed**, all three of them subclasses that had slipped between the
round-2 fixes rather than new ideas:

- **the config file no longer aborts the prologue when it does not PARSE.** `. "$CONF"` under
  `set -e` returns 2 on an unbalanced quote or a `BACKUP_LABEL=pre-release(1)`, which killed
  `ExecStart` above both the `--stop-post` branch and the trap — and then killed
  `ExecStopPost` at the identical line, publishing nothing at all. That is the shape round 2
  existed to remove, surviving in the one subclass that could never reach either block, and
  the likeliest one in the field: this file is hand-edited on instruction, and systemd's own
  `EnvironmentFile=` parser accepts files bash cannot. The source now tests its return value,
  degrades to defaults, and the run `die`s in the validation block with the trap installed —
  loudly, because a backup taken with default settings looks like the one that was asked for;
- **the `local` branch of the staged sweep got the same suffix-scoped age bound as `s3`.**
  With `-name 'hamstrack-*'` a labelled dump was matched by nothing in that mode and stayed
  on disk forever, while `backup.env.example` called `BACKUP_KEEP_LOCAL_DAYS` the retention.
  `local` is the self-hoster's path, so it is where a permanent plaintext copy of the whole
  database costs the most;
- **the run marker holds the systemd `$INVOCATION_ID` instead of a timestamp.** `run_start` is
  written by the lock winner, so both files held the previous run's value throughout a run's
  prologue and compared EQUAL: a SIGKILL before validation made `ExecStopPost=` stand down
  over a trap that had never run, leaving the previous success standing. The same change ends
  the fabricated duration a stood-down handler used to publish (`0s` now; `19s` measured for a
  0-second run, and ~86400 on a daily timer).

**And what fix round 4 changed** — one defect, and it is the round-3 fix above only half done:

- **a `backup.env` that PARSES and then FAILS is now caught too, by executing it in a child
  bash before sourcing it.** `set -e` is suppressed for the whole dynamic extent of the left
  operand of `||`, and that suppression propagates into the sourced file — so
  `. "$CONF" || CONF_BROKEN=1` catches a file bash cannot parse and *not* a line such as
  `BACKUP_S3_PREFIX=$(lookup-prefix)` that parses and then exits non-zero. The source ran on to
  EOF and returned the status of its LAST command, so unless the bad line happened to be the
  last one the run continued with that variable degraded to its default and **reported
  success**: `dump=1 upload=1`, the object in `daily/`, deleted by the 30-day lifecycle rule —
  and `daily/` is exactly where the pre-migration copy §16 step 10 tells the operator to take
  must not land. Measured on AL2023's bash: of the three obvious forms only a separate PROCESS
  catches it (`bash -c 'set -e; . "$1"' _ "$CONF"` → 1), because a plain `( set -e; . "$CONF" )`
  subshell inherits the suppression and returns 0 like the `||` form. The probe cannot *apply*
  the file (its variables die with it), so the file is executed twice on the happy path —
  harmless for the plain `KEY=value` lines this file is allowed to contain, and no new
  exposure, since sourcing already runs it as root. **A failed probe is an unconditional
  refusal**, with no "but the environment looks complete" branch: under systemd that
  environment IS the same file as read by `EnvironmentFile=`, whose parser expands nothing and
  hands the script the literal text `$(lookup-prefix)` (verified on systemd 252), and the
  environment-wins replay puts those values back *after* the source. One missed nightly upload
  costs an alert within the hour; a backup taken under settings nobody wrote costs the restore.

**Why 03:15 UTC:** the lowest-traffic hour available (05:15 CET), and offset from the DLM snapshot
window (`docs/ops-prod-hardening.md` §6.2 step 4 uses 04:30 UTC) so the two never contend for the same volume I/O.

---

## 10. Edge cases & failure modes

| # | Case | Behaviour |
|---|---|---|
| 1 | **Dump succeeds, upload fails** | `stage="dump"` metric fresh, `stage="upload"` stale and `last_status{stage="upload"}=0`, `hamstrack_backup_size_bytes` the real size, the dump kept on disk. `BackupRunFailed` fires within ~1 h; `BackupStale` fires at 26 h. **This is the failure the two-stage metric exists for** (§8.3) — and the reason `ExecStopPost=` stands down when the `EXIT` trap has already spoken (case 8): the handler fires on any non-success result, so before fix round 2 it rewrote this row as `dump=0, upload=0, size=0` milliseconds after the trap got it right, sending the operator to look for a dump that was sitting on disk. |
| 2 | **The postgres container is stopped, renamed, or the compose project moved** | `docker compose ps -q` returns empty; the run exits 1 before writing any file; both stages report 0. Note the container is located by **compose service name**, never by a hardcoded container name, so `hamstrack-postgres-1` becoming `hamstrack_postgres_1` breaks nothing. |
| 3 | **`pg_dump` fails mid-stream** | `set -o pipefail` + non-zero exit; the partial file is **never uploaded**, because the size, magic-byte and TOC checks all run before the upload stage — and it is deleted by the failing run itself rather than left for a later one (revised in fix round 1: a rejected dump is bytes nobody may restore from and nobody should be able to read either). A dump that succeeded and merely failed to *upload* is kept, because it is a good backup. |
| 4 | **`pg_dump` "succeeds" but produces something tiny or non-Postgres** (e.g. the container printed an error to stdout) | `BACKUP_MIN_BYTES` floor + the `PGDMP` magic check reject it as a dump failure. A dump truncated *late* passes both — five bytes at the front say nothing about the rest — so `pg_restore -l` must also list at least `BACKUP_MIN_TOC_ENTRIES` restorable objects. |
| 5 | **The upload stores corrupted bytes** | ETag ≠ local MD5 → what happens is `BACKUP_S3_VERIFY_ETAG` (§6.5), because the equality is an AWS guarantee and not a universal one. Default `auto`: against real AWS the run **fails at the upload stage**; behind a `BACKUP_S3_ENDPOINT` it logs a `WARN` and the run succeeds, since a store that never returns the body MD5 would otherwise fail every night forever. `on` fails everywhere, `off` compares nothing. |
| 6 | **Two runs overlap** (operator runs it by hand while the timer fires) | `flock -n`; the loser logs and exits 0 **without touching the metrics**, so it cannot forge freshness. A permanently stuck lock therefore still surfaces as staleness. The tool check runs **above** the lock so that a host with no `flock` installed cannot take this branch on every invocation: `if ! flock -n 9` cannot tell flock's `1` from the shell's `127`, and the silent-success-forever that produced is a worse failure than the one the branch is for. |
| 7 | **Disk fills** | Pre-flight `BACKUP_MIN_FREE_MB` check fails the run *before* writing anything, so the backup job can never be the thing that fills the volume the database is on. The existing `DiskFilling` alert covers the general case. **The staged-copy sweep runs in pre-flight, ahead of that check** — sweeping only on the successful-upload path wedges the mechanism permanently: past the threshold the run aborts before it can prune, so it can never again reach the code that would free the space. Under `s3` the sweep has **two** halves: a count-based one matching `hamstrack-*`, and an age-based one that ignores the name and matches by **suffix** (`*.dump`, `*.globals.sql`). Without the second, a labelled file (`pre-hd188-squash-<TS>.dump`, prescribed by `docs/release-checklist.md`) is matched by neither and stays on the root volume forever, plaintext, riding into all seven EBS snapshots. The `local` branch runs the age half on the same predicate — fix round 3; with `-name 'hamstrack-*'` a labelled dump was unbounded in the very mode where that value is the whole retention. |
| 8 | **The run exceeds `MemoryMax`, or is SIGKILLed for any other reason** | The cgroup kills the run, not the app. **Corrected in fix round 1:** SIGKILL runs no `EXIT` trap, so the `.prom` would keep the *previous* run's `last_status 1` — not stale, confidently wrong — and `BackupRunFailed` would stay silent while `BackupStale` waited 26 hours. `ExecStopPost=/usr/local/bin/hamstrack-backup --stop-post` stamps both stages as failed, preserving the `last_success_*` state files. **Refined in fix round 2, twice.** It runs *before* the config validation, so the ten `die`s down there (and the `install -d` that fails on a repointed `BACKUP_LOCAL_DIR`, and — since fix round 3 — a `backup.env` that does not parse, or, since round 4, one that parses and then fails to run, which used to abort the prologue above this block entirely) no longer kill ExecStart and ExecStopPost at the identical line, leaving nothing written at all. And it stamps **only when no trap ran** — not "whenever `SERVICE_RESULT` is not `success`", which also covered every ordinary `exit 1` and destroyed case 1's answer. `cleanup` writes a marker as its last act and the handler stands down when it matches; **fix round 3** made that marker the systemd `$INVOCATION_ID` rather than the run's start timestamp, because `run_start` is written by the lock winner and both files therefore still held the PREVIOUS run's value — matching each other — throughout a run's prologue, so a SIGKILL before validation stood the handler down over a trap that had never run. The same change removed the duration a stood-down handler used to publish, which was measured from the previous run (~86400 on a daily timer). Note the cgroup bounds bash + the docker CLI + the AWS CLI, **not** `pg_dump`, which `docker exec` runs inside the postgres container's own cgroup. |
| 9 | **The instance is down at 03:15** | `Persistent=true` runs it at the next boot; 26 h absorbs it. |
| 10 | **IMDS credentials unavailable / IAM policy removed** | `aws s3api put-object` fails → upload stage 0 → case 1. |
| 11 | **The bucket's lifecycle rule ages out the only good copy while recent ones are silently corrupt** | Not preventable by retention. This is what the restore drill (§15) exists for, and it is why the drill's cadence is a decision (§18 Q3) rather than an afterthought. |
| 12 | **A GDPR erasure request touches data present in backups** | Backups age out in 30 days with no manual surgery. `manual/` objects are the exception and must be reviewed by hand if one is retained across such a request — noted in §16 step 10, which is the step that takes one. |
| 13 | **Restoring an old dump into a *newer* application** | Flyway applies the missing migrations on boot, which is correct — and the drill (§15) is where this is observed deliberately rather than discovered during an incident. Restoring into an **older** app is the already-documented `docs/self-hosting.md` troubleshooting row: schema validation fails at startup. |
| 14 | **Clock skew on the host** | All timestamps are `date +%s` / UTC; a host whose clock is wrong by more than 26 h would produce false alerts. AL2023 runs `chronyd` against the Amazon time service by default; not defended against further. |
| 15 | **The `.prom` file is deleted or the node-exporter mount is lost** | The series disappears and `noDataState: OK` keeps both rules silent. Known, accepted (§8.3), and checked by the drill. |
| 16 | **The dump exceeds the 5 GB single-PUT ceiling** | Fails the upload with an S3 error. `hamstrack_backup_size_bytes` makes the approach visible years in advance; the remedy is multipart + `--checksum-algorithm SHA256` (§6.5). |
| 17 | **A concurrent long-running migration during the dump window** | `pg_dump` takes an MVCC snapshot and does not block DDL-free work; a migration holding an `ACCESS EXCLUSIVE` lock can block `pg_dump`'s table lock acquisition, which would show as a long-running run. Deploys are not scheduled at 03:15; if this ever bites, the remedy is to move the timer, not to weaken the dump. |

Explicitly **not** applicable, because this ticket touches no application data path: optimistic
locking / `@Version`, soft-delete and archived-entity semantics, in-use-on-delete remap-vs-409,
stranded-issue guards, idempotency of API writes.

---

## 11. Data model impact

**None.** No table, no column, no Flyway migration, no entity. `migration-reviewer` has nothing to
review for this ticket, and that is worth saying out loud so its silence is a finding rather than an
omission.

The only relationship to the schema is inverse: this ticket exists so that **HD-188's** rewrite of
`flyway_schema_history` has a proven fallback, and §15's drill produces the scratch database HD-188's
parity proof consumes.

---

## 12. API surface

**None.** No endpoint, no DTO, no status code, no change to `openapi.yaml` or `docs/api-*.md`.
`api-docs-sync` is **n/a** for this ticket.

An admin-visible "last backup" indicator was considered and rejected: it would require the application
to know about an operational job it does not run, in a product where the same binary ships to
self-hosters who may back up by entirely different means. The freshness signal belongs where it is —
in the operator's monitoring.

---

## 13. Frontend impact

**None.** No page, no component, no store, no `DESIGN.md` decision. `frontend-builder` is not needed
for this ticket.

---

## 14. DC vs Cloud

**The Cloud instance gets the mechanism; self-hosters get documentation plus a script that genuinely
works on their infrastructure.** Neither half is application behaviour, so **nothing here is
profile-gated and nothing touches `application.properties`** — there is no `dc`/`cloud` branch to
create, because the application is not involved at all. That is the cleanest possible answer to the
single-codebase rule: no fork, because no code.

The one rule that *does* bite is "no cloud-only assumption without a self-hosted path", and it is
satisfied by two variables rather than by a second script:

- `BACKUP_S3_ENDPOINT` makes the upload work against **MinIO, Backblaze B2, Wasabi** or any
  S3-compatible store, which is what a self-hoster with no AWS account actually has.
- `BACKUP_TARGET=local` makes the whole thing work with **no object store at all** — dump, verify,
  rotate on a local (ideally separate) disk. Degraded, honest, and documented as degraded: a copy on
  the same machine does not survive the machine.

### 14.1 What each mode gets

| | Cloud (hosted) | DC (self-hosted) |
|---|---|---|
| Timer installed | **Yes**, by the one-time operator step (§16) | Optional; the guide shows how |
| Destination | the dedicated S3 backup bucket | anything: S3, an S3-compatible endpoint, or a local dir |
| Retention | S3 lifecycle, 30 days | operator's choice; `BACKUP_KEEP_LOCAL_DAYS` for the local target |
| EBS snapshots | Yes, DLM, 7 days | n/a (their hypervisor/host equivalent, mentioned in the guide) |
| Alerts | Yes, provisioned, email via `OBS_ALERT_EMAIL_TO` | the rules ship in the same file and stay dormant unless the metric exists (§8.3) |
| Restore drill | quarterly + before any migration that rewrites `flyway_schema_history` | recommended before every upgrade |

### 14.2 Wiring checklist (`dc-cloud-guard`'s list)

| Target | Change |
|---|---|
| `src/main/resources/application.properties` | **No change.** No application configurable is introduced. Recorded explicitly because silence here is otherwise indistinguishable from an omission. |
| `application-dc.properties` / `application-cloud.properties` | **No change.** |
| `docker-compose.prod.yml` | **No change.** The backup runs on the host, not in the compose project. |
| `docker-compose.observability.yml` | **Changed** — node-exporter gains `--collector.textfile.directory=…` and the matching bind mount (§8.2). |
| `docker-compose.observability.dev.yml` | **Changed** — the same flag and a **repo-relative** bind mount (`./observability/textfile`). Dev provisions the same Grafana rules as prod, so without this the two backup rules would be the only ones nobody can exercise before they ship — they would sit at `noData → OK` and could not be made to fire even deliberately. The path deliberately differs from prod's `/var/lib/node_exporter/…`, which does not exist on a Docker Desktop/WSL2 box. |
| `observability/textfile/` | **New** — an empty tracked directory (`.gitkeep`) that exists only because the dev bind mount needs a source. |
| `.gitignore` | **Changed** — `observability/textfile/*.prom`: the hand-written samples used to exercise those rules are throwaway, the directory is not. |
| `.gitattributes` | **Changed** — `*.sh`, `*.service`, `*.timer`, `*.env.example` and `.env.prod.example` pinned to `eol=lf`. A CRLF checkout makes the shebang unreadable (`bad interpreter: /usr/bin/env bash^M`) and systemd reject the units; for the env templates nothing rejects anything at all — the CR just becomes part of the last value on the line, which is why `BACKUP_TARGET` is validated as an exact match. |
| `observability/grafana/provisioning/alerting/rules.yml` | **Changed** — `BackupStale` + `BackupRunFailed` (§8.3). |
| `ops/backup/*` | **New** — script, two units, `backup.env.example`, README (§9.1). |
| `.env.prod.example` | **Changed** — a pointer comment only, in a new `── Backups ──` block: backups are configured in `/etc/hamstrack/backup.env`, not here, and here is why. No variable is added to this file. |
| `docs/self-hosting.md` | **Changed** — the `## Backups` section is rewritten: keep the manual `pg_dump`/`psql` recipe, add the scheduled script (with `BACKUP_S3_ENDPOINT` and `BACKUP_TARGET=local`), add "restore it once, or you do not have a backup", add the restore-verification steps, and add the attachments-versioning advice for `STORAGE_TYPE=s3`. |
| `docs/observability.md` | **Changed** — four metric rows in the custom-metric reference, two rows in the alert table, the "textfile collector" note in Architecture, and a troubleshooting line for `node_textfile_scrape_error`. |
| `docs/ops-prod-hardening.md` | **Changed** — new **§6 Backups**: what runs where, the AWS-side commands from §16, the restore drill from §15, and the **restore drill log table**. Also correct §3's stale statement that config auto-syncs (or leave it to HD-122 if that ships first — do not fix it twice). |
| `README` | **No change.** It enumerates no operational procedures. |
| `docs/release-checklist.md` | **Changed, one line** — "take a `manual/` backup before any migration that rewrites `flyway_schema_history`". |

---

## 15. The restore drill

**A backup nobody has restored is a belief.** This section is the procedure that converts it, and its
output is HD-188's step-3 evidence.

### 15.1 Where it runs — and where it cannot

**Not on the production box.** 341 MB available and only a 1 GB emergency swapfile (measured
2026-08-28; see `docs/ops-prod-hardening.md` §5) — a second Postgres plus a second JVM is not a
tight fit, it is an outage. Swap does not change that: it converts the kill into a box too slow to
serve anybody, which is not an improvement while it is production. **Not in CloudShell** either: 1 GB of RAM, no Docker daemon.

**It runs on the owner's development machine**, which already has Docker, the Maven wrapper, a
Postgres image and the repository. The drill uses a **throwaway container on port 15433**, so the
existing `hamstrack-postgres` dev database on 15432 is untouched and nothing needs to be cleaned up
afterwards except one `docker rm`.

### 15.2 Procedure (PowerShell — the owner's shell)

```powershell
# 0. Note the production row counts BEFORE restoring, so the comparison in step 5 is honest.
#    Grafana already publishes them (Product dashboard / Explore → Prometheus):
#      hamstrack_users_total, hamstrack_workspaces_total,
#      hamstrack_projects_total, hamstrack_issues_total
#    Read them at roughly the timestamp of the dump you are about to restore.
#    Also note the version prod reports:  curl https://hamstrack.com/api/meta

# 1. Fetch the newest daily dump (OWNER credentials — the instance role cannot read these).
$B = "<BACKUP_BUCKET>"
aws s3 ls "s3://$B/daily/" --region eu-north-1 | Select-Object -Last 6
mkdir -Force .\restore-drill | Out-Null
aws s3 cp "s3://$B/daily/hamstrack-<TS>.dump"        .\restore-drill\ --region eu-north-1
aws s3 cp "s3://$B/daily/hamstrack-<TS>.globals.sql" .\restore-drill\ --region eu-north-1

# 2. A throwaway PostgreSQL, same major version as production (16), on a spare port.
docker run -d --name hamstrack-restore-drill `
  -e POSTGRES_USER=hamstrack -e POSTGRES_PASSWORD=hamstrack -e POSTGRES_DB=hamstrack `
  -p 15433:5432 postgres:16-alpine

# 3. Restore. --exit-on-error is the point: a restore that reports errors and keeps going
#    is how a half-restored database gets mistaken for a good one.
docker cp .\restore-drill\hamstrack-<TS>.dump hamstrack-restore-drill:/tmp/d.dump
docker exec hamstrack-restore-drill `
  pg_restore -U hamstrack -d hamstrack --no-owner --no-privileges --exit-on-error -v /tmp/d.dump

# 4. Globals (roles). Expect "role hamstrack already exists" — that one is benign. What
#    follows it is not: `pg_dumpall --globals-only` emits `ALTER ROLE hamstrack ... PASSWORD
#    'SCRAM-SHA-256$...'` and that statement SUCCEEDS, replacing the scratch container's
#    password with production's.
docker cp .\restore-drill\hamstrack-<TS>.globals.sql hamstrack-restore-drill:/tmp/g.sql
docker exec hamstrack-restore-drill psql -U hamstrack -d hamstrack -f /tmp/g.sql

# 4b. Put the drill password back. Not redundant: `docker exec` goes over the trusted local
#     socket, so step 5 passes regardless — it is step 6, the one that proves the restore is
#     VALID and not merely present, that breaks, because the app authenticates over TCP.
docker exec hamstrack-restore-drill psql -U hamstrack -d postgres `
  -c "ALTER ROLE hamstrack PASSWORD 'drill-only-password';"

# 5. Content check — compare with the numbers noted in step 0.
docker exec hamstrack-restore-drill psql -U hamstrack -d hamstrack `
  -c "select count(*) users from users" `
  -c "select count(*) workspaces from workspaces" `
  -c "select count(*) projects from projects" `
  -c "select count(*) issues from issues" `
  -c "select count(*) migrations, max(version) latest, bool_and(success) all_ok from flyway_schema_history"

# 6. Boot the application against the restored copy under ddl-auto=validate.
#    Check out the tag production is running (step 0) — a NEWER working tree would apply
#    migrations to the drill database, which is a different (also useful) experiment.
#    --spring.docker.compose.enabled=false is mandatory: without it spring-boot-docker-compose
#    starts/attaches the compose Postgres and silently ignores DB_URL (CLAUDE.md).
$env:DB_URL="jdbc:postgresql://localhost:15433/hamstrack"
$env:DB_USERNAME="hamstrack"; $env:DB_PASSWORD="hamstrack"
$env:JWT_SECRET="drill-only-secret-0123456789abcdef0123456789abcdef"
$env:SEED_ADMIN_EMAIL=""      # do not seed an admin into a restored production database
.\mvnw.cmd spring-boot:run --% -Dfrontend.skip=true -Dspring-boot.run.arguments="--spring.docker.compose.enabled=false --server.port=8081"

# 7. Prove it is serving the restored data.
curl http://localhost:8081/api/meta

# 8. Tear down. The dump files contain production PII — delete them from the laptop.
docker rm -f hamstrack-restore-drill
Remove-Item -Recurse -Force .\restore-drill
```

### 15.3 What counts as a pass

All five, and a run that misses any of them is a failed drill and a ticket:

1. `pg_restore --exit-on-error` completed with exit code 0.
2. The row counts match the production gauges from step 0 (allowing for activity between the dump and
   the reading).
3. `flyway_schema_history` is present, `all_ok` is true, and `latest` matches the version the
   application expects.
4. The application **started** — meaning Flyway validated the restored history and Hibernate's
   `ddl-auto=validate` matched every entity against the restored schema. This is the bar: a schema
   that restores but does not validate is not a restore.
5. `/api/meta` answered.

Also, while you are there: confirm today's EBS snapshot exists
(`aws ec2 describe-snapshots --owner-ids self --filters Name=tag:Name,Values=hamstrack-* --query 'Snapshots[].[StartTime,SnapshotId]' --output table`)
and confirm `hamstrack_backup_last_success_timestamp_seconds` is present in Prometheus — that is the
check that catches the silent-metric hole in §8.3 case 15.

### 15.4 Recording it — the part that is usually skipped

A new table in `docs/ops-prod-hardening.md` §6, appended to on every drill, in the **past tense with a
date**:

| Date | Object restored | Restored counts (users / ws / projects / issues) | Flyway version | App version booted | Elapsed | Operator | Notes |
|---|---|---|---|---|---|---|---|
| _(first row written by the drill that closes this ticket)_ | | | | | | | |

**The `Elapsed` column is the RTO**, measured rather than claimed — from "I decided to restore" to
"the application answered". A one-line summary of the most recent drill also goes into
`docs/self-hosting.md#backups` as the project's own worked example.

### 15.5 Cadence

- **Quarterly**, as a calendar item.
- **Before any migration that rewrites `flyway_schema_history`** — i.e. **before HD-188**, which is
  the immediate occasion. That drill's scratch database is exactly what HD-188 needs to prove
  squash-vs-chain schema parity, so the two are one piece of work done once.
- **After any change to the backup script, the bucket, the IAM policy or the Postgres major version.**

---

## 16. What the operator must do by hand, once

Everything here needs credentials that do not exist on the dev machine or on the instance. Run it in
one sitting, in this order.

> **Decide §18 Q1 (S3 Object Lock) before the bucket is created** — it is the one setting that can
> only be enabled at creation time. (It is now decided: **no** — see §6.3 and
> `docs/ops-prod-hardening.md` §6.2.)

**The commands live in one place, and it is not this document.**
[`docs/ops-prod-hardening.md` §6.2 and §6.3](../ops-prod-hardening.md#6-backups) are the
copy-pasteable runbook: bucket creation, the bucket policy, the lifecycle configuration, the instance
role, DLM, the attachments bucket, and the SSM install. This section used to carry a second copy of
all of it, and the second copy is what a fix round updates last: after round 1 hardened the bucket
policy and the noncurrent-version window, §16 was still handing the operator a `put-bucket-policy`
with no `DenyOverwrite…` statement and a lifecycle with `NoncurrentDays: 7` — the exact pair this
document spends §7.2 and §7.3 explaining is a live archive-destruction path. Fixing the copy in place
would only have set up the third divergence, so the copy is gone.

| Step | Where the commands are |
|---|---|
| 0. Variables (`REGION`, `INSTANCE_ID`, `ACCOUNT_ID`, `BACKUP_BUCKET`, `ATTACH_BUCKET`) | `docs/ops-prod-hardening.md` §6.2 step 0 |
| 1. Create the backup bucket — private, versioned, SSE-S3, HTTPS-only, **write-once** | §6.2 step 1 (design: §6.1, §6.3, §7.2) |
| 2. Lifecycle — 30 days on `daily/`, noncurrent 30, nothing that expires `manual/` | §6.2 step 2 (design: §6.4, §7.3) |
| 3. Instance role `backups-s3` — write-only | §6.2 step 3 (design: §7.1) |
| 4. Tag the volume, create the DLM policy (7 snapshots, 04:30 UTC) | §6.2 step 4 (design: §5.2) |
| 5. Harden the attachments bucket | §6.2 step 5 (design: §5.3) |
| 6. Put the script, the units and `backup.env` on the box (one SSM command) | §6.3 (design: §9.1) |
| 7. Configure, check the AWS CLI version, arm the alarm, recreate node-exporter, run it once, measure the memory ceiling, then enable the timer | §6.3 steps a–g |

> Step 6 also copies `docker-compose.observability.yml` and `observability/` to the box, because
> nothing ships them: the deploy pipeline runs `pull` + `up -d` and syncs no configuration (§4.4 —
> HD-122 is the ticket). **Do it after the last compose change of release 0.18.0** (HD-180, HD-189),
> or you will do it twice. If HD-122 lands first, drop those two lines.

Then verify the durability properties rather than assume them — `docs/ops-prod-hardening.md` §6.4 is
a list of commands whose *refusal* is the point, plus the two-stage asymmetry check (§10 case 1),
which must be run **through `systemctl start`** and not only by hand.

**8. Set the alert destination if it is not already set** — `OBS_ALERT_EMAIL_TO` in
`/opt/hamstrack/.env`, then `docker compose … up -d grafana`. A provisioned rule with nowhere to send
is a rule that fires into a dashboard nobody is looking at.

**9. Run the restore drill (§15) and write the first row of the log table.** Until this is done the
ticket is not finished and HD-188 is not unblocked.

**10. Retain a `manual/` copy before HD-188:**

```bash
sudo BACKUP_S3_PREFIX=manual BACKUP_LABEL=pre-hd188-squash /usr/local/bin/hamstrack-backup
# then verify:  aws s3 ls s3://$BACKUP_BUCKET/manual/     # $BACKUP_BUCKET from §6.2 step 0
```

The label is what tells this copy from the next one, and the key is write-once like every other
(§7.2) — so replacing it means deleting it first, with owner credentials. The staged local copy it
leaves behind is kept by the count-based sweep and bounded by `BACKUP_KEEP_LOCAL_DAYS`; it is a
plaintext copy of the whole database on the root volume, so do not raise that bound to keep it.

---

## 17. Acceptance criteria

Phrased so that a reviewer can tell a mechanism from a belief.

**The mechanism exists and runs**

1. `systemctl list-timers hamstrack-backup.timer` shows a next elapse; `systemctl status
   hamstrack-backup.service` shows a successful last run.
2. `aws s3 ls s3://<BACKUP_BUCKET>/daily/` lists a `.dump` **and** a `.globals.sql` for the same
   timestamp, dated today.
3. Running the script twice concurrently produces one backup and one "another run holds the lock"
   log line, and the metrics are untouched by the loser.

**It is bounded and cannot hurt the box**

4. `systemctl show hamstrack-backup.service -p MemoryMax` reports what the unit declares (512 MB as
   shipped — raised from 256 MB in fix round 1, because the cgroup bounds the AWS CLI, a PyInstaller
   bundle with a 120–180 MB baseline, and not the `pg_dump` that runs in the database container), and
   `hamstrack_backup_duration_seconds` after a real run is recorded in the drill log.
5. During a run, the app's healthcheck stays green and no container is OOM-killed
   (`docker events` / `docker inspect` exit code 137 absent).

**Failure is visible — both kinds**

6. `hamstrack_backup_last_success_timestamp_seconds{stage="dump"}` and `{stage="upload"}` are both
   present in Prometheus (`Explore → Prometheus`, job `node`) within one scrape interval of a run.
7. **The dump-succeeds/upload-fails asymmetry is demonstrated, not asserted**: temporarily set
   `BACKUP_S3_BUCKET` to a non-existent bucket, run the unit with **`sudo systemctl start
   hamstrack-backup.service`**, and observe `last_status{stage="dump"}=1`,
   `last_status{stage="upload"}=0`, a non-zero `hamstrack_backup_size_bytes`, the dump file present
   locally, and nothing in S3. Restore the correct value afterwards.
   **Through systemd, not by hand, and that is the criterion.** `ExecStopPost=` fires on any
   non-success result, so until fix round 2 this check passed as a hand-run and failed under
   `systemctl start` — the handler rewrote the trap's truthful row as `dump=0, upload=0, size=0`
   milliseconds later, which is how the runbook tells you to run it and the only way the timer ever
   will. A demonstration that only works on the invocation path production does not use proves the
   wrong thing.
8. **A deliberately skipped backup fires the alert and the alert reaches a person.** Two runs, both
   required:
   - *Fast path (same day, ~20 minutes).* Age the **published metric**, not the state file — the
     `.prom` is only regenerated by a run, so editing the state file alone changes nothing Prometheus
     can see:
     ```bash
     sudo sed -i 's/{stage="upload"} [0-9]*/{stage="upload"} 1700000000/' \
       /var/lib/node_exporter/textfile_collector/hamstrack_backup.prom
     ```
     Within ~2 minutes the aged value is in Prometheus, and after the rule's `for: 15m` Grafana shows
     **BackupStale** firing **and an email arrives at `OBS_ALERT_EMAIL_TO`**. Then
     `sudo systemctl start hamstrack-backup.service` rewrites the file with real values and the alert
     resolves — which also verifies the resolve path.
   - *Honest path (one day).* `sudo systemctl stop hamstrack-backup.timer`, skip one cycle, and
     confirm the same alert fires unaided at ~26 h with nothing edited by hand. Re-enable.
   The criterion is the **email**, not the red panel: an alert that only exists in Grafana has not
   reached a person.
9. `BackupRunFailed` fires within ~1 h of the failed run in criterion 7 and names the stage in the
   notification.

**Durability properties are real, not intended**

10. From the instance, `aws s3 cp s3://<BACKUP_BUCKET>/daily/<any key> -` is **denied**
    (`AccessDenied`), and `aws s3 rm s3://<BACKUP_BUCKET>/daily/<any key>` is **denied**. The box can
    write backups and neither read nor erase them.
11. `aws s3api get-bucket-lifecycle-configuration` shows expiry **30** days on `daily/`, noncurrent
    **30**, and **no rule carrying `NoncurrentVersionExpiration` that matches `manual/`**:
    ```bash
    aws s3api get-bucket-lifecycle-configuration --bucket "$BACKUP_BUCKET" \
      --query 'Rules[?NoncurrentVersionExpiration!=`null`].[ID,Filter.Prefix]' --output table
    # exactly one row: expire-daily-after-30-days / daily/
    ```
    The earlier wording — "no rule matching `manual/`" — is a check the bucket **already fails**:
    `abort-incomplete-multipart-uploads` is declared `"Filter":{"Prefix":""}` and matches every key.
    A criterion that cannot pass gets waved through, and the day somebody adds a bucket-wide
    `NoncurrentVersionExpiration` — the attachments bucket in `docs/ops-prod-hardening.md` §6.2
    step 5 has exactly that shape — it would go on being waved through while `manual/` quietly
    loses its protection.
    Also: `get-bucket-versioning` reports `Enabled`; `get-bucket-encryption` reports `AES256`; a
    plain-HTTP request is denied; and a `PutObject` without `If-None-Match: *` is denied **in
    `manual/` as well as in `daily/`** (§7.2).
12. `aws dlm get-lifecycle-policies` shows one ENABLED policy with `RetainRule.Count = 7`, and
    `describe-snapshots` lists a snapshot created since it was enabled.
13. The attachments bucket reports `Versioning: Enabled` and a noncurrent-version expiry rule.

**The restore is real**

14. `docs/ops-prod-hardening.md` §6 contains a **restore drill log row with a real date in the past
    tense**, naming the object restored, the counts, the Flyway version, the application version that
    booted and the elapsed time. A row written in the future tense, or a section that says "we will
    test restores", fails this criterion.
15. That drill's application boot used `ddl-auto=validate` (i.e. the default configuration) and
    started successfully, and `pg_restore` ran with `--exit-on-error` and exited 0.
16. The restored row counts match the production gauges recorded at dump time.
17. The scratch database from the drill is available (or trivially re-creatable by re-running §15) for
    HD-188's parity proof.

**Documentation**

18. `docs/self-hosting.md#backups` documents the scheduled script including `BACKUP_S3_ENDPOINT` and
    `BACKUP_TARGET=local`, and states that an untested backup is not a backup.
19. `docs/observability.md` lists the four new metrics and the two new alert rules with their
    thresholds, and explains why `noDataState: OK` means an uninstalled mechanism is silent.
20. `.env.prod.example` points at `/etc/hamstrack/backup.env` rather than duplicating its variables.
21. Every link added to a rendered doc is clicked once (the lazy-continuation trap,
    `docs/release-checklist.md`).

---

## 18. Open questions

### 18.1 Needs the owner's decision

1. **S3 Object Lock on the backup bucket — yes or no?** It makes objects immutable for a fixed
   period, so even account-level credentials cannot delete them: full ransomware resistance. It **can
   only be enabled when the bucket is created**, so it must be decided before §16 step 1, and it means
   an object cannot be deleted before its retention expires even if you want it gone (which interacts
   with erasure requests). *My recommendation: **no**, in COMPLIANCE mode certainly not.* The instance
   already cannot delete, the account is single-owner with MFA, and an immutable bucket that outlives
   an erasure request is a legal problem traded for a security one. Revisit with GOVERNANCE mode if a
   second operator ever gets AWS credentials.
2. **Bucket name.** `hamstrack-backups-<account-id>` is my suggestion; S3 names are global and this is
   yours to pick.
3. **Drill cadence — quarterly, or monthly?** I have specified quarterly plus "before any migration
   that rewrites `flyway_schema_history`". Monthly is defensible while the product is young and the
   drill takes under an hour. *Recommendation: quarterly, and treat the HD-188 drill as this
   quarter's.*
4. **Should HD-122 be extended to sync `ops/`?** It is one extra path in the same tarball copy and it
   would end hand-placement of the script forever. *Recommendation: yes, as a note on HD-122, not as a
   dependency of this ticket.*
5. **`OBS_ALERT_EMAIL_TO` — is a personal inbox the right destination for a `critical` rule?** Every
   alert in this system already routes there. Worth confirming the address is one you read on a
   Sunday, since "no backup for 26 hours" is a weekend-relevant fact.

### 18.2 Decided here — recorded so they are not re-litigated

| Question | Answer | Where |
|---|---|---|
| Compose service or host timer? | **Host systemd timer** | §4.1 |
| Depend on HD-122? | **No** — one-time operator step; HD-122 recommended in the same release | §4.4 |
| What layers? | logical dump + globals (in scope), EBS snapshots (in scope, AWS-side), attachments versioning (in scope); PITR and cross-region (out) | §2.1 |
| Prefix or separate bucket? | **Separate bucket** | §6.1 |
| Retention? | **30 days** for `daily/`, none for `manual/`, **7** EBS snapshots | §6.4, §5.2 |
| Encryption? | **SSE-S3**, not KMS | §6.3 |
| Who enforces retention? | **The S3 lifecycle rule**, never the script | §3, §6.4 |
| What can the box do? | **Write only** — no `GetObject`, no `DeleteObject` | §7.1 |
| Freshness signal? | node-exporter **textfile collector**, four gauges, `stage` label | §8.1 |
| Alert threshold? | **26 hours** (`BackupStale`, critical) + a fast `BackupRunFailed` (warning) | §8.3 |
| Dump succeeds / upload fails? | Two stages, two metrics, two rules | §8.3, §10 case 1 |
| Where does the drill run? | **The owner's dev machine**, throwaway container on port 15433 | §15.1 |
| Profile/property gating? | **None** — no application code is involved | §14 |
| Dump format? | `pg_dump -Fc` for prod; plain SQL stays in the self-hosting guide | §5.1 |

---

## 19. The highest-risk assumption, stated plainly

**That `docker exec … pg_dump` inside a 256 MB cgroup, on a box with ~340 MB available, completes
without disturbing the running application.** (As of 2026-08-28 there is also a 1023 MB swapfile at
`vm.swappiness=10`, which softens the failure mode from an OOM kill into a slowdown and does not make
the assumption true. The heavier finding from the same measurement: no container on that box except
the observability seven has a memory limit at all, so nothing bounds what the assumption competes
with either.)

Everything else here is verifiable from the repository or from an AWS API call. This one is a claim
about a machine under conditions nobody has measured — and `docs/ops-prod-hardening.md` §5 says so
about this box in general: *"Nothing has ever put the instance under load, so every number above is a
capacity that was declared rather than observed."* The backup is the first recurring workload the
instance has ever had.

What could go wrong, in order of likelihood:

- **Page-cache pressure, not RSS.** `pg_dump` reads every table, which evicts Postgres's hot pages
  from the 442 MB of buff/cache. The application does not crash; it gets slower for a few minutes at
  05:15 CET. Acceptable, and `IOSchedulingClass=idle` limits how fast that eviction happens.
- **The AWS CLI v2's resident set.** It is a frozen Python binary and is the largest single consumer
  in the run. If it exceeds `MemoryMax`, the *upload* fails while the dump succeeds — which is
  precisely the case §8.3 was built to make visible, so the failure mode is loud. If it recurs, raise
  `MemoryMax` to 384M before doing anything cleverer.
- **A `docker exec` against a Postgres already under memory pressure.** The dump connection is one
  more backend with its own `work_mem`; on a box this size that is a real, if small, cost.

**Mitigations already in the design, and what they buy:** the cgroup means the backup is the process
that dies, not the app; the metric means a death is noticed; `Nice`/`IOWeight`/`IOSchedulingClass`
mean it yields to real traffic; 03:15 UTC means there is unlikely to be any. **What none of them buy
is a measurement.** HD-189 resizes this box in the same release and HD-186 is the ticket that would
load-test it; if either lands first, re-read this section and, if the box grows, raise `MemoryMax`
deliberately rather than leaving 256M as an unexamined inheritance — an absent value is not a chosen
value, which is the argument `docs/ops-prod-hardening.md` §5 already makes about `APP_MEMORY_LIMIT`.

The second-riskiest assumption, worth one line: **that the first restore drill passes.** If it does
not, that is not a problem with this spec — it is the ticket doing its job, on a schedule of our
choosing rather than an incident's.

---

## 20. Architectural decisions (ADR)

Two decisions here are hard to reverse and will make a future contributor ask "why?". Both are drafted
as ADRs in `docs/adr/` with `Status: Proposed`; the orchestrator flips them to `Accepted` once the
mechanism has actually shipped and the first drill has run.

### 20.1 Scheduled operational jobs run as host systemd units, not compose services

- **Chosen:** a `systemd` timer + oneshot service on the instance, with the script owned by the
  repository under `ops/` and installed by an operator step.
- **Rejected:** a compose service with an in-container scheduler; a GitHub Actions schedule driving
  SSM; migrating Postgres to RDS for managed backups.
- **Trade-off:** we give up Loki log collection (Alloy reads the Docker socket, so journald output is
  invisible in Grafana) and we give up repo-driven delivery (the units must be hand-placed until
  HD-122 is extended). We gain zero idle memory on a 1909 MB box that was swapless when this was
  written (a 1023 MB swapfile was added 2026-08-28; see `docs/ops-prod-hardening.md` §5), a
  `MemoryMax` cgroup that makes an oversized job kill itself instead of the application, no new
  image or supply-chain dependency, and a `pg_dump` client that can never drift from its server.
- **Draft:** `docs/adr/0011-ops-jobs-as-host-systemd-units.md`.

### 20.2 Backups go to a dedicated bucket the instance may write but never read or delete, with retention enforced by S3 lifecycle

- **Chosen:** a separate private bucket; instance-role policy limited to `s3:PutObject` +
  `s3:AbortMultipartUpload` + prefix-scoped `s3:ListBucket`; versioning on; SSE-S3; a 30-day lifecycle
  rule on `daily/` and no rule on `manual/`.
- **Rejected:** a `backups/` prefix in the existing attachments bucket (inherits `GetObject` +
  `DeleteObject` from the app's policy); the script pruning its own old objects; SSE-KMS.
- **Trade-off:** a second bucket to create and remember, and retention that cannot be changed from the
  box (deliberately). We gain a backup archive that survives full compromise of the instance, an
  end-to-end integrity check that costs no extra permission (ETag = MD5 holds only for single-PUT
  SSE-S3), and a `manual/` prefix that no automated rule can expire.
- **Draft:** `docs/adr/0012-backups-write-only-bucket.md`.
