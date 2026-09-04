import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import type { AdminStatus } from '../../types'
import { Badge, Button, Input, Select, StatusBadge } from '../../components/ui'
import { SURFACE, contrastRatio, fillOf, inkOn, parseColour, ringOn, tintOf, token } from '../../colour'
import { AdminTable, ArchivedBadge, ArchivedToggle, DeleteDialog, InheritedBadge, Modal, PageHeader, UsageChip } from './common'
import { ownScopeTag, useAdminApi, useAdminInvalidate } from './AdminApiContext'

const CATEGORIES = ['TODO', 'IN_PROGRESS', 'DONE'] as const

export default function AdminStatusesPage() {
  const { api, keyPrefix, scope } = useAdminApi()
  const ownTag = ownScopeTag(scope)
  const invalidate = useAdminInvalidate()
  const { data: statuses = [] } = useQuery({ queryKey: [...keyPrefix, 'statuses'], queryFn: api.statuses.list })
  const [editing, setEditing] = useState<AdminStatus | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AdminStatus | null>(null)
  const [showArchived, setShowArchived] = useState(false)
  const [error, setError] = useState('')

  const visible = showArchived ? statuses : statuses.filter(s => !s.archived)

  const del = useMutation({
    mutationFn: ({ id, replaceWithId }: { id: string; replaceWithId?: string }) =>
      api.statuses.remove(id, replaceWithId),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Delete failed'),
  })

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? api.statuses.unarchive(id) : api.statuses.archive(id),
    onSuccess: () => { setDeleting(null); invalidate() },
  })

  return (
    <>
      <PageHeader
        title="Statuses"
        subtitle={scope === 'project'
          ? 'Statuses private to this project. They appear on the board only through a workflow you assign to the project.'
          : 'Catalog of statuses. A status appears on a board only through a workflow assigned to the project.'}
        action={<Button variant="primary" onClick={() => setEditing('new')}>+ New status</Button>}
      />
      <ArchivedToggle archivedCount={statuses.filter(s => s.archived).length}
                      value={showArchived} onChange={setShowArchived} />
      <AdminTable headers={['Name', 'Category', 'Used in', '']}>
        {visible.map(s => (
          <tr key={s.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2 text-sm">
                <span className="rounded-full" style={{ width: 10, height: 10, background: fillOf(s.color), boxShadow: `inset 0 0 0 1px ${ringOn(s.color, SURFACE.card)}` }} />
                {s.name}
                {s.archived && <ArchivedBadge />}
              </span>
            </td>
            <td className="px-3 py-2.5"><StatusBadge name={s.category} category={s.category} /></td>
            <td className="px-3 py-2.5"><UsageChip usage={s.usage} fetchDetail={() => api.statuses.usage(s.id)} /></td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              {s.scope === ownTag ? (
                <>
                  <Button variant="ghost" size="sm" onClick={() => setEditing(s)}>Edit</Button>
                  <Button variant="ghost" size="sm"
                          onClick={() => archive.mutate({ id: s.id, archived: s.archived })}>
                    {s.archived ? 'Unarchive' : 'Archive'}
                  </Button>
                  <Button variant="ghost" size="sm" style={{ color: 'var(--color-error-ink)' }}
                          onClick={() => { setError(''); setDeleting(s) }}>
                    Delete
                  </Button>
                </>
              ) : <InheritedBadge scope={s.scope} />}
            </td>
          </tr>
        ))}
      </AdminTable>

      {editing && (
        <StatusForm
          status={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); invalidate() }}
        />
      )}

      {deleting && (
        <DeleteDialog
          entity="status"
          name={deleting.name}
          usage={deleting.usage}
          replacements={statuses.filter(s => s.id !== deleting.id && !s.archived)
            .map(s => ({ id: s.id, name: s.name }))}
          onDelete={replaceWithId => del.mutate({ id: deleting.id, replaceWithId })}
          onArchive={() => archive.mutate({ id: deleting.id, archived: false })}
          onClose={() => setDeleting(null)}
          error={error}
        />
      )}
    </>
  )
}

function StatusForm({ status, onClose, onSaved }: {
  status: AdminStatus | null; onClose: () => void; onSaved: () => void
}) {
  const { api } = useAdminApi()
  const [name, setName] = useState(status?.name ?? '')
  const [category, setCategory] = useState<string>(status?.category ?? 'TODO')
  const [color, setColor] = useState(status?.color ?? token('--color-sandbox'))
  const [error, setError] = useState('')

  const save = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), category: category as AdminStatus['category'], color }
      return status ? api.statuses.update(status.id, payload) : api.statuses.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  return (
    <Modal title={status ? `Edit status “${status.name}”` : 'New status'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        <Select label="Category (drives board grouping)" value={category} onChange={e => setCategory(e.target.value)}>
          {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
        </Select>
        <ColorField value={color} onChange={setColor} />
        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            {status ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/**
 * The one colour picker (statuses, priorities, issue types, labels, and the
 * custom-field option editor), now showing what the product will actually paint.
 *
 * **It refuses nothing.** Under the identity-hue rule there is nothing left to
 * refuse: every hue is stored as picked and painted at full strength as a fill,
 * and only a glyph that could not be read is derived. Refusing on contrast would
 * delete yellow, amber, mid-orange, bright green and light pink from every
 * admin's palette — including most of the catalog defaults `DESIGN.md` itself
 * declares — to buy a guarantee the renderer already holds.
 *
 * What it does instead is say so at the moment of choosing: the real chip, the
 * real dot with its ring, the measured ratio as a number, and the hex that will
 * be drawn when (and only when) it is not the one picked. A derivation nobody is
 * told about is magic; one shown beside the swatch is a fact.
 *
 * `compact` is the per-row form the custom-field option editor needs — one line,
 * same disclosure, no heading — so that editor stops inlining a bare
 * `input type="color"` of its own and the product keeps one picker.
 */
export function ColorField({ value, onChange, compact }: {
  value: string; onChange: (v: string) => void; compact?: boolean
}) {
  const parsed = parseColour(value) !== null
  const ratio = contrastRatio(value, SURFACE.card)
  const tint = tintOf(value, SURFACE.card)
  const ink = inkOn(value, tint)
  const derived = parsed && ink.toUpperCase() !== value.trim().toUpperCase()

  const swatch = (
    <input type="color" value={parsed ? value : token('--color-sandbox')} onChange={e => onChange(e.target.value)}
           aria-label="Color"
           className="cursor-pointer flex-shrink-0"
           style={{ width: compact ? 30 : 34, height: compact ? 28 : 30, border: 'none', background: 'none' }} />
  )
  const dot = parsed ? (
    <span
      aria-hidden="true"
      className="rounded-full flex-shrink-0"
      style={{ width: 10, height: 10, background: fillOf(value), boxShadow: `inset 0 0 0 1px ${ringOn(value, SURFACE.card)}` }}
    />
  ) : null

  if (compact) {
    return (
      <div className="flex items-center gap-1.5 flex-shrink-0">
        {swatch}
        {dot}
        {parsed && (
          <span className="mono text-xs whitespace-nowrap" style={{ color: 'var(--color-text-muted)' }}>
            {ratio.toFixed(2)}:1{derived ? ` → ${ink}` : ''}
          </span>
        )}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-1">
      <label className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Color</label>
      <div className="flex items-center gap-2">
        {swatch}
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{value}</span>
        {parsed && <Badge label="Preview" color={value} />}
        {dot}
      </div>
      {parsed && (
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          Contrast {ratio.toFixed(2)}:1 against a white card.{' '}
          {derived
            ? <>Too low to read, so text is drawn as <span className="mono" style={{ color: ink }}>{ink}</span> — the same hue, dimmed. Dots, bars and chart segments keep {value}.</>
            : <>Text is drawn as picked.</>}
        </p>
      )}
    </div>
  )
}
