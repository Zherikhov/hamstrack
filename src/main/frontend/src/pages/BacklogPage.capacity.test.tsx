import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BacklogPage from './BacklogPage'
import { forgetProject } from '../recentProjects'
import { backlogViewKeyPrefix } from '../lib/queryKeys'
import { ApiResponseError, EXPENSIVE_SURFACE_BUSY, TOO_MANY_IN_FLIGHT } from '../apiError'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import type {
  BacklogView, IssueType, PriorityOption, ProjectDelivery, Status, User,
} from '../types'

/**
 * **A saturated instance must not tell a planner their project was deleted.**
 *
 * The page's error branch was written when `GET …/backlog` had no throttle, so
 * `isError` on that query really did mean 404 or 403. HD-174 gave the aggregate
 * three new ways to fail — the per-minute planning budget, `TOO_MANY_IN_FLIGHT`
 * and `EXPENSIVE_SURFACE_BUSY` — and `queryClient.ts` correctly never retries a
 * 429, so the query lands in `isError` and the page rendered *"Project not
 * found — it may have been deleted, or your access was removed"* **and**
 * `forgetProject` dropped the project from the recency journal, so the `/`
 * redirect stopped pointing at it.
 *
 * The path is cross-tenant: any tenant saturating the shared expensive-read
 * share — reports, search, or another team's planning — made every other team's
 * Backlog report a permission problem and silently forget the project.
 *
 * **The negatives are the point.** A wrong build and a right build are identical
 * on every screen anybody looks at until the instance is actually busy, so what
 * is asserted here is what must NOT happen: no not-found copy, no
 * `forgetProject`, and a retry that re-asks the same view rather than anything
 * larger. The positive control — a 404 still doing both — is in the same file on
 * purpose: without it, a build that had simply deleted the not-found branch
 * would pass everything else.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }
const KANBAN: ProjectDelivery = {
  board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN',
}

const EMPTY_STATS = {
  issueCount: 0, doneIssueCount: 0, points: null, donePoints: null, unestimatedCount: 0,
}

function emptyView(): BacklogView {
  return {
    sprints: [],
    backlog: {
      issues: [
        {
          id: 'i1', number: 1, key: 'PR-1', title: 'a ranked row',
          type: TASK, status: TODO, priority: PRIORITY,
          reporter: { id: 'u-other', displayName: 'Other' },
          childCount: 0, doneChildCount: 0, sprint: null, storyPoints: null,
          fields: [], version: 1,
          createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
        },
      ],
      truncated: false,
      totalAvailable: 1,
      stats: { ...EMPTY_STATS, issueCount: 1 },
    },
    sectionCap: 300,
    bulkMoveCap: 100,
  }
}

/**
 * The refusals the planning surface can answer with (HD-174 §8.1) plus the
 * failure a saturated instance produces for a reason that never reached the
 * throttle at all. They are listed as one table because the page owes all four
 * the same treatment; they are still told apart in the copy, which the last
 * assertion of each case checks.
 */
const CAPACITY_FAILURES: { name: string; error: () => ApiResponseError; declined: boolean }[] = [
  {
    name: 'the per-minute planning budget (no errorType)',
    declined: true,
    error: () => new ApiResponseError(
      429, 'Too many planning requests — try again shortly.',
      undefined, undefined, { retryAfter: 0 }),
  },
  {
    name: TOO_MANY_IN_FLIGHT,
    declined: true,
    error: () => new ApiResponseError(
      429, 'Too many of your requests are running at once — wait for one to finish.',
      undefined, undefined, { errorType: TOO_MANY_IN_FLIGHT }),
  },
  {
    name: EXPENSIVE_SURFACE_BUSY,
    declined: true,
    error: () => new ApiResponseError(
      429, 'This instance is running as many expensive requests as it can at once.',
      undefined, undefined, { errorType: EXPENSIVE_SURFACE_BUSY }),
  },
  {
    name: '503 — saturation that never produced a 429',
    declined: false,
    error: () => new ApiResponseError(503, 'Service Unavailable'),
  },
]

let viewReply: () => Promise<BacklogView> = async () => emptyView()
const apiGetBacklogViewMock = vi.fn(() => viewReply())

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetBacklogView: () => apiGetBacklogViewMock(),
  apiGetBacklogSection: vi.fn(),
  apiRankIssue: vi.fn(),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: 'MANAGER', myPermissions: PROJECT_ADMIN_PERMISSIONS,
    delivery: KANBAN, createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: 'OWNER', myPermissions: WORKSPACE_ADMIN_PERMISSIONS,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  sprintsApi: {
    list: vi.fn(async () => ({
      content: [], page: 0, size: 200, totalElements: 0, totalPages: 0, hasNext: false,
    })),
    get: vi.fn(),
    addIssues: vi.fn(),
    removeIssue: vi.fn(),
    completionPreview: vi.fn(),
  },
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  componentsApi: { list: vi.fn(async () => []), update: vi.fn() },
  versionsApi: { list: vi.fn(async () => []) },
}))

vi.mock('../recentProjects', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  forgetProject: vi.fn(),
}))

beforeAll(() => {
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
  viewReply = async () => emptyView()
  apiGetBacklogViewMock.mockClear()
  vi.mocked(forgetProject).mockClear()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
  useUiStore.setState({ createIssueOpen: false, createIssuePreset: undefined })
})

function renderBacklog() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rendered = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/backlog`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId/backlog" element={<BacklogPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { qc, ...rendered }
}

const notFoundCopy = () => screen.queryByText(/Project not found/)

describe('a busy server is not a missing project (HD-174)', () => {
  for (const { name, error, declined } of CAPACITY_FAILURES) {
    it(`does not claim the project is gone — ${name}`, async () => {
      viewReply = () => Promise.reject(error())
      renderBacklog()

      await screen.findByRole('button', { name: /Try again/ })
      // The two things a saturated instance must never cause, stated as
      // negatives because that is how they fail: a sentence blaming the
      // project, and a journal entry quietly deleted.
      expect(notFoundCopy()).toBeNull()
      expect(screen.queryByRole('button', { name: 'Go to projects' })).toBeNull()
      expect(forgetProject).not.toHaveBeenCalled()

      // …and the two states are not read out as the same fact.
      if (declined) {
        expect(screen.getByText(/declined this request/)).toBeTruthy()
        // The server's own sentence, rendered rather than paraphrased.
        expect(screen.getByText(new RegExp(error().detail.slice(0, 20)))).toBeTruthy()
      } else {
        expect(screen.getAllByText(/overloaded/).length).toBeGreaterThan(0)
      }
    })
  }

  it('retries the VIEW, and nothing larger, when the planner asks', async () => {
    viewReply = () => Promise.reject(CAPACITY_FAILURES[2].error())
    renderBacklog()
    await screen.findByRole('button', { name: /Try again/ })
    await waitFor(() => expect(apiGetBacklogViewMock).toHaveBeenCalledTimes(1))

    viewReply = async () => emptyView()
    await userEvent.click(screen.getByRole('button', { name: /Try again/ }))

    // One more request, to the same endpoint, and only because a human asked:
    // nothing in the refusal path retries by itself.
    await screen.findByText('a ranked row')
    expect(apiGetBacklogViewMock).toHaveBeenCalledTimes(2)
    expect(forgetProject).not.toHaveBeenCalled()
  })

  it('holds the retry back until Retry-After has elapsed', async () => {
    viewReply = () => Promise.reject(new ApiResponseError(
      429, 'Too many planning requests — try again shortly.',
      undefined, undefined, { retryAfter: 30 }))
    renderBacklog()

    const button = await screen.findByRole('button', { name: /Try again in/ })
    // Re-asking a surface that has just handed back a wait is the one thing the
    // refusal explicitly told us not to do.
    expect((button as HTMLButtonElement).disabled).toBe(true)
    expect(apiGetBacklogViewMock).toHaveBeenCalledTimes(1)
  })

  it('keeps the rows it has when a REFRESH of a loaded view is refused', async () => {
    const { qc } = renderBacklog()
    await screen.findByText('a ranked row')

    // The same query, refetched later (an SSE event, a window focus, the "/"
    // redirect coming back) and refused. React Query keeps the data and flips
    // the cached status to error; the observer does not re-render for it, so
    // the page shows nothing until the planner touches anything at all — and
    // THEN it rendered the whole error branch. That delay is why this failure
    // mode reads as "the backlog randomly claims my project was deleted".
    viewReply = () => Promise.reject(CAPACITY_FAILURES[0].error())
    await act(async () => {
      await qc.refetchQueries({ queryKey: backlogViewKeyPrefix(WS_ID, PROJECT_ID) })
    })
    // Any re-render at all is enough; a collapse/expand pair is the cheapest
    // one that leaves the section exactly as the planner found it.
    await userEvent.click(screen.getByLabelText('Collapse section'))
    await userEvent.click(screen.getByLabelText('Expand section'))

    // The rows the server did confirm stay on screen — they are stale, not
    // wrong; nothing rolls back; and the page says which of the two facts it is.
    expect(screen.getByText('a ranked row')).toBeTruthy()
    expect(screen.getByText(/may be out of date/)).toBeTruthy()
    expect(notFoundCopy()).toBeNull()
    expect(forgetProject).not.toHaveBeenCalled()
  })
})

describe('the answers that really do mean the project is gone', () => {
  for (const status of [404, 403]) {
    it(`still forgets the project and says so — ${status}`, async () => {
      viewReply = () => Promise.reject(new ApiResponseError(status, 'Project not found'))
      renderBacklog()

      // The positive control for every negative above. Without it, a build that
      // deleted the not-found branch outright would pass this whole file.
      expect(await screen.findByText(/Project not found/)).toBeTruthy()
      expect(screen.getByRole('button', { name: 'Go to projects' })).toBeTruthy()
      await waitFor(() => expect(forgetProject).toHaveBeenCalledWith(ME.id, PROJECT_ID))
    })
  }

  /**
   * **Cached rows must not buy silence.**
   *
   * React Query v5 keeps `data` and flips `status` to `error` on a *refetch*
   * failure. So the fix that stopped a refusal from being read as a deletion —
   * guarding the not-found branch with `!data` — also stopped the deletion from
   * being reported at all once the planner had been on the page long enough to
   * have rows. A 404 or 403 arriving mid-session (the project deleted, or this
   * caller's access revoked) then took NO branch: the backlog rendered normally,
   * `forgetProject` still fired, every subsequent request still 404'd, and
   * nothing on screen said a word. That is a silence where there used to be a
   * message, and it is invisible to anyone testing by hand — the screen looks
   * exactly like a healthy one.
   */
  for (const status of [404, 403]) {
    it(`says so over CACHED rows too — ${status} arriving mid-session`, async () => {
      const { qc } = renderBacklog()
      await screen.findByText('a ranked row')
      expect(forgetProject).not.toHaveBeenCalled()

      viewReply = () => Promise.reject(new ApiResponseError(status, 'Project not found'))
      await act(async () => {
        await qc.refetchQueries({ queryKey: backlogViewKeyPrefix(WS_ID, PROJECT_ID) })
      })

      expect(await screen.findByText(/Project not found/)).toBeTruthy()
      expect(screen.getByRole('button', { name: 'Go to projects' })).toBeTruthy()
      // And the rows go with the message: they are rows this reader no longer
      // has. This is the one failure where cached data is not worth keeping.
      expect(screen.queryByText('a ranked row')).toBeNull()
      await waitFor(() => expect(forgetProject).toHaveBeenCalledWith(ME.id, PROJECT_ID))
    })
  }

  it('says a plain refresh failure over cached rows, and keeps them', async () => {
    const { qc } = renderBacklog()
    await screen.findByText('a ranked row')

    viewReply = () => Promise.reject(new ApiResponseError(500, 'Something went wrong'))
    await act(async () => {
      await qc.refetchQueries({ queryKey: backlogViewKeyPrefix(WS_ID, PROJECT_ID) })
    })

    // The third of the three messages `data` used to suppress. A 500 says nothing
    // about the project, so the rows stay and nothing is forgotten — but the page
    // still has to admit that what is on screen was not refreshed.
    expect(await screen.findByText(/may be out of date/)).toBeTruthy()
    expect(screen.getByText('a ranked row')).toBeTruthy()
    expect(notFoundCopy()).toBeNull()
    expect(forgetProject).not.toHaveBeenCalled()
  })

  it('offers a retry, and forgets nothing, on a 500', async () => {
    viewReply = () => Promise.reject(new ApiResponseError(500, 'Something went wrong'))
    renderBacklog()

    // A 500 is a bug on one request: worth re-asking, and no evidence at all
    // about whether the project exists.
    expect(await screen.findByText(/could not be loaded/)).toBeTruthy()
    expect(notFoundCopy()).toBeNull()
    expect(forgetProject).not.toHaveBeenCalled()
  })
})
