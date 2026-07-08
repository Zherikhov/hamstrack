import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { X } from 'lucide-react'
import { apiCreateProject } from '../api'
import { Button, Input } from './ui'

interface Props {
  wsId: string
  onClose: () => void
}

export default function CreateProjectModal({ wsId, onClose }: Props) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  function deriveKey(n: string) {
    return n.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6)
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setSaving(true)
    try {
      const project = await apiCreateProject(wsId, name.trim(), key.trim())
      await qc.invalidateQueries({ queryKey: ['projects', wsId] })
      onClose()
      navigate(`/w/${wsId}/p/${project.id}`)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to create project')
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
    width: 420,
    boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={panelStyle} onClick={e => e.stopPropagation()}>
        <div
          className="flex items-center justify-between px-5 py-4 border-b"
          style={{ borderColor: 'var(--color-border)' }}
        >
          <span className="font-semibold text-sm">New Project</span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 transition-opacity">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <form onSubmit={submit} className="p-5 flex flex-col gap-4">
          <Input
            label="Project name"
            value={name}
            onChange={e => {
              setName(e.target.value)
              if (!key || key === deriveKey(name)) setKey(deriveKey(e.target.value))
            }}
            placeholder="e.g. Backend API"
            autoFocus
            required
          />
          <Input
            label="Key"
            value={key}
            onChange={e => setKey(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 10))}
            placeholder="e.g. BAPI"
            required
          />
          {error && (
            <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
            <Button variant="primary" type="submit" loading={saving} disabled={!name || !key}>
              Create project
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
