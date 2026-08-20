import { CHART_CONTEXT, CHART_SERIES } from '../reports/common'
import { OTHER_KEY, type SegmentSeries } from './insights'

/**
 * One colour-series' colour, shared by the chart and by the legend beside it so
 * the two can never drift apart.
 *
 * Its own module because the legend lives in `InsightsPanel` and the bars live
 * in `InsightsChart`, which is a **separate lazy chunk** — importing this out of
 * the chart file would pull Recharts into the panel chunk and undo the split
 * that keeps the library out of the main bundle.
 *
 * The ramp by index, and the **context grey** for the folded "Other" tail, which
 * is a derived series and may never take a hue a real category could be mistaken
 * for.
 *
 * **Not the entity's own colour**, which DESIGN.md would prefer for a chart
 * sliced BY a taxonomy entity: the insights response carries no `color` on a
 * bucket, and this panel spans a whole workspace, so there is no single project
 * `config` to read one from either. Guessing a hue per status name across
 * projects would be worse than an honest index — the ramp says "this is series
 * three", which is true, where a wrong teal would say "this is done".
 */
export function seriesColor(s: SegmentSeries, index: number): string {
  if (s.other || s.key === OTHER_KEY) return CHART_CONTEXT
  return CHART_SERIES[index % CHART_SERIES.length]
}
