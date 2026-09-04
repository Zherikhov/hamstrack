import type {
  User, Workspace, Project, Issue, BoardIssues, Comment, Attachment, IssueHistoryEntry,
  Notification, WorkspaceMember, ProjectConfig,
  AdminStatus, AdminPriority, AdminIssueType, AdminWorkflow, AdminPrioritySet,
  AdminField, AdminFieldSet, AdminIssueTypeSet, FieldConfig, FieldType, FieldValue,
  ProjectBinding, BindingOptions, TransitionRule, UsageDetail, AdminUser, PendingInvite,
  SearchResultRow, SearchSchema, SavedFilter, Label, MergeLabelsResult, Component,
  Version, VersionUsage, BoardMode, ProjectDeliveryUpdate, Sprint, SprintState, BacklogView,
  BacklogSectionResponse,
  SprintCompletionPreview, SprintCompletionResult, UnfinishedDisposition,
  Role, RoleScope, RolePermissionEntry, RoleAssignmentView, RoleUsage,
  PermissionCatalogEntry, ProjectRef, ProjectMember, MemberRemovalResult,
  ProjectAccessMode, ProjectAccessSettings, ProjectAccessImpact, ProjectDefaultRoleSettings,
  WorkspaceInvite,
  FlowReport, ReportInterval, CycleTimeReport, AgingReport,
  SprintBurnupReport, SprintMeasure, SprintReviewReport, VelocityReport,
  InsightsDimension, InsightsMeasure, InsightsResponse,
  WorkspaceStorageSummary, WorkspaceStorageByProject,
} from './types'
import { useAuthStore } from './auth'

const BASE = '/api'

// `ApiResponseError` (and the two shapes it carries) live in the leaf module
// `apiError.ts` and are re-exported here, so every existing `from '../api'`
// call site is unchanged. The split exists because `queryClient.ts` has to test
// for the error type, and importing `api.ts` from there closed the cycle
// queryClient → api → auth → queryClient. apiError.ts says what that cost.
import {
  ApiResponseError, STORAGE_QUOTA_EXCEEDED, TOO_MANY_IN_FLIGHT, EXPENSIVE_SURFACE_BUSY,
} from './apiError'
import type { HqlError, ConflictInfo, StorageQuotaRefusal } from './apiError'
export { ApiResponseError, STORAGE_QUOTA_EXCEEDED, TOO_MANY_IN_FLIGHT, EXPENSIVE_SURFACE_BUSY }
export type { HqlError, ConflictInfo, StorageQuotaRefusal }

/**
 * The longest wait this client will carry out of a `Retry-After` header, in seconds.
 *
 * **A mirror, like everything in `lib/limits.ts`**: it is one day because that is
 * `MailThrottlePolicy.MAX_CEILING_WINDOW`, the widest window any throttle policy in this
 * product may declare (enforced at bean creation, so a policy asking for more fails to
 * start). Nothing mechanical keeps the two sides equal — widen the Java constant and this
 * one has to move with it, or a legitimate refusal starts arriving here truncated.
 *
 * It is a **ceiling on rendering**, not a claim about our limiters: see `conflictOf`.
 */
const RETRY_AFTER_MAX_SECONDS = 86_400

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
    // Clamped, because every consumer of this number RENDERS it — in a sentence
    // ("try again in N seconds") or as a countdown — and the header is written by
    // servers we do not all control: our own limiters, but also any proxy or load
    // balancer in front of them. `0` becomes "in 0 seconds" (an instruction to do
    // the thing that was just refused), `2.5` becomes "2.5 seconds", and a value in
    // the millions renders verbatim. So this is a GUARD ON WHAT CAN BE RENDERED
    // — against a garbled, absurd or hostile header — and not a statement about how
    // long this product's own windows are. Round UP, never understating the wait;
    // floor at one second, so it is never a no-op; and cap at
    // `RETRY_AFTER_MAX_SECONDS`, which is the widest window any policy here is
    // ALLOWED to declare rather than the widest one configured today. The HTTP-date
    // form of `Retry-After` is still dropped by `Number.isFinite` — no Hamstrack
    // endpoint sends it, and a wrong parse would be rendered to a user as fact.
    //
    // The cap was 3600 until HD-183, under a comment claiming an hour was "longer
    // than any window this product actually has". It was not: the invitation
    // recipient-volume refusal computes its `Retry-After` from a deadline inside
    // `MailThrottlePolicy.MAX_CEILING_WINDOW` (a DAY), so it can legitimately ask for
    // most of 86400 — and clamping that to 3600 UNDERSTATES the wait, the one
    // direction the round-up above exists to forbid. It was latent only because the
    // single screen that can receive such a refusal (`WorkspacePeoplePage`) renders
    // the server's `detail` sentence and never this number, i.e. the safety rested
    // on one call site's discipline instead of on the clamp. A bound justified by
    // what our windows happen to be goes stale silently; one justified by what a
    // window may be does not.
    //
    // What the wider cap deliberately does NOT do is make a long wait legible: this
    // field is seconds by contract, read by five call sites (three of which drive a
    // per-second countdown), so "about a day" is a decision for a rendering layer,
    // not for the parser. Every door that renders the raw number today is fed by a
    // short window; a screen that can receive a long one prints `detail`, which
    // already names the wait in words.
    const seconds = Number(header)
    if (Number.isFinite(seconds) && seconds >= 0) {
      info.retryAfter = Math.min(Math.max(Math.ceil(seconds), 1), RETRY_AFTER_MAX_SECONDS)
    }
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
    if (info.errorType === STORAGE_QUOTA_EXCEEDED) info.storage = storageRefusalOf(b)
  }
  return info
}

/**
 * The four byte figures on a `STORAGE_QUOTA_EXCEEDED` 409 (HD-191 §8.3) — **all
 * four or none**.
 *
 * A partial read is refused rather than filled in, because every consumer of
 * this shape does arithmetic with it ("used of quota", "this file needed") and a
 * missing member coerced to `0` would render a confidently wrong sentence about
 * somebody's data. Absent it, the caller falls back to the server's own `detail`,
 * which already says the whole thing.
 */
function storageRefusalOf(b: Record<string, unknown>): StorageQuotaRefusal | undefined {
  const keys = ['quotaBytes', 'usedBytes', 'availableBytes', 'fileBytes'] as const
  if (!keys.every(k => typeof b[k] === 'number' && Number.isFinite(b[k] as number))) return undefined
  return {
    quotaBytes: b.quotaBytes as number,
    usedBytes: b.usedBytes as number,
    availableBytes: b.availableBytes as number,
    fileBytes: b.fileBytes as number,
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

/**
 * The `errors` ProblemDetail extension a validation 400 carries — a
 * `{field: message}` map built by `GlobalExceptionHandler.handleValidation`
 * (HD-171 §11). Every non-string value is dropped rather than coerced: this map
 * is rendered to a user, and `String(someObject)` next to an input field is
 * worse than no message at all.
 */
function fieldErrorsOf(body: unknown): Record<string, string> | undefined {
  if (!body || typeof body !== 'object') return undefined
  const raw = (body as Record<string, unknown>).errors
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const out: Record<string, string> = {}
  for (const [field, message] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof message === 'string') out[field] = message
  }
  return Object.keys(out).length > 0 ? out : undefined
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

/**
 * Facts about a call that only its caller knows. There is exactly one today, and
 * it exists so the single in-flight retry below can be given to the two POSTs
 * that are **reads**.
 */
interface RequestOptions {
  /**
   * This call changes nothing on the server, whatever its HTTP method says.
   *
   * Set by `POST …/search` and `POST …/search/insights` alone: those are POSTs
   * because a query travels in a body, not because they write. Everything else
   * earns a retry by being a GET or does not earn one — notably **saved-filter
   * create/update/delete**, which sit on the same budget as the search reads
   * (validating a filter's HQL costs what `/search/schema` costs) and are the
   * reason this flag is opt-in rather than "anything on the expensive surface".
   */
  readShaped?: boolean
}

/**
 * The longest this client will sit on the hinted wait before its one automatic
 * retry.
 *
 * The refusal it applies to carries `Retry-After: 1`, and that 1 means *the
 * obstacle is a request that ends shortly* — **not** *the window has one second
 * left*; the lock-contention 409 uses the same value for the same reason. So the
 * hint is honoured as written, and capped, because this wait parks a query the
 * user is watching and `conflictOf` will faithfully carry a proxy's `86400`
 * (it is clamped for RENDERING, and rendering a day is harmless where sleeping
 * one is not).
 */
const IN_FLIGHT_RETRY_MAX_WAIT_MS = 2_000

/**
 * Whether a failed call may be sent **once** more.
 *
 * **This branches on `errorType`, never on the status, and that is the whole
 * design.** Three different refusals now share `429` on the expensive-read
 * surface (HD-182 / ADR-0030) and only one of them is the caller's own conduct:
 *
 *  • `TOO_MANY_IN_FLIGHT` — this caller's own requests occupy their share. It
 *    clears when one of them finishes, i.e. in about a second, and a page that
 *    mounts several parallel queries can provoke it while behaving perfectly.
 *    Retried once, here.
 *  • `EXPENSIVE_SURFACE_BUSY` — the instance's expensive-read share is full. The
 *    caller may hold none of it, so retrying is not a remedy; it is more load on
 *    the resource that is already scarce. **Never** retried.
 *  • the per-minute budget 429 — no `errorType` at all. Its window is up to a
 *    minute away, so a retry cannot help and re-spends the budget that just
 *    refused. **Never** retried. (`queryClient.retryQuery` refuses every 429 for
 *    this reason too, which is what keeps the retry below the ONLY one.)
 *
 * A fourth 429 must therefore be a deliberate edit of the comparison below: an
 * unknown or absent `errorType` falls through to "do not retry", so a refusal
 * this build has never heard of inherits the safe answer instead of whatever a
 * status-based branch happened to do for it.
 */
function mayRetryOnce(error: unknown, init: RequestInit, opts: RequestOptions): boolean {
  if (!(error instanceof ApiResponseError)) return false
  if (error.errorType !== TOO_MANY_IN_FLIGHT) return false
  // Idempotence is the second half: the refusal is raised in an interceptor, so
  // nothing ran and nothing was written — but a re-send is still a second write
  // ATTEMPT, and the only calls given one here are the ones that could not write
  // even if they arrived twice.
  const method = (init.method ?? 'GET').toUpperCase()
  return opts.readShaped === true || method === 'GET' || method === 'HEAD'
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/**
 * The one door every JSON call in this module goes through (the two that fetch
 * BYTES — an attachment, a report CSV — use `authFetch` directly and get none of
 * what follows).
 *
 * The retry is deliberately the narrowest thing that works — one `await`, one
 * re-send, no backoff, no queue, no framework — and it is **not** invisible: the
 * second attempt is the last one, and whatever it throws (the same refusal, a
 * different one, a 500) propagates unchanged, so a refusal that survives the
 * retry reaches the UI exactly as it would have without one.
 */
async function request<T>(
  path: string, init: RequestInit = {}, opts: RequestOptions = {},
): Promise<T> {
  try {
    return await attempt<T>(path, init)
  } catch (error) {
    if (!mayRetryOnce(error, init, opts)) throw error
    const hinted = (error as ApiResponseError).retryAfter ?? 1
    await sleep(Math.min(hinted * 1000, IN_FLIGHT_RETRY_MAX_WAIT_MS))
    return attempt<T>(path, init)
  }
}

async function attempt<T>(path: string, init: RequestInit): Promise<T> {
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
    throw new ApiResponseError(
      res.status, detail, hql, existingId, conflictOf(body, res), fieldErrorsOf(body),
    )
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
  /**
   * Where a user writes to ask for their account to be deleted (HD-193 §10).
   * **Empty string when the operator has not configured one** — the DC default.
   * The Account page renders its deletion section either way (§9.3): a hidden
   * affordance is unreachable for exactly the operators who did not know the
   * property existed.
   */
  privacyContactEmail: string
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

/**
 * Asks for a password-reset link to be mailed to `email`.
 *
 * **The answer is uniform on purpose and says nothing about the address.** The
 * server spends the per-address mail ceiling *before* it looks the account up, and
 * records it either way, then always answers `200 {message}`. So the **body** is
 * identical for a registered and an unregistered address, and — because that
 * ceiling is spent before the lookup — a request it refused is indistinguishable
 * from one it allowed: the refusal sends no mail and still answers the same 200,
 * so not even "refused" can be read as "registered". A caller therefore renders its
 * own neutral sentence and never branches on the response.
 *
 * **The two branches are NOT equal in duration, and no caller may claim they are.**
 * The known branch pays a `SecureRandom` token, a SHA-256 hash and an `INSERT` into
 * `password_resets` that the unknown branch never does; on top of that,
 * `FailedEmailWriter`'s javadoc records a larger asymmetry it lists as still open —
 * an address-correlated park, bounded only by the unset Hikari connection-timeout,
 * on the known branch alone. What makes sampling impractical here is the
 * **per-address ceiling** (a handful of requests per window, counted across
 * everybody who asks), not equal work. That is a weaker claim than "constant time",
 * and it is the true one — do not upgrade it in a comment, a document or a test
 * failure message, where the next reader will take it as settled.
 *
 * The one refusal that does reach a caller is the per-IP `429` on `/api/auth/*`,
 * which carries `Retry-After` (`ApiResponseError.retryAfter`) and is genuinely
 * retryable — waiting is the whole remedy. It is about this browser's request rate
 * and not about the address, which is the only reason it is safe to render.
 */
export async function apiForgotPassword(email: string) {
  return request<{ message: string }>('/auth/forgot-password', {
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

// ── Workspace storage (HD-191) ───────────────────────────────────────────────

/**
 * `GET /api/workspaces/{ws}/storage` — the summary, readable by **any member**
 * (§6.6). No permission beyond membership, because it discloses nothing the
 * upload door does not already hand the same person in a refusal body.
 *
 * **Deliberately unbudgeted** (§9.2): one primary-key read plus two properties,
 * cheaper than the handler mapping that routes it, and it is the number shown
 * beside the upload control — starving it would hide the quota from exactly the
 * person about to meet it. So no 429 here. Tenancy is the usual shape: an
 * unknown workspace and a non-member both answer **404**, never 403.
 */
export async function apiGetWorkspaceStorage(wsId: string): Promise<WorkspaceStorageSummary> {
  return request(`/workspaces/${wsId}/storage`)
}

/**
 * `GET /api/workspaces/{ws}/storage/projects` — the per-project breakdown,
 * gated on `workspace.edit` (**403** for a proven member without it, **404** for
 * a non-member).
 *
 * **429 + `Retry-After`** — this one IS on the expensive-read surface, on the
 * reports registration: it is a grouped aggregate over every attachment row in
 * the workspace, i.e. O(tenant content), which is that budget's denomination. So
 * it can meet any refusal that surface declares — the per-minute report budget,
 * `TOO_MANY_IN_FLIGHT`, `EXPENSIVE_SURFACE_BUSY`, or a later addition — and which
 * one arrived is read off `ApiResponseError.errorType`, never off the status.
 * Being a GET, it is retried once by `request()` for the one of those that clears
 * in about a second, and never for the others.
 *
 * Its sibling summary is on none of them, and the asymmetry is safe in the
 * direction that matters: a caller refused here who falls back to the cheap read
 * gets *less* information, never a way around the bound.
 */
export async function apiGetWorkspaceStorageByProject(wsId: string): Promise<WorkspaceStorageByProject> {
  return request(`/workspaces/${wsId}/storage/projects`)
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

/**
 * Upload one file. Refusals a caller must tell apart (HD-191 §6.4, in the order
 * the server applies them): **400** empty · **413** over the per-file limit ·
 * **415** disallowed extension · **404** tenancy · **403** missing
 * `attachment.create` · **409** archived project · **429** budget · **409**
 * storage quota.
 *
 * The two 409s are told apart by `errorType`, and only the quota one carries it
 * ({@link STORAGE_QUOTA_EXCEEDED}, with `ApiResponseError.storage`). The two
 * 429s are told apart by `detail` alone — one is a request count, the other a
 * byte rate — and both carry `Retry-After`, which the quota 409 deliberately
 * does not: waiting frees no bytes.
 */
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
 *
 * **429 — a throttle, not a fault (HD-190).** The invite mailer carries ceilings
 * of its own (per-sender volume, a per-recipient cooldown, a global per-recipient
 * daily cap — and whatever a later release adds), and **which one refused is
 * legible only from the `detail` sentence**: there is no `errorType`, no distinct
 * status, nothing machine-readable that tells them apart. So branch on the status
 * and on `ApiResponseError.retryAfter` (seconds, off the `Retry-After` header),
 * and **never on the wording** — it is prose the server rewrites freely, and a
 * client that pattern-matches it breaks on a copy edit. Render `detail` verbatim:
 * every one of these refusals already names the wait in words, so recomputing a
 * duration from `retryAfter` can only contradict the sentence beside it.
 *
 * A refused invite **sends no mail and writes no invitation row**, so re-sending
 * the identical request after `Retry-After` is safe and is the entire remedy —
 * nothing has to be undone first, and the address the caller typed is the one
 * thing the refusal is asking them to send again, so a form must keep it.
 *
 * Unlike the search and report budgets, this one is spent **after** the workspace
 * is resolved (404 for a non-member, then 403/422/409, then the ceilings), so a
 * 429 from THIS endpoint does imply the caller is a member of the workspace it
 * names. Do not carry that reading to a budget spent before resolution — it is a
 * property of where the check sits, not of the status code.
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
 * **The workspace's own outstanding invitations** (HD-158) — every row of
 * `workspace_invites` for this workspace that has not been accepted, newest
 * first. A bare array; no envelope and no pagination, exactly like
 * `GET …/members`.
 *
 * Expired rows are **included**, carrying `status: 'EXPIRED'`, and must be
 * rendered rather than filtered out here: nothing in the product sweeps them,
 * they are withdrawable, and an invisible row is one no administrator can clear
 * and no future refusal can point at. Accepted rows never appear — the person is
 * in the roster and withdrawal could not affect them.
 *
 * **Gated on `workspace.member.manage`, which the caller must check BEFORE
 * calling.** The list is nothing but addresses, so a member without the
 * permission gets a 403 and must be shown no section at all — not an empty list
 * (a falsehood rendered on their screen) and not a count (disclosure with no
 * matching ability). Hold the query with `enabled: canManage` so that 403 is
 * never provoked; it exists for the API's sake, not the UI's.
 *
 * 404 for a non-member and for an unknown workspace alike — the usual tenancy
 * posture; 403 only for a *proven* member missing the permission.
 */
export async function apiListWorkspaceInvites(wsId: string): Promise<WorkspaceInvite[]> {
  return request(`/workspaces/${wsId}/invites`)
}

/**
 * Withdraw one invitation — **204**, and the row is hard-deleted. The emailed
 * link and accept-by-id then answer 404 by construction, and the invitee's own
 * list stops carrying it. Withdrawing an already-expired row is allowed (the
 * list offers the control on every row it shows, so it must work on every row it
 * shows).
 *
 * Two refusals, and they are **not** interchangeable:
 *
 *  • **404 — the invitation is already gone**, whether another administrator
 *    withdrew it a second ago, a member removal deleted it (HD-132), or the id
 *    never existed. Because withdrawal deletes, that state is physically
 *    identical to "never existed", so the API answers 404 rather than inventing
 *    a tombstone to make a second DELETE feel nicer. **The caller renders it as
 *    success**: the intent ("this invitation must not be acceptable") is already
 *    true, so there is nothing to report. Refetch the list and show nothing.
 *  • **409 `INVITE_ALREADY_ACCEPTED`** — lost the race against the invitee. The
 *    row is there and is not withdrawable, but its reader *can* act: the detail
 *    names the new member and points at People, which needs exactly the
 *    permission they just proved. Render `detail` verbatim.
 *
 * **Branch on the STATUS, never on the message.** The 404's `detail` is
 * deliberately the same sentence as the workspace-level 404 ("Workspace not
 * found") — an id is not an existence oracle — so the wording cannot tell the
 * two apart and was never meant to.
 */
export async function apiRevokeWorkspaceInvite(wsId: string, inviteId: string): Promise<void> {
  return request(`/workspaces/${wsId}/invites/${inviteId}`, { method: 'DELETE' })
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

// 429 on EVERY call below: the whole `…/projects/*/backlog/**` path is on the
// planning surface (HD-174), which carries a per-minute budget of its own AND a
// share of the expensive-read occupancy bound. So a planning read declares the
// same three refusals the search and filter surfaces do — the per-minute budget
// (no `errorType`), `TOO_MANY_IN_FLIGHT` and `EXPENSIVE_SURFACE_BUSY` — told
// apart by `errorType` and never by the status, plus whatever a later release
// adds. `mayRetryOnce` retries `TOO_MANY_IN_FLIGHT` once here, because these are
// GETs; the other two are never retried automatically, the budget because
// retrying it re-spends what just refused and the busy one because a retry is
// more load on the scarce resource rather than a remedy.
//
// **The caller's obligation, and it is specific to this surface** (§5.4): a 429
// on a SECTION refresh must not be answered by refetching the aggregate. The
// aggregate is a `12 + N`-statement read on one connection where the section is
// 11–12, so escalating would make a refusal of the cheap request provoke the
// expensive one. `useBacklogView` branches on it; `isThrottleRefusal` is the
// predicate.
//
// Both controls are spent in an interceptor, BEFORE the workspace or project is
// resolved, so a 429 here says nothing about whether either exists or whether
// the caller is a member — and a capability being off never changes it either.

/**
 * The whole planning view in one aggregate: ACTIVE-first sprint sections plus
 * the ranked backlog, each with whole-section stats and HD-79 truncation
 * metadata. Sections are ALSO independently refreshable, one at a time, through
 * `apiGetBacklogSection` — see `useBacklogView` in `components/sprints.tsx`.
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
 * ONE planning section, refreshed on its own (HD-96): `…/backlog/sections/backlog`
 * for the ranked backlog, `…/backlog/sections/{sprintId}` for a sprint.
 *
 * **No page and no size travel with a planning request, and that absence is the
 * fix.** The SPA used to refresh a section through `apiListIssuesPaged` asking for
 * `size = sectionCap`; `Paging` narrowed that to 100 and still answered 200, so a
 * section of 101–300 issues rendered whole and came back cut. Reading the cap off
 * the server's response instead of hardcoding it was the right instinct and did not
 * save it — a number the client holds is a number that can be wrong. With none on
 * the wire there is nothing to echo, nothing to clamp, and nothing to drift when an
 * operator retunes `app.agile.section-max-issues` in either direction.
 *
 * The response carries `sectionCap` / `bulkMoveCap` / `truncated` /
 * `totalAvailable` and whole-section `stats` under the aggregate's own field
 * names, so the caller patches it into a cached `BacklogView` verbatim.
 *
 * @param sprintId the sprint whose section to fetch, or `null` for the ranked backlog
 */
export async function apiGetBacklogSection(
  wsId: string,
  projectId: string,
  sprintId: string | null,
  opts?: BacklogViewOptions,
): Promise<BacklogSectionResponse> {
  const params = new URLSearchParams()
  // `includeDone` is a BACKLOG-section knob here exactly as it is on the aggregate:
  // a sprint's DONE issues are its record and always travel with it.
  if (sprintId === null && opts?.includeDone) params.set('includeDone', 'true')
  // Deliberately the aggregate's own filter helper, so the two surfaces can never
  // ask different questions — a refresh that filtered differently than the render
  // is this defect's own shape one layer up. The section is addressed by PATH, so
  // the two section-selecting filters are stripped: sending `sprintId`/`noSprint`
  // as well would be a second answer to a question the URL already settled.
  appendIssueFilters(params, { ...opts, sprintId: undefined, noSprint: undefined })
  const qs = params.toString()
  const path = sprintId === null ? 'backlog' : sprintId
  return request(
    `/workspaces/${wsId}/projects/${projectId}/backlog/sections/${path}${qs ? `?${qs}` : ''}`)
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
// 429 — `POST …/search`, `…/search/schema` and `…/search/suggest` are all on the
// expensive-read surface, and a refusal there can be ANY of the ones that surface
// declares (three today): this caller's per-minute search budget, `TOO_MANY_IN_FLIGHT`,
// `EXPENSIVE_SURFACE_BUSY` — and whatever a later release adds, which is why this
// says "any of them" rather than naming a set that goes stale. Tell them apart by
// `ApiResponseError.errorType`, never by the status; every one is a throttle rather
// than a fault, so nothing was computed and the identical request is legal to send
// again — but only ONE of them is worth sending again soon, and `request()` already
// does that one automatically (see `mayRetryOnce`). `retryAfter` carries the wait,
// which is a rolling window for the budget and "a request that ends shortly" for the
// other two. All of them are spent BEFORE the workspace is resolved, so a 429 here
// still says nothing about whether the caller can see it.

export async function apiSearch(
  wsId: string,
  payload: { query: string; page?: number; size?: number }
): Promise<Page<SearchResultRow>> {
  // A POST that reads: the query travels in a body because it is too big for a URL,
  // and re-sending it writes nothing — so it is eligible for the one in-flight retry.
  return request(`/workspaces/${wsId}/search`, {
    method: 'POST',
    body: JSON.stringify(payload),
  }, { readShaped: true })
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

/**
 * The Insights panel's one endpoint (HD-140, R6 — §2.6).
 *
 * **The dataset is the query, and nothing else.** There is no window, no project
 * id and no filter list: the panel aggregates exactly the rows the search box
 * would return, with page and sort dropped, which is what gives it a global
 * filter by construction and is the reason it replaces the gadget dashboard
 * rather than reimplementing it.
 *
 * **`measure` is on the wire even though both numbers come back on every row.**
 * The response carries `count` AND `points` for each slice and cell, so the
 * toggle is a re-render — but the measure is what the server *ranks* by when it
 * applies its two caps, so the same query under `POINTS` can ship a different
 * set of bars. It therefore joins the cache key, and changing it is a refetch.
 *
 * Errors are the search errors, because it IS the search query:
 *   • 422 — bad HQL, with `.hql` populated exactly as `apiSearch` fills it;
 *     also `slice === segment`, which the panel never offers (a diagonal is not
 *     a breakdown) but which a hand-edited URL can ask for.
 *   • 404 — workspace not visible (non-member and non-existent alike).
 *   • 429 — refused by one of the controls on the expensive-read surface, told
 *     apart by `ApiResponseError.errorType` and never by the status. Two of them
 *     are per-minute budgets, because this endpoint sits inside TWO
 *     registrations: it is bound to the reports limiter explicitly
 *     (`ReportRateLimitConfig`, because it does NOT live under `/reports`) and it
 *     also falls under the search path pattern. It spends both budgets, the lower
 *     configured value binds, and lowering either property lowers the panel. The
 *     explicit binding only looks redundant — deleting it would silently raise
 *     the panel's allowance to the search budget as a side effect. On top of
 *     those sits the occupancy bound (HD-182), whose two refusals —
 *     `TOO_MANY_IN_FLIGHT` and `EXPENSIVE_SURFACE_BUSY` — cost this request ONE
 *     permit even though it is behind both registrations. Whatever the count
 *     becomes, they are all throttles and never faults
 *     (`ApiResponseError.retryAfter` carries the wait), and exactly one of them
 *     is retried for the caller by `request()`.
 *
 * **A capability never changes the answer** (delivery-paths Rule A). The panel
 * omits the `sprint` slice when no visible project runs sprints, and the story
 * points measure when none estimates — reading `/search/schema`'s own `insights`
 * block, which narrows on exactly the terms its `fields` list does — but a link
 * that asks for either is sent as written and answered 200, because a hidden
 * control is not a permission.
 */
export async function apiSearchInsights(
  wsId: string,
  payload: {
    query: string
    measure: InsightsMeasure
    slice: InsightsDimension
    /** Omitted entirely when there is no colour dimension — never sent as ''. */
    segment?: InsightsDimension
  },
): Promise<InsightsResponse> {
  // Read-shaped, exactly like `apiSearch`: the query is the dataset, the POST is
  // only how it travels, and a second send aggregates the same rows again.
  return request(`/workspaces/${wsId}/search/insights`, {
    method: 'POST',
    body: JSON.stringify(payload),
  }, { readShaped: true })
}

// ── Saved filters — HD-26 ─────────────────────────────────────────────────────
// Own + shared, workspace-scoped. Create/update/delete are owner-only server-side
// (a non-owner PATCH/DELETE 404s). Create: 422 invalid HQL (.hql set), 409 dup name.
// 429 on EVERY call here: the whole `…/filters/**` path is on the expensive-read
// surface, because validating a filter's HQL builds the same resolution context
// `…/search/schema` pays for — so an invalid-body loop here was the same cost
// wearing different clothes. The binding is by PATH, not by method, so `list`,
// `get` and `remove` are throttled too, surprising as that is for a read and a
// delete. Which refusal arrived is a question for `ApiResponseError.errorType`
// and never for the status: the per-minute budget, `TOO_MANY_IN_FLIGHT`,
// `EXPENSIVE_SURFACE_BUSY`, or a later one — all throttles rather than validation
// failures, none of them meaning anything was written.
//
// **Only the reads here get the automatic retry** (HD-182). Being on this budget
// is not the same as being safe to re-send: `create`, `update` and `remove` are
// writes that happen to be priced like a search, so `request()` hands them the
// refusal instead of quietly sending a second create. `list`, `get` and `usage`
// are GETs and are retried once when the refusal is `TOO_MANY_IN_FLIGHT` — which
// is exactly what a filter sidebar mounting beside a search provokes.

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
//   • 429 — refused by one of the controls on the expensive-read surface, which
//     reports share with search: the per-minute report budget
//     (`app.reports.requests-per-minute`), or the occupancy bound's
//     `TOO_MANY_IN_FLIGHT` / `EXPENSIVE_SURFACE_BUSY` (HD-182), or whatever a
//     later release adds. Branch on `ApiResponseError.errorType`, never on the
//     status. Each is a throttle rather than a fault — nothing was computed, and
//     the identical request is legal to send again once its obstacle is gone
//     (`ApiResponseError.retryAfter` carries the wait: a rolling window for the
//     budget, a request that ends shortly for the other two). Every one of them is
//     spent per principal BEFORE the project is resolved — a control of that shape
//     answers 429 ahead of 404, so a 429 says nothing about whether the caller
//     can see the project, and no UI may imply otherwise. Budgets spent AFTER
//     resolution exist too and read the other way (see `apiInviteWorkspaceMember`).
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

  /**
   * Velocity (R5) — the last N completed sprints, with the forecast band.
   *
   * **`sprints` is always sent**, so the request states what it asked for
   * rather than leaning on a server default a later release could change under
   * an already-shared URL. The page clamps it to 1..12 before it gets here: the
   * endpoint answers 400 above the cap with the cap named, and a reader who
   * pasted `sprints=99` is better served by the chart plus a sentence about the
   * cap than by an error page.
   *
   * `measure` is on the wire for the same reason as on the burn-up — the two
   * series are different sums over different rows — so it joins the cache key.
   *
   * **Project-scoped, permanently.** There is no workspace-level velocity call
   * to add here later; §1.4 refuses one, and the absence of the endpoint is the
   * enforcement.
   */
  velocity: (
    wsId: string, projectId: string,
    params: { sprints?: number; measure?: SprintMeasure } = {},
  ) => {
    const qs = new URLSearchParams()
    if (params.sprints !== undefined) qs.set('sprints', String(params.sprints))
    if (params.measure) qs.set('measure', params.measure)
    const q = qs.toString()
    return request<VelocityReport>(
      `/workspaces/${wsId}/projects/${projectId}/reports/velocity${q ? `?${q}` : ''}`)
  },
}

// ── Report CSV export — HD-141 (R7) ──────────────────────────────────────────

/**
 * The `.csv` variants of the report endpoints (§4.4): **the plotted series**,
 * one row per data point, with a comment header carrying project, window,
 * measure, `computedAt` and `basedOnIssues`.
 *
 * That "series, not issue list" is the whole point and it is the documented
 * disappointment everywhere else: users ask to export the chart and are handed a
 * flat issue dump, which is a different artefact answering a different question.
 * The UI therefore labels this one as the chart's own numbers and keeps the
 * issue-list export as a separate, separately-labelled thing.
 */
export type ReportCsvKind =
  | 'flow' | 'cycle-time' | 'aging' | 'sprint-burnup' | 'sprint-review' | 'velocity'

/** The path a CSV link points at — same base, same params, `.csv` on the report name. */
export function reportCsvPath(
  wsId: string, projectId: string, kind: ReportCsvKind, params: Record<string, string> = {},
): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v) qs.set(k, v)
  const q = qs.toString()
  return `/workspaces/${wsId}/projects/${projectId}/reports/${kind}.csv${q ? `?${q}` : ''}`
}

/**
 * Download a report's series CSV.
 *
 * **This cannot be a plain `<a href>`, and that is not a style choice.** The API
 * authenticates with a bearer token held in memory; an anchor the browser
 * navigates sends no `Authorization` header, so the link would 401 — and a
 * copied "download link" would be a URL that never works for the person it was
 * sent to. So the bytes are fetched with the same auth (and the same silent
 * refresh) as every other call and saved from a blob.
 *
 * The server's `Content-Disposition` filename wins when it sends one; otherwise
 * the caller's name is used, so the file is never called `download`.
 *
 * Failures arrive as `ApiResponseError` with the server's own `detail` — a
 * report CSV inherits every refusal of the report itself (400 on a too-wide
 * window naming the cap, 404, and any 429 the expensive-read surface can raise,
 * each with `Retry-After`), and those sentences are the ones the page already
 * knows how to render.
 *
 * It goes through `authFetch` rather than `request()`, so it gets **no**
 * automatic retry — not even for `TOO_MANY_IN_FLIGHT`. That is a consequence of
 * the body being bytes rather than JSON, not a judgement about the refusal; a
 * download the reader started is also the one place a visible failure they can
 * click again costs least.
 */
export async function apiDownloadReportCsv(
  wsId: string,
  projectId: string,
  kind: ReportCsvKind,
  params: Record<string, string>,
  fallbackFilename: string,
): Promise<void> {
  const res = await authFetch(reportCsvPath(wsId, projectId, kind, params))
  if (!res.ok) {
    let detail = ''
    let body: unknown = null
    try {
      const text = await res.text()
      body = text ? JSON.parse(text) : null
      const b = body as Record<string, string | undefined> | null
      detail = b?.detail ?? b?.message ?? b?.title ?? ''
    } catch { /* a non-JSON error body is no reason to render nothing */ }
    if (!detail) detail = `Request failed (${res.status})`
    throw new ApiResponseError(res.status, detail, undefined, undefined, conflictOf(body, res))
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filenameFromDisposition(res.headers.get('Content-Disposition')) ?? fallbackFilename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/**
 * The filename out of a `Content-Disposition`, or null.
 *
 * Handles the RFC 5987 `filename*` form first — a project name with a non-ASCII
 * character reaches this header — and falls back to the plain quoted form. Any
 * path separator in the value is dropped: a filename is a name, and the one
 * place a server-supplied string becomes a local path is the one place to say so.
 */
export function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const extended = header.match(/filename\*\s*=\s*[^']*'[^']*'([^;]+)/i)
  const plain = header.match(/filename\s*=\s*"([^"]+)"/i) ?? header.match(/filename\s*=\s*([^;]+)/i)
  const raw = extended ? safeDecode(extended[1]) : plain?.[1]
  const name = raw?.trim().replace(/^["']|["']$/g, '').split(/[\\/]/).pop()
  return name ? name : null
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}
