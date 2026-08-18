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
- [Permissions](#permissions)
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
- [Sprints & backlog](#sprints--backlog)
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
| `BOARD_MAX_ISSUES` (`app.board.max-issues`) | `500` | Server-side cap on the unpaged board shape of [`GET …/issues`](#issues) — echoed to clients as `cap`, never client-overridable; beyond it the response reports `truncated: true` with the full count in `totalAvailable`. Valid range 1–20000 (20 000 is the most assembled issues one unpaged response may build — the same budget the planning view is held to, see `AGILE_MAX_OPEN_SPRINTS` below) — an out-of-range value aborts startup instead of being clamped |
| `MAX_LABELS_PER_ISSUE` | `20` | Max labels accepted in one issue payload (`labelIds`, `422` beyond it) and max distinct `labelId` filter values on the issue list (`400` beyond it) |
| `MAX_LABELS_PER_WORKSPACE` | `1000` | Max labels in one workspace's [catalog](#labels); `POST /labels` beyond it returns `422` |
| `MAX_COMPONENTS_PER_PROJECT` | `500` | Max components in one project's [catalog](#components), **archived ones included**; `POST …/components` beyond it returns `422`. Valid range 1–100000 — an out-of-range value aborts startup instead of being clamped |
| `MAX_VERSION_LINKS_PER_ISSUE` | `20` | Max [versions](#versions) linked to one issue, enforced **per link type**: up to this many `fixVersionIds` **and** up to this many `affectsVersionIds` (independent budgets), `422` beyond it. Valid range 1–100 — an out-of-range value aborts startup instead of being clamped |
| `MAX_VERSIONS_PER_PROJECT` | `500` | Max versions in one project's [release plan](#versions), **archived and released ones included**; `POST …/versions` beyond it returns `422`. Valid range 1–100000 — an out-of-range value aborts startup instead of being clamped |
| `AGILE_SECTION_MAX_ISSUES` (`app.agile.section-max-issues`) | `300` | Per-section cap in the [planning view](#sprints--backlog) `GET …/backlog`. A section holding more reports `truncated: true` with the full count in `totalAvailable`, while its `stats` still cover the whole section. Valid range 1–2000, but **not independently attainable** — see the joint bound on `AGILE_MAX_OPEN_SPRINTS` (at the default 20 open sprints this effectively caps out around 952). An out-of-range value aborts startup instead of being clamped |
| `AGILE_MAX_OPEN_SPRINTS` (`app.agile.max-open-sprints-per-project`) | `20` | Max **open** ([`FUTURE`](#sprints--backlog) + `ACTIVE`) sprints per project; `POST …/sprints` beyond it returns `422`. `COMPLETED` sprints are history and do not count. Valid range 1–100 — an out-of-range value aborts startup instead of being clamped. **Validated jointly with `AGILE_SECTION_MAX_ISSUES`:** `(this + 1) × section cap` must stay ≤ 20 000, because one `GET …/backlog` assembles that many issues in a single unpaged response; both knobs at their individual maxima would be ~202 000 rows, so startup fails rather than the response OOMing later |
| `AGILE_DEFAULT_SPRINT_LENGTH_DAYS` (`app.agile.default-sprint-length-days`) | `14` | The `endAt` a [sprint start](#sprints--backlog) defaults to when the caller sends none (`startAt` + this many days). Valid range 1–90 — an out-of-range value aborts startup instead of being clamped |
| `AGILE_MAX_BULK_MOVE` (`app.agile.max-issues-per-bulk-move`) | `100` | Max distinct `issueIds` accepted by `POST …/sprints/{id}/issues` in one request (`400` beyond it — chunk the request). Echoed to clients as `bulkMoveCap` in the [planning view](#sprints--backlog) `GET …/backlog`, so they chunk at your value instead of a hardcoded one — it is a **separate** knob from `AGILE_SECTION_MAX_ISSUES`. Valid range 1–500 — an out-of-range value aborts startup instead of being clamped |
| `DB_LOCK_TIMEOUT_MS` (`app.locking.lock-timeout-ms`) | `3000` | How long a transaction that takes row locks (the [membership mutations](#managing-members) — workspace member role change and removal, and project member removal) may wait for one, in milliseconds. Exceeding it is a retryable `409` with `Retry-After` (see [Errors](#errors)), never a `500`. Applied with `SET LOCAL` to that transaction only, so Flyway migrations sharing the datasource are unaffected. Lower it to shed load faster under contention, raise it if legitimate removals of very busy members time out. Valid range 100–60000 — an out-of-range value aborts startup instead of being clamped (PostgreSQL reads `0` as "wait for ever", which is the setting this exists to prevent) |
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

Uploads have two size ceilings and the `413` wording tells them apart: the in-app per-file limit (`ATTACHMENT_MAX_FILE_SIZE`) answers `"File exceeds the 20 MB limit"` — with whatever the configured size is — while the servlet multipart ceiling (`ATTACHMENT_MAX_UPLOAD_SIZE`) answers `"File is too large"`.

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

**`errorType` says which failure a body is**, wherever one status covers more than one. It is a plain string extension, stable and safe to branch on where the wording of `detail` is not: an [invalid HQL query](#search-hql) carries `PARSE_ERROR` or `SEMANTIC_ERROR`, and a member removal refused over project administrators carries `STRANDED_PROJECTS` or `ADOPTION_BLOCKED`. Two rules for consuming it: treat a value you do **not** recognise as "no recovery I know about" rather than guessing at one, and do **not** read its *absence* as an unknown value — a response that needs no discriminator simply has none (the lock-contention `409` above is exactly that case, and is identified by `Retry-After` instead).

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
  "detail": "Your own role in Alpha (P17) holds more than “Team lead” does, so taking it over would take that away from you and nobody could give it back. Ask the member you are removing to appoint another administrator there while they still can, or have someone who already administers that project remove them instead.",
  "errorType": "ADOPTION_BLOCKED",
  "projects": [
    { "id": "0198c4a1-…", "key": "P17", "name": "Alpha" }
  ]
}
```

**`errorType` tells the two apart** — they share a status and a `projects` extension, and a client that only renders "these projects are in the way" stays correct without knowing which one it received, but one that offers a *retry* must know, because the two demand opposite behaviour:

| `errorType` | What it means | What a client should offer |
|---|---|---|
| `STRANDED_PROJECTS` | The ordinary refusal | Repeating the request with `adoptStrandedProjects=true` **will** clear it — an adopt button is safe |
| `ADOPTION_BLOCKED` | That retry has already been made and cannot work | **No retry.** Render `detail`; the way out is somebody else's action |

Treat an `errorType` you do not recognise exactly as `ADOPTION_BLOCKED`: **no retry available**. It is the only safe default, because it is the one that cannot invent a button that `409`s again. Do not branch on the wording of `detail`, which is prose and may change. And do not confuse an unknown value with an *absent* one — a `409` from this endpoint with no `errorType` at all is a different failure entirely (the last-owner invariant, or [lock contention](#errors), which **is** retryable and says so with `Retry-After`). See [Managing members](#managing-members) for the way out of each.

| Status | Meaning |
|---|---|
| `400` | Malformed request or failed validation |
| `401` | Missing/expired/invalid access token |
| `403` | Authenticated and a member, but missing a [permission](#permissions) — the failure names it in `detail` |
| `404` | Not found — or not a member of the containing workspace |
| `409` | Conflict: stale `version`, duplicate name/key, resource in use, or a state invariant (the last owner, the last project administrator) — **plus** the one retryable case above, a lost row-lock race, which carries `Retry-After` |
| `413` | Attachment exceeds the per-file size limit (default 20 MB) or the servlet upload ceiling (default 25 MB) |
| `415` | Attachment file extension is not in the allow-list |
| `422` | Semantically invalid reference (unknown status/type/assignee, a user who [may not be assigned](#assignability--a-422-not-a-403) in this project, workflow-forbidden transition, an [unusable `role` value](#unknown-role--a-422)) |
| `429` | Rate limited — wait the number of seconds in the `Retry-After` header |

### Rate limits

The sensitive auth endpoints (`login`, `register`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`) share a **per-IP budget** (default 15 requests per minute). Additionally, repeated failed logins for one account trigger an **exponential backoff** (defaults: starts at 30 s after 5 consecutive failures, doubles per failure, capped at 15 min); a successful login resets the counter. Both mechanisms respond with `429` and a `Retry-After` header (seconds). Operators tune or disable this via the `RATE_LIMIT_*` variables below. Counters are in-memory (per app node).

One non-auth endpoint has a throttle of its own: [`POST …/issues/{number}/rank`](#sprints--backlog) allows at most one whole-project rank *rebalance* per project per 60 s and answers `429` + `Retry-After` for a second one inside that window. It is a retryable throttle, not a fault — nothing was moved. That cooldown is a fixed internal safety valve with no environment variable, and its counter is in-memory per app node too. The rest of the API is not rate-limited.

## Roles

**System role** (`ADMIN` — instance-wide, typically the instance operator; maintains the global taxonomy via [`/admin/**`](#system-administration)), **workspace roles** (`OWNER`, `ADMIN`, `MEMBER`) and **project roles** (`MANAGER`, `TEAM_LEAD`, `MEMBER`, `COMMENTER`, `VIEWER`). The `seed.admin` account (env `SEED_ADMIN_*`) gets the system `ADMIN` role automatically; `GET /auth/me` returns your `systemRole`.

These are the **built-in** roles, and what follows is what each one *grants* — not a rung it occupies. The server no longer compares roles anywhere: every gate is a [permission](#permissions) check, the role is just the bundle of permissions a member happens to hold.

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

### Unknown role — a `422`

Any endpoint that accepts a `role` answers **`422` `"Unknown role: <what you sent>"`** when the value cannot be assigned: an unknown key, a correctly-spelled key from the *other* scope (`MANAGER` on a workspace endpoint, `OWNER` on a project one), the wrong case (`owner`), or — once workspace-defined roles exist — a role belonging to a workspace you cannot see.

```json
{ "type": "about:blank", "title": "Unprocessable Content", "status": 422,
  "detail": "Unknown role: SUPERUSER" }
```

**One answer for all of those, on purpose.** It would be natural to expect `404` for a role that does not exist here and something else for nonsense; that pair would be an oracle — ask with a role reference and learn from the status code whether it names something real in a workspace you have no access to. So every unusable role value gets the identical `422`, and the `detail` only ever echoes what the caller already sent. `400` would be wrong for the opposite reason: the request is perfectly well-formed, it is the *value* that cannot be honoured — the same shape as `"Unknown label"` or `"Unknown sprint"`.

Two things that are still `400`, not `422`: a **missing, null or empty** `role` (the field is required, and this is ordinary [validation](#validation-failures-400)), and a body that is not valid JSON.

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

Both are `403`, and both are described under [Managing members](#managing-members).

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
| `PATCH` | `/workspaces/{id}/members/{userId}` | `workspace.member.manage` | Change a member's role (`{"role"}`, subject to the grant ceiling on both the old and the new role). Returns the member |
| `DELETE` | `/workspaces/{id}/members/{userId}?adoptStrandedProjects=` | `workspace.member.manage` | Remove a member from the workspace — not their account. `204`; `409` when it would leave a project without an administrator, cleared by repeating the call with `adoptStrandedProjects=true` |
| `POST` | `/workspaces/{id}/invites` | `workspace.member.manage` | Email an invite (`{"email", "role"}`; subject to the grant ceiling, never `OWNER`). `201` |
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

All three endpoints require [`workspace.member.manage`](#permissions) — held by the built-in `OWNER` and `ADMIN` and not by `MEMBER`, which is exactly who could use them before. There is no role *ladder* behind them any more (see [Roles](#roles)); what bounds an edit is the grant ceiling below.

`PATCH …/members/{userId}` takes `{"role": "OWNER" | "ADMIN" | "MEMBER"}` — that is the whole body, and it is required (`400` otherwise; a value that is not an assignable workspace role key is [`422 "Unknown role"`](#unknown-role--a-422)) — and returns the updated membership in the same shape `GET …/members` lists:

```json
{ "userId": "…", "email": "mia@example.com", "displayName": "Mia", "avatarUrl": null, "role": "ADMIN", "joinedAt": "…" }
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

**The adoption itself can be refused, with the same `409` — and this one deliberately offers no retry.** If the caller already holds a role in one of the stranded projects that grants something `TEAM_LEAD` does not, replacing that row would *demote* the very person doing the rescuing, and the [grant ceiling](#managing-members) would then refuse to give the missing rights back, since the departing member was the last holder of them. Skipping the project instead would strand it. So the whole removal is refused and nothing is written — not the adoption, not the unassignment, not the removal. The body is the familiar shape (`409`, a `projects` array), but it lists only the projects that block the adoption, and its `detail` names the obstacle instead of the retry, which would fail identically. This is unreachable with the built-in project roles — each is either covered by `TEAM_LEAD` or already holds `project.member.manage`, in which case the project was never stranded — so it takes a custom project role (a "QA lead" with `issue.delete` and no member management) to see it. **The remedy is somebody else's action, not yours:** ask the member you are removing — still active, still that project's administrator — to appoint a successor while they still can, or have a colleague who already administers the project run the removal instead. Appointing one yourself is not open to you here: `POST …/projects/{projectId}/members` needs `project.member.manage` **in that project**, and this branch is only reachable because you do not have it.

Tell this one apart by `errorType: "ADOPTION_BLOCKED"` (the ordinary refusal is `"STRANDED_PROJECTS"`) and **do not render an adopt button for it** — the flag is already set, and setting it again produces this same `409`.

Why the state is worth refusing a removal over in the first place: `project.member.manage` is **not** part of the workspace-wide curator set, so a workspace `OWNER`/`ADMIN` who holds no membership row in that project cannot add members back, archive it, or manage its project-private taxonomy — and no endpoint restores it, which is why the alternative (letting the removal proceed and silently orphaning those projects) was rejected. That is a property of the roles this release ships rather than a permanent one: the permission model does have workspace-wide and project-default routes that would grant `project.member.manage`, but neither is editable through the API yet. The guard behaves the same either way.

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
| `400` | `PATCH` only — `role` is missing, null or empty |
| `403` | The caller lacks `workspace.member.manage`, the change breaks the grant ceiling, or it involves the `OWNER` role and the caller is not an owner |
| `404` | Unknown workspace, caller not a member, **or** the target holds no membership in *this* workspace |
| `409` | The change would leave the workspace without an `OWNER` (no `errorType`); on `DELETE` only, would leave one or more projects without an administrator (`errorType: "STRANDED_PROJECTS"`), **or** the adoption those projects need would demote the caller (`errorType: "ADOPTION_BLOCKED"`) — both of those bodies also carry `projects`; or a lost row-lock race (no `errorType`, but a `Retry-After` header — just retry) |
| `422` | `PATCH`: `role` is not an assignable workspace role key ([`"Unknown role"`](#unknown-role--a-422)). `DELETE`: the target is the caller (see below) |

The two *invariant* `409`s cannot both be reported, and the last-owner one wins: a sole owner who is also the sole administrator of a project is told to promote another owner first, and sees the project list only once that is done. The self-removal `422` also precedes the project check. A further `409` — [lock contention](#errors) — is not an invariant at all and can arrive on any attempt.

**Tell them apart by the response, never by the wording of `detail`, and in this order:**

1. **`Retry-After` present** → lock contention. Retry the *identical* request unchanged after that many seconds. (No `errorType`, deliberately — this is why the header is checked first.)
2. **`errorType: "STRANDED_PROJECTS"`** → the removal would strand the listed `projects`. Offer the `adoptStrandedProjects=true` retry.
3. **`errorType: "ADOPTION_BLOCKED"`** → that retry was made and cannot work. Render `detail`; offer no retry. (Treat an unrecognised `errorType` as this case.)
4. **Neither** → the last-owner invariant. Promote another owner first.

Cases 2 and 3 are mutually exclusive — which one you get depends on the flag — and both carry `projects`, so a client that only lists the projects in the way can still treat them as one case; only a client that offers a retry has to distinguish them.

That third `404` case is about the **membership**, never about the account: an unknown id, an id belonging to someone in another workspace, and a member who was removed a second ago are one indistinguishable answer, so the endpoint cannot be used to probe which accounts exist. It also makes a repeated `DELETE` a clean `404` instead of an error — safe to retry.

**There is no self-removal here, and it is enforced rather than merely absent.** `DELETE …/members/{userId}` refuses with `422` when the target is the caller: leaving a workspace is a different feature — it needs a confirmation, somewhere to land afterwards, and an answer for "that was my only workspace" — and it is not built yet. A sole owner deleting themselves gets the `409` instead, because "promote another owner first" is the answer that will still be true once leaving exists. Self-*demotion* on `PATCH` is allowed: an owner stepping down to `ADMIN` while another owner exists is an ordinary handover.

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
| `POST` | `/workspaces/{wsId}/projects/{projectId}/members` | `project.member.manage` | Add a workspace member (`{"userId", "role"}`; `422` for an unusable role). `201` |
| `DELETE` | `/workspaces/{wsId}/projects/{projectId}/members/{userId}` | `project.member.manage` | Remove a member. `204`; `409` if they are the project's last administrator, or on lost lock contention (that one carries `Retry-After` — just retry) |

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
  "myRole": "MANAGER",                         // the caller's EXPLICIT project role KEY
  "myPermissions": ["issue.create", "issue.transition", "comment.edit:own", "…"],
  "createdAt": "…"
}
```

Every project response — create, list, get and update — carries [`myPermissions`](#permissions): the caller's effective **project-scoped** permissions in that project, including any they hold through their workspace role rather than a project membership. It is always present, and it can be **wider than `myRole` suggests** — `myRole` reports only the caller's explicit project membership row, and reads `VIEWER` when they have none. Gate on `myPermissions`; display `myRole`.

**Update permission.** `PATCH …/projects/{projectId}` needs [`project.edit`](#permissions) — held by a project `MANAGER` and, across every project of their workspace, by a workspace `OWNER`/`ADMIN` who need not be a project member; any other member gets a [`403` naming it](#what-a-403-says). That is the same permission every other project-content write asks for ([components](#components), [versions](#versions), [sprints](#sprints--backlog) have their own `*.manage` keys with the identical holders). Archiving (`project.archive`) and member management (`project.member.manage`) are **not** part of that set: `project.archive` remains the project `MANAGER`'s alone, and `project.member.manage` is held by the built-in `MANAGER` and `TEAM_LEAD`.

**Creating a project needs `project.create`.** Every built-in workspace role grants it, so every workspace member can still open a project exactly as before; it exists as its own key so that "only admins open new projects" becomes expressible. A caller without it gets a [`403` naming it](#what-a-403-says).

**Listing members needs nothing but membership.** `GET …/projects/{projectId}/members` is open to **any workspace member**, project member or not — the assignee picker, mention autocomplete and a People view all need it, and the workspace member list is already open the same way. Its old gate admitted everyone, so no caller's result changed.

**Adding a member.** `role` is a [project role key](#role-values-are-keys-not-an-enum): `MANAGER`, `TEAM_LEAD`, `MEMBER`, `COMMENTER` or `VIEWER`. Anything else is [`422 "Unknown role"`](#unknown-role--a-422) — including a *workspace* role key such as `ADMIN`, because scope is part of the question and `MEMBER` names a different role in each scope. A missing or empty `role` is a `400`. The grant ceiling applies here too: you cannot hand out a project role that grants a project permission you do not hold yourself, and it also bounds *removals* (the role a removed member falls back to must not exceed yours either).

> **`VIEWER` is accepted but stored as `MEMBER`.** The built-in project role keyed `VIEWER` now grants **nothing**, whereas it historically meant "no explicit row", which granted everything. Writing it literally would silently mint someone who is refused every write, so this endpoint maps it to the contributor role (`MEMBER`) — and the response echoes what was **stored**, not what you sent. Read the `role` you get back rather than assuming it round-trips. A genuinely read-only membership is not expressible through this endpoint yet.

**Removing a member: the last administrator is protected.** `DELETE …/projects/{projectId}/members/{userId}` returns `409 "This is the project's last administrator — add another one first"` when the target is the only member of that project holding [`project.member.manage`](#permissions) — for the same reason the [workspace-level removal](#managing-members) refuses: nobody could manage the project's membership afterwards, and a workspace `OWNER`/`ADMIN` who is not a member of the project cannot step in, because that permission is not part of the workspace-wide curator set. Add another administrator first, then retry. The guard is about the **permission, not one particular role**: any role granting `project.member.manage` makes its holder an administrator here, and a role that does not grant it never does — so both built-in holders (`MANAGER` and `TEAM_LEAD`) are protected, and nothing else is. Unlike the workspace-level conflict this body carries no `projects` extension — the project is the one in the path. A project with no explicit administrator at all is a normal state and is left alone; only the step from one to none is refused.

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

The same catalog and binding operations are available at two **delegated** scopes so teams self-serve without the system administrator. Authorization is membership-based (not the system `ADMIN` role) and is a permission check like everywhere else:

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

**Onboarding users (DC).** Since self-registration is closed by default, create each account via `POST /admin/users` and share the returned `setupLink`. The recipient opens it, chooses a password (`POST /auth/reset-password` under the hood), then signs in normally. No SMTP is required for this flow.

## Issues

Issues live under a project and are addressed by **number** — the numeric part of their key (`DEMO-42` → `…/issues/42`). Numbers are sequential per project and never reused.

**The project's [delivery capabilities](#delivery-capabilities) change nothing here.** `storyPoints`, `sprintId`, `fixVersionIds` and `affectsVersionIds` are accepted, stored and returned identically whatever a project declares — a project with `estimation: false` still takes a point value, and one with `board: KANBAN` still takes a `sprintId`. Every field below is always present in the response too; a capability hides a control in the UI and never removes anything from the API.

**Hierarchy.** An issue may have a parent in the same project, governed by issue-type [hierarchy levels](#project-configuration) (a parent's type level must be strictly greater than the child's). Every `IssueResponse` carries the parent summary (`parentId`, `parentKey`, `parentTitle`, `parentTypeId`, all `null` when there is no parent) and a direct-children roll-up (`childCount`, and `doneChildCount` for children in a DONE-category status). `GET …/issues/{number}/children` lists the direct children in board order.

**Listing — dual shape.** Without `size`, `GET …/issues` returns a `BoardIssuesResponse` object (the board/kanban path): `{ "issues": IssueResponse[], "truncated": boolean, "totalAvailable": integer, "cap": integer }`. `issues` is bounded server-side to `cap` ([`BOARD_MAX_ISSUES`](#operator-settings-that-affect-the-api), 500 by default, never client-overridable); when the project after the same filters exceeds it, `truncated` is `true` and `totalAvailable` reports the full count so the UI can show "Showing first {cap} of {totalAvailable}". Pass `size` to switch to a paginated [envelope](#conventions) (the backlog path); the optional `excludeDone=true` then drops issues in a DONE-category status server-side. The `statusId` / `assigneeId` / `priorityId` / `componentId` / `labelId` / `fixVersionId` / `sprintId` / `noSprint` filters apply in both modes and are ANDed together. Rows come back in the shared backlog/board **rank** order (`position`, then newest first) — the rank value itself is never exposed.

**Filtering by label.** The [label](#labels) filter is applied **server-side** in both modes (it has to search the whole project, not just the capped page already on screen): repeat `labelId` once per label and choose how they combine with `labelMatch` — `any` (default, carries at least one) or `all` (carries every one), lowercase. Any other `labelMatch` value is a `400`, as is passing more than 20 distinct `labelId` values (the per-issue label limit, `MAX_LABELS_PER_ISSUE`, 20 by default).

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

**Create** — `title`, `typeId` and `statusId` are required (the type must be offered by the project's type set, the status must belong to the project's [workflow](#project-configuration)); `priorityId` must be offered by the project's priority set and defaults to the set's default when omitted; `parentId` links the issue to a parent in the same project — rejected with `422` if the parent is unknown, in another project, or its type's [hierarchy level](#project-configuration) is not strictly greater than this issue's type level; `assigneeId` must be a workspace member. `fields` carries custom field values keyed by field id (value shapes per [field type](#project-configuration)) — required fields of the project's field set must be present, fields outside the set or archived are rejected with `422`. `labelIds` attaches workspace [labels](#labels) (duplicates are de-duped); an unknown, foreign-workspace or archived label id — or more than `MAX_LABELS_PER_ISSUE` distinct ids (20 by default) — is rejected with `422`. `componentId` files the issue under a [component](#components) of this project; an unknown, foreign-project or archived component is a `422`, and when the component has auto-assign the lead may become the assignee (see [auto-assign](#components)). `fixVersionIds` and `affectsVersionIds` link [versions](#versions) of this project in the two independent roles — "ships in" and "is broken in"; an unknown, foreign-project or archived version, or more than `MAX_VERSION_LINKS_PER_ISSUE` distinct ids (20 by default) **per link type**, is a `422` (linking an already-*released* version is allowed on purpose). `sprintId` files the issue straight into a [sprint](#sprints--backlog) of this project — an unknown, foreign-project or *completed* sprint is a `422`, and omitting it means the backlog. `storyPoints` is the native estimate: `0`–`999` with at most 2 decimals (`422` otherwise), where `null`/omitted means **unestimated**, which is deliberately not the same as `0`. A new issue is always appended to the **bottom** of the ranked backlog:

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

**Update & optimistic locking** — send any subset of `title`, `description`, `typeId`, `statusId`, `priorityId`, `assigneeId`, `dueDate`, `labelIds`, `componentId`, `fixVersionIds`, `affectsVersionIds`, `sprintId`, `storyPoints`, `fields`, plus the `version` you last read. If the issue changed since, you get `409 Conflict` — re-fetch and retry. Omitting `version` skips the check (last write wins). To unset a nullable core field send `clearAssignee: true` / `clearDueDate: true` — a plain `null` can't be told apart from an omitted field (ignored when the id/date is also given). A `parentId` sets or changes the parent and `clearParent: true` detaches it (same convention); an illegal parent — unknown, in another project, self, a cycle, a [hierarchy-level](#project-configuration) violation, or a type change that conflicts with an existing parent or child edge — returns `422`. `labelIds`, `fixVersionIds` and `affectsVersionIds` are the **full-replacement** fields: when present the issue ends up carrying exactly those labels / versions-in-that-role, `[]` removes them all, and omitting one leaves it untouched (no clear-flag needed — `[]` is unambiguous); an already-attached archived label or version may stay, it just cannot be added. The two version roles are independent, so sending only `fixVersionIds` never touches the affects set, and the `MAX_VERSION_LINKS_PER_ISSUE` cap is counted separately per role. `componentId` sets or changes the [component](#components) and `clearComponent: true` unsets it (the `assigneeId`/`clearAssignee` convention again); attaching an unknown, foreign-project or **archived** component is a `422`, though an issue already carrying an archived one stays editable — and component auto-assign never fires on an update. `sprintId` moves the issue into a [sprint](#sprints--backlog) of this project and `clearSprint: true` returns it to the backlog (the same nullable-scalar convention); sending **both** is a `400` — "put it in sprint X" and "take it out of every sprint" cannot both hold, so `sprintId` no longer wins silently, and this now matches the [rank endpoint](#sprints--backlog), which has always refused the same combination. An unknown, foreign-project or **completed** target sprint is a `422`, and so is changing or clearing the sprint of an issue whose **current** sprint is `COMPLETED` — a completed sprint's membership is frozen in both directions, though every other field of such an issue stays editable. `storyPoints` sets the estimate (`0`–`999`, at most 2 decimals — `422` otherwise) and `clearStoryPoints: true` marks the issue unestimated again; a real change writes one `storyPoints` history entry, a no-op writes none. Neither of them touches the issue's **rank** — that is the separate [rank endpoint](#sprints--backlog). Inside `fields` only the listed field ids change; JSON `null` clears a value (required fields cannot be cleared):

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

## Sprints & backlog

A **sprint** is one project's iteration — a time-box with a goal, a start and an end, holding the issues the team committed to. Like [labels](#labels), [components](#components) and [versions](#versions) it is *content*, not configuration: sprints never appear in a project's [config](#project-configuration), and two projects may each run a "Sprint 7".

**Lifecycle — one way only.** A sprint is created `FUTURE` (a planning bucket), moves to `ACTIVE` when it is started, and ends `COMPLETED` when it is completed. **At most one sprint per project may be active**, and that is enforced by the database rather than only in code. **There is no re-open**: a completion is a reported event (the done-vs-carried-over numbers were handed to the user and the unfinished issues were already moved), so the recovery path is "create a new sprint and move the issues back" — two clicks, no ambiguity.

**Rank.** Every issue in a project carries a position in one project-wide order that the **board and the backlog share** — there is no second "backlog order" that could disagree with the board. The rank is **server-written only** and is deliberately not exposed in any response: to move an issue you name the neighbours you dropped it between (`POST …/issues/{number}/rank`), and the server computes the placement. A newly created issue lands at the **bottom** of the backlog (filing an issue is not a priority statement).

**Story points** are a native issue attribute (`storyPoints` on every issue), not a custom field: `0`–`999` with at most 2 decimals, where `null` means **unestimated** — deliberately not the same as `0`, which is why the section totals report `unestimatedCount` separately.

**Permissions.** Reads (sprint list/detail, completion preview, the backlog view) need project membership. The **lifecycle** — create, rename/re-plan, start, complete, delete — needs [`sprint.manage`](#permissions): a project `MANAGER`, **or** an `OWNER`/`ADMIN` of the enclosing workspace (who need not be a project member). Putting issues *into* a sprint and taking them out is a **separate** permission, `sprint.assign`, held by every ordinary contributor — planning is teamwork, and requiring the lifecycle permission to drag would make the backlog read-only for most of the team. Dragging within a section is `issue.rank`. A missing workspace, a missing project or a non-member all give `404`, never `403`; `403` is reserved for a member without the permission, and [names it](#what-a-403-says).

**`sprint.assign` guards every door.** The sprint endpoints are not the only way to move an issue between sections: `PATCH …/issues/{number}` with `sprintId`/`clearSprint` and `POST …/issues/{number}/rank` with a sprint change are checked against the same permission, so it cannot be bypassed with a different request shape.

**The project's [delivery capabilities](#delivery-capabilities) change nothing here.** `board: KANBAN` does not close the sprint API and `estimation: false` does not reject `storyPoints` — every endpoint below behaves identically whatever a project has declared. A capability hides vocabulary in the UI; it is never a permission.

Four operator settings shape this area — `AGILE_SECTION_MAX_ISSUES`, `AGILE_MAX_OPEN_SPRINTS`, `AGILE_DEFAULT_SPRINT_LENGTH_DAYS` and `AGILE_MAX_BULK_MOVE`; see [Operator settings](#operator-settings-that-affect-the-api).

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
- a project that already holds the maximum number of **open** sprints (`AGILE_MAX_OPEN_SPRINTS`, 20 by default — `FUTURE` + `ACTIVE`; completed ones are history and don't count) returns `422`.

```bash
curl -X POST $BASE/workspaces/$WS/projects/$PROJ/sprints \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Sprint 7", "goal": "Ship billing v2"}'
```

**Update** — `PATCH` takes `{"name?", "goal?", "startAt?", "endAt?", "clearStartAt?", "clearEndAt?"}`; omitted fields are left unchanged, and `clearStartAt`/`clearEndAt` unset the dates (the `assigneeId`/`clearAssignee` convention — a plain `null` can't be told apart from an omitted field). There is deliberately **no** `state` field: the lifecycle moves only through the two calls below, and it never moves backwards.

**Start** — `POST …/sprints/{sprintId}/start`, body **optional**: a bare `POST` means "start it now and run it for `AGILE_DEFAULT_SPRINT_LENGTH_DAYS` days (14 by default)". `{"startAt?", "endAt?", "goal?"}` overrides any of that; `endAt` defaults to `startAt` + that same setting, and a `startAt` in the past is allowed on purpose (backfilling a sprint that actually began on Monday is normal).

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

**Putting issues in and taking them out** — `POST …/sprints/{sprintId}/issues` takes `{"issueIds": [...], "position": "TOP"|"BOTTOM"}` (default `BOTTOM`) and returns the sprint with refreshed counters. Issues are addressed by **id** here. An issue already in the sprint is a silent no-op (no history entry, no `version` bump); the rest are placed at the top or bottom of the sprint's slice of the shared rank, keeping their relative order among themselves. An unknown or foreign-project issue id is a `422` ("Unknown issue" — never a `404`, which would confirm existence), an empty list or more than `AGILE_MAX_BULK_MOVE` distinct ids (100 by default) is a `400`, and a `COMPLETED` target sprint is a `422` (assigning to the *active* one mid-sprint is fine — a scope change is a real event).

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

Each section is capped independently at `sectionCap` (`AGILE_SECTION_MAX_ISSUES`, 300 by default) and reports `truncated` / `totalAvailable` — but its `stats` are computed over the **whole** section, so a truncated section still shows honest totals. Treat sections as independently refreshable.

`bulkMoveCap` (`AGILE_MAX_BULK_MOVE`, 100 by default) is the limit on **one** `POST …/sprints/{sprintId}/issues` call, and it is a *different* operator knob from `sectionCap` on purpose. A "move everything to sprint X" action is driven by the section you rendered, so a client that assumes one cap from the other would `400` on every section larger than the bulk limit. Chunk bulk moves at the `bulkMoveCap` the instance reports instead of hardcoding an operator-tunable number.

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
- Dropping an issue into the same gap over and over eventually exhausts it, and the server re-spaces the whole project's ranks. That rebalance is **throttled to once per project per 60 s** — a second one inside the window answers `429` with a `Retry-After` header (seconds). This is a **retryable throttle, not a fault**: nothing was moved, and the identical request succeeds after the wait. Back off for `Retry-After` rather than retrying immediately. Hitting it in normal use is essentially impossible — right after a rebalance every gap is wide again, so exhausting one takes ~26 successive drops into that same spot. The cooldown is fixed and not operator-tunable, and the counter is in-memory per app node.
- The response is the full updated [`IssueResponse`](#issues). A rank change writes **no** history entry (positional churn would drown the log); a sprint change in the same request does.
- **Both permissions are checked before the project's state.** `issue.rank` is checked first, and `sprint.assign` as soon as the request is known to move the issue between sections — and only then does the endpoint refuse an **archived** project with `409`. So a caller who lacks either permission gets the `403`, never the archive conflict, which is the same ordering [deleting an issue](#issues) and [deleting an attachment](#attachments) already follow.

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

**`sprint` is intentionally not served here.** Its `/search/schema` `SPRINT` picklist is already bounded by `AGILE_MAX_OPEN_SPRINTS` per project, so no typeahead was wired — `?field=sprint` returns the same `422` any other non-suggestable field returns. `storyPoints` is numeric and has no picklist either.

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
