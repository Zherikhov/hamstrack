import { useParams } from 'react-router'
import { useAuthStore } from '../auth'
import { getLastProject } from '../recentProjects'

export interface CurrentProject {
  wsId: string
  projectId: string
  key?: string
  name?: string
}

/**
 * The "active" project for shell chrome — the top-bar project switcher and the
 * always-visible rail sections (Board / Backlog / Reports / Settings).
 *
 * Inside a project route it's that project; anywhere else (Home, My work,
 * Workspaces) it falls back to the last-visited project from the local recency
 * journal, so the project tabs never disappear and always point at a real
 * project.
 *
 * NOTE: last-visited is currently a per-device localStorage journal
 * (`recentProjects.ts`). Persisting a per-user "last active project" server-side
 * (so it survives across devices and can drive server features) is tracked as a
 * backend task — see the redesign notes.
 */
export function useCurrentProject(): CurrentProject | null {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const user = useAuthStore(s => s.user)
  if (wsId && projectId) return { wsId, projectId }
  const last = user ? getLastProject(user.id) : null
  return last ? { wsId: last.wsId, projectId: last.projectId, key: last.key, name: last.name } : null
}
