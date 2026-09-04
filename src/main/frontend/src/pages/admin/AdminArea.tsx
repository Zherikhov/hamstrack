import { useEffect, useRef, useState } from 'react'
import { Navigate, NavLink, Route, Routes, useNavigate } from 'react-router'
import {
  Home, Info, LogOut, ChevronDown, ShieldAlert,
  CircleDot, SlidersHorizontal, Shapes, FormInput, Workflow, FolderKanban, Users,
  type LucideIcon,
} from 'lucide-react'
import { useAuthStore } from '../../auth'
import { apiLogout, globalAdminApi } from '../../api'
import { Avatar } from '../../components/ui'
import AboutModal from '../../components/AboutModal'
import { AdminApiProvider } from './AdminApiContext'
import AdminStatusesPage from './AdminStatusesPage'
import AdminPrioritiesPage from './AdminPrioritiesPage'
import AdminIssueTypesPage from './AdminIssueTypesPage'
import AdminFieldsPage from './AdminFieldsPage'
import AdminWorkflowsPage from './AdminWorkflowsPage'
import AdminProjectsPage from './AdminProjectsPage'
import AdminUsersPage from './AdminUsersPage'

const SECTIONS: { path: string; label: string; icon: LucideIcon }[] = [
  { path: 'statuses', label: 'Statuses', icon: CircleDot },
  { path: 'priorities', label: 'Priorities', icon: SlidersHorizontal },
  { path: 'issue-types', label: 'Issue types', icon: Shapes },
  { path: 'fields', label: 'Fields', icon: FormInput },
  { path: 'workflows', label: 'Workflows', icon: Workflow },
  { path: 'projects', label: 'Projects', icon: FolderKanban },
  { path: 'users', label: 'Users', icon: Users },
]

/* ── Admin "elevated access" rail palette ──────────────────────────────────────
   Deliberately a different hue from the normal ink rail (#101828, blue-black):
   a deep plum background + amber accent so an admin instantly recognises they're
   in a privileged area and remembers to leave it. Same darkness family — a
   noticeable shift, not a jarring one. */
const BG = '#241A33'
const ITEM = '#ADA3BE'
// Collapsed into ITEM (HD-175). #877C9C measured 4.24 on this background — the
// admin rail's own version of the `--color-rail-muted` problem, on a third dark
// surface. The name stays because it documents where the design meant
// de-emphasis; the de-emphasis is carried by size, weight and case, exactly as on
// the main rail.
const MUTED = ITEM
const ACCENT = '#F59E0B'
const ACTIVE_TX = '#FCD34D'
const ACTIVE_BG = 'rgba(245,158,11,0.16)'
const HOVER_BG = 'rgba(255,255,255,0.06)'

/**
 * System administration console (/admin/**): a single dark rail (brand → admin
 * tabs → user profile), no top bar. The rail uses a distinct plum+amber tone to
 * mark elevated access. Client-side guard only for UX — /api/admin/** is
 * enforced server-side by role.
 */
export default function AdminArea() {
  const { user, clear } = useAuthStore()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const [showAbout, setShowAbout] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handle(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    if (menuOpen) document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [menuOpen])

  if (user && user.systemRole !== 'ADMIN') return <Navigate to="/" replace />

  async function handleLogout() {
    setMenuOpen(false)
    try { await apiLogout() } catch { /* ignore */ }
    clear()
    navigate('/login')
  }

  return (
    <div style={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
      {/* Admin rail */}
      <nav className="flex flex-col flex-shrink-0" style={{ width: 214, background: BG, color: ITEM, padding: '14px 10px' }}>
        {/* Brand */}
        <div className="flex items-center gap-2.5" style={{ padding: '6px 8px 12px' }}>
          <span className="flex items-center justify-center flex-shrink-0"
            style={{ width: 28, height: 28, borderRadius: 9, fontWeight: 800, fontSize: 15, color: '#2a1a05', background: `linear-gradient(135deg, ${ACCENT}, #FBBF24)` }}>H</span>
          <b style={{ fontWeight: 800, fontSize: 16, color: '#fff' }}>Hamstrack</b>
        </div>

        {/* Elevated-access caption */}
        <div className="flex items-center gap-2" style={{ margin: '2px 4px 12px', padding: '8px 10px', borderRadius: 10, background: ACTIVE_BG, border: `1px solid rgba(245,158,11,0.28)` }}>
          <ShieldAlert size={15} style={{ color: ACCENT, flexShrink: 0 }} />
          <span style={{ fontSize: 11.5, fontWeight: 700, color: ACTIVE_TX, letterSpacing: '0.02em' }}>System administration</span>
        </div>

        <AdminNavItem to="/home" icon={Home} label="Back to app" exact />

        <div style={{ fontSize: 10.5, letterSpacing: '0.1em', textTransform: 'uppercase', color: MUTED, padding: '14px 11px 6px', fontWeight: 700 }}>Administration</div>
        {SECTIONS.map(s => (
          <AdminNavItem key={s.path} to={`/admin/${s.path}`} icon={s.icon} label={s.label} />
        ))}

        <div style={{ fontSize: 11, color: MUTED, padding: '14px 11px 4px' }}>
          Workspaces — <span style={{ color: MUTED }}>planned</span>
        </div>

        {/* User footer + menu */}
        <div ref={menuRef} style={{ marginTop: 'auto', position: 'relative' }}>
          {menuOpen && (
            <div style={{ position: 'absolute', bottom: 'calc(100% + 6px)', left: 0, right: 0, background: '#2E2142', border: '1px solid rgba(255,255,255,0.12)', borderRadius: 10, overflow: 'hidden', boxShadow: '0 8px 24px rgba(0,0,0,0.45)', zIndex: 40 }}>
              <MenuItem icon={Home} label="Back to app" onClick={() => { setMenuOpen(false); navigate('/home') }} />
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
              <span className="block truncate" style={{ fontSize: 11, color: ACTIVE_TX }}>{user?.systemRole === 'ADMIN' ? 'Admin' : user?.email}</span>
            </span>
            <ChevronDown size={14} style={{ color: MUTED, transform: menuOpen ? 'rotate(180deg)' : 'none', transition: 'transform 150ms' }} />
          </button>
        </div>
      </nav>

      {/* Content */}
      <main className="flex-1 overflow-y-auto" style={{ background: 'var(--color-surface)', borderTop: `3px solid ${ACCENT}` }}>
        <div style={{ maxWidth: 960, padding: 32 }}>
          <AdminApiProvider value={{ api: globalAdminApi, scope: 'global', eyebrow: 'Administration', keyPrefix: ['admin'] }}>
            <Routes>
              <Route index element={<Navigate to="statuses" replace />} />
              <Route path="statuses" element={<AdminStatusesPage />} />
              <Route path="priorities" element={<AdminPrioritiesPage />} />
              <Route path="issue-types" element={<AdminIssueTypesPage />} />
              <Route path="fields" element={<AdminFieldsPage />} />
              <Route path="workflows" element={<AdminWorkflowsPage />} />
              <Route path="projects" element={<AdminProjectsPage />} />
              <Route path="users" element={<AdminUsersPage />} />
            </Routes>
          </AdminApiProvider>
        </div>
      </main>

      {showAbout && <AboutModal onClose={() => setShowAbout(false)} />}
    </div>
  )
}

function AdminNavItem({ to, icon: Icon, label, exact }: { to: string; icon: LucideIcon; label: string; exact?: boolean }) {
  const [h, setH] = useState(false)
  return (
    <NavLink
      to={to}
      end={exact}
      onMouseEnter={() => setH(true)}
      onMouseLeave={() => setH(false)}
      style={({ isActive }) => ({
        display: 'flex', alignItems: 'center', gap: 11,
        padding: '9px 11px', borderRadius: 10,
        fontSize: 13.5, fontWeight: 600, textDecoration: 'none',
        color: isActive ? ACTIVE_TX : (h ? '#fff' : ITEM),
        background: isActive ? ACTIVE_BG : (h ? HOVER_BG : 'transparent'),
      })}
    >
      <Icon size={16} />{label}
    </NavLink>
  )
}

function MenuItem({ icon: Icon, label, onClick }: { icon: LucideIcon; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center gap-2 px-3 py-2 text-sm cursor-pointer text-left"
      style={{ color: 'rgba(255,255,255,0.78)', background: 'transparent' }}
      onMouseEnter={e => (e.currentTarget.style.background = 'rgba(255,255,255,0.06)')}
      onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
    >
      <Icon size={14} />{label}
    </button>
  )
}
