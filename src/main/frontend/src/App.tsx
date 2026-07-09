import { useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router' // Navigate used for / → /workspaces
import { useAuthStore } from './auth'
import { apiRefresh, apiMe } from './api'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import WorkspacesPage from './pages/WorkspacesPage'
import WorkspaceHomePage from './pages/WorkspaceHomePage'
import BoardPage from './pages/BoardPage'
import BacklogPage from './pages/BacklogPage'
import AppShell from './components/AppShell'

function AuthInit({ children }: { children: React.ReactNode }) {
  const { accessToken, setToken, setUser, clear, setInitialized, initialized } = useAuthStore()

  useEffect(() => {
    async function init() {
      if (accessToken) {
        try {
          const user = await apiMe()
          setUser(user)
        } catch {
          // Token stale — try refresh
          try {
            const data = await apiRefresh()
            setToken(data.accessToken)
            const user = await apiMe()
            setUser(user)
          } catch {
            clear()
          }
        }
      }
      setInitialized()
    }
    init()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (!initialized) {
    return (
      <div className="h-full flex items-center justify-center" style={{ background: 'var(--color-surface)' }}>
        <span className="mono" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
      </div>
    )
  }

  return <>{children}</>
}

function RequireAuth() {
  const { accessToken } = useAuthStore()
  if (!accessToken) return <Navigate to="/login" replace />
  return <Outlet />
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthInit>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route element={<RequireAuth />}>
            <Route path="/" element={<Navigate to="/workspaces" replace />} />
            <Route path="/workspaces" element={<WorkspacesPage />} />
            <Route path="/w/:wsId" element={<AppShell />}>
              <Route index element={<WorkspaceHomePage />} />
              <Route path="p/:projectId" element={<BoardPage />} />
              <Route path="p/:projectId/backlog" element={<BacklogPage />} />
            </Route>
          </Route>
        </Routes>
      </AuthInit>
    </BrowserRouter>
  )
}
