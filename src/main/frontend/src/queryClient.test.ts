import { describe, it, expect, afterEach } from 'vitest'
import { queryClient, retryQuery } from './queryClient'
// From the leaf, the same module queryClient.ts imports it from — importing it
// via './api' would drag api.ts → auth.ts into this test's graph for nothing.
// moduleGraph.test.ts is what guards the cycle; this file guards the rule.
import { ApiResponseError } from './apiError'

/**
 * The global retry rule is a *contract with the server*, not a tuning knob.
 *
 * HD-151 bounds every database statement; a query that outruns its budget is
 * refused with **422 `STATEMENT_BUDGET_EXCEEDED`**, deliberately with no
 * `Retry-After`, and the published contract (`public/openapi.yaml`) is "never
 * retry it" — a retry spends the whole budget again and re-holds a pooled
 * connection on an instance that has just said it is over budget. The backend
 * chose 422 over a 5xx precisely so that automatic retry machinery would leave
 * it alone; a blanket `retry: 1` in this client defeated that choice, and the
 * ordinary board/backlog reads that inherit the default are exactly the ones a
 * large tenant trips.
 *
 * 429 is here for the sibling reason: the limiter hands back a wait, and an
 * immediate automatic retry ignores it and burns another slot.
 *
 * The expensive surfaces (reports, insights, search) also set `retry: false` by
 * hand. Those are *narrower* than this rule — they decline a retry for any
 * failure, including a 500 — and must never be what keeps a 422 from being
 * re-spent. This test asserts the default, so forgetting the hand-written
 * opt-out on the next expensive screen is not a silent regression.
 */
describe('the global query-retry rule', () => {
  afterEach(() => {
    queryClient.clear()
  })

  describe('retryQuery — the predicate itself', () => {
    it('never retries a 422', () => {
      expect(
        retryQuery(0, new ApiResponseError(422, 'Statement budget exceeded')),
        'A 422 STATEMENT_BUDGET_EXCEEDED carries no Retry-After and must NEVER be '
        + 'retried: the retry re-spends the whole statement budget and re-holds a '
        + 'pooled connection on an instance that just refused for being over budget.',
      ).toBe(false)
    })

    it('never retries a 429', () => {
      expect(
        retryQuery(0, new ApiResponseError(429, 'Too many requests')),
        'A 429 carries Retry-After. An immediate automatic retry ignores the wait '
        + 'the server just handed us and burns another slot — surface the wait to '
        + 'the reader (RateLimitNotice) instead.',
      ).toBe(false)
    })

    it('still retries everything else exactly once, as before', () => {
      const serverError = new ApiResponseError(500, 'Internal error')
      expect(retryQuery(0, serverError), 'a transient 5xx keeps its one retry').toBe(true)
      expect(retryQuery(1, serverError), 'and only one — the count is unchanged').toBe(false)

      const offline = new TypeError('Failed to fetch')
      expect(retryQuery(0, offline), 'a dropped connection keeps its one retry').toBe(true)
      expect(retryQuery(1, offline), 'and only one').toBe(false)
    })

    it('is not fooled by a non-Error rejection carrying a status field', () => {
      // Only ApiResponseError is trusted: a bare object could come from anywhere,
      // and the historical behaviour for an unknown rejection is one retry.
      expect(retryQuery(0, { status: 422 }), 'unknown rejections keep the old behaviour').toBe(true)
      expect(retryQuery(0, undefined)).toBe(true)
    })
  })

  describe('the QueryClient the app actually uses', () => {
    async function callsUntilItGivesUp(error: unknown): Promise<number> {
      let calls = 0
      await queryClient
        .fetchQuery({
          queryKey: ['retry-contract', calls, Math.random()],
          queryFn: () => {
            calls++
            return Promise.reject(error)
          },
          retryDelay: 0,
        })
        .catch(() => {})
      return calls
    }

    it('asks once for a 422 and does not ask again', async () => {
      expect(
        await callsUntilItGivesUp(new ApiResponseError(422, 'Statement budget exceeded')),
        'The default query options retried a 422. Any query that does not opt out — '
        + 'a board or backlog read on a large tenant, say — now doubles the statement '
        + 'budget it was refused for. See queryClient.ts.',
      ).toBe(1)
    })

    it('asks once for a 429 and does not ask again', async () => {
      expect(
        await callsUntilItGivesUp(new ApiResponseError(429, 'Too many requests')),
        'The default query options retried a 429, ignoring its Retry-After.',
      ).toBe(1)
    })

    it('keeps the historical single retry for a 500', async () => {
      expect(
        await callsUntilItGivesUp(new ApiResponseError(500, 'Internal error')),
        'The one retry for transient failures was lost — this fix was meant to '
        + 'narrow the rule to 422/429, not to disable retries everywhere.',
      ).toBe(2)
    })
  })
})
