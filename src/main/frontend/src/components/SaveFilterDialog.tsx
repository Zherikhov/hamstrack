import { useState } from 'react'
import { X } from 'lucide-react'
import { ApiResponseError, savedFilters } from '../api'
import { Button, Checkbox, Input } from './ui'

/**
 * "Save as filter" dialog on the results view. Names the current HQL and optionally
 * shares it workspace-wide. Handles 409 (dup name) and 422 (invalid HQL) inline —
 * the backend re-validates the HQL at save time.
 *
 * When `editing` is set (an existing filter loaded via "Edit query"), the primary
 * action becomes "Update '<name>'" (PATCH the loaded filter's body) with a secondary
 * "Save as new" (the create path). When `editing` is null it's create-only.
 */
export default function SaveFilterDialog({ wsId, hql, editing, onClose, onSaved, onUpdated }: {
  wsId: string
  hql: string
  editing?: { id: string; name: string } | null
  onClose: () => void
  onSaved: () => void
  onUpdated?: () => void
}) {
  const [name, setName] = useState(editing?.name ?? '')
  const [shared, setShared] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Create a brand-new filter from the current HQL (the "Save as new" path, and the
  // only path when not editing).
  async function saveAsNew() {
    if (!name.trim()) { setError('Name is required.'); return }
    setSaving(true)
    setError(null)
    try {
      await savedFilters.create(wsId, { name: name.trim(), hql, shared })
      onSaved()
    } catch (err) {
      // 409 dup-name and 422 invalid-HQL both arrive as ApiResponseError.detail.
      setError(err instanceof ApiResponseError ? err.detail : 'Could not save the filter.')
      setSaving(false)
    }
  }

  // Update the loaded filter's name + body (re-validated server-side). Sharing is
  // left untouched here — it's toggled from the saved-filters panel — so we don't
  // send `shared` and risk silently unsharing.
  async function update() {
    if (!editing) return
    if (!name.trim()) { setError('Name is required.'); return }
    setSaving(true)
    setError(null)
    try {
      await savedFilters.update(wsId, editing.id, { name: name.trim(), hql })
      onUpdated?.()
    } catch (err) {
      setError(err instanceof ApiResponseError ? err.detail : 'Could not update the filter.')
      setSaving(false)
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault()
    // Enter defaults to the primary action (update when editing, else create).
    if (editing) update(); else saveAsNew()
  }

  return (
    <div
      className="fixed inset-0 flex items-center justify-center"
      style={{ background: 'rgba(16,24,40,0.45)', zIndex: 100 }}
      onMouseDown={onClose}
    >
      <div
        onMouseDown={e => e.stopPropagation()}
        style={{
          width: 440, maxWidth: '92vw', background: 'var(--color-card)',
          borderRadius: 'var(--radius-xl)', boxShadow: 'var(--shadow-lg)', padding: 24,
        }}
      >
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-bold" style={{ fontSize: 16, color: 'var(--color-text)' }}>
            {editing ? 'Update filter' : 'Save as filter'}
          </h2>
          <button onClick={onClose} className="cursor-pointer" style={{ color: 'var(--color-text-muted)' }}>
            <X size={18} />
          </button>
        </div>

        <form onSubmit={submit} className="flex flex-col gap-4">
          <Input
            label="Name"
            value={name}
            onChange={e => setName(e.target.value)}
            placeholder="My open bugs"
            maxLength={120}
            autoFocus
          />

          {/* The query being saved — rendered as PLAIN TEXT (never HTML). */}
          <div className="flex flex-col gap-1">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Query</span>
            <div
              className="mono"
              style={{
                fontSize: 12, padding: '9px 12px', borderRadius: 'var(--radius-md)',
                background: 'var(--color-surface)', border: '1px solid var(--color-border)',
                color: 'var(--color-text-secondary)', wordBreak: 'break-word',
              }}
            >
              {hql || 'all issues'}
            </div>
          </div>

          {/* Sharing is set on create; when updating it's managed from the panel. */}
          {!editing && (
            <Checkbox
              label="Share with the workspace (read-only for others)"
              checked={shared}
              onChange={e => setShared(e.target.checked)}
            />
          )}

          {error && (
            <span className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</span>
          )}

          <div className="flex items-center justify-end gap-2 mt-1">
            <Button variant="ghost" onClick={onClose} type="button">Cancel</Button>
            {editing ? (
              <>
                <Button variant="secondary" type="button" onClick={saveAsNew} disabled={saving}>
                  Save as new
                </Button>
                <Button variant="primary" type="submit" loading={saving}>
                  Update “{editing.name}”
                </Button>
              </>
            ) : (
              <Button variant="primary" type="submit" loading={saving}>Save filter</Button>
            )}
          </div>
        </form>
      </div>
    </div>
  )
}
