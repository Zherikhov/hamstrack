import type {
  User, Workspace, Project, Issue, BoardIssues, Comment, Attachment, IssueHistoryEntry,
  Notification, WorkspaceMember, ProjectConfig,
  AdminStatus, AdminPriority, AdminIssueType, AdminWorkflow, AdminPrioritySet,
  AdminField, AdminFieldSet, AdminIssueTypeSet, FieldConfig, FieldType, FieldValue,
  ProjectBinding, BindingOptions, TransitionRule, UsageDetail, AdminUser, PendingInvite,
  SearchResultRow, SearchSchema, SavedFilter, Label, MergeLabelsResult, Component,
  Version, VersionUsage,
} from './types'
import { useAuthStore } from './auth'

const BASE = '/api'

// The machine-readable extra fields the search backend attaches to a 422
// HqlProblemDetail — position/length underline the offending span in the HQL
// input; errorType/token/field describe the failure. Read off ApiResponseError.hql.
export interface HqlError {
  errorType: 'PARSE_ERROR' | 'SEMANTIC_ERROR'
  position?: number
  length?: number
  token?: string | null
  field?: string
}

export class ApiResponseError extends Error {
  // Present only for an HQL 422 (search/saved-filter validation) — lets the UI
  // underline the bad span and read errorType/field without re-parsing.
  hql?: HqlError
  // Present on a 409 from a "create by name" endpoint (labels, HD-30): the id of
  // the row that already holds the name, so the picker can attach the existing
  // label in ONE round-trip instead of re-listing the whole workspace.
  existingId?: string
  constructor(public status: number, public detail: string, hql?: HqlError, existingId?: string) {
    super(detail)
    this.hql = hql
    this.existingId = existingId
  }
}

// A body carrying errorType is an HqlProblemDetail — pull the highlight fields.
function hqlErrorOf(body: unknown): HqlError | undefined {
  if (!body || typeof body !== 'object') return undefined
  const b = body as Record<string, unknown>
  if (b.errorType !== 'PARSE_ERROR' && b.errorType !== 'SEMANTIC_ERROR') return undefined
  return {
    errorType: b.errorType as HqlError['errorType'],
    position: typeof b.position === 'number' ? b.position : undefined,
    length: typeof b.length === 'number' ? b.length : undefined,
    token: typeof b.token === 'string' ? b.token : null,
    field: typeof b.field === 'string' ? b.field : undefined,
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
    let hql: HqlError | undefined
    let existingId: string | undefined
    try {
      const body = await res.json()
      detail = body.detail ?? body.message ?? body.title ?? detail
      // Carry through the HQL 422 highlight fields (position/length/token/field/
      // errorType) so the search UI can underline the offending span.
      hql = hqlErrorOf(body)
      // ProblemDetail extension on a duplicate-name 409 (labels, HD-30).
      if (typeof body.existingId === 'string') existingId = body.existingId
    } catch { /* ignore */ }
    // Never surface an empty message — statusText is blank over HTTP/2, which
    // would render as a silent, invisible error in the UI.
    if (!detail) detail = `Request failed (${res.status})`
    throw new ApiResponseError(res.status, detail, hql, existingId)
  }

  return res.status === 204 ? (undefined as T) : res.json()
}

// Single-flight refresh (HD-50). When the 30-min access token expires, several
// requests typically 401 at once (TanStack Query refetching multiple queries,
// e.g. on window-focus). The backend ROTATES the refresh token on every
// /auth/refresh (deletes the old one, issues a new pair), so if each 401 fired
// its own refresh, the first would rotate the cookie and every other concurrent
// refresh would arrive with the now-deleted token → 401 → clear() → a spurious
// logout "every ~30 minutes". Deduping so all concurrent callers await ONE
// in-flight refresh means a single rotation and one new token for everyone.
let refreshInFlight: Promise<boolean> | null = null

function tryRefresh(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const res = await fetch(`${BASE}/auth/refresh`, { method: 'POST', credentials: 'include' })
        if (!res.ok) return false
        const data = await res.json()
        useAuthStore.getState().setToken(data.accessToken)
        return true
      } catch {
        return false
      }
    })().finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
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

// Board: the capped list (no `size` — the kanban loads the whole board, but the
// server bounds it to `cap` issues and reports truncation, HD-79). Returns the
// wrapper object `{ issues, truncated, totalAvailable, cap }`, not a bare array.
export async function apiListIssues(
  wsId: string,
  projectId: string,
  filters?: IssueListFilters
): Promise<BoardIssues> {
  const params = new URLSearchParams()
  appendIssueFilters(params, filters)
  const qs = params.toString()
  return request(`/workspaces/${wsId}/projects/${projectId}/issues${qs ? `?${qs}` : ''}`)
}

// Server-side issue-list filters, shared by the board (capped) and the backlog
// (paged) — both hit the same endpoint. `labelIds` is repeated as `labelId=`
// once per label and AND-ed with the rest; `labelMatch` picks OR (any, default)
// vs AND (all) *within* the label dimension (HD-30 §3.6). These are query
// params, NOT client-side chips: a classification filter must search the whole
// project, not just the first `cap` issues the board loaded.
export interface IssueListFilters {
  statusId?: string
  assigneeId?: string
  priorityId?: string
  labelIds?: string[]
  labelMatch?: LabelMatch
  /** HD-31 — one component id, AND-ed with everything above (server-side). */
  componentId?: string
  /**
   * HD-32 — issues carrying a FIX link to this version. An AFFECTS link does
   * NOT match: "ships in 2.4.0" and "is broken in 2.4.0" are different questions.
   */
  fixVersionId?: string
}

export type LabelMatch = 'any' | 'all'

function appendIssueFilters(params: URLSearchParams, filters?: IssueListFilters) {
  if (!filters) return
  if (filters.statusId) params.set('statusId', filters.statusId)
  if (filters.assigneeId) params.set('assigneeId', filters.assigneeId)
  if (filters.priorityId) params.set('priorityId', filters.priorityId)
  if (filters.componentId) params.set('componentId', filters.componentId)
  if (filters.fixVersionId) params.set('fixVersionId', filters.fixVersionId)
  for (const id of filters.labelIds ?? []) params.append('labelId', id)
  // Only meaningful with 2+ labels; the server defaults to `any`.
  if (filters.labelMatch === 'all' && (filters.labelIds?.length ?? 0) > 1) params.set('labelMatch', 'all')
}

// Backlog: paginated; excludeDone drops DONE-category statuses server-side.
export async function apiListIssuesPaged(
  wsId: string,
  projectId: string,
  opts: IssueListFilters & { page?: number; size?: number; excludeDone?: boolean }
): Promise<Page<Issue>> {
  const params = new URLSearchParams()
  params.set('page', String(opts.page ?? 0))
  params.set('size', String(opts.size ?? 50))
  if (opts.excludeDone) params.set('excludeDone', 'true')
  appendIssueFilters(params, opts)
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
  // fields keyed by field id — value shapes per field type (see FieldValue);
  // labelIds attaches workspace labels (HD-30) — omitted/absent = none;
  // componentId sets the project component (HD-31) — when that component has
  // autoAssign + a lead and no assigneeId is sent, the server assigns the lead;
  // fixVersionIds/affectsVersionIds link project versions per role (HD-32) —
  // the same version may appear in both (a regression introduced and fixed in 2.4.0)
  payload: { title: string; typeId: string; statusId: string; priorityId?: string; description?: string; assigneeId?: string; dueDate?: string; parentId?: string; labelIds?: string[]; componentId?: string; fixVersionIds?: string[]; affectsVersionIds?: string[]; fields?: Record<string, FieldValue> }
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
  // null can't be distinguished from "not sent" server-side); parentId sets/changes the parent;
  // labelIds REPLACES the whole label set when present ([] clears it), absent = unchanged;
  // componentId sets the component and clearComponent: true unsets it (HD-31) — changing a
  // component on update NEVER reassigns the issue (auto-assign is create-time only);
  // fixVersionIds/affectsVersionIds REPLACE their whole role's set when present
  // ([] clears that role), absent = unchanged — exactly like labelIds (HD-32)
  payload: Partial<{ title: string; typeId: string; statusId: string; priorityId: string; description: string; assigneeId: string; dueDate: string; parentId: string; clearAssignee: boolean; clearDueDate: boolean; clearParent: boolean; labelIds: string[]; componentId: string; clearComponent: boolean; fixVersionIds: string[]; affectsVersionIds: string[]; fields: Record<string, FieldValue | null>; version: number }>
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

// Invite by email. The invite is bound to the address; role is the WorkspaceRole.
// Returns { message } on success (201). No endpoint exists yet to list pending
// invites or to change/remove members — that's a backend-dependent follow-up.
export async function apiInviteWorkspaceMember(
  wsId: string,
  payload: { email: string; role: 'MEMBER' | 'ADMIN' }
): Promise<{ message: string }> {
  return request(`/workspaces/${wsId}/invites`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

// ── Labels — HD-30 ────────────────────────────────────────────────────────────
// Workspace-scoped; a non-member gets 404 (never 403 — no existence leak).
// Reading and creating are open to any member (self-serve tagging); curation
// (rename/recolor beyond your own labels, archive, merge, delete) is OWNER/ADMIN
// and 403s otherwise. A duplicate name (case-insensitive, archived rows
// included) throws an ApiResponseError with .status === 409 and .existingId set
// — attach that label instead of re-listing.
//
// Exported as `labelsApi` (not `labels`) so pages can keep a local `labels`
// variable for the fetched rows without shadowing the client.

export interface UpsertLabelPayload {
  name?: string
  color?: string
  description?: string
}

export const labelsApi = {
  list: (wsId: string, opts?: { includeArchived?: boolean; withUsage?: boolean }) => {
    const params = new URLSearchParams()
    if (opts?.includeArchived) params.set('includeArchived', 'true')
    if (opts?.withUsage) params.set('withUsage', 'true')
    const qs = params.toString()
    return request<Label[]>(`/workspaces/${wsId}/labels${qs ? `?${qs}` : ''}`)
  },
  create: (wsId: string, payload: { name: string; color?: string; description?: string }) =>
    request<Label>(`/workspaces/${wsId}/labels`, { method: 'POST', body: JSON.stringify(payload) }),
  // Partial: only the keys present change (null/undefined = leave as is).
  update: (wsId: string, id: string, payload: UpsertLabelPayload) =>
    request<Label>(`/workspaces/${wsId}/labels/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  archive: (wsId: string, id: string) =>
    request<Label>(`/workspaces/${wsId}/labels/${id}/archive`, { method: 'POST' }),
  unarchive: (wsId: string, id: string) =>
    request<Label>(`/workspaces/${wsId}/labels/${id}/unarchive`, { method: 'POST' }),
  // `sourceIds` are absorbed INTO `id`; the sources are deleted afterwards.
  merge: (wsId: string, id: string, sourceIds: string[]) =>
    request<MergeLabelsResult>(`/workspaces/${wsId}/labels/${id}/merge`, {
      method: 'POST', body: JSON.stringify({ sourceIds }),
    }),
  // 409 when the label is attached to issues unless `force` — then the
  // attachments are dropped too. There is no remap: `merge` covers that case.
  remove: (wsId: string, id: string, force = false) =>
    request<void>(`/workspaces/${wsId}/labels/${id}${force ? '?force=true' : ''}`, { method: 'DELETE' }),
  usage: (wsId: string, id: string) =>
    request<{ issueCount: number }>(`/workspaces/${wsId}/labels/${id}/usage`),
}

// ── Components — HD-31 ────────────────────────────────────────────────────────
// Project-scoped; reading needs project membership, every write needs project
// MANAGER *or* workspace OWNER/ADMIN (the server's `requireProjectCurator`), so
// a workspace admin who is not a project member curates them too. A non-member
// of the workspace gets 404 (never 403 — no existence leak).
//
// Error shapes worth handling in the UI: 409 duplicate name in the project
// (including a name held by an ARCHIVED component — there is no `existingId`
// here, unlike labels, because nothing is created on the fly), 409 while the
// project is archived, 409 delete-in-use without `force`, 422 for a lead who is
// not a workspace member and for `autoAssign: true` with no lead.
//
// Exported as `componentsApi` (not `components`) so pages can keep a local
// `components` variable for the fetched rows.

export interface CreateComponentPayload {
  name: string
  description?: string
  leadId?: string
  autoAssign?: boolean
}

/** Partial — only the keys present change; `clearLead` unsets the lead. */
export interface UpdateComponentPayload {
  name?: string
  description?: string
  leadId?: string
  clearLead?: boolean
  autoAssign?: boolean
}

export const componentsApi = {
  list: (wsId: string, projectId: string, opts?: { includeArchived?: boolean; withUsage?: boolean }) => {
    const params = new URLSearchParams()
    if (opts?.includeArchived) params.set('includeArchived', 'true')
    if (opts?.withUsage) params.set('withUsage', 'true')
    const qs = params.toString()
    // Ordered `lower(name) ASC` server-side.
    return request<Component[]>(`/workspaces/${wsId}/projects/${projectId}/components${qs ? `?${qs}` : ''}`)
  },
  get: (wsId: string, projectId: string, id: string) =>
    request<Component>(`/workspaces/${wsId}/projects/${projectId}/components/${id}`),
  create: (wsId: string, projectId: string, payload: CreateComponentPayload) =>
    request<Component>(`/workspaces/${wsId}/projects/${projectId}/components`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  update: (wsId: string, projectId: string, id: string, payload: UpdateComponentPayload) =>
    request<Component>(`/workspaces/${wsId}/projects/${projectId}/components/${id}`, {
      method: 'PATCH', body: JSON.stringify(payload),
    }),
  archive: (wsId: string, projectId: string, id: string) =>
    request<Component>(`/workspaces/${wsId}/projects/${projectId}/components/${id}/archive`, { method: 'POST' }),
  unarchive: (wsId: string, projectId: string, id: string) =>
    request<Component>(`/workspaces/${wsId}/projects/${projectId}/components/${id}/unarchive`, { method: 'POST' }),
  // 409 when issues still carry it unless `force` — then it is nulled on every
  // one of them (no remap in MVP; archiving is the "keep it" path).
  remove: (wsId: string, projectId: string, id: string, force = false) =>
    request<void>(`/workspaces/${wsId}/projects/${projectId}/components/${id}${force ? '?force=true' : ''}`,
      { method: 'DELETE' }),
  usage: (wsId: string, projectId: string, id: string) =>
    request<{ issueCount: number }>(`/workspaces/${wsId}/projects/${projectId}/components/${id}/usage`),
}

// ── Versions — HD-32 ──────────────────────────────────────────────────────────
// Project-scoped release targets. Reads need project membership; every write
// needs project MANAGER *or* workspace OWNER/ADMIN (`requireProjectCurator`), so
// a workspace admin curates a project's releases without being a member. A
// non-member of the workspace gets 404 (never 403 — no existence leak).
//
// Error shapes the UI has to handle: 409 duplicate name in the project
// (case-insensitive, ARCHIVED names included), 409 releasing an already-released
// version / un-releasing an unreleased one, 409 on ANY management call while the
// project is archived, 409 delete-in-use without `force`/`remapToId`, and 422 for
// a bad `moveUnresolvedToVersionId` / `remapToId` target (self, released,
// archived, or another project's).
//
// Exported as `versionsApi` (not `versions`) so pages can keep a local
// `versions` variable for the fetched rows.

export interface CreateVersionPayload {
  name: string
  description?: string
  /** `YYYY-MM-DD`. */
  releaseDate?: string
}

/** Partial — only the keys present change; `clearReleaseDate` unsets the date. */
export interface UpdateVersionPayload {
  name?: string
  description?: string
  releaseDate?: string
  clearReleaseDate?: boolean
}

/** Both optional: no date + none stored → the server dates it today. */
export interface ReleaseVersionPayload {
  releaseDate?: string
  /** Re-points the FIX links of every non-DONE issue to another unreleased,
   *  non-archived version of the same project. */
  moveUnresolvedToVersionId?: string
}

export const versionsApi = {
  // Ordered server-side: unreleased first, then release date ascending with
  // undated last, then name. `includeReleased` defaults to true.
  list: (wsId: string, projectId: string, opts?: { includeArchived?: boolean; includeReleased?: boolean }) => {
    const params = new URLSearchParams()
    if (opts?.includeArchived) params.set('includeArchived', 'true')
    if (opts?.includeReleased === false) params.set('includeReleased', 'false')
    const qs = params.toString()
    return request<Version[]>(`/workspaces/${wsId}/projects/${projectId}/versions${qs ? `?${qs}` : ''}`)
  },
  get: (wsId: string, projectId: string, id: string) =>
    request<Version>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}`),
  create: (wsId: string, projectId: string, payload: CreateVersionPayload) =>
    request<Version>(`/workspaces/${wsId}/projects/${projectId}/versions`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  update: (wsId: string, projectId: string, id: string, payload: UpdateVersionPayload) =>
    request<Version>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}`, {
      method: 'PATCH', body: JSON.stringify(payload),
    }),
  // Releasing twice is a 409, not an idempotent no-op — a double-click must not
  // silently re-run a destructive `moveUnresolvedToVersionId`.
  release: (wsId: string, projectId: string, id: string, payload: ReleaseVersionPayload = {}) =>
    request<Version>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}/release`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  // Clears `released`/`releasedAt` but PRESERVES `releaseDate` — it stays the plan.
  unrelease: (wsId: string, projectId: string, id: string) =>
    request<Version>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}/unrelease`, { method: 'POST' }),
  archive: (wsId: string, projectId: string, id: string) =>
    request<Version>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}/archive`, { method: 'POST' }),
  unarchive: (wsId: string, projectId: string, id: string) =>
    request<Version>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}/unarchive`, { method: 'POST' }),
  // 409 while issues still link to it unless `force` (drop the links) or
  // `remapToId` (re-point BOTH roles at another version of the same project).
  remove: (wsId: string, projectId: string, id: string, opts?: { force?: boolean; remapToId?: string }) => {
    const params = new URLSearchParams()
    if (opts?.force) params.set('force', 'true')
    if (opts?.remapToId) params.set('remapToId', opts.remapToId)
    const qs = params.toString()
    return request<void>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}${qs ? `?${qs}` : ''}`,
      { method: 'DELETE' })
  },
  usage: (wsId: string, projectId: string, id: string) =>
    request<VersionUsage>(`/workspaces/${wsId}/projects/${projectId}/versions/${id}/usage`),
}

// ── Advanced search (HQL) — HD-25 ─────────────────────────────────────────────
// Workspace-scoped; a non-member gets 404. A bad query POST throws an
// ApiResponseError with .status === 422 and a populated .hql (position/length/
// token/field/errorType) so the input can underline the offending span.

export async function apiSearch(
  wsId: string,
  payload: { query: string; page?: number; size?: number }
): Promise<Page<SearchResultRow>> {
  return request(`/workspaces/${wsId}/search`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export async function apiSearchSchema(wsId: string): Promise<SearchSchema> {
  return request(`/workspaces/${wsId}/search/schema`)
}

// Bounded prefix typeahead for user-valued fields (assignee/reporter). `q` ≤ 100.
export async function apiSearchSuggest(
  wsId: string, field: string, q: string
): Promise<{ field: string; suggestions: { label: string; value: string }[] }> {
  const params = new URLSearchParams({ field })
  if (q) params.set('q', q)
  return request(`/workspaces/${wsId}/search/suggest?${params.toString()}`)
}

// ── Saved filters — HD-26 ─────────────────────────────────────────────────────
// Own + shared, workspace-scoped. Create/update/delete are owner-only server-side
// (a non-owner PATCH/DELETE 404s). Create: 422 invalid HQL (.hql set), 409 dup name.

export const savedFilters = {
  list: (wsId: string) =>
    request<SavedFilter[]>(`/workspaces/${wsId}/filters`),
  get: (wsId: string, id: string) =>
    request<SavedFilter>(`/workspaces/${wsId}/filters/${id}`),
  create: (wsId: string, payload: { name: string; hql: string; shared?: boolean }) =>
    request<SavedFilter>(`/workspaces/${wsId}/filters`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  update: (wsId: string, id: string, payload: { name?: string; hql?: string; shared?: boolean }) =>
    request<SavedFilter>(`/workspaces/${wsId}/filters/${id}`, {
      method: 'PATCH', body: JSON.stringify(payload),
    }),
  remove: (wsId: string, id: string) =>
    request<void>(`/workspaces/${wsId}/filters/${id}`, { method: 'DELETE' }),
  // Delete-warning hook (empty in MVP — no board/report consumes a filter yet).
  usage: (wsId: string, id: string) =>
    request<{ usages: { type: string; id: string; name: string }[] }>(
      `/workspaces/${wsId}/filters/${id}/usage`
    ),
}
