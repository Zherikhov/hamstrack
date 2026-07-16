import type {
  User, Workspace, Project, Issue, Comment, Attachment, IssueHistoryEntry,
  Notification, WorkspaceMember, ProjectConfig,
  AdminStatus, AdminPriority, AdminIssueType, AdminWorkflow, AdminPrioritySet,
  AdminField, AdminFieldSet, AdminIssueTypeSet, FieldConfig, FieldType, FieldValue,
  ProjectBinding, TransitionRule, UsageDetail,
} from './types'
import { useAuthStore } from './auth'

const BASE = '/api'

export class ApiResponseError extends Error {
  constructor(public status: number, public detail: string) {
    super(detail)
  }
}

// FormData bodies must NOT get a JSON Content-Type — the browser sets
// multipart/form-data with the boundary itself
function buildHeaders(init: RequestInit, token: string | null): HeadersInit {
  return {
    ...(init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(init.headers ?? {}),
  }
}

async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = useAuthStore.getState().accessToken

  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: buildHeaders(init, token),
    credentials: 'include',
  })

  if (res.status === 401) {
    // Try refresh once
    const refreshed = await tryRefresh()
    if (refreshed) {
      const newToken = useAuthStore.getState().accessToken
      const retry = await fetch(`${BASE}${path}`, {
        ...init,
        headers: buildHeaders(init, newToken),
        credentials: 'include',
      })
      if (!retry.ok) {
        useAuthStore.getState().clear()
        throw new ApiResponseError(retry.status, 'Unauthorized')
      }
      return retry
    }
    useAuthStore.getState().clear()
    throw new ApiResponseError(401, 'Session expired')
  }

  return res
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await authFetch(path, init)

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

// ── Instance metadata ─────────────────────────────────────────────────────────

export interface PublicConfig {
  publicLandingEnabled: boolean
  termsAcceptanceRequired: boolean
  publicSignupEnabled: boolean
  version: string
}

export async function apiPublicConfig() {
  return request<PublicConfig>('/meta')
}

// ── Auth ──────────────────────────────────────────────────────────────────────

export async function apiLogin(email: string, password: string) {
  const data = await request<{ accessToken: string }>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
  return data
}

export async function apiRegister(email: string, displayName: string, password: string, termsAccepted: boolean) {
  return request<{ message: string }>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, displayName, password, termsAccepted }),
  })
}

export async function apiVerifyEmail(token: string) {
  // Returns the same shape as login — verifying also signs the user in
  return request<{ accessToken: string }>('/auth/verify-email', {
    method: 'POST',
    body: JSON.stringify({ token }),
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

// ── Project taxonomy config ──────────────────────────────────────────────────
// Statuses, transitions, priorities and types all come from one endpoint since
// M1 — the project's effective workflow/priority-set resolution happens server-side

export async function apiGetProjectConfig(wsId: string, projectId: string): Promise<ProjectConfig> {
  return request(`/workspaces/${wsId}/projects/${projectId}/config`)
}

// ── Issues ─────────────────────────────────────────────────────────────────────

export async function apiListIssues(
  wsId: string,
  projectId: string,
  filters?: { statusId?: string; assigneeId?: string; priorityId?: string }
): Promise<Issue[]> {
  const params = new URLSearchParams()
  if (filters?.statusId) params.set('statusId', filters.statusId)
  if (filters?.assigneeId) params.set('assigneeId', filters.assigneeId)
  if (filters?.priorityId) params.set('priorityId', filters.priorityId)
  const qs = params.toString()
  return request(`/workspaces/${wsId}/projects/${projectId}/issues${qs ? `?${qs}` : ''}`)
}

export async function apiGetIssue(wsId: string, projectId: string, number: number): Promise<Issue> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}`)
}

export async function apiCreateIssue(
  wsId: string,
  projectId: string,
  // priorityId omitted = the project's default priority;
  // fields keyed by field id — value shapes per field type (see FieldValue)
  payload: { title: string; typeId: string; statusId: string; priorityId?: string; description?: string; assigneeId?: string; dueDate?: string; fields?: Record<string, FieldValue> }
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
  // version enables the backend's optimistic-lock check: 409 if someone else saved first;
  // fields is partial — only listed ids change, null clears a value
  payload: Partial<{ title: string; typeId: string; statusId: string; priorityId: string; description: string; assigneeId: string; dueDate: string; fields: Record<string, FieldValue | null>; version: number }>
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

// ── Attachments ───────────────────────────────────────────────────────────────

export async function apiListAttachments(wsId: string, projectId: string, number: number): Promise<Attachment[]> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/attachments`)
}

export async function apiUploadAttachment(wsId: string, projectId: string, number: number, file: File): Promise<Attachment> {
  const form = new FormData()
  form.append('file', file)
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/attachments`, {
    method: 'POST',
    body: form,
  })
}

export async function apiDownloadAttachment(wsId: string, projectId: string, number: number, attachment: Attachment): Promise<void> {
  const res = await authFetch(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/attachments/${attachment.id}`)
  if (!res.ok) throw new ApiResponseError(res.status, 'Download failed')
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = attachment.filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export async function apiDeleteAttachment(wsId: string, projectId: string, number: number, attachmentId: string): Promise<void> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/attachments/${attachmentId}`, { method: 'DELETE' })
}

// ── Issue History ──────────────────────────────────────────────────────────────

export async function apiGetIssueHistory(wsId: string, projectId: string, number: number): Promise<IssueHistoryEntry[]> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/history`)
}

// ── Admin console (system ADMIN only; server-guarded) ────────────────────────

export interface UpsertCatalogPayload {
  name: string
  color?: string
  icon?: string
  category?: 'TODO' | 'IN_PROGRESS' | 'DONE'  // statuses only
  position?: number
}

function adminCrud<TResp>(resource: string) {
  return {
    list: () => request<TResp[]>(`/admin/${resource}`),
    create: (payload: UpsertCatalogPayload) =>
      request<TResp>(`/admin/${resource}`, { method: 'POST', body: JSON.stringify(payload) }),
    update: (id: string, payload: UpsertCatalogPayload) =>
      request<TResp>(`/admin/${resource}/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
    archive: (id: string) => request<void>(`/admin/${resource}/${id}/archive`, { method: 'POST' }),
    unarchive: (id: string) => request<void>(`/admin/${resource}/${id}/unarchive`, { method: 'POST' }),
    remove: (id: string, replaceWithId?: string) =>
      request<void>(`/admin/${resource}/${id}${replaceWithId ? `?replaceWithId=${replaceWithId}` : ''}`,
        { method: 'DELETE' }),
    usage: (id: string) => request<UsageDetail>(`/admin/${resource}/${id}/usage`),
  }
}

export const adminStatuses = adminCrud<AdminStatus>('statuses')
export const adminPriorities = adminCrud<AdminPriority>('priorities')
export const adminIssueTypes = adminCrud<AdminIssueType>('issue-types')

export interface UpsertWorkflowPayload {
  name: string
  description?: string
  statusIds: string[]                 // board-column order
  transitions: TransitionRule[]
}

export const adminWorkflows = {
  list: () => request<AdminWorkflow[]>('/admin/workflows'),
  create: (p: UpsertWorkflowPayload) =>
    request<AdminWorkflow>('/admin/workflows', { method: 'POST', body: JSON.stringify(p) }),
  update: (id: string, p: UpsertWorkflowPayload) =>
    request<AdminWorkflow>(`/admin/workflows/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
  remove: (id: string) => request<void>(`/admin/workflows/${id}`, { method: 'DELETE' }),
}

export interface UpsertPrioritySetPayload {
  name: string
  items: { priorityId: string; isDefault: boolean }[]  // display order
}

export const adminPrioritySets = {
  list: () => request<AdminPrioritySet[]>('/admin/priority-sets'),
  create: (p: UpsertPrioritySetPayload) =>
    request<AdminPrioritySet>('/admin/priority-sets', { method: 'POST', body: JSON.stringify(p) }),
  update: (id: string, p: UpsertPrioritySetPayload) =>
    request<AdminPrioritySet>(`/admin/priority-sets/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
  remove: (id: string) => request<void>(`/admin/priority-sets/${id}`, { method: 'DELETE' }),
}

export interface UpsertFieldPayload {
  name: string
  key?: string                        // blank on create = derived from name; immutable afterwards
  type: FieldType                     // immutable after creation
  config?: FieldConfig | null
  description?: string
}

export const adminFields = {
  list: () => request<AdminField[]>('/admin/fields'),
  create: (p: UpsertFieldPayload) =>
    request<AdminField>('/admin/fields', { method: 'POST', body: JSON.stringify(p) }),
  update: (id: string, p: UpsertFieldPayload) =>
    request<AdminField>(`/admin/fields/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
  archive: (id: string) => request<void>(`/admin/fields/${id}/archive`, { method: 'POST' }),
  unarchive: (id: string) => request<void>(`/admin/fields/${id}/unarchive`, { method: 'POST' }),
  // No remap for arbitrary value shapes — deleting a field with values needs
  // the explicit dropValues confirmation (409 otherwise)
  remove: (id: string, dropValues = false) =>
    request<void>(`/admin/fields/${id}${dropValues ? '?dropValues=true' : ''}`, { method: 'DELETE' }),
  usage: (id: string) => request<UsageDetail>(`/admin/fields/${id}/usage`),
}

export interface UpsertFieldSetPayload {
  name: string
  items: { fieldId: string; required: boolean; showOnCreate: boolean }[]  // display order
}

export const adminFieldSets = {
  list: () => request<AdminFieldSet[]>('/admin/field-sets'),
  create: (p: UpsertFieldSetPayload) =>
    request<AdminFieldSet>('/admin/field-sets', { method: 'POST', body: JSON.stringify(p) }),
  update: (id: string, p: UpsertFieldSetPayload) =>
    request<AdminFieldSet>(`/admin/field-sets/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
  remove: (id: string) => request<void>(`/admin/field-sets/${id}`, { method: 'DELETE' }),
}

export interface UpsertIssueTypeSetPayload {
  name: string
  typeIds: string[]                   // display order
}

export const adminIssueTypeSets = {
  list: () => request<AdminIssueTypeSet[]>('/admin/issue-type-sets'),
  create: (p: UpsertIssueTypeSetPayload) =>
    request<AdminIssueTypeSet>('/admin/issue-type-sets', { method: 'POST', body: JSON.stringify(p) }),
  update: (id: string, p: UpsertIssueTypeSetPayload) =>
    request<AdminIssueTypeSet>(`/admin/issue-type-sets/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
  remove: (id: string) => request<void>(`/admin/issue-type-sets/${id}`, { method: 'DELETE' }),
}

export interface ProjectBindings {
  workflowId: string | null
  prioritySetId: string | null
  fieldSetId: string | null
  issueTypeSetId: string | null
}

export const adminProjects = {
  list: () => request<ProjectBinding[]>('/admin/projects'),
  // Full replacement of all bindings; null = system default
  updateBindings: (projectId: string, bindings: ProjectBindings) =>
    request<ProjectBinding>(`/admin/projects/${projectId}/bindings`, {
      method: 'PATCH',
      body: JSON.stringify(bindings),
    }),
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
