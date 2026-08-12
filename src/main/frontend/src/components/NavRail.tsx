import { useEffect, useRef, useState } from 'react'
import { NavLink, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import {
  Home, CheckSquare, Columns3, ListTodo, BarChart3, Settings, Search,
  Plus, Info, LogOut, Settings as Gear, ChevronDown, LayoutGrid, type LucideIcon,
} from 'lucide-react'
import { apiGetProject, apiListWorkspaces, apiLogout } from '../api'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { useCurrentProject } from '../hooks/useCurrentProject'
import { getLastWorkspaceId } from '../recentProjects'
import { Avatar } from './ui'
import AboutModal from './AboutModal'

const RAIL_BG = 'var(--color-ink)'
const ITEM = 'var(--color-rail-text)'
const ACTIVE = 'var(--color-rail-active)'
const ACTIVE_BG = 'rgba(14,165,164,0.18)'
const HOVER_BG = 'rgba(255,255,255,0.06)'
const MUTED = 'var(--color-rail-muted)'

/**
 * Dark navigation rail (Beacon). Carries the brand, the primary "New issue"
 * action, global items (Home / My work), the current project's sections, and a
 * user-menu footer. Replaces the old horizontal TopBar + light project Sidebar.
 */
export default function NavRail() {
  const navigate = useNavigate()
  const cur = useCurrentProject()
  const { user, clear } = useAuthStore()
  const openCreateIssue = useUiStore(s => s.openCreateIssue)
  const [menuOpen, setMenuOpen] = useState(false)
  const [showAbout, setShowAbout] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  const { data: project } = useQuery({
    queryKey: ['project', cur?.wsId, cur?.projectId],
    queryFn: () => apiGetProject(cur!.wsId, cur!.projectId),
    enabled: !!cur,
  })

  // Resolve a workspace for the Search link so it's reachable even before the
  // user has opened any board (`cur` is null then). Fallback chain: current
  // project's ws → most-recent workspace from the recency journal → the user's
  // first workspace (shared ['workspaces'] cache — ProjectSwitcher/CreateIssue
  // already populate it, so this rarely refetches). Only truly-zero-workspace
  // users (brand-new) end up with no wsId, and Search hides for them.
  const { data: workspaces } = useQuery({
    queryKey: ['workspaces'],
    queryFn: apiListWorkspaces,
    enabled: !cur,
    staleTime: 5 * 60 * 1000,
  })
  const searchWsId =
    cur?.wsId
    ?? (user ? getLastWorkspaceId(user.id) : null)
    ?? workspaces?.[0]?.id
    ?? null

  useEffect(() => {
    function handle(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    if (menuOpen) document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [menuOpen])

  async function handleLogout() {
    setMenuOpen(false)
    try { await apiLogout() } catch { /* ignore */ }
    clear()
    navigate('/login')
  }

  return (
    <nav
      className="flex flex-col flex-shrink-0"
      style={{ width: 214, background: RAIL_BG, color: ITEM, padding: '14px 10px' }}
    >
      {/* Brand */}
      <button
        onClick={() => navigate('/home')}
        className="flex items-center gap-2.5 cursor-pointer"
        style={{ padding: '6px 8px 14px', background: 'none', border: 'none' }}
        title="Home"
      >
        <span
          className="flex items-center justify-center flex-shrink-0"
          style={{ width: 28, height: 28, borderRadius: 9, fontWeight: 800, fontSize: 15, color: '#04211f',
            background: 'linear-gradient(135deg, var(--color-brand), var(--color-accent-2))' }}
        >H</span>
        <b style={{ fontWeight: 800, fontSize: 16, color: '#fff' }}>Hamstrack</b>
      </button>

      {/* Primary action */}
      <button
        onClick={() => openCreateIssue()}
        className="flex items-center justify-center gap-2 cursor-pointer"
        style={{
          margin: '2px 4px 12px', padding: 10, borderRadius: 11, border: 'none',
          fontWeight: 800, fontSize: 13.5, color: '#04211f',
          background: 'linear-gradient(135deg, var(--color-brand), var(--color-accent-2))',
        }}
      >
        <Plus size={16} strokeWidth={2.6} />New issue
      </button>

      <RailLink to="/home" icon={Home} label="Home" />
      <RailLink to="/my-work" icon={CheckSquare} label="My work" />
      {/* Search — reachable whenever a workspace is resolvable (absolute path;
          splat-route rule), not just when a current project exists. Hidden only
          for brand-new users with zero workspaces. */}
      {searchWsId && <RailLink to={`/w/${searchWsId}/search`} icon={Search} label="Search" />}

      {/* Project section — always visible, bound to the current (last-visited)
          project so the tabs never disappear (e.g. on Home / My work). */}
      {cur && (
        <>
          <div
            className="truncate"
            style={{ fontSize: 10.5, letterSpacing: '0.1em', textTransform: 'uppercase', color: MUTED, padding: '16px 11px 6px', fontWeight: 700 }}
            title={project?.name ?? cur.name}
          >
            {project?.name ?? cur.name ?? 'Project'}
          </div>
          <RailLink to={`/w/${cur.wsId}/p/${cur.projectId}`} end icon={Columns3} label="Board" />
          <RailLink to={`/w/${cur.wsId}/p/${cur.projectId}/backlog`} icon={ListTodo} label="Backlog" />
          {/* Reports — no backend yet */}
          <div
            style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '9px 11px', borderRadius: 10, fontSize: 13.5, fontWeight: 600, color: MUTED, cursor: 'default' }}
            title="Coming soon"
          >
            <BarChart3 size={17} />Reports
            <span className="mono" style={{ marginLeft: 'auto', fontSize: 9, letterSpacing: '0.05em', color: MUTED, border: '1px solid rgba(255,255,255,0.12)', borderRadius: 5, padding: '1px 5px' }}>SOON</span>
          </div>
          {project?.myRole === 'MANAGER' && (
            <RailLink to={`/w/${cur.wsId}/p/${cur.projectId}/settings`} icon={Settings} label="Settings" />
          )}
        </>
      )}

      {/* No project yet (brand-new user) — get them into one */}
      {!cur && (
        <>
          <div style={{ fontSize: 10.5, letterSpacing: '0.1em', textTransform: 'uppercase', color: MUTED, padding: '16px 11px 6px', fontWeight: 700 }}>Workspace</div>
          <RailLink to="/workspaces" icon={LayoutGrid} label="All projects" />
        </>
      )}

      {/* User footer + menu */}
      <div ref={menuRef} style={{ marginTop: 'auto', position: 'relative' }}>
        {menuOpen && (
          <div
            style={{
              position: 'absolute', bottom: 'calc(100% + 6px)', left: 0, right: 0,
              background: 'var(--color-ink-menu)', border: '1px solid rgba(255,255,255,0.12)',
              borderRadius: 10, overflow: 'hidden', boxShadow: '0 8px 24px rgba(0,0,0,0.4)', zIndex: 40,
            }}
          >
            {user?.systemRole === 'ADMIN' && (
              <MenuItem icon={Gear} label="System administration" onClick={() => { setMenuOpen(false); navigate('/admin') }} />
            )}
            <MenuItem icon={Info} label="About Hamstrack" onClick={() => { setMenuOpen(false); setShowAbout(true) }} />
            <MenuItem icon={LogOut} label="Sign out" onClick={handleLogout} />
          </div>
        )}
        <button
          onClick={() => setMenuOpen(v => !v)}
          className="w-full flex items-center gap-2.5 cursor-pointer"
          style={{ padding: '9px 8px', borderRadius: 10, background: 'none', border: '1px solid rgba(255,255,255,0.06)' }}
          onMouseEnter={e => (e.currentTarget.style.background = HOVER_BG)}
          onMouseLeave={e => (e.currentTarget.style.background = 'none')}
        >
          {user && <Avatar name={user.displayName} avatarUrl={user.avatarUrl} size={30} />}
          <span className="flex-1 min-w-0 text-left">
            <span className="block truncate" style={{ fontSize: 13, fontWeight: 600, color: '#fff' }}>{user?.displayName}</span>
            <span className="block truncate" style={{ fontSize: 11, color: MUTED }}>{user?.systemRole === 'ADMIN' ? 'Admin' : user?.email}</span>
          </span>
          <ChevronDown size={14} style={{ color: MUTED, transform: menuOpen ? 'rotate(180deg)' : 'none', transition: 'transform 150ms' }} />
        </button>
      </div>

      {showAbout && <AboutModal onClose={() => setShowAbout(false)} />}
    </nav>
  )
}

function RailLink({ to, end, icon: Icon, label }: { to: string; end?: boolean; icon: LucideIcon; label: string }) {
  const [h, setH] = useState(false)
  return (
    <NavLink
      to={to}
      end={end}
      onMouseEnter={() => setH(true)}
      onMouseLeave={() => setH(false)}
      style={({ isActive }) => ({
        display: 'flex', alignItems: 'center', gap: 11,
        padding: '9px 11px', borderRadius: 10,
        fontSize: 13.5, fontWeight: 600, textDecoration: 'none',
        color: isActive ? ACTIVE : (h ? '#fff' : ITEM),
        background: isActive ? ACTIVE_BG : (h ? HOVER_BG : 'transparent'),
      })}
    >
      <Icon size={17} />{label}
    </NavLink>
  )
}

function MenuItem({ icon: Icon, label, onClick }: { icon: LucideIcon; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center gap-2 px-3 py-2 text-sm cursor-pointer text-left"
      style={{ color: 'rgba(255,255,255,0.75)', background: 'transparent' }}
      onMouseEnter={e => (e.currentTarget.style.background = 'rgba(255,255,255,0.06)')}
      onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
    >
      <Icon size={14} />{label}
    </button>
  )
}
