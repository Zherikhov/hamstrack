import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Filter } from 'lucide-react'
import { apiListIssues, apiListIssueTypes, apiListStatuses, apiListStatusTransitions, apiUpdateIssue } from '../api'
import { useAuthStore } from '../auth'
import { forgetProject } from '../recentProjects'
import { Button, PriorityBadge, Avatar } from '../components/ui'
import IssueSidePanel from './IssueSidePanel'
import type { Issue, Status } from '../types'

export default function BoardPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const navigate = useNavigate()
  const { user } = useAuthStore()
  const qc = useQueryClient()
  const [openIssueNumber, setOpenIssueNumber] = useState<number | undefined>(undefined)
  const [filterPriority, setFilterPriority] = useState<string>('')
  const [dragging, setDragging] = useState<Issue | null>(null)
  const [dragOverStatusId, setDragOverStatusId] = useState<string | null>(null)
  const [moveError, setMoveError] = useState<string>('')

  const { data: issueTypes = [] } = useQuery({
    queryKey: ['issueTypes', wsId],
    queryFn: () => apiListIssueTypes(wsId!),
    enabled: !!wsId,
  })

  const { data: statuses = [] } = useQuery({
    queryKey: ['statuses', wsId],
    queryFn: () => apiListStatuses(wsId!),
    enabled: !!wsId,
  })

  const { data: transitions = [] } = useQuery({
    queryKey: ['statusTransitions', wsId],
    queryFn: () => apiListStatusTransitions(wsId!),
    enabled: !!wsId,
  })

  const issuesKey = ['issues', wsId, projectId, 'board', filterPriority]
  const { data: issues = [], isLoading, isError } = useQuery({
    queryKey: issuesKey,
    queryFn: () => apiListIssues(wsId!, projectId!, { priority: filterPriority || undefined }),
    enabled: !!wsId && !!projectId,
  })

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
      const previous = qc.getQueryData<Issue[]>(issuesKey)
      const target = statuses.find(s => s.id === statusId)
      if (target) {
        qc.setQueryData<Issue[]>(issuesKey, old =>
          old?.map(i => (i.id === issue.id ? { ...i, status: target } : i)) ?? [])
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
      qc.setQueryData<Issue[]>(issuesKey, old =>
        old?.map(i => (i.id === updated.id ? updated : i)) ?? [])
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['issue', wsId, projectId] })
    },
  })

  // Workflow rules: if a status has outgoing transitions defined, only those
  // targets are allowed; a status with none defined can move anywhere.
  function isMoveAllowed(from: Status, toStatusId: string): boolean {
    if (from.id === toStatusId) return false
    const outgoing = transitions.filter(t => t.fromStatusId === from.id)
    if (outgoing.length === 0) return true
    return outgoing.some(t => t.toStatusId === toStatusId)
  }

  function handleDrop(statusId: string) {
    setDragOverStatusId(null)
    if (!dragging) return
    const issue = dragging
    setDragging(null)
    if (!isMoveAllowed(issue.status, statusId)) return
    moveMutation.mutate({ issue, statusId })
  }

  const panelOpen = openIssueNumber !== undefined
  const ordered = [...statuses].sort((a, b) => a.position - b.position)

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
      {/* Main content */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Page header */}
        <div
          className="flex items-center justify-between px-5 py-2.5 border-b flex-shrink-0"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-display font-bold text-sm truncate">Board</span>
          </div>
        </div>

        {/* Filter bar */}
        <div
          className="flex items-center gap-2 px-5 py-2 border-b flex-shrink-0"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <Filter size={13} style={{ color: 'var(--color-text-muted)' }} />
          <select
            value={filterPriority}
            onChange={e => setFilterPriority(e.target.value)}
            className="text-xs px-2 py-1 rounded border cursor-pointer outline-none"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)', background: 'white' }}
          >
            <option value="">All priorities</option>
            {['URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE'].map(p => <option key={p} value={p}>{p}</option>)}
          </select>
          {filterPriority && (
            <button
              className="text-xs cursor-pointer hover:underline"
              style={{ color: 'var(--color-text-muted)' }}
              onClick={() => setFilterPriority('')}
            >
              Clear
            </button>
          )}
          {moveError && (
            <span className="text-xs" style={{ color: 'var(--color-error)' }}>{moveError}</span>
          )}
          <span className="ml-auto mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {issues.length} issue{issues.length !== 1 ? 's' : ''}
          </span>
        </div>

        {/* Kanban columns */}
        <div
          className="flex-1 flex gap-3 overflow-x-auto overflow-y-hidden p-4"
          style={{ background: 'var(--color-surface)' }}
        >
          {isLoading ? (
            <div className="flex-1 flex items-center justify-center">
              <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
            </div>
          ) : (
            ordered.map(status => {
              const columnIssues = issues.filter(i => i.status.id === status.id)
              const allowed = dragging ? isMoveAllowed(dragging.status, status.id) : false
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
                  className="flex flex-col rounded-xl border transition-colors"
                  style={{
                    // Columns share the viewport width; below the min they overflow
                    // into the container's horizontal scroll
                    flex: '1 1 280px',
                    minWidth: 240,
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
                  </div>

                  {/* Cards */}
                  <div className="flex-1 flex flex-col gap-2 px-2 pb-2 overflow-y-auto">
                    {columnIssues.map(issue => (
                      <IssueCard
                        key={issue.id}
                        issue={issue}
                        active={openIssueNumber === issue.number}
                        isDragging={dragging?.id === issue.id}
                        onClick={() => setOpenIssueNumber(
                          openIssueNumber === issue.number ? undefined : issue.number
                        )}
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

      {/* Side panel — keyed so switching issues remounts it with fresh state */}
      {panelOpen && wsId && projectId && (
        <IssueSidePanel
          key={openIssueNumber}
          wsId={wsId}
          projectId={projectId}
          issueNumber={openIssueNumber!}
          issueTypes={issueTypes}
          statuses={statuses}
          onClose={() => setOpenIssueNumber(undefined)}
        />
      )}
    </div>
  )
}

function IssueCard({
  issue, active, isDragging, onClick, onDragStart, onDragEnd,
}: {
  issue: Issue
  active: boolean
  isDragging: boolean
  onClick: () => void
  onDragStart: (e: React.DragEvent) => void
  onDragEnd: () => void
}) {
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
      <div className="flex items-center justify-between gap-2 mb-1">
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{issue.key}</span>
        <PriorityBadge priority={issue.priority} />
      </div>
      <div className="text-sm mb-2" style={{ color: 'var(--color-text)', lineHeight: 1.35 }}>
        {issue.title}
      </div>
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs" style={{ color: issue.type.color }}>{issue.type.name}</span>
        {issue.assignee ? (
          <Avatar name={issue.assignee.displayName} avatarUrl={issue.assignee.avatarUrl} size={20} />
        ) : (
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>—</span>
        )}
      </div>
    </div>
  )
}
