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
| **Alerts** (app/DB down, error rate, latency, disk, email failures, heap) | Grafana unified alerting | email (+ Grafana UI) |

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
| `hamstrack_ratelimit_hit_total` | counter | `kind`(ip_window/login_backoff) | rate-limit rejections |
| `hamstrack_workspaces_created_total` | counter | `source`(user/onboarding/demo) | workspaces created |
| `hamstrack_projects_created_total` | counter | — | projects created |
| `hamstrack_issues_created_total` | counter | `type`(issue-type name, from the admin catalog) | issues created |
| `hamstrack_invites_sent_total` / `_accepted_total` / `_declined_total` | counter | — | workspace invites |
| `hamstrack_email_sent_total` | counter | `type`(verification/password_reset/invite), `outcome`(success/failure) | outbound email |
| `hamstrack_attachments_uploaded_total` | counter | — | attachment uploads |
| `hamstrack_attachments_bytes_count` / `_sum` | summary | — | upload count / cumulative bytes stored |
| `hamstrack_users_total` / `hamstrack_users_active` | gauge | — | total users / ACTIVE users |
| `hamstrack_workspaces_total` / `hamstrack_projects_total` / `hamstrack_issues_total` | gauge | — | live totals |

Gauges are evaluated at scrape time via cheap `count` queries.

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
contact point when `OBS_ALERT_EMAIL_TO` is set; otherwise they still evaluate and
are visible in Grafana → Alerting.

| Rule | Condition | For | Severity |
|---|---|---|---|
| AppDown | `up{job="hamstrack-app"} < 1` | 2m | critical |
| PostgresDown | `pg_up < 1` | 2m | critical |
| HighErrorRate | 5xx ratio > 5% | 5m | critical |
| HighLatency | HTTP p95 > 1s | 10m | warning |
| DiskFilling | host filesystem < 15% free | 10m | critical |
| EmailFailures | `increase(hamstrack_email_sent_total{outcome="failure"}[15m]) > 0` | 0m | warning |
| JVMHeapPressure | heap used/max > 90% | 10m | warning |

Grafana's SMTP reuses your `MAIL_*` settings (see below).

---

## Running it

### Cloud (hosted)

In Cloud the stack is **always on** and wired into the deploy — you don't run
compose by hand.

1. **Config ships from the repo automatically.** The deploy (GitHub Actions → AWS
   SSM) downloads the repo-owned config for the built commit and layers both
   compose files:
   `docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml pull && up -d`.
   See [ops-prod-hardening §3](ops-prod-hardening.md#config-auto-sync-from-the-repo-2026-08-06).
2. **One-time server prerequisites** in `/opt/hamstrack/.env` (secrets can't come
   from the repo):
   - `GF_SECURITY_ADMIN_PASSWORD` — **required** (Grafana fails fast without it,
     aborting `up`).
   - Optional: `OBS_ALERT_EMAIL_TO`, `PROMETHEUS_RETENTION_TIME`/`_SIZE`,
     `LOKI_RETENTION_PERIOD`, and `DB_MONITOR_USER`/`DB_MONITOR_PASSWORD` (see the
     pg_monitor note below).
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
OBS_ALERT_EMAIL_TO=you@example.com               # optional (alert emails)
LOKI_RETENTION_PERIOD=168h                        # optional
PROMETHEUS_RETENTION_TIME=15d                     # optional
PROMETHEUS_RETENTION_SIZE=2GB                     # optional
```

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
| `OBS_ALERT_EMAIL_TO` | — | stack | alert email recipient; empty = evaluate but don't email |
| `DB_MONITOR_USER` / `DB_MONITOR_PASSWORD` | — | stack | postgres-exporter login; falls back to `DB_USERNAME`/`DB_PASSWORD` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | — | app + stack | app email **and** Grafana alert SMTP |

**Least-privilege DB access for the exporter (recommended for prod):** create a
read-only monitoring role and point the exporter at it instead of the app user:

```sql
CREATE ROLE hamstrack_exporter LOGIN PASSWORD 'a-strong-password';
GRANT pg_monitor TO hamstrack_exporter;
```
```
DB_MONITOR_USER=hamstrack_exporter
DB_MONITOR_PASSWORD=a-strong-password
```

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
| Alert emails not arriving | `OBS_ALERT_EMAIL_TO` unset, or `MAIL_*` (Grafana SMTP) misconfigured. |
| `up` aborts immediately | A fail-fast var is missing — usually `GF_SECURITY_ADMIN_PASSWORD` (or `DB_USERNAME`/`DB_PASSWORD`). Set it in `.env`. |
| Container panels empty (dev) | Docker-Desktop/WSL2 limitation (see the dev caveats). Works on a real Linux host. |
| Disk filling from telemetry | Lower `LOKI_RETENTION_PERIOD` / `PROMETHEUS_RETENTION_TIME` / `_SIZE`. |

Never run `docker compose -f docker-compose.prod.yml up --remove-orphans` with the
observability stack running — always include `-f docker-compose.observability.yml`,
or it deletes the obs containers.

---

## Extending (add a dashboard or metric)

- **Dashboard:** drop a Grafana dashboard JSON into
  `observability/grafana/dashboards/` (datasource UID `prometheus` or `loki`). The
  file provider auto-loads it within 30s; on prod it ships via the config
  auto-sync on the next deploy.
- **Business metric:** add a meter in `common.observability.ProductMetrics` and
  emit it from the relevant service **after the entity is saved**. Keep labels
  bounded (enums) — never label by user/workspace/issue id or email.
- **Alert:** add a rule to
  `observability/grafana/provisioning/alerting/rules.yml` (query A → threshold
  expression B → `condition: B`).
