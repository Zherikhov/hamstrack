/**
 * Axis scaling for report charts.
 *
 * One rule, and it removes a whole class of distrust (reports-proposal §1.6 #4):
 * **an axis is zero-based with fixed, round ticks — never auto-scaled to the
 * data range.** Jira's control chart rescales its axis per timeframe and per
 * outlier, so two screenshots of the same report are not comparable, and readers
 * learn not to trust either. Ours grows in round steps, so the same window a week
 * later is read against the same grid.
 *
 * **Both directions live here, and no chart may compute its own domain** (R7).
 * The rule is what makes an exported PNG worth keeping — the image carries its
 * window and its `computedAt` in the footer precisely so it can be held up
 * beside another one — and a rule implemented per chart is a rule that has
 * already drifted in one of them. {@link niceAxis} is the value direction,
 * {@link windowAxis} the time direction; a chart calls one of them and passes
 * the result straight to the axis.
 */

/**
 * A zero-based axis whose top is the next round number above `maxValue`, with
 * ticks at 1 / 2 / 5 × 10ⁿ steps.
 *
 * An all-zero series gets a 0–4 axis rather than a degenerate 0–0 one: a chart of
 * an empty window must still be a chart with a readable grid, not a flat line on
 * a single tick (§2.1 — "never a blank panel").
 */
export function niceAxis(maxValue: number, targetIntervals = 4): { max: number; ticks: number[] } {
  if (!Number.isFinite(maxValue) || maxValue <= 0) return { max: 4, ticks: [0, 1, 2, 3, 4] }
  const rawStep = maxValue / targetIntervals
  const magnitude = 10 ** Math.floor(Math.log10(rawStep))
  const normalized = rawStep / magnitude
  const step = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude
  const max = Math.ceil(maxValue / step) * step
  const ticks: number[] = []
  for (let t = 0; t <= max + step / 2; t += step) ticks.push(Math.round(t))
  return { max: Math.round(max), ticks }
}

/**
 * A **time axis pinned to the report's window**, never to the data inside it.
 *
 * The counterpart of {@link niceAxis} for the horizontal direction, and the same
 * rule: an axis that shrinks to fit its points makes a quiet fortnight look busy
 * and a busy one look quiet, so two exports of "the last 90 days" taken a week
 * apart stop being comparable. The domain is therefore `from`…`to` as the SERVER
 * answered them, whatever the points do.
 *
 * It widens — never narrows — for a point outside the window, which happens when
 * a closure is timestamped on a boundary day in another timezone. Dropping such
 * a point would be a silent omission; clipping the axis to the data would be the
 * rescaling this whole module exists to refuse.
 *
 * With no usable window (a report that failed before it echoed one back) it
 * falls back to the data's own span, and then to the last 30 days, so a chart
 * still draws a readable grid instead of collapsing onto a single column.
 *
 * Ticks are snapped to UTC midnight, because every date in this feature is a UTC
 * date and every surface says so.
 *
 * Lives here rather than in the chart that first needed it (R7): the axis rule
 * is one rule, and a copy of it inside a chart file is a copy that drifts.
 */
export function windowAxis(
  from: string,
  to: string,
  values: number[] = [],
  steps = 5,
): { min: number; max: number; ticks: number[] } {
  const DAY = 86_400_000
  let min = Date.parse(`${from}T00:00:00Z`)
  let max = Date.parse(`${to}T00:00:00Z`) + DAY - 1
  if (Number.isNaN(min) || Number.isNaN(max) || max <= min) {
    min = values.length ? Math.min(...values) : Date.now() - 30 * DAY
    max = values.length ? Math.max(...values) + DAY : Date.now()
  }
  for (const v of values) {
    if (!Number.isFinite(v)) continue
    if (v < min) min = v
    if (v > max) max = v
  }
  const ticks: number[] = []
  for (let i = 0; i <= steps; i++) {
    ticks.push(Math.floor((min + ((max - min) * i) / steps) / DAY) * DAY)
  }
  return { min, max, ticks: [...new Set(ticks)] }
}
