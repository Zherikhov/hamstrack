# HD-123 · S4 — the custom-roles API (build spec)

**Ticket:** HD-127 (slice S4 of epic HD-123) · **Branch:** `feat/hd-123-roles-permissions`
**Author:** systems-analyst · **Date:** 2026-08-18 · **Status:** spec, ready to build

> This is a **delta document**. The model, the catalog, the ceiling, the tenancy rules and the
> slice ordering are `docs/design/roles-permissions-proposal.md`; S1/S2/S3/S5 and HD-136 have
> shipped. Read that first. What follows is only what S4 decides, contracts and tests — plus the
> three places the shipped code and the epic spec no longer agree (§11).

**Highest-risk assumption of this slice, stated up front:**

> **That every write path introduced here routes its request-supplied role id through
> `RoleRepository.findAssignable(id, workspaceId, scope)` — and that every permission written into
> a role matches that role's scope.** S1 hardened *assignment* against a wrong-scope role id and
> left the finder unused because nothing could write one. S4 is the slice that makes both a live
> vector, from two directions: a WORKSPACE role id accepted into `project_members.role_id`, and a
> PROJECT-scoped role that *contains* `workspace.member.manage`. `PermissionSet` is a flat
> `EnumSet` with no memory of where a grant came from, so either mistake produces a privilege
> escalation assembled entirely from legitimate ids, with no query, log or test outside the ones
> named in §10 able to see it.

---

## 1. Scope of S4

**In:** roles CRUD (duplicate-only creation, edit, delete-with-reassign), usage, the
`max-custom-per-workspace` bound, `roleId` on invite / add-project-member / change-workspace-member,
the new `PATCH /workspaces/{ws}/projects/{p}/members/{userId}`, the third stranding door, and cache
invalidation that bites immediately for every holder on the editing node.

**Out:** everything in §12.

**No migration.** `roles` / `role_permissions` (V13), the `role_id` columns (V14/V15) and
`TEAM_LEAD` (V16) are already in the schema, and this slice adds no permission and no column. If a
builder reaches for a Flyway file, something has gone wrong — stop and re-read this line. The only
schema-adjacent work is new **repository methods** (§8.4), each of which is a §12-of-the-epic
review moment by the standing rule in `RoleRepository`'s javadoc.

---

## 2. Decision 1 — the revocation window

**Resolved: shorten the `roleView` TTL to 10 seconds as a per-cache Caffeine spec, add
`sync = true`, evict after commit, and document the residual. No cross-node bus in this slice.**

### What the code actually does

`CacheConfig` builds one `CaffeineCacheManager` with a single global spec —
`expireAfterWrite(60s)`, `maximumSize(10_000)` — shared by the six `cfg*` taxonomy caches and by
`roleView`. `RolePermissionCache.byId` is `@Cacheable(cacheNames = "roleView", key = "#roleId")`
and is reached from `RoleCatalog.view` on **every authenticated request naming a workspace** (once
for the workspace role, again for the project role), plus once per row in
`WorkspaceService.listForUser`, `WorkspaceService.listMembers`, `ProjectService.list` and
`ProjectService.listMembers`. `RolePermissionCache.evict` exists and is currently called by nobody.

### Why 10 s is the defensible number

The cache is keyed by **role id**, so its population is not "requests" but "distinct roles in
play": 8 built-ins plus this workspace's custom roles. Under `expireAfterWrite` with no
`refreshAfterWrite`, the reload cost of a hot key is **one reload per TTL, independent of request
rate** — total steady-state cost is `active distinct role ids ÷ TTL` queries per second, and each
query is `findByIdWithPermissions`, a primary-key lookup with a `LEFT JOIN FETCH` over a table
whose largest row count is ~20. An instance with 500 simultaneously active workspaces averaging two
custom roles each holds ~1,000 keys; at 10 s that is a ceiling of ~100 trivial PK lookups per
second, and only if every one of those workspaces is continuously active. At today's 60 s it is
~17/s. The difference is noise; the difference in the security property is 6×.

Going below ~5 s starts to trade a real amount of database work for a window that request latency
and clock skew already blur, and it would be the first cache in the product whose TTL is load-
bearing for a security property *and* tuned to the edge. 10 s is the round number that is an order
of magnitude better than today and still visibly cheap.

**`sync = true` is not optional if the TTL shortens.** Spring's non-sync `@Cacheable` does
get-miss → invoke → put, so every concurrent request that misses the same key issues its own query.
At 60 s that burst is rare; at 10 s it happens six times as often on the hottest key in the
product (the built-in Member role, held by nearly every user of every workspace). `sync = true`
routes through `CaffeineCache.get(key, loader)`, which is atomic per key, so a key's expiry costs
one query regardless of concurrency.

### What to build

1. In `CacheConfig`, register `roleView` as a **custom cache** rather than changing the global spec:
   `manager.registerCustomCache("roleView", Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(10_000).build())`.
   Leave the six `cfg*` caches on 60 s — they are config freshness, not authorization.
2. `@Cacheable(cacheNames = "roleView", key = "#roleId", sync = true)` on `RolePermissionCache.byId`.
3. **Evict after commit, never inside the transaction.** An eviction that fires mid-transaction is
   worse than none: a concurrent reader immediately reloads the *pre-commit* row and re-caches it,
   and the edit then goes unseen for a full TTL on the node that made it. Publish
   `RolePermissionsChanged(UUID roleId)` from the service and evict from a
   `@TransactionalEventListener(phase = AFTER_COMMIT)` listener — the pattern `SseEventListener`
   already uses for `WorkspaceMemberRemoved`. The listener must not be `@Transactional`.
4. Evict on **PATCH** (permissions or not — a rename changes `RoleView.name`, which the ceiling
   exception and the People tab render) and on **DELETE**. Not on duplicate: a new id was never
   cached.

### The residual, to be written into `RolePermissionCache`'s javadoc verbatim

> **A permission revocation is honoured for up to 10 seconds on other instances.** The cache is
> per-process, so `evict` only corrects the node that served the edit; every other node serves the
> pre-edit `PermissionSet` until its own entry expires. Worst case is therefore `TTL` plus the
> duration of a request that had already resolved its context (snapshot semantics, §9.3). Widenings
> have the same delay and nobody minds. **This is a security property, not a config-freshness one:
> do not raise the TTL, and do not fold `roleView` back into the shared 60 s spec.**
>
> Membership is *not* cached, so a role **re**assignment — moving a person to a different role, and
> the whole of `DELETE /roles/{id}?reassignToRoleId=` — bites on that person's very next request on
> every node. Only a change to a role's *contents* has a window.

### Where I disagree with the brief's reasoning (not its conclusion)

The stated reason for rejecting cross-node invalidation — "a bus single-instance DC does not have"
— is not quite right, and the spec should not enshrine a reason a future reader can disprove.
PostgreSQL `LISTEN`/`NOTIFY` **is** available in both deployment modes, is not a Cloud-only
dependency, and on a single-instance DC degrades to a no-op rather than a second code path. So it
would not violate §13. The honest reason to defer it is cost: it needs a dedicated long-lived
connection per instance outside the Hikari pool, reconnect/backfill handling, and a story for what
happens when a notification is missed — all to close a 10-second window on a mutation a workspace
performs a handful of times a year. **Record `LISTEN`/`NOTIFY` as the documented upgrade path** (in
`RolePermissionCache`'s javadoc, one sentence) so the next person does not re-derive it, and ship
the TTL.

**Not a property.** `app.roles.*` gains no cache knob. A configurable TTL on an authorization cache
is a per-deployment way to lengthen a security window, and there is no operational need — the
number is small, uniform and free.

---

## 3. Decision 2 — wrong-scope role rows

**Resolved: do both, with a split the lean did not state — the degrade applies to *collection and
third-party* reads only, never to the caller's own authorization resolution.**

### 3a. The write-side guard (mandatory, non-negotiable)

Every role id that arrives in a **request body** resolves through
`RoleRepository.findAssignable(id, workspaceId, scope)` — 422 `UnknownRoleException` otherwise. The
five doors: workspace invite, change workspace member role, add project member, **change project
member role** (new), delete-with-reassign target. `RoleCatalog` gains
`requireAssignable(RoleScope scope, UUID workspaceId, UUID roleId)`; the existing key-based
overload stays for the legacy `role` string field and keeps resolving **built-ins only** (its
javadoc already says so — and that is a second reason S6 must move the SPA to `roleId`: a legacy
key can never address a custom role).

Two further write-side invariants that make wrong-scope rows unreachable rather than merely
refused:

- **`scope` is immutable after creation.** It is not on the update DTO and is inherited by
  duplication. Were it editable, one PATCH would turn every existing assignment of that role into a
  wrong-scope row — the exact corruption the finder exists to prevent, delivered wholesale.
- **A permission's scope must equal its role's scope** (§4.2 below). Otherwise a PROJECT role can
  carry `workspace.member.manage`, and the flat `PermissionSet` puts it straight into
  `ProjectContext.permissions()`. This is the half of the hole S1 did not have to guard, because
  nothing could compose a permission set.

Path-addressed role ids (`GET/PATCH/DELETE /roles/{id}`, duplicate source) are a different question
and get a different finder — see §5.1.

### 3b. The read-side degrade

Today `WorkspaceAccessService.requireRole` throws `WorkspaceNotFoundException` (404) and logs ERROR
whenever the scope/ownership assertion fails. It is called from six places, and the blast radius is
wildly different between them:

| Call site | Whose row | Today's failure | S4 |
|---|---|---|---|
| `requireMember` | the **caller's own** workspace membership | 404 — caller is out of the workspace | **unchanged** |
| `projectContext` | the **caller's own** explicit project row | 404 — caller is out of the project | **unchanged** |
| `projectPermissionsOf` | a **third party** (assignability) | already degrades to `PermissionSet.empty()` | unchanged |
| `WorkspaceService.listForUser` | caller's own, **one row of N** | 404 — the caller's **entire workspace list** | **degrade** |
| `ProjectService.list` | caller's own, one row of N | 404 — the caller's entire project list | **degrade** |
| `WorkspaceService.listMembers` / `ProjectService.listMembers` | **third parties**, one row of N | 404 — the whole People tab | **degrade** |

Add `WorkspaceAccessService.resolveRoleOrDegrade(UUID roleId, RoleScope expected, UUID workspaceId,
String source) → Optional<RoleView>`, sharing `isUsableAs` and the ERROR log with `requireRole` so
there is one predicate. Empty means:

- `myPermissions` → `[]` (never absent — §12 of the epic),
- `myRole` / the member row's role key → **JSON `null`**, *never the foreign role's key or name*,
- the entry stays in the list.

**Why the caller's own single-resource resolution must keep failing closed.** Degrading
`requireMember` would not be "empty permissions": in an `OPEN` workspace the project-role fallback
is independent of the workspace role, so the caller would still inherit **Contributor in every
project of the workspace**. Turning a 404 into Contributor-everywhere is a widening, and it is
invisible. Keep the 404 there. The consequence — a corrupt row makes the workspace appear in
`GET /workspaces` and 404 on `GET /workspaces/{id}` — is intended and must be written into the
javadoc: *a list is a directory, a detail read is an authorization.* It is loud, bounded to one
entry, and strictly better than a user who cannot see any workspace at all.

### 3c. Confirming the degrade cannot mask a tenancy bug

Three properties, each of which the builder must be able to point at:

1. **The assertion is authorization, not tenancy.** Whether a person is a member of a workspace is
   decided by the `workspace_members` row and its `workspace_id` join, which happens before and
   independently of `requireRole`. The role id answers only *what may they do*. Degrading it can
   only ever **narrow** — `PermissionSet.empty()` is the floor — so no membership, project or issue
   becomes reachable that was not already reachable through v1's open reads.
2. **The one genuine leak in the vicinity is the role's name/key**, which is why
   `ProjectService.listMembers` and `WorkspaceService.listMembers` call `requireRole` at all
   (their javadocs say so). The degrade must therefore emit `null`, not the role it just refused.
   A test asserts the foreign role's `key` and `name` appear nowhere in the response body.
3. **The degrade is keyed on the assertion failing, never on the role being absent.**
   `RolePermissionCache.byId` throws `IllegalStateException` for an id that resolves to no row (a
   dangling FK — impossible while `ON DELETE` is NO ACTION, hence a 500 by design). Catching
   `WorkspaceNotFoundException` only, and letting `IllegalStateException` propagate, is what keeps a
   broken migration loud. Do **not** write `catch (Exception)`.

**Diagnosability.** A permanently corrupt row logs at ERROR on every list request, which is a Loki
bill, not a signal. Keep the ERROR line (it names the table, the id and the expected scope) and add
a Micrometer counter `hamstrack_role_scope_violation_total{source}` via the existing
`ProductMetrics` bean, so the condition is alertable without being read line by line.

---

## 4. Decision 3 — the stuck state, and the escape

**Resolved: adopt the escape, bounded by three constraints — built-in Project admin only, target ≠
actor, logged.**

### The state

Of the 20 project permissions, four are reachable from workspace scope through
`project.curate.all`, which the built-in Owner and Admin hold. The other 16 are reachable only from
inside the project. Granting any of them requires an actor holding **both**
`project.member.manage` and the permission itself (§11.2). So a project whose only members holding
`project.member.manage` carry a custom role narrower than Project admin can never acquire the
permissions none of them holds — `project.archive` and `project.taxonomy.manage` being the ones
with no workaround at all. No built-in role holds `project.administer.all`, and the workspace-Owner
root-of-trust decision does not help: an Owner is exempt from the ceiling but still holds no
`project.member.manage` in a project they are not a member of, so they cannot assign any project
role there.

### The escape

> **A caller holding `project.member.manage` in a project may always assign the built-in Project
> admin role (`BuiltInRoles.PROJECT_MANAGER`) to *another* member of that project, regardless of
> the grant ceiling.**

Applies to both doors — `POST .../members` and `PATCH .../members/{userId}` — because a permission
(or an exemption from one) is only real if every endpoint that produces the effect implements it.
Keyed on the built-in **role id**, never the key string, for the reason
`WorkspaceMemberService.requireWithinGrantCeiling` already gives: after this slice a workspace may
own a custom role keyed `MANAGER`, and that role is not this guardrail. Emits
`log.info("project.admin_granted project={} actor={} target={} reason=ceiling_escape")`, ids only,
matching the shape of `project.admin_adopted`.

### Does it re-open an escalation the ceiling exists to stop?

**With `target ≠ actor`, no single actor can escalate themselves — and that constraint is
load-bearing, not decorative.** Without it, `project.member.manage` would imply all 20 project
permissions for its holder, which would (a) make the project-scope ceiling decorative, and (b)
break HD-136's H1 argument directly: `ProjectAdminGuard.adoptAll` hands the built-in **Team lead**
(Contributor + `project.member.manage`) to any workspace member-manager who offboards a project's
sole administrator, and its entire safety case is "nothing this role grants can destroy anything —
no `issue.delete`, no unrestricted `attachment.delete`, no archive/settings/taxonomy authority."
A self-grant would convert every adoption into a two-call route to all 20. `ProjectService.addMember`
already refuses to be that route ("remove your own row, add it back with a bigger role"); the
escape must not become it.

**The residual, to be documented rather than hidden:** two cooperating members, one of whom holds
`project.member.manage`, can bootstrap a Project admin (A grants B; B, now holding everything,
grants A by the ordinary ceiling). That *is* the recovery procedure, performed by two willing
people — which is exactly what a "fixed, auditable escape" means. It requires an accomplice who
already has a workspace account; nobody gains visibility they lacked (v1 has no `project.view`); and
both steps are logged. Compare it with what it replaces: a project that no endpoint can repair and
whose fix is a hand-written `UPDATE`.

**Secondary effects to be checked by the builder:**

- The removal ceiling is unchanged, so after A promotes B, A can no longer remove or demote B
  (`GrantCeilingAction.ACTING_ON` compares against B's *current* role). Intended; note it in the
  javadoc so it is not later "fixed".
- The escape does not touch `WorkspaceMemberService` — workspace scope already has the Owner root
  of trust.
- The escape does not apply to `DELETE /roles/{id}?reassignToRoleId=` (§5.5 blocks a self-held
  role outright).

---

## 5. Decision 4 — compose-time ceiling feedback

**Resolved: the server derives it, on every role response and through a dry-run preview endpoint.
It is never re-derived in TypeScript.**

The ceiling is `PermissionSet.firstNotCovered` — set containment with per-grant *width* (an
unrestricted grant is not covered by an own-only one). A role built from scratch holding only
`project.member.manage` can assign nobody, because it is not a superset of Contributor, of
Commenter, or even of Viewer-plus-anything. §7.2's recipe works only because duplication starts
from a superset. Discovering that as a 403 six weeks later is the precise Jira complaint this epic
was built against, so the feedback is a **requirement of this slice**.

Re-implementing `firstNotCovered` client-side is not an option: the own-only/unrestricted asymmetry
is subtle, and a second implementation of a server predicate in the SPA is the HD-98/HD-116 bug
class by construction (§5.3).

### The derived block

Every role response carries:

```jsonc
"assignment": {
  "managesMembers": true,                       // holds workspace.member.manage / project.member.manage
                                                //  (unrestricted) for its own scope
  "canAssign":    [ { "roleId": "…", "name": "Viewer" } ],
  "cannotAssign": [ { "roleId": "…", "name": "Contributor", "missing": "issue.rank" } ],
  "warnings":     [ "MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING" ]
}
```

- Computed over the roles **of the same scope available in this workspace** (built-ins + custom),
  excluding the role itself, via `role.permissions().firstNotCovered(other.permissions())` — the
  same call the runtime ceiling makes, so the two cannot disagree.
- `missing` is the offending permission **key**, straight from `firstNotCovered`, so the editor can
  say *"cannot assign Contributor — this role is missing `issue.rank`"*. The runtime 403 keeps
  naming it too (`WorkspaceGrantCeilingException` / `ProjectGrantCeilingException` already do); the
  editor's message and the 403 quote the same string.
- `warnings` contains `MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING` iff `managesMembers && canAssign` is
  empty. Machine-readable, so S6 renders copy rather than re-deriving the condition.
- **It is a lower bound and must be documented as one.** A real actor's effective set at project
  scope is `projectRole ∪ project.curate.all / project.administer.all` from their workspace role, so
  a holder may in practice assign more than `canAssign` lists. The block is computed from the role
  alone because that is the conservative direction and the one the warning is about. One sentence in
  the DTO javadoc; S6 copy says "on its own, this role can assign: …".

### Preview

`POST /api/workspaces/{wsId}/roles/preview` — `workspace.role.manage`, persists nothing, body is
the same shape as the update request (`scope` **required** here, since there is no stored role to
take it from), response is `{ "assignment": { … } }` plus the same 422s a real write would produce
for unknown/wrong-scope/bad-own permissions. This is what lets the editor show the consequence
while the checkboxes are being ticked rather than after Save.

**No refusal.** A role that manages members and can assign nobody is legal — a role granting
nothing is legal (§11.4), an intermediate save is legitimate, and refusing would be paternalism the
product does not otherwise practise. Warn, never block.

---

## 6. Stranding doors this slice opens

HD-136 enumerates the doors by which a project loses its last administrator and guards two of them,
both deletions of a `project_members` row. S4 adds three that are not deletions. All three belong to
`ProjectAdminGuard`, whose javadoc enumeration must be extended — the enumeration is deliberate and
an unlisted door is the bug.

### Door 3 — demotion (per-row, locked, refusing)

`PATCH .../projects/{p}/members/{userId}` strands a project with no row removed. Sequence, and the
order is the rule:

1. `lockTimeout.applyToCurrentTransaction()` — **first statement**, before anything can queue on a
   lock.
2. `workspaceAccess.resolveProject(actor, ws, p)`; `ctx.permissions().require(PROJECT_MEMBER_MANAGE)`
   — before the lock, so an unauthorized caller never takes one.
3. `roleCatalog.requireAssignable(RoleScope.PROJECT, ws.getId(), req.roleId())` → 422 for unknown,
   foreign or WORKSPACE-scoped. Before the lock and before the target is read, so a bad role id
   never depends on who it was aimed at (mirrors `WorkspaceMemberService.updateRole`).
4. `projectAdminGuard.lockAdmins(project)` — **unconditionally, before the target row is read.**
   Deciding whether to lock from an unlocked read is the race the lock exists to close; this is the
   third time the project has had to learn it (HD-132, HD-136).
5. Read the target `project_members` row → 404 (`ProjectNotFoundException`, matching
   `removeMember`'s existing shape) if absent.
6. Ceiling on the target's **current** role (`ACTING_ON`) and on the **requested** role
   (`GRANTING`), unless the §4 escape applies to the latter.
7. `requireNotLastAdmin(admins, targetUserId)` — **skipped iff the requested role grants
   `project.member.manage` unrestricted** (a promotion cannot strand). Mirrors
   `updateRole`'s `if (!requested.isBuiltIn(WORKSPACE_OWNER)) requireNotLastOwner(...)`.
8. Same role as current → 200 no-op; step 7 is naturally skipped because the grant is unchanged.
9. Mutate and save last (the `@Version` ordering rule). `log.info("project.member.role_changed …")`,
   ids only.

**Do not "fix" the guard's two documented conservatisms on this path.** It counts only explicit
`project_members` rows, and it does not consult `project.administer.all`. A demotion's target is by
definition a member with an explicit row — the one person whose default-role inheritance does not
apply — so counting inheritance here would be counting a fallback that cannot reach them.
`ProjectAdminGuard.cannotBeStranded` already asks the inheritance question correctly, for the case
where somebody *else* stands on the fallback; it stays exactly where it is.

Self-demotion is allowed, subject to step 7 (mirrors workspace self-demotion).

### Doors 4 and 5 — bulk: role edit and delete-with-reassign

A `PATCH /roles/{id}` that drops `project.member.manage`, or a
`DELETE /roles/{id}?reassignToRoleId=` whose target lacks it, demotes **every holder at once**, in
every project, and neither existing guard sees it.

Guard (new, in `ProjectAdminGuard`):

```java
List<ProjectRef> projectsAdministeredOnlyBy(Workspace workspace, UUID roleId)
```

backed by one aggregate over the workspace's `project_members`, restricted to the administering
role ids (`RoleRepository.findIdsGranting`) and the ACTIVE-user filter the class already uses:

```sql
GROUP BY pm.project_id HAVING bool_and(pm.role_id = :roleId)
```

Non-empty → **409 `LAST_PROJECT_ADMIN_BULK`**, reusing `StrandedProjectsException`'s payload shape
so the SPA renders one list component. Runs only when the change actually removes
`project.member.manage` from a PROJECT-scoped role, so the ordinary edit pays nothing.

**Honest limitation, to be written into the method's javadoc:** an aggregate cannot take
`FOR UPDATE`, so this guard is advisory against a concurrent membership change, unlike the locked
per-row guard. It is a bulk-safety net for an action a human performs a handful of times a year;
the locked invariant remains the per-row one. Do not paper over that with a lock that is not there.

There is deliberately **no adoption path** for the bulk doors: the remedy ("choose a replacement
role that also manages members, or fix those projects first") is satisfiable by the person being
refused, which is the H1 test.

---

## 7. Endpoint contracts

All paths are workspace-scoped. Every one resolves through `WorkspaceAccessService.requireMember` /
`resolveProject` **before** any permission is evaluated, so a 403 is reachable only by a proven
member.

### 7.1 Roles

| # | Method & path | Gate | Success | Notes |
|---|---|---|---|---|
| R1 | `GET /api/workspaces/{ws}/roles?scope=&includeUsage=` | any **member** | 200 `RoleResponse[]` | `scope` optional (both when absent). `includeUsage=true` requires `workspace.role.manage` → 403 otherwise |
| R2 | `GET /api/workspaces/{ws}/roles/{roleId}` | any **member** | 200 `RoleResponse` | 404 for a role that is neither built-in nor this workspace's |
| R3 | `POST /api/workspaces/{ws}/roles/{sourceRoleId}/duplicate` | `workspace.role.manage` | 201 `RoleResponse` | **the only way to create a role** |
| R4 | `PATCH /api/workspaces/{ws}/roles/{roleId}` | `workspace.role.manage` | 200 `RoleResponse` | 409 for a built-in; 409 on version mismatch |
| R5 | `DELETE /api/workspaces/{ws}/roles/{roleId}?reassignToRoleId=` | `workspace.role.manage` | 204 | 409 for a built-in / in use without reassign / self-held |
| R6 | `GET /api/workspaces/{ws}/roles/{roleId}/usage` | `workspace.role.manage` | 200 `RoleUsageResponse` | |
| R7 | `POST /api/workspaces/{ws}/roles/preview` | `workspace.role.manage` | 200 `{ "assignment": … }` | persists nothing |

**`RoleResponse`:**

```jsonc
{
  "id": "0198…", "scope": "PROJECT", "key": "QA_LEAD", "name": "QA lead",
  "description": "Transitions and triages, does not file work",
  "builtIn": false, "position": 0, "version": 3,
  "permissions": [ { "key": "issue.transition", "ownOnly": false },
                   { "key": "comment.edit",     "ownOnly": true } ],
  "assignment": { … },          // §5
  "usage": { … }                // only on R6, or R1 with includeUsage=true and the permission
}
```

`permissions` uses the **object form**, not `myPermissions`' `"comment.edit:own"` wire form: the
editor needs the toggle state as a field, and the suffix encoding exists for the flat client gate.
Both are produced from the same grants; do not converge them.

**`RoleUsageResponse`:**

```jsonc
{ "roleId": "…", "members": 4, "invites": 1,        // WORKSPACE scope
  "projectMembers": 7, "projects": 2,                // PROJECT scope
  "defaultForProjects": 1, "defaultForWorkspace": false,
  "inUse": true }
```

`inUse` is the disjunction the delete guard uses, so the client cannot compute a different answer.
**Every count is filtered to this workspace** — the built-ins are shared rows, and an unscoped
`COUNT(*) WHERE role_id = …` on `workspace_members` publishes every other tenant's headcount. This
is the single most likely tenancy defect in the slice; it gets its own acceptance test (§10 T4).

**R3 — duplicate.** Body `{ "name"?: "…", "description"?: "…" }`.

- Source resolved by the **path finder** (§8.4), so a foreign/unknown source is **404**, not 422:
  it is being addressed as a resource, not supplied as a value. (Values in bodies stay 422 —
  `UnknownRoleException`'s javadoc explains why the two must not be one code.)
- `scope`, and the permission set, are **copied from the source**. `builtIn = false`,
  `workspaceId = {ws}`, `position` = max+1 within the scope, `version = 0`.
- `name` defaults to `"<source name> copy"`.
- **`key` is server-generated** from the name: uppercase, `[A-Z0-9_]`, truncated to 40, with a
  `_2`, `_3`… suffix on collision within `(workspace, scope)`. It is never accepted from the
  client. This is also how the built-in-key guard is implemented: a generated key that equals a
  built-in key of that scope (`OWNER`, `ADMIN`, `MEMBER`, `MANAGER`, `COMMENTER`, `VIEWER`,
  `TEAM_LEAD` — read from `BuiltInRoles`, never a literal list) takes the suffix. `UNIQUE NULLS NOT
  DISTINCT` permits the collision at the DB level, so this guard is the only thing standing in the
  way, and its failure mode is a role that satisfies nothing dangerous but confuses every operator
  reading `psql`.
- **`name` must be unique within `(workspace, scope)` and must not equal a built-in's display name
  in that scope, case-insensitively → 409.** Ambiguity at the level users actually read is worse
  than ambiguity in the key. Application-enforced; a race yields two same-named roles, which is
  cosmetic.
- **`max-custom-per-workspace` counted first** → 409 `ROLE_LIMIT_REACHED`. Count is
  `built_in = false` rows in this workspace, **both scopes combined**. The check is a plain count,
  so two concurrent creates can both pass; the failure mode is 51 roles and it is not worth a lock.

*Why duplication is the only door:* the anti-Jira posture is structural, not advisory. There is no
endpoint that yields an empty checklist, so nobody can accidentally compose a role that manages
members and can assign nobody. Starting "from scratch" remains reachable — duplicate **Viewer**,
which grants ∅ — and that is a named, deliberate act rather than the default.

**R4 — edit.** Body `{ "name"?, "description"?, "permissions"?: [{key, ownOnly}], "version"?: 3 }`.

- `scope`, `key`, `builtIn`, `workspaceId` are **not on the DTO**. Scope immutability is by
  construction (§3a).
- `version` follows the shipped convention (`IssueService`): when present and ≠ the entity's
  version → 409 *"Role was modified by someone else — refresh and retry"*. Absent → last writer
  wins, and `@Version` still catches a true concurrent flush via
  `GlobalExceptionHandler.handleOptimisticLock` → 409.
- `permissions` is a **full replacement**: `role.getPermissions().clear()` then `addAll(...)` in one
  transaction. **Not** the `deleteAllBy…` + `flush()` recipe — `Role.permissions` is an
  `@ElementCollection`, which has neither a child entity nor a repository, and Hibernate orders
  collection removals before creations. The entity javadoc already spells this out; do not
  "improve" it. Also note `RolePermission` equality is on `permission` alone, so toggling `ownOnly`
  with `add()` is a silent no-op — replacement is the only correct shape.
- Validation, all **422**: unknown key (`Permission.byKey` empty — the write-path half of the rule
  whose read-path half is `PermissionConverter`'s log-and-drop); a permission whose `scope()` ≠ the
  role's scope; `ownOnly = true` on a permission with `supportsOwn() == false` (silently honouring
  it would *narrow* the grant behind the admin's back); a duplicated key in the list.
  `ownRequired()` permissions (`comment.edit`) are forced own-only rather than refused —
  `PermissionSet.of` already does this and the editor renders a locked toggle.
- Built-in → 409 *"Built-in roles cannot be edited — duplicate it to customise"*.
- Door 4 guard (§6) when the edit removes `project.member.manage` from a PROJECT role.
- After commit: evict.

**R5 — delete.**

- Built-in → 409.
- Not addressable in this workspace → 404.
- `reassignToRoleId` resolved by `findAssignable(target, ws, role.scope())` → 422 (wrong scope,
  foreign, unknown); equal to the role being deleted → 422.
- In use and no `reassignToRoleId` → **409 `ROLE_IN_USE`** with the `RoleUsageResponse` as the
  problem-detail extension, so the client can render the remap dialog from the refusal.
- **Self-held → 409 `SELF_HELD_ROLE`.** If the actor holds the role being deleted (their
  `workspace_members` row, or any `project_members` row in this workspace), refuse. A bulk reassign
  is otherwise a self-escalation route: delete the custom "QA" role you hold in project P,
  reassigning to built-in Project admin, and you are Project admin of P — a widening that no
  ceiling sees, because a ceiling is evaluated per assignment and this is a bulk `UPDATE`.
  Deliberately blunt rather than a per-row ceiling: the remedy ("change your own role first, or ask
  another administrator") is one call, satisfiable by the person refused, and the alternative is a
  ceiling evaluated against N different project contexts.
- Door 5 guard (§6).
- Reassignment is bulk `@Modifying` UPDATEs, each **filtered to this workspace**, ordered before the
  role delete: `workspace_members` + `workspace_invites` (WORKSPACE scope); `project_members`,
  `projects.default_project_role_id`, `workspaces.default_project_role_id` (PROJECT scope — the two
  default columns have no write path until S7 but are in the FK graph and count as usage today per
  §11.4, so covering them now costs two statements and avoids a `NO ACTION` 500 later).
  **Plain `@Modifying`, never `clearAutomatically`** — nothing in this transaction re-reads the
  mutated rows, and `em.clear()` mid-transaction is the documented way to lose pending inserts.
- Then delete the role, then (after commit) evict its id.
- 204.

### 7.2 Membership assignment

| # | Method & path | Gate | Change |
|---|---|---|---|
| M1 | `POST /api/workspaces/{ws}/invites` | `workspace.member.manage` + ceiling + Owner-never-invitable | `InviteMemberRequest` gains `roleId` |
| M2 | `PATCH /api/workspaces/{ws}/members/{userId}` | `workspace.member.manage` + ceiling (both ends) + last-Owner | `UpdateWorkspaceMemberRequest` gains `roleId` |
| M3 | `POST /api/workspaces/{ws}/projects/{p}/members` | `project.member.manage` + ceiling + §4 escape | `AddProjectMemberRequest` gains `roleId` |
| M4 | `PATCH /api/workspaces/{ws}/projects/{p}/members/{userId}` | `project.member.manage` + ceiling + §4 escape + door 3 | **new** |

**Both fields, transitionally.** Each of M1–M3 keeps its legacy `role` **key** string and gains an
optional `roleId`. Exactly one must be present → **422** otherwise (including when both are). The
legacy field resolves built-ins only, and keeps `ProjectService.storedRole`'s `VIEWER → MEMBER`
translation; the **`roleId` path does not translate**, so a genuinely read-only project member
becomes expressible for the first time — which is precisely what `addMember`'s javadoc promised S4
would do. `role` is marked deprecated in `openapi.yaml`; S6 moves the SPA to `roleId`; removal is a
follow-up ticket, not S8.

Why not just replace the field: S5 has shipped and the invite screen is live. A slice must be
independently deployable, and breaking the running SPA's invite flow between S4 and S6 is a
production regression for the sake of one DTO field.

**M4 request/response:** `{ "roleId": "uuid" }` → 200 `ProjectMemberResponse` (same shape M3
returns). Status codes: 200 · 403 (missing `project.member.manage`, or a ceiling refusal naming the
permission) · 404 (unknown workspace / non-member / project not in workspace / target holds no
project membership here) · 409 (`LastProjectAdminException`; lock timeout with `Retry-After`) ·
422 (unknown, foreign or WORKSPACE-scoped `roleId`).

---

## 8. Tenancy posture

### 8.1 Per endpoint

| Endpoint | Non-member / unknown ws | Member, no permission | Foreign role id |
|---|---|---|---|
| R1 `GET /roles` | 404 | **200** (role names are needed by every member for display) | n/a |
| R1 with `includeUsage=true` | 404 | **403** | n/a |
| R2 `GET /roles/{id}` | 404 | 200 | **404** (path-addressed) |
| R3 duplicate | 404 | 403 | **404** (source is path-addressed) |
| R4 patch | 404 | 403 | **404** |
| R5 delete | 404 | 403 | **404** for the target role; **422** for `reassignToRoleId` (a value) |
| R6 usage | 404 | 403 | 404 |
| R7 preview | 404 | 403 | n/a |
| M1–M4 | 404 | 403 | **422** `UnknownRoleException` |

**The 404/422 split is a rule, not a taste:** a role id in the **path** is an address (404 keeps the
namespace opaque and matches labels/components/versions), a role id in a **body** is a value (422,
one indistinguishable answer for foreign, wrong-scope and nonsense, which is what stops the
two-value oracle `UnknownRoleException`'s javadoc describes). A builder must not unify them.

### 8.2 Standing rules for this slice

- No new query reads `roles` by bare id. `RoleRepository` still does not extend `JpaRepository`;
  every method added under §8.4 either carries a workspace filter or is fed an id already proven
  reachable.
- Every usage count and every bulk reassign carries `workspace_id` (§7.1, R6).
- `GET /api/permissions` is unchanged: global static product metadata, no tenancy.

### 8.3 What the built-in sharing means

Built-in rows have `workspace_id IS NULL` and are visible to every workspace. Therefore: they appear
in R1/R2 for every workspace (correct — they are product metadata); they are **not** editable or
deletable (409, keyed on `built_in`); their **usage counts must still be workspace-scoped**; and
they do not count against `max-custom-per-workspace`.

### 8.4 Repository additions (each one a §12 review moment)

`RoleRepository`: `findInWorkspace(id, workspaceId)` — **path-addressed reads only**, no scope
parameter, `(r.workspaceId IS NULL OR r.workspaceId = :workspaceId)`; add a javadoc paragraph
stating it must never be reached from a request **body**, which is `findAssignable`'s job.
`findAvailableWithPermissions(workspaceId, scope)` — `LEFT JOIN FETCH r.permissions` variant of
`findAvailable`, so R1 is one statement rather than N+1. `countByWorkspaceIdAndBuiltInFalse`,
`existsByWorkspaceIdAndScopeAndNameIgnoreCase`, and re-declared `save` / `delete` / `flush`
(they are *not* inherited — that is the point of the interface).

`WorkspaceMemberRepository` / `ProjectMemberRepository` / `WorkspaceInviteRepository` /
`ProjectRepository` / `WorkspaceRepository`: one workspace-scoped count and one workspace-scoped
`@Modifying` reassign each, as listed in §7.1 R5, plus the grouped count backing R1's
`includeUsage`.

---

## 9. DC / Cloud

Nothing here is profile-gated, and nothing may become so (§13: custom roles are a product feature,
not a plan feature — Plane gates them to Enterprise; we have no tiers and must not grow one
implicitly).

| Property | Env | Default (`dc` **and** `cloud`) | Meaning |
|---|---|---|---|
| `app.roles.max-custom-per-workspace` | `ROLES_MAX_CUSTOM_PER_WORKSPACE` | **50** | Sprawl guard. Exceeded → 409 `ROLE_LIMIT_REACHED`. **Never a licence check** |

50 stands (it is §13's number and nothing found in the code argues against it): the built-ins cover
the four motivating cases with one duplication each, a workspace needing more than a dozen custom
roles has an organisational problem the product should not silently absorb, and the value is high
enough that no honest user meets it. Counted across **both scopes**, `built_in = false`, per
workspace.

Wiring, per the `dc-cloud-guard` checklist: `application.properties` → `docker-compose.prod.yml` →
`.env.prod.example` → `README.md`. A `@ConfigurationProperties` class under `common.config`
(`RolesProperties`), not `@Value`.

`app.workspace.default-project-access-mode` is **S7's**, not this slice's.

No storage, email, billing or identity dependency is introduced, so there is no cloud-only
assumption needing a self-hosted path.

---

## 10. Acceptance criteria

### The four motivating cases, end to end

**A1 — "only leads manage sprints."** Duplicate **Contributor** → add `sprint.manage` → name "Team
lead (sprints)". Assign to U1 in project P via M4. U1 `POST /sprints` → **201**. U2 (plain
Contributor) → **403**, detail naming `sprint.manage`. U2 keeps `sprint.assign`, so
`POST /sprints/{id}/issues` still **200** — the split is real. Repeat the `sprint.manage` check on a
project with `board` **off**: still 403 for U2 and 201 for U1, because a capability never changes a
status code (delivery Rule A).

**A2 — "QA may transition but not create."** Duplicate **Commenter** → add `issue.transition` →
assign to U3. `PATCH /issues/{n}` with only a changed `statusId` → **200**. `POST /issues` →
**403** (`issue.create`). `PATCH /issues/{n}` with a changed `title` → **403** (`issue.edit`). A
`PATCH` carrying an **unchanged** `statusId` alongside a permitted field must not 403 (the shipped
per-field rule).

**A3 — "contractors may comment but never be assigned."** Duplicate **Commenter** → **remove**
`issue.assignable` → assign to U4 in P. U4 `POST /comments` → **201**. Assigning an issue to U4 →
**422** with the distinct "cannot be assigned in this project" message, not 403 and not "Unknown
assignee". An issue **already** assigned to U4 survives an unrelated `PATCH` untouched. The
workspace stays `OPEN` throughout: U4's explicit narrow row must beat the Contributor default
(§11.3), which is the assertion that proves explicit-wins.

**A4 — "only these people manage releases."** Duplicate **Contributor** → add `version.manage` →
"Release manager" → assign to U5. U5 `POST /versions` → **201**; U6 → **403**. Runs on a project
with `releases` **off**, and still returns 201/403 rather than 404 — capabilities gate UI, never the
API.

### Roles CRUD

1. `POST /roles/{builtIn}/duplicate` → 201, `builtIn=false`, `workspaceId={ws}`, permissions equal
   to the source's, `version=0`. The source is unchanged.
2. `PATCH` a built-in → 409. `DELETE` a built-in → 409. There is **no** bare `POST /roles`.
3. Duplicating a role named "Admin" produces key `ADMIN_2`, never `ADMIN`; and a *name* colliding
   with a built-in display name in that scope → 409.
4. `PATCH` with an unknown permission key → 422; with a permission of the wrong scope → 422; with
   `ownOnly=true` on `issue.transition` → 422; `comment.edit` is stored own-only whatever is sent.
5. Two concurrent `PATCH`es (stale `version`) → 409; the loser's permissions are not applied. A
   replacement that re-sends an existing permission does not violate the `(role_id, permission)` PK.
6. `DELETE` a role in use with no `reassignToRoleId` → 409 with the usage payload; with a valid one
   → 204, and **every** holder (`workspace_members` / `project_members` / `workspace_invites` /
   both default columns) now points at the target. A `reassignToRoleId` of the other scope, of
   another workspace, or equal to the role being deleted → 422.
7. `DELETE` a role the actor themselves holds → 409 `SELF_HELD_ROLE`.
8. Creating role number 51 with `max-custom-per-workspace=50` → 409; built-ins do not count; the
   count spans both scopes.
9. `scope` cannot be changed: it is absent from the update DTO, and a body carrying it does not
   alter the stored row.

### Cache & revocation

10. Edit a role to drop `issue.delete`; the holder's **very next** request on the same instance is
    403. (Same-process, so `evict` is what is under test.)
11. The eviction is after commit: a reader concurrent with the edit does not leave the pre-commit
    value in the cache. Assert by evicting through the event listener, not from inside the
    transaction.
12. `roleView` is registered with its own 10 s spec and the `cfg*` caches still have 60 s.
13. `PermissionResolutionQueryCountTest` still asserts 2 / 4 / 0. R1 with 50 roles issues **one**
    role query (the `JOIN FETCH` variant), not 51.

### The escape and the stranding doors

14. M4 demoting the **sole** holder of `project.member.manage` → 409; with a second holder present
    → 200. The guard fires on the demotion path with no row removed.
15. M4 promoting the sole administrator to a *wider* role → 200 (the last-admin check is skipped
    for a role that grants the permission).
16. A holder of a narrow custom role with `project.member.manage` may assign built-in **Project
    admin** to another member (200) and **may not** assign it to themselves (403, ceiling). The
    grant emits `project.admin_granted`.
17. That same holder still cannot assign any *other* role wider than their own set (403 naming the
    permission) — the escape is one fixed target, not a general exemption.
18. `PATCH` a role removing `project.member.manage` where a project's only administrators hold it →
    409 `LAST_PROJECT_ADMIN_BULK` naming the projects; the same edit succeeds when every affected
    project has another administrator. Same for `DELETE …?reassignToRoleId=` with a target that
    lacks the permission.
19. `ProjectAdminGuard`'s javadoc enumerates **five** doors.

### Compose-time feedback

20. A role holding only `project.member.manage` returns `assignment.canAssign == []`,
    `managesMembers == true` and the warning `MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING`.
21. A duplicate of Contributor plus `sprint.manage` lists Contributor, Commenter and Viewer under
    `canAssign`; a role missing `issue.rank` lists Contributor under `cannotAssign` with
    `missing: "issue.rank"` — the **same** string the runtime 403 carries.
22. `POST /roles/preview` returns the identical block for the same permission list and persists
    nothing (role count unchanged).

### Wrong-scope rows (defence in depth)

23. With a WORKSPACE role id forced into a `project_members.role_id` by direct SQL:
    `GET /projects` and `GET /projects/{p}/members` return **200** with that entry present, its
    role rendered `null` and `myPermissions` `[]`; the foreign role's `key` and `name` appear
    nowhere in the body; an ERROR is logged and `hamstrack_role_scope_violation_total` increments.
24. The same corruption on the **caller's own** `workspace_members` row still 404s
    `GET /workspaces/{id}` — the degrade is not applied to single-resource authorization.
25. Every M1–M4 door refuses a wrong-scope or foreign role id with **422**, not a 500 and not a
    silent accept.

### Tenancy

26. A non-member gets **404** from every one of R1–R7 and M4 — including for a role that exists.
27. A member without `workspace.role.manage` gets **200** from R1 and **403** from R3–R7 and from
    R1 with `includeUsage=true`.
28. Usage counts for a **built-in** role in workspace A do not include workspace B's members,
    invites or project members.
29. No new query reads `roles` by bare id; `RoleRepository` still does not extend `JpaRepository`.

### Regression

30. `PermissionParityTest`, `BuiltInRoleSeedParityTest` and `RoleIdsMatchMigrationTest` stay green
    with **no** declared-divergence changes: S4 adds no permission, edits no seed and moves no call
    site. Any change to those tables means something in this slice went wider than specified.
31. `mvnw.cmd test` green; the SPA is untouched, so `npx tsc -b` is unaffected (and remains the only
    real type gate).

---

## 11. Where this contradicts the epic spec — for S8, not now

Do **not** edit `roles-permissions-proposal.md` mid-slice. Record these and let S8 reconcile them.

1. **§11.2 is incomplete now that the Owner is exempt.** The section states the ceiling flatly and
   says nothing about the workspace Owner's exemption for *definition* and *assignment* within
   their own workspace (owner decision, 2026-08-17), nor that the ceiling continues to bind at
   project scope between project members. S8 must write both, and must state the consequence this
   slice depends on: **the Owner exemption is the only reason `project.administer.all` is reachable
   at all**, since no built-in role holds it and the definition ceiling would otherwise make a
   permission seeded on nothing permanently unmintable.
2. **§11.2 does not describe the project-scope escape** (§4 here). Add it as a named exception with
   its `target ≠ actor` constraint and its two-cooperating-members residual.
3. **§4's table says "Create / edit / delete a custom role → `workspace.role.manage`" with no
   mention of a definition ceiling.** This spec's position: the definition ceiling applies to
   **WORKSPACE-scoped** roles only (compared against the actor's workspace `PermissionSet`, Owner
   exempt), and **not** to PROJECT-scoped ones — a project role has no single comparand at
   workspace level, and inventing one (`effectiveProjectPermissions(actorWorkspacePerms, null)` =
   the four curator permissions) would forbid an Admin from duplicating Contributor, i.e. break the
   product's primary recipe. Its entire practical effect at workspace scope is therefore *"only an
   Owner may mint `project.administer.all`"* — which is a small, valuable, exactly-right rule.
4. **§11.4's "Delete a role in use → 409 … unless `?reassignToRoleId=`"** does not anticipate the
   self-held case (§7.1 R5) or the bulk stranding doors (§6). S8 should fold both in.
5. **§16 puts `GET /roles` under S4 but the default-role pickers under S7.** This spec keeps that
   split and covers the two `default_project_role_id` columns in the reassign anyway, because the
   FK exists today and a `NO ACTION` violation later would be a 500. Note it so S7 does not
   re-implement it.

---

## 12. What S4 does **not** do

- **No UI.** No screens, no components, no store changes, no `openapi.yaml` client regeneration.
  The Roles screen, the role editor, the People tabs and the "this role can assign…" rendering are
  **S6**.
- **No access mode.** `project_access_mode`, the workspace General switch, the project/workspace
  default-role pickers and the STRICT impact preview are **S7**. This slice does not add a write
  path for either `default_project_role_id` column.
- **No docs.** `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, `docs/project-state.md` and
  the `CLAUDE.md` gotcha rewrite are **S8** (`api-docs-sync` runs there). One exception: mark the
  legacy `role` fields deprecated when S8 runs, and file the removal ticket.
- **No migration**, no new column, no new table, **no new permission**, and no change to any
  built-in role's seed. §10 R30 is how that is enforced.
- **No tier gating and no profile fork.** If custom roles were ever limited it would be
  `max-custom-per-workspace`, never a second code path (§13).
- **No audit table.** Structured INFO lines (ids only, Loki-safe) are the whole record, as with
  HD-132/HD-136. The audit-log epic is §19 OQ 7.
- **No groups as grantees**, no permission-scheme reuse across workspaces, no time-bounded grants.
- **No removal of the legacy `role` key fields**, and no change to `GET /api/permissions`.
- **No cross-node cache invalidation** (§2).
- **No "leave project" / self-removal endpoint**; `DELETE /projects/{p}/members/{u}` is unchanged
  apart from sharing the guards.

---

## 13. Open questions

Each with a recommendation. None blocks the build.

1. **Should `GET /roles` be open to every member, or gated on `workspace.role.manage`?**
   *Recommendation: open* — §12 of the epic already decided it (the People tab renders role names
   for every member) and `includeUsage` carries the sensitive half. **Cost if wrong:** a member
   learns the names of roles they cannot hold. Negligible.
2. **Should `name` uniqueness be a DB constraint rather than an application check?**
   *Recommendation: application only* — it is a UX guard, the race outcome is cosmetic, and a
   partial unique index on `LOWER(name)` would need a migration this slice otherwise does not have.
   **Cost if wrong:** two identically named roles after a simultaneous double-submit. Low.
3. **Should the self-held delete refusal (R5) be a per-row ceiling instead of a blunt 409?**
   *Recommendation: blunt* — one existence query, zero escalation surface, and a remedy the refused
   person can perform. **Cost if wrong:** an admin has to change their own role before deleting a
   role they hold. Low, and it can be relaxed later without a data change.
4. **10 s, or 5 s, for `roleView`?** *Recommendation: 10 s* (§2). **Cost if wrong:** five extra
   seconds of a revoked permission on non-editing nodes. Revisit only if Cloud ever runs enough
   instances that the window is audited.
5. **Should `assignment` be computed for built-in roles too?** *Recommendation: yes* — it costs
   nothing (all sets are cached) and the Roles screen shows built-ins and custom roles in one table;
   asymmetric responses would push the branch into S6.
