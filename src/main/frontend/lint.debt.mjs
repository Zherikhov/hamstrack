/**
 * **The HD-177 debt record — one number, read by both halves of the gate (HD-300).**
 *
 * The SPA carries its `no-numeric-font-size` debt *at the sites*, as
 * `eslint-disable-next-line hamstrack/no-numeric-font-size -- HD-177`, rather
 * than in a baseline file: an inline directive self-expires, because
 * `reportUnusedDisableDirectives: 'error'` turns a leftover into a build failure
 * of its own the moment its site is fixed. The argument in full is in
 * `src/lint/debt.test.ts`.
 *
 * **Why the number lives here and not in either reader.** It has two enforcers,
 * and they see different populations:
 *
 *  * `lint.mjs` counts ESLint's own `suppressedMessages` over **everything the
 *    run lints** — `.ts`, `.tsx`, `.js`, `.mjs`, config files, root scripts.
 *  * `src/lint/debt.test.ts` counts directive *text* over the `.ts`/`.tsx`
 *    sources under `src/`, from outside ESLint, so a config edit that disarms
 *    the linter does not disarm the record.
 *
 * The second one alone is what shipped in HD-300's first round, and the tests
 * gate measured the hole: an **active** ticketless directive in a `.js` file
 * passed both gates and was even mislabelled `HD-177` in the witness line,
 * because only an *unused* directive there was caught. Two enforcers, two
 * populations, and exactly one number they both read — a second copy of the
 * number is a drift waiting for the diff that updates one of them.
 */

/**
 * The number of suppressions that exist today. **It may only go DOWN**, and both
 * readers say so in their own failure text. Raising it is a hand edit in a
 * reviewed diff, which is the point: the ratchet tightens by itself and loosens
 * only on purpose.
 *
 * 204, not the 203 HD-300 first declared: the rule could not see
 * `fontSize: size * 0.4` (`ui.tsx`'s `Avatar`, the smallest text in the product)
 * until its fix round taught `offendingValue` about arithmetic, so the site was
 * neither reported nor suppressed and the record undercounted the tree by one.
 */
export const DECLARED = 204

/**
 * The date `DECLARED` was last stated, and the window after which the test that
 * reads it asks for a new statement. **A ceiling with no clock is a permanent
 * parking space**: the ratchet going quiet has no other witness.
 *
 * Only `debt.test.ts` reads these two — a clock belongs to a test that can go
 * red on a Tuesday, not to a lint run that must mean the same thing on any day.
 */
export const DECLARED_AS_OF = '2026-09-11'
export const CLOCK_DAYS = 180

/**
 * **Every way `npm run lint` can pass while having verified less than it looks
 * like it did** — as a pure function, so it can be driven with fixtures.
 *
 * It lives here, and not inline in `lint.mjs`, for one measured reason: the test
 * that guards those refusals first asserted them by matching text in `lint.mjs`,
 * and the pattern for the warning refusal (`warnings > 0`) also matched the line
 * that decides whether to PRINT the stylish report. Deleting the refusal left
 * the assertion green — a guard satisfied by the wrong line, which is the exact
 * shape of vacuity HD-300 exists to refuse. `src/lint/debt.test.ts` now calls
 * this with numbers.
 *
 * **`errors` is in here, and that is the whole exit condition.** It was not, for
 * one round, on the reasoning that an ESLint error is the rule set doing its job
 * and the stylish formatter already names file, line and rule. What that costs is
 * a *second* term in the caller's exit line — `if (errors > 0 || failures.length
 * > 0)` — and the only guard on that line is a text match, which
 * `/failures\.length > 0\) process\.exit\(1\)/` also grants to the strictly
 * weaker `if (failures.length > 0)`. Measured on 2026-09-11: with the first term
 * deleted, a real `no-numeric-font-size` violation was PRINTED, `npm run lint`
 * exited **0**, and all 21 tests in `src/lint/` stayed green — every rule in the
 * set advisory, on a diff that no assertion in the tree refuses. One decision in
 * one place is the fix; the caller's job is now `process.exit(1)` on a non-empty
 * array and nothing else, which is a line a text match CAN pin exactly.
 *
 * **A malformed run is refused before anything is compared.** Every check below
 * is `x > 0` or `x < floor`, and both are FALSE against `undefined` — so an input
 * the caller forgot to pass does not relax a check, it deletes it, silently and
 * one at a time. `lint.mjs` is inside **no** `tsc -b` project (`tsconfig.app.json`
 * covers `src`, `tsconfig.node.json` covers the two vite configs), so the
 * `.d.mts` beside this file type-checks the *test's* call and never the real one.
 * This function is therefore the only thing that can see its own caller lying,
 * and it says so instead of returning `[]`.
 *
 * Returns the failure texts, most-structural first; an empty array means the run
 * asserted what it claims to assert.
 *
 * @param {{fileCount: number, floor: number, targets?: string[], errors: number,
 *          warnings: number, suppressed: number, unticketed: string[]}} run
 * @returns {string[]}
 */
export function verdict({ fileCount, floor, targets = ['.'], errors, warnings, suppressed, unticketed }) {
  const out = []

  for (const [name, value] of Object.entries({ fileCount, floor, errors, warnings, suppressed })) {
    if (!Number.isFinite(value)) {
      out.push(
        `[eslint] FAILED: the run handed verdict() no ${name} (got ${format(value)}).\n` +
        'Every refusal below compares a number, and a comparison against undefined is\n' +
        'FALSE — so a count the caller forgot to pass does not loosen one check, it\n' +
        'removes it and leaves the run green. Nothing type-checks that call site:\n' +
        'lint.mjs is in no tsc -b project. Pass the count, even when it is 0.',
      )
    }
  }
  if (!Array.isArray(unticketed)) {
    out.push(
      `[eslint] FAILED: the run handed verdict() no unticketed list (got ${format(unticketed)}).\n` +
      'Absent, it used to default to [] — which reads as "this run found no unticketed\n' +
      'suppression" when what happened is that nobody looked. Pass the array, even empty.',
    )
  }
  // A run that cannot describe itself cannot be judged: comparing the rest would
  // print refusals derived from numbers that are not this run's.
  if (out.length > 0) return out

  if (fileCount < floor) {
    out.push(
      `[eslint] FAILED: linted ${fileCount} files, floor is ${floor}.\n` +
      'A run that lints (almost) nothing exits 0 and looks identical to a clean one, so\n' +
      'the count is the assertion. Check eslint.config.js "ignores", the targets in\n' +
      `lint.mjs (${targets.join(', ')}), and that this ran from src/main/frontend.\n` +
      'If the SPA genuinely shrank, lower FILE_FLOOR in the same diff and say why.',
    )
  }

  if (suppressed > DECLARED) {
    out.push(
      `[eslint] FAILED: ${suppressed} suppressions over the ${DECLARED} declared in\n` +
      'lint.debt.mjs. This number may only go DOWN. If you are paying HD-177 off, lower\n' +
      'DECLARED and move DECLARED_AS_OF forward. If you are adding a suppression, do not:\n' +
      'fix the site, or argue for the exception in the diff and raise DECLARED by hand so\n' +
      'a reviewer sees it. This counts every file the run lints — .js and .mjs included,\n' +
      'which the .ts/.tsx scan in src/lint/debt.test.ts cannot see.',
    )
  }

  if (unticketed.length > 0) {
    out.push(
      `[eslint] FAILED: ${unticketed.length} suppression(s) name no ticket:\n` +
      `${unticketed.map((u) => `  ${u}`).join('\n')}\n` +
      'A suppression with no ticket is debt nobody owns and nobody will ever come back\n' +
      'for. Write it as `// eslint-disable-next-line <rule> -- HD-<n>`; ESLint parses\n' +
      'everything after `--` as the justification and it is printed in the witness line.',
    )
  }

  if (warnings > 0) {
    out.push(
      `[eslint] FAILED: ${warnings} warning(s). Nothing in this config is configured at\n` +
      "'warn' — every rule is an error — so a warning means a severity was downgraded in\n" +
      'a source file by an inline eslint configuration comment, which is the one disarm\n' +
      'path neither eslint.config.js nor src/lint/debt.test.ts can see. Fix the site, or\n' +
      'suppress it with a ticket so it is counted as debt.',
    )
  }

  // Last, because it is the least structural of the five and the only one whose
  // detail is already on screen: the stylish report printed above this verdict
  // names every file, line and rule. It is in this array anyway so that the
  // caller has exactly ONE thing to exit on — see the note in the javadoc.
  if (errors > 0) {
    out.push(
      `[eslint] FAILED: ${errors} error(s) — the rule set doing its job. The stylish\n` +
      'report above names the file, line and rule of each one. Fix the site, or suppress\n' +
      'it with a ticket (`-- HD-<n>`) and raise DECLARED in lint.debt.mjs in the same diff.',
    )
  }

  return out
}

/**
 * A missing input rendered as itself. `String(undefined)` is `'undefined'`, but
 * `${[]}` is the empty string — and a refusal that says "got " is the same
 * unreadable line the refusal exists to replace.
 */
function format(value) {
  if (Array.isArray(value)) return `an array of ${value.length}`
  if (value !== null && typeof value === 'object') return 'an object'
  return typeof value === 'string' ? JSON.stringify(value) : String(value)
}
