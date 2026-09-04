import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { MockInstance } from 'vitest'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { ApiResponseError, EXPENSIVE_SURFACE_BUSY } from '../apiError'
import { createIssueEventInvalidator, PLANNING_COALESCE_WINDOW_MS } from './issueEvents'
import { backlogViewKey, boardIssuesKey } from './queryKeys'

/**
 * **The SSE fan-out is the escalation path that crosses principals.**
 *
 * One authenticated member with `issue.update`, PATCHing a single issue in a
 * loop inside their own cheap write budget, publishes `ISSUE_UPDATED` to every
 * connected member of the workspace. The old handler answered each one by
 * invalidating everything under `projectIssuesKeyPrefix` — which includes the
 * planning aggregate, a `12 + N`-statement read on the throttled expensive
 * surface. So the abuser spent the *victims'* budgets, ten open Backlog tabs
 * against six surface permits pinned the whole expensive-read share
 * instance-wide, and when the refetch was refused the NEXT event provoked it
 * again immediately, against an instance already refusing. There was no backoff
 * anywhere in that loop.
 *
 * These tests are about the two rules that bound it, and both are negatives:
 * a burst does not cost one aggregate per event, and a surface that has just
 * said no is not asked again. The positive control — the cheap lists still
 * refreshing instantly, every time — is asserted beside them, because a build
 * that simply stopped invalidating anything would otherwise pass.
 */

const WS = 'w1'
const P = 'p1'
const OTHER = 'p2'

let qc: QueryClient
let invalidateSpy: MockInstance
/** Every observer this test mounted, torn down after it. */
let subscriptions: (() => void)[] = []

/** Every planning key this client has been asked to invalidate. */
function planningInvalidations(): number {
  return invalidateSpy.mock.calls.filter(([filters]) => {
    const key = (filters as { queryKey?: readonly unknown[] })?.queryKey
    return Array.isArray(key) && key[3] === 'backlogView'
  }).length
}

/** …and every invalidation that deliberately excludes the planning namespace. */
function cheapInvalidations(): number {
  return invalidateSpy.mock.calls.filter(([filters]) =>
    typeof (filters as { predicate?: unknown })?.predicate === 'function').length
}

/**
 * Mount an observer on a key — the test's stand-in for "a Backlog tab is open on
 * this exact view". `enabled: false` so subscribing fetches nothing; an observer
 * counts towards `getObserversCount()` either way, which is the only thing the
 * skip predicate asks about.
 */
function observe(key: readonly unknown[]) {
  const observer = new QueryObserver(qc, { queryKey: key, enabled: false })
  subscriptions.push(observer.subscribe(() => {}))
}

/**
 * Put a planning entry into the state a refusal leaves behind, WITH somebody
 * looking at it. The observer is not decoration: the skip is scoped to observed
 * queries on purpose, so a seed without one describes an abandoned cache entry
 * and asserts the wrong thing.
 */
function seedRefusedView(error: unknown, key: readonly unknown[] = backlogViewKey(WS, P)) {
  qc.setQueryData(key, { sprints: [], backlog: null })
  observe(key)
  const query = qc.getQueryCache().find({ queryKey: key })!
  query.setState({ status: 'error', error, fetchStatus: 'idle' } as never)
}

beforeEach(() => {
  qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
  subscriptions = []
})

afterEach(() => {
  subscriptions.forEach(unsubscribe => unsubscribe())
  vi.useRealTimers()
})

describe('the cheap lists are never delayed', () => {
  it('invalidates everything EXCEPT the planning view on every single event', () => {
    const inv = createIssueEventInvalidator(qc, { now: () => 0 })
    inv.issueChanged(WS, P)
    inv.issueChanged(WS, P)
    inv.issueChanged(WS, P)

    // The board, the pickers and the paginated list are ordinary reads on no
    // throttled surface. Coalescing them would be paying a cost this module
    // exists to avoid paying.
    expect(cheapInvalidations()).toBe(3)
    inv.dispose()
  })

  it('excludes planning keys by shape, not by the caller remembering to', () => {
    const inv = createIssueEventInvalidator(qc, { now: () => 0 })
    inv.issueChanged(WS, P)
    const [filters] = invalidateSpy.mock.calls[0] as [{ predicate: (q: unknown) => boolean }]
    const predicate = filters.predicate

    expect(predicate({ queryKey: boardIssuesKey(WS, P) })).toBe(true)
    expect(predicate({ queryKey: backlogViewKey(WS, P) })).toBe(false)
    // Every filtered planning entry, not just the unfiltered one.
    expect(predicate({ queryKey: backlogViewKey(WS, P, { includeDone: true }) })).toBe(false)
    inv.dispose()
  })
})

describe('a burst costs two aggregates, not one per event', () => {
  it('fires on the leading edge and coalesces the rest into one trailing run', () => {
    vi.useFakeTimers()
    let clock = 0
    const inv = createIssueEventInvalidator(qc, { now: () => clock })

    inv.issueChanged(WS, P)
    expect(planningInvalidations()).toBe(1)   // the first event is still immediate

    for (let i = 1; i <= 50; i++) {
      clock = i * 10
      inv.issueChanged(WS, P)
    }
    // Fifty more events inside the window bought exactly nothing extra…
    expect(planningInvalidations()).toBe(1)

    clock = PLANNING_COALESCE_WINDOW_MS
    vi.advanceTimersByTime(PLANNING_COALESCE_WINDOW_MS)
    // …and one trailing run at the end of it, so the view still converges when
    // a burst stops. Two aggregates for fifty-one events.
    expect(planningInvalidations()).toBe(2)
    inv.dispose()
  })

  it('coalesces per project, so a noisy project cannot mute a quiet one', () => {
    let clock = 0
    const inv = createIssueEventInvalidator(qc, { now: () => clock })
    inv.issueChanged(WS, P)
    clock = 10
    inv.issueChanged(WS, OTHER)

    expect(planningInvalidations()).toBe(2)
    inv.dispose()
  })

  it('is a window, not a one-shot: a later event fires immediately again', () => {
    let clock = 0
    const inv = createIssueEventInvalidator(qc, { now: () => clock })
    inv.issueChanged(WS, P)
    clock = PLANNING_COALESCE_WINDOW_MS + 1
    inv.issueChanged(WS, P)

    expect(planningInvalidations()).toBe(2)
    inv.dispose()
  })
})

describe('a surface that has just refused is not asked again', () => {
  const REFUSALS: { name: string; error: () => unknown }[] = [
    {
      name: 'the per-minute planning budget (no errorType)',
      error: () => new ApiResponseError(429, 'Too many planning requests.'),
    },
    {
      name: EXPENSIVE_SURFACE_BUSY,
      error: () => new ApiResponseError(
        429, 'This instance is running as many expensive requests as it can at once.',
        undefined, undefined, { errorType: EXPENSIVE_SURFACE_BUSY }),
    },
    {
      name: '503 — saturation that never produced a 429',
      error: () => new ApiResponseError(503, 'Service Unavailable'),
    },
  ]

  for (const { name, error } of REFUSALS) {
    it(`skips the planning invalidation entirely — ${name}`, () => {
      seedRefusedView(error())
      const inv = createIssueEventInvalidator(qc, { now: () => 0 })

      inv.issueChanged(WS, P)

      // THE test. Re-asking a surface that just declined the work is the one
      // thing that must not happen, and there is no backoff in an SSE loop to
      // save us from doing it every few seconds forever.
      expect(planningInvalidations()).toBe(0)
      // The cheap half is untouched by the planning surface's troubles.
      expect(cheapInvalidations()).toBe(1)
      inv.dispose()
    })
  }

  it('skips at the TRAILING edge too, when the refusal lands mid-window', () => {
    vi.useFakeTimers()
    let clock = 0
    const inv = createIssueEventInvalidator(qc, { now: () => clock })
    inv.issueChanged(WS, P)
    expect(planningInvalidations()).toBe(1)

    clock = 100
    inv.issueChanged(WS, P)          // schedules the trailing run
    seedRefusedView(new ApiResponseError(429, 'Too many planning requests.'))

    clock = PLANNING_COALESCE_WINDOW_MS
    vi.advanceTimersByTime(PLANNING_COALESCE_WINDOW_MS)
    // Checked when it fires, not when it was scheduled: the surface started
    // refusing during the window it was waiting out.
    expect(planningInvalidations()).toBe(1)
    inv.dispose()
  })

  it('asks again once the view is healthy — the skip is a state, not a latch', () => {
    seedRefusedView(new ApiResponseError(429, 'Too many planning requests.'))
    let clock = 0
    const inv = createIssueEventInvalidator(qc, { now: () => clock })
    inv.issueChanged(WS, P)
    expect(planningInvalidations()).toBe(0)

    // The planner pressed Try again and it worked.
    qc.getQueryCache().find({ queryKey: backlogViewKey(WS, P) })!
      .setState({ status: 'success', error: null } as never)
    clock = PLANNING_COALESCE_WINDOW_MS + 1
    inv.issueChanged(WS, P)

    expect(planningInvalidations()).toBe(1)
    inv.dispose()
  })

  it('only counts a view somebody is LOOKING at — the skip is not a latch either', () => {
    // The planner tried `includeDone`, it was refused, and they toggled back. That
    // entry keeps its error for the whole `gcTime` (5 minutes) with no observer,
    // no reader and no *Try again* anywhere on screen — the manual retry lives on
    // the notice the mounted view renders. Counting it muted SSE refresh of the
    // healthy view now in front of them, which is the stuck-ON direction.
    qc.setQueryData(backlogViewKey(WS, P, { includeDone: true }), { sprints: [], backlog: null })
    qc.getQueryCache().find({ queryKey: backlogViewKey(WS, P, { includeDone: true }) })!
      .setState({ status: 'error', error: new ApiResponseError(429, 'Too many planning requests.'),
        fetchStatus: 'idle' } as never)
    // …and the view actually on screen is fine.
    qc.setQueryData(backlogViewKey(WS, P), { sprints: [], backlog: null })
    observe(backlogViewKey(WS, P))

    const inv = createIssueEventInvalidator(qc, { now: () => 0 })
    inv.issueChanged(WS, P)

    expect(planningInvalidations()).toBe(1)
    inv.dispose()
  })

  it('still skips when the refused view is the observed one, filters and all', () => {
    // The mirror of the test above, and the reason it cannot simply match the
    // unfiltered key: a refusal on the view being READ must still skip, whatever
    // that view is filtered by.
    seedRefusedView(new ApiResponseError(429, 'Too many planning requests.'),
      backlogViewKey(WS, P, { includeDone: true }))
    const inv = createIssueEventInvalidator(qc, { now: () => 0 })

    inv.issueChanged(WS, P)

    expect(planningInvalidations()).toBe(0)
    inv.dispose()
  })

  it('is not fooled by a 500 or a 404 — those are not capacity failures', () => {
    seedRefusedView(new ApiResponseError(500, 'Something went wrong'))
    const inv = createIssueEventInvalidator(qc, { now: () => 0 })

    inv.issueChanged(WS, P)

    // The positive control for every skip above: a genuine bug on one request
    // leaves the view worth refreshing, and a build that skipped on ANY error
    // would quietly stop refreshing planning views after one blip.
    expect(planningInvalidations()).toBe(1)
    inv.dispose()
  })
})

describe('unmount', () => {
  it('drops a pending trailing invalidation', () => {
    vi.useFakeTimers()
    let clock = 0
    const inv = createIssueEventInvalidator(qc, { now: () => clock })
    inv.issueChanged(WS, P)
    clock = 100
    inv.issueChanged(WS, P)

    inv.dispose()
    clock = PLANNING_COALESCE_WINDOW_MS
    vi.advanceTimersByTime(PLANNING_COALESCE_WINDOW_MS)

    // A timer that outlives the top bar would invalidate against a cache the
    // user has since navigated away from — or, after a sign-out, someone else's.
    expect(planningInvalidations()).toBe(1)
  })
})
