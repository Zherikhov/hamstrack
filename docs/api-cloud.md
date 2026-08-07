# Hamstrack REST API — Cloud

> Deployment: **Cloud** (`hamstrack.com`). Self-hosting? See the [DC / self-hosted reference](api-dc.md).

This is the reference for the HTTP API behind the Hamstrack web app. Everything the UI does goes through this API, and it is available to users for scripting and integrations. An interactive Swagger UI version lives at [hamstrack.com/docs](https://hamstrack.com/docs); the machine-readable OpenAPI spec is at [`/openapi.yaml`](https://hamstrack.com/openapi.yaml).

**Base URL**

```
https://hamstrack.com/api
```

> **Beta notice:** the API is unversioned while Hamstrack is in beta — breaking changes are possible and are announced in release notes. While the Cloud instance is in test mode, user data may periodically be reset; every account gets a pre-populated demo workspace to explore.

## Contents

- [Quick start](#quick-start)
- [Authentication](#authentication)
- [Conventions](#conventions)
- [Errors](#errors)
- [Roles](#roles)
- [Instance metadata](#instance-metadata)
- [Auth endpoints](#auth-endpoints)
- [Workspaces](#workspaces)
- [Onboarding](#onboarding)
- [Projects](#projects)
- [Project configuration](#project-configuration)
- [System administration](#system-administration)
- [Issues](#issues)
- [Comments](#comments)
- [Attachments](#attachments)
- [Notifications](#notifications)
- [Real-time events (SSE)](#real-time-events-sse)

## Quick start

```bash
BASE=https://hamstrack.com/api

# 1. Log in (register + verify your email first — see Auth endpoints)
TOKEN=$(curl -s -X POST $BASE/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your-password"}' | jq -r .accessToken)

# 2. List your workspaces
curl -s $BASE/workspaces -H "Authorization: Bearer $TOKEN"

# 3. Create an issue
curl -s -X POST $BASE/workspaces/{workspaceId}/projects/{projectId}/issues \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Fix the flux capacitor","typeId":"…","statusId":"…","priorityId":"…"}'
```

## Authentication

Hamstrack uses short-lived **JWT access tokens** plus a rotating **refresh-token cookie**:

1. `POST /auth/register` → a verification email is sent.
2. `POST /auth/verify-email` with the emailed token → returns an access token (and logs you in).
3. `POST /auth/login` → returns an access token in the body and sets the `refresh_token` cookie (`HttpOnly`, `Secure`, `SameSite=Strict`, scoped to `/api/auth`).
4. Send the access token on every request:

   ```
   Authorization: Bearer <accessToken>
   ```

5. When the access token expires (`expiresIn` seconds, currently 30 min), call `POST /auth/refresh` — the cookie authenticates the call and is **rotated** on each use (an old cookie value becomes invalid). Refresh tokens live 30 days.
6. `POST /auth/logout` revokes the refresh token and clears the cookie.

All endpoints except [Auth endpoints](#auth-endpoints) and [Instance metadata](#instance-metadata) require the `Authorization` header. A missing or expired token yields `401 Unauthorized`.

## Conventions

- **Format** — request and response bodies are JSON (`Content-Type: application/json`), UTF-8. The only exceptions: attachment upload (`multipart/form-data`) and download (binary).
- **IDs** — all identifiers are UUIDs, except issues, which are addressed by their **project-scoped number** (the `42` in `DEMO-42`).
- **Timestamps** — ISO-8601 with UTC offset, e.g. `2026-07-14T06:24:41.486119Z`. Date-only fields (`dueDate`) use `YYYY-MM-DD`.
- **Partial updates** — `PATCH` endpoints accept any subset of fields; omitted (or `null`) fields are left unchanged.
- **Access model** — a resource you cannot see returns `404 Not Found`, whether it doesn't exist or you simply aren't a member of its workspace. Membership is never revealed via `403`.
- **Optimistic locking** — issues carry a `version`; send it back in `PATCH` and get `409 Conflict` if someone changed the issue in between (see [Issues](#issues)).
- **Pagination** — paginated list endpoints accept `page` (zero-based, default `0`) and `size` (default `50`, clamped server-side to a maximum of `100`) and return a uniform envelope:

  ```json
  { "content": [ /* rows */ ], "page": 0, "size": 50, "totalElements": 137, "totalPages": 3, "hasNext": true }
  ```

  Currently paginated: [`GET /admin/users`](#system-administration), issue [comments](#comments) and [history](#issues), and the issue [list](#issues) **when `size` is passed**. The issue list is the deliberate exception — without `size` it returns the full unpaginated array (the board needs every card), switching to the envelope only when `size` is present.

## Errors

Errors follow [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Issue was modified by someone else — refresh and retry"
}
```

Validation failures (`400`) additionally carry a per-field `errors` map:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "errors": { "email": "must be a well-formed email address" }
}
```

| Status | Meaning |
|---|---|
| `400` | Malformed request or failed validation |
| `401` | Missing/expired/invalid access token |
| `403` | Authenticated but not allowed (e.g. insufficient role) |
| `404` | Not found — or not a member of the containing workspace |
| `409` | Conflict: stale `version`, duplicate name/key, resource in use |
| `413` | Attachment exceeds the per-file size limit (default 20 MB) |
| `415` | Attachment file extension is not in the allow-list |
| `422` | Semantically invalid reference (unknown status/type/assignee, workflow-forbidden transition) |
| `429` | Rate limited — wait the number of seconds in the `Retry-After` header |

### Rate limits

The sensitive auth endpoints (`login`, `register`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`) share a **per-IP budget of 15 requests per minute**. Additionally, repeated failed logins for one account trigger an **exponential backoff** (starting at 30 s after 5 consecutive failures, doubling per failure, capped at 15 min); a successful login resets the counter. Both mechanisms respond with `429` and a `Retry-After` header (seconds). The rest of the API is currently not rate-limited.

## Roles

**System role** (`ADMIN` — instance-wide, maintains the global taxonomy via [`/admin/**`](#system-administration)), **workspace roles** (`OWNER` > `ADMIN` > `MEMBER`) and **project roles** (`MANAGER` > `MEMBER` > `VIEWER`). `GET /auth/me` returns your `systemRole`.

| Action | Required role |
|---|---|
| See a workspace and its projects, issues, members | workspace member |
| Invite workspace members | workspace `ADMIN` |
| Manage the **global** taxonomy (statuses / priorities / issue types / fields / workflows / sets) and any project's bindings | system `ADMIN` |
| Manage **workspace-scoped** taxonomy and the bindings of projects in the workspace | workspace `OWNER`/`ADMIN` ([delegated](#delegated-administration)) |
| Manage **project-private** taxonomy and this project's bindings | project `MANAGER` ([delegated](#delegated-administration)) |
| Create a project | workspace member (creator becomes project `MANAGER`) |
| Edit / archive a project, manage its members | project `MANAGER` |
| Create / edit issues, comment, attach files | workspace member |
| Delete an issue | project `MANAGER` |
| Edit / delete a comment | comment author |
| Delete an attachment | uploader or project `MANAGER` |

## Instance metadata

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/meta` | — | Instance flags and version |

```json
{ "publicLandingEnabled": true, "termsAcceptanceRequired": true, "publicSignupEnabled": true, "version": "0.2.0" }
```

## Auth endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/auth/register` | — | Create an account; sends a verification email. `201` |
| `POST` | `/auth/verify-email` | — | Exchange the emailed one-time token for a session |
| `POST` | `/auth/resend-verification` | — | Re-send the verification email (always `200`) |
| `POST` | `/auth/login` | — | Email + password → access token + refresh cookie |
| `POST` | `/auth/refresh` | cookie | Rotate the refresh token, get a fresh access token |
| `POST` | `/auth/logout` | cookie | Revoke the refresh token. `204` |
| `POST` | `/auth/forgot-password` | — | Send a reset link (always `200` — no account enumeration) |
| `POST` | `/auth/reset-password` | — | Set a new password with the emailed token; revokes all sessions |
| `GET` | `/auth/me` | ✔ | The current user (`id`, `email`, `displayName`, `avatarUrl`, `systemRole`, `needsOnboarding`) |

`GET /auth/me` includes **`needsOnboarding`** — `true` until the user creates or joins their first team (or skips the welcome screen). Route new sign-ins to the [onboarding](#onboarding) flow while it's true.

**Register** — `termsAccepted: true` is required on this instance:

```bash
curl -X POST $BASE/auth/register -H "Content-Type: application/json" -d '{
  "email": "you@example.com",
  "password": "correct-horse-battery",
  "displayName": "Ada Lovelace",
  "termsAccepted": true
}'
```

**Login / verify-email / refresh** all return the same shape:

```json
{
  "accessToken": "eyJhbGciOiJIUzUxMiJ9…",
  "expiresIn": 1800,
  "userId": "0197fa30-…",
  "email": "you@example.com",
  "displayName": "Ada Lovelace"
}
```

Unverified accounts cannot log in (`403` until the email is verified). A demo workspace and project are provisioned only when the user chooses "Create a team" during [onboarding](#onboarding) — not automatically on first authentication (users who join an existing team get none).

## Workspaces

The workspace is the top-level container (and tenancy boundary): members, projects, issue types, statuses and workflows all live inside one.

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces` | ✔ | Create; caller becomes `OWNER`, default types/statuses are seeded. `201` |
| `GET` | `/workspaces` | ✔ | Workspaces the caller belongs to |
| `GET` | `/workspaces/{id}` | member | Get one |
| `GET` | `/workspaces/{id}/members` | member | List members |
| `POST` | `/workspaces/{id}/invites` | `ADMIN` | Email an invite (`{"email", "role"}`; role ≤ your own, never `OWNER`). `201` |
| `POST` | `/workspaces/accept-invite?token=…` | ✔ | Accept an invite; must be signed in with the invited email |

```json
// POST /workspaces  {"name": "Acme Inc"}
{ "id": "…", "slug": "acme-inc", "name": "Acme Inc", "myRole": "OWNER", "createdAt": "…" }
```

## Onboarding

On first login (`needsOnboarding: true` from [`/auth/me`](#auth-endpoints)) a user either creates their own team or joins one they were invited to. These endpoints back the "join a team" screen — accepting an invite here needs no emailed token.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/invites` | ✔ | Pending, non-expired invites addressed to your email |
| `POST` | `/invites/{id}/accept` | ✔ | Join the workspace; still email-bound (`404` if the invite isn't yours). Returns the workspace |
| `POST` | `/invites/{id}/decline` | ✔ | Remove the invite. `204` |
| `POST` | `/onboarding/create-team` | ✔ | The "Create a team" choice: provisions the demo starter workspace and completes onboarding. `204` |

Completing onboarding clears `needsOnboarding` (afterwards `/auth/me` reports `false` and the welcome screen won't reappear). It happens by either **creating a team** (`POST /onboarding/create-team`, or the first `POST /workspaces`) or **accepting an invite**. The **demo workspace is provisioned only via `create-team`** — users who join an existing team get a clean account with just the team they joined.

## Projects

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/projects` | member | Create (`{"name", "key", "description?"}`); key is 1–10 chars `A-Z0-9`, unique per workspace. `201` |
| `GET` | `/workspaces/{wsId}/projects?includeArchived=false` | member | List projects |
| `GET` | `/workspaces/{wsId}/projects/{projectId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{projectId}` | `MANAGER` | Update `name` / `description` |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/archive` | `MANAGER` | Archive (read-only afterwards). `204` |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/unarchive` | `MANAGER` | Restore. `204` |
| `GET` | `/workspaces/{wsId}/projects/{projectId}/members` | member | List project members |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/members` | `MANAGER` | Add a workspace member (`{"userId", "role"}`). `201` |
| `DELETE` | `/workspaces/{wsId}/projects/{projectId}/members/{userId}` | `MANAGER` | Remove a member. `204` |

```json
// project shape
{
  "id": "…", "workspaceId": "…", "name": "Demo Project", "key": "DEMO",
  "description": "…", "archived": false, "myRole": "MANAGER", "createdAt": "…"
}
```

## Project configuration

The taxonomy (statuses, priorities, issue types, custom fields) lives in a catalog — **global** (maintained by the system administrator) and optionally **workspace-scoped** or **project-private** (see [Delegated administration](#delegated-administration)) — and reaches projects through reusable bindings: a *workflow* (statuses + allowed transitions), a *priority set* (offered priorities + the default for new issues), a *field set* (which custom fields the project's issues carry, their order and create-form behavior) and an *issue type set* (which types the project offers — restricting only issue creation and type changes; existing issues keep their type). Regular users read a project's **effective configuration** from one endpoint and never touch the catalog:

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/config` | member | Effective statuses (board order), transition rules, priorities (+default), issue types, custom fields |

```json
{
  "statuses":    [ { "id": "…", "name": "To Do", "color": "#6B7280", "category": "TODO", "position": 0 }, … ],
  "transitions": [ { "fromStatusId": null, "toStatusId": "…" } ],
  "priorities":  [ { "id": "…", "name": "High", "color": "#EA580C", "icon": "chevron-up", "isDefault": false }, … ],
  "issueTypes":  [ { "id": "…", "name": "Bug", "color": "#EF4444", "icon": "bug", "position": 0 } ],
  "fields":      [ { "id": "…", "key": "severity", "name": "Severity", "type": "SELECT",
                     "config": { "options": [ { "id": "critical", "label": "Critical", "color": "#B91C1C" }, … ] },
                     "description": "Impact of the defect on users",
                     "required": false, "showOnCreate": true }, … ]
}
```

Transition semantics: a status with no source-specific rules is open (any move allowed); once it has rules, only its listed targets plus wildcard (`fromStatusId: null` = "from any") targets are accepted — a forbidden move returns `422` on issue updates and board drag-and-drop.

Custom field types and their JSON value shapes: `TEXT`/`TEXTAREA`/`URL` — string; `NUMBER` — number (`config.min`/`max` enforced); `DATE` — `"YYYY-MM-DD"` string; `SELECT` — option id string; `MULTI_SELECT` — array of option ids; `USER` — user UUID (must be a workspace member); `CHECKBOX` — boolean. A `required` field must be filled on create and can never be cleared; `showOnCreate: false` fields are only offered when editing.

## System administration

Endpoints under `/admin/**` require the **system `ADMIN` role** (instance-wide, independent of workspace/project roles; regular users get `403`). They manage the global catalog and the project bindings:

| Method | Path | Description |
|---|---|---|
| `GET/POST` | `/admin/statuses` · `/admin/priorities` · `/admin/issue-types` | List catalog (with usage counts) / create. `201`. Create/rename returns `409` when the name collides with any item **visible** to the scope — including one inherited from a wider scope (reuse it instead of duplicating) |
| `PATCH` | `/admin/{catalog}/{id}` | Update |
| `POST` | `/admin/{catalog}/{id}/archive` · `/unarchive` | Hide from new use / restore |
| `DELETE` | `/admin/{catalog}/{id}?replaceWithId=` | Delete; `409` while issues reference it and no replacement is given — with `replaceWithId`, affected issues are remapped |
| `GET` | `/admin/{catalog}/{id}/usage` | Usage detail (all four catalogs incl. `fields`): names of containing workflows/sets and the projects reached through them, plus the referencing-issue count |
| `GET/POST` | `/admin/workflows` | List / create (`{"name", "description?", "statusIds": […], "transitions": […]}`) |
| `PATCH/DELETE` | `/admin/workflows/{id}` | Full replacement / delete (`409` while projects use it; the system default is not deletable) |
| `GET/POST` | `/admin/priority-sets` | List / create (`{"name", "items": [{"priorityId", "isDefault"}]}`) |
| `PATCH/DELETE` | `/admin/priority-sets/{id}` | Full replacement / delete (`409` while in use) |
| `GET/POST` | `/admin/fields` | List custom fields (with usage counts) / create (`{"name", "key?", "type", "config?", "description?"}`; blank `key` is derived from the name — key and type are immutable afterwards). Create/rename returns `409` when the name **or** key collides with any field visible to the scope, inherited ones included (reuse it instead of duplicating) |
| `PATCH` | `/admin/fields/{id}` | Update name/config/description (also `POST /{id}/archive` · `/unarchive`) |
| `DELETE` | `/admin/fields/{id}?dropValues=` | Delete; `409` while issues hold values unless `dropValues=true` (drops them — there is no remap across value shapes; archive instead to keep them) |
| `GET/POST` | `/admin/field-sets` | List / create (`{"name", "items": [{"fieldId", "required", "showOnCreate"}]}` — a required field is always shown on create) |
| `PATCH/DELETE` | `/admin/field-sets/{id}` | Full replacement / delete (`409` while in use; the system default "No fields" set is not deletable) |
| `GET/POST` | `/admin/issue-type-sets` | List / create (`{"name", "typeIds": […]}` in display order; a set can never be empty) |
| `PATCH/DELETE` | `/admin/issue-type-sets/{id}` | Full replacement / delete (`409` while in use; the system default "All types" set is not deletable) |
| `GET` | `/admin/projects` | Assignment matrix: every project × its bindings |
| `PATCH` | `/admin/projects/{id}/bindings` | `{"workflowId", "prioritySetId", "fieldSetId", "issueTypeSetId"}` (null = system default); `409` when issues sit in statuses the new workflow lacks |
| `GET/POST` | `/admin/users` | List accounts (paginated `?page=&size=`, oldest first) / create (`{"email", "displayName", "systemRole?"}`). No password or email — the `201` response is `{"user", "setupLink"}`; hand the one-time `setupLink` (`/reset-password?token=`, valid 7 days) to the person |
| `POST` | `/admin/users/{id}/setup-link` | Regenerate the one-time setup link → `{"setupLink"}` |
| `PATCH` | `/admin/users/{id}` | Change `systemRole` (`ADMIN`/`USER`) and/or `status` (`ACTIVE`/`DISABLED`); `409` when it would disable/demote the last active admin or your own account (disabling revokes the user's refresh tokens) |

Integrity rules: deletions never leave dangling references (remap or `409`), no workflow can end up empty, every priority set keeps a default, and a workflow change is refused while it would strand issues in statuses invisible to the board.

## Delegated administration

The same catalog and binding operations are available at two **delegated** scopes so teams self-serve without a system admin. Authorization is membership-based (not the system `ADMIN` role):

- **Workspace settings** — `/api/workspaces/{wsId}/admin/**`, for workspace `OWNER`/`ADMIN`.
- **Project settings** — `/api/workspaces/{wsId}/projects/{projectId}/admin/**`, for project `MANAGER`.

Each scope owns its own rows: a workspace admin creates **workspace-scoped** statuses/priorities/types/fields and reusable sets; a project admin creates **project-private** ones. Tenancy: a non-member gets `404`; a member without the required role gets `403`.

**Scoping & visibility**

- List endpoints return everything **visible** to the scope: global ∪ workspace ∪ (for a project) its own private rows. Every row carries a `scope` field (`GLOBAL` / `WORKSPACE` / `PROJECT`); only own-scope rows are editable — a write to an inherited (higher-scope) row returns `404`.
- **Reuse over duplication** — creating or renaming a status / priority / issue type / field (fields also on `key`) is refused with `409` when the name collides with any item **visible** to the scope, whether it's an own-scope row or one inherited from a wider scope (a system/global item, or a workspace item inherited by a project). Bind the inherited item instead of minting a scoped duplicate.
- A set (workflow / priority set / field set / type set) may reference only catalog rows visible to its scope; a foreign or wrong-scope reference is rejected `422`.
- A project (or the workspace matrix) may bind only a set visible to the target project — global, its workspace's, or its own — else `422`.

**Catalog & sets** — identical shapes and integrity rules to [System administration](#system-administration); `{base}` is the scope prefix above:

| Method | Path | Description |
|---|---|---|
| `GET` `POST` | `{base}/statuses` · `/priorities` · `/issue-types` · `/fields` | List (visible, scope-tagged) / create at this scope |
| `PATCH` | `{base}/{catalog}/{id}` | Update an own-scope row |
| `POST` | `{base}/{catalog}/{id}/archive` · `/unarchive` | Archive / restore an own-scope row |
| `DELETE` | `{base}/{catalog}/{id}?replaceWithId=` (fields: `?dropValues=`) | Delete an own-scope row |
| `GET` | `{base}/{catalog}/{id}/usage` | Usage detail |
| `GET` `POST` `PATCH` `DELETE` | `{base}/workflows` · `/priority-sets` · `/field-sets` · `/issue-type-sets` [`/{id}`] | Reusable sets at this scope |

**Bindings**

| Method | Path | Role | Description |
|---|---|---|---|
| `GET` `PATCH` | `/api/workspaces/{wsId}/projects/{pId}/admin/bindings` | project `MANAGER` | Read / set this project's four bindings (`{"workflowId","prioritySetId","fieldSetId","issueTypeSetId"}`, null = system default) |
| `GET` | `/api/workspaces/{wsId}/projects/{pId}/admin/binding-options` | project `MANAGER` | Sets bindable to this project, per dimension — each `{id, name, scope}` |
| `GET` | `/api/workspaces/{wsId}/admin/projects` | workspace `OWNER`/`ADMIN` | Binding matrix for every project in the workspace |
| `PATCH` | `/api/workspaces/{wsId}/admin/projects/{pId}/bindings` | workspace `OWNER`/`ADMIN` | Set a project's bindings |
| `GET` | `/api/workspaces/{wsId}/admin/binding-options` | workspace `OWNER`/`ADMIN` | Sets bindable within the workspace (global ∪ workspace) |

## Issues

Issues live under a project and are addressed by **number** — the numeric part of their key (`DEMO-42` → `…/issues/42`). Numbers are sequential per project and never reused.

**Listing — dual shape.** Without `size`, `GET …/issues` returns the full unpaginated `IssueResponse` array (the board/kanban needs every card). Pass `size` to switch to a paginated [envelope](#conventions) (the backlog path); the optional `excludeDone=true` then drops issues in a DONE-category status server-side. The `statusId` / `assigneeId` / `priorityId` filters apply in both modes.

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues` | member | Create. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues?statusId=&assigneeId=&priorityId=&excludeDone=&page=&size=` | member | List with optional filters — **dual shape** (see below) |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Get one |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/history?page=&size=` | member | Field-level change history (paginated, oldest first) |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Partial update with optimistic locking |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | `MANAGER` | Delete issue + comments + attachments. `204` |

**Create** — `title`, `typeId` and `statusId` are required (the type must be offered by the project's type set, the status must belong to the project's [workflow](#project-configuration)); `priorityId` must be offered by the project's priority set and defaults to the set's default when omitted; `parentId` links a sub-task to a parent issue in the same project; `assigneeId` must be a workspace member. `fields` carries custom field values keyed by field id (value shapes per [field type](#project-configuration)) — required fields of the project's field set must be present, fields outside the set or archived are rejected with `422`:

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/issues \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "title": "Rate-limit authentication endpoints",
  "description": "Login accepts unlimited attempts…",
  "typeId": "…", "statusId": "…",
  "priorityId": "…",
  "assigneeId": "…",
  "dueDate": "2026-07-24",
  "fields": { "e1b2…": 5, "f3c4…": "critical" }
}'
```

```json
{
  "id": "…", "number": 18, "key": "DEMO-18",
  "title": "Rate-limit authentication endpoints",
  "description": "Login accepts unlimited attempts…",
  "type":   { "id": "…", "name": "Task", "color": "#3B82F6", "icon": "task", "position": 1 },
  "status": { "id": "…", "name": "To Do", "color": "#6B7280", "category": "TODO", "position": 0 },
  "priority": { "id": "…", "name": "High", "color": "#EA580C", "icon": "chevron-up", "position": 1 },
  "assignee": { "id": "…", "displayName": "Ada Lovelace", "avatarUrl": null },
  "reporter": { "id": "…", "displayName": "Ada Lovelace", "avatarUrl": null },
  "parentId": null,
  "dueDate": "2026-07-24",
  "fields": [ { "fieldId": "e1b2…", "value": 5 }, { "fieldId": "f3c4…", "value": "critical" } ],
  "version": 0,
  "createdAt": "…", "updatedAt": "…"
}
```

**Update & optimistic locking** — send any subset of `title`, `description`, `typeId`, `statusId`, `priorityId`, `assigneeId`, `dueDate`, `fields`, plus the `version` you last read. If the issue changed since, you get `409 Conflict` — re-fetch and retry. Omitting `version` skips the check (last write wins). Inside `fields` only the listed field ids change; JSON `null` clears a value (required fields cannot be cleared):

```bash
curl -X PATCH $BASE/workspaces/$WS/projects/$PROJ/issues/18 \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"statusId": "…", "version": 3}'
```

**History** is paginated (oldest first) — the [envelope](#conventions) wraps entries of this shape in `content`:

```json
{ "id": "…", "field": "status", "oldValue": "To Do", "newValue": "In Progress",
  "changedById": "…", "changedByName": "Ada Lovelace", "createdAt": "…" }
```

Custom field changes appear with the field's display name in `field` and human-readable values (option labels rather than ids, user display names, `yes`/`no` for checkboxes).

## Comments

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `…/issues/{number}/comments` | member | Create (`{"body"}`). `201` |
| `GET` | `…/issues/{number}/comments?page=&size=` | member | List (deleted comments excluded; paginated, oldest first) |
| `PATCH` | `…/issues/{number}/comments/{commentId}` | author | Edit (`{"body"}`) |
| `DELETE` | `…/issues/{number}/comments/{commentId}` | author | Soft delete. `204` |

`@DisplayName` mentions in a comment body notify the mentioned workspace members.

Listing is paginated (oldest first) — the [envelope](#conventions) wraps comments of this shape in `content`:

```json
{ "id": "…", "authorId": "…", "authorName": "Ada Lovelace", "body": "Looks good!",
  "createdAt": "…", "updatedAt": "…" }
```

## Attachments

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `…/issues/{number}/attachments` | member | Upload, `multipart/form-data` with a `file` field. `201` |
| `GET` | `…/issues/{number}/attachments` | member | List |
| `GET` | `…/issues/{number}/attachments/{attachmentId}` | member | Download (binary, `Content-Disposition: attachment`) |
| `DELETE` | `…/issues/{number}/attachments/{attachmentId}` | uploader / `MANAGER` | Delete file + metadata. `204` |

Uploads are validated in two ways: a **per-file size limit** (default 20 MB — `413 Payload Too Large` when exceeded; a separate, larger servlet ceiling rejects grossly oversized bodies) and a **file-extension allow-list** (images, PDF, common Office/text formats and `zip` by default — a disallowed extension returns `415 Unsupported Media Type`). The stored/returned `contentType` is derived from the filename on the server; the client-supplied content type is ignored.

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/issues/18/attachments \
  -H "Authorization: Bearer $TOKEN" -F "file=@screenshot.png"
```

```json
{ "id": "…", "filename": "screenshot.png", "sizeBytes": 48213, "contentType": "image/png",
  "uploadedById": "…", "uploadedByName": "Ada Lovelace", "createdAt": "…" }
```

## Notifications

The signed-in user's notification feed across all their workspaces (assignments, mentions, …).

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/notifications` | ✔ | List, newest first |
| `GET` | `/notifications/unread-count` | ✔ | `{"count": 3}` |
| `POST` | `/notifications/{id}/read` | ✔ | Mark one as read |
| `POST` | `/notifications/read-all` | ✔ | Mark all as read. `204` |

```json
{ "id": "…", "type": "ISSUE_ASSIGNED", "title": "You were assigned DEMO-18",
  "body": "Rate-limit authentication endpoints", "link": "/w/…/p/…?issue=18",
  "read": false, "createdAt": "…" }
```

## Real-time events (SSE)

Subscribe to a workspace's live event stream over [Server-Sent Events](https://developer.mozilla.org/docs/Web/API/Server-sent_events):

```
GET /workspaces/{wsId}/sse
Accept: text/event-stream
Authorization: Bearer <accessToken>
```

Event names: `ISSUE_CREATED`, `ISSUE_UPDATED`, `ISSUE_DELETED` (data: `{"projectId", "issueNumber"}`) and `NOTIFICATION`. The stream is workspace-scoped and requires membership; reconnect with standard `EventSource` retry semantics.

---

*This reference will grow with the product. Found a mismatch between docs and behavior? Please [open an issue](https://github.com/Zherikhov/easyTask/issues).*
