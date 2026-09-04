import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { sprintsApi } from '../../api'
import { sprintsKey } from '../../lib/queryKeys'
import { Select } from '../../components/ui'
import { CAPABILITY } from '../../components/delivery'
import { SprintStateBadge, formatSprintRange } from '../../components/sprints'
import { useProjectDelivery } from '../../hooks/useProjectDelivery'
import { useProjectPermissions } from '../../hooks/usePermissions'
import type { Sprint } from '../../types'
import { Notice, ReportCard } from './common'
import type { SprintChoice } from './sprint'

/**
 * What the two sprint reports share (HD-29, R4): **one picker, one capability
 * gate, one sentence about which sprint is on screen.**
 *
 * The gate is the part with a history. `board` decides whether these reports are
 * OFFERED, and it is read from the project's DECLARED capabilities — never from
 * whether sprints happen to exist in the data. "The project has a sprint, so it
 * must do Scrum" is the exact inference the delivery-capability model was built
 * to delete, and it shipped once already (delivery-paths §5.3, Rule C). So:
 *
 *  • `board = SCRUM` → the reports render;
 *  • `board = KANBAN` → they are LISTED and disabled, with an affordance that is
 *    visible *while the capability is off* and links to the switch;
 *  • **the API is untouched either way** (Rule A). `/sprint-burnup` answers for
 *    any sprint in any project whatever `board` says — a capability gates the
 *    UI, and a hidden control is never a permission.
 *
 * The one permission consulted anywhere on these pages is `project.edit`, and
 * only to decide whether the Rule C affordance is a link or a sentence
 * (reports-proposal §7). The reports themselves are not permission-gated (§4.2).
 */

/** Where a capability is switched on. Absolute — these pages live in a splat route. */
export function deliverySettingsHref(wsId: string | undefined, projectId: string | undefined): string {
  return `/w/${wsId}/p/${projectId}/settings/delivery`
}

/** The Backlog, where a project's first sprint is created. Absolute, same reason. */
export function backlogHref(wsId: string | undefined, projectId: string | undefined): string {
  return `/w/${wsId}/p/${projectId}/backlog`
}

/**
 * Every sprint of the project, in the server's order: ACTIVE first, then FUTURE
 * ascending, then COMPLETED descending.
 *
 * All states, unlike the pickers on the Backlog and the create dialog, which
 * offer only the open ones: a sprint review is a RETROSPECTIVE artefact, so a
 * completed sprint is its primary subject rather than an edge case.
 *
 * It shares `sprintsKey` with `useProjectSprints`, so a project page that has
 * already listed sprints pays nothing here — `select` is per-observer, so
 * keeping the page envelope (for `totalElements`) does not fork the cache entry.
 */
export function useReportSprints(wsId: string | undefined, projectId: string | undefined) {
  return useQuery({
    queryKey: sprintsKey(wsId, projectId),
    queryFn: () => sprintsApi.list(wsId!, projectId!, { size: 200 }),
    enabled: !!wsId && !!projectId,
  })
}

/**
 * The sprint picker both reports share. Writes the URL, always — a report that
 * cannot be sent to a colleague as a link is half a feature (§4.4).
 */
export function SprintReportPicker({ sprints, value, onChange, disabled }: {
  sprints: Sprint[]
  /** The resolved sprint's id, so the control shows what the page is showing. */
  value: string
  onChange: (sprintId: string) => void
  disabled?: boolean
}) {
  // A URL naming a sprint this project does not list gets an option of its own
  // rather than falling through to the first row: a picker that quietly displays
  // "Sprint 12" while the page reports it could not find the pinned sprint is a
  // control disagreeing with its own screen.
  const unknown = !!value && !sprints.some(s => s.id === value)
  return (
    <Select
      label="Sprint"
      aria-label="Sprint"
      value={value}
      disabled={disabled || (sprints.length === 0 && !unknown)}
      onChange={e => onChange(e.target.value)}
    >
      {sprints.length === 0 && !unknown && <option value="">No sprints yet</option>}
      {unknown && <option value={value}>Unknown sprint</option>}
      {sprints.map(s => (
        <option key={s.id} value={s.id}>
          {s.state === 'ACTIVE' ? `${s.name} (running)` : s.name}
        </option>
      ))}
    </Select>
  )
}

/**
 * Which sprint is on screen and **why** — printed rather than assumed.
 *
 * A reader who opened "the sprint report" and is shown last month's sprint
 * because nothing is running must be told that, or they will read a finished
 * sprint as the current one. Same for a link naming a sprint this project does
 * not have: the page says so and keeps the picker, instead of silently
 * substituting another sprint under the same URL.
 */
export function SprintChoiceNote({ choice, sprintId }: { choice: SprintChoice; sprintId: string }) {
  if (choice.unknownPinned) {
    return (
      <Notice tone="warn">
        This link names a sprint <span className="mono">{sprintId}</span> that is not in this
        project’s sprint list — it may have been deleted, or the list may be showing only the most
        recent sprints. Nothing has been substituted for it; pick a sprint above to see a report.
      </Notice>
    )
  }
  if (choice.reason === 'LATEST_COMPLETED') {
    return (
      <Notice>
        <b>No sprint is running in this project right now</b>, so this is the most recently
        completed one — <b>{choice.sprint?.name}</b>. Pick another above.
      </Notice>
    )
  }
  if (choice.reason === 'PLANNED') {
    return (
      <Notice>
        <b>No sprint is running and none has finished yet</b>, so this is the next planned one —{' '}
        <b>{choice.sprint?.name}</b>. It has no history until it is started.
      </Notice>
    )
  }
  return null
}

/** Name, state and dates of the sprint a report is about. */
export function SprintHeadline({ sprint }: { sprint: Pick<Sprint, 'name' | 'state' | 'startAt' | 'endAt'> }) {
  const range = formatSprintRange(sprint)
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span style={{ fontSize: 15, fontWeight: 800 }}>{sprint.name}</span>
      <SprintStateBadge state={sprint.state} compact />
      {range && (
        <span className="mono" style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{range}</span>
      )}
    </div>
  )
}

/**
 * Rule C for `board` — the whole report, replaced by the way to turn sprints on
 * (delivery-paths §5.3: *every capability has an enabling affordance visible
 * while it is off, placed where a user would look for the capability*).
 *
 * Two branches, and the copy for both comes out of the capability table so this
 * page cannot invent its own vocabulary for a switch it does not own:
 *
 *  • somebody who may edit the project gets a LINK to the switch — the reports
 *    area deliberately does not flip a project-wide setting from a chart page;
 *  • anybody else gets `memberNote` and no verb at all. Offering them the word
 *    "Scrum" would teach vocabulary their project does not use and end in a 403.
 */
export function ScrumRequiredCard({ wsId, projectId, canEdit, report }: {
  wsId: string | undefined
  projectId: string | undefined
  canEdit: boolean
  /** "burn-up" / "review record" — what is unavailable, named. */
  report: string
}) {
  const copy = CAPABILITY.iterations
  return (
    <ReportCard>
      <h2 style={{ fontSize: 15, fontWeight: 800, margin: 0 }}>{copy.offTitle}</h2>
      <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: '8px 0 0', maxWidth: 640 }}>
        This project doesn’t run sprints, so there is no sprint {report} to draw. {copy.offBlurb}
      </p>
      {canEdit ? (
        <>
          <p className="text-sm" style={{ color: 'var(--color-text-muted)', margin: '8px 0 0', maxWidth: 640 }}>
            {copy.reversible}
          </p>
          <p style={{ margin: '12px 0 0' }}>
            <Link
              to={deliverySettingsHref(wsId, projectId)}
              className="text-sm no-underline"
              style={{ color: 'var(--color-brand-ink)', fontWeight: 700 }}
            >
              Turn on Scrum in project settings →
            </Link>
          </p>
        </>
      ) : (
        <p className="text-sm" style={{ color: 'var(--color-text-muted)', margin: '8px 0 0' }}>
          {copy.memberNote}
        </p>
      )}
    </ReportCard>
  )
}

/**
 * A Scrum project that has never had a sprint — the §2.3 empty state, which is a
 * card and not an empty chart.
 *
 * This is a fact about the DATA, not about the capability: the project declares
 * that it runs sprints and simply has not started one. The two are kept apart on
 * purpose, because conflating them is how "no sprints exist ⇒ this must be
 * Kanban" got written in the first place.
 */
export function NoSprintsCard({ wsId, projectId, canCreate }: {
  wsId: string | undefined
  projectId: string | undefined
  canCreate: boolean
}) {
  return (
    <ReportCard>
      <h2 style={{ fontSize: 15, fontWeight: 800, margin: 0 }}>This project has no sprints yet</h2>
      <p className="text-sm" style={{ color: 'var(--color-text-secondary)', margin: '8px 0 0', maxWidth: 640 }}>
        Sprint reports are drawn from a sprint’s own history, so there is nothing to report until
        one has been planned and started. This project plans in sprints — it just hasn’t run one.
      </p>
      {canCreate && (
        <p style={{ margin: '12px 0 0' }}>
          <Link
            to={backlogHref(wsId, projectId)}
            className="text-sm no-underline"
            style={{ color: 'var(--color-brand-ink)', fontWeight: 700 }}
          >
            Plan the first sprint on the Backlog →
          </Link>
        </p>
      )}
    </ReportCard>
  )
}

/**
 * Rule C for `estimation`, beside the disabled measure toggle.
 *
 * Turning it off never removes a value (Rule B): points already recorded keep
 * rendering in the scope-change log and in the review's five lists, read-only.
 * What is withheld is the CHART, because a points chart of a project that does
 * not estimate would be a chart of whatever estimates happen to be lying around.
 */
export function EstimationOffHint({ wsId, projectId, canEdit }: {
  wsId: string | undefined
  projectId: string | undefined
  canEdit: boolean
}) {
  if (!canEdit) {
    return (
      <span className="text-xs" style={{ color: 'var(--color-text-muted)', alignSelf: 'flex-end', paddingBottom: 9 }}>
        {CAPABILITY.estimation.memberNote}
      </span>
    )
  }
  return (
    <span className="text-xs" style={{ alignSelf: 'flex-end', paddingBottom: 9, maxWidth: 260 }}>
      <Link
        to={deliverySettingsHref(wsId, projectId)}
        className="no-underline"
        style={{ color: 'var(--color-brand-ink)', fontWeight: 600 }}
      >
        Turn on estimation to chart story points
      </Link>
    </span>
  )
}

/**
 * The gate both pages open with: the project's declared capabilities plus the
 * one permission that decides whether a Rule C affordance is a link or a
 * sentence.
 *
 * `ready` is false until the project response has landed. Both `deliveryOf`
 * defaults are "off", so rendering the Kanban card while the project is still
 * loading would tell a Scrum team their project doesn't run sprints and then
 * yank the sentence away — a wrong answer is worse than a late one.
 */
export function useSprintReportGate(wsId: string | undefined, projectId: string | undefined) {
  const delivery = useProjectDelivery(wsId, projectId)
  const permissions = useProjectPermissions(wsId, projectId)
  return {
    ready: !!delivery.project,
    iterations: delivery.iterations,
    estimation: delivery.estimation,
    /** `project.edit` — turning a capability on IS a project edit (HD-123 S5). */
    canEdit: permissions.can('project.edit'),
  }
}
