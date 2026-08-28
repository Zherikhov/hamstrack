# Measuring what this instance can actually carry, and what runs out first (HD-186)

**Status:** proposal / design review. **Date:** 2026-08-28. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness) — evidence ticket, not a feature.
**Produces evidence for:** HD-182 (entitlement vs capacity), the `REPORTS_MAX_ROWS` heap costing,
and `docs/self-hosting.md` → *Requirements*, which is today reasoned from arithmetic.
**Related:** `docs/design/statement-timeout-proposal.md` (the refusal taxonomy this run reads),
`docs/design/config-delivery-proposal.md` §9 (the memory limit this run must be downstream of),
`docs/ops-prod-hardening.md` §5 (which says, in the repository's own words, that "1 GB is fine" is a
belief), `docs/observability.md` (every metric named here already exists), HD-199 (delivery),
HD-189 (resize / move observability off), HD-180 (limits on the other containers).

**Out of scope, stated once and meant:** fixing anything this finds. HD-182 exists and is waiting.

---

## 1. Problem & goal

Nothing has ever put this instance under load. Every capacity statement the project makes — "a
2 vCPU / 2 GB host comfortably runs a small team", "budget ~1 GB RAM for it", "`REPORTS_MAX_ROWS`
is ~38 MB per request", "raise `DB_POOL_MAX_SIZE` for concurrency" — is derived from arithmetic
over configuration, and the arithmetic has never been checked against a running process. That was
tolerable while the only users were the people who wrote it. It stops being tolerable at a public
launch, because the first honest question a prospective self-hoster asks is "how big a box do I
need", and the only answer in the repository is a guess with confident formatting.

The number is the smaller half of the goal. **The larger half is which resource runs out first**,
because "the server is too small" and "the application serialises on something" call for opposite
responses and today we cannot tell them apart. A run that reports "it fell over at 40 users" and
cannot say whether that was the connection pool, the heap, the request threads, the host's memory,
the burst-credit balance or the page cache has produced a number nobody can act on.

**Goal.** A repeatable harness in this repository, runnable by somebody who did not write it,
against a dataset it generates itself, that answers per workload mix: *at what concurrency is the
target breached, and which resource breached, named with the metric that shows it.* Plus an explicit
verdict on each standing prediction, and the result written into the sizing guidance.

**Success looks like:** the `## Requirements` section of `docs/self-hosting.md` cites a measurement
with a date and a configuration fingerprint, and HD-182 opens with evidence instead of a derivation.

**Failure looks like** — and this is worth naming, because it is the likely one — a run that
produces graphs, confirms what was already computed, and settles nothing. *A load test that only
confirms what was predicted has been run badly.* Every scenario below is therefore written as a
falsifiable proposition with a stated way for it to come out the other way.

---

## 2. The most consequential decision: this runs AFTER HD-199, not before

**Ruling: the window opens only once the app container's memory limit is in force on the box, has
been watched for the 48 h HD-199 §9.3 prescribes, and any imminent resize (HD-189) has already
happened. Running before measures a configuration that exists in no file and is about to be
deleted.**

The state of the box today (measured 2026-08-28, `i-019fe684b25ad831f`): the app container has
`HostConfig.Memory = 0`, so `-XX:MaxRAMPercentage=50` is taken against *host* RAM and the heap
ceiling is **956 MB on a 1909 MB box with 341 MB available**. `APP_MEMORY_LIMIT=1g` sits in `.env`
and is read by nothing. HD-199 delivers the `mem_limit` line that makes it real; the heap becomes
512 MB.

Six reasons, in descending weight:

1. **The deliverable is sizing guidance, and guidance describes the shipped default.** Every reader
   of `docs/self-hosting.md` runs the bundled compose, which limits the app container. A capacity
   figure measured at a 956 MB heap is advice for a configuration this project does not ship and
   tells no self-hoster anything true.
2. **The pre-HD-199 result is already derived and does not need a window to discover.** HD-199 §9.1
   works it out: a JVM entitled to 956 MB on a box with ~341 MB available and, before 2026-08-28, no swap
   is a latent *host* OOM in which the kernel chooses the victim, and the victim may be `postgres`.
   Spending a production window rediscovering a documented conclusion is the definition of a run
   that only confirms a prediction — and it would very likely terminate on that conclusion before
   producing a single number for any of the mixes.
3. **Numbers outlive their caveats.** A figure measured before the limit lands would be quoted after
   it lands, by us, in a document, with the caveat two paragraphs away.
4. **HD-199 also delivers `RATE_LIMIT_TRUST_FORWARDED_FOR`,** which changes how the per-IP auth
   budget keys. The harness's token-minting phase (§4.4) runs into that limiter. Measuring before
   means measuring a login path that is about to behave differently.
5. **The predictions under test are heap-sensitive.** `REPORTS_MAX_ROWS` is costed against a
   *reference heap of 512 MB* — that is the number in the `docs/self-hosting.md` row. Testing that
   costing against a 956 MB heap tests nothing about the shipped claim.
6. **HD-189 first if it is imminent,** for HD-199 §9.4's reason applied one layer out: a capacity
   number for a box that no longer exists is worthless, and measuring twice to publish once is
   waste.

**The rule to write down is a category, not an ordering of two tickets.** Ticket numbers go stale;
the property does not:

> This measurement is valid only for the configuration it was taken against, and it is invalidated
> by any change to the app container's memory limit, the heap ceiling, the instance type or size,
> the connection-pool size, the statement or lock bounds, the report/search caps, or what else runs
> on the box. Every published figure carries the fingerprint of the configuration it belongs to
> (§4.9), and a change to any of those is a reason to re-run rather than to reinterpret.

**One change already happened today and must be in the fingerprint: swap.** The box had **0** swap;
1023 MB at `swappiness=10` was added on 2026-08-28, before any run. This is not a footnote — it
**changes the failure mode this run will observe**. Without swap, exhausting memory is a kernel OOM
kill: exit `137`, abrupt, unmistakable, and absent from every application log. With swap, the same
pressure becomes *catastrophic latency* while everything stays alive and every liveness check passes.
A harness written to look for a dead container will report "no memory problem found" on a box that
spent the whole window paging the JVM heap to an EBS volume. §4.8 therefore carries swap counters as
a first-class attribution signal, not as host trivia.

---

## 3. Scope

### 3.1 In scope

| # | Deliverable |
|---|---|
| 1 | `ops/loadtest/` — the harness: k6 scenarios, the dataset generator, the capture scripts, and a README that is the run procedure |
| 2 | A generated dataset with a justified shape and size (§4.2), generated by the harness and torn down by it |
| 3 | Latency and error targets per endpoint class, **fixed before the run** (§4.3) |
| 4 | The workload mixes and the probes that test the standing predictions (§4.5–§4.6) |
| 5 | A resource-attribution table: signal → named resource → the metric that shows it (§4.8) |
| 6 | The production-window procedure: preconditions, capture, abort conditions, return to a known state (§5) |
| 7 | `RESULTS-<date>.md` — the run record, including the configuration fingerprint and a verdict per prediction |
| 8 | `docs/self-hosting.md` → *Requirements* rewritten from measurement, and the `APP_MEMORY_LIMIT` / `DB_POOL_MAX_SIZE` / `REPORTS_MAX_ROWS` rows corrected where they are wrong |

### 3.2 Out of scope, named so the harness is not read as covering them

- **Fixing anything.** No index, no query rewrite, no cap change, no pool change, no code change of
  any kind. Findings become tickets; HD-182 is already open and waiting for exactly this.
- **Application code, migrations, properties, compose files.** This ticket adds no `@Entity`, no
  endpoint, no Flyway version, no environment variable the application reads. §6–§9 say so in the
  terms the review gates read.
- **Turning any limiter off.** The ticket is explicit and it is right: a run with the throttles
  disabled measures a product nobody ships. The harness reaches saturation with enough distinct
  principals instead (§4.4), and the limiters' behaviour is a *result*, not an obstacle.
- **Changing the production observability configuration for the run's convenience.** In particular
  no Prometheus remote-write receiver (§4.7), no new scrape target, no new exporter. `observability/`
  is a synced path; editing it on the box raises `ConfigDrift` and edits a file the next deploy
  overwrites.
- **Continuous / CI load testing.** This is a procedure run deliberately, by a person, in a window.
  A nightly load test against production is a different ticket with a different risk profile.
- **Multi-instance or horizontal-scale measurement.** One instance, one replica. The per-process
  limiters and the per-node caches behave differently at N replicas and that is not what is being
  asked.
- **Frontend / browser performance.** This measures the API. A page's render cost is a separate
  question with separate tooling.

### 3.3 Non-goals

This does not produce a supported capacity *guarantee* for anybody's install. It produces one
measurement of one configuration, generalisable by category ("this class of box, this shape of
tenant") and never by promise.

---

## 4. Behaviour & rules — how the measurement is constructed

### 4.1 The harness: k6, and why

**Recommended: k6, pinned to an exact version, run from a dedicated load-generator instance.**

| Property k6 has | Why it decides the choice here |
|---|---|
| Single static Go binary | Nothing to install on the generator, no runtime that competes with the thing being measured. A JVM-based generator makes the generator's own GC part of the experiment. |
| `thresholds` are pass/fail and abort the run | **This is the decisive one.** The targets in §4.3 are expressed *in the script* and the run exits non-zero when they are breached. The ticket's "decide targets before the run, or you get graphs and no verdict" becomes a mechanism instead of a discipline. |
| Per-request `tags` | Latency is reported per *endpoint class* (§4.3) rather than as one aggregate, which is what "the concurrency at which the target is breached" needs. |
| Both executor models | `ramping-vus` (closed, with think time) answers "how many people"; `ramping-arrival-rate` (open) avoids coordinated omission on the expensive surface. Both are needed and both are one config block. |
| `dropped_iterations` | The harness reports its **own** saturation. "A harness that saturates itself measures the harness" stops being a warning and becomes an abort condition (§5.4). |
| Cookie jar per VU | The refresh-token flow (§4.4) works without hand-rolling session state. |

Rejected, with reasons rather than preferences: **JMeter** and **Gatling** — JVM load generators, so
the generator's GC and heap enter the measurement, and both are heavier per virtual user, which
lowers the concurrency one generator can reach before §5.4 fires; Gatling additionally needs a build.
**Locust** — needs a Python runtime on the generator and one process is bounded by the GIL, so
reaching interesting concurrency means orchestrating workers, which is complexity paid for nothing
here. **`ab` / `wrk` / `hey`** — no scenario model, no per-class thresholds, no auth or session
handling; they answer "how fast is one URL", which is not the question. **A hand-written Go or Java
driver** — everything above plus we would be maintaining a load tool instead of using one.

**Licensing, because this project is licence-sensitive.** k6 is licensed **AGPL-3.0**. It is invoked
as a separate binary and nothing of it is linked into or distributed with Hamstrack, so it creates no
obligation on Hamstrack's Elastic License 2.0 terms. The scripts in `ops/loadtest/` are ours and
carry the repository's licence. Verify the licence at the version actually pinned rather than
trusting this paragraph.

**Where it runs: a separate EC2 instance in the same region, hitting the origin directly.** Two
constraints force this:

- **Not on the box.** A load generator on a 2-vCPU host with ~341 MB available memory is the
  experiment's largest confounder.
- **Not through Cloudflare.** The site is proxied; a sustained synthetic burst through the edge
  measures Cloudflare's caching, connection reuse and bot handling, and risks being challenged or
  rate-limited by a system whose configuration is not ours. Target the origin directly with an
  explicit `Host` header and a resolve override, so the request path is
  `generator → Caddy → app` — the same path a real request takes minus the edge. **Record that the
  edge is excluded**: the published capacity is origin capacity, and Cloudflare only ever adds
  latency and removes load.

Generator sizing: comfortably larger than the target — a `c7i.large`-class instance is
over-specified on purpose, and its own CPU/memory are captured so the numbers can be shown to be
about the target and not about the generator. It is created for the window and terminated after.

### 4.2 The dataset — shape first, size second

Production is **13 MB, 263 issues, 162 comments, 1190 history rows, 4 workspaces, 4
projects**. Measuring against that measures nothing: every query the expensive surface issues is
dominated by row counts, and at these counts PostgreSQL will sequential-scan everything in cache and
return in single-digit milliseconds regardless of how the query is written.

**An issue count is not a fixture.** Different generated entities drive different query paths, and a
fixture that generates only issues leaves most of the expensive surface untouched:

| Generated | Because it is what makes this path cost anything |
|---|---|
| `issues` | board list (`BOARD_MAX_ISSUES` 500), backlog sections, search page **and** its count query, every report's row set |
| `issue_history` | the flow report's transition counts and its O(project history) opening balance; cycle time; the issue-detail history tab |
| `comments` | issue detail — the browsing mix's heaviest single read |
| custom field values (JSONB) | search predicates that cast JSONB, and issue detail's field rendering |
| `labels` + issue↔label rows | HQL name resolution loads the **workspace's whole label catalog** on every `/search`, `/search/schema` and `/search/suggest`; `labelMatch=all` compiles a sub-select per label |
| `components`, `versions`, issue↔version rows | HQL resolution loads **each visible project's** component and version catalogs on every search; the fix-version filter is a correlated `EXISTS`; `GET …/versions` is unpaged |
| `sprints` + `sprint_scope_events` | velocity, burn-up, sprint review, and the planning view's `(open sprints + 1) × section cap` assembly |
| `users`, workspace/project members, roles | permission resolution per request, and the **full member scan** every `ResolutionContext` build performs |
| attachments (metadata; a handful with real bytes) | issue detail's attachment list. Blobs are deliberately near-zero — this is a disk experiment we do not need to run |

**Distribution matters more than volume, and this is where a synthetic fixture goes wrong.** A
uniform fixture makes every query uniformly cheap and every plan the same plan. The generator must
skew deliberately:

- **Project sizes 80/20** — one project holds most of the workspace's issues, the rest are small.
  Real tenants look like this and a per-project report's cost follows it.
- **Status distribution weighted to done** (~65% closed, ~20% in progress, ~15% open), because the
  flow report's opening balance counts everything closed *before* the window, and a fixture where
  nothing is closed deletes that cost entirely.
- **History clustered in the trailing 90 days** with a long tail behind it, so the default report
  window (`min(90, REPORTS_MAX_WINDOW_DAYS)`) actually hits rows rather than an empty range.
- **Long tail on comments per issue** (most 0–2, a few 50+), so issue detail has both a cheap and an
  expensive case and p99 means something.
- **Text lengths drawn from a realistic distribution**, not a fixed filler string — `text ~` search
  leaves are two unanchored `LIKE`s over a TEXT column and their cost is a function of the data's
  length, while a fixed string also lets the planner and the page cache behave unrealistically well.
- **A deterministic seed** (`setseed`), so two runs generate byte-identical data and a re-run is a
  re-measurement rather than a new experiment.

**Size — two workspaces, and the second one is not decoration:**

| | Workspace A ("large tenant") | Workspace B ("typical tenant") |
|---|---|---|
| projects | 4, sized 80/20 | 2 |
| issues | ~40 000 (largest project ~25 000) | ~2 000 |
| issue history | ~8 rows/issue → ~336 000 | ~16 000 |
| comments | ~2.5/issue avg, long tail → ~105 000 | ~5 000 |
| custom field values | 2/issue → ~84 000 | ~4 000 |
| labels (catalog) | 400 | 60 |
| issue↔label | ~2/issue → ~84 000 | ~4 000 |
| components / versions | 40 / 30 per project | 10 / 10 per project |
| issue↔version | ~1.2/issue → ~50 000 | ~2 400 |
| sprints | 20 open + 40 completed per project | 5 + 10 |
| sprint scope events | ~40 000 | ~2 000 |
| members | 120 | 15 |

Roughly **750 000 rows and an estimated 300–400 MB on disk with indexes**. Justification for each
number that is not obvious:

- **25 000 issues in one project exceeds `REPORTS_MAX_ROWS` (20 000)** on purpose: the row-level
  reports must actually hit their cap and set `meta.truncated`, because the capped case is the
  expensive case and the one the heap costing is about.
- **40 000 is 80× `BOARD_MAX_ISSUES`**, so the board's cap does real work rather than being a
  formality.
- **400 labels** is under `MAX_LABELS_PER_WORKSPACE` (1000) and large enough that loading the whole
  catalog on every search is measurable. Generating the full 1000 would measure the cap rather than
  a plausible tenant; the cap's cost is a separate, cheaper experiment if anyone wants it.
- **20 open sprints per project** is exactly `AGILE_MAX_OPEN_SPRINTS`, so the planning view assembles
  its full `(20 + 1) × 300` budget — the one unpaged response in the product with a five-figure row
  bound.
- **120 members** makes the full member scan inside `ResolutionContext` non-trivial and gives the
  harness more distinct principals than it needs (§4.4).
- **Workspace B exists for two reasons**: it is the foreign target for the tenancy canary (§4.5), and
  it makes "cost versus tenant size" a comparison inside one run instead of a claim.

**The size is bounded by disk, and today the disk cannot take it.** The box is at **79% used with
1.7 GB free** — after the 1 GB swapfile. `DiskFilling` fires below 15% free. A 300–400 MB fixture
plus WAL churn plus the run's own writes would approach that, and a full disk is the one failure in
this whole plan that can damage real data rather than merely producing a bad measurement.
**Precondition, not a nice-to-have: grow the EBS volume before the window so that free space is at
least 5 GB and at least 3× the projected fixture.** gp3 grows online with no downtime; the cost is
about a dollar a month and the box is close to its own disk alert anyway. If the volume is gp2,
record its burst-balance state as well — that is a second credit bucket that can run out and be
misread as an application problem (§4.8).

**How it is generated: SQL, through `psql`, not through the API.** Hundreds of thousands of rows
through HTTP on a 2-vCPU box would take longer than the whole window. The cost of SQL generation is
that it can write states the application never produces, so three guards:

1. The generator **refuses to run** unless the target database's Flyway version matches a value
   pinned in the script, so a schema change breaks it loudly instead of silently generating rows the
   entities cannot map.
2. It respects the things the application maintains by hand: `issue_seq` resynced to `MAX(number)`
   per project after generation, `position` spaced by the same `RANK_STEP` the ranking service uses,
   `@Version` at 0, `created_at`/`updated_at` populated, UUID v7 ids so ordering behaves as it does
   in production.
3. **A verification pass through the real API** after generation: fetch a board, a backlog section,
   an issue with its comments and history, run a search, run each report, and assert 200 with a
   plausible shape. A fixture that the API cannot read is not a fixture, and this is the check that
   catches a generator that drifted from the schema in a way Flyway's version does not reveal.

Generation happens **the day before the window**, followed by `VACUUM (ANALYZE)` and an overnight
settle, so the planner has statistics and autovacuum is quiet. A run against a freshly-bulk-loaded
table with no statistics measures a cold planner, which is a real phenomenon and not the one being
asked about.

### 4.3 The targets, fixed before the run

A target invented after seeing the graphs is not a target. These are fixed here, encoded as k6
`thresholds`, and anchored — where possible — to opinions this project has **already shipped** in
`observability/grafana/provisioning/alerting/rules.yml`, so they are not one more thing invented in
a design document.

| Endpoint class (k6 tag) | p95 | p99 | Error budget |
|---|---|---|---|
| `browse` — board list, backlog sections, issue get, comments, history, project config, label/component/version lists | 600 ms | 1500 ms | 5xx = **0**; no unexpected 4xx |
| `search` — `POST …/search`, `/search/schema`, `/search/suggest`, saved-filter CRUD | 1500 ms | 4000 ms | 422 `STATEMENT_BUDGET_EXCEEDED` = **0**; 429 ≤ 1% |
| `report` — the project reports, their `.csv` siblings, and insights | 3000 ms | 8000 ms | 422 `STATEMENT_BUDGET_EXCEEDED` = **0**; 429 ≤ 1% |
| `write` — issue PATCH, transition, comment, rank, sprint scope change | 800 ms | 2500 ms | 5xx = **0**; 409 ≤ 1%; rank-rebalance 429 ≤ 2% |
| `auth` — login, refresh | 1000 ms | 3000 ms | 5xx = **0** |

Why these numbers and not others:

- **The aggregate ceiling is the project's own `HighLatency` rule: HTTP p95 > 1 s for 10 minutes.**
  The per-class numbers are chosen so that the mix-weighted p95 stays under it at target concurrency,
  browsing dominating the volume. A target looser than the alert we already ship would mean declaring
  acceptable a state that pages an operator.
- **The report p99 sits deliberately below `DB_STATEMENT_TIMEOUT_MS` (10 s).** A p99 above the
  statement bound cannot exist without the instance cancelling statements, so a target above it would
  contradict the refusal contract 0.17.0 shipped.
- **5xx = 0 rather than a ratio.** The whole point of the 0.17.0 work is that saturation produces
  *named refusals* — 422, 409 + `Retry-After`, 429 — and not silence or 500s. Any 5xx is therefore a
  **finding**, not a budget item. `HighErrorRate`'s 5% is a paging threshold, not a target.
- **409 ≤ 1% on writes.** A retryable refusal is a correct answer at low rates; above about one in a
  hundred the product is asking users to retry more often than they will tolerate.
- **The 422 budget is zero at target.** That is what lets the run answer "at what concurrency does
  the statement bound begin to fire" — the *first* 422 is a data point and a stage boundary, not
  noise to be averaged away.

**Breach, defined once so the report cannot equivocate:**

> The reported capacity for a mix is the **highest completed stage at which every target above held
> for the whole hold period and no provisioned alert rule's condition was met for its `for:`
> duration.** The breach stage is the next one up, and the report names the resource that breached
> using §4.8.

Two properties of that definition matter. It is expressed over the *category* "every provisioned
rule", so a rule added later is automatically part of the bar and no list here goes stale. And it
requires the target to hold *for the whole hold*, which is what stops a stage passing on a warm-up
artefact.

### 4.4 Principals: reaching saturation without merely reaching 429

The expensive-surface budgets key on the **user id** (`PerPrincipalMinuteBudget`, one fixed window
per principal per minute): 120 search requests and 60 report requests per minute each. The ticket's
instruction is to capture what the limiters do rather than disable them, so the harness must be able
to saturate the *instance* without any one principal exhausting its own allowance first.

**Rule: one distinct account per virtual user, and the account pool is at least the maximum VU
count.** The fixture's 135 members are the pool; a VU takes one and keeps it for the run.

Three consequences that have to be designed for rather than discovered:

1. **At realistic think times the limiters do not bind, and that is itself a finding.** A reporting
   VU with 20 s think time makes 3 requests a minute against a 60/minute allowance. So the browsing,
   search and write mixes will reach the connection pool long before they reach any budget — which
   means **the limiters are not what protects this instance from ordinary use**, and the number that
   does the protecting is `DB_POOL_MAX_SIZE`. Say so in the results either way.
2. **Token minting runs into the per-IP auth limiter.** `/api/auth/login` is in the auth filter's URL
   set at `RATE_LIMIT_AUTH_IP_PER_MINUTE` (15/min), keyed on the peer — and after HD-199, on the
   rightmost `X-Forwarded-For`. All logins come from one generator address. The harness therefore
   mints tokens in a **pre-flight phase, client-side throttled to 10 logins per minute**, before the
   measured window opens; ~135 accounts takes about fifteen minutes and doubles as a smoke test.
   Raising the limiter for the window is rejected: it changes the configuration under measurement.
3. **Access tokens expire at 30 minutes, mid-run.** `/api/auth/refresh` is deliberately *excluded*
   from the auth filter's URL set and is cookie-driven, so each VU keeps its refresh cookie and, on a
   401, calls refresh once and retries. A second 401 fails the iteration loudly. Refresh requests are
   tagged `auth` and counted, never folded into the class whose latency they would distort.

### 4.5 The mixes

Each mix is a k6 scenario with its own executor, its own think-time model and its own tag set. The
primary executor is **`ramping-vus` with think time**, because the question is "how many people", and
a VU with think time is a person. Each mix is additionally replayed at its breach point as
**`constant-arrival-rate`** for a 15-minute soak, because a closed model hides coordinated omission
and a capacity figure that only holds for four minutes is not a capacity figure.

**Ladder:** 1 → 2 → 5 → 10 → 15 → 20 → 30 → 45 → 60 → 80 → 100 VUs, each stage 1 min ramp + 4 min
hold, stopping one stage after the first breach. Between mixes, a settle period that ends on a
**condition rather than a clock**: heap-after-collection, Hikari active connections and PostgreSQL
backend count have all returned to their pre-mix baseline.

| Mix | Composition | Think time | The question it answers |
|---|---|---|---|
| **Browsing** | board list, backlog + a section, issue detail (issue + comments + history + attachments list), project config, label/component/version lists, notifications poll, **and one held SSE stream per VU** | 4–8 s, log-normal | How many people can do what most people actually do |
| **Reporting & searching** | the project reports and their `.csv` siblings, insights, `POST …/search` with a realistic predicate mix (including `text ~` leaves and a `labelMatch=all`), `/search/schema`, `/search/suggest` | 15–30 s | The expensive surface: where the budgets, the statement bound and the heap live |
| **Writing** | 80% distinct-issue PATCH / transition / comment; 15% drag-to-rank concentrated in **one** project; 5% sprint scope changes | 10–20 s | The only mix that takes row locks, so the only one that can produce `409 + Retry-After` — and, via the concentrated ranking, the per-project rebalance 429 |
| **Tenancy canary** | 1 request per 10 s: a member of workspace A requesting a resource in workspace B | — | Runs alongside every mix. **Any status other than 404 is an abort and a security incident, not a data point.** This is what satisfies "a load fixture spanning several workspaces must not accidentally test cross-tenant reads as if they were normal": the cross-tenant request exists exactly once, at negligible rate, as an assertion |

**The SSE stream in the browsing mix is deliberate and is the kind of thing a naive harness omits.**
The real SPA opens one per session and holds it, so "100 people using the instance" is 100 held
connections plus their requests. It may turn out to be the binding resource before anything else is,
and a run without it would have measured a product nobody uses.

### 4.6 The probes — the two standing predictions, made falsifiable

These are not extra load. They are the experiments the ticket actually asks for, and each has a
stated way to come out against the prediction.

**Probe P1 — the entitlement probe (tests HD-182 directly).**
One principal spends **exactly their documented allowance and no more**: 120 search + 60 report
requests per minute, no think time, capped client-side at the entitlement so the run cannot be
dismissed as abuse. Simultaneously, a **victim probe** — a *different* principal running the browsing
mix at 5 VUs — measures what an ordinary user experiences.

- **The prediction holds** if the victim probe's `browse` class breaches its target, or Hikari
  `pending` stays above 0, while the entitled principal receives no 429 at all. That is
  "one user, entirely within the rules, degrades everyone" — which is HD-182's premise.
- **The prediction fails** if the instance absorbs one fully-entitled principal with every target
  intact. Then the arithmetic (600 connection-seconds/minute supplied against 180 requests/minute
  entitled, each able to hold a connection for the full statement bound) has over-estimated the
  *actual* hold time per request, and the number worth measuring instead is the **real mean
  connection-hold time per request class** — which the harness captures anyway, from Hikari usage
  timings. Report it either way; it is the number that makes HD-182 actionable rather than alarming.

**Probe P2 — the report heap probe (tests the ~1.9 KB/row costing).**
A step ramp of concurrent requests against a row-level report on the 25 000-issue project, so every
response materialises the full `REPORTS_MAX_ROWS` cap. Capture heap-after-collection at each step.

- **Held** if measured transient heap per concurrent capped request lands within a factor of two of
  the costed 38 MB (i.e. ~19–76 MB).
- **Did not hold** in either direction, and both directions are useful: materially less means
  `REPORTS_MAX_ROWS` is more conservative than it needs to be and the docs row overstates the danger;
  materially more means the default is closer to the heap than the documentation claims.
- **A third proposition falls out of this and should be tested in the same probe**, because it is the
  one nobody has written down: every row-level report holds a pooled connection for its whole
  assembly (the service is `@Transactional(readOnly = true)` end to end), so the number of reports
  materialising simultaneously is bounded by `DB_POOL_MAX_SIZE`, and the true heap exposure is
  `pool size × max rows × bytes per row` — **10 × 20 000 × 1.9 KB ≈ 380 MB of a 512 MB heap**. If that
  holds, then the pool is not only the concurrency bound but the heap bound, and the advice in
  `docs/self-hosting.md` to *raise `DB_POOL_MAX_SIZE` alongside `DB_STATEMENT_TIMEOUT_MS`* silently
  raises heap exposure at the same time. That interaction is a finding for HD-182, not a fix here.

**Probe L — the limiter probe.** One principal deliberately exceeding both budgets, for one minute,
at low concurrency. Its only purpose is to confirm the refusals are the shape the documentation
claims — 429 with a `Retry-After`, counted under `hamstrack_ratelimit_hit_total{kind=…}` with the
right `kind` — so that a 429 appearing during a real mix can be attributed with confidence.

### 4.7 What is captured, and how it gets off the box

**Two tiers, and the split is what keeps the harness usable outside this box.**

**Tier 1 — portable, sufficient for a verdict, works on any install including a self-hosted one:**

- k6's own output: per-class latency percentiles, request rate, status distribution over time,
  `dropped_iterations`, `http_req_connecting` vs `http_req_waiting`, and the threshold pass/fail
  summary. Written locally on the generator as JSON plus a `handleSummary` report.
- The application's own `/actuator/prometheus`, scraped **by the harness** at 5-second intervals and
  written to a file. That endpoint is internal to the compose network, so the sample runs on the box
  as a small loop for the duration and its output is collected afterwards. Everything the JVM, the
  pool and the HTTP layer expose is in there, on every install, whether or not the optional
  observability stack runs.
- A **`pg_stat_activity` sampler** on the box: every 5 s, snapshot `state`, `wait_event_type`,
  `wait_event` and backend count, plus `pg_stat_database` counters, to CSV. This is captured
  independently of postgres-exporter because lock waits and connection states are the attribution
  signal for two rows of §4.8, and depending on which collectors a given exporter build enables is a
  way to discover mid-window that the number you needed was never being recorded.
- `docker stats` sampled every 5 s (container RSS against limit), and `/proc/meminfo` + `/proc/vmstat`
  every 5 s (host available memory, **swap in/out**).

**Tier 2 — this box only, because the optional stack happens to run here:** the existing Prometheus
series, exported after the run via its HTTP API over a range query into the results directory. Run
the export on the box (`docker exec` into the Prometheus container) rather than port-forwarding; it
is one command and needs no tunnel.

**Explicitly not done: k6's Prometheus remote-write output.** It would require enabling a remote-write
receiver on the production Prometheus, which means editing a file under `observability/` — a *synced*
path. That raises `ConfigDrift`, is overwritten by the next deploy, and opens a write endpoint on a
metrics store for the convenience of one afternoon. Correlate by timestamp instead: both sides have
clocks and the run is bracketed by recorded UTC timestamps.

**The signal list the ticket requires, mapped to what actually emits it:**

| Required | Metric / source |
|---|---|
| latency percentiles per endpoint class | k6 per-tag trends; cross-checked against `http_server_requests_seconds` by `uri` (mapped pattern) |
| response-code mix over time | k6 status counters per tag; `http_server_requests_seconds_count{status}`; `hamstrack_db_statement_budget_exceeded_total{route}`; `hamstrack_ratelimit_hit_total{kind}` |
| Hikari active / pending / timeout | `hikaricp_connections_active`, `_pending`, `_timeout_total`, `_acquire_seconds`, `_usage_seconds` |
| PostgreSQL connections and lock waits | the `pg_stat_activity` sampler (`state`, `wait_event_type='Lock'`); postgres-exporter as a cross-check |
| GC pause and heap after collection | `jvm_gc_pause_seconds_{count,sum,max}`, `jvm_gc_live_data_size_bytes`, `jvm_gc_max_data_size_bytes`, `jvm_memory_used_bytes{area="heap"}` |
| container memory against its limit | `docker stats`; cAdvisor `container_memory_working_set_bytes` / `container_spec_memory_limit_bytes` |
| CPU | `node_cpu_seconds_total`, `container_cpu_usage_seconds_total`, generator CPU, **and CloudWatch `CPUCreditBalance` / `CPUSurplusCreditBalance`** |

The last one is not in the ticket's list and belongs there. **This is a `t3.small` — a burstable
instance.** Sustained load draws down a credit balance, and what happens when it empties depends on
the credit specification: in `standard` mode the box is throttled to its baseline (20% of 2 vCPUs)
and every latency number after that moment is a measurement of the credit balance rather than of the
application; in `unlimited` mode it does not throttle but bills surplus. **Read
`describe-instance-credit-specifications` in pre-flight and record it with the results.** A run that
does not know which mode it is in cannot distinguish "the application serialises" from "the CPU was
taken away", which is the precise confusion this whole ticket exists to end.

### 4.8 Attribution: the signal → resource table

This table is the deliverable. "The concurrency at which the target is breached" is a number anybody
can produce; "and the resource that breached first, named with the metric that shows it" is the part
that makes the number actionable.

| Observed | The resource that ran out | The metric that shows it |
|---|---|---|
| TTFB rises; `hikaricp_connections_pending` > 0; `hikaricp_connections_acquire_seconds` climbs; DB CPU moderate | **Connection pool** (`DB_POOL_MAX_SIZE`) | `hikaricp_connections_pending`, `_acquire_seconds`, `_active` pinned at max |
| The above, plus 5xx and `hikaricp_connections_timeout_total` rising | Pool exhaustion past Hikari's 30 s `connectionTimeout` | `hikaricp_connections_timeout_total` |
| `422` `STATEMENT_BUDGET_EXCEEDED` | **A single statement's cost** — a query or a missing index, on this much data | `hamstrack_db_statement_budget_exceeded_total{method,route}` + the matching WARN line |
| `409` + `Retry-After` on writes | **Row-lock contention** | `http_server_requests_seconds_count{status="409"}` + sampler rows with `wait_event_type='Lock'` |
| `429` | **An entitlement ceiling**, and `kind` says which — including `rank_rebalance`, which is a per-project cooldown and not a per-user budget | `hamstrack_ratelimit_hit_total{kind}` |
| GC pause sum climbing; `jvm_gc_live_data_size_bytes` approaching `jvm_gc_max_data_size_bytes`; throughput falling with pool and CPU both idle | **Heap** | `jvm_gc_live_data_size_bytes` / `jvm_memory_max_bytes{area="heap"}`, `jvm_gc_pause_seconds_sum` |
| App container gone, exit code `137`, nothing in any application log | **Container memory limit** — a kernel OOM kill, not an `OutOfMemoryError` | `docker inspect .State.ExitCode`, cAdvisor working set against limit, `dmesg` |
| Host available memory collapses; **swap-in/out non-zero**; latency degrades with nothing dying | **Host memory** — and note this presents as latency, not death, because swap was added on 2026-08-28 | `node_memory_MemAvailable_bytes`, `node_memory_SwapFree_bytes`, `node_vmstat_pswpin/pswpout` |
| Some *other* container dies first | **Host memory**, with the kernel choosing the victim. Post-HD-199 the app is the only *workload* container with a declared ceiling — `postgres` and `caddy` have none (the observability seven do) — so a ceiling on the app makes it the predictable victim, never the protected one. Verify rather than assume: `docker inspect $(docker ps -q) --format '{{.Name}} {{.HostConfig.Memory}}'`, which on 2026-08-28 returned `0` for the app as well | container exit codes, `dmesg` |
| Host CPU idle → 0, run-queue grows, all of the above flat | **Host CPU** | `node_cpu_seconds_total{mode="idle"}`, load average |
| The above **and** `CPUCreditBalance` at 0 | **Burst credits**, not CPU. Everything measured after this moment is about the credit bucket | CloudWatch `CPUCreditBalance`, `CPUSurplusCreditBalance` |
| Disk read throughput rises sharply, PG cache-hit ratio falls, latency follows | **Page cache / EBS IOPS** — the fixture no longer fits in RAM | `node_disk_io_time_seconds_total`, `pg_stat_database` hit ratio, CloudWatch volume queue length (and gp2 burst balance, if gp2) |
| TTFB rises with `hikaricp_connections_pending` = 0, DB idle, `jvm_threads_live_threads` plateaued near 200 + baseline; `http_req_connecting` also rising | **Request threads / accept queue** (Tomcat's 200-thread default, 100-deep backlog) | `jvm_threads_live_threads`, k6's `http_req_connecting` vs `http_req_waiting` |
| k6 `dropped_iterations` > 0, or generator CPU > 70% | **The harness.** Discard the sample | k6 summary, generator `node_cpu_seconds_total` |

Note the last row is on the same table as the rest on purpose. A harness that cannot detect its own
saturation reports the generator's limits as the product's.

Tomcat's own thread metrics are not exposed (they need the MBean registry enabled, which is an
application property change and therefore out of scope — §11 Q3). The proxy above is sound: DB-pool
queueing and thread-pool queueing both raise TTFB, and `hikaricp_connections_pending` is what
separates them.

### 4.9 The configuration fingerprint

Every published figure carries the fingerprint of the configuration it belongs to, recorded in
`RESULTS-<date>.md` from commands whose output is pasted, not from what the repository says:

- image digest and `APP_IMAGE_TAG`; `.deployed-sha` and `.deployed-image-tag`
- `docker inspect … .HostConfig.Memory` for **every** container, not only the app
- `java -XX:+PrintFlagsFinal -version | grep MaxHeapSize` from inside the app container
- the values actually in effect for the pool, the statement and lock bounds, the report/search caps
  and the board/agile caps
- instance type, credit specification and starting credit balance; volume type, size and IOPS
- `free -m` including swap and `swappiness`; `df -h`
- the fixture's generator seed and its verification row counts
- what else was running on the box (the observability stack, its container count and its ceilings)
- Prometheus/Loki retention at the time, since the export's resolution depends on it

The fingerprint is not paperwork. It is the thing that lets a reader in six months decide whether the
number still applies, and it is the mechanism behind §2's category rule.

---

## 5. Edge cases, failure modes, and the production window

### 5.1 Preconditions — what must be true before the window opens

Every one of these is a hard gate; a "no" postpones the window rather than adding a caveat.

1. **HD-199 merged, the container memory limit in force** (`HostConfig.Memory` non-zero, read back
   from `docker inspect`, not from `.env`), and the 48-hour watch period from its §9.3 completed
   with no exit `137` and no `HighLatency`.
2. **No imminent HD-189** (resize, or moving observability off the box). If it is scheduled within
   the next fortnight, do it first.
3. **`hamstrack_config_drift` reads 0 for every scope**, and `.deployed-sha` matches the released
   commit. Measuring a box that differs from the repository produces a number that describes nothing.
4. **A snapshot of the EBS volume, completed** — not started — and its id recorded. This is the
   owner's stated condition and it is the only real undo.
5. **Free disk ≥ 5 GB and ≥ 3× the projected fixture**, after the volume grow (§4.2).
6. **The backup job's last success is recent** (`hamstrack_backup_last_success_timestamp_seconds`),
   so there is a second recovery path that is not the snapshot.
7. **The fixture is generated, verified through the API, `VACUUM (ANALYZE)`d, and has settled
   overnight**; its row counts are recorded.
8. **The teardown has been rehearsed** — on a local Postgres with the same fixture, generated and
   torn down, and the category assertion (§5.5) passes. A teardown first attempted on production is
   not a teardown.
9. **A dry run at 1 VU against production has passed**, including the abort path: the operator has
   actually stopped a run with the documented command and seen it stop.
10. **The window is agreed and announced**, with an explicit statement that the instance may be slow
    or unavailable during it, and a named person watching Grafana with the abort command ready.
11. **Nobody is mid-onboarding.** The instance has a handful of real users; check that none of them is
    depending on it during the window rather than assuming.

### 5.2 The window, in order

| Phase | Duration | What happens |
|---|---|---|
| Pre-flight | 30 min | Fingerprint capture (§4.9); baseline metrics for 10 idle minutes; token minting at 10 logins/min; the tenancy canary started |
| Browsing mix | ~45 min | Ladder to breach, then one stage beyond, then stop |
| Settle | condition-based | Heap, Hikari active and PG backends back to baseline |
| Reporting & searching mix | ~45 min | As above |
| Settle | condition-based | |
| Writing mix | ~45 min | As above |
| Probes | ~30 min | P1 entitlement + victim, P2 report heap ramp, L limiter |
| Soak | 15 min | Constant arrival rate at the lowest breach point found, to confirm the capacity figure holds |
| Teardown | 45 min | §5.5 |
| Verification & buffer | 45 min | §5.6 |

At **T-15 minutes** from the window's hard end, stop escalating regardless of where the ladder is.
An unfinished ladder is a partial result; an overrun window is an incident.

### 5.3 Abort conditions — stop the run immediately

These are checked by the watching operator and, where they are machine-readable, by a watchdog loop
that kills k6:

1. **Free disk below 500 MB.** The only failure here that can damage real data. Non-negotiable and
   checked most frequently.
2. **Any container exits** other than by our own action.
3. **`pg_up` is 0**, or `AppDown` fires.
4. **Host available memory below 150 MB, or swap used above 512 MB.** Under memory pressure the
   kernel picks the victim, and `postgres` and `caddy` carry no ceiling at all — so the victim may
   be the database. (Only the observability seven have always had one; the app's arrives with
   HD-199. Confirm with `docker inspect $(docker ps -q) --format '{{.Name}} {{.HostConfig.Memory}}'`
   at the start of the run, and record it with the results.) **Expect `HostMemoryLow` and
   `HostSwapInUse` to fire during the run** — they are provisioned at 200 MiB available / 128 MiB
   swap (HD-189), i.e. deliberately *above* both abort thresholds here, so on a healthy run they are
   the warning that precedes the abort and not an incident. Silencing them for the window loses the
   only host-side signal the run has; leave them on and note the times they fired. **`HostKernelOOMKill`
   is the exception: it is not expected, and if it fires the run is already invalid** — something on
   the box was killed, and a capacity number measured across a kill measures the kill.
5. **The tenancy canary returns anything other than 404.** Stop, preserve everything, and treat it as
   a security incident. This is the one abort that is not about capacity.
6. **`hamstrack_role_scope_violation_total` increases.** A data-integrity signal that must never be
   masked by "we were load testing".
7. **The real-user probe degrades**: a low-rate scripted journey against a *real* workspace, run
   throughout, showing any 5xx or two consecutive minutes above 5 s p95.
8. **`CPUCreditBalance` below 25% of its starting value** in `standard` mode. Everything measured
   after that point is about credits; stop, record the balance, and decide whether to continue in a
   later window or switch the instance to `unlimited` deliberately (a configuration change, hence a
   new fingerprint).
9. **The operator's judgement.** Written down as a condition on purpose, so using it is following the
   procedure rather than departing from it.

Conditions that stop the *sample* but not the window: k6 `dropped_iterations` > 0 or generator CPU
above 70% (§4.8, last row) — discard the stage, do not escalate further, note it in the results.

### 5.4 Failure modes of the measurement itself

- **A run that only confirms the predictions.** Mitigated by §4.6 giving each prediction a stated way
  to fail, and by requiring the results to report the *measured* connection-hold time per class —
  which is a new number regardless of how the predictions come out.
- **A fixture that is systematically optimistic.** §12's flagged risk. Mitigated by the skew rules in
  §4.2 and by comparing `EXPLAIN` plan *shapes* for the main queries between the fixture and
  production's real data — same plan shape means the fixture is exercising the same code path even
  where the row counts differ.
- **Concurrent real traffic.** With a handful of real users this is noise rather than a confound, but
  it is recorded rather than assumed away: the results state the real-traffic request count during
  each stage, taken from the same `http_server_requests` series with the harness's own tags excluded.
- **Cold caches.** The first stage of each mix is a warm-up and its numbers are reported but not used
  for the breach determination. `RolePermissionCache` is a 10-second per-node cache, so it warms
  almost immediately; the page cache and the JIT do not.
- **A stage that breaches on the harness's own warm-up.** Prevented by requiring the target to hold
  for the whole hold period, not on average across ramp + hold.
- **Clock skew between generator and box**, which would silently misalign every correlation. Checked
  in pre-flight; both are on NTP, and the check is one command.

### 5.5 Returning the box to a known state

1. Stop the harness; confirm no k6 process and no held SSE connection remains.
2. **Teardown by tenancy, not by inventory.** The writing mix creates rows the generator never
   recorded, so deleting "what was inserted" is wrong by construction. Delete by
   `workspace_id IN (<load workspace ids>)` in foreign-key dependency order, then the load accounts
   by their `@load.invalid` address domain. Rely on the existing foreign keys to refuse and *name*
   anything still referencing a load user — a refusal here is the good outcome, and nothing gets a
   `CASCADE` added to make it quieter.
3. **The completeness assertion is about a category**: iterate `information_schema.columns` for every
   table carrying a `workspace_id` and assert zero rows for the load workspace ids in each. A new
   table added later is covered automatically; a hand-written list would be wrong one migration after
   it was written.
4. `VACUUM (ANALYZE)` the touched tables. Record that the space is returned to PostgreSQL and **not**
   to the filesystem — `VACUUM FULL` takes an exclusive lock and is not run on production for this.
   The grown volume from §5.1 absorbs the residual, which is a second reason it is a precondition.
5. Confirm `hamstrack_config_drift` is 0 for every scope and `.deployed-sha` is unchanged — the run
   must not have altered any configuration, and this is how that is demonstrated rather than assumed.
6. A human smoke test: log in as a real account, open a board, open an issue, create and delete a test
   issue, run a report. Plus the two-address rate-limit probe from `docs/ops-prod-hardening.md`.
7. Confirm the product gauges have returned (`hamstrack_users_total`, `hamstrack_issues_total`) —
   they jumped by the fixture's size during the run and a reader of the Product dashboard should not
   later mistake that spike for growth. Annotate the window in Grafana.
8. Take a **post-window snapshot** as the new baseline; retain the pre-window one for 30 days.
9. Terminate the generator instance.

### 5.6 If the run damages something

The recovery order is: (a) if the fixture is the problem, run the teardown; (b) if the database is
inconsistent, restore from the most recent backup, which loses less than the snapshot does; (c) if the
volume is the problem, restore the pre-window snapshot, accepting the loss of everything since. State
this order in the README, because the instinct mid-incident is to reach for the snapshot first and
that is the option with the largest loss.

---

## 6. Data model impact

**None.** No table, no column, no entity, no Flyway migration, no change to any mapping. The fixture
writes *rows* into the existing schema through `psql` and deletes them again; it defines nothing.

Two things `migration-reviewer` would want stated anyway, because a generator that writes rows has to
honour the same rules a migration does: the generator emits **UUID v7** ids, populates `created_at` /
`updated_at`, leaves `@Version` at 0, and resyncs `projects.issue_seq` to `MAX(number)` after
generation — the same repair `V9` performed, for the same reason (a DB-maintained counter that
disagrees with its rows produces a duplicate-key 500 on the next created issue).

---

## 7. API surface

**None.** No endpoint is added, changed or removed; no status code, request shape or response shape
moves. `openapi.yaml`, `docs/api-cloud.md` and `docs/api-dc.md` need no update, and `api-docs-sync`
has nothing to do on this ticket.

Stated explicitly rather than omitted: the harness is a *client* of the published API and must use
only it. If a scenario needs something the API does not offer, that is a finding about the API, not a
licence to add an endpoint for the harness's convenience.

---

## 8. Frontend impact

**None.** No page, component, store or route. `DESIGN.md` is not engaged.

The one frontend-adjacent obligation is on the harness rather than the app: the browsing mix must
reproduce what the SPA actually does per screen — including the held SSE stream and the several
requests a single page load fans out into — or it measures a client nobody runs. The scenario scripts
derive their request sets from the pages (`BoardPage`, backlog, `IssueDetail`, `HomePage`) rather than
from a hand-picked list of endpoints, and the README says so, because a request set assembled by
taste drifts from the product silently.

---

## 9. DC/Cloud implications

**No application behaviour differs between modes, so there is no profile gate and no new application
environment variable.** `dc-cloud-guard` has no wiring checklist to run on this ticket, and that is
recorded here so silence is not mistaken for omission.

The harness itself must not become a Cloud-only artefact, and three rules make that true rather than
merely intended:

- **The scenarios take a base URL, workspace/project ids and credentials.** No AWS, no SSM, no
  Cloudflare, no assumption about a reverse proxy. Pointing them at
  `http://localhost:8080` and a local docker Postgres works unchanged, and that is how the harness is
  developed and how the teardown is rehearsed (§5.1).
- **Tier 1 capture is sufficient for a verdict** (§4.7). It needs only the application's own
  `/actuator/prometheus`, which is compiled into every build in both modes. Tier 2 — the Prometheus
  range export, cAdvisor, node-exporter — is available only where the *optional* stack runs, so a
  self-hoster who does not run it still gets latency percentiles, the status mix, the pool, the heap
  and GC. A harness whose verdict depended on the optional stack would be a Cloud-only tool wearing a
  portable name.
- **The fixture generator takes a `psql` connection**, and its Flyway-version guard is the same on
  both. It does not know what a profile is.

The genuinely useful consequence: **a self-hoster can run this harness to size their own box**, which
is the DC half of the answer this ticket owes and something `docs/self-hosting.md` should point at
once the results land. The Cloud-specific parts (which instance, which credit specification, the
snapshot procedure) live in the run record and the ops runbook, not in the harness.

The one place a mode difference *is* visible is the fixture's storage backend: attachments are S3 on
`cloud` and local disk on `dc`. The fixture generates attachment **metadata** and only a handful of
real blobs precisely so that this difference does not become a difference in what the run measures.

---

## 10. Acceptance criteria

Numbered, individually checkable, each phrased so it can fail.

**The harness**

1. `ops/loadtest/` exists in the repository with the scenarios, the fixture generator, the teardown,
   the capture scripts and a README.
2. A person who did not write it can execute a complete run end to end **from the README alone**,
   against a local instance, with no questions asked of the author. Demonstrated, not asserted: one
   person other than the author does it, and the README's defects found in that attempt are fixed.
3. The k6 scripts encode the §4.3 targets as `thresholds`, so the run exits non-zero on a breach and
   the verdict does not depend on anybody reading a graph.
4. Every request carries an endpoint-class tag, and the summary reports latency percentiles and the
   status mix **per class**, not only in aggregate.
5. The harness detects its own saturation: a run whose generator is the bottleneck is reported as such
   (`dropped_iterations` > 0 or generator CPU > 70%) rather than silently reported as a result.
6. The harness never disables or reconfigures any limiter, and the run record shows the limiter
   configuration in effect (§4.9).

**The dataset**

7. The generator produces the §4.2 shape deterministically from a recorded seed: two runs with the
   same seed produce identical row counts and identical id ordering.
8. It refuses to run against a database whose Flyway version differs from its pinned value, with a
   message naming both versions.
9. The post-generation API verification passes: board, backlog section, issue with comments and
   history, a search, and each report all return 200 with a plausible shape.
10. At least one project exceeds `REPORTS_MAX_ROWS`, and a row-level report against it returns
    `meta.truncated = true`.
11. The teardown removes every generated **and every run-created** row: the category assertion over
    every `workspace_id`-carrying table returns zero, and no load account remains.
12. The teardown has been rehearsed on a non-production database before the window.

**The run**

13. A production window ran with the §5.1 preconditions all satisfied and recorded, including a
    completed EBS snapshot and free disk ≥ 5 GB.
14. For **each** mix, the results state the highest concurrency at which every §4.3 target held for a
    full hold period, and the concurrency at which it first did not.
15. For each breach, the results name **the resource** and cite the specific metric series from §4.8
    that shows it — a breach reported without a named resource and a series does not satisfy this
    criterion.
16. The response-code mix over time is reported per mix, and every non-2xx class present is
    attributed (`429` by `kind`, `422` by `route`, `409` to lock contention, any `5xx` individually —
    a 5xx is a finding and is written up as one).
17. Each standing prediction is answered explicitly as **held / did not hold / could not be
    determined**, with the numbers: the entitlement prediction (§4.6 P1) and the report-heap costing
    (§4.6 P2), including the measured mean connection-hold time per class and the measured transient
    heap per capped report.
18. The pool-bounds-the-heap proposition (§4.6 P2, third bullet) is answered too, since it is the one
    the run is uniquely able to settle.
19. The configuration fingerprint (§4.9) is recorded in full, from command output, including the
    instance's credit specification and the swap configuration added on 2026-08-28.
20. The box is returned to a known state per §5.5 and it is **demonstrated**: drift 0 on every scope,
    `.deployed-sha` unchanged, the smoke test performed by a human, the product gauges back to their
    pre-run values.

**The documentation**

21. `docs/self-hosting.md` → *Requirements* is rewritten from the measurement: what this class of box
    carried, for which mix, with the fixture's shape stated, and with an explicit statement of what
    was **not** measured.
22. Any row in the configuration table the run contradicts is corrected in the same change — the
    `APP_MEMORY_LIMIT`, `DB_POOL_MAX_SIZE` and `REPORTS_MAX_ROWS` rows are the ones most likely to
    need it, and the criterion is "every row the measurement contradicts", not those three.
23. The sizing guidance states the **category** of install the number applies to and the conditions
    that invalidate it (§2's rule), rather than a bare figure that will be quoted after the
    configuration moves.
24. `docs/ops-prod-hardening.md` §5's "what has never been measured" paragraph is replaced by the
    measurement, with its date.
25. The findings are filed as tickets (HD-182 gets the evidence; anything else new gets its own), and
    **nothing is fixed under this ticket**.

---

## 11. Open questions, with the answer I recommend taking

1. **Before or after HD-199?** → **After**, and after any imminent HD-189. §2. This is the one I would
   most want the owner to actively agree with rather than merely not object to, because it makes this
   ticket wait on another.
2. **Does the window take the site down, or does it stay live?** → **Stay live, announce degradation.**
   Taking it down would remove the real-user probe (§5.3 condition 7), which is the only signal that
   distinguishes "the box is saturated" from "the box is saturated *and real people can't work*".
   With about five real users the pollution is negligible and is recorded rather than excluded.
3. **Enable Tomcat's MBean metrics so thread-pool saturation is directly visible?** → **No.** It is an
   application property change, so it needs a deploy, a wiring checklist and a new fingerprint — and
   it would change the configuration under measurement to make the measurement easier. Use the §4.8
   proxy. If the run shows the thread pool is the binding resource and the proxy leaves it ambiguous,
   *that* is the trigger for a follow-up to add the metric properly.
4. **How large should the fixture actually be?** → **The §4.2 shape**, sized to the largest that fits
   with a 3× disk margin after growing the volume. The honest position is that this is the parameter
   most likely to need a second pass: if the breach concurrency turns out to be insensitive to the
   fixture (i.e. the same for workspace A and workspace B), the fixture is not the binding variable
   and a bigger one would not have changed the answer; if it is very sensitive, a second run at 2×
   is worth the cost. The run itself tells us which, because both sizes are in it.
5. **Grow the EBS volume — is that acceptable as a precondition?** → **Yes, and it is overdue
   regardless.** The box is at 79% used against a `DiskFilling` alert that fires at 85%, so the
   fixture is the occasion rather than the cause. It costs about a dollar a month and cannot be
   undone (EBS grows, never shrinks), which is why it is a question and not an assumption.
6. **`standard` or `unlimited` credit mode for the window?** → **Whatever the box is in today, and
   record it.** Deliberately switching to `unlimited` for the window would measure a box the owner
   does not run. If the run aborts on credit exhaustion (§5.3 condition 8), *that is a finding about
   the instance type* and belongs in the sizing guidance — "a burstable instance cannot sustain this
   load regardless of its RAM" is exactly the kind of answer this ticket exists to produce.
7. **Should `ops/loadtest/` be a synced path?** → **Yes**, and note the consequence rather than
   avoiding it: `ops/` is already synced, so the box-side capture scripts arrive with every deploy and
   need no hand-copy — which is the failure mode HD-199 exists to end. The fixture generator being
   *present* on production is not the same as its being *permitted*: it refuses to run without an
   explicit confirmation value naming the window, and it refuses on a Flyway mismatch. Presence is not
   permission, and the guard is what says so.
8. **Is a single window enough?** → **Probably not, and plan for two.** The first window's most likely
   product is a list of things the harness got wrong. Budget a second window a week later and treat
   the first as producing both results *and* corrections. A plan that assumes one attempt is how a
   partial result gets published as a complete one.

---

## 12. The highest-risk assumption, stated plainly

**That a generated fixture is close enough to a real large tenant that the breach concurrency
transfers.**

Row counts are the easy half and the harness controls them. What actually decides a query's cost is
the *distribution* — how issues spread across statuses and projects, how history clusters in time,
how long the text is, how skewed the comment counts are, how many labels a typical issue carries. A
uniform synthetic fixture is systematically wrong in a direction that nothing in the run reveals: it
can make every query cheap (perfect cache behaviour, uniform selectivity, plans that never degrade)
or uniformly expensive, and either way the number that comes out is precise and misleading.

§4.2's skew rules reduce this and do not remove it. The mitigation that actually helps is comparative
rather than absolute: **compare `EXPLAIN` plan shapes between the fixture and production's real
data** for the main queries of each mix. Same plan shape means the fixture is exercising the same
code path, and the measured number then transfers as an *order of magnitude for this class of tenant*
— which is what sizing guidance needs — rather than as a promise about any particular install. The
published guidance must say so in those terms; a figure published without that qualification will be
quoted as a guarantee within a month.

Second on the list, and cheaper to guard: **that the box's CPU is CPU.** A `t3.small` under sustained
load is drawing on a credit bucket, and a run that does not record the credit specification and the
balance can report "the application serialises" about a machine that simply had its processor taken
away. §4.7 and §5.3 make that observable; it earns its place here because it is the one confound that
would make every other number in the report wrong in the same direction at once.

---

## 13. Architectural decisions (ADR)

One decision here is a genuine fork that a future contributor would ask "why?" about, and it is
hard to reverse in the sense that matters: once the project has loaded production once, it has set
the precedent for how it measures capacity.

| Decision | Chosen | Rejected | Trade-off |
|---|---|---|---|
| **Where capacity is measured** | Against production, in a declared window, with a completed snapshot, a rehearsed teardown, published abort conditions and a recorded configuration fingerprint | A temporary clone of the box; a staging environment; extrapolation from a local run | A clone measures a machine nobody uses — different volume, different page-cache state, different neighbours, no Cloudflare, no observability stack competing for the same 1909 MB — and would have produced a number with the same confident formatting and none of the validity. The price is that a real box, holding real (if few) users' data, is deliberately pushed until something breaks; the whole of §5 is what buys that down. **The rule that survives the ticket: the box measured must be the box run, and the numbers are void the moment its configuration moves.** |

Drafted as **ADR-0020**, `Status: Proposed`. Everything else here — k6 over the alternatives, SQL
generation over API generation, two capture tiers, teardown by tenancy — is feature mechanics: real
choices, argued above, none of them a fork a future contributor would need an ADR to understand.
