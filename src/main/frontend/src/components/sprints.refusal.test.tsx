import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ApiResponseError, EXPENSIVE_SURFACE_BUSY, TOO_MANY_IN_FLIGHT } from '../apiError'
import { backlogViewKeyPrefix } from '../lib/queryKeys'
import { BACKLOG_SECTION, useBacklogView } from './sprints'
import type { BacklogViewOptions } from '../api'
import type { BacklogSectionResponse, BacklogView, Sprint } from '../types'

/**
 * **HD-174 §5.4 — a refusal must never cost more than the thing it refused.**
 *
 * `refreshSection`'s catch used to invalidate the planning VIEW key on any
 * error, which refetches `GET …/backlog`: a `12 + N`-statement read on a single
 * connection (32 at `AGILE_MAX_OPEN_SPRINTS=20`) where the section endpoint it
 * was answering for is 11–12. Fine for a network blip. Exactly wrong for a 429,
 * which is the server saying it has no room for the *cheap* read — answering
 * that by issuing the *expensive* one costs the instance one aggregate per
 * refused section refresh, per planner, precisely while it is saturated.
 *
 * **What these tests protect, stated exactly, because nothing runs `npm test` in
 * CI (HD-242) and an unrun test protects nothing.** They are a *local* seal on
 * one branch: that a 429 does not reach `invalidateQueries`, that all three
 * refusal shapes the planning surface can answer with take that branch (and, by
 * being asserted on the STATUS rather than on `errorType`, that a fourth one
 * nobody has written yet takes it too), and that a non-429 still escalates. They
 * do **not** prove the server refuses anything — `PlanningThrottleTest` and
 * `PlanningThrottleParityTest` own that half, and the two halves only meet in a
 * running system.
 *
 * The negative is the one that matters and the one that looks identical to
 * correct behaviour until the surface is actually busy: a wrong build and a
 * right build render the same section with the same rows, and differ only in a
 * request the user never sees.
 */

const WS = 'w1'
const P = 'p1'

const SPRINT: Sprint = {
  id: 'sp1', name: 'Sprint 7', goal: null, state: 'ACTIVE', sequence: 7,
  startAt: null, endAt: null, completedAt: null, daysRemaining: null,
  issueCount: 0, doneIssueCount: 0, points: null, donePoints: null, unestimatedCount: 0,
  createdAt: '2026-09-01T09:00:00Z', updatedAt: '2026-09-01T09:00:00Z',
}
const SPRINT_2: Sprint = { ...SPRINT, id: 'sp2', name: 'Sprint 8', state: 'FUTURE', sequence: 8 }

const STATS = { issueCount: 0, doneIssueCount: 0, points: null, donePoints: null, unestimatedCount: 0 }

function view(): BacklogView {
  return {
    sprints: [
      { sprint: SPRINT, issues: [], truncated: false, totalAvailable: 0, stats: { ...STATS } },
      { sprint: SPRINT_2, issues: [], truncated: false, totalAvailable: 0, stats: { ...STATS } },
    ],
    backlog: { issues: [], truncated: false, totalAvailable: 0, stats: { ...STATS } },
    sectionCap: 300,
    bulkMoveCap: 100,
  }
}

function section(): BacklogSectionResponse {
  return {
    sprint: SPRINT, issues: [], truncated: false, totalAvailable: 0,
    stats: { ...STATS }, sectionCap: 300, bulkMoveCap: 100,
  }
}

/**
 * The three shapes the planning surface can refuse with (HD-174 §8.1), built the
 * way `request()` builds them. They share status 429 and are told apart by
 * `errorType` — the budget one carrying none at all, deliberately.
 */
const REFUSALS: { name: string; error: () => ApiResponseError }[] = [
  {
    name: 'the per-minute planning budget (no errorType)',
    error: () => new ApiResponseError(
      429, 'Too many planning requests — try again shortly.',
      undefined, undefined, { retryAfter: 37 }),
  },
  {
    name: TOO_MANY_IN_FLIGHT,
    error: () => new ApiResponseError(
      429, 'Too many of your requests are running at once — wait for one to finish.',
      undefined, undefined, { retryAfter: 1, errorType: TOO_MANY_IN_FLIGHT }),
  },
  {
    name: EXPENSIVE_SURFACE_BUSY,
    error: () => new ApiResponseError(
      429, 'This instance is running as many expensive requests as it can at once. Try again in a moment.',
      undefined, undefined, { retryAfter: 1, errorType: EXPENSIVE_SURFACE_BUSY }),
  },
]

const getView = vi.fn(async () => view())
const getSection = vi.fn(async (..._a: unknown[]) => section())

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetBacklogView: (...a: unknown[]) => getView(...(a as [])),
  apiGetBacklogSection: (...a: unknown[]) => getSection(...(a as [])),
}))

beforeEach(() => {
  getView.mockClear()
  getView.mockImplementation(async () => view())
  getSection.mockClear()
  getSection.mockImplementation(async () => section())
})

function wrapper(qc: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  )
}

async function mounted() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const hook = renderHook(() => useBacklogView(WS, P, {}), { wrapper: wrapper(qc) })
  await waitFor(() => expect(hook.result.current.data).toBeTruthy())
  // The mount's own aggregate fetch. Everything below counts from here, so an
  // assertion of "1" means "no SECOND aggregate", never "no aggregate at all".
  expect(getView).toHaveBeenCalledTimes(1)
  // The client is handed back so a test can refetch the view the way something
  // OTHER than this hook would — the SSE fan-out, a window focus, a remount.
  return Object.assign(hook, { qc })
}

describe('a refused section refresh does not provoke the aggregate (HD-174 §5.4)', () => {
  for (const { name, error } of REFUSALS) {
    it(`does not invalidate the view key on a 429 — ${name}`, async () => {
      const hook = await mounted()
      getSection.mockRejectedValueOnce(error())

      await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })

      // The section was asked for exactly once and the aggregate not at all: the
      // 12-statement read was refused and the 32-statement one was NOT issued in
      // its place. This is the whole ticket.
      expect(getSection).toHaveBeenCalledTimes(1)
      expect(getView).toHaveBeenCalledTimes(1)
      // And no second attempt at the section either: `api.ts` has already spent
      // the single automatic retry it grants TOO_MANY_IN_FLIGHT, so by the time
      // the error is here, retrying is load rather than recovery.
      await new Promise(r => setTimeout(r, 20))
      expect(getSection).toHaveBeenCalledTimes(1)
      expect(getView).toHaveBeenCalledTimes(1)

      // Silence would be its own bug — the section is showing rows the planner
      // just changed. It is marked stale with the SERVER's sentence, so nothing
      // paraphrases a refusal into a remedy its reader may not be able to perform.
      expect(hook.result.current.staleSections[SPRINT.id]).toBe(error().detail)
      // The stale marker names one section; the others are untouched.
      expect(hook.result.current.staleSections[BACKLOG_SECTION]).toBeUndefined()
    })
  }

  it('marks the remaining sections stale instead of asking for them', async () => {
    // A cross-section drag refreshes two sections. If the first is refused, the
    // second is one more request onto a surface that is already refusing — so it
    // is not sent, and the section says so rather than looking current.
    const hook = await mounted()
    getSection.mockRejectedValueOnce(REFUSALS[2].error())

    await act(async () => {
      await hook.result.current.refreshSections([SPRINT.id, BACKLOG_SECTION])
    })

    expect(getSection).toHaveBeenCalledTimes(1)
    expect(getView).toHaveBeenCalledTimes(1)
    const detail = REFUSALS[2].error().detail
    expect(hook.result.current.staleSections[SPRINT.id]).toBe(detail)
    expect(hook.result.current.staleSections[BACKLOG_SECTION]).toBe(detail)
  })

  it('clears the stale marker when the section is successfully refreshed', async () => {
    const hook = await mounted()
    getSection.mockRejectedValueOnce(REFUSALS[1].error())
    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })
    expect(hook.result.current.staleSections[SPRINT.id]).toBeTruthy()

    // "Try again" — the manual affordance, and still no aggregate anywhere in
    // the recovery path.
    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })
    expect(hook.result.current.staleSections[SPRINT.id]).toBeUndefined()
    expect(getView).toHaveBeenCalledTimes(1)
  })
})

/**
 * **Saturation does not always produce a 429**, and the guard above is written
 * about the refusal alone.
 *
 * The failure a busy instance hands back for a reason *outside* the planning
 * surface — the rest of the pool exhausted by writes, the pool's acquisition
 * timeout expiring, an edge that stopped waiting — is a 5xx. In that
 * state a refused section refresh still provoked the `12 + N`-statement
 * aggregate, per section, per planner, which is the same escalation arriving
 * through a status nobody had listed.
 *
 * 500 is deliberately absent from this table and present in the one below it:
 * a bug on one request is worth re-asking about, an overloaded server is not.
 */
const OVERLOADED = [
  { status: 502, name: 'an edge that could not reach the app' },
  { status: 503, name: 'the instance shedding load' },
  { status: 504, name: 'an edge that stopped waiting' },
]

describe('a saturated instance is not escalated to either (HD-174, extended)', () => {
  for (const { status, name } of OVERLOADED) {
    it(`does not invalidate the view key on a ${status} — ${name}`, async () => {
      const hook = await mounted()
      getSection.mockRejectedValueOnce(new ApiResponseError(status, 'Service Unavailable'))

      await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })

      expect(getSection).toHaveBeenCalledTimes(1)
      expect(getView).toHaveBeenCalledTimes(1)
      await new Promise(r => setTimeout(r, 20))
      expect(getView).toHaveBeenCalledTimes(1)

      // Marked stale like a refusal — but NOT with the server's sentence: an
      // edge's body is an HTML page or nothing, and `Service Unavailable` is a
      // status line. The two states are one branch and two sentences.
      const said = hook.result.current.staleSections[SPRINT.id]
      expect(said).toBeTruthy()
      expect(said).not.toBe('Service Unavailable')
      expect(said).toMatch(/overloaded/)
      expect(hook.result.current.sectionError).toBe('')
    })
  }

  it('stops the two-section loop as a refusal does', async () => {
    const hook = await mounted()
    getSection.mockRejectedValueOnce(new ApiResponseError(503, 'Service Unavailable'))

    await act(async () => {
      await hook.result.current.refreshSections([SPRINT.id, BACKLOG_SECTION])
    })

    expect(getSection).toHaveBeenCalledTimes(1)
    expect(getView).toHaveBeenCalledTimes(1)
    expect(hook.result.current.staleSections[BACKLOG_SECTION]).toBeTruthy()
  })
})

/**
 * **A marker that only its own button can clear is a latch, and it says something
 * that has stopped being true.**
 *
 * `staleSections` was written into by the refusal branch and cleared in exactly
 * one place — a successful per-section refresh. So refuse a section, then let the
 * aggregate come back by any OTHER path (an SSE invalidation, a window focus, a
 * filter change, a remount) and the section went on warning "these rows may be
 * out of date" over rows that were the server's latest. The hook's own contract —
 * *a section in here is showing data it could not confirm* — was false, and the
 * only way to make the warning go away was to press a button that asked the
 * server for something it had already sent.
 *
 * It fails safe, which is exactly why nobody would ever notice it by hand: the
 * screen is correct and the sentence over it is not. These tests are the whole
 * reason it cannot come back.
 */
describe('a stale marker ends when the server answers (HD-174 round 2)', () => {
  it('clears when the AGGREGATE refetches successfully, not just its own button', async () => {
    const hook = await mounted()
    getSection.mockRejectedValueOnce(REFUSALS[0].error())
    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })
    expect(hook.result.current.staleSections[SPRINT.id]).toBeTruthy()

    // Anything at all that refetches the view — here the path that made this a
    // latch in production: somebody ELSE's refetch of the same aggregate.
    await act(async () => {
      await hook.qc.refetchQueries({ queryKey: backlogViewKeyPrefix(WS, P) })
    })

    await waitFor(() => expect(getView).toHaveBeenCalledTimes(2))
    // The rows on screen are now the server's latest — for EVERY section, since
    // one aggregate answers for all of them. A warning over them is a lie.
    await waitFor(() => expect(hook.result.current.staleSections[SPRINT.id]).toBeUndefined())
  })

  it('does NOT clear when the refetch fails — the marker tracks the answer, not the attempt', async () => {
    const hook = await mounted()
    getSection.mockRejectedValueOnce(REFUSALS[0].error())
    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })
    expect(hook.result.current.staleSections[SPRINT.id]).toBeTruthy()

    // The positive control for the test above: a build that cleared on every
    // settled fetch, or on `dataUpdatedAt` moving for any reason, would wipe the
    // warning here — while the rows are still exactly as unconfirmed as before.
    getView.mockRejectedValueOnce(new ApiResponseError(503, 'Service Unavailable'))
    await act(async () => {
      await hook.qc.refetchQueries({ queryKey: backlogViewKeyPrefix(WS, P) }).catch(() => {})
    })

    expect(hook.result.current.staleSections[SPRINT.id]).toBeTruthy()
  })

  it('drops markers when the FILTERS change — a different question, never refused', async () => {
    // The map is component state and the key is not, so a marker written under one
    // filter set outlived the view it described. Isolated from the rule above by
    // making the new question FAIL: if the marker still goes, only the key can
    // have cleared it.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const hook = renderHook(
      ({ f }: { f: BacklogViewOptions }) => useBacklogView(WS, P, f),
      { wrapper: wrapper(qc), initialProps: { f: {} as BacklogViewOptions } },
    )
    await waitFor(() => expect(hook.result.current.data).toBeTruthy())
    getSection.mockRejectedValueOnce(REFUSALS[0].error())
    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })
    expect(hook.result.current.staleSections[SPRINT.id]).toBeTruthy()

    getView.mockRejectedValueOnce(new ApiResponseError(503, 'Service Unavailable'))
    await act(async () => { hook.rerender({ f: { includeDone: true } }) })

    await waitFor(() => expect(hook.result.current.staleSections[SPRINT.id]).toBeUndefined())
  })

  it('does not let one section’s answer clear another section’s marker', async () => {
    // `refreshSections` marks the rest of the loop stale in the same gesture that
    // refuses the first. Clearing on `dataUpdatedAt` would break this: the
    // successful patch below is written with `setQueryData`, which advances that
    // timestamp — so A's recovery would silently clear B, which nobody confirmed.
    const hook = await mounted()
    getSection.mockRejectedValueOnce(REFUSALS[2].error())
    await act(async () => {
      await hook.result.current.refreshSections([SPRINT.id, BACKLOG_SECTION])
    })
    expect(hook.result.current.staleSections[BACKLOG_SECTION]).toBeTruthy()

    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })

    expect(hook.result.current.staleSections[SPRINT.id]).toBeUndefined()
    expect(hook.result.current.staleSections[BACKLOG_SECTION]).toBeTruthy()
  })
})

describe('every other failure keeps escalating, deliberately', () => {
  it('invalidates the view key on a 500 — a blip is not the server declining work', async () => {
    const hook = await mounted()
    getSection.mockRejectedValueOnce(new ApiResponseError(500, 'Something went wrong'))

    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })

    // The positive control for the tests above: the same catch, the same call,
    // and here the aggregate IS refetched. Without this pair, a build that never
    // invalidated at all would pass everything above.
    await waitFor(() => expect(getView).toHaveBeenCalledTimes(2))
    expect(hook.result.current.sectionError).toBe('Something went wrong')
    expect(hook.result.current.staleSections[SPRINT.id]).toBeUndefined()
  })

  it('invalidates the view key on a 404 without reporting an error', async () => {
    const hook = await mounted()
    getSection.mockRejectedValueOnce(new ApiResponseError(404, 'Sprint not found'))

    await act(async () => { await hook.result.current.refreshSection(SPRINT.id) })

    // A vanished section is dropped by refetching the view; nobody is told that
    // something went wrong, because nothing did.
    await waitFor(() => expect(getView).toHaveBeenCalledTimes(2))
    expect(hook.result.current.sectionError).toBe('')
    expect(hook.result.current.staleSections[SPRINT.id]).toBeUndefined()
  })
})
