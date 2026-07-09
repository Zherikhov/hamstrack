import { Outlet, useMatch, useParams } from 'react-router'
import TopBar from './TopBar'
import Sidebar from './Sidebar'

export default function AppShell() {
  const { wsId } = useParams<{ wsId: string }>()
  // Contextual sidebar only makes sense inside a project (any project subpage)
  const inProject = useMatch('/w/:wsId/p/:projectId/*') !== null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }}>
      <TopBar wsId={wsId!} />
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        {inProject && <Sidebar />}
        <main style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
          <Outlet />
        </main>
      </div>
    </div>
  )
}
