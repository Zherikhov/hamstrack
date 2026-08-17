import { useNavigate, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { ApiResponseError, apiGetProjectConfig } from '../api'
import { Button } from '../components/ui'
import { NotFoundAction, NotFoundScreen } from './NotFoundPage'
import IssueDetail from './IssueDetail'

/**
 * Full-page issue view (HD-67) — the shared {@link IssueDetail} body in a
 * centered, comfortable-width column for deep-linking, sharing and very large
 * issues. Reuses the same workspace/project-scoped endpoints as the drawer
 * (config + issue), so it introduces no new backend surface: a non-member or a
 * missing project/issue 404s exactly as elsewhere (never revealing existence).
 */
export default function IssueFullPage() {
  const { wsId, projectId, number } = useParams<{ wsId: string; projectId: string; number: string }>()
  const navigate = useNavigate()
  const issueNumber = Number(number)

  const { data: config, isLoading, isError, error } = useQuery({
    queryKey: ['projectConfig', wsId, projectId],
    queryFn: () => apiGetProjectConfig(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })

  // HD-97: a 404 here is the "well-formed URL, nothing you can see" case — the
  // server answers the same whether the issue is gone or simply isn't yours, so
  // the shared `resource` screen carries that wording. Any OTHER failure is an
  // outage, not a not-found, and must stay visibly different.
  if (isError) {
    const notFound = error instanceof ApiResponseError && error.status === 404
    if (notFound) {
      return (
        <NotFoundScreen variant="resource" noun="issue">
          <NotFoundAction to={`/w/${wsId}/p/${projectId}`}>Go to board</NotFoundAction>
        </NotFoundScreen>
      )
    }
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-3">
        <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
          This issue couldn’t be loaded. Check your connection and try again.
        </p>
        <Button variant="secondary" size="sm" onClick={() => navigate(`/w/${wsId}/p/${projectId}`)}>
          Go to board
        </Button>
      </div>
    )
  }

  const ready = !isLoading && config && wsId && projectId && Number.isFinite(issueNumber)

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: 'var(--color-surface)' }}>
      {/* Back bar */}
      <div
        className="flex items-center gap-2 px-4 py-2 border-b flex-shrink-0"
        style={{ background: 'white', borderColor: 'var(--color-border)' }}
      >
        <button
          onClick={() => navigate(`/w/${wsId}/p/${projectId}`, { state: { openIssue: issueNumber } })}
          className="inline-flex items-center gap-1.5 text-xs cursor-pointer hover:underline"
          style={{ color: 'var(--color-text-secondary)' }}
        >
          <ArrowLeft size={14} /> Back to board
        </button>
      </div>

      {/* Centered comfortable-width column (inline maxWidth — never Tailwind max-w-*) */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', justifyContent: 'center', overflow: 'hidden' }}>
        <div
          style={{
            width: '100%', maxWidth: 900, height: '100%',
            borderLeft: '1px solid var(--color-border)', borderRight: '1px solid var(--color-border)',
          }}
        >
          {ready ? (
            <IssueDetail
              key={issueNumber}
              wsId={wsId!}
              projectId={projectId!}
              issueNumber={issueNumber}
              issueTypes={config!.issueTypes}
              statuses={config!.statuses}
              transitions={config!.transitions}
              priorities={config!.priorities}
              fields={config!.fields}
              onOpenIssue={n => navigate(`/w/${wsId}/p/${projectId}/issues/${n}`)}
            />
          ) : (
            <div className="h-full flex items-center justify-center">
              <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
