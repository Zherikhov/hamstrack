import tsParser from '@typescript-eslint/parser'
import hamstrack from './eslint-rules/index.js'

/**
 * **The SPA's flat config — four custom rules and nothing else (HD-300).**
 *
 * No stock rule set. `eslint:recommended`, `react-hooks` and `jsx-a11y` are all
 * defensible and none of them is this ticket: the set here is exactly the
 * mechanical entries of the frontend trap list, so that a failure here always
 * reads as one of the traps and never as an opinion.
 *
 * **No `projectService` and no type information.** All four rules are
 * syntactic; type-aware linting would charge a full program build per run to
 * answer questions none of them asks. The one trap that *does* need types — a
 * `var(--…)` token reaching a prop that means "a hue from the database" — is
 * executed by the `Hex` type in `src/types.ts` under `tsc -b`, not here.
 *
 * **`reportUnusedDisableDirectives: 'error'` is load-bearing.** R2's debt sites
 * carry inline directives instead of a baseline file — how many is `DECLARED` in
 * `lint.debt.mjs`, which is the one place that number is written — and this
 * setting is what makes each one expire: the moment a site is fixed, its
 * directive becomes a build failure of its own. That is the property a regenerable baseline does
 * not have — the ratchet tightens without anyone editing it. `src/lint/debt.test.ts`
 * holds the other half, from outside this file, because a guard inside the
 * artifact it guards is disarmed by the same edit.
 *
 * `ignores` is a **declared** list, asserted by that same test: an `ignores`
 * that quietly grows to cover the tree is the vacuous-green shape `lint.mjs`'s
 * file floor exists to catch.
 */

/**
 * The only paths excluded from the lint run, asserted verbatim by
 * `src/lint/debt.test.ts`. Three entries, all of them tool output that can appear
 * *inside* this directory.
 *
 * `../resources/static/**` — the Vite `outDir` — is deliberately **not** here: the
 * run is rooted at this directory (`lint.mjs` pins `cwd`), so nothing above it is
 * reachable and an entry for it would be an inert line in a list a test says is
 * exact. An ignore that cannot match is a claim that cannot be checked.
 */
export const IGNORED = ['node_modules/**', 'dist/**', 'coverage/**']

export default [
  { ignores: IGNORED },
  {
    files: ['**/*.{ts,tsx,js,mjs}'],
    languageOptions: {
      parser: tsParser,
      ecmaVersion: 2023,
      sourceType: 'module',
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    linterOptions: {
      reportUnusedDisableDirectives: 'error',
    },
    plugins: { hamstrack },
    rules: {
      'hamstrack/no-shadowed-max-width': 'error',
      'hamstrack/no-numeric-font-size': 'error',
      'hamstrack/overlay-has-dialog-semantics': 'error',
      'hamstrack/no-role-name-gate': 'error',
    },
  },
]
