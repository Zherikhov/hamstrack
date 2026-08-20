import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import VelocityPage from './VelocityPage'
import { apiListWorkspaceMembers, reportsApi } from '../../api'
import type { ProjectDelivery, VelocityReport, VelocitySprint } from '../../types'

/**
 * HD-139 (R5) — velocity, and the four refusals that ARE the feature (§1.4).
 *
 * The research this report is a redesign of says the harm is in the audience,
 * not the chart: velocity *"was never intended to be used to compare two teams"*,
 * and when it escapes the team *"leaders misinterpret higher story point
 * averages to mean one team is more productive"*. So the assertions below are
 * mostly about what may NOT appear:
 *
 *  1. **no per-person breakdown** — not a filter, not a tooltip, not a legend;
 *     this page does not even fetch the member list, so rendering one would take
 *     new code rather than a new prop;
 *  2. **no cross-project or workspace view** — every link on the page stays
 *     inside this project, and there is no comparison affordance. That friction
 *     is the design;
 *  3. **the caption is permanent** — on screen in both measures and while the
 *     band is suppressed, never behind a tooltip;
 *  4. **fewer than three completed sprints and the band is withheld**, with the
 *     sample size stated and the bars still drawn.
 *
 * Plus the two rules every report in this epic shares: the capability gates the
 * UI and never the API, and the whole report state lives in the URL.
 */

vi.mock('./VelocityChart', () => ({
  default: ({ rows, measure, band }: {
    rows: unknown[]
    measure: string
    band: { kind: string }
  }) => (
    <div data-testid="velocity-chart">{`velocity:${rows.length}:${measure}:${band.kind}`}</div>
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
  velocity: null as unknown,
  velocityError: null as unknown,
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
  apiGetProjectConfig: vi.fn(),
  apiListWorkspaceMembers: vi.fn(async () => ([
    { userId: 'u1', email: 'alex@example.com', displayName: 'Alex Doe' },
  ])),
  sprintsApi: { list: vi.fn(async () => ({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 1, hasNext: false })) },
  reportsApi: {
    velocity: vi.fn(async () => {
      if (mockState.velocityError) throw mockState.velocityError
      return mockState.velocity
    }),
  },
}))

function sprint(over: Partial<VelocitySprint> = {}): VelocitySprint {
  return {
    sprintId: 's1', name: 'Sprint 7',
    startAt: '2026-07-01T09:00:00Z', completedAt: '2026-07-15T09:00:00Z',
    committed: 21, completed: 18, addedAfterStart: 4, carriedOver: 3,
    unestimatedCount: 0, ...over,
  }
}

const SIX = [
  sprint({ sprintId: 's1', name: 'Sprint 7', completed: 14 }),
  sprint({ sprintId: 's2', name: 'Sprint 8', completed: 20 }),
  sprint({ sprintId: 's3', name: 'Sprint 9', completed: 23 }),
  sprint({ sprintId: 's4', name: 'Sprint 10', completed: 18 }),
  sprint({ sprintId: 's5', name: 'Sprint 11', completed: 16 }),
  sprint({ sprintId: 's6', name: 'Sprint 12', completed: 21 }),
]

/** How the server signals suppression: present object, null percentiles. */
const SUPPRESSED_2 = { p50: null, p85: null, sampleSize: 2 }

function velocityReport(over: Partial<VelocityReport> = {}): VelocityReport {
  return {
    measure: 'COUNT',
    sprints: SIX,
    forecast: { p50: 18, p85: 23, sampleSize: 6 },
    meta: {
      computedAt: '2026-08-20T09:00:00Z', basedOnIssues: 112, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

beforeEach(() => {
  mockState.delivery = { board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM' } as ProjectDelivery
  mockState.permissions = ['project.edit']
  mockState.velocity = velocityReport()
  mockState.velocityError = null
  vi.mocked(reportsApi.velocity).mockClear()
  vi.mocked(apiListWorkspaceMembers).mockClear()
})

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname + loc.search}</div>
}

function renderPage(url = '/w/w1/p/p1/reports/velocity') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <LocationProbe />
        <Routes>
          <Route path="/w/:wsId/p/:projectId/reports/velocity" element={<VelocityPage />} />
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

describe('VelocityPage — the band is the deliverable', () => {
  it('prints the sentence §2.5 pins, with the sample size', async () => {
    renderPage()
    expect(await screen.findByText(
      /Recent sprints delivered between 14 and 23 issues; plan for ~18 \(p50\) and treat 23 \(p85\) as a stretch\. Based on 6 sprints\./,
    )).toBeInTheDocument()
  })

  it('draws the bars beside it, from the same rows', async () => {
    renderPage()
    expect(await screen.findByTestId('velocity-chart')).toHaveTextContent('velocity:6:COUNT:BAND')
  })

  it('ships the table equivalent under the chart, same numbers same order', async () => {
    renderPage()
    const table = await screen.findByRole('table')
    const rows = screen.getAllByRole('row').slice(1)
    expect(rows).toHaveLength(6)
    expect(rows[0]).toHaveTextContent('Sprint 7')
    expect(rows[0]).toHaveTextContent('21')
    expect(rows[0]).toHaveTextContent('14')
    expect(table).toHaveTextContent('The last 6 completed sprints')
  })

  it('labels the two percentiles as a plan and a stretch, not as targets', async () => {
    renderPage()
    expect(await screen.findByText('p50 — plan for')).toBeInTheDocument()
    expect(screen.getByText('p85 — a stretch')).toBeInTheDocument()
    expect(screen.getByText(/turned a forecast back into a quota/)).toBeInTheDocument()
  })

  it('prints the provenance line', async () => {
    renderPage()
    expect(await screen.findByText(/based on 112 issues/)).toBeInTheDocument()
  })
})

describe('VelocityPage — fewer than three sprints withholds the forecast', () => {
  it('states the sample size instead of drawing a band', async () => {
    mockState.velocity = velocityReport({ sprints: SIX.slice(0, 2), forecast: SUPPRESSED_2 })
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Not enough history to forecast' }))
      .toBeInTheDocument()
    expect(screen.getByText(/2 sprints have finished here/)).toBeInTheDocument()
    expect(screen.getByText(/at least 3/)).toBeInTheDocument()
    expect(screen.queryByText('p50 — plan for')).toBeNull()
  })

  it('still draws the bars — the record is a fact, only the forecast is withheld', async () => {
    mockState.velocity = velocityReport({ sprints: SIX.slice(0, 2), forecast: SUPPRESSED_2 })
    renderPage()
    expect(await screen.findByTestId('velocity-chart')).toHaveTextContent('velocity:2:COUNT:SUPPRESSED')
    expect(screen.getAllByRole('row').slice(1)).toHaveLength(2)
  })

  it('says the plain thing when no sprint has finished at all', async () => {
    mockState.velocity = velocityReport({
      sprints: [], forecast: { p50: null, p85: null, sampleSize: 0 },
    })
    renderPage()
    expect(await screen.findByRole('heading', { name: 'No sprint has finished here yet' }))
      .toBeInTheDocument()
    expect(screen.queryByTestId('velocity-chart')).toBeNull()
    // …and the provenance still prints, because an empty report is a computed
    // answer and not an absence of one.
    expect(screen.getByText(/based on 112 issues/)).toBeInTheDocument()
  })
})

describe('VelocityPage — unsized work, and what it does to the band', () => {
  const UNSIZED = [
    sprint({ sprintId: 's1', name: 'Sprint 7', completed: 14, unestimatedCount: 4 }),
    sprint({ sprintId: 's2', name: 'Sprint 8', completed: 20, unestimatedCount: 0 }),
    sprint({ sprintId: 's3', name: 'Sprint 9', completed: 23, unestimatedCount: 5 }),
    sprint({ sprintId: 's4', name: 'Sprint 10', completed: 18, unestimatedCount: 0 }),
  ]

  it('says the forecast itself is biased low under points — not just the bars', async () => {
    // This is the whole reason the field matters more here than on the burn-up:
    // the band is computed FROM the bars, so every silent zero lands in p50/p85.
    mockState.velocity = velocityReport({ sprints: UNSIZED })
    renderPage('/w/w1/p/p1/reports/velocity?measure=POINTS')
    const note = await noteWith(/no estimate/)
    expect(note).toHaveTextContent('9 issues across 2 of these 4 sprints have no estimate')
    expect(note).toHaveTextContent(/p50 and p85 are biased low/)
    expect(note).toHaveTextContent(/zero points/)
  })

  it('still shows the figure under counts, and says it distorts nothing there', async () => {
    // The same number means a different thing per measure, so it says a
    // different thing — a reader flipping the toggle must not have to work out
    // which reading applies.
    mockState.velocity = velocityReport({ sprints: UNSIZED })
    renderPage()
    const note = await noteWith(/no estimate/)
    expect(note).toHaveTextContent('9 issues across 2 of these 4 sprints have no estimate')
    expect(note).toHaveTextContent(/nothing here is understated/)
    expect(note).not.toHaveTextContent(/biased low/)
  })

  it('says nothing at all when every issue was sized', async () => {
    renderPage()
    await screen.findByTestId('velocity-chart')
    expect(screen.queryByText(/no estimate/)).toBeNull()
  })

  it('puts the count per sprint in the table equivalent, in issues', async () => {
    mockState.velocity = velocityReport({ sprints: UNSIZED })
    renderPage('/w/w1/p/p1/reports/velocity?measure=POINTS')
    await screen.findByRole('table')
    // Labelled in ISSUES whatever the measure: a point sum of unestimated work
    // is zero by definition, so the selected unit would print a column of noughts.
    expect(screen.getByText('Unestimated (issues)')).toBeInTheDocument()
    const rows = screen.getAllByRole('row').slice(1)
    expect(rows[0]).toHaveTextContent('4')
    expect(rows[1]).toHaveTextContent('—')
  })

  it('prints the chronology the payload carries, rather than inferring it from names', async () => {
    renderPage()
    await screen.findByRole('table')
    expect(screen.getByText('Completed (UTC)')).toBeInTheDocument()
    expect(screen.getAllByRole('row')[1]).toHaveTextContent('2026-07-15')
  })
})

describe('VelocityPage — the refusals (§1.4), which are the feature', () => {
  it('keeps the caption permanently on screen, in both measures', async () => {
    renderPage()
    expect(await screen.findByText(
      'Story points are team-relative. Velocity is not comparable between teams.',
    )).toBeInTheDocument()
  })

  it('keeps it on screen while the band is suppressed too', async () => {
    mockState.velocity = velocityReport({
      sprints: SIX.slice(0, 1), forecast: { p50: null, p85: null, sampleSize: 1 },
    })
    renderPage()
    expect(await screen.findByText(/Velocity is not comparable between teams/)).toBeInTheDocument()
  })

  it('never breaks the numbers down by person — and never even asks who they are', async () => {
    renderPage()
    await screen.findByTestId('velocity-chart')
    // No member list is fetched, so there is nothing on this page a per-person
    // split could be rendered from. That is the point: the refusal is enforced
    // by absence, not by discipline.
    expect(apiListWorkspaceMembers).not.toHaveBeenCalled()
    expect(screen.queryByText(/assignee/i)).toBeNull()
    expect(screen.queryByLabelText(/assignee/i)).toBeNull()
  })

  it('offers no view above this project, and no way to compare two of them', async () => {
    renderPage()
    await screen.findByTestId('velocity-chart')
    for (const link of screen.queryAllByRole('link')) {
      expect(link.getAttribute('href')).toMatch(/^\/w\/w1\/p\/p1\//)
    }
    // No comparison CONTROL of any kind — no second project to pick, nothing to
    // press. The page says the word "compare" only to explain why there is
    // nothing here that does it.
    expect(screen.queryByRole('button', { name: /compare/i })).toBeNull()
    expect(screen.queryByRole('link', { name: /compare/i })).toBeNull()
    expect(screen.queryByLabelText(/project/i)).toBeNull()
    expect(screen.getByText(/no view above this project/i)).toBeInTheDocument()
  })
})

describe('VelocityPage — the capability gates this UI and nothing else', () => {
  it('shows a Kanban project the way to turn sprints on', async () => {
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    renderPage()
    expect(await screen.findByRole('link', { name: /Turn on Scrum in project settings/ }))
      .toHaveAttribute('href', '/w/w1/p/p1/settings/delivery')
    expect(screen.queryByTestId('velocity-chart')).toBeNull()
  })

  it('is not decided by whether the project has sprint data', async () => {
    // Six completed sprints in the response would still not make this project a
    // Scrum project — the DECLARED capability is the only answer, and inferring
    // it from data presence is the bug the model was built to delete.
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    renderPage()
    expect(await screen.findByRole('link', { name: /Turn on Scrum/ })).toBeInTheDocument()
    expect(reportsApi.velocity).not.toHaveBeenCalled()
  })

  it('withholds the points toggle with estimation off, and points at the switch', async () => {
    mockState.delivery = { board: 'SCRUM', releases: true, estimation: false, preset: 'CUSTOM' }
    renderPage()
    await screen.findByTestId('velocity-chart')
    expect(screen.queryByLabelText('Measure')).toBeNull()
    expect(screen.getByRole('link', { name: /Turn on estimation to chart story points/ }))
      .toHaveAttribute('href', '/w/w1/p/p1/settings/delivery')
  })

  it('honours a points link as counts, and says so, when the project does not estimate', async () => {
    mockState.delivery = { board: 'SCRUM', releases: true, estimation: false, preset: 'CUSTOM' }
    renderPage('/w/w1/p/p1/reports/velocity?measure=POINTS')
    expect(await noteWith(/this project does not estimate/)).toBeInTheDocument()
    expect(reportsApi.velocity).toHaveBeenCalledWith('w1', 'p1', { sprints: 6, measure: 'COUNT' })
  })
})

describe('VelocityPage — the report state is the URL (§4.4)', () => {
  it('asks for six sprints and issue counts by default', async () => {
    renderPage()
    await screen.findByTestId('velocity-chart')
    expect(reportsApi.velocity).toHaveBeenCalledWith('w1', 'p1', { sprints: 6, measure: 'COUNT' })
  })

  it('writes the chosen sprint count into the URL and refetches', async () => {
    renderPage()
    await screen.findByTestId('velocity-chart')

    await userEvent.click(screen.getByLabelText('Sprints'))
    await userEvent.click(await screen.findByText('Last 12 sprints'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('sprints=12'))
    await waitFor(() => expect(reportsApi.velocity)
      .toHaveBeenCalledWith('w1', 'p1', { sprints: 12, measure: 'COUNT' }))
  })

  it('writes the measure into the URL, and sends it', async () => {
    renderPage()
    await screen.findByTestId('velocity-chart')

    await userEvent.click(screen.getByLabelText('Measure'))
    await userEvent.click(await screen.findByText('Story points'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('measure=POINTS'))
    await waitFor(() => expect(reportsApi.velocity)
      .toHaveBeenCalledWith('w1', 'p1', { sprints: 6, measure: 'POINTS' }))
  })

  it('clamps an over-long link, says it clamped, and still draws the report', async () => {
    renderPage('/w/w1/p/p1/reports/velocity?sprints=99')
    expect(await noteWith(/asks for 99 sprints/)).toBeInTheDocument()
    expect(reportsApi.velocity).toHaveBeenCalledWith('w1', 'p1', { sprints: 12, measure: 'COUNT' })
    expect(await screen.findByTestId('velocity-chart')).toBeInTheDocument()
  })
})

describe('VelocityPage — failures say which kind they are', () => {
  it('renders a 429 as a wait, not as a fault', async () => {
    mockState.velocityError = new FakeApiError(429, 'Too many requests', 7)
    renderPage()
    expect(await screen.findByText(/Too many report requests/)).toBeInTheDocument()
  })

  it('quotes the cap the server named on a 400', async () => {
    mockState.velocityError = new FakeApiError(400, 'sprints must be between 1 and 12')
    renderPage()
    expect(await noteWith(/sprints must be between 1 and 12/)).toBeInTheDocument()
  })

  it('never leaves an unrecognised failure silent', async () => {
    mockState.velocityError = new Error('socket hang up')
    renderPage()
    expect(await screen.findByText(/Couldn’t load velocity/)).toBeInTheDocument()
  })
})
