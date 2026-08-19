import type {
  User, Workspace, Project, Issue, BoardIssues, Comment, Attachment, IssueHistoryEntry,
  Notification, WorkspaceMember, ProjectConfig,
  AdminStatus, AdminPriority, AdminIssueType, AdminWorkflow, AdminPrioritySet,
  AdminField, AdminFieldSet, AdminIssueTypeSet, FieldConfig, FieldType, FieldValue,
  ProjectBinding, BindingOptions, TransitionRule, UsageDetail, AdminUser, PendingInvite,
  SearchResultRow, SearchSchema, SavedFilter, Label, MergeLabelsResult, Component,
  Version, VersionUsage, BoardMode, ProjectDeliveryUpdate, Sprint, SprintState, BacklogView,
  SprintCompletionPreview, SprintCompletionResult, UnfinishedDisposition,
  Role, RoleScope, RolePermissionEntry, RoleAssignmentView, RoleUsage,
  PermissionCatalogEntry, ProjectRef, ProjectMember, MemberRemovalResult,
  ProjectAccessMode, ProjectAccessSettings, ProjectAccessImpact, ProjectDefaultRoleSettings,
  FlowReport, ReportInterval, CycleTimeReport, AgingReport,
  SprintBurnupReport, SprintMeasure, SprintReviewReport,
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

/**
 * The ProblemDetail extensions a **conflict** carries, and the ONLY order they
 * may be read in (HD-123 S4 / HD-136).
 *
 * `Retry-After` first, always: a 409 carrying that header is transient lock
 * contention and is fixed by re-sending the identical request. It deliberately
 * has **no** `errorType`, so a client that branches on the discriminator first
 * reads its absence as "no recovery I know about" — the one wrong answer for a
 * body that is only asking to be retried.
 *
 * Then, and only then, `errorType`. An unrecognised or absent value means
 * "render `detail`, offer no retry".
 */
export interface ConflictInfo {
  /** Seconds from the `Retry-After` header. Present ⇒ retry the identical request. */
  retryAfter?: number
  /**
   * `STRANDED_PROJECTS` · `STRANDED_BY_INHERITANCE` · `ADOPTION_BLOCKED` · `ADOPTION_ROLE_UNREADABLE` ·
   * `LAST_PROJECT_ADMIN_BULK` · `ROLE_IN_USE` · `SELF_HELD_ROLE` ·
   * `ROLE_LIMIT_REACHED`. Typed as an open string on purpose — the server may
   * add one, and an unknown code must degrade to "render `detail`".
   */
  errorType?: string
  /** Projects in the way — uncapped, ordered by key. `detail` names at most three. */
  projects?: ProjectRef[]
  /** Where the role is still in play — attached to a `ROLE_IN_USE` refusal. */
  usage?: RoleUsage
}

export class ApiResponseError extends Error implements ConflictInfo {
  // Present only for an HQL 422 (search/saved-filter validation) — lets the UI
  // underline the bad span and read errorType/field without re-parsing.
  hql?: HqlError
  // Present on a 409 from a "create by name" endpoint (labels, HD-30): the id of
  // the row that already holds the name, so the picker can attach the existing
  // label in ONE round-trip instead of re-listing the whole workspace.
  existingId?: string
  // ── Conflict discriminators (HD-123 S6). See ConflictInfo for the read order.
  retryAfter?: number
  errorType?: string
  projects?: ProjectRef[]
  usage?: RoleUsage
  constructor(
    public status: number,
    public detail: string,
    hql?: HqlError,
    existingId?: string,
    conflict?: ConflictInfo,
  ) {
    super(detail)
    this.hql = hql
    this.existingId = existingId
    this.retryAfter = conflict?.retryAfter
    this.errorType = conflict?.errorType
    this.projects = conflict?.projects
    this.usage = conflict?.usage
  }
}

/**
 * `errorType` / `projects` / `usage` off a ProblemDetail body.
 *
 * Deliberately tolerant: an `errorType` this build has never heard of is carried
 * through verbatim so the caller can fall back to rendering `detail`, and a
 * malformed extension is dropped rather than thrown on — a refusal must never
 * become a crash.
 */
function conflictOf(body: unknown, res: Response): ConflictInfo {
  const info: ConflictInfo = {}
  const header = res.headers.get('Retry-After')
  if (header) {
    const seconds = Number(header)
    if (Number.isFinite(seconds) && seconds >= 0) info.retryAfter = seconds
  }
  if (body && typeof body === 'object') {
    const b = body as Record<string, unknown>
    // The HQL 422 uses the same extension name for its own vocabulary; hqlErrorOf
    // claims those two values, so they never reach a conflict branch.
    if (typeof b.errorType === 'string'
        && b.errorType !== 'PARSE_ERROR' && b.errorType !== 'SEMANTIC_ERROR') {
      info.errorType = b.errorType
    }
    if (Array.isArray(b.projects)) info.projects = b.projects as ProjectRef[]
    if (b.usage && typeof b.usage === 'object') info.usage = b.usage as RoleUsage
  }
  return info
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
    let body: unknown = null
    try {
      body = await res.json()
      const b = body as Record<string, string | undefined>
      detail = b.detail ?? b.message ?? b.title ?? detail
      // Carry through the HQL 422 highlight fields (position/length/token/field/
      // errorType) so the search UI can underline the offending span.
      hql = hqlErrorOf(body)
      // ProblemDetail extension on a duplicate-name 409 (labels, HD-30).
      if (typeof b.existingId === 'string') existingId = b.existingId
    } catch { /* ignore */ }
    // Never surface an empty message — statusText is blank over HTTP/2, which
    // would render as a silent, invisible error in the UI.
    if (!detail) detail = `Request failed (${res.status})`
    // Conflict discriminators (HD-123 S6). Read off the HEADERS as well as the
    // body, because the one retryable 409 is identified by `Retry-After` alone.
    throw new ApiResponseError(res.status, detail, hql, existingId, conflictOf(body, res))
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

/**
 * The body of `PATCH /api/workspaces/{ws}` and of the preview that shares it
 * (HD-130, S7 §7.1) — every field optional and every one an independent
 * decision, so a body naming only `name` must not disturb the access mode.
 *
 * `defaultProjectRoleId` and `clearDefaultProjectRole` are the two accepted ways
 * of naming the same column and sending **both is a 422**, not a precedence
 * rule: two fields that mean different things must not be resolved by picking a
 * winner. Sending neither leaves the column alone.
 */
export interface UpdateWorkspacePayload {
  name?: string
  projectAccessMode?: ProjectAccessMode
  /** A PROJECT-scoped role of THIS workspace. Anything else is a 422. */
  defaultProjectRoleId?: string
  /** Write NULL — i.e. fall through to the built-in Contributor. */
  clearDefaultProjectRole?: boolean
}

/**
 * Workspace General (HD-130, S7 W3) — rename, the OPEN/STRICT switch and the
 * workspace-level default project role, gated on `workspace.edit`.
 *
 * Refusals worth rendering rather than swallowing: **403** carries the grant
 * ceiling's sentence, which *names the permission the actor lacks*; **409
 * `STRANDED_BY_INHERITANCE`** carries the projects the change would leave with no
 * administrator, and has **no adoption retry** — it is re-derived inside the
 * write's transaction, so it can arrive after a clean preview.
 *
 * A body whose every value already holds returns 200 without writing the row.
 */
export async function apiUpdateWorkspace(
  wsId: string, payload: UpdateWorkspacePayload,
): Promise<Workspace> {
  return request(`/workspaces/${wsId}`, { method: 'PATCH', body: JSON.stringify(payload) })
}

/** W1 — the one read behind Workspace settings → General. Needs `workspace.edit`. */
export async function apiGetProjectAccess(wsId: string): Promise<ProjectAccessSettings> {
  return request(`/workspaces/${wsId}/project-access`)
}

/**
 * W2 — **what would this change do?** Same body as the write, same guards,
 * persists nothing. POST because it carries a body, exactly as
 * `POST /roles/preview` does.
 *
 * The counts are **advisory** and carry `computedAt`: they describe a population
 * that is not the row being written, so nothing here is a guarantee. Re-fetch on
 * opening the confirm dialog and never render a preview older than that
 * interaction. A ceiling failure surfaces as an ordinary **403**, never as a
 * "would fail" field in a 200 body.
 */
export async function apiPreviewProjectAccess(
  wsId: string, payload: UpdateWorkspacePayload,
): Promise<ProjectAccessImpact> {
  return request(`/workspaces/${wsId}/project-access/preview`, {
    method: 'POST', body: JSON.stringify(payload),
  })
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

export interface CreateProjectPayload {
  name: string
  key: string
  description?: string
  /**
   * The delivery capabilities chosen on the creation picker (HD-105). Optional
   * on the wire — a create without it gets the same lean defaults server-side —
   * but the SPA always sends it, because the picker exists to make the choice
   * explicit rather than inherited.
   *
   * `preset` is DERIVED and **rejected** with a 400, which is why it is absent
   * from `ProjectDeliveryUpdate`: a client can never post back what it read.
   */
  delivery?: ProjectDeliveryUpdate
}

export async function apiCreateProject(wsId: string, payload: CreateProjectPayload): Promise<Project> {
  return request(`/workspaces/${wsId}/projects`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export async function apiGetProject(wsId: string, projectId: string): Promise<Project> {
  return request(`/workspaces/${wsId}/projects/${projectId}`)
}

export async function apiUnarchiveProject(wsId: string, projectId: string): Promise<void> {
  return request(`/workspaces/${wsId}/projects/${projectId}/unarchive`, { method: 'POST' })
}

/**
 * Partial project update. Gated server-side by `requireProjectCurator` (project
 * MANAGER *or* workspace OWNER/ADMIN) since HD-22 §3.2 — the same predicate the
 * settings area already checks client-side.
 *
 * `delivery` (HD-102) carries the three delivery capabilities, each independently
 * optional: `{ delivery: { releases: true } }` turns releases on and touches
 * nothing else. They are a presentation switch, never a permission — no endpoint
 * behaves differently because of them (Rule A, §5.1) — and switching one off
 * never destroys, clears or moves data.
 *
 * `preset` is DERIVED and **rejected** on write (400), so it is absent from
 * `ProjectDeliveryUpdate`: a client can never echo back the `delivery` object it
 * read from a GET.
 *
 * `boardMode` (HD-27) is the deprecated top-level mirror of `delivery.board`,
 * still accepted; sending both with DIFFERENT values is a 400. New code sends
 * `delivery` only.
 */
export async function apiUpdateProject(
  wsId: string,
  projectId: string,
  payload: Partial<{
    name: string
    description: string
    /** @deprecated send `delivery: { board }` instead. */
    boardMode: BoardMode
    delivery: ProjectDeliveryUpdate
  }>,
): Promise<Project> {
  return request(`/workspaces/${wsId}/projects/${projectId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  })
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
  /**
   * HD-22 — issues committed to this sprint. A foreign sprint id simply matches
   * nothing (never an error, never a leak). Mutually exclusive with `noSprint`
   * — sending both is a 400, so `appendIssueFilters` lets `sprintId` win.
   */
  sprintId?: string
  /** HD-22 — only issues with no sprint (the ranked backlog). */
  noSprint?: boolean
}

export type LabelMatch = 'any' | 'all'

/**
 * The sprint half of an issue write, as a MUTUALLY EXCLUSIVE pair (HD-22 §4.4).
 *
 * `sprintId` (move into that sprint) and `clearSprint` (return to the ranked
 * backlog) answer the same question, so sending both is a **400** — the server
 * refuses rather than silently letting one win. Modelling it as a union with
 * `never` makes the illegal payload a COMPILE error at every call site instead of
 * a runtime 400 the user has to read:
 *
 *     { sprintId: id }                 // ok
 *     { clearSprint: true }            // ok
 *     { sprintId: id, clearSprint: true }   // Type error — as intended
 */
export type SprintTargetPatch =
  | { sprintId?: string; clearSprint?: never }
  | { sprintId?: never; clearSprint?: boolean }

/**
 * Runtime twin of the type above, for the one thing types can't cover: a payload
 * assembled from `unknown`/`any` or widened through a spread. It throws BEFORE
 * the request, so the bug surfaces at its origin instead of as a server 400.
 */
function assertSingleSprintTarget(payload: SprintTargetPatch, endpoint: string): void {
  const p = payload as { sprintId?: string; clearSprint?: boolean }
  if (p.sprintId && p.clearSprint) {
    throw new Error(
      `${endpoint}: sprintId and clearSprint are mutually exclusive (the server answers 400)`)
  }
}

function appendIssueFilters(params: URLSearchParams, filters?: IssueListFilters) {
  if (!filters) return
  if (filters.statusId) params.set('statusId', filters.statusId)
  if (filters.assigneeId) params.set('assigneeId', filters.assigneeId)
  if (filters.priorityId) params.set('priorityId', filters.priorityId)
  if (filters.componentId) params.set('componentId', filters.componentId)
  if (filters.fixVersionId) params.set('fixVersionId', filters.fixVersionId)
  // `sprintId` and `noSprint` together are a 400 — never send both, and never
  // let a stale `noSprint` sneak alongside an explicit sprint selection.
  if (filters.sprintId) params.set('sprintId', filters.sprintId)
  else if (filters.noSprint) params.set('noSprint', 'true')
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
  // the same version may appear in both (a regression introduced and fixed in 2.4.0);
  // sprintId commits the new issue to a sprint (HD-22) — unknown/foreign = 422,
  // a COMPLETED sprint = 422; omitted = the ranked backlog (new issues land at
  // the BOTTOM of it — filing is not a priority statement);
  // storyPoints is the native estimate — 0…999, at most 2 decimals (422 otherwise)
  payload: { title: string; typeId: string; statusId: string; priorityId?: string; description?: string; assigneeId?: string; dueDate?: string; parentId?: string; labelIds?: string[]; componentId?: string; fixVersionIds?: string[]; affectsVersionIds?: string[]; sprintId?: string; storyPoints?: number; fields?: Record<string, FieldValue> }
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
  // ([] clears that role), absent = unchanged — exactly like labelIds (HD-32);
  // sprintId moves the issue into a sprint and clearSprint: true returns it to the
  // backlog (HD-22) — the rank is PRESERVED either way; sending both is a 400;
  // storyPoints sets the native estimate and clearStoryPoints: true unsets it
  // (unestimated ≠ 0), both writing one `storyPoints` history row when they change
  payload: UpdateIssuePayload
): Promise<Issue> {
  assertSingleSprintTarget(payload, 'PATCH issue')
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  })
}

/**
 * The issue PATCH body. Everything is optional; `sprintId`/`clearSprint` are the
 * one pair that cannot both be present (see `SprintTargetPatch`).
 */
export type UpdateIssuePayload = Partial<{ title: string; typeId: string; statusId: string; priorityId: string; description: string; assigneeId: string; dueDate: string; parentId: string; clearAssignee: boolean; clearDueDate: boolean; clearParent: boolean; labelIds: string[]; componentId: string; clearComponent: boolean; fixVersionIds: string[]; affectsVersionIds: string[]; storyPoints: number; clearStoryPoints: boolean; fields: Record<string, FieldValue | null>; version: number }> & SprintTargetPatch

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

/**
 * Invite by email; the invite is bound to the address. Returns `{ message }` (201).
 *
 * **Name the role with `roleId`.** The legacy `role` KEY still works but can only
 * ever address a built-in, so it cannot express one of the workspace's own roles
 * — and exactly one of the two must be present (neither and both are alike a
 * 422). Refusals: 403 for the grant ceiling (the detail names the offending
 * permission) and for OWNER, which is never invitable, not even by an owner.
 */
export async function apiInviteWorkspaceMember(
  wsId: string,
  payload: { email: string; roleId: string },
): Promise<{ message: string }> {
  return request(`/workspaces/${wsId}/invites`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/**
 * Change a member's workspace role. Re-sending the role they already hold is an
 * accepted no-op, so a form may submit its current value.
 *
 * Refusals worth distinct copy: **403** for the grant ceiling (checked against
 * the target's CURRENT role as well as the new one) and for the Owner guardrail;
 * **409** for the last-Owner invariant; **422** for a role id that cannot be
 * assigned here.
 */
export async function apiUpdateWorkspaceMemberRole(
  wsId: string, userId: string, roleId: string,
): Promise<WorkspaceMember> {
  return request(`/workspaces/${wsId}/members/${userId}`, {
    method: 'PATCH',
    body: JSON.stringify({ roleId }),
  })
}

/**
 * Remove a member from the **workspace** — not their account, and not their
 * other workspaces.
 *
 * **Two success shapes, and the difference is the point.** 204 when the removal
 * granted the caller nothing (every ordinary removal, and a flagged one that
 * turned out to strand nothing) — resolves `null`. **200** when it granted the
 * caller the built-in Team lead role in the projects it took over — resolves
 * `{ adoptedProjects }`. That body exists so a durable grant is visible to the
 * person who caused it; surface it rather than swallowing it.
 *
 * `adoptStrandedProjects` is **consent, not an instruction**: the server
 * recomputes the set inside the transaction, so it is exactly what is about to be
 * stranded *now*, never the array an earlier 409 returned. Show the user that
 * list; do not assume it is what they will get.
 *
 * Conflicts are read via `ConflictInfo` — `Retry-After` first, then `errorType`.
 */
export async function apiRemoveWorkspaceMember(
  wsId: string, userId: string, adoptStrandedProjects = false,
): Promise<MemberRemovalResult | null> {
  const qs = adoptStrandedProjects ? '?adoptStrandedProjects=true' : ''
  const body = await request<MemberRemovalResult | undefined>(
    `/workspaces/${wsId}/members/${userId}${qs}`, { method: 'DELETE' })
  return body ?? null
}

// ── Roles & permissions — HD-123 ──────────────────────────────────────────────

/**
 * The permission catalog — **static product metadata**: no workspace context, no
 * tenant data, the same body for every caller on a given version. Fetch it once
 * and cache it for the session.
 *
 * Drive the role editor from THIS, never from a hard-coded list: the catalog
 * grows between releases and the endpoint is always in sync with what the server
 * enforces.
 */
export async function apiPermissionCatalog(): Promise<PermissionCatalogEntry[]> {
  return request('/permissions')
}

/** Body of `PATCH …/roles/{id}` — every field optional, `permissions` a FULL replacement. */
export interface UpdateRolePayload {
  name?: string
  description?: string
  /**
   * Replaces the whole set (an empty array is a real value — the built-in Viewer
   * is exactly that). Never a delta.
   */
  permissions?: RolePermissionEntry[]
  /**
   * The optimistic-concurrency token from the role you loaded. **Send it.** A
   * permission set is exactly the state where a silent lost update is
   * unacceptable: two admins each unticking one box would otherwise restore each
   * other's, and the loser's revocation would look like it had been applied.
   */
  version?: number
}

/**
 * Workspace roles — built-in templates plus the workspace's own.
 *
 * **Creation is duplication.** There is no `POST /roles`, deliberately: the grant
 * ceiling is a *subset* rule, so a role assembled from an empty checklist can
 * assign nobody. Duplication always starts from a superset; "from scratch" is
 * duplicating Viewer, which is then a named, deliberate act.
 *
 * Reading is open to any member (a People tab renders role names for everybody);
 * everything else needs `workspace.role.manage`, and so does `includeUsage`.
 */
export const rolesApi = {
  list: (wsId: string, opts?: { scope?: RoleScope; includeUsage?: boolean }) => {
    const params = new URLSearchParams()
    if (opts?.scope) params.set('scope', opts.scope)
    // Only ever sent when the caller holds `workspace.role.manage` — it turns an
    // otherwise-open endpoint into a 403 for everyone else.
    if (opts?.includeUsage) params.set('includeUsage', 'true')
    const qs = params.toString()
    return request<Role[]>(`/workspaces/${wsId}/roles${qs ? `?${qs}` : ''}`)
  },
  get: (wsId: string, roleId: string) =>
    request<Role>(`/workspaces/${wsId}/roles/${roleId}`),
  /** The only way to create a role. `name` defaults to "<source> copy"; the key is server-generated. */
  duplicate: (wsId: string, sourceRoleId: string, payload: { name?: string; description?: string } = {}) =>
    request<Role>(`/workspaces/${wsId}/roles/${sourceRoleId}/duplicate`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  update: (wsId: string, roleId: string, payload: UpdateRolePayload) =>
    request<Role>(`/workspaces/${wsId}/roles/${roleId}`, {
      method: 'PATCH', body: JSON.stringify(payload),
    }),
  /**
   * 409 `ROLE_IN_USE` (carrying the whole `usage` object, so the remap dialog
   * renders straight off the refusal) when the role still has holders and no
   * `reassignToRoleId`; 409 `SELF_HELD_ROLE` when the caller holds it; 409
   * `LAST_PROJECT_ADMIN_BULK` when the move would demote every administrator of
   * the listed projects at once.
   */
  remove: (wsId: string, roleId: string, reassignToRoleId?: string) => {
    // Encoded, like every other query this client builds — the values are
    // server-issued ids today, which is a fact about the caller and not a
    // property of the function.
    const qs = reassignToRoleId ? `?${new URLSearchParams({ reassignToRoleId })}` : ''
    return request<void>(`/workspaces/${wsId}/roles/${roleId}${qs}`, { method: 'DELETE' })
  },
  usage: (wsId: string, roleId: string) =>
    request<RoleUsage>(`/workspaces/${wsId}/roles/${roleId}/usage`),
  /**
   * Dry-run the assignment block for a set being composed — **persists nothing**,
   * and runs the same validation a real write runs, so an unknown key, a
   * wrong-scope permission or an illegal `ownOnly` surfaces as the identical 422
   * while the checkboxes are being ticked rather than at Save.
   */
  preview: (wsId: string, payload: { scope: RoleScope; permissions: RolePermissionEntry[] }) =>
    request<{ assignment: RoleAssignmentView }>(`/workspaces/${wsId}/roles/preview`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
}

// ── Project members — HD-123 ──────────────────────────────────────────────────
// The SPA had never called these before S6. Listing is open to any WORKSPACE
// member (the assignee picker and mention autocomplete need it); every write
// needs `project.member.manage` IN THIS PROJECT — a workspace Owner/Admin who is
// not a member here does not hold it, which is why the workspace-level removal
// has an adoption flow at all.

export const projectMembersApi = {
  list: (wsId: string, projectId: string) =>
    request<ProjectMember[]>(`/workspaces/${wsId}/projects/${projectId}/members`),
  /**
   * Add a workspace member to the project. `roleId` names any assignable
   * PROJECT-scoped role — including one of the workspace's own, and including the
   * built-in Viewer, which the legacy `role` key could never store.
   */
  add: (wsId: string, projectId: string, payload: { userId: string; roleId: string }) =>
    request<ProjectMember>(`/workspaces/${wsId}/projects/${projectId}/members`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  /**
   * Change a member's project role in one call. It exists because the alternative
   * was remove-then-add: two checks, the member dropping onto the workspace
   * default in between, and a window in which the project genuinely had no
   * administrator. 409 when it would demote the last administrator (a demotion
   * strands a project with no row removed at all); skipped for a promotion, which
   * cannot strand anything.
   */
  updateRole: (wsId: string, projectId: string, userId: string, roleId: string) =>
    request<ProjectMember>(`/workspaces/${wsId}/projects/${projectId}/members/${userId}`, {
      method: 'PATCH', body: JSON.stringify({ roleId }),
    }),
  /** 409 when the target is the project's last administrator — add another one first. */
  remove: (wsId: string, projectId: string, userId: string) =>
    request<void>(`/workspaces/${wsId}/projects/${projectId}/members/${userId}`, { method: 'DELETE' }),
}

/**
 * A project's **default access** (HD-130, S7 §7.2) — the role every workspace
 * member inherits here when they have no `project_members` row.
 *
 * A separate endpoint from `PATCH /projects/{p}` on purpose: that one is gated on
 * `project.edit`, and this write is membership authority, gated on
 * **`project.member.manage`**. Folding a second-permission field into a
 * single-permission PATCH is how a gate gets forgotten.
 *
 * **Exactly one of `roleId` / `inherit` per write** — neither or both is a 422,
 * never a precedence rule. `inherit: true` writes NULL, meaning "follow the
 * workspace default", which is a real choice and not an absence: "this project
 * deliberately follows the workspace" and "this project happens to name the same
 * role the workspace does" diverge the moment the workspace default moves.
 *
 * The ceiling here has **no §4 escape**: the actor who may promote a colleague to
 * Project admin still gets a 403 naming `issue.delete` when they aim that role at
 * the default, because a default's target is everyone including the actor.
 */
export const projectDefaultRoleApi = {
  get: (wsId: string, projectId: string) =>
    request<ProjectDefaultRoleSettings>(`/workspaces/${wsId}/projects/${projectId}/default-role`),
  /** Answers the full `Project`, so the People card re-renders straight from the write. */
  set: (wsId: string, projectId: string, payload: { roleId: string } | { inherit: true }) =>
    request<Project>(`/workspaces/${wsId}/projects/${projectId}/default-role`, {
      method: 'PATCH', body: JSON.stringify(payload),
    }),
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

// ── Sprints, backlog ranking — HD-22 ──────────────────────────────────────────
// Project-scoped iterations. READS (list/get/completion-preview/backlog view)
// need project membership only; the LIFECYCLE (create/rename/dates/start/
// complete/delete) needs project MANAGER *or* workspace OWNER/ADMIN (the
// server's `requireProjectCurator`) and 403s otherwise; putting issues INTO a
// sprint and dragging them (`addIssues`/`removeIssue`/`apiRankIssue`) is
// ordinary planning work any issue-editor may do. A non-member of the workspace
// gets 404 (never 403 — no existence leak).
//
// Error shapes the UI has to handle:
//   400 — `sprintId` AND `clearSprint` both set · a rank request with neither an
//         anchor nor a sprint change · an oversized `issueIds`
//   409 — start when not FUTURE / another sprint already ACTIVE · complete when
//         not ACTIVE · delete an ACTIVE sprint · delete one that still holds
//         issues without `force` · stale `version` · STALE RANK ANCHORS ("the
//         list changed — refresh") · duplicate name · project archived
//   422 — unknown/foreign sprint or anchor id · anchor not in the target section
//         · anchor == the moved issue · assign to a COMPLETED sprint · a
//         complete-target that is not a FUTURE sprint of this project ·
//         `endAt <= startAt` · `storyPoints` out of range / > 2 decimals ·
//         the open-sprint cap
//
// Exported as `sprintsApi` (not `sprints`) so pages can keep a local `sprints`
// variable for the fetched rows.

/** Blank/absent name → the server names it "Sprint {sequence}". */
export interface CreateSprintPayload {
  name?: string
  goal?: string
  /** Plan only — this does NOT start the sprint. ISO-8601 UTC. */
  startAt?: string
  endAt?: string
}

/** Partial — only the keys present change; the `clear*` flags unset the dates. */
export interface UpdateSprintPayload {
  name?: string
  goal?: string
  startAt?: string
  endAt?: string
  clearStartAt?: boolean
  clearEndAt?: boolean
}

/** All optional: an empty body means "start now, end in `default-sprint-length-days`". */
export interface StartSprintPayload {
  startAt?: string
  endAt?: string
  /** Optional last-minute goal; omitted = leave whatever is stored. */
  goal?: string
}

export interface CompleteSprintPayload {
  moveUnfinishedTo: UnfinishedDisposition
  /** Required iff `moveUnfinishedTo === 'SPRINT'`; must be a FUTURE sprint of this project. */
  targetSprintId?: string
}

export interface AddIssuesToSprintPayload {
  issueIds: string[]
  /** Default BOTTOM. */
  position?: 'TOP' | 'BOTTOM'
}

/**
 * Placement is computed SERVER-side from the neighbour anchors — the client
 * never sends a rank value (`position` isn't even exposed on `IssueResponse`).
 * Both anchors absent ⇒ append to the end of the target section.
 * `version` is optional: present = checked (409 on stale), absent = last-drag-wins.
 */
export type RankIssuePayload = {
  afterIssueId?: string
  beforeIssueId?: string
  version?: number
  /**
   * `sprintId` moves into that sprint, `clearSprint` returns to the ranked
   * backlog — mutually exclusive (400), enforced by `SprintTargetPatch` at
   * compile time and by `assertSingleSprintTarget` at runtime.
   */
} & SprintTargetPatch

export const sprintsApi = {
  /**
   * ALWAYS paged (COMPLETED sprints accumulate for years). Order: ACTIVE first,
   * then FUTURE by sequence ASC, then COMPLETED by sequence DESC. `state` is a
   * repeatable filter; omitting it returns every state.
   */
  list: (
    wsId: string,
    projectId: string,
    opts?: { state?: SprintState | SprintState[]; page?: number; size?: number },
  ) => {
    const params = new URLSearchParams()
    const states = opts?.state === undefined
      ? []
      : Array.isArray(opts.state) ? opts.state : [opts.state]
    for (const s of states) params.append('state', s)
    if (opts?.page !== undefined) params.set('page', String(opts.page))
    if (opts?.size !== undefined) params.set('size', String(opts.size))
    const qs = params.toString()
    return request<Page<Sprint>>(`/workspaces/${wsId}/projects/${projectId}/sprints${qs ? `?${qs}` : ''}`)
  },
  get: (wsId: string, projectId: string, id: string) =>
    request<Sprint>(`/workspaces/${wsId}/projects/${projectId}/sprints/${id}`),
  create: (wsId: string, projectId: string, payload: CreateSprintPayload = {}) =>
    request<Sprint>(`/workspaces/${wsId}/projects/${projectId}/sprints`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  update: (wsId: string, projectId: string, id: string, payload: UpdateSprintPayload) =>
    request<Sprint>(`/workspaces/${wsId}/projects/${projectId}/sprints/${id}`, {
      method: 'PATCH', body: JSON.stringify(payload),
    }),
  // Starting an EMPTY sprint is allowed by design — the UI warns, the API doesn't.
  start: (wsId: string, projectId: string, id: string, payload: StartSprintPayload = {}) =>
    request<Sprint>(`/workspaces/${wsId}/projects/${projectId}/sprints/${id}/start`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  // Read-only for any project member — the dialog never guesses its counters.
  completionPreview: (wsId: string, projectId: string, id: string) =>
    request<SprintCompletionPreview>(
      `/workspaces/${wsId}/projects/${projectId}/sprints/${id}/completion-preview`),
  // DONE issues KEEP their sprint (that is its record of what it delivered);
  // everything else moves to the chosen destination with its rank preserved.
  // A double-click is a 409 by design, never a silent second destructive move.
  complete: (wsId: string, projectId: string, id: string, payload: CompleteSprintPayload) =>
    request<SprintCompletionResult>(`/workspaces/${wsId}/projects/${projectId}/sprints/${id}/complete`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  addIssues: (wsId: string, projectId: string, id: string, payload: AddIssuesToSprintPayload) =>
    request<Sprint>(`/workspaces/${wsId}/projects/${projectId}/sprints/${id}/issues`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
  removeIssue: (wsId: string, projectId: string, id: string, issueId: string) =>
    request<void>(`/workspaces/${wsId}/projects/${projectId}/sprints/${id}/issues/${issueId}`,
      { method: 'DELETE' }),
  // 409 for an ACTIVE sprint ("complete it first") and for a FUTURE/COMPLETED
  // one that still holds issues unless `force` — then their sprint is nulled and
  // their rank preserved.
  remove: (wsId: string, projectId: string, id: string, force = false) =>
    request<void>(`/workspaces/${wsId}/projects/${projectId}/sprints/${id}${force ? '?force=true' : ''}`,
      { method: 'DELETE' }),
}

/** Extra knobs of the planning view on top of the shared issue filters. */
export interface BacklogViewOptions extends IssueListFilters {
  /**
   * Default false: a done, unranked issue is planning noise. Sprint sections
   * ALWAYS include DONE issues regardless — that is the sprint's record.
   */
  includeDone?: boolean
}

/**
 * The whole planning view in one aggregate: ACTIVE-first sprint sections plus
 * the ranked backlog, each with whole-section stats and HD-79 truncation
 * metadata. Sections are ALSO independently refreshable through
 * `apiListIssuesPaged({ sprintId })` / `({ noSprint: true })` — see
 * `useBacklogView` in `components/sprints.tsx`.
 */
export async function apiGetBacklogView(
  wsId: string,
  projectId: string,
  opts?: BacklogViewOptions,
): Promise<BacklogView> {
  const params = new URLSearchParams()
  if (opts?.includeDone) params.set('includeDone', 'true')
  appendIssueFilters(params, opts)
  const qs = params.toString()
  return request(`/workspaces/${wsId}/projects/${projectId}/backlog${qs ? `?${qs}` : ''}`)
}

/**
 * Re-rank an issue (and optionally move it between sections) in ONE request.
 * The moved issue is addressed by NUMBER (the established issue addressing);
 * anchors and the sprint travel as ids (the established reference convention).
 */
export async function apiRankIssue(
  wsId: string,
  projectId: string,
  number: number,
  payload: RankIssuePayload,
): Promise<Issue> {
  assertSingleSprintTarget(payload, 'POST rank')
  return request(`/workspaces/${wsId}/projects/${projectId}/issues/${number}/rank`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
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

// ── Reports — HD-5 / HD-28 ────────────────────────────────────────────────────
// Project-scoped, read-only, `Cache-Control: private, max-age=60` (match it with
// a 60s `staleTime`; see `flowReportKey`). Reading needs project membership and
// NOTHING else — there is no `report.view` permission and there is not meant to
// be one (reports-proposal §4.2), so no caller of these gates on a permission.
//
// Error shapes the UI has to render as real sentences, not a generic banner:
//   • 400 — `from > to`, a window wider than `app.reports.max-window-days`, or
//     a date outside 1970-01-01…2200-12-31. Every one of them NAMES the bound it
//     measured against, because a window is never silently clamped; surface
//     `detail` verbatim (see `FlowReportPage`). All three share one shape, so
//     they share one rendering path.
//   • 404 — workspace/project not visible (non-member and non-existent alike).
//   • 429 — past this caller's report budget (`app.reports.requests-per-minute`).
//     A retryable throttle, not a fault: nothing was computed and a retry after
//     `Retry-After` succeeds (`ApiResponseError.retryAfter` carries it). It is
//     spent per principal BEFORE the project is resolved, which makes it the one
//     place on this API where 429 precedes 404 — so a 429 says nothing about
//     whether the caller can see the project, and no UI may imply otherwise.
//
// Unknown/foreign filter ids are NOT an error: the queries are project-scoped
// first, so an id that does not exist here simply matches nothing and yields an
// empty series. Answering otherwise would make this an existence oracle. The
// caller is not left guessing, though — `meta.unmatchedFilters` names any filter
// that matched no issue in the project, so an all-zero chart is never ambiguous.

/**
 * Every parameter is optional, and **sending none is the intended first call**:
 * the server then reports the last `min(90, app.reports.max-window-days)` days,
 * weekly and unfiltered. Deriving the default from the cap is what makes a
 * parameterless request always succeed, including on an instance whose operator
 * capped windows below 90 — so do not "help" by filling a window in here. There
 * is exactly one definition of the default and it is the server's.
 *
 * Dates are ISO `YYYY-MM-DD` and the window INCLUDES both endpoints. Empty
 * strings are dropped rather than sent, so an unset window really is absent.
 */
export interface FlowReportParams {
  from?: string
  to?: string
  interval?: ReportInterval
  typeId?: string
  componentId?: string
  labelId?: string
}

/**
 * The finished-work half of the cycle-time report (HD-138). Same window and the
 * same three filters as the flow report — and deliberately **no `measure`**: one
 * response carries `cycleDays` AND `leadDays` plus both percentile pairs, so the
 * page's toggle is a re-render rather than a second round trip. Keeping the
 * measure out of the request is also what keeps it out of the cache key, so
 * flipping it back and forth never refetches.
 */
export interface CycleTimeReportParams {
  from?: string
  to?: string
  typeId?: string
  componentId?: string
  labelId?: string
}

export const reportsApi = {
  flow: (wsId: string, projectId: string, params: FlowReportParams = {}) => {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) if (v) qs.set(k, v)
    const q = qs.toString()
    return request<FlowReport>(
      `/workspaces/${wsId}/projects/${projectId}/reports/flow${q ? `?${q}` : ''}`)
  },

  cycleTime: (wsId: string, projectId: string, params: CycleTimeReportParams = {}) => {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) if (v) qs.set(k, v)
    const q = qs.toString()
    return request<CycleTimeReport>(
      `/workspaces/${wsId}/projects/${projectId}/reports/cycle-time${q ? `?${q}` : ''}`)
  },

  /**
   * Aging work in progress — **the current state, so it takes no parameters at
   * all**: no window, no filters. That asymmetry with `cycleTime` is a fact the
   * page has to state rather than hide; a reader who set a type filter above
   * would otherwise read these columns as filtered too.
   */
  aging: (wsId: string, projectId: string) =>
    request<AgingReport>(`/workspaces/${wsId}/projects/${projectId}/reports/aging`),

  /**
   * The sprint burn-up (R4). This client always SENDS `sprintId`, even though
   * the endpoint defaults to the ACTIVE sprint: the page has to resolve a sprint
   * to render its picker anyway, and a request that names the sprint is the one
   * whose URL still means the same report tomorrow, when a different sprint is
   * the active one.
   *
   * **`measure` IS on the wire here**, unlike the cycle-time page's toggle: the
   * two series are different sums over different rows, so the server computes
   * the one that was asked for. It therefore joins the cache key, and flipping
   * the toggle is a refetch.
   *
   * 404 when the sprint is not in this project (or the project is not visible).
   * Never a capability — `board = KANBAN` answers exactly the same, because a
   * hidden control is not a permission (delivery-paths Rule A).
   */
  sprintBurnup: (
    wsId: string, projectId: string,
    params: { sprintId?: string; measure?: SprintMeasure } = {},
  ) => {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) if (v) qs.set(k, v)
    const q = qs.toString()
    return request<SprintBurnupReport>(
      `/workspaces/${wsId}/projects/${projectId}/reports/sprint-burnup${q ? `?${q}` : ''}`)
  },

  /**
   * The sprint review record (R4) — five lists, no chart, and deliberately **no
   * measure**: it always reports a count AND a point sum, because that is how a
   * retrospective reads them ("18 of 23 issues, 41 of 55 points").
   */
  sprintReview: (wsId: string, projectId: string, params: { sprintId?: string } = {}) => {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) if (v) qs.set(k, v)
    const q = qs.toString()
    return request<SprintReviewReport>(
      `/workspaces/${wsId}/projects/${projectId}/reports/sprint-review${q ? `?${q}` : ''}`)
  },
}
