# Hamstrack REST API — DC (self-hosted)

> Deployment: **DC / self-hosted**. Using the hosted service? See the [Cloud reference](api-cloud.md).

This is the reference for the HTTP API behind the Hamstrack web app. Everything the UI does goes through this API, and it is available to users for scripting and integrations. An interactive Swagger UI version lives at `/docs` on your instance; the machine-readable OpenAPI spec is at `/openapi.yaml`.

**Base URL** — your instance's public URL (the `APP_BASE_URL` the operator configured) plus `/api`:

```
https://tracker.example.com/api
```

> **Beta notice:** the API is unversioned while Hamstrack is in beta — breaking changes are possible and are announced in release notes.

## Contents

- [Quick start](#quick-start)
- [Operator settings that affect the API](#operator-settings-that-affect-the-api)
- [Authentication](#authentication)
- [Conventions](#conventions)
- [Errors](#errors)
- [Roles](#roles)
- [Instance metadata](#instance-metadata)
- [Auth endpoints](#auth-endpoints)
- [Workspaces](#workspaces)
- [Projects](#projects)
- [Project configuration](#project-configuration)
- [System administration](#system-administration)
- [Issues](#issues)
- [Comments](#comments)
- [Attachments](#attachments)
- [Labels](#labels)
- [Components](#components)
- [Versions](#versions)
- [Search (HQL)](#search-hql)
- [Saved filters](#saved-filters)
- [Notifications](#notifications)
- [Real-time events (SSE)](#real-time-events-sse)

## Quick start

```bash
BASE=https://tracker.example.com/api   # your instance

# 1. Log in (accounts are created by an admin — see System administration —
#    or, if the operator enabled public signup, register + verify your email)
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

## Operator settings that affect the API

A self-hosted instance is configured through environment variables; a few of them change API behavior. Check `GET /meta` to discover how your instance is configured:

| Setting (env) | Default | API effect |
|---|---|---|
| `TERMS_ACCEPTANCE_REQUIRED` | `true` | When `false`, `termsAccepted` is optional at registration |
| `PUBLIC_SIGNUP_ENABLED` (`app.registration.public-signup-enabled`) | `false` (DC) | Self-registration is **closed by default on DC**: `POST /auth/register` returns `403` and accounts are created by the admin (see [System administration](#system-administration)). Set `true` to re-open public registration |
| `DEMO_SEED_ON_FIRST_LOGIN` | `true` | When `false`, no demo workspace is created on first login |
| `PUBLIC_LANDING_ENABLED` | `true` | When `false`, `robots.txt` disallows all crawling and `sitemap.xml` returns `404` |
| `JWT_ACCESS_TOKEN_TTL` | `PT30M` | Access-token lifetime (ISO-8601 duration). Reflected in the `expiresIn` field of login/refresh responses. Shorter = smaller replay window for a leaked token; the refresh cookie transparently renews it |
| `ATTACHMENT_MAX_FILE_SIZE` | `20MB` | Per-file business size limit enforced in-app (`413` when exceeded). Must stay ≤ `ATTACHMENT_MAX_UPLOAD_SIZE` |
| `ATTACHMENT_MAX_UPLOAD_SIZE` | `25MB` | Hard servlet ceiling for a multipart request (DoS guard, `413` when exceeded). Match your reverse-proxy body-size limit to this |
| `ATTACHMENT_ALLOWED_EXTENSIONS` | png,jpg,jpeg,gif,webp,bmp,svg,pdf,txt,csv,log,md,json,doc,docx,xls,xlsx,ppt,pptx,zip | Comma-separated, case-insensitive allow-list of uploadable file extensions (`415` for anything else) |
| `MAX_LABELS_PER_ISSUE` | `20` | Max labels accepted in one issue payload (`labelIds`, `422` beyond it) and max distinct `labelId` filter values on the issue list (`400` beyond it) |
| `MAX_LABELS_PER_WORKSPACE` | `1000` | Max labels in one workspace's [catalog](#labels); `POST /labels` beyond it returns `422` |
| `MAX_COMPONENTS_PER_PROJECT` | `500` | Max components in one project's [catalog](#components), **archived ones included**; `POST …/components` beyond it returns `422`. Valid range 1–100000 — an out-of-range value aborts startup instead of being clamped |
| `MAX_VERSION_LINKS_PER_ISSUE` | `20` | Max [versions](#versions) linked to one issue, enforced **per link type**: up to this many `fixVersionIds` **and** up to this many `affectsVersionIds` (independent budgets), `422` beyond it. Valid range 1–100 — an out-of-range value aborts startup instead of being clamped |
| `MAX_VERSIONS_PER_PROJECT` | `500` | Max versions in one project's [release plan](#versions), **archived and released ones included**; `POST …/versions` beyond it returns `422`. Valid range 1–100000 — an out-of-range value aborts startup instead of being clamped |
| `RATE_LIMIT_ENABLED` | `true` | Auth rate limiting (see [Rate limits](#rate-limits)) |
| `RATE_LIMIT_AUTH_IP_PER_MINUTE` / `RATE_LIMIT_LOGIN_FAILURE_THRESHOLD` / `RATE_LIMIT_LOGIN_BACKOFF_BASE_SECONDS` / `RATE_LIMIT_LOGIN_BACKOFF_MAX_SECONDS` | `15` / `5` / `30` / `900` | Rate-limit tuning |
| `APP_BASE_URL` | — | The `refresh_token` cookie is marked `Secure` only when this is an `https` URL |

## Authentication

Hamstrack uses short-lived **JWT access tokens** plus a rotating **refresh-token cookie**:

1. `POST /auth/register` → a verification email is sent.
2. `POST /auth/verify-email` with the emailed token → returns an access token (and logs you in).
3. `POST /auth/login` → returns an access token in the body and sets the `refresh_token` cookie (`HttpOnly`, `SameSite=Strict`, scoped to `/api/auth`; `Secure` when the instance runs on https).
4. Send the access token on every request:

   ```
   Authorization: Bearer <accessToken>
   ```

5. When the access token expires (`expiresIn` seconds, default 30 min — operators tune it via `JWT_ACCESS_TOKEN_TTL`), call `POST /auth/refresh` — the cookie authenticates the call and is **rotated** on each use (an old cookie value becomes invalid). Refresh tokens live 30 days.
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

  Currently paginated: [`GET /admin/users`](#system-administration), issue [comments](#comments) and [history](#issues), and the issue [list](#issues) **when `size` is passed**. The issue list is the deliberate exception — without `size` it returns a `BoardIssuesResponse` object (a server-capped list; the board needs every card, but not an unbounded one), switching to the envelope only when `size` is present.

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

Some conflicts carry an **extra machine-readable member** so a client can recover in one round-trip. Creating a [label](#labels) whose name is taken — or renaming one into a taken name — returns `409` with the id of the label that already owns it:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "A label named 'needs-design' already exists in this workspace",
  "existingId": "0198c4a1-…"
}
```

| Status | Meaning |
|---|---|
| `400` | Malformed request or failed validation |
| `401` | Missing/expired/invalid access token |
| `403` | Authenticated but not allowed (e.g. insufficient role) |
| `404` | Not found — or not a member of the containing workspace |
| `409` | Conflict: stale `version`, duplicate name/key, resource in use |
| `413` | Attachment exceeds the per-file size limit (default 20 MB) or the servlet upload ceiling (default 25 MB) |
| `415` | Attachment file extension is not in the allow-list |
| `422` | Semantically invalid reference (unknown status/type/assignee, workflow-forbidden transition) |
| `429` | Rate limited — wait the number of seconds in the `Retry-After` header |

### Rate limits

The sensitive auth endpoints (`login`, `register`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`) share a **per-IP budget** (default 15 requests per minute). Additionally, repeated failed logins for one account trigger an **exponential backoff** (defaults: starts at 30 s after 5 consecutive failures, doubles per failure, capped at 15 min); a successful login resets the counter. Both mechanisms respond with `429` and a `Retry-After` header (seconds). Operators tune or disable this via the `RATE_LIMIT_*` variables below. Counters are in-memory (per app node).

## Roles

**System role** (`ADMIN` — instance-wide, typically the instance operator; maintains the global taxonomy via [`/admin/**`](#system-administration)), **workspace roles** (`OWNER` > `ADMIN` > `MEMBER`) and **project roles** (`MANAGER` > `MEMBER` > `VIEWER`). The `seed.admin` account (env `SEED_ADMIN_*`) gets the system `ADMIN` role automatically; `GET /auth/me` returns your `systemRole`.

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
| Create a [label](#labels) and attach labels to issues | workspace member |
| Rename / recolor / describe a label | workspace `OWNER`/`ADMIN`, **or** the label's creator |
| Archive / unarchive / merge / delete a label | workspace `OWNER`/`ADMIN` |
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

Values reflect the [operator's configuration](#operator-settings-that-affect-the-api) — clients should read them instead of assuming defaults.

## Auth endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/auth/register` | — | Create an account; sends a verification email. `201`. **`403` when public signup is disabled** (the DC default — accounts are created by an admin instead, see [System administration](#system-administration)) |
| `POST` | `/auth/verify-email` | — | Exchange the emailed one-time token for a session |
| `POST` | `/auth/resend-verification` | — | Re-send the verification email (always `200`) |
| `POST` | `/auth/login` | — | Email + password → access token + refresh cookie |
| `POST` | `/auth/refresh` | cookie | Rotate the refresh token, get a fresh access token |
| `POST` | `/auth/logout` | cookie | Revoke the refresh token. `204` |
| `POST` | `/auth/forgot-password` | — | Send a reset link (always `200` — no account enumeration) |
| `POST` | `/auth/reset-password` | — | Set a password with a one-time token; revokes all sessions. Backs both the forgot-password email and admin-generated account setup links |
| `GET` | `/auth/me` | ✔ | The current user (`id`, `email`, `displayName`, `avatarUrl`, `systemRole`, `needsOnboarding`) |

> `needsOnboarding` is **always `false`** on DC — the first-login create-or-join-a-team flow is a Cloud feature (`app.onboarding.enabled`, off here). The invite-acceptance endpoints below still work if a workspace admin sends invites. `GET /invites` lists pending invites addressed to your email; `POST /invites/{id}/accept` · `/decline` accept/remove them by id (accept stays email-bound → `404` otherwise).

**Register** — available only when `publicSignupEnabled` is true (`GET /meta`); disabled by default on DC, where an admin creates accounts and shares a setup link. `termsAccepted: true` is required unless the operator disabled `TERMS_ACCEPTANCE_REQUIRED`:

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

Unverified accounts cannot log in (`403` until the email is verified). On first successful authentication the account is seeded with a demo workspace and project, unless the operator disabled `DEMO_SEED_ON_FIRST_LOGIN`.

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
  "issueTypes":  [ { "id": "…", "name": "Bug", "color": "#EF4444", "icon": "bug", "position": 0, "hierarchyLevel": 1 } ],
  "fields":      [ { "id": "…", "key": "severity", "name": "Severity", "type": "SELECT",
                     "config": { "options": [ { "id": "critical", "label": "Critical", "color": "#B91C1C" }, … ] },
                     "description": "Impact of the defect on users",
                     "required": false, "showOnCreate": true }, … ]
}
```

Transition semantics: a status with no source-specific rules is open (any move allowed); once it has rules, only its listed targets plus wildcard (`fromStatusId: null` = "from any") targets are accepted — a forbidden move returns `422` on issue updates and board drag-and-drop.

Custom field types and their JSON value shapes: `TEXT`/`TEXTAREA`/`URL` — string; `NUMBER` — number (`config.min`/`max` enforced); `DATE` — `"YYYY-MM-DD"` string; `SELECT` — option id string; `MULTI_SELECT` — array of option ids; `USER` — user UUID (must be a workspace member); `CHECKBOX` — boolean. A `required` field must be filled on create and can never be cleared; `showOnCreate: false` fields are only offered when editing.

Each issue type carries a `hierarchyLevel`: a parent must be **exactly one level above** its child (adjacent tiers only). The seeded taxonomy is `Epic` = 2, `Story`/`Task`/`Bug` = 1 and `Sub-task` = 0 — so an Epic can parent a Story/Task/Bug (but **not** a Sub-task directly), a Story/Task/Bug can parent a Sub-task, and a Sub-task can parent nothing. Use these levels to filter parent pickers and offer "create sub-task" (see [Issues](#issues)).

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
| `DELETE` | `/admin/fields/{id}?dropValues=` | Delete; `409` while issues hold values unless `dropValues=true` (drops them — there is no remap across value shapes; archive instead to keep them). Also `409` for a built-in system field (`isSystem: true` in the list response) — those can only be archived, not deleted |
| `GET/POST` | `/admin/field-sets` | List / create (`{"name", "items": [{"fieldId", "required", "showOnCreate"}]}` — a required field is always shown on create) |
| `PATCH/DELETE` | `/admin/field-sets/{id}` | Full replacement / delete (`409` while in use; the system default "No fields" set is not deletable) |
| `GET/POST` | `/admin/issue-type-sets` | List / create (`{"name", "typeIds": […]}` in display order; a set can never be empty) |
| `PATCH/DELETE` | `/admin/issue-type-sets/{id}` | Full replacement / delete (`409` while in use; the system default "All types" set is not deletable) |
| `GET` | `/admin/projects` | Assignment matrix: every project × its bindings |
| `PATCH` | `/admin/projects/{id}/bindings` | `{"workflowId", "prioritySetId", "fieldSetId", "issueTypeSetId"}` (null = system default); `409` when issues sit in statuses the new workflow lacks |
| `GET/POST` | `/admin/users` | List accounts (paginated `?page=&size=`, oldest first) / create (`{"email", "displayName", "systemRole?"}`). No password or email — the `201` response is `{"user", "setupLink"}`; hand the one-time `setupLink` (`/reset-password?token=`, valid 7 days) to the person. On DC this is the primary way to onboard users |
| `POST` | `/admin/users/{id}/setup-link` | Regenerate the one-time setup link → `{"setupLink"}` |
| `PATCH` | `/admin/users/{id}` | Change `systemRole` (`ADMIN`/`USER`) and/or `status` (`ACTIVE`/`DISABLED`); `409` when it would disable/demote the last active admin or your own account (disabling revokes the user's refresh tokens) |

Integrity rules: deletions never leave dangling references (remap or `409`), no workflow can end up empty, every priority set keeps a default, and a workflow change is refused while it would strand issues in statuses invisible to the board.

## Delegated administration

The same catalog and binding operations are available at two **delegated** scopes so teams self-serve without the system administrator. Authorization is membership-based (not the system `ADMIN` role):

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

**Onboarding users (DC).** Since self-registration is closed by default, create each account via `POST /admin/users` and share the returned `setupLink`. The recipient opens it, chooses a password (`POST /auth/reset-password` under the hood), then signs in normally. No SMTP is required for this flow.

## Issues

Issues live under a project and are addressed by **number** — the numeric part of their key (`DEMO-42` → `…/issues/42`). Numbers are sequential per project and never reused.

**Hierarchy.** An issue may have a parent in the same project, governed by issue-type [hierarchy levels](#project-configuration) (a parent's type level must be strictly greater than the child's). Every `IssueResponse` carries the parent summary (`parentId`, `parentKey`, `parentTitle`, `parentTypeId`, all `null` when there is no parent) and a direct-children roll-up (`childCount`, and `doneChildCount` for children in a DONE-category status). `GET …/issues/{number}/children` lists the direct children in board order.

**Listing — dual shape.** Without `size`, `GET …/issues` returns a `BoardIssuesResponse` object (the board/kanban path): `{ "issues": IssueResponse[], "truncated": boolean, "totalAvailable": integer, "cap": integer }`. `issues` is bounded server-side to `cap` (default 500, never client-overridable); when the project after the same filters exceeds it, `truncated` is `true` and `totalAvailable` reports the full count so the UI can show "Showing first {cap} of {totalAvailable}". Pass `size` to switch to a paginated [envelope](#conventions) (the backlog path); the optional `excludeDone=true` then drops issues in a DONE-category status server-side. The `statusId` / `assigneeId` / `priorityId` / `componentId` / `labelId` / `fixVersionId` filters apply in both modes and are ANDed together.

**Filtering by label.** The [label](#labels) filter is applied **server-side** in both modes (it has to search the whole project, not just the capped page already on screen): repeat `labelId` once per label and choose how they combine with `labelMatch` — `any` (default, carries at least one) or `all` (carries every one), lowercase. Any other `labelMatch` value is a `400`, as is passing more than 20 distinct `labelId` values (the per-issue label limit, `MAX_LABELS_PER_ISSUE`, 20 by default).

**Filtering by component.** Same rule, simpler shape: `componentId` is a single optional uuid, applied **server-side** and ANDed with everything above. A [component](#components) id belonging to another project simply matches nothing — it is never an error.

**Filtering by fix version.** Likewise: `fixVersionId` is a single optional uuid, applied **server-side** and ANDed with everything above. It matches the **fix** role only — an *affects* link to the same [version](#versions) does not match a "fix version" filter. An id from another project simply matches nothing.

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/issues?labelId=$L1&labelId=$L2&labelMatch=all" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$BASE/workspaces/$WS/projects/$PROJ/issues?componentId=$COMP" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$BASE/workspaces/$WS/projects/$PROJ/issues?fixVersionId=$VER" \
  -H "Authorization: Bearer $TOKEN"
```

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues` | member | Create. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues?statusId=&assigneeId=&priorityId=&componentId=&labelId=&labelMatch=&fixVersionId=&excludeDone=&page=&size=` | member | List with optional filters — **dual shape** (see above) |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Get one |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/children` | member | Direct children of the issue, in board order |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/history?page=&size=` | member | Field-level change history (paginated, oldest first) |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Partial update with optimistic locking |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | `MANAGER` | Delete issue + comments + attachments. `204` |

**Create** — `title`, `typeId` and `statusId` are required (the type must be offered by the project's type set, the status must belong to the project's [workflow](#project-configuration)); `priorityId` must be offered by the project's priority set and defaults to the set's default when omitted; `parentId` links the issue to a parent in the same project — rejected with `422` if the parent is unknown, in another project, or its type's [hierarchy level](#project-configuration) is not strictly greater than this issue's type level; `assigneeId` must be a workspace member. `fields` carries custom field values keyed by field id (value shapes per [field type](#project-configuration)) — required fields of the project's field set must be present, fields outside the set or archived are rejected with `422`. `labelIds` attaches workspace [labels](#labels) (duplicates are de-duped); an unknown, foreign-workspace or archived label id — or more than `MAX_LABELS_PER_ISSUE` distinct ids (20 by default) — is rejected with `422`. `componentId` files the issue under a [component](#components) of this project; an unknown, foreign-project or archived component is a `422`, and when the component has auto-assign the lead may become the assignee (see [auto-assign](#components)). `fixVersionIds` and `affectsVersionIds` link [versions](#versions) of this project in the two independent roles — "ships in" and "is broken in"; an unknown, foreign-project or archived version, or more than `MAX_VERSION_LINKS_PER_ISSUE` distinct ids (20 by default) **per link type**, is a `422` (linking an already-*released* version is allowed on purpose):

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/issues \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "title": "Rate-limit authentication endpoints",
  "description": "Login accepts unlimited attempts…",
  "typeId": "…", "statusId": "…",
  "priorityId": "…",
  "assigneeId": "…",
  "dueDate": "2026-07-24",
  "labelIds": ["0198c4a1-…"],
  "componentId": "0198d5b2-…",
  "fixVersionIds": ["0198e6c3-…"],
  "affectsVersionIds": ["0198e6c3-…"],
  "fields": { "e1b2…": 5, "f3c4…": "critical" }
}'
```

```json
{
  "id": "…", "number": 18, "key": "DEMO-18",
  "title": "Rate-limit authentication endpoints",
  "description": "Login accepts unlimited attempts…",
  "type":   { "id": "…", "name": "Task", "color": "#3B82F6", "icon": "task", "position": 1, "hierarchyLevel": 1 },
  "status": { "id": "…", "name": "To Do", "color": "#6B7280", "category": "TODO", "position": 0 },
  "priority": { "id": "…", "name": "High", "color": "#EA580C", "icon": "chevron-up", "position": 1 },
  "assignee": { "id": "…", "displayName": "Ada Lovelace", "avatarUrl": null },
  "reporter": { "id": "…", "displayName": "Ada Lovelace", "avatarUrl": null },
  "parentId": null,
  "parentKey": null,
  "parentTitle": null,
  "parentTypeId": null,
  "childCount": 0,
  "doneChildCount": 0,
  "dueDate": "2026-07-24",
  "labels": [ { "id": "0198c4a1-…", "name": "needs-design", "color": "#0EA5A4", "archived": false } ],
  "component": { "id": "0198d5b2-…", "name": "Billing", "archived": false },
  "fixVersions": [ { "id": "0198e6c3-…", "name": "2.4.0", "released": false, "archived": false } ],
  "affectsVersions": [ { "id": "0198e6c3-…", "name": "2.3.1", "released": true, "archived": false } ],
  "fields": [ { "fieldId": "e1b2…", "value": 5 }, { "fieldId": "f3c4…", "value": "critical" } ],
  "version": 0,
  "createdAt": "…", "updatedAt": "…"
}
```

**Update & optimistic locking** — send any subset of `title`, `description`, `typeId`, `statusId`, `priorityId`, `assigneeId`, `dueDate`, `labelIds`, `componentId`, `fixVersionIds`, `affectsVersionIds`, `fields`, plus the `version` you last read. If the issue changed since, you get `409 Conflict` — re-fetch and retry. Omitting `version` skips the check (last write wins). To unset a nullable core field send `clearAssignee: true` / `clearDueDate: true` — a plain `null` can't be told apart from an omitted field (ignored when the id/date is also given). A `parentId` sets or changes the parent and `clearParent: true` detaches it (same convention); an illegal parent — unknown, in another project, self, a cycle, a [hierarchy-level](#project-configuration) violation, or a type change that conflicts with an existing parent or child edge — returns `422`. `labelIds`, `fixVersionIds` and `affectsVersionIds` are the **full-replacement** fields: when present the issue ends up carrying exactly those labels / versions-in-that-role, `[]` removes them all, and omitting one leaves it untouched (no clear-flag needed — `[]` is unambiguous); an already-attached archived label or version may stay, it just cannot be added. The two version roles are independent, so sending only `fixVersionIds` never touches the affects set, and the `MAX_VERSION_LINKS_PER_ISSUE` cap is counted separately per role. `componentId` sets or changes the [component](#components) and `clearComponent: true` unsets it (the `assigneeId`/`clearAssignee` convention again); attaching an unknown, foreign-project or **archived** component is a `422`, though an issue already carrying an archived one stays editable — and component auto-assign never fires on an update. Inside `fields` only the listed field ids change; JSON `null` clears a value (required fields cannot be cleared):

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

Custom field changes appear with the field's display name in `field` and human-readable values (option labels rather than ids, user display names, `yes`/`no` for checkboxes). A [label](#labels) change is recorded once under `labels`, with the label names before and after comma-joined (a label **merge** deliberately writes no per-issue history — it can touch thousands of issues). A [component](#components) change is recorded under `component` with the old and new component names (a forced component **delete** writes no per-issue history either, for the same reason). [Version](#versions) changes are recorded per role, under `fixVersions` and `affectsVersions`, with the names before and after comma-joined — and, for the same "one request must stay bounded" reason, a version **delete** (forced or remapped) and a release-time `moveUnresolvedToVersionId` write no per-issue history.

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

Uploads are validated in two ways: a **per-file size limit** (default 20 MB, `ATTACHMENT_MAX_FILE_SIZE` — `413 Payload Too Large` when exceeded; a separate, larger servlet ceiling `ATTACHMENT_MAX_UPLOAD_SIZE`, default 25 MB, rejects grossly oversized bodies) and a **file-extension allow-list** (`ATTACHMENT_ALLOWED_EXTENSIONS` — images, PDF, common Office/text formats and `zip` by default; a disallowed extension returns `415 Unsupported Media Type`). The stored/returned `contentType` is derived from the filename on the server; the client-supplied content type is ignored. See [Operator settings](#operator-settings-that-affect-the-api).

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/issues/18/attachments \
  -H "Authorization: Bearer $TOKEN" -F "file=@screenshot.png"
```

```json
{ "id": "…", "filename": "screenshot.png", "sizeBytes": 48213, "contentType": "image/png",
  "uploadedById": "…", "uploadedByName": "Ada Lovelace", "createdAt": "…" }
```

## Labels

Free-form, colored tags that live in the **workspace** and are reusable across all of its projects. Labels are *content*, not configuration: they never appear in a project's [config](#project-configuration), and any workspace member can mint one on the spot. Every endpoint requires workspace membership — a non-member, or an unknown workspace, gets `404` (never `403`); `403` is reserved for a member who lacks the curation role.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/labels?includeArchived=false&withUsage=false` | member | The workspace's labels, ordered by name |
| `POST` | `/workspaces/{wsId}/labels` | member | Create a label. `201` |
| `PATCH` | `/workspaces/{wsId}/labels/{labelId}` | `OWNER`/`ADMIN` or creator | Rename / recolor / describe |
| `POST` | `/workspaces/{wsId}/labels/{labelId}/archive` | `OWNER`/`ADMIN` | Archive (allowed even while in use) |
| `POST` | `/workspaces/{wsId}/labels/{labelId}/unarchive` | `OWNER`/`ADMIN` | Restore an archived label |
| `POST` | `/workspaces/{wsId}/labels/{labelId}/merge` | `OWNER`/`ADMIN` | Merge other labels into this one |
| `DELETE` | `/workspaces/{wsId}/labels/{labelId}?force=false` | `OWNER`/`ADMIN` | Delete. `204` |
| `GET` | `/workspaces/{wsId}/labels/{labelId}/usage` | member | `{"issueCount": 12}` |

**List** — ordered by name (case-insensitive). Archived labels are hidden unless `includeArchived=true`. `issueCount` is `null` unless you ask for `withUsage=true` (one grouped query for the whole list, so the picker doesn't pay for counts it won't show):

```json
{ "id": "0198c4a1-…", "name": "needs-design", "color": "#0EA5A4",
  "description": "Blocked on a design decision", "archived": false,
  "createdById": "…", "createdByName": "Ada Lovelace",
  "issueCount": 12, "createdAt": "…", "updatedAt": "…" }
```

**Create** — the body is `{"name", "color?", "description?"}` and **any workspace member** may post it (self-serve tagging is the point):

- `name` is required and normalized server-side — Unicode NFC, invisible control/format characters stripped, surrounding whitespace trimmed, internal whitespace runs collapsed to a single space. Spaces are allowed. The normalized result must be 1–60 characters, else `400`.
- Names are **unique per workspace, case-insensitively**, and archived labels keep their name slot — a collision is a `409` (see below). Casing is preserved for display.
- `color` is `#RRGGBB` or `#RRGGBBAA` (`400` otherwise). Omit it and the server picks a deterministic swatch derived from the name, so the same name always looks the same.
- `description` is optional, max 200 chars, blank is stored as `null`.
- A workspace that has reached its label limit (`MAX_LABELS_PER_WORKSPACE`, 1000 by default) returns `422` — archive or delete some first. Archived labels count toward the cap (they keep their name slot).

```bash
curl -X POST $BASE/workspaces/$WS/labels \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "needs-design", "color": "#0EA5A4"}'
```

**Duplicate names (`409` + `existingId`)** — both on create and on a rename that races another one, the conflict body carries the id of the label that already owns the name, so a client can attach the existing label instead of re-listing the catalog:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "A label named 'needs-design' already exists in this workspace",
  "existingId": "0198c4a1-…" }
```

An **archived** label produces the same `409` (with `detail` pointing at it) — unarchive or rename it rather than creating a twin.

**Update** — `PATCH` takes `{"name?", "color?", "description?"}`; `null`/omitted fields are left unchanged. Allowed to a workspace `OWNER`/`ADMIN` **or the label's own creator**; another member gets `403`. Changing only the casing of the name keeps the same unique slot; any other rename onto a taken name is the `409` above.

**Archive vs delete** — archiving is the recommended, non-destructive option and is always allowed, even while the label is in use: it disappears from pickers and from the default catalog listing, existing attachments survive (and come back on the issue with `"archived": true` so a client can dim them), and it can no longer be added to an issue (`422`). `DELETE` is `OWNER`/`ADMIN` only and returns `409` while the label is attached to any issue, unless you pass `force=true`, which detaches it everywhere and then deletes it. There is no remap-on-delete — use merge for that.

**Merge** — `POST …/labels/{targetId}/merge` with `{"sourceIds": ["…", "…"]}` folds the sources **into** the label in the path: every issue carrying a source ends up carrying the target, duplicates collapse, and the source labels are deleted. `sourceIds` must be non-empty and at most 50 (`400`); a source equal to the target, or one that doesn't resolve inside this workspace, is a `422`. Merge (and a forced delete) also re-points issues in **archived** projects — the label catalog is workspace-level metadata above the per-project freeze.

```json
{ "targetId": "0198c4a1-…", "mergedLabelCount": 2, "reassignedIssueCount": 37 }
```

No per-issue history is written for a merge (it can touch thousands of issues) — this response, plus the target's bumped `updatedAt`, is the record.

**Using labels** — attach them with `labelIds` when creating or updating an [issue](#issues) (full-replacement set), filter the board/backlog with `?labelId=&labelMatch=`, and query them in [HQL](#search-hql) as `label` (alias `labels`). An issue's own labels come back in `IssueResponse.labels` as `{id, name, color, archived}`, ordered by name, `[]` when there are none. An issue may carry at most `MAX_LABELS_PER_ISSUE` labels (20 by default).

## Components

Curated modules of a **single project** — "Billing", "Mobile app", "API" — each with an optional **lead** and an optional create-time **auto-assign**. Like labels they are *content*, not configuration: they never appear in a project's [config](#project-configuration). Unlike labels they are project-owned (two projects may each have their own "Billing") and only curators may edit the catalog. An issue carries at most one component.

Reading needs project membership; writing needs the **project curator** role — project `MANAGER`, **or** an `OWNER`/`ADMIN` of the enclosing workspace (who need not be a project member). A missing workspace, a missing project or a non-member all give `404`, never `403`; `403` is reserved for a member without the curation role.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/components?includeArchived=false&withUsage=false` | member | The project's components, ordered by name |
| `POST` | `/workspaces/{wsId}/projects/{pId}/components` | curator | Create a component. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}` | curator | Rename / re-lead / describe / toggle auto-assign |
| `POST` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}/archive` | curator | Archive (allowed even while in use) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}/unarchive` | curator | Restore an archived component |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}?force=false` | curator | Delete. `204` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}/usage` | member | `{"issueCount": 12}` |

**List** — ordered by name (case-insensitive). Archived components are hidden unless `includeArchived=true`. `issueCount` is `null` unless you ask for `withUsage=true` (one grouped query for the whole list, so the picker doesn't pay for counts it won't show); it is also `null` on `GET …/components/{componentId}`, which is what the `/usage` endpoint is for:

```json
{ "id": "0198d5b2-…", "name": "Billing",
  "description": "Invoicing, plans and payment provider glue",
  "leadId": "…", "leadName": "Ada Lovelace", "leadAvatarUrl": null,
  "autoAssign": true, "archived": false,
  "issueCount": 12, "createdAt": "…", "updatedAt": "…" }
```

**Create** — the body is `{"name", "description?", "leadId?", "autoAssign?"}`:

- `name` is required and normalized server-side — Unicode NFC, invisible control/format characters stripped, surrounding whitespace trimmed, internal whitespace runs collapsed to a single space. The normalized result must be 1–80 characters, else `400` (the payload itself is additionally capped at 200 characters as a cheap guard).
- Names are **unique per project, case-insensitively**, and archived components keep their name slot — a collision is a `409`. Casing is preserved for display. The same name in a *different* project is perfectly fine.
- `description` is optional, max 500 chars (`400` beyond), blank is stored as `null`.
- `leadId` is optional and must be a **member of this workspace** — anything else (unknown user, another tenant's user, a former member) is `422 "Unknown lead"`.
- `autoAssign` defaults to `false` and requires a lead: turning it on with none is `422`.
- A project that has reached its component limit (`MAX_COMPONENTS_PER_PROJECT`, 500 by default) returns `422` — archive or delete some first. **Archived components count toward the cap** (they keep their unique name slot and still show up under `includeArchived=true`), so create → archive → repeat cannot get you past it.

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/components \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Billing", "leadId": "…", "autoAssign": true}'
```

**Duplicate names (`409`)** — on create, and on a rename that races another one:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "A component named 'Billing' already exists in this project" }
```

An **archived** component holding the name produces the same `409` (`"An archived component already uses this name — unarchive or rename it"`). Unlike a [label](#labels) conflict there is **no** `existingId` extension — components have no on-the-fly create to recover from, so the client just re-reads the catalog.

**Update** — `PATCH` takes `{"name?", "description?", "leadId?", "clearLead?", "autoAssign?"}`; `null`/omitted fields are left unchanged. `leadId` sets the lead and `clearLead: true` unsets it (the `assigneeId`/`clearAssignee` convention — a plain `null` can't be told apart from an omitted field). Changing only the casing of the name keeps the same unique slot; any other rename onto a taken name is the `409` above. The *resulting* state must still satisfy "auto-assign needs a lead", so clearing the lead of an auto-assigning component is `422` unless you turn `autoAssign` off in the same call.

**Auto-assign** — when an issue is **created** with a component that has `autoAssign` **and** a lead, and the request supplies no `assigneeId`, the issue is assigned to that lead. An explicit `assigneeId` always wins. A lead who has since left the workspace is skipped silently (the issue is simply created unassigned — a stale lead must never fail issue creation). Auto-assign **never** fires on update: changing an issue's component later must not silently reassign someone's work.

**Archive vs delete** — archiving is the recommended, non-destructive option and is always allowed, even while the component is in use: it disappears from pickers and from the default catalog listing, existing assignments survive (and come back on the issue with `"archived": true` so a client can dim them), it can no longer be attached to an issue (`422`), and it is reversible. `DELETE` returns `409` while the component is on any issue (the message states the count) unless you pass `force=true`, which **clears the component on every issue that carried it** and then deletes the row. There is no remap-to-another-component, and neither operation writes per-issue history — a forced delete can touch thousands of issues.

**Archived projects.** Every management call (create, update, archive, unarchive, delete) on a component of an **archived project** returns `409 "Project is archived"` — the catalog is frozen exactly as issue edits are. Reads keep working.

**Using components** — set `componentId` when creating or updating an [issue](#issues) (`clearComponent: true` unsets it), filter the board/backlog with `?componentId=`, and query them in [HQL](#search-hql) as `component` (alias `components`). An issue's own component comes back in `IssueResponse.component` as `{id, name, archived}`, or `null`.

## Versions

A **single project's** release plan — "2.4.0", "Sprint 12 release" — with a fully reversible lifecycle. Each version can be linked to issues in two independent roles: **fix** ("this change ships in that release") and **affects** ("this defect exists in that release"). Like labels and components they are *content*, not configuration: they never appear in a project's [config](#project-configuration). Two projects may each own a "2.4.0".

Reading needs project membership; writing needs the **project curator** role — project `MANAGER`, **or** an `OWNER`/`ADMIN` of the enclosing workspace (who need not be a project member). A missing workspace, a missing project or a non-member all give `404`, never `403`; `403` is reserved for a member without the curation role.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/versions?includeArchived=false&includeReleased=true` | member | The project's release plan, in Releases-page order |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions` | curator | Create a version. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}` | curator | Rename / describe / re-plan the date |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/release` | curator | Ship it (body optional) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/unrelease` | curator | Undo a release (nothing is lost) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/archive` | curator | Archive (allowed even while in use) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/unarchive` | curator | Restore an archived version |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}?force=false&remapToId=` | curator | Delete. `204` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/usage` | member | Fix / affects / unresolved counts |

**List** — ordered the way a release page reads: **unreleased first**, then by `releaseDate` ascending with undated versions last, then by name (case-insensitive). Archived versions are hidden unless `includeArchived=true`; released ones are **included by default** (`includeReleased=true` — a release page without its shipped releases is useless), so pass `includeReleased=false` for a "what is still open" picker. Unlike labels and components there is no `withUsage` flag: the three counters are **always** filled, because they cost one grouped query for the whole list.

```json
{ "id": "0198e6c3-…", "name": "2.4.0",
  "description": "Billing rewrite + the new search bar",
  "releaseDate": "2026-09-01", "released": false, "releasedAt": null,
  "archived": false,
  "issueCount": 24, "doneIssueCount": 18, "affectsIssueCount": 3,
  "createdAt": "…", "updatedAt": "…" }
```

`issueCount` and `doneIssueCount` count **fix** links only (they are the progress bar's "18 / 24"); `affectsIssueCount` counts the affects links, which are triage information rather than progress.

**Create** — the body is `{"name", "description?", "releaseDate?"}`:

- `name` is required and normalized server-side — Unicode NFC, invisible control/format characters stripped, surrounding whitespace trimmed, internal whitespace runs collapsed to a single space. The normalized result must be 1–60 characters, else `400` (the payload itself is additionally capped at 200 characters as a cheap guard).
- Names are **unique per project, case-insensitively**, and archived versions keep their name slot — a collision is a `409` (`"An archived version already uses this name — unarchive or rename it"`). Casing is preserved for display. The same name in a *different* project is fine. As with [components](#components) there is **no** `existingId` extension on the conflict body.
- `description` is optional, max 500 chars (`400` beyond), blank is stored as `null`.
- `releaseDate` is optional and is only **the plan** — a version is always created unreleased. It must fall within `1000-01-01`…`9999-12-31`; anything outside that is a `422` (not a `400` like the name/length failures — the field is well-formed, its value is simply not storable).
- A project that has reached its version limit (`MAX_VERSIONS_PER_PROJECT`, 500 by default) returns `422` — delete some first. **Archived and released versions count toward the cap** (they keep their unique name slot and still show up under `includeArchived=true`), so create → archive → repeat cannot get you past it, and archiving does not free a slot.

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/versions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "2.4.0", "releaseDate": "2026-09-01"}'
```

**Update** — `PATCH` takes `{"name?", "description?", "releaseDate?", "clearReleaseDate?"}`; `null`/omitted fields are left unchanged. `releaseDate` sets the planned date and `clearReleaseDate: true` unsets it (the `assigneeId`/`clearAssignee` convention — a plain `null` can't be told apart from an omitted field); it is bounds-checked exactly as on create (`422` outside `1000-01-01`…`9999-12-31`). Changing only the casing of the name keeps the same unique slot; any other rename onto a taken name is the `409` above. There is deliberately **no** `released` field here — the lifecycle moves only through the two calls below.

**Release** — `POST …/versions/{versionId}/release`, body **optional**: a bare `POST` (or `{}`) means "release it now, keeping the planned date". If neither the body nor the stored version carries a `releaseDate`, the server stamps **today** (server UTC date).

- Releasing a version that still has unresolved issues is **allowed on purpose** — a release with open work is a real, common state, and blocking it would only push teams to falsify the tracker.
- `moveUnresolvedToVersionId` optionally re-points the **fix** links of every issue in a non-DONE status to another version first; issues already in a DONE-category status stay on the released version. The target must be another version **of the same project**, **unreleased** and non-archived — anything else (including the version being released itself) is a `422`. Open work must never be moved onto a shipped release.
- A supplied `releaseDate` is bounds-checked like everywhere else (`422` outside `1000-01-01`…`9999-12-31`).
- Releasing an **already-released** version is a `409`, not a silent success: the flip is a conditional update, so a double-click can never run the destructive move twice.
- A `moveUnresolvedToVersionId` remap can also lose a race: if a concurrent issue edit adds the target version to one of the very issues being re-pointed, the re-created link collision surfaces as a **retryable `409`** ("Another change added this version to one of the same issues — retry"). The whole release is rolled back with it, so nothing is half-applied — just retry.

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/versions/$VER/release \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"moveUnresolvedToVersionId": "0198e6c4-…"}'
```

**Un-release** — `POST …/versions/{versionId}/unrelease` clears `released` and `releasedAt` but **preserves `releaseDate`** (it goes back to being the plan), so the round-trip loses no data. Un-releasing something that is not released is a `409`. Note that issues moved away by a `moveUnresolvedToVersionId` are *not* moved back — that was a separate, explicit action.

**Archive vs delete** — archiving is the recommended, non-destructive option, is always allowed even while the version is in use, and is orthogonal to `released` (archiving old shipped releases is how the list stays short): the version leaves every picker and the default listing, existing links survive (and come back on the issue with `"archived": true` so a client can dim them), it can no longer be linked to an issue (`422`), and it is reversible.

`DELETE` returns `409` while the version is linked to any issue in **either** role (the message states the count), unless you pass one of two escape hatches:

- `force=true` — delete every fix and affects link to it, then delete the version;
- `remapToId=<versionId>` — re-point every link of **both** roles to another version of the same project, then delete. The target must not be the version being deleted and must not be archived (`422` otherwise); a **released** target *is* allowed here — back-porting a deleted version's links onto a shipped release is legitimate bookkeeping, which is why this differs from `moveUnresolvedToVersionId`.

**When both are supplied, `remapToId` wins** — it is the strictly more specific, non-destructive intent. A released version may be deleted like any other. Neither path writes per-issue history (a delete can touch thousands of issues). A `remapToId` delete can lose the same race as a release-time move: a concurrent issue edit that re-creates a link collision mid-remap makes the call fail with a **retryable `409`**, and the delete is rolled back with it — the version is still there and its links are untouched.

**Archived projects.** Every management call (create, update, release, un-release, archive, unarchive, delete) on a version of an **archived project** returns `409 "Project is archived"` — the release plan is frozen exactly as issue edits are. Reads keep working.

**Usage** — `GET …/versions/{versionId}/usage` backs the delete-confirmation dialog and the release dialog's "move N unresolved issues to →" prompt. `unresolvedFixIssueCount` is exactly what `moveUnresolvedToVersionId` would move:

```json
{ "fixIssueCount": 24, "affectsIssueCount": 3, "unresolvedFixIssueCount": 6 }
```

**Using versions** — set `fixVersionIds` / `affectsVersionIds` when creating or updating an [issue](#issues) (full-replacement sets, one per role), filter the board/backlog with `?fixVersionId=`, and query them in [HQL](#search-hql) as `fixVersion` and `affectsVersion`. An issue's own versions come back in `IssueResponse.fixVersions` and `IssueResponse.affectsVersions` as `{id, name, released, archived}`, ordered by name, `[]` when there are none. An issue may carry at most `MAX_VERSION_LINKS_PER_ISSUE` versions **per role** (20 by default).

## Search (HQL)

Search issues across a whole workspace with **HQL** (Hamstrack Query Language) — a small, readable query language. Results are cross-project but always restricted to the caller's **visible (non-archived) projects**; this scope is enforced server-side and no query text can widen it. All three endpoints require workspace membership (`404` for a non-member).

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/search` | member | Run an HQL query. Paginated ([envelope](#conventions), `size` clamped `1`–`100`) |
| `GET` | `/workspaces/{wsId}/search/schema` | member | Autocomplete metadata: fields, per-field operators, and value picklists |
| `GET` | `/workspaces/{wsId}/search/suggest?field=&q=` | member | Typeahead for user-valued fields (`assignee`/`reporter` or a USER custom field) and for `label` / `component` / `fixVersion` / `affectsVersion`, capped at 20 |

**Query** — the body is `{"query", "page?", "size?"}`. `query` is the HQL string (max 2000 chars — a longer one is rejected at binding with `400`); an empty or omitted `query` matches all visible issues in the default sort.

```bash
curl -X POST $BASE/workspaces/$WS/search \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "query": "status = \"In Progress\" AND assignee = currentUser() ORDER BY priority DESC",
  "page": 0, "size": 50
}'
```

Each row in the paginated `content` reuses the full [`IssueResponse`](#issues) plus its owning project's identity (results are cross-project):

```json
{ "issue": { "id": "…", "number": 18, "key": "DEMO-18", … },
  "projectId": "…", "projectKey": "DEMO", "projectName": "Demo Project" }
```

**HQL grammar** — a boolean expression of `field OP value` comparisons:

- **Fields:** `status`, `assignee`, `reporter`, `type`, `priority`, `created`, `updated`, `due`, `parent`, `text`, `label` (alias `labels`), `component` (alias `components`), `fixVersion`, `affectsVersion`, plus any **custom field** by its `key` (e.g. `story_points`, `severity`). The exact list you may query comes from `/search/schema`. Field names are matched case-insensitively, so the all-lowercase `fixversion` / `affectsversion` spellings work too — `/search/schema` always advertises the camelCase name, exactly once per field.
- **Operators:** `=` `!=` `IN` `~` `>` `<` `>=` `<=`. Which operators a field accepts is per-field (see `/search/schema`): `~` is a case-insensitive substring match, only on `text` (title + description); ordered comparisons (`>` `<` `>=` `<=`) apply to the date fields and to `priority`.
- **Booleans:** combine terms with `AND`, `OR`, `NOT` and parentheses `( )`. Precedence is `NOT` > `AND` > `OR`.
- **Emptiness:** `field IS [NOT] EMPTY` for nullable fields (`assignee`, `parent`, `due`, `label`, `component`, `fixVersion`, `affectsVersion`, and every custom field except CHECKBOX).
- **Sorting:** an optional trailing `ORDER BY field [ASC|DESC], …` clause — it must come last. Custom fields are **not sortable** (yet).
- **Functions:** `currentUser()`, `now()`, `startOfWeek()` (evaluated in server UTC).
- **Values:** status / type / priority are given by **name** (quote names with spaces, e.g. `"In Progress"`); users by email, display name, `currentUser()`, or UUID; dates as `YYYY-MM-DD`.
- **Labels:** `label` (alias `labels`) is many-valued and given by **name** — operators `= != IN` plus `IS [NOT] EMPTY`; it is **not sortable** (an issue has a *set* of labels, so `ORDER BY label` is a `422`). `label = "needs-design"` means "carries it", `label != "needs-design"` means "does not carry it" (issues with no labels at all included), and `label IS EMPTY` means "carries no label". Archived labels are excluded from name resolution, so a name that no longer exists in the workspace fails with `422` when the query runs — including for a stored [saved filter](#saved-filters).
- **Components:** `component` (alias `components`) is single-valued and given by **name** — operators `= != IN` plus `IS [NOT] EMPTY` — and, unlike `label`, it **is sortable** (`ORDER BY component` orders by component name and keeps issues that have none). Names resolve across your visible **projects**, so `component = Billing` matches the "Billing" of every project you can see. `component IS EMPTY` means "has no component". Archived components are excluded from name resolution (a name that only an archived component holds is a `422` at run time), even though issues keep carrying them.
- **Versions:** `fixVersion` ("ships in") and `affectsVersion` ("broken in") are two fields over the same link table, told apart by the role. Both are many-valued and given by **name** — operators `= != IN` plus `IS [NOT] EMPTY` — and, like `label`, **not sortable** (an issue has a *set* of versions, so `ORDER BY fixVersion` is a `422`). `fixVersion = "2.4.0"` means "ships in 2.4.0", `fixVersion != "2.4.0"` means "does not" (issues with no fix version at all included), and `fixVersion IS EMPTY` means "not scheduled for any release" — the unassigned-work query. An affects link never satisfies a `fixVersion` term and vice versa. Names resolve across your visible **projects**, so `fixVersion = "2.4.0"` matches the "2.4.0" of every project you can see. Archived versions are excluded from name resolution (a `422` at run time), even though issues keep carrying them.
- **Custom fields:** queried by `key`, with operators per type — TEXT / TEXTAREA / URL: `= != ~`; NUMBER / DATE: `= != > < >= <=`; SELECT: `= != IN`; MULTI_SELECT: `=` (contains) / `IN` (any-of); USER: `= != IN` + `currentUser()`; CHECKBOX: `=`. All support `IS [NOT] EMPTY` except CHECKBOX. SELECT/MULTI_SELECT values are given by option **label or id**; USER values by email / display name / `currentUser()` / UUID; DATE as `YYYY-MM-DD`. Example: `story_points >= 5 AND severity = "High"`.

More examples: `type = Bug AND priority >= High AND due IS NOT EMPTY` · `assignee IS EMPTY AND created >= startOfWeek()` · `text ~ "flux capacitor" OR parent = "DEMO-12"`.

**Invalid queries** return `422` with a machine-readable anchor so a UI can underline the problem:

- a **parse error** carries `"errorType": "PARSE_ERROR"` with `position` (0-based character offset), `length`, and (when known) the offending `token`;
- a **semantic error** (e.g. unknown field, illegal operator, a non-sortable field in `ORDER BY`) carries `"errorType": "SEMANTIC_ERROR"` with the offending `field` and `position`.

```json
{ "type": "about:blank", "title": "Unprocessable Content", "status": 422,
  "detail": "Unknown field 'asignee' — did you mean 'assignee'?",
  "errorType": "SEMANTIC_ERROR", "field": "asignee", "position": 0 }
```

**Schema** — `GET /search/schema` drives autocomplete: `fields` describes each queryable field (`name`, data-type `type`, allowed `operators`, `nullable`, `sortable`, `valueSuggest`, `functions`) — the system fields first, then your visible **custom fields** (`name` = the field `key`, `type` = its custom `FieldType`) — `keywords` lists the HQL keywords, and `values` holds the small picklists (`STATUS`/`TYPE`/`PRIORITY`) of names reachable by your visible projects, a `LABEL` picklist of the workspace's non-archived label names, a `COMPONENT` picklist of the non-archived component names of those visible projects, a `VERSION` picklist of their non-archived version names (**one** picklist serves both `fixVersion` and `affectsVersion` — the two roles draw from the same catalog, so both fields declare `valueSuggest: "VERSION"`), plus a `CUSTOM:<key>` entry per SELECT/MULTI_SELECT custom field (options as `{label, value=optionId}`). The `LABEL`, `COMPONENT` and `VERSION` picklists are **capped at 200 entries** each — a workspace can accumulate far more, so fall back to `/search/suggest?field=label` / `?field=component` / `?field=fixVersion` beyond that. The member list (including USER custom fields) is deliberately **not** embedded — use `/search/suggest` for it.

```json
{
  "fields": [
    { "name": "status", "type": "ENUM_REF", "operators": ["=", "!=", "IN"],
      "nullable": false, "sortable": true, "valueSuggest": "STATUS", "functions": [] },
    { "name": "assignee", "type": "USER_REF", "operators": ["=", "!=", "IN", "IS EMPTY", "IS NOT EMPTY"],
      "nullable": true, "sortable": true, "valueSuggest": "USER", "functions": ["currentUser()"] },
    { "name": "severity", "type": "SELECT", "operators": ["=", "!=", "IN", "IS EMPTY", "IS NOT EMPTY"],
      "nullable": true, "sortable": false, "valueSuggest": "CUSTOM:severity", "functions": [] }
  ],
  "keywords": ["AND", "OR", "NOT", "IN", "IS", "EMPTY", "ORDER BY", "ASC", "DESC"],
  "values": {
    "STATUS": [ { "label": "In Progress", "value": null } ], "TYPE": [ … ], "PRIORITY": [ … ],
    "CUSTOM:severity": [ { "label": "High", "value": "opt-1" } ]
  }
}
```

**Suggest** — `GET /search/suggest?field=assignee&q=ada` returns up to 20 matching members (`q` is a prefix, max 100 chars); `field` may be `assignee`, `reporter`, or a USER-typed custom field. Each suggestion's `value` (the member email) is what you drop into the query:

```json
{ "field": "assignee",
  "suggestions": [ { "label": "Ada Lovelace", "value": "ada@example.com" } ] }
```

The same endpoint serves **labels** — `field=label` (or `labels`) returns up to 20 non-archived label names whose name *contains* `q` (case-insensitive; `label` and `value` are both the name). `%` and `_` in `q` are matched as literal characters, not wildcards:

```json
{ "field": "label",
  "suggestions": [ { "label": "needs-design", "value": "needs-design" } ] }
```

…and **components** — `field=component` (or `components`) behaves identically, over the non-archived component names of the projects you can see (names are de-duplicated case-insensitively, so a "Billing" owned by two projects is offered once):

```json
{ "field": "component",
  "suggestions": [ { "label": "Billing", "value": "Billing" } ] }
```

…and **versions** — `field=fixVersion` or `field=affectsVersion`, over the non-archived version names of the projects you can see. Both roles share one catalog, so the two field names return identical suggestions (names are de-duplicated case-insensitively, so a "2.4.0" shipped by two projects is offered once):

```json
{ "field": "fixVersion",
  "suggestions": [ { "label": "2.4.0", "value": "2.4.0" } ] }
```

## Saved filters

Reusable, workspace-scoped [HQL](#search-hql) data sources. Every user can save their own filters and optionally **share** them read-only with the rest of the workspace. All endpoints require workspace membership and return **`404` (never `403`)** for a non-member, for a private filter owned by someone else, or when a non-owner tries to read/edit/delete a filter that isn't theirs — existence is never leaked.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/filters` | member | List your own filters plus every shared filter in the workspace |
| `POST` | `/workspaces/{wsId}/filters` | member | Create a filter (you become the owner) |
| `GET` | `/workspaces/{wsId}/filters/{filterId}` | member | Get one filter (your own or a shared one) |
| `PATCH` | `/workspaces/{wsId}/filters/{filterId}` | owner | Partial update — only the fields you send are applied |
| `GET` | `/workspaces/{wsId}/filters/{filterId}/usage` | owner | Where the filter is used (delete-warning hook) |
| `DELETE` | `/workspaces/{wsId}/filters/{filterId}` | owner | Delete the filter |

**Owner vs shared** — a filter with `"shared": true` is visible read-only to every other workspace member (it appears in their `GET /filters` list and they can fetch it by id). Only the **owner** may `PATCH`, `DELETE`, or read its `usage` — a non-owner attempting any of those gets `404`, even for a shared filter.

**Create** — the body is `{"name", "hql?", "shared?"}`:

- `name` is required, non-blank, max 120 chars, and **unique per (workspace, owner)** — a duplicate returns `409`. Different owners may reuse a name.
- `hql` is the HQL string to store (max 2000 chars; may be empty = "all issues"). It is **validated at save time** (parse + structural checks against the field schema) but never executed here — value resolution (`currentUser()`, status-name→id, …) is deferred to run time, so a later-archived catalog row won't permanently break a saved query. An invalid query returns `422` with the same anchor shape as [search](#search-hql) (`errorType` `PARSE_ERROR`/`SEMANTIC_ERROR` plus `position`/`length`/`token`/`field`).
- `shared` defaults to `false`.

```bash
curl -X POST $BASE/workspaces/$WS/filters \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "name": "My open bugs",
  "hql": "type = Bug AND assignee = currentUser() AND status != Done",
  "shared": false
}'
```

The response (also returned by `GET` and `PATCH`) is a `SavedFilterResponse`. `mine` is relative to the caller (`true` when you own it) — the UI uses it to show edit/delete only on your own rows:

```json
{ "id": "…", "name": "My open bugs",
  "hql": "type = Bug AND assignee = currentUser() AND status != Done",
  "shared": false, "ownerId": "…", "ownerName": "Ada Lovelace", "mine": true,
  "createdAt": "2026-08-12T10:00:00Z", "updatedAt": "2026-08-12T10:00:00Z" }
```

**Update** — `PATCH` takes `{"name?", "hql?", "shared?"}`; every field is optional and a `null`/omitted field is a no-op. A changed `name` is re-checked for `(workspace, owner)` uniqueness (`409` on a dup, `400` if blank); a changed `hql` is re-validated (`422` on a bad query). Only the owner may update — a non-owner gets `404`.

**Usage** — `GET …/usage` is a forward-looking delete-warning hook so clients can show a "Used by N places — delete anyway?" confirmation. In the current release nothing consumes a saved filter yet, so `usages` is **always empty**:

```json
{ "usages": [] }
```

When boards/reports start consuming filters, each entry will be `{ "type": "BOARD"|"REPORT", "id": "…", "name": "…" }` with no contract change.

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
