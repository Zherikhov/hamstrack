import { NavLink, Navigate, Route, Routes, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { apiGetProject } from '../../api'
import FlowReportPage from './FlowReportPage'
import CycleTimeReportPage from './CycleTimeReportPage'

/**
 * Reports area — `/w/:wsId/p/:projectId/reports/*` (epic HD-5, slice R1).
 *
 * **Lazily loaded from `App.tsx`** so the chart library ships in this chunk and
 * nowhere else; `ParamKeyed`-wrapped like every other project page so a window,
 * an interval or a filter can never leak from one project into the next.
 *
 * A left list of reports, not tabs: this list is going to hold six entries, some
 * of them disabled with a reason. The unbuilt ones are listed rather than hidden
 * — a report you cannot discover is a report nobody asks for, and R4/R5's sprint
 * reports will sit here **disabled-with-reason** in a Kanban project (Rule C:
 * every capability needs an enabling affordance that is visible *while it is
 * off*), never hidden because "this project has no sprints".
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
            <NavLink
              to={`${base}/flow`}
              className="text-sm no-underline"
              style={({ isActive }) => ({
                padding: '8px 11px',
                borderRadius: 'var(--radius-md)',
                background: isActive ? 'color-mix(in srgb, var(--color-brand) 10%, white)' : 'transparent',
                color: isActive ? 'var(--color-brand)' : 'var(--color-text-secondary)',
                fontWeight: isActive ? 700 : 500,
              })}
            >
              Flow
            </NavLink>

            <NavLink
              to={`${base}/cycle-time`}
              className="text-sm no-underline"
              style={({ isActive }) => ({
                padding: '8px 11px',
                borderRadius: 'var(--radius-md)',
                background: isActive ? 'color-mix(in srgb, var(--color-brand) 10%, white)' : 'transparent',
                color: isActive ? 'var(--color-brand)' : 'var(--color-text-secondary)',
                fontWeight: isActive ? 700 : 500,
              })}
            >
              Cycle &amp; lead time
            </NavLink>

            {/* Not yet built. Listed, dimmed and labelled — the house rule for a
                surface whose backend does not exist yet (CLAUDE.md: draw a
                visible "coming soon" stub rather than omit it). R3/R4/R5 replace
                these entries in place. */}
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
              <Route path="*" element={<Navigate to={`${base}/flow`} replace />} />
            </Routes>
          </div>
        </div>
      </div>
    </div>
  )
}

/** The rest of the epic, visible while unbuilt so the shape of the area is honest. */
const UPCOMING = [
  { label: 'Sprint burn-up', hint: 'Scope and completed as separate lines, with a real scope-change log — coming soon' },
  { label: 'Sprint review', hint: 'Committed / added / completed / carried over — coming soon' },
  { label: 'Velocity', hint: 'A forecast band over recent sprints, never a scoreboard — coming soon' },
] as const
