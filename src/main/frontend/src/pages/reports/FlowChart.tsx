import {
  CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { FlowBucket, ReportInterval } from '../../types'
import { CHART_AXIS, CHART_CONTEXT, CHART_GRID, CHART_SERIES } from './common'
import { niceAxis } from './scale'

/**
 * The flow chart — created vs resolved, plus the open-count line (§2.1).
 *
 * **This file is the only place Recharts is imported for R1**, and it is only
 * reachable through the lazily-loaded `/reports` route, so the chart library
 * never enters the main bundle (the Swagger UI chunk is the precedent). Keep it
 * that way: an `import … from 'recharts'` anywhere outside the reports chunk
 * silently costs every user on every page load.
 *
 * Two axes, both **zero-based with fixed round ticks** (`niceAxis`), because the
 * comparability rule of §1.6 #4 is the whole reason this chart can be trusted
 * across two screenshots. The right-hand axis carries `openAtEnd`, which is a
 * cumulative balance and lives on a different order of magnitude from the two
 * per-bucket counts — drawn dashed and in the context colour so it reads as the
 * derived, secondary series it is, and labelled on its own axis so nobody
 * compares its height against the other two.
 *
 * The chart itself is `aria-hidden`: the accessible reading of this report is the
 * table underneath it, which carries exactly the same numbers.
 */
export default function FlowChart({
  buckets, interval, height = 300,
}: {
  buckets: FlowBucket[]
  interval: ReportInterval
  height?: number
}) {
  const counts = niceAxis(Math.max(0, ...buckets.map(b => Math.max(b.created, b.resolved))))
  const open = niceAxis(Math.max(0, ...buckets.map(b => b.openAtEnd)))

  return (
    <div aria-hidden="true" style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={buckets} margin={{ top: 8, right: 8, bottom: 4, left: -8 }}>
          <CartesianGrid stroke={CHART_GRID} vertical={false} />
          <XAxis
            dataKey="date"
            tickFormatter={d => formatBucketDate(d, interval)}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            minTickGap={24}
          />
          <YAxis
            yAxisId="counts"
            domain={[0, counts.max]}
            ticks={counts.ticks}
            allowDecimals={false}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            width={44}
          />
          <YAxis
            yAxisId="open"
            orientation="right"
            domain={[0, open.max]}
            ticks={open.ticks}
            allowDecimals={false}
            tick={{ fill: CHART_AXIS, fontSize: 11 }}
            stroke={CHART_GRID}
            width={44}
          />
          <Tooltip
            labelFormatter={d => formatBucketDate(String(d), interval, true)}
            contentStyle={{
              background: 'var(--color-card)',
              border: '1px solid var(--color-border-2)',
              borderRadius: 'var(--radius-md)',
              boxShadow: 'var(--shadow-pop)',
              fontSize: 12,
            }}
          />
          <Line
            yAxisId="counts" type="monotone" dataKey="created" name="Created"
            stroke={CHART_SERIES[0]} strokeWidth={2} dot={false} activeDot={{ r: 4 }} isAnimationActive={false}
          />
          <Line
            yAxisId="counts" type="monotone" dataKey="resolved" name="Resolved"
            stroke={CHART_SERIES[2]} strokeWidth={2} dot={false} activeDot={{ r: 4 }} isAnimationActive={false}
          />
          <Line
            yAxisId="open" type="monotone" dataKey="openAtEnd" name="Open at end"
            stroke={CHART_CONTEXT} strokeWidth={2} strokeDasharray="5 4" dot={false}
            activeDot={{ r: 4 }} isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * Bucket labels. A WEEK bucket is labelled by the Monday it starts on and says so
 * in the tooltip — "week of 14 Aug" rather than a bare date that would read as a
 * single day's worth of work.
 */
export function formatBucketDate(iso: string, interval: ReportInterval, long = false): string {
  const ms = Date.parse(`${iso}T00:00:00Z`)
  if (Number.isNaN(ms)) return iso
  const label = new Date(ms).toLocaleDateString(undefined, {
    day: 'numeric', month: 'short', timeZone: 'UTC',
    ...(long ? { year: 'numeric' } : {}),
  })
  return interval === 'WEEK' && long ? `week of ${label}` : label
}
