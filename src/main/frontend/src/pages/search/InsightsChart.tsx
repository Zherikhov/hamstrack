import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { InsightsBucket, InsightsMeasure } from '../../types'
import { CHART_AXIS, CHART_GRID, CHART_SERIES } from '../reports/common'
import { niceAxis } from '../reports/scale'
import {
  formatInsightsValue, measureUnit, narrowClause, type ChartRow, type SegmentSeries,
} from './insights'
import { seriesColor } from './palette'

/**
 * The Insights bars (HD-140, §2.6) — **a control surface that happens to be a
 * chart**.
 *
 * `onNarrow` is the feature. A bar (and, when the chart is stacked, a band
 * inside it) hands its bucket back so the panel can rewrite the HQL query. A
 * bucket the server could not express as a fragment is **not wired up at all**
 * rather than wired to something approximate: `bucket.hql` is null exactly when
 * a clause would return a different set than the bar, and a click that quietly
 * answers a different question is worse than a bar that does not respond.
 *
 * Colour is the ramp by index, capped at five hues with the tail in the context
 * grey — DESIGN.md's limit, because colours 6–10 of any ramp are not reliably
 * distinguishable for colour-blind readers. It carries no meaning; the legend
 * and the table underneath are the source of truth.
 *
 * Recharts is imported HERE and nowhere outside the lazy chunks: this file is
 * `lazy()`-imported by `InsightsPanel`, which is itself `lazy()`-imported by
 * `SearchResultsPage` — and that page is in the **main bundle**, so an eager
 * import anywhere on this path would ship the chart library to every user on
 * every page load. The reports chunk shares the same copy.
 *
 * The chart is `aria-hidden`: the accessible reading — and the keyboard path to
 * the click-through — is the table underneath, which carries exactly these
 * numbers and exactly these narrowing actions.
 */
export default function InsightsChart({
  rows, series, measure, onNarrow, height = 300,
}: {
  rows: ChartRow[]
  /** Empty when unsegmented. */
  series: SegmentSeries[]
  measure: InsightsMeasure
  /** A bar (segment `null`) or a band was clicked. */
  onNarrow: (slice: InsightsBucket, segment: InsightsBucket | null) => void
  height?: number
}) {
  // Zero-based with fixed round ticks, never auto-scaled to the data (§1.6 #4):
  // two screenshots of one query a week apart have to be comparable. Stacked, so
  // the axis is measured against the bar TOTAL and not its tallest band.
  const axis = niceAxis(Math.max(0, ...rows.map(r => r.total)))
  const data = rows.map(r => ({ ...r.values, __label: r.label, __total: r.total }))

  return (
    <div aria-hidden="true" style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 12, bottom: 4, left: -8 }}>
          <CartesianGrid stroke={CHART_GRID} vertical={false} />
          <XAxis
            dataKey="__label"
            tickFormatter={shortLabel}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            interval={0}
          />
          <YAxis
            domain={[0, axis.max]}
            ticks={axis.ticks}
            allowDecimals={measure === 'POINTS'}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            width={44}
          />
          <Tooltip
            isAnimationActive={false}
            cursor={{ fill: 'color-mix(in srgb, var(--color-chart-grid) 45%, transparent)' }}
            content={props => (
              <InsightsTooltip
                measure={measure}
                series={series}
                active={props.active}
                payload={props.payload}
              />
            )}
          />

          {series.length === 0 ? (
            <Bar
              dataKey="__total"
              name="Total"
              isAnimationActive={false}
              radius={[6, 6, 0, 0]}
              fill={CHART_SERIES[0]}
              cursor="pointer"
              onClick={(_entry, index) => {
                const row = rows[index]
                if (row && narrowClause(row.bucket)) onNarrow(row.bucket, null)
              }}
            />
          ) : (
            series.map((s, i) => (
              <Bar
                key={s.key}
                dataKey={s.key}
                name={s.label}
                stackId="insights"
                isAnimationActive={false}
                fill={seriesColor(s, i)}
                cursor={s.other ? 'default' : 'pointer'}
                // The top band of the stack carries the rounded corners; the ones
                // underneath must stay square or the stack reads as separate bars.
                radius={i === series.length - 1 ? [6, 6, 0, 0] : undefined}
                onClick={(_entry, index) => {
                  const row = rows[index]
                  if (!row || s.other || !s.bucket) return
                  if (!narrowClause(row.bucket)) return
                  onNarrow(row.bucket, s.bucket)
                }}
              />
            ))
          )}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * Category labels are free text — an issue type, a component, somebody's full
 * name — so the axis gets an abbreviated one and the full string lives in the
 * tooltip and the table. Truncated rather than rotated so the bars keep the same
 * size whatever the slice.
 */
export function shortLabel(label: string): string {
  return label.length > 14 ? `${label.slice(0, 13)}…` : label
}

function InsightsTooltip({ measure, series, payload, active }: {
  measure: InsightsMeasure
  series: SegmentSeries[]
  payload?: readonly { payload?: unknown }[]
  active?: boolean
}) {
  const row = active
    ? payload?.[0]?.payload as (Record<string, number> & { __label?: string; __total?: number }) | undefined
    : undefined
  if (!row) return null
  return (
    <div
      style={{
        background: 'var(--color-card)',
        border: '1px solid var(--color-border-2)',
        borderRadius: 'var(--radius-md)',
        boxShadow: 'var(--shadow-pop)',
        fontSize: 12,
        padding: '8px 10px',
        maxWidth: 300,
      }}
    >
      <div style={{ fontWeight: 700 }}>{row.__label}</div>
      <div style={{ marginTop: 4 }}>
        <b>{formatInsightsValue(row.__total ?? 0, measure)}</b> {measureUnit(measure)}
      </div>
      {series.length > 0 && (
        <div style={{ color: 'var(--color-text-secondary)', marginTop: 4 }}>
          {series.map((s, i) => (
            <div key={s.key} className="flex items-center gap-1.5">
              <span
                style={{
                  width: 9, height: 9, borderRadius: 2, flexShrink: 0,
                  background: seriesColor(s, i), display: 'inline-block',
                }}
              />
              {s.label}: {formatInsightsValue(row[s.key] ?? 0, measure)}
            </div>
          ))}
        </div>
      )}
      <div style={{ color: 'var(--color-text-muted)', marginTop: 4 }}>
        Click to narrow the query
      </div>
    </div>
  )
}
