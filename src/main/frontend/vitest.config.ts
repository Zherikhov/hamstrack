import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Separate from vite.config.ts (the production build) so test-only settings
// never leak into the JAR bundle. Tailwind is intentionally omitted — the unit
// tests assert DOM/behavior, not computed styles, so we skip the CSS pipeline.
export default defineConfig({
  plugins: [react()],
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

    reporters: ['default', './src/test/marginReporter.ts'],
  },
})
