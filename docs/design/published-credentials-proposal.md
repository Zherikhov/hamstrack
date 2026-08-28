# Published credentials — no value this project publishes as production configuration may be a working value (HD-200)

**Status:** design record of shipped work. **Date:** 2026-08-27. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness).
**Related:** `.env.prod.example`, `.gitattributes`, `docs/self-hosting.md`
(§"If your instance has the published admin account", §"What rotating `JWT_SECRET` does"),
`docs/observability.md` (the `pg_monitor` role), `docs/ops-prod-hardening.md`,
`docs/design/config-delivery-proposal.md` (HD-199 — the deploy that never touches `.env`),
`docs/design/production-backups-proposal.md` (HD-187 — `ops/backup/backup.env.example`,
the second template this rule reaches), ADR-0014.

**This is a record, not a proposal.** The work is built and the gates are green. It exists
because the rule below was refined across four review rounds, each of which narrowly avoided
writing it too narrowly, and because §14 settles a fork that is genuinely hard to reverse. Read
it before you weaken anything in it — in particular before you narrow a pattern, add a value to
a denylist, add an override flag, or "improve" the ordering in §8.3.

---

## 1. Problem & goal

`.env.prod.example` shipped placeholder values that **satisfied the guards meant to catch
them**. Every guard in this project fails on *absence* — `${VAR:?…}` in a Compose file, an
unresolvable `${VAR}` in `application.properties`, a length check in the application — and a
placeholder is the one thing that is not absent. Three of the values it published were live
credentials for anybody who copied the file and edited only what they recognised:

| Published line | Why nothing caught it | What an unedited install got |
|---|---|---|
| the `JWT_SECRET` placeholder, `REPLACE_WITH_64_CHAR_RANDOM_STRING` | **34 bytes**, clearing `JwtService`'s `>= 32` | every access token signed with a string printed in a public repository. Anyone able to read the repository could mint one, including a token claiming to be an administrator. The name says `64_CHAR`, so a reader who counts nothing assumes it is already right |
| `SEED_ADMIN_EMAIL` at a live-looking domain, paired with a `SEED_ADMIN_PASSWORD` that repeated its own variable name | nothing refuses a seed password at all — a blank one logs a WARN and skips | an **ACTIVE system administrator whose email and password are both published**. Two strings and you are an admin: no forging, nothing to guess, and the per-account backoff never engages, because a correct password is not a failed attempt |
| `DB_USERNAME` / `DB_PASSWORD`, each repeating its own variable name | these seed **both** the Postgres container and the application's datasource | copied unedited they do not fail, they **agree** — the install comes up cleanly on a production database whose password is published |

Two more instances were found later, in other files, **each in the round that was fixing the
previous one**: a 45-byte signing key (`change-me-to-a-random-string-of-32-plus-bytes`) in the
quick-start Compose block of `docs/self-hosting.md`, and a `pg_monitor` login in
`docs/observability.md` published twice on adjacent lines — a `DB_MONITOR_PASSWORD` env line
and a `CREATE ROLE … PASSWORD '…'` statement carrying the same literal — under a heading
recommending it *for production*.

Production was verified unaffected: every secret on the box is a real value. **The defect only
ever hurt people who followed our own instructions**, which for a project offering self-hosting
as a first-class path is the worst shape it could take.

**Goal.** That the property in §3 be *checkable* rather than *remembered*, that it hold for the
next credential on the day it is added rather than on the day somebody notices, and that the
installations which already copied a published value be reachable — which no edit to a template
can do.

**Success looks like:** a contributor who publishes a working credential learns it from a red
test whose failure message is the whole rule, and an operator running a published credential
today learns it from a refusal that carries them to repaired without the application running.

---

## 2. Scope

### 2.1 In scope

| # | Artifact | What it holds |
|---|---|---|
| 1 | `src/test/java/com/hamstrack/ops/PublishedCredentials.java` | the rule itself — what an assignment is, what makes a published value acceptable, which files are production configuration. Shared, so the two applications of it cannot disagree |
| 2 | `src/test/java/com/hamstrack/ops/EnvTemplateGuardTest.java` | the **template** half: no value in a template may satisfy its own guard, no guarded variable may be missing from the template, no template may ship a credential, and every template is pinned to LF |
| 3 | `src/test/java/com/hamstrack/common/security/JwtSecretValidationTest.java` | the **repository-wide** half: a walk of the working tree, plus a positive control replaying every defect shape this repository has actually shipped, plus the pins on all three escapes |
| 4 | `JwtService.PUBLISHED_PLACEHOLDERS` + `validateSecret()` | the running application refuses the two signing keys this project published |
| 5 | `DataSeeder.PUBLISHED_PASSWORDS` + `rejectPublishedPassword` + `rejectPublishedAdminHash` | the running application refuses the published seed password **in configuration**, and refuses a stored administrator hash that verifies it |
| 6 | `src/test/java/com/hamstrack/common/seed/SeedAdminPasswordValidationTest.java`, `SeedGuardStartupOrderingTest.java` | what those refusals *say*, and — separately — the **moment** they fire |
| 7 | `.env.prod.example`, `.gitattributes`, `docs/self-hosting.md`, `docs/observability.md` | the emptied template, the LF pin that keeps "empty" empty, and the operator's remedy |

### 2.2 Out of scope, named so the mechanism is not read as covering them

- **Password strength, anywhere.** Nothing here has an opinion about whether a value is weak.
  The two denylists hold values *we published*, decided by a checkable historical fact.
- **Secret scanning for leaked third-party keys** (AWS keys, GitHub tokens committed by
  accident). Different problem, different tool; this rule is about values the project
  *publishes on purpose* as samples.
- **Rotating anything.** The refusals tell an operator what rotation does and does not do; they
  do not rotate.
- **Names that are not credential-shaped.** `GF_SECURITY_ADMIN_USER` ships the literal `admin`
  in `.env.prod.example` and is outside the pattern by construction — a username is half a
  credential and the other half is empty. Deliberate; see §9.5.
- **Values in gitignored developer configuration** (`application-local.properties`). Not
  published, so not the subject.

### 2.3 Non-goals

This does not prove the repository has never published a credential — it proves that none of
the ones it publishes *today* would work as configured, and that the five it did publish are
refused by name where a template edit cannot reach them. It does not make an already-compromised
install safe; it makes it *loud*.

---

## 3. The rule, as a property

> **No value this project publishes as production configuration may be a working value.**

Phrased about the *category*, not about a member, and that phrasing is the whole of the
lesson. The first version of this rule was `grep JWT_SECRET`. It sat beside a published
`pg_monitor` login for a release and reported nothing, because the claim it made was about one
variable while the lesson was about all of them.

A **credential** is decided by the *name*, not by a list of names
(`PublishedCredentials.CREDENTIAL_SHAPED`): `PASSWORD`, `PASSWD`, `PASS`, `PWD`, `SECRET`,
`CREDENTIALS`, `TOKEN`, `KEY_ID`, `KEY`, optionally prefixed by `SHOUTING_` segments. The
suffix must be the whole name or follow an underscore, so `BYPASS` is not a `PASS`. The first
version of the pattern stopped at four words; a name is a credential by what it holds, not by
which four words we happened to think of.

An **assignment** is recognised in every dialect a reader actually pastes:

| Form | Where it comes from |
|---|---|
| `NAME=value` / `NAME: value` at the start of a line, optionally as a YAML list item | a dotenv line, a Compose `environment:` entry, a line in a fenced code block |
| `-e NAME=value`, `--env NAME=value`, `export NAME=value` anywhere in a line | a `docker run` in a runbook, a shell script in `ops/` |
| `$env:NAME="value"` | **PowerShell — the dialect the Windows half of our own runbooks is written in** |
| the SQL keyword `PASSWORD` followed by a quoted literal | a `CREATE ROLE` a reader pastes into psql |

The PowerShell form was added a round later, and how it was missed is the point: it is invisible
to *both* of the first two patterns, for two different reasons — the variable name is not at the
start of the line, and `$env:` is neither `export` nor a flag — and the values are
double-quoted. Meanwhile `docs/ops-prod-hardening.md`, which this very family classifies as
production configuration, sets `DB_PASSWORD` and `JWT_SECRET` in exactly that form, and so does
`docs/design/production-backups-proposal.md`. Every one of them carries a `drill-only-` label
today, which is precisely why nothing went red and why it would have stayed invisible until the
day one did not. Two reported cases and two allowed ones were added to the defect-shape control
at the same time — **the control is what makes the addition permanent**; a pattern with no case
behind it is removed by the next person tidying regexes.

Prose that *mentions* an assignment mid-sentence is deliberately outside it. This repository
explains its own defects in several places, and a rule that could not tell an explanation from
a setting would be answered by deleting the explanation.

A published value is **acceptable** on exactly one of five grounds:

1. **it is not a value** — empty, or an interpolation (`${…}`, `$(…)`). The guard that fires on
   absence gets to fire;
2. **it self-labels as unfilled** — `<a strong password>`, `…`. The angle brackets are the
   convention that carries the difference: `<a strong password>` reads as a blank to fill in,
   `a-strong-password` reads as a value somebody already chose, and only the second gets pasted
   into production;
3. **it self-labels as a throwaway** — `dev-only-…`, `ci-only-…`, `drill-only-…`,
   `test-only-…`. The label travels with the value into whatever file someone pastes it into,
   which membership of a list in a test does not;
4. **the application refuses it** — the only ground that also reaches installations that
   already copied it;
5. **it is a credential of the local development stack** — read out of the root
   `docker-compose.yml` / `docker-compose.*.dev.yml`, and **not** available inside production
   configuration.

---

## 4. Four refinements, each of which cost a review round

Each is a way the rule was nearly written too narrowly. They are recorded as refinements rather
than as bugs because each one *generalises* the rule, and a future edit that reverses one will
look like a simplification.

### 4.1 Keyed to the guarded set, not to a string

The obvious implementation is `grep REPLACE_WITH_`. It catches two rows of six and misses
`DB_PASSWORD` entirely — a different placeholder dialect — and the next placeholder will be in
a third. So the guarded set is **enumerated from the files that declare it**: every `${VAR:?…}`
in any tracked `docker-compose*.y{a,}ml`, and every no-default `${SHOUTING_CASE}` in any tracked
`src/main/resources/application*.{properties,yml,yaml}`. **Adding a guard enrols its variable
for free**, which is the opposite of a list that goes stale one entry before anyone notices.

The file sets are chosen so that an untracked file can only make the rule *stricter*: guards are
read from `git ls-files` (an untracked local `docker-compose.override.yml` cannot invent a
requirement nobody else's checkout has), templates are found by walking the tree (an uncommitted
template cannot escape).

### 4.2 Operator-facing documents are production configuration

The rule's own failure message named `docs/self-hosting.md` while the code classified production
configuration by *filename* and excluded `.md`. One round demonstrated the consequence three
times over: the quick-start Compose block and the restore drill in `docs/self-hosting.md` both
published a working Postgres password, and `docs/observability.md` handed out a `CREATE ROLE`
line — all green, because they are Markdown and Markdown was not "configuration".

**A reader pastes those blocks into a terminal; that makes them configuration regardless of
extension.** `PublishedCredentials.isProductionConfiguration` is now: env templates, the
enumerated operator manuals, anything under `ops/`, and any non-development Compose file.
`ops/**` is covered by *prefix* rather than by enumeration — those are scripts and units that
run on the production box, where nothing is ever illustrative.

Two smaller corrections rode in with it, and both are the same shape:

- `isDevelopmentCompose` now requires the **repository root**. It was a filename test regardless
  of directory, so a tracked `docker-compose.yml` in any subdirectory — an example stack, a
  fixture, something vendored — would have classified as development and put every credential in
  it into the by-value exemption. That is a repository-wide exemption granted by adding a file.
- `FLAG_ASSIGNMENT` needed `(?m)`. Without it the `^` alternative anchored to the start of the
  whole *file*, so `export DEPLOY_TOKEN=…` in column 1 — how every `ops/**` script writes one —
  matched nothing. Found by planting exactly that line to prove the new classification worked and
  watching the scan stay quiet.

### 4.3 The state matters, not only the configuration

A guard that reads the *configured* value cannot reach an install that already created the
account. Seeding is idempotent: the existing-account branch never re-passwords a user, so an
operator who sets a new password and restarts boots clean while the published one still works.
The same is true, more completely, of an operator who deleted the `SEED_ADMIN_*` lines — which
`docs/self-hosting.md` itself instructs — because then the configuration guard has nothing left
to look at.

So `DataSeeder.rejectPublishedAdminHash()` asks the **database**: does any system administrator's
stored hash verify a published password? That reaches both cases, and — the property a
configuration guard does not have — **it stops being true the moment the account is actually
repaired**.

### 4.4 Empty is the only safe template value

`${VAR:?…}` tests non-emptiness and nothing else, so *any* placeholder passes it — including a
well-mannered `<a strong password>`. Ship the line empty and put the instruction in a comment.

Two corollaries, both of which had to be argued:

- **A commented-out line is not empty enough, and an omission is worse.** Both hide the
  variable's existence from the reader who has to set it, and the omission leaves that reader
  facing a refusal that names a variable their copy of the file does not contain. Hence the
  second template test: every guarded variable must be *named* in `.env.prod.example`. The one
  exemption (`DB_URL`, set by `docker-compose.prod.yml` in the app service's own `environment:`
  block and never read from `.env`) is a claim that a reader never has to set it, and is wrong
  if they might.
- **"Empty" means empty including the carriage return.** `.gitattributes` pins the templates to
  `eol=lf`; without it a Windows checkout ships `VAR=\r`, which is non-empty, interpolates
  cleanly, and re-creates this entire defect through a door nobody watches. The pin is asserted
  twice — as a rule in `.gitattributes` and as its effect on the bytes in this checkout — and
  the pattern matcher deliberately reproduces the near miss that made it necessary:
  `*.env.example` does **not** match a name ending in `d.example`, which is why
  `.env.prod.example` has a line of its own.

---

## 5. What is deliberately **not** in the rule

- **The denylists hold only values *we* published as production configuration**, under that
  variable's own name. The standard is evidentiary and does not generalise: if you cannot name
  the commit (`git log -S`), the value does not belong there. Both sets are **pinned to their
  size** by a test whose failure message states that standard, because an earlier draft
  advertised "add it to `PUBLISHED_PLACEHOLDERS`" as a remedy — which makes silencing the whole
  rule a two-line edit. `JwtService.PUBLISHED_PLACEHOLDERS` has 2 entries;
  `DataSeeder.PUBLISHED_PASSWORDS` has 1.
- **`dev-only-` / `ci-only-` / `drill-only-` / `test-only-` self-label and are excluded.** Local,
  CI and restore-drill commands genuinely need values that work, and refusing them would break
  those commands to protect nobody.
- **There is no override flag.** "Mine is not on the list", in environment-variable form, is how
  such a guard dies. There is no property, no profile and no argument that disarms either
  refusal; the only way past is to change the value or repair the account.
- **The pattern is not narrowed when a name lies.** `IDEMPOTENCY_KEY`, `SORT_KEY` and
  `PARTITION_KEY` are credential-shaped and hold no secret. The move that suggests itself —
  narrow `CREDENTIAL_SHAPED` so it stops matching — is refused by the failure message itself: a
  rule about names cannot read what a value means, and every narrowing is permanent, silent and
  applies to every file. Use the unfilled-blank form instead; `<any unique string>` is a better
  sample idempotency key than an invented literal anyway.

---

## 6. Actors & permissions

**No application actor, no endpoint, no tenant resource.** Nothing here is workspace-scoped
because nothing here is a workspace-scoped resource: there is no query to scope and no
membership to check (§10).

| Principal | What this changes for them |
|---|---|
| A **contributor** editing a template, a runbook, an `ops/` script or a Compose file | a red test whose failure message is the rule, the four ways to make it pass in order of preference, and the cost of each defect that shipped |
| An **operator** copying `.env.prod.example` | an unedited copy **cannot start the stack, by design**. Each refusal names one variable; fill that one in and the next names itself |
| An **operator upgrading** into 0.18.0 with a published `JWT_SECRET` or a published seed password in `.env` | the application refuses to start, naming the value and what rotation does and does not do |
| An **operator upgrading** whose *account* carries a published password, whatever `.env` says | the application refuses to start, naming the address and prescribing one SQL statement performable with the application down |
| **Nobody** | can disable any of it by configuration |

---

## 7. The mechanism as built

### 7.1 The template half — `EnvTemplateGuardTest`

Five assertions, four of which apply to *every* env template in the checkout (today
`.env.prod.example` and `ops/backup/backup.env.example`) and one only to the Compose template:

1. **every guarded variable ships empty** in `.env.prod.example` — and a commented-out line is
   refused with its own message;
2. **every guarded variable is named** in `.env.prod.example`, or is listed in
   `NOT_THE_OPERATOR_S_TO_SET` with a reason;
3. **no template ships a credential**, keyed on the name shape — this is what reaches
   `SEED_ADMIN_PASSWORD`, which no `${VAR:?…}` guards, and it applies to `backup.env.example`,
   which is copied to a production server and was covered by nothing;
4. **every template is matched by an `eol=lf` rule** in `.gitattributes`;
5. **no template line carries a carriage return** — the pin checked on the bytes rather than on
   the rule.

The guarded-variable enumeration asserts that it found *something*, because an enumeration that
silently returns nothing makes every assertion built on it vacuously true.

### 7.2 The repository-wide half — `JwtSecretValidationTest`

A walk of the working tree (not the index — an uncommitted file is about to be published, and can
only make the rule stricter), reading `md`, `yml`, `yaml`, `properties`, `sh`, `java`, `txt`,
`example`, `service`, `timer`, and reporting every assignment that would work as configured.

**The positive control is the part to keep.** `assertThat(scanned).isNotEmpty()` catches exactly
one failure — reading no files at all. Drop `md` from the extension set, or add `docs` to the
skipped directories, and the scan stays green while blind to the file class where two of the
three original defects lived. So every defect shape this repository has actually shipped is
replayed through the real predicate over **in-memory fixtures**, and each must be *reported* —
together with six shapes that must **not** be, because a control that only proved the rule fires
would be satisfied by returning `true`. A second test asserts the walk's reach directly: that
Markdown is still scanned, that `docs/self-hosting.md` is still reachable, and that every
enumerated operator manual still exists as a file.

The fixtures — and the constants in every test in this family — are **assembled at runtime rather
than written as literals**, because these files are inside the scan and a written-out fixture
would be one more published credential.

### 7.3 The application half

| Guard | Where | When it fires |
|---|---|---|
| length `>= 32` on the signing key | `JwtService.validateSecret()`, `@PostConstruct` | context refresh |
| the two published signing keys, refused **by name** | same method, after the length check | context refresh |
| the published seed password in **configuration** | `DataSeeder.refusePublishedCredentials()`, `@PostConstruct` → static `rejectPublishedPassword` | context refresh |
| a **stored** administrator hash that verifies a published password | `DataSeeder.refusePublishedCredentials()`, second statement → `rejectPublishedAdminHash()` | context refresh |
| the published seed password **on the way in** (register, reset) | `AuthService.rejectPublishedPassword` → `DataSeeder.isPublishedPassword` | per request, `422` |

**The stored-hash guard was an `ApplicationRunner` in the first cut, and that was the round's
finding.** Boot calls `callRunners()` *after* the refresh that binds Tomcat's connectors, so the
refusal arrived at a fully functional instance. Measured, not argued: `Tomcat started on port
18081` at `04:44:03.255`, `GET /api/meta` answered `200` at `04:44:03.575`, a login as the
published-password administrator returned `200` with a 30-minute access token at `04:44:04.350`,
and the refusal landed at `04:44:11.213` — a **7.96 s** serving window, re-opened by
`restart: unless-stopped` on every crash-loop cycle. Moved to `@PostConstruct` and re-measured on
the same database, the log reaches `Tomcat initialized with port 18081` and **never** `Tomcat
started`; a poller hitting `/api/meta` every 50 ms across the whole boot recorded nothing. "It
needs the database" was the stated reason for running late and is not one — the repository is
injectable and usable at `@PostConstruct`.

`rejectPublishedPassword` is `static` for two reasons: the message can be read without a Spring
context, and the repository-wide scan can ask "would the application refuse this published
value?" of `SEED_ADMIN_PASSWORD` exactly as it already does of `JWT_SECRET` — which is how
ground 4 of §3 is *computed* rather than asserted.

It **strips before comparing**. A dotenv value is read verbatim, so a published password with one
trailing space nobody can see is a different string to the denylist and an identical one to
bcrypt: the account it seeds is signed into with the published value all the same. A guard that
whitespace bypasses is not a guard.

### 7.4 The moment, sealed separately from the message

`SeedGuardStartupOrderingTest` exists because of a regression **the whole suite let through**: a
fix round moved `rejectPublishedPassword` out of `@PostConstruct` into `DataSeeder.run` *below*
the blank-email and blank-password returns — precisely the arrangement its own javadoc forbids,
because those returns are what an already-seeded installation takes — and 1218 tests stayed
green. They stayed green because the only other test calls the static method by hand: it asserts
what the refusal *says* and never that anything reaches it.

**A seal that asserts a message does not seal a moment.** So this class asserts reachability, over
the whole class of branches rather than over the one that broke: the configuration guard fires
during context refresh before any `ApplicationRunner` exists; separately, **no** path through
`run` completes normally while the password is published, whatever else is configured; and the
stored-hash guard fires when the configuration mentions nothing at all. An
`ApplicationContextRunner` over a mocked repository, no database — which is what makes it cheap
enough to be a seal rather than a suite.

**The lesson recurred on the other guard, so the seal is now stated twice.** The stored-hash
check was itself left in `run()` (see §7.3), and every one of those reachability assertions was
satisfied by it, because they were phrased over `run` rather than over *when the port opens*. So
there are now two cases: one structural — an `ApplicationContextRunner` never invokes runners, so
`hasFailed()` can only be true if the refusal happened during refresh — and one that asserts the
consequence directly, over a real embedded Tomcat: refresh fails and **no
`WebServerInitializedEvent` is ever published**, which is Boot's own signal that the connectors
are up. Regressing the guard back into `run()` reds seven cases in that class, including both of
these.

**One more thing that class could not assert, and no longer pretends to.** It carried a
`surroundingWhitespaceIsNotABypass` twin of the direct-call case in
`SeedAdminPasswordValidationTest`, driven through `withPropertyValues("seed.admin.password=… ")`.
Spring's property machinery trims the bound value — measured: 19 characters, not 20 — so both of
its cases stayed green with `.strip()` deleted while the direct-call twin went red. The premise
is right (a real deployment supplies this through an environment variable, which preserves the
space); the vehicle could not carry it, so the redundant case was deleted rather than left as a
green assertion with a true-sounding name.

---

## 8. Edge cases & failure modes

### 8.1 The published value survives every repair of the file

Covered by §4.3. The refusal says so in capitals, and both refusal texts are asserted on that
sentence, because an operator who repairs `.env` and meets a second refusal they were not warned
about reads the release as broken.

### 8.2 The refusal fires at the one moment the admin console is unreachable

The refusal happens during context refresh, so the port is never bound and "sign in and change the
password" — what the message used to say — asks the reader to use a console that is not running.
(That sentence was *false* while the stored-hash guard was a runner: the console was reachable,
for about eight seconds, by anybody. See §7.4.) **A refusal may only prescribe an action its
reader can perform.** The remedy is therefore one SQL statement against the database,
complete enough to paste, with the account's own address in it, and it is asserted:

- `UPDATE users SET password_hash = NULL WHERE email = '…'` — keeps the account and everything it
  owns, and takes only its password away;
- then boot, then **Forgot password** on that address, or a reset from another system
  administrator, or delete the account if it should never have existed;
- and treat it as a compromise rather than a misconfiguration — the account has carried the
  published password for as long as it has existed, and every release before the one printing the
  refusal served requests with it active, so "*if* the instance was ever reachable with that
  account" is a condition that is always met: revoke every refresh session, delete unused reset
  links, audit the users list.

When the configured address has been removed, the statement **cannot be completed for the
reader**, so the message says so rather than inventing one — an invalid SQL remedy is a remedy
nobody can run.

### 8.3 The stored-hash probe is bounded, and the ordering is a security property

Verifying a bcrypt hash is deliberately slow — **measured at ~370 ms at the strength
`SecurityConfig` actually configures (12)**, not the ~100 ms of strength 10 this section first
claimed, and the loop runs once per *(administrator × published password)*, so the bound costs
~6.9 s of startup today and would double the day a second value joined `PUBLISHED_PASSWORDS`. An
unbounded probe was written first and was not viable: a database that had accumulated 1362
administrators added **over two minutes to every single startup**, and the suite stopped
finishing. So the probe reads the **oldest** administrators carrying a password, plus whichever
account `seed.admin.email` names today.

The verdict for a given stored hash is memoised per JVM, keyed on the hash. That is a memo of a
pure function and not a cache of a security decision — a repaired account is a different key, or
no key at all — and it exists for the suite, where 47 Spring contexts were each re-verifying the
same rows: worth about five minutes. A production JVM refreshes one context, so the map holds at
most `ADMINS_PROBED + 1` entries and is never read twice.

**The security argument for *oldest* is the load-bearing one, and it must be written down**,
because a refactor to "the 25 most recently active" reads as an improvement and hands over an
eviction primitive:

> `createdAt` is never assignable from application code — it is written by JPA auditing on
> `@PrePersist`. So evicting a compromised account from the oldest-first window requires direct
> database write access, which already moots the guard. **Every ordering influenced by a
> request** — newest-first, by last activity, by login count — **is evictable by an attacker who
> mints administrators through the admin API**, which is exactly what the account this probe
> exists for lets them do.

**The bound is covered by two mechanisms, and it is worth resisting the sentence that presents
them as one guarantee.** "The seeded account is the first administrator, by construction" is not
true, and the by-name lookup is the code conceding it. The *ordering* reaches the account the
installer creates on first boot — but only while fewer than 25 administrators predate it, which is
the usual case and not a property. The *by-name* lookup of `seed.admin.email` reaches whatever the
configuration names today, wherever it sits in that ordering, which covers an install that only
began seeding years in — but only while the variable is still set, and the stored-hash guard
exists precisely because it often is not. There is a test whose whole purpose is to prove the
second lookup does work rather than duplicating the first; what both miss is §8.4.

The probe deliberately covers the whole `ADMIN` role rather than only `ACTIVE` ones: a disabled
administrator whose stored password is public is one re-enable away from the same thing, and the
operator should hear about it while they are already fixing the others.

### 8.4 The residual, named so it is not discovered

An install with more administrators than the bound, **whose published-password account was
created later**, and whose `SEED_ADMIN_*` lines have since been removed — which the manual itself
instructs — falls outside both mechanisms. Nothing refuses it; there is only a startup WARN saying
how many of how many were covered.

That WARN is the whole of the mitigation for this residual, which is worth saying because it is
the one place in HD-200 that accepts a signal read after the incident rather than a refusal — a
deliberate exception, argued in §15 Q1.

**That WARN was unsealed and it cried wolf. Both are fixed.**

*Unsealed:* deleting the whole `if (…) log.warn(…)` block red **zero** tests, while it was the
only thing distinguishing "checked and clean" from "checked twenty-five of them and clean" in the
operator's eyes. Two cases now cover it, and deleting the block reds the first.

*Crying wolf:* it compared `countBySystemRole(ADMIN)` — *every* administrator — against a bound
governing a query filtered to `passwordHash IS NOT NULL`, so on an install with 26 administrators
of whom 22 carry a password, **every one of them verified**, it still announced a shortfall — and
named `25`, the constant, rather than the 22 it actually probed. It could not produce a false
negative, so this was message quality; but a partial-coverage warning that fires on complete
coverage is how such a warning gets tuned out, which is a false negative by a slower route. It now
gates and reports on `countBySystemRoleAndPasswordHashIsNotNull` and logs the number of candidates
actually verified. `countBySystemRole` had no other caller and is gone.

### 8.5 Smaller ones, decided

| Case | Behaviour |
|---|---|
| a blank `SEED_ADMIN_PASSWORD` | WARN and skip seeding — the symptom is that nobody can log in, deliberately the louder half of the alternative |
| a `null` or empty password reaching `rejectPublishedPassword` | accepted; this guard has no opinion about absence, which is somebody else's rule |
| a near miss (the published value lower-cased) | accepted. This refuses **one string, by name** — it is not a fuzzy matcher, and making it one is how it becomes a strength checker |
| the seed address folded by a Turkish/Azeri/Lithuanian JVM locale | a pre-existing hazard documented under "Duplicate accounts after an upgrade"; the image pins the JVM locale from 0.16.0 |
| a template with CRLF in a checkout | reported by the byte-level test, not only by the `.gitattributes` rule |
| the tests run outside a checkout, or from the wrong directory | every enumeration in this family fails loudly rather than passing on an empty set |
| a `$` inside a value in `.env` | Compose **interpolates** it, and an undefined name expands to nothing — `Vk5$mT8pQr2zXn6w` reaches the container as `Vk5`. Not detectable by anything we run, so it is documented as a property of *every* value in the file (`.env.prod.example` header, `docs/self-hosting.md` Configuration), with the two ways out: double it (`$$`), or generate a value with none — `openssl rand -base64 48` never emits one. It lands hardest on `SEED_ADMIN_PASSWORD`, which seeds an administrator with a password the operator never chose and cannot read back |

### 8.6 The published value could still enter `users` through the application

Startup refuses an administrator whose *stored* hash verifies the published password. Nothing
refused it on the way **in**: `RegisterRequest` and `ResetPasswordRequest` bounded the password
only with `@Size(min = 8, max = 100)`, and the literal is 19 characters. So an administrator could
set their own password to it from the ordinary "choose a new password" form, and two things
follow — the next restart refuses to boot naming a template nobody edited, and until that restart
the instance is administrable by anyone who can read the repository.

Both write sites now call the same predicate (`DataSeeder.isPublishedPassword`, over the same
`PUBLISHED_PASSWORDS`) and answer **422**: the request is well-formed and satisfies every
syntactic constraint on the field, so this is a business rule about which value is acceptable. The
reset check runs **before** the reset row is marked used, so a caller who picks a refused password
keeps their link.

The guard was written where the value is *read* and not where it is *written*, which is the shape
worth remembering: **three readers of one set, and the set has one home.**

---

## 9. What the escapes cost, and how each is held

Three escapes exist. All three are pinned, and the pins are the interesting part.

| Escape | Keyed to | Pin | The risk it carries |
|---|---|---|---|
| `JwtService.PUBLISHED_PLACEHOLDERS` | (variable, exact string) | size == 2 | becoming a weak-secret denylist |
| `DataSeeder.PUBLISHED_PASSWORDS` | (variable, exact string) | size == 1 | same |
| `localDevStackCredentials()` | a bare **value** | size == 2 | the widest of the three |

**The third is the weakest and knows it.** It is exempt by *value*, so every string in it is
acceptable in every non-production file in the repository, under any variable name at all — and
one of its two members is an ordinary English word (`admin`). A single unused credential-shaped
line added to a development Compose file would make that value acceptable repository-wide. Two
things hold it: the set is **derived from what `docker compose up` actually creates** rather than
listed in a test (you cannot widen it by editing a list — you would have to change the
containers), and it is **unavailable inside production configuration**, which is what stops a
restore drill from publishing a working Postgres password again.

### 9.5 One published literal that is outside the rule on purpose

`.env.prod.example` ships `GF_SECURITY_ADMIN_USER` with the literal `admin` while
`GF_SECURITY_ADMIN_PASSWORD` ships empty, and `SEED_ADMIN_EMAIL` ships a reserved RFC 2606
example domain while `SEED_ADMIN_PASSWORD` ships empty. Both are half a credential with the other
half absent, and the rule is a rule about credential-shaped *names*, so neither is matched. That
is the intended reading: publishing a username is publishing an assumption, not a way in. The
reserved domain is itself a fix — a live-looking address is a trap where an operator fills in the
password, forgets the email, and seeds a real administrator at an address they do not own, with
public "forgot password" delivering a reset token to somebody else's mail server.

---

## 10. Data model, API surface, frontend

- **Data model:** no migration, no table, no column, no entity change. `rejectPublishedAdminHash`
  adds two derived-query methods on `UserRepository` and reads existing columns. Nothing here is
  workspace-scoped, because nothing here is a tenant resource — the reads are `users`-wide by
  system role, at startup, with no request and no principal.
- **API surface: two existing endpoints gained a response.** `POST /api/auth/register` and
  `POST /api/auth/reset-password` now answer **422** when the submitted password is the published
  value. No endpoint is added, changed in shape, or removed — but this section said "no update
  needed" for a release and was wrong the moment the write-site guard was added, so:
  **`openapi.yaml` and both `docs/api-*.md` need the 422 on those two operations** (`api-docs-sync`).
  The reason the guard exists at all is §8.6.
- **Frontend:** no page, component, store or route. The SPA renders the `detail` from the problem
  document, which is what the message is written for.

The other *runtime* behaviour change is that a boot can now fail. That is §14.

---

## 11. DC/Cloud implications

**No profile gating, and that is the decision rather than an omission.** Every part of this is
identical in `dc` and `cloud`, because the two halves of the argument point the same way:

- the **template** and the manuals are read overwhelmingly by self-hosters, so the DC path is
  where the defect did its damage;
- the **refusals** run in Cloud too, and must, because Hamstrack's own hosted install is made from
  the same image and could in principle have been built from the same template.

**No new environment variable, and deliberately no toggle** (§5). `dc-cloud-guard`'s wiring
checklist has nothing to wire here; what it should check instead is that no future contributor
adds `app.security.allow-published-credentials` — which would be a cloud-safe, DC-fatal switch and
the exact shape this rule refuses.

`ops/backup/backup.env.example` — the DC-facing template from HD-187 — is inside the credential
and carriage-return rules and outside the guarded-variable ones, because it is read by a systemd
unit that has never heard of `${VAR:?…}` and demanding `DB_PASSWORD` in it would be nonsense.

---

## 12. Acceptance criteria

A reviewer re-verifying this record checks that:

1. `.env.prod.example` ships **empty** for every variable guarded by a `${VAR:?…}` in a tracked
   Compose file or a no-default `${SHOUTING}` in a tracked `application*.properties`, and names
   every one of them.
2. Adding a new `${VAR:?…}` to any Compose file **fails** `EnvTemplateGuardTest` until the
   template gains an empty line for it — no list anywhere had to be edited.
3. Every env template is matched by an `eol=lf` rule and contains no carriage return.
4. The repository-wide scan reports **zero** published values that would work as configured, and
   its positive control still reports every defect shape this repository has shipped while
   allowing every acceptable one. (Deliberately not a count: a number goes stale one entry before
   the list does, and this one did — two dialect cases and two allowances joined it a round after
   it was written.)
5. Removing `md` from the scanned extensions, or `docs` from the walk, turns a control **red**
   rather than leaving the scan green.
6. `JwtService` refuses both published signing keys **by name**, and each is `>= 32` bytes — a
   sub-32 entry would prove nothing beyond the length check.
7. The signing-key refusal says rotation **does not end sessions** and names all three follow-up
   steps in the same words the prose copies use — `DELETE FROM refresh_tokens`, `DELETE FROM
   password_resets WHERE used_at IS NULL`, and auditing administrators. The Java copy said "audit
   … recent password resets" where both prose copies carried the statement; parity across all
   three is asserted rather than remembered, and `password_resets` is the one that matters,
   because an unused admin-issued setup link (7-day TTL) survives both the rotation and the
   `refresh_tokens` delete.
8. `DataSeeder` refuses the published seed password **during context refresh**, and no path
   through `run` completes while it is set, whatever the email is. Whitespace on the password is
   asserted by the direct-call test only — the property-bound vehicle trims the value and cannot
   express it (§7.4).
9. The stored-hash guard fires when the configuration mentions nothing, and after the file has
   been repaired instead of the account.
9a. **The stored-hash guard also fires during context refresh, and the instance never serves.**
    Asserted twice: an `ApplicationContextRunner` (which never invokes runners) sees the context
    fail, and a real embedded Tomcat never publishes a `WebServerInitializedEvent`. Regressing the
    guard into `run()` reds seven cases.
9b. **A partial check says so, and a complete one is silent.** The WARN fires when administrators
    with a password outnumber the candidates verified, reports the number actually verified, and
    does **not** fire when every password-carrying administrator was covered. Deleting the WARN
    block reds a test.
9c. **Register and reset refuse the published value with 422**, through the same predicate as the
    startup guards, and a refused reset leaves the link usable.
10. The stored-hash refusal contains a complete `UPDATE users SET password_hash = NULL …` and the
    words `STORED password` and `idempotent`.
11. An instance with nothing published **starts and still seeds**, and a blank email still skips
    seeding.
12. All three escapes are pinned to their size, and each pin's failure message states the
    evidentiary standard for changing it.
13. **The bound and the query agree.** `DataSeeder.ADMINS_PROBED`, the number baked into the
    repository method name, and the number the ordering test asserts are **one number** (today,
    25). Spring Data reads the limit out of the method name, so the constant is a comment unless
    all three move together — which is why the test's failure message says so, and why this is a
    criterion rather than a note.

---

## 13. The pattern, which outlives the incident

Across this ticket and the three before it, **five fix rounds introduced a defect of the same
class they were closing**, and one reintroduced the identical defect one file over. Not one was
found by reading; every one was found by executing. Four times two reviewers arrived at the same
finding independently. Twice the defect was in the orchestrator's own instructions.

This is not a moral. It is an operating fact with two consequences, and both are already load-
bearing above:

- **Prose that describes behaviour has to be re-checked whenever that behaviour changes.** A
  document, a javadoc or a failure message that *enumerates* or *counts* goes stale exactly one
  entry before anybody notices, and it does so **while containing none of the words a grep for
  the new thing would find**. §4.2 is the instance this ticket paid for: the rule's own failure
  message named `docs/self-hosting.md` as production configuration while the code classified by
  filename and excluded `.md`, so the sentence and the behaviour disagreed for a whole release
  and no grep could have found it — the message was *right* and the code was wrong.
- **A seal that asserts a message does not seal a moment.** §7.4 is the whole of that lesson: a
  test that reads what a refusal *says* proves nothing about whether anything reaches it, and the
  regression that taught this passed 1218 tests.

The generalisation that follows, and that this record is written to: **prefer a claim phrased
about a category to one phrased about a member**. "A rule about credential-shaped names" survives
the next variable; "a check on `JWT_SECRET`" did not survive the first one.

---

## 14. Architectural decisions

One, and it is genuinely hard to reverse: **the application refuses to start when it finds a
published credential — in configuration, or in a stored password hash.** Drafted as
**ADR-0014** (`docs/adr/0014-refuse-to-boot-on-published-credentials.md`, `Status: Proposed`).

The honest accounting, in brief — the ADR carries it in full:

- an install in that state is not degraded, it is **owned**: publicly administrable, or publicly
  signable, by anyone with a browser and the repository URL. A WARN in a startup log is read
  after the incident;
- the cost is real and unusual — this can stop the boot of an operator who did nothing wrong
  today, at the one moment they cannot use the admin console, because the refusal is what makes
  it unreachable;
- what makes that cost payable is that the remedy needs only credentials the operator has **by
  definition** (database access — they configured it) and is a **single SQL statement**, carried
  in the refusal itself and asserted by a test;
- the bound and its ordering are part of the decision, not an implementation detail (§8.3);
- and the residual is named rather than papered over (§8.4).

**The highest-risk assumption in this whole record**, stated plainly: that the scan's *reach*
tracks where operator-copyable configuration actually lives. `SCANNED_EXTENSIONS`,
`SKIPPED_DIRECTORIES` and especially `OPERATOR_FACING_DOCUMENTS` — three hand-listed paths — are
enumerations, and the documented history of this rule is that its enumerations were wrong. The
next runbook this project writes is outside the rule until somebody adds it, and **nothing fails
when it is not added**. Two things mitigate it and neither closes it: `ops/**` is covered by
prefix, and a test asserts that each enumerated manual still exists as a file (so a *rename*
cannot silently empty the set, though an *addition* still can). If one thing here is worth a
follow-up ticket, it is turning that enumeration into a property.

---

## 15. Open questions — what genuinely needs the owner

Separated from everything above, which is decided.

**Q1. Should the WARN in §8.4's residual become a refusal after a grace release?** Today an
install outside both mechanisms gets a log line, which is the one place this ticket accepts the
"read after the incident" failure it rejects everywhere else. *Recommendation: no.* Refusing on
"we did not check" rather than on "we found it" stops boots for a state nobody has evidence of,
and the fix — probe every administrator — is the two-minute regression that forced the bound.
Revisit only if an unbounded probe becomes cheap (a cheaper first-pass filter than bcrypt).

**Q2. Should `OPERATOR_FACING_DOCUMENTS` become a property instead of a list?** (§14, the
highest-risk assumption.) Candidates: every `docs/**/*.md` that contains a fenced block whose
first line looks like a shell command; or a front-matter marker each runbook declares.
*Recommendation: file it as a follow-up rather than doing it here* — both candidates trade a
silent gap for false positives across a doc tree that is mostly prose, and the failure message
already routes a contributor to the list. But do not close the ticket believing the enumeration is
safe; it is the one place this rule is still keyed to a list somebody must remember.

**Q3. Does anything else this project publishes need the by-name refusal treatment?** The
denylists cover the signing key and the seed password. The published `DB_PASSWORD` and the
published `pg_monitor` password have **no** application-side refusal — the template was emptied
and the runbooks were corrected, which reaches the next install and not the ones that already
copied them. *Recommendation: accept the asymmetry, and say why in the ADR* — a database password
is not something the application can refuse without also refusing to reach its own data, and an
install running the published one is fixed by rotating a Postgres role rather than by a startup
error. But this is the owner's call, and it is the question a security reviewer will ask.

**Q4. Should the ADR record §14's decision as also covering *future* published values?** As
written the mechanism is generic (add a string, pin the new size) but the decision is stated over
the values we published. If the answer is "yes, this is the standing policy for any credential we
ever publish", the ADR should say so, because that is what makes the pinned-size tests a policy
rather than a chore.
