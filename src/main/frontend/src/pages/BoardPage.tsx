import { useState } from 'react'
import { useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Plus, Filter } from 'lucide-react'
import { apiListIssues, apiListIssueTypes, apiListStatuses, apiGetProject } from '../api'
import { Button, StatusBadge, PriorityBadge, Avatar } from '../components/ui'
import IssueSidePanel from './IssueSidePanel'
import type { Issue } from '../types'

export default function BoardPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const [openIssueNumber, setOpenIssueNumber] = useState<number | null | undefined>(undefined)
  // undefined = panel closed, null = create mode, number = view/edit
  const [filterStatusId, setFilterStatusId] = useState<string>('')
  const [filterPriority, setFilterPriority] = useState<string>('')

  const { data: project } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })

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

  const { data: issues = [], isLoading } = useQuery({
    queryKey: ['issues', wsId, projectId, filterStatusId, filterPriority],
    queryFn: () => apiListIssues(wsId!, projectId!, {
      statusId: filterStatusId || undefined,
      priority: filterPriority || undefined,
    }),
    enabled: !!wsId && !!projectId,
  })

  const panelOpen = openIssueNumber !== undefined

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      {/* Main content */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Topbar */}
        <div
          className="flex items-center justify-between px-5 py-2.5 border-b flex-shrink-0"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-semibold text-sm truncate">{project?.name ?? '…'}</span>
            <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>
              {project?.key}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="primary" size="sm" onClick={() => setOpenIssueNumber(null)}>
              <Plus size={14} />
              New issue
            </Button>
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
            <option value="">All statuses</option>
            {statuses.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <select
            value={filterPriority}
            onChange={e => setFilterPriority(e.target.value)}
            className="text-xs px-2 py-1 rounded border cursor-pointer outline-none"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)', background: 'white' }}
          >
            <option value="">All priorities</option>
            {['URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE'].map(p => <option key={p} value={p}>{p}</option>)}
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
                {filterStatusId || filterPriority ? 'No issues match the filter' : 'No issues yet'}
              </span>
              {!filterStatusId && !filterPriority && (
                <Button variant="secondary" size="sm" onClick={() => setOpenIssueNumber(null)}>
                  <Plus size={14} />
                  Create first issue
                </Button>
              )}
            </div>
          ) : (
            <table className="w-full border-collapse">
              <thead>
                <tr style={{ borderBottom: '1px solid var(--color-border)' }}>
                  {['Key', 'Title', 'Status', 'Priority', 'Type', 'Assignee'].map(h => (
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

      {/* Side panel */}
      {panelOpen && wsId && projectId && (
        <IssueSidePanel
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

function IssueRow({ issue, active, onClick }: { issue: Issue; active: boolean; onClick: () => void }) {
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
      <td className="px-4 py-2.5 max-w-xs">
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
    </tr>
  )
}
