import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { useCurrentProject } from './useCurrentProject'
import { getLastWorkspaceId } from '../recentProjects'
import { shouldHandleGlobalKey } from '../lib/keyboard'
import type { Workspace } from '../types'

/**
 * App-wide keyboard shortcuts (HD-39 §8). Mounted ONCE, in `AppShell`, so the
 * map is unavailable on the public/onboarding/admin routes by construction.
 *
 * Every key goes through `shouldHandleGlobalKey` (the normative §8.3 predicate)
 * before anything happens — that is what keeps typing `c` in a textarea from
 * opening the create dialog and eating the keystroke. The listener is attached
 * in the BUBBLE phase so a component that `stopPropagation()`s (HqlInput's
 * autocomplete) wins, and it is removed on unmount so a route transition can
 * never leave two handlers double-firing.
 */

/** How long a `g` chord stays armed before it expires (§8.1 / §20 Q5). */
export const CHORD_TIMEOUT_MS = 1200

/** The `g …` chord table. A `null` target means "no-op in this context". */
export const CHORD_KEYS = ['h', 'm', 'b', 'l', 's', 'w', 'a'] as const
export type ChordKey = (typeof CHORD_KEYS)[number]

export interface GlobalShortcutsState {
  /** True while a `g` chord is armed — drives the `g …` indicator chip. */
  chordArmed: boolean
}

export function useGlobalShortcuts(): GlobalShortcutsState {
  const navigate = useNavigate()
  const cur = useCurrentProject()
  const user = useAuthStore(s => s.user)
  const qc = useQueryClient()
  const [chordArmed, setChordArmed] = useState(false)

  const chordRef = useRef(false)
  const chordTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Everything the (once-registered) listener needs, refreshed on every render.
  // Reading through a ref keeps the effect dependency list empty, so we never
  // detach/reattach — and therefore never double-register — on navigation.
  const ctxRef = useRef({ navigate, cur, user, qc })
  ctxRef.current = { navigate, cur, user, qc }

  const cancelChord = useCallback(() => {
    if (chordTimer.current) clearTimeout(chordTimer.current)
    chordTimer.current = null
    if (chordRef.current) {
      chordRef.current = false
      setChordArmed(false)
    }
  }, [])

  const armChord = useCallback(() => {
    if (chordTimer.current) clearTimeout(chordTimer.current)
    chordRef.current = true
    setChordArmed(true)
    chordTimer.current = setTimeout(() => {
      chordRef.current = false
      chordTimer.current = null
      setChordArmed(false)
    }, CHORD_TIMEOUT_MS)
  }, [])

  useEffect(() => {
    /**
     * The workspace the workspace-scoped shortcuts act on. Same fallback chain
     * as NavRail/TopSearchBar/the palette (§3.4), but reading the `['workspaces']`
     * cache rather than subscribing — a shortcut must never trigger a fetch.
     */
    function resolveWsId(): string | null {
      const { cur: c, user: u, qc: client } = ctxRef.current
      const cached = client.getQueryData<Workspace[]>(['workspaces'])
      return c?.wsId ?? (u ? getLastWorkspaceId(u.id) : null) ?? cached?.[0]?.id ?? null
    }

    /** Where a `g {key}` chord goes, or null when it is a no-op here. */
    function chordTarget(key: string): string | null {
      const { cur: c, user: u } = ctxRef.current
      switch (key) {
        case 'h': return '/home'
        case 'm': return '/my-work'
        case 'w': return '/workspaces'
        case 'b': return c ? `/w/${c.wsId}/p/${c.projectId}` : null
        case 'l': return c ? `/w/${c.wsId}/p/${c.projectId}/backlog` : null
        case 's': { const ws = resolveWsId(); return ws ? `/w/${ws}/search` : null }
        case 'a': return u?.systemRole === 'ADMIN' ? '/admin' : null
        default: return null
      }
    }

    function onKeyDown(e: KeyboardEvent) {
      const ui = useUiStore.getState()
      const overlays = {
        createIssueOpen: ui.createIssueOpen,
        paletteOpen: ui.paletteOpen,
        helpOpen: ui.helpOpen,
      }

      // ── Cmd/Ctrl+K — the only shortcut that fires while typing (§8.3) ───────
      // Clauses 2 and 5 are exempted; 1, 3, 4 and 6 still apply, so it never
      // opens over another modal. While the palette itself is open, clause 6
      // blocks us here and the palette's own handler does the toggle.
      if ((e.metaKey || e.ctrlKey) && !e.altKey && (e.key === 'k' || e.key === 'K')) {
        if (!shouldHandleGlobalKey(e, overlays, { allowModifiers: true, allowEditableTarget: true })) return
        e.preventDefault()
        cancelChord()
        ui.openPalette()
        return
      }

      // ── Single-key map — the full §8.3 gate, no exemptions ─────────────────
      if (!shouldHandleGlobalKey(e, overlays)) {
        // A suppressed key also ends any armed chord: focus has moved into a
        // text surface (or a modal opened) and `g` must not swallow the next key.
        cancelChord()
        return
      }

      // Second key of an armed chord. Matching is on `e.key` exactly and
      // case-sensitively, so Caps Lock deliberately does not trigger (§8.1).
      if (chordRef.current) {
        cancelChord()
        if ((CHORD_KEYS as readonly string[]).includes(e.key)) {
          const target = chordTarget(e.key)
          // preventDefault ONLY when the chord resolved to an action.
          if (target) {
            e.preventDefault()
            ctxRef.current.navigate(target)
          }
          return
        }
        // Not in the chord table → cancelled silently; `g` below may re-arm.
      }

      switch (e.key) {
        case 'g':
          e.preventDefault()
          armChord()
          return
        case 'c':
          e.preventDefault()
          ui.openCreateIssue()
          return
        case '/': {
          // No workspace → the top-bar input isn't rendered; stay a no-op and
          // let the character through rather than swallowing it.
          if (!resolveWsId()) return
          e.preventDefault()
          ui.requestSearchFocus()
          return
        }
        case '?':
          e.preventDefault()
          ui.toggleHelp()
          return
        default:
          return
      }
    }

    // Losing window focus mid-chord cancels it (§14 case 25).
    const onBlur = () => cancelChord()

    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('blur', onBlur)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('blur', onBlur)
      if (chordTimer.current) clearTimeout(chordTimer.current)
    }
  }, [armChord, cancelChord])

  return { chordArmed }
}
