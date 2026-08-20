import {
  Bar, BarChart, CartesianGrid, Rectangle, ReferenceLine, ResponsiveContainer, Tooltip, XAxis,
  YAxis, type BarShapeProps,
} from 'recharts'
import type { SprintMeasure } from '../../types'
import { CHART_AXIS, CHART_CONTEXT, CHART_GRID, CHART_SERIES } from './common'
import { SPRINT_MEASURE_UNIT, formatMeasure } from './sprint'
import { niceAxis } from './scale'
import { velocityAxisMax, type VelocityBand, type VelocityRow } from './velocity'

/**
 * The velocity bars (§2.5) — **context for the band, not a scoreboard.**
 *
 * One bar per completed sprint showing what it **completed**, with what it
 * **committed** to marked as a level across the same bar. The two are drawn as
 * different things on purpose: a bar you can only read against its own
 * commitment is a bar about a plan, whereas two bars side by side invite
 * reading one team's height against another's, which is the documented harm
 * (§1.4) this whole report is a redesign of.
 *
 * The forecast, when there is one, is drawn as two dashed rules — p50 and p85 —
 * in the context colour, exactly as DESIGN.md prescribes for a derived series.
 * They are secondary to the sentence beside the chart, which is the deliverable;
 * the rules only let a reader see which sprints sat above and below it.
 *
 * **Nothing here is attributable to a person.** Not the bars, not the axis, and
 * specifically **not the tooltip** — a per-person breakdown hidden behind a
 * hover is still a per-person breakdown, and it is the exact refusal §1.4 asks
 * for by name.
 *
 * Recharts is imported here and in the other three chart files only, all inside
 * the lazy `/reports` chunk, so the library never enters the main bundle. The
 * chart is `aria-hidden`: the accessible reading is the table underneath, which
 * carries exactly these numbers.
 */
export default function VelocityChart({
  rows, measure, band, height = 280,
}: {
  rows: VelocityRow[]
  measure: SprintMeasure
  /** Drawn as two rules when there is a forecast; nothing when it is suppressed. */
  band: VelocityBand
  height?: number
}) {
  // Zero-based with fixed round ticks, never auto-scaled to the data (§1.6 #4):
  // two screenshots of this report a month apart have to be comparable, and a
  // velocity chart that rescales itself is one that can be made to look like
  // anything. The p85 rule is included in the maximum so a stretch figure above
  // every bar is still on the canvas.
  const axis = niceAxis(velocityAxisMax(rows, band))

  return (
    <div aria-hidden="true" style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} margin={{ top: 8, right: 12, bottom: 4, left: -8 }}>
          <CartesianGrid stroke={CHART_GRID} vertical={false} />
          <XAxis
            dataKey="name"
            tickFormatter={shortSprintName}
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
              <VelocityTooltip measure={measure} active={props.active} payload={props.payload} />
            )}
          />

          {band.kind === 'BAND' && (
            <ReferenceLine
              y={band.p50}
              stroke={CHART_CONTEXT}
              strokeWidth={2}
              strokeDasharray="5 4"
              label={{ value: 'p50', position: 'right', fill: CHART_AXIS, fontSize: 11 }}
            />
          )}
          {band.kind === 'BAND' && (
            <ReferenceLine
              y={band.p85}
              stroke={CHART_CONTEXT}
              strokeWidth={2}
              strokeDasharray="2 4"
              label={{ value: 'p85', position: 'right', fill: CHART_AXIS, fontSize: 11 }}
            />
          )}

          {/*
            ONE bar series, with the commitment drawn inside its own shape rather
            than as a second series beside it. Two adjacent bars would put the
            plan and the outcome at different x positions, and a level marked on
            the bar is the only way "we finished 18 of the 21 we took on" reads
            as one sprint's story instead of two competing columns.
          */}
          <Bar
            dataKey="completed"
            name="Completed"
            isAnimationActive={false}
            shape={(props: BarShapeProps) => <VelocityBarShape {...props} axisMax={axis.max} />}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * The completed bar, plus the committed level marked across it.
 *
 * The marker's y is computed from the bar's **background** rectangle — the full
 * plot band for that category, which Recharts hands to every custom shape —
 * against the axis maximum this component fixed. Deriving it from the bar's own
 * height instead would divide by zero on a sprint that completed nothing, which
 * is precisely the sprint whose commitment a reader most wants to see.
 */
function VelocityBarShape(props: BarShapeProps & { axisMax: number }) {
  const { x, y, width, height, background, axisMax } = props
  const row = props.payload as VelocityRow | undefined
  const markY = committedY(background, row?.committed, axisMax)
  return (
    <g>
      <Rectangle
        x={x}
        y={y}
        width={width}
        height={height}
        // --radius-sm (6px), the value DESIGN.md gives for bar corners; top two
        // only, so the bar still sits flat on its baseline.
        radius={[6, 6, 0, 0]}
        fill={CHART_SERIES[0]}
      />
      {markY !== null && (
        <line
          x1={x - 3}
          x2={x + width + 3}
          y1={markY}
          y2={markY}
          stroke={CHART_SERIES[1]}
          strokeWidth={2.5}
          strokeLinecap="round"
        />
      )}
    </g>
  )
}

/**
 * Where the committed level sits in pixels, or **null when it cannot be placed**.
 *
 * Exported because it is the only arithmetic in this file and a wrong marker is a
 * lie told in pixels that no table underneath would contradict (the precedent is
 * `AgingChart.layout`). Null rather than a fallback of zero on purpose: a marker
 * defaulted to the baseline reads as "this sprint committed to nothing", which is
 * a claim, where no marker at all is merely an absence.
 */
export function committedY(
  background: { y: number | null; height: number } | undefined,
  committed: number | undefined,
  axisMax: number,
): number | null {
  if (!background || typeof background.y !== 'number' || !Number.isFinite(background.height)) return null
  if (typeof committed !== 'number' || !Number.isFinite(committed) || committed < 0) return null
  if (!Number.isFinite(axisMax) || axisMax <= 0) return null
  const ratio = Math.min(1, committed / axisMax)
  return background.y + background.height * (1 - ratio)
}

/**
 * Sprint names are free text and can be a sentence; the axis gets an abbreviated
 * one and the full name lives in the tooltip and the table. It is truncated
 * rather than rotated so the bars stay the same size in every project.
 */
export function shortSprintName(name: string): string {
  return name.length > 14 ? `${name.slice(0, 13)}…` : name
}

/**
 * One sprint's numbers, and its completion date.
 *
 * The date is here because the payload carries it: the bars are ordered oldest
 * first by the server, and a reader hovering one should not have to infer when
 * it ran from what somebody named it.
 *
 * Figures about a SPRINT and not one about a person — and that is a hard
 * rule rather than an oversight: §1.4 refuses a per-person breakdown "not as a
 * filter, not as a tooltip", and a tooltip is exactly where such a thing gets
 * added by somebody who reads the refusal as being about the chart.
 */
function VelocityTooltip({ measure, payload, active }: {
  measure: SprintMeasure
  payload?: readonly { payload?: unknown }[]
  active?: boolean
}) {
  const row = active ? (payload?.[0]?.payload as VelocityRow | undefined) : undefined
  if (!row) return null
  const unit = SPRINT_MEASURE_UNIT[measure]
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
      <div style={{ fontWeight: 700 }}>
        {row.name}
        {row.completedDay && (
          <span className="mono" style={{ fontWeight: 500, color: 'var(--color-text-secondary)' }}>
            {' '}· completed {row.completedDay}
          </span>
        )}
      </div>
      <div style={{ marginTop: 4 }}>
        Completed <b>{formatMeasure(row.completed, measure)}</b> of{' '}
        <b>{formatMeasure(row.committed, measure)}</b> committed {unit}
      </div>
      <div style={{ color: 'var(--color-text-secondary)', marginTop: 2 }}>
        {formatMeasure(row.addedAfterStart, measure)} added after start ·{' '}
        {formatMeasure(row.carriedOver, measure)} carried over
      </div>
      {row.unestimatedCount > 0 && (
        <div style={{ color: 'var(--color-text-secondary)', marginTop: 2 }}>
          {row.unestimatedCount.toLocaleString()} issue
          {row.unestimatedCount === 1 ? '' : 's'} unestimated
          {measure === 'POINTS' ? ' — weighing zero in this bar' : ''}
        </div>
      )}
    </div>
  )
}
