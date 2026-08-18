# HD-123 · S7 — project access mode & the default-role picker (build spec)

**Ticket:** HD-130 (slice S7 of epic HD-123) · **Branch:** `feat/hd-123-roles-permissions` · **At:** `fe2380d`
**Author:** systems-analyst · **Date:** 2026-08-18 · **Status:** spec, ready to build

> This is a **delta document**. The model (§5.2), the catalog, the ceiling (§11.2), the tenancy
> rules (§12) and the slice ordering are `docs/design/roles-permissions-proposal.md`; S1–S6 and
> HD-132/HD-136 have shipped, and `docs/design/roles-permissions-s4-spec.md` is the nearest
> precedent for shape and tone. Read both first. What follows is only what S7 decides, its
> contracts, its guards and its tests — plus the places the shipped code and the epic spec no
> longer agree (§12), which S8 reconciles and **this slice must not edit**.

**Highest-risk assumption of this slice, stated up front:**

> **That the access mode changes exactly one thing — whether the §5.2 fallback chain yields a role
> — and that every guard which consults that chain stays mode-aware after S7 splits it in two.**
> S7 needs a *mode-independent* view of the chain (the ceiling comparand, the picker, the preview)
> alongside the existing mode-aware one (`WorkspaceAccessService.defaultProjectRole`). There will
> therefore be two methods that look interchangeable and are not. Four live call sites read the
> mode-aware one to decide whether somebody *inherits* something — `ProjectAdminGuard.cannotBeStranded`,
> `ProjectAdminGuard.inheritedAdministratorSurvives`, `ProjectService.removeMember`'s
> `LEAVING_DEFAULT` ceiling, and `projectContext` itself. Pointing any of them at the declared
> chain re-grants, in a `STRICT` workspace, an inheritance that does not exist: a stranding excuse
> that excuses nothing, a ceiling bounded by a role nobody holds, and a permission set handed to a
> member the mode says holds none. **No test outside the ones in §11 would notice.** The mechanical
> guard: `declaredDefaultProjectRole` is the *only* method allowed to ignore the mode, it is called
> from exactly three places (the two ceilings and the impact model), and its javadoc says so.

---

## 1. Scope of S7

**In:** the write paths that make `project_access_mode` and the two `default_project_role_id`
columns reachable — the workspace General switch, the workspace default-role picker, the project
default-role picker — each bounded by a grant ceiling; the impact preview; the three stranding
doors those writes open plus the one HD-136 left; the `app.workspace.default-project-access-mode`
property; the SPA for all of it.

**Out:** everything in §11.

**The enforcement branch is already shipped and must not be rewritten.**
`WorkspaceAccessService.defaultProjectRole` already returns `null` in `STRICT`, `projectContext`
already composes `effectiveProjectPermissions(workspacePermissions, null)` from it, and
`ProjectRoleResolutionTest` already pins both, including the case that matters most — a `STRICT`
workspace does not revoke the workspace Owner's `project.curate.all` bypass and does not widen it.
S7 adds **no new branch in the resolver**. If a builder finds themselves writing
`if (mode == STRICT)` anywhere outside `defaultProjectRole`, stop: that is the second
authorization path this model exists to prevent, and it is the same rule that forbids forking DC
from Cloud.

**No migration.** `workspaces.project_access_mode`, `workspaces.default_project_role_id` and
`projects.default_project_role_id` all landed in V14; `roles`/`role_permissions` in V13; `TEAM_LEAD`
in V16. This slice adds no column, no table, no permission and no change to any built-in seed. If a
builder reaches for a Flyway file, something has gone wrong — re-read this line. (§8 covers the one
thing the schema does *not* have and which S7 deliberately does not add: a `version` column on
`workspaces`.)

---

## 2. The model, restated once, because every decision below follows from it

Effective project role of *U* in project *P*:

1. the role on *U*'s explicit `project_members` row, if any; else
2. the **declared default** — `projects.default_project_role_id` → `workspaces.default_project_role_id`
   → built-in **Contributor** — **but only while the workspace is `OPEN`**; else
3. nothing.

Plus, unconditionally, the workspace-scoped grants `project.curate.all` (built-in Owner/Admin: four
permissions) and `project.administer.all` (seeded on nothing).

So the mode decides **only whether non-members inherit the declared default**. There is no
enforcement-off mode and S7 does not create one. Two consequences the whole slice leans on:

- **`OPEN` is the identity.** A workspace that never opens the new screen is byte-identically the
  workspace it was before S7 (§11 A1–A3 prove it).
- **The declared default exists in both modes.** In `STRICT` it is inert but stored, and it becomes
  live the moment someone flips back. Therefore it must be **bounded in both modes** — a ceiling
  that only bites while `OPEN` is a ceiling you step over by flipping twice.

---

## 3. Resolution 1 — the ceiling on the default-role pickers

**The picker is an escalation door and this slice is what makes it reachable.**
`defaultProjectRole` hands the named role to every workspace member with no explicit
`project_members` row — which, per §2.3 of the epic, is nearly everyone. Today the only validation
on those two columns is scope + ownership (`RoleRepository.findAssignable`, enforced at read time
by `WorkspaceAccessService.defaultProjectRole`'s T2 assertion). There is **no ceiling against the
actor**, because there is no write path. Without one, a workspace Admin holding `project.curate.all`
(four permissions) sets the workspace default to built-in **Project admin** and silently hands every
member all twenty — `issue.delete` and unrestricted `attachment.delete` included, none of which the
Admin holds anywhere.

**Resolved: the assignment ceiling applies to both pickers, on both ends of the change, using
`PermissionSet.firstNotCovered` — the same predicate `WorkspaceMemberService.updateRole` and
`ProjectService.requireGrantable` already use. The comparand differs by scope, and that is the only
new decision.**

### 3.1 The comparands

| Write | Gate | Ceiling comparand | Exempt |
|---|---|---|---|
| `workspaces.default_project_role_id` | `workspace.edit` | `effectiveProjectPermissions(ctx.permissions(), builtInContributor)` — the product's floor for a project, plus whatever the actor's **workspace** role grants in every project (`project.curate.all` / `project.administer.all`) | built-in workspace **Owner**, in their own workspace |
| `projects.default_project_role_id` | `project.member.manage` | `ctx.permissions()` — the actor's real, already-resolved effective set **in that project** | nobody |

**Why a fixed Contributor baseline at workspace scope, and not the actor's live effective set.**
Three candidates were considered and two are wrong:

- *"The actor's workspace-derived project permissions"* (`effectiveProjectPermissions(perms, null)`
  = the curator four) would refuse an Admin setting the default to **Contributor** — the value the
  product ships. A ceiling that forbids the current state is not a ceiling, it is a bug. This is the
  same trap `RoleService.requireWithinDefinitionCeiling` documents at length for PROJECT-scoped role
  definition, and the same answer applies.
- *"The actor's effective set under the current default"* is honest but **moves when the thing it
  bounds moves**, which makes it a one-way ratchet: an Admin narrows the default from Team lead to
  Viewer (allowed — they inherited Team lead at the time), and now their comparand is
  Viewer ∪ curator-four and they cannot put it back. Only the Owner can. A ceiling whose past
  applications restrict its future ones is a trap, not a rule.
- **The fixed baseline** — built-in Contributor ∪ the actor's workspace grants — is stable,
  explicable in one sentence (*"you may set the default to anything a workspace member already
  gets by default; anything beyond that needs an Owner"*), un-gameable by moving the default around,
  and it keeps the shipped value settable. Its entire practical effect is: **an Admin may set the
  workspace default to Viewer, Commenter, Contributor or any custom role within Contributor's set,
  and may not set Team lead, Project admin, or anything carrying `project.member.manage`,
  `project.archive`, `project.taxonomy.manage`, `issue.delete` or unrestricted `attachment.delete`.**
  That is exactly the escalation named above, refused.

**Both ends, and at workspace scope the "current" end is not vacuous** because the comparand does
not include the current default: an Admin may not *replace* a default that is itself outside the
baseline. So an Owner who sets the workspace default to Team lead cannot have it silently narrowed
back by an Admin — the same "whatever you may not grant, you may not strip" rule
`requireWithinDefinitionCeiling` applies to role contents, and the same rule `updateRole` applies to
both ends of a membership change.

At project scope the current end **is** vacuous for an actor with no explicit row (their
`ctx.permissions()` already contains the default they are changing) and sharply non-vacuous for an
explicit member: a narrow custom role holding `project.member.manage` cannot narrow a project
default of Project admin. Keep both calls; the vacuous case costs one bit test.

### 3.2 The refusal must name the offending permission

The ceiling is a **subset** rule, not a ladder — so "insufficient role" is not an answer.
`firstNotCovered` returns the first uncovered grant in catalog order and every existing ceiling
refusal quotes it (`WorkspaceGrantCeilingException`, `ProjectGrantCeilingException`). Reuse both
exceptions verbatim; add **two** `GrantCeilingAction` constants for the project-scope one so the
sentence fits the act:

| Constant | Phrase |
|---|---|
| `SETTING_DEFAULT` | `"You cannot make this the default role for everyone in this project"` |
| `REPLACING_DEFAULT` | `"You cannot change a default that grants more than you hold"` |

At workspace scope `WorkspaceGrantCeilingException(roleName, missing)` already reads correctly and
takes no action word; do not grow one.

403 in both cases — the caller is a proven member and the resource plainly exists.

### 3.3 The §4 escape does **not** extend to defaults

`ProjectService.requireGrantable` lets a holder of `project.member.manage` assign the built-in
Project admin role to **another member**, ceiling notwithstanding, because sixteen of the twenty
project permissions are otherwise unreachable from inside a project. Its `target != actor`
constraint is load-bearing and a default **has no target** — or rather, its target is everyone,
including the actor. Extending the escape to the picker would hand all twenty permissions to the
whole workspace on the authority of one, which is precisely the escalation §11.2 exists to stop and
precisely the argument HD-136's `adoptAll` safety case rests on. **The escape is checked at the
membership doors and nowhere else; the default-role doors call the plain ceiling.** Assert it: the
same actor who may promote a colleague to Project admin must get a 403 naming `issue.delete` when
they aim the same role at the project default (§11 C4).

---

## 4. Resolution 2 — the impact preview, and what makes it truthful

Flipping to `STRICT` on a live workspace and silently removing access from people who had it is the
failure this epic exists to prevent. So the numbers have to mean something exact.

### 4.1 What is counted

The change removes inheritance. Therefore, for every **live** (non-archived) project of the
workspace, the affected population is *ACTIVE workspace members with no explicit `project_members`
row in that project*, and what each of them is left with is
`effectiveProjectPermissions(theirWorkspacePermissions, newFallback)` — empty unless their workspace
role carries `project.curate.all` or `project.administer.all`.

Four numbers, each with a definition a reviewer can check against a query:

| Field | Definition |
|---|---|
| `membersOnDefault` (per project) | ACTIVE workspace members with no explicit row in that project |
| `membersLosingEverything` (per project) | of those, the ones whose workspace role grants **neither** `project.curate.all` **nor** `project.administer.all` — i.e. who would hold the empty set there |
| `projectsWithNoWriters` (workspace) | live projects where, after the change, **nobody at all** holds `issue.create` — no explicit member's role grants it and the fallback is gone |
| `strandedProjects` (workspace) | live projects that would lose their last administrator (§5) — the list this write will **409** on |

`projectsWithNoWriters` is the honest headline and it replaces the epic's §14.2 copy, which is
**wrong**: *"Only workspace Owners/Admins will be able to change anything there"* is false.
Owner and Admin hold `project.curate.all` = `{project.edit, component.manage, version.manage,
sprint.manage}` and **not** `issue.create`, `issue.edit`, `issue.transition` or `comment.create`. In
a `STRICT` workspace, a project with no explicit members cannot be worked in **by anybody, including
the Owner** — they can rename it and cut a version, and cannot file an issue in it. Say that in the
UI (§9.1) and record the contradiction for S8 (§12.2).

### 4.2 How, and with which queries

One model object, `ProjectAccessImpact.of(workspace, proposal)`, where `proposal` is the post-state
(mode + workspace default role id + optionally one project override). **Four statements, independent
of project count:**

1. live projects of the workspace with their `default_project_role_id`
   (`findAllByWorkspaceAndArchivedAtIsNull`, already exists);
2. `SELECT m.role.id, COUNT(m) FROM WorkspaceMember m WHERE m.workspace.id = :ws AND m.user.status = ACTIVE GROUP BY m.role.id`
   — a new ACTIVE-filtered sibling of the shipped `countMembersByRole` (the shipped one is for role
   *usage* and deliberately counts everybody; do not reuse it here — a deactivated account is not a
   person who loses access);
3. `SELECT m.project.id, m.role.id, COUNT(m) FROM ProjectMember m WHERE m.project.workspace.id = :ws AND m.user.status = ACTIVE GROUP BY m.project.id, m.role.id`
   — explicit rows, grouped, ACTIVE-filtered to match every other administrator count in the codebase;
4. `RoleRepository.findIdsGranting(ws, PROJECT, PROJECT_MEMBER_MANAGE)` — already exists, feeds §5.

Everything else is arithmetic over cached `RoleView`s: `membersOnDefault[p] = totalActive − Σ explicit[p]`,
and the per-role partitions come from `RolePermissionCache`. Per-candidate
`existsActiveMemberWithoutProjectRole` calls happen **only** for stranding candidates, of which there
are **zero** in the shipped configuration (§5.4). A workspace with 200 projects costs the same four
statements as one with two.

### 4.3 How it stays truthful between preview and switch

Three properties, and the honest admission that only the third is a guarantee:

1. **One implementation, two entry points.** The preview endpoint and the write call the *same*
   `ProjectAccessImpact.of`. The numbers cannot drift from the rule, only from time. A second
   count written for the preview would be the HD-98/HD-116 bug class with an audience.
2. **Snapshot, labelled as one.** The response carries `computedAt`; the SPA re-fetches the preview
   when the confirm dialog opens and never renders a preview older than that interaction. There is
   deliberately **no token, no echo, no `expectedCount` field**: the population being counted is
   `workspace_members` × `project_members`, which is not the row being written, so no optimistic
   check on the workspace could make the count exact. Inventing one would be ceremony that *looks*
   like a guarantee. (The precedent is HD-136's `adoptStrandedProjects`, a bare consent flag whose
   entire safety argument is that the server re-derives the set rather than trusting the client's
   copy of it.)
3. **The one number that must be exact is re-derived under the write.** `strandedProjects` is
   recomputed inside the write's transaction and enforced there, whether or not the caller ever
   previewed. A client that skips the preview gets the same 409. That refusal — not the counts — is
   what stands between a flip and an unmanageable project.

Counts are advisory; refusals are authoritative. Write that sentence into the DTO javadoc.

---

## 5. Resolution 3 — the hole HD-136 left, and the three doors S7 opens

### 5.1 The hole

`ProjectAdminGuard.lockStrandedProjects` builds its candidate set from **explicit `project_members`
rows only**: `adminsByProject` is populated from `lockAllByWorkspaceAndRoleIdIn`, and
`LockedProjectAdmins.wouldStrand` requires the departing member to be *in* that set. So a project
whose administrators exist **only by inheritance** — the declared default grants
`project.member.manage`, nobody has an explicit administering row — is never a candidate, and the
refinement that would have caught it (`cannotBeStranded`, which asks whether anybody else stands on
the fallback) never runs. Remove the last workspace member who had no explicit row there and the
project is left permanently unmanageable, with **no 409, no adoption, no log line** — the same
silent-stranding shape the DISABLED approximation is already documented for, by a different route.

It is unreachable today because no built-in role that can be a default grants
`project.member.manage`. **S7 is the slice that makes it reachable**, because S7 ships the picker
that can set a default to Team lead, Project admin or a custom role carrying it. Close it here.

### 5.2 Door 6 — the removal path, inherited administrators

Add to `ProjectAdminGuard`:

```java
List<ProjectRef> projectsAdministeredOnlyByInheritance(Workspace workspace, UUID leavingUserId)
```

A live project qualifies when **all four** hold:

1. its **mode-aware** fallback (`defaultProjectRole(workspace, project)`) is non-null and grants
   `project.member.manage` unrestricted;
2. it has **no** explicit administering `project_members` row (ACTIVE users, administering role ids —
   the same filter the locked paths use);
3. the leaving member has **no** explicit row there (so they were one of the inherited
   administrators — a project that already had none cannot lose its last);
4. `!existsActiveMemberWithoutProjectRole(workspace, project, leavingUserId)` — nobody else is
   standing on the fallback.

Called from `WorkspaceMemberService.remove`, beside `lockStrandedProjects`, and refused with a
**new `errorType`**:

- **409 `STRANDED_BY_INHERITANCE`**, `StrandedProjectsException`'s payload shape (same `projects`
  extension, so the SPA renders one list component), detail: *"Removing this member would leave
  &lt;projects&gt; with nobody able to manage their membership — they administer it through the
  project's default access, not through a membership row. Add an explicit administrator to each
  first (the current default still lets you), or ask the member you are removing to do it while
  they still can."*

**No adoption retry, deliberately, and this is not the same call as doors 1–2.** `adoptAll` writes
the caller a **Team lead** row; here the caller has *no* row and inherits a default that — by
condition 1 — is at least as wide as `project.member.manage` and may be much wider (Project admin).
An explicit row always beats the default (§11.3), so "adopting" would **narrow the adopter** in the
project they were rescuing, which is exactly the one-way trap V16 spends twenty lines avoiding and
which `adoptability`'s `WOULD_DEMOTE` verdict exists to refuse. The H1 test is met without it: the
remedy names an action the refused person can perform *right now*, because the default that is about
to matter still grants them `project.member.manage` today. The residual — an actor who holds a
*narrow explicit* row in that project cannot self-serve — is real, is the case where condition 4
proves nobody else inherits either, and is why the message also names the departing member, who
demonstrably can (HD-136's `cannotAdopt` copy uses the same escape hatch for the same reason).

**Advisory, like doors 4–5.** Conditions 2 and 4 are aggregates and an aggregate cannot take
`FOR UPDATE`. The locked invariant remains the per-row one. Do not paper this over with a lock that
is not there.

### 5.3 Doors 7, 8, 9 — the three writes this slice adds

A change to the mode or to either default column demotes **every inherited administrator at once, in
every affected project**, with no membership row touched — structurally the same as doors 4 and 5
(role edit / delete-with-reassign), and invisible to every guard that exists.

| # | Door | Fires when |
|---|---|---|
| 7 | `PATCH /workspaces/{ws}` setting `projectAccessMode = STRICT` | the current fallback grants `project.member.manage` and the new one (none) does not |
| 8 | `PATCH /workspaces/{ws}` setting `defaultProjectRoleId` | the current declared workspace default grants it and the requested role does not — for every project that does **not** override it |
| 9 | `PATCH /workspaces/{ws}/projects/{p}/default-role` | same, for that one project |

One method serves all three (and the preview):

```java
List<ProjectRef> projectsLosingLastAdministrator(Workspace workspace, ProjectAccessProposal after)
```

A live project qualifies when its **current effective** fallback grants `project.member.manage`, its
**proposed** fallback does not, it has no explicit administering row, and
`existsActiveMemberWithoutProjectRole(workspace, project, NOBODY_EXCLUDED)` is true (somebody is
actually standing on the fallback — the same both-halves proof `cannotBeStranded` insists on, for
the same reason: "the default grants it" alone is wrong in the dangerous direction).

Non-empty → **409 `STRANDED_BY_INHERITANCE`** with a per-door remedy (`StrandedProjectsException`
already takes `change` + `remedy` for exactly this):

- door 7: *"Restricting project access would leave …"* / *"Add an explicit administrator to each first — the current default still lets you."*
- doors 8–9: *"Making &lt;role&gt; the default …"* / *"Choose a default that also manages members, or add an explicit administrator to each project first."*

No adoption path, for §5.2's reason and for doors 4–5's reason: the remedy is satisfiable by the
person refused, with nothing granted to anybody.

**`ProjectAdminGuard`'s class javadoc enumerates the doors and an unlisted door is the bug** — it
must go from five to **nine**, with doors 6–9 marked advisory.

### 5.4 The interaction with the mode, stated so nobody "completes" it later

- `cannotBeStranded` and `inheritedAdministratorSurvives` already call the **mode-aware**
  `defaultProjectRole`, so in `STRICT` they answer "no fallback" and the inheritance *excuse* never
  fires. That is correct and conservative: in `STRICT` nothing is inherited, so a project's
  administrators are exactly its explicit administering rows. **Do not point these at
  `declaredDefaultProjectRole`** — see the highest-risk assumption.
- Door 6 is likewise mode-aware (condition 1 uses the effective fallback), so in a `STRICT`
  workspace it never fires and costs one comparison.
- Doors 7–9 compare the **current effective** fallback against the **proposed effective** one, so a
  flip from `STRICT` to `OPEN`, or any change that widens, can never strand: the guard is one-way
  by construction.
- **In the shipped configuration every one of these branches is free.** The workspace default is
  NULL → Contributor, which does not grant `project.member.manage`; no built-in project role that
  anyone would choose as a default does except Team lead and Project admin. So until somebody uses
  S7's own picker, the whole of §5 is a role-permission bit test and zero queries. Say so in the
  javadoc; it is the reason this can be added to a hot-ish path without argument.

---

## 6. Resolution 4 — `DefaultRoleResponse` stays ids-only, and where `mode` goes

`DefaultRoleResponse` currently emits `projectRoleId` and `workspaceRoleId` verbatim with no scope
assertion. **That is safe only because an id is not a name**: `resolveRoleById` in the SPA looks the
id up in `GET /roles`, which is workspace-scoped and therefore *cannot* return a foreign role, and
renders a placeholder when it finds nothing. The scope/ownership assertion that decides *access*
still runs, in `WorkspaceAccessService.defaultProjectRole`, on the value that actually matters.

**Resolved: S7 adds no name, key, or permission summary to `DefaultRoleResponse`, and no third id.**

- The picker does not need one. S6 already ships `resolveDefaultRole(projectRoles, defaultRole)`,
  which walks the chain client-side against the roles list the page already has and reports **which
  link supplied the value** (`PROJECT` | `WORKSPACE` | `BUILT_IN`). That origin is precisely what
  turns the picker into two distinct choices — *"inherit the workspace default (Contributor)"* vs
  *"use a different role for this project"* — instead of one dropdown that cannot express the
  difference between "chose Contributor" and "inherited Contributor". Use it.
- **The rule, if a builder later feels the pull anyway** (put it in the DTO javadoc): any name, key
  or permission list added here must be resolved through
  `WorkspaceAccessService.resolveRoleOrDegrade(id, RoleScope.PROJECT, workspaceId, DEFAULT_PROJECT_ROLE)`
  and emitted as JSON `null` on failure — never `roleCatalog.view(id).name()`. Emitting the name of
  a role the assertion just refused is exactly the leak the member-list degrade (HD-127 §3b) exists
  to prevent, and it would reach every member of the workspace on `GET /projects`, not one row of a
  People tab.

**`projectAccessMode` goes on `WorkspaceResponse`, and nowhere else.** It is a workspace-level fact
with one source of truth; the project People card reads it from the `['workspace', wsId]` query
every surface already caches. Mirroring it into `DefaultRoleResponse` would be a second copy of a
field that can then disagree with the first, for no request saved. `WorkspaceResponse` also gains
`defaultProjectRoleId` — a raw id, same rule as above: an id, never a name.

---

## 7. Endpoint contracts

All paths are workspace-scoped. Every one resolves through `WorkspaceAccessService.requireMember` /
`resolveProject` **before** any permission is evaluated, so a 403 is reachable only by a proven
member. New service `com.hamstrack.workspace.service.ProjectAccessService` owns W1–W3 (it is the
one place the mode and the workspace default are written, and `WorkspaceService` is already the
create/invite/accept surface); `ProjectService` owns P1–P2, where the `ProjectContext` and the
project ceiling already live.

### 7.1 Workspace

| # | Method & path | Gate | Success |
|---|---|---|---|
| W1 | `GET /api/workspaces/{wsId}/project-access` | `workspace.edit` | 200 `ProjectAccessResponse` |
| W2 | `POST /api/workspaces/{wsId}/project-access/preview` | `workspace.edit` | 200 `ProjectAccessImpactResponse`, persists nothing |
| W3 | `PATCH /api/workspaces/{wsId}` | `workspace.edit` | 200 `WorkspaceResponse` |

**W3 request** (`UpdateWorkspaceRequest`):

```jsonc
{
  "name": "Acme",                    // optional, trimmed, 1..255
  "projectAccessMode": "STRICT",     // optional, OPEN | STRICT
  "defaultProjectRoleId": "0198…",   // optional
  "clearDefaultProjectRole": true    // optional Boolean — BOXED (CLAUDE.md: a primitive here 400s
                                     // every body that omits it), coalesced null→false in the
                                     // compact canonical constructor
}
```

Rules, in this order:

1. no field present → **400**; `defaultProjectRoleId` **and** `clearDefaultProjectRole` both present
   → **422** (`RoleSelectionException.ambiguous()`'s shape — two fields that mean different things
   must not be resolved by precedence);
2. `requireMember` → 404 · `require(WORKSPACE_EDIT)` → 403;
3. `roleCatalog.requireAssignable(RoleScope.PROJECT, wsId, defaultProjectRoleId)` → **422** for
   unknown, foreign or WORKSPACE-scoped. Before anything is read about the workspace's state, and
   **PROJECT scope is not negotiable**: a WORKSPACE role accepted here would put `workspace.edit`
   into every member's `ProjectContext` in every project of the workspace — the wrong-scope
   escalation with the widest possible blast radius in the product;
4. the ceiling, both ends (§3.1), Owner exempt → **403** naming the missing permission;
5. `projectsLosingLastAdministrator` for the composite proposal → **409 `STRANDED_BY_INHERITANCE`**;
6. **no-op detection**: if every requested value equals the stored one, return 200 without writing
   (so `updated_at` does not move and "setting OPEN on an OPEN workspace changes nothing" is true at
   the row level, not just at the permission level);
7. mutate and save last (the `@Version` ordering rule, even though this entity has none — the habit
   is what keeps it true when one is added);
8. `log.info("workspace.project_access_changed workspace={} actor={} mode={}->{} defaultRole={}->{} strandedChecked={}")`,
   ids only, Loki-safe. This is the only record; there is no audit table (epic §19 OQ 7).

**W1 response** (`ProjectAccessResponse`) — one request powers the General page:

```jsonc
{
  "mode": "OPEN",
  "defaultProjectRoleId": null,           // null = the built-in Contributor
  "settable": {
    "canSet":    [ { "roleId": "…", "name": "Viewer" } ],
    "cannotSet": [ { "roleId": "…", "name": "Project admin", "missing": "project.archive" } ]
  },
  "impact": { … }                          // ProjectAccessImpactResponse for the CURRENT state
}
```

`settable` is derived server-side with `firstNotCovered` against §3.1's workspace comparand, over
the PROJECT-scoped roles available in this workspace. **It is not re-derived in TypeScript** — the
own-only/unrestricted asymmetry is subtle and a second implementation of a server predicate in the
SPA is the HD-98/HD-116 bug class by construction (§5.3). `missing` is the same string the runtime
403 carries, so the greyed-out tooltip and the refusal quote one value. Same shape and same argument
as S4's `assignment` block; reuse the DTO idiom, not the code path (the comparand is different).

**W2 request** = `UpdateWorkspaceRequest` (the same shape as the write, POST because it carries a
body and persists nothing — precisely `POST /roles/preview`'s idiom). It runs steps 1–5 above and
returns the impact **instead of** applying, including the refusals it would produce:

```jsonc
{
  "computedAt": "2026-08-18T09:14:00Z",
  "from": { "mode": "OPEN",   "defaultProjectRoleId": null },
  "to":   { "mode": "STRICT", "defaultProjectRoleId": null },
  "activeMembers": 24,
  "projects": 5,
  "projectsWithNoExplicitMembers": 3,
  "projectsWithNoWriters": 3,
  "perProject": [
    { "id": "…", "key": "PAY", "name": "Payments",
      "membersOnDefault": 21, "explicitMembers": 3,
      "membersLosingEverything": 21, "noWritersAfter": false }
  ],
  "strandedProjects": [ { "id": "…", "key": "OPS", "name": "Ops" } ]
}
```

A ceiling failure on the previewed role is returned as the ordinary **403** — the preview must not
succeed with a "would fail" field, or a client learns to ignore it.

### 7.2 Project

| # | Method & path | Gate | Success |
|---|---|---|---|
| P1 | `GET /api/workspaces/{wsId}/projects/{projectId}/default-role` | `project.member.manage` | 200 `ProjectDefaultRoleResponse` |
| P2 | `PATCH /api/workspaces/{wsId}/projects/{projectId}/default-role` | `project.member.manage` | 200 `ProjectResponse` |

**Why a separate endpoint rather than a field on `PATCH /projects/{p}`.** That endpoint is gated on
`project.edit`; this write must be gated on `project.member.manage` (epic §4 — it is membership
authority, not project settings, and the two are deliberately different grants). Folding a
second-permission field into a single-permission PATCH is how a gate gets forgotten; the issue PATCH
carries its multi-permission rules because it has to, not because it is a pattern to copy.

**P2 request:** `{ "roleId": "0198…" }` **or** `{ "inherit": true }` — exactly one → 422 otherwise
(`RoleSelectionException`). `inherit: true` writes `NULL`, which means *"fall back to the workspace
default"*, and is the same two-choice shape the picker renders. Guards, in order: `resolveProject`
(404) → `require(PROJECT_MEMBER_MANAGE)` (403) → `requireAssignable(PROJECT, ws, roleId)` (422) →
ceiling both ends against `ctx.permissions()` (403) → `projectsLosingLastAdministrator` for this one
project (409) → same-value no-op → mutate, save, log
`project.default_role_changed project={} actor={} from={} to={}`.

Response is the full `ProjectResponse` so the People card re-renders from the write (its
`defaultRole` chain is already on that DTO).

**P1 response:**

```jsonc
{ "projectRoleId": null, "workspaceRoleId": null, "mode": "OPEN",
  "settable": { "canSet": [...], "cannotSet": [ { "roleId": "…", "name": "…", "missing": "issue.delete" } ] } }
```

The two ids duplicate `ProjectResponse.defaultRole` on purpose: this endpoint is what a picker
dialog opens against, and a self-contained read is worth one repeated column. `mode` is included
because the dialog must be able to say *"this workspace is Restricted, so nothing is inherited right
now — this default applies again if it is switched back to Open"* without a second fetch.

---

## 8. Tenancy posture

| Endpoint | Unknown ws / non-member | Member, no permission | Foreign / wrong-scope role id |
|---|---|---|---|
| W1 `GET /project-access` | **404** | **403** | n/a |
| W2 `POST /project-access/preview` | **404** | **403** | **422** (body value) |
| W3 `PATCH /workspaces/{ws}` | **404** | **403** | **422** (body value) |
| P1 `GET /…/default-role` | **404** (ws or project) | **403** | n/a |
| P2 `PATCH /…/default-role` | **404** (ws or project) | **403** | **422** (body value) |

Standing rules for this slice:

- **Every role id here arrives in a body, so every one is 422** — never 404-with-detail, never
  accepted. `roleCatalog.requireAssignable(RoleScope.PROJECT, workspaceId, roleId)` is the only door;
  `RoleRepository` still does not extend `JpaRepository` and no new bare-id finder appears.
- **No new query reads `roles`, `project_members` or `workspace_members` without a workspace
  filter.** §4.2's three aggregates each carry `workspace.id`; the built-ins are shared rows
  (`workspace_id IS NULL`), so an unfiltered `GROUP BY role_id` would count another tenant's
  headcount — the same defect S4's usage counts had to be written around, in a slice that counts
  people for a living.
- **The impact response names projects.** `ProjectRef` (id/key/name) of the caller's own workspace
  only — the same payload `StrandedProjectsException` already ships. No user names, no emails, no
  ids of people: the preview answers *how many*, never *who*. That is deliberate — the counts are
  for a decision, and a per-person list would be a workspace-wide access report on an endpoint gated
  by one permission.
- The preview is gated on `workspace.edit` rather than open to members: project member lists are
  already open, but an aggregated "who has write access to what, workspace-wide" is a different
  object, and it is the control's own preview.

---

## 9. Frontend impact

`DESIGN.md` governs everything visual (Beacon tokens only, no hardcoded hex). The **only** permitted
input to a UI gate is `myPermissions` — no component may read `myRole` (§5.3), and S5 removed the
last one.

### 9.1 New: Workspace settings → General

`/w/:wsId/settings/general`, new first entry in `WorkspaceSettingsArea`'s `SECTIONS` with
`permission: 'workspace.edit'` (the array already supports a per-section permission and filters on
it). Three cards, one `GET /project-access` behind them:

1. **Workspace name** — the first rename affordance the product has ever had.
2. **Project access** — two radios with the epic's §14.2 copy:
   > ○ **Open** — everyone in the workspace can work in every project, using each project's default
   > role. Add someone to a project only to give them a different role.
   > ○ **Restricted** — only people added to a project can change anything in it. Everyone can still
   > see every project.

   Selecting **Restricted** opens a confirm dialog that **re-fetches** `POST /project-access/preview`
   on open (never a stale snapshot) and renders: the per-project table, the headline
   *"In N of your M projects nobody has been added explicitly. After this, nobody — including you —
   will be able to file or edit an issue there."* (see §4.1: the epic's Owner/Admin wording is
   false), and, when `strandedProjects` is non-empty, a blocking list with the remedy and **no
   confirm button**. Confirm is a plain `PATCH`; the 409 is rendered by the shared
   `ProjectRefList` + `classifyConflict` the S6 screens already use.
3. **Default project role** — a `RoleSelect` over PROJECT-scoped roles with an explicit
   `placeholder="Contributor (built-in default)"` for the null value (the shared `Select` falls back
   to its *first* option when nothing matches — a picker without the placeholder would silently
   relabel "inherited" as whichever role sorts first). Roles in `settable.cannotSet` render disabled
   with `title="Requires <missing>"`.

### 9.2 Changed: Project settings → People

- The disabled **"Change default access"** button + `coming soon` chip become a real picker: two
  radio choices driven by `resolveDefaultRole(...).origin` — *"Inherit the workspace default (X)"*
  (writes `{inherit:true}`) and *"Use a different role in this project"* + `RoleSelect`
  (writes `{roleId}`). Same disabled-with-reason treatment from P1's `settable`.
- **The card's copy is currently false in `STRICT`.** It states unconditionally that *"Everyone in
  this workspace can contribute to X without being added to it"*. With `mode === 'STRICT'` (read
  from the cached workspace query) it must instead say: *"Project access is Restricted for this
  workspace, so nobody works here through the default — only the people listed below can change
  anything. This default applies again if project access is switched back to Open."* The member
  count line switches from *"N members work here through that default today"* to *"N members would
  work here through it if project access were Open"*.
- The card stays visible **while the mechanism is off** in both directions — that is its Rule C
  affordance and the reason it is drawn first, full width.

### 9.3 Everything else reacts for free, and that is the test

No board, backlog, issue-detail, palette or NavRail change is in scope. A `STRICT` flip empties
`myPermissions` for members with no explicit row, and S5's gating already hides every control that
those permissions guard. If any surface still renders a control after the flip, the bug is in S5's
gating (a `myRole` predicate that survived), not here — and it is worth one grep in acceptance
(§11 F4).

### 9.4 Types & API client

`types.ts`: `Workspace.projectAccessMode: 'OPEN' | 'STRICT'`, `Workspace.defaultProjectRoleId: string | null`
(both optional for fixtures predating S7, as `defaultRole` already is). `api.ts`:
`apiUpdateWorkspace`, `apiGetProjectAccess`, `apiPreviewProjectAccess`,
`projectDefaultRoleApi.get/set`. Invalidate `['workspace', wsId]`, `['workspaces']`,
`['project', wsId, projectId]` and `['projects', wsId]` after any write — a mode flip changes
`myPermissions` on every project response, so a stale project list is a stale gate.

---

## 10. DC / Cloud

Nothing here is profile-gated and nothing may become so. Custom roles and access modes are product
features, not plan features (epic §13).

| Property | Env | Default (`dc` **and** `cloud`) | Meaning |
|---|---|---|---|
| `app.workspace.default-project-access-mode` | `DEFAULT_PROJECT_ACCESS_MODE` | **`OPEN`** | Mode for **newly created** workspaces only |

- A `@ConfigurationProperties` class under `common.config` (`WorkspaceProperties`), not `@Value`,
  read by `WorkspaceService.create` — the one place a `Workspace` is constructed. Demo seeding goes
  through the same method, so a DC operator who sets `STRICT` gets a strict demo workspace, which is
  correct and must not be special-cased (epic §11.5: *no permission bypass may be added for
  seeding*).
- **It never changes an existing workspace.** V14 set every workspace `OPEN` and no property may
  retroactively move one; the only way is W3. This is already written into V14's comment and must
  stay true.
- An invalid value fails fast at startup (enum binding), which is the right failure for a security
  default.
- Wiring per the `dc-cloud-guard` checklist: `application.properties` → `docker-compose.prod.yml` →
  `.env.prod.example` → `README.md`. No storage, email, billing or identity dependency, so there is
  no cloud-only assumption needing a self-hosted path.

---

## 11. Acceptance criteria

### A. `OPEN` is a no-op — the guarantee this slice must not break

1. A workspace created after S7 with the property at its default has `project_access_mode = 'OPEN'`
   and `default_project_role_id IS NULL`. `PermissionParityTest`, `BuiltInRoleSeedParityTest`,
   `RoleIdsMatchMigrationTest` and `ProjectRoleResolutionTest` stay green with **no** declared-
   divergence changes: S7 adds no permission, edits no seed and moves no call site.
2. `PATCH /workspaces/{ws}` with `{"projectAccessMode":"OPEN"}` on an already-`OPEN` workspace →
   200, and the `workspaces` row is **not written**: `updated_at` is unchanged.
3. For a workspace member with no explicit row, `myPermissions` on `GET /workspaces/{id}`,
   `GET /projects` and `GET /projects/{id}` is **byte-identical** before and after this slice
   (new fields appear on the response; no permission changes).
4. `PermissionResolutionQueryCountTest` still asserts **2 / 4 / 0**. The declared/effective split
   adds no statement to the resolution path (the mode check short-circuits first, and role views are
   cache hits).

### B. The round trip

5. Baseline: three archetypes (workspace Owner, workspace Admin, plain Member — none with a
   `project_members` row) × three projects; capture every `myPermissions`.
6. Flip to `STRICT` → the plain Member gets **403** on create / edit / transition / rank / comment /
   attach and **200** on every read (list, get, board, backlog, search, history, attachments
   download). Owner and Admin retain exactly `project.curate.all`'s four permissions and are
   **403** on `POST /issues` — the honest consequence §4.1 requires the UI to state.
7. No data changed by the flip: `project_members` row count identical, no issue lost an assignee,
   no sprint/version/component touched, `issues.updated_at` untouched.
8. Flip back to `OPEN` → every captured `myPermissions` is restored **byte-identically**, in list
   and detail responses alike.
9. A project whose `default_project_role_id` is the built-in **Viewer** behaves as strict while its
   workspace is `OPEN` (this is the model's "None" — see §12.1).

### C. The ceiling

10. Workspace Admin (holds `project.curate.all`, not `project.administer.all`) setting the workspace
    default to built-in **Project admin** → **403** naming a permission they lack (`project.archive`
    in catalog order), and the stored value is unchanged.
11. The same Admin setting it to **Viewer**, **Commenter** or **Contributor** → 200. Setting it to
    **Team lead** → 403 naming `project.member.manage`.
12. Workspace **Owner** setting it to Project admin → 200 (root of trust, own workspace only).
13. **The §4 escape does not reach the picker**: an actor holding a narrow custom role with
    `project.member.manage` may `PATCH .../members/{u}` to built-in Project admin for another member
    (200, existing behaviour) and gets **403** setting that same role as the project default.
14. An Admin may not *narrow* a workspace default that is outside their baseline: with the default
    at Team lead, an Admin setting it to Viewer → **403** (both ends checked); the Owner → 200.
15. A project actor with a narrow custom role holding `project.member.manage` cannot change a
    project default of Project admin → 403; an actor with the built-in Project admin role can.
16. Every ceiling refusal's `detail` contains the permission key, and it is the **same string**
    `settable.cannotSet[].missing` carries for that role.

### D. Stranding

17. Door 6: workspace default = **Team lead**, project P has no explicit rows, exactly two ACTIVE
    members and one is removed from the workspace → 200 (one inherited administrator left); remove
    the second → **409 `STRANDED_BY_INHERITANCE`** naming P. Retrying with
    `adoptStrandedProjects=true` does **not** clear it (no adoption path for this door) and the
    response says who can.
18. Door 7: same setup, flipping to `STRICT` → **409** naming P; after an explicit administrator is
    added to P, the flip → 200.
19. Door 8: same setup, setting the workspace default to Contributor → **409**; setting it to
    Project admin (also grants member.manage) → 200.
20. Door 9: a project whose own default grants member.manage, changed to `inherit` where the
    workspace default does not → **409** naming that project.
21. All four are **no-ops in the shipped configuration**: with the workspace default at Contributor,
    a flip to `STRICT` issues **no** `existsActiveMemberWithoutProjectRole` query at all.
22. `ProjectAdminGuard`'s javadoc enumerates **nine** doors, four marked advisory.
23. `cannotBeStranded` / `inheritedAdministratorSurvives` / `removeMember`'s `LEAVING_DEFAULT`
    ceiling still call the **mode-aware** `defaultProjectRole`: a `STRICT` workspace refuses a
    removal that `OPEN` would have excused (a test that fails loudly if someone "unifies" the two
    methods).

### E. Preview & truthfulness

24. `POST /project-access/preview` persists nothing (workspace row and `updated_at` unchanged) and
    returns counts that **exactly match** the state after applying the same body: apply it, recount
    from the database, assert equality field by field.
25. `membersLosingEverything` excludes holders of `project.curate.all` / `project.administer.all`
    and excludes DISABLED accounts.
26. `projectsWithNoWriters` counts a project whose only explicit member holds **Viewer** — the
    number is about `issue.create`, not about membership.
27. The preview's `strandedProjects` equals the write's 409 list for the same body.
28. Query count is **independent of project count**: the same four statements for a workspace with
    2 projects and one with 60 (assert via the existing query-count harness).
29. A ceiling failure surfaces from the preview as **403**, not as a field in a 200 body.

### F. Tenancy & frontend

30. A non-member gets **404** from W1, W2, W3, P1 and P2 — including for a workspace and project
    that exist. A member without the gate gets **403**.
31. A PROJECT-scoped role id from workspace B, and a WORKSPACE-scoped role id from workspace A, both
    → **422** on W3 and P2. Neither is ever stored.
32. Usage/impact counts for a workspace never include another workspace's members: seed two
    workspaces sharing built-in roles and assert.
33. `grep -r "myRole ===" src/main/frontend/src --include=*.tsx --include=*.ts` still returns zero
    hits outside display code and fixtures; `npx tsc -b` clean (**not** `tsc --noEmit`).
34. Vitest: the General page renders both radios and the disabled reason for an unsettable role; the
    Project People card renders the `STRICT` copy when the workspace is restricted and the `OPEN`
    copy when it is not; the confirm dialog re-fetches the preview on open.
35. `mvnw.cmd test` green.

---

## 12. Where this contradicts the epic spec — for S8, not now

Do **not** edit `roles-permissions-proposal.md` mid-slice. Record these; S8 reconciles them
alongside S4's five (`roles-permissions-s4-spec.md` §11).

1. **"Default role = None" (§5.2, §8.5, §15 AC 12) is not expressible, and does not need to be.**
   A NULL `default_project_role_id` means *inherit*, not *none*, so per-project strictness has no
   dedicated value — but the built-in **Viewer** grants ∅, which is the same thing with a name a
   user can read and a role a picker can offer. S8 should rewrite both sections to say
   *"set that project's default to **Viewer**"*, and §8.5's private-projects seam is unaffected
   (it still needs no new column).
2. **§14.2's `STRICT` impact copy is factually wrong.** *"Only workspace Owners/Admins will be able
   to change anything there"* — they hold `project.curate.all`, which is
   `{project.edit, component.manage, version.manage, sprint.manage}` and contains no issue or
   comment permission. In a restricted project with no explicit members, **nobody can file or edit
   an issue, including the Owner**. S8 must replace the sentence; §9.1 above carries the wording the
   UI actually ships.
3. **§4's actor table says "Set a project's default role → `project.member.manage`" and "Set the
   workspace's project-access mode / default project role → `workspace.edit`" with no mention of a
   ceiling.** §3 here adds one to both, with two different comparands. S8 folds it into §11.2 beside
   the S4 spec's items 1–3, and must state the workspace comparand explicitly — it is the only
   ceiling in the product measured against a *constant* (the built-in Contributor) rather than
   against the actor's live set, and the reason is worth preserving.
4. **§11.4's "in use" list and §11.5's mode table are silent about stranding.** The role-delete
   guard already counts the two default columns as usage (S4 §7.1 R5 implemented it), but §11.5's
   *"Flipping `project_access_mode` OPEN → STRICT … No data is changed, nothing is unassigned, no
   read is lost"* is true and incomplete: it can leave a project with no administrator, and S7
   refuses rather than allowing it. Add doors 6–9 to §11.4's table.
5. **§16 lists S7's dependencies as S2 and S6.** It also depends on **S4** (the roles list and
   `findAssignable` the pickers resolve through). Cosmetic, but the table is what a future slice
   plan is read from.
6. **§19 OQ 6** asked whether the impact preview ships in S7 "as a simple count (projects where
   explicit members < workspace members)". It ships as §4.1's four numbers instead: the simple count
   is not wrong, it is unactionable, and it cannot express the stranding refusal the write will
   produce anyway.

---

## 13. What S7 does **not** do

- **No per-project access mode.** Per-project strictness is a default of **Viewer** (§12.1); a
  second mode column would be a second place the fallback can be switched off, i.e. two answers to
  one question.
- **No narrowing of reads.** `STRICT` narrows writes only. Narrowing reads is private projects by
  the back door — an explicit non-goal that would silently break `SearchScope`, Home, My Work and
  every cross-project query (epic §12). No `project.view`, no `issue.view`, no new permission at all.
- **No "convert inheritance to explicit rows" action.** The obvious next ask before flipping
  `STRICT` — *"add everybody who currently inherits as an explicit member with the same role"* — is
  out of scope and is the thing §8.4 deliberately refused at migration time: it turns an implicit
  rule into N×M rows and destroys the distinction between "explicitly added" and "backfilled". If it
  is ever built it is its own ticket with its own argument.
- **No adoption path for doors 6–9** (§5.2), and no change to `adoptAll`, `lockAdmins`,
  `lockStrandedProjects` or `lockOwners` beyond the new sibling method and the javadoc enumeration.
- **No `@Version` on `workspaces` or `projects`.** Neither table has one and S7 does not add one:
  that is a migration this slice does not have, and a version column would not make the impact
  counts truthful anyway (§4.3) — the population they count is not the row being written. Two
  concurrent mode flips are last-writer-wins, both callers see the resulting state in their 200, and
  the invariant that must not be lost (stranding) is re-derived inside each write.
- **No audit table.** Structured INFO lines, ids only, as with HD-132/HD-136/HD-127.
- **No docs.** `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, `docs/project-state.md` and
  the `CLAUDE.md` gotcha rewrite are **S8** (`api-docs-sync` runs there).
- **No new permission, no seed change, no migration, no `GET /api/permissions` change.**
- **No workspace `description` field** (there is no column) and no workspace delete.

---

## 14. Open questions

Each with a recommendation. None blocks the build.

1. **Should the `STRICT` confirm require typing the workspace name?** *Recommendation: no.* The flip
   is reversible in one click, changes no data and loses no read; a friction ritual borrowed from
   destructive actions would misrepresent it. **Cost if wrong:** a mis-click, undone in one click.
2. **Should the preview be readable by any member rather than `workspace.edit`?**
   *Recommendation: `workspace.edit`.* It is the control's own preview and it aggregates write
   access workspace-wide. **Cost if wrong:** a lead who cannot open the switch also cannot see what
   it would do. Low, and it is one line to relax.
3. **Should `projectsWithNoWriters` be a refusal rather than a warning?** *Recommendation: warning.*
   A workspace may legitimately restrict a project that nobody is currently working in, and refusing
   would make `STRICT` unreachable for the workspaces most likely to want it. The stranding refusal
   is different: it produces a state **no endpoint can repair**. **Cost if wrong:** an admin
   restricts a project and then has to add members to it. Low.
4. **Should the workspace ceiling's baseline be built-in Contributor, or the workspace's own current
   default when that is narrower?** *Recommendation: built-in Contributor, always.* A moving
   baseline is the ratchet §3.1 rejects. **Cost if wrong:** an Admin in a workspace that has
   deliberately narrowed its default to Viewer can still widen it back to Contributor without an
   Owner. That is the shipped floor, so it is not an escalation past anybody. Low.
5. **Should S7 also expose `project_access_mode` on `ProjectResponse` for convenience?**
   *Recommendation: no* (§6). **Cost if wrong:** one extra cached query read in one component.
   Negligible, and it can be added without a breaking change.
