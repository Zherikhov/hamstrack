/**
 * The pure half of the velocity report (HD-139, slice R5 of the reports epic).
 *
 * **The band is the deliverable; the bars are context.** Everything in this file
 * exists to produce one sentence —
 *
 *   *"Recent sprints delivered between 14 and 23 issues; plan for ~18 (p50) and
 *   treat 23 (p85) as a stretch. Based on 6 sprints."*
 *
 * — or, when the history cannot support it, to REFUSE to produce it and say how
 * many sprints there were instead. A band drawn from two sprints is not a
 * slightly weaker forecast, it is the failure mode: §2.3 rule 2 refuses
 * forecasting outright wherever a sample size cannot be stated, and this is the
 * one report in the epic that can state one.
 *
 * The refusals of §1.4 are enforced by what this module CANNOT express. There is
 * no assignee anywhere in these shapes, no way to fold two projects' rows
 * together, and no "team" as a unit of anything:
 *
 *  • **no per-person breakdown** — not a filter, not a tooltip, not a legend and
 *    not a CSV column. *"Leaders misinterpret higher story point averages to
 *    mean one team is more productive"*, and the same misreading one level down
 *    is a person's velocity;
 *  • **no cross-project or workspace-level view.** Comparing two teams has to be
 *    done by hand — **that friction is the design**, so there is deliberately no
 *    helpful comparison affordance to add later;
 *  • **the caption is permanent** ({@link VELOCITY_CAPTION}), never a tooltip and
 *    never behind an info icon: a disclosure nobody hovers is a disclosure
 *    nobody made.
 */

import type { SprintMeasure, VelocityReport, VelocitySprint } from '../../types'
import { SPRINT_MEASURE_UNIT, formatMeasure, utcDay } from './sprint'

/** What the picker offers and what the URL is clamped to (§2.5, §4.3). */
export const VELOCITY_DEFAULT_SPRINTS = 6
export const VELOCITY_MAX_SPRINTS = 12
export const VELOCITY_MIN_SPRINTS = 1

/**
 * Fewer completed sprints than this and **the band is withheld** (§6), with the
 * sample size stated in its place.
 *
 * Three is not a statistical threshold and is not defended as one — it is the
 * point below which a percentile is arithmetic performed on noise. What matters
 * is that the number is fixed, printed and the same in every project, so nobody
 * can read a forecast that was never computed.
 */
export const VELOCITY_MIN_SAMPLE = 3

/**
 * The permanent caption, verbatim from §2.5 and kept as a constant so the page
 * cannot ship without it, cannot re-word it, and cannot demote it into a
 * tooltip. It is printed whatever the measure: it is a statement about what a
 * velocity number *is*, not a footnote about the current toggle.
 */
export const VELOCITY_CAPTION =
  'Story points are team-relative. Velocity is not comparable between teams.'

// ── URL state (§4.4 — a report that cannot be pasted into Slack is half a feature) ──

export interface VelocityState {
  /** Completed sprints charted — always within {@link VELOCITY_MIN_SPRINTS}..{@link VELOCITY_MAX_SPRINTS}. */
  sprints: number
  measure: SprintMeasure
}

export interface VelocityUrlState extends VelocityState {
  /**
   * The out-of-range count the URL actually asked for, or null.
   *
   * A clamp is only acceptable when it is **said out loud** — the "these numbers
   * don't match what I expected" failure (§1.7) is a report that quietly showed
   * something other than what was asked for. The alternative, forwarding
   * `sprints=99` and rendering the endpoint's 400, replaces a chart plus a
   * sentence with an error for a link somebody pasted in good faith.
   */
  clampedFrom: number | null
}

/**
 * Read the page's state out of the URL.
 *
 * An unrecognised measure degrades to `COUNT` and an unparseable count degrades
 * to the default, silently and deliberately: both are "this parameter is not a
 * value at all", and announcing a fallback for a typo teaches nothing. An
 * out-of-range NUMBER is different — it is a real request this report will not
 * satisfy — so it is clamped and reported.
 */
export function readVelocityState(params: URLSearchParams): VelocityUrlState {
  const measure: SprintMeasure = params.get('measure') === 'POINTS' ? 'POINTS' : 'COUNT'
  const raw = params.get('sprints')
  if (raw === null || raw.trim() === '') {
    return { sprints: VELOCITY_DEFAULT_SPRINTS, measure, clampedFrom: null }
  }
  const parsed = Number(raw)
  if (!Number.isFinite(parsed)) {
    return { sprints: VELOCITY_DEFAULT_SPRINTS, measure, clampedFrom: null }
  }
  const asked = Math.trunc(parsed)
  const sprints = Math.min(VELOCITY_MAX_SPRINTS, Math.max(VELOCITY_MIN_SPRINTS, asked))
  return { sprints, measure, clampedFrom: sprints === asked ? null : asked }
}

/**
 * The request parameters — and the URL, which is the same object.
 *
 * Both are on the wire: the server sums different rows per measure, and the
 * count bounds the query. Sending the count explicitly (rather than relying on
 * the endpoint's default) is what keeps a shared link meaning the same report
 * after a release that changes the default.
 */
export function writeVelocityParams(state: VelocityState): { sprints: string; measure: SprintMeasure } {
  return { sprints: String(state.sprints), measure: state.measure }
}

// ── The bars ─────────────────────────────────────────────────────────────────

/**
 * One bar. Four numbers and a name — **and no person**, which is the point.
 *
 * `key` exists only because two sprints may legitimately carry the same name
 * (nothing stops a team calling every sprint "Sprint"), and a React key that
 * collides silently drops a bar from the record.
 */
export interface VelocityRow {
  key: string
  sprintId: string
  name: string
  /** `YYYY-MM-DD` UTC, or null when the instant is unusable. Chronology, printed. */
  completedDay: string | null
  startDay: string | null
  committed: number
  completed: number
  addedAfterStart: number
  carriedOver: number
  /** Issues the sprint held at its end with no estimate — an issue count in both measures. */
  unestimatedCount: number
}

/**
 * The rows the chart and the table under it both read — same numbers, same
 * order, which is the accessibility answer and the CSV at once.
 *
 * Tolerant on purpose: a missing figure degrades to 0 rather than to `NaN`,
 * because a `NaN` bar disappears from the chart while its row stays in the
 * table, and a picture that has quietly shed a sprint is exactly what this
 * epic's provenance rules exist to prevent.
 *
 * **The order is the server's**, which is chronological, oldest first — so the
 * bars read left to right in time. There is deliberately no client-side sort:
 * the response carries no sprint dates or sequence to sort BY, so re-ordering
 * here could only mean inventing a chronology out of names.
 */
export function velocityRows(report: Pick<VelocityReport, 'sprints'> | undefined): VelocityRow[] {
  const sprints: VelocitySprint[] = report?.sprints ?? []
  return sprints.map((s, i) => ({
    key: `${s.sprintId ?? 'sprint'}-${i}`,
    sprintId: s.sprintId ?? '',
    name: s.name || 'Unnamed sprint',
    completedDay: utcDay(s.completedAt),
    startDay: utcDay(s.startAt),
    committed: num(s.committed),
    completed: num(s.completed),
    addedAfterStart: num(s.addedAfterStart),
    carriedOver: num(s.carriedOver),
    unestimatedCount: Math.max(0, Math.round(num(s.unestimatedCount))),
  }))
}

/**
 * How much of the sample nobody sized (§6, documented failure mode #4).
 *
 * Two numbers rather than one, because the disclosure has to name its own scope:
 * "9 issues" alone leaves a reader unable to tell one badly-sized sprint from a
 * habit spread across the whole sample, and those call for different reactions.
 */
export interface UnestimatedSummary {
  /** Unestimated issues across every charted sprint. */
  issues: number
  /** How many of the charted sprints hold at least one. */
  sprints: number
}

export function unestimatedSummary(rows: VelocityRow[]): UnestimatedSummary {
  return {
    issues: rows.reduce((n, r) => n + r.unestimatedCount, 0),
    sprints: rows.filter(r => r.unestimatedCount > 0).length,
  }
}

function num(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

// ── The band ─────────────────────────────────────────────────────────────────

export type VelocityBand =
  | {
    kind: 'BAND'
    /** Plan for this. */
    p50: number
    /** Treat this as a stretch, never as a target. */
    p85: number
    /** Completed sprints behind the percentiles — printed, always. */
    sampleSize: number
    /** The lowest and highest sprint actually observed — a fact, not a forecast. */
    low: number
    high: number
  }
  | { kind: 'SUPPRESSED'; sampleSize: number }

/**
 * Whether there is a forecast to show at all.
 *
 * The server suppresses by **nulling the percentiles** while still stating the
 * sample size, so the ordinary suppressed response is a present `forecast` whose
 * `p50` is null. The threshold is re-checked here regardless of how it arrives:
 * a reader must not be shown a band computed from two sprints whether the cause
 * is an old server, a hand-built fixture or a bug. A percentile that is not a
 * finite number is treated the same way — "p50 = NaN" renders as a
 * confident-looking dash, which is worse than a stated refusal.
 *
 * The suppressed branch states **the number of completed sprints charted**,
 * which is the sample the reader can see with their own eyes, so the sentence
 * and the bars can never disagree.
 */
export function velocityBand(
  report: Pick<VelocityReport, 'forecast'> | undefined,
  rows: VelocityRow[],
): VelocityBand {
  const forecast = report?.forecast
  const charted = rows.length
  const stated = forecast && Number.isFinite(forecast.sampleSize) && forecast.sampleSize > 0
    ? forecast.sampleSize
    : charted
  const p50 = forecast?.p50
  const p85 = forecast?.p85
  if (
    typeof p50 !== 'number' || !Number.isFinite(p50)
    || typeof p85 !== 'number' || !Number.isFinite(p85)
    || stated < VELOCITY_MIN_SAMPLE
    || charted < VELOCITY_MIN_SAMPLE
  ) {
    return { kind: 'SUPPRESSED', sampleSize: charted }
  }
  const completed = rows.map(r => r.completed)
  return {
    kind: 'BAND',
    p50,
    p85,
    sampleSize: stated,
    low: Math.min(...completed),
    high: Math.max(...completed),
  }
}

/**
 * The sentence §2.5 pins, and the reason this report exists:
 *
 *   *"Recent sprints delivered between 14 and 23 issues; plan for ~18 (p50) and
 *   treat 23 (p85) as a stretch. Based on 6 sprints."*
 *
 * The range is what was **observed** — the lowest and highest bar — and the two
 * percentiles are what is **forecast** from them; the sentence keeps them in
 * that order so the evidence precedes the projection. It is a plain string
 * rather than JSX because it is meant to be read as one sentence, asserted on as
 * one sentence, and copied out as one sentence.
 */
export function velocitySentence(band: VelocityBand, measure: SprintMeasure): string {
  const unit = SPRINT_MEASURE_UNIT[measure]
  if (band.kind === 'SUPPRESSED') {
    return sampleSentence(band.sampleSize)
  }
  const low = formatMeasure(band.low, measure)
  const high = formatMeasure(band.high, measure)
  const observed = low === high
    ? `Recent sprints each delivered ${low} ${unit}`
    : `Recent sprints delivered between ${low} and ${high} ${unit}`
  return `${observed}; plan for ~${formatMeasure(band.p50, measure)} (p50) and treat `
    + `${formatMeasure(band.p85, measure)} (p85) as a stretch. `
    + `Based on ${band.sampleSize.toLocaleString()} sprint${band.sampleSize === 1 ? '' : 's'}.`
}

/**
 * The refusal, with the sample size stated (§6) — the half of this report that
 * is easiest to skip and the half that keeps it honest.
 *
 * Three cases, because one sentence cannot cover them without lying. The last is
 * the one that matters: a sample big enough to forecast from, with no forecast
 * in the response. Reusing the "not enough sprints" wording there would print
 * "6 sprints have finished here, and a forecast needs at least 3" — a sentence
 * that contradicts itself in front of the reader.
 */
function sampleSentence(sampleSize: number): string {
  if (sampleSize === 0) {
    return 'No sprint has finished in this project yet, so there is nothing to forecast from.'
  }
  if (sampleSize >= VELOCITY_MIN_SAMPLE) {
    return `No forecast came back for the ${sampleSize.toLocaleString()} sprints below, so none is `
      + 'shown. The sprints themselves are the record and they are unaffected.'
  }
  return `${sampleSize.toLocaleString()} sprint${sampleSize === 1 ? ' has' : 's have'} finished here, `
    + `and a forecast needs at least ${VELOCITY_MIN_SAMPLE}. `
    + 'The sprints below are the record; the band is withheld rather than guessed.'
}

/**
 * The tallest number the y axis has to reach — bars **and** the p85 rule, so a
 * stretch figure above every bar is never drawn off the top of the chart.
 */
export function velocityAxisMax(rows: VelocityRow[], band: VelocityBand): number {
  const values = rows.flatMap(r => [r.completed, r.committed])
  if (band.kind === 'BAND') values.push(band.p50, band.p85)
  return Math.max(0, ...values.filter(Number.isFinite))
}
