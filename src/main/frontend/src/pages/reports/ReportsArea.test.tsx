import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ReportsArea from './ReportsArea'
import type { ProjectDelivery } from '../../types'

/**
 * HD-28 / HD-29 — the reports area's own contract, which is entirely about
 * routing and discoverability:
 *
 *  1. **Absolute paths inside the splat.** Inside `/reports/*` a relative `to`
 *     resolves AFTER the splat segment, so `to="flow"` from `/reports/flow`
 *     lands on `/reports/flow/flow`. This is a documented project trap and the
 *     only way to keep it fixed is to assert the rendered `href`.
 *  2. **The index and every unknown sub-path resolve to a report**, so a
 *     bookmark of `/reports` and a typo both land somewhere real.
 *  3. **Every report in the epic is now built** (R5 was the last stub), so the
 *     list carries no "coming soon" entry — but the rule that put those entries
 *     here still governs the capability-gated ones below.
 *  4. **A capability a project does not have disables a report, it does not
 *     delete it** (R4). A Kanban project still sees both sprint reports, marked
 *     unavailable, and can still open the page that says what turns them on —
 *     Rule C, whose whole point is that the affordance is visible *while the
 *     capability is off*. And the decision reads the DECLARED capability: a
 *     Kanban project with sprints in its data is still Kanban, which is the
 *     inference that shipped wrong once.
 */

vi.mock('./FlowReportPage', () => ({ default: () => <div data-testid="flow-page">flow</div> }))
vi.mock('./CycleTimeReportPage', () => ({ default: () => <div data-testid="cycle-page">cycle</div> }))
vi.mock('./SprintBurnupPage', () => ({ default: () => <div data-testid="burnup-page">burnup</div> }))
vi.mock('./SprintReviewPage', () => ({ default: () => <div data-testid="review-page">review</div> }))
vi.mock('./VelocityPage', () => ({ default: () => <div data-testid="velocity-page">velocity</div> }))

const state = vi.hoisted(() => ({
  delivery: { board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM' } as ProjectDelivery,
}))

vi.mock('../../api', () => ({
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Payments', key: 'PAY',
    archived: false, myRole: 'MEMBER', myPermissions: [], delivery: state.delivery,
    createdAt: '2026-01-01T00:00:00Z',
  })),
}))

beforeEach(() => {
  state.delivery = { board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM' }
})

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

  it('links the cycle-time report absolutely too, and renders it', async () => {
    renderArea('/w/w1/p/p1/reports/cycle-time')
    expect(await screen.findByTestId('cycle-page')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cycle & lead time' }))
      .toHaveAttribute('href', '/w/w1/p/p1/reports/cycle-time')
  })

  it('routes both sprint reports, absolutely', async () => {
    renderArea('/w/w1/p/p1/reports/sprint-burnup')
    expect(await screen.findByTestId('burnup-page')).toBeInTheDocument()
    // `find*`, not `get*`: until the project response lands the capability is
    // unknown and both entries render disabled — the deliberate direction, since
    // a control may go disabled → enabled but must never be yanked away.
    expect(await screen.findByRole('link', { name: 'Sprint burn-up' }))
      .toHaveAttribute('href', '/w/w1/p/p1/reports/sprint-burnup')
    expect(screen.getByRole('link', { name: 'Sprint review' }))
      .toHaveAttribute('href', '/w/w1/p/p1/reports/sprint-review')
  })

  it('routes velocity, absolutely — R5 was the last SOON entry', async () => {
    renderArea('/w/w1/p/p1/reports/velocity')
    expect(await screen.findByTestId('velocity-page')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Velocity' }))
      .toHaveAttribute('href', '/w/w1/p/p1/reports/velocity')
    // Nothing in this list is a stub any more: an entry that promises numbers it
    // cannot draw is exactly what the SOON label existed to prevent.
    expect(screen.queryByText('SOON')).toBeNull()
  })
})

describe('ReportsArea — a capability disables a report, it never deletes it', () => {
  it('still lists both sprint reports on a Kanban project, marked unavailable', async () => {
    state.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    renderArea('/w/w1/p/p1/reports/flow')
    await screen.findByTestId('flow-page')

    const burnup = await screen.findByRole('link', { name: /Sprint burn-up/ })
    expect(burnup).toHaveAttribute('aria-disabled', 'true')
    // Rule C: the reason is reachable while the capability is off, and it names
    // the switch rather than merely refusing.
    expect(burnup).toHaveAttribute('title', expect.stringContaining('turn on Scrum in project settings'))
    // …and it is still a link, because the page behind it carries the affordance.
    expect(burnup).toHaveAttribute('href', '/w/w1/p/p1/reports/sprint-burnup')
    expect(await screen.findByRole('link', { name: /Sprint review/ }))
      .toHaveAttribute('aria-disabled', 'true')
    // Velocity is measured from completed sprints, so it is gated identically —
    // listed, disabled, and still a link to the page that says what turns it on.
    const velocity = await screen.findByRole('link', { name: /Velocity/ })
    expect(velocity).toHaveAttribute('aria-disabled', 'true')
    expect(velocity).toHaveAttribute('href', '/w/w1/p/p1/reports/velocity')
  })

  it('marks nothing unavailable on a Scrum project', async () => {
    renderArea('/w/w1/p/p1/reports/flow')
    await screen.findByTestId('flow-page')
    expect(await screen.findByRole('link', { name: 'Sprint burn-up' }))
      .not.toHaveAttribute('aria-disabled')
    expect(screen.queryByText('OFF')).toBeNull()
  })

  it('routes a shared sprint-report link on a Kanban project to the page, not away from it', async () => {
    // The page carries the Rule C affordance, so redirecting here would send a
    // reader somewhere unrelated and lose the only sentence that helps them.
    state.delivery = { board: 'KANBAN', releases: true, estimation: true, preset: 'KANBAN' }
    renderArea('/w/w1/p/p1/reports/sprint-review')
    expect(await screen.findByTestId('review-page')).toBeInTheDocument()
    expect(screen.getByTestId('loc')).toHaveTextContent('/w/w1/p/p1/reports/sprint-review')
  })
})
