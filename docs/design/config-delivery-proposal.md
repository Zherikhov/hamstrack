# Configuration delivery — how the repository's config reaches production, and how a difference becomes visible (HD-199 + HD-122)

**Status:** proposal / design review. **Date:** 2026-08-26. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness).
**Closes:** **HD-122** (the decision: ship the sync, and what §3's text becomes) and **HD-199**
(the two shipped-but-not-running features, and the evidence that not deciding has a running cost).
**Related:** `.github/workflows/deploy.yml`, `docker-compose.prod.yml`,
`docker-compose.observability.yml`, `docs/ops-prod-hardening.md` (§3 deploy, §5 memory, §6 backups),
`docs/release-checklist.md` (rollback), `docs/design/production-backups-proposal.md` (§4.4 states the
gap this spec closes; §8 is the metric machinery reused here), `docs/observability.md`,
HD-152 (container memory limit), HD-75 (rate-limit XFF keying), HD-197 (`OBS_ALERT_EMAIL_TO`
fail-fast), HD-180 (limits on the other containers), HD-186 (load test), HD-187 (backups),
HD-189 (box resize / move observability off).

---

## 1. Problem & goal

Production runs configuration that nobody released. `.github/workflows/deploy.yml` sends exactly one
SSM command — `cd /opt/hamstrack && docker compose -f docker-compose.prod.yml -f
docker-compose.observability.yml pull && … up -d --remove-orphans && docker image prune -f` — and it
copies nothing. Every file in `/opt/hamstrack` is therefore whatever a human last put there, and on
2026-08-26 the owner measured what that means: `/opt/hamstrack/docker-compose.prod.yml` is dated
**11 July**, and `docker-compose.observability.yml` was dated **6 August** until it was hand-copied
that day. Six weeks of merged configuration changes are in the repository, in the release notes, and
not on the machine.

Two of them are features that shipped in 0.17.0 and are not running:

| Declared in the repository | On the box (measured 2026-08-26) | What the difference does |
|---|---|---|
| `mem_limit: ${APP_MEMORY_LIMIT:-1g}` on `app` (HD-152) | `docker inspect … .HostConfig.Memory` → **`0`**; the box's compose file has no `mem_limit` line | The image half of HD-152 did arrive (`/proc/1/cmdline` carries `-XX:MaxRAMPercentage=50.0`), so with **no** container limit the percentage is taken against **host** RAM: 1909 MB → a **~954 MB** heap ceiling on a box with ~330 MB available and no swap. Before 0.17.0 the JVM used its default ~25% ≈ 477 MB. **The release named "bound the container heap" doubled it in production.** `APP_MEMORY_LIMIT=1g` is present in `/opt/hamstrack/.env` and is read by nothing. |
| `RATE_LIMIT_TRUST_FORWARDED_FOR: "true"` in the `environment:` block | absent from the app container's environment; `app.rate-limit.trust-forwarded-for` therefore takes its property default, `false` | The per-IP auth budget keys on `request.getRemoteAddr()`, which behind Caddy is the Caddy container — the same address for every visitor. 15 requests/minute is **one budget for everybody**: sixteen login attempts a minute from anywhere lock out everyone. |
| `healthcheck` on `app` | absent | Nothing on the box distinguishes "the container is up" from "the application is serving", and the ordering the repository declares (Caddy waits for a healthy app) is not in force. |
| `docker-compose.observability.yml` with HD-197's `${OBS_ALERT_EMAIL_TO:?…}` guard | reached the box **only because the owner hand-copied the file** on 2026-08-26 | Merging HD-197 would not have delivered it. That is not a property of HD-197; it is a property of every configuration change this project merges. |

**A number in a file the deploy never reads is not a setting.** `docs/ops-prod-hardening.md` §5 asserted
the opposite about the memory limit, on the evidence that the variable was present in `.env` — and
§3's "config auto-sync" subsection has described, in the present tense, a deploy that was never
shipped. HD-122 is that section; HD-199 is its bill.

**Goal.** A configuration change that is merged and released reaches production without a human
remembering to copy it; a configuration change that the box's secrets cannot satisfy fails the
*deploy* rather than the site; a file on the box that differs from the released one is visible to a
person within the hour instead of at the next audit; and there is exactly **one** rollback story,
readable in one document, that stays true under the mechanism that now exists.

**Success looks like:** `docs/ops-prod-hardening.md` contains no prose description of pipeline
behaviour at all, and the two settings above are demonstrated to be in effect by commands whose
output is read, not by the fact that they are in the repository.

---

## 2. Scope

### 2.1 In scope

| # | Deliverable | Closes |
|---|---|---|
| 1 | **The delivery mechanism**: the deploy fetches the repo tree for the built commit and applies a repo-owned manifest of config paths before bringing the stack up | HD-122 (the decision), HD-199 (the cause) |
| 2 | **`ops/deploy/apply-config.sh`** — the shell that does it, in the repository, reviewable, with `--dry-run`, validate-before-swap, a backup of what it replaces, and every compose file in `pull`/`up` | HD-122 |
| 3 | **The rollback story, made single**: the image pin moves from a synced compose file to `APP_IMAGE_TAG` in the operator-owned `.env`; `docs/release-checklist.md` is rewritten accordingly | HD-122 (it is the tension that kept it open) |
| 4 | **Drift as a metric**: `ops/drift/` — three comparisons, four gauges through the node-exporter textfile collector, two alert rules | HD-199 |
| 5 | **The memory limit, sequenced** — what to measure before and after, and how it interacts with HD-189 | HD-199 |
| 6 | **A rate-limiter test that can fail** — the discriminating unit test, a compose contract seal, and a two-address production probe with its limits stated | HD-199 |
| 7 | **Documentation**: `docs/ops-prod-hardening.md` §3 replaced (not annotated), §5 corrected once measured, §6.3's hand-copy lines removed; `docs/release-checklist.md`, `docs/self-hosting.md`, `docs/observability.md`, `.env.prod.example` | HD-122 + HD-199 |

### 2.2 Out of scope, named so the mechanism is not read as covering them

- **Syncing `/opt/hamstrack/.env`.** It holds `JWT_SECRET`, the database password, SMTP credentials
  and the Grafana password; it exists in exactly one place and is swept up by the EBS snapshot layer
  (HD-187 §5.2). Secrets do not come from a public repository, and the boundary this spec draws is
  *the reason* the rollback story can be single (§5).
- **Syncing the `Caddyfile`** — for now, and with a named precondition (§6.3). The production copy
  diverges from the repository's by the hand-added Cloudflare `trusted_proxies` block
  (`docs/ops-prod-hardening.md` §2), and no one has read the live file. Replacing it blind takes the
  site down.
- **Installing anything.** The sync **places files**; it does not `install`, `chmod`, write to `/etc`,
  touch systemd, or restart anything outside the compose project. A deploy that rewrites systemd
  units on every merge is a blast radius nobody asked for. The cost of that boundary — a synced
  `ops/` copy that is newer than the installed one — is a drift scope rather than an omission (§8).
- **Provisioning the machine.** No Terraform, no Ansible, no configuration management. This ticket
  delivers *files that the compose project reads*, onto a box that already exists.
- **Deploying by immutable image tag** instead of `latest` (§18.1 Q3).
- **Changing how the application resolves a client address.** The flag that already exists is
  delivered here; a header-based strategy is a follow-up with a stated trigger (§10.3).
- **HD-180** (memory limits on postgres/caddy/observability) and **HD-189** (resize, or move
  observability off the box). §9 sequences against them; it does not do them.

### 2.3 Non-goals

This does not make deploys zero-downtime and does not add rolling deploys — `docs/release-checklist.md`
records that decision (HD-93) and nothing here revisits it. It does not make the box self-healing: a
bad configuration still needs a person. It reduces the number of steps a human must remember; it does
not remove the human.

---

## 3. Actors & permissions

**No application actor.** This ticket adds no endpoint, no role, no permission, no workspace-scoped
resource and no database access — §12 says so in the terms `tenancy-reviewer` and `api-docs-sync`
read.

| Principal | What it may do | What it may not |
|---|---|---|
| GitHub Actions, via the `hamstrack-deploy` IAM user | `ssm:SendCommand` against one instance id with `AWS-RunShellScript`, and `ssm:GetCommandInvocation` | anything else in the account |
| `root` on the instance (what the SSM document runs as) | fetch the released tree, replace the manifest paths, run `docker compose` | read or write `.env` beyond what compose already does |
| The owner | everything above by hand, plus AWS account operations | — |

**This widens no privilege, and it is worth saying why**, because "the deploy now executes a script
from the repository" reads like a new risk. The SSM document already runs arbitrary shell as `root`,
and anyone who can merge to `main` can already run arbitrary code on that box by way of the image.
What changes is **who reviews that shell**: a quoted JSON array inside YAML inside a `run:` block is
not reviewable, and the file list buried in it cannot be changed without editing it. A script in
`ops/deploy/` is diffable, `shellcheck`-able and testable on a laptop.

One property is load-bearing and is the reason the fetch is by **commit sha**: a codeload tarball
addressed by sha is content-addressed — a moved tag, a force-push or a repository rename cannot change
what that URL returns. Never fetch config by branch or by tag.

---

## 4. Decision 1 — how configuration reaches the box

**Recommended: A — the deploy fetches the repo tree for the commit that was built, and applies a
repo-owned manifest of paths, with E (drift detection) as a permanent complement.**

| Option | Rollback story | An operator editing a file mid-incident | A self-hoster who is not us | Verdict |
|---|---|---|---|---|
| **A. Codeload tarball at `head_sha`, applied by a repo-owned script** | Breaks today's advice (a pin in a synced file is wiped) — **so the pin moves to `.env`**, §5. Config rollback = re-deploy the reverted commit, or the on-box backup copy. | The edit takes effect immediately and survives until the next deploy; the drift alert fires within ~30–90 min, which is the un-forget mechanism. | Unaffected by the pipeline. The *script* is usable from a git clone (`git pull && sudo ops/deploy/apply-config.sh . /opt/hamstrack`), because it knows nothing about AWS. | **Chosen** |
| B. Bake config into a versioned artifact (inside the image, or an OCI/release asset) | Same as A. | Same as A, plus the file they edit was extracted from an artifact, so "where does this come from" has a longer answer. | Worse: the compose file stops being a thing you can read on GitHub before you install. | Reject — bootstrapping is circular (the compose file names the image tag; extracting it needs `docker create` + `docker cp` from an image you selected using the file), and a config-only fix would need a full image build (~12 min) to ship. |
| C. `git` checkout on the box (`git fetch && git checkout <sha> -- <paths>`) | Same as A. | Worse: a working tree accumulates local edits; a checkout overwrites them silently and a conflicting state can wedge the deploy. | Neutral. | Reject — it puts a full clone of the application source on the production box, needs `git` and credentials there, and buys only `git status` as drift detection, which §8 gets for less. |
| D. Keep it manual, but make the deploy **refuse** when the box's config differs from the released one | Unchanged — today's checklist stays correct as written. | Unchanged. | Unaffected. | Reject as the mechanism, **adopt as a property.** It makes the failure loud and leaves every config change a human step; this release alone has several tickets editing compose files, and what failed for six weeks *was* the human step. Its verification half is folded into A (§7 validate, §8 drift). |
| E. Drift detection | — | It is what tells you the edit is still there. | The script is generic; the timer is optional. | **Adopt alongside A** (§8). |

**Why A specifically.**

1. **Atomicity by construction.** The tarball is fetched by `workflow_run.head_sha`, the same commit
   whose build produced the image the deploy is about to pull. The config and the image cannot be
   from different trees, and this needs no coordination anywhere.
2. **The fetch is content-addressed.** A sha names bytes. Nothing else in the delivery chain has that
   property; a tag does not.
3. **The failure mode is "nothing happened".** `curl -fsSL … | tar xz` into a staging directory, then
   validate, then swap. Every way this can fail before the swap leaves the box exactly as it was, and
   the deploy goes red — which is the shape you want from a mechanism that runs unattended.
4. **It needs nothing new on the machine.** `curl` and `tar` are there; the repository is public, so
   codeload needs no token.
5. **The shell is in the repository, so the workflow's command becomes stable.** The inline
   `--parameters` string never encodes the path list again, which is exactly the property that lets
   `docs/ops-prod-hardening.md` stop describing it (§14).

**The one real cost of A, stated plainly.** A deploy can now break the site in a way it previously
could not: a bad compose change reaches production automatically. Today's mechanism has a human
between a merge and the machine. That human has demonstrably been the failure, not the safeguard —
but the honest accounting is that this ticket trades "config silently never arrives" for "config
arrives, including when it is wrong", and the mitigations are the validate step (§7), the dry run
before the first sync (§15), and the fact that `.env` — where every secret and every machine-local
decision lives — is still not something a merge can touch.

---

## 5. Decision 2 — the rollback story, made single

**This is the tension that kept HD-122 open, and it must be resolved before the mechanism ships, not
after.** `docs/release-checklist.md` currently tells the owner that an emergency rollback is: pin
`app.image` in `/opt/hamstrack/docker-compose.prod.yml`, and remember to un-pin it or the next deploy
silently does nothing. That advice is correct **only** under today's no-sync behaviour. Ship a sync
and the next deploy wipes the pin — an operator following two documents would get opposite outcomes
at the worst possible moment.

**The rule that makes it one story: what must survive a deploy lives in `.env`; what a synced file
declares belongs to the repository.** Applied to the pin:

```yaml
# docker-compose.prod.yml
image: ghcr.io/${GITHUB_OWNER}/hamstrack:${APP_IMAGE_TAG:-latest}
```

- **Rollback becomes:** set `APP_IMAGE_TAG=0.17.0` in `/opt/hamstrack/.env`, `docker compose … up -d`.
  It survives every subsequent deploy **by construction** rather than by the deploy declining to touch
  a file. A deploy while pinned re-pulls and re-runs the pinned tag: the same outcome as today, minus
  the surprise.
- **The un-pin reminder stops being a memory task.** The drift check publishes
  `hamstrack_deploy_image_pinned{tag="…"}` when `APP_IMAGE_TAG` is set to anything other than `latest`,
  and a `DeployImagePinned` rule (warning, `for: 6h`) says so. A pin still in place six hours later is
  either forgotten or has become the intended version, and both deserve a sentence to a person.
- **`docs/release-checklist.md`'s "Rolling back" section is rewritten, and the sentence about editing
  the compose file is deleted rather than amended.** An operator mid-incident must not meet two
  mechanisms, and a struck-through one is still one.
- **The self-hoster gains from the same change.** `docker-compose.prod.yml` currently tells them to
  pin by editing the file — the one thing that a `git pull` or a re-download undoes. A variable in
  `.env` is the shape the rest of their configuration already has.

**And the residual, stated so it is not discovered:** editing a *synced* file during an incident still
works and is still sometimes right (a hot patch to a healthcheck interval at 3 a.m.). It survives
until the next deploy, and the drift alert will fire. That is the documented contract, in one place:
`.env` for durable, the file for temporary, a commit for permanent.

---

## 6. Decision 3 — what is synced, what never is, and how that list is kept

### 6.1 The manifest is a file, not a line in a workflow

`ops/deploy/synced-paths.txt`:

```
docker-compose.prod.yml
docker-compose.observability.yml
observability/
ops/
```

Adding a path is a one-line change to a file with a diff and a reviewer. That is the whole point: the
previous design put this list inside a quoted JSON array inside YAML, where adding `ops/` — which
`docs/design/production-backups-proposal.md` §4.4 recommends and which this spec adopts — would have
been an edit to an unreviewable string.

**Directories are replaced wholesale** (staged copy, then `rm -rf` + `mv`). A file an operator drops
into `/opt/hamstrack/observability/` disappears at the next deploy. Intended, and documented where an
operator meets it.

### 6.2 Never synced, and why — kept in the same file as comments

- **`.env`** — secrets, and the machine's own decisions (`APP_MEMORY_LIMIT`, `APP_IMAGE_TAG`,
  `SITE_ADDRESS`). §5 depends on this.
- **`Caddyfile`** — until §6.3's precondition is met.
- **Anything outside the target directory**, and anything matching `.env*`, whatever the manifest says.
  The script refuses these as a hard error rather than trusting the manifest, because the manifest is
  the thing a careless edit would change.

### 6.3 The `Caddyfile`, deferred with a precondition rather than left vague

The production copy diverges from the repository's by a hand-added global `trusted_proxies` block, and
nobody has read the live file (no AWS credentials on this machine). It stays operator-owned in this
ticket. The precondition for bringing it in — a follow-up ticket, because §10.3's complete
rate-limiter fix depends on it:

1. The owner pastes `/opt/hamstrack/Caddyfile` into the ticket.
2. The repository's copy absorbs it with the site-specific part behind a variable
   (`{$CADDY_TRUSTED_PROXIES}`), so the two files stop being different files.
3. CI validates the repo copy (`caddy validate --adapter caddyfile`), and `apply-config.sh` validates
   it on the box before the swap.
4. Only then does it join the manifest.

### 6.4 `ops/` is synced and is still not installed

The sync places `/opt/hamstrack/ops/`. It does not install `hamstrack-backup.sh` to
`/usr/local/bin`, does not write systemd units, does not `daemon-reload`. **A changed script in the
repository therefore does not change what runs on a schedule until an operator runs the install
step.** Two consequences, both deliberate:

- `docs/ops-prod-hardening.md` §6.3's install commands lose their `curl … codeload` line and their two
  `cp` lines: the files are already on the box, so the step becomes `install -m 0750
  /opt/hamstrack/ops/backup/hamstrack-backup.sh /usr/local/bin/hamstrack-backup` and friends.
- The gap between "synced" and "installed" is a drift scope with its own metric (§8), because a gap
  that nothing measures is how this ticket happened in the first place.

---

## 7. The mechanism

### 7.1 What the workflow sends — and why it never needs editing again

```yaml
--parameters 'commands=[
  "set -eu",
  "cd /opt/hamstrack",
  "rm -rf .sync && mkdir -p .sync",
  "curl -fsSL https://codeload.github.com/${{ github.repository }}/tar.gz/${{ github.event.workflow_run.head_sha }} | tar xz -C .sync --strip-components=1",
  "bash .sync/ops/deploy/apply-config.sh .sync /opt/hamstrack ${{ github.event.workflow_run.head_sha }}",
  "rm -rf .sync"]'
```

- `--strip-components=1` flattens the tarball's top directory. **Never hardcode its name** — it is
  `<repo>-<sha>/` and this repository has been renamed once already.
- The staging directory is `/opt/hamstrack/.sync`, not `/tmp`: same filesystem, so the swap can be a
  `mv`.
- The script that applies the config is the one from the tree being applied, so a change to the
  delivery mechanism ships like any other change.

### 7.2 `ops/deploy/apply-config.sh <source-dir> [target-dir] [sha] [--dry-run] [--allow-pinned|--adopt-pin]`

`set -euo pipefail`; `flock` on `<target>/.deploy.lock` (the GitHub concurrency group serialises the
pipeline, and does nothing about an operator running it by hand at the same moment). Steps, in order,
each one a hard exit on failure:

1. **Refuse** without a source directory containing `ops/deploy/synced-paths.txt`, and refuse any
   manifest entry that escapes the target or matches the never-sync list (§6.2).
2. **Validate against the box's real secrets, before touching anything.**
   ```
   docker compose --project-directory <target> --env-file <target>/.env \
     -f <src>/<each COMPOSE_FILES entry present in the release tree> config -q
   ```
   Compose resolves interpolation before it creates, changes or stops anything, so a new `${VAR:?…}`
   the box cannot satisfy **fails the deploy and leaves the running stack alone**. This is precisely
   the HD-197 case: a fail-fast guard that merged and could not reach production now stops the deploy
   that would have installed it, and names the variable. `COMPOSE_FILES` defaults to the bundled two
   and **skips a listed file the release tree does not carry**, so a deployment without the
   observability stack is not blocked here by `${GF_SECURITY_ADMIN_PASSWORD:?…}`, a variable belonging
   to a stack it does not run.
2b. **Refuse to sync onto an image pin that has MOVED.** `APP_IMAGE_TAG` in the box's `.env` pins what
   runs, and nothing pins the configuration beside it — so an unattended deploy would otherwise place
   a newer tree on the image an incident deliberately held back, and re-apply the configuration change
   that incident may have been rolling back. What is refused is the pin **moving**, not the pin
   existing: `docs/self-hosting.md` tells every self-hoster to pin, so "pinned" is a steady state for
   most of this script's audience, and a check that refused it outright would answer that audience "no"
   for ever, behind a flag they retype every time. `.deployed-image-tag` is stamped on every run, which
   makes the two states distinguishable — **unchanged since the last apply → proceed** (the image is the
   one this configuration has been living beside); **changed since the last apply → refuse** (the
   rollback case); **no stamp yet → refuse** (nothing to compare, and a first sync deserves a human).
   **Two overrides, and they are not interchangeable.** `--adopt-pin` proceeds **and re-stamps**, so a
   deliberate version bump costs the flag once rather than for ever. `--allow-pinned` proceeds and
   leaves `.deployed-image-tag` **untouched**, so the disagreement survives the run and the next
   unattended deploy refuses again. The ordering that forces the split: pin for an incident, CI goes
   red as designed, and six hours later an urgent config fix — or the `DeployImagePinned` alert — sends
   the operator to a hand run; if that run re-stamped, the *next merge* would read the pin as
   "unmoved", call it a steady-state re-apply, and place the newest configuration tree onto the
   deliberately held-back image with no flag, no refusal and a log line saying everything is normal.
   That is precisely the case this step exists to prevent, reached by fatigue instead of by decision,
   so the override that an operator reaches for mid-incident is the one that cannot disarm CI.
   The tag is stamped next to the sha, so the deployed state is readable as the pair it is. Each
   refusal names an action for **both** of its readers — the operator who bumped a version and the one
   who rolled production back — because a refusal that prescribes only "un-pin" is advice the first of
   them must not take, and one that prescribes only "adopt" is advice the second must not take. That
   applies to the **no-stamp** branch as much as to the moved one: `unstamped` is the state of *every*
   box the first time this ships, so if production happens to be pinned for an incident that day, its
   message must say **do not adopt** rather than hand the reader an adoption.

   **The pin is read the way Compose reads it, including where Compose reads it from.** `.env` is read
   and never sourced, with the `export ` prefix and the unquoted ` # comment` tail Compose honours —
   a line Compose honours and this parse misses reads as unset, which is `latest`, which is "not
   pinned", a fail-**open** miss in the one check whose job is to refuse. And Compose gives the
   **process environment precedence** over `--env-file` (verified: `APP_IMAGE_TAG=9.9.9 docker compose
   --env-file .env config` resolves `9.9.9` while `.env` says otherwise), while this script's own
   header tells operators to run it under `sudo -E` for `COMPOSE_FILES` — so an exported pin would be
   deployed by step 7, misreported by the stamp, and invisible to the guard. The environment is folded
   in with Compose's precedence and the run is **refused** when it is the environment that decides:
   an exported pin is not a pin, because it lasts one shell, while `.env` is the one file no deploy
   replaces.
3. **`--dry-run` stops here** and prints `diff -ru` of every manifest path, box versus release. This
   is what makes the *first* sync — six weeks of changes in one step — a reviewed change rather than a
   surprise (§15). A moved pin is reported here as a warning rather than a refusal: a dry run changes
   nothing, and refusing to show the diff would only hide it. The warning is written for the flags it
   was actually given — `--dry-run --allow-pinned` and `--dry-run --adopt-pin` each say what the real run
   will *do* (and they differ: one re-stamps, one does not), not what it would
   refuse without a flag the reader has already supplied. What a dry run does *not* excuse is a bad
   INVOCATION rather than a state of the box — today, an exported `APP_IMAGE_TAG` that disagrees with
   `.env` (step 2b) — which is refused before the diff, because the diff would describe a run nobody
   should make. So a **non-zero `--dry-run` is always about the command that was typed**, never a
   report about the box.
4. **Back up** the current manifest paths to `<target>/.config-backup/<UTC-timestamp>/`, keeping the
   last 5. Skipped when nothing differs, so a re-deploy of the same sha does not mint empty backups.
5. **Apply**: stage each path beside its destination and `mv` it into place, so an interrupted command
   cannot leave a half-written file. (Across *several* paths it is still not one transaction — the
   residual is in §11, and the remedy is re-running the deploy.)
6. **Stamp**: write `<target>/.deployed-sha`, `.deployed-at`, `.deployed-image-tag` and
   `.deployed-manifest.sha256` (a checksum line per synced file). §8 is built on that last file; the
   tag is stamped because the sha alone is half of the deployed state — the tag is mutable, so
   `.deployed-sha` says which tree the files came from and says nothing about what runs.
7. **Bring the stack up** with **every** `-f` file in `pull` and in `up -d --remove-orphans`. This is
   the line that today lives as a warning comment in two documents; here it is code, and the class of
   accident it prevents (`--remove-orphans` with one file deletes the observability containers) stops
   depending on whoever types the command.
7b. **Restart the services whose configuration is bind-mounted**, when a synced path they mount
   changed. `up -d` compares the service *definition*, and a bind mount's spec does not change when
   the file behind it does: the container keeps the **deleted inode** of the config that was just
   replaced, compose correctly does nothing, and **both** drift scopes read 0 — the definition really
   does match, and the file on disk really is the released one. Every check agrees while the merged
   alert rule is not running: HD-199's own failure class, one layer down. Grafana was the case
   documented in prose; Prometheus, Loki and Alloy mount `./observability/…` exactly the same way, so
   all four are restarted, and only when that path is among the ones that changed.
8. `docker image prune -f`.
9. **Publish the drift metrics immediately** (§8), so the freshest reading is always the one taken at
   the moment of a deploy.

**Idempotent**: re-running on the same sha replaces identical files, writes the same stamp, and
`up -d` is a no-op when no service definition changed.

**Portable by construction**: the script takes a source directory and a target directory. It contains
no AWS, no SSM, no GitHub. The fetching is the caller's job — a `curl` in the workflow, a `git pull`
for a self-hoster. Portability is in its *assumptions* as well as its arguments: `COMPOSE_FILES` is
what keeps "both files are required" from being one, and a pinned `APP_IMAGE_TAG` — which is what
`docs/self-hosting.md` tells a self-hoster to run with — is a steady state the script proceeds on
rather than a wall, with `--allow-pinned` and `--adopt-pin` reserved for the pin that has actually
moved.

---

## 8. Decision 4 — drift becomes observable, and it belongs in **this** ticket

**Decision: the minimal version ships here.** Not because drift detection is glamorous, but because it
is the only thing that proves the delivery worked and keeps working, and "the box silently differs
from the repo" is the exact failure that cost two shipped features. The machinery already exists —
node-exporter's textfile collector, Prometheus, provisioned Grafana rules and an email contact point,
all installed for HD-187 — so the cost is a script and two rules, not a subsystem.

### 8.1 Three comparisons, and together they are the property

| Scope | Comparison | The failure it catches |
|---|---|---|
| `files` | re-hash every synced file and check against `.deployed-manifest.sha256`; also detect files added to or removed from a synced directory | somebody edited (or added to) a synced file after the deploy — including the incident edit that must be un-done |
| `containers` | each service's `com.docker.compose.config-hash` label on the running container versus `docker compose … config --hash '*'` | the file is right and the container was never recreated — the shape of "consequence 1" if a file had been copied without `up -d` |
| `installed-ops` | `sha256sum` of `/opt/hamstrack/ops/**` versus the copies installed under `/usr/local/bin` and `/etc/systemd/system` | the sync cannot install, so the check must be able to say that it hasn't (§6.4) |

Checksums rather than a re-download: no network dependency, no codeload availability in the hourly
path, and it answers the question that is actually asked ("has this box changed since it was
deployed"). *What the deploy applied* is answered by `.deployed-sha`, which is published as a label.

### 8.2 The metrics

```
hamstrack_config_drift{scope="files"} 0
hamstrack_config_drift{scope="containers"} 0
hamstrack_config_drift{scope="installed-ops"} 0
hamstrack_config_deployed_info{sha="a1b2c3d"} 1
hamstrack_config_check_timestamp_seconds 1756300000
hamstrack_deploy_image_pinned{tag="latest"} 0
```

`scope` is a closed enum. `sha` and `tag` change on a deploy — a handful of new series a week against
a 15-day retention, which is the reasoning that has to be written down in a project that otherwise
forbids unbounded labels. The specific files that differ go to the journal and to the script's
stdout, never into a label.

### 8.3 Two rules, in `observability/grafana/provisioning/alerting/rules.yml`

| Rule | Query | Fires | `for` | Severity |
|---|---|---|---|---|
| `ConfigDrift` | `hamstrack_config_drift` | `> 0`, **unaggregated** so the instance names the `scope` | 30m | warning |
| `DeployImagePinned` | `hamstrack_deploy_image_pinned` | `> 0`, **unaggregated** so the instance names the `tag` | 6h | warning |

**The queries are deliberately not wrapped in `max()`** — an earlier draft of this table wrote
`max(…)` in the *Query* column while the *Fires* column said unaggregated, and the rules that shipped
follow the second. `max()` drops every label: `ConfigDrift` would then render the `installed-ops`
remedy for a `files` drift (the summary branches on `$labels.scope`), and `DeployImagePinned` would
print "pinned to image tag " with nothing after it. An annotation is rendered per instance, so it
must describe exactly one — which means the query must keep the label that says which.

Both route to the existing email contact point. **Warning, not critical**: a drifted box is usually a
deliberate incident edit, and the alert's job is to make sure it is un-done, not to wake anybody.

### 8.4 Where it runs, and the honest gap

`ops/drift/hamstrack-config-drift.sh`, plus a `.service`/`.timer` pair running hourly with a small
`MemoryMax`, following ADR-0011 (host systemd units, not compose services — the reasoning about idle
memory on this box is unchanged). `apply-config.sh` calls the same script at the end of every deploy,
so the metric exists even before the timer is installed.

**The gap, stated because `noDataState: OK` makes an uninstalled mechanism silent:** until the timer
is installed by the owner (§15), the metric is only as fresh as the last deploy, and an edit made
afterwards is invisible until the next one. `hamstrack_config_check_timestamp_seconds` is what
distinguishes the two states, and it is why it exists.

### 8.5 What is deliberately deferred

Drift of **operator-owned** files (`.env`, `Caddyfile`) — they have no released baseline to compare
against, by construction, so there is nothing to diff until §6.3 lands. Named here so the gap is a
decision.

---

## 9. Decision 5 — sequencing the memory limit

Applying `mem_limit: 1g` **halves** a ceiling the box has been running with. This is not a free
correction and must not ride in as a side effect of a delivery ticket.

### 9.1 The arithmetic, and what is and is not dangerous about it

| | Heap ceiling | Container ceiling |
|---|---|---|
| Before 0.17.0 | default JVM ergonomics, ~25% of 1909 MB ≈ **477 MB** | none |
| Today (image shipped, compose did not) | 50% of **host** 1909 MB ≈ **954 MB** | none |
| After delivery, `APP_MEMORY_LIMIT=1g` | 50% of 1024 MB = **512 MB** | 1024 MB |

Today's 954 MB is not a capacity anyone chose; it is an artifact of a limit that never arrived. It is
also more than the machine has: ~330 MB available, and at the time of writing no swap. So the current
state is a *latent host*
OOM in which the kernel picks the victim — and the victim may be `postgres`. Applying the limit makes
the app the bounded, predictable one (exit `137`, no stack trace), which is an improvement in blast
radius and a reduction in headroom at the same time. Both halves are true and the sequencing exists to
keep them apart.

**Something else on this box is already over-committed, and the deploy will not change it:** the
observability stack's declared ceilings sum to more than half the machine. Recompute rather than trust
the figure here — `grep mem_limit docker-compose.observability.yml` — and note that those ceilings plus
a 1 GB app exceed 1909 MB before `postgres` has taken anything. It works today only because those
containers do not use their allowance. That is HD-189's argument, and it is why §9.4 prefers HD-189
first.

> **Measured 2026-08-28 (HD-189). Two of §9.2's inputs now exist, and they change the risk of this
> cut without changing its sequencing.** Recorded here so the decision is made against observations
> rather than against this section's arithmetic; full set in `docs/ops-prod-hardening.md` §5.
>
> - **7-day peak heap used: 387 MB.** That is *below* the 512 MB this cut lands on, so the halving
>   removes headroom the workload has not been using. It is not a guarantee — the peak was taken at
>   idle, with the caveat that carries stated once in `docs/ops-prod-hardening.md` §5.4 rather than
>   re-derived here — but it is the difference between cutting a ceiling blind and cutting one with a
>   floor underneath it.
> - **App container RSS: 597 MiB**, against the 1024 MB the limit would impose.
> - **The heap ceiling read back off the box is 956 MB** (`jvm_memory_max_bytes`, Old Gen
>   `1002438656`), which confirms the ~954 MB row above from the running JVM rather than from
>   arithmetic.
> - **Swap now exists** — 1023 MB at `vm.swappiness=10`, persisted in `/etc/fstab` and
>   `/etc/sysctl.d/99-hamstrack-swap.conf`. It softens the "latent host OOM" above into a slowdown
>   first, and it is a buffer rather than a fix: it cost 12 points of root filesystem (67% → 79%).
> - **The host side now has alert rules** — `HostMemoryLow`, `HostSwapInUse` and
>   `HostKernelOOMKill` in `observability/grafana/provisioning/alerting/rules.yml` — so the
>   48-hour watch in §9.3 has something watching the *host*, which `JVMHeapPressure`
>   structurally cannot see. The third one matters most for this cut: if a 512 MB heap turns out
>   to be too small, the container is killed at its limit and that is the rule that says so.
>
> The one thing that has **not** been measured is still the one that matters: none of this was taken
> under load (HD-186).

### 9.2 Measure before — from data that already exists, no new tooling

Prometheus scrapes the app as job `hamstrack-app` (the `JvmHeapPressure` rule already queries it), so
the numbers are a Grafana Explore query away:

```promql
max_over_time( sum(jvm_memory_used_bytes{job="hamstrack-app",area="heap"})[7d:5m] )
max_over_time( sum(jvm_memory_used_bytes{job="hamstrack-app",area="nonheap"})[7d:5m] )
max_over_time( sum(jvm_memory_committed_bytes{job="hamstrack-app",area="heap"})[7d:5m] )
max_over_time( jvm_gc_pause_seconds_max{job="hamstrack-app"}[7d:5m] )
```

and, on the box, the number Prometheus does not have — the container's resident set:

```bash
docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.MemPerc}}'
```

**Record the window with the numbers.** Retention is 15 days and the stack has not been running for
long; a peak over three days is a three-day peak, and saying so is the difference between a
measurement and a declaration.

**Do not reach for `systemctl show -p MemoryPeak`** — measured 2026-08-26, it does not exist on this
box: the property arrived in systemd 253 and Amazon Linux 2023 ships 252, and `show` returns 0 while
silently omitting the property. For a *container* the number comes from `docker stats` or the cgroup;
for a *unit*, from the cgroup while it runs (`docs/ops-prod-hardening.md` §6.3 step f).

### 9.3 Choose, then apply — with the lever named in advance

`APP_MEMORY_LIMIT` is written **explicitly** in `/opt/hamstrack/.env` **before HD-199 is merged**,
because the first synced deploy is what makes `mem_limit` real — and, since `deploy.yml` fires on a
green Build of `main`, that deploy is the merge itself (§15). An absent line is not a chosen value.

- If observed heap peak ≤ ~350 MB and container RSS ≤ ~700 MB: **`1g`**, which is the repository
  default and leaves a 512 MB heap with meaningful headroom.
- If either is higher: the larger of `1g` and (RSS peak × 1.5) rounded up — and if that number is
  above ~1200m on the current box, that is HD-189's answer, not a value to set (§9.4).
- To preserve today's ~954 MB heap exactly you would need a container limit near the whole machine.
  That is not a configuration, it is the absence of one.

**The rollback lever is `.env` plus `docker compose … up -d`** — no repository change, no pipeline, no
merge, usable at 3 a.m. by the person watching. It is the same lever the image pin uses, and that
symmetry is deliberate: everything a person may need to change under pressure lives in one file that
no deploy touches.

**Watch for 48 h after applying**: the app container's exit code (`137` is the kernel, not an
`OutOfMemoryError`, and appears in no application log), `jvm_gc_pause_seconds_max`, and the existing
`HighLatency` rule. Nothing else is a symptom of this change.

### 9.4 Against HD-189, decided

- **Preferred order: HD-189 first**, then the first synced deploy, then re-derive `APP_MEMORY_LIMIT`
  from post-change numbers. On a resized box, or one that no longer hosts the observability stack,
  the limit is a different number and measuring twice to arrive at it once is wasted.
- **If HD-199 ships first — and it should, because it is the delivery of two already-merged
  features** — then: measure (§9.2), set the value explicitly, deploy deliberately (§15), watch. When
  HD-189 lands afterwards, re-derive; the explicit line is what makes the resize *not* silently leave
  the app on a value chosen for a smaller machine.
- **Do not apply the limit and the resize in one step.** Two changes, one symptom, no way to tell
  which. That is the whole of the sequencing rule.

Once applied and measured, `docs/ops-prod-hardening.md` §5's correction blockquote is replaced by the
measured numbers — a section that says what was read off the box, with the date it was read.

---

## 10. Decision 6 — a rate-limiter claim that can fail

### 10.1 Why the old verification passed

`PLAN.md` records a 2026-07-14 verification: "a burst through CF trips 429 exactly at the 16th
request." That is true under per-IP keying and equally true under one shared key — with one bucket for
everybody, a burst from one client still trips at 16. **It was a real test of the wrong proposition**,
and the shape recurs: a test that passes under both configurations distinguishes nothing.

The property that distinguishes them is about *two* clients: **two distinct client addresses must
receive two budgets.**

### 10.2 The claim is a conjunction of three, and each needs its own artifact

No single test proves it end to end, so the doc names all three and none of them is allowed to stand
in for the others.

**(a) The code maps an address to a key — a repository test that fails under the wrong mapping.**
Two tests exist today (`AuthRateLimitForwardedForTrustedTest`,
`AuthRateLimitForwardedForUntrustedTest`) and both assert that requests **share** a budget. The
discriminating one is missing:

- **New:** `AuthRateLimitForwardedForTrustedTest#distinctRightmostXffGetIndependentBudgets` — with
  `app.rate-limit.trust-forwarded-for=true` and a budget of 5, exhaust the budget for rightmost
  `198.51.100.4` (6th request → 429), then send one request with rightmost `198.51.100.9` from the
  same socket and require **401, not 429**. It fails the moment the filter keys on anything shared
  across clients — and *only* then: keying on the socket instead fails **both** tests, because the
  sibling varies the socket deliberately, so that is not the mutation to try when checking that this
  one discriminates.
- **New:** a compose contract seal — `ProdComposeContractTest` reads `docker-compose.prod.yml` from
  the repository root and asserts the security-load-bearing declarations that have no other test:
  `RATE_LIMIT_TRUST_FORWARDED_FOR` declared in the `environment:` block as either `"true"` or
  `"${RATE_LIMIT_TRUST_FORWARDED_FOR:-true}"` (the deployment's opinion is what must survive, and the
  default form leaves the operator the override a literal would deny them), a `mem_limit` on `app`, a
  `healthcheck` on `app`, and the image expressed as `${APP_IMAGE_TAG:-latest}`. Parse with SnakeYAML
  (already on the classpath via Boot) rather than by regex. Its failure message is the propagation
  checklist — the same idiom that seals the throttled path set. This does not prove the box has the
  setting; it proves the line cannot be deleted quietly, which is a different and also necessary
  thing.

**(b) The header contains what the code assumes — and this is the finding that delivery alone may not
fix.** Caddy sets `X-Forwarded-For` to its immediate peer, appending to the incoming value only when
that peer is a trusted proxy. Behind Cloudflare, Caddy's immediate peer is a Cloudflare edge node.
So, from Caddy's documented behaviour:

- if the box's Caddyfile trusts Cloudflare's ranges, the app receives `client, cf-edge`;
- if it does not, Caddy discards the incoming header and the app receives `cf-edge`.

**Under both, the rightmost entry is a Cloudflare address, not the visitor's.** If that holds on this
box, flipping the flag turns one global budget into one budget *per Cloudflare edge node* — a large
improvement (the lock-out-everyone failure is closed either way) and still not per-client.

**This is a prediction from documented behaviour, not a measurement**, because nobody has read the
live file. The measurement is one command in §15, and it decides between two outcomes with different
follow-ups.

**(c) The setting is in effect on the running container.** Covered continuously by §8's `containers`
scope, and once by a probe:

> **The two-address probe.** From address A (the operator's machine), send 16 `POST /api/auth/login`
> to `https://hamstrack.com` within one minute with an address that belongs to no account — the 16th
> must answer 429. Immediately, from address B, send one identical request: it must answer **401**.
> A 429 from B means the budget is shared and the delivery has not taken effect.
>
> **Address B is the instance itself**, over an SSM session (`curl -s -o /dev/null -w '%{http_code}'
> https://hamstrack.com/api/auth/login …`): its egress address is stable, distinct from the
> operator's, and always available — no second device, no VPN, no coordination.
>
> **What it proves and what it does not.** It proves the budget is not global. It does **not**
> distinguish per-edge from per-client — two sources that far apart reach different Cloudflare edges
> either way. Proving per-client needs (b)'s measurement, not a bigger probe.
>
> Use a nonexistent email so no real account's failure counter is touched; the per-IP window resets
> after a minute.

**Where it lives so it runs again:** a new `docs/ops-prod-hardening.md` §7, *Verifying the deployed
configuration* — built like §6.4, a short list of commands whose *refusal or exact status code* is the
point — and `docs/release-checklist.md` points at it for any release that touches the edge path, the
rate limiter, or the compose files.

### 10.3 The complete fix, and its trigger

If (b)'s measurement shows the rightmost entry is a Cloudflare address, the durable answer is to stop
parsing a chain in the application and let the edge state the client:

- Caddy: `reverse_proxy app:8080 { header_up X-Hamstrack-Client-IP {client_ip} }`. `header_up`
  **sets** the header, so a value from the internet can never survive it, and `{client_ip}` is Caddy's
  own trusted-proxy resolution — correct at any number of hops.
- Application: `app.rate-limit.client-ip-header` (env `RATE_LIMIT_CLIENT_IP_HEADER`), default empty =
  today's behaviour; when set, key on that header and fall back to the peer address when absent.

This is a **follow-up ticket, not this one**, for two reasons: it needs the Caddyfile to be
repo-owned (§6.3), and it is a change to application code where this ticket is a change to delivery.
Its trigger is the measurement, and both possible answers are actionable.

---

## 11. Edge cases & failure modes

1. **codeload unreachable, DNS failure, GitHub outage** → `curl -f` fails, the command chain stops
   before anything is replaced, the deploy goes red. The stack keeps running the config it had.
2. **The repository is made private** → codeload answers 404 and every deploy fails opaquely. Remedy
   (one line, in the runbook): a PAT in the URL. Named because the failure gives no hint.
3. **The tarball's top-level directory name** is `<repo>-<sha>`; `--strip-components=1` is what makes
   that irrelevant. Never match on the prefix — the repository has been renamed once.
4. **A new `${VAR:?…}` the box's `.env` cannot satisfy** → caught by the validate step; the deploy is
   red, the stack untouched, the message names the variable. This is HD-197's failure, now covered by
   construction rather than by a runbook paragraph.
5. **Compose refusing the whole invocation** (a `${…:?}` failure blocks `down`, `ps` and `logs` too,
   not just `up`) is unchanged and still documented in §4 of the runbook — but it now surfaces at
   deploy time, on a staging copy, instead of on the box's live files.
6. **The SSM command times out mid-apply.** Each path is staged and `mv`d, so no single file is
   half-written; across several paths the set is not one transaction. Residual: a mixed set. Remedy:
   re-run the deploy (idempotent), or restore from `.config-backup/<ts>/`. Detected by the `files`
   drift scope, which will disagree with `.deployed-manifest.sha256`. The one window that was worse
   than "mixed" is closed by a scoped `trap`: between a directory's two renames the path does not
   exist at all, and a kill there used to leave it **absent** with two orphaned `.apply-tmp-*` copies
   and nothing naming them. The trap puts the old tree back (an old config is a running box; an
   absent one is not), and the next run sweeps and logs any residue it still finds.
7. **A valid but wrong config** (a `mem_limit` too small) → the container recreates and OOM-loops;
   `AppDown` fires and the healthcheck keeps Caddy from being told it is fine. Rollback: the `.env`
   lever when the knob is env-driven (§9.3), otherwise deploy the reverted commit.
8. **Two deploys racing** (a `main` build and a tag build finishing seconds apart) → serialised by the
   existing `deploy-production` concurrency group **and** by `flock` on the box, because the operator
   can run the script by hand at the same moment.
9. **`up -d --remove-orphans` with one `-f` file deletes the observability containers.** Impossible
   from the pipeline once the invocation is inside the script; still possible by hand, so the warning
   stays in the runbook where a human types it.
10. **An operator edits a synced file during an incident** → works, survives until the next deploy,
    raises `ConfigDrift` within ~30–90 min. Contract, not accident (§5).
11. **First run on a box that has no `.deployed-sha`** → the stamp and the checksum manifest are
    created; pre-existing divergence is *overwritten*, which is the entire purpose. The July files
    land in `.config-backup/` and are worth keeping.
12. **`.config-backup` growth** → keep 5, prune the rest. Each set is a few tens of kilobytes.
13. **A synced `ops/` newer than the installed copy** → the `installed-ops` drift scope; the remedy is
    the install step, and the sync will never do it (§6.4).
14. **The drift timer is not installed** → the metric is only as fresh as the last deploy;
    `hamstrack_config_check_timestamp_seconds` is how a reader tells. `noDataState: OK` means silence,
    not health.
15. **The applier is missing from the tarball** → `apply-config.sh` refuses a source tree with no
    `ops/deploy/synced-paths.txt`, but the SSM command's `bash $SYNC/ops/deploy/apply-config.sh` fails
    first, with "No such file or directory", before anything on the box is read. This is the one that
    will bite first, because `ops/` is untracked until HD-199 merges: a deploy of any commit that does
    not carry the applier — an older sha re-run by hand, a `Run workflow` on a branch that predates
    the merge — goes red at that line. Nothing is replaced, nothing is pulled, and the box keeps
    running what it had. Remedy: deploy a commit that contains `ops/`.
16. **The box's `.env` pins `APP_IMAGE_TAG`, and the pin has MOVED since the last apply** → the deploy
    is REFUSED at step 2b, before anything is replaced, because a pin that just changed holds the image
    still while nothing holds the configuration with it. A hand run may override two ways, and the
    pipeline passes neither: `--adopt-pin` re-stamps (the flag is needed once, for a deliberate version
    bump) and `--allow-pinned` applies that run without re-stamping (a configuration fix needed *during*
    the rollback), so while a *fresh* rollback pin is in place every deploy goes red on purpose and says
    why. Un-pinning resumes them — and so does adoption, but only adoption: an override that applied
    and re-stamped would end the red deploys by *agreeing with the pin*, which is the failure, not the
    remedy.
16c. **The pin is set in the ENVIRONMENT rather than in `.env`** (`APP_IMAGE_TAG=… sudo -E …`) → the
    deploy is REFUSED, naming `.env` as where a pin has to live. Compose gives the process environment
    precedence over `--env-file`, so obeying it would deploy a tag that the stamp, the drift check and
    every later `up -d` cannot see; an exported pin lasts one shell, and the rollback lever was put in
    `.env` precisely because that file survives every deploy.
16b. **The box's `.env` pins `APP_IMAGE_TAG` and the pin has NOT moved** → the deploy PROCEEDS. This is
    the self-hoster's steady state (`APP_IMAGE_TAG=0.4` is what `docs/self-hosting.md` prescribes) and
    the pipeline's, once a pin has been applied once: the configuration is being placed beside the same
    image it was placed beside last time, which is the thing step 2b exists to preserve. A box that has
    NEVER been applied to has no `.deployed-image-tag` and is refused instead, because "unchanged"
    cannot be established.
17. **`GITHUB_OWNER` unset in the box's `.env`** → caught by step 2, like every other `${VAR:?…}`.
    It carries a `:?` guard for a reason the others do not have: it composes the IMAGE NAME, and
    without a guard compose only warns, `config -q` still exits 0, and the resolved
    `ghcr.io//hamstrack:latest` fails at `pull` — after the files were replaced and the sha stamped.

**The deploy is ALL-OR-NOTHING, and that is new.** The image is pulled at step 7, after every file has
been placed, so a deploy that goes red at any earlier step leaves production entirely on the image and
the configuration it was already running. Before this ticket a half-broken deploy still ran `pull` and
`up -d`, which is how a box came to run new code beside an 11 July compose file for six weeks. The
trade is deliberate: yesterday's image with yesterday's configuration is a state somebody can reason
about, and the two halves disagreeing is not — so an error that used to cost half a deploy now costs
the whole one, visibly.

---

## 12. Data model, API surface, frontend

**None, none, and none.**

- **Data model:** no migration, no table, no column, no entity. Flyway is untouched.
- **API surface:** no endpoint added, changed or removed; `openapi.yaml` and `docs/api-*.md` need no
  update. Nothing here is workspace-scoped because nothing here is a tenant resource — there is no
  query to scope and no membership to check.
- **Frontend:** no page, component, store or route.

The application's *runtime behaviour* changes only insofar as two already-merged settings begin to
take effect — a container memory ceiling (§9) and per-address rate-limit keying (§10). The only
application-code change proposed in this ticket is **tests**.

---

## 13. DC/Cloud implications

The deploy pipeline is owner-only infrastructure and a self-hoster runs none of it. Two rules keep
this from becoming a cloud-only assumption:

- **The generic half is generic — in its assumptions, not only in its arguments.**
  `apply-config.sh` takes a source directory and a target directory and contains no AWS, no SSM and
  no GitHub. A self-hoster with a clone runs `git pull && sudo ops/deploy/apply-config.sh .
  /opt/hamstrack`, or ignores it entirely — and that recipe, `--allow-pinned`/`--adopt-pin` and `COMPOSE_FILES` are
  documented for that reader in `docs/self-hosting.md#applying-repository-configuration`, with the
  caveat that the default compose set is the bundled stack. (A citation is only as good as the section
  it lands in: this one pointed at a document that mentioned none of the three.) The AWS-specific half
  (SSM transport, codeload fetch) lives in `deploy.yml`. Two assumptions had to be widened to make the
  portability claim true rather than merely well-intentioned: `COMPOSE_FILES` **skips** a compose file
  the release tree does not carry, so a deployment without the observability stack is not stopped by
  `${GF_SECURITY_ADMIN_PASSWORD:?…}`; and a pinned `APP_IMAGE_TAG` — which
  `docs/self-hosting.md#upgrading` tells every self-hoster to set — is a state the applier PROCEEDS on
  while the tag holds still, rather than a wall or a flag retyped for ever. The same widening keeps the
  drift check honest on such a box: it compares only the compose files that are present, instead of
  reporting `containers=1` for ever — and the drift *unit* takes `COMPOSE_FILES` through
  `EnvironmentFile=-/etc/hamstrack/drift.env`, because a variable that reaches only the hand-run path
  leaves the hourly timer (the thing that publishes the metric) reporting `containers=1` regardless.
- **No profile gating is needed**, because no application behaviour differs between modes here.
  `dc-cloud-guard`'s check on this ticket is the env-var wiring below, not a profile fork.

**New environment variables — exactly one ships in this ticket:**

| Variable | Default | Read by | Wiring targets |
|---|---|---|---|
| `APP_IMAGE_TAG` | `latest` | Compose (`docker-compose.prod.yml`), not Spring | `docker-compose.prod.yml`, `.env.prod.example` (next to `GITHUB_OWNER`), `docs/self-hosting.md` (the pinning advice moves here from "edit the compose file"), `docs/release-checklist.md` (rollback), README **iff** it enumerates image configuration — check rather than assume |

`RATE_LIMIT_CLIENT_IP_HEADER` and `CADDY_TRUSTED_PROXIES` belong to the follow-ups in §6.3/§10.3 and
must **not** be written into `.env.prod.example` by this ticket. A documented variable that nothing
reads is the defect this whole spec is about.

An empty `APP_IMAGE_TAG=` is harmless in the same way `APP_MEMORY_LIMIT=` is: Compose reads it, not
Spring, so it falls back to `latest` rather than refusing to boot. Say so in the example file, next to
the value.

---

## 14. Documentation impact — and what HD-122 does with its section

| File | Change |
|---|---|
| **`docs/ops-prod-hardening.md` §3** | **The "Config auto-sync from the repo" subsection is deleted, not annotated** — see below. |
| `docs/ops-prod-hardening.md` §5 | The 2026-08-26 correction blockquote is replaced by measured numbers once §9 has been applied: what was read off the box, and when. |
| `docs/ops-prod-hardening.md` §6.3 | The `curl … codeload` line and the two `cp` lines go: the files are on the box, so the install step reads from `/opt/hamstrack/ops/`. The inline-tarball note stays as the named exception (§16 / open question). |
| `docs/ops-prod-hardening.md` §7 (new) | *Verifying the deployed configuration* — commands whose exact output is the point (§10.2c, §16). |
| `docs/release-checklist.md` | "Rolling back" rewritten around `APP_IMAGE_TAG`; the compose-file pin sentence **deleted**. A pointer to §7 for releases touching compose, the edge path or the limiter. A line: a new `${VAR:?…}` in a compose file is an operator step *before* the deploy, and the validate step is its backstop. |
| `docs/self-hosting.md` | `APP_IMAGE_TAG` row in the configuration table; the pinning advice moves from editing the compose file to setting the variable; a line under `## Upgrading`. Plus a new `### Applying repository configuration` — the applier's own documentation for the reader every citation to this file was sending there, covering both pin overrides (`--allow-pinned`, `--adopt-pin`), `COMPOSE_FILES` and the bundled-stack default. And the `RATE_LIMIT_TRUST_FORWARDED_FOR` row, which said the bundled compose file **forces** `true`: it defaults, and reads `.env`. The stale wording is worse after this ticket than before it — a self-hoster who publishes the app port reads "forces", concludes `.env` is useless, and hand-edits `docker-compose.prod.yml`, which is a synced path the next apply replaces wholesale, silently restoring the spoofable value. |
| `docs/observability.md` | Four metric rows and two alert rows (§8), plus one line on why an uninstalled check is silent. |
| `.env.prod.example` | `APP_IMAGE_TAG` block beside `GITHUB_OWNER`. |
| `docs/project-state.md` | One line: config is delivered from the repository at the built commit. |
| `openapi.yaml`, `docs/api-*.md` | **No change** — no API surface (§12). |

### 14.1 What §3 becomes

The subsection is **deleted**, and so is the blockquote that disowns it. A disowned present-tense
paragraph is still a present-tense paragraph: this one has now produced the same class of error
twice — once as the original claim, and once this week when a fix round wrote a *new* present-tense
sentence into it. A preface asks every future reader and every future editor to remember; it has been
demonstrated that they do not.

What replaces it is short, and it describes only what cannot be read from the pipeline itself:

1. which paths belong to the repository (pointing at `ops/deploy/synced-paths.txt`, which is the list,
   rather than restating it);
2. which belong to the operator, and why — secrets, and decisions that must survive a deploy;
3. that the sync places files and installs nothing.

**The deploy command is not reproduced.** `.github/workflows/deploy.yml` and
`ops/deploy/apply-config.sh` *are* the behaviour; the section links to them. This is the same fix the
backups spec applied to its §16, for the same reason: a second copy is what a fix round updates last,
and the copy that is wrong is the one people read.

### 14.2 The rule that keeps it from happening a third time

`docs/ops-prod-hardening.md` gains one line near the top, phrased about the category rather than about
this section:

> **No section of this runbook describes pipeline behaviour in prose. It names the file that is the
> behaviour.** A paragraph that says what a deploy does can be true when it is written and false when
> it is read, and nothing in the repository will disagree with it.

---

## 15. What the operator must do — needs the owner

No AWS credentials exist on the development machine (the temporary key has been revoked), so
everything below is the owner's, in this order. Everything that could live in the repository does.

> **Steps 1–5 happen BEFORE HD-199 IS MERGED, not before some later deploy.** `deploy.yml` fires on
> `workflow_run` after a green Build on `main` and fetches the merged `head_sha` — so **the next
> merge to `main` is HD-199's own, and it is the first synced deploy**. Everything this section
> sequences (the `mem_limit` cut from ~954 MB of heap to 512 MB on a box with ~330 MB free, the
> backup, the dry run) has to be in place before the merge button, or it arrives unattended on the
> merge commit. There is no window between "merged" and "deployed" to use.

**1. Read the box — before any change.** One SSM session, five commands, and their output goes on
HD-199:

```bash
aws ssm start-session --target i-019fe684b25ad831f

# (a) What the app container actually has
docker inspect hamstrack-app-1 --format '{{.HostConfig.Memory}} {{.State.Health.Status}}'
docker inspect hamstrack-app-1 --format '{{json .Config.Env}}' | tr ',' '\n' | grep -Ei 'RATE_LIMIT|PROFILES'

# (b) The Caddy chain — this is what decides §10.3
cat /opt/hamstrack/Caddyfile

# (c) Present resource use, for §9.2
docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.MemPerc}}'
free -m

# (d) What the box thinks it is running
ls -l /opt/hamstrack/*.yml && grep -E '^(APP_MEMORY_LIMIT|APP_IMAGE_TAG|GITHUB_OWNER)=' /opt/hamstrack/.env
```

**2. Read the memory history** in Grafana (Explore → Prometheus) with the four queries in §9.2, and
record the window they cover.

**3. Set the values that must exist before the merge**, in `/opt/hamstrack/.env`. All three, and the
third is the one this release *adds*: `docker-compose.observability.yml` now carries
`${OBS_ALERT_EMAIL_TO:?…}`, and the box's `.env` almost certainly has no such line — which is exactly
the case `docs/release-checklist.md` now calls an operator step *before* the deploy. The deploy's
validate step (§7.2, step 2) is the backstop, not the mechanism — the dry run in step 5 below names
the variable, so forgetting this costs a round-trip rather than an outage:

```bash
sudo sed -i 's/^APP_MEMORY_LIMIT=.*/APP_MEMORY_LIMIT=<chosen in §9.3>/' /opt/hamstrack/.env
grep -q '^APP_IMAGE_TAG=' /opt/hamstrack/.env || echo 'APP_IMAGE_TAG=latest' | sudo tee -a /opt/hamstrack/.env
grep -q '^OBS_ALERT_EMAIL_TO=' /opt/hamstrack/.env \
  || echo 'OBS_ALERT_EMAIL_TO=<the address alerts should reach>' | sudo tee -a /opt/hamstrack/.env
# An EMPTY value is not enough: the guard fires on unset AND on empty, deliberately, because an
# empty address disables every alert RULE and not merely its delivery.
grep -E '^(APP_MEMORY_LIMIT|APP_IMAGE_TAG|OBS_ALERT_EMAIL_TO)=' /opt/hamstrack/.env
```

**4. Take a `manual/` backup** before merging — HD-187 makes this one command, and this is exactly
the occasion it exists for:

```bash
sudo BACKUP_S3_PREFIX=manual BACKUP_LABEL=pre-config-sync /usr/local/bin/hamstrack-backup
```

**5. Dry-run the first sync from the BRANCH HEAD, and read the diff — still before merging.** The
first synced deploy replaces a file dated 11 July with the current one in a single step: it
introduces `mem_limit`, the XFF flag, the healthcheck and everything else six weeks of commits
added. That deserves to be a reviewed change, and the only place to review it is before the merge:

```bash
cd /opt/hamstrack
SYNC=$(sudo mktemp -d /opt/hamstrack/.sync-XXXXXX)
curl -fsSL --proto '=https' --tlsv1.2 \
  https://codeload.github.com/Zherikhov/hamstrack/tar.gz/<SHA of the branch head> \
  | sudo tar xz -C "$SYNC" --strip-components=1
sudo bash "$SYNC/ops/deploy/apply-config.sh" "$SYNC" /opt/hamstrack <SHA> --dry-run
sudo rm -rf "$SYNC"
```

**6. Merge, and watch it deploy.** The merge IS the deliberate deploy — there is no separate one to
schedule, so do it at a moment you can watch rather than at the end of a day. Watch the app
container's health, then `/api/meta`, then the memory panel for 48 h (§9.3). (Actions → Build →
*Run workflow* on `main` re-runs the same deploy afterwards; it is a repeat, not the first.)

**7. Install the drift timer** — one SSM command, the same shape as HD-187's §6.3, reading from the
now-synced `/opt/hamstrack/ops/`:

```bash
sudo install -m 0750 /opt/hamstrack/ops/drift/hamstrack-config-drift.sh /usr/local/bin/hamstrack-config-drift
sudo install -m 0644 /opt/hamstrack/ops/drift/hamstrack-config-drift.service /etc/systemd/system/
sudo install -m 0644 /opt/hamstrack/ops/drift/hamstrack-config-drift.timer   /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now hamstrack-config-drift.timer
cat /var/lib/node_exporter/textfile_collector/hamstrack_config.prom
```

This box runs both compose files, so it needs nothing else. A box that runs the app WITHOUT the
observability stack writes `/etc/hamstrack/drift.env` — the unit reads it through
`EnvironmentFile=-…`, which is optional, so nothing here creates it:

```bash
sudo mkdir -p /etc/hamstrack
printf 'COMPOSE_FILES=docker-compose.prod.yml\n' | sudo tee /etc/hamstrack/drift.env
```

Setting `COMPOSE_FILES` only in the shell reaches the hand-run path and not the timer, and the timer
is what publishes the metric — that box would report `containers=1` for ever about a healthy stack.
Do NOT add the variable by editing the installed unit: that copy is compared byte-for-byte with the
synced one, so the edit shows as permanent `installed-ops` drift.

**8. Run the two-address probe** (§10.2c) and record both status codes on HD-199.

**9. Verify, don't assume** — the §16 checklist, in particular the three that were false this morning:
`HostConfig.Memory` non-zero, `RATE_LIMIT_TRUST_FORWARDED_FOR` present in the container environment,
and `.State.Health.Status` existing at all.

---

## 16. Acceptance criteria

Phrased so a reviewer can tell a mechanism from a belief.

**Delivery exists and runs**

1. `.github/workflows/deploy.yml` contains no file list and no compose invocation: it fetches by
   `head_sha` and calls `ops/deploy/apply-config.sh`.
2. A deploy leaves `/opt/hamstrack/.deployed-sha` equal to the commit whose build triggered it, and
   `.deployed-manifest.sha256` covering every synced file.
3. `--dry-run` prints a diff and changes nothing (`ls -l` timestamps unchanged, `.deployed-sha`
   unchanged).
4. **A compose file requiring a variable the box does not have fails the deploy and leaves the stack
   running.** Demonstrated, not asserted: temporarily rename `OBS_ALERT_EMAIL_TO` in `.env`, run the
   script, observe a non-zero exit naming the variable, `docker ps` unchanged, then restore.
5. Running the script twice concurrently produces one apply and one "another run holds the lock".

**The two features are actually in effect**

6. `docker inspect hamstrack-app-1 --format '{{.HostConfig.Memory}}'` is non-zero and equals
   `APP_MEMORY_LIMIT`.
7. `docker exec hamstrack-app-1 java -XX:+PrintFlagsFinal -version | grep MaxHeapSize` reports ~50% of
   that limit.
8. `docker inspect hamstrack-app-1 --format '{{json .Config.Env}}'` contains
   `RATE_LIMIT_TRUST_FORWARDED_FOR=true`.
9. `docker inspect hamstrack-app-1 --format '{{.State.Health.Status}}'` reports `healthy` — i.e. the
   field exists.
10. The two-address probe (§10.2c) returns **429 from A** on the 16th request and **401 from B**, and
    both codes are recorded on the ticket.

**There is one rollback story**

11. `docker-compose.prod.yml` resolves the image through `${APP_IMAGE_TAG:-latest}`; setting the
    variable in `.env` and running `up -d` moves the running container to that tag.
12. **The pin decides on MOVEMENT, not on existence**, and all three states are demonstrated against a
    scratch target directory: an `APP_IMAGE_TAG` equal to `.deployed-image-tag` proceeds; one that
    differs from it is refused before anything is replaced; a target with no `.deployed-image-tag` is
    refused. `--adopt-pin` turns each refusal into a warning **and re-stamps**, so the second run of
    the same command needs no flag; `--allow-pinned` turns each refusal into a warning and **does not**
    stamp, so the second run of the same command is refused exactly as the first was. That last one is
    the property, not a detail: an override reached for at hour six of an incident must not hand the
    next unattended deploy a pin/stamp agreement it never meant to declare.
12b. **The three states, both flags and the parse are SEALED, in the repository.**
    `src/test/java/com/hamstrack/ops/ApplyConfigPinGuardTest.java` drives the real script against
    scratch directories (with `docker` and `flock` stubbed onto `PATH`) and walks the four-step
    ordering above: refuse → `--allow-pinned` proceeds → **refuse again** → `--adopt-pin` proceeds →
    proceed. Its failure message is the propagation checklist. A harness that lives in a scratchpad
    proves a property once; the throttle epic already paid for the difference. The same class seals
    the two guards that sit closest to that parser and had nothing exercising them: **`.env` and the
    `Caddyfile` are refused in every spelling** (including `Caddyfile///`, which a single trailing-slash
    strip let through the never-sync `case` and left to fail by accident at the existence test, blaming
    the release tree) **and when carried inside a synced directory**, which is a file no manifest line
    names; and **`.github/workflows/deploy.yml` passes neither override**, read out of the SSM command
    string rather than the file, because that file discusses both flags in prose on purpose. A flag
    there would not override one deploy — it would delete step 2b from every merge.
13. **The pin is read the way COMPOSE reads it.** `export APP_IMAGE_TAG=v0.16.3` — which compose
    honours — is seen as a pin by both `apply-config.sh` and `hamstrack-config-drift.sh`; a parse that
    misses it reads the line as unset, which is `latest`, which is "not pinned", and fails **open** in
    the one check whose job is to refuse. Same for an inline ` # comment` tail on an unquoted value —
    and *not* for a `#` inside the value, which compose keeps. Absent and empty both mean `latest`, a
    duplicated key resolves to the last one, and a near-miss key (`MYAPP_IMAGE_TAG`, `APP_IMAGE_TAGS`,
    a commented-out line) is not a pin. The two scripts carry the same function, deliberately.
13b. **A pin compose would read from the ENVIRONMENT is refused rather than obeyed** — see §11.16c.
    `apply-config.sh` folds the environment in with compose's precedence; `hamstrack-config-drift.sh`
    deliberately does not, because it is a reporter published by a timer that inherits nobody's shell,
    and a gauge that flapped with whoever ran it by hand would answer a different question.
14. A deploy performed while pinned leaves the pin in place (`docker inspect … .Config.Image` still
    names the pinned tag) — no deploy writes `.env`, which is what makes the lever survive. The
    stamp follows only when that deploy **stamped**: an unmoved pin and an `--adopt-pin` run leave
    `/opt/hamstrack/.deployed-image-tag` naming the pinned tag too, while after `--allow-pinned` it
    deliberately still names the previously adopted one — that surviving disagreement is what makes
    the next unattended deploy refuse again (§7.2). So the stamp is the last tag **adopted**, not in
    general the tag the configuration now sits beside, and a triage reader takes "what runs" from
    `.env` (`docs/ops-prod-hardening.md` §7, check (d)).
15. `docs/release-checklist.md` contains **no** instruction to edit a file under `/opt/hamstrack` for
    rollback, and `DeployImagePinned` fires while a pin is in place.
16. **Every refusal names an action for both of its readers.** The message a moved pin produces tells
    the operator who bumped a version what to do (`--adopt-pin`, once) *and* the operator who rolled
    production back what to do (leave it refused; un-pin when the incident ends, or `--allow-pinned`
    for a single fix that cannot wait). A refusal that prescribed only "un-pin" would be advice the
    first reader must not take. **The no-stamp refusal says it too**, and it is the one that matters
    most: that is the state of every box on the first run, so the message must warn the reader whose
    box is pinned *because it is rolled back right now* not to adopt — an adoption made in that
    moment is permanent, and the rollback is not.

**Drift is visible**

17. `hamstrack_config_drift{scope="files"}`, `{scope="containers"}` and `{scope="installed-ops"}` are
    all present in Prometheus (job `node`) within one scrape of a deploy, all `0`.
18. **A deliberate edit is caught.** Append a comment line to `/opt/hamstrack/docker-compose.prod.yml`;
    within the timer's period `{scope="files"}` becomes `1`, `ConfigDrift` fires after `for: 30m`, and
    **an email arrives**. Re-deploy and it resolves. The criterion is the email; a red panel has
    reached nobody.
19. **The install gap is caught.** Edit `/opt/hamstrack/ops/backup/hamstrack-backup.sh` in place;
    `{scope="installed-ops"}` becomes `1`. Restore by re-deploying.

**The rate-limiter claim is tested where it will run again**

20. `AuthRateLimitForwardedForTrustedTest#distinctRightmostXffGetIndependentBudgets` exists, passes,
    and **fails when the filter is changed to key every request on ONE SHARED value** — verify by
    making that change locally and watching it go red before reverting. WHERE it reds is decided by
    the order JUnit runs the two methods in, not by the assertion that carries the property: today
    the sibling runs first, drains the single bucket and stays green, so this test reds at the first
    request of its warm-up rather than on its last line. A test that cannot be made to fail has not
    been shown to test anything. *Not*
    `getRemoteAddr()`, which was this criterion's first wording: under it **both** tests fail, because
    the sibling varies the socket across its burst on purpose and per-socket keying gives each of
    those requests its own budget. Two reds prove nothing about which test discriminates; production
    behind Caddy never varies the socket, and that freedom is MockMvc's rather than a client's.
21. `ProdComposeContractTest` fails when any of the four declarations it seals is removed from
    `docker-compose.prod.yml`, and its message names what else must change. The XFF seal accepts
    either the literal `"true"` or `"${RATE_LIMIT_TRUST_FORWARDED_FOR:-true}"` — what must not be
    deletable is the deployment's opinion, not the literal, and the default form is what makes
    `.env.prod.example`'s "set it false when the app port is directly reachable" a lever rather than
    inert advice.
22. `docs/ops-prod-hardening.md` §7 exists and carries the probe, including the sentence about what it
    cannot distinguish.

**Documentation**

23. `docs/ops-prod-hardening.md` contains **no prose description of what the deploy does** — verified
    by reading, and by the rule in §14.2 being present.
24. The auto-sync subsection and its blockquote are gone; nothing in the file describes a mechanism in
    the present tense that a reader cannot find as a file.
25. `.env.prod.example`, `docs/self-hosting.md` and `docs/release-checklist.md` agree on where the pin
    lives, and every link added to a rendered document has been clicked once (the lazy-continuation
    trap).

---

## 17. The highest-risk assumption, stated plainly

**That `/opt/hamstrack/.env` already satisfies every variable the current compose files require, so
the first synced deploy — six weeks of configuration replaced in one step, on a box nobody can
currently reach — does not take the site down.**

The validate step (§7.2 step 2) is designed for exactly this and resolves every `${VAR:?…}` against
the box's real `.env` before anything is replaced. But **the validate step is itself unproven until it
runs on that box**, and its failure mode if it is wrong is not a red deploy — it is a stack that
refuses to come up while the previous containers have already been asked to stop. The mitigation is
sequencing, not cleverness: the owner runs `--dry-run` from their own SSM session first (§15 step 5),
reads the diff, then triggers the deploy manually while watching. The `manual/` backup in step 4 and
`.config-backup/` cover the rest.

**Second, and cheaper to be wrong about: the Cloudflare/Caddy chain in §10.2b is derived from
documented behaviour and has not been measured.** If it is right, delivering the flag improves the
limiter from one global budget to one per edge node and the complete fix is a follow-up. If it is
wrong, the flag is the complete fix. Either way the delivery is correct and the measurement is one
`cat`.

**Third, and worth naming because it is the seductive one: nothing here has ever put the instance
under load.** §9's numbers are peaks observed during ordinary quiet use. `docs/ops-prod-hardening.md`
§5 already says it about this box, and HD-186 is the ticket that would change it.

---

## 18. Open questions

### 18.1 Needs the owner's decision

1. **Apply `mem_limit` before or after HD-189?** *Recommendation: after, if HD-189 lands within days —
   otherwise now, with the measured value from §9.2 and the 48-hour watch.* Applying it twice to
   arrive at one number is the only thing worth avoiding.
2. **Does the `Caddyfile` become repo-owned?** It needs the live file pasted into a ticket (§6.3), and
   §10.3's complete rate-limiter fix depends on it. *Recommendation: yes, as the immediate follow-up.*
3. **Deploy by immutable tag (`sha-…` for `main`, `X.Y.Z` for a release) instead of `latest`?** Both
   tags exist today (`build.yml`'s metadata step). It would make image and config atomic in the strong
   sense. *Recommendation: not in this ticket* — it interacts with `APP_IMAGE_TAG` and would make a
   hand-run `docker compose up -d` on the box silently revert to `latest`, which is a new trap in
   exchange for a small gain.
4. **May the deploy ever install ops artifacts** (systemd units, `/usr/local/bin`)? *Recommendation:
   no.* Placing files is reversible and cheap; installing them on every merge is a blast radius, and
   the `installed-ops` drift scope makes the gap visible instead of silent.
5. **`ConfigDrift` severity and destination.** Warning, to the existing email contact point.
   *Confirm* — it shares an inbox with `BackupStale` (critical), and an alert people learn to ignore
   costs more than it saves.
6. **Does the repository stay public?** The fetch depends on it. If it ever goes private the sync needs
   a PAT, and the failure until then is an opaque 404 on every deploy.

### 18.2 Decided here — recorded so they are not re-litigated

| Question | Answer | Where |
|---|---|---|
| **HD-122: ship the sync, or delete the section?** | **Ship it** — and delete the section anyway, because the runbook must stop describing pipeline behaviour in prose | §4, §14 |
| How does config reach the box? | codeload tarball **by commit sha**, applied by a repo-owned script | §4, §7 |
| Where does the file list live? | `ops/deploy/synced-paths.txt`, not in the workflow | §6.1 |
| What is never synced? | `.env`, `Caddyfile` (for now), anything outside the target — refused by the script regardless of the manifest | §6.2 |
| Does the sync install things? | **No.** It places files. The gap is a measured drift scope | §6.4, §8.1 |
| Where does the image pin live? | `APP_IMAGE_TAG` in `.env`, so it survives a deploy by construction | §5 |
| Un-pin reminder? | A metric and an alert, not a sentence in a checklist | §5, §8.3 |
| Does drift detection ship now or next? | **Now**, minimal: three comparisons, four gauges, two rules | §8 |
| How is drift compared? | Checksums written at apply time + compose config hashes — no network in the hourly path | §8.1 |
| Is `.env` drift checked? | No — it has no released baseline, by construction | §8.5 |
| Order of the memory limit and HD-189? | HD-189 first if it is imminent; otherwise measure → set explicitly → deploy → watch 48 h | §9.4 |
| How is the app's memory measured? | Prometheus (`jvm_memory_*`) + `docker stats`. **Not** `systemctl show -p MemoryPeak` — absent on systemd 252 | §9.2 |
| What makes the rate-limiter claim testable? | Three artifacts: a discriminating unit test, a compose contract seal, a two-address probe — and none stands in for the others | §10.2 |
| Does the mechanism cover installing an **unreleased** ops change? | **No, by construction** — it fetches the commit a build produced, so there is nothing to fetch before a push. The inline-tarball recipe stays as the named exception; its scope shrinks because `ops/` now arrives with every deploy | §6.4, §14 |
| Profile gating? | None — no application behaviour differs between DC and Cloud here | §13 |
| New environment variables? | Exactly one: `APP_IMAGE_TAG` | §13 |

---

## 19. Architectural decisions (ADR)

One decision here is hard to reverse and will make a future contributor ask "why?": the **boundary**
between what the repository owns on the production box and what the box owns. Everything else in this
spec — the manifest, the checksums, the metric names — is mechanics that a later ticket may change
freely.

### 19.1 Production configuration is delivered from the repository at the built commit; the box owns only secrets and the image tag

- **Chosen:** the deploy fetches the repository tree for `workflow_run.head_sha` from codeload and
  applies a repo-owned manifest of config paths via `ops/deploy/apply-config.sh`, validating against
  the box's real `.env` before replacing anything. `/opt/hamstrack/.env` is never synced and is where
  the image pin (`APP_IMAGE_TAG`) and the memory ceiling (`APP_MEMORY_LIMIT`) live, so the levers an
  operator needs under pressure survive every deploy by construction.
- **Rejected:** baking config into the image or a release artifact (circular bootstrap, a config-only
  fix needs a full image build, and the compose file stops being readable before installation); a git
  checkout on the box (a working tree that accumulates local state, plus the application source on a
  production machine); keeping delivery manual and merely making the deploy refuse on mismatch (it was
  the human step that failed for six weeks; a louder human step is still a human step).
- **Trade-off:** a deploy can now break the site with a bad configuration, where previously it could
  only fail to fix one — bought back with validate-before-swap, a dry run and an on-box backup of what
  was replaced. In exchange: config and image can no longer come from different trees, the shell that
  deploys is reviewable, and the rollback story is single because the boundary is explicit.
- **Draft:** `docs/adr/0013-config-delivered-from-repo.md` (`Status: Proposed`).
