import { useCallback, useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { useCurrentProject } from '../hooks/useCurrentProject'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { eventTarget, isEditableTarget, isMac } from '../lib/keyboard'
import { Kbd } from './CommandPalette'

/**
 * The `?` cheat sheet (HD-39 §9). Every shortcut the app has, in one place —
 * discoverability for the keyboard map that otherwise only exists in muscle
 * memory. Also reachable from the NavRail user menu, so mouse users can find it.
 *
 * Rows whose command is unavailable for the current user are HIDDEN rather than
 * greyed (a disabled row invites "why can't I?"), with one deliberate exception:
 * `g b` / `g l` stay visible with a muted "needs a project" note, because that
 * state is transient and the shortcut is worth learning anyway.
 */
export default function ShortcutsHelp() {
  const open = useUiStore(s => s.helpOpen)
  return open ? <HelpPanel /> : null
}

interface Row {
  keys: string[]
  label: string
  note?: string
  /** Hidden entirely when false. */
  available?: boolean
}

function HelpPanel() {
  const closeHelp = useUiStore(s => s.closeHelp)
  const user = useAuthStore(s => s.user)
  const cur = useCurrentProject()
  const reducedMotion = useReducedMotion()
  const closeRef = useRef<HTMLButtonElement>(null)
  const closingRef = useRef(false)

  const close = useCallback(() => {
    closingRef.current = true
    closeHelp()
  }, [closeHelp])

  // Focus the dismiss control on open and hand focus back on close (§7.4).
  //
  // `helpFocusReturn` wins over `document.activeElement` when it is set: opened
  // FROM the palette, the palette is already gone by the time we mount, so the
  // active element is <body> and only the palette knows what had focus before it.
  // Read (and captured) at MOUNT — `closeHelp` clears the store field, so reading
  // it in the cleanup would always find null.
  useEffect(() => {
    const previouslyFocused = useUiStore.getState().helpFocusReturn
      ?? (document.activeElement as HTMLElement | null)
    closeRef.current?.focus()
    return () => {
      if (previouslyFocused && document.contains(previouslyFocused) && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus()
      }
    }
  }, [])

  // Esc and `?` both dismiss. Handled here rather than in the global hook so it
  // can never interfere with inline-edit Esc handlers elsewhere (§8.3).
  //
  // `?` is a PRINTABLE character, so it goes through the same gate as every
  // other single-key shortcut (§8.3 clauses 1 + 5): an already-handled event is
  // left alone, and a keystroke that started in a text surface is never stolen
  // — otherwise this listener would be the one code path that reintroduces the
  // §16 failure mode ("typing `?` does nothing / eats my text"). `Esc` stays
  // unconditional: it is the universal dismissal and the sheet owns it while
  // it is up (the focus trap below keeps background editors from holding focus
  // in the first place).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.defaultPrevented) return
      if (e.key === 'Escape' || (e.key === '?' && !isEditableTarget(eventTarget(e)))) {
        e.preventDefault()
        close()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [close])

  const cmd = isMac() ? '⌘' : 'Ctrl'
  const isAdmin = user?.systemRole === 'ADMIN'
  const needsProject = cur ? undefined : 'needs a project'

  const groups: { title: string; rows: Row[] }[] = [
    {
      title: 'General',
      rows: [
        { keys: [cmd, 'K'], label: 'Command palette' },
        { keys: ['c'], label: 'Create issue' },
        { keys: ['/'], label: 'Search' },
        { keys: ['?'], label: 'This help' },
        { keys: ['Esc'], label: 'Close the palette or a dialog' },
      ],
    },
    {
      title: 'Go to',
      rows: [
        { keys: ['g', 'h'], label: 'Home' },
        { keys: ['g', 'm'], label: 'My work' },
        { keys: ['g', 'b'], label: 'Board', note: needsProject },
        { keys: ['g', 'l'], label: 'Backlog', note: needsProject },
        { keys: ['g', 's'], label: 'Search' },
        { keys: ['g', 'w'], label: 'Workspaces' },
        { keys: ['g', 'a'], label: 'System administration', available: isAdmin },
      ],
    },
    {
      title: 'In the palette',
      rows: [
        { keys: ['↑', '↓'], label: 'Move between results' },
        { keys: ['Tab'], label: 'Move down' },
        { keys: ['Shift', 'Tab'], label: 'Move up' },
        { keys: ['↵'], label: 'Open the selected result' },
        { keys: ['Esc'], label: 'Close' },
        { keys: ['HD-42'], label: 'Type an issue key to jump straight to it' },
      ],
    },
  ]

  return (
    <div
      data-modal-open="true"
      style={{
        position: 'fixed', inset: 0, zIndex: 70,
        background: 'rgba(16,24,40,0.45)', backdropFilter: 'blur(2px)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 12,
        animation: reducedMotion ? undefined : 'cmdk-fade-in 120ms ease-out',
      }}
      onMouseDown={close}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Keyboard shortcuts"
        onMouseDown={e => e.stopPropagation()}
        style={{
          width: 560, maxWidth: '100%', maxHeight: '80vh',
          background: 'var(--color-card)',
          border: '1px solid var(--color-border-2)',
          borderRadius: 'var(--radius-xl)',
          boxShadow: 'var(--shadow-lg)',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}
      >
        <div
          className="flex items-center justify-between flex-shrink-0"
          style={{ padding: '16px 20px', borderBottom: '1px solid var(--color-border)' }}
        >
          <span style={{ fontSize: 15, fontWeight: 800 }}>Keyboard shortcuts</span>
          <button
            ref={closeRef}
            onClick={close}
            onBlur={() => {
              // Same single-element focus trap as the palette (§7.2): the sheet
              // has exactly one focusable control, and it keeps focus for the
              // sheet's whole lifetime so a background input can never own the
              // keyboard while the sheet is up.
              if (!closingRef.current) requestAnimationFrame(() => closeRef.current?.focus())
            }}
            aria-label="Close"
            className="cursor-pointer"
            style={{ background: 'none', border: 'none', lineHeight: 0 }}
          >
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <div style={{ overflowY: 'auto', padding: '4px 20px 18px' }}>
          {groups.map(group => (
            <div key={group.title}>
              <div
                style={{
                  fontSize: 11, fontWeight: 700, letterSpacing: '.08em',
                  textTransform: 'uppercase', color: 'var(--color-text-muted)',
                  padding: '16px 0 6px',
                }}
              >
                {group.title}
              </div>
              {group.rows.filter(r => r.available !== false).map(row => (
                <div
                  key={row.label}
                  className="flex items-center gap-3"
                  style={{ padding: '6px 0' }}
                >
                  <span className="flex items-center gap-1 flex-shrink-0" style={{ minWidth: 92 }}>
                    {row.keys.map((k, i) => <Kbd key={i}>{k}</Kbd>)}
                  </span>
                  <span style={{ fontSize: 13.5, color: 'var(--color-text)' }}>{row.label}</span>
                  {row.note && (
                    <span style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>— {row.note}</span>
                  )}
                </div>
              ))}
            </div>
          ))}

          <div
            style={{
              fontSize: 11, fontWeight: 700, letterSpacing: '.08em',
              textTransform: 'uppercase', color: 'var(--color-text-muted)',
              padding: '16px 0 6px',
            }}
          >
            Notes
          </div>
          {/* The honest statement of today's search limits (§4.2). */}
          <p style={{ fontSize: 12.5, lineHeight: 1.5, color: 'var(--color-text-muted)', margin: 0 }}>
            Text search matches issue titles and descriptions in the current workspace.
            Use Search for full HQL queries.
          </p>
        </div>
      </div>
    </div>
  )
}
