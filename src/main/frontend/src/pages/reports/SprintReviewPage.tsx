import { useMemo, type ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import {
  ApiResponseError, apiGetProjectConfig, apiListWorkspaceMembers, reportsApi,
} from '../../api'
import type { SprintReviewIssue, SprintReviewList, SprintReviewReport } from '../../types'
import { REPORT_STALE_TIME, reportKey } from '../../lib/queryKeys'
import { Badge, StatusBadge } from '../../components/ui'
import { formatPoints, formatSprintRange } from '../../components/sprints'
import {
  MetaLine, Notice, RateLimitNotice, ReportCard, SeriesTable, TruncationNotice,
  type SeriesColumn,
} from './common'
import {
  SNAPSHOT_POINTS_FOOTNOTE, hasPoints, readReviewList, readSprintReportState,
  resolveSprintChoice, reviewHeadline, reviewSummary, writeReviewParams,
} from './sprint'
import {
  NoSprintsCard, ScrumRequiredCard, SprintChoiceNote, SprintHeadline, SprintReportPicker,
  useReportSprints, useSprintReportGate,
} from './sprintCommon'

/**
 * **Sprint review record** (`/w/:wsId/p/:projectId/reports/sprint-review`) — the
 * artefact teams actually open at a retrospective (§2.4), and the primary
 * deliverable of R4 rather than the burn-up's appendix.
 *
 * **Not a chart.** Five labelled lists, each with a count and a point sum:
 * *Committed · Added after start · Removed before end · Completed · Carried
 * over*, each an issue row that clicks through to the issue. It carries no
 * chart library at all, which is also why it loads instantly.
 *
 * Three properties it may not lose:
 *
 *  1. **A completed sprint's record does not quietly shed rows.** An issue
 *     deleted since the sprint ran still appears, from the ledger's snapshot,
 *     with its key and the points it carried when it entered. It is rendered as
 *     a real row that says the issue is gone — never hidden, and never drawn as
 *     a broken link. (The ledger's FK is `ON DELETE SET NULL` precisely so those
 *     rows survive; a report that dropped them would undo that decision from the
 *     other end.)
 *  2. **Points are the entry snapshot, not today's estimate** — the opposite of
 *     the burn-up next door, and deliberately so: a retro asks what the team
 *     committed to, and a later re-estimate must not rewrite that answer.
 *  3. **Taxonomy comes from the project `config`.** Type and status are resolved
 *     by id against the config endpoint, exactly as every badge in the product
 *     is; nothing here hardcodes a status name or a type colour.
 *
 * Capability gating is `board`, read from the DECLARED capability and never from
 * whether sprints exist in the data — and it gates this UI only: the endpoint
 * answers for any sprint in any project (Rule A).
 */
export default function SprintReviewPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()

  const state = readSprintReportState(searchParams)
  const gate = useSprintReportGate(wsId, projectId)

  const sprintQuery = useReportSprints(wsId, projectId)
  const sprints = sprintQuery.data?.content ?? []
  const choice = resolveSprintChoice(state.sprintId, sprints)
  const sprintId = state.sprintId || choice.sprint?.id || ''

  const { data: config } = useQuery({
    queryKey: ['projectConfig', wsId, projectId],
    queryFn: () => apiGetProjectConfig(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const { data: members = [] } = useQuery({
    queryKey: ['wsMembers', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId,
    staleTime: 60_000,
  })

  const review = useQuery({
    queryKey: reportKey('sprint-review', projectId, writeReviewParams({ sprintId })),
    queryFn: () => reportsApi.sprintReview(wsId!, projectId!, { sprintId }),
    // See the burn-up: the endpoint answers regardless of `board` (Rule A);
    // this only avoids fetching a record the page has decided not to render.
    enabled: !!wsId && !!projectId && !!sprintId && gate.iterations,
    staleTime: REPORT_STALE_TIME,
    retry: false,
  })

  const report = review.data
  const error = review.error instanceof ApiResponseError ? review.error : null

  function issueHref(key: string): string | null {
    const n = Number(key.slice(key.lastIndexOf('-') + 1))
    return Number.isFinite(n) && n > 0 ? `/w/${wsId}/p/${projectId}/issues/${n}` : null
  }

  function assigneeName(id: string | null): string {
    if (!id) return 'Unassigned'
    return members.find(m => m.userId === id)?.displayName ?? id
  }

  const columns = useMemo(() => ([
    {
      key: 'issue',
      label: 'Issue',
      align: 'left' as const,
      render: (i: SprintReviewIssue) => <ReviewIssueKey issue={i} href={isGone(i) ? null : issueHref(i.key)} />,
    },
    {
      key: 'title',
      label: 'Title',
      align: 'left' as const,
      render: (i: SprintReviewIssue) => i.title
        ? (
          <span className="inline-block truncate align-bottom" style={{ maxWidth: 320 }} title={i.title}>
            {i.title}
          </span>
        )
        : <span style={{ color: 'var(--color-text-muted)' }}>no longer in this project</span>,
    },
    {
      key: 'type',
      label: 'Type',
      align: 'left' as const,
      render: (i: SprintReviewIssue) => {
        // Config-driven, by id — never a hardcoded name and never a guess from
        // the title. An unknown id is left blank rather than invented.
        const type = (config?.issueTypes ?? []).find(t => t.id === i.typeId)
        return type
          ? <Badge label={type.name} color={type.color} />
          : <span style={{ color: 'var(--color-text-muted)' }}>—</span>
      },
    },
    {
      key: 'assignee',
      label: 'Assignee',
      align: 'left' as const,
      render: (i: SprintReviewIssue) => isGone(i)
        ? <span style={{ color: 'var(--color-text-muted)' }}>—</span>
        : assigneeName(i.assigneeId),
    },
    {
      key: 'points',
      label: 'Points on entry',
      render: (i: SprintReviewIssue) => typeof i.points === 'number'
        ? formatPoints(i.points)
        : <span style={{ color: 'var(--color-text-muted)' }} title="No estimate when it entered this sprint">—</span>,
    },
    {
      key: 'status',
      label: 'Status',
      align: 'left' as const,
      render: (i: SprintReviewIssue) => {
        const status = (config?.statuses ?? []).find(s => s.id === i.statusId)
        if (status) return <StatusBadge name={status.name} category={status.category} color={status.color} />
        // A status the effective workflow no longer carries, or an issue that is
        // gone: an honest dash rather than a name this client cannot resolve.
        return <span style={{ color: 'var(--color-text-muted)' }}>—</span>
      },
    },
  ]), [config, members, wsId, projectId])

  // ── Capability gate: DECLARED, never inferred from the data ────────────────
  if (!gate.ready) {
    return <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
  }
  if (!gate.iterations) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeading />
        <ScrumRequiredCard wsId={wsId} projectId={projectId} canEdit={gate.canEdit} report="review record" />
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

  const summary = report ? reviewSummary(report) : null
  const deletedRows = report ? countDeleted(report) : 0
  const neverStarted = !!report && !report.startAt

  return (
    <div className="flex flex-col gap-4">
      <PageHeading />

      <div className="flex flex-wrap items-end gap-2">
        <SprintReportPicker
          sprints={sprints}
          value={sprintId}
          onChange={id => setSearchParams(writeReviewParams({ sprintId: id }), { replace: true })}
        />
      </div>

      <SprintChoiceNote choice={choice} sprintId={state.sprintId} />

      {error?.status === 404 && (
        <Notice tone="warn">
          <b>That sprint is no longer here.</b> It may have been deleted, or it belongs to another
          project — pick one from the list above to see its record.
        </Notice>
      )}
      {error?.status === 429 && (
        <RateLimitNotice retryAfter={error.retryAfter} onRetry={() => void review.refetch()} />
      )}
      {review.error && !HANDLED_STATUSES.includes(error?.status ?? 0) && (
        <Notice tone="warn">
          Couldn’t load the sprint review: {error?.detail ?? 'the request failed. Try again.'}
        </Notice>
      )}

      <TruncationNotice meta={report?.meta} />

      {report && summary && report.sprint && (
        <>
          <SprintHeadline
            sprint={{
              name: report.sprint.name,
              state: report.sprint.state,
              startAt: report.startAt,
              endAt: report.endAt,
            }}
          />

          {/* The one header line §2.4 pins, verbatim in shape. */}
          <ReportCard>
            <p style={{ fontSize: 15, fontWeight: 700, margin: 0, letterSpacing: '-0.01em' }}>
              {reviewHeadline(report, formatSprintRange({ startAt: report.startAt, endAt: report.endAt }))}
            </p>
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: '8px 0 0', maxWidth: 760 }}>
              <b>{summary.atEndCount.toLocaleString()}</b> is what this sprint <b>held when it
              ended</b> — the {summary.completedCount.toLocaleString()} completed plus the{' '}
              {summary.carriedCount.toLocaleString()} carried over, so the two figures count the
              same work and the first is always part of the second. It committed to{' '}
              {summary.committedCount.toLocaleString()} at its start;{' '}
              {summary.addedCount.toLocaleString()} arrived after that and{' '}
              {summary.removedCount.toLocaleString()} left before the end, which is the whole of
              the difference between those two numbers.
            </p>
            <p className="text-sm" style={{ color: 'var(--color-text-muted)', margin: '6px 0 0', maxWidth: 760 }}>
              {SNAPSHOT_POINTS_FOOTNOTE}
            </p>
          </ReportCard>

          {/* A row whose issue has been deleted is a row that still counts. */}
          {deletedRows > 0 && (
            <Notice>
              <b>
                {deletedRows.toLocaleString()} issue{deletedRows === 1 ? '' : 's'} listed below{' '}
                {deletedRows === 1 ? 'has' : 'have'} been deleted since this sprint ran.
              </b>{' '}
              {deletedRows === 1 ? 'It is' : 'They are'} shown from this sprint’s own record — the key
              and the points {deletedRows === 1 ? 'it' : 'they'} carried on entry — because a
              finished sprint’s record must not quietly lose rows. There is nothing left to open, so{' '}
              {deletedRows === 1 ? 'that row does' : 'those rows do'} not link anywhere.
            </Notice>
          )}

          {neverStarted && (
            <Notice>
              <b>{report.sprint.name} has not been started</b>, so there is nothing to review yet:
              commitment is an event, and it happens when a sprint starts. The five lists below are
              empty for that reason and not because the record was lost.
            </Notice>
          )}

          {LISTS.map(section => (
            <ReviewSection
              key={section.key}
              title={section.title}
              blurb={section.blurb}
              empty={section.empty}
              list={readReviewList(report[section.key])}
              columns={columns}
            />
          ))}

          <div className="flex flex-col gap-2">
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0, maxWidth: 760 }}>
              <b>Completed</b> and <b>carried over</b> together are everything the sprint held when
              it ended, which is why they are what completion is measured against: work added after
              the start can be finished, so measuring it against the <b>commitment</b> would compare
              two different sets and could put more in the numerator than the denominator. The
              commitment is a list of its own here, and the added and removed lists are the whole
              account of how the sprint drifted from it — which is the question a retrospective
              actually opens this page to answer.
            </p>
            <MetaLine meta={report.meta} />
          </div>
        </>
      )}

      {review.isPending && !error && !!sprintId && (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading the sprint record…</p>
      )}
    </div>
  )
}

const HANDLED_STATUSES = [400, 404, 429]

/**
 * The five lists, in the order §2.4 names them — as a table, so a sixth cannot
 * be added on one screen and forgotten on the next, and so each one's empty
 * sentence is written once.
 */
const LISTS: {
  key: 'committed' | 'addedAfterStart' | 'removedBeforeEnd' | 'completed' | 'carriedOver'
  title: string
  blurb: string
  empty: string
}[] = [
  {
    key: 'committed',
    title: 'Committed',
    blurb: 'In the sprint at the moment it started — the plan, as it stood.',
    empty: 'This sprint started with nothing in it.',
  },
  {
    key: 'addedAfterStart',
    title: 'Added after start',
    blurb: 'Arrived once the sprint was already running. Not a judgement — but it is why '
      + '“we finished less than we planned” and “more work arrived” are different sentences.',
    empty: 'Nothing was added after this sprint started.',
  },
  {
    key: 'removedBeforeEnd',
    title: 'Removed before end',
    blurb: 'Taken out of the sprint before it finished.',
    empty: 'Nothing was taken out of this sprint.',
  },
  {
    key: 'completed',
    title: 'Completed',
    blurb: 'Closed while the sprint was running.',
    empty: 'Nothing was completed in this sprint.',
  },
  {
    key: 'carriedOver',
    title: 'Carried over',
    blurb: 'Still in the sprint when it ended, unfinished.',
    empty: 'Nothing was carried over — everything in the sprint was finished.',
  },
]

function ReviewSection({ title, blurb, empty, list, columns }: {
  title: string
  blurb: string
  empty: string
  list: SprintReviewList
  columns: SeriesColumn<SprintReviewIssue>[]
}) {
  // A sum of nothing is 0 on the wire, and "0 points" is a measurement while
  // "nobody estimated any of it" is the absence of one — `hasPoints` keeps the
  // two apart, and `unestimatedCount` says the second out loud.
  const points = hasPoints(list) ? formatPoints(list.points) : null
  return (
    <ReportCard>
      <div className="flex flex-wrap items-baseline gap-2">
        <h2 style={{ fontSize: 15, fontWeight: 800, margin: 0 }}>{title}</h2>
        <span className="mono" style={{ fontSize: 12.5, color: 'var(--color-text-secondary)' }}>
          {list.count.toLocaleString()} issue{list.count === 1 ? '' : 's'}
          {points !== null && ` · ${points} point${list.points === 1 ? '' : 's'}`}
        </span>
        {list.unestimatedCount > 0 && (
          <span className="mono" style={{ fontSize: 11.5, color: 'var(--color-text-muted)' }}>
            {list.unestimatedCount.toLocaleString()} unestimated
          </span>
        )}
      </div>
      <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: '4px 0 12px', maxWidth: 760 }}>
        {blurb}
      </p>
      {list.issues.length === 0 ? (
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: 0 }}>{empty}</p>
      ) : (
        <SeriesTable
          caption={`${title} — ${list.count.toLocaleString()} issue${list.count === 1 ? '' : 's'}${points !== null ? `, ${points} points` : ''}`}
          columns={columns}
          rows={list.issues}
          rowKey={(i, index) => i.issueId ?? `${i.key}-${index}`}
        />
      )}
    </ReportCard>
  )
}

/** How many rows across the five lists name an issue that no longer exists. */
function countDeleted(report: SprintReviewReport): number {
  const keys = new Set<string>()
  for (const section of LISTS) {
    for (const issue of readReviewList(report[section.key]).issues) {
      if (isGone(issue)) keys.add(issue.key)
    }
  }
  return keys.size
}

/**
 * Whether this row's issue is gone. `deleted` is the server's own answer and is
 * authoritative; the null id is checked too so a response that carries one
 * without the other still renders a truthful row rather than a link to nothing.
 */
function isGone(issue: SprintReviewIssue): boolean {
  return issue.deleted || !issue.issueId
}

/**
 * The issue key. A deleted issue keeps its key — that is what the ledger's
 * snapshot is for — and says so, instead of linking to a 404 or vanishing.
 */
function ReviewIssueKey({ issue, href }: { issue: SprintReviewIssue; href: string | null }): ReactNode {
  if (href) {
    return (
      <Link to={href} className="mono no-underline" style={{ color: 'var(--color-brand)', fontWeight: 600 }}>
        {issue.key}
      </Link>
    )
  }
  return (
    <span className="mono" title={isGone(issue) ? 'This issue has been deleted' : undefined}>
      {issue.key}
      {isGone(issue) && (
        <span className="ml-1" style={{ color: 'var(--color-text-muted)', fontWeight: 500 }}>(deleted)</span>
      )}
    </span>
  )
}

function PageHeading() {
  return (
    <div>
      <h1 style={{ fontSize: 20, fontWeight: 800, letterSpacing: '-0.01em', margin: 0 }}>
        Sprint review record
      </h1>
      <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 660 }}>
        What this sprint committed to, what arrived late, what was finished and what carried over —
        the five lists a retrospective reads, not a chart.
      </p>
    </div>
  )
}
