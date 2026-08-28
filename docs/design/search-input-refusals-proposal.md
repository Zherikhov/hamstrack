# Search input refusals — a declared constraint refuses at the edge, in one shape, wherever it is written (HD-163 + HD-214)

**Status:** proposal / design review. **Date:** 2026-08-28. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness).
**Migration:** none. **New configuration property:** none. **New environment variable:** none.
**Profile gating:** none — see §10.
**Related:** HD-3 (shipped `SearchController`'s `@Validated` and the `@Size` on `q` in one commit),
HD-171 (`RequestFieldLengthBoundTest`, the two `token` request-param bounds, and the `errors` map the
SPA now reads — ADR-0017), HD-13 (`handleDataIntegrityViolation`, the "backstop, not the message"
doctrine this spec reuses), HD-151 (the "a clean 4xx removes the operator's only signal" doctrine),
HD-140 R6 (`SearchRateLimitConfig`, which is why `/search/suggest` is on a budget at all).
**Touches:** `SearchController` (drop `@Validated`, bound `field`), `search/dto/SearchRequest`
(`@Max` on `page`), `common/dto/Paging` (`MAX_PAGE`), five paged controller methods
(`IssueController` ×3, `SprintController`, `AdminUserController`), `GlobalExceptionHandler`
(one new handler + one backstop + a shared renderer + two javadoc corrections),
`openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md`, tests.
**No application code is written by this document.**

---

## 0. Why these two tickets are one piece of work

HD-163 and HD-214 are the same defect twice on the same controller: **input the caller supplied,
unbounded, answering `500` where a documented `400` belongs.** They are not merely adjacent — the fix
for one *decides* the fix for the other:

- HD-214's recommended remedy is to delete `@Validated` from `SearchController`. That annotation is
  what routes a parameter constraint through the AOP proxy instead of Spring MVC.
- HD-163's recommended remedy is a *declarative* bound (`@Max`) rather than a service-side clamp.
  Whether a declarative bound is even usable on this controller depends on which validation mechanism
  is live, which is exactly what HD-214 changes.

So one spec, one branch, one set of tests. Below, HD-163 is called **the page defect** and HD-214
**the parameter defect**.

### One premise of the brief is stale, and it changes nothing but should be recorded

The brief says the claim "only `SearchController` carries `@Validated`" was true when HD-171 checked.
It is **still true of controllers** and **was never true of the tree**: ten `@ConfigurationProperties`
classes carry it (§2.3). That is a different mechanism (boot-time binding validation, fail-fast) and
is correct there. The rule this spec adopts is therefore scoped by *category* — "no `@Validated` on a
bean Spring MVC dispatches to" — not "no `@Validated` anywhere", and the guarding test must be scoped
the same way or it will fail on ten correct classes.

---

## 1. Problem & goal

A member of a workspace can make `POST /api/workspaces/{ws}/search` and
`GET /api/workspaces/{ws}/search/suggest` answer **HTTP 500 with a stack trace** by sending values
that are syntactically well-formed and semantically out of range: a page index large enough to
overflow the `int` offset multiplication, or a `q` longer than the `@Size(max = 100)` that is written
on the parameter and does not fire. Neither is a data-integrity or authorization problem — both
requests are correctly refused; the API just reports the refusal as a crash, spends a slot of the
120/min search budget doing it, and writes an ERROR line an operator has to triage.

**Success** is: every value the caller supplies to the search surface is refused at the request edge,
with a documented `400` that names the offending field in the same body shape the SPA already reads
for request-body validation, **before any query runs**; and the two sweeps (§3, §4) leave no sibling
surface carrying the same shape unbounded or unreported.

---

## 2. Scope

### 2.1 In scope

1. Remove `@Validated` from `SearchController`; seal the rule with a category test (§5).
2. Bound `SearchRequest.page` declaratively so the `int` offset cannot overflow (§6).
3. Bound the page index on **every** other endpoint that takes one from the caller (§3).
4. Bound `SearchController.suggest`'s `field` parameter, which is unbounded today and feeds a
   Levenshtein scan over the whole field registry (§4.4).
5. Make a parameter-constraint refusal carry the same `{detail, errors}` body a body-constraint
   refusal carries (§7), and add the `jakarta.validation.ConstraintViolationException` backstop (§7.3).
6. Both sweeps, as tables, with a verdict per row (§3, §4). **These are the deliverable**, not an
   appendix.
7. `openapi.yaml` + `docs/api-*.md` updated for every changed status/bound/body shape (§8).

### 2.2 Out of scope (reported in §9, not designed here)

- Deep-offset cost. A page index of 21 million is arithmetically legal after this change and would
  make PostgreSQL walk two billion rows; it is already refused by the statement budget
  (`BoundedJpaTransactionManager` → 422, HD-151). Tightening the ceiling to a *product-sensible*
  number is a behavioural change that can break a caller and needs its own ticket.
- Whether a negative `page` should be **refused** rather than coerced to 0 (§11, Q1).
- `SearchService`'s duplicate copy of `Paging`'s clamp (§9.3).
- Any change to how `size` is handled. `size` is clamped in both implementations and is never the
  overflow factor; the index is.

### 2.3 Non-goals

- Removing `@Validated` from `@ConfigurationProperties` classes. There it is the *correct*
  mechanism — startup binding validation, "fail fast, never clamp", which those classes' own javadoc
  already argues for. Ten classes carry it and all ten keep it.
- Introducing an `errorType` discriminator on validation `400`s. No validation refusal in the product
  carries one; inventing one here would make this the odd surface out.

---

## 3. Sweep A — every surface that takes a page index from the caller

**Method.** Every call site that reaches `Paging.of(...)` or sets a JPA `firstResult` from a
request-supplied value. Phrased as a category on purpose: the row set is "a request value that becomes
an offset", not a list of the six endpoints that exist today.

`Paging` clamps **`size`** to `[1, 100]` and coerces a negative **index** to 0. It puts no ceiling on
the index — that is the entire gap.

| # | Endpoint | Index arrives as | Index bounded? | Offset arithmetic | Refusal before a query? | Verdict |
|---|----------|------------------|----------------|-------------------|-------------------------|---------|
| A1 | `POST /api/workspaces/{ws}/search` | `SearchRequest.page` (body) | **No** | **`int`** — `SearchService:103` `page * size` | **No** — the count query at `:99` has already run | **DEFECT (HD-163).** Overflows to a negative `firstResult`; Hibernate raises `IllegalArgumentException`; nothing handles it → **500**, after paying for a full count query. **Observed** (2026-08-28): `500`, `java.lang.IllegalArgumentException: First result cannot be negative` |
| A2 | `GET /api/workspaces/{ws}/projects/{p}/issues` | `@RequestParam Integer page` → `Paging.of` | **No** | `long` (`AbstractPageRequest.getOffset()`), narrowed by Spring Data JPA's offset conversion, which **refuses** an offset above `Integer.MAX_VALUE` rather than truncating | Yes — raised while the query is being built | **DEFECT (same class).** No overflow and no wasted query, but still an unhandled exception → **500**. **Observed** (2026-08-28): `500`, `org.springframework.dao.InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE (2147483647)` — the conversion **refuses**, as read |
| A3 | `GET …/projects/{p}/issues/{number}/history` | same | **No** | same | Yes | **DEFECT** — as A2. **Observed:** identical `500` / `InvalidDataAccessApiUsageException` |
| A4 | `GET …/projects/{p}/issues/{number}/comments` | same | **No** | same | Yes | **DEFECT** — as A2. **Observed:** identical `500` / `InvalidDataAccessApiUsageException` |
| A5 | `GET …/projects/{p}/sprints` | same | **No** | same | Yes | **DEFECT** — as A2. **Observed:** identical `500` / `InvalidDataAccessApiUsageException` |
| A6 | `GET /api/admin/users` | same | **No** | same | Yes | **DEFECT** — as A2. Not workspace-scoped (behind `/api/admin/**`, system role ADMIN); same fix. **Observed:** identical `500` / `InvalidDataAccessApiUsageException` |

**Six client-controlled page indexes. All six unbounded. One of them (A1) additionally computes the
offset in `int`.** The `int` multiplication is the part that is unique to search; the missing ceiling
is the part all six share, and it is the part the fix addresses uniformly.

### 3.1 The surfaces that do NOT appear above, and why the distinction is structural

Everything else that pages passes a **server-supplied** index of `0` and a server-chosen limit, so no
caller value ever reaches an offset:

- board / backlog section fetches — `PageRequest.of(0, cap + 1)`;
- the notification inbox — `PageRequest.of(0, 30)`;
- the label / component / version / member typeaheads — `PageRequest.of(0, limit)` with `limit` a
  service constant (`SUGGEST_LIMIT`, the picklist caps);
- velocity's sprint sample — `PageRequest.of(0, sprints)` with `sprints` **refused** at both ends
  (1…12) by `VelocityService.validated` before the repository is touched;
- the backlog rank neighbour lookups — `PageRequest.of(0, 1)`.

The category that matters is therefore: **a page index is a hazard exactly when a request supplies
it.** A surface that fixes the index at 0 has no offset to overflow however large its limit is, and a
surface that lets the caller name the limit but not the index (velocity) is bounded by the limit's own
refusal.

### 3.2 The one reading in this table that was not executed — now executed

**Executed 2026-08-28** against a running instance (Boot 4.1.0 / Spring 7.0.8 / Spring Data JPA), every
row hit with `page = MAX_PAGE + 1 = 21474837` and `size = 100`. **All six answered 500, and the reading
held: Spring Data's offset conversion refuses, it does not truncate.** A2–A6 raised
`InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE (2147483647)`; A1 raised
`IllegalArgumentException: First result cannot be negative`, the `int` overflow unique to search. So
the worse of the two possible worlds — a *silently wrong page* — was never live, and the fix is the one
this document specifies.

The original paragraph, kept because the reason it was written outlives its answer: the verdict
"**refuses** rather than truncating" for A2–A6 was read from Spring Data JPA's offset conversion, not
observed, and was the highest-risk assumption in the document. Had it truncated, A2–A6 would have been
answering a *silently wrong page* rather than a 500 — a worse defect, not a smaller one — while the fix
stayed identical, because a refusal at the edge pre-empts both outcomes. **A claim whose two possible
answers imply the same code change still has to be run**, because they imply very different severities,
and severity is what a release decision reads.

---

## 4. Sweep B — every parameter-level constraint annotation in the tree

**Method.** Every Bean Validation constraint annotation (`@Size`, `@Min`, `@Max`, `@Pattern`,
`@NotBlank`, `@NotNull`, `@NotEmpty`, `@Positive`, `@Email`, …) written directly on a
`@RequestParam` / `@PathVariable` / `@RequestHeader` / `@CookieValue` under `src/main/java`. This is
the category the parameter defect belongs to. `@Valid @RequestBody` is a different mechanism
(argument-resolver validation, §7.1) and is not in this table.

| # | Site | Constraint | Class carries `@Validated`? | Exception raised today | Status today | Status after |
|---|------|-----------|-----------------------------|------------------------|--------------|--------------|
| B1 | `AuthController.verifyEmailLink` — `token` | `@Size(max = 64)` | No | `HandlerMethodValidationException` | **400**, generic `detail`, **no `errors` map** | 400, `detail` names `token`, `errors` map |
| B2 | `WorkspaceController.acceptInvite` — `token` | `@Size(max = 64)` | No | `HandlerMethodValidationException` | **400**, same shape | 400, `detail` names `token`, `errors` map |
| B3 | `SearchController.suggest` — `q` | `@Size(max = 100)` | **Yes** | `jakarta.validation.ConstraintViolationException` | **500** | 400, `detail` names `q`, `errors` map |
| B4 | `SearchController.suggest` — `field` | **none today** | Yes | — (no refusal; see §4.4) | 422 after ~7M int ops, or 200 | 400 once `@Size(max = 100)` is added |

**Exactly one of the constrained parameters above answers 500, and it is the only one whose class
carries `@Validated`** — a one-to-one correspondence, which is what makes the mechanism the cause
rather than a coincidence.

> **A count here would have gone stale inside its own ticket.** An earlier draft said "three today,
> four after". The five `@Max(Paging.MAX_PAGE)` page bounds this same ticket adds are parameter-level
> constraints by this table's own definition, so the real figure after the change is **nine**, and
> `ParameterConstraintSweepTest`'s tripwire is set there — a floor of four would not have fired until
> five sites had silently vanished. The table above enumerates what was *found*; the sweep test, not
> this prose, is what stays true.

### 4.1 The larger category that is already correct, and must not be disturbed

Most `@RequestParam` / `@PathVariable` values in the product carry **no** constraint annotation and
need none, because their *type* is the bound: a `UUID`, an enum (`SprintState`, `LabelMatch`), an
`Integer`, a `long` path variable. A malformed value fails Spring's binding with
`MethodArgumentTypeMismatchException`, which Boot's advice already renders as **400** — independent of
`@Validated` entirely. Nothing in this spec touches that path, and it is worth naming so the next
reader does not conclude that every parameter needs an annotation: **a parameter whose type already
refuses the value has nothing left to declare.**

### 4.2 What removing `@Validated` does to each member of `SearchController`

The failure mode to rule out is a constraint that *silently stops being enforced*. Enumerated
exhaustively:

| Member | Constrained today? | With `@Validated` (today) | Without `@Validated` (proposed) |
|--------|--------------------|---------------------------|--------------------------------|
| class level | — | AOP proxy created; method validation deferred to it | No proxy for this bean. Nothing else on the class needs one: no `@Transactional`, no `@Cacheable`, no `@Async`, no method-level validation groups |
| `search(...)` — `@AuthenticationPrincipal User` | no | not validated | not validated |
| `search(...)` — `@PathVariable UUID workspaceId` | no | type-bound only | type-bound only |
| `search(...)` — `@Valid @RequestBody SearchRequest` | yes, on the DTO's fields | Validated by `RequestResponseBodyMethodProcessor` during **argument resolution**, which precedes handler invocation — so `MethodArgumentNotValidException` → `handleValidation` → 400 + `errors`. The AOP proxy is never reached on an invalid body | **Identical.** Argument-resolver validation does not depend on `@Validated`. One redundant re-validation on the *valid* path disappears |
| `schema(...)` | no constraints anywhere | nothing to validate | nothing to validate |
| `suggest(...)` — `field` | no (B4 adds `@Size`) | — | MVC method validation → `HandlerMethodValidationException` → 400 |
| `suggest(...)` — `q` | `@Size(max = 100)` | AOP proxy → `jakarta.validation.ConstraintViolationException` → **500** | MVC method validation → `HandlerMethodValidationException` → **400** |
| every return type | no constraint declared on any | return-value validation available but idle | not available, and nothing uses it |

**Ruling: removing `@Validated` refuses strictly more, never less.** The proxy enforces exactly one
constraint today (`q`'s `@Size`) and Spring MVC's built-in method validation enforces the same
annotation on the same parameter — the annotation set does not change, only which component reads it
and what it throws. The single capability `@Validated` has that MVC method validation does not is
**return-value** validation, and no method on this class declares a constraint on a return type, so
nothing is given up. The body-validation path is untouched because it never ran through the proxy in
the first place.

### 4.3 Why not add a `ConstraintViolationException` handler *instead*

That would make `@Validated` "work" and is the wrong repair, for three reasons in descending strength:

1. **It leaves three controllers behaving differently for the same annotation.** A `@Size` on a
   `@RequestParam` would raise `HandlerMethodValidationException` on `AuthController` /
   `WorkspaceController` and `ConstraintViolationException` on `SearchController`. Two exception types,
   two handlers, one rule — and the next person copying a bound onto a parameter gets whichever
   behaviour the class they copied into happens to have. Both existing sites already carry a comment
   saying *do not add `@Validated`*; a handler that quietly makes it survivable is a comment nobody
   will obey.
2. **It preserves a trap whose cost is a 500.** The annotation would keep looking like the thing that
   *enables* parameter validation while being the thing that breaks it.
3. **It is strictly more code for a strictly worse guarantee.**

A `ConstraintViolationException` handler **should still exist**, as a backstop rather than as the
mechanism — see §7.3, including the simple-name collision it must avoid.

### 4.4 `field` is unbounded, and the cost is asymmetric with `q`'s

`suggest`'s `field` carries no constraint. On an unknown name, `FieldResolver.resolve` falls through to
`FieldRegistry.suggest`, which runs Levenshtein against **every** registry entry — an O(|field| × |name|)
scan per candidate. Tomcat's ~8 KB request-line limit is the only thing bounding the input, so a single
authenticated request can buy roughly seven million integer operations, on a surface whose budget is
per-minute.

It is also *two* wasted round trips before that: `suggest` resolves the workspace and builds a full
`ResolutionContext` (≈8 statements, including a workspace-wide label projection and a member scan)
**before** it ever looks at the name. So an over-long `field` costs the context build *and* the scan
and then answers 422.

**Bound: `@Size(max = 100)`.** Derived, not chosen: the widest name that can legitimately resolve is a
tenant custom field's key, and `field_defs.key` is `VARCHAR(50)`. Every system name in `FieldRegistry`
is far shorter. 100 is double the widest resolvable value — generous enough that no legitimate caller
can meet it and small enough that the Levenshtein input is bounded by construction. A numeric literal,
per ADR-0017.

---

## 5. Actors, permissions and tenancy

**Actors.** Any authenticated user for the search surface (workspace membership is verified inside the
service); a system ADMIN for A6. No permission from the `Permission` catalog is added, removed or
re-checked by this work — a refusal happens *before* authorization, and that is the point of §5.1.

**Nothing in this spec changes who may do what.** It changes only which HTTP status an already-refused
request receives.

### 5.1 A refusal at the edge must not become an existence oracle

Search is workspace-scoped, and every constraint added here fires during argument resolution —
**before** `WorkspaceAccessService.requireMember` runs. So a member, a non-member and the sender of a
random workspace id all receive the **same 400** for an over-long `q` or an out-of-range `page`.

That is correct, and it is correct for one reason that must be stated as a rule rather than as an
observation: **every constraint on a workspace-scoped request is a pure function of the request.**
`@Size` and `@Max` read only the submitted value; they cannot consult the database, so their answer
cannot differ between a member and a stranger. The moment a constraint on such an endpoint asks the
database anything ("is this a real project?", "is this address already a member?"), the identical 400
silently becomes a membership oracle, and the "404 for both non-existence and non-membership" rule is
defeated without a single line of it being edited. ADR-0017 records the same prohibition for
`@Valid @RequestBody`; this spec extends it verbatim to parameter constraints, and AC-9 seals it.

A well-formed request from a non-member still answers **404**, unchanged.

---

## 6. Where the page bound belongs, and what the number is

### 6.1 The invariant

```
(long) page * size  ≤  Integer.MAX_VALUE
```

`size` is clamped server-side to `[1, Paging.MAX_SIZE]` (= 100) in both implementations, so the worst
case is `size = 100`, and a fixed ceiling on `page` that holds at `size = 100` holds for every smaller
size. That is how the interaction between a fixed `@Max` and a variable `size` is resolved: **the bound
is written for the largest `size` the server will ever accept, and `size` is not caller-controlled past
its own clamp.**

### 6.2 The number

```java
// common/dto/Paging
public static final int MAX_PAGE = Integer.MAX_VALUE / MAX_SIZE;   // 21_474_836
```

- `21_474_836 × 100 = 2_147_483_600 ≤ 2_147_483_647` ✔
- `21_474_837 × 100 = 2_147_483_700 > 2_147_483_647` ✘

Both boundary values are exact and derivable, which is what AC-4 needs: the test pins `MAX_PAGE`
(accepted) and `MAX_PAGE + 1` (refused), plus the derivation itself — that `(long) MAX_PAGE * MAX_SIZE`
fits an `int` and `(long) (MAX_PAGE + 1) * MAX_SIZE` does not. A test written against `2_000_000_000`
would prove nothing about the edge and would keep passing if the constant drifted.

`Integer.MAX_VALUE / MAX_SIZE` is a compile-time constant expression (both operands are `static final`
compile-time constants), so it is legal as an annotation argument and it **follows `MAX_SIZE`**: if the
page-size cap is ever raised, the page ceiling narrows with it and the invariant survives without a
second edit. That is the one place a symbolic constant beats the literal ADR-0017 mandates, and the
divergence is deliberate: ADR-0017's literal rule exists because `EmailLengthBoundTest` regex-scans
`@Size(max = …)` for digits. No scanner reads `@Max`, and the value here is a *derived invariant*
rather than a column width, so the reason for the literal does not apply. **The javadoc on `MAX_PAGE`
must say this**, or the next reader will "fix" it into a literal and break the derivation.

### 6.3 DTO / parameter bound, not a service-side clamp

The brief's criterion is *no query runs before the refusal*, and it decides this on its own:

- **A clamp inside `SearchService` cannot satisfy it as the method is written.** The count query runs
  at `:99`; `setFirstResult` is at `:103`. A clamp there refuses (or silently rewrites) *after* the
  expensive half of the request has already been paid for — which is the specific complaint in HD-163's
  own text.
- **A service-side *refusal* moved up to `:95` would satisfy the criterion and still loses**, on three
  counts: it duplicates a rule the DTO expresses declaratively; it produces a bare
  `ResponseStatusException` with **no `errors` map** (the `sprints` shape — correct for a cross-field
  business rule, wrong for a single-field range check that the SPA is now taught to read structurally);
  and it does not extend to A2–A6 at all, where the index never reaches a Hamstrack service —
  `Paging.of` is called in the controller.
- **The declarative bound covers both halves of Sweep A with one mechanism**, refuses during argument
  resolution (before the handler body, therefore before every query and before membership resolution),
  costs nothing at runtime on the happy path, and lands in `openapi.yaml` as a schema `maximum` that
  clients can read.

**Ruling: `@Max` at the edge, on all six.**

- A1 — on the DTO: `@Max(Paging.MAX_PAGE) Integer page` in `SearchRequest`.
  Refusal: `MethodArgumentNotValidException` → `handleValidation` → 400 with `errors: {"page": …}`.
- A2–A6 — on the parameter: `@RequestParam(required = false) @Max(Paging.MAX_PAGE) Integer page`.
  Refusal: `HandlerMethodValidationException` → the new handler (§7.2) → 400 with `errors: {"page": …}`.

Two exception types, **one body shape** — which is exactly what §7 exists to guarantee. Note also that
none of the five paged handlers takes a `@RequestBody`, so adding a parameter constraint to them cannot
interact with body validation.

`SearchService` keeps its `page < 0 → 0` coercion and its `size` clamp unchanged (§11, Q1). The
`int` multiplication at `:103` may stay as it is once the bound exists; changing it to `(long)` as well
is harmless belt-and-braces and is **not** a substitute for the bound, because a negative or truncated
`firstResult` is not the failure we are removing — an unbounded request is.

---

## 7. What the refusal says

### 7.1 What the two handlers actually render today — verified, not inherited

They are **different handlers producing different bodies**, and the API docs' current claim is correct
but phrased about two members.

| | Raised by | Handled by | `detail` | `errors` map |
|---|---|---|---|---|
| `MethodArgumentNotValidException` | `@Valid @RequestBody` failing during argument resolution | **our** `GlobalExceptionHandler.handleValidation` | Entries joined `"; "`, each `"<field>: <message>"`, capped at 10 with `"; … and N more"` | **Yes** — `{field: message}`, same entries, same order |
| `HandlerMethodValidationException` | a constraint on a `@RequestParam`/`@PathVariable`, when the class carries no `@Validated` | Boot's `ProblemDetailsExceptionHandler` (it is on `ResponseEntityExceptionHandler`'s declared list) | Spring's generic sentence — no field name, no value | **No** |
| `jakarta.validation.ConstraintViolationException` | a constraint on a parameter of a bean carrying `@Validated` | **nothing** | — | — → **500** |

So `docs/api-cloud.md:142` ("A refusal on a query parameter carries no `errors` map … the `token` on
the verification link and on `POST /workspaces/accept-invite`") is **true today**, and is written as a
claim about two members of a category that is about to gain a third and a fourth. It is also
incomplete in the way a member-claim always is: it does not mention that the third constrained
parameter in the tree answers 500 rather than 400.

### 7.2 Decision — take `HandlerMethodValidationException` over, and render one shape

Add to `GlobalExceptionHandler`:

```
@ExceptionHandler(HandlerMethodValidationException.class)
→ 400, ProblemDetail with the SAME detail rendering and the SAME `errors` extension
   as handleValidation, keyed by the PARAMETER NAME.
```

Rationale, in order:

1. HD-171 taught the SPA to branch on `errors` structurally rather than pattern-matching English
   (`ResetPasswordPage` distinguishes an expired `token` from a rejected `newPassword` that way). A
   `400` that names nothing forces the client back to prose exactly where it was taught not to be.
2. It collapses the special case in the docs into a **category claim**: *every 400 raised by a declared
   constraint carries an `errors` map keyed by the name of the thing that was refused, whether that
   thing is a body field or a request parameter.* That sentence does not go stale when a fifth
   constrained parameter appears.
3. It is the difference between a client being able to say "your `page` is too large" beside the
   control and "Validation failure" in a banner.

**Two mandatory cautions, both of which this class already documents about itself:**

- `HandlerMethodValidationException` **is** on `ResponseEntityExceptionHandler`'s declared list, so —
  per the class-level javadoc's *"What else the precedence change moved"* paragraph — adding this
  handler changes that exception's response body **app-wide**, not just on search. The affected rows
  are B1 and B2, and both strictly improve (a named field where there was none). That paragraph must be
  updated in the same commit; leaving it saying `MaxUploadSizeExceededException` is the only such case
  reproduces the exact defect CLAUDE.md's "a claim about a category outlives a claim about a member"
  rule was written for.
- **The rendering must be shared, not copied.** `handleValidation`'s sort, its `MAX_REPORTED_ERRORS`
  cap, its `"; … and N more"` overflow line and its `render`-prefix rule are a contract the docs
  describe in four bullets. Two copies of it will drift, and the drift is invisible (both produce a
  400). Extract one private renderer taking an ordered `Map<String, String>` and have both handlers
  call it.

**Parameter names.** Keys come from `MethodParameter#getParameterName()`, which requires `-parameters`;
`spring-boot-starter-parent` sets it, and this project uses that parent. Fall back to a positional key
if a name is ever absent, so the map can never be silently empty. `HandlerMethodValidationException`
also carries nested results for a cascaded parameter object — degrade those to the same
`{name: message}` shape rather than omitting them.

### 7.3 The `ConstraintViolationException` backstop — yes, and it must be fully qualified

Add, separately from §7.2:

```
@ExceptionHandler(jakarta.validation.ConstraintViolationException.class)  →  400 + log.error
```

- **Fully qualified, always.** `GlobalExceptionHandler` already binds
  `org.hibernate.exception.ConstraintViolationException` in `handleDataIntegrityViolation`, and
  `sqlStateOf`'s javadoc explicitly records that these two share a simple name, are unrelated types,
  and that a bare import shadows the other. An unqualified reference here is a one-character way to
  silently rebind the data-integrity handler. That javadoc paragraph — which today says this type
  *"would fall to the unchanged 500"* and is *"latent rather than live"* — becomes wrong on the day
  this ships and must be rewritten, not left.
- **Backstop, not the message** — the same doctrine as `handleDateTime`, `handleQueryTimeout` and the
  `22001` branch. After §7.2 nothing in the tree should be able to raise it, so an occurrence means a
  new web bean has acquired `@Validated` (which AC-6's test should have caught first) or an entity has
  gained Bean Validation annotations. Both are worth an **ERROR** line naming both possibilities;
  answering a clean 400 without one deletes the only signal an operator gets.
- Same body shape as §7.2: `errors` keyed by the **last node** of each violation's property path, so a
  client sees `q` rather than `suggest.q`.
- **Trade-off to record.** If an entity ever carries Bean Validation annotations, Hibernate's
  pre-insert listener raises this same type for a *server-side* failure, and this handler would report
  it as a client error. No entity in the tree carries them today. If that changes, the right move is a
  dedicated handler for that path, not widening or narrowing this one — and the ERROR line is what
  will make it visible.

### 7.4 Message wording

Bean Validation's default English messages are used unchanged (`must be less than or equal to
21474836`, `size must be between 0 and 100`). Rationale: `docs/api-cloud.md` already pins messages to
English by design and instructs clients to match on `errors` **keys**, not text. A custom message per
bound would be four more strings to keep in sync with `openapi.yaml` for no client-visible gain.

---

## 8. Data model, API surface, frontend, DC/Cloud

### 8.1 Data model impact — none

No table, no column, no migration, no `@Entity` change. `ddl-auto=validate` is not involved. (Worth
naming explicitly, because CLAUDE.md's newest gotcha — that `validate` does not compare column widths —
is about a neighbouring class of bug and might otherwise be assumed relevant here. It is not: nothing
in this work reaches a column.)

### 8.2 API surface

No endpoint is added, removed or moved. What changes is the **status** of already-refused requests and
the **body** of already-400 refusals.

| Endpoint | Change |
|---|---|
| `POST …/search` | `page > 21474836` → **400** (was 500), `errors: {"page": …}` |
| `GET …/search/suggest` | `q` longer than 100 → **400** (was 500), `errors: {"q": …}`; `field` longer than 100 → **400** (was a 422 after a full context build + registry scan) |
| A2–A6 | `page > 21474836` → **400** (was 500), `errors: {"page": …}` |
| B1, B2 | unchanged status (400); body gains `detail` naming `token` and an `errors` map |

`openapi.yaml`:
- `components/parameters/pageParam` gains `maximum: 21474836` and a description sentence
  ("refused, not clamped, above this — the offset must fit a 32-bit integer at the maximum page size");
- `components/schemas/SearchRequest.page` gains the same `maximum` and sentence;
- `/search/suggest`'s `field` gains `maxLength: 100`; `q`'s existing `maxLength: 100` gains a documented
  `400` (it currently documents no refusal because there was none to document);
- every affected operation lists `400` where it did not.

`docs/api-cloud.md` / `docs/api-dc.md`:
- the "Pagination" convention bullet (~L89) gains the page ceiling beside the existing size clamp;
- **the query-parameter bullet (~L142) is inverted and re-phrased as a category**: every `400` raised
  by a declared constraint — body field or request parameter — carries an `errors` map keyed by the
  name of the refused item. The paragraph must keep the surviving exception (a body that could not be
  parsed at all, and a cross-field service rule, both of which still carry no map) so "read `errors`
  defensively" stays true.

`api-docs-sync` runs after the build, per the pipeline.

### 8.3 Frontend impact — none required

`ApiResponseError` already reads `errors` off any ProblemDetail, so the new maps flow to the SPA with
no client change; `SearchResultsPage`, `RegisterPage`, `ResetPasswordPage` and `IssueDetail` keep
working. Two optional tidies, both documentation-only:

- `apiError.ts`'s `errors` javadoc says *"keyed by the request field's own name — `password`,
  `newPassword`, `description`, `body`"* — a member list that is stale the moment `page`, `q`, `field`
  and `token` join it. Rewrite as a category.
- The SPA cannot currently produce an out-of-range page (its pagers are bounded by `totalPages`), so
  there is no UI affordance to add. `DESIGN.md` is not engaged.

### 8.4 DC vs Cloud — no difference, and none is possible

No new property, no new environment variable, no profile-conditional bean, no new default. The bound is
arithmetic (`Integer.MAX_VALUE / MAX_SIZE`) and the refusal is a status code; neither has a deployment
dimension. Making the page ceiling operator-tunable was considered and rejected: a value below the
arithmetic ceiling would refuse requests that work today (a Cloud/DC behavioural fork by
configuration), and a value above it would re-open the overflow — so the only safe setting is the one
the code derives. `dc-cloud-guard` should confirm the negative rather than skip the gate.

---

## 9. Anything else on the search surface with the same shape (report only)

Bounded scope, per the brief: these are **reported, not designed**. None is a 500 today.

1. **`SearchService:118`, `hasNext = (long) (page + 1) * size < total`.** The cast is applied *after*
   an `int` addition, so `page == Integer.MAX_VALUE` overflows to a negative before widening. Currently
   reachable (that is exactly HD-163's input); **unreachable once `@Max` lands**. Worth spelling
   `(long) page + 1` while in the file — one character of belt, no ticket. Note the irony HD-163
   already names: this line is the anticipated-overflow counter-example that sat one statement away
   from the unguarded one.
2. **`SearchService:117`, `size == 0 ? 0 : …`.** Dead branch — `size` is clamped to a minimum of 1 four
   lines above. Cosmetic; leave or delete, no behavioural consequence.
3. **`SearchService:95–96` re-implements `Paging.of`'s clamp inline** (it already borrows
   `Paging.DEFAULT_SIZE` / `Paging.MAX_SIZE` for the numbers but not the logic). Two copies of one rule
   is the shape this whole spec is about; it is *not* a defect today because the two agree. Candidate
   for a follow-up ticket, deliberately not folded in here — changing where the clamp lives while also
   changing what it refuses would make a regression hard to attribute.
4. **`size` handling** — bounded in both implementations, clamped rather than refused, never the
   overflow factor. No finding.
5. **`POST …/search/insights`** — every string field carries a `@Size` (2000 / 32 ×3) with the reasoning
   recorded in the DTO; no page, no limit, no offset arithmetic. Clean.
6. **`GET …/search/schema`** — takes no caller input beyond the path variable. Clean.
7. **The report endpoints that share the throttle** — `sprints` is refused at both ends (1…12) by
   `VelocityService.validated`, which explicitly cites the same "an `IllegalArgumentException` deeper in
   is a 500 on an endpoint whose contract promises a 400" reasoning HD-163 is about; date windows are
   band-checked per report with the `DateTimeException` handler as backstop. Clean — and a useful
   precedent: that refusal is a `ResponseStatusException` with no `errors` map, which is why §7.4 keeps
   "not every 400 carries a map" true in the docs.
8. **Saved-filter CRUD** — `@Valid @RequestBody`, no index, no offset. Clean.
9. **The throttle itself** — `/api/workspaces/*/search/**` covers `POST /search`, `/schema`, `/suggest`
   and `/insights` with zero or more trailing segments, so every surface touched here is already
   budgeted at 120/min per principal. Both current 500s are therefore *noise*, not an outage — which is
   the severity assessment, not an excuse: 120 unhandled ERROR lines per principal per minute is still
   the operator's problem.

---

## 10. Edge cases & failure modes

1. **`page` absent / `null`** — unchanged: 0. The `@Max` does not fire on null (Bean Validation
   semantics), and `required = false` is kept on A2–A6.
2. **`page` negative** — unchanged: coerced to 0, 200. Deliberately *not* converted into a refusal by
   this ticket (§11, Q1).
3. **`page` exactly `MAX_PAGE`** — accepted, runs, and legitimately returns an empty page. That is the
   correct answer: an in-range index past the end of the result set is an empty page, not an error.
4. **`page = MAX_PAGE + 1`** — 400 before any query. This is the boundary AC-4 pins.
5. **`page` above `Integer.MAX_VALUE` (e.g. `9999999999`)** — never reaches validation: Jackson /
   Spring binding fails to produce an `Integer` and answers 400 already. Worth a test so the two
   refusal mechanisms are known to cover the whole range between them with no gap.
6. **`q` absent** — unchanged: `required = false`, treated as an empty prefix, 200.
7. **`q` of exactly 100 / 101 characters** — 200 / 400. Same boundary discipline as `page`.
8. **`field` absent** — unchanged: Spring's binding answers 400 for a missing required parameter.
9. **Concurrency / optimistic locking / soft-delete / in-use-on-delete** — not engaged. Nothing here
   writes, and no `@Version`-carrying entity is touched.
10. **Idempotency** — every affected endpoint is a read; a refusal has no side effect to repeat.
11. **A refusal still spends throttle budget.** The interceptor's `preHandle` runs before argument
    resolution, so a 400 consumes a search slot exactly as a 500 does today. Unchanged and correct — the
    budget exists to bound *attempts*.
12. **Two constraints failing at once** (e.g. `q` too long **and** `field` too long) — the `errors` map
    carries both, ordered and capped by the shared renderer. Worth one test: it is the case where a
    hand-rolled second copy of the rendering would visibly differ from `handleValidation`.

---

## 11. Open questions

**Q1 — should a negative `page` be refused rather than coerced?** *Recommended default: keep the
coercion; do not add `@Min(0)` in this ticket.* Adding `@Max` refuses only requests that already fail
(500 → 400, no working caller affected). Adding `@Min(0)` would convert a request that answers **200
today** into a 400, on six endpoints at once, and `openapi.yaml` already advertises `minimum: 0` on
`pageParam` without saying what happens below it. It also leaves `Paging.of` internally asymmetric
(coerce below, refuse above), which is untidy and honest. File it as a separate consistency ticket with
the whole API surface in view.

**Q2 — should the arithmetic ceiling be tightened to a product-sensible one?** *Recommended default:
no, not here.* See §2.2. The statement budget already refuses a genuinely deep offset with a 422 that
explains itself; a second, lower ceiling is a product decision about how deep pagination may go, not a
crash fix, and it changes the meaning of `maximum` in the published schema.

**Q3 — the Spring Data offset-conversion reading (§3.2). CLOSED 2026-08-28, by execution.** All six
Sweep-A rows were hit at `MAX_PAGE + 1` on a running instance before the fix was written: six 500s,
`InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE (2147483647)` on A2–A6 and
`IllegalArgumentException: First result cannot be negative` on A1. The conversion **refuses**; nothing
truncated; the verdict column in §3 now carries the observation beside the reading. A regression test
still owes each row an assertion (AC-2, AC-3) — an observation dates, a test does not.

---

## 12. Architectural decisions (ADR)

Two decisions here are hard-to-reverse forks a future contributor would ask "why?" about; the rest is
routine mechanics. Drafted as **ADR-0018** and **ADR-0019**, both `Status: Proposed`.

### ADR-0018 — `@Validated` is forbidden on any bean Spring MVC dispatches to; the guarantee is a category test

- **Chosen:** delete `@Validated` from `SearchController`, forbid it on `@Controller` /
  `@RestController` / `@ControllerAdvice` by a test phrased about the category, and keep it on
  `@ConfigurationProperties` where it is the correct mechanism.
- **Rejected:** (a) add a `ConstraintViolationException` handler and keep the annotation — §4.3;
  (b) keep the annotation and drop the parameter constraints — refuses less; (c) rely on the three
  existing comments that already say "do not add `@Validated`" — that is the state that produced this
  ticket, with the rule written down on two doors out of three.
- **Trade-off:** an annotation that reads as "turn on validation" now makes a build fail on a web bean,
  which will surprise someone; and a future need for **return-value** validation on a controller would
  have to be met another way. Both are cheap next to a 500.

### ADR-0019 — every 400 raised by a declared constraint carries the same `{detail, errors}` body, whichever door the constraint is written on

- **Chosen:** handle `HandlerMethodValidationException` in `GlobalExceptionHandler` with the rendering
  `handleValidation` uses (shared, not copied), keyed by parameter name; add the
  `jakarta.validation.ConstraintViolationException` backstop in the same shape.
- **Rejected:** (a) leave Boot's advice to answer parameter refusals — keeps a documented special case
  and hands the SPA prose where it was just taught to read structure; (b) give parameter refusals their
  own distinct shape — two contracts for one concept.
- **Trade-off:** this **overrides Boot's advice for an exception it declares**, so it is a body change
  app-wide (B1, B2) and puts one more type on the list that class-level javadoc warns about. And the
  backstop can, in principle, report a future entity-level Bean Validation failure as a client error —
  visible only because it logs at ERROR.

The page bound itself is **not** ADR-worthy: "refuse at the edge, never clamp, and derive the number
from the neighbouring cap" is an existing posture (`VelocityService.validated`, ADR-0017), and this is
one more application of it.

---

## 13. Acceptance criteria

Each is independently verifiable and phrased so a failure names its own cause.

1. **AC-1 — the page defect is a 400.** `POST /api/workspaces/{ws}/search` with
   `{"query":"","page":21474837,"size":100}` answers **400**, `detail` names `page`, and the `errors`
   map contains a `page` key. No 500, no stack trace.
2. **AC-2 — no query runs before that refusal, and Sweep A is verified row by row.** A query-count
   assertion proves **zero** statements are issued for the request in AC-1 (today the count query runs
   first). Every row of the Sweep-A table (§3) is exercised by a real request at `MAX_PAGE + 1`, its
   status asserted, and the table's verdict column corrected in this document if the observation
   differs (§3.2, §11 Q3).
3. **AC-3 — all six paged surfaces are bounded.** A1 through A6 each answer 400 at `MAX_PAGE + 1` and
   200 at `MAX_PAGE`. No paged surface is left unbounded and none is "explicitly recorded as already
   bounded" without a test behind the record.
4. **AC-4 — the boundary is pinned, and so is its derivation.** The test asserts `MAX_PAGE` accepted /
   `MAX_PAGE + 1` refused, **and** that `(long) Paging.MAX_PAGE * Paging.MAX_SIZE <= Integer.MAX_VALUE`
   while `(long) (Paging.MAX_PAGE + 1) * Paging.MAX_SIZE > Integer.MAX_VALUE`. No arbitrary large
   number appears in any assertion.
5. **AC-5 — the parameter defect is a 400.** `GET …/search/suggest?field=assignee&q=<101 chars>`
   answers **400** with `q` in `errors`; `q=<100 chars>` answers 200; `q=ab` still answers 200.
6. **AC-6 — the rule is sealed as a category.** A test fails if any class carrying
   `@RequestMapping`/`@Controller`/`@RestController`/`@ControllerAdvice` under `src/main/java` carries
   `@Validated`, with a failure message that *is* the explanation (it must say what to do instead). It
   carries a tripwire on the number of web classes scanned, so a scan that stops seeing declarations
   fails rather than passes. It must **not** flag the ten `@ConfigurationProperties` classes.
7. **AC-7 — Sweep B is sealed too.** A test enumerates every parameter-level constraint annotation in
   `src/main/java` and asserts each answers a **4xx, never a 5xx**, with a tripwire on the count
   (`>= 4` after this work). Phrased about the category, not about `q`/`token`.
8. **AC-8 — one body shape.** For a body-field refusal and a parameter refusal, the response carries the
   same keys, the same ordering rule, the same 10-entry cap and the same `"; … and N more"` overflow
   line; a request failing **two** parameter constraints reports both. A test asserts the two handlers
   produce equal shapes, so a second copy of the rendering cannot drift in unnoticed.
9. **AC-9 — no existence oracle.** For an over-long `q` and for an out-of-range `page`, a member, a
   non-member and a random workspace id all receive the **identical** 400 body; and a well-formed
   request from a non-member still receives **404**.
10. **AC-10 — `field` is bounded and cheap.** `field` of 101 characters answers 400 before the workspace
    is resolved (query count zero); `field=assignee` still answers 200; an unknown but in-range
    `field` still answers the existing field-anchored 422 with its "did you mean" hint.
11. **AC-11 — the backstop exists and is loud.** A test forces a `jakarta.validation.ConstraintViolationException`
    (e.g. a `@Validated` test-scoped bean) and asserts 400 with the shared body shape. The binding in
    `GlobalExceptionHandler` is **fully qualified**, and the data-integrity handler still binds
    `org.hibernate.exception.ConstraintViolationException` unchanged.
12. **AC-12 — the stale claims are corrected, not merely outnumbered.** `sqlStateOf`'s "latent rather
    than live … falls to the unchanged 500" paragraph, `GlobalExceptionHandler`'s "what else the
    precedence change moved" paragraph, `docs/api-cloud.md` + `docs/api-dc.md` L~89 and L~142, and
    `apiError.ts`'s member-list javadoc are each updated in the same change that makes them false.
13. **AC-13 — docs match the wire.** `openapi.yaml` validates, declares `maximum: 21474836` on both
    `pageParam` and `SearchRequest.page`, `maxLength: 100` on `suggest`'s `field`, and a `400` on every
    operation whose refusal changed. Both `docs/api-*.md` agree with it.
14. **AC-14 — no configuration surface was added.** No new property, env var, compose entry or
    `.env.prod.example` line; behaviour is identical under `dc` and `cloud`.
15. **AC-15 — nothing that worked stops working.** A negative `page` still answers 200 with page 0 on
    all six surfaces; the existing search, suggest and schema tests pass unchanged; no endpoint's
    success path changes status or body.
