# ADR-0017: A length bound is a REPEATED `@Size` LITERAL on every door, and the guarantee that the doors did not forget is a TEST ABOUT THE CATEGORY

Record date: 2026-08-28
Status: Accepted
Source: `docs/design/request-field-length-bounds-proposal.md` §0 (Correction 2), §3, §5 (HD-171);
`src/test/java/com/hamstrack/auth/EmailLengthBoundTest.java` — the existing implementation of the
mechanism and its javadoc; HD-120 (the five `@Email @Size(max = 255)` and the test itself), HD-190
(moving the type onto the third line, which made the line-by-line scan silently stop seeing
`InviteMemberRequest.email`); `CLAUDE.md` — the rule "a claim about a CATEGORY outlives a claim about
a MEMBER"; ADR-0005, ADR-0008, ADR-0016 — precedents for declining a wrapper type in favour of a rule
on a scalar

## Context

In this project the same column regularly has several write doors, and the length rule turns out to be
written on only some of them. `admin/dto/CreateUserRequest` carried `@NotBlank @Email @Size(max = 255)`
from the day it was written and **named this anti-pattern in its own javadoc** — "a rule on one door out
of two is not a rule" — while five other doors into the same `VARCHAR(255)` columns had no bound at
all. HD-120 added the missing five. HD-171 asked the next question: should the six spellings stop being
six.

The fork is stated as a choice of three:

1. keep the repeated `@Size(max = 255)` next to every `@Email`;
2. a composed (composite) annotation `@EmailAddress` = `@Email @NotBlank @Size(max = 255)`;
3. a validated value type `record EmailAddress(String value)`.

What makes the choice significant: the answer applies not to addresses but to **any request string field
that reaches a column with a width** — and there are dozens of those in the schema (the full inventory is
in §3 of the spec). The form chosen once will determine how length bounds are expressed in this code at
all.

The key fact, which in this project outweighs everything else, and which was found precisely by
HD-171's end-to-end sweep: **both defects of the "500" class that the inventory found have no
annotatable field at all.**

- `workspaces.slug VARCHAR(100)` is built from `CreateWorkspaceRequest.name` (`@Size(max = 255)`)
  by `WorkspaceService.generateSlug`, which does not truncate.
- `issue_history.field VARCHAR(50)` is written with the **display name** of a custom field
  (`field_defs.name VARCHAR(100)`).

No annotation — composed or not — and no value type could have found them: there is nothing to annotate.
Only a sweep over the schema and the code could find them, and only a check of behaviour could **hold**
them.

The second key fact: the rule is already held not by an annotation but by a test. `EmailLengthBoundTest`
reads the **source text**: it finds every `@Email`, walks to the `String` it annotates, and reads the
`@Size` in that declaration. Under its claim "nobody violates it" stands a tripwire `checked >= 6` —
and it has already fired once: HD-190 moved the type of `InviteMemberRequest.email` onto the third line,
the field silently dropped out of the scan, the list of violators stayed empty and green, and only the
tripwire noticed.

## Decision

**Six spellings. The repeated literal `@Size(max = 255)` next to every `@Email` stays.
The composed annotation and the value type are rejected. What is generalised is the MECHANISM — the
test about the category — not the annotation.**

Four rules follow from this, and they are the content of the decision:

- **`@Size(max = …)` takes a numeric LITERAL.** Not `10_000` and not a symbolic constant.
  `EmailLengthBoundTest.SIZE_MAX` matches `max\s*=\s*(\d+)`: an underscore will read as `10`, and a
  reference to a constant as "there is no `@Size` at all". For an `@Email` field that is a test failure
  (loud); for any future width scanner it is a silent skip. The literal's readability to the scanner is
  bought at the price of repeating the number, and that price is deliberate.
- **The guarantee comes from a test phrased about the category**, not from an annotation the doors must
  remember to put on. The phrasing is "every `@Email` in the production sources", not an enumeration of
  the fields that existed on the day it was written.
- **Under every claim "nobody violates it" stands a tripwire on the number of things checked.** A scan
  that has stopped seeing declarations must fail, not go green.
- **A value DERIVED from another column is either bounded by that column's width or truncated at the
  write site.** This is the half of the rule that annotations cannot reach, and it is exactly the half
  that closes both of the 500s that were found. A reference implementation already exists —
  `RoleService.generateKey` truncates to `roles.key VARCHAR(40)` together with the collision suffix;
  `WorkspaceService.generateSlug` is the same function without the truncation.
  **The truncation is placed on the COLUMN, not on one of its writers** — a hand-written entity setter
  (Lombok `@Setter` yields to it), because the rule is phrased about a *class of values*, and
  `issue_history.field` has four writers, and the first edition of HD-171 put the belt on one of them.
  The other three pass literals, there was no live bug — but "only the update path writes a dynamic
  name" is a claim about today's call graph, and next to it lies an almost verbatim copy of
  `makeHistory` in `SprintService` with no belt and no comment about why it will need one.
- **A bound on a READING door is justified by the word "finite", not by an enumeration of the writing
  doors.** In the first edition `LoginRequest.password` got 100 with the justification "every door that
  *writes* a password is bounded at a hundred". A login door must accept whatever any writing door
  produced, so a bound justified by **an enumeration of members** goes stale on the first new door,
  while a bound justified as "finite and knowably above anything any door can produce" does not. The
  number (1024) is chosen so as to cost nothing: `matches` goes down the `for_check` branch, which
  **truncates** at 72 bytes rather than throwing, so 100 and 1024 check identically.
  **The story that justified this in the second edition has been deleted, and the deletion is the
  lesson.** It named a concrete victim: an administrator with a 128-character `SEED_ADMIN_PASSWORD`
  whom a bound of 100 would have locked out forever. Such an administrator cannot exist: `DataSeeder`
  hashes with the same BCrypt, and BCrypt refuses to **create** a hash longer than 72 bytes — so no
  account in this codebase has ever held a longer password, and 100 would have locked nobody out.
  The decision stood on a claim about the **category**, while the claim about a **member** attached to
  it had to be thrown out entirely — exactly the rule this ticket was written for, caught in its
  own text.
- **A door that WRITES a password is bounded by what the encoder accepts, and in the encoder's units.**
  `BCryptPasswordEncoder.encode` throws above **72 UTF-8 bytes** (`BCrypt.java:615`), and nothing
  translates that — meaning a 500 on the public `POST /api/auth/register` and on `/reset-password` for
  73 ASCII characters, 37 Cyrillic ones or 25 CJK ones. Two things follow, and the second matters more:
  `@Size(max = 72)` on both writing DTOs — and **a separate byte check** in the service, because
  `@Size` counts UTF-16 units and BCrypt counts bytes, and 72 Cyrillic characters are 144 bytes,
  which the annotation lets through. One annotation does not express the rule here; the unit of measure
  is part of the rule. The refusal (`PasswordTooLongException`, 422 — like the neighbouring
  `PublishedPasswordException`) names **bytes** and explains the arithmetic, because "72 bytes" is not
  something a human can check by looking at their passphrase. The same ceiling is in `DataSeeder`
  (`MAX_SEED_PASSWORD_BYTES` = `PasswordLimits.MAX_PASSWORD_BYTES`), where the guard exists
  **only for the sake of the message**: without it startup fails anyway, but inside `encode` and with no
  variable name. And this guard, unlike the neighbouring guard on a published password, is switched on
  **only when the value will actually be encoded**: `seed.admin.email` is set, the value exceeds the
  limit **and** there is no account yet at that folded address. A published password is a compromise
  regardless of whether seeding happens; an over-long one is not: it created nothing if nobody encodes
  it. So an already-seeded installation that rotated `SEED_ADMIN_PASSWORD` to a long value keeps
  starting up.
- **PROHIBITED: a `ConstraintValidator` that goes to the database, on a workspace-scoped request DTO.**
  It follows from this decision that `@Valid @RequestBody` runs during argument resolution — **before**
  `WorkspaceAccessService` resolves membership — so a member, an outsider and a non-existent workspace
  all get **the same 400** for an over-long field, and that is correct (§6.3, §15 of the spec). This
  rests on one condition: **every constraint on such DTOs is a pure function of the submitted body.**
  `handleValidation` does not take an `HttpServletRequest`, so nothing request-dependent reaches it
  today — but that is a property of the constraints in use, not of the mechanism. A validator that
  issues a query ("this address is already a member", `@ValidRoleForWorkspace`) answers differently to a
  member and to an outsider, **while the shape of the response does not change**: that same 400 silently
  becomes a membership oracle, the very thing the rule "404 both for the non-existent and for someone
  else's" exists to forbid. Cross-entity checks live in the service, after membership is resolved.
- **The generalisation of the mechanism is a BEHAVIOURAL test, not a static scan of DTO→column pairs.**
  `RequestFieldLengthBoundTest`: a table of "write endpoint → body with very long strings", and the
  check asserts the **class** of the response (4xx, never 5xx), not a specific code. Plus two tripwires:
  the number of rows in the table, and a scan of the number of `@PostMapping`/`@PutMapping`/`@PatchMapping`
  in `src/main/java` against the covered set.

## Consequences

+ **The guarantee is strictly stronger than an annotation would give.** A DTO carrying `@Email` without
  `@Size` fails the build. A DTO that **forgot** to put `@EmailAddress` on and wrote `@Email` would
  compile and ship. The annotation and the test do not add up: the test covers exactly the miss the
  annotation creates.
+ The mechanism carries over to new classes of fields without a single new type: the next rule is the
  next scan or the next row in the behavioural test's table.
+ The behavioural test catches a class of defects unreachable by any annotation scanner — derived
  values. The row that catches the slug bug sends a **101-character** name: a valid input and an
  invalid slug.
+ Not one new dependency, not one change at the Jackson boundary, not one change to the schema shape in
  `openapi.yaml`.
− **The number is repeated.** `255` six times, `10000` four times. A divergence is possible and is
  caught only by a test — that is, the price of the decision sits in exactly the same place as its
  guarantee.
− **The prohibition on symbolic constants in `@Size` is non-obvious and will be violated** unless it is
  written in the javadoc next to it. The phrasing "the scanner reads digits" must live in the text of
  the test, not only here.
− A behavioural test costs more than an annotation to maintain: it has a table, and a table is a list.
  Lists go stale, so under it stand two tripwires, and the second (the scan of the number of write
  mappings) is the claim about the category; without it what would remain is an inventory, not a guarantee.
− The test proves a **status class**, not a bound. An endpoint that truncates instead of refusing passes
  it — and that is correct (truncation is a legitimate mechanism, §3.3(c) of the spec), but you have to
  know it so as not to mistake a green test for proof that `@Size` is there.

## Alternatives

- **A composed annotation `@EmailAddress`** — rejected, three reasons in decreasing order of strength:
  1. **It would break what actually holds the rule.** A meta-annotation hides `@Size` behind the
     annotation *type*; the text scan will stop seeing it, and the scanner would have to be rewritten to
     resolve meta-annotations reflectively — a strictly larger and more fragile machine — or weakened
     into trusting that "since `@EmailAddress` is written, everything inside must be right".
  2. **The failure mode shifts from loud to quiet.** See the first item under "Consequences".
  3. **It does not express variants.** `@NotBlank` is right on all of today's doors and wrong on the
     very first optional one — an address in a partial `PATCH` (the obvious next candidate is
     `admin/dto/UpdateUserRequest`). A second composed annotation would be needed, and two spellings of
     one rule is precisely the defect all of this was started for.
- **A validated value type `record EmailAddress(String value)`** — rejected as disproportionate.
  It changes every DTO, every service signature, (de)serialisation at the Boot 4 / Jackson 3 boundary
  (a custom `ValueInstantiator` — at the very boundary where the project already has one documented
  bridge, `Jackson2NodeModule`) and the schema shape in `openapi.yaml` — all for a rule that is one
  integer. The project's precedent is consistently against a wrapper type in favour of a rule on a
  scalar: enum-like values are `VARCHAR` + a Java enum (ADR-0005), permissions are an enum with no table
  (ADR-0008), case-insensitive uniqueness is a constraint, not a type (ADR-0016).
- **A static scan of all DTO→column pairs** (the head-on generalisation of `EmailLengthBoundTest`) —
  rejected as unsound. You cannot walk DTO → service → entity → column without type resolution, and the
  project has no infrastructure for that; and, decisively, **a perfect such scanner would put a green
  tick over both of the real bugs**, because they have no annotatable field. The scans that work in this
  tree (`EmailLengthBoundTest`, `DisplayTextTest`, `LocaleIndependentFoldingTest`) work only because
  their pattern is local within a single declaration.
- **Leave it as is and tighten review discipline** — rejected: that is exactly the state before HD-120,
  and `CreateUserRequest` proved how it breaks — the rule was understood, written down in javadoc and
  applied on one door out of six.
