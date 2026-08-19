import { useEffect, useState, type ReactNode } from 'react'
import { AlertTriangle, Info } from 'lucide-react'
import type { ReportMeta } from '../../types'
import { Button } from '../../components/ui'

/**
 * The chrome every report shares (HD-28, the first slice of the reports epic) —
 * card, notices, the provenance line and the table-under-the-chart.
 *
 * These are not styling helpers. Each one is a **disclosure rule** from
 * reports-proposal §1.7 / §6 given exactly one implementation, so that a later
 * report cannot ship without it or ship a differently-worded version of it:
 *
 *  • `MetaLine` — a report always says when it was computed and how many issues
 *    it saw. The recurring complaint about competitors' reports is "these numbers
 *    don't match what I expected", and its mechanism is a report that quietly
 *    left data out.
 *  • `TruncationNotice` — when the row cap bit, the page says so ABOVE the chart,
 *    not in a footnote. (Always false on the flow report, which aggregates in
 *    SQL; the component exists because R3's row-level reports do truncate, and a
 *    convention added after the fact is a convention that gets skipped.)
 *  • `Notice` — the caveats that would otherwise be invisible: partial end
 *    buckets, the `closed_at` reopen hazard, thin history.
 *  • `UnmatchedFilterNotice` — an empty chart because YOUR FILTER matched
 *    nothing must not look like an empty chart because nothing happened. The
 *    server names the guilty parameter (`meta.unmatchedFilters`); without this
 *    the two cases are the same picture and the reader believes the wrong one.
 *  • `RateLimitNotice` — the reports surface is throttled per principal, and a
 *    429 is a retryable throttle, not a fault. Rendering it as a generic error
 *    ("something went wrong") tells the reader to give up on a request that
 *    would have succeeded ten seconds later.
 *  • `SeriesTable` — the accessibility answer AND the CSV R7 exports. A chart
 *    with no table underneath is not finished.
 */

// ── Data-visualisation palette (DESIGN.md → Data Visualisation) ──────────────
// Series colours come from the categorical ramp, which deliberately contains no
// red / amber / yellow / teal / slate — those hues already mean priority, status
// category and the sandbox→pending→production safety state. NEVER hardcode a hex
// in a chart; and when a series IS a taxonomy entity, use that entity's own
// configured `color` instead of this ramp.
export const CHART_SERIES = [
  'var(--color-chart-1)',
  'var(--color-chart-2)',
  'var(--color-chart-3)',
  'var(--color-chart-4)',
  'var(--color-chart-5)',
] as const

/** Derived / reference series — always secondary, always dashed. */
export const CHART_CONTEXT = 'var(--color-chart-context)'
export const CHART_GRID = 'var(--color-chart-grid)'
export const CHART_AXIS = 'var(--color-chart-axis)'

/** A white report card on the slate canvas (DESIGN.md → Layout, cards). */
export function ReportCard({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={className}
      style={{
        background: 'var(--color-card)',
        border: '1px solid var(--color-border)',
        borderRadius: 'var(--radius-lg)',
        boxShadow: 'var(--shadow-card)',
        padding: 18,
      }}
    >
      {children}
    </div>
  )
}

type NoticeTone = 'info' | 'warn'

/**
 * A caveat the reader has to see BEFORE reading the numbers. Deliberately not a
 * tooltip: a disclosure nobody hovers is a disclosure nobody made.
 */
export function Notice({ tone = 'info', children }: { tone?: NoticeTone; children: ReactNode }) {
  const color = tone === 'warn' ? 'var(--color-warning)' : 'var(--color-info)'
  const Icon = tone === 'warn' ? AlertTriangle : Info
  return (
    <div
      role="note"
      className="flex items-start gap-2 text-sm"
      style={{
        background: `color-mix(in srgb, ${color} 10%, white)`,
        border: `1px solid color-mix(in srgb, ${color} 32%, white)`,
        borderRadius: 'var(--radius-md)',
        color: 'var(--color-text-secondary)',
        padding: '9px 12px',
      }}
    >
      <Icon size={15} style={{ color, flexShrink: 0, marginTop: 1 }} />
      <span>{children}</span>
    </div>
  )
}

/**
 * "The row cap bit and this is a partial view." Renders nothing when it did not
 * — but it is always mounted, so no report can forget the case.
 */
export function TruncationNotice({ meta }: { meta: ReportMeta | undefined }) {
  if (!meta?.truncated) return null
  return (
    <Notice tone="warn">
      This report is <b>truncated</b>: it was computed from the most recent{' '}
      <span className="mono">{meta.cap.toLocaleString()}</span> issues, not from every issue
      that matches. Narrow the window or the filters to see a complete picture.
    </Notice>
  )
}

/**
 * The provenance line under a report (§4.3). Prints `computedAt` and
 * `basedOnIssues` verbatim — this block exists to be read, not to be swallowed.
 */
export function MetaLine({ meta }: { meta: ReportMeta | undefined }) {
  if (!meta) return null
  const computed = new Date(meta.computedAt)
  const valid = !Number.isNaN(computed.getTime())
  // One text node, not a dozen JSX fragments: this line is meant to be read (and
  // grepped, and asserted on) as a sentence.
  const line = [
    `computed ${valid ? computed.toLocaleString() : meta.computedAt}`,
    `based on ${meta.basedOnIssues.toLocaleString()} issue${meta.basedOnIssues === 1 ? '' : 's'}`,
    `row cap ${meta.cap.toLocaleString()}`,
    ...(meta.truncated ? ['TRUNCATED'] : []),
    // The new field is provenance like everything else in this block, so it is
    // printed rather than used only to decide whether a notice appears.
    ...(meta.firstIssueAt ? [`history from ${meta.firstIssueAt.slice(0, 10)}`] : []),
    'UTC day boundaries',
  ].join(' · ')
  return (
    <p className="mono" style={{ color: 'var(--color-text-muted)', fontSize: 11.5, margin: 0 }}>
      {line}
    </p>
  )
}

/**
 * "Your filter matched nothing" — the disclosure that keeps an empty chart from
 * lying by omission (`meta.unmatchedFilters`).
 *
 * A shared URL naming a since-deleted label used to render a perfectly honest,
 * perfectly plausible chart of zeros, and "no bugs were created in Q1" and "this
 * filter matches nothing" were the same picture. The server now names the guilty
 * query parameter, and `describe` turns it into whatever the page can say about
 * the control the user actually touched.
 *
 * **Word the claim exactly as weakly as the server makes it.** What is known is
 * *no issue in this project carries this id* — NOT that the id is invalid, and
 * NOT that the thing was deleted: a perfectly valid issue type nobody here has
 * ever used is reported the same way, and telling a reader their label was
 * deleted when it was merely unused is a new wrong answer replacing an old
 * missing one.
 */
export function UnmatchedFilterNotice({ meta, describe }: {
  meta: ReportMeta | undefined
  describe: (param: string) => ReactNode
}) {
  const unmatched = meta?.unmatchedFilters ?? []
  if (unmatched.length === 0) return null
  return (
    <Notice tone="warn">
      <b>This report is empty because of a filter, not because nothing happened.</b>{' '}
      No issue in this project matches{' '}
      {unmatched.map((param, i) => (
        <span key={param}>
          {i > 0 && (i === unmatched.length - 1 ? ' or ' : ', ')}
          {describe(param)}
        </span>
      ))}
      {'. '}
      That is all this means: the {unmatched.length === 1 ? 'value' : 'values'} may still be
      perfectly valid and simply unused here, so nothing is said about whether{' '}
      {unmatched.length === 1 ? 'it' : 'they'} still exist. Clear the filter to see the rest.
    </Notice>
  )
}

/**
 * A 429 from the reports surface — a **retryable throttle, not a fault**.
 *
 * Two things this must not do. It must not read as a generic failure, because
 * the request was valid, nothing was computed, and retrying after `Retry-After`
 * seconds succeeds; and it must not be worded as though it says anything about
 * this project, because the budget is spent per principal on the whole
 * `…/reports/**` path **before** the workspace and project are resolved. A 429
 * can therefore arrive for a project the caller cannot see, and is the one place
 * on this API where it precedes the 404 — so "you are being throttled on this
 * project" would be a claim the response does not support.
 */
export function RateLimitNotice({ retryAfter, onRetry }: {
  retryAfter: number | undefined
  onRetry: () => void
}) {
  const initial = Number.isFinite(retryAfter) ? Math.max(0, Math.ceil(retryAfter!)) : 0
  const [left, setLeft] = useState(initial)

  // A new 429 (a second impatient retry) restarts the countdown rather than
  // leaving a stale one ticking towards a button that would just 429 again.
  useEffect(() => setLeft(initial), [initial])
  useEffect(() => {
    if (left <= 0) return
    const t = setTimeout(() => setLeft(n => n - 1), 1000)
    return () => clearTimeout(t)
  }, [left])

  return (
    <Notice tone="warn">
      <b>Too many report requests.</b>{' '}
      {left > 0
        ? <>Try again in <span className="mono">{left}</span> second{left === 1 ? '' : 's'}.</>
        : <>You can try again now.</>}{' '}
      Nothing was computed and nothing is wrong with this report — reports are the most
      expensive read in the product, so each account gets a budget per minute across all of
      them. It is counted before any project is looked up, so this says nothing about this
      project in particular.
      <span className="block mt-2">
        <Button size="sm" onClick={onRetry} disabled={left > 0}>Try again</Button>
      </span>
    </Notice>
  )
}

export interface SeriesColumn<T> {
  key: string
  label: string
  /** Right-aligned by default — these are numbers. Pass `align: 'left'` for a date. */
  align?: 'left' | 'right'
  render: (row: T) => ReactNode
}

/**
 * The table under every chart, with the same numbers in the same order.
 *
 * It is not a fallback. It is (a) how a screen-reader user reads the report at
 * all — the chart itself is `aria-hidden` decoration — and (b) the exact series
 * R7 serialises to CSV, which the research says is the export people actually
 * want and nobody ships (§1.6 #1).
 */
export function SeriesTable<T>({
  caption, columns, rows, rowKey, footer,
}: {
  caption: string
  columns: SeriesColumn<T>[]
  rows: T[]
  rowKey: (row: T, index: number) => string
  footer?: ReactNode
}) {
  return (
    <table className="w-full mono" style={{ borderCollapse: 'collapse', fontSize: 12 }}>
      <caption className="text-left" style={{ color: 'var(--color-text-secondary)', fontSize: 12.5, paddingBottom: 8 }}>
        {caption}
      </caption>
      <thead>
        <tr>
          {columns.map(c => (
            <th
              key={c.key}
              scope="col"
              style={{
                textAlign: c.align ?? 'right',
                padding: '6px 10px',
                borderBottom: '1px solid var(--color-border-2)',
                color: 'var(--color-text-secondary)',
                fontWeight: 600,
                whiteSpace: 'nowrap',
              }}
            >
              {c.label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={rowKey(row, i)}>
            {columns.map(c => (
              <td
                key={c.key}
                style={{
                  textAlign: c.align ?? 'right',
                  padding: '5px 10px',
                  borderBottom: '1px solid var(--color-border)',
                  color: 'var(--color-text)',
                  whiteSpace: 'nowrap',
                }}
              >
                {c.render(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
      {footer}
    </table>
  )
}

/** A legend swatch + label pair, so colour is never the only encoding. */
export function LegendItem({ color, label, dashed }: { color: string; label: string; dashed?: boolean }) {
  return (
    <span className="inline-flex items-center gap-2" style={{ fontSize: 12.5, color: 'var(--color-text-secondary)' }}>
      <span
        aria-hidden="true"
        style={{
          width: 16,
          height: 0,
          borderTop: `3px ${dashed ? 'dashed' : 'solid'} ${color}`,
          display: 'inline-block',
        }}
      />
      {label}
    </span>
  )
}
