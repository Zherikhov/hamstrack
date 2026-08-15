/**
 * Shared react-query keys for issue lists.
 *
 * Why this exists: a cache key is a contract about the *shape* of the cached
 * value, and two components that build the same key by hand can silently
 * disagree about that shape. That happened for real — the board wrote the
 * `BoardIssues` wrapper `{ issues, truncated, totalAvailable, cap }` under
 * `['issues', ws, project, 'board', priority]` while the create-issue dialog
 * wrote a bare `Issue[]` under the same key, so opening the dialog over a board
 * read the wrapper and crashed the whole SPA on `projectIssues.filter(...)`.
 *
 * Rule: every consumer of a board key goes through `boardIssuesKey()`, and the
 * value cached under it is ALWAYS the `BoardIssues` wrapper. A consumer that
 * wants just the array projects it with react-query's `select`, never with a
 * different `queryFn`.
 *
 * The paginated Backlog list is a different value shape and therefore lives
 * under a different key namespace (`'backlog'` marker) — see
 * `backlogIssuesKeyPrefix`.
 */

/** Every issue-list key starts with this — used for broad invalidation. */
export const ISSUES_KEY_ROOT = 'issues' as const

/**
 * Board list key. `priorityId` is the server-side priority filter ('' = none);
 * the cached value is a `BoardIssues` wrapper.
 */
export function boardIssuesKey(
  wsId: string | undefined,
  projectId: string | undefined,
  priorityId = '',
) {
  return [ISSUES_KEY_ROOT, wsId, projectId, 'board', priorityId] as const
}

/** Prefix of the paginated backlog keys (value shape: `Page<Issue>`). */
export function backlogIssuesKeyPrefix(
  wsId: string | undefined,
  projectId: string | undefined,
) {
  return [ISSUES_KEY_ROOT, wsId, projectId, 'backlog'] as const
}

/** Invalidation prefix covering every issue list of one project. */
export function projectIssuesKeyPrefix(
  wsId: string | undefined,
  projectId: string | undefined,
) {
  return [ISSUES_KEY_ROOT, wsId, projectId] as const
}
