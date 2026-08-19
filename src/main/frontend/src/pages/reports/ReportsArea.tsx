import { NavLink, Navigate, Route, Routes, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { apiGetProject } from '../../api'
import { useProjectDelivery } from '../../hooks/useProjectDelivery'
import FlowReportPage from './FlowReportPage'
import CycleTimeReportPage from './CycleTimeReportPage'
import SprintBurnupPage from './SprintBurnupPage'
import SprintReviewPage from './SprintReviewPage'

/**
 * Reports area — `/w/:wsId/p/:projectId/reports/*` (epic HD-5, slices R1–R4).
 *
 * **Lazily loaded from `App.tsx`** so the chart library ships in this chunk and
 * nowhere else; `ParamKeyed`-wrapped like every other project page so a window,
 * an interval, a sprint or a filter can never leak from one project into the
 * next.
 *
 * A left list of reports, not tabs. The unbuilt ones are listed rather than
 * hidden — a report you cannot discover is a report nobody asks for — and, since
 * R4, so are the ones a project's delivery capabilities do not offer:
 *
 *  • the two **sprint** reports are listed always, and **disabled with a reason**
 *    when `delivery.board` is not SCRUM (Rule C: every capability needs an
 *    enabling affordance that is visible *while it is off*). The pages
 *    themselves carry the affordance, so a deep link lands on the way to turn
 *    sprints on rather than on an error;
 *  • that decision reads the project's **declared** capability and never the
 *    data. "This project has a sprint, so it must do Scrum" is the exact
 *    inference the capability model exists to delete, and it shipped once.
 *
 * The API is untouched by any of it (Rule A): `/sprint-burnup` and
 * `/sprint-review` answer for any sprint in any project whatever `board` says.
 *
 * Reports are **not permission-gated** (reports-proposal §4.2): any member who
 * can open the project can open its reports. There is deliberately no
 * `report.view` — this product has no read permissions, and every number here is
 * derivable from the search API the same member already holds.
 *
 * Absolute paths in every `NavLink`/`Navigate`: inside a splat route a relative
 * path resolves AFTER the splat segment, so `to="flow"` from `/reports/flow`
 * would navigate to `/reports/flow/flow`.
 */
export default function ReportsArea() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const base = `/w/${wsId}/p/${projectId}/reports`

  const { data: project } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })

  // The DECLARED capability — the single answer to "does this project do X?".
  // `iterations` is false while the project is still loading, which is the safe
  // direction: an item can go disabled → enabled without anything being yanked
  // out from under a pointer.
  const { iterations } = useProjectDelivery(wsId, projectId)

  return (
    <div style={{ flex: 1, overflowY: 'auto', background: 'var(--color-surface)' }}>
      {/* Inline maxWidth, never a Tailwind `max-w-*` class: our @theme
          --spacing-* scale shadows max-w-{2xs..3xl}, so `max-w-xl` would resolve
          to 32px (CLAUDE.md). 1180 matches Home and Releases. */}
      <div style={{ maxWidth: 1180, padding: '20px 26px 40px' }}>
        <div className="mb-4">
          <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Reports</div>
          <h1 className="font-display font-bold" style={{ fontSize: 22, letterSpacing: '-0.01em' }}>
            {project?.name ?? '…'}
          </h1>
          {project?.archived && (
            <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              This project is archived. Reports are history, so they stay readable.
            </p>
          )}
        </div>

        <div className="flex items-start gap-6">
          <nav
            className="flex flex-col gap-0.5 flex-shrink-0"
            aria-label="Reports"
            style={{ width: 208 }}
          >
            <ReportLink to={`${base}/flow`} label="Flow" />
            <ReportLink to={`${base}/cycle-time`} label="Cycle & lead time" />

            {/* Listed whatever the project does. With sprints off they are
                disabled and say why — the page behind them carries the link that
                turns sprints on, so the feature stays reachable for a team that
                does not use it yet. */}
            <ReportLink
              to={`${base}/sprint-burnup`}
              label="Sprint burn-up"
              disabled={!iterations}
              disabledHint={SPRINT_HINT}
            />
            <ReportLink
              to={`${base}/sprint-review`}
              label="Sprint review"
              disabled={!iterations}
              disabledHint={SPRINT_HINT}
            />

            {/* Not yet built. Listed, dimmed and labelled — the house rule for a
                surface whose backend does not exist yet (CLAUDE.md: draw a
                visible "coming soon" stub rather than omit it). R5 replaces this
                entry in place. */}
            {UPCOMING.map(r => (
              <span
                key={r.label}
                title={r.hint}
                className="text-sm flex items-center gap-2"
                style={{ padding: '8px 11px', color: 'var(--color-text-muted)', cursor: 'default' }}
              >
                {r.label}
                <span
                  className="mono"
                  style={{
                    marginLeft: 'auto', fontSize: 9, letterSpacing: '0.05em',
                    border: '1px solid var(--color-border-2)', borderRadius: 5, padding: '1px 5px',
                  }}
                >
                  SOON
                </span>
              </span>
            ))}
          </nav>

          <div className="flex-1 min-w-0">
            <Routes>
              <Route index element={<Navigate to={`${base}/flow`} replace />} />
              <Route path="flow" element={<FlowReportPage />} />
              <Route path="cycle-time" element={<CycleTimeReportPage />} />
              {/* Routed whatever `board` says: the pages render the Rule C
                  affordance themselves, so a shared link from a Scrum project
                  opened in a Kanban one explains itself instead of redirecting
                  somewhere unrelated. */}
              <Route path="sprint-burnup" element={<SprintBurnupPage />} />
              <Route path="sprint-review" element={<SprintReviewPage />} />
              <Route path="*" element={<Navigate to={`${base}/flow`} replace />} />
            </Routes>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * One entry of the report list.
 *
 * A **disabled** entry is still a link — deliberately. The page it opens is
 * where the enabling affordance lives, and a dead `<span>` would leave a Kanban
 * team able to see that sprint reports exist and unable to find out what turns
 * them on, which is the failure Rule C names. It is styled as unavailable and
 * says why on hover, so nothing about it promises numbers.
 */
function ReportLink({ to, label, disabled, disabledHint }: {
  to: string
  label: string
  disabled?: boolean
  disabledHint?: string
}) {
  return (
    <NavLink
      to={to}
      title={disabled ? disabledHint : undefined}
      aria-disabled={disabled || undefined}
      className="text-sm no-underline flex items-center gap-2"
      style={({ isActive }) => ({
        padding: '8px 11px',
        borderRadius: 'var(--radius-md)',
        background: isActive ? 'color-mix(in srgb, var(--color-brand) 10%, white)' : 'transparent',
        color: isActive
          ? 'var(--color-brand)'
          : disabled ? 'var(--color-text-muted)' : 'var(--color-text-secondary)',
        fontWeight: isActive ? 700 : 500,
      })}
    >
      {label}
      {disabled && (
        <span
          className="mono"
          style={{
            marginLeft: 'auto', fontSize: 9, letterSpacing: '0.05em',
            border: '1px solid var(--color-border-2)', borderRadius: 5, padding: '1px 5px',
          }}
        >
          OFF
        </span>
      )}
    </NavLink>
  )
}

/** The Rule C sentence, in the one place a hover can reach it. */
const SPRINT_HINT =
  'This project doesn’t run sprints — turn on Scrum in project settings to use this report'

/** The rest of the epic, visible while unbuilt so the shape of the area is honest. */
const UPCOMING = [
  { label: 'Velocity', hint: 'A forecast band over recent sprints, never a scoreboard — coming soon' },
] as const
