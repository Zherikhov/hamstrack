# Doors — one population harness with floors — HD-296 (epic HD-294, fix version 0.18.2)

> **Status: proposal.** Spec date **2026-09-08**. Every claim about today's tree is labelled
> **measured** (grep/read on 2026-09-08 with file:line; no shell was available, so counts are
> textual and the builder confirms them by reflection — §11 lists what to measure first),
> **read** (file:line) or **inferred**. 3 story points: two helpers, two harness tests, two
> migrations, one new category test, one doc bullet, two one-line agent-file edits.
>
> Backlog search: repo-side grep for `Doors|HD-296|K1-T1|RequestRecordBoxedFieldsTest|HD-49|HD-73`
> (2026-09-08) — no harness exists; `common.testsupport.Doors` is already **named as if it
> did** in `CLAUDE.md:143`, `.claude/agents/backend-builder.md:17`, `test-runner.md:27` ("where
> it exists") and `.claude/pipeline/check-gates.mjs:183`. Related: **HD-297** (ArchUnit — must
> not be assumed here), **HD-295** (no bare `assert`, no skip without a ticket — binds this
> ticket's tests), **HD-265** (test-name rule — binds the helper names), **HD-73**
> (`AdminUpsertBooleanFlagOmittedTest`, the regression the new test generalises; HD-49 has no
> trace in the tree, only the ticket), retro items K1-T2 (the boxed-fields test, "subsumed"
> here) and K5-T1 (`ScanFloorsTest`, a later consumer of `Population.floor`).

## 1. Problem & goal

81 of 194 retro defects were a rule applied to one door and not to its siblings. The tests
that catch that class each carry a **private** scan of "all the X", and the scans disagree
with each other (§4.1): three definitions of *write handler*, two of *request record*, two
identical copies of *mailer*, two copies of a crude method parser one of which carries a
bug the other fixed. Each new category test starts from zero and inherits none of the
tripwires the last one learned. Goal: **one place answers "what are all the X"**, every
answer carries a floor, the answer is proven equal to the runtime's own view once, and
three tests (two migrated, one new) consume it so the harness is proven by use.

## 2. Scope / non-goals

**In:** `src/test/java/com/hamstrack/common/testsupport/Doors.java` and `Population.java`
(helpers — not test-named, HD-265); `DoorsHarnessTest` (floors on the real tree, the
compiled⇄tracked parity, the `[doors]` count line) and `DoorsHandlerMappingParityTest`
(`@SpringBootTest`, Doors ⇄ `RequestMappingHandlerMapping` equality); migrating the scans
of `RequestFieldLengthBoundTest` and `WriteThrottleCoverageTest` onto it; the new
`RequestRecordBoxedFieldsTest`; one bullet in `docs/project-state.md` beside the HD-295
one (`:576`); `test-runner.md:27` drops "where it exists".
**Out:** migrating the other private scanners (§4.1 names them — each is a later
one-line-per-test change, and `mailSendSites()` / `controllerParams()` /
`problemJsonWriters()` exist now so those migrations are edits, not designs); any new
*rule* over `problemJsonWriters()` (the drift it exposes — §4.3 P9 — is filed, not fixed
here: it is outside the category this diff touches); ArchUnit or any scanner dependency
(HD-297 decides; `Doors` can be re-based on it later without touching consumers, which is
the point of one harness); a rule that every scan *must* use a floor (K5-T1); frontend;
production code — **`Doors` reads `src/main`, it never changes it**.

## 3. Actors & permissions — n/a

A build-time harness. No request, no workspace, no `Permission`, no 404/403 question.

## 4. Behaviour & rules

### 4.1 What exists today — the duplication, quantified (read 2026-09-08)

| Test | How it enumerates | Population | Floor | Message |
|---|---|---|---|---|
| `RequestFieldLengthBoundTest` (Spring ctx) | `Files.walk(src/main/java)` + regex `@(Post\|Put\|Patch)Mapping\b` on comment-stripped source + `Class.forName` + `getDeclaredMethods` (`:130-133`, `:492-517`, `:593-633`) | write handlers, **3 verbs, no `@RequestMapping(method)`**, reduced by `acceptsFreeText` (`:520-561`) | rows ≥ 45 (`:128`), scanned ≥ 130 (`:436`), free-text ≥ 80 (`:460`), sources > 100 (`:415`) | ≤ 25 lines, three moves (`:463-487`) |
| `WriteThrottleCoverageTest` (Spring ctx) | `RequestMappingHandlerMapping.getHandlerMethods()` (`:238`), verbs {POST,PUT,PATCH,DELETE}, **unconditioned mapping = all four** (`:302-308`) | mutating handlers under `/api/workspaces` | probed > 30 (`:271`) | `WHAT_TO_DO` (`:198-228`) |
| `ThrottleCoverageTest` (Spring ctx) | `getHandlerMethods()` twice (`:392`, `:829`); **first verb or GET** (`:933-935`); interceptor registrations via `ExposedRegistry` | handlers under 2 packages ∪ occupancy-bound patterns | probed > 5 ×3 (`:417`, `:480`, `:551`) | 185-line checklist (`:169-353`) |
| `ReportCsvSurfaceTest:153`, `PlanningThrottleParityTest:224` (Spring ctx) | `getHandlerMethods()` loops | handlers | not read | — |
| `MailThrottleCoverageTest` (Spring ctx) | `MailService.class.getDeclaredMethods()` public non-synthetic `send*` (`:283-287`) | mailers; `EnumSet.allOf(EmailType)` | `isNotEmpty` (`:293`) | checklist (`:113-188`) |
| `MailerAfterCommitCoverageTest` (plain) | **identical copy** of the mailer reflection (`:216-224`) + source scan with comment/literal blanking (`:226-287`, `:328-382`) | mailers + call sites | `isNotEmpty` ×3 (`:179-192`) | checklist (`:86-124`) |
| `WebBeanValidatedRuleTest` (plain) | `Files.walk` + `Class.forName(name,false,loader)` + nested classes (`:199-231`); 5 stereotypes via `AnnotatedElementUtils` (`:176-182`) | web beans | ≥ 30 (`:63`, javadoc says "32 today" — **34 now**, the count went stale as predicted), ≥ 10 `@Validated` (`:66`), > 100 sources (`:60`) | `WHY` (`:72-95`) |
| `ParameterConstraintSweepTest` (Spring ctx) | `Files.walk` (`:70`) + reflection over params carrying a `@Constraint`-meta annotation on 4 binding annotations (`:49-52`) | constrained params | ≥ 9 (`:80`), > 100 sources (`:83`) | not read |
| `AttachmentDoorsTest` (plain) | `Files.walk` + crude method-declaration regex (`:261-265`), `FileStorage` holders by regex (`:187-207`) | `fileStorage.store(` call sites; holders | `isNotEmpty` (`:117`), `containsExactly` (`:222`) | `WHAT_TO_DO` (`:74-109`) |
| `AuthMailDoorsTest` (plain) | **second copy** of the crude parser (`:331-335`) — `AttachmentDoorsTest:251-260` documents that this copy lacks the indentation lookahead that fixed a mis-attribution | callers of one method | `isNotEmpty` (`:365`) | text (`:254-277`) |
| `NotificationFinderSealTest` (plain) | `getMethods()` (`:96`), `getInterfaces()` (`:135`), source regex on the table name (`:163-193`) | finders on one repo; readers of one table | **none** | `CHECKLIST` |
| `UserEmailFinderSealTest` (plain) | `getDeclaredMethods()` (`:94`, `:177`) + source scan (`:146-156`) | finders on one repo | **none** | `CHECKLIST` |
| `MailSendEventRepositorySealTest` (plain) | `getMethods()` minus `Object` (`:348-352`), `ResolvableType` (`:117`) | methods on one repo | `isNotEmpty` (`:338-343`) | `WHY` |
| `VacuousVerificationRulesTest` (plain) | `PublishedCredentials.trackedFiles()` (`git ls-files`) + regex | test sources, scripts | 250/50/6/3/18/26 | `VacuousVerification.report` |
| `PagedSurfaceBoundTest` | hand-written rows | paged surfaces | 6 rows (`:66`) | text |

**Counts:** 31 test files carry a scan idiom (`Files.walk` / `getHandlerMethods` /
`Class.forName` / `trackedFiles` / `getDeclaredMethods`, grep over `src/test/java`); the web
surface alone is scanned privately by **7** files; **3** definitions of *write handler*
(source regex, 3 verbs · handler mapping, 4 verbs + unconditioned · handler mapping,
first-verb-or-GET); **2** of *request record* (reachable-from-`@RequestBody` recursion to
depth 4, `RequestFieldLengthBoundTest:535-561` · hard-coded file lists,
`ProseLengthBoundTest:83-88`, `SeedPasswordLimitTest:68-69`); **2** identical copies of
*mailer*; **2** of *controller param*; **≥ 4** comment strippers
(`RequestFieldLengthBoundTest:602-633`, `MailerAfterCommitCoverageTest:328-382`,
`VacuousVerification:158-227`, `LocaleIndependentFoldingTest`); floors come in **three
shapes** — numeric (6 files), `isNotEmpty` only (5), none (2).

### 4.2 Decisions

**D1 — enumeration: walk the compiled production tree, no Spring context, no library.**
Root = `HamstrackApplication.class.getProtectionDomain().getCodeSource().getLocation()`
(**inferred**: `target/classes` under Surefire — probe by printing it); walk `**/*.class`,
`Class.forName(name, false, loader)`; skip anonymous, synthetic, `package-info`,
`module-info`; nested classes come for free (`Outer$Inner.class`). **Measured premise:**
every production class already loads this way today — `WebBeanValidatedRuleTest:199-221`
does it on every run (optional deps such as `spring-boot-docker-compose` and the AWS SDK
are on the test classpath). **Measured:** no scanner library is declared —
`pom.xml:60-186` has no archunit/classgraph/reflections; test scope is
`data-jpa-test`, `security-test`, `webmvc-test`, `junit-platform-launcher` (`:160-185`).
Alternatives: *(a)* today's source walk + `Class.forName` — cwd-bound (each copy asserts
the root), reads 300+ files per scan, three copies; *(b)*
`ClassPathScanningCandidateComponentProvider` — drops interfaces and abstract classes
by default (`isCandidateComponent`), so repositories need an override, and it sees
`target/test-classes` unless filtered; *(c)* `git ls-files` + regex — right for source
facts (call sites), wrong for types (a regex cannot resolve a body type's components);
kept for the seals that stay source-level; *(d)* a library — a dependency for what one
directory walk does; HD-297 may bring ArchUnit, and `Doors` re-bases on `JavaClasses`
then without a consumer changing. Cost of D1: a stale `.class` for a deleted source is a
phantom door (over-reporting — the safe direction, but a false offender). Mitigation is
**D5**. A jar code source is **refused** with a message, not half-supported.

**D2 — handlers are selected with Spring's own utilities, without a context.**
`MethodIntrospector.selectMethods(beanType, m -> AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class))`
gives one merged `RequestMapping` per handler — `method()` and `path()` populated for
`@PostMapping`/`@GetMapping`/… through `@AliasFor` (**inferred** — first probe of the
build), and a method-level `@RequestMapping(method = …)` is covered by construction
(**measured**: none exist today; all 28 `@RequestMapping` are class-level path prefixes).
Paths are composed as class prefix × method path; **D6** proves the composition.

**D3 — `Population<T>` is the one carrier of a floor and a description.** Every
population method returns one; `floor(n)` throws the standard message (§4.5) and returns
the population, so a consumer writes `Doors.writeHandlers().floor(150)` and gets the same
sentence the harness's own test prints. `filter(why, predicate)` remembers the parent
size so a narrowed population still describes where it came from.

**D4 — floors: large populations ~85 % of today, populations of ≤ 5 members = today's
count.** A floor is anti-vacuity: it catches a broken scan or a narrowed predicate, not a
single deletion — a consumer that must notice one door leaving asserts the members
(`containsExactly`, `AttachmentDoorsTest:222`). For four filters or three mailers there is
no 85 %, and there a deleted door *is* worth a deliberate edit, so the floor sits on the
count and the message says how to lower it. Raise deliberately; never lower to pass.

**D5 — the harness proves its own scan.** `DoorsHarnessTest.compiledTreeMatchesTrackedSources`:
top-level compiled class names == `git ls-files src/main/java/**/*.java` mapped to FQCNs
(reuse `PublishedCredentials.trackedFiles()`/`repositoryPath()`, `:540-553`). A class only
on the compiled side is a stale `target/classes` (remedy `mvnw clean`); one only on the
source side is a compile the scan is not looking at.

**D6 — the runtime parity test is the "superset check", and it is an equality.**
`DoorsHandlerMappingParityTest` (`@SpringBootTest`): the set of
`(Handler.id(), verb, patternString)` from `Doors.handlers()` **equals** the set from
`RequestMappingHandlerMapping.getHandlerMethods()` restricted to handlers whose bean type
lives under the production code source (test-scoped probe controllers such as
`DeclaredConstraintRefusalTest`'s `/api/__test/**` are outside Doors by design). Both
directions: Doors may neither miss a handler Spring routes nor invent one it does not.
This is what lets the two migrated tests drop their scans *without weakening*: each
previously enumerated a subset of what the runtime routes, and the runtime is now
proven equal to Doors.

**D7 — `Handler.id()` is `SimpleName#method`.** Two spellings exist today
(`WorkspaceController#create` in `RequestFieldLengthBoundTest:186`, `WorkspaceController.create`
in `WriteThrottleCoverageTest:166`); `#` is the javadoc convention and the migration rewrites
the 16 method-level `EXEMPT` keys (`:166-182`). Type-level keys stay bare simple names.

### 4.3 Populations — predicate, today's count, floor, blind spots

Counts are **measured** by grep on 2026-09-08 (annotation at line start, javadoc mentions
excluded where identified); the builder re-measures by reflection and records both numbers
in the closing comment. Every method's javadoc carries a **`Not included:`** paragraph
with the blind spots listed here (AC 7 checks the marker is present).

| # | Method | Predicate over compiled production classes | Today | Floor |
|---|---|---|---|---|
| P0 | `productionClasses()` | D1 walk | not counted here — builder measures; provisional floor | 250 |
| P1 | `webBeans()` | `AnnotatedElementUtils.hasAnnotation` for `@Controller`, `@RestController`, `@ControllerAdvice`, `@RestControllerAdvice`, `@RequestMapping` (the `WebBeanValidatedRuleTest:176-182` predicate, moved) | **34** = 32 `@RestController` + `SpaController` (`@Controller`, `:11`) + `GlobalExceptionHandler` (`@RestControllerAdvice`, `:97`) | 30 |
| P2 | `handlers()` | D2 over P1 members; `Handler(bean, method, verbs, paths, restful)` | **282** | 250 |
| P2w | `writeHandlers()` | P2 with verbs ∩ {POST, PUT, PATCH, DELETE} ≠ ∅; a mapping with **no method condition counts as all four** (`WriteThrottleCoverageTest:302-308` rule) | **175** = 138 Post/Put/Patch + 37 Delete | 150 |
| P2r | `readHandlers()` | P2 with GET/HEAD/OPTIONS or unconditioned | **107** (2 are `SpaController` view forwards `:19,:28`; 1 returns `SseEmitter`) | 90 |
| P3 | `controllerParams()` | every `Parameter` of a P2 method, `Param(handler, parameter, binding, required)` with binding ∈ BODY/PART/QUERY/PATH/HEADER/COOKIE/MODEL/OTHER from the merged annotation; `MultipartFile` is QUERY today (`IssueController:290`) | **≈ 587 annotated**: 94 BODY (92 + 2 `required = false`, `SprintController:140`, `VersionController:113`), ≈ 110 QUERY (114 textual, 3 javadoc), ≈ 383 PATH (384, 1 javadoc); 0 HEADER/COOKIE/MODEL/PART | 500 (annotated) |
| P4 | `requestRecords()` | closure of record types reachable from every BODY/PART/MODEL param through `ResolvableType` (record components, arrays, all generic arguments, depth ≤ 6, cycle-guarded) **∪** every production record whose simple name ends in `Request` (`byNameOnly = true` when only the second half finds it) | **≥ 55**: 51 named `*Request` (50 mounted directly, `DeliveryRequest` via `CreateProjectRequest:40`/`UpdateProjectRequest:49`) + 4 companions not named `*Request` — `RolePermissionEntry` (`UpdateRoleRequest:50`, `PreviewRoleRequest:25`), `UpsertFieldSetRequest.Item` (`:20`), `UpsertPrioritySetRequest.Item` (`:21`), `UpsertWorkflowRequest.TransitionRule` (`:32`). **Primitive components today: 0** (both regexes over `**/dto/*.java`; the only primitives in records are response DTOs, e.g. `AdminFieldSetResponse.Item:11`, `AuthController.MeResponse:154-155`, `MetaController.MetaResponse:56-62`) | 45 |
| P5 | `repositories()` | interfaces assignable to `org.springframework.data.repository.Repository` | **46** (36 `JpaRepository`, 10 bare `Repository`) | 40 |
| P5t | `tables()` | `@Entity` classes → `@Table.name` (default naming if absent) | **41** (all carry `@Table`) | 35 |
| P5f | `repositoryFinders(table)` | `getMethods()` minus `Object` on every P5 member whose domain type (`ResolvableType.forClass(Repository.class, repo).getGeneric(0)`) maps to `table`; `Finder(repository, method, domainType, table, inherited)` | `issues` → 4 repositories (`IssueRepository`, `FlowReportRepository`, `CycleTimeReportRepository`, `AgingReportRepository`); `sprint_scope_events` → 3 | per call ≥ 1 |
| P6 | `scheduledJobs()` | methods carrying merged repeatable `@Scheduled` | **13** (`IssueRankService:276`, `ReportRateLimiter:74`, `PlanningRateLimiter:74`, `SearchRateLimiter:71`, `WriteRateLimiter:75`, `RateLimitService:160`, `UploadByteBudget:78`, `ExpensiveReadConcurrencyLimit:126`, `InviteSenderVolumeBudget:133`, `CspReportBudget:102`, `AnonymousMailConcentration:146`, `FailedEmailRetention:99`, `MailSendEventRetention:149`) | 10 |
| P7 | `servletFilters()` | concrete classes assignable to `jakarta.servlet.Filter` | **4**: `DatabaseBusyFilter:88`, `JwtAuthenticationFilter:17`, `AuthRateLimitFilter:26`, `CspReportGuardFilter:39` | 4 |
| P8 | `mailSendSites()` | public non-synthetic `send*` methods on every class that holds a `JavaMailSender` (declared field up the hierarchy, or a constructor/method parameter); `MailSite(holder, mailer)` | **3** on **1** holder (`MailService:47`; `:78`, `:91`, `:119`); the only `JavaMailSender` import in `src/main` is `MailService:13` | 3 |
| P9 | `problemJsonWriters()` | `@ExceptionHandler` methods on `@ControllerAdvice`-meta beans (kind ADVICE_HANDLER) ∪ pre-MVC response doors: P7 ∪ `AuthenticationEntryPoint` ∪ `AccessDeniedHandler` ∪ `HandlerInterceptor` implementations (kinds FILTER/ENTRY_POINT/ACCESS_DENIED/INTERCEPTOR) | **24** `@ExceptionHandler` methods on `GlobalExceptionHandler` (26 textual, 2 javadoc mentions at `:79`, `:682`) + **6** doors (4 filters, `JwtAuthenticationEntryPoint:16`, `PrincipalThrottleInterceptor:71`; 0 `AccessDeniedHandler`) | 20 / 5 |

**Blind spots per population — the `Not included:` javadoc content:**

- **P1/P2** — a handler on a class Spring never instantiates (a stereotype without a bean) is counted; a functional endpoint (`RouterFunction`) is not; a handler in `target/test-classes` is not. `SpaController`'s two view-returning handlers **are** in P2r (`restful = false`) — a consumer wanting the JSON API filters on `restful`.
- **P2w** — verbs come from the mapping, not from what the method does: a `GET` that writes is a read here, a `POST` that reads is a write.
- **P3** — un-annotated simple-type parameters are bound as request params by Spring and classified OTHER here (none today; `@AuthenticationPrincipal`, servlet types are OTHER too); a `@RequestParam boolean` without `defaultValue` would 500 on absence — **all 20 primitive request params today carry `defaultValue`** (measured; a rule for a later consumer, not this ticket).
- **P4** — a body type that is a class, not a record, is followed for its record-typed fields but is not itself a member; a type reachable only through `JsonNode` (`UpsertFieldRequest.config:46`) is opaque; Jackson polymorphic subtypes are invisible; a query object assembled in the controller from `@RequestParam`s (`CycleTimeQuery`, `FlowQuery` in `report.dto`) is not a request record; response records are excluded on purpose (they may carry primitives).
- **P5f** — finders are grouped by the repository's **domain type**; a `@Query` (JPQL or native) on a repository of entity A that reads table B is a finder of A here, not of B; reads outside Spring Data are invisible — **8 `EntityManager` holders today** (`InsightsService:221`, `SprintScopeLedger:130`, `WorkspaceStorageReconciler:121`, `SearchService:64`, `HqlCompiler:65`, `RecipientMailThrottle:211`, `StatementTimeout:73`, `LockTimeout:129`); no `JdbcTemplate`, no `JpaSpecificationExecutor`. `NotificationFinderSealTest:163-193` covers that hole for one table by source regex; that remains the tool for it.
- **P6** — enumerated by annotation, not by registration: a task registered through `SchedulingConfigurer` is invisible — **one exists**: `StorageReconcileSchedule:38,:54` (`registrar.addCronTask`); so is anything handed to a `TaskScheduler` or run under `@Async`.
- **P7** — classes, not registrations: whether a filter is enabled, on which URLs, in which order, or in the Security chain is not visible. Today: `DatabaseBusyFilter` `/*` at `HIGHEST_PRECEDENCE + 2` (`DatabaseBusyFilterConfig:31-36`); `AuthRateLimitFilter` six auth paths at `HIGHEST_PRECEDENCE` (`RateLimitConfig:15-26`); `CspReportGuardFilter` on `REPORT_PATH` (`CspReportSinkConfig:70-73`); `JwtAuthenticationFilter` registration **disabled** (`SecurityConfig:141-145`), mounted in the Security chain by `addFilterBefore` (`:108`). A `Filter` registered only in a test is outside the tree.
- **P8** — the `send*` naming convention is the handle: a mailer named `dispatch` is invisible (the holder floor still catches a second wrapper); callers of a mailer are a **source** fact and stay with `MailerAfterCommitCoverageTest`'s scanner (today: `AuthService:361`, `:485`, `WorkspaceService:545`, all inside `AfterCommit.run`); mail produced by a template engine that bypasses the holder is invisible by construction.
- **P9** — reflection sees *places that can write a response before MVC*, not whether they do or with which content type. Measured hand-written bodies today: `DatabaseBusyRefusal:217-228` (503, `APPLICATION_PROBLEM_JSON_VALUE`), `AuthRateLimitFilter:116-123` (429, `"application/problem+json"` literal), `JwtAuthenticationEntryPoint:23-26` (401, a `ProblemDetail` body under **`application/json`** — a drift; filed as a follow-up with category "pre-MVC writers of a ProblemDetail body" and seal "a future `ProblemJsonWriterContractTest` on `Doors.problemJsonWriters()`"), `CspReportGuardFilter:73,:81` (413/429, **no body**, deliberate, `:35-36`). A static helper that writes on a filter's behalf (`DatabaseBusyRefusal`) is reached through the filter, not listed itself.

### 4.4 API sketch

```java
package com.hamstrack.common.testsupport;

public final class Doors {                         // no Spring context; no instance state
    public static Population<Class<?>>        productionClasses();
    public static Population<Class<?>>        webBeans();
    public static Population<Handler>         handlers();
    public static Population<Handler>         writeHandlers();
    public static Population<Handler>         readHandlers();
    public static Population<Param>           controllerParams();
    public static Population<RequestRecord>   requestRecords();
    public static Population<Class<?>>        repositories();
    public static Population<Entity>          tables();
    public static Population<Finder>          repositoryFinders(String table);
    public static Population<Job>             scheduledJobs();
    public static Population<Class<? extends jakarta.servlet.Filter>> servletFilters();
    public static Population<MailSite>        mailSendSites();
    public static Population<ProblemWriter>   problemJsonWriters();

    public record Handler(Class<?> bean, Method method, Set<RequestMethod> verbs,
                          List<String> paths, boolean restful) {
        public String id()      { return bean.getSimpleName() + "#" + method.getName(); }
        public boolean writes() { /* verbs ∩ {POST,PUT,PATCH,DELETE} ≠ ∅, or verbs empty */ }
        public String describe(){ /* "POST,PATCH /api/workspaces/{workspaceId}/… (IssueController#create)" */ }
    }
    public enum Binding { BODY, PART, QUERY, PATH, HEADER, COOKIE, MODEL, OTHER }
    public record Param(Handler handler, Parameter parameter, Binding binding, boolean required) { … }
    public record RequestRecord(Class<?> type, Set<Handler> mountedBy, List<Class<?>> via, boolean byNameOnly) { … }
    public record Entity(Class<?> type, String table) { … }
    public record Finder(Class<?> repository, Method method, Class<?> domainType, String table, boolean inherited) { … }
    public record Job(Class<?> owner, Method method, String schedule) { … }
    public record MailSite(Class<?> holder, Method mailer) { … }
    public enum WriterKind { ADVICE_HANDLER, FILTER, ENTRY_POINT, ACCESS_DENIED, INTERCEPTOR }
    public record ProblemWriter(WriterKind kind, Class<?> owner, Method method /* null for a door */) { … }
}

public final class Population<T> implements Iterable<T> {
    public static <T> Population<T> of(String name, List<T> members);   // for probes and tests
    public String name();
    public List<T> members();                 // immutable, in a stable order (FQCN, then method name)
    public int size();
    public Stream<T> stream();
    public Population<T> floor(int n);        // AssertionError (§4.5) when size < n; returns this
    public Population<T> filter(String why, Predicate<? super T> keep);  // remembers parent size
    public Population<T> excluding(String why, Set<T> members);          // refuses a member that is not live
    public String describe();                 // "write handlers: 175 (floor 150)" / "… : 61 (filtered from 175 — POST/PUT/PATCH only; floor 130)"
}
```

Every member type has `describe()`; every list in a failure message is built from it.
Results are computed once per JVM and cached (`WebBeanValidatedRuleTest` re-walks per
test; the suite runs ~250 test classes and must not pay a class walk per consumer).

### 4.5 Failure messages (≤ 25 lines, naming the action)

**Floor** (`Population.floor`):

```
<name>: the scan saw <n>, under the floor of <F>.

Every consumer of this population asserts "nothing offends", so a scan that stopped
seeing members reports clean for ever. Exactly one of these is true:
  1. the scan is broken — target/classes is stale or empty (`mvnw clean compile`), or
     HamstrackApplication moved and the code-source root moved with it;
  2. a door really left — if that was deliberate, lower the floor in the SAME commit
     and name the door in that commit's message;
  3. the predicate narrowed — a renamed annotation, a moved package, a stereotype Doors
     does not recognise yet: fix Doors, never the floor.
Do not lower a floor to make a run pass. (<describe()>)
```

**Boxed fields** (`RequestRecordBoxedFieldsTest`):

```
A REQUEST RECORD CARRIES A PRIMITIVE COMPONENT (the HD-49 / HD-73 class).

  <Type>.<component> : <primitive>   (reached via <via chain> / by name)
  …

Jackson 3 (Boot 4) enables FAIL_ON_NULL_FOR_PRIMITIVES, so any JSON body that omits this
field — every partial PATCH, every client that sends only what changed — fails
deserialization before validation runs and answers 400 "Failed to read request" for the
whole body. UpdateIssueRequest's clear* flags did that to every issue update; the admin
set-upsert items did it again (HD-73).

Fix: box the type (Boolean / Integer / Long …) and coalesce in the compact constructor —
`this.flag = flag != null && flag;` — so the absent case binds to the default the
primitive would have had. Response records may keep primitives: this rule is about
what is READ from a body, and the population is every record reachable from a
@RequestBody plus every record named *Request.
```

**Parity** (`DoorsHandlerMappingParityTest`):

```
Doors and Spring disagree about the handler set.

  only Doors:   <id> <verb> <pattern> …
  only Spring:  <id> <verb> <pattern> …

Doors composes paths and verbs from the merged @RequestMapping by reflection so that
category tests need no context; RequestMappingHandlerMapping is what the
DispatcherServlet routes. Every consumer of writeHandlers()/readHandlers() inherits
whichever side is wrong — fix Doors' composition (or record the Spring behaviour it
missed in Doors' javadoc), never filter the difference away here.
```

**Compiled ⇄ tracked** (`DoorsHarnessTest`): `only compiled: <FQCN> — a stale class file;
run mvnw clean` / `only tracked: <path> — this class is not in the tree Doors scans`.

### 4.6 The three consumer tests

| Test | Deleted | Replaced by | Kept / changed |
|---|---|---|---|
| `RequestFieldLengthBoundTest` | `MAIN_SOURCES`, `WRITE_MAPPING`, `writeMappings` field + method, `scanWriteMappings`, `classNameOf`, `javaSources`, `stripComments`, the `files > 100` assertion (`:130-133`, `:161-162`, `:411-415`, `:492-517`, `:572-633`) | `Doors.writeHandlers().floor(150)`, keyed by `Handler.id()`; `acceptsFreeText`/`carriesText`/`requestBodyType` stay and take `Handler.method()` | rows unchanged; `MIN_ROWS` 45; free-text floor 80 stays; **scans all four verbs** (measured: no `DELETE` accepts a `String` param or a body today, so the change adds no uncovered handler — the builder confirms by the migrated run) |
| `WriteThrottleCoverageTest` | the `getHandlerMethods()` loop (`:238-263`); `mutatingMethods` (`:302-308`) | `Doors.writeHandlers().floor(150).filter("tenant API", h -> any path startsWith SCOPE)`; per handler × path × verb, `budgeted(verb, concrete(path))` unchanged — the interceptor chain is a runtime fact, so the class stays `@SpringBootTest`; `everyExemptionNamesALiveHandler` uses `Population.excluding` semantics over `Doors.handlers()` ids and bean simple names | `probed > 30` stays as the scoped tripwire; the 16 method-level `EXEMPT` keys move to `#` (D7); `WHAT_TO_DO` unchanged |
| `RequestRecordBoxedFieldsTest` (new, `common.validation`, plain JUnit) | — | `Doors.requestRecords().floor(45)`; offender = any `RecordComponent` whose `getType().isPrimitive()`; message §4.5 | **green on day one (0 offenders measured)** — so the negative control is mandatory (AC 4) |

### 4.7 The harness's own tests

- `DoorsHarnessTest` (plain): every population of §4.3 asserted at its floor on the real
  tree (this is the run that keeps the floors honest); D5 parity; `Population.of("probe",
  2 members).floor(3)` throws the §4.5 message; `filter` keeps the parent count in
  `describe()`; `excluding` refuses a member that is not live; one `[doors]` line per run
  with every `describe()` (the count witness, §13); every `public static Population` method
  in `Doors.java` has a javadoc containing `Not included:` (source read via
  `PublishedCredentials.read`). Both "every"s are equalities against the set reflection
  finds on `Doors`, not counts — a population added without a floor or without the marker
  is red by name. P4 carries an exact witness on top of its floor: the records reached at
  depth 0 by `requestRecords()`, and their `mountedBy`, equal the BODY/PART parameters whose
  raw type is a production record (a floor alone lets the closure drop a `*Request` record
  and re-find it by name).
- `DoorsHandlerMappingParityTest` (`@SpringBootTest`, the property set of
  `WriteThrottleCoverageTest:50-54` so it shares that cached context): D6 equality over
  `(id, verb, pattern)`; and per handler, `controllerParams()` count == the runtime
  `HandlerMethod.getMethodParameters().length`.

## 5. Edge cases & failure modes

- **Stale `target/classes`** (source deleted, class file remains): a phantom door; D5 names
  it. **Truncated compile**: the harness would under-count and the floors fire (D4) —
  independent of the HD-265 guard, which bounds test classes, not production ones.
- **Non-directory code source** (running from a jar): refused with the location printed.
- **A class that cannot load** (`NoClassDefFoundError` for an optional dependency): hard
  failure naming the class — a door nobody can load is a door nobody checks; today all
  load (`WebBeanValidatedRuleTest` precedent).
- **Inherited or interface-declared handlers** (none today): `MethodIntrospector`
  selects them exactly as Spring does; D6 proves it.
- **Multiple paths on one mapping**: one `Handler`, several `paths`; populations count
  handlers, probes count paths × verbs (the `probed > 30` tripwire stays over probes).
- **Bridge/synthetic methods, records' compact constructors, Lombok-generated members**:
  skipped/irrelevant — components come from `getRecordComponents()`.
- **Generic body types** (`List<UpsertFieldSetRequest.Item>`): `ResolvableType.forMethodParameter`
  (`MailSendEventRepositorySealTest:117` precedent); a self-referencing record is guarded
  by a visited set.
- **`repositoryFinders("typo")`**: an empty population; `floor(1)` fails with "no repository
  maps `<table>`; tables known: …" — consumers assert the table exists via `tables()` first.
- **Two repositories on one entity** (`Issue` ×4, `SprintScopeEvent` ×3): all returned,
  `Finder.repository` tells them apart.
- **A `@Scheduled` on a class Spring never registers**: counted (blind spot P6) — a dead
  job reads as a live one; the freshness-gauge contract (`backend-builder.md:28`) is the
  witness for that, not this harness.
- **Ordering**: members are sorted (FQCN, method name) so failure lists are stable across
  JVMs and diffs of the `[doors]` line are meaningful.
- **Windows paths**: class names derived from the walk with `/` and `\` both mapped to `.`
  (`WebBeanValidatedRuleTest:233-236` precedent).

## 6–9. Data model / API / Frontend / DC-Cloud

None / none / none / none — no env var, profile, property or compose change; nothing under
`src/main` changes. `migration-reviewer`, `api-docs-sync`, `dc-cloud-guard`,
`tenancy-reviewer` (no backend diff), `browser-qa`: n/a. `security-officer`: n/a beyond
noting the P9 drift is filed. `test-runner`: mandatory (every file in this diff is a test
or a test helper).

## 10. Acceptance criteria — over the category

1. **A door added after a category test was written is enumerated without editing the
   test.** Test shape: the builder plants, one at a time, (a) a `@PatchMapping` with a
   `@RequestBody` record carrying a `String` in a real controller → `RequestFieldLengthBoundTest`
   red naming it, test file untouched; (b) a `@DeleteMapping` under `/api/workspaces/…` on a
   non-exempt controller → `WriteThrottleCoverageTest` red; (c) `record ProbeRequest(int x)`
   anywhere under `com.hamstrack` → `RequestRecordBoxedFieldsTest` red (by-name half), then
   mounted on a body → still exactly one offender (reachability half). Each red line pasted,
   each plant reverted, green shown.
2. **Every population method has a floor, and a population below it is red.** Test shape:
   `DoorsHarnessTest` asserts all §4.3 floors on the real tree; negative controls — raise
   one floor above today's count → red with the §4.5 message; narrow `productionClasses()`
   to `com.hamstrack.auth` in a scratch edit → every dependent floor red; the
   `Population.of(...).floor(3)` probe is a permanent positive control.
3. **Doors equals the runtime.** Test shape: `DoorsHandlerMappingParityTest` equality in both
   directions over 282 handlers (verbs and patterns included) and the per-handler parameter
   count; negative control — comment out one verb in Doors' composition → "only Spring"
   red; add a fake handler to Doors' result → "only Doors" red.
4. **The two migrated tests and the new one are red on a planted violation, then green.**
   `RequestFieldLengthBoundTest`: remove `@Size(max = 500)` from `CreateIssueRequest.title`
   (`:15`) → 5xx row red; `WriteThrottleCoverageTest`: remove the `SprintController` entry
   from `EXEMPT` (`:190`) → red listing the sprint writes; `RequestRecordBoxedFieldsTest`:
   `RolePermissionEntry.ownOnly` → `boolean` (`:32`) → red naming
   `RolePermissionEntry.ownOnly`. Reverted, green, both lines in the closing comment.
5. **No migration weakens a scan.** Test shape: the migrated `RequestFieldLengthBoundTest`
   classifies ≥ 80 free-text handlers (its own floor, unchanged) and covers ≥ 130 write
   handlers in the POST/PUT/PATCH filter (unchanged floor) — plus AC 3, which is the proof
   that the population it now reads is the runtime's.
6. **The compiled tree the harness scans is the tracked source tree.** Test shape: D5 in
   `DoorsHarnessTest`; negative control — drop a stray `Probe.class` into
   `target/classes/com/hamstrack/` → red naming it.
7. **Every population states its blind spots.** Test shape: the `Not included:` marker
   assertion in `DoorsHarnessTest`; negative control — delete the marker from one method
   → red naming the method. (A presence check on a fixed marker, not a prose classifier.)
8. **HD-295 and HD-265 hold on the diff**: `VacuousVerificationRulesTest` green (no bare
   `assert` — `Population.floor` throws `AssertionError` explicitly; no skip); no helper
   named `*Test`/`Test*` (`Doors`, `Population`).
9. **Category block on the ticket**: rule = "a population is enumerated in one place, with
   a floor, and category tests consume it rather than scanning privately"; members = the
   populations of §4.3 and the scanner-carrying tests of §4.1 (those migrated or added in
   this change, and those named as still private); `sealedBy = DoorsHarnessTest` (floors,
   and the population-method set held equal to reflection) and
   `DoorsHandlerMappingParityTest` (equality with the runtime).

## 11. Open questions (recommended default) and the builder's first report

1. Four verbs in `RequestFieldLengthBoundTest`? **Yes** — the category is "a write door
   that accepts caller text"; measured delta today is zero.
2. `#` or `.` in `Handler.id()`? **`#`** (D7).
3. Jar code source? **Refuse** (D1).
4. `[doors]` line from every consumer? **No** — once per run, from `DoorsHarnessTest`.
5. Keep the by-name half of `requestRecords()`? **Yes** — a DTO written before its endpoint
   is still a request record; `byNameOnly` says which half found it.
6. Move `WebBeanValidatedRuleTest`/`ParameterConstraintSweepTest` onto `webBeans()`/
   `controllerParams()` in this ticket? **No** — same-shape follow-ups, one line each,
   outside the 3 points; named in the category block as unmigrated members.

**Highest-risk assumptions — measured first, reported before code:**
1. (**inferred**) `AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class)`
   returns merged `method()`/`path()` for `@PostMapping("/x")` — probe: one call on
   `IssueController#create`, output quoted.
2. (**inferred**) the code source of `HamstrackApplication` under Surefire is the
   `target/classes` directory — probe: print it in the first run.
3. (**inferred**) Doors' path composition equals Spring's `RequestMappingInfo` pattern
   strings for all 282 handlers — `SpaController`'s regex variables
   (`{path:^(?!assets$|actuator$)[^\\.]*}`, `:19,:28`) are the case most likely to differ;
   D6 decides, and a difference is recorded in Doors, not filtered.
4. (**inferred**) `MethodIntrospector.selectMethods` selects the same methods as
   `AbstractHandlerMethodMapping.detectHandlerMethods` — D6 decides.
5. (**measured, textual**) the counts in §4.3 — reflection may differ by a few; the floors
   carry the headroom, and both numbers go in the closing comment.

## 12. ADR — none

A test-support harness with no production footprint; reversible by deleting the harness
files and restoring the consumers' private scans.

## 13. Observability contract

A build-time artefact: its production is the CI build, and its failure modes are the
harness going stale while staying green.

| Failure mode | Witness | Drill |
|---|---|---|
| A population shrinks or a scan breaks | the §4.5 floor failure in `DoorsHarnessTest` (red build) | AC 2 — floor raised above today → red, dated in the ticket |
| Doors' predicate drifts from what Spring routes | `DoorsHandlerMappingParityTest` red | AC 3 — one verb commented out → red |
| The scan looks at a tree that is not the source | D5 red | AC 6 — stray class file → red |
| Counts drift quietly inside the floors | the `[doors]` line in the Surefire output of `DoorsHarnessTest` — one line, every `describe()`; the builder pastes the first CI line on the ticket (2026-09-08 counts of §4.3 are the baseline) | read back from the CI log of the first green run, dated |
| A new population is added without blind spots | the `Not included:` marker assertion | AC 7 |
| A consumer's negative control was never watched | the pipeline's `negativeControl` field (HD-303 checkpoint N2) | AC 1 and AC 4 lines pasted |

## Appendix — edits outside `src/test`

- `docs/project-state.md`: one bullet after `:576` in the shape of the HD-295 bullet —
  what `Doors` enumerates, the floors, the two harness tests, the three consumers, the
  blind-spot rule, the spec path.
- `.claude/agents/test-runner.md:27`: drop "where it exists".
- No edit to `CLAUDE.md:143` or `backend-builder.md:17` — both already name the harness;
  after this ticket they stop being aspirational.
- Follow-up to file (not in this diff): `JwtAuthenticationEntryPoint:24` writes a
  `ProblemDetail` under `application/json` — category "pre-MVC writers of a ProblemDetail
  body", members = `Doors.problemJsonWriters()` doors that write a body, seal = a
  `ProblemJsonWriterContractTest` on that population; labelled `deferred-from-gate`.
