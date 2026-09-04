import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BoardPage from './BoardPage'
import { forgetProject } from '../recentProjects'
import { ApiResponseError } from '../apiError'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import type { IssueType, PriorityOption, Status, User } from '../types'

/**
 * **A failed request is not a revoked membership**, and the board was the last
 * surface still saying it was.
 *
 * `if (isError)` did two things at once: it rendered *"Project not found — it may
 * have been deleted, or your access was removed"* and it dropped the project from
 * the recency journal, so the `/` redirect stopped pointing at it. Both statements
 * are about *the project*, and the only two answers that are evidence about a
 * project are 404 and 403. Everything else — a 500, a dropped connection, the
 * 502/503/504 a saturated instance hands back for a reason that never reached any
 * throttle — says something about *this request*, and the board turned each of
 * them into an authorization lie plus a silent deletion.
 *
 * The board is not on the throttled expensive surface, so it cannot be handed a
 * planning refusal. That is a fact about *today's* mounting of one limiter, not
 * about the status class: `isThrottleRefusal`/`isOverloadFailure` are written
 * about what the server said, and this file asserts the board's copy for both
 * halves so a future control that does reach it inherits the right sentence
 * rather than the old lie.
 *
 * The positive control is in the same file on purpose: a build that simply
 * deleted the not-found branch would pass every negative here.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

let listReply: () => Promise<unknown> = async () => ({
  issues: [], truncated: false, totalAvailable: 0, cap: 100,
})
const apiListIssuesMock = vi.fn(() => listReply())

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiListIssues: () => apiListIssuesMock(),
  apiUpdateIssue: vi.fn(),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: 'MANAGER', myPermissions: PROJECT_ADMIN_PERMISSIONS,
    boardMode: 'KANBAN', createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: 'OWNER', myPermissions: WORKSPACE_ADMIN_PERMISSIONS,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  componentsApi: { list: vi.fn(async () => []), update: vi.fn() },
  versionsApi: { list: vi.fn(async () => []) },
  sprintsApi: {
    list: vi.fn(async () => ({
      content: [], page: 0, size: 200, totalElements: 0, totalPages: 0, hasNext: false,
    })),
  },
}))

vi.mock('../recentProjects', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  forgetProject: vi.fn(),
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
  apiListIssuesMock.mockClear()
  vi.mocked(forgetProject).mockClear()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
  useUiStore.setState({ createIssueOpen: false, createIssuePreset: undefined })
})

function renderBoard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/board`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId/board" element={<BoardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const notFoundCopy = () => screen.queryByText(/Project not found/)

/**
 * Every failure that is NOT evidence about the project, with the sentence the
 * board owes each. Listed as a table because the rule is about status classes:
 * the copy differs, the two prohibitions — no accusation, no journal deletion —
 * do not.
 */
const NOT_ABOUT_THE_PROJECT: { name: string; error: () => unknown; says: RegExp }[] = [
  {
    name: '500 — a bug on this one request',
    error: () => new ApiResponseError(500, 'Something went wrong'),
    says: /The board could not be loaded/,
  },
  {
    name: '502 — an edge that could not reach the app',
    error: () => new ApiResponseError(502, 'Bad Gateway'),
    says: /overloaded/,
  },
  {
    name: '503 — the instance shedding load',
    error: () => new ApiResponseError(503, 'Service Unavailable'),
    says: /overloaded/,
  },
  {
    name: '504 — an edge that stopped waiting',
    error: () => new ApiResponseError(504, 'Gateway Timeout'),
    says: /overloaded/,
  },
  {
    name: '429 — the server declining work',
    error: () => new ApiResponseError(429, 'Too many requests — try again shortly.'),
    says: /declined this request/,
  },
  {
    name: 'a dropped connection',
    error: () => new TypeError('Failed to fetch'),
    says: /The board could not be loaded/,
  },
]

describe('the board does not read a failed request as a lost project (HD-174)', () => {
  for (const { name, error, says } of NOT_ABOUT_THE_PROJECT) {
    it(`says what happened and forgets nothing — ${name}`, async () => {
      listReply = () => Promise.reject(error())
      renderBoard()

      // `findAll`: the headline and the sentence under it can both carry the
      // word, and "there are two of these" is not the failure being asserted.
      expect((await screen.findAllByText(says)).length).toBeGreaterThan(0)
      // The two things that must never follow a failure the server did not
      // frame as one about this project.
      expect(notFoundCopy()).toBeNull()
      expect(screen.queryByRole('button', { name: 'Go to projects' })).toBeNull()
      expect(forgetProject).not.toHaveBeenCalled()
      // …and the only offer is the SAME request again, asked for by a human.
      expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy()
    })
  }

  it('tells a refusal and an overloaded instance apart', async () => {
    // One branch, two sentences: only a refusal comes with a sentence the server
    // itself wrote, and reading both out the same way would tell a reader the
    // instance is overloaded when it merely said no.
    listReply = () => Promise.reject(
      new ApiResponseError(429, 'Too many requests — try again shortly.'))
    const { unmount } = renderBoard()
    expect(await screen.findByText(/declined this request/)).toBeTruthy()
    expect(screen.getByText(/Too many requests/)).toBeTruthy()
    expect(screen.queryByText(/overloaded/)).toBeNull()
    unmount()

    listReply = () => Promise.reject(new ApiResponseError(503, 'Service Unavailable'))
    renderBoard()
    expect((await screen.findAllByText(/overloaded/)).length).toBeGreaterThan(0)
    expect(screen.queryByText(/declined this request/)).toBeNull()
  })
})

describe('the two answers that really are about the project', () => {
  for (const status of [404, 403]) {
    it(`still says so and still forgets the project — ${status}`, async () => {
      listReply = () => Promise.reject(new ApiResponseError(status, 'Project not found'))
      renderBoard()

      // The positive control. Without it, a build that deleted the not-found
      // branch outright would pass every negative above.
      expect(await screen.findByText(/Project not found/)).toBeTruthy()
      expect(screen.getByRole('button', { name: 'Go to projects' })).toBeTruthy()
      await waitFor(() => expect(forgetProject).toHaveBeenCalledWith(ME.id, PROJECT_ID))
    })
  }
})
