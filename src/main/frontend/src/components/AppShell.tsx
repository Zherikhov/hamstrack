import { Outlet, useParams } from 'react-router'
import { useUiStore } from '../uiStore'
import NavRail from './NavRail'
import TopSearchBar from './TopSearchBar'
import CreateIssueModal from './CreateIssueModal'

/**
 * Authenticated app shell (Beacon): dark NavRail + slim light TopSearchBar over
 * the routed content. Wraps global pages (Home, My work, Workspaces) and all
 * project pages; the rail adapts its sections to the current params. The
 * create-issue dialog is rendered here so it's reachable from any shell page.
 */
export default function AppShell() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const createIssueOpen = useUiStore(s => s.createIssueOpen)
  const createIssuePreset = useUiStore(s => s.createIssuePreset)
  const closeCreateIssue = useUiStore(s => s.closeCreateIssue)

  return (
    <div style={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
      <NavRail />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <TopSearchBar wsId={wsId} />
        <main style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column', background: 'var(--color-surface)' }}>
          <Outlet />
        </main>
      </div>

      {createIssueOpen && (
        <CreateIssueModal
          wsId={wsId}
          defaultProjectId={projectId}
          preset={createIssuePreset}
          onClose={closeCreateIssue}
        />
      )}
    </div>
  )
}
