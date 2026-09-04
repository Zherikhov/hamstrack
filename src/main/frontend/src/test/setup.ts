// Vitest global setup: jest-dom matchers (toBeInTheDocument, toHaveValue, …)
// plus a DOM cleanup between tests so leftover React trees can't cross-talk.
import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

/**
 * HD-240 — the suite's OTHER time bound, and the one that lies about itself.
 *
 * `testTimeout` (20 s, `vitest.config.ts`) is not the only clock a test runs
 * against: every `findBy*`/`waitFor` carries testing-library's own
 * `asyncUtilTimeout`, which defaults to **1000 ms** and is not raised by
 * raising vitest's. It is the tighter of the two by 20x, and it is the one an
 * ordinary test hits first — a page that renders, fetches and settles has to do
 * all of it inside one second of WALL CLOCK, on whatever CPU share the worker
 * got.
 *
 * Its failure does not say "timeout". It says `Unable to find an element by:
 * [data-testid="burnup-chart"]` and prints the DOM as it was 1000 ms in, which
 * reads exactly like the component failing to render the thing — so the natural
 * response is to go looking for a product bug that is not there. That is how
 * this one was found: `SprintBurnupPage` "failed" under a 24-worker run of the
 * suite while `BacklogPage` timed out beside it, and only one of the two
 * announced that it was about time.
 *
 * The bound is a claim about the slowest machine, same as the other one, and
 * it is derived from a property rather than from the case that failed: a
 * single await inside a test cannot legitimately outlast the WHOLE slowest
 * test in the suite. That was 9.6 s, measured 2026-09-04 on a 12-core box
 * across five full runs at 2x oversubscription, so 10 s is the smallest number
 * that cannot refuse a wait any current test is entitled to make. It also
 * stays a third of `testTimeout`, which is what keeps this bound useful: a
 * query for something genuinely absent still fails as a QUERY, inside its
 * test, naming the element — not as a hard timeout that names nothing.
 *
 * The price, and it is real: a `findBy*` that will never resolve now spends
 * 10 s before saying so. That is the cost of a bound sized for the slowest
 * machine instead of this one, and it is paid only on the failing path.
 *
 * Not covered by the margin survey: the reporter measures whole TESTS against
 * `testTimeout`, so erosion against THIS bound is only ever visible indirectly,
 * as a test whose total duration climbs. Any single test over ~1 s is, by
 * definition, one whose waits are no longer trivially inside the old default.
 */
configure({ asyncUtilTimeout: 10_000 })

afterEach(() => {
  cleanup()
})
