import { Fragment, Suspense, lazy, useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate, Outlet, useParams, useLocation } from 'react-router' // Navigate used for / → /workspaces
import { useAuthStore } from './auth'
import { useConfigStore } from './config'
import { apiRefresh, apiMe, apiPublicConfig } from './api'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import VerifyEmailPage from './pages/VerifyEmailPage'
import ResetPasswordPage from './pages/ResetPasswordPage'
import WorkspacesPage from './pages/WorkspacesPage'
import WorkspaceHomePage from './pages/WorkspaceHomePage'
import WelcomePage from './pages/welcome/WelcomePage'
import JoinTeamPage from './pages/welcome/JoinTeamPage'
import HomePage from './pages/HomePage'
import MyWorkPage from './pages/MyWorkPage'
import BoardPage from './pages/BoardPage'
import BacklogPage from './pages/BacklogPage'
import ReleasesPage from './pages/ReleasesPage'
import IssueFullPage from './pages/IssueFullPage'
import SearchResultsPage from './pages/SearchResultsPage'
import ProjectSettingsArea from './pages/settings/ProjectSettingsArea'
import WorkspaceSettingsArea from './pages/settings/WorkspaceSettingsArea'
import AppShell from './components/AppShell'
import CookieBanner from './components/CookieBanner'
import LandingPage from './pages/LandingPage'
import TermsPage from './pages/legal/TermsPage'
import PrivacyPage from './pages/legal/PrivacyPage'
import CookiesPage from './pages/legal/CookiesPage'

// Lazy: Swagger UI is heavy and must not weigh down the main app bundle
const DocsPage = lazy(() => import('./pages/docs/DocsPage'))
// Lazy: the admin console is only ever loaded by system admins
const AdminArea = lazy(() => import('./pages/admin/AdminArea'))

function LazyFallback() {
  return (
    <div className="h-full flex items-center justify-center" style={{ background: 'var(--color-surface)' }}>
      <span className="mono" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
    </div>
  )
}

function AuthInit({ children }: { children: React.ReactNode }) {
  const { accessToken, setToken, setUser, clear, setInitialized, initialized } = useAuthStore()
  const setConfig = useConfigStore((s) => s.setConfig)

  useEffect(() => {
    async function initAuth() {
      // Try the in-memory access token first (survives page reloads within a tab).
      if (accessToken) {
        try {
          setUser(await apiMe())
          return
        } catch {
          // Access token stale/expired — fall through to the refresh cookie below.
        }
      }
      // No usable access token (e.g. a fresh tab after the browser was closed —
      // sessionStorage is gone). The 30-day HttpOnly refresh cookie may still be
      // valid, so always attempt a silent refresh to restore the session.
      try {
        const data = await apiRefresh()
        setToken(data.accessToken)
        setUser(await apiMe())
      } catch {
        clear()
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
  const { accessToken, user } = useAuthStore()
  const { pathname } = useLocation()
  if (!accessToken) return <Navigate to="/login" replace />
  // First-login onboarding gate (Cloud): until the user creates or joins a team
  // (or skips), every authenticated route funnels into the welcome flow.
  if (user?.needsOnboarding && !pathname.startsWith('/welcome')) {
    return <Navigate to="/welcome" replace />
  }
  return <Outlet />
}

// Self-registration is closed on DC (and wherever an operator disables it):
// the /register route redirects to /login when public signup is off.
function RequirePublicSignup({ children }: { children: React.ReactNode }) {
  const publicSignupEnabled = useConfigStore((s) => s.config.publicSignupEnabled)
  if (!publicSignupEnabled) return <Navigate to="/login" replace />
  return <>{children}</>
}

// "/" is public: signed-in users land on the Home dashboard (Beacon default),
// anonymous visitors see the landing page (unless a DC install disabled it via
// app.legal.*)
function RootRoute() {
  const { accessToken, user } = useAuthStore()
  const publicLandingEnabled = useConfigStore((s) => s.config.publicLandingEnabled)
  if (accessToken) {
    if (user?.needsOnboarding) return <Navigate to="/welcome" replace />
    return <Navigate to="/home" replace />
  }
  if (!publicLandingEnabled) return <Navigate to="/login" replace />
  return <LandingPage />
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthInit>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RequirePublicSignup><RegisterPage /></RequirePublicSignup>} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/cookies" element={<CookiesPage />} />
          <Route path="/docs" element={<Suspense fallback={<LazyFallback />}><DocsPage /></Suspense>} />
          <Route path="/" element={<RootRoute />} />
          <Route element={<RequireAuth />}>
            <Route path="/welcome" element={<WelcomePage />} />
            <Route path="/welcome/invites" element={<JoinTeamPage />} />
            <Route path="/admin/*" element={<Suspense fallback={<LazyFallback />}><AdminArea /></Suspense>} />
            {/* Beacon shell (rail + top bar) wraps all global + project pages */}
            <Route element={<AppShell />}>
              <Route path="/home" element={<HomePage />} />
              <Route path="/my-work" element={<MyWorkPage />} />
              <Route path="/workspaces" element={<WorkspacesPage />} />
              <Route path="/w/:wsId">
                <Route index element={<ParamKeyed><WorkspaceHomePage /></ParamKeyed>} />
                <Route path="search" element={<ParamKeyed><SearchResultsPage /></ParamKeyed>} />
                <Route path="settings/*" element={<ParamKeyed><WorkspaceSettingsArea /></ParamKeyed>} />
                <Route path="p/:projectId" element={<ParamKeyed><BoardPage /></ParamKeyed>} />
                <Route path="p/:projectId/backlog" element={<ParamKeyed><BacklogPage /></ParamKeyed>} />
                <Route path="p/:projectId/releases" element={<ParamKeyed><ReleasesPage /></ParamKeyed>} />
                <Route path="p/:projectId/issues/:number" element={<ParamKeyed><IssueFullPage /></ParamKeyed>} />
                <Route path="p/:projectId/settings/*" element={<ParamKeyed><ProjectSettingsArea /></ParamKeyed>} />
              </Route>
            </Route>
          </Route>
        </Routes>
        <CookieBanner />
      </AuthInit>
    </BrowserRouter>
  )
}
