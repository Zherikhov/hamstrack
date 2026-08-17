import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BacklogPage from './BacklogPage'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { versionsApi } from '../api'
import type {
  BacklogView, Issue, IssueType, PriorityOption, ProjectDelivery, Sprint, Status, User,
} from '../types'

/**
 * HD-23 §5.3 — the backlog's drag-and-drop CONTRACT, the one part of the page the
 * builder deliberately left untested because jsdom implements no HTML5 drag-and-drop.
 *
 * The gesture is simulated the only way it can be: synthetic `dragStart` /
 * `dragOver` / `drop` events carrying a hand-rolled `dataTransfer` stub. jsdom's
 * `getBoundingClientRect()` returns all zeros, so the page's
 * "`clientY` past the row's vertical midpoint ⇒ drop AFTER it" rule degrades to
 * `clientY > 0` — which is exactly what makes both halves of the rule addressable
 * here (`clientY: 0` = above the row, `clientY: 1` = below it).
 *
 * What is asserted is the ANCHOR RESOLUTION, not the pixels: the client never sends
 * a rank value, so the whole correctness of a drag lives in which
 * `{ afterIssueId, beforeIssueId, sprintId | clearSprint }` it derives from the
 * rendered arrays. Also covered: the "drop on your own row" no-op (which must spend
 * no request) and the kebab twin, which must produce the SAME payload as the drag —
 * the accessibility requirement that native DnD is pointer-only.
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
  daysRemaining: 6, issueCount: 2, doneIssueCount: 0, points: 0, donePoints: 0,
  unestimatedCount: 2,
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

// Two committed rows and three ranked backlog rows — enough for "between two
// rows", "at the top", "append to the end" and a cross-section move.
const S1 = issue('sprint one', SPRINT)
const S2 = issue('sprint two', SPRINT)
const B1 = issue('backlog one')
const B2 = issue('backlog two')
const B3 = issue('backlog three')

const EMPTY_STATS = {
  issueCount: 0, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 0,
}

const VIEW: BacklogView = {
  sprints: [{
    sprint: SPRINT,
    issues: [S1, S2],
    truncated: false,
    totalAvailable: 2,
    stats: { ...EMPTY_STATS, issueCount: 2, unestimatedCount: 2 },
  }],
  backlog: {
    issues: [B1, B2, B3],
    truncated: false,
    totalAvailable: 3,
    stats: { ...EMPTY_STATS, issueCount: 3, unestimatedCount: 3 },
  },
  sectionCap: 300,
}

// HD-102: what the project DECLARES about how it delivers — the only thing this
// page may consult to decide whether it speaks Scrum. Swappable per test.
const SCRUM_DELIVERY: ProjectDelivery = {
  board: 'SCRUM', releases: true, estimation: true, preset: 'CUSTOM',
}
let delivery: ProjectDelivery = SCRUM_DELIVERY

// Swappable so the HD-102 block can describe a project with NO sprints at all —
// the state the removed presence heuristic got wrong in production.
let backlogView: BacklogView = VIEW

const apiRankIssueMock = vi.fn(async () => B1)
const apiGetBacklogViewMock = vi.fn(async () => structuredClone(backlogView))

// The real module is kept and only the network functions this page touches are
// replaced — a hand-listed façade would break on any unrelated new export.
vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetBacklogView: (...a: unknown[]) => apiGetBacklogViewMock(...(a as [])),
  apiRankIssue: (...a: unknown[]) => apiRankIssueMock(...(a as [])),
  apiListIssuesPaged: vi.fn(async () => ({
    content: [], page: 0, size: 300, totalElements: 0, totalPages: 0, hasNext: false,
  })),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: 'MANAGER', myPermissions: PROJECT_ADMIN_PERMISSIONS, delivery,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: 'OWNER', myPermissions: WORKSPACE_ADMIN_PERMISSIONS,
    createdAt: '2026-01-01T00:00:00Z',
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
  delivery = SCRUM_DELIVERY
  backlogView = VIEW
  apiRankIssueMock.mockClear()
  apiGetBacklogViewMock.mockClear()
  vi.mocked(versionsApi.list).mockClear()
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

/** jsdom has no DataTransfer; the page only ever touches these three members. */
function dataTransfer() {
  return { dropEffect: '', effectAllowed: '', setData: () => {}, getData: () => '' }
}

/**
 * Dispatch one drag event by hand. jsdom implements neither `DragEvent` nor
 * `DataTransfer`, and Testing Library's `fireEvent.dragOver` therefore falls back to
 * a plain `Event` — which silently DROPS `clientY`, the single coordinate the
 * "above or below the midpoint?" rule reads. Building a `MouseEvent` and attaching
 * the stub transfer is what makes both halves of that rule reachable at all.
 */
function fireDrag(el: HTMLElement, type: string, dt: object, clientY = 0) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientY })
  Object.defineProperty(event, 'dataTransfer', { value: dt })
  fireEvent(el, event)
}

/** The draggable row element carrying this issue key. */
function row(issue: Issue): HTMLElement {
  const key = screen.getByText(issue.key)
  const el = key.closest('[draggable]')
  if (!el) throw new Error(`no draggable row for ${issue.key}`)
  return el as HTMLElement
}

/** The <section> element a row lives in — the drop target. */
function sectionOf(issue: Issue): HTMLElement {
  const el = row(issue).closest('section')
  if (!el) throw new Error(`no section around ${issue.key}`)
  return el
}

/**
 * One complete gesture: pick `moved` up, hover `target` (above or below its
 * midpoint), and release over the section `target` lives in.
 */
function drag(moved: Issue, target: Issue, where: 'above' | 'below') {
  const dt = dataTransfer()
  fireDrag(row(moved), 'dragstart', dt)
  // jsdom's getBoundingClientRect() is all zeros, so the row's midpoint is 0:
  // clientY 1 lands BELOW it, clientY 0 above it.
  fireDrag(row(target), 'dragover', dt, where === 'below' ? 1 : 0)
  fireDrag(sectionOf(target), 'drop', dt)
}

/** Drop onto a section's own chrome rather than a row — "append to the end". */
function dragToSectionEnd(moved: Issue, section: HTMLElement) {
  const dt = dataTransfer()
  fireDrag(row(moved), 'dragstart', dt)
  fireDrag(section, 'dragover', dt, 500)
  fireDrag(section, 'drop', dt)
}

const lastRank = () => apiRankIssueMock.mock.calls[apiRankIssueMock.mock.calls.length - 1]

describe('BacklogPage drag-and-drop (HD-23 §5.3)', () => {
  it('resolves both anchors when a row is dropped between two rows', async () => {
    renderBacklog()
    await backlogReady()

    // backlog three → between backlog one and backlog two
    drag(B3, B1, 'below')

    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
    expect(lastRank()).toEqual([WS_ID, PROJECT_ID, B3.number, {
      afterIssueId: B1.id, beforeIssueId: B2.id,
    }])
  })

  it('sends only a `before` anchor when a row is dropped above the first one', async () => {
    renderBacklog()
    await backlogReady()

    drag(B3, B1, 'above')

    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
    expect(lastRank()).toEqual([WS_ID, PROJECT_ID, B3.number, {
      afterIssueId: undefined, beforeIssueId: B1.id,
    }])
  })

  it('sets the sprint AND the rank in one request when a backlog row is dropped into a sprint', async () => {
    // Left pending on purpose: the optimistic placement asserted below is what the
    // user sees BEFORE the server answers (`onSettled` would refetch this fixture
    // and put the row straight back).
    apiRankIssueMock.mockImplementationOnce(() => new Promise(() => {}))
    renderBacklog()
    await backlogReady()

    // backlog one → between the two committed rows
    drag(B1, S1, 'below')

    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
    expect(lastRank()).toEqual([WS_ID, PROJECT_ID, B1.number, {
      afterIssueId: S1.id, beforeIssueId: S2.id, sprintId: SPRINT.id,
    }])
    // The optimistic cache moved the row into the sprint section immediately.
    await waitFor(() => expect(sectionOf(S1)).toContainElement(row(B1)))
  })

  it('clears the sprint when a committed row is dropped onto the backlog section', async () => {
    renderBacklog()
    await backlogReady()

    dragToSectionEnd(S1, sectionOf(B1))

    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
    expect(lastRank()).toEqual([WS_ID, PROJECT_ID, S1.number, {
      afterIssueId: B3.id, beforeIssueId: undefined, clearSprint: true,
    }])
  })

  it('spends no request when a row is dropped on either side of its own position', async () => {
    renderBacklog()
    await backlogReady()

    drag(B2, B2, 'above')   // the slot it already occupies
    drag(B2, B2, 'below')   // …and the one directly under it
    drag(B2, B1, 'below')   // between one and two — again, where it already is

    await new Promise(r => setTimeout(r, 0))
    expect(apiRankIssueMock).not.toHaveBeenCalled()
  })

  it('offers a keyboard-reachable twin that sends the same payload as the drag', async () => {
    renderBacklog()
    await backlogReady()

    // Every drag has a kebab equivalent (native DnD is pointer-only).
    await userEvent.click(screen.getByRole('button', { name: `Move ${B3.key}` }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Move to top' }))

    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
    expect(lastRank()).toEqual([WS_ID, PROJECT_ID, B3.number, {
      afterIssueId: undefined, beforeIssueId: B1.id,
    }])
  })

  it('rolls the row back and says the list changed when the server answers 409', async () => {
    // The real error class (the module is spread, not replaced), because the
    // "list changed" branch is `e instanceof ApiResponseError && e.status === 409`.
    const { ApiResponseError } = await import('../api')
    apiRankIssueMock.mockRejectedValueOnce(
      new ApiResponseError(409, 'The list changed — refresh and try again'))
    renderBacklog()
    await backlogReady()

    drag(B3, B1, 'above')

    await waitFor(() => expect(apiRankIssueMock).toHaveBeenCalledTimes(1))
    // Non-destructive: a message plus a refetch, never a ghost or a duplicate row.
    await screen.findByText(/list changed/i)
    await waitFor(() => {
      const keys = screen.getAllByText(/^PR-/).map(el => el.textContent)
      expect(keys.filter(k => k === B3.key)).toHaveLength(1)
    })
  })
})

/**
 * HD-102 — the Backlog asks the project's DECLARED delivery capabilities, never
 * "does this project have sprints yet?".
 *
 * The heuristic these replace (`boardMode === 'SCRUM' || openSprints.length > 0`)
 * is the one that shipped a production dead end: it hid BOTH sprint-creation
 * entry points until a sprint existed, so a Kanban project could never create its
 * first one. The other half of the rule matters just as much: turning sprint
 * planning off must never make a RUNNING sprint disappear (Rule B, §5.2).
 */
describe('BacklogPage delivery capabilities (HD-102)', () => {
  it('offers the sprint controls when the project declares iterations', async () => {
    renderBacklog()
    await backlogReady()

    expect(screen.getByRole('button', { name: /new sprint/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create sprint/i })).toBeInTheDocument()
  })

  it('keeps a running sprint visible and read-only when iterations are off', async () => {
    delivery = { ...SCRUM_DELIVERY, board: 'KANBAN' }
    renderBacklog()
    await backlogReady()

    // Rule B: the sprint and its issues are still on screen…
    expect(screen.getByText('Sprint 7')).toBeInTheDocument()
    expect(screen.getByText(S1.title)).toBeInTheDocument()
    // …with the reason stated…
    expect(screen.getByText(/sprint planning off/i)).toBeInTheDocument()
    // …and the one action a running sprint must never lose still offered.
    expect(screen.getByRole('button', { name: /complete sprint/i })).toBeInTheDocument()

    // Every planning CONTROL is gone (§5.2: controls are gated, values are not).
    expect(screen.queryByRole('button', { name: /new sprint/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /create sprint/i })).toBeNull()

    // …including the kebab's cross-section moves, in both directions.
    await userEvent.click(screen.getByRole('button', { name: `Move ${B1.key}` }))
    expect(await screen.findByRole('menuitem', { name: 'Move to top' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Sprint 7' })).toBeNull()
  })

  it('refuses a drag into a read-only sprint section without spending a request', async () => {
    delivery = { ...SCRUM_DELIVERY, board: 'KANBAN' }
    renderBacklog()
    await backlogReady()

    drag(B1, S1, 'above')

    await new Promise(r => setTimeout(r, 0))
    expect(apiRankIssueMock).not.toHaveBeenCalled()
  })
})

/**
 * HD-102 §5.3 Rule C, the negative half — **no surface may derive visibility
 * from data presence.** These two cases are the exact pair the deleted heuristic
 * (`boardMode === 'SCRUM' || openSprints.length > 0`) got wrong, and they are the
 * shape of the production bug rather than a paraphrase of the new code:
 *
 *  • capability ON, ZERO sprints  ⇒ the sprint controls must be there anyway.
 *    This is the dead end: the old rule hid BOTH creation entry points until a
 *    sprint existed, so the first one could never be created from here.
 *  • capability OFF, sprints PRESENT ⇒ no planning controls, even though the
 *    data the old rule keyed on is sitting right there.
 */
describe('BacklogPage — visibility never follows the data (HD-102 Rule C)', () => {
  const EMPTY_VIEW: BacklogView = {
    sprints: [],
    backlog: {
      issues: [B1, B2, B3], truncated: false, totalAvailable: 3,
      stats: { ...EMPTY_STATS, issueCount: 3, unestimatedCount: 3 },
    },
    sectionCap: 300,
  }

  it('offers sprint creation on an iterations project with ZERO sprints', async () => {
    backlogView = EMPTY_VIEW
    renderBacklog()
    await screen.findByText(B1.title)

    // Both entry points — the header button and the inline "create" affordance.
    expect(await screen.findByRole('button', { name: /new sprint/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create sprint/i })).toBeInTheDocument()
  })

  it('offers none on a project that declares no iterations, sprints or not', async () => {
    delivery = { ...SCRUM_DELIVERY, board: 'KANBAN' }
    backlogView = EMPTY_VIEW
    renderBacklog()
    await screen.findByText(B1.title)

    expect(screen.queryByRole('button', { name: /new sprint/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /create sprint/i })).toBeNull()
    // …and the backlog rows offer no "move to a sprint" either.
    await userEvent.click(screen.getByRole('button', { name: `Move ${B1.key}` }))
    expect(await screen.findByRole('menuitem', { name: 'Move to top' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /sprint/i })).toBeNull()
  })
})

/**
 * The other two Backlog rows of §6: the fix-version filter is `R`-gated and the
 * per-section point sums are `E`-gated. Both are CONTROLS/aggregates, so neither
 * has a Rule B half to preserve.
 */
describe('BacklogPage releases + estimation gating (HD-102 §6)', () => {
  const fixVersionFilter = () => screen.queryByRole('button', { name: 'Filter by fix version' })

  it('drops the fix-version filter, and its fetch, when releases are off', async () => {
    delivery = { ...SCRUM_DELIVERY, releases: false }
    renderBacklog()
    await backlogReady()

    expect(fixVersionFilter()).toBeNull()
    expect(versionsApi.list).not.toHaveBeenCalled()
  })

  it('drops the per-section point sums when estimation is off', async () => {
    delivery = { ...SCRUM_DELIVERY, estimation: false }
    renderBacklog()
    await backlogReady()

    // The sections themselves are untouched — only the point aggregates go.
    expect(screen.getByText('Sprint 7')).toBeInTheDocument()
    expect(screen.queryByTitle('Story points done / committed')).toBeNull()
    expect(screen.queryByText(/unestimated/i)).toBeNull()
  })

  it('keeps them when estimation is on', async () => {
    renderBacklog()
    await backlogReady()

    expect(screen.getAllByTitle('Story points done / committed').length).toBeGreaterThan(0)
  })
})
