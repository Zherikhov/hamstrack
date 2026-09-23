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

**The two profiles differ only in defaults.** Every setting below is declared once
in `application.properties` as `${VARIABLE:default}`, and `application-cloud.properties`
and `application-dc.properties` disagree about nothing except which default applies.

| Behaviour | `dc` default | `cloud` default | The variable, settable in either |
|---|---|---|---|
| Attachment storage | local disk | S3 | `STORAGE_TYPE=local` \| `s3` |
| Self-registration | closed | **open** | `PUBLIC_SIGNUP_ENABLED` |
| First-login onboarding | off | on | `ONBOARDING_ENABLED` |
| Workspace storage quota | 100 GB | 10 GB | `STORAGE_QUOTA_WORKSPACE_BYTES` |
| CSP report sink | off | on | `CSP_REPORT_SINK_ENABLED` |

The only thing a variable cannot move is the `deployment` label on structured log
lines, which reads `dc` or `cloud` and is how a log aggregator tells two
deployments apart.

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
2026-09-23: **48.2 s** to every service healthy, `/api/meta` reporting
`"publicSignupEnabled":true`, self-registration answering `201`, and an uploaded
attachment landing in MinIO under `ws/{workspace}/issues/{issue}/{uuid}` — in the
object store, not on a container's disk.

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
  setup link and sends nothing.

## Everything else

Configuration reference, TLS and reverse proxy, attachment storage, backups,
upgrades and troubleshooting are in [the self-hosting guide](self-hosting.md). They
are not repeated here, because they are not different: the profile chooses defaults,
and every variable that page documents is read the same way under either one. Where
a value's default differs, the guide's row says so.
