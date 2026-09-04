import { QueryClient } from '@tanstack/react-query'
// From the leaf module, NOT from './api': api.ts reaches the auth store, which
// reaches this file, and that cycle fails silently in the exact direction this
// predicate exists to prevent. apiError.ts carries the full reasoning;
// moduleGraph.test.ts fails if the cycle comes back.
import { ApiResponseError } from './apiError'

/**
 * Statuses an automatic retry must never touch, and the reason each one is here.
 *
 *  • **422 `STATEMENT_BUDGET_EXCEEDED`** (HD-151) — the server refused the query
 *    because it outran its per-statement budget. It deliberately carries **no**
 *    `Retry-After`, and the backend deliberately chose 422 over a 5xx so that
 *    automatic retry machinery would leave it alone. A retry — immediate, backed
 *    off, or automatic inside a client — spends the whole budget again and holds
 *    a pooled connection again, on an instance that has just said it is over
 *    budget. The published contract in `public/openapi.yaml` is "never retry it".
 *    (422 is also the HQL validation status: a malformed query is not going to
 *    parse on the second attempt either, so not retrying is right there too.)
 *
 *  • **429** — the refusal answers with `Retry-After`. An immediate automatic
 *    retry ignores the wait it was just handed and burns another slot. The wait
 *    is surfaced to the reader instead (`RateLimitNotice`), who retries when the
 *    countdown says they may. This is a rule about the *status*, and it stays one
 *    even though a 429 no longer names a single control: since HD-182 the same
 *    code carries a per-minute budget's refusal and two occupancy refusals, and
 *    the one of them whose obstacle really does clear in about a second
 *    (`TOO_MANY_IN_FLIGHT`) is retried **once, inside `request()`**, where the
 *    body's `errorType` is in hand. By the time an error reaches this predicate
 *    that retry has already happened and failed, so retrying here would be the
 *    second — which is the one nobody wants.
 *
 * Everything else keeps the historical behaviour: exactly one retry, which is
 * what a transient 5xx or a dropped connection deserves.
 *
 * This predicate is the load-bearing mechanism. Per-query `retry: false` on the
 * expensive surfaces (reports, insights, search) is now a *narrowing* of it —
 * those screens want no retry for **any** failure, including a 500 — not the
 * thing that keeps a 422 from being re-spent.
 */
function isNeverRetryable(error: unknown): boolean {
  return error instanceof ApiResponseError && (error.status === 422 || error.status === 429)
}

/**
 * The global query-retry rule. Exported so it can be asserted directly —
 * see `queryClient.test.ts`.
 */
export function retryQuery(failureCount: number, error: unknown): boolean {
  return !isNeverRetryable(error) && failureCount < 1
}

// Module-level so auth code can wipe the cache on identity changes —
// cached data must never survive a user switch.
//
// `mutations` is deliberately left unconfigured: React Query does not retry a
// mutation by default, and a write is the last thing that should be replayed
// against a server that has just refused it. Nothing to protect there.
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: retryQuery,
      staleTime: 30_000,
    },
  },
})
