# Issue Hierarchy: Epic → Story → Sub-task — Proposal

Status: **draft 2026-08-07** — awaiting owner sign-off.
Author: systems-analyst. Feeds `backend-builder` (HD-18) / `frontend-builder` (HD-19) / `test-runner`.
Backlog: HD-1 (parent), HD-18 (backend), HD-19 (frontend).

Companion docs to keep in sync on ship: `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, `CLAUDE.md`.

**Highest-risk assumption (flagged up front):** that a single integer
`hierarchy_level` on `issue_types` is enough to express "who may parent whom"
for the foreseeable roadmap. This is a deliberate simplification of Jira's
per-type parent-child matrix. If the product later needs "a Bug may parent a
Sub-task but a Task may not" (arbitrary pairs), the level model can't express
it and we'd need a `parent_type_id → child_type_id` allow-list table. I judge
the level model correct for now (it matches Epic/Story/Sub-task mental models
and every competitor's default), but call it out so the owner can veto before
we build.

---

## 1. Problem & goal

Teams need to break large work into a tree: an **Epic** groups **Stories**
(and Tasks/Bugs), and any of those can be split into **Sub-tasks**. Today the
`issues` table already has a self-referencing `parent_id` and both
`CreateIssueRequest.parentId` and `IssueResponse.parentId` exist, but there are
**no rules** (any issue can parent any other, including cross-project and
cycles), **no way to change or clear a parent after creation** (`UpdateIssueRequest`
has no `parentId`), **no children endpoint**, and **no UI** — the field is
effectively dead.

**Goal:** turn `parent_id` into a governed, single-level-per-hop hierarchy:
- Which types may parent which is **catalog-driven** (no hardcoded type names in
  logic), following the global-catalog shape already used for statuses /
  priorities / types.
- Assign / change / clear a parent on create **and** update, with validation
  (legal level, same project, no cycles, parent not archived-project).
- Read a parent's children (`GET .../children`) and see roll-up progress on
  Epics.
- Render it config-driven in the SPA: a parent picker filtered to legal parent
  types, a "create sub-task" affordance, an epic children panel with progress,
  and an epic/parent chip on board + backlog cards.

**Success looks like:** a user opens an Epic, sees its child Stories with a
"3 of 7 done" progress bar, creates a Sub-task under a Story in one click, and
cannot (via API or UI) attach a Story to a Sub-task, parent an issue to one in
another project, or create a cycle.

**Differentiator vs Jira:** we do **not** copy Jira's rigid three-tier
`Epic → Story → Sub-task` with special-cased "Epic Link" / "Parent Link"
fields and a separate epic-name field. Hierarchy here is **one uniform
`parent_id` edge governed by a numeric type level**, so an admin can add a
"Initiative" level above Epic, or collapse to two levels, purely by editing the
catalog — no new columns, no bespoke link types. This is closer to
Linear/GitHub-issues parent nesting than to Jira's proprietary epic model.

---

## 2. Scope

**In scope**
- `issue_types.hierarchy_level SMALLINT NOT NULL DEFAULT 1` (catalog column) +
  a seeded **Sub-task** type at level 0.
- Parent assignment/clear on issue **create** and **update**, with validation:
  strictly-greater parent level, same project, no self-parent, no cycle, parent
  exists and is visible to the caller.
- `GET .../issues/{number}/children` — the direct children of an issue.
- `IssueResponse` additions: `parentKey`, `parentTitle`, `parentTypeId`,
  `childCount`, `doneChildCount` (roll-up counters).
- Config endpoint carries `hierarchyLevel` per issue type so the SPA can filter
  parent pickers and offer "create sub-task".
- Issue history entry for parent changes ("parent").
- Frontend: parent picker (filtered to legal parent types), "Create sub-task"
  action, epic/parent children panel with roll-up progress bar, parent chip on
  board + backlog cards, breadcrumb-style parent link in the side panel.
- Delete/type-change semantics for issues that have children or a parent.

**Out of scope (non-goals)**
- **Cascade delete of children** when a parent is deleted (we re-home to
  grandparent / orphan instead — see §4). No "delete the whole subtree" button.
- **Cross-project hierarchy** (parent and child in different projects). Same
  project only, this release. (Schema doesn't forbid it at the DB level — it's
  an app rule, leaving the door open.)
- **Multi-level roll-up** (Epic sees the aggregate of grandchildren). Roll-up
  counts **direct children only** this release.
- **Moving an issue between projects** (there is no such endpoint today; if one
  is added later it must clear or re-validate `parent_id` — noted in §5).
- **Sub-task-specific behaviors** beyond hierarchy (e.g. sub-tasks not appearing
  on the board, sub-task-only statuses, "convert to sub-task" bulk ops).
- **Per-type parent allow-lists** (arbitrary pairs) — see §1 risk note.
- **Reordering children**, drag-to-reparent, tree view across the whole
  project, dependency links (blocks/relates-to — that's a separate link feature).
- **Auto-transitioning a parent** when all children are done (roll-up is
  display-only).

---

## 3. Actors & permissions & tenant scoping

- **Who triggers it:** any workspace member with write access to the project
  (same bar as creating/editing an issue today — issue create/update is not
  MANAGER-gated; delete is). Setting/clearing a parent is part of
  create/update, so it inherits those permissions.
- **Reading children / roll-up:** any workspace member who can read the issue.
- **Tenant scoping (top bug class — must hold):**
  - Every new path (`GET .../children`) resolves through
    `resolveWorkspace(actor, wsId)` → `findByIdAndWorkspace(projectId, ws)` →
    `findByProjectAndNumber` exactly like the existing issue endpoints. Missing
    membership or missing resource → **404, never 403**.
  - The candidate parent supplied on create/update is resolved with
    `issueRepository.findByIdAndProject(parentId, project)` (already used for
    create). A parent id from another project or workspace resolves to empty →
    **422 "Unknown parent issue"** (it is not leaked as 404-vs-403; from the
    caller's perspective it's an invalid field value, consistent with the
    existing create path). This keeps a foreign issue id from ever attaching.
  - `hierarchy_level` is on the **global catalog** (`scope_workspace_id`/
    `scope_project_id` NULL columns already present) — no tenant data, no
    scoping concern on the column itself.

---

## 4. Behavior & rules

### 4.1 The level model (the key design decision)

Add `issue_types.hierarchy_level SMALLINT NOT NULL DEFAULT 1`. Semantics:

- **Higher number = higher in the tree** (can parent lower numbers).
- Seeded catalog after V2: `Epic = 2`, `Story = 1`, `Task = 1`, `Bug = 1`,
  `Sub-task = 0` (new type, added in V2).
- **Parent rule:** `parent.type.hierarchyLevel > child.type.hierarchyLevel`
  (strictly greater). So Epic(2) can parent Story/Task/Bug(1) and Sub-task(0);
  Story(1) can parent Sub-task(0); Sub-task(0) can parent nothing; two Stories
  (1 == 1) cannot parent each other. This is enforced in the service, reading
  the level off the resolved `IssueType` entities — **no type names appear in
  logic.**

**Why levels, not a `parent_type → child_type` allow-list table:** the level
model needs one nullable-free integer column and zero new tables, expresses the
Epic/Story/Sub-task hierarchy every team expects, and lets an admin insert new
tiers (e.g. "Initiative" at level 3) with a single field edit. The pair-wise
allow-list is strictly more expressive but massively heavier (a new binding
table, admin UI for the matrix, migration of defaults) and solves a need no one
has articulated. See §1 risk note — this is the one place to reconsider before
building. **Recommendation: ship the level model.**

**Why `hierarchy_level` on the catalog (not on a set/binding):** the level is an
intrinsic property of a *type* ("an Epic is always above a Story"), not a
per-project policy. It rides on the existing global `issue_types` catalog row
and is exposed to projects through the type set that already carries the type —
no new binding needed. It stays catalog-driven and fits the established shape:
type sets restrict *which* types a project offers; the level restricts *how they
nest*, and travels with the type.

### 4.2 Adding the Sub-task type and its interaction with type sets

- V2 inserts a global `Sub-task` catalog row (`hierarchy_level = 0`,
  `position = 4`, an icon — recommend lucide `git-branch` or `list-tree`; final
  choice per `DESIGN.md`).
- **It is NOT auto-added to the "All types" system-default set.** This mirrors
  the M3 rule already documented ("new catalog types are NOT auto-added to the
  system default set") and the fact that the set's items were seeded once in V1.
  Adding it to the seeded set would require touching seeded rows — avoid.
- **Consequence + recommended override:** to keep Sub-task usable out of the box
  without an admin step, V2 **also** inserts a `Sub-task` item into the existing
  `is_system_default` "All types" set (append at the next position). This is a
  data insert into a set that ships empty-of-Sub-task, not a rewrite of existing
  rows, so it's Flyway-safe. **Recommendation: do add Sub-task to the default
  set in V2** — a hierarchy feature whose child type is invisible by default is
  a bad first-run experience. (If the owner prefers the strict "admin opts in"
  stance, drop this insert and document that Sub-task must be added to a set
  before it appears; I recommend against.)
- Existing per-project type-set restrictions are unchanged: a project whose set
  omits Sub-task simply can't create Sub-tasks (and the "create sub-task"
  affordance is hidden for it — config-driven, see §7).

### 4.3 Parent assignment on **create**

Current create already resolves `parentId` via `findByIdAndProject` and 422s on
unknown. Extend it to also validate:

1. Parent exists in **this project** (existing check).
2. `parent.type.hierarchyLevel > newIssue.type.hierarchyLevel` → else **422**
   "A {childType} can't be a child of a {parentType}".
3. Parent ≠ the issue being created (trivially true on create; enforced on
   update).
4. No cycle (trivially true on create — the new issue has no descendants yet).

The type is resolved and level-validated *after* the type-in-set check that
already runs, so an out-of-set type fails first with its own 422.

### 4.4 Parent assignment / change / clear on **update**

`UpdateIssueRequest` gains:
- `UUID parentId` — non-null sets/changes the parent.
- `boolean clearParent` — when true (and `parentId` null), detaches the parent.

This mirrors the existing `clearAssignee` / `clearDueDate` convention (Jackson
can't distinguish absent from explicit null). Rules on update:

1. If `clearParent` and issue has a parent → set `parent = null`, history entry.
2. If `parentId != null`:
   - Resolve via `findByIdAndProject(parentId, project)` → 422 if not in project.
   - **Self-parent guard:** `parentId != issue.id` → else 422 "An issue can't be
     its own parent".
   - **Level guard:** `parent.type.level > issue.type.level` → else 422.
   - **Cycle guard:** walk the prospective parent's ancestor chain; if the issue
     being updated appears in it, reject 422 "This would create a cycle". Bound
     the walk (see §5 depth cap).
   - Set parent, history entry ("parent": old key → new key).
3. **Type change + existing parent interaction:** if the same PATCH changes the
   issue's `type` to one whose level is **≥ its current parent's level**, the
   parent becomes illegal. **Rule: reject with 422** ("Changing type to
   {type} conflicts with its parent {parentKey}; clear the parent first") rather
   than silently detaching — silent data loss on a type change is surprising.
   Validate the *resulting* (type, parent) pair after applying the requested
   changes, before save.
4. **Type change of a parent that has children:** if an issue *has children* and
   its type changes to one whose level is now **≤ some child's level**, the
   existing parent→child edges become illegal. **Rule: reject with 422** ("This
   issue has children; changing its type to {type} would make {n} child
   relationship(s) invalid"). Same principle — no silent orphaning.

All parent-related changes follow the existing "reads first, mutations last"
ordering (CLAUDE.md `@Version` double-write gotcha): resolve the candidate
parent and walk the ancestor chain **before** mutating the issue, then apply and
`save` once.

### 4.5 Reading children

`GET /api/workspaces/{ws}/projects/{p}/issues/{number}/children`:
- Returns the **direct** children (issues whose `parent_id` = this issue's id),
  as a plain `List<IssueResponse>` (consistent with the board's un-paginated
  list; a parent's child count is small — a Sub-task fan-out of thousands is not
  a real workload, but see §5 for a soft cap / future pagination note).
- Ordered by `position ASC, createdAt DESC` (same as the board list).
- Fetch-joins the ToOne associations like `findByProjectFiltered` to avoid N+1.

### 4.6 Roll-up (Epic/parent completion)

- **Definition:** for an issue with children, `childCount` = number of direct
  children; `doneChildCount` = number of direct children whose
  `status.category == DONE`. Roll-up is **direct children only** (a Sub-task
  under a Story under an Epic does not count toward the Epic).
- These two counters are added to `IssueResponse` and computed with a single
  grouped query for a list (avoid N+1), or a cheap count for a single-issue GET.
- **Display only** — reaching "N of N done" does **not** auto-transition the
  parent. (Auto-transition is an explicit non-goal; revisit as automation.)
- An issue with zero children returns `childCount = 0, doneChildCount = 0` and
  the SPA renders no progress bar.

### 4.7 Delete semantics

Deleting an issue is MANAGER-only today and `issues.parent_id` has **no**
`ON DELETE` action in the schema (plain `REFERENCES issues(id)`), so deleting a
parent with children would hit a FK violation and 500. We must handle it:

- **Recommended: re-home children on parent delete.** Before deleting an issue,
  set each direct child's `parent_id` to the **deleted issue's own parent**
  (the grandparent), or `NULL` if the deleted issue was a root. This preserves
  the children, keeps them one level shallower, and is the least-surprising
  outcome. Record a "parent" history entry on each re-homed child.
  - Level check on re-home: the grandparent's level is strictly greater than the
    deleted issue's level, which is strictly greater than each child's level, so
    grandparent > child holds transitively — **re-homing never violates the
    level rule.** No extra validation needed.
- **Rejected alternative — cascade delete the subtree:** too destructive for a
  MANAGER misclick; not offered.
- **Rejected alternative — 409 "detach children first":** annoying busywork.
- Implementation: in `IssueService.delete`, before `issueRepository.delete`,
  run a bulk `UPDATE issues SET parent_id = :grandparentId WHERE parent_id =
  :deletedId` (a `@Modifying` repo method). Grandparent id may be null.

### 4.8 Invariants (must always hold)

- A child and its parent are always in the **same project** (and therefore same
  workspace).
- `parent.type.hierarchyLevel > child.type.hierarchyLevel` for every edge.
- No issue is its own ancestor (acyclic).
- `parent_id` is never a dangling reference (FK enforces; re-home on delete
  keeps it valid).

---

## 5. Edge cases & failure modes

| Case | Behavior |
|---|---|
| Parent id from another project/workspace | `findByIdAndProject` empty → **422** "Unknown parent issue". No existence leak. |
| Self-parent (`parentId == issue.id`) | **422** "An issue can't be its own parent". |
| Direct cycle (A→B then B→A) | Ancestor walk finds the issue in the chain → **422** "This would create a cycle". |
| Deep/indirect cycle | Same ancestor walk catches it. Walk is bounded by a **max-depth cap of 20**; exceeding it → **422** "Hierarchy too deep" (also guards a corrupt pre-existing cycle from infinite-looping). |
| Illegal level (Story under Story, anything under Sub-task) | **422** with type names. |
| Type change makes current parent illegal | **422** (§4.4.3) — don't silent-detach. |
| Type change makes existing children illegal | **422** (§4.4.4). |
| Parent in an **archived project** | Create/update already `requireNotArchived(project)` on the *child's* project, and parent must be same-project, so an archived-project parent is unreachable (the whole write path is blocked). No extra check needed. |
| Parent issue itself archived/soft-deleted | Issues have **no** soft-delete/archive (only projects do). N/A. |
| Optimistic locking | `version` check unchanged; a parent change is a normal field mutation on the child and bumps the child's `@Version`. Re-homing children on delete bumps *their* versions via the bulk update — a concurrent editor of a re-homed child gets a 409 on their next save (acceptable). |
| Two users set different parents concurrently | Last writer wins per the existing `@Version` guard on the child; no special handling. |
| Delete parent with children | Re-home to grandparent / null (§4.7), then delete. |
| Delete a child | Normal delete; parent's `childCount` drops on next read. No parent mutation needed. |
| Huge child fan-out | `GET .../children` returns all; add a **soft note** that if a single parent exceeds ~200 children we should paginate (future). Not built now. |
| Moving an issue between projects | No such endpoint exists. **If one is added,** it MUST clear `parent_id` (or reject if the issue has a parent/children) — documented here so the future builder doesn't strand a cross-project edge. |
| Sub-task type not in a project's type set | Can't create Sub-tasks there; "create sub-task" hidden (config-driven). Existing Sub-tasks (if the type left the set) keep their type — M3 rule. |
| Idempotency / races on re-home | The bulk `UPDATE ... WHERE parent_id = :deletedId` is atomic; the subsequent `DELETE` can't orphan a child that a concurrent create just attached, because that create runs in its own transaction and either committed before (gets re-homed) or after (attaches to a now-deleted parent → its own FK error → 500). To be safe, the delete transaction should re-check `count children` after the bulk update is a no-op concern; **recommendation:** accept the tiny race (deleting a parent at the exact instant someone attaches a child is vanishingly rare and self-corrects on the next create's FK error). Do not over-engineer. |

---

## 6. Data model impact

### 6.1 New column

`issue_types.hierarchy_level SMALLINT NOT NULL DEFAULT 1`

- `SMALLINT`, not `CHAR`/ENUM (Flyway rule). Maps to Java `short`.
- `NOT NULL DEFAULT 1` so existing catalog rows (Bug/Task/Story/Epic) get a
  valid value on migrate; V2 then sets Epic=2 and inserts Sub-task=0. **The
  DEFAULT is mandatory** (migration-reviewer will flag a `NOT NULL` add without
  a default on a populated table).
- **Entity parity:** add to `IssueType.java`:
  ```java
  @Column(name = "hierarchy_level", nullable = false)
  private short hierarchyLevel = 1;
  ```
  (Hibernate `validate` will fail if the column exists without the mapping, or
  vice-versa.)

### 6.2 Migration V2 outline (`V2__issue_hierarchy.sql`)

```sql
-- 1. New catalog column (default keeps existing rows valid)
ALTER TABLE issue_types ADD COLUMN hierarchy_level SMALLINT NOT NULL DEFAULT 1;

-- 2. Epic sits above the level-1 work types
UPDATE issue_types SET hierarchy_level = 2
 WHERE name = 'Epic' AND scope_workspace_id IS NULL AND scope_project_id IS NULL;

-- 3. New Sub-task catalog type at level 0 (icon per DESIGN.md; using a lucide name)
INSERT INTO issue_types (id, name, color, icon, position, hierarchy_level)
VALUES (gen_random_uuid(), 'Sub-task', '#64748B', 'git-branch', 4, 0);

-- 4. Make Sub-task usable out of the box: append it to the system-default
--    "All types" set (data insert, not a rewrite of seeded rows).
INSERT INTO issue_type_set_items (id, set_id, type_id, position)
SELECT gen_random_uuid(), s.id, t.id,
       (SELECT COALESCE(MAX(position), -1) + 1 FROM issue_type_set_items WHERE set_id = s.id)
FROM issue_type_sets s, issue_types t
WHERE s.is_system_default
  AND t.name = 'Sub-task' AND t.scope_workspace_id IS NULL AND t.scope_project_id IS NULL;
```

Notes:
- Uses `gen_random_uuid()` for SQL-seeded rows (matches V1 convention; app rows
  use UUID v7).
- No change to the `issues` table — `parent_id` and its index already exist.
- No `ON DELETE CASCADE` added to `parent_id` (re-home is done in the app layer,
  §4.7) — deliberately keep the plain FK so a stray delete surfaces rather than
  silently nuking a subtree.

### 6.3 New repository methods (`IssueRepository`)

```java
// Direct children, fetch-joined like the board list
@Query("SELECT i FROM Issue i LEFT JOIN FETCH i.type LEFT JOIN FETCH i.status "
     + "LEFT JOIN FETCH i.priority LEFT JOIN FETCH i.assignee LEFT JOIN FETCH i.reporter "
     + "WHERE i.parent = :parent ORDER BY i.position ASC, i.createdAt DESC")
List<Issue> findByParent(@Param("parent") Issue parent);

// Roll-up counts for a set of parents in one query (avoid N+1 on the board)
// returns rows of (parentId, total, doneCount)
@Query("SELECT i.parent.id, count(i), "
     + "sum(case when i.status.category = :done then 1 else 0 end) "
     + "FROM Issue i WHERE i.parent IN :parents GROUP BY i.parent.id")
List<Object[]> rollupByParents(@Param("parents") Collection<Issue> parents,
                               @Param("done") StatusCategory done);

// Re-home children to the grandparent (null = orphan) on parent delete
@Modifying
@Query("UPDATE Issue i SET i.parent = :grandparent WHERE i.parent = :deleted")
int rehomeChildren(@Param("deleted") Issue deleted, @Param("grandparent") Issue grandparent);
```

- `rehomeChildren` uses plain `@Modifying` (no `clearAutomatically`): it runs at
  the top of `delete` before other writes, and we don't re-read the mutated
  children in the same transaction (CLAUDE.md pending-inserts trap). If a
  history-per-child entry is desired, load the children first, write history,
  then bulk-update.

---

## 7. API surface

All paths are workspace-scoped (`/api/workspaces/{ws}/projects/{p}/...`).
**`openapi.yaml` + both `docs/api-*.md` must follow** (run
`api-docs-sync`; validate with swagger-cli).

### 7.1 New endpoint

```
GET /api/workspaces/{ws}/projects/{p}/issues/{number}/children
→ 200 List<IssueResponse>   (direct children, board order)
→ 404 if ws/project/issue not resolvable to the caller
```

### 7.2 Changed request DTOs

`CreateIssueRequest` — no shape change (`parentId` already present); behavior
gains level/cycle validation.

`UpdateIssueRequest` — add:
```java
UUID parentId,        // non-null sets/changes the parent
boolean clearParent   // true (with parentId null) detaches; mirrors clearAssignee
```

### 7.3 Changed response DTO — `IssueResponse`

Add (all derivable, no new stored state beyond `parent_id`):
```java
UUID   parentId,        // already present
String parentKey,       // e.g. "DEMO-12" — null if no parent
String parentTitle,     // null if no parent
UUID   parentTypeId,    // lets the SPA show the parent's type icon
int    childCount,      // direct children
int    doneChildCount   // direct children in a DONE-category status
```
- For a single-issue `get`, compute `childCount`/`doneChildCount` with a cheap
  count query. For list/board/children responses, batch via `rollupByParents`
  and fill from the map (issues absent from the map get `0/0`).
- `parentKey`/`parentTitle`/`parentTypeId` require the parent's `project.key` +
  `number` + `type`. The parent is a lazy proxy today (only its id is read). To
  avoid N+1 on lists, **fetch-join `i.parent` (and its type/project) in the
  list/board queries** OR resolve parent summaries in a second batched query
  keyed by parent id. **Recommendation: a second batched query** (`findAllById`
  on the distinct parent ids, map to a small `ParentRef`), because adding
  `parent` to the existing fetch-join set risks a Cartesian blow-up with the
  other joins and the board query is hot. Single-issue GET can just navigate the
  proxy inside the `@Transactional` method.

### 7.4 Changed config endpoint — `ProjectConfigResponse.issueTypes[]`

`IssueTypeResponse` gains `short hierarchyLevel`:
```java
public record IssueTypeResponse(UUID id, String name, String color, String icon,
                                short position, short hierarchyLevel) { ... }
```
The SPA uses this to (a) filter the parent picker to types with a level strictly
greater than the current issue's type level, and (b) decide whether "create
sub-task" is offered (does the project's type set contain any type with level
< the current issue's level?).

### 7.5 Status codes summary

- `200` children list / updated issue.
- `201` created issue (unchanged).
- `422` illegal parent (unknown, self, cycle, level violation, type-change
  conflict) — `HttpStatus.UNPROCESSABLE_CONTENT`.
- `409` optimistic-lock conflict (unchanged) / archived project (unchanged).
- `404` ws/project/issue not resolvable (tenant scoping).

---

## 8. Frontend impact (HD-19)

Read `DESIGN.md` first. Everything renders from `ProjectConfig` — no hardcoded
type names or levels.

**Types (`types.ts`):**
- `IssueType` gains `hierarchyLevel: number`.
- `Issue` gains `parentKey?`, `parentTitle?`, `parentTypeId?`, `childCount`,
  `doneChildCount`.
- `apiUpdateIssue` payload gains `parentId?` / `clearParent?`; add
  `apiGetIssueChildren(wsId, projectId, number)`.

**`CreateIssueModal.tsx`:**
- Add a **Parent** picker (a `Select` / typeahead) shown after Type. Its options
  are issues in the selected project whose **type level is strictly greater**
  than the selected type's level. Needs a lightweight issue list for the project
  (reuse `apiListIssues`, filter client-side by the parent-eligible type ids;
  the eligible type ids come from `config.issueTypes` filtered by level). When
  the modal is opened via a **"Create sub-task" / "Add child"** action from a
  parent (see below), the parent is pre-selected and the type defaults to the
  highest-level type whose level < parent level (e.g. Sub-task under a Story).
- Changing the Type re-filters the parent options and clears an now-illegal
  parent selection.

**`IssueSidePanel.tsx`:**
- **Details (view):** if the issue has a parent, show a breadcrumb chip
  ("↳ under DEMO-12 · <parent title>", with the parent type icon/color) that
  navigates to the parent. Config-driven type rendering.
- **Details (edit):** a Parent picker (same filtering as create) plus a "Clear
  parent" affordance → sends `clearParent: true`. Guard: hide/disable parent
  options whose level ≤ the (possibly just-changed) type level; block save with
  a clear message if the chosen type conflicts with the current parent (mirrors
  the backend 422, but fail fast client-side).
- **Children panel:** for an issue with `childCount > 0` (or any issue that
  *can* have children — i.e. its type level > the minimum offered level), render
  a "Child issues" section:
  - A **roll-up progress bar**: `doneChildCount / childCount` with a "3 of 7
    done" label. Colors per `DESIGN.md`.
  - The child list (from `apiGetIssueChildren`), each row = type icon, key,
    title, status badge; click opens that child in the panel.
  - A **"+ Create sub-task"** button that opens `CreateIssueModal` with this
    issue pre-selected as parent (via `uiStore.openCreateIssue({ parentId,
    parentLevel })` — extend the existing store action).

**`BoardPage.tsx` / card:**
- Add a small **parent chip** on cards that have a `parentKey` (e.g. the parent
  key in a muted pill with the parent's type color). Keep it compact — one line,
  truncate. Do not add a whole subtree to the board.
- Optionally a tiny "N/M" children indicator on parent cards (an Epic showing
  "2/5"). Config-driven, subtle.

**`BacklogPage.tsx` / table:**
- Add a **Parent** column (parent key, click-through) after Type, and a small
  children-progress indicator for rows that have children. Follows the existing
  "one column per set field" pattern used for custom fields.

**`uiStore.ts`:** extend `openCreateIssue` to accept an optional
`{ parentId, defaultTypeId }` so "create sub-task" pre-fills the modal.

**No `max-w-*` traps / splat-route quirks** apply here (not admin routes), but
the frontend-builder must still avoid the Tailwind `max-w-{2xs..3xl}` shadow
(use inline `maxWidth`) per CLAUDE.md.

---

## 9. DC/Cloud implications

**None beyond schema.** Confirmed:
- No new env var, no profile-gated behavior, no toggle. Hierarchy is a
  first-class product feature identical in DC and Cloud.
- No storage/email/auth/billing surface touched.
- The V2 migration and the seeded Sub-task type ship in the single codebase and
  run identically under both profiles.
- `dc-cloud-guard` should confirm there is nothing to gate here (expected pass).

The only cross-cutting concern is **tenancy** (§3), handled by routing every new
path through the existing workspace-membership resolution and by resolving
candidate parents with `findByIdAndProject` — run `tenancy-reviewer` on the
backend diff (the new `children` endpoint and the parent-resolution paths are
exactly the class it guards).

---

## 10. Acceptance criteria (feeds `test-runner`)

**Migration / catalog**
- [ ] After V2, `issue_types` has `hierarchy_level`; Epic=2, Story/Task/Bug=1,
      Sub-task=0.
- [ ] A `Sub-task` global type exists and is a member of the system-default
      "All types" set (so a fresh project can create Sub-tasks).
- [ ] Hibernate `validate` passes (entity ↔ column parity for `hierarchyLevel`).

**Create**
- [ ] Creating a Story with an Epic parent succeeds; `parentKey` is set.
- [ ] Creating a Story with a Story parent → 422 (level).
- [ ] Creating anything with a Sub-task parent → 422 (level).
- [ ] Creating with a parent id from another project → 422 "Unknown parent".
- [ ] Creating with a parent id from another workspace → 422 (not 403/leak).

**Update**
- [ ] Setting a legal parent via PATCH succeeds and writes a "parent" history
      entry.
- [ ] `clearParent: true` detaches and writes history.
- [ ] Self-parent → 422; direct cycle (A→B, then B→A) → 422; deep cycle → 422.
- [ ] Changing an issue's type so its current parent becomes illegal → 422
      (parent not silently detached).
- [ ] Changing a parent issue's type so existing children become illegal → 422.
- [ ] A stale `version` on a parent-change PATCH → 409.

**Children / roll-up**
- [ ] `GET .../children` returns direct children in board order; 404 for a
      non-member / unknown issue.
- [ ] `IssueResponse.childCount` / `doneChildCount` are correct (only direct
      children; only DONE-category counts as done).
- [ ] An issue with no children returns `0/0` and no parent fields.
- [ ] Board/backlog list responses fill roll-up counts without N+1 (one grouped
      query; assert query count in a test or via logging).

**Delete**
- [ ] Deleting a parent re-homes its children to the grandparent (or null if
      root); no FK error; children survive with a "parent" history entry.
- [ ] Deleting a leaf issue works unchanged.

**Config / frontend (manual QA — no headless browser on this machine)**
- [ ] `config.issueTypes[*].hierarchyLevel` is present.
- [ ] Parent picker only offers issues of a strictly-higher-level type.
- [ ] "Create sub-task" pre-selects the parent and a legal child type; is hidden
      when the project's type set offers no lower-level type.
- [ ] Epic children panel shows the roll-up progress bar and clickable children.
- [ ] Parent chip renders on board + backlog cards for issues with a parent.

**Docs**
- [ ] `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` updated
      (`children` endpoint, `UpdateIssueRequest.parentId`/`clearParent`, new
      `IssueResponse` + `IssueTypeResponse` fields); swagger-cli validates.

---

## 11. Open questions (with recommended defaults)

1. **Add Sub-task to the system-default type set in V2?**
   *Recommended: yes* (§4.2) — otherwise the child type is invisible on fresh
   installs and the feature looks broken. Owner may veto for a strict
   "admin-opts-in" stance.
2. **Reject vs silent-detach on a type change that invalidates a parent/child
   edge?** *Recommended: reject with 422* (§4.4) — no silent data loss. The
   alternative (auto-clear the parent) is friendlier but surprising; can be
   revisited once there's a bulk "reparent" UX.
3. **Delete a parent: re-home to grandparent vs orphan-to-root?**
   *Recommended: re-home to grandparent* (§4.7) — preserves as much structure as
   possible and provably never violates the level rule. Orphan-to-null is the
   fallback only when the deleted issue was itself a root.
4. **Roll-up: direct children only, or full subtree?** *Recommended: direct
   children only* this release — cheaper (one grouped query), predictable, and
   matches the "one edge per hop" model. Full-subtree aggregation is a clear
   future enhancement.
5. **Sub-task icon / color.** *Recommended: lucide `git-branch`, `#64748B`* —
   final call is a `DESIGN.md` decision; the migration author should confirm the
   exact icon name before writing V2.
6. **Should Sub-tasks appear on the board like any other issue?** *Recommended:
   yes, unchanged* — no board filtering by hierarchy this release (keeps scope
   tight; a "hide sub-tasks on board" toggle is future polish).
7. **Batched parent-summary query vs fetch-join `i.parent`?** *Recommended:
   batched second query* (§7.3) to keep the hot board query free of a Cartesian
   join. Builder may benchmark and choose the join if it measures cleaner.

---

## Revision 2 (2026-08-08) — strict adjacency + create-flow fixes

Status: **decided by owner, supersedes the rules cited below.** Author:
systems-analyst. Feeds `backend-builder` / `frontend-builder` / `test-runner`.
The original spec (§1–§11) shipped and is in production; this revision corrects
two real defects the owner found in testing and locks in a hierarchy-model
change. **Where this section conflicts with §4.1/§4.3/§4.4/§4.7/§7.4/§8, this
section wins.** No migration or schema change — `issue_types.hierarchy_level`
already exists and is enough; this is a *rule* change only, plus two bug fixes.

Companion docs to update on ship: none structurally (no API shape change) — but
re-verify the `422` message wording in `openapi.yaml`/`docs/api-*.md` examples if
they quote the old "can't be a child of" text (the messages change below).

### R2.0 What is already true in the codebase (so the builder edits, not rebuilds)

The original level model is fully built. The predicate to change lives in
exactly these spots:
- `IssueService.requireLegalParentLevel(parentType, childType)` — currently
  `parentType.level <= childType.level → 422`. Used by **create** (§4.3) and
  **update** (§4.4, twice: the set-parent guard and the type-vs-retained-parent
  guard).
- `IssueService.update` §4.4.4 children-conflict check — currently filters
  children with `child.level >= effectiveType.level`.
- `IssueService.delete` §4.7 — re-homes children to `issue.getParent()`
  (grandparent) via managed-entity re-point + `saveAll`.
- Frontend `CreateIssueModal.tsx` and `IssueSidePanel.tsx` — both compute
  `eligibleParentTypeIds` as `types.filter(t => t.level > selectedLevel)`.

### R2.1 DECISION: Variant A — STRICT ADJACENCY (supersedes §4.1, §4.8)

An edge **parent → child is legal iff `parent.type.hierarchyLevel −
child.type.hierarchyLevel == 1`** (adjacent tiers only). This replaces the
strictly-greater rule (`parent.level > child.level`) **everywhere it appears.**

With the seeded tiers (Epic = 2; the "task tier" {Story, Bug, Task, and any
future non-Epic/non-Sub-task type} = 1; Sub-task = 0):
- Epic (2) parents **only** the task tier (1). **Epic → Sub-task is now
  forbidden** (gap of 2).
- The task tier (1) parents **only** Sub-task (0).
- Two task-tier types (1 == 1) still cannot parent each other (gap 0).
- Sub-task (0) parents nothing.

**Rationale (owner-locked):** matches the established model where sub-tasks live
only under standard issues, never directly under an epic; and it generalizes
cleanly — an admin adding an "Initiative" tier (3) above Epic yields
Initiative → Epic → task → Sub-task with **no tier-skipping**, which the
strictly-greater rule would have wrongly permitted (Initiative → Sub-task).

**Invariant (supersedes §4.8 bullet 2):** for every edge,
`parent.type.hierarchyLevel − child.type.hierarchyLevel == 1`.

**The single predicate** (all sites call this — no duplicated inequality):
```
legalEdge(parentType, childType) ⇔
    parentType.hierarchyLevel - childType.hierarchyLevel == 1
```

**Corrected 422 messages** (the old message named only the two types; adjacency
needs to convey "must be exactly one tier apart"):
- Set/change parent (create §4.3 rule 2; update §4.4 rule 2):
  `"A {childType} can't be a child of a {parentType} — a parent must be exactly one level above its child"`.
- Type-change makes the retained parent illegal (§4.4.3):
  `"Changing type to {newType} conflicts with its parent {parentKey} — clear the parent or pick a parent exactly one level above"`.
- Type-change makes existing children illegal (§4.4.4):
  `"This issue has children; changing its type to {newType} would make {n} child relationship(s) invalid (a parent must be exactly one level above its children)"`.

### R2.2 Exact changes per referenced section

**§4.1 parent rule** — replace "`parent.type.hierarchyLevel >
child.type.hierarchyLevel` (strictly greater)" with the adjacency predicate
above. The seeded-catalog worked example changes: Epic(2) parents Story/Task/
Bug(1) **but no longer Sub-task(0)**; Story(1) parents Sub-task(0). Everything
else in §4.1 (levels on the catalog, not names in logic) is unchanged.

**§4.3 create** — rule 2 becomes: resolve `parent` and `type`, then
`legalEdge(parent.type, type)` else 422 (set/change message). Rules 1/3/4
unchanged. `IssueService.create` line calling `requireLegalParentLevel(parent
.getType(), type)` keeps its shape; only the predicate inside changes.

**§4.4 update** — three edits:
1. rule 2 (set-parent level guard): `legalEdge(newParent.type, effectiveType)`
   else 422.
2. rule 3 (type change vs retained parent): `legalEdge(effectiveParent.type,
   effectiveType)` else 422.
3. rule 4 (type change vs existing children): a child edge is illegal now when
   it is **not adjacent** to the new type, i.e. filter children by
   `!legalEdge(effectiveType, child.type)` — equivalently `effectiveType.level −
   child.level != 1`. **This is stricter than today's `child.level >=
   effectiveType.level`**: e.g. promoting a Story(1) that parents a Sub-task(0)
   up to an Epic(2) is currently *allowed* (2 > 0) but under adjacency the
   Epic → Sub-task edge is illegal (gap 2), so it must now 422. Keep the "only
   run this block when the type's level actually changed" short-circuit.

The cycle guard (§4.4 rule 4 in the original numbering / `requireNoCycle`) and
the "reads first, mutations last" ordering are **unchanged**.

**§7.4 config endpoint** — no shape change; `hierarchyLevel` already ships.
Frontends must switch from a `>`/level filter to the adjacency filter (R2.4).

### R2.3 DECISION: delete-with-children under adjacency (supersedes §4.7)

§4.7 re-homes each child to the **grandparent** and argues (correctly, under the
old rule) that grandparent-level > child-level held transitively so re-home
never broke the rule. **Under adjacency that argument fails:** the grandparent
is exactly **two** levels above the child (grandparent = deleted+1, deleted =
child+1 ⇒ grandparent = child+2), so re-homing to the grandparent would create a
**gap-2 edge that violates adjacency.** §4.7 must change.

**Decision: on delete, orphan all direct children to root (`parent = NULL`).**
Do **not** re-home to the grandparent. Rationale:
- It is the only option that is *always* adjacency-legal (a root issue has no
  parent edge to validate), with zero conditional logic.
- The alternative "re-home only if grandparent is exactly one tier above the
  child, else orphan" can **never** fire under the current seeded tiers
  (grandparent is always exactly two tiers above the child, by construction of a
  legal tree), so it is dead code that only adds a branch and a subtle invariant
  to maintain if tiers ever become non-uniform. Simplicity wins.
- Orphaning is not data loss: the children survive, keep their type and all
  fields, and become top-level issues the user can re-parent. A "parent" history
  entry (old parent key → null) records exactly what happened.

**Implementation change:** in `IssueService.delete`, set each managed child's
`parent = null` (not `issue.getParent()`), write a "parent" history entry
`oldKey → null` per child, `saveAll`, then delete. The existing managed-entity
re-point + `saveAll` approach (chosen to avoid the stale-L1/`TransientProperty
ValueException` trap in the original §4.7 note) stays — only the target changes
from grandparent to null. The `grandparent` local and its `issueKey` lookup are
removed.

**Edge cases table (§5) corrections:**
- "Delete parent with children" → now reads **"Orphan direct children to root
  (`parent = null`) — never re-home to grandparent (would break adjacency);
  each child gets a 'parent' history entry (old → null)."**
- The §4.7 "Level check on re-home" bullet and its transitivity argument are
  **void** — delete no longer re-homes, so there is nothing to level-check.

**Acceptance (delete):** deleting a Story that has Sub-task children leaves each
Sub-task with `parent == null` (a root), no FK error, no adjacency violation,
one "parent" history row per child (old Story key → null). Deleting a leaf is
unchanged.

### R2.4 Requirement 1 — config-driven create/sub-task type & parent filtering (frontend)

**Bug:** in `CreateIssueModal`, the **Type** `<Select>` renders `issueTypes.map(
...)` — the *full* project type set — even when a parent is preset. It only
*defaults* the type (`presetDefaultTypeId`); it never *restricts* it. So a user
creating a sub-task under a Story sees Epic/Story/Bug/Task/Sub-task and can pick
an illegal one, which then 422s (or, per R2.5, previously 500'd). Fix: make the
Type options config-driven and mutually constrained with the Parent picker, per
adjacency. All rules below are **level-driven off `config.issueTypes[*]
.hierarchyLevel` — no hardcoded type names.**

Define `adjParent(childLevel) = { t ∈ projectTypeSet : t.level == childLevel + 1 }`
and `adjChild(parentLevel) = { t ∈ projectTypeSet : t.level == parentLevel − 1 }`
(both intersected with the project's type set, which `config.issueTypes` already
is).

**Rule F1 — parent preset/selected ⇒ restrict Type.** When a parent is fixed
(the "create sub-task" preset) or selected in the Parent picker, the **Type
`<Select>` options must be exactly `adjChild(parent.level)`** — the illegal types
are **not rendered as options** (not merely non-default). If the current `typeId`
is not in that set, reset it to the first option of `adjChild(parent.level)`.

**Rule F2 — Type selected first ⇒ restrict Parent.** The Parent picker options
must be exactly the issues whose type ∈ `adjParent(type.level)` (i.e.
`issue.type.level == type.level + 1`). This replaces the current
`t.level > selectedLevel` filter in both `CreateIssueModal` and `IssueSidePanel`
(`eligibleParentTypeIds`) with `t.level == selectedLevel + 1`. Result: creating
a Sub-task offers only task-tier parents (Story/Bug/Task), never Epics.

**Rule F3 — "Create sub-task" / "Add child" affordance.** Opening the modal from
a parent fixes that parent and constrains Type to `adjChild(parent.level)` (the
single tier directly below). If that tier has several types, offer all of them
(default to the first by position). **If `adjChild(parent.level)` is empty**
(the project's type set contains no type exactly one level below the parent),
the affordance is **hidden/disabled** — do not open a modal that can't submit.
In `IssueSidePanel`, replace the current `canHaveChildren = issueLevel >
minTypeLevel` with `canHaveChildren = adjChild(issue.type.level).length > 0`
(there exists at least one offered type exactly one tier below), and gate the
"Create sub-task" button on it. The preset default type
(`presetDefaultTypeId`) changes from "highest-level type strictly below the
parent" to "first (by position) type in `adjChild(parent.level)`".

**Rule F4 — mutual clearing when a selection becomes illegal.**
- Changing **Type** so the currently-selected **parent** is no longer adjacent
  (`parent.level != newType.level + 1`) ⇒ clear the parent selection. (Today's
  `handleTypeChange` clears on `parentLevel <= newLevel`; change the condition to
  `parentLevel != newLevel + 1`.)
- Changing the **Parent** so the currently-selected **type** is no longer
  adjacent (`newParent.level != type.level + 1`) ⇒ reset Type to the first option
  of `adjChild(newParent.level)` (per F1). Since F1 already re-derives Type
  options from the parent, this falls out of re-selecting the parent; make it
  explicit so a stale `typeId` never submits.

**`IssueSidePanel` edit form:** apply F2 to `eligibleParentTypeIds`
(`== level+1`) and update `parentConflict` to fire when `parentLevel !=
selectedTypeLevel + 1` (currently `parentLevel <= selectedTypeLevel`). The
"keep the current parent selectable" fallback option (for a parent not yet in
the loaded candidate list) stays, but the save is still blocked by
`parentConflict` if that retained parent is non-adjacent to a just-changed type
— matching the backend 422.

**Acceptance (Requirement 1, manual QA — no headless browser here):**
- [ ] Opening "Create sub-task" from a Story offers **only** Sub-task in the Type
      picker (not Epic/Story/Bug/Task); the parent is fixed to that Story.
- [ ] Opening "Create sub-task" from an Epic offers **only** the task tier
      (Story/Bug/Task) as Type — **never Sub-task** (Epic → Sub-task is illegal).
- [ ] With Type = Sub-task, the Parent picker lists **only** task-tier issues; no
      Epic appears.
- [ ] With Type = Story (task tier), the Parent picker lists **only** Epics.
- [ ] Selecting a parent then changing Type to a non-adjacent type clears the
      parent (and vice-versa resets Type), so no illegal pair can ever be
      submitted from the UI.
- [ ] In a project whose type set omits Sub-task, the "Create sub-task"
      affordance on a Story is hidden/disabled (no unsubmittable modal).
- [ ] `IssueSidePanel` edit: choosing a non-adjacent (type, parent) pair shows
      the inline conflict message and disables Save.

### R2.5 Requirement 2 — create-with-parent must be atomic; no 500-then-duplicate

**Reported defect (#2):** creating a sub-task returned HTTP 500 but the issue was
**actually persisted**; the user retried and got **two** issues. **Strong
hypothesis:** `IssueService.create` `save()`s the issue, then builds the response
via `toResponse(issue)`, which for a child issue navigates the parent proxy
(`issue.getParent().getType().getId()`, `issueKey(parent)`) and runs the child
count queries. If any of that assembly — or the subsequent Jackson serialization
of the response, which crosses the Jackson 2/3 boundary (see CLAUDE.md) — throws,
the client sees 500. Because the JPA insert has already been flushed by the count
queries' auto-flush (and, depending on where the throw lands, the transaction may
still commit before the failure surfaces during response serialization *outside*
the `@Transactional` boundary), the row survives — so a retry double-creates.

**Required behavior (invariant):** a create request is atomic from the client's
view — **either `201` with a fully-assembled `IssueResponse`, or a clean `4xx`
with nothing persisted.** "Persisted + 500 during response assembly" is a
defect, never acceptable.

**Backend requirements:**
1. **Response assembly must not be able to throw after the point of no return.**
   Structure `create` so that everything that can fail on a child issue —
   resolving/navigating the parent, computing `parentKey`/`parentTitle`/
   `parentTypeId`, and the roll-up counts — runs **inside the same
   `@Transactional` method, before the method returns**, so any failure rolls the
   insert back (the client gets a 4xx/5xx *and* no row). This is already the
   structure; the fix is to guarantee the parent navigation cannot NPE/throw:
   the parent was just resolved via `resolveParent(...)` and set on the managed
   issue, so `issue.getParent()` is a managed entity in the same session —
   `toResponse` must read `parent.getType()`, `parent.getProject().getKey()`,
   `parent.getTitle()`, `parent.getNumber()` off that managed entity without a
   second lookup. Verify no lazy association touched in assembly is un-fetchable
   (type/project are `ToOne` and loadable within the tx).
2. **Serialization failures must not leave a committed row.** The known Jackson
   2/3 boundary hazard (custom-field `JsonNode` values, handled by
   `Jackson2NodeModule`) means response serialization can 500 *after* commit.
   Because MVC serializes the returned DTO **after** the service transaction
   commits, a serialization throw cannot roll back the insert. **Mitigation
   requirement:** the `IssueResponse` returned from `create` must be fully
   materialized POJO data (it already is — records of primitives/strings/UUIDs
   plus `FieldValueResponse` whose `JsonNode` values go through the bridge
   module), so serializing it must succeed for any issue that can be created.
   Add a create-path test that round-trips a child issue **carrying a custom
   field value** through the real MVC serializer (MockMvc), asserting `201` and a
   parseable body — this is the exact combination (has-parent + JSONB field) most
   likely to hit the boundary.
3. **No partial state on any create failure.** Any 4xx from validation (unknown
   parent, illegal adjacency, unknown type/status, missing required field) must
   leave **zero** rows — assert issue count unchanged after a rejected create.

**Frontend requirement (double-submit guard — hard requirement):** the Create
button must be disabled for the entire in-flight request. `CreateIssueModal`
already sets `saving=true` and passes `loading={saving}` to `Button`, and
`Button` sets `disabled={loading ?? props.disabled}` — so during the request the
button *is* disabled. **But note the pre-existing latent bug:** `loading ??
props.disabled` means when `saving===false`, `disabled` resolves to `false` and
the `title/project/type/missingRequired` guards on the `disabled` prop are
**ignored** (nullish-coalescing only falls through on null/undefined, and
`saving` is a boolean `false`). The builder must fix `Button` to
`disabled={loading || props.disabled}` (OR, not `??`) so both the in-flight guard
**and** the validity guards hold; otherwise the modal can be submitted with an
empty title. Additionally, guard against a double-fire at the call site:
`submit` should early-return if `saving` is already true. These together
guarantee **one user action ⇒ at most one create request**.

**Acceptance (Requirement 2, feeds `test-runner`):**
- [ ] `POST issues` creating a **child** (sub-task under a non-root task-tier
      issue, e.g. Sub-task under a Story that itself has an Epic parent) returns
      **`201`** with a body whose `parentKey`/`parentTitle`/`parentTypeId` are
      populated and `childCount`/`doneChildCount` are correct — and **never
      `500`**.
- [ ] Same, where the created child's parent has **no** parent of its own
      (Sub-task under a root Story) — 201, correct parent fields.
- [ ] Create a child that **also carries a custom field value** (JSONB) → 201
      and the MVC-serialized body parses (exercises the Jackson 2/3 boundary on
      the has-parent response path).
- [ ] A create rejected for **any** reason (illegal adjacency per R2.1, unknown
      parent, missing required field, unknown type) leaves the project's issue
      count **unchanged** (no orphan row); a subsequent valid retry creates
      **exactly one** issue.
- [ ] Regression: two rapid create submits of the same form (simulating a
      double-click) result in **exactly one** persisted issue — enforced by the
      disabled-while-saving guard (unit/interaction test or code review of the
      `Button` `disabled={loading || props.disabled}` fix + `submit` re-entry
      guard).

### R2.6 Adjacency acceptance additions (supersede the §10 "strictly greater" tests)

Replace the §10 Create/Update level assertions with these (the old
"strictly-greater" phrasing is void):
- [ ] Create Story with Epic parent → 201 (adjacent: 2−1==1).
- [ ] Create Sub-task with **Epic** parent → **422** (gap 2 — was legal under the
      old rule; must now fail).
- [ ] Create Sub-task with Story parent → 201 (adjacent).
- [ ] Create Story with Story parent → 422 (gap 0).
- [ ] Create anything with a Sub-task parent → 422 (Sub-task parents nothing).
- [ ] Update: set a Sub-task's parent to an **Epic** → 422 (gap 2).
- [ ] Update: promote a Story that parents a Sub-task to **Epic** → 422 (§4.4.4:
      the resulting Epic → Sub-task child edge is non-adjacent; was allowed under
      strictly-greater).
- [ ] Update: change a task-tier issue's type across the tier (Story→Bug, both
      level 1) with an Epic parent → 201 (still adjacent; parent unaffected).
- [ ] Delete a Story with Sub-task children → each child ends with `parent ==
      null` (orphaned to root), no FK error, one "parent" history row per child
      (old → null).

### R2.7 DC/Cloud, tenancy, migration

- **No migration, no schema change** — `hierarchy_level` already exists with the
  seeded values (Epic=2, task tier=1, Sub-task=0). This is purely a predicate +
  two-bug-fix change. Confirm Hibernate `validate` still passes (unchanged
  mapping).
- **No DC/Cloud gating** — adjacency is a uniform product rule in both modes; no
  env var, no profile branch (`dc-cloud-guard` expected pass).
- **Tenancy** — unchanged; no new endpoints, no new queries touching
  workspace scope. `resolveParent` still uses `findByIdAndProject`, so a foreign
  parent id still 422s as "Unknown parent issue" (no existence leak).

### R2.8 Highest-risk assumption in this revision

That making §4.4.4 (type-change children conflict) **stricter** under adjacency
won't surprise existing production data. Trees created under the old
strictly-greater rule could contain a **gap-2 edge that adjacency now considers
illegal** (e.g. a Sub-task attached directly to an Epic, which the old create
rule permitted). This revision does **not** retro-validate or migrate existing
edges — they remain in place and render fine; adjacency is enforced only on
**new** create/update/type-change operations. The one visible consequence: an
existing gap-2 edge cannot be "touched" by a type change without hitting the new
422, and the UI parent pickers won't offer to recreate it. **Recommendation:**
ship as-is (enforce-forward, don't migrate) and add a note to the release; a
one-off "find gap-2 edges" query can be run later if the owner wants to clean
legacy data. Flagged so the owner can veto before build if retro-cleanup is
desired.
