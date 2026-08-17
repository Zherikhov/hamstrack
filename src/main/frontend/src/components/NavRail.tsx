import { useEffect, useRef, useState } from 'react'
import { NavLink, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import {
  Home, CheckSquare, Columns3, ListTodo, Rocket, BarChart3, Settings, Search,
  Plus, Info, LogOut, Settings as Gear, ChevronDown, LayoutGrid,
  PanelLeftClose, PanelLeftOpen, Keyboard, type LucideIcon,
} from 'lucide-react'
import { apiGetProject, apiListWorkspaces, apiLogout } from '../api'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { useCurrentProject } from '../hooks/useCurrentProject'
import { deliveryOf } from '../hooks/useProjectDelivery'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { getLastWorkspaceId } from '../recentProjects'
import { getUiPrefs, setUiPref } from '../uiPrefs'
import { Avatar } from './ui'
import ResizeHandle from './ResizeHandle'
import AboutModal from './AboutModal'

const RAIL_BG = 'var(--color-ink)'
const ITEM = 'var(--color-rail-text)'
const ACTIVE = 'var(--color-rail-active)'
const ACTIVE_BG = 'rgba(14,165,164,0.18)'
const HOVER_BG = 'rgba(255,255,255,0.06)'
const MUTED = 'var(--color-rail-muted)'

// Rail sizing (HD-53). Width is user-draggable within [MIN, MAX] and additionally
// capped at 40% of the viewport so it can't swallow the screen; COLLAPSED is the
// icon-only strip.
const RAIL_MIN = 180
const RAIL_MAX = 320
const RAIL_DEFAULT = 220
const RAIL_COLLAPSED = 60

const railMax = () => Math.min(RAIL_MAX, Math.floor(window.innerWidth * 0.4))
const clampRail = (w: number) => Math.max(RAIL_MIN, Math.min(railMax(), w))

/**
 * Dark navigation rail (Beacon). Carries the brand, the primary "New issue"
 * action, global items (Home / My work), the current project's sections, and a
 * user-menu footer. Replaces the old horizontal TopBar + light project Sidebar.
 *
 * The rail is user-resizable (drag the right edge, clamped) and collapsible to an
 * icon-only strip; both are persisted per-user in localStorage (HD-53).
 */
export default function NavRail() {
  const navigate = useNavigate()
  const cur = useCurrentProject()
  const { user, clear } = useAuthStore()
  const openCreateIssue = useUiStore(s => s.openCreateIssue)
  const openHelp = useUiStore(s => s.openHelp)
  const reducedMotion = useReducedMotion()
  const [menuOpen, setMenuOpen] = useState(false)
  const [showAbout, setShowAbout] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  // Layout state — hydrated from per-user prefs once the user is known.
  const [width, setWidth] = useState(RAIL_DEFAULT)
  const [collapsed, setCollapsed] = useState(false)
  const [dragging, setDragging] = useState(false)
  const widthRef = useRef(width)
  const draggingRef = useRef(false)

  useEffect(() => {
    if (!user) return
    const prefs = getUiPrefs(user.id)
    if (typeof prefs.railWidth === 'number') {
      const w = clampRail(prefs.railWidth)
      setWidth(w)
      widthRef.current = w
    }
    if (typeof prefs.railCollapsed === 'boolean') setCollapsed(prefs.railCollapsed)
  }, [user?.id])

  // Re-clamp when the viewport shrinks so the rail never exceeds 40% of a
  // now-smaller window. Load also clamps, so this need not persist.
  useEffect(() => {
    function onResize() {
      setWidth(w => {
        const c = clampRail(w)
        widthRef.current = c
        return c
      })
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  const { data: project } = useQuery({
    queryKey: ['project', cur?.wsId, cur?.projectId],
    queryFn: () => apiGetProject(cur!.wsId, cur!.projectId),
    enabled: !!cur,
  })
  // HD-102: which project sections this project's way of working calls for. Read
  // off the entry above — the rail is the surface that CACHES it, so asking here
  // is free (and `useProjectDelivery` elsewhere then hits this same entry).
  const delivery = deliveryOf(project)

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

  function handleResize(next: number) {
    widthRef.current = next
    setWidth(next)
    if (!draggingRef.current && user) setUiPref(user.id, 'railWidth', next)
  }

  function handleDragChange(d: boolean) {
    draggingRef.current = d
    setDragging(d)
    // Persist once, at drag end, rather than on every pointer move.
    if (!d && user) setUiPref(user.id, 'railWidth', widthRef.current)
  }

  function toggleCollapse() {
    setCollapsed(c => {
      const next = !c
      if (user) setUiPref(user.id, 'railCollapsed', next)
      return next
    })
  }

  const effectiveWidth = collapsed ? RAIL_COLLAPSED : width
  const railTransition = dragging || reducedMotion ? 'none' : 'width 180ms ease'

  return (
    <nav
      className="flex flex-col flex-shrink-0"
      style={{
        position: 'relative',
        width: effectiveWidth,
        background: RAIL_BG,
        color: ITEM,
        padding: collapsed ? '14px 8px' : '14px 10px',
        transition: railTransition,
      }}
    >
      {/* Brand */}
      <button
        onClick={() => navigate('/home')}
        className="flex items-center gap-2.5 cursor-pointer"
        style={{ padding: collapsed ? '6px 0 14px' : '6px 8px 14px', justifyContent: collapsed ? 'center' : 'flex-start', background: 'none', border: 'none' }}
        title="Home"
      >
        <span
          className="flex items-center justify-center flex-shrink-0"
          style={{ width: 28, height: 28, borderRadius: 9, fontWeight: 800, fontSize: 15, color: '#04211f',
            background: 'linear-gradient(135deg, var(--color-brand), var(--color-accent-2))' }}
        >H</span>
        {!collapsed && <b style={{ fontWeight: 800, fontSize: 16, color: '#fff', whiteSpace: 'nowrap' }}>Hamstrack</b>}
      </button>

      {/* Primary action */}
      <button
        onClick={() => openCreateIssue()}
        className="flex items-center justify-center gap-2 cursor-pointer"
        style={{
          alignSelf: collapsed ? 'center' : 'stretch',
          width: collapsed ? 26 : undefined, height: collapsed ? 26 : undefined,
          margin: collapsed ? '2px 0 12px' : '2px 4px 12px', padding: collapsed ? 0 : 10,
          borderRadius: collapsed ? 8 : 11, border: 'none',
          fontWeight: 800, fontSize: 13.5, color: '#04211f', whiteSpace: 'nowrap', overflow: 'hidden',
          background: 'linear-gradient(135deg, var(--color-brand), var(--color-accent-2))',
        }}
        title={collapsed ? 'New issue' : undefined}
      >
        <Plus size={collapsed ? 15 : 16} strokeWidth={2.6} />{!collapsed && 'New issue'}
      </button>

      <RailLink to="/home" icon={Home} label="Home" collapsed={collapsed} />
      <RailLink to="/my-work" icon={CheckSquare} label="My work" collapsed={collapsed} />
      {/* Search — reachable whenever a workspace is resolvable (absolute path;
          splat-route rule), not just when a current project exists. Hidden only
          for brand-new users with zero workspaces. */}
      {searchWsId && <RailLink to={`/w/${searchWsId}/search`} icon={Search} label="Search" collapsed={collapsed} />}

      {/* Project section — always visible, bound to the current (last-visited)
          project so the tabs never disappear (e.g. on Home / My work). */}
      {cur && (
        <>
          {!collapsed && (
            <div
              className="truncate"
              style={{ fontSize: 10.5, letterSpacing: '0.1em', textTransform: 'uppercase', color: MUTED, padding: '16px 11px 6px', fontWeight: 700 }}
              title={project?.name ?? cur.name}
            >
              {project?.name ?? cur.name ?? 'Project'}
            </div>
          )}
          {collapsed && <div style={{ height: 12 }} />}
          <RailLink to={`/w/${cur.wsId}/p/${cur.projectId}`} end icon={Columns3} label="Board" collapsed={collapsed} />
          <RailLink to={`/w/${cur.wsId}/p/${cur.projectId}/backlog`} icon={ListTodo} label="Backlog" collapsed={collapsed} />
          {/* Releases (HD-32) — versions are working data with a lifecycle, so
              they are managed on their own page, not in project settings.
              HD-102 §6: the rail item is gated on the DECLARED `releases`
              capability (never on "does this project have versions yet?"), while
              the ROUTE still resolves — a project with releases off can still be
              linked/bookmarked into the page, which is where its own enabling
              affordance lives (Rule C). */}
          {delivery.releases && (
            <RailLink to={`/w/${cur.wsId}/p/${cur.projectId}/releases`} icon={Rocket} label="Releases" collapsed={collapsed} />
          )}
          {/* Reports — no backend yet */}
          <div
            style={{ display: 'flex', alignItems: 'center', justifyContent: collapsed ? 'center' : 'flex-start', gap: 11, padding: collapsed ? '9px 0' : '9px 11px', borderRadius: 10, fontSize: 13.5, fontWeight: 600, color: MUTED, cursor: 'default' }}
            title={collapsed ? 'Reports — coming soon' : 'Coming soon'}
          >
            <BarChart3 size={17} />
            {!collapsed && <>Reports
              <span className="mono" style={{ marginLeft: 'auto', fontSize: 9, letterSpacing: '0.05em', color: MUTED, border: '1px solid rgba(255,255,255,0.12)', borderRadius: 5, padding: '1px 5px' }}>SOON</span>
            </>}
          </div>
          {project?.myRole === 'MANAGER' && (
            <RailLink to={`/w/${cur.wsId}/p/${cur.projectId}/settings`} icon={Settings} label="Settings" collapsed={collapsed} />
          )}
        </>
      )}

      {/* No project yet (brand-new user) — get them into one */}
      {!cur && (
        <>
          {!collapsed && <div style={{ fontSize: 10.5, letterSpacing: '0.1em', textTransform: 'uppercase', color: MUTED, padding: '16px 11px 6px', fontWeight: 700 }}>Workspace</div>}
          {collapsed && <div style={{ height: 12 }} />}
          <RailLink to="/workspaces" icon={LayoutGrid} label="All projects" collapsed={collapsed} />
        </>
      )}

      {/* Collapse toggle + user footer, pinned to the bottom */}
      <div style={{ marginTop: 'auto' }}>
        <button
          onClick={toggleCollapse}
          className="w-full flex items-center gap-2.5 cursor-pointer"
          style={{ padding: collapsed ? '9px 0' : '9px 11px', justifyContent: collapsed ? 'center' : 'flex-start', marginBottom: 6, borderRadius: 10, background: 'none', border: 'none', color: MUTED, fontSize: 12.5, fontWeight: 600 }}
          onMouseEnter={e => (e.currentTarget.style.background = HOVER_BG)}
          onMouseLeave={e => (e.currentTarget.style.background = 'none')}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? <PanelLeftOpen size={17} /> : <><PanelLeftClose size={17} />Collapse</>}
        </button>

        <div ref={menuRef} style={{ position: 'relative' }}>
          {menuOpen && (
            <div
              style={{
                position: 'absolute', bottom: 'calc(100% + 6px)', left: 0,
                right: collapsed ? 'auto' : 0, minWidth: collapsed ? 200 : undefined,
                background: 'var(--color-ink-menu)', border: '1px solid rgba(255,255,255,0.12)',
                borderRadius: 10, overflow: 'hidden', boxShadow: '0 8px 24px rgba(0,0,0,0.4)', zIndex: 40,
              }}
            >
              {user?.systemRole === 'ADMIN' && (
                <MenuItem icon={Gear} label="System administration" onClick={() => { setMenuOpen(false); navigate('/admin') }} />
              )}
              {/* Keyboard map — the `?` overlay needs a mouse-reachable entry
                  point too, or it is only discoverable by accident (HD-39 §9). */}
              <MenuItem icon={Keyboard} label="Keyboard shortcuts" onClick={() => { setMenuOpen(false); openHelp() }} />
              <MenuItem icon={Info} label="About Hamstrack" onClick={() => { setMenuOpen(false); setShowAbout(true) }} />
              <MenuItem icon={LogOut} label="Sign out" onClick={handleLogout} />
            </div>
          )}
          <button
            onClick={() => setMenuOpen(v => !v)}
            className="w-full flex items-center gap-2.5 cursor-pointer"
            style={{ padding: collapsed ? '9px 0' : '9px 8px', justifyContent: collapsed ? 'center' : 'flex-start', borderRadius: 10, background: 'none', border: '1px solid rgba(255,255,255,0.06)' }}
            onMouseEnter={e => (e.currentTarget.style.background = HOVER_BG)}
            onMouseLeave={e => (e.currentTarget.style.background = 'none')}
            title={collapsed ? user?.displayName : undefined}
          >
            {user && <Avatar name={user.displayName} avatarUrl={user.avatarUrl} size={30} />}
            {!collapsed && (
              <>
                <span className="flex-1 min-w-0 text-left">
                  <span className="block truncate" style={{ fontSize: 13, fontWeight: 600, color: '#fff' }}>{user?.displayName}</span>
                  <span className="block truncate" style={{ fontSize: 11, color: MUTED }}>{user?.systemRole === 'ADMIN' ? 'Admin' : user?.email}</span>
                </span>
                <ChevronDown size={14} style={{ color: MUTED, transform: menuOpen ? 'rotate(180deg)' : 'none', transition: 'transform 150ms' }} />
              </>
            )}
          </button>
        </div>
      </div>

      {/* Drag-to-resize handle on the right edge (hidden when collapsed) */}
      {!collapsed && (
        <ResizeHandle
          side="right"
          size={width}
          min={RAIL_MIN}
          max={railMax}
          onResize={handleResize}
          onDragChange={handleDragChange}
          ariaLabel="Resize sidebar"
        />
      )}

      {showAbout && <AboutModal onClose={() => setShowAbout(false)} />}
    </nav>
  )
}

function RailLink({ to, end, icon: Icon, label, collapsed }: { to: string; end?: boolean; icon: LucideIcon; label: string; collapsed: boolean }) {
  const [h, setH] = useState(false)
  return (
    <NavLink
      to={to}
      end={end}
      onMouseEnter={() => setH(true)}
      onMouseLeave={() => setH(false)}
      title={collapsed ? label : undefined}
      style={({ isActive }) => ({
        display: 'flex', alignItems: 'center', justifyContent: collapsed ? 'center' : 'flex-start', gap: 11,
        padding: collapsed ? '9px 0' : '9px 11px', borderRadius: 10,
        fontSize: 13.5, fontWeight: 600, textDecoration: 'none', whiteSpace: 'nowrap',
        color: isActive ? ACTIVE : (h ? '#fff' : ITEM),
        background: isActive ? ACTIVE_BG : (h ? HOVER_BG : 'transparent'),
      })}
    >
      <Icon size={17} />{!collapsed && label}
    </NavLink>
  )
}

function MenuItem({ icon: Icon, label, onClick }: { icon: LucideIcon; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center gap-2 px-3 py-2 text-sm cursor-pointer text-left"
      style={{ color: 'rgba(255,255,255,0.75)', background: 'transparent', whiteSpace: 'nowrap' }}
      onMouseEnter={e => (e.currentTarget.style.background = 'rgba(255,255,255,0.06)')}
      onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
    >
      <Icon size={14} />{label}
    </button>
  )
}
