import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import ProjectSettingsArea from './ProjectSettingsArea'
import {
  PROJECT_ADMIN_PERMISSIONS, PROJECT_CONTRIBUTOR_PERMISSIONS,
  PROJECT_CURATOR_BYPASS_PERMISSIONS, PROJECT_VIEWER_PERMISSIONS,
} from '../../test/permissions'
import type { Project } from '../../types'

// HD-31 changed this area's access guard, and a bad guard is a silent lockout:
// the server let a project MANAGER *or* a workspace OWNER/ADMIN curate a
// project's components, so the client-side UX guard had to admit exactly the
// same set — and, just as importantly, wait for both lookups before redirecting.
// The pre-HD-31 guard read only the project role and bounced anyone whose
// `myRole !== 'MANAGER'`, throwing a workspace admin out of a page the API
// happily serves them.
//
// HD-123 S5 removes the second lookup and the hand-written predicate together.
// The guard now calls `canOpenProjectSettings` — the same function the rail's
// Settings link and the command palette call — over the permission strings the
// server itself checks, and the workspace bypass arrives already folded into the
// project response. So the truth table below is expressed in PERMISSIONS, and
// the loading race is about one query rather than two.
//
// What is asserted here is the guard's truth table plus that race, not the tab
// markup.

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

function project(myPermissions: string[], myRole = 'MEMBER'): Project {
  return {
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Apollo', key: 'AP',
    archived: false, myRole, myPermissions, createdAt: '2026-08-10T09:00:00Z',
  }
}

const apiGetProjectMock = vi.fn<() => Promise<Project>>()

vi.mock('../../api', () => ({
  apiGetProject: () => apiGetProjectMock(),
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
vi.mock('./ProjectDeliverySettingsPage', () => ({ default: () => <div>Delivery settings page</div> }))

/** Where the router actually ended up — a redirect's destination, not just "something rendered". */
function LocationProbe() {
  return <div data-testid="path">{useLocation().pathname}</div>
}

function renderArea(initialPath = `/w/${WS_ID}/p/${PROJECT_ID}/settings`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <LocationProbe />
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
})

describe('ProjectSettingsArea — permission guard (HD-31 / HD-123 S5)', () => {
  it('admits a project admin and offers the Components tab', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_ADMIN_PERMISSIONS, 'MANAGER'))
    renderArea()

    expect(await screen.findByRole('heading', { name: 'Apollo' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Components' }).getAttribute('href'))
      .toBe(`/w/${WS_ID}/p/${PROJECT_ID}/settings/components`)
    expect(screen.queryByText('Board page')).toBeNull()
  })

  it('admits a workspace admin who holds only the curator bypass here', async () => {
    // The exact shape that used to be bounced, and the exact shape the server
    // sends: no project role of their own (`myRole` still reads VIEWER), plus
    // `project.curate.all`'s implied grants.
    apiGetProjectMock.mockResolvedValue(project(PROJECT_CURATOR_BYPASS_PERMISSIONS, 'VIEWER'))
    renderArea()

    expect(await screen.findByRole('heading', { name: 'Apollo' })).toBeTruthy()
    expect(screen.queryByText('Board page')).toBeNull()
  })

  it('admits a taxonomy-only grant — the door is a disjunction, not one key', async () => {
    apiGetProjectMock.mockResolvedValue(project(['project.taxonomy.manage']))
    renderArea()

    expect(await screen.findByRole('heading', { name: 'Apollo' })).toBeTruthy()
    expect(screen.queryByText('Board page')).toBeNull()
  })

  it('redirects a contributor', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_CONTRIBUTOR_PERMISSIONS))
    renderArea()

    expect(await screen.findByText('Board page')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Components' })).toBeNull()
  })

  it('redirects a viewer', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_VIEWER_PERMISSIONS, 'VIEWER'))
    renderArea()

    expect(await screen.findByText('Board page')).toBeTruthy()
  })

  it('does not redirect while the permissions are still loading', async () => {
    // The race the guard has to survive. `can(...)` is false while the answer is
    // unknown — deliberately, so a gate never widens on missing data — which
    // means "denied" and "not asked yet" are the same boolean. Redirecting on it
    // would bounce a legitimate curator out of their own settings page on a slow
    // connection, non-deterministically. `isLoading` is what tells the two apart.
    const p = deferred<Project>()
    apiGetProjectMock.mockReturnValue(p.promise)
    renderArea()

    // Nothing has navigated away while the answer is outstanding.
    expect(screen.queryByText('Board page')).toBeNull()

    p.resolve(project(PROJECT_CURATOR_BYPASS_PERMISSIONS, 'VIEWER'))
    await waitFor(() => expect(screen.getByRole('link', { name: 'Components' })).toBeTruthy())
    expect(screen.queryByText('Board page')).toBeNull()
  })

  it('redirects once the answer actually arrives and says no', async () => {
    const p = deferred<Project>()
    apiGetProjectMock.mockReturnValue(p.promise)
    renderArea()

    expect(screen.queryByText('Board page')).toBeNull()

    p.resolve(project(PROJECT_CONTRIBUTOR_PERMISSIONS))
    expect(await screen.findByText('Board page')).toBeTruthy()
  })

  // 0.13.0 review (security-officer L5): `PATCH …/projects/{id}` returns the
  // caller's REAL project role, which the Delivery tab writes straight into
  // `['project', ws, p]` with setQueryData. For a workspace OWNER/ADMIN who is
  // not a project member that role is VIEWER — so a guard reading the role would
  // throw the curator out of the very page they just saved on. Reading
  // `myPermissions` instead makes that failure unexpressible: the same response
  // that says VIEWER carries the curator grants.
  it('keeps a workspace admin in the area when the project role echoes back VIEWER', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_CURATOR_BYPASS_PERMISSIONS, 'VIEWER'))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/delivery`)

    expect(await screen.findByText('Delivery settings page')).toBeTruthy()
    expect(screen.queryByText('Board page')).toBeNull()
  })

  // HD-106 renamed the tab Board → Delivery. The old path is in bookmarks, in
  // links pasted into tickets, and in the sentence the Scrum blurb has always
  // carried — so it must REDIRECT, not fall through to the area's catch-all
  // (which would drop the user on Taxonomy with no explanation).
  it('redirects the retired /settings/board path to the Delivery tab', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_ADMIN_PERMISSIONS, 'MANAGER'))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/board`)

    expect(await screen.findByText('Delivery settings page')).toBeTruthy()
    // The tab strip points at the new path, and there is no "Board" tab left.
    expect(screen.getByRole('link', { name: 'Delivery' }).getAttribute('href'))
      .toBe(`/w/${WS_ID}/p/${PROJECT_ID}/settings/delivery`)
    expect(screen.queryByRole('link', { name: 'Board' })).toBeNull()
  })

  // The destination, not merely "something rendered". Falling through to the
  // area's catch-all would ALSO render a page — Taxonomy — and an old bookmark
  // silently landing on an unrelated tab is the failure this route prevents.
  it('lands the old /settings/board URL on /settings/delivery itself', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_ADMIN_PERMISSIONS, 'MANAGER'))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/board`)

    expect(await screen.findByText('Delivery settings page')).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('path'))
      .toHaveTextContent(`/w/${WS_ID}/p/${PROJECT_ID}/settings/delivery`))
    expect(screen.queryByText('Bindings page')).toBeNull()
  })

  // The other half of the same claim: the catch-all still exists and still goes
  // to Taxonomy, so the test above is proving a dedicated route rather than a
  // coincidence of ordering.
  it('still sends an unknown settings path to Taxonomy', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_ADMIN_PERMISSIONS, 'MANAGER'))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/no-such-tab`)

    expect(await screen.findByText('Bindings page')).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('path'))
      .toHaveTextContent(`/w/${WS_ID}/p/${PROJECT_ID}/settings`))
    expect(screen.queryByText('Delivery settings page')).toBeNull()
  })

  it('routes the Delivery tab to the delivery page', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_ADMIN_PERMISSIONS, 'MANAGER'))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/delivery`)

    expect(await screen.findByText('Delivery settings page')).toBeTruthy()
    expect(screen.queryByText('Bindings page')).toBeNull()
  })

  it('routes the Components tab to ProjectComponentsPage for a curator', async () => {
    apiGetProjectMock.mockResolvedValue(project(PROJECT_CURATOR_BYPASS_PERMISSIONS))
    renderArea(`/w/${WS_ID}/p/${PROJECT_ID}/settings/components`)

    expect(await screen.findByText('Components page')).toBeTruthy()
    expect(screen.queryByText('Board page')).toBeNull()
  })
})
