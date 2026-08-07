# Cross-Tenant Data-Exposure Audit — Global Catalog & Admin Console

Status: **Analysis / spec — not yet built.** Owner-requested systematic audit (2026-08-06) of the
data-exposure risk introduced by the M1 global-catalog + global-admin design once Hamstrack runs
in Cloud (multi-tenant). Companion to `admin-console-proposal.md` (M1–M3) and
`delegated-admin-proposal.md` (P1–P4 shipped). This document catalogs every leak surface, states
the required behavior per deployment mode, recommends an overarching model, and lists prioritized
remediation with acceptance criteria.

**Highest-risk assumption flagged up front:** the team may assume the leak is confined to the
system-admin console (`/api/admin/**`), which in Cloud is platform staff only. **It is not.** The
delegated workspace/project admin consoles (shipped, membership-authorized, reachable by ordinary
Cloud customers) reuse the *same* usage/count computation, which spans **all** projects across
**all** tenants. A workspace OWNER inspecting an inherited global status today can enumerate other
customers' project keys, names, and issue counts. This is a live tenant-isolation defect, not a
future/theoretical one.

---

## 1. Problem & goal

Since M1 the taxonomy (statuses, priorities, issue types, custom fields, and their sets/workflows)
is a **single global catalog** shared by every workspace. Projects reach it through bindings;
`ProjectConfigService` resolves the *effective* per-project config. This is correct and desirable
in **DC** (one operator owns the whole instance — there is nothing to isolate). In **Cloud**, the
same design means any feature that **aggregates, counts, lists, or cross-references** catalog usage
across the global catalog can surface one tenant's data to another.

The canonical example: a shared status "Open" shows "used in N projects" plus a usage-detail
popover listing the containing workflows/sets, the **projects** (id/key/name) that use it, and a
global **issue count**. In Cloud that N and that project list span all customers.

**Goal:** enumerate every such surface; define the required behavior in DC vs Cloud; decide whether
the global admin console should exist in Cloud at all; and produce a prioritized, testable
remediation plan that closes the leaks without forking the codebase.

**Success looks like:** in Cloud, no authenticated caller (system-admin excepted, per policy below)
can learn the existence, name, key, or issue/usage counts of any workspace/project they are not a
member of, through any catalog, usage, count, matrix, binding-options, or error-message surface. In
DC, behavior is unchanged (global visibility is correct).

---

## 2. Scope

**In scope**

- All `/api/admin/**` catalog/set/matrix/usage surfaces (system admin).
- All `/api/workspaces/{ws}/admin/**` and `/api/workspaces/{ws}/projects/{p}/admin/**` delegated
  surfaces (workspace/project admin) that reuse the same services.
- Usage counts (`UsageInfo`), usage detail (`UsageDetailResponse`), the projects matrix
  (`AdminProjectService`), binding options, and the 409/422 integrity-guard messages.
- ID / name / key enumeration and information disclosure via error messages.
- Profile/property gating strategy for DC vs Cloud.

**Out of scope (verified clean or separate concern)**

- The public effective-config endpoint `GET /.../projects/{p}/config` — membership-scoped, returns
  only one project's effective config, no cross-tenant aggregation (verified, §4.9).
- The delegated **binding** validation in `ScopedProjectAdminService` — correctly scope-isolated
  (`requireBindable`, `findAllBindableForProject`), no leak (verified, §4.8).
- General workspace/project/issue tenancy (owned by `tenancy-reviewer`); auth/JWT/uploads
  (`security-officer`). This audit is specifically the **global-catalog aggregation** leak class.
- Building the fix. This is a spec; `backend-builder`/`frontend-builder` implement after approval.

**Non-goals**

- Redesigning the catalog data model (the scope columns from delegated-admin already exist).
- Changing DC behavior (it is correct today).

---

## 3. Actors & permissions

| Actor | How authorized today | Cloud meaning | DC meaning |
|---|---|---|---|
| **System admin** | `SystemRole.ADMIN` → `hasRole("ADMIN")` on `/api/admin/**` | **platform staff** (Hamstrack operators) | the self-hosting operator |
| **Workspace admin** | `WorkspaceRole.OWNER`/`ADMIN` via `ScopeResolver` on `/api/workspaces/{ws}/admin/**` | an ordinary **paying customer** | a workspace owner |
| **Project admin** | `ProjectRole.MANAGER` via `ScopeResolver` on `/api/workspaces/{ws}/projects/{p}/admin/**` | an ordinary **customer** | a project manager |
| **Regular member** | workspace membership | ordinary customer | ordinary user |

The critical Cloud insight: **workspace admin and project admin are untrusted customer roles.** Any
cross-tenant data reachable by them is a tenant-isolation breach. Only **system admin** legitimately
sees the whole instance — and in Cloud even that should be a deliberate, documented operator
capability, not something surfaced to customers.

---

## 4. Exposure surface catalog

Severity key: **Critical** = customer-reachable cross-tenant leak in Cloud today; **High** =
system-admin-reachable global aggregation that leaks tenant identity/counts (acceptable only if the
console is operator-only and profile-gated); **Medium** = enumeration / info disclosure via
messages; **Low** = latent / defense-in-depth.

### 4.1 Usage counts — `UsageInfo.projects` (via `ProjectCountService`) — **CRITICAL**

- **Where:** `AdminCatalogService.statusUsage/priorityUsage/issueTypeUsage`,
  `AdminFieldService.fieldUsage`, and the `projectsUsing*` counters in
  `ProjectCountService` / `AdminWorkflowService.toResponse` / `AdminPrioritySetService.toResponse` /
  `AdminFieldService.toSetResponse`. Returned in every `Admin*Response` list from **all three**
  consoles (system, workspace, project).
- **Exposed data:** a `projects` count that, for a **global** row (and every row is global until
  delegated rows exist), sums projects across **all workspaces/tenants**. `ProjectCountService`
  queries `ProjectRepository.countBy*` / `countBy*IsNull` with **no workspace filter**.
- **How it leaks in Cloud:** a workspace/project admin lists their catalog; inherited global rows
  (shown read-only by design, delegated-admin P2) carry `usage.projects` = the global total. The
  admin learns roughly how many projects **across the whole platform** use "Open"/"Bug"/etc.
  Combined with the usage-detail popover (§4.2) this becomes concrete tenant enumeration.
- **DC:** correct — one tenant, global == local.
- **Required behavior:**
  - **Cloud, delegated consoles:** counts must be **scoped to projects the caller can access**
    (workspace admin → projects in that workspace; project admin → that one project). For an
    inherited global row this means "N of *your* projects use this", not the platform total.
  - **Cloud, system console:** see §5 — if the system console remains operator-only and
    profile-gated, a global total is acceptable (operator legitimately sees the instance);
    otherwise it must also be scoped/removed.
  - **DC:** unchanged (global total).

### 4.2 Usage detail popover — `UsageDetailResponse` — **CRITICAL**

- **Where:** `AdminCatalogService.statusUsageDetail/priorityUsageDetail/issueTypeUsageDetail`,
  `AdminFieldService.fieldUsageDetail`. Exposed on **all three** consoles at
  `GET .../{statuses|priorities|issue-types|fields}/{id}/usage`.
- **Exposed data:** `UsageDetailResponse` = names of containing `workflows`/`sets`, a deduped list
  of **`projects` (id, key, name)**, and a global **`issues`** count. The projects come from
  `ProjectCountService.projectsListUsing*`, which lists **all** projects instance-wide (including
  `...IdIsNull` unbound projects when a system default is involved — so a system-default row lists
  essentially every project on the platform).
- **How it leaks in Cloud:** worse than §4.1 — this hands the caller **actual project ids, keys,
  and names of other tenants**, plus a cross-tenant issue count. The delegated controllers
  (`WorkspaceAdminController`/`ProjectAdminController`) pass a scoped `ScopeContext`, but the
  service **ignores the scope for the usage computation** — `statusUsageDetail` uses
  `projectCountService.projectsListUsingWorkflow(wf)` with no scope argument. So a Cloud workspace
  admin opening the usage popover on an inherited global "Open" gets the full cross-tenant project
  list. **This is the flagship live defect.**
- **DC:** correct.
- **Required behavior:**
  - **Cloud, delegated consoles:** `projects` and `issues` must be **filtered to the caller's
    accessible scope** (its workspace's projects / its one project). Names/counts of workflows and
    sets that are *global* are catalog metadata (not tenant data) and may remain, but the
    **project list and issue count must be scope-filtered**. Recommended: pass the `ScopeContext`
    (or the caller's visible-project-id set) all the way into `ProjectCountService` and filter.
  - **Cloud, system console:** operator-only per §5; full detail acceptable there if gated.
  - **DC:** unchanged.

### 4.3 `ProjectCountService` itself (root cause) — **CRITICAL (shared root cause)**

- **Where:** `admin/service/ProjectCountService.java`.
- **Problem:** every method (`projectsUsingWorkflow`, `projectsUsingPrioritySet`,
  `projectsUsingIssueTypeSet`, `projectsListUsing*`, and `AdminFieldService.projectsUsing`) issues
  **unscoped** `ProjectRepository` queries. It is the single choke point behind §4.1 and §4.2.
- **Required behavior:** introduce a **scope-aware** variant of each method that accepts the
  caller's accessible-project filter (null = whole instance, for the operator/DC path). Every
  delegated-console call path must pass a non-null filter in Cloud. This is the highest-leverage
  single fix — closes §4.1 and §4.2 for the delegated consoles at once.

### 4.4 Integrity-guard 409/422 messages — cross-tenant usage disclosure — **HIGH**

- **Where:** `AdminCatalogService.deleteStatus/deletePriority/deleteIssueType`,
  `AdminWorkflowService.update/delete`, `AdminPrioritySetService.delete`,
  `AdminIssueTypeSetService.delete`, `AdminFieldService.deleteField/deleteSet`,
  `AdminProjectService.updateBindings`, `ScopedProjectAdminService.applyBindings`.
- **Exposed data:** guard messages embed **global** aggregate numbers, e.g.
  `"<N> issues use this status — pass replaceWithId…"` (`issueRepository.countByStatus` — all
  tenants), `"<N> projects use this workflow — reassign them first"`
  (`projectCountService.projectsUsingWorkflow` — all tenants),
  `"<N> issues are in status '<name>' — move them…"`
  (`countByStatusInWorkflowProjects` — spans all projects on the workflow),
  `"<N> projects use this field set — reassign them first"`.
- **How it leaks in Cloud:** a workspace/project admin editing/deleting an **own-scope** row is
  fine, but the guards for shared behavior and the delete-in-use checks count **globally**. A
  delegated admin can provoke a 409 whose number reveals cross-tenant issue/project usage of a
  shared catalog entry. Also an oracle: the presence/absence of a guard reveals whether *other*
  tenants use the entry.
- **DC:** correct.
- **Required behavior:**
  - **Cloud, delegated consoles:** delegated admins may only create/edit/delete **own-scope** rows
    (already enforced by `findByIdAtScope`), so the *integrity counts* for those operations are
    naturally scoped to own-scope children — but the issue/project counts must be **filtered to the
    caller's accessible projects** so the surfaced number never includes another tenant's work.
    Where a shared (global) entry cannot be safely counted per-tenant, prefer a **generic message**
    (no number) in Cloud.
  - **Cloud, system console:** operator-only per §5; global numbers acceptable if gated.
  - **DC:** unchanged.

### 4.5 Projects matrix — `AdminProjectService.list()` — **HIGH (system console)**

- **Where:** `AdminProjectService.list()` → `ProjectBindingResponse` (project id, key, name,
  archived, **workspace id + workspace name**, four binding ids).
- **Exposed data:** **every project across every workspace** with its workspace name — a full
  cross-tenant inventory of the instance.
- **How it leaks in Cloud:** by design this is the *system* matrix (only `/api/admin/**`,
  operator-only). It is **not** reachable by delegated admins (they use
  `ScopedProjectAdminService.workspaceMatrix`, correctly workspace-filtered — verified). So this is
  acceptable **iff** the system console is operator-only and profile-gated per §5. If the system
  console were ever exposed to customers, this is a full-instance data dump.
- **DC:** correct.
- **Required behavior:** keep operator-only; ensure §5 gating. No customer path may reach
  `AdminProjectService.list()`.

### 4.6 Catalog/set list endpoints — global rows & scope tags — **MEDIUM**

- **Where:** `list*` methods across all `Admin*Service`s; `Scoped.scopeLabel()` on responses.
- **Exposed data:** the **names** of global catalog entries and sets are shared metadata (not tenant
  data) — safe to show. But delegated `findAllVisibleTo`/`findAllBindableForProject` correctly
  return only `global ∪ own-ws ∪ own-project` rows (verified) — so **no other tenant's scoped rows
  leak by name.** The residual risk is only the *counts attached* to those rows (§4.1) and the
  usage detail (§4.2).
- **DC:** correct.
- **Required behavior:** confirmed acceptable once §4.1/§4.2 are scoped. No change to the row
  listing itself. (Defense-in-depth: add a test asserting a workspace admin's catalog list never
  contains another workspace's scoped rows.)

### 4.7 Name-uniqueness conflict as an existence oracle — **MEDIUM**

- **Where:** `existsAtScopeAndName` / `existsAtScopeAndKey` checks in create/update across all
  services.
- **Exposed data:** these are **scoped** (`scope.workspaceId(), scope.projectId()`), so a create
  collision only reveals a name/key at the **caller's own scope** — not cross-tenant. This is
  **safe.** (Contrast: an *unscoped* uniqueness check would be an oracle for other tenants' names.)
- **Required behavior:** none — documented as verified-safe so a future refactor doesn't
  accidentally broaden these to global.

### 4.8 Delegated binding validation — **VERIFIED CLEAN (Low / regression-guard)**

- **Where:** `ScopedProjectAdminService.requireBindable`, `findAllBindableForProject`,
  `workspaceBindingOptions`/`projectBindingOptions`.
- **Assessment:** binding options and validation are correctly limited to `global ∪ own-ws ∪
  own-project`; a set from another tenant/project 422s and is never listed. **No leak.**
- **Required behavior:** none; keep the existing `DelegatedAdminBindingTest` coverage as the
  regression guard.

### 4.9 Public effective-config endpoint — **VERIFIED CLEAN**

- **Where:** `ProjectConfigController` → `ProjectConfigService`.
- **Assessment:** membership-scoped (workspace member check + `findByIdAndWorkspace`), returns only
  the **one** project's effective statuses/transitions/priorities/types/fields. No cross-tenant
  aggregation, no usage counts. **No leak.**
- **Required behavior:** none.

### 4.10 System console has no Cloud gating — **HIGH (systemic)**

- **Where:** `SecurityConfig` guards `/api/admin/**` with a single `hasRole("ADMIN")` in **both**
  profiles; the SPA `/admin/**` area is identical in DC and Cloud. There is **no** profile/property
  toggle distinguishing operator-only from customer-facing.
- **Problem:** the entire class of global-aggregation surfaces (§4.1–§4.5) is reachable by *any*
  `SystemRole.ADMIN` regardless of mode. In Cloud that is platform staff (acceptable), but there is
  no explicit gate documenting/enforcing "this console is operator-only in Cloud," and no way to
  disable it. Any future accidental promotion of a customer to `ADMIN`, or any decision to expose
  system administration to customers, silently becomes a full-instance leak.
- **Required behavior:** add an explicit profile-gated policy (§5) — the system console stays but is
  **operator-only** in Cloud, gated by a documented property, and the *customer-facing* consoles
  (delegated) get the scoped counts.

---

## 5. Overarching model — should the global admin console exist in Cloud?

Three options, evaluated against the approved delegated-admin model (which already gives every
tenant self-serve, scope-isolated catalog administration).

### Option A — Keep the global console, make it operator-only + scope the delegated counts (**recommended**)

- **What:** The system console (`/api/admin/**`) remains, explicitly **operator-only** in Cloud (it
  already is, by `hasRole("ADMIN")` = platform staff), gated by a documented property
  (`app.admin.system-console-enabled`, default **true** in DC and cloud — the guard is the ADMIN
  role, the property is an emergency kill-switch / clarity flag). Its global aggregation (usage,
  matrix, detail) is **acceptable** because the operator legitimately owns the instance.
  The **delegated** consoles (customer-reachable) get **scope-filtered** counts/detail/guards
  (§4.1–§4.4) so customers never see cross-tenant data.
- **Trade-offs:** + smallest change, reuses the shipped delegated model, no data migration, single
  codebase honored (behavior differs only by scope + one property). + Matches how customers already
  self-serve (delegated-admin P1–P4). − Retains a powerful operator surface that must stay
  operator-only forever (mitigated: it already requires `ADMIN`, and Cloud never grants that to
  customers; add a test asserting no delegated path reaches `AdminProjectService.list()` or unscoped
  counts).
- **Recommendation:** **Adopt.** It is the least-risk, single-codebase-honoring path and aligns with
  the already-approved delegated model. The global console is a legitimate operator tool; the bug is
  purely that the **customer-facing** delegated consoles reuse **unscoped** aggregation.

### Option B — Remove/disable the global console in Cloud; catalog admin is per-workspace only

- **What:** In Cloud, disable `/api/admin/**` catalog/matrix entirely (profile-gate to 404);
  customers administer taxonomy only through the delegated workspace/project consoles; platform
  staff manage the shared *global* catalog through a separate internal tool.
- **Trade-offs:** + strongest isolation (no global aggregation surface reachable at all in Cloud).
  − Platform staff lose the in-app global catalog editor; the seeded global defaults still exist and
  still need occasional curation → needs an alternative operator tool → more work, potential
  forked-behavior smell. − Larger change; doesn't by itself fix the delegated-console leak (§4.1–4.4
  still needed).
- **Recommendation:** heavier than needed; the delegated-console fix is required either way.

### Option C — Move to a fully per-workspace catalog (no shared global catalog in Cloud)

- **What:** Cloud stops sharing a global catalog; every workspace gets its own copy on creation
  (like pre-M1 per-workspace taxonomy).
- **Trade-offs:** + conceptually clean isolation. − Reverses the core M1 decision, is a large data
  model + migration change, forks DC/Cloud semantics of the catalog (violates single-codebase
  principle), and discards the delegated-admin layering just shipped. − Highest cost, highest risk.
- **Recommendation:** reject.

### Decision

**Option A.** Keep the global console operator-only and profile-document it; fix the leak where it
actually bites customers — the **delegated consoles' unscoped usage/count/guard computation** — by
threading the caller's accessible-project scope into `ProjectCountService` and the integrity guards.
This is fully consistent with `delegated-admin-proposal.md` and requires no data migration.

---

## 6. Data model impact

**None required.** The scope columns (`scope_workspace_id`, `scope_project_id`) already exist on all
catalog/set tables (V11, delegated admin). Projects already carry `workspace_id`. The fix is
service-layer scoping of existing queries, not schema.

Optional (Option A property): no table; a `@ConfigurationProperties` flag
`app.admin.system-console-enabled` wired to an env var (§8).

---

## 7. API surface impact

No new endpoints; **behavioral** changes to existing ones (response bodies stay shape-compatible):

- `GET /api/workspaces/{ws}/admin/{...}/{id}/usage` and
  `GET /api/workspaces/{ws}/projects/{p}/admin/{...}/{id}/usage` — `UsageDetailResponse.projects`
  and `.issues` become **scope-filtered** to the caller's accessible projects (Cloud). Shape
  unchanged.
- The `usage.projects` field on delegated **list** responses (`Admin*Response`) becomes
  scope-filtered.
- Delegated 409/422 integrity messages become scope-filtered numbers, or generic (no number) where a
  shared entry can't be safely per-tenant counted.
- `GET /api/admin/**` (system console) — unchanged behavior in DC; in Cloud unchanged but explicitly
  operator-only and optionally kill-switchable via `app.admin.system-console-enabled`.

`openapi.yaml` + `docs/api-cloud.md`/`docs/api-dc.md` must be updated by `api-docs-sync` to note that
delegated usage/count figures are caller-scoped, and (DC "Operator settings") document the new flag.

---

## 8. DC/Cloud implications & env wiring

- The fix is **not** a fork: the same service methods take a scope filter that is **null (whole
  instance)** for the system console / DC-operator path and **the caller's accessible projects** for
  delegated (customer) callers. DC callers use the system console → null filter → unchanged global
  numbers. Cloud customers use delegated consoles → scoped filter. No branching on profile in the
  aggregation logic itself; the scope comes from *which console/role* called.
- **New property (Option A):** `app.admin.system-console-enabled` (env `SYSTEM_ADMIN_CONSOLE_ENABLED`),
  default `true` in base/DC/cloud. Wiring targets per the dc-cloud checklist: `application.properties`
  → (compose passes through `.env`) → `.env.prod.example` → `README.md` config table →
  `docs/api-dc.md` operator section. When `false`, `/api/admin/**` returns 404 (SPA hides the
  "System administration" menu via `/api/meta`, mirroring existing toggles).
- No new storage/email/auth/billing assumptions; nothing cloud-only without a DC path.

---

## 9. Edge cases & failure modes

- **Unbound projects + system-default rows:** `projectsListUsing*` adds `...IdIsNull` projects for a
  system-default entry — in Cloud that is *every unbound project on the platform*. Scope filter must
  be applied **after** the null-binding expansion (filter the combined list to accessible projects),
  or the leak persists precisely for the most common (default) rows.
- **Inherited global rows in delegated lists:** these are shown read-only (delegated P2). Their
  `usage.projects` must reflect **only the caller's** projects, not the global total — otherwise the
  read-only row is the leak vector.
- **Project admin scope = one project:** its accessible-project set is a singleton; usage counts
  collapse to "used in this project" / issue counts limited to this project's issues.
- **Archived / soft-deleted projects:** decide whether an archived accessible project still counts
  (recommend: yes, it is still the caller's data) but a *foreign* archived project must never leak.
- **Zero-usage after scoping:** a global "Open" used heavily platform-wide but not by the caller's
  projects must show `0`/empty for that caller — not the global number.
- **Race / consistency:** counts are read-only snapshots; no locking concern. Ensure scope filtering
  is inside the same `@Transactional(readOnly=true)`.
- **System admin who is also a workspace member:** when acting through the *system* console, sees
  global (correct); through the *delegated* console, sees scoped — the scope follows the endpoint,
  not the person.

---

## 10. Acceptance criteria

A reviewer/`test-runner` can verify:

1. **Delegated usage detail is scoped (flagship):** as workspace admin of WS-A, `GET
   /api/workspaces/{WS-A}/admin/statuses/{globalStatusId}/usage` returns `projects` containing
   **only** WS-A projects and `issues` counting **only** WS-A issues — never WS-B's project
   key/name/count. Same for priorities, issue-types, fields, and for the project-admin console
   (singleton project scope).
2. **Delegated usage counts are scoped:** the `usage.projects` on every delegated catalog/set list
   response equals the count over the caller's accessible projects only, including for inherited
   global and system-default rows.
3. **`...IdIsNull` expansion respects scope:** a system-default row's usage for a delegated caller
   counts only that caller's unbound projects, not all unbound projects instance-wide.
4. **Integrity messages don't leak:** a delegated-admin 409/422 never surfaces an issue/project
   number that includes another tenant's data (assert with cross-tenant fixtures).
5. **System matrix stays operator-only:** no `/api/workspaces/**` path reaches
   `AdminProjectService.list()`; a non-ADMIN calling `/api/admin/projects` gets 403/404.
6. **System console gate (Option A):** with `SYSTEM_ADMIN_CONSOLE_ENABLED=false`, all
   `/api/admin/**` return 404 and `/api/meta` reflects it; default `true` preserves current behavior
   in DC and cloud.
7. **DC unchanged:** in the `dc` profile / via the system console, all usage/count/detail numbers
   are the global totals (regression: existing admin tests still pass).
8. **Verified-clean surfaces stay clean:** binding options/validation (§4.8), name-uniqueness oracle
   (§4.7), and the public config endpoint (§4.9) keep their guard tests green.

---

## 11. Prioritized remediation list

| # | Priority | Surface | Action |
|---|---|---|---|
| 1 | **P0 (Critical)** | §4.2/§4.3 delegated **usage detail** | Thread the caller's accessible-project scope into `ProjectCountService.projectsListUsing*`; filter `projects` + `issues` in `*UsageDetail`. Closes the flagship customer-reachable leak. |
| 2 | **P0 (Critical)** | §4.1/§4.3 delegated **usage counts** | Scope `projectsUsing*` / `AdminFieldService.projectsUsing` for delegated callers; fix the `...IdIsNull` expansion to filter after expansion. |
| 3 | **P1 (High)** | §4.4 integrity-guard messages | Scope the issue/project numbers in delegated 409/422 messages to accessible projects, or drop the number (generic message) for shared entries. |
| 4 | **P1 (High)** | §4.10/§5 system-console gating | Add `app.admin.system-console-enabled` (Option A), wire env/compose/README/docs; document "operator-only in Cloud." |
| 5 | **P2 (Medium)** | §4.5 projects matrix | Add a regression test asserting no delegated/customer path reaches `AdminProjectService.list()`; keep operator-only. |
| 6 | **P2 (Medium)** | §4.6/§4.7 lists & uniqueness | Add regression tests that delegated lists exclude foreign scoped rows and uniqueness checks stay scoped. |
| 7 | **P3 (Low)** | §4.8/§4.9 verified-clean | Keep existing guard tests; document as intentionally safe. |
| 8 | **P3 (Low)** | Frontend | Delegated admin UI usage chips/impact banners already render whatever the API returns — no change once the API is scoped; add a note in `pages/admin/AdminApiContext` that scoped consoles show caller-scoped figures. |

---

## 12. Open questions (with recommended defaults)

1. **Shared-workflow/set names in delegated usage detail** — a global workflow's *name* is catalog
   metadata; is exposing "used in workflow 'Default workflow'" acceptable to a customer? *Recommended
   default:* **yes** — workflow/set names are shared catalog identifiers, not tenant data; only the
   **project list and issue count** are tenant data and must be scoped. Revisit only if a customer
   can rename a global set (they can't — global is operator-owned).
2. **Should the system console be fully disabled by default in Cloud?** *Recommended default:* **no**
   — keep it enabled (operator needs it), rely on the `ADMIN` role (never granted to customers in
   Cloud) plus the new kill-switch. Disabling by default would strand operators.
3. **Generic vs scoped numbers in guard messages** — where a shared global entry is deleted by an
   operator, keep the global number; for delegated callers editing own-scope rows, scope the number.
   *Recommended default:* scope where cheap, generic-message where a per-tenant count is awkward.
4. **Do we need an audit/telemetry counter** for delegated callers hitting inherited-row usage (to
   detect probing)? *Recommended default:* defer — out of scope for the isolation fix, revisit with
   the observability stack.
