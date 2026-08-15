import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSSE } from '../hooks/useSSE'
import { useCurrentProject } from '../hooks/useCurrentProject'
import { useUiStore } from '../uiStore'
import { apiSearchSchema } from '../api'
import NotificationBell from './NotificationBell'
import ProjectSwitcher from './ProjectSwitcher'
import HqlInput from './HqlInput'
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
  const navigate = useNavigate()
  const cur = useCurrentProject()
  const [incoming, setIncoming] = useState<Notification | null>(null)
  const [query, setQuery] = useState('')
  // Bumped by the `/` global shortcut (HD-39) — hand focus to the HQL box.
  const searchFocusNonce = useUiStore(s => s.searchFocusNonce)

  // The workspace search is scoped to a workspace; use the route ws or the last
  // visited project's ws (via useCurrentProject) so search stays available on
  // global pages. Hidden entirely when there's no workspace context.
  const searchWsId = wsId ?? cur?.wsId

  const { data: schema } = useQuery({
    queryKey: ['searchSchema', searchWsId],
    queryFn: () => apiSearchSchema(searchWsId!),
    enabled: !!searchWsId,
    staleTime: 5 * 60 * 1000,
  })

  function runSearch() {
    if (!searchWsId) return
    const qs = query ? `?q=${encodeURIComponent(query)}` : ''
    navigate(`/w/${searchWsId}/search${qs}`)
  }

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

      {/* HQL search — Enter navigates to the workspace results view. Hidden when
          there's no workspace context (as before). */}
      {searchWsId && (
        <div className="flex items-center" style={{ flex: 1, maxWidth: 460 }}>
          <HqlInput
            wsId={searchWsId}
            value={query}
            onChange={setQuery}
            onSubmit={runSearch}
            schema={schema}
            tone="bar"
            focusNonce={searchFocusNonce}
            placeholder="Search with HQL — e.g. status = &quot;In Progress&quot;"
          />
        </div>
      )}

      <div className="ml-auto flex items-center gap-2">
        <NotificationBell incoming={incoming} tone="light" />
      </div>
    </header>
  )
}
