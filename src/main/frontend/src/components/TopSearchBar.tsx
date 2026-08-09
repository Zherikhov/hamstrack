import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { useSSE } from '../hooks/useSSE'
import { useCurrentProject } from '../hooks/useCurrentProject'
import NotificationBell from './NotificationBell'
import ProjectSwitcher from './ProjectSwitcher'
import type { Notification } from '../types'

interface Props {
  /** Route workspace (drives the SSE subscription); absent on global pages. */
  wsId?: string
}

/**
 * Slim light top bar over the content column (Beacon). Holds the project
 * switcher, global search (future HQL) and notifications; primary navigation
 * and Create live in the dark NavRail. Owns the workspace SSE subscription.
 */
export default function TopSearchBar({ wsId }: Props) {
  const qc = useQueryClient()
  const cur = useCurrentProject()
  const [incoming, setIncoming] = useState<Notification | null>(null)

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
    NOTIFICATION: (data: unknown) => setIncoming(data as Notification),
  })

  return (
    <header
      className="flex items-center gap-3 flex-shrink-0 border-b"
      style={{ height: 60, padding: '0 22px', background: 'var(--color-card)', borderColor: 'var(--color-border)' }}
    >
      {/* Project switcher — first, then HQL search */}
      <ProjectSwitcher wsId={cur?.wsId} projectId={cur?.projectId} tone="light" />

      <div
        className="flex items-center gap-2.5"
        style={{
          flex: 1, maxWidth: 420,
          background: 'var(--color-surface)',
          borderRadius: 'var(--radius-md)',
          padding: '9px 14px',
        }}
      >
        <Search size={15} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} />
        <input
          placeholder="Search issues, projects, people…"
          className="flex-1 min-w-0 bg-transparent outline-none"
          style={{ fontSize: 13.5, color: 'var(--color-text)', border: 'none' }}
        />
        <span
          className="mono flex-shrink-0"
          style={{
            fontSize: 10.5, fontWeight: 600,
            background: 'var(--color-card)',
            borderRadius: 6, padding: '2px 7px',
            color: 'var(--color-text-muted)',
          }}
        >
          HQL
        </span>
      </div>

      <div className="ml-auto flex items-center gap-2">
        <NotificationBell incoming={incoming} tone="light" />
      </div>
    </header>
  )
}
