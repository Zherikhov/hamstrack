# ADR-0013: Production configuration is delivered from the repository by commit sha; only secrets and the image tag stay on the box

Record date: 2026-08-26
Status: Proposed
Source: `docs/design/config-delivery-proposal.md` (HD-122 + HD-199); the measurements on the production
machine — in the same document §1 (taken by the owner 2026-08-26)

## Context

The deploy (`.github/workflows/deploy.yml`) runs a single SSM command — `cd /opt/hamstrack &&
docker compose … pull && up -d --remove-orphans && docker image prune -f` — and **copies
nothing**. So every file in `/opt/hamstrack` is whatever a human last put there.

What came of that, measured on the machine 2026-08-26 (not inferred from the code):

- `/opt/hamstrack/docker-compose.prod.yml` is dated **11 July**;
  `docker-compose.observability.yml` was dated **6 August**, until it was copied
  by hand on that same day.
- **The application container has no memory limit:** `docker inspect … .HostConfig.Memory` → `0`.
  The image meanwhile carries `-XX:MaxRAMPercentage=50.0` (HD-152), and without a cgroup limit the percentage
  is counted from the **host's RAM** (1909 MB) — a heap ceiling of ≈ **954 MB** where ~330 MB is available
  and there is no swap. Before 0.17.0 the JVM took its ~25% ≈ 477 MB, that is, the "limit the container's heap"
  release **doubled** it in production. `APP_MEMORY_LIMIT=1g` lies in `/opt/hamstrack/.env` and is
  read by nothing.
- **`RATE_LIMIT_TRUST_FORWARDED_FOR` is absent from the container's environment.** In the repository it is
  set to `"true"` in `environment:`, the property's default is `false`. Behind Cloudflare and Caddy the
  direct peer is the Caddy container, one and the same address for everyone, so the budget of 15
  requests/minute per IP turns out to be **one shared budget**: the sixteenth login attempt within
  a minute from anywhere blocks everyone.
- The `app` service on the box has no `healthcheck`, which the repository has.
- The fail-fast `${OBS_ALERT_EMAIL_TO:?…}` from HD-197 reached production **only** because the file
  was copied by hand. A merge would not have delivered it.

The fork is not about "how to copy files". It is about the **boundary**: what on the production machine
belongs to the repository, and what to the operator. The rollback story depends on that boundary:
`docs/release-checklist.md` prescribes nailing the image version directly in
`/opt/hamstrack/docker-compose.prod.yml` in an emergency, and that is right **only** while there is no sync.
Once a sync appears, the next deploy will overwrite the pin, and during an incident the operator will get two documents with
opposite answers.

## Decision

**Production configuration is delivered from the repository, by the commit sha of the build that is currently
being deployed. Secrets and the machine's own decisions stay on the machine.**

1. The deploy downloads the repository tree from codeload by `workflow_run.head_sha` and runs
   `ops/deploy/apply-config.sh` **from the downloaded tree**. The `--parameters` line in the workflow
   no longer contains either a list of paths or a compose invocation, and therefore needs no edits when
   the set of files changes.
2. The set of synced paths is the file `ops/deploy/synced-paths.txt` (`docker-compose.prod.yml`,
   `docker-compose.observability.yml`, `observability/`, `ops/`), not a line inside YAML.
3. **Never synced** are `/opt/hamstrack/.env` (the secrets and the machine's decisions) and `Caddyfile`
   (the production one differs from the repository one in the `trusted_proxies` block; it will be brought in by a separate ticket
   after the live file has been read). The script refuses to copy these paths,
   whatever is written in the manifest.
4. **A check before the swap:** `docker compose … config -q` is run against the downloaded copy with the
   box's real `.env`. Compose expands the interpolation before it creates or stops
   anything, so a new variable of the form `${VAR:?…}` that the machine does not have brings down
   the **deploy**, not the site.
5. **The image pin moves into `.env`:** `image: …/hamstrack:${APP_IMAGE_TAG:-latest}`. The pin
   survives the deploy not because the deploy agreed not to touch the file, but because it
   physically lies outside the synced set. "Don't forget to unpin" stops being a task for
   memory: the divergence is published as a metric and an alert.
6. **The sync places files and installs nothing** — no systemd units, no `/usr/local/bin`.
   The gap between placed and installed is measured by a separate divergence metric.

The general rule for the sake of which all this is done: **what must survive a deploy lives in
`.env`; what a synced file declares belongs to the repository.**

## Consequences

+ The config and the image cannot turn out to be from different trees: the sha is one and the same.
+ A tarball by sha is addressed by content — a moved tag, a force-push or a renaming of the
  repository cannot change what that URL returns. That is why we download by sha, never by
  a branch or a tag.
+ The deploy's shell is a file in the repository: it is visible in a diff, it can be run through `shellcheck` and
  executed on a laptop. Previously it was a quoted JSON string inside YAML.
+ There is exactly one rollback story and it is read in one document.
+ The rule "always both `-f` files" becomes code rather than a comment in two documents.
+ `docs/ops-prod-hardening.md` can stop describing the pipeline's behaviour in prose and simply
  name the file that is that behaviour.
− The deploy can now break the site with a bad config — previously it could only fail to fix it.
  Compensated by the check before the swap, by the `--dry-run` mode and by a backup of the replaced set in
  `/opt/hamstrack/.config-backup/<ts>/`.
− The first sync replaces the July file with the current one in one step: this is the widest blast radius
  in the whole history of this deploy, and it must be run by hand and watching the screen.
− A dependency appears on the availability of codeload and on the repository being public; a private
  repository will require a PAT, and until then every deploy will fail with an opaque 404.
− An edit to a synced file during an incident lives until the next deploy. This is a contract, not
  an accident, and the divergence alert reports it.

## Alternatives

- **Bake the config into a versioned artefact** (inside the image or as a separate release asset) —
  rejected: the load is circular (the compose file names the image tag, and getting it out of the image is
  possible only by picking an image), editing one config requires a full rebuild of the image
  (~12 minutes), and a self-hoster loses the ability to read the compose file on GitHub **before**
  the installation.
- **A `git` clone on the box and `git checkout <sha> -- <paths>`** — rejected: the working tree
  accumulates local edits, a checkout wipes them silently, a conflict can jam the deploy;
  plus the application's sources on the production machine. The only gain is `git status` as a
  divergence detector, which is cheaper to get with checksums.
- **Keep the copying manual, but make the deploy fail on a divergence** — rejected as a
  mechanism and adopted as a property: for six weeks it was exactly the human step that failed, and a louder
  human step is still a human step. The checking half of this option is built into the chosen one
  (the check before the swap + divergence metrics).
- **Sync `.env`** — rejected: it holds `JWT_SECRET`, the DB password and SMTP; secrets do not arrive from
  a public repository. It is precisely this refusal that makes the rollback story the only one.
