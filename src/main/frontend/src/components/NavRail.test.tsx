import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import NavRail from './NavRail'
import { useAuthStore } from '../auth'
import { PROJECT_ADMIN_PERMISSIONS } from '../test/permissions'
import type { Project, ProjectDelivery, User } from '../types'

/**
 * HD-102 §6 — the rail's Releases item is gated on the DECLARED `releases`
 * capability, never on "does this project have versions yet?". Board, Backlog
 * and Settings are universal and stay put in every configuration.
 *
 * The rail is also the surface that CACHES `['project', wsId, projectId]`, which
 * is what makes `useProjectDelivery` free on every other project page — so this
 * is the one place the gate is decided from a fetch rather than from a cache hit,
 * and the loading window is worth pinning too (§ the UNKNOWN fallback: nothing
 * path-specific is drawn before the project resolves).
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const BASE: Project = {
  id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
  archived: false, myRole: 'MANAGER', myPermissions: PROJECT_ADMIN_PERMISSIONS,
  createdAt: '2026-01-01T00:00:00Z',
}

let project: Project = BASE
let resolveProject: (() => void) | null = null

vi.mock('../api', () => ({
  ApiResponseError: class ApiResponseError extends Error { status = 0 },
  apiGetProject: vi.fn(async () => {
    if (resolveProject) await new Promise<void>(r => { resolveProject = r })
    return project
  }),
  apiListWorkspaces: vi.fn(async () => [
    { id: WS_ID, name: 'WS', slug: 'ws', myRole: 'OWNER', createdAt: '2026-01-01T00:00:00Z' },
  ]),
  apiLogout: vi.fn(),
}))

function withDelivery(delivery: ProjectDelivery): Project {
  return { ...BASE, delivery }
}

function renderRail() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId" element={<NavRail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeAll(() => {
  // jsdom has no matchMedia; the rail's `useReducedMotion` queries it.
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
  resolveProject = null
  project = BASE
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
})

describe('NavRail — Releases follows the declared capability (HD-102)', () => {
  it('shows the Releases item when the project declares releases, with none curated', async () => {
    project = withDelivery({ board: 'KANBAN', releases: true, estimation: false, preset: 'RELEASES' })
    renderRail()

    expect(await screen.findByRole('link', { name: 'Releases' }))
      .toHaveAttribute('href', `/w/${WS_ID}/p/${PROJECT_ID}/releases`)
  })

  it('hides it when releases are off — while Board and Backlog stay universal', async () => {
    project = withDelivery({ board: 'SCRUM', releases: false, estimation: true, preset: 'SCRUM' })
    renderRail()

    // Wait for the project entry to land before asserting an absence, so this
    // can't pass merely because nothing has rendered yet.
    await screen.findByRole('link', { name: 'Backlog' })
    await waitFor(() => expect(screen.getByTitle('Proj')).toBeInTheDocument())
    expect(screen.queryByRole('link', { name: 'Releases' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Board' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Settings' })).toBeInTheDocument()
  })

  it('keeps it for a project that predates `delivery` (§7 upgrade rule)', async () => {
    // Every pre-HD-102 project could reach the Releases page; the upgrade must
    // not take the rail item away from a team that was about to use it.
    project = { ...BASE, boardMode: 'KANBAN' }
    renderRail()

    expect(await screen.findByRole('link', { name: 'Releases' })).toBeInTheDocument()
  })

  it('draws no Releases item while the project is still loading', async () => {
    resolveProject = () => {}
    project = withDelivery({ board: 'KANBAN', releases: true, estimation: false, preset: 'RELEASES' })
    renderRail()

    // Board/Backlog are unconditional, so the rail is rendered — but the
    // capability is not known yet and nothing path-specific may be drawn.
    await screen.findByRole('link', { name: 'Board' })
    expect(screen.queryByRole('link', { name: 'Releases' })).toBeNull()

    resolveProject?.()
    expect(await screen.findByRole('link', { name: 'Releases' })).toBeInTheDocument()
  })
})
