import { Outlet, useParams } from 'react-router'
import { useUiStore } from '../uiStore'
import { useGlobalShortcuts } from '../hooks/useGlobalShortcuts'
import { useReducedMotion } from '../hooks/useReducedMotion'
import NavRail from './NavRail'
import TopSearchBar from './TopSearchBar'
import CreateIssueModal from './CreateIssueModal'
import CommandPalette from './CommandPalette'
import ShortcutsHelp from './ShortcutsHelp'

/**
 * Authenticated app shell (Beacon): dark NavRail + slim light TopSearchBar over
 * the routed content. Wraps global pages (Home, My work, Workspaces) and all
 * project pages; the rail adapts its sections to the current params. The
 * create-issue dialog is rendered here so it's reachable from any shell page.
 *
 * The command palette, the `?` help sheet and the global keyboard map (HD-39)
 * live here too — mounting them at the shell is exactly what scopes them to
 * authenticated app routes: the public pages, `/welcome*` and `/admin/**` render
 * outside this component, so Cmd-K simply falls through to the browser there.
 */
export default function AppShell() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const createIssueOpen = useUiStore(s => s.createIssueOpen)
  const createIssuePreset = useUiStore(s => s.createIssuePreset)
  const closeCreateIssue = useUiStore(s => s.closeCreateIssue)
  const reducedMotion = useReducedMotion()
  const { chordArmed } = useGlobalShortcuts()

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

      <CommandPalette />
      <ShortcutsHelp />

      {/* `g …` chord indicator — cheap discoverability while a chord is armed,
          and a testable artifact for the chord timeout. */}
      {chordArmed && (
        <div
          className="mono"
          aria-hidden="true"
          style={{
            position: 'fixed', left: 16, bottom: 16, zIndex: 80,
            background: 'var(--color-ink-menu)', color: '#fff',
            borderRadius: 'var(--radius-sm)', padding: '4px 9px', fontSize: 11,
            animation: reducedMotion ? undefined : 'cmdk-chord-in 120ms ease-out',
          }}
        >
          g …
        </div>
      )}
    </div>
  )
}
