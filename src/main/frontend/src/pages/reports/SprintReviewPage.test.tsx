import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import SprintReviewPage from './SprintReviewPage'
import { reportsApi } from '../../api'
import type { ProjectDelivery, Sprint, SprintReviewIssue, SprintReviewReport } from '../../types'

/**
 * HD-29 (R4) — the sprint review record, which the research says is the artefact
 * teams actually open at a retrospective, and which is therefore the primary
 * deliverable of this slice rather than the burn-up's appendix.
 *
 * What is pinned here:
 *
 *  1. **All five lists, always** — committed, added after start, removed before
 *     end, completed, carried over — each with its count and its point sum, and
 *     each with a sentence when it is empty rather than a blank card;
 *  2. **a completed sprint's record does not shed rows**: an issue deleted since
 *     is still listed, from the ledger's snapshot, named and unlinked — the whole
 *     reason the ledger's FK is `ON DELETE SET NULL`;
 *  3. **points are the entry snapshot**, and the page says so — the burn-up next
 *     door deliberately says the opposite about its own line;
 *  4. **taxonomy is read from the project config by id**, never hardcoded;
 *  5. the capability gate is the DECLARED `board`, never the presence of data,
 *     and it gates this UI only.
 */

const { FakeApiError } = vi.hoisted(() => {
  class FakeApiError extends Error {
    constructor(public status: number, public detail: string, public retryAfter?: number) {
      super(detail)
    }
  }
  return { FakeApiError }
})

const mockState = vi.hoisted(() => ({
  delivery: { board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM' },
  permissions: ['project.edit'] as string[],
  sprints: [] as unknown[],
  review: null as unknown,
  reviewError: null as unknown,
}))

vi.mock('../../api', () => ({
  ApiResponseError: FakeApiError,
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Payments', key: 'PAY', archived: false,
    myRole: 'MEMBER', myPermissions: mockState.permissions, delivery: mockState.delivery,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: 'w1', name: 'WS', slug: 'ws', myRole: 'MEMBER', myPermissions: [],
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiUpdateProject: vi.fn(),
  apiGetBacklogView: vi.fn(),
  apiListIssuesPaged: vi.fn(),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [
      { id: 'st1', name: 'Done', color: '#12B981', category: 'DONE', position: 2 },
      { id: 'st2', name: 'In Progress', color: '#F79009', category: 'IN_PROGRESS', position: 1 },
    ],
    transitions: [], priorities: [], fields: [],
    issueTypes: [{ id: 't1', name: 'Story', color: '#7C6CF5', position: 0, hierarchyLevel: 1 }],
  })),
  apiListWorkspaceMembers: vi.fn(async () => ([
    { userId: 'u1', email: 'alex@example.com', displayName: 'Alex Doe' },
  ])),
  sprintsApi: {
    list: vi.fn(async () => ({
      content: mockState.sprints, page: 0, size: 200,
      totalElements: mockState.sprints.length, totalPages: 1, hasNext: false,
    })),
  },
  reportsApi: {
    sprintReview: vi.fn(async () => {
      if (mockState.reviewError) throw mockState.reviewError
      return mockState.review
    }),
  },
}))

function sprint(over: Partial<Sprint> = {}): Sprint {
  return {
    id: 's1', name: 'Sprint 12', state: 'COMPLETED', sequence: 12,
    startAt: '2026-08-14T00:00:00Z', endAt: '2026-08-28T00:00:00Z',
    completedAt: '2026-08-28T09:00:00Z', daysRemaining: null,
    issueCount: 3, doneIssueCount: 2,
    points: null, donePoints: null, unestimatedCount: 0,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-28T09:00:00Z',
    ...over,
  }
}

function issue(over: Partial<SprintReviewIssue> = {}): SprintReviewIssue {
  return {
    issueId: 'i1', key: 'PAY-1', title: 'Ship the exporter', typeId: 't1',
    assigneeId: 'u1', statusId: 'st1', points: 3,
    closedAt: '2026-08-20T10:00:00Z', deleted: false, ...over,
  }
}

function list(issues: SprintReviewIssue[]) {
  const estimated = issues.filter(i => typeof i.points === 'number')
  return {
    issues,
    count: issues.length,
    // A SUM, so 0 and never null — "nobody estimated" is said by unestimatedCount.
    points: estimated.reduce((n, i) => n + (i.points ?? 0), 0),
    unestimatedCount: issues.length - estimated.length,
  }
}

const CARRIED = issue({ issueId: 'i2', key: 'PAY-2', title: 'Rewrite the importer', statusId: 'st2', points: 5, closedAt: null })
const ADDED = issue({ issueId: 'i3', key: 'PAY-3', title: 'Hotfix the webhook', points: 2 })
/** Deleted since the sprint ran: only the ledger's snapshot survives. */
const GONE: SprintReviewIssue = {
  issueId: null, key: 'PAY-9', title: null, typeId: null,
  assigneeId: null, statusId: null, points: 8, closedAt: null, deleted: true,
}

function reviewReport(over: Partial<SprintReviewReport> = {}): SprintReviewReport {
  return {
    sprint: { id: 's1', name: 'Sprint 12', state: 'COMPLETED' },
    startAt: '2026-08-14T00:00:00Z', endAt: '2026-08-28T00:00:00Z',
    completedAt: '2026-08-28T09:00:00Z',
    committed: list([issue(), CARRIED, GONE]),
    addedAfterStart: list([ADDED]),
    removedBeforeEnd: list([GONE]),
    completed: list([issue(), ADDED]),
    carriedOver: list([CARRIED]),
    totals: {
      committedCount: 3, committedPoints: 16,
      // completed (2) + carried over (1), and their sums — the denominator.
      atEndCount: 3, atEndPoints: 10,
      completedCount: 2, completedPoints: 5,
      addedAfterStartCount: 1,
    },
    meta: {
      computedAt: '2026-08-29T09:00:00Z', basedOnIssues: 4, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

beforeEach(() => {
  mockState.delivery = { board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM' } as ProjectDelivery
  mockState.permissions = ['project.edit']
  mockState.sprints = [sprint()]
  mockState.review = reviewReport()
  mockState.reviewError = null
  vi.mocked(reportsApi.sprintReview).mockClear()
})

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname + loc.search}</div>
}

function renderPage(url = '/w/w1/p/p1/reports/sprint-review') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <LocationProbe />
        <Routes>
          <Route path="/w/:wsId/p/:projectId/reports/sprint-review" element={<SprintReviewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function noteWith(pattern: RegExp): Promise<HTMLElement> {
  return await waitFor(() => {
    const hit = screen.getAllByRole('note').find(n => pattern.test(n.textContent ?? ''))
    if (!hit) throw new Error(`no note matching ${pattern}`)
    return hit
  })
}

/** The card whose heading is this list's name. */
function section(title: string): HTMLElement {
  const heading = screen.getByRole('heading', { name: title })
  return heading.closest('div')!.parentElement as HTMLElement
}

describe('SprintReviewPage — five lists, not a chart', () => {
  it('renders every one of the five, in the order a retro reads them', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Committed' })
    const headings = screen.getAllByRole('heading', { level: 2 }).map(h => h.textContent)
    expect(headings).toEqual([
      'Committed', 'Added after start', 'Removed before end', 'Completed', 'Carried over',
    ])
  })

  it('gives each list its own count and point sum', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Committed' })
    // The count and the sum appear twice on purpose — once as the card's header
    // and once as the table's caption, which is the screen-reader reading.
    expect(section('Committed')).toHaveTextContent('3 issues')
    expect(section('Committed')).toHaveTextContent('16 points')
    expect(section('Carried over')).toHaveTextContent('1 issue')
    expect(section('Carried over')).toHaveTextContent('5 points')
  })

  it('reads as the one header line the spec pins', async () => {
    renderPage()
    // The denominator is what the sprint HELD AT ITS END — completed plus
    // carried over, which completed is a subset of by construction.
    expect(await screen.findByText(/completed 2 of 3 issues \(5 of 10 points\)/)).toBeInTheDocument()
    expect(screen.getByText(/1 added after start/)).toBeInTheDocument()
  })

  it('defines the denominator instead of leaving two numbers to be compared blind', async () => {
    renderPage()
    expect(await screen.findByText(/is what this sprint/)).toBeInTheDocument()
    expect(screen.getByText(/It committed to/)).toBeInTheDocument()
  })

  it('says something in an empty list rather than showing a blank card', async () => {
    mockState.review = reviewReport({ removedBeforeEnd: list([]) })
    renderPage()
    expect(await screen.findByText('Nothing was taken out of this sprint.')).toBeInTheDocument()
  })

  it('lists each issue with its key, title, type, assignee, points and status', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Completed' })
    const table = within(section('Completed')).getByRole('table')
    const row = within(table).getAllByRole('row')[1]
    expect(row).toHaveTextContent('PAY-1')
    expect(row).toHaveTextContent('Ship the exporter')
    // Type and status come from the project config, by id — never hardcoded.
    expect(row).toHaveTextContent('Story')
    expect(row).toHaveTextContent('Done')
    expect(row).toHaveTextContent('Alex Doe')
    expect(row).toHaveTextContent('3')
  })

  it('links every live issue with an ABSOLUTE path (the splat-route trap)', async () => {
    renderPage()
    const links = await screen.findAllByRole('link', { name: 'PAY-1' })
    expect(links[0]).toHaveAttribute('href', '/w/w1/p/p1/issues/1')
  })
})

describe('SprintReviewPage — a finished sprint’s record does not shed rows', () => {
  it('lists an issue deleted since, named, from the ledger’s snapshot', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Committed' })
    const table = within(section('Committed')).getByRole('table')
    expect(within(table).getByText(/PAY-9/)).toBeInTheDocument()
    // The points it carried when it entered are still the record.
    expect(within(table).getByText('8')).toBeInTheDocument()
  })

  it('does not pretend a deleted issue is still openable', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Committed' })
    expect(screen.queryByRole('link', { name: /PAY-9/ })).toBeNull()
    expect(screen.getAllByText('(deleted)').length).toBeGreaterThan(0)
  })

  it('says how many rows that is, rather than leaving a dash to be discovered', async () => {
    renderPage()
    const note = await noteWith(/deleted since this sprint ran/)
    expect(note).toHaveTextContent('1 issue')
    expect(note).toHaveTextContent(/must not quietly lose rows/)
  })

  it('says nothing about deletions when nothing was deleted', async () => {
    mockState.review = reviewReport({
      committed: list([issue()]), removedBeforeEnd: list([]),
    })
    renderPage()
    await screen.findByRole('heading', { name: 'Committed' })
    expect(screen.queryByText(/deleted since this sprint ran/)).not.toBeInTheDocument()
  })
})

describe('SprintReviewPage — points are the entry snapshot', () => {
  it('says so, in the opposite direction from the burn-up', async () => {
    renderPage()
    expect(await screen.findByText(/what each issue weighed when it entered this sprint/))
      .toBeInTheDocument()
  })

  it('labels the column for what it is', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Completed' })
    expect(within(section('Completed')).getByText('Points on entry')).toBeInTheDocument()
  })

  it('counts unestimated rows instead of reading a partial sum as complete', async () => {
    mockState.review = reviewReport({
      completed: list([issue(), issue({ issueId: 'i5', key: 'PAY-5', points: null })]),
    })
    renderPage()
    await screen.findByRole('heading', { name: 'Completed' })
    expect(within(section('Completed')).getByText(/1 unestimated/)).toBeInTheDocument()
  })
})

describe('SprintReviewPage — the capability gates this UI and nothing else', () => {
  it('shows a Kanban project the way to turn sprints on', async () => {
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    renderPage()
    expect(await screen.findByRole('link', { name: /Turn on Scrum in project settings/ }))
      .toHaveAttribute('href', '/w/w1/p/p1/settings/delivery')
    expect(screen.queryByRole('heading', { name: 'Committed' })).toBeNull()
  })

  it('is not decided by whether the project has sprints', async () => {
    // Data presence is not an answer to "does this project run sprints?".
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    mockState.sprints = [sprint(), sprint({ id: 's2', name: 'Sprint 11' })]
    renderPage()
    expect(await screen.findByRole('link', { name: /Turn on Scrum/ })).toBeInTheDocument()
  })

  it('renders the record for a project that has never estimated — points are values, not controls', async () => {
    mockState.delivery = { board: 'SCRUM', releases: true, estimation: false, preset: 'CUSTOM' }
    renderPage()
    await screen.findByRole('heading', { name: 'Completed' })
    // Rule B: no measure toggle exists on this page at all, and every recorded
    // point still renders.
    expect(within(section('Completed')).getByText('Points on entry')).toBeInTheDocument()
    expect(screen.queryByLabelText('Measure')).not.toBeInTheDocument()
  })
})

describe('SprintReviewPage — the sprint, and the URL that names it', () => {
  it('sends the resolved sprint and no measure', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Committed' })
    expect(reportsApi.sprintReview).toHaveBeenCalledWith('w1', 'p1', { sprintId: 's1' })
  })

  it('writes the chosen sprint into the URL', async () => {
    mockState.sprints = [sprint(), sprint({ id: 's2', name: 'Sprint 11' })]
    renderPage()
    await screen.findByRole('heading', { name: 'Committed' })

    await userEvent.click(screen.getByLabelText('Sprint'))
    await userEvent.click(await screen.findByText('Sprint 11'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('sprintId=s2'))
  })

  it('offers the picker when the sprint is gone', async () => {
    mockState.reviewError = new FakeApiError(404, 'Sprint not found')
    renderPage()
    expect(await noteWith(/That sprint is no longer here/)).toBeInTheDocument()
  })

  it('renders a 429 as a wait, not as a fault', async () => {
    mockState.reviewError = new FakeApiError(429, 'Too many requests', 7)
    renderPage()
    expect(await screen.findByText(/Too many report requests/)).toBeInTheDocument()
  })

  it('never leaves an unrecognised failure silent', async () => {
    mockState.reviewError = new Error('socket hang up')
    renderPage()
    expect(await screen.findByText(/Couldn’t load the sprint review/)).toBeInTheDocument()
  })

  it('prints the provenance line', async () => {
    renderPage()
    expect(await screen.findByText(/based on 4 issues/)).toBeInTheDocument()
  })
})
