import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { apiMe, apiRankIssue, apiUpdateIssue, ApiResponseError } from './api'
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
