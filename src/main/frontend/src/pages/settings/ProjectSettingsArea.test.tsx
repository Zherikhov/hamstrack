import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import ProjectSettingsArea from './ProjectSettingsArea'
import type { Project, Workspace } from '../../types'

// HD-31 changed this area's access guard, and a bad guard is a silent lockout:
// the server's `ScopeResolver.requireProjectCurator` lets a project MANAGER *or* a
// workspace OWNER/ADMIN curate a project's components, so the client-side UX guard
// has to admit exactly the same set — and, just as importantly, it must WAIT for
// both lookups before redirecting. The pre-HD-31 guard read only the project role
// and bounced anyone whose `myRole !== 'MANAGER'`, which would throw a workspace
// admin out of a page the API happily serves them.
//
// What is asserted here is the guard's truth table plus the loading race, not the
// tab markup.

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

function project(myRole: Project['myRole']): Project {
  return {
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Apollo', key: 'AP',
    archived: false, myRole, createdAt: '2026-08-10T09:00:00Z',
  }
}

function workspace(myRole: Workspace['myRole']): Workspace {
  return { id: WS_ID, name: 'Acme', slug: 'acme', myRole, createdAt: '2026-08-10T09:00:00Z' }
}

const apiGetProjectMock = vi.fn<() => Promise<Project>>()
const apiGetWorkspaceMock = vi.fn<() => Promise<Workspace>>()

vi.mock('../../api', () => ({
  apiGetProject: () => apiGetProjectMock(),
  apiGetWorkspace: () => apiGetWorkspaceMock(),
  // The area hands this to the admin pages through AdminApiContext; the pages
  // themselves are stubbed below, so it only has to exist.
  makeAdminApi: () => ({}),
}))

// The nested pages are heavy and irrelevant to the guard — stub every route target.
vi.mock('../admin/AdminStatusesPage', () => ({ default: () => <div /> }))
vi.mock('../admin/AdminPrioritiesPage', () => ({ default: () => <div /> }))
vi.mock('../admin/AdminIssueTypesPage', () => ({ default: () => <div /> }))
vi.mock('../admin/AdminFieldsPage', () => ({ default: () => <div /> }))
vi.mock('../admin/AdminWorkflowsPage', () => ({ default: () => <div /> }))
vi.mock('./ProjectBindingsPage', () => ({ default: () => <div>Bindings page</div> }))
vi.mock('./ProjectComponentsPage', () => ({ default: () => <div>Components page</div> }))
vi.mock('./ProjectBoardSettingsPage', () => ({ default: () => <div>Board settings page</div> }))

function renderArea(initialPath = `/w/${WS_ID}/p/${PROJECT_ID}/settings`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId" element={<div>Board page</div>} />
          <Route path="/w/:wsId/p/:projectId/settings/*" element={<ProjectSettingsArea />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Deferred promise, so a query can be held in its loading state on purpose. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  apiGetProjectMock.mockReset()
  apiGetWorkspaceMock.mockReset()
})

describe('ProjectSettingsArea — curator guard (HD-31)', () => {
  it('admits a project MANAGER and offers the Components tab', async () => {
    apiGetProjectMock.mockResolvedValue(project('MANAGER'))
    apiGetWorkspaceMock.mockResolvedValue(workspace('MEMBER'))
    renderArea()

    expect(await screen.findByRole('heading', { name: 'Apollo' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Components' }).getAttribute('href'))
      .toBe(`/w/${WS_ID}/p/${PROJECT_ID}/settings/components`)
    expect(screen.queryByText('Board page')).toBeNull()
  })

  it.each([['OWNER'], ['ADMIN']] as const)(
    'admits a workspace %s who is not a project MANAGER (the server-side bypass)',
    async role => {
      // The exact shape that used to be bounced: the project role is not MANAGER,
      // only the workspace role entitles them.
      apiGetProjectMock.mockResolvedValue(project('MEMBER'))
      apiGetWorkspaceMock.mockResolvedValue(workspace(role))
      renderArea()

      expect(await screen.findByRole('heading', { name: 'Apollo' })).toBeTruthy()
      expect(screen.queryByText('Board page')).toBeNull()
    },
  )

  it('redirects a plain project MEMBER of a workspace they do not administer', async () => {
    apiGetProjectMock.mockResolvedValue(project('MEMBER'))
    apiGetWorkspaceMock.mockResolvedValue(workspace('MEMBER'))
    renderArea()

    expect(await screen.findByText('Board page')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Components' })).toBeNull()
  })

  it('redirects a project VIEWER', async () => {
    apiGetProjectMock.mockResolvedValue(project('VIEWER'))
    apiGetWorkspaceMock.mockResolvedValue(workspace('MEMBER'))
    renderArea()

    expect(await screen.findByText('Board page')).toBeTruthy()
  })

  it('does not redirect while the workspace role is still loading', async () => {
    // The race the guard has to survive: the project lookup resolves first and says
    // "not a MANAGER", but the workspace lookup — which may still say OWNER/ADMIN —
    // has not come back yet. Redirecting here would bounce a legitimate curator out
    // of their own settings page on a slow connection, non-deterministically.
    const ws = deferred<Workspace>()
    apiGetProjectMock.mockResolvedValue(project('MEMBER'))
    apiGetWorkspaceMock.mockReturnValue(ws.promise)
    renderArea()

    // The project query has resolved (its name is on screen) …
    expect(await screen.findByRole('heading', { name: 'Apollo' })).toBeTruthy()
    // … and nothing has navigated away.
    expect(screen.queryByText('Board page')).toBeNull()

    ws.resolve(workspace('ADMIN'))
    await waitFor(() => expect(screen.getByRole('link', { name: 'Components' })).toBeTruthy())
    expect(screen.queryByText('Board page')).toBeNull()
  })

  it('redirects only once BOTH lookups have answered "not a curator"', async () => {
    const ws = deferred<Workspace>()
    apiGetProjectMock.mockResolvedValue(project('MEMBER'))
    apiGetWorkspaceMock.mockReturnValue(ws.promise)
    renderArea()

    expect(await screen.findByRole('heading', { name: 'Apollo' })).toBeTruthy()
    expect(screen.queryByText('Board page')).toBeNull()

    ws.resolve(workspace('MEMBER'))
    expect(await screen.findByText('Board page')).toBeTruthy()
  })

  // 0.13.0 review (security-officer L5): `PATCH …/projects/{id}` stopped echoing a
  // hardcoded MANAGER and now returns the caller's REAL project role, which the
  // Board tab writes straight into `['project', ws, p]` with setQueryData. For a
  // workspace OWNER/ADMIN who is not a project member that role is VIEWER — so a
  // guard reading the project role alone would throw the curator out of the very
  // page they just saved on. The predicate must stay the curator one.
  it('keeps a workspace OWNER in the area when the project role echoes back VIEWER', async () => {
    apiGetProjectMock.mockResolvedValue(project('VIEWER'))
    apiGetWorkspaceMock.mockResolvedValue(workspace('OWNER'))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/board`)

    expect(await screen.findByText('Board settings page')).toBeTruthy()
    expect(screen.queryByText('Board page')).toBeNull()
  })

  it('routes the Components tab to ProjectComponentsPage for a curator', async () => {
    apiGetProjectMock.mockResolvedValue(project('MEMBER'))
    apiGetWorkspaceMock.mockResolvedValue(workspace('ADMIN'))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/components`)

    expect(await screen.findByText('Components page')).toBeTruthy()
    expect(screen.queryByText('Board page')).toBeNull()
  })
})
