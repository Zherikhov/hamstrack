import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ReportsArea from './ReportsArea'

/**
 * HD-28 — the reports area's own contract, which is entirely about routing and
 * discoverability:
 *
 *  1. **Absolute paths inside the splat.** Inside `/reports/*` a relative `to`
 *     resolves AFTER the splat segment, so `to="flow"` from `/reports/flow`
 *     lands on `/reports/flow/flow`. This is a documented project trap and the
 *     only way to keep it fixed is to assert the rendered `href`.
 *  2. **The index and every unknown sub-path resolve to a report**, so a
 *     bookmark of `/reports` and a typo both land somewhere real.
 *  3. **The unbuilt reports are listed, not hidden** — a report nobody can see
 *     is a report nobody asks for, and R4/R5's sprint reports will sit in this
 *     same list disabled-with-reason rather than vanish in a Kanban project.
 */

vi.mock('./FlowReportPage', () => ({ default: () => <div data-testid="flow-page">flow</div> }))

vi.mock('../../api', () => ({
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Payments', key: 'PAY',
    archived: false, myRole: 'MEMBER', myPermissions: [], createdAt: '2026-01-01T00:00:00Z',
  })),
}))

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname}</div>
}

function renderArea(url: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <LocationProbe />
        <Routes>
          <Route path="/w/:wsId/p/:projectId/reports/*" element={<ReportsArea />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReportsArea', () => {
  it('links each report with an ABSOLUTE path (the splat-route trap)', async () => {
    renderArea('/w/w1/p/p1/reports/flow')
    expect(await screen.findByRole('link', { name: 'Flow' }))
      .toHaveAttribute('href', '/w/w1/p/p1/reports/flow')
  })

  it('resolves the bare /reports index to the flow report', async () => {
    renderArea('/w/w1/p/p1/reports')
    expect(await screen.findByTestId('flow-page')).toBeInTheDocument()
    expect(screen.getByTestId('loc')).toHaveTextContent('/w/w1/p/p1/reports/flow')
  })

  it('sends an unknown sub-path to a real report instead of a blank pane', async () => {
    renderArea('/w/w1/p/p1/reports/burndown')
    expect(await screen.findByTestId('flow-page')).toBeInTheDocument()
    expect(screen.getByTestId('loc')).toHaveTextContent('/w/w1/p/p1/reports/flow')
  })

  it('lists the reports that are not built yet, labelled', async () => {
    renderArea('/w/w1/p/p1/reports/flow')
    await screen.findByTestId('flow-page')
    for (const label of ['Cycle & lead time', 'Sprint burn-up', 'Sprint review', 'Velocity']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
    expect(screen.getAllByText('SOON')).toHaveLength(4)
    // …and none of them pretends to be a destination.
    expect(screen.queryByRole('link', { name: 'Velocity' })).toBeNull()
  })
})
