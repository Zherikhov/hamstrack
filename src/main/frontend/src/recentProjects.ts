/**
 * Per-user recency journal of visited projects, kept in localStorage.
 * Powers the "/" → last-active-project redirect and the top-bar switcher's
 * recent list. Keyed by user id so accounts sharing a browser never see each
 * other's history (same isolation rule as the query cache).
 *
 * Device-local by design: history does not follow the user across browsers.
 */

export interface RecentProject {
  wsId: string
  projectId: string
  key: string
  name: string
  visitedAt: number
}

const MAX_RECENTS = 5

const storageKey = (userId: string) => `hamstrack.recent-projects.${userId}`

export function getRecentProjects(userId: string): RecentProject[] {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    if (!raw) return []
    const list = JSON.parse(raw) as RecentProject[]
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

export function getLastProject(userId: string): RecentProject | null {
  return getRecentProjects(userId)[0] ?? null
}

export function recordProjectVisit(userId: string, entry: Omit<RecentProject, 'visitedAt'>) {
  const rest = getRecentProjects(userId).filter(e => e.projectId !== entry.projectId)
  const list = [{ ...entry, visitedAt: Date.now() }, ...rest].slice(0, MAX_RECENTS)
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(list))
  } catch { /* storage full/blocked — recency is best-effort */ }
}

/** Drop a stale entry (project deleted or access revoked) so "/" stops redirecting to it. */
export function forgetProject(userId: string, projectId: string) {
  const list = getRecentProjects(userId).filter(e => e.projectId !== projectId)
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(list))
  } catch { /* ignore */ }
}
