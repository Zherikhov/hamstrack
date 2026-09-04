import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { versionsApi } from '../api'
import { Select } from './ui'
import type { Version, VersionRef } from '../types'

/**
 * Version rendering + picking (HD-32) — the versions counterpart of
 * `components/labels.tsx` and `components/projectComponents.tsx`: every surface
 * that shows or picks a version (create dialog, issue detail, backlog rows,
 * board/backlog filter bars, the Releases page) goes through this module so one
 * version reads the same everywhere.
 *
 * Versions are PROJECT-scoped content with a lifecycle, not project taxonomy:
 * they are fetched from their own endpoint under `versionsKey(wsId, projectId)`
 * and never travel in `ProjectConfig` (so releasing one doesn't invalidate every
 * board's config). Many-per-issue, in two independent roles (fix / affects) —
 * hence a multi-select picker, not the single `Select` components use.
 *
 * DESIGN.md: a version NAME is inspectable data, like an issue key, so it always
 * renders in IBM Plex Mono (`.mono`). Released state uses the success tint,
 * unreleased the slate/info tint.
 */

/**
 * Client-side soft cap, mirroring `app.classification.max-version-links-per-issue`
 * (default 20, enforced PER ROLE). Not exposed through `/api/meta`, so this is a
 * courtesy guard only — the server still returns 422 above its own limit.
 */
export const MAX_VERSION_LINKS_PER_ISSUE = 20

/** Query key for one project's version list. */
export function versionsKey(wsId: string | undefined, projectId: string | undefined) {
  return ['versions', wsId, projectId] as const
}

/**
 * The project's non-archived versions (released ones included — back-porting a
 * fix into a shipped release is legitimate, §6.4). The source for every
 * picker/filter; the Releases page uses its own key with archived rows.
 */
export function useProjectVersions(
  wsId: string | undefined,
  projectId: string | undefined,
  enabled = true,
) {
  return useQuery({
    queryKey: versionsKey(wsId, projectId),
    queryFn: () => versionsApi.list(wsId!, projectId!),
    enabled: !!wsId && !!projectId && enabled,
  })
}

// ── Badge ─────────────────────────────────────────────────────────────────────

/** Success tint for a shipped release, slate/info for one still in flight. */
function versionTint(released: boolean) {
  return released ? 'var(--color-success)' : 'var(--color-info)'
}

/**
 * One version as a pill: state dot + mono name. An archived version still linked
 * to an issue renders dimmed rather than disappearing — the issue's
 * classification stays readable after the version is retired.
 */
export function VersionBadge({ version, onRemove, compact }: {
  version: VersionRef
  /** Renders a trailing ✕ (picker/editor use). */
  onRemove?: () => void
  compact?: boolean
}) {
  const c = versionTint(version.released)
  const state = version.released ? 'released' : 'unreleased'
  return (
    <span
      className="inline-flex items-center gap-1 border max-w-full"
      title={version.archived ? `${version.name} (${state}, archived)` : `${version.name} (${state})`}
      style={{
        borderRadius: 'var(--radius-full, 9999px)',
        padding: compact ? '1px 7px' : '2px 8px',
        lineHeight: 1.4,
        background: `color-mix(in srgb, ${c} 12%, white)`,
        borderColor: `color-mix(in srgb, ${c} 40%, white)`,
        color: 'var(--color-text-secondary)',
        opacity: version.archived ? 0.55 : 1,
      }}
    >
      <span className="rounded-full flex-shrink-0" style={{ width: 6, height: 6, background: c }} />
      {/* DESIGN.md: version names are inspectable data → IBM Plex Mono */}
      <span className="mono truncate" style={{ fontSize: compact ? 11 : 12 }}>{version.name}</span>
      {onRemove && (
        <button
          type="button"
          aria-label={`Remove version ${version.name}`}
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
 * A row of version badges capped at `max` with a "+k" overflow marker — the
 * backlog cell rendering (DESIGN.md: compact in dense views).
 */
export function VersionBadges({ versions, max = 2, compact }: {
  versions: VersionRef[] | undefined
  max?: number
  compact?: boolean
}) {
  if (!versions || versions.length === 0) return null
  const shown = versions.slice(0, max)
  const rest = versions.length - shown.length
  return (
    <div className="flex items-center gap-1 flex-wrap min-w-0">
      {shown.map(v => <VersionBadge key={v.id} version={v} compact={compact} />)}
      {rest > 0 && (
        <span
          className="mono flex-shrink-0"
          title={versions.slice(max).map(v => v.name).join(', ')}
          style={{ fontSize: compact ? 10.5 : 11, color: 'var(--color-text-muted)' }}
        >
          +{rest}
        </span>
      )}
    </div>
  )
}

/** Released / Unreleased state pill — the Releases page card header. */
export function ReleasedPill({ released }: { released: boolean }) {
  const c = versionTint(released)
  return (
    <span
      className="inline-flex items-center gap-1 flex-shrink-0"
      style={{
        borderRadius: 'var(--radius-full, 9999px)',
        padding: '2px 9px',
        fontSize: 11,
        fontWeight: 600,
        background: `color-mix(in srgb, ${c} 14%, white)`,
        border: `1px solid color-mix(in srgb, ${c} 38%, white)`,
        color: c,
      }}
    >
      <span className="rounded-full" style={{ width: 6, height: 6, background: c }} />
      {released ? 'Released' : 'Unreleased'}
    </span>
  )
}

// ── Picker ────────────────────────────────────────────────────────────────────

/**
 * Multi-select over the project's versions for ONE role (fix or affects). There
 * is no on-the-fly creation here (unlike labels): a version is curated on the
 * Releases page by a project MANAGER / workspace admin, so the picker only
 * chooses among existing ones.
 *
 * Unreleased versions are grouped above released ones (the common case first),
 * matching the list order the server returns. `known` supplies display info for
 * ids the active list doesn't carry — an ARCHIVED version already linked to the
 * issue being edited stays selectable and removable, exactly as the component and
 * label pickers keep their stale current value.
 */
export function VersionPicker({
  wsId, projectId, value, onChange, known = [], label, placeholder = 'Add a version…',
  maxVersions = MAX_VERSION_LINKS_PER_ISSUE, autoFocus,
}: {
  wsId: string
  projectId: string
  value: string[]
  onChange: (ids: string[]) => void
  /** Versions already linked to this issue (may include archived ones). */
  known?: VersionRef[]
  /** Accessible name — also rendered as the control's caption when given. */
  label?: string
  placeholder?: string
  maxVersions?: number
  autoFocus?: boolean
}) {
  const { data: versions = [] } = useProjectVersions(wsId, projectId)
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const wrapRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const byId = useMemo(() => {
    const map = new Map<string, VersionRef>()
    known.forEach(v => map.set(v.id, v))
    versions.forEach(v => map.set(v.id, v))
    return map
  }, [versions, known])

  const selected = value.map(id => byId.get(id) ?? { id, name: '…', released: false, archived: false })
  const atCap = value.length >= maxVersions

  const q = query.trim().toLowerCase()
  // Unreleased first (the list order the API already returns), then released —
  // preserved rather than re-sorted so the two agree.
  const matches = useMemo(() => {
    const free = versions.filter(v => !value.includes(v.id) && (!q || v.name.toLowerCase().includes(q)))
    return [...free.filter(v => !v.released), ...free.filter(v => v.released)].slice(0, 50)
  }, [versions, value, q])
  const firstReleasedIdx = matches.findIndex(v => v.released)

  useEffect(() => { setHighlight(0) }, [q, open])

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  function add(id: string) {
    if (value.includes(id) || value.length >= maxVersions) return
    onChange([...value, id])
    setQuery('')
    inputRef.current?.focus()
  }

  function remove(id: string) {
    onChange(value.filter(x => x !== id))
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setHighlight(h => Math.min(matches.length - 1, h + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setHighlight(h => Math.max(0, h - 1)) }
    else if (e.key === 'Escape') { if (open) { e.preventDefault(); setOpen(false) } }
    else if (e.key === 'Backspace' && query === '' && value.length > 0) {
      e.preventDefault()
      remove(value[value.length - 1])
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const m = matches[highlight]
      if (m) add(m.id)
    }
  }

  return (
    <div className="flex flex-col gap-1" ref={wrapRef}>
      {label && (
        <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      )}
      <div
        className="flex items-center gap-1 flex-wrap rounded-md border px-2 py-1.5 cursor-text"
        style={{ background: 'white', borderColor: open ? 'var(--color-brand)' : 'var(--color-border-2)' }}
        onClick={() => { setOpen(true); inputRef.current?.focus() }}
      >
        {selected.map(v => <VersionBadge key={v.id} version={v} onRemove={() => remove(v.id)} />)}
        <input
          ref={inputRef}
          value={query}
          autoFocus={autoFocus}
          onChange={e => { setQuery(e.target.value); setOpen(true) }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          placeholder={atCap ? `Limit of ${maxVersions} versions reached` : (selected.length === 0 ? placeholder : '')}
          aria-label={label ?? 'Versions'}
          disabled={atCap && query === ''}
          className="flex-1 text-sm outline-none bg-transparent"
          style={{ minWidth: 90, color: 'var(--color-text)' }}
        />
      </div>

      {open && (
        <div
          role="listbox"
          className="rounded-md border overflow-y-auto"
          style={{
            maxHeight: 220, background: 'var(--color-card)', borderColor: 'var(--color-border-2)',
            boxShadow: 'var(--shadow-lg)', padding: 4,
          }}
        >
          {matches.map((v, idx) => (
            <div key={v.id}>
              {/* Group separator: everything from here on has already shipped. */}
              {idx === firstReleasedIdx && firstReleasedIdx > 0 && (
                <div
                  className="text-xs px-2 pt-1.5 pb-1 uppercase tracking-wider"
                  style={{ color: 'var(--color-text-muted)' }}
                >
                  Released
                </div>
              )}
              <button
                type="button"
                role="option"
                aria-selected={false}
                onMouseEnter={() => setHighlight(idx)}
                onMouseDown={e => e.preventDefault()}
                onClick={() => add(v.id)}
                className="w-full flex items-center gap-2 text-left cursor-pointer"
                style={{
                  padding: '6px 8px', borderRadius: 'var(--radius-sm)',
                  background: idx === highlight ? 'var(--color-surface)' : 'transparent',
                }}
              >
                <span className="rounded-full flex-shrink-0"
                      style={{ width: 8, height: 8, background: versionTint(v.released) }} />
                <span className="mono text-sm truncate flex-1">{v.name}</span>
                {v.released && (
                  <span className="text-xs flex-shrink-0" style={{ color: 'var(--color-success-ink)' }}>released</span>
                )}
                {v.releaseDate && !v.released && (
                  <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                    {v.releaseDate}
                  </span>
                )}
              </button>
            </div>
          ))}
          {matches.length === 0 && (
            <div className="text-xs px-2 py-1.5" style={{ color: 'var(--color-text-muted)' }}>
              {atCap
                ? `At most ${maxVersions} versions per issue.`
                : versions.length === 0
                  ? 'No versions in this project yet — add them on the Releases page.'
                  : (q ? 'No match.' : 'All versions added.')}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── Server-side filter control (board + backlog) ──────────────────────────────

/**
 * The board/backlog fix-version filter. Server-side by design (§3.6, the same
 * rule as the label and component filters): "ships in 2.4.0" must search the
 * whole project, not just the issues the capped board already loaded — so the
 * selection becomes a `?fixVersionId=` query param and part of the query key,
 * unlike the HD-43 client-side chips.
 *
 * FIX links only — an AFFECTS link deliberately does not match.
 *
 * Renders nothing while the project has no versions at all (and none is
 * selected) — an empty "All fix versions" select is pure noise in the filter bar.
 */
export function FixVersionFilter({ wsId, projectId, value, onChange }: {
  wsId: string | undefined
  projectId: string | undefined
  value: string
  onChange: (id: string) => void
}) {
  const { data: versions, isSuccess } = useProjectVersions(wsId, projectId)

  // A version archived or deleted since the selection was made would filter the
  // board down to nothing forever — drop the id once the list has loaded.
  useEffect(() => {
    if (!isSuccess || !versions || !value) return
    if (!versions.some(v => v.id === value)) onChange('')
  }, [isSuccess, versions, value, onChange])

  const all = versions ?? []
  if (all.length === 0 && !value) return null

  const active = !!value
  return (
    <Select
      compact
      aria-label="Filter by fix version"
      value={value}
      onChange={e => onChange(e.target.value)}
      style={active
        ? {
            background: 'color-mix(in srgb, var(--color-brand) 12%, white)',
            borderColor: 'var(--color-brand)',
            color: 'var(--color-brand-ink)',
          }
        : undefined}
    >
      <option value="">All fix versions</option>
      {all.map(v => (
        <option key={v.id} value={v.id}>{v.released ? `${v.name} (released)` : v.name}</option>
      ))}
    </Select>
  )
}

// ── Shared bits for the Releases page ─────────────────────────────────────────

/**
 * `doneIssueCount / issueCount` bar. DESIGN.md: `--color-brand` fill on a
 * `--color-surface-2` track. A version with no fix-linked issues renders
 * "No issues yet" instead of a misleading 0% bar (§6.4).
 */
export function VersionProgress({ done, total }: { done: number; total: number }) {
  if (total <= 0) {
    return (
      <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>No issues yet</span>
    )
  }
  const pct = Math.round((done / total) * 100)
  return (
    <div className="flex flex-col gap-1" style={{ minWidth: 140 }}>
      <div className="flex items-center justify-between gap-3">
        <span className="mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {done} / {total}
        </span>
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{pct}%</span>
      </div>
      <div
        className="w-full rounded-full overflow-hidden"
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`${done} of ${total} issues done`}
        style={{ height: 6, background: 'var(--color-surface-2)' }}
      >
        <div className="h-full rounded-full transition-all"
             style={{ width: `${pct}%`, background: 'var(--color-brand)' }} />
      </div>
    </div>
  )
}

/**
 * Legal `moveUnresolvedToVersionId` targets: another UNRELEASED, non-archived
 * version of the same project (anything else is a 422, §6.4).
 */
export function moveTargets(versions: Version[], excludeId: string): Version[] {
  return versions.filter(v => v.id !== excludeId && !v.released && !v.archived)
}

/**
 * Legal `remapToId` targets for a delete: another non-archived version of the
 * same project. Unlike the move-on-release target, a RELEASED version is a valid
 * remap destination ("that work actually shipped in 2.3.1").
 */
export function remapTargets(versions: Version[], excludeId: string): Version[] {
  return versions.filter(v => v.id !== excludeId && !v.archived)
}
