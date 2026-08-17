import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import NavRail from './NavRail'
import { useAuthStore } from '../auth'
import type { Project, User, Workspace } from '../types'

/**
 * HD-98 — the rail's **Settings** link must admit exactly the set the server does.
 *
 * `ScopeResolver.requireProjectCurator` is "project MANAGER *or* workspace
 * OWNER/ADMIN", and `ProjectSettingsArea` already guards on that pair. The rail
 * read only `project.myRole === 'MANAGER'`, so a workspace admin who was a plain
 * project member had no link at all — the page served them fine, they just could
 * not find it. That is the worst shape of an authorization mismatch: not a leak,
 * but a feature that silently does not exist for the people who own the workspace.
 *
 * <p>The second half of the ticket is a REQUEST-SHAPE promise, and it is asserted
 * here rather than assumed: the fix must not put a `GET /workspaces/{id}` behind
 * every board render. `useIsProjectCurator`'s `needsRole` flag exists to skip that
 * lookup whenever the PROJECT role already settles the question — so a MANAGER
 * must produce no such request at all.
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

function project(myRole: Project['myRole']): Project {
  return {
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Apollo', key: 'AP',
    archived: false, myRole, createdAt: '2026-08-10T09:00:00Z',
  }
}

function workspace(myRole: Workspace['myRole']): Workspace {
  return { id: WS_ID, name: 'Acme', slug: 'acme', myRole, createdAt: '2026-08-10T09:00:00Z' }
}

/** Every path the component actually asked the network for, in order. */
let requested: string[] = []
let projectRole: Project['myRole'] = 'MEMBER'
let workspaceRole: Workspace['myRole'] = 'MEMBER'

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
  projectRole = 'MEMBER'
  workspaceRole = 'MEMBER'
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })

  globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const path = url.replace(/^https?:\/\/[^/]+/, '')
    requested.push(path)
    if (path === `/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`) return json(project(projectRole))
    if (path === `/api/workspaces/${WS_ID}`) return json(workspace(workspaceRole))
    if (path === '/api/workspaces') return json([workspace(workspaceRole)])
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

/** The rail is up and both role lookups have settled — safe to assert an ABSENCE. */
async function railSettled() {
  await screen.findByRole('link', { name: 'Board' })
  await waitFor(() => expect(requested).toContain(`/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`))
  await waitFor(() => expect(screen.getByTitle('Apollo')).toBeInTheDocument())
}

describe('NavRail — Settings follows requireProjectCurator (HD-98)', () => {
  it('shows Settings to a workspace OWNER who is only a project MEMBER', async () => {
    projectRole = 'MEMBER'
    workspaceRole = 'OWNER'
    renderRail()

    expect(await screen.findByRole('link', { name: 'Settings' }))
      .toHaveAttribute('href', `/w/${WS_ID}/p/${PROJECT_ID}/settings`)
  })

  it('shows Settings to a workspace ADMIN who is only a project MEMBER', async () => {
    projectRole = 'MEMBER'
    workspaceRole = 'ADMIN'
    renderRail()

    expect(await screen.findByRole('link', { name: 'Settings' })).toBeInTheDocument()
  })

  it('still shows Settings to a project MANAGER — without asking for the workspace', async () => {
    projectRole = 'MANAGER'
    // A workspace role that would NOT grant curation on its own, so the link can
    // only be coming from the project role.
    workspaceRole = 'MEMBER'
    renderRail()

    expect(await screen.findByRole('link', { name: 'Settings' })).toBeInTheDocument()

    // The `needsRole` guard: the project role already settles it, so the extra
    // per-board round trip must never leave the client. Give any stray request a
    // chance to happen before declaring it absent.
    await waitFor(() => expect(requested).toContain(`/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`))
    await new Promise(r => setTimeout(r, 30))
    expect(requested).not.toContain(`/api/workspaces/${WS_ID}`)
  })

  it('hides Settings from a plain member of both the project and the workspace', async () => {
    projectRole = 'MEMBER'
    workspaceRole = 'MEMBER'
    renderRail()

    await railSettled()
    // …and the workspace lookup DID run here — otherwise the absence below would
    // just be "the answer has not arrived yet".
    await waitFor(() => expect(requested).toContain(`/api/workspaces/${WS_ID}`))
    expect(settingsLink()).toBeNull()
    // The universal sections are unaffected by the gate.
    expect(screen.getByRole('link', { name: 'Board' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Backlog' })).toBeInTheDocument()
  })

  it('draws no Settings link before the roles are known', async () => {
    projectRole = 'MEMBER'
    workspaceRole = 'OWNER'
    let release!: () => void
    const held = new Promise<void>(r => { release = r })
    const stubbed = globalThis.fetch
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      await held
      return (stubbed as typeof fetch)(input, init)
    }) as typeof fetch

    renderRail()

    // Board/Backlog are unconditional, so the rail HAS rendered — the gate simply
    // has no answer yet, and an optimistic link would be a flash of a control the
    // user may not be allowed to use.
    await screen.findByRole('link', { name: 'Board' })
    expect(settingsLink()).toBeNull()

    release()
    expect(await screen.findByRole('link', { name: 'Settings' })).toBeInTheDocument()
  })
})
