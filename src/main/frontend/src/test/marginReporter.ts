/**
 * The margin survey (HD-240).
 *
 * A test's time bound is a claim about the slowest machine the suite will ever
 * run on, not about the one that happens to be running it. So the defect this
 * reporter exists to expose is the MARGIN, not the failure: by the time a test
 * times out, the suite has already been near the bound for a while, and on
 * somebody's CI box it was over it. This prints the top of the duration
 * distribution after every full run so that erosion is something you READ,
 * not something you get paged about.
 *
 * Measured on 2026-09-04 (12-core dev box, 1166 tests): the slowest test costs
 * ~1.6 s standalone and 6.8–9.6 s inside a 24-worker run of the whole suite —
 * the same test, up to 6x, purely from the CPU share the worker got. That
 * factor, not the standalone number, is what `testTimeout` in
 * `vitest.config.ts` is sized against, and it is why the survey reports a
 * PERCENTAGE: an absolute millisecond figure means nothing without the machine
 * it was taken on, but "half the bound" travels.
 *
 * It never fails a run. A margin check that fails is just a second, stricter
 * timeout, and it would be flaky for exactly the reason the first one was.
 *
 * Structurally typed against vitest's reporter contract on purpose: only the
 * two hooks and the four fields used below are named here, so a version bump
 * that reshapes the task tree elsewhere cannot break the build.
 */

/** Fraction of `testTimeout` above which a test is called out by name. */
const WARN_AT = 0.5

/** How many of the slowest tests to list on every run. */
const TOP_N = 5

interface TaskLike {
  type?: string
  name?: string
  tasks?: TaskLike[]
  result?: { duration?: number; state?: string }
}

interface FileLike extends TaskLike {
  name?: string
}

interface VitestLike {
  config?: { testTimeout?: number; watch?: boolean }
}

interface Measured {
  file: string
  name: string
  ms: number
}

function collect(task: TaskLike, file: string, out: Measured[]): void {
  if (task.tasks) {
    for (const child of task.tasks) collect(child, file, out)
    return
  }
  if (task.type === 'test' || task.type === 'custom') {
    out.push({ file, name: task.name ?? '(unnamed)', ms: task.result?.duration ?? 0 })
  }
}

function shortPath(name: string): string {
  const normalised = name.split('\\').join('/')
  return normalised.split('/frontend/')[1] ?? normalised.split('/').slice(-2).join('/')
}

export default class MarginReporter {
  private bound = 5000
  private watch = false

  onInit(ctx: VitestLike): void {
    this.bound = ctx.config?.testTimeout ?? 5000
    // Watch mode re-runs a handful of files on every keystroke; a survey of a
    // partial run says nothing about the suite's margin.
    this.watch = ctx.config?.watch === true
  }

  onFinished(files: FileLike[] = []): void {
    if (this.watch || files.length === 0) return

    const measured: Measured[] = []
    for (const file of files) collect(file, shortPath(file.name ?? ''), measured)
    if (measured.length === 0) return

    measured.sort((a, b) => b.ms - a.ms)
    const pct = (ms: number) => Math.round((100 * ms) / this.bound)
    const line = (t: Measured) =>
      `  ${String(Math.round(t.ms)).padStart(6)}ms ${String(pct(t.ms)).padStart(3)}%  ` +
      `${t.file} › ${t.name}`

    const over = measured.filter((t) => t.ms >= this.bound * WARN_AT)

    console.log(
      `\nmargin survey — testTimeout ${this.bound}ms, ` +
      `slowest ${Math.round(measured[0].ms)}ms (${pct(measured[0].ms)}%), ` +
      `${over.length} test(s) over ${Math.round(WARN_AT * 100)}% of the bound`,
    )
    for (const t of measured.slice(0, TOP_N)) console.log(line(t))
    if (over.length > 0) {
      console.log(
        `  ^ ${over.length} of these used more than ${Math.round(WARN_AT * 100)}% of the bound. ` +
        'That is the margin eroding, not a pass: a test at half the bound here is ' +
        'a failure on a smaller box. Make it cheaper, or raise the bound and say why.',
      )
    }
  }
}
