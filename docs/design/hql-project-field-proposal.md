# HQL `project` field — proposal (HD-101)

**Status:** written 2026-08-20, *after* implementation started on `feat/release-0.16.0`. It is therefore
two documents in one: the spec that should have preceded the build, and a **design review of the
decisions already handed to the builder**. Sections marked **REVIEW** disagree with a pre-committed
decision and say what to do instead. Nothing here edits code.

**Related, deliberately out of scope:** HD-119 (`resolved` / `closed`). §14 names the machinery the two
share and stops there.

---

## 0. Verification log — what was read, not assumed

Every claim below is anchored to code read on 2026-08-20 at `main` (`30a2afd`). The in-flight branch was
not read; if it has already diverged, treat the citations as the baseline the change starts from.

| Claim | Verified at |
|---|---|
| `FieldRegistry` has no `project` entry — the reported failure is a missing registration, not a parser defect | `src/main/java/com/hamstrack/search/FieldRegistry.java:46-146` |
| Search visibility = every non-archived project of the workspace, actor-independent | `SearchScope.java:52-56` |
| The scope conjunction is always outermost; the parsed tree is nested inside it | `HqlCompiler.java:170-176`, `SearchScope.java:69-79` |
| There is no `project.view` / `issue.view` permission; read visibility is not permission-gated | `common/security/Permission.java:29-40` (§3 non-goals, in the enum's own javadoc) |
| `ProjectService.list` returns the same project set to any workspace member | `ProjectService.java:119-124` |
| `projects.key` is unique per workspace, uppercase `[A-Z0-9]{1,10}`, normalised on write | `V1__init_schema.sql:145-161`, `CreateProjectRequest.java:30`, `ProjectService.java:91-94` |
| `projects.name` has **no** uniqueness constraint and no uniqueness check on create | `V1__init_schema.sql:145-161`, `ProjectService.java:84-104` |
| `issues.project_id` is `NOT NULL` | `V1__init_schema.sql:348-372` |
| The grammar has no `field NOT IN (…)` production | `HqlParser.java:159-181`, `advanced-search-hql-proposal.md:144-147` |
| `ResolutionContextFactory` already hydrates the visible `Project` entities | `ResolutionContextFactory.java:68-70` |
| The `*IdsByName` maps are keyed by `SearchNames.key`, whose empty bucket must be refused by every reader | `SearchNames.java:33-40`, guard at `HqlValueResolver.java:275-282` |
| A registry-claimed key is silently dropped from `/schema`'s custom-field list | `SearchService.java:176` |
| A custom field named "Project" auto-slugs to key `project`; nothing rejects a registry-claimed key on create | `AdminFieldService.java:52-56`, `:250-253` |
| No seeded global `field_def` uses the key `project` | `V1__init_schema.sql:577-592`, `V3__system_fields.sql:26-63` |
| The SPA's autocomplete already inserts `value ?? label` and displays `label` | `src/main/frontend/src/components/HqlInput.tsx:193-196` |

**Could not determine:** whether any *real* workspace (prod or otherwise) already owns a custom field
keyed `project`. `Bash` is disabled in this session, so neither the local dev database nor prod was
queried. §6.4 gives the exact SQL to run before release and treats the answer as unknown until it is run.

---

## 1. Problem & goal

`project = "HD" AND fixVersion = "0.13.0"` fails with `Unknown field 'project'`. HQL search spans every
non-archived project of the workspace and offers no way to narrow to one, so the language cannot express
the most ordinary question a multi-project workspace asks. The absence has a second cost: because
`component`, `fixVersion`, `affectsVersion` and `sprint` resolve their names across *all* visible
projects (`ResolutionContextFactory.java:138-169`), a name two projects share matches both, and there is
nothing in the language that can tell them apart — those fields are not merely broad, they are
**ambiguous with no disambiguator**.

**Goal:** register `project` as a first-class queryable field that narrows a search to one or more named
projects, and in doing so give every project-owned catalog field a companion term that resolves its
ambiguity. Success = `project = "HD" AND fixVersion = "0.13.0"` answers exactly the issues in HD that
ship in HD's 0.13.0, and a saved filter can carry that sentence.

---

## 2. Scope

**In scope**

- A `project` entry in `FieldRegistry`, resolvable by **project key** (§4.1), with `=`, `!=`, `IN`.
- Rejection of `IS [NOT] EMPTY` (every issue has a project).
- A `PROJECT` value picklist on `GET …/search/schema` and a `field=project` branch on
  `GET …/search/suggest`, both drawn from state the request already loads.
- Field-anchored 422 for an operand that names no searchable project — never 404, never a silent 200
  with zero rows caused by an unresolved name.
- Saved filters carrying the field (this is free — see §5.6).

**Out of scope / non-goals**

- A project selector control in the search UI. `src/main/frontend` is a later slice; §9 states only what
  the backend must hand it and what already works unchanged.
- Any change to `SearchScope`. The project term is an ordinary parsed predicate and must never be folded
  into the scope (§4.3).
- `includeArchivedProjects` (still the future flag `advanced-search-hql-proposal.md:528` reserves).
- `resolved` / `closed` (HD-119).
- Retro-fixing existing tenant custom fields that collide with the newly reserved key (§6.4 recommends
  what *is* in scope: detection + a forward-looking guard).

---

## 3. Actors, permissions, tenancy

| Who | What | Gate |
|---|---|---|
| Any workspace member | Uses `project` in a query, in `/schema`, in `/suggest`, in a saved filter | `WorkspaceAccessService.requireMember` → **404** (not 403) for a non-member or a missing workspace, exactly as today (`SearchService.java:430-432`) |

No new permission, and none should be added. Search read visibility is not permission-gated today
(`Permission.java:29-40` says so as a deliberate non-goal, and `ProjectService.list` proves the behaviour),
so a `project` clause can only ever **narrow** a set the caller could already read in full. The field
grants no reach.

**Tenancy invariants this change must not weaken**

1. The project name/key lookup map is built **from `SearchScope.visibleProjectIds` and nothing else** —
   never from `findAllByWorkspace`. `ResolutionContextFactory.java:63-70` already states this rule for
   the catalogs and derives its entity fetch from the visible id set; the project map inherits it.
2. The compiled term restricts the same `Root<Issue>` the scope predicate restricts, ANDed, never
   negated as a whole and never ORed with the scope — obligations 1 and 2 of
   `HqlCompiler.scopedPredicate`'s javadoc (`HqlCompiler.java:133-153`).
3. A project the caller cannot search is **indistinguishable from a typo**: the same 422 the resolver
   gives any unknown name, with no hint that betrays existence.

---

## 4. Decisions

### 4.1 Key vs name — **REVIEW: resolve by key only; carry the name in the suggestion label**

**Pre-committed decision:** "resolution by project key and by name, case-insensitive."
**Recommendation: drop name resolution.** Resolve `project` operands against `projects.key` only,
case-insensitively.

The reasoning is not aesthetic:

- **A key is an identity; a name is not.** `UNIQUE(workspace_id, key)` is enforced in the schema and
  re-checked in the service (`V1__init_schema.sql:160`, `ProjectService.java:92-94`). There is **no**
  uniqueness constraint on `projects.name` and no check on create — two projects in one workspace may
  legally be called "Platform" today. So name resolution is ambiguous *even without* a key/name
  collision: `project = "Platform"` would resolve to an id **set**, and `project` would join the very
  category of ambiguous project-owned fields it was added to disambiguate. That defeats the second half
  of the ticket's own motivation.
- **The cross-collision the question asks about is worse than it looks, and is not exotic.** Keys are
  `[A-Z0-9]{1,10}` and are conventionally the acronym of a name; names of 2–10 uppercase characters are
  common ("OPS", "CRM", "HD"). A workspace with a project *named* `OPS` and another *keyed* `OPS` is an
  ordinary accident, not a contrived one — and under key+name matching one operand silently means two
  projects.
- **The discoverability argument for names is already satisfied without them.**
  `SearchSchemaResponse.ValueOption` is a `(label, value)` pair, and the SPA inserts `o.value ?? o.label`
  while *displaying* `o.label` (`HqlInput.tsx:193-196`), filtering the popup on either string
  (`HqlInput.tsx:214-216`). So publishing `ValueOption("Hamstrack (HD)", "HD")` lets a user type
  "Hams…", see the human name, and insert the unambiguous key — **with no frontend change and no
  ambiguity in the language**. The benefit of name matching is obtainable at the suggestion layer, where
  being wrong costs a redundant popup entry rather than a wrong result set.
- **A miss can teach the key without matching on it.** When an operand matches no key, compare it
  case-insensitively against the *names* of the caller's visible projects and, on a hit, append a hint:
  `No project 'Hamstrack' in this workspace. Did you mean "HD" (Hamstrack)?` A hint cannot be ambiguous,
  because it does not change the result set. This converts the entire name-matching use case into a
  one-time lesson, at zero semantic cost, and it leaks nothing (the names come from visible projects
  only — the same set `ProjectService.list` already returns).

**If the owner keeps name resolution anyway**, the rule must be **key first, then name**, short-circuit
(never a union): try the key map; only if it misses, try the name map, which may yield several ids.
Document loudly that adding a project whose *key* equals an existing project's *name* then silently
changes the meaning of an existing saved filter, and pin the precedence with a test whose failure message
says which map won. Do **not** ship a union — a union makes the field ambiguous by construction.

**Casing and normalisation.** Keys are stored uppercase; compare with the same canonical folding every
other name map uses (`SearchNames.key`, `SearchNames.java:47-49`), so a pasted `"hd "` resolves. The
blank-key rule applies with full force: reuse `HqlValueResolver.requireName` so a whitespace-only operand
is a field-anchored "expects a non-empty name" 422 and never a `map.get("")` lookup
(`SearchNames.java:33-40`). Precedent for case-insensitive project keys already exists on the parent
path: `IssueRepository.findIdByWorkspaceAndKey` compares `upper(i.project.key) = upper(:projectKey)`
(`IssueRepository.java:410-416`).

### 4.2 Data type and compilation shape — reuse `ENUM_REF`, no new branch

`project` is a single-valued, non-null `ToOne`, exactly like `component`
(`FieldRegistry.java:96-99`). Register it as `FieldDataType.ENUM_REF` with `entityPath = "project.id"`,
and it falls into the existing id-set branch (`HqlCompiler.java:296-303`) with no new compiler code.
Two side benefits: no new `FieldDataType` constant, and no edit to the SPA's data-type union — which is
already missing `VERSION_REF` (`src/main/frontend/src/types.ts:1024`), so a new constant would ship a
second stale entry.

Resolution gets a `case "project"` in `HqlValueResolver.resolveEnum` (`HqlValueResolver.java:70-93`)
pointing at the new context map, but with **its own error message**: the generic
`"No " + field.name() + " named '…'"` says *named*, and under §4.1 the operand is a key. Say
`No project with key '…' that you can search.` (§6.2 completes that sentence).

### 4.3 `!=` and `NOT IN` — which shape guarantees no out-of-scope row

**The guarantee is structural, not local.** `HqlCompiler.scopedPredicate` ANDs
`SearchScope.scopePredicate` as the outermost conjunction and nests the parsed tree inside it
(`HqlCompiler.java:170-176`). `SearchScope` binds `workspace = :ws AND project.id IN :visibleProjectIds`
from server state only (`SearchScope.java:69-79`). Therefore **no shape** of a parsed project term can
admit a row outside the boundary, including `cb.not(...)` over the whole clause — negating a nested
conjunct cannot negate its enclosing AND. What must be true is only that the term restricts *the same
`Root<Issue>`* and is ANDed, which is obligations 1–2 of that method's javadoc
(`HqlCompiler.java:133-153`).

**Recommended shape.** For `project != "HD"`:

```
cb.not(root.get("project").get("id").in(ids))
```

and **not** the generic branch's `cb.or(cb.isNull(idPath), cb.not(idPath.in(ids)))`
(`HqlCompiler.java:300`). The null disjunct is dead — `issues.project_id` is `NOT NULL`
(`V1__init_schema.sql:351`) — and worse, it is *misleading*: it reads as if the field were nullable,
one edit away from someone concluding `project IS EMPTY` should work. If reusing the shared branch
verbatim is preferred for uniformity, that is acceptable (it is correct, just redundant) — but then say
in the descriptor's comment that the disjunct is unreachable for this field, so the next reader does not
infer nullability from it. `NOT IN` over a bound, server-derived, never-null id list has no SQL
three-valued-logic trap.

**The one shape that would break the guarantee is not a Criteria shape at all: folding the term into
`SearchScope`.** Narrowing `visibleProjectIds` to the named project looks like an optimisation and is the
change a future contributor will propose. It must be refused: it makes the tenant boundary a function of
query text, which is the single property `SearchScope`'s javadoc exists to protect
(`SearchScope.java:24-28`). Write that refusal into the descriptor comment, phrased as a property of the
class of parsed terms — not as a fact about `project`.

**REVIEW — `NOT IN` is a grammar gap, not a field capability.** The ticket lists `NOT IN` among the
operators to support. There is **no `field NOT IN (…)` production in the grammar**: after a field name
the parser accepts a comparison operator, `IN`, or `IS`, and nothing else (`HqlParser.java:159-181`;
EBNF at `advanced-search-hql-proposal.md:144-147`). So `project NOT IN ("HD","OPS")` is a *parse error*
today, while `NOT project IN ("HD","OPS")` works. Two consequences:

1. Supporting `NOT IN` is a **language-wide** change — it affects every `supportsIn` field equally —
   and belongs in its own ticket that adds the production `field ["NOT"] "IN" "(" … ")"` → `Expr.Not(InList)`
   uniformly. Adding it inside HD-101 for one field would make `project` the only field with a syntax the
   others lack, which is a worse outcome than the gap.
2. HD-101's acceptance criteria must therefore say `NOT project IN (…)`, not `project NOT IN (…)`, or
   they will fail for a reason that has nothing to do with this ticket. **File the grammar ticket now**;
   `project NOT IN (…)` is exactly the phrasing a user will type first.

### 4.4 Archived projects — **422 with a message that states the category rule**

`visibleProjectIds` excludes archived projects (`SearchScope.java:52-56`), matching board/backlog and the
documented MVP rule (`advanced-search-hql-proposal.md:63-65`).

**Decision: a key that names only an archived project is a field-anchored 422**, phrased so the sentence
is true whether or not the caller's operand happens to be archived:

> `No project with key 'OLD' that you can search. Archived projects are not searchable.`

Why 422 and not "valid term, matches nothing":

- **Consistency with every other project-owned catalog.** Archived components and versions, and completed
  sprints, are excluded from name resolution and 422 (`HqlValueResolver.java:98-141`,
  `ResolutionContextFactory.java:114-168`). A silent-empty `project` would be the outlier, and a user who
  learned "an unresolvable name tells you so" would stop being able to trust that.
- **The result sets are identical either way** (an archived project's issues are outside the scope
  predicate regardless), so the only thing being chosen is the quality of the feedback. Silence is the
  worse feedback.

Why the second sentence is a *category* claim and not "project OLD is archived": telling the two apart
requires knowing the workspace's archived projects, which means a query over the **full** project set on
every search. That is a new query on the hot path — and, more seriously, it builds a code path that reads
projects the scope hides, which is precisely the seam that would rot when read visibility becomes
per-actor (§4.7). The generic sentence is honest, costs nothing, and cannot leak.

**Consequence for saved filters, stated rather than hidden.** A filter naming a project that is later
archived starts failing at run time with that 422 — the same documented behaviour a filter naming a
later-archived label has (`HqlValueResolver.java:98-104`), and a direct consequence of save-time
validation being structural only (`SavedFilterService.java:38-44`). The refusal must **not** prescribe
unarchiving: `project.archive` is a project-scoped permission outside the workspace curator set
(`Permission.java:143`, and `ProjectRepository.java:56-60` records that gap), so most readers cannot
perform it. The message prescribes nothing beyond naming the rule; the filter's owner can edit the
filter, which they can always do.

### 4.5 Sortability — **REVIEW: make it sortable**

**Pre-committed decision:** not sortable. **Recommendation: `sortable = true`, sorting on
`project.key`.**

`sprint` is non-sortable for a stated reason — "sprint order across several projects has no common
meaning" (`FieldRegistry.java:123-125`) — and `label`/`fixVersion` because an issue holds a *set* of them
(`FieldRegistry.java:82-83`, `:106-107`). **Neither reason applies here.** An issue has exactly one
project, and project key is a total order that means the same thing in every row. `component` — the field
`project` is structurally identical to — is sortable for exactly this argument
(`FieldRegistry.java:93-95`).

It is also cheap and trap-free. `sortPath` needs one arm, `root.get("project").get("key")`
(`HqlCompiler.java:830-846`), and it does **not** need the `componentJoin` dance
(`HqlCompiler.java:855-866`): that exists because an implicit inner join on a nullable `component` would
silently drop component-less issues, and `issues.project_id` is `NOT NULL`, so an implicit inner join
drops nothing.

The use case is the reason the field exists: a cross-project result set is most readable grouped by
project, and the results table already carries a Project column
(`advanced-search-hql-proposal.md:456`). Shipping the field without `ORDER BY project` means the first
thing a user tries after `project = "HD"` works is `ORDER BY project`, and gets a 422.

This is a low-stakes, reversible call in one direction only — adding sortability later breaks nothing,
so deferring is defensible. It is not defensible to defer it *silently*; if the builder keeps
`sortable = false`, the descriptor comment must say which of the three existing reasons it is claiming,
because none of them is true.

### 4.6 Suggestion bounding, and whether "no new query" holds

**The claim holds — conditionally, and the condition is worth pinning.** `ResolutionContextFactory`
already fetches the visible `Project` **entities** (`ResolutionContextFactory.java:68-70`) and currently
uses them only to derive capability id sets. Carrying `key` and `name` onto `ResolutionContext` therefore
costs **zero** additional statements on `/search`, `/schema` and `/suggest`.

- `/schema` `PROJECT` picklist: built from that in-memory list. Cap it at **200**, matching the other
  picklists (`SearchService.java:67-77`) — not because 200 projects is a realistic workspace, but because
  a shared constant is one fewer thing to reason about, and the cap has to exist for the same reason
  theirs does: nothing bounds the number of projects a workspace may hold.
- `/suggest?field=project`: a prefix filter over the same in-memory list, capped at `SUGGEST_LIMIT` (20).
  **Do not add a `ProjectService.suggestNames` repository call.** The other overflow suggesters query
  (`SearchService.java:229-261`) because their catalogs are large and not already loaded; the project
  list is small and already in the persistence context. A new query here would be pure cost.
- Match the prefix against **both** key and name, and return `Suggestion(label = "Hamstrack (HD)",
  value = "HD")` — the shape `SuggestResponse.Suggestion(label, value)` already supports and the SPA
  already honours (`HqlInput.tsx:186-188`).
- Sort by **key** ascending, so the picklist order matches what gets inserted.
- **Not capability-narrowed.** `suggested(...)` narrows fields tied to a delivery capability
  (`SearchService.java:328-336`); every project has a project, so `project` takes the `default -> true`
  arm untouched. Say so explicitly in the spec so nobody "completes the pattern" later.
- **Archived projects never appear** in either surface — the picklist must offer only what resolution
  accepts, the rule `SearchService.java:156-161` states for completed sprints.

**Throttling: nothing to do, and here is why that is not an omission.** No new endpoint is added;
`/search`, `/search/schema` and `/search/suggest` are already on the search budget, and the throttled
path set is sealed by a test whose failure message is the propagation checklist. The `project` branch of
`/suggest` does strictly *less* work than the branches beside it (no database round trip at all), so it
earns no new budget under the "a throttle is earned by the work a handler does" rule. Record this
sentence in the PR; a reviewer should see the rule was applied, not skipped.

### 4.7 If read visibility becomes permission-gated

`Permission.java:29-40` reserves `issue.view` / `project.view` for §8.5 and states the requirement that
matters: **404, never 403** — a visibility permission must not disclose existence.

**Nothing in this design breaks, provided §3's invariant 1 holds.** `visibleProjectIds` becomes an
actor-dependent subset, `ResolutionContextFactory` derives the project map from it as it derives every
other catalog, and `project = "SECRET"` becomes a 422 "no project with key … that you can search" for an
actor who cannot see it — consistent with `ProjectService.list`, which would hide it too, so no new
oracle appears.

**One thing must change with it, and the spec should say so now:** the existence oracle question. Today
every workspace member sees the same key set, so 422-vs-200-empty distinguishes nothing. Under per-actor
visibility it distinguishes "project you cannot see" from "project you can see with no matching issues" —
which is exactly the distinction `ProjectService.list` already makes, so it is not a new leak, and the
resolver's standing contract ("a name the caller can't see is indistinguishable from a typo",
`FieldResolver.java:110-117`) already covers it. **The failure mode to guard against is a future
convenience feature that widens the lookup:** an "archived vs unknown" distinction (§4.4), or a "did you
mean" hint sourced from all workspace projects rather than visible ones (§4.1). Both would turn a 422
into an existence oracle the day visibility becomes per-actor. Write that as a comment where the project
map is built, phrased about the *class* of lookup and not about today's two examples.

### 4.8 A tenant custom field keyed `project` — what the product should do

**What happens, precisely.** `FieldResolver` consults the registry first
(`FieldResolver.java:119-135`), so registering `project` makes the native field win over any workspace's
own custom field of that key — the same class of event `RetiredFieldAliases` documents
(`RetiredFieldAliases.java:22-38`), pointed the other way. Consequences for an affected tenant:

1. `project = "…"` in an existing saved filter stops meaning their field. Mostly this fails **loudly**:
   their stored values are not project keys, so value resolution 422s. It fails **silently and wrongly**
   only in the narrow case where a stored value coincides with a project key in the same workspace.
2. Worse, and silent in every case: `/schema` drops a custom field whose key the registry claims
   (`SearchService.java:176`). The tenant's `project` field keeps existing on the issue form and in the
   issue detail, but **disappears from search vocabulary with no message anywhere**. That is the real
   damage, and it is a direct consequence of the deliberate "vocabulary omits rather than refuses" rule.
3. The blast radius is one workspace — *unless* the colliding field is a **global** def
   (`scope_workspace_id IS NULL`), in which case it is every workspace on the instance at once, exactly
   as `RetiredFieldAliases.java:45-54` warns.

**This is more likely than the `story_points` precedent was**, because `AdminFieldService.createField`
slugs an omitted key from the display name (`AdminFieldService.java:250-253`): a field a curator called
**"Project"** is keyed `project` automatically, with no way to notice.

**Recommendation — three things, in order of value:**

1. **Detect before shipping (release runbook, in scope).** Run, and record the answer in the release
   notes:
   ```sql
   SELECT id, key, name, scope_workspace_id, scope_project_id, archived_at
     FROM field_defs
    WHERE lower(key) = 'project' AND archived_at IS NULL;
   ```
   Non-archived rows only — an archived def is already absent from resolution
   (`ResolutionContextFactory.java:190-194`) and is harmless. **This is what turns "unavoidable" into
   "known", and it is the single highest-value item in this section.** It has not been run: `Bash` is
   disabled in this session, so this proposal cannot state whether any workspace is affected.
2. **Guard the future (small, recommend folding into HD-101; acceptable as an immediate follow-up).**
   Reject a custom-field `key` the `FieldRegistry` has claimed, at create time, with the 409 shape
   `AdminFieldService.java:53-56` already uses — message naming it as a reserved search field name.
   The check must run **after** slugification, or the "Project" → `project` path walks straight past it,
   and must apply on create only (key is immutable on update, `AdminFieldService.java:73`). Note what it
   cannot do: it is not retroactive, and it must not reject or migrate existing rows.
3. **Release note, not a migration.** State the newly reserved key. Do not rewrite anybody's stored
   filter text — that prohibition is settled (`RetiredFieldAliases.java:56-58`). Be honest about the
   remedy for an affected tenant: a field's key is immutable, so the options are "search by a new field
   with a different key" or "accept that this one is no longer queryable"; everything except HQL search
   keeps working.

**What the product should *not* do:** register `project` as an alias in `RetiredFieldAliases`, or make
the registry yield to the custom field. Both invert the precedence for one case and would make the
resolution order conditional — the exact thing `FieldResolver` exists to prevent.

**Precedent worth naming, because it shapes the release note.** The codebase has met this collision
before, on *seeded* fields, and its answer each time was to archive the colliding def in the same
migration that made the name native: `labels` in V8 (`V8__labels.sql:98-104`), `components` in V9
(`V9__components.sql:96`), `fix_version` in V10 (`V10__versions.sql:149-156`), `story_points` and
`sprint` in V11 (`V11__sprints.sql:208-215`). No seeded def is keyed `project`
(`V1__init_schema.sql:577-592`, `V3__system_fields.sql:26-63`), so **HD-101 needs no migration** — but
the precedent is the reason the release note can say the product removes these collisions where it owns
both sides and cannot where a tenant owns one.

---

## 5. Behaviour & rules

### 5.1 Descriptor

| Property | Value | Why |
|---|---|---|
| name | `project` | |
| aliases | none | `projects` is tempting for symmetry with `labels`/`components`/`sprints`, but those are plurals of *many-valued or arguably-plural* fields; an issue has one project. Adding it later is free; removing it is not. |
| dataType | `ENUM_REF` | §4.2 |
| operators | `=`, `!=` | `EQ_ONLY` |
| supportsIn | `true` | |
| nullable | `false` | `issues.project_id NOT NULL`; `IS [NOT] EMPTY` is a 422 from `HqlValidator` (`HqlValidator.java:98-113`) with no new code |
| sortable | **`true`** (§4.5 — REVIEW) | sorts on `project.key` |
| entityPath | `project.id` | |
| valueSuggest | `PROJECT` | new picklist key |
| functions | none | |
| available | `true` | |

### 5.2 Happy path

`project = "HD"` → resolve `"HD"` → `SearchNames.key` → the visible-project key map → one id →
`root.get("project").get("id").in([id])`, ANDed inside the scope predicate. `project IN ("HD","OPS")`
unions the ids and compiles to a single `IN` (`HqlCompiler.java:327-347`, unchanged).
`NOT project IN ("HD")` compiles to `cb.not(...)` of that (§4.3).

### 5.3 Resolution rule

An operand resolves to the id of the caller's **visible, non-archived** project whose `key` matches
case-insensitively after canonical folding. Under §4.1 that is at most one id; the resolver should still
return `ResolvedValue.Ids` (a list) so a future decision to admit name matching, or a future
cross-workspace concept, does not change the compiler contract.

### 5.4 Errors

| Input | Result |
|---|---|
| Unquoted / numeric / function operand | 422, existing `requireString` / `requireName` messages (`HqlValueResolver.java:275-294`) |
| Whitespace-only operand | 422 "Field 'project' expects a non-empty name" — via `requireName`, never a `map.get("")` |
| Key matching nothing visible | 422 `No project with key 'X' that you can search. Archived projects are not searchable.` (+ the §4.1 name hint when a visible project's *name* matches) |
| `project IS EMPTY` | 422 "Field 'project' cannot be empty" — from the existing validator, no new code |
| `project ~ "x"`, `project > "x"` | 422 "Operator … is not allowed on field 'project'" — existing |
| `ORDER BY project` | 200 under §4.5; 422 "not sortable" if the pre-committed `sortable=false` stands |

Every one of these is a **422 with `field: "project"`** — never 404, never a silent empty 200.

### 5.5 What must not change

- `SearchScope` — no signature, no body, no new caller (§4.3).
- The name→id maps of `component` / `fixVersion` / `affectsVersion` / `sprint`. `project` disambiguates
  them **by conjunction** (`project = "HD" AND fixVersion = "0.13.0"`), not by narrowing their
  resolution. Making version resolution project-aware is a different, much larger change and is not
  this ticket.
- `RetiredFieldAliases` (§4.8).

### 5.6 Saved filters

Free — `SavedFilterService.validateHql` builds the same `ResolutionContext` and runs the same
`HqlValidator` (`SavedFilterService.java:163-166`), so `project` becomes valid in a stored filter the
moment it is registered. Value resolution stays deferred to run time by design, which is what produces
the archived-project behaviour in §4.4. **No code change in the filter package**; add tests, not code.

---

## 6. Edge cases & failure modes

| Case | Behaviour |
|---|---|
| Workspace with zero visible projects | Scope already yields a false predicate (`SearchScope.java:73-76`); a `project` operand 422s first, since the map is empty. Both are correct; the 422 is the one the user sees. |
| Project archived between saving and running a filter | 422 at run time, §4.4. Not a bug; documented. |
| Project archived between `/schema` and query submission | Same 422. The picklist is a snapshot, as every other picklist is. |
| Key that is also a valid project *name* elsewhere | Under §4.1 (key only): unambiguous. Under key+name fallback: key wins, and the collision must be pinned by a test. |
| Two projects sharing a name | Impossible to express under §4.1; under name fallback, resolves to both ids. Another reason for §4.1. |
| `project = "HD"` where HD is in **another workspace** | 422 unknown — the map is built from this workspace's visible ids only. No existence leak. |
| Duplicate keys in one operand list (`IN ("HD","hd")`) | Same id twice in the list; `IN` is idempotent. Harmless; no de-duplication required. |
| Concurrency / optimistic locking | None. Read-only path, no entity mutated, no `@Version` touched. |
| Idempotency / races | None. No writes. |
| In-use-on-delete / stranded rows | None. No new table, no FK, no lifecycle. |

---

## 7. Data model impact

**None.** No new table, column, index or Flyway migration. The field reads `issues.project_id` (already
`NOT NULL` with an FK) and `projects.key` / `projects.name` (already loaded per request). The existing
`ix_issues_ws_project (workspace_id, project_id)` index (`advanced-search-hql-proposal.md:428`) already
serves a `project_id IN (…)` predicate, which is what this compiles to.

The only in-memory addition is on `ResolutionContext`: a key→id map (and the display names backing
§4.1's hint and §4.6's labels), both built from entities the request already holds.

---

## 8. API surface

No new endpoint, no new path, no changed status code.

- `POST /api/workspaces/{workspaceId}/search` — accepts `project` in the query string. Unchanged
  request/response DTOs.
- `GET /api/workspaces/{workspaceId}/search/schema` — `fields[]` gains one entry:
  ```json
  { "name": "project", "type": "ENUM_REF", "operators": ["=", "!=", "IN"],
    "nullable": false, "sortable": true, "valueSuggest": "PROJECT", "functions": [] }
  ```
  and `values` gains `"PROJECT": [{ "label": "Hamstrack (HD)", "value": "HD" }, …]` (≤ 200, key order).
- `GET /api/workspaces/{workspaceId}/search/suggest?field=project&q=` — `{ "field": "project",
  "suggestions": [{ "label": "Hamstrack (HD)", "value": "HD" }] }`, ≤ 20.

`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` must follow (`api-docs-sync`). The schema
example blocks in those files list fields; adding `project` there is the whole change.

---

## 9. Frontend impact

**No frontend change is required for this slice, and that is a verified claim rather than a hope.**
`HqlInput` builds its field list from `/schema` (`HqlInput.tsx:203-205`), renders `valueSuggest`
picklists generically (`HqlInput.tsx:193-196`), inserts `value ?? label`, and prefix-filters on both
strings (`HqlInput.tsx:214-216`). A new `ENUM_REF` field with a `PROJECT` picklist therefore appears in
autocomplete, with the human name shown and the key inserted, on the next deploy.

Deferred to the later slice (out of scope here): a dedicated project chip/selector in the search UI, and
`src/main/frontend/src/types.ts` hygiene (its `SearchField['type']` union is already missing
`VERSION_REF`, `types.ts:1024`) — a pre-existing gap this change does not widen, because `project` reuses
`ENUM_REF`.

---

## 10. DC vs Cloud

**Uniform. No profile gate, no property, no new environment variable, no wiring targets.**

Stated positively rather than by omission: this is a query-language field over data both deployment
modes already hold, with no storage, mail, auth or billing surface. It must **not** be made toggleable —
a field that exists in one mode and not the other would make saved-filter text non-portable between a DC
instance and Cloud (and across an export/import), which is a worse outcome than any configurability it
would buy. The only difference between modes is data: a DC instance typically has fewer projects, so its
picklist is shorter.

---

## 11. Acceptance criteria

Tenancy & scope

- [ ] Compiled page **and** count queries still contain the `SearchScope` conjunction outermost with a
      `project` term present; `project != "X"` and `NOT project IN ("X")` return only rows inside the
      caller's visible projects.
- [ ] `SearchScope.visibleProjectIds` and `scopePredicate` are byte-for-byte unchanged.
- [ ] A key belonging to another workspace 422s (unknown), never 200-empty and never 404.
- [ ] The project lookup map is built from `SearchScope.visibleProjectIds` — a test fails if it is built
      from `findAllByWorkspace`.

Resolution

- [ ] `project = "HD"`, `project = "hd"`, `project = " HD "` all resolve identically.
- [ ] `project = "  "` → 422 "expects a non-empty name" (blank-key guard), not a `map.get("")` match.
- [ ] `project IN ("HD","OPS")` returns the union; `NOT project IN ("HD")` returns the complement within
      scope.
- [ ] A key naming only an **archived** project → 422 whose message states that archived projects are
      not searchable.
- [ ] Under §4.1: `project = "<a visible project's name>"` → 422 **with** a hint naming the key. Under a
      kept key+name fallback: a workspace with project A keyed `OPS` and project B named `OPS` resolves
      `project = "OPS"` to **A only**, pinned by a test naming the precedence.

Operators & validation

- [ ] `project IS EMPTY` / `IS NOT EMPTY` → 422 "cannot be empty".
- [ ] `project ~ "x"` and `project > "x"` → 422 "operator not allowed".
- [ ] `ORDER BY project` sorts by key ascending / descending (§4.5) — or 422 "not sortable" if that
      decision stands.
- [ ] Acceptance text says `NOT project IN (…)`, not `project NOT IN (…)` (§4.3).

Vocabulary

- [ ] `/schema` lists `project` for every member of every workspace, with no capability narrowing.
- [ ] `PROJECT` picklist contains only visible non-archived projects, ≤ 200, `label` carrying the name
      and `value` the key.
- [ ] `/suggest?field=project&q=` prefix-matches key **and** name, ≤ 20.
- [ ] A query-count test proves `/search`, `/schema` and `/suggest?field=project` issue **no additional
      statements** versus the pre-change baseline.

Saved filters

- [ ] `POST /filters` accepts `hql` containing `project = "HD"`; the filter runs; archiving the project
      makes the *run* 422 while the stored text is untouched.

Collision

- [ ] The §4.8 detection SQL has been run against production and the answer recorded in the release
      notes **before** the field ships.
- [ ] (If the guard lands) creating a custom field named "Project" with no explicit key → 409 naming the
      reserved key, not a silently shadowed field.

---

## 12. Open questions

| # | Question | Recommended default |
|---|---|---|
| 1 | Key-only, or key-then-name fallback? | **Key only** (§4.1), with the name in suggestion labels and in the 422 hint. |
| 2 | `sortable`? | **True** (§4.5). |
| 3 | Register a `projects` plural alias? | **No.** Free to add later; not free to remove. |
| 4 | Does the reserved-key guard on custom-field creation land in HD-101 or immediately after? | Fold it in — it is small, and it is the only part of §4.8 that prevents recurrence. Splitting it is acceptable if it is filed the same day. |
| 5 | Should a *shared* saved filter naming a project the runner cannot see behave differently? | No, and it needs no work: a shared filter already runs as the caller (`advanced-search-hql-proposal.md:55`), so it 422s for a runner who cannot see the project — correct under §4.7, and identical to how every other name behaves. |

---

## 13. Highest-risk assumption

**That `project` is not already in use as a custom-field key in a real workspace — asserted by nobody and
verified by no one.** This proposal could not check it (§0). Everything else here is reversible by an
edit; this one silently removes a field from one or more tenants' search vocabulary
(`SearchService.java:176`) with no error, no log line and no UI affordance, and it becomes true the
moment the field is registered. **Run the §4.8 query before the release, not after.**

Second-highest: the assumption that name matching is a convenience. It is a *semantics* change — under
name matching, one operand can denote two projects, because `projects.name` carries no uniqueness
constraint (`V1__init_schema.sql:145-161`). That is the property the field is being added to remove.

---

## 14. Shared machinery with HD-119 (noted, not specified)

HD-119 (`resolved` / `closed`) reuses this ticket's registration path and nothing more: an entry in
`FieldRegistry`, a `case` in the resolver, and the id-set/date branches that already exist. Two things it
will hit that are already visible from here, recorded so the next spec does not rediscover them:

- `issues.closed_at` is a native column (`V3__system_fields.sql:16`), so `closed` is a `TIMESTAMP` field
  like `created`/`updated` with no new compiler branch — the `storyPoints` precedent, not the
  `component` one.
- **`resolution` is a seeded *global* system `field_def`** (`V3__system_fields.sql:48-54`), non-archived.
  Registering an HQL name that collides with it would shadow it for every workspace on the instance at
  once — the §4.8 problem at maximum blast radius. HD-119 must decide that deliberately, and the V8/V9/V10
  precedent (archive the seeded def in the same migration) is available to it in a way it is not
  available to HD-101.

---

## Appendix A — side finding, outside HD-101's scope

`fix_version` was archived as a `field_def` by V10 (`V10__versions.sql:149-156`) and the native field
took the name `fixVersion`. Unlike `story_points`, it was given **no** entry in `RetiredFieldAliases`
(`RetiredFieldAliases.java:64-65`) and no lowercase-underscore registry alias — `fixversion` is a key
because the canonical name lowercases to it, but `fix_version` is not. So a saved filter written as
`fix_version = "2.4.0"` before V10 now fails with **"Unknown field"**, where the equivalent
`story_points` filter keeps working. Same for `components`/`labels`? No — those survive, because the
registry happens to carry them as plural aliases (`FieldRegistry.java:87`, `:99`).

Worth a one-line ticket: add `fix_version` → `fixVersion` to `RetiredFieldAliases`. It is the same
compatibility promise, honoured for one retired key and not its sibling. Not part of HD-101.
