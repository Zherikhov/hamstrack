import {
  CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,
  type DotItemDotProps,
} from 'recharts'
import type { ScopeChange, SprintMeasure } from '../../types'
import { CHART_AXIS, CHART_CONTEXT, CHART_GRID, CHART_SERIES } from './common'
import { SPRINT_MEASURE_LABEL, formatDelta, formatMeasure, type BurnupRow } from './sprint'
import { niceAxis } from './scale'

/**
 * The sprint burn-up (§2.3) — **a burn-up, deliberately not a burndown.**
 *
 * Two measured lines and one guide:
 *
 *  • **Scope** — total work in the sprint that day. It steps UP when an issue is
 *    added and DOWN when one is removed, and it never moves on a re-estimate,
 *    because scope change is membership change (§2.3 rule 1). Every step carries
 *    a visible dot and its tooltip names the issue and who moved it — the whole
 *    reason this is a burn-up: a burndown folds "we finished work" and "somebody
 *    added work" into one falling line and the team argues about which happened.
 *  • **Completed** — cumulative work closed by the end of that day.
 *  • **Ideal** — a faint dashed guide from (start, 0) to (end, the scope
 *    COMMITTED at the start). A guide, not a verdict: it is drawn in the context
 *    colour, dashed, and labelled as where the team said it would be.
 *
 * **The measured lines stop at today and nothing continues them.** No trend, no
 * projection, no "at this rate you'll finish Thursday" (§2.3 rule 2) — the rows
 * carry `null` past today and `connectNulls` is off, so the line physically ends
 * where the data does. Forecasting is R5 and arrives with a stated sample size.
 *
 * Recharts is imported here and in `CycleTimeChart` only, both inside the lazy
 * `/reports` chunk, so the library never enters the main bundle. The chart is
 * `aria-hidden`: the accessible reading is the table underneath, which carries
 * exactly these numbers.
 */
export default function BurnupChart({
  rows, measure, actorName, height = 320,
}: {
  rows: BurnupRow[]
  measure: SprintMeasure
  /** Resolves an actor id to a name — the log and the tooltip use the same one. */
  actorName: (id: string | null) => string
  height?: number
}) {
  const axis = niceAxis(Math.max(
    0,
    ...rows.map(r => Math.max(r.scope ?? 0, r.completed ?? 0, r.ideal)),
  ))
  // Drawn only when the sprint still has days left — on a finished sprint a
  // "today" marker would sit off the chart or, worse, inside it and imply the
  // record is still moving.
  const firstFuture = rows.find(r => r.future)

  return (
    <div aria-hidden="true" style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={rows} margin={{ top: 8, right: 12, bottom: 4, left: -8 }}>
          <CartesianGrid stroke={CHART_GRID} vertical={false} />
          <XAxis
            dataKey="date"
            tickFormatter={formatSprintDay}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            minTickGap={22}
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
            cursor={{ stroke: CHART_GRID }}
            content={props => (
              <BurnupTooltip
                measure={measure}
                actorName={actorName}
                active={props.active}
                payload={props.payload}
              />
            )}
          />

          {firstFuture && (
            <ReferenceLine
              x={firstFuture.date}
              stroke={CHART_GRID}
              strokeWidth={2}
              label={{ value: 'today', position: 'insideTopRight', fill: CHART_AXIS, fontSize: 11 }}
            />
          )}

          {/* The guide first, so the two measured lines draw over it. */}
          <Line
            type="linear" dataKey="ideal" name="Ideal"
            stroke={CHART_CONTEXT} strokeWidth={2} strokeDasharray="5 4"
            dot={false} activeDot={false} isAnimationActive={false}
          />
          <Line
            type="linear" dataKey="scope" name={`Scope (${SPRINT_MEASURE_LABEL[measure].toLowerCase()})`}
            stroke={CHART_SERIES[0]} strokeWidth={2}
            // A dot exactly where scope moved — a step you cannot point at is a
            // step nobody can ask about.
            dot={ScopeDot}
            activeDot={{ r: 4 }}
            connectNulls={false}
            isAnimationActive={false}
          />
          <Line
            type="linear" dataKey="completed" name="Completed"
            stroke={CHART_SERIES[2]} strokeWidth={2}
            dot={false} activeDot={{ r: 4 }}
            connectNulls={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

/** A visible dot on the scope line for the days scope actually changed. */
function ScopeDot(props: DotItemDotProps) {
  const row = props.payload as BurnupRow | undefined
  const { cx, cy } = props
  if (!row || row.changes.length === 0 || typeof cx !== 'number' || typeof cy !== 'number') return null
  return <circle cx={cx} cy={cy} r={4} fill={CHART_SERIES[0]} stroke="white" strokeWidth={1.5} />
}

/** `14 Aug` — a UTC day, like every other date in this feature. */
export function formatSprintDay(iso: string): string {
  const ms = Date.parse(`${iso}T00:00:00Z`)
  if (Number.isNaN(ms)) return iso
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' })
}

/**
 * The day's tooltip — and, on a day scope moved, **the step's own explanation**:
 * which issue joined or left, who moved it, and by how much.
 *
 * §2.3 asks for that by name ("every step hoverable, naming the issue and who
 * moved it"), and it is the difference between a scope line and an accusation.
 */
function BurnupTooltip({ measure, actorName, payload, active }: {
  measure: SprintMeasure
  actorName: (id: string | null) => string
  payload?: readonly { payload?: unknown }[]
  active?: boolean
}) {
  const row = active ? (payload?.[0]?.payload as BurnupRow | undefined) : undefined
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
        maxWidth: 340,
      }}
    >
      <div className="mono" style={{ fontWeight: 700 }}>{formatSprintDay(row.date)} (UTC)</div>
      {row.future ? (
        <div style={{ color: 'var(--color-text-secondary)', marginTop: 4 }}>
          Still to come — nothing is drawn for a day that hasn’t happened.
        </div>
      ) : (
        <div style={{ marginTop: 4 }}>
          Scope <b>{formatMeasure(row.scope, measure)}</b> · completed{' '}
          <b>{formatMeasure(row.completed, measure)}</b> · ideal {formatMeasure(row.ideal, measure)}
        </div>
      )}
      {row.changes.length > 0 && (
        <div style={{ marginTop: 6, borderTop: '1px solid var(--color-border)', paddingTop: 6 }}>
          {row.changes.map((change, i) => (
            <div key={`${change.key}-${change.at}-${i}`} style={{ marginTop: i === 0 ? 0 : 3 }}>
              <span className="mono" style={{ fontWeight: 700 }}>{change.key}</span>{' '}
              {change.event === 'ADDED' ? 'added by' : 'removed by'} {actorName(change.actorId)}{' '}
              <span className="mono">({formatDelta(change.delta, measure)})</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** Named for the log and the legend, so a colour is never the only encoding. */
export function scopeChangeVerb(change: ScopeChange): string {
  return change.event === 'ADDED' ? 'Added' : 'Removed'
}
