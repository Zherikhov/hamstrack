import { describe, it, expect } from 'vitest'
import type {
  InsightsBucket, InsightsCellValue, InsightsResponse, InsightsSlice, SearchSchema,
} from '../../types'
import {
  INSIGHTS_DIMENSIONS, INSIGHTS_MEASURES, MAX_SERIES, OTHER_KEY, foldInsights,
  formatInsightsValue, hiddenChoices, insightsCaption, narrowClause, normalizeInsights,
  offeredDimensions, offeredMeasures, readInsightsState, writeInsightsParams,
} from './insights'

/**
 * HD-140 (R6) — the pure half of the Insights panel, which is where the rules
 * that make it a feature rather than a chart actually live:
 *
 *  1. **the click-through is the SERVER's clause, never ours.** `bucket.hql` is
 *     null exactly when a fragment would return a different set than the bar it
 *     sits on, and the whole value of that null is lost the moment a client
 *     "helpfully" rebuilds the clause from the label;
 *  2. **one fold feeds the chart and the table.** A second computation of what
 *     went into "Other" is a second chance for the picture and the numbers under
 *     it to disagree;
 *  3. **suggestions narrow, resolution never does** — a capability decides what
 *     the toolbar OFFERS and never what the panel will render, so a URL naming a
 *     hidden slice is honoured and disclosed, never refused.
 */

function bucket(over: Partial<InsightsBucket> = {}): InsightsBucket {
  return { id: 'c1', label: 'In Progress', hql: 'status = "In Progress"', ...over }
}

function slice(over: Partial<InsightsSlice> = {}): InsightsSlice {
  return { bucket: bucket(), count: 10, points: 21, unestimatedCount: 0, ...over }
}

function cell(over: Partial<InsightsCellValue> = {}): InsightsCellValue {
  return { sliceId: 'c1', segmentId: 't1', count: 4, points: 8, unestimatedCount: 0, ...over }
}

function report(over: Partial<InsightsResponse> = {}): InsightsResponse {
  return {
    measure: 'COUNT', slice: 'STATUS', segment: null,
    slices: [slice()], segments: [], cells: [],
    sliceMultiValued: false, segmentMultiValued: false,
    slicesTruncated: false, cellsTruncated: false,
    sliceCap: 200, cellCap: 1000,
    meta: {
      computedAt: '2026-08-20T09:00:00Z', basedOnIssues: 10, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

function schema(over: Partial<NonNullable<SearchSchema['insights']>> = {}): SearchSchema {
  return {
    fields: [],
    keywords: [],
    values: {},
    insights: {
      measures: ['COUNT', 'NONE'],
      dimensions: ['STATUS', 'TYPE', 'PRIORITY', 'ASSIGNEE', 'COMPONENT', 'LABEL', 'PROJECT'],
      ...over,
    },
  }
}

// ── The click-through ────────────────────────────────────────────────────────

describe('narrowClause — the server owns the clause', () => {
  it('is the fragment the server sent, verbatim', () => {
    expect(narrowClause(bucket({ hql: 'assignee = "alex@example.com"' })))
      .toBe('assignee = "alex@example.com"')
    expect(narrowClause(bucket({ hql: 'sprint IS EMPTY' }))).toBe('sprint IS EMPTY')
  })

  it('is null when the server withheld one — and is NOT reconstructed from the label', () => {
    // The server withholds a fragment for three reasons the client cannot tell
    // apart: PROJECT has no HQL field, a name owned by two visible projects
    // would match WIDER than the bar, and a completed sprint or archived label
    // would 422 on click. Guessing `status = "<label>"` here re-introduces all
    // three as clicks that quietly answer a different question.
    expect(narrowClause(bucket({ hql: null, label: 'Payments' }))).toBeNull()
    expect(narrowClause(bucket({ hql: '   ' }))).toBeNull()
  })
})

// ── URL state ────────────────────────────────────────────────────────────────

describe('readInsightsState / writeInsightsParams', () => {
  it('is closed and defaulted when the URL says nothing', () => {
    expect(readInsightsState(new URLSearchParams('q=x')))
      .toEqual({ open: false, measure: 'COUNT', slice: 'STATUS', segment: null })
  })

  it('reads a full panel out of a shared link', () => {
    const s = readInsightsState(new URLSearchParams(
      'q=status+%3D+%22Done%22&insights=1&measure=POINTS&slice=ASSIGNEE&segment=TYPE'))
    expect(s).toEqual({ open: true, measure: 'POINTS', slice: 'ASSIGNEE', segment: 'TYPE' })
  })

  it('degrades an unrecognised value to the default without announcing it', () => {
    const s = readInsightsState(new URLSearchParams('insights=1&measure=BANANAS&slice=NOPE'))
    expect(s.measure).toBe('COUNT')
    expect(s.slice).toBe('STATUS')
  })

  it('drops a segment equal to the slice — the endpoint 422s that pair', () => {
    expect(readInsightsState(new URLSearchParams('insights=1&slice=TYPE&segment=TYPE')).segment)
      .toBeNull()
  })

  it('drops the segment under measure NONE — nothing has a height to stack', () => {
    expect(readInsightsState(new URLSearchParams('insights=1&measure=NONE&segment=TYPE')).segment)
      .toBeNull()
  })

  it('round-trips through the URL, keeping `q` — the dataset travels with the panel', () => {
    const start = new URLSearchParams('q=status+%3D+%22Done%22')
    const written = writeInsightsParams(start, {
      open: true, measure: 'POINTS', slice: 'ASSIGNEE', segment: 'TYPE',
    })
    expect(written.get('q')).toBe('status = "Done"')
    expect(readInsightsState(written))
      .toEqual({ open: true, measure: 'POINTS', slice: 'ASSIGNEE', segment: 'TYPE' })
  })

  it('closing removes all four keys rather than writing insights=0', () => {
    const open = new URLSearchParams('q=x&insights=1&measure=POINTS&slice=TYPE&segment=STATUS')
    const closed = writeInsightsParams(open, {
      open: false, measure: 'POINTS', slice: 'TYPE', segment: 'STATUS',
    })
    expect(closed.toString()).toBe('q=x')
  })

  it('does not mutate the params it was handed', () => {
    const start = new URLSearchParams('q=x')
    writeInsightsParams(start, { open: true, measure: 'COUNT', slice: 'TYPE', segment: null })
    expect(start.toString()).toBe('q=x')
  })
})

// ── Capability: suggestions narrow, resolution never does ────────────────────

describe('offered controls come from /search/schema, and bound the controls only', () => {
  it('offers exactly what the schema published', () => {
    expect(offeredMeasures(schema()).map(m => m.key)).toEqual(['COUNT', 'NONE'])
    expect(offeredDimensions(schema()).map(d => d.key)).not.toContain('SPRINT')
  })

  it('offers everything when the schema is silent — a thin response is not a capability', () => {
    // Also the compatibility path for a server that predates the panel.
    expect(offeredDimensions(undefined)).toHaveLength(INSIGHTS_DIMENSIONS.length)
    expect(offeredMeasures({ fields: [], keywords: [], values: {} }))
      .toHaveLength(INSIGHTS_MEASURES.length)
  })

  it('names a hidden choice a URL asked for instead of refusing or clamping it', () => {
    expect(hiddenChoices(
      { open: true, measure: 'POINTS', slice: 'SPRINT', segment: null },
      schema(),
    )).toEqual(['story points', 'sprint'])
  })

  it('names nothing when every asked-for choice is offered', () => {
    expect(hiddenChoices(
      { open: true, measure: 'COUNT', slice: 'STATUS', segment: 'TYPE' },
      schema(),
    )).toEqual([])
  })
})

// ── The fold ─────────────────────────────────────────────────────────────────

describe('foldInsights — one fold for the chart and the table', () => {
  it('reads the bar height from the SLICE total, not from the sum of its cells', () => {
    // The two caps are independent, so a bar is exact while its stacks may be
    // truncated. Deriving the height from the cells would make a truncated
    // response quietly understate its own chart.
    const data = report({
      segment: 'TYPE',
      slices: [slice({ count: 10 })],
      segments: [bucket({ id: 't1', label: 'Bug', hql: 'type = "Bug"' })],
      cells: [cell({ count: 4 })],
    })
    const fold = foldInsights(data, 'COUNT')
    expect(fold.rows[0].total).toBe(10)
    expect(fold.rows[0].values['t1']).toBe(4)
  })

  it('switches measure without a refetch — both numbers are already on the row', () => {
    const data = report({ slices: [slice({ count: 10, points: 21 })] })
    expect(foldInsights(data, 'COUNT').rows[0].total).toBe(10)
    expect(foldInsights(data, 'POINTS').rows[0].total).toBe(21)
    expect(foldInsights(data, 'NONE').rows[0].total).toBe(0)
  })

  it('treats a null point sum as zero rather than as NaN', () => {
    const data = report({ slices: [slice({ points: null })] })
    expect(foldInsights(data, 'POINTS').rows[0].total).toBe(0)
  })

  it('ranks the legend by its share and folds everything past the fifth hue', () => {
    const segments = ['a', 'b', 'c', 'd', 'e', 'f', 'g']
      .map(id => bucket({ id, label: id, hql: `type = "${id}"` }))
    const cells = segments.map((s, i) => cell({ segmentId: s.id, count: 10 - i }))
    const fold = foldInsights(report({ segment: 'TYPE', segments, cells }), 'COUNT')

    expect(fold.series).toHaveLength(MAX_SERIES + 1)
    const other = fold.series[fold.series.length - 1]
    expect(other.key).toBe(OTHER_KEY)
    expect(other.other).toBe(true)
    expect(other.label).toBe('Other (2)')
    expect(other.total).toBe(5 + 4)
    // Unclickable: there is no single value to narrow to.
    expect(other.bucket).toBeUndefined()
  })

  it('routes every folded cell into "Other", so no number is silently dropped', () => {
    const segments = ['a', 'b', 'c', 'd', 'e', 'f', 'g']
      .map(id => bucket({ id, label: id, hql: `type = "${id}"` }))
    const cells = segments.map((s, i) => cell({ segmentId: s.id, count: 10 - i }))
    const fold = foldInsights(report({ segment: 'TYPE', segments, cells }), 'COUNT')
    const drawn = Object.values(fold.rows[0].values).reduce((n, v) => n + v, 0)
    expect(drawn).toBe(cells.reduce((n, c) => n + c.count, 0))
    expect(fold.rows[0].values[OTHER_KEY]).toBe(5 + 4)
  })

  it('does not fold when five or fewer would each get their own hue', () => {
    const segments = ['a', 'b', 'c', 'd', 'e'].map(id => bucket({ id, label: id }))
    const cells = segments.map((s, i) => cell({ segmentId: s.id, count: 5 - i }))
    expect(foldInsights(report({ segment: 'TYPE', segments, cells }), 'COUNT')
      .series.some(s => s.other)).toBe(false)
  })

  it('carries no segment columns at all when unsegmented', () => {
    const fold = foldInsights(report(), 'COUNT')
    expect(fold.series).toEqual([])
    expect(fold.rows[0].values).toEqual({})
  })

  it('sums the bars and the unestimated issues across them', () => {
    const data = report({
      slices: [
        slice({ bucket: bucket({ id: 's1' }), count: 10, unestimatedCount: 2 }),
        slice({ bucket: bucket({ id: 's2' }), count: 8, unestimatedCount: 1 }),
      ],
    })
    const fold = foldInsights(data, 'COUNT')
    expect(fold.barSum).toBe(18)
    expect(fold.unestimated).toBe(3)
  })

  it('folds nothing at all out of an absent response', () => {
    expect(foldInsights(undefined, 'COUNT'))
      .toEqual({ rows: [], series: [], barSum: 0, unestimated: 0 })
  })
})

// ── Defensive normalisation ──────────────────────────────────────────────────

describe('normalizeInsights', () => {
  it('fills the fallbacks a thin response leaves out, without inventing meta', () => {
    const raw = {
      measure: 'COUNT', slice: 'STATUS', segment: null,
      slices: [{ bucket: { id: 'x', label: 'Open' }, count: 3 }],
    } as unknown as InsightsResponse
    const out = normalizeInsights(raw)!
    expect(out.segments).toEqual([])
    expect(out.cells).toEqual([])
    expect(out.slices[0].bucket.hql).toBeNull()
    expect(out.slices[0].points).toBeNull()
    expect(out.slicesTruncated).toBe(false)
    // Provenance is never fabricated: an unknown computedAt stays unknown.
    expect(out.meta.computedAt).toBe('')
    expect(out.meta.unmatchedFilters).toEqual([])
  })

  it('names the empty bucket "None" when the server sent no label for it', () => {
    const raw = {
      slices: [{ bucket: { id: null, label: '' }, count: 2 }],
    } as unknown as InsightsResponse
    expect(normalizeInsights(raw)!.slices[0].bucket.label).toBe('None')
  })

  it('degrades a non-numeric count to 0 rather than to NaN', () => {
    const raw = {
      slices: [{ bucket: { id: 'x', label: 'Open' }, count: null }],
    } as unknown as InsightsResponse
    expect(normalizeInsights(raw)!.slices[0].count).toBe(0)
  })
})

// ── Copy ─────────────────────────────────────────────────────────────────────

describe('formatting and the caption', () => {
  it('prints an em dash under NONE — a zero would be a claim', () => {
    expect(formatInsightsValue(0, 'NONE')).toBe('—')
    expect(formatInsightsValue(0, 'COUNT')).toBe('0')
  })

  it('keeps a fractional point sum to one decimal and an integer bare', () => {
    expect(formatInsightsValue(18.5, 'POINTS')).toBe('18.5')
    expect(formatInsightsValue(18, 'POINTS')).toBe('18')
  })

  it('counts ISSUES from meta, never the bar sum — those differ on a label slice', () => {
    const data = report({
      slices: [slice({ bucket: bucket({ id: 's1' }) }), slice({ bucket: bucket({ id: 's2' }) })],
    })
    expect(insightsCaption(data, 'STATUS', null)).toBe('10 issues across 2 statuses')
    expect(insightsCaption(data, 'STATUS', 'TYPE'))
      .toBe('10 issues across 2 statuses, coloured by type')
  })

  it('spells the singular when there is one bar', () => {
    expect(insightsCaption(report(), 'LABEL', null)).toBe('10 issues across 1 label')
  })
})
