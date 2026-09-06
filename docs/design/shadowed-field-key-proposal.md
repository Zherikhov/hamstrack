# Shadowed custom-field keys — make the shadowing visible, and let the tenant rename out of it

Ticket: **HD-275**
Status: **Shipped in 0.18.0.** This file is kept as the dated pre-implementation record, so it
still describes the system it was written against and is deliberately not rewritten to match the
build. Three of its statements are false of what shipped, and are listed here rather than edited in
place so the difference between what was planned and what was built stays legible:

- **"create-only"** (§§ near lines 20, 475, 520) — the reserved-key guard now refuses at **both**
  doors that mint a key, create and the rename target. The property it actually has, and the one
  those passages need, is that it is **never retroactive**: it touches no existing row.
- **"today's SPA sends the unchanged key on every update"** (near lines 241, 773) — the admin console
  stopped doing that in the same change. The difference-not-presence trigger is still right, but it
  is justified by the category (any client that round-trips the stored key), not by that console.
- **§7.6 / §12-Q4 on retired alias keys** — a retired key (`story_points`, `fix_version`) is now
  refused by a **rule** of its own at both minting doors, not merely occupied by an archived global
  placeholder that somebody could delete.

The follow-up this spec knowingly leaves open is **HD-284**: key occupancy resolves upward only, so
neither door refuses a key already held in a descendant scope.
Decision already taken by the owner: **option 5 + option 3**. §3.4 records the rejected options so a
later reader does not re-propose them.
Touches: `com.hamstrack.search` (`FieldRegistry`, `FieldResolver`, `SearchService`,
`SearchSchemaResponse`), `com.hamstrack.admin` (`AdminFieldService`, `AdminFieldResponse`,
`UpsertFieldRequest`, all three admin field controllers), `issue.entity.FieldDef`,
`issue.repository.FieldDefRepository`, SPA (`AdminFieldsPage`, `SearchResultsPage`/`HqlInput`,
`types.ts`), `openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md`.

---

## 1. Problem & goal

`FieldRegistry` reserves a search name for the product: `FieldResolver` consults the registry
*before* the caller's own custom fields, so a registered name outranks any tenant's `field_defs` row
of the same key. `AdminFieldService.requireUnreservedKey` stops a *new* field being created under a
claimed key, and its javadoc says so in as many words — **create-only, never retroactive**. The HQL
registry gained `labels` in the V8 slice and `components` in V9; `field_defs` uniqueness is per scope
(`field_defs_scope_key_key UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, key)`),
so a workspace- or project-scoped custom field keyed `labels` or `components` created *before* those
slices survives, and is now permanently shadowed by a name the product took.

The failure is the worst available shape. Nothing is lost and nothing is unreachable — the field
still renders on issues, still sits in field sets, still appears in `ProjectConfigResponse`. What
breaks is the **query surface**, silently, in two directions:

- `FieldResolver.resolve` answers `labels` with the system LABEL descriptor, so a saved filter the
  tenant wrote against **their own field** returns **200 with plausible rows they never set on that
  field**. No error, no log line, no 4xx anywhere.
- `SearchService.schema` skips any custom field whose key the registry claims
  (`if (registry.find(meta.key()).isPresent()) continue;`), so `/schema` omits it **with no reason
  given**.

A tenant can only notice an *absence*, while every query keeps answering confidently. And they
cannot rename out of it: `FieldDef.key` is `@Column(updatable = false)` and `updateField` ignores
`req.key()` entirely.

**Goal.** Two things, and only these two:

1. **End the silence.** `/schema` reports the shadowed field with a reason instead of dropping it;
   the admin console shows the collision on the field row; the instance names the collisions it
   finds at startup. This restores no queryability — it ends the *silence*, which the ticket names
   as the worst available form of refusal.
2. **Hand the exit to the tenant.** A field's key becomes renameable **exactly while a registry name
   shadows it**, so the field can be moved to a key the product has not taken and becomes searchable
   again under the new name.

Plus the part that makes it durable rather than a one-off cleanup: **a future registry addition
cannot repeat this by construction** (§9).

## 2. Diagnostic — recorded, because it shapes the audience and not the work

Run on **production, 2026-09-06**:

- **Zero** shadowed field definitions.
- **Zero tenant-scoped `field_defs` rows at all** — all twelve existing definitions are global
  (`scope_workspace_id IS NULL AND scope_project_id IS NULL`).

So the population in which a collision is possible is **empty by construction on that box**. That
confirms the ticket's own analysis: demo seeding never creates a `field_def`, and every
migration-inserted field is global. **HD-275 is about self-hosters and about the future**, not about
anyone who can be named. It does not shrink the work: 5 + 3 was chosen for exactly that audience,
and §9 is the half that is written *only* for a tenant who does not exist yet.

One consequence to hold on to while reading the rest: **a clean instance must stay quiet.** V3 seeds
global system placeholders keyed `labels`, `sprint` and `components` — all three registry-claimed —
and V8/V11/V9 archive them. If any surface here treated an *archived* claimed-key row as shadowed,
every Hamstrack instance in existence would warn about its own seed data on every boot. §5 makes
that a predicate rather than a habit.

## 3. Scope

### 3.1 In scope

| # | Item |
|---|---|
| S1 | One place that answers "is this key claimed, and by which built-in name" — `ShadowedFields`, in `com.hamstrack.search` |
| S2 | `GET …/search/schema` gains `shadowedFields` — the caller's own shadowed custom fields, with the built-in name that claims each key |
| S3 | `AdminFieldResponse` gains `shadowedBy` — the built-in search name claiming this field's key, or `null` |
| S4 | Admin console: a warning on a shadowed field row, and an editable **Key** input in the edit dialog while the field is shadowed |
| S5 | `PATCH …/fields/{id}` accepts a changed `key` and renames the field, under the guard in §6.2 |
| S6 | A startup scan that names every shadowed field definition it finds, at WARN, once per boot |
| S7 | A build-time ledger over `FieldRegistry`'s claimed names, whose failure message is the propagation checklist (§9) |
| S8 | Search page: a notice when `/schema` reports shadowed fields |
| S9 | Rider: `createField`/`updateField` switch to `saveAndFlush` and translate `field_defs` unique violations to 409 (§7.3) |
| S10 | `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, `docs/release-checklist.md`, `docs/self-hosting.md` |

### 3.2 Out of scope — non-goals

- **Qualified access syntax (`cf.labels`, `customfield.labels`, or any prefix).** This is the right
  long answer and it is explicitly **not** this change: it is new lexer/parser syntax, a `/schema`
  vocabulary change, a docs change and a saved-filter compatibility question. Rejected for 0.18.0 in
  §3.4. **A builder must not drift into it.** Nothing in this spec adds a token, a grammar rule or a
  reserved prefix.
- **Rewriting anybody's saved-filter text.** No migration and no service ever edits `saved_filters`.
  That rule predates this ticket (delivery-paths §9.2) and survives it unchanged.
- **Making a shadowed field queryable under its shadowed key.** Impossible without §3.4's rejected
  precedence flip. The rename is the exit; there is no other.
- **Renaming as a general capability.** After the rename the key is immutable again, because the
  general argument for immutability (saved filters are stored as text) is true again.
- **An audit table for taxonomy changes.** There is none today; see §12 Q2.
- **A metric/gauge for shadowed fields.** Rejected in §11.
- **Any change to `FieldResolver`'s precedence order.** It is a tenancy-safety property and stays
  exactly as it is.
- **Removing `labels`/`components` from the registry.** Rejected in §3.4.
- **Anything about `field_defs.name` (the display name).** Only `key` is in play.

### 3.3 Adjacent, deliberately left alone

`RetiredFieldAliases` needs no entry and no edit. The rename does not retire a key: after
`labels` → `team_labels`, the name `labels` still resolves to the built-in `label` field exactly as
it did before, so there is nothing to keep working. `RetiredFieldSweepTest` reads migrations, and
this change ships none.

### 3.4 Rejected, and they stay rejected

Recorded here so a later reader does not re-propose them.

| Option | Why it is rejected |
|---|---|
| **Drop the plural from the registry** (delete `labels`/`components` as registry aliases) | Fixes one tenant and breaks the vocabulary for every other. It is also *not* the harmless precedence change it looks like: `RetiredFieldAliases` documents that for a workspace that owns a field keyed `labels`, deleting the plural is a silent change of **meaning** — the same filters keep answering 200, from different rows. Trading a silent wrong answer for a different silent wrong answer. |
| **Precedence by presence** — the custom field wins where one exists | One name would mean different things in different workspaces, and every existing saved filter in a victim workspace would **silently change meaning** the moment the release lands. That is the identical harm, inside out, and it would arrive without anyone choosing it. It also deletes the property `FieldResolver`'s javadoc exists to protect: the product's vocabulary cannot be captured per tenant. |
| **Qualified access, `cf.labels`** | The right long answer. New parser syntax + `/schema` vocabulary + docs + a saved-filter compatibility story — not 0.18.0. Revisit as its own ticket; nothing in this change forecloses it. |

## 4. Actors & permissions

Everything here rides on surfaces that already exist. No new permission, no new role, no change to
any authorization primitive.

| Surface | Actor | Gate | Tenant scoping |
|---|---|---|---|
| `GET /api/workspaces/{ws}/search/schema` | any workspace member | `WorkspaceAccessService.requireMember` — **404** whether the workspace is missing or the caller is not a member | `shadowedFields` is built from `ResolutionContext.customFieldsByKey()`, which is assembled from the caller's **visible projects** only. No repository query is added. |
| `PATCH /api/admin/fields/{id}` | instance admin | `hasRole(ADMIN)` in `SecurityConfig` | `ScopeContext.global()` — reaches global rows only (`findByIdAtScope(id, null, null)`) |
| `PATCH /api/workspaces/{ws}/admin/fields/{id}` | workspace admin | member **+** `Permission.WORKSPACE_TAXONOMY_MANAGE` (403 for a proven member; **404** for a non-member or unknown workspace) | `ScopeContext.workspace(ws)` — reaches rows stamped with that workspace only |
| `PATCH /api/workspaces/{ws}/projects/{p}/admin/fields/{id}` | project admin | member **+** `Permission.PROJECT_TAXONOMY_MANAGE` | `ScopeContext.project(ws, p)` — reaches rows stamped with that project only |
| Startup scan | nobody — it is a process, not a request | n/a | Reads across tenants **by design**; it is an operator-facing log line on the operator's own instance, and it emits ids/keys/scope ids, never issue data or values. |

**Who can rename what, stated as the thing that matters:** `requireField` already resolves through
`findByIdAtScope`, so a workspace admin can only rename a field **stamped with their own workspace**,
and a project admin only a field stamped with their own project. **A global shadowed field can only
be renamed by an instance admin**, and that rename changes the key in every workspace on the
instance at once — the same blast radius `RetiredFieldAliases` warns about for a global def under a
retired key. The refusal messages in §6.2 are the only place that ever names a remedy, and each one
names a remedy its own recipient can perform.

## 5. Two predicates, deliberately different

One component owns both, for the same reason `FieldResolver` is a component and not a convention: an
answer each consumer derives for itself is an answer that holds only for the consumers that existed
the day it was written.

**New: `com.hamstrack.search.ShadowedFields`** (`@Component`, takes `FieldRegistry`).

```
claimedBy(key)     → Optional<String>   the CANONICAL registry name claiming this key, else empty
                                        (registry.find(key).map(FieldDescriptor::name))
shadowing(fieldDef) → Optional<String>  claimedBy(fieldDef.key), but empty when archivedAt != null
```

- **`claimedBy` is archive-blind and availability-blind.** Registration is what claims a name;
  `FieldDescriptor.available()` says only *when* it starts answering (`FieldResolver` javadoc), so a
  reserved-but-not-yet-queryable entry claims its key just as firmly. Aliases are ordinary map
  entries in `FieldRegistry.byName`, so `labels`, `components`, `closedAt`, `sprints`, `points`,
  `fixversion` are all claimed keys — which is correct: the shadowing is caused by the *lookup*, not
  by the canonical spelling.
- **`shadowing` adds "and it is live".** This is what §2 demands: the three archived V3 placeholders
  are claimed but not shadowed, so a clean instance produces no warning anywhere.

Consumers:

| Consumer | Predicate | Why that one |
|---|---|---|
| `SearchService.schema` → `shadowedFields` | `shadowing` (satisfied by construction — `ResolutionContextFactory` already skips archived defs) | a warning about a row that is out of resolution is noise |
| Startup scan | `shadowing` | same |
| Admin console SPA warning badge | `shadowedBy != null && !archived` | same, computed client-side from the DTO |
| `AdminFieldResponse.shadowedBy` | **`claimedBy`** | the rename affordance lives here and must be offered for an archived row too (§7.1) |
| Rename permission (§6.2) | **`claimedBy`** | same reason: the invariant that licenses the rename holds for an archived row as well |
| `AdminFieldService.requireUnreservedKey` (create, and the rename target) | `claimedBy` | unchanged behaviour, now routed through the one component |

`AdminFieldService` stops injecting `FieldRegistry` directly and injects `ShadowedFields` instead, so
there is exactly one definition of "claimed" in the codebase.

## 6. Behaviour & rules

### 6.1 Making the shadowing visible (option 5)

**R1 — `/schema` reports rather than drops.** In `SearchService.schema`, the custom-field loop stops
at `continue` and instead records the field:

```
for (CustomFieldMeta meta : ctx.customFieldsByKey().values()) {
    var claimed = shadowedFields.claimedBy(meta.key());
    if (claimed.isPresent()) { shadowedFields_out.add(new ShadowedField(meta.key(), meta.name(), claimed.get())); continue; }
    ... unchanged ...
}
```

**R2 — the shadowed field does NOT enter `fields`.** This is the load-bearing half of R1. Every entry
in `fields` is a name the caller may write and that means what the entry says it means; putting a
shadowed key there would make the SPA offer `labels` in autocomplete and then answer it from the
built-in label links — the exact harm, now with the product's own suggestion behind it. The
invariant "`fields` is queryable and honest" is preserved; the reason lives in its own list, which
is why the list is named for the reason rather than carrying a discriminator column.

**R3 — deterministic order.** `shadowedFields` is sorted by `key` (case-insensitive).
`customFieldsByKey` is a `LinkedHashMap` in visible-project iteration order, which is not stable
between requests.

**R4 — empty is absent-shaped, not error-shaped.** An unshadowed workspace gets `shadowedFields: []`.
No status code changes anywhere; `/schema` is 200 as before.

**R5 — the admin console shows it.** `AdminFieldResponse.shadowedBy` is populated on every field
response (list, create, update, and the field entries inside `AdminFieldSetResponse`). The SPA
renders a warning on the row when `shadowedBy != null && !archived` (§10).

**R6 — the instance names it at startup.** §8.

### 6.2 The rename (option 3)

`AdminFieldService.updateField`'s javadoc claims *"Type and key are immutable — stored values depend
on both"*. That is **true of the type and false of the key**: values live in `issue_field_values`
keyed by `field_id`, a UUID (`UNIQUE(issue_id, field_id)`, `FK field_id → field_defs(id)`), and
nothing in `FieldValueService` reads the key at all. **A rename moves no rows.**

The real dependency is the **text of saved filters** — HQL is stored verbatim and resolved at read
time, and nothing rewrites it. Which produces the invariant that licenses the whole feature:

> **The rename is permitted exactly in the population where it cannot change what any saved filter
> means.** While `labels` is claimed by the registry, a filter reading `labels = "x"` resolves to the
> built-in `label` field — before the rename and after it, because the registry still claims the
> name. The tenant's own field was unreachable from HQL beforehand and is unreachable under its old
> name afterwards. Nothing any stored query means changes.

**R7 — a rename is requested by sending a `key` that differs from the current one.** `key` is already
on `UpsertFieldRequest`; `updateField` currently ignores it.

- `key` absent, `null`, blank, or equal to the current key (case-insensitively) → **no rename, no
  refusal**. This is a hard compatibility rule: today's SPA initialises its key state from
  `field.key` and sends it on **every** update, so treating "key present on update" as a rename
  attempt would 422 every existing edit. The trigger is *difference*, never *presence*.
- `key` blank on update never means "re-derive from the name". Deriving would silently rename a
  field whenever a curator edited its display name.
- Compared case-insensitively; stored lower-cased. `@Pattern("[a-z0-9_]*")` + `@Size(max = 50)`
  already bound it (400 on violation, unchanged).

**R8 — refusals, in this order.** The order matters: the common mistake must get the message that
explains the actual rule, not a message about the target key.

| # | Condition | Status | Message |
|---|---|---|---|
| 1 | the field is not resolvable at the caller's scope | **404** | `Field not found` (existing) |
| 2 | `f.isSystem()` | **409** | `System fields cannot be renamed.` — matches `deleteField`'s system refusal, and it is load-bearing: `DemoDataService` resolves the global `severity`/`environment` defs **by key**, and every V3 placeholder is a system def. |
| 3 | `shadowedFields.claimedBy(f.getKey()).isEmpty()` — the field is **not** shadowed | **422** | `A field's key is fixed once created — saved filters are stored as text and refer to fields by key, so renaming one would silently change what they match. This key can only be changed while a built-in search name has taken it.` |
| 4 | the **new** key is itself registry-claimed | **409** | the existing `requireUnreservedKey` message, verbatim — `'<key>' is a reserved search field name — pick a different key (a custom field with this key would not be searchable)` |
| 5 | the new key already exists or is inherited at this scope (`existsVisibleToAndKey`) | **409** | the existing create message, verbatim |
| 6 | a concurrent writer took the key first (`field_defs_scope_key_key`) | **409** | §7.3 |

Rule 3 is the guard the owner asked to be specified. **Chosen: permit the rename only while the
field is shadowed** — not "allow always with a warning that says what will break". Reasons, in order
of weight:

1. **The warning cannot be written honestly.** "What will break" is the set of saved filters whose
   text names the key. Computing it means parsing every candidate filter (a substring match over HQL
   text is wrong — the key may appear inside a quoted literal). For a **global** field def the
   candidate set is *every saved filter on the instance*, in workspaces the admin cannot see and
   must not be shown. A refusal that quotes a number it cannot compute is worse than one that
   refuses.
2. **The shadowed population is the one where the answer is knowable without computing anything** —
   the invariant above makes it *zero*, for every field, always.
3. It adds no new destructive capability and no consent flag. `dropValues` exists because a delete
   destroys data the caller can see; a rename in this population destroys nothing.
4. It is reversible in the only direction that matters: rename again while still shadowed, or —
   after the field is un-shadowed — rename back is refused, which is correct, because by then the
   general immutability argument is true again.

**R9 — the write.** Reads first, mutations last (the `@Version`-jump / AUTO-flush rule in
`CLAUDE.md`), then `saveAndFlush` so a unique violation becomes a status code rather than a 500 at
commit — a `catch` around `save()` never fires. `FieldDef` carries no `@Version`, so name/config
updates are last-write-wins today and stay so; the rename's only real race is the unique constraint,
handled in §7.3.

**R10 — what a rename does and does not move.**

| Moves with the rename | Does not move |
|---|---|
| the HQL name of the field (`team_labels = "x"` now reaches it) | `issue_field_values` rows — keyed by `field_id` |
| the `/schema` entry (it leaves `shadowedFields`, enters `fields`) | field-set membership — keyed by `field_id` |
| the `CUSTOM:<key>` value-suggest token | stored option ids inside `config` |
| the `key` in `ProjectConfigResponse` and `AdminFieldResponse` | issue history rows (they record the display **name**) |
| — | **saved filter text** — never rewritten, by anyone |

**R11 — the tenant's exit is completed by hand.** After the rename, filters that said `labels` still
mean the built-in field. To reach their own field the tenant edits those filters to the new key.
This is stated in the admin console copy (§10) — a remedy the reader can perform.

## 7. Edge cases & failure modes

### 7.1 An archived shadowed field

`ResolutionContextFactory` skips archived defs, so an archived claimed-key field is **not shadowed**
(`shadowing` → empty): it is absent from `/schema`'s `shadowedFields`, absent from the startup WARN,
and shows no warning badge. But `AdminFieldResponse.shadowedBy` **is** populated
(`claimedBy`, archive-blind) and the **rename is permitted**, because the licensing invariant holds
even harder for an archived row: it is out of resolution entirely, so no filter can be pointing at
it. Renaming then unarchiving is the ordinary recovery path for a field somebody archived *because*
it had stopped working.

*This divergence between two similar-looking predicates is exactly the kind of thing that becomes a
bug, which is why §5 names both, and why the acceptance criteria pin both directions (AC-6, AC-13).*

### 7.2 A rename that collides with another key in the same scope

Refused **409** by rule 5 (`existsVisibleToAndKey`), which is deliberately **wider than the
scope**: it spans global ∪ ancestor workspace ∪ own project, the same check create uses. Note this
is a *stricter* condition than the DB constraint, which is per exact scope; the DB constraint
remains as the race backstop only.

**The widening is UPWARD only, and this paragraph originally claimed otherwise.** The predicate
matches global rows, `:ws` and `:proj`; at workspace scope `visibleProjectId()` is null, so a
project-scoped row *inside that workspace* matches no disjunct. So a workspace field renamed onto a
key one of its own projects already uses currently **succeeds** — the case the first draft of this
section used as its justification is the one case it does not cover. The consequence is that the
project then sees two live definitions under one key, and `ResolutionContextFactory.addCustomField`
is *first-wins* over visible-project iteration order, so a saved filter naming that key can begin
resolving to a different `field_defs` row with nothing to notice.

Left as it is here on purpose: the predicate is shared with `createField`, so narrowing it changes
what a create means, and a downward occupancy check belongs on both doors at once. Tracked as
**HD-284** and pinned as today's behaviour by a test in `ShadowedFieldKeyRenameTest`, which the
ticket should *replace* rather than delete.

### 7.3 Concurrency — two renames, or a create racing a rename

`existsVisibleToAndKey` is a check-then-act. Two admins renaming two fields onto the same key inside
one scope both pass the check and one loses at
`field_defs_scope_key_key UNIQUE NULLS NOT DISTINCT (…)`.

- `updateField` and `createField` both use **`saveAndFlush`**, and the service catches
  `DataIntegrityViolationException` around it, answering **409**:
  `That key or name was taken by another change — reload and try again.`
- **Only a duplicate key is translated.** `DataIntegrityViolationException` is Spring's translation
  for the whole integrity family, so the catch tests the SQLSTATE (`23505`, walked out of the cause
  chain — never the message text) and rethrows everything else. A `22001` truncation answers **400**
  through `GlobalExceptionHandler` as it did before, and a `23503` keeps its own status; telling
  either of those to "reload and try again" is advice its reader cannot act on, in a loop, with the
  diagnostic swallowed.
- One message for both constraints (`_key_key` and `_name_key`) on purpose: distinguishing them means
  string-matching a driver message, and the caller's action ("reload") is the same either way. The
  *non*-racing paths already give the precise message.
- **Seal it with a test that forces a real violation** (AC-11). A translation `catch` that is never
  entered looks identical to one that works.
- Rider S9: `createField` gets the same treatment in the same commit, because it has the identical
  latent 500 and shares the catch block.

### 7.4 A rename to a *different* registry name

`labels` → `components`. Refused **409** by rule 4, with the existing reserved-key message. The
tenant would otherwise walk out of one shadow into another, and — because the field would then be
shadowed again — the rename would still be permitted, so they could do it repeatedly without ever
escaping. Rule 4 runs before rule 5 so the message names the real problem.

### 7.5 Two fields in different scopes both keyed `labels`

Legal today and stays legal: `field_defs` uniqueness is per scope, so workspace A and workspace B may
each own one, and a global one may exist alongside both.

- Both are shadowed, independently. Each workspace sees **only its own** in `/schema` (built from
  that caller's `ResolutionContext` — no cross-tenant read).
- The startup scan names **both**, with their scope ids: it is an operator surface on the operator's
  own instance.
- Renaming one does not touch the other. If workspace A renames to `team_labels`, workspace B's
  `labels` is untouched and still shadowed.
- A **global** shadowed field is a single row that is shadowed for every workspace simultaneously.
  Only an instance admin can rename it, and doing so changes the key everywhere at once — treat it
  as a breaking change and put it in the release notes, on the same terms `RetiredFieldAliases`
  states for a global def under a retired key.

### 7.6 Rename onto a *retired alias* key (`fix_version`, `story_points`)

**Refused, 409, with a message of its own** — reversing this spec's original recommendation after
review (§12 Q4). The original argument was about *resolution*: retired aliases are consulted
*after* the caller's own custom fields (`FieldResolver`), so a tenant keying a field `story_points`
reaches its own field, which is the intended precedence and what the alias table exists to make
safe. That much is still true and unchanged.

What it missed is the effect on *everybody else's stored queries in that scope*. The tenant's field
winning is exactly the alias ceasing to fire: every saved filter written before V11 retired the key
silently stops matching the native `issues.story_points` column and starts matching a custom field
that did not exist a minute earlier. No error, no log line, nothing to notice. For a **global**
definition that is every workspace on the instance at once — which `RetiredFieldAliases` already
calls a breaking change.

The second reason is that the protection was a **data row, not a rule**. Both retired keys already
answered 409, but only because V1/V3 seeded global placeholders under them and the occupancy check
counts archived rows. Delete those rows and both keys open; retire a key that never had a
placeholder and it is open from the start, silently. The refusal is therefore stated in
`requireUnreservedKey`, which guards both doors that mint a key, and it is deliberately **not**
folded into `ShadowedFields.claimedBy`: that predicate grants the rename *permission*, and a
retired key reporting as claimed would make a field keyed `story_points` renameable, which is a
different rule with a different licence.

Nothing here is retroactive: a row that already carries a retired key is untouched, still resolves
to itself, and every other route into `field_defs` (a migration, a seeder, direct SQL) reaches none
of this.

### 7.7 A shadowed field the caller cannot see

`/schema` iterates the caller's `ResolutionContext`. A shadowed field in a project the caller cannot
see is not in that context and is not reported — same rule as everywhere else: a field the caller
cannot see is indistinguishable from one that does not exist, and its existence is never leaked.

### 7.8 Startup scan failures

- **The scan must never fail the boot.** It catches `Exception`, logs one WARN
  (`shadowed-field-def: scan could not run`) and returns. A tenant's legal data must not stop an
  instance starting, and the shadowing is our doing, not theirs.
- On `ApplicationReadyEvent`, not `@PostConstruct`: it reads the database, so it must run after
  Flyway and after the `EntityManagerFactory` is up. Same reason `FailedEmailWriter`'s startup check
  is on the ready event.
- Idempotent by nature — it only reads. Re-running on a restart re-emits the same lines, which is
  wanted: an operator who ignored it once sees it again.
- **A clean instance logs nothing at WARN** (one DEBUG line instead). Otherwise every instance warns
  about V3's own archived seeds and the signal is dead on arrival (§2).

### 7.9 Idempotency of a rename

`PATCH` with the same `key` twice: the second call is a no-op by R7 (key equal → not a rename), not a
409. A client retrying a timed-out rename gets 200, not a spurious conflict.

## 8. Startup scan — behaviour

**New: `com.hamstrack.search.ShadowedFieldStartupScan`** (`@Component`,
`@EventListener(ApplicationReadyEvent.class)`, `@Transactional(readOnly = true)`).

- Watch set: `FieldRegistry.claimedKeys()` — a new accessor returning `Set.copyOf(byName.keySet())`.
  **Derived from the registry, never a hand-written list**, so a name added tomorrow is watched
  without a second edit. This is the half of §9 that is automatic.
- One query, one small table:
  ```
  @Query("select f from FieldDef f where f.archivedAt is null and lower(f.key) in :keys")
  List<FieldDef> findAllLiveByKeyIn(@Param("keys") Collection<String> keys);
  ```
- One WARN per collision, prefixed with a stable, greppable token so log-based alerting can key on
  it without parsing prose:
  ```
  shadowed-field-def: custom field '<name>' (key '<key>', id <uuid>, scope <global|workspace <uuid>|project <uuid>>)
  is shadowed by the built-in search field '<canonical>'. HQL `<key> = …` answers from the built-in field, not from
  this one, and it is not offered in /search/schema. A taxonomy admin at that scope can rename its key
  (PATCH …/fields/<uuid>); see docs/self-hosting.md#shadowed-custom-field-keys-from-0180.
  ```
  plus one summary line `shadowed-field-def: <n> custom field definition(s) are shadowed by built-in search names`.
- It emits ids, keys, names and scope ids — **never issue data, never field values**.

**What a self-hoster actually sees, and where.** This matters more than the mechanism:

| Audience | Surface | When |
|---|---|---|
| DC operator | the WARN lines in their own application log | first boot after the upgrade that registered the name, and every boot after |
| Workspace/project admin | the warning on the field row in the admin console | whenever they open Settings → Fields |
| Any member writing a query | the notice on the search page from `/schema` | whenever they use search |
| **Us, before the release ships** | the failing build in §9 | the moment a registry name is added |

Only the last one prevents the collision; the first three end the silence about one that already
exists. Stating that split is the point — a build guard is invisible to a self-hoster and a WARN is
invisible to us.

## 9. The durability guarantee (the third acceptance criterion)

> A **new** registry name cannot shadow an existing tenant field without something failing.

This is a claim about a **category** — any future registry addition — not about `labels` and
`components`. Today's guard is create-only, so the next vocabulary addition repeats this by
construction. Three parts, and each has a named audience.

**9.1 Automatic and unloseable — the watch set is derived.** The startup scan and `/schema` both read
`FieldRegistry` itself (`claimedKeys()` / `claimedBy()`). A name added tomorrow is scanned, reported
and warned about **with no second edit**, so the reporting half cannot go partially adopted. There is
no list to forget to update. That is the reason it is written this way rather than as a constant.

**9.2 The build fails — `RegisteredSearchNameLedgerTest`** (plain unit test, no Spring, no DB;
mirrors `RetiredFieldSweepTest`'s shape). It holds `RECORDED`, the set of every key `FieldRegistry`
registers (canonical names **and** aliases), and asserts it equals `FieldRegistry.claimedKeys()`.
Adding or removing a name fails the build until the author edits `RECORDED` — which makes the
addition a deliberate act rather than an additive-looking one. **The failure message is the
propagation checklist**, not a diff:

```
FieldRegistry now claims a name this ledger does not record: <name>.

A registry name is RESERVED against every tenant's custom field of that key, retroactively and
forever (FieldResolver). Adding one is a release-note-worthy event, not an additive change.

Before you add <name> to RECORDED below:
  1. Run the collision query in docs/release-checklist.md -> "Releases that register a new HQL
     field name", against production AND against any instance you support. Record the answer in
     the release notes even when it is zero.
  2. If it finds rows: those tenants' fields become unsearchable on upgrade. They keep working
     everywhere else. The remedy they have is a key rename (HD-275) - say so in the release notes.
  3. Nothing else is needed: the startup scan and /search/schema derive their watch set from
     FieldRegistry, so <name> is reported automatically.
Removing a name is the mirror-image event: see RetiredFieldAliases before you do it.
```

Steps 1–2 are the human half; step 3 exists to stop a future author "wiring up" something that is
already a property.

**9.3 The guard is watched failing, not believed.** Project lore: a guard nobody has watched fail is
a belief. Both guards are exercised by tests that make them fail on purpose:

- **The ledger's comparison** is a pure function over two sets, so it is called directly with a
  synthetic set carrying an extra name and with one missing a name; both must throw, and the message
  of the extra-name case must contain the checklist token (`docs/release-checklist.md`).
  `RetiredFieldSweepTest` already uses `assertThatThrownBy` on its own detector — same shape.
- **The scan** is exercised against a real collision (AC-9): an integration test inserts a
  workspace-scoped `field_defs` row keyed with a live registry name **through the repository,
  bypassing `AdminFieldService`** — which is legitimate, because bypassing the service is precisely
  how this population arose (the create guard is create-only and did not exist when those rows were
  written) — then runs the scan and asserts the WARN names the row. A second case asserts a clean
  fixture emits no WARN at all (AC-10), which is the assertion that would have caught "warn about
  V3's own archived seeds".

**9.4 What is NOT claimed.** Nothing here prevents a *migration*, a seeder, or direct SQL from
inserting a row under a claimed key — `AdminFieldService`'s reach was always "fields created through
the admin service and nothing else", as `docs/release-checklist.md` says. What changes is that such
a row is now **named at every boot** instead of being silent forever.

## 10. Frontend impact

`DESIGN.md` compliance: warnings use `var(--color-warning)` / `var(--color-warning-ink)` (already in
`index.css`); no hardcoded hex, no new token, no new component family — reuse `components/ui.tsx`
and `pages/admin/common.tsx` primitives (`ImpactBanner` is the closest existing shape for the admin
banner).

**`src/main/frontend/src/types.ts`**

```ts
export interface AdminField {
  // …
  /** The built-in search name that has taken this field's key, or null. Populated
   *  whether or not the field is archived — the rename affordance needs it either way. */
  shadowedBy?: string | null
}

/** A custom field the caller owns whose key a built-in search name has taken. It is
 *  deliberately NOT in `fields`: writing this key in HQL answers from the built-in
 *  field, so offering it in autocomplete would suggest a name that lies. */
export interface ShadowedSearchField { key: string; name: string; shadowedBy: string }

export interface SearchSchema {
  // …
  /** Optional so an older server is readable. Absent means "nothing to warn about",
   *  never "hide the search box" — same rule as `insights?`. */
  shadowedFields?: ShadowedSearchField[]
}
```

**`pages/admin/AdminFieldsPage.tsx`**

- *Row:* when `f.shadowedBy && !f.archived`, a warning chip beside the mono key, tooltip/inline text:
  `Not searchable — “<key>” is a built-in search name.`
- *Edit dialog (`FieldForm`):* the current static line
  `{field.key} · {TYPE} — key and type are fixed once created` becomes conditional.
  - `field.shadowedBy && field.scope === ownTag && !field.isSystem` → render the **Key** `Input`
    (same control the create form uses, `placeholder={field.key}`) above a static type line, with
    helper copy:
    > `“<key>” is a built-in search name, so searching for it answers from the built-in “<shadowedBy>” field and not from this one. Renaming the key here makes this field searchable again under the new name. Issue values are not affected. Saved filters are not rewritten — anyone using the old key will need to update their filter to the new one.`
  - otherwise → the existing static line, unchanged.
- *Payload:* send `key` **only when it differs** from `field.key`. The current form already sends the
  unchanged key on every update and the server treats that as a no-op (R7), but the client should be
  explicit so the intent is readable.
- *Errors:* the dialog already surfaces `error` from the mutation; the 422/409 `detail` strings in
  §6.2 render as-is (`spring.mvc.problemdetails.enabled=true`).

**`pages/SearchResultsPage.tsx`** (and the `HqlInput` consumers that hold a schema)

- When `schema.shadowedFields?.length`, render a non-blocking warning notice above the input:
  > `“<name>” is a custom field in this workspace, but “<key>” is a built-in search name — searching it returns built-in <shadowedBy> data, not this field's values. A workspace admin can rename the field's key in Settings → Fields.`
- One line per shadowed field, collapsed to a count past three. Never a modal, never blocking, never
  a reason to hide the search box.
- Autocomplete is unchanged: shadowed keys are not in `fields`, so nothing new is offered.

**Tests (vitest, run by `npm-test` since HD-242):** the admin row/dialog conditionals (four states:
shadowed+own, shadowed+inherited, shadowed+archived, not shadowed), the payload's
send-only-if-changed rule, and the search notice's absent/empty/populated rendering.

## 11. DC / Cloud implications

**No profile gating, no new environment variable, no new property — deliberately, and this is a
decision rather than an omission.**

- The shadowing is a property of the *product's vocabulary* meeting *tenant data*. It exists
  identically in both modes and must be reported identically in both, or a self-hoster gets a
  quieter product than a Cloud tenant for the same defect.
- A toggle would be worse than useless: an operator who switched the warning off would be switching
  off the only notice a shadowed tenant ever gets. There is no deployment in which silence is the
  right default, so there is nothing to configure.
- The startup scan is one indexed-table read at boot in both modes. No storage, mail, auth or
  billing surface is touched, so no self-hosted path is missing.
- **Cloud alerting** keys on the `shadowed-field-def:` log prefix in Loki (the stack is deployed and
  log-based alerting is what it is for).
- **Rejected: a Micrometer gauge** (`hamstrack_shadowed_field_defs`). A gauge set once at boot goes
  stale the moment an admin renames out of the collision and stays wrong until the next restart —
  a metric that lies about a fix is worse than no metric. Re-querying on scrape puts a database read
  on the scrape path. The log line is honest about being a point-in-time observation.
- **Docs wiring** (`dc-cloud-guard`'s checklist reduces to documentation here, since no property is
  added): `docs/self-hosting.md` gains a `## Shadowed custom field keys` section under `Upgrading`,
  with a `## Contents` entry, the detection SQL, what the WARN means, and the rename remedy — and
  every pointer to it must be clicked, per the release-checklist's own verification rule.
  `docs/release-checklist.md` → "Releases that register a new HQL field name" gains the ledger test
  and the rename remedy, replacing the sentence *"a field's key is immutable, so the honest remedy
  for an affected tenant is a new field under a different key"*, which this change makes false.

## 12. Data model impact

**No migration. Nothing is added to, or removed from, `field_defs`.** The highest live version is
`V27`; this ships no `V28`.

| Artifact | Change |
|---|---|
| `FieldDef.key` | drop `updatable = false` from `@Column(nullable = false, length = 50, updatable = false)`. Length and nullability unchanged; entity↔schema parity therefore unchanged (and `ddl-auto=validate` would not have caught a width drift anyway — `CLAUDE.md`). |
| `FieldDef` javadoc | *"`key` is the immutable machine name"* becomes the accurate rule: the key is fixed once created **except while a built-in search name has taken it**, and identity is the row's UUID — `issue_field_values`, `field_set_items` and every FK reference the id, never the key. |
| `FieldDefRepository` | one new read: `findAllLiveByKeyIn(Collection<String>)` (§8). |
| `field_defs_scope_key_key` | unchanged, and now load-bearing as the race backstop (§7.3). |
| `V1__init_schema.sql` line 287 | carries the inline comment `-- immutable machine name (snake_case)`, which becomes stale. **It is not edited** — never edit an applied migration. The truth lives on the entity javadoc, in `docs/api-*.md` and here; a `COMMENT ON COLUMN` migration was considered and rejected as ceremony that would not fix the `--` comment anyway. |

`FieldDef` extends `CreatedOnlyEntity`, so there is no `@Version` and no `updated_at`: a rename is not
optimistically locked and does not stamp a timestamp. That is the status quo for every other
`field_defs` edit; §7.3 is the only concurrency control this change needs.

## 13. API surface

`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` must follow (`api-docs-sync`).

### 13.1 `GET /api/workspaces/{workspaceId}/search/schema`

Unchanged path, unchanged auth, unchanged statuses (200; **404** for unknown workspace or
non-member). `SearchSchemaResponse` gains one field:

```jsonc
{
  "fields":   [ /* unchanged — every entry is queryable and means what it says */ ],
  "keywords": [ /* unchanged */ ],
  "values":   { /* unchanged */ },
  "insights": { /* unchanged */ },
  "shadowedFields": [
    { "key": "labels", "name": "Team labels", "shadowedBy": "label" }
  ]
}
```

`SearchSchemaResponse.ShadowedField(String key, String name, String shadowedBy)` —
`key` is the custom field's key, `name` its display name, `shadowedBy` the **canonical** registry
name claiming the key (so a key of `labels` reports `label`; the SPA copy in §10 phrases that for a
human). Sorted by `key`. `[]` when there are none. **No `reason` discriminator**: the list's name is
the reason, and a second reason later gets its own list rather than a column that every client must
switch on.

### 13.2 `PATCH /api/admin/fields/{id}` · `PATCH /api/workspaces/{ws}/admin/fields/{id}` · `PATCH /api/workspaces/{ws}/projects/{p}/admin/fields/{id}`

Request DTO `UpsertFieldRequest` is **unchanged in shape**; its `key` member stops being ignored on
update. Its javadoc changes from *"immutable afterwards, like the type"* to the §6.2 rule.

| Status | When |
|---|---|
| **200** | updated (with or without a rename); body is `AdminFieldResponse` |
| **400** | `key` fails `@Pattern("[a-z0-9_]*")` / `@Size(max = 50)`, or the body is unreadable — unchanged bean validation |
| **403** | a proven workspace member lacking `WORKSPACE_TAXONOMY_MANAGE` / `PROJECT_TAXONOMY_MANAGE` |
| **404** | field not resolvable at this scope; or unknown workspace/project; or caller is not a member |
| **409** | system field renamed · new key is registry-claimed · new key exists or is inherited at this scope · name collision · a lost race on either unique constraint |
| **422** | rename attempted on a field that is **not** shadowed · type change (existing) · config/option refusals (existing) |

### 13.3 `AdminFieldResponse` (returned by every admin field surface, and nested in `AdminFieldSetResponse.Item`)

```jsonc
{
  "id": "…", "key": "labels", "name": "Team labels", "type": "MULTI_SELECT",
  "config": { }, "description": null, "archived": false, "isSystem": false,
  "usage": { }, "scope": "workspace",
  "shadowedBy": "label"     // NEW — null when the key is not claimed
}
```

Factory becomes `AdminFieldResponse.of(FieldDef f, UsageInfo usage, String shadowedBy)`; every call
site passes `shadowedFields.claimedBy(f.getKey()).orElse(null)` — including `toSetResponse`, which
passes `null` usage today and must not start passing `null` here.

### 13.4 Nothing else moves

`/search`, `/search/suggest`, `/search/insights`, `ProjectConfigResponse`, the issue endpoints and
every saved-filter endpoint are untouched. **No capability, no toggle and no shadowing state changes
a status code** anywhere: a shadowed field's values are still written, read and rendered exactly as
before.

## 14. Acceptance criteria

Backend, unless noted. Each is checkable by a reviewer or by `test-runner`.

**Visibility (option 5)**

- **AC-1** With a live workspace-scoped `field_def` keyed `labels` in workspace W, `GET …/search/schema` for a member of W returns that field in `shadowedFields` as `{key:"labels", name:<its name>, shadowedBy:"label"}`, and **not** in `fields`.
- **AC-2** The same call for a member of a *different* workspace returns `shadowedFields: []`. No cross-tenant leak, and no extra repository query is issued to build the list.
- **AC-3** A workspace with no shadowed field returns `shadowedFields: []`; the response is otherwise byte-identical to today's.
- **AC-4** `GET …/admin/fields` (all three consoles) returns `shadowedBy: "label"` for that field and `shadowedBy: null` for an ordinary one.
- **AC-5** `shadowedFields` is sorted by key and stable across two consecutive identical requests.
- **AC-6** An **archived** field keyed `labels` is absent from `/schema`'s `shadowedFields` **and** absent from the startup WARN, while its `AdminFieldResponse.shadowedBy` is `"label"`.
- **AC-7** A field registered but **not yet available** (`FieldDescriptor.available() == false`) claims its key exactly like an available one: a custom field under that key is reported as shadowed.

**Startup scan**

- **AC-8** On a database whose only claimed-key rows are V3's archived system placeholders (i.e. every clean instance), the scan logs **nothing at WARN**.
- **AC-9** With a live workspace-scoped `field_def` keyed `components` inserted through the repository, the scan logs one WARN containing `shadowed-field-def:`, the field's id, its key, `component`, and the workspace id — plus the summary line.
- **AC-10** A scan whose query throws logs one WARN and the application still reaches a ready state.

**Rename (option 3)**

- **AC-11** `PATCH …/fields/{id}` with `key:"team_labels"` on a shadowed, non-system field owned by the caller's scope → **200**; the row's key changes; `issue_field_values` row count for that field is unchanged; the field's values still render on the issues that carry them.
- **AC-12** After AC-11, `/schema` lists `team_labels` in `fields` and no longer in `shadowedFields`; `team_labels IS NOT EMPTY` compiles and returns the issues carrying values; `labels = "x"` still answers from the built-in label field.
- **AC-13** `PATCH` with a new key on a field whose key is **not** claimed → **422**, `detail` naming saved-filter text as the reason.
- **AC-14** `PATCH` with `key` equal to the current key (any casing) → **200**, no rename, no refusal. Also true for `key` absent, `null` and `""`.
- **AC-15** `PATCH` renaming `labels` → `components` → **409** with the reserved-search-name message.
- **AC-16** `PATCH` renaming onto a key that already exists **at the same scope** → **409**; onto one that exists **globally** (inherited) → **409** as well.
- **AC-17** `PATCH` renaming a system field (`isSystem = true`) → **409**, even when its key is claimed.
- **AC-18** A workspace admin cannot rename a **global** shadowed field → **404** (not 403 — the row is not at their scope).
- **AC-19** A non-member of the workspace → **404** on all three of list/patch. A proven member without the taxonomy permission → **403**.
- **AC-20** A forced unique-constraint violation on `field_defs_scope_key_key` during a rename produces **409**, not 500 — asserted by provoking a real violation, not by inspecting the code.
- **AC-21** Renaming an **archived** shadowed field → **200**; unarchiving it afterwards makes it appear in `fields`.
- **AC-22** *(revised — see §7.6)* Renaming onto a retired alias key (`story_points`, `fix_version`) → **409** carrying the retirement message, not the reserved-name message and not the occupancy one. The original AC read **200**; it was reversed in review, and the test asserting it was rewritten rather than deleted so the change of mind stays visible.

**Durability (the third criterion)**

- **AC-23** Adding a name to `FieldRegistry` without updating `RECORDED` fails `RegisteredSearchNameLedgerTest`, and the failure message contains the checklist including the `docs/release-checklist.md` pointer.
- **AC-24** Removing a name from `FieldRegistry` without updating `RECORDED` also fails.
- **AC-25** The ledger's comparison is exercised directly with a synthetic extra name and a synthetic missing name, and throws in both directions — the guard is watched failing, not assumed.
- **AC-26** The startup scan and `/schema` derive their watch set from `FieldRegistry` itself: a name added in a test double is reported without editing any list. (Pins §9.1 as a property.)
- **AC-27** The new test classes are named so Surefire's includes pick them up, and the `test-tree-coverage-guard` bound accounts for them.

**Frontend**

- **AC-28** The admin field row shows a warning chip for `shadowedBy != null && !archived` and none otherwise (four states asserted).
- **AC-29** The edit dialog renders the editable Key input only for `shadowedBy != null && scope === ownTag && !isSystem`; the static "key and type are fixed" line otherwise.
- **AC-30** The dialog sends `key` in the payload only when it differs from the current key.
- **AC-31** The search page renders the shadowed-field notice when `/schema` reports any, renders nothing when the list is empty, and renders nothing (no crash) when the field is absent from an older server's response.

**Docs**

- **AC-32** `openapi.yaml` validates with swagger-cli and both `docs/api-*.md` describe `shadowedFields`, `shadowedBy`, and the rename's 200/409/422 outcomes.
- **AC-33** `docs/release-checklist.md`'s "a field's key is immutable, so the honest remedy … is a new field under a different key" is replaced by the rename remedy, and `docs/self-hosting.md` gains the operator section with a working `## Contents` entry and clicked pointers.

## 15. Open questions

1. **Does a rename need an audit trail?** There is no taxonomy audit subsystem today, and building one is out of scope. **Recommended default:** log one INFO from `AdminFieldService` on a successful rename (actor id, field id, scope, old key, new key). It costs one line and is the only record that would exist; a table is a separate ticket.
2. **Should the admin console show the tenant which saved filters mention the old key?** **Recommended default: no.** §6.2 argues it cannot be computed honestly for a global field, and in the shadowed population the true count of *broken* filters is zero — showing a number would invite the reader to believe something breaks. The copy in §10 says the accurate thing instead ("anyone using the old key will need to update their filter").
3. **Should the rename also be offered from the project-scoped console for a field the project inherits from its workspace?** **Recommended default: no** — `findByIdAtScope` already refuses it (404), consistent with every other edit. The console shows `InheritedBadge`, and the warning copy should name *which* scope's admin can act (§4's "a remedy its recipient can perform"). Whether the copy can name that scope precisely without leaking a workspace id to a project admin is the genuinely open part; the safe phrasing is "a workspace administrator".
4. **Should a rename onto a retired alias key be refused?** ~~Recommended default: keep permitting it.~~ **Answered in review: refuse it, at both minting doors** (§7.6 rewritten). The recommendation was reasoned entirely about resolution — the tenant's own field wins, which the alias table is designed for — and said nothing about the saved filters that stop resolving to the native column the moment it does. It was also the one refusal in this area that existed only as seed data. This is the one place the spec allows a tenant to take a name the product also answers, which is why it was flagged; the answer is that it may not.
5. **Should the scan re-run periodically rather than at boot only?** **Recommended default: boot only.** A new collision can now only arrive by migration, seeder or direct SQL — migrations run before ready, and `/schema` is computed live per request, so the request-time surface is never stale.

## 16. Highest-risk assumption

**That a rename moves nothing but the key.** The whole of option 3 rests on it: values are keyed by
`field_id`, set membership is keyed by `field_id`, history records display names, and nothing resolves
a `field_def` by key at runtime. The verification done for this spec found exactly one runtime
by-key lookup — `DemoDataService` resolving the global `severity` and `environment` defs — which is
why §6.2 rule 2 refuses system fields outright. **If any code path added later resolves a
`field_defs` row by key, this feature silently breaks it**, and it will break the way this whole
ticket is about: no error, a wrong row or none, on a path nobody watches. AC-11 pins values and
rendering, but a test cannot pin a lookup that does not exist yet.

Second, smaller: the compatibility rule in R7 (rename triggered by *difference*, never *presence*).
If a builder implements "key present on update ⇒ rename attempt", **every existing edit of every
custom field starts answering 422**, because today's SPA sends the unchanged key on every update.
AC-14 exists solely to catch that.

## 17. Architectural decisions

One decision here is a hard-to-reverse fork a future contributor will ask "why?" about, because it
contradicts three places that currently say the opposite (the entity javadoc, the DTO javadoc, the
`V1` column comment):

> **ADR-0036 — A custom field's key is renameable exactly while a built-in search name shadows it.**
> Chosen: `field_defs.key` becomes mutable under a shadowing-only guard; the field's identity is its
> UUID, and the only thing that depends on the key's text is saved-filter HQL, which in the shadowed
> population resolves elsewhere both before and after.
> Rejected: (a) keep the key immutable and tell affected tenants to create a new field — loses every
> stored value and every field-set placement, for a defect the product caused; (b) allow the rename
> always, behind a "this will break N saved filters" warning — the count cannot be computed honestly
> for a global field def and spans workspaces the admin cannot see; (c) rewrite saved-filter text on
> rename — breaks the standing rule that no code ever edits a user's stored query.
> Trade-off: two of the three places that state "the key is immutable" become conditional statements,
> and immutability stops being a property a reader can rely on without checking the condition.

Drafted as `docs/adr/0036-field-key-renameable-while-shadowed.md`, `Status: Proposed` — the
orchestrator flips it to `Accepted` at finalize, once the decision has actually shipped.

The `/schema` change (report with a reason instead of omitting) is deliberately **not** an ADR: it
does not fork the design, it corrects a surface that was already documented as a known hazard in
`FieldResolver` and `docs/release-checklist.md`, and reverting it costs one DTO field.
