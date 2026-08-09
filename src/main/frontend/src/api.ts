import type {
  User, Workspace, Project, Issue, Comment, Attachment, IssueHistoryEntry,
  Notification, WorkspaceMember, ProjectConfig,
  AdminStatus, AdminPriority, AdminIssueType, AdminWorkflow, AdminPrioritySet,
  AdminField, AdminFieldSet, AdminIssueTypeSet, FieldConfig, FieldType, FieldValue,
  ProjectBinding, BindingOptions, TransitionRule, UsageDetail, AdminUser, PendingInvite,
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
    // Never surface an empty message — statusText is blank over HTTP/2, which
    // would render as a silent, invisible error in the UI.
    if (!detail) detail = `Request failed (${res.status})`
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

// Backs both the password-reset email link and admin-generated setup links —
// both point at /reset-password?token=
export async function apiResetPassword(token: string, newPassword: string) {
  return request<{ message: string }>('/auth/reset-password', {
    method: 'POST',
    body: JSON.stringify({ token, newPassword }),
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

// ── Onboarding (first-login: create or join a team) ──────────────────────────

export async function apiListInvites(): Promise<PendingInvite[]> {
  return request('/invites')
}

export async function apiAcceptInvite(inviteId: string): Promise<Workspace> {
  return request(`/invites/${inviteId}/accept`, { method: 'POST' })
}

export async function apiDeclineInvite(inviteId: string): Promise<void> {
  return request(`/invites/${inviteId}/decline`, { method: 'POST' })
}

// "Create a team" onboarding choice: provisions the demo starter workspace (if
// enabled) and completes onboarding. Joining a team completes it via the invite
// accept endpoint instead — and gets no demo.
export async function apiOnboardingCreateTeam(): Promise<void> {
  return request('/onboarding/create-team', { method: 'POST' })
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

/** Uniform pagination envelope returned by paginated list endpoints. */
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

// Board: the full list (no pagination — the kanban needs every card).
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

// Backlog: paginated; excludeDone drops DONE-category statuses server-side.
export async function apiListIssuesPaged(
  wsId: string,
  projectId: string,
  opts: { page?: number; size?: number; excludeDone?: boolean; statusId?: string; priorityId?: string; assigneeId?: string }
): Promise<Page<Issue>> {
  const params = new URLSearchParams()
  params.set('page', String(opts.page ?? 0))
  params.set('size', String(opts.size ?? 50))
  if (opts.excludeDone) params.set('excludeDone', 'true')
  if (opts.statusId) params.set('statusId', opts.statusId)
  if (opts.priorityId) params.set('priorityId', opts.priorityId)
  if (opts.assigneeId) params.set('assigneeId', opts.assigneeId)
  return request(`/workspaces/${wsId}/projects/${projectId}/issues?${params.toString()}`)
}

export async function apiGetIssue(wsId: string, projectId: string, number: number): Promise<Issue> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}`)
}

// Direct children of an issue, in board order (no pagination — a parent's fan-out is small)
export async function apiGetIssueChildren(wsId: string, projectId: string, number: number): Promise<Issue[]> {
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/children`)
}

export async function apiCreateIssue(
  wsId: string,
  projectId: string,
  // priorityId omitted = the project's default priority;
  // parentId attaches the new issue under a legal parent (higher type level, same project);
  // fields keyed by field id — value shapes per field type (see FieldValue)
  payload: { title: string; typeId: string; statusId: string; priorityId?: string; description?: string; assigneeId?: string; dueDate?: string; parentId?: string; fields?: Record<string, FieldValue> }
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
  // clearAssignee/clearDueDate/clearParent explicitly unset those nullable fields (a plain
  // null can't be distinguished from "not sent" server-side); parentId sets/changes the parent
  payload: Partial<{ title: string; typeId: string; statusId: string; priorityId: string; description: string; assigneeId: string; dueDate: string; parentId: string; clearAssignee: boolean; clearDueDate: boolean; clearParent: boolean; fields: Record<string, FieldValue | null>; version: number }>
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

export async function apiListComments(
  wsId: string, projectId: string, number: number, opts?: { page?: number; size?: number }
): Promise<Page<Comment>> {
  const params = new URLSearchParams()
  params.set('page', String(opts?.page ?? 0))
  params.set('size', String(opts?.size ?? 50))
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/comments?${params.toString()}`)
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

export async function apiGetIssueHistory(
  wsId: string, projectId: string, number: number, opts?: { page?: number; size?: number }
): Promise<Page<IssueHistoryEntry>> {
  const params = new URLSearchParams()
  params.set('page', String(opts?.page ?? 0))
  params.set('size', String(opts?.size ?? 50))
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/history?${params.toString()}`)
}

// ── Admin console (system ADMIN only; server-guarded) ────────────────────────

export interface UpsertCatalogPayload {
  name: string
  color?: string
  icon?: string
  category?: 'TODO' | 'IN_PROGRESS' | 'DONE'  // statuses only
  position?: number
}

// Each admin group is a factory bound to a base path: '/admin' for the system
// console, or a delegated scope like '/workspaces/{ws}/projects/{p}/admin'. The
// server enforces the scope; the shapes are identical, so the same pages drive
// all three consoles (see makeAdminApi + the AdminApi context on the frontend).

function catalogGroup<T>(base: string, resource: string) {
  const at = (id: string) => `${base}/${resource}/${id}`
  return {
    list: () => request<T[]>(`${base}/${resource}`),
    create: (payload: UpsertCatalogPayload) =>
      request<T>(`${base}/${resource}`, { method: 'POST', body: JSON.stringify(payload) }),
    update: (id: string, payload: UpsertCatalogPayload) =>
      request<T>(at(id), { method: 'PATCH', body: JSON.stringify(payload) }),
    archive: (id: string) => request<void>(`${at(id)}/archive`, { method: 'POST' }),
    unarchive: (id: string) => request<void>(`${at(id)}/unarchive`, { method: 'POST' }),
    remove: (id: string, replaceWithId?: string) =>
      request<void>(`${at(id)}${replaceWithId ? `?replaceWithId=${replaceWithId}` : ''}`, { method: 'DELETE' }),
    usage: (id: string) => request<UsageDetail>(`${at(id)}/usage`),
  }
}

export interface UpsertWorkflowPayload {
  name: string
  description?: string
  statusIds: string[]                 // board-column order
  transitions: TransitionRule[]
}

function workflowGroup(base: string) {
  return {
    list: () => request<AdminWorkflow[]>(`${base}/workflows`),
    create: (p: UpsertWorkflowPayload) =>
      request<AdminWorkflow>(`${base}/workflows`, { method: 'POST', body: JSON.stringify(p) }),
    update: (id: string, p: UpsertWorkflowPayload) =>
      request<AdminWorkflow>(`${base}/workflows/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
    remove: (id: string) => request<void>(`${base}/workflows/${id}`, { method: 'DELETE' }),
  }
}

export interface UpsertPrioritySetPayload {
  name: string
  items: { priorityId: string; isDefault: boolean }[]  // display order
}

function prioritySetGroup(base: string) {
  return {
    list: () => request<AdminPrioritySet[]>(`${base}/priority-sets`),
    create: (p: UpsertPrioritySetPayload) =>
      request<AdminPrioritySet>(`${base}/priority-sets`, { method: 'POST', body: JSON.stringify(p) }),
    update: (id: string, p: UpsertPrioritySetPayload) =>
      request<AdminPrioritySet>(`${base}/priority-sets/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
    remove: (id: string) => request<void>(`${base}/priority-sets/${id}`, { method: 'DELETE' }),
  }
}

export interface UpsertFieldPayload {
  name: string
  key?: string                        // blank on create = derived from name; immutable afterwards
  type: FieldType                     // immutable after creation
  config?: FieldConfig | null
  description?: string
}

function fieldGroup(base: string) {
  const at = (id: string) => `${base}/fields/${id}`
  return {
    list: () => request<AdminField[]>(`${base}/fields`),
    create: (p: UpsertFieldPayload) =>
      request<AdminField>(`${base}/fields`, { method: 'POST', body: JSON.stringify(p) }),
    update: (id: string, p: UpsertFieldPayload) =>
      request<AdminField>(at(id), { method: 'PATCH', body: JSON.stringify(p) }),
    archive: (id: string) => request<void>(`${at(id)}/archive`, { method: 'POST' }),
    unarchive: (id: string) => request<void>(`${at(id)}/unarchive`, { method: 'POST' }),
    // No remap for arbitrary value shapes — deleting a field with values needs
    // the explicit dropValues confirmation (409 otherwise)
    remove: (id: string, dropValues = false) =>
      request<void>(`${at(id)}${dropValues ? '?dropValues=true' : ''}`, { method: 'DELETE' }),
    usage: (id: string) => request<UsageDetail>(`${at(id)}/usage`),
  }
}

export interface UpsertFieldSetPayload {
  name: string
  items: { fieldId: string; required: boolean; showOnCreate: boolean }[]  // display order
}

function fieldSetGroup(base: string) {
  return {
    list: () => request<AdminFieldSet[]>(`${base}/field-sets`),
    create: (p: UpsertFieldSetPayload) =>
      request<AdminFieldSet>(`${base}/field-sets`, { method: 'POST', body: JSON.stringify(p) }),
    update: (id: string, p: UpsertFieldSetPayload) =>
      request<AdminFieldSet>(`${base}/field-sets/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
    remove: (id: string) => request<void>(`${base}/field-sets/${id}`, { method: 'DELETE' }),
  }
}

export interface UpsertIssueTypeSetPayload {
  name: string
  typeIds: string[]                   // display order
}

function issueTypeSetGroup(base: string) {
  return {
    list: () => request<AdminIssueTypeSet[]>(`${base}/issue-type-sets`),
    create: (p: UpsertIssueTypeSetPayload) =>
      request<AdminIssueTypeSet>(`${base}/issue-type-sets`, { method: 'POST', body: JSON.stringify(p) }),
    update: (id: string, p: UpsertIssueTypeSetPayload) =>
      request<AdminIssueTypeSet>(`${base}/issue-type-sets/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
    remove: (id: string) => request<void>(`${base}/issue-type-sets/${id}`, { method: 'DELETE' }),
  }
}

/** All admin groups bound to one base path. Drives the system, workspace and project consoles. */
export function makeAdminApi(base: string) {
  return {
    statuses: catalogGroup<AdminStatus>(base, 'statuses'),
    priorities: catalogGroup<AdminPriority>(base, 'priorities'),
    issueTypes: catalogGroup<AdminIssueType>(base, 'issue-types'),
    workflows: workflowGroup(base),
    prioritySets: prioritySetGroup(base),
    fields: fieldGroup(base),
    fieldSets: fieldSetGroup(base),
    issueTypeSets: issueTypeSetGroup(base),
  }
}

export type AdminApi = ReturnType<typeof makeAdminApi>

// The system console (system ADMIN); delegated scopes use makeAdminApi(base) too.
export const globalAdminApi = makeAdminApi('/admin')

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

// ── Delegated admin: project settings (project MANAGER; server-guarded) ──────
// Same shapes as the system admin console, scoped to one project. A project may
// bind only sets visible to it (global ∪ its workspace ∪ its own project-private).

export function projectAdminApi(wsId: string, projectId: string) {
  const base = `/workspaces/${wsId}/projects/${projectId}/admin`
  return {
    bindings: () => request<ProjectBinding>(`${base}/bindings`),
    bindingOptions: () => request<BindingOptions>(`${base}/binding-options`),
    // Full replacement of all four bindings; null = the system default set
    updateBindings: (bindings: ProjectBindings) =>
      request<ProjectBinding>(`${base}/bindings`, { method: 'PATCH', body: JSON.stringify(bindings) }),
  }
}

// ── Delegated admin: workspace settings (workspace OWNER/ADMIN; server-guarded) ──
// The catalog/set consoles use makeAdminApi('/workspaces/{ws}/admin'); this covers
// the binding matrix for every project in the workspace.

export function workspaceAdminApi(wsId: string) {
  const base = `/workspaces/${wsId}/admin`
  return {
    matrix: () => request<ProjectBinding[]>(`${base}/projects`),
    bindingOptions: () => request<BindingOptions>(`${base}/binding-options`),
    updateProjectBindings: (projectId: string, bindings: ProjectBindings) =>
      request<ProjectBinding>(`${base}/projects/${projectId}/bindings`, {
        method: 'PATCH', body: JSON.stringify(bindings),
      }),
  }
}

// ── Admin users (system ADMIN only; server-guarded) ──────────────────────────
// Accounts are created without a password/email; the response carries a
// one-time setup link (/reset-password?token=) the admin hands over.

export const adminUsers = {
  list: (opts?: { page?: number; size?: number }) =>
    request<Page<AdminUser>>(`/admin/users?page=${opts?.page ?? 0}&size=${opts?.size ?? 50}`),
  create: (payload: { email: string; displayName: string; systemRole: 'ADMIN' | 'USER' }) =>
    request<{ user: AdminUser; setupLink: string }>('/admin/users', {
      method: 'POST', body: JSON.stringify(payload),
    }),
  regenerateSetupLink: (id: string) =>
    request<{ setupLink: string }>(`/admin/users/${id}/setup-link`, { method: 'POST' }),
  update: (id: string, payload: { systemRole?: 'ADMIN' | 'USER'; status?: 'ACTIVE' | 'DISABLED' }) =>
    request<AdminUser>(`/admin/users/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
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
