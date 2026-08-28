# Request field length bounds — the constraint belongs to the column, and the guarantee belongs to a category (HD-171)

**Status:** proposal / design review. **Date:** 2026-08-28. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness).
**Migration:** **V24** (`V24__issue_history_field_width.sql`) — one `ALTER TABLE … ALTER COLUMN … TYPE`
widening. No data written, folded or deleted. Widening a `varchar` in PostgreSQL takes no table
rewrite.
**Related:** HD-120 (shipped the five `@Email @Size(max = 255)` bounds and `EmailLengthBoundTest`),
HD-190 (pushed `InviteMemberRequest.email`'s type onto a third line and proved the line-based scan
wrong), HD-13 (`GlobalExceptionHandler.handleDataIntegrityViolation` — the SQLSTATE-gated translation
this spec extends by exactly one state), HD-133 (`logServerErrorDetail=false`, which decides what a
`22001` log line may contain), HD-151 (the "a clean 4xx removes the operator's only signal" doctrine),
HD-30/31/32 (`ClassificationNames` + per-service post-normalization length checks — the pattern this
spec generalises).
**Touches:** `WorkspaceService.generateSlug` + `randomSuffix` (javadoc), `IssueService.makeHistory`,
`V24`, `IssueHistory` (width + a hand-written `setField` carrying the truncation belt),
`CreateIssueRequest`, `UpdateIssueRequest`, `CreateCommentRequest`, `CommentService.parseMentions`,
`CreateProjectRequest`, `UpdateProjectRequest`, `UpsertWorkflowRequest`, `LoginRequest`,
`VerifyEmailRequest`, `ResetPasswordRequest`, `RegisterRequest` (the password bound),
`AuthController.verifyEmailLink` (the `token` request param), `WorkspaceController.acceptInvite`
(same), **new** `common.security.PasswordLimits` and `auth.exception.PasswordTooLongException`,
`AuthService` (one refusal on both password-writing paths),
`DataSeeder` (`MAX_SEED_PASSWORD_BYTES` + `rejectOverLongPassword`),
`AdminFieldService.requireConfigSize` + `requireSelectOptions`, `FieldValueService` (one constant),
`GlobalExceptionHandler` (one SQLSTATE branch), three SPA textareas, tests.
**No new configuration property. No new environment variable. No profile gating.**

---

## 0. What the ticket asked for, and what is left of it

The ticket has three scope bullets. **The first is already done and was done by another ticket.**

All five named DTOs plus `admin/dto/CreateUserRequest` carry `@Email @NotBlank @Size(max = 255)`
today (`b397866`, reinforced by `2d60345`), and `src/test/java/com/hamstrack/auth/EmailLengthBoundTest.java`
seals it **as a category**: it walks every `.java` under `src/main/java`, resolves the `String` each
`@Email` annotates, and fails on a missing or over-wide `@Size`, with a `checked >= 6` tripwire under
the "nothing offends" assertion. Nothing in this spec re-does that work, and nothing in this spec may
weaken that test.

What remains is bullets two and three, and they are the ticket's actual deliverable:

1. **The sweep** — §3. Every request-reachable text column, its writer, its bound, its verdict.
2. **Six spellings or one** — §5. Decided: **six**, with the reason recorded, and the *mechanism*
   generalised instead of the annotation.
3. Plus three things the dispatching brief asked to settle: the missing per-endpoint assertions (§6),
   what a refusal says (§7), and the `DataIntegrityViolationException` question (§8).

**Two of the ticket's stated premises are stale and the design changes because of it.**

### Correction 1 — `GlobalExceptionHandler` *does* declare a `DataIntegrityViolationException` handler

The ticket says it declares none. HD-13 added one
(`GlobalExceptionHandler.handleDataIntegrityViolation`, two bindings:
`DataIntegrityViolationException` and `org.hibernate.exception.ConstraintViolationException`). It is
**gated on SQLSTATE `23503`** and translates only that, to 409. Every other state — including
`22001 string_data_right_truncation`, which is exactly what an over-long value raises — falls to a
deliberate `log.error(...)` + **500**. So the ticket's *symptom* claim is still true (an over-long
value is a 500) while its *cause* claim is not, and the fix is therefore **one more SQLSTATE branch in
an existing handler**, not a new handler. See §8.

### Correction 2 — the interesting failures are not on the DTOs at all

The five email fields were an *annotation* problem: a field on a record, a column behind it, a missing
`@Size`. Both **500-class defects the sweep found are the other shape** — a value the server *derives*
and no request DTO carries:

- `workspaces.slug VARCHAR(100)` is built from `CreateWorkspaceRequest.name` (`@Size(max = 255)`) by
  `WorkspaceService.generateSlug`, which lowercases and substitutes but **never truncates**.
- `issue_history.field VARCHAR(50)` is written with a custom field's **display name**
  (`field_defs.name VARCHAR(100)`, `UpsertFieldRequest.name @Size(max = 100)`).

**Consequence for the design:** no annotation scan, no composed annotation and no value type can ever
have found either of these, because there is no annotated field to look at. That fact is what decides
§5, and it is the highest-value sentence in this document.

---

## 1. Problem & goal

A request field that reaches a `VARCHAR(n)` column without a length bound answers **500** to what is
plainly a client input error, and one that reaches a `TEXT` column without a bound stores whatever it
is given. The project has repeatedly fixed this **per field** — `CreateUserRequest` carried the rule
and named the anti-pattern in its own javadoc while five siblings did not; `LabelService`,
`ComponentService`, `VersionService` and `SprintService` each carry their own private copy of "check
the length after normalisation". HD-171 asks for the **sweep** rather than the next five annotations.

**Success:** (a) every request-reachable text column is inventoried with a verdict; (b) no request
path can write an over-length value into a `VARCHAR` column and surface a 500; (c) a value that is too
long is refused with a **400 that names the field**, at every endpoint that accepts one, asserted per
endpoint; (d) a bound that goes missing in future is a **red build**, not a silent omission.

---

## 2. Scope

**In scope**

- Fixing the two 500-class defects (§4.1, §4.2) and the five unbounded-payload fields (§4.3).
- One new SQLSTATE branch in `GlobalExceptionHandler` as a backstop (§8).
- Three missing end-to-end per-endpoint assertions (§6).
- One new category test that keeps sweeping (§5.3).
- The inventory itself (§3) — it is the artifact, not an appendix.

**Out of scope / non-goals**

- **Re-doing HD-120.** No change to any `@Email` field, to `EmailLengthBoundTest`, or to the 255 bound.
- **A composed constraint annotation or a validated email value type.** Decided against in §5, with
  the reasoning recorded there so it is not re-litigated.
- **Widening any column to accommodate input.** The one widening proposed (§4.2) matches a column to
  its *own* declared source, and is not a response to user input being long.
- **A general "translate every constraint violation" handler.** Explicitly refused in §8.
- **Request body size limits at the container.** A global JSON body cap is a different lever with
  different failure modes (it produces a 413 with no field, on every endpoint at once) and belongs
  to an infrastructure ticket. Noted as an open question (§14.2), **which round 2 sharpened into
  something this spec must not be read as covering** — see that entry.
- **Bounding `@RequestParam` strings that are never persisted** (`/search/suggest?q=`,
  `/insights` slice names). Checked during the sweep and recorded as read-only in §3.4; not changed.
  **Two exceptions:** `GET /api/auth/verify-email`'s `token` param (round 2) and
  `POST /api/workspaces/accept-invite`'s (round 3) *are* bounded (§4.4), not because either is
  persisted — neither is — but because each is an **unbounded twin of a field this ticket bounded**,
  and leaving one is the "rule on one of two doors" defect committed by the ticket named after it.
  The accept-invite param carries the least risk of the four token doors (SHA-256'd, looked up by
  hash, authenticated, no header built from it) and is bounded anyway, because the claim being made
  is about the category and not about that door's exposure.

---

## 3. The sweep

**Method.** Column widths read from `V1__init_schema.sql` (the squash baseline) plus every later
migration (`V2`–`V23`); each column matched to its `@Entity` field; each entity field traced back
through the service that writes it to the request DTO field (or to the server-side expression) that
supplies it; the `@Size`/`@Pattern`/enum/service check on that source recorded.

**Boundary, so the table is not mistaken for a guarantee about anything else.** It covers columns of
type `VARCHAR`, `TEXT` and `JSONB` whose value originates in an HTTP request — including values the
server *derives* from request input, which is where both 500s live. It says nothing about columns
written only by migrations, by `DataSeeder`/`DemoDataService` from configuration, or about the
`oauth_accounts` table, which no code in the tree writes.

### 3.1 Summary by verdict

| verdict | count | where |
|---|---|---|
| **unbounded → 500 on write** (`VARCHAR`, derived value, no truncation) | **2** | §3.2 rows 1–2 |
| **bounded wider than the sink accepts → 500** (the sink is the password encoder, not a column) | **2** | §3.2 rows 8a–8b |
| **unbounded → unbounded stored payload** (`TEXT`/`JSONB`) | **5** | §3.2 rows 3–7 |
| **unbounded request field that reaches no column** | **3** | §3.2 rows 8–10 |
| **bounded** (at the DTO / by a service check / by truncation / by shape) | **58** | §3.3 |
| **not request-reachable** | **3** | §3.4 |
| **untraced** | **0** | — |

The counts are a property of the table below **on the day it was written**, and the table is the
authority. A number goes stale one entry before the list does.

### 3.2 The findings

| # | column | width | writer / DTO field | current bound | verdict & the risk it actually carries |
|---|---|---|---|---|---|
| 1 | `workspaces.slug` | `VARCHAR(100)` | `WorkspaceService.generateSlug(req.name())` ← `CreateWorkspaceRequest.name` | none on the derived value (`name` is `@Size(min = 2, max = 255)`) | **UNBOUNDED → 500.** A workspace name of 101+ slug-safe characters produces a 101+ character slug. `POST /api/workspaces` answers 500. Reachable by **any authenticated user**, and it is on the first-run onboarding path ("Create a team"). Highest severity in this sweep. |
| 2 | `issue_history.field` | `VARCHAR(50)` | `IssueService.makeHistory(issue, actor, fieldName, …)` ← `FieldValueService.applyValues` `onChange.changed(field.getName(), …)` ← `field_defs.name` | `UpsertFieldRequest.name @Size(max = 100)` | **BOUNDED WIDER THAN THE COLUMN → 500.** A custom field whose name is 51–100 characters makes **every value change to that field** 500 on `PATCH …/issues/{number}`. Two actors: an admin names the field, then any project member trips it. Only the *update* path writes this row — create passes a no-op listener (`IssueService:304`) — so create succeeds and update crashes, which is the hardest shape to diagnose. |
| 3 | `issues.description` | `TEXT` | `CreateIssueRequest.description`, `UpdateIssueRequest.description` | none | **UNBOUNDED PAYLOAD.** No 500 — `TEXT` has no width. The amplification is what matters: on every edit the old and new values are both copied verbatim into `issue_history.old_value`/`new_value` (also `TEXT`), so one unbounded field is stored **three times** after one edit, and unboundedly often after N. |
| 4 | `issue_comments.body` | `TEXT` | `CreateCommentRequest.body` | `@NotBlank` only | **UNBOUNDED PAYLOAD + UNBOUNDED CPU on a write path.** `CommentService.parseMentions` lowercases the whole body and then, **at every `@` in it**, walks every workspace member comparing a prefix. Cost is O(occurrences-of-`@` × members × name length) over an input the caller chooses, inside a `@Transactional` write. This is the only unbounded field in the sweep whose cost is superlinear in the input. |
| 5 | `projects.description` | `TEXT` | `CreateProjectRequest.description`, `UpdateProjectRequest.description` | none | **UNBOUNDED PAYLOAD.** Returned in every project list response, so it is also unbounded *egress* on a hot read. |
| 6 | `workflows.description` | `TEXT` | `UpsertWorkflowRequest.description` | none | **UNBOUNDED PAYLOAD (admin-only).** Lower severity — `/api/admin/**` requires system role ADMIN — but it is the same defect and a delegated-admin tier would widen who can reach it. |
| 7 | `field_defs.config` | `JSONB` | `UpsertFieldRequest.config` (`JsonNode`) | none — not the document size, not the option count, not any option's `id`/`label` length | **UNBOUNDED PAYLOAD (admin-only).** `AdminFieldService.requireSelectOptions` iterates the options and checks each has a non-blank `id` and `label`, and bounds neither. Read back by `FieldValueService.optionIds` on every custom-field write. |
| 8 | — | — | `LoginRequest.password` | `@NotBlank` only | **UNBOUNDED REQUEST FIELD, no column.** `matches` takes BCrypt's `for_check` branch, which truncates at 72 bytes instead of throwing, so verification cost is constant and cannot be raised by a long submission; the defect is that the field is unbounded at all. It is also **the same DTO** whose `email` field the read-only-door argument was already rejected for. Bounded at 1024 by §4.4 rule 7 as a *resource* guard — "finite", justified as a property of reading doors and **not** by enumerating the doors that write the value. |
| 8a | — | — | `RegisterRequest.password`, `ResetPasswordRequest.newPassword` | `@Size(min = 8, max = 100)` | **BOUNDED WIDER THAN THE ENCODER ACCEPTS → 500** (found in round 3, pre-existing). `BCrypt.hashpw` throws `IllegalArgumentException("password cannot be more than 72 bytes")` above **72 UTF-8 bytes** when *creating* a hash (spring-security-crypto 7.1.0, `BCrypt.java:615`, `if (!for_check && passwordb.length > 72)`), and nothing translates it. So 73–100 ASCII characters, 37–100 Cyrillic/Greek ones, 25+ CJK ones or 19+ emoji answer **500** on `POST /api/auth/register` (**unauthenticated, public**) and on `POST /api/auth/reset-password`. Same shape as row 2 — a door advertising a range its sink refuses — and it punishes the longest passwords and the non-Latin scripts first. No partial write (`encode` precedes the INSERT; the reset transaction rolls back, so **the reset token is not burned**) and no leak (`server.error.include-message=never`). Fixed in §4.4 rule 9. |
| 8b | — | — | `seed.admin.password` (`DataSeeder`) | none | Same sink, same 72-byte ceiling, at boot: an over-long value kills the context inside `passwordEncoder.encode` with a message naming neither the variable nor a remedy. §4.4 rule 9. |
| 9 | — | — | `VerifyEmailRequest.token` | `@NotBlank` only | **UNBOUNDED REQUEST FIELD, no column.** Hashed to 64 hex before any lookup, so nothing overflows; unauthenticated, and the SHA-256 input is caller-sized. |
| 10 | — | — | `ResetPasswordRequest.token` | `@NotBlank` only | As row 9. |

**On "this one only reads".** `EmailLengthBoundTest`'s javadoc argues that a read-only handler is a
property of today's code and not of the field, and that a read-only door does not crash — it
*answers*, which is harder to notice. **This spec agrees, and rows 8–10 are that argument applied
consistently.** The three of them reach no column today; they are still bounded here, at the same
literal-`@Size` cost, because "reaches no column" is a claim about the current call graph. The
counter-argument to weigh honestly: a bound on `password` is a behaviour change on an authentication
door, which is why §4.4 specifies `max` and forbids `min` there.

### 3.3 Bounded — how each one is already held

Recorded so that "already bounded" is a checked statement rather than an omission. Four mechanisms
appear, and they are named because §5 is a decision about which of them to extend.

**(a) Bounded at the DTO** — `@Size(max = n)` where *n* ≤ the column width.

| # | column | width | DTO field(s) | bound |
|---|---|---|---|---|
| 11 | `users.email` | 255 | `RegisterRequest`, `LoginRequest`, `ForgotPasswordRequest`, `ResendVerificationRequest`, `CreateUserRequest` | 255 — sealed by `EmailLengthBoundTest` |
| 12 | `workspace_invites.email` | 255 | `InviteMemberRequest.email` | 255 + `@Pattern("\\p{ASCII}*@[^@]*")` |
| 13 | `users.display_name` | 100 | `RegisterRequest.displayName`, `CreateUserRequest.displayName` | 100 (+ `DisplayText.SINGLE_LINE`) |
| 14 | `workspaces.name` | 255 | `CreateWorkspaceRequest.name`, `UpdateWorkspaceRequest.name` | 255 |
| 15 | `projects.name` | 255 | `CreateProjectRequest.name`, `UpdateProjectRequest.name` | 255 |
| 16 | `projects.key` | 10 | `CreateProjectRequest.key` | 10 + `@Pattern("[A-Z0-9]+")` |
| 17 | `issues.title` | 500 | `CreateIssueRequest.title`, `UpdateIssueRequest.title` | 500 |
| 18–24 | `statuses.name`, `priorities.name`, `issue_types.name`, `workflows.name`, `priority_sets.name`, `field_sets.name`, `issue_type_sets.name` | 100 each | the seven `Upsert*Request.name` | 100 each |
| 25 | `field_defs.name` | 100 | `UpsertFieldRequest.name` | 100 — **and this is row 2's source**; the bound is correct for *this* column |
| 26 | `field_defs.key` | 50 | `UpsertFieldRequest.key` | 50 + `@Pattern("[a-z0-9_]*")` |
| 27 | `field_defs.description` | `TEXT` | `UpsertFieldRequest.description` | 500 |
| 28–29 | `priorities.icon`, `issue_types.icon` | 50 | `UpsertPriorityRequest.icon`, `UpsertIssueTypeRequest.icon` | 50 |
| 30 | `labels.description` | 200 | `Create/UpdateLabelRequest.description` | 200 — exact fit; `LabelService` only `trimToNull`s it |
| 31–32 | `components.description`, `versions.description` | 500 | `Create/Update{Component,Version}Request.description` | 500 — exact fit |
| 33 | `sprints.goal` | 500 | `CreateSprintRequest.goal`, `UpdateSprintRequest.goal`, `StartSprintRequest.goal` | 500 — exact fit, three doors |
| 34 | `sprints.name` | 60 | `CreateSprintRequest.name`, `UpdateSprintRequest.name` | 60 (+ service check, mechanism **b**) |
| 35 | `roles.name` | 80 | `DuplicateRoleRequest.name`, `UpdateRoleRequest.name` | 80 (+ `DisplayText.SINGLE_LINE`) |
| 36 | `roles.description` | 500 | `DuplicateRoleRequest.description`, `UpdateRoleRequest.description` | 500 (+ `DisplayText.MULTI_LINE`) |
| 37 | `saved_filters.name` | 120 | `Create/UpdateSavedFilterRequest.name` | 120 |
| 38 | `saved_filters.hql` | 2000 | `Create/UpdateSavedFilterRequest.hql` | 2000 — matches `HqlParser.MAX_QUERY_LENGTH` |
| 39 | `mail_send_events.recipient_email` | 320 | every `@Email` writing flow | 255 — deliberate margin, argued on `MailSendEvent` |

An **exact fit** (rows 30–33) is safe in the direction that matters: `@Size` counts UTF-16 code units
and PostgreSQL counts characters, so a string that passes `@Size(max = n)` is at most *n* characters.
It is only unsafe if something between the check and the INSERT can **lengthen** the value; nothing on
these four paths does (`trimToNull` and `strip()` only shorten). Stated because it is the invariant an
exact fit rests on, and the next transformation added to one of these paths is where it breaks.

**(b) Bounded by a service check after normalisation** — the DTO `@Size` is deliberately wider (a
cheap payload guard); the column width is enforced in the service *after* `ClassificationNames.normalize`,
because NFC + control-stripping + separator-collapsing changes the length.

| # | column | width | DTO bound | service check |
|---|---|---|---|---|
| 40 | `labels.name` | 60 | 200 | `LabelService.MAX_NAME_LENGTH = 60` → 400 |
| 41 | `components.name` | 80 | 200 | `ComponentService.MAX_NAME_LENGTH = 80` → 400 |
| 42 | `versions.name` | 60 | 200 | `VersionService.MAX_NAME_LENGTH = 60` → 400 |
| 43 | `sprints.name` | 60 | 60 | `SprintService.MAX_NAME_LENGTH = 60` → 400 |

**(c) Bounded by server-side truncation** — never refuses; clips.

| # | column | width | source | truncation |
|---|---|---|---|---|
| 44 | `issue_attachments.filename` | 255 | the multipart part's `originalFilename` | `AttachmentService.sanitizeFilename` → `truncate(name, 255)` |
| 45 | `roles.key` | 40 | `DuplicateRoleRequest.name` (80) | `RoleService.generateKey` truncates to 40 **and reserves room for the `_2`/`_3` collision suffix inside 40** |
| 46 | `notifications.body` | `TEXT` | comment body | first 120 chars + `…` |
| 47–50 | `failed_email.recipient` / `.subject` / `.last_error`, and the invite subject that feeds `.subject` | 320 / 255 / 1000 | `MailService.deadLetter` | `truncate(…)` at each width — note the invite subject is `"You've been invited to " + workspaceName + " on Hamstrack"`, up to ~285 characters from a 255-character name, and this truncation is the only thing that bounds it |

**Row 45 is the fix for row 1, already written, 800 lines away in the same package.** `generateKey`
and `generateSlug` are the same function — uppercase/lowercase, substitute the disallowed class,
suffix on collision — and exactly one of them truncates.

**(d) Bounded by shape** — a pattern, an enum, a hash or a UUID makes the length a constant.

| # | column(s) | width | why it cannot overflow |
|---|---|---|---|
| 51 | `refresh_tokens.token_hash`, `email_verifications.token_hash`, `password_resets.token_hash`, `workspace_invites.token_hash` | 64 | SHA-256 hex is exactly 64 |
| 52 | `users.password_hash` | 255 | BCrypt output is 60 |
| 53 | `statuses.color`, `priorities.color`, `issue_types.color` | 7 | `@Pattern("#[0-9A-Fa-f]{6}")` |
| 54 | `labels.color` | 9 | `@Pattern("^#([0-9A-Fa-f]{6}\|[0-9A-Fa-f]{8})$")` |
| 55 | every enum-ish column — `users.status`, `users.system_role`, `statuses.category`, `field_defs.type`, `sprints.state`, `projects.board_mode`, `roles.scope`, `version_links.link_type`, `sprint_scope_events.event`, `workspaces.project_access_mode`, `notifications.type`, `failed_email.email_type`, `mail_send_events.email_type` | 10–50 | a Java enum's `name()`/key, never a request string |
| 56 | `role_permissions.permission` | 64 | `Permission` enum keys |
| 57 | `issue_attachments.content_type` | 255 | derived by `MediaTypeFactory` from the filename; **the client `Content-Type` header is discarded** |
| 58 | `issue_attachments.storage_key`, `notifications.link` | `TEXT` | composed from UUIDs only |
| 59 | `notifications.title` | 255 | `displayName + " mentioned you"` ≤ 100 + 13 = 113 |
| 60 | `sprint_scope_events.issue_key` | 40 | `projects.key` (≤ 10) + `-` + a number |
| 61 | `mail_send_events.recipient_key` | 320 | `@Email`'s own 64 + 1 + 255, argued in full on `MailSendEvent.recipientKey` |
| 62 | `issue_field_values.value` | `JSONB` | per-type bound in `FieldValueService.validate`: TEXT 500, TEXTAREA 10 000, URL 2 000, SELECT/MULTI_SELECT ∈ the configured option ids, DATE/USER parsed |
| 63 | `issue_history.old_value`, `.new_value` | `TEXT` | inherits each source's bound — **including `issues.description`'s absence of one** (row 3) |

**Row 62 is the ticket's own anti-pattern, found a second time.** A TEXTAREA custom field is bounded
at 10 000 characters and an issue *description* — the same kind of value, stored in the same database,
rendered in the same panel — is bounded at nothing. §4.3 fixes that by making the two agree.

### 3.4 Not request-reachable

| # | column(s) | why |
|---|---|---|
| 64 | `oauth_accounts.provider`, `.provider_user_id`, `.access_token`, `.refresh_token` | the table exists in `V1` and **no code in `src/main/java` reads or writes it**; there is no OAuth flow yet. Whoever builds one inherits four unbounded columns and this row is the note that says so. |
| 65 | `workspace_members.role`, `project_members.role`, `workspace_invites.role` (the legacy `VARCHAR(20)` columns) | dropped by `V15__drop_legacy_role_columns.sql` |
| 66 | `@RequestParam` strings that are never persisted — `/search/suggest?q=`, `InsightsRequest.measure`/`slice`/`segment` (each `@Size(max = 32)`), `SearchRequest.query` (`@Size(max = 2000)`) | read-only lookups; each is bounded anyway |

**Untraced: none.** Every column in the sweep resolved to a writer or to "no writer exists".

---

## 4. Behaviour & rules — what changes

### 4.1 `workspaces.slug` — truncate the derived value, do not widen the column

`WorkspaceService.generateSlug` must bound the slug at **100**, in the shape `RoleService.generateKey`
already uses:

- truncate `base` to 100 after the substitution and the leading/trailing `-` trim;
- when the collision loop appends `-<6 random chars>`, take the suffix **out of the 100**, not on top
  of it (`base.substring(0, 100 - suffix.length())`), or a 95-character name that collides once
  produces a 102-character slug — the near-miss that makes a "fixed" version still 500 occasionally.

**Rejected: widening `workspaces.slug`.** A slug is a URL identifier, not user content; the full name
is stored beside it in `workspaces.name`, so clipping loses nothing a reader can act on. Widening also
makes the two generators disagree at a new width and re-opens the same bug there.

Rules:

1. The slug is never accepted from a client and is derived on **create only** (`UpdateWorkspaceRequest`
   does not regenerate it — verified: `setSlug` has one call site).
2. Truncation must happen **before** the uniqueness loop, so the loop tests the value that will
   actually be stored.
3. A truncated base raises the collision rate; the existing random-suffix loop already handles that
   and needs no change beyond point 2.

4. **`randomSuffix`'s `ThreadLocalRandom` is fine — on a condition, not as a property.**
   `WorkspaceRepository.findBySlug` has **zero callers** and every workspace route is UUID-based, so
   the suffix decorates a column nothing resolves by and guessing it grants nothing. **If
   `findBySlug` ever gains a caller — vanity URLs, `/w/{slug}`, per-tenant subdomains — it must move
   to `SecureRandom` in that same change**, because `ThreadLocalRandom`'s internal state is
   recoverable from a handful of observed outputs, and a predictable identifier on a route that
   resolves is an enumeration primitive. Recorded in the javadoc **next to `randomSuffix`**, not only
   here, because that is the line the next person edits.

### 4.2 `issue_history.field` — match the column to its own declared source, and belt it

The widen, the belt, two claims that had to be corrected in round 2, and two more in round 3.

1. **`V24__issue_history_field_width.sql`** — `ALTER TABLE issue_history ALTER COLUMN field TYPE VARCHAR(100);`
   plus `length = MAX_FIELD_LENGTH` (= 100) on `IssueHistory.field`. The column's meaning is "the name of the thing that
   changed" and its widest legitimate source is `field_defs.name VARCHAR(100)`; matching them is the
   honest fix. Widening a `varchar` takes no table rewrite in PostgreSQL.
2. **A truncation belt on the COLUMN, not on one writer.** A hand-written `IssueHistory.setField`
   (Lombok's `@Setter` yields to it) clips to the same 100 — for every writer **that goes through
   the setter**, which is all four application writers. Round 2's javadoc said "every writer present
   and future", and that overclaims by exactly the shape this ticket is about: the entity is
   *field-accessed* (`@Id` on a field in `CreatedOnlyEntity`, no `@Access` anywhere), so Hibernate's
   own hydration assigns the field directly and never calls the setter, as would a `@Modifying` JPQL
   update or a native INSERT. Harmless today — hydration's value came out of the column — but a rule
   stated over a category has to be true of the category. This is the invariant, not the fix: a
   widen alone re-creates the defect the day a longer source appears, and the general rule this sweep
   establishes is *any value derived from another column is either bounded by that column's width or
   truncated at the write site.*

   **Round 1 installed that rule at one of four writers, which is the mistake the rule itself names.**
   `issue_history.field` is written by `IssueService.makeHistory` (literals + `field_defs.name` — the
   only writer round 1 clipped), `SprintService.writeSprintHistory` and a *near-identical private
   copy* of `makeHistory` in `SprintService` (both `"sprint"`), and `WorkspaceMemberService`
   (`"assignee"`).
   No live bug — the other three pass literals — but "only the update path writes a dynamic name" is a
   claim about today's call graph, and the duplicated `makeHistory` is the divergence trap: the next
   person to add a dynamic field name may well be editing sprints, and their copy had no belt and no
   comment saying why it needed one. A rule stated about a *category of values* has to be installed
   where the category is written, i.e. on the setter. The clip in `IssueService.makeHistory` is
   removed, and its javadoc now points at the setter rather than repeating the rule.

3. **The clip is surrogate-exact, and the reason is written down.** `substring(0, 100)` was already
   safe in the direction that matters — Java counts UTF-16 code units, PostgreSQL `varchar(100)`
   counts code points, and code points are never more numerous than units, so the clip can never
   overshoot and a `22001` from here is impossible. The residual is a **lone high surrogate** at the
   last index, which pgjdbc's encoder replaces with `?`: the last character is silently mangled, no
   exception. Unreachable today (`UpsertFieldRequest.name` is `@Size(max = 100)` counting the same
   units, so only a path bypassing the DTO could reach it — which is exactly what a belt is for). The
   setter steps back off a trailing high surrogate **and says why in a comment**, because the naive
   version is correct for a non-obvious reason and a future reader is likely to "fix" it into
   something wrong.

4. **Entity/column parity here is convention, not enforcement — and both `V24`'s header and the
   entity javadoc claimed otherwise for one round.** `ddl-auto=validate` **does not** compare widths:
   Hibernate's schema validator compares JDBC *type codes* (`ColumnDefinitions.hasMatchingType`);
   `hasMatchingLength` exists but is referenced only from `StandardTableMigrator`, i.e. from
   `ddl-auto=update` (verified against hibernate-core 7.4.1 and 7.2.12). A `VARCHAR(50)` column
   against `length = 100` on the entity **boots clean and 500s at INSERT** — the very bug being
   fixed. A header that promises a safety net which is not there is worse than one promising nothing,
   because it makes the next reader stop checking. The only thing that catches a drift is the
   behavioural assertion in AC 6.

**Rejected: storing `field_defs.key` (50, exact fit) instead of the name.** Stable across renames and
otherwise attractive, but **existing rows already hold names with no discriminator**, so the history
feed would render `story_points` for new rows and `Story points` for old ones with no way to tell
which is which. It also moves a rendering decision into the frontend for no benefit this ticket needs.

**Rejected: narrowing `UpsertFieldRequest.name` to 50.** That refuses legitimate input to protect an
unrelated column, and does nothing for field definitions that already have 51–100-character names.

### 4.3 The five unbounded payload fields

| field | bound | why this number |
|---|---|---|
| `CreateIssueRequest.description`, `UpdateIssueRequest.description` | `@Size(max = 10000)` | agrees with `FieldValueService`'s existing TEXTAREA bound — one number for "a block of prose this product stores", not two |
| `CreateCommentRequest.body` | `@NotBlank @Size(max = 10000)` | same |
| `CreateProjectRequest.description`, `UpdateProjectRequest.description` | `@Size(max = 10000)` | same |
| `UpsertWorkflowRequest.description` | `@Size(max = 10000)` | same |
| `UpsertFieldRequest.config` | **`AdminFieldService.requireConfigSize` refuses a serialized `config` over 20 000 characters with 422, for every field type**; `requireSelectOptions` additionally refuses `options.size() > 100`, or any option whose `id` or `label` exceeds 100 characters | the option checks are two more lines in a loop that is already there — but they bound two *leaves*, and only inside the SELECT branch. The document guard is what actually bounds the field. |

Rules:

3. **`@Size(max = …)` takes a numeric literal in this codebase — write `10000`, never `10_000` and
   never a symbolic constant.** `EmailLengthBoundTest.SIZE_MAX` matches `max\s*=\s*(\d+)`: an
   underscore makes it read `10` and a constant reference makes it read "no `@Size` at all". For an
   `@Email` field that fails loudly; for any future column-width scanner it would pass silently. This
   rule is the price of mechanism (§5) and is worth paying.

   **None of the five prose fields is read by a scanner today, and the code comments must not say
   otherwise.** `EmailLengthBoundTest` applies `SIZE_MAX` *only* to declarations it reaches from an
   `@Email`, so it reads the address bounds and nothing else — the literal here is bare so that a
   *future* scanner **can** read it, which is the "future" this rule already said and which round 3's
   javadoc dropped, asserting enforcement that does not exist in nine files. Fixed in round 4.
4. `FieldValueService`'s TEXTAREA bound stays 10 000 and is now *the* number; if a later ticket raises
   one it raises both. Making that mechanical is a **requirement** (§10, AC 14) and is not yet built —
   until §5.3's behavioural test lands, the agreement is maintained by hand, and no javadoc may state
   it as an accomplished fact.
5. These are `@Size` on `TEXT`-backed fields, so they are **payload guards, not column guards** — the
   same status the DTO bounds in §3.3(b) have. The refusal is still a 400 naming the field. (And read
   §14.2 before calling any of them a *resource* guard: every `@Size` here runs **after**
   deserialization.)

6. **A bound on the members of a set is not a bound on the set.** Round 1 bounded
   `options[].id`/`options[].label` and then described `config` as bounded. It was not: the guard
   cannot be *bypassed* for what it checks (a non-SELECT type carrying options never reaches
   `optionIds`; `isArray()` precedes `size()`; `asText()` on a container yields `""` and hits the
   blank check) — it simply checks two leaves. `{"options":[{"id":"a","label":"b","color":"<20 M
   chars>"}]}` passed, so did any unrelated top-level key, and so did the whole `config` of every
   non-SELECT type. Hence the document-level ceiling, **outside** the SELECT branch. `config` is
   **egress to every project member** — `ProjectConfigController` returns it on the endpoint the SPA
   fetches for every board and every issue form — so an unbounded document is not a stored blob but
   hundreds of megabytes re-served on every page load, plantable by any workspace admin and contained
   to their own tenant. The 20 000 ceiling is deliberately *tighter* than the option ceilings at their
   extreme (100 options × 100-character id + label ≈ 23 KB is refused); that interaction is stated in
   the constant's javadoc so it reads as a decision rather than being discovered as a bug.

7. **`CreateCommentRequest.body`'s bound caps one factor of `parseMentions`, not the scan.** The cost
   is O(occurrences-of-`@` × members) and nothing in a request bounds the member count. What made
   each step expensive was `name.toLowerCase(Locale.ROOT)` **inside both loops**: a body of 10 000
   `@` characters in a 10 000-member workspace allocated a lowercased display name 10⁸ times —
   seconds of CPU and gigabytes of transient garbage, inside a `@Transactional` write holding a
   pooled connection, on an unthrottled endpoint any member can call. The lowercasing is now hoisted
   to once per call, which leaves the inner loop allocation-free.

   **Measured after the hoist, because round 2 stated this from estimate and was ~8× optimistic**
   ("roughly 0.1 s"): the 10 000-`@` body costs **~3 ms at 100 members, ~78 ms at 1 000 and ~0.77 s
   at 10 000**. That second runs inside a `@Transactional` write holding one of ten pooled
   connections (`maximum-pool-size` 10), and `POST …/comments` is on **no rate-limit budget** — so
   ten concurrent posts in a large workspace hold the whole pool. Member-only and tenant-local,
   which is why it is recorded rather than escalated.

   **No mention-count cap**, and the reason is *which factor* a cap would bound: the cost is
   occurrences × members, so a cap on `@` occurrences narrows the factor a request already bounds
   (the 10 000-character body) and leaves untouched the one that actually grows — the member count,
   which no request bounds and which a tenant raises by hiring. The fix that does bound it is to
   bucket the candidate names by length so each `@` compares only against names that could match
   there, making the scan independent of member count; **filed as its own ticket and deliberately
   not implemented here.** `CreateCommentRequest`'s javadoc says the bound is not a fix for the
   scan, because round 1's read as though it were.

### 4.4 The fields whose sink is not a column — tokens, and passwords

| field | bound | note |
|---|---|---|
| `LoginRequest.password` | `@Size(max = 1024)` | **`max` only. No `min`.** A `min` here would answer 400 where the endpoint must answer 401, distinguishing "short password" from "wrong password" and handing an attacker a weak oracle. **The `max` is a resource guard, not a policy claim** — see rule 7. |
| `RegisterRequest.password`, `ResetPasswordRequest.newPassword` | `@Size(min = 8, max = 72)` **+ a byte check in the service** | narrowed from 100 in round 3: these are the **writing** doors, and the encoder refuses above 72 UTF-8 bytes (rule 9, §3.2 rows 8a–8b). The `min` stays — a strength floor belongs on a door that writes. |
| `seed.admin.password` | `DataSeeder.MAX_SEED_PASSWORD_BYTES` (= 72 bytes), refused at the boot that would encode it | the third writing door, refused with a sentence instead of the encoder's bare `IllegalArgumentException` (rule 9). The refusal is gated on the value actually being encoded — `seed.admin.email` set, over the limit, and no account already at that address — so a rotation on an already-seeded install is not refused |
| `VerifyEmailRequest.token` | `@Size(max = 64)` | `TokenUtils.generateRawToken` is 32 random bytes → 43 Base64url characters. 64 is generous and finite. |
| `ResetPasswordRequest.token` | `@Size(max = 64)` | as above |
| `GET /api/auth/verify-email`'s `token` **request param** | `@Size(max = 64)` | the unbounded twin of the field above: same value, same flow, same lookup, on the redirect that keeps already-sent email links working. An ~8 KB token of characters `URLEncoder` expands 3× builds a ~24 KB `Location` header, past Tomcat's 8 KB response-header limit → 500 + an ERROR line, unauthenticated. **No `@Validated` on the controller** — see rule 8. |
| `POST /api/workspaces/accept-invite`'s `token` **request param** | `@Size(max = 64)` | the **fourth** token door, found in round 3. Same generator, same 43 characters. Impact is small — SHA-256'd and looked up by hash, authenticated, and no header is built from it — and it is bounded because it is the category, not because of what it risks. `WorkspaceController` carries no `@Validated`, so rule 8 holds here unchanged. |

Rules:

6. A token field's bound is set from its **generator**, not from the hash column behind it — the hash
   is always 64 hex characters regardless of what was submitted, so the column can never be the thing
   that refuses.

7. **A READING door's bound is justified as "finite", never by enumerating the doors that write the
   value.** Round 1 of this ticket set `LoginRequest.password` to 100 and justified it by naming the
   doors that *write* a password. That justification is wrong in form, and round 3 established that
   the example it named is wrong in fact as well.

   Wrong in form: a login door must accept whatever password any writing door produced, so a bound
   justified by *enumerating the writing doors* is a claim about **members** that goes stale the
   moment another door opens — in the very ticket that quotes *"a rule on one of two doors into a
   column is not a rule"*. A bound justified as **"finite, and far above anything any door can
   produce"** is a claim about a **category**, and does not go stale. That is why the number is 1024
   and why it costs nothing: `matches` takes BCrypt's `for_check` branch, which truncates rather than
   throws, so 100 and 1024 verify identically.

   Wrong in fact, and **round 2's own narrative is deleted rather than corrected**: round 2 defended
   1024 with a specific victim — an administrator seeded with a 128-character `SEED_ADMIN_PASSWORD`,
   permanently locked out by a bound of 100. **That instance cannot exist.** `DataSeeder` hashes with
   the same BCrypt, which refuses to *create* a hash above 72 bytes (rule 9), so the boot that would
   have seeded that administrator dies inside `encode`; no account on this codebase has ever held a
   password longer than 72 bytes, and round 1's 100 would have locked out nobody. The decision stands
   on the category claim alone, which is the point worth keeping: **the sentence written about a
   class survived being wrong about its only named member.** `LoginRequest`'s javadoc carries that
   correction, because the file is where the next reader meets the number.

8. **A bounded `@RequestParam` must NOT be accompanied by `@Validated` on the controller class.**
   Spring MVC's built-in method validation raises `HandlerMethodValidationException`, which Boot's
   advice renders as a **400**; `@Validated` on the class makes
   `HandlerMethod.shouldValidateArguments()` return `false` and defers to the AOP proxy, which throws
   `jakarta.validation.ConstraintViolationException` — a type `GlobalExceptionHandler.sqlStateOf`'s
   javadoc explicitly records as falling to an **unchanged 500**. Adding it would trade one 500 for
   another. (Verified against spring-web 7.0.8 sources. **Pre-existing, filed separately:**
   `SearchController` is `@Validated` and bounds `suggest`'s `q` at 100, so an over-long `q`
   answers 500 rather than 400 — confirmed in round 3, and filed as its own ticket; the same defect,
   on a door this ticket did not open.)

9. **A door that WRITES a password is bounded by what the encoder accepts, in the encoder's own
   unit — and the annotation cannot express that unit, so it takes two guards** (round 3; §3.2 rows
   8a–8b). `BCryptPasswordEncoder.encode` throws above **72 UTF-8 bytes**
   (`BCrypt.java:615`, `if (!for_check && passwordb.length > 72)`), untranslated, i.e. a 500 on
   `POST /api/auth/register` (unauthenticated) and `POST /api/auth/reset-password`.
   - `RegisterRequest.password` and `ResetPasswordRequest.newPassword` become
     `@Size(min = 8, max = 72)`. **Nobody is locked out by narrowing**: no account can hold a longer
     password, because `encode` would have refused to create it.
   - **`@Size` alone is not enough, and that is the substance of the rule rather than a caveat.** It
     counts UTF-16 code units and BCrypt counts bytes, so 72 characters of Cyrillic is 144 bytes and
     still 500s. `AuthService.rejectUnencodablePassword` measures
     `password.getBytes(UTF_8).length` on both writing paths, beside the existing
     `rejectPublishedPassword` and in the same shape: a **422** `AppException`
     (`PasswordTooLongException`), because the body is well-formed and what is refused is which
     values this application can store — the status `PublishedPasswordException` already uses for
     the same kind of statement. It runs **before** the reset token is marked used, so a refused
     caller can retry on the same link.
   - The refusal **names bytes and explains the arithmetic**, because "72 bytes" is not something a
     person typing a passphrase can evaluate: Latin letters cost 1 byte, accented/Greek/Cyrillic 2,
     most other scripts 3, emoji 4. A refusal may only prescribe an action its reader can perform.
   - `DataSeeder.MAX_SEED_PASSWORD_BYTES` is the same 72, by reference to
     `PasswordLimits.MAX_PASSWORD_BYTES` rather than by transcription, and
     `rejectOverLongPassword` counts bytes. It exists **only to replace a message**: without it the
     boot still fails, at `encode`, with `password cannot be more than 72 bytes` and no mention of
     the variable or of any remedy — the guard would otherwise trail the failure it was added to
     pre-empt.
   - That length guard is gated on **"will this value actually be encoded?"**, unlike
     `rejectPublishedPassword` beside it. Both run from `@PostConstruct`, i.e. before `run` has
     decided anything; the published-password guard has a reason to ignore that (a published
     password is a compromise whether or not seeding happens, because an earlier boot may already
     have created the account), and a *length* guard inherits no such reason — an over-long value
     that is never encoded never created anything — so it may only refuse a boot whose `run` would
     reach `passwordEncoder.encode`. That is three conditions, not one: `seed.admin.email` non-blank,
     the value over the encoder's limit, **and no row already occupying the folded address**
     (`UserRepository.existsByFoldedEmail`, the same fold `run`'s `findByFoldedEmail` uses, so the
     gate and the early return it models cannot disagree). Round 3 shipped only the first two, which
     refused a boot over a value nothing reads: seeding is idempotent, so an operator who rotates
     `SEED_ADMIN_PASSWORD` to an `openssl rand -base64 96` value after a successful first seed — or
     who points `SEED_ADMIN_EMAIL` at an account created by registration or the admin console —
     changed nothing that is ever encoded, and the next restart failed to boot. Reading the
     repository from `@PostConstruct` is safe for the reason `rejectPublishedAdminHash` already
     relies on (eager bean, repository injected, Flyway complete); the two free checks run first, so
     a healthy boot never reaches the query.
   - **`LoginRequest.password` stays at 1024** and keeps its missing `min`. It is a *reading* door,
     `matches` truncates safely, and the asymmetry is the design (rule 7).


---

## 5. Six spellings, or one — decided

**Decision: six. Keep the repeated literal `@Size(max = 255)` next to each `@Email`. Do not introduce
a composed constraint annotation and do not introduce a validated value type. Generalise the
*mechanism* — the category test — instead.**

### 5.1 Why not a composed annotation (`@EmailAddress`)

It looks like the obvious answer and it is a **net loss of guarantee** here, for three reasons in
descending order of force:

1. **It would break the thing that actually holds the rule.** `EmailLengthBoundTest` reads source
   *text*: it finds `@Email`, resolves the `String` it annotates, and reads the `@Size` in that
   declaration. A meta-annotation hides the `@Size` behind an annotation *type*, so the scanner would
   have to be rewritten to resolve meta-annotations reflectively — a strictly larger and more fragile
   machine — or be weakened to trust that `@EmailAddress` is used.
2. **The failure mode moves from loud to silent.** A DTO that carries `@Email` without `@Size` fails
   the suite today. A DTO that *forgets* `@EmailAddress` and writes `@Email` instead would compile,
   ship, and — if the test had been weakened per point 1 — pass. The annotation and the test are not
   additive: **the test is a strictly stronger guarantee, and the annotation's only advantage is
   removing repetition the test exists to police.**
3. **It cannot express the variants.** `@NotBlank` is right on every email door that exists today and
   wrong on the first optional one — an `email` field on a partial `PATCH` (the obvious next door is
   `admin/dto/UpdateUserRequest` growing one). A composed annotation would then need a second
   composed annotation, and two spellings of one rule is the defect this ticket is named after.

### 5.2 Why not a validated value type (`record EmailAddress(String value)`)

The strongest option in the abstract and the wrong size for this codebase. It changes every DTO, every
service signature, Jackson (de)serialisation across the Boot 4 / Jackson 3 boundary (a custom
`ValueInstantiator`, on the boundary this project already has one documented bridge for), and the
`openapi.yaml` schema shape — for a rule that is one integer. The project's own precedent is
consistently against wrapper types for scalar rules: enum-ish values are `VARCHAR` + a Java enum
(ADR-0005), permissions are an enum with no table (ADR-0008), case-insensitive uniqueness is a
constraint and not a type (ADR-0016). **Hamstrack's answer to "make this uniform" has been a rule
phrased about a category, not a type that carries the rule.**

### 5.3 What *is* generalised — and the honest limit on it

The dispatching brief asks whether a category test over all DTO→column pairs generalises, or whether
that scan would be too fragile to trust. **It is too fragile as a static scan and sound as a
behavioural one, and §0's Correction 2 is why.** A source scanner cannot follow DTO → service →
entity → column without type resolution the project has no infrastructure for; worse, both of this
sweep's 500s have **no annotated field at all**, so a perfect DTO→column scanner would have scored a
clean pass over the two real bugs.

So the mechanism to build is a **behavioural** category test, `RequestFieldLengthBoundTest`:

- **Table-driven.** One row per write endpoint that accepts free text: method, path template, a body
  factory that fills every `String` field with a 40 000-character value, and the fixture context the
  endpoint needs (anonymous / member / workspace admin / system admin).
- **The assertion is a class, not a value:** the response status is **4xx, never 5xx**. It does not
  assert *which* 4xx, so a service that answers 422 after normalisation and a DTO that answers 400 at
  the edge both pass — the claim is "no request path can write an over-length value into a column and
  surface a 500", which is AC 2 verbatim.
- **It catches the derived-value class**, which is the whole point: a 40 000-character workspace
  *name* is refused by `@Size(max = 255)` at the edge, so the row that catches the slug bug submits a
  **101-character name** — a value that is valid input and an invalid slug. Rows of that kind are
  written per finding and are the reason the harness is worth building rather than bought.
- **Two tripwires, in the shape `EmailLengthBoundTest.checked >= 6` already uses**, because every
  assertion in it is of the form "nothing offends":
  - `rows >= N` — a row that stops running is a door with no guarantee while the suite stays green.
  - a source scan counting `@PostMapping`/`@PutMapping`/`@PatchMapping` methods under
    `src/main/java`, asserting the total does not exceed the covered set plus a declared exclusion
    list. **This second assertion is the category claim** — it is what makes a new write endpoint a
    deliberate edit rather than an omission — and it is a scan of a shape local to one declaration,
    which is the only kind that has worked in this codebase.
- **Its failure message is the propagation checklist**, in the shape the throttled-path-set test
  already uses: it names the new endpoint, says what to add, and says explicitly *do not lower the
  tripwire.*

**The limit, stated so nobody mistakes the boundary for a guarantee:** this test proves a *status
class*, not a bound. An endpoint that truncates rather than refuses passes it, correctly (§3.3(c) is
a legitimate mechanism). It says nothing about columns reached by any route other than an HTTP write.

---

## 6. The three missing per-endpoint assertions

AC 1 says "asserted per endpoint, not once". `EmailLengthBoundTest` asserts end-to-end on
`/api/auth/register` and `/api/auth/login` only. Three doors have no end-to-end assertion.

### 6.1 `POST /api/auth/forgot-password` — unauthenticated

Assert, for the 300-character well-formed address fixture already in `EmailLengthBoundTest`:

7. status **400** (not the enumeration-safe 202/204 this endpoint otherwise always answers);
8. the body's `errors` map contains the key `email`;
9. **no mail is sent** — `verifyNoInteractions(mailSender)` on the `@MockitoBean JavaMailSender` the
   class already declares;
10. **no `mail_send_events` row is written and no rate-limit budget is spent** — the refusal happens
    during argument resolution, before the handler method body runs, so nothing downstream of the
    controller may observe the request.

**The enumeration question, answered explicitly because a reviewer will ask it.** This endpoint is
deliberately enumeration-safe: it answers the same thing whether or not an account exists. A 400 here
does **not** weaken that. The length of the submitted string is a property of the string, known to
whoever typed it, and the same 400 is returned for an address that exists, one that does not, and one
that could never exist. Nothing about the account database is disclosed.

### 6.2 `POST /api/auth/resend-verification` — unauthenticated

Assertions 7–10 in the same shape, on the same fixture.

### 6.3 `POST /api/workspaces/{ws}/invites` — authenticated, workspace-scoped

This one needs a member context and it is where the tenancy question lives.

11. A workspace **member holding `workspace.member.manage`**, posting the 300-character address:
    **400**, with `errors.email` present, and no invite row, no mail, no `mail_send_events` row.
12. `role` over-length (41+ characters, against `@Size(max = 40)`): **400** with `errors.role`.
13. **Validation precedes tenancy on this door, and the three refusals must be indistinguishable.**
    `@Valid` on a `@RequestBody` is evaluated during argument resolution, *before* the handler method
    runs — therefore before `WorkspaceAccessService` resolves membership. So an authenticated caller
    who is **not** a member, and one who names a **workspace id that does not exist**, both receive
    **400** rather than 404. Assert the three bodies match: the member's and the non-member's
    **byte for byte**, with nothing normalised, since they send the same request URI; the
    nonexistent workspace's modulo problem+json's `instance`, which is that URI echoed back by the
    framework and so necessarily differs. Assert `instance` is present, then drop it and compare.

    **Why that is correct and not a tenancy regression:** the project's invariant is that
    non-existence and non-membership are indistinguishable from one another, and here they are — both
    are the same 400 a member gets. The 400 reveals only that the route exists, which the published
    OpenAPI document already states. What would be a leak is the *opposite* outcome: a 400 for a
    member and a 404 for a non-member on the same malformed body would turn a validation error into a
    membership oracle. Asserting this is the tenancy-relevant part of HD-171, and it is worth a test
    precisely because a reviewer's default assumption runs the other way. An **unauthenticated**
    caller still gets 401 from the security filter chain, which runs before argument resolution.

---

## 7. What a refusal says — already correct, assert it

**`GlobalExceptionHandler.handleValidation` already names the field, in two places**, and no code
change is needed for AC 1:

- `detail` is the joined, deterministically sorted list of `"<field>: <message>"` (capped at
  `MAX_REPORTED_ERRORS = 10`, with `"; … and N more"` when it bites) — so the SPA's `request()`, which
  renders `detail`, shows the field name;
- a `ProblemDetail` extension `errors` is a `{field: message}` map over the same, same order.

For a record component the field path is the component name, so an over-long email yields
`{"detail":"email: size must be between 0 and 255","errors":{"email":"size must be between 0 and 255"}}`
with status 400.

**What `spring.mvc.problemdetails.enabled=true` changed, precisely.** It does **not** change the shape
this handler produces — the handler builds its own `ProblemDetail`. It changed two other things, both
already documented on the class: (a) it registers Boot's `ProblemDetailsExceptionHandler` at order 0,
which made `handleValidation` **dead code** until the advice was raised to
`@Order(HIGHEST_PRECEDENCE + 100)` — before that every `@Valid` failure in the app answered Boot's
generic `{"detail":"Invalid request content."}` and the `errors` map never reached a client; (b) it is
what makes a raw `ResponseStatusException`'s reason serialize at all, which is how the §3.3(b)
service-level length refusals ("Label name must be at most 60 characters") reach the UI.

Rule:

14. The **authoritative** refusal is the `@Size` at the edge (names the field) or the service check
    after normalisation (names the limit). §8's handler is a backstop and names neither.

---

## 8. The `DataIntegrityViolationException` question

**Recommendation: in scope, and narrowly — add exactly one SQLSTATE branch (`22001`) to the existing
`handleDataIntegrityViolation`, answering 400 and logging at ERROR. Do not widen the catch.**

### 8.1 The argument for

`22001 string_data_right_truncation` is, unlike `23503`, **unambiguous about whose fault it is**. The
database is stating that a value it was handed is longer than the column. There is no second direction
to guess at (the `23503` handler's whole difficulty is that it cannot tell a refused parent delete
from a refused child write), and there is no reading under which the caller supplied nothing wrong —
even a value the *server* derived over-long is derived from something a request supplied. It arrives
as `DataIntegrityViolationException`: Spring's `HibernateJpaDialect` maps `org.hibernate.exception.DataException`
to it, and the existing `sqlStateOf` cause-walk finds `22001` in either spelling. Without the branch,
**the next missed bound is a 500 again** — which is precisely how this ticket came to exist.

### 8.2 The argument against, and how it is answered

A catch-all 400 would mask genuine server faults: `23505` (unique), `23502` (not-null) and `23514`
(check) all arrive as the same Spring type, and each of them means *the application believed a write
was valid and it was not* — a server fault whose remedy is a fix, not a retry. **That argument is
correct and is exactly why the existing handler is SQLSTATE-gated rather than type-gated.** It does
not extend to `22001`, and the branch must not extend to them: everything that is not `23503` or
`22001` keeps today's outcome unchanged (ERROR + 500).

The residual risk — a `22001` on a value the caller genuinely did not control — is real and is
answered by logging, not by refusing to translate:

15. The `22001` branch logs at **ERROR** with the request method and the **mapped pattern** (never the
    URI, which carries workspace and project ids), plus the SQLSTATE. ERROR, not WARN, because unlike
    a lock timeout this is never normal: a `22001` reaching the handler means a bound is missing
    somewhere, and answering a clean 400 would otherwise remove the only signal an operator had. Same
    doctrine as `handleDateTime` and `handleQueryTimeout`.
16. It logs `ex.toString()`, not the throwable — **and what that carries was overstated in round 1.**
    `ex.toString()` is Spring's *translated* message, and
    `AbstractFallbackSQLExceptionTranslator.buildMessage` folds Hibernate's statement text into it, so
    the line reads roughly `could not execute statement [ERROR: value too long for type character
    varying(100)] [insert into issue_history (changed_by,created_at,field,issue_id,…) values
    (?,?,?,?,…)]`. So the correct sentence is: **the translated message carries the parameterised SQL
    — table and column names, no values.** Every parameter is a `?`, so no bind values, ids or row
    data arrive, and `logServerErrorDetail=false` (HD-133) keeps PostgreSQL's `DETAIL` out at the
    driver. Not a leak, nothing reaches the response body, and arguably *better* for the operator this
    line is written for — it names the table whose column is too narrow. Corrected in the code comment
    too, because this sentence is what a later reader will trust when deciding whether this log line
    is safe. The frames are Hibernate's.
17. The client gets a generic 400 that **names no field**, because the handler genuinely does not know
    which one: *"Some of the text you submitted is too long."* It carries the `errorType` extension
    `VALUE_TOO_LONG`, in the shape `REFERENCE_CONSTRAINT_VIOLATION` and `STATEMENT_BUDGET_EXCEEDED`
    already use — one convention for "which failure is this", not three.
18. Nothing from the database reaches the wire: not the width, not the SQL, not the column.

### 8.3 Where it must live

**At the MVC layer, in `GlobalExceptionHandler` — never in a service.** The project has already paid
for this once: a `catch` around `save()` never fires, because `save()` only queues the persist and
Hibernate flushes at commit, *after* the `@Transactional` method returns. A service-level catch here
would look identical to a working one in the happy path and translate nothing. There is also nothing
to translate *into* — no domain-specific status, unlike `DUPLICATE_INVITE`'s 409 — so the
`saveAndFlush` escape hatch buys nothing either.

19. The branch is sealed by a test that forces a **real** `22001` (an entity written directly with an
    over-long value, bypassing the DTO) and asserts 400 + `errorType`. A translation branch that is
    never entered is indistinguishable from one that works.

---

## 9. Data model impact

**One migration, one widening, no data movement.**

`V24__issue_history_field_width.sql`:

```
ALTER TABLE issue_history ALTER COLUMN field TYPE VARCHAR(100);
```

- `VARCHAR`, never `CHAR(n)`, never a PG `ENUM` — this is a widen of an existing `VARCHAR`.
- Widening a `varchar` length does **not** rewrite the table (PostgreSQL ≥ 9.2); it takes a brief
  `ACCESS EXCLUSIVE` on the catalog entry only.
- Entity parity: `IssueHistory.field` becomes `@Column(nullable = false, length = MAX_FIELD_LENGTH)`,
  where the constant is 100 — the number is written once in the entity and once in the SQL, and
  round 3 removed the third copy (an inline literal in `@Column`, justified by a "source scan of the
  mapping" that does not exist: the only source scanner in the tree, `EmailLengthBoundTest`, matches
  `@Size` and never `@Column(length = …)`). Since nothing mechanical keeps the numbers equal, each
  extra copy is a drift surface.
- No new table, no new column, no UUID, no `@CreatedDate`, no index.
- Nothing else in this spec touches the schema. The five `@Size` additions are on `TEXT`-backed
  fields and change no DDL.

---

## 10. API surface

**No new endpoint, no changed path, no changed DTO shape.** What changes is which bodies are accepted:

| endpoint | before | after |
|---|---|---|
| `POST /api/workspaces` | 500 on a name whose slug exceeds 100 | 201, with a slug clipped to 100 |
| `PATCH /api/workspaces/{ws}/projects/{p}/issues/{n}` | 500 when a custom field with a 51–100-char name changes | 200 |
| `POST`/`PATCH` issues, comments, projects, workflows | any length accepted | **400** naming the field above 10 000 characters |
| `POST /api/auth/login` | any password length accepted | **400** naming `password` above **1024** (§4.4 rule 7 — a resource guard on a reading door, justified as "finite") |
| `POST /api/auth/register`, `/reset-password` | **500** above 72 UTF-8 bytes of password (73 ASCII characters, 37 Cyrillic ones); 400 above 100 characters | **400** naming the field above 72 characters, **422 `PasswordTooLongException`** above 72 *bytes* (§4.4 rule 9) |
| `POST /api/auth/verify-email`, `/reset-password` | any token length accepted | **400** naming `token` above 64 |
| `GET /api/auth/verify-email?token=` | any length accepted (500 past ~8 KB) | **400** above 64 |
| `POST /api/workspaces/accept-invite?token=` | any length accepted | **400** above 64 |
| `POST`/`PATCH` `/api/admin/fields` | any `config` accepted | **422** above a 20 000-character serialized `config` (every type), above 100 options, or an option `id`/`label` above 100 characters |
| any write that still overflows a column | 500 | **400** `VALUE_TOO_LONG` (backstop, §8) |

`openapi.yaml` and both `docs/api-*.md` must gain the new `maxLength` on the affected request schemas
and the `VALUE_TOO_LONG` error type — `api-docs-sync` runs as a conditional gate on this ticket.
Three password-specific edits are easy to miss because they contradict text already written:
`openapi.yaml`'s `maxLength: 100` on `RegisterRequest.password` (line ~9200) and on
`ResetPasswordRequest.newPassword` (line ~672) become **72**, and both `docs/api-*.md` say
"8–100 characters" in the published-password section — that sentence is now wrong twice over and
must also mention the **422 refusal above 72 bytes**, with the byte arithmetic, since a caller
cannot derive it from a character bound.

---

## 11. Frontend impact

Small and worth doing, because a bound the UI does not know about is a refusal the user meets after
typing rather than while typing.

- Add `maxLength={10000}` to the issue description editor, the comment composer and the project
  description field (`IssueDetail.tsx` body, the comment box, project settings), matching §4.3
  exactly. The values are literals in both places; a mismatch is a refusal the client cannot explain.
- Add `maxLength={72}` to the password inputs on `RegisterPage.tsx` and `ResetPasswordPage.tsx`
  (not `LoginPage.tsx`, whose door is deliberately generous — §4.4 rule 7). It is a hint, not the
  guard: the server still refuses by *bytes*, which an `input` cannot count, so a Cyrillic
  passphrase under 72 characters can still come back 422. The banner renders that `detail`, which
  names bytes and explains the arithmetic.
- No new component, no new store, no config-driven rendering change, no `DESIGN.md` question. The
  existing error banner already renders `detail`, which now names the field.
- Nothing renders `workspaces.slug` from a client-supplied value, so §4.1 is invisible to the SPA.

---

## 12. DC/Cloud implications

**None.** No profile gating, no new property, no new environment variable, no compose/`.env.prod.example`
/README wiring. Every bound in this spec is a compile-time literal or a schema width, identical in
both modes; the one migration runs in both. `dc-cloud-guard` is **n/a** for this ticket, and that is a
deliberate statement rather than an omission: a length bound that differed between deployments would
mean a body accepted on DC and refused on Cloud, which is the class of divergence the single-codebase
rule exists to prevent.

---

## 13. Acceptance criteria

Numbered and individually testable. AC 1–3 are the ticket's own.

1. A ~300-character well-formed address answers **400** with a validation error naming `email`, on
   **every** endpoint that accepts one — `register`, `login`, `forgot-password`, `resend-verification`
   and `POST /api/workspaces/{ws}/invites` — asserted **per endpoint**, five assertions.
2. No request path can write an over-length value into a `VARCHAR` column and surface a 500 —
   asserted by `RequestFieldLengthBoundTest` (§5.3) over every covered write endpoint, as a status
   *class* (4xx, never 5xx).
3. The sweep of §3 is in this document, each row either fixed or recorded as already bounded, with
   zero untraced rows.
4. `POST /api/workspaces` with a 200-character name returns **201** and the stored slug is ≤ 100
   characters.
5. `POST /api/workspaces` with a 98-character name that collides with an existing slug returns **201**
   and the stored slug (base + suffix) is ≤ 100 characters — the near-miss, asserted separately.
6. `PATCH …/issues/{n}` changing the value of a custom field whose name is 100 characters returns
   **200**, and the resulting `issue_history` row exists. **This is the only assertion that can fail
   if `V24` is missing or the widths drift** — see AC 7.
   - and the belt is asserted **on the entity**, not through one service: `IssueHistory.setField`
     stores 100 for a 120-character value, so every writer of that column inherits it; and a value
     whose 100th and 101st units are a surrogate **pair** stores 99 units, never a lone high
     surrogate.
7. `V24` applies cleanly to a database built from `V1` onward. **It is deliberately NOT asserted that
   `ddl-auto=validate` catches a width mismatch, because it does not** — Hibernate's validator
   compares JDBC type codes (`ColumnDefinitions.hasMatchingType`), and `hasMatchingLength` is reached
   only from `ddl-auto=update`. Round 1 stated the opposite in three places and made this criterion
   trivially true: it would have passed **with the migration deleted**. An acceptance criterion that
   cannot fail is worse than none, so what remains here is the migration applying, and the behavioural
   half is AC 6.
8. An issue description / comment body / project description / workflow description of 10 001
   characters returns **400** whose `errors` map names that field; 10 000 returns 2xx.
9. `POST /api/auth/login` with a **1025**-character password returns **400** naming `password`; a
   7-character password still returns **401**, not 400 (no `min` on this door); and a **128**-character
   password still reaches `AuthService.login` and answers 200/401 on its merits, never 400 — a
   reading door refuses nothing on length that the encoder would have truncated anyway.
9a. **The writing doors do not advertise a range the encoder refuses** (§4.4 rule 9, round 3). Each
    of these must answer **4xx and never 5xx**, on `POST /api/auth/register` *and* on
    `POST /api/auth/reset-password`:
    - a **73-character ASCII** password — 73 bytes, one past `BCrypt.hashpw`'s ceiling;
    - a **37-character Cyrillic** password — 74 bytes but only 37 UTF-16 units, so it passes
      `@Size(max = 72)` and is refused by the byte check instead. **This is the case that proves the
      two guards are not redundant**; without it the annotation looks sufficient and is not;
    - a **72-byte** password (72 ASCII characters, and separately 36 Cyrillic ones) is **accepted** —
      the bound is exact, not approximate.
    - The refusal is **422** with a message naming *bytes*; the reset-password case additionally
      leaves the reset token **unused**, so the same link still works on a second attempt.
9b. `DataSeeder.MAX_SEED_PASSWORD_BYTES` **equals** the `@Size(max = …)` literal on
    `RegisterRequest.password` and on `ResetPasswordRequest.newPassword` — **72, compared as bytes**,
    not the 1024 characters an earlier round paired to `LoginRequest` — asserted by a test that reads
    both (the `@Size` side by source scan, per rule 3). A `seed.admin.password` of 73 bytes that
    **would actually be encoded** — `seed.admin.email` set, no account yet at that folded address —
    **refuses the context refresh** with a message naming the limit and the variable, in the same
    shape `SeedAdminPasswordValidationTest` uses; and the same 73-byte value **boots normally**
    wherever it would never reach the encoder — with **no** `seed.admin.email` configured, and
    equally with an account **already occupying** that address — because the length guard is gated
    on all three conditions ("will this value actually be encoded?", §4.4 rule 9), while the
    published-password guard beside it deliberately is gated on none of them.
10. `POST /api/auth/verify-email` and `/reset-password` with a 65-character token return **400**
    naming `token`.
    - and `GET /api/auth/verify-email?token=<65 characters>` returns **4xx (never 5xx)**, while a
      43-character token still redirects **302** to `/verify-email?token=…`. Asserted as a status
      *class* on the refusal because the exact code depends on which method-validation mechanism
      fires — but a **500 fails this criterion**, since that is the outcome the bound exists to remove
      and the one `@Validated` would have reintroduced (§4.4 rule 8).
    - and `POST /api/workspaces/accept-invite?token=<65 characters>`, authenticated, returns
      **4xx (never 5xx)** for the same reason on the same mechanism — the fourth token door, bounded
      in round 3.
11. `POST /api/admin/fields` with 101 options, or with an option whose `label` is 101 characters,
    returns **422**; **and** a `config` over 20 000 characters returns **422 for a non-SELECT type
    too** — the case the option checks never see.
12. `POST /api/auth/forgot-password` and `/resend-verification` with the over-long address return
    **400**, and `JavaMailSender` records **no interaction** and `mail_send_events` gains no row.
13. `POST /api/workspaces/{ws}/invites` with the over-long address returns the same **400** for
    (a) a member with `workspace.member.manage`, (b) an authenticated non-member, and (c) a
    workspace id that does not exist; an **unauthenticated** caller still gets **401**. (a) and (b)
    send the same request URI, and their bodies are **byte-identical** — compared with nothing
    normalised, which is the tenancy-relevant half of this AC. (c) necessarily differs in exactly
    one member: problem+json's `instance` is the request URI echoed back by the framework
    (`GlobalExceptionHandler` never calls `setInstance`), so it carries the caller's own input and
    nothing the server knows. It is asserted **present** and then normalised away before the
    comparison. "Byte-identical across all three" is one step stronger than the wire allows, and is
    deliberately not what this AC asks for.
14. A test asserts that the issue-description bound and `FieldValueService`'s TEXTAREA bound are the
    same number, so raising one raises both.
15. A forced `22001` (an entity written directly with an over-long value) answers **400** with
    `errorType: "VALUE_TOO_LONG"`, and the response body contains no column name, no width and no SQL.
16. A forced `23505` still answers **500** — the branch did not widen.
17. `EmailLengthBoundTest` still passes unchanged, including its `checked >= 6` tripwire.
18. `RequestFieldLengthBoundTest`'s two tripwires fail when a write endpoint is added without a row —
    verified by adding a throwaway `@PostMapping` and observing the failure, then removing it.
19. `openapi.yaml` validates (swagger-cli) and both `docs/api-*.md` carry the new `maxLength` values
    and the `VALUE_TOO_LONG` error type.

---

## 14. Open questions

1. **Is 10 000 the right number for prose?** *(recommended default: yes, ship it.)* It is chosen to
   agree with the TEXTAREA custom-field bound that already exists rather than to be independently
   optimal, and agreement is worth more than optimality here. ~4 pages of text. The realistic
   complaint is a pasted stack trace in an issue description; the realistic answer is an attachment.
   If it ever needs raising, AC 14 forces both numbers up together.

   **The bounds are RETROACTIVE, and round 2 reasoned only about the future complaint** (added in
   round 3). These fields have been unbounded for the life of the product, so a description or
   comment that is *already* 15 000 characters — the spec's own example, a pasted stack trace — now
   answers **400 on every save**, not only on an attempt to make it longer: the SPA submits the whole
   description on each edit, so an untouched over-long field refuses a change to any *other* part of
   the same request. It is self-healing (shorten once and it saves), it is not a security issue, and
   it does not lose stored data — nothing rewrites the row, and reads are unaffected — but it is a
   silent behaviour change on **existing** rows, which is a different claim from "new bodies are
   refused" and has to be stated as one.

   Operators can size it before upgrading:

   ```sql
   SELECT 'issues' AS t, count(*) FROM issues WHERE length(description) > 10000
   UNION ALL SELECT 'issue_comments', count(*) FROM issue_comments WHERE length(body) > 10000
   UNION ALL SELECT 'projects', count(*) FROM projects WHERE length(description) > 10000
   UNION ALL SELECT 'workflows', count(*) FROM workflows WHERE length(description) > 10000;
   ```

   **`length()` counts code points; the bound counts UTF-16 code units, so this number is a
   floor.** Jakarta `@Size` measures a Java `String`, where anything outside the BMP — emoji, CJK
   Extension B — costs **two** units and one code point, while PostgreSQL's `length()` charges one
   for each. An emoji-dense 8 000-character description is ~14 000 units: it will 400, and the
   query above counts it as fine. A zero is therefore "no plain-text row is over", not "no row is
   over". For a result that is a real all-clear, run the same query with `octet_length(x) > 10000`
   instead — UTF-16 units are never more than UTF-8 bytes, so a zero there rules the table out
   outright. It over-counts (Cyrillic and CJK prose costs 2–3 bytes per character, so a
   comfortably-short row can be listed), which is why it is the second query and not the first: use
   `length()` to see who is *probably* affected, `octet_length()` to prove that nobody is.

   **The findings table in §3.2 is the authority for what this query counts, and this query is
   derived from it — never maintained beside it.** It shipped one table short: §4.3 bounds *five*
   prose fields and the query listed three, missing `workflows.description`
   (`UpsertWorkflowRequest.description`), which is retroactive in exactly the same way — an existing
   over-long workflow description 400s on every save. Admin-only lowers the blast radius and changes
   nothing about the defect. This is the ticket's own rule failing against the ticket's own table:
   *a number goes stale one entry before the list does*, and "the five prose fields" was written
   above a query that counted three.

   `field_defs.config` is retroactive on the same terms and is deliberately **not** in the query
   above, because it is not a `length()` on a prose column: `AdminFieldService.requireConfigSize`
   refuses a *serialized* `config` over 20 000 characters with 422, so an existing field definition
   whose stored document exceeds that now fails every save through the admin field editor until it
   is trimmed. Admin-only, self-healing, and no stored data is lost.

   Not migrated and not truncated on purpose: a migration that clipped existing prose would destroy
   content the user still owns to satisfy a bound they never agreed to, which is strictly worse than
   a refusal they can act on. Release notes carry the query, and so does the operator manual:
   `docs/self-hosting.md`, "Free text is bounded from 0.18.0" under `## Upgrading`, which is
   the file a self-hoster actually opens. The two copies of the query must stay identical.

2. **A global request-body cap.** Nothing in the tree limits a JSON body's size —
   `spring.servlet.multipart.max-*` bounds only multipart. A container-level cap would be a cheaper,
   blunter version of §4.3 and it interacts with every endpoint at once (413, no field named).
   *Recommended default: not in this ticket.* Field-level bounds name the field; a body cap does not,
   and shipping the blunt instrument first would remove the pressure to ship the precise one.
   **Filed as its own ticket; deliberately not implemented here.**

   **What must not be misread, and round 1's framing invited exactly that misreading.** This spec
   calls §4.3's bounds "payload guards", which implies they constrain resource *consumption*. **They
   do not. Every `@Size` in this document runs AFTER deserialization**, so each one bounds what is
   *stored*, never what is *allocated*. An authenticated member can POST
   `{"body":"<20 000 000 characters>"}`, the server materialises a ~40 MB `String` on the heap, and
   *then* validation answers 400. Nothing in the tree caps a JSON body: `multipart.max-*` is multipart
   only, Tomcat's `maxPostSize` is form-urlencoded only, the `Caddyfile` sets no
   `request_body max_size`, and Jackson's 20 M-character `StreamReadConstraints` default is the only
   ceiling anywhere. The hole is **pre-existing and this change does not widen it** — but the gap is
   the body cap's, not these bounds', and `GlobalExceptionHandler.handleDataIntegrityViolation`'s
   neighbourhood already documents the same gap in code.
3. **Should §5.3's harness cover `PUT`/`DELETE` bodies and multipart?** *(recommended default: JSON
   write endpoints only for v1.)* Multipart filename length is already bounded by truncation
   (§3.3(c) row 44) and is the only text a multipart request contributes to a column.
4. **`oauth_accounts` (§3.4 row 64).** Four unbounded columns on a table nothing writes.
   *Recommended default: leave them, and let this row be the note the OAuth ticket reads.* Bounding a
   column whose writer does not exist is guessing at a provider's format.

---

## 15. The highest-risk assumption, flagged

**That `@Valid` on a `@RequestBody` really is evaluated before the handler body — and therefore before
`WorkspaceAccessService` resolves membership — on `POST /api/workspaces/{ws}/invites`.** §6.3 builds a
tenancy claim on it, and if the ordering were the other way the assertion in AC 13 would be pinning
the wrong behaviour. It is argument-resolution ordering in Spring MVC and is not in doubt as
mechanism; what makes it worth flagging is that the *conclusion* ("a non-member gets 400, and that is
correct") reads backwards against this project's strongest reflex, which is that a non-member gets
404. **AC 13 must be written as a test that observes the three statuses, not as a test that asserts
the ordering** — so if the ordering is ever changed by a filter, an interceptor or a future
`@InitBinder`, the test reports the new reality rather than passing on a stale premise.

**The condition that assumption actually rests on, added after the tenancy review confirmed the
ordering — and it is a prohibition, not a footnote.** The indistinguishable 400 for member, non-member
and nonexistent workspace holds **only while every constraint on these DTOs is a pure function of the
submitted body**. `handleValidation` takes no `HttpServletRequest`, so nothing request-derived can
enter it *today*; that is a property of the constraints in use, not of the mechanism. **The day
someone adds a database-touching `ConstraintValidator`** — "this email is already a member",
`@ValidRoleForWorkspace`, "this role id belongs to this workspace" — **that 400 silently becomes the
membership oracle the 404 rule exists to prevent**, because a constraint that queries by workspace id
answers differently for a member and a stranger while the handler's *shape* does not change at all.
So: **no DB-touching `ConstraintValidator` on a workspace-scoped request DTO.** Cross-entity checks
belong in the service, *after* `WorkspaceAccessService` has resolved membership and earned the right
to answer anything but 404. Recorded here and as a prohibition in ADR-0017.

Secondary risk: §4.2's truncation belt is a silent clip. A 100-character custom field name renders in
full and a hypothetical 120-character source would render clipped with nothing saying so. Accepted —
`issue_history.field` is a label nothing keys on, and the alternative on that path is a 500.

---

## 16. Architectural decisions (ADR)

One. **A length bound is expressed as a repeated literal `@Size` at each door, and the guarantee that
every door has one is a category test — not a composed constraint annotation and not a validated value
type.** Chosen option, rejected alternatives and trade-off in §5; drafted as
`docs/adr/0017-length-bounds-as-repeated-literals-sealed-by-category-tests.md` (Status: Proposed).

Deliberately **not** an ADR: §8's `22001` branch. It follows HD-13's already-recorded decision
(SQLSTATE-gated translation inside one handler) rather than forking from it, and adding a state to an
existing gate is routine feature mechanics.
