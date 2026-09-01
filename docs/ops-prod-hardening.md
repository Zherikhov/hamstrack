# Prod hardening runbook

> **Audience: the Hamstrack owner only.** This documents how the *official*
> hosted deployment at **hamstrack.com** (AWS EC2 + SSM, Cloudflare, S3) is set
> up and hardened — it is a reference specific to that infrastructure, **not a
> requirement for self-hosting**. If you're running your own instance (DC), see
> the [Self-hosting guide](self-hosting.md); you do not need any of the
> AWS/Cloudflare steps below.

Remaining PLAN.md Phase 7 backlog items that require AWS console / Cloudflare
dashboard access (no AWS credentials exist on the dev machine or the EC2
instance — these steps must be run by an account admin, e.g. in
[CloudShell](https://console.aws.amazon.com/cloudshell)). Server-side steps can
be done over SSH afterwards.

Facts used below: region `eu-north-1`, instance IP `<INSTANCE_IP>`
(`<INSTANCE_PRIVATE_DNS>`), app dir `/opt/hamstrack`, compose project `hamstrack`,
only Caddy (80/443) is exposed publicly.

> **No section of this runbook describes pipeline behaviour in prose. It names the file
> that is the behaviour.** A paragraph that says what a deploy does can be true when it is
> written and false when it is read, and nothing in the repository will disagree with it.
> This document has produced that error twice — once as an original claim, once when a fix
> round wrote a *new* present-tense sentence into the paragraph disowning the first — so the
> rule is about the category, not about the paragraph that caused it.

---

## 1. Attachments → S3

### AWS side (CloudShell)

```bash
REGION=eu-north-1
BUCKET=<BUCKET>
INSTANCE_ID=$(aws ec2 describe-instances --region $REGION \
  --filters "Name=ip-address,Values=<INSTANCE_IP>" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)

# Private bucket
aws s3api create-bucket --bucket $BUCKET --region $REGION \
  --create-bucket-configuration LocationConstraint=$REGION
aws s3api put-public-access-block --bucket $BUCKET \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

# Instance role with access to exactly this bucket
aws iam create-role --role-name hamstrack-ec2 --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam put-role-policy --role-name hamstrack-ec2 --policy-name attachments-s3 --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\"],\"Resource\":\"arn:aws:s3:::$BUCKET/*\"},
    {\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::$BUCKET\"}
  ]}"
aws iam create-instance-profile --instance-profile-name hamstrack-ec2
aws iam add-role-to-instance-profile --instance-profile-name hamstrack-ec2 --role-name hamstrack-ec2
aws ec2 associate-iam-instance-profile --region $REGION --instance-id $INSTANCE_ID \
  --iam-instance-profile Name=hamstrack-ec2

# CRITICAL: the app resolves credentials from IMDS *inside a container* —
# the default metadata hop limit of 1 blocks that. Raise it to 2:
aws ec2 modify-instance-metadata-options --region $REGION --instance-id $INSTANCE_ID \
  --http-put-response-hop-limit 2 --http-tokens required
```

### Server side (SSH, after the role is attached)

```bash
# migrate existing local files (volume path may differ — check `docker volume inspect hamstrack_attachments_data`)
sudo aws s3 sync /var/lib/docker/volumes/hamstrack_attachments_data/_data s3://<BUCKET>/

# switch the app over
cd /opt/hamstrack
sudo sed -i 's/^STORAGE_TYPE=local/STORAGE_TYPE=s3/' .env
echo 'STORAGE_S3_BUCKET=<BUCKET>' | sudo tee -a .env
echo 'STORAGE_S3_REGION=eu-north-1' | sudo tee -a .env
docker compose -f docker-compose.prod.yml up -d app

# verify: upload + download an attachment in the UI, then
aws s3 ls s3://<BUCKET>/ --recursive | head
```

No compose change needed — `docker-compose.prod.yml` already passes
`STORAGE_*` through, and empty S3 keys fall back to the SDK default chain
(instance role). The `attachments_data` volume can be removed once verified.

## 2. Cloudflare proxy (orange cloud)

Dashboard only, ~2 minutes. Order matters:

1. **SSL/TLS → Overview → Full (strict)** — do this FIRST. The default
   Flexible mode sends plain HTTP to origin port 80 and loops on Caddy's
   HTTPS redirect.
2. **DNS → Records** — switch `@` and `www` from "DNS only" to **Proxied**.
3. **Update the Caddyfile** so the app still sees real client IPs — behind the
   proxy Caddy's peer is a Cloudflare edge node, and (Caddy ≥ 2.5) it discards
   the `X-Forwarded-For` Cloudflare sends because CF isn't a trusted proxy.
   **Without this step the auth rate limiter would bucket all visitors under a
   handful of CF IPs → false 429s for everyone.** In `/opt/hamstrack/Caddyfile`:

   ```caddy
   {
       servers {
           trusted_proxies cloudflare   # requires the cloudflare-ip plugin, OR:
           # trusted_proxies static 173.245.48.0/20 103.21.244.0/22 ... (https://www.cloudflare.com/ips/)
       }
   }

   # Site address comes from SITE_ADDRESS in .env — do NOT hardcode the domain.
   {$SITE_ADDRESS} {
       reverse_proxy app:8080
   }
   ```

   The global `trusted_proxies` block is the only site-specific addition here —
   keep the site line as `{$SITE_ADDRESS}` (add `SITE_ADDRESS=…` to `/opt/hamstrack/.env`
   first, since the caddy service uses `${SITE_ADDRESS:?…}` fail-fast). Merge this
   block into the existing Caddyfile; don't overwrite it with the repo template.

   Then `docker compose -f docker-compose.prod.yml restart caddy` and verify
   login still works and `docker compose logs app` shows distinct client IPs.
4. Certificate renewal: Let's Encrypt HTTP-01 keeps working through the proxy
   (CF passes `/.well-known/acme-challenge/`); nothing to change.

Rollback: flip records back to "DNS only".

## 3. Close SSH port 22 (deploy via SSM)

The instance role from step 1 is a prerequisite.

### AWS side (CloudShell)

```bash
REGION=eu-north-1
# 1. Let the instance register with SSM (agent is preinstalled on AL2023)
aws iam attach-role-policy --role-name hamstrack-ec2 \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
# wait a few minutes, then confirm it shows up:
aws ssm describe-instance-information --region $REGION

# 2. Deploy principal for GitHub Actions (simple variant: IAM user + keys;
#    OIDC federation is the cleaner long-term option)
aws iam create-user --user-name hamstrack-deploy
aws iam put-user-policy --user-name hamstrack-deploy --policy-name ssm-deploy --policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect":"Allow","Action":"ssm:SendCommand","Resource":[
      "arn:aws:ec2:eu-north-1:*:instance/<INSTANCE_ID>",
      "arn:aws:ssm:eu-north-1::document/AWS-RunShellScript"]},
    {"Effect":"Allow","Action":["ssm:GetCommandInvocation"],"Resource":"*"}
  ]}'
aws iam create-access-key --user-name hamstrack-deploy   # → GitHub secrets
```

GitHub repo secrets: add `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_INSTANCE_ID`; the old `SERVER_*` secrets become obsolete.

### Pipeline change (`.github/workflows/deploy.yml`)

```yaml
  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: eu-north-1
      - name: Deploy via SSM
        run: |
          CMD_ID=$(aws ssm send-command \
            --instance-ids "${{ secrets.AWS_INSTANCE_ID }}" \
            --document-name AWS-RunShellScript \
            --parameters 'commands=["cd /opt/hamstrack && docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml up -d --remove-orphans && docker image prune -f"]' \
            --query 'Command.CommandId' --output text)
          aws ssm wait command-executed --command-id "$CMD_ID" \
            --instance-id "${{ secrets.AWS_INSTANCE_ID }}"
          aws ssm get-command-invocation --command-id "$CMD_ID" \
            --instance-id "${{ secrets.AWS_INSTANCE_ID }}" \
            --query '{status:Status,out:StandardOutputContent,err:StandardErrorContent}'

```

> **The YAML above is a 2026 snapshot of the SSH→SSM migration, kept as history.**
> What deploys today is [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml);
> it has been rewritten twice since (HD-115, HD-199) and this block was not. Never read a
> snapshot in this file as current behaviour — open the workflow.

#### What lives where on the box

The deploy places the repository-owned configuration for the built commit and then brings
the stack up.

1. **Which paths belong to the repository:**
   [`ops/deploy/synced-paths.txt`](../ops/deploy/synced-paths.txt). That file is the list —
   it is not restated here, because a second copy is what a fix round updates last.
   Directories in it are replaced **wholesale**: a file dropped into
   `/opt/hamstrack/observability/` is gone at the next deploy. Replacing a directory
   wholesale also swaps the *inode* a running container bind-mounts, which `up -d` cannot
   see, so the applier restarts the services that mount a changed path (grafana, prometheus,
   loki, alloy) — otherwise the file is right, both drift scopes read 0, and the container
   is still serving the config that was deleted out from under it.
2. **Which paths belong to the operator, and why.** `/opt/hamstrack/.env` and
   `/opt/hamstrack/Caddyfile` are never synced. `.env` holds every secret **and** the
   decisions that must survive a deploy — `APP_IMAGE_TAG` (the rollback pin) and
   `APP_MEMORY_LIMIT`. The `Caddyfile` carries this box's hand-added Cloudflare
   `trusted_proxies` block (§2), which the repository's copy does not; syncing it would
   replace a hardened config with a bare one.
   [`apply-config.sh`](../ops/deploy/apply-config.sh) refuses both **even if somebody adds
   them to the manifest**, because the manifest is the thing a careless edit would change —
   and refuses them the same way when they arrive *inside* a synced directory, which is the
   only route by which a `Caddyfile` could reach the box through a manifest line that names
   neither.
3. **The sync places files and installs nothing.** `/opt/hamstrack/ops/` arrives with every
   deploy; nothing is copied to `/usr/local/bin`, no systemd unit is written, nothing is
   `daemon-reload`ed. A changed script in the repository does not change what runs on a
   schedule until an operator runs the install step (§6.3). That gap is measured — it is
   the `installed-ops` scope of `hamstrack_config_drift` — because a gap nothing measures
   is how six weeks of merged configuration stayed off this box.

The behaviour is [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) and
[`ops/deploy/apply-config.sh`](../ops/deploy/apply-config.sh). Read them; they are short,
and unlike a paragraph they cannot disagree with what runs.

These properties are worth knowing without opening either file, because they are what an
operator's decisions depend on (deliberately not counted here — a count goes stale one
entry before the list does):

- **The deploy is all-or-nothing.** The image is pulled *after* every file has been placed,
  so a deploy that goes red at any earlier step leaves production entirely on the image and
  the configuration it was already running. Before this, a partly-broken deploy still pulled
  and started the new image. Yesterday's image beside yesterday's config is a state you can
  reason about; the two halves disagreeing is what six weeks of this box looked like.
- **A MOVED `APP_IMAGE_TAG` refuses the deploy; a pin that sits still does not.**
  `apply-config.sh` compares `/opt/hamstrack/.env` against
  `/opt/hamstrack/.deployed-image-tag` — **the last tag anybody adopted**, which is what a
  stamping run placed configuration beside (`--allow-pinned` deliberately does not stamp, so
  after one of those the stamp still names the previous tag while `.env` names the running
  one, and that disagreement is the refusal working, not drift).
  Unchanged means the configuration is going next to the same image it has been living
  beside, so the deploy proceeds. Changed means somebody just moved the image: the run stops
  before replacing anything, because a pin holds the image still while nothing holds the
  configuration with it, and a config change synced onto a rolled-back image can re-apply the
  very thing being rolled back. So after you pin for an incident **every deploy stays red
  until you un-pin *or* adopt the pin**, on purpose — and a box that pins by policy (which is
  what `docs/self-hosting.md` prescribes for self-hosters) is not blocked for ever by that
  rule. **Two overrides, and the difference between them is the whole point.**
  `--adopt-pin` applies *and* re-stamps: it says "this tag is the intended one now", so a
  deliberate version bump costs the flag once. `--allow-pinned` applies **this run only** and
  deliberately leaves `.deployed-image-tag` alone, so the next unattended deploy refuses
  again — that is the one to reach for when a configuration change must go out *during* the
  rollback. The split exists because the alternative was walked: pin for an incident, CI goes
  red as designed, and six hours later an urgent fix (or the `DeployImagePinned` alert) sends
  somebody to a hand run — if that run re-stamped, the *next merge* would read the pin as
  "unmoved", call it a steady-state re-apply and put the newest configuration on the
  deliberately held-back image with no flag and no refusal. An override that fatigue can turn
  into a permanent disarming is not an override. A target with no `.deployed-image-tag` at all
  is refused too: "unchanged" cannot be established on a box this script has never applied to
  — and that is the state of *every* box on the first run, so if production happens to be
  rolled back that day, do **not** adopt: the refusal is right, and un-pinning is what ends
  it. The **adopted** tag is stamped in `.deployed-image-tag` beside `.deployed-sha`; read
  both during an incident, because the sha alone is half the answer — and read `.env` for
  the third, because that is the file that says what actually runs.
- **The pin must live in `/opt/hamstrack/.env`, not in your shell.** Compose gives the
  process environment precedence over `--env-file`, and this runbook tells you to use
  `sudo -E` for `COMPOSE_FILES` — so an exported `APP_IMAGE_TAG` would be the tag that gets
  deployed while the guard, the stamp, the hourly drift check and every later `up -d` all
  read the file and see something else. `apply-config.sh` folds the environment in with
  Compose's precedence and **refuses** when it is the environment that decides, naming the
  fix: put the pin in `.env`, which is the one file no deploy replaces.
- **A compose file the box's `.env` cannot satisfy fails the deploy, not the site.**
  `apply-config.sh` resolves every compose file it is going to run against
  `/opt/hamstrack/.env` **before** replacing anything, so a new `${VAR:?…}` that the box has
  no value for turns into a red
  deploy naming the variable, with the running stack untouched. Setting such a variable is
  still an operator step *before* the merge reaches production; this is its backstop, not
  its replacement.
- **An edit made on the box works, survives until the next deploy, and is noticed.** That
  is the contract, not an accident: `.env` for durable, the file for temporary, a commit
  for permanent. `ConfigDrift` fires within ~30–90 minutes (§6.4, `docs/observability.md`).

Deploy this change and verify one green deploy via SSM **before** the last step:

```bash
# 3. Remove the world-open SSH rule (or restrict to your own IP)
SG_ID=$(aws ec2 describe-instances --region eu-north-1 --instance-ids <INSTANCE_ID> \
  --query 'Reservations[0].Instances[0].SecurityGroups[0].GroupId' --output text)
aws ec2 revoke-security-group-ingress --region eu-north-1 --group-id $SG_ID \
  --protocol tcp --port 22 --cidr 0.0.0.0/0
# optional replacement for personal access:
# aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 22 --cidr <YOUR_IP>/32
```

Ad-hoc shell access afterwards: `aws ssm start-session --target <INSTANCE_ID>`
(or the browser-based Session Manager in the console) — no open ports needed.

## 4. Observability — reaching Grafana over SSM

The observability stack (`docker-compose.observability.yml`: Loki + Alloy +
Grafana, Prometheus/exporters in later phases) publishes **no public port**.
Grafana binds `127.0.0.1:3000` on the instance, so it's reachable from the host's
loopback but not the internet. You tunnel to it with an SSM port-forward — the
same credentials/instance role already used for deploys, no SSH, no security-group
change.

Prerequisite on the server: the stack must be running and its config present under
`/opt/hamstrack/observability/` (see the [Self-hosting guide](self-hosting.md#observability-optional)
for the file layout). Bring it up alongside the app:

```bash
cd /opt/hamstrack
docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d
```

> **Always pass BOTH `-f` files together.** Running `up --remove-orphans` with only
> `docker-compose.prod.yml` once the stack is up would treat loki/alloy/grafana as
> orphans and delete them.

> **A missing fail-fast variable aborts the whole command, not just the obs half.**
> `GF_SECURITY_ADMIN_PASSWORD` and (since HD-197) `OBS_ALERT_EMAIL_TO` are
> `${…:?}` in `docker-compose.observability.yml`, and because both files are layered
> in one invocation the refusal takes `up -d`, `down`, `stop`, `ps` and `logs` alike —
> you cannot even stop the app until the variable is back in `/opt/hamstrack/.env`.
> Compose resolves interpolation before touching anything, so **nothing is created,
> changed or stopped** by the refusal and a running stack keeps running.

Then, from your laptop (needs AWS creds with `ssm:StartSession` + the SSM plugin):

```bash
aws ssm start-session \
  --region eu-north-1 \
  --target i-019fe684b25ad831f \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'
```

Leave it running and open **http://localhost:3000** — log in with
`GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD` from `/opt/hamstrack/.env`.
The Loki datasource and the **Logs** dashboard are auto-provisioned; use
**Explore → Loki** for ad-hoc `{container="hamstrack-app-1"}` queries.

To debug Loki/Prometheus directly, forward their ports the same way (they don't
bind a host port, so use `AWS-StartPortForwardingSessionToRemoteHost` targeting the
container, or query them from inside Grafana).

## 5. Memory: what the box has, and what the app is allowed

**Re-measured on the running instance 2026-08-28 (HD-189)** — `i-019fe684b25ad831f`,
`eu-north-1`. Every figure below was read off the box or out of Prometheus, and where a
number is still a *declaration* rather than an *observation* it is labelled as one. The
previous version of this section is preserved as the before-state, because the interesting
part of this measurement is not any single number but that it **inverted a claim this
document made with confidence**.

### 5.1 The claim that was backwards

This section used to say: *"There is no swap, and the app is the only container with a
limit… `postgres`, `caddy` and the observability containers are deliberately unbounded."*

The two halves failed in different ways, and the difference is the lesson. **"No swap" was
true when written and is false now** because somebody changed the box — an ordinary stale
fact. **"The app is the only container with a limit" was false when it was written**, and
it was false in the direction that matters: it blamed the wrong containers for the pressure
and it named the app as protected when the app was the least protected thing running. What
the box actually reported on 2026-08-28:

| Container | `docker inspect … .HostConfig.Memory` | Observed |
|---|---|---|
| `app` | **`0` — no limit** | 597 MiB RSS |
| `postgres` | **`0` — no limit** | — |
| `caddy` | **`0` — no limit** | — |
| all seven observability containers | limit **set and honoured** (256m / 128m / 128m / 256m / 64m / 128m / 64m — `grep mem_limit docker-compose.observability.yml`) | **466 MiB total** |

So on that day the observability stack was the only bounded part of the deployment, it
lived well inside its ceilings, and it is not what is squeezing the box — the containers
that carry the actual workload are the unbounded ones. **Read that as a reading and not as
a property**: which containers have a limit is exactly the thing this section got wrong by
asserting it once, so the durable claim is the category — *whatever the operator has not
explicitly bounded and verified is unbounded* — and the way to settle it is
`docker inspect $(docker ps -q) --format '{{.Name}} {{.HostConfig.Memory}}'`, on the box,
today.

**The general form of the mistake, which is the part worth keeping:** the earlier text was
a claim about *a member* ("the app is the only one with a limit") standing in for a claim
about *a category* ("everything the operator did not explicitly bound is unbounded"). The
member claim inverted the moment the box's compose file diverged from the repository's,
and nothing in the repository disagreed with it. **A limit is a property of a running
container, never of a file and never of a variable** — `docker inspect` is the only thing
that answers it. `APP_MEMORY_LIMIT=1g` has been sitting in `/opt/hamstrack/.env` this whole
time and is read by nothing, because the box's `docker-compose.prod.yml` (dated 11 July,
from the six weeks when nothing shipped configuration here) has no `mem_limit` line to read
it with.

**What the repository declares as of HD-180 (2026-09-01), which is still not what any box
has.** `docker-compose.prod.yml` now carries a `mem_limit` on `postgres`
(`POSTGRES_MEMORY_LIMIT`, default `512m` — ~2× the ~240 MB peak RSS measured during the
08-31 load window) and on `caddy` (`CADDY_MEMORY_LIMIT`, default `128m` — ~5× its ~24 MB
peak, looser on purpose because it is the only container on 80/443 and an OOM kill there is
a site-wide outage), and it passes PostgreSQL's memory dials explicitly
(`POSTGRES_SHARED_BUFFERS` 128MB, `POSTGRES_EFFECTIVE_CACHE_SIZE` 512MB,
`POSTGRES_WORK_MEM` 4MB — HD-225; the image default `effective_cache_size` of 4GB told the
planner this 1909 MiB box had more than twice its own RAM cached). It also declares
`shm_size: ${POSTGRES_SHM_SIZE:-64m}` on that container, which changes nothing as shipped —
64 MB is Docker's own default — and exists so that raising `work_mem` on a bigger host has
the matching `/dev/shm` dial in `.env` rather than in a file a deploy replaces. Those limits reach a
container only when a deploy places the file and `docker compose up -d` recreates it, so
the discriminator above is unchanged and is still the only answer: **read it back from the
box**. What the limits buy is *containment* — a service that runs away dies and restarts in
its own cgroup instead of the kernel choosing across the host. They do **not** make the
deployment fit: app 1024 + postgres 512 + caddy 128 + observability 1024 is **2688 MiB of
declared ceilings on a 1909 MiB host**, and no arrangement of these numbers makes that sum
fit while leaving the app a usable heap. That is an argument for the resize, not against
the ceilings, and it is stated in full at the top of `docker-compose.prod.yml`.

**HD-189 has now made this same mistake three times inside its own corrections**, which
makes the shape worth more than any of the three fixes: the `mem_limit` claim above; the
`vm.swappiness=10` sentence, written as though every host ran 10 when it is this box's
setting and a distro default of 60 makes `HostSwapInUse` mis-tuned rather than right; and
exit `137` (§5.3), written as the signature of a kernel OOM kill when it is the signature
of any `SIGKILL`. **A symptom identifies a class, not a cause** — name the class, then name
the discriminator that picks the member out of it (`docker inspect … .HostConfig.Memory`,
`sysctl vm.swappiness`, `docker inspect … .State.OOMKilled`). A sentence that names only
the member reads as a diagnosis to everybody after you, and it is right until the first
time it is not.

### 5.2 The measurement

Before — recorded 2026-08-26:

```
$ free -m
               total        used        free      shared  buff/cache   available
Mem:            1909        1395          71          22         442         335
Swap:              0           0           0
```

After — 2026-08-28, once swap existed. These are the fields that were actually captured,
listed as fields rather than pasted as a terminal transcript: only part of one was taken,
and completing it from memory would be a fabrication.

| | 2026-08-26 | 2026-08-28 |
|---|---|---|
| memory total | 1909 MB | 1909 MB |
| memory available | 335 MB | **341 MB** — 7-day **minimum 227 MB** |
| memory used, as `1 - available/total` | 82.4% | **82.1%** — 7-day **maximum 88.1%** |
| swap total | **0** | **1023 MB** |
| root filesystem | 8.0 G, 5.3 G used (**67%**) | **79% used**, 1.7 GB free |

**Do not read that used-memory row against `free`'s `used` column** — they are different
quantities and the trap is a two-day-old memory spike that never happened. `free` reported
**1395 MB used (73%)** on 2026-08-26, which excludes the 442 MB of page cache the kernel
would hand back on demand; Grafana's host panels and both new alert rules work from
`MemAvailable`, which counts it. The two rows above are the same definition on both dates,
and by it **this box has not moved: ~82% either day.** Compare like with like, or a change
of denominator reads as an incident.

**Swap exists as of 2026-08-28** — a 1023 MB file at `vm.swappiness=10`, persisted in
**`/etc/fstab`** and **`/etc/sysctl.d/99-hamstrack-swap.conf`** so it survives a reboot.
It is an emergency buffer, not a memory tier: at swappiness 10 the kernel reaches for it
reluctantly, so *sustained* swap usage means RAM was genuinely gone rather than that a page
was cold. **It is not free** — it cost 12 points of the root filesystem, 67% → 79%, leaving
1.7 GB. `DiskFilling` fires below 15% free, so the swapfile moved this box from "far from
that rule" to "6 points from it", and anything that grows on disk now has less room to do it
in.

Read the swap arrangement back rather than trusting this paragraph — it is two files and a
runtime state, and the failure mode is a swapfile that exists today and is gone after the
next reboot:

```bash
swapon --show                                  # the file, its size, its priority
sysctl vm.swappiness                           # expect 10
grep -r swap /etc/fstab /etc/sysctl.d/         # both persistence halves, by name
```

### 5.3 The heap ceiling is larger than the machine

**An unlimited container sizes its heap against *host* RAM** — `-XX:MaxRAMPercentage` has
nothing else to be a percentage *of* when there is no cgroup limit — so on a small box the
JVM is entitled to a ceiling the machine could never hand it. The consequence, stated as
the property rather than as this week's numbers: **the kernel's OOM killer arrives before
`OutOfMemoryError` does.** The victim dies on `SIGKILL` — exit `137`, no stack trace,
nothing in any application log — with `JVMHeapPressure` (heap used/max > 90%) **green
throughout**, because the ratio it watches never approaches its threshold on a box whose
real constraint is the machine rather than the heap. That is the failure a small unbounded
deployment is exposed to, its cause is the **missing** `mem_limit` rather than the
observability stack, and it is why `HostMemoryLow` exists.

**`137` is any `SIGKILL` (128 + 9), not a diagnosis.** A `docker stop` whose grace period
expired and escalated, a `docker kill`, a `kill -9`, Docker Desktop or the daemon stopping
containers on a workstation — every one of them exits `137`, and a kernel OOM kill is one
member of that class rather than its identifier.
**`docker inspect <container> --format '{{.State.OOMKilled}} {{.State.ExitCode}}'`** is
what separates them: `OOMKilled` is the field that answers the question `137` only raises.
The direction of harm is a false alarm — an operator sees `137` after a routine restart and
resizes a box that was fine, or spends a day hunting a memory leak that does not exist.
Demonstrated rather than theorised: while HD-189 was being written a container on the
owner's workstation recorded `Exited (137)` because Docker Desktop was shut down, and it
was read here as an out-of-memory event before the field was checked. **This is the long
form** — the two alert summaries that mention `137` and the matching passage in
`docs/observability.md` carry the short one plus this same `docker inspect` line. None of
the first-moves guidance changes: `HostKernelOOMKill` already asked for `OOMKilled`.

The values behind that paragraph were read off the running instance on 2026-08-28 and are
deliberately **not** restated here. Two reasons, and the first outlives the second: a
margin measured on one day is the kind of member claim §5.1 watched invert, and while the
fix below is unmerged this section — the operational runbook — has no use for arithmetic
that describes one live machine rather than the shape of the failure. They are kept where
something actually reasons from them, which is
[`docs/design/config-delivery-proposal.md`](design/config-delivery-proposal.md) §9.1 (the
size of the cut) and
[`docs/design/load-capacity-measurement-proposal.md`](design/load-capacity-measurement-proposal.md)
§1 (why a load run against an unbounded heap measures the wrong configuration), plus
**HD-189** itself.

It is also why 0.17.0 did not do here what its release notes describe. That release added
`-XX:MaxRAMPercentage=50` to the image *and* `mem_limit` to the repository's compose file;
only the image half ever reached this box. Before 0.17.0 the JVM used its own default of
~25% of host RAM. After it, with no container limit, it takes 50% of host RAM.
**The release named "bound the container heap" doubled it in production.**

**What closes this is `APP_MEMORY_LIMIT` / `mem_limit: ${APP_MEMORY_LIMIT:-1g}` on the
`app` service reaching the box — which is HD-199's payload, and HD-199 is still unmerged.**
Until it lands there is no ceiling to raise or lower; there is no ceiling. Note the
direction of the step when it does land: with a limit the heap becomes half the **limit**
instead of half the **host**, so unless the limit is the whole machine it *cuts* the
ceiling the container has been running on rather than lowering a new one onto it — a `1g`
limit is a 512 MB heap. So HD-199 sequences it deliberately (measure first, choose the
value in `.env` *before* the merge, then watch for 48 h) rather than letting it arrive as a
side effect. Read that sequencing as "before **merging** HD-199": `deploy.yml` fires on a
green Build of `main`, so the merge *is* the first synced deploy.

The new alert rules in
[`observability/grafana/provisioning/alerting/rules.yml`](../observability/grafana/provisioning/alerting/rules.yml)
— **`HostMemoryLow`** (< 200 MiB available for 10m, critical), **`HostSwapInUse`**
(> 128 MiB of swap for 15m, warning) and **`HostKernelOOMKill`**
(`increase(node_vmstat_oom_kill[15m]) > 0`, critical) — are what make host pressure
visible from either side of that change, and each names a different point on the same
failure: approaching the wall, already past it, and one that has already happened.

The first two are absolute byte thresholds and not percentages **on purpose**: 90% of 1909 MB and
90% of 4096 MB are different amounts of danger, and the buffer a Linux box needs — GC
headroom plus enough page cache to keep Postgres off the disk — does not scale with the
box, so the thresholds survive the resize this ticket intends without being re-derived.
`HostMemoryLow`'s 200 MiB sits *below* the observed 7-day minimum of 227 MB, so **it would
not have fired once in the week it was derived from** and fires only when the box is worse
than it has ever been. `HostSwapInUse` has no such backtest and cannot have one: there was
no swap during the observation window, so its 128 MiB is reasoned (a Linux box parks a few
idle MB there and never touches them again) rather than fitted, and it is the one of the
two to re-check once a week of swap history exists. `HostKernelOOMKill` has nothing to
tune: it counts an event the kernel already decided, so it is inert on a healthy box, it
is the only one of the three that does not depend on how eagerly that kernel swaps, and —
because it reads a counter of the event itself rather than an exit code — it is the only
one that cannot be confused by a `SIGKILL` that came from somewhere else.

### 5.4 When to revisit, and what is still only declared

**Revisit on any instance resize.** Above 2 GB the guidance is roughly half the host
(`4g` host → `APP_MEMORY_LIMIT=2g`), and from a `4g` limit upward pair it with an explicit
`JAVA_TOOL_OPTIONS=-Xmx…` — the 50% split over-reserves at a large limit, because most
non-heap cost is constant rather than proportional. The worked table lives in
`docs/self-hosting.md` under the `APP_MEMORY_LIMIT` row; do not re-derive it here.

**Never apply a container limit and a resize in one step** — two changes, one symptom, no
way to tell which caused it.

**What is now observed:** host memory (total, available, 7-day minimum and maximum), swap,
disk, the app container's RSS, the JVM's heap ceiling and its 7-day peak usage, every
container's actual limit, the observability stack's real footprint, peak database
connections and database size. Those stopped being declarations on 2026-08-28. Observed is
not the same as printed here: the heap figures live on HD-189 (§5.3) and the database's
size is stated where it is load-bearing, in
[`docs/design/load-capacity-measurement-proposal.md`](design/load-capacity-measurement-proposal.md)
§4.2 — a number stated in more than one document goes stale in all but one of them.

**What is still declared:** whether any of it is *enough*. Every number above was taken
from an instance whose peak concurrent database connection count over seven days is **1** —
capacity measured at idle is not capacity. **HD-186** is the load run that would replace
that last declaration with a measurement, and until it has run, "1 GB is fine" remains a
belief. The rule this section keeps re-learning: read the limit back from the running
container, and read the load off a box that has some.

### 5.5 OPEN — the root volume is not encrypted, and that is the control swap raises

**Status: open. Nothing below has been decided or done; the owner has not chosen.**

**Measured 2026-08-28.** The production root volume `vol-02d8251fd45b62472` reports
`Encrypted: false`, and the account's **default EBS encryption is off**, so anything
created from that default inherits the same state — including the daily snapshots of
§6.1's layer 3.

Adding a swapfile put a copy of anonymous process memory on that volume, which is what
raises the question. It is the wrong end of it. That volume already holds, in plaintext,
`/opt/hamstrack/.env` — **the only copy of `JWT_SECRET` anywhere**, the database password
and `MAIL_PASSWORD` — plus every `pg_dump` staged there before upload. **Swap is the
lowest-yield artefact on the disk**, so `swapoff` before taking a snapshot would sanitise
one file while the rest of the volume rides along in the same image, and it would do it
by pulling every swapped page back into the headroom §5.2 measured — trading a real risk
of the OOM kill §5.3 describes for a partial cleanup of the least valuable thing there.

**So the control is volume encryption, not swap management.** State it as the category:
*any artefact on an unencrypted volume is exposed to whoever obtains the volume or one of
its snapshots, and removing artefacts one at a time does not change that.* Encryption
covers all of them at once, including the ones nobody enumerated.

What is genuinely undecided is the sequencing and the cost: EBS encryption cannot be
enabled in place, so it means snapshot → copy with encryption → restore, i.e. a stop of
the instance. **A snapshot is imminent for HD-186**, and that snapshot will be unencrypted
unless this is settled first — which is the only reason it is written down now rather than
filed. Also unsettled: turning on the account default (cheap, prevents the *next*
unencrypted volume, does nothing for this one) and whether the backup bucket's SSE-S3
(§6.2, already on) changes the priority.

---

## 6. Backups

Design, and every decision behind it:
[`docs/design/production-backups-proposal.md`](design/production-backups-proposal.md)
(HD-187). The self-hoster-facing half is
[`docs/self-hosting.md#backups`](self-hosting.md#backups). This section is the
owner-side runbook: what runs where, the AWS commands that only account credentials
can run, and the drill log.

The script, the units and the alert rules are in the repository; **§6.2 and §6.3 are
the one-time steps that install them**, §6.4 is how each durability property is
checked rather than assumed, and §6.6 is where a drill is recorded once it has been
run. Nothing below is a report of work already done.

### 6.1 What runs where

| Layer | Mechanism | Where it lives | Retention | Protects against |
|---|---|---|---|---|
| 1 + 2 | daily `pg_dump -Fc` + `pg_dumpall --globals-only` → the backup bucket | host `systemd` timer `hamstrack-backup.timer`, script from [`ops/backup/`](../ops/backup/) | 30 days, by S3 lifecycle | a bad migration, a dropped table, an application bug that deletes rows, ransomware, loss of the instance **and** its volume |
| 3 | daily EBS snapshot of the root volume | AWS Data Lifecycle Manager | 7 snapshots | loss of the **box**: `/opt/hamstrack/.env` (which is the only copy of `JWT_SECRET` anywhere), the hand-edited `Caddyfile`, `caddy_data` certificates, the observability volumes |
| 4 | versioning + noncurrent-version expiry on the **attachments** bucket | bucket settings | 30 days of noncurrent versions | an accidental or malicious delete/overwrite of an attachment, which the database dump cannot undo |

**Deliberately absent, so the mechanism above is not read as covering them:**
point-in-time recovery (layer 1 loses up to 24 hours; closing that needs a permanently
running archiver and memory this box does not have), cross-region durability (the bucket
is in `eu-north-1`, same region as the instance), and backups of Grafana/Loki/Prometheus
data (derived and disposable; layer 3 sweeps the volumes up incidentally).

**Three properties are load-bearing.** The instance may **write** backups and may neither
**read** nor **delete** them: a backup an attacker who owns the box can download is an
exfiltration channel for every tenant at once, and one they can delete is no backup at
all. It may not **overwrite** them either — write-once keys (`If-None-Match: *`, enforced by
the bucket policy), because "overwrite everything with an empty body and wait for the
lifecycle rule" destroys an archive using nothing but the permissions the design grants.
And retention is enforced by the **bucket's lifecycle rule**, never by the script — a
script that can delete one backup can be made to delete all of them. Whatever else changes
here, those three do not.

`manual/` exists so that a copy somebody took deliberately is not expired by a rule
written for routine ones. The expiry rule is scoped to `daily/` alone, and — the property
that matters, stated the way it can stop being true — **no rule carrying
`NoncurrentVersionExpiration` matches `manual/`**. That is deliberately not "no rule
matches `manual/`": the multipart-abort rule already matches every key in the bucket, and
writing the weaker claim is what let a verification step pass against a bucket that did not
have the property. The instance can delete neither prefix, and since fix round 2 it cannot
overwrite either one (§6.2 step 1).

The job runs at **03:15 UTC** and the DLM window is **04:30 UTC**, offset so the two
never contend for the same volume I/O.

### 6.2 One-time AWS setup (CloudShell, owner credentials)

Run in one sitting, in this order. `<PLACEHOLDERS>` are yours to fill.

> **S3 Object Lock: decided, and the answer is no.** It can only be enabled *at bucket
> creation*, so it is settled here rather than later: the instance already cannot delete,
> the account is single-owner, and an immutable bucket that outlives a GDPR erasure request
> trades a security problem for a legal one. What replaces it is in step 1 — write-once keys
> plus a 30-day noncurrent-version window, which block the same attack (overwrite, then wait
> for the lifecycle rule) without making an object undeletable by its owner.

```bash
# ── 0. Variables ─────────────────────────────────────────────────────────────
REGION=eu-north-1
INSTANCE_ID=i-019fe684b25ad831f
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BACKUP_BUCKET=hamstrack-backups-$ACCOUNT_ID     # S3 names are GLOBAL; pick a unique one
ATTACH_BUCKET=<EXISTING_ATTACHMENTS_BUCKET>
```

**1. Create the backup bucket — private, versioned, encrypted, HTTPS-only.**

SSE-S3 rather than SSE-KMS on purpose: no key policy to misconfigure on the day you
need a restore, no per-request cost, and the ETag of a single PUT stays the MD5 of the
body — which is what makes the script's end-to-end integrity check free and, crucially,
possible **without** granting `s3:GetObject`.

```bash
aws s3api create-bucket --bucket "$BACKUP_BUCKET" --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"
aws s3api put-public-access-block --bucket "$BACKUP_BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-versioning --bucket "$BACKUP_BUCKET" \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "$BACKUP_BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-bucket-policy --bucket "$BACKUP_BUCKET" --policy "{
  \"Version\":\"2012-10-17\",
  \"Statement\":[
   {\"Sid\":\"DenyInsecureTransport\",\"Effect\":\"Deny\",\"Principal\":\"*\",
    \"Action\":\"s3:*\",
    \"Resource\":[\"arn:aws:s3:::$BACKUP_BUCKET\",\"arn:aws:s3:::$BACKUP_BUCKET/*\"],
    \"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}},
   {\"Sid\":\"DenyOverwriteOfBackups\",\"Effect\":\"Deny\",\"Principal\":\"*\",
    \"Action\":\"s3:PutObject\",
    \"Resource\":\"arn:aws:s3:::$BACKUP_BUCKET/*\",
    \"Condition\":{\"StringNotEquals\":{\"s3:if-none-match\":\"*\"}}}]}"
```

**`DenyOverwriteOfBackups` is what makes the keys write-once**, and it closes a hole
that `PutObject` + prefix `ListBucket` left wide open on its own: list the prefix, overwrite
every key with an empty body, wait out `NoncurrentVersionExpiration`, and the lifecycle
rule deletes the last good version for you. Nothing would notice — the freshness metrics
measure the local run and never the remote state. With the deny in place a `PutObject` that
does not carry `If-None-Match: *` is refused, so the box can add tomorrow's backup and can
not touch yesterday's. The script always sends it; **`--if-none-match` needs AWS CLI ≥
2.19, and §6.3 verifies the version on the box before the timer is armed** (an older CLI
exits 252 on the unknown option — loud, but only if you look).

**It covers `/*`, not `daily/*`, and that widening is a fix.** The earlier version exempted
`manual/` on the reasoning that no lifecycle rule matches that prefix, so an overwrite there
would leave a noncurrent version that never expires. The conclusion is true today; the
premise is not. `abort-incomplete-multipart-uploads` is declared with `"Filter":{"Prefix":""}`
and matches every key in the bucket. What actually protects `manual/` is narrower and much
easier to lose: **no rule carrying `NoncurrentVersionExpiration` matches it.** The
attachments bucket in step 5 is configured with exactly `Filter:{"Prefix":""}` +
`NoncurrentVersionExpiration:{"NoncurrentDays":30}`, so the natural "tidy noncurrent
versions bucket-wide" edit here would silently re-arm the whole attack against `manual/` —
no deny, no alert, and nothing in the archive to say it happened. And `manual/` is where
`docs/release-checklist.md` sends the pre-migration copy you are about to depend on: during
an incident, fetching it would return the attacker's empty object and read as a corrupt
backup. The exemption bought nothing anyway — every basename embeds a second-precision
timestamp, so a legitimate write never collides with an existing key. An owner who really
does want to replace a `manual/` object deletes it first; they hold `DeleteObject` and the
box does not.

**The owner has decided against S3 Object Lock**; these two settings — a 30-day noncurrent
window and write-once keys — are what close that finding instead.

**2. Lifecycle: 30 days for `daily/`; nothing that expires `manual/`.**

```bash
aws s3api put-bucket-lifecycle-configuration --bucket "$BACKUP_BUCKET" \
  --lifecycle-configuration '{"Rules":[
    {"ID":"expire-daily-after-30-days","Status":"Enabled","Filter":{"Prefix":"daily/"},
     "Expiration":{"Days":30},"NoncurrentVersionExpiration":{"NoncurrentDays":30}},
    {"ID":"abort-incomplete-multipart-uploads","Status":"Enabled","Filter":{"Prefix":""},
     "AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}'
```

`NoncurrentDays` is **30, not 7**, and it matches `Expiration.Days` and the attachments
bucket. A noncurrent version is the copy that survives an overwrite, so a 7-day window
meant a version history one week deep protecting an archive advertised as thirty days
deep — the shorter number silently became the real retention for anything overwritten.

30 days is not "as long as feels safe": it brackets the window in which slow, silent
logical corruption gets noticed on a product this size, it outlives a release cycle, and
it is short enough that a GDPR erasure request needs no backup surgery at all. A longer
horizon, if ever wanted, is a second lifecycle rule on a `weekly/` prefix — not a bigger
number here.

**3. Grant the instance role write-only access.** A **new** inline policy alongside the
existing `attachments-s3`; do not merge them, the two grants have different lifetimes and
different justifications.

```bash
aws iam put-role-policy --role-name hamstrack-ec2 --policy-name backups-s3 --policy-document "{
  \"Version\":\"2012-10-17\",
  \"Statement\":[
    {\"Sid\":\"WriteBackupsOnly\",\"Effect\":\"Allow\",
     \"Action\":[\"s3:PutObject\",\"s3:AbortMultipartUpload\"],
     \"Resource\":[\"arn:aws:s3:::$BACKUP_BUCKET/daily/*\",\"arn:aws:s3:::$BACKUP_BUCKET/manual/*\"]},
    {\"Sid\":\"ListOwnPrefixesForVerification\",\"Effect\":\"Allow\",
     \"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::$BACKUP_BUCKET\",
     \"Condition\":{\"StringLike\":{\"s3:prefix\":[\"daily/*\",\"manual/*\"]}}}]}"
```

**What is absent is the design.** No `s3:GetObject`, so owning the box does not hand over
the archive. No `s3:DeleteObject` and no `s3:PutBucketLifecycleConfiguration`, so the box
can neither erase history nor shorten retention. `ListBucket` is kept only so the operator
can verify from the box during install; it discloses key names, which are timestamps.

**4. Tag the volume and create the DLM snapshot policy (7 snapshots, 04:30 UTC).**

```bash
VOL_ID=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].BlockDeviceMappings[0].Ebs.VolumeId' --output text)
aws ec2 create-tags --region "$REGION" --resources "$VOL_ID" --tags Key=Backup,Value=hamstrack

aws dlm create-default-role --resource-type snapshot
DLM_ROLE=arn:aws:iam::$ACCOUNT_ID:role/AWSDataLifecycleManagerDefaultRole

aws dlm create-lifecycle-policy --region "$REGION" \
  --description "hamstrack daily EBS snapshots" \
  --state ENABLED --execution-role-arn "$DLM_ROLE" \
  --policy-details '{
    "PolicyType":"EBS_SNAPSHOT_MANAGEMENT",
    "ResourceTypes":["VOLUME"],
    "TargetTags":[{"Key":"Backup","Value":"hamstrack"}],
    "Schedules":[{"Name":"daily-7d",
      "CreateRule":{"Interval":24,"IntervalUnit":"HOURS","Times":["04:30"]},
      "RetainRule":{"Count":7},
      "TagsToAdd":[{"Key":"Name","Value":"hamstrack-auto"}],
      "CopyTags":true}]}'
```

A snapshot of a running Postgres is crash-consistent, and PostgreSQL is designed to come
up from a crash by replaying WAL; the whole data directory is on the **one** volume, so
there is no torn-across-volumes hazard. It is a real recovery path — and it is the
*second* one, because a snapshot cannot rescue you from a bad migration it faithfully
preserves.

**5. Harden the attachments bucket (layer 4).**

```bash
aws s3api put-bucket-versioning --bucket "$ATTACH_BUCKET" --versioning-configuration Status=Enabled
aws s3api put-bucket-lifecycle-configuration --bucket "$ATTACH_BUCKET" \
  --lifecycle-configuration '{"Rules":[
    {"ID":"expire-noncurrent-attachment-versions","Status":"Enabled","Filter":{"Prefix":""},
     "NoncurrentVersionExpiration":{"NoncurrentDays":30},
     "AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}'
```

### 6.3 Putting the job on the box

SSH is closed (§3), so this is one SSM command. It reads from `/opt/hamstrack/ops/`, which
a deploy places (§3) — there is nothing to download, and the files are already the ones the
running release carries.

```bash
aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript --comment "HD-187 install backup timer" \
  --parameters 'commands=["set -e",
"install -m 0750 -o root -g root /opt/hamstrack/ops/backup/hamstrack-backup.sh /usr/local/bin/hamstrack-backup",
"install -m 0644 /opt/hamstrack/ops/backup/hamstrack-backup.service /etc/systemd/system/",
"install -m 0644 /opt/hamstrack/ops/backup/hamstrack-backup.timer /etc/systemd/system/",
"mkdir -p /etc/hamstrack /var/backups/hamstrack /var/lib/hamstrack-backup /var/lib/node_exporter/textfile_collector",
"test -f /etc/hamstrack/backup.env || install -m 0640 /opt/hamstrack/ops/backup/backup.env.example /etc/hamstrack/backup.env"]'
```

> **A deploy places `/opt/hamstrack/ops/` and installs nothing** — this step is what
> installs it, and it stays a human step on purpose (a deploy that rewrites systemd units
> on every merge is a blast radius nobody asked for). Re-run it whenever a release changes
> a file under `ops/`; the `installed-ops` scope of `hamstrack_config_drift` is what tells
> you that it has, so this is not a thing to remember, only a thing to do when asked.

> **This reads what a deploy has already delivered, which presumes the change is pushed
> AND deployed.** For an **unreleased** ops change there is nothing on the box to install
> from — and on 2026-08-26 that was the first install itself: the HD-187 files were still
> uncommitted, so no tag and no commit on GitHub carried `ops/backup/`. The way through is
> to **transfer the tarball inline over SSM** — base64 the needed paths on the dev machine,
> send them as the first commands of the same `send-command`, unpack into `/tmp/hs`, and
> point the `install` lines above at `/tmp/hs` instead:
>
> ```powershell
> # dev machine: pack only what the install needs, then inline it into the SSM command
> tar czf ops.tgz ops/backup ops/drift
> $B64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("ops.tgz"))
> # …send as: "rm -rf /tmp/hs && mkdir -p /tmp/hs",
> #           "echo '<B64>' | base64 -d > /tmp/hs.tgz",
> #           "tar xzf /tmp/hs.tgz -C /tmp/hs"
> ```
>
> SSM caps a command's parameters, so a large tree has to be split across several
> `send-command` calls or staged through S3. Its scope is now small — `ops/` arrives with
> every deploy, so this is for the ops change that has **not been merged yet**, which is
> exactly the moment the runbook is most in use.

**Rollback copies left by the first install (2026-08-26).** Before replacing them, that
install kept the previous provisioning wholesale:

```
/opt/hamstrack/observability.bak-2026-08-26-1444
/opt/hamstrack/docker-compose.observability.yml.bak-2026-08-26-1444
```

They are the one-command way back if the new rules or the node-exporter change misbehave.
Delete them once the arrangement has survived a few days — a `.bak-` directory nobody has
needed in a week is only a thing to mistake for the live one.

Then configure it, arm the alarm, and prove it end to end:

```bash
# a. Configure it by EDITING the installed example — the `install` line above already put
#    the annotated template at /etc/hamstrack/backup.env. Do not overwrite it with a
#    printf: the comments are the only place several settings are explained, and the
#    reader who needs them is you, mid-restore. Set BACKUP_S3_BUCKET and BACKUP_S3_REGION;
#    everything else is already correct for this box.
sudo $EDITOR /etc/hamstrack/backup.env
sudo chmod 0640 /etc/hamstrack/backup.env
grep -E '^BACKUP_(TARGET|S3_BUCKET|S3_REGION)=' /etc/hamstrack/backup.env
# Line endings matter: a CR would become part of the value.
! grep -q $'\r' /etc/hamstrack/backup.env && echo "clean LF"
# And so does parsing: systemd's EnvironmentFile= accepts lines bash cannot read (a stray
# paren, one quote too few), and the script sources this file as well. It survives such a
# file — WARN, defaults, then a refusal to take a backup with them — but the run fails, so
# find out here rather than at 03:15. `bash -n` parses without running, so it will not show
# you a line that parses and then FAILS (`BACKUP_S3_PREFIX=$(some-tool)` with no such tool);
# the script runs the file in a child shell before sourcing it and refuses on that too.
sudo bash -n /etc/hamstrack/backup.env && echo "parses"

# b. The write-once upload needs AWS CLI >= 2.19 (--if-none-match). An older CLI exits 252
#    on the unknown option — the run fails, loudly, but only after the first dump. Check
#    before arming the timer, not after.
#    Compare the VERSION rather than grepping `aws s3api put-object help`: AWS CLI v2
#    renders help through groff, which a minimal AL2023 box does not have, and there
#    `help` exits 255 with EMPTY stdout — so the grep reports a perfectly capable CLI
#    as too old, and prints nothing at all to say why. A gate that fails closed on a
#    missing man renderer is a gate that gets ignored.
aws --version
CLI_VER=$(aws --version 2>&1 | sed -n 's|^aws-cli/\([0-9][0-9.]*\).*|\1|p')
printf '2.19.0\n%s\n' "$CLI_VER" | sort -V -C \
  && echo "AWS CLI $CLI_VER supports --if-none-match" \
  || echo "AWS CLI $CLI_VER is BELOW 2.19 — upgrade it before arming the timer"

# c. Arm the alarm. A zero sentinel means "installed, never succeeded", so the first run to
#    execute at all publishes a series that is instantly stale rather than a healthy-looking
#    one. Note what it does NOT do: the SERIES appears only once the script has run once and
#    written a .prom, which is why step (e) is a manual run and not optional — the rules use
#    noDataState: OK, and an absent series is silence, not an alert.
echo 0 | sudo tee /var/lib/hamstrack-backup/last_success_upload
echo 0 | sudo tee /var/lib/hamstrack-backup/last_success_dump

# d. RECREATE node-exporter so it picks up the textfile flag and the new mount, and
#    then RESTART Grafana explicitly. Naming grafana in the `up -d` does nothing:
#    measured 2026-08-26, compose recreated node-exporter and left Grafana running,
#    because Grafana's SERVICE DEFINITION did not change — only the contents of the
#    provisioning directory it bind-mounts, which compose does not look inside. So the
#    new alert rules did not load at all until the container was restarted, after which
#    every rule in the file was provisioned (a count here goes stale one rule before the
#    list does, so it is not written down). "up -d does nothing when nothing changed" is
#    correct compose behaviour and is exactly the trap here: a bind-mounted config is
#    read at container start, so changing it is not a change compose can see.
#    A `restart` will not pick up a command/volumes change either — hence both commands.
cd /opt/hamstrack && sudo docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d node-exporter
sudo docker restart hamstrack-grafana-1

# e. Run it once by hand and watch it. Start it THROUGH systemd, never as a bare command:
#    that is the only way ExecStopPost= is exercised, and it is also the only way an abort
#    behaves — KillMode=control-group signals the whole cgroup, so `systemctl stop` reaches
#    the in-flight `docker exec` and the metrics are published in seconds. A bare hand run
#    that you `kill -TERM` keeps running until the dump it is streaming finishes (bash runs
#    a trap only after the current foreground command returns — 300s, measured), so if you
#    ever need to stop one, TERM its child too: pkill -TERM -P <pid>.
sudo systemctl daemon-reload
sudo systemctl start hamstrack-backup.service
sudo journalctl -u hamstrack-backup -n 50 --no-pager
cat /var/lib/node_exporter/textfile_collector/hamstrack_backup.prom
aws s3 ls "s3://$BACKUP_BUCKET/daily/" --region "$REGION"

# f. Replace the unit's guessed memory ceiling with a measured one — but NOT with
#    MemoryPeak, which does not exist on this box. `systemctl show -p MemoryPeak
#    hamstrack-backup.service` printed Result and ExecMainStatus and simply OMITTED the
#    property (measured 2026-08-26): the property landed in systemd 253 and Amazon Linux
#    2023 ships systemd 252. An unknown property is not an error — `show` returns 0 and
#    says nothing, which is precisely the silent-failure shape this step exists to catch.
#    Confirmed present instead: MemoryAccounting=yes, MemoryHigh=402653184 (384 MB),
#    MemoryMax=536870912 (512 MB), and systemd's own "Consumed 2.460s CPU time" in the
#    journal — accounting is on, so the kernel counter exists even though systemd will
#    not report its high-water mark after the fact.
systemctl --version | head -1
systemctl show -p MemoryAccounting -p MemoryHigh -p MemoryMax hamstrack-backup.service

#    On systemd 252 the peak has to be read from the unit's cgroup WHILE IT RUNS — the
#    cgroup is destroyed with the unit, so after the run there is nothing left to read.
#    Start the run, then from a SECOND shell either read memory.peak once (kernel >= 6.8,
#    a high-water mark maintained by the kernel) or sample memory.current in a loop:
CG=/sys/fs/cgroup/system.slice/hamstrack-backup.service
sudo systemctl start hamstrack-backup.service &
cat $CG/memory.peak 2>/dev/null || cat $CG/memory.max_usage_in_bytes 2>/dev/null \
  || while [ -r $CG/memory.current ]; do cat $CG/memory.current; sleep 1; done | sort -n | tail -1

#    Then act on the number: if the peak is anywhere near 384M, raise both with
#    `systemctl edit hamstrack-backup.service`; if it is far below, tighten them. The
#    ceilings bound bash + the docker CLI + the AWS CLI (a PyInstaller bundle, 120-180 MB
#    baseline) — NOT pg_dump, which runs in the postgres container's own cgroup.
#    Until such a measurement exists, say so plainly: as of 2026-08-26 the 384/512 MB
#    pair is STILL A GUESS on this box — installed, never measured. A ceiling nobody
#    measured is a ceiling that will be hit at the worst time.

# g. Only now enable the schedule.
sudo systemctl enable --now hamstrack-backup.timer
systemctl list-timers hamstrack-backup.timer
```

Finally, confirm `OBS_ALERT_EMAIL_TO` is set in `/opt/hamstrack/.env` and that the address
is one you read on a Sunday — "no backup for 26 hours" is a weekend-relevant fact. A
provisioned rule with nowhere to send is a rule that fires into a dashboard nobody is
looking at.

**Alloy collects logs through the Docker socket, so this unit's output is not in Loki.**
That is the accepted cost of a host timer over a compose sidecar. The signal that must
reach a person is the metric and the alert; the text survives on the box under
`journalctl -u hamstrack-backup`.

### 6.4 Verifying the properties, rather than assuming them

Each of these is a command whose *refusal* is the point.

```bash
# From the instance: it can write backups and neither read, erase nor overwrite them.
aws s3 cp s3://$BACKUP_BUCKET/daily/<any key> -      # expect AccessDenied
aws s3 rm s3://$BACKUP_BUCKET/daily/<any key>        # expect AccessDenied
# The one that is easy to forget, and the one that made "PutObject only" insufficient:
echo x | aws s3 cp - s3://$BACKUP_BUCKET/daily/<any existing key>   # expect AccessDenied
aws s3api put-object --bucket "$BACKUP_BUCKET" --key daily/<any existing key> \
  --body /dev/null --if-none-match "*"                             # expect PreconditionFailed
# And the same two against manual/, which the deny now covers as well:
echo x | aws s3 cp - s3://$BACKUP_BUCKET/manual/<any existing key>  # expect AccessDenied

# From the owner's shell: the bucket is what it claims to be.
aws s3api get-bucket-lifecycle-configuration --bucket "$BACKUP_BUCKET"   # 30d on daily/; see below for manual/
aws s3api get-bucket-versioning  --bucket "$BACKUP_BUCKET"               # Enabled
aws s3api get-bucket-encryption  --bucket "$BACKUP_BUCKET"               # AES256
aws dlm get-lifecycle-policies                                          # one ENABLED policy
aws ec2 describe-snapshots --owner-ids self \
  --filters Name=tag:Name,Values=hamstrack-* \
  --query 'Snapshots[].[StartTime,SnapshotId]' --output table
```

**Read the lifecycle output for the right property.** The check is **not** "no rule matches
`manual/`" — `abort-incomplete-multipart-uploads` has `"Filter":{"Prefix":""}` and matches
every key, so that phrasing passes a bucket that does not have the property and would go on
passing after somebody added a bucket-wide `NoncurrentVersionExpiration`. The property is:

```
# No rule that carries NoncurrentVersionExpiration may match manual/ (or match everything).
aws s3api get-bucket-lifecycle-configuration --bucket "$BACKUP_BUCKET" \
  --query 'Rules[?NoncurrentVersionExpiration!=`null`].[ID,Filter.Prefix]' --output table
# Expect exactly one row: expire-daily-after-30-days / daily/
```

To see the dump-succeeds/upload-fails asymmetry rather than trust it, point
`BACKUP_S3_BUCKET` at a bucket that does not exist, run the script **through systemd**
(`sudo systemctl start hamstrack-backup.service`) and read the `.prom`:
`last_status{stage="dump"} 1`, `last_status{stage="upload"} 0`, a real
`hamstrack_backup_size_bytes`, the dump file present locally, nothing in S3. Put the real
value back afterwards. Run it through systemd rather than by hand deliberately: until fix
round 2 this check passed as a hand-run and failed under `systemctl start`, because
`ExecStopPost=` fires on any non-success result and used to overwrite the trap's truthful
`dump=1, upload=0` with `dump=0, upload=0, size=0`. The handler now stands down when the
run's `EXIT` trap already published, so the two invocation paths agree — and this step is
what proves they still do.

### 6.5 The restore drill

**A backup nobody has restored is a belief.** This is the procedure that converts it.

**Where it runs — and where it cannot.** Not on the production box: 341 MB available and a
1 GB emergency swapfile that exists to survive a spike, not to host a second database (§5),
so a second Postgres plus a second JVM is not a tight fit, it is an outage — swap changes
that from an OOM kill into a box too slow to serve anybody, which is not an improvement
while it is production.
Not in CloudShell either — 1 GB of RAM and no Docker daemon. It runs on the **owner's dev
machine**, in a throwaway container on port **15433**, so the `hamstrack-postgres` dev
database on 15432 is untouched and the cleanup is one `docker rm`.

**Cadence:** quarterly, **before any migration that rewrites `flyway_schema_history`**,
and after any change to the script, the bucket, the IAM policy or the Postgres major
version.

**The scratch database this drill leaves behind is HD-188's step-3 evidence.** The Flyway
chain squash rewrites `flyway_schema_history` on the live production database, and its
step 3 requires a restored copy of production to rehearse that rewrite against — this
container, on port 15433, is it. So do not tear it down (step 8) until the squash has been
tried on it, and note the order that makes the evidence worth anything: the drill of
**2026-08-26 was run before any squash work began**, so what it proves is that *today's*
production dump restores and validates — a copy taken after the chain was touched could
only ever prove that the squash reproduces itself.

```powershell
# 0. Note the PRODUCTION row counts BEFORE restoring, so step 5 is an honest comparison.
#    Grafana publishes them (Product dashboard / Explore → Prometheus):
#      hamstrack_users_total, hamstrack_workspaces_total,
#      hamstrack_projects_total, hamstrack_issues_total
#    Read them at roughly the timestamp of the dump you are about to restore, and note
#    the version prod reports:  curl https://hamstrack.com/api/meta

# 1. Fetch the newest daily dump. OWNER credentials — the instance role cannot read these.
$B = "<BACKUP_BUCKET>"
aws s3 ls "s3://$B/daily/" --region eu-north-1 | Select-Object -Last 6
mkdir -Force .\restore-drill | Out-Null
aws s3 cp "s3://$B/daily/hamstrack-<TS>.dump"        .\restore-drill\ --region eu-north-1
aws s3 cp "s3://$B/daily/hamstrack-<TS>.globals.sql" .\restore-drill\ --region eu-north-1

# 2. A throwaway PostgreSQL, same major version as production (16), on a spare port.
#    127.0.0.1 and a drill-only password are not decoration: within two steps this
#    container holds a full copy of production - every user's email and password hash, plus
#    the SCRAM verifiers from the globals file. A bare `-p 15433:5432` publishes it on every
#    interface of the laptop, and a password printed in this repository is not a password.
docker run -d --name hamstrack-restore-drill `
  -e POSTGRES_USER=hamstrack -e POSTGRES_PASSWORD=drill-only-password -e POSTGRES_DB=hamstrack `
  -p 127.0.0.1:15433:5432 postgres:16-alpine

# 3. Restore. --exit-on-error is the point: a restore that reports errors and keeps going
#    is how a half-restored database gets mistaken for a good one.
docker cp .\restore-drill\hamstrack-<TS>.dump hamstrack-restore-drill:/tmp/d.dump
docker exec hamstrack-restore-drill `
  pg_restore -U hamstrack -d hamstrack --no-owner --no-privileges --exit-on-error -v /tmp/d.dump

# 4. Globals (roles). "role hamstrack already exists" is expected and benign — but note
#    what runs AFTER that notice and does NOT fail: `pg_dumpall --globals-only` emits
#    `ALTER ROLE hamstrack ... PASSWORD 'SCRAM-SHA-256$...'`, and that statement SUCCEEDS.
#    So restoring the globals silently replaces this scratch container's drill-only password
#    with PRODUCTION's.
docker cp .\restore-drill\hamstrack-<TS>.globals.sql hamstrack-restore-drill:/tmp/g.sql
docker exec hamstrack-restore-drill psql -U hamstrack -d hamstrack -f /tmp/g.sql

# 4b. Put the drill password back. DO NOT DELETE THIS AS REDUNDANT — it is load-bearing,
#     and the reason it looks redundant is the reason it is needed. `docker exec` reaches
#     the socket, which the image trusts, so the content checks in step 5 pass either way;
#     the break lands on step 6, the one that distinguishes "restored" from "restored and
#     VALID", because the application connects over TCP and authenticates. The obvious
#     recovery from there is to type the production database password into a shell on a
#     laptop that is already holding a full copy of production, which is exactly what this
#     drill must never make anybody do.
docker exec hamstrack-restore-drill psql -U hamstrack -d postgres `
  -c "ALTER ROLE hamstrack PASSWORD 'drill-only-password';"

# 5. Content check — compare with the numbers noted in step 0.
docker exec hamstrack-restore-drill psql -U hamstrack -d hamstrack `
  -c "select count(*) users from users" `
  -c "select count(*) workspaces from workspaces" `
  -c "select count(*) projects from projects" `
  -c "select count(*) issues from issues" `
  -c "select count(*) migrations, max(version) latest, bool_and(success) all_ok from flyway_schema_history"

# 6. Boot the application against the restored copy under ddl-auto=validate.
#    Check out the tag production is running (step 0) — a NEWER working tree would apply
#    migrations to the drill database, which is a different (also useful) experiment.
#    --spring.docker.compose.enabled=false is MANDATORY: without it spring-boot-docker-compose
#    starts/attaches the compose Postgres and silently ignores DB_URL (CLAUDE.md).
#    On 2026-08-26 this step was done with the PUBLISHED IMAGE instead of a working tree
#    — `docker run` of ghcr.io/zherikhov/hamstrack:latest under profile dc, pointed at the
#    scratch database — which is the same test with fewer moving parts and no docker-compose
#    override to disable. Either form passes or fails on the same two lines: Flyway's
#    "Schema public is up to date" and an EntityManagerFactory that initialises under
#    ddl-auto=validate.
$env:DB_URL="jdbc:postgresql://localhost:15433/hamstrack"
$env:DB_USERNAME="hamstrack"; $env:DB_PASSWORD="drill-only-password"
$env:JWT_SECRET="drill-only-secret-0123456789abcdef0123456789abcdef"
$env:SEED_ADMIN_EMAIL=""      # do not seed an admin into a restored production database
.\mvnw.cmd spring-boot:run --% -Dfrontend.skip=true -Dspring-boot.run.arguments="--spring.docker.compose.enabled=false --server.port=8081"

# 7. Prove it is serving the restored data.
curl http://localhost:8081/api/meta

# 8. Tear down. The dump files contain production PII — delete them from the laptop.
docker rm -f hamstrack-restore-drill
Remove-Item -Recurse -Force .\restore-drill
```

**What counts as a pass — all five. A run that misses any of them is a failed drill and a
ticket:**

1. `pg_restore --exit-on-error` completed with exit code 0.
2. The row counts match the production gauges from step 0, allowing for activity between
   the dump and the reading.
3. `flyway_schema_history` is present, `all_ok` is true, and `latest` is the version the
   application expects.
4. The application **started** — Flyway validated the restored history and Hibernate's
   `ddl-auto=validate` matched every entity against the restored schema. This is the bar:
   a schema that restores but does not validate is not a restore.
5. `/api/meta` answered.

While you are there, confirm today's EBS snapshot exists (the `describe-snapshots` command
in §6.4) and that `hamstrack_backup_last_success_timestamp_seconds` is present in
Prometheus — that second check is the one that catches a deleted `.prom` file or a lost
node-exporter mount, which `noDataState: OK` would otherwise turn into silence.

### 6.6 Restore drill log

Append a row **after** each drill, in the **past tense, with the date it happened**. A row
written in advance, or a promise that restores will be tested, is not a row. `Elapsed` is
the **measured** recovery time — from "I decided to restore" to "the application
answered" — and it is the only version of that number that is not a guess.

| Date | Object restored | Restored counts (users / ws / projects / issues) | Flyway version | App version booted | Elapsed | Operator | Notes |
|---|---|---|---|---|---|---|---|
| 2026-08-26 | `s3://hamstrack-backups/daily/hamstrack-2026-08-26T144759Z.dump` — 542 197 bytes, 357 TOC objects | 5 / 4 / 4 / 242 — **exact**, identical in the restored copy and in the live database (`issue_comments=141`, `flyway_schema_history=20` matched too) | 20 — "Successfully validated 20 migrations", "Current version of schema public: 20", "Schema public is up to date. No migration necessary." | `ghcr.io/zherikhov/hamstrack:latest`, profile `dc` | ~4 min | owner | Integrity checked independently of the script: downloaded with owner credentials, local MD5 `2be88c866cdea5646700563d94987ba7` matched the `ETag` from `head-object`, `ServerSideEncryption: AES256` — a second party verified the same bytes. Restored into a throwaway `postgres:16-alpine` on port **15433** on the owner's machine (not the production box — it has no memory for it); `pg_restore --exit-on-error --no-owner --no-privileges` returned 0, **42 tables** in `public`. Hibernate initialised the EntityManagerFactory under `ddl-auto=validate` with **no** schema-validation error; `Started HamstrackApplication in 22.202 seconds`. **Partial: the globals file was NOT restored on this run** — only the `.dump` — so step 4 and the step 4b that undoes it were not exercised. Read the pass as "the data restores and validates", not as "the written procedure was walked end to end". |

The drill that closes HD-187 was run on **2026-08-26 and it passed** — the row above is that
run, and it is the first one. Three things to read out of it:

- **It walked part of the procedure, not all of it.** That run restored only the `.dump`; it
  never applied the `.globals.sql` (step 4), so it did not exercise the step where the
  written procedure breaks — the `ALTER ROLE … PASSWORD` inside the globals file replaces the
  scratch container's password with production's, which is invisible to the `docker exec`
  content checks and lands on step 6. Step 4b was added afterwards, and the next drill is the
  first one that will have run it. "The runbook was walked" is what the row above reads like;
  what was walked is most of it.

- **`Elapsed` is the measured RTO, and it is roughly 4 minutes** — `get-object` to
  `Started HamstrackApplication`, **on a dump of this size** (542 KB). It is a real number
  for today's database and a lower bound for a larger one; the qualifier travels with the
  number or the number is a guess again.
- **It unblocks HD-188.** The squash rewrites `flyway_schema_history` on the live
  production database, and this drill — run *before* any squash work began — is what
  established that a restore of the pre-squash chain works, plus the scratch database
  HD-188's step 3 rehearses against (§6.5).

---

## 7. Verifying the deployed configuration

Built like §6.4: a short list of commands whose **refusal or exact status code** is the
point. Run it after any release that touches the compose files, the edge path or the rate
limiter — `docs/release-checklist.md` points here for exactly those. **A setting is in the
repository is not a finding; a setting is in effect is.** On 2026-08-26 the first three
checks below were all false on a box that had shipped every one of them in a release.

```bash
aws ssm start-session --target i-019fe684b25ad831f

# (a) The container memory ceiling is real, and the heap follows it (HD-152).
#     Non-zero, and equal to APP_MEMORY_LIMIT in .env.
docker inspect hamstrack-app-1 --format '{{.HostConfig.Memory}}'
docker exec hamstrack-app-1 java -XX:+PrintFlagsFinal -version | grep MaxHeapSize
#     …expect roughly HALF of the number above. `0` from the first command means there is
#     no limit and the heap is sized against HOST RAM, whatever .env says.

# (b) The rate limiter keys on the forwarded address (HD-75).
docker inspect hamstrack-app-1 --format '{{json .Config.Env}}' | tr ',' '\n' | grep -i RATE_LIMIT
#     …expect RATE_LIMIT_TRUST_FORWARDED_FOR=true. Absent = the property default (false) =
#     one per-IP budget for every visitor behind Caddy.

# (c) The healthcheck exists at all — the field, not merely the value.
docker inspect hamstrack-app-1 --format '{{.State.Health.Status}}'
#     …expect `healthy`. A template error here means the container was created from a
#     definition with no healthcheck, so caddy's `condition: service_healthy` means nothing.

# (d) What the box thinks it is running, and whether it still matches. THREE files, and each
#     answers a different question — the third is the one that is easy to misread mid-incident:
#       .deployed-sha        which TREE the placed files came from;
#       /opt/hamstrack/.env  which IMAGE RUNS: compose resolves APP_IMAGE_TAG from this file,
#                            so it is what the next `up -d` starts (an EXPORTED value would
#                            win over it, which is why apply-config.sh refuses that
#                            invocation instead of deploying it);
#       .deployed-image-tag  the last tag anybody ADOPTED — the tag a run that stamped placed
#                            configuration beside. Not, in general, the tag now running.
#     After an `--allow-pinned` run the last two DISAGREE BY DESIGN: that override applies the
#     configuration for one run and deliberately does not re-stamp, so .env can say 0.17.0
#     while the stamp still says latest. That gap is not drift and needs no repair — it is
#     exactly why deploys are red, and it ends when somebody un-pins or adopts the pin.
cat /opt/hamstrack/.deployed-sha /opt/hamstrack/.deployed-at /opt/hamstrack/.deployed-image-tag
grep -E '^[[:space:]]*(export[[:space:]]+)?APP_IMAGE_TAG=' /opt/hamstrack/.env \
  || echo 'APP_IMAGE_TAG not set in .env — compose resolves it to latest'
sudo /usr/local/bin/hamstrack-config-drift && \
  cat /var/lib/node_exporter/textfile_collector/hamstrack_config.prom
#     …expect every hamstrack_config_drift scope at 0.

# (e) A bind-mounted config change actually took effect. `up -d` cannot see inside a bind
#     mount, so the deploy restarts these when observability/ changed — this is how you
#     confirm it did, and the check no drift scope can make for you.
docker inspect hamstrack-grafana-1 --format '{{.State.StartedAt}}'
#     …expect a timestamp at or after .deployed-at when that deploy changed observability/.
#     Older means the container is still serving the file that was replaced under it.
```

### Installing the drift check

The deploy publishes these metrics at the end of every run, so the series exists before the
timer does — and is then only as fresh as the last deploy, which is why
`hamstrack_config_check_timestamp_seconds` is published and why this step matters. One SSM
command, reading from the now-synced `/opt/hamstrack/ops/`:

```bash
sudo install -m 0750 /opt/hamstrack/ops/drift/hamstrack-config-drift.sh /usr/local/bin/hamstrack-config-drift
sudo install -m 0644 /opt/hamstrack/ops/drift/hamstrack-config-drift.service /etc/systemd/system/
sudo install -m 0644 /opt/hamstrack/ops/drift/hamstrack-config-drift.timer   /etc/systemd/system/
sudo mkdir -p /var/lib/node_exporter/textfile_collector
sudo systemctl daemon-reload && sudo systemctl enable --now hamstrack-config-drift.timer
systemctl list-timers hamstrack-config-drift.timer
cat /var/lib/node_exporter/textfile_collector/hamstrack_config.prom
```

Like the backup job (§6.3), this is an install the deploy will never do for you — and like
it, the check itself will tell you when the installed copy has fallen behind the synced one.

**This box runs both compose files, so it needs nothing further.** A deployment that runs
the app *without* the observability stack must narrow the set the `containers` scope
compares against, or it reports `containers=1` for ever about a stack that is entirely
healthy — and it must do that where the **timer** can see it, since exporting the variable
in a shell reaches only a hand run:

```bash
sudo mkdir -p /etc/hamstrack
printf 'COMPOSE_FILES=docker-compose.prod.yml\n' | sudo tee /etc/hamstrack/drift.env
sudo systemctl restart hamstrack-config-drift.service
```

The unit reads that file through `EnvironmentFile=-…` (optional, so its absence is normal).
Do **not** add the variable by editing the installed unit instead: that copy is compared
byte-for-byte with the synced one, so the edit would show as permanent `installed-ops`
drift — the check reporting your own fix.

### The two-address probe

The one check that cannot be made from a single machine, because the property is about
*two* clients. A burst from one client trips at the 16th request under per-address keying
**and** under one shared bucket, so a single-address burst distinguishes nothing — that is
precisely how a 2026-07-14 verification passed while the flag was not in effect.

> From address **A** (the operator's machine), send 16 `POST /api/auth/login` to
> `https://hamstrack.com` within one minute, with an email that belongs to no account — the
> 16th must answer **429**. Immediately, from address **B**, send one identical request: it
> must answer **401**. A **429 from B** means the budget is shared and the delivery has not
> taken effect.
>
> **Address B is the instance itself**, over an SSM session
> (`curl -s -o /dev/null -w '%{http_code}' -X POST https://hamstrack.com/api/auth/login -H
> 'Content-Type: application/json' -d '{"email":"nobody@example.invalid","password":"x"}'`):
> its egress address is stable, distinct from the operator's, and always available — no
> second device, no VPN, no coordination.
>
> **What it proves and what it does not.** It proves the budget is **not global**. It does
> **not** distinguish per-edge from per-client: two sources that far apart reach different
> Cloudflare edge nodes either way, and Caddy sets `X-Forwarded-For` to its immediate peer —
> which behind Cloudflare is an edge node, whether or not the box's Caddyfile trusts
> Cloudflare's ranges. Proving *per-client* needs `cat /opt/hamstrack/Caddyfile` and the
> follow-up in `docs/design/config-delivery-proposal.md` §10.3, not a bigger probe.
>
> Use a nonexistent email so no real account's failure counter is touched; the per-IP
> window resets after a minute.

Record both status codes on the ticket. A probe whose result was not written down has to be
run again by the next person who asks.
