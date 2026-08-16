import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGetProject, apiUpdateProject } from '../../api'
import { projectIssuesKeyPrefix } from '../../lib/queryKeys'
import { apiErrorText } from '../../components/sprints'
import { Button } from '../../components/ui'
import type { BoardMode } from '../../types'

/**
 * Project settings → Board (HD-27): the Kanban/Scrum switch.
 *
 * `boardMode` is a PRESENTATION switch, not a permission — the sprint API
 * behaves identically in both modes, and a Kanban team may still plan an
 * iteration. Only two things change: the board scopes itself to the active
 * sprint, and the Backlog shows sprint sections unconditionally instead of only
 * once a sprint exists.
 *
 * Saved through `PATCH …/projects/{id}`, which the server gates with
 * `requireProjectCurator` — the same predicate `ProjectSettingsArea` already
 * checks before rendering this area, so a non-curator never reaches the tab.
 */

const MODES: { value: BoardMode; title: string; blurb: string }[] = [
  {
    value: 'KANBAN',
    title: 'Kanban',
    blurb: 'A continuous board over the whole project. Every open issue sits in a workflow column '
      + 'and nothing is time-boxed.',
  },
  {
    value: 'SCRUM',
    title: 'Scrum',
    blurb: 'The board shows only the running sprint, with its goal, countdown and point totals. '
      + 'The Backlog gains sprint sections you plan by dragging.',
  },
]

export default function ProjectBoardSettingsPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const qc = useQueryClient()
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

  const { data: project, isLoading } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const current: BoardMode = project?.boardMode ?? 'KANBAN'

  const save = useMutation({
    mutationFn: (boardMode: BoardMode) => apiUpdateProject(wsId!, projectId!, { boardMode }),
    onSuccess: updated => {
      setError('')
      setSaved(true)
      // The board reads `boardMode` off this entry and re-scopes immediately…
      qc.setQueryData(['project', wsId, projectId], updated)
      qc.invalidateQueries({ queryKey: ['project', wsId, projectId] })
      // …and its issue lists change shape (sprint-scoped vs whole project).
      qc.invalidateQueries({ queryKey: projectIssuesKeyPrefix(wsId, projectId) })
      qc.invalidateQueries({ queryKey: ['sprints', wsId, projectId] })
    },
    // 409 while the project is archived; 403 if the role changed under us.
    onError: e => { setSaved(false); setError(apiErrorText(e, 'Could not change the board mode')) },
  })

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="font-semibold" style={{ fontSize: 15 }}>Board</h2>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 560 }}>
          How this project’s board works. Switching is reversible and changes nothing about the
          issues themselves — sprints keep working in either mode.
        </p>
      </div>

      {isLoading ? (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
      ) : (
        <div className="flex flex-col gap-2.5" role="radiogroup" aria-label="Board mode">
          {MODES.map(mode => {
            const selected = current === mode.value
            return (
              <label
                key={mode.value}
                className="flex items-start gap-3 rounded-lg border cursor-pointer transition-colors"
                style={{
                  padding: '12px 14px',
                  background: 'white',
                  borderColor: selected ? 'var(--color-brand)' : 'var(--color-border)',
                  boxShadow: selected ? 'var(--shadow-card)' : undefined,
                }}
              >
                <input
                  type="radio"
                  name="boardMode"
                  value={mode.value}
                  checked={selected}
                  disabled={save.isPending || !!project?.archived}
                  onChange={() => { setSaved(false); save.mutate(mode.value) }}
                  style={{ accentColor: 'var(--color-brand)', marginTop: 3, width: 15, height: 15 }}
                />
                <span className="flex flex-col gap-0.5 min-w-0">
                  <span className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>
                    {mode.title}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    {mode.blurb}
                  </span>
                </span>
              </label>
            )
          })}
        </div>
      )}

      {project?.archived && (
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          This project is archived — unarchive it to change the board mode.
        </p>
      )}
      {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
      {saved && !error && (
        <p className="text-xs" style={{ color: 'var(--color-success)' }}>Board mode saved.</p>
      )}

      {current === 'SCRUM' && wsId && projectId && (
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            Plan and start sprints from the project’s Backlog.
          </span>
          {/* Absolute path: inside the /settings/* splat a relative link would
              resolve AFTER the splat segment (see AdminArea / CLAUDE.md). */}
          <Link to={`/w/${wsId}/p/${projectId}/backlog`} style={{ textDecoration: 'none' }}>
            <Button variant="secondary" size="sm">Open Backlog</Button>
          </Link>
        </div>
      )}
    </div>
  )
}
