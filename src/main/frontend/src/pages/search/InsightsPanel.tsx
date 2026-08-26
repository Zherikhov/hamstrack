import { Suspense, lazy, useMemo, useRef, type CSSProperties } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronUp } from 'lucide-react'
import { ApiResponseError, apiSearchInsights } from '../../api'
import { andClause } from '../../hql'
import type {
  InsightsBucket, InsightsDimension, InsightsMeasure, SearchSchema,
} from '../../types'
import { Select } from '../../components/ui'
import { REPORT_STALE_TIME } from '../../lib/queryKeys'
import {
  MetaLine, Notice, RateLimitNotice, SeriesTable, TruncationNotice, type SeriesColumn,
} from '../reports/common'
import {
  MAX_BARS, dimensionDef, foldInsights, formatInsightsValue, hiddenChoices, insightsCaption,
  measureUnit, narrowClause, normalizeInsights, offeredDimensions, offeredMeasures,
  type ChartRow, type InsightsState, type SegmentSeries,
} from './insights'
import { seriesColor } from './palette'
import { ChartExport } from '../reports/export'

// Split out of the panel chunk exactly as the report pages split theirs: the
// controls, the caption and the TABLE are readable — and clickable — before
// Recharts has parsed. The table is the accessible reading of this panel and it
// carries the same narrowing actions the bars do, so it may never wait on a
// chart library to arrive.
const InsightsChart = lazy(() => import('./InsightsChart'))

/**
 * **The Insights panel** (HD-140, R6 — reports-proposal §2.6): the answer to
 * everything the five fixed reports do not answer, and the deliberate
 * replacement for the configurable gadget dashboard §1.5 refuses.
 *
 * Its dataset is **the HQL query already in the search box**. Not a copy of it,
 * not a filter configured beside it — the same string, sent to the same
 * compiler, scoped by the same `SearchScope`. That is the property a widget grid
 * cannot have: two gadgets carry two filters and quietly count overlapping sets,
 * which is the single most consistent complaint in the research behind this
 * epic. Here there is one set, so two numbers on this panel cannot disagree.
 *
 * And it is a **navigation device as much as a chart**: clicking a bar narrows
 * the query in the box. Slice by status, see the pile in "In Progress", click
 * it, and you are looking at that pile. If a layout decision has to give, the
 * click-through stays — it is the half a dashboard can never do.
 *
 * Four rules it inherits from the rest of the epic, each of which is a sentence
 * on screen rather than a comment:
 *
 *  1. **Every chart ships its table.** The chart is `aria-hidden` decoration
 *     over a real `<table>`, which carries the same numbers in the same order —
 *     and, here, the same narrowing buttons, so the feature is reachable from a
 *     keyboard.
 *  2. **Provenance is printed, never hovered.** `computedAt` and
 *     `basedOnIssues` are on screen, and so is **each** of the two ways this
 *     report truncates: the axis at `sliceCap` and the cross tab at `cellCap`
 *     are different sentences, because a chart missing bars and a chart missing
 *     stacks are different pictures.
 *  3. **A many-valued slice is disclosed.** On labels an issue lands in several
 *     bars, so the bars sum to more than the issues matched. Unexplained, that
 *     is exactly the "these numbers don't match" complaint the epic's disclosure
 *     rules exist to prevent — so the bar sum is never called an issue count.
 *  4. **A capability gates the UI and never the answer.** The slice list omits
 *     `sprint` when no visible project runs sprints and the points measure when
 *     none estimates — from `/search/schema`'s own `insights` block — but a
 *     shared URL asking for either is sent as written, answered, and explained.
 *     A hidden control is not a permission.
 */
export default function InsightsPanel({
  wsId, query, hasQuery, schema, state, onState, onNarrow, onClose,
}: {
  wsId: string
  /** The COMMITTED query — the dataset, not the draft in the input. */
  query: string
  /** Whether a query has been run at all (`q` present in the URL). */
  hasQuery: boolean
  /** Drives which slices and measures are OFFERED. Never which are answered. */
  schema: SearchSchema | undefined
  state: InsightsState
  onState: (patch: Partial<InsightsState>) => void
  /** Hand back a narrowed query; the page commits it and re-runs everything. */
  onNarrow: (nextQuery: string) => void
  onClose: () => void
}) {
  const { measure, slice, segment } = state

  const insights = useQuery({
    queryKey: ['insights', wsId, query, measure, slice, segment ?? ''],
    queryFn: () => apiSearchInsights(wsId, {
      query,
      measure,
      slice,
      ...(segment ? { segment } : {}),
    }),
    enabled: !!wsId && hasQuery,
    staleTime: REPORT_STALE_TIME,
    // Narrower than the global default — queryClient.ts owns the never-retry-a-422/429
    // rule. This surface is expensive enough to decline a retry for *any* failure.
    retry: false,
  })

  const data = useMemo(() => normalizeInsights(insights.data), [insights.data])
  // ONE fold feeds the chart and the table, so the picture and the numbers under
  // it cannot disagree about what was grouped into "Other".
  const fold = useMemo(() => foldInsights(data, measure), [data, measure])
  const { rows, series } = fold
  const drawn = rows.slice(0, MAX_BARS)
  const error = insights.error instanceof ApiResponseError ? insights.error : null

  const sliceDef = dimensionDef(slice)
  const hidden = hiddenChoices(state, schema)
  const measures = offeredMeasures(schema)
  const dimensions = offeredDimensions(schema)
  const chartRef = useRef<HTMLDivElement>(null)
  const unclickable = rows.filter(r => !narrowClause(r.bucket)).length

  /** Narrow the query to one bar (and optionally one band inside it). */
  function narrow(sliceBucket: InsightsBucket, segmentBucket: InsightsBucket | null) {
    const first = narrowClause(sliceBucket)
    if (!first) return
    let next = andClause(query, first)
    if (segmentBucket) {
      const second = narrowClause(segmentBucket)
      if (second) next = andClause(next, second)
    }
    onNarrow(next)
  }

  // Deliberately NOT memoised: `narrow` closes over the current query and state,
  // which are precisely what these columns must never go stale against — a
  // memoised column set is how a row would narrow the query it was rendered
  // against rather than the one on screen. Building a handful of column objects
  // per render costs nothing next to that.
  const columns = tableColumns({
    measure, slice, series, barSum: fold.barSum, onNarrow: narrow,
  })

  return (
    <section
      aria-label="Insights"
      className="flex-shrink-0 border-b"
      style={{
        background: 'var(--color-card)',
        borderColor: 'var(--color-border)',
        maxHeight: '58vh',
        overflow: 'auto',
      }}
    >
      <div className="flex flex-col gap-3" style={{ padding: '14px 20px 18px' }}>
        {/* ── Header + controls ─────────────────────────────────────────── */}
        <div className="flex flex-wrap items-end gap-2">
          <div style={{ marginRight: 'auto' }}>
            <h2 style={{ fontSize: 15, fontWeight: 800, margin: 0 }}>Insights</h2>
            <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)', margin: 0 }}>
              These numbers describe exactly the issues this query matches — nothing wider,
              nothing narrower.
            </p>
          </div>

          <Select
            label="Measure" aria-label="Measure" compact value={measure}
            onChange={e => onState({ measure: e.target.value as InsightsMeasure })}
          >
            {measures.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
          </Select>
          <Select
            label="Slice" aria-label="Slice" compact value={slice}
            onChange={e => onState({ slice: e.target.value as InsightsDimension })}
          >
            {dimensions.map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
          </Select>
          {measure !== 'NONE' && (
            <Select
              label="Colour by" aria-label="Colour by" compact value={segment ?? ''}
              onChange={e => onState({ segment: (e.target.value || null) as InsightsDimension | null })}
            >
              <option value="">Nothing</option>
              {/* The endpoint refuses slice === segment with a 422, so the pair is
                  not offerable in the first place. */}
              {dimensions.filter(d => d.key !== slice).map(d => (
                <option key={d.key} value={d.key}>{d.label}</option>
              ))}
            </Select>
          )}
          <button
            onClick={onClose}
            aria-label="Hide insights"
            title="Hide insights"
            className="cursor-pointer flex-shrink-0"
            style={{ color: 'var(--color-text-muted)', padding: 6 }}
          >
            <ChevronUp size={16} />
          </button>
        </div>

        {/* ── Notices ───────────────────────────────────────────────────── */}
        {!hasQuery && (
          <Notice>
            Run a query first. This panel has no filter of its own — that is the point of it — so
            it charts whatever is in the search box, and there is nothing there yet.
          </Notice>
        )}

        {hidden.length > 0 && (
          <Notice>
            This link asks for{' '}
            {hidden.map((choice, i) => (
              <span key={choice}>
                {i > 0 && (i === hidden.length - 1 ? ' and ' : ', ')}
                <b>{choice}</b>
              </span>
            ))}
            , which no project in this workspace currently offers — so the control is not in the
            toolbar above. The numbers below are real and were computed anyway: a capability
            decides what this workspace <em>suggests</em>, never what it will answer.
          </Notice>
        )}

        {error?.status === 422 && (
          <Notice tone="warn">
            <b>This query didn’t run</b>, so there is nothing to aggregate. The error is shown with
            the search box above — fix the query there and this panel follows it.
          </Notice>
        )}
        {error?.status === 429 && (
          <RateLimitNotice retryAfter={error.retryAfter} onRetry={() => void insights.refetch()} />
        )}
        {insights.error && !HANDLED_STATUSES.includes(error?.status ?? 0) && (
          <Notice tone="warn">
            Couldn’t compute insights: {error?.detail ?? 'the request failed. Try again.'}
          </Notice>
        )}

        {/* Always mounted, so the row-cap case can never be forgotten — it is
            always false on this report, which aggregates in SQL and materialises
            no issue rows. What DOES truncate here has its own two notices. */}
        <TruncationNotice meta={data?.meta} />

        {data?.slicesTruncated && (
          <Notice tone="warn">
            <b>This query has more {sliceDef.plural} than the panel will chart.</b> It is showing
            the largest <span className="mono">{data.sliceCap.toLocaleString()}</span> by{' '}
            {measure === 'NONE' ? 'issue count' : measureUnit(measure)}, and the rest are not below
            at all — neither in the chart nor in the table. Narrow the query to see them.
          </Notice>
        )}

        {data?.cellsTruncated && (
          <Notice tone="warn">
            <b>The colour breakdown is truncated.</b> The bars are exact totals, but their stacks
            are capped at <span className="mono">{data.cellCap.toLocaleString()}</span>{' '}
            combinations — so a bar can be taller than the bands drawn inside it. That gap is
            missing stacks, not missing issues.
          </Notice>
        )}

        {/* The one disclosure that changes what the numbers MEAN. */}
        {data?.sliceMultiValued && rows.length > 0 && (
          <Notice>
            An issue can carry several {sliceDef.plural}, so it is counted in each of their bars —{' '}
            <b>
              these bars deliberately sum to more than the{' '}
              {data.meta.basedOnIssues.toLocaleString()} issue
              {data.meta.basedOnIssues === 1 ? '' : 's'} matched
            </b>. Nothing is double-counted inside a bar; the total under the table is the sum of
            the bars, not an issue count.
          </Notice>
        )}

        {/* Documented failure mode #4 (§1.2, §6): unestimated work weighs zero in
            a points measure, so a bar reads low by exactly the work nobody sized.
            Never silently treated as zero — printed in both measures, because it
            is what a points view of this same query WOULD drop. */}
        {data && fold.unestimated > 0 && measure !== 'NONE' && (
          <Notice tone={measure === 'POINTS' ? 'warn' : 'info'}>
            <b>
              {fold.unestimated.toLocaleString()} issue{fold.unestimated === 1 ? '' : 's'} in these
              bars {fold.unestimated === 1 ? 'has' : 'have'} no estimate.
            </b>{' '}
            {measure === 'POINTS'
              ? <>They weigh <b>zero points</b>, so every bar holding one reads low by exactly the
                work nobody sized. Switch the measure to <b>issue count</b> to see all of it.</>
              : <>Counting issues, every one of them is below and nothing here is understated. The
                figure is shown because a <b>story points</b> view of this same query would drop it
                silently.</>}
          </Notice>
        )}

        {data && rows.length > MAX_BARS && (
          <Notice>
            The chart draws the first <b>{MAX_BARS}</b> of{' '}
            <b>{rows.length.toLocaleString()}</b> {sliceDef.plural}. Nothing is hidden by that —{' '}
            <b>the table below lists every one</b>, and each row narrows the query the same way a
            bar does. A bar chart with {rows.length.toLocaleString()} bars is not a smaller version
            of a readable one.
          </Notice>
        )}

        {data && unclickable > 0 && (
          <Notice>
            {unclickable === rows.length
              ? <>None of these bars can be clicked through.</>
              : <><b>{unclickable.toLocaleString()}</b> of these bars cannot be clicked through.</>}{' '}
            {slice === 'PROJECT'
              ? <>HQL has no <span className="mono">project</span> field, so there is no query that
                means “only this one”. Every other slice narrows on click.</>
              : <>A {sliceDef.label.toLowerCase()} whose name is used by two projects you can see,
                or one outside query name-resolution (a completed sprint, an archived label), has
                no clause matching <em>exactly</em> its bar — and a click returning a wider or
                different set would be worse than one that does nothing.</>}
          </Notice>
        )}

        {/* ── The chart ─────────────────────────────────────────────────── */}
        {data && rows.length > 0 && (
          <>
            <div className="flex flex-wrap items-start gap-3">
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0 }}>
                {insightsCaption(data, slice, segment)}
              </p>
              {/* R7: this panel gets the **image** and neither CSV, and both
                  halves of that are deliberate. A picture is exactly what an
                  ad-hoc chart is for — it is the artefact somebody pastes into
                  the conversation that prompted the question — and its footer
                  carries the QUERY, because unlike a report this chart's identity
                  is its dataset and nothing else names it. The series CSV has no
                  endpoint here (insights is a POST, with no `.csv` variant), and
                  "matching issues" would be absurd: the matching issues are the
                  result table this panel is sitting on top of. */}
              {measure !== 'NONE' && (
                <ChartExport
                  chartRef={chartRef}
                  title={`${sliceDef.label} breakdown`}
                  subtitle={insightsCaption(data, slice, segment)}
                  slug={`insights-${slice.toLowerCase()}`}
                  projectKey={null}
                  provenance={{
                    project: 'Search insights',
                    window: query.trim()
                      ? `query: ${query.trim()}`
                      : 'every issue you can see (no query)',
                    computedAt: data.meta.computedAt,
                    basedOnIssues: data.meta.basedOnIssues,
                    truncated: data.meta.truncated || data.slicesTruncated || data.cellsTruncated,
                    cap: data.meta.truncated ? data.meta.cap : data.sliceCap,
                  }}
                />
              )}
            </div>

            {measure === 'NONE' ? (
              <Notice>
                No measure is selected, so nothing has a height and no chart is drawn. This is the
                breakdown on its own — which {sliceDef.plural} these results contain, and a way
                into each of them.
              </Notice>
            ) : (
              <>
                {series.length > 0 && (
                  <div className="flex flex-wrap items-center gap-3">
                    {series.map((s, i) => <SegmentSwatch key={s.key} series={s} index={i} />)}
                  </div>
                )}
                <div ref={chartRef}>
                  <Suspense fallback={<div style={{ height: 300 }} />}>
                    <InsightsChart
                      rows={drawn}
                      series={series}
                      measure={measure}
                      onNarrow={narrow}
                    />
                  </Suspense>
                </div>
              </>
            )}

            {/* ── The table: same numbers, same order, and the accessible
                   (and keyboard) path to the click-through. ─────────────── */}
            <SeriesTable
              caption={
                `${sliceDef.label} breakdown`
                + (measure === 'NONE' ? '' : ` — ${measureUnit(measure)}`)
                + (segment ? `, coloured by ${dimensionDef(segment).label.toLowerCase()}` : '')
              }
              columns={columns}
              rows={rows}
              rowKey={r => r.key}
              footer={measure === 'NONE' ? undefined : (
                <tfoot>
                  <tr>
                    <td style={FOOT} colSpan={Math.max(1, columns.length - 2)}>
                      {/* NEVER "total issues": on a many-valued slice, and on a
                          truncated one, this is not the issue count, and saying
                          so would be the exact mismatch the notices above exist
                          to prevent. */}
                      Sum of the bars — {measureUnit(measure)}
                    </td>
                    <td style={{ ...FOOT, textAlign: 'right' }}>
                      {formatInsightsValue(fold.barSum, measure)}
                    </td>
                    <td style={{ ...FOOT, textAlign: 'right' }}>100%</td>
                  </tr>
                </tfoot>
              )}
            />

            <div className="flex flex-col gap-1.5">
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)', margin: 0 }}>
                Clicking a bar or a row <b>narrows the query in the box</b> and re-runs everything
                on this page, this panel included. The clause comes from the server, which emits
                one only when it returns precisely the issues that bar counted.
              </p>
              <MetaLine meta={data.meta} />
            </div>
          </>
        )}

        {data && rows.length === 0 && !insights.isFetching && (
          <p className="text-sm" style={{ color: 'var(--color-text-muted)', margin: 0 }}>
            This query matched nothing, so there is nothing to break down.
          </p>
        )}

        {insights.isPending && hasQuery && !error && (
          <p className="mono text-sm" style={{ color: 'var(--color-text-muted)', margin: 0 }}>
            computing insights…
          </p>
        )}
      </div>
    </section>
  )
}

/** The statuses this panel renders a sentence of its own for. */
const HANDLED_STATUSES = [422, 429]

const FOOT: CSSProperties = {
  padding: '6px 10px',
  borderTop: '1px solid var(--color-border-2)',
  color: 'var(--color-text-secondary)',
  fontWeight: 700,
  whiteSpace: 'nowrap',
  textAlign: 'left',
}

function SegmentSwatch({ series, index }: { series: SegmentSeries; index: number }) {
  return (
    <span className="inline-flex items-center gap-2" style={{ fontSize: 12.5, color: 'var(--color-text-secondary)' }}>
      <span
        aria-hidden="true"
        style={{
          width: 12, height: 12, borderRadius: 3, display: 'inline-block',
          background: seriesColor(series, index),
        }}
      />
      {series.label}
    </span>
  )
}

/**
 * The table's columns — the category, one per colour series, then the bar total
 * and its share.
 *
 * The first cell is a real `<button>` wherever the bucket is clickable, which is
 * what makes the click-through reachable without a mouse: the chart above is
 * `aria-hidden`, so a narrowing action that existed only on a bar would exist
 * only for sighted mouse users. Where it is NOT clickable the label is plain
 * text with the reason on its `title`, rather than a dead button.
 */
function tableColumns({ measure, slice, series, barSum, onNarrow }: {
  measure: InsightsMeasure
  slice: InsightsDimension
  series: SegmentSeries[]
  barSum: number
  onNarrow: (slice: InsightsBucket, segment: InsightsBucket | null) => void
}): SeriesColumn<ChartRow>[] {
  const columns: SeriesColumn<ChartRow>[] = [{
    key: 'label',
    label: dimensionDef(slice).label,
    align: 'left',
    render: (row: ChartRow) => {
      const clause = narrowClause(row.bucket)
      if (!clause) {
        return (
          <span title="No query matches exactly this bar, so it cannot be clicked through">
            {row.label}
          </span>
        )
      }
      return (
        <button
          onClick={() => onNarrow(row.bucket, null)}
          className="cursor-pointer text-left"
          title={`Narrow the query: ${clause}`}
          style={{ color: 'var(--color-brand)', fontWeight: 600 }}
        >
          {row.label}
        </button>
      )
    },
  }]

  if (measure === 'NONE') return columns

  for (const s of series) {
    columns.push({
      key: s.key,
      label: s.label,
      render: (row: ChartRow) => formatInsightsValue(row.values[s.key] ?? 0, measure),
    })
  }

  columns.push({
    key: '__total',
    label: `Total (${measureUnit(measure)})`,
    render: (row: ChartRow) => formatInsightsValue(row.total, measure),
  })
  columns.push({
    key: '__share',
    label: 'Share of bars',
    render: (row: ChartRow) => (barSum > 0 ? `${((row.total / barSum) * 100).toFixed(1)}%` : '—'),
  })
  return columns
}
