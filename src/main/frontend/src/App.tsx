import { Fragment, useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate, Outlet, useParams } from 'react-router' // Navigate used for / → /workspaces
import { useAuthStore } from './auth'
import { useConfigStore } from './config'
import { apiRefresh, apiMe, apiPublicConfig } from './api'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import VerifyEmailPage from './pages/VerifyEmailPage'
import WorkspacesPage from './pages/WorkspacesPage'
import WorkspaceHomePage from './pages/WorkspaceHomePage'
import BoardPage from './pages/BoardPage'
import BacklogPage from './pages/BacklogPage'
import AppShell from './components/AppShell'
import CookieBanner from './components/CookieBanner'
import LandingPage from './pages/LandingPage'
import TermsPage from './pages/legal/TermsPage'
import PrivacyPage from './pages/legal/PrivacyPage'
import CookiesPage from './pages/legal/CookiesPage'

function AuthInit({ children }: { children: React.ReactNode }) {
  const { accessToken, setToken, setUser, clear, setInitialized, initialized } = useAuthStore()
  const setConfig = useConfigStore((s) => s.setConfig)

  useEffect(() => {
    async function initAuth() {
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
    }
    async function initConfig() {
      try {
        setConfig(await apiPublicConfig())
      } catch {
        // unreachable — keep fail-safe defaults from the store
      }
    }
    // Both must settle before first render to avoid landing/checkbox flicker
    Promise.all([initAuth(), initConfig()]).then(setInitialized)
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

// React reuses the same component instance when only route params change
// (/w/A/p/X → /w/A/p/Y renders the same <BoardPage/> element), so page state
// — open issue panel, filters — would leak across projects and workspaces.
// Keying the subtree by the params forces a clean remount instead.
function ParamKeyed({ children }: { children: React.ReactNode }) {
  const { wsId, projectId } = useParams()
  return <Fragment key={`${wsId}/${projectId ?? ''}`}>{children}</Fragment>
}

function RequireAuth() {
  const { accessToken } = useAuthStore()
  if (!accessToken) return <Navigate to="/login" replace />
  return <Outlet />
}

// "/" is public: signed-in users go to their workspaces, anonymous visitors see
// the landing page (unless a DC install disabled it via app.legal.*)
function RootRoute() {
  const { accessToken } = useAuthStore()
  const publicLandingEnabled = useConfigStore((s) => s.config.publicLandingEnabled)
  if (accessToken) return <Navigate to="/workspaces" replace />
  if (!publicLandingEnabled) return <Navigate to="/login" replace />
  return <LandingPage />
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthInit>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/cookies" element={<CookiesPage />} />
          <Route path="/" element={<RootRoute />} />
          <Route element={<RequireAuth />}>
            <Route path="/workspaces" element={<WorkspacesPage />} />
            <Route path="/w/:wsId" element={<AppShell />}>
              <Route index element={<ParamKeyed><WorkspaceHomePage /></ParamKeyed>} />
              <Route path="p/:projectId" element={<ParamKeyed><BoardPage /></ParamKeyed>} />
              <Route path="p/:projectId/backlog" element={<ParamKeyed><BacklogPage /></ParamKeyed>} />
            </Route>
          </Route>
        </Routes>
        <CookieBanner />
      </AuthInit>
    </BrowserRouter>
  )
}
