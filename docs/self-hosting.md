# Self-hosting Hamstrack (DC)

> This is the home for self-hosting docs — the project [README](../README.md)
> only points here. It tracks the current release; if something drifts from the
> app's behavior, the [`.env.prod.example`](../.env.prod.example) template and
> `application.properties` are the source of truth.

**Audience:** anyone running their own Hamstrack instance (the **DC**
deployment model) — on a VPS, a company server, or a homelab. If you just want
to *use* Hamstrack without operating it, use the hosted Cloud at
[hamstrack.com](https://hamstrack.com) instead.

Hamstrack ships as a single Docker image plus PostgreSQL. It runs the same core
as Cloud; the differences are config/profile-gated (`SPRING_PROFILES_ACTIVE=dc`).

## Contents

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [TLS & reverse proxy](#tls--reverse-proxy)
- [Email (SMTP)](#email-smtp)
- [First run & the admin account](#first-run--the-admin-account)
- [Attachment storage](#attachment-storage)
- [Optional toggles](#optional-toggles)
- [Observability (optional)](#observability-optional)
- [Upgrading](#upgrading)
- [Backups](#backups)
- [Troubleshooting](#troubleshooting)
- [REST API](#rest-api)

## Requirements

- Docker with the Compose plugin.
- A PostgreSQL 16 database (the sample compose runs one for you).
- For a public instance: a domain and a TLS-terminating reverse proxy (Caddy,
  nginx, Traefik…). HTTP-only on `localhost` works for trying it out.
- **Resources:** the app is a JVM service — budget ~1 GB RAM for it (2 GB is
  comfortable) plus a little for PostgreSQL; a 2 vCPU / 2 GB host comfortably
  runs a small team. Disk is dominated by attachments — size the
  `attachments_data` volume (or your S3 bucket) for expected uploads.

## Quick start

Pin a released image line (`:0.4`), not `latest`:

```yaml
# docker-compose.yml
services:
  app:
    image: ghcr.io/zherikhov/hamstrack:0.4
    environment:
      SPRING_PROFILES_ACTIVE: dc
      DB_URL: jdbc:postgresql://postgres:5432/hamstrack
      DB_USERNAME: hamstrack
      DB_PASSWORD: change-me
      JWT_SECRET: change-me-to-a-random-string-of-32-plus-bytes
      APP_BASE_URL: https://tracker.example.com
      # SMTP — required for email verification (which doubles as login):
      MAIL_HOST: smtp.example.com
      MAIL_PORT: "587"
      MAIL_USERNAME: tracker@example.com
      MAIL_PASSWORD: change-me
      MAIL_SMTP_AUTH: "true"
      MAIL_STARTTLS: "true"
    ports:
      - "8080:8080"
    volumes:
      - attachments_data:/app/data/attachments
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/api/meta"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s   # Spring Boot startup grace
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: hamstrack
      POSTGRES_USER: hamstrack
      POSTGRES_PASSWORD: change-me
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hamstrack -d hamstrack"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

volumes:
  postgres_data:
  attachments_data:
```

```bash
docker compose up -d
```

Browse your instance at its `APP_BASE_URL`, reached through the TLS proxy you put
in front (see [TLS & reverse proxy](#tls--reverse-proxy)). Public self-registration
is **closed by default** on self-hosted installs, so set `SEED_ADMIN_EMAIL` +
`SEED_ADMIN_PASSWORD` to create your first administrator on startup (see
[First user](#first-user)). The schema is created and migrated automatically on
startup (Flyway).

> **Trying it out locally without a proxy?** Set `APP_BASE_URL=http://localhost:8080`
> and open that. With an `https` base the session cookie is `Secure` and won't
> survive plain HTTP — so an `https` base requires actually serving HTTPS.

### Keeping secrets in a `.env` file

Rather than hard-coding secrets in `docker-compose.yml`, keep them in a `.env`
file next to it and load it with `env_file`:

```yaml
  app:
    image: ghcr.io/zherikhov/hamstrack:0.4
    env_file: .env
    # ...only non-secret / internal values remain inline
```

Docker Compose also substitutes `.env` values into the compose file itself (e.g.
`${DB_PASSWORD}`). [`.env.prod.example`](../.env.prod.example) is a starting
template — it targets the fuller reverse-proxy stack, so use the subset of
variables that matches your setup (see [Configuration](#configuration)). Keep
`.env` out of version control.

## Configuration

All configuration is via environment variables; [`.env.prod.example`](../.env.prod.example)
is a template to crib from (it's owner-oriented — take the subset you need). Full reference:

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | — | `dc` (self-hosted) or `cloud` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | — | PostgreSQL connection (required) |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `5` | HikariCP pool sizing; raise the max for concurrency, keep (max × replicas) under Postgres `max_connections` |
| `JWT_SECRET` | — | HMAC key for access tokens, **min 32 bytes** (required) |
| `JWT_ACCESS_TOKEN_TTL` | `PT30M` | Access-token lifetime (ISO-8601 duration). Short by design — the refresh cookie renews it. Longer = a leaked token is replayable for longer |
| `APP_BASE_URL` | `http://localhost:8080` | Public URL; used in emails, cookies (`Secure` when https), robots/sitemap |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` / `MAIL_FROM` | localhost:1025 | Outgoing SMTP (verification, invites, password reset) |
| `MAIL_SMTP_CONNECT_TIMEOUT_MS` / `MAIL_SMTP_READ_TIMEOUT_MS` / `MAIL_SMTP_WRITE_TIMEOUT_MS` | `5000` / `10000` / `10000` | SMTP socket timeouts (ms) — a black-holed mail host fails a worker fast instead of hanging (connect / per-read / per-write) |
| `MAIL_ASYNC_CORE_POOL` / `MAIL_ASYNC_MAX_POOL` / `MAIL_ASYNC_QUEUE_CAPACITY` | `2` / `5` / `100` | Bounded async mail executor — mail can't starve other `@Async` work or spawn a thread-per-task under an SMTP stall (`CallerRunsPolicy` backpressure) |
| `MAIL_CRITICAL_MAX_ATTEMPTS` / `MAIL_CRITICAL_RETRY_BACKOFF_MS` | `3` / `2000` | CRITICAL mail (verification + password reset) retries with backoff, then dead-letters to `failed_email`; invite mail stays best-effort |
| `STORAGE_TYPE` | `local` (dc) / `s3` (cloud) | Attachment storage backend |
| `STORAGE_LOCAL_DIR` | `./data/attachments` | Local storage path (mount a volume) |
| `STORAGE_S3_BUCKET` / `STORAGE_S3_REGION` / `STORAGE_S3_ENDPOINT` / `STORAGE_S3_PATH_STYLE` / `STORAGE_S3_ACCESS_KEY` / `STORAGE_S3_SECRET_KEY` | — | S3 or S3-compatible storage (MinIO etc.); empty keys fall back to the AWS default credentials chain |
| `STORAGE_S3_CONNECT_TIMEOUT_MS` / `STORAGE_S3_READ_TIMEOUT_MS` / `STORAGE_S3_API_CALL_TIMEOUT_MS` / `STORAGE_S3_API_CALL_ATTEMPT_TIMEOUT_MS` | `3000` / `20000` / `30000` / `10000` | S3 client timeouts (ms), only when storage type is `s3`: TCP connect / per socket-read / total per-request budget / per single attempt |
| `BOARD_MAX_ISSUES` | `500` | Max issues a single board query returns — a guard against unbounded board loads on very large projects. Valid range 1–20000 (20 000 is the most assembled issues one unpaged response may build — the same budget the agile planning view is held to, see `AGILE_MAX_OPEN_SPRINTS` below); an out-of-range value fails startup instead of being clamped — a `0` would otherwise bind quietly and return an empty board everywhere |
| `MAX_LABELS_PER_ISSUE` | `20` | Max labels attachable to a single issue — a DoS guard; a payload above it is rejected with 422. Valid range 1–100; an out-of-range value fails startup instead of being clamped |
| `MAX_LABELS_PER_WORKSPACE` | `1000` | Max labels in one workspace's catalog. Any member may create labels, so this bounds what every picker, `/search/schema` and HQL name resolution loads; creating past it is a 422. Valid range 1–100000 |
| `MAX_COMPONENTS_PER_PROJECT` | `500` | Max components in one project's catalog. Creation needs a project curator, but anyone can create their own workspace and curate every project in it, and HQL name resolution loads each visible project's whole component catalog on every search; creating past the cap is a 422. Valid range 1–100000 |
| `MAX_VERSION_LINKS_PER_ISSUE` | `20` | Max versions linkable to a single issue **per link type** — this many fix versions and, independently, this many affects versions; a payload above it is rejected with 422. Valid range 1–100; an out-of-range value fails startup instead of being clamped |
| `MAX_VERSIONS_PER_PROJECT` | `500` | Max versions in one project's catalog — same reasoning as `MAX_COMPONENTS_PER_PROJECT`: curator-gated creation is no volume barrier, HQL name resolution loads each visible project's version names on every search, and the versions list endpoint is unpaged. Archived and released versions count toward the cap; creating past it is a 422. Valid range 1–100000 |
| `AGILE_SECTION_MAX_ISSUES` | `300` | Max issues rendered per section of the planning view (`GET …/backlog`) — one section per open sprint plus the backlog. Together with `AGILE_MAX_OPEN_SPRINTS` this bounds the response size; a truncated section still reports honest whole-section totals. Valid range 1–2000, but **not independently attainable** — see the joint bound on `AGILE_MAX_OPEN_SPRINTS` below (at the default 20 open sprints this caps out around 952, not 2000). An out-of-range value fails startup instead of being clamped |
| `AGILE_MAX_OPEN_SPRINTS` | `20` | Max FUTURE + ACTIVE sprints in one project (COMPLETED ones are history and cannot be started, so they don't count); creating past the cap is a 422. Valid range 1–100; an out-of-range value fails startup instead of being clamped. **Also validated jointly with `AGILE_SECTION_MAX_ISSUES`:** their product `(this + 1) × section cap` must stay ≤ 20 000, since one `GET …/backlog` assembles that many issues in a single unpaged response — both at their individual maxima would be ~202 000 rows, so startup fails rather than OOM later |
| `AGILE_DEFAULT_SPRINT_LENGTH_DAYS` | `14` | Default iteration length — the end date a sprint start assumes when the request carries none. Valid range 1–90; an out-of-range value fails startup instead of being clamped |
| `AGILE_MAX_BULK_MOVE` | `100` | Max issue ids accepted in one "move to sprint" request; beyond it the request is rejected with 400 and the client chunks it. Valid range 1–500; an out-of-range value fails startup instead of being clamped |
| `ATTACHMENT_MAX_FILE_SIZE` | `20MB` | Per-file size limit enforced in-app (the business limit; kept app-side so a future admin setting can tune it). Must stay ≤ `ATTACHMENT_MAX_UPLOAD_SIZE` |
| `ATTACHMENT_MAX_UPLOAD_SIZE` | `25MB` | Hard servlet/DoS ceiling (multipart parse limit). Match your reverse-proxy body limit to this |
| `ATTACHMENT_ALLOWED_EXTENSIONS` | (images, pdf, office, text, zip…) | Comma-separated allow-list of uploadable file extensions (case-insensitive) |
| `PUBLIC_SIGNUP_ENABLED` | `false` | Self-registration is **closed by default** on self-hosted installs — create accounts in the Admin console (Users → New user → share the setup link, no email needed). Set `true` to let anyone register |
| `PUBLIC_LANDING_ENABLED` | `true` | `false` hides the public landing page (`/` redirects to login, crawlers disallowed) |
| `TERMS_ACCEPTANCE_REQUIRED` | `true` | `false` removes the required terms checkbox at registration |
| `DEMO_SEED_ON_FIRST_LOGIN` | `true` | `false` disables the demo workspace seeded on a user's first login |
| `RATE_LIMIT_ENABLED` (+ `RATE_LIMIT_AUTH_IP_PER_MINUTE`, `RATE_LIMIT_LOGIN_FAILURE_THRESHOLD`, `RATE_LIMIT_LOGIN_BACKOFF_BASE_SECONDS`, `RATE_LIMIT_LOGIN_BACKOFF_MAX_SECONDS`) | `true` (15 / 5 / 30 / 900) | Brute-force protection on auth endpoints: per-IP budget + per-account login backoff, `429` + `Retry-After` |
| `SEED_ADMIN_EMAIL` / `SEED_ADMIN_DISPLAY_NAME` / `SEED_ADMIN_PASSWORD` | — | Optionally create/promote a system administrator on startup (access to the `/admin` console) — **both** email and password required |

Each variable is wired to a Spring property via a placeholder in
`application.properties` (e.g. `DB_URL` → `spring.datasource.url`, `MAIL_HOST` →
`spring.mail.host`, `APP_BASE_URL` → `app.base-url`). The names above are the
supported configuration surface — prefer them over setting Spring properties directly.

## TLS & reverse proxy

Hamstrack serves plain HTTP on `8080`. Put a TLS-terminating reverse proxy in
front and point `APP_BASE_URL` at the public HTTPS URL.

**Caddy** (add to the compose stack — it gets Let's Encrypt certs automatically;
drop the app's `ports: 8080:8080` so it's only reachable through Caddy):

```yaml
  caddy:
    image: caddy:2-alpine
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
    depends_on:
      app:
        condition: service_healthy   # wait for the app healthcheck (no 502 window)
    restart: unless-stopped
volumes:
  caddy_data:
```

```caddy
# Caddyfile
tracker.example.com {
    reverse_proxy app:8080
}
```

**nginx** (host-level, app published on `127.0.0.1:8080`):

```nginx
server {
    listen 443 ssl;
    server_name tracker.example.com;
    # ssl_certificate ... ; ssl_certificate_key ... ;
    client_max_body_size 25m;          # match ATTACHMENT_MAX_UPLOAD_SIZE
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Two things to get right behind a proxy:

- **`APP_BASE_URL` scheme drives the session cookie.** The `refresh_token`
  cookie (HttpOnly, `SameSite=Strict`, path `/api/auth` — the only cookie, and
  strictly necessary) is marked `Secure` **iff `APP_BASE_URL` starts with
  `https`**. So serve HTTPS end-to-end with an `https://` base; use an `http://`
  base only for a plain-HTTP LAN/localhost instance. A mismatch (https base but
  users reach plain http) drops the cookie and no one stays logged in.
- **Pass `X-Forwarded-For`.** The auth rate limiter and access logs read the
  client IP from the right-most `X-Forwarded-For` entry (else the socket peer),
  so the proxy must set it — otherwise every visitor buckets under the proxy IP.

## Email (SMTP)

Email verification **doubles as login**, so a working SMTP server is required
for a usable instance — without it, no one can complete registration.

Any SMTP server works — a transactional provider (Resend, Amazon SES, Postmark,
Mailgun…) or your own relay. Configure:

- `MAIL_HOST` / `MAIL_PORT` — the SMTP endpoint (`587` for STARTTLS submission).
- `MAIL_USERNAME` / `MAIL_PASSWORD` — credentials (for many providers the
  password is an API key), with `MAIL_SMTP_AUTH=true` and `MAIL_STARTTLS=true`.
- `MAIL_FROM` — the From address. Use a domain you've verified with SPF/DKIM, or
  messages land in spam.

Verification, invite and password-reset links point at `APP_BASE_URL`, so set it
correctly or the links won't work.

**Timeouts & delivery reliability** (all optional; defaults shown). SMTP socket
timeouts stop a black-holed mail host from hanging a worker; the async executor
and CRITICAL-mail retry settings bound how mail is dispatched:

| Variable | Default | Meaning |
|---|---|---|
| `MAIL_SMTP_CONNECT_TIMEOUT_MS` | `5000` | TCP connect timeout to the SMTP host |
| `MAIL_SMTP_READ_TIMEOUT_MS` | `10000` | per-read (server response) timeout |
| `MAIL_SMTP_WRITE_TIMEOUT_MS` | `10000` | per-write (send) timeout |
| `MAIL_ASYNC_CORE_POOL` | `2` | steady-state mail-sender threads |
| `MAIL_ASYNC_MAX_POOL` | `5` | max mail-sender threads under load |
| `MAIL_ASYNC_QUEUE_CAPACITY` | `100` | queued mails before backpressure (`CallerRunsPolicy`) |
| `MAIL_CRITICAL_MAX_ATTEMPTS` | `3` | verification/reset send attempts before dead-lettering to `failed_email` |
| `MAIL_CRITICAL_RETRY_BACKOFF_MS` | `2000` | base backoff between CRITICAL-mail retries |

Only verification + password-reset mail is CRITICAL (retried, then
dead-lettered); invite mail stays best-effort with no retry.

**Local testing:** run [MailHog](https://github.com/mailhog/MailHog) (SMTP on
`1025`, web UI on `8025`) and set `MAIL_HOST=mailhog`, `MAIL_PORT=1025` (no auth).
Every message shows up in the UI instead of being delivered — these are also the
built-in defaults, so with no `MAIL_*` set the app expects MailHog on localhost.

## First run & the admin account

### First user (the administrator)

Public self-registration is **closed by default** on self-hosted installs
(`PUBLIC_SIGNUP_ENABLED=false`), so there is no "click Register" first step —
you seed the first **system administrator** at startup instead:

```
SEED_ADMIN_EMAIL=admin@example.com
SEED_ADMIN_PASSWORD=<a strong password>
SEED_ADMIN_DISPLAY_NAME=Admin          # optional, defaults to "Admin"
```

**Both** email and password are required — a blank email skips seeding entirely.
On boot the account is created (or an existing user with that email is promoted)
to system admin; it's idempotent, so the variables are safe to leave set. Log in
with those credentials — "System administration" appears in the top-bar user
menu → `/admin`. The `/admin` console holds the global catalog (statuses,
priorities, issue types, custom fields, workflows and their project bindings)
**and** the user directory.

### Adding more users

In the `/admin` console open **Users → New user**, enter a name, email and role.
No email is sent: you get a one-time **setup link** (`/reset-password?token=`,
valid 7 days) — copy it and hand it to the person over your own channel. They
open it, choose a password, and sign in. Regenerate the link anytime, and
disable or promote accounts from the same screen.

> Prefer open registration? Set `PUBLIC_SIGNUP_ENABLED=true` and anyone can
> register + verify their email themselves (SMTP required for the verification
> mail). New accounts are regular users until an admin promotes them.

## Attachment storage

### Local disk (default for DC)

`STORAGE_TYPE=local`. Files live under `STORAGE_LOCAL_DIR` (default
`/app/data/attachments` in the container) — mount a volume there so they survive
restarts (the sample compose mounts `attachments_data`) and back it up like any
data volume. Storage keys are server-generated; original filenames live only in
the database.

### S3 / S3-compatible

`STORAGE_TYPE=s3`, plus:

| Variable | Purpose |
|---|---|
| `STORAGE_S3_BUCKET` / `STORAGE_S3_REGION` | target bucket + region |
| `STORAGE_S3_ENDPOINT` / `STORAGE_S3_PATH_STYLE` | only for S3-compatible stores (MinIO, Ceph…): the endpoint URL and `true` for path-style addressing |
| `STORAGE_S3_ACCESS_KEY` / `STORAGE_S3_SECRET_KEY` | static credentials; **leave empty** to use the AWS default chain (env vars, `~/.aws`, instance/task role) |
| `STORAGE_S3_CONNECT_TIMEOUT_MS` (`3000`) / `STORAGE_S3_READ_TIMEOUT_MS` (`20000`) | TCP connect timeout / per socket-read timeout, in ms — a large attachment streams over one socket, so read is a per-read ceiling, not the whole transfer |
| `STORAGE_S3_API_CALL_TIMEOUT_MS` (`30000`) / `STORAGE_S3_API_CALL_ATTEMPT_TIMEOUT_MS` (`10000`) | total per-request budget incl. SDK retries / budget per single attempt, in ms — bound a hung S3 rather than block a request thread indefinitely |

The four `STORAGE_S3_*_TIMEOUT_MS` values only take effect when the effective
storage type is `s3`; local-disk storage ignores them.

MinIO example:

```
STORAGE_TYPE=s3
STORAGE_S3_BUCKET=hamstrack
STORAGE_S3_REGION=us-east-1
STORAGE_S3_ENDPOINT=https://minio.example.com
STORAGE_S3_PATH_STYLE=true
STORAGE_S3_ACCESS_KEY=...
STORAGE_S3_SECRET_KEY=...
```

### Upload size + file-type limits

Two layers guard uploads:

- **`ATTACHMENT_MAX_UPLOAD_SIZE`** (default `25MB`) is the hard servlet ceiling —
  the multipart parser rejects anything larger at parse time (DoS guard). If you
  raise it, bump your reverse proxy's body limit to match (nginx
  `client_max_body_size`); Caddy has no default limit.
- **`ATTACHMENT_MAX_FILE_SIZE`** (default `20MB`, must stay ≤ the ceiling) is the
  per-file business limit, enforced in the app so a future in-app admin setting
  can tune it without a redeploy.
- **`ATTACHMENT_ALLOWED_EXTENSIONS`** is a comma-separated, case-insensitive
  allow-list of uploadable file extensions. Uploads outside it are rejected with
  `415`. The stored content-type is derived from the filename (never the client
  header), so a spoofed/malformed type can't break downloads.

## Optional toggles

DC operators can disable Cloud-oriented behavior:

| Variable | Effect |
|---|---|
| `PUBLIC_SIGNUP_ENABLED=true` | re-opens public self-registration (closed by default on DC — admins create accounts via the Users console) |
| `PUBLIC_LANDING_ENABLED=false` | hides the public landing page (`/` → login, crawlers disallowed) |
| `TERMS_ACCEPTANCE_REQUIRED=false` | removes the required terms checkbox at registration |
| `DEMO_SEED_ON_FIRST_LOGIN=false` | disables the demo workspace seeded on first login |
| `RATE_LIMIT_ENABLED` (+ tuning vars) | brute-force protection on auth endpoints |

Rate-limit tuning (all optional; defaults shown):

| Variable | Default | Meaning |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | master switch |
| `RATE_LIMIT_AUTH_IP_PER_MINUTE` | `15` | per-IP request budget/min across login, register, verify, resend, forgot & reset |
| `RATE_LIMIT_LOGIN_FAILURE_THRESHOLD` | `5` | failed logins for one account before backoff starts |
| `RATE_LIMIT_LOGIN_BACKOFF_BASE_SECONDS` | `30` | first backoff delay (doubles on each further failure) |
| `RATE_LIMIT_LOGIN_BACKOFF_MAX_SECONDS` | `900` | backoff cap (15 min); a success resets the counter |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | `false` | Key the per-IP budget on the rightmost `X-Forwarded-For` entry. Enable **only** behind a trusted proxy that strips client-supplied XFF; if the app port is directly reachable, leaving this `false` stops clients spoofing XFF to bypass the per-IP limit. The bundled `docker-compose.prod.yml` forces it `true` (all traffic goes through Caddy, which strips XFF). |

The limiter is **in-memory / single-node**. If you run multiple app replicas it
applies per-node (there's no shared store yet), so keep it in mind when scaling out.

The same caveat applies to the **backlog rank-rebalance cooldown** (the 429 with
`Retry-After` on `POST …/issues/{number}/rank`): it is node-local, so N replicas
allow up to N whole-project rebalances per cooldown window instead of one. It
degrades safely — the operation is idempotent and the throttle only damps an
abuse vector — and a restart re-arms the window rather than locking planners out.

## Observability (optional)

> Full operator guide (Cloud + DC, backend internals, metric reference, alerts,
> dev setup, security): **[docs/observability.md](observability.md)**. This is the
> DC quick version.

Hamstrack always logs to stdout — in the `dc` profile as **structured JSON, one
object per line** (`docker compose logs app` shows them; fields include `level`,
`logger`, `message`, `stack_trace` and `deployment=dc`). Tune verbosity with
`LOG_LEVEL` (root) and `LOG_LEVEL_APP` (the `com.hamstrack` package), both default
`INFO`.

For centralized logs with search and dashboards, the repo ships an **opt-in**
stack you layer on top of your compose file — [Grafana](https://grafana.com/) +
[Loki](https://grafana.com/oss/loki/) (log store) + [Alloy](https://grafana.com/docs/alloy/)
(collector that tails every container's stdout via the docker socket):

```bash
# from the dir holding docker-compose.prod.yml + the observability/ config dir
docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d
```

You need these files next to your compose file (they're in the repo):
`docker-compose.observability.yml` and the `observability/` directory
(`loki/loki-config.yml`, `alloy/config.alloy`, `grafana/provisioning/…`,
`grafana/dashboards/…`).

The same stack also collects **metrics** — [Prometheus](https://prometheus.io/)
scrapes host CPU/RAM/disk (node-exporter), per-container CPU/RAM (cAdvisor),
PostgreSQL (postgres-exporter) and the **app itself** (Spring Boot Actuator +
Micrometer: HTTP latency/throughput/errors, JVM heap/GC/threads, HikariCP pool).
The **App Overview**, **JVM & DB**, **Host & Containers**, **Postgres** and
**Product** (registrations, active users, issues/projects/workspaces created,
logins, invites, email, attachments) dashboards are auto-provisioned alongside
**Logs**. The app serves metrics on a
separate internal management port (`MANAGEMENT_PORT`, default `9090`) that is
**never published or proxied** — only in-network Prometheus reaches it.

Set in your `.env` (see [`.env.prod.example`](../.env.prod.example)):

| Variable | Default | Purpose |
|---|---|---|
| `GF_SECURITY_ADMIN_PASSWORD` | — | Grafana admin password (**required** when the stack runs) |
| `GF_SECURITY_ADMIN_USER` | `admin` | Grafana admin username |
| `LOKI_RETENTION_PERIOD` | `168h` | how long Loki keeps logs (7 days) |
| `PROMETHEUS_RETENTION_TIME` / `PROMETHEUS_RETENTION_SIZE` | `15d` / `2GB` | metrics retention (whichever is hit first) |

**postgres-exporter database login (least privilege).** By default the exporter
reuses your app `DB_USERNAME`/`DB_PASSWORD`, which is a read-write role — more
than it needs. Create a dedicated read-only monitoring role and point the
exporter at it via `DB_MONITOR_USER`/`DB_MONITOR_PASSWORD`:

```sql
-- run once against the hamstrack database
CREATE ROLE hamstrack_exporter LOGIN PASSWORD 'a-strong-password';
GRANT pg_monitor TO hamstrack_exporter;
```

Then set in `.env`:

```
DB_MONITOR_USER=hamstrack_exporter
DB_MONITOR_PASSWORD=a-strong-password
```

`pg_monitor` is a built-in PostgreSQL role granting read-only access to the
statistics views the exporter reads — no access to your table data. Leave
`DB_MONITOR_*` unset to fall back to the app credentials.

**Alerts** are provisioned too (AppDown, Postgres down, high 5xx rate, high
latency, disk filling, email-send failures, JVM heap pressure). Set
`OBS_ALERT_EMAIL_TO` to receive them by email — Grafana's SMTP reuses your
`MAIL_*` settings. Leave it empty to keep the rules evaluating (visible in
Grafana → Alerting) without email delivery.

**Nothing is exposed publicly.** Grafana binds `127.0.0.1:3000` on the host only;
Loki and Alloy publish no port at all. Reach Grafana by tunnelling to it (SSH
port-forward, `ssh -L 3000:localhost:3000 you@server`, or an SSM port-forward on
AWS — see [ops-prod-hardening §4](ops-prod-hardening.md#4-observability--reaching-grafana-over-ssm)),
then open `http://localhost:3000`. The Loki datasource and a **Logs** dashboard are
auto-provisioned.

> **Always pass both `-f` files together** on later `up`/`pull`. Running
> `up --remove-orphans` with only `docker-compose.prod.yml` would delete the
> observability containers.

Budget ~0.7–0.8 GB extra RAM for the full stack (Loki, Alloy, Grafana,
Prometheus + the three exporters). Application/JVM metrics and alerts are added
in later phases.

## Upgrading

Images are published to `ghcr.io/zherikhov/hamstrack` with these tags:

| Tag | Meaning |
|---|---|
| `0.4.3` | exact release — fully reproducible |
| `0.4` | latest patch of the 0.4 line — **recommended for production** |
| `latest` | the newest stable release, or the newest `main` build — whichever published last. Pre-releases (`0.14.0-rc1`) never move it, and neither does a tag on an older commit, so `latest` can legitimately sit *behind* the newest entry on the Releases page. A moving target that can jump mid-upgrade; **not for production** |

Pin a version in your compose (e.g. `:0.4`), then upgrade with:

```bash
docker compose pull && docker compose up -d
```

Database migrations run automatically on startup (Flyway) — no manual step. See
the [Releases](https://github.com/Zherikhov/easyTask/releases) page for notes
before upgrading across a minor version.

**Pin an exact patch** (`:0.4.3`) for maximum reproducibility — you then upgrade
deliberately by bumping the tag. `:0.4` instead auto-tracks the newest 0.4.x on
each `docker compose pull`.

**Downgrades are not supported.** Flyway migrations are forward-only, and a newer
release may change the schema in ways an older image can't read (`ddl-auto` is
`validate`, so it will refuse to start rather than corrupt data). Always take a
backup before a minor upgrade so you can roll back by restoring it.

## Backups

Two things to back up: the **PostgreSQL database** and **attachments**. They
reference each other, so capture them together and restore to a consistent point.

**Database** — logical dump:

```bash
docker compose exec postgres pg_dump -U hamstrack hamstrack > hamstrack-$(date +%F).sql
```

Restore into a running (empty) database:

```bash
docker compose exec -T postgres psql -U hamstrack hamstrack < hamstrack-YYYY-MM-DD.sql
```

(Or snapshot the `postgres_data` volume while the container is stopped.)

**Attachments** — with `STORAGE_TYPE=local`, back up the `attachments_data`
volume (files under `/app/data/attachments`); with `s3`, rely on your bucket's
versioning/backup. Take a backup **before every minor upgrade**.

## Troubleshooting

| Symptom | Likely cause & fix |
|---|---|
| App exits at startup with a JWT/key error | `JWT_SECRET` is shorter than 32 bytes — HMAC-SHA256 requires ≥32. Use a longer random string. |
| Registration never completes / no email arrives | SMTP misconfigured. Check `MAIL_*` and your provider; test locally with MailHog (`http://localhost:8025`). |
| Logged out immediately / can't stay signed in | `APP_BASE_URL` scheme doesn't match how users reach the app. The `refresh_token` cookie is `Secure` only with an `https` base — serve HTTPS end-to-end (https base) or use an `http` base for plain HTTP. |
| `502` right after `up` | The app is still starting (Spring Boot needs ~30–40 s; it has a healthcheck). Wait, or check `docker compose logs app`. |
| Attachment upload returns `500` | `STORAGE_TYPE=s3` without a valid bucket/region/credentials, or the local dir isn't writable. |
| Upload rejected (`413` / too large) | Over `ATTACHMENT_MAX_FILE_SIZE` (app limit) or `ATTACHMENT_MAX_UPLOAD_SIZE` (servlet ceiling); raise both, and the proxy body-size limit to match. |
| Upload rejected (`415` / type not allowed) | The file extension isn't in `ATTACHMENT_ALLOWED_EXTENSIONS` — add it (comma-separated, case-insensitive). |
| Everyone shares one IP / false `429`s | Behind a proxy/CDN that doesn't pass `X-Forwarded-For` (or passes an untrusted one). Ensure the proxy sets it; the app trusts the right-most entry. |
| Startup fails with a schema validation error after changing the image | You moved to an **older** image than the DB was migrated to. Use the newer image, or restore a pre-upgrade backup. |

## REST API

The HTTP API for a self-hosted instance is documented in
[api-dc.md](api-dc.md), and interactively at `/docs` (Swagger UI) on your
instance. The OpenAPI spec is served at `/openapi.yaml`.
