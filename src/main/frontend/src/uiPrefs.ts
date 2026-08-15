/**
 * Per-user UI layout preferences, kept in localStorage under a single key per
 * account (`hamstrack.ui-prefs.<userId>`). Mirrors the isolation rule of
 * `recentProjects.ts` — accounts sharing a browser never see each other's
 * layout, and the state is device-local by design (it does not follow the user
 * across browsers).
 *
 * Holds chrome sizing the user can adjust: the nav rail width + collapsed flag
 * (HD-53), the board issue-panel width (HD-54) and the board quick filters
 * (HD-43). All fields optional — a missing value means "use the component
 * default".
 */

/** Board quick-filter selection for one project (HD-43). */
export interface BoardQuickFilters {
  /** Only issues assigned to the current user. */
  mine?: boolean
  /** Only issues with no assignee. */
  unassigned?: boolean
  /** Issue-type ids to keep (empty/absent = all types). May contain stale ids. */
  typeIds?: string[]
}

export interface UiPrefs {
  /** Expanded nav-rail width in px (the remembered width, ignored while collapsed). */
  railWidth?: number
  /** Whether the nav rail is collapsed to the icon-only strip. */
  railCollapsed?: boolean
  /** Board issue side-panel width in px. */
  boardPanelWidth?: number
  /**
   * Board quick filters, keyed by projectId — so each board keeps its own chips
   * (HD-43). Ids of types that no longer exist are ignored when applying.
   */
  boardQuickFilters?: Record<string, BoardQuickFilters>
  /**
   * Ids of the last STATIC command-palette commands the user ran, most recent
   * first, capped at `PALETTE_RECENTS_MAX` (HD-39 §11).
   *
   * Deliberately only `action.*` / `nav.*` ids: issue / project / filter /
   * workspace / member ids are tenant data, go stale the moment access is
   * revoked, and a "Recent" row naming a resource the user can no longer reach
   * is both a bad experience and a leak of a name they lost access to. Project
   * recency already lives in `recentProjects.ts` and the palette reuses it as a
   * tie-break instead. Unknown ids are filtered out silently on read.
   */
  paletteRecentIds?: string[]
}

/** How many recent palette commands are kept / shown (§11, §5.4). */
export const PALETTE_RECENTS_MAX = 5

const storageKey = (userId: string) => `hamstrack.ui-prefs.${userId}`

export function getUiPrefs(userId: string): UiPrefs {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const prefs = parsed as UiPrefs
    // localStorage is user-writable and survives schema changes, so a stored
    // value can be ANY shape. `paletteRecentIds` is consumed with `.map()`
    // during the palette's render — a non-array there would throw inside a
    // `useMemo` and take the whole palette down until site data is cleared.
    // Coerce like `recentProjects.ts` does: unusable value → no recents.
    if ('paletteRecentIds' in prefs) {
      const ids: unknown = prefs.paletteRecentIds
      prefs.paletteRecentIds = Array.isArray(ids)
        ? ids.filter((x): x is string => typeof x === 'string')
        : []
    }
    return prefs
  } catch {
    return {}
  }
}

/** Merge a single preference and persist. Best-effort — storage may be full/blocked. */
export function setUiPref<K extends keyof UiPrefs>(userId: string, key: K, value: UiPrefs[K]) {
  const next = { ...getUiPrefs(userId), [key]: value }
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(next))
  } catch { /* ignore — layout persistence is best-effort */ }
}
