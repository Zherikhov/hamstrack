import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import NavRail from './NavRail'
import { useAuthStore } from '../auth'
import {
  PROJECT_ADMIN_PERMISSIONS, PROJECT_CONTRIBUTOR_PERMISSIONS,
  PROJECT_CURATOR_BYPASS_PERMISSIONS, PROJECT_VIEWER_PERMISSIONS,
  WORKSPACE_ADMIN_PERMISSIONS,
} from '../test/permissions'
import type { Project, User, Workspace } from '../types'

/**
 * HD-98, and its HD-123 S5 replacement — the rail's **Settings** link must admit
 * exactly the set the server does.
 *
 * The original bug: the rail read `project.myRole === 'MANAGER'` while
 * `ScopeResolver.requireProjectCurator` (and `ProjectSettingsArea`) admitted
 * "project MANAGER *or* workspace OWNER/ADMIN", so a workspace admin who was a
 * plain project member had no link at all — the page served them fine, they just
 * could not find it. That is the worst shape of an authorization mismatch: not a
 * leak, but a feature that silently does not exist for the people who own the
 * workspace.
 *
 * <p>S5 removes the class rather than the instance. The rail no longer has a
 * predicate to get wrong: it asks `canOpenProjectSettings` over the permission
 * strings the server itself checks, and so do the settings area and the command
 * palette. The fixtures below are therefore permission SETS, and the workspace
 * admin case is expressed the way the server expresses it — a project response
 * whose `myRole` still reads VIEWER, carrying the `project.curate.all` bypass.
 *
 * <p>The second half of the ticket is a REQUEST-SHAPE promise, and S5 makes it
 * unconditional: the gate must not put a `GET /workspaces/{id}` behind any board
 * render, for any actor, because the project response already carries the
 * workspace bypass folded in. It is asserted here rather than assumed.
 *
 * <p>Which is why this file drives the REAL api layer over a stubbed `fetch`
 * instead of mocking `../api`: with the module mocked, "no request was made" can
 * only be stated about a spy, and a future refactor that fetched the workspace
 * through a different helper would keep the spy silent and the test green. Here
 * the assertion is about the URL that actually reached the network.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

function project(myPermissions: string[], myRole = 'MEMBER'): Project {
  return {
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Apollo', key: 'AP',
    archived: false, myRole, myPermissions, createdAt: '2026-08-10T09:00:00Z',
  }
}

function workspace(): Workspace {
  return {
    id: WS_ID, name: 'Acme', slug: 'acme', myRole: 'ADMIN',
    myPermissions: WORKSPACE_ADMIN_PERMISSIONS, createdAt: '2026-08-10T09:00:00Z',
  }
}

/** Every path the component actually asked the network for, in order. */
let requested: string[] = []
let projectPermissions: string[] = PROJECT_CONTRIBUTOR_PERMISSIONS
let projectRole = 'MEMBER'

const realFetch = globalThis.fetch

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
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
  requested = []
  projectPermissions = PROJECT_CONTRIBUTOR_PERMISSIONS
  projectRole = 'MEMBER'
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })

  globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const path = url.replace(/^https?:\/\/[^/]+/, '')
    requested.push(path)
    if (path === `/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`) {
      return json(project(projectPermissions, projectRole))
    }
    if (path === `/api/workspaces/${WS_ID}`) return json(workspace())
    if (path === '/api/workspaces') return json([workspace()])
    // An unexpected call must be loud, not an empty 200 that quietly changes a gate.
    throw new Error(`unstubbed request: ${path}`)
  }) as unknown as typeof fetch
})

afterEach(() => {
  globalThis.fetch = realFetch
})

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

const settingsLink = () => screen.queryByRole('link', { name: 'Settings' })

/** The rail is up and the project lookup has settled — safe to assert an ABSENCE. */
async function railSettled() {
  await screen.findByRole('link', { name: 'Board' })
  await waitFor(() => expect(requested).toContain(`/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`))
  await waitFor(() => expect(screen.getByTitle('Apollo')).toBeInTheDocument())
}

describe('NavRail — Settings follows the project-settings permissions (HD-98 / HD-123)', () => {
  it('shows Settings to a workspace admin with only the curator bypass in this project', async () => {
    // Byte-for-byte the server's answer for a workspace OWNER/ADMIN who has no
    // `project_members` row: `myRole` reads VIEWER, and the permissions carry
    // `project.curate.all`'s implied grants. The pre-S5 rail hid the link here.
    projectRole = 'VIEWER'
    projectPermissions = PROJECT_CURATOR_BYPASS_PERMISSIONS
    renderRail()

    expect(await screen.findByRole('link', { name: 'Settings' }))
      .toHaveAttribute('href', `/w/${WS_ID}/p/${PROJECT_ID}/settings`)
  })

  it('shows Settings to a project admin — and asks the network for no workspace at all', async () => {
    projectRole = 'MANAGER'
    projectPermissions = PROJECT_ADMIN_PERMISSIONS
    renderRail()

    expect(await screen.findByRole('link', { name: 'Settings' })).toBeInTheDocument()

    // The request-shape promise, now unconditional: the project response already
    // carries the workspace bypass, so the gate never costs a second round trip
    // — for anyone. Give a stray request a chance before declaring it absent.
    await waitFor(() => expect(requested).toContain(`/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`))
    await new Promise(r => setTimeout(r, 30))
    expect(requested).not.toContain(`/api/workspaces/${WS_ID}`)
  })

  it('shows Settings on a taxonomy-only grant — the door is a disjunction, not one key', async () => {
    projectPermissions = ['project.taxonomy.manage']
    renderRail()

    expect(await screen.findByRole('link', { name: 'Settings' })).toBeInTheDocument()
  })

  it('hides Settings from a contributor, and from a viewer', async () => {
    projectPermissions = PROJECT_CONTRIBUTOR_PERMISSIONS
    renderRail()

    await railSettled()
    expect(settingsLink()).toBeNull()
    // The universal sections are unaffected by the gate.
    expect(screen.getByRole('link', { name: 'Board' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Backlog' })).toBeInTheDocument()
    // …and the absence is a real answer, not a pending one: the project response
    // that produced it is the same one the rail's title came from.
    expect(PROJECT_VIEWER_PERMISSIONS).toHaveLength(0)
  })

  it('draws no Settings link before the permissions are known', async () => {
    projectRole = 'MANAGER'
    projectPermissions = PROJECT_ADMIN_PERMISSIONS
    let release!: () => void
    const held = new Promise<void>(r => { release = r })
    const stubbed = globalThis.fetch
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      await held
      return (stubbed as typeof fetch)(input, init)
    }) as typeof fetch

    renderRail()

    // Board/Backlog are unconditional, so the rail HAS rendered — the gate simply
    // has no answer yet. A conditionally-mounted item stays unmounted while
    // loading (§14.1), so it can pop in but can never flash in and then vanish.
    await screen.findByRole('link', { name: 'Board' })
    expect(settingsLink()).toBeNull()

    release()
    expect(await screen.findByRole('link', { name: 'Settings' })).toBeInTheDocument()
  })
})
