import { Navigate, NavLink, Route, Routes, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { apiGetProject, apiGetWorkspace, makeAdminApi } from '../../api'
import { AdminApiProvider } from '../admin/AdminApiContext'
import type { AdminScopeValue } from '../admin/AdminApiContext'
import AdminStatusesPage from '../admin/AdminStatusesPage'
import AdminPrioritiesPage from '../admin/AdminPrioritiesPage'
import AdminIssueTypesPage from '../admin/AdminIssueTypesPage'
import AdminFieldsPage from '../admin/AdminFieldsPage'
import AdminWorkflowsPage from '../admin/AdminWorkflowsPage'
import ProjectBindingsPage from './ProjectBindingsPage'
import ProjectComponentsPage from './ProjectComponentsPage'

/**
 * Project settings area (/w/:wsId/p/:projectId/settings/**). Renders inside the
 * app shell, keeping the project's contextual sidebar. Project MANAGERs *or*
 * workspace OWNER/ADMINs (the server enforces every endpoint; this is a UX
 * guard). Reuses the system admin
 * catalog/set pages via the AdminApi context, scoped to this project — so a
 * project MANAGER creates project-private statuses/workflows/fields and binds
 * them under Taxonomy. Inherited (global/workspace) sets are select-only there.
 */
export default function ProjectSettingsArea() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()

  const { data: project, isLoading } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })

  // The server's `requireProjectCurator` lets a workspace OWNER/ADMIN curate a
  // project they are not a member of (they already edit its bindings), so the
  // guard below has to know the WORKSPACE role too — otherwise such an admin is
  // bounced client-side out of a page they are entitled to.
  const { data: workspace, isLoading: wsLoading } = useQuery({
    queryKey: ['workspace', wsId],
    queryFn: () => apiGetWorkspace(wsId!),
    enabled: !!wsId,
  })

  const settingsBase = `/w/${wsId}/p/${projectId}/settings`
  const scopeValue: AdminScopeValue = {
    api: makeAdminApi(`/workspaces/${wsId}/projects/${projectId}/admin`),
    scope: 'project',
    eyebrow: '',   // the area header below already reads "Project settings"
    keyPrefix: ['project-admin', wsId, projectId],
    // The board/forms read the effective taxonomy — refresh it after any change
    extraInvalidate: [['projectConfig', wsId, projectId]],
  }

  // Absolute paths: inside a splat route (/settings/*) relative links resolve
  // AFTER the splat (see AdminArea) — "statuses" from /settings/fields would
  // navigate to /settings/fields/statuses
  const TABS = [
    { to: settingsBase, label: 'Taxonomy', end: true },
    { to: `${settingsBase}/statuses`, label: 'Statuses', end: false },
    { to: `${settingsBase}/workflows`, label: 'Workflows', end: false },
    { to: `${settingsBase}/issue-types`, label: 'Issue types', end: false },
    { to: `${settingsBase}/priorities`, label: 'Priorities', end: false },
    { to: `${settingsBase}/fields`, label: 'Fields', end: false },
    // Components are project content (HD-31), not bound taxonomy — but their
    // curation belongs with the rest of the project's settings.
    { to: `${settingsBase}/components`, label: 'Components', end: false },
  ]

  // Curator = project MANAGER, or an OWNER/ADMIN of the enclosing workspace
  // (mirrors ScopeResolver.requireProjectCurator). Wait for BOTH lookups before
  // redirecting, so a workspace admin isn't bounced while their role loads.
  const isCurator = project?.myRole === 'MANAGER' || workspace?.myRole === 'OWNER' || workspace?.myRole === 'ADMIN'
  if (!isLoading && !wsLoading && project && !isCurator) {
    return <Navigate to={`/w/${wsId}/p/${projectId}`} replace />
  }

  return (
    <div className="flex-1 overflow-y-auto p-8" style={{ background: 'var(--color-surface)' }}>
      <div style={{ maxWidth: 960 }}>
        <div className="mb-4">
          <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Project settings</div>
          <h1 className="font-display font-bold" style={{ fontSize: 24, letterSpacing: '-0.4px' }}>
            {project?.name ?? '…'}
          </h1>
        </div>

        <nav className="flex gap-1 border-b mb-6" style={{ borderColor: 'var(--color-border)' }}>
          {TABS.map(t => (
            <NavLink key={t.to} to={t.to} end={t.end}
                     className="text-sm no-underline"
                     style={({ isActive }) => ({
                       padding: '8px 14px',
                       marginBottom: -1,
                       borderBottom: `2px solid ${isActive ? 'var(--color-brand)' : 'transparent'}`,
                       color: isActive ? 'var(--color-brand)' : 'var(--color-text-secondary)',
                       fontWeight: isActive ? 600 : 400,
                     })}>
              {t.label}
            </NavLink>
          ))}
        </nav>

        <AdminApiProvider value={scopeValue}>
          <Routes>
            <Route index element={<ProjectBindingsPage />} />
            <Route path="statuses" element={<AdminStatusesPage />} />
            <Route path="workflows" element={<AdminWorkflowsPage />} />
            <Route path="issue-types" element={<AdminIssueTypesPage />} />
            <Route path="priorities" element={<AdminPrioritiesPage />} />
            <Route path="fields" element={<AdminFieldsPage />} />
            <Route path="components" element={<ProjectComponentsPage />} />
            <Route path="*" element={<Navigate to={settingsBase} replace />} />
          </Routes>
        </AdminApiProvider>
      </div>
    </div>
  )
}
