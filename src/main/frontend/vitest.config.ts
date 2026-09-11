import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Separate from vite.config.ts (the production build) so test-only settings
// never leak into the JAR bundle. Tailwind is intentionally omitted — the unit
// tests assert DOM/behavior, not computed styles, so we skip the CSS pipeline.
export default defineConfig({
  plugins: [react()],
  // `src/lint/debt.test.ts` reads `pom.xml` as text — the last silent-drop path
  // for the lint gate is the Maven execution being deleted, and nothing else
  // would notice. It sits three directories above the Vite root, so without an
  // entry here the transform is refused as a `Denied ID`.
  //
  // The entries NAME THE FILE. `'../../..'` — what HD-300 first wrote — hands
  // the whole repository to Vite's transform middleware: `application*.properties`,
  // `data/attachments/`, `ops/`, `.claude/`, any `.env` a developer keeps there.
  // It grants no capability a test lacked (`node:fs` was never gated), but it is
  // the control that bounds what an open `@vitest/mocker` path-traversal
  // advisory can reach, and there is no reason for it to be wider than one file.
  //
  // WHY TWO ENTRIES FOR ONE FILE, measured against vite 6.4.3 rather than read:
  // `isFileLoadingAllowed` matches an entry by `isSameFileUri(uri, filePath) ||
  // isParentDirectory(uri, filePath)`, and this import is checked TWICE on two
  // different spellings of the same file. `isServerAccessDeniedForTransform`
  // runs first and passes the id **with its query** (`…/pom.xml?raw`); the load
  // path afterwards passes `cleanUrl(id)` (`…/pom.xml`). A directory entry
  // covered both by prefix; an exact-match entry covers only what it spells, so
  // `allow: ['./', '../../../pom.xml']` alone fails at collection with
  // `Denied ID C:/…/pom.xml?raw` — reproduced here on 2026-09-11 before this
  // line was written. Remove the `?raw` entry and `debt.test.ts` stops running
  // at all, which is a lint gate losing its outside guard; it does not fail
  // quietly, but it does fail for a reason nobody would guess from the diff.
  //
  // `vite.config.ts` (the production build) is untouched and still cannot reach
  // outside src/main/frontend.
  server: { fs: { allow: ['./', '../../../pom.xml', '../../../pom.xml?raw'] } },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // The CSS pipeline stays off — with ONE file excused. `css: false` makes a
    // stylesheet import resolve to an empty string even through `?raw`, and
    // `colour.test.ts` reads `index.css` as TEXT to prove that the design tokens
    // `colour.ts` falls back on when no stylesheet is attached still agree with
    // the ones the app ships. No test imports `index.css` as a stylesheet, so
    // nothing here starts asserting on computed styles.
    css: { include: [/index\.css/] },

    /**
     * HD-240 — the bound is a HANG detector, not a performance budget.
     *
     * A test timeout is a claim about the slowest machine the suite will ever
     * run on, and vitest's 5 s default was a claim about an idle one. The same
     * test, with the same assertions, measured three ways on a 12-core box on
     * 2026-09-04 over 1166 tests — `BacklogPage.sectionRefresh`, a 120-row
     * jsdom render plus two `userEvent` interactions:
     *
     *   ~1.6 s   the file run on its own
     *   ~5.2 s   inside a full, otherwise idle suite run
     *   6.8–9.6 s  inside a full run at 2x oversubscription (24 workers)
     *
     * None of that spread is the code under test. It is the CPU share the
     * worker happened to get, and it grows with the suite: at 947 tests two
     * cases sat at 77–79% of the old bound, at 1166 the same file put three
     * over it and the population near it was 22 tests deep.
     *
     * 30 s is ~3x the worst run observed under load and ~19x the test's own
     * cost, which is the room a CI runner slower than this box needs. The
     * price is that a genuinely hung test takes 30 s to say so, once, at the
     * end of one run — cheaper than a suite that goes red for reasons that are
     * not about the product, because the answer to THAT is muting the suite.
     * `hookTimeout` matches: setup runs on exactly the same diluted CPU as the
     * test it sets up.
     *
     * This is not the suite's only clock — `findBy*`/`waitFor` have their own,
     * far tighter one, raised in `src/test/setup.ts`, and it does not inherit
     * from this. Read that comment before trusting this number alone.
     *
     * The margin, not the failure, is what to watch: `marginReporter` prints
     * the top of the duration distribution after every full run, so the next
     * erosion is read from the log rather than discovered by a red CI job.
     */
    testTimeout: 30_000,
    hookTimeout: 30_000,

    // `suiteRecorder` is the record half of the HD-301 suite-ran guard: it writes
    // down which test modules this run executed, and the `test-tree-coverage-guard`
    // antrun step in pom.xml refuses a run whose executed set is smaller than the
    // tree. Dropping it from this array does not make anything green — the guard
    // finds no record and refuses, naming this line among its causes. Both
    // entries here are asserted by `src/lint/suiteGuard.test.ts`; a `--reporter=…`
    // flag on the command line REPLACES this list, which is why `package.json`'s
    // `test` script is pinned to a bare `vitest run` by the same test.
    reporters: ['default', './src/test/marginReporter.ts', './src/test/suiteRecorder.ts'],
  },
})
