import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Outlet } from 'react-router'
import App from '../App'
import { useAuthStore } from '../auth'
import type { User } from '../types'

/**
 * HD-97 — an unknown URL must render the **not-found screen**, not nothing.
 *
 * The bug report was literally "`document.body.innerText` is empty": `<Routes>`
 * had no catch-all, so any address that matched no route rendered `null` and the
 * user got a white viewport with no way out and no clue what had happened. This
 * suite therefore asserts the SYMPTOM as well as the fix — a non-empty body — so
 * that a future routing change that silently drops the catch-all fails here even
 * if somebody has reworded the screen.
 *
 * <p>It drives the real `<App/>` on purpose. The whole defect lived in the route
 * table, so a test that mounted `NotFoundScreen` directly would have passed
 * against the broken build. `AppShell` is the one thing stubbed — it is the
 * chrome under test only to the extent that it is PRESENT.
 */

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

let me: User | null = null

vi.mock('../api', async importOriginal => ({
  // Keep the real module (App's page tree imports a lot of it at load time);
  // override only the three calls `AuthInit` makes on mount.
  ...(await importOriginal<typeof import('../api')>()),
  apiMe: vi.fn(async () => {
    if (!me) throw new Error('no session')
    return me
  }),
  apiRefresh: vi.fn(async () => { throw new Error('no refresh cookie') }),
  apiPublicConfig: vi.fn(async () => ({
    publicSignupEnabled: true, publicLandingEnabled: true,
  })),
}))

// The shell pulls in the rail, the search bar and an SSE connection — none of
// which this ticket is about. The stub keeps the Outlet so the 404 screen still
// renders inside it, and marks itself so "did the signed-in user keep a way to
// navigate out?" is assertable.
vi.mock('../components/AppShell', () => ({
  default: () => <div data-testid="app-shell"><Outlet /></div>,
}))

beforeAll(() => {
  if (typeof window.matchMedia !== 'function') {
    window.matchMedia = ((query: string) => ({
      matches: false, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia
  }
})

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  me = null
  useAuthStore.setState({ user: null, accessToken: null, initialized: false })
})

afterEach(() => {
  window.history.pushState({}, '', '/')
})

function renderAppAt(path: string) {
  window.history.pushState({}, '', path)
  return render(<App />)
}

function signIn() {
  me = ME
  useAuthStore.setState({ user: ME, accessToken: 'test-token' })
}

describe('HD-97 — an unknown URL renders the not-found screen', () => {
  it('renders a non-empty 404 screen for an anonymous visitor, without bouncing to /login', async () => {
    // A plausible stale link: the board actually lives at /w/{ws}/p/{id}.
    const dead = '/w/ws-1/p/p-1/board'
    renderAppAt(dead)

    expect(await screen.findByText(/doesn.t exist/i)).toBeInTheDocument()
    // The reported symptom, asserted as such.
    expect(document.body.textContent?.trim()).not.toBe('')
    // The offending address is echoed so the typo is visible.
    expect(screen.getByText(dead)).toBeInTheDocument()
    expect(screen.getByText('404')).toBeInTheDocument()

    // The catch-all sits OUTSIDE RequireAuth: a typo is not a protected resource,
    // so an anonymous visitor must not be redirected into a sign-in loop that
    // returns them to the same dead URL.
    expect(window.location.pathname).toBe(dead)
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to the start page' })).toHaveAttribute('href', '/')
  })

  it('keeps the app shell — and a way out — for a signed-in user', async () => {
    signIn()
    renderAppAt('/nope/not/a/page')

    expect(await screen.findByText(/doesn.t exist/i)).toBeInTheDocument()
    // A dead end WITH navigation is a detour; without it, it is a trap.
    expect(screen.getByTestId('app-shell')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Home' })).toHaveAttribute('href', '/home')
    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull()
  })

  it('does not hijack a real route', async () => {
    // The guard against "fixed the 404 by 404-ing everything": a known public
    // path must still render its own page.
    renderAppAt('/login')

    await screen.findByRole('button', { name: /sign in/i })
    expect(screen.queryByText(/doesn.t exist/i)).toBeNull()
  })
})
