/**
 * The pure half of the two sprint reports (HD-29, slice R4 of the reports epic).
 *
 * Both pages are per-sprint, both default to the ACTIVE sprint and both share
 * one picker — so the rules they have to agree on live here, once and testable,
 * rather than twice in JSX:
 *
 *  • **which sprint a page is looking at**, and what to say when the URL names
 *    one that no longer exists or the project has none running;
 *  • **where the burn-up's lines stop** — at today, never at the sprint's end
 *    date. A cumulative line drawn flat across the remaining days is a forecast
 *    with no sample size, and §2.3 rule 2 refuses forecasting outright;
 *  • **what the ideal guide is drawn to** — the scope COMMITTED at the start,
 *    not the current scope, so widening the sprint never quietly moves the bar
 *    it is measured against;
 *  • **how a measure is printed**, identically in the chart, the cards, the log
 *    and the five review lists.
 *
 * **There is no projection in this file and none may be added.** No trend line,
 * no "at this rate", no extrapolated completion date. Forecasting is R5's
 * velocity band and it ships with a stated sample size; anything here that looked
 * like a prediction would be one without one.
 */

import type {
  BurnupPoint, ScopeChange, SprintBurnupReport, SprintMeasure, SprintReviewList,
  SprintReviewReport, Sprint,
} from '../../types'
import { formatPoints } from '../../components/sprints'
import { isoDate, todayIso } from './window'

const DAY_MS = 86_400_000

/**
 * A sprint longer than this is not enumerated day by day — the chart falls back
 * to the days the server actually sent.
 *
 * The bound exists because `endAt` is a user-supplied date: a typo of `2126`
 * would otherwise ask the browser to lay out 36 500 points and hang the tab.
 */
export const MAX_BURNUP_DAYS = 366

export const SPRINT_MEASURE_LABEL: Record<SprintMeasure, string> = {
  COUNT: 'Issues',
  POINTS: 'Story points',
}

/** The unit as it reads inside a sentence ("23 issues" / "55 points"). */
export const SPRINT_MEASURE_UNIT: Record<SprintMeasure, string> = {
  COUNT: 'issues',
  POINTS: 'points',
}

/**
 * The footnote §2.3 asks for **by name**, kept as a constant so the burn-up
 * cannot ship without it and the review cannot ship a differently-worded twin.
 *
 * It is a disclosure, not a caveat: points on the burn-up are the issue's
 * CURRENT estimate (owner decision, 2026-08-19), so a re-estimate moves the
 * whole scope line, its past included. The alternative — freezing each issue's
 * points at the moment it entered — buys an immovable history at the price of a
 * re-estimate never being visible anywhere, and would make a sprint's scope
 * disagree with the same issues' estimates on every other screen.
 */
export const CURRENT_POINTS_FOOTNOTE =
  'Points reflect current estimates. A re-estimate therefore moves this whole scope line, '
  + 'its past included — the same issues show the same numbers everywhere else in the product, '
  + 'which is the trade that was chosen deliberately.'

/**
 * …and its counterpart on the review, which reads the ledger's SNAPSHOT instead.
 *
 * The two halves of R4 disagree on purpose: a burn-up is a live chart of work in
 * flight, a review is a record of what a team committed to, and "what did this
 * issue weigh when it entered this sprint" is exactly the question current
 * points destroy.
 */
export const SNAPSHOT_POINTS_FOOTNOTE =
  'Points are what each issue weighed when it entered this sprint, not what it is estimated at '
  + 'today — a retrospective asks what was committed to, and a later re-estimate must not rewrite '
  + 'that answer.'

// ── URL state (§4.4 — a report that cannot be pasted into Slack is half a feature) ──

export interface SprintReportState {
  /** `''` = "the page picks", which resolves to the ACTIVE sprint. */
  sprintId: string
  measure: SprintMeasure
}

/**
 * Read both pages' state out of the URL. An unrecognised measure degrades to
 * `COUNT` (the default) rather than to a blank page; an unknown sprint id is
 * passed through untouched so the SERVER can answer 404 and the page can offer
 * the picker — guessing a different sprint would silently show one report under
 * another one's link.
 */
export function readSprintReportState(params: URLSearchParams): SprintReportState {
  return {
    sprintId: params.get('sprintId') ?? '',
    measure: params.get('measure') === 'POINTS' ? 'POINTS' : 'COUNT',
  }
}

/**
 * The burn-up's REQUEST parameters. `measure` is on the wire here (the server
 * sums different rows for it), unlike the cycle-time page's client-only toggle.
 */
export function writeBurnupParams(state: SprintReportState): Record<string, string> {
  const out: Record<string, string> = { measure: state.measure }
  if (state.sprintId) out.sprintId = state.sprintId
  return out
}

/** The burn-up's URL — identical to its request, since both parameters are real. */
export function writeBurnupUrl(state: SprintReportState): Record<string, string> {
  return writeBurnupParams(state)
}

/**
 * The review takes no measure: it always reports a count AND a point sum,
 * because a retrospective reads them together. Keeping the measure out of its
 * URL is what stops a copied burn-up link from implying a review setting that
 * does not exist.
 */
export function writeReviewParams(state: Pick<SprintReportState, 'sprintId'>): Record<string, string> {
  return state.sprintId ? { sprintId: state.sprintId } : {}
}

// ── Which sprint ─────────────────────────────────────────────────────────────

/** Why the page is showing the sprint it is showing — printed, never inferred. */
export type SprintChoiceReason =
  /** The URL named it. */
  | 'PINNED'
  /** The one that is running — the documented default. */
  | 'ACTIVE'
  /** Nothing is running, so the most recent finished sprint is shown instead. */
  | 'LATEST_COMPLETED'
  /** Nothing is running and nothing has finished — the next planned sprint. */
  | 'PLANNED'
  /** This project has never had a sprint. */
  | 'NONE'

export interface SprintChoice {
  sprint: Sprint | null
  reason: SprintChoiceReason
  /** The URL named a sprint this project does not have — said out loud, not swallowed. */
  unknownPinned: boolean
}

/**
 * Resolve the sprint on screen from the URL and the project's sprint list.
 *
 * The fallback chain is deliberate and is **never** a statement about the
 * project's capabilities: a project with no sprint at all is a project with no
 * sprint at all, not a Kanban project. Whether this project runs sprints is
 * `delivery.board`, which is DECLARED — inferring it from whether sprints exist
 * is the documented shipped bug this whole capability model was built to delete.
 */
export function resolveSprintChoice(sprintId: string, sprints: Sprint[]): SprintChoice {
  if (sprintId) {
    const pinned = sprints.find(s => s.id === sprintId)
    if (pinned) return { sprint: pinned, reason: 'PINNED', unknownPinned: false }
    // Not in the list: it may have been deleted, or the list may be capped. The
    // request still goes out with the id — the server is the authority on
    // whether it exists — so the page shows the id it was asked for and says it
    // could not name it, rather than silently swapping in another sprint.
    return { sprint: null, reason: 'PINNED', unknownPinned: true }
  }
  const active = sprints.find(s => s.state === 'ACTIVE')
  if (active) return { sprint: active, reason: 'ACTIVE', unknownPinned: false }
  // The list arrives ACTIVE first, then FUTURE ascending, then COMPLETED
  // descending — so the first COMPLETED row is the most recent one.
  const completed = sprints.find(s => s.state === 'COMPLETED')
  if (completed) return { sprint: completed, reason: 'LATEST_COMPLETED', unknownPinned: false }
  const planned = sprints.find(s => s.state === 'FUTURE')
  if (planned) return { sprint: planned, reason: 'PLANNED', unknownPinned: false }
  return { sprint: null, reason: 'NONE', unknownPinned: false }
}

// ── The burn-up series ───────────────────────────────────────────────────────

export interface BurnupRow {
  /** `YYYY-MM-DD`, UTC. */
  date: string
  /** Total work in the sprint that day — null once the day is in the future. */
  scope: number | null
  /** Cumulative work closed by the end of that day — null in the future. */
  completed: number | null
  /** The guide from (start, 0) to (end, committed). Drawn across the whole sprint. */
  ideal: number
  /** Every membership change that landed on this day, in order. */
  changes: ScopeChange[]
  /** After today — nothing is known about it, so nothing is drawn. */
  future: boolean
}

/** The UTC day an instant falls on, or `null` when it is not a real instant. */
export function utcDay(iso: string | null | undefined): string | null {
  if (!iso) return null
  const ms = Date.parse(iso)
  return Number.isNaN(ms) ? null : isoDate(ms)
}

/** The scope-change log, indexed by the UTC day each event landed on. */
export function changesByDay(changes: ScopeChange[]): Map<string, ScopeChange[]> {
  const map = new Map<string, ScopeChange[]>()
  for (const change of changes) {
    const day = utcDay(change.at)
    if (!day) continue
    const list = map.get(day)
    if (list) list.push(change)
    else map.set(day, [change])
  }
  return map
}

/**
 * The rows the chart and the table under it both read — the same numbers in the
 * same order, which is the accessibility answer and the CSV at once.
 *
 * Two rules are enforced here rather than in the chart, so the table cannot
 * disagree with the picture:
 *
 *  1. **The lines stop at today.** A day after today carries no scope and no
 *     completed value, so Recharts breaks the line instead of running it flat to
 *     the sprint's end — which reads as "and then nothing more will happen", a
 *     prediction this report does not make.
 *  2. **The x axis is the SPRINT, not the data.** Days are enumerated from the
 *     sprint's start to its end even when the server sent fewer, so a sprint
 *     halfway through looks halfway through instead of being zoomed to fill the
 *     panel (§1.6 #4, the comparability rule).
 */
export type BurnupSource = Pick<
  SprintBurnupReport,
  'sprint' | 'series' | 'scopeChanges' | 'committedAtStart' | 'startAt' | 'endAt'
>

export function burnupRows(report: BurnupSource, today: string = todayIso()): BurnupRow[] {
  const byDate = new Map<string, BurnupPoint>()
  for (const point of report.series) byDate.set(point.date, point)
  const changes = changesByDay(report.scopeChanges)

  const dates = enumerateDays(report, [...byDate.keys()])
  const span = Math.max(1, dates.length - 1)

  return dates.map((date, i) => {
    const point = byDate.get(date)
    const future = date > today
    return {
      date,
      scope: future || !point ? null : point.scope,
      completed: future || !point ? null : point.completed,
      // A guide, drawn across the WHOLE sprint including the days the measured
      // lines deliberately do not reach. That asymmetry is the point: it is
      // where the team said it would be, not where it is going to be.
      ideal: (report.committedAtStart * i) / span,
      changes: changes.get(date) ?? [],
      future,
    }
  })
}

/**
 * Every UTC day of the sprint, or the server's own dates when that is unusable.
 *
 * The server's series ends at TODAY (or at `completedAt`), never at the planned
 * end — so a running sprint's axis is widened here to its `endAt` and the extra
 * days are drawn empty. A **completed** sprint is deliberately not widened: its
 * record ended when it was closed, and stretching the axis to a planned end it
 * beat would draw a week of blank days after a sprint that finished early and
 * make an early finish look like a stall.
 */
function enumerateDays(report: BurnupSource, seriesDates: string[]): string[] {
  const sorted = [...seriesDates].sort()
  const start = utcDay(report.startAt) ?? sorted[0]
  const last = sorted[sorted.length - 1]
  const declaredEnd = report.sprint?.state === 'COMPLETED' ? null : utcDay(report.endAt)
  const end = declaredEnd && (!last || declaredEnd > last) ? declaredEnd : last
  if (!start || !end || end < start) return sorted
  const from = Date.parse(`${start}T00:00:00Z`)
  const to = Date.parse(`${end}T00:00:00Z`)
  const days = Math.round((to - from) / DAY_MS) + 1
  // A hand-typed end date can be centuries out; the chart falls back to the
  // days the server sent rather than laying out ten thousand points.
  if (!Number.isFinite(days) || days < 1 || days > MAX_BURNUP_DAYS) return sorted
  const out: string[] = []
  for (let i = 0; i < days; i++) out.push(isoDate(from + i * DAY_MS))
  return out
}

/** The last day with real numbers — what "the line ends here" means, in a date. */
export function lastMeasuredDay(rows: BurnupRow[]): BurnupRow | null {
  for (let i = rows.length - 1; i >= 0; i--) if (rows[i].scope != null) return rows[i]
  return null
}

// ── Printing a measure ───────────────────────────────────────────────────────

/**
 * One measure, printed the same way everywhere: counts are whole numbers, points
 * go through the same formatter every sprint section and board column uses, so a
 * burn-up never prints `3.00` where the Backlog prints `3`.
 */
export function formatMeasure(value: number | null | undefined, measure: SprintMeasure): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return measure === 'POINTS' ? formatPoints(value) : Math.round(value).toLocaleString()
}

/** A signed delta for the scope-change log — `+1`, `−3`, `+2.5`. */
export function formatDelta(delta: number, measure: SprintMeasure): string {
  if (!Number.isFinite(delta)) return '—'
  const body = formatMeasure(Math.abs(delta), measure)
  return `${delta < 0 ? '−' : '+'}${body}`
}
// ── The review record ────────────────────────────────────────────────────────

/** An empty list — what a missing section degrades to, never a crash. */
export const EMPTY_REVIEW_LIST: SprintReviewList = {
  count: 0, points: null, unestimatedCount: 0, issues: [],
}

/**
 * Read one of the five lists defensively, deriving anything the server left out
 * from the rows themselves.
 *
 * The tolerance is not decoration. This report's whole promise is that **a
 * completed sprint's record does not quietly shed rows**, and a page that threw
 * on a missing count would shed all of them at once. Rows are the truth; the
 * totals are a convenience that can be recomputed.
 *
 * A derived sum is **null when no row carried an estimate**, matching the wire:
 * defaulting it to 0 would re-introduce, on the fallback path only, exactly the
 * "it added up to nothing" claim the nullable sum exists to prevent.
 */
export function readReviewList(raw: SprintReviewList | null | undefined): SprintReviewList {
  if (!raw) return EMPTY_REVIEW_LIST
  const issues = raw.issues ?? []
  const estimated = issues.filter(i => typeof i.points === 'number')
  return {
    issues,
    count: typeof raw.count === 'number' ? raw.count : issues.length,
    points: raw.points === null || typeof raw.points === 'number'
      ? raw.points
      : (estimated.length > 0 ? round2(estimated.reduce((n, i) => n + (i.points ?? 0), 0)) : null),
    unestimatedCount: typeof raw.unestimatedCount === 'number'
      ? raw.unestimatedCount
      : issues.length - estimated.length,
  }
}

/**
 * Whether a list's point sum is a measurement at all.
 *
 * Structural since round 2: the server nulls the sum when nothing in the list
 * was estimated, so this is simply "is there a number here". It stays a named
 * predicate rather than an inline `!== null` because it is the one rule every
 * point figure on these two pages goes through — "the work added up to nothing"
 * and "nobody estimated any of it" are different statements, and a bare sum
 * renders them identically (§6, documented failure mode #4).
 */
export function hasPoints(list: SprintReviewList): boolean {
  return list.points !== null
}

function round2(n: number): number {
  return Math.round(n * 100) / 100
}

/** Two point sums, either of which may be "nothing here was estimated". */
function addPoints(a: number | null, b: number | null): number | null {
  if (a === null && b === null) return null
  return round2((a ?? 0) + (b ?? 0))
}

export interface ReviewSummary {
  /** What the sprint held when it ended — completed + carried over. The denominator. */
  atEndCount: number
  atEndPoints: number | null
  completedCount: number
  completedPoints: number | null
  /** What it committed to at its start — its own figure, never the denominator. */
  committedCount: number
  committedPoints: number | null
  addedCount: number
  removedCount: number
  carriedCount: number
}

/**
 * The numbers behind the header line (§2.4).
 *
 * Prefers the server's own `totals` — one computation, so the sentence and the
 * five lists cannot drift — and falls back to the lists when a response does not
 * carry them.
 *
 * **The denominator is `atEndCount`**, what the sprint held when it ended, and
 * that is settled rather than a preference: completed work is a subset of it by
 * construction, so the ratio cannot exceed one. Measuring against the commitment
 * compared two different populations — work added after the start can be
 * completed — and "completed 25 of 23" was reachable. The commitment keeps its
 * own count and sum here, and the added/removed clauses are what disclose the
 * drift between the two.
 */
export function reviewSummary(report: SprintReviewReport): ReviewSummary {
  const completed = readReviewList(report.completed)
  const carried = readReviewList(report.carriedOver)
  const committed = readReviewList(report.committed)
  const added = readReviewList(report.addedAfterStart)
  const removed = readReviewList(report.removedBeforeEnd)
  const totals = report.totals
  return {
    atEndCount: totals ? totals.atEndCount : completed.count + carried.count,
    atEndPoints: totals ? totals.atEndPoints : addPoints(completed.points, carried.points),
    completedCount: totals ? totals.completedCount : completed.count,
    completedPoints: totals ? totals.completedPoints : completed.points,
    committedCount: totals ? totals.committedCount : committed.count,
    committedPoints: totals ? totals.committedPoints : committed.points,
    addedCount: totals ? totals.addedAfterStartCount : added.count,
    removedCount: removed.count,
    carriedCount: carried.count,
  }
}

/**
 * The one-line header §2.4 pins:
 * *"Sprint 12 · 14 Aug – 28 Aug · completed 18 of 25 issues (41 of 55 points) · 5 added after start."*
 *
 * "of 25" is what the sprint **held at its end**. The point clause is dropped
 * entirely when nothing in it was estimated — a headline reporting "0 of 0
 * points" on an unestimated sprint invents a measurement nobody made — and so is
 * the "added" clause when nothing arrived late, because "0 added after start" is
 * noise dressed as a finding.
 */
export function reviewHeadline(report: SprintReviewReport, range: string | null): string {
  const s = reviewSummary(report)
  const parts = [report.sprint?.name ?? 'This sprint']
  if (range) parts.push(range)
  const points = s.atEndPoints !== null
    ? ` (${formatPoints(s.completedPoints ?? 0)} of ${formatPoints(s.atEndPoints)} points)`
    : ''
  parts.push(
    `completed ${s.completedCount.toLocaleString()} of ${s.atEndCount.toLocaleString()} `
    + `issue${s.atEndCount === 1 ? '' : 's'}${points}`,
  )
  if (s.addedCount > 0) {
    parts.push(`${s.addedCount.toLocaleString()} added after start`)
  }
  return `${parts.join(' · ')}.`
}

/**
 * The sprint as a window sentence, for an exported image's footer (R7).
 *
 * A sprint **is** this report's window, so an image dated only by `computedAt`
 * would not say which fortnight it is about — and two burn-ups filed side by
 * side would be indistinguishable. A sprint that never started has no dates and
 * says so rather than inventing them: `startAt` is null until the commitment
 * event exists, which is the same fact the empty chart above it states.
 */
export function sprintWindow(name: string, startAt: string | null, endAt: string | null): string {
  const from = startAt ? startAt.slice(0, 10) : ''
  const to = endAt ? endAt.slice(0, 10) : ''
  if (from && to) return `${name} · ${from} → ${to} (UTC)`
  if (from) return `${name} · from ${from} (UTC), no planned end`
  return `${name} · not started`
}
