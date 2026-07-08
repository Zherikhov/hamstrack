import { useEffect } from 'react'
import { useParams, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { FolderOpen, Plus } from 'lucide-react'
import { apiListProjects } from '../api'
import { Button } from '../components/ui'
import { useState } from 'react'
import CreateProjectModal from '../components/CreateProjectModal'

export default function WorkspaceHomePage() {
  const { wsId } = useParams<{ wsId: string }>()
  const navigate = useNavigate()
  const [showCreate, setShowCreate] = useState(false)

  const { data: projects = [], isLoading } = useQuery({
    queryKey: ['projects', wsId],
    queryFn: () => apiListProjects(wsId!),
    enabled: !!wsId,
  })

  const active = projects.filter(p => !p.archived)

  // Auto-redirect if exactly one project
  useEffect(() => {
    if (active.length === 1) {
      navigate(`/w/${wsId}/p/${active[0].id}`, { replace: true })
    }
  }, [active.length]) // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
      </div>
    )
  }

  return (
    <div className="flex-1 flex flex-col overflow-y-auto p-8" style={{ background: 'var(--color-surface)' }}>
      <div style={{ maxWidth: 640, margin: '0 auto', width: '100%' }}>
        <div className="flex items-center justify-between mb-6">
          <h1 className="font-display font-bold text-xl" style={{ color: 'var(--color-text)' }}>
            Projects
          </h1>
          <Button variant="primary" size="sm" onClick={() => setShowCreate(true)}>
            <Plus size={14} />
            New project
          </Button>
        </div>

        {active.length === 0 ? (
          <div
            className="rounded-xl border border-dashed flex flex-col items-center justify-center py-16 gap-4"
            style={{ borderColor: 'var(--color-border-2)' }}
          >
            <FolderOpen size={36} style={{ color: 'var(--color-border-2)' }} />
            <div className="text-center">
              <p className="text-sm font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                No projects yet
              </p>
              <p className="text-sm mt-1" style={{ color: 'var(--color-text-muted)' }}>
                Create a project to start tracking issues
              </p>
            </div>
            <Button variant="primary" onClick={() => setShowCreate(true)}>
              <Plus size={14} />
              Create first project
            </Button>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {active.map(project => (
              <button
                key={project.id}
                onClick={() => navigate(`/w/${wsId}/p/${project.id}`)}
                className="w-full text-left rounded-lg border px-4 py-3.5 flex items-center gap-3 transition-colors cursor-pointer"
                style={{ background: 'white', borderColor: 'var(--color-border)' }}
                onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--color-border-2)')}
                onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--color-border)')}
              >
                <span
                  className="flex items-center justify-center rounded font-display font-bold text-xs text-white flex-shrink-0"
                  style={{ width: 32, height: 32, background: 'var(--color-brand)' }}
                >
                  {project.key.slice(0, 2)}
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>
                    {project.name}
                  </div>
                  {project.description && (
                    <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-muted)' }}>
                      {project.description}
                    </div>
                  )}
                </div>
                <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                  {project.key}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {showCreate && wsId && (
        <CreateProjectModal wsId={wsId} onClose={() => setShowCreate(false)} />
      )}
    </div>
  )
}
