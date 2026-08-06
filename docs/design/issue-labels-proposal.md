# Issue Labels — Proposal

Status: **draft 2026-08-06** — awaiting owner sign-off.
Author: systems-analyst. Feeds `backend-builder` / `frontend-builder` / `test-runner`.

Companion docs to keep in sync on ship: `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, `CLAUDE.md`.

---

## 1. Problem & goal

Users want to attach lightweight, reusable, colored labels to issues
("tech-debt", "customer-reported", "needs-design") and filter the board and
backlog by them. Labels are a **cross-cutting, many-per-issue** classification
that is deliberately *not* part of the governed taxonomy (status / priority /
type / custom field). Today there is no way to tag an issue outside the
single-valued custom `SELECT`/`MULTI_SELECT` field mechanism, which is
admin-governed, per-project-bound and heavier than users want for ad-hoc
tagging.

**Goal:** ship a labels feature that is (a) many-to-many with issues, (b)
reusable within its scope, (c) colored, (d) filterable on board + backlog, (e)
manageable in an obvious place, and (f) low-friction to create — without
importing Jira's exact "labels" behavior, naming, or UX.

**Non-goal / differentiator vs Jira:** Jira labels are a single *global,
free-text, uncurated* namespace that becomes a swamp of typo-duplicates
("techdebt" / "tech-debt" / "TechDebt") with no colors and no governance. We
deliberately diverge: labels are **workspace-scoped, curated-but-self-serve,
colored first-class rows** — closer to Linear/GitHub labels than to Jira's
tag soup. This is a genuine product improvement, not a reimplementation.

---

## 2. Scope

**In scope**
- A `labels` catalog entity (name + color + description), workspace-scoped.
- A `issue_labels` join table (issue ↔ label, many-to-many).
- Attach/detach labels on issue create and update; labels rendered on issue
  cards, side panel, and backlog.
- Filter board + backlog by one or more labels (AND/OR — see §4).
- Label management: an inline "manage labels" surface reachable from the issue
  editor **and** a dedicated workspace-settings section; on-the-fly creation
  from the issue label picker (typeahead → "Create label 'X'").
- Delete / archive / rename / recolor with in-use handling.
- Issue history entries for label add/remove.

**Out of scope (non-goals)**
- Global (instance-wide) or project-private label scopes. (Justified in §4;
  the schema leaves the door open but we do **not** build them now.)
- Label **sets** / bindings (the catalog+binding+set shape). See §4 — labels
  deliberately break the taxonomy pattern.
- Per-label permissions ("locked labels", label-editor role à la Asana).
- Label hierarchy / groups / colors-as-categories.
- Saved filters / HQL integration (labels will slot into the future HQL search
  naturally; not built here).
- Bulk label operations on many issues at once (future polish).
- SLA/automation driven by labels.

---

## 3. Actors & permissions

Multi-tenancy rule applies throughout: every path is workspace-scoped, access
is by workspace membership, non-members get **404** (never 403).

| Action | Who |
|---|---|
| View labels in a workspace / on issues | any **workspace member** (via `resolveWorkspace`) |
| Attach/detach a label to an issue | anyone who can edit that issue (existing issue create/update permission — workspace member, project not archived) |
| **Create** a label (catalog row) | any **workspace member** — low-friction, self-serve (see §4 "free-form vs managed"). Not restricted to admins. |
| Rename / recolor / describe a label | workspace **OWNER/ADMIN** (curation), **or** the label's creator |
| Archive / delete a label | workspace **OWNER/ADMIN** |
| Manage all labels of a workspace | workspace **OWNER/ADMIN** in the Workspace-settings "Labels" section |

Rationale for the split: creating a label must be as cheap as typing it
(otherwise people abuse free-text fields), but *destructive/curation* actions
(rename that changes meaning, delete, recolor) are governed to keep the list
tidy — the Linear model. This mirrors the delegated-admin philosophy: broad
self-serve at the working tier, governance for shared mutation. Instance
`SystemRole.ADMIN` inherits everything (already true for all workspace-scoped
resources through membership; an instance admin who is a member acts as such).

> **Highest-risk assumption (flagged):** that **any workspace member may create
> a label**. If the owner wants labels curated (admin-created only, like the
> governed taxonomy), flip the create-permission to OWNER/ADMIN and drop the
> on-the-fly "Create label 'X'" affordance from the issue picker. Everything
> else in this spec is unaffected. Recommended default: **member-create ON**,
> because low-friction tagging is the whole point and the workspace scope + soft
> `UNIQUE(scope, lower(name))` guard already prevents the Jira tag-soup failure
> mode. This is the one decision most worth confirming before build.

---

## 4. Behavior & rules — the key design decisions

### 4.1 Scope: **workspace-scoped** (decided)

Options considered: global catalog (like statuses/priorities), workspace-scoped,
project-scoped.

**Decision: workspace-scoped** (`scope_workspace_id NOT NULL`), with a
`scope_project_id` column reserved-but-unused for a future project-private tier.

Justification against the existing model and multi-tenancy:

- **Global is wrong for labels.** Statuses/priorities/types are *global* because
  they are governance primitives an instance operator curates once and reuses
  everywhere; the whole admin-console thesis is a small, curated shared catalog.
  Labels are the opposite: high-cardinality, team-specific, coined ad-hoc
  ("customer-reported" means nothing to another tenant). A global label catalog
  in **Cloud would surface one tenant's vocabulary to another** — either a data
  leak or a shared swamp. That violates the core tenancy rule and the "labels
  are lightweight and team-owned" product intent.
- **Project-scoped is too narrow.** Users routinely want the same label
  ("tech-debt") across every project in their workspace; per-project labels
  force re-creation and defeat reuse. Board/backlog are per-project today, but
  labels want to travel with the *team*, i.e. the workspace.
- **Workspace-scoped is the right grain.** It matches the tenancy boundary
  exactly (natural 404-on-non-member), gives reuse across a team's projects, and
  keeps each tenant's vocabulary isolated. It also mirrors how the codebase
  already reasons: `Issue` carries `workspace_id`; resolution goes through
  `resolveWorkspace`.
- **Future-proofing:** add the `scope_project_id UUID NULL` column and a CHECK
  (mirroring the `Scoped` pattern from V11) so a later "project-private labels"
  tier is an additive migration, not a reshape. We do **not** implement or
  resolve project scope now — labels are created workspace-scoped only.

> Note: labels are intentionally **not** a `Scoped`-interface catalog primitive
> in the taxonomy sense. They are their own workspace-owned concept. Reusing the
> `scope_workspace_id`/`scope_project_id` *column names* keeps future symmetry
> without pulling labels into `ProjectConfigService` / bindings.

### 4.2 Catalog + binding + set shape: **do NOT reuse it** (decided)

The taxonomy pattern is *global catalog → reusable set → project binding →
`ProjectConfigService` resolves effective config*. **Labels do not fit and must
not adopt it.** Reasons:

- Labels are **not per-project-configurable**. There is no "which labels does
  this project offer" governance question worth answering — a team wants its
  labels available everywhere in the workspace. A `label_set` + binding layer
  would be pure ceremony with zero payoff (the anti-pattern the admin-console
  proposal explicitly warns against: indirection without reuse value).
- Labels are **many-per-issue and user-attached**, unlike status/priority/type
  (single-valued, governed). They behave like tags, not config.
- Therefore **`ProjectConfigService` is not involved.** The project `config`
  endpoint does *not* gain labels. Labels are fetched from a dedicated
  workspace-scoped endpoint (§7). This keeps the single-resolver invariant
  intact (`ProjectConfigService` stays the sole interpreter of *bindings*;
  labels aren't bindings).

**Consequence:** labels are the first "workspace-owned, un-bound, many-valued"
concept. That's deliberate — not every classification should be forced through
the taxonomy machine.

### 4.3 Free-form-on-the-fly vs admin-managed: **both** (decided)

Hybrid, matching §3 permissions:
- **On-the-fly create** from the issue label picker: typeahead filters existing
  labels; if no exact (case-insensitive) match, a "Create label 'X'" row
  creates the label (default color auto-assigned from a fixed palette, see §8)
  and attaches it in one action. Available to any workspace member.
- **Managed** in Workspace settings → Labels: full CRUD, recolor, describe,
  archive, delete-with-handling — OWNER/ADMIN.

This gives Linear/GitHub ergonomics (fast tagging) without Jira's ungoverned
swamp (the managed surface + soft uniqueness keep it tidy).

### 4.4 Attach / detach semantics

- On issue **create**: optional `labelIds: UUID[]` in the request; each must be
  a non-archived label of the issue's workspace (else **422**). Duplicates in
  the array are de-duped. Empty/absent = no labels.
- On issue **update**: `labelIds: UUID[]` is a **full replacement set** when
  present (absent = leave labels unchanged — consistent with the partial-update
  style of the issue PATCH). This is simpler and less error-prone than
  add/remove deltas for the UI (the picker always holds the full set). Diffing
  add vs remove happens server-side to write history + avoid churn.
- Attaching an **archived** label is rejected (422) — but issues already
  carrying a now-archived label keep it (archive hides from *new* use, not
  existing associations), and it still renders (dimmed). This mirrors the
  custom-field archived-value rule.
- Attaching a label from **another workspace** → 422 (resolved through the
  issue's workspace, never a bare `findById`; same tenancy discipline as
  assignee/USER-field resolution).
- Label add/remove writes an **issue history** entry (field `"labels"`, old/new
  = comma-joined label names) so the audit trail is complete, matching how
  custom-field and core-field changes are recorded.

### 4.5 Filtering integration (board + backlog)

Extends the existing issue-list filter (`GET .../issues?statusId=&assigneeId=&priorityId=`).

- Add repeatable query param **`labelId`** (multi-valued: `?labelId=A&labelId=B`).
- **Match mode:** default **OR** (issue has *any* of the given labels) —
  matches user expectation for tag filtering and the common "show me anything
  customer-reported or urgent-ish" case. Add `labelMatch=all` to switch to AND
  (issue has *all* given labels). Recommended default `any`.
- Combined with existing filters via **AND** across dimensions (status AND
  assignee AND priority AND label-clause), consistent with today's behavior.
- Backlog already filters client- or server-side by status category ≠ DONE;
  the label param composes with that.
- Query shape: the existing `findByProjectFiltered` JPQL gains a label
  sub-clause. For OR: `EXISTS (select 1 from IssueLabel il where il.issue=i and
  il.label.id in :labelIds)`. For AND: group-count
  (`... and il.label.id in :labelIds ... group by i having count(distinct il.label.id) = :labelCount`),
  or an `EXISTS`-per-label fold. Keep it a single query; avoid N+1 on the label
  list itself by batch-loading labels per issue page (mirror
  `FieldValueService.valuesByIssue`).

### 4.6 Rename / recolor

- Rename changes the display name everywhere (labels referenced by **id**, not
  by string — unlike Jira's string labels — so rename is safe and cheap). Guard
  soft-uniqueness (§5) case-insensitively within the workspace scope; 409 on
  collision.
- Recolor is free (color is presentational; no stored value depends on it —
  contrast with select-option ids).

---

## 5. Edge cases & failure modes

| Case | Behavior |
|---|---|
| **Duplicate name** (same workspace, case-insensitive) on create/rename | **409**. Enforced by `UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, lower(name))` (expression index). The on-the-fly picker pre-checks and just attaches the existing one instead of erroring. |
| **On-the-fly create races** (two users create "tech-debt" at once) | DB unique constraint is the arbiter; loser catches the constraint violation and re-resolves to the winner's row, then attaches (idempotent outcome). |
| **Delete a label in use** | **409** unless the caller opts in: `DELETE /labels/{id}?force=true` detaches it from all issues (bulk delete of `issue_labels` rows) and deletes the row. No "remap to another label" (unlike status/type — a label carries no positional/config meaning, so remap is needless ceremony; detach-or-archive is the honest choice). The delete dialog shows "used on N issues — deleting removes it from them." |
| **Archive a label in use** | Always allowed. Hidden from pickers and the managed list's default view; existing associations preserved and rendered dimmed. Unarchive restores. Archive is the recommended safe path; the managed UI nudges toward it over delete. |
| **Attach archived / foreign / unknown label** | 422 (see §4.4). |
| **Last label / empty catalog** | No "last of kind" constraint — a workspace may have zero labels; the picker shows only "Create…". No system-default label row. |
| **Concurrency on the issue** | Label replacement goes through the normal issue PATCH, so it is covered by the existing `@Version` optimistic lock (409 on stale version). Label-only edits still bump the issue version — acceptable and consistent. Follow the CLAUDE.md gotcha: **do all reads (resolve labels) before mutating the issue**, then apply and save last, to avoid Hibernate double-flush / version jumps. |
| **Idempotent attach** | Replacing with a set equal to the current set is a no-op (no history entry, no version bump beyond what the PATCH already does — diff first). |
| **Issue deleted** | `issue_labels` rows cascade-delete (`ON DELETE CASCADE`), like comments/attachments/field-values. Labels themselves untouched. |
| **Workspace deleted** | Labels + join rows cascade (`scope_workspace_id ... ON DELETE CASCADE`). |
| **Project archived** | Issue edits (incl. label changes) already 409 via `requireNotArchived`; labels inherit that. |
| **Label referenced by a filter after archive/delete** | Filter query simply matches nothing for that id (no error); the UI drops unknown/archived label chips from the active filter with a toast. |

---

## 6. Data model impact

Flyway rules honored: `VARCHAR` (never `CHAR`/ENUM), UUID v7 app-generated
(`@UuidGenerator(TIME)`), `@CreatedDate`/`@LastModifiedDate` auditing +
`DEFAULT NOW()` safety net, `TIMESTAMPTZ`, no `CREATE TYPE`. **Additive
migration — no data reset** (V6/V7/V8 reset precedent does not apply; labels add
tables only).

New migration `V12__issue_labels.sql` (next free version):

```sql
CREATE TABLE labels (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    scope_project_id   UUID         REFERENCES projects(id) ON DELETE CASCADE,  -- reserved; always NULL for now
    name               VARCHAR(60)  NOT NULL,
    color              VARCHAR(9)    NOT NULL DEFAULT '#64748B',  -- hex #RRGGBB(AA); slate default
    description        VARCHAR(200),
    created_by         UUID         REFERENCES users(id) ON DELETE SET NULL,  -- for creator-can-edit rule
    archived_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT labels_scope_ck CHECK (scope_project_id IS NULL)  -- project scope reserved, not yet allowed
);

-- Case-insensitive uniqueness within a scope (workspace). Expression unique index
-- (a plain UNIQUE can't lower()). NULLS NOT DISTINCT so scope columns behave.
CREATE UNIQUE INDEX labels_scope_name_uk
    ON labels (scope_workspace_id, scope_project_id, lower(name)) NULLS NOT DISTINCT;

CREATE INDEX idx_labels_workspace ON labels(scope_workspace_id);

CREATE TABLE issue_labels (
    issue_id   UUID        NOT NULL REFERENCES issues(id)  ON DELETE CASCADE,
    label_id   UUID        NOT NULL REFERENCES labels(id)  ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, label_id)
);

CREATE INDEX idx_issue_labels_label ON issue_labels(label_id);  -- filter/usage-count by label
CREATE INDEX idx_issue_labels_issue ON issue_labels(issue_id);  -- (PK already covers issue-first, kept explicit for clarity of batch loads)
```

Notes:
- `color VARCHAR(9)` holds `#RRGGBB` or `#RRGGBBAA`; app validates the hex
  shape (same discipline as status/priority colors — those are `VARCHAR`).
- **No trigger for `updated_at`** is required beyond the existing DB convention;
  entity uses `@LastModifiedDate` (BaseEntity). `labels` uses `BaseEntity`
  (id/createdAt/updatedAt); `issue_labels` is a bare join (no entity id — either
  an `@IdClass`/`@Embeddable` composite key entity, or map as an
  `@ElementCollection`/`@ManyToMany` on `Issue`). **Recommended:** a thin
  `IssueLabel` `@Entity` with a composite key (mirrors how the codebase models
  join rows like `WorkflowStatus`/`FieldSetItem` explicitly rather than JPA
  `@ManyToMany`, which the project avoids for control over ordering/history).
- **DB-generated defaults** on `color` are fine; the app sets them too.
- Seeding: **none required**. Optionally seed 3–4 starter labels into the demo
  workspace via `DemoDataService` (e.g. "bug-bash", "customer-reported",
  "tech-debt") for a non-empty demo — recommended, small, and reuses the new
  service. If seeded, add the same rows to the test-mode reset block's cleanup
  (`DELETE FROM labels;` before `DELETE FROM workspaces;` is redundant given
  cascade, but harmless) — actually labels cascade from workspaces, so the
  existing `DELETE FROM workspaces` reset already clears them; no reset change
  needed.

Entity sketch (`com.hamstrack.issue.entity` — labels live with the issue
feature since they attach to issues; or a new `label` sub-package — recommend
`issue.entity` for cohesion with `IssueFieldValue`):

```java
@Entity @Table(name="labels")
class Label extends BaseEntity {           // id, createdAt, updatedAt
    @Column(name="scope_workspace_id", nullable=false) UUID scopeWorkspaceId;
    @Column(name="scope_project_id") UUID scopeProjectId;   // reserved, null
    @Column(nullable=false, length=60) String name;
    @Column(nullable=false, length=9)  String color;
    @Column(length=200) String description;
    @Column(name="created_by") UUID createdBy;
    @Column(name="archived_at") Instant archivedAt;
}

@Entity @Table(name="issue_labels")
class IssueLabel {                          // composite-key join, CreatedOnly
    @EmbeddedId IssueLabelId id;            // (issueId, labelId)
    @ManyToOne @MapsId("issueId") Issue issue;
    @ManyToOne @MapsId("labelId") Label label;
    @CreatedDate Instant createdAt;
}
```

---

## 7. API surface

All workspace-scoped, membership-guarded, 404 on non-member.

### 7.1 Label catalog (new controller `LabelController`)

```
GET    /api/workspaces/{wsId}/labels?includeArchived=false   # list (members); archived hidden by default
POST   /api/workspaces/{wsId}/labels                          # create (any member)   → 201
PATCH  /api/workspaces/{wsId}/labels/{id}                     # rename/recolor/describe (OWNER/ADMIN or creator)
POST   /api/workspaces/{wsId}/labels/{id}/archive            # archive (OWNER/ADMIN)
POST   /api/workspaces/{wsId}/labels/{id}/unarchive          # unarchive (OWNER/ADMIN)
DELETE /api/workspaces/{wsId}/labels/{id}?force=false         # delete (OWNER/ADMIN); 409 if in use unless force=true
GET    /api/workspaces/{wsId}/labels/{id}/usage              # { issueCount } — for the delete dialog
```

DTOs:

```
LabelResponse      { id, name, color, description, archived (bool), issueCount?, createdBy?, createdAt, updatedAt }
CreateLabelRequest { @NotBlank @Size(max=60) name, @Pattern(hex) color?, @Size(max=200) description? }
UpdateLabelRequest { name?, color?, description? }   // partial; null = leave
LabelUsageResponse { issueCount }
```

Status codes: 201 create; 200 list/patch/usage/archive; 204 delete; 400
validation (bad hex, blank name); 403→ **use 404** for non-member; 409 duplicate
name / delete-in-use-without-force; 422 never needed here (422 lives on the
issue side for bad attach).

### 7.2 Issue payload changes

- `CreateIssueRequest` **+ `List<UUID> labelIds`** (optional).
- `UpdateIssueRequest` **+ `List<UUID> labelIds`** (optional; present = full
  replacement set, absent = unchanged).
- `IssueResponse` **+ `List<LabelRef> labels`** where
  `LabelRef { id, name, color }` (archived labels included, flagged via a
  boolean or by omission — include with the label's archived state derivable;
  simplest: include `{id,name,color}` and let the client dim if the id is not in
  the active non-archived list — but to avoid a second lookup, add `archived`
  to `LabelRef`). **Decision:** `LabelRef { id, name, color, archived }`.
- Issue **list filter**: `GET .../issues?...&labelId={id}&labelId={id}&labelMatch=any|all`.

### 7.3 What does **not** change

- `GET .../projects/{p}/config` — labels are **not** added here (they are
  workspace-scoped, not project taxonomy; §4.2). The board/backlog fetch labels
  from `GET .../workspaces/{ws}/labels` separately (cache with TanStack Query
  key `['labels', wsId]`).
- `ProjectConfigService` — untouched.

### 7.4 Docs obligation

Update `openapi.yaml` (new `Labels` tag + paths + schemas; `labelIds` on issue
create/update; `labels` on `IssueResponse`; `labelId`/`labelMatch` query params)
and both `docs/api-*.md` (identical structure; no DC-only operator setting for
labels, so the DC "Operator settings" section is unchanged). Validate with
`npx @apidevtools/swagger-cli validate`.

---

## 8. Frontend impact

Stack: React 19 / TS / Vite / TanStack Query v5 / Zustand / lucide-react.
DESIGN.md compliance: labels are **chips** (`border-radius: full` — the badge
tier), warm-neutral surface, industrial/utilitarian register. Color dots/chips
follow the existing `PriorityBadge`/status-dot pattern in `components/ui.tsx`.
**Do not** use Tailwind `max-w-*` classes shadowed by our spacing scale (use
inline `maxWidth`); use absolute paths for links inside splat routes.

- **`components/labels.tsx`** (new, mirrors `components/fields.tsx`):
  - `LabelChip` — color dot + name, `full` radius, compact.
  - `LabelPicker` — multi-select typeahead over `['labels', wsId]`; filters
    existing, offers "Create label 'X'" (member-create) → `POST /labels` then
    add to the selection; validates duplicate client-side before POST.
  - Auto-color: a fixed palette (derive from DESIGN.md safety/neutral swatches +
    a few distinct hues — teal `#0F6E63`, amber `#B45309`, slate `#64748B`,
    error `#B91C1C`, success `#15803D`, plus a couple more) assigned round-robin
    or by name-hash so new labels get a sensible default the user can change.
- **`CreateIssueModal`** — add a `LabelPicker` (below custom fields); sends
  `labelIds`.
- **`IssueSidePanel`** — show label chips in the details header/area (read),
  and the `LabelPicker` in edit mode; PATCH sends the full `labelIds` set
  (diff-aware skip if unchanged).
- **Issue cards** (`BoardPage`) — render up to N label chips on the card
  (overflow "+k"), beneath/near the existing priority + assignee row.
- **`BoardPage` + `BacklogPage` filter bars** — add a label multi-select
  filter next to the existing priority filter; drives `labelId[]` (+ optional
  any/all toggle). Include the active labels in the query key
  (`['issues', wsId, projectId, 'board', {priority, labelIds, labelMatch}]`).
- **`BacklogPage`** — optionally add a "Labels" column showing chips (like the
  per-field columns it already appends). Recommended.
- **Workspace settings → Labels** (new section in
  `pages/settings/WorkspaceSettingsArea`, OWNER/ADMIN): a dense table (color
  dot, name inline-edit, description, usage chip, archived toggle, kebab:
  Edit / Archive / Delete-with-usage-dialog) — reuse `ArchivedToggle`,
  `UsageChip`, and the delete-confirm pattern from `pages/admin/common.tsx`.
  This is the "manageable somewhere sensible" home; the inline picker covers
  quick creation.
- **`api.ts` / `types.ts`** — `labels` API group (`list/create/update/archive/
  unarchive/remove/usage`), `Label`/`LabelRef` types; extend issue
  create/update/list calls with labels params.
- **Config-driven rendering:** labels are fetched live per workspace (not from
  project `config`); ensure cache invalidation on create/edit/delete
  (`['labels', wsId]`) and that changing a label recolors chips everywhere via
  query refetch.

---

## 9. DC / Cloud implications

Single codebase, profile-gated differences only. Labels have **no behavioral
DC/Cloud fork** — a workspace-scoped, membership-guarded, cascade-cleaned
concept behaves identically in both. Specifically:

- **Storage/auth/email/billing:** none touched. No new external dependency.
- **Tenancy:** workspace-scoping *is* the Cloud isolation boundary; the same
  code gives DC (single-tenant-ish) the same per-workspace labels for free. No
  cloud-only assumption.
- **New toggle:** a feature flag is **optional**. Recommendation: ship without a
  flag (labels are universally useful and low-risk). *If* the owner wants an
  operator kill-switch, add `app.labels.enabled` (env `LABELS_ENABLED`, default
  `true` in both base and per-profile) — when off, the label endpoints 404 and
  the SPA hides label UI (read the flag from public `GET /api/meta`, like
  `publicLandingEnabled`). Every new toggle needs env + per-profile default;
  this one defaults ON everywhere. **Default recommendation: no flag** unless a
  gating need surfaces.
- **Demo seeding:** if we seed starter labels (§6), it runs through
  `DemoDataService` and is already covered by `app.demo.seed-on-first-login`;
  no new toggle. DC-with-onboarding-off still gets demo labels via the existing
  first-auth seed path; Cloud gets them via create-team. Consistent.

---

## 10. Acceptance criteria (feeds test-runner)

Backend:
- [ ] `V12` applies additively (no reset); `labels` + `issue_labels` created;
      Hibernate `validate` passes (no `CHAR`/ENUM; `VARCHAR` widths match).
- [ ] Create label as a plain workspace member → 201; non-member → 404.
- [ ] Duplicate name case-insensitive within a workspace → 409; same name in a
      *different* workspace → 201 (scope isolation).
- [ ] Same name reused after the first is **archived**: still 409 (archived rows
      keep the unique slot) — or, if product wants reuse-after-archive, exclude
      archived from the unique index (partial index). **Decision: keep the slot
      (409)** — simpler, avoids resurrecting deleted meaning; rename/unarchive
      instead.
- [ ] Attach labels on issue create; response `labels` reflects them; foreign /
      archived / unknown label → 422.
- [ ] Update issue with `labelIds` = full replacement; add+remove both produce
      one history entry (field `"labels"`); no-op replacement writes no history.
- [ ] Stale `version` on a label-only PATCH → 409 (optimistic lock intact); no
      `@Version` double-jump (reads-before-mutate).
- [ ] Filter `?labelId=A&labelId=B` (OR) returns issues with A or B; `labelMatch=all`
      returns only issues with both; composes with `statusId`/`assigneeId`/`priorityId`
      via AND; no N+1 on label loading.
- [ ] Delete in-use → 409; `?force=true` detaches from all issues and deletes;
      archive in-use always succeeds and preserves associations.
- [ ] Deleting an issue cascades its `issue_labels`; deleting a workspace
      cascades its labels + joins.
- [ ] Cross-tenant: a member of WS-A cannot see, attach, or address WS-B labels
      (404/422), and label lists never leak across workspaces.

Frontend:
- [ ] `tsc` + `vite build` clean.
- [ ] Board & backlog show label chips; filter bar filters by label; active
      label filter reflected in the query key and cleared cleanly.
- [ ] Issue create modal and side panel attach/detach labels; on-the-fly create
      works and de-dupes against existing (case-insensitive).
- [ ] Workspace-settings Labels section: CRUD, recolor, archived toggle, usage
      chip, delete-with-usage dialog — all gated to OWNER/ADMIN.
- [ ] Chips render at `full` radius per DESIGN.md; no shadowed `max-w-*` classes.

Docs:
- [ ] `openapi.yaml` updated + validates; both `api-*.md` updated; `CLAUDE.md`
      state paragraph added.

---

## 11. Open questions (with recommended defaults)

1. **Who may create labels?** *Recommended: any workspace member* (low-friction
   tagging is the point; curation via managed surface + soft uniqueness). This
   is the **highest-risk assumption** — confirm before build. Alternative:
   OWNER/ADMIN-only (drop on-the-fly create).
2. **Filter default match mode.** *Recommended: OR (`any`)*, with `all`
   opt-in. Matches tag-filter intuition.
3. **Seed starter labels in the demo workspace?** *Recommended: yes*, 3–4 via
   `DemoDataService` so the feature isn't empty on first look. Small, reuses the
   new service, cleared by the existing reset cascade.
4. **Reuse a name after archive/delete?** *Recommended: no reuse while archived
   (409, keep the slot); delete frees the name.* Simpler than a partial unique
   index; avoids resurrecting stale meaning silently.
5. **Delete = detach-only, no remap.** *Recommended: yes* — labels carry no
   positional/config meaning, so Jira/status-style "remap to replacement" is
   needless. Offer archive as the safe path and `force` detach for hard delete.
6. **Feature flag?** *Recommended: none* (ship on); add `app.labels.enabled`
   only if an operator kill-switch is later requested.
7. **Colors: free hex vs fixed palette?** *Recommended: free hex in the managed
   editor (color picker), fixed-palette auto-assignment for on-the-fly creates.*
   Keeps quick-create sane while allowing precise curation.
8. **Max labels per issue?** *Recommended: soft cap ~20 in the UI, no hard DB
   limit* — avoids abuse without an arbitrary constraint.
```
