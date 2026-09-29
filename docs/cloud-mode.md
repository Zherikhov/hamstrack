# The Cloud model, run by you

Hamstrack ships one codebase in two deployment models. **Cloud** is what runs at
[hamstrack.com](https://hamstrack.com); **DC** is the self-hosted model, and
[the self-hosting guide](self-hosting.md) is where you should be if you want to
run Hamstrack for your team. This page is for the other question: *what is the
cloud model, and can I run it myself?*

You can, and this page is how. **What the licence forbids is offering Hamstrack
to third parties as a managed service** — running either model for yourself or
your organisation is explicitly permitted ([LICENSE](../LICENSE), ELv2). The
difference is not which profile you run; it is whether you are selling access.

## First, the fact that decides whether you need this page

**The two profiles differ only in defaults** — every behaviour below is reachable from
either one by setting a single variable, because each is declared as
`${VARIABLE:default}` and an environment variable wins over whatever default the
profile chose. The only difference no variable can move is the `deployment` label on
structured log lines, which reads `dc` or `cloud` and is how a log aggregator tells two
deployments apart.

| Behaviour | `dc` default | `cloud` default | The variable, settable in either |
|---|---|---|---|
| Attachment storage | local disk | S3 | `STORAGE_TYPE=local` \| `s3` |
| Self-registration | closed | **open** | `PUBLIC_SIGNUP_ENABLED` |
| First-login onboarding | off | on | `ONBOARDING_ENABLED` |
| Workspace storage quota | 100 GB | 10 GB | `STORAGE_QUOTA_WORKSPACE_BYTES` |
| CSP report sink | off | on | `CSP_REPORT_SINK_ENABLED` |

Two details that "differ only in defaults" hides, and both are worth knowing:

- **The two profile files are not line-for-line twins.** Each declares some of these
  settings and leaves the rest at the base default in `application.properties`:
  `application-cloud.properties` declares onboarding and the quota, and
  `application-dc.properties` declares self-registration. The *behaviour* is what the
  table says; the *mechanism* is one file naming a value and the other inheriting one.
- **Open registration is therefore the base default, not a cloud decision.**
  `PUBLIC_SIGNUP_ENABLED` is declared only by `dc`, which closes it. A run with the
  `cloud` profile, a run with **no** profile, and any third profile added later all get
  open signup unless something sets `PUBLIC_SIGNUP_ENABLED=false`. If you are adding a
  profile, or running without one, that is the line to set on purpose.

One row has a caveat in **this stack**: `STORAGE_TYPE` is settable on any install, but
`deploy/cloud/docker-compose.yml` pins it as a literal under `environment:`, which wins
over `.env` — deliberately, because this stack's endpoint, credentials and bucket all
point at the MinIO container beside the app. Want local-disk storage? That is the DC
stack. The pin protects the stack from a `.env` cribbed from somewhere else; it is not a
statement about the variable.

**So if you came here because you want S3 storage, or open registration, you do
not need this page or this stack** — set the variable on your existing DC install
and you have it. Come here when you want the cloud model as a whole: its defaults,
its shape, and a way to see how hamstrack.com behaves without an AWS account.

## What this stack is, and what it is not

`deploy/cloud/` runs the cloud model on one host with **no AWS account and no
public DNS**. Object storage is [MinIO](https://min.io), which speaks S3 and runs
beside the application; the app talks to it over the compose network.

It is **not** the stack that runs hamstrack.com. That one is
`docker-compose.prod.yml`: Caddy terminating TLS for a real domain, a real S3
bucket, an owner's AWS account, and the operational apparatus in
[ops-prod-hardening.md](ops-prod-hardening.md), which is addressed to the owner
and is not a self-hosting requirement. This stack is the **model**, not that
deployment.

## Install

**Needs an x86-64 (`amd64`) host**, the same as the DC stack: the published image
has no `arm64` build, so this fails on an Ampere or Graviton VPS, a Raspberry Pi or
an ARM virtual machine. Check with `docker version --format '{{.Server.Arch}}'`
before you start, and see [Requirements](self-hosting.md#requirements) for what to
do if that is what you have.

```bash
git clone https://github.com/Zherikhov/hamstrack.git
cd hamstrack/deploy/cloud
cp .env.example .env
# Fill DB_PASSWORD, JWT_SECRET, SEED_ADMIN_EMAIL, SEED_ADMIN_PASSWORD,
# MINIO_ROOT_USER, MINIO_ROOT_PASSWORD. If you skip one, the next command
# refuses and names it.
docker compose up -d --wait --wait-timeout 240
```

`--wait` makes the exit code the check — a plain `up -d` returns 0 while a
container is crash-looping. The timeout is longer than the DC stack's because
there are two more images to pull and a bucket to create.

Then open <http://localhost:8080>. Measured on a first install with cold images,
2026-09-23: **48.2 s** until the `up` returned, `/api/meta` reporting
`"publicSignupEnabled":true`, self-registration answering `201`, and an uploaded
attachment landing in MinIO under `ws/{workspace}/issues/{issue}/{uuid}` — in the
object store, not on a container's disk.

(That number was taken before `preflight` was added; it starts from an image the stack
already pulls and does two string comparisons, so it costs about a second.)

**`docker compose ps -a` then shows one-shot jobs as `Exited (0)` beside the running
app, and that is success.** Some of this stack's services are jobs rather than servers:
`preflight` checks the credentials MinIO would refuse, before the object store is
started, and `minio-bucket` creates the bucket and stops. Neither is ever "healthy",
because neither is still running to be asked — what the `up` waits for is that they
*completed*, and a job that exits non-zero fails the command naming the job. Without
`-a` you do not see them at all, which reads as services missing; with it, `Exited (0)`
reads as services dead. It is neither.

You can sign in as `SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD` — **or register
yourself**, which is the visible difference from a DC install: this model's sign-up
page is open to anybody who can reach the port. (Registration requires accepting the
Terms of Service; through the API that is `"termsAccepted": true`, and without it
the request is refused with `400` naming the reason.)

### The bucket is created for you, and that is load-bearing

The application **refuses to start** without a bucket name, but it does not create
the bucket, and a bucket that does not exist turns every upload into a `500` long
after the install looked successful. The `minio-bucket` service creates it once and
exits; the app waits for it through `depends_on: service_completed_successfully`, so
"the bucket exists" is a startup-time fact rather than something you discover from
the first person who attaches a file.

## Before you put this on the internet

**Open registration means a published port is a public sign-up page**, not merely a
public login. That is the model behaving as designed, and it is the one thing about
it that will surprise somebody who installed the DC stack first.

- `APP_BIND=127.0.0.1` keeps it on the loopback interface; reach it over an SSH
  tunnel while you evaluate.
- `PUBLIC_SIGNUP_ENABLED=false` gives you the cloud model's storage and onboarding
  with a closed door.
- MinIO's **console** is on loopback by default and its **API port is not published
  at all** — the app reaches it on the compose network. Publishing object storage is
  a separate decision from publishing the application, and this stack does not make
  it for you.
- With open registration and no SMTP, a person who signs up cannot complete
  verification. Either configure mail ([Email (SMTP)](self-hosting.md#email-smtp)) or
  turn signup off and add people yourself in `/admin`, which hands you a one-time
  setup link and sends nothing. If you do configure mail, set `MAIL_SMTP_AUTH` and
  `MAIL_STARTTLS` with the username and password: both default to **false**, so
  credentials on their own are never offered to the server.
- **The CSP report sink is on in this model, and it wants a second setting with it.**
  It is one unauthenticated endpoint collecting browser violation reports, and its
  per-sender budget keys on the client address — so behind a reverse proxy, where every
  request arrives from the proxy, that bound quietly degrades to one instance-wide
  bound. Set `RATE_LIMIT_TRUST_FORWARDED_FOR=true` with it, and only if your proxy
  overwrites a client-supplied `X-Forwarded-For` (the bundled Caddy does). On `dc` the
  sink is off, so this is the mode where the pair matters —
  [the guide pairs them](self-hosting.md#content-security-policy-report-only).
- **Open signup, 10 GB per workspace, no total cap, and the bytes are on your disk.**
  The cloud profile justifies that quota with an S3 bill; here every attachment lands in
  the `minio_data` volume on this host, and nothing bounds the *sum* across workspaces.
  With signup open, the ceiling on your disk is 10 GB times however many workspaces get
  created. Lower `STORAGE_QUOTA_WORKSPACE_BYTES`, close signup, or watch the disk —
  whichever, decide it before the port is public rather than after.

## When the `up` fails

The `up` returns non-zero and names a *service*, not a value. Read that service's log,
because the application and MinIO both refuse bad configuration **by name**:

```bash
docker compose ps -a                # which state each service is in, jobs included
docker compose logs preflight       # values this stack checks before anything starts
docker compose logs minio           # MinIO refuses bad root credentials by name
docker compose logs minio-bucket    # created the bucket, or said why it could not
docker compose logs app             # the app refuses bad values by name at startup
```

Two failures are worth knowing in advance:

- **`MINIO_ROOT_PASSWORD` shorter than 8 characters, or `MINIO_ROOT_USER` shorter than
  3.** MinIO enforces both and exits at startup when they are not met, so nothing in the
  stack would come up and the `up` would only ever tell you which *dependency* it gave
  up waiting for, after the whole `--wait-timeout`. `preflight` refuses it first instead:
  measured with a 7-character password, the `up` fails in about a second with
  `service "preflight" didn't complete successfully: exit 1`, MinIO is never started, and
  `docker compose logs preflight` names the variable, its length and the minimum.
- **A `$` in any value in `.env`.** Compose reads it as the start of a variable and an
  undefined name expands to nothing, so a password becomes a prefix of itself with no
  warning from anything. Write `$$` for one literal `$`, or generate values without one.

## Backups (what this stack keeps, and where)

**Two volumes, and a dump of the database alone is not a backup of this instance.**

- `postgres_data` — every workspace, issue, comment and user.
- `minio_data` — **every uploaded file.** This stack runs `STORAGE_TYPE=s3`, but the
  store is the MinIO container beside the app, so the attachments are in a Docker volume
  on this host's disk. Bucket versioning on somebody's AWS account is not what protects
  them, and the self-hosting guide's `aws s3api` recipe does not apply here.

Restoring the database without `minio_data` gives you an instance whose every attachment
row points at an object that does not exist: the issues, comments and history come back
and every file 404s. Back up both, from the same moment, and take a backup **before every
minor upgrade**.

The database procedure is the shared one —
[Backups](self-hosting.md#backups) in the self-hosting guide, which is `pg_dump` inside
the `postgres` container and works identically here.

For the object store there are two routes. The simple one is to **stop the stack and
copy the volume**: `docker compose down`, then archive the directory that
`docker volume inspect cloud_minio_data` reports as its `Mountpoint` (the volume name is
your compose project plus `_minio_data`, and the project name comes from this directory).
Consistent, offline, no credentials involved.

The other is to **mirror the bucket out with the client the stack already ships**, which
needs no downtime:

```bash
mkdir attachments-backup
docker compose run --rm -v "$PWD/attachments-backup:/backup" --entrypoint sh minio-bucket -c \
  'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" &&
   mc mirror --overwrite "local/$BUCKET" /backup'
```

Both credentials and the bucket name are already in that service's environment, so
nothing is typed twice. Whichever route you take, **verify a restore rather than
believing in one** — the same rule the self-hosting guide makes of database dumps
([Verify a restore](self-hosting.md#verify-a-restore)) — and take the object copy and the
database dump from the same moment.

## Everything else

Configuration reference, TLS and reverse proxy, attachment storage, upgrades and
troubleshooting are in [the self-hosting guide](self-hosting.md). They are not repeated
here, because they are not different: the profile chooses defaults, and every variable
that page documents is read the same way under either one. Where a value's default
differs, the guide's row says so.

**Backups are the exception**, which is why they have a section of their own above: that
page's attachment advice branches on `STORAGE_TYPE`, and this stack is in the `s3` branch
while its objects sit in a Docker volume on your host. Follow
[Backups (what this stack keeps, and where)](#backups-what-this-stack-keeps-and-where)
here, and the database half of the guide's procedure.
