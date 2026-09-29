# Hamstrack — the CLOUD model, on one host

The application, PostgreSQL and [MinIO](https://min.io) (object storage that speaks S3), and
nothing else. No AWS account, no public DNS and no SMTP server are needed to get an instance
running and sign in to it.

**This is the model, not the deployment.** It runs the `cloud` profile's defaults — S3 attachment
storage and open self-registration are the two you will notice first, and
[the guide's table](../../docs/cloud-mode.md) is the current list of all of them — so you can see
how the Cloud model behaves. It is not the stack that runs hamstrack.com (that one is
`docker-compose.prod.yml`: Caddy, a real certificate, a real bucket). **Most people self-hosting
Hamstrack want the DC stack instead**, in [`deploy/dc/`](../dc/).

**Needs an x86-64 (`amd64`) host.** The published image has no `arm64` build, so the install fails
on an Ampere or Graviton VPS, a Raspberry Pi or an ARM virtual machine — `no matching manifest for
linux/arm64/v8`. Check with `docker version --format '{{.Server.Arch}}'` before you start, and
see [Requirements](../../docs/self-hosting.md#requirements) for what to do if that is what you
have.

**The install steps live once, in [the Cloud-model guide](../../docs/cloud-mode.md#install)** —
copy the template beside this file, fill what the refusals name, and bring the stack up. You are
already in the directory they `cd` into, so you can start at the copy. They are not repeated here
on purpose: the same four commands in two files drift in their flags, and the flag that matters is
`--wait`, which is what makes the exit code the check — without it `up -d` returns 0 while a
container is crash-looping. The timeout is required rather than tidy: these services restart
themselves, so a bare `--wait` waits for as long as the restarting continues.

**This model's sign-up page is open to anybody who can reach the port**, which is the one thing
about it that surprises somebody who installed the DC stack first. The app port is published on
every interface by default, so on a public server `http://<your-public-ip>:8080` is a public
registration form from the moment the stack is up. Put `APP_BIND=127.0.0.1` in `.env` and re-run
`docker compose up -d` to publish it on the loopback interface only, then reach it over an SSH
tunnel — or set `PUBLIC_SIGNUP_ENABLED=false` if you want this model's storage and onboarding with
a closed door. MinIO's **console** is on loopback already and its **API port is not published at
all**: the app reaches it on the compose network, and publishing object storage is a separate
decision from publishing the application.

When the `up` fails, or later when something looks wrong:

```bash
docker compose ps -a                # -a, or you will not see the jobs that have exited
docker compose logs -f app          # the app refuses bad values BY NAME at startup
docker compose logs preflight       # a value this stack can check before anything starts
docker compose logs minio           # MinIO refuses bad root credentials BY NAME too
docker compose logs minio-bucket    # created the bucket, or said why it could not
curl -s localhost:8080/api/meta     # JSON carrying the running version
```

`docker compose ps -a` shows `preflight` and `minio-bucket` as **`Exited (0)`** beside the running
app. That is success, not failure: both are one-shot jobs. `preflight` checks the values MinIO will
reject *before* two images are pulled, and `minio-bucket` creates the bucket and stops — the app
waits for it to finish, which is what makes "the bucket exists" a startup-time fact rather than
something the first person to attach a file discovers as a `500`.

That `version` field from `/api/meta` is baked into the image at build time — compare it with your
`APP_IMAGE_TAG` when you are not sure which build is running.

## Everyday commands

```bash
docker compose pull && docker compose up -d   # upgrade to the newest patch on your line
docker compose down                           # stop; the data volumes SURVIVE
docker compose down -v                        # stop AND DESTROY both volumes
```

`down -v` deletes `postgres_data` (every issue, user and comment) **and `minio_data` (every
uploaded file)**. There is no undo.

**Those two volumes are also what a backup of this stack has to include.** Attachments are in
`minio_data` on this host's disk — this stack is `STORAGE_TYPE=s3`, but the store is the container
beside the app, so bucket versioning on somebody's AWS account is not what protects them, and a
database dump on its own restores an instance whose every attachment row points at nothing. The
procedure is in [the guide](../../docs/cloud-mode.md#backups-what-this-stack-keeps-and-where).

## Two things that surprise people

- **The volumes are scoped to this directory.** Compose derives its project name from the
  directory name, so a second clone in a different directory is a different project with its own
  empty database and its own empty object store. Moving or renaming the directory therefore looks
  like data loss, and so does running the same stack from a *different* directory.
- **Editing `.env` is not enough on its own.** `docker compose up -d` re-creates the containers
  with the new values; a running container keeps the values it started with.

## More

The Cloud model, what it changes and what it does not, before you put it on the internet, and this
stack's backups: [`docs/cloud-mode.md`](../../docs/cloud-mode.md). Configuration reference, TLS and
reverse proxy, email, upgrades and troubleshooting are shared with the self-hosted model and live
in [`docs/self-hosting.md`](../../docs/self-hosting.md) — the profile chooses defaults, and every
variable that guide documents is read the same way here.
