# Agile: sprints, Scrum board & backlog ranking — Implementation Spec

Release **0.13.0** · Epic **HD-4** · Stories **HD-22** (backend) → **HD-23** (backlog + planning DnD)
→ **HD-27** (Scrum board)
Status: **draft 2026-08-16** — written for `backend-builder` / `frontend-builder` / `test-runner`.
Author: systems-analyst. Autopilot policy: build may proceed; owner vetoes after.

Structural precedent followed throughout: `docs/design/labels-components-versions-proposal.md`
(HD-6 / V8–V10) — a project-scoped child entity with a lifecycle, a curator permission tier, a
denormalized `workspace_id` with composite FKs, conditional bulk UPDATEs as lifecycle arbiters, and
batched (never N+1) page assembly. Where this document is silent, that one is the fallback.

Companion docs to update on ship: `src/main/frontend/public/openapi.yaml`, `docs/api-cloud.md`,
`docs/api-dc.md`, `docs/project-state.md`, `docs/self-hosting.md` (config table),
`.env.prod.example`. **Not** `README.md` (no env table — it delegates to the self-hosting guide).

---

## 1. Problem & goal

Hamstrack has exactly one way to work: a Kanban board over the whole project, ordered by creation
sequence, plus a flat paginated backlog table with no ordering the team controls. There is no
iteration concept, so there is no way to say "this is what we committed to for the next two weeks",
no way to rank what comes next, and no way to close a time-box and see what actually got done. That
is the single largest gap between Hamstrack and the tool teams are migrating from, and it blocks the
entire Scrum audience.

**Goal:** sprints with a real start/complete lifecycle, a backlog the team ranks by dragging, and a
board that can be scoped to the active sprint — so a team can plan an iteration, work it, and close
it with an honest done-vs-carried-over report.

**Success looks like:** a project curator creates "Sprint 7", the team drags eight items out of the
ranked backlog into it, the curator starts it, the board now shows only those eight in workflow
columns with a header reading *goal · 6 days left · 21 pts*, and on Friday "Complete sprint" reports
*17 of 21 points done, 2 issues carried over to Sprint 8*.

**Non-copy statement.** Jira Software and Azure DevOps are the capability benchmark, not the
implementation. Deliberate divergences: **rank is a single project-wide integer order that the board
and the backlog share** (Jira keeps a global LexoRank plus per-board sub-orders that drift apart);
Kanban vs Scrum is **one boolean-ish project attribute**, not two board types with two configuration
trees; there is **no sprint re-open, no parallel sprints, and no board-scoped "sprint" object
separate from the project**; completion is **one dialog with one decision** (backlog or a named
future sprint), not Jira's multi-step sprint report wizard; story points become a **native issue
attribute**, not a configurable estimation-statistic scheme. No Jira naming, screens, URL shapes or
scheme mechanics are reproduced.

---

## 2. Scope

### In scope

| Slice | Ships |
|---|---|
| **HD-22** | `sprints` table + `issues.sprint_id`; sprint CRUD, start, complete (+ preview), delete; bulk assign/remove issues; **backlog rank** on `issues.position` with a rank/move endpoint; `projects.board_mode`; **`issues.story_points`** as a native column (replacing the placeholder system field); the `GET …/backlog` planning aggregate; `?sprintId=` on the issue list; HQL `sprint` + `storyPoints`; demo seeding |
| **HD-23** | `BacklogPage` rewritten: sprint sections above a rank-ordered backlog, native drag-and-drop for reorder and for moving between sections, per-section point sums, create/start/complete sprint controls, keyboard/menu fallback for every drag |
| **HD-27** | `BoardPage` Scrum mode: scoped to the active sprint, sprint header (name, goal, days remaining, per-column point totals), Complete-sprint action, empty state; project-settings **Board** tab with the Kanban/Scrum switch |

HD-23 and HD-27 both depend on HD-22 and are independent of each other; they can be built in
parallel against the pinned contract in §7.

### Out of scope / non-goals

- **Sprint reports, burndown/burnup charts, velocity.** Reports remain the `SOON` rail item; the
  completion *result* (done vs carried over) is returned by the API and shown in the dialog, but
  nothing is persisted as a report artifact.
- **Team capacity in hours / per-person capacity.** "Capacity" in HD-4 is served by the story-point
  sum only. See open question 6.
- **Parallel active sprints, cross-project sprints, shared "boards" as first-class objects.** One
  active sprint per project, sprints belong to exactly one project.
- **Re-opening a completed sprint** (§4.1) and **sprint templates / auto-scheduled cadence**.
- **Epic swimlanes or hierarchy grouping in the backlog.** The ranked list is flat; a child renders
  as an ordinary row with the existing `ParentChip`.
- **Automatic sprint propagation from parent to child issues.** Each issue carries its own
  `sprint_id`.
- **Multi-select drag** in the backlog UI (the bulk *endpoint* exists and the row menu uses it; only
  the DnD gesture is single-issue).
- **Sprint-scoped permissions or notifications** ("notify on sprint start").
- **Reordering inside a board column by drag** (the board's vertical order follows the shared rank;
  dragging a card between columns keeps its current behaviour = status change only).

---

## 3. Cross-cutting decisions

### 3.1 Tenancy — non-negotiable

Every endpoint lives under `/api/workspaces/{workspaceId}/projects/{projectId}/…` and resolves
through `WorkspaceAccessService.requireProjectMember` (reads, issue-level writes) or
`ScopeResolver.requireProjectCurator` (sprint lifecycle writes). A missing workspace, a missing
project and a non-member all yield **404** (`WorkspaceNotFoundException` / `ProjectNotFoundException`)
— never 403. **403** is reserved for a member who lacks the curation role
(`InsufficientProjectRoleException`).

- No repository method takes a bare id: sprint lookups are `findByIdAndProject`, issue lookups are
  `findByIdAndProject` / `findByProjectAndNumber`.
- A sprint id inside an issue payload (`sprintId` on create/update/rank) that is unknown or belongs
  to another project is **422 "Unknown sprint"** — an invalid field value in a request the caller is
  entitled to make; it leaks nothing. Direct `GET /sprints/{id}` of a foreign id is **404**.
- `sprints` carries a denormalized `workspace_id` (like `versions`/`components`), and
  `issues.sprint_id` references it through the **composite FK** `(sprint_id, workspace_id) →
  sprints (id, workspace_id)` — the HD-6 §3.8 owner decision, binding here too. A cross-tenant
  sprint assignment is therefore *unrepresentable*, not merely rejected in Java.
  `issues_id_workspace_id_key` already exists (added once in V8) — **do not re-add it**.
- The composite FK proves *same workspace*, not *same project*. "Same project" stays
  service-enforced through `findByIdAndProject`; every write path must use it.

### 3.2 Permissions

| Action | Required |
|---|---|
| Read sprints, backlog view, sprint issue lists | any **project member** (i.e. a workspace member for whom the project resolves) |
| Create / rename / edit goal & dates / delete a sprint | **project curator** = project `MANAGER` **or** workspace `OWNER`/`ADMIN` (`ScopeResolver.requireProjectCurator`) |
| **Start** / **Complete** a sprint | project curator |
| Assign an issue to a sprint, remove it, **drag-rank** it | anyone who can edit the issue — workspace member + project not archived (the same gate as `assigneeId`/`labelIds` today) |
| Set `boardMode` (Kanban/Scrum) | project curator (see the note below) |
| Set `storyPoints` on an issue | anyone who can edit the issue |

**Deliberate, flagged change:** `ProjectService.update` is `MANAGER`-only today, while the SPA's
`ProjectSettingsArea` already admits a workspace `OWNER`/`ADMIN` (it checks exactly the curator
predicate). Since `boardMode` is added to that same PATCH, `ProjectService.update` **switches from
`requireRole(MANAGER)` to `ScopeResolver.requireProjectCurator`** — aligning the backend with the UI
and with every other project-content write since HD-6. `archive`/`unarchive`/member management stay
`MANAGER`-only. This widens who may rename a project; `security-officer` must sign it off.

Rationale for the sprint split: starting and completing a sprint are commitments with reporting
consequences and must not be a stray click by any member; putting *items into* a sprint is ordinary
planning work the whole team does at a planning meeting. Requiring MANAGER to drag would make the
backlog read-only for most of the team and kill the feature.

### 3.3 The ranking scheme — decision and justification

**Decision: the rank is `issues.position` (BIGINT), a single project-wide order, written only by the
server from neighbour anchors, spaced by `RANK_STEP = 2^26 (67 108 864)`, with a bounded whole-project
rebalance when a gap is exhausted. No new `backlog_rank` column, no LexoRank string, no fractional
type.**

Why not the alternatives:

- **LexoRank (base-36 string).** No precision cliff, but in PostgreSQL a text rank is only correct
  under a byte-ordered collation: with the cluster default (`en_US.UTF-8`) `'a1' < 'A2'` is
  locale-dependent and *disagrees with Java's `String.compareTo`*, so the server and the DB can order
  the same two rows differently. The fix (`VARCHAR … COLLATE "C"` plus a matching index plus a
  matching comparator in Java) is three coupled invariants a future migration can silently break, in
  exchange for avoiding a rebalance that our step size already makes rare. The midpoint-string
  generator is also genuinely fiddly (between `"a"` and `"ab"`, at the alphabet bounds, on an empty
  list) — error-prone code in the hot path of every drag.
- **Fractional double.** Dies after ~50 successive midpoints in one gap (2^53) with *silent* ties,
  which is the worst failure mode available.
- **Unbounded `NUMERIC`.** Correct and midpoint-trivial, but it is a new column type in this schema
  and its precision/scale interaction with Hibernate's `BigDecimal` mapping under
  `ddl-auto=validate` is an unnecessary unknown.
- **A new `issues.backlog_rank` column (as HD-22 names it).** Rejected because `issues.position`
  **already is the order key** of every issue list in the codebase —
  `IssueRepository.findByProjectFilteredCapped`, `findByProjectFilteredPaged` and `findByParent` all
  end in `ORDER BY i.position ASC, i.createdAt DESC`, and `IssueService.create` seeds it from the
  issue sequence. A second rank column would leave the board ordered by the old one and the backlog
  by the new one — two contradicting orders and a guaranteed "I ranked it but the board didn't
  move" bug. **This is a conscious deviation from the ticket's column name; the behaviour it asks
  for is delivered in full.**

Mechanics:

1. **Spacing.** V11 rescales every existing row (`position = position * 67108864`) and
   `IssueService.create` changes from `issue.setPosition(seq)` to
   `issue.setPosition(issueRepository.maxPosition(project) + RANK_STEP)` — new issues land at the
   **bottom** of the backlog, which is what "just file it" means, and the `idx_issues_project_position`
   index already serves the `MAX`. Two concurrent creates may receive the same position; that is
   harmless (the `createdAt DESC` tie-break keeps the order stable) and the first drag separates them.
2. **Placement is computed server-side from neighbours, never sent by the client.** The client sends
   `afterIssueId` and/or `beforeIssueId`; the server fills in the missing neighbour with one indexed
   query over the *target section* and takes the midpoint. Both absent ⇒ append to the section end.
   The client can therefore never invent, corrupt or leak a rank value, and `position` is **not**
   exposed in `IssueResponse` (§7).
3. **Concurrent drags.** Two users dropping different issues into the same gap simply get two
   different midpoints (each is computed under the row's own transaction from freshly-read
   neighbours), so both succeed — the desirable outcome for a planning meeting. If the *anchors
   themselves* have become inconsistent (`after.position >= before.position`, i.e. the caller's view
   is stale), the answer is **409 "the list changed — refresh"**, not a 500 and not a silent
   arbitrary placement. `version` in the rank request is **optional**: when present it is checked
   (409 on stale), when absent the move applies. Ranking is a positional, last-drag-wins operation;
   forcing an optimistic-lock round trip on every drop would produce a 409 storm during planning.
4. **Rebalance.** When `before.position - after.position <= 1` the gap is exhausted (≈26 consecutive
   midpoints into the *same* gap). The service then renumbers the whole project in **one native
   statement** using `row_number() OVER (ORDER BY position, created_at DESC) * RANK_STEP`,
   `@Modifying(clearAutomatically = true, flushAutomatically = true)`, **before any entity mutation
   in that transaction** (the documented "clearAutomatically wipes pending inserts" trap), then
   re-reads the neighbours and retries the midpoint exactly once.
   - The rebalance is **native SQL and does not touch `version`**, so it cannot invalidate anybody's
     optimistic lock on an unrelated edit.
   - It must also not touch `updated_at`, or one drag would mark every issue in the project as
     recently updated and poison Home/My work/`ORDER BY updated`. V11 therefore replaces the shared
     `set_updated_at()` trigger function with a version guarded by a custom GUC:

     ```sql
     IF coalesce(current_setting('hamstrack.skip_updated_at', true), '') <> 'on' THEN
         NEW.updated_at = NOW();
     END IF;
     ```

     Default behaviour is byte-identical to today; the rebalance (and the V11 backfills) run
     `SET LOCAL hamstrack.skip_updated_at = 'on'` first, which expires with the transaction and
     cannot leak back into the connection pool. *Considered and rejected:* a separate
     `issue_ranks` side table (no trigger change needed, but it adds a join to the hottest query in
     the product and an extra insert per issue).
   - Cost note: the rebalance is `O(issues in project)` in one statement. At our scale (boards are
     capped at 500 for display; real projects are ≤ tens of thousands) this is milliseconds and
     rare. Past ~200k issues in one project it should become a neighbourhood-scoped renumber —
     recorded, not built.
5. **Sections share one rank space.** `sprint_id` is a *filter*, not a separate order: a section
   renders `ORDER BY position` over its own rows. Ranks therefore interleave across sections, which
   is intentional — moving an issue out of a sprint keeps its relative place in the backlog.

### 3.4 Story points — decision and justification

**Decision: `story_points` becomes a native nullable column on `issues` (`NUMERIC(5,2)`), and the
V1-seeded global system custom field `story_points` is archived by V11 with its existing values
migrated into the column.**

There *is* an existing custom field — `field_defs.key = 'story_points'`, NUMBER, `{"min":0,"max":100}`,
promoted to `is_system` by V3 — so this spec is not inventing a concept, it is **promoting** one, on
the precedent HD-6 set for `labels` / `components` / `fix_version` (§3.4 of that proposal: archive the
placeholder, never delete; keep old values readable).

Why promote rather than read the custom field:

- The custom field only exists on a project whose bound field set contains it, and the
  **system-default field set is "No fields"** (V1). On a stock install *no project* offers story
  points, so every sprint point sum would render empty and HD-4's "story-point sum" acceptance
  criterion would ship dead.
- Summing it means casting JSONB per row (`jsonb_scalar_numeric`) through
  `issue_field_values`, per project field-id resolution, and a section-stats query that cannot use a
  plain index. As a column it is `SUM(i.storyPoints)` in JPQL, sortable, and free on the existing
  page fetch.
- V3's own header states the rule this follows: *native issue attributes stay native columns and are
  NOT `field_defs` rows.* Points behave exactly like `dueDate`.

Backfill is lossless and unambiguous (the stored shape is a bare JSON number — see `FieldType`), so
unlike HD-6's empty option lists there **is** real data to migrate, and V11 migrates it.

Rules: `0 ≤ storyPoints ≤ 999`, at most 2 decimals (`0.5`, `1.5`, `13` all valid) → **422** otherwise;
`null` = unestimated (never `0` — "we didn't estimate it" and "it's free" are different statements,
and the section stats report `unestimatedCount` separately). Changing it writes an
`issue_history` row with `field = "storyPoints"`.

> **Fallback if the owner vetoes the promotion** (one paragraph so the switch is cheap): drop the
> column, the backfill and the field-def archival from V11; `SprintService` resolves the effective
> `story_points` field id per project via `FieldValueService.fields(project)` (honouring scope
> precedence), sums it with a native `jsonb_typeof(value)='number'`-guarded query, and every
> `points`/`donePoints` in §7 becomes `null` when the project's field set does not offer the field.
> The SPA then hides all point UI and shows curators a hint linking to Project settings → Fields.
> Everything else in this spec is unchanged.

### 3.5 Board mode

`projects.board_mode VARCHAR(10) NOT NULL DEFAULT 'KANBAN'` — `KANBAN | SCRUM`, validated by the Java
enum `BoardMode` (never a PG ENUM). Read on `ProjectResponse` (already fetched by `NavRail` and both
settings areas, so no new request on the hot path), written through `PATCH …/projects/{projectId}`.

It is a **presentation switch, not a permission**: the sprint API works identically in both modes
(a Kanban team may still plan iterations). Only two things change:

- **Board:** `SCRUM` scopes the board to the active sprint and shows the sprint header; `KANBAN` is
  today's behaviour, unchanged.
- **Backlog:** sprint sections render when `boardMode === 'SCRUM'` **or** the project already has at
  least one non-completed sprint. A pure-Kanban project that never created a sprint therefore just
  gets a ranked backlog list, with no Scrum vocabulary on screen.

### 3.6 Sprints are content, not bound taxonomy

Like components and versions (HD-6 §3.2): a sprint is owned by exactly one project, is edited by
working users rather than admins, has a lifecycle no bound set models, and is worthless to another
project. **`ProjectConfigService` gains no methods and `ProjectConfigResponse` gains no field.**
Sprints have their own endpoints and their own SPA query keys, so a sprint start does not invalidate
the config every board render depends on.

### 3.7 Caps, properties and DC/Cloud

Zero behavioural fork: everything here is workspace/project-scoped and membership-guarded, touching
no storage/email/auth/billing surface. No profile-conditional beans, no
`application-{dc,cloud}.properties` divergence, **no feature flag** (a kill switch would create a
second, untested code path — the same stance HD-6 took).

New `common.config.AgileProperties` (`@Validated @ConfigurationProperties("app.agile")`, a record
with `@DefaultValue` + `@Min`/`@Max`, mirroring `ClassificationProperties`). An out-of-range value
**aborts startup** — never clamps.

| Property | Env | Default | Enforced as |
|---|---|---|---|
| `app.agile.section-max-issues` | `AGILE_SECTION_MAX_ISSUES` | `300` (1…2000) | per-section cap in `GET …/backlog`; `truncated` + `totalAvailable` per section (the HD-79 pattern) |
| `app.agile.max-open-sprints-per-project` | `AGILE_MAX_OPEN_SPRINTS` | `20` (1…100) | 422 on `POST /sprints` when FUTURE+ACTIVE already at the cap |
| `app.agile.default-sprint-length-days` | `AGILE_DEFAULT_SPRINT_LENGTH_DAYS` | `14` (1…90) | `endAt` default at start |
| `app.agile.max-issues-per-bulk-move` | `AGILE_MAX_BULK_MOVE` | `100` (1…500) | 400 on an oversized `issueIds` |

Worst-case planning-view size is bounded by `(max-open-sprints + 1) × section-max-issues` = 6300 rows;
typical is a few hundred. Wiring checklist (`dc-cloud-guard` checks all four):
`application.properties` → `docker-compose.prod.yml` needs **no** change (the `app` service pulls the
whole operator `.env` via `env_file`) → `.env.prod.example` (commented, with the default) →
`docs/self-hosting.md` config table → the `docs/api-dc.md` "Operator settings that affect the API"
table (all four turn a valid-looking payload into a 4xx or truncate a response).

---

## 4. Backend — HD-22

### 4.1 Sprint state machine

```
            POST /sprints                POST /{id}/start            POST /{id}/complete
   (none) ─────────────────▶  FUTURE  ─────────────────────▶  ACTIVE ────────────────────▶ COMPLETED
                                │  ▲                              │                              │
                                │  └── (no transition back) ──────┘                              │
                                │                                                                │
                                └──── DELETE (curator, always)          DELETE (409 — complete    DELETE (curator,
                                                                        or delete after)          ?force=true if it
                                                                                                  still holds issues)
```

- **`FUTURE`** — planning bucket. Any number per project (bounded by
  `max-open-sprints-per-project`). `startAt`/`endAt` may be pre-filled as a plan.
- **`ACTIVE`** — **at most one per project**, enforced in the DB by a partial unique index
  (`sprints_one_active_per_project_uk`), not only in Java. `startAt` is non-null.
- **`COMPLETED`** — terminal. `completedAt` non-null.
- **Re-open is NOT allowed.** A completion is a reported event (done vs carried-over counts were
  handed to the user and unfinished issues were already moved elsewhere); re-opening would
  invalidate that report, re-open the one-active race, and require an inverse "un-move". The
  recovery path is: create a new sprint and move the issues back — two clicks, no ambiguity. Matches
  both reference products. Recorded as open question 1 with this as the recommended default.
- Both transitions are performed by a **conditional bulk UPDATE checked on its affected-row count**
  (`UPDATE … WHERE id = ? AND project_id = ? AND state = 'FUTURE'`), never a read-then-write —
  exactly `VersionService.release`'s trap-T1 pattern. Zero rows ⇒ **409**. Both use
  `@Modifying(clearAutomatically = true)` and re-read the sprint afterwards; the returned entity from
  before the update is detached and must only be used as a query parameter.
- If two *different* FUTURE sprints are started concurrently, the conditional update lets both
  through and the partial unique index arbitrates: the loser's
  `DataIntegrityViolationException` on `sprints_one_active_per_project_uk` is translated to
  **409 "Another sprint is already active"** (constraint-name check only, `isNameConflict`-style
  hygiene — any other constraint keeps its 500).

### 4.2 Data model — `V11__sprints.sql`

Chain currently ends at `V10__versions.sql`; **the next free number is V11**. Purely additive except
the two documented data rewrites (position rescale, story-point backfill) and the guarded trigger
function. `VARCHAR` throughout — no `CHAR(n)`, no `CREATE TYPE … AS ENUM`. UUID PKs generated by the
app (`@UuidGenerator(TIME)`); `created_at`/`updated_at` carry `DEFAULT NOW()` + the shared trigger as
a raw-SQL safety net while entities use `@CreatedDate`/`@LastModifiedDate`.

```sql
-- 0) Guarded shared trigger (§3.3.4). Default behaviour is unchanged; a transaction
--    may opt out with SET LOCAL hamstrack.skip_updated_at = 'on' for bulk rank/backfill
--    rewrites that must not look like edits.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    IF coalesce(current_setting('hamstrack.skip_updated_at', true), '') <> 'on' THEN
        NEW.updated_at = NOW();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Flyway wraps each migration in a transaction, so SET LOCAL is scoped to this
-- migration and cannot leak into the pooled connection.
SET LOCAL hamstrack.skip_updated_at = 'on';

-- 1) Sprints
CREATE TABLE sprints (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id   UUID         NOT NULL REFERENCES projects(id)   ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL,
    goal         VARCHAR(500),
    -- 'FUTURE' | 'ACTIVE' | 'COMPLETED' — validated by the Java enum SprintState.
    -- No domain CHECK: the codebase validates enum-ish VARCHARs in Java (status.category,
    -- link_type) and a CHECK would turn a future state into a schema migration.
    state        VARCHAR(10)  NOT NULL DEFAULT 'FUTURE',
    sequence     INTEGER      NOT NULL,          -- 1-based, per project; drives default names + order
    start_at     TIMESTAMPTZ,
    end_at       TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Cross-column invariants ARE enforced in the DB (the versions_released_ck precedent):
    -- both write paths are conditional bulk UPDATEs that bypass entity-level checks.
    CONSTRAINT sprints_dates_ck     CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),
    CONSTRAINT sprints_active_ck    CHECK (state <> 'ACTIVE' OR start_at IS NOT NULL),
    CONSTRAINT sprints_completed_ck CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL)),
    -- Referenced by the composite FK on issues below (§3.1). PG requires a UNIQUE on the
    -- exact referenced column list.
    CONSTRAINT sprints_id_workspace_id_key UNIQUE (id, workspace_id),
    CONSTRAINT sprints_project_id_sequence_key UNIQUE (project_id, sequence)
);

CREATE TRIGGER trg_sprints_updated_at
    BEFORE UPDATE ON sprints
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- THE invariant of HD-22: at most one ACTIVE sprint per project, enforced by the DB.
CREATE UNIQUE INDEX sprints_one_active_per_project_uk
    ON sprints (project_id) WHERE state = 'ACTIVE';
-- Case-insensitive name uniqueness inside one project (a plain UNIQUE can't lower()).
-- COMPLETED sprints keep their slot, exactly like archived labels/components/versions.
CREATE UNIQUE INDEX sprints_project_name_uk ON sprints (project_id, lower(name));
-- Serves the list (state filter + sequence order) and the backlog view's section fetch.
CREATE INDEX idx_sprints_project_state ON sprints (project_id, state, sequence);
-- Needed: the ON DELETE CASCADE from workspaces probes by workspace_id, and
-- sprints_id_workspace_id_key leads on id.
CREATE INDEX idx_sprints_workspace ON sprints (workspace_id);

-- 2) Issue → sprint. COMPOSITE FK (§3.1): a cross-tenant assignment is unrepresentable.
--    ON DELETE SET NULL names the column list explicitly (PG 15+; we require PG 16) — a
--    bare SET NULL would try to null the NOT NULL issues.workspace_id too and the cascade
--    would fail at delete time. issues_id_workspace_id_key already exists (V8) — do NOT re-add.
ALTER TABLE issues ADD COLUMN sprint_id UUID;
ALTER TABLE issues ADD CONSTRAINT issues_sprint_fk
    FOREIGN KEY (sprint_id, workspace_id) REFERENCES sprints (id, workspace_id)
    ON DELETE SET NULL (sprint_id);
-- (sprint_id, position) serves both the section fetch and its rank ordering.
CREATE INDEX idx_issues_sprint ON issues (sprint_id, position) WHERE sprint_id IS NOT NULL;

-- 3) Story points as a native attribute (§3.4)
ALTER TABLE issues ADD COLUMN story_points NUMERIC(5,2);
ALTER TABLE issues ADD CONSTRAINT issues_story_points_ck
    CHECK (story_points IS NULL OR (story_points >= 0 AND story_points <= 999));

UPDATE issues i
   SET story_points = round(LEAST(999, GREATEST(0, (v.value #>> '{}')::numeric)), 2)
  FROM issue_field_values v
  JOIN field_defs f ON f.id = v.field_id
 WHERE v.issue_id = i.id
   AND f.key = 'story_points'
   AND f.scope_workspace_id IS NULL AND f.scope_project_id IS NULL
   AND jsonb_typeof(v.value) = 'number';

-- Retire the placeholder: archive, never delete (HD-6 §3.4). Existing values keep
-- rendering on issues that carry them; the field leaves pickers, ProjectConfigResponse.fields
-- and HQL name resolution, freeing the `storyPoints` key for the native field.
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'story_points' AND archived_at IS NULL;

-- Same treatment for the never-usable 'sprint' SELECT placeholder seeded by V3.
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'sprint' AND archived_at IS NULL;

-- 4) Board mode (§3.5)
ALTER TABLE projects ADD COLUMN board_mode VARCHAR(10) NOT NULL DEFAULT 'KANBAN';

-- 5) Rank spacing (§3.3). Existing positions are 1..N (seeded from the issue sequence),
--    i.e. zero room between neighbours; rescaling gives every pair 2^26 of headroom.
--    Max realistic position afterwards is ~1e7 * 6.7e7 — far inside BIGINT.
UPDATE issues SET position = position * 67108864;
```

**Deployment note.** `ADD CONSTRAINT issues_sprint_fk` on the populated `issues` table runs a
validation scan under `SHARE ROW EXCLUSIVE`, and the `UPDATE issues SET position = …` rewrites every
row. Both are free today (single app container, migrations run inside the deploy window with no
concurrent writer). The moment Cloud moves to a rolling deploy this must become
`ADD CONSTRAINT … NOT VALID` + a later `VALIDATE CONSTRAINT`, in a **new** migration — never a
retrofit into an applied one (HD-6 §3.8 recorded the same for V8/V9).

### 4.3 Entities

`com.hamstrack.issue.entity` (sprints attach to issues; same package as `Version`/`Component`):

- **`SprintState`** — `enum { FUTURE, ACTIVE, COMPLETED }`.
- **`Sprint extends BaseEntity`** — `@ManyToOne(LAZY) Workspace workspace`,
  `@ManyToOne(LAZY) Project project`, `String name`, `String goal`,
  `@Enumerated(STRING) @Column(length = 10, updatable = false) SprintState state`, `int sequence`,
  `OffsetDateTime startAt`, `OffsetDateTime endAt`,
  `@Column(updatable = false) OffsetDateTime completedAt`, `@ManyToOne(LAZY) User createdBy`.
  - `state` and `completedAt` are **`updatable = false`** on purpose: only the conditional bulk
    UPDATEs write them, so a stale managed copy flushed later can never un-complete a sprint (the
    `projects.issue_seq` / `versions.released` rule).
  - `startAt`/`endAt` **are** entity-writable (PATCH may re-plan them). `start` writes them inside
    its conditional UPDATE and then `clearAutomatically`-clears + re-reads, so no stale copy
    survives to clobber them.
- **`Issue`** gains `@ManyToOne(LAZY) @JoinColumn(name = "sprint_id") Sprint sprint` and
  `@Column(name = "story_points", precision = 5, scale = 2) BigDecimal storyPoints`.
  `Issue.position` keeps its mapping and gains a javadoc line: *"the project-wide backlog/board rank
  (§3.3) — written only by `IssueRankService` and by create; never by the client."*
- **`Project`** gains `@Enumerated(STRING) @Column(name = "board_mode", length = 10, nullable = false)
  BoardMode boardMode = BoardMode.KANBAN` (`com.hamstrack.project.entity.BoardMode { KANBAN, SCRUM }`).

> **Trap to respect (the HD-31 `ON DELETE SET NULL` class):** deleting a sprint clears
> `issues.sprint_id` *behind JPA's back*. `SprintService.delete` must therefore run an explicit
> `@Modifying(clearAutomatically = true) update Issue i set i.sprint = null where i.sprint = :s`
> **before** deleting the row (the FK stays as the safety net for out-of-band deletes).
> `clearAutomatically` is safe there because that method has no other pending inserts — do not copy
> it into one that has.

### 4.4 API surface

All paths are prefixed `/api/workspaces/{workspaceId}/projects/{projectId}`.

```
GET    /sprints?state=ACTIVE&state=FUTURE&page=&size=                200  PageResponse<SprintResponse>
POST   /sprints                                                      201  SprintResponse
GET    /sprints/{sprintId}                                           200  SprintResponse
PATCH  /sprints/{sprintId}                                           200  SprintResponse
POST   /sprints/{sprintId}/start                                     200  SprintResponse
GET    /sprints/{sprintId}/completion-preview                        200  SprintCompletionPreview
POST   /sprints/{sprintId}/complete                                  200  SprintCompletionResult
POST   /sprints/{sprintId}/issues                                    200  SprintResponse
DELETE /sprints/{sprintId}/issues/{issueId}                          204  —
DELETE /sprints/{sprintId}?force=false                               204  —
GET    /backlog?<issue filters>&includeDone=false                    200  BacklogViewResponse
POST   /issues/{number}/rank                                         200  IssueResponse
GET    /issues?sprintId=<uuid>|&noSprint=true&<existing filters>     200  BoardIssuesResponse | PageResponse<IssueResponse>
PATCH  /projects/{projectId}   (existing)  + boardMode               200  ProjectResponse
```

Request DTOs (`com.hamstrack.issue.dto`), all records:

```java
CreateSprintRequest(@Size(max = 60) String name,        // blank/absent → "Sprint {sequence}"
                    @Size(max = 500) String goal,
                    OffsetDateTime startAt,             // plan only; does NOT start it
                    OffsetDateTime endAt)

UpdateSprintRequest(String name, String goal,           // partial; null = leave
                    OffsetDateTime startAt, OffsetDateTime endAt,
                    Boolean clearStartAt, Boolean clearEndAt)   // boxed + null→false coalesce

StartSprintRequest(OffsetDateTime startAt,              // default: now
                   OffsetDateTime endAt,                // default: startAt + default-sprint-length-days
                   String goal)                         // optional last-minute goal; null = leave

CompleteSprintRequest(@NotNull UnfinishedDisposition moveUnfinishedTo,   // BACKLOG | SPRINT
                      UUID targetSprintId)              // required iff SPRINT

AddIssuesToSprintRequest(@NotEmpty List<UUID> issueIds,
                         SprintInsertPosition position) // TOP | BOTTOM, default BOTTOM

RankIssueRequest(UUID afterIssueId, UUID beforeIssueId,
                 UUID sprintId, Boolean clearSprint,    // boxed + coalesce (Jackson-3 primitive trap)
                 Integer version)                       // optional optimistic check
```

Response DTOs — the exact wire shapes are pinned in **§7** (backend and frontend build against that
section, not against this list).

**Status codes**

| Code | When |
|---|---|
| 200 | reads, PATCH, start, complete, add-issues, rank |
| 201 | sprint created |
| 204 | sprint deleted, issue removed from sprint |
| 400 | malformed request: `sprintId` **and** `clearSprint` both set; no anchor and no sprint change in a rank request; `issueIds` over `max-issues-per-bulk-move`; `sprintId` and `noSprint` both on the issue list |
| 403 | member without the curation role on a lifecycle/CRUD call |
| 404 | workspace/project/sprint not found **or** caller not a member (never 403) |
| 409 | start when not FUTURE / when another sprint is active · complete when not ACTIVE · delete an ACTIVE sprint · delete a sprint that still holds issues without `force` · stale `version` · **stale rank anchors** ("the list changed — refresh") · project archived |
| 422 | unknown/foreign `sprintId` or anchor id · anchor not in the target section · anchor == the moved issue · assign to a COMPLETED sprint · complete-target not a FUTURE sprint of this project · `endAt <= startAt` · `storyPoints` out of range or > 2 decimals · open-sprint cap reached |

Notes:

- `GET /sprints` is **always paged** (`PageResponse`, default size 50): open sprints are capped, but
  COMPLETED ones accumulate for years. Ordering: ACTIVE first, then FUTURE by `sequence ASC`, then
  COMPLETED by `sequence DESC` (a `CASE` in the `ORDER BY`). `state` is a repeatable filter;
  omitting it returns all states.
- `POST /sprints/{id}/start` and `/complete` accept an **optional body** (`@RequestBody(required =
  false)`) — `POST` with no body means "start now with the default length" (only `complete` requires
  a body, for its disposition).
- `GET /sprints/{id}/completion-preview` is the dialog's data source and is **read-only** for any
  project member: the same counters the completion will report, plus the eligible target sprints, so
  the dialog never guesses.
- `POST /issues/{number}/rank` addresses the moved issue by **number** (the established issue
  addressing) while anchors and the sprint travel as **ids** (the established DTO convention for
  references: `parentId`, `assigneeId`, `componentId`).
- `GET /issues?sprintId=` compiles to a plain `i.sprint.id = :sprintId` predicate ANDed with every
  existing filter; `noSprint=true` compiles to `i.sprint IS NULL`. A foreign sprint id simply matches
  nothing — never an error, never a leak.

### 4.5 Rules & edge cases

| Case | Behaviour |
|---|---|
| Sprint name omitted on create | `"Sprint " + sequence`. |
| Name normalization | `ClassificationNames.normalize` (NFC, control/format chars dropped, whitespace collapsed), max 60 after normalization → 400 if blank/too long. Casing preserved; uniqueness case-insensitive per project. |
| Duplicate name (incl. a COMPLETED sprint's) | **409** "a completed sprint already uses this name — rename it". Sequence-based defaults never collide, so this is a hand-typed-name case only. |
| `sequence` assignment | `COALESCE(MAX(sequence),0)+1` within the project, arbitrated by `sprints_project_id_sequence_key`; a lost race retries **once**, then 409. Numbers are not reused after a delete of the highest sprint — harmless, `sequence` is an order key, not an identity. |
| Open-sprint cap | `POST /sprints` → **422** when FUTURE+ACTIVE ≥ `max-open-sprints-per-project`. COMPLETED sprints do not count (they are history and cannot be started). |
| Start when another sprint is ACTIVE | **409** (conditional UPDATE + partial unique index, §4.1). |
| Start a COMPLETED / already-ACTIVE sprint | **409**. |
| Start an **empty** sprint | **Allowed.** Blocking it pushes teams to file a placeholder issue. The UI warns, the API does not. |
| `endAt <= startAt` (create, patch or start) | **422**; also enforced by `sprints_dates_ck`. |
| `startAt` in the past | Allowed (backfilling a sprint that began on Monday is normal). |
| Complete when not ACTIVE | **409**. |
| Complete: what moves | Every issue in the sprint whose **status category ≠ DONE** moves to the chosen destination; DONE issues **keep** `sprint_id` — that is the sprint's record of what it delivered. |
| Complete → `SPRINT` target | Must be a **FUTURE** sprint of the same project and not the sprint being completed → **422** otherwise. Deliberately not an ACTIVE one: there is none (this sprint is it) — and not a COMPLETED one. |
| Complete → `BACKLOG` | `sprint_id := null`; rank is **preserved** (the carried-over items keep their relative order at the top of the backlog they came from). |
| Carried-over rank into a target sprint | Preserved as well — one bulk UPDATE of `sprint_id`, no rank rewrite. Order within the target sprint is the shared rank order. |
| Concurrent complete (double-click) | Conditional UPDATE ⇒ exactly one wins, the second gets **409**. Idempotent-looking success is refused precisely because the move is destructive (the `VersionService.release` rationale). |
| Assign an issue to a **COMPLETED** sprint | **422** "sprint is completed". Assigning to an ACTIVE one mid-sprint is allowed (scope change is a real event). |
| Assign an issue already in that sprint | No-op, no history row, no `@Version` bump. |
| `sprintId` unknown / another project's | **422 "Unknown sprint"** (via `findByIdAndProject`), never 404, never a leak. |
| Issue history | `field = "sprint"`, old/new = sprint names (null when none). Rank changes write **no** history (positional churn would drown the log) — the same reasoning as label merge. `storyPoints` changes write history. Create-time values write none (existing convention). |
| Rank: no anchors and no sprint change | **400** — the request asks for nothing. |
| Rank: anchor in a different section | **422** "anchor is not in the target list" — makes the operation total and prevents ordering paradoxes. |
| Rank: `after.position >= before.position` | **409** "the list changed — refresh". |
| Rank: gap exhausted | Whole-project rebalance, then one retry (§3.3.4). Never a 500, never a tie. |
| Rank of a DONE issue | Allowed (rank is orthogonal to status). |
| Delete an ACTIVE sprint | **409** "complete it first". |
| Delete a FUTURE/COMPLETED sprint holding issues | **409** unless `?force=true`; with `force` the issues' `sprint_id` is nulled first via an explicit bulk update (§4.3 trap) and rank is preserved. |
| Project archived | Every sprint mutation, rank move and issue assignment → **409 "Project is archived"** (`requireNotArchived`). Reads still work. |
| Issue deleted | Nothing special — `sprint_id` disappears with the row. |
| Project/workspace deleted | `sprints` cascade. |
| `storyPoints` on a DONE issue | Editable; `donePoints` reflects the current value. |
| Section stats vs the truncation cap | Stats (`issueCount`, `points`, `donePoints`, `unestimatedCount`, `doneIssueCount`) are computed over the **whole** section by a grouped query, never over the truncated page — a truncated section still shows honest totals. |

### 4.6 Query & performance obligations

- **One grouped stats query for the entire planning view**, grouped by `i.sprint.id` (the NULL group
  is the backlog), carrying the same filter predicate as the sections. Never one query per section.
- `GET /sprints` gets its `issueCount`/`doneIssueCount`/`points`/`donePoints` from **one** grouped
  query over the whole page of sprints (the `VersionService.progress` pattern, including its batching
  helper if the page can exceed the parameter ceiling).
- Section fetches reuse the existing `LEFT JOIN FETCH` block (`type/status/priority/assignee/
  reporter/component`) and the batched label/version loaders in `IssueService.toResponses` — a
  planning view of 300 issues must stay a **constant** number of queries.
- New repository method `long maxPosition(Project)` (`SELECT COALESCE(MAX(i.position), 0) …`) served
  by `idx_issues_project_position`.
- The rank endpoint's neighbour lookups are two `LIMIT 1` queries over
  `(project_id, sprint_id, position)` — both index-served.

### 4.7 Search / HQL integration (sub-slice HD-22F)

Kept as a separately-marked sub-slice so the owner can drop it in one edit if scope must shrink;
**recommended to include** — a saved filter "my open work in the active sprint" is the first thing a
team will ask for.

| HQL name | `FieldDataType` | Ops | IN | `IS EMPTY` | Sortable | `valueSuggest` |
|---|---|---|---|---|---|---|
| `sprint` (`sprints`) | `ENUM_REF` | `=` `!=` | yes | yes | no | `SPRINT` |
| `storyPoints` (`points`) | `NUMBER` | `=` `!=` `>` `<` `>=` `<=` | no | yes | **yes** | none |

- `sprint` is a plain ToOne, so it needs no new compiler branch: `entityPath = "sprint.id"`, a
  `case "sprint" -> ctx.sprintIdsByName()` in `HqlValueResolver.resolveEnum`, and
  `sprintIdsByName`/`sprintNames` in `ResolutionContext`/`ResolutionContextFactory` built from the
  **visible project set only** (so a name can never resolve through a project the actor cannot see),
  exactly like `component`. Completed sprints are excluded from *name resolution* but issues carrying
  them still match by id. Not sortable: sprint order across several projects has no common meaning.
- `storyPoints` is the first **native numeric** HQL field and needs three small additions:
  a `ResolvedValue.NumberValue(BigDecimal)` member of the sealed interface, a `case NUMBER ->` in
  `HqlValueResolver.resolve` (replacing today's "not queryable in MVP" throw) and an ordered-compare
  branch in `HqlCompiler` binding `root.get("storyPoints")`. `IS EMPTY` = unestimated.
- `/schema` gains a `SPRINT` picklist (open sprints of the visible projects, capped at 200 like the
  others) and lists both fields once (aliases de-duplicate by descriptor identity).
- Saved filters need no change (save-time validation is parse + structural only).

### 4.8 Events, demo data

- The rank endpoint, the assign/remove endpoints and `complete` publish **`IssueUpdated`** per
  affected issue so other clients' boards refresh over SSE — *except* `complete`, which may touch
  hundreds of issues: it publishes **one** `IssueUpdated` per moved issue only when the moved count
  is ≤ 50, and otherwise nothing (the completing client refetches; other clients refresh on their
  next poll/navigation). Record the threshold as a constant with this comment. No new SSE event type
  in this release.
- `DemoDataService` (gated by the existing `app.demo.seed-on-first-login`): the demo project is
  created with `boardMode = SCRUM`, gets one **ACTIVE** sprint ("Sprint 1", started 5 days ago,
  ending in 9) holding ~8 of the seeded issues with story points (1/2/3/5/8), one **FUTURE**
  ("Sprint 2") holding ~3, and leaves the rest ranked in the backlog. Everything cascades away with
  the workspace, so the documented test-mode reset block needs no change.

### 4.9 Acceptance criteria — HD-22

Schema & migration
- [ ] `V11` applies additively on a populated DB; Hibernate `validate` passes; no `CHAR`/PG ENUM;
      `sprints.state` is `VARCHAR(10)`.
- [ ] `sprints_one_active_per_project_uk` rejects a second ACTIVE row at the DB level (verify with
      raw SQL, not only through the service).
- [ ] `issues_sprint_fk` is composite over `(sprint_id, workspace_id)` with
      `ON DELETE SET NULL (sprint_id)`; a hand-crafted cross-tenant INSERT fails.
- [ ] Existing positions are rescaled; `story_points` is backfilled from `issue_field_values` for
      every issue that had a numeric value; the `story_points` and `sprint` field defs are archived
      (not deleted) and their old values still render on issues that carry them.
- [ ] The rescale + backfill do **not** change any `issues.updated_at`; a normal PATCH still does.

Lifecycle & permissions
- [ ] Create/rename/delete/start/complete as project MANAGER → 2xx; as workspace OWNER/ADMIN who is
      not a project member → 2xx; as a plain project MEMBER → **403**; as a non-member of the
      workspace → **404**.
- [ ] Start: FUTURE → ACTIVE with `startAt` defaulted to now and `endAt` to +14d; starting a second
      sprint → **409**; two concurrent starts of two different sprints → exactly one 200, one 409;
      starting an ACTIVE or COMPLETED sprint → 409.
- [ ] Complete: requires ACTIVE (409 otherwise); DONE issues keep `sprint_id`; non-DONE issues move
      to backlog or to the chosen FUTURE sprint; `completedIssueCount` / `carriedOverIssueCount` /
      `donePoints` / `carriedOverPoints` match reality; a non-FUTURE or foreign target → 422;
      double-click → one 200 + one 409.
- [ ] `completion-preview` returns the same numbers the subsequent `complete` reports (no drift) and
      is readable by a plain member.
- [ ] Re-opening is impossible: there is no endpoint and a PATCH cannot change `state`.
- [ ] Delete: ACTIVE → 409; FUTURE/COMPLETED with issues → 409 without `force`, 204 with it and the
      issues' `sprint` is null in the API **and** in the DB; deleting a project cascades its sprints.
- [ ] Open-sprint cap reached → 422; duplicate name → 409; blank/61-char name → 400.

Rank
- [ ] Reordering persists across reloads and is identical on board and backlog (the same order key).
- [ ] `afterIssueId` only, `beforeIssueId` only, both, and neither (+ a sprint change) all place the
      issue correctly; a new issue is created at the **bottom** of the backlog.
- [ ] An anchor from another project/section → 422; anchor == the moved issue → 422; stale anchors
      (`after >= before`) → 409.
- [ ] Forcing 30 consecutive midpoints into one gap triggers exactly one rebalance, the order is
      preserved, no ties are produced, **no `@Version` is bumped** on untouched issues and **no
      `updated_at` changes** on them.
- [ ] Dragging into a sprint sets `sprint_id` and the rank in one request; `clearSprint` returns it
      to the backlog.
- [ ] `version` in the rank request: stale → 409; absent → applies.

Issue payload, filters, points
- [ ] `IssueResponse.sprint` and `.storyPoints` are present everywhere (single GET, board, backlog,
      children, HQL search rows) and `position` is **not** exposed.
- [ ] `storyPoints`: `0`, `0.5`, `999` accepted; `-1`, `1000`, `1.234` → 422; `clearStoryPoints`
      unsets it; a change writes one `storyPoints` history row, a no-op writes none.
- [ ] `?sprintId=` and `?noSprint=true` filter board and backlog and compose (AND) with
      status/assignee/priority/component/label/fixVersion; both together → 400.
- [ ] `GET …/backlog` returns ACTIVE-first sprint sections + the backlog section; stats are computed
      over the whole section even when the section is truncated; `truncated`/`totalAvailable` follow
      the HD-79 shape; a 300-issue view is a **constant** number of queries (assert with the
      statement counter).
- [ ] **Cross-tenant:** a sprint id from another workspace/project → 422 on assign/rank, 404 on a
      direct GET; no sprint list or backlog view ever contains a foreign row.

Search (sub-slice)
- [ ] `sprint = "Sprint 7"`, `sprint IN (…)`, `sprint IS EMPTY`, `storyPoints >= 5`,
      `storyPoints IS EMPTY`, `ORDER BY storyPoints DESC` all compile and return the right rows;
      `ORDER BY sprint` → 422; `/schema` lists both fields once with a `SPRINT` picklist; a saved
      filter using `sprint` saves, loads and runs.

---

## 5. Frontend — HD-23 (rank-ordered backlog + sprint planning)

### 5.1 DnD library decision

**Use the native HTML5 drag-and-drop API — no new dependency.** `package.json` carries no DnD
library, and `BoardPage` already implements card dragging with `draggable` + `dataTransfer` +
`onDragOver`/`onDrop`. Adding `dnd-kit`/`react-beautiful-dnd` would bring a second, incompatible drag
paradigm into a codebase that already ships one, grow the bundle for a single screen, and force a
refactor of the board to match. The backlog list needs exactly what the board needs plus an
insertion indicator, which is ~40 lines: on `dragOver` over a row, compare `e.clientY` to the row's
vertical midpoint to decide *before* or *after*, render a 2px brand-teal rule at that boundary, and
on drop resolve `{ afterIssueId, beforeIssueId }` from the rendered array.

**Accessibility is not optional:** native DnD is pointer-only, so every drag has a keyboard-reachable
equivalent in the row's kebab menu — *Move to top*, *Move to bottom*, *Move up*, *Move down*,
*Move to sprint ▸ (list)*, *Move to backlog* — all hitting the same `POST …/rank` endpoint. The
sprint-section headers expose the same actions for their contents.

### 5.2 Pages, components, stores

- **`pages/BacklogPage.tsx` (rewritten).** Route unchanged (`/w/:wsId/p/:projectId/backlog`, wrapped
  in `ParamKeyed`). One query against `GET …/backlog` under a new
  `backlogViewKey(wsId, projectId, filters)`; the old `apiListIssuesPaged` + `Pager` path is
  removed (truncation banner replaces pagination — the HD-79 pattern, with links to Search).
  Layout, top to bottom:
  - filter bar: the existing controls (status, priority, `ComponentFilter`, `FixVersionFilter`,
    `LabelFilter`) — all already server-side and simply forwarded to the new endpoint;
  - **sprint sections** (rendered when `boardMode === 'SCRUM'` or ≥1 non-completed sprint exists),
    ACTIVE first then FUTURE by sequence. Each is a collapsible card with a header showing name,
    date range, `daysRemaining` for the active one, `issueCount`, a **point-sum badge**
    (`donePoints / points`, plus `n unestimated` when > 0), and the primary action — *Start sprint*
    (curator, FUTURE, disabled with a tooltip when another sprint is active) or *Complete sprint*
    (curator, ACTIVE) — plus a kebab (Edit, Delete);
  - **create-sprint** control at the end of the sprint area (curator only);
  - **Backlog** section: the same ranked list, the drop target for "remove from sprint".
  - Rows are compact (`key`, title, type, status, priority, assignee, point chip, `ParentChip`,
    `LabelChips`), draggable, and clicking one opens the existing `IssueSidePanel`.
- **`components/sprints.tsx` (new).** `SprintStateBadge`, `SprintPicker` (open sprints of the
  project), `SprintHeader`, `StartSprintDialog` (name/goal/start/end, prefilled), `CompleteSprintDialog`
  (see below), `SprintPointsBadge`, `useSprintMutations` hook. **Shared with HD-27** — build it here,
  consume it there.
- **`CompleteSprintDialog` contract:** on open it fetches `completion-preview` and renders
  *"N of M issues done · X of Y points"* plus a radio pair — **Move the K unfinished issues to the
  backlog** (default) or **to → [select of FUTURE sprints]**; the select is disabled with an
  explanatory line when the project has no future sprint, and the dialog offers *Create sprint* in
  that case. On confirm it POSTs `complete` and shows the returned
  `SprintCompletionResult` as a summary toast: *"Sprint 7 completed — 17 points done, 2 issues moved
  to Sprint 8."*
- **Optimistic drag.** `onMutate` reorders/reassigns in the cached `BacklogViewResponse`
  (`qc.setQueryData`) so the row lands instantly; `onError` rolls back and surfaces the message
  (a 409 renders *"The list changed — refreshing"* and invalidates); `onSettled` invalidates the
  backlog view + `projectIssuesKeyPrefix` so the board agrees.
- **`lib/queryKeys.ts`** — add `sprintsKey(wsId, projectId, state?)` and
  `backlogViewKey(wsId, projectId, filters)` reusing `serializeIssueFilters`. **`boardIssuesKey`'s
  existing 2-argument call must keep working** (`CreateIssueModal` reads exactly that key — the
  HD-86/87 white-screen class of bug).
- **`api.ts` / `types.ts`** — a `sprints` API group (`list/get/create/update/start/completionPreview/
  complete/addIssues/removeIssue/remove`), `apiGetBacklogView`, `apiRankIssue`, and the
  `Sprint`, `SprintRef`, `SprintState`, `BacklogView`, `SectionStats`, `SprintCompletionPreview`,
  `SprintCompletionResult`, `BoardMode` types from §7; `sprintId`/`clearSprint`/`storyPoints`/
  `clearStoryPoints` added to the issue create/update payload types and `sprint`/`storyPoints` to
  `Issue`.
- **`pages/IssueDetail.tsx`** — two new cells in the HD-68 details grid: **Sprint** (read = badge,
  click = `SprintPicker`, commit = `apiUpdateIssue({ sprintId })`, "Remove from sprint" sends
  `clearSprint`) and **Story points** (inline numeric input, blank sends `clearStoryPoints`).
- **`components/CreateIssueModal.tsx`** — optional Sprint picker and Story-points input, both in the
  details column; sent as `sprintId` / `storyPoints`.
- **DESIGN.md compliance** — point chips and the sprint badge use `--radius-full` and the tokens
  (`--color-brand` for the active sprint, the slate/info tint for future, success for completed);
  point numbers and dates render in IBM Plex Mono (they are inspectable data); the drop indicator is
  a 2px `--color-brand` rule; drag opacity matches the board's `0.4`. **Never** the Tailwind
  `max-w-*` scale (our `@theme --spacing-*` shadows it) — use inline `maxWidth`.

### 5.3 Acceptance criteria — HD-23

- [ ] `tsc --noEmit` and `vite build` clean; existing Vitest suites still pass.
- [ ] The backlog renders sprint sections above a ranked backlog; a Kanban project with no sprints
      shows only the ranked list and no Scrum vocabulary.
- [ ] **Reordering persists and is stable across reloads** (the story's criterion) — including after
      a hard refresh and in a second browser.
- [ ] **Dragging an issue into a sprint sets its `sprint_id`** (the story's criterion) and dragging
      it back to the Backlog section clears it, both in one request.
- [ ] Dropping between two rows places the issue exactly there; the insertion indicator matches the
      final position; dropping onto an empty section appends.
- [ ] Every drag has a kebab equivalent that is reachable and operable by keyboard alone.
- [ ] Point sums per section match the server's numbers, are shown as `done / total` with an
      "n unestimated" hint, and stay correct when a section is truncated.
- [ ] Create-sprint, Start (with the disabled-tooltip when another sprint is active) and Complete
      are visible only to curators; a plain member sees the sections read-only-for-lifecycle but can
      still drag.
- [ ] The complete dialog shows the preview counts, both disposition options, refuses to submit
      "to sprint" without a target, and reports the result summary.
- [ ] A 409 from a stale drag shows a non-destructive "list changed — refreshing" message and the
      list re-renders correctly (no ghost row, no duplicate).

---

## 6. Frontend — HD-27 (Scrum board scoped to the active sprint)

- **`pages/BoardPage.tsx`** reads `boardMode` from the already-cached `['project', wsId, projectId]`
  query. When `SCRUM`:
  - fetch the active sprint via `sprintsKey(wsId, projectId, 'ACTIVE')` and pass
    `sprintId` into the existing `apiListIssues` filters (it joins `BoardFilters` and therefore the
    query key, so the Kanban and Scrum caches never mix);
  - render a **sprint header** strip above the columns: name, goal (truncating, full text on hover),
    `daysRemaining` ("6 days left" / "2 days overdue" / "ends today"), the total point sum, and a
    **Complete sprint** button (curator only) opening the shared `CompleteSprintDialog`;
  - each column header additionally shows its **point subtotal**, computed client-side over the
    loaded cards (the board is capped; when `truncated` is true the subtotal is suffixed with `+`
    and the existing truncation banner explains it);
  - **empty state** when there is no ACTIVE sprint: a centred panel — *"No active sprint. Plan one in
    the Backlog."* with a primary link to the backlog (curators additionally get *Start sprint* when
    a FUTURE sprint exists).
  - When `KANBAN`: byte-identical to today (no sprint fetch, no header, no filter param).
- **Workflow transitions are untouched:** the Scrum board reuses the same `isMoveAllowed` +
  `apiUpdateIssue({ statusId, version })` drag path, so transition rules, optimistic move, rollback
  and the 409 handling behave exactly as on the Kanban board.
- **`pages/settings/ProjectBoardSettingsPage.tsx` (new)** + a `Board` tab in
  `ProjectSettingsArea.TABS` (absolute path — inside a splat route relative links resolve after the
  splat): a two-option radio (Kanban / Scrum) with one explanatory line each, saved via
  `PATCH …/projects/{id} { boardMode }`, invalidating `['project', …]` and the board keys. The area
  is already curator-gated.
- **`NavRail`** needs no structural change: Board/Backlog already exist. The Board item's label stays
  "Board" in both modes (the header says which sprint); the rail item is *not* renamed to "Sprint
  board" — one less thing to translate and to explain.

### 6.1 Acceptance criteria — HD-27

- [ ] **Switching a project between Kanban and Scrum is a per-board setting** (the story's criterion):
      the Board tab in project settings flips `boardMode`, the board changes immediately, another
      project in the same workspace is unaffected, and a non-curator cannot reach the tab.
- [ ] In Scrum mode the board shows **only** the active sprint's issues; issues outside it are absent
      from every column.
- [ ] The sprint header shows name, goal, days remaining (including the overdue and "today" wordings)
      and point totals; each column shows its point subtotal.
- [ ] **The board respects workflow transitions like the existing Kanban** (the story's criterion):
      a disallowed target column is not a drop target, an allowed move is optimistic and rolls back
      on error, and the 409 message still surfaces.
- [ ] Complete-sprint from the board header opens the same dialog as the backlog, and on success the
      board falls back to the "no active sprint" empty state.
- [ ] With no active sprint, the empty state (not an empty board) renders, and the Kanban mode is
      pixel-unchanged from before this release.
- [ ] `tsc --noEmit` + `vite build` clean.

---

## 7. Pinned contract (backend ⇄ frontend, build in parallel against this)

```jsonc
// SprintResponse
{
  "id": "uuid", "name": "Sprint 7", "goal": "Ship billing v2" | null,
  "state": "FUTURE" | "ACTIVE" | "COMPLETED",
  "sequence": 7,
  "startAt": "2026-08-10T09:00:00Z" | null,
  "endAt":   "2026-08-24T17:00:00Z" | null,
  "completedAt": "2026-08-24T17:04:11Z" | null,
  "daysRemaining": 6 | null,          // ACTIVE + endAt only; negative = overdue
  "issueCount": 12, "doneIssueCount": 7,
  "points": 34.5 | null, "donePoints": 21 | null,   // null only in the §3.4 fallback design
  "unestimatedCount": 2,
  "createdAt": "…", "updatedAt": "…"
}

// SprintRef (embedded in IssueResponse)
{ "id": "uuid", "name": "Sprint 7", "state": "ACTIVE" }

// IssueResponse — ADDITIONS ONLY (everything else unchanged; `position` is NOT exposed)
{ "sprint": SprintRef | null, "storyPoints": 5 | null }

// CreateIssueRequest / UpdateIssueRequest — ADDITIONS
{ "sprintId": "uuid" | null, "clearSprint": true|false,
  "storyPoints": 5 | null,   "clearStoryPoints": true|false }

// ProjectResponse — ADDITION;  UpdateProjectRequest — ADDITION
{ "boardMode": "KANBAN" | "SCRUM" }

// SectionStats
{ "issueCount": 12, "doneIssueCount": 7, "points": 34.5, "donePoints": 21, "unestimatedCount": 2 }

// BacklogViewResponse — GET …/backlog
{
  "sprints": [ { "sprint": SprintResponse, "issues": [IssueResponse],
                 "truncated": false, "totalAvailable": 12, "stats": SectionStats } ],
  "backlog": { "issues": [IssueResponse],
               "truncated": true, "totalAvailable": 812, "stats": SectionStats },
  "sectionCap": 300
}

// SprintCompletionPreview — GET …/sprints/{id}/completion-preview
{ "totalIssueCount": 12, "doneIssueCount": 7, "unfinishedIssueCount": 5,
  "totalPoints": 34.5, "donePoints": 21, "unfinishedPoints": 13.5,
  "targetCandidates": [ { "id": "uuid", "name": "Sprint 8", "state": "FUTURE" } ] }

// CompleteSprintRequest / SprintCompletionResult
{ "moveUnfinishedTo": "BACKLOG" | "SPRINT", "targetSprintId": "uuid" | null }
{ "sprint": SprintResponse, "completedIssueCount": 7, "carriedOverIssueCount": 5,
  "carriedOverToSprintId": "uuid" | null, "donePoints": 21, "carriedOverPoints": 13.5 }

// RankIssueRequest — POST …/issues/{number}/rank  → IssueResponse
{ "afterIssueId": "uuid" | null, "beforeIssueId": "uuid" | null,
  "sprintId": "uuid" | null, "clearSprint": false, "version": 3 | null }

// AddIssuesToSprintRequest — POST …/sprints/{id}/issues → SprintResponse
{ "issueIds": ["uuid"], "position": "TOP" | "BOTTOM" }
```

Timestamps are UTC ISO-8601 (`OffsetDateTime`, stamped with `ZoneOffset.UTC` — the bug
`VersionService.release` already fixed once). Point values are JSON numbers (`BigDecimal`, ≤2
decimals, trailing zeros stripped).

---

## 8. Review gates & doc obligations

- **`migration-reviewer`** — mandatory: `V11` (composite FK, `ON DELETE SET NULL (col)`, partial
  unique index, the trigger-function replacement, the two data rewrites) + the new/changed
  `@Entity` classes (`Sprint`, `Issue.sprint`/`storyPoints`, `Project.boardMode`, the
  `updatable = false` pair).
- **`tenancy-reviewer`** — mandatory: a new project-scoped family of repositories/services plus a
  new cross-entity write path (rank) — three new chances to leak.
- **`security-officer`** — mandatory: the widened `ProjectService.update` gate (§3.2), the
  destructive `complete`/`force`-delete paths, the rank endpoint's authorization (issue-edit tier,
  not curator), input validation on points/dates/names, and the `hamstrack.skip_updated_at` GUC
  (confirm it cannot be set from user input and cannot outlive its transaction).
- **`dc-cloud-guard`** — the four `app.agile.*` properties' full wiring path.
- **`api-docs-sync`** — `openapi.yaml` gains a `Sprints` tag with every path + schema above, the
  issue payload additions, `boardMode`, and the new list params; both `docs/api-*.md` updated
  identically; `docs/api-dc.md`'s operator-settings table gains all four properties. Validate with
  `npx @apidevtools/swagger-cli validate`.
- **`test-runner`** — the §4.9 / §5.3 / §6.1 checklists.
- **`docs/project-state.md`** — a new "Agile: sprints, ranking & Scrum board (V11)" section.
  **`CLAUDE.md`** gains exactly one new hot rule: *"`issues.position` is the project-wide backlog
  rank — server-written only, spaced by 2^26, rebalanced natively with
  `SET LOCAL hamstrack.skip_updated_at = 'on'`; never write it from a client payload."*

---

## 9. Risks & the highest-risk assumption

> **Highest-risk assumption:** that **story points should be promoted from the seeded
> `story_points` custom field to a native `issues.story_points` column** (§3.4). It is the one
> decision in this spec that rewrites existing data and retires an existing (if barely used) system
> field, and it slightly exceeds HD-22's literal scope. It is recommended because the alternative
> ships HD-4's "story-point sum" criterion **dead on a stock install** (the system-default field set
> is "No fields"), and because HD-6 set the precedent of replacing placeholder system fields with
> first-class attributes. If the owner disagrees, §3.4's boxed fallback paragraph is the complete
> switch — nothing else in this document changes.

Secondary risks:

1. **Rank rebalance blast radius.** One statement rewrites every `position` in the project. Mitigated
   by 2^26 spacing (rare), by the native-SQL/no-`@Version` design and by the `updated_at` guard —
   but a very large project will see a multi-hundred-millisecond statement. Assert the guard
   behaviour in tests; revisit with a neighbourhood renumber past ~200k issues per project.
2. **The shared `set_updated_at()` change touches every table with that trigger.** Default behaviour
   is provably identical (the GUC is unset), but it is a global function — `migration-reviewer` must
   confirm no other path sets the GUC and that the `SET LOCAL` inside V11 cannot survive the
   migration.
3. **`ON DELETE SET NULL` vs a stale managed `Issue`** on sprint delete — the documented
   `issue_seq`-clobber class; the explicit pre-delete bulk update in §4.3 is not optional.
4. **The widened project-update permission** (§3.2) is a real authorization change and needs an
   explicit sign-off, not a silent merge.
5. **Planning-view payload size.** Bounded by two caps, but a 21-section × 300-issue response is
   several MB of JSON. If it ever bites, the fix is lazy per-section fetches behind the same DTO —
   the frontend must therefore treat sections as independently refreshable from day one.
6. **Native HTML5 DnD ergonomics** (no touch support, coarse drag images). Accepted with the kebab
   fallback; a library remains an option later precisely because the endpoint contract is
   anchor-based and UI-agnostic.

---

## 10. Open questions (each with a recommended default — none blocks the build)

1. **Re-open a completed sprint?** → *No.* Terminal state; recovery is "create a new sprint and move
   the issues". Matches both reference products and keeps the one-active invariant simple.
2. **One active sprint per project, or per board?** → *Per project*, because a board is not a
   first-class object here (board mode is a project attribute). Revisit only if multiple boards per
   project are ever built.
3. **Should the backlog section hide DONE issues?** → *Yes by default* (`includeDone=false`), with
   the query param to override. A done, unranked issue is planning noise. Sprint sections always
   include DONE — that is the sprint's record.
4. **Should new issues land at the top or the bottom of the backlog?** → *Bottom.* Filing an issue is
   not a priority statement; the team ranks it at grooming.
5. **Should the rank endpoint require `version`?** → *Optional* (§3.3.3). Rank is last-drag-wins;
   mandatory optimistic locking would 409-storm a planning meeting.
6. **Sprint capacity as a number (HD-4's "capacity")?** → *Not in MVP.* The point sum answers the
   question a planning meeting actually asks. If demanded, it is a purely additive
   `sprints.capacity_points NUMERIC(6,2)` plus a header comparison — do not pre-build it.
7. **Do sprints belong in `GET …/projects/{p}/config`?** → *No* (§3.6). Separate endpoints, separate
   query keys; the config response must not be invalidated by a sprint start.
8. **Should HD-22F (HQL `sprint`/`storyPoints`) ship in 0.13.0?** → *Yes.* It is small, follows the
   `component` pattern, and saved filters over the active sprint are the first follow-up request.
9. **Should the demo project be seeded as SCRUM?** → *Yes*, with one active and one future sprint —
   otherwise the release's headline feature is invisible on first login.
10. **Kanban projects: hide sprint sections entirely?** → *Hide until a sprint exists* (§3.5). Never
    hard-block the API — a Kanban team may legitimately plan one iteration.
