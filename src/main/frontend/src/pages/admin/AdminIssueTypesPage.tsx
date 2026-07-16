import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminIssueTypes, adminIssueTypeSets } from '../../api'
import type { AdminIssueType, AdminIssueTypeSet } from '../../types'
import { Button, Checkbox, Input } from '../../components/ui'
import { AdminTable, ArchivedBadge, ArchivedToggle, DeleteDialog, ImpactBanner, Modal, PageHeader, UsageChip } from './common'
import { ColorField } from './AdminStatusesPage'

export default function AdminIssueTypesPage() {
  const qc = useQueryClient()
  const { data: types = [] } = useQuery({ queryKey: ['admin', 'issue-types'], queryFn: adminIssueTypes.list })
  const { data: sets = [] } = useQuery({ queryKey: ['admin', 'issue-type-sets'], queryFn: adminIssueTypeSets.list })
  const [editing, setEditing] = useState<AdminIssueType | 'new' | null>(null)
  const [editingSet, setEditingSet] = useState<AdminIssueTypeSet | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AdminIssueType | null>(null)
  const [showArchived, setShowArchived] = useState(false)
  const [error, setError] = useState('')

  const visible = showArchived ? types : types.filter(t => !t.archived)

  const invalidate = () => qc.invalidateQueries({ queryKey: ['admin'] })

  const del = useMutation({
    mutationFn: ({ id, replaceWithId }: { id: string; replaceWithId?: string }) =>
      adminIssueTypes.remove(id, replaceWithId),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Delete failed'),
  })

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? adminIssueTypes.unarchive(id) : adminIssueTypes.archive(id),
    onSuccess: () => { setDeleting(null); invalidate() },
  })

  const delSet = useMutation({
    mutationFn: (id: string) => adminIssueTypeSets.remove(id),
    onSuccess: invalidate,
    onError: e => window.alert(e instanceof Error ? e.message : 'Delete failed'),
  })

  return (
    <>
      <PageHeader
        title="Issue types"
        subtitle="Global catalog. Type sets pick which of these a project offers for new issues and type changes — existing issues keep their type."
        action={<Button variant="primary" onClick={() => setEditing('new')}>+ New type</Button>}
      />
      <ArchivedToggle archivedCount={types.filter(t => t.archived).length}
                      value={showArchived} onChange={setShowArchived} />
      <AdminTable headers={['Name', 'Icon', 'Used by', '']}>
        {visible.map(t => (
          <tr key={t.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2 text-sm">
                <span className="rounded-full" style={{ width: 10, height: 10, background: t.color }} />
                {t.name}
                {t.archived && <ArchivedBadge />}
              </span>
            </td>
            <td className="px-3 py-2.5">
              <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{t.icon ?? '—'}</span>
            </td>
            <td className="px-3 py-2.5"><UsageChip usage={t.usage} fetchDetail={() => adminIssueTypes.usage(t.id)} /></td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={() => setEditing(t)}>Edit</Button>
              <Button variant="ghost" size="sm" onClick={() => archive.mutate({ id: t.id, archived: t.archived })}>
                {t.archived ? 'Unarchive' : 'Archive'}
              </Button>
              <Button variant="ghost" size="sm" style={{ color: 'var(--color-error)' }}
                      onClick={() => { setError(''); setDeleting(t) }}>
                Delete
              </Button>
            </td>
          </tr>
        ))}
      </AdminTable>

      {/* Issue type sets */}
      <div className="flex items-center justify-between mt-8 mb-3">
        <h2 className="font-display font-bold" style={{ fontSize: 17 }}>Type sets</h2>
        <Button variant="secondary" size="sm" onClick={() => setEditingSet('new')}>+ New type set</Button>
      </div>
      <div className="flex flex-col gap-2">
        {sets.map(set => (
          <div key={set.id} className="flex items-center gap-3 rounded-lg border px-4 py-3"
               style={{ background: 'white', borderColor: 'var(--color-border)' }}>
            <span className="text-sm font-medium" style={{ minWidth: 160 }}>
              {set.name}
              {set.systemDefault && <span className="mono text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>system</span>}
            </span>
            <span className="flex flex-wrap gap-1.5 flex-1">
              {set.types.map(t => (
                <span key={t.id}
                      className="inline-flex items-center gap-1.5 text-xs rounded-full border px-2.5 py-0.5"
                      style={{ borderColor: 'var(--color-border-2)', background: 'white' }}>
                  <span className="rounded-full" style={{ width: 8, height: 8, background: t.color }} />
                  {t.name}
                </span>
              ))}
            </span>
            <span className="text-xs px-2.5 py-0.5 rounded-full whitespace-nowrap"
                  style={{ color: 'var(--color-brand)', background: '#E7F0EE' }}>
              {set.projectsUsing} project{set.projectsUsing !== 1 ? 's' : ''}
            </span>
            <Button variant="ghost" size="sm" onClick={() => setEditingSet(set)}>Edit</Button>
            {!set.systemDefault && (
              <Button variant="ghost" size="sm" style={{ color: 'var(--color-error)' }}
                      onClick={() => { if (window.confirm(`Delete set “${set.name}”?`)) delSet.mutate(set.id) }}>
                Delete
              </Button>
            )}
          </div>
        ))}
      </div>

      {editing && (
        <TypeForm type={editing === 'new' ? null : editing}
                  onClose={() => setEditing(null)}
                  onSaved={() => { setEditing(null); invalidate() }} />
      )}
      {editingSet && (
        <TypeSetForm set={editingSet === 'new' ? null : editingSet}
                     types={types.filter(t => !t.archived)}
                     onClose={() => setEditingSet(null)}
                     onSaved={() => { setEditingSet(null); invalidate() }} />
      )}
      {deleting && (
        <DeleteDialog
          entity="issue type"
          name={deleting.name}
          usage={deleting.usage}
          replacements={types.filter(t => t.id !== deleting.id && !t.archived)
            .map(t => ({ id: t.id, name: t.name }))}
          onDelete={replaceWithId => del.mutate({ id: deleting.id, replaceWithId })}
          onArchive={() => archive.mutate({ id: deleting.id, archived: false })}
          onClose={() => setDeleting(null)}
          error={error}
        />
      )}
    </>
  )
}

function TypeSetForm({ set, types, onClose, onSaved }: {
  set: AdminIssueTypeSet | null
  types: AdminIssueType[]
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(set?.name ?? '')
  const [selected, setSelected] = useState<string[]>(set?.types.map(t => t.id) ?? [])
  const [error, setError] = useState('')

  function toggle(id: string) {
    setSelected(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id])
  }

  const save = useMutation({
    mutationFn: () => {
      // Types in catalog order, like priority sets
      const payload = { name: name.trim(), typeIds: types.filter(t => selected.includes(t.id)).map(t => t.id) }
      return set ? adminIssueTypeSets.update(set.id, payload) : adminIssueTypeSets.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  return (
    <Modal title={set ? `Edit type set “${set.name}”` : 'New type set'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <ImpactBanner projectsUsing={set?.projectsUsing ?? 0} entity="type set" />
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
            Types offered by projects using this set
          </span>
          {types.map(t => (
            <Checkbox key={t.id} checked={selected.includes(t.id)} onChange={() => toggle(t.id)}
                      label={<span className="inline-flex items-center gap-1.5">
                        <span className="rounded-full" style={{ width: 8, height: 8, background: t.color }} />
                        {t.name}
                      </span>} />
          ))}
        </div>
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          Removing a type never touches existing issues — they keep it; only new issues are restricted.
        </p>
        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim() || selected.length === 0}
                  loading={save.isPending} onClick={() => save.mutate()}>
            {set ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function TypeForm({ type, onClose, onSaved }: {
  type: AdminIssueType | null; onClose: () => void; onSaved: () => void
}) {
  const [name, setName] = useState(type?.name ?? '')
  const [color, setColor] = useState(type?.color ?? '#6B7280')
  const [icon, setIcon] = useState(type?.icon ?? '')
  const [error, setError] = useState('')

  const save = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), color, icon: icon.trim() || undefined }
      return type ? adminIssueTypes.update(type.id, payload) : adminIssueTypes.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  return (
    <Modal title={type ? `Edit issue type “${type.name}”` : 'New issue type'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        <Input label="Icon (name shown in future icon picker)" value={icon} onChange={e => setIcon(e.target.value)} />
        <ColorField value={color} onChange={setColor} />
        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            {type ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
