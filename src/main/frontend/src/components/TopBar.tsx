import { useEffect, useRef, useState } from 'react'
import { useNavigate, useMatch } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ChevronDown, Info, LogOut, Plus, Search } from 'lucide-react'
import { apiLogout } from '../api'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { Avatar } from './ui'
import ProjectSwitcher from './ProjectSwitcher'
import CreateIssueModal from './CreateIssueModal'
import AboutModal from './AboutModal'
import NotificationBell from './NotificationBell'
import { useSSE } from '../hooks/useSSE'
import type { Notification } from '../types'

interface Props {
  /** Absent on workspace-agnostic pages (/workspaces) — hides the project switcher and search. */
  wsId?: string
}

/**
 * Global top bar, shared by every authenticated page.
 * The contextual sidebar below it only covers the current project.
 */
export default function TopBar({ wsId }: Props) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { user, clear } = useAuthStore()
  const createIssueOpen = useUiStore(s => s.createIssueOpen)
  const openCreateIssue = useUiStore(s => s.openCreateIssue)
  const closeCreateIssue = useUiStore(s => s.closeCreateIssue)
  const projectMatch = useMatch('/w/:wsId/p/:projectId/*')
  const projectId = projectMatch?.params.projectId
  const [showUserMenu, setShowUserMenu] = useState(false)
  const [showAbout, setShowAbout] = useState(false)
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

  // The flag lives in a global store — clear it when leaving the workspace shell
  // so the dialog doesn't reappear on the next visit
  useEffect(() => closeCreateIssue, [closeCreateIssue])

  useEffect(() => {
    function handle(e: MouseEvent) {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target as Node)) {
        setShowUserMenu(false)
      }
    }
    if (showUserMenu) document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [showUserMenu])

  async function handleLogout() {
    setShowUserMenu(false)
    try { await apiLogout() } catch { /* ignore */ }
    clear()
    navigate('/login')
  }

  return (
    <header
      className="flex items-center gap-4 px-4 flex-shrink-0"
      style={{ height: 52, background: 'var(--color-ink)', color: 'white' }}
    >
      {/* Logo → workspace list */}
      <button
        onClick={() => navigate('/workspaces')}
        className="flex items-center gap-2 cursor-pointer hover:opacity-80 transition-opacity"
        title="All workspaces"
      >
        <span
          className="flex items-center justify-center rounded font-display font-bold flex-shrink-0"
          style={{ width: 26, height: 26, fontSize: 15, background: 'var(--color-brand)', color: 'white' }}
        >
          H
        </span>
        <span className="font-display font-bold" style={{ fontSize: 16 }}>Hamstrack</span>
      </button>

      {wsId && (
        <>
          <span style={{ width: 1, height: 22, background: 'rgba(255,255,255,0.14)' }} />

          <ProjectSwitcher wsId={wsId} projectId={projectId} />

          {/* Global search — placeholder for the future query language */}
          <div
            className="flex items-center gap-2 px-2.5 py-1.5 rounded"
            style={{
              flex: 1,
              maxWidth: 520,
              background: 'rgba(255,255,255,0.06)',
              border: '1px solid rgba(255,255,255,0.12)',
            }}
          >
            <Search size={13} style={{ color: 'rgba(255,255,255,0.45)', flexShrink: 0 }} />
            <input
              placeholder="Search issues…"
              className="flex-1 min-w-0 bg-transparent outline-none"
              style={{ fontSize: 13.5, color: 'white', border: 'none' }}
            />
            <span
              className="mono flex-shrink-0"
              style={{
                fontSize: 10.5,
                border: '1px solid rgba(255,255,255,0.18)',
                borderRadius: 3,
                padding: '1px 5px',
                color: 'rgba(255,255,255,0.45)',
              }}
            >
              HQL
            </span>
          </div>
        </>
      )}

      <div className="flex items-center gap-3 ml-auto">
        <button
          onClick={openCreateIssue}
          className="flex items-center gap-1.5 font-medium cursor-pointer rounded transition-colors"
          style={{ background: 'var(--color-brand)', color: 'white', border: 'none', padding: '7px 14px', fontSize: 13.5 }}
          onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-brand-hover)')}
          onMouseLeave={e => (e.currentTarget.style.background = 'var(--color-brand)')}
        >
          <Plus size={14} />
          Create
        </button>

        <NotificationBell incoming={incomingNotification} />

        {/* User menu */}
        <div ref={userMenuRef} style={{ position: 'relative' }}>
          <button
            onClick={() => setShowUserMenu(v => !v)}
            className="flex items-center gap-1.5 cursor-pointer hover:opacity-80 transition-opacity"
          >
            {user && <Avatar name={user.displayName} avatarUrl={user.avatarUrl} size={28} />}
            <ChevronDown
              size={13}
              style={{
                color: 'rgba(255,255,255,0.35)',
                transform: showUserMenu ? 'rotate(180deg)' : 'none',
                transition: 'transform 150ms',
              }}
            />
          </button>

          {showUserMenu && (
            <div
              style={{
                position: 'absolute',
                top: 'calc(100% + 8px)',
                right: 0,
                width: 220,
                background: '#2a2927',
                border: '1px solid rgba(255,255,255,0.12)',
                borderRadius: 'var(--radius-md)',
                overflow: 'hidden',
                boxShadow: '0 8px 24px rgba(0,0,0,0.4)',
                zIndex: 40,
              }}
            >
              <div className="px-3 py-2 border-b" style={{ borderColor: 'rgba(255,255,255,0.08)' }}>
                <div className="text-xs font-medium truncate">{user?.displayName}</div>
                <div className="text-xs truncate" style={{ color: 'rgba(255,255,255,0.4)' }}>
                  {user?.email}
                </div>
              </div>
              <button
                onClick={() => { setShowUserMenu(false); setShowAbout(true) }}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm cursor-pointer transition-colors text-left"
                style={{ color: 'rgba(255,255,255,0.7)' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'rgba(255,255,255,0.06)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
              >
                <Info size={13} />
                About Hamstrack
              </button>
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
        </div>
      </div>

      {createIssueOpen && (
        <CreateIssueModal wsId={wsId} defaultProjectId={projectId} onClose={closeCreateIssue} />
      )}

      {showAbout && <AboutModal onClose={() => setShowAbout(false)} />}
    </header>
  )
}
