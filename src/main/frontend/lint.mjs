#!/usr/bin/env node
/**
 * **`npm run lint` — a program, not a bare `eslint .` (HD-300).**
 *
 * A bare `eslint .` **exits 0 when it lints nothing**: an `ignores` entry that
 * grows a `**`, a config that fails to resolve to the source tree, a `cwd` one
 * directory off — each of those produces a silent, instantaneous, green run that
 * is indistinguishable from a clean one. That is the same vacuous-green shape
 * `pom.xml` records for surefire's `passWithNoTests`, and HD-265 answered it for
 * the backend with a guard shaped as a **program** rather than a test, for the
 * reason that applies here too: a guard shaped as a lint rule would be swallowed
 * by the very `ignores` it is guarding against.
 *
 * So this asserts a **floor on the number of files actually linted** and prints
 * the count on every run, pass or fail. The number is the witness; the exit code
 * alone is not evidence.
 *
 * **The refusals are not here.** Every one of them — the file floor, a
 * downgraded severity, the suppression ceiling, a suppression naming no ticket,
 * an ESLint error, and a caller that omitted one of those counts — is `verdict`
 * in `lint.debt.mjs`, driven with fixtures by `src/lint/debt.test.ts`. Each is a
 * hole some version of this program had, and listing them here as well would put
 * a count in prose one edit away from the array that holds them. This file
 * counts, prints, and exits on what `verdict` returns; it decides nothing.
 *
 * Deliberately no `--cache`: a cache turns a clean run into a claim about a
 * previous run, and `.eslintcache` would then be the artifact the build trusts.
 * Which means CI pays a full cold lint every run — that is the price of the
 * count on the line above meaning "this tree, now".
 */
import { ESLint } from 'eslint'
import { verdict } from './lint.debt.mjs'

/**
 * The floor, chosen **below** the population on purpose.
 *
 * The population is whatever the witness line prints — 231 on 2026-09-11, and
 * the point of printing it is that nobody has to trust this sentence. A floor
 * set just under the population would assert nothing about the population and
 * would only fire on a deletion, so it is set with real slack and its job is to
 * catch the **collapse**, not the drift: an `ignores` that swallows the tree
 * takes the count to single digits, not to one less than today's. The drift is
 * what a reader watches (`.github/workflows/build.yml` says where). Raise this
 * when the SPA grows enough that 200 stops being a collapse; lowering it needs a
 * reason in the diff.
 */
const FILE_FLOOR = 200

/**
 * The whole frontend tree, minus `eslint.config.js`'s declared `ignores`. Naming
 * directories here instead would mean a new top-level one is linted by nobody and
 * says nothing — which is the failure this program exists to make loud.
 *
 * **Asserted verbatim by `src/lint/debt.test.ts`, and it has to be.** The floor
 * is 200 with deliberate slack, so narrowing this to `['src']` still lints 217
 * files and clears it — green, and every `.js`/`.mjs` suppression outside `src`
 * invisible again, which is the exact hole the ticketless-suppression refusal
 * was added to close (measured 2026-09-11: `217 files linted (floor 200)`, exit
 * 0, a planted ticketless suppression in `eslint.config.js` unseen). A floor
 * cannot guard this; only naming the value can.
 */
const TARGETS = ['.']

const eslint = new ESLint({ cwd: import.meta.dirname })
const results = await eslint.lintFiles(TARGETS)

const errors = results.reduce((n, r) => n + r.errorCount, 0)
const warnings = results.reduce((n, r) => n + r.warningCount, 0)
const suppressed = results.reduce((n, r) => n + (r.suppressedMessages?.length ?? 0), 0)

/**
 * The debt tickets named by the directives that are actually suppressing
 * something — and, in the same pass, the directives that name **no** ticket.
 *
 * Both halves matter and only one of them existed first: the witness line used
 * to print `N suppressed (HD-177)` by collecting the tickets it could find, so
 * an unticketed suppression was silently counted under someone else's ticket.
 */
const tickets = new Set()
const unticketed = []
let ticketedCount = 0
for (const r of results) {
  for (const m of r.suppressedMessages ?? []) {
    let named = false
    for (const s of m.suppressions ?? []) {
      const ticket = /HD-\d+/.exec(s.justification ?? '')
      if (ticket) { tickets.add(ticket[0]); named = true }
      else unticketed.push(`${r.filePath}:${m.line} — ${m.ruleId}`)
    }
    if (named) ticketedCount++
  }
}

if (errors > 0 || warnings > 0) {
  const formatter = await eslint.loadFormatter('stylish')
  process.stdout.write(await formatter.format(results))
}

/**
 * `N suppressed (HD-177)` — but only while the ticket list covers all N. The
 * label used to be printed unconditionally from the tickets the run could find,
 * so a directive naming none was counted under someone else's: measured
 * 2026-09-11, one planted ticketless suppression printed
 * `205 suppressed (HD-177)`, which is an attribution the diff never made. The
 * refusal below is the gate; this line is the witness, and a witness that rounds
 * a number into another ticket is one a reader stops checking. When the two
 * disagree the label splits and says so.
 */
const label = unticketed.length === 0
  ? [...tickets].sort().join(', ')
  : [
    ...(ticketedCount > 0 ? [`${ticketedCount} for ${[...tickets].sort().join(', ')}`] : []),
    `${unticketed.length} naming no ticket`,
  ].join(', ')
const debt = suppressed > 0 ? ` · ${suppressed} suppressed (${label})` : ''
console.log(
  `[eslint] ${results.length} files linted (floor ${FILE_FLOOR}) · ` +
  `${errors} errors · ${warnings} warnings${debt}`,
)

// The decision is `verdict` in lint.debt.mjs — a pure function over the counts
// above, so `src/lint/debt.test.ts` can drive every refusal with fixtures
// instead of grepping this file for a substring. (It grepped, once: the pattern
// `warnings > 0` also matched the formatter guard above, so deleting the refusal
// left the assertion green. A guard matched by the wrong line is the vacuous
// shape this whole ticket is about.)
//
// EVERY count goes in, including `errors`, and the exit below tests ONE thing.
// The earlier `if (errors > 0 || failures.length > 0)` was two decisions on one
// line, and a test can only pin such a line by matching part of it — which the
// strictly weaker `if (failures.length > 0)` satisfies too. Measured on
// 2026-09-11: that edit printed a real rule violation and exited 0, with the
// whole lint suite green. Nothing may be added to this line; add a refusal to
// `verdict` instead, where a fixture can drive it.
const failures = verdict({
  fileCount: results.length, floor: FILE_FLOOR, targets: TARGETS,
  errors, warnings, suppressed, unticketed,
})
for (const f of failures) console.error(f)

if (failures.length > 0) process.exit(1)
