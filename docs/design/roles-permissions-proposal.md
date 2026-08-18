# Flexible roles & permissions (workspace + project) — Proposal

**Ticket:** HD-123 (epic) · **Status:** spec, awaiting approval · **Branch:** `feat/hd-123-roles-permissions`
**Author:** systems-analyst · **Date:** 2026-08-17

> Read this before implementing anything: §2 (what exists today) is not background, it is the
> contract the migration must reproduce byte-for-byte. §5 is the load-bearing part — three rules
> that make every other decision in this document mechanical.

---

## 1. Problem & goal

Hamstrack ships three role enums whose names promise an authorization model the product does not
have. `ProjectRole.VIEWER` is the *fallback for a user with no project membership row*, and
`isAtLeast(VIEWER)` is true for everybody, so nothing in the product means "read-only"; every
issue mutation resolves through `WorkspaceAccessService.requireProjectMember`, which checks
**workspace** membership only, so **any workspace member can create, edit, transition, rank,
comment on and assign issues in every project of the workspace**. Exactly two project-level
boundaries actually exist (delete an issue → project `MANAGER`; curate components / versions /
sprints / project settings → project `MANAGER` **or** workspace `OWNER`/`ADMIN`). A team that
wants "contractors may comment but not edit", "only the release manager cuts versions", "only
leads manage sprints" or "this person must never be assigned work" cannot express any of it.

**Goal.** Replace the three ordinal role ladders with one **permission catalog** (29 action
strings), a small set of **built-in role templates** that cover the overwhelming majority of
installs, and **custom roles** as an escape hatch. Authorization resolves **once per request**
into the existing `WorkspaceContext` / `ProjectContext`, so every call site becomes a permission
check instead of a bespoke membership query.

**Success looks like:** (a) a team that never opens the Roles screen sees no change whatsoever,
before or after upgrade; (b) a team that opens it can express the four policies above in under a
minute without reading documentation; (c) there is exactly **one** authorization primitive in the
backend and exactly **one** source of truth in the SPA, so a UI gate can no longer drift from a
server predicate (the HD-98 / HD-116 bug class); (d) adding a permission is one enum constant plus
one call-site line, never a schema change.

---

## 2. What exists today (verified 2026-08-17 — the contract to reproduce)

### 2.1 The three enums

| Enum | Values | Comparison |
|---|---|---|
| `auth/entity/SystemRole` | `ADMIN`, `USER` | `isAtLeast` = `ordinal() <=`; guards `/api/admin/**` via one `hasRole("ADMIN")` line in `SecurityConfig` |
| `workspace/entity/WorkspaceRole` | `OWNER`, `ADMIN`, `MEMBER` | same |
| `project/entity/ProjectRole` | `MANAGER`, `MEMBER`, `VIEWER` | same |

The ordinal comparison encodes a **total order**. Custom roles have no total order, so every
`isAtLeast` call site must become a permission check. That conversion — not the new tables — is
the bulk of this epic.

### 2.2 What is actually enforced

| Surface | Today's real gate |
|---|---|
| Everything under `/api/workspaces/{ws}/**` | workspace membership (404 for non-member/non-existent) |
| Issue create / update / transition / assign / rank / comment / attach / sprint-assign | **workspace membership only** — `requireProjectMember` never reads `project_members` |
| `IssueService.delete` (`IssueService.java:712`) | project `MANAGER` (`project_members` row required) |
| Components, versions, sprint lifecycle, `PATCH /projects/{id}` | `ScopeResolver.requireProjectCurator` (`admin/scope/ScopeResolver.java:95`) = project `MANAGER` **or** workspace `OWNER`/`ADMIN` — **26 call sites** |
| `AttachmentService.delete` (`:178`) | uploader **or** project `MANAGER` |
| `CommentService.update` / `delete` (`:73`, `:94`) | **author only**, no override for anyone |
| Project archive / unarchive / add member / remove member | project `MANAGER` (`ProjectService.java:143,152,171,192`) |
| `ProjectService.listMembers` (`:161`) | `requireRole(…, VIEWER)` — **a gate everyone passes** |
| Workspace invite (`WorkspaceService.java:106,110`) | workspace `ADMIN`+; `OWNER` never grantable; nobody grants above their own role |
| Labels: archive/merge/delete (`LabelService.java:553`) | workspace `ADMIN`+ |
| Labels: rename/recolor (`:560`) | workspace `ADMIN`+ **or** the label's creator |
| Labels: create | any workspace member |
| Projects: create | any workspace member |
| Workspace-scoped admin (`/api/workspaces/{ws}/admin/**`) | `ScopeResolver.requireWorkspaceAdmin` — workspace `ADMIN`+ |
| Project-scoped admin (`/api/workspaces/{ws}/projects/{p}/admin/**`) | `ScopeResolver.requireProjectAdmin` — project `MANAGER`, and 404s a non-project-member |
| Saved filters | ownership-based (`owner_id`), plus a `shared` flag; no role involvement |
| HQL search scope (`SearchScope`) | every non-archived project of the workspace |

### 2.3 Gaps that this epic must fill because nothing else can

- **There is no endpoint to change a member's role** — workspace or project. Roles are set once
  (at invite / at add) and are immutable thereafter.
- **There is no workspace `PATCH`** at all.
- **The SPA has no project-members UI** and never calls `/projects/{id}/members`. Project
  membership is invisible to users today. This is the single most important fact for the
  migration: enabling strict project-role enforcement against today's `project_members` table
  would lock nearly every user out of nearly every project, because almost nobody has a row.
- The SPA re-derives the curator predicate by hand in ~15 places
  (`project.myRole === 'MANAGER' || workspace.myRole === 'OWNER' || workspace.myRole === 'ADMIN'`
  in `ProjectSettingsArea.tsx:76`, `components/sprints.tsx:247`, `NavRail.tsx:110`,
  `palette/commands.ts:256,271`, …). Every one of those is an independent opportunity to drift
  from the server.

---

## 3. Scope

### In scope

- The permission catalog (§6) — 29 action strings, workspace- and project-scoped.
- Built-in role templates (§7): 3 workspace, 4 project.
- **Custom roles** (workspace-owned, either scope), composed from the catalog.
- Storage (§8), single-resolution into `WorkspaceContext`/`ProjectContext` (§9).
- Migration of every `isAtLeast` / `requireRole` / `ScopeResolver` call site (§10).
- The **project default role** and the workspace **project-access mode** (§5.2) — the mechanism
  that makes the upgrade a no-op and tightening a deliberate action.
- Role-assignment endpoints that do not exist today: change a workspace member's role, change a
  project member's role, set a project's default role, patch the workspace's access mode.
- `myPermissions` on `WorkspaceResponse` / `ProjectResponse`, and the SPA rewrite that makes it
  the **only** source of UI gating.
- Workspace **People** + **Roles** screens; project **People** tab; the role editor.

### Out of scope / non-goals

- **Project visibility / private projects.** All projects stay visible to all workspace members;
  v1 answers *"what may you do"*, never *"what may you see"*. There is deliberately **no
  `issue.view` / `project.view` permission** in the v1 catalog — see §8.5 for the exact seam that
  makes adding it later a two-line change rather than a rewrite.
- **Issue-level and field-level security.** The ownership modifier (§6.2) is a qualifier on a
  *project* permission, not a per-issue grant, and does not open that door.
- **User groups as grantees.** §8.6 records the seam.
- **Removing a workspace member.** No such endpoint exists today; adding one is adjacent, not
  part of this epic. (The last-owner invariant in §11 still applies to *demotion*.)
- **Changing `SystemRole` or `/api/admin/**`.** The instance console is unchanged; instance
  admins keep acting through workspace membership on workspace-scoped resources.
- **Tier-gating custom roles** (they are Enterprise-only in Plane). Hamstrack has one codebase and
  no tiers — see §13.
- Approval workflows, permission-scheme reuse across workspaces, time-bounded grants, audit log
  of permission changes (§16 open question 7).

---

## 4. Actors & permissions (of this feature itself)

| Action | Required | Scope |
|---|---|---|
| Read the permission catalog (`GET /api/permissions`) | any authenticated user | global, static product metadata |
| List roles in a workspace | any **workspace member** | workspace |
| Create / edit / delete a custom role | `workspace.role.manage` | workspace |
| Assign a workspace role to a member | `workspace.member.manage` + the grant-ceiling rule (§11.2) | workspace |
| Assign a project role to a project member | `project.member.manage` | project |
| Set a project's default role | `project.member.manage` | project |
| Set the workspace's project-access mode / default project role | `workspace.edit` | workspace |
| Read own effective permissions (`myPermissions`) | any member — it rides responses they already fetch | both |

**Tenancy is unchanged and non-negotiable:** a missing workspace, a missing project and a
non-member all yield **404**. **403** is reserved for a member who lacks a permission — they
already know the resource exists. §12 enumerates every place the new model could accidentally
invert that.

---

## 5. The three rules that make everything else mechanical

### 5.1 Rule P1 — one resolution, one primitive

Authorization is resolved **once per request**, at the moment the workspace/project is resolved,
into an immutable `PermissionSet` carried by `WorkspaceContext` / `ProjectContext`. No service
may query `project_members` or `workspace_members` for authorization again. After this epic there
is exactly **one** authorization primitive (`WorkspaceAccessService`), and `ScopeResolver` is
absorbed into it (§10.4). A call site that wants to authorize writes one line:

```java
ctx.permissions().require(Permission.SPRINT_MANAGE);   // 403 MissingPermissionException
```

Corollary: authorization cost per request is **constant** — it no longer grows with the number of
things a handler checks.

### 5.2 Rule P2 — there is no "enforcement off" mode; there is a **default role**

The owner asked for a workspace switch that "turns real enforcement on", so nothing tightens
silently on upgrade. **I am implementing that intent, but not as an enforcement bypass** — see
§17.1 for why the framing changed. The mechanism is:

> **Effective project role of user *U* in project *P*:**
> 1. the role on *U*'s explicit `project_members` row, if one exists; else
> 2. *P*'s **default role** (`projects.default_project_role_id`, `NULL` → the workspace's
>    `default_project_role_id`, `NULL` → the built-in **Contributor**) — **but only while the
>    workspace's `project_access_mode` is `OPEN`**; else
> 3. **no project role** → the project-scoped permission set is empty (reads are unaffected —
>    they are not permission-gated in v1).
>
> Plus, unconditionally: a member whose **workspace** role grants `project.administer.all` holds
> every project permission in every project of that workspace (§17.2).

So permissions are **always** enforced, from day one, exactly as their names say. What the
workspace switch decides is only whether people who were never explicitly added to a project
inherit its default role. That answers the owner's question — *"a workspace with the flag off
still has roles assigned and a roles screen; what do those roles do while it is off?"* — cleanly:
**they do precisely what they say.** A project Viewer is read-only the instant they are given that
role, in either mode. `OPEN` mode does not weaken their role; it grants a role to people who have
none.

Consequences, all intended:

- The upgrade is a **provable no-op** (§8.4): the migration maps every existing membership row to
  a built-in role with today's exact abilities, and sets every workspace to `OPEN` with the
  default role **Contributor** — which is precisely "any workspace member may work in any
  project", i.e. today.
- Tightening is one switch, reversible, with no data migration.
- There is **no second code path** that could rot, no dead `if (enforcementEnabled)` branch, and
  no predicate that exists only in the "off" state. This is the DC/Cloud "never fork the logic"
  rule applied to time instead of deployment.
- Per-project strictness is expressible without the workspace switch at all: set that project's
  default role to **None**. That is also the exact seam private projects will use (§8.5).
  > **"None" is not a value — see [§20.3](#203-default-role--none-is-not-expressible-and-does-not-need-to-be-found-in-s7-hd-130).**
  > A NULL `default_project_role_id` means *inherit*. Read this bullet (and §8.5, and §15 AC 12)
  > as "set that project's default role to **Viewer**", the built-in role that grants ∅.

### 5.3 Rule P3 — hiding a control is never a permission, and a permission is never a capability

Three separate concepts, never conflated:

| Concept | Where it lives | May it change a status code? |
|---|---|---|
| **Delivery capability** (`board`/`releases`/`estimation`) | columns on `projects` | **Never** (`docs/design/delivery-paths-proposal.md` Rule A). A Kanban project still accepts `POST /sprints`. |
| **Permission** | this document | **Yes** — 403 is its whole purpose. |
| **UI gate** | the SPA | Never — it is presentation only; the API is the enforcement boundary. |

Therefore: `sprint.manage` is checked on `POST /sprints` **regardless of whether the project's
`board` capability is `SCRUM`**. A capability annotation on a permission (§6.1) is a *hint for the
role editor* ("this only matters for projects that plan in sprints"), never a check.

And the anti-drift rule the SPA must obey: **no component may read `myRole` for a decision.** The
only permitted input to a UI gate is `myPermissions`. `myRole` survives as a display string
(e.g. the People table). This is structural, not stylistic: `myRole === 'MANAGER'` cannot express
a custom role, so a component that reads it is *wrong by construction* the moment custom roles
exist, and that is the property that makes the drift impossible rather than merely discouraged.

---

## 6. The permission catalog

**29 permissions.** Format `area.action`, lowercase, dot-separated, ≤64 chars, stored as
`VARCHAR(64)` and validated by the Java enum `common.security.Permission` (per the project's
"VARCHAR not PG ENUM" rule). **The enum is the source of truth — there is no `permissions`
table**: a permission without a call site is meaningless, and call sites are code. Adding a
permission is one enum constant + one `require(...)` line + one row per role that should get it —
no migration.

`Cap` = the delivery capability this permission is *about* (a role-editor hint only — never a
check). `Own` = supports the ownership modifier (§6.2).

### 6.1 Workspace scope (9)

| Key | Cap | Own | Gates |
|---|---|---|---|
| `workspace.edit` | — | — | Rename/describe the workspace; set the project-access mode and the workspace default project role. |
| `workspace.member.manage` | — | — | Invite people, change a member's workspace role, revoke pending invites. |
| `workspace.role.manage` | — | — | Create/edit/delete **custom roles** in this workspace. Deliberately separate from the line above: defining what a role may do is strictly more dangerous than handing out an existing one. |
| `workspace.taxonomy.manage` | — | — | The workspace-scoped catalog & sets and the project-binding matrix (`/api/workspaces/{ws}/admin/**`). Today's `requireWorkspaceAdmin`. |
| `project.create` | — | — | Create a project in this workspace. Workspace-scoped despite the `project.` prefix — there is no project to scope it to yet. |
| `project.curate.all` | — | — | Hold `{project.edit, component.manage, version.manage, sprint.manage}` in **every** project of the workspace, without being a project member. This is today's implicit workspace-admin bypass — the 16 `requireProjectCurator` call sites — made explicit and grantable (§17.2). **Held by the built-in Owner and Admin.** |
| `project.administer.all` | — | — | Hold **every** project permission in **every** project of the workspace, without being a project member. The strict superset of `project.curate.all`, and **held by no built-in role** (§7.1): it is wider than anything that exists today, so seeding it would change what an Owner may do. It exists so a workspace can build a "Program manager" custom role (§17.2). |
| `label.create` | — | — | Create a workspace label. Everyday action; separate from curation on purpose. |
| `label.manage` | — | ✓ | Rename/recolor/describe/archive/merge/delete **any** label. `own` = only labels you created — which is exactly today's `LabelService.requireEditor` fallback. **The own/unrestricted split must land on the split the code already has:** `requireEditor` (rename/recolor/describe) becomes `require(label.manage, isOwn)`, `requireCurator` (archive/unarchive/merge/delete) becomes `require(label.manage)` with *no* ownership argument — which an own-only grant can never satisfy. One key, two arities, today's rule on the nose ("a member may rename the label they created, and may not archive it"); no catalog split needed. |

### 6.2 Project scope (20)

| Key | Cap | Own | Gates |
|---|---|---|---|
| `project.edit` | — | — | Rename/describe the project; change its delivery capabilities. |
| `project.archive` | — | — | Archive / unarchive the project. |
| `project.member.manage` | — | — | Add/remove project members, change their project role, set the project's default role. |
| `project.taxonomy.manage` | — | — | Project-private catalog & sets and this project's bindings (`/api/workspaces/{ws}/projects/{p}/admin/**`). |
| `issue.create` | — | — | File a new issue. |
| `issue.edit` | — | ✓ | Change any issue field that is not covered by a more specific permission below: title, description, type, priority, labels, component, versions, parent, due date, story points, custom fields. `own` = issues you reported. |
| `issue.transition` | — | — | Change an issue's status (board drag, status picker). The single most-requested split. |
| `issue.assign` | — | — | Set or clear an issue's assignee (including self-assign). |
| `issue.assignable` | — | — | **May be chosen as an assignee.** The only permission that is about *being a subject* rather than acting; see §6.3. |
| `issue.rank` | — | — | Reorder the backlog/board rank (`issues.position`). Ranking is a planning act, and "developers must not reprioritise the backlog" is a real policy. |
| `issue.delete` | — | ✓ | Delete an issue. `own` = issues you reported (a common ask: clean up your own mis-filed issue). |
| `comment.create` | — | — | Post a comment. |
| `comment.edit` | — | **own only** | Edit a comment. **Not grantable unrestricted in v1** — putting words in someone else's mouth is not a permission we ship (§16 OQ 3). |
| `comment.delete` | — | ✓ | Delete a comment. `own` = your own; unrestricted = moderation (new — today nobody can). |
| `attachment.create` | — | — | Upload a file to an issue. |
| `attachment.delete` | — | ✓ | Delete an attachment. `own` = files you uploaded — exactly today's rule; unrestricted ≈ today's project `MANAGER`. |
| `sprint.manage` | `board` | — | Create / rename / start / complete / delete sprints. |
| `sprint.assign` | `board` | — | Put issues into / take issues out of a sprint. **Separate from `sprint.manage` because today they differ** (lifecycle is curator-gated, assignment is open to any member) and reproducing today's behaviour requires the split. Governs **both doors** — see §6.4. |
| `version.manage` | `releases` | — | Create / edit / release / unrelease / archive / delete versions. |
| `component.manage` | — | — | Create / edit / archive / delete components. |

### 6.3 Why 29, and what was rejected

The count is defended one entry at a time against *"could a built-in role cover this instead?"*.
Jira's ~43 project permissions are the anti-benchmark: most of them exist because Jira has
features we don't (voters, watchers, worklogs, issue-level security, bulk change, archiving),
and the rest are splits nobody uses. Every entry above is either (a) a boundary that exists
**today** and must be reproduced, or (b) one of the owner's motivating examples.

**Deliberately rejected:**

| Rejected | Why |
|---|---|
| `issue.view`, `project.view` | Visibility is an explicit non-goal (§3). Shipping a permission every role must have is dead code that lies. §8.5 shows why adding it later is trivial. |
| `estimate.set` (story points) | Covered by `issue.edit`. No competitor separates it, and "who estimates" is a team norm, not an access boundary. |
| `filter.share` | A Viewer publishing a saved filter harms nobody; saved filters are already ownership-scoped. |
| `issue.link` | No issue-links feature exists. Add the permission with the feature. |
| `comment.create.internal` / private comments | No such feature. |
| `workspace.delete` | No such endpoint. |
| `attachment.download`, `comment.read`, `history.read` | All read surfaces — non-goal. |
| Separate `issue.edit.description` / `.labels` / … | Field-level security, explicit non-goal. |
| A `board.configure` permission | Board columns come from the workflow → `project.taxonomy.manage` already covers it. |
| `sprint.start` / `sprint.complete` split from `sprint.manage` | Nobody asked; a custom role can't usefully separate "may create a sprint" from "may start it". |

**On `issue.assign` vs `issue.assignable`** — Jira separates "Assign issues" from "Assignable
user", and it is right to. Without `assignable` you cannot express "contractors comment but are
never given work", and, more mundanely, you cannot keep read-only accounts out of the assignee
picker. Cost: one enum constant and one check inside `IssueService.resolveAssignee`. **Both are
in.** Note the asymmetry to preserve: `issue.assign` is checked against the *actor*,
`issue.assignable` against the *target*.

### 6.4 The ownership modifier

A grant is `(role, permission, own_only)`. `own_only = true` narrows the permission to objects the
actor owns:

| Permission | "own" means |
|---|---|
| `issue.edit`, `issue.delete` | the actor is the issue's **reporter** (§16 OQ 2: not the assignee, in v1) |
| `comment.edit`, `comment.delete` | the actor is the comment's author |
| `attachment.delete` | the actor uploaded it |
| `label.manage` | the actor created the label |

Resolution produces a flat `Set<String>`: an unrestricted grant contributes `"issue.edit"`, an
own-only grant contributes `"issue.edit:own"`. Server API:
`perms.has(ISSUE_EDIT)` / `perms.has(ISSUE_EDIT, isOwn)` / `perms.require(ISSUE_EDIT, isOwn)`.
Client: `can('issue.edit')` and `canOwn('issue.edit', issue.reporterId === me.id)`.

Chosen over a `Map<Permission, Qualifier>` because a flat string set serialises to the SPA with
zero ceremony and needs no client-side qualifier logic; chosen over minting `issue.edit.own` as a
*separate catalog entry* because that would inflate the catalog by 6 for no expressive gain and
would let a role hold `.any` without `.own` (nonsense).

### 6.5 The double-door rule (a trap worth naming)

A permission is only real if **every** endpoint that can produce the effect checks it. Two live
cases:

1. **`sprint.assign`** must be checked by `POST /sprints/{id}/issues`, `DELETE
   /sprints/{id}/issues/{issueId}` **and** by `PATCH /issues/{n}` when the body carries `sprintId`.
   Otherwise the permission is bypassable with a one-line curl.
2. **`component.manage` / `version.manage` govern the catalog object's lifecycle, not its
   assignment to an issue.** Setting `componentId` / `fixVersionIds` on an issue is `issue.edit`.
   This asymmetry with sprints is deliberate: sprint membership has its own endpoints and its own
   planning semantics; component/version assignment does not.

Any future permission must state which doors it guards, and the acceptance criteria (§15) require
a test per door.

---

## 7. Built-in role templates

Built-ins are seeded rows with `workspace_id IS NULL` and `built_in = true`, shared by every
workspace, **not editable** (§11.4 — "Duplicate to customise" is the front door). Their **keys**
are deliberately the existing enum names so `myRole` stays wire-compatible; their **display
names** are the new vocabulary.

### 7.1 Workspace roles (3 — unchanged from today, by design)

| Key | Display | Permissions |
|---|---|---|
| `OWNER` | Owner | 8 of the 9 workspace permissions — everything **except `project.administer.all`** |
| `ADMIN` | Admin | the same 8 |
| `MEMBER` | Member | `project.create`, `label.create`, `label.manage:own` |

Owner and Admin hold **identical permission sets**, exactly as today (`isAtLeast(ADMIN)` is the
only test anywhere). Owner is a **guardrail role, not a bigger one**: `OWNER` is never grantable
via invite, nobody may grant a role above their own, and a workspace must always retain at least
one Owner (§11.1). Encoding that difference as permissions would be dishonest — it is a
*constraint on assignment*, not a capability. Said plainly in the UI: *"Owners and Admins can do
the same things; a workspace must always keep one Owner."*

**Owner/Admin get `project.curate.all`, not `project.administer.all`** — the difference is the
whole no-op promise. The workspace-admin bypass that exists today lives *only* inside
`ScopeResolver.requireProjectCurator` (project settings, components, versions, sprints — 16 call
sites). It does **not** grant `project.archive`, `project.member.manage`,
`project.taxonomy.manage`, `issue.delete` or unrestricted `attachment.delete`: those are
project-`MANAGER`-only, and a workspace Owner with no `project_members` row is refused all five
right now. Seeding the wide permission would have opened those five gates for every Owner and
Admin in every install — the "mapped a notch too loose" failure §18 calls the worse of the two.
`project.administer.all` stays in the catalog, grantable to a custom role, seeded on nothing.
*(Caught by `PermissionParityTest`, which is exactly what it is for.)*

**Member holds `label.manage:own`** for the same reason in the opposite direction.
`LabelService.requireEditor` returns early for the label's **creator**, before the workspace role
is consulted, so a plain member can already rename/recolor/describe a label they created. Omitting
the row would have taken that ability away on upgrade — a silent narrowing. Own-only is also the
right *width*: the archive/merge/delete sites (`requireCurator`) ask for the unrestricted grant,
which an own-only grant can never satisfy (§6.1).

No **Guest** tier: an outside-collaborator role only means something once projects can be hidden,
which is a non-goal. Adding it later is one seeded row.

### 7.2 Project roles (4)

| Key | Display | Permissions |
|---|---|---|
| `MANAGER` | Project admin | all 20 project permissions; `comment.delete` and `attachment.delete` unrestricted, `comment.edit` own-only, `issue.edit`/`issue.delete` unrestricted |
| `MEMBER` | Contributor | `issue.create`, `issue.edit`, `issue.transition`, `issue.assign`, `issue.assignable`, `issue.rank`, `comment.create`, `comment.edit:own`, `comment.delete:own`, `attachment.create`, `attachment.delete:own`, `sprint.assign` |
| `COMMENTER` | Commenter | `comment.create`, `comment.edit:own`, `comment.delete:own`, `attachment.create`, `attachment.delete:own`, `issue.assignable` |
| `VIEWER` | Viewer | **∅** (reads only — and in v1 reads are open to all workspace members anyway) |

**Contributor is the load-bearing row**: its permission set is, verbatim, everything a workspace
member can do in a project today. That is what makes the upgrade a no-op (§8.4).

**Justification against the market.** Asana, GitHub and Plane have converged on
admin / editor / commenter / viewer, and Linear ships nothing but three workspace roles and is
widely liked. Our ladder is that convergence with our own vocabulary. We deliberately do **not**
ship a fifth "Lead / Release manager" built-in that curates sprints, versions and components
without touching members or settings — even though it is the most obvious split — because the
thesis is *built-ins cover ~95%, customisation is the escape hatch, not the front door*. It ships
instead as the **documented first recipe** in the Roles screen's empty state: *"Duplicate
Contributor, add Manage sprints + Manage versions + Manage components → 'Team lead'."*

⚠ **`VIEWER` changes meaning.** Today it is the everybody-fallback and grants everything; after
this epic it grants nothing. That is why the migration remaps every existing explicit `VIEWER` row
to `MEMBER` (§8.4) — otherwise the one group of users who *were* marked read-only would be the
only ones whose permissions changed on upgrade.

---

## 8. Data model

Flyway only, `ddl-auto=validate`, UUID v7 via `@UuidGenerator(TIME)`, `@CreatedDate` /
`@LastModifiedDate` (never `@CreationTimestamp`), `VARCHAR` never `CHAR`/PG-ENUM. Migration count
is not a constraint — three files, each with one job.

### 8.1 New tables

```sql
-- V13__roles.sql
CREATE TABLE roles (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE, -- NULL = built-in template
    scope        VARCHAR(20)  NOT NULL,          -- WORKSPACE | PROJECT
    key          VARCHAR(40)  NOT NULL,          -- OWNER/ADMIN/MEMBER | MANAGER/MEMBER/COMMENTER/VIEWER | custom slug
    name         VARCHAR(80)  NOT NULL,          -- display name
    description  VARCHAR(500),
    built_in     BOOLEAN      NOT NULL DEFAULT FALSE,
    position     SMALLINT     NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,   -- @Version, optimistic locking
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT roles_builtin_ck  CHECK (built_in = (workspace_id IS NULL)),
    CONSTRAINT roles_scope_key_uk UNIQUE NULLS NOT DISTINCT (workspace_id, scope, key)
);
CREATE TRIGGER trg_roles_updated_at BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_roles_workspace ON roles(workspace_id) WHERE workspace_id IS NOT NULL;

CREATE TABLE role_permissions (
    role_id    UUID        NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission VARCHAR(64) NOT NULL,     -- validated by the Java enum, never a PG enum
    own_only   BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (role_id, permission)
);
```

`UNIQUE NULLS NOT DISTINCT` matches the idiom already used for the scoped taxonomy in `V1`.
Built-in roles get **fixed literal UUIDs** in the migration and are looked up at runtime by
`(scope, key)` through a cached repository method.

`roles` is a `BaseEntity` (`id`/`createdAt`/`updatedAt`) with `@Version`. `role_permissions` is
mapped as an `@ElementCollection` on `Role` (a value collection, no id of its own). The editor
replaces the set wholesale with **`permissions.clear()` + `addAll(...)` in one transaction** — and
that is all it needs. The CLAUDE.md "replace children wholesale" trap (`deleteAllBy…` + `flush()`
before re-inserting) **does not apply here**: that is about a child *`@Entity`* with its own
repository, where one flush orders the INSERTs before the DELETEs and a re-inserted unique key
collides. An element collection has neither a child entity nor a repository to flush, and
Hibernate's `ActionQueue` runs collection removals before collection creations. Following the
entity recipe here would mean inventing an `@Entity` and a repository *in order to* work around a
problem this mapping does not have.

Note one sharp edge of the mapping instead: `RolePermission`'s equality is on `permission` alone
(mirroring the PK), so `add()`ing a permission the role already holds is a **silent no-op** — a
"toggle own-only" written with `add()` will not persist. Mutate the existing element or replace
the collection.

### 8.2 Changed tables

```sql
-- V14__role_assignments.sql  (additive + backfill; legacy columns still present)
ALTER TABLE workspace_members  ADD COLUMN role_id UUID REFERENCES roles(id);
ALTER TABLE project_members    ADD COLUMN role_id UUID REFERENCES roles(id);
ALTER TABLE workspace_invites  ADD COLUMN role_id UUID REFERENCES roles(id);
ALTER TABLE workspaces ADD COLUMN default_project_role_id UUID REFERENCES roles(id);   -- NULL = built-in Contributor
ALTER TABLE workspaces ADD COLUMN project_access_mode VARCHAR(20) NOT NULL DEFAULT 'OPEN';
ALTER TABLE projects   ADD COLUMN default_project_role_id UUID REFERENCES roles(id);   -- NULL = the workspace's
-- ... backfill (see §8.4) ...

-- V15__drop_legacy_role_columns.sql
ALTER TABLE workspace_members ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE project_members   ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE workspace_invites ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE workspace_members DROP COLUMN role;
ALTER TABLE project_members   DROP COLUMN role;
ALTER TABLE workspace_invites DROP COLUMN role;
```

`ON DELETE` for role references is **NO ACTION** (the PostgreSQL default): deleting a role that is
in use is a 409 handled in the service (§11.4); the FK is the backstop, not the UX. NO ACTION, not
RESTRICT — the check is deferred to the end of the statement, so a future `DELETE FROM workspaces`
(which cascades to `roles` *and* to `workspace_members` at once) succeeds, where a literal RESTRICT
would abort it even though nothing is left pointing at the role.

The `WorkspaceRole` / `ProjectRole` Java enums are **deleted** at the end of slice S3. `SystemRole`
is untouched.

### 8.3 Entities

- `Role` (`workspace` package — it is workspace-owned data) with `@Version`, `@CreatedDate`,
  `@LastModifiedDate`, `scope` as `@Enumerated(STRING) @Column(length = 20)`, and
  `@ElementCollection Set<RolePermission> permissions`.
- `WorkspaceMember.role` / `ProjectMember.role` / `WorkspaceInvite.role` become
  `@ManyToOne(fetch = LAZY) Role`.
- `Workspace` gains `projectAccessMode` (`@Enumerated(STRING)`, `VARCHAR(20)`) and
  `defaultProjectRole` (`@ManyToOne`, nullable).
- `Project` gains `defaultProjectRole` (`@ManyToOne`, nullable).

### 8.4 Upgrade migration — the no-op proof

The whole point of the `V14` backfill is that **no user's effective abilities change**.

| Existing state | Becomes | Ability delta |
|---|---|---|
| `workspace_members.role = 'OWNER' / 'ADMIN' / 'MEMBER'` | the built-in workspace role of the same key | none — the built-ins' permission sets are §2.2 transcribed |
| `workspace_invites.role` | same mapping | none |
| `project_members.role = 'MANAGER'` | built-in `MANAGER` (Project admin) | none |
| `project_members.role = 'MEMBER'` | built-in `MEMBER` (Contributor) | none |
| `project_members.role = 'VIEWER'` | built-in **`MEMBER`** (Contributor) | none — a `VIEWER` row grants everything today (§2.2) |
| every workspace | `project_access_mode = 'OPEN'`, `default_project_role_id = NULL` (→ Contributor) | none — a workspace member with no project row keeps exactly today's abilities |
| every project | `default_project_role_id = NULL` (→ the workspace's) | none |

Nothing is inserted into `project_members`. Deliberate: backfilling a row per
(workspace member × project) would convert an implicit rule into ~N×M rows, destroy the
distinction between "explicitly added" and "backfilled", and make private projects harder later.
The default role expresses the same thing with one column.

**Rollback:** these migrations are additive-then-destructive across two files; if `V15` is not yet
applied, the legacy columns are still authoritative-compatible. Once `V15` runs, rollback is a
restore. The owner runs a single prod instance and squashes migrations, so this is acceptable —
but the release checklist must snapshot the DB before `V15`.

### 8.5 The visibility seam (why v1 is not a rewrite later)

Adding private projects later is:
1. two new catalog entries (`project.view`, `issue.view`) — enum constants, no migration;
2. those permissions added to the built-in roles' seed sets — data;
3. a check in **exactly two places**: `WorkspaceAccessService.resolveProject` (404, never 403, when
   the caller lacks `project.view`) and `SearchScope.visibleProjectIds` / `scopePredicate`, which
   already carries a comment reserving that role;
4. "private project" = its `default_project_role_id` is **None**, a value the model already
   supports (§5.2) — no new column.

The storage model therefore does not need to change at all. That is the test the owner asked for.

### 8.6 The groups seam

Groups are **not** a polymorphic column added speculatively. The seam is that role resolution is a
**single function** (§9). Group support later adds one additive table —
`project_role_grants(project_id, grantee_type VARCHAR(20), grantee_id UUID, role_id UUID)` and its
workspace twin — and one extra branch in that function (union the grants of the groups the actor
belongs to). Membership tables stay as they are; groups do not replace membership, they add
grants. No existing row changes shape.

---

## 9. Resolution & performance

### 9.1 Where it resolves

`WorkspaceAccessService` is the only resolver. Contexts grow one field:

```java
public record WorkspaceContext(Workspace workspace, WorkspaceMember membership,
                               PermissionSet permissions) { ... }
public record ProjectContext(Workspace workspace, WorkspaceMember membership, Project project,
                             PermissionSet permissions) { ... }   // project-scoped set
public record IssueContext(...)  // carries the project's PermissionSet unchanged
```

`PermissionSet` is an immutable value (`Set<String>` inside) with
`has(Permission)`, `has(Permission, boolean isOwn)`, `require(Permission[, isOwn])` and
`asWireStrings()`. `require` throws `MissingPermissionException extends AppException(FORBIDDEN)`
whose detail names the permission (`"Requires permission: sprint.manage"`) — which is also the
debugging tool that kills UI/server drift: a hidden-but-clicked control now reports exactly which
predicate disagreed.

Project set = `permissionsOf(effectiveProjectRole)` ∪ (all project permissions **iff** the
workspace set contains `project.administer.all`).

**Rename:** `requireProjectMember` → `resolveProject`. It never checked project membership; keeping
a name that lies has already cost one documented gotcha. `requireMember` → `resolveWorkspace`
optional; recommended for symmetry but not required.

### 9.2 Query cost, before → after

| Request | Today | After |
|---|---|---|
| Workspace-scoped (e.g. `GET /workspaces/{ws}/labels`) | 2 (workspace, membership) | 2 + **0** (role→permissions is `@Cacheable`, keyed by role id) |
| Project-scoped read (`GET /issues`) | 3 (ws, membership, project) | 4 (+1 `project_members` lookup, indexed unique key) |
| `IssueService.delete` | 4 (3 + project member) | 4 |
| `requireProjectCurator` path (26 call sites) | 4 | 4 |
| `ProjectService.update` | 5 (curator + `getRole` for the response) | 4 |
| `ProjectService.list` (N projects) | 4 (already batched via `findAllByUserAndProjectIn`) | 4 — the batch stays, and `myPermissions` per row comes from the cached role map |

Net: **+1 query on the plainest project read, −1 on the paths that today check a role twice, and
constant thereafter** — a handler that checks six permissions costs the same as one that checks
none. Today's `projectMemberRepository.findByProjectAndUser` pattern (currently in
`ProjectService.getRole`, `IssueService.requireProjectRole`, `AttachmentService.hasProjectRole`,
`ScopeResolver` ×2) disappears entirely.

### 9.3 Caching

- `RolePermissionCache` — a **separate bean** (`@Cacheable` is proxy-applied, so a self-invocation
  from the resolver would bypass it — the exact reason `ProjectConfigCache` is separate), keyed by
  role id, returning an immutable `PermissionSet`. TTL from the existing `CacheConfig`.
- Evicted on role edit/delete (`@CacheEvict` by role id) — the only mutation that changes a set.
- **Membership is never cached** — a role reassignment must take effect on the member's next
  request, and membership rows are hot in PG's buffer cache anyway.
- **Permissions are never put in the JWT.** They would go stale and the JWT is not revocable; a
  demotion must bite immediately. Non-negotiable.
- Within a request the set is resolved once and passed down; a role change committed mid-request
  is not observed by that request (snapshot semantics — documented, acceptable).

---

## 10. Call-site migration (the bulk of the work)

~30 authorization sites. Grouped by the permission they become. **Every row is behaviour-preserving
under the §8.4 migration unless the Δ column says otherwise.**

This is not an assertion, it is a **test**: `PermissionParityTest` (S1) evaluates every
(call site × actor archetype) cell against both the legacy predicate and its replacement and fails
the build on any disagreement it does not declare. As of the S1 corrections the table declares
**exactly one** divergence — `CommentService.delete` for a project admin (§10.3.5, intended). Two
others were declared and are now closed by the §7.1 seed corrections: the built-in Member's missing
`label.manage:own`, and Owner/Admin holding `project.administer.all` instead of
`project.curate.all`.

### 10.1 Workspace scope

| Call site | Today | Becomes | Δ |
|---|---|---|---|
| `WorkspaceService.inviteMember:106` | `isAtLeast(ADMIN)` | `workspace.member.manage` | — |
| `WorkspaceService.inviteMember:110` | `role != OWNER && isAtLeast(req.role())` | the **grant-ceiling rule** (§11.2), which is not a permission | — |
| *(new)* `PATCH /workspaces/{ws}/members/{userId}` | does not exist | `workspace.member.manage` + ceiling + last-owner guard | **new** |
| *(new)* `PATCH /workspaces/{ws}` | does not exist | `workspace.edit` | **new** |
| `ProjectService.create:39` | any member | `project.create` | — (Member has it) |
| `LabelService.create:130` | any member | `label.create` | — |
| `LabelService.requireCurator:553` (archive/unarchive/merge/delete) | `isAtLeast(ADMIN)` | `require(label.manage)` — **no ownership argument**, so Member's own-only grant does not satisfy it | — |
| `LabelService.requireEditor:560` (rename/recolor/describe) | ADMIN **or** creator | `require(label.manage, isOwn)` | — (Member holds `label.manage:own`, §7.1) |
| `ScopeResolver.requireWorkspaceAdmin:41` (`ScopedProjectAdminService` ×3, `WorkspaceAdminController` ×1) | `isAtLeast(ADMIN)` | `workspace.taxonomy.manage` | — |
| `SavedFilterService` (all) | ownership | **unchanged** | — |
| `SearchService` / `SearchScope` | membership | **unchanged** | — |
| `SseController`, `NotificationController` | membership | **unchanged** | — |

### 10.2 Project scope

| Call site | Today | Becomes | Δ |
|---|---|---|---|
| `ProjectService.update:127` | `requireProjectCurator` | `project.edit` | — |
| `ProjectService.archive:143` / `unarchive:152` | `requireRole(MANAGER)` | `project.archive` | — |
| `ProjectService.addMember:171` / `removeMember:192` | `requireRole(MANAGER)` | `project.member.manage` | — |
| *(new)* `PATCH .../projects/{p}/members/{userId}` | does not exist | `project.member.manage` | **new** |
| `ProjectService.listMembers:161` | `requireRole(VIEWER)` — a no-op | **no gate** (any workspace member) | ⚠ §10.3 |
| `ScopeResolver.requireProjectAdmin:57` (`ScopedProjectAdminService` ×3, `ProjectAdminController` ×1) | project `MANAGER`, 404s non-members | `project.taxonomy.manage` | ⚠ §10.3 |
| `ComponentService` create/update/archive/unarchive/delete (`:122,:168,:240,:266`) | `requireProjectCurator` | `component.manage` | — |
| `VersionService` create/update/release/unrelease/archive/delete (`:132,:184,:251,:305,:334,:364`) | `requireProjectCurator` | `version.manage` | — |
| `SprintService` create/update/start/complete/delete (`:188,:246,:310,:370,:451`) | `requireProjectCurator` | `sprint.manage` | — |
| `SprintService.addIssues:510` / `removeIssue:594` | membership only | `sprint.assign` | — |
| `IssueService.create:149` | membership only | `issue.create` | — |
| `IssueService.update:331` | membership only | **per-field**: `issue.transition` if `statusId` present & changing; `issue.assign` if assignee changing; `sprint.assign` if `sprintId` present & changing; `issue.edit` for everything else | ⚠ §10.3 |
| `IssueService.rank:636` | membership only | `issue.rank` | — |
| `IssueService.delete:712` | `requireProjectRole(MANAGER)` | `issue.delete` | — |
| `IssueService.resolveAssignee:953` | workspace member | workspace member **and** target holds `issue.assignable` in this project | ⚠ §10.3 |
| `CommentService.create:45` | membership only | `comment.create` | — |
| `CommentService.update:73` | author only | `comment.edit` (own-only in v1) | — |
| `CommentService.delete:94` | author only | `comment.delete` (own or any) | widening for Project admin |
| `AttachmentService.upload:73` | membership only | `attachment.create` | — |
| `AttachmentService.delete:178` | uploader or MANAGER | `attachment.delete` (own or any) | — |
| All read paths (`listCapped`, `listPaged`, `get`, `children`, `getHistory`, `BacklogService.view`, component/version/sprint `list`/`get`/`usage`) | membership | **unchanged** | — |

⚠ **Five of those "—"s depend on the §7.1 seed, not on the mapping.** `project.archive`,
`project.member.manage`, `project.taxonomy.manage`, `issue.delete` and unrestricted
`attachment.delete` are project-`MANAGER`-only today, and a workspace Owner/Admin with no
`project_members` row is refused every one of them. They stay Δ-free **only** because the built-in
Owner and Admin hold `project.curate.all` (the four `requireProjectCurator` permissions) rather than
`project.administer.all` (all twenty). Swapping the seed re-opens all five silently — no call site
changes, no test outside `PermissionParityTest` notices.

### 10.3 Sites where the current behaviour is ambiguous — decide before building

1. **`ProjectService.listMembers`** currently calls `requireRole(VIEWER)`, which passes for
   everyone. Is that intent or accident? **Recommendation: no gate** — any workspace member may
   list a project's members. The assignee picker, mention autocomplete and the People tab all need
   it, and the workspace member list is already open to any member.
2. **`ScopeResolver.requireProjectAdmin` 404s a caller who is a workspace member but not a project
   member**, while `requireProjectCurator` 403s them. Two different answers for the same shape of
   failure. **Recommendation: 403 everywhere** — the caller demonstrably knows the project exists
   (they can list it). Keeping the 404 would be the only place in the codebase where a permission
   failure masquerades as non-existence, and after this epic it cannot be expressed anyway.
   *This is a visible behaviour change for one endpoint family and needs the owner's nod.*
3. **`PATCH /issues/{n}` becomes multi-permission.** A single request can require `issue.edit`,
   `issue.transition`, `issue.assign` and `sprint.assign` at once. Rules: check **only** the
   permissions for fields actually present *and actually changing* (a no-op field must not 403 —
   the SPA sends whole-form patches); on failure, 403 naming the **first** missing permission;
   check all permissions **before** applying any mutation (also required by the `@Version`
   double-bump rule — reads first, mutations last).
4. **`resolveAssignee` when the target lacks `issue.assignable`.** Recommendation: **422**, not
   403 — the caller is entitled to make the request, the *value* is invalid. Distinct message
   ("That user cannot be assigned in this project") separate from today's "Unknown assignee" so a
   member is never told a colleague does not exist. Precedent: `ComponentService.requireAssignable`
   422s an archived component the same way.
5. **`comment.delete` unrestricted is new.** Nobody can delete another's comment today. Granting
   it to Project admin is a widening. Recommendation: ship it (moderation is the #1 request for
   comment permissions) and note it in the release notes.
6. **Order of `requireNotArchived` vs the permission check.** Today `IssueService.delete` checks
   archived first (`:711`) then role (`:712`). Recommendation: **permission first, state second**,
   uniformly — so a 403 never depends on project state and the error a caller sees is stable.

### 10.4 `ScopeResolver` — absorbed, not coexisting

`admin/scope/ScopeResolver` is **deleted** at the end of S3. Its three methods map to
`workspace.taxonomy.manage`, `project.taxonomy.manage` and the **curator set**
`{project.edit, component.manage, version.manage, sprint.manage}` — which is precisely what
`project.curate.all` grants across a whole workspace (§6.1, `Permission.projectCuration()`);
its 403-on-insufficient-role
semantics move into `PermissionSet.require`, and its 404-on-non-member half is already
`WorkspaceAccessService`'s. Keeping two authorization services after this epic would recreate the
exact condition (two predicates for one question) that HD-123 exists to remove. `ScopeContext`
(the *catalog scope* stamping used by the delegated-admin services) is a different concept and is
**untouched**.

---

## 11. Behaviour, rules & edge cases

### 11.1 Last Owner

A workspace must always have ≥1 member holding the built-in `OWNER` role. Demoting or removing the
last Owner → **409** (`"A workspace must keep at least one Owner"`). Because `OWNER` is built-in
and not editable, this single invariant also guarantees the escalation path can never be
permission-edited away — no separate "someone must still hold `workspace.role.manage`" check is
needed.

### 11.2 Grant ceiling

> **Incomplete as written — see [§20.1](#201-112-states-the-grant-ceiling-without-the-owner-exemption-found-in-s4-hd-127).**
> The built-in workspace **Owner is exempt** in their own workspace (an `ADMIN` is not), the
> *definition* ceiling applies to WORKSPACE-scoped roles only, and the ceiling bounds both ends of
> a write. The text below is kept as written.

Preserved from today's invite rule and extended to role changes: **nobody may grant a role that
holds a permission they do not themselves hold**, and `OWNER` is never grantable by an Admin
(only by an Owner). Prevents self-escalation via a custom role. Violation → **403**.

### 11.3 Assigning a role weaker than the default

In `OPEN` mode, adding a person to a project as **Viewer** *reduces* their abilities (from the
default Contributor to nothing). Explicit membership always wins — max(explicit, default) would
make Viewer meaningless. The UI must warn inline: *"Viewer grants less than this project's default
(Contributor). Everyone in the workspace already has Contributor here."* Redmine/OpenProject's
Non-member pseudo-role behaves the same way.

### 11.4 Role lifecycle

| Situation | Behaviour |
|---|---|
| Edit a role in use | Takes effect immediately for every holder. The editor shows an impact line — *"Used by N members across M projects"* — reusing the existing `ImpactBanner` idiom. |
| Delete a role in use | **409** with the usage payload, unless `?reassignToRoleId=` is supplied — mirroring the taxonomy delete-with-remap idiom the admin console already uses. The reassign target must be the same scope and the same workspace (or built-in). |
| Delete a built-in role | **409** always. |
| Edit a built-in role | **409** always — `POST /roles/{id}/duplicate` is the front door. |
| Custom role granting nothing | **Allowed.** Viewer is exactly that, and "read-only" must be expressible. |
| Two admins editing one role concurrently | `@Version` on `roles` → **409**. Permission rows are replaced wholesale with `clear()` + `addAll(...)` in one transaction — *not* the `deleteAllBy…` + `flush()` recipe, which is for child entities (§8.1). |
| Removing your own permissions | Allowed (an Admin may demote themselves), **except** where §11.1 or §11.2 blocks it. The UI confirms: *"This removes your own access to X."* |
| A role referenced by `workspaces.default_project_role_id` / `projects.default_project_role_id` | Counts as "in use" for the delete guard. |
| Custom role from another workspace supplied by id | **422 "Unknown role"** — never 404-with-detail, never accepted. See §12. |

### 11.5 Project & workspace state

| Situation | Behaviour |
|---|---|
| Archived project | Writes stay **409** (unchanged); the permission check runs **first** (§10.3.6). Role assignment on an archived project is allowed (it is not project content). |
| Project deleted | `project_members` cascades; roles are untouched. |
| Workspace deleted | `roles.workspace_id` cascades — custom roles die with the workspace. |
| Flipping `project_access_mode` OPEN → STRICT | Writes narrow immediately for anyone with no explicit project membership. **No data is changed, nothing is unassigned, no read is lost.** The UI must show the impact before confirming (§14.3). |
| Flipping STRICT → OPEN | Immediately restores the default role. Fully reversible — that is the point. |
| Demo seeding (`DemoDataService`) | Unaffected: it creates the workspace (seeder becomes `OWNER`) then the project (becomes `MANAGER`) through the normal services, so every check passes. The new columns default to NULL → built-ins. **No permission bypass may be added for seeding** — if the seeder ever needs one, the model is wrong. |
| Existing assignments when `issue.assignable` is revoked | **Nothing is unassigned.** The stored assignee still renders everywhere. Only *new* assignments are validated; re-saving an issue whose assignee is unchanged must not 422. Same non-destructive rule as an archived component (`ComponentService.requireAssignable`) and the same spirit as delivery Rule B (values persist, controls disappear). |
| A member is removed from a project while holding assigned issues | Unchanged from today — issues keep their assignee. |
| Idempotency / races | Assigning the role a member already has → 200 no-op. Two concurrent role assignments → last writer wins on `project_members` (no `@Version` there today; not worth adding). Creating two custom roles with the same name → the `(workspace, scope, key)` unique constraint gives a clean **409**. |

---

## 12. Tenancy (the project's top bug class)

The rule is unchanged and this epic must not bend it: **non-membership and non-existence both
404; a member lacking a permission gets 403.**

New surfaces and their verdicts:

| Situation | Correct response | Why |
|---|---|---|
| `GET /workspaces/{ws}/roles`, caller is not a member | **404** | via `resolveWorkspace`; role *names* are tenant data |
| Same call, member without `workspace.role.manage` | **200** | listing roles is a read every member needs (the People tab shows role names) |
| `POST/PATCH/DELETE /workspaces/{ws}/roles/**`, member without the permission | **403** | they know the workspace exists |
| A **role id belonging to another workspace** — or to the wrong **scope** — passed to any assign/create call | **422 "Unknown role"** | never 404-with-detail (leaks nothing), never accepted. **This is the one genuinely new cross-tenant vector in this epic**: `roles.id` is a UUID resolvable from a workspace-scoped path, so every read of a role by id must go through `findAssignable(id, workspaceId, scope)`, never a bare `findById` (which `RoleRepository` no longer inherits). Same class as the `findByIdAndWorkspace` rule for labels/components/versions. |
| A **permission key** this build does not know, passed to the role editor | **422 "Unknown permission"** | write path only — the read path logs and drops instead, see below |
| A role id from another workspace written into `workspace_members.role_id` | **Impossible by construction** — the FK cannot express it, so the service invariant *"a member's role must be built-in or belong to that member's workspace"* is enforced in `RoleService.resolveAssignable(workspace, roleId)`, the single entry point, and asserted by a test |
| `PATCH .../projects/{p}/members/{u}`, caller is not a workspace member | **404** | project resolution first |
| Same, caller is a member without `project.member.manage` | **403** | |
| `myPermissions` on a project the caller can see but has no role in | `[]` (or the default role's set in OPEN mode) — **never absent** | an absent field would make the SPA fall back to permissive rendering |
| A permission failure on an **archived** project | 403 (permission) before 409 (state) | §10.3.6 — state must not leak through the authorization answer |

**A role id carries two questions, not one — scope as well as tenancy (S1 hardening).** The
scoped finder is `RoleRepository.findAssignable(id, workspaceId, scope)` and **`scope` is
required**. `PermissionSet` is a flat `EnumSet` that does not remember where its grants came from,
so a WORKSPACE-scoped role accepted into `project_members.role_id` does not fail — it *succeeds*,
and puts `workspace.edit` / `workspace.member.manage` into that member's `ProjectContext`. No
workspace-id check catches it, because the role belongs to the right workspace. `RoleRepository`
therefore does **not** extend `JpaRepository` (an inherited `findById` would compile and pass
review), and the three resolution points in `WorkspaceAccessService` — the workspace membership
row, the `project_members` row, and `default_project_role_id` — each assert scope + ownership on
read. The membership rows refuse (404); the *default* falls back to the built-in Contributor and
logs at ERROR, because one bad config row must not lock every member out of every project.

**An unknown permission key is rejected on the WRITE path, dropped on the READ path.** The S4 role
editor must answer **422 "Unknown permission"** for a key this build does not know — there the
divergence is user input and refusing it costs one request. `PermissionConverter` (read path)
deliberately does the opposite: it logs at ERROR and drops the grant, because it runs inside
`requireMember`, i.e. on *every authenticated request naming a workspace*, and throwing on a
built-in role's row would take down the whole instance including for the admin who has to fix it.
Dropping can only ever **narrow** a role — `PermissionSet.of` only ever adds — so it is fail-closed
in the safe direction. Reachable without anything exotic: release N+1 adds a permission, the editor
writes it, N+1 is rolled back.

**The legacy bridges (`RoleView.asWorkspaceRole`/`asProjectRole`) refuse any non-built-in role.**
`roles_scope_key_uk` is `UNIQUE NULLS NOT DISTINCT (workspace_id, scope, key)`, so once S4 ships,
a workspace can own a role keyed `ADMIN` beside the built-in one. With **zero permissions** it
passes the §11.2 grant ceiling (which compares permission sets) and looks harmless — and then every
unconverted `isAtLeast(ADMIN)` bridge returns true for its holder. Checking `Enum.valueOf` alone
protects only the *harmless* case (a key that is not an enum name), so the guard is on `builtIn`
and throws. This is why S3 must finish before S4 ships; the guard means the ordering is enforced by
the code rather than by the schedule.

**Places the new model could invert 404 → 403, and the guard for each:**

1. `resolveProject` — must keep throwing `ProjectNotFoundException` for a non-member of the
   *workspace*, and only then evaluate permissions. Reviewed by `tenancy-reviewer` per slice.
2. `requireProjectAdmin`'s current 404-for-non-project-member becomes a 403 (§10.3.2) — this is a
   *deliberate* 404→403 change on a member who already sees the project in their project list.
   It is safe precisely because the project is already listed to them; it must not be copied to
   any surface where the resource is not already listed.
3. Any future `project.view` (§8.5) must produce **404**, never 403 — write that into the enum's
   javadoc when it lands.

**Reads stay open in v1.** `STRICT` mode narrows writes only. If it narrowed reads it would be
private projects by the back door, and would silently break `SearchScope`, Home, My Work and every
cross-project query. Stated here so no implementer "improves" it.

---

## 13. DC / Cloud

- **No profile-gated behaviour, no forked code, no tier gating.** Custom roles are a product
  feature, not a plan feature; Cloud and DC are identical. (Plane gates custom roles to
  Enterprise — we do not have tiers and must not grow one implicitly.)
- Two new properties, identical defaults in both profiles, wired through
  `application.properties` → `docker-compose.prod.yml`/`.env.prod.example` → `README.md`
  (the `dc-cloud-guard` checklist):

| Property | Env | Default (`dc` and `cloud`) | Meaning |
|---|---|---|---|
| `app.roles.max-custom-per-workspace` | `ROLES_MAX_CUSTOM_PER_WORKSPACE` | `50` | Guard against unbounded role sprawl. Exceeding it → 409. Never a licence check. |
| `app.workspace.default-project-access-mode` | `DEFAULT_PROJECT_ACCESS_MODE` | `OPEN` | Mode for **newly created** workspaces. A DC operator running a large enterprise can make new workspaces strict by default without a code path. |

- Existing workspaces are set to `OPEN` by the migration regardless of the property — the property
  governs creation, never a retroactive change.
- Nothing here needs storage, email, billing or an external identity provider, so there is no
  cloud-only assumption to give a self-hosted path to.

---

## 14. Frontend impact

`DESIGN.md` governs everything visual (Beacon: slate + teal `--color-brand`, Inter, tokens only —
no hardcoded hex). Reuse existing building blocks: the admin catalog table shell, `UsageChip`,
`ImpactBanner`, the delete-with-remap dialog, `AdminApiContext`'s scope-parametrised pages.

### 14.1 The single source of truth

New `src/hooks/usePermissions.ts`:

```ts
const { can, canOwn, isLoading } = usePermissions(wsId, projectId?)
can('sprint.manage')                       // boolean
canOwn('comment.edit', c.authorId === me)  // boolean
```

Backed by `myPermissions` on the already-cached `['workspace', wsId]` / `['project', wsId, pid]`
queries — **zero extra requests**, and it invalidates with the same query keys that already
invalidate on a role change.

Then: **delete every `myRole`-based predicate.** All ~15 sites (`ProjectSettingsArea.tsx:76`,
`WorkspaceSettingsArea.tsx:51`, `NavRail.tsx:110`, `components/sprints.tsx:247`,
`palette/commands.ts:256,271`, `WorkspaceHomePage.tsx:34,171`, `ReleasesPage`, …) become `can(...)`
calls. `Project['myRole']` / `Workspace['myRole']` widen from a literal union to `string` and are
used **only** for display. HD-98 and HD-116 exist because a UI gate drifted from a server
predicate; after this the UI gate and the server predicate are literally the same string, and a
component *cannot* express the old predicate any more.

While permissions are loading, render controls **disabled**, never hidden-then-popped-in.

### 14.2 New screens

**Workspace settings → People** (`/w/:wsId/settings/people`, needs `workspace.member.manage` to
edit, readable by any member): member table (avatar, name, email, role select, joined), invite
row, pending invites. Role select is disabled with a tooltip when the ceiling rule (§11.2) blocks
it; the last-Owner row's select is disabled with the reason.

**Workspace settings → Roles** (`/w/:wsId/settings/roles`, `workspace.role.manage`): two sections
(Workspace roles, Project roles). Built-ins are listed with an `InheritedBadge`-style "Built-in"
chip, no Edit/Delete, and a **Duplicate** action. Custom roles have Edit/Duplicate/Delete + a
`UsageChip` ("used by 4 members in 2 projects"). Empty state carries the "Team lead" recipe (§7.2).

**Role editor** (modal or sub-route): name, description, then the catalog grouped by area with one
checkbox per permission and a small **Own only** toggle where `supportsOwn`. Each row shows its
one-line prose description. Permissions carrying a capability hint show a muted tag
(*"sprint planning"*, *"releases"*) — a hint, never a disabled control, because a role is
workspace-level while capabilities are per-project.

**Project settings → People** (`/w/:wsId/p/:projectId/settings/people`, `project.member.manage`):
the project's explicit members with role selects, an "Add member" picker limited to workspace
members, and — always visible, including when it is the default — a **Default access** card:
*"Everyone in this workspace can contribute here (default role: Contributor). Change."* That card
is this feature's **Rule C affordance**: the mechanism must be discoverable from the page where it
applies, while it is off, or nobody who does not already use it will ever find it.

**Workspace settings → General**: the `project_access_mode` switch with plain-language copy —

> **Project access**
> ○ **Open** — everyone in the workspace can work in every project, using each project's default
>   role. Add someone to a project only to give them a different role.
> ○ **Restricted** — only people added to a project can change anything in it. Everyone can still
>   see every project.

…and, on switching to Restricted, a confirmation showing the impact: *"In 3 of your 5 projects,
nobody has been added explicitly. Only workspace Owners/Admins will be able to change anything
there."* (§16 OQ 5 covers whether the impact preview ships in the same slice.)

> **That last sentence is false — see [§20.2](#202-142s-strict-impact-copy-is-false-found-in-s7-hd-130).**
> `project.curate.all` holds no issue or comment permission, so in a `STRICT` project with no
> explicit members **nobody can file an issue, the Owner included**. What shipped is the counted
> preview's `projectsWithNoWriters`, with the wording in `roles-permissions-s7-spec.md` §9.1.

### 14.3 Existing surfaces that must react

- `NavRail` — Settings link on `project.edit ∨ project.taxonomy.manage ∨ project.member.manage`;
  "New issue" enabled iff **any** visible project grants `issue.create` (the modal's project picker
  filters to those; if none, the button shows the reason rather than vanishing).
- Board — drag-and-drop enabled on `issue.transition`; card drag for rank on `issue.rank`.
- Backlog — drag-to-rank on `issue.rank`; sprint sections' add/remove on `sprint.assign`; sprint
  lifecycle buttons on `sprint.manage`.
- Issue detail — inline editing per field group (`issue.edit` / `issue.transition` / `issue.assign`);
  comment box on `comment.create`; edit/delete on `canOwn`; attachment upload/delete likewise;
  Delete issue on `canOwn('issue.delete', isReporter)`.
- Releases page — create/edit/release on `version.manage` (its Rule C "turn on releases"
  affordance stays capability-driven and is unaffected).
- `CommandPalette` — every project-scoped command's availability comes from `can(...)`.

---

## 15. Acceptance criteria

**Model & migration**
1. Fresh DB: 3 workspace + 4 project built-in roles exist with exactly the §7 permission sets.
2. Upgraded DB: every `workspace_members`/`project_members`/`workspace_invites` row has a
   `role_id`; every `VIEWER` project row became `MEMBER`; every workspace is `OPEN` with a NULL
   default role; `V15` leaves no legacy `role` column and Hibernate `validate` passes.
3. **Parity test** (`PermissionParityTest`, table-driven): for each of the ~30 call sites × 6 actor
   archetypes (workspace Owner / workspace Admin / workspace Member with no project row / project
   Manager / project Member / project Viewer-as-migrated), the new predicate returns the **same
   verdict** as the legacy one. This is the proof that the upgrade is a no-op.

**Enforcement**
4. A project Viewer gets 403 on create/edit/transition/rank/comment/attach and 200 on every read.
5. A Commenter can comment and attach, and gets 403 on `PATCH /issues/{n}`.
6. `sprint.assign` is enforced on **both** doors: the sprint endpoints *and* `sprintId` in
   `PATCH /issues/{n}` (§6.5).
7. A `PATCH /issues/{n}` that changes only the status requires `issue.transition` and **not**
   `issue.edit`; one that sends an unchanged `statusId` alongside a title change requires only
   `issue.edit`.
8. Assigning a user who lacks `issue.assignable` → 422 with the distinct message; an existing
   assignment by that user survives an unrelated PATCH untouched.
9. `sprint.manage` is enforced on a `board = KANBAN` project (capabilities never change a status
   code).
10. Workspace Admin with no project membership retains full project-admin abilities via
    `project.administer.all`.

**Modes**
11. `OPEN` + no explicit membership → Contributor abilities. Flip to `STRICT` → the same user 403s
    on writes and still 200s on every read, with no data changed. Flip back → restored.
12. A project whose `default_project_role_id` is **None** behaves as strict while its workspace is
    `OPEN`.

**Roles CRUD**
13. Built-in role: edit → 409, delete → 409, duplicate → 200 creating an editable copy.
14. Delete a custom role in use → 409 + usage; with `?reassignToRoleId=` → 200 and every holder
    moves.
15. Concurrent role edits → 409 (`@Version`); a role edit that replaces permissions does not
    violate the `(role_id, permission)` PK.
16. Demoting the last Owner → 409. Granting a role holding a permission the actor lacks → 403.
17. `max-custom-per-workspace` exceeded → 409.

**Tenancy** (`tenancy-reviewer` gate, mandatory per slice)
18. Non-member: every new endpoint → **404**. Member without the permission → **403**.
19. A role id from workspace B used in workspace A → **422 "Unknown role"**, in every one of:
    invite, add member, change member role, set project default role, set workspace default role,
    role delete-reassign.
20. No new query anywhere reads `roles` by bare id without a workspace/built-in scope filter.

**Frontend**
21. `grep -r "myRole ===" src/main/frontend/src --include=*.tsx --include=*.ts` returns **zero**
    hits outside display code and test fixtures.
22. `myPermissions` is present (possibly empty) on every workspace and project response, including
    list responses.
23. A Viewer sees no create/edit affordances anywhere, and clicking nothing produces a 403.
24. `npx tsc -b` clean (**not** `tsc --noEmit`, which type-checks nothing here).

**Docs** — `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` updated (roles endpoints, the
`myPermissions` field, the new member-role endpoints, the reworked Roles tables), validated with
swagger-cli; `docs/project-state.md` gains a Roles & permissions section; `CLAUDE.md`'s
`requireProjectMember` gotcha is rewritten to describe the new model.

---

## 16. Slice breakdown

Independently shippable, each green on its own. Gates per `docs/design/dev-team-pipeline.md`.

| # | Slice | Includes | Does **not** include | Depends on |
|---|---|---|---|---|
| **S1** | **Model & resolution, dark** | `V13`/`V14`/`V15`; `Role` entity + repos; `Permission` enum; `PermissionSet`; `RolePermissionCache`; resolution into `WorkspaceContext`/`ProjectContext`; `resolveProject` rename; `GET /api/permissions`; `myPermissions` on workspace + project responses; the parity test harness | Any call-site change; any roles CRUD; any UI | — |
| **S2** | **Project call-site migration** | Every project-scoped call site in §10.2; `MissingPermissionException`; the double-door fix; the `issue.assignable` check; the per-field `PATCH /issues` rules | Workspace-scoped sites; `ScopeResolver` deletion | S1 |
| **S3** | **Workspace call-site migration + `ScopeResolver` removal** | §10.1 sites; `PATCH /workspaces/{ws}`; `PATCH /workspaces/{ws}/members/{u}`; grant-ceiling + last-Owner guards; delete `ScopeResolver`, `WorkspaceRole`, `ProjectRole` | Custom roles | S1 (S2 recommended first) |
| **S4** | **Custom roles API** | Roles CRUD, duplicate, delete-with-reassign, usage endpoint, `max-custom-per-workspace`, `PATCH .../projects/{p}/members/{u}`, `roleId` on add-member/invite | Any UI | S3 |
| **S5** | **SPA: permission-driven gating** | `usePermissions`; replace all ~15 `myRole` predicates; loading/disabled behaviour; palette, NavRail, board, backlog, issue detail, releases | New screens | S1 |
| **S6** | **SPA: People & Roles screens** | Workspace People, Workspace Roles + role editor, Project People + default-access card | Access-mode switch | S4, S5 |
| **S7** | **Access mode** | `project_access_mode` enforcement branch; workspace General switch + copy; project default-role picker; impact preview | — | S2, S6 |
| **S8** | **Docs** | `openapi.yaml`, both `api-*.md`, `project-state.md`, `CLAUDE.md` gotcha rewrite | — | S4, S7 |

S1–S3 are shippable to prod with **zero user-visible change** — that is the point of the ordering.
The first slice a user notices is S5.

---

## 17. Where I challenged the brief

The orchestrator asked to be told, in writing, where a stated default was pushed back on.

### 17.1 "A workspace switch turns real enforcement on" → **there is no enforcement-off mode**

*(Owner decision 1.)* I kept the outcome the owner asked for — nothing tightens on upgrade,
tightening is a deliberate act, roles are assigned and visible beforehand — but implemented it as a
**per-project default role** plus an `OPEN`/`STRICT` mode (§5.2) rather than as a flag that
disables permission checks. Reasons: (a) a flag that bypasses checks is a second authorization
code path that must be reasoned about by every future reviewer and will rot, which is the same
argument that makes DC/Cloud profile-forking forbidden; (b) it makes the transitional state
incoherent — the owner's own question *"what do the roles do while it is off?"* has no good answer
under a bypass, and a crisp one under a default role ("exactly what they say"); (c) the default
role is the same mechanism private projects will need, so it is not throwaway scaffolding; (d) it
gives per-project granularity for free (a single project can be strict inside an open workspace).
The switch still exists, still defaults to the permissive value, and still requires a deliberate
click.

### 17.2 "Workspace OWNER/ADMIN keep the implicit project-curator bypass" → **kept, but made explicit and grantable**

*(Orchestrator default 5.)* Accepted in effect, rejected in form. Instead of a hardcoded
`if (workspaceRole.isAtLeast(ADMIN)) return;` inside the resolver, the bypass is a workspace
permission: greppable, testable, and grantable to a role that is not Admin.

**The bypass becomes `project.curate.all`; `project.administer.all` is its grantable superset.**
Two entries, because "made explicit" must mean *exactly as wide as it is today* — and the bypass
today is narrow. It is `requireProjectCurator` and nothing else: `{project.edit, component.manage,
version.manage, sprint.manage}`. Archive/unarchive, project member management, project taxonomy,
issue deletion and attachment moderation are `MANAGER`-only, and a workspace Owner who is not a
project member gets none of them. So the built-in Owner and Admin hold `project.curate.all`, which
is that set and no more, and behaviour is identical on upgrade.

`project.administer.all` — every project permission in every project — stays in the catalog and is
**seeded on nothing**. That is the stated benefit of making the bypass explicit, kept: a workspace
can compose a "Program manager" role that administers every project without being able to invite
people, which the hardcoded bypass could never express. It is now a deliberate grant rather than
something every Owner silently acquired. Cost: two enum constants instead of one.

*(This was originally written as one entry, `project.administer.all` on the built-ins.
`PermissionParityTest` reported it as a widening at 12 call sites — 24 (call site × archetype)
cells, plus 2 more where a workspace Owner picked up comment moderation they do not have — before
a single call site had moved. That is the entire argument for building the harness in S1.)*

### 17.3 `comment.edit` is **own-only, not grantable unrestricted**

Not a default I was given, but a decision worth flagging: editing another person's words is not a
capability we should ship, at any role. `comment.delete` unrestricted (moderation) covers the real
need. Recorded as OQ 3 in case the owner disagrees.

### 17.4 The "one role per member per project" default is **accepted without change**

*(Orchestrator default 4.)* OpenProject's multi-role model doubles resolution complexity (union
semantics, conflicting own-qualifiers, an unclear answer to "what is my role here?") for a case a
custom role solves better. No challenge.

Defaults **6** (SystemRole.ADMIN gains nothing), **7** (no issue/field-level security) and **8**
(no groups) are accepted as stated; §8.6 and §6.4 record the seams so neither becomes a rewrite.

---

## 18. Highest-risk assumption

> **That the built-in Contributor role plus the project default role reproduce today's effective
> permissions *exactly*, at every one of the ~30 call sites — so S2 and S3 ship with zero
> user-visible change.**

If one call site is mapped a notch too tight, a team silently loses an ability, and the failure
mode is a **button that stopped appearing** — no error, no log, nothing the owner would notice
until someone complains. (Mapped a notch too loose is the mirror risk and is worse: a permission
that does not actually bite.)

Mitigations, all mandatory: the table-driven `PermissionParityTest` in **S1** (before any call site
moves), the per-slice `tenancy-reviewer` + `security-officer` gates, and the deliberate ordering
that keeps S1–S3 invisible to users so a parity bug is caught before any UI depends on it. The
`MissingPermissionException` detail string (naming the exact permission) is the field-debugging
tool if one slips through.

Second-order risk: the SPA rewrite in S5 touches ~15 gating sites at once. A miss there is
cosmetic (a control shown that 403s on click) rather than a security hole, because the API is the
boundary — which is precisely why §5.3 forbids ever inverting that relationship.

---

## 19. Open questions

Each with a recommendation and the cost of being wrong. None blocks S1.

1. **Should `workspace.role.manage` be Owner-only rather than Owner+Admin?**
   *Recommendation: Owner + Admin.* A workspace with one Owner who goes on holiday would otherwise
   have no way to adjust roles. **Cost if wrong:** an Admin can craft a role granting themselves
   more — but Admins can already invite other Admins today, so the ceiling rule (§11.2) already
   bounds the escalation. Low.

2. **Does `own` on `issue.edit` / `issue.delete` mean reporter only, or reporter ∪ assignee?**
   *Recommendation: reporter only.* "Own" should mean "you made it". **Cost if wrong:** teams who
   wanted "assignees may edit their own work" must grant `issue.edit` unrestricted; changing the
   meaning later silently widens every existing grant, so it is a real (if cheap) migration. Medium.

3. **Should `comment.edit` ever be grantable unrestricted?** *Recommendation: no* (§17.3).
   **Cost if wrong:** a moderation gap for a workspace with a compliance need; adding it later is
   one boolean on the catalog entry. Low.

4. **Should `project.create` stay on the built-in Member role?** *Recommendation: yes* — it is
   today's behaviour and changing it would break the no-op promise. Workspaces that want it
   restricted duplicate Member without it. **Cost if wrong:** project sprawl in large Cloud
   workspaces. Low.

5. **New workspaces: `OPEN` or `STRICT` by default?** *Recommendation: `OPEN`* — a five-person team
   inviting a colleague who then cannot edit anything is a bad first hour, the project-members UI
   is brand new, and Linear (the simplicity benchmark) scopes by team membership only when you ask.
   Discoverability is handled by the always-visible Default-access card (§14.2). **Cost if wrong:**
   growing Cloud tenants over-share until someone finds the switch. Medium — and it is the one
   answer most worth revisiting after the first month of real use.

6. **Does the STRICT-mode impact preview ship in S7 or later?** *Recommendation: S7*, as a simple
   count (projects where explicit members < workspace members). **Cost if wrong:** an owner flips
   the switch and surprises their team. Low, and reversible in one click.

7. **Should permission/role changes be audited?** *Recommendation: not in v1* — there is no
   workspace-level audit log to hang it on, and inventing one here is a separate epic.
   **Cost if wrong:** a compliance-minded DC customer asks "who gave them that role?" and we cannot
   answer. Medium for Cloud later; zero for now.

8. **Should `requireProjectAdmin`'s 404-for-non-project-member become a 403 (§10.3.2)?**
   *Recommendation: yes* — the project is already listed to every workspace member, so the 404
   protects nothing and is the codebase's only permission-failure-as-404. **Cost if wrong:** a
   trivially observable behaviour change on one endpoint family. Low, but it is a behaviour change
   and the owner should say yes out loud.
---

## 20. Corrections — where this document was wrong (recorded in S8, HD-131)

Recorded **as corrections rather than as edits**: the original sentences are left standing
above, each with a pointer down here. A spec that is quietly rewritten to match what was
built teaches nobody anything, and in all three cases below the reasoning that produced the
mistake is worth keeping — two of them are wrong in the same direction, by treating a
workspace role as a floor under project work.

The build recorded eleven reconciliation items in total (`roles-permissions-s4-spec.md` §11,
`roles-permissions-s7-spec.md` §12). The three below are the ones that would mislead a
reader of *this* document into building — or authorizing — the wrong thing. The remaining
eight are refinements the two slice specs already state correctly and are best read there.

### 20.1 §11.2 states the grant ceiling without the Owner exemption (found in S4, HD-127)

§11.2 says flatly that **nobody** may grant a role holding a permission they do not
themselves hold. What shipped: the built-in workspace **Owner is exempt inside their own
workspace**, for both *defining* and *assigning* a role. An **`ADMIN` is not** exempt (owner
decision, 2026-08-17), and that distinction is the point of it.

Why this is load-bearing rather than a softening: the ceiling exists to stop escalation
*past* whoever is ultimately responsible, and inside one workspace that person is the Owner.
More concretely — **the exemption is the only reason `project.administer.all` is mintable at
all.** No built-in role holds it (§7.1 seeds it on nothing deliberately, because it is wider
than anything that exists today), so under a ceiling with no exemption the product would
ship a permission that *every possible actor* is forbidden to grant: dead on arrival, in a
catalog whose premise is that a permission nothing can reach is meaningless.

Three things §11.2 also does not say, and the code does:

- The **definition** ceiling applies to **WORKSPACE-scoped roles only**. A project-scoped
  role has no comparand at workspace level, and inventing one would forbid an Admin from
  duplicating Contributor — the product's primary recipe. Its entire practical effect at
  workspace scope is therefore *"only an Owner may mint `project.administer.all`"*, which is
  a small and exactly-right rule.
- Project scope is guarded instead by the `SELF_HELD_ROLE` rules on editing and deleting
  (you may not widen a project role you hold yourself), plus the one named escape on
  membership writes: a holder of `project.member.manage` may grant the built-in Project
  admin role **to somebody else, never to themselves**.
- The ceiling bounds **both ends** of a write — the role as it stands today as well as the
  set being sent — so it forbids *stripping* and even *renaming* a role wider than yours,
  not only widening one. Sabotage is as reachable as escalation, and irreversible by the
  person who did it.

### 20.2 §14.2's `STRICT` impact copy is false (found in S7, HD-130)

§14.2 proposes the confirmation text *"In 3 of your 5 projects, nobody has been added
explicitly. **Only workspace Owners/Admins will be able to change anything there.**"* The
bolded half is **wrong**, and shipping it would have told an owner the opposite of the truth
at the exact moment they were deciding.

Workspace Owners and Admins hold `project.curate.all`, which is exactly
`{project.edit, component.manage, version.manage, sprint.manage}` — **no issue permission
and no comment permission anywhere in it**. So in a `STRICT` project that nobody has been
added to, *nobody can file an issue, edit one, move it on the board or comment on it,
including the Owner*. What they can do is rename the project and cut a version.

What shipped instead of the sentence is a counted preview
(`POST …/workspaces/{id}/project-access/preview`) whose **`projectsWithNoWriters`** field
names that population directly — live projects where, after the change, nobody at all holds
`issue.create` — with the UI wording in `roles-permissions-s7-spec.md` §9.1. The general
lesson outlives the copy: **`project.curate.all` is a curation bypass, not an administration
one.** Any statement that treats a workspace role as a floor under project work is wrong in
this same way — §11.2's missing exemption above and the stranding hole HD-136 closed are
both the same misreading from other directions.

### 20.3 "Default role = None" is not expressible, and does not need to be (found in S7, HD-130)

§5.2's last consequence bullet — echoed by §8.5 and by §15 AC 12 — says per-project
strictness is available by setting *"that project's default role to **None**"*. There is no
such value, and there must not be one: `projects.default_project_role_id IS NULL` means
**inherit** (fall through to the workspace default, then to the built-in Contributor), which
is the opposite of none. Had NULL been given the "none" meaning, V14's upgrade — every
workspace `OPEN` with both default columns NULL — would have been a mass revocation instead
of the provable no-op §8.4 promises.

The capability itself is real; it already had a name. The built-in **Viewer** grants ∅, so
read all three places as *"set that project's default role to **Viewer**"* — a role a picker
can offer, a user can read, and `myPermissions` reports honestly. §8.5's private-projects
seam is unaffected and still needs no new column.
