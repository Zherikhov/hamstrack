# The vitest suite-ran guard — HD-301 (epic HD-294, last child)

> **Status: proposal.** Spec date **2026-09-11**. Evidence labels: **M** = measured this session (a tool was run and
> its output is quoted, with `file:line` or a glob count); **R** = read (a file says so, and I did not execute it);
> **I** = inferred — a hypothesis the builder checks *before* writing code.
>
> **No shell was available in this session.** Every count below comes from `Glob`/`Grep` over the working tree or from
> reading a file in `node_modules`; **nothing was executed**. So there is no timing figure, no `vitest run` output and
> no test count in this document that was produced by running the suite — §3 P4 says exactly which number is
> therefore still unverified, and §13 makes producing it the builder's first act.
>
> **Backlog search (recorded, per CLAUDE.md).** `Grep -i "vitest|HD-301"` over the tree excluding
> `node_modules` — 103 files, of which the only *design* documents are `docs/design/eslint-rule-set-proposal.md`
> (HD-300), `docs/design/vacuous-verification-rules-proposal.md` (HD-295), `docs/design/shadowed-field-key-proposal.md`
> and `docs/design/command-palette-proposal.md`; **no proposal for a vitest coverage/suite-ran guard exists**, and
> `docs/design/vitest-suite-ran-guard-proposal.md` did not exist before this file. **No match; no duplicate.**
> Related: **HD-265** (the JVM half this is the sibling of), **HD-242** (`npm-test` wiring), **HD-295**
> (`VacuousVerificationRulesTest` — already pins vitest's `include` line and refuses an unticketed `it.skip`),
> **HD-300** (`lint.mjs` / `lint.debt.mjs` / `src/lint/debt.test.ts` — the guard-program shape), **HD-296** (`Doors` /
> `Population`, the floor message), **HD-240** (`marginReporter.ts`, the precedent for a standing vitest reporter),
> **HD-94** (`-Dfrontend.skip`), **HD-303** (pipeline history).

---

## 1. Problem & goal

**Problem.** The backend half of "an unfiltered green run is not evidence unless the count was checked" is enforced
(HD-265: the antrun `test-tree-coverage-guard` execution, `pom.xml:620-662`, R). The **frontend half is not enforced at
all**. What exists on the vitest side today is:

- a count printed by vitest (`Test Files … / Tests …`) that `.github/workflows/build.yml:119-125` explicitly asks a
  human to *read* (R);
- one property that makes an *empty* match red — `vitest run` exits 1 with `No test files found`, and
  `passWithNoTests` appears nowhere in the repo (R `pom.xml:411-427`; M — `Grep "passWithNoTests"` over
  `src/main/frontend` excluding `node_modules` returns exactly one hit, a *comment* in `lint.mjs:9`).

Neither covers the failure this ticket is about: a run that matches **some** files, prints a confident green line, and
says nothing about the rest. And one case is worse on the frontend than on the backend: because the whole suite hangs
off a **single Maven execution** (`npm-test`), the frontend suite can go from 73 files to *zero* by one edit that
deletes an execution from `pom.xml` — and the only thing that would notice today is `src/lint/debt.test.ts`, which
itself only runs inside that execution.

**Goal.** For the frontend suite, the same guarantee HD-265 gives the backend, from the same two halves and with the
same honesty about being switched off: a **reporter records** the test files this run actually executed, a
**Maven-bound step refuses** a run whose executed set is smaller than the tree, and both counts (JVM classes, vitest
files and tests) reach a reader — locally on stdout and in CI in the job summary — **without arithmetic, on red runs
as well as green ones**.

---

## 2. Scope / non-goals

**In.** One vitest reporter (`src/main/frontend/src/test/suiteRecorder.ts`) and its unit test; a second arm inside the
existing `test-tree-coverage-guard` antrun execution, whose decision is a pure function in a new
`com.hamstrack.common.testsupport.SuiteCoverage`; extensions to `SuiteRunRecord` (a second file-name prefix) and to
`SuiteCoverageGuardTest` (fixtures for every refusal); `reporters` and `environmentVariables` wiring in
`vitest.config.ts` and `pom.xml`; a new "gate is reachable from the build" block sealing the wiring from the vitest
side; one `if: always()` step in `.github/workflows/build.yml` that appends the summary; pointer edits in `CLAUDE.md`
(§ Gotchas one-liner), `.claude/agents/test-runner.md`, and `docs/project-state.md`.

**Out.**
- **Any vitest upgrade.** The installed version is **3.2.7** (M — `node_modules/vitest/package.json:4`), which is in
  the range an open `@vitest/mocker` path-traversal advisory covers; HD-300 answered that advisory by *bounding
  `server.fs.allow`* (`vitest.config.ts:36`, R), not by upgrading, and this ticket must not reopen it. Everything
  below uses APIs present in 3.2.7 and read from the installed `.d.ts`.
- Coverage instrumentation (`@vitest/coverage-*`). This guard is about *which files ran*, not which lines.
- A skipped-test rule — `VacuousVerificationRulesTest#noFrontendTestSkipsWithoutATicket` already owns that (R
  `VacuousVerificationRulesTest.java:136-149`), and this guard deliberately counts a module containing only skipped
  tests as **present**, exactly as the JVM recorder counts a `@Disabled` class as present (R
  `ExecutedTestClassRecorder.java:42-45`).
- Reproducing or fixing HD-265's underlying truncation. Still open; this is a detector, like its sibling.
- Any change to application code, migrations or runtime config.

**N/a.** Actors & permissions, data model, API surface, frontend *product* impact — this is a build-time gate with no
runtime behaviour. DC/Cloud: §11.

---

## 3. Premises, measured

**P1 — the vitest configuration today (M, `vitest.config.ts`).**
`include: ['src/**/*.{test,spec}.{ts,tsx}']` (`:41`). **No `exclude`** — so vitest's `defaultExclude` applies. **No
`passWithNoTests`** anywhere. `reporters: ['default', './src/test/marginReporter.ts']` (`:87`) — so a project-local
`.ts` reporter is an established, working shape here. `server.fs.allow: ['./', '../../../pom.xml', '../../../pom.xml?raw']`
(`:36`) — the SPA test run may read `pom.xml`, and only `pom.xml`, from outside the Vite root.

`defaultInclude`/`defaultExclude` in the installed 3.2.7 (M, `node_modules/vitest/dist/chunks/defaults.B7q_naMc.js:5-12`):

```
defaultInclude = ["**/*.{test,spec}.?(c|m)[jt]s?(x)"]
defaultExclude = ["**/node_modules/**", "**/dist/**", "**/cypress/**",
                  "**/.{idea,git,cache,output,temp}/**",
                  "**/{karma,rollup,webpack,vite,vitest,jest,ava,babel,nyc,cypress,tsup,build,eslint,prettier}.config.*"]
```

Two consequences the bound in §5 is built on: the configured `include` is **narrower than vitest's own default** in
two independent ways — it is rooted at `src/`, and it accepts only `.ts`/`.tsx` (a `*.test.js`, `*.test.mts` or
`*.test.cjs` anywhere in the SPA is silently not a test today); and `node_modules` is excluded by a *default*, not by
anything this repo wrote.

**P2 — how the suite is invoked (M).** `package.json:13` — `"test": "vitest run"` (no flags, no filter, no
`--reporter`, no `--config`). `pom.xml:439-444` — execution `npm-test`, goal `npm`, phase `test`,
`<arguments>run test</arguments>`. Plugin-level `<skip>${frontend.skip}</skip>` at `pom.xml:217` covers **every**
execution of `frontend-maven-plugin`.

**P3 — the population of test files (M, `Glob`, 2026-09-11).**

| glob | count |
|---|---|
| `src/main/frontend/src/**/*.{test,spec}.{ts,tsx}` (= the configured `include`) | **73** |
| the same shape anywhere under `src/main/frontend/` **outside** `src/` and outside `node_modules` | **0** |
| `src/main/frontend/node_modules/**/*.{test,spec}.{ts,tsx}` | **155** |

The 73 confirms HD-300's file count. The **155** is the number that kills the ticket's own phrasing: a bound derived
from "`**/*.test.ts*` in the tree" would demand that vitest run `zod`'s and `entities`' test suites and would be red
on every build. The bound must be an *exclusion-aware* population (§5).

**P4 — the test count is NOT verified (I).** HD-300 records 73 files / **1218 tests** on 2026-09-11. The 73 is
confirmed above (M); **1218 was not** — no suite was run in this session. The builder measures it in round 1 and uses
the measured number for the floor in §5.4.

**P5 — the reporter API in 3.2.7 (M, read from the installed
`node_modules/vitest/dist/chunks/reporters.d.BuRON0I0.d.ts:557-614`).**

```ts
interface Reporter {
  onInit?: (vitest: Vitest) => void
  /** @deprecated use `onTestRunEnd` instead */
  onFinished?: (files: File[], errors: unknown[], coverage?: unknown) => Awaitable<void>
  onTestRunStart?: (specifications: ReadonlyArray<TestSpecification>) => Awaitable<void>
  onTestRunEnd?: (testModules: ReadonlyArray<TestModule>,
                  unhandledErrors: ReadonlyArray<SerializedError>,
                  reason: TestRunEndReason) => Awaitable<void>   // "passed" | "interrupted" | "failed"
  onTestModuleEnd?: (testModule: TestModule) => Awaitable<void>
}
```

So: **`onTestModuleEnd` + `onTestRunEnd` are the API** — not `onFinished`, which is deprecated in this exact version
and is what `marginReporter.ts:81` still uses (R; leave it alone, it is a different ticket). `--reporter=json` is
rejected in §4: it replaces the reporter list rather than adding to it, it is a CLI flag on a command this repo pins
by text, and a JSON file is a second artefact to keep fresh.

**P6 — the population source already used by the repo for exactly this question (R).**
`VacuousVerificationRulesTest` derives its frontend population from `PublishedCredentials.trackedFiles()` — `git
ls-files`, "read from the index, not from the working tree" (`PublishedCredentials.java:535-548`), with
`publishableFiles(pathspec)` = `--cached --others --exclude-standard` for "this checkout and the compile it came
from" (`:550-561`). `PublishedCredentials` imports **only the JDK** (M, `:1-13`), so it loads in the guard's forked
JVM, whose classpath is `target/test-classes` and nothing else. `.gitignore:45` is `src/main/frontend/node_modules/`
(M) — so a git-derived population excludes the 155 by construction rather than by a hand-maintained list.

**P7 — what already refuses part of this (R), so the new guard is not a second copy.**
`VacuousVerificationRulesTest:85-93,156-170` pins vitest's include **as a whole line**, one element, that one
(`VITEST_INCLUDE = "src/**/*.{test,spec}.{ts,tsx}"`), with a failure message that explains why a `contains` check
would be green while the population moved. `FRONTEND_FLOOR = 50` (`:77`) floors the scan. Those cover *narrowing the
include* and *a collapsed scan* from the Java side; they do **not** cover an `exclude`, `passWithNoTests`, an
unregistered reporter, a deleted execution, or a file outside `include`.

**P8 — execution ordering (R, and the builder confirms).** `pom.xml:615-618` states that within the `test` phase the
default-lifecycle binding (surefire) runs first and POM-declared executions then run in **declaration order**, "the
same ordering the frontend `npm-test` execution above depends on, verified in the build log for both".
`frontend-maven-plugin` is declared at `pom.xml:202`, `maven-antrun-plugin` at `:620`. So the antrun guard runs after
`npm-test`. HD-300 already seals a sibling of this claim by comparing declaration offsets in text
(`src/lint/debt.test.ts:317-332`, R) — §8 adds the third offset.

**P9 — CI writes no job summary for the test job (M, `.github/workflows/build.yml`).** The only
`$GITHUB_STEP_SUMMARY` write in the whole workflow is `:261`, in the **`build-and-push`** job. `build-and-test` has
four steps and writes nothing. So "the CI job summary prints both counts" is a **new step**, not an edit.

---

## 4. The design question: where the comparison runs, and why not the other two places

The ticket names three honest options. They differ on exactly one question — **what happens when the suite is not run
at all** — and that is the failure the guard exists for.

| option | answer to "the suite did not run" | verdict |
|---|---|---|
| **a reporter that fails the run itself** | *silence.* A reporter is code inside the run; if the run does not happen, neither does the reporter. Identical in shape to "a guard written as a test class is selected by the mechanism that failed" (R `SuiteCoverageGuard.java:40-48`). | **rejected as the refusal**; kept as the *recorder* |
| **a program in `lint.mjs`'s family, run as a `frontend-maven-plugin` execution** | *silence.* `<skip>${frontend.skip}</skip>` is bound at **plugin level** (`pom.xml:209-217`, R) precisely so that every execution switches off together — so the guard is skipped by the same flag and the same edit that skips the suite. Worse: the realistic disarm is "the `npm-test` execution is deleted", and a guard that is a sibling execution of the thing it guards is deleted in the same block. | **rejected** |
| **the existing antrun step** (`maven-antrun-plugin`, phase `test`) | *a refusal.* The step is outside `frontend-maven-plugin`, so `frontend.skip` does not reach it unless the POM hands it in deliberately; it already runs after `npm-test` (P8); it already owns the `<fail>` plumbing whose message is the last line Maven prints. | **proposed** |

**So: the reporter records, the antrun step refuses** — the same split, for the same reason, as
`ExecutedTestClassRecorder` + `SuiteCoverageGuard`. The ticket's own words ("compared by the same antrun step") are
what ships.

**What covers the paths where the suite legitimately does not run.** Nothing else has to: the arm *stands down and
says so*, from inside the program, exactly like its sibling (R `SuiteCoverageGuard.java:106-112`). The switches are
in §6.3, and the asymmetry there is the part a builder will get wrong if it is not spelled out.

**Why not HD-300's `lint.mjs` shape, given it is the newest guard in the tree.** Two of its three ideas are adopted
and one is not.
- **Adopted: the decision is a pure function that refuses a malformed call.** `lint.debt.mjs`'s `verdict` exists
  because a text assertion matched the wrong line and a missing input deleted its own check
  (R `lint.debt.mjs:52-91`). §5.5 ports both properties to Java.
- **Adopted: a guard sealed from *outside* the artifact it guards.** `src/lint/debt.test.ts` reads `pom.xml` as text
  so that deleting the execution is red (R `:289-315`). §8 grows that block.
- **Not adopted: living in the SPA.** A JS program under `src/main/frontend` cannot be the refusal here, for the
  reason in the table. The *record* is written from there; the *decision* is not.

---

## 5. Mechanism

### 5.1 The recorder — `src/main/frontend/src/test/suiteRecorder.ts`

A default-exported reporter class, registered third in `vitest.config.ts`:

```ts
reporters: ['default', './src/test/marginReporter.ts', './src/test/suiteRecorder.ts'],
```

Structurally typed against the reporter contract, like `marginReporter.ts:44-53` — name only the hooks and fields
used, so a version bump that reshapes the task tree elsewhere cannot break the build.

- `onInit(ctx)` — capture `ctx.config.watch`. **Write nothing in watch mode** (precedent: `marginReporter.ts:77-78`);
  a watch run re-runs a handful of files per keystroke and its record is a lie about the suite.
- `onTestModuleEnd(module)` — append one line: the module's path, **relative to `src/main/frontend`, POSIX
  separators, as it appears in git**. Appending per module (rather than one write at the end) means an interrupted or
  crashed run leaves a *short* record, which the guard reports as a hole, instead of no record, which the guard
  reports as "the suite did not run" — two different first questions.
- `onTestRunEnd(modules, _errors, reason)` — append one final line
  `#summary modules=<n> tests=<n> reason=<passed|interrupted|failed>`. `tests` is counted by walking each module's
  test tree (the same recursive shape as `marginReporter.ts:55-63`). This line is the only source of the test count
  the summary prints; it is **not** part of the refusal.
- **Nothing here may throw.** A failed write prints one `[vitest-run-record] …` line on stderr and leaves the record
  short (precedent and reasoning: `ExecutedTestClassRecorder.java:54-57`, R). A reporter that throws attaches noise
  to whichever test happened to be running.

**Path normalisation is load-bearing and is the second-highest risk in this spec.** `TestModule.moduleId` is an
absolute id; on Windows the tree side produces `src\main\frontend\src\...` from git and the reporter side produces
`C:/.../src/main/frontend/src/...`. Rule: the reporter emits **repo-relative POSIX** (`src/main/frontend/src/x.test.ts`)
by stripping a prefix it computes from `process.cwd()`, lower-casing nothing, and the guard compares **sets of those
strings**. A normalisation bug is then loud (every file reported absent) rather than quiet.

**Where the record goes.** `<buildDir>/test-run-record/vitest-<token>-<pid>.txt`, where `<buildDir>` is
`process.env.HAMSTRACK_TEST_RUN_DIR` if set and `path.resolve(process.cwd(), '../../../target')` otherwise (the npm
working directory is `src/main/frontend`, R `pom.xml:207`), and `<token>` is `SuiteRunRecord.token(...)`'s convention
applied to `process.env.HAMSTRACK_TEST_RUN_ID` — `outside-maven` when absent (R `SuiteRunRecord.java:50-71`).

### 5.2 Freshness: identity, never a timestamp

Same rule as HD-265, restated because it is the whole reason `surefire-reports` was rejected there
(R `SuiteRunRecord.java:9-23`): a record is accepted **only** if it carries the identity of *this* Maven session.

- `pom.xml`, on the `npm-test` execution:
  ```xml
  <environmentVariables>
    <HAMSTRACK_TEST_RUN_ID>${maven.build.timestamp}</HAMSTRACK_TEST_RUN_ID>
    <HAMSTRACK_TEST_RUN_DIR>${project.build.directory}</HAMSTRACK_TEST_RUN_DIR>
  </environmentVariables>
  ```
  `${maven.build.timestamp}` is fixed for the whole session and is already handed to the JVM half
  (`pom.xml:573-574`, R), so **both halves of both arms share one identity**.
- **This is premise I-1 and the builder probes it first** (§13): that `frontend-maven-plugin` 1.15.1's `npm` goal
  honours `<environmentVariables>`. Read, not measured. **Plan B, if it does not:** a `maven-antrun-plugin` execution
  bound to `process-test-classes` writes `${maven.build.timestamp}` into
  `${project.build.directory}/test-run-record/run-id.txt`, and the reporter reads that file instead of the env var.
  Same identity, no plugin feature. Do **not** fall back to file timestamps.
- `SuiteRunRecord` grows `vitestFileNamePrefix(runId)` → `vitest-<token>-` and `isRecordFile` is widened to both
  prefixes, so the existing prune (`SuiteCoverageGuard.prune`, R `:324-335`) sweeps vitest records too. The two
  prefixes cannot collide: the JVM one is `executed-<token>-`.

### 5.3 The bound (the tree side)

```
publishableFiles("src/main/frontend")                     // git: --cached --others --exclude-standard
  filtered to the vitest DEFAULT include shape:  **/*.{test,spec}.?(c|m)[jt]s?(x)
  minus vitest's defaultExclude                            // node_modules, dist, cypress, dot-dirs, *.config.*
  minus  coverage/**                                       // eslint.config.js already ignores it
```

Four decisions inside that, each of which is a way the bound could quietly shrink:

1. **Derived, never pinned.** No number to maintain; adding a test file cannot make the build red on its own. Same
   sentence as `SuiteCoverageGuard.java:50-55`.
2. **From git, not from a filesystem walk.** `node_modules` holds **155** matching files (P3); an exclusion list
   maintained by hand is a thing that can be widened silently, whereas `--exclude-standard` follows `.gitignore`,
   whose collapse is caught by the floor. `--others --exclude-standard` (not `--cached` alone) is HD-298's lesson: a
   test file written five minutes ago is untracked, and vitest runs it (R `PublishedCredentials.java:550-561`).
3. **Wider than the configured `include`, on purpose.** The bound uses vitest's *default* include shape rooted at the
   SPA, so a `*.test.js` in `eslint-rules/`, or a `*.test.ts` in `audit/`, is **demanded** and therefore reported
   absent — loud — rather than being a test nobody runs. The refusal names the two remedies (move the file under
   `src/`, or widen `include` **and** `VacuousVerificationRulesTest.VITEST_INCLUDE` in the same diff). This mirrors
   "the expected set is a question about NAMES, and deliberately not about annotations" (R `SuiteCoverageGuard.java:57-65`):
   loud and wrong about a misplaced file beats quiet and wrong about a test.
4. **git unavailable is a refusal, not a stand-down.** The message names the cause. This adds no new requirement:
   `PublishedCredentials.trackedFiles()` already refuses an empty listing and several suites already require a
   checkout, and CI already checks out with `fetch-depth: 0` (R `build.yml:78-82`).

### 5.4 Two floors

- **`FRONTEND_TEST_FILE_FLOOR`** — a collapse detector on the bound itself, with deliberate slack (the house
  precedent: `lint.mjs:47` floors 231 files at 200 and says why). Proposed **55** against today's 73. Its message is
  `Population.floorMessage(...)` — package-private static in the same package as the guard
  (R `Population.java:156-169`), so the house wording is reused rather than re-written.
- **`FRONTEND_TEST_COUNT_FLOOR`** — from the `#summary` line, the answer to "every module ran and contained nothing".
  Set at **~75 % of the measured count** in round 1 (≈ 900 if P4's 1218 holds). Without it, emptying every
  `describe` body is a green run in which all 73 modules are present.

Both floors catch the **collapse**, not the drift; the drift is what the summary line is for. Neither may be lowered
to make a run pass — the message says so.

### 5.5 The refusal (the decision)

A new `com.hamstrack.common.testsupport.SuiteCoverage` — **not** a `*Test`-shaped name, because those four patterns
are the JVM guard's own bound and a helper wearing one is demanded as a class that must execute (R
`SuiteRunRecord.java:32-38`). It holds one pure function:

```java
static List<String> verdict(Arm arm, Set<String> tree, Set<String> executed,
                            Integer testCount, int fileFloor, int testFloor, String disarmedBy)
```

Four properties, each of which is a defect some earlier version of a guard in this repo had:

1. **A malformed call is refused before anything is compared.** `tree == null`, `executed == null`,
   `testCount == null` → a refusal that names the missing input, and the rest is **not** judged. This is
   `lint.debt.mjs:78-116` in Java. In Java the failure mode is different but no better: `Set.containsAll(null)` and
   an unboxing NPE are a stack trace where a sentence belongs, and `Collections.emptySet()` defaulted in by a helper
   is a comparison that passes.
2. **Sets, never counts.** `tree.size() == executed.size()` is satisfiable by a stray file masking a missing one. The
   refusal is `tree − executed`, and `executed − tree` is printed as a *note* (a stale or renamed file), matching the
   JVM arm's "strays" line (R `SuiteCoverageGuard.java:154-160`).
3. **Absent input never disarms.** Only an explicitly-passed, explicitly-true narrowing switch stands the arm down;
   a missing `frontendRoot`, a missing `runId`, a missing record, a git failure are all refusals. "The developer
   passed no filter" arrives as an unresolved `${…}` placeholder and is read as unset — already handled and already
   tested (R `SuiteCoverageGuard.java:182-198`, `SuiteCoverageGuardTest.java:51-61`).
4. **The entry point exits on exactly what `verdict` returned, and on nothing else.** One term on that line. HD-300
   measured what a second term costs: a text assertion pinned it, the first term was deleted, a real violation was
   printed, the run exited 0 and 21 tests stayed green (R `lint.debt.mjs:63-75`). `main` therefore becomes: run both
   arms, concatenate, print, `System.exit(refusals.isEmpty() ? 0 : 1)`.

The JVM arm's existing logic moves behind the same function so there is **one** decision shape for both arms. That is
a refactor, not a rewrite: `SuiteCoverageGuardTest` already covers the derivation and must stay green.

### 5.6 The witness

Every path — pass, fail, disarmed — prints one line per arm on stdout/stderr **and** writes
`${project.build.directory}/test-tree-summary.md`, overwritten each run:

```
| arm    | executed | in tree | tests | verdict |
|--------|----------|---------|-------|---------|
| jvm    | 252      | 252     | —     | complete |
| vitest | 73       | 73      | 1218  | complete |
```

Failure rows say `INCOMPLETE (3 absent)`; disarmed rows say `disarmed by -Dfrontend.skip=true`. The file is written
**before** the exit, so a red run has one too. §9 wires it into CI.

---

## 6. Behaviour, rules, and the arming asymmetry

### 6.1 Happy path
`[vitest-tree] all 73 test files under src/main/frontend executed in this run (1218 tests).`
Printed on green, on purpose, for the reason the sibling gives: the count is the thing a reader was supposed to
notice and did not (R `SuiteCoverageGuard.java:150-153`).

### 6.2 Refusal shapes (each ≤ 25 lines, each naming an action)
- **Incomplete run** — `[vitest-tree] INCOMPLETE RUN: 3 of 73 test files were never executed`, then the names, then
  the first fork in the road: *is the file outside `include`* (the bound is wider, §5.3.3) *or inside it and not
  run* (an `exclude`, a filter, a collection failure). Spill to `target/missing-test-files.txt` past 40, like the
  sibling (R `:360-365`).
- **No record** — names the **three** causes explicitly: the `npm-test` execution did not run; the reporter is not in
  `vitest.config.ts`'s `reporters`; the run identity did not reach the reporter. Anything less sends the reader to
  the wrong one of the three.
- **Floor** — `Population.floorMessage`, verbatim.
- **Malformed call** — names the missing input and says that a comparison against a missing value is not a weaker
  check but an absent one.

### 6.3 Arming — and it is **not** the same set for the two arms

This is the part that will be got wrong. The flags narrow *different runners*:

| switch | surefire | `npm-test` | jvm arm | vitest arm |
|---|---|---|---|---|
| `-Dtest=Foo` | narrowed | **unaffected** | disarm | **stays armed** |
| `-Dgroups=` / `-DexcludedGroups=` | narrowed | **unaffected** | disarm | **stays armed** |
| `-DskipTests` | skipped | skipped (R `pom.xml:377-385`: the plugin honours it for a `test`-phase binding) | disarm | **disarm** |
| `-Dfrontend.skip=true` | unaffected | skipped (plugin-level `<skip>`) | **stays armed** | **disarm** |
| `-Dmaven.test.skip.exec=true` | skipped | **unaffected** (R `pom.xml:280-284`: the plugin binds `${skipTests}` only) | disarm | **stays armed** |
| `-Dmaven.test.skip=true` | skipped, and tests are not compiled | **unaffected** per the same line | disarm | see the edge below |

`frontend.skip` must therefore be **added to the argument list** the POM passes into the step
(`<arg value="frontend.skip=${frontend.skip}"/>`) — note it has a POM-declared default of `false` (R `pom.xml:217`
binds `${frontend.skip}`), so unlike `${test}` it arrives as the literal `false` and the existing
`!equalsIgnoreCase("false")` test already reads that correctly (R `SuiteCoverageGuard.java:171-180`).

`-Dtest=` leaving the vitest arm armed is deliberate and costs nothing new: that run already pays the whole 45-70 s
vitest suite today (it is not skipped by `-Dtest=`), so the arm is answerable for a suite that really did run in
full.

**Edge, pre-existing, do not silently inherit it (I).** Under `-Dmaven.test.skip=true` the test sources are not
compiled, so `target/test-classes` may not contain `SuiteCoverageGuard` at all, and the antrun `<java>` fork would
exit non-zero → `<fail>`. That is a property of the step as it stands today, not of this ticket. The builder
**probes it** (`mvnw.cmd clean test -Dmaven.test.skip=true` on a clean `target/`) and, if it reproduces, files it as
its own ticket rather than smuggling a fix in here; this ticket's only obligation is not to make it worse.

### 6.4 Edge cases
- **Interrupted run** (`reason: 'interrupted'`, Ctrl-C, worker crash): per-module appends mean the record is short →
  incomplete-run refusal naming the modules not reached. The run is red anyway; the guard says *how far it got*.
- **A module that fails to collect** (syntax/import error): vitest reports it as a failed file, the run is red, and
  the module is recorded as present if `onTestModuleEnd` fired for it. Either way no silence.
- **A module containing only skipped tests**: present. §2 (Out).
- **A test file deleted from the tree**: invisible to a tree-derived bound, by design and identically to the JVM arm.
  The file floor catches a *mass* deletion; `VacuousVerificationRulesTest`'s `FRONTEND_FLOOR` catches a collapse of
  the scanned population. Deleting *one* file is caught by nothing here — stated in §7 as the residual, not hidden.
- **Two Maven builds sharing one `target/`**: they already tread on each other over `surefire-reports`,
  `test-classes` and `target/antrun` (R `SuiteCoverageGuard.java:314-323`); this adds no case that was previously
  safe.
- **A local `npm test`** writes a record under `outside-maven`, which the guard ignores on sight and prunes on its
  next run. Bounded litter, never a false pass.
- **Windows + a running Vite dev server**: unchanged and still fatal to any `npm ci` build (`EPERM`). The builder
  uses `-Dfrontend.skip=true` for backend-only loops — which now also stands the vitest arm down, honestly.

---

## 7. The disarm enumeration — every way this guard can pass while verifying nothing

This table is the ticket's real content. **Every row names the mechanism that refuses it, and the seal that keeps
that mechanism in place.** A row whose "refused by" column is empty is not acceptable; the two rows that end in
*residual* say so out loud.

| # | how it can be made to pass while verifying nothing | refused by | sealed by |
|---|---|---|---|
| 1 | `test.include` narrowed (`src/pages/**`) | set difference: the bound is the SPA, not the include | new fixture in `SuiteCoverageGuardTest`; **already** `VacuousVerificationRulesTest#frontendPopulationStillEqualsTheVitestInclude` (R `:156-170`) |
| 2 | `test.exclude` added | set difference | fixture + AC1's watched negative control |
| 3 | `passWithNoTests` added (flag or config) | 0 modules → set difference **and** both floors | fixture at floor−1; text assertion in the new `src/lint/` block |
| 4 | the recorder dropped from `reporters` | no record → the three-cause refusal | text assertion on `vitest.config.ts`'s `reporters` array, whole-line, one array (the shape `debt.test.ts:225-242` uses for `ignores`) |
| 5 | `--reporter=…` added on the CLI (replaces the list) | no record | `scripts.test` pinned to exactly `vitest run`; `<arguments>run test</arguments>` pinned |
| 6 | the `test` script rewritten (`--config`, a dir argument, a filter) | — | same two text assertions |
| 7 | the `npm-test` execution deleted or moved off `test` | **no record → refusal** (this is the row that decides §4) | plus the existing `debt.test.ts`-style execution assertions, extended to `npm-test` |
| 8 | the antrun execution deleted from the pom | — | a new assertion in the `src/lint/` block reading `pom.xml` as text (`<id>test-tree-coverage-guard</id>`, `<phase>test</phase>`, the `<fail>` + `resultproperty` pair). Mutual seal: the antrun guard keeps the vitest suite whole, the vitest suite keeps the antrun guard wired |
| 9 | the `<fail>`/`resultproperty` wiring removed so a non-zero exit is ignored | — | same assertion, which matches the `<fail>` block inside the execution |
| 10 | the guard class missing from `target/test-classes` | `java` exits non-zero → `<fail>` (existing property, R `pom.xml:600-602`) | inherited |
| 11 | the bound collapses (a `.gitignore` swallowing the SPA, wrong cwd) | `FRONTEND_TEST_FILE_FLOOR` | fixture at floor−1 |
| 12 | `git` unavailable / non-checkout build | refusal naming the cause — **never** a stand-down | `SuiteCoverageGuardTest#refusesTheSpaArmWhenItsTreeCannotBeDerivedAtAll`, through the real `catch`: a `frontendRoot` outside the repository makes `git ls-files` exit 128 (measured 2026-09-11) and the arm answers with its own sentence. Round 3 — until then this row was held by a reading of the code, the branch being inside a private method |
| 13 | `verdict` called with a missing input | refusal naming the input, rest not judged | fixture per input (the HD-300 shape, `debt.test.ts:423-444`) |
| 14 | the comparison weakened to sizes | — | fixture: `tree={a,c}`, `executed={a,b}` — **equal sizes, must refuse** |
| 15 | a second condition added to the exit line | — | whole-line text assertion on `main`'s exit, anchored (`debt.test.ts:468-475`'s lesson) |
| 16 | a stale record from an earlier/IDE/local run | run-identity token + prune | fixture over `vitestFileNamePrefix` |
| 17 | the reporter silently fails to write | short record → incomplete-run refusal | reporter unit test asserting it does not throw and reports on stderr |
| 18 | path normalisation drift (Windows separators, absolute ids) | set comparison → *every* file absent, loudly | reporter unit test over a Windows-shaped `moduleId` |
| 19 | a test file placed outside `include` (`eslint-rules/x.test.js`) | in the bound, not in the executed set → refusal naming both remedies | fixture |
| 20 | every module runs but contains no tests | `FRONTEND_TEST_COUNT_FLOOR` from the `#summary` line | fixture at floor−1 |
| 21 | modules run and are recorded, but tests inside are `it.skip`-ed | **out of scope by design** — `VacuousVerificationRulesTest#noFrontendTestSkipsWithoutATicket` (R `:136-149`) owns it | existing |
| 22 | **the antrun execution switched off IN PLACE** by `-Dmaven.antrun.skip=true` — both suites run in full, the guard does not, BUILD SUCCESS (measured 2026-09-11 from the 3.2.0 descriptor: `<skip implementation="boolean" default-value="false">${maven.antrun.skip}</skip>`, `editable=true`) | `<skip>false</skip>` in the execution's `<configuration>`, which wins over the property expression | `suiteGuard.test.ts`, beside the `resultproperty`/`<fail>` assertions. **The row this table was missing**: every other row is a way the guard can be *removed or moved*, and none was a way to switch it off where it stands — which is structurally the same objection §4 used to reject the sibling-plugin placement, one level up |
| 23 | **the guard throws before judging** — wrong working directory, unreadable `target/`, no `git` on the PATH: a stack trace, exit 1, **no summary**, and a `<fail>` line pointing at a `[test-tree]` report that never printed | `judgeOrRefuse` turns any throw into a judged refusal naming the three causes in the order they happen, and writes a summary row reading `REFUSED: threw before judging` — or, when this run's table had already been written, leaves those real rows standing and says so (measured both ways 2026-09-11). `writeSummary` now runs **before** the spill loop, so an IO error while writing the absent members' names no longer takes the table down with it, and an unusable `buildDirectory=` value is judged rather than thrown at `Path.of` | the exit paths are the seal's subject rather than a fixture's: row 13's shape one level up. **Round 3** — the mechanism this row describes is the one `missingPomArgument` already applied one level down, and it had not been applied here |
| R1 | **residual:** a test file is deleted from the tree *and* nothing else changes | nothing (a derived bound cannot see it); the floors catch a mass deletion | — |
| R2 | **residual:** the antrun execution *and* the sealing test are deleted in one diff | nothing; that is a two-file deliberate edit visible in review | — |

---

## 8. Category members (for the builder's `category` block)

**Rule.** *Every automated suite in this repository proves how much of its own tree it executed, and refuses a run
that cannot show it.*

**Members** (two today; the builder starts from this list, does not shorten it, and adds a member rather than a
second mechanism if a third suite appears):

1. **JVM / surefire** — `ExecutedTestClassRecorder` + `SuiteCoverageGuard`, bound `src/test/java`, arming
   `{test, groups, excludedGroups, skipTests, maven.test.skip, maven.test.skip.exec}`. *Exists.*
2. **SPA / vitest** — `suiteRecorder.ts` + the second arm, bound `src/main/frontend`, arming
   `{skipTests, maven.test.skip, frontend.skip}`. *This ticket.*

**`sealedBy`:** `com.hamstrack.common.testsupport.SuiteCoverageGuardTest` (both arms' fixtures) **and**
`src/main/frontend/src/lint/suiteGuard.test.ts` (the wiring, from outside the build file it guards).

**The wiring category, which is what §7 rows 4-9 are members of** — every place the SPA suite can be unhooked from
the Maven path, listed so the builder's assertions start from a list and not from memory (no count in this sentence;
it acquired a member in round 2 and a leading number would already have been wrong):
`package.json` `scripts.test` · `pom.xml` `npm-test` execution (id, phase, arguments) · `pom.xml`
`test-tree-coverage-guard` execution (id, phase, `<fail>`+`resultproperty`) · **`pom.xml` that execution's own
`<skip>`, which `-Dmaven.antrun.skip=true` reaches — the member that is not about either suite** ·
`vitest.config.ts` `include` · `vitest.config.ts` `reporters` · `vitest.config.ts` absence of `passWithNoTests`.

**A second category, found in round 2 and sealed with the first: every floor in this guard.** Members: the JVM arm's
tree floor, the vitest arm's tree floor, the vitest arm's test-count floor. The rule is *a floor refuses with the
house frame and with remedies its own reader can perform*; the seal is
`SuiteCoverageGuardTest#givesEveryFloorRemediesItsOwnReaderCanPerform`, which enumerates the live arms, drives every
floor at value−1 and floors the population of floors at 3.

**A third, in the SPA: every file under `src/` that could name a Node API.** The declarations `suiteRecorder.ts`
needs are ambient, so the rule is *only the recorder may name one*, and the seal is
`src/main/frontend/src/lint/nodeApi.test.ts` (a glob over the same 222-file population `debt.test.ts` scans, floored
at 200, with four files excluded **by exact key and with a reason each**) plus the `tsconfig.app.json` /
`tsconfig.node.json` split that takes the declarations out of the app program altogether.

**And a fourth, which round 3 found unheld: every file under `src/` belongs to exactly one TypeScript project.** The
split above is two halves that do different work — the `declare module` rewrite closes `process`, the re-partition
closes `node:fs` — and only the first was sealed. Measured 2026-09-11: with `src/test/suiteRecorder.ts` dropped from
`tsconfig.node.json`'s `include`, still excluded from the app project, and a **real type error inside it**,
`npx tsc -b --force` exited **0** and all four `src/lint/` files, 35 tests, stayed green. *A file in no project is a
file no gate reads* — which `tsconfig.node.json`'s own comment already said. Sealed by
`nodeApi.test.ts` › *every file under src/ is in exactly one typescript project*, over both tsconfigs parsed as
JSON: every entry of the app program's `exclude` must appear in the node program's `include`, with the two
node-only files asserted by identity as that loop's own floor (an `exclude` deleted wholesale would otherwise make
it iterate zero times — the same defect one level up, again). `NODE_ONLY` is one enumeration read by both the
exemption list and the partition.

---

## 9. DC/Cloud implications

**None, and that is a statement rather than an omission.** This is a build-time gate: no Spring profile, no
`@ConfigurationProperties`, no `application*.properties` key, no runtime branch, nothing that ships in the JAR or the
image. The two environment variables (`HAMSTRACK_TEST_RUN_ID`, `HAMSTRACK_TEST_RUN_DIR`) are Maven-to-npm build
variables with a working default and are never read by application code — so there is no env-var wiring list and
`dc-cloud-guard` is not armed by this diff.

The **image build is unaffected**: the Dockerfile builds with `clean package -DskipTests`, which skips the `npm-test`
execution and stands the vitest arm down with a printed line (R `pom.xml:377-385`, `build.yml:164-168`).

**Gates this diff does arm** (so the orchestrator does not have to derive it): `test-runner` (mandatory —
`src/test/**` and `*.test.*`), `ops-reviewer` (`pom.xml` and `.github/workflows/**` are the ops area),
`frontend-builder` for the reporter and the `src/lint/` test, `backend-builder` for the Java arm. Not armed:
`migration-reviewer`, `dc-cloud-guard`, `api-docs-sync`, `browser-qa`, `tenancy-reviewer`, `security-officer` (no
runtime surface) — the orchestrator marks those `n/a` with that reason.

---

## 10. Acceptance criteria — phrased over the category

Each names the test shape that holds it. "Watched" means the builder saw it red first and pastes the output; where an
effect is observable in a real run, the criterion asks for the effect **with a date**.

**AC1 — every arm refuses a run whose executed set is a strict subset of its tree, and names the absent members.**
*Shape:* fixtures in `SuiteCoverageGuardTest`, one per arm, plus one **watched end-to-end**: add
`exclude: ['src/pages/BoardPage.test.tsx']` to `vitest.config.ts`, run `mvnw.cmd test`, paste the red output and the
`<fail>` line, then revert. Report the date. *(This is the ticket's first acceptance criterion, generalised from
"deleting a test file" to "any way the executed set shrinks", because the config `exclude` is the reachable spelling.)*

**AC2 — every arm refuses a run for which no record of this session exists, and the refusal names every cause.**
*Shape:* fixture + one watched run with the recorder removed from `reporters`. A refusal that names one cause fails
this criterion.

> **As built (HD-301 round 2), the watched half of this one does not land where the criterion assumes.** Removing
> `./src/test/suiteRecorder.ts` from `reporters` reds `suiteGuard.test.ts` *inside* the vitest suite, so `npm-test`
> fails and Maven never reaches the guard — the drill produces a red build with a different message, and the
> three-cause refusal is reachable only by invoking `SuiteCoverageGuard` directly or by deleting the seal in the same
> diff. That is the mutual seal working as designed, not a gap: the fixture holds the refusal's contents and the seal
> holds the wiring. The count in the original wording was also wrong by the time it was written — the vitest arm now
> names **four** causes (the fourth is the recorder refusing or failing to write), which is why this criterion is
> phrased over "every cause" and no number appears in it.

**AC3 — every arm that stands down prints the switch that stood it down, and no arm stands down on a missing input.**
*Shape:* fixtures over the per-arm narrowing sets in §6.3 (including the asymmetric entries: `-Dtest=Foo` disarms the
JVM arm and **not** the vitest arm; `-Dfrontend.skip=true` the reverse) + fixtures asserting that a null/absent
`frontendRoot`, `runId`, record or git result produces a **refusal**. Plus one watched
`mvnw.cmd test -Dfrontend.skip=true` whose output contains the disarm line.

**AC4 — every population this guard derives asserts a floor, and a population under it is refused with the house
floor message.** *Shape:* fixtures at floor−1 for both the file floor and the test-count floor; the message is
`Population.floorMessage` output, asserted by substring, not re-written.

> **Amended in round 2.** Reusing that message *verbatim* — which this section told the builder to do — made every
> floor in the guard prescribe `mvnw clean compile` and "fix Doors" to a reader whose subject was a vitest `include`
> line. The **frame** is still shared (one voice: `…the scan saw N, under the floor of M`, `Exactly one of these is
> true:`, `Do not lower a floor to make a run pass`); the **causes** now travel with each floor as
> `SuiteCoverage.Floor(value, why, causes)`, so a floor cannot be constructed without remedies its own reader can
> perform, and `SuiteCoverageGuardTest#givesEveryFloorRemediesItsOwnReaderCanPerform` drives every live floor at
> value−1 with a floor of 3 on the population of floors.
>
> **Amended again in round 3, because that sentence was still one level short.** "Cannot be constructed" was prose:
> `Floor` had no compact constructor, and the seal iterates `causes()` — so a floor with none iterated zero times and
> passed. Measured 2026-09-11: `JVM_TREE_FLOOR` rebuilt with `List.of()` gave `Tests run: 22, Failures: 0` and BUILD
> SUCCESS. *A scan over floors with no floor of its own* is this ticket's own subject, one level up. Both records now
> refuse an empty or blank remedy list in a compact constructor, the category is **every list a refusal message
> iterates** (`Floor.causes`, `Arm.incompleteHelp`, `Arm.noRecordCauses` — an arm with no `noRecordCauses` prints
> "Exactly one of these is true:" and stops), and `SuiteCoverageGuardTest#refusesAFloorOrAnArmThatPrescribesNothing`
> enumerates them **from the record components** with a floor of 3, so a fourth list joins by existing. The live
> floors are reached because `judge` and the seal now read one enumeration, `SuiteCoverageGuard.members`.
>
> **And the order changed:** the *tree* floor is still judged before the set difference (a collapsed bound makes the
> difference empty, which is the confident green line this whole ticket exists to refuse), but the *test-count* floor
> is now judged **after** it. Judged before, a run that executed 15 of 74 modules was refused as
> `REFUSED: every member empty` over 15 modules full of tests and named none of the 59 absent files — AC1's own
> requirement, failed at scale by the arm that is supposed to hold it.

**AC5 — every refusal is produced by one pure function, and each entry point exits on exactly that function's output.**
*Shape:* the fixture table of §7 driven through `verdict`, plus an anchored whole-line text assertion on the exit
statement. *Negative control:* delete one refusal from `verdict`, watch the fixture go red; add a second term to the
exit line, watch the text assertion go red.

**AC6 — every member of the wiring category in §8 has a test that is red when that wiring is gone.**
*Shape:* `src/main/frontend/src/lint/suiteGuard.test.ts`, six assertions, each watched red by removing the wiring it
names. The pom-reading assertions reuse the already-allowed `pom.xml?raw` import — **and must not widen
`server.fs.allow`**, which `debt.test.ts:263-287` asserts by equality.

**AC7 — every run of the build, red or green, leaves both counts where a reader finds them without arithmetic.**
*Shape:* the guard writes `target/test-tree-summary.md` on all three paths (asserted by a fixture); CI appends it with
`if: always()`. *Observed effect with a date:* the builder pastes the **actual rendered job summary** of the first CI
run on the branch, and the local stdout block of one full `mvnw.cmd verify`, both dated.

**AC8 — no member of the category is enforced by a mechanism that the failure it guards can switch off.**
*Shape:* stated and checked in review against §4's table; mechanically, the vitest arm's refusal must not be reachable
from any `frontend-maven-plugin` execution, asserted by the §8 wiring test finding the guard's `<id>` outside that
plugin's `<executions>` block.

---

## 11. Observability contract

A mechanism with no witness is not specified. Per failure mode: the witness, where it appears, and the drill that
proves it fires.

| failure mode | witness | where | drill (builder performs, pastes, dates) |
|---|---|---|---|
| vitest run truncated | `[vitest-tree] INCOMPLETE RUN: N of M test files were never executed` + names + the `<fail>` line Maven prints last | stderr, and an `INCOMPLETE` row in the job summary | add a config `exclude`, run `mvnw.cmd test`, paste red output + summary, revert |
| suite did not run at all | `[vitest-tree] no execution record for this run …` + every cause it knows | stderr + summary row | remove the recorder from `reporters`, run, paste |
| arm legitimately off | `[vitest-tree] coverage check disarmed by -Dfrontend.skip=true` | stdout + a `disarmed` summary row | run with the flag, paste |
| bound collapsed | the `Population.floorMessage` text | stderr + summary | point the arm at an empty root via its argument, paste |
| every module empty | test-count floor message | stderr + summary | fixture at floor−1, paste |
| the guard itself never ran in CI | the summary step's else branch — an `::error::` annotation naming both causes and the test that separates them, **and a red step** | job summary + the annotations list | **owner, after merge** (the branch has never been seen firing because the workflow change is uncommitted): push once to a throwaway branch with the guard execution commented out, confirm the annotation text and the red job, and write the date into row 22 of §7 beside the measurement |
| the guard switched off in place and the `<skip>false</skip>` line gone inert | nothing — the seal on that line is a **text** assertion, and Maven answers `[WARNING] Parameter 'skipp' is unknown for plugin …` rather than failing, so a BOM bump that renamed antrun's `skip` would leave the line present, the build green and `suiteGuard.test.ts` passing | — | **one disarm run, on the same line as the drill above and repeated on every Boot BOM bump:** `mvnw -B verify -Dmaven.antrun.skip=true` must **refuse**. Pinning the antrun version is the alternative and contradicts the POM's own "do NOT pin" note. Probability is low (antrun 3.x `skip` is stable); the point is that no mechanism here can see it |
| the guard throws before judging (wrong cwd, unreadable `target/`, no `git`) | `[test-tree] REFUSED: the guard threw before it could judge either suite: …` after the stack trace, and a `REFUSED: threw before judging` row in the summary | stderr + summary | fixture-free by nature; the shape is asserted by §7 row 23 and the row exists because `writeSummary` now precedes the spill loop |
| the summary file is a leftover from an earlier local run | the header line `Test-tree coverage for Maven run \`<runId>\`, judged <instant>` | the file, and the job summary | run a build that fails before the `test` phase, read the header and see the previous run's id |
| drift (no failure, but the numbers are moving) | the counts row on every green run | stdout + job summary | read the first CI run's summary and quote the numbers in the report |

**CI step** (new, in `build-and-test`, after the Maven step):

```yaml
      - name: Test-tree counts in the job summary
        if: always()
        run: |
          if [ -f target/test-tree-summary.md ]; then
            cat target/test-tree-summary.md >> "$GITHUB_STEP_SUMMARY"
          else
            message="no test-tree summary was written: either a suite went red before the guard ran (see the failing step above), or the guard itself was skipped or deleted. Nothing compared either suite against its tree on this run."
            echo "::error title=Test-tree guard did not run::$message"
            echo "$message" >> "$GITHUB_STEP_SUMMARY"
            exit 1
          fi
```

`if: always()` is the load-bearing part **for the run this guard failed**: the counts matter most there. It says
nothing about the other four red paths — compile, surefire, `npm-lint` and `npm-test` all abort *before* the antrun
step, so no summary exists and the fallback is all there is.

**The fallback refuses rather than notes** (round 2). As first written it `echo`ed and the step exited 0 on both
paths, so the one case where it fires on an otherwise-green job — the guard skipped in place or its execution
deleted, i.e. exactly the case where nothing else goes red — produced a line in a section nobody reads. It is now an
`::error::` annotation and `exit 1`; on the four suite-red paths the job is red anyway, so it costs nothing there.
The text names both causes, because "the guard did not run" read as a guard defect when the usual cause is a suite
failing first.

**Local witness.** The same two lines on stdout of any `mvnw.cmd test` / `verify`. `build.yml:116-161`'s "what to
look for in this step's log" list is edited: the first bullet stops saying "the count is what tells a reader" for
vitest and starts saying it is *enforced*, with the read-it-anyway drift sentence kept.

---

## 12. Open questions, each with a recommended default

| # | question | recommended default |
|---|---|---|
| Q1 | Does `frontend-maven-plugin` 1.15.1's `npm` goal honour `<environmentVariables>`? | **Assume yes; probe first.** Plan B in §5.2 (a `process-test-classes` antrun `<echo file>` writing the run id) needs no plugin feature and is a four-line change. |
| Q2 | Should the vitest arm stay armed under `-Dtest=`? | **Yes.** That run executes the whole vitest suite today; disarming would be a lie in the safe direction that hides a real truncation. |
| Q3 | One antrun execution with two arms, or two executions? | **One**, one JVM, one summary — the ticket says "the same antrun step", and two executions would produce two tables a reader has to add up. |
| Q4 | A test-count floor as well as a file-count floor? | **Yes**, at ~75 % of the measured count. Row 20 of §7 has no other refusal. |
| Q5 | A test file outside `include`: refuse, or widen `include`? | **Refuse**, naming both remedies. A guard that silently accepts a file nobody runs is the defect wearing the guard's clothes. |
| Q6 | Should the bound be git-derived or a filesystem walk with an exclusion list? | **git** (`publishableFiles`). 155 files in `node_modules` (P3) make the hand-maintained list a standing disarm path, and the repo already requires a checkout. |
| Q7 | Reuse `marginReporter.ts` by adding recording to it? | **No.** It is HD-240's margin survey, deliberately never fails a run, and still uses the deprecated `onFinished`. Two jobs, two files. |
| Q8 | Should the guard also fail on `executed − tree` (a stray)? | **No** — print it as a note, like the JVM arm. A stale artefact is not an unexecuted test. |

---

## 13. ADR

**None.** An ADR is for a hard-to-reverse fork; this is reversible by deleting one antrun arm, one reporter file and
one CI step, and it introduces no new dependency, no schema change and no runtime behaviour. The decision that *would*
have been ADR-shaped — "the refusal lives in a Maven-bound step, not in the runner it guards" — was already taken and
argued in HD-265 and is restated in §4 rather than re-decided.

---

## 14. Builder's first report — the highest-risk assumptions, in order

Before any code, measure these and report **measured / read / inferred** on each:

1. **I-1 — `<environmentVariables>` on the `npm-test` execution reaches the reporter.** Probe: a one-line reporter (or
   `node -e`) printing `process.env.HAMSTRACK_TEST_RUN_ID` through `mvnw.cmd frontend:npm@npm-test`. If absent, take
   Plan B (§5.2) **in the same round** and say so. *This is the single assumption that, if wrong and unnoticed, makes
   the guard refuse every build.*
2. **I-2 — path normalisation.** Print one raw `TestModule.moduleId` on this Windows box and one repo-relative path
   from `publishableFiles("src/main/frontend")`, side by side, before writing the comparison.
3. **R-1 — execution ordering.** Confirm from the build log that `test-tree-coverage-guard` runs **after**
   `frontend:npm (npm-test)`, and add the declaration-offset assertion (the third one, beside
   `debt.test.ts:317-332`).
4. **M-1 — the real counts.** `Test Files … / Tests …` from one full run, dated. P4's 1218 is unverified; the
   test-count floor is derived from **your** number, not from this document's.
5. **I-3 — the `-Dmaven.test.skip=true` edge** (§6.3). Probe on a clean `target/`. If the antrun step already breaks
   there, file it, do not fix it here.
6. **R-2 — `SuiteCoverage` must not be named into Surefire's four include patterns**, and neither may any new helper;
   `suiteRecorder.ts` must not match `*.{test,spec}.{ts,tsx}` or it becomes a member of its own bound.

**Which agent checklist grows** (Phase 7): `.claude/agents/test-runner.md` item 3 — "for vitest the file and test
counts" stops being a thing the agent remembers to report and becomes a line it **quotes from the guard**, with the
disarm line quoted verbatim whenever an arm stood down.
