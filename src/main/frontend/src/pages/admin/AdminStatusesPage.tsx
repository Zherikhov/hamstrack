import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminStatuses } from '../../api'
import type { AdminStatus } from '../../types'
import { Button, Input, Select, StatusBadge } from '../../components/ui'
import { AdminTable, ArchivedBadge, ArchivedToggle, DeleteDialog, Modal, PageHeader, UsageChip } from './common'

const CATEGORIES = ['TODO', 'IN_PROGRESS', 'DONE'] as const

export default function AdminStatusesPage() {
  const qc = useQueryClient()
  const { data: statuses = [] } = useQuery({ queryKey: ['admin', 'statuses'], queryFn: adminStatuses.list })
  const [editing, setEditing] = useState<AdminStatus | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AdminStatus | null>(null)
  const [showArchived, setShowArchived] = useState(false)
  const [error, setError] = useState('')

  const visible = showArchived ? statuses : statuses.filter(s => !s.archived)

  const invalidate = () => qc.invalidateQueries({ queryKey: ['admin'] })

  const del = useMutation({
    mutationFn: ({ id, replaceWithId }: { id: string; replaceWithId?: string }) =>
      adminStatuses.remove(id, replaceWithId),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Delete failed'),
  })

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? adminStatuses.unarchive(id) : adminStatuses.archive(id),
    onSuccess: () => { setDeleting(null); invalidate() },
  })

  return (
    <>
      <PageHeader
        title="Statuses"
        subtitle="Global catalog. A status appears on a board only through a workflow assigned to the project."
        action={<Button variant="primary" onClick={() => setEditing('new')}>+ New status</Button>}
      />
      <ArchivedToggle archivedCount={statuses.filter(s => s.archived).length}
                      value={showArchived} onChange={setShowArchived} />
      <AdminTable headers={['Name', 'Category', 'Used in', '']}>
        {visible.map(s => (
          <tr key={s.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2 text-sm">
                <span className="rounded-full" style={{ width: 10, height: 10, background: s.color }} />
                {s.name}
                {s.archived && <ArchivedBadge />}
              </span>
            </td>
            <td className="px-3 py-2.5"><StatusBadge name={s.category} category={s.category} /></td>
            <td className="px-3 py-2.5"><UsageChip usage={s.usage} fetchDetail={() => adminStatuses.usage(s.id)} /></td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={() => setEditing(s)}>Edit</Button>
              <Button variant="ghost" size="sm"
                      onClick={() => archive.mutate({ id: s.id, archived: s.archived })}>
                {s.archived ? 'Unarchive' : 'Archive'}
              </Button>
              <Button variant="ghost" size="sm" style={{ color: 'var(--color-error)' }}
                      onClick={() => { setError(''); setDeleting(s) }}>
                Delete
              </Button>
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
  const [name, setName] = useState(status?.name ?? '')
  const [category, setCategory] = useState<string>(status?.category ?? 'TODO')
  const [color, setColor] = useState(status?.color ?? '#6B7280')
  const [error, setError] = useState('')

  const save = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), category: category as AdminStatus['category'], color }
      return status ? adminStatuses.update(status.id, payload) : adminStatuses.create(payload)
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
        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
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

export function ColorField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Color</label>
      <div className="flex items-center gap-2">
        <input type="color" value={value} onChange={e => onChange(e.target.value)}
               className="cursor-pointer" style={{ width: 34, height: 30, border: 'none', background: 'none' }} />
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{value}</span>
      </div>
    </div>
  )
}
