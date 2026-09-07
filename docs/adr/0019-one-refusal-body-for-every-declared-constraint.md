# ADR-0019: Every `400` produced by a DECLARED constraint returns the same body `{detail, errors}` — no matter which door the constraint is written on

Record date: 2026-08-28
Status: Accepted
Source: `docs/design/search-input-refusals-proposal.md` §7 (HD-214, HD-163);
`src/main/java/com/hamstrack/common/exception/GlobalExceptionHandler.java` — `handleValidation`,
the class javadoc ("What else the precedence change moved") and the `sqlStateOf` javadoc ("Not covered,
deliberately"); `src/main/frontend/src/apiError.ts` — the `errors` field and its readers;
`docs/api-cloud.md` L~142 ("A refusal on a query parameter carries no `errors` map");
HD-171 / ADR-0017 — what taught the SPA to read `errors` structurally; ADR-0018 — the decision that
makes `HandlerMethodValidationException` a live type in this tree

## Context

In the application three mechanisms turn a declared constraint into a `400`, and they give
**different bodies**:

| What is thrown | Who handles it | `detail` | `errors` |
|---|---|---|---|
| `MethodArgumentNotValidException` (`@Valid @RequestBody`) | our `handleValidation` | lists the fields and the messages | **present** |
| `HandlerMethodValidationException` (a constraint on a `@RequestParam`) | Boot's advice — the type is in the `ResponseEntityExceptionHandler` list | Spring's generic phrase, with no field name | **absent** |
| `jakarta.validation.ConstraintViolationException` (the same `@RequestParam`, but the class carries `@Validated`) | nobody | — | — → **500** |

HD-171 taught the SPA to branch on `errors` **structurally** rather than to match English text:
`ResetPasswordPage` tells a stale `token` from a refused `newPassword` only by the keys of that
map. That is, the client acquired a contract that the second row of the table does not fulfil and the
third one brings down.

The documentation already records this state — and records it **as a claim about MEMBERS**: "a refusal
on a query parameter carries no `errors`… these are `token` on the verification link and on
`POST /workspaces/accept-invite`". Today that is true; tomorrow the category has four members (`q`,
`field`, `page` across five endpoints, `token` ×2), and one of them, before ADR-0018, answered not 400
but 500 — which the sentence does not mention at all.

The fork: **how many refusal shapes a declared constraint has — one or two.**

## Decision

**One. `GlobalExceptionHandler` takes `HandlerMethodValidationException` on itself and renders it with
the same code as `handleValidation`, with keys by PARAMETER NAME. Plus a backstop handler for
`jakarta.validation.ConstraintViolationException` in the same shape.**

The rules, which are the content of the decision:

- **The claim is phrased about the category:** *every `400` produced by a declared constraint carries
  an `errors` map whose key is the name of what was refused — be it a body field or a request
  parameter.* That sentence does not go stale on the fifth bounded parameter. The phrasing "these are
  the two tokens" goes stale on the third.
- **The exceptions to the category stay named, and they are categories too:** a body that could not be
  parsed at all (nothing bound), and a cross-field rule living in the service (`sprints` in
  `VelocityService.validated`, `ResponseStatusException`), carry no map. "Read `errors`
  defensively" must remain true.
- **The rendering is SHARED, not copied.** The sorting, `MAX_REPORTED_ERRORS`, the trailing
  "; … and N more" and the field-prefix rule are a contract described in the documentation by four
  bullet points. Two copies will diverge, and the divergence is invisible: both variants give 400. One
  private renderer, two handlers.
- **This handler OVERRIDES Boot's advice for a type that advice declares.** The class javadoc already
  warns about this in the paragraph about `MaxUploadSizeExceededException`; after this decision the
  list of "what else the precedence change moved" holds two types, and the paragraph must be rewritten
  **in the same commit**. A paragraph naming one member of a two-member list is exactly the defect the
  rule about the category and the member is written down in `CLAUDE.md` for.
- **The backstop handler is declared by its FULLY QUALIFIED NAME.** `GlobalExceptionHandler`
  already binds `org.hibernate.exception.ConstraintViolationException` in
  `handleDataIntegrityViolation`; the types are unrelated and differ only by the import. A bare import
  here is a one-symbol way to silently rebind the data-integrity handler. The paragraph in the
  `sqlStateOf` javadoc which today says that this type is "latent rather than live" and "would fall
  into an unchanged 500" becomes false after this decision and is rewritten, not left standing.
- **A backstop is a backstop, not a mechanism** (the `handleDateTime` / `handleQueryTimeout` /
  `22001`-branch doctrine). After ADR-0018 nobody should be able to reach it, so it logs an **ERROR**,
  naming both possible causes: a new web bean with `@Validated`, or Bean Validation on an entity. A clean
  400 without that line would delete the only signal to the operator.

## Consequences

+ The client has one shape instead of two: a form can put the message next to the control rather than
  into a banner, for a parameter exactly as for a body field.
+ The claim in `docs/api-*.md` turns from an inventory into a rule and stops going stale on every new
  bounded parameter.
+ Two existing doors (`token` on verification and on `accept-invite`) improve for free: the body
  starts naming the field. No status changes.
+ `apiError.ts` needs no change: `ApiResponseError` already reads `errors` off any ProblemDetail. The
  only thing that changes is the javadoc, which today carries an enumeration of members (`password`,
  `newPassword`, `description`, `body`).
− **The body change is application-wide**, not local to search: every refusal on a constraint on a
  parameter anywhere in the application changes the shape of the response. That is the deliberate price
  of one shape.
− The list of types that override Boot's advice grows, and with it the duty to keep the javadoc
  paragraph in sync. The defect here is quiet: the divergence breaks no test, it merely misleads the
  next reader.
− The backstop handler is in principle capable of reporting a future Bean Validation refusal **on an
  entity** as a client error. Today no entity carries such annotations; if one does, the right move is
  a separate handler for that path, not a widening or a narrowing of this one. The ERROR line is what will
  make it visible.
− The keys come from `MethodParameter#getParameterName()`, so they depend on the `-parameters` compiler
  flag (which `spring-boot-starter-parent` sets). If it goes missing the map must not silently become
  empty — a positional key is provided for.

## Alternatives

- **Leave Boot's advice to answer refusals on parameters** — rejected. That preserves the
  documented exception ("on a query parameter there is no map") and hands the SPA prose exactly where
  HD-171 taught it to read structure. The exception would then have to be kept phrased about members,
  because the category "parameter" would stop being uniform in the shape of its response.
- **Give parameter refusals their own, distinct shape** (a dedicated `errorType`, say) — rejected:
  two contracts for one notion, and no validation refusal in the product carries an `errorType` today.
  A new discriminator would make this surface the only special one.
- **Handle `ConstraintViolationException` instead of `HandlerMethodValidationException`, keeping
  `@Validated`** — rejected in ADR-0018 on independent grounds; all that matters here is that it would
  not have solved this ADR's problem: two doors out of three would go on throwing
  `HandlerMethodValidationException` and returning a body with no `errors`.
- **Teach the SPA to do without `errors` on parameters** — rejected: that moves the rule from one
  place (the server) into every client, including the ones we do not write.
