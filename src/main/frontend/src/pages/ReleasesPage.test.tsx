import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ReleasesPage from './ReleasesPage'
import { apiUpdateProject, versionsApi } from '../api'
import type { Version } from '../types'

/**
 * HD-32 — the Releases page is the story's deliverable, so its load-bearing
 * behaviours get direct tests rather than being implied by the API client:
 *
 *  1. progress renders as `done / total` (never a bare percentage), and a version
 *     with zero fix-linked issues renders "No issues yet" instead of a 0% bar;
 *  2. the released/unreleased pill reflects the lifecycle;
 *  3. the kebab offers "Release…" only while unreleased and "Un-release" only
 *     once released (releasing twice is a 409 server-side);
 *  4. clicking the issue count navigates to the shared HQL search results page
 *     pre-filled with `project = "KEY" AND fixVersion = "…"` — we do NOT build a
 *     second list view;
 *  5. a non-curator (plain project member, plain workspace member) gets the
 *     read-only page: no "New version", no kebab.
 */

const UNRELEASED: Version = {
  id: 'v1', name: '2.4.0', released: false, archived: false,
  releaseDate: '2026-09-01',
  issueCount: 24, doneIssueCount: 18, affectsIssueCount: 0,
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
}
const RELEASED: Version = {
  id: 'v2', name: '2.3.1', released: true, archived: false,
  releaseDate: '2026-07-15', releasedAt: '2026-07-15T10:00:00Z',
  issueCount: 0, doneIssueCount: 0, affectsIssueCount: 3,
  createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-15T10:00:00Z',
}
/** The only legal "move unresolved to" target: unreleased and not archived. */
const NEXT: Version = {
  id: 'v3', name: '2.5.0', released: false, archived: false,
  issueCount: 5, doneIssueCount: 1, affectsIssueCount: 0,
  createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
}

/**
 * Archived and unreleased. Excluded from BOTH target lists — planning work into an
 * archived version and back-porting links onto one are equally meaningless.
 */
const ARCHIVED: Version = {
  id: 'v4', name: '0.9.0', released: false, archived: true,
  issueCount: 0, doneIssueCount: 0, affectsIssueCount: 0,
  createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z',
}

const mockState = vi.hoisted(() => ({
  versions: [] as unknown[],
  projectRole: 'MANAGER' as 'MANAGER' | 'MEMBER',
  workspaceRole: 'MEMBER' as 'OWNER' | 'ADMIN' | 'MEMBER',
  // HD-102: `undefined` = a response with no `delivery` at all, which `deliveryOf`
  // upgrades per §7 (releases stay ON for every pre-existing project) — the state
  // every test outside the off-state block below describes.
  delivery: undefined as Record<string, unknown> | undefined,
}))

vi.mock('../api', () => ({
  ApiResponseError: class ApiResponseError extends Error { status = 0; detail = '' },
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Payments', key: 'PAY',
    archived: false, myRole: mockState.projectRole, createdAt: '2026-01-01T00:00:00Z',
    ...(mockState.delivery ? { delivery: mockState.delivery } : {}),
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: 'w1', name: 'WS', slug: 'ws', myRole: mockState.workspaceRole, createdAt: '2026-01-01T00:00:00Z',
  })),
  // HD-104: the only write the off-state affordance performs — a PARTIAL delivery
  // PATCH that never carries the derived `preset`.
  apiUpdateProject: vi.fn(async (_ws: string, _p: string, payload: Record<string, unknown>) => {
    mockState.delivery = { board: 'KANBAN', estimation: false, preset: 'RELEASES',
      ...(mockState.delivery ?? {}), ...(payload.delivery as Record<string, unknown>) }
    return {
      id: 'p1', workspaceId: 'w1', name: 'Payments', key: 'PAY',
      archived: false, myRole: mockState.projectRole, createdAt: '2026-01-01T00:00:00Z',
      delivery: mockState.delivery,
    }
  }),
  versionsApi: {
    list: vi.fn(async () => mockState.versions),
    usage: vi.fn(async () => ({ fixIssueCount: 24, affectsIssueCount: 0, unresolvedFixIssueCount: 6 })),
    create: vi.fn(),
    update: vi.fn(),
    release: vi.fn(),
    unrelease: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
    remove: vi.fn(),
  },
}))

beforeEach(() => {
  mockState.versions = [UNRELEASED, NEXT, RELEASED]
  mockState.projectRole = 'MANAGER'
  mockState.workspaceRole = 'MEMBER'
  mockState.delivery = undefined
  vi.mocked(apiUpdateProject).mockClear()
  vi.mocked(versionsApi.create).mockClear()
})

/** Echoes the current URL so the search-navigation assertion can read it. */
function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname + loc.search}</div>
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/w/w1/p/p1/releases']}>
        <LocationProbe />
        <Routes>
          <Route path="/w/:wsId/p/:projectId/releases" element={<ReleasesPage />} />
          <Route path="/w/:wsId/search" element={<div>search results</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReleasesPage — per-version progress', () => {
  it('renders the numeric done/total for a version with issues', async () => {
    renderPage()
    expect(await screen.findByText('18 / 24')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '18 of 24 issues done' }))
      .toHaveAttribute('aria-valuenow', '75')
  })

  it('renders "No issues yet" instead of a 0% bar when nothing links to it', async () => {
    renderPage()
    // 2.3.1 has no fix-linked issues at all…
    expect(await screen.findByText('No issues yet')).toBeInTheDocument()
    // …so only the two versions that DO have issues draw a bar.
    expect(screen.getAllByRole('progressbar')).toHaveLength(2)
  })

  it('shows the lifecycle pill for both states', async () => {
    renderPage()
    expect(await screen.findAllByText('Unreleased')).toHaveLength(2)
    expect(screen.getByText('Released')).toBeInTheDocument()
  })
})

describe('ReleasesPage — lifecycle actions', () => {
  it('offers "Release…" while unreleased and "Un-release" once released', async () => {
    renderPage()
    await userEvent.click(await screen.findByLabelText('Actions for 2.4.0'))
    expect(screen.getByRole('menuitem', { name: 'Release…' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Un-release' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByLabelText('Actions for 2.3.1'))
    expect(screen.getByRole('menuitem', { name: 'Un-release' })).toBeInTheDocument()
  })

  it('offers the "move N unresolved issues to" select only when unresolved work exists', async () => {
    renderPage()
    await userEvent.click(await screen.findByLabelText('Actions for 2.4.0'))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Release…' }))

    // 6 unresolved (from the mocked usage endpoint) → the move control appears.
    const select = await screen.findByLabelText('Move 6 unresolved issues to')
    // …and only a legal target is offered: another UNRELEASED, non-archived
    // version of the same project — never the already-released 2.3.1, and never
    // the version being released.
    await userEvent.click(select)
    expect(await screen.findByRole('option', { name: '2.5.0' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: '2.3.1' })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: '2.4.0' })).not.toBeInTheDocument()
  })
})

/**
 * The asymmetry between the two target lists — `moveTargets` excludes released AND
 * archived, `remapTargets` excludes archived ONLY — is the kind of thing a later
 * "these two helpers are the same, let's merge them" refactor destroys silently:
 * both lists would still look plausible, and the server would only reject one of the
 * two directions (a released `moveUnresolvedToVersionId` is a 422, a released
 * `remapToId` is legal). The asymmetry is deliberate and semantic:
 *
 *  - **release → move unresolved work**: the work is still open, so parking it on an
 *    already-shipped release is never what the operator meant → released targets out;
 *  - **delete → remap the links**: "that work actually shipped in 2.3.1" is normal
 *    back-porting bookkeeping → released targets stay in, and are labelled as such.
 *
 * Archived versions are out of both: they are out of every picker by definition.
 */
describe('ReleasesPage — move vs remap target lists are deliberately different', () => {
  beforeEach(() => {
    mockState.versions = [UNRELEASED, NEXT, RELEASED, ARCHIVED]
  })

  it('release: the move target list excludes released AND archived versions', async () => {
    renderPage()
    await userEvent.click(await screen.findByLabelText('Actions for 2.4.0'))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Release…' }))

    const select = await screen.findByLabelText('Move 6 unresolved issues to')
    await userEvent.click(select)
    // The only legal target: another UNRELEASED, non-archived version.
    expect(await screen.findByRole('option', { name: '2.5.0' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /2\.3\.1/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /0\.9\.0/ })).not.toBeInTheDocument()
    // …and never the version being released. Exact name: the "Leave them on 2.4.0"
    // no-op placeholder legitimately mentions it and is not a target.
    expect(screen.queryByRole('option', { name: '2.4.0' })).not.toBeInTheDocument()
  })

  it('delete: the remap target list keeps released versions and drops archived ones', async () => {
    renderPage()
    await userEvent.click(await screen.findByLabelText('Actions for 2.4.0'))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Delete' }))

    const select = await screen.findByLabelText('Move the links to')
    await userEvent.click(select)
    expect(await screen.findByRole('option', { name: '2.5.0' })).toBeInTheDocument()
    // Released IS offered here — and labelled, so the choice is informed.
    expect(screen.getByRole('option', { name: '2.3.1 (released)' })).toBeInTheDocument()
    // Archived is not, in either dialog.
    expect(screen.queryByRole('option', { name: /0\.9\.0/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: '2.4.0' })).not.toBeInTheDocument()
  })
})

describe('ReleasesPage — issue count links into HQL search', () => {
  it('navigates to the shared search page pre-filled with project + fixVersion', async () => {
    renderPage()
    await userEvent.click(await screen.findByTitle('Show the 24 issues with this fix version'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/w/w1/search'))
    const url = screen.getByTestId('loc').textContent ?? ''
    const q = new URLSearchParams(url.slice(url.indexOf('?'))).get('q')
    expect(q).toBe('project = "PAY" AND fixVersion = "2.4.0"')
    expect(screen.getByText('search results')).toBeInTheDocument()
  })
})

describe('ReleasesPage — curation guard', () => {
  it('hides every write affordance from a plain member', async () => {
    mockState.projectRole = 'MEMBER'
    mockState.workspaceRole = 'MEMBER'
    renderPage()

    // The page still reads…
    expect(await screen.findByText('18 / 24')).toBeInTheDocument()
    // …but offers nothing to change.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /New version/ })).not.toBeInTheDocument())
    expect(screen.queryByLabelText('Actions for 2.4.0')).not.toBeInTheDocument()
  })

  it('lets a workspace ADMIN who is not a project MANAGER curate', async () => {
    mockState.projectRole = 'MEMBER'
    mockState.workspaceRole = 'ADMIN'
    renderPage()

    expect(await screen.findByLabelText('Actions for 2.4.0')).toBeInTheDocument()
  })
})

/**
 * HD-104 (S3) — the releases half of **Rule C** (HD-102 §5.3). The rail item is
 * gone while the capability is off, so this page is the only place the capability
 * can be turned back on, and the route deliberately still resolves.
 *
 * The same three properties as the Backlog affordance: a curator-only enabling
 * control, a reversibility notice that is copy rather than a tooltip, and a
 * PARTIAL `delivery` PATCH that never carries the derived `preset`. Plus Rule B
 * (§5.2) on top: turning releases off hides the lifecycle CONTROLS and nothing
 * else — every version, its progress and its issue links keep rendering.
 */
describe('ReleasesPage — the off-state (HD-104, Rule C)', () => {
  const RELEASES_OFF = { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' }

  it('offers a curator the enabling action, and says it is reversible in the copy', async () => {
    mockState.delivery = RELEASES_OFF
    renderPage()

    expect(await screen.findByRole('button', { name: /turn on releases/i })).toBeInTheDocument()
    expect(screen.getByText(/turn them off again in Project settings/i)).toBeInTheDocument()
    // Rule B: the versions themselves are untouched — nothing was deleted.
    expect(screen.getByText('18 / 24')).toBeInTheDocument()
    // …but their lifecycle controls are gone while the capability is off.
    expect(screen.queryByLabelText('Actions for 2.4.0')).not.toBeInTheDocument()
  })

  it('turns releases on with a partial delivery PATCH that omits `preset`', async () => {
    mockState.delivery = RELEASES_OFF
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /turn on releases/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(apiUpdateProject).mock.calls[0][2] as { delivery: object }
    expect(payload.delivery).toEqual({ releases: true })
    expect(payload.delivery).not.toHaveProperty('preset')
    // The page re-reads the fresh project and the controls come back.
    expect(await screen.findByLabelText('Actions for 2.4.0')).toBeInTheDocument()
  })

  it('creates the first version and turns releases on in one gesture', async () => {
    mockState.delivery = RELEASES_OFF
    mockState.versions = []
    renderPage()

    await userEvent.click((await screen.findAllByRole('button', { name: /New version/ }))[0])
    // The notice is in the dialog, before the click that changes anything (the
    // off-state panel behind it carries the same sentence, hence two).
    expect((await screen.findAllByText(/This turns on releases for this project/i)).length)
      .toBeGreaterThanOrEqual(2)

    await userEvent.type(screen.getByLabelText('Name'), '3.0.0')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect((vi.mocked(apiUpdateProject).mock.calls[0][2] as { delivery: object }).delivery)
      .toEqual({ releases: true })
    await waitFor(() => expect(versionsApi.create).toHaveBeenCalledTimes(1))
  })

  it('offers a plain member no enabling action at all', async () => {
    mockState.delivery = RELEASES_OFF
    mockState.projectRole = 'MEMBER'
    mockState.workspaceRole = 'MEMBER'
    renderPage()

    expect(await screen.findByText('18 / 24')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /turn on releases/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /New version/ })).not.toBeInTheDocument()
    // …but is told why the page is inert, rather than left guessing.
    expect(screen.getByText(/Releases are off for this project/i)).toBeInTheDocument()
  })
})
