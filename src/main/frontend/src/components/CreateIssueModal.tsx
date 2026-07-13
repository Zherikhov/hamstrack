import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { apiCreateIssue, apiListIssueTypes, apiListProjects, apiListStatuses, apiListWorkspaces } from '../api'
import { Button, Input, Select, Textarea } from './ui'

const PRIORITIES = ['URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE']

interface Props {
  /** Absent when opened outside a workspace (/workspaces) — a Workspace select appears instead. */
  wsId?: string
  /** Pre-selected project — the one currently open, if any. */
  defaultProjectId?: string
  onClose: () => void
}

export default function CreateIssueModal({ wsId, defaultProjectId, onClose }: Props) {
  const qc = useQueryClient()

  const [wsSelection, setWsSelection] = useState('')

  const { data: workspaces = [] } = useQuery({
    queryKey: ['workspaces'],
    queryFn: apiListWorkspaces,
    enabled: !wsId,
  })

  // '' while the lists load; the effective value falls back to the first option
  const effectiveWsId = wsId ?? (wsSelection || workspaces[0]?.id || '')

  const { data: projects = [], isSuccess: projectsLoaded } = useQuery({
    queryKey: ['projects', effectiveWsId],
    queryFn: () => apiListProjects(effectiveWsId),
    enabled: !!effectiveWsId,
  })

  const { data: issueTypes = [] } = useQuery({
    queryKey: ['issueTypes', effectiveWsId],
    queryFn: () => apiListIssueTypes(effectiveWsId),
    enabled: !!effectiveWsId,
  })

  const { data: statuses = [] } = useQuery({
    queryKey: ['statuses', effectiveWsId],
    queryFn: () => apiListStatuses(effectiveWsId),
    enabled: !!effectiveWsId,
  })

  const active = projects.filter(p => !p.archived)

  const [projectId, setProjectId] = useState(defaultProjectId ?? '')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [typeId, setTypeId] = useState('')
  const [statusId, setStatusId] = useState('')
  const [priority, setPriority] = useState('MEDIUM')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  const effectiveProjectId = projectId || active[0]?.id || ''
  const effectiveTypeId = typeId || issueTypes[0]?.id || ''
  const effectiveStatusId = statusId || statuses[0]?.id || ''

  function handleWorkspaceChange(id: string) {
    setWsSelection(id)
    // Workspace-scoped selections don't carry over
    setProjectId('')
    setTypeId('')
    setStatusId('')
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setSaving(true)
    try {
      await apiCreateIssue(effectiveWsId, effectiveProjectId, {
        title: title.trim(),
        typeId: effectiveTypeId,
        statusId: effectiveStatusId,
        priority,
        description: description.trim() || undefined,
      })
      await qc.invalidateQueries({ queryKey: ['issues', effectiveWsId, effectiveProjectId] })
      onClose()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to create issue')
    } finally {
      setSaving(false)
    }
  }

  const overlayStyle: React.CSSProperties = {
    position: 'fixed', inset: 0, zIndex: 50,
    background: 'rgba(28,27,25,0.55)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    backdropFilter: 'blur(2px)',
  }

  const panelStyle: React.CSSProperties = {
    background: 'white',
    borderRadius: 'var(--radius-lg)',
    border: '1px solid var(--color-border)',
    width: 480,
    boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={panelStyle} onClick={e => e.stopPropagation()}>
        <div
          className="flex items-center justify-between px-5 py-4 border-b"
          style={{ borderColor: 'var(--color-border)' }}
        >
          <span className="font-semibold text-sm">New Issue</span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 transition-opacity">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <form onSubmit={submit} className="p-5 flex flex-col gap-4">
          {!wsId && (
            <Select label="Workspace" value={effectiveWsId} onChange={e => handleWorkspaceChange(e.target.value)}>
              {workspaces.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
            </Select>
          )}

          <div>
            <Select label="Project" value={effectiveProjectId} onChange={e => setProjectId(e.target.value)}>
              {active.map(p => <option key={p.id} value={p.id}>{p.key} — {p.name}</option>)}
            </Select>
            {projectsLoaded && active.length === 0 && (
              <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                No projects in this workspace yet.
              </p>
            )}
          </div>

          <Input
            label="Title"
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="Issue title"
            autoFocus
            required
          />

          <div className="grid grid-cols-3 gap-3">
            <Select label="Type" value={effectiveTypeId} onChange={e => setTypeId(e.target.value)}>
              {issueTypes.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </Select>
            <Select label="Status" value={effectiveStatusId} onChange={e => setStatusId(e.target.value)}>
              {statuses.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </Select>
            <Select label="Priority" value={priority} onChange={e => setPriority(e.target.value)}>
              {PRIORITIES.map(p => <option key={p} value={p}>{p}</option>)}
            </Select>
          </div>

          <Textarea
            label="Description"
            value={description}
            onChange={e => setDescription(e.target.value)}
            placeholder="Add a description…"
            rows={4}
          />

          {error && (
            <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>
          )}

          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
            <Button
              variant="primary"
              type="submit"
              loading={saving}
              disabled={!title.trim() || !effectiveProjectId || !effectiveTypeId || !effectiveStatusId}
            >
              Create issue
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
