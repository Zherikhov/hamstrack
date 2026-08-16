# Labels, Components & Versions (fix/affects) — Implementation Spec

Epic **HD-6** · Stories **HD-30** (Labels) → **HD-31** (Components) → **HD-32** (Versions + release page)
Status: **draft 2026-08-15** — written for `backend-builder` / `frontend-builder` / `test-runner`.
Author: systems-analyst. Autopilot policy: build may proceed; owner vetoes after.

**Supersedes** `docs/design/issue-labels-proposal.md` (draft 2026-08-06). That document predates the
schema squash (it targets `V12`; the chain now ends at `V7`), predates `WorkspaceAccessService`,
and predates the V3 system-field seeding that collides with this epic. Section 4 below carries its
still-valid reasoning forward; where the two disagree, **this document wins**.

Companion docs to update on ship: `src/main/frontend/public/openapi.yaml`, `docs/api-cloud.md`,
`docs/api-dc.md`, `docs/project-state.md`, `docs/self-hosting.md` (config table),
`.env.prod.example`. **Not** `README.md` — it has no env table and delegates to the self-hosting
guide.

---

## 1. Problem & goal

Issues today can only be classified along the governed taxonomy (type / status / priority) and the
admin-configured custom-field sets. There is no lightweight cross-cutting tag, no notion of "which
module of the product does this belong to", and no way to say "this ships in 2.4.0" or "this bug
exists in 2.3.1". Teams migrating from another tracker lose their triage vocabulary on day one, and
there is no release-readiness view at all. V3 papered over the gap by seeding three **empty**
placeholder custom fields (`labels` MULTI_SELECT, `components` MULTI_SELECT, `fix_version` SELECT)
whose option lists an admin must hand-maintain per install — unusable at any real scale, and
invisible to reporting.

**Goal:** three first-class, queryable, filterable classification primitives —

- **Labels** — free-form, colored, **workspace**-scoped, many-per-issue, self-serve.
- **Components** — curated **project** modules with an optional lead and optional auto-assign,
  one-per-issue.
- **Versions** — **project** release targets with a lifecycle (unreleased → released, reversible),
  linked to issues as **fix version** and/or **affects version**, plus a Releases page showing
  per-version progress.

**Success looks like:** a user can tag an issue in two keystrokes from the create dialog, filter a
board by "component = Billing AND label = customer-reported", write
`fixVersion = "2.4.0" AND status != "Done"` in the search bar, save that as a filter, and open the
Releases page to see 18/24 done before flipping 2.4.0 to released.

**Non-copy statement.** Jira is the capability benchmark, not the implementation. Deliberate
divergences: labels are **colored, curated first-class rows in a workspace namespace** (Jira: a
single global uncurated string namespace that becomes typo soup — "techdebt"/"tech-debt"/"TechDebt"
— with no colors and no rename), label names **may contain spaces** (Jira forbids them), a component
has **one optional lead + an explicit `auto_assign` boolean** (Jira: a 4-way "default assignee"
enum inherited from a project-lead concept we do not have), and versions have **no "Merge/Move"
modal and no start-date/overdue machinery** — release, un-release, archive, and an optional
"move unresolved to" on release. No Jira naming, markup, screens or scheme mechanics are reproduced.

---

## 2. Scope

### In scope (this epic, three independently-shippable slices)

| Slice | Ships |
|---|---|
| **HD-30** | `labels` + `issue_labels`; workspace label CRUD/archive/merge/delete; `labelIds` on issue create/update; label chips on cards/detail/backlog; board+backlog label filter; HQL `label`; workspace-settings Labels page |
| **HD-31** | `components`; `issues.component_id`; project component CRUD/archive/delete; component on create/edit; optional auto-assign to lead; board+backlog component filter; HQL `component`; project-settings Components tab |
| **HD-32** | `versions` + `issue_versions` (FIX/AFFECTS); project version CRUD/archive/delete; release + un-release; fix/affects pickers; fix-version filter; HQL `fixVersion`/`affectsVersion`; **Releases page** with per-version progress |

Each slice is shippable alone: HD-31 does not depend on HD-30, HD-32 does not depend on HD-31. The
shared plumbing each slice touches (issue DTOs, issue list filter, HQL registry, batch loaders) is
additive in all three.

### Out of scope / non-goals

- **Label sets / component sets / version sets and project bindings.** These are *content*, not
  bound taxonomy — see §3.2. `ProjectConfigService` is not touched by this epic.
- **Cross-project versions** ("release trains" spanning projects). Versions are project-scoped.
- **Version dependency/ordering semantics** ("2.4.0 supersedes 2.3.x"), release notes generation,
  changelog export, CI/deployment integration.
- **Component-based permissions or notification routing** ("watch this component").
- **Bulk label/component/version edit across many issues** (a general bulk-edit surface is its own
  ticket). The label **merge** operation is the one deliberate exception, because it is the only
  cure for the tag-soup failure mode.
- **Multi-valued components.** The ticket specifies `issues.component_id` — one per issue. §5.C
  records the forward-compatible path if that changes.
- **Label/component/version-driven automation, SLAs, or reports.** Reports are a separate SOON item.
- **Sprints.** `sprint` remains an (empty) system custom field; iterations are a different epic.

---

## 3. Cross-cutting decisions (apply to all three slices)

### 3.1 Tenancy — the non-negotiable part

Every new endpoint lives under `/api/workspaces/{workspaceId}/…` and resolves through
`WorkspaceAccessService`:

- labels → `workspaceAccess.requireMember(actor, wsId)`
- components / versions → `workspaceAccess.requireProjectMember(actor, wsId, projectId)`

A missing workspace/project and a caller who is not a member both yield **404**
(`WorkspaceNotFoundException` / `ProjectNotFoundException`) — never 403. 403 is reserved for a
*member who lacks the role* (`InsufficientWorkspaceRoleException` /
`InsufficientProjectRoleException`), exactly as `ScopeResolver` already does.

**No repository method may take a bare id.** Every lookup is `findByIdAndWorkspace` /
`findByIdAndProject`. When an issue payload references a label/component/version id, it is resolved
**through the issue's own workspace/project** and a foreign or unknown id yields **422**
("Unknown label") — the same shape `IssueService.resolveAssignee` and `resolveParent` already use.
422 (not 404) because it is an invalid field value inside a request the caller is otherwise
entitled to make, and it leaks nothing about the other tenant.

Denormalized `workspace_id` is carried on `components` and `versions` (as `issues` already does)
purely so a tenant-scoped query never has to join through `projects`.

### 3.2 These are content, not bound taxonomy — `ProjectConfigService` is untouched

The catalog → set → project-binding shape exists because statuses/priorities/types/fields are
**governance primitives an operator curates once and reuses across projects**. Labels, components
and versions are the opposite:

- there is no meaningful "which labels does this project offer" question — a team wants its labels
  everywhere in its workspace;
- components and versions are *owned by exactly one project* and are worthless to another;
- versions have a lifecycle (released) that no bound set models;
- all three are high-cardinality and edited by working users, not admins-once-a-quarter.

Forcing them through catalog+binding would add two indirection layers with zero reuse payoff — the
exact anti-pattern `admin-console-proposal.md` warns about. **Decision: none of the three enters
`ProjectConfigResponse`, and `ProjectConfigService` gains no methods.** The SPA fetches them from
dedicated endpoints and caches them under their own query keys. This also keeps the config
response (fetched on every board render) from being invalidated by every label recolor.

They are equally **not** `Scoped` catalog rows: labels use a plain `workspace_id NOT NULL` (like
`saved_filters`), not `scope_workspace_id`/`scope_project_id`. There is no global label tier and
there must never be one — a global label catalog would surface one Cloud tenant's vocabulary to
another.

### 3.3 Permissions model

| Action | Required |
|---|---|
| Read labels / components / versions | any **workspace member** (components/versions additionally require the project to resolve within that workspace) |
| Attach/detach any of them on an issue | anyone who can edit the issue (workspace member + project not archived) — same gate as `assigneeId` today |
| **Create** a label | any **workspace member** (self-serve; see §4.3) |
| Rename / recolor / describe a label | workspace **OWNER/ADMIN**, or the label's `created_by` |
| Archive / unarchive / merge / delete a label | workspace **OWNER/ADMIN** |
| Create / edit / archive / delete a **component** or **version** | project **MANAGER** *or* workspace **OWNER/ADMIN** |
| Release / un-release a version | project **MANAGER** *or* workspace **OWNER/ADMIN** |

The "MANAGER **or** workspace OWNER/ADMIN" rule needs one new helper on
`admin.scope.ScopeResolver`:

```java
/** Project MANAGER, or an OWNER/ADMIN of the enclosing workspace (who may not be a project member). */
Project requireProjectCurator(User actor, UUID workspaceId, UUID projectId)
```

Semantics: `workspaceAccess.requireProjectMember` first (404 for missing workspace/project or
non-member of the workspace); then pass if the workspace membership role is ≥ ADMIN, else if the
project membership role is ≥ MANAGER; else **403** `InsufficientProjectRoleException`. Rationale: a
workspace admin already edits that project's *bindings* through
`PATCH /workspaces/{ws}/admin/projects/{p}/bindings` without being a project member; refusing them
the component list would be arbitrary. `SystemRole.ADMIN` gets nothing extra here — instance admins
act through their workspace membership, as they do for every workspace-scoped resource.

### 3.4 Retiring the V3 placeholder custom fields

V3 seeded three global **system** `field_defs` this epic replaces: `labels` (MULTI_SELECT),
`components` (MULTI_SELECT), `fix_version` (SELECT) — all with `{"options": []}`.

**Decision: archive, never delete.** Each slice's migration stamps `archived_at = NOW()` on its
counterpart:

```sql
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'labels' AND archived_at IS NULL;
```

Consequences (all already-implemented behavior, no new code):
- archived fields disappear from pickers and from `ProjectConfigResponse.fields`;
- any values an install already stored survive in `issue_field_values` and still render
  (`FieldValueService` renders archived fields' existing values);
- archived fields are excluded from `ResolutionContextFactory`, so the HQL keys `labels` /
  `components` / `fix_version` free up for the first-class fields — no clash with the
  "a system field always wins" rule.

Do **not** attempt to migrate option values into the new tables: the seeded option lists are empty
out of the box, so on a stock install there is nothing to migrate, and on a hand-populated install
the option ids carry no reliable mapping. Release note: "the placeholder Labels/Components/Fix
version custom fields are archived and replaced by first-class ones; if you populated their
options, re-enter them in the new surfaces."

### 3.5 HQL / saved-filter integration pattern

All three become live `FieldRegistry` entries (`available = true`). The reserved not-available
`label` stub already in `FieldRegistry` is *activated* by HD-30, not replaced.

| HQL name (aliases) | `FieldDataType` | Ops | IN | `IS EMPTY` | Sortable | `valueSuggest` |
|---|---|---|---|---|---|---|
| `label` (`labels`) | **`LABEL_REF`** (new) | `=` `!=` | yes | yes | no | `LABEL` |
| `component` (`components`) | `ENUM_REF` | `=` `!=` | yes | yes | **yes** (`component.name`) | `COMPONENT` |
| `fixVersion` (`fixversion`) | **`VERSION_REF`** (new) | `=` `!=` | yes | yes | no | `VERSION` |
| `affectsVersion` (`affectsversion`) | `VERSION_REF` | `=` `!=` | yes | yes | no | `VERSION` |

Registry keys are canonical lowercase; `FieldRegistry.find` already lowercases, so `fixVersion`
typed by a user resolves. Aliases are registered as additional map entries pointing at the same
descriptor (add a `register(alias, descriptor)` overload; `availableFields()` must de-duplicate by
descriptor identity so `/schema` lists each field once).

**Compilation** (`HqlCompiler`):
- `component` is a plain ToOne column — it needs no new code path beyond `entityPath =
  "component.id"` and a `sortPath` case (`root.get("component").get("name")`).
- `LABEL_REF` / `VERSION_REF` are many-valued, so they compile to a **correlated EXISTS** over the
  join entity, exactly like the HD-52 custom-field path:

```
label = "x"      → EXISTS (SELECT 1 FROM IssueLabel il WHERE il.issue = <outer> AND il.label.id IN :ids)
label != "x"     → NOT EXISTS (same)
label IN (a,b)   → EXISTS (… il.label.id IN :allIds)
label IS EMPTY   → NOT EXISTS (SELECT 1 FROM IssueLabel il WHERE il.issue = <outer>)
fixVersion = "x" → EXISTS (… vl.version.id IN :ids AND vl.linkType = 'FIX')
```

All operands stay **bound Criteria parameters**; `SearchScope.scopePredicate` remains the outermost
conjunction, so no new path can widen the tenant boundary.

**Name resolution** (`ResolutionContext` / `ResolutionContextFactory`): add
`labelIdsByName` + `labelNames` (from the **workspace's** labels — the workspace *is* the tenant
boundary), and `componentIdsByName`/`componentNames`, `versionIdsByName`/`versionNames` built from
the **visible project set** only (`searchScope.visibleProjectIds`), so a name never resolves
through a project the actor cannot see. A name maps to a *list* of ids (two projects may both have
a "Billing" component) — identical to how statuses already work. Archived rows are excluded from
name resolution but issues carrying them still match by id, matching §6.1 of the search proposal.
`HqlValueResolver.resolveEnum` gains a `case "component"`; a new `resolveLabel` / `resolveVersion`
mirrors it for the two new data types.

**`/schema`**: `values` gains `LABEL`, `COMPONENT`, `VERSION` picklists, each **capped at 200
entries** (a workspace can have thousands of labels); when a picklist is truncated the client falls
back to `/suggest?field=label|component|fixVersion&q=`, which each slice extends with its own
bounded (≤20) prefix search over the same scoped sets.

**Saved filters** need no change: save-time validation is parse + structural only, so
`label = "tech-debt"` validates without resolving, and a later-deleted label simply yields a
run-time 422 "No label named 'tech-debt' in this workspace" — the documented, intended behavior.

### 3.6 Board / backlog filtering is server-side

The board is server-capped at `app.board.max-issues` (HD-79). The HD-43 quick filters are
client-side over the already-loaded page, which is honest for "narrow what's on screen" but would
be **wrong** for a classification filter: "component = Billing" must search the whole project, not
the first `cap` issues. So — like the existing priority filter — the three new filters are **query
parameters** on `GET …/issues`, and the client-side HD-43 chips stay as they are.

New params on the existing endpoint (all optional, all AND-ed with the existing ones):

```
?labelId=<uuid>&labelId=<uuid>&labelMatch=any|all      # HD-30, default any
?componentId=<uuid>                                     # HD-31
?fixVersionId=<uuid>                                    # HD-32
```

JPQL fragments to add to `findByProjectFilteredCapped`, `countByProjectFiltered`,
`findByProjectFilteredPaged` (and the plain `findByProjectFiltered`):

```jpql
AND (:labelCount = 0 OR (SELECT COUNT(DISTINCT il.label.id) FROM IssueLabel il
                          WHERE il.issue = i AND il.label.id IN :labelIds) >= :requiredMatches)
AND (:componentId IS NULL OR i.component.id = :componentId)
AND (:fixVersionId IS NULL OR EXISTS (SELECT 1 FROM IssueVersionLink vl
                                       WHERE vl.issue = i AND vl.version.id = :fixVersionId
                                         AND vl.linkType = :fixType))
```

`requiredMatches` = `1` for `any`, `labelCount` for `all`. **Trap:** an empty `IN` list is invalid
in JPQL/Hibernate — when no labels are selected the service must pass `labelIds =
List.of(new UUID(0,0))` (a sentinel that matches nothing) together with `labelCount = 0`, so the
first disjunct short-circuits. Do not pass an empty list.

### 3.7 Issue payload & history conventions

`IssueResponse` gains (per slice):

```
labels:         [{ id, name, color, archived }]     // HD-30, [] when none
component:      { id, name, archived } | null       // HD-31
fixVersions:    [{ id, name, released, archived }]  // HD-32
affectsVersions:[{ id, name, released, archived }]  // HD-32
```

`CreateIssueRequest` / `UpdateIssueRequest` gain `labelIds`, `componentId`, `fixVersionIds`,
`affectsVersionIds` plus `clearComponent` (Boolean). Conventions, matching what the codebase
already does:

- **Collections are full-replacement sets when present, unchanged when absent.** The picker always
  holds the whole set, so deltas would only invite drift. Duplicates de-duped; `[]` clears.
- **`componentId` is a nullable scalar** → follows the `assigneeId` / `clearAssignee` pattern:
  a non-null id sets it, `clearComponent: true` unsets it. It **must be a boxed `Boolean`** with a
  compact-constructor `null → false` coalesce — a primitive `boolean` makes every PATCH that omits
  it 400 under Jackson 3 (`FAIL_ON_NULL_FOR_PRIMITIVES`; this exact bug already bit
  `clearAssignee`).
- **Ordering rule:** resolve every label/component/version **before** mutating the issue, then apply
  and `save` last. Mutating first and querying after triggers a Hibernate AUTO flush and the
  `@Version` double-bump.
- **History** entries (field names fit `issue_history.field VARCHAR(50)`):
  `labels`, `component`, `fixVersions`, `affectsVersions`; old/new = comma-joined display names
  (null when empty). Only written when the diff is non-empty. No history for values set at create
  time (consistent with custom fields).
- **`changeSet`** on the `IssueUpdated` event carries the same `FieldChange` entries — SSE payload
  shape is unchanged.
- **Batch loading, no N+1:** `IssueService.toResponses(List<Issue>)` must load labels and version
  links for the whole page in one query each, keyed by issue id (mirror
  `FieldValueService.valuesByIssue`). `component` is a ToOne and joins into the existing
  `LEFT JOIN FETCH` block of the board/backlog queries and `HqlCompiler.buildPageQuery`.

### 3.8 Migrations & numbering

Chain currently ends at `V7__failed_email.sql`. This epic adds, in build order:

| # | File | Slice |
|---|---|---|
| V8 | `V8__labels.sql` | HD-30 |
| V9 | `V9__components.sql` | HD-31 |
| V10 | `V10__versions.sql` | HD-32 |

All **purely additive** — no data reset, no edits to an applied migration. Rules honored
throughout: `VARCHAR(n)` (never `CHAR`/`CREATE TYPE … AS ENUM`), `TIMESTAMPTZ`, UUID PKs generated
by the app (`@UuidGenerator(TIME)`), `created_at`/`updated_at` with `DEFAULT NOW()` plus the
existing `set_updated_at()` trigger as a safety net (entities still use `@CreatedDate` /
`@LastModifiedDate`).

**Join tables get a surrogate `id UUID PRIMARY KEY` + a `UNIQUE` business key**, not a composite
PK — that is the established shape of every join table in this schema (`workflow_statuses`,
`field_set_items`, `comment_mentions`, `issue_field_values`) and it lets the entities extend
`CreatedOnlyEntity`. (This overrides the `@EmbeddedId` sketch in the superseded labels proposal.)

**Join tables also carry a denormalized `workspace_id UUID NOT NULL` and reference BOTH parents
through composite foreign keys that include it.** This is a **decision made by the project owner**
(HD-30 fix round 2, on the tenancy reviewer's L-1 recommendation), **binding on all three slices** —
`issue_labels` (V8), the HD-31 join table (V9) and `issue_versions` (V10) — not a suggestion to
weigh per slice:

```sql
-- every join table of this epic, verbatim shape
    workspace_id UUID NOT NULL,
    CONSTRAINT <table>_issue_fk  FOREIGN KEY (issue_id,  workspace_id)
        REFERENCES issues (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT <table>_<parent>_fk FOREIGN KEY (<parent>_id, workspace_id)
        REFERENCES <parent> (id, workspace_id) ON DELETE CASCADE
```

Rationale: cross-tenant leakage is this project's highest-severity bug class, and without this the
"both sides live in the same workspace" invariant is 100% service-enforced — one forgotten
`…AndWorkspace` away from a leak. With the composite FKs a row pairing one tenant's issue with
another tenant's label/component/version is **unrepresentable**, and a wrong `workspace_id` fails
loudly at INSERT instead of leaking quietly. Consequences to honor in each slice:

- Both parent tables need an explicitly named `UNIQUE (id, workspace_id)` (Postgres requires a
  unique constraint on the exact referenced column list). `issues` gets
  `issues_id_workspace_id_key` **once**, in V8 — V9/V10 reuse it, they must not re-add it. Each new
  parent table (`labels`, components, versions) declares its own inline, e.g.
  `labels_id_workspace_id_key`.
- Do **not** also declare the single-column FKs to the parents: each composite FK already proves its
  parent row exists, so a second FK is only a redundant RI trigger per insert. `ON DELETE CASCADE`
  rides on the composite FKs.
- The entity maps `workspace_id` (`@ManyToOne(LAZY) @JoinColumn(name = "workspace_id",
  nullable = false)`) and **every** write path stamps it from the **issue's** workspace. Funnel row
  construction through a single private factory so it cannot be forgotten.
- **All three referencing columns must be `NOT NULL`.** Composite FKs default to `MATCH SIMPLE`,
  which skips the check entirely when *any* referencing column is NULL — a nullable `workspace_id`
  would silently reopen the exact hole these FKs exist to close.
- The FKs are `NO ACTION` on **update**, so an `UPDATE issues SET workspace_id = …` on an issue that
  has join rows now fails. No such feature exists (an issue's workspace is set once, at creation),
  but any future issue/project relocation must re-point the join rows in the same statement or add
  `ON UPDATE CASCADE`. Note this before building one.
- No extra index on the join table's `workspace_id`: it is never a standalone predicate (reads are
  keyed by issue or by the other parent) and both cascade paths are served by the leading column of
  an index that already exists.
- **Deployment note, recorded so it is not re-derived.** When the composite FK is added to an
  *existing populated* table (V9's `issues_component_fk`, and V8's `UNIQUE (id, workspace_id)` on
  `issues`), the `ADD CONSTRAINT` is a separate statement from the `ADD COLUMN`, so PostgreSQL runs
  a full validation scan even though the new column is definitionally all-NULL — `SHARE ROW
  EXCLUSIVE`, which blocks DML on `issues` for its duration. That is free today: DC and Cloud both
  deploy a single app container, so migrations run inside an existing downtime window with no
  concurrent writers. **It stops being free the moment Cloud moves to a rolling deploy**, because
  the old instance keeps writing while the new one migrates. The fix at that point is
  `ADD CONSTRAINT … NOT VALID` followed by a separate `VALIDATE CONSTRAINT` (which takes only
  `SHARE UPDATE EXCLUSIVE` and permits concurrent DML) — in a **new** migration covering V8 and V9
  alike, never as a retrofit into an applied one. Note `CREATE INDEX CONCURRENTLY` is not a drop-in
  substitute: Flyway wraps each migration in a transaction. A join table created empty in the same
  migration (V10's `issue_versions`) has nothing to validate and is unaffected.

### 3.9 DC / Cloud

Zero behavioral fork. All three features are workspace/project-scoped, membership-guarded, and use
no storage/email/auth/billing surface — a self-hosted single-workspace install and a Cloud tenant
run byte-identical code. No profile-conditional beans, no `application-{dc,cloud}.properties`
divergence.

**New properties, all base-level with identical defaults in both modes** (they are DoS guards,
not mode switches), read via a new `common.config.ClassificationProperties`
(`@ConfigurationProperties("app.classification")`, mirroring `BoardProperties`). All are
`@Validated` with `@Min`/`@Max`: an out-of-range value **aborts startup**, never clamps.

| Property | Env | Default | Enforced |
|---|---|---|---|
| `app.classification.max-labels-per-issue` | `MAX_LABELS_PER_ISSUE` | `20` | HD-30, 422 |
| `app.classification.max-labels-per-workspace` | `MAX_LABELS_PER_WORKSPACE` | `1000` | HD-30, 422 on `POST /labels` |
| `app.classification.max-components-per-project` | `MAX_COMPONENTS_PER_PROJECT` | `500` | HD-31, 422 on `POST /components` |
| `app.classification.max-version-links-per-issue` | `MAX_VERSION_LINKS_PER_ISSUE` | `20` | HD-32, 422 per link type |
| `app.classification.max-versions-per-project` | `MAX_VERSIONS_PER_PROJECT` | `500` | HD-32, 422 on `POST /versions` (archived + released rows counted) |

The three *catalog* caps exist because creation is effectively self-serve in every case: any member
may create a label, and any user may create their own workspace and is thereby OWNER — hence
project curator — of every project in it. `ResolutionContextFactory` loads each visible project's
whole component catalog and every version name (and the workspace's whole label catalog) on every
`/search`, `/search/schema` and `/search/suggest`, so on shared Cloud infrastructure an unbounded
catalog is CPU and heap paid by co-resident tenants, not just by the offender. The version cap was
added in HD-32 fix round 1: "a release plan is hand-curated and has a natural ceiling" describes
intended usage, not a bound, and `GET …/versions` is unpaged with a grouped progress query batched
at 500, so an unbounded catalog is hundreds of round-trips out of one GET. Batching converts a hard
failure into unbounded work — an argument *for* a ceiling, not a substitute for one.

Wiring checklist (`dc-cloud-guard` will check all four): `application.properties` →
`docker-compose.prod.yml` needs **no** change (the `app` service pulls the whole operator `.env`
via `env_file`) → `.env.prod.example` (commented, with the default) → the `docs/self-hosting.md`
config table (**not** `README.md` — it has no env table and delegates to the self-hosting guide) →
the `docs/api-dc.md` "Operator settings that affect the API" table, since the cap turns a
valid-looking payload into a 422.

**No feature flag.** These are core tracker primitives; an operator kill-switch would create a
second, untested code path. If one is ever demanded, the pattern is `app.labels.enabled` surfaced
through `GET /api/meta` — do not build it speculatively.

**Demo data** (`common.seed.DemoDataService`, gated by the existing
`app.demo.seed-on-first-login`): each slice seeds a small starter set in the demo project so the
feature is not empty on first look — 4 labels, 3 components, 2 versions (one released). All of it
cascades away with the workspace, so the documented test-mode reset block needs no change.

---

## 4. HD-30 — Labels

### 4.1 Behavior

Labels are **workspace-scoped**, colored, many-per-issue, reusable across every project of the
workspace (the story's acceptance criterion). Workspace scope is the right grain because it matches
the tenant boundary exactly (natural 404-on-non-member), gives cross-project reuse, and keeps each
tenant's vocabulary isolated. Project-private labels are explicitly **not** built and no reserved
column is added for them — if that need ever appears, it is a new nullable `project_id` column plus
a resolver change, and speculating now costs more than it saves.

**Name normalization** (applied server-side on create and rename, before validation and uniqueness):

1. Unicode-normalize to NFC; strip C0/C1 control characters.
2. Trim leading/trailing whitespace; collapse internal whitespace runs to a single space.
3. Reject empty result → **400**.
4. Reject length > 60 after normalization → **400**.
5. Spaces **are allowed** (deliberate divergence from Jira). No other character class is banned.
6. **Store the normalized string with the user's casing preserved.** Uniqueness is enforced
   case-insensitively per workspace (`UNIQUE (workspace_id, lower(name))`), so "Tech Debt" and
   "tech debt" cannot coexist, but whichever was created first keeps its display casing.

**Colors:** `#RRGGBB` or `#RRGGBBAA`, validated `^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$` → 400 otherwise.
On-the-fly creates that omit a color get one assigned by `Math.abs(hash(lower(name))) % palette.length`
from a fixed 8-swatch palette built from DESIGN.md tokens (teal `#0EA5A4`, amber `#F79009`, slate
`#667085`, error `#F04438`, success `#12B981`, violet `#7C6CF5`, blue `#3B5BFD`, yellow `#EAB308`)
— deterministic, so the same name gets the same color and the list looks intentional. Recolor is
always free (no stored value depends on a label's color).

**Rename is safe and cheap** because issues reference labels by **id**, not by string — the
structural fix for Jira's rename-is-impossible problem.

### 4.2 Data model — `V8__labels.sql`

```sql
CREATE TABLE labels (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL,
    color        VARCHAR(9)   NOT NULL DEFAULT '#667085',   -- #RRGGBB or #RRGGBBAA
    description  VARCHAR(200),
    created_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Referenced by issue_labels' composite FK (§3.8); PG requires a UNIQUE on the
    -- exact referenced column list, so (id) alone is not enough
    CONSTRAINT labels_id_workspace_id_key UNIQUE (id, workspace_id)
);

CREATE TRIGGER trg_labels_updated_at
    BEFORE UPDATE ON labels
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Case-insensitive uniqueness inside one workspace (a plain UNIQUE can't lower()).
CREATE UNIQUE INDEX labels_workspace_name_uk ON labels (workspace_id, lower(name));
CREATE INDEX idx_labels_workspace ON labels (workspace_id) WHERE archived_at IS NULL;

-- Same, on the issues side. issues is created in V1 (applied + shipped), so the
-- constraint is added here. Declared ONCE for the whole epic — V9/V10 reuse it.
ALTER TABLE issues ADD CONSTRAINT issues_id_workspace_id_key UNIQUE (id, workspace_id);

-- §3.8 join-table shape: denormalized workspace_id + composite FKs to BOTH parents,
-- so a cross-tenant attachment is unrepresentable rather than merely rejected in Java.
-- No single-column FKs: each composite FK already proves its parent row exists.
CREATE TABLE issue_labels (
    id           UUID        PRIMARY KEY,
    issue_id     UUID        NOT NULL,
    label_id     UUID        NOT NULL,
    workspace_id UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Named explicitly (project convention), so a later migration can reference it
    CONSTRAINT issue_labels_issue_id_label_id_key UNIQUE (issue_id, label_id),
    CONSTRAINT issue_labels_issue_fk FOREIGN KEY (issue_id, workspace_id)
        REFERENCES issues (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT issue_labels_label_fk FOREIGN KEY (label_id, workspace_id)
        REFERENCES labels (id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX idx_issue_labels_label ON issue_labels (label_id, issue_id);  -- filter + usage count
-- No (issue_id) index: the unique constraint's btree already leads on issue_id and
-- serves the single-issue read, the page batch load and the FK cascade.
-- No (workspace_id) index either: never a standalone predicate; both cascade paths are
-- served by an existing index's leading column

-- Retire the V3 placeholder (see §3.4)
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'labels' AND archived_at IS NULL;
```

Entities in `com.hamstrack.issue.entity` (labels attach to issues; keeping them in the `issue`
feature package sits next to `IssueFieldValue` and avoids a package with two classes):

- `Label extends BaseEntity` — `@ManyToOne(LAZY) Workspace workspace`, `String name`,
  `String color`, `String description`, `@ManyToOne(LAZY) User createdBy`, `Instant archivedAt`.
- `IssueLabel extends CreatedOnlyEntity` — `@ManyToOne(LAZY) Issue issue`,
  `@ManyToOne(LAZY) Label label`.

`Issue` gains **no** collection mapping. A `@OneToMany` on `Issue` would drag labels into every
issue flush and defeat the batched page loading; the service loads them explicitly through
`IssueLabelRepository`. (Same reason `Issue` has no `fieldValues` collection today.)

### 4.3 API

```
GET    /api/workspaces/{wsId}/labels?includeArchived=false&withUsage=false   200
POST   /api/workspaces/{wsId}/labels                                          201
PATCH  /api/workspaces/{wsId}/labels/{id}                                     200
POST   /api/workspaces/{wsId}/labels/{id}/archive                             200
POST   /api/workspaces/{wsId}/labels/{id}/unarchive                           200
POST   /api/workspaces/{wsId}/labels/{id}/merge                               200
DELETE /api/workspaces/{wsId}/labels/{id}?force=false                         204
GET    /api/workspaces/{wsId}/labels/{id}/usage                               200
```

DTOs (`com.hamstrack.issue.dto`):

```java
LabelResponse      { UUID id, String name, String color, String description,
                     boolean archived, UUID createdById, String createdByName,
                     Integer issueCount,          // null unless withUsage=true / usage endpoint
                     Instant createdAt, Instant updatedAt }

CreateLabelRequest { @NotBlank @Size(max=60) String name,
                     @Pattern(regexp="^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$") String color,   // optional
                     @Size(max=200) String description }

UpdateLabelRequest { String name, String color, String description }   // partial; null = leave

MergeLabelsRequest { @NotEmpty List<UUID> sourceIds }                  // merged INTO {id}
MergeLabelsResponse{ UUID targetId, int mergedLabelCount, int reassignedIssueCount }
LabelUsageResponse { int issueCount }
```

Status codes: **201** create · **200** read/patch/archive/unarchive/merge/usage · **204** delete ·
**400** blank/too-long name, bad hex · **404** non-member workspace, unknown label id (within the
workspace it is a real 404 — the label genuinely does not exist for this tenant) · **403** member
without the curation role · **409** duplicate name, delete-in-use without `force` ·
**422** merge source == target / source in another workspace.

**409 on duplicate carries the winner's id** as a ProblemDetail extension
(`"existingId": "<uuid>"`) so the picker's optimistic "Create label 'X'" can recover in one
round-trip instead of re-listing. `spring.mvc.problemdetails.enabled=true` is already set, so the
detail message reaches the SPA.

Issue payload additions: `labelIds` on create/update (full replacement when present),
`labels: [{id,name,color,archived}]` on `IssueResponse`, and the `labelId`/`labelMatch` list params
of §3.6.

### 4.4 Rules & edge cases

| Case | Behavior |
|---|---|
| Duplicate name (case-insensitive, same workspace) | **409** + `existingId`. The picker pre-checks its cached list and just attaches the existing label instead of POSTing. |
| Two users create the same label concurrently | The unique index arbitrates; the loser catches `DataIntegrityViolationException`, re-reads by `lower(name)`, and returns **409 + existingId** (client then attaches). Outcome is idempotent. |
| Same name in a **different** workspace | Allowed (201) — scope isolation. |
| Reusing a name held by an **archived** label | **409** (archived rows keep the unique slot). The error message says "an archived label already uses this name — unarchive or rename it". Simpler than a partial index and it prevents silently resurrecting stale meaning. |
| Attach an **archived** label to an issue | **422**. Issues already carrying it keep it and render it dimmed (`archived: true` on the ref). |
| Attach a label from another workspace / unknown id | **422** "Unknown label" — resolved via `findByIdAndWorkspace`, never a bare `findById`. |
| More than `max-labels-per-issue` in one payload | **422** "At most N labels per issue". |
| Replacement set equals the current set | True no-op: no history row **and no `@Version` bump**. Do the diff before mutating — nothing is dirtied, so Hibernate emits no UPDATE. (This corrects an earlier draft of this line, which claimed the PATCH still bumps `@Version`; verified against the build in `LabelOnIssueTest` — a real change is +1, a no-op is +0.) |
| Concurrency on the issue | Covered by the existing `@Version` check (409 on stale `version`). Label-only edits do bump the version — accepted and consistent. |
| **Archive** a label in use | Always allowed. Hidden from pickers and the default settings list; existing links preserved; unarchive restores. This is the recommended safe path and the settings UI nudges toward it. |
| **Delete** a label in use | **409** unless `?force=true`, which deletes the `issue_labels` rows then the label. No "remap on delete" — a label carries no positional/config meaning, so remap is ceremony; `merge` covers the real use case. |
| **Merge** semantics | `POST /labels/{target}/merge {sourceIds}`: every source must be in the same workspace and ≠ target (**422**). Execution order matters — first `DELETE` the source join rows whose issue already carries the target, **then `flush()`**, then re-point the remaining source rows to the target, then delete the source label rows. Skipping the flush hits the documented "INSERT/UPDATE ordered before DELETE ⇒ UNIQUE violation" trap that already bit the admin set editors. |
| Merge and issue history | **No per-issue history rows are written.** A merge can touch thousands of issues; writing a history row each would make one request unbounded. The operation is curation at the catalog level (the issue keeps a label meaning the same thing), and `MergeLabelsResponse.reassignedIssueCount` + the target's bumped `updatedAt` record it. Flagged in §8. |
| Issue deleted | `issue_labels` cascade at DB level (like `issue_field_values`). |
| Workspace deleted | `labels` and their joins cascade. |
| Project archived | Issue edits already 409 via `requireNotArchived`; label changes made **through the issue** inherit that. **Catalog curation does not**: `merge` and `DELETE ?force=true` deliberately ignore `requireNotArchived` — decided during the HD-30 build, endorsed by `security-officer`, and it supersedes the original "label changes inherit that" reading. Rationale: those two are workspace OWNER/ADMIN operations on the workspace's own vocabulary, the `labels` row disappears either way, and the composite-FK `ON DELETE CASCADE` would remove the join rows regardless — so honouring the freeze would be unenforceable rather than merely inconvenient, while letting a single archived project veto a workspace-wide merge forever. A workspace admin can unarchive any project at will, so the freeze is not a privilege boundary against them. **Known gap (backlog, not this epic):** neither operation writes `issue_history`, while an ordinary label change on an issue does — so admin-side label edits currently have no audit trail. The fix is one workspace-level audit record per merge, not the per-issue row explosion §4.4 rightly rejects. |
| A filter/saved query references a deleted label | The board filter matches nothing for that id (no error) and the UI drops unknown ids from the chip row with a toast; HQL by name yields the standard 422 "No label named …". |
| Label list size | The picker endpoint returns all non-archived labels of the workspace (bounded by the tenant's own curation). If a workspace ever exceeds 500 labels the picker switches to `/suggest`-driven typeahead — cap and behavior noted, not built. |

### 4.5 Frontend

- **`components/labels.tsx`** (new; mirrors `components/fields.tsx`):
  - `LabelChip` — color dot + name, `--radius-full`, 11–12px, dimmed when `archived`.
  - `LabelPicker` — multi-select typeahead over `['labels', wsId]`; filters case-insensitively;
    when no exact match exists offers **"Create label 'X'"** (any member) which POSTs, then adds to
    the selection; handles the 409+`existingId` recovery. Soft-caps the selection at
    `max-labels-per-issue`.
  - `LABEL_PALETTE` + `colorForName()` (the deterministic auto-color of §4.1).
- **`components/CreateIssueModal.tsx`** — `LabelPicker` in the details column, below custom fields;
  sends `labelIds`.
- **`pages/IssueDetail.tsx`** — a "Labels" cell in the HD-68 details grid (same
  muted-label-above-value rhythm as Status/Priority), read = chips, click = inline `LabelPicker`,
  commit = `apiUpdateIssue({ labelIds })`. Skip the request when the set is unchanged.
- **`pages/BoardPage.tsx`** — (a) up to 3 chips + "+k" on the card, beneath the priority/assignee
  row; (b) a **server-side** label multi-select in the filter bar next to the priority select, with
  an any/all toggle shown only when ≥2 labels are picked. The selection joins the query key.
- **`pages/BacklogPage.tsx`** — same filter control + a "Labels" column of chips.
- **`lib/queryKeys.ts`** — `boardIssuesKey`'s third argument becomes an optional
  `{ priorityId?, labelIds?, labelMatch?, componentId?, fixVersionId? }` object (serialized
  deterministically: sort `labelIds`). **`boardIssuesKey(wsId, projectId)` must keep working with
  no third argument** — `CreateIssueModal` reads that exact key for its parent picker, and changing
  its arity silently is how the HD-86/87 white-screen class of bug happens.
- **`pages/settings/WorkspaceLabelsPage.tsx`** (new) + a "Labels" entry in
  `WorkspaceSettingsArea.SECTIONS` — dense table (color swatch, inline-editable name, description,
  `UsageChip`, kebab: Edit / Merge into… / Archive / Delete), reusing `ArchivedToggle`,
  `UsageChip`, `Modal` and the delete-confirm pattern from `pages/admin/common.tsx`. Non-admin
  members are redirected by the existing `myRole === 'MEMBER'` guard in `WorkspaceSettingsArea`.
- **`api.ts` / `types.ts`** — a `labels` API group (`list/create/update/archive/unarchive/merge/
  remove/usage`) and `Label` / `LabelRef` types; `labelIds` added to the issue create/update
  payload types and `labels` to `Issue`.
- **DESIGN.md compliance:** chips at `--radius-full`, brand teal for the active filter state, no
  hardcoded hex outside `LABEL_PALETTE`, and **never** Tailwind `max-w-2xs…max-w-3xl` (our
  `@theme --spacing-*` scale shadows them — use inline `maxWidth`).

### 4.6 Acceptance criteria — HD-30

Backend
- [ ] `V8` applies additively on an existing DB; Hibernate `validate` passes; no `CHAR`/ENUM.
- [ ] Plain workspace member creates a label → 201; a non-member → **404** (not 403).
- [ ] `"Tech Debt"` then `"tech debt"` in the same workspace → **409** with `existingId`; the same
      name in another workspace → 201.
- [ ] Name normalization: `"  tech   debt  "` stores as `"tech debt"`; 61 chars → 400; empty → 400;
      bad hex color → 400.
- [ ] Reusing an archived label's name → 409.
- [ ] Create an issue with `labelIds`; response `labels` reflects them; a foreign / archived /
      unknown id → **422**; >20 ids → 422.
- [ ] PATCH with `labelIds` replaces the whole set; add+remove produce exactly one history row with
      `field = "labels"`; an unchanged set writes none.
- [ ] Stale `version` on a label-only PATCH → 409; `@Version` increments by exactly 1.
- [ ] `?labelId=A&labelId=B` returns issues with A **or** B; `&labelMatch=all` only those with
      both; composes (AND) with `statusId`/`assigneeId`/`priorityId`; no selected labels → no
      filtering (and no empty-`IN` crash).
- [ ] A board page of 100 issues issues a **constant** number of queries (assert with the
      statement counter / SQL log — the label batch load must not be per-issue).
- [ ] Rename/recolor by an OWNER/ADMIN → 200; by the creator → 200; by another plain member → 403.
- [ ] Delete in use → 409; `?force=true` → 204 and the join rows are gone; archive in use → 200,
      links preserved, `archived: true` in issue responses.
- [ ] Merge: sources' issues carry the target, duplicates collapse (no UNIQUE violation), source
      rows deleted, `reassignedIssueCount` correct; source == target → 422; cross-workspace source
      → 422.
- [ ] Deleting an issue cascades its `issue_labels`; deleting a workspace cascades labels + joins.
- [ ] **Cross-tenant:** a member of WS-A cannot list, read, rename, merge, delete or attach a WS-B
      label (404/422); no label list ever contains a foreign row.
- [ ] HQL: `label = "tech-debt"`, `label != …`, `label IN (…)`, `label IS EMPTY`,
      `label IS NOT EMPTY` all compile and return the right rows; `ORDER BY label` → 422 (not
      sortable); `/schema` lists `label` with `LABEL` values; a saved filter using `label` saves,
      loads and runs.

Frontend
- [ ] `tsc --noEmit` and `vite build` clean; existing Vitest suites still pass.
- [ ] Create dialog and issue detail attach/detach; on-the-fly create de-dupes case-insensitively.
- [ ] Board cards show ≤3 chips + overflow; the filter bar filters server-side; the selection is in
      the query key and "Clear filters" clears it.
- [ ] `boardIssuesKey(wsId, projectId)` (2-arg) still resolves to the board cache entry and the
      create dialog's parent picker does not crash over a filtered board.
- [ ] Workspace settings → Labels: CRUD, recolor, merge, archive toggle, usage chip, delete dialog;
      a `MEMBER` is redirected away.

---

## 5. HD-31 — Components

### 5.1 Behavior

A component is a **project-scoped** module ("Billing", "iOS app", "Ingest pipeline") with an
optional **lead** and an optional **auto-assign** switch. `issues.component_id` is single-valued,
per the story.

**Auto-assign rule** (the story's "optional auto-assign to component lead on creation"):
on issue **create** only, if `componentId` is present **and** the component has `autoAssign = true`
**and** a `leadId` **and** the request carries no `assigneeId`, then `assignee := lead`. Guard: the
lead must still be a workspace member (resolved through `resolveAssignee`'s membership check); if
they are not, **skip silently** and leave the issue unassigned — a stale lead must never fail issue
creation. Auto-assign does **not** fire on update (changing a component later must not silently
reassign someone's work) and writes no history row (create-time values never do).

Explicitly **not** Jira's default-assignee matrix: one lead, one boolean.

### 5.2 Data model — `V9__components.sql`

```sql
CREATE TABLE components (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,  -- denormalized tenant scope
    project_id   UUID         NOT NULL REFERENCES projects(id)   ON DELETE CASCADE,
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(500),
    lead_id      UUID         REFERENCES users(id) ON DELETE SET NULL,
    auto_assign  BOOLEAN      NOT NULL DEFAULT FALSE,   -- assign new issues to lead_id
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Referenced by the composite FK on issues below (§3.8)
    CONSTRAINT components_id_workspace_id_key UNIQUE (id, workspace_id)
);

CREATE TRIGGER trg_components_updated_at
    BEFORE UPDATE ON components
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Leads on project_id, so it serves every per-project lookup AND the ORDER BY
-- lower(name) of ComponentRepository.findAllByProject — a separate (project_id) index
-- would be pure write amplification (same finding as idx_labels_workspace in V8).
CREATE UNIQUE INDEX components_project_name_uk ON components (project_id, lower(name));
-- Needed: the ON DELETE CASCADE from workspaces probes by workspace_id, and
-- components_id_workspace_id_key leads on id.
CREATE INDEX idx_components_workspace ON components (workspace_id);

-- The story's acceptance criterion: deleting a component nulls it on issues, no orphan FK.
-- HD-31 has no join table, so §3.8's rule lands on this reference instead: the FK is
-- COMPOSITE over (component_id, workspace_id), which makes "issue in tenant A carries a
-- component of tenant B" unrepresentable. ON DELETE SET NULL names the column list
-- explicitly (PG 15+; we require PG 16) — a bare SET NULL would try to null the NOT NULL
-- issues.workspace_id as well and the cascade would fail at delete time.
ALTER TABLE issues ADD COLUMN component_id UUID;
ALTER TABLE issues ADD CONSTRAINT issues_component_fk
    FOREIGN KEY (component_id, workspace_id) REFERENCES components (id, workspace_id)
    ON DELETE SET NULL (component_id);
CREATE INDEX idx_issues_component ON issues (component_id) WHERE component_id IS NOT NULL;

UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'components' AND archived_at IS NULL;
```

Note the deliberate contrast with `issues.type_id`/`status_id`, which carry **no** FK: those are
remapped in bulk by the admin console and an FK would break delete-with-remap. `component_id` has
no remap path and its whole acceptance criterion is FK-enforced nulling, so the FK belongs here.

Entity: `Component extends BaseEntity` in `com.hamstrack.issue.entity` (`Workspace workspace`,
`Project project`, `String name`, `String description`, `@ManyToOne(LAZY) User lead`,
`boolean autoAssign`, `Instant archivedAt`). `Issue` gains
`@ManyToOne(FetchType.LAZY) @JoinColumn(name = "component_id") Component component`.

> **Trap to respect:** `ON DELETE SET NULL` clears the column *behind* JPA's back. A managed,
> now-stale `Issue` flushed later in the same transaction would write the old id back — the same
> class of bug as the `issue_seq` clobber. So `ComponentService.delete` must run an explicit
> `@Modifying(clearAutomatically = true) update Issue i set i.component = null where i.component = :c`
> **before** deleting the row (the FK stays as the safety net for out-of-band deletes).
> `clearAutomatically` is safe here because that service method has no other pending inserts —
> do not copy it into a method that does.

### 5.3 API

```
GET    /api/workspaces/{wsId}/projects/{pId}/components?includeArchived=false&withUsage=false  200
POST   /api/workspaces/{wsId}/projects/{pId}/components                                        201
GET    /api/workspaces/{wsId}/projects/{pId}/components/{id}                                   200
PATCH  /api/workspaces/{wsId}/projects/{pId}/components/{id}                                   200
POST   /api/workspaces/{wsId}/projects/{pId}/components/{id}/archive                           200
POST   /api/workspaces/{wsId}/projects/{pId}/components/{id}/unarchive                         200
DELETE /api/workspaces/{wsId}/projects/{pId}/components/{id}?force=false                       204
GET    /api/workspaces/{wsId}/projects/{pId}/components/{id}/usage                             200
```

```java
ComponentResponse      { UUID id, String name, String description,
                         UUID leadId, String leadName, String leadAvatarUrl,
                         boolean autoAssign, boolean archived,
                         Integer issueCount,              // null unless withUsage / usage endpoint
                         Instant createdAt, Instant updatedAt }
CreateComponentRequest { @NotBlank @Size(max=80) String name, @Size(max=500) String description,
                         UUID leadId, Boolean autoAssign }        // boxed! null → false
UpdateComponentRequest { String name, String description, UUID leadId, Boolean clearLead,
                         Boolean autoAssign }                     // partial; boxed booleans
ComponentUsageResponse { int issueCount }
```

Listing is ordered `lower(name) ASC`. Writes require `ScopeResolver.requireProjectCurator`; reads
require project membership only.

Issue payload: `componentId` + `clearComponent` on create/update, `component` on `IssueResponse`,
`?componentId=` on the list endpoint.

### 5.4 Rules & edge cases

| Case | Behavior |
|---|---|
| Duplicate name in the same project (case-insensitive) | **409**. Same name in a *different* project → 201 (components are project-owned). |
| Lead who is not a workspace member | **422** "Unknown lead" (resolved via the workspace membership check, never a bare `findById` — otherwise it enumerates users of other tenants). |
| Lead leaves the workspace | Row survives (`lead_id` remains); the response returns the lead, the UI marks them "no longer a member", and auto-assign silently skips. `ON DELETE SET NULL` covers actual account deletion. |
| `autoAssign = true` with no lead | **422** on create/update — a switch with nothing to switch to is a configuration mistake, not a silent no-op. |
| `PATCH { autoAssign: true }` while the stored lead has left the workspace | **422**, same message. Create can only ever pair the switch with a lead just proved to be a member, and the two paths must agree; an untouched `leadId` on update previously reused the stored lead unchecked, so the switch could be turned on in a state where it can never fire. Enforced **only** when the request actually asserts `autoAssign` — renaming a component whose lead left months ago must not start failing. |
| Assign an archived component to an issue | **422**. Issues already on it keep it and render it dimmed. |
| Component from another project | **422** "Unknown component" (`findByIdAndProject`). |
| **Archive** a component in use | Always allowed; hidden from pickers; existing assignments kept; unarchive restores. Recommended path. |
| **Delete** a component in use | **409** unless `?force=true`, which nulls it on every issue (explicit bulk update, §5.2 trap) and deletes the row. **No remap-to-another-component** in MVP — archive covers the "keep the history" case and a remap parameter can be added later without a breaking change. |
| Delete/force and history | One `component` history row per affected issue is **not** written (same unbounded-request reasoning as label merge); the 409 dialog states "used on N issues — deleting clears it on all of them". |
| Project deleted | Components cascade with the project; issues cascade too. |
| Project archived | Issue edits 409 as today; component *management* also 409s (`requireNotArchived`) so an archived project's config is frozen. |
| Auto-assign vs explicit assignee | An explicit `assigneeId` in the create request always wins. |
| Two concurrent creates of the same name | Unique index arbitrates → 409. |
| Project's component catalog is full | **422** at `app.classification.max-components-per-project` (default 500, archived rows counted — they keep their name slot). Archive or delete some first. |

### 5.5 Frontend

- **`pages/settings/ProjectComponentsPage.tsx`** (new) + a **"Components"** tab in
  `ProjectSettingsArea.TABS` (absolute path `${settingsBase}/components` — relative links inside
  the splat route resolve after the splat). Table: name (inline edit), description, lead
  (`Select` over workspace members), auto-assign toggle, `UsageChip`, `ArchivedToggle`, kebab with
  Archive / Delete-with-usage dialog. `ProjectSettingsArea` currently redirects non-MANAGERs; a
  workspace OWNER/ADMIN who is not a project member would be bounced client-side even though the
  server allows them — **fix the guard** to also let workspace OWNER/ADMIN through (read
  `myRole` from `apiGetWorkspace`).
- **`components/CreateIssueModal.tsx`** — a "Component" `Select` (blank = none) fed by
  `['components', wsId, projectId]`, filtered to non-archived.
- **`pages/IssueDetail.tsx`** — a "Component" cell in the details grid, same inline-edit pattern as
  Assignee; blank sends `clearComponent: true`. Keep the current component selectable even when it
  has been archived (mirrors the existing "assignee who left the workspace" handling).
- **`pages/BoardPage.tsx` / `pages/BacklogPage.tsx`** — a "All components" `Select` in the filter
  bar (server-side param); backlog gains a "Component" column. Card display: a small muted
  component name under the title only when the board's component filter is **not** active
  (otherwise it is noise) — a deliberate density choice, DESIGN.md "compact in the board".
- **`api.ts` / `types.ts`** — `components` API group + `Component` / `ComponentRef` types.

### 5.6 Acceptance criteria — HD-31

- [ ] `V9` applies additively; `issues.component_id` FK is `ON DELETE SET NULL`; `validate` passes.
- [ ] Create/rename/delete as project MANAGER → 2xx; as workspace OWNER/ADMIN who is not a project
      member → 2xx; as a plain project MEMBER → **403**; as a non-member of the workspace → **404**.
- [ ] Duplicate name in one project → 409; same name in a sibling project → 201.
- [ ] Lead outside the workspace → 422; `autoAssign` without a lead → 422.
- [ ] **Auto-assign:** creating an issue with a component whose `autoAssign` is on and no explicit
      assignee assigns the lead; an explicit assignee wins; auto-assign off → unassigned; lead no
      longer a member → unassigned, request still 201.
- [ ] Changing a component on **update** never reassigns.
- [ ] `?componentId=` filters board and backlog and composes with the other filters.
- [ ] **Deleting a component nulls it on issues** (the story's criterion): `?force=true` → the
      issues' `component` is null in the API response *and* in the DB, with no dangling id;
      without `force` and with usage → 409.
- [ ] Archived component: not offered in pickers, still shown on issues carrying it, attaching →
      422.
- [ ] **Cross-tenant:** a component id from another project/workspace → 422 on attach and 404 on
      direct GET; the list never contains foreign rows.
- [ ] HQL: `component = "Billing"`, `component IN (…)`, `component IS EMPTY`,
      `ORDER BY component ASC` all work; a name existing in two visible projects matches issues in
      both; `/schema` exposes `COMPONENT` values.
- [ ] `tsc` + `vite build` clean; project-settings Components tab reachable by MANAGER and by
      workspace OWNER/ADMIN.

---

## 6. HD-32 — Versions (fix / affects) + release page

### 6.1 Behavior

A version is a **project-scoped** release target with a name ("2.4.0"), an optional description, an
optional `release_date`, and a `released` flag. Issues link to versions in two roles:

- **fix version** — "the change ships in this release" (drives the release page's progress);
- **affects version** — "this defect exists in that release" (drives triage).

Both are **many-to-many** (a fix can ship in 2.4.0 and 2.3.1; a bug can affect three releases).

**Lifecycle:** `unreleased ⇄ released`, plus an orthogonal `archived`. Releasing is **reversible**
(the story's criterion): `POST /release` sets `released = true`, `released_at = now()` and defaults
`release_date` to today when it is null; `POST /unrelease` sets `released = false`,
`released_at = null` and **keeps** `release_date` (it stays the plan). Archiving hides a version
from pickers and from the default Releases list without touching links; released and archived are
independent (you archive old shipped releases to keep the list short).

**Releasing with unresolved issues is allowed** — a version with open work is a real, common state
and blocking it would push people to lie to the tracker. The release request may optionally carry
`moveUnresolvedToVersionId` to re-point the fix links of every issue in a non-DONE status to
another **unreleased, non-archived** version of the same project (422 otherwise). Without it,
unresolved issues keep the released version and the Releases page shows them.

**Ordering** of the version list: `released ASC` (unreleased first), then `release_date ASC NULLS
LAST`, then `lower(name) ASC`. Derived — no manual `position` column in MVP (see §8).

**Progress:** per version, `issueCount` = issues with a **FIX** link, `doneIssueCount` = those whose
status category is `DONE`. Computed for the whole list in **one grouped query** keyed by version id
(never per version).

### 6.2 Data model — `V10__versions.sql`

```sql
CREATE TABLE versions (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id   UUID         NOT NULL REFERENCES projects(id)   ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL,
    description  VARCHAR(500),
    release_date DATE,
    released     BOOLEAN      NOT NULL DEFAULT FALSE,
    released_at  TIMESTAMPTZ,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT versions_released_ck CHECK ((released AND released_at IS NOT NULL)
                                        OR (NOT released AND released_at IS NULL)),
    -- Referenced by issue_versions' composite FK (§3.8)
    CONSTRAINT versions_id_workspace_id_key UNIQUE (id, workspace_id)
);

CREATE TRIGGER trg_versions_updated_at
    BEFORE UPDATE ON versions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE UNIQUE INDEX versions_project_name_uk ON versions (project_id, lower(name));
CREATE INDEX idx_versions_project ON versions (project_id) WHERE archived_at IS NULL;
CREATE INDEX idx_versions_workspace ON versions (workspace_id);

-- One link table for both roles: link_type is VARCHAR (never a PG ENUM — Hibernate 7 breaks on it).
-- §3.8 join-table shape (owner decision, mandatory): denormalized workspace_id + composite FKs to
-- BOTH parents, no single-column FKs. issues_id_workspace_id_key already exists (added in V8) —
-- do NOT re-add it here.
CREATE TABLE issue_versions (
    id           UUID        PRIMARY KEY,
    issue_id     UUID        NOT NULL,
    version_id   UUID        NOT NULL,
    workspace_id UUID        NOT NULL,
    link_type    VARCHAR(10) NOT NULL,        -- 'FIX' | 'AFFECTS' (validated by the Java enum)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT issue_versions_issue_id_version_id_link_type_key
        UNIQUE (issue_id, version_id, link_type),
    CONSTRAINT issue_versions_issue_fk FOREIGN KEY (issue_id, workspace_id)
        REFERENCES issues (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT issue_versions_version_fk FOREIGN KEY (version_id, workspace_id)
        REFERENCES versions (id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX idx_issue_versions_version ON issue_versions (version_id, link_type, issue_id);
-- No (issue_id) index: the unique constraint's btree leads on issue_id. No (workspace_id)
-- index: never a standalone predicate, and both cascade paths use an existing leading column

UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'fix_version' AND archived_at IS NULL;
```

One link table rather than two (`issue_fix_versions` / `issue_affects_versions`): one batch loader,
one filter path, one cascade story, and adding a third role later (e.g. "discovered in") is a value,
not a migration. `link_type` is `VARCHAR(10)` validated by a Java enum `VersionLinkType {FIX,
AFFECTS}` — **never** `CREATE TYPE … AS ENUM` (Hibernate 7 + PG enums throw JDBC cast errors on
insert).

Entities: `Version extends BaseEntity`, `IssueVersionLink extends CreatedOnlyEntity`
(`@Enumerated(EnumType.STRING) @Column(length = 10) VersionLinkType linkType`).

### 6.3 API

```
GET    /api/workspaces/{wsId}/projects/{pId}/versions?includeArchived=false&includeReleased=true   200
POST   /api/workspaces/{wsId}/projects/{pId}/versions                                              201
GET    /api/workspaces/{wsId}/projects/{pId}/versions/{id}                                         200
PATCH  /api/workspaces/{wsId}/projects/{pId}/versions/{id}                                         200
POST   /api/workspaces/{wsId}/projects/{pId}/versions/{id}/release                                 200
POST   /api/workspaces/{wsId}/projects/{pId}/versions/{id}/unrelease                               200
POST   /api/workspaces/{wsId}/projects/{pId}/versions/{id}/archive                                 200
POST   /api/workspaces/{wsId}/projects/{pId}/versions/{id}/unarchive                               200
DELETE /api/workspaces/{wsId}/projects/{pId}/versions/{id}?force=false&remapToId=                  204
GET    /api/workspaces/{wsId}/projects/{pId}/versions/{id}/usage                                   200
```

```java
VersionResponse       { UUID id, String name, String description, LocalDate releaseDate,
                        boolean released, OffsetDateTime releasedAt, boolean archived,
                        int issueCount, int doneIssueCount,          // FIX-link progress, always present
                        int affectsIssueCount,
                        Instant createdAt, Instant updatedAt }
CreateVersionRequest  { @NotBlank @Size(max=60) String name, @Size(max=500) String description,
                        LocalDate releaseDate }
UpdateVersionRequest  { String name, String description, LocalDate releaseDate, Boolean clearReleaseDate }
ReleaseVersionRequest { LocalDate releaseDate, UUID moveUnresolvedToVersionId }   // both optional
VersionUsageResponse  { int fixIssueCount, int affectsIssueCount, int unresolvedFixIssueCount }
```

The list response is the Releases page's data source — progress is always included (it is one extra
grouped query for the whole list, not per row).

Issue payload: `fixVersionIds` / `affectsVersionIds` (full-replacement sets when present) on
create/update; `fixVersions` / `affectsVersions` on `IssueResponse`; `?fixVersionId=` on the list
endpoint.

### 6.4 Rules & edge cases

| Case | Behavior |
|---|---|
| Duplicate name in one project (case-insensitive) | **409**; same name in another project → 201. |
| Link an **archived** or foreign version | **422**. Existing links kept and rendered dimmed. |
| Link a **released** version as fix version | **Allowed** (back-porting and post-release bookkeeping are legitimate). No warning at the API level; the UI marks released options with a "released" tag. |
| Same version as both fix **and** affects on one issue | Allowed — different `link_type`, so the UNIQUE key permits it. It is a real state ("regression introduced and fixed in 2.4.0"). |
| More than `max-version-links-per-issue` per role | **422**. |
| Project's version catalog is full | **422** at `app.classification.max-versions-per-project` (default 500; archived *and* released rows counted — they keep their name slot, so create → archive → repeat can't walk past the cap). |
| `releaseDate` outside 1000-01-01…9999-12-31 | **422**. `LocalDate` parses `+999999999-12-31`, which PostgreSQL's `date` cannot store — unbounded, it reached the flush and escaped as a 500. |
| Release an already-released version | **409** "already released" (idempotent-looking success would hide a double-click on a destructive `moveUnresolved`). |
| Un-release an unreleased version | **409** likewise. |
| `moveUnresolvedToVersionId` = self, released, archived, or another project's | **422**. |
| `moveUnresolvedToVersionId` collides with a link the issue already has | The move must `DELETE` colliding source rows, **`flush()`**, then re-point the rest (the UNIQUE-ordering trap again). |
| A concurrent issue edit re-creates that collision mid-remap | **409** "retry", not a 500: the DELETE→flush→re-point sequence is not atomic against a `PATCH /issues/{n}` adding the target version in between, and `issue_versions_issue_id_version_id_link_type_key` then arbitrates. Applies to both the release-time move and delete-with-remap. |
| Concurrent PATCH/archive vs. release | The release wins. `released`/`released_at` are `updatable = false` on the entity (the `projects.issue_seq` pattern), so only the conditional bulk UPDATEs write them — an ordinary `saveAndFlush` from a pre-race snapshot can no longer un-release the row. `versions_released_ck` cannot catch that case: the stale pair is internally consistent. |
| `release` with no `releaseDate` and none stored | `release_date := today` (server UTC date). |
| `unrelease` | `released = false`, `released_at = null`, `release_date` preserved. Reversible with no data loss — the story's criterion. |
| **Archive** a version in use | Always allowed; hidden from pickers and the default list; links preserved. |
| **Delete** a version in use | **409** unless `?force=true` (delete all its links) **or** `?remapTo={id}` (re-point all links, both roles, to another version of the same project — 422 if that version is archived or in another project). Remap is offered here (unlike components) because a version *does* carry positional meaning: "the work moved to the next release" is the normal reason to delete one. |
| Delete a **released** version | Allowed, same rules. No extra guard — deleting a released version is a curation decision, and `force`/`remapTo` already require intent. |
| Issue deleted / project deleted | `issue_versions` cascade from both sides; versions cascade from the project. |
| Project archived | Version management and issue edits 409 (`requireNotArchived`). |
| Progress with zero issues | `issueCount = 0`, `doneIssueCount = 0`; the UI renders "No issues yet" rather than a 0% bar. |
| Concurrency: two users release the same version | Second gets the 409 "already released" (a conditional `UPDATE … WHERE released = false` returning 0 rows raises it — do not rely on a read-then-write). |

### 6.5 Frontend

- **`pages/ReleasesPage.tsx`** (new) at route `/w/:wsId/p/:projectId/releases`, wrapped in
  `ParamKeyed` like the other project pages, and a **"Releases"** entry in `NavRail` between
  Backlog and Reports (lucide `Rocket` or `Tag`). This is the story's "release page": a card per
  version — name, release date, released/unreleased pill, a progress bar (`doneIssueCount /
  issueCount` with a numeric "18 / 24") and a kebab (Edit, Release…/Un-release, Archive, Delete).
  "Release…" opens a dialog offering the release date and, when unresolved issues exist, the
  "move N unresolved issues to →" version select. Clicking a version's issue count navigates to
  `/w/{ws}/search` pre-filled with `project = "KEY" AND fixVersion = "2.4.0"` — reusing the HQL
  search results page instead of building a second list view.
  Versions are **managed here**, not in project settings: they are working data with a lifecycle,
  not configuration. Project settings → Components links across ("Release versions live on the
  Releases page").
- **`components/versions.tsx`** (new): `VersionBadge` (name + released dot) and `VersionPicker`
  (multi-select over `['versions', wsId, projectId]`, groups unreleased above released, marks
  archived).
- **`components/CreateIssueModal.tsx`** — "Fix version(s)" picker; "Affects version(s)" is hidden
  behind the modal's existing "more fields" affordance to keep the create path short.
- **`pages/IssueDetail.tsx`** — "Fix versions" and "Affects versions" cells in the details grid.
- **`pages/BoardPage.tsx` / `BacklogPage.tsx`** — an "All fix versions" `Select` in the filter bar
  (server-side); backlog gains a "Fix version" column.
- **`api.ts` / `types.ts`** — `versions` API group + `Version` / `VersionRef` types.
- **DESIGN.md:** progress bar uses `--color-brand` on `--color-surface-2`; the released pill uses
  the success tint, unreleased uses the slate/info tint; version names render in **IBM Plex Mono**
  (they are inspectable data, like issue keys).

### 6.6 Acceptance criteria — HD-32

- [ ] `V10` applies additively; `validate` passes; `link_type` is `VARCHAR`, not a PG enum; the
      `versions_released_ck` CHECK holds for every write path.
- [ ] Create/edit/release/delete as project MANAGER or workspace OWNER/ADMIN → 2xx; plain member →
      403; non-member → 404.
- [ ] Duplicate name per project → 409; same name in another project → 201.
- [ ] Link fix + affects versions on create and update (full replacement); foreign/archived/unknown
      → 422; > cap → 422; the same version as both fix and affects on one issue → allowed.
- [ ] History rows `fixVersions` / `affectsVersions` written on change, none on a no-op.
- [ ] **Release is reversible** (the story's criterion): release → `released = true`,
      `releasedAt` set, `releaseDate` defaulted to today when absent; un-release → `released =
      false`, `releasedAt` null, `releaseDate` preserved; releasing twice → 409.
- [ ] `moveUnresolvedToVersionId` moves exactly the non-DONE issues' FIX links, collapses
      duplicates without a UNIQUE violation, leaves DONE issues on the released version; target
      released/archived/foreign → 422.
- [ ] **Filtering by fix version works** (the story's criterion): `?fixVersionId=` on board and
      backlog returns exactly the issues with a FIX link to it and composes with the other filters;
      HQL `fixVersion = "2.4.0"` and `affectsVersion IN ("2.3.0","2.3.1")` return the same sets;
      `fixVersion IS EMPTY` finds unassigned work.
- [ ] Progress: `issueCount`/`doneIssueCount` correct across statuses; the whole list costs a
      **constant** number of queries regardless of version count.
- [ ] Delete in use → 409; `?force=true` drops the links; `?remapTo=` re-points both roles;
      archive keeps links.
- [ ] Deleting an issue or a project cascades the links.
- [ ] **Cross-tenant:** a version id from another project → 422 on link, 404 on GET; the list never
      contains foreign rows; the search `fixVersion` name resolves only through visible projects.
- [ ] Releases page renders per-version progress, release/un-release round-trips, and the issue-count
      link lands on a pre-filled search; `tsc` + `vite build` clean.

---

## 7. Shared obligations & review gates

- **`api-docs-sync`** (mandatory, all three slices): `openapi.yaml` gains `Labels`, `Components`,
  `Versions` tags with paths + schemas; `labelIds`/`componentId`/`fixVersionIds`/
  `affectsVersionIds` on issue create/update; `labels`/`component`/`fixVersions`/`affectsVersions`
  on `IssueResponse`; the new list query params. Both `docs/api-*.md` updated identically (no
  DC-only operator setting is introduced, so the DC "Operator settings" section only gains the two
  cap properties). Validate with `npx @apidevtools/swagger-cli validate`.
- **`migration-reviewer`**: V8/V9/V10 + the new `@Entity` classes.
- **`tenancy-reviewer`**: mandatory on every slice — this epic adds three new families of
  workspace/project-scoped repositories, i.e. three new chances to leak.
- **`dc-cloud-guard`**: the two new properties' full wiring path.
- **`security-officer`**: input validation on names/colors/hex, the caps, and the merge/force/remap
  destructive paths.
- **`docs/project-state.md`**: a new "Labels, components & versions (V8–V10)" section; `CLAUDE.md`
  needs no new hot rule (every trap this epic touches is already documented there).

---

## 8. Risks & the highest-risk assumption

> **Highest-risk assumption:** that **any workspace member may create a label** (§3.3), with
> curation (rename/merge/delete) reserved for OWNER/ADMIN. Everything about HD-30's ergonomics — the
> on-the-fly "Create label 'X'" in the picker, two-keystroke tagging — depends on it. If the owner
> wants labels curated like the taxonomy, flip create to OWNER/ADMIN and drop that picker
> affordance; **nothing else in this spec changes.** Recommended default: **member-create ON** —
> low-friction tagging is the entire point, and workspace scope + case-insensitive uniqueness +
> merge already close the tag-soup failure mode that makes Jira's version of this feature useless.

Secondary risks:

1. **Issue-list query growth.** `findByProjectFilteredPaged` will carry six optional predicates plus
   a correlated sub-select. Mitigation: the covering indexes above, and a required assertion that a
   filtered board/backlog page stays a constant number of queries. If the JPQL becomes unreadable,
   the escape hatch is to move the issue list onto Criteria (the search engine already proves the
   pattern) — but not in this epic.
2. **Empty `IN` list** in the label predicate (§3.6) — a real 500 if the sentinel is forgotten.
3. **`ON DELETE SET NULL` vs a stale managed `Issue`** (§5.2) — the `issue_seq` clobber class.
4. **UNIQUE-violation ordering** in label merge and version remap (§4.4, §6.4) — the documented
   "INSERT before DELETE within one flush" trap; both need an explicit `flush()`.
5. **Docs drift**: three slices, three doc syncs. Run `api-docs-sync` per slice, not once at the end.

---

## 9. Open questions (each with a recommended default — none blocks the build)

1. **Who may create labels?** → *Any workspace member.* (The flagged assumption above.)
2. **Board/backlog label match mode default?** → *`any` (OR)*, `all` opt-in. Matches tag-filter
   intuition; the UI only shows the toggle once two labels are selected.
3. **Should label merge write per-issue history?** → *No.* Unbounded request size; the merge
   response + the target's `updatedAt` are the record. If audit demands it later, do it
   asynchronously, not inline.
4. **Reuse a label name held by an archived label?** → *No — 409, the archived row keeps the slot.*
   Simpler than a partial unique index and it prevents silently resurrecting stale meaning.
5. **Multi-valued components?** → *No — one `component_id`, per the ticket.* If it changes, the path
   is a `issue_components` join table plus keeping `component_id` as a derived "primary" for one
   release; do not pre-build it.
6. **Manual ordering of versions?** → *No `position` column in MVP*; order is
   `unreleased first, release_date, name`. Add `position SMALLINT` + a reorder endpoint only if
   users actually ask — it is a purely additive migration.
7. **Block releasing a version that still has unresolved issues?** → *No, allow it*, with the
   optional `moveUnresolvedToVersionId`. Blocking pushes teams to falsify the tracker.
8. **Delete-with-remap for components?** → *Not in MVP* (archive covers it). Versions **do** get
   `remapTo` because "moved to the next release" is the normal case.
9. **Do components/versions belong in `GET …/projects/{p}/config`?** → *No* (§3.2). Separate
   endpoints and separate query keys; `ProjectConfigService` stays the resolver of *bindings* only.
10. **Feature flags?** → *None.* Only the two DoS caps are configurable, identically in DC and Cloud.
11. **Seed demo labels/components/versions?** → *Yes*, small starter sets in the demo project via
    `DemoDataService`, covered by the existing `app.demo.seed-on-first-login` toggle and cleared by
    the existing workspace cascade.
12. **Label picker beyond ~500 labels per workspace?** → Switch the picker and `/schema` values to
    `/suggest`-driven typeahead. Cap documented (200 in `/schema`), the picker fallback is *not*
    built now.
