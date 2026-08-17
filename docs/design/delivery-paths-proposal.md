# Delivery paths — how a project behaves per way of working

Ticket **HD-95** · Status: **decision draft 2026-08-16** — analysis-first; no code ships from this
document. Author: systems-analyst. Implementation is split into the follow-up slices in §12.

Structural precedents followed: `docs/design/agile-sprints-proposal.md` (§3.5 board mode, open
questions 9–10 — this document supersedes both) and
`docs/design/labels-components-versions-proposal.md` (project-scoped content with a lifecycle).

Companion docs to update when the slices ship: `src/main/frontend/public/openapi.yaml`,
`docs/api-cloud.md`, `docs/api-dc.md`, `docs/project-state.md`. **No** `.env.prod.example` /
`docs/self-hosting.md` change — this feature adds zero configuration (§10).

---

## 1. Problem & goal

Hamstrack currently decides what a project shows by three unrelated mechanisms: one stored attribute
(`projects.board_mode`, `KANBAN | SCRUM`), and four ad-hoc *presence heuristics* scattered across the
SPA that infer "does this team use X?" from "does X already exist here?" —
`BacklogPage.tsx:139` (`boardMode === 'SCRUM' || openSprints.length > 0`),
`IssueDetail.tsx` (`showSprintCell`, `showFixVersionCell`), and `CreateIssueModal.tsx`
(`sprintOptions.length > 0`). Nothing states, in one place, what kind of project this is; every new
surface invents its own rule; and one of those rules has already produced a production dead end.

**The concrete bug.** V11 defaulted every existing project to `KANBAN`. The Backlog hides *both*
sprint-creation entry points behind `showSprintArea`, which is false until a sprint exists. So a
Kanban team cannot create its first sprint from the Backlog — the only route is Project settings →
Board → Scrum, which nothing on the Backlog points at. **The rule that hides Scrum vocabulary from
Kanban teams also hides the way to become a Scrum team.** The agile spec's stated intent was "hide
until a sprint exists, never hard-block the API"; the API indeed does not block, but the UI does.

**Goal.** One declared, project-level model of *how this team delivers*, chosen explicitly at project
creation, changeable at any time without losing data, and read by every surface instead of each
surface guessing. Success looks like: a curator creating a project answers one short question; every
page afterwards shows exactly the vocabulary that answer implies; turning a capability on is always
one visible click away from where you'd want it; and turning one off never deletes anything.

**Non-copy statement.** "Kanban" and "Scrum" are industry terms (lean manufacturing; Schwaber &
Sutherland), not Jira naming, and the codebase already uses them. Nothing here reproduces another
tool's project-type/scheme mechanics, screens or URL shapes. The capability-plus-preset shape is our
own, and mirrors this codebase's existing catalog-plus-binding instinct.

---

## 2. The shape of the choice — decision and justification

> **Decision: a project stores three independent delivery capabilities. The UI presents them as a
> two-way board-style choice plus one add-on toggle, labelled with a derived preset name. It is
> NOT a three-way exclusive enum.**

### 2.1 Why not the literal three-way radio

The owner's framing is *"Kanban, Scrum or Releases — pick one at creation, changeable later."* Three
of those four properties are exactly right and are honoured in full: **explicit, up-front,
reversible, and told-to-be-reversible.** The fourth — *exclusive* — is contradicted by the model we
already have.

Kanban and Scrum are two ways to run **the same board over the same issues**, which is why one
`board_mode` column with two values is a faithful representation. Releases are not a third way to run
that board; they are an **orthogonal capability over a different object** (`versions`, with a
fix/affects lifecycle and its own page). A team running two-week sprints that also ships tagged
2.4.0 releases is completely ordinary, and under a three-way enum it is *unrepresentable*. Worse, a
"Releases project" would still have to answer "what does your board show?" — so the enum would
either secretly carry a board mode anyway or leave the board undefined. And on upgrade there is no
rule that can classify existing projects into three buckets, because nothing in the data says a
project "is a Releases project" — it either has versions or it does not.

A three-way radio would therefore ship a lie that we would have to walk back the first time someone
asks for "Scrum with releases" — a schema change and a migration, on a field we just taught users to
read as exclusive.

### 2.2 Why not two plain booleans either

The honest minimum is `boardMode` + `releasesEnabled`. That is correct but presents as a settings
page with two unrelated switches and no story — it loses precisely what the owner asked for (one
explicit, named, memorable choice), and it does not scale: the next capability adds a third orphan
checkbox.

### 2.3 The chosen model

**Capabilities (stored, the single source of truth):**

| Capability | Values | Governs |
|---|---|---|
| `board` | `KANBAN` \| `SCRUM` | sprints as a planning concept: backlog sprint sections, sprint-scoped board, sprint field on issues, sprint pickers |
| `releases` | on / off | versions: the Releases page and rail item, fix/affects pickers and filters |
| `estimation` | on / off | story points: the points input and every point sum |

**Presets (derived for display, never stored):** the server computes a label from the capability set.

| Preset | `board` | `releases` | `estimation` |
|---|---|---|---|
| `KANBAN` | KANBAN | off | off |
| `SCRUM` | SCRUM | off | on |
| `RELEASES` | KANBAN | on | off |
| `CUSTOM` | any other combination | | |

One stored truth, one derived label — no drift is possible, and "Scrum + Releases" simply reads as
*Custom (Scrum, Releases)* rather than being forbidden.

**How the user experiences it (the owner's three names, honestly):** the creation picker and the
settings page show **three named cards** — Kanban, Scrum, Releases — where Kanban and Scrum are a
radio pair (they answer "how does the board work?") and **Releases is a toggle card, visually
separated, labelled "works with either"**. The user still meets three named things up front and
picks explicitly; they are just not told a falsehood about exclusivity.

`estimation` is deliberately **not** on the creation picker — it follows the preset (Scrum → on) and
is adjustable in settings. Three questions at create time is one too many.

### 2.4 Where the choice lives

**Project attribute only.** Not a workspace default, not a global catalog entry.

- It is not config-like taxonomy: there is nothing to share between projects, no reuse, no admin
  curation, and no NULL-means-system-default semantics. It is a per-project working preference, the
  same class as `board_mode` today. `ProjectConfigService` gains **nothing** and
  `ProjectConfigResponse` gains **no field** — a delivery change must not invalidate the config cache
  that every board render depends on. (Same reasoning as agile spec §3.6 for sprints.)
- A workspace-level *default* is a real convenience for a shop creating ten Scrum projects, but it
  introduces a second source of truth and an inheritance question ("does changing the workspace
  default retro-change projects?") for a saving of two clicks. **Recommended instead:** the creation
  picker pre-selects the preset last chosen by *this user in this workspace*, remembered in
  `localStorage` only, explicitly non-authoritative and never sent as a default by the server.
  (Open question 1.)

---

## 3. Scope

### In scope

- The capability model, its storage, its API representation and its derived preset label.
- The per-surface visibility matrix (§6) and the two rules that make it mechanical (§5).
- The creation picker, the settings surface, and the switch-confirmation flows.
- The upgrade rule for every existing project, and the anti-stranding invariant that resolves the
  production bug **by construction**.
- The two leftovers 0.13.0 handed over: whether story points stay unconditional (§8), and what
  happens to saved filters written against the retired `story_points` custom key (§9).
- The removal of all four presence heuristics.

### Out of scope / non-goals

- **Any new capability beyond the three named.** Components, labels, hierarchy, workflows and custom
  fields are not delivery capabilities (§5.3 gives the test); nothing about them changes.
- **Permissions.** A capability is never an authorization boundary (§5.1). No endpoint gains a
  capability check.
- **Reports.** Reports is still the `SOON` rail item; §6 records what it must obey when it lands, and
  nothing more.
- **Workspace or instance-level defaults**, delivery templates, and per-user preferences.
- **Renaming the `board_mode` column.** The name is now slightly narrow, but a shipped column +
  DTO field + TS type rename costs more than it returns; the domain vocabulary moves, the column
  does not.
- **A project audit trail** for delivery changes (open question 6).
- Migrating or rewriting the text of anybody's saved filters (§9 resolves at run time instead).

---

## 4. Actors & permissions

| Action | Required |
|---|---|
| Read a project's delivery capabilities | any **project member** (they ride `ProjectResponse`, which every member already fetches) |
| Choose capabilities at project creation | whoever may create a project in the workspace today — unchanged |
| Change capabilities later | **project curator** = project `MANAGER` **or** workspace `OWNER`/`ADMIN` (`ScopeResolver.requireProjectCurator`) — the gate `ProjectService.update` already uses since HD-22 §3.2 |
| Everything a capability reveals (create a sprint, create a version, set points) | unchanged from today — the capability changes *visibility*, never *authority* |

**Tenancy.** No new entity and no new endpoint: the capabilities are columns on `projects`, read and
written through the existing workspace-scoped `/api/workspaces/{workspaceId}/projects/{projectId}`
paths. A missing workspace, a missing project and a non-member all still yield **404**, never 403.
**403** stays reserved for a member without the curation role. Nothing here can leak across tenants,
because nothing here is addressable independently of a project.

**Archived projects** stay frozen: changing delivery capabilities on an archived project is
**409**, exactly like `boardMode` today.

---

## 5. The three rules that make everything else mechanical

These are the load-bearing part of this document. Every rule in §6 is a consequence of them, and a
future feature is checked against them rather than against the matrix.

### 5.1 Rule A — capabilities are presentation, never API

**A capability never changes the API.** Every endpoint, every request field, every filter and every
HQL field behaves identically whether a capability is on or off. There is no capability-conditional
4xx, and nothing is ever absent from the API.

Why: (a) the agile spec already established `board_mode` as "a presentation switch, never a
permission", and integrations, imports and scripts written before a flip must keep working;
(b) gating the API creates a second code path that the tenancy and permission logic must reason
about, for a rule that a UI can enforce for free; (c) a capability that can 422 you turns a
reversible preference into a data-entry trap — the exact class of harm the prod bug is.

Consequence: `POST /sprints` succeeds on a Kanban project. `fixVersionIds` applies on a project with
releases off. That is intentional and is what makes §5.2 safe.

### 5.2 Rule B — controls are gated, values never are

For every path-gated surface, split it in two:

- **Controls** — anything that *creates or changes* path-specific data (a sprint picker, a
  "Create version" button, a points input, a fixVersion filter). These render **iff the capability is
  on**.
- **Values** — anything that *displays existing* path-specific data (the sprint badge on an issue
  that has one, a fix-version chip, a stored point value, a history entry). These render **whenever
  the value exists**, regardless of capability, in a read-only presentation with a tooltip naming
  the reason (*"Sprint planning is off for this project"*).

Consequences, all of them desirable: switching a capability off is provably non-destructive and
*legible* — you can still see what you have; nothing is ever silently hidden; and the issue history
is never censored (an audit trail that changes with a UI preference is not an audit trail).

### 5.3 Rule C — the bootstrap invariant (this is the bug fix)

> **Every capability has at least one enabling affordance that is visible while the capability is
> off, placed where a user would look for the capability. Using it turns the capability on, tells the
> user so, and says it is reversible. No capability's entry point may be gated by its own capability,
> or by the existence of its own data.**

And its negative half:

> **No surface may derive visibility from data presence.** `openSprints.length > 0`,
> `sprintOptions.length > 0` and `versionOptions.length > 0` are removed. "Does this project use X?"
> is answered by the declared capability, never inferred. (Presence may still be used to decide
> whether to render a *value* — that is Rule B, and it is a different question.)

Concretely:

- **Backlog**, curator, iterations off: a **"Plan in sprints"** button sits in the same place
  "Create sprint" occupies today. Clicking it opens the create-sprint dialog with one extra line —
  *"This turns on sprint planning for this project. You can turn it off again in Project settings →
  Delivery."* — and on submit sets `board = SCRUM` and creates the sprint in the same user gesture.
- **Releases page**, curator, releases off: the route still resolves; the page renders read-only with
  a banner and a **"Turn on releases"** action; "Create version" behaves like the above.
- **Estimation**: enabled from Project settings → Delivery only. It has no dedicated page to seed it
  from, and unlike sprints/versions it strands nobody — a project with points data always still shows
  those values (Rule B) and the settings page always lists it.

This is why the migration's blanket default is harmless (§7): even a project defaulted to the
"wrong" path can always reach the right one from the page it is standing on.

**The test for "is this a delivery capability?"** — a future feature qualifies only if it (a)
introduces vocabulary a team on another path should never have to read, (b) has its own objects with
their own lifecycle, and (c) can be switched off without any issue losing data. Components, labels,
hierarchy and custom fields fail (a) or (c) and are **not** capabilities.

---

## 6. The per-surface visibility matrix

`I` = `board = SCRUM` (iterations) · `R` = releases · `E` = estimation · `∨ value` = Rule B
(render read-only when a value exists).
Three treatments only: **absent from API** — *never happens* (Rule A); **hidden** — not rendered;
**read-only** — rendered, inert, with an explaining tooltip.

| Surface | Element | Gate | When the gate is off |
|---|---|---|---|
| **Nav rail** | Board, Backlog | — | always visible |
| | Releases | `R` | **hidden** from the rail; the route still resolves (Rule C) |
| | Reports (when it lands) | — | item always visible; its *content* must follow this matrix — a velocity/burndown panel is `I`-gated, a release-progress panel is `R`-gated, and a Kanban project sees flow metrics only |
| | Settings | curator | unchanged |
| **Board** | continuous board over the project | `!I` | — |
| | sprint scoping + sprint header | `I` | **hidden** |
| | "No active sprint" empty state | `I` | **hidden**; must itself offer *Create & start a sprint* (curator) and *Show all issues* (any member, a local view toggle — never a settings change) |
| | column point subtotals | `I ∧ E` | **hidden** |
| **Backlog** | ranked list + drag-to-rank | — | **always** — rank is `issues.position`, the shared order key of the board too; it is universal and never path-gated |
| | sprint sections | `I` | **hidden**, *except* sections for sprints that are still `ACTIVE`/`FUTURE`, which render **read-only** (Rule B) with *Complete sprint* still offered to curators — a running sprint must never become invisible |
| | "Create sprint" / "Plan in sprints" | curator | **always visible to curators** (Rule C); enables `I` on first use |
| | per-section point sums, `n unestimated` | `E` | **hidden** |
| | fixVersion filter | `R` | **hidden** |
| **Create-issue modal** | Sprint picker | `I` | **hidden** |
| | Story points input | `E` | **hidden** |
| | Fix version(s) picker | `R` | **hidden** |
| **Issue detail** (drawer + full page) | Sprint cell | `I ∨ value` | **read-only** when off with a value; **hidden** when off and empty |
| | Story points cell | `E ∨ value` | same |
| | Fix / Affects version cells | `R ∨ value` | same |
| | history entries for `sprint` / `storyPoints` / versions | — | **always** — audit is never filtered by a UI preference |
| **Search (HQL)** | parser / compiler field resolution | — | **always** — every registered field resolves on every project (§9) |
| | `/search/schema` field suggestions | ≥1 visible project has the capability | omitted from *suggestions* only; still parses and runs |
| | `/search/schema` value picklists (`SPRINT`, `VERSION`) | per project | scoped to visible projects **with the capability on** |
| | results table columns | user's query / column choice | user-driven — no vocabulary is imposed |
| **Saved filters** | save, load, run | — | **always** — a filter never breaks because a project flipped (§9) |
| **Home** | any path vocabulary | — | **never present.** Home is cross-project; it shows universal attributes only (status, priority, assignee, due date, activity). A future "your active sprint" panel must be per-project, rendered only for `I` projects, and **absent** — not empty — otherwise |
| **My work** | any path vocabulary | — | same as Home. A per-row sprint/version *chip* is allowed under Rule B (it is a value the issue carries); a sprint *grouping* or a "Sprint" column header is not |
| **Notifications** | path-specific types | project capability | none exist today. When one is added it is emitted only by projects with the capability on, and its text names the project |
| **Command palette** | project-scoped actions | capability of the *current* project | "Create sprint" appears for curators regardless (Rule C); "Start sprint" only when `I` |
| **Project settings** | Delivery tab | curator | **always lists all three capabilities, including the off ones**, each with its blurb — an off capability must be discoverable, never merely absent |

Two matrix-wide notes:

1. **Nothing in this table is a security control.** Every hidden control corresponds to an endpoint
   that still works (Rule A). Reviewers must not read "hidden" as "protected".
2. **Every "hidden" cell is one click from visible**, and that click is always in the place the
   hidden thing would have been (Rule C).

---

## 7. Existing projects on upgrade

The migration's guiding rule, chosen deliberately over "the model's ideal defaults":

> **An upgrade never takes away a capability a project already has access to.**

| Capability | Existing projects become | Why |
|---|---|---|
| `board` | **unchanged** (`KANBAN`/`SCRUM` as stored) — **except** a project that already owns ≥1 sprint row is set to `SCRUM` | The exception is the mid-adoption rescue: a team that created a sprint and then flipped back (or seeded one via the API) is demonstrably using iterations, and would otherwise land on a Backlog whose sections are hidden |
| `releases` | **on for every existing project** | Every project can reach the Releases page today; a data-driven "only if it has versions" rule would remove a nav item from teams who were about to use it, on upgrade, with no warning |
| `estimation` | **on for every existing project** | The story-points input has been unconditional since 0.13.0; same argument |

New projects created after the ship get the picker defaults instead (**Kanban, releases off,
estimation off** — see open question 2), so the model's leanness arrives through explicit choice
rather than through a silent removal.

**Why this is enough, given that a blanket default is exactly what caused the prod bug.** It is not
the migration that fixes the bug — it is Rule C. The migration keeps every project exactly as capable
as it is today; Rule C guarantees that a project on the "wrong" path can always change it from the
page where the missing thing belongs. A migration that silently defaults everything is only dangerous
when the UI has no way back, which is precisely the property this model removes.

---

## 8. Native attributes vs path visibility (the first 0.13.0 leftover)

**The rule.** A native issue attribute may be hidden by delivery path **only if** it satisfies one of:

- **(a) It is meaningless without a path-specific object** — `sprint` needs sprints to exist as a
  concept; `fixVersion`/`affectsVersion` need versions. Hiding these is not an opinion, it is the
  absence of a referent.
- **(b) It is explicitly declared as a capability the project turned off**, with a settings row the
  user can see and flip.

Attributes with universal meaning — title, description, status, priority, type, assignee, reporter,
`dueDate`, labels, components, parent, attachments, comments — are **never** path-gated, ever. And in
both cases Rule B still applies: a hidden attribute is never removed from the API, never cleared, and
always rendered read-only when the issue carries a value.

**Applied to story points: no, the input does not stay unconditional.** Story points become case (b)
— the `estimation` capability — **on** for the Scrum preset and **off** for the Kanban preset on new
projects, **on** for every existing project on upgrade (§7).

Reasoning: an always-present numeric field that most Kanban teams never fill is real noise in the
create modal and a permanently empty cell in the issue-detail grid; points are also the one native
attribute whose *aggregates* (sprint sums, column subtotals) are path-specific, so the project
already has to know whether it estimates. Making it a declared capability is nearly free under this
model and removes the last "should we show this?" judgement call from individual components.

Counter-argument, stated fairly: this is the **lowest-confidence** of the three capabilities — it is
the only one whose sole job is hiding one input, and it is the only one where a team could plausibly
want points without sprints (flow sizing on a Kanban board). That case is served: `estimation` is
independent of `board`, so "Kanban + estimation" is a legal combination and reads as *Custom*.
**Fallback if the owner disagrees** (one paragraph, so the switch is cheap): drop the
`estimation_enabled` column, the settings row and the `E` column of §6; the points input and every
point sum go back to unconditional; presets reduce to `board` × `releases` (four combinations, three
named). Nothing else in this document changes.

---

## 9. Search backwards-compatibility (the second 0.13.0 leftover)

### 9.1 Hidden fields still resolve — always

**When a field is hidden by path, HQL and saved filters still resolve it, everywhere, unchanged.**
`sprint`, `fixVersion`, `affectsVersion` and `storyPoints` remain parseable and executable regardless
of any project's capabilities. This is Rule A applied to search, and it is not negotiable: search is
cross-project by nature (a workspace may hold one Scrum project and nine Kanban ones), and a filter
that stopped working the moment a colleague flipped a project preference would be the same class of
failure as the bug we are fixing.

The only capability-awareness in search is **soft and suggestion-only**:

- `/search/schema` lists a field when **at least one visible project** has the corresponding
  capability on. If nobody does, the field is omitted from *autocomplete* but still parses, still
  compiles, and still runs if typed or loaded from a saved filter.
- The `SPRINT` and `VERSION` value picklists are additionally scoped to visible projects with the
  capability on (they already scope to visible projects; this is one predicate more).

### 9.2 Retired keys become aliases — yes

V11 archived the global `story_points` custom field and promoted the value to the native
`issues.story_points` column under the HQL name `storyPoints`. `ResolutionContextFactory` skips
archived `field_defs`, so **every saved filter written as `story_points = 5` now fails with "unknown
field"** — silently, and only when the user next runs it.

**Decision: retired keys become permanent aliases, resolved as a last-resort fallback — not as a
`FieldRegistry` entry, and not by rewriting anybody's stored filter text.**

The mechanism matters, because of an existing precedence trap: `HqlCompiler.isCustom` treats a name
as a custom field **only when the registry does not know it** — the registry wins. Registering
`story_points` in `FieldRegistry` would therefore permanently **shadow** any workspace- or
project-scoped custom field a tenant happens to key `story_points`, in every workspace, forever.

So the alias lives in a small retired-key map consulted **after** both normal resolution steps:

```
system field (FieldRegistry)  →  visible custom field (ResolutionContext)  →  retired-key alias  →  "unknown field"
```

`story_points` → `storyPoints` is the only entry today. (V11 also archived the `sprint` placeholder
field, but `sprint` is already a live registry name, so it resolves natively with no alias needed.)

Deliberately **not** done: rewriting the stored text of saved filters in a migration. Editing a
user's saved query is worse than resolving it — it is unreviewable, irreversible, and wrong the
moment the user's intent differed from our guess. Aliases are also **not** offered in `/schema`
suggestions: they are compatibility, not vocabulary.

---

## 10. DC / Cloud implications

**None — and that is a positive assertion, not an omission.**

- No new Spring profile, no `@ConditionalOnProperty` bean, no `application-{dc,cloud}.properties`
  divergence, **no new property and no new environment variable**. Delivery capabilities are three
  columns on a tenant-owned row, chosen per project by a curator; there is nothing an operator could
  usefully configure and nothing a deployment mode could reasonably differ on.
- No storage, email, auth or billing surface is touched, so there is no cloud-only assumption to give
  a self-hosted path to.
- Behaviour is byte-identical in `dc` and `cloud`.

`dc-cloud-guard`'s job on these slices is therefore to **confirm the absence** of properties, profile
branches and env wiring — if any appears, the design has drifted.

---

## 11. Data model & API impact

### 11.1 Migration outline — `V12__delivery_capabilities.sql`

The chain currently ends at `V11__sprints.sql`; **the next free number is V12**. Purely additive plus
two explicit, documented backfills.

```sql
-- V11 introduced the guarded shared trigger; reuse it so a migration-time backfill
-- does not mark every project as "recently updated". SET LOCAL expires with the
-- Flyway transaction and cannot leak into the pool.
SET LOCAL hamstrack.skip_updated_at = 'on';

ALTER TABLE projects ADD COLUMN releases_enabled   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE projects ADD COLUMN estimation_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- §7: an upgrade never takes away a capability a project already has.
UPDATE projects SET releases_enabled = TRUE, estimation_enabled = TRUE;

-- §7: mid-adoption rescue — a project that already owns a sprint is using iterations,
-- whatever its board_mode says.
UPDATE projects p SET board_mode = 'SCRUM'
 WHERE p.board_mode <> 'SCRUM'
   AND EXISTS (SELECT 1 FROM sprints s WHERE s.project_id = p.id);
```

Notes for `migration-reviewer`: `BOOLEAN`, not `CHAR`/ENUM; no new PK so no UUID concern; the column
DB defaults (`FALSE`) deliberately match the *new-project* defaults while the explicit `UPDATE`
carries the *existing-project* rule, so the two policies are visibly separate rather than smuggled
into a default. `board_mode` is **not** renamed (§3).

### 11.2 Entity

`Project` gains two fields:

```java
@Column(name = "releases_enabled", nullable = false)   private boolean releasesEnabled = false;
@Column(name = "estimation_enabled", nullable = false) private boolean estimationEnabled = false;
```

Primitive `boolean` is correct **on the entity**. In the **request DTO** they must be boxed
`Boolean` — the documented Jackson 3 `FAIL_ON_NULL_FOR_PRIMITIVES` trap makes a primitive in a
partial-PATCH record 400 on any body that omits it (this bit `UpdateIssueRequest` already).

`board_mode` / `BoardMode` are unchanged.

### 11.3 API surface

No new endpoints. Two existing ones gain a field; one response gains an object.

```
POST   /api/workspaces/{workspaceId}/projects              201  ProjectResponse   (+ delivery in body)
PATCH  /api/workspaces/{workspaceId}/projects/{projectId}  200  ProjectResponse   (+ delivery in body)
GET    …/projects, …/projects/{projectId}                  200  ProjectResponse   (+ delivery)
```

```jsonc
// ProjectResponse — ADDITION
"delivery": {
  "board":      "KANBAN" | "SCRUM",   // reuses the existing BoardMode enum — no new enum, no mapping
  "releases":   true,
  "estimation": false,
  "preset":     "KANBAN" | "SCRUM" | "RELEASES" | "CUSTOM"   // DERIVED, read-only, never stored
},
// DEPRECATED mirror, kept for exactly one minor release so nothing breaks mid-flight
"boardMode": "KANBAN" | "SCRUM"

// CreateProjectRequest / UpdateProjectRequest — ADDITION (optional; partial on PATCH)
"delivery": { "board": "KANBAN"|"SCRUM"|null, "releases": true|null, "estimation": true|null }
```

Rules:

- `delivery` omitted on **create** ⇒ the picker defaults (`KANBAN`, releases off, estimation off).
- `delivery` omitted on **update** ⇒ unchanged; each member is independently nullable (partial).
- `preset` is **not accepted** in requests — one source of truth. Clients send the three capabilities;
  the preset label is a display convenience the server computes.
- Legacy top-level `boardMode` is still accepted on PATCH. **`boardMode` and `delivery.board` both
  present and disagreeing ⇒ 400.**
- Status codes: **200/201** normal · **400** contradictory `boardMode`/`delivery.board` or an unknown
  enum value · **403** member without the curation role · **404** unknown workspace/project or
  non-member · **409** project archived. **No capability-conditional status codes anywhere** (Rule A).

`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` follow, via `api-docs-sync`. The
`docs/api-dc.md` "operator settings that affect the API" table needs **no** new row (§10).

---

## 12. Frontend impact

New shared plumbing:

- `src/main/frontend/src/types.ts` — `ProjectDelivery`, `DeliveryPreset`.
- `src/main/frontend/src/hooks/useProjectDelivery.ts` (new) — reads the already-cached
  `['project', wsId, projectId]` entry and returns `{ iterations, releases, estimation, preset,
  isCurator }`. **The single place any surface asks "does this project do X?".** Costs no request.

Files whose ad-hoc heuristics are deleted and replaced by that hook:

| File | Today | After |
|---|---|---|
| `pages/BacklogPage.tsx:139` | `showSprintArea = boardMode === 'SCRUM' \|\| openSprints.length > 0` | `iterations`; plus §5.3's always-visible curator affordance and the read-only open-sprint sections |
| `pages/IssueDetail.tsx` | `showSprintCell = sprintOptions.length > 0 \|\| !!issue?.sprint`; `showFixVersionCell = versionOptions.length > 0 \|\| fixVersions.length > 0` | `iterations ∨ value`, `releases ∨ value`, read-only when off (Rule B) |
| `components/CreateIssueModal.tsx` | `sprintOptions.length > 0` gates the sprint picker; points unconditional | `iterations`, `estimation`, `releases` |
| `pages/BoardPage.tsx` | `boardMode` | `iterations`; empty state gains *Create & start a sprint* + *Show all issues* |
| `components/NavRail.tsx` | Releases item unconditional | gated on `releases`; route still resolves |
| `pages/ReleasesPage.tsx` | — | off-state banner + enable-on-first-use |

New / reworked surfaces:

- **`components/CreateProjectModal.tsx`** — one new block below Key, not a wizard step:

  > **How will this team deliver?**
  > ◉ **Kanban** — Continuous flow. One board, no time-boxes.
  > ○ **Scrum** — Fixed-length sprints you plan, run and close. Adds sprint sections to the backlog
  >   and story points to issues.
  > ☐ **Releases** — Group issues into versions and ship them from a Releases page. *Works with
  >   either.*
  >
  > *You can change this any time in Project settings → Delivery.*

  The reassurance line sits **directly under the group, always visible** (not a tooltip, not a
  post-create toast — it is the sentence that makes an up-front choice feel cheap). The picker is
  **not skippable and needs no skip**: Kanban is pre-selected, the Create button is never blocked,
  and there is no state in which the user is stuck on this question.
- **`pages/settings/ProjectDeliverySettingsPage.tsx`** — replaces `ProjectBoardSettingsPage.tsx`; the
  settings tab is renamed **Board → Delivery**, with the old `/settings/board` path redirecting so
  bookmarks and the existing link from the Scrum blurb survive. It shows all three capabilities
  (including off ones), the derived preset name, the switch confirmations of §13, and the
  "kept data" notices ("This project still has 3 unreleased versions").
- **DESIGN.md compliance** — preset cards reuse the existing selected-card treatment from
  `ProjectBoardSettingsPage` (`--color-brand` border + `--shadow-card`); the read-only value chips
  of Rule B use the muted/secondary tokens, never a new colour; never the Tailwind `max-w-*` scale
  (our `@theme --spacing-*` shadows it) — inline `maxWidth`.

---

## 13. Switching paths later — behaviour & edge cases

**Invariant: switching never destroys, clears or moves issue data.** No `sprint_id` is nulled, no
version link is removed, no point value is erased, ever, by any capability change.

| Switch | Precondition | Behaviour |
|---|---|---|
| Kanban → Scrum | any | No confirmation. Sections appear. Nothing to lose. |
| Scrum → Kanban | no ACTIVE or FUTURE sprint | No confirmation. |
| Scrum → Kanban | FUTURE sprints exist | Confirm dialog naming them: *"Sprint 8 and Sprint 9 are kept, and stay on the Backlog as read-only sections that can't be re-planned or started until sprint planning is back on."* — **corrected 2026-08-17**: this row previously said they would be "kept but hidden", which contradicted §6 (sections for `ACTIVE`/`FUTURE` render read-only, only `COMPLETED` are filtered) and would have promised the user something false. §6 is the behaviour, and HD-106 shipped copy that matches it. |
| Scrum → Kanban | an **ACTIVE** sprint exists | Confirm dialog: *"Sprint 7 is running. It keeps its issues and stays on the Backlog as a read-only section until you complete it."* **Allowed** — the section stays visible read-only with *Complete sprint* still offered to curators (§6). Never auto-complete, never auto-move issues, never leave a running sprint invisible. |
| Releases on → off | unreleased versions exist | Confirm naming the count; versions are kept, the rail item goes, existing fix/affects chips render read-only, and the settings page keeps a live "3 unreleased versions kept" notice with a link. |
| Releases on → off | none | No confirmation. |
| Estimation on → off | point values exist | Confirm: *"Existing story points are kept and still shown on issues; only the input is hidden."* |
| Any switch | archived project | **409** — archived projects are frozen (§4). |
| Any switch | concurrent double-submit | Last write wins on a scalar column; the response carries the resulting state and the SPA re-renders from it. No optimistic-lock round trip is warranted for a three-boolean preference. |
| Re-enabling anything | ever | Restores full function immediately — because nothing was destroyed. |
| API-level switch (script, integration) | any | **200, no confirmation, no 409** — confirmations are a UI affordance (Rule A). |

Also covered:

- **Empty / last-of-kind:** turning `I` off with zero sprints, or `R` off with zero versions, is a
  plain no-op switch. There is no "last sprint" concept to guard.
- **Stranded issues:** impossible by construction — an issue never loses a field value, and every
  value stays visible (Rule B).
- **Idempotency:** setting a capability to the value it already has is a no-op that still returns
  200 with the current state.

---

## 14. Acceptance criteria

Written so a **future** feature can be checked against the model without re-litigating it.

### 14.1 Model conformance checklist (apply to every new surface, forever)

- [ ] The surface asks `useProjectDelivery` (or reads `ProjectResponse.delivery` server-side) — it
      does **not** infer a path from the presence of data.
- [ ] Its **controls** are capability-gated; its **values** render whenever present, read-only when
      the capability is off (Rule B).
- [ ] No endpoint it calls returns a different status code depending on a capability (Rule A).
- [ ] If it introduces a capability's first entry point, that entry point is visible while the
      capability is off, and using it enables the capability with an explicit reversible notice
      (Rule C).
- [ ] If it is cross-project (Home, My work, search, notifications, palette), it imposes no
      path-specific vocabulary as structure (§6).
- [ ] It adds no property, env var or profile branch (§10).

### 14.2 Shippable criteria for the implementation slices

Model & API
- [ ] `GET`/`POST`/`PATCH` project responses carry `delivery` with the three capabilities and the
      derived `preset`; `preset` is rejected (400 or ignored per §11.3) if sent in a request.
- [ ] `PATCH` with `boardMode` alone still works; with `boardMode` and a disagreeing
      `delivery.board` → **400**.
- [ ] Changing delivery as project MANAGER → 200; as workspace OWNER/ADMIN who is not a project
      member → 200; as a plain project MEMBER → **403**; as a non-member → **404**; on an archived
      project → **409**.
- [ ] Preset derivation returns `KANBAN`/`SCRUM`/`RELEASES` for the three exact combinations and
      `CUSTOM` for all five others.

Migration
- [ ] V12 applies additively on a populated DB; Hibernate `validate` passes; no `CHAR`/PG ENUM.
- [ ] Every pre-existing project ends with `releases_enabled = TRUE` and
      `estimation_enabled = TRUE`.
- [ ] Every pre-existing project owning ≥1 sprint ends with `board_mode = 'SCRUM'`; no other
      project's `board_mode` changes.
- [ ] The migration changes no `projects.updated_at` (the `hamstrack.skip_updated_at` guard).

**The prod bug, closed by construction**
- [ ] On a brand-new Kanban project with zero sprints, a curator can create **and start** a first
      sprint **entirely from the Backlog**, without visiting Project settings.
- [ ] Doing so flips the project to `SCRUM` and the user is told, in the same dialog, that it is
      reversible.
- [ ] The same holds for versions from the Releases page with `releases` off.
- [ ] `grep` finds **no** remaining `openSprints.length > 0`, `sprintOptions.length > 0` or
      `versionOptions.length > 0` visibility conditions in the SPA.

Non-destructive switching
- [ ] Turning `I` off while a sprint is ACTIVE keeps the sprint running, keeps its section visible
      read-only, still offers *Complete sprint* to curators, and changes no `issues.sprint_id`.
- [ ] Turning `R` off keeps every version row and every issue-version link; fix/affects chips still
      render, read-only; the Releases URL still resolves.
- [ ] Turning `E` off keeps every `story_points` value and still renders it on issues that have one.
- [ ] Re-enabling any capability restores full function with no data loss and no user action.

Creation
- [ ] The create-project modal shows Kanban/Scrum as a radio pair plus Releases as a toggle, with
      the "you can change this later" line visible without hovering anything.
- [ ] Creating without touching the picker yields Kanban / releases off / estimation off.
- [ ] Choosing Scrum yields `board = SCRUM` **and** `estimation = true` in one request.

Search
- [ ] `sprint = "Sprint 7"`, `fixVersion = "2.4.0"` and `storyPoints >= 5` compile and return rows
      even when every visible project has the corresponding capability **off**.
- [ ] A saved filter written as `story_points = 5` runs and returns the same rows as
      `storyPoints = 5`.
- [ ] A workspace-scoped custom field keyed `story_points` still resolves to **itself**, not to the
      native column (the alias is a last-resort fallback, not a registry entry).
- [ ] `/schema` omits `sprint` from suggestions when no visible project has iterations on, and lists
      it again as soon as one does; the `SPRINT`/`VERSION` picklists only contain values from
      capability-on projects.
- [ ] No saved filter's stored text is modified by any migration.

Cross-project vocabulary
- [ ] Home and My work render no sprint/version/point vocabulary as structure, for any mix of
      project paths in the workspace.
- [ ] A Kanban-only workspace never renders the word "sprint" anywhere except the curator-facing
      enabling affordances and the settings blurb.

DC/Cloud
- [ ] `git diff` on the slices touches no `*.properties`, no compose file, no `.env.prod.example`,
      and adds no `@Profile`/`@ConditionalOnProperty`.

---

## 15. Proposed implementation split (file these as follow-up tickets — I have not created them)

Ordered; **S1 blocks everything**, then S2–S6 are largely parallel.

| # | Slice | Agent | Contents | Size |
|---|---|---|---|---|
| **S1** | Delivery capabilities — backend & migration | `backend-builder` | `V12` (+ both backfills), `Project` entity fields, `DeliveryResponse`/`DeliveryRequest`, preset derivation, `ProjectResponse`/`CreateProjectRequest`/`UpdateProjectRequest` wiring, the `boardMode` deprecation mirror + the disagreement 400, curator/archived gates, tests | M |
| **S2** | Capability plumbing + heuristic removal **(the bug fix)** | `frontend-builder` | `types.ts`, `useProjectDelivery`, and the replacement of all four presence heuristics across Backlog / IssueDetail / CreateIssueModal / BoardPage / NavRail, including Rule B read-only rendering | M |
| **S3** | Bootstrap affordances | `frontend-builder` | Backlog "Plan in sprints" (curator, always visible) with enable-on-first-use; Releases page off-state + enable-on-first-use; board empty state gains *Create & start a sprint* and *Show all issues*; read-only open-sprint sections when `I` is off | M |
| **S4** | Project creation picker | `frontend-builder` | `CreateProjectModal` picker block, defaults, reassurance copy, `localStorage` last-preset nudge (open question 1) | S |
| **S5** | Project settings → Delivery | `frontend-builder` | `ProjectDeliverySettingsPage` replacing the Board tab, tab rename + `/settings/board` redirect, switch confirmations, live "kept data" notices | M |
| **S6** | Search compatibility | `backend-builder` | retired-key alias resolution (`story_points` → `storyPoints`) placed **after** custom-field resolution; capability-aware `/schema` suggestions and value picklists; regression tests for saved filters | S |
| **S7** | Docs | `api-docs-sync` + doc pass | `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, `docs/project-state.md`; one new `CLAUDE.md` hot rule: *"project delivery capabilities are declared on `projects`, never inferred from data presence; capabilities gate UI only, never the API"* | S |

Review gates per the pipeline: `tenancy-reviewer` on S1/S6 · `migration-reviewer` on S1 ·
`security-officer` on S1 (a widened-write surface on `projects`) · `dc-cloud-guard` on S1 to
**confirm no config was added** · `api-docs-sync` on S1/S6 · `test-runner` on all.

---

## 16. Divergences from the owner's stated vision

| # | Owner's vision | Recommendation | Trade-off in one sentence |
|---|---|---|---|
| **D1** | "Kanban, Scrum **or** Releases" — one three-way choice | Two-way board choice (Kanban/Scrum) **plus** an independent Releases toggle, presented as three named cards with Releases marked "works with either" | The three-way enum is simpler to explain but makes "Scrum + releases" unrepresentable and would need a schema change and a re-education the first time someone asks for it; the split keeps the same three names and the same up-front question while staying true to the data. |
| **D2** | (implicit) one field | Three stored capabilities + a derived preset label | Slightly more surface than one column, but it is the only shape where "what kind of project is this?" has a single answer *and* unusual combinations remain legal; the preset label gives back the one-word answer for display. |
| **D3** | (not addressed) story points | Gated by a new `estimation` capability rather than staying unconditional | Removes real noise from Kanban create forms, but it is the one capability whose only job is hiding an input — §8 carries a complete one-paragraph fallback if the owner prefers unconditional. |
| **D4** | (not addressed) defaults for new projects | Lean by default: Kanban, releases **off**, estimation **off** | Cleaner new projects and a meaningful up-front question, at the cost of Releases being less discoverable for users who click straight past the picker — this is the highest-risk assumption (§17) and is a one-line flip. |
| **D5** | (not addressed) existing projects | Existing projects keep **everything** (releases on, estimation on, `SCRUM` if they own a sprint), i.e. the opposite of D4 | Two different default policies to explain, but "an upgrade never takes away what you had" is worth more than internal consistency. |

---

## 17. Risks & the highest-risk assumption

> **Highest-risk assumption: that new projects should default to the leanest capability set (D4).**
> If it is wrong, the failure is quiet and cumulative — users who accept the picker default never
> discover Releases, and the release reads as "features disappeared" rather than "features became
> deliberate". It is recommended because an explicitly-asked, always-reversible question earns the
> right to a minimal default, and because §5.3 guarantees that every off capability is one visible
> click away from the page where it belongs. It is also the cheapest thing in this document to
> reverse: change the picker's initial state and one server-side default constant. If the owner
> prefers safety over leanness, flip `releases` to on-by-default for new projects and leave the rest
> of the model untouched.

Secondary risks:

1. **Rule A (capabilities never gate the API) is load-bearing and could be misread as a gap.**
   Reviewers will see "hidden control, open endpoint" and may file it as a security finding. It is
   not: the underlying endpoints have always been membership- and role-gated, and a capability is a
   preference, not a permission. If a genuine requirement for "this project may not have sprints at
   all" ever appears, it needs a *separate* permission layer — never a widened meaning for these
   booleans.
2. **The retired-key alias precedence.** Putting `story_points` in `FieldRegistry` would silently
   shadow tenant custom fields with that key in every workspace. The fallback-after-custom ordering
   in §9.2 is not optional, and needs a test that proves a tenant field wins.
3. **The `estimation` capability's blast radius** — it touches the create modal, the issue-detail
   grid, both point-sum surfaces and the board subtotals. If it is cut (§8 fallback), cut it before
   S2 starts, not after.
4. **Read-only value rendering (Rule B) is easy to skip.** The tempting implementation is a plain
   `if (capability) render(...)`, which silently hides real data and re-creates the "where did my
   sprint go?" confusion in a new place. Every §6 row marked `∨ value` needs its own test.
5. **Preset drift.** If a future capability is added without updating the preset table, most projects
   become `CUSTOM` overnight. The derivation must live in exactly one server-side method with the
   table in §2.3 as its comment.

---

## 18. Open questions (each with a recommended default — none blocks implementation)

1. **Workspace-level default preset?** → *No stored default.* Pre-select the preset this user last
   chose in this workspace, in `localStorage` only, explicitly non-authoritative. A real workspace
   default adds a second source of truth and an inheritance question for two saved clicks.
2. **New-project defaults: lean or status-quo?** → *Lean* (Kanban, releases off, estimation off).
   See §17 — this is the flip most likely to be overruled.
3. **Does the Scrum board with no active sprint keep its empty state, or fall back to a continuous
   board?** → *Keep the empty state*, but make it actionable (*Create & start a sprint* for curators,
   *Show all issues* as a local view toggle for everyone). Redefining board semantics is scope creep;
   the dead end is what needed fixing, not the scoping.
4. **Rename `board_mode` → `iterations_enabled`?** → *No.* The domain vocabulary moves; the column,
   the enum and the deprecated DTO field stay. A rename buys tidiness and costs a migration plus a
   coordinated frontend/API change.
5. **Should `delivery.preset` be settable in requests as a shorthand?** → *No.* Clients send the
   three capabilities; one source of truth. Revisit only if a public API consumer asks.
6. **Persist an audit record of delivery changes?** → *Not in these slices.* There is no
   project-level history table today, and the settings page derives its "kept data" notices live from
   the data itself, which is what users actually need. If an audit trail is wanted, it should be a
   general project-history feature, not a one-off for three booleans.
7. **Should `estimation` be visible on the creation picker?** → *No.* It follows the preset (Scrum →
   on) and is adjustable in settings; a third question at create time costs more than it informs.
8. **Should the demo project change?** → *No.* It is seeded `SCRUM` with sprints and points, so it
   maps to `board = SCRUM, estimation = on, releases = ?` — recommend seeding it with **releases on**
   as well (it is the showcase project, and it should demonstrate that Scrum and Releases coexist,
   which is the whole point of D1).
