# Giving the planning surface a budget and a place in the bulkhead (HD-174)

**Status:** proposal / design review. **Date:** 2026-09-04. **Author:** systems-analyst.
**Related:** `docs/design/backlog-section-refresh-proposal.md` §8 (HD-96, which filed this and
recommended a shape), `docs/design/expensive-read-concurrency-proposal.md` + ADR-0030 (HD-182, which
shipped the occupancy bound hours before this was written and changed the question), HD-140 R6
(`ThrottleCoverageTest`, the seal this edits), HD-191 (the write budget, the third configurer),
HD-186 / ADR-0020 (the load harness whose victim class this contaminates), HD-151
(`docs/design/statement-timeout-proposal.md`, the per-statement bound this shows is not enough here).

**One sentence:** the largest single response this product produces answers with no budget of any
kind, and HD-182's arrival split that into two questions — *may you ask this often* and *how many of
these may be running* — which this settles as **yes to both**, on a new rate pot of its own and on
the existing shared occupancy share.

---

## 0. The two questions, answered first

| | question | answer | why, in one line |
|---|---|---|---|
| **Q1** | Does the planning surface earn a **rate** budget? | **Yes — its own pot, 240/min.** | It is unbudgeted today only because nobody decided; the reports pot would 429 an ordinary reorder session, so a shared pot is not available. |
| **Q2** | Does it earn a place in the **occupancy bulkhead**? | **Yes — and this is the more important half.** | One `GET …/backlog` holds **one connection across up to 32 statements** in a single read-only transaction. `statement_timeout` bounds each of them and nothing bounds their sum; a rate counter provably cannot bound it either. |

**If only one of the two ships, ship Q2.** The rate pot buys tidiness and a refusal a client can
read; the occupancy permit is what stops one planning read holding a pool connection for minutes
while the rest of the API waits out Hikari's 30 s `connectionTimeout`. HD-182's own §2 point 4 named
the planning view as a surface outside its bound; this closes the gap that document declared.

---

## 1. Problem & goal

`GET /api/workspaces/{ws}/projects/{p}/backlog` and its two section siblings under
`…/backlog/sections/…` carry **no per-principal budget and no occupancy permit**.
`ThrottleCoverageTest`'s sealed path set covers `…/projects/*/reports/**`, `…/search/insights`,
`…/storage/projects`, `…/search/**`, `…/filters/**` and `…/projects/*/issues/**`; none of them
matches `…/projects/*/backlog`. That is not a decision anybody made — it is what the surface happens
to have, and `PlanningThrottleParityTest` and `BacklogController`'s javadoc both say so explicitly
and both defer the question here.

The work is not small and it is not bounded by what comes back:

- `AgileProperties.isPlanningViewBounded` permits `section-max-issues × (max-open-sprints + 1)` up to
  **`MAX_PLANNING_VIEW_ROWS` = 20 000** assembled `IssueResponse`s in **one unpaged response** —
  6300 at the stock defaults, ~1.9 KB per shipped row on the accounting the reports budget uses, so
  **~12 MB of transient heap at the defaults and ~38 MB at the configurable maximum**, per request,
  on a box whose measured binding constraint is memory (HD-186, 512 MB heap).
- It is a `GET` the browser re-issues on navigation, and `Cache-Control: private` means no shared
  cache absorbs a repeat.
- `planningStats` is the **unconditional, cap-blind** term: it reads and groups a whole section
  whatever the filters say and whatever the cap is. (Do not restate this as "everything else is
  bounded by what it returns" — a `LIMIT` bounds output, never selection, and `findSectionIssues`
  orders the whole filtered set before its limit applies. That paraphrase has been wrong three times
  in this repository; the defensible line is *unconditional and cap-blind*, not *bounded vs
  unbounded*.)

**Goal.** The planning surface is bounded on both axes the product now has, by a decision recorded in
the code rather than in a ticket; a normal planning session provably does not 429; the guard is a
property of the surface, so a fourth planning read inherits it without anyone editing a test; and the
artefacts that describe the throttled surface — including the load harness's own victim class —
still tell the truth afterwards.

**Success looks like:** `PlanningThrottleParityTest` asserts a *derived* set of planning handlers all
resolve to a chain carrying both controls, named; a facilitator dragging cards for an hour never sees
a 429; and `hamstrack_expensive_read_in_flight` becomes the one number that answers "is the planning
surface holding pool connections?".

---

## 2. Q1 — the rate budget

### 2.1 It is earned, and not by response size

The argument that spared planning reads until now was that a report is O(project history) while a
planning response is bounded by `MAX_PLANNING_VIEW_ROWS`. "Bounded" is not "small", and the bound is
20 000 rows — the same number as `REPORTS_MAX_ROWS`, on a surface that is not throttled, from a
handler that is re-issued on every navigation. The budget is earned by `planningStats` being
unconditional and cap-blind, which is the property `…/storage/projects` was put on the reports pot
for and the property `GET …/issues` does **not** have (§7.3).

### 2.2 Its own pot, not the reports pot — and the number

The reports pot is 60/min. A planning session that fires up to two section refreshes per drag would
reach it inside three minutes of ordinary work, so a shared pot is not on the table; the ticket says
so and the traffic model below confirms it.

**What the UI actually does — read off the code, not estimated.**

| gesture | planning requests | source |
|---|---|---|
| Open the Backlog page / change any filter | **1** aggregate (`GET …/backlog`) | `BacklogPage.tsx:154` → `useBacklogView`, `sprints.tsx:272-276`. The page's other mount queries (`/sprints`, `/config`, `/project`) are **not** on this surface. |
| Drag or keyboard-move a card **within** one section | **1** section fetch | `rank.onSettled` → `view.refreshSections([from, to])`, deduplicated to one id (`sprints.tsx:332-335`) |
| Drag a card **across** sections | **2** section fetches, **sequential** (`for … await`) | same, `sprints.tsx:334` |
| Manual per-section refresh button | **1** section fetch | `BacklogPage.tsx:592, 667` |
| "Move every issue to →", create issue, any sprint mutation | **1** aggregate (the key lives under `projectIssuesKeyPrefix`, which `useSprintMutations.invalidate` clears) | `queryKeys.ts:123`, `sprints.tsx:377-381` |
| A failed section refresh | **+1** aggregate (`invalidateQueries` on the view key) | `sprints.tsx:322` — see §5.4, this is a hazard the refusal must not trigger |

So a **busy but legitimate minute**, derived rather than adjectival:

```
a fast facilitator sustains ~1 drag / 2 s          = 30 drags/min
mostly cross-section during grooming (x2 fetches)  = 60 section fetches/min
plus filter toggles + sprint mutations             ≈ 10 aggregates/min
                                                     ────────────────────
one very busy tab                                  ≈ 70 planning requests/min
the same person with a second tab open on a
second project (common in grooming)                ≈ 140
```

Nobody sustains 30 drags a minute for a whole minute; 70 is already the pessimistic reading of one
tab. **240/min is ~3.4× the busiest single tab and ~1.7× the two-tab pathological case**, which is
the same posture the search budget documents ("120 is ~10x ordinary SPA use" against a debounced
typist). It is deliberately generous: this is a budget on an *interactive drag-and-drop gesture*, and
a 429 mid-drag is a visible product defect, whereas a 429 on a report is a delay. The occupancy
permit (§3) is what protects the pool; this number only has to catch a client in a loop.

**Recommendation: `app.planning.requests-per-minute`, env `PLANNING_REQUESTS_PER_MINUTE`, default
240.** Range `@Min(1) @Max(10000)`, no "unlimited" (`0` fails startup), no per-family off switch —
under the master `app.rate-limit.enabled` like every other minute budget. Identical in `dc` and
`cloud`, no profile override. This is §8 of the HD-96 proposal's recommended shape, unchanged, now
with the arithmetic behind the number. Flagged as **Q-A** in §11 because it sets production capacity.

### 2.3 One pattern, and it is the surface not the endpoints

`/api/workspaces/*/projects/*/backlog/**` — one pattern covering the aggregate and every section
endpoint, so a fourth planning read added to `BacklogController` is budgeted the moment its
`@GetMapping` exists. A new `PlanningRateLimitConfig` (`@Order(ReportRateLimitConfig.ORDER + 2)`)
registers **one** `PrincipalThrottleInterceptor` carrying **both** controls, exactly as the reports
and search configurers do.

The order annotation is declared for determinism and is **not** load-bearing today, because no other
registered pattern overlaps this one — stated as a condition rather than as a fact about today's
paths: *if a future pattern ever overlaps the planning path, the two orders become load-bearing for
the same reason `ReportRateLimitConfig.ORDER` is, and whoever adds it owes that constant's argument a
re-read.*

---

## 3. Q2 — the occupancy bulkhead

### 3.1 It is earned by a hold a rate cannot see, and the numbers are specific

`BacklogService.view` and `BacklogService.section` are both `@Transactional(readOnly = true)` over
the whole method, so **one Hikari connection is held for every statement they issue**, plus the Java
assembly between them:

| | statements in one transaction |
|---|---|
| `GET …/backlog` (aggregate) | `4` (`resolveProject`, the constant documented in `CLAUDE.md`) `+ 1` (`openSprintsOf`) `+ 1` (`planningStats`) `+ (N+1)` row queries, one per open section `+ 5` batched loaders (field values, labels, versions, roll-ups, parents) = **`12 + N`**, i.e. **32** at `AGILE_MAX_OPEN_SPRINTS=20` |
| `GET …/backlog/sections/{sprintId}` | `4 + 1` (`requireSprint`) `+ 1 + 1 + 5` = **12** |
| `GET …/backlog/sections/backlog` | **11** |

`DB_STATEMENT_TIMEOUT_MS` (10 s) bounds **each** statement. Nothing bounds their sum. **The
worst-case connection hold for one planning aggregate is therefore ~320 seconds**, and for a section
fetch ~120 — against a default pool of 10 and a Hikari `connectionTimeout` of 30 s for everybody
else. This is the exact shape ADR-0030 exists for, and it is *worse* than the reports surface it was
built for, which the ticket suspected and the code confirms.

**And the rate counter provably cannot bound it.** HD-182's own argument applies here without
modification: a rate bound spends the same unit whether a request takes 8 ms or 8 s, so its
protection evaporates precisely as the instance slows down. At 240/min a single principal is entitled
to spend 240 × up-to-320 connection-seconds against a replica that supplies 600 a minute. An
occupancy bound *tightens* as the instance slows: at 3 permits and a 1 s planning response, one
principal's achievable rate is ~3/s; at a 3.9 s response (the measured P1 median) it is ~0.77/s, so
the entitlement becomes self-limiting under exactly the conditions where it mattered.

**A second benefit, the same arithmetic HD-182 ran for reports.** Concurrent planning materialisation
is bounded today by *Tomcat threads* (200): at the configurable maximum that is 200 × ~38 MB ≈ 7.6 GB
of transient heap against a 512 MB heap. Inside the bulkhead the ceiling is `max-in-flight × rows ×
~1.9 KB` = **6 × 38 MB ≈ 228 MB**, and ~114 MB for any one principal. Lower, not safe —
`app.agile.*` remains the only dial that bounds heap *per request*.

### 3.2 The shared share, not a share of its own — and what that costs

**Recommendation: the planning registration injects the existing `ExpensiveReadConcurrencyLimit`.**
Same primitive, same permits, same two refusals, no new dial, no new arithmetic. One edit —
registering the pattern — buys both controls, which is precisely why HD-182 refused to create a
second pattern list.

**The interaction the ticket asks about is real: planning and reports now compete for the same 6
permits, so a planning-heavy team can refuse a colleague's report, and vice versa.** Three things
make that acceptable rather than merely tolerated:

1. **Occupancy is arrival rate × duration, and planning's duration is short in the healthy case.** A
   busy planner at ~70 requests/min with a ~200 ms response occupies ~0.23 permits. Ten simultaneous
   busy planners occupy ~2.3 of the 6. The competition only becomes real when responses get slow —
   which is the state the bulkhead exists to convert from "everything on the replica fails after
   30 s" into "the expensive surface refuses in milliseconds while everything else is served".
2. **The alternative is worse for the same colleague.** Today an unbounded planning read holds a pool
   connection for up to 320 s and the report waits out `connectionTimeout` and fails. Refusing that
   report in milliseconds with `Retry-After: 1` is the trade ADR-0030 already made; leaving planning
   outside the share does not spare the report, it changes how the report fails.
3. **The per-principal ceiling still stops one planner being the whole cause** — 3 of 6, so a single
   planning session can never take the surface.

**Why not a second share.** A `PlanningConcurrencyLimit` with its own ceiling would re-open exactly
the arithmetic `PoolShareConsistency` enforces: hard rule 2 becomes a *sum* rule
(`expensiveRead.maxInFlight + planning.maxInFlight < poolSize`), and `ExpensiveReadShare`'s
derive-from-the-pool default becomes a **partition** problem. On the small pools this project
actively recommends that partition is degenerate — a pool of 4 derives a 60 % share of 2, which split
two ways is **1 permit per surface**, serialising the entire planning surface for the whole instance;
a pool of 6 gives 1 and 2. A literal that must sit below the pool is what crash-looped every small
self-host on the HD-182 upgrade, and a *pair* of literals that must jointly sit below it is that
hazard squared. Fairness between two read surfaces is a property nobody has measured a need for; the
pool floor is a property every self-host has. Recorded as **ADR-0031**.

### 3.3 What joining the bulkhead does not change

- **`PoolShareConsistency`, `ExpensiveReadShare`, `ExpensiveReadProperties`: no code change and no
  new number.** The share is now spent by more paths; the relation it enforces is unaltered.
- **The per-principal ceiling of 3 is not tightened by this.** `ExpensiveReadProperties` justifies 3
  as "one above the largest concurrent burst a correct client makes" — the search page's three
  parallel mount queries. The Backlog page's mount puts **one** request on this surface (the other
  mount queries are `/sprints`, `/config`, `/project`, none of them bound), and `refreshSections`
  iterates with `for … await`, so a drag never puts two planning requests in flight at once. **The
  planning surface does not raise the largest correct-client burst**, which is the one check that had
  to be made before adding a surface to a per-principal ceiling.
- **The async tripwire and the concurrency-coverage tripwire extend for free.**
  `ThrottleCoverageTest.expensiveReadSurface()` is a union whose second half reads the registrations
  of *every* `WebMvcConfigurer` in the context, so the three `BacklogController` handlers enter the
  scanned set the moment the new configurer carries an occupancy bound — with **no edit to either
  tripwire**. `everyOccupancyBoundedPatternHasAHandlerOnTheScannedSurface` then seals the new pattern
  against being dead configuration. This is HD-182's derivation doing exactly what it was built for,
  and it is worth stating because it is the reason this ticket is a small diff.

---

## 4. Scope

**In scope**

- One `PlanningRateLimiter` (`PerPrincipalMinuteBudget`), one `PlanningProperties`, one env var, one
  `RateLimitKind`.
- One `PlanningRateLimitConfig` registering one pattern with **both** controls.
- The seal work: `PlanningThrottleParityTest` rewritten as a derived property scan;
  `ThrottleCoverageTest.theThrottledPathSetIsSealed` gains the fourth configurer; the propagation
  checklist gains the sixth question.
- The frontend refusal handling, including the escalation hazard in §5.4.
- The full documentation set (§9), including the load harness's `browse` class (§8.2), which this
  change makes wrong.

**Out of scope, each with a reason rather than by omission**

- **`GET …/issues` (board + list) and `GET …/sprints`.** §7.3 — a weaker case, for a stated reason,
  and extending the pattern to them is an argument to be made on their own evidence.
- **Changing any existing budget or the occupancy ceilings.** 60, 120, 180, 3 and 6 stay. Whether 6
  should rise now that the busiest read surface joined it is **Q-B** in §11 — an open question for
  the owner, not a silent change.
- **A second occupancy share.** §3.2, ADR-0031.
- **Any change to `AgileProperties`.** 20 000 is somebody's deliberate ceiling; this bounds how many
  of them can be in flight, not how big one is.
- **A new alert rule.** §9.3 — the reports and search budget kinds carry none either, and the metric
  row will say that nothing watches it.

**Non-goals**

- This does not make the planning view fast, and it does not make a 20 000-row response a good idea.
  Under sustained overload some legitimate planning reads will be refused, in milliseconds, while the
  interactive API keeps its connections. That is the trade, stated once.

---

## 5. Behaviour & rules

### 5.1 Actors, permissions, tenancy

**Unchanged, and nothing here may change them.** Any member of the owning workspace may read the
planning surface; a missing workspace, a missing project, a project in another workspace and a
non-member are all **404**, never 403. Both controls are spent in `preHandle`, **before** the
controller resolves anything, and both are keyed on the **caller** — which is exactly why that is
safe: the refusal is byte-for-byte identical for a real workspace, a nonexistent one and somebody
else's, so it cannot answer an existence question. Never re-key either control on a workspace, a
project or a sprint.

### 5.2 Happy path

1. Request arrives on `/api/workspaces/*/projects/*/backlog/**`.
2. `PlanningRateLimiter.require(userId)` spends one unit of the caller's 240. Over budget → **429**,
   `Retry-After` = seconds until the window rolls, no `errorType`.
3. `ExpensiveReadConcurrencyLimit.acquire(userId)` takes one per-principal permit, then one surface
   permit, waiting up to `acquire-wait-ms` (1000) across the whole acquisition. Refusals: **429
   `TOO_MANY_IN_FLIGHT`** or **429 `EXPENSIVE_SURFACE_BUSY`**, both `Retry-After: 1`.
4. The handler runs. `afterCompletion` releases the permit on every terminal path.

**Rate first, permit last** — a request the rate budget will refuse must never have held a permit.
That ordering is `PrincipalThrottleInterceptor`'s and is inherited, not re-implemented.

### 5.3 Invariants

1. **Every planning read carries the same chain.** The HD-96 property survives verbatim and is
   strengthened: not "the same chain" but "the same chain, carrying the planning budget *and* an
   occupancy bound". A half-budgeted surface is worse than either whole answer, because the client
   refused on the cheap request retries with the expensive one.
2. **One permit per request, never one per interceptor.** Inherited; no planning path is behind two
   configurers today, and if one ever is, the request-attribute mechanism already handles it.
3. **The budget bounds nobody's *answer*.** Past the budget the response is 429; it is never a
   narrowed section, a smaller cap, or a truncated view. The honesty protocol
   (`sectionCap`/`truncated`/`totalAvailable`) is untouched.
4. **A capability never changes a status code.** A project with `board`/`releases`/`estimation` off
   still resolves the planning surface and still spends the same budget. Restated because this
   repository has shipped the opposite once.

### 5.4 The escalation hazard — the one new failure mode this change creates

`refreshSection`'s catch block calls `qc.invalidateQueries({ queryKey: key })` on any error
(`sprints.tsx:322`), so **a refused section refresh currently triggers a full aggregate refetch** —
i.e. a refusal on the cheap request provokes the expensive one, the exact asymmetry
`PlanningThrottleParityTest` exists to forbid, arriving through the error path instead of through the
registration.

**Rule: a 429 of any kind on a section refresh must not invalidate the aggregate.** Keep the stale
section, mark it stale, surface the refusal with a manual *Try again*. Any other error keeps today's
invalidate-and-refetch behaviour. This is not a nicety: without it, one saturated instant costs the
server one 32-statement aggregate per refused section refresh, per planner.

---

## 6. Edge cases & failure modes

- **429 mid-drag.** The rank write has already succeeded (the refusal is on the *refresh* that
  follows it), so the board is correct on the server and stale on screen. The optimistic patch stays
  applied, the section is marked stale, and the user sees a retry affordance — never a rollback,
  which would show them a move that did happen as if it had not.
- **429 on the aggregate at page load.** The page renders its error state with the refusal's
  sentence. Do **not** auto-retry `EXPENSIVE_SURFACE_BUSY` (retrying a busy shared resource amplifies
  it) and do **not** auto-retry the budget 429 (retrying it is the abuse it refuses). A single retry
  of `TOO_MANY_IN_FLIGHT` is allowed by `api.ts`'s existing rule and is correct here.
- **Two permits, one gesture.** A cross-section drag issues its two section fetches sequentially, so
  it holds one permit at a time. If a future change parallelises them, it moves the planning surface
  from a burst of 1 to a burst of 2 and the per-principal ceiling of 3 must be re-argued.
- **A COMPLETED sprint section still answers 200**, and still spends the budget. State must not
  change a status code, and a throttle must not become the thing that does.
- **`RATE_LIMIT_ENABLED=false`** removes the planning *rate* budget and deliberately leaves the
  *occupancy* permit in force — the direction `application.properties` already documents.
- **The test suite** runs with `app.rate-limit.enabled=false` in dozens of contexts, so the new pot
  is inert there, while the occupancy bound stays on. A leaked permit on a planning path therefore
  breaks tests loudly, which is the property HD-182 designed for.
- **Idempotency, optimistic locking, soft deletes, stranded issues, concurrency on the data:**
  untouched. Nothing is written and nothing is resolved before either control is spent.
- **Data model:** **none.** No table, no column, no migration. Stated explicitly because "budget" and
  "share" read like persisted state and must not be: the rate window and the permits are per-process
  memory, and §10 argues that is the correct scope for one of them and a known weakening for the
  other.

---

## 7. The seal — a property of the surface, not a list of paths

### 7.1 What is wrong with today's parity test

`PlanningThrottleParityTest` probes **three URI literals**. It is a good test and it defends the
right property, but a fourth `@GetMapping` on `BacklogController` is invisible to it — which is the
AC's explicit target ("a third planning endpoint inherits it without an edit").

### 7.2 The replacement, reusing HD-182's matching rather than re-deriving it

**The scanned set is a union of two halves, derived at runtime; the test contains no URI literal
other than the base pattern constant.**

- **Half A — the pattern half.** Parse `PlanningRateLimitConfig.PLANNING_PATH` with
  `PathPatternParser`, then walk `RequestMappingHandlerMapping.getHandlerMethods()`, build the
  concrete URI of each mapped pattern (the `concrete()` trick: every `{var}` → a UUID), and keep the
  handlers whose own mapped pattern the planning pattern matches. A fourth planning endpoint is in
  scope by existing.
- **Half B — the registration half.** Every pattern that a `PrincipalThrottleInterceptor` whose
  `budget()` is the `PlanningRateLimiter` is registered on, **read from the `InterceptorRegistry` of
  every `WebMvcConfigurer` in the context** — not by probing every mapped handler through
  `getHandler`. HD-182 rejected probing for this specific job because `getHandler` returns **no
  chain** when a mapping's `consumes`, `produces`, `params` or `headers` conditions are not
  satisfied, so a multipart or content-negotiated handler would silently answer "no chain" and drop
  out of the set. That reasoning is reused verbatim; it is not re-derived and must not be weakened.
  This half is what puts a planning read registered on a *second* pattern into scope automatically.

Then, for each handler in the union:

- `getHandler` is used **only to obtain the chain**, and the chain is asserted **non-null** with a
  message naming the fix ("a planning handler could not be probed — give the probe the `consumes` /
  `produces` / `params` its mapping requires; a handler that drops out of this scan is a handler this
  seal silently stops covering"). A drop is loud, not silent — which is the whole of HD-182's lesson
  applied at the one place `getHandler` is unavoidable.
- Assert **every handler's chain is equal to every other's** (the HD-96 sameness property).
- Assert the chain contains a `PrincipalThrottleInterceptor` that `appliesTo("GET")`, whose
  `budget()` is the `PlanningRateLimiter` **and** whose `concurrencyBound()` is non-null. Asking
  `appliesTo` as well as membership is HD-182's refinement: a method-conditioned binding in the chain
  is not coverage for a handler whose verb it skips.

**Two tripwires**, both of which this file's history says are needed:

- the scanned set has size **≥ 3**, so a moved path cannot leave the test comparing empty lists;
- **every pattern from half B matches at least one scanned handler**, so a registration whose pattern
  matches nothing (dead configuration wearing a bulkhead's clothes) fails here rather than passing
  green.

### 7.3 What the seal still cannot see, said plainly

Nothing structural can identify "a planning read mounted somewhere else". That is the insights
failure mode — a report that did not live under `/reports` — and it is the gap the propagation
checklist names and cannot close. The checklist therefore gains a **sixth question**:

> **AND THE SIXTH (HD-174): is it a PLANNING read** — does it assemble a project's sections, or run
> an unconditional, cap-blind aggregation over one of them? A budget is earned by the work a handler
> does, not by where it is mounted; register it in `PlanningRateLimitConfig`, which carries both
> controls on one interceptor. Note what is deliberately NOT on it and why, so the next reader does
> not "fix" the omission: `GET …/issues` and `GET …/sprints` are a **weaker** case — not because
> their work is bounded by what they return (no filtered, ordered, capped query's work is), but
> because neither runs an unconditional project-wide aggregation. Extending the pattern to them is an
> argument to be made on their own worst case.

`ThrottleCoverageTest.theThrottledPathSetIsSealed` gains a fourth assertion over
`PlanningRateLimitConfig`'s registered patterns —
`containsExactlyInAnyOrder("/api/workspaces/*/projects/*/backlog/**")` — with a failure message
saying it is **one pattern on purpose**: the planning surface as a category, deliberately not
`…/projects/*/**`, which would charge one pot for issues, versions, components and config that are
bounded elsewhere or on a different axis.

---

## 8. Surfaces to change

### 8.1 API

No new endpoint, no changed request or response shape. **Three refusal shapes on existing
endpoints**, described once as a property of the planning surface:

```json
{ "type": "about:blank", "title": "Too Many Requests", "status": 429,
  "detail": "Too many planning requests — try again shortly.", "errorType": null }
```

plus the two HD-182 bodies verbatim (`TOO_MANY_IN_FLIGHT`, `EXPENSIVE_SURFACE_BUSY`, both
`Retry-After: 1`). Clients discriminate on `errorType` presence, exactly as they do on the reports
and search surfaces; the budget 429 gains no `errorType` here for the reason HD-182 gave for not
adding one there.

`api-docs-sync` updates `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md`: the env-var table,
the "surfaces with throttles of their own" section, and the 429 note on all three backlog endpoints.

### 8.2 The load harness — this change makes it wrong in two ways

**This is the highest-value non-code deliverable and it is easy to miss.**

`ops/loadtest/k6/lib/classes.js` defines `CLASS.BROWSE` as "board, **backlog**, issue detail, …" and
`browse.js:114-123` fires `…/backlog/sections/backlog` and `…/backlog/sections/{sprintId}` inside it.
Two consequences:

1. **The threshold's stated rationale becomes false.** `README.md:702-704` says *"nothing in the
   product budgets ordinary browsing or writing per principal, so a `browse` 429 above 1% is a
   finding about what sits in front of the app"*. After this change part of the browse mix **is**
   budgeted and **is** occupancy-bounded, so a `browse` 429 could be the product working as
   designed. A harness whose failure rationale is false is worse than one with no rationale.
2. **Probe P1's victim class is contaminated.** P1's success criterion is *"the victim's `browse`
   class stays inside its target"* while the entitled principal is refused. If the victim class is
   itself partly on the bounded surface, a P1 pass no longer means what it says.

**Deliverable: split the planning reads out of `CLASS.BROWSE` into a new `CLASS.PLANNING`**, with its
own targets and its own `hs_refused_429{class:planning}` witness, and rewrite the browse rationale
sentence to describe a category ("the browse class is deliberately composed only of reads the product
does not budget") rather than a claim about the product as a whole. Arrival rate check: a browse VU
thinks 4–8 s and rolls into `backlogPage` 25 % of the time, so it produces ~5 planning requests/min —
two orders below 240, and it must stay that way, or the harness measures the limiter instead of the
app.

### 8.3 Frontend

`DESIGN.md`: **no visual change** — reuse the existing error banner and the section's existing
refresh/stale affordance.

| File | Change |
|---|---|
| `src/main/frontend/src/components/sprints.tsx` | `refreshSection`'s catch: on **429 of any kind**, set the section error and **skip** `invalidateQueries` (§5.4). Every other error keeps today's behaviour. |
| `src/main/frontend/src/api.ts` | 429 comments on the three backlog callers, naming the three refusals; the existing `TOO_MANY_IN_FLIGHT` single-retry rule applies unchanged, and neither of the other two is ever auto-retried. |
| `src/main/frontend/src/pages/BacklogPage.tsx` | No behavioural change beyond what the hook does; the aggregate's error state renders the refusal's `detail`. |
| `src/main/frontend/src/pages/BacklogPage.*.test.tsx` | A 429 on a section refresh leaves the section stale, shows the message and issues **no** aggregate request. |

### 8.4 Observability

- New `RateLimitKind.PLANNING_REQUESTS("planning_requests")` →
  `hamstrack_ratelimit_hit_total{kind="planning_requests"}`.
- **No new alert rule** — the reports and search budget kinds carry none, and the `docs/observability.md`
  row must say in so many words that nothing watches it, per the propagation checklist's rule that a
  metric row prescribing an action needs a rule that prompts it *or* must say there is none.
- **Two existing artefacts change meaning and must be edited in the same change:**
  `hamstrack_expensive_read_in_flight` and the provisioned
  `hamstrack_ratelimit_hit_total{kind="expensive_read_surface_full"}` rule now cover the planning
  surface too, so the rule's description and every doc row that enumerates what "the expensive-read
  surface" contains are claims this change falsifies.

---

## 9. Propagation — the artefacts the seal will print

Adding a fourth throttled pattern fails `theThrottledPathSetIsSealed`, whose message is the
checklist. These are the entries this change adds or invalidates (deliberately not introduced by a
count):

- `common/config/PlanningProperties.java` — the number, its derivation, and the traffic model in §2.2
  kept **once**, here.
- `application.properties` — the `app.planning.*` block **and** the `app.rate-limit.enabled`
  enumeration of what the master switch turns off (a list, not a count).
- `.env.prod.example` — a `PLANNING_REQUESTS_PER_MINUTE` block beside the reports/search ones, with
  the "comment it out, never blank" warning.
- `docs/self-hosting.md` — the rate-limit table row and the node-local prose below it.
- `docs/api-dc.md`, `docs/api-cloud.md`, `openapi.yaml`, `src/main/frontend/src/api.ts`.
- `docs/project-state.md` — the limiter list.
- `docs/observability.md` + `observability/grafana/provisioning/alerting/rules.yml` — §8.4.
- `ops/loadtest/k6/lib/classes.js`, `k6/browse.js`, `README.md` (the targets table and the browse
  rationale), `RESULTS-TEMPLATE.md` — §8.2.
- **Claims this change falsifies, each in the shape `CLAUDE.md` warns about:**
  - `BacklogController`'s javadoc: *"Throttle: none, deliberately"* and *"Today no planning read has a
    per-principal budget"* — the whole paragraph is replaced, not amended.
  - `PlanningThrottleParityTest`'s javadoc: *"which today is none"* and *"the answer today is
    recorded … `theThrottledPathSetIsSealed()`"*. Note this test's own javadoc **already predicted**
    this change and says it should keep passing — that prediction is correct and should be marked as
    fulfilled rather than deleted.
  - `BacklogService.section`'s javadoc: *"That is why a budget belongs to the whole planning surface
    rather than to this endpoint (HD-174)"* — the reasoning stays, the tense changes.
  - `ExpensiveReadProperties`' *"What this does NOT bound"* list: *"The planning view (up to 6300
    issues) … outside this bound"* is **no longer true** and is the single most important sentence to
    fix, because it is the one a future reader will trust.
  - `docs/design/expensive-read-concurrency-proposal.md` §2 point 4 — same sentence, same reason.
  - `docs/design/backlog-section-refresh-proposal.md` §8 — the follow-up it recommends has landed;
    add a one-line pointer to this document rather than rewriting the section.

---

## 10. DC / Cloud

**Identical code, identical defaults, no profile override** — the posture of every cap in this
product. `application-dc.properties` and `application-cloud.properties`: **no change, deliberately**.
`docker-compose.prod.yml`: **no change needed** (the app service carries `env_file: .env`).
`README`: **no change — it enumerates no variables.**

| property | env var | default | range |
|---|---|---|---|
| `app.planning.requests-per-minute` | `PLANNING_REQUESTS_PER_MINUTE` | `240` | `@Min(1) @Max(10000)`, no unlimited, blank aborts the boot |

**No new occupancy dial**, which is the point of §3.2: nothing here has to sit below
`DB_POOL_MAX_SIZE`, so nothing here can crash-loop a small self-host on upgrade, and
`ExpensiveReadShare`'s derive-from-the-pool default needs no second consumer. The one derive-shaped
requirement HD-182 established therefore applies vacuously — stated rather than skipped, because
"there is no new number" is the reason and not an oversight.

**Per process, and the two controls scale differently — the sentence to get the right way round.**
The rate budget is node-local, so N replicas allow one user N × 240; that is a **weakening**, the
same one every minute budget in this product carries, and it is documented as such. The occupancy
permit is per replica and so is the pool, so its guarantee holds on every replica without
coordination — **exactly correct, not a compromise**.

**Release class:** this is a *"Releases that change a resource default"* change under
`docs/release-checklist.md`. It changes behaviour on installs whose `.env` says nothing about it, and
the symptom on a small overloaded box is a 429 where yesterday there was a slow 200. All steps apply,
including the hand-written Release-body line.

---

## 11. Acceptance criteria — each names the artefact that checks it

**Rate budget**

1. The 241st planning request from one principal inside one minute answers **429** with
   `Retry-After` between 1 and 60, and the 240th answers 200 — `PlanningThrottleTest` (the
   `SearchThrottleTest` shape).
2. A different principal at the same instant is unaffected — `PlanningThrottleTest`.
3. `PLANNING_REQUESTS_PER_MINUTE=0` and a blank value both **fail startup**;
   `RATE_LIMIT_ENABLED=false` removes this budget — `PlanningPropertiesTest`.
4. A refused planning request returns a 429 body and **never** a narrowed section, a smaller
   `sectionCap` or a truncated view — `PlanningThrottleTest`.

**Occupancy**

5. Every planning handler's chain carries a `PrincipalThrottleInterceptor` whose
   `concurrencyBound()` is non-null and which `appliesTo("GET")` — `PlanningThrottleParityTest`.
6. The three planning handlers are inside `ThrottleCoverageTest.expensiveReadSurface()` and therefore
   inside `everyExpensiveReadHandlerIsAlsoConcurrencyBounded` and
   `noExpensiveReadHandlerIsAsynchronous`, **with no edit to either test** —
   `ThrottleCoverageTest`, unchanged except for the sealed-set assertion.
7. The new pattern is not dead configuration: it matches at least one scanned handler —
   `ThrottleCoverageTest.everyOccupancyBoundedPatternHasAHandlerOnTheScannedSurface`.
8. A planning request holds **one** permit while in flight and releases it on normal completion, on a
   handler exception and on a 429 from the rate budget — `PlanningConcurrencyTest` (the
   `ExpensiveReadConcurrencyTest` shape; the leak assertion is the important one).
9. `PoolShareConsistency` and `ExpensiveReadShare` are **unchanged** and their tests pass unedited —
   `PoolShareConsistencyTest`, verified by absence of a diff.

**Seal as a property**

10. `PlanningThrottleParityTest` contains **no URI literal** other than the pattern constant; the
    scanned set is derived from `getHandlerMethods()` and from the interceptor registrations of every
    `WebMvcConfigurer`; it fails if the set drops below 3 — reviewable by reading the test, and the
    tripwire fires on a moved path.
11. Every planning handler resolves to a chain **equal** to every other planning handler's — the
    HD-96 property, preserved — `PlanningThrottleParityTest`.
12. A handler that cannot be probed produces a **failure naming the fix**, never a silent drop —
    `PlanningThrottleParityTest`'s non-null assertion.
13. The sealed set is exactly four configurers and their documented patterns —
    `ThrottleCoverageTest.theThrottledPathSetIsSealed`, whose message carries the six-question
    checklist.

**Frontend**

14. A **429** on a section refresh leaves the section stale, shows the refusal's `detail`, offers a
    manual retry and issues **no** aggregate request — `BacklogPage.*.test.tsx`.
15. `EXPENSIVE_SURFACE_BUSY` and the budget 429 are **never** auto-retried;
    `TOO_MANY_IN_FLIGHT` is retried once — `api.ts` behaviour, covered by the existing test for that
    rule plus one planning case.

**Docs, harness and stale claims**

16. Every artefact in §9 is edited in the same change, including the five stale claims named there —
    reviewed against the checklist the seal prints; `api-docs-sync` owns the three API documents.
17. The planning reads are out of `CLASS.BROWSE`, the browse rationale sentence is rewritten as a
    category, and probe P1's victim class contains no bounded endpoint — `ops/loadtest/k6/lib/classes.js`,
    `browse.js`, `README.md`, `RESULTS-TEMPLATE.md`.
18. A dated `ops/loadtest/RESULTS-*.md` records one measurement this spec could not make: the maximum
    of `hamstrack_expensive_read_in_flight` and the planning p95 under the browse ladder, so the
    "planning occupies ~0.23 permits per busy planner" estimate in §3.2 is replaced by a number.

---

## 12. Open questions — flagged, with the answer I recommend

Each of these changes production capacity behaviour, so none is decided silently.

**Q-A. `PLANNING_REQUESTS_PER_MINUTE = 240`?** → **Take 240.** ~3.4× the busiest single tab, ~1.7×
the two-tab pathological case (§2.2). Lower it only with a measurement; the cost of it being too low
is a 429 in the middle of a drag, which reads as a product defect.

**Q-B. Should `EXPENSIVE_READ_MAX_IN_FLIGHT` rise from 6 now that the busiest read surface has joined
the share?** → **No, not in this change** — but this is the question most likely to be revisited in
production. Raising it requires raising `DB_POOL_MAX_SIZE` first (hard rule 2), which has a
`work_mem × nodes × backends` consequence, so it is a resource decision and not a tuning one. The
instrument already exists: watch `hamstrack_expensive_read_in_flight`'s maximum and the
`expensive_read_surface_full` counter for a week (AC-18). **If the owner prefers to be conservative,
the cheap alternative is to raise the pool rather than the share** — that widens the reservation for
the interactive API at the same time.

**Q-C. Should the planning surface get its own occupancy share instead of the shared one?** → **No**
(§3.2, ADR-0031). The partition is degenerate on the small pools this project recommends, and
fairness between two read surfaces is unmeasured while the pool floor is universal.

**Q-D. Should `GET …/issues` (board) and `GET …/sprints` join the planning pattern?** → **No, not
here.** Neither runs an unconditional project-wide aggregation, which is the property this budget is
earned by. What would change the answer is evidence about *their* worst case — and note that `GET
…/issues` is already partly covered on the write axis and would need its own reasoning about the
read half.

**Q-E. Is `EXPENSIVE_SURFACE_BUSY` acceptable during a drag-and-drop gesture?** → **Yes, with §5.4's
handling.** The alternative is not "no refusal" but "a 30 s hang and a failure", and the refusal is a
stale section with a retry button rather than a lost move (the write already committed). Flagged
because it is the one place a user meets this bound during an interactive gesture rather than while
waiting for a chart.

---

## 13. Highest-risk assumption, stated flatly

**That a planning response is short in the healthy case.** Every occupancy number in §3.2 rests on
it, and *I have no measurement* — the estimate of ~200 ms comes from the shape of the queries and
not from a run. If a default-configured 6300-row aggregate actually takes 2–3 s, a handful of
planners occupy the whole six-permit share on their own and `EXPENSIVE_SURFACE_BUSY` becomes a
routine outcome rather than a saturation signal. The symptom would be ugly and would look like a bug:
reports and searches refused during standup.

It is *reducible* rather than eliminable, and the mitigations are already in the plan: the acquire
wait (1000 ms) serialises a burst rather than refusing it; AC-18 replaces the estimate with a real
number before anyone reasons from it again; and the remedy if it is wrong is one `.env` line plus a
pool. **This is the assumption to test first, and the k6 browse ladder already exercises the exact
endpoints.**

Second, and much smaller: **that 240/min is above every legitimate client burst**, including clients
we have not written. Same shape as HD-182's second risk. If it is wrong the symptom is a 429 in the
middle of a drag; the mitigation is that the number is one environment variable and the metric
(`kind="planning_requests"`) says so immediately.

---

## 14. Architectural decisions

One, and it is the fork a future contributor will ask about — *why does the busiest read surface in
the product share six permits with reports and search instead of having its own?*

**ADR-0031 — every connection-holding read surface shares ONE occupancy share; per-surface shares are
rejected.** Chosen because the resource being reserved is a single pool with a floor
(`PoolShareConsistency` hard rule 2), and a second share turns the derive-from-the-pool default into
a partition problem that is degenerate on the small pools this project recommends — 1 permit per
surface on a pool of 4, which serialises a whole surface instance-wide, from a change whose entire
purpose is to stop one surface taking the instance down. Rejected alternatives: a second
`PerPrincipalInFlightLimit` with its own ceiling (the partition above, plus a sum rule in
`PoolShareConsistency` and a second number every operator must size); keeping planning out of the
bulkhead and relying on the rate pot alone (a rate spends the same unit whether a request takes 8 ms
or 8 s, and this handler holds one connection across up to 32 statements); a second physical pool
(already rejected by ADR-0030 for reasons unchanged here). Trade-off: planning, reports and search
can now refuse each other under contention, and a planning-heavy team can be the reason a colleague's
report is refused — accepted, because the refusal is milliseconds with `Retry-After: 1` where the
status quo is that colleague waiting out a 30 s connection timeout behind a planning read that may
legitimately hold its connection for minutes.

Drafted at `docs/adr/0031-one-occupancy-share-for-every-expensive-read-surface.md`,
`Status: Proposed`.
