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
  - [Statements are bounded from 0.17.0](#statements-are-bounded-from-0170)
  - [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170)
  - [Notifications are scoped to a workspace from 0.17.0](#notifications-are-scoped-to-a-workspace-from-0170)
  - [Duplicate accounts after an upgrade](#duplicate-accounts-after-an-upgrade-locale-dependent-email-folding)
- [Backups](#backups)
- [Troubleshooting](#troubleshooting)
- [REST API](#rest-api)

## Requirements

- Docker with the Compose plugin.
- A PostgreSQL 16 database (the sample compose runs one for you).
- For a public instance: a domain and a TLS-terminating reverse proxy (Caddy,
  nginx, Traefik…). HTTP-only on `localhost` works for trying it out.
- **Resources:** the app is a JVM service — budget ~1 GB RAM for it (2 GB is
  comfortable, and on a **4 GB host or larger** `APP_MEMORY_LIMIT=2g` is what
  actually hands it that second gigabyte — the default caps the container at 1 GB
  however big the host is; on a 2 GB host leave it alone, because that box has no
  second gigabyte to spare) plus a little for PostgreSQL; a 2 vCPU / 2 GB host
  comfortably runs a small team. That ~1 GB is what the container is actually
  limited to: the bundled compose sets `mem_limit: 1g` (`APP_MEMORY_LIMIT`) and the
  image sizes the heap at 50% of the container limit, so the default is a
  **512 MB heap** with the rest left for the JVM's non-heap memory. Give the
  container more and the heap follows — nothing to rebuild, one variable, and see
  [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170) for the sizing
  table and for what changes if you are upgrading rather than installing fresh.
  **If you write your own Compose file, set a memory limit on the app service**:
  with no limit the JVM sizes its heap against *host* RAM. Add the
  [observability stack](#observability-optional) and the arithmetic tightens
  sharply — its services' own limits sum to ~1 GB, so app 1 GB + observability
  ~1 GB + PostgreSQL + Caddy does not fit a 2 GB host; run it on 4 GB. Disk is
  dominated by attachments — size the `attachments_data` volume (or your S3
  bucket) for expected uploads.

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
    # Not optional: the image sizes the heap at 50% of the CONTAINER limit, so
    # without a limit here it sizes against host RAM. 1g → 512 MB heap.
    mem_limit: 1g
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
[First user](#first-user-the-administrator)). The schema is created and migrated automatically on
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
| `APP_MEMORY_LIMIT` | `1g` | Memory ceiling for the **app container**, read by Docker Compose (`mem_limit`) and never by the app — so it takes docker size suffixes (`1g`, `1536m`). **This is the heap dial**: the image runs the JVM with `-XX:MaxRAMPercentage=50`, i.e. half of the *container* limit, so `1g` here is a 512 MB heap and `2g` is a 1 GB heap. The other half is not slack — metaspace, thread stacks (Tomcat's request pool is capped at 200 threads by default, ~1 MB of stack each), the code cache, direct buffers and GC bookkeeping all live outside the heap, and squeezing them gets the container **OOM-killed by the kernel** (exit `137`, no stack trace) rather than the JVM throwing `OutOfMemoryError`. 512 MB is the reference heap `REPORTS_MAX_ROWS` below is costed against, so raising one is the occasion to re-read the other. **Upgrading from before 0.17.0 on a host bigger than 2 GB? The default is less heap than you had** — see [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170). **If you run your own Compose file rather than the bundled one, set a limit there too** — with no container limit the percentage is taken against *host* RAM, which is the situation this setting exists to end. **Half is the right split near `1g` and wasteful well above it**, because the non-heap need is largely *constant* rather than proportional (metaspace and the code cache do not grow with the heap): from `4g` up, pair the bigger limit with an explicit heap, `JAVA_TOOL_OPTIONS=-Xmx…` at roughly the limit minus ~700 MB (at `2g` the waste is only ~300 MB and a second setting is not worth it). **`-Xmx` is the only form that works** — `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` loses to the image's own copy of that flag, and the JVM logs `Picked up JAVA_TOOL_OPTIONS: …` in both cases, which says the variable was *read* and not that it was *applied*; the percentage form therefore looks like it worked. Unlike the app's own settings in this table, an **empty** value is harmless here — Compose reads it, not Spring, so `APP_MEMORY_LIMIT=` falls back to `1g` instead of stopping the boot. Identical in `dc` and `cloud`: how much memory a JVM may use is a property of the box it runs on, not of the plan |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | — | PostgreSQL connection (required) |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `5` | HikariCP pool sizing; raise the max for concurrency, keep (max × replicas) under Postgres `max_connections` |
| `DB_LOCK_TIMEOUT_MS` | `3000` | How long a transaction may wait for a row lock before giving up, in ms. **From 0.17.0 this applies to every transaction the app opens, not only to the few that lock deliberately** — so an ordinary edit queued behind a long-running change (removing a member with a lot of assigned work is the usual one) now fails after 3 s with a retryable `409` instead of waiting indefinitely. That is the point: it is issued together with `DB_STATEMENT_TIMEOUT_MS` below, because `statement_timeout` counts lock-wait time, and without it a contended write would be cancelled by *that* bound and answered `422` — a refusal that tells the caller not to retry when retrying is exactly what works. Raise it if legitimate edits collide often enough to be noticed. (This row used to name the handful of endpoints that locked on purpose; that list was wrong within one release and is now wrong by design.) Applied with `SET LOCAL` inside each transaction the app opens — **not** as a server-wide PostgreSQL `lock_timeout`, which is why Flyway migrations on the same pool are unaffected and still wait as long as they need: Flyway runs its own transactions and never goes through the app's transaction manager. Exceeding it is a retryable `409` + `Retry-After`, not a failure. Valid range 100–60000; out-of-range, `0` (PostgreSQL reads it as "wait for ever" — the behaviour this setting exists to remove) or **blank** fails startup instead of being clamped, so `DB_LOCK_TIMEOUT_MS=` does not disable the line, it stops the boot — remove the line to get the default. **From 0.17.0 the usable top of that range is lower than 60000**: `DB_STATEMENT_TIMEOUT_MS` must stay at least twice this value, so at its default of `10000` this one may not exceed **5000**. Raising it past that stops the boot naming both properties — raise the statement bound in the same edit |
| `DB_STATEMENT_TIMEOUT_MS` | `10000` | How long **any one statement** of an application transaction may run before PostgreSQL cancels it, in ms. Applied with `SET LOCAL` to every transaction the app opens — there is no list of covered endpoints, because the cost of a statement is a property of how much data you have and not of which feature issued it. **Flyway is deliberately not covered**: migrations run their own transactions, and an index build or a table rewrite on a large install legitimately takes minutes. Exceeding it answers `422` with `errorType: STATEMENT_BUDGET_EXCEEDED` and **no** `Retry-After` — an identical retry costs identical time — and logs a WARN naming this variable. It does **not** bound how long a *connection* is held: a transaction of many statements, or one that spends its time assembling a response in Java, can outlive this number. **New in 0.17.0, and on a large install it can turn a slow report, search or member removal into an error** — see [Statements are bounded from 0.17.0](#statements-are-bounded-from-0170) for a size-to-value table. Must be at least **2x `DB_LOCK_TIMEOUT_MS`** or the app refuses to start: `statement_timeout` counts lock-wait time too, so a smaller value would fire first and replace the retryable `409` above with a `422` that is not retryable. Valid range 1000-600000 — but the `2x` rule is the binding one in practice: **with the default `DB_LOCK_TIMEOUT_MS` of 3000 the smallest value that boots is 6000**, and `1000` is only reachable if you also lower the lock bound to 500 or less. `0` means "no bound" to PostgreSQL and is refused, and **blank** stops the boot exactly as `DB_LOCK_TIMEOUT_MS` does. **Raising this is not free:** every second you add is a second one request may hold one of your `DB_POOL_MAX_SIZE` connections, so the same pool serves fewer concurrent slow requests — past ~30 s, raise the pool with it. Identical in `dc` and `cloud` |
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
| `REPORTS_MAX_WINDOW_DAYS` | `365` | Widest window one report may span, in days, counting **both** endpoints. A wider request is refused with `400` naming this cap — it is **never silently narrowed**, because a chart of a window nobody asked for is exactly how a reporting feature earns "these numbers don't match what I expected". Valid range 1–3650; at daily buckets the window length *is* the number of points in the response, which is what the upper bound guards. Identical in `dc` and `cloud`: reporting depth is a product feature, not a plan feature. A value **under 90** is safe and is not a trap: the endpoint's own default window is `min(90, this)`, so the parameterless request the reports page makes on load is always inside the cap — what is never done is clamping a window a caller explicitly asked for. An out-of-range value fails startup instead of being clamped. **Leave the line out to get the default — `REPORTS_MAX_WINDOW_DAYS=` is an empty value, not an absent one, and it stops the boot rather than restoring 365**; that matters more here than elsewhere, because this number is quoted back to API callers inside a 400 |
| `REPORTS_MAX_ROWS` | `20000` | Most issue **rows** one report may materialise before it declares itself truncated — every report response carries `meta.truncated` + `meta.cap` and the UI prints it, so a cap never bites silently. It does not bite on the flow report, which aggregates in PostgreSQL and returns one row per bucket; it is the budget for the row-level reports (cycle time, aging WIP). Valid range 1–50000; an out-of-range value fails startup instead of being clamped. The ceiling is a byte budget wearing a row count: a shipped row costs roughly 1.9 KB of transient heap at worst (the JDBC row, the DTO and the buffered JSON are all alive at once), so 50000 is ~95 MB for one request — the previous ceiling of 200000 was ~380 MB, i.e. a documented value that could OOM the instance in a single GET. The ceiling is sized against a **reference heap of 512 MB**, and since `HD-152` that is the heap a default install actually gets rather than a premise it hopes for: the image runs the JVM at `-XX:MaxRAMPercentage=50` and the bundled compose limits the app container to `1g` (`APP_MEMORY_LIMIT`), which is 512 MB of heap exactly. So 50000 rows is ~95 MB of a 512 MB heap for one request — legal, large, and the reason the *default* is 20000 (~38 MB worst case) rather than the ceiling. The relationship still has to be maintained by whoever moves either number: the heap follows the container limit, so `APP_MEMORY_LIMIT=2g` doubles it to 1 GB and makes this ceiling correspondingly conservative, while a smaller limit — or your own Compose file with **no** `mem_limit`, where the percentage is taken against host RAM — moves it the other way. And nothing bounds *concurrency*: N users asking together is N of these alive together, so ~95 MB is a per-request figure and never a total. **Leave the line out to get the default — `REPORTS_MAX_ROWS=` is an empty value, not an absent one, and it stops the boot rather than restoring 20000** |
| `REPORTS_REQUESTS_PER_MINUTE` | `60` | How many report requests **one user** may make per minute across the whole reports surface (every `…/projects/{id}/reports/**` endpoint, not per report) **plus `POST …/workspaces/*/search/insights`**, the Insights panel — a report that lives on the search path and is bound to this limiter explicitly rather than by prefix. For the six project-scoped reports this is the only bound on the **work** a report does: `REPORTS_MAX_WINDOW_DAYS` bounds the response array, the "open at window start" balance is O(project history) whatever window you ask for, and `Cache-Control: private` means no shared cache absorbs a repeat — so without a budget one authenticated member in a loop can saturate `DB_POOL_MAX_SIZE` with entirely legal 200s. Past it the answer is `429` + `Retry-After`; a report is never narrowed or approximated to fit a budget. Counted in-memory per instance. Valid range 1–10000; there is **no "unlimited" value** — `0` fails startup, and the off switch is `RATE_LIMIT_ENABLED` (which disables every limiter in the app). Identical in `dc` and `cloud`. **Leave the line out to get the default — `REPORTS_REQUESTS_PER_MINUTE=` is an empty value, not an absent one, and it stops the boot rather than restoring 60**. Insights is the one exception to "only bound": it is additionally inside `SEARCH_REQUESTS_PER_MINUTE` below. It sits on the reports limiter deliberately, because removing that binding would raise the panel’s allowance to the search budget as a side effect — so **the lower configured value binds**, and lowering *either* property lowers the panel. |
| `SEARCH_REQUESTS_PER_MINUTE` | `120` | How many **search-surface** requests **one user** may make per minute across the whole `…/workspaces/*/search/**` path: `POST …/search`, `GET …/search/schema`, `GET …/search/suggest` and `POST …/search/insights` **and the whole `…/workspaces/*/filters/**` path** — every saved-filter operation, `GET` and `DELETE` included, since the binding is by path and not by method. Saved filters are on this budget because **HQL validation is search-surface work wherever it is mounted**: validating a filter builds the same resolution context `…/search/schema` pays for (roughly eight statements, including a workspace-wide label projection and a full member scan), so creating one with a deliberately invalid body was an unthrottled eight-query refusal loop. It is charged here rather than to the reports pot because a saved filter *is* a saved search. Saved filters (`…/workspaces/*/filters/**`) are on this budget too: validating a filter's HQL builds the same resolution context `…/search/schema` pays for (a workspace-wide label projection and a full member scan), so an invalid-body loop there was the same unthrottled cost wearing different clothes — and a saved filter is a saved search, done by the same person. Past it the answer is `429` + `Retry-After`. **Its own budget rather than the reports one** because a person typing in a search box legitimately fires several requests a minute and must not be starved to protect charts; 120 is roughly ten times ordinary SPA use. Search is not the cheap surface it looks like — a query may carry up to 50 leaf predicates, a `text ~` leaf is two unanchored, unindexable `LIKE`s over a TEXT column, and the endpoint runs the whole predicate **twice** per request (count, then page) — so until this existed the expensive door was the unthrottled one. Counted in-memory per instance, so N replicas allow up to N × the budget per user (it damps an abuse vector rather than enforcing an invariant, so a split budget is a weaker guard and never a wrong answer). Valid range 1–10000; there is **no "unlimited" value** — `0` fails startup, and the off switch is `RATE_LIMIT_ENABLED` (which disables every limiter in the app). Identical in `dc` and `cloud`. **Leave the line out to get the default — `SEARCH_REQUESTS_PER_MINUTE=` is an empty value, not an absent one, and it stops the boot rather than restoring 120**. Note that the Insights panel is inside **both** this budget and `REPORTS_REQUESTS_PER_MINUTE` above. It sits on the reports limiter deliberately, because removing that binding would raise the panel’s allowance to the search budget as a side effect — so **the lower configured value binds**, and lowering *either* property lowers the panel. |
| `ROLES_MAX_CUSTOM_PER_WORKSPACE` | `50` | Custom roles per workspace, counted across **both** scopes (workspace + project) with `built_in = false`; the 8 built-in templates belong to no workspace and never count. Creating past the cap is a 409 `ROLE_LIMIT_REACHED`. **A sprawl guard, never a licence check** — custom roles are a product feature, not a plan feature, so this is identical in `dc` and `cloud` and is never profile-gated. Valid range 1–500; an out-of-range value fails startup instead of being clamped. The count is taken under a row lock on the workspace, so the cap is exact rather than advisory — which also makes a duplicate one of the calls that can lose a lock race and answer a retryable `409` + `Retry-After` (bounded by `DB_LOCK_TIMEOUT_MS`) |
| `DEFAULT_PROJECT_ACCESS_MODE` | `OPEN` | Project-access mode a **newly created** workspace starts in. `OPEN` — everyone in the workspace can work in every project through its default role; add someone to a project only to give them a *different* role. `STRICT` — only people added to a project can change anything in it (everyone can still **see** every project: it narrows writes, never reads). Applies at creation only and **never moves an existing workspace** — change one in Workspace settings → General. Demo seeding uses the same code path, so `STRICT` gives you a strict demo workspace too. Identical in `dc` and `cloud`: access modes are a product feature, not a plan feature. An unrecognised value **aborts startup** rather than falling back |
| `ATTACHMENT_MAX_FILE_SIZE` | `20MB` | Per-file size limit enforced in-app (the business limit; kept app-side so a future admin setting can tune it). Must stay ≤ `ATTACHMENT_MAX_UPLOAD_SIZE` |
| `ATTACHMENT_MAX_UPLOAD_SIZE` | `25MB` | Hard servlet/DoS ceiling (multipart parse limit). Match your reverse-proxy body limit to this |
| `ATTACHMENT_ALLOWED_EXTENSIONS` | (images, pdf, office, text, zip…) | Comma-separated allow-list of uploadable file extensions (case-insensitive) |
| `PUBLIC_SIGNUP_ENABLED` | `false` | Self-registration is **closed by default** on self-hosted installs — create accounts in the Admin console (Users → New user → share the setup link, no email needed). Set `true` to let anyone register |
| `PUBLIC_LANDING_ENABLED` | `true` | `false` hides the public landing page (`/` redirects to login, crawlers disallowed) |
| `TERMS_ACCEPTANCE_REQUIRED` | `true` | `false` removes the required terms checkbox at registration |
| `DEMO_SEED_ON_FIRST_LOGIN` | `true` | `false` disables the demo workspace seeded on a user's first login |
| `RATE_LIMIT_ENABLED` (+ `RATE_LIMIT_AUTH_IP_PER_MINUTE`, `RATE_LIMIT_LOGIN_FAILURE_THRESHOLD`, `RATE_LIMIT_LOGIN_BACKOFF_BASE_SECONDS`, `RATE_LIMIT_LOGIN_BACKOFF_MAX_SECONDS`) | `true` (15 / 5 / 30 / 900) | Brute-force protection on auth endpoints: per-IP budget + per-account login backoff, `429` + `Retry-After` |
| `SEED_ADMIN_EMAIL` / `SEED_ADMIN_DISPLAY_NAME` / `SEED_ADMIN_PASSWORD` | — | Optionally create/promote a system administrator on startup (access to the `/admin` console) — **both** email and password required |

Every variable the **application** reads is wired to a Spring property via a
placeholder in `application.properties` (e.g. `DB_URL` → `spring.datasource.url`,
`MAIL_HOST` → `spring.mail.host`, `APP_BASE_URL` → `app.base-url`). A variable that
sizes the **container** rather than the application is read by Docker Compose and
never reaches the JVM (`APP_MEMORY_LIMIT`); its row says so, and it takes docker
units rather than Spring ones. The names above are the supported configuration
surface — prefer them over setting Spring properties directly.

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
to system admin; it's idempotent, so the variables are safe to leave set.

> **Upgrading from before 0.16.0 on a Turkish, Azeri or Lithuanian locale?** The
> "or promoted" half of that sentence depends on the address matching, and 0.16.0
> changed how addresses are folded — so the seeder can miss an existing admin and
> create a *second* one. See
> [Duplicate accounts after an upgrade](#duplicate-accounts-after-an-upgrade-locale-dependent-email-folding).
> A fresh install is unaffected.

Log in with those credentials — "System administration" appears in the top-bar
user menu → `/admin`. The `/admin` console holds the global catalog (statuses,
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
| `RATE_LIMIT_ENABLED` (+ tuning vars) | master switch for **every** in-memory limiter: brute-force protection on auth endpoints, the per-principal reports budget and the per-principal search budget |

Rate-limit tuning (all optional; defaults shown):

| Variable | Default | Meaning |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | master switch — turns off **all three** limiter families at once: the auth budgets in this table, `REPORTS_REQUESTS_PER_MINUTE` and `SEARCH_REQUESTS_PER_MINUTE` |
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

A third is the **per-principal reports budget** (`REPORTS_REQUESTS_PER_MINUTE`,
the 429 with `Retry-After` on `/api/workspaces/*/projects/*/reports/**` — and on
`/api/workspaces/*/search/insights`, the Insights panel, which is a report that
lives on the search path and is bound to this limiter explicitly rather than by
prefix). It is
node-local too, so N replicas allow up to N × the budget per user. It degrades
safely for the same reason the cooldown does — it damps an abuse vector rather
than enforcing an invariant, so a split budget yields a weaker guard and never a
wrong answer — and what it protects is the *per-instance* connection pool, which
N replicas also have N of. A restart re-arms the window. Note the key is the
**principal**, so the budget bounds one user, not one tenant: aggregate load
still scales with member count, which is the number to size against.

A fourth is the **per-principal search budget** (`SEARCH_REQUESTS_PER_MINUTE`,
the 429 with `Retry-After` on `/api/workspaces/*/search/**` — `POST …/search`,
`/search/schema`, `/search/suggest` and the Insights panel — plus saved filters
under `/api/workspaces/*/filters/**`, because validating a filter's HQL does the
same work `/search/schema` does). It is node-local on
the same terms and degrades the same way: N replicas allow up to N × the budget
per user, which weakens a guard rather than producing a wrong answer. It is a
separate pot from the reports budget on purpose — somebody typing in a search
box legitimately fires several requests a minute, and starving them to protect
charts would be the wrong trade.

**The Insights panel sits inside both budgets and spends both.** It sits on the
reports limiter deliberately, because removing that binding would raise the
panel’s allowance to the search budget as a side effect — so **the lower
configured value binds**, and lowering *either* property lowers the panel. If you
tune one of the two, check what it does to the panel.

A fifth mechanism is node-local, and it is the only one of the five that is a
**security** property: the **permission set of each role is cached in-process for
10 seconds**. An edit through `PATCH /api/workspaces/{wsId}/roles/{roleId}`
evicts that entry immediately *on the replica that served it*; every other
replica keeps answering from its own copy until that copy expires. So on a
multi-replica deployment a permission you just removed from a role can still be
honoured for up to ~10 s, plus the tail of any request that had already resolved
its permissions before the edit committed. Widenings have the same delay and
nobody minds; it is the revocation direction that is worth knowing about. A
single-instance deployment — which is what most self-hosted installs are — is
unaffected, because the one node that serves the edit is the one that evicts.

**Membership is not cached, and that is the more important half.** Moving a
person between roles (`PATCH …/members/{userId}`,
`PATCH …/projects/{pId}/members/{userId}`), deleting a role while reassigning
its holders (`DELETE …/roles/{roleId}?reassignToRoleId=`), switching a workspace
between Open and Restricted (`PATCH /workspaces/{wsId}`) and changing either
default project role (`PATCH /workspaces/{wsId}`,
`PATCH …/projects/{pId}/default-role`) all take effect on that person's very next
request, on every replica — the mode and both default columns are read from the
row on each request, not cached. That is worth knowing in the direction that
matters: switching a workspace to **Restricted** removes inherited write access
everywhere, immediately, with no ten-second tail. Permissions are never put in the
access token either, so nothing waits for a token to expire. **Only a change to a
role's _contents_ has a window** — if you need someone's access cut instantly,
change their role or remove them rather than editing the role they hold.

Two things worth knowing before this reaches you as a support ticket. It is a
**scale-out property, not a defect**: the cache is what keeps authorization at a
constant, query-free cost on every request, and the fix (a cross-node
invalidation channel) buys a ten-second window on an operation a workspace
performs a handful of times a year. And the symptom users report will overstate
it — the web UI renders its controls from a `myPermissions` payload it already
fetched, so somebody whose access was revoked may keep *seeing* a button well
past 10 s even though the API refuses the call behind it. Ask them to reload
before you go looking at replicas.

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

**Coming from before 0.17.0, read these first.** That release puts bounds on resources you
never configured, and none of the resulting failures names the upgrade:

- **[The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170).** On a host larger
  than 2 GB the app now gets *less* heap than the JVM used to take. There is no error; the
  only symptom is that things get slower.
- **[Statements are bounded from 0.17.0](#statements-are-bounded-from-0170).** Any single
  database statement is cancelled after 10 seconds, so on a large install a report, a search
  or a member removal that used to be merely slow now fails outright with a `422`.
- **Lock waits are bounded with them.** `DB_LOCK_TIMEOUT_MS` (3 s) now applies to *every*
  transaction rather than to the handful that lock deliberately, so a write queued behind a
  long-running change answers a retryable `409` instead of waiting indefinitely. The default
  did not move — its reach did. See its row in [Configuration](#configuration), and the
  `409` entry in [Troubleshooting](#troubleshooting).

**Also new in 0.17.0, and this one wants a check before you pull rather than after:**
[Notifications are scoped to a workspace](#notifications-are-scoped-to-a-workspace-from-0170).
The upgrade attributes every existing notification to a workspace and deletes any it cannot
attribute; one query tells you whether that number is zero on your instance, and it is
expected to be.

Database migrations run automatically on startup (Flyway) — no manual step. See
the [Releases](https://github.com/Zherikhov/hamstrack/releases) page for notes
before upgrading across a minor version.

**Pin an exact patch** (`:0.4.3`) for maximum reproducibility — you then upgrade
deliberately by bumping the tag. `:0.4` instead auto-tracks the newest 0.4.x on
each `docker compose pull`.

**Downgrades are not supported.** Flyway migrations are forward-only, and a newer
release may change the schema in ways an older image can't read (`ddl-auto` is
`validate`, so it will refuse to start rather than corrupt data). Always take a
backup before a minor upgrade so you can roll back by restoring it.

### Notifications are scoped to a workspace from 0.17.0

**Two changes, and one of them wants five minutes *before* you pull the image.**

**What your users will see.** A notification now belongs to the workspace whose comment it
quotes, and an inbox shows only the notifications from workspaces that person is currently
a member of. Remove somebody from a workspace and that workspace's notifications stop
appearing for them — in the bell, in the unread count and in the live stream. The rows are
**hidden, not deleted**: add the person back and their notifications return, with the same
read and unread state they had when they left. Before 0.17.0 they kept a readable inbox of
that workspace's comment text indefinitely, which is why this is worth the upgrade.

**What to check first.** The upgrade gives every existing notification a workspace by
reading it out of the row's `link`, which is where the product has always recorded which
issue a mention points at. A row whose workspace cannot be read back that way is **removed
from your inbox** — it could never be shown again under the new rule, and nothing anywhere
else records which workspace it belonged to, so there is nothing to repair it from. It is
copied aside rather than destroyed (see below), but it does not come back. Every
notification the product has ever written carries a usable link, so the expected answer
below is `0`; run it anyway, because the only instance that can tell you about your data is
yours:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT count(*) AS unresolvable
  FROM notifications n
  LEFT JOIN workspaces w
    ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid
 WHERE w.id IS NULL;
SQL
```

`0` means the upgrade deletes nothing — pull the image and carry on.

**Any other number is not a reason to stop.** It is that many notifications the upgrade will
move out of your inbox table, and there are two quite different reasons a row can be in that
count. This tells you which:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT n.link IS NULL
       OR substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/') IS NULL
         AS link_did_not_parse,
       count(*)
  FROM notifications n
  LEFT JOIN workspaces w
    ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid
 WHERE w.id IS NULL
 GROUP BY 1;
SQL
```

- **`link_did_not_parse` is `false`** — the link is fine, but the workspace it points at no
  longer exists on your instance. These are leftovers from a workspace deleted outside the
  application, a partial restore, or a dump reloaded without its parent rows; before 0.17.0
  nothing in the database noticed them. They could never be displayed again. **Upgrade** —
  removing them is the point.
- **`link_did_not_parse` is `true`** — some notification on your instance was written in a
  shape this release does not recognise. Upgrading is still safe (see the next paragraph),
  but please post the numbers on the
  [issue tracker](https://github.com/Zherikhov/hamstrack/issues): every notification the
  product is known to write carries a readable link, so yours would be new information.

**The upgrade keeps a copy either way.** If it removes anything at all, it first copies those
rows — in full, content included — into a table called `notifications_unresolvable_v20`, so
you can still look at them afterwards. That table is created **only** when there is something
to put in it, so on a clean upgrade it never appears. Nothing in Hamstrack reads it; it is
there for you. Once you have your answer, drop it:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT * FROM notifications_unresolvable_v20;
DROP TABLE notifications_unresolvable_v20;
SQL
```

If you would rather have the rows as a file before you upgrade, export them first:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' > unresolvable-notifications.csv <<'SQL'
\copy (SELECT n.* FROM notifications n LEFT JOIN workspaces w ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid WHERE w.id IS NULL) TO STDOUT WITH CSV HEADER
SQL
```

A backup taken as [Backups](#backups) describes covers you either way, and is the general
answer for a minor upgrade.

### Statements are bounded from 0.17.0

**Read this if your instance holds a lot of history** — a workspace with hundreds of
thousands of issues, years of activity, or one very large project. On a small or ordinary
install nothing changes and there is nothing to do: the bound is roughly a hundred times a
normal request.

Before 0.17.0, a single database statement could run **for ever**. Nothing shortened it: a
browser that gives up does not stop the query on the server, and the connection pool's own
timeout governs *waiting for* a connection, never one already in use. Ten slow queries were
the whole pool (`DB_POOL_MAX_SIZE`, default 10) and everything else on the instance began
failing to get a connection. From 0.17.0 every statement the application runs is cancelled
after **10 seconds** (`DB_STATEMENT_TIMEOUT_MS`), and the request that asked for it answers
`422` with `errorType: STATEMENT_BUDGET_EXCEEDED`.

**Database migrations are deliberately not bounded.** Flyway runs its own transactions, so
an index build or a table rewrite on a large install still takes as long as it takes.

> **0.17.0 changed a second default, and on a big host the two compound.**
> [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170) cuts the JVM heap on any
> host larger than 2 GB — a 4 GB host drops from ~1 GB to 512 MB. Less heap means more garbage
> collection inside the same query, which makes queries *slower*, which pushes borderline ones
> over this bound. **The causal direction is one-way:** the heap change can produce a `422`
> here, but nothing here affects the heap. So if reports started failing after upgrading on a
> host of 4 GB or more, set `APP_MEMORY_LIMIT` **first** and see whether the `422` goes away,
> before raising `DB_STATEMENT_TIMEOUT_MS`. Raising the bound hides a heap problem by letting
> the slower query run longer, on a connection it holds the whole time.

**What changes for you, if anything:**

| Your install | Before | After |
|---|---|---|
| Ordinary size — no request takes more than a second or two | nothing was near the bound | unchanged |
| Large, with one query that took ~5 s | a slow report | still works, ~5 s |
| Large, with a report or search that took 30 s | very slow, and it pinned a connection the whole time | **`422`**, in 10 s |
| Very large, where removing a busy member took 30 s | slow, and it held locks throughout | **`422`**, and the caller has nothing to narrow |

The last row is the one worth knowing about: a report or a search can be made cheaper by
asking for less — a shorter date range, fewer sprints, a narrower filter — but **removing a
workspace member cannot**, because the expensive part is a single update over every issue
that person was assigned. If that is where you meet this, raise the value.

**What to do.** Nothing, unless something that worked yesterday starts answering `422`
today. When it does, the app has already written the reason to the log:

```
Statement budget exceeded on GET /api/workspaces/{workspaceId}/projects/{projectId}/reports/flow
after 10000ms — answering 422 (SQLSTATE 57014). Raise app.persistence.statement-timeout-ms
(DB_STATEMENT_TIMEOUT_MS) if this request is legitimate, or narrow the query.
```

Then pick a value and restart:

| Situation | Set in `.env` | Why |
|---|---|---|
| Default, and nothing is failing | nothing | 10 s is far past any healthy request |
| A report or a report CSV download on a large tenant fails | `DB_STATEMENT_TIMEOUT_MS=30000` | 30 s, i.e. **at** the pool's own 30 s acquisition timeout, not under it. **Startup logs a sizing WARN at any value above 15000 and nothing silences it — expected here, not a misconfiguration.** Raise `DB_POOL_MAX_SIZE` with it, which is what that WARN itself advises: a longer bound means one request holds one connection for longer |
| A member removal or another write fails | `DB_STATEMENT_TIMEOUT_MS=60000` | the caller cannot narrow a write; give it a minute. **Startup logs the same sizing WARN — unavoidable above 15000, and correct to ignore if this was deliberate.** Raise `DB_POOL_MAX_SIZE` with it: one such request now holds a connection for up to a minute |
| You are diagnosing and want the old behaviour | **not available on purpose** | `0` means "no bound" to PostgreSQL and is refused at startup — that is the state this release exists to remove. Use a large value like `300000` instead, and expect the sizing WARN on every boot: at twenty times the threshold it is certain, and it is the app telling you this is a diagnostic setting rather than a resting one |

```bash
# in .env, next to DB_LOCK_TIMEOUT_MS
DB_STATEMENT_TIMEOUT_MS=30000
```

Then `docker compose up -d`.

**The floor is twice `DB_LOCK_TIMEOUT_MS` (default 3000), so the smallest accepted value is
6000** — and if you go below it the app **refuses to start** and says so. That is friendly
rather than hostile: PostgreSQL counts time spent waiting for a lock as part of the
statement, so a statement bound at or under the lock bound would fire first, and every
"someone else is editing this, try again in a moment" `409` in the product would silently
become a `422` that no retry can fix.

> **Above 15000 the app logs a sizing WARN at every boot, and no setting turns it off.** It
> compares the statement bound against half of the pool's 30 s acquisition timeout and nothing
> else — raising `DB_POOL_MAX_SIZE` is the right response to it but does not suppress it, and
> the WARN says so itself while firing. Any value in the table above trips it; that is the
> intended state for a deliberately long bound, and the log line is the record of the decision.
>
> **Raising it is not free, and these three numbers are really one setting.** Every second you
> add is a second one request can hold one of your `DB_POOL_MAX_SIZE` connections, so the
> relation to keep in view is
>
> ```
> requests-per-minute x statement-timeout-seconds  <=  pool-size x 60 x (that surface's share)
> ```
>
> **The left side is a floor, not the demand.** The bound is per *statement*, so a request made
> of several statements holds a connection for longer than the number on the left — and one that
> assembles its response in Java while the transaction is open (a report CSV) holds it for time
> the bound does not govern at all. Solve it for `DB_POOL_MAX_SIZE` and you will under-provision
> by roughly the statements-per-request factor.
>
> At the defaults one user is entitled to 180 expensive requests a minute (120 search + 60
> reports) while one replica has 600 connection-seconds a minute to spend — so the entitlement
> already exceeds the supply even at the floor. Bounding the hold is what makes that arithmetic
> possible to do at all, and it does not by itself close the gap (tracked as HD-182).
> Practically: if you raise
> `DB_STATEMENT_TIMEOUT_MS` near or above 30 s, raise `DB_POOL_MAX_SIZE` with it, or lower
> `REPORTS_REQUESTS_PER_MINUTE` / `SEARCH_REQUESTS_PER_MINUTE` — otherwise a handful of slow
> requests can still take the pool, more slowly than before, which is all this setting promises.

### The heap is bounded from 0.17.0

**Read this if your host has more than 2 GB of RAM.** On a smaller host you *gain*
heap and there is nothing to do. There is no error either way, which is the problem:
the only symptom is that the instance behaves as though it has less memory than it
used to, and nothing connects that to the upgrade.

Before 0.17.0 the image ran `java -jar` with no heap flag and the bundled compose set
no memory limit, so the JVM applied its own default — **~25% of whatever the host
had**. Your heap was a property of the machine, and no setting in Hamstrack named it.
From 0.17.0 the image runs `-XX:MaxRAMPercentage=50` and the bundled
`docker-compose.prod.yml` limits the app container to `1g` (`APP_MEMORY_LIMIT`), so
the heap is **half the container limit — 512 MB by default, on every host**. That is
what makes `REPORTS_MAX_ROWS` and the other byte budgets mean something: they are
costed against a 512 MB heap, which until now was an assumption about your machine
rather than a fact about the deployment.

**Break-even is a 2 GB host**, and it moves in both directions:

| Your setup before | Heap before | Heap after (default `1g`) |
|---|---|---|
| 1 GB host, no container limit | ~256 MB | **512 MB** — you gain |
| your own compose with `mem_limit: 1g` | ~256 MB | **512 MB** — you gain |
| 2 GB host, no container limit | ~512 MB | 512 MB — unchanged |
| 4 GB host, no container limit | ~1 GB | **512 MB** — you lose half |
| 8 GB host, no container limit | ~2 GB | **512 MB** — you lose three quarters |

On the losing rows the instance still works. It garbage-collects more often, large
reports and searches get slower, and a report that used to fit may now report itself
truncated or, at the extreme, fail. It reads as "0.17.0 made it slower".

**"Fail" has a specific spelling now, and it is the other half of this release.**
0.17.0 also cancels any single database statement after 10 seconds
([Statements are bounded from 0.17.0](#statements-are-bounded-from-0170)), so a query the
smaller heap has slowed past that answers **`422` `STATEMENT_BUDGET_EXCEEDED`** rather than
finishing late. Two changed defaults, one symptom, and this one is the cause: on a host of
4 GB or more, fix the heap here first — raising the statement bound instead buys the slower
query more time on a connection it is already holding too long.

**What to do:** on a host of 4 GB or more, set `APP_MEMORY_LIMIT` to **about half the
host** — never above **host RAM minus 2 GB**, less another 1 GB if you run the
[observability stack](#observability-optional). Whichever of the two is smaller is your
number, and the heap is half of it. On a 2 GB host or smaller, leave the default: the
whole point of that box is the app, and `1g` already gives it what it had.

| Host | Set in `.env` | Heap you get | Also worth setting |
|---|---|---|---|
| ≤ 2 GB | nothing — the default `1g` is right | 512 MB | — |
| 4 GB | `APP_MEMORY_LIMIT=2g` | 1 GB | — |
| 4 GB **with observability** | nothing — the default `1g` is right | 512 MB | — |
| 8 GB | `APP_MEMORY_LIMIT=4g` | 2 GB | `JAVA_TOOL_OPTIONS=-Xmx3g` → 3 GB |
| 8 GB **with observability** | `APP_MEMORY_LIMIT=4g` | 2 GB | `JAVA_TOOL_OPTIONS=-Xmx3g` → 3 GB |
| 16 GB | `APP_MEMORY_LIMIT=8g` | 4 GB | `JAVA_TOOL_OPTIONS=-Xmx7g` → 7 GB |

```bash
# in .env, next to the other settings
APP_MEMORY_LIMIT=2g     # 4 GB host running app + PostgreSQL + Caddy → 1 GB heap
```

Then `docker compose up -d` to recreate the container; a memory limit is not applied to a
running one.

**From `4g` up, also set an explicit heap** — the fourth column above. The 50% split is
headroom sized for a *small* container and does not stay right as the limit grows, because
most of what lives outside the heap — metaspace, the code cache, thread stacks — is roughly
*constant* rather than proportional: at `4g` the default reserves ~1.5 GB nothing will use,
where at `2g` it over-reserves by ~300 MB and is not worth a second setting. Claim the rest
back with

```bash
JAVA_TOOL_OPTIONS=-Xmx3g    # with APP_MEMORY_LIMIT=4g: limit minus ~700 MB
```

> **Only the `-Xmx` form works.** Setting `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`
> — the natural thing to try, since that is the flag named above — changes nothing:
> the image passes its own copy on the command line and that copy wins. The JVM still
> logs `Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75.0` when it starts, and
> that line means the variable was *read*, not that it was *applied*. So the evidence
> you would look for is present and says the wrong thing. `-Xmx` is a different flag
> and does override the percentage.

**Check what you actually got.** The container's limit and the heap it produces:

```bash
docker stats --no-stream --format '{{.Name}}  {{.MemUsage}}'
docker compose exec app java -XX:MaxRAMPercentage=50.0 -XX:+PrintFlagsFinal -version | grep -w MaxHeapSize
```

The first prints `used / limit` per container. The second prints the heap in bytes
(`536870912` is 512 MB). Repeat the flag exactly as shown: `exec` starts a *fresh*
JVM that does not inherit the image's startup arguments, so without it you would be
reading the default rather than yours. If you set `JAVA_TOOL_OPTIONS`, that JVM picks
it up the same way the app does, so the number stays honest.

**Running your own compose file rather than the bundled one?** Then nothing has
capped your container and the percentage is taken against host RAM — which at 50% is
*more* heap than before, and closer to the host's ceiling than is safe. Add a
`mem_limit:` to the app service; the [Quick start](#quick-start) file shows one.

### Duplicate accounts after an upgrade (locale-dependent email folding)

**Most instances can skip this.** It applies only if your Hamstrack container or host
ever ran with a Turkish, Azeri or Lithuanian locale (`LANG=tr_TR.UTF-8`, `az_AZ…`,
`lt_LT…`), and only to addresses containing an uppercase `I`. If `LANG` was never set
— the default for the published image and the sample compose — nothing here applies.

Before 0.16.0 the app lower-cased email addresses using the **JVM default locale**,
which on Linux comes from `LANG`/`LC_ALL`. Those three locales fold `I` to a dotless
`ı` (U+0131) rather than `i`, so an address entered as `IT-Admin@corp.com` was stored
as `ıt-admin@corp.com`. From 0.16.0 the fold is locale-independent and the same
address stores as `it-admin@corp.com` — meaning any row written under the old
behaviour is one this version can no longer find.

Two consequences, and the second is why this section exists:

- **That account can no longer log in.** The address its owner types no longer
  resolves to their row.
- **`SEED_ADMIN_EMAIL` mints a *second* administrator.** The seeder looks its
  configured address up and, on a miss, creates the account — so the first boot after
  upgrading leaves you with a second ACTIVE system administrator holding
  `SEED_ADMIN_PASSWORD`, while the original stays active and orphaned. Nothing logs
  it: the seeder deliberately never prints the address.

**This cannot recur on the published image.** 0.16.0 pins the JVM locale in the image
itself (`-Duser.language=en -Duser.country=US`), identically for every deployment.
That pin reaches the container and nothing else.

If you run the JAR directly you need those flags on your own command line — but be
clear about what that path is before you take it: **there is no published JAR asset
and no documented bare-JAR install.** Releases ship the container image, and
`docker compose` is the documented way to run Hamstrack. Building from source and
launching the JAR yourself is reachable, and this paragraph exists for that case; it
is not a second supported deployment model. If that is you:

```bash
java -Duser.language=en -Duser.country=US -jar target/hamstrack-<version>.jar
```

or `JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"` in a systemd unit.

The pin is `en`/`US` rather than a neutral root locale. For case folding the two are
equivalent; `en-US` additionally fixes the default number and date formatting used by
any code that formats without naming a locale. If your operators read the UI in
another language that is unaffected — this sets a server-side default, not the
interface language.

**Exactly two characters can differ**, and it is worth knowing which, because the
folding tables are full of near-misses that are *not* involved here. An uppercase `I`
folds to `ı` (U+0131) under these locales and to plain `i` everywhere else; a dotted
capital `İ` (U+0130) folds to plain `i` under these locales and to `i` followed by a
combining dot above (U+0307) everywhere else. Those are the only two. Long s (`ſ`,
U+017F) and the Kelvin sign (`K`, U+212A) look like they belong on this list and do
not — both fold identically under every locale, so they can never be the difference
between an old row and a new one.

**Check before upgrading.** While the old rows are still the only rows:

```sql
SELECT id, email FROM users WHERE email ~ '[^\x00-\x7F]';
```

A hit here is a flag, not a verdict: internationalised addresses are perfectly legal
and Hamstrack accepts them. What you are looking for is an otherwise-ASCII address
containing `ı`. Note that this query cannot see the `İ` case, whose old spelling is
pure ASCII — the pair query below catches both, so treat this one as an early warning
rather than a clearance.

**Check after upgrading.** Once the new build has booted, the duplicate exists and
the query above returns only *one* row of each pair. Ask for the pairs instead:

```sql
SELECT translate(email, U&'\0131\0307', 'i')     AS folded_form,
       count(*)                                  AS copies,
       array_agg(id    ORDER BY created_at)      AS ids,
       array_agg(email ORDER BY created_at)      AS addresses
  FROM users
 GROUP BY 1
HAVING count(*) > 1;
```

`translate` here maps `ı` to `i` and **drops** the combining dot: its third argument
is shorter than its second, and PostgreSQL removes any character with no counterpart.
That collapses both spellings of a pair onto one key.

**An empty result means no duplicate pairs.** `users.email` is `UNIQUE`, so two rows
land in one group only by differing in exactly the characters that fold — so in
practice there is nothing to sift here. The one way to get a group you should *not*
act on is if somebody deliberately registered a genuinely different address that
happens to differ only by a dotless `ı`; check the two addresses look like the same
person before merging them. A non-empty result lists each pair with its ids and both
spellings, **oldest first**.

**It does not clear the lone-stale-row case.** If the old account existed but nothing
has since re-created it — nobody re-registered, and it was not the seed admin — there
is no pair, no group, and nothing above finds it. The symptom is a single person
unable to log in. There is no duplicate to retire here, so fix the row directly — set
it to what the current build folds their typed address to. For a dotless `ı` row that
is `UPDATE users SET email = translate(email, U&'\0131', 'i') WHERE id = '<their id>';`
and here `translate` *is* correct, because that case's lookup key and group key
coincide.

The `İ` variant of the lone-row case behaves differently again, and better than it
looks. Two things are genuinely unavailable: you **cannot detect it proactively** —
its stored spelling is ordinary ASCII, indistinguishable from a correct row, so no
query finds it and it surfaces only as a login complaint — and you **cannot restore
the dotted-capital spelling**, because the address that spelling now folds to carries
an invisible combining dot. Neither matters, because you do not need either.

That stored spelling being plain ASCII is exactly what rescues it: an ordinary ASCII
`I` folds to a plain `i` under the current build too, so **the row is already
reachable — by typing an ordinary `I` instead of `İ`**. Confirm it with the address
they *meant*, spelled with ordinary ASCII capitals — which doubles as the way to find
the row, since no query detects this case:

```sql
-- Type the address in lower case yourself. Do NOT wrap it in lower(): that folds
-- under the DATABASE's collation, and on a tr_TR cluster it reproduces this very bug
-- from the SQL side, returning nothing and sending you looking for a row that is there.
SELECT id, email, display_name FROM users WHERE email = 'it-admin@corp.com';
```

If that returns their row, the spelling in the `email` column is their working
address: pure ASCII, nothing invisible, and this was a **read** — no write, no retire,
no lost history. Give it to them verbatim and they log in with it from now on.

**Do not send them to "forgot password" first.** That flow folds the address exactly
the way login does, so the dotted spelling misses the same row — and because the
endpoint deliberately reports success for unknown addresses to prevent enumeration, it
tells them a mail is on the way when none was sent. On DC, where SMTP is optional,
it is weaker still. Nor should you delete the row and re-create the account:
`issues.reporter_id`, `comments.author_id`, `invited_by` and the `created_by` columns
are all `NOT NULL REFERENCES users(id)` with no `ON DELETE`, and a person with a stale
row is by definition someone who has been using the instance — so the delete fails on
a foreign key, and would destroy their history if it did not.

**Fixing a pair.** Decide which row to keep first: it is the one with **history**
(memberships, issues, comments — normally the older, listed first above), *not* the
one the seeder has just minted. Move any work off the duplicate before you retire it;
for a freshly created seed admin there will not be any.

The survivor must end up holding **the duplicate's exact address** — that is by
definition the spelling the current build produces, because the duplicate is the row
the current build just wrote. Copy it across in SQL rather than retyping it: one of
these spellings carries a combining dot (U+0307) that is **invisible in a terminal**,
so a retyped address can look identical and still not match.

**Run this block in one interactive session**, in the order printed:

```bash
docker compose exec -it postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"'
```

then paste it there. The stash in statement 0 is a **temp table, which lives only for
the connection that created it** — so running these as separate one-shot
`psql -c "…"` invocations, one command per shell line, drops it between statements:
statement 1 still retires the duplicate and tombstones
its address, and statement 2 then fails with `relation "keep" does not exist`. That is
the stop-you-halfway state the comment in statement 1 warns about, reached through a
different door.

```sql
-- 0. Stash the duplicate's address before step 1 overwrites it. Doing this in SQL is
--    what removes the transcription risk -- never retype the address by hand.
CREATE TEMP TABLE keep AS
SELECT email FROM users WHERE id = '<duplicate id>';

-- 1. Retire the duplicate: disable it AND free its address, so the survivor can take
--    it. Order matters -- correcting the survivor first, while the duplicate still
--    holds the spelling it is moving to, violates the UNIQUE constraint on
--    users.email and stops you half way. left(email, 200) keeps the tombstone inside
--    VARCHAR(255); appending the id keeps it unique.
UPDATE users
   SET status = 'DISABLED',
       email  = left(email, 200) || '.retired-' || id
 WHERE id = '<duplicate id>';

-- 2. Hand the stashed address to the survivor.
--    Do NOT re-derive it with translate(): translate() produces the GROUP KEY, which
--    is not the lookup key. For a dotted capital I the address the build looks up
--    carries the combining dot that the group key deliberately drops -- and in that
--    case the survivor is already plain ASCII, so a translate() here would change
--    nothing, report "UPDATE 1", and leave the account locked out with the only
--    matching row already retired.
UPDATE users
   SET email = (SELECT email FROM keep)
 WHERE id = '<survivor id>';

DROP TABLE keep;
```

**Then verify by logging in as that account.** This is the one step whose failure is
silent — every statement above reports success whether or not the address it left
behind is the one the application will look up — so a clean run is not evidence that
access is restored. A login is.

If the pair was your seed administrator, **rotate `SEED_ADMIN_PASSWORD`** afterwards:
that password was set on a live administrator account that nobody asked to create.
Both rows in that pair are usually administrators, which is why "keep the one with
history" is the rule rather than "keep the active one".

## Backups

Two things to back up: the **PostgreSQL database** and **attachments**. They
reference each other, so capture them together and restore to a consistent point.

**Database** — logical dump:

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > hamstrack-$(date +%F).sql
```

Restore into a running (empty) database:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' < hamstrack-YYYY-MM-DD.sql
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
| A report, search or report CSV download that worked now returns `422` (`STATEMENT_BUDGET_EXCEEDED`) | One database statement ran past `DB_STATEMENT_TIMEOUT_MS` (default 10 s, **new in 0.17.0**) and PostgreSQL cancelled it. An identical retry fails identically. Narrow the request (shorter date range, fewer sprints, tighter filter) or raise the value — [Statements are bounded from 0.17.0](#statements-are-bounded-from-0170) has a size-to-value table. A **write** that does this (removing a member with a lot of assigned work) cannot be narrowed, so raise it. On a host of 4 GB or more also check the [heap](#the-heap-is-bounded-from-0170): 0.17.0 cut that too, and less heap makes the same query slower, so one symptom here can have either cause — or both. |
| An edit or save that used to work now returns `409` "Someone else is changing this right now" | **New in 0.17.0, and unlike the `422` above it announces nothing** — the status, the message and the `Retry-After` header all existed before, so there is no new string to search for. `DB_LOCK_TIMEOUT_MS` (3 s) now bounds *every* transaction rather than only the few that lock deliberately, so a write queued behind a long-running change gives up instead of waiting indefinitely. **It is retryable and the header says when**, so a client should retry rather than surface it. If legitimate edits collide often enough to be noticed, raise `DB_LOCK_TIMEOUT_MS` — but it may not exceed half `DB_STATEMENT_TIMEOUT_MS` (default `10000`, so `5000` is today's maximum) and the app refuses to start above that, so raise both together. The usual cause is removing a member with a lot of assigned work. |
| Everyone shares one IP / false `429`s | Behind a proxy/CDN that doesn't pass `X-Forwarded-For` (or passes an untrusted one). Ensure the proxy sets it; the app trusts the right-most entry. |
| Startup fails naming `app.persistence.statement-timeout-ms` and `app.locking.lock-timeout-ms` | The statement bound is under **2x** the lock bound. PostgreSQL counts lock-wait time inside the statement, so the smaller bound always fires first, and a statement bound at or under the lock bound would make the lock bound dead configuration — every retryable `409` in the product would quietly become a `422` no retry can fix. The message prints both values, the computed minimum and both knobs. **It can fire from the side you did not touch:** raising `DB_LOCK_TIMEOUT_MS` above 5000 while `DB_STATEMENT_TIMEOUT_MS` is at its default 10000 stops the boot. |
| Startup fails with a schema validation error after changing the image | You moved to an **older** image than the DB was migrated to. Use the newer image, or restore a pre-upgrade backup. |

## REST API

The HTTP API for a self-hosted instance is documented in
[api-dc.md](api-dc.md), and interactively at `/docs` (Swagger UI) on your
instance. The OpenAPI spec is served at `/openapi.yaml`.
