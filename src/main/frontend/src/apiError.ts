import type { ProjectRef, RoleUsage } from './types'

/**
 * The error type every failed API call rejects with — and a **leaf module** on
 * purpose.
 *
 * It lives here, not in `api.ts`, because `queryClient.ts` has to test for it
 * (`retryQuery` refuses to retry a 422/429) and `api.ts` reaches the auth store,
 * which in turn reaches the query client:
 *
 *     queryClient.ts → api.ts → auth.ts → queryClient.ts
 *
 * That cycle was harmless only by accident — nothing in it dereferences an
 * imported binding while the modules evaluate. One top-level line anywhere in
 * the loop (a `queryClient.setQueryDefaults(...)` at `auth.ts` module scope, a
 * top-level `ApiResponseError` reference in `queryClient.ts`) makes one of the
 * three modules see a half-initialised partner, and the way it fails is the bad
 * way: `ApiResponseError` resolves `undefined`, `instanceof` throws inside the
 * retry predicate, and the behaviour that comes back is **"a 422 is retryable
 * again"** — silently, in the bundle, and not in the tests, because Vitest
 * enters the graph at a different module than the app bundle does.
 *
 * So: this file imports nothing from the app graph but *types* (erased at
 * build time), and both `api.ts` and `queryClient.ts` import it. `api.ts`
 * re-exports these three names, so every existing `from '../api'` call site is
 * unchanged. `moduleGraph.test.ts` fails if the cycle comes back.
 */

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
   * `ROLE_LIMIT_REACHED` · `DUPLICATE_INVITE`. Not every conflict has one — the
   * "already a member" refusal deliberately carries none, and it outranks
   * `DUPLICATE_INVITE` when both describe the same address, which is why a
   * caller branches on this field and never on the 409 itself.
   * Typed as an open string on purpose — the server may
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
  /**
   * The `{field: message}` ProblemDetail extension a **validation 400** carries
   * (`GlobalExceptionHandler.handleValidation`), keyed by the request field's
   * own name — `password`, `newPassword`, `description`, `body`.
   *
   * `detail` already joins the same entries into one sentence, so a caller that
   * renders `detail` loses nothing. This map exists so a form can put the
   * message **next to the field** instead of in a banner, and so a page can ask
   * *which* field was refused without pattern-matching English (HD-171 §11):
   * `ResetPasswordPage` rewrites a 400 into "this link expired", which is right
   * for the `token` field and wrong for `newPassword`, and the only structural
   * way to tell them apart is this map.
   *
   * Absent on every non-validation error, including a 422 — a business-rule
   * refusal such as `PasswordTooLongException` is a whole sentence in `detail`
   * and names no field, so a reader must fall back to `detail` and never treat
   * an empty map as "nothing was wrong".
   */
  errors?: Record<string, string>
  constructor(
    public status: number,
    public detail: string,
    hql?: HqlError,
    existingId?: string,
    conflict?: ConflictInfo,
    errors?: Record<string, string>,
  ) {
    super(detail)
    this.hql = hql
    this.existingId = existingId
    this.errors = errors
    this.retryAfter = conflict?.retryAfter
    this.errorType = conflict?.errorType
    this.projects = conflict?.projects
    this.usage = conflict?.usage
  }
}
