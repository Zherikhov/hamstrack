import { NavLink, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { BarChart3, Columns3, ListTodo, Settings } from 'lucide-react'
import { apiGetProject } from '../api'

/**
 * Contextual sidebar — sections of the CURRENT project only.
 * Workspace-level navigation (project switching, search, user) lives in TopBar.
 */
export default function Sidebar() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()

  const { data: project } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })

  return (
    <nav
      className="flex flex-col flex-shrink-0 border-r"
      style={{ width: 200, background: 'var(--color-surface)', borderColor: 'var(--color-border)', paddingTop: 12 }}
    >
      {/* Current project header */}
      <div
        className="flex items-center gap-2 border-b"
        style={{ padding: '4px 14px 14px', borderColor: 'var(--color-border)', marginBottom: 10 }}
      >
        <span
          className="mono font-semibold flex-shrink-0"
          style={{
            fontSize: 11,
            background: 'var(--color-surface-2)',
            border: '1px solid var(--color-border-2)',
            borderRadius: 3,
            padding: '2px 6px',
            color: 'var(--color-text-secondary)',
          }}
        >
          {project?.key ?? '…'}
        </span>
        <span className="font-semibold truncate" style={{ fontSize: 13.5 }}>
          {project?.name ?? '…'}
        </span>
      </div>

      {[
        { icon: Columns3, label: 'Board', to: `/w/${wsId}/p/${projectId}`, end: true },
        { icon: ListTodo, label: 'Backlog', to: `/w/${wsId}/p/${projectId}/backlog`, end: false },
      ].map(({ icon: Icon, label, to, end }) => (
        <NavLink
          key={label}
          to={to}
          end={end}
          className="flex items-center gap-2.5 no-underline transition-colors"
          style={({ isActive }) => ({
            padding: '7px 14px',
            fontSize: 14,
            borderLeft: `2px solid ${isActive ? 'var(--color-brand)' : 'transparent'}`,
            color: isActive ? 'var(--color-brand)' : 'var(--color-text-secondary)',
            fontWeight: isActive ? 600 : 400,
            background: isActive ? 'var(--color-surface-2)' : 'transparent',
          })}
        >
          <Icon size={15} />
          {label}
        </NavLink>
      ))}

      {/* Future sections — kept visible so the shell structure reads correctly */}
      {[
        { icon: BarChart3, label: 'Reports' },
        { icon: Settings, label: 'Settings' },
      ].map(({ icon: Icon, label }) => (
        <div
          key={label}
          className="flex items-center gap-2.5"
          style={{
            padding: '7px 14px',
            fontSize: 14,
            borderLeft: '2px solid transparent',
            color: 'var(--color-text-muted)',
            cursor: 'default',
            opacity: 0.6,
          }}
          title="Coming soon"
        >
          <Icon size={15} />
          {label}
          <span
            className="mono ml-auto"
            style={{ fontSize: 9, color: 'var(--color-text-muted)', letterSpacing: '0.04em' }}
          >
            SOON
          </span>
        </div>
      ))}
    </nav>
  )
}
