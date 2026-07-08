import { useEffect, useRef, useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { LayoutGrid, FolderOpen, LogOut, Plus, ChevronUp } from 'lucide-react'
import { apiListProjects, apiGetWorkspace, apiLogout } from '../api'
import { useAuthStore } from '../auth'
import { Avatar } from './ui'
import CreateProjectModal from './CreateProjectModal'
import NotificationBell from './NotificationBell'
import { useSSE } from '../hooks/useSSE'
import type { Notification } from '../types'

export default function Sidebar() {
  const { wsId } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { user, clear } = useAuthStore()
  const [showCreateProject, setShowCreateProject] = useState(false)
  const [showUserMenu, setShowUserMenu] = useState(false)
  const [incomingNotification, setIncomingNotification] = useState<Notification | null>(null)
  const userMenuRef = useRef<HTMLDivElement>(null)

  // SSE: real-time updates for the current workspace
  useSSE(wsId, {
    ISSUE_CREATED: (data: unknown) => {
      const d = data as { projectId: string }
      qc.invalidateQueries({ queryKey: ['issues', wsId, d.projectId] })
    },
    ISSUE_UPDATED: (data: unknown) => {
      const d = data as { projectId: string; issueNumber: number }
      qc.invalidateQueries({ queryKey: ['issues', wsId, d.projectId] })
      qc.invalidateQueries({ queryKey: ['issue', wsId, d.projectId, d.issueNumber] })
    },
    ISSUE_DELETED: (data: unknown) => {
      const d = data as { projectId: string }
      qc.invalidateQueries({ queryKey: ['issues', wsId, d.projectId] })
    },
    COMMENT_ADDED: (data: unknown) => {
      const d = data as { projectId: string; issueNumber: number }
      qc.invalidateQueries({ queryKey: ['comments', wsId, d.projectId, d.issueNumber] })
    },
    NOTIFICATION: (data: unknown) => {
      setIncomingNotification(data as Notification)
    },
  })

  const { data: workspace } = useQuery({
    queryKey: ['workspace', wsId],
    queryFn: () => apiGetWorkspace(wsId!),
    enabled: !!wsId,
  })

  const { data: projects = [] } = useQuery({
    queryKey: ['projects', wsId],
    queryFn: () => apiListProjects(wsId!),
    enabled: !!wsId,
  })

  // Close user menu on outside click
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target as Node)) {
        setShowUserMenu(false)
      }
    }
    if (showUserMenu) document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [showUserMenu])

  async function handleLogout() {
    setShowUserMenu(false)
    try { await apiLogout() } catch { /* ignore */ }
    clear()
    navigate('/login')
  }

  return (
    <>
      <nav
        style={{
          background: 'var(--color-ink)',
          color: 'white',
          width: 220,
          minWidth: 220,
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
          overflow: 'hidden',
          position: 'relative',
        }}
      >
        {/* Workspace header */}
        <div
          className="flex items-center gap-2 px-3 py-3 cursor-pointer hover:opacity-80 transition-opacity border-b"
          style={{ borderColor: 'rgba(255,255,255,0.08)' }}
          onClick={() => navigate('/workspaces')}
        >
          <span
            className="flex items-center justify-center rounded font-display font-bold text-sm flex-shrink-0"
            style={{ width: 28, height: 28, background: 'var(--color-brand)', color: 'white' }}
          >
            {workspace?.name?.[0]?.toUpperCase() ?? 'H'}
          </span>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-medium truncate">{workspace?.name ?? '…'}</div>
            <div className="text-xs opacity-40">workspace</div>
          </div>
          <LayoutGrid size={14} className="opacity-40 flex-shrink-0" />
        </div>

        {/* Projects */}
        <div className="flex-1 overflow-y-auto py-2">
          <div
            className="flex items-center justify-between px-3 py-1.5"
            style={{ color: 'rgba(255,255,255,0.4)' }}
          >
            <span className="text-xs font-medium tracking-wider uppercase">Projects</span>
            <button
              onClick={() => setShowCreateProject(true)}
              className="hover:opacity-80 transition-opacity cursor-pointer"
              title="New project"
            >
              <Plus size={14} />
            </button>
          </div>

          {projects.filter(p => !p.archived).map(project => (
            <NavLink
              key={project.id}
              to={`/w/${wsId}/p/${project.id}`}
              className="flex items-center gap-2 px-3 py-1.5 text-sm transition-colors no-underline"
              style={({ isActive }) => ({
                color: isActive ? 'white' : 'rgba(255,255,255,0.65)',
                background: isActive ? 'rgba(255,255,255,0.08)' : 'transparent',
              })}
            >
              <FolderOpen size={14} className="flex-shrink-0 opacity-70" />
              <span className="truncate">{project.name}</span>
              <span className="mono text-xs opacity-40 flex-shrink-0 ml-auto">{project.key}</span>
            </NavLink>
          ))}

          {projects.length === 0 && (
            <div className="px-3 py-2 text-xs italic" style={{ color: 'rgba(255,255,255,0.3)' }}>
              No projects yet
            </div>
          )}
        </div>

        {/* Notification bell + user footer */}
        <div ref={userMenuRef} style={{ position: 'relative' }}>
          {/* Dropdown menu */}
          {showUserMenu && (
            <div
              style={{
                position: 'absolute',
                bottom: '100%',
                left: 8,
                right: 8,
                background: '#2a2927',
                border: '1px solid rgba(255,255,255,0.12)',
                borderRadius: 'var(--radius-md)',
                overflow: 'hidden',
                boxShadow: '0 -8px 24px rgba(0,0,0,0.4)',
                zIndex: 10,
              }}
            >
              <div
                className="px-3 py-2 border-b"
                style={{ borderColor: 'rgba(255,255,255,0.08)' }}
              >
                <div className="text-xs font-medium truncate">{user?.displayName}</div>
                <div className="text-xs truncate" style={{ color: 'rgba(255,255,255,0.4)' }}>
                  {user?.email}
                </div>
              </div>
              <button
                onClick={handleLogout}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm cursor-pointer transition-colors text-left"
                style={{ color: 'rgba(255,255,255,0.7)' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'rgba(255,255,255,0.06)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
              >
                <LogOut size={13} />
                Sign out
              </button>
            </div>
          )}

          {/* Footer trigger */}
          <div
            className="flex items-center border-t"
            style={{ borderColor: 'rgba(255,255,255,0.08)' }}
          >
            <button
              onClick={() => setShowUserMenu(v => !v)}
              className="flex-1 flex items-center gap-2 px-3 py-2.5 cursor-pointer transition-opacity hover:opacity-80 text-left min-w-0"
            >
              {user && <Avatar name={user.displayName} avatarUrl={user.avatarUrl} size={26} />}
              <div className="flex-1 min-w-0">
                <div className="text-xs font-medium truncate">{user?.displayName}</div>
              </div>
              <ChevronUp
                size={13}
                style={{
                  color: 'rgba(255,255,255,0.35)',
                  transform: showUserMenu ? 'rotate(180deg)' : 'none',
                  transition: 'transform 150ms',
                  flexShrink: 0,
                }}
              />
            </button>
            <div className="px-2 flex-shrink-0">
              <NotificationBell incoming={incomingNotification} />
            </div>
          </div>
        </div>
      </nav>

      {showCreateProject && wsId && (
        <CreateProjectModal wsId={wsId} onClose={() => setShowCreateProject(false)} />
      )}
    </>
  )
}
