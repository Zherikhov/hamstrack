import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import InsightsPanel from './InsightsPanel'
import { apiSearchInsights } from '../../api'
import type {
  InsightsBucket, InsightsResponse, InsightsSlice, SearchSchema,
} from '../../types'
import type { InsightsState } from './insights'

/**
 * HD-140 (R6) — the Insights panel, **the dashboard replacement**.
 *
 * The assertions are about the five things that make it the replacement rather
 * than a nicer gadget:
 *
 *  1. **one dataset** — the panel sends the query in the box, verbatim, and
 *     never a filter of its own. Two numbers on this panel cannot disagree
 *     because there is only one set behind them (§1.5);
 *  2. **the click-through** — a click narrows the HQL query, from the table as
 *     well as from the chart. The chart is `aria-hidden`, so a narrowing action
 *     that lived only on a bar would exist only for sighted mouse users; and a
 *     bucket the server could not express is not clickable at all;
 *  3. **the table under the chart** — same numbers, same order, and it is what
 *     the accessible reading is;
 *  4. **every truncation is its own sentence** — a missing bar and a missing
 *     stack are different pictures, and a many-valued slice changes what the
 *     numbers mean;
 *  5. **a capability gates the UI and never the answer** — a hidden slice is
 *     still sent, still answered, and said out loud.
 */

// Recharts is stubbed exactly as the report-page tests stub theirs: the chunk it
// lives in is lazy, and these tests assert behaviour rather than SVG.
vi.mock('./InsightsChart', () => ({
  default: ({ rows, series, measure }: {
    rows: unknown[]; series: unknown[]; measure: string
  }) => <div data-testid="insights-chart">{`insights:${rows.length}:${series.length}:${measure}`}</div>,
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
  response: null as unknown,
  error: null as unknown,
}))

vi.mock('../../api', () => ({
  ApiResponseError: FakeApiError,
  apiSearchInsights: vi.fn(async () => {
    if (mockState.error) throw mockState.error
    return mockState.response
  }),
}))

function bucket(over: Partial<InsightsBucket> = {}): InsightsBucket {
  return { id: 's1', label: 'In Progress', hql: 'status = "In Progress"', ...over }
}

function slice(over: Partial<InsightsSlice> = {}): InsightsSlice {
  return { bucket: bucket(), count: 12, points: 21, unestimatedCount: 0, ...over }
}

function response(over: Partial<InsightsResponse> = {}): InsightsResponse {
  return {
    measure: 'COUNT',
    slice: 'STATUS',
    segment: null,
    slices: [
      slice({ bucket: bucket({ id: 's1', label: 'In Progress' }), count: 12 }),
      slice({ bucket: bucket({ id: 's2', label: 'Done', hql: 'status = "Done"' }), count: 8 }),
    ],
    segments: [],
    cells: [],
    sliceMultiValued: false,
    segmentMultiValued: false,
    slicesTruncated: false,
    cellsTruncated: false,
    sliceCap: 200,
    cellCap: 1000,
    meta: {
      computedAt: '2026-08-20T09:00:00Z', basedOnIssues: 20, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

const FULL_SCHEMA: SearchSchema = {
  fields: [], keywords: [], values: {},
  insights: {
    measures: ['COUNT', 'POINTS', 'NONE'],
    dimensions: [
      'STATUS', 'TYPE', 'PRIORITY', 'ASSIGNEE', 'COMPONENT', 'LABEL', 'SPRINT', 'PROJECT',
    ],
  },
}

/** No project here plans in sprints and none estimates. */
const THIN_SCHEMA: SearchSchema = {
  fields: [], keywords: [], values: {},
  insights: {
    measures: ['COUNT', 'NONE'],
    dimensions: ['STATUS', 'TYPE', 'PRIORITY', 'ASSIGNEE', 'COMPONENT', 'LABEL', 'PROJECT'],
  },
}

const DEFAULT_STATE: InsightsState = {
  open: true, measure: 'COUNT', slice: 'STATUS', segment: null,
}

beforeEach(() => {
  mockState.response = response()
  mockState.error = null
  vi.mocked(apiSearchInsights).mockClear()
})

function renderPanel(over: Partial<Parameters<typeof InsightsPanel>[0]> = {}) {
  const onNarrow = vi.fn()
  const onState = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <InsightsPanel
        wsId="w1"
        query={'type = "Bug"'}
        hasQuery
        schema={FULL_SCHEMA}
        state={DEFAULT_STATE}
        onState={onState}
        onNarrow={onNarrow}
        onClose={vi.fn()}
        {...over}
      />
    </QueryClientProvider>,
  )
  return { onNarrow, onState }
}

async function noteWith(pattern: RegExp): Promise<HTMLElement> {
  return await waitFor(() => {
    const hit = screen.getAllByRole('note').find(n => pattern.test(n.textContent ?? ''))
    if (!hit) throw new Error(`no note matching ${pattern}`)
    return hit
  })
}

describe('InsightsPanel — one dataset, and it is the query', () => {
  it('sends the query in the box verbatim, with no filter of its own', async () => {
    renderPanel()
    await screen.findByTestId('insights-chart')
    expect(apiSearchInsights).toHaveBeenCalledWith('w1', {
      query: 'type = "Bug"', measure: 'COUNT', slice: 'STATUS',
    })
  })

  it('omits `segment` entirely rather than sending an empty one', async () => {
    renderPanel({ state: { ...DEFAULT_STATE, segment: 'TYPE' } })
    await screen.findByTestId('insights-chart')
    expect(apiSearchInsights).toHaveBeenCalledWith('w1', {
      query: 'type = "Bug"', measure: 'COUNT', slice: 'STATUS', segment: 'TYPE',
    })
  })

  it('asks for nothing until a query has been run, and says why', async () => {
    renderPanel({ hasQuery: false })
    await noteWith(/Run a query first/)
    expect(apiSearchInsights).not.toHaveBeenCalled()
  })
})

describe('InsightsPanel — the table under the chart', () => {
  it('carries the same numbers in the same order, plus the bar sum', async () => {
    renderPanel()
    await screen.findByRole('table')
    const rows = screen.getAllByRole('row').slice(1)   // drop the header
    expect(rows[0]).toHaveTextContent('In Progress')
    expect(rows[0]).toHaveTextContent('12')
    expect(rows[1]).toHaveTextContent('Done')
    expect(rows[1]).toHaveTextContent('8')
    // Never "total issues": on a many-valued or truncated slice it is not one.
    expect(screen.getByRole('table')).toHaveTextContent('Sum of the bars — issues')
  })

  it('prints the provenance line rather than hiding it in a tooltip', async () => {
    renderPanel()
    expect(await screen.findByText(/based on 20 issues/)).toBeInTheDocument()
  })

  it('says out loud when the row cap bit', async () => {
    mockState.response = response({
      meta: {
        computedAt: '2026-08-20T09:00:00Z', basedOnIssues: 20000, truncated: true, cap: 20000,
        firstIssueAt: null, unmatchedFilters: [],
      },
    })
    renderPanel()
    await noteWith(/truncated/)
  })
})

describe('InsightsPanel — clicking narrows the query', () => {
  it('appends the server’s clause and hands back the whole query', async () => {
    const { onNarrow } = renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'In Progress' }))
    expect(onNarrow).toHaveBeenCalledWith('(type = "Bug") AND status = "In Progress"')
  })

  it('is reachable from the TABLE, not only from the (aria-hidden) chart', async () => {
    renderPanel()
    await screen.findByTestId('insights-chart')
    const table = screen.getByRole('table')
    expect(table).toContainElement(screen.getByRole('button', { name: 'In Progress' }))
  })

  it('offers no button for a bucket the server could not express, and says why', async () => {
    // `hql: null` — PROJECT has no HQL field at all.
    mockState.response = response({
      slice: 'PROJECT',
      slices: [slice({ bucket: { id: 'p1', label: 'Payments', hql: null }, count: 20 })],
    })
    renderPanel({ state: { ...DEFAULT_STATE, slice: 'PROJECT' } })
    await screen.findByRole('table')
    expect(screen.queryByRole('button', { name: 'Payments' })).not.toBeInTheDocument()
    await noteWith(/no query that means/)
  })

  it('explains an unclickable bar on an ordinary slice without blaming the project', async () => {
    // An ambiguous or unresolvable NAME — a clause would be wider than the bar.
    mockState.response = response({
      slices: [
        slice({ bucket: bucket({ id: 's1', label: 'In Progress' }), count: 12 }),
        slice({ bucket: { id: 's2', label: 'Billing', hql: null }, count: 8 }),
      ],
    })
    renderPanel({ state: { ...DEFAULT_STATE, slice: 'COMPONENT' } })
    await screen.findByRole('table')
    expect(screen.queryByRole('button', { name: 'Billing' })).not.toBeInTheDocument()
    await noteWith(/1 of these bars cannot be clicked through/)
  })
})

describe('InsightsPanel — a capability gates the UI and never the answer', () => {
  it('drops the story-points measure from the controls', async () => {
    renderPanel({ schema: THIN_SCHEMA })
    await screen.findByRole('table')
    expect(screen.queryByText('Story points')).not.toBeInTheDocument()
  })

  it('still SENDS a hidden slice a shared link asked for, and says so', async () => {
    renderPanel({ schema: THIN_SCHEMA, state: { ...DEFAULT_STATE, slice: 'SPRINT' } })
    await screen.findByRole('table')
    expect(apiSearchInsights).toHaveBeenCalledWith('w1', {
      query: 'type = "Bug"', measure: 'COUNT', slice: 'SPRINT',
    })
    await noteWith(/no project in this workspace currently offers/)
  })
})

describe('InsightsPanel — disclosures', () => {
  it('says a many-valued slice sums to more than the issues matched', async () => {
    mockState.response = response({ slice: 'LABEL', sliceMultiValued: true })
    renderPanel({ state: { ...DEFAULT_STATE, slice: 'LABEL' } })
    await noteWith(/sum to more than the 20 issues matched/)
  })

  it('tells a missing bar apart from a missing stack', async () => {
    mockState.response = response({ slicesTruncated: true })
    renderPanel()
    await noteWith(/more statuses than the panel will chart/)

    mockState.response = response({ cellsTruncated: true })
    renderPanel()
    await noteWith(/colour breakdown is truncated/)
  })

  it('reports unestimated work rather than letting it weigh zero in silence', async () => {
    mockState.response = response({
      measure: 'POINTS',
      slices: [slice({ unestimatedCount: 3 })],
    })
    renderPanel({ state: { ...DEFAULT_STATE, measure: 'POINTS' } })
    await noteWith(/3 issues in these bars have no estimate/)
  })

  it('draws no chart under measure NONE, and says there is nothing to draw', async () => {
    mockState.response = response({ measure: 'NONE' })
    renderPanel({ state: { ...DEFAULT_STATE, measure: 'NONE' } })
    await noteWith(/No measure is selected/)
    expect(screen.queryByTestId('insights-chart')).not.toBeInTheDocument()
    // The breakdown itself is still there, and still clickable.
    expect(screen.getByRole('button', { name: 'In Progress' })).toBeInTheDocument()
  })

  it('defers the 422 to the search box instead of repeating its message', async () => {
    mockState.error = new FakeApiError(422, 'Unknown field: bananas')
    renderPanel()
    const note = await noteWith(/didn’t run/)
    expect(note).not.toHaveTextContent('Unknown field: bananas')
  })

  it('renders a 429 as a retryable throttle, never as a fault', async () => {
    mockState.error = new FakeApiError(429, 'Too many requests', 30)
    renderPanel()
    await noteWith(/Too many report requests/)
  })

  it('caps the bars but not the table, and prints the difference', async () => {
    mockState.response = response({
      slices: Array.from({ length: 30 }, (_, i) => slice({
        bucket: bucket({ id: `s${i}`, label: `Status ${i}`, hql: `status = "Status ${i}"` }),
        count: 30 - i,
      })),
    })
    renderPanel()
    await noteWith(/The chart draws the first/)
    expect(await screen.findByTestId('insights-chart')).toHaveTextContent('insights:24:0:COUNT')
    // 1 header + 30 categories + the totals row in the tfoot.
    expect(screen.getAllByRole('row')).toHaveLength(32)
  })
})

/**
 * R7 (HD-141) — what this panel exports, and what it deliberately does not.
 *
 * **The image, yes**: an ad-hoc chart is exactly the thing somebody pastes back
 * into the conversation that prompted the question, and its footer carries the
 * QUERY, because unlike a fixed report this chart's identity is its dataset and
 * nothing else names it.
 *
 * **Neither CSV**: the series has no `.csv` variant (insights is a POST), and
 * "download matching issues" would be absurd here — the matching issues are the
 * result table this panel is sitting on top of.
 */
describe('InsightsPanel — export (R7)', () => {
  it('offers the picture, and no CSV of any kind', async () => {
    renderPanel()
    expect(await screen.findByRole('button', { name: /Copy image/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'PNG' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /CSV/ })).toBeNull()
  })

  it('offers no picture when there is no chart — a measure of NONE draws none', async () => {
    renderPanel({ state: { ...DEFAULT_STATE, measure: 'NONE' } })
    // The breakdown is still there; the chart is not, so neither is its export.
    await screen.findByRole('table')
    expect(screen.queryByRole('button', { name: /Copy image/ })).toBeNull()
  })
})
