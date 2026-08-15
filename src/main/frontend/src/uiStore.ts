import { create } from 'zustand'

/**
 * Pre-fill for the create-issue dialog — e.g. "create sub-task" from a parent, or the
 * board's per-column quick add. Every field is a default the user can still change.
 */
export interface CreateIssuePreset {
  /** Pre-select this issue as the new issue's parent. */
  parentId?: string
  /** The parent's project — pins the modal's project selector so eligibility matches. */
  projectId?: string
  /** The parent's type hierarchy level — the child type defaults to the highest legal level below it. */
  parentLevel?: number
  /**
   * Pre-select this status (board column quick-add). Only meaningful together with
   * `projectId` — statuses are per-project taxonomy, so the modal drops it as soon
   * as the user switches project.
   */
  statusId?: string
}

interface UiState {
  /** Create-issue dialog visibility — rendered by AppShell, triggerable from any page. */
  createIssueOpen: boolean
  /** Optional pre-fill (e.g. "create sub-task"); undefined = a blank create. */
  createIssuePreset?: CreateIssuePreset
  openCreateIssue: (preset?: CreateIssuePreset) => void
  closeCreateIssue: () => void

  // ── Command palette (HD-39) ────────────────────────────────────────────────
  // Kept in the same store as `createIssueOpen` on purpose: the global shortcut
  // hook needs ONE place to ask "does an overlay already own the keyboard?"
  // (spec §8.3 clause 6).

  /** Command-palette overlay visibility (Cmd/Ctrl-K). */
  paletteOpen: boolean
  /**
   * Whether closing the palette should hand focus back to the element that had
   * it before opening (§7.4). Set to false when a command took over — the new
   * page/modal owns focus then, and stealing it back would be jarring.
   */
  paletteRestoreFocus: boolean
  /**
   * Open the palette. NO-OP while the create-issue modal is open: two stacked
   * focus traps, with a half-typed issue silently losing focus, is the worst
   * possible outcome (§3.3). Enforced here so every caller inherits the rule.
   */
  openPalette: () => void
  closePalette: (opts?: { restoreFocus?: boolean }) => void
  togglePalette: () => void

  /** Keyboard-shortcuts help overlay (`?`). */
  helpOpen: boolean
  /**
   * Element the sheet hands focus back to on close, when `document.activeElement`
   * can no longer tell it. Set only by the palette's "Keyboard shortcuts" command:
   * the palette closes BEFORE the sheet mounts, so by then its input is gone and
   * the active element is `<body>` — without this the keyboard user restarts
   * tabbing from the top (§7.4). Cleared on close; `null` for a direct `?` open,
   * where `document.activeElement` is still the right answer.
   */
  helpFocusReturn: HTMLElement | null
  /**
   * Open the help sheet. NO-OP while the palette or the create-issue modal is
   * open, for exactly the reason `openPalette` no-ops (§3.3): both overlays run a
   * single-element focus trap that re-focuses through `requestAnimationFrame`, so
   * two of them up at once would ping-pong focus forever with no way out but a
   * reload. Enforced here so every caller inherits the rule — callers that mean to
   * REPLACE the palette with the sheet must close the palette first (the palette's
   * own command does, in the same handler).
   */
  openHelp: (focusReturn?: HTMLElement | null) => void
  closeHelp: () => void
  toggleHelp: () => void

  /**
   * Bumped by `/` to ask the top-bar HQL input to take focus. A nonce rather
   * than a boolean so repeated presses always re-focus (and no reset dance is
   * needed); TopSearchBar focuses on every change.
   */
  searchFocusNonce: number
  requestSearchFocus: () => void
}

export const useUiStore = create<UiState>((set, get) => ({
  createIssueOpen: false,
  createIssuePreset: undefined,
  openCreateIssue: preset => set({ createIssueOpen: true, createIssuePreset: preset }),
  closeCreateIssue: () => set({ createIssueOpen: false, createIssuePreset: undefined }),

  paletteOpen: false,
  paletteRestoreFocus: true,
  openPalette: () => {
    if (get().createIssueOpen) return
    set({ paletteOpen: true, paletteRestoreFocus: true })
  },
  closePalette: opts => set({ paletteOpen: false, paletteRestoreFocus: opts?.restoreFocus !== false }),
  togglePalette: () => {
    if (get().paletteOpen) set({ paletteOpen: false, paletteRestoreFocus: true })
    else get().openPalette()
  },

  helpOpen: false,
  helpFocusReturn: null,
  openHelp: focusReturn => {
    if (get().paletteOpen || get().createIssueOpen) return
    set({ helpOpen: true, helpFocusReturn: focusReturn ?? null })
  },
  closeHelp: () => set({ helpOpen: false, helpFocusReturn: null }),
  toggleHelp: () => {
    if (get().helpOpen) get().closeHelp()
    else get().openHelp()
  },

  searchFocusNonce: 0,
  requestSearchFocus: () => set(s => ({ searchFocusNonce: s.searchFocusNonce + 1 })),
}))
