import { Navigate, NavLink, Route, Routes, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { apiGetWorkspace, makeAdminApi } from '../../api'
import { AdminApiProvider } from '../admin/AdminApiContext'
import type { AdminScopeValue } from '../admin/AdminApiContext'
import AdminStatusesPage from '../admin/AdminStatusesPage'
import AdminPrioritiesPage from '../admin/AdminPrioritiesPage'
import AdminIssueTypesPage from '../admin/AdminIssueTypesPage'
import AdminFieldsPage from '../admin/AdminFieldsPage'
import AdminWorkflowsPage from '../admin/AdminWorkflowsPage'
import WorkspaceProjectsMatrix from './WorkspaceProjectsMatrix'

const SECTIONS = [
  { path: '', label: 'Projects', end: true },
  { path: 'statuses', label: 'Statuses', end: false },
  { path: 'workflows', label: 'Workflows', end: false },
  { path: 'issue-types', label: 'Issue types', end: false },
  { path: 'priorities', label: 'Priorities', end: false },
  { path: 'fields', label: 'Fields', end: false },
]

/**
 * Workspace settings (/w/:wsId/settings/**) — a workspace OWNER/ADMIN manages
 * workspace-scoped taxonomy and the binding matrix for every project in the
 * workspace. Renders inside the app shell (no project sidebar here), with its own
 * admin-style nav. Reuses the system catalog/set pages via the AdminApi context,
 * scoped to the workspace: global rows are inherited (read-only), workspace rows
 * are editable. The server enforces the OWNER/ADMIN role on every endpoint.
 */
export default function WorkspaceSettingsArea() {
  const { wsId } = useParams<{ wsId: string }>()

  const { data: workspace, isLoading } = useQuery({
    queryKey: ['workspace', wsId],
    queryFn: () => apiGetWorkspace(wsId!),
    enabled: !!wsId,
  })

  const base = `/w/${wsId}/settings`
  const scopeValue: AdminScopeValue = {
    api: makeAdminApi(`/workspaces/${wsId}/admin`),
    scope: 'workspace',
    eyebrow: 'Workspace settings',
    keyPrefix: ['ws-admin', wsId],
  }

  if (!isLoading && workspace && workspace.myRole === 'MEMBER') {
    return <Navigate to={`/w/${wsId}`} replace />
  }

  return (
    <div className="flex-1 flex overflow-hidden">
      <nav className="flex-shrink-0 border-r py-4 px-2 overflow-y-auto"
           style={{ width: 210, background: 'white', borderColor: 'var(--color-border)' }}>
        <div className="text-xs font-semibold uppercase tracking-wider px-3 pb-1"
             style={{ color: 'var(--color-text-muted)' }}>
          Workspace settings
        </div>
        <div className="text-sm font-semibold px-3 pb-2 truncate" title={workspace?.name}>
          {workspace?.name ?? '…'}
        </div>
        {SECTIONS.map(s => (
          // Absolute paths: inside a splat route relative links resolve after the splat
          <NavLink key={s.path} to={s.path ? `${base}/${s.path}` : base} end={s.end}
                   className="block px-3 py-1.5 rounded text-sm mb-0.5"
                   style={({ isActive }) => ({
                     color: isActive ? 'var(--color-brand)' : 'var(--color-text-secondary)',
                     background: isActive ? '#E7F0EE' : 'transparent',
                     fontWeight: isActive ? 600 : 400,
                   })}>
            {s.label}
          </NavLink>
        ))}
      </nav>
      <main className="flex-1 overflow-y-auto p-8" style={{ background: 'var(--color-surface)' }}>
        <div style={{ maxWidth: 960 }}>
          <AdminApiProvider value={scopeValue}>
            <Routes>
              <Route index element={<WorkspaceProjectsMatrix />} />
              <Route path="statuses" element={<AdminStatusesPage />} />
              <Route path="workflows" element={<AdminWorkflowsPage />} />
              <Route path="issue-types" element={<AdminIssueTypesPage />} />
              <Route path="priorities" element={<AdminPrioritiesPage />} />
              <Route path="fields" element={<AdminFieldsPage />} />
              <Route path="*" element={<Navigate to={base} replace />} />
            </Routes>
          </AdminApiProvider>
        </div>
      </main>
    </div>
  )
}
