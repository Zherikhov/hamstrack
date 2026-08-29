# HD-186 — the load-capacity harness

**This is the run procedure.** It is written so that somebody who did not build the harness
can execute a complete window from this file alone. If you have to ask the author a
question, that is a defect in this README and it should be fixed in the same change that
answered you.

**Spec:** [`docs/design/load-capacity-measurement-proposal.md`](../../docs/design/load-capacity-measurement-proposal.md).
Section references (§) throughout are to that document, which is the authority. Where this
README and the spec disagree, the spec wins and this file is wrong.

**What this measures:** at what concurrency each workload mix breaches its latency/error
target, and **which resource breached**, named with the metric that shows it. The number is
the smaller half; "the server is too small" and "the application serialises on something"
call for opposite responses, and today we cannot tell them apart.

**What this is not:** a fix for anything it finds. Findings become tickets. HD-182 is
already open and waiting for exactly this.

---

## 0. Read this before you read anything else

Everything in this section surprises somebody, and every one of them is deliberate. (No
count here on purpose: a leading number goes stale one entry before the list does, and this
list has already grown once.)

**It runs against production.** Not a clone, not staging. A clone measures a machine nobody
uses — different volume, different page-cache state, different neighbours, no edge in front
of it, and none of the other tenants of the same host competing for the same memory — and
would have produced a number with the same confident formatting and none of the validity.
The price is that a real box holding real users' data is deliberately pushed until something
breaks, and §5 of the spec is what buys that down: a completed snapshot, a rehearsed
teardown, published abort conditions, a recorded configuration fingerprint. **The rule that survives the ticket: the box measured
must be the box run, and the numbers are void the moment its configuration moves.**

**It does not turn off a single limiter.** Not the per-IP auth budget, not the per-principal
search and report budgets, not the rank-rebalance cooldown, not the statement timeout. A run
with the throttles disabled measures a product nobody ships. The harness reaches saturation
with **enough distinct principals** instead (one account per virtual user), and what the
limiters do is a **result**, not an obstacle.

**It is present on production whether or not it is permitted to run there.** `ops/` is a
synced path, so every deploy places this directory at `/opt/hamstrack/ops/loadtest/`. That is
intentional — it is how the box-side capture scripts arrive without a hand-copy. Presence is
not permission: the generator, the teardown and the revocation all refuse without
`LOAD_CONFIRM` set to today's UTC date and `LOAD_TARGET` naming the database the server
itself reports; the first two refuse again on a Flyway version mismatch.

**Nothing it writes goes inside that synced directory.** A synced directory is replaced
*wholesale* by every deploy, its differing contents are copied into the last five
`.config-backup/` snapshots, and a file added to one raises
`hamstrack_config_drift{scope="files"}` — which precondition 3 requires to read zero and
which `ConfigDrift` (`for: 30m`) turns into part of the breach bar. A `config.env` or a
`results/` in here would put the load password in five retained backups **and** make it
impossible to record any stage as passed. So the configuration lives at
`/opt/hamstrack/.loadtest.env` and the output under `$LOAD_RESULTS_DIR` (`/var/tmp/hd186`).

**A run that only confirms what was predicted has been run badly.** Every probe in §4.6 has a
stated way to come out *against* the prediction, and the results must report the measured
mean connection-hold time per class regardless of how the predictions land — that number is
new either way.

---

## 1. What is in here

```
ops/loadtest/
├── README.md                  this file — the run procedure
├── RESULTS-TEMPLATE.md        copy to RESULTS-<date>.md at the start of the window
├── config.env.example         every knob, with the reasoning. Copy it OUT of the tree to
│                              $LOAD_CONFIG (/opt/hamstrack/.loadtest.env) — never to
│                              ops/loadtest/config.env, which a deploy would back up
│
├── fixture/                   the dataset: generate, verify, tear down, revoke, rehearse
│   ├── lib.sh                 shared helpers + the four guards
│   │                          (confirm / flyway / TARGET / tenancy)
│   ├── generate.sh            the entry point. Refuses without confirmation, a named
│   │                          target, schema match, a run id, a bcrypt hash, and free disk
│   ├── revoke.sh              makes every load account unusable — refresh rows, password
│   │                          hash AND status=DISABLED, which is what stops a live JWT.
│   │                          Independent of teardown,
│   │                          and the FIRST step of the abort path
│   ├── 10-generate.sql        the generator. One transaction; rolls back wholesale on error
│   ├── 20-resync.sql          issue_seq resync, VACUUM ANALYZE, row counts, MEASURED size
│   ├── verify-api.sh          reads the fixture back through the REAL API (§4.2 guard 3)
│   ├── teardown.sh            entry point: guards, delete, then prove completeness
│   ├── teardown.sql           delete BY TENANCY in foreign-key order — never by inventory
│   ├── completeness.sql       the category assertion. Also the tripwire
│   ├── completeness.sh        runs it and turns it into an exit code
│   └── rehearse.sh            §5.1 precondition 8: the whole cycle on a DISPOSABLE
│                              database, including proving the completeness check can FAIL
│
├── k6/                        the load generator (runs on the generator instance)
│   ├── lib/config.js          base URL, ladder position, think time, the ramp/hold phase tag
│   ├── lib/classes.js         the endpoint classes and the §4.3 targets, as thresholds
│   ├── lib/auth.js            one account per VU; refresh once on 401, then fail loudly
│   ├── lib/fixture.js         resolves every id through the API in setup() — none is typed
│   ├── lib/canary.js          the tenancy canary
│   ├── mint-tokens.js         pre-flight, throttled to 10 logins/min -> tokens.json
│   ├── refresh-tokens.js      BETWEEN STAGES: spends the whole file's single-use refresh
│   │                          chain in one pass (~1 min) and rewrites tokens.json
│   ├── browse.js              mix 1: what most people actually do (+ one held SSE per VU)
│   ├── report-search.js       mix 2: the expensive surface
│   ├── write.js               mix 3: the only mix that takes row locks
│   ├── probes.js              P1 entitlement · P2 report heap · L limiter
│   ├── soak.js                constant-arrival-rate confirmation of the ladder's figure
│   └── run-ladder.sh          drives a mix up the ladder; one k6 run per stage
│
└── capture/                   box-side observation (runs ON the box)
    ├── fingerprint.sh         §4.9 — from command output, never from what a file says
    ├── capture.sh             start|stop the Tier 1 samplers (actuator, pg, host, docker)
    ├── watchdog.sh            the machine-checkable abort conditions
    ├── export-prometheus.sh   Tier 2 — this box only
    └── alert-rules.sh         lists the provisioned rules that are half the breach bar
```

**Two machines.** `fixture/` and `capture/` run **on the box**. `k6/` runs on a **separate
generator instance** in the same region. Running the generator on the box under measurement
would make it the experiment's largest confounder — it competes for the same CPU and the
same memory as the thing being measured, and nothing in the resulting numbers says so.

**Every command below is prefixed `bash`, and that is not a style choice.** This repository
has `core.filemode=false`, so shell scripts are stored `100644` and arrive on the box without
an execute bit — every existing `ops/` script is invoked the same way (`bash
$SYNC/ops/deploy/apply-config.sh …` in `deploy.yml`, `install -m 0750 …` in the hardening
runbook). Dropping the prefix gives `Permission denied` on a freshly deployed box and works
fine on the laptop where someone once ran `chmod +x`, which is the worst shape of
instruction: correct for its author and broken for its reader.

---

## 2. Preconditions — every one is a hard gate

A "no" **postpones the window**. It does not add a caveat. (§5.1)

| # | Gate | How to check |
|---|---|---|
| 1 | **HD-199 merged and the container memory limit IN FORCE**, watched 48 h with no exit `137` and no `HighLatency` | `docker inspect … .HostConfig.Memory` — read it back from the CONTAINER, never from `.env`. `.env` said `APP_MEMORY_LIMIT=1g` for weeks while nothing read it and the real limit was `0`. |
| 2 | **No imminent HD-189** (resize, or moving observability off the box). If scheduled within a fortnight, do it first | ask |
| 3 | **`hamstrack_config_drift` reads 0 for every scope** and `.deployed-sha` matches the released commit | `capture/fingerprint.sh` prints both |
| 4 | **An EBS snapshot COMPLETED** — not started — and its id recorded | AWS console / CLI. This is the owner's stated condition and the only real undo. |
| 5 | **Free disk ≥ 5 GB and ≥ 3× the projected fixture** | `df -h`. The volume was grown 8 → 20 GiB for this; `generate.sh` checks it too. |
| 6 | **The backup job's last success is recent** | `hamstrack_backup_last_success_timestamp_seconds` — a second recovery path that is not the snapshot |
| 7 | **The fixture is generated, verified through the API, `VACUUM (ANALYZE)`d, and has settled overnight**; row counts recorded | §3 below |
| 8 | **The teardown has been REHEARSED** on a **disposable** database, and all four completeness tripwires passed | `fixture/rehearse.sh`. A teardown first attempted on production is not a teardown. It refuses any database holding an account that is not `@load.invalid`. |
| 8b | **`fixture/revoke.sh` has been run once, in the rehearsal**, and **every count it prints reads 0** | The abort path's first step. "Has read it" is not "has run it" — same rule as 9. Phrased as a property because the number of counts has already changed once: the third, `AFTER_ACTIVE`, is the only one that speaks for live *access* tokens, and it was added after this line said "two". |
| 9 | **A 1-VU dry run against production has passed, INCLUDING THE ABORT PATH** — the operator has actually run the abort command and seen a run stop | §5 below. Not "has read the command". Has run it. |
| 10 | **The window is agreed and announced**, with an explicit statement that the instance may be slow or unavailable, and a named person watching Grafana with the abort command ready | — |
| 11 | **Nobody is mid-onboarding** | The instance has a handful of real users. Check that none of them is depending on it during the window rather than assuming. |

---

## 3. The day before: generate the fixture

Generation happens **the day before the window**, followed by `VACUUM (ANALYZE)` and an
overnight settle. A run against freshly bulk-loaded tables with no statistics measures a
**cold planner** — a real phenomenon, and not the one being asked about.

**On the box:**

```bash
cd /opt/hamstrack/ops/loadtest

# The configuration lives OUTSIDE this directory — ops/ is synced, and a file added to a
# synced directory lands in five deploy backups and raises the drift check that
# precondition 3 requires to be zero. config.env.example explains it at length.
install -m 0600 /dev/null /opt/hamstrack/.loadtest.env
$EDITOR /opt/hamstrack/.loadtest.env        # BOX half only — see "The load password"

# The fixture scripts source it themselves ($LOAD_CONFIG). Everything on the command line
# below wins over it, which is why the two confirmations are typed here and not stored.
LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack bash fixture/generate.sh
```

It will refuse, loudly and with the reason, if: `LOAD_CONFIRM` is missing or not today;
`LOAD_TARGET` does not equal the database name the server itself reports; the database's
Flyway version is not the pinned one; `LOAD_RUN_ID` is missing; `LOAD_PASSWORD_HASH` is
missing or is not a bcrypt hash; free disk is short; or a load fixture already exists.

**`LOAD_TARGET` is the guard that answers *which box*.** The other three all answer "may
this run" — `LOAD_PSQL_MODE`, `LOAD_PG_CONTAINER` and `LOAD_DB_DSN` *choose* the database
and nothing read them back, so nothing could tell a scratch database from production. This
one compares what you typed against `current_database()` read over the same connection, and
prints the resolved name, the database size and **how many accounts on it are not
`@load.invalid`** before acting. A name is not an identity — two databases called
`hamstrack` answer it identically — but acting on a database *nobody named* is no longer
possible, and that is what happened.

**It holds one transaction across ~750 000 inserts.** That is deliberate (a failure at row
700 000 leaves the database as it found it) and it is not free for anyone else: an open
transaction pins the `xmin` horizon, so for its duration **autovacuum can reclaim nothing
anywhere in the database**, including in real tenants' tables. `generate.sh` prints
`pg_stat_activity`'s open transactions first, so generating beside somebody else's
forgotten one is a decision rather than a discovery.

**Record from its output**, into `RESULTS-<date>.md`: the pre-generation database size, the
per-workspace row counts, the distribution checks, and the measured on-disk size. The
proposal's "300–400 MB" is an estimate; this is the measurement, and where they disagree the
measurement wins.

**Then verify through the real API** — this is the guard that catches a generator which
drifted from the *entities* in a way the schema still accepts:

```bash
bash fixture/verify-api.sh
```

It asserts a status **and something about the shape of each body**, because a 200 carrying an
empty list is how a broken fixture passes a status-only check. It also uploads three real
attachments through `FileStorage` (the fixture writes attachment *metadata* only, on
purpose) and asserts the tenancy canary's premise before the run rather than after.

**If `verify-api.sh` fails, do not open the window.** A fixture the API cannot read produces
a measurement of error paths.

**You do not need to look up workspace B's id.** It used to be resolved by hand here and
pasted into the configuration, where a value from a *previous* fixture would make every
canary request 404 for the boring reason and the run would record a clean tenancy result for
a check that could not have failed. It is now resolved in `setup()` from a workspace-B
principal and asserted to be both **foreign** to the A principal and **real** (that B
principal gets 200 on it). Setting `CANARY_WORKSPACE_ID` is optional and is a *cross-check*:
a mismatch is fatal.

Record `LOAD_RUN_ID` in `RESULTS-<date>.md` and put it in **both** machines' configuration —
it is part of the workspace slugs, so it is how the teardown and the mixes find this fixture.

### The load password

Every load account shares one password, and it is **not in this repository**. A bcrypt hash
committed here would be a credential in a source-available repository with a publicly known
plaintext, and every account reachable with it would exist on production during a window in
which the instance is deliberately being pushed to its limits.

**Generate it. Do not choose one.** The residual is not bounded by the window: if the run is
aborted the accounts stay usable until `fixture/revoke.sh` is run, so this string is
protecting production accounts for as long as it takes somebody to notice.

```bash
PW=$(openssl rand -base64 24)                              # 32 chars; the floor is 24
htpasswd -bnBC 12 "" "$PW" | tr -d ':\n' | sed 's/^\$2y/\$2a/'   # strength 12
```

`mint-tokens.js` and `verify-api.sh` both refuse a password below the floor — those are the
two places the plaintext first arrives, one per machine.

**Split by machine.** `LOAD_PASSWORD_HASH` goes in the **box's** file; `LOAD_PASSWORD` goes
in the **generator's**. The box does not need the plaintext: `verify-api.sh` is the only
thing there that logs in, and it **prompts** for it (`read -rs`) for one command rather than
reading it off disk. If `python3` and `bcrypt` happen to be available and the plaintext is
in the environment, `generate.sh` checks the pair *before* writing 750 000 rows; otherwise it
says out loud that the pair is unverified until that login.

### What the fixture looks like

Two workspaces, and the second is not decoration: **B is the tenancy canary's foreign
target**, and it makes "cost versus tenant size" a comparison *inside* one run rather than a
claim between two.

| | A ("large tenant") | B ("typical tenant") |
|---|---|---|
| projects | 4, sized 80/20 (25 000 / 9 000 / 4 000 / 2 000) | 2 (1 500 / 500) |
| issues | ~40 000 | ~2 000 |
| issue history | ~8/issue → ~336 000 | ~16 000 |
| comments | ~2.9/issue, long tail to 85 | ~5 000 |
| custom field values | 2/issue | 2/issue |
| labels | 400 | 60 |
| components / versions | 40 / 30 per project | 10 / 10 |
| sprints | 20 open (1 ACTIVE + 19 FUTURE) + 40 completed per project | 5 + 10 |
| members | 210 load + 2 canary | 15 |

Roughly **750 000 rows**, an estimated **250–350 MB** with indexes. Numbers that are not
obvious:

- **25 000 issues in one project exceeds `REPORTS_MAX_ROWS` (20 000) on purpose** — the
  row-level reports must actually hit their cap and set `meta.truncated`, because the capped
  case is the expensive case and the one probe P2 is about.
- **40 000 is 80× `BOARD_MAX_ISSUES`**, so the board's cap does real work.
- **400 labels** is under `MAX_LABELS_PER_WORKSPACE` (1000) and large enough that loading the
  whole catalog on every search is measurable. The full 1000 would measure the *cap*.
- **20 open sprints** is exactly `AGILE_MAX_OPEN_SPRINTS`, so the planning view assembles its
  full `(20 + 1) × 300` budget.
- **210 workspace-A load members** makes the full member scan inside `ResolutionContext`
  non-trivial, and — the reason for the number — covers every VU k6 *allocates* at the
  ladder's top stage. `browse` declares three scenarios (browse at `VUS`, sse at `VUS`,
  canary at 1), so a 100-VU stage instantiates **201** VUs and each needs its own principal.
  The **2 canary members** are a separate address family (`load-ac-NNN`) that no load VU can
  draw. It also gives
  more distinct principals than the ladder's 100-VU top stage needs.

**Distribution matters more than volume, and it is where a synthetic fixture goes wrong.**
Status is weighted to done (~65%), history clusters in the trailing 90 days with a long tail
behind it, comment counts are long-tailed, text lengths are drawn from a distribution rather
than being a filler string, and project sizes are 80/20. A uniform fixture makes every query
uniformly cheap and every plan the same plan — and is systematically wrong in a direction
nothing in the run reveals. `20-resync.sql` **prints the measured distributions**; read them.
They are the claim, and the first version of the generator satisfied every row count while
getting four of them silently wrong.

---

## 3b. The generator instance and k6

Created for the window and **terminated after** it, as part of the by-hand teardown list.

**Terminating it revokes nothing.** It destroys the *client's* copy of `tokens.json`; the
refresh tokens in that file remain valid server-side for thirty days and the accounts keep
working with the shared password. Revocation happens on the box, with `fixture/revoke.sh`.
Three places in this harness used to say otherwise, which made a hurried abort feel handled.

- **Sized comfortably larger than the target**, on purpose: a `c7i.large`-class instance is
  over-specified so the numbers can be *shown* to be about the target and not about the
  generator. Its own CPU and memory are captured for the same reason.
- **Same region, hitting the origin directly.** Not on the box (a load generator on a 2-vCPU
  host with ~341 MB available is the largest confounder there is) and not through Cloudflare.

**Pin k6 to an exact version and record it in the fingerprint.** "Whatever `apt` had that
day" makes a re-run a different experiment.

```bash
K6_VERSION=v0.54.0        # pin it; record it; change it deliberately
curl -fsSL "https://github.com/grafana/k6/releases/download/${K6_VERSION}/k6-${K6_VERSION}-linux-amd64.tar.gz" \
  | tar xz --strip-components=1 -C /usr/local/bin "k6-${K6_VERSION}-linux-amd64/k6"
k6 version
```

**Licence, because this project is licence-sensitive.** k6 is **AGPL-3.0**. It is invoked as
a separate binary and nothing of it is linked into or distributed with Hamstrack, so it
creates no obligation on Hamstrack's **Elastic License 2.0** terms. The scripts in
`ops/loadtest/` are ours and carry this repository's licence. **Verify the licence at the
version you actually pin** rather than trusting this paragraph — it is a statement about
`v0.54.0` made by someone who was not looking at your tarball.

---

## 4. Pre-flight (30 minutes, at the start of the window)

**On the box:**

```bash
cd /opt/hamstrack/ops/loadtest

# FIRST: read the container names off the running box. Compose prefixes them with its
# project name, which differs between a checkout directory and /opt/hamstrack, and the
# samplers reach every subject through `docker exec <name>`. capture.sh now refuses to
# start on a name that does not exist — before that, a typo produced a whole window of
# HD186_SCRAPE_FAILED discovered while reading the results.
docker ps --format '{{.Names}}\t{{.Image}}'
# The LOAD_-prefixed names, not the bare ones. capture/lib-config.sh resolves
# `PG_CONTAINER := ${PG_CONTAINER:-${LOAD_PG_CONTAINER:-…}}`, so a bare PG_CONTAINER WINS
# over LOAD_PG_CONTAINER — the opposite of what the fixture side documents. The template used
# to ship both; it now ships only these, so editing the line the comment calls "the source"
# changes both halves.
export LOAD_APP_CONTAINER=… LOAD_PG_CONTAINER=… LOAD_PROM_CONTAINER=…

# LOAD_CAPTURE_MODE IS PINNED TO `docker` FOR THIS WINDOW, AND `host` IS UNEXERCISED.
# `host` is a real code path — it is what a self-hoster running the JAR under systemd would
# use — but it is the only path that runs a local `psql`, so it is the only one where a
# database credential can reach a command line, and its fail-fast was broken until
# 2026-08-29 in a way that fired *precisely when the actuator was working*. Nothing in this
# window needs it. Record that it was not exercised.
export LOAD_CAPTURE_MODE=docker

W=${LOAD_RESULTS_DIR:-/var/tmp/hd186}/window     # NOT ./results — see §0
install -d -m 0700 "$(dirname "$W")" "$W"        # the box side writes here too, and /var/tmp is 0755
bash capture/fingerprint.sh   "$W"
bash capture/alert-rules.sh                      # the roster that is half the breach bar
bash capture/capture.sh start "$W"               # 5 s samplers: actuator, pg, host+SWAP, docker
bash capture/watchdog.sh      "$W" &
```

Then leave it **idle for ten minutes** to establish a baseline.

**On the generator:**

```bash
set -a; . /opt/hamstrack/.loadtest.env; set +a   # the GENERATOR half: BASE_URL, password

# THE RESULTS DIRECTORY AND THE TOKEN FILE ARE PRIVATE, AND NOTHING CREATES THEM THAT WAY.
# k6's handleSummary writes through os.Create (0644) and /var/tmp is world-readable and
# sticky (0755), so without these three lines ~227 live access tokens and ~227 THIRTY-DAY
# refresh tokens are readable by every local account on this machine. `install -d` also
# CREATES the directory, which nothing else on the generator side does — the first mint
# would otherwise fail at the write.
umask 077
install -d -m 0700 "${LOAD_RESULTS_DIR:-/var/tmp/hd186}"

export TOKENS_FILE="${LOAD_RESULTS_DIR:-/var/tmp/hd186}/tokens.json"   # ABSOLUTE, OUTSIDE THE REPO
k6 run k6/mint-tokens.js -e BASE_URL="$BASE_URL" -e TOKENS_FILE="$TOKENS_FILE"
chmod 600 "$TOKENS_FILE"
```

`run-ladder.sh` **refuses to start a stage** unless `stat -c %a "$TOKENS_FILE"` reads `600`,
re-checked before every stage rather than once at startup, and it re-applies the mode after
every chain advance — `refresh-tokens.js` rewrites the file through the same `os.Create`.
The refusal names the two `chmod`s above, so an operator who skipped them is told what to
run rather than being told to read this section.

**`TOKENS_FILE` must be an absolute path, and it lives outside the repository.** Two separate
reasons:

* `k6`'s `handleSummary` writes relative to the **working directory**; `open()` inside
  `lib/auth.js` resolves relative to **the script that calls it**. Two bases for one name is
  how a `tokens.json` minted from the harness root ends up one directory away from where the
  mixes look. `mint-tokens.js` now **refuses** a relative path rather than defaulting to one
  that is right from one directory and wrong from another.
* it is a **credential file** — access tokens accepted on a signature check alone, refresh
  tokens good for thirty days. `config.env` and `results/` were both moved out of the synced
  `ops/` tree for weaker reasons than that, and this one file was left inside it. It defaults
  to `$LOAD_RESULTS_DIR/tokens.json`, and `tokens.json` is now gitignored **unanchored** —
  the old pattern only covered `ops/loadtest/**`, so a file minted from the repository root
  was not ignored at all.

**`LOAD_PASSWORD` is exported, not passed as `-e`.** k6 inherits the environment, and an
argument list is readable through `ps` by anyone on the machine — a needless second place
for the credential to exist. Same reason `verify-api.sh` puts its bearer token in a
mode-`0600` curl config file instead of on thirty command lines.

This takes about **twenty-three minutes** (210 + 2 + 15 logins) and that is deliberate. `/api/auth/login` is in the auth
filter's URL set at `RATE_LIMIT_AUTH_IP_PER_MINUTE` (15/min) keyed on the peer, and every
login comes from one generator address, so minting throttles itself to **10/min** with a third
of the budget left over for the real users who are still using the site. **Raising the limiter
for the window is rejected** — it changes the configuration under measurement.

It doubles as a smoke test: if the instance cannot serve the whole pool's sequential logins,
the window is over before it opened.

### The refresh chain, and why a ladder needs `refresh-tokens.js`

`/api/auth/refresh` **rotates** — `AuthService.refresh` deletes the presented row and issues a
new one — so **a refresh token in `tokens.json` can be spent exactly once, ever, by exactly
one holder.** Every stage of the ladder is a **separate k6 process seeded from that same
file**, and the ladder runs ~66 minutes against 30-minute access tokens.

The consequence used to be invisible: the first stage to cross the half hour refreshed,
rotating every account's token, and every later stage then presented a string the server had
already deleted → 400 → `hs_auth_failures` → abort. **The upper half of the ladder, where the
capacity number comes from, was unmeasurable, and no summary said so.**

The invariant is now stated and enforced: **one refresh per account, ever, per
`tokens.json`.**

* `run-ladder.sh` **refuses** to start a stage that would end after the expiry
  (`mintedAt` + `accessTokenLifetimeSeconds`, read from the file itself).
* **Do not run `refresh-tokens.js` twice over the same file.** It rewrites `tokens.json` in
  place, so a second run against a file whose rewrite did not land — an interrupted pass, a
  copy restored from somewhere, a path typo — presents the *already spent* half again (400,
  and the whole run fails) while spending the untouched half for nothing. The repair for
  either is the same and it is not another refresh: re-mint.
* With `LOAD_AUTO_REFRESH=1` (the default) it first runs `k6/refresh-tokens.js`, which spends
  the whole file's chain in one pass and rewrites it. That costs about **a minute**, because
  `/api/auth/refresh` is deliberately outside the auth filter's URL set — where re-minting
  costs twenty-three. **The pause is logged; record it**, it is time the instance spent
  unloaded between two stages that are compared with each other.
* The in-flight refresh inside `authed()` is now a **safety net, not the plan**. If it fires,
  that account's entry is already dead and the run says so loudly.

```bash
# by hand, between mixes:
k6 run k6/refresh-tokens.js -e BASE_URL="$BASE_URL" -e TOKENS_FILE="$TOKENS_FILE"
```

---

## 5. The abort path — run this once, before you need it

Precondition 9 is not "has read the command". Start a 1-VU run, stop it, watch it stop:

```bash
VUS=1 RAMP=0s HOLD=1m k6 run k6/browse.js \
  -e BASE_URL="$BASE_URL" -e TOKENS_FILE="$TOKENS_FILE"
  # the canary's target is resolved in setup() from tokens.json's B pool, not passed in

# in another shell, THE ABORT COMMAND:
pkill -INT -f 'k6 run'
```

**`-INT`, never `-KILL`.** k6 stops the scenarios, runs `handleSummary` and writes the partial
results, so an aborted stage still produces evidence. A `SIGKILL` discards the stage's
measurements — a second loss on top of whatever caused the abort.

### Abort conditions (§5.3)

Checked by `capture/watchdog.sh` on the box (1, 2, 3, 4, 6), by a k6 threshold on the
generator (5), and by the operator (7, 8, 9). **The watchdog writes a stop file; it cannot
kill k6, because it is on the other machine. The operator runs the abort command.**

1. **Free disk below 500 MB — on the box.** The only failure here that can damage real data.
   Checked most frequently, and **only by the watchdog**: `run-ladder.sh` checks the
   *generator's* disk, which is a different machine, a different filesystem and a different
   consequence (a truncated capture, not damaged data). This README claimed the two were
   redundant two lines after saying the watchdog is on the other machine; they never were,
   except while the harness was rehearsed on one host. **If the watchdog dies, condition 1
   is unwatched — restart it and say so in the run record.** If the watchdog's stop file is
   reachable from the generator (shared mount, rsync, SSM copy), point `LOAD_ABORT_FILE` at
   it and `run-ladder.sh` will stop between stages on it.
2. **Any container exits** other than by our own action. Exit `137` is a kernel OOM kill — not
   an `OutOfMemoryError`, and absent from every application log.
3. **`pg_up` is 0**, or `AppDown` fires.
4. **Host available memory below 150 MB, or swap used above 512 MB.** These three numbers —
   with condition 1's 500 MB — are `DISK_FLOOR_MB`, `MEM_FLOOR_MB` and `SWAP_CEILING_MB`,
   set in `config.env.example`. They decide when the window ends early, so they belong in
   the configuration an operator reads rather than only in `watchdog.sh`'s defaults.
5. **The tenancy canary returns anything other than 404.** Stop, preserve everything, and
   treat it as a **security incident**. This is the one abort that is not about capacity.
   **The evidence is itself leaked tenant data.** `canary.js` writes up to 200 characters of
   the foreign response body into the stage log so the leak can be characterised at all —
   which is the right trade, and it means `stage-<N>vu.log` and everything else in the
   results directory now contain one tenant's data disclosed to another. Preserve them, and
   handle them as such: do not paste them into a ticket, a chat or a commit. Attach them the
   way an incident's evidence is attached, not the way a run record is.
6. **`hamstrack_role_scope_violation_total` increases.** A data-integrity signal that must
   never be masked by "we were load testing".
7. **The real-user probe degrades**: any 5xx, or two consecutive minutes above 5 s p95, on a
   low-rate scripted journey against a *real* workspace. Run by hand, deliberately not
   automated here — it uses a real account against real tenant data, and a script in this
   repository that did that would be a script that could be pointed at a customer.
8. **`CPUCreditBalance` below 25% of its starting value — IN `standard` MODE ONLY.** In
   `unlimited` mode the instance never throttles (it bills surplus instead) and this
   condition does not exist. **Read the mode in the fingerprint before the window** — it is
   a per-instance setting anyone with the console can change, and `RESULTS-TEMPLATE.md`
   carries it as a named checkbox because a condition that is retired by an unread value is
   a condition nobody is watching.
9. **The operator's judgement.** Written down as a condition on purpose, so that *using* it is
   following the procedure rather than departing from it.

Conditions that stop the **sample** but not the window: k6 `dropped_iterations` > 0, or
generator CPU above 70%. Discard the stage, do not escalate further, note it in the results.

### Alerts you should EXPECT to fire

`HostMemoryLow` (200 MiB available) and `HostSwapInUse` (128 MiB swap) are provisioned
deliberately **above** the abort thresholds here, so on a healthy run they are the **warning
that precedes the abort**, not an incident. **Do not silence them for the window** — that
loses the only host-side signal the run has. Note the times they fired.

`HostKernelOOMKill` is the exception: it is **not** expected, and if it fires **the run is
already invalid**. Something on the box was killed, and a capacity number measured across a
kill measures the kill.

---

## 6. The window, in order (§5.2)

| Phase | Duration | Command |
|---|---|---|
| Pre-flight | 30 min | §4 above |
| Browsing mix | ~45 min | `bash k6/run-ladder.sh browse` |
| Settle | condition-based | see below |
| Reporting & searching | ~45 min | `bash k6/run-ladder.sh report-search` |
| Settle | condition-based | |
| Writing | ~45 min | `bash k6/run-ladder.sh write` |
| Probes | ~30 min | `k6 run k6/probes.js -e PROBE=p1` then `p2`, then `l` (needs `BASE_URL` and `TOKENS_FILE` in the environment, like every other invocation here) |
| Soak | 15 min | `k6 run k6/soak.js -e MIX=<mix> -e RATE=<observed> -e RAMP=0s` |
| Teardown | 45 min | §7 |
| Verification & buffer | 45 min | §8 |

**At T-15 minutes from the window's hard end, stop escalating regardless of where the ladder
is.** An unfinished ladder is a partial result; an overrun window is an incident.

**The settle between mixes ends on a CONDITION, not a clock:** heap-after-collection, Hikari
active connections and the PostgreSQL backend count have all returned to their pre-mix
baseline. `run-ladder.sh` pauses a floor of 60 s and then tells you to confirm on Grafana. A
clock is not the condition.

### How a stage passes

> The reported capacity for a mix is the **highest completed stage at which every §4.3 target
> held for the whole hold period and no provisioned alert rule's condition was met for its
> `for:` duration.** The breach stage is the next one up, and the report names the resource
> that breached using §4.8.

Two halves, and `run-ladder.sh` only decides the first:

- **k6's exit code** answers "did every target hold for the whole hold". Each stage is its own
  k6 run with its own thresholds, and requests made during the ramp are tagged `phase:ramp`
  and excluded — which is what stops a stage breaching on the harness's own warm-up.
- **You** answer "did any provisioned alert rule meet its condition". `capture/alert-rules.sh`
  lists them, read from the file so the roster cannot go stale. Check Grafana.

#### A non-zero exit is not automatically a capacity number

Six thresholds can end a stage and **only some of them say anything about the product.**
`run-ladder.sh` parses the stage summary (this is why **`jq` is required** on the generator)
and branches:

| failed threshold | verdict | what it means |
|---|---|---|
| `dropped_iterations` · `hs_auth_failures` · `hs_unexpected_404` | **harness fault** | the GENERATOR saturated, the fleet stopped authenticating, or the mix asked for resources its own principals do not own. **Not a capacity result, and nothing below this stage is invalidated.** Fix and re-run *this* stage. |
| `hs_canary_leak` | **security incident** | §5.3 condition 5. Stop, preserve everything. |
| latency p95/p99 · `hs_budget_422` · `hs_refused_429` · `hs_conflict_409` · `hs_rebalance_429` · `hs_errors_5xx` | **breach** | the capacity result. |
| *(non-zero exit, no failed threshold)* | **unknown** | usually a script error or a refused `setup()` — read the stage log. |

Writing any of the first row up as "capacity is *N* VU" produces a figure that looks
conservative, reads as defensible and is wrong. That is exactly what "stage N VU: BREACHED"
on **any** non-zero exit used to do.

#### Three seals, and the order they run in

`run-ladder.sh` exits **6** on a vacuous threshold, **7** on a tenancy leak and **8** on an
inverted classifier. The order matters as much as the checks.

**Classification runs first.** Every abort in the harness fires *inside the ramp*, because
what it aborts on is a property of the **server** and is present from t=0 rather than a
property of the load: `hs_canary_leak` evaluates at 15 s (the canary probes every 10 s from
0 s), `hs_errors_5xx` at 30 s, `hs_auth_failures` at 60 s, against a 1-minute default ramp.
So a real cross-tenant leak stops the stage about fifteen seconds in and leaves every
`{phase:hold}` sub-metric empty **by construction** — which is exactly what the vacuous
seal is built to call a harness fault. Run in the other order, a tenancy incident would exit
6 ("defects in the harness, not results") and `exit 7`, "preserve everything" and the whole
incident path would never run.

**The vacuous-threshold seal** then runs, but only on a stage that **reached the hold** (the
wall clock around `k6 run` cleared `RAMP + HOLD`); a stage aborted during the ramp says so
and falls through to normal classification. It asks `k6 inspect` for the threshold keys the
stage declared — the same options object the run resolves — and fails the ladder if any of
them is absent from the summary or resolved to a sub-metric with **no samples**. A threshold
with no samples is not a pass, and **an unobtainable key list is a failure too**: a seal that
cannot see its input is a seal that did not run.

It exists because four budget thresholds could never receive a sample: a custom-metric sample
carries only the tags passed to `.add()`, and `record()` built its tag object **without
`phase`**, so `hs_refused_429{class:search,phase:hold}`,
`hs_refused_429{class:report,phase:hold}`, `hs_conflict_409{phase:hold}` and
`hs_rebalance_429{phase:hold}` selected sub-metrics that stayed empty for the whole run —
either a vacuous pass or a spurious no-data failure, and no reader could tell either from a
real verdict. The fix is in `record()`; the seal is what stops it coming back.

**The seal itself was vacuous for its whole life**, which is the reason it now asks k6
directly. It used to `sed` the key list out of a `console.log` line in the tee'd stage log,
and k6 does not print `console.log` verbatim — it goes through logrus, so the line on disk
is `time="…" level=info msg="HD186_THRESHOLD_KEYS […]" source=console` and the `sed` handed
`jq` a string with ` source=console` stuck to the end of it. `jq` exited before evaluating
anything, the error was redirected away, the status was swallowed, and the seal reported
success on every stage of every mix ever run. One command falsifies the old form:

```bash
grep HD186_THRESHOLD_KEYS stage-1vu.log
```

**The classifier-polarity seal (exit 8)** runs on every stage k6 passes. The boolean in
`--summary-export` means *breached*, not *ok*, and reading it backwards is invisible in
normal operation — `classify_stage()` is only consulted when k6 exits non-zero, so an
inverted reading would not produce noise; it would relabel a **genuine breach** as "harness
fault, nothing below is invalidated", which is worse and quieter. So every clean stage is
made to prove the polarity: the same `jq`, on a summary k6 has just certified as
all-passing, must find nothing.

### The targets (§4.3), fixed before the run and encoded as thresholds

| class | p95 | p99 | error budget |
|---|---|---|---|
| `browse` | 600 ms | 1500 ms | 5xx = **0**; no unexpected 4xx |
| `search` | 1500 ms | 4000 ms | 422 `STATEMENT_BUDGET_EXCEEDED` = **0**; 429 ≤ 1% |
| `report` | 3000 ms | 8000 ms | 422 `STATEMENT_BUDGET_EXCEEDED` = **0**; 429 ≤ 1% |
| `write` | 800 ms | 2500 ms | 5xx = **0**; 409 ≤ 1%; rank-rebalance 429 ≤ 2% |
| `auth` | 1000 ms | 3000 ms | 5xx = **0** |

**5xx = 0 is not a budget item, it is a finding.** The whole point of the 0.17.0 work is that
saturation produces *named refusals* — 422, 409 + `Retry-After`, 429 — and not silence or
500s. `HighErrorRate`'s 5% is a paging threshold, not a target. Any 5xx is written up
individually.

---

## 7. Teardown (§5.5)

```bash
# on the generator
pkill -INT -f 'k6 run'      # confirm no k6 process and no held SSE connection remains

# on the box
bash capture/capture.sh stop
LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack bash fixture/revoke.sh     # credentials
LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack bash fixture/teardown.sh   # rows
```

**Revocation first, and it is a separate script for a reason.** Deleting `tokens.json`,
killing k6 and terminating the generator all destroy the *client's* copy of a credential and
tell the server nothing. What exists on the server after minting is **the whole pool sharing
one password, a thirty-day refresh token each** (`jwt.refresh-token-expiration=P30D`) **and a
live access token each**. The third is the one that needed a row changed and did not get one
for a long time: an access token is a **self-contained signed JWT**, and
`JwtAuthenticationFilter` accepts it on a valid signature plus `.filter(User::isEnabled)` and
nothing else — so a deleted refresh row and a broken password hash are both invisible to it
for up to another thirty minutes. `revoke.sh` now also sets `status = 'DISABLED'`, which the
filter re-reads on **every** request, and reports **three** counts that must all be zero.
`revoke.sh` deletes every refresh token, password
reset and verification for `@load.invalid` and sets those accounts' password hash to a
sentinel no bcrypt output can equal. It needs no run id, no slug prefix, no Flyway match and
no successful teardown — because the case it exists for is the **aborted** window, in a
hurry, and it deletes no rows, so whatever caused the abort can still be investigated.

`teardown.sh` finishes by printing **two separately verifiable claims** rather than one
reassurance:

- **accounts inert** — how many `@load.invalid` accounts still hold a usable password hash,
  and how many live refresh tokens remain. Both must be `0`.
- **rows gone** — the category assertion over the catalog reports nothing.

A teardown that completes gives both. A teardown that fails halfway may give neither, and
these counts are what tell you which. **Every count `revoke.sh` prints must read 0** — the
requirement is stated as a property rather than as a number, because the number has already
changed once: `AFTER_ACTIVE` (accounts still `ACTIVE`, and therefore still accepting an
unexpired *access* token out of `tokens.json`) was added after this paragraph had been
written as "the two counts", and it is the only one of them that speaks for live JWTs at
all — revoking a refresh token does nothing to an access token already minted.

**By tenancy, not by inventory.** The writing mix creates rows nothing recorded, so deleting
"what the generator inserted" is wrong *by construction* — not merely incomplete. Everything
is scoped by the load workspace ids and the `@load.invalid` address domain, so a row created
by the run at 14:30 is deleted by exactly the same predicate as a row created the night
before.

**Explicit ordered deletes, not `CASCADE`.** Three reasons: `issues.workspace_id` has no
`ON DELETE` clause at all (deleting a workspace only works because a cascade through
`projects` happens to remove the issues first — an ordering coincidence, not a guarantee);
`mail_send_events.workspace_id` has no foreign key at all and would survive a cascade
indefinitely; and a cascade does not say what it deleted.

**Where a foreign key refuses, the refusal is the good outcome.** It names a table the
teardown has not accounted for. Add the `DELETE` in `teardown.sql`, in dependency order.
**Never widen a constraint on the product's schema to make a fixture's deletion quieter.**

### How completeness is proved

`completeness.sql` iterates `information_schema` — **a category, never a list**. A
hand-written roster of tables would be wrong one migration after it was written, and wrong
*silently*: a new table simply would not appear and the check would keep returning "clean"
about a database it had stopped looking at.

**The spec's own category is too narrow, and this harness widens it — and the first attempt
at widening it repeated the mistake one level down.** §5.5.3 says "every table carrying a
`workspace_id`". The tables the fixture writes most heavily into carry none —
`issue_comments`, `issue_history`, `issue_field_values`, `comment_mentions`,
`issue_attachments`. Those were covered. But the *workspace* category itself matched
`column_name = 'workspace_id'` **exactly**, over a schema that spells workspace tenancy
**two ways**: everything in the taxonomy and custom-field family uses `scope_workspace_id`,
and its children carry no tenancy column at all. **Fifteen base tables the teardown NAMES
were invisible to the verifier that proves the teardown worked**, three of them tables the
fixture writes. No rows were left behind by that — `teardown.sql` deletes them explicitly —
but the two files disagreed about the shape of the database, so "zero rows attributable to
the fixture" was being printed by something that could not have seen them.

**A category defined by a string is a list with one entry.** So the categories are now
defined by *mechanism*, read from the catalog:

1. **workspace** — every base table carrying a column named `workspace_id` **or**
   `scope_workspace_id`, **or** having a single-column foreign key whose target is
   `workspaces(id)`. The FK half covers a third spelling the day it lands; the name half
   covers the tables no FK covers (`mail_send_events`, which has none at all and cascades
   from nothing; `issue_labels`, `issue_versions`, which reach workspaces compositely).
2. **reachable** — a **fixpoint over foreign keys**: any base table with a single-column FK
   into a table already reached is itself reached, through its parent's load-owned ids. That
   is what finds the five issue children, and `field_set_items`, `workflow_statuses`,
   `workflow_transitions`, `priority_set_items`, `issue_type_set_items` and
   `role_permissions` through their scoped parents — and whatever a future migration hangs
   off any of them.
3. **issue** — every table with an `issue_id` column, kept beside the fixpoint: a child that
   loses its foreign key stops being *reachable* and does not stop being tenant data.
4. **user** — every column in `public` whose FK points at `users(id)`, read from
   `pg_constraint`, so `reporter_id`, `changed_by`, `uploaded_by`, `invited_by`, `lead_id`,
   `actor_id`, `owner_id` and anything added later are covered without this file being edited.
5. **recipient** — every recipient-shaped column, matched on the address domain. For
   `failed_email` and for an **anonymous** `mail_send_events` row (both `workspace_id` and
   `sender_user_id` are nullable) **the address is the only tenancy handle there is**.

**It sees PostgreSQL and nothing else.** Attachment *blobs* live in `FileStorage` and no
query can see one, which is why `verify-api.sh` **deletes through the API** the three objects
it uploads: the row and the blob are only both known to the application.

**And the tripwires.** A check that returns "nothing offends" is worthless until it has been
seen to say "something offends" — and *a category that has never been seen to fire has not
been shown to work*, which is exactly how the `workspace_id` spelling stayed broken while two
tripwires passed. `rehearse.sh` now fires **four**: the whole fixture; after deleting only
`issue_history` (must still name `issue_comments`, reached through `issues`); on a populated
database it must name **`field_sets`** (reached only by the `scope_workspace_id` spelling)
**and `field_set_items`** (reached only through its scoped parent), and must still name
`field_sets` after `field_set_items` is deleted; and zero after the full teardown. If any of
those comes back clean, the check has gone blind and every future "teardown verified" is a lie.

### Still to do by hand

`teardown.sh` prints this list; none of it is automatable and all of it is evidence.

- `hamstrack_config_drift` is 0 for every scope and `.deployed-sha` is unchanged.
- **A human smoke test**: log in as a real account, open a board, open an issue, create and
  delete a test issue, run a report. Plus the two-address rate-limit probe from
  `docs/ops-prod-hardening.md`.
- The product gauges (`hamstrack_users_total`, `hamstrack_issues_total`) are back at their
  pre-run values, **and the window is annotated in Grafana** — they jumped by the fixture's
  size and a later reader of the Product dashboard must not mistake that spike for growth.
- Post-window EBS snapshot as the new baseline; keep the pre-window one 30 days.
- **Terminate the generator instance.** This is housekeeping, **not revocation** — it
  destroys the client's copy of `tokens.json` and tells the server nothing. Revocation is
  `fixture/revoke.sh`, and the teardown's two printed counts are what confirm it.
- **Delete `/opt/hamstrack/.loadtest.env` on both machines** (the box's holds the bcrypt
  hash, the generator's holds the plaintext).

**Disk:** the space returns to PostgreSQL, **not** to the filesystem. `df` looking unchanged
is the expected result, not a failed teardown. `VACUUM FULL` takes an `ACCESS EXCLUSIVE` lock
and is not run on production for this; the grown volume absorbs the residual, which is the
second reason growing it is a precondition.

---

## 8. If the run is aborted, or damages something (§5.3, §5.6)

**In this order.** The instinct mid-incident is to reach for the snapshot first, and that is
the option with the largest loss.

0. **ALWAYS FIRST, whatever else is true: revoke.** On the generator `pkill -INT -f 'k6 run'`,
   then **on the box**:

   ```bash
   LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack bash fixture/revoke.sh
   ```

   An abandoned window otherwise leaves **every load account working on production** — one
   shared password, a thirty-day refresh token each, and a live access token each that is
   accepted on a signature check alone — and *nothing else in this
   procedure ends them*. Not stopping k6, not deleting `tokens.json`, not terminating the
   generator. It deletes no rows, takes seconds, and needs neither the run id nor a
   schema match, so there is never a reason to defer it.
1. **If the fixture is the problem** → run the teardown.
2. **If the database is inconsistent** → restore from the most recent **backup**. It loses
   less than the snapshot does.
3. **If the volume is the problem** → restore the pre-window **snapshot**, accepting the loss
   of everything since.

An abort is not the end of the window's obligations: the teardown still has to happen, and
the run record still has to say what was measured before it stopped and why it stopped.

---

## 9. Writing it up

Copy `RESULTS-TEMPLATE.md` to `RESULTS-<date>.md` at the **start** of the window and fill it
as you go. Filling it afterwards from memory is how a partial result gets published as a
complete one.

The template carries the acceptance criteria as headings, so an unfilled section is a visibly
unmet criterion rather than an omission.

**The raw capture is scrubbed by construction, not by remembering.** `run-ladder.sh` records
every stage with `--out json=…` and that file is attached to the ticket. k6's *default*
system tags include `url`, and the SSE endpoint authenticates by **query parameter** (the
product's design — `EventSource` cannot set headers), so a default capture would carry up to
100 principals' live access tokens out of the machine. `url` is therefore dropped from
`systemTags` in `lib/config.js`, for every script; `name` is kept so metrics stay
attributable, and because k6 *defaults `name` to the URL*, the one request that carries a
token sets an explicit `name` of its own. One omission in a list plus one tag at one call
site — not a habit anybody has to maintain.

**The access token in the SSE query string does not reach the instance's logs, and there is
exactly one action that would change that.** All four log surfaces were checked: there is no
Tomcat access log, Caddy has no `log` directive, the application's own request logging uses
`getRequestURI()` — which **excludes** the query string — and Alloy tails stdout only. So the
query parameter stays; it is the product's design and moving it for a load test would be
measuring a different product.

**Do not raise `LOG_LEVEL` during the window.** Spring's `FrameworkServlet` request logging
at `DEBUG` **does** include the query string, and that line goes to stdout, to Alloy, and to
Loki — where it is retained and searchable. Raising the level to debug a slow stage would put
up to 100 principals' live access tokens into the log store, and `revoke.sh` cannot reach an
access token that has already been minted (only `AFTER_ACTIVE`, by deactivating the account,
does). If it happens anyway: finish or abort the stage, run `fixture/revoke.sh` immediately
so every load account stops being `ACTIVE`, and record it — the tokens in Loki are then inert
rather than merely unused.

**The fingerprint is publishable for two different reasons, and only one is a property of the
whole file.** The container environment dump is filtered by a positive **allow-list of
names**, so a secret added later cannot pass it. Every other command is publishable because
of what it happens to print — a claim about each command. The EC2 identity document was the
counter-example (it carries `accountId`, `instanceId`, `privateIp`) and is now **projected**
to instance type, region, AZ and image. Anyone adding a command there says why *its* output
is publishable.

---

## 10. Running it somewhere else

Nothing here is Cloud-specific, and three properties keep it that way:

- **The scenarios take a base URL, ids resolved from the API, and credentials.** No AWS, no
  SSM, no Cloudflare, no assumption about a reverse proxy. `BASE_URL=http://localhost:8080`
  against a local docker Postgres works unchanged — that is how the harness is developed and
  how the teardown is rehearsed.
- **Tier 1 capture is sufficient for a verdict.** It needs only the application's own
  `/actuator/prometheus`, which is compiled into every build in both DC and Cloud. Tier 2
  (the Prometheus range export, cAdvisor, node-exporter) is available only where the
  *optional* stack runs. A harness whose verdict depended on the optional stack would be a
  Cloud-only tool wearing a portable name.
- **The generator takes a `psql` connection** and its Flyway guard is the same on both. It
  does not know what a profile is.

So **a self-hoster can run this to size their own box**, which is the DC half of the answer
this ticket owes. To rehearse:

```bash
# A DISPOSABLE database of its own — not your dev database, and certainly not production.
createdb hamstrack_hd186     # then bring its schema to the pinned Flyway version
export LOAD_PSQL_MODE=dsn
export LOAD_DB_DSN=postgresql://hamstrack:hamstrack@localhost:15432/hamstrack_hd186  # 15432
export LOAD_PASSWORD_HASH='$2a$12$…'
LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack_hd186 LOAD_RUN_ID=reh1 \
  bash fixture/rehearse.sh
```

**`rehearse.sh` refuses any database that holds an account which is not `@load.invalid`.**
The guard it replaced tested the *shape of the connection string* — and let an empty DSN
through whenever `LOAD_PSQL_MODE=docker`, which is the mode the box's own configuration
ships and the runbook tells the operator to source. So "the rehearsal" passed its
never-against-production guard **on production**, generated a full-scale fixture there,
deleted it and `VACUUM (ANALYZE)`d, outside any window. A property of a connection string
cannot answer a question about a database; this one asks the database, and cannot be
satisfied by exporting anything.

`rehearse.sh` also **forces** its scale (`LOAD_REHEARSAL_SCALE`, default `0.01`) instead of
defaulting it, because the configuration this runbook has you source sets `LOAD_SCALE=1.0`
and a default loses to it silently.

---

## 11. When a migration lands

The generator is pinned to a Flyway version (`LOAD_PINNED_FLYWAY_VERSION` in
`fixture/lib.sh`, currently **24**) and refuses anything else, naming both versions.

**Do not just bump the number.** Re-read `src/main/resources/db/migration` against
`10-generate.sql` and `teardown.sql` first: a new NOT NULL column, a new child table, a
changed constraint or a new `workspace_id`-carrying table all change what the generator must
write and what the teardown must remove. Then bump it, and re-run `rehearse.sh` — which will
tell you whether the completeness assertion still sees everything.

The version guard exists because a generator that writes rows the entities cannot map is
worse than one that refuses: the refusal is loud, and the bad rows are silent until a report
500s in the middle of a window.

---

## 12. Known limitations, stated rather than discovered

- **The SSE scenario holds the connection but does not parse events.** It measures the
  *resource* — a held connection plus a registered emitter per session, which is what §4.8
  asks about — not event delivery. Parsing would need `xk6-sse`, i.e. a custom k6 build, and
  "a single static binary, nothing to install on the generator" is one of the reasons k6 was
  chosen.
- **A killed k6 process breaks its VUs' refresh chains.** `/api/auth/refresh` *rotates*: it
  deletes the presented token and issues a new one, so a refresh cookie is single-use and the
  chain lives inside the k6 process that owns it. After an aborted mix, **re-mint** (fifteen
  minutes). This is a property of the product's rotation, not a defect of the harness. It is
  also **not revocation**: the accounts and their password are untouched by any of it.
- **A 404 from a mix aborts the stage** (`hs_unexpected_404`). Every request a mix makes is
  for something its own principal owns — workspace from the principal's list, project from
  that workspace, issue number from *that project's* count — so a 404 means the harness is
  asking the wrong question, not that the product refused. It used to be counted as nothing,
  which hid two defects that both **deflated** the browse p95/p99 the breach point is read
  from: a flat account pool that put workspace-B credentials on part of the fleet above 60
  VUs, and one global issue count that made small projects 404 nine reads in ten.
- **`run-ladder.sh` decides only half of §4.3's breach definition.** The alert-rule half is on
  the box and is checked by the operator. A green exit code is not the verdict.
- **Tier 1 capture has two transports, and only the signals were ever portable.**
  `LOAD_CAPTURE_MODE=docker` (default) reaches the actuator and psql through `docker exec`, so
  no port has to be published for the run. `LOAD_CAPTURE_MODE=host` scrapes `ACTUATOR_URL`
  directly and runs the local psql — for a DC install running the JAR under systemd, which
  could not run Tier 1 at all while the script hard-refused without Docker *and its own header
  claimed Tier 1 needed only "the actuator, psql and /proc"*. Host mode cannot give
  per-container memory/CPU accounting; it says so in the output rather than writing zeros.
- **A contaminated workspace does NOT mean "the fixture's own rows are gone".** An excluded
  workspace keeps everything inside it *and* keeps the load accounts its rows point at.
  `teardown.sh` prints the ordering playbook when it happens; the one move that cannot be
  undone is deleting the colliding workspace through the app **first** — after that no slug
  matches, and nothing here will find those accounts by workspace again.
  `LOAD_ACCOUNTS_ONLY=1 bash fixture/teardown.sh` deletes load accounts by the **address
  domain** alone, needing no slug, no run id and no workspace.
- **`jq` is required on the generator.** It reads the pool sizes and the token expiry out of
  `tokens.json`, k6's own peak VU count out of `k6 inspect`, and — the one that decides what a
  stage *means* — which threshold failed. A grep fallback could only ever answer "some
  threshold failed", which is the answer that publishes a harness fault as a capacity number.
- **Attachment storage keys in the fixture name nothing in any backend.** The browsing mix
  lists attachments and never downloads one; `verify-api.sh` uploads three real ones so the
  storage path is exercised against objects that exist.
- **The pool must cover the VUs k6 ALLOCATES, which is not the ladder's VU number.**
  `tokens.json` carries **three** arrays: `accountsA` (the load), `accountsCanary` (the
  tenancy canary's own principals, workspace-A members no load VU can draw) and `accountsB`
  (workspace-B members, never load — they exist so the canary's target can be proved to
  exist). `myAccount()` is `accountsA[__VU - 1]` with **no modulo**, so the map is injective
  by construction and two VUs cannot share an account whatever the scenario mix does.
  `browse` declares three scenarios, so a 100-VU stage allocates **201** VUs.
  **Both ends refuse rather than warn**: `run-ladder.sh` asks `k6 inspect
  --execution-requirements` for k6's own `maxVUs` before each stage, and every mix's
  `setup()` recomputes it from the resolved options. The old guard compared 120 accounts
  against a 100-VU headline — the wrong number — and could not see 81 accounts being held
  twice, which broke authentication for both holders the moment either refreshed.
- **The tenancy canary's target is resolved, never typed.** `setup()` finds workspace B
  through a B principal's own list and asserts it is both foreign to the A principal and
  real; each iteration then asks for it *and* for a random non-existent id and requires the
  two answers to be identical. `CANARY_WORKSPACE_ID` is now only a cross-check, and a
  mismatch is fatal — which is what catches the stale id a paste used to hide.
- **Attachment blobs are outside everything the completeness check can see.** It is a claim
  about PostgreSQL. `verify-api.sh` deletes the three objects it uploads through the API,
  because the row and the blob are only both known to the application.
