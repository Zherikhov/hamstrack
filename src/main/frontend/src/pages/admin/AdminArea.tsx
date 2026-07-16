import { Navigate, NavLink, Route, Routes } from 'react-router'
import { useAuthStore } from '../../auth'
import TopBar from '../../components/TopBar'
import AdminStatusesPage from './AdminStatusesPage'
import AdminPrioritiesPage from './AdminPrioritiesPage'
import AdminIssueTypesPage from './AdminIssueTypesPage'
import AdminFieldsPage from './AdminFieldsPage'
import AdminWorkflowsPage from './AdminWorkflowsPage'
import AdminProjectsPage from './AdminProjectsPage'

const SECTIONS = [
  { path: 'statuses', label: 'Statuses' },
  { path: 'priorities', label: 'Priorities' },
  { path: 'issue-types', label: 'Issue types' },
  { path: 'fields', label: 'Fields' },
  { path: 'workflows', label: 'Workflows' },
  { path: 'projects', label: 'Projects' },
] as const

/**
 * System administration console (/admin/**): global top bar + admin sidebar,
 * same two-level shell pattern as project pages. Client-side guard only for
 * UX — /api/admin/** is enforced server-side by role.
 */
export default function AdminArea() {
  const { user } = useAuthStore()
  if (user && user.systemRole !== 'ADMIN') return <Navigate to="/" replace />

  return (
    <div className="h-full flex flex-col">
      <TopBar />
      <div className="flex-1 flex overflow-hidden">
        <nav className="flex-shrink-0 border-r py-4 px-2 overflow-y-auto"
             style={{ width: 220, background: 'white', borderColor: 'var(--color-border)' }}>
          <div className="text-xs font-semibold uppercase tracking-wider px-3 pb-2"
               style={{ color: 'var(--color-text-muted)' }}>
            Administration
          </div>
          {SECTIONS.map(s => (
            // Absolute paths: inside a splat route (/admin/*) relative links
            // resolve AFTER the splat — "statuses" from /admin/priorities
            // would navigate to /admin/priorities/statuses
            <NavLink key={s.path} to={`/admin/${s.path}`}
                     className="block px-3 py-1.5 rounded text-sm mb-0.5"
                     style={({ isActive }) => ({
                       color: isActive ? 'var(--color-brand)' : 'var(--color-text-secondary)',
                       background: isActive ? '#E7F0EE' : 'transparent',
                       fontWeight: isActive ? 600 : 400,
                     })}>
              {s.label}
            </NavLink>
          ))}
          <div className="border-t mt-3 pt-3 px-3" style={{ borderColor: 'var(--color-border)' }}>
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              Users, Workspaces — planned
            </span>
          </div>
        </nav>
        <main className="flex-1 overflow-y-auto p-8" style={{ background: 'var(--color-surface)' }}>
          <div style={{ maxWidth: 960 }}>
            <Routes>
              <Route index element={<Navigate to="statuses" replace />} />
              <Route path="statuses" element={<AdminStatusesPage />} />
              <Route path="priorities" element={<AdminPrioritiesPage />} />
              <Route path="issue-types" element={<AdminIssueTypesPage />} />
              <Route path="fields" element={<AdminFieldsPage />} />
              <Route path="workflows" element={<AdminWorkflowsPage />} />
              <Route path="projects" element={<AdminProjectsPage />} />
            </Routes>
          </div>
        </main>
      </div>
    </div>
  )
}
