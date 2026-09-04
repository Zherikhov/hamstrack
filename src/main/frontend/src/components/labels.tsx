import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Plus, Tag, X } from 'lucide-react'
import { ApiResponseError, labelsApi } from '../api'
import type { LabelMatch } from '../api'
import type { Label, LabelRef } from '../types'
import { NEUTRAL_EDGE, NEUTRAL_FILL, SURFACE, fillOf, ringOn, tintOf } from '../colour'

/**
 * Label rendering + picking (HD-30) — the labels counterpart of
 * `components/fields.tsx`: every surface that shows or edits labels (create
 * dialog, issue detail, board cards, backlog rows, workspace settings) goes
 * through this module so a chip looks the same everywhere.
 *
 * Labels are workspace-scoped content, NOT project taxonomy: they are fetched
 * from their own endpoint under `labelsKey(wsId)` and never travel in
 * `ProjectConfig` (so recoloring one doesn't invalidate every board's config).
 */

/**
 * The 8 auto-color swatches, taken from the DESIGN.md palette (brand teal,
 * amber, slate, error, success, violet, blue, yellow). This list and its ORDER
 * mirror `LabelService.PALETTE` exactly — the server assigns the same swatch to
 * a color-less label, so an optimistic client-side preview matches what comes
 * back. The only place in the SPA allowed to hold literal hex (DESIGN.md).
 */
export const LABEL_PALETTE = [
  '#0EA5A4', // brand teal
  '#F79009', // amber
  '#667085', // slate
  '#F04438', // error
  '#12B981', // success
  '#7C6CF5', // violet
  '#3B5BFD', // blue
  '#EAB308', // yellow
] as const

/**
 * Deterministic auto-color for a label name — `palette[floorMod(hash, 8)]` over
 * the lower-cased name, replicating Java's `String.hashCode` (31·h + charCode,
 * wrapped to a signed 32-bit int) so the client preview and the server's
 * `LabelService.colorForName` agree on the same swatch.
 */
export function colorForName(name: string): string {
  let hash = 0
  const lower = name.toLowerCase()
  for (let i = 0; i < lower.length; i++) {
    // Math.imul keeps the multiply in 32-bit space, exactly like the JVM.
    hash = (Math.imul(31, hash) + lower.charCodeAt(i)) | 0
  }
  const idx = ((hash % LABEL_PALETTE.length) + LABEL_PALETTE.length) % LABEL_PALETTE.length
  return LABEL_PALETTE[idx]
}

/**
 * Client-side soft cap, mirroring `app.classification.max-labels-per-issue`
 * (default 20). Not exposed through `/api/meta`, so this is a courtesy guard
 * only — the server still returns 422 above its own configured limit.
 */
export const MAX_LABELS_PER_ISSUE = 20

/** Query key for a workspace's label list. */
export function labelsKey(wsId: string | undefined) {
  return ['labels', wsId] as const
}

/** The workspace's non-archived labels — the source for every picker/filter. */
export function useWorkspaceLabels(wsId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: labelsKey(wsId),
    queryFn: () => labelsApi.list(wsId!),
    enabled: !!wsId && enabled,
  })
}

// ── Chip ──────────────────────────────────────────────────────────────────────

/**
 * One label as a pill: color dot + name, `--radius-full` (DESIGN.md: pills). An
 * archived label that is still attached to an issue renders dimmed rather than
 * disappearing — the issue's history stays readable.
 */
export function LabelChip({ label, onRemove, title, compact }: {
  label: LabelRef
  /** Renders a trailing ✕ (picker/editor use). */
  onRemove?: () => void
  title?: string
  compact?: boolean
}) {
  // The chip's text is a fixed neutral token and always was — this is the rule
  // HD-176 generalised to every other stored colour, not a surface it changed.
  // What it gains is an opaque tint (measured, rather than composited over
  // whatever is behind the row) and a ring on the dot, so a pale label keeps a
  // visible edge. No padding, radius or size moves.
  const c = label.color || null
  const tint = c ? tintOf(c, SURFACE.card, 0.12) : NEUTRAL_FILL
  const edge = c ? tintOf(c, SURFACE.card, 0.4) : NEUTRAL_EDGE
  return (
    <span
      className="inline-flex items-center gap-1 border max-w-full"
      title={title ?? (label.archived ? `${label.name} (archived)` : label.name)}
      style={{
        borderRadius: 'var(--radius-full, 9999px)',
        padding: compact ? '1px 7px' : '2px 8px',
        fontSize: compact ? 11 : 12,
        lineHeight: 1.4,
        background: tint,
        borderColor: edge,
        color: 'var(--color-text-secondary)',
        opacity: label.archived ? 0.55 : 1,
      }}
    >
      <span className="rounded-full flex-shrink-0"
            style={{ width: 6, height: 6, background: fillOf(c), boxShadow: `inset 0 0 0 1px ${ringOn(c, tint)}` }} />
      <span className="truncate">{label.name}</span>
      {onRemove && (
        <button
          type="button"
          aria-label={`Remove label ${label.name}`}
          onClick={e => { e.stopPropagation(); onRemove() }}
          className="cursor-pointer flex-shrink-0 hover:opacity-60 transition-opacity"
          style={{ color: 'var(--color-text-muted)', display: 'inline-flex' }}
        >
          <X size={11} />
        </button>
      )}
    </span>
  )
}

/**
 * A row of chips capped at `max` with a "+k" overflow marker — the board card
 * and backlog cell rendering (DESIGN.md: compact in the board).
 */
export function LabelChips({ labels, max = 3, compact }: {
  labels: LabelRef[] | undefined
  max?: number
  compact?: boolean
}) {
  if (!labels || labels.length === 0) return null
  const shown = labels.slice(0, max)
  const rest = labels.length - shown.length
  return (
    <div className="flex items-center gap-1 flex-wrap min-w-0">
      {shown.map(l => <LabelChip key={l.id} label={l} compact={compact} />)}
      {rest > 0 && (
        <span
          className="mono flex-shrink-0"
          title={labels.slice(max).map(l => l.name).join(', ')}
          style={{ fontSize: compact ? 10.5 : 11, color: 'var(--color-text-muted)' }}
        >
          +{rest}
        </span>
      )}
    </div>
  )
}

// ── Picker ────────────────────────────────────────────────────────────────────

/** Case-insensitive equality on the normalized (collapsed-whitespace) name. */
function normalizeName(raw: string) {
  return raw.trim().replace(/\s+/g, ' ')
}

/**
 * Multi-select typeahead over the workspace's labels, with on-the-fly creation
 * (any workspace member may create a label — curation is the settings page's
 * job). `value` is the selected label ids; `known` supplies display info for
 * ids the active list doesn't carry (an archived label still attached to the
 * issue being edited).
 *
 * Duplicate handling: an exact case-insensitive match in the cached list is
 * attached instead of POSTed, and a 409 that slips through carries `existingId`
 * so the losing side of a concurrent create recovers in ONE round-trip.
 */
export function LabelPicker({
  wsId, value, onChange, known = [], autoFocus, placeholder = 'Add a label…', maxLabels = MAX_LABELS_PER_ISSUE,
}: {
  wsId: string
  value: string[]
  onChange: (ids: string[]) => void
  known?: LabelRef[]
  autoFocus?: boolean
  placeholder?: string
  maxLabels?: number
}) {
  const qc = useQueryClient()
  const { data: labels = [] } = useWorkspaceLabels(wsId)
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [highlight, setHighlight] = useState(0)
  const wrapRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const byId = useMemo(() => {
    const map = new Map<string, LabelRef>()
    known.forEach(l => map.set(l.id, l))
    labels.forEach(l => map.set(l.id, l))
    return map
  }, [labels, known])

  const selected = value.map(id => byId.get(id) ?? { id, name: '…', color: '#667085', archived: false })
  const atCap = value.length >= maxLabels

  const normalized = normalizeName(query)
  const matches = useMemo(() => {
    const q = normalized.toLowerCase()
    return labels
      .filter(l => !value.includes(l.id))
      .filter(l => !q || l.name.toLowerCase().includes(q))
      .slice(0, 50)
  }, [labels, value, normalized])

  // Offer "Create" only when nothing in the workspace already holds the name
  // (case-insensitively) — the pre-check that makes the 409 path rare.
  const exact = labels.find(l => l.name.toLowerCase() === normalized.toLowerCase())
  const canCreate = normalized.length > 0 && normalized.length <= 60 && !exact && !atCap

  useEffect(() => { setHighlight(0) }, [normalized, open])

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  function add(id: string) {
    if (value.includes(id) || value.length >= maxLabels) return
    onChange([...value, id])
    setQuery('')
    setError('')
    inputRef.current?.focus()
  }

  function remove(id: string) {
    onChange(value.filter(x => x !== id))
  }

  async function createLabel() {
    const name = normalized
    if (!name || busy) return
    // An exact match already in the cache never needs a round-trip.
    if (exact) { add(exact.id); return }
    setBusy(true)
    setError('')
    try {
      const created = await labelsApi.create(wsId, { name })
      qc.setQueryData<Label[]>(labelsKey(wsId), old => (old ? [...old, created] : [created]))
      qc.invalidateQueries({ queryKey: labelsKey(wsId) })
      add(created.id)
    } catch (err: unknown) {
      // 409 + existingId: someone (or the archived-name rule) already owns the
      // name — attach that label instead of making the user retype.
      if (err instanceof ApiResponseError && err.status === 409 && err.existingId) {
        const existingId = err.existingId
        await qc.invalidateQueries({ queryKey: labelsKey(wsId) })
        add(existingId)
      } else {
        setError(err instanceof Error ? err.message : 'Failed to create label')
      }
    } finally {
      setBusy(false)
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    const rows = matches.length + (canCreate ? 1 : 0)
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setHighlight(h => Math.min(rows - 1, h + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setHighlight(h => Math.max(0, h - 1)) }
    else if (e.key === 'Escape') { if (open) { e.preventDefault(); setOpen(false) } }
    else if (e.key === 'Backspace' && query === '' && value.length > 0) {
      e.preventDefault()
      remove(value[value.length - 1])
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (highlight < matches.length) { const m = matches[highlight]; if (m) add(m.id) }
      else if (canCreate) createLabel()
    }
  }

  return (
    <div className="flex flex-col gap-1" ref={wrapRef}>
      <div
        className="flex items-center gap-1 flex-wrap rounded-md border px-2 py-1.5 cursor-text"
        style={{ background: 'white', borderColor: open ? 'var(--color-brand)' : 'var(--color-border-2)' }}
        onClick={() => { setOpen(true); inputRef.current?.focus() }}
      >
        {selected.map(l => <LabelChip key={l.id} label={l} onRemove={() => remove(l.id)} />)}
        <input
          ref={inputRef}
          value={query}
          autoFocus={autoFocus}
          onChange={e => { setQuery(e.target.value); setOpen(true) }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          placeholder={atCap ? `Limit of ${maxLabels} labels reached` : (selected.length === 0 ? placeholder : '')}
          aria-label="Labels"
          disabled={atCap && query === ''}
          className="flex-1 text-sm outline-none bg-transparent"
          style={{ minWidth: 90, color: 'var(--color-text)' }}
        />
      </div>

      {open && (matches.length > 0 || canCreate || normalized.length > 0) && (
        <div
          role="listbox"
          className="rounded-md border overflow-y-auto"
          style={{
            maxHeight: 220, background: 'var(--color-card)', borderColor: 'var(--color-border-2)',
            boxShadow: 'var(--shadow-lg)', padding: 4,
          }}
        >
          {matches.map((l, idx) => (
            <button
              key={l.id}
              type="button"
              role="option"
              aria-selected={false}
              onMouseEnter={() => setHighlight(idx)}
              onMouseDown={e => e.preventDefault()}
              onClick={() => add(l.id)}
              className="w-full flex items-center gap-2 text-left cursor-pointer"
              style={{
                padding: '6px 8px', borderRadius: 'var(--radius-sm)',
                background: idx === highlight ? 'var(--color-surface)' : 'transparent',
              }}
            >
              <span className="rounded-full flex-shrink-0"
                    style={{ width: 8, height: 8, background: fillOf(l.color), boxShadow: `inset 0 0 0 1px ${ringOn(l.color, SURFACE.card)}` }} />
              <span className="text-sm truncate flex-1">{l.name}</span>
              {l.description && (
                <span className="text-xs truncate" style={{ color: 'var(--color-text-muted)', maxWidth: 120 }}>
                  {l.description}
                </span>
              )}
            </button>
          ))}
          {canCreate && (
            <button
              type="button"
              onMouseEnter={() => setHighlight(matches.length)}
              onMouseDown={e => e.preventDefault()}
              onClick={createLabel}
              disabled={busy}
              className="w-full flex items-center gap-2 text-left cursor-pointer"
              style={{
                padding: '6px 8px', borderRadius: 'var(--radius-sm)',
                background: highlight === matches.length ? 'var(--color-surface)' : 'transparent',
                color: 'var(--color-brand-ink)',
              }}
            >
              <Plus size={13} className="flex-shrink-0" />
              <span className="text-sm truncate">Create label “{normalized}”</span>
              <span className="rounded-full flex-shrink-0 ml-auto"
                    style={{ width: 8, height: 8, background: colorForName(normalized) }} />
            </button>
          )}
          {matches.length === 0 && !canCreate && (
            <div className="text-xs px-2 py-1.5" style={{ color: 'var(--color-text-muted)' }}>
              {atCap ? `At most ${maxLabels} labels per issue.` : 'Already added.'}
            </div>
          )}
        </div>
      )}

      {error && <span className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</span>}
    </div>
  )
}

// ── Server-side filter control (board + backlog) ──────────────────────────────

/**
 * The board/backlog label filter. Server-side by design (HD-30 §3.6): a
 * classification filter must search the whole project, not just the issues the
 * capped board already loaded — so the selection becomes `?labelId=` query
 * params and part of the query key, unlike the HD-43 client-side chips.
 *
 * The any/all toggle only appears at 2+ selected labels, where it first means
 * something.
 */
export function LabelFilter({ wsId, value, onChange, match, onMatchChange }: {
  wsId: string | undefined
  value: string[]
  onChange: (ids: string[]) => void
  match: LabelMatch
  onMatchChange: (m: LabelMatch) => void
}) {
  const { data: labels, isSuccess } = useWorkspaceLabels(wsId)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const wrapRef = useRef<HTMLDivElement>(null)

  // A label deleted (or archived) since the selection was made would filter the
  // board down to nothing forever — drop those ids once the list has loaded.
  useEffect(() => {
    if (!isSuccess || !labels || value.length === 0) return
    const live = new Set(labels.map(l => l.id))
    const kept = value.filter(id => live.has(id))
    if (kept.length !== value.length) onChange(kept)
  }, [isSuccess, labels, value, onChange])

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const all = labels ?? []
  const q = query.trim().toLowerCase()
  const visible = q ? all.filter(l => l.name.toLowerCase().includes(q)) : all
  const selectedNames = value
    .map(id => all.find(l => l.id === id)?.name)
    .filter((n): n is string => !!n)

  const label = value.length === 0
    ? 'All labels'
    : value.length === 1
      ? (selectedNames[0] ?? '1 label')
      : `${value.length} labels`

  function toggle(id: string) {
    onChange(value.includes(id) ? value.filter(x => x !== id) : [...value, id])
  }

  const active = value.length > 0

  return (
    <div className="flex items-center gap-1.5">
      <div className="relative" ref={wrapRef}>
        <button
          type="button"
          aria-label="Filter by label"
          aria-haspopup="listbox"
          aria-expanded={open}
          title={selectedNames.length > 0 ? selectedNames.join(', ') : 'Filter by label'}
          onClick={() => setOpen(o => !o)}
          className="rounded-md border cursor-pointer flex items-center gap-1.5 px-2.5 py-1.5 text-xs transition-colors"
          style={{
            background: active ? 'color-mix(in srgb, var(--color-brand) 12%, white)' : 'var(--color-card)',
            borderColor: active || open ? 'var(--color-brand)' : 'var(--color-border-2)',
            color: active ? 'var(--color-brand-ink)' : 'var(--color-text)',
            maxWidth: 190,
          }}
        >
          <Tag size={12} className="flex-shrink-0" />
          <span className="truncate">{label}</span>
        </button>

        {open && (
          <div
            role="listbox"
            className="absolute left-0 mt-1 rounded-md border z-30"
            style={{
              minWidth: 240, maxHeight: 300, overflowY: 'auto', padding: 4,
              background: 'var(--color-card)', borderColor: 'var(--color-border-2)',
              boxShadow: 'var(--shadow-lg)',
            }}
          >
            <input
              value={query}
              autoFocus
              onChange={e => setQuery(e.target.value)}
              placeholder="Find a label…"
              aria-label="Find a label"
              className="w-full text-sm rounded-md border px-2 py-1.5 mb-1 outline-none"
              style={{ borderColor: 'var(--color-border-2)', color: 'var(--color-text)' }}
            />
            {visible.map(l => {
              const on = value.includes(l.id)
              return (
                <button
                  key={l.id}
                  type="button"
                  role="option"
                  aria-selected={on}
                  onClick={() => toggle(l.id)}
                  className="w-full flex items-center gap-2 text-left cursor-pointer hover:bg-[var(--color-surface)]"
                  style={{ padding: '6px 8px', borderRadius: 'var(--radius-sm)' }}
                >
                  <span className="rounded-full flex-shrink-0"
                        style={{ width: 8, height: 8, background: fillOf(l.color), boxShadow: `inset 0 0 0 1px ${ringOn(l.color, SURFACE.card)}` }} />
                  <span className="text-sm truncate flex-1">{l.name}</span>
                  {on && <Check size={13} style={{ color: 'var(--color-brand-ink)', flexShrink: 0 }} />}
                </button>
              )
            })}
            {visible.length === 0 && (
              <div className="text-xs px-2 py-1.5" style={{ color: 'var(--color-text-muted)' }}>
                {all.length === 0 ? 'No labels in this workspace yet.' : 'No match.'}
              </div>
            )}
            {value.length > 0 && (
              <button
                type="button"
                onClick={() => onChange([])}
                className="w-full text-left text-xs cursor-pointer hover:underline"
                style={{ padding: '6px 8px', color: 'var(--color-text-muted)' }}
              >
                Clear labels
              </button>
            )}
          </div>
        )}
      </div>

      {/* any/all only matters once two labels are picked */}
      {value.length >= 2 && (
        <div
          className="flex items-center gap-0.5 rounded-md p-0.5"
          role="group"
          aria-label="Label match mode"
          style={{ background: 'var(--color-surface-2)' }}
        >
          {(['any', 'all'] as const).map(m => (
            <button
              key={m}
              type="button"
              aria-pressed={match === m}
              onClick={() => onMatchChange(m)}
              title={m === 'any' ? 'Issues with any of the selected labels' : 'Issues with all of the selected labels'}
              className="text-xs px-2 py-0.5 rounded cursor-pointer transition-colors"
              style={{
                background: match === m ? 'white' : 'transparent',
                color: match === m ? 'var(--color-brand-ink)' : 'var(--color-text-muted)',
                fontWeight: match === m ? 600 : 400,
                boxShadow: match === m ? 'var(--shadow-card)' : 'none',
              }}
            >
              {m}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
