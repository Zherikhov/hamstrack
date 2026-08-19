import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import SprintBurnupPage from './SprintBurnupPage'
import { reportsApi } from '../../api'
import type { ProjectDelivery, Sprint, SprintBurnupReport } from '../../types'

/**
 * HD-29 (R4) — the burn-up's contract, which is a set of sentences and a set of
 * refusals rather than a picture:
 *
 *  1. **Capabilities gate the UI and never the API.** A Kanban project gets the
 *     Rule C card with the way to turn sprints on — visible *while the
 *     capability is off* — and the decision reads the DECLARED capability, never
 *     whether sprints exist in the data (the bug this model was built to delete,
 *     which shipped once).
 *  2. **With estimation off there is no points toggle**, the Rule C affordance
 *     sits where it would have been, and points the project already recorded
 *     keep rendering read-only in the scope-change log (Rule B).
 *  3. **The line ends where it ends.** Days after today carry no value at all —
 *     not zero, not the last value held flat — because a flat line to the
 *     sprint's end is a forecast, and forecasting is R5 with a sample size.
 *  4. **The ideal guide is drawn to the COMMITTED scope**, so adding work never
 *     moves the line the sprint is read against.
 *  5. **Every step is explainable**: the scope-change log names the issue, the
 *     actor and the delta — and an issue deleted since is still named, from the
 *     ledger's snapshot, without pretending to link to it.
 *  6. Report state — the sprint AND the measure — lives in the URL (§4.4).
 */

vi.mock('./BurnupChart', () => ({
  default: ({ rows, measure }: { rows: { scope: number | null }[]; measure: string }) => (
    <div data-testid="burnup-chart">
      {`burnup:${rows.length}:${measure}:drawn=${rows.filter(r => r.scope != null).length}`}
    </div>
  ),
}))

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
  burnup: null as unknown,
  burnupError: null as unknown,
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
    sprintBurnup: vi.fn(async () => {
      if (mockState.burnupError) throw mockState.burnupError
      return mockState.burnup
    }),
  },
}))

const DAY = 86_400_000
/** A UTC day relative to today, so "after today" is a fact and not a fixture date. */
function day(offset: number): string {
  return new Date(Date.now() + offset * DAY).toISOString().slice(0, 10)
}

function sprint(over: Partial<Sprint> = {}): Sprint {
  return {
    id: 's1', name: 'Sprint 12', state: 'ACTIVE', sequence: 12,
    startAt: `${day(-3)}T00:00:00Z`, endAt: `${day(3)}T00:00:00Z`, completedAt: null,
    daysRemaining: 3, issueCount: 7, doneIssueCount: 4,
    points: null, donePoints: null, unestimatedCount: 0,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-14T00:00:00Z',
    ...over,
  }
}

function burnupReport(over: Partial<SprintBurnupReport> = {}): SprintBurnupReport {
  return {
    sprint: { id: 's1', name: 'Sprint 12', state: 'ACTIVE' },
    startAt: `${day(-3)}T00:00:00Z`,
    endAt: `${day(3)}T00:00:00Z`,
    measure: 'COUNT',
    committedAtStart: 6,
    unestimatedCount: 0,
    series: [
      { date: day(-3), scope: 6, completed: 0 },
      { date: day(-2), scope: 6, completed: 1 },
      { date: day(-1), scope: 7, completed: 2 },
      { date: day(0), scope: 7, completed: 4 },
    ],
    scopeChanges: [
      {
        at: `${day(-1)}T10:00:00Z`, issueId: 'i7', key: 'PAY-77', event: 'ADDED',
        delta: 1, actorId: 'u1', storyPoints: 3,
      },
      {
        // Deleted since: the ledger keeps the key and the points it carried.
        at: `${day(-1)}T14:00:00Z`, issueId: null, key: 'PAY-14', event: 'REMOVED',
        delta: -1, actorId: null, storyPoints: 5,
      },
    ],
    seriesTruncatedAt: null,
    meta: {
      computedAt: '2026-08-19T09:00:00Z', basedOnIssues: 7, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

beforeEach(() => {
  mockState.delivery = { board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM' } as ProjectDelivery
  mockState.permissions = ['project.edit']
  mockState.sprints = [sprint()]
  mockState.burnup = burnupReport()
  mockState.burnupError = null
  vi.mocked(reportsApi.sprintBurnup).mockClear()
})

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname + loc.search}</div>
}

function renderPage(url = '/w/w1/p/p1/reports/sprint-burnup') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <LocationProbe />
        <Routes>
          <Route path="/w/:wsId/p/:projectId/reports/sprint-burnup" element={<SprintBurnupPage />} />
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

describe('SprintBurnupPage — the board capability gates the UI, and only the UI', () => {
  it('replaces the report with the way to turn sprints on, on a Kanban project', async () => {
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    renderPage()
    const link = await screen.findByRole('link', { name: /Turn on Scrum in project settings/ })
    // Absolute — this page lives inside the /reports/* splat, where a relative
    // path would resolve after the splat segment.
    expect(link).toHaveAttribute('href', '/w/w1/p/p1/settings/delivery')
    expect(screen.queryByTestId('burnup-chart')).not.toBeInTheDocument()
    // …and nothing was requested: the endpoint would have answered, but there is
    // no sprint on this screen to ask about.
    expect(reportsApi.sprintBurnup).not.toHaveBeenCalled()
  })

  it('does NOT decide that from the data — a Kanban project with sprints is still Kanban', async () => {
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    mockState.sprints = [sprint(), sprint({ id: 's2', name: 'Sprint 11', state: 'COMPLETED' })]
    renderPage()
    expect(await screen.findByRole('link', { name: /Turn on Scrum/ })).toBeInTheDocument()
    expect(screen.queryByLabelText('Sprint')).not.toBeInTheDocument()
  })

  it('offers a plain member no verb at all', async () => {
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    mockState.permissions = []
    renderPage()
    expect(await screen.findByText('This project plans as one ranked list.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Turn on Scrum/ })).toBeNull()
  })

  it('says a Scrum project simply has no sprints yet, without blaming the capability', async () => {
    mockState.sprints = []
    renderPage()
    expect(await screen.findByText(/This project has no sprints yet/)).toBeInTheDocument()
    expect(screen.getByText(/This project plans in sprints/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Plan the first sprint on the Backlog/ }))
      .toHaveAttribute('href', '/w/w1/p/p1/backlog')
  })
})

describe('SprintBurnupPage — which sprint, and the URL that says so', () => {
  it('defaults to the running sprint and names it on the wire', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(reportsApi.sprintBurnup).toHaveBeenCalledWith('w1', 'p1', { sprintId: 's1', measure: 'COUNT' })
  })

  it('says when it is showing a finished sprint because none is running', async () => {
    mockState.sprints = [sprint({ id: 'c9', name: 'Sprint 11', state: 'COMPLETED' })]
    renderPage()
    const note = await noteWith(/No sprint is running/)
    expect(note).toHaveTextContent('Sprint 11')
  })

  it('never substitutes another sprint for a link naming one this project lacks', async () => {
    renderPage('/w/w1/p/p1/reports/sprint-burnup?sprintId=ghost')
    const note = await noteWith(/names a sprint/)
    expect(note).toHaveTextContent('ghost')
    expect(note).toHaveTextContent(/Nothing has been substituted/)
  })

  it('writes the chosen sprint into the URL', async () => {
    mockState.sprints = [sprint(), sprint({ id: 's2', name: 'Sprint 11', state: 'COMPLETED' })]
    renderPage()
    await screen.findByTestId('burnup-chart')

    await userEvent.click(screen.getByLabelText('Sprint'))
    await userEvent.click(await screen.findByText('Sprint 11'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('sprintId=s2'))
  })
})

describe('SprintBurnupPage — the measure, and what estimation off does to it', () => {
  it('puts the measure in the URL and on the wire — it is a different sum', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(reportsApi.sprintBurnup).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByLabelText('Measure'))
    await userEvent.click(await screen.findByText('Story points'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('measure=POINTS'))
    // Unlike the cycle-time toggle this one really does refetch: the server sums
    // different rows for it and the response carries only one series.
    await waitFor(() => expect(reportsApi.sprintBurnup)
      .toHaveBeenCalledWith('w1', 'p1', { sprintId: 's1', measure: 'POINTS' }))
  })

  it('omits the toggle with estimation off, and offers the way to turn it on', async () => {
    mockState.delivery = { board: 'SCRUM', releases: true, estimation: false, preset: 'CUSTOM' }
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(screen.queryByLabelText('Measure')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Turn on estimation to chart story points/ }))
      .toHaveAttribute('href', '/w/w1/p/p1/settings/delivery')
  })

  it('tells a plain member estimation is off rather than teaching them the verb', async () => {
    mockState.delivery = { board: 'SCRUM', releases: true, estimation: false, preset: 'CUSTOM' }
    mockState.permissions = []
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(screen.getByText('Story-point estimation is off for this project.')).toBeInTheDocument()
  })

  it('answers a points link with counts, and says so instead of pretending', async () => {
    mockState.delivery = { board: 'SCRUM', releases: true, estimation: false, preset: 'CUSTOM' }
    renderPage('/w/w1/p/p1/reports/sprint-burnup?measure=POINTS')
    await screen.findByTestId('burnup-chart')
    expect(reportsApi.sprintBurnup).toHaveBeenCalledWith('w1', 'p1', { sprintId: 's1', measure: 'COUNT' })
    const note = await noteWith(/asks for/)
    expect(note).toHaveTextContent(/counts issues instead/)
  })

  it('keeps recorded points visible in the log with estimation off (Rule B)', async () => {
    mockState.delivery = { board: 'SCRUM', releases: true, estimation: false, preset: 'CUSTOM' }
    renderPage()
    await screen.findByTestId('burnup-chart')
    const tables = screen.getAllByRole('table')
    const log = tables[tables.length - 1]
    expect(within(log).getByText('Points at the time')).toBeInTheDocument()
    expect(within(log).getByText('3')).toBeInTheDocument()
  })

  it('says out loud when unestimated issues are counting as zero points', async () => {
    mockState.burnup = burnupReport({ measure: 'POINTS', unestimatedCount: 4 })
    renderPage('/w/w1/p/p1/reports/sprint-burnup?measure=POINTS')
    const note = await noteWith(/no\s+estimate/)
    expect(note).toHaveTextContent('4 issues in this sprint have no')
    expect(note).toHaveTextContent(/count as zero points/)
  })
})

describe('SprintBurnupPage — the line ends where it ends', () => {
  it('draws nothing for a day that has not happened', async () => {
    renderPage()
    const chart = await screen.findByTestId('burnup-chart')
    // Seven days of sprint, four of them measured — the remaining three are not
    // zero and not held flat, they are absent.
    expect(chart).toHaveTextContent('burnup:7:COUNT:drawn=4')
  })

  it('refuses a projection in words as well as in geometry', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(screen.getByText(/no projection here and no trend line/)).toBeInTheDocument()
  })

  it('names the committed scope as what the ideal guide is drawn to', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(screen.getByText(/Committed at start/)).toBeInTheDocument()
    expect(screen.getByText(/not\s+to its scope now/)).toBeInTheDocument()
  })
})

describe('SprintBurnupPage — the scope-change log', () => {
  it('names the issue, the change, the delta and who moved it', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    const tables = screen.getAllByRole('table')
    const log = tables[tables.length - 1]
    const rows = within(log).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('PAY-77')
    expect(rows[1]).toHaveTextContent('Added to sprint')
    expect(rows[1]).toHaveTextContent('+1')
    expect(rows[1]).toHaveTextContent('Alex Doe')
  })

  it('links a live issue with an ABSOLUTE path', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(await screen.findByRole('link', { name: 'PAY-77' }))
      .toHaveAttribute('href', '/w/w1/p/p1/issues/77')
  })

  it('still names an issue deleted since, and does not link to it', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    // The ledger row outlives the issue on purpose, so the scope arithmetic keeps
    // both of its steps. Hiding the row would put the chart and the log at odds.
    const tables = screen.getAllByRole('table')
    const log = tables[tables.length - 1]
    expect(within(log).getByText(/PAY-14/)).toBeInTheDocument()
    expect(within(log).getByText('(deleted)')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /PAY-14/ })).toBeNull()
  })

  it('says a sprint kept its scope rather than showing an empty table', async () => {
    mockState.burnup = burnupReport({ scopeChanges: [] })
    renderPage()
    expect(await screen.findByText(/ran with the scope it was committed to/)).toBeInTheDocument()
  })

  it('states that a re-estimate is not a scope change', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(screen.getByText(/A re-estimate is not a scope change/)).toBeInTheDocument()
  })
})

describe('SprintBurnupPage — refusals are real messages', () => {
  it('offers the picker when the sprint is gone', async () => {
    mockState.burnupError = new FakeApiError(404, 'Sprint not found')
    renderPage()
    const note = await noteWith(/That sprint is no longer here/)
    expect(note).toHaveTextContent(/pick one from the list above/)
  })

  it('renders a 429 as a wait, not as a fault', async () => {
    mockState.burnupError = new FakeApiError(429, 'Too many requests', 9)
    renderPage()
    expect(await screen.findByText(/Too many report requests/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled()
  })

  it('never leaves an unrecognised failure silent', async () => {
    mockState.burnupError = new Error('socket hang up')
    renderPage()
    expect(await screen.findByText(/Couldn’t load the burn-up/)).toBeInTheDocument()
  })

  it('prints the provenance line', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(screen.getByText(/based on 7 issues/)).toBeInTheDocument()
    expect(screen.getByText(/UTC day boundaries/)).toBeInTheDocument()
  })

  it('says the report is truncated, above the chart', async () => {
    mockState.burnup = burnupReport({
      meta: {
        computedAt: '2026-08-19T09:00:00Z', basedOnIssues: 20000, truncated: true, cap: 20000,
        firstIssueAt: null, unmatchedFilters: [],
      },
    })
    renderPage()
    const notice = await screen.findByText(/truncated/)
    const chart = await screen.findByTestId('burnup-chart')
    expect(notice.compareDocumentPosition(chart) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

describe('SprintBurnupPage — the picker never disagrees with the page', () => {
  it('shows an unknown pinned sprint as unknown instead of displaying another one', async () => {
    mockState.sprints = [sprint({ id: 's1', name: 'Sprint 12' })]
    renderPage('/w/w1/p/p1/reports/sprint-burnup?sprintId=ghost')
    await noteWith(/names a sprint/)
    // The control would otherwise fall through to the first option and read
    // "Sprint 12 (running)" while the page says it could not find the sprint.
    expect(screen.getByLabelText('Sprint')).toHaveTextContent('Unknown sprint')
  })
})

describe('SprintBurnupPage — two different limits, two different sentences', () => {
  it('names the DAY the chart stops at, and never quotes the row cap for it', async () => {
    // A backdated sprint hits the day bound, not `app.reports.max-rows`. Before
    // round 2 this rode on `meta.truncated` and a twelve-issue sprint answered
    // "computed from the most recent 20 000 issues" — a banner quoting a cap
    // that had dropped nothing.
    mockState.burnup = burnupReport({ seriesTruncatedAt: day(-1) })
    renderPage()
    const note = await noteWith(/longer than this instance charts/)
    expect(note).toHaveTextContent(day(-1))
    expect(note).toHaveTextContent(/first/)
    // …and the row-cap banner is absent, because the row cap did not bite.
    expect(screen.queryByText(/most recent/)).not.toBeInTheDocument()
    // The review has no such bound, and the page says so rather than leaving the
    // two reports to disagree silently.
    expect(note).toHaveTextContent(/sprint review/)
  })

  it('says nothing about the day bound when the whole sprint is drawn', async () => {
    renderPage()
    await screen.findByTestId('burnup-chart')
    expect(screen.queryByText(/longer than this instance charts/)).not.toBeInTheDocument()
  })

  it('still prints the row-cap banner for the limit it actually means', async () => {
    mockState.burnup = burnupReport({
      meta: {
        computedAt: '2026-08-19T09:00:00Z', basedOnIssues: 20000, truncated: true, cap: 20000,
        firstIssueAt: null, unmatchedFilters: [],
      },
    })
    renderPage()
    expect(await screen.findByText(/truncated/)).toBeInTheDocument()
    expect(screen.queryByText(/longer than this instance charts/)).not.toBeInTheDocument()
  })
})

describe('SprintBurnupPage — the log carries the ledger’s own snapshot', () => {
  it('always shows the points column, whatever the measure is', async () => {
    // Rule B: the value is the snapshot on the event row, not a re-reading of
    // `delta`, so a COUNT chart — which is every project with estimation off —
    // still shows the estimates the project had already recorded.
    renderPage()
    await screen.findByTestId('burnup-chart')
    const tables = screen.getAllByRole('table')
    const log = tables[tables.length - 1]
    expect(within(log).getByText('Points at the time')).toBeInTheDocument()
    expect(within(log).getAllByRole('row')[1]).toHaveTextContent('3')
  })

  it('treats a null actor as ordinary, not as an error', async () => {
    // Two reasons, neither a fault: the account is gone, or the ISSUE is — and
    // attribution is dropped on a deleted issue's rows deliberately, so this log
    // does not become the last place naming who touched it.
    renderPage()
    await screen.findByTestId('burnup-chart')
    const tables = screen.getAllByRole('table')
    const rows = within(tables[tables.length - 1]).getAllByRole('row')
    expect(rows[2]).toHaveTextContent('PAY-14')
    expect(rows[2]).toHaveTextContent('not recorded')
    expect(screen.getByText(/rows of a deleted issue, where naming a person/))
      .toBeInTheDocument()
  })
})
