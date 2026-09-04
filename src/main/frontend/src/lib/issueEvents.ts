import type { QueryClient } from '@tanstack/react-query'
import { isCapacityFailure } from '../apiError'
import { backlogViewKeyPrefix, isBacklogViewKey, projectIssuesKeyPrefix } from './queryKeys'

/**
 * What one workspace SSE issue event costs the instance — and the two rules
 * that bound it (HD-174 follow-up).
 *
 * The fan-out is the part that is easy to miss, because it crosses principals.
 * A single member with `issue.update` PATCHing in a loop stays inside their own
 * cheap write budget, but every PATCH publishes `ISSUE_UPDATED` to **every
 * connected member of the workspace**, and the naive answer — invalidate
 * everything under `projectIssuesKeyPrefix` — includes the planning aggregate,
 * a `12 + N`-statement read on the throttled expensive surface. So one writer's
 * budget buys N readers' aggregates: the load lands in the *victims'*
 * per-minute pots, and ten open Backlog tabs against a six-permit surface pin
 * the whole expensive-read share instance-wide. TanStack dedupes only while a
 * fetch is in flight, which a 30-second-apart burst never is.
 *
 * Two rules, and they are deliberately different in kind:
 *
 *  1. **Coalesce** — the planning namespace is invalidated on the leading edge
 *     of a {@link PLANNING_COALESCE_WINDOW_MS} window, with at most one trailing
 *     invalidation for everything dropped inside it. The first event is still
 *     immediate (a planner watching a colleague drag a card sees it move), and a
 *     burst of a hundred costs two aggregates instead of a hundred. The view
 *     already carries `staleTime: 30_000`, so a few seconds of coalescing is
 *     below the resolution of anything the user was promised.
 *  2. **Never re-ask a surface that has just refused** — if a planning entry
 *     somebody is currently looking at is sitting in a capacity failure (a 429,
 *     or a 502/503/504 from a saturated instance), the invalidation is *skipped
 *     entirely* rather than deferred. "Currently looking at" is load-bearing and
 *     is spelled out on {@link planningIsRefusing}: an abandoned cache entry
 *     keeps its error for the whole `gcTime` with no reader and no retry
 *     affordance, so counting it turns this rule into a latch. There is no backoff anywhere in an SSE loop: without this,
 *     the next event provokes the same refetch immediately, against an instance
 *     that is already refusing. The planner is not left uninformed — the page
 *     renders the refusal with a manual *Try again*, which is a retry a human
 *     decided on.
 *
 * Everything else under the prefix (the board, the pickers, the paginated list)
 * is invalidated immediately and unconditionally, exactly as before: those are
 * ordinary reads, they are not on the expensive surface, and delaying them would
 * be paying a cost this module exists to avoid paying.
 */
export const PLANNING_COALESCE_WINDOW_MS = 5_000

export interface IssueEventInvalidator {
  /**
   * An issue in this project was created, updated or deleted — from the
   * workspace SSE stream, i.e. possibly caused by somebody else entirely.
   */
  issueChanged(wsId: string | undefined, projectId: string | undefined): void
  /** Drop any pending trailing invalidation (unmount). */
  dispose(): void
}

export interface InvalidatorOptions {
  /** Injectable for tests; the app has exactly one clock. */
  now?: () => number
  windowMs?: number
}

export function createIssueEventInvalidator(
  qc: QueryClient,
  opts: InvalidatorOptions = {},
): IssueEventInvalidator {
  const now = opts.now ?? (() => Date.now())
  const windowMs = opts.windowMs ?? PLANNING_COALESCE_WINDOW_MS
  /** project → when its planning namespace was last invalidated by an event. */
  const lastPlanning = new Map<string, number>()
  /** project → the one trailing timer allowed per window. */
  const pending = new Map<string, ReturnType<typeof setTimeout>>()

  /**
   * True when the project's planning view is **being shown** and is showing a
   * capacity failure. Read off the cache rather than off a flag some component
   * set, because the state belongs to the query and any tab may have put it
   * there.
   *
   * **Observed entries only, and that clause is the whole correctness of the
   * skip.** A prefix match covers every filter combination the planner has
   * visited this session, and an unmounted one keeps its error for the full
   * `gcTime` (5 minutes) with nobody looking at it and no way for anybody to
   * clear it — the manual *Try again* lives on the rendered notice, so an
   * abandoned variant has no retry affordance at all. Matching those too makes
   * the skip a **latch**: one 429 on an `includeDone: true` view the planner has
   * since toggled away from mutes SSE refresh of the healthy view now on screen,
   * for five minutes, silently. This predicate exists to stop the app re-asking a
   * surface a *reader is currently waiting on*; a query no observer is mounted
   * against is not that, and it is not going to be re-fetched by an invalidation
   * either — an invalidation only marks it stale.
   *
   * So the rule is the stuck-**on** direction, as everywhere else in this module:
   * skipping too little costs one aggregate that a live view would have paid for
   * anyway, skipping too much costs a planner a screen that quietly stops
   * updating.
   */
  function planningIsRefusing(wsId: string | undefined, projectId: string | undefined): boolean {
    return qc.getQueryCache()
      .findAll({ queryKey: backlogViewKeyPrefix(wsId, projectId) })
      .some(q => q.getObserversCount() > 0
        && q.state.status === 'error' && isCapacityFailure(q.state.error))
  }

  function invalidatePlanning(wsId: string | undefined, projectId: string | undefined) {
    if (planningIsRefusing(wsId, projectId)) return
    void qc.invalidateQueries({ queryKey: backlogViewKeyPrefix(wsId, projectId) })
  }

  return {
    issueChanged(wsId, projectId) {
      // The cheap half — immediate, and narrowed by the predicate so it can
      // never reach the planning namespace by inheriting the shared prefix.
      void qc.invalidateQueries({
        queryKey: projectIssuesKeyPrefix(wsId, projectId),
        predicate: q => !isBacklogViewKey(q.queryKey),
      })

      const project = `${wsId}|${projectId}`
      const at = now()
      const last = lastPlanning.get(project)
      if (last === undefined || at - last >= windowMs) {
        lastPlanning.set(project, at)
        invalidatePlanning(wsId, projectId)
        return
      }
      // Inside the window. One trailing invalidation covers every event dropped
      // here, so the view still converges when a burst ends — the alternative,
      // dropping them outright, leaves the last change of a burst invisible
      // until something else happens to ask.
      if (pending.has(project)) return
      pending.set(project, setTimeout(() => {
        pending.delete(project)
        lastPlanning.set(project, now())
        // Re-checked at fire time, not at schedule time: the surface may have
        // started refusing during the window.
        invalidatePlanning(wsId, projectId)
      }, windowMs - (at - last)))
    },
    dispose() {
      for (const timer of pending.values()) clearTimeout(timer)
      pending.clear()
    },
  }
}
