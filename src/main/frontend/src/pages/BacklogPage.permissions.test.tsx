import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BacklogPage from './BacklogPage'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import type {
  BacklogView, Issue, IssueType, PriorityOption, ProjectDelivery, Sprint, Status, User,
} from '../types'

/**
 * **HD-123 S5 — the backlog offers no gesture the server would refuse.**
 *
 * The sibling file (`BacklogPage.dnd.test.tsx`) proves the drag resolves the right
 * anchors; it runs as a project admin throughout, so it says nothing about who may
 * drag at all. This one varies only `myPermissions` and asserts the two gates the
 * page applies — `issue.rank` (reorder within a section) and `sprint.assign` (move
 * between sections) — from both sides.
 *
 * Two properties, and the second is the one worth the file:
 *
 *  1. the **control** is not rendered (no grip, no kebab, no "Move to" list); and
 *  2. the **gesture** is inert — a drag the actor may not make spends **no
 *     request**. A hidden control is not a permission, but a client that fires the
 *     request anyway turns every refusal into a 403 toast plus an optimistic row
 *     that jumps and snaps back, which is exactly the "nothing happens, twice"
 *     experience §14.1 is written against.
 *
 * The two grants are asserted **separately**, never as one "may plan" boolean:
 * `issue.rank` without `sprint.assign` is a real, shippable custom role (§6.5's
 * split is on the server, so the UI's must match it), and a page that collapsed
 * them would offer a sprint drop to somebody the API refuses.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

const SPRINT: Sprint = {
  id: 'sp1', name: 'Sprint 7', goal: null, state: 'ACTIVE', sequence: 7,
  startAt: '2026-08-10T09:00:00Z', endAt: '2026-08-24T09:00:00Z', completedAt: null,
  daysRemaining: 6, issueCount: 1, doneIssueCount: 0, points: 0, donePoints: 0,
  unestimatedCount: 1,
  createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-01T09:00:00Z',
}

let n = 0
function issue(title: string, sprint?: Sprint): Issue {
  n += 1
  return {
    id: `i${n}`, number: n, key: `PR-${n}`, title,
    type: TASK, status: TODO, priority: PRIORITY,
    reporter: { id: 'u-other', displayName: 'Other' },
    childCount: 0, doneChildCount: 0,
    sprint: sprint ? { id: sprint.id, name: sprint.name, state: sprint.state } : null,
    storyPoints: null,
    fields: [], version: 1,
    createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
  }
}

const S1 = issue('sprint one', SPRINT)
const B1 = issue('backlog one')
const B2 = issue('backlog two')

const EMPTY_STATS = {
  issueCount: 0, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 0,
}

const VIEW: BacklogView = {
  sprints: [{
    sprint: SPRINT, issues: [S1], truncated: false, totalAvailable: 1,
    stats: { ...EMPTY_STATS, issueCount: 1, unestimatedCount: 1 },
  }],
  backlog: {
    issues: [B1, B2], truncated: false, totalAvailable: 2,
    stats: { ...EMPTY_STATS, issueCount: 2, unestimatedCount: 2 },
  },
  sectionCap: 300,
}

const SCRUM_DELIVERY: ProjectDelivery = {
  board: 'SCRUM', releases: true, estimation: true, preset: 'CUSTOM',
}

/** The only thing that varies between these tests. */
let projectPermissions: string[] = PROJECT_ADMIN_PERMISSIONS

// Typed to accept the call's arguments (rather than `vi.fn(async () => B1)`), so
// the payload assertion below can index into `mock.calls` — with a no-arg
// signature the recorded call is the empty tuple and `calls[0][3]` does not
// type-check. `npx tsc -b` is the gate that says so; vitest runs it happily.
const apiRankIssueMock = vi.fn(async (..._args: unknown[]) => B1)

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetBacklogView: vi.fn(async () => structuredClone(VIEW)),
  apiRankIssue: (...a: unknown[]) => apiRankIssueMock(...(a as [])),
  apiListIssuesPaged: vi.fn(async () => ({
    content: [], page: 0, size: 300, totalElements: 0, totalPages: 0, hasNext: false,
  })),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: 'MEMBER', myPermissions: projectPermissions,
    delivery: SCRUM_DELIVERY, createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: 'MEMBER',
    myPermissions: WORKSPACE_ADMIN_PERMISSIONS, createdAt: '2026-01-01T00:00:00Z',
  })),
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  componentsApi: { list: vi.fn(async () => []), update: vi.fn() },
  versionsApi: { list: vi.fn(async () => []) },
}))

beforeAll(() => {
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
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
  projectPermissions = PROJECT_ADMIN_PERMISSIONS
  apiRankIssueMock.mockClear()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
  useUiStore.setState({ createIssueOpen: false, createIssuePreset: undefined })
})

function renderBacklog() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/backlog`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId/backlog" element={<BacklogPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function backlogReady() {
  await screen.findByText('Sprint 7')
  await screen.findByText(B1.title)
}

// ── the drag plumbing, as in BacklogPage.dnd.test.tsx ────────────────────────
function dataTransfer() {
  return { dropEffect: '', effectAllowed: '', setData: () => {}, getData: () => '' }
}

function fireDrag(el: HTMLElement, type: string, dt: object, clientY = 0) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientY })
  Object.defineProperty(event, 'dataTransfer', { value: dt })
  fireEvent(el, event)
}

function row(i: Issue): HTMLElement {
  const el = screen.getByText(i.key).closest('[draggable]')
  if (!el) throw new Error(`no row element for ${i.key}`)
  return el as HTMLElement
}

function sectionOf(i: Issue): HTMLElement {
  const el = row(i).closest('section')
  if (!el) throw new Error(`no section around ${i.key}`)
  return el
}

function drag(moved: Issue, target: Issue, where: 'above' | 'below' = 'below') {
  const dt = dataTransfer()
  fireDrag(row(moved), 'dragstart', dt)
  fireDrag(row(target), 'dragover', dt, where === 'below' ? 1 : 0)
  fireDrag(sectionOf(target), 'drop', dt)
}

/** Every row the page rendered as actually draggable. */
function draggableRows() {
  return [...document.querySelectorAll('[draggable="true"]')]
}

describe('BacklogPage permission gates (HD-123 S5)', () => {
  it('gives a full planner the grip, both kebab groups and a live create button', async () => {
    renderBacklog()
    await backlogReady()

    expect(draggableRows().length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: /Create issue/ })).toBeEnabled()

    await userEvent.click(screen.getByRole('button', { name: `Move ${B1.key}` }))
    expect(await screen.findByText('Rank')).toBeInTheDocument()
    expect(screen.getByText('Move to')).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Sprint 7' })).toBeInTheDocument()
  })

  it('offers a viewer no gesture at all, and still shows every row', async () => {
    projectPermissions = []
    renderBacklog()
    await backlogReady()

    // Rule B: the DATA is untouched — the backlog is fully readable.
    expect(screen.getByText(B1.title)).toBeInTheDocument()
    expect(screen.getByText(S1.title)).toBeInTheDocument()

    // …and every write affordance is gone or inert.
    expect(draggableRows()).toHaveLength(0)
    expect(screen.queryByRole('button', { name: `Move ${B1.key}` })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Create issue/ })).toBeDisabled()
  })

  it('lets `issue.rank` reorder within a section and refuses the sprint drop', async () => {
    // A real custom role: may groom the backlog, may not plan iterations.
    projectPermissions = ['issue.rank']
    renderBacklog()
    await backlogReady()

    // The kebab offers Rank and NOT "Move to" — the two grants are separate.
    await userEvent.click(screen.getByRole('button', { name: `Move ${B1.key}` }))
    expect(await screen.findByText('Rank')).toBeInTheDocument()
    expect(screen.queryByText('Move to')).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Sprint 7' })).not.toBeInTheDocument()
    await userEvent.keyboard('{Escape}')

    // A move INTO the sprint is not the actor's to make — and it spends no
    // request rather than firing one for the server to refuse. Asserted before
    // the permitted gesture, so the "no call" cannot be an artefact of the
    // refetch that a settled rank triggers.
    drag(B1, S1, 'below')
    await new Promise(r => setTimeout(r, 20))
    expect(apiRankIssueMock).not.toHaveBeenCalled()

    // A move WITHIN the backlog is.
    drag(B2, B1, 'above')
    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
  })

  it('lets `sprint.assign` move between sections and refuses the pure re-rank', async () => {
    // The mirror image, so neither grant can be standing in for the other.
    projectPermissions = ['sprint.assign']
    renderBacklog()
    await backlogReady()

    await userEvent.click(screen.getByRole('button', { name: `Move ${B1.key}` }))
    expect(await screen.findByText('Move to')).toBeInTheDocument()
    expect(screen.queryByText('Rank')).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Move to top' })).not.toBeInTheDocument()
    await userEvent.keyboard('{Escape}')

    // Reordering inside the backlog needs `issue.rank`, which this actor lacks.
    drag(B2, B1, 'above')
    await new Promise(r => setTimeout(r, 20))
    expect(apiRankIssueMock).not.toHaveBeenCalled()

    // …but dropping into the sprint is exactly what they hold.
    drag(B1, S1, 'below')
    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
    expect(apiRankIssueMock.mock.calls[0]?.[3]).toMatchObject({ sprintId: SPRINT.id })
  })
})
