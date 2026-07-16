# Admin Console & Flexible Taxonomy — Proposal

Status: **approved 2026-07-15** (open questions resolved: issue-type sets deferred to M3 but shown in the matrix; single global catalog per instance with `scope_workspace_id` reserved; `priority` API shape change accepted). Priority icons: lucide chevron set (ChevronsUp / ChevronUp / Equal / ChevronDown / Minus) — the `icon` column stores the lucide name.
Mockups: `admin-console-mockup.html` (open in a browser).

## 1. Goal

Introduce a **global administrator** role and an **admin console** where statuses,
priorities and (custom) fields are managed centrally and flexibly bound to
projects — Jira-grade capability without Jira's scheme-hell UX. Regular users
must not access any of it. The role model must be extensible (more roles later,
including delegating parts of this to workspace admins).

## 2. Market analysis

| Product | Model | Take-away |
|---|---|---|
| **Jira DC/Cloud** | Global catalogs (statuses, priorities, custom fields) bound to projects through layered *schemes* (workflow scheme → workflow per issue type; screen scheme → screens; field configuration scheme…) | The **power benchmark**: reusable named configurations, per-project binding, per-issue-type granularity. The **UX anti-benchmark**: 3–4 indirection layers, "where is this used?" requires digging, editing a shared scheme silently affects other projects |
| **YouTrack** | Custom fields with per-project *bundles* (reusable value sets) | Bundles ≈ our "sets": one indirection layer is enough |
| **Asana** | Org-wide **field library**; fields added to projects individually | The friendliest custom-fields UX; "library → attach to project" is instantly understood |
| **ClickUp** | Statuses/fields defined at Space/Folder/List levels with inheritance | Inheritance without naming is convenient until it surprises you; prefer explicit named sets |
| **Linear** | Opinionated: fixed status categories per team, minimal admin | Proof that *status categories* (our `StatusCategory`) keep boards sane regardless of custom statuses |
| **Azure DevOps** | Inherited process templates | Template inheritance is powerful but heavyweight for our stage |
| **Plane (OSS)** | States per project, no global governance | What we're moving *away* from — no reuse, no governance |

**Synthesis**: global catalog (Jira) + one layer of named reusable sets
(YouTrack bundles / Jira schemes flattened) + library-attach ergonomics (Asana)
+ mandatory status categories (Linear). Explicit UX pillars that fix Jira's pain:

1. **Usage-first**: every catalog row shows "used in N workflows / M projects" — click to see exactly where. Nothing is edited blind.
2. **Impact preview**: destructive/риsky actions (delete status, remove field from set) show affected projects/issues *before* confirming, with required remapping built into the dialog.
3. **One assignment matrix**: a single Projects screen shows every project × its workflow / priority set / field set, editable in place — replaces four separate Jira scheme pages.
4. **Inline editing + drag reorder** in catalog tables; archive instead of delete when in use.

## 3. Role model

- `users.system_role VARCHAR(20) NOT NULL DEFAULT 'USER'` — Java enum `SystemRole { ADMIN, USER }`, extensible (e.g. `SUPPORT`, `AUDITOR` later). Independent of workspace/project roles.
- `User.getAuthorities()` returns `ROLE_<systemRole>`; SecurityConfig adds `.requestMatchers("/api/admin/**").hasRole("ADMIN")`. Admin endpoints live under `/api/admin/**` — one guard, no per-controller checks to forget.
- Bootstrap: the `seed.admin.*` account becomes `ADMIN`; a migration promotes it if it exists. Admins can promote/demote others later via a Users admin page (future milestone).
- `GET /api/auth/me` returns `systemRole`; the SPA shows the "System administration" menu item (user menu) and guards `/admin/**` routes client-side (server enforces regardless).
- **DC vs Cloud**: same code — in DC the global admin is the instance operator; in Cloud it's platform staff. No forked behavior.

## 4. Data model

### 4.1 Catalog (global now, workspace-scopable later)

Every catalog entity carries `scope_workspace_id UUID NULL`: `NULL` = global
(managed by system admin). This costs nothing now and gives the future
"workspace admins manage their own entries" for free — queries always filter
`scope IS NULL OR scope = :wsId`.

```
statuses      id, scope_workspace_id NULL, name, category(TODO|IN_PROGRESS|DONE),
              color, position, archived_at
priorities    id, scope_workspace_id NULL, name, color, icon, position, archived_at   -- NEW ENTITY
issue_types   id, scope_workspace_id NULL, name, color, icon, position, archived_at   -- existing, rescoped
field_defs    id, scope_workspace_id NULL, key(unique, mono), name, type, config JSONB,
              description, archived_at
              -- type: TEXT | TEXTAREA | NUMBER | DATE | SELECT | MULTI_SELECT |
              --       USER | CHECKBOX | URL
              -- config: options[] with id/label/color, min/max, default…
```

**Priorities stop being a Java enum.** `issues.priority VARCHAR` → `issues.priority_id FK`.
This is the one unavoidable breaking change; migration maps
URGENT/HIGH/MEDIUM/LOW/NONE onto seeded catalog rows 1:1, so existing clients
of `IssueResponse` change shape (`priority` becomes an object like `status`).

### 4.2 Reusable sets (the single indirection layer)

```
workflows             id, scope_workspace_id NULL, name, description, is_system_default
workflow_statuses     workflow_id, status_id, position            -- which statuses, board order
workflow_transitions  workflow_id, from_status_id NULL(=any), to_status_id

priority_sets         id, scope NULL, name, is_system_default
priority_set_items    set_id, priority_id, position, is_default   -- default for new issues

field_sets            id, scope NULL, name, is_system_default
field_set_items       set_id, field_id, position, required, show_on_create
```

### 4.3 Binding + values

```
projects              + workflow_id NULL, priority_set_id NULL, field_set_id NULL
                      -- NULL = the system-default set ("Default workflow" etc.)
issue_field_values    issue_id, field_id, value JSONB     -- one row per filled field
```

Semantics:
- Board columns / allowed transitions come from the project's **workflow** (replaces today's workspace-level `statuses` + `status_transitions`).
- Issue create/edit offers the project's **priority set** (with its default) and renders the project's **field set** (order, required, on-create visibility).
- Editing a shared set warns: "This workflow is used by 7 projects" (usage-first).
- A status/priority/field can't be hard-deleted while referenced; the delete dialog offers **remap** (choose replacement; bulk-updates issues) or **archive** (hidden from new use, history intact).

### 4.4 Migration strategy

Prod is in test mode with an approved reset precedent and automatic demo
reseeding on next login. The migration therefore: creates new tables, seeds the
global catalog (statuses To Do/In Progress/Done; priorities Urgent…None;
issue types Bug/Task/Story/Epic; "Default" workflow/priority set/field set),
**wipes workspaces/issues again** and re-arms demo seeding (same block as V5).
`WorkspaceService.create` stops seeding per-workspace statuses/types;
`DemoDataService` switches to catalog lookups. Old `statuses`/`issue_types`
workspace-scoped rows and `status_transitions` are dropped/replaced.

## 5. API surface (new, all under `/api/admin`, system-ADMIN only)

```
GET/POST/PATCH/DELETE  /api/admin/statuses[/{id}]        (+ GET /{id}/usage, POST /{id}/archive)
GET/POST/PATCH/DELETE  /api/admin/priorities[/{id}]      (+ reorder: PATCH position)
GET/POST/PATCH/DELETE  /api/admin/issue-types[/{id}]
GET/POST/PATCH/DELETE  /api/admin/fields[/{id}]
GET/POST/PATCH/DELETE  /api/admin/workflows[/{id}]       (statuses + transitions in body)
GET/POST/PATCH/DELETE  /api/admin/priority-sets[/{id}]
GET/POST/PATCH/DELETE  /api/admin/field-sets[/{id}]
GET                    /api/admin/projects               (all projects + bindings — the matrix)
PATCH                  /api/admin/projects/{id}/bindings (workflow_id / priority_set_id / field_set_id)
```

Read-side (non-admin) additions: project-scoped
`GET /api/workspaces/{ws}/projects/{p}/config` returning effective statuses,
transitions, priorities, fields for the UI (board, create modal, issue panel).
Existing workspace-scoped `/statuses` + `/issue-types` + `/status-transitions`
endpoints are retired (breaking, acceptable in beta).

## 6. Admin console UX

Entry: user menu → **System administration** (only for `ADMIN`). Route area
`/admin/**` — keeps the global dark top bar, swaps the project sidebar for an
admin sidebar (same two-level shell pattern as DESIGN.md prescribes):

```
Sections:  Statuses · Priorities · Issue types · Fields · Workflows · Projects
Future:    Users · Workspaces · Settings
```

Screen inventory (all in the HTML mockup):

1. **Statuses** — dense table: drag handle, color dot, name (inline edit), category badge (mono), usage chip, kebab (Edit / Archive / Delete). Delete opens the impact dialog with remap select.
2. **Priorities** — same pattern + "default" marker lives in *sets*, not the catalog.
3. **Fields** — table (name, `key` in mono, type chip, options preview, usage) + right side panel editor (matches the issue-panel pattern already in the app): name, key, type, options list with colors, description, per-set flags shown read-only.
4. **Workflows** — master list (name, ordered status chips, usage) + editor: statuses in workflow (ordered, add from catalog) and transition rules ("Any → …", "… → …") as a compact list. A visual graph editor is a possible later upgrade; list-first is honest and shippable.
5. **Projects** (assignment matrix) — one row per project across all workspaces: key, name, workspace, three set selects (workflow / priorities / fields) editable in place; filter by workspace/set. This single screen replaces Jira's scattered scheme-assignment pages.

## 7. Delivery phases (after approval)

- **M1 — role + console + statuses/priorities/workflows**: `system_role`, security guard, admin shell UI; catalog CRUD for statuses & priorities (incl. priority enum→entity migration); workflows + transitions; project binding (workflow + priority set); board/create-modal reading effective config. Biggest milestone — touches issue read/write paths.
- **M2 — custom fields**: field defs, field sets, `issue_field_values`, rendering in create modal + issue panel + backlog table columns.
- **M3 — polish & insight**: assignment matrix bulk ops, usage popovers everywhere, impact-preview dialogs, archive flows, issue-type sets (если понадобится Jira-подобное «типы на проект»).

## 8. Open questions (recommendations inline)

1. **Issue types** — включать их в ту же модель сетов сейчас (типы на проект, как в Jira) или оставить глобальный список на всех? *Рекомендация: каталог + глобальный список в M1, сеты типов отложить до M3 — редко нужно раньше.*
2. **Насколько «глобален» глобальный каталог в Cloud**: все тенанты видят один каталог (куратор — платформа). Колонка `scope_workspace_id` уже заложена под будущее делегирование workspace-админам. *Рекомендация: да, так.*
3. **Поле `priority` в API** меняет форму (enum-строка → объект) — ломает текущий фронт и OpenAPI; правим всё в M1 одним махом. *Рекомендация: да, в бете это дёшево.*
