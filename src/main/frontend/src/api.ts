import type { User, Workspace, Project, IssueType, Status, Issue, Comment, IssueHistoryEntry, StatusTransition, Notification, WorkspaceMember } from './types'
import { useAuthStore } from './auth'

const BASE = '/api'

export class ApiResponseError extends Error {
  constructor(public status: number, public detail: string) {
    super(detail)
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = useAuthStore.getState().accessToken

  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
    credentials: 'include',
  })

  if (res.status === 401) {
    // Try refresh once
    const refreshed = await tryRefresh()
    if (refreshed) {
      const newToken = useAuthStore.getState().accessToken
      const retry = await fetch(`${BASE}${path}`, {
        ...init,
        headers: {
          'Content-Type': 'application/json',
          ...(newToken ? { Authorization: `Bearer ${newToken}` } : {}),
          ...(init.headers ?? {}),
        },
        credentials: 'include',
      })
      if (!retry.ok) {
        useAuthStore.getState().clear()
        throw new ApiResponseError(retry.status, 'Unauthorized')
      }
      return retry.status === 204 ? (undefined as T) : retry.json()
    }
    useAuthStore.getState().clear()
    throw new ApiResponseError(401, 'Session expired')
  }

  if (!res.ok) {
    let detail = res.statusText
    try {
      const body = await res.json()
      detail = body.detail ?? body.message ?? body.title ?? detail
    } catch { /* ignore */ }
    throw new ApiResponseError(res.status, detail)
  }

  return res.status === 204 ? (undefined as T) : res.json()
}

async function tryRefresh(): Promise<boolean> {
  try {
    const res = await fetch(`${BASE}/auth/refresh`, { method: 'POST', credentials: 'include' })
    if (!res.ok) return false
    const data = await res.json()
    useAuthStore.getState().setToken(data.accessToken)
    return true
  } catch {
    return false
  }
}

// ── Auth ──────────────────────────────────────────────────────────────────────

export async function apiLogin(email: string, password: string) {
  const data = await request<{ accessToken: string }>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
  return data
}

export async function apiRegister(email: string, displayName: string, password: string) {
  return request<{ message: string }>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, displayName, password }),
  })
}

export async function apiResendVerification(email: string) {
  return request<{ message: string }>('/auth/resend-verification', {
    method: 'POST',
    body: JSON.stringify({ email }),
  })
}

export async function apiLogout() {
  return request<void>('/auth/logout', { method: 'POST' })
}

export async function apiMe(): Promise<User> {
  return request('/auth/me')
}

export async function apiRefresh(): Promise<{ accessToken: string }> {
  return request('/auth/refresh', { method: 'POST' })
}

// ── Workspaces ────────────────────────────────────────────────────────────────

export async function apiListWorkspaces(): Promise<Workspace[]> {
  return request('/workspaces')
}

export async function apiCreateWorkspace(name: string): Promise<Workspace> {
  return request('/workspaces', { method: 'POST', body: JSON.stringify({ name }) })
}

export async function apiGetWorkspace(wsId: string): Promise<Workspace> {
  return request(`/workspaces/${wsId}`)
}

// ── Projects ──────────────────────────────────────────────────────────────────

export async function apiListProjects(wsId: string, includeArchived = false): Promise<Project[]> {
  return request(`/workspaces/${wsId}/projects${includeArchived ? '?includeArchived=true' : ''}`)
}

export async function apiCreateProject(wsId: string, name: string, key: string, description?: string): Promise<Project> {
  return request(`/workspaces/${wsId}/projects`, {
    method: 'POST',
    body: JSON.stringify({ name, key, description }),
  })
}

export async function apiGetProject(wsId: string, projectId: string): Promise<Project> {
  return request(`/workspaces/${wsId}/projects/${projectId}`)
}

export async function apiUnarchiveProject(wsId: string, projectId: string): Promise<void> {
  return request(`/workspaces/${wsId}/projects/${projectId}/unarchive`, { method: 'POST' })
}

// ── Issue Taxonomy ─────────────────────────────────────────────────────────────

export async function apiListIssueTypes(wsId: string): Promise<IssueType[]> {
  return request(`/workspaces/${wsId}/issue-types`)
}

export async function apiListStatuses(wsId: string): Promise<Status[]> {
  return request(`/workspaces/${wsId}/statuses`)
}

// ── Issues ─────────────────────────────────────────────────────────────────────

export async function apiListIssues(
  wsId: string,
  projectId: string,
  filters?: { statusId?: string; assigneeId?: string; priority?: string }
): Promise<Issue[]> {
  const params = new URLSearchParams()
  if (filters?.statusId) params.set('statusId', filters.statusId)
  if (filters?.assigneeId) params.set('assigneeId', filters.assigneeId)
  if (filters?.priority) params.set('priority', filters.priority)
  const qs = params.toString()
  return request(`/workspaces/${wsId}/projects/${projectId}/issues${qs ? `?${qs}` : ''}`)
}

export async function apiGetIssue(wsId: string, projectId: string, number: number): Promise<Issue> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}`)
}

export async function apiCreateIssue(
  wsId: string,
  projectId: string,
  payload: { title: string; typeId: string; statusId: string; priority?: string; description?: string; assigneeId?: string; dueDate?: string }
): Promise<Issue> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export async function apiUpdateIssue(
  wsId: string,
  projectId: string,
  number: number,
  // version enables the backend's optimistic-lock check: 409 if someone else saved first
  payload: Partial<{ title: string; typeId: string; statusId: string; priority: string; description: string; assigneeId: string; dueDate: string; version: number }>
): Promise<Issue> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  })
}

export async function apiDeleteIssue(wsId: string, projectId: string, number: number): Promise<void> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}`, { method: 'DELETE' })
}

// ── Comments ──────────────────────────────────────────────────────────────────

export async function apiListComments(wsId: string, projectId: string, number: number): Promise<Comment[]> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/comments`)
}

export async function apiCreateComment(wsId: string, projectId: string, number: number, body: string): Promise<Comment> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/comments`, {
    method: 'POST',
    body: JSON.stringify({ body }),
  })
}

export async function apiDeleteComment(wsId: string, projectId: string, number: number, commentId: string): Promise<void> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/comments/${commentId}`, { method: 'DELETE' })
}

// ── Issue History ──────────────────────────────────────────────────────────────

export async function apiGetIssueHistory(wsId: string, projectId: string, number: number): Promise<IssueHistoryEntry[]> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/history`)
}

// ── Workflow / Status Transitions ──────────────────────────────────────────────

export async function apiListStatusTransitions(wsId: string): Promise<StatusTransition[]> {
  return request(`/workspaces/${wsId}/status-transitions`)
}

export async function apiCreateStatusTransition(wsId: string, fromStatusId: string, toStatusId: string): Promise<StatusTransition> {
  return request(`/workspaces/${wsId}/status-transitions`, {
    method: 'POST',
    body: JSON.stringify({ fromStatusId, toStatusId }),
  })
}

export async function apiDeleteStatusTransition(wsId: string, transitionId: string): Promise<void> {
  return request(`/workspaces/${wsId}/status-transitions/${transitionId}`, { method: 'DELETE' })
}

// ── Notifications ──────────────────────────────────────────────────────────────

export async function apiListNotifications(): Promise<Notification[]> {
  return request('/notifications')
}

export async function apiGetUnreadCount(): Promise<number> {
  const data = await request<{ count: number }>('/notifications/unread-count')
  return data.count
}

export async function apiMarkNotificationRead(id: string): Promise<Notification> {
  return request(`/notifications/${id}/read`, { method: 'POST' })
}

export async function apiMarkAllNotificationsRead(): Promise<void> {
  return request('/notifications/read-all', { method: 'POST' })
}

// ── Workspace Members ──────────────────────────────────────────────────────────

export async function apiListWorkspaceMembers(wsId: string): Promise<WorkspaceMember[]> {
  return request(`/workspaces/${wsId}/members`)
}
