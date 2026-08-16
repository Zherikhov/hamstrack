/**
 * Global-shortcut gatekeeping (HD-39, spec §8.2–§8.3).
 *
 * `shouldHandleGlobalKey` is the NORMATIVE predicate: every global key handled
 * outside a focused widget MUST route through it. It is the single point of
 * failure for the whole feature — if one editable surface slips through, the app
 * develops a "typing `c` randomly opens a dialog and eats my text" bug that is
 * intermittent and infuriating (spec §16). Each clause is unit-tested
 * independently in `keyboard.test.ts`; add a clause here, add a test there.
 *
 * Pure except for the documented `document.querySelector` probe in clause 6.
 */

/**
 * Elements (or ancestors of the event target) that own the keyboard and must
 * therefore suppress single-key shortcuts.
 *
 * Written as a `closest()` selector on purpose: a keystroke can land on a
 * wrapper inside a contenteditable, and ancestor-awareness catches that.
 * `[data-no-shortcuts]` is the opt-out hook for any future widget with its own
 * key handling.
 */
export const EDITABLE_SELECTOR = [
  'textarea',
  'select',
  // Every input EXCEPT the ones that are really buttons/pickers. Note the
  // deliberate absence of `[type=text]` etc. — a type-less <input> is a text
  // input and must suppress too, so we filter by exclusion, not inclusion.
  'input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit])'
    + ':not([type=reset]):not([type=range]):not([type=color]):not([type=file]):not([type=image])',
  '[contenteditable]:not([contenteditable="false"])',
  '[role="textbox"]',
  '[role="combobox"]',
  '[role="searchbox"]',
  '[data-no-shortcuts]',
].join(',')

/** The deepest element the event actually started on (shadow-DOM aware). */
export function eventTarget(e: KeyboardEvent): Element | null {
  const path = typeof e.composedPath === 'function' ? e.composedPath() : []
  const first = (path[0] ?? e.target) as unknown
  return first && typeof (first as Element).closest === 'function' ? (first as Element) : null
}

/**
 * Clause 5 of §8.3: is the event target an editable surface (or inside one)?
 * A non-Element target (document/window) is never editable.
 */
export function isEditableTarget(el: Element | null | undefined): boolean {
  if (!el || typeof el.closest !== 'function') return false
  return el.closest(EDITABLE_SELECTOR) !== null
}

/** Overlay flags owned by `uiStore` — clause 6 reads these plus a DOM probe. */
export interface OverlayState {
  createIssueOpen: boolean
  paletteOpen: boolean
  helpOpen: boolean
}

/**
 * Clause 6 of §8.3. Covers both the overlays tracked in `uiStore` and the
 * locally-owned modals (`CreateProjectModal`, `SaveFilterDialog`,
 * `WorkspaceMembersModal`, `AboutModal`, `CreateIssueModal`), each of which
 * carries `data-modal-open="true"` on its overlay div.
 */
export function anyOverlayOpen(overlays: OverlayState): boolean {
  if (overlays.createIssueOpen || overlays.paletteOpen || overlays.helpOpen) return true
  if (typeof document === 'undefined') return false
  return document.querySelector('[data-modal-open="true"]') !== null
}

export interface GlobalKeyOptions {
  /**
   * Skip clause 2 (no Ctrl/Meta/Alt). Only `Cmd/Ctrl+K` sets this — it REQUIRES
   * a modifier by definition.
   */
  allowModifiers?: boolean
  /**
   * Skip clause 5 (target not editable). Only `Cmd/Ctrl+K` sets this — it is the
   * one shortcut documented to work while typing anywhere (§8.3).
   */
  allowEditableTarget?: boolean
}

/**
 * The 6-clause predicate from spec §8.3. A single-key shortcut (`c`, `/`, `?`,
 * `g`, and any chord second key) fires ONLY when all of these hold:
 *
 *  1. the event was not already handled (`defaultPrevented`);
 *  2. no Ctrl/Meta/Alt modifier (Shift is ignored — it only picks the character);
 *  3. no IME composition in progress (`isComposing` / legacy `keyCode === 229`);
 *  4. not an auto-repeat from a held key;
 *  5. the event target is not an editable surface (§8.3 clause 5 — see
 *     `EDITABLE_SELECTOR`);
 *  6. no overlay owns the keyboard.
 */
export function shouldHandleGlobalKey(
  e: KeyboardEvent,
  overlays: OverlayState,
  opts: GlobalKeyOptions = {},
): boolean {
  // 1 — someone downstream already consumed it (e.g. HqlInput's autocomplete).
  if (e.defaultPrevented) return false
  // 2 — modifiers belong to browser/OS chords, not to our single-key map.
  if (!opts.allowModifiers && (e.ctrlKey || e.metaKey || e.altKey)) return false
  // 3 — mid-composition keystrokes are IME input, not commands.
  if (e.isComposing || e.keyCode === 229) return false
  // 4 — a held key must not fire the action N times.
  if (e.repeat) return false
  // 5 — THE clause. Never steal a keystroke from a text surface.
  if (!opts.allowEditableTarget && isEditableTarget(eventTarget(e))) return false
  // 6 — an open overlay owns the keyboard.
  if (anyOverlayOpen(overlays)) return false
  return true
}

/**
 * Platform label switch (§8.2) — `⌘K` vs `Ctrl K`. Behaviour always accepts
 * either `metaKey` or `ctrlKey` on both platforms; only the LABEL differs, so a
 * wrong guess here is cosmetic, never functional.
 */
export function isMac(): boolean {
  if (typeof navigator === 'undefined') return false
  const nav = navigator as Navigator & { userAgentData?: { platform?: string } }
  const platform = nav.userAgentData?.platform ?? nav.platform ?? ''
  return /mac|iphone|ipad/i.test(platform)
}

/** `⌘K` on mac, `Ctrl K` elsewhere — for placeholders, footers and the help sheet. */
export function paletteHint(): string {
  return isMac() ? '⌘K' : 'Ctrl K'
}
