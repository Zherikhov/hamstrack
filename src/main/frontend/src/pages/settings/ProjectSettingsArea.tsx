import { Navigate, NavLink, Route, Routes, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { apiGetProject, makeAdminApi } from '../../api'
import { canOpenProjectSettings, useProjectPermissions } from '../../hooks/usePermissions'
import { AdminApiProvider } from '../admin/AdminApiContext'
import type { AdminScopeValue } from '../admin/AdminApiContext'
import AdminStatusesPage from '../admin/AdminStatusesPage'
import AdminPrioritiesPage from '../admin/AdminPrioritiesPage'
import AdminIssueTypesPage from '../admin/AdminIssueTypesPage'
import AdminFieldsPage from '../admin/AdminFieldsPage'
import AdminWorkflowsPage from '../admin/AdminWorkflowsPage'
import ProjectBindingsPage from './ProjectBindingsPage'
import ProjectDeliverySettingsPage from './ProjectDeliverySettingsPage'
import ProjectComponentsPage from './ProjectComponentsPage'
import ProjectPeoplePage from './ProjectPeoplePage'

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

  // HD-98/HD-123: the same function the rail's Settings link and the command
  // palette apply, over the server's own permission strings — so the door and
  // the link cannot disagree. It also needs no second (workspace-role) lookup:
  // a workspace admin curating a project they are not a member of already has
  // `project.edit` in the project's `myPermissions`.
  const permissions = useProjectPermissions(wsId, projectId)

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
    // How this team delivers (HD-106): the board question plus the releases and
    // estimation capabilities. Per-project presentation switches, not taxonomy —
    // and never a permission (Rule A). Was "Board" until the tab grew the other
    // two capabilities; `/settings/board` still resolves, see the routes below.
    { to: `${settingsBase}/delivery`, label: 'Delivery', end: false },
  ]

  // Who works here, and under what role. Conditionally mounted on the permission
  // the endpoints themselves check — `project.member.manage` is NOT part of the
  // workspace-wide curator set, so a workspace admin who is not a member of this
  // project genuinely does not have it and must not be shown the tab.
  if (permissions.can('project.member.manage')) {
    TABS.push({ to: `${settingsBase}/people`, label: 'People', end: false })
  }

  // Never redirect on a not-yet-known answer: `isLoading` is the difference
  // between "you may not" and "we have not asked yet", and bouncing on the
  // latter would throw an entitled curator out of their own settings page.
  if (!isLoading && !permissions.isLoading && project && !canOpenProjectSettings(permissions)) {
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
                       color: isActive ? 'var(--color-brand-ink)' : 'var(--color-text-secondary)',
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
            <Route path="people" element={<ProjectPeoplePage />} />
            <Route path="delivery" element={<ProjectDeliverySettingsPage />} />
            {/* The tab was "Board" until HD-106 renamed it. Bookmarks, the link
                the Scrum blurb has always carried and anything a user pasted into
                a ticket must keep working, so the old path redirects rather than
                falling through to the catch-all below (which would silently land
                them on Taxonomy). Absolute target: inside this splat a relative
                one would resolve AFTER the splat segment. */}
            <Route path="board" element={<Navigate to={`${settingsBase}/delivery`} replace />} />
            <Route path="*" element={<Navigate to={settingsBase} replace />} />
          </Routes>
        </AdminApiProvider>
      </div>
    </div>
  )
}
