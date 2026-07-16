import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, X } from 'lucide-react'
import { adminStatuses, adminWorkflows } from '../../api'
import type { AdminWorkflow, TransitionRule } from '../../types'
import { Button, Input, Select } from '../../components/ui'
import { AdminTable, ImpactBanner, Modal, PageHeader } from './common'

export default function AdminWorkflowsPage() {
  const qc = useQueryClient()
  const { data: workflows = [] } = useQuery({ queryKey: ['admin', 'workflows'], queryFn: adminWorkflows.list })
  const [editing, setEditing] = useState<AdminWorkflow | 'new' | null>(null)

  const invalidate = () => qc.invalidateQueries({ queryKey: ['admin'] })

  const del = useMutation({
    mutationFn: (id: string) => adminWorkflows.remove(id),
    onSuccess: invalidate,
    onError: e => window.alert(e instanceof Error ? e.message : 'Delete failed'),
  })

  return (
    <>
      <PageHeader
        title="Workflows"
        subtitle="A workflow = which statuses a project uses (board column order) + allowed transitions between them. No transition rules for a status = any move allowed from it."
        action={<Button variant="primary" onClick={() => setEditing('new')}>+ New workflow</Button>}
      />
      <AdminTable headers={['Name', 'Statuses', 'Used in', '']}>
        {workflows.map(wf => (
          <tr key={wf.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="text-sm font-medium">{wf.name}</span>
              {wf.systemDefault && <span className="mono text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>system</span>}
            </td>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-1 flex-wrap">
                {wf.statuses.map((s, i) => (
                  <span key={s.id} className="inline-flex items-center gap-1">
                    {i > 0 && <ArrowRight size={11} style={{ color: 'var(--color-border-2)' }} />}
                    <span className="text-xs rounded-full border px-2 py-0.5"
                          style={{ borderColor: 'var(--color-border-2)', background: 'white' }}>
                      {s.name}
                    </span>
                  </span>
                ))}
              </span>
            </td>
            <td className="px-3 py-2.5">
              <span className="text-xs px-2.5 py-0.5 rounded-full whitespace-nowrap"
                    style={{ color: 'var(--color-brand)', background: '#E7F0EE' }}>
                {wf.projectsUsing} project{wf.projectsUsing !== 1 ? 's' : ''}
              </span>
            </td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={() => setEditing(wf)}>Edit</Button>
              {!wf.systemDefault && (
                <Button variant="ghost" size="sm" style={{ color: 'var(--color-error)' }}
                        onClick={() => { if (window.confirm(`Delete workflow “${wf.name}”?`)) del.mutate(wf.id) }}>
                  Delete
                </Button>
              )}
            </td>
          </tr>
        ))}
      </AdminTable>

      {editing && (
        <WorkflowForm workflow={editing === 'new' ? null : editing}
                      onClose={() => setEditing(null)}
                      onSaved={() => { setEditing(null); invalidate() }} />
      )}
    </>
  )
}

function WorkflowForm({ workflow, onClose, onSaved }: {
  workflow: AdminWorkflow | null; onClose: () => void; onSaved: () => void
}) {
  const { data: catalog = [] } = useQuery({ queryKey: ['admin', 'statuses'], queryFn: adminStatuses.list })
  const [name, setName] = useState(workflow?.name ?? '')
  const [description, setDescription] = useState(workflow?.description ?? '')
  // Ordered list of status ids = board column order
  const [statusIds, setStatusIds] = useState<string[]>(workflow?.statuses.map(s => s.id) ?? [])
  const [transitions, setTransitions] = useState<TransitionRule[]>(workflow?.transitions ?? [])
  const [newFrom, setNewFrom] = useState<string>('ANY')
  const [newTo, setNewTo] = useState<string>('')
  const [error, setError] = useState('')

  const available = catalog.filter(s => !s.archived && !statusIds.includes(s.id))
  const inWorkflow = statusIds
    .map(id => catalog.find(s => s.id === id))
    .filter((s): s is NonNullable<typeof s> => !!s)

  function statusName(id: string | null) {
    if (id === null) return 'Any status'
    return catalog.find(s => s.id === id)?.name ?? '?'
  }

  function move(idx: number, dir: -1 | 1) {
    setStatusIds(prev => {
      const next = [...prev]
      const target = idx + dir
      if (target < 0 || target >= next.length) return prev
      ;[next[idx], next[target]] = [next[target], next[idx]]
      return next
    })
  }

  function removeStatus(id: string) {
    setStatusIds(prev => prev.filter(x => x !== id))
    setTransitions(prev => prev.filter(t => t.fromStatusId !== id && t.toStatusId !== id))
  }

  function addTransition() {
    if (!newTo) return
    const rule: TransitionRule = { fromStatusId: newFrom === 'ANY' ? null : newFrom, toStatusId: newTo }
    const exists = transitions.some(t => t.fromStatusId === rule.fromStatusId && t.toStatusId === rule.toStatusId)
    if (!exists) setTransitions(prev => [...prev, rule])
  }

  const save = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), description: description.trim() || undefined, statusIds, transitions }
      return workflow ? adminWorkflows.update(workflow.id, payload) : adminWorkflows.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  return (
    <Modal title={workflow ? `Edit workflow “${workflow.name}”` : 'New workflow'} onClose={onClose} width={560}>
      <div className="flex flex-col gap-4">
        <ImpactBanner projectsUsing={workflow?.projectsUsing ?? 0} entity="workflow" />
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        <Input label="Description" value={description} onChange={e => setDescription(e.target.value)} />

        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
            Statuses (top to bottom = board columns left to right)
          </span>
          {inWorkflow.map((s, idx) => (
            <div key={s.id} className="flex items-center gap-2 rounded border px-2.5 py-1.5"
                 style={{ borderColor: 'var(--color-border)', background: 'var(--color-surface)' }}>
              <span className="rounded-full" style={{ width: 8, height: 8, background: s.color }} />
              <span className="text-sm flex-1">{s.name}</span>
              <button type="button" className="cursor-pointer text-xs px-1" onClick={() => move(idx, -1)} disabled={idx === 0}>↑</button>
              <button type="button" className="cursor-pointer text-xs px-1" onClick={() => move(idx, 1)} disabled={idx === inWorkflow.length - 1}>↓</button>
              <button type="button" className="cursor-pointer px-1" onClick={() => removeStatus(s.id)}>
                <X size={12} style={{ color: 'var(--color-text-muted)' }} />
              </button>
            </div>
          ))}
          {available.length > 0 && (
            <Select value="" onChange={e => { if (e.target.value) setStatusIds(prev => [...prev, e.target.value]) }}>
              <option value="">+ Add status from catalog…</option>
              {available.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </Select>
          )}
        </div>

        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
            Transition rules (a status without rules is open — any move allowed)
          </span>
          {transitions.map((t, idx) => (
            <div key={idx} className="flex items-center gap-2 text-sm">
              <span className="text-xs rounded-full border px-2 py-0.5" style={{ borderColor: 'var(--color-border-2)' }}>
                {statusName(t.fromStatusId)}
              </span>
              <ArrowRight size={11} style={{ color: 'var(--color-border-2)' }} />
              <span className="text-xs rounded-full border px-2 py-0.5" style={{ borderColor: 'var(--color-border-2)' }}>
                {statusName(t.toStatusId)}
              </span>
              <button type="button" className="cursor-pointer ml-auto"
                      onClick={() => setTransitions(prev => prev.filter((_, i) => i !== idx))}>
                <X size={12} style={{ color: 'var(--color-text-muted)' }} />
              </button>
            </div>
          ))}
          <div className="flex items-center gap-2">
            <Select value={newFrom} onChange={e => setNewFrom(e.target.value)}>
              <option value="ANY">Any status</option>
              {inWorkflow.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </Select>
            <ArrowRight size={13} style={{ color: 'var(--color-border-2)', flexShrink: 0 }} />
            <Select value={newTo} onChange={e => setNewTo(e.target.value)}>
              <option value="">Target…</option>
              {inWorkflow.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </Select>
            <Button variant="secondary" size="sm" type="button" onClick={addTransition} disabled={!newTo}>Add</Button>
          </div>
        </div>

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim() || statusIds.length === 0}
                  loading={save.isPending} onClick={() => save.mutate()}>
            {workflow ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
