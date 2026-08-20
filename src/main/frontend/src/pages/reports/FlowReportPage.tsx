import { Suspense, lazy, useMemo, useRef, type ReactNode } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ApiResponseError, apiGetProjectConfig, reportsApi } from '../../api'
import type { FlowBucket, ReportInterval } from '../../types'
import { REPORT_STALE_TIME, reportKey } from '../../lib/queryKeys'
import { Button, Select } from '../../components/ui'
import { useProjectComponents } from '../../components/projectComponents'
import { useWorkspaceLabels } from '../../components/labels'
import {
  CHART_CONTEXT, CHART_SERIES, LegendItem, MetaLine, Notice, RateLimitNotice, ReportCard,
  SeriesTable, TruncationNotice, UnmatchedFilterNotice,
} from './common'
import {
  RANGE_PRESETS, THIN_HISTORY_DAYS, daysInclusive, historyDays, presetOf, presetWindow,
  readFlowState, todayIso, writeFlowParams, type FlowState,
} from './window'
import { ChartExport, ReportExportBar, useReportProject } from './export'

// The chart chunk (Recharts) is split off even inside the already-lazy reports
// area, so the report's chrome — controls, totals, table, the disclosures — is
// interactive before the chart library has parsed. The table under the chart
// carries the same numbers, so nothing is unreadable while it loads.
const FlowChart = lazy(() => import('./FlowChart'))

/**
 * **Flow — created vs resolved** (`/w/:wsId/p/:projectId/reports/flow`), the
 * first report of epic HD-5 and the slice that fixes the conventions the other
 * five inherit.
 *
 * The report answers one question — *are we keeping up?* — and the interesting
 * work is not the chart. It is the four disclosures, which are the point of the
 * feature (reports-proposal §1.7, §2.1, §6):
 *
 *  1. **`meta` is printed, never swallowed** — `computedAt` and `basedOnIssues`
 *     sit under the chart, and `truncated` (when it ever bites) is a notice ABOVE
 *     it. A report that quietly leaves data out is the documented mechanism
 *     behind "these numbers don't match what I expected".
 *  2. **A too-wide window is refused, not clamped** — the server answers 400 with
 *     a detail naming `app.reports.max-window-days`, and that sentence is
 *     rendered verbatim next to a control that fixes it. A generic "something
 *     went wrong" here would hide the one fact the reader needs.
 *  3. **The `closed_at` footnote** — "resolved" means *issues closed now, dated
 *     by their latest closure*. Reopening clears `closed_at`, so an issue leaves
 *     a past bucket and joins a new one; past buckets are mutable and `openAtEnd`
 *     inherits it. This is a genuine "the number changed under me" hazard and the
 *     spec requires it in the UI, not in a doc.
 *  4. **Partial end buckets** — windows are not snapped to bucket boundaries, so
 *     a weekly window that starts mid-week opens with a short bar. Unlabelled,
 *     that reads as a drop in throughput. Read off `buckets[].partial`: Monday
 *     truncation is the server's calendar rule and is not reproduced here, so
 *     the footnote cannot end up describing a different bar than the chart drew.
 *  5. **A filter that matched nothing** (`meta.unmatchedFilters`) — a shared URL
 *     naming a label this workspace no longer carries renders a perfectly honest
 *     chart of zeros, and "nothing happened" and "your filter matches nothing"
 *     are the same picture unless somebody says which one it is.
 *
 * Plus the thin-data state: when `meta.firstIssueAt` says this report can only
 * reach back a fortnight, it says so — *"Only N days of history"* — rather than
 * drawing a confident-looking flat line, and an empty window says so in a
 * sentence. Never a blank panel. (`firstIssueAt` is filtered like the rest of the
 * response, so with a filter set the wording says *matching* history: it is this
 * chart's reach, not the project's age.)
 *
 * Two refusals get real states rather than a generic banner: a **400** prints the
 * server's own sentence — it names the cap or the date bound it measured against
 * — next to a control that fixes it, and a **429** says how many seconds to wait.
 * The 429 is a retryable throttle spent per account before any project is
 * resolved, so it is neither a fault nor a statement about this project.
 *
 * **State lives in the URL** (§4.4): window, interval and the three filters are
 * query parameters, because the shareable URL is the sharing mechanism R7 builds
 * on. **No permission gate** (§4.2): reads are not permission-gated in this
 * product, and every number here is derivable from the search API any member
 * already holds.
 */
export default function FlowReportPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()

  // `todayIso()` once per render, so the window and the "is this a preset?"
  // answer cannot straddle a UTC midnight within one paint.
  const today = todayIso()
  const state = readFlowState(searchParams)
  const params = writeFlowParams(state)

  // Issue types come from the project config endpoint — the taxonomy is never
  // hardcoded and never inferred from the issues that happen to exist.
  const { data: config } = useQuery({
    queryKey: ['projectConfig', wsId, projectId],
    queryFn: () => apiGetProjectConfig(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const { data: components = [] } = useProjectComponents(wsId, projectId)
  const { data: labels = [] } = useWorkspaceLabels(wsId)

  const { data, isPending, error, isFetching, refetch } = useQuery({
    queryKey: reportKey('flow', projectId, params),
    queryFn: () => reportsApi.flow(wsId!, projectId!, state),
    enabled: !!wsId && !!projectId,
    // Matches the endpoint's `Cache-Control: private, max-age=60`.
    staleTime: REPORT_STALE_TIME,
    retry: false,
  })

  // The window ON SCREEN, which is not always the window in the URL: on first
  // load the URL names none, the server derives one from its own
  // `max-window-days` (so a parameterless request always succeeds, even where an
  // operator lowered the cap below 90) and echoes it back. That echo is what the
  // controls have to describe — a 90 guessed here would be wrong on exactly the
  // instances the server-side default exists to serve.
  const shownFrom = data?.from ?? state.from
  const shownTo = data?.to ?? state.to

  /**
   * Merge a patch into the URL — the single writer of report state.
   *
   * Touching any control **pins the window**, including one the reader never
   * chose. Up to that point the URL may legitimately mean "whatever window this
   * server defaults to", which is a moving target and a different report to the
   * next person who opens the link; after it, the URL is a complete description
   * of what is on screen. Passing an explicit empty `from`/`to` in the patch
   * un-pins it again, which is what the refusal's way-out does.
   */
  function update(patch: Partial<FlowState>) {
    setSearchParams(
      writeFlowParams({ ...state, from: shownFrom, to: shownTo, ...patch }),
      { replace: true },
    )
  }

  const preset = presetOf(shownFrom, shownTo, today)

  // The state the export bar shares and the CSV asks for: PINNED, so the link a
  // reader is sent describes this window rather than "whatever the server
  // defaults to", which is a different report tomorrow.
  const pinned = writeFlowParams({ ...state, from: shownFrom, to: shownTo })
  const { projectKey, projectLabel } = useReportProject(wsId, projectId)
  const chartRef = useRef<HTMLDivElement>(null)

  const buckets = data?.buckets ?? []
  // Straight off the response. Partiality is a fact about where the SERVER cut
  // the window, and re-deriving it here would be Monday truncation implemented a
  // third time (Java, SQL, TypeScript) — three copies of one calendar rule, and
  // the first disagreement footnotes a bar the chart did not draw.
  const partialFirst = buckets.length > 0 && buckets[0].partial
  // Only when there IS a distinct last bar: "the first and last buckets are
  // partial" is nonsense said about a single bucket.
  const partialLast = buckets.length > 1 && buckets[buckets.length - 1].partial

  // Filtered like the rest of the response — so with a filter on, this is how
  // far back THIS CHART reaches, not how old the project is, and the sentence
  // below says the different thing.
  const filtered = !!(state.typeId || state.componentId || state.labelId)

  /**
   * The active filters in words, for the exported image's subtitle.
   *
   * A picture of a filtered chart that does not say it is filtered is the most
   * portable way to mislead somebody with numbers that are all true. Unresolvable
   * ids are printed raw, exactly as the on-page disclosure prints them.
   */
  const filterParts = [
    state.typeId && `type ${nameOrId(config?.issueTypes, state.typeId)}`,
    state.componentId && `component ${nameOrId(components, state.componentId)}`,
    state.labelId && `label ${nameOrId(labels, state.labelId)}`,
  ].filter(Boolean) as string[]
  const filterSummary = filterParts.length ? `Filtered by ${filterParts.join(' · ')}` : ''
  const history = historyDays(data?.meta.firstIssueAt, today)
  const unmatched = data?.meta.unmatchedFilters ?? []
  const empty = buckets.length > 0 && buckets.every(b => b.created === 0 && b.resolved === 0)

  const apiError = error instanceof ApiResponseError ? error : null

  /**
   * A filter parameter named by `meta.unmatchedFilters`, in the reader's own
   * vocabulary: the control they touched, plus the value if this browser can
   * still resolve it.
   *
   * When it cannot, the raw id is printed rather than a euphemism — an id the
   * workspace's label list no longer contains is the exact case this disclosure
   * exists for, and showing it lets the reader recognise the URL they were sent.
   * What is NOT said is that the thing was deleted: the server's claim is only
   * "no issue in this project carries this id", which is equally true of a
   * perfectly valid type nobody here has ever used.
   */
  function describeFilter(param: string): ReactNode {
    switch (param) {
      case 'typeId':
        return <>the <b>issue type</b> {filterValue(config?.issueTypes, state.typeId)}</>
      case 'componentId':
        return <>the <b>component</b> {filterValue(components, state.componentId)}</>
      case 'labelId':
        return <>the <b>label</b> {filterValue(labels, state.labelId)}</>
      default:
        return <>the filter <span className="mono">{param}</span></>
    }
  }

  // The answered interval, not the requested one — they agree, but the caption
  // and the axis must describe the numbers actually on screen.
  const shownInterval: ReportInterval = data?.interval ?? state.interval
  const bucketWord = shownInterval === 'WEEK' ? 'week' : 'day'
  const bucketLabel = shownInterval === 'WEEK' ? 'Week of' : 'Day'
  const columns = useMemo(() => ([
    {
      key: 'date',
      label: bucketLabel,
      align: 'left' as const,
      // The chart footnote says "an end bar is short"; the table says WHICH rows,
      // which is the reading a screen-reader user gets and the one R7 exports.
      render: (b: FlowBucket) => b.partial ? `${b.date} (partial)` : b.date,
    },
    { key: 'created', label: 'Created', render: (b: FlowBucket) => b.created.toLocaleString() },
    { key: 'resolved', label: 'Resolved', render: (b: FlowBucket) => b.resolved.toLocaleString() },
    { key: 'net', label: 'Net', render: (b: FlowBucket) => signed(b.created - b.resolved) },
    { key: 'openAtEnd', label: 'Open at end', render: (b: FlowBucket) => b.openAtEnd.toLocaleString() },
  ]), [bucketLabel])

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 style={{ fontSize: 20, fontWeight: 800, letterSpacing: '-0.01em', margin: 0 }}>Flow</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 620 }}>
          Created versus resolved. Are we keeping up — is the backlog growing or shrinking, and
          since when?
        </p>
      </div>

      {/* ── Controls. Every one of them writes the URL, so the address bar is
          always a complete description of what is on screen. ── */}
      <div className="flex flex-wrap items-end gap-2">
        <Select
          label="Range"
          aria-label="Range"
          value={String(preset)}
          onChange={e => {
            const v = e.target.value
            if (v === 'custom') return
            update(presetWindow(Number(v), today))
          }}
        >
          {RANGE_PRESETS.map(d => <option key={d} value={String(d)}>{`Last ${d} days`}</option>)}
          <option value="custom">Custom</option>
        </Select>

        <label className="flex flex-col gap-1">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>From</span>
          <input
            type="date" value={shownFrom} max={shownTo || undefined}
            onChange={e => e.target.value && update({ from: e.target.value })}
            className="px-3 py-2 text-sm rounded-md border outline-none"
            style={{ background: 'white', borderColor: 'var(--color-border-2)', color: 'var(--color-text)' }}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>To</span>
          <input
            type="date" value={shownTo} min={shownFrom || undefined}
            onChange={e => e.target.value && update({ to: e.target.value })}
            className="px-3 py-2 text-sm rounded-md border outline-none"
            style={{ background: 'white', borderColor: 'var(--color-border-2)', color: 'var(--color-text)' }}
          />
        </label>

        <Select
          label="Buckets" aria-label="Buckets" value={state.interval}
          onChange={e => update({ interval: e.target.value as ReportInterval })}
        >
          <option value="WEEK">Weekly</option>
          <option value="DAY">Daily</option>
        </Select>

        <Select
          label="Type" aria-label="Issue type" value={state.typeId}
          onChange={e => update({ typeId: e.target.value })}
        >
          <option value="">All types</option>
          {(config?.issueTypes ?? []).map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
        </Select>

        <Select
          label="Component" aria-label="Component" value={state.componentId}
          onChange={e => update({ componentId: e.target.value })}
        >
          <option value="">All components</option>
          {components.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </Select>

        <Select
          label="Label" aria-label="Label" value={state.labelId}
          onChange={e => update({ labelId: e.target.value })}
        >
          <option value="">All labels</option>
          {labels.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
        </Select>
      </div>

      {/* Export (R7) — the shareable URL and the SERIES csv, kept separate from
          the issue-list export by two labels and a sentence (§1.6 #1). */}
      <ReportExportBar
        wsId={wsId}
        projectId={projectId}
        projectKey={projectKey}
        shareParams={pinned}
        csv={[{ kind: 'flow', label: 'Chart series', params: pinned, slug: 'flow' }]}
        disabled={!data}
      />

      {/* ── The 400s — ALL of them through one path. Every refusal on this
          endpoint has the same shape: it names the bound it measured against
          (`max-window-days` for a too-wide window, 1970…2200 for an out-of-band
          date, the two dates themselves for an inverted window), and rendering
          that sentence verbatim is the whole reason the server refuses instead of
          clamping (§6). A generic banner would throw away the only fact the
          reader needs; a branch per rule would end up wording them differently
          and would need extending for the next one. ── */}
      {apiError && apiError.status === 400 && (
        <Notice tone="warn">
          <b>That window can’t be reported on.</b>{' '}
          {apiError.detail}
          <span className="block mt-2">
            {/* Clears the window instead of naming one. "The last 90 days" was a
                guess that fails on precisely the instances that produce this
                refusal — an operator who set max-window-days below 90 — whereas
                asking for no window at all makes the server pick one derived
                from its own cap, which it is guaranteed to serve. */}
            <Button size="sm" onClick={() => update({ from: '', to: '' })}>
              Use the default window
            </Button>
          </span>
        </Notice>
      )}
      {apiError && apiError.status === 404 && (
        <Notice tone="warn">
          Project not found — it may have been deleted, or your access was removed.
        </Notice>
      )}
      {/* A throttle, not a fault. Its own state, with the wait the server named:
          rendered as a generic error it would tell the reader to give up on a
          request that succeeds a few seconds later. It carries no claim about
          this project — the budget is spent per account before the project is
          resolved, so a 429 can arrive for a project the caller cannot see. */}
      {apiError && apiError.status === 429 && (
        <RateLimitNotice retryAfter={apiError.retryAfter} onRetry={() => void refetch()} />
      )}
      {/* Anything else — including a failure that never reached the API layer (a
          dropped connection). Blank is the one answer this page may never give,
          so an unrecognised error still gets a sentence. */}
      {error && !HANDLED_STATUSES.includes(apiError?.status ?? 0) && (
        <Notice tone="warn">
          Couldn’t load this report: {apiError?.detail ?? 'the request failed. Try again.'}
        </Notice>
      )}

      <UnmatchedFilterNotice meta={data?.meta} describe={describeFilter} />

      <TruncationNotice meta={data?.meta} />

      {/* Thin data (§2.1): a statement about the DATA, not about the window — a
          two-week-old project asked for 90 days gets 90 buckets and 14 days of
          reality in them, which reads as a collapse if nobody says so. Measured
          from `meta.firstIssueAt`, which arrives with the report; the project's
          own createdAt is a different date and used to cost a second request for
          a number that was never the right one. */}
      {history !== null && history < THIN_HISTORY_DAYS && (
        <Notice>
          Only {history} day{history === 1 ? '' : 's'} of {filtered ? 'matching ' : ''}history —
          trends need a few weeks. The {filtered ? 'earliest issue these filters match' : 'first issue here'} was
          created {history} day{history === 1 ? '' : 's'} ago, so the window below is real but there is
          not enough of it yet to read as a trend.
          {filtered && ' That is how far back this chart reaches, not how old the project is.'}
        </Notice>
      )}

      {data && (
        <>
          {/* Totals — the three numbers under the chart (§2.1). `net` is the
              server's arithmetic, not ours, so every surface agrees. */}
          <div className="flex flex-wrap gap-3">
            <Total label="Created" value={data.totals.created} color={CHART_SERIES[0]} />
            <Total label="Resolved" value={data.totals.resolved} color={CHART_SERIES[2]} />
            <Total
              label="Net"
              value={data.totals.net}
              signed
              color={CHART_CONTEXT}
              hint={data.totals.net > 0 ? 'the backlog grew' : data.totals.net < 0 ? 'the backlog shrank' : 'level'}
            />
          </div>

          <ReportCard>
            <div className="flex flex-wrap items-center gap-4 mb-3">
              <LegendItem color={CHART_SERIES[0]} label="Created" />
              <LegendItem color={CHART_SERIES[2]} label="Resolved" />
              <LegendItem color={CHART_CONTEXT} label="Open at end (right axis)" dashed />
              {isFetching && (
                <span className="mono ml-auto" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                  refreshing…
                </span>
              )}
              {/* The picture of THIS chart, carrying the project, the window and
                  `computedAt` in its footer — a chart pasted into a chat is
                  separated from its URL the moment it is pasted. */}
              {!empty && <ChartExport
                chartRef={chartRef}
                title="Flow — created vs resolved"
                subtitle={filterSummary}
                slug="flow"
                projectKey={projectKey}
                provenance={{
                  project: projectLabel,
                  window: `${data.from} → ${data.to} (UTC), ${shownInterval === 'WEEK' ? 'weekly' : 'daily'} buckets`,
                  computedAt: data.meta.computedAt,
                  basedOnIssues: data.meta.basedOnIssues,
                  truncated: data.meta.truncated,
                  cap: data.meta.cap,
                }}
              />}
            </div>

            {empty ? (
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)', padding: '28px 0' }}>
                {unmatched.length > 0 ? (
                  // The notice above already named the guilty control; repeating
                  // "nothing was created or resolved" here would restate the very
                  // reading that notice exists to prevent.
                  <>Nothing to chart — a filter above matches no issue in this project.</>
                ) : (
                  <>
                    Nothing was created or resolved between{' '}
                    <span className="mono">{data.from}</span> and <span className="mono">{data.to}</span>
                    {' '}with these filters. The window is real and the series is all zeros — widen the
                    range or clear a filter.
                  </>
                )}
              </p>
            ) : (
              // The chart slot is hidden from the accessibility tree as a whole —
              // the chart marks itself too, but the Suspense fallback is not the
              // chart and would otherwise be announced as an empty region. The
              // accessible reading of this report is the table below.
              <div aria-hidden="true" ref={chartRef}>
                <Suspense fallback={<div style={{ height: 300 }} />}>
                  <FlowChart buckets={buckets} interval={data.interval} />
                </Suspense>
              </div>
            )}

            {(partialFirst || partialLast) && (
              <p className="text-sm mt-3" style={{ color: 'var(--color-text-secondary)' }}>
                The {partialFirst && partialLast ? 'first and last buckets are' : partialFirst ? 'first bucket is' : 'last bucket is'}{' '}
                <b>partial</b> — this window doesn’t start and end on {bucketWord}{' '}
                boundaries, so {partialFirst && partialLast ? 'those bars cover' : 'that bar covers'} less
                than a full {bucketWord}. A short end bar is the window, not a drop.
              </p>
            )}
          </ReportCard>

          {/* The table equivalent. Not a fallback: it is how this report is read
              by a screen reader (the chart is aria-hidden), and it is exactly the
              series R7 exports as CSV. */}
          <ReportCard>
            <SeriesTable
              caption={`Flow, ${shownInterval === 'WEEK' ? 'weekly' : 'daily'} buckets — ${data.from} to ${data.to} (UTC)`}
              columns={columns}
              rows={buckets}
              rowKey={b => b.date}
              footer={
                <tfoot>
                  <tr>
                    <th scope="row" style={{ textAlign: 'left', padding: '7px 10px', fontWeight: 700 }}>Total</th>
                    <td style={{ textAlign: 'right', padding: '7px 10px', fontWeight: 700 }}>{data.totals.created.toLocaleString()}</td>
                    <td style={{ textAlign: 'right', padding: '7px 10px', fontWeight: 700 }}>{data.totals.resolved.toLocaleString()}</td>
                    <td style={{ textAlign: 'right', padding: '7px 10px', fontWeight: 700 }}>{signed(data.totals.net)}</td>
                    <td style={{ textAlign: 'right', padding: '7px 10px', color: 'var(--color-text-muted)' }}>—</td>
                  </tr>
                </tfoot>
              }
            />
          </ReportCard>

          {/* ── The footnotes. The reopen hazard is not a detail: it is the one
              way these numbers legitimately change under the reader. ── */}
          <div className="flex flex-col gap-2">
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              <b>Resolved</b> counts issues that are <b>closed now</b>, dated by their latest
              closure. Reopening an issue clears that date, so the issue leaves the bucket it used
              to sit in and joins a new one when it is closed again — past buckets can change, and{' '}
              <b>Open at end</b> follows them. There is no separate record of resolution events yet.
            </p>
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              Buckets are cut on <b>UTC</b> day boundaries; weeks start on Monday. The window
              covers {daysInclusive(data.from, data.to).toLocaleString()}{' '}
              day{daysInclusive(data.from, data.to) === 1 ? '' : 's'}, both ends included.
            </p>
            <MetaLine meta={data.meta} />
          </div>
        </>
      )}

      {isPending && !apiError && (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
      )}
    </div>
  )
}

/**
 * The statuses that have a state of their own above. Anything else — including a
 * failure that never produced a status at all — falls through to the generic
 * sentence, because blank is the one answer this page may never give.
 */
const HANDLED_STATUSES = [400, 404, 429]

/**
 * The name behind a filter id, or the id itself when this browser cannot resolve
 * it — which is not an error state: a since-deleted label is gone from the
 * workspace list and its id is the only handle the reader still has on it.
 */
function filterValue(list: { id: string; name: string }[] | undefined, id: string): ReactNode {
  const hit = list?.find(x => x.id === id)
  return hit ? <>“{hit.name}”</> : <span className="mono">{id}</span>
}

/** The same answer as a plain string — an exported image has no JSX in it. */
function nameOrId(list: { id: string; name: string }[] | undefined, id: string): string {
  return list?.find(x => x.id === id)?.name ?? id
}

/** `+5` / `-3` / `0` — a net figure is only readable when its sign is explicit. */
function signed(n: number): string {
  return n > 0 ? `+${n.toLocaleString()}` : n.toLocaleString()
}

function Total({ label, value, color, signed: isSigned, hint }: {
  label: string
  value: number
  color: string
  signed?: boolean
  hint?: string
}) {
  return (
    <div
      style={{
        background: 'var(--color-card)',
        border: '1px solid var(--color-border)',
        borderLeft: `3px solid ${color}`,
        borderRadius: 'var(--radius-md)',
        boxShadow: 'var(--shadow-card)',
        padding: '10px 16px',
        minWidth: 132,
      }}
    >
      <div style={{ fontSize: 22, fontWeight: 800, letterSpacing: '-0.02em' }}>
        {isSigned ? signed(value) : value.toLocaleString()}
      </div>
      <div style={{ fontSize: 12, color: 'var(--color-text-muted)', fontWeight: 600 }}>
        {label}{hint ? ` · ${hint}` : ''}
      </div>
    </div>
  )
}
