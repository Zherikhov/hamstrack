# ArchUnit rules — HD-297 (epic HD-294, 5 SP)

> **Status: proposal.** Spec date **2026-09-09**. Labels: **M** = grep/read run this session, output quoted with
> file:line; **R** = read; **I** = inferred — the builder measures before code. No shell was available: nothing here
> was executed; every "today" count is a grep and is re-measured by the first run of the class itself.
> Backlog search: repo grep `archunit|ArchUnit|ArchitectureRulesTest` (2026-09-09) — no rule class exists; related
> **HD-295** (R7 home, `VacuousVerificationRulesTest:55-58` defers to this ticket), **HD-296** (`Doors`,
> `RequestRecordBoxedFieldsTest`), **HD-214/HD-239** (`WebBeanValidatedRuleTest`), **HD-120**
> (`LocaleIndependentFoldingTest`), **HD-265** (suite guard), **HD-164** (assert conversion).

## 1. Problem & goal
Seven CLAUDE.md gotchas have a mechanical form and are held today by prose, by four unrelated scanners of three
different shapes (regex, `Files.walk`+reflection, `Doors`), or by nothing. Goal: each gotcha is executed by **exactly
one** rule whose failure text is the gotcha, seen red on a plant; the tree is clean on day one; the doc points at the rule.

## 2. Scope / non-goals
**In:** one test dependency; `ArchitectureRulesTest` (R2–R6); fixes at 8 production sites; deletion of two scanners;
pointer edits in CLAUDE.md, `backend-builder.md`, ADR-0017/0018, one `project-state.md` line.
**Out:** `FreezingArchRule` (an allow-list without a reason); rules over `src/test` bytecode (R7 stays regex);
package-dependency/layering rules (wave 2 candidates, not this ticket); ESLint. **N/a:** actors/permissions, data
model, API, frontend, DC/Cloud — a build-time rule; the only profile question is none.

## 3. Premises the ticket states that measurement corrected
- **P1 — `archunit-junit5` is the wrong artifact for this stack.** M: Boot 4.1.0 BOM pins
  `<junit-jupiter.version>6.0.3</junit-jupiter.version>` (`spring-boot-dependencies-4.1.0.pom:121`, `junit-bom` at
  `:3394-3395`); `pom.xml` overrides nothing (`:181-185` declares only the managed launcher). ArchUnit 1.5.0 (latest,
  GitHub releases fetched 2026-09-09) added `archunit-junit6` because `archunit-junit5` "cannot be used in environments
  which adopt JUnit 6.x" (TNG/ArchUnit#1556). Decision in D1.
- **P2 — rule 2 already has a home.** R: `LocaleIndependentFoldingTest.java:43-49` (HD-120) regex-scans
  `src/main/java` for the zero-arg overloads with comment/literal blanking, floor > 100 files. Same one-home question
  as rules 1/3/7 — decided in §4.
- **P3 — `WebBeanValidatedRuleTest` has no regex half.** R: it is `Files.walk` + `Class.forName` reflection
  (`:199-245`) with two tests: the rule (`:101-120`) and the permitted-category + detector floor (`:134-166`).
- **P4 — today's violations: 8, all in R5 and R6** (table §4). R1–R4, R7: 0.

## 4. The seven rules — home, today, day-one action, mechanism, plant
| # | Rule | Home (decided) | Today (M) | Day-one action | ArchUnit mechanism (I unless noted) | Plant → expected red (first line) |
|---|---|---|---|---|---|---|
| R1 | no primitive component in a request record | **`RequestRecordBoxedFieldsTest`** (R `:46-59`): population `Doors.requestRecords().floor(45)` = every record reachable from a `@RequestBody` **plus** every `*Request` — wider than the ticket's `**/dto/*Request*` name filter; negative control recorded on HD-296 | 0 (grep primitives in `**/dto/*Request*.java`: 0 code hits) | none; pointer only | not duplicated | recorded (HD-296: `RolePermissionEntry.ownOnly` → `boolean`) |
| R2 | no `toLowerCase()`/`toUpperCase()` zero-arg | **ArchUnit**; `LocaleIndependentFoldingTest` **deleted** same commit | 0 in main (grep `\.to(Lower|Upper)Case\(\)`: only comment `InsightsRequest.java:43`; 70+ hits in `src/test` → excluded by `DoNotIncludeTests`); 0 method refs `String::to*Case` | none | shared `ArchCondition<JavaClass>` `usesZeroArg(String.class, names)` over `getMethodCallsFromSelf()` ∪ `getMethodReferencesFromSelf()`, target owner `java.lang.String`, `getRawParameterTypes()` empty. "Non-constant operands" → **forbid all zero-arg uses**; a site that must follow the JVM default writes `toLowerCase(Locale.getDefault())` (1-arg overload passes — keeps the HD-120 remedy) | `SearchNames.java:77` `.toLowerCase()` → `Method <com.hamstrack.search.SearchNames.key(java.lang.String)> calls method <java.lang.String.toLowerCase()> in (SearchNames.java:77)` |
| R3 | no `@Validated` on a bean MVC dispatches to (ADR-0018) | **ArchUnit**; `WebBeanValidatedRuleTest` **deleted whole** (both tests re-homed) | 0 (grep `@Validated` in main: 18 `^@Validated`, all `common.config.*Properties`) | none | population = names of `Doors.webBeans().floor(30)` (**one** definition of "web bean", R `Doors.java:318-329`), `noClasses().that(inWebBeans).should().beAnnotatedWith(Validated.class).orShould().beMetaAnnotatedWith(Validated.class)`; second rule `classes().that().areAnnotatedWith(Validated.class).should().beAnnotatedWith(ConfigurationProperties.class)` with floor ≥ 10 (18 today) | `@Validated` on `common/web/MetaController.java` → `Class <com.hamstrack.common.web.MetaController> is annotated with @Validated` |
| R4 | no `@CreationTimestamp` / `@UpdateTimestamp` / `@GeneratedValue` | **ArchUnit** | 0 (grep: no matches in main) | none | `members().should().notBeAnnotatedWith(CreationTimestamp.class).andShould().notBeAnnotatedWith(UpdateTimestamp.class).andShould().notBeAnnotatedWith(GeneratedValue.class)` — `members()` covers property-access getters too | `@CreationTimestamp` on `BaseEntity.createdAt` → `Field <com.hamstrack.common.entity.BaseEntity.createdAt> is annotated with @CreationTimestamp` |
| R5 | `@Modifying(clearAutomatically = true)` ⇒ `flushAutomatically = true` | **ArchUnit** | **4**: `UserRepository.java:157` (`claimDemoSeed`), `IssueRepository.java:78,82,86` (`remapStatus/Priority/Type`); 6 already carry both (`VersionRepository:117,135`, `SprintRepository:223,242`, `IssueRepository:213,836`), `SprintRepository:270` flush-only | **fix all four** by adding `flushAutomatically = true`: for the remaps strictly safer (a dirty `Issue` loaded earlier is written before the clear instead of discarded; the clear's purpose at `AdminCatalogService.java:58-63` is unchanged); for `claimDemoSeed` a no-op (CLAUDE.md:53 names it as the nothing-pending case). Allow-list **empty**; format fixed in D4, code deferred until the first entry | `methods().that().areAnnotatedWith(Modifying.class).should(flushWhenClearing)` — condition reads `method.getAnnotationOfType(Modifying.class)` (proxy, defaults applied — **I**; `JavaAnnotation.get("clearAutomatically")` may omit defaults); floor ≥ 30 annotated methods (59 `@Modifying` tokens incl. comments in 23 files — builder measures) | remove `flushAutomatically` from `remapStatus` → `Method <com.hamstrack.issue.repository.IssueRepository.remapStatus(…)> is @Modifying(clearAutomatically = true) without flushAutomatically = true` (abstract methods print `:0` — I) |
| R6 | no JDK trimmer under `com.hamstrack.search..` | **ArchUnit** | **4** `.trim(`: `HqlValueResolver.java:288` (`new BigDecimal(raw.trim())`), `SearchService.java:337,361` (`q.trim().toLowerCase(Locale.ROOT)`), `SavedFilterService.java:109` (`req.name().trim()` in `update`; `create:82-89` stores `req.name()` **raw** — the two name doors disagree). `.strip*(`: 0 (javadoc `HqlParentResolver.java:25`) | 288 → `SearchNames.canonical(raw)`; 337/361 → `SearchNames.key(q)` (`null` → `""`, ternary goes); saved filter: one private `canonicalName()` = `ClassificationNames.normalize` (≡ `SearchNames.canonical`, R `SearchNames.java:87-89`) in **both** `create` and `update`; update's blank check stays, create keeps `@NotBlank` (`CreateSavedFilterRequest.java:19`) | same shared condition, names `trim, strip, stripLeading, stripTrailing` (the family, not one spelling), scoped `noClasses().that().resideInAPackage("com.hamstrack.search..")`; floor ≥ 35 classes (48 files) | `SearchNames.canonical` → `.trim()` → `Method <com.hamstrack.search.SearchNames.canonical(java.lang.String)> calls method <java.lang.String.trim()> in (SearchNames.java:88)` |
| R7 | no bare `assert` under `src/test` | **`VacuousVerificationRulesTest#noBackendTestLeansOnABareAssert`** (R `:174`; P1 floor 250 over `git ls-files`, fixture positive control) | 0 (grep `^\s+assert\s+[^=(]` over `src/test/java`) | none | not duplicated: would need a second import (`OnlyIncludeTests`, ~300 classes) and would split R2 from R1/R3, which cannot move (TS files, `package.json`) | recorded (HD-295 AC-4) |

## 5. Decisions
- **D1 — dependency: `com.tngtech.archunit:archunit:1.5.0`, `<scope>test</scope>`**, no version property. Core
  only: no engine, no JUnit dependency, so the JUnit-6 question (P1) does not arise. Rules are Jupiter `@Test` methods
  calling `RULE.check(MAIN)` — the same discovery path the HD-265 recorder keys on (R `ExecutedTestClassRecorder.java:82-85`:
  `ClassSource`/`MethodSource`), and `-Dtest=ArchitectureRulesTest#method` works. If the owner wants `@ArchTest`, the
  artifact is `archunit-junit6:1.5.0`, never `-junit5`. `pom.xml` is in the ops area → **arms `ops_witness`**.
- **D2 — class:** `src/test/java/com/hamstrack/common/architecture/ArchitectureRulesTest.java` (new package; the
  `testsupport` guards are about the test corpus, this one is about production code). One `private static final
  JavaClasses MAIN = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.hamstrack")`
  — once per JVM (`pom.xml:401`: forkCount 1). First test: `MAIN.size() > 450` (M: 573 `.java` files; nested classes
  push the count higher). One `@Test` per rule; the rule's `because(...)` is ≤ 8 lines and names the action; history
  lives in the javadoc on the rule constant (`backend-builder.md:51`). The shared zero-arg condition is written once
  and parameterised (R2 and R6) — a copy is a defect.
- **D3 — floors before rules**, each an AssertJ assertion in the same method: import > 450; web beans ≥ 30 (Doors);
  `@Validated` classes ≥ 10; `@Modifying` methods ≥ 30; classes in `..search..` ≥ 35. "Found nothing" and "looked
  at nothing" print the same green line.
- **D4 — allow-list format (for the day it is needed):** `Map<String,String> ALLOWED = Map.of("<FQCN>#<member>",
  "HD-nnn: <reason>")`, applied as one `DescribedPredicate` in `that(...)`, with a tripwire that every key resolves
  to an existing member and every value matches `HD-\d+`. Not implemented while empty — dead code teaches nothing.
- **D5 — failure bound ≤ 25 lines:** ArchUnit prints header + `because` + one line per violation; with a clean tree a
  plant prints ≈ 12 lines. That bound holds only while the tree is kept clean — hence "fix today's", not freeze.
- **D6 — retirements (every copy, one change):** delete `WebBeanValidatedRuleTest`, `LocaleIndependentFoldingTest`;
  re-point the mentions — `CLAUDE.md:69`, `docs/adr/0018:6` ("held by …"), `ParameterConstraintSweepTest.java:173`
  (message), `DeclaredConstraintRefusalTest.java:61` (`{@link}`), `Doors.java:321` (history — reword),
  `docs/adr/0017:181` (list of existing tests), `VacuousVerification.java:162`, `EmailLengthBoundTest.java:309`.
  `docs/design/*` proposals are records and stay (HD-295 precedent).
- **D7 — pointers:** append ` ⟶ test: <Class>#<method>` to CLAUDE.md § Gotchas lines **46** (R4), **53** (R5),
  **62** (R1 → `RequestRecordBoxedFieldsTest#noRequestRecordCarriesAPrimitiveComponent`), **69** (R3; also replace
  the two sentences naming `WebBeanValidatedRuleTest` and "a category test over every web bean"). R2/R6/R7 have no
  § Gotchas line (M) — their pointers go on `backend-builder.md:41` (R2, R6) and `test-runner.md` already names R7's
  class. `backend-builder.md:30` heading → "held by `ArchitectureRulesTest` (R2–R6), `RequestRecordBoxedFieldsTest`
  (R1), `VacuousVerificationRulesTest` (R7)"; bullets `:31-32` (R4), `:37` (R5), `:39` (R1), `:40` (R3), `:41` get the
  same pointer. **Same change, both files:** `AgentChecklistFreshnessTest` blames each § Gotchas line against the
  newest `.claude/agents/` edit (R `:43-44`) and dates an uncommitted line on either side to now (R `:516`).
- **D8 — no ADR.** A test dependency and a test class are reversible in one commit; the hard decision (ELv2, ADR-0018)
  already has its record.

## 6. Category (for `run.json`)
`{"rule": "every CLAUDE.md gotcha with a mechanical form is executed by exactly one rule carrying the gotcha as its reason",
"members": ["R1 RequestRecordBoxedFieldsTest", "R2 ArchitectureRulesTest#noDefaultLocaleCaseFold",
"R3 ArchitectureRulesTest#noWebBeanCarriesValidated (+ #validatedOnlyOnConfigurationProperties)",
"R4 ArchitectureRulesTest#noHibernateTimestampOrGeneratedValue", "R5 ArchitectureRulesTest#clearAutomaticallyFlushes",
"R6 ArchitectureRulesTest#noJdkTrimmerInSearch", "R7 VacuousVerificationRulesTest#noBackendTestLeansOnABareAssert"],
"sealedBy": "ArchitectureRulesTest"}` — second category, the retired-name mentions, is the D6 list.

## 7. Acceptance criteria (over the category)
- **AC1 one home each.** After the change, grep for `WebBeanValidatedRuleTest|LocaleIndependentFoldingTest` returns
  only `docs/design/` lines; no rule in §4 has two executing scanners. Shape: grep quoted in the report.
- **AC2 red on a plant, per ArchUnit rule (R2–R6, and R3's second rule).** For each: the plant from §4, the first
  ≤ 25 lines of the Surefire failure pasted (must contain the `because` text and the offending member), revert, green.
  R1/R7 cite their recorded reds — no code of theirs changes. The R2 plant additionally covers a method reference
  (`.map(String::toLowerCase)`) so the union in the condition is proven, not assumed.
- **AC3 clean tree by fixing, not narrowing.** The four R5 and four R6 sites are changed as §4 says; no rule's scope
  or floor is narrowed to fit today's code. Holding tests green: `AdminCatalogDeleteWithRemapTest`,
  `WorkspaceCreationTest`, demo-seed tests, `SearchApiTest` (suggest), `SavedFilterApiTest` **plus one new case**: a
  filter created as `"Foo   bar "` reads back `"Foo bar"` and an update to `"  Foo bar"` is a no-op rename.
- **AC4 floors.** Each of D3's five floors is asserted before its rule; the drill `importPackages("com.hamstrack.nope")`
  turns the class red at the import floor, not green.
- **AC5 pointers.** Every CLAUDE.md § Gotchas line whose rule has a home ends with a `⟶ test:` pointer (4); every
  `backend-builder.md` § Mandatory patterns bullet with a rule does (6); `AgentChecklistFreshnessTest` is green in the same run.
- **AC6 suite guard.** The unfiltered `mvnw test -Dfrontend.skip=true` report lists `ArchitectureRulesTest` as executed
  and the class total equals previous + 1 − 2; the report quotes both numbers.

## 8. Observability contract (build-time rules have a build-time witness)
| Failure mode | Witness | Drill |
|---|---|---|
| A member breaks a rule | Surefire failure `ArchitectureRulesTest.<rule>`, `because` text within the first 25 lines | AC2 plants |
| Import sees nothing / a `that()` selects nothing | floor assertion red, message names the population and the floor | AC4 |
| The class is not executed at all | HD-265 `[test-tree]` report names it; build fails | proven by HD-265; AC6 reads it back |
| Allow-list key rots (renamed member) | D4 resolve tripwire (when the list exists) | plant a key naming a non-member |
| A `⟶ test:` pointer rots | **none today** — Q5 | — |

## 9. Open questions (recommended default)
- **Q1** core `archunit` (default) vs `archunit-junit6` with `@ArchTest` — owner accepted "the dependency", not the engine.
- **Q2** move R2 and delete `LocaleIndependentFoldingTest` (default yes: one home, bytecode needs no stripper, sees method refs).
- **Q3** R5: fix all four (default) vs allow-list `claimDemoSeed` as the CLAUDE.md-named exception.
- **Q4** canonicalise saved-filter names on **create** too (default yes — behaviour change: NFC + whitespace collapse, same as labels).
- **Q5** add `everyTestPointerResolves` (parse `⟶ test: (\w+)#(\w+)` in CLAUDE.md and `.claude/agents/*.md`, resolve
  class file + method name under `src/test/java`) — default yes, ~15 lines in `ArchitectureRulesTest`.
- **Q6** package `common.architecture` (default) vs `common.testsupport`.

## 10. Builder's first report — highest-risk assumptions, in order
1. ArchUnit 1.5.0 core imports Java 21 class files and `MAIN.size()` (print it; set floors at ~80 %).
2. `getMethodReferencesFromSelf()` exists on `JavaClass` in 1.5.0 and reports `String::toLowerCase` (the AC2 method-ref plant).
3. `getAnnotationOfType(Modifying.class)` yields defaults (probe: a plain `@Modifying` reads `clearAutomatically() == false`).
4. Adding `flushAutomatically = true` to the four R5 sites leaves the named holding tests green (run them first, alone, then in the full suite).
5. The R6 saved-filter change: `SavedFilterApiTest` outcomes and the new AC3 case.
6. The unfiltered run's class total and the `[test-tree]` line (AC6) — the only proof the new class is counted.
