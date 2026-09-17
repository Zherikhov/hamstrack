# A runnable self-hosted stack in the tree — `deploy/dc/`, the dev helper renamed, and the install command a stranger can paste (HD-314)

**Status:** proposal / design review. **Date:** 2026-09-15. **Author:** systems-analyst.
**Release:** 0.18.3 (install readiness). **Epic:** HD-312 — *a stranger can install Hamstrack from this repository alone*.
**Closes:** **HD-314**.
**Hands a decided answer to:** HD-313 (which image line a template names), HD-315 (which profile a DC
template selects), HD-320 (whether SMTP is required to install), HD-324 (what the seal needs from this
change). **Must not contradict:** HD-316 (architecture), HD-317 (the Cloud install path), HD-319
(README structure), HD-321 (`java -jar`), HD-323 (repository first impression).
**Related artefacts:** `docker-compose.yml`, `docker-compose.prod.yml`, `docs/self-hosting.md`,
`README.md`, `src/test/java/com/hamstrack/ops/PublishedCredentials.java`,
`src/test/java/com/hamstrack/common/security/JwtSecretValidationTest.java`,
`src/main/resources/application.properties`, `ADR-0013` (config delivered from the repository).

**Backlog searched before writing** (`.local/hd_dump.mjs`, 327 issues, 2026-09-15): titles matching
`compose|install|self-host|quick-start|README|.env|stack|docker`. **No duplicate.** Related and open:
HD-313, HD-315, HD-316, HD-317, HD-318, HD-319, HD-320, HD-321, HD-322, HD-323, HD-324 (all children of
HD-312), HD-249, HD-250. Related and closed: HD-199, HD-221, HD-287.

---

## 0. How to read this document, and the one thing the builder does first

Every statement about existing behaviour carries a label:

- **measured** — a command was run in this session and its output is quoted (Appendix B);
- **read** — `file:line`, quoted or paraphrased, not executed;
- **inferred** — a hypothesis. Every one of them has a numbered probe in §0.1 and a stated consequence.

**§0.1 is the builder's first deliverable.** Nothing below may be implemented before the two probes are
run and reported, because each one decides the content of a section rather than a line.

### 0.1 Probes — run these first, paste the output

| # | Probe | What it settles | If it refutes |
|---|---|---|---|
| **P1** | ~~Rename `docker-compose.yml` → `docker-compose.dev.yml` locally and run `mvnw.cmd test -Dtest=JwtSecretValidationTest`. Expect **red**, then **green** after §4.3.2.~~ **RUN AND REFUTED IN BOTH DIRECTIONS — see the correction note below.** | — | — |

> **P1 CORRECTION (measured, 2026-09-15, during implementation).** The probe was run. The
> rename alone left `JwtSecretValidationTest` **12/12 green**, and §4.3.2 applied alone turned
> it **red** (two assertions). Both predictions above are wrong, for two measured reasons:
>
> 1. **The dev stack publishes no literal credential.** Its only credential-shaped line is
>    `POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-hamstrack}` — an *interpolation*, which
>    `PublishedCredentials.isNotAValue` exempts in **every** classification. The `:-default`
>    unwrap that yields `hamstrack` exists only inside `localDevStackCredentials()`, never in
>    the offence scan. §1.1's claim of a literal `POSTGRES_PASSWORD: hamstrack` is **false**.
> 2. **`localDevStackCredentials()` cannot be moved by this rename.** Both exempt values come
>    from `docker-compose.observability.dev.yml` alone — `admin` (`:44`) and `hamstrack`
>    (`:133`) — and that filename still matches the `.dev.` regex. The set stays size 2.
>
> §4.3.2 alone is red because `JwtSecretValidationTest:483` and `:682` are keyed on the string
> literal `"docker-compose.yml"`. It is therefore neither necessary nor sufficient **on its
> own**; it is correct only as one change together with M3/M4/M5, which is the category rule.
>
> **The finding underneath it, and the reason M15/M16 below exist:** the rename left every
> assertion keyed on the old filename *green*, because each asked about a filename while the
> population was the tree. Sealed by `DevComposeFileReferencesTest` (measured: under a planted
> rename it fires three arms while `JwtSecretValidationTest` stays 12/12 green).
| **P2** | With Docker Desktop running: `cd deploy/dc && docker compose up -d`, then `docker compose ps`, `curl -s localhost:8080/api/meta`, and one `POST /api/auth/login` as `SEED_ADMIN_EMAIL` (report the **status code only**, never a token). | That the file in §4.1 actually brings up a working instance with no SMTP, no domain and no TLS — the whole claim of this ticket. | **RUN AND CONFIRMED, 2026-09-15** (Docker Engine 29.2.1): both services `healthy`; `GET /api/meta` → `200`, `version 0.18.2`, `publicSignupEnabled:false` (so the `dc` profile literal took effect); `POST /api/auth/login` as `SEED_ADMIN_EMAIL` → **200**, with a wrong-password control → **401**. No SMTP, no domain, no TLS were configured. **A24 is no longer inferred.** |

Two further probes belong to the builder as ordinary verification, not as spec-deciding forks: `docker
compose config -q` on every file the diff touches (§10 A2), and `git check-ignore` on the new paths
(already measured here, Appendix B.6, but cheap to repeat after the files exist).

### 0.2 Decisions at a glance

| # | Question the ticket asked | Decision | Where |
|---|---|---|---|
| **D1** | What breaks when the dev helper is renamed | `mvnw spring-boot:run` stops booting at all (**measured**), and `JwtSecretValidationTest`'s credential exemption stops applying (**measured classification**). Both are compensated in the same commit. The full reference category is §4.3.1. | §4.3 |
| **D2** | Which image tag `deploy/dc/.env.example` carries | The **current minor line**, written once as `${APP_IMAGE_TAG:-0.18}` in the compose file and echoed as a commented default in the template. Not `latest`, not an exact patch, not `0.4`. Kept current by HD-324's seal deriving the line from `git tag` (**not** from `pom.xml`, which is `0.0.0-DEV`) plus a registry check in the release checklist. | §4.2, §4.6 |
| **D3** | Whether `docs/self-hosting.md:105-205` keeps its YAML | **It loses it.** The Quick start becomes commands + a link + at most one *marked* excerpt, and the marker (`quoted-from:`) is what makes an excerpt checkable instead of a second copy. | §4.5 |
| **D4** | What the stack must contain | `app` + `postgres`, no proxy; four required values, all `${VAR:?}`-guarded; the app published on `${APP_BIND:-0.0.0.0}:${APP_PORT:-8080}`; first run = Flyway + a seeded **ACTIVE** system admin; **SMTP is not required to install**. | §4.1, §5.1 |
| **D5** | DC/Cloud position | Nothing changes for `cloud`. `SPRING_PROFILES_ACTIVE: dc` is a **literal** in the new compose file's `environment:` block, so an operator's `.env` cannot flip it — the exact inverse of `.env.prod.example:300`, and that asymmetry is the point (HD-315). One new name, `APP_PORT`/`APP_BIND`, is Compose-only and is read by no Spring property. | §9 |
| **D6** | What can be verified here | `docker compose config -q` on any candidate file, the registry facts, the rename's boot consequence, the jar's contents, and `git check-ignore` — all **measured today**. A real `up -d` and a clean-host timed install **cannot** be done in this session (the Docker daemon is down on this machine, Appendix B.1) and are P2 + HD-312's DoD. | §11.6, Appendix B |

---

## 1. Problem & goal

### 1.1 What is true today

Every premise the ticket filed is re-checked here.

| Ticket premise | Verdict | Evidence |
|---|---|---|
| Root `docker-compose.yml` declares `postgres` (host port 15432) and `mailhog` and **no `app`** | **confirmed — read** | `docker-compose.yml`, 27 lines, two services: `postgres` (`ports: "15432:5432"`, `:9-10`) and `mailhog` (`:19-24`). No `app` key anywhere in the file. |
| `README.md:39` tells the reader to run `docker compose up -d`, which in a fresh clone starts a database and a mail catcher, exits 0, and starts no application | **confirmed — read** | `README.md:39`: "*Pin a released image line (e.g. `APP_IMAGE_TAG=0.4`, not `latest`) in your `.env` and run `docker compose up -d`*". The sentence is in the **Self-hosting (DC)** section, so the file it resolves to is the repository root's development helper. The "exits 0, starts no app" half is **inferred** (the daemon is down here); it follows from the file having no `app` service. Probe if you want it: P2's `docker compose ps` run against the *old* root file. |
| The only runnable DC stack is markdown at `docs/self-hosting.md:105-205` | **confirmed — read** | The fenced YAML starts at `:104` (fence) / `:106` (`# docker-compose.yml`) and the block plus its `.env` companion runs to `:246` (`docker compose up -d`). ~90 lines of YAML in one fence, ~25 lines of `.env` in a second. |
| `docker-compose.prod.yml` is not an alternative for a stranger | **confirmed — measured** | `:61` `image: ghcr.io/${GITHUB_OWNER:?set GITHUB_OWNER in .env}/hamstrack:${APP_IMAGE_TAG:-latest}`; `:319` `SITE_ADDRESS: ${SITE_ADDRESS:?set SITE_ADDRESS in .env}`; the only published ports are Caddy's `80`, `443`, `443/udp` (`:328-330`) — the `app` service publishes nothing. Measured: `docker compose -f docker-compose.prod.yml config -q` → `error while interpolating services.app.image: required variable GITHUB_OWNER is missing a value` (Appendix B.2). |

Two facts the ticket did not state, both **measured**, both of which change the shape of the work:

1. **The rename breaks the development run.** `spring-boot-docker-compose` discovers its file by a fixed
   search order — `compose.yaml`, `compose.yml`, `docker-compose.yaml`, `docker-compose.yml`
   (`DockerComposeFile.SEARCH_ORDER`, Boot 4.1.0 sources) — and when none is present it does not skip, it
   **asserts**: `IllegalStateException: No Docker Compose file found in directory '…'`, thrown from
   `prepareContext`. Measured by booting the app with a working directory containing only
   `docker-compose.dev.yml` (Appendix B.3).
2. **The rename changes a credential classification** (it does **not** break the seal — see the
   P1 correction in §0.1: the renamed file publishes only an interpolation, so nothing goes
   red, which is itself the defect M15/M16 close). `PublishedCredentials.isDevelopmentCompose`
   (`src/test/java/com/hamstrack/ops/PublishedCredentials.java:375-381`) classifies a compose file as
   *development* — and therefore exempts its published passwords — by matching, **at the repository root
   only**, `docker-compose.yml` or `docker-compose.*.dev.yml`. Measured against the candidate names:
   `docker-compose.dev.yml` matches **neither** clause, so it would be classified as *production
   configuration* and its `POSTGRES_PASSWORD: hamstrack` would become an offence (Appendix B.5). The rule is
   also encoded as a table row in `JwtSecretValidationTest:483`. **Measured correction:** that
   classification change produces no offence, because the file's `POSTGRES_PASSWORD` is
   `${POSTGRES_PASSWORD:-hamstrack}` — an interpolation, not the literal this paragraph and
   §0.1 both assumed.

### 1.2 Goal

A person who has been handed nothing but the repository link can install the self-hosted model on a clean
host with no domain, no TLS, no SMTP and no account anywhere, by pasting five commands from `README.md`,
and can tell — from output the document names in advance — whether it worked. The stack they run exists
**once** in the tree as a real file, and the documents point at it rather than reprinting it.

---

## 2. Scope / non-goals

**In scope**

1. `deploy/dc/docker-compose.yml` — the DC stack as a real, `config -q`-clean file.
2. `deploy/dc/.env.example` — its template, every required value empty.
3. `deploy/dc/README.md` — ~15 lines, because a GitHub directory view is where a stranger lands.
4. Renaming the development helper to `docker-compose.dev.yml`, **with every compensating edit in the same
   commit** (§4.3.1 is the category).
5. `README.md` — the DC install command exact and complete; the development command corrected for the
   rename.
6. `docs/self-hosting.md` — the Quick start stops carrying a copy of the stack.
7. The three seals of §10 that make the above checkable.

**Explicitly not in scope** (each belongs to a named sibling, and this spec must not pre-empt or
contradict it)

- **HD-313** — sweeping *every* image-tag example in the tree onto the current line. This ticket decides
  what the **new** files say (D2) and adds no member that is off-line; the sweep is HD-313's.
- **HD-315** — making startup refusals name environment variables rather than Spring property names. This
  ticket owns only "which profile the DC template selects, and how an operator cannot change it by
  accident".
- **HD-316** — architecture. The new files say nothing about `amd64`/`arm64`; HD-316 owns the Requirements
  row and the `exec format error` troubleshooting entry. **Do not add an architecture sentence here** —
  two tickets writing the same sentence is HD-313's defect wearing a different hat.
- **HD-317** — the Cloud install path. `deploy/cloud/` is deliberately **not** created here; the namespace
  is left free.
- **HD-318 / HD-319** — Requirements, Troubleshooting and README's structure beyond the install command.
- **HD-320** — the SMTP section's prose. This ticket supplies the *fact* (SMTP is not needed to install,
  §5.1) and the template that does not demand it; HD-320 rewrites the section.
- **HD-321** — the container-free `java -jar` path.
- **HD-322 / HD-323** — backups portability; repository first impression.
- **HD-324** — the seal. §10 and §13.2 state what it needs from this change; building it is not this
  ticket.
- **The production stack.** `docker-compose.prod.yml`, `docker-compose.observability.yml`,
  `ops/deploy/synced-paths.txt` and the deploy pipeline are **untouched**. `deploy/dc/` is not a synced
  path and must not be added to one (ADR-0013 §6.2 — the manifest is short on purpose).

---

## 3. Actors & permissions

This change adds no endpoint, no `Permission` constant and no authorization decision. Stated explicitly so
`tenancy-reviewer` and `security-officer` have a position to check rather than a silence to interpret:

| Actor | Where they act | What the change gives them |
|---|---|---|
| **Operator** (a person with a shell on the host) | `deploy/dc/` | The stack and its template. They are outside the application's permission model entirely: their authority is the Docker socket. |
| **The seeded system administrator** | the running instance | `SystemRole.ADMIN`, created at startup by `DataSeeder` from `SEED_ADMIN_EMAIL`/`SEED_ADMIN_PASSWORD` (`DataSeeder.java:612-620`, **read**). This is the only privilege this change causes to exist, and the template must not make it weaker: every required value ships **empty** (§4.2), so an unedited copy cannot produce a working instance with a password printed in a public repository. That failure has happened once — `SEED_ADMIN_PASSWORD=SEED_ADMIN_PASSWORD` in `.env.prod.example` (HD-200) — and the guard that now refuses it is `DataSeeder.PUBLISHED_PASSWORDS` (`:90`, **read**). |
| **Developer** | repository root | The renamed helper. No privilege change. |

**Permission-model invariants this change must not disturb:** public self-registration is closed on `dc`
(`application-dc.properties:15`, `app.registration.public-signup-enabled=${PUBLIC_SIGNUP_ENABLED:false}`,
**read**), so the seeded administrator is the only door into a fresh instance and the template's
`SEED_ADMIN_*` lines are load-bearing rather than convenient.

---

## 4. Behaviour & rules — the deliverables

### 4.1 `deploy/dc/docker-compose.yml`

**Normative content.** The YAML below was validated in this session with `docker compose config -q`
(Compose **v5.1.0**) in all three states — no `.env`, an unedited (empty) `.env`, a filled `.env` —
and the refusal messages quoted in §5.2 are its real output (Appendix B.4). The builder may re-order
comments and must expand them; the **structure, the guard set, the literals and the variable names are
normative**.

> **Measured correction (implementation, 2026-09-15): the `JWT_SECRET` line below does not
> parse.** `docker compose config -q` on the block as written answers
> `yaml: line 46, column 71: mapping values are not allowed in this context`, because an
> unquoted YAML scalar ends at a colon-space and the guard message contains one
> (`… - generate with: openssl rand -base64 48`). Appendix B.4 was measured against the
> variant *without* `generate with:` — which is also the wording B.4a quotes — so the
> normative block had drifted from the thing that was validated. The shipped file uses the
> measured wording (`set JWT_SECRET in .env - openssl rand -base64 48`) and carries a comment
> saying why no `: ` may appear inside a `${VAR:?…}` message.

```yaml
services:
  app:
    image: ghcr.io/zherikhov/hamstrack:${APP_IMAGE_TAG:-0.18}
    # Everything an operator legitimately sets reaches the container through this file,
    # so adding SMTP later is a line in .env and never an edit to this YAML.
    # `required: false` so a fresh clone can still be validated (`docker compose config -q`)
    # before a .env exists; the ${VAR:?} guards below are what refuse the `up`.
    env_file:
      - path: .env
        required: false
    environment:
      # Deployment model. A LITERAL, not ${SPRING_PROFILES_ACTIVE:-dc}: a value under
      # `environment:` wins over `env_file:`, so an operator who cribbed .env from
      # .env.prod.example (which ships SPRING_PROFILES_ACTIVE=cloud) still gets `dc`.
      SPRING_PROFILES_ACTIVE: dc
      # Internal. The database host is this compose project's service name.
      DB_URL: jdbc:postgresql://postgres:5432/hamstrack
      DB_USERNAME: hamstrack
      # ONE line for both sides: the app's datasource password and the postgres
      # container's POSTGRES_PASSWORD below are the same variable, so they cannot drift.
      DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env beside this file}
      # Min 32 bytes. The app refuses to start on a shorter value, and on the placeholders
      # this project has published, by name.
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET in .env - generate with: openssl rand -base64 48}
      # The first administrator, created on startup. Self-registration is closed on `dc`,
      # so without these two nobody can log in at all.
      SEED_ADMIN_EMAIL: ${SEED_ADMIN_EMAIL:?set SEED_ADMIN_EMAIL in .env beside this file}
      SEED_ADMIN_PASSWORD: ${SEED_ADMIN_PASSWORD:?set SEED_ADMIN_PASSWORD in .env - your own, not one from these docs}
      # Where this instance is reached from a browser. Leave it http://localhost:PORT while
      # you are trying it out: with an https base the session cookie is Secure and will not
      # survive plain HTTP, so an https value requires actually serving HTTPS.
      APP_BASE_URL: ${APP_BASE_URL:-http://localhost:8080}
      # Read by the app (it refuses to start if its mail drain would not fit) and by
      # stop_grace_period below. One variable, both places.
      APP_STOP_GRACE_SECONDS: ${APP_STOP_GRACE_SECONDS:-30}
    ports:
      # APP_BIND=127.0.0.1 makes the app reachable only from this host — the right answer
      # on a public server until a TLS proxy is in front of it.
      - "${APP_BIND:-0.0.0.0}:${APP_PORT:-8080}:8080"
    # Not optional: the image sizes the heap at 50% of the CONTAINER limit, so with no
    # limit here it sizes against host RAM. 1g -> a 512 MB heap.
    mem_limit: ${APP_MEMORY_LIMIT:-1g}
    # Docker's own default is 10s between SIGTERM and SIGKILL, shorter than the 15s the app
    # spends flushing queued mail at shutdown.
    stop_grace_period: ${APP_STOP_GRACE_SECONDS:-30}s
    volumes:
      - attachments_data:/app/data/attachments
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/api/meta"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: hamstrack
      POSTGRES_USER: hamstrack
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env beside this file}
    command:
      - postgres
      - -c
      - shared_buffers=${POSTGRES_SHARED_BUFFERS:-128MB}
      - -c
      - effective_cache_size=${POSTGRES_EFFECTIVE_CACHE_SIZE:-512MB}
      - -c
      - work_mem=${POSTGRES_WORK_MEM:-4MB}
    mem_limit: ${POSTGRES_MEMORY_LIMIT:-512m}
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

**The rules behind that file, each one stated because a reviewer will ask:**

- **R1 — two services, no proxy.** A stranger with no domain cannot complete an ACME challenge, so a Caddy
  service would make the stack refuse to work for exactly the reader it is for. TLS is a *later* step and
  belongs to `docs/self-hosting.md#tls--reverse-proxy`.
- **R2 — the app is published, and the bind address is one word away from safe.** `APP_BIND` defaults to
  `0.0.0.0` because an operator on a VPS who cannot reach the app concludes the stack is broken; the
  comment and the template row tell them how to close it. Measured: the long form interpolates correctly
  and flips `host_ip` (Appendix B.4d).
- **R3 — `env_file` + `${VAR:?}` are two different jobs.** `env_file` is *delivery* (so optional values —
  SMTP, `SEED_ADMIN_DISPLAY_NAME`, storage — need no YAML edit, which is the trap
  `docs/self-hosting.md:252-258` warns about for the hand-copied stack). The four `${VAR:?}` guards are the
  *refusal*. The guard set is exactly "the values with no safe default": `DB_PASSWORD`, `JWT_SECRET`,
  `SEED_ADMIN_EMAIL`, `SEED_ADMIN_PASSWORD`.
- **R4 — `required: false` on the env file.** Measured: with a plain `env_file: .env` and no `.env`
  present, Compose fails with `env file …/.env not found` *before* interpolation, which makes
  `docker compose config -q` on a fresh clone fail for a reason that says nothing about the install
  (Appendix B.4c). With `required: false` the same command fails on the *guard*, naming a variable the
  reader can act on. §10 A2 is phrased against this behaviour.
- **R5 — literals win.** `SPRING_PROFILES_ACTIVE`, `DB_URL` and `DB_USERNAME` are literals under
  `environment:` precisely so a `.env` cribbed from `.env.prod.example` cannot redirect this stack at S3,
  a 10 GB workspace quota and open signup (HD-315's failure, `application-cloud.properties`, **read** via
  `.env.prod.example:285-300`).
- **R6 — one password, two consumers.** `DB_PASSWORD` seeds the Postgres container and the app's
  datasource. Two literals can be edited apart; one variable cannot.
- **R7 — no `container_name:` anywhere.** The development helper names its containers (and CLAUDE.md's
  `docker start hamstrack-postgres` depends on that); this stack must not, or two Hamstrack projects on one
  host collide.
- **R8 — the image reference is lowercase and literal.** `ghcr.io/zherikhov/hamstrack` — measured against
  the registry (Appendix B.7). No `${GITHUB_OWNER}`: that variable is a production-deploy concern and is
  exactly what makes `docker-compose.prod.yml` unusable for a stranger.

### 4.2 `deploy/dc/.env.example`

**Normative content** (comments abbreviated; the builder writes them out in the register of
`.env.prod.example`, i.e. each value says what it is for and what happens if it is wrong):

```
# Copy this file to `.env` in this directory, fill the four values below, then:
#   docker compose up -d && docker compose ps
# Nothing here has a working default on purpose: a filled-in sample in a public repository
# is a working credential. Each ${VAR:?} guard in docker-compose.yml fires on ABSENCE, so
# `up` refuses and names the one it wants; fill it, run again, and the next names itself.

# ── Required ───────────────────────────────────────────────────────
# Any strong password you choose. Used twice from this one line: it seeds the PostgreSQL
# container and is what the app logs in with, so the two cannot drift apart.
#   openssl rand -base64 24
DB_PASSWORD=
# Token signing key, minimum 32 bytes. The app refuses to start on a shorter value and on
# the placeholder values this project has published, by name — so generate your own:
#   openssl rand -base64 48
JWT_SECRET=
# Your own address. This becomes the first system administrator; self-registration is
# closed on a self-hosted install, so this is the only way in.
SEED_ADMIN_EMAIL=
# A password you choose — not one from any documentation, and not this variable's own name.
# Max 72 UTF-8 BYTES (a BCrypt limit, not ours): 72 ASCII characters, ~36 Cyrillic.
SEED_ADMIN_PASSWORD=

# ── Optional: where this instance is reached ───────────────────────
# Leave APP_BASE_URL on http://localhost:PORT until a TLS proxy is in front: with an https
# base the session cookie is Secure and will not survive plain HTTP.
#APP_BASE_URL=http://localhost:8080
# Host port. Change it if 8080 is taken.
#APP_PORT=8080
# Bind address for that port. 0.0.0.0 (the default) publishes it on every interface; on a
# public server with no TLS proxy yet, set 127.0.0.1 and reach it over an SSH tunnel.
#APP_BIND=0.0.0.0

# ── Optional: which build runs ─────────────────────────────────────
# The release LINE, so patch releases arrive on `docker compose pull`. Never `latest`:
# it moves on every main build. An exact version (0.18.2) pins harder and never moves.
#APP_IMAGE_TAG=0.18

# ── Optional: email (SMTP) ─────────────────────────────────────────
# NOT REQUIRED TO INSTALL. With no SMTP the instance starts and you sign in as the seeded
# administrator; you add further users in /admin, which hands you a one-time setup link to
# pass on yourself and sends no mail. Configure SMTP when you want verification, invite and
# password-reset mail to be delivered.
#MAIL_HOST=
#MAIL_PORT=587
#MAIL_USERNAME=
#MAIL_PASSWORD=
#MAIL_SMTP_AUTH=true
#MAIL_STARTTLS=true
#MAIL_FROM=

# ── Optional: host sizing ──────────────────────────────────────────
#APP_MEMORY_LIMIT=1g
#POSTGRES_MEMORY_LIMIT=512m
#POSTGRES_SHARED_BUFFERS=128MB
#POSTGRES_EFFECTIVE_CACHE_SIZE=512MB
#POSTGRES_WORK_MEM=4MB

# Do NOT set SPRING_PROFILES_ACTIVE here. This stack is the self-hosted (dc) model and
# docker-compose.yml fixes the profile as a literal, which wins over this file.
```

**Rules:**

- **R9 — every required value ships empty.** Not a placeholder. A placeholder does not *fail*, it
  *agrees* (HD-200). Mechanically enforced already: `PublishedCredentials.isEnvTemplate` matches any file
  whose name ends `.env.example` (`:432-435`, **read**), so `deploy/dc/.env.example` joins the
  `JwtSecretValidationTest` population on creation with no registration step. **Measured** corollary:
  `.gitattributes` pins `*.env.example text eol=lf`, so the new template is CRLF-proof for free.
- **R10 — optional values are commented out, not empty.** An empty `MAIL_HOST=` in `.env` is delivered to
  the container as an empty string and *overrides* the application's own default
  (`spring.mail.host=${MAIL_HOST:localhost}`, `application.properties:194`, **read**). A commented line
  delivers nothing and lets the default stand. This distinction is why the SMTP block is `#`-prefixed and
  the required block is not.
- **R11 — the tag line appears here as a *comment*, and as a live default only in the compose file.** One
  live member for HD-324's seal to check, one commented echo whose drift is checkable by the same rule.
- **R12 — the operator's `.env` is already git-ignored.** **Measured**: `git check-ignore -v deploy/dc/.env`
  → `.gitignore:62:.env`, while `deploy/dc/.env.example` and `deploy/dc/docker-compose.yml` are **not**
  ignored (Appendix B.6). No `.gitignore` edit is needed, and adding one would be a change with no effect
  to review.

### 4.3 The rename — `docker-compose.yml` → `docker-compose.dev.yml`

#### 4.3.1 The category: every place that names the file or relies on its name

This list is the `category.members` block for the diff. It was produced by
`git grep -n "docker-compose\.yml"` minus the `.prod`/`.observability` siblings, plus a second pass for
**implicit** references — commands that name no file because they rely on Compose's default discovery,
which is where the actual breakage lives.

| # | Member | Kind | Required action |
|---|---|---|---|
| M1 | `spring-boot-docker-compose`'s default discovery (`DockerComposeFile.SEARCH_ORDER`) | implicit, **measured breakage** | Add `spring.docker.compose.file=docker-compose.dev.yml` to `src/main/resources/application.properties`. §4.3.3 says why that file and not another. |
| M2 | `src/test/java/com/hamstrack/ops/PublishedCredentials.java:375-381` (`isDevelopmentCompose`) + its javadoc at `:365-374` | code, **measured breakage** | §4.3.2. |
| M3 | `src/test/java/com/hamstrack/common/security/JwtSecretValidationTest.java:483` (`new Case("docker-compose.yml", …)`) | code | Rename the case's path to `docker-compose.dev.yml`; its rationale string ("the local dev stack, in the file that creates it") stays true. |
| M4 | `JwtSecretValidationTest.java:680` and `:683` (the two `isDevelopmentCompose` assertions) + the message at `:686` | code | `:680` asserts the root file is development → becomes `docker-compose.dev.yml`. `:683` asserts a **subdirectory** copy is *not* development → becomes `examples/docker-compose.dev.yml`, preserving what that assertion exists to prove (path-awareness, not spelling). |
| M5 | `JwtSecretValidationTest.java:313` and `:650` (failure-message prose naming the file) | text in a failure message | Update both. A refusal that names a file which no longer exists sends its reader to the wrong place. |
| M6 | `README.md:58` — `docker compose up -d postgres mailhog` | implicit | `docker compose -f docker-compose.dev.yml up -d postgres mailhog`. |
| M7 | `README.md:39` — `run 'docker compose up -d'` in the **Self-hosting** section | implicit, and wrong for a second reason | Replaced wholesale by §4.4's install block. |
| M8 | `docs/self-hosting.md:106`, `:217`, `:262` | text inside the Quick start | Removed with the YAML (§4.5). `:262`'s paragraph ("Rather than hard-coding secrets in `docker-compose.yml`…") is **rewritten**, not just re-pathed — see R13. |
| M9 | `docs/design/published-credentials-proposal.md:148`, `:190` | dated design record | **No edit.** A proposal is a record of a decision on a date; the live rule is the javadoc at M2. Stated here so the omission is a decision rather than a miss. |
| M10 | `docs/release-checklist.md:1073` | prose about Compose's discovery on the **production** box | **No edit** — the sentence is about `compose.yaml`/`docker-compose.yml` as *Compose's* defaults and stays true. Re-read it after the rename to confirm; it is one line. |
| M11 | `.claude/pipeline/check-gates.mjs:70,85` (area regexes) | pipeline config | **No edit** (§11.3). Recorded because the rename changes which gates arm: `docker-compose.dev.yml` still matches both regexes; `deploy/dc/docker-compose.yml` matches the **config** regex (`(^\|\/)docker-compose[^/]*\.ya?ml$`) and **not** the ops one (anchored at `^`). |
| M12 | `CLAUDE.md` "Local dev environment" | owner-owned document | **Recommended, owner's call** (§11.4). The block names containers (`docker start hamstrack-postgres …`), not the file, so nothing in it is false after the rename — but the dev-run command's new `-f` belongs there. Note the side effect: an edit to § Gotchas/§ Quality rules resets `AgentChecklistFreshnessTest`'s clock; an edit to the "Local dev environment" block does not. |
| M13 | `.github/workflows/**` | — | **No action — measured absent.** CI uses a `services: postgres:` container (`build.yml:61-63`); no workflow names the root compose file. |
| M14 | `src/test/java/com/hamstrack/ops/ConfigDriftContainerOracleTest.java`, `DeployVerifyOracleTest.java` (many `docker-compose.yml` strings) | — | **No action.** Those are fixture files the tests *write into a scratch box*, not this repository's file. Verified by reading the surrounding `box.resolve(...)` / `write(...)` calls. |
| **M15** | `src/test/java/com/hamstrack/ops/EnvTemplateGuardTest.java:337-349` — `composeFiles()` enumerates **every tracked `docker-compose*.y(a)ml` anywhere in the tree**, harvests `${VAR:?` into `guardedVariables()`, and `everyGuardedVariableShipsEmpty` / `everyGuardedVariableIsNamedInTheTemplate` hold the result against `.env.prod.example`. | code, **measured breakage — MISSED BY THE FIRST DRAFT** | Creating `deploy/dc/docker-compose.yml` makes `SEED_ADMIN_EMAIL` a guarded variable for the first time tree-wide (measured: it is guarded by neither `docker-compose.prod.yml` nor any `application*.properties`), so `.env.prod.example:1363`'s `SEED_ADMIN_EMAIL=admin@your-domain.example` becomes an offence. **Resolved by emptying that line** — owner decision, and consistent with HD-200's own rule: `DataSeeder` (`:52` `@Value("${seed.admin.email:}")`, `:525-528`) returns before touching `users` on a blank address, so an unedited prod template now seeds nothing instead of an ACTIVE administrator at a placeholder domain. |
| **M16** | `docs/observability.md:849-859` — the DC layering command `docker compose -f docker-compose.yml -f docker-compose.observability.yml` and the "sample compose in self-hosting.md#quick-start" prerequisite | doc, **measured breakage — MISSED BY THE FIRST DRAFT** | Both re-pointed at `deploy/dc/docker-compose.yml`. Found by `DevComposeFileReferencesTest`, not by hand. |
| **M17** | `docs/self-hosting.md` — six further references to "the **Quick start** file" as a thing that *shows* YAML (`:344` ×2, `:630`, `:643`, `:2129`, `:2197`) | doc | §4.5 deletes the YAML those sentences point at. All six re-pointed at `deploy/dc/docker-compose.yml`. The original M8 listed three lines; these are the other six. |
| **M18** | the seal itself — every assertion keyed on a filename *literal* rather than on a file | **the defect P1 exposed** | New `DevComposeFileReferencesTest`: the population is derived (every publishable `.java` mentioning `isDevelopmentCompose`), the unit is the (source, path) pair, counterfactuals assert their own absence, and there is a floor plus a granularity control. |
| **M19** | `EnvTemplateGuardTest.guardedVariables()` reading the whole compose file as one string | code, **measured breakage — found by `security-officer`** | A line of PROSE spelling a live `${NAME:?}` registered `NAME` as a guarded variable (measured: `deploy/dc/docker-compose.yml:24` made `guardedCount` 11 instead of 10 and red the build naming `VAR`). Fixed on both sides: the comment no longer spells a live expansion, and the registry strips `#` comments first. Category swept — exactly one member existed, and it was the new file. **Corroboration that the stripper is the right place for the fix:** re-running the *un-stripped* harvester against the fixed tree reports `NAME`, harvested out of the explanatory comment added to warn about the trap. The prose documenting it would itself have sprung it, which no amount of care in writing comments can prevent. |
| **M24** | `literalEnvironmentNames` applied to **both** kinds of guard | code, **High, introduced by the M20 fix and found by `security-officer`** | `pairs()` groups every compose file in a directory, so at the root a *development* literal cancelled a *production* Compose guard: `GF_SECURITY_ADMIN_PASSWORD` and `OBS_ALERT_EMAIL_TO` left the guarded set entirely (measured, both before and after). Fixed by restricting the exemption to **property-derived guards only**. Sealed by `theLiteralExemptionNeverShrinksTheComposeHalf`, seen red against the planted leak. |
| **M25** | `withoutComments` cutting at a `#` inside a quoted scalar | code, Low | A silent narrowing of guard *detection*, in the direction nothing reds for. Fixed (quote-aware scan) rather than documented. Round 3 closed the two residuals the javadoc had only *named* — a `#` inside a block scalar and a backslash-escaped quote — and sealed all of them with a hand-written oracle table. |
| **M28** | `docs/observability.md` layering — where `.env` must live | doc, **High, found by `dc-cloud-guard`** | `--project-directory .` fixed the bind mounts and broke `.env`: both interpolation **and** `env_file:` delivery anchor on the project directory. Measured three ways — as documented it refuses naming a value the operator has already set; with `--env-file deploy/dc/.env` interpolation succeeds while all seven `MAIL_*` lines are **silently dropped** (same `.env`, delivered with project dir `deploy/dc`, absent with `.` — an instance that boots healthy and stops sending mail). **The reviewer's claim that no single-command form keeps both halves true is refuted by measurement:** a `.env` at the **repository root** keeps all three — `MAIL_*` delivered, bind mounts at the root, profile `dc`. The document now prescribes that one location and names both failure modes. |
| **M29** | `SEED_ADMIN_EMAIL` in every template | code, **found by `dc-cloud-guard`** | `CREDENTIAL_SHAPED` cannot match a name ending `EMAIL`, so the address half of the account-creating pair was held by nothing and the twelve lines of reasoning added at `.env.prod.example:1358-1374` were a claim with no rule behind them (measured: restoring the placeholder was green). Held now by `noTemplateShipsAValueThatWouldCreateAnAccount`, whose members are **derived from `DataSeeder`'s `@Value` keys with an EMPTY default** — so `seed.admin.display-name` (default `Admin`) is excluded by a property rather than by name. Deliberately **not** a `${SEED_ADMIN_EMAIL:?}` in `docker-compose.prod.yml`: that would refuse the `up` for every install that deleted the pair after seeding. |
| **M30** | the install command, all three sites | doc, **F10 adopted after measuring** | `up -d --wait --wait-timeout 120` replaces `up -d` + a prescribed `ps`. Measured both paths: healthy → exit 0 in ~35s; 9-byte `JWT_SECRET` → **exit 1**, `container dc-app-1 is unhealthy`. The witness moves from a step the reader must remember into the command's own exit code. The timeout is not optional — `restart: unless-stopped` makes a bare `--wait` wait for as long as the restarting continues. |
| **M31** | `SPRING_PROFILES_ACTIVE`, `DB_*`, `APP_PORT`/`APP_BASE_URL`, the guarded-set counts, the `[Mail](#mail)` anchor | doc, F2/F4/F5/F6/F7 | Each fixed. **F6's premise was partly refuted**: `docs/self-hosting.md` does carry a `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` row — combined, which is why a per-name grep missed it — but it said nothing about which of the three each stack lets you set, so it was rewritten rather than added. F5's three bare counts are gone; the enumerations in the command blocks stay, because they are the actionable instruction and each already sits beside "if you skip one, the next command refuses and names it". |
| **M32** | the Compose **project name** when layering | doc, **found by `dc-cloud-guard`** | Measured: the everyday form (`cd deploy/dc`) is project `dc` with `dc_postgres_data` / `dc_attachments_data`; the layering form takes its name from the *root directory* (whatever the clone is called) and therefore creates **new empty volumes** — Flyway migrates an empty schema, the admin is re-seeded, nothing errors, and the operator's data sits unattached on the host. `deploy/dc/README.md` already warned about exactly this while `docs/observability.md` never mentioned the project name. Fixed by pinning `COMPOSE_PROJECT_NAME=dc` in the root `.env` — measured to hold all four properties at once (project, volumes, bind mounts, delivery). |
| **M33** | the DC README's everyday commands after the layering move | doc, R2 | They run from `deploy/dc/` and no longer find the moved `.env` (`config -q` exits 1 naming a value the operator has set). Both documents now say the other one changed. |
| **M34** | `DevComposeFileReferencesTest.DASH_F_COMMAND` | code, **the seal blind to this diff's own command** | It allowed one optional global flag and captured only the FIRST `-f`, so the layering command carrying `--project-directory .` left the population **entirely**, and every layered command in the tree had its second file checked by nothing — silently, because the `>= 1` floor stayed satisfied by the single-file commands. This is the class's own failure shape reproduced inside it. Fixed: arbitrary leading global flags, shell line-continuations joined as a shell joins them, and **every** `-f` captured. **Measured population: 21 → 31 paths**, 8 of them in commands naming more than one file. A new **shape** floor (at least one command names two compose files) guards the regression that a count floor could not see. |
| **M35** | `DB_USERNAME` on the DC observability path | doc, **High, found by `ops-reviewer` on a running stack** | `docker-compose.observability.yml:198` reads `${DB_MONITOR_USER:-${DB_USERNAME}}`; the DC stack sets `DB_USERNAME` as a compose **literal**, which interpolation cannot see — so the exporter starts with an empty user and can never log in. Read back live: `up{job="postgres"}=1` but `pg_up=0`, `password authentication failed for user "nobody"`, and a **critical `PostgresDown` alert firing about a healthy container**. Invisible to every configuration-level check, because `DATA_SOURCE_USER: ""` is *valid*. Doc-only fix (`DB_USERNAME=hamstrack` in the root `.env`) — the stronger `${…:?}` fix costs a production deploy and is a separate ticket. **Category verified independently and there is no fourth member:** the bare (unguarded) references in that file are exactly `DB_PASSWORD`, `DB_USERNAME` and `LOKI_RETENTION_PERIOD`; `DB_PASSWORD` is in the DC `.env`, and `LOKI_RETENTION_PERIOD`'s bare uses are a comment and an in-container expansion that `:41` defaults. |
| **M36** | two claims in `docs/observability.md` | doc, F1/F3 | `:1035` said the exporter "falls back to `DB_USERNAME`/`DB_PASSWORD`" — true of the prod stack, false of the DC one. `:1097` promised `up` "aborts immediately … the error names the variable" — measured false for these two, which warn and proceed; the fail-fast row now names only the two genuinely guarded variables and a new row covers the silent case. **And a claim of mine was measured false:** I wrote that the wrong layering form "comes up observing nothing while reporting success" — it exits **1** (Docker creates directories at missing bind sources; Prometheus and Loki both refuse). Corrected, with the `rm -r deploy/dc/observability` cleanup the failure leaves behind. |
| **M37** | the memory-ceiling contract | code, F4 | `ProdComposeContractTest` derives its population from `apply-config.sh`, correctly for "what a deploy places" — but the *rule* (`-XX:MaxRAMPercentage=50` reads HOST RAM without a container limit) is about any container a person runs, and `deploy/dc/` was outside it. Added a **sibling** rather than widening in place: the existing method name is quoted twice in `ops/deploy/apply-config.sh`, a synced file this ticket must not touch, so widening would have left that name describing a population it no longer had. Development stacks are excluded by the live `.dev.` marker, not a list. |
| **M27** | the exemption's **equivalence**, never stated | doc, round 3 | `application.properties` declares exactly **four** no-default variables (measured), so the confined exemption excuses `DB_URL` and nothing else at the root pair — precisely the deleted map's single entry — plus `DB_USERNAME` at `deploy/dc/`, correctly. Recorded in the javadoc, because it converts "derived, therefore fine" into a measured equivalence. The property half has no delta control of its own; noted there as a bounded structural gap, not a defect. |
| **M26** | the **index**, as distinct from the working tree | process, Low | The H-1 source fix lived only in the working tree while the staged blob still carried `${VAR:?}`: tests read the working tree, so they were green over content a commit would not have shipped. Index and working tree are now identical. |
| **M20** | `EnvTemplateGuardTest`'s template↔stack pairing, hard-coded to `.env.prod.example` | code, **found by `security-officer`** | Pairing is now **derived per directory**, so `deploy/dc/.env.example` ↔ `deploy/dc/docker-compose.yml` is a pair and the prod template is no longer held to the DC stack's guards. Floor of 2 pairs. A third rule asserts every guarded stack has a template beside it, so a template-less stack cannot ship silently. |
| **M21** | `PublishedCredentials.isProductionConfiguration` reaching operator manuals only via the enumerated `OPERATOR_FACING_DOCUMENTS` set | code, **found by `security-officer`** | `deploy/dc/README.md` — the first document a stranger opens — classified as neither template nor production configuration, so the dev stack's credentials would have been acceptable in it. Fixed by a `deploy/` **prefix** clause beside `ops/`, so the next install README is covered on the day it is written. |
| **M22** | `docs/self-hosting.md` § TLS — "drop the app's `ports: 8080:8080`" | doc, **found by `security-officer`** | That literal does not exist in the new file, so the remedy was unperformable and the reader ends up with Caddy on 443 *and* the app on public `:8080`. Rewritten around `APP_BIND=127.0.0.1`. `APP_BIND`/`APP_PORT` also gained Configuration rows (they had none), and `deploy/dc/README.md` gained the exposure note. |
| **M23** | `docs/observability.md` layering command | doc, **found by `security-officer`** | Compose takes the project directory from the first `-f`, so the command as first written resolved the observability bind mounts under `deploy/dc/observability/` (measured) and would have come up observing nothing. Fixed with `--project-directory .`, measured to resolve them at the repository root. |

**Non-members, stated so the reviewer need not re-derive them:** the test suite does not break, because
`DockerComposeSkipCheck.shouldSkip` returns true whenever `spring.docker.compose.skip.in-tests` (default)
holds and the stack contains `org.junit.platform.` / `org.springframework.boot.test.` — **read**, from
the Boot 4.1.0 sources; corroborated by the fact that only two test classes bother to set
`spring.docker.compose.enabled=false` explicitly.

#### 4.3.2 The compensating edit to `PublishedCredentials`

```java
// today (:375-381)
public static boolean isDevelopmentCompose(Path file) {
    String path = repositoryPath(file);
    if (path.contains("/")) {
        return false;
    }
    return path.equals("docker-compose.yml") || path.matches("docker-compose\\..*\\.dev\\.ya?ml");
}
```

**Measured** (Appendix B.5): `docker-compose.dev.yml` satisfies neither clause — the regex requires a
segment between two dots, which `docker-compose.observability.dev.yml` has and `docker-compose.dev.yml`
does not.

> **Measured correction (implementation, 2026-09-15).** The paragraph below called this change
> "required". It is **not required to keep the suite green** — the rename alone is green (P1) —
> and applied *alone* it is red. It is applied here as a deliberate decision, together with
> M3/M4/M5, on the ground stated below: the exempt set is provably unchanged (both values come
> from `docker-compose.observability.dev.yml`), and dropping the bare-filename clause **narrows**
> the rule, because the exemption stops being granted by occupying a well-known name.

**Required change:** make the `.dev.` marker the whole of the rule and **drop the bare-filename clause**.

```java
return path.matches("docker-compose(\\..*)?\\.dev\\.ya?ml");
```

Two properties this buys, and both belong in the javadoc:

- A development compose file **says so in its name**. The exemption is no longer granted by occupying a
  well-known filename, so re-adding a root `docker-compose.yml` later cannot silently re-open it.
- The path-awareness is unchanged: the `path.contains("/")` guard stays, so
  `deploy/dc/docker-compose.yml` is *production configuration* — which is exactly what we want, because it
  puts the new template inside the credential scan from the day it exists.

**Direction of risk, and it is asymmetric.** Widening this predicate widens a *credential exemption*. The
change above widens it by exactly one filename that this commit creates; anything broader (e.g. matching
`*dev*`) must be refused in review.

#### 4.3.3 Where `spring.docker.compose.file` goes

**In `src/main/resources/application.properties`**, not in `application-local.properties`.

- **Measured:** the documented dev command (`mvnw spring-boot:run`, README:64 / CLAUDE.md) activates **no
  profile** — the boot log reads `No active profile set, falling back to 1 default profile: "default"`
  (Appendix B.3). A line in `application-local.properties` would therefore never be read by the command it
  is meant to fix.
- **Measured:** the property works. With `--spring.docker.compose.file=docker-compose.dev.yml` the manager
  logs `Using Docker Compose file …\docker-compose.dev.yml` and proceeds (failing afterwards only because
  the Docker daemon is down on this machine) — Appendix B.3b.
- **Measured: it is inert in production.** `spring-boot-docker-compose` is `<optional>true</optional>`
  (`pom.xml:152-156`) *and* the repackaged jar contains **zero** entries matching `docker-compose`
  (Appendix B.8), so the module is not in the image and the property is read by nothing there. This is the
  premise that makes a line in the always-loaded properties file safe, and it is measured rather than
  assumed.
- Write it as `spring.docker.compose.file=docker-compose.dev.yml` with a comment naming the two things that
  break without it (the boot, and the CLAUDE.md gotcha about `DB_URL` being overridden — which only makes
  sense while the integration is actually running).

**Consequence to state in that comment:** with the property set, a `spring-boot:run` from a directory that
has no `docker-compose.dev.yml` fails with `Docker compose file '…' does not exist` instead of
*silently* skipping. That is the same class of failure as today and is not a regression.

### 4.4 `README.md` — the install command, exact and complete

The **Self-hosting (DC)** section's prose command is replaced by a block that runs as written:

````markdown
```bash
git clone https://github.com/Zherikhov/hamstrack.git
cd hamstrack/deploy/dc
cp .env.example .env
# Fill DB_PASSWORD, JWT_SECRET, SEED_ADMIN_EMAIL, SEED_ADMIN_PASSWORD.
# If you skip one, the next command refuses and names it.
docker compose up -d
docker compose ps        # both services should read `healthy` within ~60s
```

Then open <http://localhost:8080> and sign in with `SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD`.
No SMTP server and no domain are needed for this.
````

**Rules:**

- **R13 — `docker compose ps` is part of the install command, not an afterthought.** `docker compose up -d`
  exits `0` while a container crash-loops; this project has written that sentence three times about
  production (`docker-compose.prod.yml:218`, `docker-compose.observability.yml:87`,
  `.env.prod.example:1543`) and never once put the check into the install instruction. The witness is the
  `ps`, so the `ps` is in the block.
- **R14 — the development block gets its `-f`** (M6), and the sentence introducing it says the file is the
  *development* stack, so the two blocks cannot be confused by someone scanning for "compose".
- **R15 — no image tag is named in README prose.** `APP_IMAGE_TAG=0.4` at `:39` goes away entirely rather
  than moving to `0.18`: the pin lives in `deploy/dc/.env.example`, and a README that repeats it is a
  second member for HD-313 to sweep next time. Where README must mention pinning, it says *"pin a release
  line in `deploy/dc/.env` — the template names the current one"* and links.
- **R16 — the clone URL is a measured fact**, not a guess: `https://github.com/Zherikhov/hamstrack`
  (README badge, `:3`; the image namespace `ghcr.io/zherikhov/hamstrack` confirms the owner spelling).

### 4.5 `docs/self-hosting.md` — the stack stops being printed here

**Decision (D3): the Quick start's YAML fence and its `.env` fence are deleted.** What replaces them:

1. the same command block as README (one canonical copy — see R17 below);
2. links to `deploy/dc/docker-compose.yml` and `deploy/dc/.env.example`;
3. the prose that cannot live in a YAML file and is the actual value of that section: *why* every required
   value ships empty, what the refusal looks like, what the first run does, and the pointer to
   [An unedited template is refused, by design](../self-hosting.md#an-unedited-template-is-refused-by-design)
   (which stays, and is now about a file that exists);
4. **at most one excerpt**, and only if the prose genuinely needs to point at a specific line.

**R17 — how a document quotes a file without becoming a second copy.** Three permitted forms, in order of
preference:

| Form | Rule | Why it is safe |
|---|---|---|
| **A — link only** | `[deploy/dc/docker-compose.yml](../deploy/dc/docker-compose.yml)` | Nothing to drift. HD-324's seal need only check that the path exists. |
| **B — a marked excerpt** | The fence carries a marker line the seal can read: ` ```yaml quoted-from: deploy/dc/docker-compose.yml ` and the fence body must be a **literal substring** of that file (modulo leading indentation). | A drift is a red build rather than a discovery. This is the one mechanism that makes quoting legitimate, and it is the thing HD-324 must implement (§13.2). |
| **C — a whole-file copy** | **Forbidden.** | It is the defect this ticket closes. |

An excerpt in form B must be ≤ 20 lines. A longer one is form C wearing a marker.

**R18 — one paragraph in that section becomes false and must be rewritten, not moved.**
`docs/self-hosting.md:252-258` states, correctly for the hand-copied stack, that *"a variable reaches the
app only through a line in `environment:`"*. `deploy/dc/docker-compose.yml` uses `env_file:`, so for the
bundled DC stack the opposite is true. The rewritten paragraph must say which file does which, and the
sentence should be phrased over the **category** — *"a variable reaches the container only through an
`environment:` line or an `env_file:`; the bundled stacks use `env_file:`, a compose file you write
yourself probably does not"* — rather than by naming today's files, which is the shape that goes stale.

**R19 — `docs/self-hosting.md:441`** ("`.env.prod.example` is a template to crib from — it's
owner-oriented") now has a better answer for a self-hoster and must point at `deploy/dc/.env.example`
first, keeping the prod template as the *reference for every variable*. That single sentence is the whole
of HD-315's overlap with this ticket; anything more is HD-315's.

### 4.6 The image tag, and what keeps it current (D2)

**The value: `0.18`** — the current release **line**.

| Candidate | Rejected because |
|---|---|
| `latest` | Moves on every `main` build *and* every stable release tag (HD-115) — it can jump mid-upgrade. `docker-compose.prod.yml:42-46` already tells self-hosters not to use it. |
| `0.18.2` (exact patch) | Correct today, stale on the next patch, and it teaches a reader to copy an exact number — which is how `0.4.3`, a tag that was **never published**, ended up in `docs/self-hosting.md:1525` (HD-313, measured there; the registry holds `0.4`, `0.4.5`, `0.4.6`). |
| `0.4` | The abandoned line HD-313 exists to remove. |
| `0.18` | **Measured to exist** (anonymous GHCR read today: `HTTP/1.1 200` with `docker-content-digest: sha256:61a9a6f5…`, Appendix B.7) and **measured to be produced by the release pipeline as a rule**, not by hand: `build.yml:318-322` configures `docker/metadata-action` with `type=semver,pattern={{version}}` **and** `type=semver,pattern={{major}}.{{minor}}`, so every stable release tag publishes both `X.Y.Z` and `X.Y`. A minor line therefore keeps receiving patch releases and never silently disappears. |

**What keeps it from becoming the next `0.4`** — two mechanisms, because a person is not one:

1. **HD-324's seal, tree-derived.** The current line is `major.minor` of the newest git tag, obtained from
   `git tag` / `git describe`, **not** from `pom.xml` (which is the `0.0.0-DEV` placeholder, `:15`, and
   would make the assertion vacuously wrong). **Measured prerequisite, and HD-324 should not have to
   rediscover it:** the CI test job already checks out with `fetch-depth: 0` (`build.yml:78-82`), for
   `AgentChecklistFreshnessTest`, so tags are present where the seal runs. The assertion: *every image-tag
   example in the install-facing category equals the current line*. When `0.19.0` is tagged, the build goes
   red naming `deploy/dc/docker-compose.yml`, and the fix is a one-character edit.
2. **The registry check stays out of the test suite and goes into the release checklist.** A unit test that
   reaches ghcr.io is a test that fails on an aeroplane and gets muted. The checklist step is: after the
   release build publishes, `curl` the manifest for the line the template names and confirm `200`. (The
   exact anonymous-read recipe is in Appendix B.7 — hand it to HD-324 rather than re-inventing it.)

**Note for HD-313:** this ticket's files are already on the current line, so HD-313's sweep must treat
`deploy/dc/**` as *already correct* and enumerate its members from the tree rather than from its own filed
list, which predates these files.

---

## 5. Edge cases & failure modes

### 5.1 The first run, precisely

| Step | What happens | Label |
|---|---|---|
| Schema | Flyway migrates an empty database on startup. | read (`README.md:39`, `docs/self-hosting.md` Quick start) |
| Administrator | `DataSeeder` creates a user with `UserStatus.ACTIVE` and `SystemRole.ADMIN` (`DataSeeder.java:612-620`) — **no email verification step**. It is idempotent: an existing account at that address is *promoted* rather than duplicated (`:602-610`). | read |
| Signing in | Password login. Self-registration is closed (`application-dc.properties:15`), so this is the only door. | read |
| More users | `/admin` → Users → New user hands out a one-time setup link; **no mail is sent** (`docs/self-hosting.md:822-826`). | read |
| **Therefore** | **SMTP is not required to install or to use a small instance.** It is required for verification, invite and password-reset *delivery*. | inferred from the four rows above; **P2 settles it end to end** |

Corroborating measurement: the application boots with no SMTP configuration at all —
`spring.mail.host=${MAIL_HOST:localhost}` (`application.properties:194`) and
`management.health.mail.enabled=false` (`:184`, with the comment "*whenever the mail server is
unreachable (e.g., CI with no MailHog)*"), so an unreachable SMTP host cannot fail the health check that
Compose reads.

This is the fact HD-320 needs. `docs/self-hosting.md:681-682` currently says *"Email verification doubles
as login, so a working SMTP server is required for a usable instance — without it, no one can complete
registration"*, which is true of the **Cloud** signup flow and false of a DC install, where nobody
registers at all.

### 5.2 Refusals — what a wrong or missing value looks like

| Situation | What the operator sees | Label |
|---|---|---|
| No `.env` at all | `error while interpolating services.app.environment.JWT_SECRET: required variable JWT_SECRET is missing a value: set JWT_SECRET in .env - openssl rand -base64 48` | **measured** (B.4a) |
| `.env` copied and not edited | `error while interpolating services.postgres.environment.POSTGRES_PASSWORD: required variable DB_PASSWORD is missing a value: set DB_PASSWORD in .env beside this file` | **measured** (B.4b) |
| **One at a time.** Compose stops at the first unresolved variable, and which one comes first is a property of map ordering, not of the file's line order | The reader fills the named value and re-runs; the next one names itself. The template says so in its header, so this reads as the design and not as a flaky tool. | measured (the two rows above named different variables) |
| Nothing is created or stopped by a refusal | Interpolation is resolved before Compose acts, so a running stack keeps running. | read (`docs/self-hosting.md:298-305`) |
| `JWT_SECRET` under 32 bytes | The **app** refuses at startup: `jwt.secret (JWT_SECRET) must be at least 32 bytes for HMAC-SHA256; current value is …` (`JwtService.java:97-101`). Compose still exits 0 — the witness is `docker compose ps` / `logs app`. | read |
| `JWT_SECRET` set to a value this project published | Startup refusal by name (`JwtService.PUBLISHED_PLACEHOLDERS`, `:53-55`). | read |
| `SEED_ADMIN_PASSWORD` = the published value | Startup refusal at context refresh, **before the port binds** (`DataSeeder.refusePublishedCredentials`). | read |
| `SEED_ADMIN_PASSWORD` over 72 UTF-8 bytes | Startup refusal naming the byte count and the remedy (`DataSeeder.java:218-227`). | read |
| `SEED_ADMIN_EMAIL` over 255 characters folded | Startup refusal (`DataSeeder.java:288-296`). | read |
| A `users` row already holds the folded address with a different spelling | Startup refusal naming the row id (`DataSeeder.java:594-600`). Fresh installs cannot hit it. | read |
| `APP_IMAGE_TAG` names a tag that does not exist | `docker compose up -d` fails at `pull` with `manifest unknown`. | inferred — standard registry behaviour; HD-318 owns the Troubleshooting row, so **do not add it here**, just do not contradict it |
| The host is arm64 | `exec format error` (the image is `linux/amd64` only — measured in HD-316 and re-measured here, Appendix B.7). **HD-316 owns the remedy.** | measured |

### 5.3 Concurrency, re-runs and the usual suspects

- **Re-running `up -d`** is idempotent; seeding is idempotent (§5.1). Re-running with a changed
  `SEED_ADMIN_PASSWORD` does **not** change the existing account — the template must not imply otherwise.
- **Two Hamstrack projects on one host** work, because no service declares `container_name` (R7). They
  collide only on the published port, which is `APP_PORT`.
- **Port 8080 already taken** → `Bind for 0.0.0.0:8080 failed: port is already allocated`; the remedy is
  `APP_PORT`, and the template says so in the row itself.
- **Port 15432** (the dev helper) and port 8080 (this stack) do not overlap, so a developer can run both.
- **Volumes:** named volumes `postgres_data` and `attachments_data` are scoped to the Compose project,
  which is derived from the directory name (`dc`). Two clones in different directories therefore do **not**
  share data. Say it in `deploy/dc/README.md`; it is the most surprising property of the layout.
- **`docker compose down -v` destroys both volumes.** The `deploy/dc/README.md` names `down` (safe) and
  `down -v` (not) separately.
- **Last-of-kind / archived / in-use-on-delete** — not applicable: this change creates no entity and no
  deletion path.

---

## 6. Data model impact

**None.** No migration, no entity, no column, no `FieldRegistry` name, no denormalised `workspace_id`.
Stated explicitly so `migration-reviewer` is not armed by a false positive: the Flyway history is untouched,
and the only database in this diff is a *container image tag* (`postgres:16-alpine`) that already appears in
`docker-compose.yml` and `docker-compose.prod.yml`.

The one adjacent fact worth writing down for the operator: a fresh `up -d` runs the whole migration history
into an empty database on first boot, which is what makes "no schema step" true in the install command.

---

## 7. API surface

**No endpoint is added, removed or changed.** Two existing surfaces are *depended on* and therefore become
install-critical; both already exist and both are unauthenticated by design:

| Surface | Used by | Consequence of changing it |
|---|---|---|
| `GET /api/meta` | the `app` healthcheck in **two** compose files now (`docker-compose.prod.yml` and `deploy/dc/docker-compose.yml`) and the operator's own `curl` | Renaming it or making it authenticated silently makes both stacks never report `healthy`, and `up -d` still exits 0. That is now a **category of two**, and whoever touches `MetaController` must check both. |
| `POST /api/auth/login` | the first-run instruction | — |

`api-docs-sync` is **not** armed by this diff (no controller, no `openapi.yaml` change).

---

## 8. Frontend impact

**None.** No component, route, token or `DESIGN.md` decision is touched; `src/main/frontend/**` is not in
the diff. `browser-qa` is not armed.

---

## 9. DC / Cloud implications

**Position, stated for `dc-cloud-guard` to check:**

1. **No behaviour changes for `cloud`.** No property default moves, no profile-gated branch is added, no
   code is forked. `application-cloud.properties` is not in the diff.
2. **The only shared file touched is `application.properties`**, which gains one line
   (`spring.docker.compose.file`). It is **measured inert in both deployed profiles**, because the module
   that reads it is not in the image at all (Appendix B.8). It is a *development* setting living in the
   always-loaded file for the measured reason that the documented dev command activates no profile
   (§4.3.3).
3. **The DC template fixes its profile as a literal** (`SPRING_PROFILES_ACTIVE: dc`), the inverse of
   `docker-compose.prod.yml:69` (`${SPRING_PROFILES_ACTIVE:-cloud}`) and of `.env.prod.example:300`
   (`SPRING_PROFILES_ACTIVE=cloud`). **The asymmetry is deliberate and is the design:** the production file
   serves an operator who legitimately chooses a model; the DC quick-start file serves a reader who has
   already chosen, and for whom a silent flip to `cloud` means S3 storage they have no bucket for, a 10 GB
   workspace quota and open public signup. HD-315 is the ticket for that failure; this file must not be a
   second instance of it.
4. **New names introduced by this change, with their full wiring:**

| Name | Read by | Declared in | Per-profile default | Not in |
|---|---|---|---|---|
| `APP_PORT` | **Compose only** — the host side of the port mapping. No Spring property reads it. | `deploy/dc/docker-compose.yml`, `deploy/dc/.env.example` | `8080` (compose-level `:-`), identical in both models because the container port is always 8080 | `application*.properties`, `.env.prod.example`, `docs/self-hosting.md`'s Configuration table — deliberately, because a Configuration row for a non-application variable is the kind of entry that later gets "wired up" by someone taking the table as a contract |
| `APP_BIND` | **Compose only** — the host IP of that mapping | same | `0.0.0.0` | same |

  Every *other* variable in the new files (`DB_*`, `JWT_SECRET`, `SEED_ADMIN_*`, `APP_BASE_URL`,
  `APP_STOP_GRACE_SECONDS`, `APP_MEMORY_LIMIT`, `POSTGRES_*`, `MAIL_*`, `APP_IMAGE_TAG`) already exists and
  is already documented in `docs/self-hosting.md`'s Configuration section; this ticket adds **no** new
  application property and therefore no new row there.
5. **Cloud's install path is untouched** (HD-317). `deploy/cloud/` is not created, and no document acquires
  a sentence about installing Cloud.

---

## 10. Acceptance criteria — over the category

Each criterion names the test shape that holds it. Where a criterion cannot be held by a test in this
ticket, it says so and names its owner, rather than being written as if it were sealed.

### A. Category — *every install command a document tells a reader to run*

**Members** (enumerated from the tree; this is the list HD-324 inherits):

| # | Location | Command |
|---|---|---|
| A-1 | `README.md` § Self-hosting (DC) | clone → `cd deploy/dc` → `cp .env.example .env` → edit → `docker compose up -d` → `docker compose ps` |
| A-2 | `README.md` § Development | `docker compose -f docker-compose.dev.yml up -d postgres mailhog` |
| A-3 | `README.md` § Development | `./mvnw spring-boot:run` (with the env prefix already in the block) |
| A-4 | `docs/self-hosting.md` § Quick start | the same block as A-1 |
| A-5 | `deploy/dc/README.md` | `docker compose up -d`, `docker compose ps`, `docker compose logs -f app`, `docker compose pull && docker compose up -d`, `docker compose down` |

- **A1.** Every member runs as written in a fresh clone, **verified by running it**. For A-1/A-4/A-5 that is
  probe **P2** (this machine, Docker Desktop started); for A-2/A-3 it is a real `mvnw spring-boot:run` after
  the rename, whose success line (`Started HamstrackApplication`) is pasted into the report. A clean-host
  run of a distribution nobody here develops on is **HD-312's DoD and is not claimed by this ticket**.
- **A2.** `docker compose config -q` exits 0 for every compose file a document names, **given that file's
  documented variable set** — for `deploy/dc/docker-compose.yml` that means with a `.env` holding the four
  required values. Phrased this way because it is the measured behaviour: bare `config -q` on that file
  *correctly* exits 1, naming a missing required variable, exactly as `docker-compose.prod.yml` does today
  (B.2). **HD-324 must implement the criterion in this form**; a seal that demanded a bare `config -q` pass
  would force the guards out of the file and re-create HD-200.
- **A3.** The DC stack exists **exactly once** in the repository. Test shape: a scan asserting that no
  tracked Markdown file contains a fenced block declaring a `services:` key with a `hamstrack` image unless
  the fence carries a `quoted-from:` marker whose target file contains the block verbatim (R17 form B).
  Floor: the scan must report the number of fences it examined, and refuse a population of zero.
- **A4.** Every fenced block in an install-facing document that names a file path names one that exists
  (`deploy/dc/docker-compose.yml`, `deploy/dc/.env.example`, `docker-compose.dev.yml`). **Owner: HD-324.**

### B. Category — *every compose file in the tree*

**Members:** `deploy/dc/docker-compose.yml`, `docker-compose.dev.yml`, `docker-compose.prod.yml`,
`docker-compose.observability.yml`, `docker-compose.observability.dev.yml`.

- **B1.** Each parses (`config -q`, with its own variable set).
- **B2.** Each service in each *deployed* file declares a memory ceiling — the existing rule
  (`ProdComposeContractTest#everyServiceInEveryDeployedComposeFileDeclaresAMemoryCeiling`, whose population
  is read out of `ops/deploy/synced-paths.txt`). `deploy/dc/docker-compose.yml` is **not** in that manifest
  and therefore not in that population; it nevertheless declares `mem_limit` on both services, for the
  reason `docs/self-hosting.md` § Requirements already gives (an unlimited container sizes the JVM heap
  against host RAM). **Decision to record:** do not add `deploy/dc/` to `synced-paths.txt` to get it into
  that test — the manifest describes what a deploy places on the production box, and widening it to gain a
  test would put the DC template on the production server.

### C. Category — *every place that names or relies on the dev compose file's name*

**Members:** M1-M14 in §4.3.1.

- **C1.** After the rename, no tracked file outside `deploy/dc/**` relies on Compose's default discovery in
  the repository root. Test shape: `DevComposeFileReferencesTest` with three assertions and a floor —
  (i) exactly one tracked file matches `^docker-compose\.dev\.ya?ml$` at the root; (ii) the file named by
  `spring.docker.compose.file` in `application.properties` exists; (iii) no tracked `README.md` /
  `docs/**.md` line contains `docker compose ` followed by a subcommand without a `-f` **unless** it is in
  the allow-list of production-box commands (which are executed in `/opt/hamstrack`, not here) — with the
  allow-list stated as a set in the test and its size asserted, so a new bare command is a deliberate
  addition.
- **C2.** `mvnw spring-boot:run` boots (negative control: the measured `No Docker Compose file found`
  failure of B.3, then green with the property).
- **C3.** `JwtSecretValidationTest` is green **and was seen red first** — probe P1. This is the Stop hook's
  negative control for this diff.

### D. Category — *every template value with no safe default*

**Members:** `DB_PASSWORD`, `JWT_SECRET`, `SEED_ADMIN_EMAIL`, `SEED_ADMIN_PASSWORD` in
`deploy/dc/.env.example`.

- **D1.** Each ships empty and each is `${VAR:?}`-guarded in the compose file, so an unedited copy cannot
  produce a running instance. Held by `JwtSecretValidationTest`'s existing scan, which picks the new
  template up automatically (`isEnvTemplate`, §4.2 R9) — **and that automatic pickup is itself a claim to
  verify**: the builder reports the file count the scan examined before and after adding the template, and
  the two numbers differ by one. **Measured: 4 → 5.**

  **Correction (implementation): "each ships empty" was held by nothing for this template.**
  `EnvTemplateGuardTest`'s two pair-level rules were keyed on the single hard-coded path
  `.env.prod.example`, so neither half of the new pair was reached — and `SEED_ADMIN_EMAIL`
  is not credential-shaped, so the credential scan does not cover it either. The pairing is
  now derived per directory (M20), and the gap is measured closed: planting
  `SEED_ADMIN_EMAIL=admin@example.invalid` in `deploy/dc/.env.example` now reds naming both
  halves of the pair.

  **And a coupling worth recording, because it explains an edit in this diff.** While the
  guarded set was global, every `${VAR:?}` anywhere was demanded of `.env.prod.example` — which
  is what forced `.env.prod.example:1363` to be emptied when this stack added a
  `SEED_ADMIN_EMAIL` guard. **Measured after the pairing became per-directory: that edit is no
  longer demanded** (the test is green with the old placeholder restored, because no *root*
  compose file guards `SEED_ADMIN_EMAIL`). The edit is kept on its own merits — an unedited
  prod template otherwise seeds an ACTIVE administrator at a placeholder address — but it is
  now an independent decision rather than a consequence of a coupling.
- **D2.** The refusal for each names the variable and a remedy the reader can perform (§5.2, measured for
  two of the four; the builder measures the other two by emptying them in turn).

### E. Observable-in-production criteria

There is no production surface in this diff (§13.1 explains what replaces one for an install artefact), so
no criterion here carries an *observed effect with a date* from the running system. The dated observations
this ticket does carry are the measurements in Appendix B, all **2026-09-15**, and P2's, which the builder
dates.

---

## 11. Open questions, each with a recommended default

| # | Question | Recommended default | Cost of the other branch |
|---|---|---|---|
| **11.1** | Directory name: `deploy/dc/` vs `install/dc/` vs `examples/dc/` | **`deploy/dc/`** — the ticket names it, and `ops/deploy/` already means "how this gets onto a box", so the word is consistent inside the repository. | A later rename breaks external links (blog posts, issue comments) and costs a redirect stub. Decide now, not after the release. |
| **11.2** | Does `deploy/dc/` carry a `README.md`? | **Yes**, ~15 lines: what the stack is, the five commands, `down` vs `down -v`, the volume-scoping note, a link to `docs/self-hosting.md`. A GitHub directory view renders it, and that is where a reader who follows a link lands. | Without it the directory is two files with no explanation, which is HD-323's complaint in miniature. |
| **11.3** | Should `.claude/pipeline/check-gates.mjs` be widened so `deploy/**` arms the **ops** gate? | **No edit in this ticket.** Arm `ops-reviewer` by hand for this diff. The hook is pipeline configuration; changing it inside a feature ticket is how a gate definition drifts. If the owner wants `deploy/**` in the ops area permanently, that is its own change with its own negative control. | Widening it here means this diff silently changes what every future diff is gated on. |
| **11.4** | Does `CLAUDE.md`'s "Local dev environment" block get the `-f docker-compose.dev.yml` command? | **Recommend yes, owner's call** — it is the block a fresh session reads. Note the mechanism: an edit there does *not* reset `AgentChecklistFreshnessTest` (which blames § Gotchas and § Quality rules), so it is a cheap edit. | Leaving it means the next session's first `docker compose up -d postgres` fails with `no configuration file provided`. |
| **11.5** | Should the dev helper instead be `compose.dev.yml`? | **No.** Measured: `compose.dev.yml` matches neither `isProductionConfiguration`'s filename pattern nor `isDevelopmentCompose`'s, so it would fall out of the credential scan's world entirely — a file classified as nothing. `docker-compose.dev.yml` also matches the `.dev.` convention `docker-compose.observability.dev.yml` already established. | An unclassified compose file is an exemption nobody wrote down. |
| **11.6** | Can the clean-host claim be made in this session? | **No, and the spec does not claim it.** Measurable here and measured: `docker compose config -q` (Compose v5.1.0 works with the daemon **down**), the GHCR manifest reads, the boot consequence of the rename and its remedy, the jar's contents, `git check-ignore`. Not measurable here: an actual `up -d` (**the Docker daemon is down on this machine today** — B.1), the first-run flow end to end (**P2**, once the daemon is up), and a timed install on a foreign distribution (**HD-312's DoD**). Substitute evidence for the last one: P2's local `up -d` plus A2's parse check, reported as exactly that and not as a clean-host run. | Claiming a clean-host run we did not perform is the 2026-09 retrospective's defect, filed against ourselves. |
| **11.7** | Should `docs/self-hosting.md` keep **any** excerpt of the stack? | **One, at most**, in form B (R17), and only for the `${VAR:?}` guard lines the surrounding prose is about. Otherwise link only. | Form B costs HD-324 a substring check; form C costs a release. |
| **11.8** | Should `*.yml` be pinned `text eol=lf` in `.gitattributes` for the new file? | **No.** The value whose CR does damage is one inside a `.env` (it lands inside a secret), and `*.env.example` is already pinned — **measured** in `.gitattributes`. A YAML parser tolerates CRLF. | An unnecessary `.gitattributes` line is a change with no effect for a reviewer to evaluate. |

---

## 12. ADR

**No new ADR.** The test is "a hard-to-reverse fork", and this is not one: `deploy/dc/` is two files plus a
README, the rename is mechanical, and the profile literal follows an existing rule rather than establishing
one. **ADR-0013** (configuration delivered from the repository) is the ADR that governs what reaches the
production box, and this change deliberately stays outside its manifest (§10 B2).

**One branch would need an ADR, and it is explicitly not taken here:** making `deploy/dc/` a *published
install surface* — a raw-URL `curl | docker compose -f -` recipe, a release asset, or a synced path. Each
of those creates a compatibility promise about a URL, which is exactly the kind of decision that is
expensive to walk back. If HD-317 or HD-321 wants one, it writes the ADR.

---

## 13. Observability contract

### 13.1 What tells the **operator** that the stack came up

An install artefact has no metrics endpoint of its own; its witnesses are the four readings the install
command tells the reader to take, and every one of them must be named in the document **before** it is
needed.

| Failure mode | Witness | Where the witness is named | Drill that proves it fires |
|---|---|---|---|
| A required value is missing | Compose's interpolation refusal, naming the variable and the remedy | `deploy/dc/.env.example` header; `docs/self-hosting.md` § Quick start | **Measured today** (B.4a, B.4b). Builder repeats it for the two variables not yet exercised. |
| The app container starts and dies (bad `JWT_SECRET`, a seed refusal, an unreachable database) | `docker compose ps` shows the service not `healthy` / restarting — **not** the exit code of `up -d`, which is 0 | the install command block itself (R13) | Plant a 10-byte `JWT_SECRET`, run the block, and paste `docker compose ps` plus the `logs app` line `jwt.secret (JWT_SECRET) must be at least 32 bytes…`. |
| The app is healthy but the operator cannot reach it | `curl http://localhost:${APP_PORT}/api/meta` returns JSON carrying the running version | `deploy/dc/README.md` | P2 pastes the response. |
| The wrong build is running | the `version` field of `/api/meta` (baked into the image at build time — `build.yml:330`) | `deploy/dc/README.md`, one line | Compare it with `APP_IMAGE_TAG`. |
| The image does not exist / wrong architecture | `pull` failure, `exec format error` | **HD-318 / HD-316** own these rows | — |

**The silence this closes:** `docker compose up -d` exits 0 while a container crash-loops. The project has
written that sentence three times about production and never put the check into the install instruction —
R13 does.

**The silence that remains open, deliberately:** nothing in a DC install reports *to us*. There is no
telemetry and none is proposed; the operator's console is the only observer. That is a property of the
deployment model, not a gap to be fixed here — and stating it is what stops a future reader assuming an
install failure would have been noticed somewhere.

### 13.2 What tells **us** that an install document drifted — what HD-324 needs from this change

HD-324 owns the mechanism. This change must leave it five things it would otherwise have to invent:

1. **One canonical location.** `deploy/dc/` is the single DC stack, so "the file the documents name" is one
   path, and A3 can be phrased as *"no second copy"* rather than *"copies agree"*.
2. **A machine-readable quote marker.** The `quoted-from: <path>` fence marker (R17 form B) is what turns
   "documents quote files" into a substring assertion. Introducing the marker here, with at most one use,
   means HD-324 implements a checker against a live example rather than against a convention.
3. **One live image-tag member.** `${APP_IMAGE_TAG:-0.18}` in exactly one file, and one commented echo.
4. **A tree-derived source of truth for the current line** — `git tag`, not `pom.xml` (`0.0.0-DEV`) — plus
   the **measured** fact that CI's test job already has tags (`fetch-depth: 0`, `build.yml:78-82`), and the
   registry read recipe in B.7 for the release-checklist half.
5. **The `config -q` criterion in its true form** (A2): *with the documented variable set*, because the
   guards are the feature.

**Negative controls HD-324 must be able to show**, each planted against this change: move the tag line to
`0.4` → red naming the file; delete `deploy/dc/.env.example` while a document still names it → red naming
the path; change a `quoted-from:` excerpt so it no longer matches → red naming both files.

---

## Appendix A — Premise ledger

Every premise this spec relies on, with its label. An **inferred** row names its probe.

| # | Premise | Label | Evidence |
|---|---|---|---|
| A1 | Root `docker-compose.yml` has `postgres` + `mailhog` and no `app`; 27 lines | read | whole file |
| A2 | `README.md:39` sends a self-hoster to a bare `docker compose up -d` | read | `README.md:39` |
| A3 | The only runnable DC stack is markdown at `docs/self-hosting.md:104-246` | read | fences at `:104` and `:215` |
| A4 | `docker-compose.prod.yml` refuses without `GITHUB_OWNER`; app port unpublished | measured | B.2; `:61`, `:319`, `:328-330` |
| A5 | Boot discovers `compose.yaml`, `compose.yml`, `docker-compose.yaml`, `docker-compose.yml` and **asserts** when none is found | measured | B.3; `DockerComposeFile.SEARCH_ORDER`, `DockerComposeLifecycleManager:182` |
| A6 | `--spring.docker.compose.file` resolves a renamed file | measured | B.3b |
| A7 | The docker-compose module is absent from the repackaged jar | measured | B.8 |
| A8 | Compose support is skipped under JUnit (`DockerComposeSkipCheck`) | read | Boot 4.1.0 sources; two tests set the flag anyway |
| A9 | `isDevelopmentCompose` does not match `docker-compose.dev.yml` | measured | B.5 |
| A10 | `JwtSecretValidationTest:483` encodes the dev-stack exemption keyed on the old filename | read | that line |
| A11 | Renaming without the §4.3.2 edit turns `JwtSecretValidationTest` red | **inferred** | **probe P1**. If refuted, §4.3.2 is a silent widening and must be stopped. |
| A12 | `isEnvTemplate` matches any `*.env.example`, so the new template joins the scan automatically | read | `PublishedCredentials:432-435`; verified in the diff by the file-count report (D1) |
| A13 | `deploy/dc/.env` is git-ignored; `.env.example` and `docker-compose.yml` are not | measured | B.6 |
| A14 | `.gitattributes` pins `*.env.example text eol=lf` | read | `.gitattributes` |
| A15 | The registry holds `0.18` (and `0.18.2`), and both are `linux/amd64` only | measured | B.7 |
| A15b | `0.18`, `0.18.2` and `latest` resolve to one digest today; `v0.18.2` is a **404** — a leading `v` is not a registry tag | measured | B.7 |
| A16 | The pipeline publishes `X.Y.Z` **and** `X.Y` for a stable release tag | read | `build.yml:318-322` |
| A17 | `pom.xml` is `0.0.0-DEV` and is never hand-bumped | read | `pom.xml:13-15` |
| A18 | CI's **test** job checks out with `fetch-depth: 0` | measured | `build.yml:78-82` |
| A19 | CI uses a `services: postgres:` container, not the repo compose file | read | `build.yml:61-63` |
| A20 | `DataSeeder` creates the admin `ACTIVE` with `SystemRole.ADMIN`, idempotently | read | `DataSeeder.java:602-620` |
| A21 | Public signup is closed on `dc` | read | `application-dc.properties:15` |
| A22 | New users are added by setup link with **no mail sent** | read | `docs/self-hosting.md:822-826` |
| A23 | The app boots with no SMTP; the mail health indicator is disabled | read | `application.properties:184, 194-199` |
| A24 | **An operator can therefore install and use a small DC instance with no SMTP at all** | **inferred** (from A20-A23) | **probe P2**. If refuted, §4.2's SMTP block and HD-320's answer both change. |
| A25 | `env_file: .env` with a missing file is a hard error; `required: false` fixes it | measured | B.4c |
| A26 | `.env` is auto-loaded from the compose file's directory, so `-f deploy/dc/…` from the repo root also works | measured | B.4e |
| A27 | `${APP_BIND:-0.0.0.0}:${APP_PORT:-8080}:8080` interpolates and flips `host_ip` | measured | B.4d |
| A28 | The install-facing documents currently declare SMTP mandatory | read | `docs/self-hosting.md:681-682` |
| A29 | `docs/self-hosting.md:252-258` states a rule that `env_file:` makes false for the new file | read | that paragraph |
| A30 | `docker compose up -d` exits 0 while a container crash-loops | read | `docker-compose.prod.yml:218`, `docker-compose.observability.yml:87-88`, `.env.prod.example:1543` |
| A31 | `/api/meta` is the healthcheck target of the production stack today | read | `docker-compose.prod.yml` app healthcheck |
| A32 | The hook's config regex matches `deploy/dc/docker-compose.yml`; its ops regex does not | read | `.claude/pipeline/check-gates.mjs:70, 85` |

---

## Appendix B — Measurements taken for this spec (2026-09-15, this machine)

**B.1 — Docker CLI present, daemon down.** `docker compose version` → `Docker Compose version v5.1.0`;
`docker version --format '{{.Server.Version}}'` →
`failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`. Everything below that
says "measured" was therefore obtained **without** a daemon, which is itself the finding that makes A2's
criterion cheap to run anywhere.

**B.2 — `config -q` on the shipped files.**
`docker compose -f docker-compose.yml config -q` → `exit=0`.
`docker compose -f docker-compose.prod.yml config -q` →
`error while interpolating services.app.image: required variable GITHUB_OWNER is missing a value: set GITHUB_OWNER in .env`, `exit=1`.

**B.3 — the rename's boot consequence.** A scratch working directory containing only
`docker-compose.dev.yml` (a copy of the root file):

```
mvnw.cmd spring-boot:run -Dspring-boot.run.workingDirectory=<scratch>
…
java.lang.IllegalStateException: No Docker Compose file found in directory '<scratch>\.'
    at org.springframework.boot.docker.compose.lifecycle.DockerComposeLifecycleManager.getComposeFile(DockerComposeLifecycleManager.java:182)
    at …SpringApplication.prepareContext(SpringApplication.java:418)
```

The same run also printed `No active profile set, falling back to 1 default profile: "default"` — which is
the measurement behind §4.3.3.

**B.3b — the remedy.** Same directory, plus
`-Dspring-boot.run.arguments="--spring.docker.compose.file=docker-compose.dev.yml"`:

```
DockerComposeLifecycleManager : Using Docker Compose file <scratch>\docker-compose.dev.yml
… ProcessExitException: 'docker version --format {{.Client.Version}}' failed with exit code 1.
Stderr: failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

i.e. the file resolves and the boot proceeds to the daemon, which is down on this machine.

**B.4 — the draft stack of §4.1, validated in a scratch directory.**

- **a)** no `.env`: `error while interpolating services.app.environment.JWT_SECRET: required variable JWT_SECRET is missing a value: set JWT_SECRET in .env - openssl rand -base64 48` → `exit=1`
- **b)** `.env` with all four lines **empty**: `error while interpolating services.postgres.environment.POSTGRES_PASSWORD: required variable DB_PASSWORD is missing a value: set DB_PASSWORD in .env beside this file` → `exit=1`
- **c)** with a plain `env_file: .env` and no file: `env file …\.env not found: GetFileAttributesEx …` → `exit=1`; with `env_file: [{path: .env, required: false}]` and the variables supplied from the shell → `exit=0`
- **d)** ports: `"${APP_BIND:-0.0.0.0}:${APP_PORT:-8080}:8080"` → `config` prints `host_ip: 0.0.0.0`; with `APP_BIND=127.0.0.1` → `host_ip: 127.0.0.1`
- **e)** run from the parent directory as `docker compose -f dcstack/docker-compose.yml config -q` with `.env` inside `dcstack/` → `exit=0`, image resolved to `ghcr.io/zherikhov/hamstrack:0.18`
- **f)** filled `.env`: `exit=0`

**B.5 — classification of the candidate names** (the two regexes of `PublishedCredentials`, evaluated
literally):

```
docker-compose.yml                    devPattern=false  (matched by the equality clause instead)
docker-compose.dev.yml                devPattern=false  filenameProdPattern=true
docker-compose.observability.dev.yml  devPattern=true
compose.dev.yml                       devPattern=false  filenameProdPattern=false
```

**B.6 — git-ignore status of the new paths.**
`git check-ignore -v deploy/dc/.env` → `.gitignore:62:.env` (ignored);
`deploy/dc/.env.example` → not ignored; `deploy/dc/docker-compose.yml` → not ignored.

**B.7 — registry, anonymous read** (the recipe HD-324's checklist step should reuse):

```
TOKEN=$(curl -s "https://ghcr.io/token?scope=repository:zherikhov/hamstrack:pull&service=ghcr.io" | …)
curl -s -o /dev/null -D - -X HEAD -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.oci.image.index.v1+json" \
  https://ghcr.io/v2/zherikhov/hamstrack/manifests/0.18
→ HTTP/1.1 200 OK   docker-content-digest: sha256:61a9a6f5fa20281e9686623e4f6fb817d9c71872f7a52f45eb4556f12ec59ab0
```

The index for `0.18.2` lists exactly one runnable platform — `architecture: amd64, os: linux` — plus an
attestation manifest (`architecture: unknown`). The tag listing (one page) also shows the published
non-sha lines `0.4, 0.4.5, 0.4.6, 0.13*, 0.14*, 0.15*, 0.16*, 0.17*, latest`, which corroborates HD-313's
measurement.

Digests read in the same pass, and two of them are worth carrying forward:

```
0.18    200  sha256:61a9a6f5…      0.18.0  200  sha256:332b73e2…
0.18.1  200  sha256:f733e104…      0.18.2  200  sha256:61a9a6f5…
0.17    200  sha256:740e4f6d…      latest  200  sha256:61a9a6f5…
v0.18.2 404  Not Found
```

- `0.18`, `0.18.2` and `latest` are **the same image today**, so a reader who pins the line is on the
  current build and gains nothing but safety by doing so — the sentence the template's `APP_IMAGE_TAG`
  comment should make.
- **A leading `v` is a git-tag spelling, not a registry tag** (`v0.18.2` → 404). A reader who copies the
  release name off the GitHub Releases page and pastes it into `APP_IMAGE_TAG` gets `manifest unknown`.
  That belongs in **HD-318's** Troubleshooting row, not in this ticket's files — recorded here so it is not
  re-discovered.

**B.8 — the docker-compose module is not in the image.**
`mvnw.cmd package -Dfrontend.skip=true -DskipTests` → `target/hamstrack-0.0.0-DEV.jar`;
`unzip -l … | grep -ci docker-compose` → **0**.

**B.9 — backlog search.** `.local/hd_dump.mjs` → 327 issues; title match on
`compose|install|self-host|quick-start|README|.env|stack|docker` returned HD-64, HD-199, HD-221, HD-249,
HD-250, HD-287, HD-312, HD-313, HD-314, HD-317, HD-318, HD-319, HD-320, HD-321, HD-324. No duplicate of
HD-314.

**Tree state:** every probe above ran in the session scratchpad or against read-only commands. The working
tree is exactly as it was found; the only file this session writes into the repository is this proposal.
