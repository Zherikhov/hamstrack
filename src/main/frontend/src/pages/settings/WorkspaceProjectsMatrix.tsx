import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { workspaceAdminApi } from '../../api'
import type { BindingOptions, ProjectBinding, SetOption } from '../../types'
import { AdminTable, PageHeader } from '../admin/common'
import { Select } from '../../components/ui'

type Dim = 'workflowId' | 'prioritySetId' | 'fieldSetId' | 'issueTypeSetId'

const DIMENSIONS: { key: Dim; optionsKey: keyof BindingOptions; header: string }[] = [
  { key: 'workflowId', optionsKey: 'workflows', header: 'Workflow' },
  { key: 'issueTypeSetId', optionsKey: 'issueTypeSets', header: 'Types' },
  { key: 'prioritySetId', optionsKey: 'prioritySets', header: 'Priorities' },
  { key: 'fieldSetId', optionsKey: 'fieldSets', header: 'Fields' },
]

function bindingsOf(p: ProjectBinding) {
  return {
    workflowId: p.workflowId,
    prioritySetId: p.prioritySetId,
    fieldSetId: p.fieldSetId,
    issueTypeSetId: p.issueTypeSetId,
  }
}

/**
 * Workspace assignment matrix: every project in the workspace × its taxonomy
 * bindings, editable in place. Options are the sets visible to the workspace
 * (global ∪ workspace-scoped); a project's own project-private set, if bound,
 * still shows as its current value. Empty select = the system default.
 */
export default function WorkspaceProjectsMatrix() {
  const { wsId } = useParams<{ wsId: string }>()
  const api = useMemo(() => workspaceAdminApi(wsId!), [wsId])
  const qc = useQueryClient()

  const { data: projects = [] } = useQuery({
    queryKey: ['ws-admin', wsId, 'projects'],
    queryFn: api.matrix,
    enabled: !!wsId,
  })
  const { data: options } = useQuery({
    queryKey: ['ws-admin', wsId, 'binding-options'],
    queryFn: api.bindingOptions,
    enabled: !!wsId,
  })
  const [error, setError] = useState('')

  const update = useMutation({
    mutationFn: ({ p, dim, value }: { p: ProjectBinding; dim: Dim; value: string }) =>
      api.updateProjectBindings(p.projectId, { ...bindingsOf(p), [dim]: value || null }),
    onSuccess: (_res, { p }) => {
      setError('')
      qc.invalidateQueries({ queryKey: ['ws-admin', wsId, 'projects'] })
      qc.invalidateQueries({ queryKey: ['projectConfig', wsId, p.projectId] })
    },
    onError: e => setError(e instanceof Error ? e.message : 'Update failed'),
  })

  return (
    <>
      <PageHeader
        title="Projects"
        subtitle="Assign the taxonomy each project in this workspace uses. Sets shown are the workspace's own and the global defaults; a project can also define its own under its Settings."
      />
      {error && <p className="text-sm mb-3" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
      <AdminTable headers={['Project', ...DIMENSIONS.map(d => d.header)]}>
        {projects.map(p => (
          <tr key={p.projectId} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2 text-sm">
                <span className="mono text-xs px-1.5 py-0.5 rounded"
                      style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-secondary)' }}>
                  {p.key}
                </span>
                {p.name}
                {p.archived && <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(archived)</span>}
              </span>
            </td>
            {DIMENSIONS.map(d => (
              <td key={d.key} className="px-3 py-2.5">
                <Select compact style={{ maxWidth: 200 }} value={p[d.key] ?? ''} disabled={!options}
                        onChange={e => update.mutate({ p, dim: d.key, value: e.target.value })}>
                  <option value="">System default</option>
                  {(options?.[d.optionsKey] ?? []).map((o: SetOption) => (
                    <option key={o.id} value={o.id}>
                      {o.name}{o.scope !== 'GLOBAL' ? ` (${o.scope.toLowerCase()})` : ''}
                    </option>
                  ))}
                </Select>
              </td>
            ))}
          </tr>
        ))}
      </AdminTable>
      {projects.length === 0 && (
        <p className="text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>No projects in this workspace yet.</p>
      )}
    </>
  )
}
