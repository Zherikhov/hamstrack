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
- [Permissions](#permissions)
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
- [Labels](#labels)
- [Components](#components)
- [Versions](#versions)
- [Sprints & backlog](#sprints--backlog)
- [Search (HQL)](#search-hql)
- [Saved filters](#saved-filters)
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
- **Optimistic locking** — issues carry a `version`; send it back in `PATCH` and get `409 Conflict` if someone changed the issue in between (see [Issues](#issues)). Omitting it is last-write-wins for the fields you send — but not a guarantee of success: if a competing write commits while your request is in flight, you get the same `409` from the database's own check. Either way a lost race is always a `409` telling you to refresh and retry, never a `500`.
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

### Validation failures (`400`)

A request body that fails validation answers with a `detail` naming every offending field **and** an `errors` object keyed by the JSON path of the field:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "key: Key must be uppercase letters and digits only; name: must not be blank",
  "errors": {
    "key": "Key must be uppercase letters and digits only",
    "name": "must not be blank"
  }
}
```

Rules worth coding against:

- **`detail` and `errors` always agree** — same entries, same order. `detail` is those entries joined with `"; "`, each rendered as `<field>: <message>`; the field prefix is dropped when the message already starts with the field path, so a full-sentence message isn't stuttered back.
- **Keys are JSON field paths**, nested ones included: `delivery.preset`, `items[3].fieldId`.
- **Cross-field (class-level) rules use the empty-string key `""`** — they belong to no single field. Their message is rendered bare in `detail`, without a prefix.
- **At most 10 entries are reported**, sorted by their rendered line. If more fields failed, `detail` ends with `"; … and 3 more"` (the number of entries not shown); `errors` carries the same 10.
- **Messages are always English**, whatever the request's `Accept-Language`. Constraint text used to follow the caller's locale; it is now pinned by design, so the wording is stable — but treat it as human-readable text, not a machine-readable code, and match on `errors` keys (field paths) instead.
- **One message per field** — if a field breaks several constraints, the first failure wins.
- A body that can't be parsed at all (malformed JSON, a wrong JSON type) is still a `400`, but carries **no** `errors` map: nothing was ever bound.
- **Not every `400` is a validation failure.** A rule that spans fields and is enforced in the service rather than by a field constraint — sending `boardMode` and a disagreeing [`delivery.board`](#delivery-capabilities), for instance — answers a plain problem detail: `detail` explains it, and there is **no** `errors` map. Read `errors` defensively.

Uploads have two size ceilings and the `413` wording tells them apart: the in-app per-file limit answers `"File exceeds the 20 MB limit"`, while the servlet multipart ceiling answers `"File is too large"`.

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

The sensitive auth endpoints (`login`, `register`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`) share a **per-IP budget of 15 requests per minute**. Additionally, repeated failed logins for one account trigger an **exponential backoff** (starting at 30 s after 5 consecutive failures, doubling per failure, capped at 15 min); a successful login resets the counter. Both mechanisms respond with `429` and a `Retry-After` header (seconds).

One non-auth endpoint has a throttle of its own: [`POST …/issues/{number}/rank`](#sprints--backlog) allows at most one whole-project rank *rebalance* per project per 60 s and answers `429` + `Retry-After` for a second one inside that window. It is a retryable throttle, not a fault — nothing was moved. The rest of the API is not rate-limited.

## Roles

**System role** (`ADMIN` — instance-wide, maintains the global taxonomy via [`/admin/**`](#system-administration)), **workspace roles** (`OWNER` > `ADMIN` > `MEMBER`) and **project roles** (`MANAGER` > `MEMBER` > `VIEWER`). `GET /auth/me` returns your `systemRole`.

| Action | Required role |
|---|---|
| See a workspace and its projects, issues, members | workspace member |
| Invite workspace members | workspace `ADMIN` |
| Change a member's role, or remove a member | workspace `ADMIN` (never on — or to — a role above your own) |
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

The table is the human summary; the machine-readable form of the same idea is [permissions](#permissions) — `GET /permissions` for the catalog, and `myPermissions` on each workspace and project response for what the caller actually holds.

## Permissions

Roles resolve to **permissions** — flat, stable string keys such as `issue.transition` or `sprint.manage`. Two surfaces expose them: `GET /permissions` describes every permission the product defines, and `myPermissions` on each workspace and project response tells you which of them *you* hold there.

### The catalog — `GET /permissions`

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/permissions` | ✔ | Every permission this instance defines |

Static product metadata, not tenant data: no workspace context, no path or query parameters, no database access, and the same body for every caller on a given version. Fetch it once and cache it for the session. It still needs a valid access token (`401` without one).

The response is a JSON array of catalog entries in a stable order — workspace-scoped first, then project-scoped:

```json
[
  { "key": "workspace.member.manage", "scope": "WORKSPACE", "supportsOwn": false, "ownRequired": false, "capability": null },
  { "key": "label.manage",            "scope": "WORKSPACE", "supportsOwn": true,  "ownRequired": false, "capability": null },
  { "key": "issue.transition",        "scope": "PROJECT",   "supportsOwn": false, "ownRequired": false, "capability": null },
  { "key": "comment.edit",            "scope": "PROJECT",   "supportsOwn": true,  "ownRequired": true,  "capability": null },
  { "key": "sprint.manage",           "scope": "PROJECT",   "supportsOwn": false, "ownRequired": false, "capability": "BOARD" }
]
```

| Field | Type | Meaning |
|---|---|---|
| `key` | string | The permanent wire key: `area.action`, lowercase, dot-separated, at most 64 chars, **never containing a `:`**. This is the exact string that appears in `myPermissions` (optionally suffixed with `:own`). |
| `scope` | `WORKSPACE` \| `PROJECT` | Which object the permission is granted on, and therefore which response carries it: a `WORKSPACE` key can only appear in a workspace's `myPermissions`, a `PROJECT` key only in a project's. |
| `supportsOwn` | boolean | Whether a grant may be narrowed to objects the caller owns — i.e. whether you may ever see this key with the `:own` suffix. `false` for most entries. |
| `ownRequired` | boolean | Whether the permission can **only** be granted own-only. Implies `supportsOwn: true`, and means the bare key never appears in any `myPermissions` — only the `:own` form does. Today this is true for `comment.edit` alone: editing someone else's words is not a permission the product grants at any role. |
| `capability` | `BOARD` \| `RELEASES` \| `null` | The [delivery capability](#delivery-capabilities) the permission is *about* — a labelling hint, **never a check**, and `null` for most entries. |

**Read the catalog, don't transcribe it.** There are 29 entries at the time of writing (9 workspace-scoped, 20 project-scoped) and the list grows between releases, so this reference deliberately does not reproduce it: `GET /permissions` is the authoritative version, always in sync with what the server enforces. Drive a role picker or a permission legend from the endpoint rather than hard-coding keys or a count. Individual keys, on the other hand, are permanent — they are the wire contract and are never renamed.

**`capability` is a hint, not a gate.** A project whose [`delivery.board`](#delivery-capabilities) is `KANBAN` still enforces `sprint.manage` in exactly the same way, and `delivery.releases: false` changes nothing about `version.manage`. Capabilities decide what the UI offers; permissions decide what the API allows. Never substitute one for the other.

### `myPermissions` — what *you* may do

Every [workspace](#workspaces) and [project](#projects) response carries `myPermissions`: the **requesting user's** effective permissions on that object, as a flat array of catalog keys. It rides responses a client already fetches, so asking "may I do this?" costs no extra request.

- **Always present**, in catalog order. An empty array is a real answer (a caller with no grants); the field is never `null` and never omitted — never read "absent" as "allowed".
- **The caller only.** It never describes another user's access.
- **Scoped to the object it rides.** A workspace's array holds only `WORKSPACE`-scoped keys; a project's holds only `PROJECT`-scoped keys — including any the caller gets in that project by virtue of their workspace role rather than a project membership.

### The `:own` suffix

An entry is one of two forms:

| Wire value | Means |
|---|---|
| `issue.edit` | the permission over **any** object of that kind |
| `issue.edit:own` | the permission over **only objects the caller owns** |

"Own" is per permission: the issue's **reporter** for `issue.edit` / `issue.delete`, the comment's **author** for `comment.edit` / `comment.delete`, the **uploader** for `attachment.delete`, the **creator** for `label.manage`. At most one of the two forms appears for a given permission — an unrestricted grant supersedes an own-only one.

**`issue.edit` and `issue.edit:own` are two distinct values, not a prefix relationship.** An own-only grant does **not** satisfy an unrestricted check. Compare with equality:

```js
// the unrestricted question: "may I edit anyone's issue?"
const canEditAny = perms.includes('issue.edit');

// the per-object question: "may I edit this one?"
const canEdit = (issue) =>
  perms.includes('issue.edit') ||
  (issue.reporter.id === me.id && perms.includes('issue.edit:own'));

// WRONG — a prefix match also matches 'issue.edit:own', silently reading an
// own-only grant as permission over everyone's issues.
const canEditAnyBroken = perms.some((p) => p.startsWith('issue.edit'));
```

That failure widens rather than narrows, which is why it is worth stating plainly. Keys never contain a `:`, so the suffix is unambiguous if you do need to split — but equality against the two full strings is the safer test.

### `myPermissions` is not an authorization boundary

It tells a client what to render. It does not decide anything: the API performs its own check on every request, whatever the client drew. A call the caller may not make fails identically whether the button was hidden or not, and hiding a control is a UX decision, never a security control. Equally, `myRole` is display metadata — on a project it reports the caller's **explicit** project role (`VIEWER` when they have no project membership row of their own, even where that member can in fact do more), so it can be narrower than the truth. Gate on `myPermissions`.

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
| `PATCH` | `/workspaces/{id}/members/{userId}` | `ADMIN` | Change a member's role (`{"role"}`; role ≤ your own, and so must their current one). Returns the member |
| `DELETE` | `/workspaces/{id}/members/{userId}` | `ADMIN` | Remove a member from the workspace — not their account. `204` |
| `POST` | `/workspaces/{id}/invites` | `ADMIN` | Email an invite (`{"email", "role"}`; role ≤ your own, never `OWNER`). `201` |
| `POST` | `/workspaces/accept-invite?token=…` | ✔ | Accept an invite; must be signed in with the invited email |

```json
// POST /workspaces  {"name": "Acme Inc"}
{
  "id": "…", "slug": "acme-inc", "name": "Acme Inc",
  "myRole": "OWNER",
  "myPermissions": ["workspace.edit", "workspace.member.manage", "…"],
  "createdAt": "…"
}
```

Every workspace response — create, list, get and invite acceptance — carries [`myPermissions`](#permissions): the caller's effective **workspace-scoped** permissions. It is always present (an empty array is a real answer) and is the field to gate UI on; `myRole` is for display.

### Managing members

`PATCH …/members/{userId}` takes `{"role": "OWNER" | "ADMIN" | "MEMBER"}` — that is the whole body, and it is required (`400` otherwise) — and returns the updated membership in the same shape `GET …/members` lists:

```json
{ "userId": "…", "email": "mia@example.com", "displayName": "Mia", "avatarUrl": null, "role": "ADMIN", "joinedAt": "…" }
```

Setting the role a member already holds is an accepted no-op rather than an error, so re-sending the current value from a form is safe.

**The grant ceiling.** Nobody may hand out — or act on — a role stronger than their own, and the check covers the target's **current** role as well as the new one. An `ADMIN` can therefore manage `MEMBER`s and other `ADMIN`s, but can neither promote anyone to `OWNER` nor demote or remove an existing `OWNER`; both refusals are `403`. An `OWNER` **may** promote another member to `OWNER` — that is how ownership is handed over. This differs on purpose from `POST …/invites`, which refuses `OWNER` outright: you promote a colleague to owner, you do not invite a stranger as one. It looks like an inconsistency and is not one.

**The last owner is protected.** A workspace must never end up without an `OWNER`, so demoting or removing the final one returns `409` — whoever asks, including that owner acting on themselves. Promote a second owner first. (Because an unchanged role is a no-op, re-sending `OWNER` for the last owner is still fine.) The check is serialised, so two admins demoting two different owners at the same moment cannot both succeed; the second gets the `409`.

**A workspace demotion does not touch project roles.** The two scopes are separate: someone demoted from `ADMIN` to `MEMBER` keeps every explicit project membership they hold, project `MANAGER` rows included, and keeps whatever those grant inside those projects. That is deliberate scope separation, not an oversight — but it is probably not what "demote" suggests, so if you are reducing someone's access in a hurry, check `GET …/projects/{projectId}/members` as well. Removal, by contrast, *does* delete their project memberships in this workspace.

**Removal is not account deletion.** A user account is global and may belong to several workspaces, so `DELETE …/members/{userId}` revokes access to **this** workspace only. In one transaction it:

- deletes the workspace membership;
- deletes that user's project memberships **within this workspace** (leaving them behind would silently restore a project role if the person were ever re-invited);
- clears the `assignee` of their issues **in this workspace**, writing one `assignee` history entry per issue so the unassignment shows up in the activity feed like any other edit;
- deletes every **unaccepted invite** for their address in this workspace. Duplicate pending invites are normal (a re-send), and they stay hidden while the person is a member — so without this, removing them would make a leftover invite *reappear* on their "join a team" screen, and one click would put them straight back. Accepted invites are kept as the record of how they originally joined;
- closes any **live event stream** (SSE) they hold on this workspace. Membership is only checked when a stream is opened, so an open one would otherwise keep delivering activity metadata until it timed out. Their browser reconnects, that reconnect re-checks membership, and it gets the `404`.

The unassignment bumps each affected issue's `version`, so a client that loaded one of those issues **before** the removal and then saves an edit gets the usual `409` — asking it to refresh and retry — instead of silently writing the old assignee back. That holds whether the client sent `version` (rejected by the issue endpoint's own check) or omitted it and simply lost the race (rejected by the database's).

The account itself is untouched, as is their membership of every other workspace — including issues assigned to them there.

**What deliberately survives**, and is not a dangling reference:

| Kept | Why |
|---|---|
| Historical attribution — issue `reporter`, comment author, [history](#issues) entries, [attachment](#attachments) uploader | Who did a thing does not change because they left |
| [Saved filters](#saved-filters) they own, shared ones included | A shared filter resolves in the **viewer's** context, so it keeps working for everyone else |
| Any [component](#components) they lead (`lead`, and its `autoAssign` flag) | A departed lead keeps the row so the module still records who led it; auto-assign re-checks membership at issue-create time and silently skips them, leaving the new issue unassigned |

**Status codes** for both verbs:

| Status | When |
|---|---|
| `403` | The caller is a workspace member but below `ADMIN`, or the change breaks the grant ceiling |
| `404` | Unknown workspace, caller not a member, **or** the target holds no membership in *this* workspace |
| `409` | The change would leave the workspace without an `OWNER` |
| `422` | `DELETE` only — the target is the caller (see below) |

That third `404` case is about the **membership**, never about the account: an unknown id, an id belonging to someone in another workspace, and a member who was removed a second ago are one indistinguishable answer, so the endpoint cannot be used to probe which accounts exist. It also makes a repeated `DELETE` a clean `404` instead of an error — safe to retry.

**There is no self-removal here, and it is enforced rather than merely absent.** `DELETE …/members/{userId}` refuses with `422` when the target is the caller: leaving a workspace is a different feature — it needs a confirmation, somewhere to land afterwards, and an answer for "that was my only workspace" — and it is not built yet. A sole owner deleting themselves gets the `409` instead, because "promote another owner first" is the answer that will still be true once leaving exists. Self-*demotion* on `PATCH` is allowed: an owner stepping down to `ADMIN` while another owner exists is an ordinary handover.

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
| `POST` | `/workspaces/{wsId}/projects` | member | Create (`{"name", "key", "description?", "delivery?"}`); key is 1–10 chars `A-Z0-9`, unique per workspace. `201` |
| `GET` | `/workspaces/{wsId}/projects?includeArchived=false` | member | List projects |
| `GET` | `/workspaces/{wsId}/projects/{projectId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{projectId}` | curator | Update `name` / `description` / `delivery` (and the deprecated `boardMode`) |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/archive` | `MANAGER` | Archive (read-only afterwards). `204` |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/unarchive` | `MANAGER` | Restore. `204` |
| `GET` | `/workspaces/{wsId}/projects/{projectId}/members` | member | List project members |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/members` | `MANAGER` | Add a workspace member (`{"userId", "role"}`). `201` |
| `DELETE` | `/workspaces/{wsId}/projects/{projectId}/members/{userId}` | `MANAGER` | Remove a member. `204` |

```json
// project shape
{
  "id": "…", "workspaceId": "…", "name": "Demo Project", "key": "DEMO",
  "description": "…", "archived": false,
  "boardMode": "KANBAN",                       // DEPRECATED mirror of delivery.board
  "delivery": {
    "board": "KANBAN",                         // KANBAN | SCRUM
    "releases": false,
    "estimation": false,
    "preset": "KANBAN"                         // DERIVED, read-only — never send it back
  },
  "myRole": "MANAGER",                         // the caller's EXPLICIT project role
  "myPermissions": ["issue.create", "issue.transition", "comment.edit:own", "…"],
  "createdAt": "…"
}
```

Every project response — create, list, get and update — carries [`myPermissions`](#permissions): the caller's effective **project-scoped** permissions in that project, including any they hold through their workspace role rather than a project membership. It is always present, and it can be **wider than `myRole` suggests** — `myRole` reports only the caller's explicit project membership row, and reads `VIEWER` when they have none. Gate on `myPermissions`; display `myRole`.

**Update permission.** `PATCH …/projects/{projectId}` needs the **project curator** role — project `MANAGER`, **or** an `OWNER`/`ADMIN` of the enclosing workspace (who need not be a project member); any other member gets `403`. This is wider than it used to be: the endpoint was `MANAGER`-only until `boardMode` joined it, and it now matches every other project-content write ([components](#components), [versions](#versions), [sprints](#sprints--backlog)). Archiving, unarchiving and member management stay `MANAGER`-only.

**Archived projects are frozen.** `PATCH …/projects/{projectId}` on an archived project returns `409 "Project is archived"` — the same answer every issue edit, sprint mutation and rank move already gives. That covers `delivery` too, which changes how the board, the backlog, the rail and the issue detail render. Unarchive it first (`POST …/unarchive`, `MANAGER`-only); reads keep working throughout.

### Delivery capabilities

A project declares **how its team delivers** through three independent capabilities. They ride the `delivery` object on **every** project response — list, get, create and update — so no surface needs an extra request (or a guess based on "does this project already have sprints?") to ask what kind of project this is. They are deliberately **not** part of [project configuration](#project-configuration): flipping a capability changes nothing about a project's workflow, priority set, type set or field set, and does not invalidate anything you cached from `…/config`.

| Capability | Values | Governs (in the UI) |
|---|---|---|
| `board` | `KANBAN` \| `SCRUM` | sprints as a planning concept: backlog sprint sections, sprint-scoped board, the sprint field on issues |
| `releases` | `true` / `false` | versions: the Releases page and rail item, fix/affects pickers and filters |
| `estimation` | `true` / `false` | story points: the points input and every point sum |

`delivery.preset` is a **derived** label the server computes from those three — a display convenience, never stored:

| `preset` | `board` | `releases` | `estimation` |
|---|---|---|---|
| `KANBAN` | `KANBAN` | off | off |
| `SCRUM` | `SCRUM` | off | on |
| `RELEASES` | `KANBAN` | on | off |
| `CUSTOM` | any other combination | | |

`CUSTOM` is a legal, first-class answer — "Scrum + Releases" is an ordinary way to work and is never forbidden.

> **Capabilities gate the UI, never the API.** This is a guarantee, not an implementation detail: **no endpoint, request field, filter or [HQL](#search-hql) field behaves differently because of a capability, and no status code anywhere depends on one.** A project with `releases: false` still accepts and returns version data; `POST …/sprints` still succeeds with `board: KANBAN`; issue creation with `storyPoints`, `fixVersionIds` and `sprintId` still returns `201` with **every** capability off. Turning a capability off never deletes, clears or moves anything — re-enabling it restores full function immediately. Do not treat a capability as a permission or as a schema switch.
>
> The single place a capability is visible in the API is **autocomplete metadata**: [`/search/schema`](#search-hql) omits `sprint` / `fixVersion` / `affectsVersion` / `storyPoints` when no visible project has the matching capability on, and the `SPRINT` / `VERSION` picklists (plus `/search/suggest` for the two version fields) only carry names from capability-on projects. That is a *suggestion* narrowing: every one of those fields still parses, compiles and runs, so no saved filter can break when a capability is flipped.

**Choosing them at creation.** `POST …/projects` takes an optional `delivery` object, and it is **partial per member**: whatever you leave out takes the lean default — `board: KANBAN`, `releases: false`, `estimation: false` — rather than staying unset. The three capabilities are independent and the server never infers one from another, so a client that means "Scrum, and we estimate" says both, in the one create call:

```bash
curl -X POST $BASE/workspaces/$WS/projects \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "name": "Payments", "key": "PAY",
  "delivery": { "board": "SCRUM", "estimation": true }
}'
```

That project comes back with `board: "SCRUM"`, `estimation: true`, `releases: false` and the derived `preset: "SCRUM"`.

**Changing them later.** `PATCH …/projects/{projectId}` takes the same object, equally partial per member: `{"delivery": {"releases": true}}` turns releases on and leaves `board` and `estimation` untouched, and omitting `delivery` entirely leaves all three alone (renaming a project never quietly re-leans it). Setting a capability to the value it already has is a no-op that still returns `200` with the current state. The call needs the **project curator** role (above) and returns `409` on an archived project; there is no confirmation step and no `409` for "you still have unreleased versions" — confirmations are a UI affordance. **Switching destroys nothing**: no issue's sprint is cleared, no version link removed, no story-point value erased, and re-enabling a capability restores full function immediately with no further action.

`board` is a closed set on both endpoints — an unknown value is a `400`, never a silent fall-through; `null`/omitted leaves the current value.

**`preset` cannot be sent.** It is derived, so a create or update body carrying `delivery.preset` is rejected — a named failure rather than a silent ignore. It fails at validation, so the body is the ordinary [field-anchored `400`](#conventions) keyed by JSON path:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "delivery.preset is derived from board/releases/estimation and cannot be set",
  "errors": { "delivery.preset": "delivery.preset is derived from board/releases/estimation and cannot be set" } }
```

The practical consequence: **you cannot blindly `PATCH` back the `delivery` object you read from a `GET`** — strip `preset` first and send only the capabilities you mean to change. The rejection is **total**: nothing else in the same body is applied on the way to it, so a body that sets `releases` *and* echoes `preset` changes nothing at all.

**`boardMode` is deprecated.** The top-level `boardMode` field is a mirror of `delivery.board`: it is still **populated** in every response and still **accepted** on `PATCH`, for one minor release, so clients written before `delivery` existed keep working. Sending both is fine when they **agree**; sending `boardMode` together with a **disagreeing** `delivery.board` is a `400` rather than a silent winner-takes-all — picking a winner would discard half of what an out-of-date client asked for without telling it which half. This one is enforced in the service, not by a field constraint, so it carries **no** `errors` map:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "boardMode and delivery.board disagree — send only one" }
```

This rejection is total too — the rest of the body is not applied. Create has no `boardMode` field at all, so the disagreement case is `PATCH`-only. When the mirror is eventually dropped, nothing about behavior changes: `delivery.board` already carries the identical value, so migrating off it is a rename. New clients should read and write `delivery` only.

**Defaults, and why an old project looks different.** A project created without a `delivery` object gets the lean defaults — `board: KANBAN`, `releases: false`, `estimation: false`. Projects that existed **before** delivery capabilities shipped were migrated to keep everything they already had: `releases: true`, `estimation: true`, and `board: SCRUM` if the project already owned a sprint. So an older project will legitimately look more capable than a freshly created one; that is the migration rule ("an upgrade never takes away a capability a project already had"), not a bug.

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

**The project's [delivery capabilities](#delivery-capabilities) change nothing here.** `storyPoints`, `sprintId`, `fixVersionIds` and `affectsVersionIds` are accepted, stored and returned identically whatever a project declares — a project with `estimation: false` still takes a point value, and one with `board: KANBAN` still takes a `sprintId`. Every field below is always present in the response too; a capability hides a control in the UI and never removes anything from the API.

**Hierarchy.** An issue may have a parent in the same project, governed by issue-type [hierarchy levels](#project-configuration) (a parent's type level must be strictly greater than the child's). Every `IssueResponse` carries the parent summary (`parentId`, `parentKey`, `parentTitle`, `parentTypeId`, all `null` when there is no parent) and a direct-children roll-up (`childCount`, and `doneChildCount` for children in a DONE-category status). `GET …/issues/{number}/children` lists the direct children in board order.

**Listing — dual shape.** Without `size`, `GET …/issues` returns a `BoardIssuesResponse` object (the board/kanban path): `{ "issues": IssueResponse[], "truncated": boolean, "totalAvailable": integer, "cap": integer }`. `issues` is bounded server-side to `cap` (default 500, never client-overridable); when the project after the same filters exceeds it, `truncated` is `true` and `totalAvailable` reports the full count so the UI can show "Showing first {cap} of {totalAvailable}". Pass `size` to switch to a paginated [envelope](#conventions) (the backlog path); the optional `excludeDone=true` then drops issues in a DONE-category status server-side. The `statusId` / `assigneeId` / `priorityId` / `componentId` / `labelId` / `fixVersionId` / `sprintId` / `noSprint` filters apply in both modes and are ANDed together. Rows come back in the shared backlog/board **rank** order (`position`, then newest first) — the rank value itself is never exposed.

**Filtering by label.** The [label](#labels) filter is applied **server-side** in both modes (it has to search the whole project, not just the capped page already on screen): repeat `labelId` once per label and choose how they combine with `labelMatch` — `any` (default, carries at least one) or `all` (carries every one), lowercase. Any other `labelMatch` value is a `400`, as is passing more than 20 distinct `labelId` values (the per-issue label limit).

**Filtering by component.** Same rule, simpler shape: `componentId` is a single optional uuid, applied **server-side** and ANDed with everything above. A [component](#components) id belonging to another project simply matches nothing — it is never an error.

**Filtering by fix version.** Likewise: `fixVersionId` is a single optional uuid, applied **server-side** and ANDed with everything above. It matches the **fix** role only — an *affects* link to the same [version](#versions) does not match a "fix version" filter. An id from another project simply matches nothing.

**Filtering by sprint.** `sprintId` narrows the list to one [sprint](#sprints--backlog); `noSprint=true` narrows it to the backlog (issues in no sprint at all). Both are applied **server-side** and ANDed with everything above, and a sprint id from another project simply matches nothing. The two are **mutually exclusive** — "in this sprint" and "in no sprint" can never both hold, so sending both is a `400` rather than a silently empty result you would misread as "nothing matches".

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/issues?labelId=$L1&labelId=$L2&labelMatch=all" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$BASE/workspaces/$WS/projects/$PROJ/issues?componentId=$COMP" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$BASE/workspaces/$WS/projects/$PROJ/issues?fixVersionId=$VER" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$BASE/workspaces/$WS/projects/$PROJ/issues?sprintId=$SPRINT" \
  -H "Authorization: Bearer $TOKEN"
```

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues` | member | Create. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues?statusId=&assigneeId=&priorityId=&componentId=&labelId=&labelMatch=&fixVersionId=&sprintId=&noSprint=&excludeDone=&page=&size=` | member | List with optional filters — **dual shape** (see above) |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Get one |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/children` | member | Direct children of the issue, in board order |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/history?page=&size=` | member | Field-level change history (paginated, oldest first) |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Partial update with optimistic locking |
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/rank` | member | Move the issue in the backlog/board [rank](#sprints--backlog), optionally into or out of a sprint |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | `MANAGER` | Delete issue + comments + attachments. `204` |

**Create** — `title`, `typeId` and `statusId` are required (the type must be offered by the project's type set, the status must belong to the project's [workflow](#project-configuration)); `priorityId` must be offered by the project's priority set and defaults to the set's default when omitted; `parentId` links the issue to a parent in the same project — rejected with `422` if the parent is unknown, in another project, or its type's [hierarchy level](#project-configuration) is not strictly greater than this issue's type level; `assigneeId` must be a workspace member. `fields` carries custom field values keyed by field id (value shapes per [field type](#project-configuration)) — required fields of the project's field set must be present, fields outside the set or archived are rejected with `422`. `labelIds` attaches workspace [labels](#labels) (duplicates are de-duped); an unknown, foreign-workspace or archived label id — or more than 20 distinct ids — is rejected with `422`. `componentId` files the issue under a [component](#components) of this project; an unknown, foreign-project or archived component is a `422`, and when the component has auto-assign the lead may become the assignee (see [auto-assign](#components)). `fixVersionIds` and `affectsVersionIds` link [versions](#versions) of this project in the two independent roles — "ships in" and "is broken in"; an unknown, foreign-project or archived version, or more than 20 distinct ids **per link type**, is a `422` (linking an already-*released* version is allowed on purpose). `sprintId` files the issue straight into a [sprint](#sprints--backlog) of this project — an unknown, foreign-project or *completed* sprint is a `422`, and omitting it means the backlog. `storyPoints` is the native estimate: `0`–`999` with at most 2 decimals (`422` otherwise), where `null`/omitted means **unestimated**, which is deliberately not the same as `0`. A new issue is always appended to the **bottom** of the ranked backlog:

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
  "sprintId": "0198f7d4-…",
  "storyPoints": 5,
  "fields": { "e1b2…": 5, "f3c4…": "critical" }
}'
```

> `clearSprint` and `clearStoryPoints` are accepted on **create** for payload symmetry with the update body, but they do nothing there — a brand-new issue has nothing to clear, and omitting `sprintId`/`storyPoints` already means "backlog" / "unestimated". They only carry meaning on `PATCH`.

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
  "sprint": { "id": "0198f7d4-…", "name": "Sprint 7", "state": "ACTIVE" },
  "storyPoints": 5,
  "fields": [ { "fieldId": "e1b2…", "value": 5 }, { "fieldId": "f3c4…", "value": "critical" } ],
  "version": 0,
  "createdAt": "…", "updatedAt": "…",
  "closedAt": null
}
```

`sprint` is `null` when the issue sits in the backlog, and `storyPoints` is `null` when it is unestimated (never `0` — "we didn't estimate it" and "it's free" are different statements). The issue's **rank** is deliberately *not* exposed: it is server-written only, and a client places an issue by naming its neighbours instead (see [rank](#sprints--backlog)).

`closedAt` is the **closed date** — server-maintained and never accepted on a request. It is stamped when the issue enters a status whose `category` is `DONE` (creating an issue directly in a DONE status stamps it immediately) and cleared back to `null` when the issue leaves a DONE-category status. Only a change of status **category** re-evaluates it: moving between two different DONE statuses keeps the original stamp, and an update that doesn't touch the status leaves it alone. The stamp writes no [history](#issues) entry of its own — the `status` entry already records the transition.

**Update & optimistic locking** — send any subset of `title`, `description`, `typeId`, `statusId`, `priorityId`, `assigneeId`, `dueDate`, `labelIds`, `componentId`, `fixVersionIds`, `affectsVersionIds`, `sprintId`, `storyPoints`, `fields`, plus the `version` you last read. If the issue changed since, you get `409 Conflict` — re-fetch and retry. Omitting `version` skips the check (last write wins). To unset a nullable core field send `clearAssignee: true` / `clearDueDate: true` — a plain `null` can't be told apart from an omitted field (ignored when the id/date is also given). A `parentId` sets or changes the parent and `clearParent: true` detaches it (same convention); an illegal parent — unknown, in another project, self, a cycle, a [hierarchy-level](#project-configuration) violation, or a type change that conflicts with an existing parent or child edge — returns `422`. `labelIds`, `fixVersionIds` and `affectsVersionIds` are the **full-replacement** fields: when present the issue ends up carrying exactly those labels / versions-in-that-role, `[]` removes them all, and omitting one leaves it untouched (no clear-flag needed — `[]` is unambiguous); an already-attached archived label or version may stay, it just cannot be added. The two version roles are independent, so sending only `fixVersionIds` never touches the affects set, and the 20-per-issue cap is counted separately per role. `componentId` sets or changes the [component](#components) and `clearComponent: true` unsets it (the `assigneeId`/`clearAssignee` convention again); attaching an unknown, foreign-project or **archived** component is a `422`, though an issue already carrying an archived one stays editable — and component auto-assign never fires on an update. `sprintId` moves the issue into a [sprint](#sprints--backlog) of this project and `clearSprint: true` returns it to the backlog (the same nullable-scalar convention); sending **both** is a `400` — "put it in sprint X" and "take it out of every sprint" cannot both hold, so `sprintId` no longer wins silently, and this now matches the [rank endpoint](#sprints--backlog), which has always refused the same combination. An unknown, foreign-project or **completed** target sprint is a `422`, and so is changing or clearing the sprint of an issue whose **current** sprint is `COMPLETED` — a completed sprint's membership is frozen in both directions, though every other field of such an issue stays editable. `storyPoints` sets the estimate (`0`–`999`, at most 2 decimals — `422` otherwise) and `clearStoryPoints: true` marks the issue unestimated again; a real change writes one `storyPoints` history entry, a no-op writes none. Neither of them touches the issue's **rank** — that is the separate [rank endpoint](#sprints--backlog). Inside `fields` only the listed field ids change; JSON `null` clears a value (required fields cannot be cleared):

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

Custom field changes appear with the field's display name in `field` and human-readable values (option labels rather than ids, user display names, `yes`/`no` for checkboxes). A [label](#labels) change is recorded once under `labels`, with the label names before and after comma-joined (a label **merge** deliberately writes no per-issue history — it can touch thousands of issues). A [component](#components) change is recorded under `component` with the old and new component names (a forced component **delete** writes no per-issue history either, for the same reason). [Version](#versions) changes are recorded per role, under `fixVersions` and `affectsVersions`, with the names before and after comma-joined — and, for the same "one request must stay bounded" reason, a version **delete** (forced or remapped) and a release-time `moveUnresolvedToVersionId` write no per-issue history. A [sprint](#sprints--backlog) change is recorded under `sprint` with the old and new sprint names (`null` when there is none), and a story-point change under `storyPoints` — while a **rank** change writes nothing at all, because positional churn would drown the log.

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
- A workspace that has reached its label limit (1000) returns `422` — archive or delete some first. Archived labels count toward the cap (they keep their name slot).

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

**Using labels** — attach them with `labelIds` when creating or updating an [issue](#issues) (full-replacement set), filter the board/backlog with `?labelId=&labelMatch=`, and query them in [HQL](#search-hql) as `label` (alias `labels`). An issue's own labels come back in `IssueResponse.labels` as `{id, name, color, archived}`, ordered by name, `[]` when there are none. An issue may carry at most 20 labels.

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
- A project that has reached its component limit (500) returns `422` — archive or delete some first. **Archived components count toward the cap** (they keep their unique name slot and still show up under `includeArchived=true`), so create → archive → repeat cannot get you past it.

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

**The project's [delivery capabilities](#delivery-capabilities) change nothing here.** A project with `releases: false` still creates, lists, links and returns versions exactly as one with `releases: true` does — the capability hides the Releases page and the fix/affects pickers in the UI, and nothing else.

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
- A project that has reached its version limit (500) returns `422` — delete some first. **Archived and released versions count toward the cap** (they keep their unique name slot and still show up under `includeArchived=true`), so create → archive → repeat cannot get you past it, and archiving does not free a slot.

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

**Using versions** — set `fixVersionIds` / `affectsVersionIds` when creating or updating an [issue](#issues) (full-replacement sets, one per role), filter the board/backlog with `?fixVersionId=`, and query them in [HQL](#search-hql) as `fixVersion` and `affectsVersion`. An issue's own versions come back in `IssueResponse.fixVersions` and `IssueResponse.affectsVersions` as `{id, name, released, archived}`, ordered by name, `[]` when there are none. An issue may carry at most 20 versions **per role**.

## Sprints & backlog

A **sprint** is one project's iteration — a time-box with a goal, a start and an end, holding the issues the team committed to. Like [labels](#labels), [components](#components) and [versions](#versions) it is *content*, not configuration: sprints never appear in a project's [config](#project-configuration), and two projects may each run a "Sprint 7".

**Lifecycle — one way only.** A sprint is created `FUTURE` (a planning bucket), moves to `ACTIVE` when it is started, and ends `COMPLETED` when it is completed. **At most one sprint per project may be active**, and that is enforced by the database rather than only in code. **There is no re-open**: a completion is a reported event (the done-vs-carried-over numbers were handed to the user and the unfinished issues were already moved), so the recovery path is "create a new sprint and move the issues back" — two clicks, no ambiguity.

**Rank.** Every issue in a project carries a position in one project-wide order that the **board and the backlog share** — there is no second "backlog order" that could disagree with the board. The rank is **server-written only** and is deliberately not exposed in any response: to move an issue you name the neighbours you dropped it between (`POST …/issues/{number}/rank`), and the server computes the placement. A newly created issue lands at the **bottom** of the backlog (filing an issue is not a priority statement).

**Story points** are a native issue attribute (`storyPoints` on every issue), not a custom field: `0`–`999` with at most 2 decimals, where `null` means **unestimated** — deliberately not the same as `0`, which is why the section totals report `unestimatedCount` separately.

**Permissions.** Reads (sprint list/detail, completion preview, the backlog view) need project membership. The **lifecycle** — create, rename/re-plan, start, complete, delete — needs the **project curator** role: project `MANAGER`, **or** an `OWNER`/`ADMIN` of the enclosing workspace (who need not be a project member). Putting issues *into* a sprint, taking them out and dragging them around is the ordinary **issue-edit** tier, because planning is teamwork and requiring `MANAGER` to drag would make the backlog read-only for most of the team. A missing workspace, a missing project or a non-member all give `404`, never `403`; `403` is reserved for a member without the curation role.

**The project's [delivery capabilities](#delivery-capabilities) change nothing here.** `board: KANBAN` does not close the sprint API and `estimation: false` does not reject `storyPoints` — every endpoint below behaves identically whatever a project has declared. A capability hides vocabulary in the UI; it is never a permission.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/sprints?state=&page=&size=` | member | The project's sprints — **always paginated** |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints` | curator | Create a `FUTURE` sprint. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}` | curator | Rename / re-goal / re-plan the dates |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/start` | curator | `FUTURE` → `ACTIVE` (body optional) |
| `GET` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/completion-preview` | member | What completing it would report |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/complete` | curator | `ACTIVE` → `COMPLETED` (body required) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/issues` | member | Put a batch of issues into the sprint |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/issues/{issueId}` | member | Take one issue out. `204` (idempotent); `422` for a completed sprint |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}?force=false` | curator | Delete. `204` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/backlog?…&includeDone=false` | member | The whole planning view in one request |
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/rank` | member | Move an issue in the shared rank |

**List** — always paginated (the [envelope](#conventions), default size 50): open sprints are capped, but completed ones accumulate for years. The order is `ACTIVE` first, then `FUTURE` by `sequence` ascending, then `COMPLETED` newest-first. `state` is a **repeatable** filter (`?state=ACTIVE&state=FUTURE`); omitting it returns every state. The counters are always filled — they cost one grouped query for the whole page, so there is nothing to opt out of:

```json
{ "id": "0198f7d4-…", "name": "Sprint 7", "goal": "Ship billing v2",
  "state": "ACTIVE", "sequence": 7,
  "startAt": "2026-08-10T09:00:00Z", "endAt": "2026-08-24T17:00:00Z",
  "completedAt": null, "daysRemaining": 6,
  "issueCount": 12, "doneIssueCount": 7,
  "points": 34.5, "donePoints": 21, "unestimatedCount": 2,
  "createdAt": "…", "updatedAt": "…" }
```

`daysRemaining` counts whole days from today (UTC) to `endAt` for an **active** sprint only — `0` means it ends today and a **negative** value means it is overdue; it is `null` for a future or completed sprint, which has nothing to count down. `points` sums the story points of the sprint's issues (unestimated ones contribute nothing rather than zero), and `donePoints` sums them over the DONE-category issues.

**Create** — the body is `{"name?", "goal?", "startAt?", "endAt?"}` and everything in it is optional, so `{}` creates the next sprint:

- a blank or absent `name` becomes `"Sprint {sequence}"` — which is why default names can never collide. A supplied name is normalized server-side (Unicode NFC, invisible control/format characters stripped, whitespace trimmed and collapsed) and must be 1–60 characters afterwards, else `400`;
- names are **unique per project, case-insensitively, completed sprints included** — a collision is a `409`. The same name in a different project is fine;
- `goal` is optional, max 500 characters;
- `startAt`/`endAt` are only **the plan** — filling them in does not start anything. `endAt <= startAt` is a `422`;
- a project that already holds the maximum number of **open** sprints (20 — `FUTURE` + `ACTIVE`; completed ones are history and don't count) returns `422`.

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/sprints \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Sprint 7", "goal": "Ship billing v2"}'
```

**Update** — `PATCH` takes `{"name?", "goal?", "startAt?", "endAt?", "clearStartAt?", "clearEndAt?"}`; omitted fields are left unchanged, and `clearStartAt`/`clearEndAt` unset the dates (the `assigneeId`/`clearAssignee` convention — a plain `null` can't be told apart from an omitted field). There is deliberately **no** `state` field: the lifecycle moves only through the two calls below, and it never moves backwards.

**Start** — `POST …/sprints/{sprintId}/start`, body **optional**: a bare `POST` means "start it now and run it for 14 days". `{"startAt?", "endAt?", "goal?"}` overrides any of that; `endAt` defaults to `startAt` + the instance's default sprint length, and a `startAt` in the past is allowed on purpose (backfilling a sprint that actually began on Monday is normal).

- Starting a sprint that is not `FUTURE` — already active, or completed — is a `409`, so a double-click can never re-start anything.
- Starting one while **another sprint of the project is already active** is a `409` too, and that verdict comes from a database-level uniqueness rule, so two simultaneous starts always resolve to exactly one winner.
- Starting an **empty** sprint is allowed: blocking it would only push teams to file a placeholder issue. The UI warns; the API does not.

**Completion preview** — `GET …/sprints/{sprintId}/completion-preview` is the completion dialog's data source and is readable by any project member (looking at the numbers is not a commitment). It returns the same counters the completion itself reports, off the same query, so the two cannot drift:

```json
{ "totalIssueCount": 12, "doneIssueCount": 7, "unfinishedIssueCount": 5,
  "totalPoints": 34.5, "donePoints": 21, "unfinishedPoints": 13.5,
  "targetCandidates": [ { "id": "0198f7d5-…", "name": "Sprint 8", "state": "FUTURE" } ] }
```

An empty `targetCandidates` means the project has no future sprint to carry work over to — offer "create a sprint" instead.

**Complete** — `POST …/sprints/{sprintId}/complete`. Unlike `start` the body is **required**, because the disposition of the unfinished work is a real decision and defaulting it silently would move issues nobody asked to move:

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/sprints/$SPRINT/complete \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"moveUnfinishedTo": "SPRINT", "targetSprintId": "0198f7d5-…"}'
```

- `moveUnfinishedTo` is `BACKLOG` or `SPRINT`; with `SPRINT` a `targetSprintId` is required and must be a **`FUTURE` sprint of the same project** and not the sprint being completed — anything else is a `422`.
- Every issue whose status category is **not DONE** moves to the chosen destination. **DONE issues keep their sprint** — that is the sprint's record of what it delivered.
- The **rank is never rewritten**, so carried-over items keep their relative order wherever they land.
- Completing something that is not `ACTIVE` is a `409` — a double-click gets one `200` and one `409`, never a second destructive move.

The response is the honest done-vs-carried-over report (nothing is persisted as a report artifact — burndown and velocity are not part of this release):

```json
{ "sprint": { "id": "0198f7d4-…", "state": "COMPLETED", … },
  "completedIssueCount": 7, "carriedOverIssueCount": 5,
  "carriedOverToSprintId": "0198f7d5-…",
  "donePoints": 21, "carriedOverPoints": 13.5 }
```

**Putting issues in and taking them out** — `POST …/sprints/{sprintId}/issues` takes `{"issueIds": [...], "position": "TOP"|"BOTTOM"}` (default `BOTTOM`) and returns the sprint with refreshed counters. Issues are addressed by **id** here. An issue already in the sprint is a silent no-op (no history entry, no `version` bump); the rest are placed at the top or bottom of the sprint's slice of the shared rank, keeping their relative order among themselves. An unknown or foreign-project issue id is a `422` ("Unknown issue" — never a `404`, which would confirm existence), an empty list or more than 100 distinct ids is a `400`, and a `COMPLETED` target sprint is a `422` (assigning to the *active* one mid-sprint is fine — a scope change is a real event).

This call is a **removal** path too: an issue joining the sprint leaves whichever sprint it is in today, so a completed sprint's frozen membership applies in both directions here. You get `422 "Sprint 'X' is completed"` when the **target** sprint is completed, **and** when any issue in `issueIds` is currently in a completed sprint (`X` is then that source sprint) — the same message and `ProblemDetail` shape as `DELETE …/sprints/{sprintId}/issues/{issueId}`. The batch is validated as a whole before anything moves, so the rejection is **atomic**: one frozen member fails the request and *no* issue changes sprint or rank, rather than leaving the move half-applied. Issues already in the target sprint are no-ops and are not part of that check.

`DELETE …/sprints/{sprintId}/issues/{issueId}` takes one issue back out, preserving its rank. It is **idempotent**: removing an issue that is not in that sprint returns `204` as well, because the request expresses "this issue must not be in this sprint" and that is already true. An `issueId` that does not resolve **within the project** is still a `404`. A **`COMPLETED`** sprint is a `422` ("Sprint 'X' is completed"): its membership is the delivery record the completion already reported, so it is frozen on the removal side exactly as it is on the assignment side. The two rules coexist in that order — the idempotent check runs **first**, so an issue that is not in the sprint still gets `204`, completed or not.

**Delete** — `DELETE …/sprints/{sprintId}` is curator-only and refuses an **ACTIVE** sprint with `409` ("complete it first"). A future or completed sprint that still holds issues is a `409` too unless you pass `force=true`, which detaches them first — their **rank is preserved**, so they keep their relative place in the backlog they return to. If another curator deleted the same sprint a moment earlier you get a `404` rather than a `500`: the row is removed by a conditional delete, so the loser of that race is told the sprint is gone — which it is, so treat it as success.

**Archived projects.** Every sprint mutation (create, update, start, complete, delete, add/remove issues), every rank move and the project's own `PATCH …/projects/{pId}` on an **archived project** return `409 "Project is archived"`. Reads keep working.

### The planning view

`GET …/projects/{pId}/backlog` returns everything the backlog screen renders in one round trip: the project's **open** sprint sections (`ACTIVE` first, then `FUTURE` by `sequence`) above the rank-ordered backlog. It takes exactly the same filters as the [issue list](#issues) (`statusId`, `assigneeId`, `priorityId`, `componentId`, `labelId` + `labelMatch`, `fixVersionId`), all applied server-side so filtering searches the whole project rather than the page already on screen, plus `includeDone`.

`includeDone` defaults to `false` — a done, unranked issue is planning noise. It affects the **backlog** section only: sprint sections always include their DONE issues, because that is the sprint's record of what it delivered.

```json
{
  "sprints": [
    { "sprint": { "id": "0198f7d4-…", "name": "Sprint 7", "state": "ACTIVE", … },
      "issues": [ { "id": "…", "key": "DEMO-18", … } ],
      "truncated": false, "totalAvailable": 12,
      "stats": { "issueCount": 12, "doneIssueCount": 7,
                 "points": 34.5, "donePoints": 21, "unestimatedCount": 2 } }
  ],
  "backlog": {
    "issues": [ … ], "truncated": true, "totalAvailable": 812,
    "stats": { "issueCount": 812, "doneIssueCount": 0,
               "points": 1204, "donePoints": 0, "unestimatedCount": 310 }
  },
  "sectionCap": 300,
  "bulkMoveCap": 100
}
```

Each section is capped independently at `sectionCap` (300) and reports `truncated` / `totalAvailable` — but its `stats` are computed over the **whole** section, so a truncated section still shows honest totals. Treat sections as independently refreshable.

`bulkMoveCap` (100) is the limit on **one** `POST …/sprints/{sprintId}/issues` call, and it is a *different* number from `sectionCap` on purpose. A "move everything to sprint X" action is driven by the section you rendered, so a client that assumes one cap from the other would `400` on every section larger than the bulk limit. Chunk bulk moves at `bulkMoveCap` instead of hardcoding a value.

### Ranking an issue

`POST …/issues/{number}/rank` moves one issue in the order the board and the backlog share, optionally into or out of a sprint in the same request. The moved issue is addressed by **number** (the usual issue addressing) while the anchors and the sprint travel as **ids**:

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/issues/18/rank \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"afterIssueId": "0198a…", "beforeIssueId": "0198b…", "sprintId": "0198f7d4-…"}'
```

- You send **anchors, never a rank value**. Supply `afterIssueId`, `beforeIssueId`, or both — the server fills in whichever neighbour is missing by looking at the target section. Sending **neither** is only valid together with a sprint change, and means "append to the end of that section"; a request with no anchor and no sprint change asks for nothing and is a `400`.
- `sprintId` moves the issue into a sprint at the same time and `clearSprint: true` returns it to the backlog; sending both is a `400`. An unknown, foreign-project or **completed** target sprint is a `422` — and so is moving an issue whose **current** sprint is `COMPLETED`, in or out: that membership is a delivered fact and is frozen both ways. A pure rank move *within* a completed sprint is still fine.
- Both anchors must already be **in the target section** — dropping next to a row that lives in a different sprint is an ordering paradox, not a placement, so it is a `422`; so is naming the moved issue itself as its own anchor, or an anchor that does not resolve in the project.
- If the anchors are no longer consistent with each other (someone else re-ordered the list under you), the answer is `409` "the list changed — refresh", never a silent arbitrary placement.
- `version` is **optional**: send it and you get the usual `409` on a stale read; omit it and the move simply applies. Ranking is a positional, last-drag-wins operation, so a mandatory optimistic check would produce a storm of conflicts during a planning meeting.
- Dropping an issue into the same gap over and over eventually exhausts it, and the server re-spaces the whole project's ranks. That rebalance is **throttled to once per project per 60 s** — a second one inside the window answers `429` with a `Retry-After` header (seconds). This is a **retryable throttle, not a fault**: nothing was moved, and the identical request succeeds after the wait. Back off for `Retry-After` rather than retrying immediately. Hitting it in normal use is essentially impossible — right after a rebalance every gap is wide again, so exhausting one takes ~26 successive drops into that same spot.
- The response is the full updated [`IssueResponse`](#issues). A rank change writes **no** history entry (positional churn would drown the log); a sprint change in the same request does.

**Using sprints elsewhere** — set `sprintId` / `storyPoints` when creating or updating an [issue](#issues) (`clearSprint` / `clearStoryPoints` unset them), filter the board and backlog with `?sprintId=` or `?noSprint=true`, and query them in [HQL](#search-hql) as `sprint` (alias `sprints`) and `storyPoints` (alias `points`). An issue's own sprint comes back in `IssueResponse.sprint` as `{id, name, state}`, or `null`.

## Search (HQL)

Search issues across a whole workspace with **HQL** (Hamstrack Query Language) — a small, readable query language. Results are cross-project but always restricted to the caller's **visible (non-archived) projects**; this scope is enforced server-side and no query text can widen it. All three endpoints require workspace membership (`404` for a non-member).

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/search` | member | Run an HQL query. Paginated ([envelope](#conventions), `size` clamped `1`–`100`) |
| `GET` | `/workspaces/{wsId}/search/schema` | member | Autocomplete metadata: fields, per-field operators, and value picklists. **Suggestion-only, and [capability-aware](#search-hql)** — a field it omits still resolves |
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

- **Fields:** `status`, `assignee`, `reporter`, `type`, `priority`, `created`, `updated`, `due`, `parent`, `text`, `label` (alias `labels`), `component` (alias `components`), `fixVersion`, `affectsVersion`, `sprint` (alias `sprints`), `storyPoints` (alias `points`), plus any **custom field** by its `key` (e.g. `severity`). `/search/schema` advertises the fields your autocomplete should offer — that list is [capability-aware](#search-hql) and can be a **subset** of what actually resolves, so treat it as vocabulary, not as the definitive query surface. Field names are matched case-insensitively, so the all-lowercase `fixversion` / `affectsversion` spellings work too — `/search/schema` always advertises the camelCase name, exactly once per field. **That list is exhaustive: there is no `project` field**, so a query cannot be narrowed to one project — the scope is always all of your visible projects, and each result row names its owner (`projectId` / `projectKey` / `projectName`) instead.
- **Operators:** `=` `!=` `IN` `~` `>` `<` `>=` `<=`. Which operators a field accepts is per-field (see `/search/schema`): `~` is a case-insensitive substring match, only on `text` (title + description); ordered comparisons (`>` `<` `>=` `<=`) apply to the date fields and to `priority`.
- **Booleans:** combine terms with `AND`, `OR`, `NOT` and parentheses `( )`. Precedence is `NOT` > `AND` > `OR`.
- **Emptiness:** `field IS [NOT] EMPTY` for nullable fields (`assignee`, `parent`, `due`, `label`, `component`, `fixVersion`, `affectsVersion`, `sprint`, `storyPoints`, and every custom field except CHECKBOX).
- **Sorting:** an optional trailing `ORDER BY field [ASC|DESC], …` clause — it must come last. Custom fields are **not sortable** (yet).
- **Functions:** `currentUser()`, `now()`, `startOfWeek()` (evaluated in server UTC).
- **Values:** status / type / priority are given by **name** (quote names with spaces, e.g. `"In Progress"`); users by email, display name, `currentUser()`, or UUID; dates as `YYYY-MM-DD`.
- **Labels:** `label` (alias `labels`) is many-valued and given by **name** — operators `= != IN` plus `IS [NOT] EMPTY`; it is **not sortable** (an issue has a *set* of labels, so `ORDER BY label` is a `422`). `label = "needs-design"` means "carries it", `label != "needs-design"` means "does not carry it" (issues with no labels at all included), and `label IS EMPTY` means "carries no label". Archived labels are excluded from name resolution, so a name that no longer exists in the workspace fails with `422` when the query runs — including for a stored [saved filter](#saved-filters).
- **Components:** `component` (alias `components`) is single-valued and given by **name** — operators `= != IN` plus `IS [NOT] EMPTY` — and, unlike `label`, it **is sortable** (`ORDER BY component` orders by component name and keeps issues that have none). Names resolve across your visible **projects**, so `component = Billing` matches the "Billing" of every project you can see. `component IS EMPTY` means "has no component". Archived components are excluded from name resolution (a name that only an archived component holds is a `422` at run time), even though issues keep carrying them.
- **Versions:** `fixVersion` ("ships in") and `affectsVersion` ("broken in") are two fields over the same link table, told apart by the role. Both are many-valued and given by **name** — operators `= != IN` plus `IS [NOT] EMPTY` — and, like `label`, **not sortable** (an issue has a *set* of versions, so `ORDER BY fixVersion` is a `422`). `fixVersion = "2.4.0"` means "ships in 2.4.0", `fixVersion != "2.4.0"` means "does not" (issues with no fix version at all included), and `fixVersion IS EMPTY` means "not scheduled for any release" — the unassigned-work query. An affects link never satisfies a `fixVersion` term and vice versa. Names resolve across your visible **projects**, so `fixVersion = "2.4.0"` matches the "2.4.0" of every project you can see. Archived versions are excluded from name resolution (a `422` at run time), even though issues keep carrying them.
- **Sprints:** `sprint` (alias `sprints`) is single-valued and given by **name** — operators `= != IN` plus `IS [NOT] EMPTY` — and, like `label`, **not sortable** (`ORDER BY sprint` is a `422`: sprint order across several projects has no common meaning). `sprint IS EMPTY` means "in no sprint", i.e. the backlog. Names resolve across your visible **projects**, so `sprint = "Sprint 7"` matches the "Sprint 7" of every project you can see. **Completed** sprints are excluded from name resolution — years of history would flood the namespace — though issues carrying them still match by id.
- **Story points:** `storyPoints` (alias `points`) is a native numeric field, so it takes ordered comparisons directly (`= != > < >= <=`) and **is sortable** — `ORDER BY storyPoints DESC` is the "show me the big ones first" query. `storyPoints IS EMPTY` means **unestimated**, which is deliberately not the same statement as `storyPoints = 0`. The operand is held to the field's own domain — the same `0`–`999` with at most 2 decimals that writing an estimate allows — so `storyPoints > 1e999` or `storyPoints = 1.234` comes back as the usual `422` semantic error naming the field, not a `500`. A comparison that could never match a row is rejected up front rather than passed to the database. Example: `sprint = "Sprint 7" AND storyPoints >= 5`.
- **Custom fields:** queried by `key`, with operators per type — TEXT / TEXTAREA / URL: `= != ~`; NUMBER / DATE: `= != > < >= <=`; SELECT: `= != IN`; MULTI_SELECT: `=` (contains) / `IN` (any-of); USER: `= != IN` + `currentUser()`; CHECKBOX: `=`. All support `IS [NOT] EMPTY` except CHECKBOX. SELECT/MULTI_SELECT values are given by option **label or id**; USER values by email / display name / `currentUser()` / UUID; DATE as `YYYY-MM-DD`. Example: `severity = "High" AND environment = prod`.
- **Retired field keys:** `story_points` — the custom-field key that release 0.13.0 archived when story points became a native field — still resolves, as a permanent alias for `storyPoints`, so a [saved filter](#saved-filters) written against the old key keeps running unchanged (no migration ever rewrites the stored text of a filter). Two observable details: a workspace that owns **its own** custom field keyed `story_points` still wins — the alias is a last-resort fallback consulted only after system fields and your visible custom fields, so it can never shadow a field of yours — and once the alias has been applied, errors name the **canonical** field: `story_points IN (5)` answers `422 "The IN operator is not allowed on field 'storyPoints'"`. Aliases are compatibility, not vocabulary: they are never advertised by `/search/schema`.

More examples: `type = Bug AND priority >= High AND due IS NOT EMPTY` · `assignee IS EMPTY AND created >= startOfWeek()` · `text ~ "flux capacitor" OR parent = "DEMO-12"`.

**Invalid queries** return `422` with a machine-readable anchor so a UI can underline the problem:

- a **parse error** carries `"errorType": "PARSE_ERROR"` with `position` (0-based character offset), `length`, and (when known) the offending `token`;
- a **semantic error** (e.g. unknown field, illegal operator, a non-sortable field in `ORDER BY`) carries `"errorType": "SEMANTIC_ERROR"` with the offending `field` and `position`.

```json
{ "type": "about:blank", "title": "Unprocessable Content", "status": 422,
  "detail": "Unknown field 'asignee' — did you mean 'assignee'?",
  "errorType": "SEMANTIC_ERROR", "field": "asignee", "position": 0 }
```

**Suggestions are capability-aware; resolution is not.** A project's [delivery capabilities](#delivery-capabilities) narrow what the two autocomplete endpoints *offer*, and nothing else:

- `/search/schema` lists `sprint`, `fixVersion`, `affectsVersion` and `storyPoints` only when **at least one** of your visible projects has the matching capability on (`board: SCRUM`, `releases`, `estimation` respectively). Every other field is listed always. In a workspace whose only visible project is `board: KANBAN`, `releases: false`, `estimation: false`, `fields` comes back as exactly `status` `type` `priority` `assignee` `reporter` `parent` `text` `created` `updated` `due` `label` `component` — plus your custom fields — with the four capability-gated names absent.
- The `SPRINT` and `VERSION` picklists carry names only from the visible projects that have the capability **on** (they were already scoped to your visible projects; this is one predicate more). With every capability off, both come back empty.
- `/search/suggest?field=fixVersion` and `?field=affectsVersion` are scoped the same way — that endpoint is the overflow of the same `VERSION` picklist, so it could not honestly answer differently. `label`, `component` and the user-valued fields are unaffected.

**Hidden fields still resolve — this is a guarantee, not an implementation detail.** A field omitted from `/search/schema` still **parses, compiles and runs**, whether you typed it by hand or loaded it from a [saved filter](#saved-filters). On that same all-capabilities-off project, `sprint = "Smoke Sprint"` returns `200` with its rows, while `sprint = "No Such Sprint"` returns the ordinary field-anchored `422` — exactly the two answers you would get with sprints on. No capability ever changes a status code ([capabilities gate the UI, never the API](#delivery-capabilities)); a colleague flipping a project toggle must never break your saved filter.

The asymmetry that follows is deliberate, and integrations need to expect it: **the suggested set is no longer the same as the resolvable set.** Because capabilities never delete or block data, a Kanban project may legitimately own sprints and a `releases: false` project may legitimately own versions; those names vanish from suggestions while still matching in queries. Do not use `/search/schema` as a validator for a query you are about to send — the query endpoint is the only authority on what resolves.

**Schema** — `GET /search/schema` drives autocomplete: `fields` describes each queryable field (`name`, data-type `type`, allowed `operators`, `nullable`, `sortable`, `valueSuggest`, `functions`) — the system fields first, then your visible **custom fields** (`name` = the field `key`, `type` = its custom `FieldType`) — `keywords` lists the HQL keywords, and `values` holds the small picklists (`STATUS`/`TYPE`/`PRIORITY`) of names reachable by your visible projects, a `LABEL` picklist of the workspace's non-archived label names, a `COMPONENT` picklist of the non-archived component names of those visible projects, a `VERSION` picklist of the non-archived version names of those projects **that have `releases` on** (**one** picklist serves both `fixVersion` and `affectsVersion` — the two roles draw from the same catalog, so both fields declare `valueSuggest: "VERSION"`), a `SPRINT` picklist of the **open** (non-completed) sprint names of those projects **that have `board: SCRUM`**, plus a `CUSTOM:<key>` entry per SELECT/MULTI_SELECT custom field (options as `{label, value=optionId}`). The `LABEL`, `COMPONENT`, `VERSION` and `SPRINT` picklists are **capped at 200 entries** each — a workspace can accumulate far more, so fall back to `/search/suggest?field=label` / `?field=component` / `?field=fixVersion` beyond that. **`sprint` is the exception: it has no `/search/suggest` fallback** (`?field=sprint` returns the usual `422`), because a project's open sprints are already bounded and cannot realistically overflow the picklist. `storyPoints` has no picklist at all — it is numeric. The member list (including USER custom fields) is deliberately **not** embedded — use `/search/suggest` for it. The system-field half of `fields`, and the `SPRINT` / `VERSION` picklists, are **capability-aware** (above): a field or a name that is missing here is still perfectly queryable.

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

…and **versions** — `field=fixVersion` or `field=affectsVersion`, over the non-archived version names of the projects you can see **that have `releases` on** (the same capability scoping the `VERSION` picklist applies, since this endpoint is that picklist's overflow; a version whose project has releases off is still perfectly queryable by name; with no releases-on project you simply get an empty `suggestions` list and a `200`, never an error). Both roles share one catalog, so the two field names return identical suggestions (names are de-duplicated case-insensitively, so a "2.4.0" shipped by two projects is offered once):

```json
{ "field": "fixVersion",
  "suggestions": [ { "label": "2.4.0", "value": "2.4.0" } ] }
```

**`sprint` is intentionally not served here.** Its `/search/schema` `SPRINT` picklist is already bounded by the per-project open-sprint cap, so no typeahead was wired — `?field=sprint` returns the same `422` any other non-suggestable field returns. `storyPoints` is numeric and has no picklist either.

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
