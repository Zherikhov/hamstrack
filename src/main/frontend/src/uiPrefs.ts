/**
 * Per-user UI layout preferences, kept in localStorage under a single key per
 * account (`hamstrack.ui-prefs.<userId>`). Mirrors the isolation rule of
 * `recentProjects.ts` — accounts sharing a browser never see each other's
 * layout, and the state is device-local by design (it does not follow the user
 * across browsers).
 *
 * Holds chrome sizing the user can adjust: the nav rail width + collapsed flag
 * (HD-53) and the board issue-panel width (HD-54). All fields optional — a
 * missing value means "use the component default".
 */

export interface UiPrefs {
  /** Expanded nav-rail width in px (the remembered width, ignored while collapsed). */
  railWidth?: number
  /** Whether the nav rail is collapsed to the icon-only strip. */
  railCollapsed?: boolean
  /** Board issue side-panel width in px. */
  boardPanelWidth?: number
}

const storageKey = (userId: string) => `hamstrack.ui-prefs.${userId}`

export function getUiPrefs(userId: string): UiPrefs {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    if (!raw) return {}
    const parsed = JSON.parse(raw) as UiPrefs
    return parsed && typeof parsed === 'object' ? parsed : {}
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
