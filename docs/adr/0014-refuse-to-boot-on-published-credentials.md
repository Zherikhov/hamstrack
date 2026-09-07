# ADR-0014: The application refuses to boot when it finds a credential we published — in the configuration or in a stored password hash

Record date: 2026-08-27
Status: Accepted
Source: `docs/design/published-credentials-proposal.md` §4.3, §8, §14 (HD-200);
code — `JwtService.validateSecret`, `DataSeeder.rejectPublishedPassword`,
`DataSeeder.rejectPublishedAdminHash`; the operator instruction —
`docs/self-hosting.md` §"If your instance has the published admin account"

## Context

`.env.prod.example` published placeholder values that **passed the very guards that were supposed to
catch them**. Every guard in the project fires on **absence** — `${VAR:?…}` in the compose file, an
unresolvable `${VAR}` in `application.properties`, the signing-key length check — and a placeholder
is exactly the thing that is not absent. Three of the published values were working credentials:

- a `JWT_SECRET` placeholder **34 bytes** long, i.e. one that passes the "at least 32" check. Any
  unedited installation signed every access token with a string from a public repository;
- both halves of the administrator account: the address and a password repeating the name of its own
  variable. An installation made from the unedited template got an **active system administrator**
  anyone can log in as. Nothing is forged and nothing is guessed, and the per-account backoff never
  engages, because a correct password is not a failed attempt;
- the database login and password, which seed **both** the Postgres container **and** the
  application datasource: copied without editing they do not conflict, they **match**, and the
  installation comes up on a database with a published password.

Production was not hit — real values everywhere there. **The defect struck exactly those who
followed our own instructions.**

What makes this a fork in the road rather than routine:

- **Fixing the template does not reach installations that already happened.** The seeding is
  idempotent: the "the user already exists" branch never resets the password, so an operator who put
  a new password into `.env` and restarted gets a clean start while the published password keeps
  working. For someone who deleted the `SEED_ADMIN_*` variables long ago (and the instructions
  explicitly suggest that), a configuration check has nothing to look at at all.
- **So what is needed is a check of state, not of configuration** — and then the question arises of
  what to do with a find: log a WARN or refuse to boot.
- A boot refusal is **expensive to reverse**: it can stop the installation of an operator who did
  nothing today, and it does so at the moment when the admin console is unavailable — because it is
  unavailable precisely on account of that refusal.

## Decision

**The application refuses to boot.** Both halves:

1. **Configuration.** `JwtService.validateSecret()` (`@PostConstruct`) rejects by name the two
   signing keys the project published — on top of the length check.
   `DataSeeder.refusePublishedCredentials()` (`@PostConstruct`) rejects the published seed password.
   Both checks live in `@PostConstruct` deliberately: it is the cheapest "before everything
   else", and the "whether to seed at all" branches sit below it.
2. **State.** `DataSeeder.rejectPublishedAdminHash()` — **the second statement of that same
   `@PostConstruct`** — asks the database: does the hash of any system administrator verify the
   published password. This reaches both compromised configurations and, unlike the configuration
   check, **stops firing at the moment the account is actually fixed**.

   **The moment here is part of the decision, not a placement detail.** In the first draft this check
   was the first statement of `run(...)`, i.e. an `ApplicationRunner`; Boot calls `callRunners()`
   **after** the refresh that brings up the Tomcat connectors. Measured: `Tomcat started on port
   18081` at `04:44:03.255`, `GET /api/meta` → `200` at `04:44:03.575`, a login as the administrator
   with the published password → `200` and a 30-minute access token at `04:44:04.350`, the refusal —
   at `04:44:11.213`. That is **7.96 seconds of a fully working instance**, and with
   `restart: unless-stopped` the window reopens on every turn of the crash loop. After the move into
   `@PostConstruct` the same run against the same database reaches `Tomcat initialized` and **never**
   `Tomcat started`; polling `/api/meta` every 50 ms across the whole start got not a single
   response. The argument "it needs the database" does not hold: the repository is injected and
   fully usable in `@PostConstruct`.
   **The moment rests on eager bean creation, and that is pinned down explicitly.** `@PostConstruct`
   runs inside `finishBeanFactoryInitialization` only while the bean is created eagerly. In itself
   that is a property not of the guard's code but of the *deployment configuration*: `spring.main.lazy-initialization=true`
   (or `@Lazy` on the class) moves the call to wherever the bean is first needed — and that is
   `SpringApplication.callRunners`, the very stack frame the guard was moved to escape. Measured on
   a real `SpringApplication` with a real Tomcat: an eager bean — zero connections across the whole
   start; with the lazy-initialization property — **386 connections**; with `@Lazy` on the class the
   window opens too. That is, an environment variable reopened the measured seven-second window of a
   working login under the published password **without touching a single line of the guard
   itself**, and every existing seal stayed green meanwhile: both `ApplicationContextRunner` and a
   direct `refresh()` create beans eagerly and do not read that property.

   Hence `@Lazy(false)` on the class. That is neither decoration nor redundancy:
   `LazyInitializationBeanFactoryPostProcessor.postProcess` returns early on a bean definition whose
   laziness flag is **set explicitly** — that is, the property does not reach that bean. Pinned down
   by the case `SeedGuardStartupOrderingTest.nothingDefersTheGuardPastThePortBind`.

   The general rule worth taking away from here: **a guard whose value lies in its moment depends not
   only on where its call is written, but also on what decides when that call happens.**
   The latter is configuration, and it has to be pinned down separately.

3. **Writing.** `AuthService` rejects the same value at the two doors through which a password
   reaches `users` from a running application (registration and reset completion) — **422**, through
   that same `DataSeeder.isPublishedPassword` predicate. Without it an administrator could set the
   published password on themselves: 19 characters, and `@Size(min = 8)` settles nothing here.

**There is no off switch.** No property, no profile, no argument. "My case is different" in the form
of an environment variable is how a guard like this dies.

**The lists are not a strength check.** They contain only what *we* published as production
configuration under its own variable name; the criterion is evidential (`git log -S`), not "looks
weak". The size of both sets is pinned down by a test, and that criterion is written in its
message.

**The refusal must carry its reader to the fix without a working application**, so its text carries
a single SQL statement (`UPDATE users SET password_hash = NULL WHERE email = …`) that keeps the
account and everything it owns and takes away only the password; then a boot, then an ordinary
password reset. This is checked by a test, not upheld by agreement.

**The hash probe is bounded, and the order it walks is a security property, not a detail.**
The **oldest** administrators with a password are checked, plus whoever `seed.admin.email` names
today. The reason for exactly this order: `createdAt` is not assigned from application code, so
pushing a compromised account out of the "oldest" window is possible only by writing directly to the
database — and for whoever can do that, this guard settles nothing anyway. **Any order a request can
influence** (newest first, by activity) **is evicted by an attacker who stamps out administrators
through the admin API** — that is, by exactly what a captured account gives them. A refactor "to the
25 recently active" looks like an improvement and gives that primitive away.

## Consequences

+ An installation in this state is not "slightly weakened" — it is **captured**: publicly
  administrable or publicly signable by anyone with a browser and the repository address. A WARN in
  the startup log gets read after the incident.
+ The state check reaches those the template fix does not reach, and it lifts only on a real fix of
  the account.
+ The absence of an off switch means the guard cannot be "temporarily" disabled and forgotten.
+ The cost of the fix is paid with the accesses the operator has **by definition** (they configured
  the database), and it is a single SQL statement.
− The boot can fail for someone who changed nothing today — they merely upgraded. The refusal text
  must say so plainly, or the release reads as broken.
− The refusal fires at the moment when the admin console is unavailable precisely because of it.
  This is accepted knowingly and is offset by the means of repair being the database, not the
  console.
− Every checked account costs one bcrypt verification on **every** start — measured at **~370 ms**
  at the cost factor `SecurityConfig` actually sets (12), not the ~100 ms of factor 10, as was
  written here at first. The loop runs once per pair (administrator × published password), so the
  bound of 25 is ~6.9 s of startup today, and it will double on the day a second row is added to
  `PUBLISHED_PASSWORDS`. The unbounded version added more than two minutes to the start on a
  database with 1362 administrators, hence the bound, and raising it is a knowing trade against boot
  time.
− A hole remains: an installation with more administrators than the bound, where the compromised
  account was created later and the `SEED_ADMIN_*` lines are already deleted, is covered by neither
  of the two mechanisms — only the WARN at startup is left. Named in the spec (§8.4), not painted
  over.
− An asymmetry: the published database password and the published `pg_monitor` password are **not**
  covered by the refusal — the template was emptied and the runbooks corrected, i.e. the next
  installations are protected, not those already made. The application cannot reject the database
  password without renouncing its own data along with it; it is fixed by rotating the Postgres role.
  Carried into the spec's open questions.

## Alternatives

- **Only empty the template** — rejected: that protects the future reader and does not reach a single
  already-existing installation. Precisely the half one would like to consider sufficient, and
  precisely the one that does not work because the seeding is idempotent.
- **A WARN at startup instead of a refusal** — rejected: a signal that an account can be captured
  with one login form gets read after the fact. This is the only place where the project accepts
  giving up loudness — and it is accepted only for the hole from "Consequences", where there is no
  proof of compromise, only "we did not check".
- **A separate administrative command/endpoint "check the credentials"** — rejected: it requires a
  live application and a person who remembers to run it, i.e. it moves the guard into discipline.
  That is exactly the class the original defect belongs to.
- **Check every administrator, with no bound** — rejected on measurement: >2 minutes added to every
  start on 1362 accounts, and the test run stopped finishing.
- **The "recently active" order instead of "the oldest"** — rejected: it gives an attacker an
  eviction primitive through the admin API. It looks like an improvement, so it is written down here
  explicitly.
- **A bypass flag (a property or an environment variable)** — rejected: "my case is different" in the
  form of configuration is the standard way a guard like this turns into dead code.
- **Extending the lists on the "the password looks weak" criterion** — rejected: that is a strength
  check, it goes stale weekly and invites the reasoning "mine is not on the list". The strength of a
  published value is beside the point entirely: it is published.
