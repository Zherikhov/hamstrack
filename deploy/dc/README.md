# Hamstrack — self-hosted (DC) stack

The application and PostgreSQL, and nothing else. No domain, no TLS and no SMTP server are
needed to get an instance running and sign in to it.

**Needs an x86-64 (`amd64`) host.** The published image has no `arm64` build, so the first
command below fails on an Ampere or Graviton VPS, a Raspberry Pi or an ARM virtual machine —
`no matching manifest for linux/arm64/v8`. Check with `docker version --format
'{{.Server.Arch}}'` before you start.

**The install steps live once, in the [repository README](../../README.md#self-hosting-dc)** —
copy the template beside this file, fill what the refusals name, and bring the stack up. You
are already in the directory they `cd` into, so you can start at the copy. They are not
repeated here on purpose: the same four commands in two files drift in their flags, and the
flag below is the one that matters.

`--wait` makes the exit code the check: it returns 0 only once both services are healthy
(~35s on a warm image), and otherwise fails naming the service. Without it, `up -d` returns 0
while a container is crash-looping. The timeout is required rather than tidy — these services
restart themselves, so a bare `--wait` waits for as long as the restarting continues.

Then open <http://localhost:8080> and sign in with `SEED_ADMIN_EMAIL` /
`SEED_ADMIN_PASSWORD`. Public self-registration is closed on a self-hosted install, so that
seeded administrator is the way in; further people are added in `/admin`, which hands you a
one-time setup link to pass on yourself.

**On a public server, the app is on the internet from this moment.** The port is published on
every interface by default, so `http://<your-public-ip>:8080` answers plain HTTP. That is the
right default for trying it out and the wrong one to leave: put `APP_BIND=127.0.0.1` in `.env`
and re-run `docker compose up -d` to publish it on the loopback interface only — then reach it
over an SSH tunnel, or through a TLS proxy on the same host
([TLS & reverse proxy](../../docs/self-hosting.md#tls--reverse-proxy)).

When the `up` fails, or later when something looks wrong:

```bash
docker compose ps                   # what state each service is actually in
docker compose logs -f app          # the app refuses bad values BY NAME at startup
curl -s localhost:8080/api/meta     # JSON carrying the running version
```

That `version` field is baked into the image at build time — compare it with your
`APP_IMAGE_TAG` when you are not sure which build is running.

## Everyday commands

```bash
docker compose pull && docker compose up -d   # upgrade to the newest patch on your line
docker compose down                           # stop; the data volumes SURVIVE
docker compose down -v                        # stop AND DESTROY both volumes
```

`down -v` deletes `postgres_data` (every issue, user and comment) and `attachments_data`
(every uploaded file). There is no undo.

These commands assume you run them **from this directory**, with `.env` beside them. If you
have added the [observability stack](../../docs/self-hosting.md#observability-optional), its
instructions move `.env` to the repository root and run from there — these then fail with
`required variable ... is missing a value`, naming something you have already set. Run the
full `-f` set from the root instead, and keep pinning `COMPOSE_PROJECT_NAME=dc`.

## Two things that surprise people

- **The volumes are scoped to this directory.** Compose derives its project name from the
  directory name, so a second clone in a different directory is a different project with
  its own empty database. Moving or renaming the directory therefore looks like data loss —
  and so does running the same stack from a *different* directory, which is why the
  observability instructions pin `COMPOSE_PROJECT_NAME=dc` rather than letting the name
  follow the directory.
- **Editing `.env` is not enough on its own.** `docker compose up -d` re-creates the
  containers with the new values; a running container keeps the values it started with.

## More

Full configuration reference, TLS and reverse proxy, email, attachment storage, upgrades
and backups: [`docs/self-hosting.md`](../../docs/self-hosting.md).

