import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminFieldSets, adminIssueTypeSets, adminPrioritySets, adminProjects, adminWorkflows } from '../../api'
import type { ProjectBindings } from '../../api'
import type { ProjectBinding } from '../../types'
import { Button } from '../../components/ui'
import { AdminTable, PageHeader } from './common'

/** Bulk-bar sentinel: leave this dimension of the selected projects untouched. */
const KEEP = '__keep__'

function bindingsOf(p: ProjectBinding): ProjectBindings {
  return {
    workflowId: p.workflowId,
    prioritySetId: p.prioritySetId,
    fieldSetId: p.fieldSetId,
    issueTypeSetId: p.issueTypeSetId,
  }
}

/**
 * The assignment matrix: every project across all workspaces × its workflow /
 * priority set / field set / issue type set, editable in place — replaces
 * Jira's scattered scheme pages. Empty select value = the system default.
 * Selecting rows opens a bulk bar that applies bindings to all of them.
 */
export default function AdminProjectsPage() {
  const qc = useQueryClient()
  const { data: projects = [] } = useQuery({ queryKey: ['admin', 'projects'], queryFn: adminProjects.list })
  const { data: workflows = [] } = useQuery({ queryKey: ['admin', 'workflows'], queryFn: adminWorkflows.list })
  const { data: prioritySets = [] } = useQuery({ queryKey: ['admin', 'priority-sets'], queryFn: adminPrioritySets.list })
  const { data: fieldSets = [] } = useQuery({ queryKey: ['admin', 'field-sets'], queryFn: adminFieldSets.list })
  const { data: typeSets = [] } = useQuery({ queryKey: ['admin', 'issue-type-sets'], queryFn: adminIssueTypeSets.list })
  const [wsFilter, setWsFilter] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [error, setError] = useState('')

  const workspaces = [...new Map(projects.map(p => [p.workspaceId, p.workspaceName])).entries()]
  const visible = wsFilter ? projects.filter(p => p.workspaceId === wsFilter) : projects
  const allVisibleSelected = visible.length > 0 && visible.every(p => selected.has(p.projectId))

  const update = useMutation({
    mutationFn: ({ p, patch }: { p: ProjectBinding; patch: Partial<ProjectBindings> }) =>
      adminProjects.updateBindings(p.projectId, { ...bindingsOf(p), ...patch }),
    onSuccess: () => { setError(''); qc.invalidateQueries({ queryKey: ['admin'] }) },
    onError: e => setError(e instanceof Error ? e.message : 'Update failed'),
  })

  function toggle(id: string) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function toggleAllVisible() {
    setSelected(allVisibleSelected ? new Set() : new Set(visible.map(p => p.projectId)))
  }

  const dimensions = [
    { key: 'workflowId' as const, header: 'Workflow', defaultLabel: 'Default workflow',
      options: workflows.filter(w => !w.systemDefault).map(w => ({ id: w.id, name: w.name })) },
    { key: 'prioritySetId' as const, header: 'Priorities', defaultLabel: 'Default priorities',
      options: prioritySets.filter(s => !s.systemDefault).map(s => ({ id: s.id, name: s.name })) },
    { key: 'fieldSetId' as const, header: 'Fields', defaultLabel: 'Default fields',
      options: fieldSets.filter(s => !s.systemDefault).map(s => ({ id: s.id, name: s.name })) },
    { key: 'issueTypeSetId' as const, header: 'Types', defaultLabel: 'Default types',
      options: typeSets.filter(s => !s.systemDefault).map(s => ({ id: s.id, name: s.name })) },
  ]

  const selectStyle: React.CSSProperties = {
    fontSize: 13, padding: '4px 8px', borderRadius: 'var(--radius-sm)',
    border: '1px solid var(--color-border-2)', background: 'white',
    color: 'var(--color-text)', cursor: 'pointer', maxWidth: 180,
  }

  return (
    <>
      <PageHeader
        title="Project configuration"
        subtitle="Every project × its workflow, priority set, field set and issue type set — assign everything from one screen. Empty value = the system default."
        action={
          <select value={wsFilter} onChange={e => setWsFilter(e.target.value)} style={selectStyle}>
            <option value="">All workspaces</option>
            {workspaces.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select>
        }
      />
      {error && <p className="text-xs mb-3" style={{ color: 'var(--color-error)' }}>{error}</p>}

      {selected.size > 0 && (
        <BulkBar
          count={selected.size}
          dimensions={dimensions}
          onClear={() => setSelected(new Set())}
          onApply={async patch => {
            setError('')
            const targets = projects.filter(p => selected.has(p.projectId))
            const results = await Promise.allSettled(targets.map(p =>
              adminProjects.updateBindings(p.projectId, { ...bindingsOf(p), ...patch })))
            const failed = results.filter(r => r.status === 'rejected')
            if (failed.length > 0) {
              const first = failed[0] as PromiseRejectedResult
              setError(`${failed.length} of ${targets.length} projects failed: `
                + (first.reason instanceof Error ? first.reason.message : 'update failed'))
            } else {
              setSelected(new Set())
            }
            qc.invalidateQueries({ queryKey: ['admin'] })
          }}
        />
      )}

      <AdminTable headers={['', 'Project', 'Workspace', 'Workflow', 'Priorities', 'Fields', 'Types']}>
        <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
          <td className="px-3 py-1.5" colSpan={7} style={{ background: 'var(--color-surface-2)' }}>
            <label className="inline-flex items-center gap-2 text-xs cursor-pointer select-none"
                   style={{ color: 'var(--color-text-muted)' }}>
              <input type="checkbox" checked={allVisibleSelected} onChange={toggleAllVisible}
                     className="cursor-pointer" style={{ accentColor: 'var(--color-brand)' }} />
              {allVisibleSelected ? 'Deselect all' : `Select all${wsFilter ? ' in workspace' : ''} (${visible.length})`}
            </label>
          </td>
        </tr>
        {visible.map(p => (
          <tr key={p.projectId} className="border-b"
              style={{ borderColor: 'var(--color-border)', opacity: p.archived ? 0.55 : 1,
                       background: selected.has(p.projectId) ? '#F3F7F6' : undefined }}>
            <td className="px-3 py-2.5" style={{ width: 28 }}>
              <input type="checkbox" checked={selected.has(p.projectId)} onChange={() => toggle(p.projectId)}
                     className="cursor-pointer" style={{ accentColor: 'var(--color-brand)' }} />
            </td>
            <td className="px-3 py-2.5">
              <span className="mono text-xs mr-2" style={{ color: 'var(--color-text-muted)' }}>{p.key}</span>
              <span className="text-sm">{p.name}</span>
              {p.archived && <span className="text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>archived</span>}
            </td>
            <td className="px-3 py-2.5 text-sm" style={{ color: 'var(--color-text-secondary)' }}>{p.workspaceName}</td>
            {dimensions.map(d => (
              <td key={d.key} className="px-3 py-2.5">
                <select style={selectStyle} value={p[d.key] ?? ''}
                        onChange={e => update.mutate({ p, patch: { [d.key]: e.target.value || null } })}>
                  <option value="">{d.defaultLabel}</option>
                  {d.options.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
              </td>
            ))}
          </tr>
        ))}
      </AdminTable>
      {visible.length === 0 && (
        <p className="text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>No projects yet.</p>
      )}
    </>
  )
}

function BulkBar({ count, dimensions, onApply, onClear }: {
  count: number
  dimensions: { key: keyof ProjectBindings; header: string; defaultLabel: string; options: { id: string; name: string }[] }[]
  onApply: (patch: Partial<ProjectBindings>) => Promise<void>
  onClear: () => void
}) {
  const [choice, setChoice] = useState<Record<string, string>>(
    Object.fromEntries(dimensions.map(d => [d.key, KEEP])))
  const [applying, setApplying] = useState(false)

  const patch: Partial<ProjectBindings> = {}
  for (const d of dimensions) {
    const v = choice[d.key]
    if (v !== KEEP) patch[d.key] = v === '' ? null : v
  }
  const hasChanges = Object.keys(patch).length > 0

  return (
    <div className="flex items-center gap-2 flex-wrap rounded-lg border px-4 py-3 mb-3"
         style={{ background: '#F3F7F6', borderColor: 'var(--color-brand)' }}>
      <span className="text-sm font-medium whitespace-nowrap" style={{ color: 'var(--color-brand)' }}>
        {count} project{count !== 1 ? 's' : ''} selected
      </span>
      {dimensions.map(d => (
        <label key={d.key} className="inline-flex items-center gap-1.5 text-xs"
               style={{ color: 'var(--color-text-secondary)' }}>
          {d.header}
          <select value={choice[d.key]}
                  onChange={e => setChoice(prev => ({ ...prev, [d.key]: e.target.value }))}
                  style={{ fontSize: 12, padding: '3px 6px', borderRadius: 'var(--radius-sm)',
                           border: '1px solid var(--color-border-2)', background: 'white',
                           color: 'var(--color-text)', cursor: 'pointer', maxWidth: 150 }}>
            <option value={KEEP}>— keep —</option>
            <option value="">{d.defaultLabel}</option>
            {d.options.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
          </select>
        </label>
      ))}
      <span className="ml-auto flex items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onClear}>Cancel</Button>
        <Button variant="primary" size="sm" disabled={!hasChanges} loading={applying}
                onClick={async () => { setApplying(true); try { await onApply(patch) } finally { setApplying(false) } }}>
          Apply to selected
        </Button>
      </span>
    </div>
  )
}
