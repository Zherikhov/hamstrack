import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Filter, Plus } from 'lucide-react'
import { apiGetProjectConfig, apiListIssues, apiUpdateIssue } from '../api'
import { useAuthStore } from '../auth'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { forgetProject } from '../recentProjects'
import { getUiPrefs, setUiPref } from '../uiPrefs'
import type { BoardQuickFilters } from '../uiPrefs'
import { useUiStore } from '../uiStore'
import { isMoveAllowed } from '../lib/transitions'
import { boardIssuesKey } from '../lib/queryKeys'
import { Button, PriorityBadge, Avatar, ParentChip, ChildrenProgress, Select } from '../components/ui'
import IssueSidePanel from './IssueSidePanel'
import type { BoardIssues, Issue, IssueType } from '../types'

// Board issue-panel sizing (HD-54): user-draggable width, clamped and additionally
// capped at 55% of the viewport so the board never disappears. Owned by BoardPage
// so the width survives the panel's key-based remount when switching issues.
const PANEL_MIN = 360
const PANEL_MAX = 720
const PANEL_DEFAULT = 440
const panelMax = () => Math.min(PANEL_MAX, Math.floor(window.innerWidth * 0.55))
const clampPanel = (w: number) => Math.max(PANEL_MIN, Math.min(panelMax(), w))

// Drawer open/close animation (HD-56) — DESIGN.md "short" band, drawer easing
const PANEL_ANIM_MS = 200
const PANEL_EASE = 'cubic-bezier(.32,.72,0,1)'

// Quick filters (HD-43): stable "nothing selected" value so the initial state
// identity doesn't change between renders.
const EMPTY_QUICK: BoardQuickFilters = {}
const isQuickEmpty = (q: BoardQuickFilters) => !q.mine && !q.unassigned && !q.typeId

export default function BoardPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const { user } = useAuthStore()
  const qc = useQueryClient()
  // Create-issue dialog lives in AppShell — the board just opens it with this
  // project (and, from a column, that column's status) pre-selected (HD-70).
  const openCreateIssue = useUiStore(s => s.openCreateIssue)
  // Returning from the full-page issue view (HD-67) reopens that issue's drawer:
  // the "Back to board" button navigates here with { state: { openIssue } }.
  const [openIssueNumber, setOpenIssueNumber] = useState<number | undefined>(
    (location.state as { openIssue?: number } | null)?.openIssue,
  )
  const [filterPriority, setFilterPriority] = useState<string>('')
  // Quick filters (HD-43) — client-side chips over the already-loaded board data,
  // persisted per user + per project. Never sent to the API.
  const [quick, setQuick] = useState<BoardQuickFilters>(EMPTY_QUICK)
  const [dragging, setDragging] = useState<Issue | null>(null)
  const [dragOverStatusId, setDragOverStatusId] = useState<string | null>(null)
  const [moveError, setMoveError] = useState<string>('')

  const reducedMotion = useReducedMotion()

  // Resizable issue panel (HD-54) — width lives here so it persists across the
  // panel's key-based remount when switching issues.
  const [panelWidth, setPanelWidth] = useState(PANEL_DEFAULT)
  const [panelDragging, setPanelDragging] = useState(false)
  const panelWidthRef = useRef(panelWidth)
  const panelDraggingRef = useRef(false)

  useEffect(() => {
    if (!user) return
    const w = getUiPrefs(user.id).boardPanelWidth
    if (typeof w === 'number') {
      const c = clampPanel(w)
      setPanelWidth(c)
      panelWidthRef.current = c
    }
  }, [user?.id])

  // Restore this board's saved filters (HD-43). Sanitised on read — a malformed,
  // hand-edited or legacy entry degrades to "no quick filters" instead of
  // throwing. A pre-revision `typeIds` array is simply not read: the type filter
  // is a single selection now, so the legacy value is dropped (and the next
  // write-back persists the new shape without it).
  useEffect(() => {
    if (!user || !projectId) return
    const saved = getUiPrefs(user.id).boardQuickFilters?.[projectId]
    if (!saved || typeof saved !== 'object') return
    setQuick({
      mine: saved.mine === true,
      unassigned: saved.unassigned === true,
      typeId: typeof saved.typeId === 'string' && saved.typeId ? saved.typeId : undefined,
    })
  }, [user?.id, projectId])

  /**
   * Apply + persist a quick-filter change (per user, per project). A type id that
   * no longer exists in the project config is dropped here too, so a stale id
   * can't be written back (and can't resurrect if the id ever reappears).
   */
  function applyQuick(next: BoardQuickFilters) {
    const type = next.typeId
    const cleaned: BoardQuickFilters = {
      mine: !!next.mine,
      unassigned: !!next.unassigned,
      typeId: type && (issueTypes.length === 0 || issueTypes.some(t => t.id === type)) ? type : undefined,
    }
    setQuick(cleaned)
    if (!user || !projectId) return
    const all = { ...(getUiPrefs(user.id).boardQuickFilters ?? {}) }
    if (isQuickEmpty(cleaned)) delete all[projectId]
    else all[projectId] = cleaned
    setUiPref(user.id, 'boardQuickFilters', all)
  }

  // Re-clamp when the viewport shrinks (load also clamps, so no need to persist here)
  useEffect(() => {
    function onResize() {
      setPanelWidth(w => {
        const c = clampPanel(w)
        panelWidthRef.current = c
        return c
      })
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  function handlePanelResize(next: number) {
    panelWidthRef.current = next
    setPanelWidth(next)
    if (!panelDraggingRef.current && user) setUiPref(user.id, 'boardPanelWidth', next)
  }

  function handlePanelDrag(d: boolean) {
    panelDraggingRef.current = d
    setPanelDragging(d) // suppress the drawer width transition while dragging
    // Persist once at drag end, not on every pointer move
    if (!d && user) setUiPref(user.id, 'boardPanelWidth', panelWidthRef.current)
  }

  // One endpoint since M1: the project's effective workflow statuses (board
  // order), transition rules, offered priorities and issue types
  const { data: config } = useQuery({
    queryKey: ['projectConfig', wsId, projectId],
    queryFn: () => apiGetProjectConfig(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const statuses = config?.statuses ?? []
  const transitions = config?.transitions ?? []
  const issueTypes = config?.issueTypes ?? []
  const priorities = config?.priorities ?? []
  const fields = config?.fields ?? []

  // Shared key (lib/queryKeys.ts) — the create-issue dialog reads the same cache
  // entry for its parent picker, so the cached value must stay the `BoardIssues`
  // wrapper for both.
  const issuesKey = boardIssuesKey(wsId, projectId, filterPriority)
  const { data: board, isLoading, isError } = useQuery({
    queryKey: issuesKey,
    queryFn: () => apiListIssues(wsId!, projectId!, { priorityId: filterPriority || undefined }),
    enabled: !!wsId && !!projectId,
  })
  const issues = board?.issues ?? []

  // ── Quick filters (HD-43) ───────────────────────────────────────────────────
  // Composition semantics:
  //   • Assignee dimension — the "My issues" and "Unassigned" chips OR together
  //     (both on = mine OR unassigned); neither on = no assignee constraint.
  //   • Type dimension — a single selected issue type (the "All types" select);
  //     nothing selected = all types.
  //   • The two dimensions AND together, and the result ANDs with the existing
  //     server-side priority filter (which stays a query param, untouched here).
  // Applied purely client-side over the already-loaded (server-capped) board data.

  // A stale type id (a type removed from the project) is ignored — but only once
  // the config has actually loaded, so the selection doesn't blink off while it fetches.
  const activeTypeId = useMemo(() => {
    const saved = quick.typeId
    if (!saved) return undefined
    if (issueTypes.length === 0) return saved
    return issueTypes.some(t => t.id === saved) ? saved : undefined
  }, [quick.typeId, issueTypes])

  // "My issues" needs a signed-in user; without one the chip is hidden and a
  // persisted `mine` is inert rather than filtering everything away.
  const mineActive = !!quick.mine && !!user
  const unassignedActive = !!quick.unassigned
  const quickActive = mineActive || unassignedActive || !!activeTypeId

  const visibleIssues = useMemo(() => {
    if (!quickActive) return issues
    const userId = user?.id
    return issues.filter(i => {
      if (mineActive || unassignedActive) {
        const mineHit = mineActive && !!i.assignee && i.assignee.id === userId
        const unassignedHit = unassignedActive && !i.assignee
        if (!mineHit && !unassignedHit) return false
      }
      if (activeTypeId && i.type.id !== activeTypeId) return false
      return true
    })
  }, [issues, quickActive, mineActive, unassignedActive, activeTypeId, user?.id])

  /** Reset the chips + type select (the priority select is cleared alongside by "Clear filters"). */
  function resetQuick() { applyQuick(EMPTY_QUICK) }

  function clearAllFilters() {
    setFilterPriority('')
    resetQuick()
  }

  const anyFilterActive = quickActive || !!filterPriority

  // Project gone or access revoked — drop it from the recency journal so the
  // "/" redirect stops pointing here
  useEffect(() => {
    if (isError && user && projectId) forgetProject(user.id, projectId)
  }, [isError, user, projectId])

  const moveMutation = useMutation({
    mutationFn: ({ issue, statusId }: { issue: Issue; statusId: string }) =>
      apiUpdateIssue(wsId!, projectId!, issue.number, { statusId, version: issue.version }),
    onMutate: async ({ issue, statusId }) => {
      // Optimistic move: the card lands in the target column immediately
      await qc.cancelQueries({ queryKey: issuesKey })
      const previous = qc.getQueryData<BoardIssues>(issuesKey)
      const target = statuses.find(s => s.id === statusId)
      if (target) {
        qc.setQueryData<BoardIssues>(issuesKey, old =>
          old && { ...old, issues: old.issues.map(i => (i.id === issue.id ? { ...i, status: target } : i)) })
      }
      return { previous }
    },
    onError: (err, _vars, ctx) => {
      if (ctx?.previous) qc.setQueryData(issuesKey, ctx.previous)
      setMoveError(err instanceof Error ? err.message : 'Failed to move issue')
    },
    onSuccess: updated => {
      setMoveError('')
      // Replace with the server copy so the next drag carries a fresh version
      qc.setQueryData<BoardIssues>(issuesKey, old =>
        old && { ...old, issues: old.issues.map(i => (i.id === updated.id ? updated : i)) })
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['issue', wsId, projectId] })
    },
  })

  function handleDrop(statusId: string) {
    setDragOverStatusId(null)
    if (!dragging) return
    const issue = dragging
    setDragging(null)
    if (!isMoveAllowed(issue.status, statusId, transitions)) return
    moveMutation.mutate({ issue, statusId })
  }

  // config.statuses arrive in workflow (board-column) order — don't re-sort
  const ordered = statuses

  if (isError) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-3">
        <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
          Project not found — it may have been deleted, or your access was removed.
        </p>
        <Button variant="secondary" size="sm" onClick={() => navigate(`/w/${wsId}`, { state: { showAll: true } })}>
          Go to projects
        </Button>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      {/* Main content — minWidth:0 lets the kanban zone shrink (reflow) when the
          issue panel opens and take horizontal scroll instead of overflowing */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Page header — creation lives in the nav rail ("New issue") and in each
            column's quiet "+" (HD-70); no duplicate primary button here. */}
        <div
          className="flex items-center justify-between px-5 py-2.5 border-b flex-shrink-0"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-bold truncate" style={{ fontSize: 18, letterSpacing: '-0.01em' }}>Board</span>
          </div>
        </div>

        {/* Filter bar — server-side priority select, client-side type select and
            assignee chips (HD-43) */}
        <div
          className="flex items-center gap-2 px-5 py-2 border-b flex-shrink-0 flex-wrap"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <Filter size={13} style={{ color: 'var(--color-text-muted)' }} />
          <Select
            compact
            aria-label="Filter by priority"
            value={filterPriority}
            onChange={e => setFilterPriority(e.target.value)}
          >
            <option value="">All priorities</option>
            {priorities.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>

          {/* Type filter — one selection, shaped like the priority select above.
              Client-side (never sent to the API); `activeTypeId` (not the raw
              persisted value) drives it so a stale id shows as "All types". */}
          <Select
            compact
            aria-label="Filter by type"
            value={activeTypeId ?? ''}
            onChange={e => applyQuick({ ...quick, typeId: e.target.value || undefined })}
          >
            <option value="">All types</option>
            {issueTypes.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
          </Select>

          <span className="flex-shrink-0" style={{ width: 1, height: 18, background: 'var(--color-border)' }} />

          <div className="flex items-center gap-1.5 flex-wrap" role="group" aria-label="Quick filters">
            {user && (
              <Chip
                active={!!quick.mine}
                onClick={() => applyQuick({ ...quick, mine: !quick.mine })}
                title="Only issues assigned to me"
              >
                My issues
              </Chip>
            )}
            <Chip
              active={!!quick.unassigned}
              onClick={() => applyQuick({ ...quick, unassigned: !quick.unassigned })}
              title="Only issues with no assignee"
            >
              Unassigned
            </Chip>
          </div>

          {anyFilterActive && (
            <button
              type="button"
              className="text-xs cursor-pointer hover:underline rounded"
              style={{ color: 'var(--color-text-muted)' }}
              onClick={clearAllFilters}
            >
              Clear filters
            </button>
          )}
          {moveError && (
            <span className="text-xs" style={{ color: 'var(--color-error)' }}>{moveError}</span>
          )}
          <span className="ml-auto mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {quickActive
              ? `${visibleIssues.length} of ${issues.length} issues`
              : `${issues.length} issue${issues.length !== 1 ? 's' : ''}`}
          </span>
        </div>

        {/* Truncation banner (HD-79) — the board is server-capped; when the project
            (under the current filter) has more than `cap` issues, only the first
            `cap` are shown. Nudge to the paginated Backlog / Search for the full set. */}
        {board?.truncated && (
          <div
            className="flex items-center gap-2 px-5 py-2 border-b flex-shrink-0 text-xs"
            style={{
              background: 'var(--color-warning-soft, rgba(247,144,9,0.10))',
              borderColor: 'var(--color-border)',
              color: 'var(--color-text-secondary)',
            }}
          >
            <AlertTriangle size={13} style={{ color: 'var(--color-warning)', flexShrink: 0 }} />
            <span>
              Showing the first{' '}
              <span className="mono font-semibold">{board.cap}</span> of{' '}
              <span className="mono font-semibold">{board.totalAvailable}</span> issues.
              Refine with filters, or use the{' '}
              <Link
                to={`/w/${wsId}/p/${projectId}/backlog`}
                className="font-semibold hover:underline"
                style={{ color: 'var(--color-brand)' }}
              >
                Backlog
              </Link>{' '}
              or{' '}
              <Link
                to={`/w/${wsId}/search`}
                className="font-semibold hover:underline"
                style={{ color: 'var(--color-brand)' }}
              >
                Search
              </Link>{' '}
              to see them all. Quick filters only narrow the issues loaded here.
            </span>
          </div>
        )}

        {/* Kanban columns */}
        <div
          className="flex-1 flex gap-3 overflow-x-auto overflow-y-hidden p-4"
          style={{ background: 'var(--color-surface)' }}
        >
          {isLoading ? (
            <div className="flex-1 flex items-center justify-center">
              <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
            </div>
          ) : quickActive && issues.length > 0 && visibleIssues.length === 0 ? (
            // Every loaded issue is hidden by the chips — say so instead of
            // rendering a board of empty columns (HD-43)
            <div className="flex-1 flex flex-col items-center justify-center gap-3">
              <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                No issues match the current filters.
              </p>
              <Button variant="secondary" size="sm" onClick={resetQuick}>
                Reset quick filters
              </Button>
            </div>
          ) : (
            ordered.map(status => {
              const columnIssues = visibleIssues.filter(i => i.status.id === status.id)
              const allowed = dragging ? isMoveAllowed(dragging.status, status.id, transitions) : false
              const isOver = dragOverStatusId === status.id
              return (
                <div
                  key={status.id}
                  onDragOver={e => {
                    if (!dragging) return
                    if (allowed) {
                      e.preventDefault()
                      e.dataTransfer.dropEffect = 'move'
                      setDragOverStatusId(status.id)
                    }
                  }}
                  onDragLeave={e => {
                    if (!e.currentTarget.contains(e.relatedTarget as Node) && isOver) {
                      setDragOverStatusId(null)
                    }
                  }}
                  onDrop={e => { e.preventDefault(); handleDrop(status.id) }}
                  className="group flex flex-col rounded-xl border transition-colors"
                  style={{
                    // Columns share the viewport width; below the min they overflow
                    // into the container's horizontal scroll
                    flex: '1 1 280px',
                    minWidth: 220,
                    maxWidth: 420,
                    background: isOver && allowed ? '#e2efec' : 'var(--color-surface-2)',
                    borderColor: isOver && allowed ? 'var(--color-brand)' : 'var(--color-border)',
                    opacity: dragging && !allowed && dragging.status.id !== status.id ? 0.45 : 1,
                    maxHeight: '100%',
                  }}
                >
                  {/* Column header */}
                  <div className="flex items-center gap-2 px-3 py-2.5 flex-shrink-0">
                    <span
                      className="rounded-full flex-shrink-0"
                      style={{ width: 8, height: 8, background: status.color || 'var(--color-text-muted)' }}
                    />
                    <span className="text-xs font-semibold uppercase tracking-wider" style={{ color: 'var(--color-text-secondary)' }}>
                      {status.name}
                    </span>
                    <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                      {columnIssues.length}
                    </span>
                    {/* Quick add (HD-70) — a permanent, quiet affordance in every
                        column: muted at rest, stronger (ink + tinted fill) on
                        hover/focus. Every workflow status can receive a new
                        issue, so no column is excluded. */}
                    <button
                      type="button"
                      aria-label={`Create issue in ${status.name}`}
                      title={`Create issue in ${status.name}`}
                      onClick={() => openCreateIssue({ projectId, statusId: status.id })}
                      className="ml-auto flex items-center justify-center flex-shrink-0 rounded-md cursor-pointer transition-colors focus-visible:outline-2 focus-visible:outline-offset-1"
                      style={{
                        width: 22, height: 22,
                        color: 'var(--color-text-muted)',
                        background: 'transparent',
                        outlineColor: 'var(--color-brand)',
                      }}
                      onMouseEnter={e => {
                        e.currentTarget.style.background = 'var(--color-border)'
                        e.currentTarget.style.color = 'var(--color-text)'
                      }}
                      onMouseLeave={e => {
                        e.currentTarget.style.background = 'transparent'
                        e.currentTarget.style.color = 'var(--color-text-muted)'
                      }}
                      onFocus={e => { e.currentTarget.style.color = 'var(--color-text)' }}
                      onBlur={e => { e.currentTarget.style.color = 'var(--color-text-muted)' }}
                    >
                      <Plus size={14} />
                    </button>
                  </div>

                  {/* Cards */}
                  <div className="flex-1 flex flex-col gap-2 px-2 pb-2 overflow-y-auto">
                    {columnIssues.map(issue => (
                      <IssueCard
                        key={issue.id}
                        issue={issue}
                        issueTypes={issueTypes}
                        active={openIssueNumber === issue.number}
                        isDragging={dragging?.id === issue.id}
                        onClick={() => setOpenIssueNumber(
                          openIssueNumber === issue.number ? undefined : issue.number
                        )}
                        onOpenNumber={setOpenIssueNumber}
                        onDragStart={e => {
                          e.dataTransfer.effectAllowed = 'move'
                          setDragging(issue)
                        }}
                        onDragEnd={() => { setDragging(null); setDragOverStatusId(null) }}
                      />
                    ))}
                    {columnIssues.length === 0 && (
                      <div
                        className="text-xs italic text-center rounded border border-dashed py-4 mx-1"
                        style={{ color: 'var(--color-text-muted)', borderColor: 'var(--color-border-2)' }}
                      >
                        No issues
                      </div>
                    )}
                  </div>
                </div>
              )
            })
          )}
        </div>
      </div>

      {/* Side panel — drawer-animated open/close; keyed so switching issues
          remounts content with fresh state without replaying the animation */}
      {wsId && projectId && (
        <BoardIssueDrawer
          issueNumber={openIssueNumber}
          width={panelWidth}
          dragging={panelDragging}
          reducedMotion={reducedMotion}
        >
          {num => (
            <IssueSidePanel
              key={num}
              wsId={wsId}
              projectId={projectId}
              issueNumber={num}
              issueTypes={issueTypes}
              statuses={statuses}
              transitions={transitions}
              priorities={priorities}
              fields={fields}
              onOpenIssue={setOpenIssueNumber}
              onClose={() => setOpenIssueNumber(undefined)}
              width={panelWidth}
              minWidth={PANEL_MIN}
              maxWidth={panelMax}
              onResize={handlePanelResize}
              onResizeDragChange={handlePanelDrag}
            />
          )}
        </BoardIssueDrawer>
      )}
    </div>
  )
}

/**
 * Toggleable assignee filter chip (HD-43). A real button with `aria-pressed`, so
 * it is keyboard-focusable and announced as a toggle. The active state is tinted
 * brand teal (the issue-type dimension is a select, not a chip, since the HD-43
 * revision).
 */
function Chip({
  active, title, onClick, children,
}: {
  active: boolean
  title?: string
  onClick: () => void
  children: React.ReactNode
}) {
  const c = 'var(--color-brand)'
  return (
    <button
      type="button"
      aria-pressed={active}
      title={title}
      onClick={onClick}
      className="inline-flex items-center text-xs font-semibold border cursor-pointer transition-colors select-none focus-visible:outline-2 focus-visible:outline-offset-2"
      style={{
        padding: '3px 9px',
        borderRadius: 'var(--radius-sm)',
        background: active ? `color-mix(in srgb, ${c} 14%, white)` : 'white',
        borderColor: active ? c : 'var(--color-border-2)',
        color: active ? c : 'var(--color-text-secondary)',
        outlineColor: 'var(--color-brand)',
      }}
    >
      {children}
    </button>
  )
}

/**
 * Drawer wrapper for the board issue panel (HD-56). Animates its own width
 * 0 ↔ `width` so the panel slides in from the right and the kanban columns
 * reflow to make room; the inner panel is right-anchored and clipped during the
 * transition. Stays mounted through the exit (keyed off `issueNumber` going
 * undefined) so the close can animate, then unmounts. Switching issues (number
 * changes but stays defined) does not replay the animation. Instant under
 * reduced motion, and the transition is suppressed while the user is dragging
 * the resize handle so resizing stays 1:1 with the pointer.
 */
function BoardIssueDrawer({
  issueNumber, width, dragging, reducedMotion, children,
}: {
  issueNumber: number | undefined
  width: number
  dragging: boolean
  reducedMotion: boolean
  children: (issueNumber: number) => React.ReactNode
}) {
  // The issue currently rendered — retained through the exit animation.
  const [rendered, setRendered] = useState(issueNumber)
  const [expanded, setExpanded] = useState(false)
  const timer = useRef<number | undefined>(undefined)
  const rafA = useRef<number | undefined>(undefined)
  const rafB = useRef<number | undefined>(undefined)

  useEffect(() => {
    if (issueNumber !== undefined) {
      // Open or switch: keep it mounted and expand. Double rAF so the collapsed
      // (width:0) frame actually paints before we expand — a single rAF lets the
      // browser coalesce both states and the open snaps instead of animating.
      clearTimeout(timer.current)
      setRendered(issueNumber)
      rafA.current = requestAnimationFrame(() => {
        rafB.current = requestAnimationFrame(() => setExpanded(true))
      })
      return () => { cancelAnimationFrame(rafA.current!); cancelAnimationFrame(rafB.current!) }
    }
    // Close: collapse, then unmount once the exit has played.
    setExpanded(false)
    timer.current = window.setTimeout(() => setRendered(undefined), reducedMotion ? 0 : PANEL_ANIM_MS)
    return () => clearTimeout(timer.current)
  }, [issueNumber, reducedMotion])

  useEffect(() => () => {
    clearTimeout(timer.current)
    cancelAnimationFrame(rafA.current!)
    cancelAnimationFrame(rafB.current!)
  }, [])

  if (rendered === undefined) return null

  const open = expanded && issueNumber !== undefined
  return (
    <div
      style={{
        flexShrink: 0,
        width: reducedMotion ? width : (open ? width : 0),
        overflow: 'hidden',
        position: 'relative',
        willChange: 'width',
        transition: reducedMotion || dragging ? 'none' : `width ${PANEL_ANIM_MS}ms ${PANEL_EASE}`,
      }}
    >
      {/* Right-anchored so the panel reveals as a slide-in from the right */}
      <div style={{ position: 'absolute', top: 0, right: 0, bottom: 0, width }}>
        {children(rendered)}
      </div>
    </div>
  )
}

function IssueCard({
  issue, issueTypes, active, isDragging, onClick, onOpenNumber, onDragStart, onDragEnd,
}: {
  issue: Issue
  issueTypes: IssueType[]
  active: boolean
  isDragging: boolean
  onClick: () => void
  onOpenNumber: (number: number) => void
  onDragStart: (e: React.DragEvent) => void
  onDragEnd: () => void
}) {
  const parentColor = issue.parentTypeId
    ? issueTypes.find(t => t.id === issue.parentTypeId)?.color
    : undefined
  const parentNumber = issue.parentKey
    ? Number(issue.parentKey.slice(issue.parentKey.lastIndexOf('-') + 1))
    : undefined
  return (
    <div
      draggable
      onClick={onClick}
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      className="rounded-lg border px-3 py-2.5 transition-colors select-none"
      style={{
        background: 'white',
        borderColor: active ? 'var(--color-brand)' : 'var(--color-border)',
        cursor: 'grab',
        opacity: isDragging ? 0.4 : 1,
        boxShadow: '0 1px 2px rgba(28,27,25,0.05)',
      }}
      onMouseEnter={e => { if (!active) e.currentTarget.style.borderColor = 'var(--color-border-2)' }}
      onMouseLeave={e => { if (!active) e.currentTarget.style.borderColor = 'var(--color-border)' }}
    >
      {/* Parent chip — one line, truncates; stops propagation so it opens the parent */}
      {issue.parentKey && parentNumber !== undefined && Number.isFinite(parentNumber) && (
        <div className="mb-1.5 min-w-0">
          <ParentChip
            parentKey={issue.parentKey}
            color={parentColor}
            title={issue.parentTitle}
            onClick={e => { e.stopPropagation(); onOpenNumber(parentNumber) }}
          />
        </div>
      )}
      <div className="flex items-center justify-between gap-2 mb-1">
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{issue.key}</span>
        <PriorityBadge priority={issue.priority} />
      </div>
      <div className="text-sm mb-2" style={{ color: 'var(--color-text)', lineHeight: 1.35 }}>
        {issue.title}
      </div>
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 min-w-0">
          <span className="text-xs truncate" style={{ color: issue.type.color }}>{issue.type.name}</span>
          {issue.childCount > 0 && (
            <ChildrenProgress done={issue.doneChildCount} total={issue.childCount} compact />
          )}
        </div>
        {issue.assignee ? (
          <Avatar name={issue.assignee.displayName} avatarUrl={issue.assignee.avatarUrl} size={20} />
        ) : (
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>—</span>
        )}
      </div>
    </div>
  )
}
