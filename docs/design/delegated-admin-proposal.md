# Delegated Administration (Workspace & Project admins) — Proposal

Status: **P1–P4 shipped 2026-08-05** (concept approved 2026-08-04: layered scope, full project
self-serve, reuse of existing roles). Backend + both UIs + docs done; frontend not browser-QA'd.
Deferred: per-issue-type workflows (open question below). Supersedes the "future workspace-admin
delegation" note reserved in `admin-console-proposal.md` (M1).

## 1. Goal

Introduce **two delegated administration tiers** on top of the existing instance admin, in
**both Cloud and DC** (config/profile differences only, never forked code):

- **Workspace admin** ("global" in the owner's wording) — configures everything inside a
  workspace they own: workspace-scoped taxonomy/sets and the binding matrix for all its projects.
- **Project admin** — configures only their own project: its private workflow, fields,
  priorities, types, and bindings. Not about creating issues — about project *settings*.

Jira-inspired capability, without Jira's scheme-hell and without its escalation tax.

## 2. Market analysis (what we borrow / avoid)

| Product | Model | Take-away |
|---|---|---|
| **Jira** | 4 tiers (Org → Site → Product → Project admin). Project admin can only *add existing* statuses/fields + reorder; **cannot create** statuses/fields/workflows. "Extended project admin" (edit project workflow) is DC-only; Cloud replaced it with team-managed projects. | Power benchmark. Anti-benchmarks: (a) "ridiculously complex", basic config needs a global admin → teams blocked; (b) **editing a shared scheme silently breaks other projects** — the #1 complaint; (c) role sprawl (>50 roles). |
| **Linear** | Single **team owner** role scoped to a team; owner decides whether all members or only owners manage statuses/labels/templates/settings. | Simplicity benchmark: full self-serve autonomy at the delegated level, no separate "workflow/field admin" roles, no escalation. |
| **Asana** | Org-wide field library + granular field perms (Field admin/Editor/User), "locked" fields. | Library→attach ergonomics; per-field governance is optional polish, not core. |
| **YouTrack** | Per-project reusable bundles. | One indirection layer (our "sets") is enough. |

**Synthesis:** give the delegated admin **Linear-grade autonomy** (self-serve, no escalation)
while keeping **governed reusable sets** (already built). Close Jira's #1 pain with a hard rule:
**a delegated admin edits only config private to their scope; shared/inherited config is
select-only.** Editing a truly shared set stays with the tier that owns it and shows the
existing `ImpactBanner` ("used by N projects").

## 3. Role model — reuse existing roles (fork 3 ✓)

No new enums, no role sprawl. Three tiers map onto roles we already have:

| Tier | Role(s) | Scope | Cloud / DC |
|---|---|---|---|
| **Instance admin** | `SystemRole.ADMIN` | whole instance + all global-scoped config | Cloud = platform staff; DC = operator. **Unchanged.** |
| **Workspace admin** | `WorkspaceRole.OWNER` **and** `ADMIN` | one workspace | owner/admin of that workspace in both models |
| **Project admin** | `ProjectRole.MANAGER` | one project | manager of that project in both models |

Capabilities are *additive down the tree*: an instance admin can do everything a workspace
admin can, who can do everything a project admin can — but each edits config **at or below its
own scope** only.

## 4. Data model — layered scope (fork 1 ✓)

Every catalog primitive and every set already carries `scope_workspace_id UUID NULL` (V6/V7/V8,
reserved for exactly this). Add **`scope_project_id UUID NULL`** to the same tables:

- Catalog primitives: `statuses`, `priorities`, `issue_types`, `field_defs`
- Sets: `workflows`, `priority_sets`, `field_sets`, `issue_type_sets`

Scope is a three-state, mutually exclusive tuple enforced by a CHECK constraint
(`scope_workspace_id` and `scope_project_id` not both non-null):

| scope_workspace_id | scope_project_id | Meaning | Managed by |
|---|---|---|---|
| NULL | NULL | **global** | instance admin |
| set | NULL | **workspace-scoped** | that workspace's admins |
| NULL | set | **project-private** | that project's manager(s) |

**Migration is additive** — add the nullable column + CHECK; all existing rows are global
(both NULL). **No data reset.** (V6/V7/V8 reset precedent does not apply here.)

**Effective config resolution** stays centralized in `ProjectConfigService` — the *only* place
that resolves effective taxonomy. A project sees primitives/sets where
`scope global OR scope_workspace_id = project.workspace_id OR scope_project_id = project.id`
(archived filtered). Issue read/write paths and the public `.../config` endpoint go through it,
unchanged in shape.

## 5. Authorization

`/api/admin/**` stays a single `hasRole("ADMIN")` line — the instance console is unchanged and
is the super-admin view over *all* scopes.

Two new resource-scoped surfaces, guarded in the **service layer** (like existing
`requireWorkspaceRole` / `requireProjectRole` — NOT a blanket SecurityConfig matcher, because
the check needs a membership lookup; return 404 on non-membership, never 403, per the tenancy rule):

```
/api/workspaces/{ws}/admin/{statuses|priorities|issue-types|fields|workflows|
                            priority-sets|field-sets|issue-type-sets}   # WorkspaceRole OWNER/ADMIN
/api/workspaces/{ws}/admin/projects [+ /{id}/bindings]                  # binding matrix for ws projects

/api/workspaces/{ws}/projects/{p}/admin/{...same catalog & sets...}     # ProjectRole MANAGER
/api/workspaces/{ws}/projects/{p}/admin/bindings                        # this project's bindings
```

These reuse the existing `Admin*Service` logic, parametrized by a resolved **scope context**
(global / ws / project) that stamps `scope_*` on create and filters/authorizes edits:

- A caller may **create/edit/delete only rows at its own scope.** Inherited rows (higher scope)
  are visible **read-only** and **select-only** for bindings.
- **Binding validation:** a project-private set may be bound only to *its* project; a
  workspace-scoped set only to projects in *its* workspace. Guarded (409) on violation.
- Existing integrity guards carry over per scope: in-use delete needs remap; no empty
  workflow/set; no status removal / workflow change that strands issues in board-invisible
  statuses. Editing a shared (ws/global) set shows `ImpactBanner`.

This structurally eliminates Jira's #1 pain: a project admin **cannot** touch a shared set, so
they can never silently break another project.

## 6. Capability matrix

| Action | Instance admin | Workspace admin | Project admin |
|---|---|---|---|
| Global catalog & sets (CRUD) | ✅ | select-only | select-only |
| Workspace catalog & sets (CRUD) | ✅ (any ws) | ✅ (own ws) | select-only |
| Project-private catalog & sets (CRUD) | ✅ | ✅ (ws projects) | ✅ (own project) |
| Bind sets to a project | ✅ | ✅ (ws projects) | ✅ (own project) |
| Workspace members / projects | ✅ | ✅ | — (project members only, already exists) |
| Project members / roles | ✅ | ✅ | ✅ (already exists) |

## 7. Frontend

Reuse the admin-console building blocks (catalog tables, set editors, projects matrix,
`UsageChip`, `ImpactBanner`, delete-with-remap dialog) — parametrized by scope context and a
`readOnly` flag for inherited rows:

- **Instance console** `/admin/**` — unchanged (shows all scopes to super admin).
- **Workspace settings** — new area (e.g. `/w/:wsId/settings/**`), entered from the workspace;
  shows ws-scoped rows editable + global rows read-only, plus the ws project matrix. Label it
  **"Workspace settings"** (not "global") to avoid confusion with the instance console.
- **Project settings** — the `Sidebar` already has a **Settings** placeholder on project routes;
  that becomes the project-admin home (workflow, fields, priorities, types, bindings, members).
  Shows project-private rows editable + inherited (ws/global) read-only.

Client-side route guards mirror the server (`WorkspaceRole`/`ProjectRole` from membership);
server enforces regardless.

## 8. Delivery phases (after go-ahead)

- **P1 — scope plumbing + authorization (backend).** ✅ **Done** (full suite 28/28).
  - ✅ **Data model** — `V11__delegated_admin_scope.sql`: `scope_project_id` on all 8 catalog/set
    tables, CHECK (ws/project not both set), per-scope UNIQUE, partial indexes. `scopeProjectId`
    field + `Scoped` interface on the 8 entities. Additive, all existing rows global.
  - ✅ **Scope core + bindings surface** — `admin.scope.ScopeResolver` (membership-based
    workspace-OWNER/ADMIN and project-MANAGER auth; 404 non-member / 403 insufficient-role);
    `findAllBindableForProject` on the 4 set repos (global ∪ own-ws ∪ own-project); 
    `ScopedProjectAdminService` + `WorkspaceAdminController` (`/api/workspaces/{ws}/admin/projects`,
    `/binding-options`) and `ProjectAdminController`
    (`/api/workspaces/{ws}/projects/{p}/admin/bindings`, `/binding-options`) with scope-isolated
    binding validation (422 on a set not visible to the project). `DelegatedAdminBindingTest`
    (7 tests: authz 403/404, cross-tenant + cross-project 422, happy paths).
  - ✅ **Scoped catalog CRUD** — `ScopeContext` (stamp/owns) threaded through `AdminCatalogService`
    (statuses/priorities/issue types); exact-scope repo queries (`findAllAtScope`,
    `existsAtScopeAndName`, `findByIdAtScope`); catalog endpoints added to both delegated controllers
    (list/create/update/archive/unarchive/delete) via a `scope(actor,…)` authorize-and-return helper.
    `IssueService.resolve*` loosened to `findById` (any scope — `ProjectConfigService.requireXInSet`
    stays the gate). `DemoDataService` global lookups switched to `findAllAtScope(null,null)` (the
    first scoped writer exposed the leak: a `scope_workspace_id IS NULL` filter also matches
    project-scoped rows). Tests: `DelegatedAdminBindingTest` now 11 (project-private status invisible
    globally, project admin can't edit a global row → 404, per-scope name uniqueness, non-manager 403).
    Full suite 22/22.
  - ✅ **Scoped set CRUD (workflows, priority sets, issue-type sets)** — `ScopeContext` gained an
    ancestor-workspace (for child visibility) + `visibleWorkspaceId/ProjectId`; `ScopeContext.project`
    now takes `(wsId, projectId)`. Threaded through `AdminWorkflowService`/`AdminPrioritySetService`/
    `AdminIssueTypeSetService`: exact-scope set queries (`findAllAtScope`/`existsAtScopeAndName`/
    `findByIdAtScope`); a scoped set may only reference **children visible to its scope**
    (`findByIdVisibleTo` on Status/Priority/IssueType → 422 on a foreign/other-project child).
    Set endpoints added to both delegated controllers; `AdminWorkflowController` passes
    `ScopeContext.global()`. `AdminProjectService` global matrix tightened to bind only global sets
    (`findByIdAtScope(id,null,null)`; added `FieldSet.findByIdAtScope`). Tests: `DelegatedAdminBindingTest`
    now 14 (project-private workflow from own status; foreign-status child → 422; scoped workflow
    invisible in global console). Full suite 25/25.
  - ✅ **Scoped fields** — `AdminFieldService` (field defs + field sets) threaded with `ScopeContext`;
    `FieldDefRepository` exact-scope + `findByIdVisibleTo`, `FieldSetRepository` `findAllAtScope`/
    `existsAtScopeAndName`; field + field-set endpoints on both delegated controllers; `AdminFieldController`
    passes global. A field set may only include fields visible to its scope (→ 422). `DemoDataService`
    field-by-key/name lookups switched to both-scope-null variants (same leak class as primitives — a
    project field could reuse key `story_points`). `FieldValueService` needs no change: it resolves an
    issue's fields through the project's bound field set (JPA relations), not an id/scope lookup. Tests:
    3 more (private field+set; foreign-field child → 422; private field invisible globally). Full suite 28/28.

  **P1 acceptance:** three tiers wired — instance (`/api/admin/**`), workspace
  (`/api/workspaces/{ws}/admin/**`), project (`/api/workspaces/{ws}/projects/{p}/admin/**`) — each with
  catalog (statuses/priorities/issue-types/fields) + set (workflows/priority/field/issue-type-sets) CRUD +
  bindings, scope-isolated (own-scope edit only; children visible-scope only; global console excludes
  scoped rows; matrix binds global only). Next: **P2 — Project Settings UI**.
- **P2 — Project settings UI.** ✅ **Done** (frontend `tsc`+build clean; backend 28/28; not browser-QA'd).
  - **Taxonomy bindings** — Sidebar "Settings" real link (MANAGER-gated; `myRole`), route
    `/w/:wsId/p/:projectId/settings/*` under `AppShell` (keeps the project sidebar). `projectAdminApi`
    in `api.ts`; `BindingOptions`/`SetOption` types. `ProjectBindingsPage` assigns
    workflow/issue-types/priorities/fields from sets visible to the project (scope tag on non-global
    options), invalidates `['projectConfig', ws, proj]`.
  - **Project-private catalog & set CRUD** — the `pages/admin/*` catalog/set pages are now
    scope-agnostic: admin API groups became base-path factories (`makeAdminApi(base)` → `globalAdminApi`),
    and the pages read their API + query-key namespace + scope-aware copy from `pages/admin/AdminApiContext`
    (`AdminApiProvider`/`useAdminApi`/`useAdminInvalidate`). `AdminArea` provides the global scope;
    `ProjectSettingsArea` adds tabs (Taxonomy · Statuses · Workflows · Issue types · Priorities · Fields)
    and provides the project scope (`keyPrefix ['project-admin', ws, proj]`, `extraInvalidate` projectConfig).
    Usage popover endpoints (`GET …/{status,priorities,issue-types,fields}/{id}/usage`) added to both
    delegated controllers for parity. So a MANAGER builds a project-private status set + workflow and
    binds it, all self-serve. Test: `projectAdminCanReadCatalogUsage` (suite 18 in the class).
  - **Inherited rows read-only** (fix, 2026-08-05) — delegated catalog/set lists now show
    `global ∪ workspace ∪ project` (not just own-scope, which looked empty until you created
    something). Each Admin*Response carries a `scope` tag (`Scoped.scopeLabel()`); repos gained
    `findAllVisibleTo`; delegated `list()` uses visible-not-own; the frontend renders inherited rows
    with an `InheritedBadge` and no Edit/Archive/Delete (writes are still guarded server-side by
    `findByIdAtScope`). Guard test `projectCatalogListIncludesInheritedGlobalRows`.
  - _Not visually QA'd_ ([[browse-blocked-wdac]]) — validated by `tsc`, vite build and the backend suite.

## Open question — per-issue-type workflows (raised 2026-08-05)

Current model binds **one workflow per project** (`projects.workflow_id`). Jira maps issue types →
workflows via a *workflow scheme*. Supporting "each issue type its own workflow" is a real milestone:
a project × issue-type → workflow mapping, `ProjectConfigService` resolving the workflow per issue,
board/transition validation keyed by the issue's type, and UI for the mapping. Deferred — needs owner
sign-off before scheduling (it is not part of P1–P4).
- **P3 — Workspace settings UI.** ✅ **Done.** Entry: "Workspace settings" button on `WorkspaceHomePage`
  (workspace OWNER/ADMIN, `myRole`). Route `/w/:wsId/settings/*` under `AppShell` (no project sidebar).
  `pages/settings/WorkspaceSettingsArea` = admin-style left nav (Projects · Statuses · Workflows · Issue
  types · Priorities · Fields) + workspace-scoped `AdminApiProvider` (`makeAdminApi('/workspaces/{ws}/admin')`,
  `keyPrefix ['ws-admin', ws]`). `WorkspaceProjectsMatrix` (ws projects × 4 bindings, `workspaceAdminApi`).
  Reuses the same catalog/set pages (global rows inherited/read-only, ws rows editable). Frontend build clean.
- **P4 — docs.** ✅ **Done.** `docs/api-cloud.md` + `docs/api-dc.md` got a **Delegated administration**
  section + updated Roles table + config intro; `openapi.yaml` got a **Delegated admin** tag, the
  workspace/project binding + representative catalog paths, `scope` on the 8 Admin* schemas, and new
  `AdminScope`/`SetOption`/`BindingOptions` schemas (validates via swagger-cli).

## 9. Open items / risks

1. **Terminology:** owner calls the workspace tier "global admin"; UI must say "Workspace
   settings" so it isn't confused with the instance console (the true global admin).
2. **DC single-instance:** works unchanged — instance admin = operator; workspace/project
   delegation is pure per-membership behavior, no cloud-only assumption.
3. **Primitive fragmentation:** project-private primitives are visible only in their project, so
   the shared catalog stays curated (avoids Plane-style chaos) while keeping self-serve.
4. **Instance console scope filter:** the existing `/admin/**` tables must start showing/filtering
   by scope (currently all rows are global, so it renders unchanged until delegated rows exist).
