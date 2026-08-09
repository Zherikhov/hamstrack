import { useQuery } from '@tanstack/react-query'
import { apiListWorkspaces, apiListProjects, apiListIssues } from '../api'
import type { Issue, Project } from '../types'

/** An issue assigned to the current user, tagged with its workspace/project for navigation. */
export interface MyIssue extends Issue {
  _wsId: string
  _wsName: string
  _project: Project
}

/**
 * Cross-project "assigned to me" roll-up. There is no single aggregate endpoint,
 * so this fans out over the user's workspaces → active projects → issues filtered
 * by assignee. Failures per project/workspace are swallowed so one broken scope
 * never blanks the dashboard.
 */
export async function fetchMyWork(userId: string): Promise<MyIssue[]> {
  const workspaces = await apiListWorkspaces()
  const perWs = await Promise.all(
    workspaces.map(async ws => {
      try {
        const projects = (await apiListProjects(ws.id)).filter(p => !p.archived)
        const lists = await Promise.all(
          projects.map(p =>
            apiListIssues(ws.id, p.id, { assigneeId: userId })
              .then(issues => issues.map(i => ({ ...i, _wsId: ws.id, _wsName: ws.name, _project: p } as MyIssue)))
              .catch(() => [] as MyIssue[]),
          ),
        )
        return lists.flat()
      } catch {
        return [] as MyIssue[]
      }
    }),
  )
  return perWs.flat()
}

export function useMyWork(userId?: string) {
  return useQuery({
    queryKey: ['myWork', userId],
    queryFn: () => fetchMyWork(userId!),
    enabled: !!userId,
    staleTime: 30_000,
  })
}

/** Days until a `YYYY-MM-DD` due date (negative = overdue). null when no date. */
export function daysUntil(dueDate?: string | null): number | null {
  if (!dueDate) return null
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const due = new Date(dueDate + 'T00:00:00')
  return Math.round((due.getTime() - today.getTime()) / 86_400_000)
}

/** Human label + tone for a due date relative to today. */
export function dueLabel(dueDate?: string | null): { text: string; urgent: boolean } | null {
  const d = daysUntil(dueDate)
  if (d === null) return null
  if (d < 0) return { text: `${-d}d overdue`, urgent: true }
  if (d === 0) return { text: 'Today', urgent: true }
  if (d === 1) return { text: 'Tomorrow', urgent: true }
  if (d <= 7) return { text: `${d}d`, urgent: false }
  return { text: new Date(dueDate + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' }), urgent: false }
}
