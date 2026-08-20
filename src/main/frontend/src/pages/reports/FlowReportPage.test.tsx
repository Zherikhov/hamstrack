import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import FlowReportPage from './FlowReportPage'
import { apiDownloadReportCsv, reportsApi } from '../../api'
import type { FlowReport } from '../../types'

/**
 * HD-28 — the flow report's tests are about the **disclosures**, not the chart.
 *
 * The chart is mocked away (Recharts needs a laid-out DOM jsdom does not give it,
 * and the numbers a reader can rely on are in the table underneath anyway). What
 * is pinned here is every rule that makes the report honest, because each one is
 * a sentence a future refactor could silently drop while every other test stayed
 * green:
 *
 *  1. `meta` printed — `computedAt` + `basedOnIssues` + the cap;
 *  2. `truncated` surfaced ABOVE the chart, not swallowed;
 *  3. a 400 rendered as the SERVER's own sentence (it names the cap) with a way out;
 *  4. the `closed_at` reopen footnote;
 *  5. partial first/last buckets called out — from the SERVER's `partial` flag,
 *     never re-derived here;
 *  6. thin history (from `meta.firstIssueAt`, with different copy when a filter
 *     is on) and the all-zero window — never a blank panel;
 *  7. a filter that matched nothing named as such, so an empty chart because of
 *     a stale id does not look like an empty chart because nothing happened;
 *  8. a 429 rendered as a wait, not as a fault;
 *  9. the table equivalent under the chart, carrying the same numbers;
 * 10. report state living in the URL, because the shareable URL is the sharing
 *     mechanism R7 builds on.
 */

vi.mock('./FlowChart', () => ({
  default: ({ buckets }: { buckets: unknown[] }) =>
    <div data-testid="flow-chart">{`chart:${buckets.length}`}</div>,
}))

const mockState = vi.hoisted(() => ({
  report: null as unknown,
  reportError: null as unknown,
}))

// Hoisted with the mock factory below: a top-level class would not exist yet
// when vi.mock's factory runs (it is hoisted above every declaration).
const { FakeApiError } = vi.hoisted(() => {
  class FakeApiError extends Error {
    // `retryAfter` is parsed off the Retry-After header by the real api layer for
    // EVERY status, so a 429 arrives here already carrying its wait.
    constructor(public status: number, public detail: string, public retryAfter?: number) {
      super(detail)
    }
  }
  return { FakeApiError }
})

vi.mock('../../api', () => ({
  ApiResponseError: FakeApiError,
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [], transitions: [], priorities: [], fields: [],
    issueTypes: [{ id: 't1', name: 'Bug', color: '#F04438', position: 0, hierarchyLevel: 1 }],
  })),
  componentsApi: { list: vi.fn(async () => [{ id: 'c1', name: 'API', archived: false, autoAssign: false, issueCount: null, createdAt: '', updatedAt: '' }]) },
  labelsApi: { list: vi.fn(async () => [{ id: 'l1', name: 'flaky', color: '#0EA5A4', archived: false, issueCount: null, createdAt: '', updatedAt: '' }]) },
  reportsApi: {
    flow: vi.fn(async () => {
      if (mockState.reportError) throw mockState.reportError
      return mockState.report
    }),
  },
  // R7: the page now carries an export bar, which reads the project (for the
  // image footer's identity) and downloads the series CSV.
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Payments', key: 'PAY', archived: false,
    myRole: 'MEMBER', myPermissions: [], createdAt: '2026-01-01T00:00:00Z',
  })),
  apiDownloadReportCsv: vi.fn(async () => undefined),
}))

/** A weekly report whose window starts on a Monday and ends on a Sunday (aligned). */
function meta(over: Partial<FlowReport['meta']> = {}): FlowReport['meta'] {
  return {
    computedAt: '2026-08-19T09:14:22Z', basedOnIssues: 1842, truncated: false, cap: 20000,
    firstIssueAt: '2024-11-04T08:31:00Z', unmatchedFilters: [], ...over,
  }
}

function report(over: Partial<FlowReport> = {}): FlowReport {
  return {
    from: '2026-08-03', to: '2026-08-16', interval: 'WEEK',
    buckets: [
      { date: '2026-08-03', created: 12, resolved: 9, openAtEnd: 124, partial: false },
      { date: '2026-08-10', created: 4, resolved: 11, openAtEnd: 117, partial: false },
    ],
    totals: { created: 16, resolved: 20, net: -4 },
    meta: {
      computedAt: '2026-08-19T09:14:22Z', basedOnIssues: 1842, truncated: false, cap: 20000,
      firstIssueAt: '2024-11-04T08:31:00Z', unmatchedFilters: [],
    },
    ...over,
  }
}

function zeroReport(over: Partial<FlowReport> = {}): FlowReport {
  return report({
    buckets: [
      { date: '2026-08-03', created: 0, resolved: 0, openAtEnd: 0, partial: false },
      { date: '2026-08-10', created: 0, resolved: 0, openAtEnd: 0, partial: false },
    ],
    totals: { created: 0, resolved: 0, net: 0 },
    ...over,
  })
}

beforeEach(() => {
  mockState.report = report()
  mockState.reportError = null
  vi.mocked(reportsApi.flow).mockClear()
})

/**
 * A number as the UI formats it, with the exotic separators `toLocaleString`
 * emits (NBSP / narrow NBSP) folded to a plain space — which is exactly what
 * jest-dom's `toHaveTextContent` does to the DOM before comparing. Without this
 * the assertion fails against a string that LOOKS identical in the diff.
 */
function fmt(n: number): string {
  return n.toLocaleString().replace(/[   ]/g, ' ')
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname + loc.search}</div>
}

function renderPage(url = '/w/w1/p/p1/reports/flow?from=2026-08-03&to=2026-08-16&interval=WEEK') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <LocationProbe />
        <Routes>
          <Route path="/w/:wsId/p/:projectId/reports/flow" element={<FlowReportPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FlowReportPage — meta is printed, not swallowed', () => {
  it('prints computedAt, basedOnIssues and the cap', async () => {
    renderPage()
    // Number grouping is locale-dependent (the box running the suite is not
    // necessarily en-US), so the expectations are built with the same formatter
    // the UI uses rather than with a hardcoded "1,842".
    const meta = await screen.findByText(
      (_, el) => el?.textContent?.includes(`based on ${(1842).toLocaleString()} issues`) === true
        && el.tagName === 'P')
    expect(meta).toHaveTextContent('computed')
    expect(meta).toHaveTextContent(`row cap ${fmt(20000)}`)
    expect(meta).toHaveTextContent('UTC day boundaries')
  })

  it('says so, above the chart, when the row cap bit', async () => {
    mockState.report = report({ meta: meta({ basedOnIssues: 20000, truncated: true }) })
    renderPage()
    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent('truncated')
    expect(notice).toHaveTextContent(fmt(20000))
    // Above the chart, not in a footnote nobody scrolls to.
    const chart = await screen.findByTestId('flow-chart')
    expect(notice.compareDocumentPosition(chart) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('renders no truncation notice when nothing was truncated', async () => {
    renderPage()
    await screen.findByTestId('flow-chart')
    expect(screen.queryByText(/truncated/i)).not.toBeInTheDocument()
  })
})

describe('FlowReportPage — a refused window is a real message', () => {
  it('renders the server 400 verbatim, cap and all, with a way out', async () => {
    mockState.reportError = new FakeApiError(400,
      'Window is 800 days (2024-06-01 to 2026-08-09); the maximum is 365 days (app.reports.max-window-days).')
    renderPage('/w/w1/p/p1/reports/flow?from=2024-06-01&to=2026-08-09')

    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent('the maximum is 365 days (app.reports.max-window-days)')
    expect(notice).toHaveTextContent('Window is 800 days')
    // Not a generic banner, and not a dead end. The way out CLEARS the window
    // rather than naming one: "the last 90 days" is refused by exactly the
    // instance that produced this message (max-window-days below 90), while an
    // absent window makes the server pick one derived from its own cap.
    const wayOut = screen.getByRole('button', { name: 'Use the default window' })
    expect(wayOut).toBeInTheDocument()
    expect(screen.queryByTestId('flow-chart')).not.toBeInTheDocument()

    await userEvent.click(wayOut)
    await waitFor(() => {
      expect(screen.getByTestId('loc')).not.toHaveTextContent('from=')
    })
    expect(screen.getByTestId('loc')).not.toHaveTextContent('to=')
  })

  it('renders an out-of-band date through the same path as the over-cap window', async () => {
    // A second 400 with a different rule behind it and the same refuse-and-
    // name-it shape — it needs no branch of its own, and having one would be an
    // invitation to get its wording subtly different from the cap's.
    mockState.reportError = new FakeApiError(400,
      'Invalid window: to (+999999999-12-31) is outside the supported range 1970-01-01 to 2200-12-31.')
    renderPage()
    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent('outside the supported range 1970-01-01 to 2200-12-31')
    expect(screen.getByRole('button', { name: 'Use the default window' })).toBeInTheDocument()
  })

  it('names the 404 for what it is', async () => {
    mockState.reportError = new FakeApiError(404, 'Not found')
    renderPage()
    expect(await screen.findByText(/Project not found/)).toBeInTheDocument()
  })
})

describe('FlowReportPage — the honesty footnotes', () => {
  it('states the closed_at / reopen hazard', async () => {
    renderPage()
    const note = await screen.findByText(/dated by their latest\s+closure/)
    expect(note).toHaveTextContent('closed now')
    expect(note).toHaveTextContent(/Reopening an issue clears that date/)
    expect(note).toHaveTextContent(/past buckets can change/)
  })

  it('calls out partial end buckets from the SERVER flag, not from the dates', async () => {
    // The dates in the URL are deliberately Monday→Sunday — perfectly aligned —
    // while the response flags both ends partial. Only a page reading
    // `buckets[].partial` can pass this; one re-deriving Monday truncation in
    // TypeScript would look at the same aligned window and say nothing.
    mockState.report = report({
      buckets: [
        { date: '2026-08-03', created: 12, resolved: 9, openAtEnd: 124, partial: true },
        { date: '2026-08-10', created: 4, resolved: 11, openAtEnd: 117, partial: true },
      ],
    })
    renderPage()
    expect(await screen.findByText(/first and last buckets are/)).toBeInTheDocument()
  })

  it('says ONE bucket is partial when only the first one is', async () => {
    mockState.report = report({
      buckets: [
        { date: '2026-08-03', created: 12, resolved: 9, openAtEnd: 124, partial: true },
        { date: '2026-08-10', created: 4, resolved: 11, openAtEnd: 117, partial: false },
      ],
    })
    renderPage()
    expect(await screen.findByText(/first bucket is/)).toBeInTheDocument()
  })

  it('never says "first and last" about a single bucket', async () => {
    // One bar cannot be both ends of anything, and a window that fits inside one
    // week legitimately produces exactly one partial bucket.
    mockState.report = report({
      buckets: [{ date: '2026-08-03', created: 3, resolved: 1, openAtEnd: 9, partial: true }],
    })
    renderPage()
    expect(await screen.findByText(/first bucket is/)).toBeInTheDocument()
    expect(screen.queryByText(/first and last/)).not.toBeInTheDocument()
  })

  it('says nothing about partial buckets when no bucket is flagged', async () => {
    renderPage()
    await screen.findByTestId('flow-chart')
    expect(screen.queryByText(/is .*partial/)).not.toBeInTheDocument()
  })

  it('names the partial rows in the table, which is the accessible reading', async () => {
    mockState.report = report({
      buckets: [
        { date: '2026-08-03', created: 12, resolved: 9, openAtEnd: 124, partial: true },
        { date: '2026-08-10', created: 4, resolved: 11, openAtEnd: 117, partial: false },
      ],
    })
    renderPage()
    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('2026-08-03 (partial)')
    expect(rows[2]).not.toHaveTextContent('partial')
  })
})

describe('FlowReportPage — thin and empty states are never blank', () => {
  it('discloses under two weeks of history, measured from meta.firstIssueAt', async () => {
    // No second request for the project: the date arrives with the report, and
    // the project's own createdAt was never the right number anyway.
    mockState.report = report({
      meta: meta({ firstIssueAt: new Date(Date.now() - 3 * 86_400_000).toISOString() }),
    })
    renderPage()
    expect(await screen.findByText(/days of history — trends need a few weeks/)).toBeInTheDocument()
    // The short window still renders — the disclosure is added, nothing is withheld.
    expect(await screen.findByTestId('flow-chart')).toBeInTheDocument()
  })

  it('does not call a FILTERED reach "the project’s history"', async () => {
    // firstIssueAt is filtered like the rest of the response, so with a type
    // filter on it is the first issue OF THAT TYPE. Saying "this project has
    // only 3 days of history" there would be a confidently wrong sentence.
    mockState.report = report({
      meta: meta({ firstIssueAt: new Date(Date.now() - 3 * 86_400_000).toISOString() }),
    })
    renderPage('/w/w1/p/p1/reports/flow?from=2026-08-03&to=2026-08-16&typeId=t1')
    const note = await screen.findByText(/days of matching history/)
    expect(note).toHaveTextContent(/earliest issue these filters match/)
    expect(note).toHaveTextContent(/not how old the project is/)
  })

  it('says nothing about thin history when the report reaches back years', async () => {
    renderPage()
    await screen.findByTestId('flow-chart')
    expect(screen.queryByText(/days of history/)).not.toBeInTheDocument()
  })

  it('explains an all-zero window instead of drawing an empty chart', async () => {
    mockState.report = report({
      buckets: [
        { date: '2026-08-03', created: 0, resolved: 0, openAtEnd: 0, partial: false },
        { date: '2026-08-10', created: 0, resolved: 0, openAtEnd: 0, partial: false },
      ],
      totals: { created: 0, resolved: 0, net: 0 },
    })
    renderPage()
    expect(await screen.findByText(/Nothing was created or resolved between/)).toBeInTheDocument()
    expect(screen.queryByTestId('flow-chart')).not.toBeInTheDocument()
    // …and the table is still there, so the window is still inspectable.
    expect(screen.getByRole('table')).toBeInTheDocument()
  })
})

describe('FlowReportPage — every chart has a table equivalent', () => {
  it('renders the same series as rows, with a totals row', async () => {
    renderPage()
    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row')
    // header + 2 buckets + totals
    expect(rows).toHaveLength(4)
    expect(rows[1]).toHaveTextContent('2026-08-03')
    expect(rows[1]).toHaveTextContent('12')
    expect(rows[1]).toHaveTextContent('124')
    // Net is signed per bucket AND in the totals row — an unsigned net is unreadable.
    expect(rows[2]).toHaveTextContent('-7')
    expect(rows[3]).toHaveTextContent('Total')
    expect(rows[3]).toHaveTextContent('-4')
  })

  it('keeps the chart out of the accessibility tree, since the table is the reading', async () => {
    renderPage()
    const chart = await screen.findByTestId('flow-chart')
    expect(chart.closest('[aria-hidden="true"]')).not.toBeNull()
  })
})

describe('FlowReportPage — report state lives in the URL', () => {
  it('sends the window and filters straight through to the endpoint', async () => {
    renderPage('/w/w1/p/p1/reports/flow?from=2026-08-03&to=2026-08-16&interval=DAY&typeId=t1&labelId=l1')
    await screen.findByTestId('flow-chart')
    expect(reportsApi.flow).toHaveBeenCalledWith('w1', 'p1', expect.objectContaining({
      from: '2026-08-03', to: '2026-08-16', interval: 'DAY', typeId: 't1', labelId: 'l1',
    }))
  })

  it('writes a control change back into the URL, so the link is shareable', async () => {
    renderPage()
    await screen.findByTestId('flow-chart')

    await userEvent.click(screen.getByLabelText('Buckets'))
    await userEvent.click(await screen.findByText('Daily'))

    await waitFor(() => {
      expect(screen.getByTestId('loc')).toHaveTextContent('interval=DAY')
    })
    // The whole window travels with it — a shared URL must not mean "the last 90
    // days" to the next reader if it meant a pinned window to this one.
    expect(screen.getByTestId('loc')).toHaveTextContent('from=2026-08-03')
    expect(screen.getByTestId('loc')).toHaveTextContent('to=2026-08-16')
  })
})


describe('FlowReportPage — an empty chart says WHY it is empty', () => {
  it('names the filter that matched nothing, in the reader’s vocabulary', async () => {
    // The exact bug this fixes: a URL shared last quarter names a label the
    // workspace no longer carries. Without the disclosure the reader sees a
    // complete, plausible, all-zero chart and believes it.
    mockState.report = zeroReport({ meta: meta({ unmatchedFilters: ['labelId'] }) })
    renderPage('/w/w1/p/p1/reports/flow?from=2026-08-03&to=2026-08-16&labelId=l1')

    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent(/empty because of a filter/)
    expect(notice).toHaveTextContent('label')
    // Resolvable ids are named, so the reader recognises the control they set.
    expect(notice).toHaveTextContent('flaky')
    expect(notice).toHaveTextContent(/not because nothing happened/)
  })

  it('prints the raw id when the workspace no longer lists it', async () => {
    mockState.report = zeroReport({ meta: meta({ unmatchedFilters: ['labelId'] }) })
    renderPage('/w/w1/p/p1/reports/flow?from=2026-08-03&to=2026-08-16&labelId=deleted-label-id')
    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent(/empty because of a filter/)
    // The id is the only handle the reader still has on a label that is gone —
    // printing it is how they recognise their own URL.
    expect(notice).toHaveTextContent('deleted-label-id')
  })

  it('makes the deliberately WEAK claim, not "this label was deleted"', async () => {
    // The server only knows that no issue in this project carries the id. A
    // valid type nobody here has ever used is reported identically, so any
    // sentence about the id existing would be a new wrong answer.
    mockState.report = zeroReport({ meta: meta({ unmatchedFilters: ['typeId'] }) })
    renderPage('/w/w1/p/p1/reports/flow?from=2026-08-03&to=2026-08-16&typeId=t1')
    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent(/empty because of a filter/)
    expect(notice).toHaveTextContent('No issue in this project matches')
    expect(notice).toHaveTextContent(/may still be\s+perfectly valid and simply unused here/)
    expect(notice.textContent).not.toMatch(/delet/i)
  })

  it('names every unmatched filter when more than one missed', async () => {
    mockState.report = zeroReport({ meta: meta({ unmatchedFilters: ['typeId', 'componentId'] }) })
    renderPage('/w/w1/p/p1/reports/flow?from=2026-08-03&to=2026-08-16&typeId=t1&componentId=c1')
    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent(/empty because of a filter/)
    expect(notice).toHaveTextContent('Bug')
    expect(notice).toHaveTextContent('API')
  })

  it('keeps saying "nothing happened" when the filters DID match', async () => {
    // The other half of the pair: an honestly quiet window must not be blamed on
    // a filter, or the disclosure becomes noise nobody reads.
    mockState.report = zeroReport()
    renderPage()
    expect(await screen.findByText(/Nothing was created or resolved between/)).toBeInTheDocument()
    expect(screen.queryByText(/empty because of a filter/)).not.toBeInTheDocument()
  })
})

describe('FlowReportPage — a 429 is a wait, not a fault', () => {
  it('says how long, and offers a retry that is disabled until then', async () => {
    mockState.reportError = new FakeApiError(429, 'Too many requests — try again later', 12)
    renderPage()

    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent('Too many report requests')
    expect(notice).toHaveTextContent('Try again in 12 seconds')
    // Not a generic failure: nothing is wrong with the report.
    expect(notice).toHaveTextContent(/Nothing was computed/)
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled()
    expect(screen.queryByText(/Couldn’t load this report/)).not.toBeInTheDocument()
  })

  it('claims nothing about access to this project', async () => {
    // The budget is spent per principal BEFORE the project is resolved, so a 429
    // can arrive for a project the caller cannot see. Any wording implying the
    // project exists would be an inference the response does not support.
    mockState.reportError = new FakeApiError(429, 'Too many requests — try again later', 5)
    renderPage()
    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent(/says nothing about this project in particular/)
    expect(screen.queryByText(/Project not found/)).not.toBeInTheDocument()
  })

  it('lets the reader retry immediately when the server named no wait', async () => {
    mockState.reportError = new FakeApiError(429, 'Too many requests — try again later')
    renderPage()
    expect(await screen.findByText(/You can try again now/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
  })
})

describe('FlowReportPage — the first load names no window at all', () => {
  it('sends no from/to, so the server picks one derived from its own cap', async () => {
    // A parameterless request always succeeds. A client-side "last 90 days"
    // would 400 on every instance whose operator lowered max-window-days below
    // 90 — i.e. on exactly the instances the server-side default exists for.
    renderPage('/w/w1/p/p1/reports/flow')
    await screen.findByTestId('flow-chart')
    const sent = vi.mocked(reportsApi.flow).mock.calls[0][2]
    expect(sent).toMatchObject({ from: '', to: '' })
  })

  it('shows the window the server chose, not one guessed here', async () => {
    renderPage('/w/w1/p/p1/reports/flow')
    await screen.findByTestId('flow-chart')
    // The response echoed 2026-08-03 → 2026-08-16; the date controls describe it.
    expect(screen.getByLabelText('From')).toHaveValue('2026-08-03')
    expect(screen.getByLabelText('To')).toHaveValue('2026-08-16')
  })

  it('pins that window into the URL as soon as any control is touched', async () => {
    // Before the first touch the link means "whatever window this server
    // defaults to" — a moving target, and a different report to the next reader.
    renderPage('/w/w1/p/p1/reports/flow')
    await screen.findByTestId('flow-chart')
    expect(screen.getByTestId('loc')).not.toHaveTextContent('from=')

    await userEvent.click(screen.getByLabelText('Buckets'))
    await userEvent.click(await screen.findByText('Daily'))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('interval=DAY'))
    expect(screen.getByTestId('loc')).toHaveTextContent('from=2026-08-03')
    expect(screen.getByTestId('loc')).toHaveTextContent('to=2026-08-16')
  })
})

describe('FlowReportPage — no failure is silent', () => {
  it('still says something when the failure never reached the API layer', async () => {
    // A dropped connection: not an ApiResponseError, so no status, no detail.
    // Blank is the one answer this page may never give.
    mockState.reportError = new TypeError('Failed to fetch')
    renderPage()
    expect(await screen.findByText(/Couldn’t load this report/)).toBeInTheDocument()
  })
})

/**
 * R7 (HD-141) — export, wired to a real report page rather than to a fixture.
 *
 * The component's own contract is asserted in `export.test.tsx`; what these two
 * cover is the wiring only this page can get wrong: that the CSV asks for the
 * window **on screen**, and that the shareable link is pinned even when the URL
 * that produced the page carried no window at all.
 */
describe('FlowReportPage — export (R7)', () => {
  it('asks the CSV for the window on screen, not for the one in the URL', async () => {
    // Landing with no window: the SERVER picked one and echoed it back, and that
    // echo is what an export has to carry — a CSV of "whatever the default is"
    // would not be a copy of the chart above it.
    renderPage('/w/w1/p/p1/reports/flow')
    await screen.findByTestId('flow-chart')

    await userEvent.click(screen.getByRole('button', { name: /Chart series \(CSV\)/ }))
    await waitFor(() => expect(apiDownloadReportCsv).toHaveBeenCalled())
    const [ws, project, kind, params] = vi.mocked(apiDownloadReportCsv).mock.calls[0]
    expect([ws, project, kind]).toEqual(['w1', 'p1', 'flow'])
    expect(params).toMatchObject({ from: '2026-08-03', to: '2026-08-16', interval: 'WEEK' })
  })

  it('lists both exports, and only the series one is clickable today', async () => {
    renderPage()
    await screen.findByTestId('flow-chart')
    expect(screen.getByRole('button', { name: /Chart series \(CSV\)/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /Matching issues \(CSV\)/ })).toBeDisabled()
  })
})
