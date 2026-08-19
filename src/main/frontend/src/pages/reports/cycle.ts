/**
 * The pure half of the cycle-time report (HD-138, slice R3 of the reports epic).
 *
 * Everything here is a rule the two halves of that page have to agree on, so it
 * lives in one testable place rather than twice in JSX:
 *
 *  • which duration a measure means, and which issues even HAVE one;
 *  • how many issues a measure was computed from — the denominator of the
 *    honesty sentence, and it is different for the two measures;
 *  • how a suppressed percentile is read (below the 5-issue floor the server
 *    sends nothing, and nothing must never be drawn as a line at zero);
 *  • how a duration in days is printed, everywhere, identically.
 *
 * **There is no rolling average in this file and none may be added.** The spec
 * says so twice (§1.7, §2.2): a smoothed cycle-time line hides exactly the
 * outlier the report exists to name, and once a "trend" is on screen people read
 * it as a forecast. Percentiles come from the server (`percentile_cont`) and are
 * the only summary this report draws.
 */

import type { CycleMeasure, CycleTimeItem, CycleTimeReport, Percentiles } from '../../types'

/** Below this many completed issues the server suppresses percentiles (§2.2). */
export const PERCENTILE_MIN_SAMPLE = 5

export const MEASURE_LABEL: Record<CycleMeasure, string> = {
  CYCLE: 'Cycle time',
  LEAD: 'Lead time',
}

/** What each measure is measured BETWEEN — printed, so the number is definable. */
export const MEASURE_DEFINITION: Record<CycleMeasure, string> = {
  CYCLE: 'from the moment work started to the moment it closed',
  LEAD: 'from the moment the issue was created to the moment it closed',
}

/**
 * The plotted value of an item, or `null` when this measure is undefined for it.
 *
 * Only cycle time can be undefined: an issue with no recorded start has no cycle
 * time, and **we never fall back to `createdAt`** — that silently turns this
 * chart into a lead-time chart wearing a false name (§2.2). Such issues are
 * excluded from the plot and counted in `missingStartCount`, which the page
 * prints.
 */
export function measureValue(item: CycleTimeItem, measure: CycleMeasure): number | null {
  const raw = measure === 'CYCLE' ? item.cycleDays : item.leadDays
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : null
}

/**
 * How many completed issues this measure was actually computed from.
 *
 * Lead time is defined for every completed issue, so it is the whole sample;
 * cycle time is defined only for the ones with a recorded start, so it is the
 * sample minus `missingStartCount`. Printing the same number for both is the
 * quiet-subset failure this report is built to avoid.
 */
export function measureSample(report: Pick<CycleTimeReport, 'sampleSize' | 'missingStartCount'>, measure: CycleMeasure): number {
  if (measure === 'LEAD') return report.sampleSize
  return Math.max(0, report.sampleSize - report.missingStartCount)
}

/**
 * A percentile pair read defensively: a missing pair, a missing member and a
 * non-finite number all read as "not computed".
 *
 * Suppression below the sample floor may legitimately arrive as a null pair or
 * as null members, and the difference must not change what is drawn. A `0`
 * default here would put a reference line on the axis and label it p85 — noise
 * presented as a measurement, which §1.7 rates as worse than printing nothing.
 */
export function readPercentiles(p: Percentiles | null | undefined): { p50: number | null; p85: number | null } {
  return { p50: finite(p?.p50), p85: finite(p?.p85) }
}

function finite(n: number | null | undefined): number | null {
  return typeof n === 'number' && Number.isFinite(n) ? n : null
}

/** The percentile pair for the measure on screen. */
export function percentilesFor(report: CycleTimeReport | undefined, measure: CycleMeasure): { p50: number | null; p85: number | null } {
  const pair = measure === 'CYCLE' ? report?.percentiles?.cycle : report?.percentiles?.lead
  return readPercentiles(pair)
}

/**
 * A duration in days, printed the same way in the chart, the cards, the
 * reference-line labels and the table — **always one decimal**.
 *
 * Uniform on purpose: the server's own numbers arrive at that precision
 * (`4.2`, `12.6`, `28.4`), and a rule that dropped the decimal above some
 * threshold would print p85 as `13` in one place and `12.6` in the next, which
 * reads as two different measurements of the same thing.
 */
export function formatDays(days: number): string {
  return days.toFixed(1)
}

/** `4.1 days` / `1.0 day` — for prose, where the unit is needed. */
export function formatDaysUnit(days: number): string {
  const text = formatDays(days)
  return `${text} ${text === '1.0' ? 'day' : 'days'}`
}
