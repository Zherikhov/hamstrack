import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Check, ChevronDown, LayoutGrid, Plus } from 'lucide-react'
import { apiListProjects } from '../api'
import CreateProjectModal from './CreateProjectModal'

interface Props {
  wsId: string
  projectId?: string
}

/**
 * Top-bar project switcher: shows the current project (or "Projects"),
 * opens a dropdown with the workspace project list.
 */
export default function ProjectSwitcher({ wsId, projectId }: Props) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  const { data: projects = [] } = useQuery({
    queryKey: ['projects', wsId],
    queryFn: () => apiListProjects(wsId),
    enabled: !!wsId,
  })

  const active = projects.filter(p => !p.archived)
  const current = active.find(p => p.id === projectId)

  useEffect(() => {
    function handle(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    if (open) document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [open])

  function go(id: string) {
    setOpen(false)
    navigate(`/w/${wsId}/p/${id}`)
  }

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
            Projects
          </div>

          <div style={{ maxHeight: 280, overflowY: 'auto' }}>
            {active.length === 0 && (
              <div className="px-3 py-3 text-xs italic" style={{ color: 'var(--color-text-muted)' }}>
                No projects yet
              </div>
            )}
            {active.map(p => (
              <button
                key={p.id}
                onClick={() => go(p.id)}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm text-left cursor-pointer transition-colors"
                style={{ background: 'transparent' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-surface)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
              >
                <span
                  className="flex items-center justify-center rounded font-display font-bold text-white flex-shrink-0"
                  style={{ width: 24, height: 24, fontSize: 10, background: 'var(--color-brand)' }}
                >
                  {p.key.slice(0, 2)}
                </span>
                <span className="flex-1 truncate">{p.name}</span>
                <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>{p.key}</span>
                {p.id === projectId && <Check size={13} style={{ color: 'var(--color-brand)', flexShrink: 0 }} />}
              </button>
            ))}
          </div>

          <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
            <button
              onClick={() => { setOpen(false); navigate(`/w/${wsId}`) }}
              className="w-full flex items-center gap-2 px-3 py-2 text-xs cursor-pointer transition-colors"
              style={{ color: 'var(--color-text-secondary)' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-surface)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
            >
              <LayoutGrid size={13} />
              View all projects
            </button>
            <button
              onClick={() => { setOpen(false); setShowCreate(true) }}
              className="w-full flex items-center gap-2 px-3 py-2 text-xs cursor-pointer transition-colors"
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
