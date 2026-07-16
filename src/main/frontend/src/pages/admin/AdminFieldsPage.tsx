import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { EyeOff, Trash2 } from 'lucide-react'
import { adminFields, adminFieldSets } from '../../api'
import type { UpsertFieldPayload } from '../../api'
import type { AdminField, AdminFieldSet, FieldType } from '../../types'
import { FIELD_TYPE_LABELS } from '../../components/fields'
import { Button, Checkbox, Input, Select } from '../../components/ui'
import { AdminTable, ArchivedBadge, ArchivedToggle, ImpactBanner, Modal, PageHeader, UsageChip } from './common'

export default function AdminFieldsPage() {
  const qc = useQueryClient()
  const { data: fields = [] } = useQuery({ queryKey: ['admin', 'fields'], queryFn: adminFields.list })
  const { data: sets = [] } = useQuery({ queryKey: ['admin', 'field-sets'], queryFn: adminFieldSets.list })
  const [editing, setEditing] = useState<AdminField | 'new' | null>(null)
  const [editingSet, setEditingSet] = useState<AdminFieldSet | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AdminField | null>(null)
  const [showArchived, setShowArchived] = useState(false)

  const visible = showArchived ? fields : fields.filter(f => !f.archived)

  const invalidate = () => qc.invalidateQueries({ queryKey: ['admin'] })

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? adminFields.unarchive(id) : adminFields.archive(id),
    onSuccess: () => { setDeleting(null); invalidate() },
  })

  const delSet = useMutation({
    mutationFn: (id: string) => adminFieldSets.remove(id),
    onSuccess: invalidate,
    onError: e => window.alert(e instanceof Error ? e.message : 'Delete failed'),
  })

  return (
    <>
      <PageHeader
        title="Fields"
        subtitle="Global catalog of custom fields. Field sets pick which of these a project shows on its issues, in what order, and how they behave on the create form."
        action={<Button variant="primary" onClick={() => setEditing('new')}>+ New field</Button>}
      />
      <ArchivedToggle archivedCount={fields.filter(f => f.archived).length}
                      value={showArchived} onChange={setShowArchived} />
      <AdminTable headers={['Name', 'Type', 'Options', 'Used in', '']}>
        {visible.map(f => (
          <tr key={f.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2 text-sm">
                {f.name}
                <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{f.key}</span>
                {f.archived && <ArchivedBadge />}
              </span>
            </td>
            <td className="px-3 py-2.5">
              <span className="text-xs px-2 py-0.5 rounded"
                    style={{ color: 'var(--color-text-secondary)', background: 'var(--color-surface-2)' }}>
                {FIELD_TYPE_LABELS[f.type]}
              </span>
            </td>
            <td className="px-3 py-2.5">
              <span className="flex flex-wrap gap-1" style={{ maxWidth: 240 }}>
                {f.config?.options?.map(o => (
                  <span key={o.id} className="text-xs rounded-full border px-2 py-0.5"
                        style={{ borderColor: 'var(--color-border-2)', color: o.color ?? 'var(--color-text-secondary)' }}>
                    {o.label}
                  </span>
                ))}
                {f.type === 'NUMBER' && (f.config?.min !== undefined || f.config?.max !== undefined) && (
                  <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    {f.config?.min ?? '−∞'} … {f.config?.max ?? '∞'}
                  </span>
                )}
              </span>
            </td>
            <td className="px-3 py-2.5">{f.usage && <UsageChip usage={f.usage} fetchDetail={() => adminFields.usage(f.id)} />}</td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={() => setEditing(f)}>Edit</Button>
              <Button variant="ghost" size="sm" onClick={() => archive.mutate({ id: f.id, archived: f.archived })}>
                {f.archived ? 'Unarchive' : 'Archive'}
              </Button>
              <Button variant="ghost" size="sm" style={{ color: 'var(--color-error)' }}
                      onClick={() => setDeleting(f)}>
                Delete
              </Button>
            </td>
          </tr>
        ))}
      </AdminTable>
      {fields.length === 0 && (
        <p className="text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>No custom fields yet.</p>
      )}

      {/* Field sets */}
      <div className="flex items-center justify-between mt-8 mb-3">
        <h2 className="font-display font-bold" style={{ fontSize: 17 }}>Field sets</h2>
        <Button variant="secondary" size="sm" onClick={() => setEditingSet('new')}>+ New field set</Button>
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
              {set.items.length === 0 && (
                <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>no fields</span>
              )}
              {set.items.map(i => (
                <span key={i.field.id}
                      className="inline-flex items-center gap-1 text-xs rounded-full border px-2.5 py-0.5"
                      style={{ borderColor: 'var(--color-border-2)', background: 'white' }}
                      title={`${FIELD_TYPE_LABELS[i.field.type]}${i.required ? ' · required' : ''}${i.showOnCreate ? '' : ' · hidden on create'}`}>
                  {i.field.name}
                  {i.required && <span style={{ color: 'var(--color-error)' }}>*</span>}
                  {!i.showOnCreate && <EyeOff size={10} style={{ color: 'var(--color-text-muted)' }} />}
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
        <FieldForm field={editing === 'new' ? null : editing}
                   onClose={() => setEditing(null)}
                   onSaved={() => { setEditing(null); invalidate() }} />
      )}
      {editingSet && (
        <FieldSetForm set={editingSet === 'new' ? null : editingSet}
                      fields={fields.filter(f => !f.archived)}
                      onClose={() => setEditingSet(null)}
                      onSaved={() => { setEditingSet(null); invalidate() }} />
      )}
      {deleting && (
        <FieldDeleteDialog field={deleting}
                           onArchive={() => archive.mutate({ id: deleting.id, archived: false })}
                           onClose={() => setDeleting(null)}
                           onDeleted={() => { setDeleting(null); invalidate() }} />
      )}
    </>
  )
}

/**
 * Field values have no meaningful remap across arbitrary shapes, so unlike
 * statuses/priorities the delete dialog offers "drop the values" instead of a
 * replacement select — or archiving, which keeps history intact.
 */
function FieldDeleteDialog({ field, onArchive, onClose, onDeleted }: {
  field: AdminField; onArchive: () => void; onClose: () => void; onDeleted: () => void
}) {
  const issues = field.usage?.issues ?? 0
  const [confirmed, setConfirmed] = useState(false)
  const [error, setError] = useState('')

  const del = useMutation({
    mutationFn: () => adminFields.remove(field.id, issues > 0),
    onSuccess: onDeleted,
    onError: e => setError(e instanceof Error ? e.message : 'Delete failed'),
  })

  return (
    <Modal title={`Delete field “${field.name}”?`} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <div className="text-sm rounded-lg px-3 py-2.5"
             style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
          {issues > 0 ? (
            <><span className="mono">{issues}</span> issue(s) have a value for this field — deleting the
              field <b>permanently drops those values</b>. Archiving hides the field from forms and keeps them.</>
          ) : (
            <>No issues carry a value for this field{(field.usage?.sets ?? 0) > 0 && <> — it will also be removed from {field.usage!.sets} field set(s)</>}.</>
          )}
        </div>
        {issues > 0 && (
          <Checkbox checked={confirmed} onChange={e => setConfirmed(e.target.checked)}
                    label={`Delete the values on ${issues} issue(s)`} />
        )}
        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="secondary" onClick={onArchive}>Archive instead</Button>
          <Button variant="danger" disabled={issues > 0 && !confirmed}
                  loading={del.isPending} onClick={() => del.mutate()}>
            <Trash2 size={13} /> Delete
          </Button>
        </div>
      </div>
    </Modal>
  )
}

interface OptionDraft { id: string; label: string; color?: string }

function slugify(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '')
}

function FieldForm({ field, onClose, onSaved }: {
  field: AdminField | null; onClose: () => void; onSaved: () => void
}) {
  const isNew = field === null
  const [name, setName] = useState(field?.name ?? '')
  const [key, setKey] = useState(field?.key ?? '')
  const [type, setType] = useState<FieldType>(field?.type ?? 'TEXT')
  const [description, setDescription] = useState(field?.description ?? '')
  const [options, setOptions] = useState<OptionDraft[]>(field?.config?.options ?? [])
  const [min, setMin] = useState(field?.config?.min !== undefined ? String(field.config.min) : '')
  const [max, setMax] = useState(field?.config?.max !== undefined ? String(field.config.max) : '')
  const [error, setError] = useState('')

  const isSelect = type === 'SELECT' || type === 'MULTI_SELECT'
  const hasValues = (field?.usage?.issues ?? 0) > 0

  function updateOption(idx: number, patch: Partial<OptionDraft>) {
    setOptions(prev => prev.map((o, i) => i === idx ? { ...o, ...patch } : o))
  }

  const save = useMutation({
    mutationFn: () => {
      const payload: UpsertFieldPayload = {
        name: name.trim(),
        key: key.trim() || undefined,
        type,
        description: description.trim() || undefined,
        config: isSelect
          // Stored values reference option ids, so ids of existing options are kept as-is
          ? { options: options.filter(o => o.label.trim()).map(o => ({ ...o, id: o.id || slugify(o.label) })) }
          : type === 'NUMBER' && (min !== '' || max !== '')
            ? { ...(min !== '' ? { min: Number(min) } : {}), ...(max !== '' ? { max: Number(max) } : {}) }
            : null,
      }
      return field ? adminFields.update(field.id, payload) : adminFields.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  const valid = name.trim() && (!isSelect || options.some(o => o.label.trim()))

  return (
    <Modal title={field ? `Edit field “${field.name}”` : 'New field'} onClose={onClose} width={480}>
      <div className="flex flex-col gap-3">
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        {isNew ? (
          <>
            <Input label="Key (optional — derived from the name)" value={key}
                   placeholder={slugify(name) || 'snake_case'}
                   onChange={e => setKey(e.target.value)} />
            <Select label="Type" value={type} onChange={e => setType(e.target.value as FieldType)}>
              {(Object.keys(FIELD_TYPE_LABELS) as FieldType[]).map(t => (
                <option key={t} value={t}>{FIELD_TYPE_LABELS[t]}</option>
              ))}
            </Select>
          </>
        ) : (
          <p className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {field.key} · {FIELD_TYPE_LABELS[field.type]} — key and type are fixed once created
          </p>
        )}
        <Input label="Description (shown as a hint in issue forms)" value={description}
               onChange={e => setDescription(e.target.value)} />

        {isSelect && (
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Options</span>
            {hasValues && (
              <p className="text-xs" style={{ color: 'var(--color-warning)' }}>
                Issues store option ids — removing an option leaves old values showing the raw id.
              </p>
            )}
            {options.map((o, idx) => (
              <div key={idx} className="flex items-center gap-2">
                <Input value={o.label} placeholder="Label" className="flex-1"
                       onChange={e => updateOption(idx, { label: e.target.value })} />
                <input type="color" value={o.color ?? '#64748B'} className="cursor-pointer flex-shrink-0"
                       style={{ width: 30, height: 28, border: 'none', background: 'none' }}
                       onChange={e => updateOption(idx, { color: e.target.value })} />
                <button type="button" className="cursor-pointer hover:opacity-60 flex-shrink-0"
                        onClick={() => setOptions(prev => prev.filter((_, i) => i !== idx))}>
                  <Trash2 size={13} style={{ color: 'var(--color-text-muted)' }} />
                </button>
              </div>
            ))}
            <Button variant="ghost" size="sm" className="self-start"
                    onClick={() => setOptions(prev => [...prev, { id: '', label: '' }])}>
              + Add option
            </Button>
          </div>
        )}

        {type === 'NUMBER' && (
          <div className="grid grid-cols-2 gap-3">
            <Input label="Min (optional)" type="number" value={min} onChange={e => setMin(e.target.value)} />
            <Input label="Max (optional)" type="number" value={max} onChange={e => setMax(e.target.value)} />
          </div>
        )}

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!valid} loading={save.isPending} onClick={() => save.mutate()}>
            {field ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function FieldSetForm({ set, fields, onClose, onSaved }: {
  set: AdminFieldSet | null
  fields: AdminField[]
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(set?.name ?? '')
  const [items, setItems] = useState<Map<string, { required: boolean; showOnCreate: boolean }>>(
    new Map(set?.items.map(i => [i.field.id, { required: i.required, showOnCreate: i.showOnCreate }]) ?? [])
  )
  const [error, setError] = useState('')

  function toggle(id: string) {
    setItems(prev => {
      const next = new Map(prev)
      if (next.has(id)) next.delete(id)
      else next.set(id, { required: false, showOnCreate: true })
      return next
    })
  }

  function patch(id: string, p: Partial<{ required: boolean; showOnCreate: boolean }>) {
    setItems(prev => {
      const next = new Map(prev)
      const cur = next.get(id)
      if (!cur) return prev
      const merged = { ...cur, ...p }
      // A required field the create form doesn't show would make creation impossible
      if (merged.required) merged.showOnCreate = true
      next.set(id, merged)
      return next
    })
  }

  const save = useMutation({
    mutationFn: () => {
      // Items in catalog order, like priority sets
      const ordered = fields.filter(f => items.has(f.id))
      const payload = {
        name: name.trim(),
        items: ordered.map(f => ({ fieldId: f.id, ...items.get(f.id)! })),
      }
      return set ? adminFieldSets.update(set.id, payload) : adminFieldSets.create(payload)
    },
    onSuccess: onSaved,
    onError: e => setError(e instanceof Error ? e.message : 'Save failed'),
  })

  return (
    <Modal title={set ? `Edit field set “${set.name}”` : 'New field set'} onClose={onClose} width={480}>
      <div className="flex flex-col gap-3">
        <ImpactBanner projectsUsing={set?.projectsUsing ?? 0} entity="field set" />
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} autoFocus />
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
            Fields in this set
          </span>
          {fields.length === 0 && (
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Create fields in the catalog first.</p>
          )}
          {fields.map(f => {
            const item = items.get(f.id)
            return (
              <div key={f.id} className="flex items-center gap-3">
                <Checkbox checked={!!item} onChange={() => toggle(f.id)}
                          label={<span className="inline-flex items-center gap-1.5">
                            {f.name}
                            <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                              {FIELD_TYPE_LABELS[f.type]}
                            </span>
                          </span>} />
                {item && (
                  <span className="ml-auto flex items-center gap-3 flex-shrink-0">
                    <Checkbox checked={item.required} onChange={e => patch(f.id, { required: e.target.checked })}
                              label={<span className="text-xs">required</span>} />
                    <Checkbox checked={item.showOnCreate} disabled={item.required}
                              onChange={e => patch(f.id, { showOnCreate: e.target.checked })}
                              label={<span className="text-xs">on create</span>} />
                  </span>
                )}
              </div>
            )
          })}
        </div>
        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            {set ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
