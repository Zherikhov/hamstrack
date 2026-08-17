import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import {
  ApiResponseError, apiGetIssue, apiListProjects, apiListWorkspaceMembers,
  apiListWorkspaces, apiSearch, savedFilters,
} from '../api'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { useCurrentProject } from '../hooks/useCurrentProject'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { permissionsFrom } from '../hooks/usePermissions'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { getLastWorkspaceId, getRecentProjects } from '../recentProjects'
import { getUiPrefs, setUiPref, PALETTE_RECENTS_MAX } from '../uiPrefs'
import { hqlTextContains } from '../hql'
import { rank } from '../lib/fuzzy'
import { paletteHint } from '../lib/keyboard'
import {
  buildDynamicCommands, buildStaticCommands, parseIssueKey, resolvePaletteWorkspaceId,
  DYNAMIC_SECTIONS, SECTION_CAPS, SECTION_ORDER, SECTION_TITLES, UNRANKED_SECTIONS,
  type Command, type CommandInput, type FastPathState, type IssuesState, type SectionId,
} from './palette/commands'

/**
 * Global command palette (HD-39). Cmd/Ctrl-K from any authenticated shell page:
 * fuzzy-jump to any navigation target, action, project, saved filter, workspace,
 * teammate or issue the user can already reach — keyboard only, start to finish.
 *
 * Mounted once in `AppShell`; visibility lives in `uiStore` so the global
 * shortcut hook has one place to ask "does an overlay own the keyboard?".
 *
 * Two invariants worth keeping:
 *  • every row's text comes from server data rendered as PLAIN TEXT (React text
 *    nodes) — never `dangerouslySetInnerHTML`;
 *  • every user-typed fragment that reaches HQL goes through
 *    `sanitizeForHql` + `hqlQuote` (see `hql.ts`), so a stray quote or backslash
 *    can never produce a 422.
 */
export default function CommandPalette() {
  const open = useUiStore(s => s.paletteOpen)
  // Remounting per open resets query/selection/queries for free — a stale query
  // is a worse default than none (§11).
  return open ? <PalettePanel /> : null
}

/** Debounce window before the server-backed issue search fires (§6.5). */
const SEARCH_DEBOUNCE_MS = 200
/** Below this many characters we never hit the server (§6.5). */
const MIN_SEARCH_CHARS = 2
/** Rows the palette can hold before it scrolls; PageUp/PageDown step (§7.1). */
const PAGE_STEP = 5

const optionId = (id: string) => `cmdk-opt-${id}`

function PalettePanel() {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore(s => s.user)
  const cur = useCurrentProject()
  const closePalette = useUiStore(s => s.closePalette)
  const openCreateIssue = useUiStore(s => s.openCreateIssue)
  const openHelp = useUiStore(s => s.openHelp)
  const reducedMotion = useReducedMotion()

  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const [announcement, setAnnouncement] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const closingRef = useRef(false)
  /**
   * The element that had focus before we opened. Kept in a ref (not just the
   * effect's closure) because a command that REPLACES the palette with another
   * overlay has to hand it over — see the help command in `runCommand` (§7.4).
   */
  const preFocusRef = useRef<HTMLElement | null>(null)

  const trimmed = query.trim()

  // ── Focus: capture on open, trap while open, restore on close (§7.2/§7.4) ───
  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    preFocusRef.current = previouslyFocused
    inputRef.current?.focus()
    return () => {
      // Skip restoration when a command took over — the new page/modal owns focus.
      if (!useUiStore.getState().paletteRestoreFocus) return
      if (previouslyFocused && document.contains(previouslyFocused) && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus()
      } else if (typeof document.body.focus === 'function') {
        document.body.focus()
      }
    }
  }, [])

  const close = useCallback((restoreFocus: boolean) => {
    closingRef.current = true
    closePalette({ restoreFocus })
  }, [closePalette])

  // Safety net: every action closes explicitly, but browser back/forward doesn't.
  const openedAt = useRef(location.pathname)
  useEffect(() => {
    if (location.pathname !== openedAt.current) close(false)
  }, [location.pathname, close])

  // ── Data sources — all pre-existing endpoints, reads only (§4.1) ────────────
  const { data: workspaces } = useQuery({
    queryKey: ['workspaces'],
    queryFn: apiListWorkspaces,
    staleTime: 5 * 60 * 1000,
  })

  const wsId = resolvePaletteWorkspaceId(
    cur?.wsId,
    user ? getLastWorkspaceId(user.id) : null,
    workspaces,
  )

  // A 404 from any of these is SILENT (empty section) — it must never reveal
  // whether a workspace exists (§2).
  const { data: projects } = useQuery({
    queryKey: ['projects', wsId],
    queryFn: () => apiListProjects(wsId!),
    enabled: !!wsId,
    retry: false,
    staleTime: 60_000,
  })
  const { data: filters } = useQuery({
    queryKey: ['savedFilters', wsId],
    queryFn: () => savedFilters.list(wsId!),
    enabled: !!wsId,
    retry: false,
    staleTime: 60_000,
  })
  const { data: members } = useQuery({
    queryKey: ['wsMembers', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId,
    retry: false,
    staleTime: 60_000,
  })

  // ── Server-backed issue search (§6.5) ───────────────────────────────────────
  // The debounced text is part of the query KEY, so a slow response for an older
  // string can never overwrite a newer one.
  const debounced = useDebouncedValue(trimmed, SEARCH_DEBOUNCE_MS)
  const searchEnabled = !!wsId && debounced.length >= MIN_SEARCH_CHARS
  const search = useQuery({
    queryKey: ['palette-search', wsId, debounced],
    queryFn: () => apiSearch(wsId!, { query: hqlTextContains(debounced), page: 0, size: 6 }),
    enabled: searchEnabled,
    placeholderData: prev => prev,
    retry: false,
    staleTime: 30_000,
  })

  const issuesState: IssuesState = useMemo(() => {
    if (!wsId || trimmed.length < MIN_SEARCH_CHARS) return 'idle'
    if (search.isError) return 'error'
    if (search.data) return 'ready'
    return 'loading'
  }, [wsId, trimmed, search.isError, search.data])

  // ── Issue-key fast path (§6.7) ──────────────────────────────────────────────
  // The ROW is optimistic and tracks the live text ("render the row
  // immediately"); the LOOKUP is debounced exactly like the text search, so
  // typing `HD-1234` costs one request instead of one per keystroke — three of
  // which would be 404s for keys the user never meant (§6.5/§6.7).
  const parsedKey = useMemo(() => parseIssueKey(trimmed), [trimmed])
  const lookupKey = useMemo(() => parseIssueKey(debounced), [debounced])
  const findKeyProject = useCallback(
    (key: { prefix: string } | null) =>
      (key ? projects?.find(p => p.key.toUpperCase() === key.prefix) : undefined),
    [projects],
  )
  const keyProject = useMemo(() => findKeyProject(parsedKey), [findKeyProject, parsedKey])
  const lookupProject = useMemo(() => findKeyProject(lookupKey), [findKeyProject, lookupKey])
  const keyIssue = useQuery({
    queryKey: ['issue', wsId, lookupProject?.id, lookupKey?.number],
    queryFn: () => apiGetIssue(wsId!, lookupProject!.id, lookupKey!.number),
    enabled: !!wsId && !!lookupProject && !!lookupKey,
    retry: false,
    staleTime: 30_000,
  })

  // The lookup only describes the row once it has caught up with what is typed;
  // mid-flight (`HD-4` while the user is still typing `HD-42`) the row stays the
  // plain optimistic one rather than borrowing another issue's title/404.
  const keySettled = !!parsedKey && !!lookupKey
    && lookupKey.prefix === parsedKey.prefix && lookupKey.number === parsedKey.number

  const fastPath: FastPathState | null = useMemo(() => {
    if (!wsId || !parsedKey || !keyProject) return null
    // A non-404 failure keeps the row selectable — the destination page renders
    // its own error state, which is more informative than a dead row here.
    const notFound = keySettled && keyIssue.isError && keyIssue.error instanceof ApiResponseError
      && keyIssue.error.status === 404
    return {
      key: `${parsedKey.prefix}-${parsedKey.number}`,
      projectId: keyProject.id,
      number: parsedKey.number,
      title: keySettled ? keyIssue.data?.title : undefined,
      notFound,
    }
  }, [wsId, parsedKey, keyProject, keySettled, keyIssue.isError, keyIssue.error, keyIssue.data])

  // ── Build ───────────────────────────────────────────────────────────────────
  const wsName = workspaces?.find(w => w.id === wsId)?.name

  // The registry is a pure builder with no hook context, so the permissions it
  // gates on are composed HERE and threaded in (HD-116). Both carriers are
  // entries the palette has already fetched — no request is spent to decide
  // whether to draw a row, and an entry that has not arrived denies.
  const permissions = useMemo(
    () => permissionsFrom([
      workspaces?.find(w => w.id === wsId),
      cur ? projects?.find(p => p.id === cur.projectId) : undefined,
    ]),
    [workspaces, wsId, projects, cur],
  )

  const input: CommandInput = useMemo(() => ({
    user,
    wsId,
    wsName,
    currentProject: cur,
    permissions,
    projects: projects ?? [],
    workspaces: workspaces ?? [],
    filters: filters ?? [],
    members: members ?? [],
    issues: search.data?.content ?? [],
    query: trimmed,
    fastPath,
    issuesState,
  }), [user, wsId, wsName, cur, permissions, projects, workspaces, filters, members, search.data, trimmed, fastPath, issuesState])

  const staticCommands = useMemo(() => buildStaticCommands(input), [input])
  const dynamicCommands = useMemo(() => buildDynamicCommands(input), [input])

  // Recents: persisted STATIC ids only, resolved against the CURRENT registry so
  // an id whose command is gone (feature removed, permission lost) is dropped.
  const recentIds = useMemo(
    () => (user ? getUiPrefs(user.id).paletteRecentIds ?? [] : []),
    [user],
  )

  // Project tie-break: local recency journal first, then name (§6.4).
  const projectRecency = useMemo(() => {
    const m = new Map<string, number>()
    if (user) getRecentProjects(user.id).forEach((p, i) => m.set(`project:${p.projectId}`, i))
    return m
  }, [user])

  const groups = useMemo(() => {
    const all = [...staticCommands, ...dynamicCommands]
    const staticById = new Map(staticCommands.map(c => [c.id, c]))
    const out: { section: SectionId; title: string; items: Command[] }[] = []

    for (const section of SECTION_ORDER) {
      let items: Command[]

      if (section === 'recent') {
        // Empty query only (§5.4).
        if (trimmed) continue
        items = recentIds
          .map(id => staticById.get(id))
          .filter((c): c is Command => !!c)
          .slice(0, SECTION_CAPS.recent)
          // Re-key the clone: the same command also appears under Actions/
          // Navigation, and two nodes may not share a DOM id.
          .map(c => ({ ...c, id: `recent:${c.id}`, section: 'recent' as SectionId }))
      } else {
        if (!trimmed && DYNAMIC_SECTIONS.includes(section)) continue
        const pool = all.filter(c => c.section === section)
        items = UNRANKED_SECTIONS.includes(section)
          ? pool
          : rank(trimmed, pool, tieBreakFor(section, projectRecency))
        const cap = SECTION_CAPS[section]
        if (cap != null) items = items.slice(0, cap)
      }

      if (items.length === 0) continue
      const title = section === 'issues' && wsName
        ? `${SECTION_TITLES.issues} · ${wsName}`
        : SECTION_TITLES[section]
      out.push({ section, title, items })
    }
    return out
  }, [staticCommands, dynamicCommands, recentIds, projectRecency, trimmed, wsName])

  const rows = useMemo(() => groups.flatMap(g => g.items), [groups])
  const selectable = useMemo(() => rows.filter(c => !c.disabled), [rows])
  // Identity of the result list — activeIndex resets whenever it changes (§7.1).
  const identity = useMemo(() => selectable.map(c => c.id).join('|'), [selectable])

  useEffect(() => { setActiveIndex(0) }, [identity])

  const clamped = selectable.length ? Math.min(activeIndex, selectable.length - 1) : -1
  const activeId = clamped >= 0 ? selectable[clamped].id : null

  // Keep the active row visible. jsdom has no scrollIntoView — guard, don't throw.
  useEffect(() => {
    const el = listRef.current?.querySelector('[data-active="true"]') as HTMLElement | null
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' })
  }, [activeId])

  // Polite announcement once the result set settles (§10).
  useEffect(() => {
    const t = setTimeout(() => {
      if (issuesState === 'loading') setAnnouncement('Searching issues')
      else setAnnouncement(selectable.length ? `${selectable.length} results` : 'No results')
    }, 300)
    return () => clearTimeout(t)
  }, [identity, issuesState, selectable.length])

  // ── Running a row ───────────────────────────────────────────────────────────
  const runCommand = useCallback((cmd: Command) => {
    if (cmd.disabled) return
    const baseId = cmd.id.startsWith('recent:') ? cmd.id.slice('recent:'.length) : cmd.id
    // Persist ONLY stable, workspace-free ids — never tenant data (§11).
    if (user && (cmd.kind === 'action' || cmd.kind === 'nav')) {
      const prev = getUiPrefs(user.id).paletteRecentIds ?? []
      setUiPref(user.id, 'paletteRecentIds',
        [baseId, ...prev.filter(id => id !== baseId)].slice(0, PALETTE_RECENTS_MAX))
    }
    // Close FIRST, then act — an overlay mid-teardown must never eat a keystroke.
    // This also clears `paletteOpen` synchronously (zustand `set` is not batched),
    // which is what lets `openHelp`'s "never stack two focus traps" guard pass.
    close(false)
    cmd.run({
      navigate,
      openCreateIssue,
      // We skipped focus restoration (`close(false)`), so the sheet would capture
      // `document.activeElement` = <body> and hand focus to nothing on close.
      // Pass OUR saved element instead (§7.4).
      openHelp: () => openHelp(preFocusRef.current),
    })
  }, [user, close, navigate, openCreateIssue, openHelp])

  const move = useCallback((delta: number, wrap: boolean) => {
    setActiveIndex(i => {
      const len = selectable.length
      if (len === 0) return 0
      const from = Math.min(i, len - 1)
      const next = from + delta
      return wrap
        ? ((next % len) + len) % len
        : Math.max(0, Math.min(len - 1, next))
    })
  }, [selectable.length])

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    // Cmd/Ctrl-K toggles: while we're open it closes us (§3.2).
    if ((e.metaKey || e.ctrlKey) && !e.altKey && (e.key === 'k' || e.key === 'K')) {
      e.preventDefault()
      close(true)
      return
    }
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); move(1, true); return
      case 'ArrowUp': e.preventDefault(); move(-1, true); return
      // Tab moves the SELECTION, never DOM focus — the panel has exactly one
      // focusable element and it keeps focus for the palette's whole lifetime.
      case 'Tab': e.preventDefault(); move(e.shiftKey ? -1 : 1, true); return
      case 'PageDown': e.preventDefault(); move(PAGE_STEP, false); return
      case 'PageUp': e.preventDefault(); move(-PAGE_STEP, false); return
      case 'Enter': {
        e.preventDefault()
        const cmd = clamped >= 0 ? selectable[clamped] : null
        if (cmd) runCommand(cmd)
        return
      }
      case 'Escape': e.preventDefault(); close(true); return
      // Home/End belong to the text caret — deliberately not intercepted.
      default: return
    }
  }

  // ── Render (§12 Beacon) ─────────────────────────────────────────────────────
  const enter = reducedMotion ? undefined : 'cmdk-panel-in'
  const showHairline = search.isFetching && !!search.data

  return (
    <div
      // Not `data-modal-open`: the palette's own flag already lives in uiStore,
      // and double-counting would be redundant (the DOM probe is for modals the
      // store doesn't know about).
      style={{
        position: 'fixed', inset: 0, zIndex: 60,
        background: 'rgba(16,24,40,0.45)', backdropFilter: 'blur(2px)',
        display: 'flex', justifyContent: 'center', alignItems: 'flex-start',
        animation: reducedMotion ? undefined : 'cmdk-fade-in 120ms ease-out',
      }}
      onMouseDown={() => close(true)}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        onMouseDown={e => e.stopPropagation()}
        className="cmdk-panel"
        style={{
          width: 640, maxWidth: '92vw', marginTop: '12vh',
          background: 'var(--color-card)',
          border: '1px solid var(--color-border-2)',
          borderRadius: 'var(--radius-xl)',
          boxShadow: 'var(--shadow-lg)',
          maxHeight: 'min(60vh, 560px)',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
          animation: enter ? `${enter} 160ms ease-out` : undefined,
        }}
      >
        {/* Input row */}
        <div
          style={{
            position: 'relative', display: 'flex', alignItems: 'center', gap: 11,
            height: 56, padding: '0 18px', borderBottom: '1px solid var(--color-border)',
            flexShrink: 0,
          }}
        >
          <Search size={17} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} />
          <input
            ref={inputRef}
            value={query}
            onChange={e => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            onBlur={() => {
              // Single-element focus trap: take focus back unless we're closing.
              if (!closingRef.current) requestAnimationFrame(() => inputRef.current?.focus())
            }}
            role="combobox"
            aria-expanded="true"
            aria-controls="cmdk-list"
            aria-autocomplete="list"
            aria-activedescendant={activeId ? optionId(activeId) : undefined}
            aria-label="Search commands, projects and issues"
            autoComplete="off"
            spellCheck={false}
            placeholder="Search issues, projects and commands…"
            className="flex-1 min-w-0 outline-none"
            style={{
              border: 'none', background: 'transparent',
              fontSize: 15, fontWeight: 450, color: 'var(--color-text)',
            }}
          />
          <span
            className="mono"
            style={{
              fontSize: 10.5, padding: '2px 6px', borderRadius: 'var(--radius-sm)',
              border: '1px solid var(--color-border-2)', background: 'var(--color-surface-2)',
              color: 'var(--color-text-secondary)', flexShrink: 0,
            }}
          >esc</span>
          {showHairline && (
            <span
              aria-hidden="true"
              style={{
                position: 'absolute', left: 0, right: 0, bottom: -1, height: 2,
                background: 'var(--color-brand)',
                opacity: reducedMotion ? 0.5 : undefined,
                animation: reducedMotion ? undefined : 'cmdk-hairline 1s ease-in-out infinite',
              }}
            />
          )}
        </div>

        {/* Results */}
        <div
          ref={listRef}
          id="cmdk-list"
          role="listbox"
          aria-label="Results"
          style={{ overflowY: 'auto', padding: '8px 0 6px', flex: 1, minHeight: 0 }}
        >
          {/* "Nothing matched" = nothing but the always-present escape hatch
              (§6.8). The info row itself stays rendered and selectable. */}
          {rows.every(r => r.section === 'info') && (
            <div
              style={{
                padding: '28px 18px 8px', textAlign: 'center',
                fontSize: 13, color: 'var(--color-text-muted)',
              }}
            >
              {trimmed ? `No matches for "${trimmed}"` : 'Type to search'}
            </div>
          )}

          {groups.map(group => (
            <div key={group.section} role="group" aria-label={group.title || 'More'}>
              {group.title && (
                <div
                  aria-hidden="true"
                  style={{
                    fontSize: 11, fontWeight: 700, letterSpacing: '.08em',
                    textTransform: 'uppercase', color: 'var(--color-text-muted)',
                    padding: '12px 18px 4px',
                  }}
                >
                  {group.title}
                </div>
              )}
              {group.items.map(cmd => (
                <Row
                  key={cmd.id}
                  cmd={cmd}
                  active={cmd.id === activeId}
                  onHover={() => {
                    const idx = selectable.findIndex(c => c.id === cmd.id)
                    if (idx >= 0) setActiveIndex(idx)
                  }}
                  onRun={() => runCommand(cmd)}
                />
              ))}
            </div>
          ))}

          {!wsId && (
            <div
              style={{
                padding: '10px 18px 4px', fontSize: 12,
                color: 'var(--color-text-muted)',
              }}
            >
              Join or create a workspace to search issues.
            </div>
          )}
        </div>

        {/* Footer hints — hidden on narrow viewports (§12 responsive) */}
        <div
          className="mono cmdk-footer"
          style={{
            height: 34, flexShrink: 0, display: 'flex', alignItems: 'center',
            justifyContent: 'space-between', padding: '0 18px',
            borderTop: '1px solid var(--color-border)',
            fontSize: 11, color: 'var(--color-text-muted)',
          }}
        >
          <span>↑↓ navigate · ↵ open · esc close</span>
          <span>{paletteHint()}</span>
        </div>

        {/* Screen-reader result count */}
        <div
          aria-live="polite"
          aria-atomic="true"
          style={{
            position: 'absolute', width: 1, height: 1, overflow: 'hidden',
            clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap',
          }}
        >
          {announcement}
        </div>
      </div>
    </div>
  )
}

/** Section-native tie-break for equal fuzzy scores (§6.4) — total determinism. */
function tieBreakFor(section: SectionId, projectRecency: Map<string, number>) {
  if (section === 'projects') {
    return (a: Command, b: Command) => {
      const ra = projectRecency.get(a.id) ?? Number.MAX_SAFE_INTEGER
      const rb = projectRecency.get(b.id) ?? Number.MAX_SAFE_INTEGER
      if (ra !== rb) return ra - rb
      return a.label.localeCompare(b.label)
    }
  }
  return (a: Command, b: Command) => a.label.localeCompare(b.label)
}

function Row({ cmd, active, onHover, onRun }: {
  cmd: Command
  active: boolean
  onHover: () => void
  onRun: () => void
}) {
  const Icon = cmd.icon
  const color = cmd.disabled
    ? 'var(--color-text-muted)'
    : active ? 'var(--color-brand)' : 'var(--color-text-secondary)'

  return (
    <div
      id={optionId(cmd.id)}
      role="option"
      aria-selected={active}
      aria-disabled={cmd.disabled || undefined}
      data-active={active || undefined}
      tabIndex={-1}
      onMouseEnter={cmd.disabled ? undefined : onHover}
      onClick={cmd.disabled ? undefined : onRun}
      className="flex items-center"
      style={{
        position: 'relative', height: 40, margin: '0 6px', padding: '0 12px', gap: 11,
        borderRadius: 'var(--radius-md)',
        cursor: cmd.disabled ? 'default' : 'pointer',
        background: active && !cmd.disabled
          ? 'color-mix(in srgb, var(--color-brand) 10%, white)'
          : 'transparent',
      }}
    >
      {/* Selection is never conveyed by colour alone (§10). */}
      {active && !cmd.disabled && (
        <span
          aria-hidden="true"
          style={{
            position: 'absolute', left: 0, top: 11, width: 2, height: 18,
            borderRadius: 1, background: 'var(--color-brand)',
          }}
        />
      )}

      {cmd.dotColor ? (
        <span
          aria-hidden="true"
          style={{
            width: 9, height: 9, borderRadius: 999, flexShrink: 0,
            background: cmd.dotColor,
          }}
        />
      ) : Icon ? (
        <Icon size={16} style={{ color, flexShrink: 0 }} />
      ) : (
        <span aria-hidden="true" style={{ width: 16, flexShrink: 0 }} />
      )}

      <span className="flex-1 min-w-0 truncate">
        <span
          style={{
            fontSize: 13.5, fontWeight: 600,
            color: cmd.disabled ? 'var(--color-text-muted)' : 'var(--color-text)',
          }}
        >
          {cmd.label}
        </span>
        {cmd.sublabel && (
          <span style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>
            {' · '}{cmd.sublabel}
          </span>
        )}
      </span>

      {cmd.meta && (
        <span className="mono flex-shrink-0" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
          {cmd.meta}
        </span>
      )}
      {cmd.shortcut && (
        <span className="flex items-center gap-1 flex-shrink-0">
          {cmd.shortcut.map((k, i) => <Kbd key={i}>{k}</Kbd>)}
        </span>
      )}
    </div>
  )
}

export function Kbd({ children }: { children: React.ReactNode }) {
  return (
    <span
      className="mono"
      style={{
        fontSize: 10.5, padding: '2px 6px', borderRadius: 'var(--radius-sm)',
        border: '1px solid var(--color-border-2)', background: 'var(--color-surface-2)',
        color: 'var(--color-text-secondary)', whiteSpace: 'nowrap',
      }}
    >
      {children}
    </span>
  )
}
