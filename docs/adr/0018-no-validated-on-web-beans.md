# ADR-0018: `@Validated` is forbidden on beans Spring MVC dispatches to; the guarantee comes from a TEST ABOUT THE CATEGORY, not from a handler

Record date: 2026-08-28
Status: Accepted
Source: `docs/design/search-input-refusals-proposal.md` §4 (HD-214, HD-163);
`src/main/java/com/hamstrack/search/SearchController.java` — at the time of the decision the only controller with `@Validated`; the annotation was removed by this same ticket, and the prohibition is now held by `WebBeanValidatedRuleTest`, not by the absence of violators;
`src/main/java/com/hamstrack/auth/controller/AuthController.java` (the comment "NO @Validated ON THIS
CLASS, deliberately") and `src/main/java/com/hamstrack/workspace/controller/WorkspaceController.java`
("Do not add it.") — the same reasoning, already written down by HD-171 on two doors out of three;
`src/test/java/com/hamstrack/auth/TokenLengthBoundTest.java` §4.4 rule 8;
`CLAUDE.md` — the gotcha about `HandlerMethod.shouldValidateArguments()`; ADR-0017 — the precedent
"the guarantee comes from a test about the category, not from an annotation"

## Context

`HandlerMethod.shouldValidateArguments()` returns `false` exactly when the bean's class carries
`@Validated`. Spring MVC then does not run its own argument validation and delegates it to the AOP
proxy, which throws `jakarta.validation.ConstraintViolationException` — a type
`GlobalExceptionHandler` does not handle and which is not in the `ResponseEntityExceptionHandler`
list. The answer is a **500**. Without the annotation Spring MVC throws
`HandlerMethodValidationException`, and Boot renders it as a **400**.

The consequence in this tree is measurable and unambiguous. There are **three** parameter-level
constraints (`@Size`/`@Min`/`@Max`/… directly on a `@RequestParam`/`@PathVariable`) in
`src/main/java`:

- `AuthController.verifyEmailLink` — `token`, `@Size(max = 64)`, class without `@Validated` → **400**;
- `WorkspaceController.acceptInvite` — `token`, `@Size(max = 64)`, class without `@Validated` → **400**;
- `SearchController.suggest` — `q`, `@Size(max = 100)`, class **with** `@Validated` → **500**.

A one-to-one correspondence: the only parameter answering 500 sits on the only controller with that
annotation. `@Validated` and `@Size` arrived in one commit (HD-3), no test ever sent an over-long
`q`, and the defect stayed green until it was observed in the course of HD-171.

The fork: **fix the mechanism or fix the symptom.**

1. Remove `@Validated` from the web beans.
2. Keep it and add a handler for `jakarta.validation.ConstraintViolationException`.
3. Leave everything as it is, relying on review discipline — on two doors out of three the rule is
   already written down right in the code, legibly and with a justification.

What makes the choice significant: the answer spreads not to `q` but to **any future constraint on
any request parameter**, and it determines whether the next developer gets a 400 or a 500 depending
on which class he copied the line into.

A separate fact without which the decision is stated wrongly: `@Validated` is carried in this tree
**not only** by controllers. Ten `@ConfigurationProperties` classes (`SearchProperties`,
`ReportProperties`, `RolesProperties`, `InviteProperties`, `LockingProperties`, `BoardProperties`,
`AgileProperties`, `ClassificationProperties`, `WorkspaceProperties`, `StatementTimeoutProperties`)
carry it for the sake of validating the binding at startup — "fail fast, never clamp", as written in
their own javadoc. That is a different mechanism, it is correct, and the rule must be stated so as
not to touch it.

## Decision

**`@Validated` is removed from `SearchController` and forbidden on any bean Spring MVC dispatches to
(`@Controller` / `@RestController` / `@ControllerAdvice`). On `@ConfigurationProperties` it stays. A
handler for `jakarta.validation.ConstraintViolationException` is added — but as a BACKSTOP, not as
the mechanism (ADR-0019 and `GlobalExceptionHandler`).**

From this follow the rules that are the content of the decision:

- **Bounding a request parameter is one annotation.** Adding `@Validated` to make it "work" is
  exactly what breaks it. The wording has to be precisely this, because intuition suggests the
  opposite.
- **Removing the annotation refuses STRICTLY MORE, not less**, and this was checked file by file, not
  assumed. The proxy on `SearchController` today holds exactly one constraint (`@Size` on `q`);
  Spring MVC's built-in validation holds the same annotation on the same parameter. The set of
  constraints does not change — only who reads them and what gets thrown does. `@Valid @RequestBody`
  is validated by the argument resolver **before** the method is called, i.e. on an invalid body the
  proxy is never reached: that path does not depend on the annotation, neither now nor afterwards.
- **The only capability `@Validated` has and MVC's built-in validation does not is validation of the
  RETURN value.** No method on any controller declares a constraint on its return type, so nothing is
  lost. This statement is part of the decision, and checking it must be part of any future revision.
- **The guarantee comes from a test stated about the category**: "no class with `@RequestMapping`/
  `@Controller`/`@RestController`/`@ControllerAdvice` in `src/main/java` carries `@Validated`", and
  not from an enumeration of the three controllers that existed on the day it was written. Under the
  claim "nobody violates it" stands a tripwire on the number of web classes scanned: a scan that has
  stopped seeing declarations must fail, not go green (the ADR-0017 rule).
- **The test's failure message is the instruction.** It must name what to do instead of the
  annotation, because the reader of that failure is a person who has just added `@Validated` in order
  to "turn validation on".
- **The test must NOT touch `@ConfigurationProperties`.** The rule is stated about the web layer,
  because the harm arises only on the MVC dispatch path.

## Consequences

+ The three controllers start behaving alike: `@Size` on a `@RequestParam` gives a 400 wherever it is
  written. The next developer copying a bound gets a predictable result.
+ The public unauthenticated 500 disappears not "after a handler is added" but because the path that
  produced it has ceased to exist.
+ The HD-171 comments on two doors ("Do not add it") stop being a request and become a checkable
  rule. Before that the rule was understood, written down and applied on two doors out of three —
  exactly the mode of failure ADR-0017 describes for lengths.
+ The superfluous AOP proxy around the controller and the repeated body validation on the success
  path disappear.
− **An annotation that reads as "turn validation on" now breaks the build.** That will surprise, and
  the surprise is the price of the decision; it pays off because the surprise arrives in a test and
  not in production disguised as a 500.
− If a controller ever needs validation of a **return value**, it will have to be provided some other
  way. Today no method has such a requirement.
− The rule requires a future author to understand the difference between `@Validated` on a web bean
  and on `@ConfigurationProperties`. It is written in the text of the test, not only here.

## Alternatives

- **Add a `ConstraintViolationException` handler and keep `@Validated`** — rejected as the mechanism,
  accepted as a backstop (ADR-0019, §7.3 of the spec). Three reasons in decreasing order of strength:
  1. **Two exception types remain for one rule.** `@Size` on a parameter gives a
     `HandlerMethodValidationException` on two controllers and a `ConstraintViolationException` on
     the third; the next author inherits the behaviour of the class into which he copied the line.
  2. **The trap is preserved, and its price is a 500.** The annotation keeps looking like the thing
     that *turns on* parameter validation while remaining the thing that breaks it.
  3. Strictly more code for the sake of a strictly weaker guarantee.
- **Remove the constraints from the parameters so that the question does not arise** — rejected: that
  refuses less. `token` is bounded on the two doors precisely because it is the only thing that bounds
  those paths at all (`AuthRateLimitFilter.shouldNotFilter` returns `true` for non-POST, and the
  javadoc of `verifyEmailLink` names this endpoint outright as the reason for the exclusion).
- **Review discipline** — rejected: this is the state before this ADR. The rule was written down on
  two doors out of three, with a justification, and the third door answered 500 all the same.
