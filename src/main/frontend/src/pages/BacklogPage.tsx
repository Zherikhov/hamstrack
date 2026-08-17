import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertTriangle, ChevronDown, ChevronRight, Filter, GripVertical, MoreHorizontal, Plus,
  RefreshCw,
} from 'lucide-react'
import { apiGetProjectConfig, apiRankIssue } from '../api'
import type { LabelMatch, RankIssuePayload } from '../api'
import { useAuthStore } from '../auth'
import { forgetProject } from '../recentProjects'
import { boardIssuesKeyPrefix } from '../lib/queryKeys'
import {
  Avatar, Button, ChildrenProgress, ParentChip, PriorityBadge, Select, StatusBadge,
} from '../components/ui'
import { LabelChips, LabelFilter } from '../components/labels'
import { ComponentFilter } from '../components/projectComponents'
import { FixVersionFilter } from '../components/versions'
import {
  BACKLOG_SECTION, CLOSED_SPRINT_HINT, CompleteSprintDialog, DeleteSprintDialog,
  SprintFormDialog, SprintPointsBadge, SprintStateBadge, StartSprintDialog, StoryPointsChip,
  apiErrorText, formatDaysRemaining, formatSprintRange, isSprintClosed, isStaleListError,
  useBacklogView, useSprintMutations,
} from '../components/sprints'
import { CAPABILITY, CapabilityOffState, DELIVERY_SETTINGS_TAB } from '../components/delivery'
import { useProjectDelivery } from '../hooks/useProjectDelivery'
import { useUiStore } from '../uiStore'
import IssueSidePanel from './IssueSidePanel'
import type {
  BacklogSectionBase, BacklogSprintSection, BacklogView, Issue, IssueType, SectionStats, Sprint,
} from '../types'

/**
 * Why a still-open sprint section is inert (HD-102 §5.2 / §6). The sprint is
 * KEPT — nothing was moved, nothing was completed — and turning sprint planning
 * back on restores it in full.
 */
const PLANNING_OFF_HINT =
  'Sprint planning is off for this project — this sprint and its issues are kept, '
  + 'but issues can’t be moved in or out until a curator turns it back on.'

/**
 * Backlog & sprint planning (HD-23) — `/w/:wsId/p/:projectId/backlog`, wrapped in
 * `ParamKeyed` like every other project page so drag state and filters can't leak
 * across projects.
 *
 * The page is a RANK-ORDERED planning view, not a paginated table any more: one
 * `GET …/backlog` aggregate renders the open sprint sections (ACTIVE first) above
 * the ranked backlog, and pagination is replaced by the HD-79 truncation banner
 * with links into Search.
 *
 * Ordering is the project-wide rank (`issues.position`), the SAME key the board
 * orders by — so "I ranked it here" and "the board shows it there" can never
 * disagree. The client never sends a rank value: a drop resolves to
 * `{ afterIssueId, beforeIssueId }` and the server computes the midpoint.
 *
 * Drag-and-drop is the native HTML5 API (no new dependency — `BoardPage` already
 * drags natively). Native DnD is pointer-only, so EVERY drag gesture has a
 * keyboard-reachable twin in each row's kebab menu (Move to top/up/down/bottom,
 * Move to a sprint, Move to backlog) hitting the very same `POST …/rank`.
 *
 * Sections are independently refreshable (§9 risk 5): the mutation pipeline
 * refreshes only the two sections a move touched, and each header carries its own
 * refresh control — nothing here triggers a whole-view refetch on a drag.
 */
export default function BacklogPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { user } = useAuthStore()
  const openCreateIssue = useUiStore(s => s.openCreateIssue)

  const [openIssueNumber, setOpenIssueNumber] = useState<number | undefined>(undefined)

  // Server-side filters — all of them already existed on the issue list and are
  // simply forwarded to the planning endpoint, where they narrow every section.
  const [filterStatusId, setFilterStatusId] = useState('')
  const [filterPriority, setFilterPriority] = useState('')
  const [filterComponentId, setFilterComponentId] = useState('')
  const [filterFixVersionId, setFilterFixVersionId] = useState('')
  const [filterLabelIds, setFilterLabelIds] = useState<string[]>([])
  const [labelMatch, setLabelMatch] = useState<LabelMatch>('any')
  // A done, unranked issue is planning noise — hidden by default in the BACKLOG
  // section only (sprint sections always keep theirs: that is the sprint's record).
  const [includeDone, setIncludeDone] = useState(false)

  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})
  const [moveError, setMoveError] = useState('')
  const [notice, setNotice] = useState('')

  // Sprint lifecycle dialogs
  const [creatingSprint, setCreatingSprint] = useState(false)
  /**
   * Whether the create dialog was opened as the FIRST-USE gesture (Rule C). Held
   * in a ref, not derived at save time: the enabling PATCH lands in the project
   * cache mid-submit, so by the time the sprint comes back `iterations` is
   * already true and a fresh read would conclude nothing had changed — and
   * swallow the very notice that tells the user it did.
   */
  const bootstrappingRef = useRef(false)
  const [editingSprint, setEditingSprint] = useState<Sprint | null>(null)
  const [startingSprint, setStartingSprint] = useState<Sprint | null>(null)
  const [completingSprint, setCompletingSprint] = useState<Sprint | null>(null)
  const [deletingSprint, setDeletingSprint] = useState<Sprint | null>(null)

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
  const openStatuses = statuses.filter(s => s.category !== 'DONE')

  // HD-102: what this project declares, not what its data implies. `needsRole`
  // because this page renders curator-only sprint controls.
  const { iterations, releases, estimation, isCurator, project } =
    useProjectDelivery(wsId, projectId, { needsRole: true })
  // The curator predicate can go true from the WORKSPACE role alone, before the
  // project itself has loaded — and an unloaded project answers "off" for every
  // capability by design. Without this guard a Scrum project would flash the
  // "Plan in sprints" affordance at its own curator for one round trip.
  const canBootstrap = isCurator && !!project
  const { addIssues, removeIssues } = useSprintMutations(wsId, projectId)

  const filters = useMemo(() => ({
    statusId: filterStatusId || undefined,
    priorityId: filterPriority || undefined,
    componentId: filterComponentId || undefined,
    fixVersionId: filterFixVersionId || undefined,
    labelIds: filterLabelIds,
    labelMatch,
    includeDone,
  }), [filterStatusId, filterPriority, filterComponentId, filterFixVersionId,
       filterLabelIds, labelMatch, includeDone])

  const view = useBacklogView(wsId, projectId, filters)
  const data = view.data

  // Project gone or access revoked — drop it from the recency journal so the
  // "/" redirect stops pointing here
  useEffect(() => {
    if (view.isError && user && projectId) forgetProject(user.id, projectId)
  }, [view.isError, user, projectId])

  // ── Drag state ──────────────────────────────────────────────────────────────
  // `dragging` is the issue under the pointer plus the section it came from;
  // `dropAt` is the insertion point the indicator draws at (an index INTO the
  // rendered array of that section, so 0..len are all valid).
  const [dragging, setDragging] = useState<{ issue: Issue; section: string } | null>(null)
  const [dropAt, setDropAt] = useState<{ section: string; index: number } | null>(null)

  const sprintSections = data?.sprints ?? []
  // The endpoint returns OPEN sprints only, but a section can go stale into
  // COMPLETED under the user (someone else completes it, and a per-section
  // refresh patches the new state in). A completed sprint is a delivered fact:
  // it can neither receive issues nor give them up, so it is never a move target.
  const openSprints = sprintSections.map(s => s.sprint).filter(s => !isSprintClosed(s.state))
  const activeSprint = openSprints.find(s => s.state === 'ACTIVE') ?? null

  // ── What this page shows, per HD-102 §6 ─────────────────────────────────────
  // The heuristic this replaces (`boardMode === 'SCRUM' || openSprints.length >
  // 0`) is the one that produced the production dead end: it hid BOTH
  // sprint-creation entry points until a sprint existed, so a Kanban project
  // could never create its first one. Sprint CONTROLS now follow the declared
  // capability and nothing else.
  //
  // Rule B keeps the values: a sprint that is still ACTIVE or FUTURE never
  // becomes invisible just because the project stopped planning in sprints — its
  // section renders READ-ONLY (no moves in or out, no lifecycle edits) with
  // *Complete sprint* still offered to curators, because a running sprint must
  // never be hidden. A COMPLETED section is a record the endpoint only returns
  // transiently, and it follows the capability.
  const sprintsReadOnly = !iterations
  const visibleSprintSections = iterations
    ? sprintSections
    : sprintSections.filter(s => !isSprintClosed(s.sprint.state))

  /**
   * The ONE way this page opens the create-sprint dialog — from the header
   * button, from the dashed slot, from the off-state affordance and from the
   * complete-sprint dialog's "no planned sprint to carry the work into" fallback.
   * Every one of them is a legitimate first use, so none of them may skip the
   * capability switch (Rule C, §5.3).
   */
  function openCreateSprint() {
    bootstrappingRef.current = !iterations
    setCreatingSprint(true)
  }

  function sectionIssues(sectionId: string): Issue[] {
    if (!data) return []
    if (sectionId === BACKLOG_SECTION) return data.backlog.issues
    return data.sprints.find(s => s.sprint.id === sectionId)?.issues ?? []
  }

  /** True for a section whose sprint has completed — inert for every move. */
  function isClosedSection(sectionId: string): boolean {
    if (sectionId === BACKLOG_SECTION) return false
    return isSprintClosed(data?.sprints.find(s => s.sprint.id === sectionId)?.sprint.state)
  }

  /**
   * Why a section-to-section move is not on offer, or null when it is.
   *
   * Two different reasons, deliberately worded differently: a COMPLETED sprint is
   * a delivered fact the server itself refuses to change (422), while "planning
   * off" is a reversible project preference this UI enforces alone — the API
   * would happily take the move (Rule A, §5.1).
   */
  function moveRefusal(from: string, to: string): string | null {
    if (from === to) return null
    if (isClosedSection(from) || isClosedSection(to)) return CLOSED_SPRINT_HINT
    // Every section other than the backlog IS a sprint, so any cross-section move
    // touches one while sprint planning is off.
    if (sprintsReadOnly) return PLANNING_OFF_HINT
    return null
  }

  /** A move between these two sections is not offered — see `moveRefusal`. */
  function moveBlocked(from: string, to: string): boolean {
    return moveRefusal(from, to) !== null
  }

  // ── Rank mutation (optimistic, with rollback) ────────────────────────────────
  const rank = useMutation({
    mutationFn: ({ issue, payload }: RankVars) =>
      apiRankIssue(wsId!, projectId!, issue.number, payload),
    onMutate: async (vars: RankVars) => {
      await qc.cancelQueries({ queryKey: view.queryKey })
      const previous = qc.getQueryData<BacklogView>(view.queryKey)
      qc.setQueryData<BacklogView>(view.queryKey, old =>
        old && applyMove(old, vars.issue, vars.from, vars.to, vars.index))
      return { previous }
    },
    onError: (err, _vars, ctx) => {
      if (ctx?.previous) qc.setQueryData(view.queryKey, ctx.previous)
      if (isStaleListError(err)) {
        // Someone else re-ranked underneath us. Non-destructive: say so and pull
        // a fresh view rather than fighting over the anchors.
        setNotice('The list changed — refreshing.')
        setMoveError('')
        qc.invalidateQueries({ queryKey: view.queryKey })
      } else {
        setNotice('')
        setMoveError(apiErrorText(err, 'Could not move the issue'))
      }
    },
    onSuccess: () => { setMoveError(''); setNotice('') },
    onSettled: (_data, error, vars) => {
      if (error) return   // the error path already rolled back / invalidated
      // ONLY the two sections the move touched — never the whole planning view.
      view.refreshSections([vars.from, vars.to])
      // The board shares the rank as its vertical order, so it has to agree.
      qc.invalidateQueries({ queryKey: boardIssuesKeyPrefix(wsId, projectId) })
      qc.invalidateQueries({ queryKey: ['issue', wsId, projectId] })
    },
  })

  /**
   * Turn "issue X lands at index I of section S" into a rank request. The
   * anchors are read off the CURRENTLY rendered arrays and skip the moved issue
   * itself (an anchor equal to the moved issue is a 422).
   */
  function move(issue: Issue, from: string, to: string, index: number) {
    if (!wsId || !projectId) return
    // Backstop for every entry point (drag, kebab, "move all"): a completed
    // sprint neither takes issues in nor lets them out — the server answers 422
    // and the UI must not have offered the gesture in the first place. Same
    // backstop carries the "sprint planning is off" case.
    const refusal = moveRefusal(from, to)
    if (refusal) {
      setNotice('')
      setMoveError(refusal)
      return
    }
    const list = sectionIssues(to)
    const currentIdx = from === to ? list.findIndex(i => i.id === issue.id) : -1
    // Dropping either side of its own row changes nothing — don't spend a request.
    if (from === to && (index === currentIdx || index === currentIdx + 1)) return

    let before: Issue | undefined
    for (let i = index; i < list.length; i++) {
      if (list[i].id !== issue.id) { before = list[i]; break }
    }
    let after: Issue | undefined
    for (let i = index - 1; i >= 0; i--) {
      if (list[i].id !== issue.id) { after = list[i]; break }
    }

    const sectionChange = from !== to
    // "No anchor and no sprint change" is a 400 — that can only happen inside one
    // section holding nothing but the moved issue, which is already a no-op above.
    if (!after && !before && !sectionChange) return

    const payload: RankIssuePayload = {
      afterIssueId: after?.id,
      beforeIssueId: before?.id,
      // `version` is deliberately NOT sent: ranking is a positional,
      // last-drag-wins operation and an optimistic-lock round trip would
      // 409-storm a planning meeting (§3.3.3).
      ...(sectionChange
        ? (to === BACKLOG_SECTION ? { clearSprint: true } : { sprintId: to })
        : {}),
    }
    rank.mutate({ issue, from, to, index, payload })
  }

  /** Keyboard/menu twin of a drag: relative moves inside the issue's own section. */
  function moveWithin(issue: Issue, section: string, where: 'top' | 'up' | 'down' | 'bottom') {
    const list = sectionIssues(section)
    const idx = list.findIndex(i => i.id === issue.id)
    if (idx < 0) return
    const target = where === 'top' ? 0
      : where === 'bottom' ? list.length
      : where === 'up' ? idx - 1
      : idx + 2                    // "down" = past the next row
    move(issue, section, section, Math.max(0, Math.min(list.length, target)))
  }

  /** Keyboard/menu twin of a cross-section drag: append to the target section. */
  function moveToSection(issue: Issue, from: string, to: string) {
    if (from === to) return
    move(issue, from, to, sectionIssues(to).length)
  }

  /**
   * "Move every issue to →" from a sprint section header.
   *
   * The list handed over is the RENDERED one, whose size is bounded by
   * `sectionCap` (300 by default) — while a single bulk request may carry at most
   * `bulkMoveCap` (100 by default). Those two caps are independent properties, so
   * the payload is chunked by the one the server reports for THIS response; the
   * mutations in `useSprintMutations` do the chunking (sequential POSTs for an
   * add, bounded-concurrency DELETEs for a return to the backlog).
   *
   * A chunked run can stop half-way, so failure is never treated as "nothing
   * happened": the error carries how many issues actually moved and both touched
   * sections are refreshed before the message is shown.
   */
  function moveAllTo(section: BacklogSprintSection, target: string) {
    const from = section.sprint.id
    const refusal = moveRefusal(from, target)
    if (refusal) { setNotice(''); setMoveError(refusal); return }
    const ids = section.issues.map(i => i.id)
    if (ids.length === 0) return
    setMoveError('')
    setNotice('')
    // Undefined until the server ships the field — the mutations then fall back
    // to one issue per request rather than to a copied-in default.
    const cap = data?.bulkMoveCap
    const onError = (e: unknown) => {
      setMoveError(apiErrorText(e, 'Could not move the issues'))
      view.refreshSections([from, target])
    }
    const onSuccess = (moved: number) => {
      // A truncated section hands over only what is on screen — say so instead of
      // implying the whole section moved.
      if (section.truncated) {
        setNotice(`Moved the ${moved} loaded issue${moved === 1 ? '' : 's'}; `
          + 'the rest of the section was not on screen.')
      }
    }
    if (target === BACKLOG_SECTION) {
      removeIssues.mutate({ id: from, issueIds: ids, cap }, { onError, onSuccess })
    } else {
      addIssues.mutate({ id: target, issueIds: ids, cap }, { onError, onSuccess })
    }
  }

  // ── Drag handlers ───────────────────────────────────────────────────────────
  function onRowDragOver(e: React.DragEvent, section: string, index: number) {
    if (!dragging) return
    // No `preventDefault` ⇒ the browser refuses the drop, so a move a completed
    // sprint would reject is never even droppable (no indicator, no request).
    if (moveBlocked(dragging.section, section)) return
    e.preventDefault()
    e.stopPropagation()
    e.dataTransfer.dropEffect = 'move'
    const rect = e.currentTarget.getBoundingClientRect()
    const after = e.clientY > rect.top + rect.height / 2
    setDropAt({ section, index: after ? index + 1 : index })
  }

  function onSectionDragOver(e: React.DragEvent, section: string) {
    if (!dragging) return
    if (moveBlocked(dragging.section, section)) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    // Rows stop propagation, so reaching here means the pointer is over the
    // section's chrome/padding rather than a row — i.e. "append to the end".
    setDropAt({ section, index: sectionIssues(section).length })
  }

  function onSectionDrop(e: React.DragEvent, section: string) {
    e.preventDefault()
    const d = dragging
    const at = dropAt
    setDragging(null)
    setDropAt(null)
    if (!d) return
    const index = at && at.section === section ? at.index : sectionIssues(section).length
    move(d.issue, d.section, section, index)
  }

  const anyFilterActive = !!filterStatusId || !!filterPriority || !!filterComponentId
    || !!filterFixVersionId || filterLabelIds.length > 0 || includeDone

  function clearFilters() {
    setFilterStatusId('')
    setFilterPriority('')
    setFilterComponentId('')
    setFilterFixVersionId('')
    setFilterLabelIds([])
    setLabelMatch('any')
    setIncludeDone(false)
  }

  const panelOpen = openIssueNumber !== undefined

  if (view.isError) {
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

  const totalCount = (data?.backlog.stats.issueCount ?? 0)
    + sprintSections.reduce((n, s) => n + s.stats.issueCount, 0)

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Page header */}
        <div
          className="flex items-center justify-between gap-3 px-5 py-2.5 border-b flex-shrink-0"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-bold truncate" style={{ fontSize: 18, letterSpacing: '-0.01em' }}>
              Backlog
            </span>
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              {iterations ? 'plan iterations and rank what comes next' : 'ranked — drag to reorder'}
            </span>
          </div>
          {/* Rule C (§5.3): the entry point to sprints is NOT gated by sprints.
              A curator always has one here — it just says what it will do when
              the project doesn't plan in sprints yet. A plain member sees no
              enabling control and no sprint vocabulary at all. */}
          {canBootstrap && (
            <Button variant="secondary" size="sm" onClick={openCreateSprint}>
              <Plus size={13} /> {iterations ? 'New sprint' : CAPABILITY.iterations.enable}
            </Button>
          )}
        </div>

        {/* Filter bar — every control here is server-side and narrows EVERY section */}
        <div
          className="flex items-center gap-2 px-5 py-2 border-b flex-shrink-0 flex-wrap"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <Filter size={13} style={{ color: 'var(--color-text-muted)' }} />
          <Select compact aria-label="Filter by status" value={filterStatusId}
                  onChange={e => setFilterStatusId(e.target.value)}>
            <option value="">All open statuses</option>
            {openStatuses.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </Select>
          <Select compact aria-label="Filter by priority" value={filterPriority}
                  onChange={e => setFilterPriority(e.target.value)}>
            <option value="">All priorities</option>
            {priorities.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
          <ComponentFilter wsId={wsId} projectId={projectId}
                           value={filterComponentId} onChange={setFilterComponentId} />
          {/* §6: the fix-version filter is release vocabulary — gated on the
              declared capability, not on whether versions exist. */}
          {releases && (
            <FixVersionFilter wsId={wsId} projectId={projectId}
                              value={filterFixVersionId} onChange={setFilterFixVersionId} />
          )}
          <LabelFilter wsId={wsId} value={filterLabelIds} onChange={setFilterLabelIds}
                       match={labelMatch} onMatchChange={setLabelMatch} />

          <label className="flex items-center gap-1.5 text-xs cursor-pointer select-none"
                 style={{ color: 'var(--color-text-secondary)' }}>
            <input
              type="checkbox"
              checked={includeDone}
              onChange={e => setIncludeDone(e.target.checked)}
              style={{ accentColor: 'var(--color-brand)', width: 14, height: 14 }}
            />
            Show done
          </label>

          {anyFilterActive && (
            <button type="button" onClick={clearFilters}
                    className="text-xs cursor-pointer hover:underline rounded"
                    style={{ color: 'var(--color-text-muted)' }}>
              Clear filters
            </button>
          )}
          {moveError && <span className="text-xs" style={{ color: 'var(--color-error)' }}>{moveError}</span>}
          {notice && <span className="text-xs" style={{ color: 'var(--color-warning)' }}>{notice}</span>}
          {view.sectionError && (
            <span className="text-xs" style={{ color: 'var(--color-error)' }}>{view.sectionError}</span>
          )}
          <span className="ml-auto mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {totalCount} issue{totalCount === 1 ? '' : 's'}
          </span>
        </div>

        {/* Sections */}
        <div className="flex-1 overflow-y-auto" style={{ background: 'var(--color-surface)' }}>
          {view.isLoading ? (
            <div className="flex items-center justify-center py-16">
              <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
            </div>
          ) : !data ? null : (
            <div className="flex flex-col gap-3" style={{ padding: '14px 18px 40px', maxWidth: 1180 }}>
              {visibleSprintSections.map(section => (
                <SectionCard
                  key={section.sprint.id}
                  sectionId={section.sprint.id}
                  header={
                    <SprintSectionHeader
                      sprint={section.sprint}
                      stats={section.stats}
                      canCurate={isCurator}
                      // Rule B: still visible, but inert except for the one
                      // action a running sprint must never lose.
                      planningOff={sprintsReadOnly}
                      showPoints={estimation}
                      anotherActive={!!activeSprint && activeSprint.id !== section.sprint.id}
                      onEdit={() => setEditingSprint(section.sprint)}
                      onStart={() => setStartingSprint(section.sprint)}
                      onComplete={() => setCompletingSprint(section.sprint)}
                      onDelete={() => setDeletingSprint(section.sprint)}
                      onMoveAllTo={target => moveAllTo(section, target)}
                      // A COMPLETED sprint offers no bulk move at all: its
                      // membership is what it delivered (the server refuses both
                      // directions with a 422). Neither does any sprint while
                      // planning is off — the section is read-only.
                      moveTargets={isSprintClosed(section.sprint.state) || sprintsReadOnly ? [] : [
                        ...openSprints.filter(s => s.id !== section.sprint.id)
                          .map(s => ({ id: s.id, name: s.name })),
                        { id: BACKLOG_SECTION, name: 'Backlog' },
                      ]}
                    />
                  }
                  section={section}
                  collapsed={!!collapsed[section.sprint.id]}
                  onToggle={() => setCollapsed(c => ({ ...c, [section.sprint.id]: !c[section.sprint.id] }))}
                  refreshing={view.refreshingSection === section.sprint.id}
                  onRefresh={() => view.refreshSection(section.sprint.id)}
                  emptyText={
                    isCurator && !sprintsReadOnly
                      ? 'Drag issues here from the backlog to plan this sprint.'
                      : 'No issues planned for this sprint yet.'
                  }
                  wsId={wsId}
                  issueTypes={issueTypes}
                  openIssueNumber={openIssueNumber}
                  onOpenIssue={setOpenIssueNumber}
                  dragging={dragging}
                  dropAt={dropAt}
                  onDragStartIssue={issue => setDragging({ issue, section: section.sprint.id })}
                  onDragEnd={() => { setDragging(null); setDropAt(null) }}
                  onRowDragOver={onRowDragOver}
                  onSectionDragOver={onSectionDragOver}
                  onSectionDrop={onSectionDrop}
                  // Same rule for a single row's kebab: nothing leaves a
                  // completed sprint — or any sprint while planning is off — so
                  // it offers no "Move to →" at all.
                  menuTargets={isSprintClosed(section.sprint.state) || sprintsReadOnly ? [] : [
                    ...openSprints.filter(s => s.id !== section.sprint.id)
                      .map(s => ({ id: s.id, name: s.name })),
                    { id: BACKLOG_SECTION, name: 'Backlog' },
                  ]}
                  onMoveWithin={moveWithin}
                  onMoveToSection={moveToSection}
                />
              ))}

              {/* Sprint creation follows the CAPABILITY, never the data — and it
                  is offered in BOTH states (Rule C, §5.3). With iterations on
                  this is the familiar dashed "Create sprint" slot; with them off
                  the same slot explains the project's current way of working and
                  offers to change it, saying up front that the change is
                  reversible. It sits exactly where the sprint sections would be,
                  which is where a curator looks for them. */}
              {canBootstrap && (iterations ? (
                <button
                  type="button"
                  onClick={openCreateSprint}
                  className="flex items-center justify-center gap-1.5 text-sm cursor-pointer rounded-lg border border-dashed transition-colors hover:bg-[var(--color-surface-2)]"
                  style={{ padding: '10px 12px', borderColor: 'var(--color-border-2)', color: 'var(--color-text-secondary)' }}
                >
                  <Plus size={14} /> Create sprint
                </button>
              ) : (
                <CapabilityOffState
                  capability="iterations"
                  isCurator
                  // The switch itself happens inside the create dialog, in the
                  // same gesture that creates the first sprint — turning sprint
                  // planning on and then landing on an empty sprint area would be
                  // a second dead end, one step further along.
                  onEnable={openCreateSprint}
                />
              ))}

              <SectionCard
                sectionId={BACKLOG_SECTION}
                header={
                  <BacklogSectionHeader
                    stats={data.backlog.stats}
                    showPoints={estimation}
                    onCreateIssue={() => openCreateIssue({ projectId })}
                  />
                }
                section={data.backlog}
                collapsed={!!collapsed[BACKLOG_SECTION]}
                onToggle={() => setCollapsed(c => ({ ...c, [BACKLOG_SECTION]: !c[BACKLOG_SECTION] }))}
                refreshing={view.refreshingSection === BACKLOG_SECTION}
                onRefresh={() => view.refreshSection(BACKLOG_SECTION)}
                emptyText={anyFilterActive ? 'No issues match the filter.' : 'The backlog is empty.'}
                wsId={wsId}
                issueTypes={issueTypes}
                openIssueNumber={openIssueNumber}
                onOpenIssue={setOpenIssueNumber}
                dragging={dragging}
                dropAt={dropAt}
                onDragStartIssue={issue => setDragging({ issue, section: BACKLOG_SECTION })}
                onDragEnd={() => { setDragging(null); setDropAt(null) }}
                onRowDragOver={onRowDragOver}
                onSectionDragOver={onSectionDragOver}
                onSectionDrop={onSectionDrop}
                // "Move to a sprint" is a planning control, so it follows the
                // capability — with iterations off the ranked list is all there is.
                menuTargets={sprintsReadOnly ? [] : openSprints.map(s => ({ id: s.id, name: s.name }))}
                onMoveWithin={moveWithin}
                onMoveToSection={moveToSection}
              />
            </div>
          )}
        </div>
      </div>

      {/* Side panel — keyed so switching issues remounts it with fresh state */}
      {panelOpen && wsId && projectId && (
        <IssueSidePanel
          key={openIssueNumber}
          wsId={wsId}
          projectId={projectId}
          issueNumber={openIssueNumber!}
          issueTypes={issueTypes}
          statuses={statuses}
          transitions={transitions}
          priorities={priorities}
          fields={fields}
          onOpenIssue={setOpenIssueNumber}
          onClose={() => setOpenIssueNumber(undefined)}
        />
      )}

      {/* ── Sprint lifecycle dialogs (curator only; the server re-checks) ── */}
      {creatingSprint && wsId && projectId && (
        <SprintFormDialog
          wsId={wsId} projectId={projectId} sprint={null}
          // Rule C: the first sprint of a Kanban project turns sprint planning on
          // in the same submit. Already-on projects send no delivery PATCH at all.
          enableIterations={bootstrappingRef.current}
          onClose={() => setCreatingSprint(false)}
          onSaved={sprint => {
            setCreatingSprint(false)
            // …and the user is told what just changed, with the way back.
            if (bootstrappingRef.current) {
              setMoveError('')
              setNotice(`Sprint planning is on — ${sprint.name} is ready to plan. `
                + `You can turn it off again in Project settings → ${DELIVERY_SETTINGS_TAB}.`)
            }
          }}
        />
      )}
      {editingSprint && wsId && projectId && (
        <SprintFormDialog
          wsId={wsId} projectId={projectId} sprint={editingSprint}
          onClose={() => setEditingSprint(null)}
          onSaved={() => setEditingSprint(null)}
        />
      )}
      {startingSprint && wsId && projectId && (
        <StartSprintDialog
          wsId={wsId} projectId={projectId} sprint={startingSprint}
          issueCount={sprintSections.find(s => s.sprint.id === startingSprint.id)?.stats.issueCount ?? 0}
          onClose={() => setStartingSprint(null)}
          onStarted={() => setStartingSprint(null)}
        />
      )}
      {completingSprint && wsId && projectId && (
        <CompleteSprintDialog
          wsId={wsId} projectId={projectId} sprint={completingSprint}
          onClose={() => setCompletingSprint(null)}
          onCreateSprint={() => { setCompletingSprint(null); openCreateSprint() }}
          onCompleted={result => {
            setCompletingSprint(null)
            // The reported outcome, in the spec's own wording: "Sprint 7
            // completed — 17 points done, 2 issues moved to Sprint 8."
            const points = result.donePoints === null || result.donePoints === undefined
              ? '' : `${result.donePoints} points done, `
            const target = result.carriedOverToSprintId
              ? openSprints.find(s => s.id === result.carriedOverToSprintId)?.name ?? 'the next sprint'
              : 'the backlog'
            const carried = result.carriedOverIssueCount === 0
              ? 'nothing carried over'
              : `${result.carriedOverIssueCount} issue${result.carriedOverIssueCount === 1 ? '' : 's'} moved to ${target}`
            setNotice(`${result.sprint.name} completed — ${points}${carried}.`)
          }}
        />
      )}
      {deletingSprint && wsId && projectId && (
        <DeleteSprintDialog
          wsId={wsId} projectId={projectId} sprint={deletingSprint}
          issueCount={sprintSections.find(s => s.sprint.id === deletingSprint.id)?.stats.issueCount ?? 0}
          onClose={() => setDeletingSprint(null)}
          onDeleted={() => setDeletingSprint(null)}
        />
      )}
    </div>
  )
}

interface RankVars {
  issue: Issue
  from: string
  to: string
  index: number
  payload: RankIssuePayload
}

// ── Optimistic cache surgery ──────────────────────────────────────────────────

/** Subtract one issue's contribution from a section's totals. */
function statsWithout(stats: SectionStats, issue: Issue): SectionStats {
  const done = issue.status.category === 'DONE'
  const p = issue.storyPoints
  const estimated = p !== null && p !== undefined
  return {
    issueCount: Math.max(0, stats.issueCount - 1),
    doneIssueCount: Math.max(0, stats.doneIssueCount - (done ? 1 : 0)),
    points: stats.points === null ? null : Math.max(0, stats.points - (estimated ? p! : 0)),
    donePoints: stats.donePoints === null ? null
      : Math.max(0, stats.donePoints - (estimated && done ? p! : 0)),
    unestimatedCount: Math.max(0, stats.unestimatedCount - (estimated ? 0 : 1)),
  }
}

/** …and add it to another's. */
function statsWith(stats: SectionStats, issue: Issue): SectionStats {
  const done = issue.status.category === 'DONE'
  const p = issue.storyPoints
  const estimated = p !== null && p !== undefined
  return {
    issueCount: stats.issueCount + 1,
    doneIssueCount: stats.doneIssueCount + (done ? 1 : 0),
    points: stats.points === null ? null : stats.points + (estimated ? p! : 0),
    donePoints: stats.donePoints === null ? null : stats.donePoints + (estimated && done ? p! : 0),
    unestimatedCount: stats.unestimatedCount + (estimated ? 0 : 1),
  }
}

/**
 * Optimistic reorder/reassign inside the cached planning view, so the row lands
 * under the pointer instantly. Exactly reversed by react-query's rollback on
 * error, and replaced by the per-section refresh on success.
 */
function applyMove(view: BacklogView, issue: Issue, from: string, to: string, index: number): BacklogView {
  const sprintRef = to === BACKLOG_SECTION
    ? null
    : view.sprints.find(s => s.sprint.id === to)?.sprint ?? null
  const moved: Issue = {
    ...issue,
    sprint: sprintRef ? { id: sprintRef.id, name: sprintRef.name, state: sprintRef.state } : null,
  }

  const mapSection = <T extends BacklogSectionBase>(id: string, section: T): T => {
    let issues = section.issues
    let stats = section.stats
    const wasHere = id === from
    const removedAt = wasHere ? issues.findIndex(i => i.id === issue.id) : -1
    if (removedAt >= 0) {
      issues = issues.filter(i => i.id !== issue.id)
      if (from !== to) stats = statsWithout(stats, issue)
    }
    if (id === to) {
      // The drop index was measured against the array INCLUDING the dragged row.
      const at = removedAt >= 0 && removedAt < index ? index - 1 : index
      issues = [...issues.slice(0, at), moved, ...issues.slice(at)]
      if (from !== to) stats = statsWith(stats, moved)
    }
    return issues === section.issues && stats === section.stats
      ? section
      : { ...section, issues, stats }
  }

  return {
    ...view,
    sprints: view.sprints.map(s => mapSection(s.sprint.id, s)),
    backlog: mapSection(BACKLOG_SECTION, view.backlog),
  }
}

// ── Section shell ─────────────────────────────────────────────────────────────

function SectionCard({
  sectionId, header, section, collapsed, onToggle, refreshing, onRefresh, emptyText,
  wsId, issueTypes, openIssueNumber, onOpenIssue,
  dragging, dropAt, onDragStartIssue, onDragEnd, onRowDragOver, onSectionDragOver, onSectionDrop,
  menuTargets, onMoveWithin, onMoveToSection,
}: {
  sectionId: string
  header: React.ReactNode
  section: BacklogSectionBase
  collapsed: boolean
  onToggle: () => void
  refreshing: boolean
  onRefresh: () => void
  emptyText: string
  wsId: string | undefined
  issueTypes: IssueType[]
  openIssueNumber: number | undefined
  onOpenIssue: (n: number | undefined) => void
  dragging: { issue: Issue; section: string } | null
  dropAt: { section: string; index: number } | null
  onDragStartIssue: (issue: Issue) => void
  onDragEnd: () => void
  onRowDragOver: (e: React.DragEvent, section: string, index: number) => void
  onSectionDragOver: (e: React.DragEvent, section: string) => void
  onSectionDrop: (e: React.DragEvent, section: string) => void
  /** Sections this section's rows may be moved into from the kebab menu. */
  menuTargets: { id: string; name: string }[]
  onMoveWithin: (issue: Issue, section: string, where: 'top' | 'up' | 'down' | 'bottom') => void
  onMoveToSection: (issue: Issue, from: string, to: string) => void
}) {
  // Where the indicator draws inside THIS section (null = the drag is elsewhere).
  const dropIndex = dragging && dropAt?.section === sectionId ? dropAt.index : null
  return (
    <section
      className="rounded-lg border"
      onDragOver={e => onSectionDragOver(e, sectionId)}
      onDrop={e => onSectionDrop(e, sectionId)}
      style={{
        background: 'white',
        borderColor: dropIndex !== null ? 'var(--color-brand)' : 'var(--color-border)',
        boxShadow: 'var(--shadow-card)',
      }}
    >
      <div className="flex items-center gap-2 px-3 py-2.5 border-b flex-wrap"
           style={{ borderColor: 'var(--color-border)' }}>
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={!collapsed}
          aria-label={collapsed ? 'Expand section' : 'Collapse section'}
          className="cursor-pointer rounded p-0.5 hover:bg-[var(--color-surface-2)] transition-colors flex-shrink-0"
          style={{ color: 'var(--color-text-muted)' }}
        >
          {collapsed ? <ChevronRight size={15} /> : <ChevronDown size={15} />}
        </button>
        {header}
        {/* Per-section refresh (§9 risk 5): a section is refetched on its own,
            never by pulling the whole planning view down again. */}
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          aria-label="Refresh this section"
          title="Refresh this section"
          className="cursor-pointer rounded p-1 hover:bg-[var(--color-surface-2)] transition-colors flex-shrink-0 disabled:opacity-50"
          style={{ color: 'var(--color-text-muted)' }}
        >
          <RefreshCw size={13} className={refreshing ? 'animate-spin' : undefined} />
        </button>
      </div>

      {!collapsed && (
        <div className="flex flex-col" style={{ padding: '4px 0 6px' }}>
          {section.issues.length === 0 ? (
            <>
              <DropRule active={dropIndex !== null} />
              <p className="text-xs italic text-center px-3" style={{ padding: '14px 12px', color: 'var(--color-text-muted)' }}>
                {emptyText}
              </p>
            </>
          ) : (
            section.issues.map((issue, idx) => (
              // The dragover handler lives on the WRAPPER, not the row, so the
              // 2px indicator slot above the row can't hand the event to the
              // section (which would read as "append") and make it flicker.
              <div key={issue.id} onDragOver={e => onRowDragOver(e, sectionId, idx)}>
                <DropRule active={dropIndex === idx} />
                <IssueRow
                  issue={issue}
                  issueTypes={issueTypes}
                  active={openIssueNumber === issue.number}
                  isDragging={dragging?.issue.id === issue.id}
                  index={idx}
                  lastIndex={section.issues.length - 1}
                  sectionId={sectionId}
                  menuTargets={menuTargets}
                  onClick={() => onOpenIssue(openIssueNumber === issue.number ? undefined : issue.number)}
                  onOpenNumber={onOpenIssue}
                  onDragStart={e => {
                    e.dataTransfer.effectAllowed = 'move'
                    // Firefox refuses to start a drag without payload.
                    e.dataTransfer.setData('text/plain', issue.key)
                    onDragStartIssue(issue)
                  }}
                  onDragEnd={onDragEnd}
                  onMoveWithin={where => onMoveWithin(issue, sectionId, where)}
                  onMoveToSection={to => onMoveToSection(issue, sectionId, to)}
                />
              </div>
            ))
          )}
          {section.issues.length > 0 && (
            <DropRule active={dropIndex !== null && dropIndex >= section.issues.length} />
          )}

          {/* HD-79 truncation: pagination is gone, so say plainly what is hidden. */}
          {section.truncated && (
            <div className="flex items-center gap-2 mx-3 mt-2 rounded-md px-3 py-2 text-xs"
                 style={{
                   background: 'color-mix(in srgb, var(--color-warning) 10%, white)',
                   color: 'var(--color-text-secondary)',
                 }}>
              <AlertTriangle size={13} style={{ color: 'var(--color-warning)', flexShrink: 0 }} />
              <span>
                Showing the first <span className="mono font-semibold">{section.issues.length}</span> of{' '}
                <span className="mono font-semibold">{section.totalAvailable}</span> issues. Totals above
                cover the whole section. Narrow with filters, or use{' '}
                <Link to={`/w/${wsId}/search`} className="font-semibold hover:underline"
                      style={{ color: 'var(--color-brand)' }}>
                  Search
                </Link>{' '}
                to see them all.
              </span>
            </div>
          )}
        </div>
      )}
    </section>
  )
}

/**
 * The insertion indicator: a fixed-height slot that becomes a 2px brand-teal
 * rule when the drop would land here. Constant height, so nothing shifts under
 * the pointer while dragging.
 */
function DropRule({ active }: { active: boolean }) {
  return (
    <div
      aria-hidden
      style={{
        height: 2,
        margin: '0 10px',
        borderRadius: 2,
        background: active ? 'var(--color-brand)' : 'transparent',
        transition: 'background 80ms ease-out',
        // Never a drag target itself — a 2px strip stealing dragover events
        // would make the indicator jump between "this row" and "append".
        pointerEvents: 'none',
      }}
    />
  )
}

// ── Section headers ───────────────────────────────────────────────────────────

function SprintSectionHeader({
  sprint, stats, canCurate, planningOff, showPoints, anotherActive,
  onEdit, onStart, onComplete, onDelete, onMoveAllTo, moveTargets,
}: {
  sprint: Sprint
  stats: SectionStats
  canCurate: boolean
  /**
   * The project turned sprint planning off while this sprint is still open
   * (HD-102 §6). The section stays VISIBLE and read-only: no lifecycle edits, no
   * moves — except *Complete sprint*, which a running sprint must never lose.
   */
  planningOff: boolean
  /** `estimation` — point sums are an aggregate and follow the capability. */
  showPoints: boolean
  /** Another sprint is already running — Start must be refused with a reason. */
  anotherActive: boolean
  onEdit: () => void
  onStart: () => void
  onComplete: () => void
  onDelete: () => void
  onMoveAllTo: (target: string) => void
  moveTargets: { id: string; name: string }[]
}) {
  const range = formatSprintRange(sprint)
  const countdown = formatDaysRemaining(sprint.daysRemaining)
  const overdue = (sprint.daysRemaining ?? 0) < 0
  const startBlocked = anotherActive
  const closed = isSprintClosed(sprint.state)
  return (
    <>
      <div className="flex items-center gap-2 min-w-0">
        <span className="font-semibold truncate" style={{ fontSize: 14 }}>{sprint.name}</span>
        <SprintStateBadge state={sprint.state} compact />
        {/* Why every move control disappeared for this section. */}
        {closed && (
          <span className="text-xs flex-shrink-0" title={CLOSED_SPRINT_HINT}
                style={{ color: 'var(--color-text-muted)' }}>
            record only
          </span>
        )}
        {/* …and why an OPEN one did (Rule B: kept, visible, inert). */}
        {!closed && planningOff && (
          <span className="text-xs flex-shrink-0" title={PLANNING_OFF_HINT}
                style={{ color: 'var(--color-text-muted)', fontStyle: 'italic' }}>
            sprint planning off
          </span>
        )}
      </div>
      {range && (
        <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>{range}</span>
      )}
      {countdown && (
        <span className="mono text-xs font-semibold flex-shrink-0"
              style={{ color: overdue ? 'var(--color-warning)' : 'var(--color-text-secondary)' }}>
          {countdown}
        </span>
      )}
      {sprint.goal && (
        <span className="text-xs truncate min-w-0" title={sprint.goal}
              style={{ color: 'var(--color-text-secondary)', maxWidth: 300 }}>
          {sprint.goal}
        </span>
      )}

      <div className="flex items-center gap-2 ml-auto flex-shrink-0">
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
          {stats.issueCount} issue{stats.issueCount === 1 ? '' : 's'}
        </span>
        {showPoints && <SprintPointsBadge stats={stats} compact />}
        {canCurate && !planningOff && sprint.state === 'FUTURE' && (
          <span title={startBlocked ? 'Another sprint is already active — complete it first.' : undefined}>
            <Button variant="primary" size="sm" disabled={startBlocked} onClick={onStart}>
              Start sprint
            </Button>
          </span>
        )}
        {/* Offered even with planning off: never leave a running sprint with no
            way to end it (§6). */}
        {canCurate && sprint.state === 'ACTIVE' && (
          <Button variant="secondary" size="sm" onClick={onComplete}>Complete sprint</Button>
        )}
        {canCurate && !planningOff && (
          <KebabMenu label={`Actions for ${sprint.name}`}>
            {close => (
              <>
                <MenuItem label="Edit sprint" onClick={() => { close(); onEdit() }} />
                {moveTargets.length > 0 && stats.issueCount > 0 && (
                  <>
                    <MenuSeparator />
                    <MenuCaption>Move every issue to</MenuCaption>
                    {moveTargets.map(t => (
                      <MenuItem key={t.id} label={t.name} onClick={() => { close(); onMoveAllTo(t.id) }} />
                    ))}
                  </>
                )}
                <MenuSeparator />
                <MenuItem label="Delete sprint" danger onClick={() => { close(); onDelete() }} />
              </>
            )}
          </KebabMenu>
        )}
      </div>
    </>
  )
}

function BacklogSectionHeader({ stats, showPoints, onCreateIssue }: {
  stats: SectionStats
  /** `estimation` — the point sum is an aggregate and follows the capability. */
  showPoints: boolean
  onCreateIssue: () => void
}) {
  return (
    <>
      <span className="font-semibold" style={{ fontSize: 14 }}>Backlog</span>
      <div className="flex items-center gap-2 ml-auto flex-shrink-0">
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
          {stats.issueCount} issue{stats.issueCount === 1 ? '' : 's'}
        </span>
        {showPoints && <SprintPointsBadge stats={stats} compact />}
        <Button variant="ghost" size="sm" onClick={onCreateIssue}>
          <Plus size={13} /> Create issue
        </Button>
      </div>
    </>
  )
}

// ── Row ───────────────────────────────────────────────────────────────────────

function IssueRow({
  issue, issueTypes, active, isDragging, index, lastIndex, sectionId, menuTargets,
  onClick, onOpenNumber, onDragStart, onDragEnd, onMoveWithin, onMoveToSection,
}: {
  issue: Issue
  issueTypes: IssueType[]
  active: boolean
  isDragging: boolean
  index: number
  lastIndex: number
  sectionId: string
  menuTargets: { id: string; name: string }[]
  onClick: () => void
  onOpenNumber: (n: number) => void
  onDragStart: (e: React.DragEvent) => void
  onDragEnd: () => void
  onMoveWithin: (where: 'top' | 'up' | 'down' | 'bottom') => void
  onMoveToSection: (to: string) => void
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
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      onClick={onClick}
      className="flex items-center gap-2.5 select-none transition-colors"
      style={{
        padding: '6px 10px',
        cursor: 'pointer',
        background: active ? 'var(--color-surface-2)' : 'transparent',
        // DESIGN.md / board parity: a dragged card fades to 0.4.
        opacity: isDragging ? 0.4 : 1,
      }}
      onMouseEnter={e => { if (!active) e.currentTarget.style.background = 'var(--color-surface)' }}
      onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent' }}
    >
      <GripVertical size={13} style={{ color: 'var(--color-text-muted)', flexShrink: 0, cursor: 'grab' }} />
      <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)', minWidth: 62 }}>
        {issue.key}
      </span>
      {/* Inline maxWidth — our @theme --spacing-* scale shadows Tailwind's
          max-w-{2xs..3xl} (max-w-xl would resolve to 32px). See CLAUDE.md. */}
      <span className="text-sm truncate" style={{ color: 'var(--color-text)', flex: 1, minWidth: 0 }}>
        {issue.title}
      </span>

      {issue.parentKey && parentNumber !== undefined && Number.isFinite(parentNumber) && (
        <span className="flex-shrink-0" style={{ maxWidth: 130 }}>
          <ParentChip
            parentKey={issue.parentKey}
            color={parentColor}
            title={issue.parentTitle}
            onClick={e => { e.stopPropagation(); onOpenNumber(parentNumber) }}
          />
        </span>
      )}
      {issue.labels && issue.labels.length > 0 && (
        <span className="flex-shrink-0" style={{ maxWidth: 160 }}>
          <LabelChips labels={issue.labels} max={2} compact />
        </span>
      )}
      <span className="text-xs flex-shrink-0 truncate" style={{ color: issue.type.color, maxWidth: 90 }}>
        {issue.type.name}
      </span>
      {issue.childCount > 0 && (
        <ChildrenProgress done={issue.doneChildCount} total={issue.childCount} compact />
      )}
      <StoryPointsChip points={issue.storyPoints} compact />
      <span className="flex-shrink-0"><PriorityBadge priority={issue.priority} /></span>
      <span className="flex-shrink-0">
        <StatusBadge name={issue.status.name} category={issue.status.category} color={issue.status.color} />
      </span>
      <span className="flex-shrink-0" style={{ width: 22 }}>
        {issue.assignee
          ? <Avatar name={issue.assignee.displayName} avatarUrl={issue.assignee.avatarUrl} size={20} />
          : <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>—</span>}
      </span>

      {/* Keyboard/menu equivalent of EVERY drag gesture (§5.1) — native HTML5 DnD
          is pointer-only, so this is not optional. */}
      <span onClick={e => e.stopPropagation()} className="flex-shrink-0">
        <KebabMenu label={`Move ${issue.key}`}>
          {close => (
            <>
              <MenuCaption>Rank</MenuCaption>
              <MenuItem label="Move to top" disabled={index === 0}
                        onClick={() => { close(); onMoveWithin('top') }} />
              <MenuItem label="Move up" disabled={index === 0}
                        onClick={() => { close(); onMoveWithin('up') }} />
              <MenuItem label="Move down" disabled={index === lastIndex}
                        onClick={() => { close(); onMoveWithin('down') }} />
              <MenuItem label="Move to bottom" disabled={index === lastIndex}
                        onClick={() => { close(); onMoveWithin('bottom') }} />
              {menuTargets.length > 0 && (
                <>
                  <MenuSeparator />
                  <MenuCaption>Move to</MenuCaption>
                  {menuTargets.map(t => (
                    <MenuItem
                      key={t.id}
                      label={t.id === BACKLOG_SECTION ? 'Backlog' : t.name}
                      disabled={t.id === sectionId}
                      onClick={() => { close(); onMoveToSection(t.id) }}
                    />
                  ))}
                </>
              )}
            </>
          )}
        </KebabMenu>
      </span>
    </div>
  )
}

// ── Menu primitives ───────────────────────────────────────────────────────────

/**
 * A kebab whose trigger and every item are real buttons in DOM order, so the
 * whole menu is reachable and operable with Tab/Enter alone — the accessibility
 * contract the native-DnD decision depends on. Escape closes and returns focus.
 */
function KebabMenu({ label, children }: {
  label: string
  children: (close: () => void) => React.ReactNode
}) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const btnRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  return (
    <div className="relative" ref={wrapRef}
         onKeyDown={e => {
           if (e.key === 'Escape' && open) {
             e.preventDefault()
             setOpen(false)
             btnRef.current?.focus()
           }
         }}
         onBlur={e => {
           if (!e.currentTarget.contains(e.relatedTarget as Node)) setOpen(false)
         }}>
      <button
        ref={btnRef}
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen(o => !o)}
        className="cursor-pointer p-1 rounded hover:bg-[var(--color-surface-2)] transition-colors focus-visible:outline-2 focus-visible:outline-offset-1"
        style={{ color: 'var(--color-text-muted)', outlineColor: 'var(--color-brand)' }}
      >
        <MoreHorizontal size={15} />
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 mt-1 border rounded-md py-1 z-30"
          style={{
            minWidth: 190, background: 'var(--color-card)',
            borderColor: 'var(--color-border-2)', boxShadow: 'var(--shadow-lg)',
          }}
        >
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}

function MenuItem({ label, danger, disabled, onClick }: {
  label: string; danger?: boolean; disabled?: boolean; onClick: () => void
}) {
  return (
    <button
      role="menuitem"
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="w-full px-3 py-1.5 text-sm text-left cursor-pointer transition-colors hover:bg-[var(--color-surface-2)] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent"
      style={{ color: danger ? 'var(--color-error)' : 'var(--color-text)' }}
    >
      {label}
    </button>
  )
}

function MenuCaption({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-3 pt-1 pb-0.5 text-xs uppercase tracking-wider"
         style={{ color: 'var(--color-text-muted)' }}>
      {children}
    </div>
  )
}

function MenuSeparator() {
  return <div style={{ height: 1, margin: '4px 0', background: 'var(--color-border)' }} />
}
