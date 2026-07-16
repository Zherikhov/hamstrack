import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Plus, Filter } from 'lucide-react'
import { apiGetProjectConfig, apiListIssues, apiListWorkspaceMembers } from '../api'
import { useAuthStore } from '../auth'
import { forgetProject } from '../recentProjects'
import { Button, StatusBadge, PriorityBadge, Avatar } from '../components/ui'
import { FieldValueDisplay } from '../components/fields'
import { useUiStore } from '../uiStore'
import IssueSidePanel from './IssueSidePanel'
import type { Issue, ProjectField, WorkspaceMember } from '../types'

/** Backlog — every issue that is not in a DONE-category status, as a flat list. */
export default function BacklogPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const navigate = useNavigate()
  const { user } = useAuthStore()
  const openCreateIssue = useUiStore(s => s.openCreateIssue)
  const [openIssueNumber, setOpenIssueNumber] = useState<number | undefined>(undefined)
  const [filterStatusId, setFilterStatusId] = useState<string>('')
  const [filterPriority, setFilterPriority] = useState<string>('')

  const { data: config } = useQuery({
    queryKey: ['projectConfig', wsId, projectId],
    queryFn: () => apiGetProjectConfig(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const statuses = config?.statuses ?? []
  const issueTypes = config?.issueTypes ?? []
  const priorities = config?.priorities ?? []
  const fields = config?.fields ?? []

  // Only needed to display USER-type field values by name
  const { data: members = [] } = useQuery({
    queryKey: ['wsMembers', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId && fields.some(f => f.type === 'USER'),
  })

  const openStatuses = statuses.filter(s => s.category !== 'DONE')

  const { data: allIssues = [], isLoading, isError } = useQuery({
    queryKey: ['issues', wsId, projectId, 'backlog', filterStatusId, filterPriority],
    queryFn: () => apiListIssues(wsId!, projectId!, {
      statusId: filterStatusId || undefined,
      priorityId: filterPriority || undefined,
    }),
    enabled: !!wsId && !!projectId,
  })

  // Project gone or access revoked — drop it from the recency journal so the
  // "/" redirect stops pointing here
  useEffect(() => {
    if (isError && user && projectId) forgetProject(user.id, projectId)
  }, [isError, user, projectId])

  const issues = allIssues.filter(i => i.status.category !== 'DONE')

  const panelOpen = openIssueNumber !== undefined

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
            <span className="font-display font-bold text-sm truncate">Backlog</span>
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>open issues only</span>
          </div>
        </div>

        {/* Filter bar */}
        <div
          className="flex items-center gap-2 px-5 py-2 border-b flex-shrink-0"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <Filter size={13} style={{ color: 'var(--color-text-muted)' }} />
          <select
            value={filterStatusId}
            onChange={e => setFilterStatusId(e.target.value)}
            className="text-xs px-2 py-1 rounded border cursor-pointer outline-none"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)', background: 'white' }}
          >
            <option value="">All open statuses</option>
            {openStatuses.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <select
            value={filterPriority}
            onChange={e => setFilterPriority(e.target.value)}
            className="text-xs px-2 py-1 rounded border cursor-pointer outline-none"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)', background: 'white' }}
          >
            <option value="">All priorities</option>
            {priorities.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          {(filterStatusId || filterPriority) && (
            <button
              className="text-xs cursor-pointer hover:underline"
              style={{ color: 'var(--color-text-muted)' }}
              onClick={() => { setFilterStatusId(''); setFilterPriority('') }}
            >
              Clear
            </button>
          )}
          <span className="ml-auto mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {issues.length} issue{issues.length !== 1 ? 's' : ''}
          </span>
        </div>

        {/* Issue list */}
        <div className="flex-1 overflow-y-auto" style={{ background: 'var(--color-surface)' }}>
          {isLoading ? (
            <div className="flex items-center justify-center py-16">
              <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
            </div>
          ) : issues.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 gap-3">
              <span className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                {filterStatusId || filterPriority ? 'No issues match the filter' : 'Backlog is empty'}
              </span>
              {!filterStatusId && !filterPriority && (
                <Button variant="secondary" size="sm" onClick={openCreateIssue}>
                  <Plus size={14} />
                  Create issue
                </Button>
              )}
            </div>
          ) : (
            <table className="w-full border-collapse">
              <thead>
                <tr style={{ borderBottom: '1px solid var(--color-border)' }}>
                  {['Key', 'Title', 'Status', 'Priority', 'Type', 'Assignee', ...fields.map(f => f.name)].map(h => (
                    <th
                      key={h}
                      className="text-left px-4 py-2 text-xs font-medium"
                      style={{ color: 'var(--color-text-muted)', background: 'white', position: 'sticky', top: 0, borderBottom: '1px solid var(--color-border)' }}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {issues.map(issue => (
                  <IssueRow
                    key={issue.id}
                    issue={issue}
                    fields={fields}
                    members={members}
                    active={openIssueNumber === issue.number}
                    onClick={() => setOpenIssueNumber(
                      openIssueNumber === issue.number ? undefined : issue.number
                    )}
                  />
                ))}
              </tbody>
            </table>
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
          priorities={priorities}
          fields={fields}
          onClose={() => setOpenIssueNumber(undefined)}
        />
      )}
    </div>
  )
}

function IssueRow({ issue, fields, members, active, onClick }: {
  issue: Issue; fields: ProjectField[]; members: WorkspaceMember[]; active: boolean; onClick: () => void
}) {
  const values = Object.fromEntries(issue.fields.map(f => [f.fieldId, f.value]))
  return (
    <tr
      onClick={onClick}
      className="cursor-pointer border-b transition-colors"
      style={{
        borderColor: 'var(--color-border)',
        background: active ? 'var(--color-surface-2)' : 'white',
      }}
      onMouseEnter={e => { if (!active) e.currentTarget.style.background = 'var(--color-surface)' }}
      onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'white' }}
    >
      <td className="px-4 py-2.5">
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{issue.key}</span>
      </td>
      {/* Inline maxWidth: max-w-xs resolves to 4px under our @theme spacing scale */}
      <td className="px-4 py-2.5" style={{ maxWidth: 320 }}>
        <span className="text-sm truncate block" style={{ color: 'var(--color-text)' }}>{issue.title}</span>
      </td>
      <td className="px-4 py-2.5">
        <StatusBadge name={issue.status.name} category={issue.status.category} color={issue.status.color} />
      </td>
      <td className="px-4 py-2.5">
        <PriorityBadge priority={issue.priority} />
      </td>
      <td className="px-4 py-2.5">
        <span className="text-xs" style={{ color: issue.type.color }}>{issue.type.name}</span>
      </td>
      <td className="px-4 py-2.5">
        {issue.assignee ? (
          <div className="flex items-center gap-1.5">
            <Avatar name={issue.assignee.displayName} avatarUrl={issue.assignee.avatarUrl} size={20} />
            <span className="text-xs truncate max-w-24" style={{ color: 'var(--color-text-secondary)' }}>
              {issue.assignee.displayName}
            </span>
          </div>
        ) : (
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>—</span>
        )}
      </td>
      {fields.map(f => (
        <td key={f.id} className="px-4 py-2.5" style={{ maxWidth: 180 }}>
          {values[f.id] !== undefined ? (
            <FieldValueDisplay field={f} value={values[f.id]} members={members} />
          ) : (
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>—</span>
          )}
        </td>
      ))}
    </tr>
  )
}
