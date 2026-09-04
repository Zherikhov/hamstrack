# Load capacity measurement — RESULTS

**Copy this to `RESULTS-<date>.md` at the START of the window and fill it as you go.**
Filling it afterwards from memory is how a partial result gets published as a complete one.

Every heading below is an acceptance criterion from §10 of the spec, numbered. An unfilled
section is therefore a **visibly unmet criterion**, not an omission somebody has to notice.

> **Delete this block before publishing:** anything still reading `<TODO>` is a claim the run
> did not make. Leave it as `<TODO>` or write "could not be determined, because …". Do not
> quietly drop a section — a missing section reads as "not applicable" and none of them are.

---

## 0. Header

| | |
|---|---|
| **Date / window (UTC)** | `<TODO start>` – `<TODO end>` |
| **Operator** | `<TODO>` |
| **Watcher (Grafana + abort command)** | `<TODO>` |
| **Instance** | `<TODO i-…>` |
| **Generator instance** | `<TODO type, terminated at …>` |
| **k6 version** (`k6 version`, on the generator) | `<TODO>` — *the harness's exit codes and the vacuous-threshold seal are properties of k6 and were proven on **v2.2.0**; any other version is unverified until re-proven (README §3b)* |
| **Fixture seed** | `<TODO>` |
| **Harness commit** | `<TODO>` |
| **Snapshot taken before (id)** | `<TODO snap-…>` |
| **Snapshot taken after (id)** | `<TODO snap-…>` |

**Scope of this number, stated once and meant** *(§3.3, §12)*:

> This is one measurement of one configuration. It generalises **by category** ("this class
> of box, this shape of tenant") and never by promise. It is **origin** capacity — Cloudflare
> was excluded, and the edge only ever adds latency and removes load. It is invalidated by
> any change to the app container's memory limit, the heap ceiling, the instance type or
> size, the connection-pool size, the statement or lock bounds, the report/search caps, or
> what else runs on the box.

---

## 1. Preconditions (criterion 13)

Every §5.1 gate, with evidence. A "no" postponed the window; if any of these is not "yes with
evidence", say why the window opened anyway.

| # | Gate | Met? | Evidence |
|---|---|---|---|
| 1 | HD-199 in force, 48 h watched, no exit 137, no HighLatency | `<TODO>` | `HostConfig.Memory` = `<TODO>` |
| 2 | No imminent HD-189 | `<TODO>` | |
| 3 | `hamstrack_config_drift` = 0 all scopes; `.deployed-sha` matches | `<TODO>` | |
| 4 | EBS snapshot **completed** | `<TODO>` | id `<TODO>` |
| 5 | Free disk ≥ 5 GB and ≥ 3× fixture | `<TODO>` | `<TODO GB>` free |
| 6 | Backup last success recent | `<TODO>` | |
| 7 | Fixture generated, API-verified, ANALYZEd, settled overnight | `<TODO>` | |
| 8 | Teardown **rehearsed** on a disposable database, all four tripwires passed | `<TODO>` | date `<TODO>` |
| 8b | `fixture/revoke.sh` **run once** in the rehearsal; all THREE counts read 0 (usable password hashes, live refresh tokens, and accounts still `ACTIVE` — the third is what refuses the live access tokens in `tokens.json`) | `<TODO>` | date `<TODO>` |
| 9 | 1-VU dry run passed **including the abort path actually exercised** | `<TODO>` | |
| 10 | Window agreed and announced; watcher named | `<TODO>` | |
| 11 | Nobody mid-onboarding | `<TODO>` | |

---

## 2. Configuration fingerprint (criterion 19)

**Paste `capture/fingerprint.sh` output WHOLE.** Do not summarise it: the reader in six months
needs to check a specific value against their own box, and a summary is a claim about the
configuration rather than the configuration.

```
<TODO paste fingerprint.txt>
```

Two things the script cannot read — add by hand:

- [ ] **The burstable credit specification was READ, not assumed.** Mode: `<TODO — the value
  you read, not the value you expected>`. Starting balance: `<TODO>`. Command:
  `aws ec2 describe-instance-credit-specifications --instance-ids <id>`.

  *This is a named checkbox rather than a line in "what was not measured", and no expected
  value is printed beside it, because this single value RETIRES AN ABORT CONDITION. In
  `unlimited` the instance never throttles — it bills surplus — so credit exhaustion is a
  **cost** note and the burstable-confounds-the-measurement concern does not apply. In
  `standard` the opposite is true and §5.3 condition 8 is live for the whole window. A
  pre-printed answer beside a TODO gets **confirmed** rather than read, and it is a
  per-instance setting anyone with the console can change between runs.*
- [ ] **If the mode is `standard`:** `CPUCreditBalance` across the window, sampled.
  `<TODO / n-a>`
- **Volume type / size / IOPS**, and if gp2, its burst-balance state. `<TODO>`

**Swap** (its presence or absence changes what failure looks like — see the swap note in §6):
total `<TODO>` MB, `swappiness` `<TODO>`.

### Fixture, as measured

| | Workspace A | Workspace B |
|---|---|---|
| projects / issues | `<TODO>` | `<TODO>` |
| history / comments | `<TODO>` | `<TODO>` |
| field values / labels / version links | `<TODO>` | `<TODO>` |
| sprints / scope events | `<TODO>` | `<TODO>` |
| members | `<TODO>` | `<TODO>` |

- **Run id** (`LOAD_RUN_ID`, part of the workspace slugs — the handle teardown finds this
  fixture by): `<TODO>`
- **Hierarchy** (from `20-resync.sql`): sub-tasks `<TODO>`%, all parented `<TODO>`, children
  per parented issue mean/p50/p99/max `<TODO>`. *`/children` is read on a uniformly random
  issue by the browsing mix, so this distribution is what that endpoint costs. A max in the
  thousands, or zero parented, is the arithmetic bug that put every sub-task on issue #1.*
- **Measured distributions** (from `20-resync.sql`; these are the §4.2 claims, verified rather
  than assumed): status split `<TODO>`, comments per issue mean/p50/p99/max `<TODO>`,
  description length mean/p50/p99 `<TODO>`.
- **Measured size on disk:** database before `<TODO>`, after `<TODO>`, **fixture =
  `<TODO>`**. *(The proposal estimated 300–400 MB. Where the estimate and the measurement
  disagree, the measurement wins — say which happened.)*
- **A project over `REPORTS_MAX_ROWS`:** `<TODO key>` with `<TODO>` issues, and a row-level
  report against it returned `meta.truncated = true` **(criterion 10: `<TODO yes/no>`)**.

---

## 3. Per-mix results (criteria 14, 15, 16)

For **each** mix: the highest concurrency at which every §4.3 target held for a full hold, and
the concurrency at which it first did not. **A breach reported without a named resource and a
metric series does not satisfy criterion 15.**

### 3.1 Browsing

| | |
|---|---|
| Highest passing stage | `<TODO>` VU |
| First breach | `<TODO>` VU |
| **Resource that breached** | `<TODO>` |
| **Metric series that shows it** | `<TODO>` |
| Provisioned alerts that fired (and when) | `<TODO>` |
| Real-traffic requests during the stages | `<TODO>` |

Response-code mix over time, **every non-2xx class attributed** (429 by `kind`, 422 by
`route`, 409 to lock contention, any 5xx individually and written up as a finding):

`<TODO>`

> **`http_req_failed` IS NOT THE ERROR RATE HERE, AND ON A CLEAN BROWSE STAGE IT IS ABOUT
> 20%.** Measured on the dry run: **21.08% (62 of 294)** on a stage where `hs_errors_5xx` and
> `hs_unexpected_404` were **both 0 throughout**. k6 counts any non-2xx/3xx, and this mix
> makes two kinds of deliberate non-2xx — the tenancy canary's **asserted 404s** (one every
> 10 s, and a 200 there would be a security incident) and the held **SSE** stream, whose
> completion k6 does not score as a success. Do not copy this number into the results as an
> error rate, and do not compare it with `HighErrorRate`'s 5%. The error signals are the
> named ones: `hs_errors_5xx`, `hs_unexpected_404`, `hs_budget_422`, `hs_refused_429`,
> `hs_conflict_409`, `hs_rebalance_429` — and, splitting the 429s by what they mean,
> `hs_occupancy_429` (too many RUNNING at once) beside `hs_minute_budget_429` (asked too
> often). Both are inside `hs_refused_429`; they are attribution, not extra refusals.

### 3.2 Reporting & searching

*(same table)* `<TODO>`

### 3.3 Writing

*(same table — plus: did the concentrated ranking produce a `rank_rebalance` 429, and at what
concurrency?)* `<TODO>`

### 3.4 Soak (criterion 14's "holds for fifteen minutes")

Mix `<TODO>` at `<TODO>` req/min for 15 min under `constant-arrival-rate`: `<TODO held / did
not hold>`. *A capacity figure that only survives four minutes of a closed ladder is not a
capacity figure.*

### 3.5 Tenancy canary

Requests: `<TODO>`. Non-404 responses: **`<TODO>` (must be 0)**.

*Any other status is a security incident, not a data point. If this is non-zero, stop reading
this document and open an incident.*

### 3.6 Did the harness saturate itself?

`dropped_iterations`: `<TODO>`. Peak generator CPU: `<TODO>`%. Stages discarded for this
reason: `<TODO>`.

*A harness that cannot detect its own saturation reports the generator's limits as the
product's (criterion 5).*

---

## 4. The standing predictions (criteria 17, 18)

Each answered explicitly as **held / did not hold / could not be determined**, with numbers.

### P1 — the entitlement probe (HD-182: first the premise, now the fix)

One principal spending exactly their documented allowance (`SEARCH_ENTITLEMENT` / `REPORT_ENTITLEMENT`, which MUST equal the `SEARCH_REQUESTS_PER_MINUTE` / `REPORTS_REQUESTS_PER_MINUTE` capture/fingerprint.sh read off the box — record both),
with a *different* principal browsing at 5 VUs as the victim.

> **The prediction this probe used to carry was deliberately falsified by the product.** It
> read: *held if the victim degrades while the entitled principal receives no 429 at all.* It
> held — worse than predicted, the 2026-08-31 run aborted after 32 s on dropped iterations —
> and the answer shipped was an **occupancy bound** (`EXPENSIVE_READ_MAX_IN_FLIGHT`). So the
> entitled principal receiving no 429 is now the **regression**, and the wording below is the
> inversion. Do not restore the old sentence from an older results file.

| | |
|---|---|
| **Verdict** | `<TODO the fix holds / the fix did not engage / regression / could not be determined>` |
| Victim `browse` p95 / p99 (vs target) | `<TODO>` |
| Hikari `pending` during the probe | `<TODO>` |
| `hs_occupancy_429` received by the entitled principal | `<TODO>` |
| `hs_minute_budget_429` received by the entitled principal (must be **0**) | `<TODO>` |
| `hamstrack_expensive_read_in_flight` — max during, and value AFTER the run | `<TODO>` / `<TODO>` |
| `hamstrack_expensive_read_permit_force_released_total` — total during the run (expected **0**) | `<TODO>` |

- **The fix holds** if the victim's `browse` class stays **inside** its target while the
  entitled principal receives occupancy 429s (`TOO_MANY_IN_FLIGHT` / `EXPENSIVE_SURFACE_BUSY`).
  Their own latency is not a target and never was.
- **The fix did not engage** if there were no occupancy 429s at all: this box absorbed one
  fully entitled principal without the share ever filling, so the run says nothing about the
  bulkhead in either direction. Record it that way rather than as a pass.
- **Regression** if the victim breaches while the entitled principal is unrefused — the
  original finding, reproduced after the fix.
- **A minute-budget 429 for the entitled principal is a harness/box disagreement, not a
  result**: the entitlements above do not match what the instance grants. It is a threshold,
  so the run fails on it.
- **The value of `hamstrack_expensive_read_in_flight` after the run has ended is a permit
  leak check**, and it is the one number here that reports a defect rather than a capacity: it
  must fall back to 0. A gauge stuck at the ceiling means that replica has lost the capacity
  permanently until it restarts — **unless `hamstrack_expensive_read_permit_force_released_total`
  moved**, in which case slots were being *held* rather than leaked and the watchdog took them
  back. Report both, because they look identical on the gauge and have opposite remedies.
- **`hamstrack_expensive_read_permit_force_released_total` above 0 during a k6 run is a finding about
  the box, not about the product**: k6 sends and reads promptly, so nothing in this harness
  should hold a slot past `DB_STATEMENT_TIMEOUT_MS` + 60 s. A nonzero value means something
  else was talking to that instance, or that a single request really did run for over a minute.

**Measured mean connection-hold time per request class** (from
`hikaricp_connections_usage_seconds`) — **report this regardless of the verdict; it is new
either way, and it is what makes HD-182 actionable rather than alarming**:

| class | mean hold (ms) |
|---|---|
| browse | `<TODO>` |
| search | `<TODO>` |
| report | `<TODO>` |
| write | `<TODO>` |

### P2 — the report heap costing (~1.9 KB/row)

| | |
|---|---|
| **Verdict** | `<TODO>` |
| Measured transient heap per concurrent capped request | `<TODO>` MB |
| Costed | 38 MB (held if 19–76 MB) |

If it did not hold, **both directions are useful**: materially less means `REPORTS_MAX_ROWS`
is more conservative than it needs to be and the docs row overstates the danger; materially
more means the default is closer to the heap than the documentation claims. Say which.

### P2b — "the pool bounds the heap" (criterion 18)

The proposition nobody had written down, and the one this run is uniquely able to settle:
every row-level report holds a pooled connection for its whole assembly
(`@Transactional(readOnly = true)` end to end), so simultaneous materialisation is bounded by
`DB_POOL_MAX_SIZE` and the true heap exposure is `pool × max rows × bytes/row`
= 10 × 20 000 × 1.9 KB ≈ **380 MB of a 495 MiB heap**.

| | |
|---|---|
| **Verdict** | `<TODO>` |
| Peak heap-after-collection at the deepest P2 step | `<TODO>` |
| Step at which heap stopped growing | `<TODO>` (expected: the pool size) |

**If it holds:** the pool is not only the concurrency bound but the **heap** bound, and the
advice in `docs/self-hosting.md` to raise `DB_POOL_MAX_SIZE` alongside
`DB_STATEMENT_TIMEOUT_MS` silently raises heap exposure at the same time. **That is a finding
for HD-182, not a fix here.**

### L — the limiter probe

Do the refusals have the shape the documentation claims?

| | |
|---|---|
| 429 returned with a `Retry-After` | `<TODO>` |
| Counted under `hamstrack_ratelimit_hit_total{kind=…}` with the right kind | `<TODO>` |
| Any 5xx instead of a refusal | `<TODO>` (must be 0) |

### The finding that does not depend on any of the above

At realistic think times the per-principal budgets do **not** bind: a reporting VU at 20 s
think time makes 3 requests a minute against a 60/minute allowance. So the mixes reach the
connection pool long before they reach any budget — **the limiters are not what protects this
instance from ordinary use, and the number that does the protecting is `DB_POOL_MAX_SIZE`**.

Observed: `<TODO — did the mixes reach any budget at all before breaching?>`

---

## 5. Attribution table, filled in (§4.8)

| Observed | Resource that ran out | Metric that shows it | Seen? |
|---|---|---|---|
| TTFB up, `hikaricp_connections_pending` > 0, acquire time climbing | Connection pool | `hikaricp_connections_pending`, `_acquire_seconds` | `<TODO>` |
| …plus 5xx and `hikaricp_connections_timeout_total` rising | Pool exhaustion past the 30 s `connectionTimeout` | `hikaricp_connections_timeout_total` | `<TODO>` |
| 422 `STATEMENT_BUDGET_EXCEEDED` | A single statement's cost | `hamstrack_db_statement_budget_exceeded_total{route}` | `<TODO>` |
| 409 + `Retry-After` on writes | Row-lock contention | 409 count + sampler `wait_event_type='Lock'` | `<TODO>` |
| 429 | An entitlement ceiling — `kind` says which | `hamstrack_ratelimit_hit_total{kind}` | `<TODO>` |
| GC pause sum climbing, live data approaching max, throughput falling with pool and CPU idle | Heap | `jvm_gc_live_data_size_bytes` / `jvm_memory_max_bytes` | `<TODO>` |
| App container gone, exit 137, nothing in any log | Container memory limit (kernel OOM kill) | `docker inspect .State.ExitCode`, `dmesg` | `<TODO>` |
| Host available memory collapses, **swap in/out non-zero**, latency degrades with nothing dying | **Host memory** | `node_memory_MemAvailable_bytes`, `pswpin/pswpout` | `<TODO>` |
| Some *other* container dies first | Host memory, kernel choosing the victim | container exit codes, `dmesg` | `<TODO>` |
| Host CPU idle → 0, run queue grows | Host CPU | `node_cpu_seconds_total{mode="idle"}` | `<TODO>` |
| Disk read throughput up, PG cache-hit ratio down | Page cache / EBS IOPS | `pg_stat_database` hit ratio | `<TODO>` |
| TTFB up with `pending` = 0, DB idle, threads ~200 | Request threads / accept queue | `jvm_threads_live_threads`, `http_req_connecting` | `<TODO>` |
| k6 `dropped_iterations` > 0 or generator CPU > 70% | **The harness.** Discard the sample | k6 summary | `<TODO>` |

**Swap note.** Whether the host has swap decides what memory exhaustion *looks like*. With
none, it is a kernel OOM kill: abrupt, exit 137, absent from every application log. With
swap, the same pressure becomes **latency while everything stays alive and every liveness
check passes**. A run written to look for a dead container cannot see the second one, so if
this run reports "no memory problem" **check `pswpin`/`pswpout` before believing it**.

- Swap on this host during this window: total `<TODO>` MB, `swappiness` `<TODO>` (from the
  fingerprint).
- Pre-window pressure test, if one was run — how far the host was pushed and what it did:
  `<TODO / not run>`
- Observed swap activity during the run: `<TODO>`

---

## 6. Return to a known state (criterion 20)

| | Done? | Evidence |
|---|---|---|
| No k6 process, no held SSE connection | `<TODO>` | |
| Teardown ran and the **category assertion returned zero** | `<TODO>` | |
| No load account remains | `<TODO>` | |
| `VACUUM (ANALYZE)` run; space returned to PG (not to the filesystem) | `<TODO>` | |
| `hamstrack_config_drift` = 0 all scopes; `.deployed-sha` unchanged | `<TODO>` | |
| **Human** smoke test: login, board, issue, create+delete an issue, a report | `<TODO>` | by `<TODO>` |
| Two-address rate-limit probe (`docs/ops-prod-hardening.md`) | `<TODO>` | |
| Product gauges back to pre-run values | `<TODO>` | |
| **Window annotated in Grafana** | `<TODO>` | |
| Post-window snapshot taken; pre-window one retained 30 days | `<TODO>` | |
| Generator instance terminated — **this revokes NOTHING**; it destroys the client copy of `tokens.json`. `fixture/revoke.sh` is what revoked, and row 8b is where that is recorded | `<TODO>` | |

Foreign keys that refused during teardown, if any — **this is information, not a failure**;
each one names a table the teardown did not account for:

`<TODO>`

---

## 7. Findings → tickets (criterion 25)

**Nothing is fixed under this ticket.** Every finding becomes a ticket.

| Finding | Severity | Ticket |
|---|---|---|
| `<TODO>` | | HD-182 (evidence) |
| `<TODO>` | | `<TODO>` |

Any 5xx observed, each written up individually:

`<TODO — or "none, which is the result the refusal contract predicts">`

---

## 8. Documentation to update (criteria 21–24)

- [ ] `docs/self-hosting.md` → **Requirements** rewritten from this measurement: what this
      class of box carried, for which mix, with the fixture's shape stated, **and an explicit
      statement of what was NOT measured**.
- [ ] Every row of the configuration table this run **contradicts** is corrected. The
      `APP_MEMORY_LIMIT`, `DB_POOL_MAX_SIZE` and `REPORTS_MAX_ROWS` rows are the likeliest,
      but the criterion is "every row the measurement contradicts", not those three.
- [ ] The sizing guidance states the **category** of install the number applies to and the
      conditions that invalidate it — not a bare figure that will be quoted after the
      configuration moves.
- [ ] `docs/ops-prod-hardening.md` §5's "what has never been measured" paragraph is replaced
      by the measurement, with its date.

---

## 9. What the harness got wrong

**Budget a second window a week later.** The first window's most likely product is a list of
things the harness got wrong, and a plan that assumes one attempt is how a partial result gets
published as a complete one.

| What the harness got wrong | Fixed in |
|---|---|
| `<TODO>` | |
