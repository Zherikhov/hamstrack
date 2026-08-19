/**
 * Report window state — the pure half of every report page (HD-28, reports
 * epic HD-5).
 *
 * **Report state lives in the URL, not in a store.** A shareable URL is the
 * sharing mechanism R7 builds on (reports-proposal §1.6 #2, §4.4), and it is the
 * cheapest one: paste it into Slack and the reader sees the same numbers, the
 * same window and the same filters. So everything a report is parameterised by
 * is a query parameter, and this module is the only place that knows how to read
 * one and how to write one back.
 *
 * Everything here is deliberately date-string arithmetic in **UTC**, matching the
 * server: both ends of a window are inclusive, and a day is a UTC day. Doing this
 * with local `Date` getters would move a date by a day for anybody west of
 * Greenwich, which is exactly the class of silent wrongness this feature is
 * supposed to be free of.
 *
 * **There is no bucket-boundary arithmetic in this file, and none may be added.**
 * Where a bucket starts, and whether an end bucket is partial, is the calendar
 * rule the server already owns (`ReportInterval` in Java, `date_trunc` in SQL);
 * a third implementation in TypeScript is a rule in three languages that drift
 * independently, and the first disagreement footnotes the wrong bar. The window
 * the *user picked* is this module's business; where the server cuts it is not.
 * Read `buckets[].partial` off the response.
 */

import type { ReportInterval } from '../../types'

/**
 * The offered windows, in days (inclusive of both endpoints).
 *
 * These are windows the user CHOOSES, so each one is a request the server may
 * still refuse: 365 is the default of `app.reports.max-window-days`, and on an
 * instance whose operator lowered the cap the wider presets answer 400 with a
 * detail naming it, rendered verbatim rather than as a generic error.
 *
 * The **default** window is not in this list and is not computed here at all —
 * the server derives it from its own cap (`min(90, max-window-days)`), so a
 * request that names no window always succeeds. Asking for nothing is how the
 * page loads; a duplicated 90 here would 400 on exactly the instances the
 * server-side default exists to serve.
 */
export const RANGE_PRESETS = [30, 90, 180, 365] as const

const DAY_MS = 86_400_000

/** `YYYY-MM-DD` for a UTC epoch-millis instant. */
export function isoDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

/** Today, in UTC. Injectable so tests are not a coin flip at 23:59 local. */
export function todayIso(now: Date = new Date()): string {
  return isoDate(now.getTime())
}

/**
 * Epoch millis at UTC midnight of an ISO date, or `null` when the string is not
 * a real date. Tolerant on purpose: these values come out of a URL somebody may
 * have hand-edited, and a malformed one must degrade to "unset" — i.e. to the
 * window the server picks — rather than throw a blank page.
 */
export function parseIso(value: string | null | undefined): number | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const ms = Date.parse(`${value}T00:00:00Z`)
  if (Number.isNaN(ms)) return null
  // Rejects 2026-02-31 and friends, which `Date.parse` happily rolls over.
  return isoDate(ms) === value ? ms : null
}

/** `iso` shifted by whole UTC days (negative shifts backwards). */
export function shiftIso(iso: string, days: number): string {
  const ms = parseIso(iso)
  if (ms == null) return iso
  return isoDate(ms + days * DAY_MS)
}

/** Window length in days, counting both endpoints — the server's `windowDays()`. */
export function daysInclusive(from: string, to: string): number {
  const a = parseIso(from)
  const b = parseIso(to)
  if (a == null || b == null) return 0
  return Math.round((b - a) / DAY_MS) + 1
}

/** The full parameter set of the flow report, exactly as the URL carries it. */
export interface FlowState {
  /**
   * Window start, or `''` for "the server picks". Empty is a real, sendable
   * state — not a placeholder to be filled in before the request — because the
   * server's default window is derived from its own `max-window-days` and is
   * therefore the only window guaranteed to be served on every instance.
   */
  from: string
  /** Window end, or `''` for "today, in the server's UTC". */
  to: string
  interval: ReportInterval
  typeId: string
  componentId: string
  labelId: string
}

/**
 * Read the report state out of the URL, keeping anything absent or unusable
 * absent — the defaults are the server's, not ours.
 *
 * Nothing here validates ids: an id that does not exist — or belongs to another
 * tenant — simply matches nothing server-side and yields an empty series, which
 * is deliberate (answering with a 404 would make the endpoint an existence
 * oracle). The page is told which filter matched nothing by
 * `meta.unmatchedFilters` and says so, rather than guessing here.
 *
 * A window with `from > to` is passed through untouched so the SERVER refuses it
 * and the page can print the server's own sentence; we do not second-guess a
 * rule whose message names a configured cap we cannot see. An unparseable date
 * degrades to "unset", i.e. to the server's default — never to a blank page.
 */
export function readFlowState(params: URLSearchParams): FlowState {
  const from = params.get('from')
  const to = params.get('to')
  const interval: ReportInterval = params.get('interval') === 'DAY' ? 'DAY' : 'WEEK'
  return {
    from: parseIso(from) != null ? from! : '',
    to: parseIso(to) != null ? to! : '',
    interval,
    typeId: params.get('typeId') ?? '',
    componentId: params.get('componentId') ?? '',
    labelId: params.get('labelId') ?? '',
  }
}

/**
 * The state as URL parameters, and the request's parameters too — one function,
 * so the link in the address bar and the bytes on the wire cannot disagree.
 *
 * An unset member is **omitted rather than sent empty**, which is what makes the
 * first load a genuinely parameterless request: the server then derives the
 * window from its own `max-window-days` and always answers. Once the reader
 * touches a control the page pins the window it is showing (see the page's
 * `update`), so from that point the URL is a complete description of the report
 * and means the same thing to the next person who opens it.
 */
export function writeFlowParams(state: FlowState): Record<string, string> {
  const out: Record<string, string> = { interval: state.interval }
  if (state.from) out.from = state.from
  if (state.to) out.to = state.to
  if (state.typeId) out.typeId = state.typeId
  if (state.componentId) out.componentId = state.componentId
  if (state.labelId) out.labelId = state.labelId
  return out
}

/**
 * Which preset a window matches, or `'custom'`.
 *
 * Takes the two dates rather than a `FlowState` because the window on screen is
 * not always the window in the URL: with no window in the URL the page shows the
 * one the SERVER chose and echoed back, and that is the window this control has
 * to describe.
 */
export function presetOf(from: string, to: string, today: string = todayIso()): number | 'custom' {
  if (!from || to !== today) return 'custom'
  const days = daysInclusive(from, to)
  return (RANGE_PRESETS as readonly number[]).includes(days) ? days : 'custom'
}

/** The window a preset denotes: N days ending today, inclusive. */
export function presetWindow(days: number, today: string = todayIso()): { from: string; to: string } {
  return { from: shiftIso(today, -(days - 1)), to: today }
}

/**
 * How many days of history this report has, counted from `meta.firstIssueAt` —
 * the creation date of the earliest issue the report's own filters admit.
 *
 * Deliberately NOT the project's `createdAt`, which is a different date (often
 * years off) and used to cost a second request for a number that was never the
 * right one. The caveat that comes with it: because `firstIssueAt` is filtered
 * like the rest of the response, a filtered report's answer is "how far back
 * THIS chart could reach", not the project's age — the two sentences the page
 * prints are different for exactly that reason.
 */
export function historyDays(firstIssueAt: string | null | undefined, today: string = todayIso()): number | null {
  if (!firstIssueAt) return null
  const ms = Date.parse(firstIssueAt)
  if (Number.isNaN(ms)) return null
  const days = daysInclusive(isoDate(ms), today)
  return days > 0 ? days : null
}

/** Below this, a report has no trend to show yet — only a short history (§2.1). */
export const THIN_HISTORY_DAYS = 14
