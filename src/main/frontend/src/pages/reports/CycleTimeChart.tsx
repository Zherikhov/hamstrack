import {
  CartesianGrid, ReferenceLine, ResponsiveContainer, Scatter, ScatterChart, Tooltip,
  XAxis, YAxis, ZAxis,
} from 'recharts'
import type { CycleMeasure, CycleTimeItem } from '../../types'
import { CHART_AXIS, CHART_CONTEXT, CHART_GRID, CHART_SERIES } from './common'
import { MEASURE_LABEL, formatDays, measureValue } from './cycle'
import { niceAxis } from './scale'

/**
 * The finished-work half (§2.2): **one dot per completed issue**, x = the day it
 * closed, y = how many days it took, with p50 and p85 drawn across it.
 *
 * Recharts, imported only inside the lazy reports chunk — the same rule as
 * `FlowChart`, and the reason the library was chosen at all: this scatter, with
 * its reference lines and per-point click-through, is the thing hand-rolled SVG
 * was not worth doing.
 *
 * Three rules the shape of this chart encodes:
 *
 *  1. **No rolling average, ever** (§1.7, §2.2). A smoothed line through this
 *     cloud hides the outlier the report exists to name, and readers take a
 *     trend line for a forecast. The only summary drawn is the two percentiles,
 *     and they come from the server.
 *  2. **The x axis is the WINDOW, not the data.** It spans `from`…`to` whatever
 *     the points do, so two screenshots of the same window are comparable and a
 *     quiet fortnight looks quiet instead of being zoomed away (§1.6 #4).
 *  3. **The y axis is zero-based with fixed round ticks** (`niceAxis`), and its
 *     top always clears p85 — a reference line off the top of the chart is a
 *     line the reader silently never sees.
 *
 * A dot is clickable through to its issue, which is the point of plotting issues
 * rather than a distribution. The chart is `aria-hidden` all the same: the
 * accessible reading — and the keyboard path to those same issues — is the table
 * underneath, whose keys are real links.
 */
export interface CyclePoint {
  x: number
  y: number
  item: CycleTimeItem
}

export default function CycleTimeChart({
  items, measure, from, to, p50, p85, onSelect, height = 320,
}: {
  items: CycleTimeItem[]
  measure: CycleMeasure
  /** Window bounds (`YYYY-MM-DD`, inclusive) — the axis domain, echoed by the server. */
  from: string
  to: string
  p50: number | null
  p85: number | null
  onSelect: (item: CycleTimeItem) => void
  height?: number
}) {
  const points: CyclePoint[] = []
  for (const item of items) {
    const y = measureValue(item, measure)
    const x = Date.parse(item.closedAt)
    // An item with no value for THIS measure is not plotted at zero — it is not
    // plotted at all, and the page prints how many there were.
    if (y == null || Number.isNaN(x)) continue
    points.push({ x, y, item })
  }

  const domain = windowDomain(from, to, points)
  const axis = niceAxis(Math.max(0, p85 ?? 0, ...points.map(p => p.y)))

  return (
    <div aria-hidden="true" style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        <ScatterChart margin={{ top: 10, right: 16, bottom: 4, left: -8 }}>
          <CartesianGrid stroke={CHART_GRID} vertical={false} />
          <XAxis
            type="number"
            dataKey="x"
            domain={[domain.min, domain.max]}
            ticks={domain.ticks}
            tickFormatter={formatDay}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            minTickGap={20}
          />
          <YAxis
            type="number"
            dataKey="y"
            domain={[0, axis.max]}
            ticks={axis.ticks}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            width={44}
          />
          {/* Fixed dot area — the third dimension carries no meaning here, and a
              size that varied with anything would invent one. */}
          <ZAxis range={[40, 40]} />
          <Tooltip
            isAnimationActive={false}
            cursor={{ stroke: CHART_GRID }}
            content={props => (
              <PointTooltip measure={measure} active={props.active} payload={props.payload} />
            )}
          />

          {/* The percentiles: derived, secondary, dashed — the context colour,
              never a series colour (DESIGN.md → Data Visualisation). Drawn only
              when the server computed them. */}
          {p50 != null && (
            <ReferenceLine
              y={p50} stroke={CHART_CONTEXT} strokeDasharray="5 4" strokeWidth={2}
              label={{ value: `p50 ${formatDays(p50)}d`, position: 'insideTopLeft', fill: CHART_AXIS, fontSize: 11 }}
            />
          )}
          {p85 != null && (
            <ReferenceLine
              y={p85} stroke={CHART_CONTEXT} strokeDasharray="2 3" strokeWidth={2}
              label={{ value: `p85 ${formatDays(p85)}d`, position: 'insideTopLeft', fill: CHART_AXIS, fontSize: 11 }}
            />
          )}

          <Scatter
            name={MEASURE_LABEL[measure]}
            data={points}
            fill={CHART_SERIES[0]}
            fillOpacity={0.72}
            isAnimationActive={false}
            cursor="pointer"
            onClick={(entry: unknown) => {
              const point = (entry as { payload?: CyclePoint } | undefined)?.payload
              if (point) onSelect(point.item)
            }}
          />
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * The x domain: the window the server answered with, widened only if a point
 * somehow falls outside it (a closure timestamped on the boundary day in another
 * timezone). Never narrowed to the data — that is the rescaling-per-timeframe
 * habit §1.6 #4 exists to refuse.
 */
function windowDomain(from: string, to: string, points: CyclePoint[]): { min: number; max: number; ticks: number[] } {
  const DAY = 86_400_000
  let min = Date.parse(`${from}T00:00:00Z`)
  let max = Date.parse(`${to}T00:00:00Z`) + DAY - 1
  if (Number.isNaN(min) || Number.isNaN(max) || max <= min) {
    // No usable window — fall back to the data's own span so the chart still
    // draws something readable rather than collapsing to a single column.
    const xs = points.map(p => p.x)
    min = xs.length ? Math.min(...xs) : Date.now() - 30 * DAY
    max = xs.length ? Math.max(...xs) + DAY : Date.now()
  }
  for (const p of points) {
    if (p.x < min) min = p.x
    if (p.x > max) max = p.x
  }
  const ticks: number[] = []
  const steps = 5
  for (let i = 0; i <= steps; i++) {
    // Snapped to UTC midnight: the axis reads as days, like every other date in
    // this feature.
    ticks.push(Math.floor((min + ((max - min) * i) / steps) / DAY) * DAY)
  }
  return { min, max, ticks: [...new Set(ticks)] }
}

/** UTC day label — every date in this feature is a UTC date and the page says so. */
export function formatDay(ms: number): string {
  if (!Number.isFinite(ms)) return ''
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' })
}

/**
 * The per-dot tooltip: the issue's key, its title and its duration. A dot that
 * cannot say which issue it is would be a distribution, and a distribution is
 * the report we refused to build.
 */
function PointTooltip({ measure, payload, active }: {
  measure: CycleMeasure
  payload?: readonly { payload?: unknown }[]
  active?: boolean
}) {
  const point = active ? (payload?.[0]?.payload as CyclePoint | undefined) : undefined
  if (!point) return null
  return (
    <div
      style={{
        background: 'var(--color-card)',
        border: '1px solid var(--color-border-2)',
        borderRadius: 'var(--radius-md)',
        boxShadow: 'var(--shadow-pop)',
        fontSize: 12,
        padding: '8px 10px',
        maxWidth: 320,
      }}
    >
      <div className="mono" style={{ fontWeight: 700 }}>{point.item.key}</div>
      <div style={{ color: 'var(--color-text-secondary)', marginTop: 2 }}>{point.item.title}</div>
      <div style={{ marginTop: 4 }}>
        {MEASURE_LABEL[measure]}: <b>{formatDays(point.y)}</b> days · closed {formatDay(point.x)} (UTC)
      </div>
    </div>
  )
}
