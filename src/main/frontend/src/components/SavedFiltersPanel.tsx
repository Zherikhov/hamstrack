import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Pencil, Play, Share2, SquarePen, Trash2, X } from 'lucide-react'
import { ApiResponseError, savedFilters } from '../api'
import type { SavedFilter } from '../types'

/**
 * Saved-filters dropdown, anchored under the results-view "Filters" button. Lists
 * the caller's own filters plus every shared filter in the workspace; clicking one
 * loads its HQL into the search and runs it. Owner-only affordances (rename, toggle
 * share, delete) show only on `mine` rows — a non-owner sees shared filters
 * read-only.
 *
 * SECURITY: a filter's name and hql are authored by another workspace member (a
 * shared filter), so they are rendered as React text content ONLY — never
 * dangerouslySetInnerHTML or an HTML-building highlighter (stored-XSS otherwise).
 */
export default function SavedFiltersPanel({ wsId, onLoad, onEditQuery, onClose }: {
  wsId: string
  onLoad: (hql: string) => void
  // Owner-only "edit query" — loads the filter's body into the search for editing
  // (with autocomplete) and puts the page into "update this filter" mode.
  onEditQuery: (f: { id: string; name: string; hql: string }) => void
  onClose: () => void
}) {
  const qc = useQueryClient()
  const ref = useRef<HTMLDivElement>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    function onDoc(e: MouseEvent) { if (!ref.current?.contains(e.target as Node)) onClose() }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [onClose])

  const { data: filters = [], isLoading } = useQuery({
    queryKey: ['savedFilters', wsId],
    queryFn: () => savedFilters.list(wsId),
  })

  const mine = filters.filter(f => f.mine)
  const shared = filters.filter(f => !f.mine)

  function refresh() { qc.invalidateQueries({ queryKey: ['savedFilters', wsId] }) }

  async function saveRename(f: SavedFilter) {
    if (!editName.trim() || editName.trim() === f.name) { setEditingId(null); return }
    setBusyId(f.id); setError(null)
    try {
      await savedFilters.update(wsId, f.id, { name: editName.trim() })
      setEditingId(null); refresh()
    } catch (err) {
      setError(err instanceof ApiResponseError ? err.detail : 'Rename failed.')
    } finally { setBusyId(null) }
  }

  async function toggleShare(f: SavedFilter) {
    setBusyId(f.id); setError(null)
    try { await savedFilters.update(wsId, f.id, { shared: !f.shared }); refresh() }
    catch (err) { setError(err instanceof ApiResponseError ? err.detail : 'Update failed.') }
    finally { setBusyId(null) }
  }

  async function remove(f: SavedFilter) {
    if (!confirm(`Delete filter "${f.name}"?`)) return
    setBusyId(f.id); setError(null)
    try { await savedFilters.remove(wsId, f.id); refresh() }
    catch (err) { setError(err instanceof ApiResponseError ? err.detail : 'Delete failed.') }
    finally { setBusyId(null) }
  }

  return (
    <div
      ref={ref}
      style={{
        position: 'absolute', left: 0, top: '100%', marginTop: 4, zIndex: 90, width: 340,
        background: 'var(--color-card)', border: '1px solid var(--color-border-2)',
        borderRadius: 'var(--radius-md)', boxShadow: 'var(--shadow-lg)', padding: 6,
        maxHeight: 420, overflowY: 'auto',
      }}
    >
      {error && (
        <div className="text-xs px-2 py-1.5" style={{ color: 'var(--color-error)' }}>{error}</div>
      )}
      {isLoading ? (
        <div className="mono text-xs px-3 py-3" style={{ color: 'var(--color-text-muted)' }}>loading…</div>
      ) : filters.length === 0 ? (
        <div className="text-xs px-3 py-3" style={{ color: 'var(--color-text-muted)' }}>
          No saved filters yet. Run a query and choose “Save”.
        </div>
      ) : (
        <>
          {mine.length > 0 && <SectionLabel>My filters</SectionLabel>}
          {mine.map(f => (
            <FilterRow
              key={f.id} f={f} busy={busyId === f.id}
              editing={editingId === f.id} editName={editName}
              onEditName={setEditName}
              onStartEdit={() => { setEditingId(f.id); setEditName(f.name) }}
              onCommitEdit={() => saveRename(f)}
              onCancelEdit={() => setEditingId(null)}
              onLoad={() => onLoad(f.hql)}
              onEditQuery={() => onEditQuery({ id: f.id, name: f.name, hql: f.hql })}
              onToggleShare={() => toggleShare(f)}
              onDelete={() => remove(f)}
            />
          ))}
          {shared.length > 0 && <SectionLabel>Shared with the workspace</SectionLabel>}
          {shared.map(f => (
            <FilterRow
              key={f.id} f={f} busy={false}
              editing={false} editName="" onEditName={() => {}}
              onStartEdit={() => {}} onCommitEdit={() => {}} onCancelEdit={() => {}}
              onLoad={() => onLoad(f.hql)}
              onEditQuery={() => {}}
              onToggleShare={() => {}} onDelete={() => {}}
            />
          ))}
        </>
      )}
    </div>
  )
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="text-xs font-semibold uppercase px-2 pt-2 pb-1"
      style={{ color: 'var(--color-text-muted)', letterSpacing: '0.05em', fontSize: 10.5 }}
    >
      {children}
    </div>
  )
}

function FilterRow({
  f, busy, editing, editName, onEditName, onStartEdit, onCommitEdit, onCancelEdit,
  onLoad, onEditQuery, onToggleShare, onDelete,
}: {
  f: SavedFilter; busy: boolean
  editing: boolean; editName: string; onEditName: (v: string) => void
  onStartEdit: () => void; onCommitEdit: () => void; onCancelEdit: () => void
  onLoad: () => void; onEditQuery: () => void; onToggleShare: () => void; onDelete: () => void
}) {
  return (
    <div
      className="group flex items-center gap-1.5"
      style={{ padding: '6px 8px', borderRadius: 'var(--radius-sm)', opacity: busy ? 0.5 : 1 }}
      onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-surface)' }}
      onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
    >
      <button onClick={onLoad} className="cursor-pointer flex-shrink-0" title="Run filter" style={{ color: 'var(--color-brand)' }}>
        <Play size={13} />
      </button>
      <div className="flex-1 min-w-0 cursor-pointer" onClick={onLoad}>
        {editing ? (
          <input
            value={editName}
            autoFocus
            onChange={e => onEditName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') onCommitEdit(); if (e.key === 'Escape') onCancelEdit() }}
            onClick={e => e.stopPropagation()}
            className="w-full outline-none"
            style={{
              fontSize: 13, padding: '2px 4px', borderRadius: 4,
              border: '1px solid var(--color-brand)', color: 'var(--color-text)', background: 'white',
            }}
            maxLength={120}
          />
        ) : (
          <>
            {/* name + hql are plain text content — never HTML (shared-filter XSS guard) */}
            <div className="text-sm truncate" style={{ color: 'var(--color-text)' }}>{f.name}</div>
            <div className="mono truncate" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
              {f.hql || 'all issues'}{!f.mine && ` · ${f.ownerName}`}
            </div>
          </>
        )}
      </div>

      {f.mine ? (
        editing ? (
          <>
            <button onClick={onCommitEdit} className="cursor-pointer flex-shrink-0" title="Save" style={{ color: 'var(--color-brand)' }}>
              <Check size={13} />
            </button>
            <button onClick={onCancelEdit} className="cursor-pointer flex-shrink-0" title="Cancel" style={{ color: 'var(--color-text-muted)' }}>
              <X size={13} />
            </button>
          </>
        ) : (
          <div className="flex items-center gap-1.5 flex-shrink-0">
            <button
              onClick={e => { e.stopPropagation(); onToggleShare() }}
              className="cursor-pointer" title={f.shared ? 'Shared — click to unshare' : 'Share with workspace'}
              style={{ color: f.shared ? 'var(--color-brand)' : 'var(--color-text-muted)' }}
            >
              <Share2 size={13} />
            </button>
            <button onClick={e => { e.stopPropagation(); onStartEdit() }} className="cursor-pointer" title="Rename" style={{ color: 'var(--color-text-muted)' }}>
              <Pencil size={13} />
            </button>
            <button onClick={e => { e.stopPropagation(); onEditQuery() }} className="cursor-pointer" title="Edit query" style={{ color: 'var(--color-text-muted)' }}>
              <SquarePen size={13} />
            </button>
            <button onClick={e => { e.stopPropagation(); onDelete() }} className="cursor-pointer" title="Delete" style={{ color: 'var(--color-text-muted)' }}>
              <Trash2 size={13} />
            </button>
          </div>
        )
      ) : (
        <Share2 size={12} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} />
      )}
    </div>
  )
}
