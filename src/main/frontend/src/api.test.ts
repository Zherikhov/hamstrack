import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import {
  apiGetBacklogSection, apiGetWorkspaceStorageByProject, apiMe, apiRankIssue, apiSearch,
  apiSearchSchema, apiUpdateIssue, ApiResponseError, savedFilters,
  filenameFromDisposition, reportCsvPath,
  TOO_MANY_IN_FLIGHT, EXPENSIVE_SURFACE_BUSY,
} from './api'
import type { RankIssuePayload, UpdateIssuePayload } from './api'
import { useAuthStore } from './auth'

// Exercises the shared request()/authFetch() plumbing through a public wrapper
// (apiMe → GET /api/auth/me). We assert two things the audit called out:
//   1. a 401 triggers exactly ONE single-flight refresh, then retries the request
//   2. request()'s error path never surfaces an empty message

type FetchFn = typeof globalThis.fetch

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

// A 401 with no JSON body — statusText is blank over HTTP/2, so this is the
// "silent error" case the fallback message guards against.
function emptyResponse(status: number): Response {
  return new Response(null, { status })
}

const originalFetch = globalThis.fetch

beforeEach(() => {
  // Start each test signed in with a known token; queryClient.clear() on
  // clear() is a no-op for these tests.
  useAuthStore.setState({ user: null, accessToken: 'old-token', initialized: true })
  sessionStorage.clear()
})

afterEach(() => {
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

describe('request() single-flight refresh on 401', () => {
  it('refreshes exactly once, then retries with the new token and returns the body', async () => {
    const me = { id: 'u1', email: 'a@b.com', displayName: 'A' }
    const fetchMock = vi.fn<FetchFn>(async (input) => {
      const url = String(input)
      if (url.endsWith('/api/auth/me')) {
        // First hit 401s (expired token); the post-refresh retry succeeds.
        const token = useAuthStore.getState().accessToken
        return token === 'new-token' ? jsonResponse(200, me) : emptyResponse(401)
      }
      if (url.endsWith('/api/auth/refresh')) {
        return jsonResponse(200, { accessToken: 'new-token' })
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    globalThis.fetch = fetchMock

    const result = await apiMe()

    expect(result).toEqual(me)
    // The store advanced to the rotated token.
    expect(useAuthStore.getState().accessToken).toBe('new-token')

    const calls = fetchMock.mock.calls.map(c => String(c[0]))
    // Exactly one refresh; /me hit twice (original 401 + retry).
    expect(calls.filter(u => u.endsWith('/api/auth/refresh'))).toHaveLength(1)
    expect(calls.filter(u => u.endsWith('/api/auth/me'))).toHaveLength(2)
  })

  it('dedupes concurrent 401s into a single refresh (single-flight)', async () => {
    const me = { id: 'u1', email: 'a@b.com', displayName: 'A' }
    let refreshCount = 0
    const fetchMock = vi.fn<FetchFn>(async (input) => {
      const url = String(input)
      if (url.endsWith('/api/auth/me')) {
        const token = useAuthStore.getState().accessToken
        return token === 'new-token' ? jsonResponse(200, me) : emptyResponse(401)
      }
      if (url.endsWith('/api/auth/refresh')) {
        refreshCount++
        // Small async gap so both callers land while the refresh is in flight.
        await Promise.resolve()
        useAuthStore.getState() // touch store
        return jsonResponse(200, { accessToken: 'new-token' })
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    globalThis.fetch = fetchMock

    const [a, b] = await Promise.all([apiMe(), apiMe()])

    expect(a).toEqual(me)
    expect(b).toEqual(me)
    // Two concurrent 401s, but only ONE refresh round-trip.
    expect(refreshCount).toBe(1)
  })

  it('clears the session when the refresh itself fails', async () => {
    const cleared = vi.fn()
    // Spy on clear via a real state slice: replace clear with a tracking fn.
    const realClear = useAuthStore.getState().clear
    useAuthStore.setState({ clear: () => { cleared(); realClear() } })

    const fetchMock = vi.fn<FetchFn>(async (input) => {
      const url = String(input)
      if (url.endsWith('/api/auth/me')) return emptyResponse(401)
      if (url.endsWith('/api/auth/refresh')) return emptyResponse(401)
      throw new Error(`unexpected fetch: ${url}`)
    })
    globalThis.fetch = fetchMock

    await expect(apiMe()).rejects.toBeInstanceOf(ApiResponseError)
    expect(cleared).toHaveBeenCalled()
    expect(useAuthStore.getState().accessToken).toBeNull()
  })
})

describe('request() error normalization', () => {
  it('prefers the problem-detail "detail" field for the error message', async () => {
    const fetchMock = vi.fn<FetchFn>(async () =>
      jsonResponse(400, { detail: 'Title is required' }),
    )
    globalThis.fetch = fetchMock

    await expect(apiMe()).rejects.toMatchObject({
      status: 400,
      detail: 'Title is required',
      message: 'Title is required',
    })
  })

  it('never surfaces an empty message when the body and statusText are blank', async () => {
    // 500 with no JSON body and blank statusText (HTTP/2) — must fall back.
    const fetchMock = vi.fn<FetchFn>(async () => emptyResponse(500))
    globalThis.fetch = fetchMock

    let caught: unknown
    try {
      await apiMe()
    } catch (e) {
      caught = e
    }
    expect(caught).toBeInstanceOf(ApiResponseError)
    const err = caught as ApiResponseError
    expect(err.status).toBe(500)
    expect(err.detail).not.toBe('')
    expect(err.detail).toContain('500')
  })

  // HD-171 §11: a validation 400 names the field it refused, in an `errors`
  // ProblemDetail extension. Dropping it leaves a form with nothing but a
  // banner, i.e. "something was wrong" for a refusal that said exactly what.
  it('carries the validation 400 errors map through as {field: message}', async () => {
    globalThis.fetch = vi.fn<FetchFn>(async () =>
      jsonResponse(400, {
        detail: 'newPassword: size must be between 8 and 72',
        errors: { newPassword: 'size must be between 8 and 72' },
      }),
    )

    await expect(apiMe()).rejects.toMatchObject({
      status: 400,
      errors: { newPassword: 'size must be between 8 and 72' },
    })
  })

  it('leaves errors undefined when the body has none, and never coerces a non-string', async () => {
    globalThis.fetch = vi.fn<FetchFn>(async () =>
      // A 422 business-rule refusal names no field; and a value that is not a
      // string must be dropped rather than rendered as "[object Object]" under
      // an input.
      jsonResponse(422, { detail: 'That password is 84 bytes…', errors: { bad: { nested: 1 } } }),
    )

    const err = await apiMe().then(() => null, (e: unknown) => e as ApiResponseError)
    expect(err).toBeInstanceOf(ApiResponseError)
    expect(err?.status).toBe(422)
    expect(err?.errors).toBeUndefined()
  })
})

// HD-22 §4.4: `sprintId` (move into a sprint) and `clearSprint` (return to the
// ranked backlog) answer the same question, so the server answers 400 when both
// arrive — it does NOT let one win silently. The client must be structurally
// unable to emit that payload: the types forbid it at every call site (the casts
// below are what it takes to even express it), and this guard is the runtime
// twin for anything assembled dynamically.
describe('issue writes: sprintId and clearSprint are mutually exclusive', () => {
  it('throws before the request instead of letting the server 400', async () => {
    const fetchMock = vi.fn<FetchFn>(async () => jsonResponse(200, {}))
    globalThis.fetch = fetchMock

    const both = { sprintId: 'sp1', clearSprint: true } as unknown as UpdateIssuePayload
    await expect(apiUpdateIssue('w1', 'p1', 7, both)).rejects.toThrow(/mutually exclusive/)
    await expect(apiRankIssue('w1', 'p1', 7, both as RankIssuePayload)).rejects.toThrow(/mutually exclusive/)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('lets each of them through on its own', async () => {
    const fetchMock = vi.fn<FetchFn>(async () => jsonResponse(200, { id: 'i1' }))
    globalThis.fetch = fetchMock

    await apiUpdateIssue('w1', 'p1', 7, { sprintId: 'sp1' })
    await apiUpdateIssue('w1', 'p1', 7, { clearSprint: true })
    await apiRankIssue('w1', 'p1', 7, { afterIssueId: 'i2', sprintId: 'sp1' })
    await apiRankIssue('w1', 'p1', 7, { afterIssueId: 'i2', clearSprint: true })
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  /**
   * The runtime guard above is the SECOND line of defence; the first is the type
   * itself, and a type has no runtime trace to assert on. `@ts-expect-error` is the
   * assertion: if the union is ever loosened so both keys become legal, these lines
   * stop being errors and `tsc -b` fails with "unused '@ts-expect-error'" — i.e. the
   * build breaks the moment the compile-time half regresses, which is exactly when
   * a dynamically-assembled payload would start reaching the server as a 400.
   */
  it('is rejected by the TYPES too, not only at runtime', () => {
    // @ts-expect-error — sprintId and clearSprint are mutually exclusive by type.
    const bothOnUpdate: UpdateIssuePayload = { sprintId: 'sp1', clearSprint: true }
    // @ts-expect-error — …and on the rank payload, which shares the same union.
    const bothOnRank: RankIssuePayload = { afterIssueId: 'i2', sprintId: 'sp1', clearSprint: true }

    // Each on its own type-checks — the union forbids the PAIR, not the fields.
    const idOnly: UpdateIssuePayload = { sprintId: 'sp1' }
    const clearOnly: UpdateIssuePayload = { clearSprint: true }
    expect([bothOnUpdate, bothOnRank, idOnly, clearOnly]).toHaveLength(4)
  })
})

/**
 * HD-141 (R7) — the report CSV link.
 *
 * Two facts worth pinning. The path is the report's own path with `.csv` on the
 * name and the SAME parameters, so the file and the chart are the same query;
 * and the filename that comes back off `Content-Disposition` is a **name**, not
 * a path — the one place a server string becomes something the browser saves.
 */
describe('report CSV', () => {
  it('is the report path plus .csv, with the report’s own parameters', () => {
    expect(reportCsvPath('w1', 'p1', 'flow', { from: '2026-05-01', interval: 'WEEK' }))
      .toBe('/workspaces/w1/projects/p1/reports/flow.csv?from=2026-05-01&interval=WEEK')
  })

  it('drops empty parameters rather than sending them blank', () => {
    expect(reportCsvPath('w1', 'p1', 'aging', { typeId: '' }))
      .toBe('/workspaces/w1/projects/p1/reports/aging.csv')
  })

  it('reads a quoted filename, and an RFC 5987 one', () => {
    expect(filenameFromDisposition('attachment; filename="flow-PAY.csv"')).toBe('flow-PAY.csv')
    expect(filenameFromDisposition("attachment; filename*=UTF-8''fl%C3%B8w.csv")).toBe('fløw.csv')
    expect(filenameFromDisposition('attachment; filename=plain.csv')).toBe('plain.csv')
  })

  it('never lets a filename be a path', () => {
    expect(filenameFromDisposition('attachment; filename="../../etc/passwd"')).toBe('passwd')
    expect(filenameFromDisposition('attachment; filename="C:\\temp\\x.csv"')).toBe('x.csv')
  })

  it('falls back to the caller’s name when the header says nothing usable', () => {
    expect(filenameFromDisposition(null)).toBeNull()
    expect(filenameFromDisposition('attachment')).toBeNull()
  })
})

/**
 * HD-96 — the planning surface sends no page size, and that absence is the fix.
 *
 * The defect was a refresh asking `GET …/issues` for `size = sectionCap`: the
 * server narrowed it to 100 and answered 200, so a section of 101–300 issues
 * rendered whole and came back cut with no signal. Reading the cap off the
 * server's own response was the right instinct and did not save it. These pin
 * the property that replaces the instinct — there is no size on the wire to be
 * wrong about — rather than any one of today's call sites.
 */
describe('backlog section fetch', () => {
  const SECTION = {
    sprint: null, issues: [], truncated: false, totalAvailable: 0,
    stats: { issueCount: 0, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 0 },
    sectionCap: 300, bulkMoveCap: 100,
  }
  const urlOf = (fetchMock: { mock: { calls: unknown[][] } }) =>
    new URL(String(fetchMock.mock.calls[0][0]), 'http://test.local')

  it('carries no page and no size, whatever else the view is filtered by', async () => {
    const fetchMock = vi.fn<FetchFn>(async () => jsonResponse(200, SECTION))
    globalThis.fetch = fetchMock

    await apiGetBacklogSection('w1', 'p1', null, {
      statusId: 's1', priorityId: 'pr1', componentId: 'c1', fixVersionId: 'v1',
      labelIds: ['l1', 'l2'], labelMatch: 'all', includeDone: true,
    })

    const url = urlOf(fetchMock)
    expect(url.searchParams.get('size')).toBeNull()
    expect(url.searchParams.get('page')).toBeNull()
    // …and the filters the render used still travel, or the refresh would answer
    // a different question than the view asked.
    expect(url.searchParams.get('statusId')).toBe('s1')
    expect(url.searchParams.getAll('labelId')).toEqual(['l1', 'l2'])
    expect(url.searchParams.get('labelMatch')).toBe('all')
  })

  it('addresses the backlog by a literal segment and a sprint by its id', async () => {
    const fetchMock = vi.fn<FetchFn>(async () => jsonResponse(200, SECTION))
    globalThis.fetch = fetchMock

    await apiGetBacklogSection('w1', 'p1', null)
    expect(urlOf(fetchMock).pathname).toBe('/api/workspaces/w1/projects/p1/backlog/sections/backlog')

    fetchMock.mockClear()
    await apiGetBacklogSection('w1', 'p1', 'sp-7')
    expect(urlOf(fetchMock).pathname).toBe('/api/workspaces/w1/projects/p1/backlog/sections/sp-7')
    // The section is named by the PATH, so re-sending it as a list filter would
    // be a second answer to a question the URL has already settled.
    expect(urlOf(fetchMock).searchParams.get('sprintId')).toBeNull()
    expect(urlOf(fetchMock).searchParams.get('noSprint')).toBeNull()
  })

  it('sends includeDone to the backlog section only', async () => {
    const fetchMock = vi.fn<FetchFn>(async () => jsonResponse(200, SECTION))
    globalThis.fetch = fetchMock

    await apiGetBacklogSection('w1', 'p1', null, { includeDone: true })
    expect(urlOf(fetchMock).searchParams.get('includeDone')).toBe('true')

    fetchMock.mockClear()
    // A sprint keeps its DONE issues unconditionally — they are its record of
    // what it delivered, so the knob does not apply and is not sent.
    await apiGetBacklogSection('w1', 'p1', 'sp-7', { includeDone: true })
    expect(urlOf(fetchMock).searchParams.get('includeDone')).toBeNull()
  })
})

// ── HD-182: three refusals share status 429, and only one may be re-sent ──────
//
// The expensive-read surface (search, insights, saved filters, the storage
// breakdown, reports) now answers 429 for three different reasons, told apart by
// `errorType` and by nothing else. `request()` retries exactly one of them, once.
//
// The negative cases are the ones that matter here: an over-eager retry is
// indistinguishable from correct behaviour until the surface is actually
// saturated, at which point the client is amplifying the shortage it was told
// about. Nothing in CI runs these tests (HD-242), so they exist for a reviewer.

/** A 429 as the server writes it: `errorType` in the body, `Retry-After` in the header. */
function throttledResponse(errorType: string | null, retryAfter: string): Response {
  const body: Record<string, string> = { detail: 'Refused.' }
  if (errorType) body.errorType = errorType
  return new Response(JSON.stringify(body), {
    status: 429,
    headers: { 'Content-Type': 'application/json', 'Retry-After': retryAfter },
  })
}

/**
 * Advances past the one-second wait `request()` honours before its retry, so the
 * suite does not spend a real second per case. Chunked, because the timer is
 * scheduled by a promise chain that has to settle first.
 */
async function runThroughTheRetryWait(): Promise<void> {
  for (let i = 0; i < 10; i++) await vi.advanceTimersByTimeAsync(200)
}

describe('the one 429 that is retried is chosen by errorType, not by the status', () => {
  const SCHEMA = { fields: [] }

  afterEach(() => {
    vi.useRealTimers()
  })

  it('retries a TOO_MANY_IN_FLIGHT GET exactly once, then returns the second answer', async () => {
    vi.useFakeTimers()
    let calls = 0
    globalThis.fetch = vi.fn<FetchFn>(async () => {
      calls++
      return calls === 1
        ? throttledResponse(TOO_MANY_IN_FLIGHT, '1')
        : jsonResponse(200, SCHEMA)
    })

    const promise = apiSearchSchema('w1')
    await runThroughTheRetryWait()

    await expect(promise).resolves.toEqual(SCHEMA)
    expect(calls).toBe(2)
  })

  it('surfaces the refusal when the single retry is refused too — and does not go again', async () => {
    // The whole risk of a silent retry is that it hides a real refusal. It must
    // stop at two attempts and hand the second failure to the caller unchanged,
    // errorType and all, so the banner is the one the server wrote.
    vi.useFakeTimers()
    let calls = 0
    globalThis.fetch = vi.fn<FetchFn>(async () => {
      calls++
      return throttledResponse(TOO_MANY_IN_FLIGHT, '1')
    })

    // The handler is attached before the clock moves: the rejection happens
    // while we are advancing timers, and `expect(...).rejects` afterwards would
    // catch it a tick too late for Node to call it handled.
    const settled = apiSearchSchema('w1').then(() => null, (e: unknown) => e as ApiResponseError)
    await runThroughTheRetryWait()

    expect(await settled).toMatchObject({
      status: 429,
      errorType: TOO_MANY_IN_FLIGHT,
      detail: 'Refused.',
    })
    expect(calls).toBe(2)
  })

  it('NEVER retries EXPENSIVE_SURFACE_BUSY — the caller may hold no permit at all', async () => {
    // The negative case this block exists for. Retrying a full shared surface is
    // what makes it fuller, and the reader is not the one occupying it.
    const fetchMock = vi.fn<FetchFn>(async () =>
      throttledResponse(EXPENSIVE_SURFACE_BUSY, '1'))
    globalThis.fetch = fetchMock

    await expect(apiSearchSchema('w1')).rejects.toMatchObject({
      status: 429,
      errorType: EXPENSIVE_SURFACE_BUSY,
      // `Retry-After: 1` here means "the obstacle is a request that ends
      // shortly", not "the window has one second left" — it is carried to the
      // reader, never acted on by this client.
      retryAfter: 1,
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('NEVER retries the per-minute budget 429, which carries no errorType at all', async () => {
    const fetchMock = vi.fn<FetchFn>(async () => throttledResponse(null, '37'))
    globalThis.fetch = fetchMock

    const err = await apiSearchSchema('w1').then(() => null, (e: unknown) => e as ApiResponseError)
    // The wait is a window rolling, not a request ending — and the ABSENT
    // errorType is exactly what marks it as the refusal with no fast remedy.
    expect(err).toMatchObject({ status: 429, retryAfter: 37 })
    expect(err?.errorType).toBeUndefined()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('NEVER retries a 429 whose errorType this build has not heard of', async () => {
    // A fourth refusal must be a deliberate edit of the branch. Until someone
    // makes it, an unknown code inherits the safe answer.
    const fetchMock = vi.fn<FetchFn>(async () =>
      throttledResponse('SOME_LATER_REFUSAL', '1'))
    globalThis.fetch = fetchMock

    await expect(apiSearchSchema('w1')).rejects.toMatchObject({
      status: 429,
      errorType: 'SOME_LATER_REFUSAL',
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('the retry follows what a call DOES, not what budget it is on', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('retries POST …/search: a POST because the query is a body, not because it writes', async () => {
    vi.useFakeTimers()
    const page = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false }
    let calls = 0
    globalThis.fetch = vi.fn<FetchFn>(async () => {
      calls++
      return calls === 1 ? throttledResponse(TOO_MANY_IN_FLIGHT, '1') : jsonResponse(200, page)
    })

    const promise = apiSearch('w1', { query: 'status = Open' })
    await runThroughTheRetryWait()

    await expect(promise).resolves.toEqual(page)
    expect(calls).toBe(2)
  })

  it('does NOT retry a saved-filter create, which is on the same budget and is a write', async () => {
    // Saved-filter CRUD is throttled with the search reads because validating a
    // filter's HQL costs what /search/schema costs. That is a statement about
    // price, not about safety: a second create is a second create.
    const fetchMock = vi.fn<FetchFn>(async () => throttledResponse(TOO_MANY_IN_FLIGHT, '1'))
    globalThis.fetch = fetchMock

    await expect(savedFilters.create('w1', { name: 'Mine', hql: 'status = Open' }))
      .rejects.toMatchObject({ status: 429, errorType: TOO_MANY_IN_FLIGHT })
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // …and neither is the delete, for the same reason.
    fetchMock.mockClear()
    await expect(savedFilters.remove('w1', 'f1')).rejects.toMatchObject({ status: 429 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('retries the storage breakdown, a GET on the same surface as the reports', async () => {
    vi.useFakeTimers()
    const breakdown = { projects: [] }
    let calls = 0
    globalThis.fetch = vi.fn<FetchFn>(async () => {
      calls++
      return calls === 1 ? throttledResponse(TOO_MANY_IN_FLIGHT, '1') : jsonResponse(200, breakdown)
    })

    const promise = apiGetWorkspaceStorageByProject('w1')
    await runThroughTheRetryWait()

    await expect(promise).resolves.toEqual(breakdown)
    expect(calls).toBe(2)
  })
})
