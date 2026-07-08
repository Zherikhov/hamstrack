import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, LogOut, LayoutGrid } from 'lucide-react'
import { apiListWorkspaces, apiCreateWorkspace, apiLogout } from '../api'
import { useAuthStore } from '../auth'
import { Button, Input, Avatar } from '../components/ui'
import type { Workspace } from '../types'

export default function WorkspacesPage() {
  const navigate = useNavigate()
  const { user, clear } = useAuthStore()
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [newName, setNewName] = useState('')
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState('')

  const { data: workspaces = [], isLoading } = useQuery({
    queryKey: ['workspaces'],
    queryFn: apiListWorkspaces,
  })

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setCreating(true)
    try {
      const ws = await apiCreateWorkspace(newName.trim())
      await qc.invalidateQueries({ queryKey: ['workspaces'] })
      navigate(`/w/${ws.id}`)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to create workspace')
    } finally {
      setCreating(false)
    }
  }

  async function handleLogout() {
    try { await apiLogout() } catch { /* ignore */ }
    clear()
    navigate('/login')
  }

  const roleLabel = (role: Workspace['myRole']) => {
    if (role === 'OWNER') return 'Owner'
    if (role === 'ADMIN') return 'Admin'
    return 'Member'
  }

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--color-surface)' }}>
      {/* Topbar */}
      <header
        className="flex items-center justify-between px-6 py-3 border-b"
        style={{ background: 'white', borderColor: 'var(--color-border)' }}
      >
        <span className="font-display font-bold text-lg" style={{ color: 'var(--color-text)' }}>
          Hamstrack
        </span>
        <div className="flex items-center gap-3">
          {user && (
            <div className="flex items-center gap-2">
              <Avatar name={user.displayName} avatarUrl={user.avatarUrl} size={28} />
              <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>{user.displayName}</span>
            </div>
          )}
          <Button variant="ghost" size="sm" onClick={handleLogout}>
            <LogOut size={14} />
            Sign out
          </Button>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto p-8">
        <div style={{ maxWidth: 720, margin: '0 auto' }}>
          {/* Header */}
          <div className="flex items-center justify-between mb-6">
            <div>
              <h1 className="font-display font-bold text-2xl" style={{ color: 'var(--color-text)' }}>
                Workspaces
              </h1>
              <p className="text-sm mt-0.5" style={{ color: 'var(--color-text-muted)' }}>
                Select a workspace to continue
              </p>
            </div>
            <Button variant="primary" onClick={() => setShowCreate(!showCreate)}>
              <Plus size={15} />
              New workspace
            </Button>
          </div>

          {/* Create form */}
          {showCreate && (
            <form
              onSubmit={handleCreate}
              className="rounded-lg border p-4 mb-4 flex gap-3 items-end"
              style={{ background: 'white', borderColor: 'var(--color-border)' }}
            >
              <div className="flex-1">
                <Input
                  label="Workspace name"
                  value={newName}
                  onChange={e => setNewName(e.target.value)}
                  placeholder="e.g. Acme Corp"
                  autoFocus
                  required
                  error={error || undefined}
                />
              </div>
              <Button variant="primary" type="submit" loading={creating} disabled={!newName.trim()}>
                Create
              </Button>
              <Button variant="ghost" type="button" onClick={() => { setShowCreate(false); setError('') }}>
                Cancel
              </Button>
            </form>
          )}

          {/* Workspace list */}
          {isLoading ? (
            <p className="mono text-sm py-8 text-center" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
          ) : workspaces.length === 0 ? (
            <div
              className="rounded-xl border border-dashed p-12 text-center"
              style={{ borderColor: 'var(--color-border-2)' }}
            >
              <LayoutGrid size={32} className="mx-auto mb-3 opacity-30" style={{ color: 'var(--color-text-muted)' }} />
              <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                No workspaces yet. Create your first one above.
              </p>
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              {workspaces.map(ws => (
                <button
                  key={ws.id}
                  onClick={() => navigate(`/w/${ws.id}`)}
                  className="w-full text-left rounded-lg border px-4 py-3.5 flex items-center gap-3 transition-colors cursor-pointer"
                  style={{
                    background: 'white',
                    borderColor: 'var(--color-border)',
                  }}
                  onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--color-border-2)')}
                  onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--color-border)')}
                >
                  <span
                    className="flex items-center justify-center rounded font-display font-bold text-sm text-white flex-shrink-0"
                    style={{ width: 36, height: 36, background: 'var(--color-brand)' }}
                  >
                    {ws.name[0]?.toUpperCase()}
                  </span>
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-sm truncate" style={{ color: 'var(--color-text)' }}>
                      {ws.name}
                    </div>
                    <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-muted)' }}>
                      {roleLabel(ws.myRole)} · {ws.slug}
                    </div>
                  </div>
                  <span className="text-xs font-medium px-2 py-0.5 rounded" style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-muted)' }}>
                    {roleLabel(ws.myRole)}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
