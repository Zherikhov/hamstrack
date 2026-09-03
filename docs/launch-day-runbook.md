# Launch-day runbook

> **Audience: the Hamstrack owner, and whoever is holding the pager on the day.** This
> covers the *hosted* deployment at **hamstrack.com** (AWS EC2 + SSM, Cloudflare, S3). It is
> not a requirement for self-hosting — a DC operator wants
> [`docs/self-hosting.md`](self-hosting.md) instead. Nothing here changes application
> behaviour; everything here is a decision, a threshold, or a command.

**If you are here mid-incident, do not read this page.** Go to §7 (which move), then §8
(close the door) or §9 (roll back the image). Everything before §7 is preparation and is
useless once the day has started.

**What this page is not.** It is not the release procedure — that is
[`docs/release-checklist.md`](release-checklist.md), including the pre-flight for a
migration that deletes rows, the "resource default" lines for the Release body, and the
settings this deployment must set by hand before the merge. It is not the alert reference —
that is [`docs/observability.md`](observability.md) and
[`observability/grafana/provisioning/alerting/rules.yml`](../observability/grafana/provisioning/alerting/rules.yml),
which is the file that decides what alerts and on what; read it rather than any prose
summary of it, including this one. It is not the infrastructure runbook — that is
[`docs/ops-prod-hardening.md`](ops-prod-hardening.md), whose §5 (memory), §6 (backups) and
§7 (verifying the deployed configuration) this page assumes you can reach.

**Status of the work that can only be done against production.** §2 (prove the alert
path) and §3 (rehearse the rollback) are written as procedures the owner can execute. Their
log tables are **empty**, and they stay empty until somebody runs them. A row written in
advance is not a row — the rule and its reasoning are
[§6.6 of the prod-hardening runbook](ops-prod-hardening.md#66-restore-drill-log), and this
page follows it because the failure it prevents is the one a launch runbook is most exposed
to: a procedure that reads like a report.

---

## 1. What is already true, and what is not

A claim about the alert *destination* and a claim about the alert *path* get read as the
same claim. They are not, and they do not have the same status.

- **Holds:** the alert *destination* is configured and guarded. HD-197 found every rule
  delivering to a placeholder address, caught a real `HighLatency` alert being refused by
  the mail provider with a `550`, replaced the fallback with `${OBS_ALERT_EMAIL_TO:?…}` in
  `docker-compose.observability.yml`, and set a real address in `/opt/hamstrack/.env`. An
  empty value now aborts the compose command by name; there is no placeholder left to
  interpolate cleanly.
- **Does not hold:** that a rule has fired *deliberately*, on a known date, and that the
  resulting message was found by a human in the inbox that variable now names. Nothing has
  observed that. Until §2.3 has a row, the honest sentence is "the path is configured and
  has never been walked", and an alerting system nobody has seen fire is indistinguishable
  from none.

**The capacity ceiling is measured and it is the input to every threshold below.** On
**one `t3.small` (1 vCPU / 2 threads, 1909 MB, `eu-north-1`) running a single application
container beside Postgres, Caddy and the whole observability stack**, at a fixture far larger
than today's production database, the deployment served **45 concurrent browsing users at
p95 364 ms**, plateaued at **~43 requests/second**, and the resource that ran out was
**memory, never CPU** — a 4.986 s GC pause equal to the slowest request in the same window.
Evidence and its caveats:
[`ops/loadtest/RESULTS-2026-08-31.md`](../ops/loadtest/RESULTS-2026-08-31.md). **The
instance has deliberately not been resized before publication**, so those numbers describe
the box that will take the launch traffic.

**Every number on this page is a property of that box, not of Hamstrack.** 45 concurrent,
32 req/s and ~43 req/s are restated in §5.3 and throughout §6's table, and they are restated
about *this* hardware and this container layout — a different instance size, a separate
database host, or a second replica moves all of them and invalidates the thresholds derived
from them. Nobody may quote them as the product's capacity.

Qualifiers travel with that ceiling and must not be dropped:

- It was measured against a **312 MB** fixture (42 000 issues). Production is far smaller,
  so per-request cost is lower and the real ceiling is probably *higher* than 45 users. The
  numbers below are therefore a **conservative floor**, not a prediction — treat a breach
  as real and treat headroom as unproven.
- The soak was not run and the entitlement probe aborted. Nothing established fifteen
  minutes of steady state, and a single compliant principal saturated the instance at
  ~1.3 MB per response. A sustained hour of real traffic is a shape this deployment has
  never been observed under, which is exactly why §5's window exists.

---

## 2. Proving the alert path — do this before the day

### 2.1 Which rule to fire, and why that one

Do not wait for a real incident, and do not pick the rule by severity. Pick it by these
properties, which are what make a deliberate firing safe and conclusive:

- its cause is something an operator can switch on and off on purpose;
- switching it touches nothing on the request path — no container recreated, no restart,
  no configuration a deploy would fight over;
- it clears by construction when the cause is removed, rather than by waiting out a window;
- its annotation interpolates a label, so a delivered message also proves that Grafana
  expanded the template rather than silently losing the whole annotation to a template
  error (the failure class the header of `rules.yml` describes — valid YAML, green CI, no
  text in the alert).

**`BackupRunFailed` satisfies all of them, and it is the one to use.** It is `for: 5m` at a
one-minute evaluation, so it arrives in minutes rather than in the six hours
`DeployImagePinned` needs or the thirty `ConfigDrift` needs. Its summary carries
`{{ $labels.stage }}`. And the way to cause it is already documented for a different
purpose — [§6.4 of the prod-hardening runbook](ops-prod-hardening.md#64-verifying-the-properties-rather-than-assuming-them)
points the backup job at a bucket that does not exist, so the dump still succeeds and only
the upload is refused. Nothing that serves a user is involved.

Rules to leave alone for this: anything whose cause is an outage (`AppDown`), anything that
requires real harm (`HostKernelOOMKill`), anything needing volume you would have to
manufacture (`MailDailyVolumeHigh`, the mail-abuse family), and anything whose `for:` is
measured in hours.

### 2.2 The procedure

Before touching anything, confirm the route still belongs to us — a message delivered from
an unmanaged built-in receiver looks identical in an inbox and goes to a stranger:

`GF_SECURITY_ADMIN_PASSWORD` is **not** set on your laptop. It lives in
`/opt/hamstrack/.env` on the box — read it there over SSM
(`sudo grep '^GF_SECURITY_ADMIN_PASSWORD=' /opt/hamstrack/.env`) and export it in the shell
you run the curl from, or pass it inline. Do not paste it anywhere it persists.

```bash
# Laptop: SSM port-forward to Grafana (ops-prod-hardening §4), then
export GF_SECURITY_ADMIN_PASSWORD='<the value from /opt/hamstrack/.env on the box>'
curl -su admin:"$GF_SECURITY_ADMIN_PASSWORD" localhost:3000/api/v1/provisioning/policies \
  | jq '{receiver, provenance}'
# expect exactly: { "receiver": "email", "provenance": "file" }

# And read the destination off the box rather than assuming it — this file is the
# only authority, and contactpoints.yml has no fallback address to fall back to.
grep '^OBS_ALERT_EMAIL_TO=' /opt/hamstrack/.env
```

Then fire it, on the box, over SSM:

```bash
aws ssm start-session --region eu-north-1 --target i-019fe684b25ad831f

# 1. Keep the real configuration, then point the job at a bucket that does not exist.
sudo cp /etc/hamstrack/backup.env /etc/hamstrack/backup.env.pre-alertproof
sudo sed -i 's/^BACKUP_S3_BUCKET=.*/BACKUP_S3_BUCKET=hamstrack-alert-proof-no-such-bucket/' \
  /etc/hamstrack/backup.env
grep '^BACKUP_S3_BUCKET=' /etc/hamstrack/backup.env

# 2. Run it THROUGH systemd, never as a bare command (ops-prod-hardening §6.3e says why).
#    The unit is Type=oneshot, so `systemctl start` returns only once the run has finished —
#    but a run that LOSES the flock (the nightly timer, or a previous run still wedged) exits
#    0 having touched nothing, and then the .prom below is the PREVIOUS run's file and you
#    record a pass that never happened. Stamp the time and prove the file is this run's.
started=$(date +%s)
sudo systemctl start hamstrack-backup.service; echo "systemctl exit: $?"
systemctl is-active hamstrack-backup.service
#    expect 'inactive' — a oneshot that has finished. 'activating' means it is still
#    running and every number below is stale; wait and re-read.
sudo journalctl -u hamstrack-backup -n 50 --no-pager
stat -c '%Y  %n' /var/lib/node_exporter/textfile_collector/hamstrack_backup.prom
#    that mtime must be >= $started. Older means this invocation wrote nothing at all —
#    lock loser, or a failure above the metrics — and the file describes a different run.
cat /var/lib/node_exporter/textfile_collector/hamstrack_backup.prom
#    expect last_status{stage="dump"} 1 and last_status{stage="upload"} 0,
#    a real hamstrack_backup_size_bytes, and the dump present locally.
```

Note the clock, because it is what makes the record worth anything: the metric has to be
scraped, the rule evaluates every minute, `for: 5m` must elapse, and the notification
policy's `group_wait` is 30 s. **Expect the message roughly six to eight minutes after the
run**, not immediately. If nothing has arrived twenty minutes later, the path is broken and
that is the finding — record it as a failed proof and treat it as a launch blocker.

Then put it back, and do not rely on the safety net:

```bash
sudo mv /etc/hamstrack/backup.env.pre-alertproof /etc/hamstrack/backup.env
grep '^BACKUP_S3_BUCKET=' /etc/hamstrack/backup.env
started=$(date +%s)
sudo systemctl start hamstrack-backup.service; echo "systemctl exit: $?"
systemctl is-active hamstrack-backup.service                                  # expect 'inactive'
stat -c '%Y  %n' /var/lib/node_exporter/textfile_collector/hamstrack_backup.prom  # mtime >= $started
cat /var/lib/node_exporter/textfile_collector/hamstrack_backup.prom
#    expect last_status 1 for both stages — and read it only if the mtime moved, or the
#    "recovered" you record is the metric the failing run left behind.
```

`BackupStale` would eventually catch a bogus bucket left in place, but it takes 26 hours and
until then nothing leaves the box. Restore the value in the same sitting.

**A resolved notification is a second, free proof.** Grafana sends one when the rule returns
to Normal, so the same inbox should receive a resolution shortly after the good run. Record
whether it arrived — a path that delivers firings and swallows resolutions leaves an
operator unable to tell a fixed problem from a forgotten one.

### 2.3 What "arrived" means, and the log

**Arrived means a human opened the mailbox that `/opt/hamstrack/.env` names and found the
message.** None of the following is a substitute, and each has already been mistaken for one
somewhere in this project's history:

- Grafana showing the rule **Firing**. That is the rule working, not the path.
- The contact point's **Test** button. It exercises the integration and bypasses both the
  rule and the notification policy, so it cannot see a lost route or a broken annotation.
  Useful as a cheap pre-check; not a proof.
- The mail provider reporting **delivered**. HD-197's `550` was visible only there, which
  makes the provider a good *second* source and a poor *only* one.
- A message in the **spam folder**. Record the folder; an alert nobody sees is not delivered,
  and if it lands in spam that is a finding with its own fix.

Record on the ticket, not here: the address itself. **This repository is public** — the log
row below says the address matched `/opt/hamstrack/.env` and nothing more.

> **Nothing below is a record.** Append a row **after** the proof has been walked, in the
> **past tense, with the date it happened**. A row written in advance, or a promise that the
> alert path will be tested, is not a row.

| Date | Rule | How it was caused | Firing at (UTC) | Mail received at (UTC) | Delta | Address matched `.env`? | Folder | Resolution mail? | Steps not walked | Operator |
|---|---|---|---|---|---|---|---|---|---|---|
| *(empty — the alert path has never been walked end to end)* | | | | | | | | | | |

---

## 3. Rehearsing the rollback — do this before the day

**Rehearse it after the release is deployed and before the first post**, in a quiet minute.
Rehearsing the *mechanism* on an older pair of versions proves less: this rollback crosses
migrations that constrain tables the older image writes, and §9.2 explains why that crossing
has consequences worth measuring rather than predicting.

Walk §9 exactly as written, with these additions, and time it. Steps 3 and 4 are the schema
probes (§9.2): each is one click, and each turns an inference about the schema into a
measurement.

**Create a rehearsal workspace on an account you own before you start, and do both probes
there.** These probes write real rows into production — an attachment in a real project, an
invitation to a real address — so no customer's workspace is a probe target, and no
colleague should receive a mail they did not ask for (invite an address you control). Clean
up as each step says; deleting the rehearsal workspace afterwards is the simplest way.

1. Note `/api/meta` before you start.
2. Do the pin, the `up -d`, and confirm `/api/meta` reports the previous version.
3. **Upload an attachment on the pinned image.** This is the `V26` probe (§9.2) and it is the
   whole reason the rehearsal happens after the deploy rather than before it. Record what
   happened, whatever it was. Attach the file to an issue in the **rehearsal workspace**, and
   **delete the attachment afterwards if it lands** — a successful probe leaves a real file
   in S3 and a real row in `workspace_storage_usage`.
4. **Invite an address that already has a pending invitation in the same workspace.** This is
   the `V22` probe (§9.2): in the **rehearsal workspace**, invite an address you control,
   leave the invitation unaccepted, and invite the same address again. Record the status — a
   `409` is the release's behaviour, a `500` is the regression. **Withdraw both invitations
   afterwards**, from the same members screen.
5. Log in, open a board, open an issue, create and delete a test issue — the same human
   smoke test the load run ends with.
6. Un-pin, `up -d`, confirm `/api/meta` reports the release version again.

**`Elapsed` is measured, from "I decided to roll back" to "`/api/meta` reports the previous
version".** It is the only version of that number that is not a guess. Take a second reading
for the un-pin, because a rollback you cannot come back from is half a procedure.

> **Nothing below is a record.** Append a row **after** each rehearsal or real rollback, in
> the **past tense, with the date it happened**.

| Date | Kind (rehearsal / real) | From → to | Elapsed to previous version serving | Attachment upload on the pinned image | Duplicate invite on the pinned image | Elapsed to un-pin and back | Steps not walked | Operator |
|---|---|---|---|---|---|---|---|---|
| *(empty — no rollback has been rehearsed against production)* | | | | | | | | |

---

## 4. Preconditions, checked before the first post

Each of these is a command whose answer you read, not a thing you believe. Most link out
rather than restating; a second copy of a procedure is what a fix round updates last.

| # | Precondition | How you know |
|---|---|---|
| 1 | The alert path has a row in §2.3 | Read it. No row, no launch — that is the point of the section. |
| 2 | The rollback has a row in §3 | Read it. |
| 3 | Grafana is **running**, not merely deployed | `docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml ps` — `up -d` exits 0 over a crash-looping Grafana, and a dead Grafana means every rule is silent. |
| 4 | The notification route is still ours | The `provisioning/policies` curl in §2.2. |
| 5 | Deployed configuration is in effect | [ops-prod-hardening §7](ops-prod-hardening.md#7-verifying-the-deployed-configuration) — the memory ceiling, the forwarded-for keying, the healthcheck, and every `hamstrack_config_drift` scope at 0. A setting in the repository is not a finding. |
| 6 | The release's own hand-set values are set | [release-checklist, "Releases that add a setting the deployment must set by hand"](release-checklist.md#releases-that-add-a-setting-the-deployment-must-set-by-hand) and the "resource default" section beside it. Not duplicated here. |
| 7 | Last night's backup succeeded | `cat /var/lib/node_exporter/textfile_collector/hamstrack_backup.prom`, plus a `manual/` copy if the release carries a migration that rewrites history ([release-checklist](release-checklist.md#releases-carrying-a-destructive-migration)). |
| 8 | Free disk | `df -h /` — `DiskFilling` fires below 15% free, and the swapfile already moved this box to within a few points of that. |
| 9 | `/opt/hamstrack/.env` passes through to the app | `grep -n 'env_file' /opt/hamstrack/docker-compose.prod.yml` — §8's lever depends on it, and the box's compose file is a question for the box. |
| 10 | The port-forward is open and you can read a dashboard | Grafana at `localhost:3000` (ops-prod-hardening §4). If the tunnel drops during the window, the alert inbox is your only channel. |
| 11 | Grafana annotation for the post | Add one at each post time. Graphs read afterwards are worth much more with the moment marked. |

---

## 5. The staged rollout

The staging exists to **measure the one number nobody can derive**: how many concurrent
users a post of a given reach produces on this deployment. That conversion is not in any
document, cannot be reasoned out, and decides whether the wide post is safe. The narrow
channel is the instrument.

### 5.1 Stage 1 — the narrow channel

Post to the smaller channel first. Choose it for these properties, not for its name: the
audience is small (low hundreds), the post can be **edited or withdrawn** after the fact,
and the platform will tell you afterwards **how many people saw it** — that reach figure is
the denominator and without it stage 1 measures nothing.

### 5.2 Window A — 60 minutes, minimum

Sixty minutes because it spans six evaluations of the slowest rules in the watch set
(`HighLatency` and `HostMemoryLow` are both `for: 10m`), and because the traffic peak of a
post arrives well inside it. **That last clause is an assumption, not a measurement** — if
the request rate is still climbing at minute 60, the window is not over; extend to 90 and
re-read.

Watch §6 continuously. At the end of the window:

- every row in the CONTINUE column, unbroken for the last 15 minutes → go to 5.3;
- any HOLD row → hold. Do not post anything further. Stay on the watch for another 30
  minutes and re-read;
- any STOP row → §7.

### 5.3 The arithmetic that decides the wide post

From stage 1, take:

- `reach₁` — the reach the platform reports for the narrow post;
- `peak₁` — the peak of `sum(rate(http_server_requests_seconds_count{job="hamstrack-app"}[5m]))`
  during window A.

Then `projected = peak₁ ÷ reach₁ × reach₂` for the wider channel's reach.

- `projected ≤ 20 req/s` → post.
- `20 < projected ≤ 32 req/s` → post, with §8's lever pre-typed in a terminal and the
  operator present for the whole of window B. 32 req/s is where this deployment was
  measured serving p95 364 ms.
- `projected > 32 req/s` → **do not post yet.** Above the measured capacity point the
  choice is to close the gap first (the instance resize, HD-189, deliberately deferred until
  publication) or to split the wide post into narrower ones and repeat window A. Posting
  into a projection above the plateau (~43 req/s) buys no extra users at all — throughput
  stops rising and latency multiplies — so the traffic you cannot serve is not traffic you
  lose gracefully.

If `reach₁` never arrives from the platform, the projection cannot be made. Say so and fall
back to the conservative path: treat the wide post as if `projected > 32`.

### 5.4 Stage 2 — the wider post, and window B

Window B is **two hours continuous**, then a check every hour to +8 h, then the alert path
carries it overnight, then a review at +24 h (which is also when the 24-hour mail-volume
signal becomes readable). Same watch set, same three verdicts.

---

## 6. The watch set

Every row has a value and a window, and says where the number comes from. Read the whole
table in under a minute: one row per signal, one column per verdict, left to right.

Panels: Grafana → **Hamstrack** folder → *App Overview* (rate, 5xx, p95), *Host &
Containers* (host memory), *Product* (registrations, verifications, email). Every expression
below also runs in **Explore → Prometheus**, which is what to use when a panel is not
showing what you need.

| Signal | Expression | CONTINUE | HOLD | STOP | Where the numbers come from |
|---|---|---|---|---|---|
| **5xx** | `sum(rate(http_server_requests_seconds_count{job="hamstrack-app",status=~"5.."}[5m]))` | 0 in the last 5 min, or one isolated error that does not repeat | ≥ 5 in 5 min | ≥ 20 in 5 min, **or** `HighErrorRate` fires | The measured baseline is **zero** — every stage of every load mix recorded zero 5xx. So a sustained 5xx is a regression and not noise. The outer bound is the rule's own: 5xx ratio > 5% for 5m. |
| **p95 latency** | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="hamstrack-app"}[5m])) by (le))` | ≤ 400 ms | 400–600 ms | > 600 ms for 10 min, **or** `HighLatency` fires (> 1 s / 10m) | 364 ms is the measured p95 at the capacity point (45 concurrent). 600 ms is the load harness's own pass threshold, breached at 60 concurrent (665 ms). 1 s is the alert. |
| **host memory available** | `node_memory_MemAvailable_bytes` | > 300 MB | 200–300 MB | < 200 MB — **do not wait out the rule's 10 m**; below **150 MB** act immediately | Typical available ≈ 341 MB, 7-day minimum 227 MB, so under 300 MB is already worse than routine. 200 MiB is `HostMemoryLow`. The load run's watchdog floor was 150 MB and it aborted at 147 MB with 4.99 s GC pauses. |
| **swap in use** | `node_memory_SwapTotal_bytes - node_memory_SwapFree_bytes` | < 128 MiB | 128–256 MiB | > 256 MiB, or rising steadily across 15 min | 128 MiB is `HostSwapInUse`. The load run went 51 MB → 275 MB while breaching. Total swap is 1023 MB, and it is an emergency buffer at `vm.swappiness=10`, not a memory tier. |
| **request rate** | `sum(rate(http_server_requests_seconds_count{job="hamstrack-app"}[5m]))` | ≤ 20 req/s | 20–32 req/s | > 32 req/s sustained 5 min | 32.4 req/s was measured at the capacity point; the plateau is ~43 req/s, where more concurrency buys nothing and costs 5× the latency. |
| **registrations** | `increase(hamstrack_users_registered_total[24h])` | < 200 | 200–400 | > 400 → hold the **wider post**, not the site | Each registration sends one verification mail. `MailDailyVolumeHigh` fires above 500 sends/24 h, derived from the provider's 3000/month quota — 500/day consumes the month in six days, and the first symptom of running out is that new users silently stop receiving links. |
| **verification funnel** | `increase(hamstrack_auth_email_verified_total[1h])` against `increase(hamstrack_users_registered_total[1h])` | ratio ≥ 0.5 with ≥ 5 registrations | ratio < 0.5 | ≥ 5 registrations and **0** verifications for 30 min | The zero is derivable and is the important half: nobody completing signup means the mail path is broken, and every person who arrives in that state is a person who cannot use the product. **The 0.5 is an assumption** — see below. |
| **the watcher itself** | `docker compose … ps` | grafana `Up` | — | grafana not `Up` → you are flying blind; fix that before anything else | `up -d` exits 0 over a crash-looping Grafana; nothing else reports it. |

**The threshold that is not derived, stated as such.** There is no measured baseline for
what fraction of Hamstrack signups verify their address — no window of real registration
traffic has ever been observed. 0.5 is a placeholder. What would replace it is the funnel
from stage 1 itself: registrations and verifications over window A, which becomes the
baseline for window B and for every launch after this one. Record both counts at the end of
each window whatever the verdict, because that recording is what turns this row into a
number.

**Cross-checks that decide what a breach means:**

- A p95 breach with host memory healthy and no 5xx is usually **mix**, not saturation — the
  reporting/searching endpoints measured p95 ≈ 1 s at 45 concurrent while browsing measured
  364 ms, and the panel aggregates them. The discriminator is host memory: memory is the
  resource that actually runs out on this box.
- An `EmailFailures` burst **at a deploy timestamp**, all rows never-attempted with
  `NEVER ATTEMPTED [SHUTDOWN_RESIDUE]` in `last_error`, is the deploy landing on a non-empty
  mail queue. It is real — those users were told to check an inbox nothing arrived in — and
  it is **not an outage** and not a reason to roll back. A rate that continues after the
  deploy is.
- A `137` exit is **any** `SIGKILL`, not an out-of-memory diagnosis. The field that decides
  is `.State.OOMKilled` (`docker inspect <container> | grep -i oomkilled`), and
  `HostKernelOOMKill` is the rule that counts the kernel's own decision.

---

## 7. Which move — the symptom decides, and picking wrong wastes the window

The ticket frames this as a pair. There is a third, it is the cheapest, and it is the one
most likely to be right on a launch day: **a setting is wrong**, and settings live in
`/opt/hamstrack/.env`.

| What you are seeing | What it is | Move |
|---|---|---|
| Latency climbing, host memory falling, swap rising, GC pauses in the JVM panel, request rate at or above the plateau — and **everything still works, just slowly, for everybody** | The box is losing. The release is not at fault; the machine is out of memory. | **§8 — close the door.** No deploy, no image change. |
| A clean refusal nobody asked for: `409 STORAGE_QUOTA_EXCEEDED`, a `422 STATEMENT_BUDGET_EXCEEDED` on a report, a 429 where a person should have been served | A ceiling set too low, not a defect. These appear in no error rate and no log. | **Change the value in `/opt/hamstrack/.env` and `up -d`.** `STORAGE_QUOTA_WORKSPACE_BYTES`, `DB_STATEMENT_TIMEOUT_MS`, the rate-limit budgets — each takes effect on the next request and nothing is cached. |
| 5xx concentrated on a route, a behaviour regression a user reports, an error naming code this release touched, or a container that will not stay up on the new image | The release is wrong. | **§9 — roll back the image.** |
| One 5xx that does not repeat; an `EmailFailures` burst at the deploy timestamp | Neither. | Record it, keep watching, do nothing. |

**Why picking wrong costs the window.** A rollback is minutes of recreated containers and
carries regressions of its own on the way back (§9.2) — spending it on a memory problem
leaves you with the same memory problem on an older image and a smaller set of
options. Closing the
door on a release defect leaves the defect serving every user already signed up while you
have stopped the only thing that was going well.

---

## 8. Close the door — no deploy

> **Written for the hosted box at hamstrack.com**, whose paths, instance id and compose
> files these commands hard-code. A self-hoster wants the same flag — it is documented in
> [`docs/self-hosting.md`](self-hosting.md) — applied to their own layout, not this one.

`PUBLIC_SIGNUP_ENABLED=false` stops `POST /api/auth/register` with **403 "Public
registration is disabled"**, before any mail budget is spent. `/api/meta` publishes the flag
and the SPA reads it, so the signup affordance disappears from the landing and login pages
rather than becoming a button that fails.

```bash
aws ssm start-session --region eu-north-1 --target i-019fe684b25ad831f
cd /opt/hamstrack

# Set or append — sed alone silently does nothing when the line is absent, and this
# variable is not in .env.prod.example, so on this box it very likely is absent.
grep -q '^PUBLIC_SIGNUP_ENABLED=' .env \
  && sudo sed -i 's/^PUBLIC_SIGNUP_ENABLED=.*/PUBLIC_SIGNUP_ENABLED=false/' .env \
  || echo 'PUBLIC_SIGNUP_ENABLED=false' | sudo tee -a .env
grep '^PUBLIC_SIGNUP_ENABLED=' .env

sudo docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d app

# Verify from OUTSIDE — a value read on the box is not the value a visitor meets.
curl -s https://hamstrack.com/api/meta | jq .publicSignupEnabled     # expect false
```

**What it costs, so none of it arrives as a surprise:**

- The app container is **recreated** — `env_file: .env` is read at container creation, not
  at request time. Expect a short window of 502s through Caddy while the new container
  reaches `healthy`, and every SSE stream reconnects.
- That restart can itself fire `EmailFailures` with `[SHUTDOWN_RESIDUE]` if the mail queue is
  not empty. Expected; see §6.
- **Invited people are locked out too.** Invitations are accepted by an authenticated
  account, so somebody invited who has no account can no longer create one. The remedy while
  the door is shut is a **system administrator** creating the account (Admin console →
  Users) — the system role `ADMIN`, which is what `/api/admin/**` is gated on
  (`hasRole("ADMIN")`), and **not** a workspace Owner. An Owner without that system role
  finds the console entry missing rather than the procedure failing, which is a confusing
  place to discover it. Know who holds `ADMIN` before the day.
- Logins, invitations to existing accounts, and everything inside the product are unaffected.

**Re-open deliberately.** `/opt/hamstrack/.env` is the one file no deploy replaces, so a
closed door survives every subsequent deploy by construction — and, unlike an image pin,
**nothing alerts on it**: `DeployImagePinned` exists for the pin, and there is no equivalent
for this flag. Set a reminder, and put the re-opening in the after-action list. See Open
questions.

---

## 9. Roll back the image

> **Written for the hosted box at hamstrack.com** — the SSM target, `/opt/hamstrack`, and
> both bundled compose files are this deployment's, not yours. A self-hoster pins
> `APP_IMAGE_TAG` the same way in their own `.env`
> ([`docs/self-hosting.md`](self-hosting.md)); §9.2 below is the part that applies to
> **everyone** pinning back across 0.18.0, whoever is hosting.

### 9.1 The procedure

`latest` only moves forward. The rollback is a **pin in `/opt/hamstrack/.env`**, the one
file no deploy touches — which is why it survives, and why every deploy afterwards goes red
until you un-pin or adopt it. The full reasoning, both override flags and their difference,
is in [release-checklist, "Rolling back"](release-checklist.md#rolling-back) and
[ops-prod-hardening §3](ops-prod-hardening.md#3-close-ssh-port-22-deploy-via-ssm); do not
re-derive it here under pressure.

Which tag: **the version production was serving before the launch deploy.** Every build
publishes `X.Y.Z` and `X.Y` alongside `latest`, so the GitHub Releases page names it. Do not
read it out of `/opt/hamstrack/.deployed-image-tag` — that file is the last tag anybody
*adopted*, which in normal operation is `latest` and is not the answer to this question.

```bash
aws ssm start-session --region eu-north-1 --target i-019fe684b25ad831f
cd /opt/hamstrack

# 0. What is serving now, so the rollback has a before-state.
curl -s https://hamstrack.com/api/meta

# 1. Pin it. IN .env — never as an exported variable, and not even for the pull below:
#    compose gives the process environment precedence over --env-file, so an exported
#    pin would be the tag that runs while the guard, the stamp and the drift check all
#    read the file and see something else. apply-config.sh refuses that combination
#    rather than deploying it. Editing .env changes nothing that is running.
grep -q '^APP_IMAGE_TAG=' .env \
  && sudo sed -i 's/^APP_IMAGE_TAG=.*/APP_IMAGE_TAG=<previous-tag>/' .env \
  || echo 'APP_IMAGE_TAG=<previous-tag>' | sudo tee -a .env
grep '^APP_IMAGE_TAG=' .env

# 2. Pull BEFORE bringing anything up — compose resolves the tag from the .env you just
#    edited. The deploy runs `docker image prune -f`, so the previous image is probably
#    not on the box any more, and a pull that fails must fail while the running stack is
#    still untouched.
sudo docker compose -f docker-compose.prod.yml pull app

# 3. Bring it up — BOTH -f files, always.
sudo docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d

# 4. Verify from outside.
curl -s https://hamstrack.com/api/meta          # expect the previous version
```

Un-pinning is the same edit with `latest` (or removing the line) and the same `up -d`.
`DeployImagePinned` will say so after six hours whichever way you leave it.

### 9.2 What the rollback does **not** restore

**A migration that has run does not un-run.** The image moves back; the schema does not. The
previous release (`v0.17.0`) stops at `V20`, so this rollback crosses everything from `V21`
to `V26` — and the property that decides what breaks is not how many there are but **which
of them constrain a table the older image still writes**. A migration that only adds a table,
an index or a wider column is invisible to the old image; one that adds a `NOT NULL` column
or a uniqueness rule turns one of that image's ordinary, previously-succeeding writes into an
error at commit. `ddl-auto=validate` catches none of this — it compares what the entities
declare and does not object to a schema carrying more — so **the old image starts cleanly and
fails later, on a write, in a path that looks unrelated to the rollback.**

The writes known to break on a pre-0.18.0 image, each with a one-click probe:

- **Attachment upload (`V26`).** `issue_attachments` gains **`workspace_id`, `NOT NULL`, with
  no default** (plus a composite foreign key and an index), and `workspace_storage_usage` is
  created, seeded and maintained by a row trigger. The old image's attachment entity does not
  know that column, so its INSERT omits a `NOT NULL` column with no default and the upload
  fails. Downloads and every other attachment path still work.
- **Re-inviting an address that already has a pending invitation (`V22`).**
  `workspace_invites_pending_email_uk` is a partial unique index over
  `(workspace_id, lower(email)) WHERE accepted_at IS NULL`. The duplicate pre-check and the
  translated `409 DUPLICATE_INVITE` shipped with it, in this release — the older image saves
  the invite with a plain `save()` and has nothing to translate the violation, so the insert
  raises at commit and the inviter gets a **`500`** where 0.17.0 previously created a second
  standing offer. **Inviting a teammate again is an ordinary launch-day action**, not a
  corner, and the workaround while pinned is to withdraw the existing invitation first.

The rest of the crossing is inert on the old image: `V21` and `V25` add a table and an index
it never reads, `V24` widens a column, and `V23`'s `users_email_lower_uk` refuses nothing the
old `UNIQUE (email)` did not already refuse, because 0.17.0 already folds the address with
`Locale.ROOT` before every write. **If a future release adds another `NOT NULL` column or
another uniqueness rule to a table an older image writes, it belongs in this list** — that is
the test, and it is the only one that does not go stale.

Each consequence above is inferred from the schema and the old code rather than observed,
which is why §3's rehearsal probes them. If one is broken while pinned,
the honest options are: accept it for the hours the pin is meant to last, or roll forward
with a fix. Treat the pin as a bridge, not a destination.

Also not restored by a pin:

- **Configuration.** The pin holds the image; nothing holds the files. If the incident was
  caused by a configuration change, the pin does not undo it — that is precisely why
  `apply-config.sh` refuses to sync onto a moved pin.
- **Data.** Rows a migration deleted or rewrote are a *restore* question, not a deploy
  question: [ops-prod-hardening §6](ops-prod-hardening.md#6-backups), and a production
  restore is gated by the erasure replay in §6.7.
- **`/opt/hamstrack/.env` and the `Caddyfile`**, which no deploy ever touched in the first
  place.

---

## 10. Who is watching, and for how long

Stated as roles and durations. A name goes stale; a role does not.

| Role | What they hold | When |
|---|---|---|
| **Release operator** | SSM credentials, the Grafana port-forward, and the ability to run §8 and §9 without asking anybody | Continuously through window A (60–90 min) and window B (2 h). Then a check every hour to +8 h. Then a review at +24 h. |
| **Inbox watcher** | The mailbox `OBS_ALERT_EMAIL_TO` names, open, on a device that will be looked at | From the first post until +24 h, including overnight. This is the channel that works when the port-forward is closed. |

On this deployment both roles are one person, and that is a single point of failure worth
naming rather than assuming away: if that person's machine or network dies, nobody else can
reach the box. See Open questions.

**What the operator does between checks:** nothing. The value of the window is that the
thresholds were decided in advance, so the job during it is to read a table and compare, not
to form an opinion.

**At the end of each window, record — whatever the verdict:** registrations and
verifications for the window, peak request rate, the reach the platform reported, worst p95,
minimum host memory, and any alert that fired. That is the input to §6's undecided
threshold and to the next launch's sizing, and it is knowable only while the window is open.

---

## Open questions

### Blocks the launch

- **The alert path has never been walked (AC#2 of HD-196).** §2 is executable; nothing has
  executed it, and it needs the box and the owner. **Recommendation:** run §2 before the
  first post and put a row in §2.3. An unproven alert path plus an unattended overnight
  window is the combination this ticket was filed about.
- **The rollback has never been rehearsed (AC#3).** §3 is executable and needs the same
  authorisation. **Recommendation:** run it after the release deploy and before the first
  post, including both schema probes, and put a row in §3.
- **The instance has deliberately not been resized, and the launch is aimed at a box
  measured at 45 concurrent users.** **Recommendation:** launch on the current box, because
  the staged rollout exists to find the conversion rather than to guess it — but treat §5.3's
  `projected > 32 req/s` branch as a real stop, not a formality, and hold HD-189 ready.
- **Nothing watches the signup door.** Once `PUBLIC_SIGNUP_ENABLED=false` is set it survives
  every deploy silently; there is no rule, no drift scope and no dashboard panel for it.
  **Recommendation:** launch without one and add the reminder to the after-action list; file
  a follow-up for a `hamstrack_config_drift` scope or an alert on the published
  `/api/meta` flag, since the mechanism for both already exists.
- **One person holds every credential.** **Recommendation:** decide before the day whether a
  second party gets standby access, or accept explicitly that an operator outage is an
  unmitigated risk for the window.

### Blocks the runbook being complete

- **The verification-funnel ratio is an assumption.** No window of real registration traffic
  has ever been observed on this product. **Recommendation:** keep 0.5 for stage 1, record
  the actual counts, and replace the number from window A's own funnel before window B.
- **The conversion from post reach to concurrent users is unknown**, and it is the input the
  wide post's safety depends on. **Recommendation:** the staged rollout is the measurement —
  do not skip stage 1 to save time, because skipping it is the same as guessing this number.
  **This is the highest-risk assumption on the page.**
- **A rolled-back image probably refuses writes this release made ordinary.** Attachment
  upload (`V26`) and re-inviting an address that already has a pending invitation (`V22`,
  a `500` where the release answers `409`). Each mechanism is certain from the schema and the
  older image's code; neither behaviour has been observed, and a migration added after this
  was written can extend the set. **Recommendation:** §3's
  rehearsal settles them, and this line should be rewritten as a measurement afterwards. If
  attachments do prove broken and a longer pin is ever needed, a `BEFORE INSERT` trigger
  filling `workspace_id` from `issues` would bridge it — a database change made under
  pressure, so decide it in advance or not at all. The invite regression has no bridge worth
  building and does not need one: withdraw the standing invitation and re-issue it, which is
  the same screen the inviter is already on.
- **Grafana's alert-state history is the source for "firing at"**, and its retention on this
  install has not been checked. **Recommendation:** take the timestamp during the proof
  rather than reconstructing it later.
- **Nothing here measures how long a launch post's traffic takes to peak.** Window A's
  60 minutes assumes it lands inside. **Recommendation:** record the shape of window A's
  request rate; one real curve replaces the assumption.
