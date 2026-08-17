import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BacklogPage from './BacklogPage'
import BoardPage from './BoardPage'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { apiListIssues, apiUpdateProject, sprintsApi } from '../api'
import type {
  BacklogView, Issue, IssueType, PriorityOption, ProjectDelivery, Sprint, Status, User,
} from '../types'

/**
 * HD-104 (S3) — **Rule C, the bootstrap invariant** (HD-102 §5.3): every
 * capability has an enabling affordance visible *while it is off*, and first use
 * turns it on with a reversible notice.
 *
 * These are not tests of a button. They are the production bug in its exact
 * shape: after 0.13.0 every pre-existing project defaulted to Kanban, the Backlog
 * hid both sprint-creation entry points until a sprint existed, and the only
 * route to a first sprint was a settings tab nothing pointed at. So the central
 * case here is stated the way the owner reported it —
 *
 *   *a Kanban project with zero sprints, viewed by a curator, offers a route to a
 *   running sprint without visiting settings.*
 *
 * The three properties asserted alongside it are the ones that keep the fix from
 * decaying into "one more button on one more screen":
 *
 *  • the enabling write is a **partial** `PATCH { delivery: { … } }` that never
 *    carries `preset` (S1 answers 400 to a body containing it, so a client can
 *    never echo back the object it read from a GET);
 *  • the reversibility notice is **copy**, discoverable without hovering;
 *  • a **plain member** sees no affordance and no sprint vocabulary at all — the
 *    capability is curator-owned, and a member of a Kanban project should never
 *    have to learn the word "sprint" to use their backlog.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

let n = 0
function issue(title: string): Issue {
  n += 1
  return {
    id: `i${n}`, number: n, key: `PR-${n}`, title,
    type: TASK, status: TODO, priority: PRIORITY,
    reporter: { id: 'u-other', displayName: 'Other' },
    childCount: 0, doneChildCount: 0,
    sprint: null, storyPoints: null,
    fields: [], version: 1,
    createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
  }
}

const B1 = issue('ranked one')
const B2 = issue('ranked two')

/** The state the bug lives in: a project with NO sprint rows whatsoever. */
const NO_SPRINTS: BacklogView = {
  sprints: [],
  backlog: {
    issues: [B1, B2], truncated: false, totalAvailable: 2,
    stats: { issueCount: 2, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 2 },
  },
  sectionCap: 300,
}

const CREATED_SPRINT: Sprint = {
  id: 'sp-new', name: 'Sprint 1', goal: null, state: 'FUTURE', sequence: 1,
  startAt: null, endAt: null, completedAt: null, daysRemaining: null,
  issueCount: 0, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 0,
  createdAt: '2026-08-17T09:00:00Z', updatedAt: '2026-08-17T09:00:00Z',
}
const STARTED_SPRINT: Sprint = { ...CREATED_SPRINT, state: 'ACTIVE', daysRemaining: 14 }

const KANBAN: ProjectDelivery = {
  board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN',
}
const SCRUM: ProjectDelivery = {
  board: 'SCRUM', releases: false, estimation: true, preset: 'SCRUM',
}

const state = vi.hoisted(() => ({
  delivery: { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' } as ProjectDelivery,
  projectRole: 'MANAGER' as 'MANAGER' | 'MEMBER',
  workspaceRole: 'OWNER' as 'OWNER' | 'ADMIN' | 'MEMBER',
  sprints: [] as Sprint[],
  /**
   * The enabling PATCH fails (403 after a role change, 409 on an archived
   * project). Exercised to pin the ORDER claim: capability first, sprint second,
   * so a failure can never leave a sprint on a project that declares it does not
   * plan in sprints.
   */
  patchFails: false,
  /** Every write this gesture performed, in the order it performed them. */
  calls: [] as string[],
}))

// The real module is kept and only the network functions these two pages touch
// are replaced — a hand-listed façade would break on any unrelated new export
// (both pages pull in the issue drawer, which imports a dozen more).
vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: state.projectRole, delivery: state.delivery,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: state.workspaceRole,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiUpdateProject: vi.fn(async (_ws: string, _p: string, payload: Record<string, unknown>) => {
    state.calls.push('patch')
    if (state.patchFails) throw new Error('You are no longer a project admin')
    const patch = (payload.delivery ?? {}) as Partial<ProjectDelivery>
    state.delivery = { ...state.delivery, ...patch }
    return {
      id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
      archived: false, myRole: state.projectRole, delivery: state.delivery,
      createdAt: '2026-01-01T00:00:00Z',
    }
  }),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  // The planning view follows the sprints that actually exist, so the walk from
  // "no sprints at all" to a RUNNING one can be driven end to end through the UI
  // instead of being asserted one call at a time.
  apiGetBacklogView: vi.fn(async () => ({
    ...structuredClone(NO_SPRINTS),
    sprints: state.sprints.map(s => ({
      sprint: s,
      issues: [], truncated: false, totalAvailable: 0,
      stats: { issueCount: 0, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 0 },
    })),
  })),
  apiListIssuesPaged: vi.fn(async () => ({
    content: [], page: 0, size: 300, totalElements: 0, totalPages: 0, hasNext: false,
  })),
  apiListIssues: vi.fn(async () => ({
    issues: [B1, B2], truncated: false, totalAvailable: 2, cap: 100,
  })),
  apiRankIssue: vi.fn(),
  sprintsApi: {
    // Honours the `state` filter the way the endpoint does — the ACTIVE slice and
    // the open (ACTIVE+FUTURE) slice are different questions, and the board's
    // empty state exists precisely when the first is empty and the second is not.
    list: vi.fn(async (_ws: string, _p: string, opts?: { state?: string | string[] }) => {
      const want = opts?.state === undefined ? undefined
        : Array.isArray(opts.state) ? opts.state : [opts.state]
      const content = want ? state.sprints.filter(s => want.includes(s.state)) : state.sprints
      return {
        content, page: 0, size: 200,
        totalElements: content.length, totalPages: 1, hasNext: false,
      }
    }),
    create: vi.fn(async () => {
      state.calls.push('create')
      state.sprints = [...state.sprints, CREATED_SPRINT]
      return CREATED_SPRINT
    }),
    start: vi.fn(async () => {
      state.calls.push('start')
      state.sprints = state.sprints.map(s => (s.id === CREATED_SPRINT.id ? STARTED_SPRINT : s))
      return STARTED_SPRINT
    }),
    get: vi.fn(async () => CREATED_SPRINT),
  },
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
  state.delivery = KANBAN
  state.projectRole = 'MANAGER'
  state.workspaceRole = 'OWNER'
  state.sprints = []
  state.patchFails = false
  state.calls = []
  vi.mocked(apiUpdateProject).mockClear()
  vi.mocked(apiListIssues).mockClear()
  vi.mocked(sprintsApi.create).mockClear()
  vi.mocked(sprintsApi.start).mockClear()
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

function renderBoard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/board`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId/board" element={<BoardPage />} />
          <Route path="/w/:wsId/p/:projectId/backlog" element={<div>backlog page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The delivery body of the one enabling PATCH this gesture sent. */
function lastDeliveryPatch(): Record<string, unknown> {
  const calls = vi.mocked(apiUpdateProject).mock.calls
  expect(calls.length).toBeGreaterThan(0)
  const payload = calls[calls.length - 1][2] as { delivery?: Record<string, unknown> }
  return payload.delivery ?? {}
}

describe('Backlog — a Kanban project’s route to its first sprint (HD-104 Rule C)', () => {
  it('offers a curator the affordance with ZERO sprints and iterations off', async () => {
    renderBacklog()
    await screen.findByText(B1.title)

    // The affordance is visible while the capability is OFF — the whole invariant.
    expect((await screen.findAllByRole('button', { name: /plan in sprints/i })).length)
      .toBeGreaterThan(0)
    // …and it says, as copy rather than as a tooltip, what it will do and that it
    // can be undone.
    expect(screen.getByText(/turn it off again in project settings/i)).toBeInTheDocument()
  })

  it('turns sprint planning on and creates the sprint in ONE gesture, never touching settings', async () => {
    renderBacklog()
    await screen.findByText(B1.title)

    await userEvent.click((await screen.findAllByRole('button', { name: /plan in sprints/i }))[0])

    // The dialog repeats the reversible notice — the user decides with it on
    // screen, not after the fact (the off-state panel behind it carries the same
    // sentence, hence two).
    expect((await screen.findAllByText(/This turns on sprint planning for this project/i)))
      .toHaveLength(2)

    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    // Partial per member: the board flips and the other two capabilities are not
    // even mentioned…
    expect(lastDeliveryPatch()).toEqual({ board: 'SCRUM' })
    // …and `preset` is never echoed back (S1 answers 400 to a body carrying it).
    expect(lastDeliveryPatch()).not.toHaveProperty('preset')
    // The same gesture created the sprint — enabling alone would just be a second
    // dead end one step further along.
    await waitFor(() => expect(sprintsApi.create).toHaveBeenCalledTimes(1))

    // …and the user is told what changed, with the way back.
    expect(await screen.findByText(/Sprint planning is on/i)).toBeInTheDocument()
  })

  it('walks from ZERO sprints to a RUNNING one without ever visiting settings', async () => {
    // The production bug in full: a Kanban project, no sprint rows, a curator —
    // and the destination is a *running* sprint, not merely a created one. The
    // previous test stops at the first write; this one drives the whole route the
    // owner reported as missing, through the UI only.
    renderBacklog()
    await screen.findByText(B1.title)

    await userEvent.click((await screen.findAllByRole('button', { name: /plan in sprints/i }))[0])
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(await screen.findByText(/Sprint planning is on/i)).toBeInTheDocument()

    // The section the project just gained is the one that starts the sprint —
    // enabling the capability has to LEAD somewhere, or it is a second dead end.
    await userEvent.click(await screen.findByRole('button', { name: 'Start sprint' }))
    const dialog = await screen.findByRole('dialog', { name: /Start “Sprint 1”/ })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Start sprint' }))

    await waitFor(() => expect(sprintsApi.start).toHaveBeenCalledTimes(1))
    expect(vi.mocked(sprintsApi.start).mock.calls[0][2]).toBe(CREATED_SPRINT.id)

    // One delivery write, first, and only the board — the order is the invariant,
    // not just the set of calls.
    expect(state.calls).toEqual(['patch', 'create', 'start'])
    expect(lastDeliveryPatch()).toEqual({ board: 'SCRUM' })
    // The body the SERVER sees: `toEqual` treats an explicit `undefined` as
    // absent, `JSON.stringify` drops it — a key that reaches the wire is a 400.
    const wire = JSON.stringify(vi.mocked(apiUpdateProject).mock.calls[0][2])
    expect(wire).toBe('{"delivery":{"board":"SCRUM"}}')
    expect(wire).not.toContain('preset')
  })

  it('creates NO sprint when the enabling PATCH fails — never strands one', async () => {
    // The order claim is only worth anything at its failure edge. A sprint
    // created against a project that still says it does not plan in sprints would
    // be visible-but-read-only under Rule B: the exact shape of the dead end.
    state.patchFails = true
    renderBacklog()
    await screen.findByText(B1.title)

    await userEvent.click((await screen.findAllByRole('button', { name: /plan in sprints/i }))[0])
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/no longer a project admin/i)).toBeInTheDocument()
    // Nothing was created, and nothing was started.
    expect(sprintsApi.create).not.toHaveBeenCalled()
    expect(sprintsApi.start).not.toHaveBeenCalled()
    expect(state.calls).toEqual(['patch'])
    // The project is unchanged, so the affordance is still the one that enables.
    expect(screen.getAllByRole('button', { name: /plan in sprints/i }).length).toBeGreaterThan(0)
  })

  it('does not send a delivery PATCH when the project already plans in sprints', async () => {
    state.delivery = SCRUM
    renderBacklog()
    await screen.findByText(B1.title)

    await userEvent.click(screen.getByRole('button', { name: /new sprint/i }))
    expect(screen.queryByText(/This turns on sprint planning/i)).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(sprintsApi.create).toHaveBeenCalledTimes(1))
    expect(apiUpdateProject).not.toHaveBeenCalled()
  })

  it('shows a plain member no affordance and no sprint vocabulary at all', async () => {
    state.projectRole = 'MEMBER'
    state.workspaceRole = 'MEMBER'
    renderBacklog()
    await screen.findByText(B1.title)

    expect(screen.queryByRole('button', { name: /plan in sprints/i })).toBeNull()
    // The capability is curator-owned: a member of a Kanban project never has to
    // learn the word to use their backlog.
    expect(screen.queryByText(/sprint/i)).toBeNull()
  })
})

describe('Board — the "no active sprint" empty state is actionable (HD-102 §6)', () => {
  beforeEach(() => { state.delivery = SCRUM })

  it('gets a curator from no sprint at all to a RUNNING one without leaving the board', async () => {
    renderBoard()

    await userEvent.click(await screen.findByRole('button', { name: /create & start a sprint/i }))
    // One dialog, both steps.
    expect(await screen.findByText('Create and start a sprint')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Create & start' }))

    await waitFor(() => expect(sprintsApi.create).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(sprintsApi.start).toHaveBeenCalledTimes(1))
    expect(vi.mocked(sprintsApi.start).mock.calls[0][2]).toBe(CREATED_SPRINT.id)
    // The board is already scoped to sprints, so nothing about the project changed.
    expect(apiUpdateProject).not.toHaveBeenCalled()
  })

  it('lets ANY member drop the sprint scoping locally, without changing the project', async () => {
    state.projectRole = 'MEMBER'
    state.workspaceRole = 'MEMBER'
    renderBoard()

    // No curator affordance…
    expect(await screen.findByText('No active sprint')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /create & start a sprint/i })).toBeNull()
    // …but the escape hatch is for everyone, and it is a VIEW, not a setting.
    vi.mocked(apiListIssues).mockClear()

    await userEvent.click(screen.getByRole('button', { name: /show all issues/i }))

    await waitFor(() => expect(apiListIssues).toHaveBeenCalledTimes(1))
    expect(vi.mocked(apiListIssues).mock.calls[0][2]).toMatchObject({ sprintId: undefined })
    expect(await screen.findByText(B1.title)).toBeInTheDocument()
    expect(apiUpdateProject).not.toHaveBeenCalled()

    // …and it is reversible on the spot.
    await userEvent.click(screen.getByRole('button', { name: /back to the sprint board/i }))
    expect(await screen.findByText('No active sprint')).toBeInTheDocument()
  })

  it('never shows the empty state — or any sprint vocabulary — on a KANBAN board', async () => {
    // The mirror image of the dead end, and the reason Rule C is not simply
    // "always show the sprint controls": a continuous-flow team must not be made
    // to read about sprints. The board is scoped to the whole project, so the
    // empty state has nothing to be empty about.
    state.delivery = KANBAN
    renderBoard()

    expect(await screen.findByText(B1.title)).toBeInTheDocument()
    expect(screen.queryByText('No active sprint')).toBeNull()
    expect(screen.queryByRole('button', { name: /create & start a sprint/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /show all issues/i })).toBeNull()
  })

  it('offers to start the sprint that is already planned, and to create another', async () => {
    state.sprints = [CREATED_SPRINT]
    renderBoard()

    expect(await screen.findByRole('button', { name: /start “Sprint 1”/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create & start a new one instead/i }))
      .toBeInTheDocument()
  })
})
