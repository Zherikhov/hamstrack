import { Suspense, lazy, useMemo, type ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ApiResponseError, apiListWorkspaceMembers, reportsApi } from '../../api'
import type { ScopeChange, SprintMeasure } from '../../types'
import { REPORT_STALE_TIME, reportKey } from '../../lib/queryKeys'
import { Select } from '../../components/ui'
import {
  CHART_CONTEXT, CHART_SERIES, LegendItem, MetaLine, Notice, RateLimitNotice, ReportCard,
  SeriesTable, TruncationNotice,
} from './common'
import {
  CURRENT_POINTS_FOOTNOTE, SPRINT_MEASURE_LABEL, SPRINT_MEASURE_UNIT, burnupRows, formatDelta,
  formatMeasure, lastMeasuredDay, readSprintReportState, resolveSprintChoice, writeBurnupParams,
  writeBurnupUrl, type BurnupRow,
} from './sprint'
import {
  EstimationOffHint, NoSprintsCard, ScrumRequiredCard, SprintChoiceNote, SprintHeadline,
  SprintReportPicker, useReportSprints, useSprintReportGate,
} from './sprintCommon'
import { todayIso } from './window'

// Split out of the page chunk: the chrome — picker, measure, every disclosure,
// both tables — is readable and interactive before Recharts has parsed, and the
// table under the chart carries the same numbers, so nothing is unreadable while
// it loads.
const BurnupChart = lazy(() => import('./BurnupChart'))

/**
 * **Sprint burn-up** (`/w/:wsId/p/:projectId/reports/sprint-burnup`) — R4 of the
 * reports epic, and the answer to *"will this sprint land, and what happened to
 * the plan?"*
 *
 * Two measured lines and a guide (§2.3):
 *
 *  • **Scope**, which steps up on an add and down on a remove — **and every step
 *    is hoverable, naming the issue and who moved it.** That is the whole reason
 *    this is a burn-up: a burndown folds "we finished work" and "somebody added
 *    work" into one falling line, which is the documented disagreement §1.2
 *    catalogues, and no tooltip can un-fold it after the fact.
 *  • **Completed**, cumulative.
 *  • A faint **ideal** guide to the scope COMMITTED at the start, never to
 *    current scope — widening a sprint must not quietly move the bar it is
 *    measured against.
 *
 * Below the chart, the **scope-change log**: every add and remove with its
 * timestamp, issue, actor and delta. The chart shows that scope moved; the log
 * is where a team finds out why.
 *
 * Rules this page may not break:
 *
 *  1. **No projection.** The lines stop at today and nothing continues them —
 *     no trend, no "at this rate". Forecasting is R5 and ships with a stated
 *     sample size (§2.3 rule 2).
 *  2. **Scope change is membership change.** A re-estimate is not a step and
 *     never appears in the log.
 *  3. **Points are the issue's CURRENT estimate**, so a re-estimate moves the
 *     whole scope line, its past included. That is a deliberate, confirmed
 *     decision and it is FOOTNOTED here rather than buried — the review record
 *     next door deliberately does the opposite and reads the ledger's snapshot.
 *  4. **Capabilities gate this UI and nothing else.** `board = KANBAN` replaces
 *     the report with the Rule C card; `estimation = false` hides the measure
 *     toggle but not a single recorded point (Rule B). Neither changes what the
 *     API accepts or answers (Rule A), and neither is EVER decided by whether
 *     sprints exist in the data.
 *
 * State lives in the URL — the sprint and the measure (§4.4).
 */
export default function SprintBurnupPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()

  const state = readSprintReportState(searchParams)
  const gate = useSprintReportGate(wsId, projectId)

  // With estimation off the toggle is not offered and the report is counts —
  // including when a shared link asks for points, which is said out loud below
  // rather than silently honoured or silently ignored.
  const measure: SprintMeasure = gate.estimation ? state.measure : 'COUNT'

  const sprintQuery = useReportSprints(wsId, projectId)
  const sprints = sprintQuery.data?.content ?? []
  const choice = resolveSprintChoice(state.sprintId, sprints)
  const sprintId = state.sprintId || choice.sprint?.id || ''

  const { data: members = [] } = useQuery({
    queryKey: ['wsMembers', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId,
    staleTime: 60_000,
  })

  const params = writeBurnupParams({ sprintId, measure })
  const burnup = useQuery({
    queryKey: reportKey('sprint-burnup', projectId, params),
    queryFn: () => reportsApi.sprintBurnup(wsId!, projectId!, { sprintId, measure }),
    // Not fired while the project's capabilities are unknown or say the report
    // is not offered here. This is NOT a capability gating the API — the
    // endpoint answers a Kanban project exactly as it answers a Scrum one
    // (Rule A) — it is simply not spending a request on numbers this page has
    // already decided not to draw.
    enabled: !!wsId && !!projectId && !!sprintId && gate.iterations,
    staleTime: REPORT_STALE_TIME,
    retry: false,
  })

  function update(patch: Partial<{ sprintId: string; measure: SprintMeasure }>) {
    setSearchParams(writeBurnupUrl({ sprintId, measure, ...patch }), { replace: true })
  }

  const report = burnup.data
  const rows = useMemo(
    () => (report ? burnupRows(report, todayIso()) : []),
    [report],
  )
  const last = lastMeasuredDay(rows)
  const changes = report?.scopeChanges ?? []
  const added = changes.filter(c => c.event === 'ADDED').length
  const removed = changes.filter(c => c.event === 'REMOVED').length
  const error = burnup.error instanceof ApiResponseError ? burnup.error : null

  function issueHref(key: string): string | null {
    const n = Number(key.slice(key.lastIndexOf('-') + 1))
    return Number.isFinite(n) && n > 0 ? `/w/${wsId}/p/${projectId}/issues/${n}` : null
  }

  /**
   * A member's display name; the raw id for somebody this browser cannot resolve.
   *
   * A **null actor is ordinary, not an error**, and it happens for two reasons:
   * the account was deleted (the event outlives the user who caused it), or the
   * ISSUE was deleted, on whose rows attribution is dropped deliberately —
   * `issue_history` cascades away with an issue while the ledger row survives,
   * so keeping the actor would leave this log as the only place in the product
   * still naming who touched a since-deleted issue.
   */
  function actorName(id: string | null): string {
    if (!id) return 'not recorded'
    return members.find(m => m.userId === id)?.displayName ?? id
  }

  const seriesColumns = useMemo(() => ([
    { key: 'date', label: 'Day (UTC)', align: 'left' as const, render: (r: BurnupRow) => r.date },
    {
      key: 'scope',
      label: `Scope (${SPRINT_MEASURE_UNIT[measure]})`,
      render: (r: BurnupRow) => r.scope == null
        ? <span style={{ color: 'var(--color-text-muted)' }}>—</span>
        : formatMeasure(r.scope, measure),
    },
    {
      key: 'completed',
      label: `Completed (${SPRINT_MEASURE_UNIT[measure]})`,
      render: (r: BurnupRow) => r.completed == null
        ? <span style={{ color: 'var(--color-text-muted)' }}>—</span>
        : formatMeasure(r.completed, measure),
    },
    { key: 'ideal', label: 'Ideal (guide)', render: (r: BurnupRow) => formatMeasure(r.ideal, measure) },
    {
      key: 'changes',
      label: 'Scope changes',
      render: (r: BurnupRow) => r.changes.length === 0
        ? <span style={{ color: 'var(--color-text-muted)' }}>—</span>
        : r.changes.length,
    },
  ]), [measure])

  const logColumns = useMemo(() => ([
    {
      key: 'at',
      label: 'When (UTC)',
      align: 'left' as const,
      render: (c: ScopeChange) => c.at.replace('T', ' ').slice(0, 16),
    },
    {
      key: 'issue',
      label: 'Issue',
      align: 'left' as const,
      render: (c: ScopeChange) => <ChangeIssue change={c} href={c.issueId ? issueHref(c.key) : null} />,
    },
    {
      key: 'event',
      label: 'Change',
      align: 'left' as const,
      render: (c: ScopeChange) => c.event === 'ADDED' ? 'Added to sprint' : 'Removed from sprint',
    },
    { key: 'delta', label: `Delta (${SPRINT_MEASURE_UNIT[measure]})`, render: (c: ScopeChange) => formatDelta(c.delta, measure) },
    {
      // Rule B (§5.2), and the reason this column is unconditional: the value is
      // the ledger's own snapshot, not a re-reading of `delta`, so a project
      // charted in COUNT — which is every project with `estimation` off — still
      // sees the estimates it had already recorded. A dash here means the issue
      // entered unestimated, which is a fact about that row and not a gap.
      key: 'points',
      label: 'Points at the time',
      render: (c: ScopeChange) => typeof c.storyPoints === 'number'
        ? formatMeasure(c.storyPoints, 'POINTS')
        : <span style={{ color: 'var(--color-text-muted)' }} title="Unestimated when it moved">—</span>,
    },
    { key: 'actor', label: 'Moved by', align: 'left' as const, render: (c: ScopeChange) => actorName(c.actorId) },
  ]), [measure, members, wsId, projectId])

  // ── Capability gate. Read from the DECLARED capability, never from the data ──
  if (!gate.ready) {
    return <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
  }
  if (!gate.iterations) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeading />
        <ScrumRequiredCard wsId={wsId} projectId={projectId} canEdit={gate.canEdit} report="burn-up" />
      </div>
    )
  }
  if (!sprintQuery.isPending && sprints.length === 0 && !state.sprintId) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeading />
        <NoSprintsCard wsId={wsId} projectId={projectId} canCreate={gate.canEdit} />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeading />

      <div className="flex flex-wrap items-end gap-2">
        <SprintReportPicker
          sprints={sprints}
          value={sprintId}
          onChange={id => update({ sprintId: id })}
        />

        {gate.estimation ? (
          <Select
            label="Measure" aria-label="Measure" value={measure}
            onChange={e => update({ measure: e.target.value as SprintMeasure })}
          >
            <option value="COUNT">Issue count</option>
            <option value="POINTS">Story points</option>
          </Select>
        ) : (
          <EstimationOffHint wsId={wsId} projectId={projectId} canEdit={gate.canEdit} />
        )}
      </div>

      <SprintChoiceNote choice={choice} sprintId={state.sprintId} />

      {!gate.estimation && state.measure === 'POINTS' && (
        <Notice>
          This link asks for <b>story points</b>, and this project does not estimate — so the chart
          below counts issues instead. Nothing is hidden by that: points this project already
          recorded are still listed in the scope-change log.
        </Notice>
      )}

      {error?.status === 404 && (
        <Notice tone="warn">
          <b>That sprint is no longer here.</b> It may have been deleted, or it belongs to another
          project — pick one from the list above to see a report.
        </Notice>
      )}
      {error?.status === 429 && (
        <RateLimitNotice retryAfter={error.retryAfter} onRetry={() => void burnup.refetch()} />
      )}
      {burnup.error && !HANDLED_STATUSES.includes(error?.status ?? 0) && (
        <Notice tone="warn">
          Couldn’t load the burn-up: {error?.detail ?? 'the request failed. Try again.'}
        </Notice>
      )}

      {/* Two DIFFERENT limits, and each says its own sentence. `meta.truncated`
          is the 20 000-row cap; `seriesTruncatedAt` is the day bound, which is
          what a backdated sprint hits — quoting the row cap at a twelve-issue
          sprint is the "these numbers don't match" failure the meta convention
          exists to prevent. */}
      <TruncationNotice meta={report?.meta} />

      {report?.seriesTruncatedAt && (
        <Notice tone="warn">
          <b>
            This sprint is longer than this instance charts, so the lines stop on{' '}
            <span className="mono">{report.seriesTruncatedAt}</span>.
          </b>{' '}
          The days kept are the <b>first</b> ones, because they carry the commitment every later
          number is read against. The scope-change log below stops at the same day, so the chart,
          the log and the unestimated count all describe one window — but the{' '}
          <b>sprint review</b> has no such bound and legitimately covers the whole sprint.
        </Notice>
      )}

      {/* Documented failure mode #4 of the burndown (§1.2): an unestimated issue
          counts as zero in a points series, and a points series that does not
          say so is a chart of a smaller sprint than the one that ran. */}
      {report && measure === 'POINTS' && report.unestimatedCount > 0 && (
        <Notice tone="warn">
          <b>
            {report.unestimatedCount.toLocaleString()} issue
            {report.unestimatedCount === 1 ? ' in this sprint has' : 's in this sprint have'} no
            estimate.
          </b>{' '}
          They count as zero points in the lines below, so the scope line is lower than the work
          actually in the sprint. Switch the measure to <b>issue count</b> to see all of it.
        </Notice>
      )}

      {report && report.sprint && (
        <>
          <SprintHeadline
            sprint={{
              name: report.sprint.name,
              state: report.sprint.state,
              startAt: report.startAt,
              endAt: report.endAt,
            }}
          />

          <div className="flex flex-wrap gap-3">
            <Stat
              label="Committed at start"
              value={formatMeasure(report.committedAtStart, measure)}
              hint={`what the ideal guide is drawn to, in ${SPRINT_MEASURE_UNIT[measure]}`}
              color={CHART_CONTEXT}
            />
            <Stat
              label={last ? `Scope on ${last.date}` : 'Scope'}
              value={formatMeasure(last?.scope, measure)}
              hint="total work in the sprint"
              color={CHART_SERIES[0]}
            />
            <Stat
              label={last ? `Completed by ${last.date}` : 'Completed'}
              value={formatMeasure(last?.completed, measure)}
              hint="closed, cumulative"
              color={CHART_SERIES[2]}
            />
            <Stat
              label="Scope changes"
              value={changes.length.toLocaleString()}
              hint={`${added} added · ${removed} removed`}
              color={CHART_SERIES[1]}
            />
          </div>

          <ReportCard>
            <div className="flex flex-wrap items-center gap-4 mb-3">
              <LegendItem color={CHART_SERIES[0]} label={`Scope (${SPRINT_MEASURE_LABEL[measure].toLowerCase()})`} />
              <LegendItem color={CHART_SERIES[2]} label="Completed, cumulative" />
              <LegendItem color={CHART_CONTEXT} label="Ideal — to the scope committed at the start" dashed />
              {burnup.isFetching && (
                <span className="mono ml-auto" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                  refreshing…
                </span>
              )}
            </div>

            {rows.length === 0 ? (
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)', padding: '28px 0' }}>
                <b>{report.sprint.name} has no history to draw yet.</b>{' '}
                {report.sprint.state === 'FUTURE'
                  ? 'It has not been started, so nothing has entered or left it and nothing has been closed in it.'
                  : 'No day of this sprint carries a scope or a completed figure.'}
              </p>
            ) : (
              <Suspense fallback={<div style={{ height: 320 }} />}>
                <BurnupChart rows={rows} measure={measure} actorName={actorName} />
              </Suspense>
            )}
          </ReportCard>

          {rows.length > 0 && (
            <ReportCard>
              <SeriesTable
                caption={`${report.sprint.name} day by day (UTC) — scope, completed and the ideal guide, in ${SPRINT_MEASURE_UNIT[measure]}`}
                columns={seriesColumns}
                rows={rows}
                rowKey={r => r.date}
              />
              <p className="text-sm mt-3" style={{ color: 'var(--color-text-secondary)', maxWidth: 760 }}>
                A dash is a day that has not happened yet. <b>The lines end where they end</b> — there
                is no projection here and no trend line: at this point in a sprint any such line
                would be a forecast with no sample size behind it.
              </p>
            </ReportCard>
          )}

          {/* ── The scope-change log ─────────────────────────────────────── */}
          <h2 style={{ fontSize: 15, fontWeight: 800, margin: '8px 0 0' }}>Scope changes</h2>
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
            Every issue that joined or left this sprint, with who moved it and when.{' '}
            <b>A re-estimate is not a scope change</b> and never appears here: scope moves when
            membership moves, which is exactly what a burndown’s single falling line cannot tell you.
            “Moved by” reads <b>not recorded</b> when the account has since been deleted, and on the
            rows of a deleted issue, where naming a person would outlive the issue they touched.
          </p>

          {changes.length === 0 ? (
            <ReportCard>
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0 }}>
                Nothing entered or left {report.sprint.name} after it started — the sprint ran with
                the scope it was committed to.
              </p>
            </ReportCard>
          ) : (
            <ReportCard>
              <SeriesTable
                caption={`${changes.length.toLocaleString()} scope change${changes.length === 1 ? '' : 's'} — ${added} added, ${removed} removed`}
                columns={logColumns}
                rows={changes}
                rowKey={(c, i) => `${c.key}-${c.at}-${i}`}
              />
            </ReportCard>
          )}

          <div className="flex flex-col gap-2">
            {measure === 'POINTS' && (
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
                {CURRENT_POINTS_FOOTNOTE}
              </p>
            )}
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              The ideal line is drawn to the scope this sprint <b>committed to at its start</b>, not
              to its scope now — so adding work does not move the line the sprint is read against.
              It is a guide to where the team said it would be, not a verdict on where it is.
            </p>
            <MetaLine meta={report.meta} />
          </div>
        </>
      )}

      {burnup.isPending && !error && !!sprintId && (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading the burn-up…</p>
      )}
    </div>
  )
}

const HANDLED_STATUSES = [400, 404, 429]

function PageHeading() {
  return (
    <div>
      <h1 style={{ fontSize: 20, fontWeight: 800, letterSpacing: '-0.01em', margin: 0 }}>
        Sprint burn-up
      </h1>
      <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 660 }}>
        What was in the sprint, and what got finished — as two separate lines, so “we shipped less”
        and “more work arrived” never look like the same thing.
      </p>
    </div>
  )
}

/**
 * The issue behind a scope change. A change whose issue has since been deleted
 * still names it — the ledger keeps the key — but does not pretend to link to
 * it: the row is a record, and a link that 404s is worse than no link.
 */
function ChangeIssue({ change, href }: { change: ScopeChange; href: string | null }): ReactNode {
  if (href) {
    return (
      <Link to={href} className="mono no-underline" style={{ color: 'var(--color-brand)', fontWeight: 600 }}>
        {change.key}
      </Link>
    )
  }
  return (
    <span className="mono" title={change.issueId ? undefined : 'This issue has since been deleted'}>
      {change.key}
      {!change.issueId && (
        <span className="ml-1" style={{ color: 'var(--color-text-muted)', fontWeight: 500 }}>(deleted)</span>
      )}
    </span>
  )
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
