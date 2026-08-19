import { useCallback, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Flag } from 'lucide-react'
import {
  ApiResponseError, apiGetBacklogView, apiGetProject,
  apiListIssuesPaged, sprintsApi,
} from '../api'
import type { BacklogViewOptions, CreateSprintPayload, StartSprintPayload, UpdateSprintPayload } from '../api'
import {
  backlogViewKey, projectIssuesKeyPrefix, sprintsKey,
} from '../lib/queryKeys'
import type { SprintStateFilter } from '../lib/queryKeys'
import { Button, Input, Select, Textarea } from './ui'
import { ReversibleNotice, useEnableCapability } from './delivery'
import { Modal } from '../pages/admin/common'
import type {
  BacklogSectionBase, BacklogView, Issue, SectionStats, Sprint, SprintCompletionPreview,
  SprintCompletionResult, SprintRef, SprintState, UnfinishedDisposition,
} from '../types'

/**
 * Sprint rendering, picking and lifecycle (HD-22/23/27) — the sprints
 * counterpart of `components/versions.tsx`: every surface that shows, picks or
 * drives a sprint (planning backlog, Scrum board header, issue detail, create
 * dialog) goes through this module, so a sprint reads and behaves the same
 * everywhere. HD-23 builds it; HD-27 consumes it.
 *
 * Sprints are PROJECT-scoped content with a lifecycle, not project taxonomy:
 * they are fetched from their own endpoints under `sprintsKey(wsId, projectId,
 * state?)` and never travel in `ProjectConfig` — so a sprint start does not
 * invalidate the config every board render depends on (HD-22 §3.6).
 *
 * DESIGN.md: the ACTIVE sprint wears `--color-brand`, FUTURE the slate/info
 * tint and COMPLETED the success tint; point numbers and dates are inspectable
 * data and therefore render in IBM Plex Mono (`.mono`); pills use
 * `--radius-full`. No literal hex anywhere — tokens only.
 */

/** The two non-terminal states — what a picker may legally offer. */
export const OPEN_SPRINT_STATES: SprintState[] = ['ACTIVE', 'FUTURE']

/** Synthetic section id for the ranked backlog (the "no sprint" group). */
export const BACKLOG_SECTION = 'BACKLOG'

/** Server messages reach the SPA as ProblemDetail `detail` — prefer it over a generic string. */
export function apiErrorText(e: unknown, fallback: string): string {
  if (e instanceof ApiResponseError) return e.detail
  return e instanceof Error ? e.message : fallback
}

/** A 409 on a drag means the caller's view of the list is stale — refresh, don't fight. */
export function isStaleListError(e: unknown): boolean {
  return e instanceof ApiResponseError && e.status === 409
}

/**
 * A COMPLETED sprint's membership is a delivered fact: the server refuses to add
 * an issue to one (422) and, since the 0.13.0 review, to REMOVE one from it as
 * well — through `DELETE …/sprints/{id}/issues/{issueId}`, through `clearSprint`
 * on an issue update and through a rank move out of it. The UI must therefore
 * stop *offering* those actions rather than let the user discover them as a 422.
 */
export function isSprintClosed(state: SprintState | undefined | null): boolean {
  return state === 'COMPLETED'
}

/** Explanation shown wherever a move in/out of a completed sprint is withheld. */
export const CLOSED_SPRINT_HINT =
  'This sprint is completed — its issues are its record and can no longer be moved.'

/**
 * Split a list into slices of at most `size` (>= 1). Used for every bulk sprint
 * move: the server caps `issueIds` per request at `app.agile.max-issues-per-bulk-move`
 * (default 100), which is SMALLER than the section cap the UI renders (default
 * 300) — so a section-sized payload is a 400 waiting to happen.
 */
export function chunk<T>(items: T[], size: number): T[][] {
  const n = Math.max(1, Math.floor(size))
  const out: T[][] = []
  for (let i = 0; i < items.length; i += n) out.push(items.slice(i, i + n))
  return out
}

/**
 * The per-request bulk cap to use, given what the server reported.
 *
 * When the cap is unknown (a response that predates the field) we do NOT guess a
 * default — duplicating the server's number in the client is exactly what drifts
 * the moment an operator retunes the property. The only size guaranteed to be
 * accepted by any configuration is **1** (the property's minimum), so an unknown
 * cap degrades to one issue per request: slower, never wrong.
 */
export function effectiveBulkCap(cap: number | undefined | null): number {
  return typeof cap === 'number' && cap >= 1 ? Math.floor(cap) : 1
}

/**
 * A bulk move that stopped part-way. Carries how many issues DID move so the
 * page can tell the user the truth and refresh the affected sections instead of
 * rolling a stale list forward.
 */
export class BulkMoveError extends Error {
  constructor(
    /** The server's own message for the request that failed. */
    readonly reason: string,
    readonly movedCount: number,
    readonly total: number,
  ) {
    super(movedCount === 0
      ? reason
      : `Moved ${movedCount} of ${total} issues, then stopped: ${reason}`)
    this.name = 'BulkMoveError'
  }
}

// ── Formatting ────────────────────────────────────────────────────────────────

/**
 * Points are `BigDecimal` with at most 2 decimals and trailing zeros stripped
 * server-side; this keeps a locally-summed value looking the same (`3` not
 * `3.00`, `0.5` not `0.50`).
 */
export function formatPoints(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return String(Number(value.toFixed(2)))
}

/**
 * The active sprint's countdown, in the three wordings the spec pins:
 * "6 days left" · "ends today" · "2 days overdue". Null for anything that has no
 * countdown (a FUTURE sprint, or an ACTIVE one with no end date).
 */
export function formatDaysRemaining(days: number | null | undefined): string | null {
  if (days === null || days === undefined) return null
  if (days === 0) return 'ends today'
  if (days > 0) return `${days} day${days === 1 ? '' : 's'} left`
  const over = Math.abs(days)
  return `${over} day${over === 1 ? '' : 's'} overdue`
}

/** ISO instant → the viewer's short local date; blank input renders nothing. */
export function formatSprintDate(iso: string | null | undefined): string | null {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/** "Aug 10 – Aug 24", or one half of it, or null when neither date is planned. */
export function formatSprintRange(sprint: Pick<Sprint, 'startAt' | 'endAt'>): string | null {
  const from = formatSprintDate(sprint.startAt)
  const to = formatSprintDate(sprint.endAt)
  if (from && to) return `${from} – ${to}`
  return from ?? to
}

/** `<input type="datetime-local">` needs a local, second-less value; the API wants UTC ISO. */
export function toLocalInputValue(iso: string | null | undefined): string {
  const d = iso ? new Date(iso) : new Date()
  if (Number.isNaN(d.getTime())) return ''
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000)
  return local.toISOString().slice(0, 16)
}

/** …and back. Empty input = "don't send this key at all". */
export function fromLocalInputValue(value: string): string | undefined {
  if (!value) return undefined
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

// ── Data hooks ────────────────────────────────────────────────────────────────

/**
 * One project's sprints, narrowed by state. Always paged server-side; the
 * default size (200) comfortably covers the open ones, which are capped by
 * `app.agile.max-open-sprints-per-project` (default 20).
 */
export function useProjectSprints(
  wsId: string | undefined,
  projectId: string | undefined,
  state?: SprintStateFilter,
  enabled = true,
) {
  return useQuery({
    queryKey: sprintsKey(wsId, projectId, state),
    queryFn: () => sprintsApi.list(wsId!, projectId!, { state, size: 200 }),
    // The page envelope is an implementation detail here — every consumer wants
    // the rows, and `select` keeps ONE cache entry with ONE value shape.
    select: page => page.content,
    enabled: !!wsId && !!projectId && enabled,
  })
}

/** The project's open (ACTIVE + FUTURE) sprints — the source for every picker. */
export function useOpenSprints(
  wsId: string | undefined,
  projectId: string | undefined,
  enabled = true,
) {
  return useProjectSprints(wsId, projectId, OPEN_SPRINT_STATES, enabled)
}

/**
 * The single ACTIVE sprint, or null. At most one per project — enforced in the
 * DB by a partial unique index, so taking the first row is safe.
 */
export function useActiveSprint(
  wsId: string | undefined,
  projectId: string | undefined,
  enabled = true,
) {
  const q = useProjectSprints(wsId, projectId, 'ACTIVE', enabled)
  return { ...q, sprint: q.data?.[0] ?? null }
}

/**
 * The cached project entry (`NavRail` and both settings areas already fetch it),
 * which is where the declared delivery capabilities ride.
 *
 * HD-123 S5 removed this helper's other half. It used to also answer "is this
 * actor a project curator?" by re-deriving `ScopeResolver.requireProjectCurator`
 * client-side — project `myRole === 'MANAGER'` OR workspace `myRole` OWNER/ADMIN
 * — which cost a second request for the workspace role and, being a hand-written
 * copy of a server predicate, drifted from it (HD-98). Authorization now comes
 * from `hooks/usePermissions`, which reads the server's own answer off this same
 * cache entry and needs no workspace lookup at all.
 */
export function useProjectEntry(wsId: string | undefined, projectId: string | undefined) {
  const { data: project } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  return { project }
}

/**
 * Whole-section totals recomputed from a freshly-fetched page.
 *
 * Only used for a section the server can't re-stat on its own (the backlog) and
 * only when that section is NOT truncated — over a truncated page the numbers
 * would be a lie, so the previous server-computed stats are kept instead.
 * `points === null` (the story-points fallback design) stays null.
 */
export function statsFromIssues(issues: Issue[], previous: SectionStats): SectionStats {
  const pointsDisabled = previous.points === null
  let points = 0
  let donePoints = 0
  let unestimated = 0
  let done = 0
  for (const i of issues) {
    const isDone = i.status.category === 'DONE'
    if (isDone) done += 1
    const p = i.storyPoints
    if (p === null || p === undefined) unestimated += 1
    else {
      points += p
      if (isDone) donePoints += p
    }
  }
  return {
    issueCount: issues.length,
    doneIssueCount: done,
    points: pointsDisabled ? null : points,
    donePoints: pointsDisabled ? null : donePoints,
    unestimatedCount: unestimated,
  }
}

/**
 * The planning aggregate plus PER-SECTION refresh (HD-23 / §9 risk 5).
 *
 * The view arrives as one `GET …/backlog` response, but a section must never
 * need a whole-view refetch to become current — a 21-section × 300-issue
 * response is several MB, and a planning meeting re-ranks constantly. So every
 * section can be refreshed on its own out of endpoints that already exist:
 *   • a sprint section → `GET /sprints/{id}` (its counters ARE the section
 *     stats) + `GET /issues?sprintId=…`;
 *   • the backlog section → `GET /issues?noSprint=true`.
 * The result is patched into the cached `BacklogView` with `setQueryData`, so
 * only that section re-renders and nothing else refetches.
 *
 * Caveat, deliberately explicit: a sprint's own counters are unfiltered, so they
 * are only adopted as section stats when NO issue filter is active. Under a
 * filter the stats are recomputed from the refetched page when the section is
 * complete, and left at their last server value when it is truncated.
 */
export function useBacklogView(
  wsId: string | undefined,
  projectId: string | undefined,
  filters: BacklogViewOptions,
) {
  const qc = useQueryClient()
  // Memoized so `refreshSection` keeps a stable identity across renders (the key
  // is a fresh array literal every call).
  const key = useMemo(() => backlogViewKey(wsId, projectId, filters), [wsId, projectId, filters])
  const [refreshingSection, setRefreshingSection] = useState<string | null>(null)
  const [sectionError, setSectionError] = useState<string>('')

  const query = useQuery({
    queryKey: key,
    queryFn: () => apiGetBacklogView(wsId!, projectId!, filters),
    enabled: !!wsId && !!projectId,
  })

  // A filter narrows a section, so the sprint's own (unfiltered) counters stop
  // describing it. Serialized rather than deep-compared — it is one short string.
  const filtered = !!(filters.statusId || filters.priorityId || filters.componentId
    || filters.fixVersionId || (filters.labelIds?.length ?? 0) > 0)

  const refreshSection = useCallback(async (sectionId: string) => {
    if (!wsId || !projectId) return
    setRefreshingSection(sectionId)
    setSectionError('')
    try {
      // The section cap is the SERVER's number and travels on the view. When it
      // is unknown (no cached view yet) `size` is omitted entirely and the server
      // applies its own cap — never a copy of the default baked in here, which
      // would silently drift the moment an operator retunes the property.
      const cap = qc.getQueryData<BacklogView>(key)?.sectionCap
      const listOpts = {
        statusId: filters.statusId,
        priorityId: filters.priorityId,
        componentId: filters.componentId,
        fixVersionId: filters.fixVersionId,
        labelIds: filters.labelIds,
        labelMatch: filters.labelMatch,
        page: 0,
        ...(cap ? { size: cap } : {}),
      }

      if (sectionId === BACKLOG_SECTION) {
        // The backlog hides DONE issues unless the view asked for them; sprint
        // sections always keep theirs (that is the sprint's record).
        const page = await apiListIssuesPaged(wsId, projectId, {
          ...listOpts, noSprint: true, excludeDone: !filters.includeDone,
        })
        qc.setQueryData<BacklogView>(key, old => old && {
          ...old,
          backlog: patchSection(old.backlog, page.content, page.totalElements, null, false),
        })
        return
      }

      const [sprint, page] = await Promise.all([
        sprintsApi.get(wsId, projectId, sectionId),
        apiListIssuesPaged(wsId, projectId, { ...listOpts, sprintId: sectionId }),
      ])
      qc.setQueryData<BacklogView>(key, old => old && {
        ...old,
        sprints: old.sprints.map(s => s.sprint.id !== sectionId ? s : {
          ...patchSection(s, page.content, page.totalElements, sprint, filtered),
          sprint,
        }),
      })
    } catch (e) {
      setSectionError(apiErrorText(e, 'Could not refresh this section'))
      // A section that can't be patched must not stay silently stale.
      qc.invalidateQueries({ queryKey: key })
    } finally {
      setRefreshingSection(null)
    }
  }, [qc, wsId, projectId, key, filters.statusId, filters.priorityId, filters.componentId,
      filters.fixVersionId, filters.labelIds, filters.labelMatch, filters.includeDone, filtered])

  /** Refresh the (at most two) sections a move touched — never the whole view. */
  const refreshSections = useCallback(async (sectionIds: (string | null | undefined)[]) => {
    const unique = [...new Set(sectionIds.filter((s): s is string => !!s))]
    for (const id of unique) await refreshSection(id)
  }, [refreshSection])

  return {
    ...query,
    queryKey: key,
    refreshSection,
    refreshSections,
    refreshingSection,
    sectionError,
    clearSectionError: () => setSectionError(''),
  }
}

function patchSection<T extends BacklogSectionBase>(
  section: T,
  issues: Issue[],
  totalAvailable: number,
  sprint: Sprint | null,
  filtered: boolean,
): T {
  const truncated = totalAvailable > issues.length
  const stats: SectionStats = sprint && !filtered
    // The sprint row carries exactly the five section counters, computed over the
    // WHOLE sprint — better than anything derivable from a capped page.
    ? {
        issueCount: sprint.issueCount,
        doneIssueCount: sprint.doneIssueCount,
        points: sprint.points,
        donePoints: sprint.donePoints,
        unestimatedCount: sprint.unestimatedCount,
      }
    : truncated ? section.stats : statsFromIssues(issues, section.stats)
  return { ...section, issues, truncated, totalAvailable, stats }
}

// ── Lifecycle mutations ───────────────────────────────────────────────────────

/**
 * Every sprint write in one place, with a single invalidation policy: the sprint
 * lists (all state slices), every issue list of the project (board + planning
 * view — a start/complete moves issues) and any open issue detail.
 *
 * Errors are NOT swallowed: each mutation surfaces the server's ProblemDetail
 * `detail`, which is where the 409/422 wordings this feature depends on live.
 */
export function useSprintMutations(wsId: string | undefined, projectId: string | undefined) {
  const qc = useQueryClient()

  const invalidate = useCallback(() => {
    qc.invalidateQueries({ queryKey: ['sprints', wsId, projectId] })
    qc.invalidateQueries({ queryKey: projectIssuesKeyPrefix(wsId, projectId) })
    qc.invalidateQueries({ queryKey: ['issue', wsId, projectId] })
  }, [qc, wsId, projectId])

  const create = useMutation({
    mutationFn: (payload: CreateSprintPayload) => sprintsApi.create(wsId!, projectId!, payload),
    onSuccess: invalidate,
  })
  const update = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateSprintPayload }) =>
      sprintsApi.update(wsId!, projectId!, id, payload),
    onSuccess: invalidate,
  })
  const start = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload?: StartSprintPayload }) =>
      sprintsApi.start(wsId!, projectId!, id, payload ?? {}),
    onSuccess: invalidate,
  })
  const complete = useMutation({
    mutationFn: ({ id, moveUnfinishedTo, targetSprintId }: {
      id: string; moveUnfinishedTo: UnfinishedDisposition; targetSprintId?: string
    }) => sprintsApi.complete(wsId!, projectId!, id, { moveUnfinishedTo, targetSprintId }),
    onSuccess: invalidate,
  })
  const remove = useMutation({
    mutationFn: ({ id, force }: { id: string; force?: boolean }) =>
      sprintsApi.remove(wsId!, projectId!, id, !!force),
    onSuccess: invalidate,
  })
  /**
   * Bulk assign, CHUNKED by the server's own `bulkMoveCap`
   * (`app.agile.max-issues-per-bulk-move`, default 100) — which is a DIFFERENT,
   * smaller number than the section cap the caller rendered (default 300), so
   * handing a whole section to one request is a 400 ("At most N issues per
   * request") on every stock install.
   *
   * Chunks run SEQUENTIALLY: they all rank against the same target section, and
   * firing them in parallel would race the server's neighbour resolution. A
   * failed chunk stops the run and reports how many issues actually moved
   * (`BulkMoveError`) — the caller refreshes the affected sections instead of
   * showing a list that is now half true.
   */
  const addIssues = useMutation({
    mutationFn: async ({ id, issueIds, position, cap }: {
      id: string; issueIds: string[]; position?: 'TOP' | 'BOTTOM'
      /** `BacklogView.bulkMoveCap`; absent ⇒ one issue per request (never a guess). */
      cap?: number
    }) => {
      const batches = chunk(issueIds, effectiveBulkCap(cap))
      let moved = 0
      for (const batch of batches) {
        try {
          await sprintsApi.addIssues(wsId!, projectId!, id, { issueIds: batch, position })
        } catch (e) {
          throw new BulkMoveError(
            apiErrorText(e, 'Could not move the issues'), moved, issueIds.length)
        }
        moved += batch.length
      }
      return moved
    },
    onSuccess: invalidate,
  })
  /**
   * Bulk removal — there is no bulk endpoint, so it is one DELETE per issue with
   * BOUNDED concurrency (the same cap): an unbounded `Promise.all` over a
   * rendered section fires up to `sectionCap` (300) simultaneous requests at the
   * browser's connection pool and the server. Failures inside a wave are
   * collected rather than swallowed, and the run stops at the first failing wave
   * with an honest moved-count.
   */
  const removeIssues = useMutation({
    mutationFn: async ({ id, issueIds, cap }: {
      id: string; issueIds: string[]
      /** `BacklogView.bulkMoveCap`; absent ⇒ strictly sequential (never a guess). */
      cap?: number
    }) => {
      const waves = chunk(issueIds, effectiveBulkCap(cap))
      let moved = 0
      for (const wave of waves) {
        const settled = await Promise.allSettled(
          wave.map(i => sprintsApi.removeIssue(wsId!, projectId!, id, i)))
        moved += settled.filter(r => r.status === 'fulfilled').length
        const failed = settled.find((r): r is PromiseRejectedResult => r.status === 'rejected')
        // A 422 here is the "sprint is completed" refusal — surface the SERVER's
        // wording, never a generic "could not move".
        if (failed) {
          throw new BulkMoveError(
            apiErrorText(failed.reason, 'Could not move the issues'), moved, issueIds.length)
        }
      }
      return moved
    },
    onSuccess: invalidate,
  })

  return { create, update, start, complete, remove, addIssues, removeIssues, invalidate }
}

// ── Badges ────────────────────────────────────────────────────────────────────

/** Brand teal for the running sprint, slate for a plan, success for history. */
export function sprintTint(state: SprintState): string {
  if (state === 'ACTIVE') return 'var(--color-brand)'
  if (state === 'COMPLETED') return 'var(--color-success)'
  return 'var(--color-info)'
}

const STATE_LABEL: Record<SprintState, string> = {
  ACTIVE: 'Active',
  FUTURE: 'Planned',
  COMPLETED: 'Completed',
}

export function SprintStateBadge({ state, compact }: { state: SprintState; compact?: boolean }) {
  const c = sprintTint(state)
  return (
    <span
      className="inline-flex items-center gap-1 flex-shrink-0"
      style={{
        borderRadius: 'var(--radius-full, 9999px)',
        padding: compact ? '1px 7px' : '2px 9px',
        fontSize: compact ? 10.5 : 11,
        fontWeight: 600,
        background: `color-mix(in srgb, ${c} 14%, white)`,
        border: `1px solid color-mix(in srgb, ${c} 38%, white)`,
        color: c,
      }}
    >
      <span className="rounded-full" style={{ width: 6, height: 6, background: c }} />
      {STATE_LABEL[state]}
    </span>
  )
}

/**
 * One sprint as a pill on an issue row / in the detail grid. A COMPLETED sprint
 * still attached to an issue renders dimmed rather than disappearing — that is
 * the record of what shipped when.
 */
export function SprintBadge({ sprint, compact }: { sprint: SprintRef; compact?: boolean }) {
  const c = sprintTint(sprint.state)
  return (
    <span
      className="inline-flex items-center gap-1 border max-w-full"
      title={`${sprint.name} (${STATE_LABEL[sprint.state].toLowerCase()})`}
      style={{
        borderRadius: 'var(--radius-full, 9999px)',
        padding: compact ? '1px 7px' : '2px 8px',
        lineHeight: 1.4,
        background: `color-mix(in srgb, ${c} 12%, white)`,
        borderColor: `color-mix(in srgb, ${c} 40%, white)`,
        color: 'var(--color-text-secondary)',
        opacity: sprint.state === 'COMPLETED' ? 0.65 : 1,
      }}
    >
      <Flag size={compact ? 9 : 10} style={{ color: c, flexShrink: 0 }} />
      <span className="truncate" style={{ fontSize: compact ? 11 : 12 }}>{sprint.name}</span>
    </span>
  )
}

/**
 * `donePoints / points` for a section, plus an "n unestimated" hint. Renders
 * nothing when the server reports `points: null` (the §3.4 fallback design, in
 * which no project offers story points) — no misleading zeros.
 *
 * DESIGN.md: point numbers are inspectable data → IBM Plex Mono.
 */
export function SprintPointsBadge({ stats, compact }: {
  stats: Pick<SectionStats, 'points' | 'donePoints' | 'unestimatedCount'>
  compact?: boolean
}) {
  if (stats.points === null || stats.points === undefined) return null
  return (
    <span className="inline-flex items-center gap-1.5 flex-shrink-0">
      <span
        className="mono inline-flex items-center gap-1"
        title="Story points done / committed"
        style={{
          borderRadius: 'var(--radius-full, 9999px)',
          padding: compact ? '1px 7px' : '2px 9px',
          fontSize: compact ? 10.5 : 11,
          background: 'var(--color-surface-2)',
          color: 'var(--color-text-secondary)',
        }}
      >
        {formatPoints(stats.donePoints)} / {formatPoints(stats.points)} pts
      </span>
      {stats.unestimatedCount > 0 && (
        <span
          className="text-xs"
          title="Issues with no estimate — they are not in the point total"
          style={{ color: 'var(--color-text-muted)' }}
        >
          <span className="mono">{stats.unestimatedCount}</span> unestimated
        </span>
      )}
    </span>
  )
}

/** One issue's estimate as a compact chip. Unestimated renders nothing. */
export function StoryPointsChip({ points, compact }: { points: number | null | undefined; compact?: boolean }) {
  if (points === null || points === undefined) return null
  return (
    <span
      className="mono flex-shrink-0"
      title={`${formatPoints(points)} story points`}
      style={{
        borderRadius: 'var(--radius-full, 9999px)',
        padding: compact ? '0px 6px' : '1px 7px',
        fontSize: compact ? 10.5 : 11,
        fontWeight: 600,
        background: 'var(--color-surface-2)',
        color: 'var(--color-text-secondary)',
      }}
    >
      {formatPoints(points)}
    </span>
  )
}

// ── Picker ────────────────────────────────────────────────────────────────────

/**
 * Single-select over the project's OPEN sprints (a completed sprint can no
 * longer receive work — assigning to one is a 422). There is no on-the-fly
 * creation: a sprint is created by a curator in the Backlog.
 *
 * `current` keeps an already-assigned sprint selectable even when it is no
 * longer offered (it completed while the issue was open) — the same handling as
 * an archived component or a departed assignee.
 */
export function SprintPicker({
  wsId, projectId, value, current, onChange, label, ariaLabel, disabled, emptyLabel = 'No sprint',
}: {
  wsId: string | undefined
  projectId: string | undefined
  value: string
  current?: SprintRef | null
  /** '' means "no sprint" — the caller turns that into `clearSprint`. */
  onChange: (sprintId: string) => void
  label?: string
  ariaLabel?: string
  disabled?: boolean
  emptyLabel?: string
}) {
  const { data: sprints = [] } = useOpenSprints(wsId, projectId)
  const known = sprints.some(s => s.id === (current?.id ?? ''))
  return (
    <Select
      label={label}
      aria-label={ariaLabel ?? label ?? 'Sprint'}
      value={value}
      disabled={disabled}
      onChange={e => onChange(e.target.value)}
    >
      <option value="">{emptyLabel}</option>
      {sprints.map(s => (
        <option key={s.id} value={s.id}>
          {s.state === 'ACTIVE' ? `${s.name} (active)` : s.name}
        </option>
      ))}
      {current && !known && <option value={current.id}>{current.name}</option>}
    </Select>
  )
}

// ── Sprint header (Scrum board strip) ─────────────────────────────────────────

/**
 * The Scrum board's header strip (HD-27): name, goal, the countdown and the
 * point total, with the curator's Complete-sprint action on the right. Kept here
 * rather than in `BoardPage` so the vocabulary matches the planning sections.
 */
export function SprintHeader({ sprint, canCurate, showPoints, onComplete }: {
  sprint: Sprint
  canCurate: boolean
  /**
   * `estimation` (HD-102 §6) — the point total is a point SUM, so it follows the
   * capability exactly as the Backlog's section headers do. A project running
   * sprints without estimating must not be shown "0 / 3 pts" anywhere.
   */
  showPoints: boolean
  onComplete: () => void
}) {
  const countdown = formatDaysRemaining(sprint.daysRemaining)
  const overdue = (sprint.daysRemaining ?? 0) < 0
  const range = formatSprintRange(sprint)
  return (
    <div
      className="flex items-center gap-3 px-5 py-2.5 border-b flex-shrink-0 flex-wrap"
      style={{ background: 'white', borderColor: 'var(--color-border)' }}
    >
      <div className="flex items-center gap-2 min-w-0">
        <Flag size={14} style={{ color: 'var(--color-brand)', flexShrink: 0 }} />
        <span className="font-bold truncate" style={{ fontSize: 14 }}>{sprint.name}</span>
        <SprintStateBadge state={sprint.state} compact />
      </div>

      {sprint.goal && (
        // Truncates on the strip, full text on hover — a goal is a sentence, not a label.
        <span
          className="text-sm truncate min-w-0"
          title={sprint.goal}
          style={{ color: 'var(--color-text-secondary)', maxWidth: 420 }}
        >
          {sprint.goal}
        </span>
      )}

      <div className="flex items-center gap-2 ml-auto flex-shrink-0">
        {range && (
          <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{range}</span>
        )}
        {countdown && (
          <span
            className="mono text-xs font-semibold"
            style={{ color: overdue ? 'var(--color-warning)' : 'var(--color-text-secondary)' }}
          >
            {countdown}
          </span>
        )}
        {showPoints && <SprintPointsBadge stats={sprint} compact />}
        {canCurate && (
          <Button variant="secondary" size="sm" onClick={onComplete}>Complete sprint</Button>
        )}
      </div>
    </div>
  )
}

// ── Create / edit ─────────────────────────────────────────────────────────────

/**
 * Create or re-plan a sprint. The name may be left blank on create — the server
 * then names it "Sprint {sequence}", which is what most teams want.
 * `startAt`/`endAt` here are a PLAN only; they do not start anything — unless
 * `alsoStart` is set, which is the board empty state's one-gesture affordance.
 *
 * This dialog is ALSO the first-use entry point for sprint planning (Rule C,
 * HD-102 §5.3): with `enableIterations` it carries the reversibility notice and
 * turns the capability on in the same submit that creates the sprint. That is why
 * the Backlog can offer "Plan in sprints" to a Kanban project at all — the entry
 * point to sprints is not gated by sprints.
 */
export function SprintFormDialog({
  wsId, projectId, sprint, enableIterations, alsoStart, onClose, onSaved,
}: {
  wsId: string
  projectId: string
  /** null = create. */
  sprint: Sprint | null
  /**
   * The project has iterations OFF and this submit is the gesture that turns them
   * on. Ignored when editing — an existing sprint proves the switch already
   * happened, or that a curator turned it back off (Rule B keeps it visible).
   */
  enableIterations?: boolean
  /** Create it and start it in one gesture (board empty state, §6). */
  alsoStart?: boolean
  onClose: () => void
  onSaved: (sprint: Sprint) => void
}) {
  const [name, setName] = useState(sprint?.name ?? '')
  const [goal, setGoal] = useState(sprint?.goal ?? '')
  const [startAt, setStartAt] = useState(sprint?.startAt ? toLocalInputValue(sprint.startAt) : '')
  const [endAt, setEndAt] = useState(sprint?.endAt ? toLocalInputValue(sprint.endAt) : '')
  const [error, setError] = useState('')
  const { create, update, start } = useSprintMutations(wsId, projectId)
  const enabling = useEnableCapability(wsId, projectId)
  const bootstrapping = !sprint && !!enableIterations

  async function submit() {
    setError('')
    const trimmedName = name.trim()
    const trimmedGoal = goal.trim()
    if (sprint) {
      const payload: UpdateSprintPayload = {
        name: trimmedName || undefined,
        goal: trimmedGoal,
        // A blank date field means "unset it" — a plain null can't be told apart
        // from "not sent" server-side, hence the explicit clear flags.
        ...(startAt ? { startAt: fromLocalInputValue(startAt) } : { clearStartAt: true }),
        ...(endAt ? { endAt: fromLocalInputValue(endAt) } : { clearEndAt: true }),
      }
      update.mutate({ id: sprint.id, payload }, {
        onSuccess: onSaved,
        onError: e => setError(apiErrorText(e, 'Could not save the sprint')),
      })
      return
    }
    const payload: CreateSprintPayload = {
      name: trimmedName || undefined,
      goal: trimmedGoal || undefined,
      startAt: fromLocalInputValue(startAt),
      endAt: fromLocalInputValue(endAt),
    }
    try {
      // Capability FIRST, then the sprint. The reverse order can leave a sprint
      // on a project that still says it doesn't plan in sprints (visible but
      // read-only under Rule B) — confusing, and the exact shape of the dead end
      // this ticket removes. This way a failure leaves the project switched on
      // with no sprint yet, which the Backlog itself invites you to fix.
      if (bootstrapping) await enabling.enable('iterations')
      const created = await create.mutateAsync(payload)
      if (!alsoStart) { onSaved(created); return }
      const started = await start.mutateAsync({
        id: created.id,
        payload: {
          // Blank "planned start" means "now"; a blank end lets the server apply
          // `app.agile.default-sprint-length-days`.
          startAt: fromLocalInputValue(startAt) ?? new Date().toISOString(),
          endAt: fromLocalInputValue(endAt),
          goal: trimmedGoal || undefined,
        },
      })
      onSaved(started)
    } catch (e) {
      setError(apiErrorText(e, 'Could not create the sprint'))
    }
  }

  const saving = create.isPending || update.isPending || start.isPending || enabling.isPending
  const title = sprint ? `Edit “${sprint.name}”`
    : alsoStart ? 'Create and start a sprint'
    : 'New sprint'
  return (
    <Modal title={title} onClose={onClose}>
      <div className="flex flex-col gap-3">
        {/* Rule C: the switch is announced, and said to be reversible, in the
            dialog's own copy — before the click, not in a tooltip. */}
        {bootstrapping && <ReversibleNotice capability="iterations" />}

        <div className="flex flex-col gap-1">
          <Input
            label="Name"
            value={name}
            maxLength={60}
            autoFocus
            placeholder={sprint ? '' : 'Leave blank for the next number'}
            onChange={e => setName(e.target.value)}
          />
          {!sprint && (
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              Blank names it “Sprint {'{'}n{'}'}” automatically. Names are unique within the project.
            </span>
          )}
        </div>

        <Textarea
          label="Goal"
          rows={2}
          value={goal}
          maxLength={500}
          placeholder="What is this iteration for?"
          onChange={e => setGoal(e.target.value)}
        />

        <div className="grid grid-cols-2 gap-3">
          <Input label={alsoStart ? 'Starts' : 'Planned start'} type="datetime-local" value={startAt}
                 onChange={e => setStartAt(e.target.value)} />
          <Input label={alsoStart ? 'Ends' : 'Planned end'} type="datetime-local" value={endAt}
                 onChange={e => setEndAt(e.target.value)} />
        </div>
        <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          {alsoStart
            ? 'Leave the start blank to begin now, and the end blank to use the project’s default '
              + 'sprint length.'
            : 'Dates are a plan only — the sprint does not begin until you start it.'}
        </span>

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={saving} onClick={submit}>
            {sprint ? 'Save' : alsoStart ? 'Create & start' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ── Start ─────────────────────────────────────────────────────────────────────

/**
 * "Start sprint" — the dates (pre-filled: now, and the stored end or blank) plus
 * an optional last-minute goal. Starting an EMPTY sprint is allowed by design:
 * blocking it would only push teams to file a placeholder issue. We warn.
 */
export function StartSprintDialog({ wsId, projectId, sprint, issueCount, onClose, onStarted }: {
  wsId: string
  projectId: string
  sprint: Sprint
  /** Section issue count, so the "this sprint is empty" warning is honest. */
  issueCount: number
  onClose: () => void
  onStarted: (sprint: Sprint) => void
}) {
  const [startAt, setStartAt] = useState(toLocalInputValue(sprint.startAt ?? undefined))
  const [endAt, setEndAt] = useState(sprint.endAt ? toLocalInputValue(sprint.endAt) : '')
  const [goal, setGoal] = useState(sprint.goal ?? '')
  const [error, setError] = useState('')
  const { start } = useSprintMutations(wsId, projectId)

  function submit() {
    setError('')
    start.mutate({
      id: sprint.id,
      payload: {
        startAt: fromLocalInputValue(startAt),
        // Omitted = the server's `app.agile.default-sprint-length-days` (14).
        endAt: fromLocalInputValue(endAt),
        goal: goal.trim() || undefined,
      },
    }, {
      onSuccess: onStarted,
      // 409 — not FUTURE, or another sprint is already active.
      onError: e => setError(apiErrorText(e, 'Could not start the sprint')),
    })
  }

  return (
    <Modal title={`Start “${sprint.name}”?`} onClose={onClose}>
      <div className="flex flex-col gap-3">
        {issueCount === 0 && (
          <div className="text-sm rounded-lg px-3 py-2.5"
               style={{
                 background: 'color-mix(in srgb, var(--color-warning) 12%, white)',
                 border: '1px solid color-mix(in srgb, var(--color-warning) 34%, white)',
                 color: 'var(--color-text-secondary)',
               }}>
            This sprint has no issues yet. You can still start it and pull work in as you go.
          </div>
        )}

        <div className="grid grid-cols-2 gap-3">
          <Input label="Starts" type="datetime-local" value={startAt}
                 onChange={e => setStartAt(e.target.value)} />
          <div className="flex flex-col gap-1">
            <Input label="Ends" type="datetime-local" value={endAt}
                   onChange={e => setEndAt(e.target.value)} />
          </div>
        </div>
        <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          Leave the end date blank to use the project’s default sprint length.
        </span>

        <Textarea label="Goal" rows={2} value={goal} maxLength={500}
                  placeholder="What does this sprint deliver?"
                  onChange={e => setGoal(e.target.value)} />

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={start.isPending} onClick={submit}>Start sprint</Button>
        </div>
      </div>
    </Modal>
  )
}

// ── Complete ──────────────────────────────────────────────────────────────────

/**
 * "Complete sprint" — shared by the backlog section header and the Scrum board
 * header (HD-27), so the decision reads identically wherever it is taken.
 *
 * The counters come from `completion-preview` (readable by any member) so the
 * dialog never guesses; the single decision is where the unfinished work goes —
 * back to the ranked backlog (default) or into a named FUTURE sprint. DONE
 * issues always keep this sprint: that is its record of what it delivered.
 */
export function CompleteSprintDialog({ wsId, projectId, sprint, onClose, onCompleted, onCreateSprint }: {
  wsId: string
  projectId: string
  sprint: Sprint
  onClose: () => void
  onCompleted: (result: SprintCompletionResult) => void
  /** Offered when there is no FUTURE sprint to carry the work into. */
  onCreateSprint?: () => void
}) {
  const [disposition, setDisposition] = useState<UnfinishedDisposition>('BACKLOG')
  const [targetSprintId, setTargetSprintId] = useState('')
  const [error, setError] = useState('')
  const { complete } = useSprintMutations(wsId, projectId)

  const { data: preview, isLoading, isError } = useQuery<SprintCompletionPreview>({
    queryKey: ['sprint', wsId, projectId, sprint.id, 'completion-preview'],
    queryFn: () => sprintsApi.completionPreview(wsId, projectId, sprint.id),
  })

  const targets = preview?.targetCandidates ?? []
  const unfinished = preview?.unfinishedIssueCount ?? 0
  const needsTarget = disposition === 'SPRINT'

  function submit() {
    setError('')
    if (needsTarget && !targetSprintId) {
      setError('Choose the sprint the unfinished issues move to.')
      return
    }
    complete.mutate({
      id: sprint.id,
      moveUnfinishedTo: disposition,
      targetSprintId: needsTarget ? targetSprintId : undefined,
    }, {
      onSuccess: onCompleted,
      // 409 — not ACTIVE any more (someone else completed it, or a double-click).
      // 422 — the target is not a FUTURE sprint of this project.
      onError: e => setError(apiErrorText(e, 'Could not complete the sprint')),
    })
  }

  return (
    <Modal title={`Complete “${sprint.name}”?`} onClose={onClose} width={480}>
      <div className="flex flex-col gap-4">
        {isLoading ? (
          <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>
            loading the sprint summary…
          </span>
        ) : isError || !preview ? (
          <p className="text-sm" style={{ color: 'var(--color-error)' }}>
            The sprint summary could not be loaded. Completing now would report numbers we can’t
            verify — close this and try again.
          </p>
        ) : (
          <>
            <div
              className="text-sm rounded-lg px-3 py-2.5"
              style={{
                background: 'color-mix(in srgb, var(--color-brand) 8%, white)',
                border: '1px solid color-mix(in srgb, var(--color-brand) 26%, white)',
                color: 'var(--color-text-secondary)',
              }}
            >
              <span className="mono font-semibold">{preview.doneIssueCount}</span> of{' '}
              <span className="mono font-semibold">{preview.totalIssueCount}</span> issues done
              {preview.totalPoints !== null && preview.totalPoints !== undefined && (
                <>
                  {' · '}
                  <span className="mono font-semibold">{formatPoints(preview.donePoints)}</span> of{' '}
                  <span className="mono font-semibold">{formatPoints(preview.totalPoints)}</span> points
                </>
              )}
              .
              <div className="mt-1">
                {unfinished === 0
                  ? 'Nothing is left unfinished — completing changes nothing else.'
                  : <>
                      <span className="mono font-semibold">{unfinished}</span> unfinished issue
                      {unfinished === 1 ? '' : 's'} will move; done issues stay on this sprint as its
                      record.
                    </>}
              </div>
            </div>

            {unfinished > 0 && (
              <fieldset className="flex flex-col gap-2">
                <legend className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Move the unfinished issues to
                </legend>

                <label className="flex items-start gap-2 text-sm cursor-pointer">
                  <input
                    type="radio"
                    name="disposition"
                    checked={disposition === 'BACKLOG'}
                    onChange={() => setDisposition('BACKLOG')}
                    style={{ accentColor: 'var(--color-brand)', marginTop: 3 }}
                  />
                  <span style={{ color: 'var(--color-text-secondary)' }}>
                    The backlog — they keep their rank and their relative order.
                  </span>
                </label>

                <label className="flex items-start gap-2 text-sm cursor-pointer">
                  <input
                    type="radio"
                    name="disposition"
                    checked={disposition === 'SPRINT'}
                    disabled={targets.length === 0}
                    onChange={() => setDisposition('SPRINT')}
                    style={{ accentColor: 'var(--color-brand)', marginTop: 3 }}
                  />
                  <span style={{ color: 'var(--color-text-secondary)' }}>A planned sprint</span>
                </label>

                {targets.length > 0 ? (
                  <div style={{ paddingLeft: 24 }}>
                    <Select
                      aria-label="Target sprint"
                      value={targetSprintId}
                      disabled={disposition !== 'SPRINT'}
                      onChange={e => setTargetSprintId(e.target.value)}
                    >
                      <option value="">Choose a sprint…</option>
                      {targets.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                    </Select>
                  </div>
                ) : (
                  <div className="flex items-center gap-2 flex-wrap" style={{ paddingLeft: 24 }}>
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                      This project has no planned sprint to carry the work into.
                    </span>
                    {onCreateSprint && (
                      <button
                        type="button"
                        onClick={onCreateSprint}
                        className="text-xs cursor-pointer hover:underline"
                        style={{ color: 'var(--color-brand)' }}
                      >
                        Create sprint
                      </button>
                    )}
                  </div>
                )}
              </fieldset>
            )}
          </>
        )}

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={complete.isPending}
            disabled={isLoading || isError || !preview || (needsTarget && !targetSprintId)}
            onClick={submit}
          >
            Complete sprint
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ── Delete ────────────────────────────────────────────────────────────────────

/**
 * Delete a sprint. An ACTIVE one is a 409 ("complete it first"); a
 * FUTURE/COMPLETED one holding issues is a 409 unless `force`, which nulls their
 * sprint and PRESERVES their rank.
 */
export function DeleteSprintDialog({ wsId, projectId, sprint, issueCount, onClose, onDeleted }: {
  wsId: string
  projectId: string
  sprint: Sprint
  issueCount: number
  onClose: () => void
  onDeleted: () => void
}) {
  const [error, setError] = useState('')
  const { remove } = useSprintMutations(wsId, projectId)
  const holds = issueCount > 0

  return (
    <Modal title={`Delete “${sprint.name}”?`} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <div className="text-sm rounded-lg px-3 py-2.5"
             style={{
               background: 'color-mix(in srgb, var(--color-warning) 12%, white)',
               border: '1px solid color-mix(in srgb, var(--color-warning) 34%, white)',
               color: 'var(--color-text-secondary)',
             }}>
          {holds
            ? <>
                It still holds <span className="mono font-semibold">{issueCount}</span> issue
                {issueCount === 1 ? '' : 's'}. They are not deleted — they return to the ranked
                backlog and keep their order.
              </>
            : <>It holds no issues — safe to delete.</>}
        </div>

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="danger"
            loading={remove.isPending}
            onClick={() => {
              setError('')
              remove.mutate({ id: sprint.id, force: holds }, {
                onSuccess: onDeleted,
                onError: e => setError(apiErrorText(e, 'Could not delete the sprint')),
              })
            }}
          >
            Delete
          </Button>
        </div>
      </div>
    </Modal>
  )
}
