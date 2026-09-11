/**
 * **The record half of the vitest suite-ran guard (HD-301).**
 *
 * `vitest run` prints `Test Files … / Tests …` and exits 0 over whatever it
 * happened to match. A run that matches *some* of the tree prints exactly the
 * same confident green line as one that matched all of it — which is HD-265's
 * defect, on the other suite, with nothing watching for it. This reporter writes
 * down **which test modules this run actually executed**; the refusal is the
 * `test-tree-coverage-guard` antrun step in `pom.xml`, which compares that
 * record against the tree.
 *
 * **Why the refusal is not here.** A reporter is code *inside* the run. The
 * failure this mechanism exists for is "the suite did not run" — the `npm-test`
 * execution deleted, `passWithNoTests`, the reporters array edited — and in every
 * one of those a reporter is silent, because it never loads. Same split, same
 * reason, as `ExecutedTestClassRecorder` (records) and `SuiteCoverageGuard`
 * (refuses): detection where it can see, refusal where nothing the suite does can
 * reach it.
 *
 * **Appending per module, not one write at the end.** An interrupted or crashed
 * run then leaves a *short* record, which the guard reports as a hole naming the
 * modules it never reached — instead of *no* record, which the guard reports as
 * "the suite did not run". Those are two different first questions and a reader
 * should not have to guess which one happened.
 *
 * **Path normalisation is the load-bearing part.** `moduleId` is an absolute Vite
 * id (`C:/…/src/main/frontend/src/x.test.ts` on this box); the guard's bound comes
 * from `git ls-files` and is repo-relative (`src/main/frontend/src/x.test.ts`).
 * This emits **repo-relative POSIX** and the guard compares sets of those strings,
 * so a normalisation bug reports *every* file absent — loud — rather than shifting
 * one file quietly out of the comparison.
 *
 * **Nothing here may throw.** An exception escaping a reporter callback is noise
 * attached to whichever test happened to be running. A failed write prints one
 * line on stderr and leaves the record short, which the guard turns into a
 * refusal: loud in the right place rather than fatal in the wrong one
 * (`ExecutedTestClassRecorder` says the same about the JVM half).
 *
 * Structurally typed against vitest's reporter contract, like `marginReporter.ts`:
 * only the hooks and fields used below are named, so a version bump that reshapes
 * the task tree elsewhere cannot break the build. The hooks are `onTestModuleEnd`
 * / `onTestRunEnd` and deliberately **not** `onFinished`, which is deprecated in
 * the installed vitest 3.2.7.
 */
import { appendFileSync, mkdirSync } from 'node:fs'
import { relative, resolve } from 'node:path'
// Imported rather than reached for as a global: `src/test/nodeApi.d.ts` declares
// it as a MODULE, so nothing under `src/` gets `process` in scope by accident
// (HD-301 round 2 — the first version of that file put it in global scope and a
// component reading `process.env` compiled and linted clean).
import process from 'node:process'
// Same reason, and the one that proves the rule: this file is compiled by
// tsconfig.node.json, whose `lib` is ES2023 with no DOM, so even `console` has to
// be named rather than assumed.
import console from 'node:console'

/** Must equal `SuiteRunRecord.DIRECTORY_NAME`; both halves resolve the same directory. */
const DIRECTORY_NAME = 'test-run-record'

/**
 * Must equal `SuiteRunRecord.VITEST_PREFIX`. The JVM half writes `executed-…`, so
 * the two records of one Maven session cannot be mistaken for each other, and the
 * guard's prune sweeps both.
 */
const FILE_PREFIX = 'vitest-'

/** Must equal `SuiteRunRecord.NO_MAVEN_RUN` — an IDE run, or a bare `npm test`. */
const NO_MAVEN_RUN = 'outside-maven'

/** Must equal `SuiteCoverage.SUMMARY_PREFIX`. */
const SUMMARY_PREFIX = '#summary'

interface CollectionLike {
  allTests?: () => Iterable<unknown>
}

interface ModuleLike {
  moduleId?: string
  children?: CollectionLike
}

interface VitestLike {
  config?: { watch?: boolean }
}

/**
 * `SuiteRunRecord.token` in TypeScript: the run identity reduced to characters
 * legal in a file name on every platform we build on. The default
 * `maven.build.timestamp` format contains colons, which Windows rejects.
 */
function token(runId: string | undefined): string {
  const raw = runId === undefined || runId.trim() === '' ? NO_MAVEN_RUN : runId.trim()
  return raw.replace(/[^A-Za-z0-9]/g, '-')
}

export default class SuiteRecorder {
  /** The repository root: the npm working directory is `src/main/frontend` (pom.xml). */
  private readonly repoRoot = resolve(process.cwd(), '..', '..', '..')

  private watch = false
  private reported = false
  private file: string | null = null
  private readonly written = new Set<string>()

  onInit(ctx: VitestLike): void {
    // A watch run re-runs a handful of files per keystroke; its record is a lie
    // about the suite, so it writes none (marginReporter.ts stands down the same
    // way, for the same reason).
    this.watch = ctx.config?.watch === true
  }

  onTestModuleEnd(testModule: ModuleLike): void {
    this.record(testModule)
  }

  onTestRunEnd(
    testModules: readonly ModuleLike[] = [],
    _unhandledErrors?: unknown,
    reason = 'passed',
  ): void {
    if (this.watch) return
    // Recorded again from the final list, not only from onTestModuleEnd: a module
    // that fails to COLLECT (a syntax or import error) may never reach the
    // per-module hook, and the guard must not report a red run's broken file as a
    // file that was never offered to the plan.
    let tests = 0
    for (const testModule of testModules) {
      this.record(testModule)
      tests += countTests(testModule)
    }
    this.append(
      `${SUMMARY_PREFIX} modules=${this.written.size} tests=${tests} reason=${reason}`,
    )
  }

  private record(testModule: ModuleLike): void {
    if (this.watch) return
    const id = this.repoRelative(testModule?.moduleId)
    if (id === null || this.written.has(id)) return
    this.written.add(id)
    this.append(id)
  }

  /** Repo-relative, POSIX separators — the spelling `git ls-files` produces. */
  private repoRelative(moduleId: string | undefined): string | null {
    if (moduleId === undefined || moduleId.trim() === '') return null
    const posix = relative(this.repoRoot, moduleId).split('\\').join('/')
    return posix === '' ? null : posix
  }

  private append(line: string): void {
    try {
      if (this.file === null) this.file = this.open()
      appendFileSync(this.file, `${line}\n`, 'utf8')
    } catch (error) {
      // Once per run, not once per module: a misconfigured directory fails on
      // every append, and one identical paragraph per test file buries the
      // suite's own output. The write is still retried — a transient IO error may
      // clear — but the reader is told once.
      if (this.reported) return
      this.reported = true
      console.error(
        '[vitest-run-record] could not record the executed test modules: ' +
        `${String(error)}. The run continues; the coverage guard will report the ` +
        'record as short, which is the truth about this run.',
      )
    }
  }

  /**
   * Where this run's record goes.
   *
   * **A blank environment variable is an ABSENT one**, the same reading `token()`
   * two functions up already gives `HAMSTRACK_TEST_RUN_ID`: `??` coalesces only
   * `null`/`undefined`, so an un-interpolated `${project.build.directory}` or an
   * exported-empty var resolved against the CWD and dropped the record in
   * `src/main/frontend/test-run-record/` — untracked source-tree litter, and the
   * guard then refused the build naming three causes of which none was the real
   * one. `ExecutedTestClassRecorder` is the sibling with the same hole and
   * `SuiteRunRecord.buildDirectory` now closes both.
   *
   * **And a directory outside the repository is refused rather than written to.**
   * The throw is caught by the caller, which prints `[vitest-run-record]` on
   * stderr and leaves the record short — the guard's fourth no-record cause names
   * this line, so the refusal a reader eventually sees points here.
   */
  private open(): string {
    const configured = process.env.HAMSTRACK_TEST_RUN_DIR?.trim()
    const directory = resolve(configured || resolve(this.repoRoot, 'target'), DIRECTORY_NAME)
    const inside = relative(this.repoRoot, directory)
    if (inside.startsWith('..') || /^([A-Za-z]:)?[\\/]/.test(inside)) {
      throw new Error(
        `HAMSTRACK_TEST_RUN_DIR=${String(process.env.HAMSTRACK_TEST_RUN_DIR)} resolves to ` +
        `${directory}, outside ${this.repoRoot}. The record is read from the build directory ` +
        'the same Maven session passes to the coverage guard, so a record written anywhere ' +
        'else is a record nobody reads. Unset it, or point it inside the repository.',
      )
    }
    mkdirSync(directory, { recursive: true })
    return resolve(
      directory,
      `${FILE_PREFIX}${token(process.env.HAMSTRACK_TEST_RUN_ID)}-${process.pid}.txt`,
    )
  }
}

/** The module's own test count, however deep the suites nest. */
function countTests(testModule: ModuleLike): number {
  const all = testModule?.children?.allTests
  if (typeof all !== 'function') return 0
  let n = 0
  for (const _test of all.call(testModule.children)) n += 1
  return n
}
