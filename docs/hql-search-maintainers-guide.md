# HQL Search & Saved Filters — Maintainer's Guide

Handover doc for the Advanced Search feature (epic **HD-3**: HD-21 engine, HD-26 saved filters, HD-25 UI). Explains **how the code is built, where the core is, and what to change for which case.** The formal spec (grammar, decisions, edge cases) is `docs/design/advanced-search-hql-proposal.md` — this guide is the code-level companion.

> **Naming note:** the user-facing language is called **HQL** (Hamstrack Query Language). The Java package is `com.hamstrack.search`, deliberately **not** `hql`, to avoid confusion with Hibernate HQL. "HQL" appears only in user-facing text/grammar.

---

## 1. Mental model — the pipeline

A search is a straight pipeline. Everything flows one way; each stage has one job:

```
 raw query string
   │  Lexer.tokenize            → List<Token>            (search/parser/Lexer.java)
   │  HqlParser.parse           → Query AST              (search/parser/HqlParser.java, .parser.ast.*)
   │  HqlValidator.validate     → (throws on bad field/op/etc.)  (search/HqlValidator.java)
   │  ResolutionContextFactory  → ResolutionContext      (visible projects, name→id maps, members)
   │  HqlValueResolver /         resolve value tokens → ids / dates / text
   │  HqlParentResolver
   │  HqlCompiler.build*Query   → CriteriaQuery<Issue> / <Long>   (search/HqlCompiler.java)
   │      └── SearchScope.scopePredicate  ANDed OUTERMOST   (search/SearchScope.java)
   │  EntityManager             → List<Issue> + count
   ▼
 SearchService → PageResponse<SearchResultRow>   (search/SearchService.java)
```

The **frontend** (HD-25) never re-implements any of this: it renders board/forms/results purely from the `/schema` metadata and posts the raw HQL string to `/search`. The server parser is the single source of truth.

The two invariants that make the whole thing safe:
1. **Bound parameters only.** The compiler produces JPA Criteria; user text becomes bind parameters, never SQL string. Injection is impossible by construction.
2. **Scope is always outermost.** `SearchScope.scopePredicate` is ANDed as the outermost conjunction on both the page and the count query. A parsed query can only *narrow* within the workspace/visible-project boundary — never widen or remove it. **This is the #1 thing not to break.**

---

## 2. Backend file map (`com.hamstrack.search`)

| File | Role |
|---|---|
| `parser/Lexer.java`, `Token.java`, `TokenType.java` | Tokenize the string; positioned lexical errors. |
| `parser/HqlParser.java` | Recursive-descent/Pratt parser → AST. Static entry `HqlParser.parse(String)`. Enforces DoS caps (length 2000, depth 32, IN 200, 5 sort keys, 50 predicates). |
| `parser/ast/{Query,Expr,Value,OrderBy,ComparisonOp}.java` | The typed AST (sealed records). **Field-name-agnostic** — carries raw field identifiers; no semantics. |
| `parser/HqlParseException.java` | Parse error carrying `position`/`length`/`token`; `errorType = PARSE_ERROR`. |
| **`FieldRegistry.java`** | **The extension hub.** Maps HQL field name → `FieldDescriptor`. Drives validation, resolution, compilation, and `/schema`. Add a field here. |
| `FieldDescriptor.java`, `FieldDataType.java` | One field's declarative metadata (operators, nullable, sortable, `entityPath`, value source, functions, `available`). |
| `HqlValidator.java` | Semantic walk over the AST: unknown field, illegal operator for field, `IS EMPTY` on a non-nullable field, non-sortable `ORDER BY`, not-available field. Throws `HqlSemanticException`. |
| `ResolutionContext.java`, `ResolutionContextFactory.java` | Per-request state built **once** inside the tx: visible projects (from `SearchScope.visibleProjectIds`), status/type/priority name→id maps (via `ProjectConfigService`), member roster. |
| `HqlValueResolver.java`, `HqlParentResolver.java`, `ResolvedValue.java` | Turn value tokens into bound values: names→id-set, `currentUser()`→caller, `now()`/`startOfWeek()`→UTC instants, `"KEY-42"`→issue id. Unresolvable → `HqlSemanticException` (422). |
| **`HqlCompiler.java`** | **The pipeline heart.** Walks the validated AST → `CriteriaQuery`. Special-cases priority ranking, dates, text `~`. Always ANDs the scope predicate. |
| **`SearchScope.java`** | **The tenant boundary.** `scopePredicate(actor, ws, root, cb)` — a Criteria *predicate builder*, not an id list. Change visibility rules **here only**. |
| `SearchService.java` | Orchestrates: `resolveWorkspace` (membership → 404), parse→validate→compile→execute→map to `SearchResultRow` (reuses `IssueService.toResponsesBatched` for the ToOne/rollup batching), plus `schema()` and `suggest()`. |
| `SearchController.java` | `POST /search`, `GET /search/schema`, `GET /search/suggest`. `@Valid` request; `@Size` on params. |
| `dto/{SearchRequest,SearchResultRow,SearchSchemaResponse,SuggestResponse}.java` | Wire shapes. |
| `filter/**` | Saved filters (HD-26) — see §7. |

Migrations: `db/migration/V4__search_indexes.sql` (composite indexes leading with `workspace_id`), `V5__saved_filters.sql` (the `saved_filters` table). Error mapping lives in `common/exception/GlobalExceptionHandler.java` (`handleHqlParse`, `handleHqlSemantic`).

---

## 3. The core, in three files

If you only read three files, read these:

- **`FieldRegistry.java`** — the catalog of what's queryable. Its constructor registers every field as a `FieldDescriptor`. This decouples the public language from DB columns: `status` → `status.id`, `created` → `createdAt`, etc. It also powers autocomplete (`availableFields()`) and the "did you mean" typo hint (`suggest()`).
- **`HqlCompiler.java`** — how an AST node becomes a predicate. The `compile(Expr…)` switch is the map from grammar to Criteria. Note the deliberate special cases documented in-file: **priority** ranks by catalog `position` inverted for urgency; **dates** split DATE (`due`, direct) vs TIMESTAMP (`created`/`updated`, inclusive end-of-day UTC); **text** is `LOWER(title/description) LIKE %term%` with `%`/`_` escaped.
- **`SearchScope.java`** — the tenant boundary and the seam for the future access model (see §6, "Change who can see what").

---

## 4. The tenancy invariant (do not break)

`HqlCompiler.fullPredicate` is:

```java
Predicate scope = searchScope.scopePredicate(actor, ws, root, cb);
return query.filter().map(f -> cb.and(scope, compile(f, …))).orElse(scope);
```

Rules any change must preserve:
- `scopePredicate` is ANDed **outermost** on **both** `buildPageQuery` and `buildCountQuery` (they must never diverge in scope).
- Every value in the scope predicate is derived **server-side** from the authenticated actor and the resolved workspace — **never from query text**.
- No visible projects → the scope returns a *false* predicate (match nothing), never "all".
- Endpoints return **404 (not 403)** for a non-member (via `SearchService.resolveWorkspace`).

This was reviewed clean by `tenancy-reviewer`; re-run it after any change to `SearchScope`, `HqlCompiler`, `ResolutionContextFactory`, or the resolvers.

---

## 5. Error model

Two error classes, both HTTP **422**, both mapped in `GlobalExceptionHandler` to a `ProblemDetail` with extra props the SPA reads:

| Exception | `errorType` | Extra props | Raised by |
|---|---|---|---|
| `HqlParseException` | `PARSE_ERROR` | `position`, `length`, `token` | Lexer / parser (syntax) |
| `HqlSemanticException` | `SEMANTIC_ERROR` | `field`, `position` | Validator / resolvers (unknown field, illegal op, unresolvable value) |

Binding validation (query > 2000 chars, blank/oversized names) → **400** via `MethodArgumentNotValidException`. Saved-filter dup name → **409**. The SPA uses `position`+`length` to draw the wavy underline in the HQL input.

---

## 6. Cookbook — "what do I change for …?"

### Add a new queryable field (e.g. `sprint`, `component`, `labels`)
1. **`FieldRegistry`** — add one `register(new FieldDescriptor(...))` line: name, `FieldDataType`, allowed operators, `supportsIn`, `nullable`, `sortable`, the JPA `entityPath` from `Issue`, the `/schema` value-source token, functions, `available=true`.
2. If the field's value maps to an existing `FieldDataType` (ENUM_REF / USER_REF / DATE / TIMESTAMP / TEXT / NUMBER) and a simple `entityPath`, **you're done** — validation, compilation, autocomplete all pick it up for free.
3. If it needs new *resolution* (a new way to turn a token into a bound value), extend **`HqlValueResolver`** (and `ResolutionContextFactory` if it needs new per-request lookup data, e.g. a sprint name→id map).
4. If it needs a *bespoke predicate* (like priority's position ranking or text's LIKE), add a branch in **`HqlCompiler.comparison`** and, if sortable differently, in `sortPath`.
5. Add the column index if you'll filter on it a lot (a new `V#__*.sql` migration; lead with `workspace_id`).
6. Update `openapi.yaml` + `docs/api-*.md` (run `api-docs-sync`) and add a test in `SearchApiTest`.

### Turn on a reserved field (`label`, custom fields)
Flip `available` to `true` in its `FieldRegistry` entry and wire its resolution (steps 3–4 above). Until then it parses but 422s with "not yet queryable" — by design.

### Add an operator (e.g. `STARTS WITH`)
Touch, in order: `parser/ast/ComparisonOp` (enum + `symbol()`), `parser/Lexer` (tokenize it), `parser/HqlParser` (accept it), the operator sets in `FieldRegistry` (which fields allow it), and the `HqlCompiler.comparison` switch (how it compiles). Add parser unit tests + a `SearchApiTest`.

### Add a function (e.g. `currentSprint()`, `endOfMonth()`)
The parser already accepts any `name(args)` generically (`Value.FunctionCall`). Handle the new name in **`HqlValueResolver`**, and list it in the relevant fields' `functions` in `FieldRegistry` so autocomplete offers it.

### **Change who can see what** (the planned 3-layer access model)
Change **only `SearchScope`** — this is the whole reason scope is a predicate builder, not an id list:
- **Public/private projects** (layer 2): `visibleProjectIds` computes the actor-visible subset instead of "all non-archived".
- **Intra-project per-type grants** (layer 3): `scopePredicate` ANDs a further row-level clause (e.g. a correlated subquery against a grant table). The grammar, parser, AST, `FieldRegistry`, endpoints, and frontend are untouched. Ideally delegate to a shared issue-visibility component so board/backlog/search never diverge. See spec §3.1.2.

### Full-text search doesn't scale (`text ~`)
Today it's `ILIKE '%term%'` (`HqlCompiler.textMatch`). At scale, add a `pg_trgm` GIN index (a migration) or switch to `tsvector` full-text and rewrite `textMatch`. No grammar change.

### Move execution off Postgres (OpenSearch, someday)
The AST→compiler split exists exactly for this. Add a **second compiler target** (`AST → OpenSearch query DSL`) behind the same grammar, endpoints, and frontend; select it by config. The language/API/UI don't change. See spec §3a — but note: our fine-grained access model fits Postgres/Criteria far better than a denormalized index, so this is a deliberate *last* resort.

### Change result columns / pagination / sorting default
Result shape = `SearchResultRow` (+ `SearchService` mapping). Default sort + tie-break = `HqlCompiler.orderBy`. Sort is driven by the query's `ORDER BY` (single source of truth) — the frontend rewrites the clause, it does not sort client-side.

---

## 7. Saved filters (HD-26) — `com.hamstrack.search.filter`

A thin CRUD over stored HQL strings. It **stores and validates** HQL but never executes it (execution is always the HD-21 engine, which is injection-safe).

- **Entity/table:** `SavedFilter` / `saved_filters` (`workspace`, `owner`, `name`, `hql`, `shared`), `UNIQUE(workspace_id, owner_id, name)`.
- **Permissions** (`SavedFilterService`): `resolveWorkspace` (404 for non-member); reads = own **or** shared; mutations = **owner-only** (a non-owner, even on a shared filter, gets **404** — never 403).
- **Save-time validation:** `validateHql` = `HqlParser.parse` + `HqlValidator.validate` (structural only). Value resolution is **deliberately deferred** to run time — `currentUser()` and name→id are caller/time-relative, and an archived catalog row must not brick a stored filter.
- **Delete-usage hook:** `GET /filters/{id}/usage` returns an (empty for now) list — the drop-in point for a future "used by N boards/reports" warning.
- **API:** `/api/workspaces/{ws}/filters` CRUD. `SavedFilterNameBlankException` → 400, `…Conflict` → 409, `…NotFound` → 404.

---

## 8. Frontend (HD-25) — `src/main/frontend/src`

| File | Role |
|---|---|
| `api.ts` | `apiSearch`, `apiSearchSchema`, `apiSearchSuggest`, `savedFilters.{list,get,create,update,remove,usage}`. `ApiResponseError.hql` carries the 422 props (`errorType/position/length/token/field`) parsed by `hqlErrorOf()`. |
| `components/HqlInput.tsx` | Token-aware autocomplete + inline error underline. **Not** a grammar validator — it looks at the token under the caret + its left context and offers fields → operators → values from `/schema` (+ debounced `/suggest` for user fields). Error underline mirrors the text in a layer behind the input and draws a wavy underline over `[position, position+length)`. |
| `pages/SearchResultsPage.tsx` | Route `/w/:wsId/search?q=`. **Start view** when `q` param is *absent* (lists saved filters + examples, no auto-run); results table otherwise. Sortable headers **rewrite the `ORDER BY`** (via `hql.ts`) and re-run — no client sort. Row → `IssueSidePanel` drawer (fetches that row's project config, since results are cross-project). |
| `hql.ts` | `parseOrderBy` / `setOrderBy` / `nextSortDir` — pure ORDER BY string helpers for header-click sorting. |
| `components/SavedFiltersPanel.tsx` | "Saved filters" dropdown: own + shared; owner-only rename / share / **edit query** / delete. |
| `components/SaveFilterDialog.tsx` | "Save as filter" / (in edit mode) "Update '{name}'" vs "Save as new". |
| `components/NavRail.tsx` | The "Search" nav entry (resolves a wsId: current project → last-visited → first workspace). |

**Frontend traps specific to this feature:**
- **All string values must be quoted.** Autocomplete inserts values via `quoteValue` (always double-quotes, escaping `"`/`\`); functions like `currentUser()` are inserted unquoted. Any hardcoded example/placeholder must use quoted values (they're valid HQL) — an unquoted example errors when clicked.
- Render a filter's `name`/`hql` as **React text content only** — never `dangerouslySetInnerHTML` / an HTML-building highlighter (a shared filter is authored by another member → stored-XSS otherwise).
- Project-wide gotchas still apply: no Tailwind `max-w-2xs…3xl` (use inline `maxWidth`); absolute paths inside the splat route.

---

## 9. Tests & how to run them

- **Parser unit tests** (no DB): `src/test/java/com/hamstrack/search/parser/*` (Lexer/Parser/ParseError, ~48). Plain JUnit.
- **Engine integration:** `search/SearchApiTest.java` (MockMvc, needs Postgres) — canonical query, tenant isolation (cross-workspace `OR` can't widen scope), injection-as-literal, parse/semantic 422 with position, pagination, sorting, `IS EMPTY`, `~`.
- **Saved filters:** `search/filter/SavedFilterApiTest.java` (MockMvc) — CRUD, owner/shared/member 404-not-403, save-time validation, dup-name 409, blank-name 400.

Run (Postgres on :15432, creds `hamstrack`/`hamstrack`):
```
DB_URL=jdbc:postgresql://localhost:15432/hamstrack DB_USERNAME=hamstrack DB_PASSWORD=hamstrack \
JWT_SECRET=dev-only-jwt-secret-hamstrack-0123456789abcdef \
./mvnw.cmd -Dfrontend.skip=true -Dtest='com.hamstrack.search.**' test
```
(`.**` — `.*` doesn't match subpackages.) Frontend: `cd src/main/frontend && npm run build` (tsc + vite).

---

## 10. Gotchas — don't re-debug these

- **Priority is rank-inverted vs its `position` column.** Catalog `position` 0 = most urgent, but users expect `priority DESC` = most-urgent-first and `priority > "Low"` = "more urgent than Low". `HqlCompiler` flips the operator (`flipForUrgency`) and the sort direction for priority only. Don't "fix" this.
- **Every string value must be quoted** (grammar decision). Bare `Done` → parse error. The UI auto-quotes; keep examples quoted.
- **`quoteValue` escaping order:** backslash first, then quote (`replace(/\\/g,'\\\\').replace(/"/g,'\\"')`), or you double-escape.
- **Scope must stay a predicate builder** (not an id list) — that's what makes the future access model a one-method change.
- **Save-time validation is structural only** — don't add value-resolution at save (breaks `currentUser()` in shared filters and bricks filters when a catalog row is archived).
- **An empty-`hql` saved filter** ("all issues") clicked from the start view calls `runQuery("")`, which drops the `q` param and returns to the start view rather than showing all issues. Minor known edge; fix in `runQuery`/`loadFilter` if it ever matters.

---

## 11. Pointers

- **Spec / decisions / edge cases:** `docs/design/advanced-search-hql-proposal.md`.
- **API reference:** `openapi.yaml` (Search + Saved Filters tags) + `docs/api-cloud.md` / `docs/api-dc.md`. Keep them in sync on any API change (`api-docs-sync`).
- **Review before merge:** `tenancy-reviewer` + `security-officer` after any change to the compiler, scope, resolvers, or the filter service.
