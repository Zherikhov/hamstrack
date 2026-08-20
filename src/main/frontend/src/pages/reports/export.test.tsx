import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useRef } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ChartExport, ReportExportBar } from './export'
import { apiDownloadReportCsv } from '../../api'
import { copyImageToClipboard, copyTextToClipboard, downloadBlob, renderChartImage } from './png'

/**
 * HD-141 (R7) — export, and the three claims it makes to a reader.
 *
 *  1. **Two files, two labels.** The research is unambiguous that exporting a
 *     flat issue list when the user asked for the chart is *the* documented
 *     disappointment, so the series CSV and the issue-list CSV are separate,
 *     separately labelled, and the difference between them is printed. The
 *     second is disabled today and still listed — hiding it would leave the
 *     series CSV as the only export on screen, which is precisely how a reader
 *     comes to believe it holds the issue list.
 *  2. **A shared link reproduces what the sender saw.** Report URLs are
 *     deliberately incomplete while browsing (no window means "the server's
 *     default", no sprint means "whichever is active"), so Copy link **pins**
 *     the resolved state first and copies that.
 *  3. **The picture says what it is a picture of.** Project, window and
 *     `computedAt` reach the renderer for every image, and a clipboard that
 *     cannot take an image gets a download and a sentence — never a dead button
 *     and never a claimed copy that did not happen.
 */

const { FakeApiError } = vi.hoisted(() => {
  class FakeApiError extends Error {
    constructor(public status: number, public detail: string) { super(detail) }
  }
  return { FakeApiError }
})

vi.mock('../../api', () => ({
  ApiResponseError: FakeApiError,
  apiDownloadReportCsv: vi.fn(async () => undefined),
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Payments', key: 'PAY', archived: false,
    myRole: 'MEMBER', myPermissions: [], createdAt: '2026-01-01T00:00:00Z',
  })),
}))

// The canvas half only — `imageFooterLines` and `imageFileName` stay REAL, so
// what the renderer is handed is the footer a browser would actually draw.
vi.mock('./png', async importOriginal => ({
  ...(await importOriginal<typeof import('./png')>()),
  renderChartImage: vi.fn(async () => new Blob(['png'], { type: 'image/png' })),
  copyImageToClipboard: vi.fn(async () => true),
  copyTextToClipboard: vi.fn(async () => true),
  downloadBlob: vi.fn(),
}))

const WINDOW = { from: '2026-05-01', to: '2026-05-31', interval: 'WEEK' }

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{`${loc.pathname}${loc.search}`}</div>
}

function renderBar(props: Partial<Parameters<typeof ReportExportBar>[0]> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/w/w1/p/p1/reports/flow']}>
        <LocationProbe />
        <Routes>
          <Route
            path="/w/:wsId/p/:projectId/reports/flow"
            element={
              <ReportExportBar
                wsId="w1"
                projectId="p1"
                projectKey="PAY"
                shareParams={WINDOW}
                csv={[{ kind: 'flow', label: 'Chart series', params: WINDOW, slug: 'flow' }]}
                {...props}
              />
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.mocked(apiDownloadReportCsv).mockClear()
  vi.mocked(apiDownloadReportCsv).mockResolvedValue(undefined)
  vi.mocked(copyTextToClipboard).mockClear()
  vi.mocked(copyTextToClipboard).mockResolvedValue(true)
  vi.mocked(copyImageToClipboard).mockClear()
  vi.mocked(copyImageToClipboard).mockResolvedValue(true)
  vi.mocked(renderChartImage).mockClear()
  vi.mocked(downloadBlob).mockClear()
})

describe('ReportExportBar — two files, two labels', () => {
  it('labels the series CSV as the chart’s own numbers', async () => {
    renderBar()
    expect(await screen.findByRole('button', { name: /Chart series \(CSV\)/ })).toBeEnabled()
  })

  it('lists the issue-list export, disabled, with the reason on it', async () => {
    renderBar()
    const issues = await screen.findByRole('button', { name: /Matching issues \(CSV\)/ })
    expect(issues).toBeDisabled()
    expect(issues).toHaveAttribute('title', expect.stringContaining('search export'))
  })

  it('prints the difference between the two files instead of leaving it to be discovered', async () => {
    renderBar()
    // The exact sentence matters less than that BOTH claims are on screen: this
    // one holds the plotted numbers, the other would hold the issue list.
    expect(await screen.findByText(/numbers plotted on this page/)).toBeInTheDocument()
    expect(screen.getByText(/issue list behind them/)).toBeInTheDocument()
  })

  it('downloads the series CSV with the report’s OWN parameters', async () => {
    renderBar()
    await userEvent.click(await screen.findByRole('button', { name: /Chart series \(CSV\)/ }))
    await waitFor(() => expect(apiDownloadReportCsv).toHaveBeenCalled())
    const [ws, project, kind, params, filename] = vi.mocked(apiDownloadReportCsv).mock.calls[0]
    expect([ws, project, kind]).toEqual(['w1', 'p1', 'flow'])
    expect(params).toEqual(WINDOW)
    // Dated and keyed, because the point of exporting is comparing two later.
    expect(filename).toMatch(/^hamstrack-flow-pay-\d{4}-\d{2}-\d{2}\.csv$/)
  })

  it('renders the server’s own refusal verbatim — it names the cap it measured against', async () => {
    vi.mocked(apiDownloadReportCsv).mockRejectedValueOnce(
      new FakeApiError(400, 'Window is 400 days; the maximum is 365 (app.reports.max-window-days).'),
    )
    renderBar()
    await userEvent.click(await screen.findByRole('button', { name: /Chart series \(CSV\)/ }))
    expect(await screen.findByText(/app\.reports\.max-window-days/)).toBeInTheDocument()
  })

  it('never leaves a failure silent, even one that carried no status', async () => {
    vi.mocked(apiDownloadReportCsv).mockRejectedValueOnce(new Error('offline'))
    renderBar()
    await userEvent.click(await screen.findByRole('button', { name: /Chart series \(CSV\)/ }))
    expect(await screen.findByText(/Couldn’t download that CSV/)).toBeInTheDocument()
  })

  it('disables the CSV while there is nothing to export', async () => {
    renderBar({ disabled: true })
    expect(await screen.findByRole('button', { name: /Chart series \(CSV\)/ })).toBeDisabled()
  })
})

describe('ReportExportBar — a link that means the same thing to its reader', () => {
  it('pins the resolved state into the URL and copies exactly that', async () => {
    renderBar()
    await userEvent.click(await screen.findByRole('button', { name: /Copy link/ }))

    // Pinned: the address bar now names the window that was on screen, which it
    // did not before the click — a link that says "the default window" is a
    // different report tomorrow.
    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('from=2026-05-01'))
    expect(screen.getByTestId('loc')).toHaveTextContent('to=2026-05-31')

    const copied = vi.mocked(copyTextToClipboard).mock.calls[0][0]
    expect(copied).toContain('/w/w1/p/p1/reports/flow?')
    expect(copied).toContain('from=2026-05-01')
    expect(copied).toContain('interval=WEEK')
    expect(await screen.findByText(/Link copied/)).toBeInTheDocument()
  })

  it('does not claim a copy the browser refused — and says where the link is', async () => {
    vi.mocked(copyTextToClipboard).mockResolvedValueOnce(false)
    renderBar()
    await userEvent.click(await screen.findByRole('button', { name: /Copy link/ }))
    expect(await screen.findByText(/address bar now holds this exact report/)).toBeInTheDocument()
  })
})

/** A chart card: a ref'd container with (or without) a chart inside it. */
function ChartHost({ withChart }: { withChart: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  return (
    <div>
      <ChartExport
        chartRef={ref}
        title="Flow — created vs resolved"
        slug="flow"
        projectKey="PAY"
        provenance={{
          project: 'Payments (PAY)',
          window: '2026-05-01 → 2026-05-31 (UTC), weekly buckets',
          computedAt: '2026-08-20T09:14:22Z',
          basedOnIssues: 1842,
        }}
      />
      <div ref={ref}>{withChart && <svg width="640" height="300" data-testid="svg" />}</div>
    </div>
  )
}

describe('ChartExport — the picture says what it is a picture of', () => {
  it('hands the renderer a footer carrying project, window and computedAt', async () => {
    render(<ChartHost withChart />)
    await userEvent.click(screen.getByRole('button', { name: /Copy image/ }))

    await waitFor(() => expect(renderChartImage).toHaveBeenCalled())
    const [, opts] = vi.mocked(renderChartImage).mock.calls[0]
    const footer = opts.footerLines.join(' | ')
    expect(footer).toContain('Payments (PAY)')
    expect(footer).toContain('2026-05-01 → 2026-05-31 (UTC)')
    expect(footer).toContain('computed 2026-08-20 09:14 UTC')
    // The axis claim rides along, because that is what makes two exports
    // comparable and a reader who does not know it will not try.
    expect(footer).toContain('axes zero-based, fixed ticks')
  })

  it('serialises the chart that is on screen, not a redrawn copy of it', async () => {
    render(<ChartHost withChart />)
    await userEvent.click(screen.getByRole('button', { name: /Copy image/ }))
    await waitFor(() => expect(renderChartImage).toHaveBeenCalled())
    expect(vi.mocked(renderChartImage).mock.calls[0][0]).toBe(screen.getByTestId('svg'))
  })

  it('downloads and SAYS SO when the browser cannot copy an image', async () => {
    vi.mocked(copyImageToClipboard).mockResolvedValueOnce(false)
    render(<ChartHost withChart />)
    await userEvent.click(screen.getByRole('button', { name: /Copy image/ }))

    await waitFor(() => expect(downloadBlob).toHaveBeenCalled())
    expect(vi.mocked(downloadBlob).mock.calls[0][1]).toMatch(/^hamstrack-flow-pay-.*\.png$/)
    expect(await screen.findByText(/can’t copy images, so it was downloaded/)).toBeInTheDocument()
  })

  it('downloads a PNG under a dated name', async () => {
    render(<ChartHost withChart />)
    await userEvent.click(screen.getByRole('button', { name: 'PNG' }))
    await waitFor(() => expect(downloadBlob).toHaveBeenCalled())
    expect(await screen.findByText('Image downloaded.')).toBeInTheDocument()
  })

  it('says the chart is not ready rather than exporting a blank image', async () => {
    // The chart chunk is lazy; a button that produced an empty PNG here would be
    // worse than one that admits it has nothing to serialise yet.
    render(<ChartHost withChart={false} />)
    await userEvent.click(screen.getByRole('button', { name: /Copy image/ }))
    expect(await screen.findByText(/hasn’t finished loading yet/)).toBeInTheDocument()
    expect(renderChartImage).not.toHaveBeenCalled()
    expect(downloadBlob).not.toHaveBeenCalled()
  })

  it('reports a renderer failure instead of a silent no-op', async () => {
    vi.mocked(renderChartImage).mockRejectedValueOnce(new Error('Image export isn’t supported'))
    render(<ChartHost withChart />)
    await userEvent.click(screen.getByRole('button', { name: 'PNG' }))
    expect(await screen.findByText(/Couldn’t make the image/)).toBeInTheDocument()
  })
})
