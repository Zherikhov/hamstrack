# ADR-0011: Scheduled operational jobs are systemd units on the host, not compose services

Record date: 2026-08-26
Status: Accepted
Source: `docs/design/production-backups-proposal.md` §4 (HD-187); the memory measurements —
`docs/ops-prod-hardening.md` §5 (2026-08-26)

## Context

Everything that runs in production has so far been a container in the `hamstrack` compose project
(`docker-compose.prod.yml` + `docker-compose.observability.yml`). HD-187 adds the first
periodic operational job — a daily backup of the database — and it has to be run somewhere.

The constraints that make this a fork rather than a routine:

- **Memory.** Measured 2026-08-26 on `i-019fe684b25ad831f`: 1909 MB in total, ~335 MB
  available, **no swap**, `mem_limit` is set only on the `app` container. A permanently
  living scheduler container pays with its resident set 24 hours a day for 30 seconds of
  work.

  > **A correction to the facts of this bullet, 2026-08-28 (HD-189). The decision does not change — it
  > only becomes better founded.** Re-measured on the same machine: there was no `mem_limit`
  > **on `app`, nor on `postgres`, nor on `caddy`** (`HostConfig.Memory = 0` on all three);
  > the limits were set on exactly the seven observability containers — the only limited
  > part of the deployment. `APP_MEMORY_LIMIT=1g` lay in `/opt/hamstrack/.env` and was read by nothing,
  > because the copy of the compose file on the machine was older than the `mem_limit` line. As a consequence
  > `-XX:MaxRAMPercentage=50` was taken from **host** memory: a heap ceiling of ~956 MB on a
  > machine with 1909 MB. Since 2026-08-28 there is swap — a 1023 MB file with `vm.swappiness=10`
  > (`/etc/fstab`, `/etc/sysctl.d/99-hamstrack-swap.conf`), at the cost of disk occupancy growing
  > 67% → 79%. The current measurements: `docs/ops-prod-hardening.md` §5.
  >
  > The lesson generalises and is worth more than the numbers themselves: **a limit is a property of a running
  > container, not of a file and not of a variable.** The claim "only `app` is limited" was
  > a claim about a *member* of a list and it flipped without contradicting anything in the repository;
  > the check has to be by category — `docker inspect … {{.HostConfig.Memory}}` over all
  > containers at once.
- **The deploy does not sync configs.** `deploy.yml` runs a single SSM command
  (`docker compose … pull && up -d`) and copies nothing from the repository. The auto-sync described in
  `docs/ops-prod-hardening.md` was never implemented (that is HD-122), so
  a new service in the repository's compose file does not by itself reach the server — that is,
  "through compose" does not mean "delivered automatically".
- **The client version.** `pg_dump` from a separate image will sooner or later drift from the server
  version and refuse to work.

## Decision

Scheduled operational jobs (for now — the backup) are shaped as a **`systemd` timer +
a oneshot service on the host**. The script belongs to the repository (`ops/backup/`), it reaches the machine
by a one-off documented operator step (§16 of the spec), the configuration lies in
`/etc/hamstrack/backup.env` — separately from `/opt/hamstrack/.env`, which compose passes
wholesale into the application container.

The mandatory properties of such a unit:

- `MemoryMax=` / `MemoryHigh=` — its own cgroup, so that a job that outgrew its budget kills
  **itself** and not the application (on a machine without swap an overrun is an OOM kill by the kernel, exit 137,
  with no stack trace);
- `Nice`/`CPUWeight`/`IOWeight`/`IOSchedulingClass=idle` — yield to production traffic;
- `Persistent=true` on the timer — a run missed because of a reboot is executed later
  rather than lost;
- a separate observability channel: the unit's logs go to journald and are **not visible in Loki** (Alloy
  reads the docker socket), so the signal about the result must be a metric, not a log
  line.

The work against the database is done through `docker exec` into the **already running** postgres container,
found by the compose service name (`docker compose ps -q postgres`) rather than by the container
name.

## Consequences

+ Zero standing memory cost on a box where there is ~335 MB of it.
+ The job is locked in its own cgroup: its failure is its failure, not the failure of the application.
+ Neither a new image nor a new dependency in the supply chain.
+ `pg_dump` cannot drift in version from the server — it is one and the same container.
− The unit's logs do not reach Loki; they have to be looked at through `journalctl` on the machine.
  Closed by the `loki.source.journal` component in `observability/alloy/config.alloy`,
  if it is ever needed.
− The unit files and the script are put in place by hand until HD-122 is extended with a sync of the `ops/` directory.
− The unit runs as root (the docker socket is needed), that is, it is privileged code in the
  repository — mode `0750 root:root` and a review are mandatory.

## Alternatives

- **A compose service with a scheduler inside** (cron/supercronic or a ready-made
  `*-backup-local` image) — rejected: a permanent resident set, a second process in the image or a new
  dependency with the DB password in the environment, plus either the docker socket or its own
  `pg_dump` with the risk of a version drift. The only thing it wins is the collection of logs into
  Loki.
- **A schedule in GitHub Actions poking SSM** — rejected: the durability of the data
  ends up depending on GitHub's scheduler (which documentedly skips
  runs under load), on the IAM keys of the `hamstrack-deploy` user and on the
  repository not being renamed/archived. Backups must not depend on the health of CI.
- **Moving Postgres to RDS for the sake of managed backups** — rejected within this decision:
  it is an infrastructure migration that roughly doubles the cost, and it splits the history of the database
  between DC and Cloud. It may be revisited in a separate ADR.
