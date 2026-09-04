import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BacklogPage from './BacklogPage'
import { apiListIssuesPaged } from '../api'
import { ApiResponseError, EXPENSIVE_SURFACE_BUSY } from '../apiError'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import type {
  BacklogSectionResponse, BacklogView, Issue, IssueType, PriorityOption, ProjectDelivery,
  Sprint, Status, User,
} from '../types'

/**
 * HD-96 — a section obtained by REFRESH is the same section the aggregate would
 * have rendered.
 *
 * The defect: refreshing one section called the ordinary issue list asking for
 * `size = sectionCap`, the server narrowed that to 100 and answered 200, and a
 * section holding 101–300 issues loaded whole and came back cut. Nothing errored,
 * because both halves were internally consistent — the refreshed section simply
 * answered under a different honesty protocol than the rendered one. `truncated`
 * meant "more exist than the page I received" on one side and "more exist than
 * `sectionCap`" on the other, which is why the bulk-move notice could report a
 * truncation the server had never applied.
 *
 * These tests are written about the PROPERTY, not about the old call: whatever the
 * server says a section is, that is what the page shows and what its actions
 * operate on. There is no size on the wire for anyone to be wrong about — pinned
 * on the request itself in `api.test.ts`.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const DOING: Status = { id: 's2', name: 'Doing', color: '#666', category: 'IN_PROGRESS', position: 1 }
const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

const SCRUM: ProjectDelivery = {
  board: 'SCRUM', releases: true, estimation: false, preset: 'SCRUM',
}

function sprint(id: string, name: string, state: Sprint['state'], sequence: number): Sprint {
  return {
    id, name, goal: null, state, sequence,
    startAt: null, endAt: null, completedAt: null, daysRemaining: null,
    issueCount: 0, doneIssueCount: 0, points: null, donePoints: null, unestimatedCount: 0,
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

const EMPTY_STATS = {
  issueCount: 0, doneIssueCount: 0, points: null, donePoints: null, unestimatedCount: 0,
}

/**
 * 120 committed rows — deliberately more than `Paging.MAX_SIZE` (100) and fewer
 * than the stock section cap (300), i.e. exactly the band in which the defect
 * lived. The number is a property of the DEFECT, not of any configuration: it has
 * to straddle the general list's page ceiling to say anything.
 */
const COMMITTED = Array.from({ length: 120 }, (_, i) => issue(`row ${i + 1}`, ACTIVE))
const BACKLOG_ROW = issue('backlog one')

function bigView(): BacklogView {
  return {
    sprints: [
      {
        sprint: ACTIVE,
        issues: COMMITTED,
        truncated: false,
        totalAvailable: COMMITTED.length,
        stats: { ...EMPTY_STATS, issueCount: COMMITTED.length },
      },
      { sprint: FUTURE, issues: [], truncated: false, totalAvailable: 0, stats: { ...EMPTY_STATS } },
    ],
    backlog: {
      issues: [BACKLOG_ROW], truncated: false, totalAvailable: 1,
      stats: { ...EMPTY_STATS, issueCount: 1 },
    },
    sectionCap: 300,
    bulkMoveCap: 100,
  }
}

/**
 * The same project seen through a status filter, on an install whose operator
 * retuned `app.agile.section-max-issues` DOWN to 2: the section is genuinely
 * over-cap, so the server says so and reports whole-section, filter-aware totals
 * beside the two rows it returned.
 */
function filteredTruncatedView(): BacklogView {
  return {
    sprints: [
      {
        sprint: ACTIVE,
        issues: COMMITTED.slice(0, 2),
        truncated: true,
        totalAvailable: 60,
        stats: { ...EMPTY_STATS, issueCount: 60 },
      },
      { sprint: FUTURE, issues: [], truncated: false, totalAvailable: 0, stats: { ...EMPTY_STATS } },
    ],
    backlog: { issues: [], truncated: false, totalAvailable: 0, stats: { ...EMPTY_STATS } },
    sectionCap: 2,
    bulkMoveCap: 100,
  }
}

/** What the section endpoint answers next. Set per test; the server is the author. */
let sectionReply: (sprintId: string | null) => BacklogSectionResponse

function sectionOf(view: BacklogView, sprintId: string | null): BacklogSectionResponse {
  const sprintSection = view.sprints.find(s => s.sprint.id === sprintId)
  const section = sprintId === null ? view.backlog : sprintSection
  return {
    sprint: sprintSection?.sprint ?? null,
    issues: section?.issues ?? [],
    truncated: section?.truncated ?? false,
    totalAvailable: section?.totalAvailable ?? 0,
    stats: section?.stats ?? { ...EMPTY_STATS },
    sectionCap: view.sectionCap,
    bulkMoveCap: view.bulkMoveCap ?? 100,
  }
}

let aggregate: (statusId?: string) => BacklogView = bigView
// Rest-typed rather than parameter-typed, so `mock.calls[i][n]` still indexes
// under `tsc -b` — a no-arg signature records the empty tuple and the assertions
// below stop compiling. (vitest runs either happily; the build is the gate.)
const apiGetBacklogViewMock = vi.fn(async (...args: unknown[]) =>
  structuredClone(aggregate((args[2] as { statusId?: string } | undefined)?.statusId)))
const apiGetBacklogSectionMock = vi.fn(async (...args: unknown[]) =>
  structuredClone(sectionReply(args[2] as string | null)))
const addIssuesMock = vi.fn(async () => ACTIVE)

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetBacklogView: (...a: unknown[]) => apiGetBacklogViewMock(...(a as [])),
  apiGetBacklogSection: (...a: unknown[]) => apiGetBacklogSectionMock(...(a as [])),
  // Mocked purely so this test can assert it is NEVER reached: the planning
  // surface must not refresh a section through the paged issue list again.
  apiListIssuesPaged: vi.fn(),
  apiRankIssue: vi.fn(),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO, DOING], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: 'MANAGER', myPermissions: PROJECT_ADMIN_PERMISSIONS,
    delivery: SCRUM, createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: 'OWNER', myPermissions: WORKSPACE_ADMIN_PERMISSIONS,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  sprintsApi: {
    list: vi.fn(async (_ws: string, _p: string, opts?: { state?: string | string[] }) => {
      const want = opts?.state === undefined ? undefined
        : Array.isArray(opts.state) ? opts.state : [opts.state]
      const content = [ACTIVE, FUTURE].filter(s => !want || want.includes(s.state))
      return { content, page: 0, size: 200, totalElements: content.length, totalPages: 1, hasNext: false }
    }),
    get: vi.fn(async () => ACTIVE),
    addIssues: (...a: unknown[]) => addIssuesMock(...(a as [])),
    removeIssue: vi.fn(),
    completionPreview: vi.fn(),
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
  aggregate = bigView
  sectionReply = id => sectionOf(bigView(), id)
  apiGetBacklogViewMock.mockClear()
  apiGetBacklogSectionMock.mockClear()
  addIssuesMock.mockClear()
  vi.mocked(apiListIssuesPaged).mockClear()
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

const rows = () => screen.queryAllByText(/^row \d+$/)
const truncationBanner = () => screen.queryAllByText(/Showing the first/)

/** The sprint sections come first, so the ACTIVE one owns the first control. */
async function refreshFirstSection() {
  await userEvent.click(screen.getAllByLabelText('Refresh this section')[0])
}

describe('BacklogPage — refreshing one section (HD-96)', () => {
  it('keeps every row of a section larger than the issue list’s page ceiling', async () => {
    renderBacklog()
    await screen.findByText('row 1')
    expect(rows()).toHaveLength(120)

    await refreshFirstSection()

    await waitFor(() => expect(apiGetBacklogSectionMock).toHaveBeenCalledTimes(1))
    expect(apiGetBacklogSectionMock.mock.calls[0][2]).toBe(ACTIVE.id)
    // The whole section came back, not a first page of it — and `truncated` is
    // the server's answer, so nothing claims rows are hidden when none are.
    expect(rows()).toHaveLength(120)
    expect(truncationBanner()).toHaveLength(0)
    expect(screen.getByText('120 issues')).toBeTruthy()
    // The refresh never goes near the paged issue list, which is where the
    // silently-clamped `size` lived.
    expect(apiListIssuesPaged).not.toHaveBeenCalled()
  })

  it('hands the WHOLE refreshed section to “move every issue to →”', async () => {
    renderBacklog()
    await screen.findByText('row 1')
    await refreshFirstSection()
    await waitFor(() => expect(apiGetBacklogSectionMock).toHaveBeenCalledTimes(1))

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Sprint 7' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Sprint 8' }))

    // 120 ids chunked by the server's bulkMoveCap (100) — 100 + 20, never a set
    // silently reduced to the 100 rows a clamped refresh would have left behind.
    await waitFor(() => expect(addIssuesMock).toHaveBeenCalledTimes(2))
    const batches = addIssuesMock.mock.calls.map(c => (c as unknown[])[3] as { issueIds: string[] })
    expect(batches.map(b => b.issueIds.length)).toEqual([100, 20])
    expect(batches.flatMap(b => b.issueIds)).toEqual(COMMITTED.map(i => i.id))
    // The section was not truncated, so the user is told nothing was left behind.
    expect(screen.queryAllByText(/was not on screen/)).toHaveLength(0)
  })

  it('adopts the server’s counters when a filtered, truncated section is refreshed', async () => {
    aggregate = statusId => statusId ? filteredTruncatedView() : bigView()
    renderBacklog()
    await screen.findByText('row 1')

    // The status filter is the design system's custom <Select> — a button plus a
    // listbox popover, not a native <select>.
    await userEvent.click(screen.getByRole('button', { name: 'Filter by status' }))
    await userEvent.click(await screen.findByRole('option', { name: TODO.name }))
    await waitFor(() => expect(screen.getAllByText('60 issues').length).toBeGreaterThan(0))
    expect(truncationBanner()).toHaveLength(1)

    // Three of those 60 left the sprint while the planner was reading. The
    // section endpoint is the only thing that can see the whole section, so its
    // numbers are the ones the header shows — the old path recomputed them from
    // the rows on screen, or froze them, and reported 60 forever.
    sectionReply = id => ({
      ...sectionOf(filteredTruncatedView(), id),
      totalAvailable: 57,
      stats: { ...EMPTY_STATS, issueCount: 57 },
    })
    await refreshFirstSection()

    await waitFor(() => expect(screen.getAllByText('57 issues').length).toBeGreaterThan(0))
    // Still over the (retuned) cap, so the banner stays — with the new total.
    expect(truncationBanner()).toHaveLength(1)
    expect(screen.queryAllByText('60 issues')).toHaveLength(0)
    // The filter the view was rendered with travels with the refresh; a refresh
    // that asked a different question would answer a different section.
    expect(apiGetBacklogSectionMock.mock.calls[0][3]).toMatchObject({ statusId: TODO.id })
  })
})

/**
 * HD-174 §5.4 — what the planner SEES when the server refuses the refresh.
 *
 * The matrix (all three refusal shapes, the loop that stops, the negative that
 * matters) is sealed one layer down in `components/sprints.refusal.test.tsx`,
 * where a hook renders in milliseconds. This is the other half of the same rule
 * and the half only a page can answer: that the section does not quietly keep
 * rendering rows it could not confirm. A stale section with no signal is its own
 * defect — it is showing data the user just changed.
 *
 * ONE test, and a generous timeout, on purpose: every case here costs a full
 * `BacklogPage` render, and this file already sits near vitest's 5 s default on
 * a loaded machine.
 */
describe('BacklogPage — a refused section refresh (HD-174)', () => {
  const BUSY = new ApiResponseError(
    429, 'This instance is running as many expensive requests as it can at once. Try again in a moment.',
    undefined, undefined, { retryAfter: 1, errorType: EXPENSIVE_SURFACE_BUSY })

  it('keeps the rows, marks them stale, refetches no aggregate, and offers a manual retry', async () => {
    renderBacklog()
    await screen.findByText('row 1')
    expect(apiGetBacklogViewMock).toHaveBeenCalledTimes(1)

    apiGetBacklogSectionMock.mockRejectedValueOnce(BUSY)
    await refreshFirstSection()

    // The refusal's own sentence, unparaphrased. `EXPENSIVE_SURFACE_BUSY` means
    // the INSTANCE is full and this caller may hold no permit at all, so the UI
    // adds no call to action of its own — a refusal may only prescribe something
    // its reader can actually do, and "Try again" is the whole of it.
    await screen.findByText(/Not refreshed/)
    expect(screen.getByText(new RegExp(BUSY.detail.slice(0, 40)))).toBeTruthy()
    // The rank write that preceded the refresh committed, so the rows are stale
    // rather than wrong: nothing is rolled back and nothing is hidden.
    expect(rows()).toHaveLength(120)
    // THE assertion. A 429 refuses a 12-statement read; the aggregate this used
    // to fall back to is 12 + N (32 at AGILE_MAX_OPEN_SPRINTS=20). One more
    // aggregate call here is the whole defect, and it is invisible on screen.
    expect(apiGetBacklogViewMock).toHaveBeenCalledTimes(1)

    // The manual affordance re-asks for the SECTION, and the marker goes when it
    // answers — still without an aggregate anywhere in the recovery path.
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(screen.queryByText(/Not refreshed/)).toBeNull())
    expect(apiGetBacklogSectionMock).toHaveBeenCalledTimes(2)
    expect(apiGetBacklogViewMock).toHaveBeenCalledTimes(1)
  }, 20_000)
})
