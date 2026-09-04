import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { ApiResponseError, labelsApi } from '../../api'
import type { Label } from '../../types'
import { Button, Input, Select, Textarea } from '../../components/ui'
import { LABEL_PALETTE, LabelChip, colorForName, labelsKey } from '../../components/labels'
import { ISSUES_KEY_ROOT } from '../../lib/queryKeys'
import { AdminTable, ArchivedBadge, ArchivedToggle, Modal, PageHeader, UsageChip } from '../admin/common'
import { ColorField } from '../admin/AdminStatusesPage'
import { SURFACE, ringOn } from '../../colour'

/**
 * Workspace settings → Labels (HD-30). The curation surface for the workspace's
 * label vocabulary: rename, recolor, describe, merge duplicates, archive and
 * delete. Creating a label needs no curation role at all — any member does that
 * straight from the picker — so this page exists for the tag-soup cure, which is
 * exactly what a free-form namespace lacks in other trackers.
 *
 * Access: `WorkspaceSettingsArea` already redirects anyone the workspace-settings
 * permission set does not admit (HD-123 S5 — `canOpenWorkspaceSettings`, over the
 * server's own `myPermissions` strings), and the server independently enforces
 * `label.manage` on every mutating endpoint — this page adds no guard of its own.
 *
 * Its list deliberately uses its OWN query key (archived rows + usage counts)
 * so the pickers' lean `labelsKey(wsId)` cache entry stays lean; both live under
 * the `['labels', wsId]` prefix, so one invalidation refreshes both.
 */
export default function WorkspaceLabelsPage() {
  const { wsId } = useParams<{ wsId: string }>()
  const qc = useQueryClient()
  const [showArchived, setShowArchived] = useState(false)
  const [editing, setEditing] = useState<Label | 'new' | null>(null)
  const [merging, setMerging] = useState<Label | null>(null)
  const [deleting, setDeleting] = useState<Label | null>(null)
  const [error, setError] = useState('')

  const adminLabelsKey = [...labelsKey(wsId), 'curation'] as const
  const { data: labels = [], isLoading } = useQuery({
    queryKey: adminLabelsKey,
    queryFn: () => labelsApi.list(wsId!, { includeArchived: true, withUsage: true }),
    enabled: !!wsId,
  })

  /** Labels changed → the pickers' list AND every issue list that renders chips. */
  function invalidate() {
    qc.invalidateQueries({ queryKey: labelsKey(wsId) })
    qc.invalidateQueries({ queryKey: [ISSUES_KEY_ROOT] })
    qc.invalidateQueries({ queryKey: ['issue'] })
  }

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? labelsApi.unarchive(wsId!, id) : labelsApi.archive(wsId!, id),
    onSuccess: () => { setDeleting(null); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Failed'),
  })

  const remove = useMutation({
    mutationFn: ({ id, force }: { id: string; force: boolean }) => labelsApi.remove(wsId!, id, force),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Delete failed'),
  })

  const visible = showArchived ? labels : labels.filter(l => !l.archived)
  const archivedCount = labels.filter(l => l.archived).length

  return (
    <>
      <PageHeader
        title="Labels"
        subtitle="Free-form, colored tags shared by every project in this workspace. Any member can create one while filing an issue; renaming, merging and archiving them is curation — and safe, because issues reference labels by id, not by name."
        action={<Button variant="primary" onClick={() => setEditing('new')}>+ New label</Button>}
      />
      <ArchivedToggle archivedCount={archivedCount} value={showArchived} onChange={setShowArchived} />

      <AdminTable headers={['Label', 'Description', 'Used on', '']}>
        {visible.map(l => (
          <tr key={l.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2">
                <LabelChip label={l} />
                {l.archived && <ArchivedBadge />}
              </span>
            </td>
            <td className="px-3 py-2.5" style={{ maxWidth: 280 }}>
              <span className="text-sm truncate block" style={{ color: 'var(--color-text-secondary)' }}>
                {l.description || '—'}
              </span>
            </td>
            <td className="px-3 py-2.5">
              <UsageChip usage={{ workflows: 0, sets: 0, projects: 0, issues: l.issueCount ?? 0 }} />
            </td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={() => setEditing(l)}>Edit</Button>
              <Button variant="ghost" size="sm" onClick={() => { setError(''); setMerging(l) }}>Merge into…</Button>
              <Button variant="ghost" size="sm"
                      onClick={() => archive.mutate({ id: l.id, archived: l.archived })}>
                {l.archived ? 'Unarchive' : 'Archive'}
              </Button>
              <Button variant="ghost" size="sm" style={{ color: 'var(--color-error-ink)' }}
                      onClick={() => { setError(''); setDeleting(l) }}>
                Delete
              </Button>
            </td>
          </tr>
        ))}
        {visible.length === 0 && (
          <tr>
            <td colSpan={4} className="px-3 py-6 text-center text-sm" style={{ color: 'var(--color-text-muted)' }}>
              {isLoading ? 'loading…' : 'No labels yet — create one here, or straight from an issue.'}
            </td>
          </tr>
        )}
      </AdminTable>

      {editing && (
        <LabelForm
          wsId={wsId!}
          label={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); invalidate() }}
        />
      )}

      {merging && (
        <MergeLabelDialog
          wsId={wsId!}
          source={merging}
          candidates={labels.filter(l => l.id !== merging.id && !l.archived)}
          onClose={() => setMerging(null)}
          onMerged={() => { setMerging(null); invalidate() }}
        />
      )}

      {deleting && (
        <Modal title={`Delete label “${deleting.name}”?`} onClose={() => setDeleting(null)}>
          <div className="flex flex-col gap-4">
            <div className="text-sm rounded-lg px-3 py-2.5"
                 style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
              {(deleting.issueCount ?? 0) > 0 ? (
                <>
                  Used on <span className="mono">{deleting.issueCount}</span> issue
                  {deleting.issueCount === 1 ? '' : 's'} — deleting removes it from all of them.
                  Archiving keeps the tag on those issues and only hides it from the pickers.
                </>
              ) : (
                <>Not used on any issue — safe to delete.</>
              )}
            </div>
            {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setDeleting(null)}>Cancel</Button>
              {!deleting.archived && (
                <Button variant="secondary"
                        onClick={() => archive.mutate({ id: deleting.id, archived: false })}>
                  Archive instead
                </Button>
              )}
              <Button variant="danger" loading={remove.isPending}
                      onClick={() => remove.mutate({ id: deleting.id, force: (deleting.issueCount ?? 0) > 0 })}>
                Delete
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </>
  )
}

/** Create / edit one label: name, color (8-swatch palette or a custom hex), description. */
function LabelForm({ wsId, label, onClose, onSaved }: {
  wsId: string
  label: Label | null
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(label?.name ?? '')
  const [color, setColor] = useState(label?.color ?? LABEL_PALETTE[0])
  // A new label follows the deterministic auto-color until the user picks one,
  // exactly like a label created on the fly from the picker.
  const [colorTouched, setColorTouched] = useState(!!label)
  const [description, setDescription] = useState(label?.description ?? '')
  const [error, setError] = useState('')

  const effectiveColor = colorTouched || !name.trim() ? color : colorForName(name.trim())

  const save = useMutation({
    mutationFn: () => {
      const payload = {
        name: name.trim(),
        color: effectiveColor,
        description: description.trim(),
      }
      return label ? labelsApi.update(wsId, label.id, payload) : labelsApi.create(wsId, payload)
    },
    onSuccess: onSaved,
    onError: (e: unknown) => {
      // 409 duplicate carries the winner's id; the message already names the
      // conflict (including "an archived label already uses this name").
      setError(e instanceof ApiResponseError ? e.detail : (e instanceof Error ? e.message : 'Save failed'))
    },
  })

  function pick(c: string) {
    setColor(c)
    setColorTouched(true)
  }

  return (
    <Modal title={label ? `Edit label “${label.name}”` : 'New label'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} maxLength={60} autoFocus />

        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Color</span>
          <div className="flex items-center gap-1.5 flex-wrap">
            {LABEL_PALETTE.map(c => (
              <button
                key={c}
                type="button"
                aria-label={`Use color ${c}`}
                aria-pressed={effectiveColor.toLowerCase() === c.toLowerCase()}
                onClick={() => pick(c)}
                className="rounded-full cursor-pointer transition-transform"
                style={{
                  width: 22, height: 22, background: c,
                  border: effectiveColor.toLowerCase() === c.toLowerCase()
                    ? '2px solid var(--color-text)'
                    : '2px solid transparent',
                  // A pale swatch on a white card is a fill with no edge; the ring
                  // is the same hue at 3:1, so it stays visible without repainting it.
                  boxShadow: `inset 0 0 0 1px ${ringOn(c, SURFACE.card)}`,
                }}
              />
            ))}
          </div>
          <ColorField value={effectiveColor} onChange={pick} />
        </div>

        <Textarea label="Description" rows={2} value={description} maxLength={200}
                  onChange={e => setDescription(e.target.value)} />

        <div className="flex items-center gap-2">
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Preview</span>
          <LabelChip label={{ id: 'preview', name: name.trim() || 'label', color: effectiveColor, archived: false }} />
        </div>

        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            {label ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/**
 * Merge this label INTO another one: every issue tagged with the source ends up
 * tagged with the target (duplicates collapse), then the source row is deleted.
 * The one bulk operation this slice ships, because it is the only cure for the
 * "techdebt / tech-debt / TechDebt" failure mode.
 */
function MergeLabelDialog({ wsId, source, candidates, onClose, onMerged }: {
  wsId: string
  source: Label
  candidates: Label[]
  onClose: () => void
  onMerged: () => void
}) {
  const [targetId, setTargetId] = useState(candidates[0]?.id ?? '')
  const [error, setError] = useState('')

  const merge = useMutation({
    // sourceIds are absorbed INTO the target, so the endpoint is on the target.
    mutationFn: () => labelsApi.merge(wsId, targetId, [source.id]),
    onSuccess: onMerged,
    onError: e => setError(e instanceof Error ? e.message : 'Merge failed'),
  })

  const target = candidates.find(c => c.id === targetId)

  return (
    <Modal title={`Merge “${source.name}” into…`} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <div className="text-sm rounded-lg px-3 py-2.5"
             style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
          Every issue tagged <b>{source.name}</b>
          {(source.issueCount ?? 0) > 0 && <> (<span className="mono">{source.issueCount}</span>)</>}
          {' '}gets the target label instead, and <b>{source.name}</b> is deleted. This is not undoable
          and writes no per-issue history.
        </div>

        {candidates.length === 0 ? (
          <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
            No other active label to merge into.
          </p>
        ) : (
          <Select label="Merge into" value={targetId} onChange={e => setTargetId(e.target.value)}>
            {candidates.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
        )}

        {target && (
          <div className="flex items-center gap-2">
            <LabelChip label={source} />
            <span style={{ color: 'var(--color-text-muted)' }}>→</span>
            <LabelChip label={target} />
          </div>
        )}

        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!targetId} loading={merge.isPending} onClick={() => merge.mutate()}>
            Merge
          </Button>
        </div>
      </div>
    </Modal>
  )
}
