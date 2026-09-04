import { Suspense, lazy, useMemo, useRef, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import {
  ApiResponseError, apiGetProjectConfig, apiListWorkspaceMembers, reportsApi,
} from '../../api'
import type { AgingColumn, AgingItem, CycleMeasure, CycleTimeItem } from '../../types'
import { REPORT_STALE_TIME, reportKey } from '../../lib/queryKeys'
import { Button, Select } from '../../components/ui'
import { useProjectComponents } from '../../components/projectComponents'
import { useWorkspaceLabels } from '../../components/labels'
import {
  CHART_CONTEXT, CHART_SERIES, LegendItem, MetaLine, Notice, RateLimitNotice, ReportCard,
  SeriesTable, TruncationNotice, UnmatchedFilterNotice,
} from './common'
import {
  MEASURE_DEFINITION, MEASURE_LABEL, PERCENTILE_MIN_SAMPLE, formatDays, measureSample,
  measureValue, percentilesFor, readPercentiles,
} from './cycle'
import {
  RANGE_PRESETS, THIN_HISTORY_DAYS, historyDays, presetOf, presetWindow, readCycleState,
  todayIso, writeCycleParams, writeCycleUrl, type CycleState,
} from './window'
import { ChartExport, ReportExportBar, useReportProject } from './export'

// Both charts split out of the page chunk, and only one of them (the scatter)
// carries Recharts. The chrome — controls, the numbers, every disclosure and
// both tables — is interactive before either has parsed, and the tables carry
// the same values, so nothing is unreadable while they load.
const CycleTimeChart = lazy(() => import('./CycleTimeChart'))
const AgingChart = lazy(() => import('./AgingChart'))

/** Beyond this many rows the per-issue table is capped — and says so. */
const TABLE_LIMIT = 200

/** Why this half has no PNG button while the other four charts do (R7). */
const AGING_IMAGE_HINT =
  'Image export serialises a chart’s own SVG. The aging columns are drawn as an HTML layout '
  + 'rather than SVG, so there is nothing to serialise — the CSV above holds every column and row.'

/**
 * **Cycle & lead time** (`/w/:wsId/p/:projectId/reports/cycle-time`) — R3 of the
 * reports epic, and the report that carries the most value in it.
 *
 * One page, two halves, two endpoints:
 *
 *  • **Finished work** — a scatter, one dot per completed issue, x = the day it
 *    closed, y = how long it took, with p50 and p85 across it and the sample
 *    size printed beside them. A dot is a link to its issue.
 *  • **Aging work in progress** — a column per non-DONE status, each a stack of
 *    dots by age with **issue key and assignee** on every one, and the same
 *    p50/p85 lines drawn across. An item above p85 is visibly older than 85% of
 *    everything the team has ever finished. That naming is the entire reason
 *    this epic refuses the cumulative flow diagram, which shades an area and
 *    leaves the reader to guess which item is stuck.
 *
 * **No rolling average, anywhere** (§1.7, §2.2). Smoothing hides the outlier
 * this report exists to point at, and a trend line gets read as a forecast.
 *
 * The disclosures, which are the feature and not decoration around it:
 *
 *  1. **`missingStartCount`** — cycle time exists only for issues with a
 *     recorded start, so the page prints *"cycle time available for 812 of 940
 *     completed issues"*. We never substitute the creation date for a missing
 *     start: that turns cycle time into lead time wearing a false name.
 *  2. **Percentiles below the sample floor are not drawn** — the server sends
 *     nothing under five completed issues and the page says *"Not enough
 *     completed work to compute percentiles (need 5, have 3)"*. Printing noise
 *     is worse than printing nothing.
 *  3. **`meta.truncated`** — the row cap can genuinely bite here (unlike the
 *     flow report, this one is row-level), so it is stated above the chart.
 *  4. **`meta.unmatchedFilters`** — an empty chart must never be ambiguous
 *     between a quiet quarter and a filter that is a typo.
 *  5. **The two halves do not share a window.** `/aging` is the CURRENT state
 *     and takes no parameters at all — not the window, not the filters — so the
 *     page says so rather than letting the reader assume the columns below were
 *     filtered like the chart above. Its percentile lines come with its own
 *     response and do not move when the window changes.
 *  6. **A status outside the effective workflow** arrives in a trailing "Not on
 *     this board" column and is rendered, not dropped (§6). It is recognised by
 *     a `statusId` the project config does not carry — never by its name.
 *
 * State lives in the URL (§4.4), including the measure toggle, which is
 * client-only on the wire: one response carries both durations and both
 * percentile pairs, so flipping it re-renders instead of refetching — but a link
 * that lost it would show the next reader a different report under the same name.
 */
export default function CycleTimeReportPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const today = todayIso()
  const state = readCycleState(searchParams)
  const params = writeCycleParams(state)
  const measure = state.measure

  // Taxonomy from the project config endpoint, never hardcoded and never
  // inferred from the issues that happen to exist. It is also what tells the
  // aging half which column is the stranded one.
  const { data: config } = useQuery({
    queryKey: ['projectConfig', wsId, projectId],
    queryFn: () => apiGetProjectConfig(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const { data: components = [] } = useProjectComponents(wsId, projectId)
  const { data: labels = [] } = useWorkspaceLabels(wsId)
  const { data: members = [] } = useQuery({
    queryKey: ['wsMembers', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId,
    staleTime: 60_000,
  })

  const cycle = useQuery({
    queryKey: reportKey('cycle-time', projectId, params),
    queryFn: () => reportsApi.cycleTime(wsId!, projectId!, params),
    enabled: !!wsId && !!projectId,
    staleTime: REPORT_STALE_TIME,
    // Narrower than the global default — queryClient.ts owns the never-retry-a-422/429
    // rule. This surface is expensive enough to decline a retry for *any* failure.
    retry: false,
  })

  // A separate query with NO parameters — the aging half is the current state,
  // so it neither takes the window nor invalidates when the window moves.
  const aging = useQuery({
    queryKey: reportKey('aging', projectId),
    queryFn: () => reportsApi.aging(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
    staleTime: REPORT_STALE_TIME,
    // Narrower than the global default — queryClient.ts owns the never-retry-a-422/429
    // rule. This surface is expensive enough to decline a retry for *any* failure.
    retry: false,
  })

  // The window ON SCREEN, which on first load is the one the SERVER chose (it
  // derives the default from its own `max-window-days`, so a parameterless
  // request always succeeds) and echoed back.
  const shownFrom = cycle.data?.from ?? state.from
  const shownTo = cycle.data?.to ?? state.to

  /** The single writer of report state; touching any control pins the window. */
  function update(patch: Partial<CycleState>) {
    setSearchParams(
      writeCycleUrl({ ...state, from: shownFrom, to: shownTo, ...patch }),
      { replace: true },
    )
  }

  const preset = presetOf(shownFrom, shownTo, today)
  const items = cycle.data?.items ?? []
  const { p50, p85 } = percentilesFor(cycle.data, measure)
  const sample = cycle.data ? measureSample(cycle.data, measure) : 0
  const plotted = useMemo(
    () => items.filter(i => measureValue(i, measure) != null),
    [items, measure],
  )

  const filtered = !!(state.typeId || state.componentId || state.labelId)

  // Pinned state for the shareable link and the series CSV — including the
  // measure, which is client-only on the wire but is half of what this page IS.
  const pinned = writeCycleUrl({ ...state, from: shownFrom, to: shownTo })
  const csvParams = writeCycleParams({ ...state, from: shownFrom, to: shownTo })
  const { projectKey, projectLabel } = useReportProject(wsId, projectId)
  const scatterRef = useRef<HTMLDivElement>(null)

  const history = historyDays(cycle.data?.meta.firstIssueAt, today)
  const cycleError = cycle.error instanceof ApiResponseError ? cycle.error : null
  const agingError = aging.error instanceof ApiResponseError ? aging.error : null

  /**
   * The active filters in words, for the exported image's subtitle — a picture
   * of a filtered chart that does not say so misleads with numbers that are all
   * true. Names where this browser can resolve them, raw ids where it cannot.
   */
  const filterParts = [
    state.typeId && `type ${nameOrId(config?.issueTypes, state.typeId)}`,
    state.componentId && `component ${nameOrId(components, state.componentId)}`,
    state.labelId && `label ${nameOrId(labels, state.labelId)}`,
  ].filter(Boolean) as string[]
  const filterSummary = filterParts.length ? `Filtered by ${filterParts.join(' · ')}` : ''

  /** A filter parameter in the reader's own vocabulary — see `FlowReportPage`. */
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

  /**
   * The deep link for an issue key. The number is the trailing segment of the
   * key (`PAY-131` → 131), the same derivation the board and the issue drawer
   * already use. **Absolute** — inside the `/reports/*` splat a relative path
   * would resolve after the splat segment.
   */
  function issueHref(key: string): string | null {
    const n = Number(key.slice(key.lastIndexOf('-') + 1))
    return Number.isFinite(n) && n > 0 ? `/w/${wsId}/p/${projectId}/issues/${n}` : null
  }

  function openIssue(key: string) {
    const href = issueHref(key)
    if (href) navigate(href)
  }

  /**
   * A member's display name, or a truthful placeholder.
   *
   * `Unassigned` for a null id; the raw id for somebody this browser cannot
   * resolve (a member who left the workspace) — the same rule the filter
   * disclosure uses, because an id the reader can see is better than a
   * confident-sounding guess.
   */
  function assigneeName(id: string | null): string {
    if (!id) return 'Unassigned'
    return members.find(m => m.userId === id)?.displayName ?? id
  }

  // Columns of the aging half whose status the project config does not carry:
  // the stranded "Not on this board" bucket. Recognised by id, never by name.
  const knownStatusIds = useMemo(
    () => new Set((config?.statuses ?? []).map(s => s.id)),
    [config],
  )
  function isStranded(column: AgingColumn): boolean {
    if (!column.statusId) return true
    // Before the config lands, nothing is called stranded — an unknown id and an
    // unloaded taxonomy look identical, and only one of them is a fact.
    return knownStatusIds.size > 0 && !knownStatusIds.has(column.statusId)
  }
  function columnColor(column: AgingColumn): string | null {
    return (config?.statuses ?? []).find(s => s.id === column.statusId)?.color ?? null
  }

  const agingColumns = aging.data?.columns ?? []
  const agingPercentiles = readPercentiles(aging.data?.percentiles)
  const agingItems = useMemo(
    () => agingColumns
      .flatMap(c => c.items.map(item => ({ item, column: c })))
      .sort((a, b) => b.item.ageDays - a.item.ageDays),
    [agingColumns],
  )
  const strandedColumns = agingColumns.filter(isStranded)

  // The per-issue table is the accessible reading of the scatter, but a row cap
  // of 20 000 makes "every row" a browser-freezing promise. Over the limit it
  // lists the SLOWEST — the ones a reader is looking for — and says exactly
  // that, rather than silently showing a prefix.
  const tableRows = useMemo(() => {
    const rows = [...plotted]
    if (rows.length <= TABLE_LIMIT) {
      return rows.sort((a, b) => Date.parse(a.closedAt) - Date.parse(b.closedAt))
    }
    return rows
      .sort((a, b) => (measureValue(b, measure) ?? 0) - (measureValue(a, measure) ?? 0))
      .slice(0, TABLE_LIMIT)
  }, [plotted, measure])
  const tableCapped = plotted.length > TABLE_LIMIT

  const cycleColumns = useMemo(() => ([
    {
      key: 'closed',
      label: 'Closed (UTC)',
      align: 'left' as const,
      render: (i: CycleTimeItem) => i.closedAt.slice(0, 10),
    },
    {
      key: 'issue',
      label: 'Issue',
      align: 'left' as const,
      render: (i: CycleTimeItem) => <IssueLink issueKey={i.key} href={issueHref(i.key)} />,
    },
    {
      key: 'title',
      label: 'Title',
      align: 'left' as const,
      render: (i: CycleTimeItem) => (
        <span
          className="inline-block truncate align-bottom"
          style={{ maxWidth: 340 }}
          title={i.title}
        >
          {i.title}
        </span>
      ),
    },
    {
      key: 'cycle',
      label: measure === 'CYCLE' ? 'Cycle days (plotted)' : 'Cycle days',
      render: (i: CycleTimeItem) => i.cycleDays == null
        ? <span style={{ color: 'var(--color-text-muted)' }}>—</span>
        : formatDays(i.cycleDays),
    },
    {
      key: 'lead',
      label: measure === 'LEAD' ? 'Lead days (plotted)' : 'Lead days',
      // Defensive on a boxed `Double` the server could in principle send as
      // null: a dash is a fact, a thrown render is a blank page.
      render: (i: CycleTimeItem) => typeof i.leadDays === 'number'
        ? formatDays(i.leadDays)
        : <span style={{ color: 'var(--color-text-muted)' }}>—</span>,
    },
  ]), [measure, wsId, projectId])

  const agingTableColumns = useMemo(() => ([
    {
      key: 'status',
      label: 'Status',
      align: 'left' as const,
      render: (r: { item: AgingItem; column: AgingColumn }) => r.column.name,
    },
    {
      key: 'issue',
      label: 'Issue',
      align: 'left' as const,
      render: (r: { item: AgingItem; column: AgingColumn }) =>
        <IssueLink issueKey={r.item.key} href={issueHref(r.item.key)} />,
    },
    {
      key: 'title',
      label: 'Title',
      align: 'left' as const,
      render: (r: { item: AgingItem; column: AgingColumn }) => (
        <span className="inline-block truncate align-bottom" style={{ maxWidth: 300 }} title={r.item.title}>
          {r.item.title}
        </span>
      ),
    },
    {
      key: 'assignee',
      label: 'Assignee',
      align: 'left' as const,
      render: (r: { item: AgingItem; column: AgingColumn }) => assigneeName(r.item.assigneeId),
    },
    {
      key: 'age',
      label: 'Age (days)',
      render: (r: { item: AgingItem; column: AgingColumn }) => formatDays(r.item.ageDays),
    },
    {
      // Colour is never the only encoding: what the dot says in vermillion, this
      // says in a word — and this is the version a screen reader gets.
      key: 'vs',
      label: 'vs finished work',
      render: (r: { item: AgingItem; column: AgingColumn }) => {
        const { p50: a50, p85: a85 } = agingPercentiles
        if (a85 != null && r.item.ageDays > a85) return 'older than p85'
        if (a50 != null && r.item.ageDays > a50) return 'older than p50'
        if (a50 == null && a85 == null) return '—'
        return 'within p50'
      },
    },
  ]), [agingPercentiles.p50, agingPercentiles.p85, members, wsId, projectId])

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 style={{ fontSize: 20, fontWeight: 800, letterSpacing: '-0.01em', margin: 0 }}>
          Cycle &amp; lead time
        </h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 640 }}>
          How long work takes — and which open item is rotting right now. No averages: the two
          lines are p50 and p85 of what this team has actually finished.
        </p>
      </div>

      {/* ── Controls. Every one writes the URL, so the address bar is always a
          complete description of what is on screen — including the measure,
          which the request itself never carries. ── */}
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
          label="Measure" aria-label="Measure" value={measure}
          onChange={e => update({ measure: e.target.value as CycleMeasure })}
        >
          <option value="CYCLE">Cycle time</option>
          <option value="LEAD">Lead time</option>
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

      {/* Export (R7). TWO series CSVs, because this page is two reports with two
          endpoints and two different scopes — one windowed, one current-state —
          and a single "download the data" button would have to pick one and
          silently drop the other. */}
      <ReportExportBar
        wsId={wsId}
        projectId={projectId}
        projectKey={projectKey}
        shareParams={pinned}
        csv={[
          { kind: 'cycle-time', label: 'Finished work', params: csvParams, slug: 'cycle-time' },
          { kind: 'aging', label: 'Aging WIP', params: {}, slug: 'aging' },
        ]}
        disabled={!cycle.data && !aging.data}
      />

      {/* ── Finished work ───────────────────────────────────────────────────── */}
      <h2 style={{ fontSize: 15, fontWeight: 800, margin: '4px 0 0' }}>Finished work</h2>

      {/* Every 400 through one path — each one names the bound it measured
          against, and that sentence is the reason the server refuses instead of
          clamping. */}
      {cycleError?.status === 400 && (
        <Notice tone="warn">
          <b>That window can’t be reported on.</b>{' '}
          {cycleError.detail}
          <span className="block mt-2">
            <Button size="sm" onClick={() => update({ from: '', to: '' })}>
              Use the default window
            </Button>
          </span>
        </Notice>
      )}
      {cycleError?.status === 404 && (
        <Notice tone="warn">
          Project not found — it may have been deleted, or your access was removed.
        </Notice>
      )}
      {cycleError?.status === 429 && (
        <RateLimitNotice retryAfter={cycleError.retryAfter} onRetry={() => void cycle.refetch()} />
      )}
      {cycle.error && !HANDLED_STATUSES.includes(cycleError?.status ?? 0) && (
        <Notice tone="warn">
          Couldn’t load finished work: {cycleError?.detail ?? 'the request failed. Try again.'}
        </Notice>
      )}

      <UnmatchedFilterNotice meta={cycle.data?.meta} describe={describeFilter} />
      <TruncationNotice meta={cycle.data?.meta} />

      {history !== null && history < THIN_HISTORY_DAYS && (
        <Notice>
          Only {history} day{history === 1 ? '' : 's'} of {filtered ? 'matching ' : ''}history — a
          distribution needs a few weeks. The {filtered ? 'earliest issue these filters match' : 'first issue here'} was
          created {history} day{history === 1 ? '' : 's'} ago, so the window below is real but there is
          not enough finished work in it yet to read as a distribution.
          {filtered && ' That is how far back this chart reaches, not how old the project is.'}
        </Notice>
      )}

      {/* The honesty sentence §2.2 asks for by name. Printed whenever any
          completed issue lacks a start, in BOTH measures — under lead time it is
          the reason the two measures have different sample sizes. */}
      {cycle.data && cycle.data.missingStartCount > 0 && (
        <Notice tone={measure === 'CYCLE' ? 'warn' : 'info'}>
          <b>
            Cycle time available for {(cycle.data.sampleSize - cycle.data.missingStartCount).toLocaleString()} of{' '}
            {cycle.data.sampleSize.toLocaleString()} completed issues.
          </b>{' '}
          The other {cycle.data.missingStartCount.toLocaleString()} have no recorded start, so they have
          no cycle time —{' '}
          {measure === 'CYCLE'
            ? <>they are not plotted below and the percentiles are computed without them.</>
            : <>lead time is defined for every completed issue, so all {cycle.data.sampleSize.toLocaleString()} are plotted here.</>}
          {' '}The creation date is never substituted for a missing start: that would turn cycle time
          into lead time wearing a false name.
        </Notice>
      )}

      {/* Percentiles below the sample floor are not drawn and not guessed. */}
      {cycle.data && (p50 == null || p85 == null) && (
        <Notice>
          <b>
            Not enough completed work to compute percentiles (need {PERCENTILE_MIN_SAMPLE}, have{' '}
            {sample.toLocaleString()}).
          </b>{' '}
          The dots below are real; the p50 and p85 lines are left off rather than drawn from a
          handful of issues, and the aging columns are read without them.
        </Notice>
      )}

      {cycle.data && (
        <>
          <div className="flex flex-wrap gap-3">
            <Stat
              label={`${MEASURE_LABEL[measure]} p50`}
              value={p50 == null ? '—' : `${formatDays(p50)} d`}
              hint="half of finished work was faster"
              color={CHART_CONTEXT}
            />
            <Stat
              label={`${MEASURE_LABEL[measure]} p85`}
              value={p85 == null ? '—' : `${formatDays(p85)} d`}
              hint="85% of finished work was faster"
              color={CHART_CONTEXT}
            />
            <Stat
              label="Sample"
              value={sample.toLocaleString()}
              hint={`issue${sample === 1 ? '' : 's'} with a ${MEASURE_LABEL[measure].toLowerCase()}`}
              color={CHART_SERIES[0]}
            />
          </div>

          <ReportCard>
            <div className="flex flex-wrap items-center gap-4 mb-1">
              <LegendItem color={CHART_SERIES[0]} label={`${MEASURE_LABEL[measure]}, one dot per issue`} />
              {(p50 != null || p85 != null) && (
                <LegendItem color={CHART_CONTEXT} label="p50 / p85 of finished work" dashed />
              )}
              {cycle.isFetching && (
                <span className="mono ml-auto" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                  refreshing…
                </span>
              )}
              {/* Only when there is a chart: an empty window draws none, and a
                  button that answers "not loaded yet" forever is worse than no
                  button at all. */}
              {plotted.length > 0 && <ChartExport
                chartRef={scatterRef}
                title={`${MEASURE_LABEL[measure]} — finished work`}
                subtitle={filterSummary}
                slug={measure === 'CYCLE' ? 'cycle-time' : 'lead-time'}
                projectKey={projectKey}
                provenance={{
                  project: projectLabel,
                  // The measure is IN the window line, not only in the title: the
                  // two measures are different numbers over different sets, and an
                  // image of one filed beside an image of the other is exactly how
                  // a cycle time gets quoted as a lead time.
                  window: `${cycle.data.from} → ${cycle.data.to} (UTC) · ${MEASURE_LABEL[measure].toLowerCase()}`,
                  computedAt: cycle.data.meta.computedAt,
                  basedOnIssues: cycle.data.meta.basedOnIssues,
                  truncated: cycle.data.meta.truncated,
                  cap: cycle.data.meta.cap,
                }}
              />}
            </div>
            {/* The sample size printed beside the lines, as §2.2 words it. */}
            <p className="text-sm mb-3" style={{ color: 'var(--color-text-secondary)', margin: '0 0 12px' }}>
              {p50 != null && p85 != null ? (
                <>
                  <b>p50 {formatDays(p50)} days</b> · <b>p85 {formatDays(p85)} days</b> — based on{' '}
                  {sample.toLocaleString()} issue{sample === 1 ? '' : 's'}, measured{' '}
                  {MEASURE_DEFINITION[measure]}.
                </>
              ) : (
                <>No percentile lines — measured {MEASURE_DEFINITION[measure]}.</>
              )}
            </p>

            {plotted.length === 0 ? (
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)', padding: '28px 0' }}>
                {(cycle.data.meta.unmatchedFilters ?? []).length > 0 ? (
                  <>Nothing to chart — a filter above matches no issue in this project.</>
                ) : cycle.data.sampleSize > 0 && measure === 'CYCLE' ? (
                  <>
                    {cycle.data.sampleSize.toLocaleString()} issue
                    {cycle.data.sampleSize === 1 ? ' was' : 's were'} completed between{' '}
                    <span className="mono">{cycle.data.from}</span> and{' '}
                    <span className="mono">{cycle.data.to}</span>, but none of them has a recorded
                    start, so none has a cycle time. Switch the measure to <b>lead time</b> to see
                    them.
                  </>
                ) : (
                  <>
                    Nothing was completed between <span className="mono">{cycle.data.from}</span> and{' '}
                    <span className="mono">{cycle.data.to}</span> with these filters. The window is
                    real and empty — widen the range or clear a filter.
                  </>
                )}
              </p>
            ) : (
              <div aria-hidden="true" ref={scatterRef}>
                <Suspense fallback={<div style={{ height: 320 }} />}>
                  <CycleTimeChart
                    items={plotted}
                    measure={measure}
                    from={cycle.data.from}
                    to={cycle.data.to}
                    p50={p50}
                    p85={p85}
                    onSelect={item => openIssue(item.key)}
                  />
                </Suspense>
              </div>
            )}
          </ReportCard>

          <ReportCard>
            <SeriesTable
              caption={
                tableCapped
                  ? `Completed issues, ${cycle.data.from} to ${cycle.data.to} (UTC) — the ${TABLE_LIMIT} slowest by ${MEASURE_LABEL[measure].toLowerCase()} of ${plotted.length.toLocaleString()} plotted`
                  : `Completed issues, ${cycle.data.from} to ${cycle.data.to} (UTC) — all ${plotted.length.toLocaleString()} plotted, oldest closure first`
              }
              columns={cycleColumns}
              rows={tableRows}
              rowKey={i => i.issueId}
            />
            {tableCapped && (
              <p className="text-sm mt-3" style={{ color: 'var(--color-text-secondary)' }}>
                The chart plots all {plotted.length.toLocaleString()} of them; this table lists the{' '}
                {TABLE_LIMIT} slowest so the page stays usable. Narrow the window or a filter to see
                every row.
              </p>
            )}
          </ReportCard>

          <div className="flex flex-col gap-2">
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              <b>Cycle time</b> runs from the moment work started on an issue to the moment it
              closed; <b>lead time</b> runs from the moment it was created. An issue with no
              recorded start has no cycle time and is left out of that measure rather than dated
              from its creation. Reopening an issue clears its closure date, so it leaves this
              window until it is closed again.
            </p>
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              The lines are <b>percentiles, not averages</b>: p50 is the middle of finished work and
              p85 is the point 85% of it came in under. There is deliberately no rolling average —
              smoothing hides the outliers this report exists to find.
            </p>
            <MetaLine meta={cycle.data.meta} />
          </div>
        </>
      )}

      {cycle.isPending && !cycleError && (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading finished work…</p>
      )}

      {/* ── Aging work in progress ──────────────────────────────────────────── */}
      <h2 style={{ fontSize: 15, fontWeight: 800, margin: '12px 0 0' }}>Aging work in progress</h2>
      <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 700 }}>
        Everything open right now, by status and by age, oldest at the top — read against p50/p85 of
        the cycle time of everything this project has ever completed. An item above p85 has already
        taken longer than 85% of the work this team has finished.
      </p>

      {agingError?.status === 404 && (
        <Notice tone="warn">
          Project not found — it may have been deleted, or your access was removed.
        </Notice>
      )}
      {agingError?.status === 429 && (
        <RateLimitNotice retryAfter={agingError.retryAfter} onRetry={() => void aging.refetch()} />
      )}
      {aging.error && !HANDLED_STATUSES.includes(agingError?.status ?? 0) && (
        <Notice tone="warn">
          Couldn’t load aging work in progress: {agingError?.detail ?? 'the request failed. Try again.'}
        </Notice>
      )}

      <TruncationNotice meta={aging.data?.meta} />

      {/* The asymmetry between the halves, said out loud rather than left to be
          discovered: this one is the current state and takes no parameters. */}
      {/* The asymmetry between the halves, said out loud rather than left to be
          discovered — and the baseline named exactly, because it is neither the
          window above nor (under lead time) the same measure. */}
      {aging.data && (
        <Notice>
          These columns are <b>the current state of the project</b>: the window and the filters above
          apply to the finished-work chart only, so everything open is shown here
          {filtered ? ', including issues your filters exclude above' : ''}. Their lines are p50/p85 of{' '}
          <b>cycle time across everything this project has ever completed</b> — a fixed baseline, so
          they do not move when you change the window
          {measure === 'LEAD' ? ', and they are not the lead-time lines drawn above' : ''}.
        </Notice>
      )}

      {aging.data && agingPercentiles.p50 == null && agingPercentiles.p85 == null && (
        <Notice>
          <b>No percentile lines on this half.</b> This project has not completed enough work with a
          recorded start to compute them (the floor is {PERCENTILE_MIN_SAMPLE} issues, the same one
          the chart above uses), so ages are shown without a baseline to read them against.
        </Notice>
      )}

      {strandedColumns.length > 0 && (
        <Notice>
          {strandedColumns.map(c => <b key={c.statusId ?? c.name}>“{c.name}”</b>)} holds issues sitting
          in a status this project’s workflow no longer carries. They are shown rather than dropped —
          an issue nobody can see is the one that ages longest — but no board column will surface
          them until they are moved.
        </Notice>
      )}

      {aging.data && (
        <>
          <ReportCard>
            <div className="flex flex-wrap items-center gap-4 mb-3">
              <LegendItem color={CHART_SERIES[0]} label="Open issue, placed at its age" />
              {agingPercentiles.p85 != null && (
                <LegendItem color={CHART_SERIES[1]} label="Older than p85 of completed work" />
              )}
              {(agingPercentiles.p50 != null || agingPercentiles.p85 != null) && (
                <LegendItem color={CHART_CONTEXT} label="p50 / p85 of cycle time, all completed work" dashed />
              )}
              {aging.isFetching && (
                <span className="mono ml-auto" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                  refreshing…
                </span>
              )}
              {/* The one chart in this feature with NO image export, said out
                  loud rather than left as a missing button somebody hunts for.
                  The four SVG charts are serialised and rasterised as they
                  stand; these columns are an HTML layout, and rasterising HTML
                  needs the DOM-to-canvas library this slice deliberately does
                  not add. The CSV above carries every row of it. */}
              <span
                className="text-xs"
                style={{ marginLeft: 'auto', color: 'var(--color-text-muted)' }}
                title={AGING_IMAGE_HINT}
              >
                no image export — use “Aging WIP (CSV)” above
              </span>
            </div>

            {agingItems.length === 0 ? (
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)', padding: '28px 0' }}>
                Nothing is open in this project right now — every issue is in a done status. There is
                no aging work to name.
              </p>
            ) : (
              <div aria-hidden="true">
                <Suspense fallback={<div style={{ height: 340 }} />}>
                  <AgingChart
                    columns={agingColumns}
                    p50={agingPercentiles.p50}
                    p85={agingPercentiles.p85}
                    assigneeName={assigneeName}
                    columnColor={columnColor}
                    onSelect={item => openIssue(item.key)}
                  />
                </Suspense>
              </div>
            )}
          </ReportCard>

          {agingItems.length > 0 && (
            <ReportCard>
              <SeriesTable
                caption={`Open issues by age, oldest first — ${agingItems.length.toLocaleString()} across ${agingColumns.length} status${agingColumns.length === 1 ? '' : 'es'} (UTC)`}
                columns={agingTableColumns}
                rows={agingItems}
                rowKey={r => r.item.issueId}
              />
            </ReportCard>
          )}

          <div className="flex flex-col gap-2">
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              Age runs from the moment work started, or from creation for an issue that was never
              started — so a long-untouched item in a to-do status is aged from the day it was
              filed, which is the honest reading of how long somebody has been waiting for it.
            </p>
            <MetaLine meta={aging.data.meta} />
          </div>
        </>
      )}

      {aging.isPending && !agingError && (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>
          loading aging work in progress…
        </p>
      )}
    </div>
  )
}

/** Statuses with a state of their own; anything else gets the generic sentence. */
const HANDLED_STATUSES = [400, 404, 429]

/**
 * An issue key as a link to the issue — the keyboard and screen-reader path to
 * the thing a dot points at, since the charts themselves are `aria-hidden`.
 * Absolute path: this page lives inside the `/reports/*` splat.
 */
function IssueLink({ issueKey, href }: { issueKey: string; href: string | null }) {
  if (!href) return <span className="mono">{issueKey}</span>
  return (
    <Link to={href} className="mono no-underline" style={{ color: 'var(--color-brand-ink)', fontWeight: 600 }}>
      {issueKey}
    </Link>
  )
}

/** The name behind a filter id, or the id itself when it cannot be resolved. */
function filterValue(list: { id: string; name: string }[] | undefined, id: string): ReactNode {
  const hit = list?.find(x => x.id === id)
  return hit ? <>“{hit.name}”</> : <span className="mono">{id}</span>
}

/** The same answer as a plain string — an exported image has no JSX in it. */
function nameOrId(list: { id: string; name: string }[] | undefined, id: string): string {
  return list?.find(x => x.id === id)?.name ?? id
}

function Stat({ label, value, hint, color }: {
  label: string
  value: string
  hint?: string
  color: string
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
        minWidth: 150,
      }}
    >
      <div style={{ fontSize: 22, fontWeight: 800, letterSpacing: '-0.02em' }}>{value}</div>
      <div style={{ fontSize: 12, color: 'var(--color-text-muted)', fontWeight: 600 }}>
        {label}{hint ? ` · ${hint}` : ''}
      </div>
    </div>
  )
}
