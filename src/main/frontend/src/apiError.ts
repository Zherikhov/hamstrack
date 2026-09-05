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
  /** The four figures a {@link STORAGE_QUOTA_EXCEEDED} refusal carries. */
  storage?: StorageQuotaRefusal
}

/**
 * The `errorType` on the 409 an upload gets when the workspace has no room
 * left (HD-191 §5.5).
 *
 * A named constant rather than a literal at each branch, because two surfaces
 * compare against it — the upload door and the storage read that follows the
 * refusal — and a typo in one of them fails the way this class is built to
 * prevent: silently, by falling through to "render `detail`, offer nothing".
 */
export const STORAGE_QUOTA_EXCEEDED = 'STORAGE_QUOTA_EXCEEDED'

/**
 * The two `errorType`s the expensive-read occupancy bound refuses with (HD-182,
 * ADR-0030). They share status **429** with each other and with the per-minute
 * budget, so nothing but this field tells them apart.
 *
 * - **`TOO_MANY_IN_FLIGHT`** — too many of *this caller's own* requests are
 *   running at once. The obstacle is the caller's own conduct and it clears as
 *   soon as one of their requests finishes, which is why this is the only one of
 *   the three that `api.ts` retries by itself, once.
 * - **`EXPENSIVE_SURFACE_BUSY`** — the instance's expensive-read share is full.
 *   The caller may hold **no** permit at all, so there is nothing for them to
 *   stop doing and a retry is not a remedy, it is more load: never retry it
 *   automatically. Render `detail` and let the reader decide.
 *
 * Both carry `Retry-After: 1`, and that number means *the obstacle is a request
 * that ends shortly* — **not** *the window has one second left*. The
 * lock-contention 409 uses the same value for the same reason. The per-minute
 * budget's `Retry-After` is the other kind (a clock), which is why computing one
 * of these the way that one is computed would be wrong by up to 60×.
 *
 * Named constants rather than literals at each branch, for
 * {@link STORAGE_QUOTA_EXCEEDED}'s reason: a typo fails silently, by falling
 * through to the branch that offers nothing.
 */
export const TOO_MANY_IN_FLIGHT = 'TOO_MANY_IN_FLIGHT'

/** @see TOO_MANY_IN_FLIGHT — the refusal that must NEVER be retried automatically. */
export const EXPENSIVE_SURFACE_BUSY = 'EXPENSIVE_SURFACE_BUSY'

/**
 * The ProblemDetail extensions on that 409 — bytes, raw, all four together or
 * not at all.
 *
 * **It carries no `Retry-After`, and that is the contract, not an omission.**
 * Waiting never frees a byte, so the refusal is deliberately not a rate limit
 * and a client must not render it as one. Nor may a client add a call to
 * action: the reader may hold no delete grant, and the space may be in a project
 * they cannot see, so a refusal that dispatched them would be dispatching them
 * nowhere.
 *
 * Read these **instead of** a cached storage summary when rendering the
 * refusal: the summary may be minutes old, and these figures are the ones the
 * server actually refused on.
 */
export interface StorageQuotaRefusal {
  quotaBytes: number
  usedBytes: number
  /**
   * **Clamped at zero by the server**, deliberately: a quota lowered below
   * current usage makes the difference negative, and "-2.1 GB available" is a
   * number no reader can act on. So this is not `quotaBytes - usedBytes` and
   * must not be re-derived as one — `usedBytes > quotaBytes` is the state that
   * says a workspace is past its ceiling.
   */
  availableBytes: number
  /** The parsed size of the file that was refused — never a client-declared length. */
  fileBytes: number
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
  /** Set only when `errorType === STORAGE_QUOTA_EXCEEDED` and all four figures parsed. */
  storage?: StorageQuotaRefusal
  /**
   * The `{name: message}` ProblemDetail extension carried by **any 400 raised by a
   * declared constraint**, keyed by the name of the item that was refused — a
   * request-body field path (nested ones included) or the name a request parameter
   * was sent under. Body constraints, parameter constraints and a Bean Validation
   * backstop all render through one shared path in `GlobalExceptionHandler`, so this
   * is a **category, not a list**: code against "the key is the refused item's own
   * name", never against which names can appear — an earlier version of this comment
   * enumerated four of them and was wrong twice over within two releases. A rule that
   * belongs to no single item — a class-level or cross-parameter constraint — keys on
   * the **empty string**.
   *
   * `detail` already joins the same entries into one sentence, so a caller that
   * renders `detail` loses nothing. This map exists so a form can put the message
   * **next to the field** instead of in a banner, and so a page can ask *which* item
   * was refused without pattern-matching English (HD-171 §11): `ResetPasswordPage`
   * rewrites a 400 into "this link expired", which is right when the refused item is
   * the reset token and wrong when it is the new password, and the only structural
   * way to tell them apart is this map.
   *
   * **Optional — read defensively, as this class already does.** Not every 400
   * carries it: a body that could not be parsed bound nothing and so can name
   * nothing, a *missing* required parameter never reached a constraint at all, and a
   * cross-field rule enforced inside a service (the delivery/board and sprint checks)
   * is a whole sentence in `detail` and nothing else. Neither does any non-400 — a
   * business-rule 422 such as `PasswordTooLongException` names no field either. Fall
   * back to `detail`, and never read an absent or empty map as "nothing was wrong".
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
    this.storage = conflict?.storage
  }
}

/**
 * **A refusal must never cost more than the thing it refused** (HD-174 §5.4).
 *
 * The server has just declined to do work. Told from every other failure by the
 * **status**, on purpose, and deliberately wider than the refusals that exist
 * today: the expensive surfaces answer 429 with three different `errorType`s
 * (the per-minute budget carrying none at all, {@link TOO_MANY_IN_FLIGHT} and
 * {@link EXPENSIVE_SURFACE_BUSY}), and a fourth nobody has written yet inherits
 * the safe answer here rather than the escalating one.
 *
 * What a caller owes a request that took this branch is the same everywhere it
 * is asked: **do not answer it by issuing something more expensive**, and do not
 * retry it. By the time an error reaches this predicate, `api.ts` has already
 * made the one automatic retry it will ever make (`TOO_MANY_IN_FLIGHT`, once)
 * and that retry has failed.
 *
 * A leaf-module predicate rather than a planning-surface one: the rule is about
 * a *status class*, and every surface that can be refused reads it — the
 * planning aggregate, its per-section refresh and the SSE fan-out that would
 * otherwise re-ask a surface which has just said no.
 */
export function isThrottleRefusal(e: unknown): boolean {
  return e instanceof ApiResponseError && e.status === 429
}

/**
 * **Saturation does not always produce a 429.** The failure a busy instance
 * hands back for a reason *outside* the throttled surface — the connection pool
 * exhausted by writes, a Hikari `connectionTimeout` expiring, an edge that gave
 * up waiting — is a 502/503/504, and in that state asking for something bigger
 * is exactly as wrong as it is after a refusal.
 *
 * Since HD-233 the middle one of those is **deliberate rather than incidental**:
 * a failed connection acquisition answers `503` with `errorType: DATABASE_BUSY`
 * and `Retry-After: 1`. That does not change this predicate, and the omission is
 * the decision: an *intermediary* retrying one acquisition attempt is cheap,
 * while this layer would retry from every open tab at the moment the instance has
 * least room. The banner that renders `detail` is the right answer here; the
 * retry belongs to the person reading it.
 *
 * **500 is deliberately NOT here**, and that is the whole distinction: a 500 is
 * a genuine bug on one request, where a fresh read is worth having and may well
 * succeed. An overloaded server is not going to be helped by being asked for
 * more.
 *
 * That distinction is only sound while the server does not answer 500 for a
 * condition that belongs in the list above, and for one release it did: the
 * `DATABASE_BUSY` refusal reached only requests that got as far as a handler,
 * so a starved instance answered every *authenticated* query with a 500 — and
 * this file's own reasoning then had each tab ask again. The server closed it
 * (a servlet filter answers the acquisitions that fail before the dispatcher),
 * which is the shape of the fix to insist on: **a status that means "the
 * instance is out of room" is worth nothing unless the paths that carry the
 * traffic can actually produce it.**
 *
 * That last sentence binds the **automatic** layer as hard as the hand-written
 * ones: `queryClient.ts` reads this predicate so its global single retry cannot
 * hold the opposite opinion about the same three statuses — a retry is one more
 * of the request that just failed, from every open tab, at the moment there is
 * least room for it.
 */
export function isOverloadFailure(e: unknown): boolean {
  return e instanceof ApiResponseError
    && (e.status === 502 || e.status === 503 || e.status === 504)
}

/**
 * The union: **the server has no room**, whether because it refused the work
 * ({@link isThrottleRefusal}) or because it is overloaded
 * ({@link isOverloadFailure}).
 *
 * One branch, two sentences. Code branches on this — the escalation rule is
 * identical for both — but a reader must never be told the same thing for both:
 * *"the server declined this request"* and *"the server is overloaded"* are
 * different facts, and only the first one comes with a sentence the server
 * itself wrote. Keep the two predicates reachable at every site that renders
 * copy.
 */
export function isCapacityFailure(e: unknown): boolean {
  return isThrottleRefusal(e) || isOverloadFailure(e)
}
