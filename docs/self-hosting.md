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

> **Licence — you may run this, including at work.** Hamstrack is **source-available**
> under the [Elastic License 2.0](../LICENSE), which is *not* an OSI-approved
> open-source licence. **You may** self-host it, modify it and use it commercially,
> free of charge. **You may not** provide Hamstrack to third parties as a hosted or
> managed service, circumvent its license-key functionality, or remove its licensing
> and copyright notices. Everything on this page is something you are allowed to do;
> [`LICENSE`](../LICENSE) is the full text.

## Contents

- [Requirements](#requirements)
- [Quick start](#quick-start)
  - [An unedited template is refused, by design](#an-unedited-template-is-refused-by-design)
    - [If your instance has the published admin account](#if-your-instance-has-the-published-admin-account)
    - [What rotating `JWT_SECRET` does, and what it does not](#what-rotating-jwt_secret-does-and-what-it-does-not)
- [Configuration](#configuration)
- [TLS & reverse proxy](#tls--reverse-proxy)
- [Email (SMTP)](#email-smtp)
- [First run & the admin account](#first-run--the-admin-account)
- [Attachment storage](#attachment-storage)
- [Optional toggles](#optional-toggles)
- [Observability (optional)](#observability-optional)
- [Upgrading](#upgrading)
  - [Applying repository configuration](#applying-repository-configuration)
  - [Statements are bounded from 0.17.0](#statements-are-bounded-from-0170)
  - [Connection acquisition is bounded from 0.18.0](#connection-acquisition-is-bounded-from-0180)
  - [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170)
  - [PostgreSQL is bounded and tuned from 0.18.0](#postgresql-is-bounded-and-tuned-from-0180)
  - [Notifications are scoped to a workspace from 0.17.0](#notifications-are-scoped-to-a-workspace-from-0170)
  - [Account addresses become case-insensitive in 0.18.0](#account-addresses-become-case-insensitive-in-0180-one-query-before-you-pull)
  - [Duplicate accounts after an upgrade](#duplicate-accounts-after-an-upgrade-locale-dependent-email-folding)
  - [Free text is bounded from 0.18.0](#free-text-is-bounded-from-0180)
  - [Attachment storage is capped per workspace from 0.18.0](#attachment-storage-is-capped-per-workspace-from-0180)
  - [Expensive reads are bounded by concurrency from 0.18.0](#expensive-reads-are-bounded-by-concurrency-from-0180)
- [Backups](#backups)
  - [By hand](#by-hand)
  - [On a schedule](#on-a-schedule)
  - [Verify a restore](#verify-a-restore)
  - [Attachments on S3: turn versioning on](#attachments-on-s3-turn-versioning-on)
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
  with no limit the JVM sizes its heap against *host* RAM. The bundled file also
  caps PostgreSQL (`POSTGRES_MEMORY_LIMIT`, `512m`) and Caddy
  (`CADDY_MEMORY_LIMIT`, `128m`) — containment, so a runaway service is killed and
  restarted in its own cgroup instead of the kernel picking a victim across the
  box. **A ceiling is not a reservation, and bounding everything does not make a
  small host safe**: app 1 GB + PostgreSQL 512 MB + Caddy 128 MB is ~1.6 GB of
  ceilings before the operating system and the page cache get anything, and adding
  the [observability stack](#observability-optional) — whose seven services' limits
  sum to ~1 GB — takes it to ~2.6 GB, more than a 2 GB box has at all. Those maxima
  are not reached together, which is why the smaller figure still works in practice;
  the larger one is why the full stack does not belong on a 2 GB host. Run it on
  4 GB. Disk
  is dominated by attachments — size the `attachments_data` volume (or your S3
  bucket) for expected uploads.

## Quick start

Pin a released image line (`:0.4`), not `latest`. Written as `${APP_IMAGE_TAG:-0.4}` so the
`APP_IMAGE_TAG` row in [Configuration](#configuration) is true of this file too: set the
variable in `.env` to move the pin, set nothing and you get `0.4`.

**The secrets below are `${VAR:?…}` rather than sample values, and that is deliberate**:
Compose refuses to create anything at all until you put them in a `.env` beside this file,
naming the one it wants. A sample secret in a copy-pasteable snippet is a working secret —
see [An unedited template is refused, by design](#an-unedited-template-is-refused-by-design).

```yaml
# docker-compose.yml
services:
  app:
    image: ghcr.io/zherikhov/hamstrack:${APP_IMAGE_TAG:-0.4}
    environment:
      SPRING_PROFILES_ACTIVE: dc
      DB_URL: jdbc:postgresql://postgres:5432/hamstrack
      DB_USERNAME: hamstrack
      DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env beside this file}
      # Min 32 bytes. Generate with: openssl rand -base64 48
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET in .env - openssl rand -base64 48}
      APP_BASE_URL: https://tracker.example.com
      # First administrator, created on startup — self-registration is closed on `dc`, so
      # without these nobody can log in. Named here on purpose: a variable that is only in
      # `.env` reaches this container if, and only if, a line like this one puts it there.
      SEED_ADMIN_EMAIL: ${SEED_ADMIN_EMAIL:?set SEED_ADMIN_EMAIL in .env beside this file}
      SEED_ADMIN_PASSWORD: ${SEED_ADMIN_PASSWORD:?set SEED_ADMIN_PASSWORD in .env - your own, not one from these docs}
      # SMTP — required for email verification (which doubles as login):
      MAIL_HOST: smtp.example.com
      MAIL_PORT: "587"
      MAIL_USERNAME: tracker@example.com
      MAIL_PASSWORD: <your SMTP password>
      MAIL_SMTP_AUTH: "true"
      MAIL_STARTTLS: "true"
      # Compose reads this for `stop_grace_period` below, and the APP has to be told the
      # same number — this line is what tells it, exactly as with SEED_ADMIN_* above.
      # The app waits for queued mail at shutdown and then writes what is left to
      # `failed_email`, and it refuses to start if both steps would not fit in this.
      APP_STOP_GRACE_SECONDS: ${APP_STOP_GRACE_SECONDS:-30}
    ports:
      - "8080:8080"
    # Not optional: the image sizes the heap at 50% of the CONTAINER limit, so
    # without a limit here it sizes against host RAM. 1g → 512 MB heap. Spelled as a
    # variable for the reason the `postgres` service below spells its dials that way —
    # a literal here is a value your `.env` cannot reach.
    mem_limit: ${APP_MEMORY_LIMIT:-1g}
    # Also not optional: Docker's own default is TEN seconds between SIGTERM and
    # SIGKILL, which is shorter than the 15 s the app spends flushing queued mail on
    # shutdown. Without this line every `docker compose up -d` kills the JVM part-way
    # through that flush and the queued password resets and verifications — rows already
    # committed, users already told to check their inbox — are lost with nothing written
    # down. Same variable as the environment line above, and for the same reason as
    # `mem_limit`: a literal here is a value your `.env` cannot reach.
    stop_grace_period: ${APP_STOP_GRACE_SECONDS:-30}s
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
      # The same variable as the app's DB_PASSWORD above, on purpose: two literals can be
      # edited apart, and then the app cannot log in to its own database.
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env beside this file}
    # PostgreSQL's memory dials, spelled as variables for the same reason `APP_IMAGE_TAG`
    # is: a literal here is a value your `.env` cannot reach, so setting it there later
    # changes nothing and looks like it did. The image's own defaults are shared_buffers
    # 128MB, work_mem 4MB and effective_cache_size 4GB — that last one tells the planner a
    # 2 GB host has more cache than the machine has RAM, and because it is a belief rather
    # than an allocation nothing ever refuses it. The POSTGRES_* rows under Configuration
    # size all three from your own host, in both directions.
    command:
      - postgres
      - -c
      - shared_buffers=${POSTGRES_SHARED_BUFFERS:-128MB}
      - -c
      - effective_cache_size=${POSTGRES_EFFECTIVE_CACHE_SIZE:-512MB}
      - -c
      - work_mem=${POSTGRES_WORK_MEM:-4MB}
    # A ceiling, not a reservation: it contains a runaway inside this container instead of
    # letting the kernel pick a victim elsewhere. Keep it well above shared_buffers plus
    # work_mem × sort nodes × connections, or you have converted a tuning value into a kill.
    mem_limit: ${POSTGRES_MEMORY_LIMIT:-512m}
    # Docker gives every container a 64 MB /dev/shm, and PostgreSQL's parallel workers put
    # their shared segments there — sized from work_mem. The default below is Docker's own,
    # so this line changes nothing until you raise POSTGRES_WORK_MEM; it exists so that when
    # you do, the matching dial is in `.env` rather than in a file a `git pull` replaces.
    shm_size: ${POSTGRES_SHM_SIZE:-64m}
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

Beside it, a `.env` that Compose reads. **All four values ship empty, with the instruction
in a comment above each** — because a filled-in sample in a copy-pasteable block *is* the
defect this page is about. Whatever stands to the right of `=` here is a value every reader
of this repository already has, and the `${VAR:?…}` guards above only fire while a value is
**absent**. So this block cannot start the stack as it stands, on purpose: fill in the one
the refusal names, run again, and the next names itself.

```
# .env — next to docker-compose.yml, never committed.

# Any strong password you choose. The compose above uses this one line twice: it seeds the
# Postgres container AND is what the app logs in with, so they cannot drift apart.
DB_PASSWORD=
# The output of:  openssl rand -base64 48
# Minimum 32 bytes, and never a value copied out of any documentation — including this one.
# The app refuses the placeholders this project has published, by name.
JWT_SECRET=
# Your own address. This becomes the first administrator.
SEED_ADMIN_EMAIL=
# A strong password you choose — not one from these docs, and not this variable's own name.
# Once the administrator exists you can stop seeding: delete BOTH `SEED_ADMIN_*` lines here
# AND the two `environment:` entries above that name them — together, because Compose
# refuses to start while a `${VAR:?…}` it still mentions is unset. That also stops a
# plaintext administrator password living in `.env` forever, and it leaves the account
# itself exactly as it is.
SEED_ADMIN_PASSWORD=
```

**`.env` is substituted into the compose file, not injected into the container.** Compose
resolves `${…}` in the YAML above and passes on the result; it does **not** hand the
container everything in `.env`. So a variable reaches the app only through a line in
`environment:` (or an `env_file: .env`, which is what the bundled
[`docker-compose.prod.yml`](../docker-compose.prod.yml) uses — there `.env` does both jobs).
Adding a setting to `.env` and expecting the app to see it is the mistake this paragraph
exists to prevent.

```bash
docker compose up -d
```

Browse your instance at its `APP_BASE_URL`, reached through the TLS proxy you put
in front (see [TLS & reverse proxy](#tls--reverse-proxy)). Public self-registration
is **closed by default** on self-hosted installs, so the `SEED_ADMIN_EMAIL` +
`SEED_ADMIN_PASSWORD` above create your first administrator on startup (see
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
    image: ghcr.io/zherikhov/hamstrack:${APP_IMAGE_TAG:-0.4}
    env_file: .env
    # ...only non-secret / internal values remain inline
```

Docker Compose also substitutes `.env` values into the compose file itself (e.g.
`${DB_PASSWORD}`). [`.env.prod.example`](../.env.prod.example) is a starting
template — it targets the fuller reverse-proxy stack, so use the subset of
variables that matches your setup (see [Configuration](#configuration)). Keep
`.env` out of version control.

### An unedited template is refused, by design

Every required value in `.env.prod.example` ships **empty**, and copying it to `.env`
unedited gets you a stack that will not start. That is the intended behaviour and not a
broken release.

The reason is that the guards protecting an installation all fail on **absence** —
`${VAR:?…}` in the compose files, the length check on `JWT_SECRET` in the application —
and a placeholder is the one thing that is not absent. A template that filled them in
produced an install that started, worked, and was wrong: until 0.18.0 this file shipped
`DB_PASSWORD=DB_PASSWORD`, which does not *fail*, it **agrees** — it seeds the Postgres
container and the application's datasource from the same line — so the instance came up on
a production database whose password is printed in a public repository.

**What the refusal looks like.** Interpolation is resolved before Compose creates, changes
or stops anything, so a stack already running keeps running; you simply cannot drive it:

```
$ cp .env.prod.example .env && docker compose -f docker-compose.prod.yml up -d
error while interpolating services.app.image: required variable GITHUB_OWNER is missing a
value: set GITHUB_OWNER in .env
```

It names **one** variable per run, and **which one is not fixed** — Compose stops at the
first unset value it happens to reach, so the same unedited file can name `SITE_ADDRESS`
on the next invocation. Treat it as a chain, not a checklist: set the one it names, run it
again, and the next names itself. Some come from `docker-compose.prod.yml`, the rest from
`docker-compose.observability.yml` when you pass that as well — the set is exactly what
those files guard, which is why nothing here lists it: a list would go stale one entry
before anyone noticed, and you never need to know it in advance.

Further refusals come from the **application** rather than from Compose. They surface as a
container that exits during startup, with the reason in `docker compose logs app`:

- `JWT_SECRET` empty or under 32 bytes — an empty line in `.env` reaches the app as an
  empty *value*, so the length check is what refuses it and the message counts what it got:
  `jwt.secret (JWT_SECRET) must be at least 32 bytes for HMAC-SHA256; current value is 0
  bytes. Generate one with: openssl rand -base64 48`. Deleting the line rather than emptying
  it fails earlier and less helpfully, with a Spring placeholder error naming `${JWT_SECRET}`
- `JWT_SECRET` set to a value **published in this repository's own documentation** — the old
  `REPLACE_WITH_…` placeholder was 34 bytes, i.e. long enough to pass the length check, so it
  is now refused by name. Anyone who can read the repository could otherwise mint an access
  token for your instance, including one claiming to be an administrator. **If an upgrade
  starts refusing your secret for this reason, replace it** (`openssl rand -base64 48`) —
  and see [What rotating `JWT_SECRET` does, and what it does
  not](#what-rotating-jwt_secret-does-and-what-it-does-not) below, because rotation is the
  first step of that cleanup and not the whole of it
- `SEED_ADMIN_PASSWORD` set to **the value this template used to ship** — it repeated its own
  variable name, so every install created from the unedited template had an **active system
  administrator whose email and password are both printed in a public repository**. Two
  strings and you are an administrator: nothing is forged, nothing is guessed, and the
  per-account login backoff never engages, because a correct password is not a failed
  attempt. It is refused by name for the same reason `JWT_SECRET`'s placeholder is, and the
  refusal names the account. **Emptying the variable does not fix it** — see the next section
- A **system administrator whose stored password is that same published value** — checked
  against the database rather than against `.env`, because the installations that are
  actually exposed are the ones whose configuration no longer mentions it. Seeding is
  idempotent and never re-passwords an existing user, so an operator who set a new
  `SEED_ADMIN_PASSWORD` and restarted got a clean boot with the account unchanged, and an
  operator who deleted the `SEED_ADMIN_*` lines years ago never saw the refusal above at
  all. This one reaches both, it names the account, and it stops being true the moment the
  account is actually repaired — see the next section. It costs one password verification
  per administrator checked, and it is bounded: the 25 oldest administrators (the seeded
  one is the *first*, by construction) plus whichever account `SEED_ADMIN_EMAIL` names
  today. If you have more than 25, a startup WARN says so rather than letting a partial
  check read as a complete one
- `SEED_ADMIN_PASSWORD` **longer than 72 UTF-8 bytes** — the ceiling BCrypt itself enforces,
  not a policy this project chose, so it bounds **every** password the application stores:
  registration and password reset refuse a longer one with a `422`. Count bytes, not
  characters — plain Latin letters cost 1 each, accented, Greek and Cyrillic 2, most other
  scripts 3, emoji 4 — so a 40-character Cyrillic passphrase is already 80 bytes and refused,
  while 72 ASCII characters fit exactly. The refusal fires only when the value **would be used
  to create the administrator account**: `SEED_ADMIN_EMAIL` set, the value over the limit, and
  no account occupying that address yet. An install that seeded its administrator once and
  later rotated this variable to an `openssl rand -base64 96` value therefore keeps booting —
  seeding is idempotent, so nothing ever encodes the new value. Without the guard the boot
  fails anyway, inside the encoder, saying only `password cannot be more than 72 bytes` and
  naming neither the variable nor a remedy

`SEED_ADMIN_PASSWORD` left **empty** has no fail-fast at all — a blank one logs a WARN and
skips seeding — so the symptom is that **nobody can log in**. That is deliberately the louder
half of the alternative, which used to be that anyone could.

#### If your instance has the published admin account

Seeding is idempotent, so an upgrade finds the existing user and *skips*: removing
`SEED_ADMIN_PASSWORD`, or setting a new one, leaves the account exactly as it is, with the
published password still working. The account has to be repaired directly.

From 0.18.0 the application checks this **against the database**, not against your `.env` —
so if your configuration says nothing about seeding and the app still refuses to start
naming an address, that is what it found, and you did nothing wrong today. **The refusal
fires while the context is still starting, so the port is never bound at all** — the admin
console is not available to you, and equally not to anyone else for the seconds the check
takes. (In 0.18.0's first cut it ran as a startup *runner*, which is after the port opens;
that arrangement served working logins for ~7 s per boot, and on `restart: unless-stopped`
it did so once per crash-loop cycle. It is a refresh-time check now, and a test asserts the
web server never reports itself started.) Start from the database:

```sql
-- The account keeps everything it owns; it only loses its password. Use the address the
-- refusal named. Run this against your Postgres container with the app stopped:
--   docker compose -f docker-compose.prod.yml stop app
--   docker compose -f docker-compose.prod.yml exec postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
UPDATE users SET password_hash = NULL WHERE email = 'the-address-the-refusal-named';
```

The application starts again immediately. Then:

1. Use **Forgot password** on that address to set one of your own (requires working SMTP) —
   or, from another system administrator, Admin console → **Users** → that user → reset it.
2. If the account was never meant to exist (you only ever wanted your own), delete it there
   instead. An install that was never logged into as the seeded admin owns nothing, so
   nothing is orphaned.
3. Then set a password of your own in `.env`, or stop seeding entirely. The refusal is
   cleared by step 1 or 2 and by nothing else — editing `.env` alone no longer makes the
   application quiet about it, which is the whole change.
   **If you stop seeding, remove the pair from both places at once:** delete
   `SEED_ADMIN_EMAIL` and `SEED_ADMIN_PASSWORD` from `.env` **and** the two matching
   `environment:` lines in your compose file. They are `${VAR:?…}`, so deleting only the
   `.env` entries makes Compose refuse to start; deleting only the compose lines leaves an
   administrator's password sitting in plaintext in `.env` forever. Nothing about removing
   them changes the account — it stays exactly as step 1 left it.
4. If the instance was publicly reachable with that account active, treat it as a
   compromise, not a misconfiguration: audit the users list for accounts you did not create,
   revoke every refresh session and every unused setup link (below), and check recent
   password resets.

#### What rotating `JWT_SECRET` does, and what it does not

Replacing the signing key **rejects every access token minted under the old key
immediately** — and that is all it does. It does **not** sign anyone out: refresh tokens are
opaque random values stored hashed and are independent of `jwt.secret`, and `/api/auth/refresh`
is public, so each client takes a single `401` and silently re-issues. Sessions continue
under the new key.

That matters in both directions. Rotation is cheaper than it sounds — nobody is logged out,
so there is no reason to defer it — and it is **not** a purge, so if you believe a published
secret was actually used against your instance, it is the first step of the cleanup rather
than the whole of it:

```bash
# revoke every refresh session (everyone signs in again on their next 401)
docker compose -f docker-compose.prod.yml exec postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DELETE FROM refresh_tokens;"'

# and every unused password-setup/reset link. An admin session forged with the old key can
# invite a user or reset one, and what that leaves behind is a `password_resets` row with a
# life of its own - seven days for an admin-issued setup link, one hour for a self-service
# reset. It is neither a session nor an access token, so neither the key rotation above nor
# the DELETE above reaches it, and it is the cheapest way to keep a foothold.
docker compose -f docker-compose.prod.yml exec postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DELETE FROM password_resets WHERE used_at IS NULL;"'
```

Anyone mid-signup or mid-reset simply asks for a new link. …Then audit your system
administrators (Admin console → Users) and the users list generally: a forged token is
short-lived, but an account created with one outlives every key rotation.

## Configuration

All configuration is via environment variables; [`.env.prod.example`](../.env.prod.example)
is a template to crib from (it's owner-oriented — take the subset you need).

> **A `$` in any `.env` value is an interpolation, not a character.** Docker Compose expands
> `$NAME`/`${NAME}` inside `.env`, and an undefined name expands to *nothing* — so a
> generated password of the form `Vk5$mT8pQr2zXn6w` reaches the container as `Vk5`, three
> characters, with no warning from anything. This is a property of every value in the file,
> not of any one variable. **Double it (`$$`) to get one literal `$`, or generate a value
> that has none** — `openssl rand -base64 48` never emits one. It hurts most in
> `SEED_ADMIN_PASSWORD`, where it seeds an administrator with a password you never chose and
> cannot read back.

Full reference:

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | — | `dc` (self-hosted) or `cloud` |
| `APP_IMAGE_TAG` | `latest` | Which tag of `ghcr.io/zherikhov/hamstrack` a compose file that *reads it* runs — the bundled `docker-compose.prod.yml` (default `latest`) and the Quick-start snippet above (default `0.4`). **In those, this is where you pin a version** — `APP_IMAGE_TAG=0.4` for a release line, `0.4.3` for an exact one — rather than editing the `image:` line, which a `git pull` or a re-download of the compose file undoes. In a compose file of your own that hard-codes a tag, this variable is read by nothing and setting it is the mistake this row exists to prevent: pin in whichever of the two files *you* own, and make sure it is the one docker actually reads. Read by Docker Compose, never by the app, so like `APP_MEMORY_LIMIT` an **empty** value is harmless: it falls back to `latest` instead of stopping the boot. `latest` is not for production — see [Upgrading](#upgrading) for what it means and when it moves. Identical in `dc` and `cloud` |
| `APP_STOP_GRACE_SECONDS` | `30` | How many seconds the app container gets between `SIGTERM` and `SIGKILL`. **Read twice, which is the whole reason it is a variable**: Docker Compose puts it in `stop_grace_period`, and the application binds the same value as `app.mail.async.stop-grace-seconds` — it has to know its own grace, because it waits `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` for queued mail and then writes whatever is left to `failed_email`, and it **refuses to start** unless the whole of that shutdown fits inside this number — the drain, the connection the write must first obtain (`DB_CONNECTION_TIMEOUT_MS`), and the write itself, which costs with the number of rows queued. **Docker's own default is 10 s**, which is *shorter* than the 15 s drain, so a compose file with no `stop_grace_period` line kills the JVM mid-flush and loses the queued password resets and verifications with no row and no log line — see [Mail](#mail). **If you run your own Compose file rather than the bundled one, add the line there too** (the [Quick start](#quick-start) file shows both halves): setting this variable alone changes nothing Docker reads. Raise it before raising the drain, never after. **Valid range 1–600**; `900` is refused at boot, so a drain that needs more than ~598 s of grace is not expressible — which is well past anything the drain's own `@Max` of 120 s can ask for. **An empty value means different things depending on how it reaches the container, which is worth knowing before you blank the line rather than after.** In the bundled `docker-compose.prod.yml` the app reads it through `env_file: .env`, so `APP_STOP_GRACE_SECONDS=` arrives as an empty string, the `${…:30}` fallback in `application.properties` never applies, and **the app aborts the boot** (Compose still gives the container 30 s, so the two disagree). In the [Quick start](#quick-start) file on this page the app service names it explicitly as `APP_STOP_GRACE_SECONDS: ${APP_STOP_GRACE_SECONDS:-30}`, and Compose's `:-` substitutes for an empty value as well as an absent one — so there a blank renders `30` and the app boots normally. Either way the safe habit is the same: **leave the line out rather than blanking it**, because only one of the two arrangements tells you that you did something. Identical in `dc` and `cloud` |
| `APP_MEMORY_LIMIT` | `1g` | Memory ceiling for the **app container**, read by Docker Compose (`mem_limit`) and never by the app — so it takes docker size suffixes (`1g`, `1536m`). **This is the heap dial**: the image runs the JVM with `-XX:MaxRAMPercentage=50`, i.e. half of the *container* limit, so `1g` here is a 512 MB heap and `2g` is a 1 GB heap. The other half is not slack — metaspace, thread stacks (Tomcat's request pool is capped at 200 threads by default, ~1 MB of stack each), the code cache, direct buffers and GC bookkeeping all live outside the heap, and squeezing them gets the container **OOM-killed by the kernel** (exit `137`, no stack trace) rather than the JVM throwing `OutOfMemoryError`. 512 MB is the reference heap `REPORTS_MAX_ROWS` below is costed against, so raising one is the occasion to re-read the other. **Upgrading from before 0.17.0 on a host bigger than 2 GB? The default is less heap than you had** — see [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170). **If you run your own Compose file rather than the bundled one, set a limit there too** — with no container limit the percentage is taken against *host* RAM, which is the situation this setting exists to end. **Half is the right split near `1g` and wasteful well above it**, because the non-heap need is largely *constant* rather than proportional (metaspace and the code cache do not grow with the heap): from `4g` up, pair the bigger limit with an explicit heap, `JAVA_TOOL_OPTIONS=-Xmx…` at roughly the limit minus ~700 MB (at `2g` the waste is only ~300 MB and a second setting is not worth it). **`-Xmx` is the only form that works** — `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` loses to the image's own copy of that flag, and the JVM logs `Picked up JAVA_TOOL_OPTIONS: …` in both cases, which says the variable was *read* and not that it was *applied*; the percentage form therefore looks like it worked. Unlike the app's own settings in this table, an **empty** value is harmless here — Compose reads it, not Spring, so `APP_MEMORY_LIMIT=` falls back to `1g` instead of stopping the boot. **The container limit also chooses the garbage collector, and nothing else here says so.** At `1g` the JVM sits below its "server-class machine" threshold and ergonomically selects **SerialGC** — single-threaded, stop-the-world — where at `2g`, same image and same flags, it selects **G1** (measured 2026-09-01 on `eclipse-temurin:21-jre-alpine`, the tag the published image is built from, 2 CPUs). That is the most likely explanation of the 4.99 s GC pause the 2026-08-31 load run recorded on a `1g` container — the collector was not itself recorded that day, which is why the startup line names it now — so if long pauses rather than `OutOfMemoryError` are your symptom, this is the dial that changes the collector as well as the heap. The application's startup line names the collector it actually got — see [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170). Identical in `dc` and `cloud`: how much memory a JVM may use is a property of the box it runs on, not of the plan |
| `POSTGRES_MEMORY_LIMIT` | `512m` | Memory ceiling for the **PostgreSQL container** in the bundled compose file. Read by Docker Compose, never by the app or by PostgreSQL, so it takes docker suffixes and an **empty** value falls back to the default. Until 0.18.0 this container had **no** limit while every observability container had one — which does not mean it was safe, it means that under host memory pressure the kernel chose which process to kill and the app, as the only bounded one, was as likely to be the victim as the container that grew. A limit is **containment**: the offender dies and restarts inside its own cgroup. It is emphatically **not** a promise that the host cannot run out of memory, because ceilings are maxima and not reservations — the bundled defaults declare more ceiling than a 2 GB host has RAM, which `docker-compose.prod.yml` states in full at the top. `512m` is ~2× the peak RSS measured on Hamstrack's own production box (~240 MB) at the `POSTGRES_*` settings below. **Raise it whenever you raise `POSTGRES_SHARED_BUFFERS`, `POSTGRES_WORK_MEM` or `DB_POOL_MAX_SIZE`, and never set it under the server's own dials**: a cgroup ceiling below what PostgreSQL is configured to use converts a tuning value into an OOM kill of a backend — or of the postmaster, which takes every session with it. **Those three are the list because they are the terms in what this ceiling has to contain**: `shared_buffers` is a floor under it, while `work_mem` and the pool are the two factors in `work_mem × sort nodes × backends` on top of it. The one derivation, quoted the same way in `.env.prod.example` and `docker-compose.prod.yml`: `4MB × ~4 nodes × ~12 backends` (a pool of 10, plus the `postgres-exporter` and a `psql` session) ≈ **190 MB**; at `DB_POOL_MAX_SIZE=50` it is `4MB × 4 × 52` ≈ **830 MB**, which nothing else refuses. Docker gives a container with a `mem_limit` and no `memswap_limit` the same amount again in swap, so the first symptom is swapping rather than death. **Upgrading an existing install? This container had no ceiling before 0.18.0** — see [PostgreSQL is bounded and tuned from 0.18.0](#postgresql-is-bounded-and-tuned-from-0180). Identical in `dc` and `cloud` |
| `CADDY_MEMORY_LIMIT` | `128m` | Memory ceiling for the **Caddy container**, same mechanism as the row above. Deliberately ~5× its measured peak (~24 MB) where PostgreSQL gets ~2×: Caddy is the only container on ports 80/443, so an OOM kill here is a site-wide outage plus a TLS handshake surge when it returns, and unused ceiling costs nothing. Only relevant if you use the bundled compose file's Caddy; if you front the app with your own proxy this variable is read by nothing. Identical in `dc` and `cloud` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | — | PostgreSQL connection (required) |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `5` | HikariCP pool sizing; raise the max for concurrency, keep (max × replicas) under Postgres `max_connections`. **The max is also a memory dial on the database, and `max_connections` is not the bound that bites first**: `POSTGRES_WORK_MEM` is charged per sort or hash node per *backend*, so this number is the `backends` in `work_mem × nodes × backends` — `4MB × ~4 × ~12` (this pool, plus the `postgres-exporter` and a `psql` session) ≈ **190 MB** at the defaults, and `4MB × 4 × 52` ≈ **830 MB** at a pool of 50, against a `POSTGRES_MEMORY_LIMIT` of `512m`. Fifty connections sits comfortably under a stock `max_connections` of 100 and comfortably over that cgroup ceiling, where the failure is an OOM-killed backend — or postmaster, which takes every session with it — rather than a refused connection. **Raise `POSTGRES_MEMORY_LIMIT`, or lower `POSTGRES_WORK_MEM`, in the same edit.** `DB_STATEMENT_TIMEOUT_MS` below is the other half of pool sizing: a longer statement bound holds each of these connections for longer. **Part of this pool is reserved, and the reservation is checked at startup**: `EXPENSIVE_READ_MAX_IN_FLIGHT` is the most of these connections the **expensive-read surface** — every read that holds a connection while it works — may hold at once, so the rest of the API always retains the difference. (Today that surface is reports, HQL search, saved filters, the storage breakdown and the **planning** reads; read it as the category, because the membership grows and an enumeration here goes stale one entry before the list does.) **While you leave that variable unset it is derived from this one** (60 % of it, capped at 6, with the per-user ceiling clamped to fit), so lowering the pool on its own is safe and an install that has never touched `EXPENSIVE_READ_*` cannot be stopped from booting by this row. **If you have set it explicitly it must stay strictly below this number or the app refuses to start**, and an explicit share above 60 % of this number logs a sizing WARN at every boot — so lowering the pool while pinning the share is an edit to make in one go |
| `POSTGRES_EFFECTIVE_CACHE_SIZE` | `512MB` | What the PostgreSQL **planner believes is cached** — `shared_buffers` plus the OS page cache it can expect to reach. Passed to the server as `postgres -c effective_cache_size=…` by the bundled compose file, so it takes PostgreSQL's units (`512MB`, `2GB`). **It allocates nothing**; it changes which plans look cheap, and a value far above the truth makes the planner prefer index access it will actually have to read off disk. The PostgreSQL image's own default is **4GB**, which is why this row exists: on Hamstrack's 1909 MiB production host that claimed more than twice the machine's entire RAM, on a box measured swapping. The `512MB` default is `shared_buffers` (128 MB) plus the low end of the page cache measured there under load (387 MB). **Set it from your host, in both directions**: roughly `shared_buffers` + the page cache this database can really expect. The usual starting point of ~75% of RAM assumes a *dedicated* database host — a box that also runs the JVM, Caddy and the observability stack is not one. **And under ~1 GB of RAM, lower it — to about `192MB`**: `512MB` on a 512 MB VPS claims the whole machine as cache, which is the image's `4GB` mistake one order of magnitude down. `192MB` is the `64MB` of `shared_buffers` that row recommends plus a small real page cache; confirm the second half with `free -m` rather than copying the figure. That ~1 GB is the same threshold `POSTGRES_SHARED_BUFFERS` and `POSTGRES_WORK_MEM` use — one number for all three dials. A value the server cannot parse makes PostgreSQL refuse to start while `docker compose up -d` still exits `0`, so change one dial at a time and check `docker compose ps`. **Upgrading from before 0.18.0 on a host of 4 GB or more? This default is a planner regression for you** — see [PostgreSQL is bounded and tuned from 0.18.0](#postgresql-is-bounded-and-tuned-from-0180). Identical in `dc` and `cloud`: this is host sizing, not a deployment mode |
| `POSTGRES_SHARED_BUFFERS` | `128MB` | PostgreSQL's own cache, and unlike the row above this one **is** an allocation — it comes out of `POSTGRES_MEMORY_LIMIT`, and on a single-box install it comes out of the JVM's share of the machine. Left at the image default deliberately: the stock advice of 25% of RAM assumes the database owns the host, and this memory is double-buffered against the very page cache `effective_cache_size` just told the planner to count on. Raise it with the host, and raise `effective_cache_size` and `POSTGRES_MEMORY_LIMIT` with it. **Under ~1 GB of RAM, lower it — to `64MB`**: there this allocation comes straight out of the JVM's share, and the page cache is doing the work anyway. Same ~1 GB threshold as `POSTGRES_EFFECTIVE_CACHE_SIZE` and `POSTGRES_WORK_MEM`. Identical in `dc` and `cloud` |
| `POSTGRES_WORK_MEM` | `4MB` | Memory for one sort or hash — **per node, not per connection**, which is the whole reason this row spells it out. A single query with three sorts and a hash join can take four times this value, and the app opens up to `DB_POOL_MAX_SIZE` (default 10) connections, so the honest worst case is `work_mem × nodes × backends`: even `4MB` is `4MB × ~4 × ~12` ≈ **190 MB** of exposure. **That makes this one of the three dials `POSTGRES_MEMORY_LIMIT` has to be raised with** — doubling it doubles the product that ceiling must contain, without the pool changing at all. Lower spills sorts into temp files (slow); higher swaps a small host, which is slower still and drags the JVM's garbage collector down with it. Raise it on a roomy host, or per session (`SET work_mem`) for one heavy job, rather than globally on a box that is already tight — and under ~1 GB of RAM go **down**, to `2MB`, since the same multiplication happens against less memory (same threshold as the two rows above). **Raising it also wants `POSTGRES_SHM_SIZE` raised**: PostgreSQL's parallel workers allocate their share of a sort in `/dev/shm`, which Docker fixes at 64 MB per container, and running out of it fails with `could not resize shared memory segment … No space left on device` — a message that names neither this variable nor the memory limit. Identical in `dc` and `cloud` |
| `POSTGRES_SHM_SIZE` | `64m` | Size of `/dev/shm` for the **PostgreSQL container** in the bundled compose file (`shm_size`). Read by Docker Compose, so it takes docker suffixes, and the default **is** Docker's own 64 MB — as shipped this variable changes nothing. It exists because `POSTGRES_WORK_MEM` above tells you to raise `work_mem` on a roomy host, and PostgreSQL's **parallel** workers put their dynamic shared memory segments here, sized from `work_mem`: raise one far enough without the other and queries fail with `could not resize shared memory segment … No space left on device`, against `/dev/shm` rather than against `POSTGRES_MEMORY_LIMIT`. A starting point is `work_mem` × the parallel workers one query may use, with room to spare. Not part of `POSTGRES_MEMORY_LIMIT`'s budget in the way `shared_buffers` is, but not free either — a tmpfs page in use is host RAM. Identical in `dc` and `cloud` |
| `DB_LOG_SERVER_ERROR_DETAIL` | `false` | Whether PostgreSQL's `DETAIL`, `HINT`, `POSITION`, `WHERE` and `INTERNAL QUERY` lines reach your application log. **Off by default, and that is a privacy floor rather than a tuning default**: the JDBC driver folds `DETAIL` into the exception *message* and Hibernate logs that message before any application code runs, so nothing inside the app can redact it — on a duplicate-key error it would print the colliding **values**, which on the invitation path is a third party's full email address, from a request that person never made. Logs are also the one place data leaves the box (a shipper, a support ticket, a screenshot), so the address you would leak is your own user's. **What being off costs you is wider than the case it was added for**, because it applies to *every* server error on this pool: **Flyway shares this datasource**, so a migration that fails during an upgrade names the index or constraint and **not the rows that collided**, and reports no source position on a syntax error — see the [Troubleshooting](#troubleshooting) row for the one-session procedure. Also lost: trigger context, `Failing row contains (...)` on a not-null violation, and the colliding key on duplicate errors that carry no personal data at all. **Turn it on for one debugging session and remove the line afterwards** — it is not a setting to run with. Setting it via `?logServerErrorDetail=true` on `DB_URL` also works at the driver but does **not** survive: the bundled `docker-compose.prod.yml` sets `DB_URL` itself and is replaced wholesale by an upgrade. **Unlike the two timeouts above, a blank value is harmless here** — it binds as a driver property rather than as an int, and an empty string reads as `false`, so `DB_LOG_SERVER_ERROR_DETAIL=` is simply off. Identical in `dc` and `cloud` |
| `DB_LOCK_TIMEOUT_MS` | `3000` | How long a transaction may wait for a row lock before giving up, in ms. **From 0.17.0 this applies to every transaction the app opens, not only to the few that lock deliberately** — so an ordinary edit queued behind a long-running change (removing a member with a lot of assigned work is the usual one) now fails after 3 s with a retryable `409` instead of waiting indefinitely. That is the point: it is issued together with `DB_STATEMENT_TIMEOUT_MS` below, because `statement_timeout` counts lock-wait time, and without it a contended write would be cancelled by *that* bound and answered `422` — a refusal that tells the caller not to retry when retrying is exactly what works. Raise it if legitimate edits collide often enough to be noticed. (This row used to name the handful of endpoints that locked on purpose; that list was wrong within one release and is now wrong by design.) Applied with `SET LOCAL` inside each transaction the app opens — **not** as a server-wide PostgreSQL `lock_timeout`, which is why Flyway migrations on the same pool are unaffected and still wait as long as they need: Flyway runs its own transactions and never goes through the app's transaction manager. Exceeding it is a retryable `409` + `Retry-After`, not a failure. Valid range 100–60000; out-of-range, `0` (PostgreSQL reads it as "wait for ever" — the behaviour this setting exists to remove) or **blank** fails startup instead of being clamped, so `DB_LOCK_TIMEOUT_MS=` does not disable the line, it stops the boot — remove the line to get the default. **From 0.17.0 the usable top of that range is lower than 60000**: `DB_STATEMENT_TIMEOUT_MS` must stay at least twice this value, so at its default of `10000` this one may not exceed **5000**. Raising it past that stops the boot naming both properties — raise the statement bound in the same edit |
| `DB_STATEMENT_TIMEOUT_MS` | `10000` | How long **any one statement** of an application transaction may run before PostgreSQL cancels it, in ms. Applied with `SET LOCAL` to every transaction the app opens — there is no list of covered endpoints, because the cost of a statement is a property of how much data you have and not of which feature issued it. **Flyway is deliberately not covered**: migrations run their own transactions, and an index build or a table rewrite on a large install legitimately takes minutes. Exceeding it answers `422` with `errorType: STATEMENT_BUDGET_EXCEEDED` and **no** `Retry-After` — an identical retry costs identical time — and logs a WARN naming this variable. It does **not** bound how long a *connection* is held: a transaction of many statements, or one that spends its time assembling a response in Java, can outlive this number. **New in 0.17.0, and on a large install it can turn a slow report, search or member removal into an error** — see [Statements are bounded from 0.17.0](#statements-are-bounded-from-0170) for a size-to-value table. Must be at least **2x `DB_LOCK_TIMEOUT_MS`** or the app refuses to start: `statement_timeout` counts lock-wait time too, so a smaller value would fire first and replace the retryable `409` above with a `422` that is not retryable. Valid range 1000-600000 — but the `2x` rule is the binding one in practice: **with the default `DB_LOCK_TIMEOUT_MS` of 3000 the smallest value that boots is 6000**, and `1000` is only reachable if you also lower the lock bound to 500 or less. `0` means "no bound" to PostgreSQL and is refused, and **blank** stops the boot exactly as `DB_LOCK_TIMEOUT_MS` does. **Raising this is not free:** every second you add is a second one request may hold one of your `DB_POOL_MAX_SIZE` connections, so the same pool serves fewer concurrent slow requests — past ~30 s, raise the pool with it. Identical in `dc` and `cloud` |
| `DB_CONNECTION_TIMEOUT_MS` | `3000` | How long a request may wait for a **connection from the pool** before it is refused, in ms — the third member of the family above, and **new in 0.18.0**: until this release it was never set, so every acquisition waited HikariCP's 30-second default, which nobody had chosen. **The three are one derivation**: the lock bound is how long a transaction waits for a *row*, the statement bound how long one statement *runs* (at least twice the lock bound, because it counts that wait), and this is how long a request waits for a *connection* — at least the lock bound, because a connection held by a lock-waiting transaction is legitimately unavailable for exactly that long. None of the three is derived from the pool size, and this one is not derived from the statement bound in either direction: waiting for a connection is queueing, not work, which is why it can be short while a statement may legitimately run for ten seconds. Exceeding it answers **`503`** with `errorType: DATABASE_BUSY` and `Retry-After: 1` — a 5xx on purpose, unlike the `422` above, because one retry costs one acquisition attempt and the obstacle is somebody else's transaction, which ends. **Every request gets that answer, wherever the acquisition failed** — inside a handler, or earlier in the security filter chain, which is where an authenticated request's token is resolved to a user. Two pieces of code write it, and they agree on everything a client acts on: the same status, the same `detail`, the same `Retry-After`. They differ only in optional members — the filter-written body omits `instance` (the request path Spring MVC fills in for you), because hand-escaping a caller-supplied string into a hand-built JSON document is a worse thing to own than a missing member nothing here reads. What cannot be given a status is a failure with no answer left to change: one on an `ASYNC` or `ERROR` dispatch, one after the response has already begun (a streamed download, an SSE stream), and one outside a request altogether — a scheduled job, the shutdown residue write, Flyway at startup. Those last are also why HikariCP's own `hikaricp_connections_timeout_total` can still read higher than `hamstrack_db_connection_acquisition_failed_total`: it counts acquisitions with no caller to refuse. **On a busy or under-provisioned instance this turns a slow period into visible errors rather than a slow one** — see [Connection acquisition is bounded from 0.18.0](#connection-acquisition-is-bounded-from-0180). It does **not** bound how long a connection is *held* (that is `EXPENSIVE_READ_MAX_IN_FLIGHT`), and Flyway is unaffected although it shares the pool: migrations take their connections at startup from a pool nothing else is using, and this bounds *getting* one rather than keeping it. **It sets two HikariCP values, not one**: the same number is also the pool's `validation-timeout`, which bounds the aliveness check run on a connection that has sat idle in the pool past `aliveBypassWindowMs`. They are one number deliberately — left apart, a single `getConnection()` can cost this bound *plus* HikariCP's 5-second validation default, in exactly the degraded-database case this bound exists for — but it means raising this to `6000` also gives that check 6 seconds. The app compares **both** values against what the pool ended up holding and refuses to start if either was moved by another name or from another property source. Must stay at or above `DB_LOCK_TIMEOUT_MS` or every boot logs a sizing WARN. **`0` is refused at startup** — HikariCP maps it to `Integer.MAX_VALUE`, about 24.8 days, so it means *no bound* rather than *no wait* — as is anything below **250** (HikariCP's own floor) and a **blank** value. **There is also a ceiling nothing else states**: the shutdown residue write must fit inside `APP_STOP_GRACE_SECONDS` together with the mail drain, so at the default mail settings the largest value that boots is **13900**; above that the boot stops, naming every knob that can move the arithmetic. Identical in `dc` and `cloud` |
| `JWT_SECRET` | — | HMAC key for access tokens, **min 32 bytes** (required). Generate it — `openssl rand -base64 48` — never reuse a value from any documentation: the app additionally refuses the placeholders this project has published, by name, because they are long enough to pass the length check and an instance signing tokens with one can be impersonated by anybody. See [An unedited template is refused, by design](#an-unedited-template-is-refused-by-design) |
| `JWT_ACCESS_TOKEN_TTL` | `PT30M` | Access-token lifetime (ISO-8601 duration). Short by design — the refresh cookie renews it. Longer = a leaked token is replayable for longer |
| `APP_BASE_URL` | `http://localhost:8080` | Public URL; used in emails, cookies (`Secure` when https), robots/sitemap — and, if you enable the CSP report sink, its **host** is what decides which violation reports are accepted (see [Content-Security-Policy (report-only)](#content-security-policy-report-only)) |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` / `MAIL_FROM` | localhost:1025 | Outgoing SMTP (verification, invites, password reset) |
| `MAIL_SMTP_CONNECT_TIMEOUT_MS` / `MAIL_SMTP_READ_TIMEOUT_MS` / `MAIL_SMTP_WRITE_TIMEOUT_MS` | `5000` / `10000` / `10000` | SMTP socket timeouts (ms) — a black-holed mail host fails a worker fast instead of hanging (connect / per-read / per-write) |
| `MAIL_ASYNC_CORE_POOL` / `MAIL_ASYNC_MAX_POOL` / `MAIL_ASYNC_QUEUE_CAPACITY` | `2` / `5` / `100` | Bounded mail executor — mail can't starve other async work or spawn a thread-per-task under an SMTP stall. A full queue **drops and dead-letters** the message rather than sending it on the request thread, so a slow SMTP host cannot take Tomcat workers with it. Valid ranges 1–50 / 1–50 / 1–10000; the queue's practical ceiling is lower, because the drain plus a batch write of that many rows has to fit inside `APP_STOP_GRACE_SECONDS` — with the connection that write has to obtain first (`DB_CONNECTION_TIMEOUT_MS`) counted in. The refusal names every knob that can move the arithmetic, so read it rather than guessing which one to change. A **blank** value stops the boot rather than restoring the default — these bind as `int`, so `MAIL_ASYNC_MAX_POOL=` is not "unset". Identical in `dc` and `cloud` |
| `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` | `15` | How long shutdown waits for queued mail to flush before dead-lettering whatever is left. **Read together with `MAIL_ASYNC_QUEUE_CAPACITY`, `DB_CONNECTION_TIMEOUT_MS` and `APP_STOP_GRACE_SECONDS`** — the drain, the connection the residue write has to obtain, and the write itself must all fit inside the stop grace, and the app refuses to start if they do not, naming every knob that can move it. So **raising this alone is refused**: raise `APP_STOP_GRACE_SECONDS` in the same edit and one variable moves both the container's grace and the app's belief about it. **Valid range 1–120**, and at the shipped defaults (grace 30 s, queue 100, acquisition 3000 ms) the practical ceiling is **25 s** — the declared range is reachable only by raising the grace with it. A **blank** value stops the boot rather than restoring the default. Identical in `dc` and `cloud` |
| `MAIL_FAILED_EMAIL_RETENTION_DAYS` | `90` | How long a `failed_email` dead-letter row is kept before a daily sweep deletes it. Long on purpose: these rows are the only record that somebody's password reset never went, and they are what a re-drive reads. **New in 0.18.0, and the table had no sweep at all before** — which was safe only while a row cost an exhausted retry cycle; a row now also records a message that was never attempted, and those are written at request rate rather than at SMTP rate. **Valid range 7–3650**; the floor is a week rather than a day on purpose, so a default cannot quietly delete the evidence for an incident nobody looked at over a holiday. A **blank** value stops the boot rather than restoring the default. Identical in `dc` and `cloud` |
| `MAIL_NEVER_ATTEMPTED_MAX_PER_HOUR` | `500` | Per-instance hourly ceiling on `failed_email` rows for mail that was **never attempted** (queue full / pool shutting down). The other half of the row above: retention bounds the table in time, this bounds the rate, and without it a burst of refused dispatches can write hundreds of rows a minute — bounded only by the per-IP auth rate limit, i.e. not bounded at all across many IPs. Above the ceiling the loss is still logged at ERROR (type + recipient domain) and still reaches the caller's error line; it just stops being durable, and a line naming the suppressed count is logged when the hour rolls. **If you are hitting it, the mail pool is saturated — look there before raising this**, and if you raise it far, note that the retention sweep is an un-indexed `DELETE`. **Valid range 1–1000000**; a **blank** value stops the boot rather than restoring the default. Node-local, like the rate limiters — see [Optional toggles](#optional-toggles) for what that costs on N replicas. Identical in `dc` and `cloud` |
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
| `AGILE_SECTION_MAX_ISSUES` | `300` | Max issues rendered per section of the planning view (`GET …/backlog`) — one section per open sprint plus the backlog. Together with `AGILE_MAX_OPEN_SPRINTS` this bounds the response size; a truncated section still reports honest whole-section totals. Valid range 1–2000, but **not independently attainable** — see the joint bound on `AGILE_MAX_OPEN_SPRINTS` below (at the default 20 open sprints this caps out around 952, not 2000). An out-of-range value fails startup instead of being clamped. **The unit is rows and the cost is megabytes, with a concurrency multiplier on top** — the same relationship `REPORTS_MAX_ROWS` documents, and the reason both ceiling at the same 20 000: an assembled issue is ~1.9 KB of transient heap, so the default view is ~12 MB per request and the joint ceiling ~38 MB, against the 512 MB of heap a default install gets. Planning reads take permits from `EXPENSIVE_READ_MAX_IN_FLIGHT` (6 on the shipped pool), so up to that many of those views are alive at once — ~228 MB at the configurable maximum, **shared with reports and search rather than added to them**. Raising this number raises a per-request heap figure that is then multiplied by ~6, and the 512 MB follows `APP_MEMORY_LIMIT` rather than being a constant |
| `AGILE_MAX_OPEN_SPRINTS` | `20` | Max FUTURE + ACTIVE sprints in one project (COMPLETED ones are history and cannot be started, so they don't count); creating past the cap is a 422. Valid range 1–100; an out-of-range value fails startup instead of being clamped. **Also validated jointly with `AGILE_SECTION_MAX_ISSUES`:** their product `(this + 1) × section cap` must stay ≤ 20 000, since one `GET …/backlog` assembles that many issues in a single unpaged response — both at their individual maxima would be ~202 000 rows, so startup fails rather than OOM later |
| `AGILE_DEFAULT_SPRINT_LENGTH_DAYS` | `14` | Default iteration length — the end date a sprint start assumes when the request carries none. Valid range 1–90; an out-of-range value fails startup instead of being clamped |
| `AGILE_MAX_BULK_MOVE` | `100` | Max issue ids accepted in one "move to sprint" request; beyond it the request is rejected with 400 and the client chunks it. Valid range 1–500; an out-of-range value fails startup instead of being clamped |
| `REPORTS_MAX_WINDOW_DAYS` | `365` | Widest window one report may span, in days, counting **both** endpoints. A wider request is refused with `400` naming this cap — it is **never silently narrowed**, because a chart of a window nobody asked for is exactly how a reporting feature earns "these numbers don't match what I expected". Valid range 1–3650; at daily buckets the window length *is* the number of points in the response, which is what the upper bound guards. Identical in `dc` and `cloud`: reporting depth is a product feature, not a plan feature. A value **under 90** is safe and is not a trap: the endpoint's own default window is `min(90, this)`, so the parameterless request the reports page makes on load is always inside the cap — what is never done is clamping a window a caller explicitly asked for. An out-of-range value fails startup instead of being clamped. **Leave the line out to get the default — `REPORTS_MAX_WINDOW_DAYS=` is an empty value, not an absent one, and it stops the boot rather than restoring 365**; that matters more here than elsewhere, because this number is quoted back to API callers inside a 400 |
| `REPORTS_MAX_ROWS` | `20000` | Most issue **rows** one report may materialise before it declares itself truncated — every report response carries `meta.truncated` + `meta.cap` and the UI prints it, so a cap never bites silently. It does not bite on the flow report, which aggregates in PostgreSQL and returns one row per bucket; it is the budget for the row-level reports (cycle time, aging WIP). Valid range 1–50000; an out-of-range value fails startup instead of being clamped. The ceiling is a byte budget wearing a row count: a shipped row costs roughly 1.9 KB of transient heap at worst (the JDBC row, the DTO and the buffered JSON are all alive at once), so 50000 is ~95 MB for one request — the previous ceiling of 200000 was ~380 MB, i.e. a documented value that could OOM the instance in a single GET. The ceiling is sized against a **reference heap of 512 MB**, and since `HD-152` that is the heap a default install actually gets rather than a premise it hopes for: the image runs the JVM at `-XX:MaxRAMPercentage=50` and the bundled compose limits the app container to `1g` (`APP_MEMORY_LIMIT`), which is 512 MB of heap exactly. So 50000 rows is ~95 MB of a 512 MB heap for one request — legal, large, and the reason the *default* is 20000 (~38 MB worst case) rather than the ceiling. The relationship still has to be maintained by whoever moves either number: the heap follows the container limit, so `APP_MEMORY_LIMIT=2g` doubles it to 1 GB and makes this ceiling correspondingly conservative, while a smaller limit — or your own Compose file with **no** `mem_limit`, where the percentage is taken against host RAM — moves it the other way. And nothing bounds *concurrency*: N users asking together is N of these alive together, so ~95 MB is a per-request figure and never a total. **Leave the line out to get the default — `REPORTS_MAX_ROWS=` is an empty value, not an absent one, and it stops the boot rather than restoring 20000** |
| `REPORTS_REQUESTS_PER_MINUTE` | `60` | How many report requests **one user** may make per minute across the whole reports surface (every `…/projects/{id}/reports/**` endpoint, not per report) **plus `POST …/workspaces/*/search/insights`**, the Insights panel — a report that lives on the search path and is bound to this limiter explicitly rather than by prefix. For the six project-scoped reports this is the only bound on the **work** a report does: `REPORTS_MAX_WINDOW_DAYS` bounds the response array, the "open at window start" balance is O(project history) whatever window you ask for, and `Cache-Control: private` means no shared cache absorbs a repeat — so without a budget one authenticated member in a loop can saturate `DB_POOL_MAX_SIZE` with entirely legal 200s. Past it the answer is `429` + `Retry-After`; a report is never narrowed or approximated to fit a budget. Counted in-memory per instance. Valid range 1–10000; there is **no "unlimited" value** — `0` fails startup, and the off switch is `RATE_LIMIT_ENABLED` (which disables every limiter that **has** an off switch — not the backlog-rebalance cooldown, a fixed internal safety valve with no variable and no switch). Identical in `dc` and `cloud`. **Leave the line out to get the default — `REPORTS_REQUESTS_PER_MINUTE=` is an empty value, not an absent one, and it stops the boot rather than restoring 60**. Insights is the one exception to "only bound": it is additionally inside `SEARCH_REQUESTS_PER_MINUTE` below. It sits on the reports limiter deliberately, because removing that binding would raise the panel’s allowance to the search budget as a side effect — so **the lower configured value binds**, and lowering *either* property lowers the panel. |
| `SEARCH_REQUESTS_PER_MINUTE` | `120` | How many **search-surface** requests **one user** may make per minute across the whole `…/workspaces/*/search/**` path: `POST …/search`, `GET …/search/schema`, `GET …/search/suggest` and `POST …/search/insights` **and the whole `…/workspaces/*/filters/**` path** — every saved-filter operation, `GET` and `DELETE` included, since the binding is by path and not by method. Saved filters are on this budget because **HQL validation is search-surface work wherever it is mounted**: validating a filter builds the same resolution context `…/search/schema` pays for (roughly eight statements, including a workspace-wide label projection and a full member scan), so creating one with a deliberately invalid body was an unthrottled eight-query refusal loop. It is charged here rather than to the reports pot because a saved filter *is* a saved search. Saved filters (`…/workspaces/*/filters/**`) are on this budget too: validating a filter's HQL builds the same resolution context `…/search/schema` pays for (a workspace-wide label projection and a full member scan), so an invalid-body loop there was the same unthrottled cost wearing different clothes — and a saved filter is a saved search, done by the same person. Past it the answer is `429` + `Retry-After`. **Its own budget rather than the reports one** because a person typing in a search box legitimately fires several requests a minute and must not be starved to protect charts; 120 is roughly ten times ordinary SPA use. Search is not the cheap surface it looks like — a query may carry up to 50 leaf predicates, a `text ~` leaf is two unanchored, unindexable `LIKE`s over a TEXT column, and the endpoint runs the whole predicate **twice** per request (count, then page) — so until this existed the expensive door was the unthrottled one. Counted in-memory per instance, so N replicas allow up to N × the budget per user (it damps an abuse vector rather than enforcing an invariant, so a split budget is a weaker guard and never a wrong answer). Valid range 1–10000; there is **no "unlimited" value** — `0` fails startup, and the off switch is `RATE_LIMIT_ENABLED` (which disables every limiter that **has** an off switch — not the backlog-rebalance cooldown, a fixed internal safety valve with no variable and no switch). Identical in `dc` and `cloud`. **Leave the line out to get the default — `SEARCH_REQUESTS_PER_MINUTE=` is an empty value, not an absent one, and it stops the boot rather than restoring 120**. Note that the Insights panel is inside **both** this budget and `REPORTS_REQUESTS_PER_MINUTE` above. It sits on the reports limiter deliberately, because removing that binding would raise the panel’s allowance to the search budget as a side effect — so **the lower configured value binds**, and lowering *either* property lowers the panel. |
| `PLANNING_REQUESTS_PER_MINUTE` | `240` | How many **planning** requests **one user** may make per minute across `/api/workspaces/*/projects/*/backlog/**` — the whole planning view (`GET …/backlog`) and every section read under it, on one pattern, so a new planning read is budgeted the moment it exists. Until 0.18.0 the largest single response this product produces had no budget of any kind, and that was not a decision anybody made. "Bounded by the planning-view cap" is not "small": the cap is **20 000** assembled issues in one unpaged response — the same number as `REPORTS_MAX_ROWS` — 6300 at the stock `AGILE_*` defaults, roughly 12 MB of transient heap per request and 38 MB at the maximum you may configure. What earns the budget is that the per-section stats query is **unconditional and cap-blind**: it reads and groups a whole section whatever the filters say and whatever the cap is. **240 is derived, not chosen** — a drag within a section is 1 request, a drag *across* sections is 2 issued one after the other, a filter change or sprint change is 1 aggregate, so a fast facilitator produces ~70 planning requests a minute from one very busy tab and ~140 with a second project open; 240 is ~3.4× the first. Deliberately generous because it sits under an interactive drag-and-drop gesture: a 429 mid-drag reads as a product defect. **Its own pot rather than the reports one** — at 60/min an ordinary grooming session would be refused inside three minutes. Past it: `429` + `Retry-After`, **never** a narrowed section, a smaller cap or a truncated view. **This number does not protect your connection pool and cannot**: one planning aggregate holds one connection across up to 32 statements and `DB_STATEMENT_TIMEOUT_MS` bounds each statement, not their sum — that is `EXPENSIVE_READ_MAX_IN_FLIGHT`'s job, and the planning surface takes permits from that existing share with **no dial of its own**, so nothing here has to sit below `DB_POOL_MAX_SIZE` and nothing here can stop a boot. Counted in-memory per instance, so N replicas allow up to N × the budget per user. Valid range 1–10000; there is **no "unlimited" value** — `0` fails startup, and the off switch is `RATE_LIMIT_ENABLED` (which deliberately leaves the occupancy bound in force). Identical in `dc` and `cloud`. **Leave the line out to get the default — `PLANNING_REQUESTS_PER_MINUTE=` is an empty value, not an absent one, and it stops the boot rather than restoring 240** |
| `INVITE_MAX_PER_SENDER_PER_HOUR` | `20` | Invitations **one account** may send per hour. A ceiling on the invitation **mailer**, not on the invitations table: without it any account that can log in can make this instance send mail to any address on the internet, from a workspace it created seconds ago, and that mail carries your domain's SPF/DKIM — so a stranger's spam complaints land on the reputation that also carries your verification and reset mail. Counted **in memory, per node**, so N replicas allow up to N × this. Past it: `429` + `Retry-After`, and the message tells the admin their already-sent invitations are unaffected. Valid range 1–1000; **no "unlimited" value** — `0` fails startup, and the off switch is `RATE_LIMIT_ENABLED`. Identical in `dc` and `cloud`. **Leave the line out to get the default — `INVITE_MAX_PER_SENDER_PER_HOUR=` is an empty value, not an absent one, and it stops the boot rather than restoring 20** |
| `INVITE_MAX_PER_SENDER_PER_DAY` | `100` | The same, per day — and **this is the quota control**. What is at risk is a *monthly* provider quota (Resend's free tier is 3000 messages a month), and any per-minute ceiling loose enough for a human is ~28 800/day, so the daily window is the one that means anything here; there is deliberately **no per-minute window at all**. The two windows are independent: spending the hourly allowance five times over ends the day, and the daily refusal does not lift when the hour rolls. **This is the one you will raise** — onboarding 300 people on install day hits it, and the fix is this variable for a day, not a code change. Valid range 1–10000; `0` fails startup. **Leave the line out to get the default — `INVITE_MAX_PER_SENDER_PER_DAY=` is an empty value, not an absent one, and it stops the boot rather than restoring 100** |
| `INVITE_RECIPIENT_COOLDOWN_MINUTES` | `60` | Minutes one account must wait before inviting **the same address** again, **across every workspace on the instance**. The abuse this closes is one victim and a succession of workspaces the abuser creates for free, so any per-workspace bound looks at the wrong dimension. Its state lives in the `mail_send_events` table rather than in memory, and **not for durability** — derived from the invitations table it would be cleared by the victim's own *decline* button, i.e. the product would punish the exact action it asks the victim to take. Consequently this ceiling is **cluster-wide and exact**, unlike every other limiter here. It counts the destination **inbox**, not the spelling of the address: `+tag` suffixes are stripped, quoted local parts (`"victim"@…`) are unquoted, the domain is normalised to its ASCII/punycode form, and Gmail's dots and `googlemail.com` alias are folded — so `victim+1@`, `"victim"@` and `v.i.c.t.i.m@googlemail.com` cannot be used to get a fresh cooldown each time. Only published facts about delivery and standard normalisations are folded; addresses differing by a Unicode confusable stay **different** keys, because that would be a guess about identity and would spend a stranger's allowance. Valid range 1–1439 — one minute **inside** the fixed 24 h volume window, because a cooldown as wide as the window it sits inside is refused at startup — and it must stay **inside `INVITE_EVENT_RETENTION_DAYS`** — the rows this counts are swept on that window, so a cooldown longer than the retention would silently shorten to it; startup fails on that pair rather than letting it drift. `0` fails startup. **Leave the line out to get the default — `INVITE_RECIPIENT_COOLDOWN_MINUTES=` is an empty value, not an absent one, and it stops the boot rather than restoring 60** |
| `INVITE_MAX_PER_RECIPIENT_PER_DAY` | `5` | **Invitations** one address may receive per day from senders other than you, *plus* how many of your own you may send to it — one number, spent on both. Counted as "my own sends one each, every **other** sender once, however much they sent": counting raw sends would let a single account spend a named person's whole daily allowance and thereby stop **every** workspace on the instance from inviting them for the rest of the day, which is a denial-of-onboarding aimed at a human rather than a defence of one. This way five throwaway accounts inviting one victim once each still trip it at the sixth, while any one account is capped at 5 sends to one inbox and occupies one of the five slots as far as anybody else is concerned — so the price of blocking somebody's invitations is a fresh mailbox per slot. Everything keyed on the sender multiplies by the number of accounts an attacker holds; this does not. Per **kind** of mail, so a future reset/verification cap is a separate bucket — a stranger's invitations must never suppress a victim's own password reset. The known cost, stated so it is not a surprise: at the sixth invitation an innocent admin learns the address has been invited from workspaces they cannot see — one bit, only to somebody who already administers members and already knows the address; the refusal's **wording** is what keeps it at one bit, and its `Retry-After` coarsens the *deadline* (not the remaining duration — rounding the duration re-anchors the quantum to "now", so a caller who probes twice watches it step down onto the true instant). Every probe therefore reports the same moment, and the emitted number decreases one per second rather than being a round multiple. That raises the price of another tenant's send instant; a caller who simply waits for the refusal to lift still learns it, as with any retryable window. If it proves noisy, **raise it rather than removing it — but in small steps**: because the count is "own sends one each, other senders once", N colluding accounts can deliver `N × (cap − N + 1)` messages into one inbox in a day, i.e. `floor((cap+1)² / 4)` at worst — **9/day at 5, but 30/day at 10**. The bound is quadratic, so doubling this number triples the harassment ceiling. Also cluster-wide and exact, and keyed on the inbox rather than the spelling. Valid range 1–1000; `0` fails startup. **Leave the line out to get the default — `INVITE_MAX_PER_RECIPIENT_PER_DAY=` is an empty value, not an absent one, and it stops the boot rather than restoring 5** |
| `INVITE_EVENT_RETENTION_DAYS` | `7` (min `2`) | Days of `mail_send_events` history kept. It has to outlast **every** ceiling window, because a swept row is a row no ceiling can count — set it below one and that ceiling silently shortens to it, so **startup fails on the pair** instead. It covers every ceiling in the table above, not just the invitation ones — the widest of them is `INVITE_MAX_PER_RECIPIENT_PER_DAY`'s **fixed 24 h**, which no variable can lower, and no ceiling anywhere in the product may declare a window wider than that (the code enforces it at startup rather than trusting this sentence). Startup compares the retention against that width, which is why the range starts at **2** and not 1: one day would be *exactly* 24 h against a 24 h window, and a replica whose sweep clock runs a second ahead then deletes a row another replica's daily count still needs — the cap under-counts and permits a send it meant to refuse, with no error and no log line. The rest of the window is forensic: after the `MailDailyVolumeHigh` alert fires this table is the only place that can answer *who* and *which addresses* — no metric is allowed to carry an address or an id — and it is short enough not to be a durable record of who-emailed-whom. The sweep runs **whether or not `RATE_LIMIT_ENABLED` is true**, because events are recorded either way. **It is not the whole lifetime of the table.** Rows written for an *anonymous* caller — the `AUTH_MAIL_*` mailers below, the only rows an unauthenticated stranger can make you write, and the ones whose forensic value is a sender id they do not have — carry a second cutoff of **2 days**, fixed in code and not settable at all. Their real lifetime is therefore `min(2 days, this value)`: at the default of `7` the anonymous cutoff reaches them first, and at `2` the two coincide exactly. The general sweep has no sender predicate, so it reaches those rows too — this variable is not "out of reach" of them, merely usually slower. Valid range 2–90; `0` and `1` fail startup. **Leave the line out to get the default — `INVITE_EVENT_RETENTION_DAYS=` is an empty value, not an absent one, and it stops the boot rather than restoring 7** |
| `AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES` | `1` | Minutes **one address** waits before another password-reset (or another verification) link can be sent to it, **whoever asks**. These mailers are the ones anybody on the internet can aim at a stranger, and until this existed their only budget was `RATE_LIMIT_AUTH_IP_PER_MINUTE` — a budget keyed on where a request came from, which can always be widened by coming from somewhere else. That looked adequate only while `RATE_LIMIT_TRUST_FORWARDED_FOR` was not reaching the app and every request in the world shared one bucket; with it on (this stack's default) the bucket is one per **visitor**, so a proxy pool has as many as it likes and can point all of them at one person's inbox — mail sent by you, from your domain, with their address in `To`. The flows are anonymous, so there is no per-sender half: every request shares one bucket per address. Same mechanism and same `mail_send_events` table as the `INVITE_*` ceilings, so it is **cluster-wide and exact**, and it counts the destination **inbox** rather than the spelling — `a+1@gmail.com`, `a+2@gmail.com` and `a.1@googlemail.com` are one bucket, which is the point: keyed on the spelling, one keystroke defeats the whole control. Valid range 1–14; the top of the range is one minute **inside** the narrowest of the fixed windows below (15 minutes, password reset — one variable feeds every one of them), because a cooldown **as wide as** the window it sits inside already makes that window's cap unreachable: the first send starts a cooldown that runs to the far edge, so nothing is left to count towards the cap. Startup refuses to build such a policy. `0` fails startup. **Leave the line out to get the default — `AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES=` is an empty value, not an absent one, and it stops the boot rather than restoring 1** |
| `AUTH_MAIL_MAX_PER_RECIPIENT_PER_WINDOW` | `5` | How many of **each** kind of auth mail — reset, verification, registration — one address may receive per **window**, across everybody who asked. Separate buckets per kind, so a stranger flooding one flow cannot suppress the other; counts are taken per `email_type` for exactly that reason. **`POST /api/auth/register` has a bucket of its own** — it used to share the verification one, and that was the hole rather than the economy: `resend-verification` records its slot *before* the account lookup and unconditionally (it must, or the row itself answers whether the address is registered), so at an address with **no account** it sends no mail, logs nothing, and still filled register's ceiling. Five such requests an hour therefore locked every spelling of a stranger's inbox out of signup, for free and with no signal on either side. **So this number is per bucket, there are three of them, and each is spent over its own window — the bound on one inbox is a sum of rates, not a multiple of this number.** At the default of `5`: **20** reset emails an hour (a quarter-hour window refills four times inside one), **5** verification and **5** registration = **30 auth emails an hour into a single inbox**. **The windows are fixed, are not configurable, and are not the same width: 15 minutes for password reset, 1 hour for verification, 1 hour for registration.** Reset is narrower because a ceiling on a **recovery** flow is also a denial of it — anybody can type your users' addresses into forgot-password, so whoever fills that window decides how long that person cannot reset their own password, and nothing tells them. But **sustained denial is achievable at every width** (no per-address ceiling can be built that cannot be filled), so a wide window buys no prevention — only a higher effort for the attacker. What it really buys is the mail bound, and what it costs is the length of a **hit-and-run** lockout: five requests, fired once, buy the whole window. 20 reset mails an hour into one inbox is a nuisance and still a factor of 3 under the deliverability number; an hour of silent lockout bought for five requests is not. Verification keeps the hour: it locks nobody out of an account they already hold. **Raising this moves two things in opposite directions** — more mail one address can be sent in a window, *and* less ability for somebody to withhold that address's own recovery for the rest of it — so move it in small steps. Valid range 1–1000; `0` fails startup. **Leave the line out to get the default.** ⚠️ **The refusal is invisible on two of the three endpoints, and the sustained attack is invisible on all of them** — see the note under this table |
| `ROLES_MAX_CUSTOM_PER_WORKSPACE` | `50` | Custom roles per workspace, counted across **both** scopes (workspace + project) with `built_in = false`; the 8 built-in templates belong to no workspace and never count. Creating past the cap is a 409 `ROLE_LIMIT_REACHED`. **A sprawl guard, never a licence check** — custom roles are a product feature, not a plan feature, so this is identical in `dc` and `cloud` and is never profile-gated. Valid range 1–500; an out-of-range value fails startup instead of being clamped. The count is taken under a row lock on the workspace, so the cap is exact rather than advisory — which also makes a duplicate one of the calls that can lose a lock race and answer a retryable `409` + `Retry-After` (bounded by `DB_LOCK_TIMEOUT_MS`) |
| `DEFAULT_PROJECT_ACCESS_MODE` | `OPEN` | Project-access mode a **newly created** workspace starts in. `OPEN` — everyone in the workspace can work in every project through its default role; add someone to a project only to give them a *different* role. `STRICT` — only people added to a project can change anything in it (everyone can still **see** every project: it narrows writes, never reads). Applies at creation only and **never moves an existing workspace** — change one in Workspace settings → General. Demo seeding uses the same code path, so `STRICT` gives you a strict demo workspace too. Identical in `dc` and `cloud`: access modes are a product feature, not a plan feature. An unrecognised value **aborts startup** rather than falling back |
| `ATTACHMENT_MAX_FILE_SIZE` | `20MB` | Per-file size limit enforced in-app (the business limit; kept app-side so a future admin setting can tune it). Must stay **at least 5% below** `ATTACHMENT_MAX_UPLOAD_SIZE`, and **the boot refuses the pair** if it is not — equality is not enough, because the bundled Caddy reads that same variable and reads `MB` as 10⁶ where Spring reads 2²⁰, so `25MB`/`25MB` would leave the top ~4.6% of the size range this instance advertises refused at the edge with a bare `413` and no Hamstrack error body |
| `ATTACHMENT_MAX_UPLOAD_SIZE` | `25MB` | Hard servlet/DoS ceiling (multipart parse limit). **Read twice — by the application and by the bundled Caddy**, whose `request_body max_size` comes from this same variable, so raising it moves both and they cannot drift. That edge limit is the only bound on an oversized body: Spring resolves multipart *before* it maps a handler, so a body over the ceiling is streamed to a temp file and refused with `413` without spending any rate-limit budget at all — the proxy refuses it before the application reads a byte. Caddy reads `MB` as 10⁶ and Spring as 2²⁰, so the edge sits ~5% tighter than the servlet ceiling (the safe direction); keep `ATTACHMENT_MAX_FILE_SIZE` comfortably below this — the boot refuses a pair that is not at least 5% apart — or write both as plain byte counts, which the two read identically. **No typo here can leave an unbounded proxy in front of a bounded app**: `DataSize` accepts at most a two-letter unit, so `25MiB` or `25G` fails the *application's* boot, and a value Caddy cannot parse fails *Caddy's* start. Both misparse directions fail closed; what a typo costs you is a stack that will not start. **If you front the stack with your own proxy**, set its body limit yourself — see [TLS & reverse proxy](#tls--reverse-proxy) |
| `ATTACHMENT_ALLOWED_EXTENSIONS` | (images, pdf, office, text, zip…) | Comma-separated allow-list of uploadable file extensions (case-insensitive) |
| `STORAGE_QUOTA_ENABLED` | `true` | Whether a workspace's total attachment storage is **capped**. `ATTACHMENT_MAX_FILE_SIZE` above refuses one large file; nothing refused the ten-thousandth small one until this existed. **This is deliberately NOT under `RATE_LIMIT_ENABLED`** — folding them together would mean that removing a bound on your disk requires disabling brute-force protection on your login page, and that debugging a rate limiter requires removing the disk bound. Two kinds of control, two switches; `RATE_LIMIT_ENABLED=false` does **not** stop the quota refusing uploads. Setting this `false` disables the **refusal**, not the bookkeeping: usage is still counted, still shown on Workspace settings → Storage and still reconciled — an instance that turns the quota on later must not resume from a blank number, and the figure you need in order to *choose* a quota is the one you already use. **Cluster-wide and exact** (state in PostgreSQL, like the recipient-keyed mail ceilings and unlike every in-memory budget), so it neither divides by replica count nor re-arms on a redeploy |
| `STORAGE_QUOTA_WORKSPACE_BYTES` | `100GB` self-hosted · `10GB` Cloud | The ceiling itself, per workspace. Same code, same table, same `409` in both models — only the number differs, and it differs because what it protects differs: on a self-hosted install you own the disk, signup is closed by default and there are no strangers, so the number is a **safety net against runaway growth**; on Cloud signup is public, the backend is S3 where every byte stored *and* every request made is billed, and a workspace is what a stranger gets for the price of one disposable mailbox. Past it an upload answers **`409` `STORAGE_QUOTA_EXCEEDED`** carrying `quotaBytes`/`usedBytes`/`availableBytes`/`fileBytes`, and **no `Retry-After`** — waiting never frees a byte, so a retry hint would be an instruction that cannot work. **It bounds growth and deletes nothing**: no path deletes, archives or expires an attachment because of this setting, and reads and downloads are never quota-gated, so a full workspace stays fully readable. Must be **≥ `ATTACHMENT_MAX_FILE_SIZE`** — a quota smaller than one permitted file admits nothing at all, and the boot **refuses that pair** rather than letting the first upload after a deploy discover it. ⚠️ **Which of the two defaults you get follows `SPRING_PROFILES_ACTIVE`, not the fact that you are self-hosting**: `.env.prod.example` ships `cloud`, so an install that never changed that line is on the **10 GB** ceiling while this row quotes 100 GB. Set the profile to `dc`, or set this value explicitly — an explicit value wins over both defaults and cannot be surprised by a profile. ⚠️ **Before lowering it, or enabling it on an install that already has content**, see the note under this table: a quota introduced silently at a value somebody is already past is indistinguishable from an outage |
| `STORAGE_QUOTA_WARN_PERCENT` | `80` | The share of the quota at which the app starts **saying so** — the line beside the upload control and the level `StorageFillHigh` is sized against. It changes no server behaviour: nothing is refused, narrowed or slowed at this threshold. It exists so the first thing a team hears about the ceiling is not a refusal. Valid range 1–99 |
| `STORAGE_QUOTA_RECONCILE_CRON` | `0 20 3 * * *` | When to re-check that the stored counter still equals the attachment rows it claims to count. The counter is maintained by a **database trigger** — that is the mechanism; this is only the **witness**, and drift is expected to be exactly zero. Spring cron syntax (six fields, seconds first). **An empty value disables the schedule**, which is a supported choice and is **not silent**: `hamstrack_storage_drift_refreshed_at_age_seconds` then rises from process start and `StorageDriftGaugeStale` fires, which is the documented consequence of the setting rather than an accident. An **invalid** cron still fails the boot — a typo is not a decision. Disabling it does not disable the counter or the quota |
| `WRITE_REQUESTS_PER_MINUTE` | `180` | How many **mutating** requests **one user** may make per minute across `…/workspaces/*/projects/*/issues/**` — issue create/update/delete, comment create/update/delete, attachment upload/delete and backlog rank. `GET` on the same path is **not** counted: the binding is method-conditioned, so board and issue reads are untouched. It covers **all** mutating verbs and not only `POST`, because a client refused on the create simply retries with the patch, and an update is not the cheap half — it writes history rows, bumps a version and fans out SSE and notifications to every watcher. Until this existed the entire write surface had no budget of any kind while reads and authentication had three. `180` is 3/s sustained, sized against the SPA's inline-edit saves and board drags. Past it: `429` + `Retry-After`. Counted **in memory, per node**, so N replicas allow up to N × this. Valid range 1–10000; **no "unlimited"** — `0` fails startup, and the off switch is `RATE_LIMIT_ENABLED`. **Leave the line out to get the default — `WRITE_REQUESTS_PER_MINUTE=` is an empty value, not an absent one, and it stops the boot rather than restoring 180** |
| `WRITE_UPLOAD_BYTES_PER_MINUTE` | `250MB` | How many uploaded **bytes** one user may push per minute, summed over the **parsed** sizes of their uploads — never a client-declared `Content-Length`, which is a number the client sets. A separate denomination because **neither other control bounds bytes**: the request budget above counts requests, and one 20 MB upload is not one comment; the workspace quota counts *cumulative* bytes and never sees churn, since upload → delete → upload leaves the workspace total exactly where it started while billing every PUT and every stored byte in between. Past it: `429` + `Retry-After` (unlike the quota's `409`, the wait here is real — the window does empty). Must be **≥ `ATTACHMENT_MAX_FILE_SIZE`**, or a file this instance permits could never be uploaded and every attempt would answer `429` telling the caller to wait for room a fixed one-minute window can never make; the boot **refuses that pair**. In memory, per node. Off switch: `RATE_LIMIT_ENABLED`. **Empty stops the boot rather than restoring 250MB** |
| `EXPENSIVE_READ_LIMIT_ENABLED` / `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` / `EXPENSIVE_READ_MAX_IN_FLIGHT` / `EXPENSIVE_READ_ACQUIRE_WAIT_MS` | `true` (3 / 6 / 1000) | **How many expensive reads may be RUNNING at once — the bound the per-minute budgets above are not.** A rate budget spends the same unit whether a request takes 8 ms or 8 s, so its protection evaporates exactly as an instance slows down; this one tightens instead. Through the expensive-read surface — every read that holds a connection while it works, today `…/reports/**`, `…/search/**`, `…/filters/**`, `…/storage/projects` and `…/projects/*/backlog/**` (the planning view and its sections, which joined this share in 0.18.0 rather than getting a share of their own: a second ceiling would have to sit below `DB_POOL_MAX_SIZE` *jointly* with this one, which on a small pool is 1 permit per surface) — no user may occupy more than `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` of a replica's connections and no set of users more than `EXPENSIVE_READ_MAX_IN_FLIGHT`, **so the rest of the API always retains `DB_POOL_MAX_SIZE − EXPENSIVE_READ_MAX_IN_FLIGHT` of them**. It is a counted share of the one pool, not a second pool. Past the share: `429` with `errorType` `TOO_MANY_IN_FLIGHT` (your own requests — let one finish) or `EXPENSIVE_SURFACE_BUSY` (the instance's share — retry shortly), both with `Retry-After: 1`, because what you are waiting for is a request that ends rather than a window that rolls. `EXPENSIVE_READ_MAX_IN_FLIGHT` must be **strictly less than `DB_POOL_MAX_SIZE`** and at least the per-principal number, or the app **refuses to start** naming both; above **60 % of the pool** it logs one sizing WARN at every boot, which is legitimate on a large pool and is not silenceable. **Both ceilings ship unset and are then derived from your pool** — 60 % of `DB_POOL_MAX_SIZE` capped at 6, with the per-user ceiling clamped to fit, which is exactly 3 and 6 on the default pool of 10 — so an install that has never named these variables cannot be stopped from booting by them, whatever its pool size. Set one and it is obeyed exactly and checked hard. The wait is the only one of the three that accepts `0` (refuse immediately); `0` on either ceiling fails startup, and a blank value stops the boot rather than restoring the default. Either ceiling also accepts **`-1`, which asks for the derived value explicitly** — the same thing leaving the line out does, and the only way to ask for it when your environment comes from a systemd `EnvironmentFile`, an ECS task definition or a Kubernetes ConfigMap, where there is no line to comment out. Valid ranges are therefore **1–100 (or `-1`)** per user and **1–1000 (or `-1`)** per instance. The wait's own ceiling is **2000 ms**, and it is the one number here denominated in Tomcat worker threads rather than in connections: a waiting request holds a worker, so a longer wait multiplies the thread cost of every refusal. **Its off switch is its own and is NOT `RATE_LIMIT_ENABLED`** — removing a bound on your connection pool must not require disabling brute-force protection on your login page. Per **process**, which against the pool is exactly right (the pool is per process too); against a shared `max_connections`, the number to check when scaling out is `EXPENSIVE_READ_MAX_IN_FLIGHT × replicas`. **New in this release, and on a small busy box the symptom is a `429` where yesterday there was a slow `200`** |
| `PUBLIC_SIGNUP_ENABLED` | `false` | Self-registration is **closed by default** on self-hosted installs — create accounts in the Admin console (Users → New user → share the setup link, no email needed). Set `true` to let anyone register |
| `PUBLIC_LANDING_ENABLED` | `true` | `false` hides the public landing page (`/` redirects to login, crawlers disallowed) |
| `TERMS_ACCEPTANCE_REQUIRED` | `true` | `false` removes the required terms checkbox at registration |
| `PRIVACY_CONTACT_EMAIL` | *(empty)* | Address the in-app **Account** page tells a user to write to when they want their account deleted. It is served on the **public, unauthenticated** `GET /api/meta`, so whatever you set here is **published** to anyone who can reach the instance — use an address you are willing to have on the open internet, and expect it to be scraped like any address on a web page. Empty is the default and a supported answer: it does **not** hide the deletion section, which still explains what deletion does and tells the user this installation's administrator handles the request — an affordance that appeared only where somebody remembered to set a variable would be missing for exactly the operators who did not know it existed. Nothing here creates or monitors a mailbox, and the product promises no reply time: the deletion itself is carried out by the operator, out of band. A malformed value **aborts startup** rather than being published: it must be a single address of at most 255 characters, and the characters `mailto:` treats as separators (`? & # % , ; < >`), whitespace, quotes and backslashes are refused. |
| `DEMO_SEED_ON_FIRST_LOGIN` | `true` | `false` disables the demo workspace seeded on a user's first login |
| `RATE_LIMIT_ENABLED` (+ `RATE_LIMIT_AUTH_IP_PER_MINUTE`, `RATE_LIMIT_LOGIN_FAILURE_THRESHOLD`, `RATE_LIMIT_LOGIN_BACKOFF_BASE_SECONDS`, `RATE_LIMIT_LOGIN_BACKOFF_MAX_SECONDS`) | `true` (15 / 5 / 30 / 900) | Brute-force protection on auth endpoints: per-IP budget + per-account login backoff, `429` + `Retry-After`. `RATE_LIMIT_ENABLED` is **not** auth-only — it is the master switch for every limiter that has an off switch, including the search, report, **planning** and **write** budgets (`PLANNING_REQUESTS_PER_MINUTE`, `WRITE_REQUESTS_PER_MINUTE`, `WRITE_UPLOAD_BYTES_PER_MINUTE`) and every recipient-keyed mail ceiling (`INVITE_*`, `AUTH_MAIL_*`); see the rate-limiting section. It does **not** reach the workspace storage quota (`STORAGE_QUOTA_ENABLED`, its own switch), the expensive-read occupancy bound (`EXPENSIVE_READ_LIMIT_ENABLED`, likewise) or the backlog-rebalance cooldown (no switch at all) |
| `SEED_ADMIN_EMAIL` / `SEED_ADMIN_DISPLAY_NAME` / `SEED_ADMIN_PASSWORD` | — | Optionally create/promote a system administrator on startup (access to the `/admin` console) — **both** email and password required. `SEED_ADMIN_PASSWORD` is capped at **72 UTF-8 bytes** — BCrypt's own ceiling, so it is the cap on every password the application stores, and bytes are not characters (Cyrillic costs 2 each, CJK 3, emoji 4). A longer value **aborts startup** rather than being truncated, but only where it would actually create the account: email set, over the limit, and nobody at that address yet |

### Turning the storage quota on where there is already content

`STORAGE_QUOTA_WORKSPACE_BYTES` takes effect on the **next request** — nothing is cached,
nothing is migrated, and nothing warns anybody. So enabling it (or lowering it) on an
instance that already holds attachments can stop every upload in a workspace the moment the
deploy completes, and from the inside that is indistinguishable from an outage: the people
affected get a `409` naming two numbers, and the refusal deliberately tells them to do
nothing, because there is no action every reader of it can perform.

Do it in two changes rather than one:

```sql
-- What your workspaces already hold. Run this BEFORE you choose a number.
SELECT workspace_id, bytes_used, attachment_count, updated_at
  FROM workspace_storage_usage
 ORDER BY bytes_used DESC
 LIMIT 20;
```

1. Set `STORAGE_QUOTA_WORKSPACE_BYTES` **above the largest existing workspace** and deploy.
2. Watch `hamstrack_storage_quota_fill_max` (Grafana; `StorageFillHigh` fires at 0.9) for a
   week, and `hamstrack_storage_quota_refused_total` for zero.
3. Lower it deliberately, as its own change, once you know what the distribution looks like.

The reverse direction needs no ceremony: **raising** the quota is immediate and affects
nothing else. And `STORAGE_QUOTA_ENABLED=false` is always available as the fast way out — it
stops the refusals while leaving the usage numbers, which are the ones you need in order to
pick a value.

### Attachments where the row and the object disagree (orphans)

**The quota counts attachment *rows*, and rows and stored objects can each exist without the
other.** Two paths produce a mismatch, and they run in opposite directions:

- **An orphan object** — a delete succeeds and the follow-up blob deletion fails. The row is
  gone, the object is not (logged at ERROR, never allowed to fail the request). Your store
  charges for it; the quota does not, because the row it counted is gone. This one is in the
  tenant's favour.
- **An orphan row, which is the expensive one** — an upload's blob write **fails**, and the
  compensating row delete then fails too (logged at **WARN**, carrying the workspace id and
  the byte count for exactly this reason). There is no object: the store is what failed. What
  survives is a row holding `size_bytes` for a file that was never written, so the workspace
  **pays quota for nothing**, and the nightly reconciler cannot see it — it compares the
  counter against the rows, the row exists, so the counter is correct and there is no drift to
  report. Nothing else will ever notice. The remedy is to delete that row (its id is in the
  log line) and the trigger returns the bytes.

Neither is visible to the quota — it counts rows — and your store still charges for the first.
There is no online sweeper on purpose: an interface method that lists a bucket invites a caller
that pages it on a request thread. It is an operator procedure, and the live key set comes
out of the database:

```sql
-- The keys that SHOULD exist for one workspace.
SELECT storage_key FROM issue_attachments WHERE workspace_id = '<workspace-uuid>';
```

- **Local disk (`STORAGE_TYPE=local`)** — walk `ws/<workspace-uuid>/` under
  `STORAGE_LOCAL_DIR`, subtract the keys above, delete the difference.
- **S3 (`STORAGE_TYPE=s3`)** — list with `list-object-versions`, not `list-objects`, and
  delete **every version and every delete marker**: on a versioned bucket a plain
  `DeleteObject` writes a marker and keeps paying for the object underneath it.

Do it with the application stopped, or accept that an upload in flight will look like an
orphan. Sweeping the **store** never touches `workspace_storage_usage`: the counter follows
rows, so deleting objects that no row names changes nothing the quota enforces. The other
direction is the one that does — deleting a stranded **row** (the WARN case above) returns its
bytes to the workspace, because the same trigger that counted them decrements them.

Every variable the **application** reads is wired to a Spring property via a
placeholder in `application.properties` (e.g. `DB_URL` → `spring.datasource.url`,
`MAIL_HOST` → `spring.mail.host`, `APP_BASE_URL` → `app.base-url`). A variable that
sizes the **container** rather than the application is read by Docker Compose and
never reaches the JVM (`APP_MEMORY_LIMIT`, `POSTGRES_MEMORY_LIMIT`,
`CADDY_MEMORY_LIMIT` and `POSTGRES_SHM_SIZE`, which size containers, plus the three
`POSTGRES_*` server dials, which Compose passes to the database rather than to Spring);
those rows say so, and they take docker or PostgreSQL units rather than Spring ones. The names above are the supported
configuration surface — prefer them over setting Spring properties directly.

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
    # Match the upload ceiling. The repository's own Caddyfile takes this from
    # {$ATTACHMENT_MAX_UPLOAD_SIZE:25MB} so the two cannot drift; a literal is fine
    # if you keep your own file. Note the units: Caddy reads MB as 10^6 where the
    # application reads it as 2^20, so this sits ~5% below the servlet ceiling.
    request_body {
        max_size 25MB
    }
    reverse_proxy app:8080
}
```

**Set a body limit whichever proxy you use.** It is not only about rejecting junk earlier:
Spring parses a multipart upload *before* it maps a handler, so an over-sized body reaches the
application, is streamed to a temp file and is refused with `413` having spent **no** rate-limit
budget — the one upload lane the application's own controls cannot charge for. The proxy is
where that is refused cheaply.

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
| `MAIL_ASYNC_QUEUE_CAPACITY` | `100` | queued mails before new ones are dropped and dead-lettered |
| `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` | `15` | graceful drain at shutdown, before the rest is dead-lettered |
| `APP_STOP_GRACE_SECONDS` | `30` | the container's `stop_grace_period`, which the drain above has to fit inside |
| `MAIL_CRITICAL_MAX_ATTEMPTS` | `3` | verification/reset send attempts before dead-lettering to `failed_email` |
| `MAIL_CRITICAL_RETRY_BACKOFF_MS` | `2000` | base backoff between CRITICAL-mail retries |
| `MAIL_FAILED_EMAIL_RETENTION_DAYS` | `90` | how long dead-letter rows are kept before a daily sweep |
| `MAIL_NEVER_ATTEMPTED_MAX_PER_HOUR` | `500` | per-instance hourly cap on rows for never-attempted mail |

Only verification + password-reset mail is CRITICAL (retried, then
dead-lettered); invite mail stays best-effort with no retry.

**No request thread ever performs SMTP.** When the queue is full the message is
dropped and — if it is CRITICAL — a `failed_email` row is written instead. That is a
deliberate reversal of the older backpressure design: a slow mail host used to make
each dispatch a synchronous send on a Tomcat worker for up to ~34 s, from endpoints
including unauthenticated registration, which got worse the slower the host was. The
trade is that a **best-effort** message (an invite) refused by a full queue is now
dropped rather than sent slowly, and the only trace is a server-side ERROR: the
invitation row is committed and the sender was already told it went. Re-send it.

**`failed_email` now records two different things and says which.** A row with
`attempts >= 1` means the send was tried and failed; a row with `attempts = 0` and a
`last_error` beginning `NEVER ATTEMPTED` means the message was never sent at all, and
the bracketed token after that prefix says which of the three ways — `[QUEUE_FULL]`,
`[POOL_SHUT_DOWN]` or `[SHUTDOWN_RESIDUE]` (a deploy landing on a backlog). Sort a
burst with `GROUP BY left(last_error, 40)`. The second kind is much more likely to
succeed if you re-drive it. The
table is swept daily (`MAIL_FAILED_EMAIL_RETENTION_DAYS`) and the never-attempted
kind is rate-capped per instance (`MAIL_NEVER_ATTEMPTED_MAX_PER_HOUR`), because a row
of that kind costs one refused dispatch rather than one exhausted retry cycle.

**Shutdown.** The app waits `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` for queued mail, then
writes whatever is still queued to `failed_email` in one batch and logs the count. So
raising `MAIL_ASYNC_QUEUE_CAPACITY` on its own is safe. What is **not** safe is a
container stop grace shorter than the drain: the process is then killed part-way
through and the queued mail is lost with no record. **Docker's own default grace is
10 s — shorter than the drain** — so the bundled `docker-compose.prod.yml` and the
[Quick start](#quick-start) file above both set
`stop_grace_period: ${APP_STOP_GRACE_SECONDS:-30}s` and pass the same variable to the
app, which refuses to start if the drain plus the residue write would not fit inside
it. Raise `APP_STOP_GRACE_SECONDS` first and `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS`
second; the other order is refused at boot.

**Running your own compose file rather than the bundled one?** Then your app container
has Docker's 10 s grace and **this protection is not in force for you** — the app boots,
its own startup check passes (it compares against the value it was *given*, not against
what Docker will actually do), and every `docker compose up -d` SIGKILLs the JVM
mid-drain. Add both lines to your `app` service: `stop_grace_period:
${APP_STOP_GRACE_SECONDS:-30}s`, and `APP_STOP_GRACE_SECONDS:
${APP_STOP_GRACE_SECONDS:-30}` under `environment:` so the application is told the same
number. The [Quick start](#quick-start) file shows both.

**In-flight sends are outside all of this.** The drain recovers what is still
*queued*; the handful of messages a worker has already started (up to
`MAIL_ASYNC_MAX_POOL`) are interrupted and get no row — the shutdown WARN names them
as an upper bound and that line is their only record.

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

**Choose the password yourself.** The app refuses to start on the one value this project
ever published under that variable name (see [An unedited template is refused, by
design](#an-unedited-template-is-refused-by-design)) — and because seeding is idempotent,
that refusal is also the *only* notice an already-seeded install ever gets: changing the
variable later does not change the account.

**And keep it to 72 UTF-8 bytes.** That is the ceiling **BCrypt** enforces, not a limit this
project chose, so it is the limit on every password the application stores — registration and
password reset refuse a longer one too, with a `422`. Count bytes, not characters: plain Latin
letters cost 1 each, accented, Greek and Cyrillic 2, most other scripts 3, emoji 4 — so a
40-character Cyrillic passphrase is already 80 bytes, while 72 ASCII characters fit exactly and
`openssl rand -base64 48` gives you 64 of them. An over-long `SEED_ADMIN_PASSWORD` is refused
when it would actually be used to **create** the administrator account — `SEED_ADMIN_EMAIL` set
and no account at that address yet — and then the application does not start at all: it neither
truncates the value nor warns about it. An install whose administrator already exists goes on
booting whatever this variable says, for the same idempotence reason as the paragraph above.

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

**Completing a password reset stops that account's other setup links.** A setup link is
one of the account's password-reset links with a longer life, so as soon as anyone
finishes a reset on that account — through a setup link or through **Forgot password**
— every other outstanding link for it stops working, and a recipient who opens one
afterwards meets the same refusal as an expired token. Usually there is nothing to do
about that: somebody who went through forgot-password first already has the password
they chose and needs nothing further from you. If they still need a link, regenerate it.

**Regenerating does not stop the previous link.** Issuing a link never invalidates one,
deliberately — retirement is earned by proving possession of a token, and only there. So
the links accumulate: regenerate five times and that account is holding five live ones,
each a seven-day right to set its password without knowing the current one. On a
self-hosted install these links *are* the onboarding path, so that stack is yours to keep
track of — hand out as few as you can. The [`password_resets`
sweep](#what-rotating-jwt_secret-does-and-what-it-does-not) is what ends a whole
accumulated set at once, and it is what "revoke every unused setup link" means there.

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
  the multipart parser rejects anything larger at parse time (DoS guard). **The bundled
  Caddy reads the same variable** (`request_body max_size` in the repository's
  `Caddyfile`), so raising it moves the proxy limit with it. Caddy has no default limit
  of its own, which is why the shipped file sets one: a body over the ceiling that
  reaches the application has already been streamed to a temp file before any handler,
  budget or permission check sees it. With your own proxy, set the limit yourself
  (nginx `client_max_body_size`).
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
| `PRIVACY_CONTACT_EMAIL=privacy@example.com` | publishes that address on the in-app Account page as the one to write to for account deletion. Empty by default, and the section is never hidden — unset, the page tells the user their installation's administrator handles it. Also served on the public `GET /api/meta`, so setting it publishes it. A malformed value **aborts startup** rather than being published: it must be a single address of at most 255 characters, and the characters `mailto:` treats as separators (`? & # % , ; < >`), whitespace, quotes and backslashes are refused. |
| `DEMO_SEED_ON_FIRST_LOGIN=false` | disables the demo workspace seeded on first login |
| `CSP_REPORT_SINK_ENABLED=true` | serves the unauthenticated CSP violation-report endpoint and makes the report-only policy name it. Off by default on DC; leaving it off is a complete answer, since the header still ships and violations still appear in your own browser console. See [Content-Security-Policy (report-only)](#content-security-policy-report-only) before enabling it |
| `RATE_LIMIT_ENABLED` (+ tuning vars) | master switch for **every limiter that has an off switch and does not carry one of its own** — the auth brute-force protection, and each of the per-principal request budgets. **A control with a switch of its own, or with none at all, is outside it**, and that is the category to check rather than a list to extend: today it excludes the workspace storage quota (`STORAGE_QUOTA_ENABLED`), the expensive-read occupancy bound (`EXPENSIVE_READ_LIMIT_ENABLED`) and the backlog-rebalance cooldown (no variable at all), and the CSP report sink's budget (`CSP_REPORT_SINK_ENABLED`). Note it no longer says *in-memory* — the recipient-keyed mail ceilings keep their state in PostgreSQL |

Rate-limit tuning (all optional; defaults shown):

| Variable | Default | Meaning |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | master switch — turns off, in one go, **every limiter that has an off switch and does not carry one of its own**: the auth budgets in this table, every per-principal request budget (`REPORTS_REQUESTS_PER_MINUTE`, `SEARCH_REQUESTS_PER_MINUTE`, `PLANNING_REQUESTS_PER_MINUTE`, `WRITE_REQUESTS_PER_MINUTE`), the uploaded-byte budget (`WRITE_UPLOAD_BYTES_PER_MINUTE`) and every recipient-keyed mail ceiling (`INVITE_*`, `AUTH_MAIL_*`) — switching it off is the one way to make `forgot-password` send a link on every request, which is worth knowing before you do it on an instance with public signup. **What is outside it is a category, not a list to extend: a control that carries a switch of its own, or carries none at all.** Read it that way rather than by the membership below, which is what today's controls happen to be — a control added tomorrow belongs to whichever category it belongs to, and an enumeration here goes stale one entry before the list does. Today that category holds the **workspace storage quota** (`STORAGE_QUOTA_ENABLED`) and the **expensive-read occupancy bound** (`EXPENSIVE_READ_LIMIT_ENABLED`), each with its own switch because removing a bound on your disk — or on your connection pool — must not require disabling brute-force protection on your login page; and the backlog-rebalance cooldown, a fixed internal safety valve protecting the database from a whole-table rewrite storm, which has no variable at all. It also does not reach the **CSP report sink's budget** (`CSP_REPORTS_PER_MINUTE_PER_IP`, `CSP_REPORTS_PER_MINUTE`), whose own switch is `CSP_REPORT_SINK_ENABLED` — that budget is the only bound on an endpoint needing no account, so folding it in here would let "turn limiting off for a moment" mean "let anyone write unlimited lines into this log". It does **not** stop `mail_send_events` rows being written or swept — those are your forensic trail after a volume alert, and a table that only grows while a switch is off would be the worse trade |
| `RATE_LIMIT_AUTH_IP_PER_MINUTE` | `15` | per-IP request budget/min across login, register, verify, resend, forgot & reset |
| `RATE_LIMIT_LOGIN_FAILURE_THRESHOLD` | `5` | failed logins for one account before backoff starts |
| `RATE_LIMIT_LOGIN_BACKOFF_BASE_SECONDS` | `30` | first backoff delay (doubles on each further failure) |
| `RATE_LIMIT_LOGIN_BACKOFF_MAX_SECONDS` | `900` | backoff cap (15 min); a success resets the counter |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | `false` | Key the per-IP budget on the rightmost `X-Forwarded-For` entry. Enable **only** behind a trusted proxy that strips client-supplied XFF; if the app port is directly reachable, leaving this `false` stops clients spoofing XFF to bypass the per-IP limit. The bundled `docker-compose.prod.yml` **defaults** it to `true` — `${RATE_LIMIT_TRUST_FORWARDED_FOR:-true}`, because in that stack every request arrives through Caddy, which strips client-supplied XFF — and reads your `.env` when you set it, so `RATE_LIMIT_TRUST_FORWARDED_FOR=false` there is what you want if you publish the app port directly. **Set it in `.env`, never by editing the compose file on the box:** that file is replaced wholesale by the next `git pull` or [config apply](#applying-repository-configuration), which silently restores the default you were overriding. |

The limiter is **in-memory / single-node**. If you run multiple app replicas it
applies per-node (there's no shared store yet), so keep it in mind when scaling out.

The same caveat applies to the **backlog rank-rebalance cooldown** (the 429 with
`Retry-After` on `POST …/issues/{number}/rank`): it is node-local, so N replicas
allow up to N whole-project rebalances per cooldown window instead of one. It
degrades safely — the operation is idempotent and the throttle only damps an
abuse vector — and a restart re-arms the window rather than locking planners out.

The **per-principal reports budget** is node-local too (`REPORTS_REQUESTS_PER_MINUTE`,
the 429 with `Retry-After` on `/api/workspaces/*/projects/*/reports/**` — and on
`/api/workspaces/*/search/insights`, the Insights panel, which is a report that
lives on the search path and is bound to this limiter explicitly rather than by
prefix), so N replicas allow up to N × the budget per user. It degrades
safely for the same reason the cooldown does — it damps an abuse vector rather
than enforcing an invariant, so a split budget yields a weaker guard and never a
wrong answer — and what it protects is the *per-instance* connection pool, which
N replicas also have N of. A restart re-arms the window. Note the key is the
**principal**, so the budget bounds one user, not one tenant: aggregate load
still scales with member count, which is the number to size against.

So is the **per-principal search budget** (`SEARCH_REQUESTS_PER_MINUTE`,
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

The **per-principal planning budget** (`PLANNING_REQUESTS_PER_MINUTE`, the 429 with
`Retry-After` on `/api/workspaces/*/projects/*/backlog/**` — the whole planning view and
every section read under it) is node-local on the same terms and degrades the same way. It
is a pot of its own rather than a share of the reports one because a card dragged *across*
sections costs two section refreshes, so an ordinary grooming session would reach 60/min
inside three minutes — and a 429 in the middle of a drag reads as a product defect, where a
429 on a report is a delay. 240 is roughly 3.4× the busiest single tab a facilitator
produces.

**But the planning surface's real protection is not this budget, and that is worth knowing
before you tune it.** One planning aggregate holds **one** pool connection across up to 32
statements in a single read-only transaction, and `DB_STATEMENT_TIMEOUT_MS` bounds each
statement and not their sum — so a rate budget, which spends the same unit whether a request
takes 8 ms or 8 s, cannot bound it even in principle. What does is
`EXPENSIVE_READ_MAX_IN_FLIGHT`, which the planning surface shares with reports and search.
Lowering `PLANNING_REQUESTS_PER_MINUTE` is not the lever for pool safety.

**The per-principal write budgets** (`WRITE_REQUESTS_PER_MINUTE`,
`WRITE_UPLOAD_BYTES_PER_MINUTE`) are node-local on exactly the same terms and degrade the
same way. They bound the mutating side of the API — everything under
`/api/workspaces/*/projects/*/issues/**` for `POST`/`PUT`/`PATCH`/`DELETE`, plus the
uploaded-byte total spent at the attachment door — and they are two properties rather than
one because a request count does not bound bytes and most mutations carry none. `GET` on
the same path is deliberately outside both; the binding is method-conditioned, so board and
issue reads are never charged.

**The expensive-read occupancy bound is per process too, and here that is exactly right
rather than a weakening — this is the one sentence to get the right way round.** For a
per-minute budget, counting per node is a loss: N replicas allow N × the budget for one
user. `EXPENSIVE_READ_MAX_IN_FLIGHT` bounds how much of *a replica's connection pool* the
expensive-read surface (reports, HQL search, saved filters, the storage breakdown and the
planning reads) may hold at once, and the pool is per
replica as well — so the ratio between the two is invariant as you add replicas and the
guarantee ("the rest of the API always retains `DB_POOL_MAX_SIZE − EXPENSIVE_READ_MAX_IN_FLIGHT`
connections") holds on every one of them with no coordination at all. What does *not* scale
neutrally is the database behind them: the instance's aggregate ceiling against a shared
`max_connections` is `EXPENSIVE_READ_MAX_IN_FLIGHT × replicas`, which is the number to check
when scaling out, beside the `work_mem × nodes × backends` arithmetic. It is also outside
`RATE_LIMIT_ENABLED`, with a switch of its own, for the same reason the storage quota is.


### Content-Security-Policy (report-only)

Every response carries a **`Content-Security-Policy-Report-Only`** header. *Report-only* means
the browser enforces nothing: it checks each thing a page loads against the policy and, where
the policy would have refused, writes a console message and — if the report sink below is on —
posts a small JSON report back. **Nothing about the product's behaviour changes**, and no
configuration in this release makes the policy enforcing. The policy deliberately omits the
usual escape hatches so that violations are *measured* before anything is enforced; enforcing
it is a later, separate decision made from what the reports actually contain.

So during this period **violation reports are the feature working, not a fault**, and one of
them is expected immediately: the API documentation page loads a validator badge image from
`validator.swagger.io`, which `img-src 'self' data:` reports. It is left in place on purpose as
the proof that collection works at all — an empty report stream and a broken sink look exactly
alike.

The policy names **no address of yours**: every source in it is `'self'`, `'none'`, `data:` or
one of the two Google Fonts origins the SPA loads its typefaces from. That is why one
version-controlled string is correct for every installation and why there is nothing here to
configure per host.

| Variable | Default | Meaning |
|---|---|---|
| `CSP_REPORT_ONLY_ENABLED` | `true` | Whether the header is sent at all. On in both `dc` and `cloud`. ~280 bytes per response, and on HTTP/2 an identical header repeats as a dynamic-table index of a few bytes after the first, so the cost of a 40-request page load is ~280 bytes rather than 11 KB. Set `false` only if the header itself is a problem for you; violations then have no witness at all |
| `CSP_REPORT_SINK_ENABLED` | `false` (`true` on `cloud`) | Whether **this instance collects** reports, by serving `POST /api/security/csp-report` — and, by the same property, whether the header names a `report-uri`. One setting for both halves, so there is no configuration in which the header names an endpoint that does not exist. **Read the paragraph below before turning it on** |
| `CSP_POLICY` | *(empty)* | Replaces the built-in directive string entirely. Leave it empty unless you have a reason. **Checked at startup: the app refuses to boot on a value containing a control character**, because this string goes verbatim into a response header where a carriage return does not break the policy — it splits the response. Max 2000 characters. It **also refuses to boot on a `report-uri` aimed at this instance that this instance does not serve** — one with no host (a browser resolves it against the page it is on, which is a page you served) or with *your* `APP_BASE_URL` host must be exactly `/api/security/csp-report`, matched case-sensitively, without a trailing slash and with percent-encoding decoded first, and `CSP_REPORT_SINK_ENABLED` must be `true`; otherwise every violation a browser sees is a POST answered `405`. It is the mistake to expect, since the obvious way to write this variable is to copy the header off a running Hamstrack Cloud page. A `report-uri` on **any other host is untouched — including another Hamstrack** acting as a central collector for a fleet, whose URL contains that path by construction |
| `CSP_REPORTS_PER_MINUTE_PER_IP` | `60` | The sink's per-sender budget, **counted in log lines** (a batched request spends one token per line it writes, so this is not a request count). Past it: `429` + `Retry-After`. A worst-case page in which *every* subresource is refused produces at most 22 reports, so 60 leaves roughly 3× headroom over a client hard-navigating twice a minute. In bytes: ~70 KB a minute, about **100 MB a day** from one address. Valid range 1–10000; no "unlimited" value |
| `CSP_REPORTS_PER_MINUTE` | `600` | The instance-wide ceiling, 10× the per-sender one, in the same unit: 600 lines a minute ≈ **~700 KB a minute, about 1 GB a day** if it is saturated every minute of every day. A sink hearing from ten simultaneously-violating clients has already told you everything it can and must not become your log budget. Valid range 1–100000 |

**Before you turn the sink on.** It is an **unauthenticated, public** endpoint — it has to be,
because a browser sends a violation report with no credentials. It stores nothing (no table, no
row, no file), answers `204` to everything including bodies it threw away, refuses bodies over
16 KB, drops reports about pages this instance does not serve, and has the budget above. What it
costs you is **log lines**: one `INFO` line per accepted report, **of at most ~700 bytes of field
values** (~1.2 KB on the wire with the field names and the log envelope) — every field in it is
individually truncated **in UTF-8 bytes**, query strings are stripped from every URL, and
UUID-shaped and numeric path segments are replaced by placeholders wherever they appear. Two
bounds rather than one, and neither substitutes for the other: the length bound is why a line
cannot be as long as the sender wants, and the budget above is **counted in lines rather than in
requests** — one request may carry a batch — which is why 600 a minute means about 1 GB a day at the
absolute worst and not twenty times that. Together they are the difference between a known cost
and a licence to write somebody else's text onto the disk that also holds your database and your
attachments. **Rotate your container logs regardless** — the bundled `docker-compose.prod.yml`
sets `max-size`/`max-file` on the app's `json-file` driver, and a stack assembled by hand should
too. Leaving it **off is a complete answer rather than a gap** — the header
still ships, so violations still appear in your own browser's console, which for a
single-instance install is a genuinely adequate instrument and is the same thing you would read
anyway.

**Pair `CSP_REPORT_SINK_ENABLED` with `APP_BASE_URL`.** The "pages this instance does not
serve" filter compares the reported page's **host** against `APP_BASE_URL`'s, so with the sink on
and `APP_BASE_URL` left at its `http://localhost:8080` default, **every real report is dropped**
(counted as `foreign_document`) and you collect nothing while everything looks configured. The
app logs a `WARN` at startup for exactly that pair. A base URL the app cannot read a **host** from
at all is refused harder: with the sink on, **the app does not start**, because the filter is the
only thing between an unauthenticated endpoint and reports about any page on the internet from
anyone, and a value with no host would switch it off. Include the scheme
(`https://tracker.example.com`, not `tracker.example.com`) and use no underscore in the hostname.
If `SITE_ADDRESS` lists apex *and* `www`,
reports from whichever host is not `APP_BASE_URL`'s are dropped the same way and land on the
same counter as third-party noise — redirect one host to the other at your proxy if that matters.

**Pair `CSP_REPORT_SINK_ENABLED` with `RATE_LIMIT_TRUST_FORWARDED_FOR`.** The per-IP budget keys
on the client address, and behind a reverse proxy every request arrives from the proxy unless
that setting is on — so with the sink on and forwarded-for off, the per-sender bound quietly
degrades to the instance bound. Turn both on together, and only if your proxy strips
client-supplied `X-Forwarded-For` (the bundled Caddy does).

**The sink's budget is deliberately outside `RATE_LIMIT_ENABLED`**, unlike every per-principal
budget above and for a reason none of them has: it is the only bound on a door that requires no
account at all, so if the master switch reached it, "turn limiting off while I debug something"
would silently mean "let anyone on the internet write unlimited lines into this instance's log".
The off switch for that door is the door — `CSP_REPORT_SINK_ENABLED=false`. It is also
node-local, on the same terms as every in-memory budget here: N replicas allow up to N × both
ceilings.

**Two counters make the reports readable without anybody grepping**:
`hamstrack_csp_violations_total{directive}` and `hamstrack_csp_reports_dropped_total{reason}`
(`foreign_document` / `too_large` / `unparseable` / `budget`). See `docs/observability.md`.

**A gap worth knowing about rather than discovering: nothing budgets read BYTES, and read
responses are not compressed.** Every bound above is denominated in requests, in rows or in
connections; this product's byte-denominated budgets are all on the *write* side
(`WRITE_UPLOAD_BYTES_PER_MINUTE`). And there is no `encode` directive in the bundled
`Caddyfile` and no `server.compression.enabled` in the application, so a 12 MB planning
response is 12 MB on the wire — at `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` (3) one user
sustains roughly 36 MB/s of it. Self-hosted that is your own LAN; on metered egress it is a
bill rather than an outage, which is why it is recorded here and not fixed by lowering
something. If it matters to you, `encode zstd gzip` in your own edge is the lever, and it is
an edit to the `Caddyfile` — one of the artefacts a deploy does not overwrite for you.

**The workspace storage quota is the exception in the other direction, and it must stay
one.** `STORAGE_QUOTA_WORKSPACE_BYTES` is **cluster-wide and exact** — its state is a row
in PostgreSQL (`workspace_storage_usage`), maintained by a database trigger — so N replicas
do not multiply it and a redeploy does not re-arm it. That is the requirement rather than an
optimisation, and the reason is the same one the recipient-keyed mail ceilings give one
paragraph down: **a bound on a bill may not divide by replica count, and a bound that resets
on deploy is a bound the other side waits out.** It is also the one limiter here with an off
switch of its own (`STORAGE_QUOTA_ENABLED`) rather than sharing `RATE_LIMIT_ENABLED` —
removing a bound on your disk should not require disabling brute-force protection on your
login page. If you scale out, do not "fix" the asymmetry by moving it into memory.

**A node-local bound that is not a request limiter at all** is the dead-letter cap
(`MAIL_NEVER_ATTEMPTED_MAX_PER_HOUR`). It counts rows *this process* has written to
`failed_email` for mail that was never attempted, so N replicas write up to N × the
cap into one shared table — and a restart re-arms the window, which on a rolling
deploy means the very event most likely to produce those rows also resets the counter
that bounds them. Deliberately left that way: above the cap the loss is still logged
at ERROR and still reaches the caller's error line, so a split ceiling costs disk and
never visibility. Size the *table* against `cap × replicas`, not against `cap`.
(Written without a number in front of it on purpose — the ordinals above go stale one
entry before the list does.)

**The mail ceilings break the pattern, and that is the point.** They have two different
homes, and which home a control lives in follows from what it protects rather than from
which feature it arrived with. The **per-sender invitation volume budget**
(`INVITE_MAX_PER_SENDER_PER_HOUR` / `INVITE_MAX_PER_SENDER_PER_DAY`) is node-local like
everything above: N replicas allow up to N × the ceiling and a restart re-arms the
window. It protects a *spend*, so that degradation is acceptable on the same terms as
the budgets above.

The **recipient-keyed mail ceilings** are **not** node-local — they are the limiters in
this product that are **cluster-wide and exact**, because their state is a table in
PostgreSQL (`mail_send_events`) rather than a map in a process. Whatever N is, the
configured number is the number: N replicas do not multiply it and a redeploy does not
re-arm it. That is not an optimisation; it is the requirement. These protect a *person*,
not a spend, and a cooldown that a deploy or a second replica resets is a cooldown an
attacker waits out. The same reasoning rules out deriving the invitation one from the
invitations table, which three existing code paths delete rows from — one of them the
victim's own *decline* button. If you scale out, do not "fix" the asymmetry by moving any
of these into memory.

They are all one mechanism, applied per *kind* of mail: a cooldown and a volume cap on
**invitations** (`INVITE_RECIPIENT_COOLDOWN_MINUTES`, `INVITE_MAX_PER_RECIPIENT_PER_DAY`),
and a cooldown and a volume cap on the **auth mail** — password reset, resend-verification
and the verification message `POST /api/auth/register` sends —
(`AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES`, `AUTH_MAIL_MAX_PER_RECIPIENT_PER_WINDOW`). Counts
are taken per kind, so one address has a separate allowance of each and a
stranger's invitations can never suppress that person's own password reset — and for the
same reason `register` and `resend-verification` hold **separate** allowances even though
they send the same message: the one that can be spent without sending any mail must not be
able to exhaust the other.

**Some of them behave unlike every other limiter in this document, and those are the ones
most likely to be misdiagnosed: their refusal is invisible.**
`POST /api/auth/forgot-password` and `POST /api/auth/resend-verification` answer one
uniform sentence to everybody, because that uniformity is what stops them being an
account-enumeration oracle — so a throttled request there returns the usual `200` and the
usual sentence, sends nothing, and logs nothing. There is no `429` and there is no
`Retry-After`. (`POST /api/auth/register` spends a budget of its own and *does*
answer `429`: it already discloses whether an address is taken through its own `409`, so
there is nothing left for a refusal to reveal, and silently dropping a registration would
leave an account nobody — its owner included — could ever activate.) Every other 429 in
this document is a limiter telling you it fired; those two are limiters that must not.

**So how do you see one fire?** Two questions, two different answers, and the second is the
one that matters.

*Is somebody hammering an address right now?* The refusal metrics say so:
`hamstrack_ratelimit_hit_total{kind="password_reset_recipient_cooldown"}` and
`..._recipient_window`, and the `verification_*` and `registration_verification_*` pairs. Alerts `AuthMailThrottleTripping`
and `MailRecipientVolumeCapReached` watch them.

*Is somebody quietly holding one person out of their own account?* **The refusal metrics
cannot answer that, and it is the failure mode these ceilings exist to bound.** To keep a
victim's bucket full an attacker sends exactly as slots age out — spaced past the cooldown,
five per window — and **every one of those requests is allowed**, so nothing is refused and
every counter above reads zero while the victim stays locked out indefinitely. What sees it
is `hamstrack_mail_anonymous_recipient_max` (alert `MailRecipientConcentration`), a gauge of
how much anonymous auth mail went to the single busiest inbox in the last six hours. It
carries no address, because no metric here may; the address is in the database:

```sql
SELECT recipient_key, email_type, count(*)
  FROM mail_send_events
 WHERE sender_user_id IS NULL
   AND created_at > now() - interval '6 hours'
 GROUP BY 1, 2
HAVING count(*) > 20
 ORDER BY 3 DESC;
```

**If you did not deploy the observability stack, you have neither of those by default** —
it is optional, so there is no Grafana, and the app's management port (9090) is not
published, so nothing scrapes the metrics either. Two things still work, and they are worth
knowing before you need them. The counters can be read straight out of the container:

```sh
docker compose exec app wget -qO- http://localhost:9090/actuator/prometheus | grep -E 'ratelimit_hit|anonymous_recipient_max'
```

`wget`, not `curl`: the app image is `eclipse-temurin:21-jre-alpine`, which ships no `curl`, so
the `curl` spelling this paragraph used to carry failed with `executable file not found` — on
precisely the deployment it was written for, and for the one refusal that has no other witness.
The compose healthcheck uses `wget` for the same reason. Confirm with
`docker compose exec app which curl wget`.

and the SQL above runs against your database with no extra machinery at all —
`mail_send_events` records **allowed** sends, so it sees the paced attacker that the
refusal counters miss. Note the asymmetry: a *cooldown* refusal writes no row and no log
line, so on an install with no metrics scrape it has no witness whatsoever. The database
query is the one to reach for.

They also cost the one thing no other limiter here costs: one extra `SELECT` and one
extra `INSERT` per message, plus a PostgreSQL advisory lock held for the rest of that
transaction and keyed on a hash of the kind of mail and the recipient address, so concurrent
sends of one kind aimed at *the same address* serialise. These are all rare writes; nothing
else contends with them.

A further mechanism is node-local, and it is the only one that is a
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

**Opting in means naming a destination for the alerts.** The stack provisions
alert rules, so it refuses to start unless `OBS_ALERT_EMAIL_TO` is set in your
`.env` (details under *Alerts* below) — alongside `GF_SECURITY_ADMIN_PASSWORD`,
which has always been required. Because the two files are layered in one command,
that refusal aborts the **whole** command — `up -d`, and equally `down`, `stop`,
`ps` and `logs` — not just the observability half. Compose checks this before it
creates, changes or stops anything, so nothing is modified and a running stack
keeps running; you just cannot drive it until the variable is back.

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
| `OBS_ALERT_EMAIL_TO` | — | where alerts are emailed (**required** when the stack runs; empty also removes the rules) |
| `GF_SMTP_ENABLED` | `true` | alert delivery on/off — `false` keeps the rules evaluating and sends nothing |
| `GF_SERVER_ROOT_URL` | `http://localhost:3000` | external URL Grafana builds links with; change only if you front it with a proxy |
| `LOKI_RETENTION_PERIOD` | `168h` | how long Loki keeps logs (7 days) |
| `PROMETHEUS_RETENTION_TIME` / `PROMETHEUS_RETENTION_SIZE` | `15d` / `2GB` | metrics retention (whichever is hit first) |

**postgres-exporter database login (least privilege).** By default the exporter
reuses your app `DB_USERNAME`/`DB_PASSWORD`, which is a read-write role — more
than it needs. Create a dedicated read-only monitoring role and point the
exporter at it via `DB_MONITOR_USER`/`DB_MONITOR_PASSWORD`:

```sql
-- run once against the hamstrack database
CREATE ROLE hamstrack_exporter LOGIN PASSWORD '<a strong password>';
GRANT pg_monitor TO hamstrack_exporter;
```

Then set the **same** password in `.env` — the two lines are one credential written twice,
and a value taken from a page anybody can read is not a credential at all
(`openssl rand -base64 24`):

```
DB_MONITOR_USER=hamstrack_exporter
DB_MONITOR_PASSWORD=<a strong password>
```

`pg_monitor` is a built-in PostgreSQL role granting read-only access to the
statistics views the exporter reads — no access to your table data. Leave
`DB_MONITOR_*` unset to fall back to the app credentials.

**Alerts** are provisioned too (AppDown, Postgres down, high 5xx rate, high
latency, disk filling, email-send failures, JVM heap pressure, stale backups…),
and they need somewhere to go: **`OBS_ALERT_EMAIL_TO` is required** whenever you
run the observability stack. `docker compose … up` refuses to start without it
and names the variable. Grafana's SMTP reuses your `MAIL_*` settings.

This is a hard requirement rather than an optional extra because an empty value
does not merely skip the email — Grafana rejects the alerting provisioning
wholesale, so the rules are not created at all, and Grafana then refuses to start
and crash-loops. The part that makes it worth a fail-fast: **`docker compose up -d`
exits 0 anyway**, so an automated deploy reports success and only `docker compose
… ps` shows the dead container. Point it at an address, or a group alias, that
someone actually reads; "disk 15% free" and "no backup for 26 hours" are weekend
facts.

**Running dashboards on a host with no SMTP?** You still have to name an address —
the contact point cannot exist without one — but you can turn delivery off:
set `OBS_ALERT_EMAIL_TO` to any address you own and add **`GF_SMTP_ENABLED=false`**
to your `.env`. The rules provision, evaluate and show their state in
Grafana → Alerting, and nothing is ever sent.

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

**Pin a version in `.env`, not in the compose file** — `APP_IMAGE_TAG=0.4` — then upgrade
by moving that value and re-running:

```bash
docker compose pull && docker compose up -d
```

The bundled `docker-compose.prod.yml` resolves the image through `${APP_IMAGE_TAG:-latest}`
precisely so that pinning is a value you own rather than an edit to a file you may later
replace: a pin written into the compose file is undone by the next `git pull` or
re-download, silently and exactly when you were being careful. If you maintain your own
compose file, `image: ghcr.io/zherikhov/hamstrack:0.4` in it is equivalent — the rule is
that the pin lives in whichever of the two files *you* own.

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

**Coming from before 0.18.0, run one query before you pull.**
[Account addresses become case-insensitive](#account-addresses-become-case-insensitive-in-0180-one-query-before-you-pull):
the upgrade **refuses to start** — atomically, changing and deleting nothing — if any
account's stored address is not already lower-case. On an instance whose `users` table only
Hamstrack has ever written, that number is zero and one `SELECT` proves it. It cannot be
fixed automatically, because folding an address in place changes which mailbox can reset
that account's password.

**The same release makes pending invitations case-insensitive too, and that one deletes
rather than refuses** — deliberately, because a withdrawn offer is recoverable in two
clicks by the person who sent it, and an account is not. On upgrade, `workspace_invites`
loses every **unaccepted** row whose address is not already lower-case, and then, where a
workspace has several unaccepted invitations for one folded address, all but the **newest**.
Accepted invitations are never touched, and nobody loses access they already have: the only
effect an invitee can see is a link that no longer works, which is fixed by inviting them
again. From then on a workspace may hold **one standing invitation per address**, and a
second attempt is refused with a `409` instead of quietly creating a duplicate. If your
instance sends a lot of invitations and you want the number in advance, count them the same
way the migration does:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT count(*) FILTER (WHERE email <> lower(email)) AS mixed_case_removed,
       (SELECT count(*) - count(DISTINCT (workspace_id, email))
          FROM workspace_invites
         WHERE accepted_at IS NULL AND email = lower(email)) AS duplicates_removed
  FROM workspace_invites
 WHERE accepted_at IS NULL;
SQL
```

**Run those two knowing the order the migrations run in, because an upgrade that stops halfway is
not a no-op.** The invitation cleanup applies and **commits** before the account check is
attempted, and the account check aborting does **not** roll it back — Flyway wraps each migration,
not the upgrade as a whole. So an upgrade halted by the duplicate-address refusal has already
deleted the invitations described above, while every later schema change in 0.18.0 never ran at
all. Nothing is lost that cannot be re-sent, but "the upgrade failed" does not mean "nothing
changed": clear the address problem, pull again, and re-invite anyone whose link stopped working.

**The same release also bounds free text, and that one is retroactive on rows you already have:**
[Free text is bounded from 0.18.0](#free-text-is-bounded-from-0180). No migration is involved and
nothing is rewritten, but a description or comment already longer than the new bound refuses
**every** save of that record — including one that changes something else — until somebody
shortens it. There is a query there too, and on an ordinary install it returns zeros.

**The same release also puts a ceiling on how much attachment storage one workspace may hold,
and it arrives switched on:**
[Attachment storage is capped per workspace from 0.18.0](#attachment-storage-is-capped-per-workspace-from-0180).
An install whose largest workspace is under the ceiling sees nothing at all; one that is already
over it has every new upload in that workspace refused with a `409` the moment the deploy
completes — and because a `409` is a clean refusal it appears in no error rate and no log. There
is a query there that answers "am I affected" before you pull, and the ceiling you get
(100 GB or 10 GB) follows `SPRING_PROFILES_ACTIVE`, not the fact that you are self-hosting.

**And the same release reserves part of your connection pool, so check one line of `.env`
before you pull if you pin `EXPENSIVE_READ_MAX_IN_FLIGHT`.**
[Expensive reads are bounded by concurrency from 0.18.0](#expensive-reads-are-bounded-by-concurrency-from-0180).
The share must stay strictly below `DB_POOL_MAX_SIZE` or **the app refuses to start** — and while
you leave the variable unset the share is derived from your pool, so an install that has never
touched `EXPENSIVE_READ_*` upgrades cleanly whatever its pool size. The one configuration to look
at is a pinned share against a small pool. **The two variables have different comparands, and a
grep that matches both will mislead you** — `grep '^EXPENSIVE_READ_MAX_IN_FLIGHT=' .env` matches
only the surface share, which is the one that must be **below `DB_POOL_MAX_SIZE`** (default `10`).
`grep '^EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL=' .env` is the other one, and it is compared
**against the surface share, never against the pool**: pinning it to `3` on a pool of `4` passes
"below `DB_POOL_MAX_SIZE`" and is still above the share of `2` derived from that pool. That case
now **narrows to `2` with a WARN** rather than refusing to boot; you are refused only if you pinned
*both* numbers in an impossible order. Comment either line out — or set it to `-1` — to get the
derived value back.

**And the same release bounds how long a request waits for a connection at all, which is the
change most likely to look like a fault to somebody who was not told:**
[Connection acquisition is bounded from 0.18.0](#connection-acquisition-is-bounded-from-0180).
A request that finds every connection in use now gives up after **3 seconds** and answers
`503 DATABASE_BUSY` instead of waiting HikariCP's unset 30-second default — on every
endpoint, authenticated or not. **Nothing in `.env` needs changing and there is no query to
run before you pull, *unless* you have raised `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` or
`MAIL_ASYNC_QUEUE_CAPACITY`**: this release adds a 3000 ms term to a sum those two are
already checked against, so an install with less than 3 s of slack **boots today and refuses
to start after the upgrade**. The check names every knob that can move it; the arithmetic and
the ceiling are in the section linked above. On a busy or under-provisioned instance a slow
period also stops being slow and starts being errors, and the request that fails is whoever
asks while something *else* is holding the connections. If you see them, the answer is
usually `DB_POOL_MAX_SIZE`.

**And the same release bounds and tunes the database container, which is the one change here
that lands differently depending on how big your host is:**
[PostgreSQL is bounded and tuned from 0.18.0](#postgresql-is-bounded-and-tuned-from-0180).
The bundled compose file now caps the `postgres` container at `512m` where it had no cap at
all, and passes `effective_cache_size=512MB` where the image's own default is `4GB`. On a
small host both are fixes. **On a host with several gigabytes of page cache the second is a
planner regression** — the planner stops believing in cache the machine really has and drifts
towards sequential scans on large tables. There is no error; things simply get slower, the
same shape as the 0.17.0 heap cut above. One line in `.env` restores it.

**Also new in 0.17.0, and this one wants a check before you pull rather than after:**
[Notifications are scoped to a workspace](#notifications-are-scoped-to-a-workspace-from-0170).
The upgrade attributes every existing notification to a workspace and deletes any it cannot
attribute; one query tells you whether that number is zero on your instance, and it is
expected to be.

Database migrations run automatically on startup (Flyway) — no manual step. See
the [Releases](https://github.com/Zherikhov/hamstrack/releases) page for notes
before upgrading across a minor version.

**Pin an exact patch** (`APP_IMAGE_TAG=0.4.3`) for maximum reproducibility — you then
upgrade deliberately by bumping the value. `0.4` instead auto-tracks the newest 0.4.x on
each `docker compose pull`.

**Downgrades are not supported.** Flyway migrations are forward-only, and
`ddl-auto=validate` does **not** object to a schema that has *more* than the entities
declare — so an older image usually starts cleanly and then fails at the first write to a
table a newer migration constrained. Going back to 0.17.0 or earlier after 0.18.0 is the
worked example: `issue_attachments.workspace_id` is `NOT NULL` with no default, and the
older image's INSERT omits it, so attachment upload fails while everything else looks
healthy. Take a backup before a minor upgrade and restore it if you need to go back.

### Applying repository configuration

Upgrading the image is `APP_IMAGE_TAG` plus `docker compose pull && up -d`, above. The
*other* half of an upgrade is the files: `docker-compose.prod.yml`, the observability
provisioning under `observability/`, and the ops scripts under `ops/`. If you deploy from a
clone, `git pull` already updates them and `docker compose up -d` applies them — that is
enough, and nothing below is required.

**One exception, and it is the only thing on this page that a `git pull` cannot do for
you.** A script or unit under `ops/` that you *installed* — copied to `/usr/local/bin` or
`/etc/systemd/system`, as the backup job and the drift check are — is run from that copy,
not from your clone. Neither `git pull` nor `docker compose up -d` nor
`apply-config.sh` touches it, on purpose: a deploy that rewrites systemd units is a blast
radius nobody asked for. So after a release that changed a file under `ops/`, re-run the
install step you originally used (`docs/ops-prod-hardening.md` §6.3 for the backup job,
*Installing the drift check* for the drift timer). If you installed the drift check, it
tells you when this is outstanding: `hamstrack_config_drift{scope="installed-ops"}` goes
to `1` and the journal names the file.

`ops/deploy/apply-config.sh` is the same step done deliberately. It is what this project's
own production box runs, it contains no AWS and no GitHub, and it is offered here because
it does four things a bare `up -d` does not: it validates the new compose files against
your real `.env` **before** replacing anything, keeps the last five copies of what it
replaced under `.config-backup/`, restarts the containers whose config is bind-mounted (a
replaced file behind a bind mount is invisible to `up -d`), and records what it applied.

```bash
cd /path/to/your/clone && git pull
sudo ops/deploy/apply-config.sh . /opt/hamstrack --dry-run   # read the diff first
sudo ops/deploy/apply-config.sh . /opt/hamstrack
```

Two things to know before you run it:

- **It targets the bundled stack unless you narrow it.** By default it validates and brings
  up `docker-compose.prod.yml` and `docker-compose.observability.yml`. If you run a compose
  file of your own, or no observability stack, say so — a file it names and your tree does
  not carry is skipped with a log line, so this is also how you avoid being blocked by a
  variable belonging to a stack you do not run:

  ```bash
  sudo COMPOSE_FILES=docker-compose.prod.yml ops/deploy/apply-config.sh . /opt/hamstrack
  ```

  **If you also installed the drift check, set the same value in `/etc/hamstrack/drift.env`.**
  That check resolves its own compose file set from `COMPOSE_FILES`, and the hourly timer
  inherits nothing from the shell you ran the line above in — so a box narrowed here and not
  there reports your own containers as orphans, every hour, and tells you a deploy would
  delete them. Commands in `docs/ops-prod-hardening.md` → *Installing the drift check*.

  It replaces exactly the paths listed in `ops/deploy/synced-paths.txt`, and directories
  there are replaced **wholesale** — a file you dropped into `observability/` on the box is
  gone afterwards. `.env` and `Caddyfile` are never touched, whatever that list says.

  **And it checks the tree you run it *from*, not only the box.** If your working copy
  carries a file named `.env`, starting with `.env.`, or *ending in* `.env` — `config.env`,
  `backup.env` — anywhere inside a directory that `synced-paths.txt` lists (`ops/` and
  `observability/` today), the script refuses the whole apply and names the file, because a
  synced directory is copied wholesale and would carry that file to the box and into five
  retained `.config-backup/` copies. `git pull` does not delete untracked files, so a config
  you once wrote next to the script it configures will fail *every* deploy from that clone
  until you move it — and being listed in `.gitignore` does not help, because the script
  reads the tree rather than git. Each such file has a `.example` beside it saying where the
  real copy belongs (`/opt/hamstrack/.loadtest.env`, `/etc/hamstrack/backup.env`); the
  `.example` itself travels normally.

- **A pinned `APP_IMAGE_TAG` is fine; a pin that MOVED needs a flag.** Pinning is what this
  page tells you to do, so re-applying configuration onto a tag that has not changed since
  the last apply just proceeds. When the tag *has* changed — you bumped the version, or you
  pinned to roll production back — the script stops before replacing anything, because
  configuration from a newer tree beside a deliberately older image is a decision rather
  than a default. Which flag you confirm with says which of those two you are doing:

  - `--adopt-pin` applies **and** re-stamps the tag. This is the upgrade case: you moved to
    `0.5` on purpose, so the flag is needed once and every later apply on that pin needs
    none.
  - `--allow-pinned` applies **this run only** and leaves the stamp alone, so the next
    unattended apply refuses again. This is the case where you have rolled back and still
    need one configuration change out; adopting there would make the rolled-back tag your
    intended version permanently.

  The first apply on a box asks for a flag too, since there is no previous tag to compare
  against — and it is the same question: adopt if that pin is the version you mean to run,
  and if you happen to be rolled back at that moment, don't.

- **The pin goes in `.env`, not in your shell.** Compose gives the process environment
  precedence over `.env`, so `APP_IMAGE_TAG=0.5 sudo -E ops/deploy/apply-config.sh …` would
  deploy `0.5` while every later `docker compose up -d` — which reads only `.env` — runs
  whatever that file says. The applier refuses that rather than deploying a tag nothing else
  can see.

Two further notes if you use it on a schedule: it takes a lock, so a hand run and an
automated one cannot overlap, and it is idempotent — re-running on an unchanged tree
replaces identical files and leaves every container alone.

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
| A report or a report CSV download on a large tenant fails | `DB_STATEMENT_TIMEOUT_MS=30000` | 30 s. **No startup WARN**: it fits inside the stop grace the platform gives the process (`APP_STOP_GRACE_SECONDS`, 30 s by default), which is what the sizing rule compares against since 0.18.0. Raise `DB_POOL_MAX_SIZE` with it anyway: a longer bound means one request holds one connection for longer |
| A member removal or another write fails | `DB_STATEMENT_TIMEOUT_MS=60000` | the caller cannot narrow a write; give it a minute. **Startup logs one sizing WARN and nothing silences it** — above the stop grace, a statement running at this bound cannot finish inside a shutdown, so a deploy kills it while it is still holding its connection. Correct to accept if this was deliberate; `APP_STOP_GRACE_SECONDS` is the knob that makes it finishable. Raise `DB_POOL_MAX_SIZE` with it either way |
| You are diagnosing and want the old behaviour | **not available on purpose** | `0` means "no bound" to PostgreSQL and is refused at startup — that is the state this release exists to remove. Use a large value like `300000` instead, and expect the sizing WARN on every boot: at ten times the stop grace it is certain, and it is the app telling you this is a diagnostic setting rather than a resting one |

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

> **Above `APP_STOP_GRACE_SECONDS` (30 s by default) the app logs a sizing WARN at every boot,
> and no setting turns it off.** The rule changed in 0.18.0 and the new anchor is one you can
> act on: a statement allowed to run for longer than the grace the platform gives the process
> *cannot finish inside a shutdown*, so a deploy kills it while it is still holding one of your
> connections. Until 0.18.0 this compared the statement bound against half of the pool's
> acquisition timeout — which warned at anything above 15 000 and certified nothing, because one
> statement is not one connection hold: a transaction of many statements holds its connection for
> all of them, and one planning read holds one for minutes at a statement bound of ten seconds.
> Raising `DB_POOL_MAX_SIZE` is still the right response to a long bound (and the WARN also points
> at `EXPENSIVE_READ_MAX_IN_FLIGHT`, which decides how much of that pool the expensive surface may
> hold); raising `APP_STOP_GRACE_SECONDS` is what makes the statement survivable across a deploy.
> Neither suppresses the line, and the WARN says so itself while firing.
>
> **Raising it is not free, and these numbers are really one setting.** Every second you add is
> a second one request can hold one of your `DB_POOL_MAX_SIZE` connections. What keeps that from
> being an arithmetic exercise is that **occupancy is now bounded directly** rather than inferred
> from a rate:
>
> > Through the expensive-read surface, no user may occupy more than
> > `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` of a replica's connections and no set of users
> > more than `EXPENSIVE_READ_MAX_IN_FLIGHT`, so the rest of the API always retains
> > `DB_POOL_MAX_SIZE − EXPENSIVE_READ_MAX_IN_FLIGHT` of them. The per-minute budgets bound
> > throughput; they do not bound occupancy and never did.
>
> That replaces the `requests-per-minute × statement-timeout-seconds ≤ pool-size × 60 × share`
> relation this section used to ask you to solve. The relation was not wrong, it was
> unsatisfiable at the defaults — one user was entitled to 180 expensive requests a minute (120
> search + 60 reports) while one replica has 600 connection-seconds a minute to spend — and a
> rate can never deliver a bound on occupancy anyway, because it spends the same unit whether a
> request takes 8 ms or 8 s. A load probe confirmed the consequence: a single user, breaking no
> rule, saturated an instance and everything else on it failed on connection acquisition.
>
> So, practically: if you raise `DB_STATEMENT_TIMEOUT_MS` near or above 30 s, raise
> `DB_POOL_MAX_SIZE` with it — and if you raise the pool in order to give the expensive surface
> more room, check `EXPENSIVE_READ_MAX_IN_FLIGHT` with it, since that is the number deciding how
> much of the pool that surface can actually reach. Lowering `REPORTS_REQUESTS_PER_MINUTE` /
> `SEARCH_REQUESTS_PER_MINUTE` / `PLANNING_REQUESTS_PER_MINUTE` is no longer the lever for pool
> safety; they bound throughput.
> **Whether the pool alone widens that surface depends on whether you pinned the share**: unset, it
> is derived from the pool at every boot (60 % of it, capped at 6), so raising the pool from 6 to 10
> widens the share from 3 to 6 by itself; pinned, the number is yours and nothing moves it.
> **What the statement bound still does not govern** is a request that assembles its response in
> Java while the transaction is open (a report CSV): it is per *statement*, so occupancy ×
> duration remains unbounded above even though occupancy is not.

### Connection acquisition is bounded from 0.18.0

**Read this if your instance is ever busy.** Until 0.18.0 a request that found every
database connection in use waited **30 seconds** for one — HikariCP's default, which
Hamstrack had never set. From 0.18.0 it waits **3 seconds** (`DB_CONNECTION_TIMEOUT_MS`)
and is then refused with **`503`**, `errorType: DATABASE_BUSY` and `Retry-After: 1`.

**This is a change you will see, and it is the intended one.** A busy or
under-provisioned instance used to degrade into *slowness*; now it degrades into
*errors* — and the request that fails is not the slow one. Whoever asks while something
else is holding the connections is refused. Two things make that a policy rather than a
symptom: a parked request holds a worker thread and a connection of its own for the whole
wait, so waiting propagates the outage, and since 0.18.0 the
[expensive-read occupancy bound](#expensive-reads-are-bounded-by-concurrency-from-0180)
already guarantees the rest of the API a reserve of the pool that reports and searches
can never take.

**Every endpoint answers it, and the two pieces of code that write it write the same
document.** One is an exception handler, which covers a request that reached a handler; the
other is a servlet filter outside the whole chain, which covers a request whose connection was
needed earlier — **that is every authenticated request**, because the access token is resolved
to a user inside the security filter chain, and it is the larger half. Until 0.18.0 that half
answered a bare `500`, which was worse than untidy: the web UI declines to retry a `503` and
deliberately *does* retry a `500`, so a starved instance was asked again by every open tab.

**What still has no status, stated as the property rather than as a list of paths.** A refusal
needs a response it can still change. So a failure on an `ASYNC` or `ERROR` dispatch, one after
the response has already begun (a streamed download, an SSE stream), and one outside a request
altogether — a scheduled job, the shutdown residue write, Flyway at startup — cannot be turned
into one, here or anywhere else in this product. Those last are why
`hikaricp_connections_timeout_total` can still read higher than the app's own counter: it counts
acquisitions that had no caller to refuse.

**If you start seeing `503 DATABASE_BUSY`, the fix is almost always
`DB_POOL_MAX_SIZE`, not this value.** They are refusals about *capacity*: the pool had
nothing to give. Raising `DB_CONNECTION_TIMEOUT_MS` buys waiting instead — the old
behaviour — and it costs a worker thread per waiting request while doing it.

| What you see | Look at | Why |
|---|---|---|
| Occasional `503 DATABASE_BUSY` at peak | `DB_POOL_MAX_SIZE`, then `POSTGRES_MEMORY_LIMIT`/`POSTGRES_WORK_MEM` with it | more connections is the capacity answer; the pool is a factor in the database's memory arithmetic, so the two move together |
| Sustained `503 DATABASE_BUSY`, one tenant or one screen | `EXPENSIVE_READ_MAX_IN_FLIGHT` and the WARN lines, which name the route | something is holding connections for a long time; the occupancy bound is what caps that share |
| You would genuinely rather wait than shed | `DB_CONNECTION_TIMEOUT_MS=6000` (say) | legitimate. Every second added is a second a refused request holds a worker, and see the ceiling below |

**Three values it refuses at startup, one of which looks harmless.** `0` is refused
because HikariCP reads it as `Integer.MAX_VALUE` — about 24.8 days, i.e. *no bound*
rather than *no wait*; anything below `250` is refused (HikariCP's own floor); and a
**blank** line is refused, since `DB_CONNECTION_TIMEOUT_MS=` is an empty value and not an
absent one. Comment the line out to get the default.

**And a fourth, which is about a *name* rather than a value.** Before the web server is
started, the app compares the bounds the pool is actually holding against the one it validated,
and refuses to start if they differ. That happens when something sets the value by a spelling
the check does not read — most plausibly `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT` (no
dashes), which Boot's relaxed binding accepts and which overrides everything else. Without this
check that configuration starts happily and then reports a bound it is not using, in the log
line below and in the ceiling arithmetic above. **Both** Hikari settings this variable drives
are checked — `connection-timeout` and `validation-timeout` — because the second one is read
back by nothing else at all, so `SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT` would otherwise
pull the two apart with no refusal, no warning and no metric to show for it. Set the value
through `DB_CONNECTION_TIMEOUT_MS` and nothing else.

The refusal arrives **before the connector opens**, deliberately: an instance that has already
begun listening is one a load balancer will route to and a rolling deploy will count as up, so a
misconfiguration would flap rather than stop.

**And one ceiling that comes from somewhere else entirely.** When the app shuts down it
drains the mail queue and then writes whatever is left to the database as one batch — and
that write has to obtain a connection first, inside the stop grace the platform gives the
process. So the boot refuses any combination where
`drain + acquisition + commit + queued rows` exceeds `APP_STOP_GRACE_SECONDS`: at the
default mail settings (drain 15 s, queue 100, grace 30 s) the largest acquisition bound
that boots is **13900 ms**. If you want a longer wait than that, raise
`APP_STOP_GRACE_SECONDS` in the same edit — the refusal names every knob that can move the
arithmetic.

**Read that ceiling from the other end before you upgrade**, because it is the one way this
release can stop an install that changes nothing: the acquisition is a *new term* in a sum
`MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` and `MAIL_ASYNC_QUEUE_CAPACITY` were already checked
against, so 3000 ms of slack that used to be spare is now spent. At the shipped mail settings
the drain's ceiling is **25 s**; `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS=28` with a queue of 100
against a 30 s grace boots today (`29 100 ≤ 30 000`) and refuses afterwards
(`32 100 > 30 000`). Raise `APP_STOP_GRACE_SECONDS` — which moves the container's
`stop_grace_period` and this bound together — or lower the drain.

**The log line is the operator's copy of the refusal**, and it carries what the caller's
never does:

```
Could not obtain a database connection within 3000 ms on POST /api/auth/login — answering
503 DATABASE_BUSY with Retry-After 1s. The pool said: HikariPool-1 - Connection is not
available, request timed out after 3005ms (total=10, active=10, idle=0, waiting=3). The
usual remedy is a larger pool (DB_POOL_MAX_SIZE) …
```

`waiting=` and `total=` are how you tell a pool that is *tight* from one that is *gone*.
If you run the [observability stack](#observability-optional), the same event is
`hamstrack_db_connection_acquisition_failed_total`, tagged with the route, and unlike the
`422` above it also raises the general `HighErrorRate` alert — correctly, since pool
exhaustion is an incident. The `route` tag is the **mapped pattern** for a refusal that
reached a handler and **`unmapped`** for one refused earlier — that is how you tell the two
halves apart on one graph, not a missing label. `hikaricp_connections_timeout_total` counts
every acquisition the pool refused, including ones with no caller to refuse (a scheduled job,
the shutdown write, Flyway), so **expect it to be the larger of the two** and read the
difference as those rather than as a broken metric.

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

**Check what you actually got.** From 0.18.0 the application says so itself, once, at
startup — ask it before you ask anything else:

```bash
docker compose logs app | grep "Memory: max heap"
# Memory: max heap 512 MB = 536870912 bytes (MaxHeapSize; derived from -XX:MaxRAMPercentage=50,
# no -Xmx); GC SerialGC; container memory limit 1024 MB; app.reports.max-rows=20000
```

That line is printed by the running JVM about itself, so unlike every other check here
it cannot be reading a different process. It names:

- **the resolved maximum in bytes**, and *which* maximum it is. `MaxHeapSize` is
  HotSpot's own figure — the one `-XX:+PrintFlagsFinal` prints below, so the two can be
  compared digit for digit. If that word instead reads `Runtime.maxMemory`, this JVM
  would not state `MaxHeapSize` and the number is *usable* heap, which some collectors
  report a little below the configured maximum (~18 MB below it at `1g`);
- **whether that maximum came from an explicit `-Xmx` or was derived from a percentage**
  — which is what settles the `JAVA_TOOL_OPTIONS` trap above, since
  `Picked up JAVA_TOOL_OPTIONS: …` says a variable was read and not that it was applied;
- **the garbage collector.** At `APP_MEMORY_LIMIT=1g` the JVM is below its "server-class
  machine" threshold and picks **SerialGC** — single-threaded, stop-the-world — and at
  `2g`, same image and same flags, it picks **G1** (measured 2026-09-01 on
  `eclipse-temurin:21-jre-alpine`, the tag the published image is built from, with 2 CPUs).
  That is the difference between a 50 ms pause and a multi-second one, and the
  likeliest explanation of the 4.99 s pause the 2026-08-31 load run measured on a `1g`
  container. If pauses rather than `OutOfMemoryError` are the symptom, `APP_MEMORY_LIMIT`
  is the dial that moves the collector;
- **the container limit the JVM can see** — `none` means no limit, so the percentage is
  being taken against *host* RAM; `unknown` means there is no cgroup memory file to read;
- **`REPORTS_MAX_ROWS`**, because that budget is costed in bytes against exactly this heap.

The container's limit and the heap from the outside:

```bash
docker stats --no-stream --format '{{.Name}}  {{.MemUsage}}'
docker compose exec app java -XX:MaxRAMPercentage=50.0 -XX:+PrintFlagsFinal -version | grep -w MaxHeapSize
```

The first prints `used / limit` per container. The second prints the heap in bytes
(`536870912` is 512 MB). **Repeat the flag exactly as shown**: `exec` starts a *fresh*
JVM that does not inherit the image's startup arguments, so without it you would be
reading the JVM's default (~25% of the limit, i.e. `268435456` — a plausible-looking
number for a process that is not your application) rather than yours. That mistake has
been made against this deployment in earnest, which is the other reason the startup line
above exists. If you set `JAVA_TOOL_OPTIONS`, that fresh JVM picks it up the same way the
app does, so the number stays honest.

**Do this even if you are sure**, and the blunt form of the check is

```bash
docker inspect "$(docker compose ps -q app)" --format '{{.HostConfig.Memory}}'   # 0 means NO LIMIT
```

Resolve the container instead of naming it: it is called `<compose-project>-app-1`, after
the directory your compose file sits in, so a hard-coded name works only on the install it
was written on.

Run it because **a value in `.env` is not a limit until a container reports one**. The
hosted Hamstrack instance ran for six weeks with `APP_MEMORY_LIMIT=1g` sitting in its
`.env` and read by nothing — its copy of the compose file predated the `mem_limit` line —
so the JVM took its 50% against *host* RAM and ran a heap ceiling larger than the memory
the machine could ever hand it, which the kernel would have ended with an OOM kill (exit
`137`, no stack trace) long before any heap alert noticed. You can reach the same state by
editing `.env` and not recreating the container, or by running an older copy of the compose
file. The command above is the only thing that answers it; a file cannot.

**Running your own compose file rather than the bundled one?** Then nothing has
capped your container and the percentage is taken against host RAM — which at 50% is
*more* heap than before, and closer to the host's ceiling than is safe. Add a
`mem_limit:` to the app service; the [Quick start](#quick-start) file shows one.

### PostgreSQL is bounded and tuned from 0.18.0

**Read this if you use the bundled `docker-compose.prod.yml` and have never edited the
`POSTGRES_*` lines in `.env`.** Two defaults change for you, and only one of them can hurt.

Until 0.18.0 the `postgres` service carried **no `mem_limit`** and ran at the image's own
memory settings. From 0.18.0 the bundled file caps the container and passes three dials
explicitly:

| Setting | Before (image default) | From 0.18.0 | `.env` variable |
|---|---|---|---|
| container ceiling | none | `512m` | `POSTGRES_MEMORY_LIMIT` |
| `effective_cache_size` | `4GB` | `512MB` | `POSTGRES_EFFECTIVE_CACHE_SIZE` |
| `shared_buffers` | `128MB` | `128MB` — unchanged | `POSTGRES_SHARED_BUFFERS` |
| `work_mem` | `4MB` | `4MB` — unchanged | `POSTGRES_WORK_MEM` |

**The defaults are sized for a 1–2 GB host, and `effective_cache_size` is the one that can
hurt you without failing.** It is not an allocation — it is what the planner *believes* is cached, so
nothing ever refuses it and nothing ever runs out because of it. On a small box the image's
`4GB` was a claim that more data was cached than the machine had RAM, and correcting it is
the fix this change exists for. On an **8 GB or 32 GB host with a multi-gigabyte page
cache, `512MB` is an under-claim**: the planner stops believing in cache it really has and
shifts towards sequential scans on large tables. There is no error and nothing fails — the
only symptom is that things get slower, which is exactly the shape of the 0.17.0 heap cut
above.

The rows are disjoint — read the one your host falls in, not the first one that could match:

| Your host | Do this |
|---|---|
| under 1 GB (a small VPS) | **lower all three**: `POSTGRES_SHARED_BUFFERS=64MB`, `POSTGRES_EFFECTIVE_CACHE_SIZE=192MB` (that is `64MB` + what `free -m` really shows as cache — check yours rather than copying `192MB`), `POSTGRES_WORK_MEM=2MB`. Bring `POSTGRES_MEMORY_LIMIT` down with them if you like, but never under what the server is then configured to use |
| 1–2 GB | nothing — the new defaults are the fix, and this is the host they were measured on |
| 4 GB, app + database on one box | `POSTGRES_EFFECTIVE_CACHE_SIZE=1GB` |
| 8 GB, app + database on one box | `POSTGRES_EFFECTIVE_CACHE_SIZE=2GB`, and `POSTGRES_SHARED_BUFFERS=256MB` with `POSTGRES_MEMORY_LIMIT=1g` if the database is the busy part |
| dedicated database host | `shared_buffers` ~25% of RAM, `effective_cache_size` ~75%, and `POSTGRES_MEMORY_LIMIT` above the sum |

**Raising `POSTGRES_WORK_MEM` on any of the roomy rows wants `POSTGRES_SHM_SIZE` raised with
it.** Parallel workers put their share of a sort in `/dev/shm`, which Docker sizes at 64 MB
for every container; exhausting it fails with `could not resize shared memory segment … No
space left on device`, which names neither dial. The default is Docker's own 64 MB, so it
only becomes a setting once `work_mem` grows.

Then `docker compose up -d`. A value PostgreSQL cannot parse makes the **server** refuse to
start while `docker compose up -d` still exits `0`, so change one dial at a time and check
`docker compose ps`.

**The container ceiling is the other half, and it is containment rather than protection.**
`512m` is ~2× the peak RSS measured on this project's own production box (~240 MB) at these
settings. What it buys is that a runaway database dies and restarts inside its own cgroup
instead of the kernel picking a victim across the whole host — which, before this release,
could as easily have been the application, the only bounded process in the file. What it
does **not** buy is a host that cannot run out of memory: ceilings are maxima, not
reservations, and the bundled defaults still declare more of them than a 2 GB box has RAM.

**Raise `POSTGRES_MEMORY_LIMIT` whenever you raise any dial that is a term in what it has to
contain — `POSTGRES_SHARED_BUFFERS`, `POSTGRES_WORK_MEM` or `DB_POOL_MAX_SIZE`.** The first
is obvious; the last is the one that catches people. `work_mem` is charged per sort or hash
node **per backend**, so both it and the pool size are factors in the same worst case:
`4MB × ~4 nodes × ~12 backends` (a pool of 10, plus the `postgres-exporter` and a `psql`
session) ≈ **190 MB** at the defaults, and `DB_POOL_MAX_SIZE=50` makes it `4MB × 4 × 52` ≈
**830 MB** — well under a stock `max_connections` of 100, and well over a `512m` ceiling,
where the failure is an OOM-killed backend or postmaster rather than a refused connection.
Doubling `POSTGRES_WORK_MEM` doubles the same figure without touching the pool at all.

**Running your own compose file?** None of this reaches you: both the ceiling and the dials
live in `docker-compose.prod.yml`, so your database keeps the image's `4GB`
`effective_cache_size` and no container limit. The [Quick start](#quick-start) file shows
the form to copy, spelled with the same variables so `.env` can still drive it.

### Account addresses become case-insensitive in 0.18.0 (one query, before you pull)

**Most instances have nothing to do here, and one query proves it in ten seconds.** Run
it *before* upgrading and you can never meet the block described below.

**What changes.** `users.email` gains a second uniqueness rule — `UNIQUE (lower(email))` —
so `Ivan@x.com` and `ivan@x.com` can no longer both be accounts. Until now that was true
only *by convention*: every place in Hamstrack that creates an account lower-cases the
address first, and nothing but that habit enforced it. From 0.18.0 PostgreSQL enforces it,
which is what makes it survive an LDAP/SSO import, a bulk load or a support script that
forgets.

**What does not change.** Nothing about how you log in. Sign-in still matches your address
exactly, deliberately — a login that folded could resolve one typed address to either of
two rows, and that is a door, not a convenience. Existing addresses are not rewritten,
re-cased or merged by the upgrade.

**Run this before you pull:**

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT id, email, status, created_at
  FROM users
 WHERE email <> lower(email)
 ORDER BY created_at;
SQL
```

**Empty is the expected result, and it is the only one that needs no action.** Every
account Hamstrack itself creates is lower-cased at signup, so rows appear here only if some
*other* writer touched the table — an import, a support script, a dump edited by hand. If
the query is empty, upgrade normally and skip the rest of this section.

**If it returns rows, the upgrade will refuse to start.** It refuses *atomically*: nothing
is applied, no index is created, and no account row is changed or deleted. **Flyway records
nothing either** — on PostgreSQL the schema-history row is written inside the same
transaction and rolls back with it — so there is no failed migration to `repair` and no
half-state to clean up: fix the data and start the container again. The message names both
counts and repeats the queries it needs you to run. Ask which rows collide as well:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT lower(email)                          AS folded,
       count(*)                              AS copies,
       array_agg(id    ORDER BY created_at)  AS ids,
       array_agg(email ORDER BY created_at)  AS addresses
  FROM users
 GROUP BY 1
HAVING count(*) > 1;
SQL
```

**Fix them in this order. The order is load-bearing, not a preference.**

1. **Resolve every collision first.** Only you can decide which of two accounts survives,
   and the answer is *re-address or disable* — never delete: `issues.reporter_id`,
   `comments.author_id`, `invited_by` and the `created_by` columns all reference `users`
   with no `ON DELETE`, so a delete either fails on a foreign key or would destroy that
   person's history. The block under
   [Duplicate accounts after an upgrade](#duplicate-accounts-after-an-upgrade-locale-dependent-email-folding)
   retires one row of a pair and hands its address to the survivor. **Its mechanics apply
   here; one line of its reasoning does not** — it says the survivor must take *the
   duplicate's* address because that is the spelling the current build just wrote, which is
   true after the locale-folding incident it was written for and false here. Nothing was
   just written: both spellings are old, and the duplicate's may be the wrong one. Decide
   which of the two addresses the pair should end up on, then run the block with that one.
2. **Then fold whatever is left:**
   `UPDATE users SET email = lower(email) WHERE email <> lower(email);`

Doing 2 before 1 is not a shortcut. At that moment the new index does not exist yet, so a
blind fold across a colliding pair **succeeds** in producing two identical addresses and is
only then refused by the older byte-exact constraint — an error that reads like a different
bug entirely. Note also that **step 1's own output is mixed-case in two places** — the
tombstone it leaves (`Ivan@x.com.retired-<id>`) *and* the address it hands the survivor,
which is the one that matters, because that is a live account rather than a disabled row.
Step 2 is what clears both. Do not spot-check the tombstone and call it done: **re-run the
first query** afterwards and expect nothing back.

**Why the upgrade will not do step 2 for you**, given that it is one statement it could
obviously run. `Bob@x.com` and `bob@x.com` are two different mailboxes on any RFC-compliant
mail server, so folding an address in place changes **which mailbox can reset that
account's password**. That is your decision about your people, and a migration may not make
it silently.

**And why it refuses over a single row that collides with nothing.** That row is already
broken: its owner cannot log in (sign-in lower-cases what they type before looking it up)
and cannot receive a reset mail (so does that flow). From 0.18.0 it additionally *occupies*
the lower-cased address, so registering the correct spelling would be refused with "Email
is already registered" — for an address nobody holds, in a way no one would ever connect
back to this row. The upgrade is the one moment anybody looks.

**A non-ASCII address is a different question and blocks nothing.** The upgrade prints a
notice if it finds one, because it *may* be a legitimate internationalised address or *may*
be the locale-folding bug described next — and no query can tell those apart, because the
stored value is a perfectly legal lower-case address either way. The notice is a pointer to
the next section, not a verdict on your data.

#### If the database's collation provider ever changes

This applies to both address indexes at once — `users_email_lower_uk` and
`workspace_invites_pending_email_uk` — and it is one procedure, not two. After a C-library
or ICU upgrade under a running cluster, PostgreSQL says:

```
WARNING: index "users_email_lower_uk" depends on collation "default" version "2.28",
         but the current version is "2.36"
DETAIL:  The index may be corrupted due to changes in sort order.
HINT:    REINDEX to avoid the risk of corruption.
```

```sql
REINDEX INDEX users_email_lower_uk;
REINDEX INDEX workspace_invites_pending_email_uk;
ALTER DATABASE hamstrack REFRESH COLLATION VERSION;
```

What actually breaks is narrower than the warning sounds, and the precise version is worth
having because the vague one causes panic:

- **Equality does not change.** Under a deterministic collation — every collation this
  schema uses — equality is byte equality, so a provider change can never make two stored
  addresses newly equal or newly distinct. No existing account's uniqueness lapses. (That
  is about the *stored values*. What `lower()` maps them to is a separate question, and
  the third bullet is where it is answered.)
- **Sort order can change**, and a btree finds its duplicate candidates by order, so a
  stale index could fail to notice a *new* duplicate until it is rebuilt. That is what the
  `REINDEX` is for.
- **`lower()` itself can change** — it reads `LC_CTYPE` — and this is the part that would
  matter and does not: the characters whose folding varies between providers are
  *uppercase* ones, and Hamstrack has already lower-cased every address it stores. On the
  values in your table, `lower()` is the identity function under every provider in
  practical use. **And if that ever stopped being true** — a provider that knows a case
  mapping the app's JVM does not — the failure is the loud one in the next bullet rather than a silent
  one: every check the application makes goes through the *same* `lower()` the index does,
  so a disagreement can only produce a refusal you can see, never a duplicate account.
- **`REINDEX` is the detector, and it fails loudly.** If a provider change ever did fold
  two stored addresses together — a change in `lower()`'s *image*, which is a different
  question from the collation *equality* of the first bullet — the rebuild fails with
  `could not create unique index … Key (lower(email))=(…) already exists` — and run in
  `psql` you see the `DETAIL` naming the value. (The application's connection pool
  suppresses that detail so third-party addresses stay out of its log; your session is not
  the application's.)
- The bundled compose file pins `postgres:16-alpine`, so the C library changes only when
  *you* move that tag. This is an upgrade-time event with a known moment, not drift — which
  is why it lives here and not in a monitor.

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

> **Arriving here from the 0.18.0 upgrade instead?** Then that reasoning does not
> hold, because nothing was just written: both spellings are old, and the duplicate's
> may be the *wrong* one of the two. The mechanics of the block are unchanged — decide
> which address the pair should end up on first, and use it wherever the block says
> "the duplicate's address".

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

If the pair was your seed administrator, **reset that account's password** afterwards:
`SEED_ADMIN_PASSWORD` was set on a live administrator account that nobody asked to create.
Change it on the *account* (sign in and change it, or Admin console → Users) — editing the
variable alone changes nothing, because seeding skips a user that already exists.
Both rows in that pair are usually administrators, which is why "keep the one with
history" is the rule rather than "keep the active one".

### Free text is bounded from 0.18.0

**Read this if your instance holds long descriptions or comments** — a pasted stack trace, a
migrated wiki page, a specification somebody kept in an issue. On an ordinary install nothing
changes and there is nothing to do: the bound is roughly four pages of text.

Before 0.18.0 the free-text fields on the API were unbounded; the only ceiling anywhere was the
database column. From 0.18.0 each one is bounded, and an over-long value answers `400` naming the
field that was too long:

| Field | Where you meet it | Bound |
|---|---|---|
| Issue description | issue create and update | 10 000 characters |
| Comment body | issue comments | 10 000 characters |
| Project description | project create and update, project settings | 10 000 characters |
| Workflow description | Admin console → Workflows | 10 000 characters |
| A custom field of type *text area* | issue fields | 10 000 characters |
| A field definition's `config` | Admin console → Fields | 20 000 characters serialized (`422`) |

**The bounds apply to rows you already have, and that is the part worth reading.** They are checked
on the way in, so a record whose stored text is already longer than the bound refuses **every**
save until it is shortened — including a save that changes something else entirely, because the
editor submits the whole record. In practice: a 15 000-character description means that issue's
status, assignee and due date cannot be changed through the UI either, and the message names
`description` even though nobody touched it.

**Nothing is lost and nothing is rewritten.** There is no migration behind this. No row is
truncated, no text is deleted, no column is narrowed. Every existing value stays exactly as it is
and is read back in full — on the board, in the issue view, through the API, in a database dump.
The only thing that changed is that a *write* carrying an over-long value is refused.

**It is self-healing.** Shorten the text once, below the bound, and that record saves normally from
then on. If the content is worth keeping — a log, a dump, a long specification — attach it as a
file and leave a line in the description instead.

**Size it before you upgrade.** Nothing here is destructive, so this is for planning rather than
safety:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT 'issues' AS t, count(*) FROM issues WHERE length(description) > 10000
UNION ALL SELECT 'issue_comments', count(*) FROM issue_comments WHERE length(body) > 10000
UNION ALL SELECT 'projects', count(*) FROM projects WHERE length(description) > 10000
UNION ALL SELECT 'workflows', count(*) FROM workflows WHERE length(description) > 10000;
SQL
```

**Treat that number as a floor, not as an answer.** PostgreSQL's `length()` counts characters; the
server counts UTF-16 code units, and anything outside the Basic Multilingual Plane — emoji, the
rarer CJK ranges — costs two units and one character. An emoji-heavy 8 000-character description is
around 14 000 units: it will be refused, and the query above counts it as fine. Ordinary Latin,
Cyrillic or Greek prose is one unit per character, so for most instances the count is exact. If you
want a result where **zero really means zero**, run the same query with `octet_length(...) > 10000`
in place of `length(...) > 10000` — a UTF-16 unit is never more than a UTF-8 byte, so nothing can
hide under it. That one errs the other way (Cyrillic costs 2 bytes per character, CJK 3), so a
non-zero answer from it is a list to re-check with the first query, not a list of problems.

Text-area custom field values and field-definition `config` are deliberately not in the query:
they are stored inside JSONB documents rather than in a prose column. Both behave the same way —
refused on save, unchanged in storage, fixed by shortening once.

### Attachment storage is capped per workspace from 0.18.0

Before 0.18.0 nothing bounded how much attachment storage one workspace could occupy:
`ATTACHMENT_MAX_FILE_SIZE` refused one large file and nothing refused the ten-thousandth
small one. From 0.18.0 there is a per-workspace ceiling and **it arrives switched on**
(`STORAGE_QUOTA_ENABLED` defaults to `true`), so it applies at the container restart your
upgrade performs, to an install whose `.env` names neither variable.

**The break-even is one number.** A workspace holding **less** than the ceiling sees no
change of any kind — no refusal, nothing slower, nothing hidden. A workspace already holding
**more** has every new upload in it answered `409 STORAGE_QUOTA_EXCEEDED` from the moment the
deploy completes, with no warning to anyone and no entry in any error rate, because a `409` is
a clean refusal rather than a fault. Existing files stay readable and downloadable and
nothing is deleted, archived or expired; only new uploads are refused.

The ceiling is **100 GB self-hosted, 10 GB on the `cloud` profile**, and *which one you get
follows `SPRING_PROFILES_ACTIVE`*. `.env.prod.example` ships `cloud`, so an install that
never changed that line is on the **10 GB** ceiling while this page quotes 100 GB — the most
likely way to be surprised by this change is to be on the wrong profile rather than to be a
large install.

**Am I affected? One query, before you pull.** It works on any 0.17.x instance (the counter
table does not exist yet, so it counts the rows directly):

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT i.workspace_id,
       pg_size_pretty(SUM(a.size_bytes)) AS attachments
  FROM issue_attachments a
  JOIN issues i ON i.id = a.issue_id
 GROUP BY i.workspace_id
 ORDER BY SUM(a.size_bytes) DESC
 LIMIT 20;
SQL
```

Zero rows, or a largest workspace comfortably under your ceiling, means this change is
invisible to you. Otherwise pick one of two values and put it in `.env` **before** the pull:

| Situation | Line to add |
|---|---|
| Largest workspace is over the ceiling and you want the cap anyway | `STORAGE_QUOTA_WORKSPACE_BYTES=` a value above it (e.g. `500GB`) |
| You are self-hosting and `.env` still says `SPRING_PROFILES_ACTIVE=cloud` | `SPRING_PROFILES_ACTIVE=dc` — and read [the deployment-model note](#configuration): the profile also decides public signup and where attachments are stored |
| You do not want a ceiling at all | `STORAGE_QUOTA_ENABLED=false` |

**Whatever you type there is checked at boot, and a value that cannot work stops the container
rather than the first upload.** `STORAGE_QUOTA_WORKSPACE_BYTES` must be at least
`ATTACHMENT_MAX_FILE_SIZE` — a ceiling smaller than one permitted file admits nothing at all —
and the check runs **even when `STORAGE_QUOTA_ENABLED=false`**, because a number that is only
validated while a switch is on is a number that is wrong the moment somebody turns the switch on.
A **blank** `STORAGE_QUOTA_WORKSPACE_BYTES=` stops the boot too rather than restoring the default:
remove the line to get the default back. The startup message names both numbers and the variable
to change.

`STORAGE_QUOTA_ENABLED=false` stops the refusals and keeps the bookkeeping: usage is still
counted and still shown on **Workspace settings → Storage**, which is the figure you need in
order to choose a number later. Once you know the distribution, lower the ceiling deliberately
as its own change — the procedure is
[Turning the storage quota on where there is already content](#turning-the-storage-quota-on-where-there-is-already-content).

After the upgrade the same question is one primary-key read per workspace:

```sql
SELECT workspace_id, bytes_used, attachment_count, updated_at
  FROM workspace_storage_usage
 ORDER BY bytes_used DESC
 LIMIT 20;
```

### Expensive reads are bounded by CONCURRENCY from 0.18.0

Before 0.18.0 two per-minute budgets bounded how *often* one user could ask for a report or a
search, and **nothing bounded how many they could have running**. That is not a small gap: a
rate spends the same unit whether a request takes 8 ms or 8 s, so its protection evaporates
exactly as an instance slows down. At the shipped defaults one user was entitled to 180
expensive requests a minute while one replica has 600 connection-seconds a minute to spend —
and a load probe confirmed the consequence, which is worse than a slow report: **one user,
breaking no rule, saturated the instance, and everything else on it failed on connection
acquisition after 30 s**, including endpoints with nothing to do with reports.

From 0.18.0 there is an occupancy bound, and **it arrives switched on**
(`EXPENSIVE_READ_LIMIT_ENABLED` defaults to `true`), so it applies at the container restart
your upgrade performs, to an install whose `.env` names none of these variables:

> Through the expensive-read surface — every read that holds a connection while it works, today
> `…/reports/**`, `…/search/**`, `…/filters/**`, `…/storage/projects` and the **planning** reads
> under `…/projects/*/backlog/**` — no user may occupy more than
> `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` (3) of a replica's connections and no set of
> users more than `EXPENSIVE_READ_MAX_IN_FLIGHT` (6), so the rest of the API always retains
> `DB_POOL_MAX_SIZE − EXPENSIVE_READ_MAX_IN_FLIGHT` of them.

**Those two numbers are derived from your pool while you leave them unset, and that is what makes
this upgrade safe on a small box.** 3 and 6 are what the derivation produces against the default
`DB_POOL_MAX_SIZE` of 10; on a pool of 6 it produces 3 and 3, on a pool of 4, 2 and 2 — 60 % of the
pool, capped at the shipped 6, with the per-user ceiling clamped to fit. **The share is never
derived larger than 6**, so a big pool keeps the documented numbers and the only installs whose
behaviour the derivation changes are the ones that would otherwise have refused to start. Set
either variable and the number is yours exactly, checked against the pool as described below. The
boot log names the numbers in force and says whether they were derived.

**What you may see that you did not see before: a `429` where yesterday there was a slow
`200`.** A request over the share waits up to `EXPENSIVE_READ_ACQUIRE_WAIT_MS` (1 s) for a slot
and is then refused with `Retry-After: 1` and one of two `errorType`s — `TOO_MANY_IN_FLIGHT`
(the caller's own requests are occupying their share) or `EXPENSIVE_SURFACE_BUSY` (the
instance's share is full). Nothing is computed and nothing is wrong with the request; the
identical retry a moment later succeeds. That is the trade, stated plainly: under sustained
overload some legitimate reports and searches are refused **in milliseconds** instead of
everything on the instance failing **after 30 s**.

**The planning reads join this bound in the same release, and there the refusal is newer still.**
`GET …/projects/{projectId}/backlog` and its per-section refreshes under `…/backlog/**` had **no
budget of any kind** before 0.18.0 — the largest single response this product produces was
unbudgeted, which was not a decision anybody made. From 0.18.0 they carry two: a per-principal
`PLANNING_REQUESTS_PER_MINUTE` (240 a minute, in memory per app node, under `RATE_LIMIT_ENABLED`)
and a share of the same occupancy bound as reports and search, with **no dial of its own**. Two
consequences to know before somebody meets them. **The Backlog page can now answer `429` where it
previously always answered `200`** — with `Retry-After`, never a narrowed section, a smaller cap or
a truncated view, and the identical retry succeeds. And because the share is *one* share, **a team
grooming a backlog can be the reason a colleague's report is refused, and the reverse.** That is
the intended trade rather than a defect to chase: the alternative was that colleague waiting out a
30 s connection timeout behind a planning read legitimately holding its connection for minutes.
The one thing a client owes here is not to answer a refusal by asking for something bigger — a
refused section refresh must not be retried as the whole view, which assembles every open section
in one transaction.

**Who is likely to notice.** A small box under real load, and anyone driving the reports, search or
planning API with more requests in flight at once than their per-user ceiling. The web UI's widest
parallel burst on this surface is the search results page's three mount queries — so **while the
per-user ceiling is 3, i.e. on a pool of 5 or more**, the acquire wait absorbs those rather than
refusing them. On a smaller pool the derived ceiling is 2 (pool 4) or 1 (pools 1–3) and that page
can meet it: the mount queries then serialise inside the one-second wait, and only past that does
one of them answer `429` and retry.

**If the numbers are wrong for your instance**, they are three `.env` lines — but they are not
independent of your pool, and the app checks the relation rather than trusting it:

- An explicit `EXPENSIVE_READ_MAX_IN_FLIGHT` must be **strictly less than `DB_POOL_MAX_SIZE`**, or
  the app **refuses to start**. At or above it the surface could hold every connection and the
  reservation this feature exists to make would not exist. **A derived share satisfies this by
  construction** — refusing to boot is the right answer to a number you typed and the wrong one to
  a number nobody chose.
- An explicit `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` must be
  **≤ `EXPENSIVE_READ_MAX_IN_FLIGHT`** — above it the per-user ceiling can never fire and callers
  would get the wrong refusal for their situation. **What happens then depends on who chose the
  other number.** If you pinned *both*, the app refuses to start naming both: you stated a relation
  and the relation cannot work. If you pinned only this one and let the share be derived from your
  pool — a pool of 4 derives 2, so the `3` this file shows you is already above it — the app
  **narrows your number to the derived share and logs one WARN** naming both numbers and the pool.
  A bound that exists so one surface cannot take an instance down must not take the instance down
  over a pair only half of which anybody chose.
- **An explicit share above 60 % of the pool** logs one sizing WARN naming the connections left. It
  is legitimate on a large pool and nothing silences it. A derived share is taken at exactly that
  fraction and never warns about itself.
- **Neither number bounds how long one request may hold its slot**, and there is no variable for
  that. A slot is taken before the request body is read and given back after the response is
  written, so a client that trickles bytes would otherwise hold one for the price of a socket.
  **The layer that makes that hold finite is the watchdog**: it force-releases a slot held past
  `DB_STATEMENT_TIMEOUT_MS` + 60 s and counts it in
  `hamstrack_expensive_read_permit_force_released_total`. Two further layers raise the price of the
  attempt rather than ending it, and it is worth knowing which does which. Inside the application
  the gap between two reads of a request body is pinned at 20 seconds — Tomcat's default is *not*
  "no timeout"; it lets the body inherit the connector's connection timeout (60 s, and whatever you
  set `server.tomcat.connection-timeout` to), so this tightens that gap and makes it independent of
  a dial meant for idle keep-alive connections. It ships in the app, so it applies behind any proxy,
  including your own. At the edge, a `read_body` timeout in the bundled `Caddyfile` is an absolute
  deadline on reading a whole request. **That last one does not arrive with an upgrade** — like `.env`, the
  `Caddyfile` is never replaced by [config apply](#applying-repository-configuration), so if you
  run the bundled edge and your copy predates 0.18.0, copy the `timeouts` block from the repository
  by hand and reload Caddy.

So the order for giving reports more room is **raise `DB_POOL_MAX_SIZE` first, then the share**
— and if you raise the pool, re-read `POSTGRES_MEMORY_LIMIT` and `POSTGRES_WORK_MEM` with it,
since the pool is the `backends` term in that arithmetic.

**Turning it off is one variable and it is not `RATE_LIMIT_ENABLED`.**
`EXPENSIVE_READ_LIMIT_ENABLED=false` removes the bound; `RATE_LIMIT_ENABLED=false` does **not**,
deliberately — removing a bound on your connection pool should not require disabling
brute-force protection on your login page. Turning it off restores exactly the behaviour above,
so if you do it, watch `hamstrack_expensive_read_in_flight` and Hikari's `pending`.

**Is the share ever full?** `hamstrack_expensive_read_in_flight` is the gauge, per replica —
alert with `max()`, never `sum()`. The rule `ExpensiveReadSurfaceSaturated` fires on a sustained
rate of `EXPENSIVE_SURFACE_BUSY` refusals, which means the instance is under-provisioned for its
traffic rather than that anything is broken.

## Backups

Two things to back up: the **PostgreSQL database** and **attachments**. They
reference each other, so capture them together and restore to a consistent point.

Durability on a self-hosted instance is whatever your backups make it — nothing in
the product provides it for you, and the statement about Hamstrack Cloud is about
the operator's instance, not yours.

> **Restore it once, or you do not have a backup.** A dump nobody has read back is
> a belief about a file. The commands below take about fifteen minutes to run
> against a throwaway container, and they are the only thing that turns the belief
> into a fact — see [Verify a restore](#verify-a-restore).

### By hand

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
volume (files under `/app/data/attachments`); with `s3`, see
[Attachments on S3](#attachments-on-s3-turn-versioning-on) below. Take a backup
**before every minor upgrade**.

### On a schedule

The repository ships a ready-made job under [`ops/backup/`](../ops/backup/) — the
same one the hosted instance is set up with. It is a **host `systemd` timer**, not
a compose service: it costs nothing until it fires, it takes the dump with the `pg_dump`
that is already inside your postgres container (so the client can never be a
different version from the server), and it needs no new image.

Per run it produces two objects — a `pg_dump -Fc` custom-format dump and a
`pg_dumpall --globals-only` file. The second one is a few kilobytes and it is what
makes a restore come up *complete*: a fresh database recreates your `hamstrack`
login role from compose, but not a read-only `pg_monitor` exporter role or any
role you added by hand.

Install (as root, from a checkout of the tag you are running):

```bash
install -m 0750 -o root -g root ops/backup/hamstrack-backup.sh /usr/local/bin/hamstrack-backup
install -m 0644 ops/backup/hamstrack-backup.service /etc/systemd/system/
install -m 0644 ops/backup/hamstrack-backup.timer   /etc/systemd/system/
mkdir -p /etc/hamstrack /var/backups/hamstrack /var/lib/hamstrack-backup
# node-exporter's textfile directory, where the job publishes its freshness metrics. Create
# it even if you do not run the observability stack: the script always writes there, and the
# unit lists it in ReadWritePaths=, which systemd refuses to set up for a path that does not
# exist. If you DO run the stack, create it before node-exporter starts (or Docker creates
# it for you at mount time).
mkdir -p /var/lib/node_exporter/textfile_collector
install -m 0640 -o root -g root ops/backup/backup.env.example /etc/hamstrack/backup.env
$EDITOR /etc/hamstrack/backup.env      # every variable, its default and its meaning
bash -n /etc/hamstrack/backup.env      # silence = bash can parse it; see below

# Arm the alarm before the first run. A zero sentinel means "installed, never succeeded",
# so if the first real run never happens, BackupStale fires instead of staying silent
# forever — the rules use noDataState: OK, and an absent series is silence, not an alert.
echo 0 > /var/lib/hamstrack-backup/last_success_dump
echo 0 > /var/lib/hamstrack-backup/last_success_upload
```

**Uploading to S3 needs AWS CLI v2 ≥ 2.19** (`aws --version`). Backups are written with
`If-None-Match: *` so a key can never be overwritten — an older CLI does not know the
option and the run fails after the dump. Amazon Linux 2023 ships a new enough one; Debian
and Ubuntu ship no `aws` at all, and their `awscli` package is v1, which will not do.
`BACKUP_TARGET=local` needs none of this.

Run it once by hand before you trust the schedule, then enable the timer — and start it
**through systemd** rather than as a bare command, so that everything the unit adds is
exercised too:

```bash
systemctl daemon-reload
systemctl start hamstrack-backup.service
journalctl -u hamstrack-backup -n 50 --no-pager
systemctl enable --now hamstrack-backup.timer
systemctl list-timers hamstrack-backup.timer
```

**Two things nothing else will tell you.** `/etc/hamstrack/backup.env` is hand-edited and
read by two parsers that disagree. systemd's `EnvironmentFile=` accepts lines bash cannot —
a stray paren, one quote too few — and the script sources the same file, so the `bash -n`
above is what catches that kind before 03:15 does. A file bash cannot parse does not silently produce a backup with default settings: the
run logs a `WARN`, keeps going far enough to publish a failure metric, and then refuses. The
same refusal covers a line that parses and then *fails* — `BACKUP_S3_PREFIX=$(some-tool)`
with no such tool on the box — which `bash -n` cannot see, because it parses without running;
the script runs the file in a child shell before sourcing it, precisely so that case ends in a
refusal rather than in a backup written to the default prefix and reported as a success.
And if you ever need to abort a run: `systemctl stop hamstrack-backup.service` reaches the
whole cgroup and finishes in seconds, while a bare hand run you send `TERM` keeps going
until the dump it is streaming completes (bash runs a trap only once the current foreground
command returns — 300 s, measured). To stop one of those now, signal its child as well:
`pkill -TERM -P <pid>`.

It fires daily at 03:15 UTC with up to 10 minutes of jitter, and `Persistent=true`
means a box that was down at 03:15 runs the job at the next boot instead of
skipping the day.

**Where it uploads to.** The full variable reference is in
[`ops/backup/backup.env.example`](../ops/backup/backup.env.example); three settings
decide the destination.

| You have | Set | Credentials |
|---|---|---|
| AWS S3, on EC2 | `BACKUP_TARGET=s3`, `BACKUP_S3_BUCKET=…`, `BACKUP_S3_REGION=…` | none to set — the instance role arrives over IMDS |
| AWS S3, not on EC2 | the same | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in `/etc/hamstrack/backup.env` (it is `0640 root:root` and both systemd and the script export it), or `~/.aws/credentials`. Also set `AWS_EC2_METADATA_DISABLED=true` |
| MinIO, Backblaze B2, Wasabi, or any S3-compatible store | the same, plus `BACKUP_S3_ENDPOINT=https://…` (passed straight through as `--endpoint-url`; **`https` only** — an `http` endpoint would upload your whole database in the clear, so the run refuses it), a `BACKUP_S3_REGION` your provider expects (B2 `us-west-004`, MinIO usually `us-east-1` — a wrong one is an opaque signature error), and `BACKUP_S3_PATH_STYLE_ACCESS=true` if your store does not do virtual-host URLs (most MinIO deployments) | the same as the row above |
| no object store at all | `BACKUP_TARGET=local` | none |

`BACKUP_TARGET=local` dumps, verifies and rotates on a local disk
(`BACKUP_LOCAL_DIR`, kept for `BACKUP_KEEP_LOCAL_DAYS` days) and skips the upload
stage entirely. It is a real backup of a bad migration or a dropped table, and it
is **not** a backup of the machine: a copy on the same disk dies with the disk.
Point `BACKUP_LOCAL_DIR` at a separate volume, or copy it off yourself.

**Do not make the script delete old backups on the remote side, and it does not.**
Retention belongs to the storage — an S3 lifecycle rule, your provider's
equivalent, or `BACKUP_KEEP_LOCAL_DAYS` for a local target. A script that can
delete one backup can be made to delete all of them, which is the second move of
anyone who gets into the box. For the same reason, if you use AWS, give the
instance role `s3:PutObject` on the backup bucket and **not** `s3:GetObject` or
`s3:DeleteObject`.

**Every run writes `hamstrack_backup.prom`** into node-exporter's textfile directory —
always, which is why the install block above creates that directory even when you do not
run the observability stack. **If you also run the stack**, two provisioned alert rules —
`BackupStale` (a backup stage has not succeeded in 26 hours, critical) and
`BackupRunFailed` (the last run failed at a stage, warning) — start working with no extra
configuration beyond that directory and one run of the job, which is what makes the series
exist. Both rules are **per stage**, which is what
makes them work in `local` mode too: there is no `upload` stage there, and the `dump` stage
is then what shows you a timer that has stopped. They stay silent if you never install the
timer, because the metric does not exist. See
[Observability](observability.md) for the wiring and the metric names.

### Verify a restore

Do this when you install the mechanism, before every upgrade, and on a calendar
reminder afterwards. It touches nothing you are running: it is a throwaway
container on a spare port.

```bash
# 1. A scratch PostgreSQL of the SAME major version as yours, on a free port.
#    Bound to 127.0.0.1 and given a throwaway password on purpose: in a moment this
#    container holds a copy of your production database — every user's email and password
#    hash, and the SCRAM verifiers from the globals file. A bare `-p 15433:5432` listens on
#    every interface, so on a cafe or office network that is a reachable full-database read
#    by anyone who knows the password, and a password printed in this file is known.
docker run -d --name hamstrack-restore-check \
  -e POSTGRES_USER=hamstrack -e POSTGRES_PASSWORD=drill-only-password -e POSTGRES_DB=hamstrack \
  -p 127.0.0.1:15433:5432 postgres:16-alpine

# 2. Restore. --exit-on-error is the point: a restore that reports errors and keeps
#    going is how a half-restored database gets mistaken for a good one.
docker cp hamstrack-<TS>.dump hamstrack-restore-check:/tmp/d.dump
docker exec hamstrack-restore-check \
  pg_restore -U hamstrack -d hamstrack --no-owner --no-privileges --exit-on-error -v /tmp/d.dump

# 3. Roles. "role hamstrack already exists" is expected and benign — but look at what runs
#    after that notice and does NOT fail: `pg_dumpall --globals-only` emits
#    `ALTER ROLE hamstrack ... PASSWORD 'SCRAM-SHA-256$...'`, and that statement SUCCEEDS.
#    Restoring the globals therefore replaces this container's throwaway password with your
#    PRODUCTION one.
docker cp hamstrack-<TS>.globals.sql hamstrack-restore-check:/tmp/g.sql
docker exec hamstrack-restore-check psql -U hamstrack -d hamstrack -f /tmp/g.sql

# 3b. So put the throwaway password back. DO NOT DELETE THIS LINE AS REDUNDANT — the reason
#     it looks redundant is the reason it is needed. `docker exec` uses the local socket,
#     which the image trusts, so step 4 passes either way; what breaks is step 5, the one
#     that tells "restored" apart from "restored and VALID", because the application
#     connects over TCP and authenticates. And the obvious way out of that failure is to
#     type your production database password into a shell on a machine that is already
#     holding a full copy of production.
docker exec hamstrack-restore-check psql -U hamstrack -d postgres \
  -c "ALTER ROLE hamstrack PASSWORD 'drill-only-password';"

# 4. Content, and the migration history that has to survive with it.
docker exec hamstrack-restore-check psql -U hamstrack -d hamstrack \
  -c "select count(*) from users" \
  -c "select count(*) from workspaces" \
  -c "select count(*) from projects" \
  -c "select count(*) from issues" \
  -c "select count(*), max(version), bool_and(success) from flyway_schema_history"

# 5. Point a Hamstrack container at it and let it start. This is the bar: the app
#    runs Hibernate's ddl-auto=validate, so a schema that restores but does not
#    match the image is a restore that has not actually worked.
docker run --rm --network host \
  -e DB_URL=jdbc:postgresql://localhost:15433/hamstrack \
  -e DB_USERNAME=hamstrack -e DB_PASSWORD=drill-only-password \
  -e JWT_SECRET=drill-only-secret-0123456789abcdef0123456789abcdef \
  -e SERVER_PORT=8081 \
  ghcr.io/<owner>/hamstrack:<the tag you are running>
curl -s http://localhost:8081/api/meta

# 6. Tear down. The dump holds every user's email and password hash — do not leave
#    it on a laptop.
docker rm -f hamstrack-restore-check
```

A run passes only if all five hold: `pg_restore` exited 0, the counts match what
production had at dump time, `flyway_schema_history` is present with
`bool_and(success)` true, the application **started**, and `/api/meta` answered.
Anything else is a failed drill and something to fix now rather than during an
incident. Restoring an old dump into a *newer* image is fine and expected — Flyway
applies the missing migrations on boot; restoring into an **older** image is the
schema-validation failure in [Troubleshooting](#troubleshooting).

Write down how long step 1 to step 5 took. That number is your recovery time, and
it is the only version of it that is not a guess.

### Attachments on S3: turn versioning on

With `STORAGE_TYPE=s3` the application holds `s3:DeleteObject` on your attachments
bucket, because deleting an attachment is something the product legitimately does.
A bug or a compromised instance can therefore erase attachments irreversibly, and
a database dump does not help — it holds the attachment *rows*, whose storage keys
would point at nothing.

Enable versioning and expire the noncurrent versions, so a delete becomes a
tombstone you can undo for a month, and you pay only for what was deleted or
overwritten:

```bash
aws s3api put-bucket-versioning --bucket "$ATTACH_BUCKET" \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-lifecycle-configuration --bucket "$ATTACH_BUCKET" \
  --lifecycle-configuration '{"Rules":[
    {"ID":"expire-noncurrent-attachment-versions","Status":"Enabled","Filter":{"Prefix":""},
     "NoncurrentVersionExpiration":{"NoncurrentDays":30},
     "AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}'
```

Most S3-compatible stores expose the same two settings under different names.

## Troubleshooting

| Symptom | Likely cause & fix |
|---|---|
| App exits at startup with a JWT/key error | `JWT_SECRET` is missing or shorter than 32 bytes — HMAC-SHA256 requires ≥32. Generate one: `openssl rand -base64 48`. |
| App exits at startup saying `JWT_SECRET` is "a value published in Hamstrack's own documentation" | The secret is one of the placeholders this repository has shipped (they pass the length check, which is why they are refused by name). It is not newly unsafe — the upgrade only started saying so. Replace it with `openssl rand -base64 48`. That rejects every access token signed with the old key immediately but does **not** sign anyone out — clients re-issue from their refresh cookie — so if you think the published value was used against you, [there are two more steps](#what-rotating-jwt_secret-does-and-what-it-does-not). See [An unedited template is refused, by design](#an-unedited-template-is-refused-by-design). |
| App exits at startup saying `SEED_ADMIN_PASSWORD` is "the value this project published" | Your `.env` carries the password the template used to ship, so the administrator account it seeded — named in the message — is signable-into by anyone who can read this repository. **Clearing the variable does not fix it**: seeding is idempotent, the account already exists and keeps that password. Reset or delete that user first, then set your own value or none — [step by step](#if-your-instance-has-the-published-admin-account). |
| App exits at startup saying a **system administrator HAS** the published password | Read from the database, not from `.env` — so it fires even when your configuration mentions no seeding at all, and it keeps firing after you change `SEED_ADMIN_PASSWORD`, because seeding never re-passwords an existing user. The account named in the message is signable-into by anyone who can read this repository. The app is down, so start from SQL: `UPDATE users SET password_hash = NULL WHERE email = '<the address named>';` — then boot and set a new password via **Forgot password** or another administrator. [Step by step](#if-your-instance-has-the-published-admin-account). |
| App exits at startup saying `SEED_ADMIN_PASSWORD` is *N* bytes and the maximum is 72 | The value is over **BCrypt's** 72-UTF-8-byte ceiling, which is the cap on every password the application stores — not a rule this project chose, and not one you can raise. Bytes are not characters: accented, Greek and Cyrillic letters cost 2 each, most other scripts 3, emoji 4, so a 40-character Cyrillic passphrase is 80 bytes. Shorten it (`openssl rand -base64 48` is 64 ASCII characters and stronger than BCrypt can use), or clear `SEED_ADMIN_EMAIL` if you did not mean to seed an administrator. It only fires where the value would actually **create** the account, so it cannot be triggered by an install whose administrator already exists. |
| Registration never completes / no email arrives | **Two causes, and the second produces an identical symptom with no error anywhere.** (1) SMTP misconfigured — check `MAIL_*` and your provider; test locally with MailHog (`http://localhost:8025`). (2) The per-recipient ceiling on verification mail refused it. That one is only silent on `POST /api/auth/resend-verification`; `POST /api/auth/register` answers `429` with `Retry-After`, so check which endpoint the user actually reached. Note the ceiling counts the destination **inbox**, not the address — several accounts at one inbox (`a+1@`, `a+2@`, `a.1@googlemail.com`) share one allowance. Diagnose with the queries under [Optional toggles](#optional-toggles), not by changing `MAIL_*`. |
| Password-reset links stop arriving for **one person**, everybody else is fine, and nothing is logged | The per-recipient ceiling on reset mail refused them, silently — `POST /api/auth/forgot-password` answers `200` either way by design, so the user is told a link was sent and no error exists anywhere in your stack. Often this is somebody *deliberately* holding that address's allowance full; an attacker who paces themselves is never refused, so the refusal metrics read zero too. The one thing that sees it is `mail_send_events` — run the six-hour concentration query under [Optional toggles](#optional-toggles). **Do not lower `AUTH_MAIL_MAX_PER_RECIPIENT_PER_WINDOW`**: it would shorten the victim's own allowance as well. Block the source at your proxy. |
| Logged out immediately / can't stay signed in | `APP_BASE_URL` scheme doesn't match how users reach the app. The `refresh_token` cookie is `Secure` only with an `https` base — serve HTTPS end-to-end (https base) or use an `http` base for plain HTTP. |
| `502` right after `up` | The app is still starting (Spring Boot needs ~30–40 s; it has a healthcheck). Wait, or check `docker compose logs app`. |
| Attachment upload returns `500` | `STORAGE_TYPE=s3` without a valid bucket/region/credentials, or the local dir isn't writable. |
| Upload rejected (`413` / too large) | Over `ATTACHMENT_MAX_FILE_SIZE` (app limit) or `ATTACHMENT_MAX_UPLOAD_SIZE` (servlet ceiling); raise both, and the proxy body-size limit to match. |
| Upload rejected (`415` / type not allowed) | The file extension isn't in `ATTACHMENT_ALLOWED_EXTENSIONS` — add it (comma-separated, case-insensitive). |
| A report, search or report CSV download that worked now returns `422` (`STATEMENT_BUDGET_EXCEEDED`) | One database statement ran past `DB_STATEMENT_TIMEOUT_MS` (default 10 s, **new in 0.17.0**) and PostgreSQL cancelled it. An identical retry fails identically. Narrow the request (shorter date range, fewer sprints, tighter filter) or raise the value — [Statements are bounded from 0.17.0](#statements-are-bounded-from-0170) has a size-to-value table. A **write** that does this (removing a member with a lot of assigned work) cannot be narrowed, so raise it. On a host of 4 GB or more also check the [heap](#the-heap-is-bounded-from-0170): 0.17.0 cut that too, and less heap makes the same query slower, so one symptom here can have either cause — or both. |
| An edit or save that used to work now returns `409` "Someone else is changing this right now" | **New in 0.17.0, and unlike the `422` above it announces nothing** — the status, the message and the `Retry-After` header all existed before, so there is no new string to search for. `DB_LOCK_TIMEOUT_MS` (3 s) now bounds *every* transaction rather than only the few that lock deliberately, so a write queued behind a long-running change gives up instead of waiting indefinitely. **It is retryable and the header says when**, so a client should retry rather than surface it. If legitimate edits collide often enough to be noticed, raise `DB_LOCK_TIMEOUT_MS` — but it may not exceed half `DB_STATEMENT_TIMEOUT_MS` (default `10000`, so `5000` is today's maximum) and the app refuses to start above that, so raise both together. The usual cause is removing a member with a lot of assigned work. |
| Requests start answering `503` (`DATABASE_BUSY`) during a busy period — or `500` with nothing in the log naming a cause | **New in 0.18.0.** A request waited `DB_CONNECTION_TIMEOUT_MS` (3 s) for a database connection and there was none — until this release it waited 30 s instead, so the same load looked like slowness. **Both halves of the traffic get the `503`** — a request refused inside a handler and one refused earlier in the security filter chain, which is where an authenticated request's token is resolved. The `500` in the left column is what an install **before 0.18.0** shows for the second half, and it is the same incident; if you see a `500` storm with `hikaricp_connections_timeout_total` climbing on this version, look for a failure with no response left to change (a streamed download, an async dispatch) rather than for a second cause. The refusal carries `Retry-After: 1`, and the transaction that failed applied nothing — whether the *request* is safe to repeat is a property of the endpoint, not of this status. **Read the WARN line, which names the route and quotes the pool** (`total=`, `active=`, `waiting=`): that is what separates a pool that is tight from a database that is gone. The usual fix is `DB_POOL_MAX_SIZE` — raise `POSTGRES_MEMORY_LIMIT` or lower `POSTGRES_WORK_MEM` with it — and if one screen or one tenant is holding connections for a long time, `EXPENSIVE_READ_MAX_IN_FLIGHT` is the ceiling on that. Raising `DB_CONNECTION_TIMEOUT_MS` buys waiting rather than capacity: see [Connection acquisition is bounded from 0.18.0](#connection-acquisition-is-bounded-from-0180) |
| Startup fails naming `spring.datasource.hikari.connection-timeout` and `DB_CONNECTION_TIMEOUT_MS` | The acquisition bound is `0`, blank, not a number, or below 250. **`0` does not mean "no timeout"** — HikariCP maps it to about 24.8 days, i.e. no bound at all, which is the state the setting exists to remove. `DB_CONNECTION_TIMEOUT_MS=` (blank) is an empty value rather than an absent one; comment the line out to get the default of `3000` |
| Startup fails saying the mail shutdown does not fit inside the stop grace | The drain, one connection acquisition, the commit and the queued rows together exceed `APP_STOP_GRACE_SECONDS`, so a deploy would SIGKILL the process part-way through writing the mail it could not send. The message names every knob that can move the arithmetic; the common cause is raising `DB_CONNECTION_TIMEOUT_MS` above **13900** at the default mail settings. Raise `APP_STOP_GRACE_SECONDS` in the same edit, or shorten `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` |
| Everyone shares one IP / false `429`s | Behind a proxy/CDN that doesn't pass `X-Forwarded-For` (or passes an untrusted one). Ensure the proxy sets it; the app trusts the right-most entry. |
| Startup fails naming `app.persistence.statement-timeout-ms` and `app.locking.lock-timeout-ms` | The statement bound is under **2x** the lock bound. PostgreSQL counts lock-wait time inside the statement, so the smaller bound always fires first, and a statement bound at or under the lock bound would make the lock bound dead configuration — every retryable `409` in the product would quietly become a `422` no retry can fix. The message prints both values, the computed minimum and both knobs. **It can fire from the side you did not touch:** raising `DB_LOCK_TIMEOUT_MS` above 5000 while `DB_STATEMENT_TIMEOUT_MS` is at its default 10000 stops the boot. |
| Startup fails naming `app.invites.event-retention-days` and `app.invites.recipient-cooldown-minutes` | Those are the **property** names behind `INVITE_EVENT_RETENTION_DAYS` and `INVITE_RECIPIENT_COOLDOWN_MINUTES` — the message quotes properties, your `.env` sets variables, so a search for the variable name finds nothing. The retention must be strictly **longer than the widest ceiling window**, which is the larger of the cooldown and the widest width any per-recipient volume cap is *permitted* to count over — **24 h**, fixed in code, not settable by any variable, and compared against as a bound rather than as a list, so that adding a new kind of throttled mail cannot quietly invalidate the check. Raise `INVITE_EVENT_RETENTION_DAYS` (minimum **2**) or lower `INVITE_RECIPIENT_COOLDOWN_MINUTES`. (It is not the only cutoff on that table: anonymous auth-mail rows have a second, fixed one of **2 days**, because the long window is bought to answer *who* and those rows have no sender to name. Their real lifetime is `min(2 days, INVITE_EVENT_RETENTION_DAYS)` — the general sweep carries no sender predicate and reaches them too, it is simply usually slower; at the minimum of `2` the two coincide.) Refusing the boot is deliberate: a retention that undercuts a ceiling silently shortens it to itself, with no error and no log line — the throttle simply stops refusing sends it meant to refuse. |
| A database error no longer names the offending row — a failed migration says *which* index it could not create but not *which* rows collided, and a syntax error reports no source position | Expected, not a fault: `DB_LOG_SERVER_ERROR_DETAIL` is `false` by default, which strips PostgreSQL's `DETAIL` / `HINT` / `POSITION` / `WHERE` lines from every error on the application datasource — **and Flyway runs on that same datasource**, so upgrade failures lose their detail along with everything else. It is off because the driver folds `DETAIL` into the message *before* the app can redact it, and on some errors that message carries a user's email address or other row values. **To get one session's worth back:** put `DB_LOG_SERVER_ERROR_DETAIL=true` in `.env`, `docker compose up -d`, reproduce the failure, read the log — then **remove the line and `up -d` again**, because leaving it on writes row values into every log sink you have. Editing `DB_URL` to add `?logServerErrorDetail=true` works on the driver but is undone by the next upgrade, which replaces `docker-compose.prod.yml`.  **Before you re-enable anything, try `docker compose logs postgres`** — this setting is a *client-side render flag*: it changes only what the JDBC driver concatenates into the application's log line. PostgreSQL still writes the same error, `DETAIL` intact, to its own log, so the failure that **already happened** is readable there with no restart, no reproduction, and nothing new written into the app's stream. That is usually the answer, and for a migration that failed mid-upgrade it is strictly better than re-running a failed upgrade with row values switched on. It also means this setting **reduces** the exposure rather than eliminating it on a stack that ships container logs: the database's copy travels the same route. What it does remove is the copy in the application's own stream — the one that reaches support tickets and screenshots — and on an install with no database-log shipper it is the only copy at all. **One failure is exempt and it is the one you are most likely to meet on the 0.18.0 upgrade:** the `users` address pre-flight writes its counts, its queries and its remedy into the *primary* message, which this setting never strips — re-enabling it there tells you nothing new. See the row below. |
| The upgrade to **0.18.0** stops the boot with a message beginning `HD-167 V23 aborted` | Deliberate — this is the one migration in the product that refuses on purpose, and it is refusing over *your data*, not over a fault. Your `users` table holds at least one address that is not already lower-case, and 0.18.0 adds `UNIQUE (lower(email))`. **Nothing was applied:** no index, no account changed or deleted, and **no schema-history row written**, so there is nothing to `flyway repair` — fix the data and start again. Unlike every other failed migration, **this one carries its own remedy in the primary message** (both counts, the queries that find the rows, and the statements in the one order that works), so the row above does not apply to it: turning `DB_LOG_SERVER_ERROR_DETAIL` on adds nothing here. Full procedure, including why a row that collides with nothing still blocks: [Account addresses become case-insensitive in 0.18.0](#account-addresses-become-case-insensitive-in-0180-one-query-before-you-pull). |
| Startup fails with a schema validation error after changing the image | You moved to an **older** image than the DB was migrated to. Use the newer image, or restore a pre-upgrade backup. |

## REST API

The HTTP API for a self-hosted instance is documented in
[api-dc.md](api-dc.md), and interactively at `/docs` (Swagger UI) on your
instance. The OpenAPI spec is served at `/openapi.yaml`.
