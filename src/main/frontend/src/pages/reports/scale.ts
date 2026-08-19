/**
 * Axis scaling for report charts.
 *
 * One rule, and it removes a whole class of distrust (reports-proposal §1.6 #4):
 * **an axis is zero-based with fixed, round ticks — never auto-scaled to the
 * data range.** Jira's control chart rescales its axis per timeframe and per
 * outlier, so two screenshots of the same report are not comparable, and readers
 * learn not to trust either. Ours grows in round steps, so the same window a week
 * later is read against the same grid.
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
