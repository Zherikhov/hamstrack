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
  // HD-22/HD-27 — the Scrum board scopes itself to the ACTIVE sprint by adding
  // `?sprintId=` to the very same request. It therefore has to join the key, or
  // the Kanban and Scrum caches would overwrite each other under one entry.
  sprintId?: string
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
  // Appended LAST so every pre-0.13.0 key stays byte-identical — an unselected
  // sprint must still resolve to the 2-arg `boardIssuesKey(ws, p)` entry the
  // create dialog reads (the HD-86/87 white-screen class of bug).
  if (f.sprintId) parts.push(`sprint:${f.sprintId}`)
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

/**
 * Prefix covering ONLY the board's list entries — lets a backlog rank move
 * refresh the board (which shares the rank as its vertical order) without
 * nuking the planning view the user is currently dragging in.
 */
export function boardIssuesKeyPrefix(
  wsId: string | undefined,
  projectId: string | undefined,
) {
  return [ISSUES_KEY_ROOT, wsId, projectId, 'board'] as const
}

/**
 * The HD-23 planning aggregate (`GET …/backlog`; value shape: `BacklogView`).
 *
 * Its own marker segment, distinct from the legacy paginated `'backlog'` one —
 * the two value shapes must never share an entry. It DOES live under
 * `projectIssuesKeyPrefix`, so creating an issue (which lands at the bottom of
 * the ranked backlog) still refreshes the planning view; fine-grained rank
 * moves instead patch the affected sections in place (see `useBacklogView`).
 */
export function backlogViewKey(
  wsId: string | undefined,
  projectId: string | undefined,
  filters: BacklogViewFilters = {},
) {
  const parts = [serializeBoardFilters(filters)].filter(Boolean)
  // The planning view offers two dimensions the board doesn't: a status select
  // (the board's columns ARE the statuses) and the show-done toggle. Both change
  // the response, so both have to change the key.
  if (filters.statusId) parts.push(`status:${filters.statusId}`)
  if (filters.includeDone) parts.push('includeDone')
  return [ISSUES_KEY_ROOT, wsId, projectId, 'backlogView', parts.join('|')] as const
}

/** The board filters plus the planning view's own two dimensions. */
export interface BacklogViewFilters extends BoardFilters {
  statusId?: string
  includeDone?: boolean
}

/**
 * One project's sprint list. Sprints are project CONTENT, not bound taxonomy
 * (HD-22 §3.6) — their own key namespace, so starting a sprint never
 * invalidates `['projectConfig', …]`, which every board render depends on.
 *
 * `state` narrows the fetch (the pickers want the open ones, the Scrum board the
 * ACTIVE one) and joins the key so those never share a cache entry.
 */
export function sprintsKey(
  wsId: string | undefined,
  projectId: string | undefined,
  state?: SprintStateFilter,
) {
  const states = state === undefined ? [] : (Array.isArray(state) ? [...state] : [state])
  return ['sprints', wsId, projectId, states.sort().join(',')] as const
}

/** Repeatable `state=` filter, in the shape `sprintsApi.list` accepts. */
export type SprintStateFilter =
  | 'FUTURE' | 'ACTIVE' | 'COMPLETED'
  | ('FUTURE' | 'ACTIVE' | 'COMPLETED')[]

/** Invalidation prefix covering every issue list of one project. */
export function projectIssuesKeyPrefix(
  wsId: string | undefined,
  projectId: string | undefined,
) {
  return [ISSUES_KEY_ROOT, wsId, projectId] as const
}

// ── Reports (HD-5 / HD-28) ───────────────────────────────────────────────────

/**
 * One report's cache entry: `['reports', kind, projectId, params]`.
 *
 * Deliberately NOT under `ISSUES_KEY_ROOT`: a report is a server-side aggregate
 * over a window, so an optimistic issue patch must not invalidate it (the number
 * it would refetch is the same number, one round trip later), and a report
 * refetch must never look like an issue-list refetch to the board.
 *
 * `wsId` is absent on purpose — a project id is globally unique, and the report
 * of a project is the report of a project whichever workspace path reached it.
 *
 * The params object is the LAST segment and is compared structurally by
 * TanStack Query, so `{ interval: 'WEEK' }` and `{ interval: 'WEEK' }` share one
 * entry. Undefined-valued keys are dropped first, so "the default window" and
 * "the default window, explicitly" cannot split into two entries.
 */
export function reportKey(
  kind: string,
  projectId: string | undefined,
  params: Record<string, string | undefined> = {},
) {
  const clean: Record<string, string> = {}
  for (const k of Object.keys(params).sort()) {
    const v = params[k]
    if (v) clean[k] = v
  }
  return ['reports', kind, projectId, clean] as const
}

/** `staleTime` for every report query — matches the endpoints' `Cache-Control: max-age=60`. */
export const REPORT_STALE_TIME = 60 * 1000
