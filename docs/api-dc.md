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
- [Issue types](#issue-types)
- [Statuses](#statuses)
- [Workflow transitions](#workflow-transitions)
- [Issues](#issues)
- [Comments](#comments)
- [Attachments](#attachments)
- [Notifications](#notifications)
- [Real-time events (SSE)](#real-time-events-sse)

## Quick start

```bash
BASE=https://tracker.example.com/api   # your instance

# 1. Log in (register + verify your email first — see Auth endpoints)
TOKEN=$(curl -s -X POST $BASE/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your-password"}' | jq -r .accessToken)

# 2. List your workspaces
curl -s $BASE/workspaces -H "Authorization: Bearer $TOKEN"

# 3. Create an issue
curl -s -X POST $BASE/workspaces/{workspaceId}/projects/{projectId}/issues \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Fix the flux capacitor","typeId":"…","statusId":"…","priority":"HIGH"}'
```

## Operator settings that affect the API

A self-hosted instance is configured through environment variables; a few of them change API behavior. Check `GET /meta` to discover how your instance is configured:

| Setting (env) | Default | API effect |
|---|---|---|
| `TERMS_ACCEPTANCE_REQUIRED` | `true` | When `false`, `termsAccepted` is optional at registration |
| Public signup (`app.registration.public-signup-enabled`) | `true` | When disabled, `POST /auth/register` returns `403` once the first account exists |
| `DEMO_SEED_ON_FIRST_LOGIN` | `true` | When `false`, no demo workspace is created on first login |
| `PUBLIC_LANDING_ENABLED` | `true` | When `false`, `robots.txt` disallows all crawling and `sitemap.xml` returns `404` |
| `ATTACHMENT_MAX_FILE_SIZE` | `25MB` | Upload size limit for attachments (`413` when exceeded) |
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

5. When the access token expires (`expiresIn` seconds, currently 24 h), call `POST /auth/refresh` — the cookie authenticates the call and is **rotated** on each use (an old cookie value becomes invalid). Refresh tokens live 30 days.
6. `POST /auth/logout` revokes the refresh token and clears the cookie.

All endpoints except [Auth endpoints](#auth-endpoints) and [Instance metadata](#instance-metadata) require the `Authorization` header. A missing or expired token yields `401 Unauthorized`.

## Conventions

- **Format** — request and response bodies are JSON (`Content-Type: application/json`), UTF-8. The only exceptions: attachment upload (`multipart/form-data`) and download (binary).
- **IDs** — all identifiers are UUIDs, except issues, which are addressed by their **project-scoped number** (the `42` in `DEMO-42`).
- **Timestamps** — ISO-8601 with UTC offset, e.g. `2026-07-14T06:24:41.486119Z`. Date-only fields (`dueDate`) use `YYYY-MM-DD`.
- **Partial updates** — `PATCH` endpoints accept any subset of fields; omitted (or `null`) fields are left unchanged.
- **Access model** — a resource you cannot see returns `404 Not Found`, whether it doesn't exist or you simply aren't a member of its workspace. Membership is never revealed via `403`.
- **Optimistic locking** — issues carry a `version`; send it back in `PATCH` and get `409 Conflict` if someone changed the issue in between (see [Issues](#issues)).
- **Pagination** — list endpoints are currently unpaginated (beta); pagination parameters will be added in a backward-compatible way.

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
| `413` | Attachment exceeds the upload size limit (default 25 MB) |
| `422` | Semantically invalid reference (unknown status/type/assignee, workflow-forbidden transition) |
| `429` | Rate limited — wait the number of seconds in the `Retry-After` header |

### Rate limits

The sensitive auth endpoints (`login`, `register`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`) share a **per-IP budget** (default 15 requests per minute). Additionally, repeated failed logins for one account trigger an **exponential backoff** (defaults: starts at 30 s after 5 consecutive failures, doubles per failure, capped at 15 min); a successful login resets the counter. Both mechanisms respond with `429` and a `Retry-After` header (seconds). Operators tune or disable this via the `RATE_LIMIT_*` variables below. Counters are in-memory (per app node).

## Roles

**Workspace roles** (`OWNER` > `ADMIN` > `MEMBER`) and **project roles** (`MANAGER` > `MEMBER` > `VIEWER`):

| Action | Required role |
|---|---|
| See a workspace and its projects, issues, members | workspace member |
| Invite workspace members | workspace `ADMIN` |
| Manage issue types / statuses | workspace `ADMIN` |
| Manage workflow transitions | workspace `OWNER` |
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

Values reflect the [operator's configuration](#operator-settings-that-affect-the-api) — clients should read them instead of assuming defaults.

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
| `GET` | `/auth/me` | ✔ | The current user |

**Register** — `termsAccepted: true` is required unless the operator disabled `TERMS_ACCEPTANCE_REQUIRED` (check `GET /meta`):

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
  "expiresIn": 86400,
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

## Issue types

Workspace-wide catalog; new workspaces start with **Bug, Task, Story, Epic**.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/issue-types` | member | List, ordered by `position` |
| `POST` | `/workspaces/{wsId}/issue-types` | `ADMIN` | Create (`{"name", "color?", "icon?"}`). `201` |
| `PATCH` | `/workspaces/{wsId}/issue-types/{typeId}` | `ADMIN` | Update |
| `DELETE` | `/workspaces/{wsId}/issue-types/{typeId}` | `ADMIN` | Delete; `409` if used by issues. `204` |

```json
{ "id": "…", "name": "Bug", "color": "#EF4444", "icon": "bug", "position": 0 }
```

## Statuses

Workspace-wide board columns; new workspaces start with **To Do, In Progress, Done**. Every status has a `category` — `TODO`, `IN_PROGRESS` or `DONE` — that drives board grouping and backlog filtering.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/statuses` | member | List, ordered by `position` |
| `POST` | `/workspaces/{wsId}/statuses` | `ADMIN` | Create (`{"name", "category", "color?"}`). `201` |
| `PATCH` | `/workspaces/{wsId}/statuses/{statusId}` | `ADMIN` | Update |
| `DELETE` | `/workspaces/{wsId}/statuses/{statusId}` | `ADMIN` | Delete; `409` if used by issues. `204` |

```json
{ "id": "…", "name": "In Progress", "color": "#3B82F6", "category": "IN_PROGRESS", "position": 1 }
```

## Workflow transitions

Optional rules restricting how issues move between statuses. With no rules configured for a source status, any move from it is allowed; once one exists, only listed targets are accepted (both via `PATCH` and board drag-and-drop) — a forbidden move returns `422`.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/status-transitions` | member | List rules |
| `POST` | `/workspaces/{wsId}/status-transitions` | `OWNER` | Create (`{"fromStatusId", "toStatusId"}`). `201` |
| `DELETE` | `/workspaces/{wsId}/status-transitions/{transitionId}` | `OWNER` | Delete. `204` |

## Issues

Issues live under a project and are addressed by **number** — the numeric part of their key (`DEMO-42` → `…/issues/42`). Numbers are sequential per project and never reused.

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues` | member | Create. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues?statusId=&assigneeId=&priority=` | member | List with optional filters |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Get one |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/history` | member | Field-level change history |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Partial update with optimistic locking |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | `MANAGER` | Delete issue + comments + attachments. `204` |

**Create** — `title`, `typeId` and `statusId` are required; `priority` is one of `URGENT`, `HIGH`, `MEDIUM`, `LOW`, `NONE` (default); `parentId` links a sub-task to a parent issue in the same project; `assigneeId` must be a workspace member:

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/issues \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "title": "Rate-limit authentication endpoints",
  "description": "Login accepts unlimited attempts…",
  "typeId": "…", "statusId": "…",
  "priority": "HIGH",
  "assigneeId": "…",
  "dueDate": "2026-07-24"
}'
```

```json
{
  "id": "…", "number": 18, "key": "DEMO-18",
  "title": "Rate-limit authentication endpoints",
  "description": "Login accepts unlimited attempts…",
  "type":   { "id": "…", "name": "Task", "color": "#3B82F6", "icon": "task", "position": 1 },
  "status": { "id": "…", "name": "To Do", "color": "#6B7280", "category": "TODO", "position": 0 },
  "priority": "HIGH",
  "assignee": { "id": "…", "displayName": "Ada Lovelace", "avatarUrl": null },
  "reporter": { "id": "…", "displayName": "Ada Lovelace", "avatarUrl": null },
  "parentId": null,
  "dueDate": "2026-07-24",
  "version": 0,
  "createdAt": "…", "updatedAt": "…"
}
```

**Update & optimistic locking** — send any subset of `title`, `description`, `typeId`, `statusId`, `priority`, `assigneeId`, `dueDate`, plus the `version` you last read. If the issue changed since, you get `409 Conflict` — re-fetch and retry. Omitting `version` skips the check (last write wins):

```bash
curl -X PATCH $BASE/workspaces/$WS/projects/$PROJ/issues/18 \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"statusId": "…", "version": 3}'
```

**History entries:**

```json
{ "id": "…", "field": "status", "oldValue": "To Do", "newValue": "In Progress",
  "changedById": "…", "changedByName": "Ada Lovelace", "createdAt": "…" }
```

## Comments

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `…/issues/{number}/comments` | member | Create (`{"body"}`). `201` |
| `GET` | `…/issues/{number}/comments` | member | List (deleted comments excluded) |
| `PATCH` | `…/issues/{number}/comments/{commentId}` | author | Edit (`{"body"}`) |
| `DELETE` | `…/issues/{number}/comments/{commentId}` | author | Soft delete. `204` |

`@DisplayName` mentions in a comment body notify the mentioned workspace members.

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

Upload size limit: **25 MB** by default — operators change it via `ATTACHMENT_MAX_FILE_SIZE` (`413` when exceeded).

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
