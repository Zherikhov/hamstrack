# Observability — logs, metrics, dashboards & alerts

**Audience:** engineers who deploy and operate Hamstrack — both the hosted
**Cloud** model and self-hosted **DC** installs. This is the operator reference;
the internal design rationale lives in
[`docs/design/observability-proposal.md`](design/observability-proposal.md).

Hamstrack ships two things:

1. **Instrumentation baked into the app** — structured JSON logs and a Prometheus
   metrics endpoint. Always compiled in, identical in Cloud and DC; only a couple
   of profile/env defaults differ. You get this whether or not you run the stack.
2. **An optional observability stack** (`docker-compose.observability.yml`) —
   Grafana + Loki (logs) + Prometheus (metrics) + Alloy (log collector) +
   node/cadvisor/postgres exporters. Ships pre-provisioned dashboards and alerts.
   **Cloud always runs it; DC opts in.**

---

## Contents

- [What you get](#what-you-get)
- [Architecture](#architecture)
- [Part A — app instrumentation (both modes)](#part-a--app-instrumentation-both-modes)
  - [Logging](#logging)
  - [Metrics & the management port](#metrics--the-management-port)
  - [Custom business metrics reference](#custom-business-metrics-reference)
- [Part B — the observability stack](#part-b--the-observability-stack)
  - [Components](#components)
  - [Dashboards](#dashboards)
  - [Alerts](#alerts)
- [Running it](#running-it)
  - [Cloud (hosted)](#cloud-hosted)
  - [DC (self-hosted)](#dc-self-hosted)
  - [Dev (local, app from an IDE)](#dev-local-app-from-an-ide)
- [Configuration reference](#configuration-reference)
- [Security model](#security-model)
- [Operations & troubleshooting](#operations--troubleshooting)
- [Extending](#extending-add-a-dashboard-or-metric)

---

## What you get

| Signal | Source | Where you see it |
|---|---|---|
| **Logs** | app stdout (JSON) + every container | Grafana → Explore/Logs (Loki) |
| **HTTP / JVM / DB-pool metrics** | app Actuator + Micrometer | App Overview, JVM & DB dashboards |
| **Host CPU/RAM/disk** | node-exporter | Host & Containers dashboard |
| **Per-container CPU/RAM** | cAdvisor | Host & Containers dashboard |
| **PostgreSQL** | postgres-exporter | Postgres dashboard |
| **Business metrics** (signups, active users, issues/projects created, logins, invites, email, attachments) | app custom Micrometer meters | Product dashboard |
| **Backup freshness** | host `systemd` job → node-exporter textfile collector | Explore → Prometheus, and the two backup alert rules |
| **Config drift** (the box versus the commit deployed to it) | host `systemd` job → node-exporter textfile collector | Explore → Prometheus, and the `ConfigDrift` / `DeployImagePinned` rules |
| **Alerts** (app/DB down, error rate, latency, disk, host memory/swap, email failures, heap, backup freshness, config drift…) | Grafana unified alerting | email (+ Grafana UI) |

---

## Architecture

```
                       Internet
                          │  (443 only)
                     ┌────▼────┐
                     │  Caddy  │      public reverse proxy (Cloud/DC prod)
                     └────┬────┘
                          │ :8080  (app HTTP — the only proxied port)
                     ┌────▼─────────────────────────┐
                     │            app                │
                     │  :8080  public HTTP / SPA     │
                     │  :9090  MANAGEMENT (INTERNAL)  │  /actuator/health,prometheus,info
                     │  stdout JSON logs             │
                     └──┬─────────────┬──────────────┘
        scrape :9090 /  │             │  stdout via docker socket
        actuator/prom   │             │
                  ┌──────▼─────┐   ┌───▼────┐
                  │ Prometheus │   │ Alloy  │──► pushes logs ──►┌──────┐
                  └──────┬─────┘   └────────┘                   │ Loki │
     scrape  ┌──────────┼─────────────┐                        └──┬───┘
   node-exp  │      cadvisor      postgres-exporter               │
   :9100     │      :8080         :9187 ──► postgres:5432          │
             └──────────┬──────────────────────────┬──────────────┘
                        │ PromQL                    │ LogQL
                     ┌──▼───────────────────────────▼──┐
                     │            Grafana               │  dashboards + alerting
                     │   127.0.0.1:3000 (loopback only) │  → email
                     └──────────────────────────────────┘
                        ▲
                        │  SSM port-forward (Cloud)  /  SSH tunnel (DC)
                     operator's laptop → http://localhost:3000
```

**Key invariant:** only Caddy (80/443) and, through it, the app's `:8080` are
public. The management port `:9090`, Prometheus, Loki, the exporters and Grafana
are **internal to the compose network** and never published to the internet.
Operators reach Grafana over a tunnel, not a public URL.

**One signal enters from outside the diagram: the textfile collector.**
node-exporter is started with
`--collector.textfile.directory=/var/lib/node_exporter/textfile_collector` and that
host directory is bind-mounted read-only into the container at the **same path** on
both sides (node-exporter prefixes some paths with `--path.rootfs`, and an identical
path resolves to the same host directory under either behaviour, so the flag cannot
be subtly wrong). Anything that writes a valid `.prom` file there becomes a metric
under the `node` job with no new process, no new port and no new scrape target. Its users are the
**host `systemd` timers** — the scheduled database backup (HD-187) and the config-drift
check (HD-199) — which are outside the compose project entirely and therefore invisible to
Alloy, which collects logs through the Docker socket. For anything that runs that way, a
`.prom` file is its only channel to a person; its own text stays on the box
(`journalctl -u hamstrack-backup`, `journalctl -u hamstrack-config-drift`).

A `command`/`volumes` change like that one needs `docker compose … up -d
node-exporter` to **recreate** the container; a `restart` will not pick it up. Create
the host directory before the container starts, or Docker creates it for you at mount
time.

---

## Part A — app instrumentation (both modes)

This is compiled into the single JAR and behaves identically in Cloud and DC. The
only per-mode difference is the value of the `deployment` label/field
(`cloud` vs `dc`, from `SPRING_PROFILES_ACTIVE`).

### Logging

The app logs to **stdout**. Format is profile-gated (Spring Boot 4 native
structured logging — no extra dependency):

| Profile | Console format |
|---|---|
| default / `local` (dev) | human-readable colored text |
| `cloud`, `dc` (prod) | one **JSON** object per line (`logstash` schema) |

JSON fields: `@timestamp`, `level`, `logger_name`, `thread_name`, `message`,
`stack_trace` (on exceptions), plus static `application=hamstrack` and
`deployment=<cloud|dc>`. MDC values are included, leaving a slot for a future
`requestId`/`traceId`.

Verbosity is env-driven:

| Env var | Default | Controls |
|---|---|---|
| `LOG_LEVEL` | `INFO` | root logger |
| `LOG_LEVEL_APP` | `INFO` | the `com.hamstrack` package |

The app never logs secrets (tokens, passwords, JWTs, reset/verification tokens are
only ever placed in email bodies). Full emails are not logged at INFO.

**How logs reach Loki:** the **Alloy** collector tails every container's stdout
via the docker socket (read-only) and ships them to Loki with low-cardinality
labels only: `container`, `compose_project`, `service`, `level`, `deployment`.

### Metrics & the management port

The app exposes Spring Boot Actuator + a Micrometer Prometheus registry on a
**separate management port** (`MANAGEMENT_PORT`, default **9090**), kept strictly
internal:

- Exposed endpoints (`MANAGEMENT_ENDPOINTS`, default `health,prometheus,info`):
  - `GET :9090/actuator/prometheus` — the metrics scrape endpoint.
  - `GET :9090/actuator/health` — liveness/readiness (does **not** include the
    mail server — SMTP reachability must not mark the app unhealthy).
  - `GET :9090/actuator/info` — build info (env dump is disabled).
- `health.show-details=never` — health returns status only, no DB/disk internals.
- A dedicated Spring Security filter chain (`@Order(0)`,
  `EndpointRequest.toAnyEndpoint()`) permits the management endpoints — this is
  safe **because 9090 is never published**; only in-network Prometheus reaches it.
- On the public `:8080`, `/actuator/**` is **not served** — the SPA fallback
  explicitly 404s it. Metrics are never reachable on the public port.

**Auto-provided metrics:** `http_server_requests_seconds_*` (rate, latency
histogram, status), `jvm_*` (heap, GC, threads), `hikaricp_connections_*` (DB
pool), `logback_events_total`, process/system.

All app metrics carry `application="hamstrack"` and `deployment="<cloud|dc>"` tags
and are scraped under the Prometheus job `hamstrack-app`.

### Custom business metrics reference

Emitted by `common.observability.ProductMetrics` at the event site. **Labels are
bounded enums only** — no user/workspace/issue IDs or emails, so there is no
high-cardinality blow-up and no tenant PII in the metrics store.

Prometheus names (Micrometer converts dots→underscores; counters get `_total`):

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `hamstrack_users_registered_total` | counter | — | successful registrations |
| `hamstrack_auth_login_total` | counter | `outcome`(success/failure), `reason`(ok/bad_credentials/not_verified/disabled) | login attempts |
| `hamstrack_auth_email_verified_total` | counter | — | email verifications |
| `hamstrack_auth_password_reset_total` | counter | `phase`(requested/completed) | password-reset flow |
| `hamstrack_ratelimit_hit_total` | counter | `kind`(ip_window/login_backoff/rank_rebalance/report_requests/search_requests/planning_requests/write_requests/upload_bytes/expensive_read_in_flight/expensive_read_surface_full/invite_sender_volume/invite_recipient_cooldown/invite_recipient_daily/password_reset_recipient_cooldown/password_reset_recipient_window/verification_recipient_cooldown/verification_recipient_window/registration_verification_recipient_cooldown/registration_verification_recipient_window) | rate-limit rejections. The three `invite_*` kinds are separate because they mean three different things to whoever reads the alert: `invite_sender_volume` is bulk-shaped, `invite_recipient_cooldown` is harassment-shaped, and `invite_recipient_daily` means one address has taken all it may take in a day — reached from several accounts (each other sender counts once, so that costs a mailbox per slot, which is why it is the sharpest of the three) or by one account spending its own share; the kind alone does not say which, and the domain-only log line and `mail_send_events` do. The `password_reset_*`/`verification_*` kinds (HD-202) are the per-address ceilings on the two anonymous auth mailers **whose refusal is invisible**, and they are the one refusal in this product with **no other witness at all**: both endpoints must answer identically for a registered and an unregistered address, so a throttled request gets the usual `200`, the usual sentence and no log line — this counter is the entire evidence that it happened. The `registration_verification_*` pair is the third auth budget, held by `POST /api/auth/register` alone; it refuses *visibly*, so those two are corroboration rather than sole evidence — a bucket shared with resend-verification let anybody deny a stranger's signup for free and in silence, which is why it is separate. `AuthMailThrottleTripping` is built on it. Read a `_window` rate in two directions: mail aimed at a person, *and* a window in which that person cannot recover their own account. **A rule built on this metric only ever sees an attacker who HITS a ceiling** — one who stays beneath them is invisible here, which is why `MailDailyVolumeHigh` and `InviteVolumeUnaccepted` rank above `InviteThrottleTripping`, and why the auth mailers needed `hamstrack_mail_anonymous_recipient_max` below. Every kind here that a mail policy declares is named `<thing>_recipient_cooldown` / `_recipient_window` / `_recipient_daily`, and the alert rules select on that convention rather than on a list of names — a test reads the regexes out of `rules.yml` and fails if a policy declares a kind no rule would match. The `write_requests` / `upload_bytes` pair (HD-191) is the mutating side of the API, which had no budget of any kind until then: they are two constants rather than one because they mean different things to whoever reads the alert — a rate on the first is a client that lost its debounce or a script driving the issue API, a rate on the second is somebody moving **volume**, which is the shape that costs money on Cloud. Neither is the storage quota: a refusal here says a caller went too fast and will be served again next minute, while `hamstrack_storage_quota_refused_total` says a **tenant** is stuck until somebody frees space or raises a number. The `planning_requests` kind (HD-174) is the per-principal budget over `…/projects/*/backlog/**` — the planning view and its section reads. **Nothing watches it: there is no alert rule on it, deliberately, exactly as there is none on `report_requests` or `search_requests`**, and the reason is that a rate here is almost always a client defect rather than instance load. 240/min is ~3.4× the busiest single tab a facilitator produces and a person cannot drag cards faster than they can drag cards, so a sustained rate means a UI that lost its query cache or a script — and the remedy is with that client, not with an operator at 3 a.m. **It is also not the number that says the planning surface is hurting the instance**; that is the `expensive_read_*` pair below, which since HD-174 covers the planning reads too, because one planning aggregate holds a connection across up to 32 statements and a rate spends the same unit whether a request takes 8 ms or 8 s. The `expensive_read_*` pair (HD-182) is the **occupancy** bound rather than a rate at all — how many expensive reads are RUNNING, not how often they were asked for — and the two kinds are separated because the remedies are in different hands: `expensive_read_in_flight` is one client fanning out past its own share, `expensive_read_surface_full` is every principal together exceeding this replica's share of the connection pool, which is a provisioning decision. Only the second has a rule (`ExpensiveReadSurfaceSaturated`); paging on the first would page an operator about somebody else's client |
| `hamstrack_mail_anonymous_recipient_max` | gauge | — | **The one meter here that sees an attacker who stays under every ceiling** (HD-202). How much anonymous auth mail (password reset / verification) reached the single busiest `recipient_key` in the last 6 h, refreshed every 5 min by `AnonymousMailConcentration`. The refusal counter above cannot see the denial-of-recovery attack it was written for: to hold a victim's bucket full you send exactly as slots age out, and **every one of those requests is allowed**, so the counter reads zero while somebody stays locked out of their own password recovery indefinitely. This is a *quantity*, so it is true whether or not a ceiling fired. **It carries no address on purpose** — a Grafana alert emits its series labels through the contact point, so a `recipient_key` label would mail recipient addresses to the operator; the address is looked up in `mail_send_events`. Every replica computes the same instance-wide number, so alert on `max()`, never `sum()`. **It fails silent**: the value is last-write-wins, so a query that starts erroring or timing out freezes it at its last (quiet) value rather than clearing it — and the row count that makes that query slow is the flood it exists to detect. Read it together with the freshness gauge below. `MailRecipientConcentration` treats `NoData`/`Error` as firing rather than `OK`, which is the file's default — that departure is a **category rather than a count**: any rule whose failure mode is *absence* rather than noise, i.e. any rule that is the only witness to something, and `MailConcentrationGaugeStale` below qualifies for the same reason one level up |
| `hamstrack_mail_anonymous_recipient_max_age_seconds` | gauge | — | Seconds since the gauge above was last **successfully** refreshed — counting from **process start** until the first success, so there is deliberately no "never yet refreshed" sentinel. It published `-1` until HD-202's final review, and `-1` is *below* the alert's own `> 1800` threshold: an instance whose refresh never succeeded once (V25 not applied on an upgrade, a persistent timeout, a permissions problem) therefore had `MailRecipientConcentration` pinned at `0` and `MailConcentrationGaugeStale` unable to fire, with both series present so `NoData`/`Error` did not help and no Loki-backed rule to catch the WARN. Evaluated at scrape rather than on the refresh schedule, so it keeps rising while the refresh is broken instead of freezing beside the value it describes. `MailConcentrationGaugeStale` fires past 30 min (six missed refreshes), over a `for: 10m`, which absorbs a deploy several times over. If it fires, check that `idx_mail_send_events_anonymous` (V25, partial on `(created_at, recipient_key) WHERE sender_user_id IS NULL`) exists — without it that aggregate scans the whole table |
| `hamstrack_expensive_read_in_flight` | gauge | — | Requests currently in flight on the expensive-read surface — every read that holds a connection while it works, today reports, HQL search, saved filters, the storage breakdown and, since HD-174, the **planning** reads (`…/projects/*/backlog/**`) — out of `EXPENSIVE_READ_MAX_IN_FLIGHT` (HD-182). Read the membership as a category: it grew once already, and the planning reads are the largest of them, so this gauge is now the one number that answers “is the planning surface holding pool connections?”. **The number that makes the bulkhead legible** — without it "is the share ever full?" is unanswerable, and, more importantly, so is "is it draining?": a permit is taken in an interceptor and returned when the request ends, so a **leaked** permit is a permanent capacity loss on that replica and looks exactly like a busy instance until the surface refuses everything. A gauge pinned at the ceiling with no traffic to explain it is **either** that leak **or** a handful of slots held open by slow or hostile clients — a permit spans the request-body read and the response write, both paced by the caller — and `hamstrack_expensive_read_permit_force_released_total` below is what separates the two. Per replica — each process has its own bulkhead and its own pool — so alert with `max()` and **never** `sum()`, which would report an occupancy no single replica ever had. No labels: occupancy is an instance property, and a per-principal breakdown would be the unbounded label the cardinality rule forbids |
| `hamstrack_expensive_read_permit_force_released_total` | counter | — | Slots the watchdog took back because one request had held one longer than `DB_STATEMENT_TIMEOUT_MS` + 60 s (HD-182 review). **The counter that keeps a new failure mode from being silent**: a slot is taken before the request body is read and given back after the response is written, so a client that trickles bytes — or reads a CSV one byte at a time — can hold a share of the bulkhead for as long as it likes at the price of one socket. Six of them are the whole default surface. The watchdog takes those back, and a correction nobody counted would be indistinguishable from nothing happening. **Read it beside `hamstrack_expensive_read_in_flight`**: pinned gauge + flat counter is a leaked permit (restart, and file a bug); pinned gauge + climbing counter is slots being held, and the thing to look at is where those connections come from. Nonzero at all is worth a look — a legitimate request that runs for over a minute on this surface is either a report nobody should be waiting for or a client that stalled. **`ExpensiveReadPermitForceReleased` watches it directly** on a sustained rate rather than on the first occurrence, which is what stops this row prescribing an action nothing would ever prompt: before that rule existed the counter was covered only *by consequence*, since a held slot reaches `ExpensiveReadSurfaceSaturated` only once the surface is already refusing real users. Note the asymmetry the two rules inherit from the metric: a **leak** never increments this counter at all, so it is the *silence* here beside a pinned gauge that names it, and no rule can fire on that. No labels, for the reason above; the WARN line carries the user id |
| `hamstrack_storage_quota_refused_total` | counter | — | Attachment uploads refused because a workspace filled its storage quota (HD-191). **This is the only signal that a tenant is stuck**: the refusal is a clean `409`, so it appears in no error rate, no 5xx panel and no log anybody watches — and the message deliberately prescribes no action, because there is no remedy every reader of it can perform (a contributor may delete only their own files, the space is often in a project they cannot see, and neither they nor their workspace owner can raise an instance property). No workspace label, per the cardinality rule: an alert emits its series labels through the contact point, so "which workspace" is answered against `workspace_storage_usage`, and `StorageQuotaRefusals` carries that query in its summary |
| `hamstrack_storage_bytes_used_total` | gauge | — | Attachment bytes occupied across every workspace on the instance. Evaluated at **scrape** — it is a `SUM` over one row per workspace, the same class of query as the `_total` counts above, unlike the two reconciler-fed gauges below |
| `hamstrack_storage_quota_fill_max` | gauge | — | The fullest workspace as a **0..1 fraction** of the configured quota. Refreshed on the **reconcile schedule**, not at scrape (it is a `MAX` over the counter table, and fill is a trend rather than a tick), so it is as old as the last pass — read it together with the freshness gauge below. Every replica computes the same install-wide number, so the series are **duplicates, not shards**: alert on `max()`, **never** `sum()`, which would multiply the fill level by the replica count and page at a third of the real threshold. `StorageFillHigh` fires at 0.9, i.e. *before* the refusals, which is the entire point of having a threshold |
| `hamstrack_storage_drift_bytes` | gauge | — | The largest absolute difference the last reconcile pass found between `workspace_storage_usage.bytes_used` and the attachment rows it is supposed to equal. **Expected to be exactly zero** — the counter is maintained by a database trigger that follows every INSERT, DELETE and `ON DELETE CASCADE`, and the reconciler is only the witness — so any value is a real finding: rows moved outside the trigger (a restore, a hand-run `DELETE`, a bulk path), or the trigger is gone. While it is non-zero the quota is enforcing a number that is not true, in one direction or the other |
| `hamstrack_storage_drift_refreshed_at_age_seconds` | gauge | — | Seconds since the reconcile pass last **completed** — counting from **process start** until the first success, with **no sentinel branch**, exactly as the mail freshness gauge above does and for the same reason. Both gauges it describes are last-write-wins, so a reconciler that stops running leaves them **frozen** at their last (calm) values and `StorageUsageCounterDrift` and `StorageFillHigh` go quiet together, reading precisely like a clean instance. A sentinel below the threshold would make "never ran, not once" the one state nobody hears about. Evaluated at scrape (arithmetic on a long, not a query) so it keeps rising while the pass is broken. **Stamped on every replica, including one that skipped because another replica held the advisory lock** — a node that correctly deferred is not stale, and `StorageDriftGaugeStale` reads `max()` across replicas, so a deferring node that did not stamp would make the rule fire on every multi-replica install and never clear. (`min()` is not the alternative: one restarted node would then suppress it for 25 h with nothing reconciling.) `StorageDriftGaugeStale` fires at 25 h = one missed daily pass — and fires **on purpose** when `STORAGE_QUOTA_RECONCILE_CRON` is deliberately emptied: disabling the witness is allowed and is not allowed to be silent. That rule treats **`NoData`/`Error` as firing**, unlike most of this file: its subject is an absence, so "no series" is not "nothing is wrong" |
| `hamstrack_workspaces_created_total` | counter | `source`(user/onboarding/demo) | workspaces created |
| `hamstrack_projects_created_total` | counter | — | projects created |
| `hamstrack_issues_created_total` | counter | `type`(issue-type name, from the admin catalog) | issues created |
| `hamstrack_invites_sent_total` / `_accepted_total` / `_declined_total` | counter | — | workspace invites |
| `hamstrack_email_sent_total` | counter | `type`(verification/password_reset/invite), `outcome`(success/failure) | outbound email. `failure` covers **two** things: a send the SMTP host refused, and a message that was never attempted because the mail queue was full or a deploy's shutdown drain expired with it queued. Same tag on purpose — a mail that never left is still a mail that failed, and a separate tag would put it outside the `EmailFailures` alert. `failed_email` is where the two are told apart (`attempts = 0` and a `last_error` starting `NEVER ATTEMPTED [<reason>]` means it was never tried, and the bracketed reason says which of the three) |
| `hamstrack_role_scope_violation_total` | counter | `source`(workspace_members/project_members/workspace_invites/default_project_role) | a stored `role_id` failed the scope/ownership assertion — see the alert below |
| `hamstrack_db_statement_budget_exceeded_total` | counter | `method`(HTTP method), `route`(**mapped pattern**, e.g. `/api/workspaces/{workspaceId}/projects/{projectId}/reports/flow`, or `unmapped`) | a database statement PostgreSQL cancelled at `DB_STATEMENT_TIMEOUT_MS` — see the alert below. `route` is the templated pattern and never the request URI, which would carry workspace and project ids |
| `hamstrack_db_connection_acquisition_failed_total` | counter | `method`(HTTP method), `route`(**mapped pattern**, or `unmapped`) | a request the pool could not serve within `DB_CONNECTION_TIMEOUT_MS`, answered `503 DATABASE_BUSY` — see the alert below. HikariCP's own `hikaricp_connections_timeout_total` is **not** a substitute: it says the pool refused somebody, never which route they were on. Nor is it the same population — it counts a **superset**. This counter is written by whichever of the two writers answered the request: an exception handler for a failure inside a handler (`route` = the mapped pattern) and a servlet filter for one that failed earlier, before a handler was matched (`route` = `unmapped`, which on a normal instance is the majority — an authenticated request's token lookup runs in the security filter chain). HikariCP's counter additionally sees acquisitions with no caller to refuse — a scheduled job, the shutdown residue write, Flyway at startup — so the two are expected to disagree by that much rather than by a metrics fault. Read it beside `hikaricp_connections_pending` (how many are queued right now) — the pair is what separates "the pool is tight" from "the pool is gone". Unlike the `422` above, this one is a 5xx and therefore **also** raises `HighErrorRate`, which is correct: pool exhaustion is an incident — and that holds for **both** writers, including the filter's, because it is registered inside the observation filter that records `http_server_requests`; outside it, a refusal that unwinds through the observation is recorded as a `200` and the alert sees nothing |
| `hamstrack_attachments_uploaded_total` | counter | — | attachment uploads |
| `hamstrack_attachments_bytes_count` / `_sum` | summary | — | upload count / cumulative bytes stored |
| `hamstrack_users_total` / `hamstrack_users_active` | gauge | — | total users / ACTIVE users |
| `hamstrack_workspaces_total` / `hamstrack_projects_total` / `hamstrack_issues_total` | gauge | — | live totals |

Gauges are evaluated at scrape time via cheap `count` queries.

#### Backup metrics — not emitted by the app

Some `hamstrack_*` gauges do not come from the JAR at all, and these four are the first of
them. They are
written as a `.prom` file by the host `systemd` unit `hamstrack-backup.service` (HD-187,
[`ops/backup/`](../ops/backup/)) and picked up by node-exporter's textfile collector, so
they arrive under the Prometheus job **`node`**, not `hamstrack-app`, and they carry
neither the `application` nor the `deployment` tag. The application knows nothing about
backups and deliberately does not: the same binary ships to self-hosters who may back up
by entirely different means.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `hamstrack_backup_last_success_timestamp_seconds` | gauge | `stage`(dump/upload) | Unix time the stage last **succeeded**. Read from a state file, so a failed run *preserves* the previous value — "this run failed" and "nothing has succeeded since Tuesday" are different facts, and the two alert rules below depend on telling them apart |
| `hamstrack_backup_last_status` | gauge | `stage`(dump/upload) | whether the most recent run completed that stage (`1`) or not (`0`) |
| `hamstrack_backup_size_bytes` | gauge | — | size of the dump the most recent run produced. Published so a baseline accumulates; there is deliberately **no** rule on it yet (a threshold set before there is history cries wolf during normal growth) |
| `hamstrack_backup_duration_seconds` | gauge | — | wall-clock duration of the most recent run |

The only label is `stage`, a two-valued enum — the same cardinality rule as everything
above. `stage="upload"` is not emitted at all when the job is configured with
`BACKUP_TARGET=local`, because there is no upload to report on.

#### Config-delivery metrics — not emitted by the app either

Same shape, same channel, different question. Written by
[`ops/drift/hamstrack-config-drift.sh`](../ops/drift/hamstrack-config-drift.sh) (HD-199) —
hourly from `hamstrack-config-drift.timer`, **and at the end of every deploy**, because the
freshest reading should always be the one taken at the moment the configuration changed.
They answer "has this box changed since it was deployed?", which is a question nothing in
the application can see: the whole failure they exist for is a repository and a machine
that disagree.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `hamstrack_config_drift` | gauge | `scope`(files/containers/installed-ops/edge-body-limit) | whether the box differs from what was deployed (`1`) or not (`0`). **`files`**: a synced file was edited, or a file was added to a synced directory, since the deploy. **`containers`**: the file on disk is right and the running container was never recreated from it. **`installed-ops`**: an artefact installed under `/usr/local/bin` or `/etc/systemd/system` differs from its copy in `/opt/hamstrack/ops` — a deploy places files and never installs them, so this scope exists to say that the gap is open. **`edge-body-limit`**: the deployed `Caddyfile` carries no `request_body`/`max_size` block (HD-191) — the one scope that compares the box against the repository's *intent* rather than against what a deploy placed, because the applier hard-refuses to sync a `Caddyfile` and that block therefore arrives only by a hand merge; until it does, an over-sized upload is refused only after the app has read the whole body, on the one lane no rate-limit budget bounds. A box with **no** `Caddyfile` is not running the bundled proxy and reads `0`. *Which* files differ goes to the journal, never into a label |
| `hamstrack_config_deployed_info` | gauge | `sha` | always `1`; the label is the commit whose configuration was last applied. This is what the deploy *applied*, as distinct from whether the box still matches it |
| `hamstrack_config_check_timestamp_seconds` | gauge | — | when this check last ran. It exists to tell a fresh `0` from a stale one, which matters because the deploy publishes these metrics even where the hourly timer is **not** installed |
| `hamstrack_deploy_image_pinned` | gauge | `tag` | `1` while `APP_IMAGE_TAG` in `/opt/hamstrack/.env` names anything other than `latest` — an emergency rollback still in place, or a version somebody is deliberately holding. It says the tag is not `latest`; it does **not** say the deploy is blocked, which depends on whether the pin has *moved* since `.deployed-image-tag` was written. The pin lives in `.env` because that file survives every deploy by construction; this metric is what makes un-pinning a mechanism instead of a memory task. Ending the alert has exactly two honest endings — un-pin, or adopt the pin with one `--adopt-pin` apply; `--allow-pinned` deliberately does not end it, because it applies one run without declaring the tag intended |

`scope` is a closed three-valued enum. `sha` and `tag` change on a deploy — a handful of new
series a week against a 15-day retention, which is worth writing down in a project that
otherwise forbids unbounded labels.

---

## Part B — the observability stack

The stack is a separate compose file layered on top of your app compose. It
publishes **no public port** (Grafana binds `127.0.0.1:3000` only).

### Components

| Service | Image (pinned) | Purpose | mem_limit | Retention |
|---|---|---|---|---|
| loki | `grafana/loki:3.4.2` | log store | 256m | `LOKI_RETENTION_PERIOD` (default 168h / 7d) |
| alloy | `grafana/alloy:v1.7.1` | tail container stdout → Loki | 128m | — |
| grafana | `grafana/grafana:11.5.2` | dashboards + alerting | 128m | provisioned |
| prometheus | `prom/prometheus:v3.2.1` | metrics store + scrape + rules | 256m | `PROMETHEUS_RETENTION_TIME`/`_SIZE` (15d / 2GB) |
| node-exporter | `prom/node-exporter:v1.9.0` | host CPU/RAM/disk | 64m | scraped |
| cadvisor | `gcr.io/cadvisor/cadvisor:v0.49.1` | per-container CPU/RAM | 128m | scraped |
| postgres-exporter | `quay.io/prometheuscommunity/postgres-exporter:v0.16.0` | DB stats | 64m | scraped |

Budget **~0.7–0.8 GB RAM** for the full stack. On a small host (e.g. a 2 GB
instance already running app + Postgres + Caddy), validate headroom or size up.

**Measured on the Hamstrack Cloud box 2026-08-28** (HD-189), for whatever one idle
instance is worth: all seven containers reported a non-zero `HostConfig.Memory` and
sat inside it, **~466 MiB of actual RSS** against ~1 GB of declared ceilings. On that
same box the `app`, `postgres` and `caddy` containers each reported
`HostConfig.Memory = 0` — no limit at all — so this stack was the only *bounded* part
of the deployment. Read that before blaming the optional stack for host memory
pressure; it is the part that cannot run away. It is **not** a sizing recommendation
— that instance had never been under load when it was taken.

**Both halves have since moved, so read the paragraph above as a dated reading.** The
load run happened (2026-08-31, `ops/loadtest/RESULTS-2026-08-31.md`): under load this
stack peaked at the same **~466 MiB** while the app reached ~678 MB, PostgreSQL ~240 MB
and Caddy ~24 MB, and the box — not this stack — ran out of memory. And `app`,
`postgres` and `caddy` are no longer unbounded: all three now carry a `mem_limit` in
`docker-compose.prod.yml` (HD-152 / HD-180). What that does **not** buy is a stack that
cannot exhaust the host: the ten ceilings now declare **2688 MiB on a 1909 MiB box**,
which is why the sum is written out at the top of that file rather than left to be
inferred from here. Whether any given container is bounded is still a question for
`docker inspect`, on the box, today.

### Dashboards

Auto-provisioned into the **Hamstrack** folder in Grafana:

| Dashboard | Shows |
|---|---|
| **Logs** | live container logs, filterable by container / level / free-text |
| **App Overview** | request rate, 5xx %, p95/p99 latency, requests by status, slowest endpoints |
| **JVM & DB** | heap used/max, non-heap, GC pause, live threads, HikariCP pool |
| **Host & Containers** | host CPU/RAM/filesystem, per-container CPU/RAM |
| **Postgres** | up, DB size, cache-hit ratio, connections, TPS |
| **Product** | users total/active, registrations, logins by outcome, content created (incl. by issue type), invites, email by type/outcome, rate-limit hits |

### Alerts

Provisioned Grafana rules (evaluate every 1 min). They route to the **email**
contact point, whose address is `OBS_ALERT_EMAIL_TO`.

**`OBS_ALERT_EMAIL_TO` is required, and the rules need it — not only the delivery.**
The contact point has no "address optional" mode, though prose here used to promise
one. Two different things happen when the value is missing, and which one you meet
depends only on how Grafana was started:

| How the value is missing | What happens |
|---|---|
| Unset or empty when starting through `docker-compose.observability.yml` | **The `docker compose` command refuses to run**, naming `OBS_ALERT_EMAIL_TO` and the `.env` it belongs in. This is the one an operator meets. The dev file (`docker-compose.observability.dev.yml`) never refuses — it hardcodes a literal address. |
| An empty string reaching Grafana (Grafana started by hand against `observability/grafana/provisioning`) | Grafana rejects the contact point at provisioning time, **skips the whole alerting tree with it — every rule below is absent** — and then **refuses to start**: it exits 1 and crash-loops under `restart: unless-stopped`, `/api/health` never answering. |

**The stack is not "green with nothing watching it" — Grafana is visibly dead. The
silence is one layer up.** `docker compose up -d` **still exits 0** while that
container crash-loops, so a deploy reports success and moves on; only `docker compose
ps` (or the missing Grafana) shows it. Grafana's own error names an internal
integration UID, not the variable or the file an operator must fix:

```
logger=provisioning level=error msg="Failed to provision alerting" error="failure to map file
contactpoints.yml: failure parsing contact points: email: failed to validate integration \"email\"
(UID hamstrack-email) of type \"email\": could not find addresses in settings"
```

That is the case for guarding it at interpolation instead: `${OBS_ALERT_EMAIL_TO:?…}`
fails *earlier* — before anything is created — and names both the variable and the
`.env` it belongs in.

**What the refusal costs, since every documented command layers this file onto the
app's compose file in one invocation:** the abort takes the *whole command*, not just
the optional stack — `up -d`, and equally `down`, `stop`, `ps` and `logs` when passed
with both `-f` files. So an operator whose `.env` lost the variable cannot even tear
the stack down until they put it back. This is acceptable and long-standing
(`GF_SECURITY_ADMIN_PASSWORD` has behaved this way since day one): Compose resolves
interpolation *before* it creates, changes or stops anything, so nothing is modified
by the refusal and a running app keeps running.

**Dashboards without alert mail — the self-hosted case — is possible, one layer up.**
It does not exist at the contact point (the address is not optional), but it does
exist at the SMTP switch: set `OBS_ALERT_EMAIL_TO` to any address you own **and**
`GF_SMTP_ENABLED=false`, and the rules provision, evaluate and show their state in
Grafana → Alerting with nothing delivered. `GF_SMTP_ENABLED` defaults to `true` and
is already read by the compose file.

**Verify the routing, not only the rules — and verify it after every Grafana upgrade
or provisioning change.** From a shell with Grafana reachable (port-forward, or
`docker exec` into the container):

```bash
curl -su admin:"$GF_SECURITY_ADMIN_PASSWORD" localhost:3000/api/v1/provisioning/policies \
  | jq '{receiver, provenance}'
# expect exactly: { "receiver": "email", "provenance": "file" }
```

Both fields matter. `receiver != "email"` means the root policy is no longer ours;
`provenance != "file"` means it is no longer owned by `policies.yml` and can be
edited away in the UI. **The reason this is worth a check of its own:** Grafana
carries an unmanaged built-in contact point named `email receiver`, addressed to
`<example@email.com>`, and `email.com` is a live third-party domain with real MX
records. So if our provisioned policy is ever lost, alerts do not bounce and do not
pile up in a queue where someone would notice — **they are delivered to a stranger**.
`policies.yml` is the only thing fencing that off. While you are there, the rules
themselves: `curl -su … localhost:3000/api/v1/provisioning/alert-rules | jq length`.

A *default* address is not a third option: shipping one is how these rules spent
months delivering to a placeholder at an IANA-reserved domain, sent from our own
verified sending domain — the same sender reputation that carries user
verification links. Which is also why `.env.prod.example` ships this variable
**empty** — as, since HD-200, it ships every value that something guards: `${…:?}`
fires on unset and on empty and *not* on "non-empty and wrong", so a placeholder
interpolates cleanly and reinstates the exact defect. This variable was the first
row of that rule rather than an exception to it.

| Rule | Condition | For | Severity |
|---|---|---|---|
| AppDown | `up{job="hamstrack-app"} < 1` | 2m | critical |
| PostgresDown | `pg_up < 1` | 2m | critical |
| HighErrorRate | 5xx ratio > 5% | 5m | critical |
| HighLatency | HTTP p95 > 1s | 10m | warning |
| DiskFilling | host filesystem < 15% free | 10m | critical |
| EmailFailures | `increase(hamstrack_email_sent_total{outcome="failure"}[15m]) > 0` | 0m | warning |
| JVMHeapPressure | heap used/max > 90% | 10m | warning |
| HostMemoryLow | `node_memory_MemAvailable_bytes < 209715200` (200 MiB, per host) | 10m | critical |
| HostSwapInUse | `node_memory_SwapTotal_bytes - node_memory_SwapFree_bytes > 134217728` (128 MiB, per host) | 15m | warning |
| HostKernelOOMKill | `increase(node_vmstat_oom_kill[15m]) > 0` (per host) | 0m | critical |
| RoleScopeViolation | `increase(hamstrack_role_scope_violation_total[15m]) > 0` | 0m | warning |
| StatementBudgetExceeded | `increase(hamstrack_db_statement_budget_exceeded_total[15m]) > 5` | 5m | warning |
| DatabaseConnectionAcquisitionFailing | `increase(hamstrack_db_connection_acquisition_failed_total[15m]) > 5` | 5m | warning |
| BackupStale | `time() - hamstrack_backup_last_success_timestamp_seconds > 93600` (26 h, per `stage`) | 15m | critical |
| BackupRunFailed | `hamstrack_backup_last_status < 1` (per `stage`) | 5m | warning |
| ConfigDrift | `hamstrack_config_drift > 0` (per `scope`) | 30m | warning |
| DeployImagePinned | `hamstrack_deploy_image_pinned > 0` (per `tag`) | 6h | warning |
| MailDailyVolumeHigh | `sum(increase(hamstrack_email_sent_total{outcome="success"}[24h])) > 500` | 30m | warning |
| InviteVolumeUnaccepted | 6h invitation acceptance ratio < 10% **and** > 200 invitations sent in 6h | 30m | warning |
| InviteThrottleTripping | `sum(increase(hamstrack_ratelimit_hit_total{kind=~"invite_.*"}[15m])) > 20` | 5m | warning |
| AuthMailThrottleTripping | `sum(increase(hamstrack_ratelimit_hit_total{kind=~".*_recipient_(cooldown|window|daily)"}[15m])) > 20` | 5m | warning |
| MailRecipientVolumeCapReached | `sum(increase(hamstrack_ratelimit_hit_total{kind=~".*_recipient_(window|daily)"}[6h])) > 10` | 15m | warning |
| MailRecipientConcentration | `max(hamstrack_mail_anonymous_recipient_max) > 20` | 15m | warning |
| MailConcentrationGaugeStale | `max(hamstrack_mail_anonymous_recipient_max_age_seconds) > 1800` | 10m | warning |
| ExpensiveReadSurfaceSaturated | `sum(rate(hamstrack_ratelimit_hit_total{kind="expensive_read_surface_full"}[15m])) > 0.005` | 15m | warning |
| ExpensiveReadPermitForceReleased | `sum(rate(hamstrack_expensive_read_permit_force_released_total[15m])) > 0.003` | 15m | warning |

**`EmailFailures` counts two different things, and the difference decides what you do**
(HD-207/HD-208). A row in `failed_email` with `attempts >= 1` was *tried* and refused by the
SMTP host — look at the mail provider. A row with `attempts = 0` and a `last_error` beginning
`NEVER ATTEMPTED` was **never sent at all**, and the bracketed token that follows says which of
the three ways: `[QUEUE_FULL]` the mail queue was full, `[POOL_SHUT_DOWN]` the dispatch arrived
while the pool was stopping, `[SHUTDOWN_RESIDUE]` a deploy's shutdown drain expired with the
message still queued. That token is what a `GROUP BY left(last_error, 40)` sorts a burst by —
the sentence after it is written for a human reading one row, not a hundred. The second kind
means the pool is saturated or a deploy landed on a backlog, not that mail is broken, and it
is much more likely to succeed if re-driven. The rule's own `description` annotation carries
the query that separates them, so a paged operator does not need this page. Both share
`outcome="failure"` deliberately — a mail that never left is still a mail that failed, and a
tag of its own would be a silent loss with this alert pointed away from it.

**And this rule got noisier in 0.18.0, in exchange for a loss.** It is `> 0` with `for: 0m`,
so now that an abandoned mail queue is *recorded* rather than discarded at shutdown, **any
deploy landing on a non-empty queue fires it** — before, the same deploy was quiet and the
mail was simply gone. One burst at a deploy timestamp, all never-attempted, is that: real
(those users were told to check an inbox nothing arrived in), but not an outage. A rate that
continues after the deploy is.

**The invitation rules are ranked, and the ranking is the point** (HD-190). A rule
built on throttle refusals can only see an attacker who *hits* a ceiling; one who reads
the configured numbers and stays beneath them trips nothing and never appears in
`hamstrack_ratelimit_hit_total`. So `InviteThrottleTripping` is listed last on purpose —
anyone who builds only that rule has built a monitor for the incompetent. What stays true
whichever ceiling did or did not fire is the **volume** of mail leaving the box
(`MailDailyVolumeHigh`) and the **proportion of invitations anybody accepts**
(`InviteVolumeUnaccepted`): real invitations get accepted and spam does not, and nothing
else in the metric surface separates the two. `InviteVolumeUnaccepted` has a **named false
positive** — a genuine large onboarding started on a Friday evening — which is written into
its own annotation, because a rule whose false positive is a surprise gets muted within a
week and a rule that explains its own survives.

**The auth mailers get three rules, and the reason is that the obvious one cannot see the
attack they exist to stop** (HD-202). `AuthMailThrottleTripping` is built on refusals like
`InviteThrottleTripping` and inherits the same blind spot — but here the blind spot is not a
corner case, it is the **optimal play**. To keep a victim's bucket full an attacker sends
exactly as slots age out, spaced past the cooldown: five requests a window, *every one of
them allowed*. Nothing is refused, so the counter stays at zero; the only refusals generated
are the victim's own one or two attempts, one or two hits against a threshold of twenty.
That rule therefore fires for a noisy mail bomb and **never** for a sustained single-target
denial of recovery, which is precisely what `AuthMailProperties` names as the failure mode.

It is also the one refusal in the product with no second witness. Every other throttle tells
its caller it fired, so a user complaint eventually arrives; `POST /auth/forgot-password` and
`POST /auth/resend-verification` must answer identically for a registered and an unregistered
address, so a refusal there returns the usual `200`, the usual sentence and no log line.
Weakest witness and sole witness at once.

Hence the other two. **`MailRecipientVolumeCapReached`** is the same counter at a tenth of
the threshold over eight times the window, restricted to the *volume* kinds — a
`_recipient_window` / `_recipient_daily` hit means one inbox took its whole allowance for the
period, which needs sustained volume or several senders and is abnormal at any instance size,
so it can fire far earlier than a rule that must tolerate ordinary cooldown noise.
**`MailRecipientConcentration`** is not built on refusals at all: it reads
`hamstrack_mail_anonymous_recipient_max`, a quantity that is true whichever ceiling did or did
not fire, and it is the only rule here that sees the paced attacker. Its summary carries the
`mail_send_events` query that names the address, because the metric may not.

**The selectors are naming conventions, not lists.** They used to enumerate the four kinds
this feature shipped with, so a fifth silent mailer would have tripped nothing and failed no
test — silence squared, given that the counter is the only witness.
`MailThrottleCoverageTest.everyRecipientKindIsSelectedByTheAlertRules` now reads the regexes
out of this file and fails if any policy declares a kind none of them matches.

**On a self-hosted install with no observability stack there is neither of these**, since it
is optional and the management port is not published. `docs/self-hosting.md` carries the two
things that still work: reading the counters out of the container
(`docker compose exec app wget -qO- http://localhost:9090/actuator/prometheus | grep ratelimit_hit`
— `wget`, because the `eclipse-temurin:21-jre-alpine` app image has no `curl`, which is also why
the compose healthcheck uses it)
and the `mail_send_events` query, which needs nothing at all. Note the asymmetry —
`mail_send_events` records *allowed* sends, so it sees the paced attacker the counters miss,
while a *cooldown* refusal writes no row and no log line and has no witness whatsoever
without a metrics scrape.

**`HostMemoryLow`, `HostSwapInUse` and `HostKernelOOMKill` watch the host, and they exist
because `JVMHeapPressure` cannot see the failure that actually threatens a small box**
(HD-189). An
app container with **no** `mem_limit` takes `-XX:MaxRAMPercentage` against *host* RAM, so
the JVM is entitled to a heap the machine can never give it: the kernel picks an OOM victim
and `SIGKILL`s it (exit `137`, no stack trace, nothing in any application log) long before
heap used/max reaches 90%, and the heap rule stays green throughout. Which state a given box is in is a
question for the box, never for a compose file and never for a variable in `.env`:

```bash
docker inspect "$(docker compose ps -q app)" --format '{{.HostConfig.Memory}}'   # 0 means NO LIMIT
```

Resolve the container rather than naming it: the container's name is
`<compose-project>-app-1`, taken from the directory the compose file sits in, so a
hard-coded `hamstrack-app-1` answers `No such object` on any install that cloned somewhere
else.

**The heap the JVM actually resolved is in the application's own log, once per start**
(HD-179) — the one signal here that is neither a declaration nor a reading of a different
process:

```bash
docker compose logs app | grep "Memory: max heap"
# Memory: max heap 512 MB = 536870912 bytes (MaxHeapSize; derived from -XX:MaxRAMPercentage=50,
# no -Xmx); GC SerialGC; container memory limit 1024 MB; app.reports.max-rows=20000
```

It names the resolved maximum in bytes — HotSpot's `MaxHeapSize`, so it compares digit for
digit with `java -XX:+PrintFlagsFinal -version`; the word `Runtime.maxMemory` there instead
means the JVM would not state `MaxHeapSize` and the figure is usable heap, which can read
slightly below it — whether that maximum came from an explicit `-Xmx` or was derived from a
percentage, **the garbage collector**, the container limit the JVM can see (`none` there
means the percentage was taken against *host* RAM — the state `JVMHeapPressure` cannot see
and `HostMemoryLow` exists for), and `REPORTS_MAX_ROWS`, which is costed in bytes against
that heap.

The collector is on that line because the container limit selects it: at `1g` the JVM is
below its "server-class machine" threshold and ergonomically picks **SerialGC**, at `2g` it
picks **G1** (measured, same image and flags). A stop-the-world single-threaded collector is
the likeliest explanation of the 4.99 s pause in `ops/loadtest/RESULTS-2026-08-31.md`, and
no dashboard panel here would have told you which one was running.
`jvm_memory_max_bytes{area="heap"}` is the heap figure as a series when you want it on a
dashboard; the log line is what an operator has during an incident, before Grafana is open.

**The two thresholds are absolute bytes rather than percentages, on purpose.** A percentage
stops meaning anything the moment the instance is resized: 90% of 1909 MB and 90% of
4096 MB are different amounts of danger, while the buffer a Linux box needs — GC headroom
plus enough page cache to keep Postgres off the disk — is roughly constant and does not
scale with the machine. 200 MiB is derived from measurement and the derivation is written
into the rule's own annotation: it sits *below* the 227 MB 7-day minimum observed on
production, so it would not have fired once in the week it was taken from, and it fires only
when the box is worse than it has ever been. **The three rules name three points on one
failure** — approaching the wall, already past it, and one that has already happened. Swap
**at a low `vm.swappiness`** is an emergency
buffer, not a memory tier, so sustained swap usage means RAM is already gone; that is a
capacity signal (`warning`, go and resize) rather than an outage, because the box survives,
which is the entire reason the swapfile is there. An install with **no** swap reports
`SwapTotal = SwapFree = 0`, so `HostSwapInUse` is silently inert there and `HostMemoryLow`
is the only warning that box will get — without swap the same pressure arrives as a kill
rather than as a slowdown, and the only rule that speaks after that is `HostKernelOOMKill`.

**`HostSwapInUse` assumes a *low* `vm.swappiness`** — the Hamstrack Cloud box runs `10`, so
sustained swap use there means RAM is genuinely gone. At the distro default of `60` a
perfectly healthy Linux box pages out cold anonymous memory and can sit above 128 MiB
indefinitely, and then this rule is mis-tuned rather than right. Run `sysctl vm.swappiness`
on your host; if it says 60, either lower it or raise the threshold in
`observability/grafana/provisioning/alerting/rules.yml`. The rule's own annotation says the
same thing, but an annotation is only read by somebody who has already been paged — i.e.
after the false positive, which is one too late.

**`HostKernelOOMKill` reports the event the other two only circle.** One predicts an OOM
kill and the other describes the state that precedes one; nothing else in the stack says
one *occurred*. It is the only thing that ever will: the kernel's victim dies on `SIGKILL`
and leaves **exit `137` and no stack trace** — no Java exception, no application log line,
no Loki entry to search for — so the evidence an operator is otherwise left with is a
container that restarted and a gap, which reads exactly like a deploy. **`137` is any
`SIGKILL`** (a timed-out `docker stop` that escalated, a `docker kill`, a `kill -9`, a
daemon or Docker Desktop shutdown), so on its own it does not mean the kernel chose the
victim; `docker inspect <container> --format '{{.State.OOMKilled}}'` is what separates an
OOM kill from a stop, and the long form of that trap is in
[`docs/ops-prod-hardening.md`](ops-prod-hardening.md) §5.3. That is a second reason this
rule is the dependable one: it counts `node_vmstat_oom_kill`, the event itself, and no
other cause of a `SIGKILL` can move that counter. The rule is threshold-free
(`> 0` on a counter of an event the kernel already decided), so there is nothing to tune
away and nothing to re-derive after a resize, it is **independent of `vm.swappiness`** and
therefore immune to the caveat above, and it is inert by construction on a healthy box —
the counter never moves. `for: 0m` is deliberate and its reasoning is not the usual one:
`for:` exists to require that a *level* persists, and this is a **counter of a past
event**, which does not become truer by lasting. The 15-minute range window already
carries the persistence, so a longer `for:` would only delay news of something already
over — and would do it on the one box where a scrape gap is likely, since node-exporter is
not immune to the OOM killer either, and a gap resets a pending alert. The counter is
host-wide and does not name the victim; `dmesg -T | grep -i 'killed process'` does, and
once `mem_limit` is delivered (HD-199) a container killed at its *own* limit lands in the
same counter — which is wanted, because a limit set too low should report itself rather
than look like a restart loop.

`MailDailyVolumeHigh`'s threshold of 500/day is calibrated against **Hamstrack Cloud's**
mail provider quota (3000 messages a month, Resend's free tier) — a sixth of the month's
allowance in a day. **A self-hosted install relaying through its own SMTP has a different
quota, or none**, and 500 a day may be entirely ordinary there; if it is, change the number
in `observability/grafana/provisioning/alerting/rules.yml`. Alert thresholds in this stack
are provisioned files rather than environment variables, deliberately — there is nothing to
set in `.env`.

None of these can name a tenant, an account or an address: the cardinality rule forbids it.
The bridge is a log line, not a label — every allowed send writes one INFO line carrying the
sender id, the workspace id and **the recipient's domain only**, never the local part. One
abuser mailing four hundred domains and one customer onboarding four hundred colleagues at
one domain are indistinguishable in Prometheus and trivially distinguishable in that line;
the `mail_send_events` table answers "which account" for `INVITE_EVENT_RETENTION_DAYS` days
after the fact. Refusals are deliberately **metrics and not log lines**, so a client that
keeps retrying cannot become a log-flooding vector.

Grafana's SMTP reuses your `MAIL_*` settings (see below).

**The backup rules are two because a backup has two failures and only one of them is
loud.** A `pg_dump` that fails is over in seconds and reports itself. A dump that
**succeeds** and then fails to upload leaves a perfectly good file on the volume that is
about to be lost, with every local check green — that is the failure that quietly turns a
backup system into a folder, and it is why the metric carries a `stage` label.
**BackupRunFailed** is the fast one (a stage reported failure). **BackupStale**
is the slow, load-bearing one, and it is the only rule that can see the job **not running
at all** — a stopped timer, a hung lock, a box that was down — because a job that does not
run reports nothing for BackupRunFailed to look at. Both are left **unaggregated**, so
Grafana raises one instance per stage and the notification names which; for BackupStale that
is not cosmetic but the difference between working and not on a self-hosted install, where
`BACKUP_TARGET=local` means the `upload` series is never written and the `dump` stage is the
only thing a staleness rule can watch. `severity: critical` matches
AppDown/PostgresDown/DiskFilling: "there is no second copy of the data" belongs in the same
class as "there is no first copy". The 26-hour threshold is one daily period plus about
1h50m of slack, so one slow run, a reboot or a `Persistent=true` catch-up does not page
anybody while two consecutive missed days cannot hide.

**`noDataState: OK` on both is deliberate, and it has a price worth knowing.** It is what
keeps the rules dormant on an install that runs this stack without the backup timer — the
metric simply does not exist there, and an alert about a mechanism you never installed is
noise. The cost is that **deleting the `.prom` file, or losing node-exporter's bind mount,
silences the alert instead of firing it**. Installing the job writes zero-timestamp sentinel
**state files** into `/var/lib/hamstrack-backup/`, so the first run to execute at all
publishes a series that is instantly stale rather than a healthy-looking one — but note what
that does not do: the **series** appears only once the script has run once and written a
`.prom`, which is why every install procedure here ends with a manual run rather than with
enabling the timer. Confirming the series is still present is a step of the periodic restore
check in [self-hosting.md](self-hosting.md#verify-a-restore).

**ConfigDrift and DeployImagePinned carry the same `noDataState: OK` and one extra gap that
is peculiar to them.** Their metrics are published by *two* things — the hourly
`hamstrack-config-drift.timer` and the tail of every deploy — so on a box where the timer
has not been installed the series **exists and looks healthy**, while being only as fresh as
the last deploy: an edit made afterwards is invisible until the next one. Nothing about a
zero says which of those two states produced it, which is the entire reason
`hamstrack_config_check_timestamp_seconds` is published. An uninstalled check is silent, and
silence here is not health.

**To try these two rules before they matter, use the dev stack.**
`docker-compose.observability.dev.yml` provisions the same rule file and now runs
node-exporter with `--collector.textfile.directory` pointed at `./observability/textfile`.
Hand-write a `.prom` there — `hamstrack_backup_last_status{stage="dump"} 0`, or a
`hamstrack_backup_last_success_timestamp_seconds{stage="upload"} 1` for a stale one — wait
for a scrape, and watch Grafana → Alerting. `*.prom` in that directory is gitignored.

**StatementBudgetExceeded is the only way this condition can reach you.** A statement the
database cancelled at `DB_STATEMENT_TIMEOUT_MS` answers **`422`**, deliberately — it is a
refusal the server decided, not a fault, and 5xx is the class intermediaries auto-retry. The
consequence is that it is invisible to **HighErrorRate**, which watches the 5xx ratio: an
instance can refuse every report it is asked for and stay green on every other rule here.
That is why the counter exists, and it is the whole reason this rule does.

**Rate-based, not `> 0`, and the difference is deliberate.** Unlike RoleScopeViolation above
— where no request can produce the condition, so any occurrence is a bug — a single `422`
here is an ordinary user asking a large tenant for more than ten seconds of work, and paging
on that would train an operator to ignore the rule. What matters is the *sustained* rate:
more than five in fifteen minutes, held for five, means the instance has outgrown an index
(or its heap — 0.17.0 bounded that too, and a smaller heap makes the same query slower). The
`route` label names which endpoint, and the matching WARN line names the budget. Two
remedies, in order: check the heap first on a host of 4 GB or more, then raise
`DB_STATEMENT_TIMEOUT_MS` — and raise `DB_POOL_MAX_SIZE` with it, since a longer bound means
one request holds one connection for longer. If the reason you are raising the pool is that
reports and searches need more of it, check `EXPENSIVE_READ_MAX_IN_FLIGHT` with it: that is the
number deciding how much of the pool the expensive-read surface may ever hold. **Whether a bigger
pool widens it by itself depends on whether the variable is pinned** — unset, the share is derived
from the pool at every boot (60 % of it, capped at 6), so taking the pool from 6 to 10 takes the
share from 3 to 6 on the next restart and there is nothing to change. Pinned, the number is yours
and the pool cannot move it, so raise it deliberately — and note it must stay strictly below the
pool, so raise the pool first.

**RoleScopeViolation is a data-integrity alert, not a load one.** It fires when a stored
`role_id` — on `workspace_members`, `project_members`, `workspace_invites` or a
`default_project_role_id` column — names a role of the wrong scope or of another
workspace. No request can create such a row (every write door resolves role ids through
`RoleRepository.findAssignable(id, workspaceId, scope)`), so a non-zero value means a bad
migration, a hand-written `UPDATE`, or a genuine authorization bug — never traffic. **Any
increase at all is worth looking at**, hence the `> 0` threshold and the `for: 0m`. The
`source` label names the table so the offending rows are one query away; the matching
`ERROR` log line names the role id. On the list endpoints the condition is survivable
(the entry renders with no role rather than 404ing the whole page), which is exactly why
it needs an alert: without one it is invisible except as a permanent ERROR trickle.

---

## Running it

### Cloud (hosted)

In Cloud the stack is **always on** and wired into the deploy — you don't run
compose by hand.

1. **Config ships from the repo, at the commit that was built.** The deploy (GitHub
   Actions → AWS SSM) fetches the repository tree for that commit and applies the paths in
   [`ops/deploy/synced-paths.txt`](../ops/deploy/synced-paths.txt) — which include
   `docker-compose.observability.yml` and the whole `observability/` directory — before
   bringing the stack up with **both** compose files. So a merged change to a dashboard, a
   datasource or an alert rule reaches the box with the next release, and
   `observability/` is replaced **wholesale**: a file an operator drops in there is gone at
   the next deploy. Until 2026-08-26 none of that was true — the deploy synced no files at
   all, and prod's compose files were dated 11 July and 6 August. Two things follow that a
   reader here needs:
   - **A bind-mounted config is read at container start, so replacing the file is not a
     change compose can see.** Grafana's *service definition* does not change when its
     provisioning directory does, and `up -d` correctly does nothing — while the container
     goes on serving the **deleted inode** of the file that was replaced, and both drift
     scopes read 0 because both of them are right. The deploy therefore restarts the
     services that bind-mount a changed path — **grafana, prometheus, loki and alloy**, not
     Grafana alone — whenever `observability/` is among them. By hand, after editing a file
     on the box: `docker restart hamstrack-grafana-1` (see
     [ops-prod-hardening §6.3](ops-prod-hardening.md#63-putting-the-job-on-the-box) step d).
   - **`.env` is still never synced**, so every value in item 2 remains an operator step.
2. **One-time server prerequisites** in `/opt/hamstrack/.env` — secrets do not come from a
   public repository, so these stay an operator step and must be in place **before** the
   deploy that first needs them:
   - `GF_SECURITY_ADMIN_PASSWORD` — **required** (Grafana fails fast without it,
     aborting the whole `docker compose` command).
   - `OBS_ALERT_EMAIL_TO` — **required**. An unset or empty value aborts the whole
     `docker compose` invocation, and since HD-199 it aborts it **at deploy time, on a
     staging copy**: the applier resolves every compose file it will run against this `.env` before
     replacing anything, so a missing value is a red deploy naming the variable rather than
     a stopped site. What it protects against is worth knowing, because it is silent —
     without the guard, an empty value leaves Grafana with no alert rules at all (not
     merely no email) and crash-looping, while `up -d` exits `0`. See
     [Alerts](#alerts).
   - Optional: `PROMETHEUS_RETENTION_TIME`/`_SIZE`, `LOKI_RETENTION_PERIOD`, and
     `DB_MONITOR_USER`/`DB_MONITOR_PASSWORD` (see the pg_monitor note below).
3. **Access Grafana over AWS SSM** (SSH port 22 is closed on the Cloud host):
   ```bash
   aws ssm start-session --region eu-north-1 --target <INSTANCE_ID> \
     --document-name AWS-StartPortForwardingSession \
     --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'
   ```
   Then open `http://localhost:3000` and log in with `GF_SECURITY_ADMIN_USER`
   (default `admin`) / `GF_SECURITY_ADMIN_PASSWORD`. Full runbook:
   [ops-prod-hardening §4](ops-prod-hardening.md#4-observability--reaching-grafana-over-ssm).

### DC (self-hosted)

In DC the stack is **opt-in** — the app logs and exposes metrics regardless, but
you choose whether to run Grafana/Loki/Prometheus.

**Prerequisites:** your compose must define services named **`app`** and
**`postgres`** on a shared network (the sample compose in
[self-hosting.md](self-hosting.md#quick-start) does), the app must be reachable on
its management port `9090` **inside** the network (do **not** publish 9090), and
you need the `docker-compose.observability.yml` file plus the `observability/`
config directory next to your compose file (both are in the repo).

Enable it by layering the file:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

> **Always pass both `-f` files together** on every `up`/`pull`. Running
> `up --remove-orphans` with only your base file would delete the observability
> containers.

Set in your `.env`:

```
GF_SECURITY_ADMIN_PASSWORD=<a strong password>   # required
OBS_ALERT_EMAIL_TO=<an inbox you read>           # required (see Alerts)
GF_SMTP_ENABLED=true                              # optional — false = evaluate, don't deliver
GF_SERVER_ROOT_URL=http://localhost:3000          # optional — external URL for links
LOKI_RETENTION_PERIOD=168h                        # optional
PROMETHEUS_RETENTION_TIME=15d                     # optional
PROMETHEUS_RETENTION_SIZE=2GB                     # optional
```

> **No SMTP at all, and you only want dashboards?** You still have to give
> `OBS_ALERT_EMAIL_TO` an address — the contact point cannot exist without one, and
> the compose file will not start without it. Use any address you own and add
> **`GF_SMTP_ENABLED=false`**. The rules are then provisioned, evaluate on schedule
> and show their state in Grafana → Alerting; Grafana simply sends nothing, so the
> address is never used. That is the "evaluate without delivery" mode — it lives at
> the SMTP switch, not at the contact point.

**Access Grafana** — it binds `127.0.0.1:3000` on the host and is **not** public.
Tunnel to it, e.g. over SSH:

```bash
ssh -L 3000:localhost:3000 you@your-server
# then browse http://localhost:3000
```

Do **not** expose Grafana directly to the internet. If you must, put it behind
your reverse proxy with authentication and TLS.

### Dev (local, app from an IDE)

When you run the app from an IDE (IntelliJ) it's a host process, not a container,
so the docker-socket log collector can't see it. Use the **dev** stack, which
tails the app's JSON **log file** instead.

1. In your run configuration, add env vars so the app also writes JSON to a file:
   ```
   LOGGING_STRUCTURED_FORMAT_FILE=logstash
   LOGGING_FILE_NAME=logs/hamstrack.json
   ```
   (Console stays human-readable. Optional: `LOGGING_STRUCTURED_JSON_ADD_DEPLOYMENT=dev`.)
2. Start the dev stack:
   ```bash
   docker compose -f docker-compose.observability.dev.yml up -d
   ```
   It runs Loki + Alloy (file-tail) + Grafana + Prometheus + exporters, wired for
   a host-run app (`prometheus.dev.yml` scrapes `host.docker.internal:9090`;
   postgres-exporter → `host.docker.internal:15432`). Grafana admin is
   `admin/admin` (loopback only; do **not** copy this to a server).
3. Run the app, then open `http://localhost:3000` → Dashboards → Hamstrack.

**Docker-Desktop / WSL2 caveats (dev only — fine on a real Linux host):** the
per-container CPU/RAM panels stay empty (cAdvisor can't resolve container names
under WSL2), and the host-disk panel is limited (the dev node-exporter mounts
`/proc`+`/sys` only). Logs, app/JVM/HTTP metrics and Postgres all work.

---

## Configuration reference

All env-driven. App-instrumentation vars apply in both Cloud and DC; stack vars
matter only when you run the observability compose file.

| Variable | Default | Scope | Notes |
|---|---|---|---|
| `LOG_LEVEL` | `INFO` | app | root log level |
| `LOG_LEVEL_APP` | `INFO` | app | `com.hamstrack` log level |
| `MANAGEMENT_PORT` | `9090` | app | actuator/metrics port — **never publish or proxy it** |
| `MANAGEMENT_ENDPOINTS` | `health,prometheus,info` | app | exposed actuator endpoints — keep minimal, never `*` |
| `METRICS_EXPORT_ENABLED` | `true` | app | Prometheus registry on/off |
| `GF_SECURITY_ADMIN_PASSWORD` | — | stack | **required** when the stack runs (fail-fast) |
| `GF_SECURITY_ADMIN_USER` | `admin` | stack | Grafana admin username |
| `LOKI_RETENTION_PERIOD` | `168h` | stack | how long logs are kept |
| `PROMETHEUS_RETENTION_TIME` | `15d` | stack | metrics retention (time) |
| `PROMETHEUS_RETENTION_SIZE` | `2GB` | stack | metrics retention (size cap) |
| `OBS_ALERT_EMAIL_TO` | — | stack | **required** when the stack runs (fail-fast, aborting the whole `docker compose` command); alert email recipient — an empty value removes the alert *rules*, not just the email, and leaves Grafana crash-looping |
| `GF_SMTP_ENABLED` | `true` | stack | Grafana alert delivery on/off. `false` = rules still evaluate, nothing is sent (the no-SMTP self-hosted mode) |
| `GF_SERVER_ROOT_URL` | `http://localhost:3000` | stack | external URL Grafana builds links with; the default matches the port-forward the runbooks assume |
| `DB_MONITOR_USER` / `DB_MONITOR_PASSWORD` | — | stack | postgres-exporter login; falls back to `DB_USERNAME`/`DB_PASSWORD` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | — | app + stack | app email **and** Grafana alert SMTP. `MAIL_FROM` is the alert *sender* and deliberately keeps its default (see the comment on `GF_SMTP_FROM_ADDRESS` in the compose file) |

**Least-privilege DB access for the exporter (recommended for prod):** create a
read-only monitoring role and point the exporter at it instead of the app user:

```sql
CREATE ROLE hamstrack_exporter LOGIN PASSWORD '<a strong password>';
GRANT pg_monitor TO hamstrack_exporter;
```
```
DB_MONITOR_USER=hamstrack_exporter
DB_MONITOR_PASSWORD=<a strong password>
```

The two lines are one credential written twice, so they must match — generate it
(`openssl rand -base64 24`) rather than filling in something these pages could have
handed you. A password printed in a public repository opens a `pg_monitor` login on the
production database to anyone who reads it, which is `DB_PASSWORD=DB_PASSWORD` in another
dialect. This is also why the placeholder is written `<a strong password>`: it reads as a
blank to fill in, where `a-strong-password` reads as a value that has already been chosen.

---

## Security model

- **Nothing observability-related is public.** Only Caddy 80/443 → app 8080 is
  reachable from the internet. Management port 9090, Prometheus, Loki, the
  exporters and Grafana are internal to the compose network; Grafana additionally
  binds loopback only.
- **Actuator is unreachable on the public port** — `/actuator/**` on 8080 returns
  404 (SPA fallback exclusion); metrics/health live only on internal 9090.
- **Grafana**: admin password is fail-fast (`${...:?}`), sign-up disabled,
  analytics/update-checks off; reached only via SSM (Cloud) or a tunnel (DC).
- **Alloy** mounts the docker socket **read-only** — it can read container
  metadata/logs but not control the daemon. (Hardening follow-up:
  docker-socket-proxy.)
- **No PII / unbounded cardinality in metrics** — labels are bounded enums only;
  the only free label, `issues.created{type}`, is an admin-catalog value, not
  user-supplied.
- **App health does not depend on SMTP** (`management.health.mail.enabled=false`)
  — a mail outage won't flap the health endpoint; email problems surface through
  the `hamstrack_email_sent_total{outcome="failure"}` metric and the EmailFailures
  alert.
- **Retention caps** bound how long logs/metrics (and any sensitive fields in
  them) live.
- **Operator misconfig to avoid:** publishing 9090, proxying `/actuator` in Caddy,
  setting `MANAGEMENT_ENDPOINTS=*` (exposes `env`/`heapdump`/`threaddump`), or
  exposing Grafana publicly without auth. None of these are in the shipped config.

---

## Operations & troubleshooting

| Symptom | Likely cause & fix |
|---|---|
| Grafana `localhost:3000` refused | Stack not up / images still pulling. `docker compose ... ps`; wait for `grafana` to be healthy. Cloud: ensure the SSM port-forward session is running. |
| Grafana login fails | `GF_SECURITY_ADMIN_PASSWORD` changed in the UI ≠ env. Reset: `docker exec <grafana> grafana cli admin reset-admin-password <pw>`. |
| Prometheus target `DOWN` | Check the target: `hamstrack-app` needs the app reachable on `:9090` inside the network (don't publish it, don't set `management.server.address`); `postgres` needs valid `DB_MONITOR_*`/`DB_*`. |
| No logs in Loki | App not emitting JSON (needs `cloud`/`dc` profile in prod, or the dev file env vars) / Alloy can't read the docker socket. |
| Alert emails not arriving | `MAIL_*` (Grafana SMTP) misconfigured, `GF_SMTP_ENABLED=false` (delivery deliberately off), or the address in `OBS_ALERT_EMAIL_TO` is wrong. Before blaming SMTP, check that the rules and the routing exist: Grafana → Alerting → Alert rules, and `/api/v1/provisioning/policies` → `{"receiver":"email","provenance":"file"}` (see [Alerts](#alerts)). A **lost** root policy is worse than a broken one — Grafana's unmanaged built-in `email receiver` points at a live third-party domain. |
| Grafana keeps restarting; `up -d` said nothing was wrong | `up -d` exits 0 even when a container crash-loops. An empty `OBS_ALERT_EMAIL_TO` reaching Grafana makes alerting provisioning fail and Grafana exit 1 on every restart. `docker compose … ps` shows it; `docker compose … logs grafana` has `Failed to provision alerting … could not find addresses in settings`. |
| `up` aborts immediately | A fail-fast var is missing — `GF_SECURITY_ADMIN_PASSWORD`, `OBS_ALERT_EMAIL_TO` (or `DB_USERNAME`/`DB_PASSWORD`). The error names the variable; set it in `.env`. Note this aborts the **entire** command, including `down`/`stop`/`ps`/`logs` with both `-f` files — but nothing is created, changed or stopped, so a running stack is unaffected. |
| Container panels empty (dev) | Docker-Desktop/WSL2 limitation (see the dev caveats). Works on a real Linux host. |
| Disk filling from telemetry | Lower `LOKI_RETENTION_PERIOD` / `PROMETHEUS_RETENTION_TIME` / `_SIZE`. |
| `node_textfile_scrape_error{}` is `1`, or a `hamstrack_backup_*` or `hamstrack_config_*` series stops updating | node-exporter found a **malformed** `.prom` file in the textfile directory and skipped it, so whatever wrote it is publishing nothing while looking installed. It has no alert rule of its own on purpose — the writer replaces the file atomically (temp file in the same directory, then `mv`), which makes a half-written scrape nearly impossible, and a rule per near-impossibility is how a rule set becomes background noise. Check the directory listed in `--collector.textfile.directory`; `promtool check metrics < the-file.prom` names the bad line. A file that is *absent* rather than malformed leaves the error at `0` and the series simply missing — which is why neither `BackupStale` nor `ConfigDrift` can see a deleted `.prom`. |

Never run `docker compose -f docker-compose.prod.yml up --remove-orphans` with the
observability stack running — always include `-f docker-compose.observability.yml`,
or it deletes the obs containers.

---

## Extending (add a dashboard or metric)

- **Dashboard:** drop a Grafana dashboard JSON into
  `observability/grafana/dashboards/` (datasource UID `prometheus` or `loki`). The
  file provider auto-loads it within 30s; on prod it reaches the box with the next
  deploy (`observability/` is a synced path), which also restarts Grafana — a provisioning
  change needs that, because `up -d` cannot see inside a bind mount. Editing the file on the
  box yourself does not restart anything: that is your `docker restart` to run.
- **Business metric:** add a meter in `common.observability.ProductMetrics` and
  emit it from the relevant service **after the entity is saved**. Keep labels
  bounded (enums) — never label by user/workspace/issue id or email.
- **Alert:** add a rule to
  `observability/grafana/provisioning/alerting/rules.yml` (query A → threshold
  expression B → `condition: B`).
