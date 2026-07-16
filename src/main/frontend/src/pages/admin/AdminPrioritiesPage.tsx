import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Star } from 'lucide-react'
import { adminPriorities, adminPrioritySets } from '../../api'
import type { AdminPriority, AdminPrioritySet } from '../../types'
import { Button, Checkbox, Input, PriorityIcon, Select } from '../../components/ui'
import { AdminTable, ArchivedBadge, ArchivedToggle, DeleteDialog, ImpactBanner, Modal, PageHeader, UsageChip } from './common'
import { ColorField } from './AdminStatusesPage'

const ICONS = ['chevrons-up', 'chevron-up', 'equal', 'chevron-down', 'minus'] as const

export default function AdminPrioritiesPage() {
  const qc = useQueryClient()
  const { data: priorities = [] } = useQuery({ queryKey: ['admin', 'priorities'], queryFn: adminPriorities.list })
  const { data: sets = [] } = useQuery({ queryKey: ['admin', 'priority-sets'], queryFn: adminPrioritySets.list })
  const [editing, setEditing] = useState<AdminPriority | 'new' | null>(null)
  const [editingSet, setEditingSet] = useState<AdminPrioritySet | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AdminPriority | null>(null)
  const [showArchived, setShowArchived] = useState(false)
  const [error, setError] = useState('')

  const visible = showArchived ? priorities : priorities.filter(p => !p.archived)

  const invalidate = () => qc.invalidateQueries({ queryKey: ['admin'] })

  const del = useMutation({
    mutationFn: ({ id, replaceWithId }: { id: string; replaceWithId?: string }) =>
      adminPriorities.remove(id, replaceWithId),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Delete failed'),
  })

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? adminPriorities.unarchive(id) : adminPriorities.archive(id),
    onSuccess: () => { setDeleting(null); invalidate() },
  })

  const delSet = useMutation({
    mutationFn: (id: string) => adminPrioritySets.remove(id),
    onSuccess: invalidate,
    onError: e => window.alert(e instanceof Error ? e.message : 'Delete failed'),
  })

  return (
    <>
      <PageHeader
        title="Priorities"
        subtitle="Global catalog. Priority sets pick which of these a project offers and which one is the default for new issues."
        action={<Button variant="primary" onClick={() => setEditing('new')}>+ New priority</Button>}
      />
      <ArchivedToggle archivedCount={priorities.filter(p => p.archived).length}
                      value={showArchived} onChange={setShowArchived} />
      <AdminTable headers={['Name', 'Color', 'Used in', '']}>
        {visible.map(p => (
          <tr key={p.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2 text-sm">
                <PriorityIcon priority={p} />
                {p.name}
                {p.archived && <ArchivedBadge />}
              </span>
            </td>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2">
                <span className="rounded-full" style={{ width: 10, height: 10, background: p.color }} />
                <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{p.color}</span>
              </span>
            </td>
            <td className="px-3 py-2.5"><UsageChip usage={p.usage} fetchDetail={() => adminPriorities.usage(p.id)} /></td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={() => setEditing(p)}>Edit</Button>
              <Button variant="ghost" size="sm" onClick={() => archive.mutate({ id: p.id, archived: p.archived })}>
                {p.archived ? 'Unarchive' : 'Archive'}
              </Button>
              <Button variant="ghost" size="sm" style={{ color: 'var(--color-error)' }}
                      onClick={() => { setError(''); setDeleting(p) }}>
                Delete
              </Button>
            </td>
          </tr>
        ))}
      </AdminTable>

      {/* Priority sets */}
      <div className="flex items-center justify-between mt-8 mb-3">
        <h2 className="font-display font-bold" style={{ fontSize: 17 }}>Priority sets</h2>
        <Button variant="secondary" size="sm" onClick={() => setEditingSet('new')}>+ New priority set</Button>
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
              {set.items.map(i => (
                <span key={i.priority.id}
                      className="inline-flex items-center gap-1 text-xs rounded-full border px-2.5 py-0.5"
                      style={{ borderColor: i.isDefault ? 'var(--color-warning)' : 'var(--color-border-2)', background: 'white' }}>
                  <PriorityIcon priority={i.priority} size={12} />
                  {i.priority.name}
                  {i.isDefault && <Star size={10} style={{ color: 'var(--color-warning)' }} fill="var(--color-warning)" />}
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
        <PriorityForm priority={editing === 'new' ? null : editing}
                      onClose={() => setEditing(null)}
                      onSaved={() => { setEditing(null); invalidate() }} />
      )}
      {editingSet && (
        <PrioritySetForm set={editingSet === 'new' ? null : editingSet}
                         priorities={priorities.filter(p => !p.archived)}
                         onClose={() => setEditingSet(null)}
                         onSaved={() => { setEditingSet(null); invalidate() }} />
      )}
      {deleting && (
        <DeleteDialog
          entity="priority"
          name={deleting.name}
          usage={deleting.usage}
          replacements={priorities.filter(p => p.id !== deleting.id && !p.archived)
            .map(p => ({ id: p.id, name: p.name }))}
          onDelete={replaceWithId => del.mutate({ id: deleting.id, replaceWithId })}
          onArchive={() => archive.mutate({ id: deleting.id, archived: false })}
          onClose={() => setDeleting(null)}
          error={error}
        />
      )}
    </>
  )
}

function PriorityForm({ priority, onClose, onSaved }: {
  priority: AdminPriority | null; onClose: () => void; onSaved: () => void
}) {
  const [name, setName] = useState(priority?.name ?? '')
  const [color, setColor] = useState(priority?.color ?? '#8B8680')
  const [icon, setIcon] = useState(priority?.icon ?? 'minus')
  const [error, setError] = useState('')

  const save = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), color, icon }
      return priority ? adminPriorities.update(priority.id, payload) : adminPriorities.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  return (
    <Modal title={priority ? `Edit priority “${priority.name}”` : 'New priority'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        <Select label="Icon" value={icon} onChange={e => setIcon(e.target.value)}>
          {ICONS.map(i => <option key={i} value={i}>{i}</option>)}
        </Select>
        <ColorField value={color} onChange={setColor} />
        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            {priority ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function PrioritySetForm({ set, priorities, onClose, onSaved }: {
  set: AdminPrioritySet | null
  priorities: AdminPriority[]
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(set?.name ?? '')
  const [selected, setSelected] = useState<string[]>(set?.items.map(i => i.priority.id) ?? [])
  const [defaultId, setDefaultId] = useState(set?.items.find(i => i.isDefault)?.priority.id ?? '')
  const [error, setError] = useState('')

  function toggle(id: string) {
    setSelected(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id])
  }

  const save = useMutation({
    mutationFn: () => {
      // Items in catalog order; the checked default falls back to the first item
      const ordered = priorities.filter(p => selected.includes(p.id))
      const payload = {
        name: name.trim(),
        items: ordered.map(p => ({ priorityId: p.id, isDefault: p.id === defaultId })),
      }
      return set ? adminPrioritySets.update(set.id, payload) : adminPrioritySets.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  return (
    <Modal title={set ? `Edit priority set “${set.name}”` : 'New priority set'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <ImpactBanner projectsUsing={set?.projectsUsing ?? 0} entity="priority set" />
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
            Priorities in this set · ★ = default for new issues
          </span>
          {priorities.map(p => (
            <div key={p.id} className="flex items-center gap-2">
              <Checkbox checked={selected.includes(p.id)} onChange={() => toggle(p.id)}
                        label={<span className="inline-flex items-center gap-1.5"><PriorityIcon priority={p} size={13} />{p.name}</span>} />
              {selected.includes(p.id) && (
                <button type="button" onClick={() => setDefaultId(p.id)}
                        className="cursor-pointer ml-auto" title="Default for new issues">
                  <Star size={14}
                        style={{ color: defaultId === p.id ? 'var(--color-warning)' : 'var(--color-border-2)' }}
                        fill={defaultId === p.id ? 'var(--color-warning)' : 'none'} />
                </button>
              )}
            </div>
          ))}
        </div>
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
