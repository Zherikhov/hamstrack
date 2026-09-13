# Fold-then-store length bounds — a case fold is a derived value, and its bound is measured after the fold (HD-306)

**Status:** proposal / design review. **Date:** 2026-09-13. **Author:** systems-analyst.
**Epic:** HD-294 (2026-09 retrospective), follow-up of HD-297.
**Related:** HD-171 (`RequestFieldLengthBoundTest`, the derived-value rule, `GlobalExceptionHandler`'s
`22001` branch), HD-297 (the NFC family — the seal shape this spec mirrors), HD-120 (`Locale.ROOT`
folds and `EmailLengthBoundTest`), HD-190 (`MailAddresses.throttleKey`, `mail_send_events`),
HD-202 (the recipient ceilings on the three anonymous mail doors), HD-261/HD-298
(`SignupRefusal`), HD-133 (`saveAndFlush` when a violation must become a status code),
ADR-0015 (the persisted recipient-keyed throttle), ADR-0017 (repeated literals, never imported),
ADR-0018 (no `@Validated` on a web bean).
**Touches:** `common.mail.MailAddresses` (one new gate + one truncation), `AuthService.register`,
`AdminUserService.create`, `WorkspaceService.inviteMember`, `DataSeeder.refusePublishedCredentials`,
`ProductMetrics.SignupRefusal` (+1 constant, and its category sentence),
`RecipientMailThrottle` (one counter call), `MailSendEvent.recipientKey` (javadoc — a false claim),
`docs/observability.md` (one row), `observability/rules.yml` (one rule, optional — §13),
tests: `RequestFieldLengthBoundTest`, `MailAddressesThrottleKeyTest`, `EmailLengthBoundTest`
(javadoc only), `common/testsupport/ProductionBytecode` (one method), `SeedGuardStartupOrderingTest`.
**No new configuration property. No new environment variable. No profile gating. No migration.**

---

## 0. Evidence discipline, and what this session could **not** measure

Every premise below is labelled **measured** / **read** / **inferred**. Two honesty notes, because
they change what the builder must do first:

1. **This session had no execution tool** (`Bash` disabled, no test runner, no `psql`). **Nothing in
   this document is measured by its author.** Statements labelled *measured* are measured **by the
   HD-297 security gate on 2026-09-09 and quoted from the ticket**, or by an **existing test in the
   tree whose assertion I read**. Everything else is *read* (file:line) or *inferred*.
2. Therefore **§4 is the first deliverable**: five probes, run and pasted before a line of production
   code is written. Two of them can overturn a member of §3 — and either outcome is shippable, but a
   silent omission is not.

---

## 1. Problem & goal

`String.toLowerCase(Locale.ROOT)` and `String.toUpperCase(Locale.ROOT)` are **not
length-preserving**. Doors fold a caller-supplied value *after* its `@Size(max = N)` and store the
**folded** value in a `VARCHAR(N)` column, so a raw value that passes bean validation overflows the
column. The commit fails with SQLSTATE `22001`; `GlobalExceptionHandler` answers **400** and logs at
**ERROR** (`GlobalExceptionHandler.java:1097` — read), by design, because reaching that backstop
means *some path has no bound*. On `POST /api/auth/register` the caller is **unauthenticated** and
has already been charged one bcrypt-12 (~370 ms) and one recipient-mail ceiling inside an advisory
lock before the INSERT fails.

**Measured (HD-297 gate, 2026-09-09, quoted from the ticket, not re-run here):** Hibernate Validator
9.1.0 against `RegisterRequest`'s real constraint set — `64 × U+0130 + "@" + a 190-character ASCII
domain` gives `raw length 255, violations 0, folded length 319, column 255`.
**Measured (Unicode UCD `SpecialCasing.txt`, read 2026-09-13 via unicode.org):** exactly **one**
unconditional lowercase mapping lengthens — `0130; 0069 0307; 0130; 0130; # LATIN CAPITAL LETTER I
WITH DOT ABOVE`. **Roughly twenty-plus** unconditional *uppercase* mappings lengthen (U+00DF → `SS`,
U+FB01 → `FI`, U+FB03 → `FFI`, U+0149, the ligature block U+FB00–FB06/FB13–FB17, several Greek
diacritic forms), which is why the `toUpperCase` doors are members of this category even where they
are safe today.

**Goal.** No request path, and no boot, may reach a column with a value the server produced by case
folding. The bound is measured **after** the fold and **before** any metered or slow work, on every
member of the category, and a new member cannot escape by not being listed.

## 2. Scope / non-goals

**In scope:** every production site that case-folds a value and **stores** the result (§3), the
shared gate, the refusal's status/message/ordering, the derived-key truncation, the counter, the
seal, and one **false claim in the tree** (§3 M4) that this ticket must correct.

**Non-goals.** (a) Widening any column — §8 says why truncating the derived key beats widening it.
(b) Changing what `throttleKey` folds; the folding *rules* are untouched (`MailAddresses:66–120`,
read). (c) The NFC family — sealed by HD-297; this spec reuses its machinery and adds no second
one. (d) Non-storing folds (lookups, comparisons, sort keys, map keys, log/metric tags) — §3 states
why, as a property. (e) Unicode confusable folding — refused on purpose (`MailAddresses:93–99`,
read). (f) A locale audit: `Locale.ROOT` is already everywhere it matters (HD-120).

## 3. The category, enumerated from the code

**The rule, phrased as a property of the class and not as a list of doors:**

> **A case fold is a derived value.** Where the server folds a caller-supplied (or
> operator-supplied) value and then **stores** the folded result, the bound that protects the target
> column must be measured **on the folded value**, because the fold may lengthen it. A `@Size` on
> the request record bounds the *raw* text and the column bounds the *stored* text; a door that
> folds between the two and measures nothing lets its caller drive the commit into SQLSTATE `22001`
> on demand. **Which mechanism is correct is decided by what the value IS, not by which door writes
> it: an identity is REFUSED, a derived key or forensic copy is TRUNCATED at the site that produces
> it.** A fold whose result is only compared, looked up, sorted or counted has no column to
> overflow and is out — but a door is a *storing* door if anything it calls stores a value derived
> from its fold (which is how the two anonymous mail doors are members, see M4).

**Enumeration (read — `grep -rn "toLowerCase(\|toUpperCase(" src/main/java`, 2026-09-13: 66 hits
across ~30 production classes; the members below are the subset whose result is stored).**

| # | Fold site (read) | Target column (width) | Raw bound on the request | Growth reachable? | Today |
|---|---|---|---|---|---|
| **M1** | `AuthService.java:97` (register, **unauthenticated**) | `users.email` **255** (V1:41) | `RegisterRequest.email` `@Email @NotBlank @Size(max = 255)`, **no pattern** (`RegisterRequest.java:46`) | **YES — measured** (gate 2026-09-09: 255 → 319) | `22001` → 400 + **ERROR**, after 1 bcrypt-12 and one ceiling spend inside the advisory lock |
| **M2** | `AdminUserService.java:57` (admin creates a user) | `users.email` **255** | `CreateUserRequest.email` `@NotBlank @Email @Size(max = 255)`, no pattern (`CreateUserRequest.java:26`) | **YES** — same fixture, same constraint set (inferred from M1; probe P2) | `22001` → 400 + **ERROR** |
| **M3** | `WorkspaceService.java:254` (invite) | `workspace_invites.email` **255** (V1:130) | `InviteMemberRequest.email` `@Email @NotBlank @Size(max = 255) @Pattern("\\p{ASCII}*@[^@]*")` (`InviteMemberRequest.java:68`) | **INFERRED** — the local part is ASCII, so growth must come from **one** U+0130 in the domain at raw exactly 255 (probe P3; the ticket labels this unprobed and so does this spec) | `22001` → 400 + **ERROR**, **and the recipient-ceiling row rolls back** (the free-probe hazard `WorkspaceService.java:297–302` exists to prevent) |
| **M4** | `MailAddresses.java:137` (+ punycode `:202`) → stored by `RecipientMailThrottle.record` (`:611–619`) | `mail_send_events.recipient_key` **320** (V21:72, `MailSendEvent.java:90`) | **none of its own.** The width rests on `64 + "@" + 255 = 320` — *and that claim is certified against `InviteMemberRequest` only* (`MailAddressesThrottleKeyTest.java:198`, `:472–475`, read) | **INFERRED, and it survives the M1 fix** — register/forgot-password/resend-verification carry no ASCII pattern, so `64 × U+0130` folds to **128** and the key is `128 + 1 + 255 = 384 > 320` (probe P4) | `22001` **inside the throttle** → 400 + ERROR, ceiling row rolled back |
| **M4b** | the same fold, stored verbatim by `RecipientMailThrottle.record` beside the key | `mail_send_events.recipient_email` **320** (`MailSendEvent.java:56`) | **none of its own** — and its javadoc claimed the writing DTOs' `@Size(max = 255)` made 320 "margin, not a licence to store unbounded input" | **YES — MEASURED in the fix loop** (2026-09-13): the widest address every non-invite DTO accepts with zero violations folds to **347**; the two anonymous doors have no gate above them by design | `22001` **inside the throttle** → the uniform-response endpoints answer 400 + ERROR and the ceiling row rolls back. **Missed by round 1 of this spec**, which named one of the two columns this one fold reaches |
| **M5** | `DataSeeder.java:211 / :261 / :380 / :518` (`SEED_ADMIN_EMAIL`) | `users.email` **255** | **none** — `@Value("${seed.admin.email:}")` (`DataSeeder.java:51`), no `@Size`, no `@Email` (read) | **YES, and without any fold** — a 300-character `SEED_ADMIN_EMAIL` already crashes the boot inside the seed INSERT (inferred; probe P5) | boot fails with PostgreSQL's `value too long for type character varying(255)` and no mention of the property that caused it |
| M6 | `WorkspaceService.java:961` `generateSlug` | `workspaces.slug` **100** | `@Size(max = 255)` name | n/a — substitutes, then **truncates inside the width**, suffix carved out of it | **bounded** (HD-171); row `WorkspaceController#create[slug]` holds it |
| M7 | `RoleService.java:1269` `generateKey` | `roles.key` **40** | `@Size(max = 80)` name | n/a — same shape | **bounded** (HD-171); row `RoleController#duplicate[key]` holds it |
| M8 | `AdminFieldService.java:661` `slugify` | `field_defs.key` **50** (V1:287) | `@Size(max = 100)` name | n/a — substitutes to `[a-z0-9_]`, then `substring(0, min(len, 50))` | **bounded** |
| M9 | `AdminFieldService.java:198` (key rename) | `field_defs.key` **50** | `UpsertFieldRequest.key` `@Pattern("[a-z0-9_]*") @Size(max = 50)` (`UpsertFieldRequest.java:44`) | **NO** — the pattern admits only ASCII already-lowercase; the fold is the identity | safe **by that pattern**, not by the fold |
| M10 | `ProjectService.java:92` | `projects.key` **10** (V1:149) | `CreateProjectRequest.key` `@Size(min = 1, max = 10) @Pattern("[A-Z0-9]+")` (`CreateProjectRequest.java:30`) | **NO** — `toUpperCase` lengthens for ~20+ code points (ß→SS, ﬁ→FI, U+0149, U+1E9E family), all excluded by the pattern | safe **by that pattern**, not by the fold |

**Out of the category, and the reason is a property rather than a list:** the remaining ~20 folding
classes (`AuthService:255/:312/:326`, `RateLimitService:155`, `EmailUniqueness`, `LabelService`,
`ComponentService`, `VersionService`, `SearchService`, `SearchNames`, `CommentService`,
`FieldRegistry`, `RetiredFieldAliases`, `ResolutionContext(Factory)`, `HqlCompiler`,
`HqlValueResolver`, `Lexer`, `InsightsService`, `InsightsDimension`, `LabelMatch`,
`ContentSecurityPolicy`, `ProductMetrics`, `PrincipalThrottleInterceptor`,
`ScopedProjectAdminService`) fold a value they **compare, look up, sort, count or print**. There is
no column, so there is nothing to overflow; the raw `@Size` still bounds the key space, which is
`EmailLengthBoundTest`'s settled "bound the field, not the itinerary" (read,
`EmailLengthBoundTest.java:49–59`). **The post-fold bound does not extend to them**, and the reason
it does not is exactly the reason it *does* extend to M4: an unbounded in-memory key costs memory
that the raw bound already caps, while a stored key costs a `22001` the raw bound cannot see.

**`AuthService.forgotPassword` / `resendVerification` are members through M4 and not on their own.**
They only *read* `users` — and they *write* `mail_send_events` through
`mailThrottle.allowAnonymousSend`, unconditionally and before the lookup
(`AuthService.java:315`, `:346`, read). The fold they perform is stored by somebody else, which is
why the member is the **producing site** (`MailAddresses.throttleKey`) and not the three endpoints:
one fix there covers every present and future caller, which is the same argument
`RecipientMailThrottle` already makes for deriving the key inside itself
(`RecipientMailThrottle.java:441–443`, read).

### 3.1 The false claim this ticket must correct

`MailSendEvent.recipientKey`'s javadoc (read, `:73–88`) states:

> "The longest key that can reach this column is therefore 64 + `"@"` + 255 = 320. It fits exactly,
> with no margin … Also why a future fold that ever APPENDS to a key rather than only stripping from
> it needs a wider column first."
> "(`InviteMemberRequest`'s ASCII-only local part does not change this arithmetic: the lengthening is
> in the DOMAIN…)"

**`toLowerCase` is a fold that appends** (U+0130 → `i` + U+0307), and it runs on the **local part**
in `throttleKey`'s first line. The arithmetic is `128 + 1 + 255 = 384` for any door whose DTO does
not force an ASCII local part — i.e. every door except the invite. The guarding test
(`MailAddressesThrottleKeyTest#theWidestKeyReachableThroughTheRealDtoConstraintsExactlyFillsTheColumn`)
validates its fixture **through `InviteMemberRequest` only** (`:472–475`), so the claim is a
*uniqueness claim certified on one member* — the exact shape CLAUDE.md's "a claim phrased about a
category outlives a claim phrased about a member" names. §12 Seal C re-phrases it over every writing
DTO.

## 4. Probes — the builder's first report, before any production code

| # | Probe | What it settles |
|---|---|---|
| **P1** | `Character.toString(0x0130).toLowerCase(Locale.ROOT).length()`; and the gate's fixture: build `64 × U+0130 + "@" + <190-char ASCII domain of labels ≤ 63>`, assert `raw == 255`, `validator.validate(new RegisterRequest(addr, "password123", "N", true))` **is empty**, `folded == 319` | re-establishes the ticket's measured premise in this build (HV 9.1.0, JDK 21) instead of quoting 2026-09-09 |
| **P2** | the same address through `CreateUserRequest` | M2 reachable (expected: yes) |
| **P3** | `60 ASCII + "@" + <194-char domain, labels ≤ 63, containing exactly one U+0130>`: assert `raw == 255`, `folded == 256`, `IDN.toASCII(domain, ALLOW_UNASSIGNED).length() <= 255`, and `validator.validate(new InviteMemberRequest(addr, null, "MEMBER"))` **is empty** | **M3 reachable or not.** If HV or `IDN` refuses it, M3 becomes "safe by `@Pattern` + `@Email`'s ASCII-domain check" — and then the *deliverable for M3 is a test that pins that refusal to `@Email`/`@Pattern`* (the shape of `MailAddressesThrottleKeyTest#pastTheWorstCaseItIsValidationThatRefusesAndNotTheColumn`), plus a row in Seal B's pattern map. Not silence |
| **P4** | `64 × U+0130 + "@" + worstCaseDomain()` (reuse `MailAddressesThrottleKeyTest`'s existing `EXPANDING_LABEL`/`FILLER_LABEL` fixture, raw = 85): assert violations **empty against `RegisterRequest`**, `MailAddresses.throttleKey(addr).length() == 384`, `> columnWidth("recipientKey")` | **M4 reachable, and unaffected by the M1 fix** — folded address is 149, well inside 255, so the door bound does not save this column. This is the row that makes M4 part of this ticket rather than a follow-up |
| **P5** | boot (or call `DataSeeder.run` directly) with `SEED_ADMIN_EMAIL` = a 300-character valid address | M5: what an operator sees today (expected: `22001` out of the seed INSERT, no mention of the property) |

Each probe is pasted into the builder's report with its output. **P3 and P4 may change §3**; nothing
downstream in this spec assumes their result beyond what the table already labels.

## 5. Actors & permissions

No permission changes. The four request doors keep their present gates:

| Door | Actor | Gate (read) |
|---|---|---|
| `POST /api/auth/register` | anonymous | none; `app.registration.public-signup-enabled` (DC default **off**) |
| `POST /api/admin/users` | system `ADMIN` | `SystemRole.ADMIN` |
| `POST /api/workspaces/{ws}/invites` | workspace member | `WorkspaceAccessService.requireMember` → `requireMemberAdmin(ctx.permissions())` + grant ceiling |
| `POST /api/auth/forgot-password`, `/resend-verification` | anonymous | none (uniform responses) |
| `SEED_ADMIN_EMAIL` | operator at boot | n/a |

A refusal added here **must not become an oracle**: every refusal in §6 is a statement about the
bytes the caller submitted, decided before any lookup, so it discloses nothing about any other
account. Tenancy is untouched — nothing here reads or widens a scope.

## 6. Behaviour & rules

### 6.1 Where the bound goes — the central decision

**(a) A shared measure-after-fold gate, called at each write site + (b) a truncation at the site
that produces the derived key.** Not an entity setter for the identity columns. The reasoning, once:

HD-171 rule (a) says a derived value is "either bounded by its target column's width or truncated at
the write site, and the truncation belongs on the **entity setter**, not in one service" — because
`issue_history` had four writers and the belt was installed on one. **That rule is about a
truncation, and an email must be refused, not truncated**: truncating an identity hands the account
to a *different address* (`users.email` carries a byte-exact unique and every lookup is an exact
match — `AuthService.java:90–96`, read; `acceptInvite` matches the invited address exactly —
`WorkspaceService.java:243–246`, read). A setter cannot refuse usefully either: it would throw at
assignment or flush — i.e. **after** the bcrypt and the ceiling have been spent, which is the whole
complaint of this ticket. So:

- **Identity columns (M1, M2, M3, M5) → refuse at the door**, through one shared gate, placed exactly
  where the fold already is, above every spend.
- **Derived key (M4) → truncate where the key is produced**, in `MailAddresses.throttleKey`'s exit,
  so all four callers get it and no call site can forget it. Truncation is right here for two
  published reasons: over-folding is the **fail-safe direction for a ceiling** (an extra match raises
  a count and refuses sooner — `MailAddresses.java:66–71`, read), and a `22001` *inside* the throttle
  **rolls back the ceiling row the caller has already been refused on**, which is the free-probe
  hazard the invite ordering comment exists to prevent (`WorkspaceService.java:297–302`, read).
- **What replaces HD-171's setter belt as the anti-drift mechanism is the seal** (§12), not a setter:
  members enumerated from bytecode, so a fifth door is a member the day its call compiles.

**The general form, for the next transform:** *the truncation belongs on the setter; the refusal
belongs at the door; which one a value gets is decided by whether it is an identity.*

### 6.2 The gate

In `common.mail.MailAddresses` — the class that already owns the address fold — mirroring
`ClassificationNames.requireValidName(raw, maxLength, noun)` deliberately, because that is the
codebase's one shape for "measure a derived name's length where it is derived" (read,
`ClassificationNames.java:93–107`):

```
/** Fold for storage and refuse what the column cannot hold. Never a throttle key. */
public static String requireStorableAddress(String raw, int maxLength)
```

- folds with `Locale.ROOT` (unchanged semantics — the fold **is** the account identity, HD-120),
- refuses when the **folded** length exceeds `maxLength`,
- returns the folded value, so a caller cannot use the gate and then store the raw value.

Its javadoc must say, in one paragraph, that it is **the opposite of `throttleKey`**: `throttleKey`
is a throttle key and **never** a recipient or a stored identity (`MailAddresses.java:122–125`,
read); `requireStorableAddress` returns the identity and refuses rather than folding further. Both
belong in this class because both are address handling more than one mail path needs.

`maxLength` is the door's own repeated literal `255` (ADR-0017: repeated, never imported — a call
site that reads the constant it is testing agrees with any value it takes).

### 6.3 Status code and message

**400**, matching **`ClassificationNames.requireValidName`** (read: `HttpStatus.BAD_REQUEST`), not
the 422 of `PasswordLimits`/`PasswordTooLongException`. The discriminator, stated so the next bound
does not have to re-argue it: **a bound that is a column width answers 400; a bound that is an
algorithm's refusal answers 422.** BCrypt's 72 bytes is a property of the encoder and cannot be
expressed in the unit the client counts in; 255 characters is the same kind of statement `@Size`
already makes on the same field with the same status.

The `detail` must prescribe an action its reader can perform — the caller cannot see a "folded
length", so it says what to change and gives the arithmetic:

> `Email address must be at most 255 characters (lower-casing it turned its 255 characters into 319) — use a shorter address.`

Parenthesis only when the raw value was **within** the limit (the `requireValidName` shape: "at most
255 characters" is otherwise a lie to somebody who typed 255). **No `errorType`** — and a test must
refuse `errorType: VALUE_TOO_LONG`, because the `22001` backstop also answers 400 and a status class
alone cannot see this bug (HD-297's lesson; `RequestFieldLengthBoundTest.java:157–162`, read).

### 6.4 Ordering on the register door

The measurement replaces the fold **in place, at `AuthService.java:97`**. That is:

- **below** the two password refusals (`:86–89`), so `SignupRefusal`'s existing counts keep their
  present order and meaning;
- **above** `existsByFoldedEmail` (`:104`) — one fewer query for a refused request, and no oracle:
  the refusal is about the caller's own bytes;
- **above** `passwordEncoder.encode` (`:129`) and **above**
  `mailThrottle.requireAndRecordWhereEndpointDiscloses` (`:172`).

The bcrypt-before-ceiling argument must survive untouched. Quoted (read, `AuthService.java:107–122`):

> "The password is hashed BEFORE the ceiling below, and that ordering is deliberate and was argued
> against the review that asked for the opposite. Spending the ceiling first would save a refused
> caller one bcrypt-12 (~370 ms) — but it would put that bcrypt INSIDE the advisory lock
> `RecipientMailThrottle` takes on the recipient key … THE FACT THAT MAKES THAT NON-OBVIOUS, AND THE
> ONE A READER GETS WRONG: `pg_advisory_xact_lock` IS HELD TO COMMIT, NOT TO THE END OF THE METHOD
> THAT TOOK IT … which is why moving work ABOVE the ceiling is the only way to keep it out."

The new check moves **nothing** across the ceiling and adds nothing inside the lock: it is an
O(length) fold and a length comparison, placed above both. The comment stays as it is.

**Invite door (`WorkspaceService.java:254`)** — the measurement goes at the existing fold, i.e.
**above** `inviteThrottle.requireSenderVolume` (`:289`) and therefore above the recipient half. This
is a deliberate reading of that method's "every refusal on this path lands ABOVE the recipient half,
so each check added made one more refusal FREE" (read, `:275–283`): **that rule is about refusals
that require a LOOKUP** (duplicate invite, existing membership) — those are the ones that probe
somebody else's state and must therefore cost the caller. A refusal decided from the submitted bytes
alone is a *validation* refusal, it is already free for every other bound on this door (`@Size`,
`@Pattern`, `@Email` all refuse above everything), and charging a sender-volume slot for a malformed
body would let one bad request burn a legitimate inviter's hourly budget. **This is the
highest-risk placement judgement in the spec** and the one to argue with first.

**Admin door (`AdminUserService.java:57`)** — at the existing fold, above `existsByFoldedEmail` and
above `generateSetupLink`.

**Seed (`DataSeeder`)** — a **fail-fast at boot**, not a request refusal, added beside
`rejectOverLongPassword` in `refusePublishedCredentials` (`:129–134`): measure
`adminEmail.strip()` folded against 255 and throw `IllegalStateException` naming the property
(`seed.admin.email` / `SEED_ADMIN_EMAIL`), the limit, and the remedy — the shape and voice of
`rejectOverLongPassword` (read, `:215–226`). A startup crash and a request refusal are different
failure shapes and the operator gets the one they can act on: the message names the variable they
set. **This also closes the plain over-long case**, which needs no fold at all (§3 M5).

### 6.4.1 The derived key (M4)

In `MailAddresses.throttleKey`, before returning: if the key exceeds **320**, truncate to 320, and
report it (§13 — a silent drop is the silence the retro named). The number is a repeated literal
with a comment pointing at `MailSendEvent.recipientKey` and `failed_email.recipient`
(`FailedEmail.java:29`, read — truncated at its write site by `MailService.truncate(to, 320)` when
this was written, `MailService.java:283`, read; the precedent for truncating a forensic mail value.
**Round 3 inverted that relationship:** the precedent counted nothing, logged nothing and split
surrogate pairs, so it now reaches the shared cut through `MailService.fitStoredRecipient` and is a
third series on the counter rather than the exemplar).

Truncation is **not a belt under the door bound, and calling it one was wrong** (corrected in the fix
loop): the register door's worst key is 384 produced by an address that folds to **149** — 106
characters inside the 255 it refuses at — so the door bound is not evidence about this column at all.
The two mechanisms are independent, and each column is bounded at the site that writes it or by
nothing. The cut is counted so we learn when it fires.

### 6.4.2 The forensic copy (M4b) — the member round 1 of this spec missed

`RecipientMailThrottle.record` writes **two** columns from the address its caller folded, one line
apart, and §6.4.1 above bounded one of them. The same fold reaches `recipient_email`, whose widest
reachable value is **347** (measured), and the two callers that can send it are
`forgot-password` / `resend-verification` — unauthenticated, with no gate above them *by design*
(§6.2 deliberately does not apply: a refusal there would change the shape of an endpoint whose whole
contract is one uniform response, and the address on that path is not an identity).

So the same decision, in the same shared place: `MailAddresses.fitStoredRecipient` cuts the value to
the column at the write site, both cuts share one width literal and one witness
(`hamstrack.mail.stored_address_truncated{column}`), and the seals are
`MailAddressesThrottleKeyTest#theWidestAddressEveryWritingDtoStoresFitsTheForensicColumn` (every
writing DTO), `#everyProductionWriteOfTheForensicAddressGoesThroughTheFit` (the write really goes
through the fit) and
`RequestFieldLengthBoundTest#theAnonymousUniformDoorRecordsAnOverLongFoldedAddressWithoutChangingItsAnswer`
(the answer does not change, the row survives, the counter moves).

**Why the miss is worth writing down.** This spec's own member table asked "which fold sites store a
value?" and answered with one column per site. A site can store more than one, and the category test
had the same shape one level up — its unit was a class, so one row about `register` covered
`AuthService`'s two other fold-and-store methods. Both are fixed by making the unit finer: a site,
and a *column*, rather than a class.

## 7. Edge cases & failure modes

| Case | Required behaviour |
|---|---|
| raw **over** 255 | unchanged: `@Size` answers 400 at the boundary, gate never reached |
| raw within 255, folded over 255 | 400 from the gate, naming 255 and the arithmetic, **no** `errorType`, **no** ERROR line |
| folded length exactly 255 | accepted (the bound is `>`, not `>=`) |
| non-ASCII **domain**, folded length within 255 | accepted; unchanged behaviour |
| `null` / blank address | unchanged — `@NotBlank` answers first; the gate treats `null` as `""` and refuses nothing (mirrors `ClassificationNames.normalize`) |
| U+212A KELVIN → `k`, U+0131 dotless ı | folds are unchanged; only the *length measurement* is new (`MailAddressesThrottleKeyTest:296–314`, read) |
| concurrency | none introduced: the gate is pure and takes no lock; it runs before the advisory lock is taken |
| refused register request | **no** bcrypt, **no** `mail_send_events` row, **no** `users` row, no mail |
| refused invite | no sender-volume spend, no recipient row, no invite row |
| key over 320 (M4) | truncated, counted, logged with the **domain only**; two inboxes may share a bucket — over-fold, the fail-safe direction for a ceiling |
| stored address over 320 (M4b) | truncated at the write site, counted on the same meter with `column="recipient_email"`, logged with the **domain only**; **no status change on any door** — in particular the two uniform-response endpoints still answer exactly what they answered |
| `SEED_ADMIN_EMAIL` over 255 folded | boot refuses, naming the property; an installation that already seeded the account is **unaffected** (mirror `rejectOverLongPassword`'s "a row already occupies the folded address → return", `DataSeeder.java:187–198`, read) |
| a future JDK/Unicode revision adding a lengthening lowercase mapping | nothing to change: the bound is measured, never computed from a table |
| `@Validated` | **not** added anywhere (ADR-0018). The gate is a service-layer call, not an annotation |

## 8. Data model impact

**No migration.** No column changes width, no entity changes length, no denormalised `workspace_id`
is introduced. `migration-reviewer` is **not** armed unless the builder edits an `@Entity` (the only
planned entity edit is **javadoc** on `MailSendEvent.recipientKey` — §3.1).

**Why `recipient_key` is truncated rather than widened to 384.** (1) A width of 384 would again rest
on third-party invariants nothing in the codebase states — HV's 64/255 *and* the JDK's
one-code-point-to-two mapping — so the same defect recurs on the next transform, silently. (2) A
truncated *key* is fail-safe by that class's own published argument; a `22001` there is not, because
it rolls back the ceiling row. (3) The javadoc already pairs this column with
`failed_email.recipient` ("widen the column and `failed_email.recipient` with it"), so widening is a
two-table change bought to preserve an invariant we are deleting anyway. Entity↔schema parity is
therefore unchanged and `ddl-auto=validate`'s blindness to widths (CLAUDE.md) costs nothing here.

## 9. API surface

No new endpoint, no request or response **shape** change. Four existing endpoints gain one more
reason for a `400 application/problem+json`:

- `POST /api/auth/register`
- `POST /api/admin/users`
- `POST /api/workspaces/{workspaceId}/invites`
- (M4 only) no status change on `POST /api/auth/forgot-password` / `/resend-verification` — they keep
  their uniform responses; the truncation is invisible to the caller by design.

`api-docs-sync` arms: `src/main/frontend/public/openapi.yaml` + `docs/api-cloud.md` +
`docs/api-dc.md` wherever those enumerate 400 reasons for the three write doors.

## 10. Frontend impact

**None required.** `spring.mvc.problemdetails.enabled=true` and the SPA's `request()` already render
`detail` (CLAUDE.md § Gotchas, read), so the message in §6.3 is the entire user-facing surface — one
line, no truncation in the banner, no new copy, no config-driven element. `DESIGN.md` is not
engaged; `browser-qa` is **not** armed unless the builder edits SPA files (it should not).

## 11. DC / Cloud

**Both modes, identically. Confirmed, not assumed:** the gate is a length comparison against a
column width that is the same in both schemas; no property decides it, so there is nothing to
profile-gate and no env var to wire. Two mode-shaped notes:

- M1 is reachable only where `app.registration.public-signup-enabled=true` — **Cloud's** default;
  DC's default is closed, so DC's exposure of the *unauthenticated* member is via an operator who
  opened signup deliberately. M2/M3 are reachable in both.
- M5 is the **DC install path** (`SEED_ADMIN_EMAIL` is how a self-hosted instance gets its first
  admin) and is therefore the member with install-readiness value (HD-312 epic).

`dc-cloud-guard` is **not** armed: no file in the config area changes. If the optional alert rule in
§13 ships, `observability/**` changes and the **`ops_witness`** gate arms instead.

## 12. The seal

Three seals, each phrased over a category, each with a floor and a named red-before-green plant.
**Nothing here duplicates HD-297's machinery — it extends it.**

### Seal A — behavioural, per member: `RequestFieldLengthBoundTest`

- New `Expect.REFUSED_AFTER_FOLD` and a factory `Row.growsUnderFold(id, path, body, as, door, limit)`
  beside the existing `Row.growsUnderNfc`.
- **Share the judge, do not copy it:** generalise `judgeCanonicalisationRow` into one
  `judgeDerivedValueRow(row, response, transform)` used by both the `[nfc]` and the `[fold]` rows —
  the three verdicts are identical (status must be 400/422; body must not carry
  `errorType: VALUE_TOO_LONG`; `detail` must contain the limit) and only the sentence differs.
- Rows: `AuthController#register[fold]` (ANONYMOUS), `AdminUserController#create[fold]` (ADMIN),
  `WorkspaceController#invite[fold]` (MEMBER, P3-dependent — if P3 shows growth unreachable, this
  row becomes a `pastTheWorstCase`-shaped refusal test in `MailAddressesThrottleKeyTest` instead,
  and M3 joins Seal B's pattern map).
- `MIN_ROWS` **45 → 48** (raise with the rows; never lower it to pass).
- Additionally, for at least one row, assert with a `ListAppender` (precedent: 10 test files in the
  tree, read) that **no ERROR line is logged** — the ERROR line is half the defect and the
  `errorType` check alone does not see it.

### Seal B — the category claim: `everyDoorThatCaseFoldsAValueItStoresMeasuresItsBoundAfterTheFold`

Mirrors `everyDoorThatCanonicalisesANameMeasuresItsBoundAfterCanonicalisation` (read,
`RequestFieldLengthBoundTest.java:570–628`), in the same class, reusing `Population` and
`ProductionBytecode`.

- **One new method on `ProductionBytecode`** (test support, not production):
  `callersOfMethods(Set<String> throughHelpers, Class<?> owner, Set<String> methodNames)` — the same
  single `MAIN` import and the same fixpoint walk as `callersOf`, with the target matched on
  **owner + method name**. Required because `callersOf(String.class)` would match every class that
  calls any `String` method, i.e. the whole tree. Its javadoc states that blind spot is why it
  exists.
- **Population:** `callersOfMethods({MailAddresses, SearchNames}, String.class, {toLowerCase, toUpperCase})`
  ∪ `callersOf({MailAddresses, SearchNames}, MailAddresses.class)`, walked **through** the helper set
  so a future delegator is never a hiding place. Predicted size ~30 classes (read from the grep);
  **the builder sets `FOLD_CALLER_FLOOR` from the measured population, pastes the member list, and
  the spec's number is a prediction, not a bound**.
- **Exclusions, each live-checked by `Population.excluding`:**
  1. `FOLD_HELPERS` — "the helper itself; its callers were followed".
  2. `READ_SIDE_FOLDERS` — the ~20 classes of §3's out-list, one line of reason each ("folds a
     lookup key / an operand / a sort key / a metric tag and stores nothing").
  3. `TRUNCATING_DERIVERS` — M6/M7/M8, "derives a slug or key: substitutes, truncates inside the
     width, and carries its own `[slug]`/`[key]` row".
  4. `PATTERN_BOUNDED` — M9/M10, **and this entry is not prose**: a map
     `door → (requestRecord, component, exact @Pattern regexp literal)`, with the test asserting the
     live annotation still reads that literal (`"[A-Z0-9]+"`, `"[a-z0-9_]*"`). Relaxing either
     pattern reds the build and forces the door to get a row — which is the only honest way to
     exclude something for being ASCII-only by construction.
  5. `DataSeeder` — "not request-reachable; refuses at boot instead", naming the test that holds it
     (`SeedGuardStartupOrderingTest`).
- Every remaining member must be the `door` of a `[fold]` row; every `[fold]` row must name a class
  that still folds (stale-row check, both directions, as HD-297 does).
- Failure message = the propagation checklist, ≤ 25 lines, four remedies (route it through
  `MailAddresses.requireStorableAddress` + add a row / add to `READ_SIDE_FOLDERS` / add to
  `TRUNCATING_DERIVERS` / add to `PATTERN_BOUNDED` with the literal), plus "do not lower either
  floor".

### Seal C — the arithmetic: `MailAddressesThrottleKeyTest`

- **Re-phrase the false claim over the category.** Rename and rewrite
  `theWidestKeyReachableThroughTheRealDtoConstraintsExactlyFillsTheColumn` →
  `theWidestKeyReachableThroughEveryWritingDtoFitsTheColumn`, parameterised over **every DTO that
  reaches the throttle** — `RegisterRequest`, `ForgotPasswordRequest`, `ResendVerificationRequest`,
  `InviteMemberRequest` — asserting for each that the key of its worst case is `<= columnWidth("recipientKey")`.
  The width stays read off the entity.
- Add the U+0130 local-part fixture (P4) and assert: the address passes `RegisterRequest`'s
  constraints, the **pre-truncation** key would be 384, and the produced key is exactly 320 and the
  counter moved.
- Update `MailSendEvent.recipientKey`'s javadoc: the 64/255/320 arithmetic is stated as a property of
  **`InviteMemberRequest`'s ASCII local part only**, and the general bound is the truncation. Delete
  the "a future fold that ever APPENDS … needs a wider column first" sentence — it described this
  defect as hypothetical and it is not.

### Red before green — the exact plants

| Seal | Plant | Expected red |
|---|---|---|
| A | run the three rows **before** the gate exists | each row → `400 errorType=VALUE_TOO_LONG` (+ an ERROR line), the judge naming the door and the arithmetic |
| A | after the fix, remove the gate call from **one** door (register) | that row only, named |
| B | after the fix, remove the gate call from `AdminUserService` | `com.hamstrack.admin.service.AdminUserService: case-folds a value it stores and has no [fold] row` |
| B | rename `FOLD_HELPERS`' `SearchNames` entry to a class that no longer folds | `Population.excluding`'s stale-member failure |
| B | relax `CreateProjectRequest.key`'s `@Pattern` to `".*"` | the `PATTERN_BOUNDED` assertion, naming `ProjectService` |
| C | run the new parameterised test **before** the truncation | `RegisterRequest` case → key 384 > 320 |
| M5 | boot with a 300-character `SEED_ADMIN_EMAIL` before the guard | `22001` from the seed INSERT; after: `IllegalStateException` naming `SEED_ADMIN_EMAIL` |

## 13. Observability contract

| Failure mode | Witness in production | Drill that proves the witness fires |
|---|---|---|
| a **fifth** fold-then-store door ships with no bound | unchanged and deliberate: `GlobalExceptionHandler:1097` — 400 + **ERROR** carrying method, mapped pattern, SQLSTATE and the parameterised SQL (so the *table* is named) | Seal A's red-before-green run **is** the drill: the rows are watched producing that exact line, and the output is pasted |
| M1 refused on the unauthenticated door | **new** `SignupRefusal.EMAIL_TOO_LONG("email_too_long")` → `hamstrack.auth.signup_refused{reason="email_too_long"}` | `AuthServiceTest`/the Seal A row asserts the counter moved on a refusal; a non-signup door's refusal must leave it unmoved |
| M2 / M3 refused | the 400 in the access log. **No counter**, stated as a decision: the caller is authenticated and authorised, a mistyped address is not an incident, and a counter per door would be cardinality with no question behind it | n/a (declared, not silent) |
| M4 truncation fires (two inboxes share a ceiling bucket) | **new** `ProductMetrics.mailStoredAddressTruncated(TruncatedMailColumn)` → `hamstrack.mail.stored_address_truncated_total{column}`, one series per column that holds a stored copy of a recipient address (`recipient_key`, `recipient_email` — the fix loop found that the SAME fold overflows both — and, from round 3, `failed_email_recipient`, which was cut by an uncounted `MailService.truncate` while the exclusion covering all three said each was cut *and counted*); plus one log line carrying `MailAddresses.domainOf` — **domain only**, never the local part (the class's existing rule, `MailAddresses.java:10–16`, read). **The level is `DEBUG` for an anonymous caller and `INFO` when there is a sender to name, and every path that can fire today is anonymous** — one `INFO` per request on `forgot-password` / `resend-verification` is ~370 characters of attacker-chosen text bounded only by a per-IP window a proxy pool defeats (measured, round 2 review), and the only sender-carrying call site (`InviteThrottle.requireRecipientCeilings`) overflows neither column: worst-case key **320 exactly**, worst-case folded address **85** (measured 2026-09-13). So the INFO branch is unreachable until a door with a sender can overflow a column, and the counter — not the log — is the witness | `MailAddressesThrottleKeyTest` asserts the counter moved against a `SimpleMeterRegistry`; `FailedEmailBoundsTest.anOverLongDeadLetterRecipientIsCutToTheColumnAndCounted` does the same for the third column against a real row; plus the operator read-back (`docs/release-checklist.md` step 6: the counter in Explore and `length(recipient_email) = 320` in SQL) |
| M5 boot refusal | the `IllegalStateException` message (fatal, so the boot log *is* the witness) | `SeedGuardStartupOrderingTest` row |

**Two rules the counter must obey.** (i) `signup_refused` must **not** move for a non-signup door —
so the count is emitted at register's own call site, never inside the shared gate, exactly as the
two password refusals are (`AuthService.java:83–89`, read: "a reset is not a signup, and a counter
named `signup_refused` must not move for one"). The mechanical shape is a private
`AuthService.requireStorableEmail(String, Runnable refused)` mirroring `rejectUnencodablePassword`.
(ii) **`SignupRefusal`'s category sentence becomes false with this member and must be re-phrased**:
it currently reads "every refusal `AuthService.register` makes **before `req.email()` is read**"
(read, `ProductMetrics.java:379–383`) — a refusal *about* the address is by definition after it is
read. Re-phrase to "every refusal `register` makes **before it spends a bcrypt or a ceiling**",
which is the property the counter is actually for, and which the new member satisfies.

**Docs:** one row in `docs/observability.md`'s auth table for the new `reason` value and one for the
new counter. **Optional alert** (recommended: ship it) —
`sum(increase(hamstrack_mail_stored_address_truncated_total[1h])) > 0`, `for: 0m`, severity
`warning`, in the same group and with the same shape as `MailDeadLetterSkipped`: a non-zero value
means either somebody is probing with pathological addresses or the 320 arithmetic has drifted
again. Shipping it arms `ops-reviewer` (`observability/**`).

## 14. Acceptance criteria

**Phrased over the category. The per-member rows are evidence for the criteria, not the criteria.**

1. **Every door that case-folds a caller-supplied value and stores the folded result measures its
   bound after the fold.** Held by Seal B (bytecode caller-set, floor from the measured population,
   four live-checked exclusion classes). A new folding-and-storing class with no row is a red build.
2. **No endpoint that accepts an address answers with the `22001` backstop's signature.** For every
   member: status 400, `detail` names `255`, body carries **no** `errorType: VALUE_TOO_LONG`, and no
   `ERROR` line is logged. Held by Seal A's shared judge + one `ListAppender` assertion.
   *Observed effect required:* each row watched red first with the backstop answering, output pasted
   (dated in the builder's report).
3. **A refusal on an identity column costs the caller nothing beyond the request**, and in
   particular spends no bcrypt and no ceiling. Held by: a `@MockitoSpyBean`/spy `PasswordEncoder`
   never invoked on a refused register request, and `mail_send_events` row count unchanged across it.
4. **Every refusal in the category prescribes an action its reader can perform**: it names the limit
   and what to change, and never a quantity the caller cannot observe. Held by the judge's
   `detail.contains("255")` and read in review for the wording.
5. **No value derived by folding can exceed any column it is stored in**, whether the mechanism is a
   refusal or a truncation. Held by Seal C parameterised over **every** DTO that reaches the
   throttle — not the one it was written against.
6. **Every drop or skip on this path has a witness.** The truncation increments a counter and logs a
   domain; the register refusal increments `signup_refused{reason="email_too_long"}`; a non-signup
   door leaves that counter unmoved. Held by registry assertions in the two tests + §13's read-back.
7. **The seal fails by naming the door.** Held by the five plants in §12.

**Per-member behavioural rows (evidence for #2):**

| Row | Payload | Expected |
|---|---|---|
| `AuthController#register[fold]` | `64 × U+0130 @ <190 ASCII>` (raw 255, folded 319) | 400, detail names 255, no `errorType`, no ERROR, no bcrypt, no `mail_send_events` row |
| `AdminUserController#create[fold]` | same address | 400, same assertions |
| `WorkspaceController#invite[fold]` | `60 ASCII @ <194-char domain, one U+0130>` (raw 255, folded 256) | 400, same assertions — **or**, if P3 refutes reachability, a test pinning the refusal to `@Email`/`@Pattern` and a `PATTERN_BOUNDED` entry |
| `MailAddressesThrottleKeyTest[fold]` | `64 × U+0130 @ worstCaseDomain()` (raw 85, folded 149, key 384) | key == 320, counter moved, no `22001` |
| `SeedGuardStartupOrderingTest[fold]` | `SEED_ADMIN_EMAIL` folded over 255 | boot refuses, message names `SEED_ADMIN_EMAIL` and 255 |

## 15. Open questions, each with a recommended default

1. **Does `@Email` accept a 64-character U+0130 local part, and does HV/`IDN` accept a domain
   carrying one U+0130 at raw 255?** → probes P1/P3/P4. **Default:** build the fix for M1/M2/M4
   regardless (P1's premise is already gate-measured); treat M3 as pattern-bounded **only** if P3
   refutes it, and record the refutation as a test rather than as prose.
2. **Should the invite refusal cost a sender-volume slot?** **Default: no** (§6.4). If review
   disagrees, the measurement moves below `requireSenderVolume` and above the duplicate check, and
   the reason is recorded at both lines.
3. **Widen `recipient_key` to 384 instead of truncating?** **Default: no** (§8). Revisit only if a
   truncation is ever observed in production — which is what §13's counter is for.
4. **Bound `seed.admin.email` with `@Size`/`@Email` by moving it onto a `@ConfigurationProperties`
   class?** **Default: not in this ticket** — it is a live `@Value` field with four read sites, and
   the boot refusal in §6.4 closes the defect. File it as a follow-up if the install epic wants a
   bound property (`dc-cloud-guard`'s fail-fast-on-properties rule would then apply).
5. **Should `EmailLengthBoundTest`'s source scan also demand a post-fold measurement?** **Default:
   no** — a source scan cannot see a derived value (HD-171's central finding), so the post-fold
   guarantee lives in Seal A/B. Add **one paragraph** to that class's javadoc saying so, pointing at
   Seal B, so the next reader does not mistake its clean pass for this guarantee.

## 16. ADR

**None.** This applies a rule that is already recorded and already has a mechanism — HD-171 §3.3(a)
(a derived value is bounded at its target width, and the guarantee is a behavioural category test)
and HD-297 (the same rule for NFC, with the caller-set seal). The one sub-decision that looks
ADR-shaped — truncating a throttle key rather than refusing it — is settled by ADR-0015 plus
`MailAddresses.throttleKey`'s published over-fold argument, so it belongs in a comment at the
truncation, not in a new ADR. Nothing here is hard to reverse: no migration, no property, no forked
behaviour.

## 17. Backlog search

**Searched (2026-09-13, in-tree; the HD tracker itself could not be queried from this session — no
execution tool):** `docs/design/*.md`, `docs/adr/*`, `docs/project-state.md` and the test tree for
`fold-then-store`, `toLowerCase`, `toUpperCase`, `U+0130`, `0130`, `VALUE_TOO_LONG`, `22001`,
`recipient_key`, `320`, `throttleKey`. **No match** — there is no prior proposal for this defect
class and no duplicate design doc. **Related, and deliberately not duplicated:** HD-171
(`docs/design/request-field-length-bounds-proposal.md` — the parent rule), HD-297
(`RequestFieldLengthBoundTest#everyDoorThatCanonicalisesANameMeasuresItsBoundAfterCanonicalisation`
— the seal shape), HD-120 (`docs/design/email-uniqueness-proposal.md` — the folds themselves),
HD-190 (`docs/design/invite-budget-proposal.md` — `throttleKey` and `mail_send_events`), HD-202
(the three anonymous mail doors), HD-261/HD-298 (`SignupRefusal`). The builder should re-run the
search in the tracker and record it in the ticket.
