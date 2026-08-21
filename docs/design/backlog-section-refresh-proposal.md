# Backlog section refresh — one section, one honesty protocol (HD-96)

Status: **proposed**, not built. Branch `feat/release-0.16.0`.
Author: systems-analyst. Written *before* implementation; nothing in here has shipped.

---

## 1. Problem & goal

The planning view (`GET …/projects/{p}/backlog`) renders each section up to
`app.agile.section-max-issues` — 300 by default — and tells the client exactly what it
withheld: `sectionCap` on the response, `truncated` + `totalAvailable` per section, and
`SectionStats` computed over the **whole** section rather than over the returned page
(`BacklogService.java:102-160`, `BacklogViewResponse.java:11-49`). When the SPA refreshes a
**single** section it does not re-call that aggregate; it calls the ordinary issue list
(`GET …/issues?sprintId=…` / `?noSprint=true`) asking for `size = sectionCap`
(`components/sprints.tsx:321-331`), and `Paging.of` silently clamps `size` to
`Paging.MAX_SIZE = 100` and answers `200` (`Paging.java:13-22`). A section holding 101–300
issues therefore loads fully on first render and comes back cut to 100 after any per-section
refresh — a rank move, a sprint assignment, or the manual refresh control.

**The defect is not "the cap is too low". It is that two surfaces answer the same question
under two different honesty protocols, and one of them is not honest.** A careful client asked
a legitimate question and got a narrowed answer with no signal that anything had been withheld.
The client is written correctly and still gets a wrong result — its comment ("the section cap is
the SERVER's number and travels on the view … never a copy of the default baked in here",
`components/sprints.tsx:317-320`) is exactly right and does not save it.

**It also leaks out of the read and into a write.** `moveAllTo` hands the bulk-move mutation
the **rendered** list (`pages/BacklogPage.tsx:366-393`). After one refresh of a 250-issue
sprint, that list is 100 rows, so "Move every issue to →" moves 100 issues and reports
*"Moved the 100 loaded issues; the rest of the section was not on screen"* — a truthful-sounding
message about a truncation the server never applied. A silent read clamp becomes a silently
partial write.

**Goal.** A section obtained by refresh carries the same `sectionCap` / `truncated` /
`totalAvailable` / `stats` guarantees as a section obtained from the aggregate, holds the same
number of rows, and **has no number of its own**. When an operator retunes
`AGILE_SECTION_MAX_ISSUES` in either direction, both surfaces move together with no number
duplicated in the client and no redeploy of the SPA.

---

## 2. Scope

### In scope — slice A (the fix)

1. Two new read endpoints under the existing planning path, returning one section each, with
   **no `page` and no `size` parameter at all**.
2. One response DTO whose field names match the aggregate's section records exactly.
3. `BacklogService` gains a single-section path reusing `IssueRepository.findSectionIssues`
   and `IssueRepository.planningStats` (`IssueRepository.java:489-572`).
4. SPA `refreshSection` rewired onto it; every `size` computation and the client-side stats
   reconstruction deleted.
5. `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md`.

### In scope — slice B (separable, own veto point): stop `Paging` clamping silently

6. `Paging.of` **refuses** an out-of-range `size` with a `400` naming the maximum, instead of
   narrowing it. Coordinated with HD-163 (out-of-range `page`), which needs the same posture.
7. `SearchService.java:95-96,103` stops re-implementing the clamp by hand and stops computing
   its offset in `int` arithmetic.

Slice B is argued in §7 and is the item to veto if only one change is wanted. Slice A does not
depend on it; slice A does remove the only in-repo caller that over-asks.

### Out of scope / non-goals

- Changing `app.agile.section-max-issues`, `Paging.MAX_SIZE`, `Paging.DEFAULT_SIZE`, or any
  default. The fix introduces **zero** new configuration.
- Raising the page-size ceiling of `GET …/issues` for anybody. See §4.2.
- Client-side pagination of a section. See §4.3.
- Cursor/keyset pagination of a section, or making a section infinitely scrollable. A section is
  a capped list by design; the escape hatch above the cap is Search, and that stays.
- A new throttle pot. §8 states the budget deliberately and explains why the answer is
  "the same as the aggregate's, whatever that is" plus a filed follow-up.
- Any schema change. There is none.

---

## 3. Actors & permissions

| Who | May call | Resolution |
|---|---|---|
| Any member of the workspace that owns the project | Both section endpoints | `WorkspaceAccessService.resolveProject(actor, workspaceId, projectId)` (`WorkspaceAccessService.java:112-117`) |

Identical to the aggregate (`BacklogService.java:84`) and to `GET /sprints/{id}`
(`SprintService.java:173-176`).

- **No `permissions().require(...)`.** Reads inside a resolved project are unrestricted; the
  sprint *lifecycle* is what needs `SPRINT_MANAGE`. Adding a permission check here would make a
  refresh fail where the render that produced it succeeded.
- **Tenancy.** A missing workspace, a missing project, a project in another workspace and a
  non-member are all **404**, never 403.
- **The sprint is resolved through the project**, via
  `SprintService.requireSprint(project, sprintId)` →
  `sprintRepository.findByIdAndProject` (`SprintService.java:944-947`), which throws
  `SprintNotFoundException` (404). A bare `findById` here would be the nested-path
  re-verification bug: a sprint id from a sibling project in the same workspace would answer.
  `requireSprint` is package-private and the new code lives in `com.hamstrack.issue.service`,
  so it is reachable without widening anything.
- **Delivery capabilities do not gate this.** `board` / `releases` / `estimation` narrow what
  the UI offers and never what the server resolves; the aggregate checks none of them and
  neither does this. A project with `board` off must still answer.

---

## 4. Behaviour & rules

### 4.1 The decision: a dedicated single-section endpoint (ticket option 1, the "dedicated endpoint" variant)

```
GET /api/workspaces/{workspaceId}/projects/{projectId}/backlog/sections/backlog
GET /api/workspaces/{workspaceId}/projects/{projectId}/backlog/sections/{sprintId}
```

Two mappings, one literal and one `UUID`-typed, so a malformed id is a `400` at binding like
everywhere else and the literal wins by `PathPattern` specificity. `sprintId` is a UUID and can
never collide with the literal `backlog`.

Filters: exactly the aggregate's set — `statusId`, `assigneeId`, `priorityId`, `componentId`,
repeatable `labelId`, `labelMatch`, `fixVersionId` — plus `includeDone` on the **backlog**
mapping only, because `includeDone` applies to the backlog section only in the aggregate too
(`BacklogController.java:48-52`). **No `page`. No `size`.**

**Why this shape, in one line: deleting the parameter is the mechanism.** A section fetch that
takes no size has no number for the client to hold, no number for the client to echo back, and
no clamp to apply. The existing client comment stops being a discipline the next author has to
maintain and becomes true by construction. Every other option keeps a `size` on the wire and
therefore keeps correctness dependent on the echo being right forever.

This is also what the aggregate's own DTO said to do: *"The SPA must treat sections as
independently refreshable from day one — if the payload ever gets too large the fix is lazy
per-section fetches behind this same DTO"* (`BacklogViewResponse.java:14-16`). HD-23 built the
per-section refresh; what was missing was the endpoint behind it.

### 4.2 Argument against option 1's other variant — a per-endpoint max in `Paging` for `GET …/issues`

Rejected, and it is the closest call in the ticket.

- **The handler cannot know it is "being used as a section fetch".** `GET …/issues` serves the
  paged backlog, any script, and any integration. A per-endpoint maximum applies to all of them,
  so it converts an operator knob for the *planning view* into the page-size ceiling of the
  *whole issue API*. `app.agile.section-max-issues` validates up to 2000
  (`AgileProperties.java:38`), so `GET …/issues?size=2000` — a `count(*)` over the same
  predicate plus 2000 assembled `IssueResponse`s — becomes legal for every caller on an install
  that merely wanted a bigger planning view. That is a strictly wider change than the defect
  requires, and it is the one the ticket itself flags.
- **It couples two unrelated knobs.** An operator retuning the planning view would silently
  resize an endpoint that has nothing to do with planning. The ticket's "no number duplicated"
  criterion would be met by sharing a number that does not belong to both.
- **It does not fix the honesty half at all.** The refreshed section would still get its
  `truncated` from a client-side comparison, would still adopt the sprint's *unfiltered*
  counters only when no filter is active, and would still freeze its stats at the last server
  value when truncated (`components/sprints.tsx:384-404`). Two surfaces, two protocols, still.
- **The two queries are not the same query.** `findByProjectFilteredPaged` does not fetch-join
  `i.sprint` (`IssueRepository.java:318-337`) while `findSectionIssues` does
  (`IssueRepository.java:489-509`), and `Issue.sprint` is `LAZY` (`Issue.java:87-89`) while
  `IssueResponse` renders it (`IssueResponse.java:46,101`). Serving a section through the
  general list also duplicates the section's canonical ordering: `ORDER BY i.position ASC,
  i.createdAt DESC` is hard-coded in the repository for a section and re-declared as a
  controller-side `Sort` for the list (`IssueController.java:154-155`). Two copies of an
  ordering that must be identical is the next bug of this exact family.

### 4.3 Argument against option 2 — the client walks pages of 100

Rejected. It needs no API change and deserves a real answer, so here is the cost.

- **It makes the client responsible for a server-side invariant.** The sentence that makes
  today's client correct — the cap is the server's number and travels on the view — is the
  sentence option 2 contradicts: it replaces *trust the server's number* with *loop until you
  have collected the server's number*, which is the same trust plus a loop.
- **It is not atomic, and the non-atomicity lands on a mutating order.** Three offset pages over
  `issues.position` while other people are re-ranking at a planning meeting can duplicate a row,
  skip a row, or both. Today's single request cannot. So option 2 fixes "rows disappear" by
  introducing "rows appear twice" — and the duplicate is worse, because the rank model places an
  issue by naming its neighbours (`afterIssueId` / `beforeIssueId`), so a duplicated or skipped
  row corrupts the anchors the user then drops between.
- **It multiplies the hottest interaction in the app.** Every drag refreshes up to two sections
  (`pages/BacklogPage.tsx:278,379`). At the default cap that is up to 3 requests per section
  instead of 1 — up to 6 per drag; at the configured maximum, up to 40. And it does it on an
  unthrottled surface (§8), which promotes "should this be throttled?" from a follow-up to a
  blocker for this ticket.
- **It cannot fix the stats.** `page.totalElements` is the only whole-section number the list
  endpoint returns. However many pages the client walks, a filtered truncated section keeps
  showing last-known header numbers.
- **It leaves the defect live for every other client.** The API still silently clamps; the SPA
  merely stops being the one that notices.

### 4.4 Argument against option 3 — lower `AGILE_SECTION_MAX_ISSUES` to 100

Rejected.

- **It fixes the symptom by shrinking the feature for everyone.** A 250-issue backlog is
  ordinary. The planning view would truncate at 100 on first render — a product regression
  shipped as a bug fix, on the release-0.16.0 branch, with no user-facing story explaining it.
- **It makes the knob's documented range mostly a lie.** `@Max(2000)` (`AgileProperties.java:38`)
  and the "1…2000" range quoted to operators would remain, while every value above 100
  re-creates the defect. An operator knob whose range is mostly broken is worse than no knob.
- **It discards a validated bound in favour of an undocumented coupling.**
  `AgileProperties.isPlanningViewBounded` already asserts at startup that
  `section-max-issues × (max-open-sprints-per-project + 1) ≤ MAX_PLANNING_VIEW_ROWS`
  (`AgileProperties.java:75,88-93`) — a real bound with a message naming both knobs. Option 3
  replaces it with an unstated dependency on a constant in `common.dto`.
- **And it does not remove the silent clamp.** At `section-max-issues = 100` the two numbers
  coincide, so nothing refuses; the moment anyone raises it the failure is silent again. That is
  a coincidence, not an invariant, and the ticket asks for a behaviour that holds under retuning.

### 4.5 Happy path

1. Caller `GET`s a section with the same filters the aggregate was rendered with.
2. `resolveProject` (404 on any tenancy failure). For the sprint mapping, `requireSprint`
   (404 on unknown/foreign).
3. `cap = agileProperties.sectionMaxIssues()`.
4. One `planningStats` call scoped to this section — whole-section, filter-aware, cap-blind
   (`IssueRepository.java:541-572`).
5. One `findSectionIssues` with `PageRequest.of(0, cap + 1)` — the same one-query truncation
   probe the aggregate uses (`BacklogService.java:103-105`).
6. `truncated = rows.size() > cap`; trim to `cap`.
7. `toResponsesBatched` once for the trimmed rows — constant query count regardless of section
   size (`IssueService.java:1141-1160`).
8. Respond with the section plus `sectionCap` and `bulkMoveCap`.

Query budget: `2 (tenancy) + 1 (stats) + 1 (rows) + the constant assembly block`. Strictly less
than one `GET …/backlog`, which pays the same constants plus one row query **per open section**.

### 4.6 Invariants

- **The section endpoint and the aggregate return the same section for the same inputs.** Same
  filters, same cap, same ordering, same stats source, same `truncated` predicate. This is the
  property the whole ticket exists to establish; §10 makes it a test.
- **`truncated` means one thing everywhere: "this section holds more matching issues than
  `sectionCap`."** Today the refresh path computes it as "more exist than the page I received"
  (`components/sprints.tsx:391`) — the flag survives the round trip and changes meaning, which is
  why nothing errored.
- **`stats` are always the server's whole-section numbers**, filtered or not, truncated or not.
  The client stops deriving them.
- **`sectionCap` and `bulkMoveCap` ride on the section response too.** They are independent
  operator knobs with deliberately different defaults (`BacklogViewResponse.java:19-26`), and
  `moveAllTo` reads `bulkMoveCap` off the cached view (`pages/BacklogPage.tsx:376`). A view whose
  sections have all been refreshed must still carry both, and must adopt a newer value if the
  operator retuned between the two calls.
- **The client never sends a page size to any planning endpoint.** Enforceable by grep and
  asserted in §10.

### 4.7 Preventing filter drift between the two surfaces

If the section endpoint's filter set ever diverges from the aggregate's, a refresh answers a
different question than the render did — this defect's exact shape, one layer up.

**Recommended:** extract a `BacklogFilters` record bound with `@ModelAttribute` and use it for
`BacklogController.view` and both section mappings. Wire-compatible (parameter names unchanged),
and it makes drift impossible rather than detectable. Keep `labelMatch` as a `String` in the
record and parse it with `LabelMatch.parse` in the service — Spring's `String`→enum conversion is
case-sensitive and the wire contract is lowercase (`BacklogController.java:65-67`).

**Fallback if a minimal diff is preferred:** leave the signatures alone and add a reflection test
asserting the three handler methods declare the same `@RequestParam` names, minus `includeDone`
on the sprint mapping. Detection instead of prevention; acceptable, second best.

---

## 5. Edge cases & failure modes

| Case | Behaviour |
|---|---|
| Section holds exactly `cap` | `truncated: false`, `totalAvailable == cap`. The `cap + 1` fetch is what distinguishes this from `cap + 1`. |
| Section holds `cap + 1` or more | `truncated: true`, `totalAvailable` from the grouped query (never from the returned page). |
| Empty section | `issues: []`, `stats: SectionStats.EMPTY`, `truncated: false`, `totalAvailable: 0`. Mirrors `BacklogService.java:133-135`. |
| Sprint deleted between render and refresh | `404`. The SPA must treat a 404 on a section refresh as *"this section is gone"*: invalidate the aggregate query silently and render no error banner. Today the catch shows the error text **and** invalidates (`components/sprints.tsx:357-360`) — the invalidate is the right recovery, the banner is wrong for this case. |
| Sprint **completed** between render and refresh | **Answer it, 200.** The endpoint resolves a sprint by id, not by openness, exactly like `GET /sprints/{id}`. A completed sprint drops out of the *aggregate* (it lists open sprints only), so the client's next full refetch removes the section — but a refresh in flight must not turn into a 404 or a 409. Refusing here would be state changing a status code, which is the same mistake as a capability changing one. |
| Sprint id from a sibling project in the same workspace | `404` via `findByIdAndProject`. Explicit test — this is the classic nested-path leak. |
| Malformed UUID in the path | `400` at binding, before any handler runs. |
| More distinct `labelId` values than `app.classification.max-labels-per-issue` | `400`, same as the aggregate (`IssueService.LabelFilter.of`). |
| Unknown `labelMatch` | `400` via `LabelMatch.parse` — never a silent fall-through to the broader `any`. |
| `includeDone` sent to the sprint mapping | Unknown query parameter, ignored by Spring — as `page`/`size` already are on the aggregate. Not worth a refusal; it is not a narrowing of anything the caller asked for. |
| Archived project | Reads answer. The aggregate does not check archived state and neither does this. |
| Concurrency / optimistic locking | None applies: pure read, no `@Version`, no locking, no idempotency key. Two concurrent refreshes of the same section are independent. |
| Rank moved while the refresh is in flight | The response is a consistent snapshot of one read transaction. This is strictly better than today's sprint-header + rows pair, which is two concurrent responses (`Promise.all`, `components/sprints.tsx:346-349`) that can describe two different instants. |
| Operator retunes `AGILE_SECTION_MAX_ISSUES` between the aggregate and the refresh | The refresh returns the **new** cap and the client adopts it. Nothing to reconcile because the client holds no copy. |

---

## 6. Data model impact

**None.** No migration, no new table, no new column, no entity change.

Existing indexes serve both mappings:

- sprint section — `idx_issues_sprint ON issues (sprint_id, position) WHERE sprint_id IS NOT NULL`
  (`docs/design/agile-sprints-proposal.md:419-420`);
- backlog section — the project/position index already used by `findNextInSection` /
  `findPreviousInSection` (`IssueRepository.java:455`).

No Flyway rules are engaged (no `VARCHAR`/ENUM question, no UUID v7 generation, no
`@CreatedDate`). `migration-reviewer` is `n/a` for slice A.

---

## 7. API surface

### 7.1 New endpoints

```
GET /api/workspaces/{workspaceId}/projects/{projectId}/backlog/sections/backlog
      ?statusId=&assigneeId=&priorityId=&componentId=&labelId=&labelMatch=&fixVersionId=&includeDone=

GET /api/workspaces/{workspaceId}/projects/{projectId}/backlog/sections/{sprintId}
      ?statusId=&assigneeId=&priorityId=&componentId=&labelId=&labelMatch=&fixVersionId=
```

### 7.2 Response DTO — `BacklogSectionResponse`

```java
public record BacklogSectionResponse(
        SprintResponse sprint,        // null = the backlog section
        List<IssueResponse> issues,
        boolean truncated,
        long totalAvailable,
        SectionStats stats,
        int sectionCap,
        int bulkMoveCap
) {}
```

One record, not two. It is `BacklogViewResponse.SprintSection` with a nullable header plus the
two caps, and **the field names are byte-identical to the aggregate's section records**, so the
client patches a section without translating anything. `sprint: null` already means "the backlog"
throughout this domain — `Issue.sprint` null is the backlog, `planningStats`'s NULL group is the
backlog (`IssueRepository.java:527-529`) — so the nullable header is the domain's existing
encoding rather than a new convention.

### 7.3 Status codes

| Code | When |
|---|---|
| `200` | The section, possibly empty, possibly truncated. |
| `400` | Malformed path UUID; unknown `labelMatch`; too many distinct `labelId` values. |
| `401` | Unauthenticated (filter chain). |
| `404` | Workspace missing / caller not a member / project missing or in another workspace / sprint unknown or in another project. |

No `403` — there is no permission to miss here. No `429` today; see §8.

### 7.4 Slice B — `Paging` stops clamping silently

**Position: `Paging` should refuse, not clamp.** The house rule already exists and is written
into the published API description; `Paging` is the surface that violates it.

- Reports refuse an over-cap window with *"a `400` naming the cap — never silently clamped,
  because a chart of a window nobody asked for is exactly how a report earns 'these numbers
  don't match what I expected'"* (`openapi.yaml:235-238`).
- `/velocity`'s `sprints` is *"refused at both ends (1…12) rather than clamped, by the same
  never-answer-a-question-nobody-asked rule"* (`openapi.yaml:270-273`).
- The flow report distinguishes **defaulting** from **clamping** explicitly: *"a caller who
  sends an over-cap window still gets the `400`, because nothing you asked for is ever narrowed
  for you"* (`openapi.yaml:5091-5094`).

HD-96 is precisely the bug that rule predicts, and it stayed invisible for a release because the
one surface that narrows silently is the one every list endpoint goes through.

**Who a refusal breaks.** In this repo, exactly one caller currently over-asks: the planning
view's `refreshSection` (`components/sprints.tsx:330`), which slice A deletes. Everything else
sends `50` or a value from the SPA's 10/20/50/100 selector (`api.ts:586,667,722,922`). Outside
this repo, DC operators write scripts against `docs/api-dc.md`, which documents the current
behaviour as the contract: *"`size` (default 50, clamped server-side to a maximum of 100)"*
(`docs/api-dc.md:125`, `docs/api-cloud.md:89`). An integrator who read that and wrote
`size=1000` is **correct per today's contract**, and a `400` breaks them at upgrade with no
deprecation window. That is the real cost, and it is why this is a separable slice with its own
veto — see §12.

**Recommendation:** ship it in 0.16.0 with the three documentation lines changed in the same
commit and a line in the release notes. The blast radius is one documented sentence and no known
client, and the alternative — leaving the narrowing in place — leaves the *next* legitimate
`size > 100` surface silently wrong. If the owner prefers caution, slice A alone is complete and
correct, and slice B moves to 0.17.0.

**Shape of the refusal:** `400` naming the maximum, e.g.
`"size must be between 1 and 100"`. `size` absent stays "defaulting, not clamping" — `null` →
`DEFAULT_SIZE`, which narrows nothing anybody asked for. Applies to `size < 1` as well; note
that the search surface already *documents* `minimum: 1, maximum: 100`
(`openapi.yaml:10374`) while enforcing neither — `SearchRequest` carries no `@Min`/`@Max`
(`SearchRequest.java:18-24`) and `SearchService.java:95-96` re-implements the clamp by hand. So
the app already gives two answers to this question in prose and one in code.

**HD-163 (out-of-range `page`) is the same class and must take the same posture.** I could not
find HD-163 in this repository — it is a tracker ticket with no code or doc footprint here — so
I am describing the class, not verifying that ticket's reproduction. The mechanism in this code
is visible: `Paging.of` accepts any non-negative `page` up to `Integer.MAX_VALUE`
(`Paging.java:19`), and Spring Data narrows the offset to an `int` for
`Query.setFirstResult(int)`, which throws on a negative — a `500` on a value the caller merely
mis-typed. **The search surface has a worse variant of it and does not go through `Paging` at
all**: `SearchService.java:103` computes `setFirstResult(page * size)` in **`int` arithmetic**,
so the product overflows *before* any widening — and the very next lines widen deliberately for
the same product (`hasNext`, `SearchService.java:118`), which is good evidence line 103 is an
oversight rather than a decision. An overflow that wraps negative is a `500`; one that wraps
positive silently returns the wrong page.

This is also a correction to a framing worth stating: *"`Paging.of` has five call sites"* is a
count about a **member** (the helper), while the property that matters is a **category**
(every paged surface). There is a sixth paged surface that clamps identically without ever
calling `Paging.of`. Slice B's real deliverable is therefore *one validation, used by every
paged surface* — `SearchService` routed through it, not a second copy kept in sync.

### 7.5 Documentation targets

`api-docs-sync` must update, in the same change:

1. `src/main/frontend/public/openapi.yaml` — the two paths, the `BacklogSectionResponse` schema,
   and (slice B) the `size` parameter description at `openapi.yaml:7058`, the search `size`
   schema at `openapi.yaml:10374`, and the pagination sentence in the info block at
   `openapi.yaml:96`.
2. `docs/api-cloud.md` — the backlog section of the endpoint map; (slice B) the pagination
   convention at line 89 and the search row at line 2605.
3. `docs/api-dc.md` — the same, at lines 125 and 2630.

Do **not** hand-edit `src/main/resources/static/openapi.yaml`; the Vite build overwrites it from
`public/`.

---

## 8. Throttle — stated deliberately, not inherited

The rule here is that **a throttle is earned by the work a handler does, not by where it is
mounted**, and this repo has already paid a review round for the opposite assumption.

**What is true today, verified rather than assumed.** `ThrottleCoverageTest` requires a budget
for every handler under `com.hamstrack.report.controller` and `com.hamstrack.search`
(`ThrottleCoverageTest.java:84-89`), and seals the throttled path set at exactly four patterns:
`…/projects/*/reports/**`, `…/search/insights`, `…/search/**`, `…/filters/**`
(`ThrottleCoverageTest.java:265-277`). `BacklogController` is in `com.hamstrack.issue.controller`
and none of those four patterns matches `…/projects/*/backlog`. **The planning aggregate has no
per-principal budget, and neither does the issue list or the board list.**

**Is that right?** Partly. The argument that spares them is real: a report is O(project history)
and unbounded by what it returns, while a planning response is bounded by what it returns — at
most `MAX_PLANNING_VIEW_ROWS` assembled rows, 20 000 as configured
(`AgileProperties.java:75`), 6300 at the stock defaults. But "bounded" is not "small": at the
~1.9 KB-per-shipped-row accounting the reports budget uses (`application.properties:251-257`),
a default-configured planning view is ~12 MB of transient heap per request, and it is a `GET`
that a browser re-issues on every navigation. That is the same league as a throttled report,
unbudgeted. I think that is a genuine gap.

**Decision for this ticket: the section endpoints ship with the same budget as the aggregate —
which is none — and that is a deliberate statement of parity, not an omission.**

> **Correction (HD-96 implementation, security review — and a correction to the correction).**
> Item 1 below originally read *"the section endpoint does **strictly less** work than the
> aggregate … it narrows the per-request share of one already accepted."* **That was false in its
> load-bearing term.** A section fetch divides the row assembly and the response, but it *repeats*
> `planningStats`, so refreshing every section of a view one at a time ships the same bytes as one
> `GET …/backlog` while running that aggregation once per section — **21 aggregations instead of
> 1** at stock defaults, over the same rows.
>
> The first attempt to fix that sentence said `planningStats` is *"the one term of a planning read
> that the response size does not bound"*, **and that is false too.** A `LIMIT` bounds what comes
> back, never what is selected: `findSectionIssues` filters and then orders the whole matching set
> before its limit can apply, so `?statusId=<something the section does not hold>` visits and
> orders the entire section to return nothing. (The correlated label and fix-version predicates
> multiply the per-row cost; they are not what makes it unbounded.) **What is actually true** is
> narrower: `planningStats` is the *unconditional, cap-blind* term — it always reads and groups a
> whole section, whatever the filters and whatever the cap — while a row query is unbounded in
> work only as far as its filters happen to fall.
>
> *Three claims in a row, each tidier than the last and each slightly wider than the evidence.*
> Worth recording as a pattern rather than as three mistakes: this project prefers claims phrased
> about a **category** because they outlive claims about a member — and the cost of that
> preference is a standing pull toward the cleanest categorical sentence, which is reliably the
> one that overshoots. Prefer a mechanism you can point at in the SQL over an assertion about
> which term is "the only" anything.
>
> **The implementation narrowed what it could:** `includeBacklogGroup`, so a sprint section
> aggregates that sprint's rows instead of the project's (measured on a 62k-row corpus: half the
> buffers and the `Sort` node gone, because one group needs no sort). Note the plan still comes
> off a project index, not `idx_issues_sprint` — the statement stays anchored on `i.project`, so
> the sprint index is merely *eligible*. The saving is the row set, not the index; a claim about
> which index serves a query is exactly the kind this note exists to stop repeating. The backlog
> mapping's group is inherent and still paid.
>
> **The decision below is unchanged** — the ceiling is still the aggregate, and throttling the
> cheaper path alone drives clients onto the dearer one — but the follow-up's budget is earned by
> **`planningStats` being unconditional and cap-blind**, not by response size.

1. The section endpoint **divides what a planning response ships and repeats what it aggregates**:
   the same constants and one row query against the aggregate's one row query *per open section*,
   but one `planningStats` per section rather than one for the whole view. Post-fix, an abuser
   loops `…/backlog/sections/{id}` for at most `section-max-issues` rows per request where they
   could already loop `…/backlog` for up to `(open sprints + 1) × section-max-issues`. **The fix
   does not raise the ceiling** — the aggregate remains the most expensive single request — and a
   throttle on the section endpoint alone would push a refused client onto it.
2. Adding a fifth throttled pattern is a **sealed-set edit**: it fails
   `ThrottleCoverageTest.theThrottledPathSetIsSealed()`, whose failure message is a nine-item
   propagation checklist across properties, `.env.prod.example`, `docs/self-hosting.md`, both
   API references, `openapi.yaml`, `docs/project-state.md`, the HQL maintainers' guide and
   `api.ts` (`ThrottleCoverageTest.java:119-151`). That is correct and deliberate machinery, and
   smuggling it through a defect fix is exactly what it exists to prevent.
3. Throttling the section endpoint **without** the aggregate would be worse than throttling
   neither: a client refused on a section would fall back to the unthrottled whole-view refetch
   and cost the server more.

**Therefore the invariant, and it is testable now:** *the single-section endpoints and the
planning aggregate carry the same interceptor chain.* Phrased as a property of the planning
surface rather than as a list of today's paths, so a third planning endpoint inherits the claim.
§10 makes it an acceptance criterion — that guard is what makes "no budget" a decision that
cannot silently become an asymmetry.

**Filed as a follow-up, with a recommended shape** so the next author does not re-derive it:

- One pattern, `/api/workspaces/*/projects/*/backlog/**`, covering the aggregate and every
  section endpoint at once.
- Its **own** pot, not the reports pot. A planning meeting legitimately fires up to two section
  refreshes per drag (`pages/BacklogPage.tsx:278,379`); at the reports budget of 60/min a normal
  reorder session would start getting 429s. Sizing rationale mirrors the search budget's
  ("120 is ~10x ordinary SPA use", `application.properties:285`): `app.planning.requests-per-minute`,
  env `PLANNING_REQUESTS_PER_MINUTE`, default **240**, identical in DC and Cloud, master switch
  `app.rate-limit.enabled`, no per-family off switch, `@Min(1) @Max(10000)`, reusing
  `PerPrincipalMinuteBudget`.
- Whoever does it also decides whether `GET …/issues` and the board list belong on it. **That is a
  question this document poses and does not answer.** What can be said is why they are a *weaker*
  case than the planning reads, and the reason is not the one written here before: it is **not**
  that their work is bounded by what they return — no filtered, ordered, capped query's work is
  bounded, because a `LIMIT` bounds output and not selection — but that neither runs an
  **unconditional, project-wide aggregation**. That is the property this budget is earned by, so
  the deliverable is a pattern covering the planning reads; extending it is an argument that has
  to be made on its own evidence, about those endpoints' own worst case.

---

## 9. Frontend impact

`DESIGN.md`: **no visual change.** Same Beacon components, same layout, same tokens. The user
sees the same section, correct.

### Touched

| File | Change |
|---|---|
| `src/main/frontend/src/api.ts` | Add `apiGetBacklogSection(wsId, projectId, sectionRef, filters)`. Sends **no** `page`/`size`. `sprintsApi.get` loses its only caller (`components/sprints.tsx:347`) — the builder's call whether to delete it or keep it as an API wrapper; I lean delete. |
| `src/main/frontend/src/types.ts` | Add `BacklogSectionResponse`. `sectionCap` at `types.ts:426` stays — it still arrives, it is just no longer *sent back*. |
| `src/main/frontend/src/components/sprints.tsx` | `refreshSection` (312-365) becomes one call, one `setQueryData`. Delete the `cap`/`size` block (317-331) and its comment. Delete the `filtered` flag (309-310) if it has no other reader. `patchSection` (384-404) stops reconstructing stats and truncation — it copies the server's `issues`/`truncated`/`totalAvailable`/`stats` and adopts `sectionCap`/`bulkMoveCap`. Delete `statsFromIssues` (246) — after this it has no callers. Add: a `404` from a section refresh invalidates the aggregate query silently, with no error banner. |
| `src/main/frontend/src/pages/BacklogPage.tsx` | Behaviourally unchanged. The comment at 352-365 about the rendered list being bounded by `sectionCap` becomes true again for a refreshed section; re-read it and correct it if it now over-claims. |
| `src/main/frontend/src/pages/BacklogPage.*.test.tsx` | Existing fixtures set `sectionCap: 300` on the aggregate mock; add fixtures for the section endpoint. |

### Observable to a user beyond "rows stop disappearing"

1. **Section header numbers stop going stale under a filter.** Today, refreshing a *filtered*
   truncated section leaves the counters at their last server value
   (`components/sprints.tsx:402`). After the fix the header always shows the server's
   whole-section, filter-aware numbers.
2. **"Move every issue to →" stops moving a silently reduced set.** §1: today a post-refresh
   bulk move carries 100 ids and reports a truncation the server never applied.
3. **Header and rows can no longer describe two different instants.** Today a sprint refresh is
   two concurrent responses (`Promise.all`, `components/sprints.tsx:346-349`); a write landing
   between them yields a header that does not match its own rows. One request makes that
   unrepresentable.
4. **One round trip instead of two per sprint-section refresh** — the spinner clears sooner on
   the app's hottest interaction.
5. **The "showing N of M" affordance stops appearing spuriously** on any 101–300-row section.

---

## 10. Acceptance criteria

**Behaviour — the cap**

1. With `app.agile.section-max-issues=150` and a sprint holding 200 matching issues, the section
   endpoint returns **150** issues, `truncated: true`, `totalAvailable: 200`, `sectionCap: 150`.
2. Same install, a sprint holding 120: **120** issues, `truncated: false`, `totalAvailable: 120`.
3. With `app.agile.section-max-issues=40` and a section holding 60: **40** issues,
   `truncated: true`. (Retuning **down** must work too — the ticket's criterion is both
   directions.) Follow `BoardCapTest`'s `@SpringBootTest(properties = …)` pattern
   (`BoardCapTest.java:41-46`).
4. A section holding exactly `cap`: `truncated: false`.
5. No response is ever 100 rows because of `Paging`. No planning request carries `size`.

**Behaviour — parity with the aggregate**

6. For a project with several sections and a non-trivial filter set, each section returned by
   `GET …/backlog` is **field-for-field equal** to the same section fetched individually —
   issue ids in the same order, `truncated`, `totalAvailable`, every `stats` field,
   `sectionCap`, `bulkMoveCap`. Run it truncated and untruncated, filtered and unfiltered, and
   with `includeDone` both ways on the backlog section.
7. The set of filter query parameters accepted by the section endpoints equals the aggregate's
   (minus `includeDone` on the sprint mapping) — by construction if `BacklogFilters` is
   extracted, by test otherwise (§4.7).

**Tenancy — the top bug class**

8. Non-member of the workspace → `404` on both mappings.
9. Unknown workspace / unknown project / project in another workspace → `404`.
10. A sprint id belonging to a **different project in the same workspace** → `404`.
11. A sprint id belonging to a **different workspace** → `404`.
12. No `403` is reachable on either mapping.

**State**

13. A `COMPLETED` sprint's section still answers `200`.
14. A deleted sprint's section answers `404`, and the SPA drops the section and refetches the
    aggregate without showing an error banner.
15. An archived project's sections still answer `200`.

**Throttle**

16. `ThrottleCoverageTest.theThrottledPathSetIsSealed()` still passes unchanged — no new pattern.
17. A new test asserts the section endpoints and `GET …/backlog` resolve to the **same**
    interceptor chain, so the planning surface can never end up half-budgeted.

**Performance**

18. A section fetch is a constant number of statements independent of section size — assert with
    the query-count harness used by `LabelQueryCountTest` / `PermissionResolutionQueryCountTest`.
    Specifically: no query grows with the number of issues, labels, versions or custom fields.

**Frontend**

19. After a refresh of a 250-issue section (cap 300), all 250 rows are present and no truncation
    banner is shown.
20. Refreshing a *filtered, truncated* section updates the header counters from the response
    rather than leaving them stale.
21. `grep -rn "size" src/main/frontend/src/components/sprints.tsx` shows no page-size argument to
    any planning request.
22. `npx tsc -b` is clean (`tsc --noEmit` proves nothing here — solution-style root tsconfig).

**Slice B, if taken**

23. `GET …/issues?size=101` → `400` naming the maximum; `size=100` → `200`; `size` absent → 50
    rows; `size=0` → `400`.
24. `POST …/search` with `size=101` → `400` (the documented `maximum: 100` becomes enforced).
25. An out-of-range `page` no longer `500`s on any paged surface, including `POST …/search` —
    where the offset must be computed in `long`, not `int` (`SearchService.java:103`).
26. `SearchService` no longer contains its own copy of the size clamp.
27. Both API references and `openapi.yaml` say "refused", not "clamped", everywhere they
    describe `size`.

---

## 11. DC / Cloud implications

**Uniform. Nothing mode-specific, and that is a decision rather than an omission.**

- No new property, no new env var, no profile-conditional bean, no `@ConditionalOnProperty`.
  `AgileProperties` already declares this family to be *"DoS guards and defaults, not mode
  switches: identical values in DC and Cloud, no profile override, no feature flag (a kill switch
  would create a second, untested code path)"* (`AgileProperties.java:16-20`). Nothing here
  changes that.
- Nothing touches storage, email, auth or billing, so there is no cloud-only assumption to give a
  self-hosted path to.
- The endpoints are workspace-scoped and membership-guarded like everything else, so there is
  nothing to fork.
- **`dc-cloud-guard` is `n/a` for slice A** (no `*.properties`, profile, compose or env change)
  and `n/a` for slice B. It becomes mandatory if the §8 throttle follow-up is pulled forward:
  `PLANNING_REQUESTS_PER_MINUTE` would then need the full wiring chain —
  `application.properties` → `docker-compose` → `.env.prod.example` → `docs/self-hosting.md` →
  both API references → `README` — with identical defaults in both modes.

The fix's answer to "must not hardcode either number" is the strongest form available: after
slice A there is **no** number to hardcode on either side of the wire, because the request
carries no size and the client keeps no copy.

---

## 12. Open questions

1. **Does any external client depend on `size > 100` being clamped rather than refused?**
   *This is the highest-risk assumption in this document.* I can prove there is no such caller
   inside this repository; I cannot prove it for DC installs, and both published API references
   currently document the clamp as the contract (`docs/api-dc.md:125`,
   `docs/api-cloud.md:89`) — so an integrator relying on it is following the documentation, not
   abusing it. **Recommended default: ship slice B in 0.16.0 with the doc lines and a release
   note.** If that is unacceptable, take slice A alone; it is complete without slice B.

2. **Is HD-163 the `int`-narrowing of the offset, and does it also cover `SearchService`?**
   HD-163 has no footprint in this repository, so I have described the *class* of defect and the
   two mechanisms visible in this code (`Paging.java:19`; `SearchService.java:103`) rather than
   verifying that ticket's reproduction. **Recommendation: fold the `SearchService` overflow into
   whichever ticket fixes `page`, and give both `page` and `size` the same posture** — a
   `Paging` that refuses `size` while clamping `page` is two philosophies in twenty-two lines.
   Refusing an out-of-range `page` is *not* the compatibility break refusing `size` is: nobody
   legitimately asks for page 2 000 000 000, so that half can ship without a deprecation note.

3. **`BacklogFilters` via `@ModelAttribute`, or a drift test?** Recommended: the record (§4.7).
   It touches the aggregate's controller signature — wire-compatible, but it is a change to
   working code inside a defect fix, so it is the owner's call.

4. **Delete `sprintsApi.get` and `statsFromIssues`, or keep them?** Both lose their only callers.
   Recommended: delete both. `statsFromIssues` in particular encodes "derive section stats from
   the rows you happen to have", which is the reasoning this ticket is removing — leaving it
   exported invites its return.

5. **Should the §8 planning throttle be pulled into 0.16.0?** Recommended: no, follow-up ticket
   with the shape in §8. The gap is pre-existing, this fix narrows rather than widens it, and the
   change requires a nine-artefact propagation that deserves its own review.

6. **Nit, unrelated, found while reading:** `SprintService.java:181` closes a javadoc tag as
   `<\strong>` instead of `</strong>`. Harmless today; worth a one-character drive-by.
