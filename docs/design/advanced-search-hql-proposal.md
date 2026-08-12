# Advanced Search (HQL) + Saved Filters — Proposal

Status: **draft, awaiting approval** — spec for epic **HD-3** and children **HD-21** (backend query engine), **HD-25** (frontend search + results), **HD-26** (saved filters). Written for `backend-builder` / `frontend-builder`. Engine architecture, `FieldRegistry`, package `com.hamstrack.search`, workspace-scoped endpoints, and Criteria-only compilation are **already decided** (see §0) and are not re-opened here.

Epic acceptance (verbatim): `status = "In Progress" AND assignee = currentUser() ORDER BY priority DESC` returns correct, **workspace-scoped** results; a saved filter can be reused as a board/report data source.

---

## 0. Locked decisions (do not re-open)

1. **Engine**: hand-written lexer + Pratt/recursive-descent parser → typed AST → value resolver → compiler to **JPA Criteria API** with bound parameters. No ANTLR, no jOOQ, no string SQL.
2. **Central `FieldRegistry`**: HQL field name → `{ dataType, allowedOperators, valueResolver, predicateBuilder, autocompleteSource }`. One place to register a queryable field; it drives parse-validation, execution, and the `/schema` autocomplete endpoint. Decouples the public language from DB column names.
3. **Package**: `com.hamstrack.search` (backend). "HQL" appears only in user-facing text/grammar, never as a Java package/class name that could collide with Hibernate HQL.
4. **Scope**: workspace-scoped endpoint; verify workspace membership; **404 (not 403)** for non-members. Results further filtered by a server-built `SearchScope.scopePredicate(...)` — a Criteria **predicate builder, not an id list** — so the planned 3-layer access model (public/private projects + intra-project per-type grants) lands as a change to one method, not a rewrite (see §3.1.1–§3.1.2).
7. **Execution engine**: Postgres via JPA Criteria; **no external search engine** (Elasticsearch/OpenSearch) in MVP or the foreseeable roadmap. Decoupled `AST → compiler` design keeps OpenSearch available as a future second compiler target if scale ever forces it (see §3a).
5. **Endpoints**: `POST /api/workspaces/{ws}/search` (paginated; 422+position on parse error), `GET /api/workspaces/{ws}/search/schema` (autocomplete metadata), `GET /api/workspaces/{ws}/search/suggest?field=&q=` (bounded user/value typeahead — §8.2/§16.4), CRUD under `/api/workspaces/{ws}/filters`. Stored HQL is validated at save time.
6. **Text search** `~` = Postgres `ILIKE '%term%'` on title/description for MVP. `pg_trgm`/`tsvector` noted as future, not a blocker.

---

## 1. Problem & goal

Users need to answer cross-project questions ("everything assigned to me that's In Progress, most urgent first") that today's hardcoded single-project `statusId/assigneeId/priorityId` filters cannot express. HD-3 introduces **HQL** — a small, safe, autocompleted query language — plus a results view and **saved filters** (personal and shared) that later become reusable data sources for boards and reports.

**Success:** a workspace member types an HQL expression in the top bar, gets autocomplete for fields then legal values, sees a paginated sortable results table restricted to issues they may read within that workspace, and can save the query as a named filter (private or shared to the workspace). Malicious input can neither escape parameter binding nor the workspace/visibility predicate.

---

## 2. Scope

**In scope (MVP):**
- HQL grammar over the queryable fields in §5, compiled to JPA Criteria.
- `POST …/search` (paginated results) and `GET …/search/schema` (autocomplete metadata).
- Saved filters: table, CRUD, personal/shared visibility, save-time validation, delete-warning hook for future board/report usage.
- Frontend: HQL input with field/value autocomplete + inline error position highlight; results table with column chooser, sortable columns, click-through to the issue drawer, offset pagination; "Save as filter".

**Out of scope / non-goals (MVP):**
- Actually rendering a board or report *from* a saved filter — HD-26 defines only the **data-model hook and delete-warning**; wiring a filter as a board/report source is a follow-up epic.
- Full-text relevance ranking (`tsvector`/`pg_trgm`), fuzzy match, stemming.
- Querying `label` (HD-30, not yet a column) and custom fields (JSONB) — the grammar and registry are built to accept them later without a grammar change; MVP ships them as **registry entries that report "not yet available"** (see §5.4).
- Cross-workspace / global search. Every query is bound to one workspace.
- Saved-filter sharing to individuals or projects (only "private" vs "whole-workspace shared" in MVP).
- Keyset pagination, saved sort/column layouts per filter (offset + client-side column choice only).

---

## 3. Actors, permissions & tenant scoping

| Action | Who | Rule |
|---|---|---|
| `POST …/{ws}/search` | any **workspace member** | `resolveWorkspace(actor, ws)` → 404 if not a member. Results scoped to visible projects (below). |
| `GET …/{ws}/search/schema` | any **workspace member** | same membership gate; schema is per-workspace (value suggestions read that workspace's catalog/members). |
| Create/update/delete own filter | filter **owner** (any workspace member) | owner-only edit/delete. |
| Read a filter | owner (private) or **any workspace member** (shared) | shared filters are read-only to non-owners. |
| Run a shared filter | any workspace member | executes as the *caller* — `currentUser()` and project-visibility resolve to the **caller**, never the owner (see §9 risk). |

### 3.1 Project-visibility model — resolve the conflict explicitly

Decision #4 says "only projects the caller is a member of." **The current codebase does not gate issue reads on project membership** — `IssueService.list/get` require only workspace membership and default a non-project-member to `ProjectRole.VIEWER` (read allowed). So today *every workspace member can read every project's issues in that workspace.*

To avoid HD-3 silently introducing a stricter (and inconsistent) visibility rule than the rest of the app, the spec adopts:

> **MVP rule: search returns issues in all *non-archived* projects of the workspace the caller is a member of** — i.e. it matches the existing issue-read surface exactly. The workspace-membership predicate is the hard tenant boundary; project-level visibility mirrors `IssueService`.

Archived projects are **excluded by default** (matches board/backlog behavior); a future `includeArchivedProjects` flag can relax it.

#### 3.1.1 Scope is a Criteria *predicate builder*, not an id list — future-proofing for the planned 3-layer access model

The scope must **not** be modeled as a `List<UUID>` of visible project ids. The planned access model (see §3.1.2) has an intra-project dimension that a project-id `IN` list cannot express (it is a row-level filter *within* a project, e.g. by issue type). To make future tightening a **localized change instead of a rewrite**, the scope is injected as a predicate builder:

```java
// com.hamstrack.search.SearchScope
Predicate scopePredicate(User actor, Workspace ws, Root<Issue> root, CriteriaBuilder cb);
```

- **Today** it returns `cb.and( root.get(workspace) = ws , root.get(project).get(id) IN :visibleProjectIds )` where `:visibleProjectIds` = the workspace's non-archived projects (matching `IssueService`).
- **Tomorrow** it additionally ANDs layer-2 (public vs private membership) and layer-3 (per-issue-type / per-grant) clauses — arbitrary row-level rules, including correlated subqueries against an ACL/grant table — **without touching the grammar, parser, AST, `FieldRegistry`, endpoints, or frontend.** Only this method's body changes.
- **`SearchScope` should delegate to a shared issue-visibility component** (the same one that will back `IssueService.list/get`) as soon as one exists, so board, backlog, and search can never diverge on who-can-see-what — the project's #1 bug class. Until then it stays a single method so adopting that component later is a one-line swap.

**Invariant (non-negotiable):** the compiler always ANDs `scopePredicate(...)` onto the parsed predicate as the **outermost conjunction**. The parsed query can only *narrow* within that boundary; no parsed token can widen or remove it. Every value inside the scope predicate (`ws`, visible ids, future grants) is bound/derived **server-side from the authenticated actor**, never from query text.

#### 3.1.2 Planned future access model (informational — not built in HD-3, but the reason for §3.1.1)

Three layers, tightening inward. HD-3 must not paint itself into a corner that any of these would force a rewrite of.

1. **Workspace membership** — the hard tenant boundary. Unchanged by HD-3. → outermost `workspace = :ws`.
2. **Public vs private projects.** A user added to a workspace is **auto-considered a member of every *public* project** (its issues show immediately); **private** projects require an admin to add the user explicitly. → this is exactly "the set of visible project ids for the actor." When it lands, `scopePredicate` computes that set instead of "all non-archived projects." **One-method change.**
3. **Intra-project access.** Access can be narrowed *within* a project — e.g. a user may see issue type `Task` but not `Finance`. → a **row-level** predicate (`project.id IN partiallyVisible ⇒ type.id IN allowedTypesForThatProject`, or a join to a grant table). Not expressible as a project-id list — this is the whole reason scope is a predicate builder. When it lands, `scopePredicate` ANDs this clause; search inherits it with **no change to the query engine.**

Because the parsed user query is always ANDed *under* `scopePredicate`, a user can never widen beyond what these layers allow, at any layer.

**Engine implication (see §3a):** this fine-grained, frequently-changing access model is a strong reason to keep execution in Postgres/Criteria rather than an external search index — permission-aware search in a denormalized index requires re-indexing on every grant change and leaks data during any indexing lag. In a Criteria query the ACL is just another join in the same transactional read: always consistent, revocation is instant.

---

## 3a. Execution engine choice & evolution path

**Decision: HQL executes against Postgres via JPA Criteria. No external search engine (Elasticsearch/OpenSearch/Lucene) in the MVP or the foreseeable roadmap.** This is deliberate, not a default. Rationale, tied to project constraints:

1. **"Simple deployment, identical DC + Cloud" is a core requirement (CLAUDE.md).** An external search cluster forces *every self-hoster* to run and operate a second stateful datastore alongside Postgres — directly at odds with simple DC deployment. Postgres is already a hard dependency; a search cluster is not.
2. **No dual-write / consistency tax.** A separate index means keeping it in sync with the DB (dual writes, reindex jobs, eventual consistency, "search says X but the issue is Y"). Postgres as the single source of both truth and queries is transactionally consistent with zero staleness.
3. **HQL is structured filtering, not relevance ranking.** The acceptance query (`status = … AND assignee = currentUser() ORDER BY priority DESC`) is a filter, Postgres's native strength. The only place a Lucene-based engine is genuinely better is full-text (`~`), which `pg_trgm` / `tsvector`+GIN cover well far past our current scale (§12).
4. **The planned fine-grained access model (§3.1.2) fits Postgres better than an index.** Permission-aware search in a denormalized index requires embedding permissions in each document and re-indexing on every grant change; any indexing lag is a **data-leak window**. In Criteria the ACL is one more join in the same transactional query — always consistent, instant revocation. The finer the per-type/per-object permissions, the worse an external index fits.

**Why Jira's Lucene→OpenSearch move is not a template for us (yet).** Note it is a move from *embedded/self-managed* Lucene to a *distributed* search platform that is itself *built on* Lucene — not away from Lucene. Atlassian's drivers are planet-scale Cloud (billions of issues, cross-tenant relevance/faceting) and the operational pain of syncing/recovering per-node Lucene indices across Data Center nodes. Those are correct decisions *for their stage*; copying them at ours would trade our core "simple deployment" property for scale we don't have and add a consistency burden for no present benefit.

**The door stays open by design.** We compile `parser → AST → compiler`, and the compiler targets JPA Criteria/Postgres. Because execution is decoupled from the language, a future scale ceiling (full-text relevance / faceting over a huge corpus) can be met by adding a **second compiler target: AST → OpenSearch query DSL**, behind the *same* grammar, endpoints, and frontend. The query language, API, and UI do not change. This swappability is the strategic payoff of compiling from an AST rather than hand-writing queries — and the reason we can safely defer the engine question until scale actually forces it.

---

## 4. HQL grammar

### 4.1 Lexer tokens

```
IDENT      = letter (letter | digit | '_')*        # field names, function names, ORDER BY keywords
STRING     = '"' ( '\\' any | not('"' | '\\') )* '"'   # double-quoted, backslash escapes \" and \\
            | '\'' ( '\\' any | not('\'' | '\\') )* '\''  # single-quoted, symmetric
NUMBER     = '-'? digit+ ('.' digit+)?
LPAREN ')' RPAREN '(' COMMA ','
OP         = '=' | '!=' | '~' | '>' | '<' | '>=' | '<='
KEYWORDS   = AND OR NOT IN IS EMPTY ORDER BY ASC DESC      # case-insensitive
FUNC-CALL  = IDENT '(' [ args ] ')'                        # currentUser(), now(), startOfWeek()
```

- **Case sensitivity:** keywords, operators and function names are **case-insensitive** (`and`/`AND`, `currentuser()`/`currentUser()`). Field names are **case-insensitive** and normalized to the registry's canonical lowercase key. Quoted string *values* are **case-preserving** but matched case-insensitively by resolvers (status/type/priority/user lookups use `ILIKE`/`equalsIgnoreCase`).
- **Reserved words:** `AND OR NOT IN IS EMPTY ORDER BY ASC DESC` plus the function names. A field literally named like a keyword is not a concern (all registry fields are plain identifiers distinct from keywords).
- **Whitespace** is insignificant except as a token separator.
- **Comments:** none in MVP.

### 4.2 Grammar (EBNF-ish)

```
query        = expr [ orderBy ] ;
orderBy      = "ORDER" "BY" sortKey { "," sortKey } ;
sortKey      = field [ "ASC" | "DESC" ] ;              # default ASC

expr         = orExpr ;
orExpr       = andExpr { "OR" andExpr } ;
andExpr      = notExpr { "AND" notExpr } ;
notExpr      = "NOT" notExpr | primary ;
primary      = "(" expr ")" | predicate ;

predicate    = comparison | inClause | emptyClause | textMatch ;
comparison   = field ( "=" | "!=" | ">" | "<" | ">=" | "<=" ) value ;
inClause     = field "IN" "(" value { "," value } ")" ;
emptyClause  = field "IS" [ "NOT" ] "EMPTY" ;          # null / not-null
textMatch    = field "~" STRING ;                      # ILIKE contains

value        = STRING | NUMBER | funcCall ;
funcCall     = IDENT "(" [ value { "," value } ] ")" ; # MVP funcs take no args
field        = IDENT ;
```

**Precedence (highest → lowest):** parentheses → `NOT` → `AND` → `OR`. `ORDER BY`, if present, must be the last clause. Associativity of `AND`/`OR` is left; explicit parentheses are honored.

**Empty query:** an empty/whitespace-only `query` string is **legal** and means "no predicate" → returns all visible issues (still scoped), default sort. (Convenient for "browse everything" and for a filter that is just a sort.)

**IN-list:** at least one value; comma-separated; homogeneous per the field's type. Values may be strings, numbers, or functions (e.g. `assignee IN (currentUser())` — legal but degenerate).

**Quoting/escaping:** either `"..."` or `'...'`. Inside a string, `\"`, `\'`, `\\` are the only escapes; any other `\x` is a parse error (keeps it predictable). Unquoted bare words are **not** accepted as values (avoids the `status = In Progress` ambiguity) — the parser emits a targeted error "string value must be quoted" with the position. Numbers and function calls are unquoted.

---

## 5. Field catalog (the `FieldRegistry`)

Each field is a registry entry: `{ name, dataType, operators, valueKind, nullable, predicateBuilder, autocomplete }`. `dataType ∈ {ENUM_REF, USER_REF, ISSUE_REF, TEXT, DATE, TIMESTAMP, NUMBER}`.

| HQL field | dataType | Operators | Value syntax | EMPTY? | Notes |
|---|---|---|---|---|---|
| `status` | ENUM_REF | `=` `!=` `IN` | quoted name | no | resolved by name across workspace catalog → id set (§6.1) |
| `type` | ENUM_REF | `=` `!=` `IN` | quoted name | no | same resolution as status |
| `priority` | ENUM_REF | `=` `!=` `IN` `>` `<` `>=` `<=` | quoted name | no | ordering/`>`/`<` use catalog `position` (see §6.1) |
| `assignee` | USER_REF | `=` `!=` `IN` `IS [NOT] EMPTY` | quoted (email/displayName), `currentUser()` | **yes** (unassigned) | |
| `reporter` | USER_REF | `=` `!=` `IN` | quoted (email/displayName), `currentUser()` | no (always set) | |
| `parent` | ISSUE_REF | `=` `!=` `IS [NOT] EMPTY` | quoted issue key `"DEMO-12"` | **yes** (top-level) | |
| `text` | TEXT | `~` | quoted term | n/a | `ILIKE '%term%'` over `title OR description` |
| `created` | TIMESTAMP | `>` `<` `>=` `<=` `=` `!=` | quoted `"YYYY-MM-DD"`, `now()`, `startOfWeek()` | no | date compared against timestamp (§6.3) |
| `updated` | TIMESTAMP | `>` `<` `>=` `<=` `=` `!=` | same | no | |
| `due` | DATE | `>` `<` `>=` `<=` `=` `!=` `IS [NOT] EMPTY` | quoted `"YYYY-MM-DD"`, `now()`, `startOfWeek()` | **yes** (no due date) | `issues.due_date` is a `DATE` |

**Not in the MVP grammar but reserved (see §5.4):** `label`, custom-field keys.

### 5.1 EMPTY / null semantics

Null is expressed with `IS EMPTY` / `IS NOT EMPTY` (only for nullable fields: `assignee`, `parent`, `due`). This reuses SQL keywords the user already knows and avoids overloading `= ""`:
- `assignee IS EMPTY` → `issue.assignee IS NULL` (unassigned).
- `assignee IS NOT EMPTY` → assigned to anyone.
- `parent IS EMPTY` → top-level issues.
- `due IS NOT EMPTY` → has a due date.

Using `IS [NOT] EMPTY` on a non-nullable field (e.g. `status`) is a **semantic 422** ("field 'status' cannot be empty"). `EMPTY` used as a plain value (`assignee = EMPTY`) is a parse error (EMPTY is only legal after `IS`).

### 5.2 `text` field

`text ~ "login bug"` compiles to `LOWER(title) LIKE %login bug% OR LOWER(description) LIKE %login bug%` via `criteriaBuilder.like(cb.lower(...), pattern)` with the pattern bound as a parameter and `%`/`_` in the user term **escaped** (`ESCAPE '\'`) so a term containing `%` matches literally. No other operator is legal on `text`.

### 5.3 Ordering / comparison on `priority`

Priorities have no intrinsic numeric value; the catalog `position` column defines rank. `priority > "Low"` resolves `"Low"` to its `position` and compares `priority.position` — but **positions differ per priority-set binding is not a concern** because priorities are a flat global catalog (position is a catalog column, one value per priority). `priority = "High"` resolves to the priority id (name→id, §6.1) and compares the FK. `>`/`<` on `priority` use `position`; `=`/`!=`/`IN` use the id.

### 5.4 Extensibility for `label` and custom fields (ship "not yet available")

The registry supports adding fields later with **no grammar or parser change**. For MVP:
- `label` and custom-field keys are **either omitted entirely from the registry** (simplest — unknown-field error) **or** registered as `Unavailable` stubs that parse but return a **422 "field 'label' is not yet queryable"**. **Recommendation: omit them in MVP** (cleaner error: "unknown field 'label'; did you mean …") and add real entries with HD-30 / custom-field search as their own tickets. The `/schema` endpoint therefore lists only live fields, so autocomplete never suggests a dead field.

When added later, custom fields will register as JSONB predicates (`jsonb_extract_path_text(value, ...)` cast per field type) and USER/SELECT custom fields reuse the USER_REF/ENUM_REF resolvers. This is explicitly out of MVP.

---

## 6. Value resolution

All resolution happens in the `valueResolver` per field **after** parsing, **before** compilation, inside the request transaction, scoped to the workspace. A resolution failure is a **semantic error** (see §7).

### 6.1 status / type / priority by name (cross-project ambiguity — resolved)

The taxonomy is a **global catalog** reached per-project through bindings; the *same name* can map to one catalog row used by several projects, but a name is **not** guaranteed unique in a multi-project search. Resolution rule:

> Resolve a status/type/priority **name → the set of catalog ids whose name matches (case-insensitive) and that are reachable by any visible project in the workspace**, then compile `=`/`!=` as `IN (ids)` / `NOT IN (ids)`. In practice each name maps to exactly one catalog row (names are unique in the global catalog today), but building it as an id-**set** is future-proof against per-workspace-scoped catalog rows (`scope_workspace_id`) that could duplicate a name.

- "Reachable by a visible project" = the id appears in some visible project's effective workflow (status), type set (type) or priority set (priority), resolved via `ProjectConfigService`. This means `status = "In Progress"` matches issues across every project whose workflow contains an "In Progress" status — exactly the cross-project semantics the epic wants.
- If a name resolves to **zero** ids → semantic 422 "no status named 'Foo' in this workspace" (not an empty result — a typo'd status name should be a helpful error, not silently zero rows). Rationale in §7.
- Archived catalog rows are excluded from name resolution but issues already carrying them still match by id if the name also resolves to a live row; a name that resolves *only* to archived rows → 422.

### 6.2 user (assignee / reporter) by email / displayName / currentUser()

- `currentUser()` → the caller's user id (bound parameter). This is what makes a shared filter personal to each runner.
- A quoted value resolves as: **exact email match (case-insensitive) first**, else exact `displayName` match (case-insensitive), scoped to **workspace members**. Non-members never resolve (no cross-tenant user enumeration).
- Ambiguous displayName (two members share a display name and the value isn't an email) → semantic 422 "ambiguous user 'Alex'; use their email". Zero matches → 422 "no member matching 'x'".
- **Resolved:** the human forms — email, displayName, `currentUser()` — are primary; a **raw UUID is accepted as a fallback**. displayName is not guaranteed unique, so a name resolves via `IN` over all matching member ids. A UUID that doesn't match a workspace member is unresolvable → 422 (§16.1).

### 6.3 dates / timestamps & functions

- **Timezone: server UTC.** `now()`, `startOfWeek()`, and `"YYYY-MM-DD"` literals are all evaluated in UTC. Documented in api docs; a per-user timezone is a future enhancement (§12.2).
- `now()` → current instant (for `created`/`updated`) or current date (for `due`, a `DATE` column) — the resolver picks the shape from the field's dataType.
- `startOfWeek()` → 00:00 UTC of Monday of the current week (ISO week, Monday start).
- A `"YYYY-MM-DD"` literal against a `TIMESTAMP` field (`created`/`updated`): `created > "2026-08-01"` compiles to `created > 2026-08-01T00:00:00Z`; `created <= "2026-08-01"` compiles to `< 2026-08-02T00:00:00Z` (inclusive end-of-day) so date comparisons behave intuitively. `=` on a timestamp with a date literal means "within that UTC day" (`>= day AND < day+1`). Against `due` (a real `DATE`) comparisons are direct.
- Malformed date literal → semantic 422 "invalid date 'x'; expected YYYY-MM-DD".

---

## 7. Error model

Two error classes, both `ProblemDetail` (the app's existing shape via `GlobalExceptionHandler`), distinguished by *when* they're detected:

### 7.1 Parse errors → **422** with position

Lexer/parser failures (bad token, unquoted value, unbalanced parens, `ORDER BY` not last, illegal escape, depth/length limits). Response:

```
HTTP 422
{
  "type": "about:blank",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Expected a quoted string value at position 9",
  "errorType": "PARSE_ERROR",
  "position": 9,        // 0-based char offset into the query string
  "length": 11,         // span length for the highlight (best-effort; may be 0)
  "token": "In"         // offending lexeme, when available
}
```

The SPA underlines `[position, position+length)`. `position`/`length`/`token` are custom `ProblemDetail` properties (via `setProperty`).

### 7.2 Semantic errors → **422** (no position, or field-anchored)

Unknown field, illegal operator for a field, unresolvable value (no such status/user/date), `IS EMPTY` on a non-nullable field, ambiguous user. These are known only after parse + registry validation + resolution:

```
HTTP 422
{ "status":422, "detail":"Unknown field 'labl'. Did you mean 'label' … 'text'?",
  "errorType":"SEMANTIC_ERROR", "field":"labl", "position": 0 }
```

- Unknown-field errors include a **suggestion** (Levenshtein-nearest registered field) — cheap and high-value for typos.
- Illegal operator: "operator '~' is not allowed on field 'status'".

### 7.3 What is an empty result vs an error

- A **valid, resolvable** query that matches no issues → **200 with `content: []`**. (e.g. `assignee = currentUser() AND status = "Done"` with nothing done.)
- A query naming a **non-existent** status/type/priority/user → **422** (typo protection), *not* empty. Rationale: silently returning zero rows for a misspelled value hides mistakes and is the #1 complaint about strict query UIs; a fast, specific error is better UX. (Open question §12.3 — some products prefer empty; we default to error.)

### 7.4 HTTP status summary

- 404 — not a workspace member (tenancy).
- 422 — parse error, semantic error, save-time HQL validation failure.
- 400 — malformed request envelope (missing `query` field, non-integer `page`).
- 200 — successful search (including empty result set).

---

## 8. API surface

### 8.1 `POST /api/workspaces/{ws}/search`

Request:
```json
{ "query": "status = \"In Progress\" AND assignee = currentUser() ORDER BY priority DESC",
  "page": 0, "size": 50 }
```
- `query` required (may be empty string). `page` default 0, `size` default 50, **max 100** (values above clamp to 100). `size < 1` → 400.
- Sort precedence: an `ORDER BY` in the HQL **wins**; if absent, default sort = `updated DESC, id DESC` (stable). There is no separate sort param — sorting is expressed in HQL (keeps one source of truth; the results-table header click rewrites the HQL's ORDER BY, §11).

Response `200` — a `PageResponse<SearchResultRow>`:
```json
{ "content": [ SearchResultRow, … ],
  "page":0, "size":50, "totalElements":137, "totalPages":3, "hasNext":true }
```

**`SearchResultRow`** — reuse `IssueResponse` **plus** `projectId`/`projectKey`/`projectName` (results are cross-project, so the row must self-identify its project). Recommendation: return the existing `IssueResponse` (it already carries `key = "DEMO-12"`, which embeds the project key) and add `projectId` + `projectName` at the row level:

```java
record SearchResultRow(IssueResponse issue, UUID projectId, String projectName) {}
```
The board/backlog already render `IssueResponse`; reusing it means the results table and issue drawer need no new mapping. `issue.key` already gives the human key; `projectName` is the only genuinely new datum for grouping/column display.

Fetch plan: reuse the `LEFT JOIN FETCH type/status/priority/assignee/reporter` pattern from `IssueRepository.findByProjectFiltered`, but issued through the **Criteria** query the compiler builds (add the same fetches to the `CriteriaQuery` root). Parent summaries and roll-ups are batched exactly as the board does (`parentSummaries`, `rollupByParentIds`) after the page is fetched — do **not** fetch-join parent (Cartesian). Count query is a separate `CriteriaQuery<Long>` with the same predicate but no fetches/sort.

### 8.2 `GET /api/workspaces/{ws}/search/schema`

Drives autocomplete. Returns the **static field/operator schema** plus **how to fetch value suggestions** (mostly static enough to embed):

```json
{
  "fields": [
    { "name":"status", "type":"ENUM_REF", "operators":["=","!=","IN"],
      "nullable":false, "valueSuggest":"STATUS" },
    { "name":"assignee", "type":"USER_REF", "operators":["=","!=","IN","IS EMPTY"],
      "nullable":true, "valueSuggest":"USER", "functions":["currentUser()"] },
    { "name":"created", "type":"TIMESTAMP", "operators":[">","<",">=","<=","=","!="],
      "nullable":false, "valueSuggest":"DATE", "functions":["now()","startOfWeek()"] },
    …
  ],
  "keywords":["AND","OR","NOT","IN","IS","EMPTY","ORDER BY","ASC","DESC"],
  "values": {
    "STATUS":   [ {"label":"To Do"},{"label":"In Progress"},{"label":"Done"} ],
    "PRIORITY": [ {"label":"Urgent"},… ],
    "TYPE":     [ {"label":"Bug"},{"label":"Task"},… ],
    "USER":     [ {"label":"Alex Kim","value":"alex@x.com"},… ]   // workspace members
  }
}
```

- `values.STATUS/TYPE/PRIORITY` = the **distinct names reachable by any visible project** in the workspace (deduped across bindings) — so the picklist matches what name-resolution will accept.
- `values.STATUS/TYPE/PRIORITY` are small and static → embedded in `/schema` and filtered client-side; one call per search session (cached in the store).
- `values.USER` is **not** embedded wholesale (workspaces can have thousands of members). `/schema` MAY include a small recent/self subset for instant suggestions, but user-value autocomplete is served by a bounded typeahead: `GET …/search/suggest?field=assignee&q=<prefix>` (server-side prefix match on displayName/email, capped result count). This is the resolution of §16.4. `DATE` has no picklist (calendar/functions).

### 8.3 Saved filters — `/api/workspaces/{ws}/filters`

```
GET    /api/workspaces/{ws}/filters                 # own + shared (in this ws)
POST   /api/workspaces/{ws}/filters                 # create (owner = caller)
GET    /api/workspaces/{ws}/filters/{id}            # own or shared
PATCH  /api/workspaces/{ws}/filters/{id}            # owner only (name/hql/shared)
DELETE /api/workspaces/{ws}/filters/{id}            # owner only (+ usage warning, §10.4)
```

- All gated by workspace membership (404 for non-members; 404 for a filter id not in this workspace — never 403, never reveal existence).
- `GET (list)` returns filters where `owner = caller OR shared = true`, this workspace only.
- Non-owner accessing a **private** filter by id → 404.
- Non-owner `PATCH`/`DELETE` (even on a shared filter) → 404 (hide existence) **or** 403 — **recommend 404** to match the tenancy rule (don't confirm the id exists).

**Create/Update request:**
```json
{ "name":"My in-progress", "hql":"assignee = currentUser() AND status = \"In Progress\"", "shared":false }
```
- `name` required, ≤ 120 chars, unique per (workspace, owner) — 409 on dup for the same owner (different owners may share a name).
- `hql` **validated at save time**: parsed + registry-validated + value-resolved against the workspace. A filter that won't parse/resolve is rejected **422** with the same error shape as search. (Caveat: `currentUser()` validates fine; a status name that later gets archived can make a stored filter fail at run time — that surfaces as a 422 when run, and the UI shows the error inline; acceptable.)
- `shared` default `false`.

**Response `SavedFilterResponse`:**
```json
{ "id":"…","name":"…","hql":"…","shared":false,
  "ownerId":"…","ownerName":"Alex Kim","mine":true,
  "createdAt":"…","updatedAt":"…" }
```

---

## 9. Security & edge cases

- **Scope can't be escaped:** compiler always ANDs `workspace = :ws AND project.id IN :visibleProjectIds` as the outermost predicate; parsed AST is nested *inside* it. `:ws` and `:visibleProjectIds` are bound server-side from the authenticated principal + membership, never from query text. A tenancy-review of the compiler is mandatory (this is the project's top bug class).
- **Injection:** impossible by construction — every literal becomes a `ParameterExpression`; no user text reaches SQL as a string. `ILIKE` patterns escape `%`/`_`. Field/operator names are matched against the registry (whitelist), never interpolated.
- **Shared-filter identity:** a shared filter runs as the **caller** — `currentUser()` and `:visibleProjectIds` resolve to the runner, so a shared filter can never expose issues in projects the runner couldn't otherwise read. **Highest-risk assumption — see §13.**
- **Parser DoS — depth & size limits (enforced in the lexer/parser, 422 PARSE_ERROR):**
  - Max query length: **2000 chars**.
  - Max parse/AST nesting depth: **32** (guards `((((…))))` stack blow-ups).
  - Max IN-list size: **200** values.
  - Max ORDER BY keys: **5**.
  - Max predicates (leaf comparisons) per query: **50**.
  Each limit → a specific 422 ("query too long", "expression nested too deeply", "IN list too large (max 200)").
- **ORDER BY on an unselected/uncorrelated field:** ORDER BY is restricted to **registered sortable fields** (all §5 fields except `text`); ordering by `text` or an unknown field → 422. Ordering by `priority` uses `position`; by `status`/`type` uses the catalog `position` of the current row (join already present). No arbitrary correlated subqueries.
- **Huge result / count cost:** `size` capped at 100; count is a dedicated predicate-only Criteria query. Offset pagination is fine for MVP volumes; keyset noted as future (§10.5).
- **Text search performance:** `ILIKE '%x%'` is a seq-scan-ish pattern; acceptable at current scale and always AND-ed with the workspace/project predicate (which is indexed, §10). `pg_trgm` GIN index is the documented upgrade path, not a blocker.
- **Empty query:** legal → all visible issues, default sort (still capped/paginated).
- **currentUser() when unauthenticated:** impossible (endpoint requires auth); resolver reads the security principal.

---

## 10. Data model & performance

### 10.1 New table (Flyway `V2`, continues from the squashed `V1` baseline)

```sql
CREATE TABLE saved_filters (
    id            UUID PRIMARY KEY,                         -- app-generated UUID v7
    workspace_id  UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    owner_id      UUID NOT NULL REFERENCES users(id),
    name          VARCHAR(120) NOT NULL,
    hql           VARCHAR(2000) NOT NULL,                   -- VARCHAR, not TEXT-unbounded; matches parser cap
    shared        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_saved_filter_owner_name UNIQUE (workspace_id, owner_id, name)
);
CREATE INDEX ix_saved_filters_ws_shared ON saved_filters (workspace_id, shared);
CREATE INDEX ix_saved_filters_owner     ON saved_filters (workspace_id, owner_id);
```
- No `CHAR(n)`, no PG ENUM (per CLAUDE.md gotchas). `VARCHAR` throughout.
- Entity `SavedFilter extends BaseEntity` (gets `id`/`createdAt`/`updatedAt` via Spring Data auditing; DB defaults are the raw-SQL safety net). `@Version` **not** required for MVP (last-write-wins on a personal filter is fine); add if concurrent edits become a concern.
- The **board/report usage hook** (HD-26) is *not* a new column yet — boards/reports don't consume filters in MVP. When they do, a `filter_id` FK is added to the future board/report tables; the delete-warning (§10.4) queries those. For MVP the DELETE endpoint returns a usage stub (always "not in use") so the frontend warning flow is built and ready.

### 10.2 No changes to `issues` schema

Search reads existing columns/associations. **Recommended supporting indexes** (composite, leading with the tenant column) — add in the same or a follow-up migration:
```sql
CREATE INDEX IF NOT EXISTS ix_issues_ws_project     ON issues (workspace_id, project_id);
CREATE INDEX IF NOT EXISTS ix_issues_ws_assignee    ON issues (workspace_id, assignee_id);
CREATE INDEX IF NOT EXISTS ix_issues_ws_status      ON issues (workspace_id, status_id);
CREATE INDEX IF NOT EXISTS ix_issues_ws_updated     ON issues (workspace_id, updated_at);
```
These back the always-present `workspace_id`+`project_id IN (…)` predicate and the default `updated` sort. `text ~` remains a scan within the (already-narrowed) tenant set; `pg_trgm` GIN on `title`/`description` is the future upgrade.

### 10.3 Fetch / query plan
- Main page: one Criteria `SELECT` with `fetch` on the five ToOne assocs + `IN`/predicate + `ORDER BY` + `LIMIT/OFFSET`.
- Count: one Criteria `SELECT COUNT` with the same predicate, no fetch/sort.
- Post-page batches (reuse existing repo methods): `parentSummaries(parentIds)` and `rollupByParentIds(parentIds)` — same N-avoidance the board uses.

### 10.4 Delete-with-usage warning (HD-26 acceptance)
`DELETE …/filters/{id}` behavior:
- If the filter is used by ≥1 board/report (future): return **409** with `{ detail, usedBy:[{type:"BOARD"|"REPORT", id, name}] }`; the client shows "Used by N places — delete anyway?" and re-issues `DELETE …?force=true`, which unbinds + deletes.
- MVP has no consumers, so the endpoint always deletes (204) and the `usedBy` array is empty — but the **contract and the client confirm-dialog are built now** so the future wiring is drop-in.

### 10.5 Pagination strategy
Offset/limit for MVP (bounded `size ≤ 100`, typical few pages). Keyset pagination on `(updated_at, id)` is the documented scale path if deep pages ever matter; not needed now.

---

## 11. Frontend impact (HD-25)

- **`api.ts`**: add `apiSearch(wsId, { query, page, size })`, `apiSearchSchema(wsId)`, and `savedFilters` group (`list/get/create/update/remove`). New types `SearchResultRow`, `SearchSchema`, `SavedFilter`, `HqlError` (with `position`/`length`/`errorType`).
- **`useSearchSchemaStore`** (or a TanStack query): fetch `/schema` once per workspace; feeds autocomplete.
- **Top bar (`TopSearchBar.tsx`)**: replace the stub `<input>` with an HQL input component. On Enter (or after a debounce) navigate to the results route with the query. The `HQL` chip stays. Autocomplete: a lightweight token-aware dropdown — after a field token suggest **operators**, after an operator suggest **values** (from schema `values.<suggest>` filtered locally), otherwise suggest **fields + keywords**. Inline error: on a 422 PARSE/SEMANTIC response, underline `[position, position+length)` and show `detail` beneath the input. No third-party query-editor lib; a controlled input + a positioned suggestion list is enough for MVP (matches DESIGN "Beacon", token-styled). Avoid the `max-w-*` shadow trap — use inline `maxWidth` for the dropdown.
- **New route `/w/:wsId/search`** (`pages/SearchResultsPage.tsx`) under `AppShell` (dark NavRail + light TopSearchBar): the HQL input pre-filled from the URL `?q=`, then a **results table**:
  - Columns: Key, Type, Summary, Status, Priority, Assignee, Project, Updated (default set); a **column chooser** (checkbox popover) to toggle/add Reporter, Due, Created, Parent. Column choice persisted in `localStorage` per user (like `recentProjects`).
  - Sortable headers: clicking a sortable column **rewrites the HQL `ORDER BY`** and re-runs (single source of truth). A small caret shows current sort.
  - Row click → opens the existing `IssueSidePanel` drawer (it already renders `IssueResponse`); the drawer must accept a cross-project row (pass `wsId` + `projectId` + `number` from the row).
  - Offset pager (Prev/Next + "N of M") from `PageResponse`.
  - **"Save as filter"** button → modal (name, shared toggle) → `POST …/filters`; on 422 show the inline HQL error.
- **Saved filters surface (HD-26)**: a "Filters" section (NavRail entry or a dropdown on the search page) listing own + shared filters; clicking one loads its HQL into the search input and runs it. Owner rows get edit/delete; shared rows owned by others are read-only. Delete → confirm dialog that shows the `usedBy` warning when non-empty.
- **DESIGN.md compliance**: Beacon tokens only (no hardcoded hex); mono font for keys/HQL chip; results table uses the existing dense-table styling from Backlog. Config-driven rendering — status/priority/type badges reuse `components/ui.tsx` (`PriorityBadge`, `StatusResponse` colors) so search rows look identical to the board.

---

## 12. DC/Cloud implications

**Minimal — confirm: no forked code, no new env vars, no cloud-only assumptions.** Search is pure app logic over existing Postgres data; the workspace-scope predicate is the same tenant boundary the whole app uses. In DC there's typically one workspace; in Cloud many — identical code path. `ILIKE` and Criteria work on any Postgres. No storage/email/auth/billing touchpoints. The only knob worth *reserving* (not shipping) is a future `app.search.max-page-size` / `max-query-depth` if an operator wants to tune DoS limits — **not** added in MVP (hardcoded constants are fine; if requested later they become properties with per-profile defaults). **Recommendation: no new env var in MVP.**

---

## 13. Highest-risk assumption (flagged)

> **That "workspace-member-scoped, all non-archived projects" is the correct visibility model for search** — decision #4's wording ("only projects the caller is a member of") implies *project*-membership scoping, but the existing `IssueService` grants any workspace member read access to every project. Shipping project-membership scoping in search alone would make search **more restrictive than the board/backlog**, confusing users ("I can open the board but search hides it"). This spec resolves the conflict by matching the existing issue-read surface (workspace-member scope) and isolating it behind `SearchScope.projectIdsFor(...)` so a future app-wide tightening changes one method. **If the intent is genuinely per-project visibility, that's an app-wide change (board/backlog/get included), not a search-only rule — confirm before build.** This is the single assumption most likely to be wrong.

---

## 14. Acceptance criteria

### HD-21 — Backend query engine
- [ ] `POST …/{ws}/search` with `status = "In Progress" AND assignee = currentUser() ORDER BY priority DESC` returns only the caller's in-progress issues across visible projects, priority-desc, paginated.
- [ ] Non-member of `{ws}` → **404**.
- [ ] Compiled query always contains `workspace = :ws AND project.id IN :visibleProjectIds`; a crafted query (`) OR 1=1 --`, unbalanced parens, `workspace = "other"`) cannot widen scope or break out of parameter binding (verified by a tenancy/security test).
- [ ] Unknown field → 422 SEMANTIC with a suggestion; illegal operator for a field → 422; unquoted value / unbalanced parens → 422 PARSE with correct `position`.
- [ ] Unresolvable status/user name → 422 (not empty); valid-but-no-match query → 200 `[]`.
- [ ] `IS EMPTY` / `IS NOT EMPTY` work on `assignee`/`parent`/`due`; error on non-nullable fields.
- [ ] `currentUser()`/`now()`/`startOfWeek()` resolve (UTC); date literal comparisons are inclusive per §6.3.
- [ ] Depth/length/IN-size limits enforced with specific 422s.
- [ ] Name→id resolution matches issues across multiple projects sharing a status/type/priority name.
- [ ] Result rows reuse `IssueResponse` + `projectId`/`projectName`; parent/rollup batched (no N+1).

### HD-25 — Frontend search + results
- [ ] Top-bar HQL input suggests fields, then operators, then legal values (from `/schema`); local filtering, no per-keystroke server call.
- [ ] Parse/semantic 422 underlines the error span at `position` and shows the message inline.
- [ ] Results page: sortable columns (header click rewrites ORDER BY), column chooser (persisted), row click opens the issue drawer, offset pager works.
- [ ] Results respect current-workspace scope (no cross-workspace rows).
- [ ] "Save as filter" creates a filter; a save-time HQL error shows inline.

### HD-26 — Saved filters
- [ ] Owner can create/edit/delete own filters; name unique per (workspace, owner).
- [ ] A **shared** filter is visible read-only to other workspace members; a **private** filter 404s for non-owners.
- [ ] Running a shared filter resolves `currentUser()`/visibility to the **runner**, not the owner.
- [ ] HQL validated at save time (422 on invalid).
- [ ] `DELETE` returns/handles a `usedBy` warning contract (empty in MVP); the confirm dialog is wired for the future board/report hook.
- [ ] All filter endpoints 404 (not 403) for non-members / cross-workspace ids.

---

## 15. Build & sequencing order

**HD-21 → HD-26 → HD-25**, with parallelism:

1. **HD-21 first** (blocks everything): `com.hamstrack.search` package — lexer, parser, AST, `FieldRegistry`, value resolvers, `SearchCompiler` (Criteria), `SearchScope`, `SearchService`, `SearchController` (`/search` + `/schema`), DTOs. Tenancy + security review before merge.
2. **HD-26 backend can start in parallel** once the parser+validator exist (it reuses them for save-time validation): `saved_filters` migration (V2), entity/repo/service/controller, delete-usage stub.
3. **HD-25 frontend** after `/search` + `/schema` return real data: api.ts, schema store, HQL input + autocomplete, results page. The **saved-filters frontend** (part of HD-26) follows HD-25's search input (it reuses the same input to load a filter's HQL).

Parallelizable: HD-26 backend alongside HD-21 tail; HD-25 table/columns can be scaffolded against a mocked `/search` response while HD-21 finishes.

---

## 16. Open questions — RESOLVED (signed off 2026-08-11)

1. **User values:** ✅ **email / displayName / `currentUser()` primary, raw UUID accepted as fallback.** displayName not unique → resolve via `IN` over all matches; unresolvable value → 422.
2. **Timezone:** ✅ **server UTC** for MVP (`now()`/`startOfWeek()`/date-literal boundaries). Per-user tz is future.
3. **Unresolvable value:** ✅ **422 with a positioned "unknown <field> '<value>'" message**, not silent-empty (typo protection).
4. **Autocomplete for large workspaces:** ✅ **prefix/typeahead lookup with a server-side limit**, not the whole member list embedded. MVP `/schema` may embed small static lists (statuses/types/priorities); member/user value suggestions use a bounded `GET …/search/suggest?field=assignee&q=` typeahead so large workspaces don't ship thousands of members per session.
5. **Non-owner mutating a shared filter → 404** (tenancy consistency). *(unchanged recommendation, accepted.)*
6. **Archived projects:** ✅ **excluded by default**; future `includeArchivedProjects` flag.
7. **Visibility model (§3.1, was the load-bearing one):** ✅ **workspace-member scope, matching existing issue reads**, injected via `SearchScope.scopePredicate(...)` as a **Criteria predicate builder (not an id list)** so the planned 3-layer access model (§3.1.2) lands as a one-method change. Signed off.

---

## 17. Docs follow-up (post-approval, per CLAUDE.md)

On build, `api-docs-sync` must add `POST …/search`, `GET …/search/schema`, and the `/filters` CRUD to `openapi.yaml` + both `docs/api-cloud.md` / `docs/api-dc.md` (DC section unchanged — no operator settings affect search in MVP), and validate with swagger-cli.
