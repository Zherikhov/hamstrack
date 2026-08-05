import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { projectAdminApi } from '../../api'
import type { BindingOptions, ProjectBinding, SetOption } from '../../types'
import { Button, Select } from '../../components/ui'

type Dim = 'workflowId' | 'prioritySetId' | 'fieldSetId' | 'issueTypeSetId'

const DIMENSIONS: { key: Dim; optionsKey: keyof BindingOptions; label: string; hint: string }[] = [
  { key: 'workflowId', optionsKey: 'workflows', label: 'Workflow',
    hint: 'Statuses and their board order, plus allowed transitions.' },
  { key: 'issueTypeSetId', optionsKey: 'issueTypeSets', label: 'Issue types',
    hint: 'Which issue types can be created in this project.' },
  { key: 'prioritySetId', optionsKey: 'prioritySets', label: 'Priorities',
    hint: 'Which priorities are offered, and the default for new issues.' },
  { key: 'fieldSetId', optionsKey: 'fieldSets', label: 'Custom fields',
    hint: 'Which custom fields appear on issues in this project.' },
]

/**
 * Project settings — taxonomy bindings. A project MANAGER assigns the sets that
 * drive the board, forms and fields. Options are the sets visible to the
 * project: global, its workspace's, and its own project-private ones. "System
 * default" (null binding) uses the instance-wide default set.
 */
export default function ProjectBindingsPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const api = useMemo(() => projectAdminApi(wsId!, projectId!), [wsId, projectId])
  const qc = useQueryClient()

  const { data: binding } = useQuery({
    queryKey: ['project-bindings', wsId, projectId],
    queryFn: api.bindings,
    enabled: !!wsId && !!projectId,
  })
  const { data: options } = useQuery({
    queryKey: ['project-binding-options', wsId, projectId],
    queryFn: api.bindingOptions,
    enabled: !!wsId && !!projectId,
  })

  // Local draft, seeded from the loaded binding (null → '' = system default)
  const [draft, setDraft] = useState<Record<Dim, string> | null>(null)
  const current: Record<Dim, string> = binding
    ? {
        workflowId: binding.workflowId ?? '',
        prioritySetId: binding.prioritySetId ?? '',
        fieldSetId: binding.fieldSetId ?? '',
        issueTypeSetId: binding.issueTypeSetId ?? '',
      }
    : { workflowId: '', prioritySetId: '', fieldSetId: '', issueTypeSetId: '' }
  const value = draft ?? current

  const dirty = draft !== null && (Object.keys(current) as Dim[]).some(k => draft[k] !== current[k])

  const save = useMutation({
    mutationFn: () =>
      api.updateBindings({
        workflowId: value.workflowId || null,
        prioritySetId: value.prioritySetId || null,
        fieldSetId: value.fieldSetId || null,
        issueTypeSetId: value.issueTypeSetId || null,
      }),
    onSuccess: (updated: ProjectBinding) => {
      qc.setQueryData(['project-bindings', wsId, projectId], updated)
      // The board/forms read the effective taxonomy — refresh it everywhere
      qc.invalidateQueries({ queryKey: ['projectConfig', wsId, projectId] })
      setDraft(null)
      setError('')
    },
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  const [error, setError] = useState('')

  function set(dim: Dim, v: string) {
    setDraft({ ...value, [dim]: v })
  }

  return (
    <div>
      <div className="mb-5">
        <h2 className="font-display font-bold" style={{ fontSize: 20, letterSpacing: '-0.3px' }}>
          Taxonomy
        </h2>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 560 }}>
          Assign the workflow, issue types, priorities and fields this project uses — pick sets shared
          across the workspace, or ones created just for this project. “System default” uses the
          instance-wide default.
        </p>
      </div>

      <div className="rounded-lg border" style={{ background: 'white', borderColor: 'var(--color-border)' }}>
        {DIMENSIONS.map((d, i) => (
          <div key={d.key}
               className="flex items-start justify-between gap-6 px-4 py-4"
               style={{ borderTop: i === 0 ? 'none' : '1px solid var(--color-border)' }}>
            <div style={{ maxWidth: 340 }}>
              <div className="text-sm font-semibold">{d.label}</div>
              <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-muted)' }}>{d.hint}</div>
            </div>
            <div style={{ width: 280, flexShrink: 0 }}>
              <Select value={value[d.key]} onChange={e => set(d.key, e.target.value)}
                      disabled={!options}>
                <option value="">System default</option>
                {(options?.[d.optionsKey] ?? []).map((o: SetOption) => (
                  <option key={o.id} value={o.id}>
                    {o.name}{o.scope !== 'GLOBAL' ? ` (${o.scope.toLowerCase()})` : ''}
                  </option>
                ))}
              </Select>
            </div>
          </div>
        ))}
      </div>

      {error && <p className="text-sm mt-3" style={{ color: 'var(--color-error)' }}>{error}</p>}

      <div className="flex items-center gap-3 mt-4">
        <Button variant="primary" disabled={!dirty} loading={save.isPending} onClick={() => save.mutate()}>
          Save changes
        </Button>
        {dirty && (
          <Button variant="ghost" onClick={() => { setDraft(null); setError('') }}>
            Reset
          </Button>
        )}
        {save.isSuccess && !dirty && (
          <span className="text-xs" style={{ color: 'var(--color-brand)' }}>Saved</span>
        )}
      </div>
    </div>
  )
}
