import { Suspense, lazy, useMemo } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ApiResponseError, reportsApi } from '../../api'
import type { SprintMeasure } from '../../types'
import { REPORT_STALE_TIME, reportKey } from '../../lib/queryKeys'
import { Select } from '../../components/ui'
import {
  CHART_CONTEXT, CHART_SERIES, LegendItem, MetaLine, Notice, RateLimitNotice, ReportCard,
  SeriesTable, TruncationNotice,
} from './common'
import { SPRINT_MEASURE_UNIT, formatMeasure } from './sprint'
import { EstimationOffHint, ScrumRequiredCard, useSprintReportGate } from './sprintCommon'
import {
  VELOCITY_CAPTION, VELOCITY_MAX_SPRINTS, VELOCITY_MIN_SAMPLE, readVelocityState,
  unestimatedSummary, velocityBand, velocityRows, velocitySentence, writeVelocityParams,
  type VelocityRow,
} from './velocity'

// Split out of the page chunk, as on the burn-up: the sentence this report
// exists for, the controls and the table are readable before Recharts has
// parsed — and the sentence is the deliverable, so it may not wait on a chart.
const VelocityChart = lazy(() => import('./VelocityChart'))

/**
 * **Velocity** (`/w/:wsId/p/:projectId/reports/velocity`) — R5 of the reports
 * epic, and the one report in it that answers a question about the FUTURE:
 * *how much should we plan for next sprint?*
 *
 * It ships as **a forecast band, never a scoreboard** (§2.5), because that is
 * what the research (§1.4) leaves standing. Velocity *"was never intended to be
 * used to compare two teams"*; when it escapes the team, *"leaders misinterpret
 * higher story point averages to mean one team is more productive"*, which
 * *"harms their estimating process, creates inflated estimates, and demoralizes
 * the team"*. The redesign keeps the number and removes every affordance that
 * turns it into a ranking:
 *
 *  1. **No per-person breakdown.** Not a filter, not a tooltip, not a hover, not
 *     a legend. Nothing on this page is attributable to an individual, and this
 *     page fetches no member list at all — the cheapest way to keep a rule is to
 *     make breaking it require new code rather than a new prop.
 *  2. **No cross-project or workspace-level view.** One project, and nothing
 *     that aggregates above it. Comparing two teams has to be done by hand and
 *     **that friction is intended**, so there is deliberately no "compare with"
 *     control to add here later.
 *  3. **A permanent caption** — {@link VELOCITY_CAPTION} — always on screen,
 *     never behind a tooltip or an info icon.
 *  4. **Fewer than three completed sprints and the band is withheld**, with the
 *     sample size stated. The bars are still drawn: the record is a fact, the
 *     forecast is not, and the difference is said out loud rather than smoothed
 *     over with a thinner line.
 *
 * The bars are context; **the sentence is the deliverable**. If a layout
 * decision has to give, the sentence stays.
 *
 * Capabilities gate this UI and nothing else (Rule A). `board = KANBAN` replaces
 * the report with the Rule C affordance that turns Scrum on; `estimation = false`
 * withholds the points toggle and charts counts, with its own affordance beside
 * where the toggle would be. Neither is EVER decided by whether sprints exist in
 * the data — that inference is the bug the capability model was built to delete,
 * and it shipped once already.
 *
 * State lives in the URL: the sprint count and the measure (§4.4).
 */
export default function VelocityPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()

  const state = readVelocityState(searchParams)
  const gate = useSprintReportGate(wsId, projectId)

  // With estimation off the toggle is not offered and the report is counts —
  // including when a shared link asks for points, which is said out loud below
  // rather than silently honoured or silently dropped.
  const measure: SprintMeasure = gate.estimation ? state.measure : 'COUNT'
  const params = writeVelocityParams({ sprints: state.sprints, measure })

  const velocity = useQuery({
    queryKey: reportKey('velocity', projectId, params),
    queryFn: () => reportsApi.velocity(wsId!, projectId!, { sprints: state.sprints, measure }),
    // Not fired while the project's capabilities are unknown or say this report
    // is not offered here. That is NOT a capability gating the API — the
    // endpoint answers a Kanban project exactly as it answers a Scrum one — it
    // is simply not spending a request on numbers this page has decided not to
    // draw.
    enabled: !!wsId && !!projectId && gate.iterations,
    staleTime: REPORT_STALE_TIME,
    retry: false,
  })

  function update(patch: Partial<{ sprints: number; measure: SprintMeasure }>) {
    setSearchParams(
      writeVelocityParams({ sprints: state.sprints, measure, ...patch }),
      { replace: true },
    )
  }

  const report = velocity.data
  const rows = useMemo(() => velocityRows(report), [report])
  const band = useMemo(() => velocityBand(report, rows), [report, rows])
  const unsized = useMemo(() => unestimatedSummary(rows), [rows])
  const error = velocity.error instanceof ApiResponseError ? velocity.error : null

  const columns = useMemo(() => ([
    { key: 'name', label: 'Sprint', align: 'left' as const, render: (r: VelocityRow) => r.name },
    {
      // The chronology, in the accessible reading. The bars are ordered oldest
      // first by the server and this is the column that says so — two sprints
      // sharing a name are told apart here rather than guessed at.
      key: 'completedAt',
      label: 'Completed (UTC)',
      align: 'left' as const,
      render: (r: VelocityRow) => r.completedDay
        ?? <span style={{ color: 'var(--color-text-muted)' }}>—</span>,
    },
    {
      key: 'committed',
      label: `Committed (${SPRINT_MEASURE_UNIT[measure]})`,
      render: (r: VelocityRow) => formatMeasure(r.committed, measure),
    },
    {
      key: 'completed',
      label: `Completed (${SPRINT_MEASURE_UNIT[measure]})`,
      render: (r: VelocityRow) => formatMeasure(r.completed, measure),
    },
    {
      key: 'added',
      label: 'Added after start',
      render: (r: VelocityRow) => formatMeasure(r.addedAfterStart, measure),
    },
    {
      key: 'carried',
      label: 'Carried over',
      render: (r: VelocityRow) => formatMeasure(r.carriedOver, measure),
    },
    {
      // Labelled in ISSUES whatever the measure, because that is what it counts:
      // a point sum of unestimated work is zero by definition, so printing it in
      // the selected unit would be a column of noughts saying nothing.
      key: 'unestimated',
      label: 'Unestimated (issues)',
      render: (r: VelocityRow) => r.unestimatedCount === 0
        ? <span style={{ color: 'var(--color-text-muted)' }}>—</span>
        : r.unestimatedCount.toLocaleString(),
    },
  ]), [measure])

  // ── Capability gate. Read from the DECLARED capability, never from the data ──
  if (!gate.ready) {
    return <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
  }
  if (!gate.iterations) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeading />
        <ScrumRequiredCard wsId={wsId} projectId={projectId} canEdit={gate.canEdit} report="velocity" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeading />

      <div className="flex flex-wrap items-end gap-2">
        <Select
          label="Sprints" aria-label="Sprints" value={String(state.sprints)}
          onChange={e => update({ sprints: Number(e.target.value) })}
        >
          {SPRINT_COUNTS.map(n => (
            <option key={n} value={String(n)}>
              {n === 1 ? 'Last 1 sprint' : `Last ${n} sprints`}
            </option>
          ))}
        </Select>

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

      {/* A clamp is only acceptable when it is stated. The alternative — sending
          the number through and rendering the endpoint's 400 — replaces a chart
          and a sentence with an error for a link somebody pasted in good faith. */}
      {state.clampedFrom !== null && (
        <Notice>
          This link asks for <b>{state.clampedFrom.toLocaleString()}</b> sprints, and this report
          charts between 1 and <b>{VELOCITY_MAX_SPRINTS}</b>. It is showing{' '}
          <b>{state.sprints.toLocaleString()}</b>. The cap is deliberate: a forecast built from a
          year of sprints describes a team that no longer exists.
        </Notice>
      )}

      {!gate.estimation && state.measure === 'POINTS' && (
        <Notice>
          This link asks for <b>story points</b>, and this project does not estimate — so the bars
          below count issues instead. Nothing recorded is hidden by that; only the toggle is.
        </Notice>
      )}

      {error?.status === 400 && (
        <Notice tone="warn">
          <b>That request was refused.</b> {error.detail ?? `This report charts at most ${VELOCITY_MAX_SPRINTS} sprints.`}
        </Notice>
      )}
      {error?.status === 429 && (
        <RateLimitNotice retryAfter={error.retryAfter} onRetry={() => void velocity.refetch()} />
      )}
      {velocity.error && !HANDLED_STATUSES.includes(error?.status ?? 0) && (
        <Notice tone="warn">
          Couldn’t load velocity: {error?.detail ?? 'the request failed. Try again.'}
        </Notice>
      )}

      <TruncationNotice meta={report?.meta} />

      {/* Documented failure mode #4 (§1.2, §6), and it bites harder here than on
          the burn-up: the band is computed FROM these bars, so under POINTS the
          forecast inherits every silent zero. A forecast quietly reading low, in
          the report whose only purpose is the forecast, is the thing this notice
          exists to prevent. */}
      {report && rows.length > 0 && unsized.issues > 0 && (
        <Notice tone={measure === 'POINTS' ? 'warn' : 'info'}>
          <b>
            {unsized.issues.toLocaleString()} issue{unsized.issues === 1 ? '' : 's'} across{' '}
            {unsized.sprints.toLocaleString()} of these {rows.length.toLocaleString()} sprints
            {unsized.issues === 1 ? ' has' : ' have'} no estimate.
          </b>{' '}
          {measure === 'POINTS' ? (
            <>
              They weigh <b>zero points</b> in the bars below, and the forecast is computed from
              those bars — so <b>p50 and p85 are biased low</b> by exactly the work nobody sized,
              and planning against them plans for less than this team has been delivering. Switch
              the measure to <b>issue count</b> to see all of it, or estimate the outstanding work
              and reload.
            </>
          ) : (
            <>
              Counting issues, every one of them is in the bars below and nothing here is
              understated. The figure is shown anyway because it is what a <b>story points</b> view
              of these same sprints would silently drop — the bars and the band would both read low
              by that much.
            </>
          )}
        </Notice>
      )}

      {report && rows.length === 0 && (
        <>
          <NoCompletedSprintsCard />
          <MetaLine meta={report.meta} />
        </>
      )}

      {/* The permanent caption (§2.5). Always visible, never a tooltip, never
          behind an info icon — and printed whatever the measure, because it is a
          statement about what a velocity number IS rather than a footnote about
          the toggle that happens to be selected. */}
      {report && (
        <p
          className="text-sm"
          style={{ color: 'var(--color-text-secondary)', margin: 0, fontWeight: 600, maxWidth: 760 }}
        >
          {VELOCITY_CAPTION}
        </p>
      )}

      {report && rows.length > 0 && (
        <>
          {/* The band FIRST — it is what the report is for, and it is read before
              the picture rather than deduced from it. It sits beside the chart on
              a wide screen and above it on a narrow one; either way it is never
              the thing that scrolls off. */}
          <div className="flex flex-wrap gap-4 items-stretch">
            <div style={{ flex: '1 1 320px', minWidth: 300 }}>
              <BandCard band={band} measure={measure} />
            </div>

            <div style={{ flex: '2 1 440px', minWidth: 320 }}>
              <ReportCard>
                <div className="flex flex-wrap items-center gap-4 mb-3">
                  <LegendItem
                    color={CHART_SERIES[0]}
                    label={`Completed (${SPRINT_MEASURE_UNIT[measure]})`}
                  />
                  <LegendItem color={CHART_SERIES[1]} label="Committed — marked on the bar" />
                  {band.kind === 'BAND' && (
                    <LegendItem color={CHART_CONTEXT} label="p50 / p85 forecast" dashed />
                  )}
                  {velocity.isFetching && (
                    <span className="mono ml-auto" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                      refreshing…
                    </span>
                  )}
                </div>
                <Suspense fallback={<div style={{ height: 280 }} />}>
                  <VelocityChart rows={rows} measure={measure} band={band} />
                </Suspense>
              </ReportCard>
            </div>
          </div>

          <ReportCard>
            <SeriesTable
              caption={
                `The last ${rows.length.toLocaleString()} completed sprint${rows.length === 1 ? '' : 's'} — `
                + `committed and completed, in ${SPRINT_MEASURE_UNIT[measure]}`
              }
              columns={columns}
              rows={rows}
              rowKey={r => r.key}
            />
          </ReportCard>

          <div className="flex flex-col gap-2">
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              <b>There is no per-person breakdown here, and there is no view above this project.</b>{' '}
              Both are deliberate. A team’s velocity is a property of that team’s estimating habits,
              so comparing two of them measures the habits and not the work — and comparing two
              people that way is the same mistake one level down. Comparing projects is possible by
              opening them one at a time, which is the intended amount of effort.
            </p>
            <MetaLine meta={report.meta} />
          </div>
        </>
      )}

      {velocity.isPending && !error && (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading velocity…</p>
      )}
    </div>
  )
}

/** The two statuses this page renders a sentence of its own for. */
const HANDLED_STATUSES = [400, 429]

/** Every count the endpoint accepts, so a URL can never disagree with the control. */
const SPRINT_COUNTS = Array.from({ length: VELOCITY_MAX_SPRINTS }, (_, i) => i + 1)

function PageHeading() {
  return (
    <div>
      <h1 style={{ fontSize: 20, fontWeight: 800, letterSpacing: '-0.01em', margin: 0 }}>
        Velocity
      </h1>
      <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 660 }}>
        How much recent sprints actually delivered, and what that suggests you can plan for — a
        forecast for this team, never a score to compare it against another.
      </p>
    </div>
  )
}

/**
 * The band — **the deliverable of this whole report** — or the stated refusal to
 * draw one.
 *
 * The refusal is not a degraded state: it carries the sample size, which is the
 * fact a reader needs in order to know why there is no number and what would
 * change it. A pale band over two sprints would be read as a forecast by
 * everybody who did not notice it was pale.
 */
function BandCard({ band, measure }: {
  band: ReturnType<typeof velocityBand>
  measure: SprintMeasure
}) {
  const sentence = velocitySentence(band, measure)
  return (
    <ReportCard className="h-full">
      <h2 style={{ fontSize: 15, fontWeight: 800, margin: 0 }}>
        {band.kind === 'BAND' ? 'How much to plan for' : 'Not enough history to forecast'}
      </h2>
      <p
        className="text-sm"
        style={{ color: 'var(--color-text)', margin: '10px 0 0', maxWidth: 420, lineHeight: 1.55 }}
      >
        {sentence}
      </p>

      {band.kind === 'BAND' ? (
        <>
          <div className="flex flex-wrap gap-4" style={{ margin: '14px 0 0' }}>
            <Figure label="p50 — plan for" value={formatMeasure(band.p50, measure)} />
            <Figure label="p85 — a stretch" value={formatMeasure(band.p85, measure)} />
          </div>
          <p className="text-sm" style={{ color: 'var(--color-text-muted)', margin: '12px 0 0', maxWidth: 420 }}>
            Half of these sprints landed at or below the p50, and all but the top sixth at or below
            the p85. It is a range to plan inside, not a target to hit: a team that treats the p85
            as the commitment has turned a forecast back into a quota.
          </p>
        </>
      ) : (
        <p className="text-sm" style={{ color: 'var(--color-text-muted)', margin: '12px 0 0', maxWidth: 420 }}>
          The bars beside this are the record and they are accurate. Only the forecast is withheld —
          percentiles over fewer than {VELOCITY_MIN_SAMPLE} sprints describe the last sprint, not
          the next one.
        </p>
      )}
    </ReportCard>
  )
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ fontSize: 26, fontWeight: 800, letterSpacing: '-0.02em' }}>{value}</div>
      <div style={{ fontSize: 12, color: 'var(--color-text-muted)', fontWeight: 600 }}>{label}</div>
    </div>
  )
}

/**
 * A Scrum project that has never finished a sprint.
 *
 * A fact about the DATA, and deliberately not the same card as "this project
 * doesn't run sprints": conflating the two is how "no sprints exist ⇒ this must
 * be Kanban" got written in the first place. There is also nothing to offer
 * here beyond finishing a sprint, so this card does not invent a call to action
 * it cannot honour.
 */
function NoCompletedSprintsCard() {
  return (
    <ReportCard>
      <h2 style={{ fontSize: 15, fontWeight: 800, margin: 0 }}>No sprint has finished here yet</h2>
      <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: '8px 0 0', maxWidth: 640 }}>
        Velocity is measured from <b>completed</b> sprints, so a sprint that is running or planned
        contributes nothing to it yet — its work has not been settled. The first bar appears when a
        sprint is completed, and the forecast band once {VELOCITY_MIN_SAMPLE} have been.
      </p>
    </ReportCard>
  )
}
