import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Check, ChevronDown, Globe, LayoutGrid, Plus } from 'lucide-react'
import { apiListProjects, apiListWorkspaces } from '../api'
import { useAuthStore } from '../auth'
import { getRecentProjects, recordProjectVisit, type RecentProject } from '../recentProjects'
import CreateProjectModal from './CreateProjectModal'

interface Props {
  wsId: string
  projectId?: string
}

/**
 * Top-bar project switcher: shows the current project (or "Projects"), opens a
 * dropdown with the user's 5 most recently visited projects grouped by
 * workspace (padded with the current workspace's projects while history is
 * short), plus View all projects / View all workspaces / New project.
 */
export default function ProjectSwitcher({ wsId, projectId }: Props) {
  const navigate = useNavigate()
  const { user } = useAuthStore()
  const [open, setOpen] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  const { data: projects = [] } = useQuery({
    queryKey: ['projects', wsId],
    queryFn: () => apiListProjects(wsId),
    enabled: !!wsId,
  })

  const { data: workspaces = [], isSuccess: workspacesLoaded } = useQuery({
    queryKey: ['workspaces'],
    queryFn: apiListWorkspaces,
  })

  const active = projects.filter(p => !p.archived)
  const current = active.find(p => p.id === projectId)

  // Journal the visit — feeds this dropdown and the "/" redirect
  useEffect(() => {
    if (user && current) {
      recordProjectVisit(user.id, { wsId, projectId: current.id, key: current.key, name: current.name })
    }
  }, [user, current, wsId])

  // Recents first; while history is shorter than 5, pad with the current
  // workspace's projects. Drop entries from workspaces the user has left.
  const recents = user ? getRecentProjects(user.id) : []
  const known = workspacesLoaded
    ? recents.filter(r => workspaces.some(w => w.id === r.wsId))
    : recents
  const padding: RecentProject[] = active
    .filter(p => !known.some(r => r.projectId === p.id))
    .slice(0, Math.max(0, 5 - known.length))
    .map(p => ({ wsId, projectId: p.id, key: p.key, name: p.name, visitedAt: 0 }))
  const entries = [...known, ...padding]

  // Group by workspace, current workspace first, then by most recent visit
  const groups: { wsId: string; wsName: string; items: RecentProject[] }[] = []
  for (const e of entries) {
    let g = groups.find(x => x.wsId === e.wsId)
    if (!g) {
      const wsName = workspaces.find(w => w.id === e.wsId)?.name ?? ''
      g = { wsId: e.wsId, wsName, items: [] }
      groups.push(g)
    }
    g.items.push(e)
  }
  groups.sort((a, b) => (a.wsId === wsId ? -1 : b.wsId === wsId ? 1 : 0))

  useEffect(() => {
    function handle(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    if (open) document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [open])

  function go(e: RecentProject) {
    setOpen(false)
    navigate(`/w/${e.wsId}/p/${e.projectId}`)
  }

  const footerBtnClass = 'w-full flex items-center gap-2 px-3 py-2 text-xs cursor-pointer transition-colors'

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button
        onClick={() => setOpen(v => !v)}
        className="flex items-center gap-2 px-2.5 py-1.5 text-sm cursor-pointer transition-colors rounded"
        style={{
          background: open ? 'rgba(255,255,255,0.12)' : 'rgba(255,255,255,0.07)',
          border: '1px solid rgba(255,255,255,0.10)',
          color: 'white',
        }}
      >
        {current ? (
          <>
            <span className="mono text-xs" style={{ color: 'rgba(255,255,255,0.55)' }}>{current.key}</span>
            <span className="max-w-44 truncate" style={{ fontSize: 13.5 }}>{current.name}</span>
          </>
        ) : (
          <span style={{ fontSize: 13.5 }}>Projects</span>
        )}
        <ChevronDown size={13} style={{ color: 'rgba(255,255,255,0.4)' }} />
      </button>

      {open && (
        <div
          style={{
            position: 'absolute',
            top: 'calc(100% + 6px)',
            left: 0,
            width: 280,
            background: 'white',
            border: '1px solid var(--color-border-2)',
            borderRadius: 'var(--radius-md)',
            boxShadow: '0 8px 24px rgba(28,27,25,0.18)',
            zIndex: 40,
            overflow: 'hidden',
            color: 'var(--color-text)',
          }}
        >
          <div
            className="px-3 py-2 text-xs font-medium tracking-wider uppercase border-b"
            style={{ color: 'var(--color-text-muted)', borderColor: 'var(--color-border)' }}
          >
            Recent projects
          </div>

          <div style={{ maxHeight: 300, overflowY: 'auto' }}>
            {entries.length === 0 && (
              <div className="px-3 py-3 text-xs italic" style={{ color: 'var(--color-text-muted)' }}>
                No projects yet
              </div>
            )}
            {groups.map(g => (
              <div key={g.wsId}>
                {g.wsName && (
                  <div
                    className="px-3 pt-2 pb-1 text-xs truncate"
                    style={{ color: 'var(--color-text-muted)' }}
                  >
                    {g.wsName}
                  </div>
                )}
                {g.items.map(e => (
                  <button
                    key={e.projectId}
                    onClick={() => go(e)}
                    className="w-full flex items-center gap-2 px-3 py-2 text-sm text-left cursor-pointer transition-colors"
                    style={{ background: 'transparent' }}
                    onMouseEnter={ev => (ev.currentTarget.style.background = 'var(--color-surface)')}
                    onMouseLeave={ev => (ev.currentTarget.style.background = 'transparent')}
                  >
                    <span
                      className="flex items-center justify-center rounded font-display font-bold text-white flex-shrink-0"
                      style={{ width: 24, height: 24, fontSize: 10, background: 'var(--color-brand)' }}
                    >
                      {e.key.slice(0, 2)}
                    </span>
                    <span className="flex-1 truncate">{e.name}</span>
                    <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>{e.key}</span>
                    {e.projectId === projectId && <Check size={13} style={{ color: 'var(--color-brand)', flexShrink: 0 }} />}
                  </button>
                ))}
              </div>
            ))}
          </div>

          <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
            <button
              // showAll suppresses WorkspaceHome's single-project auto-redirect:
              // an explicit "View all projects" click must always land on the list
              onClick={() => { setOpen(false); navigate(`/w/${wsId}`, { state: { showAll: true } }) }}
              className={footerBtnClass}
              style={{ color: 'var(--color-text-secondary)' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-surface)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
            >
              <LayoutGrid size={13} />
              View all projects
            </button>
            <button
              onClick={() => { setOpen(false); navigate('/workspaces') }}
              className={footerBtnClass}
              style={{ color: 'var(--color-text-secondary)' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-surface)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
            >
              <Globe size={13} />
              View all workspaces
            </button>
            <button
              onClick={() => { setOpen(false); setShowCreate(true) }}
              className={footerBtnClass}
              style={{ color: 'var(--color-brand)' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-surface)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
            >
              <Plus size={13} />
              New project
            </button>
          </div>
        </div>
      )}

      {showCreate && <CreateProjectModal wsId={wsId} onClose={() => setShowCreate(false)} />}
    </div>
  )
}
