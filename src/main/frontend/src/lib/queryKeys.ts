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
 * The board's server-side filters (HD-30 and the slices after it). Everything
 * here is a query parameter on `GET …/issues`, so each distinct selection is a
 * distinct cache entry.
 */
export interface BoardFilters {
  priorityId?: string
  labelIds?: string[]
  labelMatch?: 'any' | 'all'
  componentId?: string   // HD-31 — one component per issue, so a single id
  // HD-32 — a single version id, matched against FIX links only (an AFFECTS
  // link is a different question and deliberately doesn't match).
  fixVersionId?: string
}

/**
 * Deterministic serialization of the board filters into ONE key segment.
 * `labelIds` is sorted so {a,b} and {b,a} share a cache entry, and an empty
 * selection collapses to `''` — the value `boardIssuesKey(ws, project)` uses,
 * so an unfiltered board and the create dialog's parent picker keep sharing one
 * entry.
 *
 * The legacy string form is the priority id (the only filter that existed
 * before) and serializes identically to `{ priorityId }`, so the two forms can
 * never split one logical list across two entries.
 */
function serializeBoardFilters(filters: string | BoardFilters): string {
  const f: BoardFilters = typeof filters === 'string' ? { priorityId: filters } : filters
  const parts: string[] = []
  if (f.priorityId) parts.push(`priority:${f.priorityId}`)
  if (f.labelIds && f.labelIds.length > 0) {
    parts.push(`labels:${[...f.labelIds].sort().join(',')}`)
    // "all" only differs from the default "any" once two labels are selected.
    if (f.labelMatch === 'all' && f.labelIds.length > 1) parts.push('labelMatch:all')
  }
  if (f.componentId) parts.push(`component:${f.componentId}`)
  if (f.fixVersionId) parts.push(`fixVersion:${f.fixVersionId}`)
  return parts.join('|')
}

/**
 * Board list key; the cached value is ALWAYS a `BoardIssues` wrapper.
 *
 * The third argument is OPTIONAL and stays optional: `boardIssuesKey(wsId,
 * projectId)` must keep resolving to the unfiltered board entry, because
 * `CreateIssueModal` reads exactly that key for its parent picker. Silently
 * changing this signature's arity is how the HD-86/HD-87 white screen happened.
 * It accepts either the legacy priority-id string or a `BoardFilters` object.
 */
export function boardIssuesKey(
  wsId: string | undefined,
  projectId: string | undefined,
  filters: string | BoardFilters = '',
) {
  return [ISSUES_KEY_ROOT, wsId, projectId, 'board', serializeBoardFilters(filters)] as const
}

/**
 * Same deterministic serialization for the backlog's own key namespace (whose
 * cached value is a `Page<Issue>`, never the board wrapper).
 */
export function serializeIssueFilters(filters: BoardFilters): string {
  return serializeBoardFilters(filters)
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
