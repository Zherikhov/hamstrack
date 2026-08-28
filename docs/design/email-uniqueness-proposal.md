# Account identity — a database guarantee for `users.email`, not an application convention (HD-167)

**Status:** proposal / design review. **Date:** 2026-08-28. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness), priority **High**.
**Migration:** **V23** (`V23__users_email_uniqueness.sql`) — two pre-flight probes and one functional
unique index. **No data is written, folded or deleted.**
**Related:** HD-120 (fold once at the boundary with `Locale.ROOT`, compare exactly ever after — the
ticket that found the locale bug and shipped `docs/self-hosting.md` §"Duplicate accounts after an
upgrade"), HD-133 (`V22`, `workspace_invites (workspace_id, lower(email)) WHERE accepted_at IS NULL`
— the same shape on the neighbouring column, plus the SQLSTATE-gated constraint translation this
spec reuses), HD-13 (`GlobalExceptionHandler.handleDataIntegrityViolation`).
**Touches:** `V23__users_email_uniqueness.sql`, `UserRepository`, `AuthService.register`,
`AdminUserService.create`, the admin find-or-create inline in `DataSeeder.run` (there is no
`seedAdmin` method to grep for — older prose across this repo, and ADR 0016, name one), `User`
(javadoc only), `docs/self-hosting.md`, tests. **No new configuration property. No new environment
variable. No profile gating. No frontend change.**

---

## 0. The premise, and the four corrections that change what gets built

The ticket's core claim is **confirmed on every point**. `users.email` is
`VARCHAR(255) NOT NULL UNIQUE` (`V1__init_schema.sql:41`) — byte-exact. `UserRepository` exposes
`findByEmail` and `existsByEmail`, both derived exact-match queries. There is no `citext`, no
functional index, no `CHECK`. The three writers of the table (`AuthService.register`,
`AdminUserService.create`, `DataSeeder.run`) each fold with `toLowerCase(Locale.ROOT)`, and
that fold is the only thing keeping `Ivan@x.com` and `ivan@x.com` from being two accounts. Verified
by grep, not assumed: `new User()` appears at exactly those three sites.

Four corrections follow. Two of them change the design.

### Correction 1 — the detection query the ticket asks for cannot see the failure the ticket is named after, and the ticket owner already found this

`WHERE email <> lower(email)` finds a row that is **not equal to its own fold**. That is a *case*
difference. The failure HD-120 actually hit is a *locale* difference: a Turkish JVM folded `I` to a
dotless `ı` (U+0131), which is **already lowercase**, so `lower()` leaves it alone, the predicate is
false, the row reads clean — and the application, folding the typed `I` to `i` with `Locale.ROOT`,
never matches it. The account is unreachable and the detection says nothing is wrong.

**Consequence for the design: two populations, two predicates, two different outcomes.** They are
not variants of one problem and must not share a remedy (§6).

| | population | predicate | blocks the index? | V23's answer |
|---|---|---|---|---|
| **A** | *case*-broken — the stored value is not its own fold | `email <> lower(email)` | yes, if two of them fold together; and it silently squats a folded key even alone | **refuse the migration** |
| **B** | *locale*-broken — a well-formed lowercase address that no typed address now folds to | **none exists** | no | **`RAISE NOTICE`, point at the existing operator section** |

Population B is the honest one to state as a property: **it is not detectable from the stored value
at all.** The stored value is a legal lowercase address; it is wrong only relative to a `Locale.ROOT`
fold of the *typed* address, and the typed address was never stored. `email ~ '[^\x00-\x7F]'` is a
*proxy* — it is the fingerprint of a locale-dependent fold **and** of a perfectly legitimate
internationalised address, and no query can tell those apart. `docs/self-hosting.md` §"Duplicate
accounts after an upgrade" already says exactly this ("a hit here is a flag, not a verdict") and
already ships both queries and all three remedies. V23 must point at that section, not invent a
second one.

### Correction 2 — `lower()` is the same locale-dependent function HD-120 removed from Java, and `docs/self-hosting.md` already warns about it in writing

> **Amended after implementation (2026-08-28): the argument below is real but conditional, and the
> decision does not rest on it.** "Contains no uppercase" is a claim about **Java's** Unicode tables
> (Java 21 = Unicode 15.1), while `lower()` reads the **provider's** (glibc 2.28 ≈ Unicode 11,
> ICU 72–75 ≈ 15–15.1) — so identity really requires *the provider to know no case mapping the JDK
> lacks*, a Unicode-data-version claim rather than an "uppercase only" one. It holds today. The
> **unconditional** argument, and the one the header now leads with, is Correction 3's: every
> comparison that matters applies the *same* `lower()` to both sides, so a provider disagreement can
> only **manufacture a collision** — a 409, visible, told to the caller — and never a false "free".
> That survives any provider and any Unicode version, which is what makes it, not this, the reason
> to prefer the index over `citext`.

The ticket proposes `UNIQUE (lower(email))`. The project's own operator manual (section *"Duplicate
accounts after an upgrade"*, the `SELECT … WHERE email = 'it-admin@corp.com'` block — cited by
section rather than by line, because the line moved once already) says:

> `-- Type the address in lower case yourself. Do NOT wrap it in lower(): that folds under the`
> `-- DATABASE's collation, and on a tr_TR cluster it reproduces this very bug from the SQL side`

That warning is correct and it does **not** sink the proposal — but the reason it does not is
load-bearing and belongs in the migration header rather than being rediscovered:

> **`lower()` is the identity function on a value this application has already folded.** The only
> characters whose lowercase mapping varies across locales and providers are **uppercase**
> characters — `I` → `i`/`ı`, `İ` → `i`/`i̇` (`docs/self-hosting.md`: *"exactly two characters can
> differ"*). A value written through any of the three writers contains none. So for every row the
> application wrote, the index key equals the stored value under **any** `LC_CTYPE`, on glibc, musl
> or ICU alike.

The index's exposure to the collation provider is therefore **bounded by the application's fold**,
and that boundary is what §4 turns into the reason to reject `citext` (whose exposure is not
bounded, because it re-folds the *raw, case-preserving* stored value on every comparison).

### Correction 3 — the guarantee introduces a new way to reach a 500, and the ticket does not mention it

> **Amended after implementation (2026-08-28), and the amendment is the interesting half.** The
> reachable case below is `1`, not `2`. Point 2 was written while the pre-check was still
> `existsByEmail`; §5.2 replaced it with `existsByFoldedEmail`, and that closed the case point 2
> describes without anyone noticing that it had. Measured: `Bob@x.com` inserted by direct SQL, then
> `bob@x.com` registered — **409 from the pre-check, no INSERT attempted.** The property, which is
> unconditional: the pre-check asks `lower(stored) = lower(:typed)`, the index enforces uniqueness of
> `lower(stored)`, and the value inserted is `:typed` — **all three go through the same PostgreSQL
> `lower()`**, so they cannot disagree for any input, under any `LC_CTYPE` or provider. *The
> pre-check and the index ask the same question of the same function, so they can differ only by the
> window between them — and a window is a race, not a fold.* The conclusion of this correction is
> unchanged and the reason for it is now the pre-existing race alone (reproduced with a `pg_sleep`
> BEFORE INSERT trigger: real 23505s on **both** constraint names, both answered 409).

A `23505` that reaches `GlobalExceptionHandler.handleDataIntegrityViolation` is answered **500**,
deliberately and in writing (*"a 23505 … that reaches here is a genuine fault with no sentence
written for it yet, and inventing one would be worse than a 500"*). Two facts follow:

1. The concurrent-signup race **already** answers 500 today, on `users_email_key`. Pre-existing, and
   nobody has noticed because it needs two simultaneous registrations of one address. From V23 there
   is a **second** constraint the same race can fail under, so the surface grows.
2. ~~`users_email_lower_uk` fires on an ordinary first-time signup against a database holding a
   population-A row: `existsByEmail('ivan@x.com')` finds nothing (the squatter is stored as
   `Ivan@x.com`), the INSERT is refused by the index, and a legitimate registration 500s.~~ True of
   the **exact** pre-check this correction was written against, and that is precisely why §5.2
   folds it. It is *not* true of the shipped code — see the amendment above. The counterfactual
   survives as the reason `existsByFoldedEmail` exists, and nothing more.

So this ticket cannot ship the constraint alone. It must ship the constraint **plus** the
`23505` → `409` translation at both writers, in HD-133's shape (§5.3). That also retires the
pre-existing race-500 for free, which is a gain worth naming rather than burying.

### Correction 4 — "keep the application fold" is right, and the reason is not the one people give

The usual reason ("the code gives a clean error instead of a constraint violation") is true but
secondary and is really an argument for the *pre-check*, not for the fold. The fold's own job is
different and survives every future change: **the address is stored, mailed and used as a lookup
key, and those three must be the same string.** `MailService` writes to `user.getEmail()`; the
byte-exact `findByEmail` reads it; `acceptInvite` compares it with `equals`. A constraint that only
governs *uniqueness* leaves all three of those free to disagree. Fold and constraint answer two
different questions and neither replaces the other.

---

## 1. Problem & goal

`users.email` is the account. It is what a person types to log in, what a password-reset link is
mailed to, and what `WorkspaceService.acceptInvite` matches an invitation against with `equals`. Its
uniqueness is byte-exact in the schema and case-insensitive only by convention — three call sites
that each remember to fold. HD-120 already found one way that convention broke silently (the fold
read the JVM default locale, so a Turkish container stored a dotless `ı`) and fixed the code without
making the rule enforceable. Any future write path that forgets the fold — an LDAP or SSO importer,
a bulk admin import, a support script — creates a second account for the same person with no error
and no signal, and this project has already named those importers as expected future work.

**Success:** `Ivan@x.com` and `ivan@x.com` cannot both exist as `users` rows, refused by PostgreSQL
rather than by a check somebody can forget; the refusal reaches the caller as the 409 the product
already has, never a 500; the application fold stays, so the stored, mailed and looked-up spellings
remain one string; a database that already violates the rule stops the upgrade with a sentence its
operator can act on, and **loses no account row to a migration**.

## 2. Scope

**In scope**

- `V23__users_email_uniqueness.sql` — a blocking pre-flight (population A), an advisory notice
  (population B), and `CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email))`.
- The write-side existence checks in `AuthService.register`, `AdminUserService.create` and
  `DataSeeder.run` move to a `lower()` query that asks the question the index answers.
- A `23505` → `409 EmailAlreadyUsedException` translation at those same writers, gated on SQLSTATE
  and constraint name, with HD-133's locale-proof fallback.
- Javadoc on `User.email` and on `UserRepository` stating which comparisons fold and which must not.
- One new subsection in `docs/self-hosting.md` (a pre-upgrade check for 0.18.0), chained to the
  existing §"Duplicate accounts after an upgrade" rather than duplicating it.
- Migration + service tests, including a **direct SQL** insert test (the ticket's AC 1).

**Out of scope — named so nothing here reads as covering them**

- **A `CHECK (email = lower(email))`.** §13 Q4 prices it and recommends against it *for now*; it is
  the only thing that would stop a future foreign writer from squatting a free folded key, and that
  residual is stated plainly in §14 rather than hidden.
- **Merging, folding or deleting any existing account.** V23 refuses instead. §6.2 says why the
  V22 precedent does not transfer.
- **Changing what an address may contain.** `RegisterRequest` and `CreateUserRequest` carry
  `@Email @Size(max = 255)` and no ASCII restriction — unlike `InviteMemberRequest`, deliberately
  (HD-120: *"it bounds the INVITED address only"*). Adding one is a different ticket with a
  different argument, and this spec is built to be correct without it.
- **`workspace_invites`.** V22 settled it three days ago; §5.4 states the relationship and changes
  nothing there.
- **`equalsIgnoreCase` in `HqlValueResolver` / `HqlCompiler`.** They resolve `assignee = "…"` against
  an already-tenant-scoped in-memory member list. That is a search convenience over a bounded set,
  not an identity decision, and no schema change reaches it. Named because it is a fourth spelling
  of "the same address" and a reader will find it.
- Any change to authentication, the reset flow, or the invite lifecycle.

**Non-goals.** No new endpoint. No new permission. No new `errorType`. No configuration.

## 3. Actors & permissions

Nothing about authorization changes; the guarantee is instance-wide because the object is. Stated so
`tenancy-reviewer` can confirm rather than hunt:

| Surface | Actor | What changes |
|---|---|---|
| `POST /api/auth/register` | anonymous (only when `PUBLIC_SIGNUP_ENABLED=true`) | a lost race answers **409** instead of 500 |
| `POST /api/admin/users` | system `ADMIN` (`/api/admin/**`) | same |
| startup seeding | none — `DataSeeder` | a foreign-written squatter can no longer be granted ADMIN in silence — the boot refuses instead |
| the migration | the operator running the upgrade | may be refused, with a performable remedy |

**`users` is deliberately not workspace-scoped and must not become so.** An account exists before
any membership and is shared across workspaces; this is one of the few tables where an instance-wide
question is the correct one, the same exception `UserRepository`'s published-password probe already
documents. The new index is instance-wide for that reason, and it discloses nothing to anyone: it is
a constraint, not a read.

**Enumeration.** `register` already answers 409 for a known address — a pre-existing oracle that the
pre-check creates and this ticket does not widen. The folded pre-check learns nothing new: on any
database V23 accepts, every stored value is its own fold, so `lower(email) = lower(:email)` and
`email = :email` return the same answer for every input the DTO admits. The change is
**precautionary**, for a row a future writer leaves — the same standing V22 gave its own step 1.

## 4. The decision: a functional unique index, not `citext`

```sql
CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));
```

`citext` is rejected. Six reasons, ordered by weight, and the first is the one that decides it on its
own.

**1. `citext` changes the *type*, so it changes every comparison the column takes part in — including
the ones this project deliberately made exact.** A unique index changes only what the database
*refuses*; it alters no read. `AuthService.login` resolves an account by
`findByEmail(typed.toLowerCase(ROOT))`, and HD-120's rule is that a redemption compares exactly
because *an extra match lets the wrong person in*. Under `citext` that lookup silently becomes
case-insensitive. On a database with a squatter (`Ivan@x.com` and `ivan@x.com` as two different
people, which is exactly what the pre-`citext` schema permits) a folding login lookup resolves the
typed address to whichever row the planner returns. **The guarantee and the hazard arrive in the same
commit**, and the hazard lands on the authentication path.

**2. `citext` does not buy collation stability — it buys *more* exposure to it.** Its case-folding
behaviour depends on the database's `LC_CTYPE` and *"the initial folding to lower case is always done
according to the database's LC_CTYPE setting"* (PostgreSQL docs). So both mechanisms reduce to
`lower()`. They are not equally exposed, and the asymmetry runs the way the ticket would not guess:

- The **functional index** materialises the fold once, at write time, over a value the application
  has **already folded**. `lower()` on an already-folded value is the identity function under every
  provider in practical use (Correction 2 — see its amendment: that claim is conditional, and the
  unconditional one is that both sides of every comparison go through the SAME `lower()`, so a
  provider disagreement can only manufacture a visible collision, never a false "free").
- **`citext` preserves the stored case** and re-evaluates `lower()` on the **raw** value at every
  comparison. A row stored as `Ivan@x.com` — which `citext` happily accepts, since the type
  constrains nothing — genuinely changes meaning when `LC_CTYPE` changes.

**The functional index's exposure is bounded by the application's fold. `citext`'s is not.**

**3. The schema requires no extension today, and that is a deliberate property, not an accident.**
Zero `CREATE EXTENSION` across all 22 migrations, and `V1__init_schema.sql:11` records the reasoning
for the one place it came up (*"SQL-seeded catalog rows below use `gen_random_uuid()` (built into
PG 13+)"*). `citext` is a **trusted** extension from PostgreSQL 13, so a non-superuser with `CREATE`
on the database can install it — but "trusted" is a floor, not a guarantee: a managed provider's
allow-list, a DBA-owned schema, or a `search_path` that does not include the extension's schema each
break it, and a self-hoster hitting any of those cannot run the migration at all. A functional index
needs no privilege beyond ownership of the table they already own.

**4. It would make "the same address" a property of the JDBC connection string.** PgJDBC binds
`String` parameters as `varchar` by default (`stringtype=varchar`). The reported consequence of
comparing a `citext` column against a `varchar` parameter is that the comparison resolves
**case-sensitively**, and the documented workaround is `stringtype=unspecified` on the connection —
which changes how *every* string parameter in the application binds, on every query, for one column.
So `citext` would deliver either no guarantee at all or a guarantee plus a case-insensitive `login`,
selected by a connection property rather than by the schema. *(Reported on the pgsql-jdbc list and
consistent with the driver's documented default; not re-verified in this session — and the fact that
adopting `citext` would oblige somebody to verify it is itself part of its cost.)*

**5. Cost of applying it.** `ALTER TABLE users ALTER COLUMN email TYPE citext` is a table rewrite
under `ACCESS EXCLUSIVE` plus an index rebuild; `CREATE UNIQUE INDEX` takes `SHARE` for the duration
of one build. Both are milliseconds on the five production rows and neither is on a large
self-hoster. The rewrite also has to satisfy `ddl-auto=validate` afterwards, which means proving what
Hibernate 7 does with a non-standard column type before the migration is written, and probably
`columnDefinition`/`@JdbcTypeCode` on the entity — a mapping annotation carrying a schema decision.

**6. It gives `users.email` the same answer V22 gave `workspace_invites.email`.** Two columns holding
the same kind of value now get one mechanism, one operator procedure (§11.2) and one thing to know.
Two different answers on two columns holding the same kind of value would need a reason, and there
is none.

### 4.1 The third option, named and rejected

A **nondeterministic ICU collation** on the column (`CREATE COLLATION … (provider = icu,
deterministic = false, locale = 'und-u-ks-level2')`, PostgreSQL 12+) makes `=` itself
case-insensitive. Rejected for reason 1 above in a stronger form — it changes equality for the column
*everywhere*, including `login` — plus two of its own: it forbids pattern-matching operators on the
column (`LIKE` and `text_pattern_ops` are unavailable under a nondeterministic collation), and it
binds account identity to a specific ICU version, which is the exposure reason 2 exists to avoid.
Nothing today runs `LIKE` on `users.email` (verified — every email match in `search` happens in Java
over an in-memory member list), so that cost is latent rather than paid, and a latent cost on the
identity column is not a good trade for a guarantee the index already gives.

### 4.2 `users_email_key` stays

The functional index implies the byte-exact one for uniqueness — two byte-equal values are also
fold-equal — so `users_email_key` is redundant *as a constraint*. It is not redundant *as an index*:
it is the access path for `WHERE email = ?`, which is the comparison §5.1 keeps exact, and an index
on `lower(email)` cannot serve it. **Two indexes, two jobs.** Dropping the unique constraint to keep
a plain index would trade a free second guarantee for nothing.

## 5. Behaviour & rules

### 5.1 The rule that decides every call site: fold as far as the harm points

This is HD-120's rule and HD-190's rule, applied to a third column. It is stated as the generating
property, because the list of call sites goes stale one writer before the rule does:

> **A check that can only REFUSE folds. A lookup that RESOLVES an identity compares exactly.**
> An extra match on a refusal declines someone who was entitled — recoverable, visible, and the
> caller is told. An extra match on a resolution admits the wrong person.

| Call site | Question it asks | Comparison | Change |
|---|---|---|---|
| `AuthService.register` existence check | may this address be registered? | **folds** (`lower()` in SQL) | new |
| `AdminUserService.create` existence check | same | **folds** | new |
| `DataSeeder.run` admin find-or-create | does the seed admin already exist? | **folds** — it decides whether to *write* | new |
| `AuthService.login` lookup | who is this? | **exact** | unchanged |
| `AuthService.forgotPassword` / `resendVerification` | whose mailbox? | **exact** | unchanged |
| `WorkspaceService.inviteMember` already-a-member | is this person here? | **exact** | unchanged |
| `WorkspaceService.acceptInvite` / `declineInvite` | is this offer yours? | **exact** (`equals`, HD-120) | unchanged |
| `listPendingInvites` | which offers name my address? | already folds, in SQL, both sides | unchanged |

The three unchanged-exact rows are not an oversight and a reviewer should not "finish the job". An
exact lookup on a stale or corrupted key **fails closed** — the person cannot log in, and says so. A
folded lookup on the same data can **fail open**.

`listPendingInvites` is worth one sentence because it looks like a counter-example: it folds on both
sides, against a value (`actor.getEmail()`) that is already folded, and it is a *list* rather than a
resolution — the authorization still happens in `acceptInvite`, with `equals`. It is the shape to
copy for a read that offers choices, not for a read that grants one.

### 5.2 The write-side check must use the same expression as the index

```java
// UserRepository
@Query("SELECT count(u) > 0 FROM User u WHERE lower(u.email) = lower(:email)")
boolean existsByFoldedEmail(@Param("email") String email);

@Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
Optional<User> findByFoldedEmail(@Param("email") String email);   // seeding only — see §5.1
```

Verbatim HD-133 §4.2, for a reason that is *stronger* here: `Locale.ROOT`'s fold and PostgreSQL's
`lower()` are different functions, and `workspace_invites` at least bounds its local part to ASCII —
**`users` bounds nothing**. `RegisterRequest` carries `@Email @NotBlank @Size(max = 255)` and no
pattern. Where the two folds disagree, a Java-side check says "free" while the index says "taken",
so an ordinary signup runs a **doomed INSERT** — which is exactly why this check folds in SQL rather
than in Java. (Two qualifications, both of which cost this ticket a correction. It is a
counterfactual about the check, not a description of the shipped code: see the amendment to
Correction 3. And because the `23505` translation ships in the *same commit*, the doomed INSERT
would be answered **409 at the cost of a wasted round-trip**, not 500 — the 500 is what returns the
day that translation stops matching, which is precisely why the refusal must not depend on it.)

**Do not use Spring Data's derived `existsByEmailIgnoreCase`.** It generates `upper(…)`, not
`lower(…)`, and `upper` is not the inverse of `lower` on every input — so a derived finder would ask
a *different* question than the index answers, which is the exact defect this method exists to
prevent, in a new spelling. (`WorkspaceInviteRepository.findByEmailIgnoreCaseAndAcceptedAtIsNull…`
is *named* `IgnoreCase` but is backed by an explicit `@Query` with `lower()`. The name lies; do not
copy it as a pattern.)

### 5.3 The constraint translation, in HD-133's shape

Both writers wrap their `save` and translate a `23505` on either email constraint into the existing
`EmailAlreadyUsedException` (409, *"Email is already registered"*). Reuse the shape, not just the
idea — `WorkspaceService.isDuplicateInvite` / `namesPendingEmailIndex` is the reference, and its two
hard-won properties both apply unchanged:

- **Match the constraint name AND require SQLSTATE `23505`.** Neither alone is sufficient: `users`
  has one other unique constraint and several foreign keys, and a lock error that merely quotes the
  failing statement would mention the index name too.
- **Do not depend on Hibernate's dialect having found the name.** `PostgreSQLDialect`'s extractor
  matches the literal English fragment `violates unique constraint "`, so on a server whose
  `lc_messages` is anything else it returns null for a well-formed `23505` — and the fallback must
  match the index's own name against the cause-chain messages, because PostgreSQL quotes an
  identifier verbatim in every locale. Do **not** test `instanceof DuplicateKeyException`: under JPA
  that branch is unreachable.

**Both names are translated** — `users_email_key` as well as `users_email_lower_uk` — because they
are two spellings of one answer and the caller must not be able to tell which fired. That is also
what retires the pre-existing race-500.

The 409 carries **no new `errorType`**: the pre-check's 409 has none, the two must be
indistinguishable, and nothing in the SPA branches on it.

The seeder's admin write gets no `23505` translation, and the reason is a **mechanism** rather than
the population: `DataSeeder.run` is not `@Transactional`, so `SimpleJpaRepository.save` opens and
commits its own transaction and a unique violation surfaces from the `save()` call itself, out of
the `ApplicationRunner` — the boot fails loudly, which at boot is the outcome a 409 would have to be
argued against. (Adding `@Transactional` to `run` later moves where that exception lands.) Its
existence check still folds (§5.1), because a find-or-create is a write-side question — **but it
folds only to find.** The returned row's address is compared *exactly* before `SystemRole.ADMIN` is
granted, and a mismatch refuses the boot: this is the one call site that both resolves and grants,
and a bare folded find there converts a loud refusal into a silent ADMIN grant to whoever occupies
the folded key.

### 5.4 What this does and does not do to `workspace_invites`

**Nothing.** Stated explicitly because the ticket asks.

`workspace_invites.email` stays `VARCHAR`, `V22`'s partial index stays as built, and
`findPendingByWorkspaceAndFoldedEmail` keeps folding in SQL. `acceptInvite` keeps comparing
`invite.getEmail().equals(actor.getEmail())` — both sides Java `String`s read out of `VARCHAR`
columns, untouched by anything here. The two constraints answer two different questions on two
tables and the same mechanism now backs both:

| | `workspace_invites` (V22) | `users` (V23) |
|---|---|---|
| key | `(workspace_id, lower(email))` | `(lower(email))` |
| partial | yes, `WHERE accepted_at IS NULL` | **no** — every account obeys |
| scope | one workspace | the instance |
| what a duplicate means | two standing offers | two accounts for one person |
| on an existing violation | **delete** the loser | **refuse to migrate** (§6.2) |

*(Had `citext` been chosen, this row would not have been empty: `users.email` as `citext` leaves the
invite SQL alone — the parameter still binds as `varchar` and the invite column is still `varchar` —
and silently rewrites the **account** lookups that `inviteMember`'s already-a-member check and
`login` share. The invite side would be fine and the auth side would not, which is reason 1 again,
from the direction the ticket asked about.)*

## 6. The migration: what it detects, what it refuses, what it never touches

```
V23__users_email_uniqueness.sql
  1. DO $$ … RAISE EXCEPTION  — population A > 0 → abort, nothing applied
  2. DO $$ … RAISE NOTICE     — population B > 0 → report a count, never a row
  3. CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));
```

Both precedents are already in this schema and the migration should mirror them rather than invent a
style: `V15__drop_legacy_role_columns.sql:23-42` is the blocking `DO $$ … RAISE EXCEPTION` pre-flight
(*"fails BEFORE any column is dropped, so a half-migrated database is not possible"*), and
`V20__notifications_workspace_scope.sql:137-152` is the `RAISE NOTICE` that reports a count and
points at `docs/self-hosting.md`.

### 6.1 The detection result, recorded (the ticket's AC 2, discharged)

Run against **production** over SSM on **2026-08-28**, PostgreSQL **16.15**, `server_encoding=UTF8`:

```
users_total          = 5   users_not_pg_lower  = 0   users_dotless_i (U+0131) = 0
users_dotted_I (U+0130) = 0   users_non_ascii  = 0   users_fold_collisions    = 0
invites_non_ascii    = 0   invites_not_pg_lower = 0
Indexes on users: users_pkey, users_email_key — no lower(email) index.
citext: available in the image, not installed.
```

**Population A = 0, population B = 0, collisions = 0.** Production is clean and **no repair is being
applied to live rows**. Everything in §6.2 exists for a database we cannot see — a self-hoster whose
`users` table some other writer touched, or who ran a pre-0.16.0 build under `LANG=tr_TR.UTF-8`. If
step 1 ever aborts, that is the finding, not the fix.

The first version of the query asked only `WHERE email <> lower(email)` and would have reported the
same clean result on a database broken in the way HD-120 actually hit. That is Correction 1, and it
is why the numbers above count the two characters separately.

### 6.2 The repair rule, and why V22's answer does not transfer

> **A migration may repair what its own application can recreate, and must refuse what it cannot.**

V22 deleted mixed-case invitations because a deleted offer is recoverable — by the same
administrator, from the same screen, in two clicks. **Nothing recreates an account.** It owns issues,
comments, memberships, sessions and history. So V23 **repairs nothing and deletes nothing**, and the
ticket's AC 3 ("a collision found during repair fails loudly rather than dropping a row") is
satisfied by there being no repair at all.

The gentler-looking option is worse for the same reason V22 gave, one step up in severity.
`UPDATE users SET email = lower(email)` keeps the account and looks strictly kinder. It is not:
`Bob@x.com` and `bob@x.com` are two different mailboxes on any RFC-compliant server, so folding in
place changes **which mailbox can reset that account's password**. V22 refused to let a migration
silently change who an *offer* reaches; this is the same act against an *account*, and the argument
gets stronger rather than weaker as the object gets more valuable.

**Step 1 refuses on every population-A row, not only on colliding ones**, and that is deliberately
stricter than "would the index build succeed". A lone non-colliding mixed-case row is not harmless:

- its owner already cannot log in (every lookup folds the typed address first) and already cannot
  receive a reset mail (`forgotPassword` folds too);
- from V23 onward it **squats the folded key**, so the correct address becomes unregisterable — a
  409 for an address nobody holds, which no operator would connect to this row;
- the upgrade is the only moment anyone will look.

Refusing a *whole upgrade* over one row is the cost, and it is accepted: the population is measured
at zero on every database we can see, the message names the remedy, and `docs/self-hosting.md` will
carry the check so an operator can run it **before** upgrading and never meet the block (§11.1).

### 6.3 "Loudly", concretely — and why the obvious version is not loud enough here

A Flyway migration runs in one transaction, so `RAISE EXCEPTION` aborts the file whole: nothing is
applied, the index does not exist, **every `users` row is still there**, Flyway records the failure
and the application does not start. Loud, atomic, re-runnable after the operator acts.

The tempting alternative is to skip step 1 and let `CREATE UNIQUE INDEX` fail by itself. It is
atomic and it does abort — but **HD-133 already took away the part that made it actionable.** Since
V22 the datasource runs `logServerErrorDetail=false`, and V22's own review recorded the consequence
in writing: *"a failed migration names its index and not the colliding rows."* An operator would get
`could not create unique index "users_email_lower_uk"` and no way to find out which accounts. A
refusal may only prescribe an action its reader can perform, so the explicit pre-flight is
**required, not decorative**.

What the message must carry, and what it must not:

- **the count**, never the addresses. Third-party email addresses do not go into a shipped log
  (`RecipientMailThrottle`'s domain-only rule). `DataSeeder.rejectPublishedPassword` is the one place
  that trades the other way and its subject is the operator's own admin account — not a precedent for
  arbitrary users.
- **the queries**, because whoever reads this message is at a database prompt by definition. Both
  are `SELECT`s: the list of population-A rows, and the collision check
  (`GROUP BY lower(email) HAVING count(*) > 1`).
- **the two remedies in the only order that works.** Resolve collisions **first** — only a human can
  decide which of two accounts survives, and the answer is re-address or disable, never delete —
  **then** fold. The order is load-bearing: at that moment the index does not exist, so a blind
  `UPDATE … SET email = lower(email)` over a colliding pair **succeeds** and produces two identical
  addresses, which `users_email_key` then refuses in a way that reads like a different bug. Say so.
- **that folding redirects that account's mail**, so it is the operator's decision and not the
  migration's.
- **that nothing has been applied**, in the first sentence. V15's message opens with *"Nothing has
  been dropped"* for the same reason.

Step 2's `RAISE NOTICE` carries a count and one pointer: `docs/self-hosting.md` §"Duplicate accounts
after an upgrade", which already ships the proxy query, the pair query, the lone-`ı`-row remedy and
the lone-`İ`-row remedy. It does **not** block, because a non-ASCII address is legitimate, the index
neither fixes nor is blocked by one, and refusing an upgrade over a legal address would be a refusal
whose only performable remedy is "stop using your own alphabet". *(The builder should confirm
PostgreSQL notices surface in the startup log through Flyway; if they do not, the check still belongs
in the migration and the doc subsection carries the whole weight.)*

## 7. Edge cases & failure modes

**Two rows folding together (`Ivan@x.com` + `ivan@x.com`).** Step 1 aborts before step 3 is reached;
both rows survive; the operator is told there is a collision and that only they can pick the
survivor. This is AC 3 and it must have a test.

**One mixed-case row, no collision.** Also aborts (§6.2). Deliberately stricter than the index needs.

**Population B (`ıt-admin@corp.com`).** Notice only. The index builds; the row is unique and
untouched; the person still cannot log in, which is a pre-existing HD-120 condition with a documented
remedy that V23 does not worsen and does not pretend to fix.

**Concurrent registration of one address.** Both pass the pre-check, one insert wins, the loser's
`23505` becomes a **409** (§5.3) instead of today's 500. No lock is added: the window is a single
INSERT, there is no `mail_send_events`-style transactional side effect to unwrite on this path, and
an advisory lock on every signup would be a real cost against an improved status code in a race.
`AuthService.register` sends its verification mail after the row exists, so a rolled-back loser mails
nothing.

**Race between a signup and the migration.** Impossible on the documented deployment: `deploy.yml`
runs `docker compose up -d`, which stops the old container before starting the new one, so no
instance serves traffic while Flyway runs. The condition to watch is a **rolling deploy or a second
replica** (`docs/design/p2-scaleout-proposal.md`), not a row count — and there the failure is the
benign one: the index build fails outright, Flyway rolls back whole, startup fails loudly and
re-runnably. Same answer as V22's header: start Flyway with nothing else writing the table.

**Optimistic locking / concurrency on the row.** Untouched. `User` extends `BaseEntity` and this
ticket adds no update path to `email`; there is no admin "change email" endpoint (`UpdateUserRequest`
carries `status` and `systemRole` only), so no in-flight rename can race the constraint. **If one is
ever added, it inherits every rule in §5 and needs the §5.3 translation on its own save.**

**Soft delete / archived.** `users` has no soft delete; `DISABLED` is a status and a disabled account
keeps its row and its slot. That is correct — the address is still spoken for, re-enabling is the
remedy, and it is the same "a dead row keeps its slot until somebody clears it" shape V22 named.

**Deleting a user.** No endpoint exists. When one does, deleting frees the folded key, which is
stock and therefore permitted to be freed by a revocation (HD-158's rule).

**Locking during the build.** `CREATE UNIQUE INDEX` (not `CONCURRENTLY` — it cannot run inside a
transaction and Flyway runs each migration in one) takes `SHARE` on `users` for the duration.
Five rows in production; a self-hoster's `users` table is bounded by their headcount. The size
argument is not what keeps this safe, though — the deploy model is (see the paragraph above).

**Idempotency.** Re-running after a failed step 1 is safe: the file is atomic, so the second run
starts from the same state as the first.

## 8. Data model impact

**One migration. No new column, no changed column, no changed entity mapping.**

- `users`: one new index `users_email_lower_uk` (`UNIQUE`, non-partial, expression `lower(email)`).
- `users_email_key`: kept (§4.2).
- No PG `ENUM`, no `CHAR(n)`, no new column — so no UUID-v7 and no `@CreatedDate` obligation arises.
- **Do not mirror this on the entity.** JPA cannot express a functional unique constraint;
  `@Table(uniqueConstraints = …)` would describe a rule the schema does not have, and
  `@Column(unique = true)` on `User.email` already covers `users_email_key`. The entity change is
  **javadoc only**: that the constraint exists, that it is on `lower(email)`, that the fold in the
  three writers is the message rather than the enforcement, and — phrased as the requirement rather
  than as today's list of callers — that **any new writer of this table must fold with
  `Locale.ROOT` before the insert and must ask its existence question with `lower()` in SQL**.
- `ddl-auto=validate` is unaffected: Hibernate validates columns and types, not expression indexes.

Migration outline (semantics; the header text is §6.3's contract):

```sql
-- 1. Pre-flight: refuse if any stored address is not its own fold. Nothing is repaired here.
DO $$
DECLARE unfolded bigint; collisions bigint;
BEGIN
    SELECT count(*) INTO unfolded FROM users WHERE email <> lower(email);
    SELECT count(*) INTO collisions FROM (
        SELECT lower(email) FROM users GROUP BY 1 HAVING count(*) > 1
    ) c;
    IF unfolded > 0 THEN
        RAISE EXCEPTION 'HD-167 V23 aborted: … % row(s) …, % colliding group(s). Nothing has been '
                        'applied and no account has been changed. …', unfolded, collisions;
    END IF;
END $$;

-- 2. Advisory: a non-ASCII address may be legitimate or may be a pre-0.16.0 locale-folded row.
--    Reported, never rewritten — no query can tell those apart.
DO $$
DECLARE non_ascii bigint;
BEGIN
    SELECT count(*) INTO non_ascii FROM users WHERE email ~ '[^\x00-\x7F]';
    IF non_ascii > 0 THEN
        RAISE NOTICE 'HD-167: % users row(s) hold a non-ASCII address. …', non_ascii;
    END IF;
END $$;

-- 3. The guarantee.
CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));
```

*(Step 1 computes `collisions` even though `unfolded > 0` is what aborts — the operator needs both
numbers in one message to know whether their remedy is one `UPDATE` or a decision about two people.
**And `unfolded > 0` is not merely a sufficient gate, it is a complete one, by algebra rather than
by semantics:** if `a = lower(a)` and `b = lower(b)` and `lower(a) = lower(b)`, then `a = b` — which
`users_email_key` already forbids. So `unfolded = 0` **implies step 3 cannot fail**, on any database
the pre-flight passes, assuming nothing about what `lower()` means on any provider. That is what
*fully* retires the un-actionable index-build failure `logServerErrorDetail=false` created, and it
is the reason `collisions` can be reported without gating: it cannot add a refusal the unfolded
count does not already make.)*

## 9. API surface

**No new endpoint. No request or response shape changes. No new `errorType`.**

**Two mechanisms change what these endpoints answer, and only one of them is a `500` → `409`.** The
table is the *translation's* half:

| Endpoint | Before | After |
|---|---|---|
| `POST /api/auth/register` | 500 on a lost race | **409** `"Email is already registered"` |
| `POST /api/admin/users` | 500 on a lost race | **409**, identical |

A lost race is the *whole* of that half — two concurrent registrations of one address, where
`users_email_key` used to surface an untranslated `23505`.

The other half belongs to the **folded pre-check**, and its before-state was never a `500`: pre-V23,
a signup at the folded form of a stored mixed-case address passed the exact pre-check and was then
*accepted* by the byte-exact `users_email_key` — a **201 and a duplicate account**, not an error.
After this ticket that address is refused **409** by the pre-check, with no INSERT attempted. An
earlier draft of this section put that outcome in the table, which attributes to the translation a
change that is the pre-check's: the index is what makes the duplicate impossible, and the folded
check is what makes the refusal a 409 instead of a translated race. §12's AC 8 carries the same
amendment; this paragraph is what keeps the two in step.

`openapi.yaml` and both `docs/api-*.md` already document the 409 on these endpoints (the pre-check
raises it); **verify rather than assume**, and add it if either is missing — the wire contract is
unchanged, only its reachability. `api-docs-sync` runs to confirm, not necessarily to edit.

No path joins or leaves the throttled set, so `ThrottleCoverageTest` is untouched — nothing new is
mounted and no handler's cost changes. `register` remains as throttled as it was.

## 10. Frontend impact

**None.** Stated explicitly so `frontend-builder` is not dispatched. The register form and the admin
"New user" form already render the server's 409 detail, and the sentence is unchanged. No new
component, no new store, no `DESIGN.md` decision.

Do **not** add a client-side case-insensitivity hint or pre-check. The client cannot know what is in
`users`, the server owns the rule, and the 409 already says the only true thing.

## 11. DC / Cloud implications

**No profile gating, no new property, no new environment variable, no compose change, no
`.env.prod.example` line, no README line.** The wiring checklist for this ticket is **empty** —
stated so `dc-cloud-guard` can confirm rather than hunt. The constraint is schema, applied by Flyway
identically in both modes; the translation is one code path in both.

**And it must never become a toggle.** A schema-level identity guarantee that one deployment mode has
and the other does not is a fork by definition, and it would make "can this address be registered" a
question about which mode you are running — the exact class of difference ADR-0006 forbids.

The only asymmetry is *who is exposed to the refusal*: Cloud is one database, measured clean, so
step 1 cannot fire there. DC is every database we cannot see, so DC is where a blocked upgrade can
happen. That is a difference in **data**, not in behaviour, and the answer is documentation rather
than configuration.

### 11.1 `docs/self-hosting.md` — one new subsection, chained to the existing one

A short **pre-upgrade check for 0.18.0** placed next to §"Duplicate accounts after an upgrade":
the population-A query and the collision query, the statement that a hit blocks the upgrade, the two
remedies in the order §6.3 requires, and a one-line explanation that folding redirects that account's
mail so the choice is theirs. It must **link** to the existing section for population B rather than
restate it — that section is complete, correct, and better than anything this ticket would rewrite.
It should also carry the release-notes line: run this **before** upgrading and you will never meet the
block.

### 11.2 The collation-provider answer, for both indexes at once

What an operator sees when the C library or ICU changes under a running cluster:

```
WARNING: index "users_email_lower_uk" depends on collation "default" version "2.28",
         but the current version is "2.36"
DETAIL:  The index may be corrupted due to changes in sort order.
HINT:    REINDEX to avoid the risk of corruption.
```

plus, on PostgreSQL 15+, a database-level collation-version-mismatch warning at connect. The remedy
is `REINDEX INDEX users_email_lower_uk;` followed by `ALTER DATABASE hamstrack REFRESH COLLATION
VERSION;` — and it is the **same procedure `workspace_invites_pending_email_uk` already needs**, so
`docs/self-hosting.md` gets one procedure covering both, not two. That is the operational half of
"one mechanism for both columns".

What actually breaks, stated precisely because the vague version causes panic:

- **Equality does not change.** Under a deterministic collation — every collation this schema uses —
  equality is byte equality, so a provider change can never make two stored keys newly equal or newly
  distinct. No existing row's uniqueness silently lapses.
- **Sort order can change**, and a btree finds its duplicate candidates by order, so a stale index can
  fail to notice a *new* duplicate until it is rebuilt. That is what the `REINDEX` is for.
- **`lower()` itself can change** — it reads `LC_CTYPE` — and this is the part that would matter, and
  does not: `lower()` is the identity function on every value the application stores (Correction 2),
  because the characters whose folding varies across providers are all uppercase and stored values
  have none.
- **`REINDEX` is the detector, and it fails loudly**: if a provider change did fold two stored
  addresses together, the rebuild fails with `could not create unique index … Key (lower(email))=(…)
  already exists`. An operator running that in `psql` sees the `DETAIL` naming both rows —
  `logServerErrorDetail=false` is a JDBC connection property on the application's pool and does not
  reach their session.
- The deployed image pins `postgres:16-alpine`, so the C library changes only when that tag is
  deliberately moved. This is an **upgrade-time event with a known moment**, not drift — which is why
  the procedure belongs in the upgrade documentation and not in a monitor.

*(PostgreSQL 17's provider-independent `pg_c_utf8` would remove this class of hazard entirely. The
documented floor is PostgreSQL 16, so it is a note for a future ticket, not a recommendation here.)*

## 12. Acceptance criteria

**Migration** (shape the tests on `V15RoleBackfillMigrationTest`):

1. On a database seeded before V23 with `Ivan@x.com` and `ivan@x.com`, V23 **aborts**; **both rows
   are still present**; `users_email_lower_uk` does not exist; and Flyway records **nothing** — no
   schema-history row at all, since PostgreSQL writes that row inside the same transaction, so a
   re-run after the remedy needs no `repair`. *(AC 3.)*
2. On a database seeded with a single non-colliding `Ivan@x.com`, V23 also aborts — the check is
   population A, not "would the build succeed" (§6.2).
3. On a database seeded with `ıt-admin@corp.com` (U+0131) and no mixed-case row, V23 **succeeds**,
   the notice fires, and the row is unchanged.
4. On a clean database V23 succeeds and neither message is emitted.
5. After V23, a **direct SQL** `INSERT` of a second row differing only in case is refused by the
   database. Direct SQL, not through the service — this is the ticket's AC 1 and going through the
   service would prove only that the pre-check works.
6. `users_email_key` still exists after V23.

**Backend:**

7. `POST /api/auth/register` with `IVAN@x.com` after `ivan@x.com` → **409** (already true via the
   fold; pinned so a future change to the fold cannot silently regress it).
8. With a squatter row inserted by direct SQL and the constraint in place, a registration for the
   folded address answers **409**, never 500 — **through the pre-check**, with no INSERT attempted.
   *(Amended 2026-08-28: this was filed as "Correction 3 — the reachable case" and is not that; the
   folded pre-check gets there first. It stays as an AC because it pins the outcome, and a test that
   exercises the constraint translation needs a simulated 23505 instead — see 9.)*
9. A simulated `23505` on `users_email_lower_uk` reaching either writer surfaces as **409**; a
   `23505` on any other constraint and a `23503` keep today's outcome.
10. The fallback path — a `23505` whose dialect-extracted constraint name is null (a non-English
    `lc_messages`) but whose message names the index — is also **409**. A lock error that merely
    quotes the index is **not**.
11. `login` still resolves by exact match: with a squatter present, the mixed-case account does
    **not** answer to the folded address. *(§5.1's fail-closed property, pinned.)*
12. `DataSeeder.run`'s admin seed with a squatter occupying `SEED_ADMIN_EMAIL`'s folded key
    **refuses the boot** — `IllegalStateException` out of the `ApplicationRunner`, naming the row's
    id and no address — rather than granting it `SystemRole.ADMIN`. **Amended 2026-08-28:** this AC used to
    read "finds it and promotes it rather than minting a second administrator", which was written
    against a pre-V23 world where the alternative was a duplicate. Post-V23 the index makes a
    duplicate impossible, so what a bare folded find would displace is not a duplicate but the
    **loud boot failure** — converted into a silent instance-wide ADMIN grant to a stranger, logged
    with a line that reads like success. The seeder folds to *find* and compares exactly to *grant*.
13. `DataSeeder.run`'s admin seed with an **exactly** matching row still promotes it — the refusal
    is narrow, and a mixed-case `SEED_ADMIN_EMAIL` for a correctly-stored account is not affected
    (the configured value is folded before the comparison).
14. No repository method on `UserRepository` uses derived `IgnoreCase` — a sealed test whose failure
    message says why (`upper()` is not `lower()`, §5.2).

**Docs:**

15. `docs/self-hosting.md` carries the pre-upgrade check, links to §"Duplicate accounts after an
    upgrade" for population B, and carries the one `REINDEX` procedure covering both email indexes.
16. `openapi.yaml` validates and the 409 is documented on both affected endpoints in
    `docs/api-cloud.md` and `docs/api-dc.md`.

**Already discharged before any code:**

17. *(Ticket AC 2)* The detection query was run against production on 2026-08-28 and its result is
    recorded verbatim in §6.1, in a form that distinguishes the case-broken from the locale-broken
    population.

## 13. Open questions — each with the recommended default

**Q1. `citext` or a functional unique index on `lower(email)`?**
*Recommendation: the functional index.* §4. Deciding reason: `citext` changes the type, so it changes
every comparison the column takes part in — including `login`, which HD-120 made exact on purpose —
while an index changes only what the database refuses. Everything else (no extension privilege, no
table rewrite, no JDBC `stringtype` dependency, less collation exposure, one answer with V22) points
the same way. Overrule this only together with a decision about what `login` compares.

**Q2. Repair the offending rows, or refuse the migration?**
*Recommendation: refuse.* §6.2. A migration may repair what its application can recreate and must
refuse what it cannot; folding an account's address in place silently changes which mailbox can reset
it. Overrule only together with a decision about who is accountable for that redirection.

**Q3. Refuse on every population-A row, or only on colliding ones?**
*Recommendation: every one.* §6.2. A lone mixed-case row is already a broken account and, from V23
onward, silently makes the correct address unregisterable. The upgrade is the only moment anyone
looks. The cost — an upgrade blocked by one harmless-looking row — is bought off by the pre-upgrade
check in §11.1.

**Q4. Add `CHECK (email = lower(email))` as well?**
*Recommendation: no, not in this ticket — and here is exactly what that leaves on the table.* The
CHECK is the only thing that would stop a **future** foreign writer from squatting a *free* folded
key, which the unique index cannot (§14). Against that: it pins the application's every write to
PostgreSQL's `lower()` rather than to `Locale.ROOT`'s fold, and those are different functions whose
divergence depends on the deployment's `LC_CTYPE` and collation provider. A `23514` has no other row
to name, so no sentence could be written for it — it would be a bare 500, and a deployment-dependent
one. Revisit if a single `MailAddresses.fold(...)` is ever introduced and proven equal to `lower()`
across the provider matrix; file it as a follow-up rather than shipping it blind.

**Q5. Keep `users_email_key`, or drop it as redundant?**
*Recommendation: keep.* §4.2 — it is the access path for the exact `findByEmail` that §5.1 keeps
exact, and an index on `lower(email)` cannot serve `WHERE email = ?`.

**Q6. Should the write-side pre-checks fold, given that the fold already ran in Java?**
*Recommendation: yes, and it is precautionary rather than corrective.* §5.2. On any database V23
accepts, the folded and exact checks return the same answer for every admissible input; the folded
one exists so that a row some other writer leaves produces a clean 409 instead of the 500 of
Correction 3. Same standing as V22's step 1.

**Q7. Should the migration's refusal name the offending addresses?**
*Recommendation: no — the count, plus the `SELECT`.* §6.3. The reader is at a database prompt by
definition, so a query is a performable remedy; third-party addresses in a shipped log are not
something this project does, and `DataSeeder`'s one exception is about the operator's own account.

**Q8. Should the 409 from the constraint carry a distinguishing `errorType`?**
*Recommendation: no.* §5.3. The pre-check's 409 has none, nothing in the SPA branches on it, and the
two must be indistinguishable so a race is invisible to the client.

## 14. Architectural decisions (ADR)

**One, and it clears the bar on all three tests: it is hard to reverse (a type change is a rewrite,
and the choice propagates to every future identity-bearing text column), a future contributor will
certainly ask "why not `citext`?", and the answer is not obvious from the code.**

- **ADR-0016 — case-insensitive uniqueness is a CONSTRAINT (`UNIQUE (lower(col))`), never a TYPE
  (`citext`).** Chosen: a functional unique index. Rejected: `citext`; a nondeterministic ICU
  collation. Trade-off: the guarantee no longer travels with the column, so every future write path
  must still fold at the boundary and every new existence check must fold in SQL — bought in exchange
  for leaving every *read* comparison exactly as this project deliberately wrote it, and for a schema
  that still requires no extension.

Drafted as `docs/adr/0016-case-insensitive-uniqueness-by-constraint.md`, `Status: Proposed`.

Two candidates were weighed and fall below the bar: *"a migration may repair what its application can
recreate and must refuse what it cannot"* (§6.2 — a generalisation of V22's already-recorded
reasoning, and better placed as a quotable rule in the migration header than as a third document
restating two accepted ones), and *"a refusal folds, a resolution compares exactly"* (§5.1 — the
application of HD-120 and ADR-0015 to a third column, not a new fork).

## 15. The highest-risk assumption, stated plainly

**That where PostgreSQL's `lower()` and Java's `toLowerCase(Locale.ROOT)` disagree, the disagreement
REFUSES rather than ADMITS.**

The design leans on it everywhere: writes fold in Java, the constraint and the write-side checks fold
in SQL, and every identity read compares exactly. The two folds *do* disagree — on `İ` (U+0130),
Java produces `i` + a combining dot and libc produces plain `i`, which is precisely why
`docs/self-hosting.md` calls those "exactly two characters". I cannot enumerate the disagreement once
and for all, because `lower()` depends on each deployment's `LC_CTYPE` and provider. So the claim is
not "they agree" — it is the property above, and it holds by construction: the index key is derived
from a value the application already folded, so an unexpected divergence can only *manufacture a
collision* (a 409 naming an address the caller does not hold — visible, recoverable, and the caller is
told) and can never *merge two identities* on a read, because no read folds.

**The second-highest: that the three writers of `users` remain the only ones, and that the fourth is
an importer.** LDAP/SSO provisioning and admin bulk import are both named as expected work, and both
insert accounts from a foreign source. The mitigation is structural rather than remembered — the
index makes such a path fail **closed**: it cannot create the duplicate, only report it badly. What it
cannot stop is a foreign writer inserting `Ivan@x.com` into a *free* folded slot, squatting it for a
future legitimate signup. That is the residual Q4's `CHECK` would close, it is the one thing this
ticket knowingly leaves open, and it is filed here rather than implied absent.
