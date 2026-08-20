import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import CycleTimeReportPage from './CycleTimeReportPage'
import { reportsApi } from '../../api'
import type { AgingReport, CycleTimeReport } from '../../types'

/**
 * HD-138 — like R1's tests, these are about the **disclosures and the naming**,
 * not about the pixels. Both charts are mocked away (Recharts needs a laid-out
 * DOM jsdom does not provide, and the aging half's geometry has its own test);
 * what is pinned here is every sentence a refactor could silently drop while the
 * page still looked right:
 *
 *  1. `missingStartCount` printed as *"cycle time available for N of M"* — a
 *     cycle-time chart resting quietly on a subset is the failure this whole
 *     epic exists to prevent, and the creation date is never substituted;
 *  2. suppressed percentiles say *"need 5, have 3"* and draw no lines;
 *  3. the sample size printed beside the lines (*"based on 812 issues"*);
 *  4. `truncated` above the chart — this report is row-level, so the cap really
 *     can bite here;
 *  5. `unmatchedFilters` named, so an empty chart is never ambiguous between a
 *     quiet quarter and a typo in a filter;
 *  6. the aging half **names the item**: key, assignee, age, and a word (not
 *     just a colour) for "older than p85";
 *  7. the trailing "Not on this board" column rendered, not dropped;
 *  8. the two halves do not share a window — `/aging` is called with no
 *     parameters and the page says so;
 *  9. the measure toggle lives in the URL but NOT on the wire: flipping it
 *     re-renders from the same response instead of refetching;
 * 10. empty, thin and refused states all say something — never a blank panel.
 */

vi.mock('./CycleTimeChart', () => ({
  default: ({ items, p50, p85 }: { items: unknown[]; p50: number | null; p85: number | null }) =>
    <div data-testid="cycle-chart">{`chart:${items.length}:p50=${p50}:p85=${p85}`}</div>,
}))
vi.mock('./AgingChart', () => ({
  default: ({ columns, p50, p85 }: { columns: { items: unknown[] }[]; p50: number | null; p85: number | null }) =>
    <div data-testid="aging-chart">
      {`aging:${columns.length}:${columns.reduce((n, c) => n + c.items.length, 0)}:p50=${p50}:p85=${p85}`}
    </div>,
}))

const mockState = vi.hoisted(() => ({
  cycle: null as unknown,
  cycleError: null as unknown,
  aging: null as unknown,
  agingError: null as unknown,
}))

const { FakeApiError } = vi.hoisted(() => {
  class FakeApiError extends Error {
    constructor(public status: number, public detail: string, public retryAfter?: number) {
      super(detail)
    }
  }
  return { FakeApiError }
})

vi.mock('../../api', () => ({
  ApiResponseError: FakeApiError,
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [
      { id: 's1', name: 'In Progress', color: '#F79009', category: 'IN_PROGRESS', position: 1 },
      { id: 's2', name: 'To Do', color: '#667085', category: 'TODO', position: 0 },
    ],
    transitions: [], priorities: [], fields: [],
    issueTypes: [{ id: 't1', name: 'Bug', color: '#F04438', position: 0, hierarchyLevel: 1 }],
  })),
  apiListWorkspaceMembers: vi.fn(async () => ([
    { userId: 'u1', email: 'alex@example.com', displayName: 'Alex Doe' },
  ])),
  componentsApi: { list: vi.fn(async () => [{ id: 'c1', name: 'API', archived: false, autoAssign: false, issueCount: null, createdAt: '', updatedAt: '' }]) },
  labelsApi: { list: vi.fn(async () => [{ id: 'l1', name: 'flaky', color: '#0EA5A4', archived: false, issueCount: null, createdAt: '', updatedAt: '' }]) },
  reportsApi: {
    cycleTime: vi.fn(async () => {
      if (mockState.cycleError) throw mockState.cycleError
      return mockState.cycle
    }),
    aging: vi.fn(async () => {
      if (mockState.agingError) throw mockState.agingError
      return mockState.aging
    }),
  },
}))

function meta(over: Partial<CycleTimeReport['meta']> = {}): CycleTimeReport['meta'] {
  return {
    computedAt: '2026-08-19T09:14:22Z', basedOnIssues: 1842, truncated: false, cap: 20000,
    firstIssueAt: '2024-11-04T08:31:00Z', unmatchedFilters: [], ...over,
  }
}

function cycleReport(over: Partial<CycleTimeReport> = {}): CycleTimeReport {
  return {
    from: '2026-05-21', to: '2026-08-19',
    items: [
      {
        issueId: 'i1', key: 'DEMO-14', title: 'Fix the paging bug', typeId: 't1',
        startedAt: '2026-08-01T09:00:00Z', closedAt: '2026-08-05T12:00:00Z',
        cycleDays: 4.2, leadDays: 11.7,
      },
      {
        // No recorded start: it has a lead time and no cycle time, and it is
        // never dated from its creation to invent one.
        issueId: 'i2', key: 'DEMO-15', title: 'Ship the exporter', typeId: 't1',
        startedAt: null, closedAt: '2026-08-09T12:00:00Z',
        cycleDays: null, leadDays: 28.4,
      },
    ],
    percentiles: { cycle: { p50: 4.1, p85: 12.6 }, lead: { p50: 9.0, p85: 28.4 } },
    sampleSize: 940, missingStartCount: 128,
    meta: meta(),
    ...over,
  }
}

function agingReport(over: Partial<AgingReport> = {}): AgingReport {
  return {
    columns: [
      {
        statusId: 's1', name: 'In Progress', category: 'IN_PROGRESS',
        items: [
          { issueId: 'a1', key: 'DEMO-31', title: 'Rewrite the importer', ageDays: 19.4, assigneeId: 'u1', startedAt: '2026-07-31T09:00:00Z' },
          { issueId: 'a2', key: 'DEMO-32', title: 'Tidy the logs', ageDays: 1.2, assigneeId: null, startedAt: null },
        ],
      },
      {
        // The deliberate trailing column: a status this workflow no longer
        // carries. It must be rendered, not dropped.
        statusId: 'gone', name: 'Not on this board', category: null,
        items: [
          { issueId: 'a3', key: 'DEMO-40', title: 'Stranded work', ageDays: 44, assigneeId: 'u1', startedAt: null },
        ],
      },
    ],
    percentiles: { p50: 4.1, p85: 12.6 },
    meta: meta({ basedOnIssues: 3 }),
    ...over,
  }
}

beforeEach(() => {
  mockState.cycle = cycleReport()
  mockState.cycleError = null
  mockState.aging = agingReport()
  mockState.agingError = null
  vi.mocked(reportsApi.cycleTime).mockClear()
  vi.mocked(reportsApi.aging).mockClear()
})

/**
 * The whole `role="note"` whose text matches — not the inner `<b>` that
 * `findByText` happens to land on. Every disclosure here is a SENTENCE, and
 * asserting on a fragment of one would let the rest of it be deleted.
 */
async function noteWith(pattern: RegExp): Promise<HTMLElement> {
  return await waitFor(() => {
    const hit = screen.getAllByRole('note').find(n => pattern.test(n.textContent ?? ''))
    if (!hit) throw new Error(`no note matching ${pattern}`)
    return hit
  })
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname + loc.search}</div>
}

function renderPage(url = '/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <LocationProbe />
        <Routes>
          <Route path="/w/:wsId/p/:projectId/reports/cycle-time" element={<CycleTimeReportPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CycleTimeReportPage — cycle time never rests quietly on a subset', () => {
  it('prints how many completed issues actually have a cycle time', async () => {
    renderPage()
    const notice = await noteWith(/Cycle time available for/)
    expect(notice).toHaveTextContent(`${(812).toLocaleString()} of ${(940).toLocaleString()} completed issues`)
    expect(notice).toHaveTextContent(/no recorded start/)
    // The rule that makes the number meaningful at all.
    expect(notice).toHaveTextContent(/creation date is never substituted/)
  })

  it('says the missing starts do not touch lead time, which is defined for everything', async () => {
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19&measure=LEAD')
    const notice = await noteWith(/Cycle time available for/)
    expect(notice).toHaveTextContent(/lead time is defined for every completed issue/)
  })

  it('says nothing at all when every completed issue has a start', async () => {
    mockState.cycle = cycleReport({ missingStartCount: 0 })
    renderPage()
    await screen.findByTestId('cycle-chart')
    expect(screen.queryByText(/Cycle time available for/)).not.toBeInTheDocument()
  })

  it('counts the sample per measure — the two measures have different denominators', async () => {
    renderPage()
    // Cycle: 940 completed minus 128 with no start.
    expect(await screen.findByText(/based on 812 issues/)).toBeInTheDocument()
  })
})

describe('CycleTimeReportPage — percentiles, or an explanation instead of noise', () => {
  it('prints p50, p85 and the sample size beside the lines', async () => {
    renderPage()
    const line = await screen.findByText(/based on 812 issues/)
    expect(line).toHaveTextContent('p50 4.1 days')
    expect(line).toHaveTextContent('p85 12.6 days')
    // …and the chart is told to draw them.
    expect(screen.getByTestId('cycle-chart')).toHaveTextContent('p50=4.1:p85=12.6')
  })

  it('draws no lines and names the floor when the server suppressed them', async () => {
    mockState.cycle = cycleReport({
      percentiles: { cycle: null, lead: null },
      sampleSize: 3, missingStartCount: 0,
    })
    renderPage()
    expect(await screen.findByText(/Not enough completed work to compute percentiles \(need 5, have 3\)/))
      .toBeInTheDocument()
    expect(screen.getByTestId('cycle-chart')).toHaveTextContent('p50=null:p85=null')
  })

  it('treats a pair of null members exactly like a missing pair — never a line at zero', async () => {
    mockState.cycle = cycleReport({
      percentiles: { cycle: { p50: null, p85: null }, lead: { p50: null, p85: null } },
      sampleSize: 4, missingStartCount: 0,
    })
    renderPage()
    expect(await screen.findByText(/need 5, have 4/)).toBeInTheDocument()
    expect(screen.getByTestId('cycle-chart')).toHaveTextContent('p50=null:p85=null')
  })

  it('switches to the lead-time percentiles when the measure changes', async () => {
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19&measure=LEAD')
    const chart = await screen.findByTestId('cycle-chart')
    expect(chart).toHaveTextContent('p50=9:p85=28.4')
    // Lead time is defined for every completed issue, so the whole sample counts.
    expect(screen.getByText(/based on 940 issues/)).toBeInTheDocument()
  })
})

describe('CycleTimeReportPage — the row cap and the filters', () => {
  it('says the report is truncated, above the chart', async () => {
    mockState.cycle = cycleReport({ meta: meta({ truncated: true, basedOnIssues: 20000 }) })
    renderPage()
    const notice = await screen.findByText(/truncated/)
    const chart = await screen.findByTestId('cycle-chart')
    expect(notice.compareDocumentPosition(chart) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('names a filter that matched nothing, so an empty chart is never ambiguous', async () => {
    mockState.cycle = cycleReport({
      items: [], percentiles: { cycle: null, lead: null }, sampleSize: 0, missingStartCount: 0,
      meta: meta({ unmatchedFilters: ['labelId'] }),
    })
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19&labelId=l1')
    const notice = await noteWith(/empty because of a filter/)
    expect(notice).toHaveTextContent('label')
    expect(notice).toHaveTextContent('flaky')
    expect(screen.getByText(/a filter above matches no issue in this project/)).toBeInTheDocument()
  })

  it('explains an empty window rather than drawing an empty chart', async () => {
    mockState.cycle = cycleReport({
      items: [], percentiles: { cycle: null, lead: null }, sampleSize: 0, missingStartCount: 0,
    })
    renderPage()
    expect(await screen.findByText(/Nothing was completed between/)).toBeInTheDocument()
    expect(screen.queryByTestId('cycle-chart')).not.toBeInTheDocument()
  })

  it('offers the other measure when everything completed lacks a start', async () => {
    // The cruel case: 12 issues closed, none of them plottable as cycle time.
    // A blank chart with no sentence would read as "we shipped nothing".
    mockState.cycle = cycleReport({
      items: [{
        issueId: 'i9', key: 'DEMO-99', title: 'Old work', typeId: 't1',
        startedAt: null, closedAt: '2026-08-09T12:00:00Z', cycleDays: null, leadDays: 30,
      }],
      percentiles: { cycle: null, lead: { p50: 9, p85: 28.4 } },
      sampleSize: 12, missingStartCount: 12,
    })
    renderPage()
    const note = await screen.findByText(/none of them has a recorded/)
    expect(note).toHaveTextContent(/Switch the measure/)
    expect(screen.queryByTestId('cycle-chart')).not.toBeInTheDocument()
  })

  it('says when the report can only reach back a fortnight', async () => {
    mockState.cycle = cycleReport({
      meta: meta({ firstIssueAt: new Date(Date.now() - 4 * 86_400_000).toISOString() }),
    })
    renderPage()
    expect(await screen.findByText(/days of history/)).toBeInTheDocument()
  })
})

describe('CycleTimeReportPage — the aging half names the rotting item', () => {
  it('lists every open issue with its key, assignee and age, oldest first', async () => {
    renderPage()
    const tables = await screen.findAllByRole('table')
    const aging = tables[tables.length - 1]
    const rows = within(aging).getAllByRole('row')
    // header + 3 items
    expect(rows).toHaveLength(4)
    // Oldest first — the whole point of the half.
    expect(rows[1]).toHaveTextContent('DEMO-40')
    expect(rows[1]).toHaveTextContent('44')
    expect(rows[1]).toHaveTextContent('Alex Doe')
    expect(rows[2]).toHaveTextContent('DEMO-31')
    expect(rows[3]).toHaveTextContent('DEMO-32')
    // An unassigned item says so rather than showing an empty cell.
    expect(rows[3]).toHaveTextContent('Unassigned')
  })

  it('says "older than p85" in words, not only in colour', async () => {
    renderPage()
    const tables = await screen.findAllByRole('table')
    const rows = within(tables[tables.length - 1]).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('older than p85')   // 44 days
    expect(rows[2]).toHaveTextContent('older than p85')   // 19.4 days
    expect(rows[3]).toHaveTextContent('within p50')       // 1.2 days
  })

  it('renders the trailing "Not on this board" column instead of dropping it', async () => {
    renderPage()
    const notice = await noteWith(/no longer carries/)
    expect(notice).toHaveTextContent('Not on this board')
    expect(notice).toHaveTextContent(/shown rather than dropped/)
    // …and the stranded issue is in the chart's columns and in the table.
    expect(screen.getByTestId('aging-chart')).toHaveTextContent('aging:2:3')
  })

  it('draws the same p50/p85 across the aging half', async () => {
    renderPage()
    expect(await screen.findByTestId('aging-chart')).toHaveTextContent('p50=4.1:p85=12.6')
  })

  it('says there is no baseline when the aging percentiles are suppressed', async () => {
    mockState.aging = agingReport({ percentiles: null })
    renderPage()
    expect(await screen.findByText(/No percentile lines on this half/)).toBeInTheDocument()
    expect(screen.getByTestId('aging-chart')).toHaveTextContent('p50=null:p85=null')
  })

  it('says something when nothing at all is open', async () => {
    mockState.aging = agingReport({ columns: [] })
    renderPage()
    expect(await screen.findByText(/Nothing is open in this project right now/)).toBeInTheDocument()
    expect(screen.queryByTestId('aging-chart')).not.toBeInTheDocument()
  })

  it('links every named issue to the issue itself, with an ABSOLUTE path', async () => {
    // A relative link inside the /reports/* splat resolves AFTER the splat
    // segment — the documented project trap, and the reason this is asserted on
    // the rendered href rather than on the code.
    renderPage()
    expect(await screen.findByRole('link', { name: 'DEMO-31' }))
      .toHaveAttribute('href', '/w/w1/p/p1/issues/31')
    expect(screen.getByRole('link', { name: 'DEMO-14' }))
      .toHaveAttribute('href', '/w/w1/p/p1/issues/14')
  })
})

describe('CycleTimeReportPage — the two halves do not share a window', () => {
  it('calls /aging with no parameters at all', async () => {
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19&typeId=t1')
    await screen.findByTestId('aging-chart')
    expect(reportsApi.aging).toHaveBeenCalledWith('w1', 'p1')
  })

  it('tells the reader the columns below are not filtered like the chart above', async () => {
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19&typeId=t1')
    const notice = await noteWith(/current state of the project/)
    expect(notice).toHaveTextContent(/apply to the finished-work chart only/)
    expect(notice).toHaveTextContent(/do not move when you change the window/)
    // The baseline is named exactly: cycle time over the project's whole
    // completed history — not the window above, and not the measure on screen.
    expect(notice).toHaveTextContent(/cycle time across everything this project has ever completed/)
  })
})

describe('CycleTimeReportPage — report state lives in the URL', () => {
  it('sends the window and the filters, and never the measure', async () => {
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19&measure=LEAD&typeId=t1')
    await screen.findByTestId('cycle-chart')
    expect(reportsApi.cycleTime).toHaveBeenCalledWith('w1', 'p1', {
      from: '2026-05-21', to: '2026-08-19', typeId: 't1',
    })
  })

  it('writes the measure into the URL and re-renders WITHOUT a second request', async () => {
    renderPage()
    await screen.findByTestId('cycle-chart')
    expect(reportsApi.cycleTime).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByLabelText('Measure'))
    await userEvent.click(await screen.findByText('Lead time'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('measure=LEAD'))
    // The response carries both measures and both percentile pairs, so the
    // toggle is a re-render. A refetch here would be a wasted round trip AND a
    // second chance for the two halves to disagree.
    expect(reportsApi.cycleTime).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('cycle-chart')).toHaveTextContent('p50=9:p85=28.4')
    // The window travels with it, so the link means the same thing to the next reader.
    expect(screen.getByTestId('loc')).toHaveTextContent('from=2026-05-21')
  })
})

describe('CycleTimeReportPage — refusals are real messages', () => {
  it('renders the server 400 verbatim, with a way out', async () => {
    mockState.cycleError = new FakeApiError(400,
      'Window is 800 days (2024-06-01 to 2026-08-09); the maximum is 365 days (app.reports.max-window-days).')
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2024-06-01&to=2026-08-09')
    const notice = await noteWith(/the maximum is 365 days/)
    expect(notice).toHaveTextContent('Window is 800 days')

    await userEvent.click(screen.getByRole('button', { name: 'Use the default window' }))
    await waitFor(() => expect(screen.getByTestId('loc')).not.toHaveTextContent('from='))
    // …and the aging half is unaffected: it never had a window to refuse.
    expect(screen.getByTestId('aging-chart')).toBeInTheDocument()
  })

  it('renders a 429 as a wait, not as a fault', async () => {
    mockState.cycleError = new FakeApiError(429, 'Too many requests', 12)
    renderPage()
    expect(await screen.findByText(/Too many report requests/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled()
  })

  it('names a 404 for what it is', async () => {
    mockState.cycleError = new FakeApiError(404, 'Not found')
    mockState.agingError = new FakeApiError(404, 'Not found')
    renderPage()
    expect((await screen.findAllByText(/Project not found/)).length).toBeGreaterThan(0)
  })

  it('never leaves an unrecognised failure silent', async () => {
    mockState.agingError = new Error('socket hang up')
    renderPage()
    expect(await screen.findByText(/Couldn’t load aging work in progress/)).toBeInTheDocument()
    // …and one half failing does not take the other down.
    expect(await screen.findByTestId('cycle-chart')).toBeInTheDocument()
  })
})

describe('CycleTimeReportPage — every chart has a table equivalent', () => {
  it('lists the plotted issues with both durations, and keeps the chart out of the a11y tree', async () => {
    renderPage()
    const table = (await screen.findAllByRole('table'))[0]
    const rows = within(table).getAllByRole('row')
    // header + the one issue that has a cycle time (DEMO-15 has no start).
    expect(rows).toHaveLength(2)
    expect(rows[1]).toHaveTextContent('DEMO-14')
    expect(rows[1]).toHaveTextContent('4.2')
    expect(rows[1]).toHaveTextContent('11.7')
    expect(within(table).getByText('Cycle days (plotted)')).toBeInTheDocument()

    expect(screen.getByTestId('cycle-chart').closest('[aria-hidden="true"]')).not.toBeNull()
    expect(screen.getByTestId('aging-chart').closest('[aria-hidden="true"]')).not.toBeNull()
  })

  it('plots and lists every completed issue under lead time', async () => {
    renderPage('/w/w1/p/p1/reports/cycle-time?from=2026-05-21&to=2026-08-19&measure=LEAD')
    const table = (await screen.findAllByRole('table'))[0]
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(screen.getByTestId('cycle-chart')).toHaveTextContent('chart:2')
  })

  it('prints the provenance of BOTH halves', async () => {
    renderPage()
    await screen.findByTestId('aging-chart')
    const lines = screen.getAllByText((_, el) =>
      el?.tagName === 'P' && el.textContent?.includes('UTC day boundaries') === true)
    expect(lines).toHaveLength(2)
  })
})
