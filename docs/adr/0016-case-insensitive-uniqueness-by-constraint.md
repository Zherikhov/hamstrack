# ADR-0016: Case-insensitive uniqueness of an address is a CONSTRAINT (`UNIQUE (lower(col))`), not a TYPE (`citext`)

Record date: 2026-08-28
Status: Accepted
Source: `docs/design/email-uniqueness-proposal.md` §4, §5.1, §11.2 (HD-167);
`V22__invite_uniqueness.sql` (HD-133) — the same mechanism on a neighbouring column;
HD-120 — "fold once at the boundary, compare exactly thereafter";
`docs/self-hosting.md` §"Duplicate accounts after an upgrade";
the PostgreSQL documentation on `citext` and on collation versions

## Context

`users.email` *is* the account: it is typed at login, the password-reset mail goes to it, and
`WorkspaceService.acceptInvite` compares an invite against it with `equals`. In the schema it carries
`VARCHAR(255) NOT NULL UNIQUE` — **byte for byte**. Case insensitivity rests on **convention**: the
three places that write to the table (`AuthService.register`, `AdminUserService.create` and the admin
seeding inside `DataSeeder.run` — there is no separate `seedAdmin` method) each remember
`toLowerCase(Locale.ROOT)` every time. HD-120 already found how this
convention breaks silently (the fold read the JVM locale, and a Turkish container wrote a dotless
`ı`), and it fixed the **code** without making the rule a **guarantee**. Any future writer — an
LDAP/SSO import, a bulk upload by an administrator, a support script — creates a second account for
the same person without a single error.

The fork is not "is a guarantee needed" — it is. The fork is **what to express it with**, and there
are exactly three options: change the column's **type** (`citext`), hang a **constraint** on it
(`UNIQUE (lower(email))`) or change the column's **collation** to a non-deterministic ICU one.

What makes the choice expensive to reverse: a type is changed with `ALTER COLUMN … TYPE`, i.e. by
rewriting the table under `ACCESS EXCLUSIVE`, and the answer chosen once spreads to every subsequent
identifier column in the schema.

The key fact that in this project outweighs everything else: **the application has comparisons on
this column that were made exact DELIBERATELY.** `AuthService.login` finds the account by an exact
match, and the HD-120 rule says: redemption compares exactly, because an extra match lets in **the
wrong person**. `acceptInvite`/`declineInvite` compare addresses with `equals` and not with
`equalsIgnoreCase` for the same reason, and that is written out there across twenty lines.

## Decision

Case-insensitive uniqueness is expressed by a **functional unique index**:

```sql
CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));
```

The column type stays `VARCHAR(255)`. The byte-wise `users_email_key` **is kept** — it is redundant
as a constraint (byte-equal values are equal after folding too), but it is the only access path for
`WHERE email = ?`, that is, for the very exact lookup that stays exact.

The general rule that follows from this and is worth quoting instead of re-deriving:

> **A constraint changes only what the database REFUSES. A type changes EVERY comparison the column
> takes part in — including those made exact deliberately.**

Additions without which the decision is incomplete:

- **The fold in the application stays.** It answers a different question: the address is stored, is
  mailed to and serves as a lookup key, and all three must be one string. A uniqueness constraint
  does not give that.
- **The write-side checks fold in SQL** (`lower(u.email) = lower(:email)`), with the same expression
  as the index. Otherwise Java says "free", the index says "taken", and out comes a `23505` for which
  there is nothing to propose — that is, a 500 on an ordinary registration.
- **Reads that resolve identity stay exact.** The fold goes exactly as far as the harm indicates: an
  extra match in a refusal turns away someone who had the right (visible and correctable), an extra
  match in resolving identity lets a stranger in. An exact lookup fails **closed**, a folding one can
  fail **open**.
- **A `23505` violation is translated into the existing `409`** at both write points, in the HD-133
  shape (a gate on the SQLSTATE + the index name, with a fallback that does not depend on the
  server's `lc_messages`). The reachable case is a **race** between two simultaneous registrations of
  one address, not a "squatter": the pre-check and the index ask the same question of the same
  function (PostgreSQL's `lower()`), so they can diverge only on the **window between themselves**.
  Measured: a row `Bob@x.com` inserted directly by SQL is rejected by the pre-check with a 409 with no
  INSERT attempted.

## Consequences

+ A guarantee at the schema level: two addresses differing only in case cannot be rows of `users` at
  the same time. A future writer who forgets the fold fails **closed** — he cannot create a
  duplicate, he can only report it badly.
+ **No read changed its meaning.** `login`, `forgotPassword`, `acceptInvite` compare exactly as they
  did yesterday — that is, this guarantee did not bring case-insensitive authentication with it.
+ The schema still **requires not a single extension** — a property fixed as far back as the header of
  `V1__init_schema.sql` ("`gen_random_uuid()` is built into PG 13+"). A self-hoster on managed
  PostgreSQL, where `CREATE EXTENSION` is forbidden by the provider's policy, installs the upgrade
  without a single additional privilege.
+ `users.email` gets **the same mechanism** as `workspace_invites.email` in V22: one shape, one
  operational procedure (a `REINDEX` after a change of collation provider), one piece of knowledge
  instead of two.
+ A 500 that existed before the ticket is closed as a side effect: a race between two simultaneous
  registrations of one address broke on `users_email_key` and reached `GlobalExceptionHandler` as a
  500.
+ **Both sides of every comparison go through THE SAME `lower()`.** The write-side check asks
  `lower(stored) = lower(:typed)`, the index enforces uniqueness of `lower(stored)`, `:typed` is what
  gets inserted — all of it PostgreSQL's `lower()`, with the `Locale.ROOT` fold taking part on
  neither of the sides. So the application's check and the database's guarantee **cannot diverge** on
  the question "is this address free" — on any input, any `LC_CTYPE`, on any provider. A provider with
  a different `lower()` is able only to **create a collision** (a 409, visible, reported to the
  caller) and never a false "free". That is the unconditional argument for the index against
  `citext`, whose exposure is bounded by nothing: it preserves case and recomputes `lower()` on the
  RAW value at every comparison, including those the project made exact deliberately.
+ **Exposure to the collation is additionally bounded by the application's fold** — but this argument
  is already conditional, and the decision does not rest on it. `lower()` is the identity function on
  an already-folded value: the characters whose folding differs between locales and providers
  (`I` → `i`/`ı`, `İ` → `i`/`i̇`) are **upper-case**, and there are none of those in the stored value.
  A caveat: "there are no upper-case ones" is a property under the Unicode tables of the **JVM**
  (Java 21 = Unicode 15.1), whereas `lower()` reads the **provider's** tables (glibc 2.28 ≈ Unicode
  11, ICU 72–75 ≈ 15–15.1). Strictly speaking, identity requires the provider to know no case mapping
  the JDK does not know. Today that holds; a provider **ahead of** the JDK will break it, and the
  consequence will be exactly that created collision — that is, a refusal visible to the operator,
  not a lost account. A change of glibc/ICU changes the sort order, so a `REINDEX` is needed, and
  PostgreSQL warns about it itself.
− The guarantee **does not travel with the column**. Every new write path must still fold at the
  boundary, and every new existence check must fold in SQL. A type would do that automatically; a
  constraint does not. This is the knowing price of the "no read changed its meaning" item.
− The index does not stop a foreign writer from taking a **free** folded key with the string
  `Ivan@x.com`. After that the correct address becomes unregistrable, and the owner of the row cannot
  log in. Only `CHECK (email = lower(email))` would close this, and it is rejected separately: it
  would tie every application write to `lower()` instead of `Locale.ROOT`, and there is nothing to
  name in a refusal for a `23514` — it would be a bare 500, and one dependent on the deploy at that.
− Migration V23 is able to **block the upgrade** for a self-hoster who already has such a row. This is
  chosen knowingly: the account cannot be recovered, so the migration fixes nothing and deletes
  nothing, it refuses — with a message naming the count, the queries and the two remedies in the one
  order that works.
− Two indexes instead of one on a single column. On a table of "employees" scale that is nothing, but
  it is a line that will have to be explained at the next schema squash.

## Alternatives

- **`citext`** — rejected, and this is the main rejected branch. Four reasons, the first of which
  suffices:
  1. **Changing the type changes every comparison**, including `AuthService.login`, which HD-120 made
     exact deliberately. On a database where a foreign writer left `Ivan@x.com` and `ivan@x.com` as
     **two different people** (and until this migration the schema allows exactly that), a folding
     lookup at login resolves the typed address into whichever row the planner returns. The guarantee
     and the vulnerability arrive in one commit, and the vulnerability lands on the authentication
     path.
  2. **`citext` does not give stability against the collation, it gives LESS of it.** Its fold is the
     same `lower()` under the database's `LC_CTYPE` (stated outright in the PostgreSQL
     documentation). Meanwhile `citext` **preserves the case of the stored value** and recomputes the
     fold on every comparison of the raw value, whereas a functional index materializes the fold once
     on top of a value the application has already folded. The index's exposure is bounded by the
     application's fold; `citext`'s exposure is not.
  3. **`CREATE EXTENSION` is a privilege.** Since PostgreSQL 13 `citext` is marked `trusted`, i.e. a
     non-superuser with `CREATE` on the database can install it too — but "trusted" is a lower bound,
     not a guarantee: a managed provider's allow-list, a foreign schema, a `search_path` without the
     extension's schema break the installation, and such a self-hoster cannot run the migration at
     all.
  4. **The meaning of "the same address" would become a property of the connection string.** PgJDBC
     binds a `String` as `varchar` by default; it has been reported that a comparison of a `citext`
     column against a `varchar` parameter resolves **case-sensitively**, and the documented
     workaround is `stringtype=unspecified` for the whole connection, i.e. changing the binding of
     every string parameter in the application for the sake of one column. The upshot: either there
     is no guarantee at all, or there is one together with case-insensitive login, and what chooses
     between them is a connection parameter, not the schema.
- **A non-deterministic ICU collation on the column** (`deterministic = false`, PG 12+) — rejected for
  reason 1 in a stronger form: it makes the `=` operator itself case-insensitive, i.e. all the
  comparisons at once. Plus two costs of its own: under a non-deterministic collation the pattern
  matching operators (`LIKE`, `text_pattern_ops`) are unavailable, and the identity of an account ends
  up tied to a particular ICU version — exactly the exposure that point 2 avoids.
- **Leave it as is, strengthening code discipline** — rejected: this is the current state, and HD-120
  has already shown how it breaks silently. An invariant that rests only on every writer remembering
  the fold is not an invariant.
- **Fix the rows with a migration (`UPDATE … SET email = lower(email)`)** — rejected as part of the
  decision: folding in place changes **which mailbox the password reset goes to** for that account.
  V22 already forbade migrations to silently change whom an offer is addressed to; here it is the
  same act against an account, and the argument only gets stronger. The general shape: **a migration
  may fix what its application is able to create anew, and must refuse what it is not.**
- **`CHECK (email = lower(email))` in addition to the index** — not finally rejected, carried into a
  follow-up. It is the only thing that would close "a foreign writer took a free key", but it would
  tie every application write to `lower()` instead of `Locale.ROOT` — to different functions whose
  divergence depends on the deploy's `LC_CTYPE`. It makes sense only after a single folding point
  appears (`MailAddresses.fold(...)`), provably agreeing with `lower()` across a matrix of providers.
