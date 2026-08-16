import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BacklogPage from './BacklogPage'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import type {
  BacklogView, Issue, IssueType, PriorityOption, Sprint, Status, User,
} from '../types'

/**
 * The 0.13.0 review's two backlog findings, at the level a user meets them.
 *
 * 1. **"Move every issue to →" must chunk by the SERVER's bulk cap**
 *    (`BacklogView.bulkMoveCap`, `app.agile.max-issues-per-bulk-move`), not by the
 *    size of the rendered section (`sectionCap`). Those are independent properties
 *    and the stock defaults disagree (100 vs 300), so an unchunked "move all" of a
 *    large sprint is a guaranteed `400 "At most 100 issues per request"` — and the
 *    "move all to Backlog" fan-out was an unbounded `Promise.all` of up to 300
 *    simultaneous DELETEs. A partial run must report what actually moved.
 *
 * 2. **A COMPLETED sprint's membership is frozen** — the server now refuses to take
 *    an issue out of one, so the UI must stop offering the gesture (no bulk move in
 *    the section header, no "Move to →" in a row's kebab).
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

function sprint(id: string, name: string, state: Sprint['state'], sequence: number): Sprint {
  return {
    id, name, goal: null, state, sequence,
    startAt: null, endAt: null, completedAt: state === 'COMPLETED' ? '2026-08-14T09:00:00Z' : null,
    daysRemaining: null, issueCount: 0, doneIssueCount: 0, points: 0, donePoints: 0,
    unestimatedCount: 0,
    createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-01T09:00:00Z',
  }
}

const ACTIVE = sprint('sp1', 'Sprint 7', 'ACTIVE', 7)
const FUTURE = sprint('sp2', 'Sprint 8', 'FUTURE', 8)

let n = 0
function issue(title: string, s?: Sprint): Issue {
  n += 1
  return {
    id: `i${n}`, number: n, key: `PR-${n}`, title,
    type: TASK, status: TODO, priority: PRIORITY,
    reporter: { id: 'u-other', displayName: 'Other' },
    childCount: 0, doneChildCount: 0,
    sprint: s ? { id: s.id, name: s.name, state: s.state } : null,
    storyPoints: null,
    fields: [], version: 1,
    createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
  }
}

// Five committed rows — more than the (deliberately tiny) bulk cap below, so the
// chunking is observable: 5 issues at cap 2 = three requests, never one.
const COMMITTED = [1, 2, 3, 4, 5].map(i => issue(`committed ${i}`, ACTIVE))
const BACKLOG_ROW = issue('backlog one')

const EMPTY_STATS = {
  issueCount: 0, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 0,
}

/** `bulkMoveCap` is deliberately 2 while `sectionCap` is 300 — the whole point. */
function view(activeState: Sprint['state'] = 'ACTIVE'): BacklogView {
  const active = { ...ACTIVE, state: activeState }
  return {
    sprints: [
      {
        sprint: active,
        issues: COMMITTED.map(i => ({ ...i, sprint: { id: active.id, name: active.name, state: active.state } })),
        truncated: false,
        totalAvailable: COMMITTED.length,
        stats: { ...EMPTY_STATS, issueCount: COMMITTED.length, unestimatedCount: COMMITTED.length },
      },
      {
        sprint: FUTURE,
        issues: [],
        truncated: false,
        totalAvailable: 0,
        stats: { ...EMPTY_STATS },
      },
    ],
    backlog: {
      issues: [BACKLOG_ROW],
      truncated: false,
      totalAvailable: 1,
      stats: { ...EMPTY_STATS, issueCount: 1, unestimatedCount: 1 },
    },
    sectionCap: 300,
    bulkMoveCap: 2,
  }
}

let currentView: BacklogView = view()

const addIssuesMock = vi.fn(async () => ACTIVE)
// Records the concurrency of the DELETE fan-out, not just its call count: the
// finding was an unbounded `Promise.all`, which a call count alone can't catch.
let inFlight = 0
let peakInFlight = 0
const removeIssueMock = vi.fn(async () => {
  inFlight += 1
  peakInFlight = Math.max(peakInFlight, inFlight)
  await new Promise(r => setTimeout(r, 5))
  inFlight -= 1
})

const apiGetBacklogViewMock = vi.fn(async () => structuredClone(currentView))

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetBacklogView: (...a: unknown[]) => apiGetBacklogViewMock(...(a as [])),
  apiRankIssue: vi.fn(),
  apiListIssuesPaged: vi.fn(async () => ({
    content: [], page: 0, size: 300, totalElements: 0, totalPages: 0, hasNext: false,
  })),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: 'MANAGER', boardMode: 'SCRUM',
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: 'OWNER', createdAt: '2026-01-01T00:00:00Z',
  })),
  sprintsApi: {
    list: vi.fn(async () => ({
      content: [], page: 0, size: 200, totalElements: 0, totalPages: 0, hasNext: false,
    })),
    get: vi.fn(async () => ACTIVE),
    addIssues: (...a: unknown[]) => addIssuesMock(...(a as [])),
    removeIssue: (...a: unknown[]) => removeIssueMock(...(a as [])),
    completionPreview: vi.fn(async () => ({
      totalIssueCount: 5, doneIssueCount: 2, unfinishedIssueCount: 3,
      totalPoints: null, donePoints: null, unfinishedPoints: null,
      targetCandidates: [{ id: FUTURE.id, name: FUTURE.name, state: FUTURE.state }],
    })),
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
  currentView = view()
  addIssuesMock.mockClear()
  removeIssueMock.mockClear()
  removeIssueMock.mockImplementation(async () => {
    inFlight += 1
    peakInFlight = Math.max(peakInFlight, inFlight)
    await new Promise(r => setTimeout(r, 5))
    inFlight -= 1
  })
  inFlight = 0
  peakInFlight = 0
  apiGetBacklogViewMock.mockClear()
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

async function ready() {
  await screen.findByText('Sprint 7')
  await screen.findByText(COMMITTED[0].title)
}

/** Open the sprint section header's kebab. */
async function openSectionMenu(name = 'Sprint 7') {
  await userEvent.click(screen.getByRole('button', { name: `Actions for ${name}` }))
}

describe('BacklogPage bulk "move every issue to" (0.13.0 review, dc-cloud-guard)', () => {
  it('chunks a bulk add by the SERVER-supplied bulkMoveCap, sequentially', async () => {
    renderBacklog()
    await ready()

    await openSectionMenu()
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Sprint 8' }))

    // 5 issues at cap 2 → 3 requests of 2/2/1, never one 5-issue payload (400).
    await waitFor(() => expect(addIssuesMock).toHaveBeenCalledTimes(3))
    const batches = addIssuesMock.mock.calls.map(c => (c as unknown[])[3] as { issueIds: string[] })
    expect(batches.map(b => b.issueIds.length)).toEqual([2, 2, 1])
    // Every issue moved exactly once, in the rendered order.
    expect(batches.flatMap(b => b.issueIds)).toEqual(COMMITTED.map(i => i.id))
    // …and into the chosen sprint.
    expect((addIssuesMock.mock.calls[0] as unknown[])[2]).toBe(FUTURE.id)
  })

  it('bounds the "move all to Backlog" DELETE fan-out by the same cap', async () => {
    renderBacklog()
    await ready()

    await openSectionMenu()
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Backlog' }))

    await waitFor(() => expect(removeIssueMock).toHaveBeenCalledTimes(COMMITTED.length))
    // The bug was an unbounded Promise.all over the whole (up to 300-row) section.
    expect(peakInFlight).toBeLessThanOrEqual(2)
  })

  it('reports how many issues actually moved when a chunk fails, and refreshes', async () => {
    const { ApiResponseError } = await import('../api')
    let call = 0
    removeIssueMock.mockImplementation(async () => {
      call += 1
      // Third DELETE (i.e. the second wave) is refused by the server.
      if (call === 3) throw new ApiResponseError(422, 'This sprint is completed')
    })
    renderBacklog()
    await ready()

    await openSectionMenu()
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Backlog' }))

    // Truthful, and in the SERVER's words — never a generic "could not move".
    await screen.findByText(/Moved 3 of 5 issues/)
    await screen.findByText(/This sprint is completed/)
    // The run stopped at the failing wave instead of firing the rest.
    expect(removeIssueMock).toHaveBeenCalledTimes(4)
  })
})

describe('BacklogPage — a COMPLETED sprint is frozen (0.13.0 review, security-officer L1)', () => {
  beforeEach(() => { currentView = view('COMPLETED') })

  it('offers no bulk move out of it and no "Move to →" on its rows', async () => {
    renderBacklog()
    await ready()

    await openSectionMenu()
    // Editing and deleting a completed sprint stay available; MOVING its issues
    // does not — its membership is what it delivered.
    await screen.findByRole('menuitem', { name: 'Edit sprint' })
    expect(screen.queryByText('Move every issue to')).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Backlog' })).toBeNull()
    await userEvent.keyboard('{Escape}')

    await userEvent.click(screen.getByRole('button', { name: `Move ${COMMITTED[0].key}` }))
    await screen.findByRole('menuitem', { name: 'Move to top' })
    expect(screen.queryByText('Move to')).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Sprint 8' })).toBeNull()
  })

  it('keeps it out of every other section\'s move targets', async () => {
    renderBacklog()
    await ready()

    // The backlog row may be moved into the FUTURE sprint, never into the
    // completed one (the server answers 422).
    await userEvent.click(screen.getByRole('button', { name: `Move ${BACKLOG_ROW.key}` }))
    await screen.findByRole('menuitem', { name: 'Sprint 8' })
    expect(screen.queryByRole('menuitem', { name: 'Sprint 7' })).toBeNull()
  })
})

/**
 * Every sprint dialog must be a real dialog to assistive tech: `role="dialog"` +
 * `aria-modal="true"` + an accessible name, the same contract `CommandPalette`
 * and `ShortcutsHelp` already meet. Found in 0.13.0 browser QA, where
 * `[role="dialog"] button` over the open "Complete sprint" dialog matched
 * nothing — so a screen-reader user was never told a modal had opened.
 */
describe('BacklogPage — the sprint dialogs are announced as modal dialogs', () => {
  /** The QA probe itself: the dialog is reachable by role, and so are its buttons. */
  function assertModal(name: RegExp | string) {
    const dialog = screen.getByRole('dialog', { name })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(within(dialog).getAllByRole('button').length).toBeGreaterThan(0)
    return dialog
  }

  it('names the create dialog', async () => {
    renderBacklog()
    await ready()

    await userEvent.click(screen.getByRole('button', { name: /New sprint/ }))
    assertModal('New sprint')
  })

  it('names the edit and delete dialogs after their sprint', async () => {
    renderBacklog()
    await ready()

    await openSectionMenu()
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Edit sprint' }))
    assertModal(/Edit .*Sprint 7/)
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))

    await openSectionMenu()
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete sprint' }))
    assertModal(/Delete .*Sprint 7/)
  })

  it('names the complete dialog — the one the QA probe missed', async () => {
    renderBacklog()
    await ready()

    await userEvent.click(screen.getByRole('button', { name: 'Complete sprint' }))
    // Waits for the completion preview so the dialog is in its loaded shape.
    await screen.findByText(/issues done/)
    assertModal(/Complete .*Sprint 7/)
  })

  it('names the start dialog', async () => {
    // No ACTIVE sprint ⇒ the FUTURE section's "Start sprint" is not blocked.
    currentView = view('COMPLETED')
    renderBacklog()
    await ready()

    await userEvent.click(screen.getByRole('button', { name: 'Start sprint' }))
    assertModal(/Start .*Sprint 8/)
  })
})
