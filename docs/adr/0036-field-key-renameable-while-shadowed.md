# ADR-0036: A custom field's key may be renamed exactly when a built-in search name has shadowed it

Record date: 2026-09-06
Status: Accepted (implemented in HD-275, shipped in 0.18.0)
Source: `docs/design/shadowed-field-key-proposal.md` §6.2, §12, §16 (HD-275);
the code as of this record — `com.hamstrack.issue.entity.FieldDef` (`@Column(updatable = false)` on `key`),
`AdminFieldService.updateField` ("Type and key are immutable — stored values depend on both"),
`AdminFieldService.requireUnreservedKey` (checked only on creation, not retroactive),
`FieldResolver` (the registry wins over a custom field), `V1__init_schema.sql`
(`key VARCHAR(50) NOT NULL, -- immutable machine name (snake_case)` and
`field_defs_scope_key_key UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, key)`)

## Context

A name registered in `FieldRegistry` is a reserved system name: `FieldResolver` asks the registry
**before** the caller's custom fields. The `requireUnreservedKey` check will not let a new field be
created under a taken key, but it is **only on creation and not retroactive** — its javadoc says so
outright. The registry gained `labels` in slice V8 and `components` in V9; `field_defs` uniqueness is
within a scope, so a workspace or project row with the key `labels` created **before** those slices is
alive and has been shadowed ever since.

The failure meanwhile takes the worst of the available forms. The data is neither lost nor
unreachable: the field is still drawn on issues, sits in field sets and arrives in
`ProjectConfigResponse`. Only the query surface breaks, and silently in both directions: `labels = "…"`
answers **200** with rows from the built-in labels, which the tenant never set on that field, while
`/search/schema` drops such a key **with no reason given**. The tenant is able to notice only an
*absence*, while every query answers confidently.

There is no way out for them: `FieldDef.key` is marked `@Column(updatable = false)`, and `updateField`
ignores `req.key()`. The only way named in `docs/release-checklist.md` is "create a new field under a
different key", that is, lose every value and every placement in field sets because of a defect that
the product created, not the tenant.

Diagnostics on production 2026-09-06: shadowed fields **zero**, and moreover — **zero** definitions
scoped to a workspace or a project at all (all twelve are global). The fork is decided for
self-hosted installations and for future name registrations, not for a particular victim.

The claim "type and key are immutable — stored values depend on both" is true **of the type and false
of the key**: values live in `issue_field_values` keyed by `field_id` (UUID), `field_set_items` also
references the id, and `FieldValueService` does not read the key at all. A rename moves not a single
row. The real dependency is **the text of saved filters**: HQL is stored verbatim and resolved on
read, and nobody ever rewrites it.

## Decision

**`field_defs.key` becomes mutable exactly when the key name is taken by `FieldRegistry`.**
A field's identity is the row's UUID, not the key text.

- Permission is checked by the predicate "the key is taken by the registry" (`ShadowedFields.claimedBy`),
  which **does not look at archived-ness and does not look at `available()`**: registration is itself
  the claiming of the name.
- The refusal when a field is **not** shadowed is **422** with an explanation about the text of saved
  filters. The refusal on a system field is **409** (`DemoDataService` resolves the global
  `severity`/`environment` **by key**, and all V3 placeholders are system ones).
- The rename target passes the same checks as creation: a name taken by the registry — **409**,
  a key taken or inherited in that scope — **409**, a race on
  `field_defs_scope_key_key` — **409** via `saveAndFlush`.
- Once a field has stopped being shadowed, its key is immutable again: the general argument
  (filters are stored as text) is true once more.

The invariant that licenses the whole operation:

> **A rename is allowed exactly in the population where it cannot change the meaning of any
> saved filter.** While `labels` is taken by the registry, the filter `labels = "x"` resolves to the
> built-in `label` field — both before the rename and after, because the name stays with the registry.
> The tenant's own field was unreachable from HQL before the operation and stays unreachable under the old
> key after it. The meaning of no stored query changes.

## Consequences

+ The affected tenant gains a way out that costs them not a single value and not a single
  placement in a field set: after the rename the field is searchable again — under its new name.
+ The remedy named by the refusals and by the release checklist becomes performable by its recipient:
  the taxonomy admin of their own scope makes one `PATCH`. Previously the only answer was "create a
  new field", that is, "lose your data".
+ Permission is expressed as a predicate, not as a list of keys, so it automatically covers any
  name the registry takes in the future.
− Three places that stated "the key is immutable" become conditional: the `FieldDef` javadoc, the
  `UpsertFieldRequest`/`updateField` javadoc and the line comment in `V1__init_schema.sql`. The first two
  are rewritten, the third **stays wrong forever** — an applied migration is not edited.
− Immutability stops being a property a reader can lean on without checking the condition.
  That is exactly why this ADR is recorded.
− A dependency appears on no path resolving `field_defs` by key at runtime.
  As of this record there is exactly one such path (`DemoDataService`, global system fields), and it is
  closed by the 409 refusal on system fields. Any new resolution by key will break silently — this is
  named the spec's main risk (§16).
− `PATCH` stops being purely "an update of form fields": it now carries an operation with a permission
  of its own. Compatibility rests on the rule "the trigger is a *difference* in the key, never its
  *presence*": today's SPA sends the unchanged key on every save, and reading "a key was sent ⇒ a
  rename" would answer 422 on any edit of any field.

## Alternatives

- **Leave the key immutable; the affected party creates a new field under a different key** (what is
  written today in `docs/release-checklist.md`) — rejected: every `issue_field_values` value and
  every placement in field sets is lost, and for a defect the product introduced by registering the name.
- **Always allow the rename, behind a warning "this will break N saved filters"** —
  rejected, and for a reason stronger than taste: the number `N` cannot be counted honestly.
  A substring search over the HQL text is wrong (the key can sit inside a string literal), and an honest
  parse for a **global** field definition means parsing *all* of the instance's saved filters —
  in workspaces the admin does not see and must not see. A refusal naming a number it cannot
  compute is worse than a refusal.
- **Rewrite the text of saved filters on rename** — rejected: it breaks the standing
  rule that neither a migration nor a service ever edits someone else's stored query
  (delivery-paths §9.2, `RetiredFieldAliases`).
- **Remove the plurals from the registry** (`labels`/`components`) — rejected in the spec §3.4: it fixes
  one tenant and breaks the vocabulary for everyone else, and for the owner of a field with such a key it is
  a silent change of **meaning**, not of priority — the same harm inside out.
- **Priority by presence: the custom field wins where it exists** — rejected: one name would come to
  mean different things in different workspaces, and the affected tenant's already-saved filters would
  silently change meaning.
- **Qualified access `cf.labels`** — the right long-term answer and not this fork: new parser
  syntax, a change to the `/schema` vocabulary and a compatibility story of its own. Deferred, and nothing
  here forecloses it.
