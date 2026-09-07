# ADR-0031: One occupancy share for every expensive read; there is no per-surface share

Record date: 2026-09-04
Status: Accepted (implemented in HD-174)
Source: HD-174 (the planning surface has neither a rate budget nor a place in the bulkhead, and nobody
decided that); `docs/design/planning-surface-budget-proposal.md`; ADR-0030 and
`docs/design/expensive-read-concurrency-proposal.md` (HD-182 — the primitive reused here,
and its §2 item 4, where the planning surface is named outright as staying outside the bound);
HD-96 `docs/design/backlog-section-refresh-proposal.md` §8 (the predecessor ticket and the recommended
shape); HD-151 (`DB_STATEMENT_TIMEOUT_MS` — a bound on **one** statement); code:
`BacklogService.view` / `BacklogService.section`, `AgileProperties.MAX_PLANNING_VIEW_ROWS`,
`ExpensiveReadShare`, `PoolShareConsistency`

## Context

`GET …/projects/{p}/backlog` and its two per-section endpoints are bounded by nothing. Meanwhile:

- both service operations are `@Transactional(readOnly = true)` in their entirety, so **one pool
  connection is held for every statement they issue**: the aggregate is `12 + N`, where `N` is the
  number of open sprints (at `AGILE_MAX_OPEN_SPRINTS=20` that is **32** statements), the section 11–12;
- `DB_STATEMENT_TIMEOUT_MS` (10 s) bounds **each** of them and does not bound their sum, so the worst
  connection hold by a single planning aggregate is on the order of **320 seconds**, against a default
  pool of 10 and a `connectionTimeout` of 30 s for everyone else;
- the response is not paginated and is bounded by `MAX_PLANNING_VIEW_ROWS` = 20 000 assembled
  `IssueResponse`s (6300 on defaults) — at ~1.9 KB per row that is ~12 MB on defaults and ~38 MB at
  the maximum the operator is entitled to configure.

HD-182 added a second axis of bounding to the product — occupancy (`ExpensiveReadConcurrencyLimit`: 3
permits per principal and 6 across the whole expensive surface, a computed share of the single Hikari
pool). Its own document, in the list of "what this does NOT bound", names the planning surface outright
as staying outside. So HD-174's question is not "is a budget needed", but "on which of the two axes and
out of whose permits".

The key fork this ADR fixes: **does the planning surface take permits from the already existing shared
share, or get its own?** Its own share looks fairer — it protects reports from planners and vice
versa — and that is exactly why the decision needs recording: it is non-obvious and expensive to
reverse, because it touches the arithmetic that `PoolShareConsistency` checks at startup.

## Decision

**Every expensive-read surface that holds a connection takes permits from ONE shared occupancy
share.** The planning registration (`PlanningRateLimitConfig`, one pattern
`/api/workspaces/*/projects/*/backlog/**`) installs the existing `ExpensiveReadConcurrencyLimit` —
the same primitive, the same permits, the same two refusal shapes. There is no new configuration knob,
and the arithmetic of `ExpensiveReadProperties` / `ExpensiveReadShare` / `PoolShareConsistency` does
not change by a single line.

The **rate** budget, meanwhile, is the planning surface's own (`app.planning.requests-per-minute`,
240) — rate and occupancy protect different resources, so the answers to the ticket's two questions
are not obliged to coincide: a reports bucket of 60/min would have refused an ordinary card-dragging
session, whereas a permit from the shared share would not.

The property that stays true under any change of numbers: *through any connection-holding surface, no
principal occupies more than `max-in-flight-per-principal` of the replica's connections, and all of
them together no more than `max-in-flight`, so the difference always remains for the rest of the API.*
Adding a surface to the shared share preserves that property literally; adding a second share would
replace the right-hand side with a sum.

### Memory is the second half of the same decision, and it is stronger than the connections half

Everything above is phrased about **connections**, and the first edition of this ADR confined itself
to them. That undersold its own decision: on the **heap** the argument is stricter, and it is recorded
here because an ADR that proved only half of its thesis invites being reopened on the unproven half.

The reference figure is **512 MB** of heap: the image starts the JVM with `-XX:MaxRAMPercentage=50`,
`.env.prod.example` supplies `APP_MEMORY_LIMIT=1g`, and `StartupMemoryLogger` prints the resulting
number at every start. One assembled response row costs ~1.9 KB of transient heap. Hence:

- `app.reports.max-rows` already defaults to 20 000 rows ≈ **38 MB per request**, so the **worst
  case before this change is 6 × 38 MB ≈ 228 MB** on the shared share;
- a planning response costs ~12 MB on default `AGILE_*` (6300 rows) and the same ~38 MB at the maximum
  the operator is entitled to configure (`MAX_PLANNING_VIEW_ROWS` = 20 000 — deliberately the same number).

The key consequence: by entering the shared share, planning became **a neighbour on the already
existing 228 MB, not an addend to them**. The heap maximum across the whole surface remains
`max-in-flight × (the largest row ceiling among the surfaces) × ~1.9 KB` — a quantity that adding a
surface does not increase.

And **what exactly this replaced** is the argument. Before HD-174 planning assemblies were bounded only
by Tomcat threads: 200 × 12 MB ≈ **2.4 GB** on defaults and ~7.6 GB at the maximum of the settings,
against 512 MB of heap. That is not a risk but a guaranteed OOM given enough simultaneous readers, and
no rate budget removes it (it spends one unit for 8 ms and for 8 s alike). So **entering the shared
share is a decision correct on memory, not only on connections**; alternative 2 ("leave it outside,
bound it by rate only") is rejected on both axes, not on one.

## Alternatives

1. **Its own occupancy share for the planning surface (`PlanningConcurrencyLimit` with its own ceiling).**
   Rejected for a reason that concerns the resource, not the amount of work. `PoolShareConsistency`'s
   hard rule 2 requires the share to be strictly below the pool; with two shares that becomes a rule
   about the **sum**, and deriving the default value from the pool (`ExpensiveReadShare`) becomes a
   **partitioning** problem. On the small pools this project itself recommends, the partitioning
   degenerates: a pool of 4 gives a share of 2, halved — **1 permit per surface**, that is, the
   instance's entire planning surface is served strictly one request at a time. A literal obliged to be
   below the pool has already once put every small self-host into a crash-loop on upgrade; a **pair**
   of literals obliged jointly to be below the pool is the same trap squared. Fairness between two read
   surfaces has been measured by nobody, whereas the pool's lower bound is present at every installation.
2. **Leave the planning surface outside the bulkhead and bound it by rate only.** Rejected by exactly
   the argument for which HD-182 exists at all: a rate budget debits the same one unit whether the
   request took 8 ms or 8 s, so its protection disappears exactly as the instance slows down. Here
   that is not an abstraction — the handler holds one connection across up to 32 statements, and
   `statement_timeout`, which bounds one statement, does not bound their sum. That is precisely the
   bound rate does not give even in principle.
3. **A second physical pool for the planning surface.** Already rejected by ADR-0030 for reasons that
   have not changed here: one transaction manager sets `SET LOCAL statement_timeout`, the metrics, the
   test pool cap and the `work_mem × nodes × backends` arithmetic all assume one pool per replica, and
   the question "which pool did this run on" turns into a list somebody maintains.
4. **Lower `AGILE_SECTION_MAX_ISSUES` / `AGILE_MAX_OPEN_SPRINTS` instead of bounding.** Those are other
   knobs with meaningful values: they bound how large ONE response is, and say nothing about how many
   such responses may run at once. Lowering them would narrow the product without giving the protected
   property.
5. **A shared rate bucket with reports (60/min) instead of its own.** Rejected by the arithmetic of
   client behaviour: one card drag between sections is two section-refresh requests, and an ordinary
   grooming session hits 60/min after three minutes of work. A refusal in the middle of a drag-and-drop
   reads as a product defect, not as protection.

## Consequences

- **Planning, reports and search now refuse one another under saturation.** A team busy grooming can
  become the cause of a refusal for a colleague who opened a report, and vice versa. This is accepted,
  not passed over in silence: the refusal is a 429 in milliseconds with `Retry-After: 1`, whereas the
  status quo for that same colleague is a 30 s `connectionTimeout` wait behind a planning read that
  legitimately holds a connection for minutes. The per-principal ceiling (3 of 6) prevents one planner
  from becoming the whole cause.
- **The range of simultaneous materialisation falls, it does not grow.** Previously the number of
  simultaneous planning assemblies was bounded by Tomcat threads (200): at the maximum of the settings
  that is ~7.6 GB of transient heap against 512 MB. Inside the bulkhead the ceiling is
  `max-in-flight × rows × ~1.9 KB` ≈ 228 MB and ~114 MB per principal. Lower does not mean safe: the
  size of ONE response is still bounded only by `app.agile.*`.
- **There are no new occupancy configuration knobs, so there is no new way to fail to start.** Not one
  number in this change is obliged to be below `DB_POOL_MAX_SIZE`, which means a small self-host cannot
  go into a crash-loop on upgrade — a requirement HD-182 established at the cost of one review round is
  satisfied here by there being nothing to satisfy.
- **Both of HD-182's tripwires extend without edits.** `ThrottleCoverageTest.expensiveReadSurface()` is
  a union whose second half reads the registrations of **every** `WebMvcConfigurer` in the context, so
  the three planning handlers fall into the scope of
  `everyExpensiveReadHandlerIsAlsoConcurrencyBounded` and `noExpensiveReadHandlerIsAsynchronous` by
  themselves. Only the sealed set of paths is edited.
- **The claim in `ExpensiveReadProperties` has become false** — "the planning surface (up to 6300
  issues) … is outside this bound". It is a membership claim that the new path refutes while containing
  not one word by which a grep would find it; it is fixed in the same change together with the copy in
  `docs/design/expensive-read-concurrency-proposal.md` §2 item 4.
- **The load rig's `browse` class has stopped being "traffic bounded by nothing".** It includes
  planning reads and serves as the victim class in probe P1, whose success criterion is "the victim
  stays within its target values". Part of the victim is now on a bounded surface, so planning reads are
  moved out into their own class and the wording of the threshold is rewritten; otherwise the rig
  documents a rationale the product has made false.
- **The decision sets the rule for the next expensive surface.** It takes permits from the same share
  rather than setting up its own; the question "is the share enough" is settled by one number
  (`EXPENSIVE_READ_MAX_IN_FLIGHT`) and is raised only together with the pool. The converse — setting up
  a second share — now requires refuting the partitioning arithmetic above, not merely adding a
  `@Component`.
- **A residual risk, inherited rather than introduced: the sweeper returns the PERMIT, not the response.**
  `PerPrincipalInFlightLimit.sweepStalePermits` forcibly releases a permit by age
  (`statement_timeout + 60 s`), but the assembled `BacklogViewResponse` stays on the Tomcat thread
  meanwhile, while a slow client finishes reading it — and the same principal's next request takes a
  fresh permit. That is, the occupancy bound is not a heap bound: **on the order of 42** held ~12 MB
  responses suffice to fill 512 MB. It takes many sockets and patience, and before HD-174 the same was
  true for reports — so this is not a regression of the change. But the wording "an over-issued permit
  costs a Tomcat thread and some heap, but never a CONNECTION" was written when the largest object on
  the surface was a report; now "some heap" is up to 38 MB, and that is recorded where that phrase
  lives rather than left to be discovered.
