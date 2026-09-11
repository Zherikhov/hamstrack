import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { AlertTriangle, EyeOff, Trash2 } from 'lucide-react'
import type { UpsertFieldPayload } from '../../api'
import type { AdminField, AdminFieldSet, AdminScopeTag, FieldType, Hex } from '../../types'
import { FIELD_TYPE_LABELS } from '../../components/fields'
import { Button, Checkbox, Input, Select } from '../../components/ui'
import { AdminTable, ArchivedBadge, ArchivedToggle, ImpactBanner, InheritedBadge, Modal, PageHeader, UsageChip } from './common'
import { ownScopeTag, useAdminApi, useAdminInvalidate } from './AdminApiContext'
import { ColorField } from './AdminStatusesPage'
import { SURFACE, inkOn, token } from '../../colour'

export default function AdminFieldsPage() {
  const { api, keyPrefix, scope } = useAdminApi()
  const ownTag = ownScopeTag(scope)
  const invalidate = useAdminInvalidate()
  const { data: fields = [] } = useQuery({ queryKey: [...keyPrefix, 'fields'], queryFn: api.fields.list })
  const { data: sets = [] } = useQuery({ queryKey: [...keyPrefix, 'field-sets'], queryFn: api.fieldSets.list })
  const [editing, setEditing] = useState<AdminField | 'new' | null>(null)
  const [editingSet, setEditingSet] = useState<AdminFieldSet | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AdminField | null>(null)
  const [showArchived, setShowArchived] = useState(false)

  const visible = showArchived ? fields : fields.filter(f => !f.archived)

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? api.fields.unarchive(id) : api.fields.archive(id),
    onSuccess: () => { setDeleting(null); invalidate() },
  })

  const delSet = useMutation({
    mutationFn: (id: string) => api.fieldSets.remove(id),
    onSuccess: invalidate,
    onError: e => window.alert(e instanceof Error ? e.message : 'Delete failed'),
  })

  return (
    <>
      <PageHeader
        title="Fields"
        subtitle={scope === 'project'
          ? 'Custom fields private to this project. Field sets pick which appear on issues, in what order, and how they behave on the create form.'
          : 'Catalog of custom fields. Field sets pick which of these a project shows on its issues, in what order, and how they behave on the create form.'}
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
              <ShadowedKeyWarning field={f} ownTag={ownTag} />
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
                        style={{ borderColor: 'var(--color-border-2)', color: o.color ? inkOn(o.color, SURFACE.card) : 'var(--color-text-secondary)' }}>
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
            <td className="px-3 py-2.5">{f.usage && <UsageChip usage={f.usage} fetchDetail={() => api.fields.usage(f.id)} />}</td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              {f.scope === ownTag ? (
                <>
                  <Button variant="ghost" size="sm" onClick={() => setEditing(f)}>Edit</Button>
                  <Button variant="ghost" size="sm" onClick={() => archive.mutate({ id: f.id, archived: f.archived })}>
                    {f.archived ? 'Unarchive' : 'Archive'}
                  </Button>
                  <Button variant="ghost" size="sm" style={{ color: 'var(--color-error-ink)' }}
                          onClick={() => setDeleting(f)}>
                    Delete
                  </Button>
                </>
              ) : <InheritedBadge scope={f.scope} />}
            </td>
          </tr>
        ))}
      </AdminTable>
      {fields.length === 0 && (
        <p className="text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>No custom fields yet.</p>
      )}

      {/* Field sets */}
      <div className="flex items-center justify-between mt-8 mb-3">
        {/* eslint-disable-next-line hamstrack/no-numeric-font-size -- HD-177 */}
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
                  {i.required && <span style={{ color: 'var(--color-error-ink)' }}>*</span>}
                  {!i.showOnCreate && <EyeOff size={10} style={{ color: 'var(--color-text-muted)' }} />}
                </span>
              ))}
            </span>
            <span className="text-xs px-2.5 py-0.5 rounded-full whitespace-nowrap"
                  style={{ color: 'var(--color-brand-ink)', background: '#E7F0EE' }}>
              {set.projectsUsing} project{set.projectsUsing !== 1 ? 's' : ''}
            </span>
            {set.scope === ownTag ? (
              <>
                <Button variant="ghost" size="sm" onClick={() => setEditingSet(set)}>Edit</Button>
                {!set.systemDefault && (
                  <Button variant="ghost" size="sm" style={{ color: 'var(--color-error-ink)' }}
                          onClick={() => { if (window.confirm(`Delete set “${set.name}”?`)) delSet.mutate(set.id) }}>
                    Delete
                  </Button>
                )}
              </>
            ) : <InheritedBadge scope={set.scope} />}
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
 * HD-275 — the row-level end of the silence.
 *
 * A field whose key a built-in HQL name has taken keeps working everywhere
 * except the query surface: `labels = "x"` answers from the built-in `label`
 * field, with 200 and plausible rows the tenant never set. Nothing said so, and
 * the only symptom was the field's *absence* from `/search/schema`.
 *
 * **Shown only while the field is live.** An archived definition is out of
 * resolution entirely, so warning about one would mean every Hamstrack instance
 * warning about V3's own archived `labels`/`sprint`/`components` seed rows on
 * every visit — a signal dead on arrival (proposal §2). The rename affordance
 * in `FieldForm` uses the *other* predicate and is offered for an archived row;
 * the two look alike on purpose and are not the same.
 *
 * The remedy names someone the reader can actually reach, and no more than
 * that: a console that cannot perform the rename says which administrator can,
 * and never identifies the workspace (that would be a scope the project admin
 * was not shown).
 */
function ShadowedKeyWarning({ field, ownTag }: { field: AdminField; ownTag: AdminScopeTag }) {
  if (!field.shadowedBy || field.archived) return null
  const remedy = field.scope === ownTag
    ? 'Edit the field to rename its key.'
    : field.scope === 'GLOBAL'
      ? 'An instance administrator can rename its key.'
      : 'A workspace administrator can rename its key.'
  return (
    <span className="mt-1.5 flex items-start gap-1.5 text-xs rounded-md px-2 py-1"
          style={{
            background: 'color-mix(in srgb, var(--color-warning) 12%, white)',
            border: '1px solid color-mix(in srgb, var(--color-warning) 34%, white)',
            color: 'var(--color-warning-ink)',
            maxWidth: 460,
          }}>
      <AlertTriangle size={12} style={{ marginTop: 2, flexShrink: 0 }} aria-hidden="true" />
      <span>
        Not searchable — “{field.key}” is a built-in search name. A query for it answers from the
        built-in “{field.shadowedBy}” field, not from this one. {remedy}
      </span>
    </span>
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
  const { api } = useAdminApi()
  const issues = field.usage?.issues ?? 0
  const [confirmed, setConfirmed] = useState(false)
  const [error, setError] = useState('')

  const del = useMutation({
    mutationFn: () => api.fields.remove(field.id, issues > 0),
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
        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
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

interface OptionDraft { id: string; label: string; color?: Hex }

function slugify(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '')
}

function FieldForm({ field, onClose, onSaved }: {
  field: AdminField | null; onClose: () => void; onSaved: () => void
}) {
  const { api, scope } = useAdminApi()
  const isNew = field === null
  /**
   * HD-275 §6.2 — the key stops being immutable *exactly while* a built-in
   * search name shadows it, which is the only population where the rename
   * cannot change what any stored filter means. Three conditions, each of which
   * the server also enforces: the field must be shadowed (422 otherwise), owned
   * by this console (404 — a project cannot rename a field it inherits), and
   * not a system definition (409 — `DemoDataService` resolves those by key).
   *
   * Deliberately NOT conditioned on `archived`: an archived shadowed field is
   * the one this affordance matters most for, because archiving it is what a
   * curator does when it stops working. Rename, then unarchive.
   */
  const canRename = !!field && !!field.shadowedBy && field.scope === ownScopeTag(scope) && !field.isSystem
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
      const trimmedKey = key.trim()
      /**
       * **Send `key` only when it actually changed.** On update the server reads
       * a `key` as a *rename request* and refuses one with 422 on a field that
       * is not shadowed — so echoing the unchanged key here (which this form
       * used to do on every save) would make every ordinary field edit, name
       * only, start failing. The server's own trigger is difference rather than
       * presence for exactly that reason; the client states the same intent
       * rather than relying on it.
       *
       * Compared case-insensitively because the server lower-cases and compares
       * the same way — `Labels` typed over `labels` is not a rename.
       */
      const renaming = !!field
        && trimmedKey !== ''
        && trimmedKey.toLowerCase() !== field.key.toLowerCase()
      const payload: UpsertFieldPayload = {
        name: name.trim(),
        key: field ? (renaming ? trimmedKey : undefined) : (trimmedKey || undefined),
        type,
        description: description.trim() || undefined,
        config: isSelect
          // Stored values reference option ids, so ids of existing options are kept as-is
          ? { options: options.filter(o => o.label.trim()).map(o => ({ ...o, id: o.id || slugify(o.label) })) }
          : type === 'NUMBER' && (min !== '' || max !== '')
            ? { ...(min !== '' ? { min: Number(min) } : {}), ...(max !== '' ? { max: Number(max) } : {}) }
            : null,
      }
      return field ? api.fields.update(field.id, payload) : api.fields.create(payload)
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
        ) : canRename ? (
          <>
            <Input label="Key" value={key} placeholder={field.key}
                   onChange={e => setKey(e.target.value)} />
            {/* The remedy is stated in full because the reader is about to perform
                it. No count of affected saved filters is shown, and none can be:
                for a global definition the candidate set spans workspaces this
                admin cannot see, and in the shadowed population the number of
                filters that actually break is zero — a figure here would invite
                the reader to believe something does. */}
            <p className="text-xs rounded-md px-2.5 py-2"
               style={{
                 background: 'color-mix(in srgb, var(--color-warning) 12%, white)',
                 border: '1px solid color-mix(in srgb, var(--color-warning) 34%, white)',
                 color: 'var(--color-warning-ink)',
               }}>
              “{field.key}” is a built-in search name, so searching for it answers from the built-in
              “{field.shadowedBy}” field and not from this one. Renaming the key here makes this field
              searchable again under the new name. Issue values are not affected. Saved filters are not
              rewritten — anyone using the old key will need to update their filter to the new one.
            </p>
            <p className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
              {FIELD_TYPE_LABELS[field.type]} — the type is fixed once created
            </p>
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
              <p className="text-xs" style={{ color: 'var(--color-warning-ink)' }}>
                Issues store option ids — removing an option leaves old values showing the raw id.
              </p>
            )}
            {/* An option colour is a stored, user-chosen colour like any other, so it
                renders by the same rule — excluding it would mean the same yellow is
                legible as a status and unreadable as a Severity. */}
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              An option's colour is its identity: the dot is painted exactly as picked, and its
              label is dimmed to the same hue only when the picked one cannot be read.
            </p>
            {options.map((o, idx) => (
              <div key={idx} className="flex items-center gap-2">
                <Input value={o.label} placeholder="Label" className="flex-1"
                       onChange={e => updateOption(idx, { label: e.target.value })} />
                <ColorField compact value={o.color ?? token('--color-sandbox')}
                            onChange={v => updateOption(idx, { color: v })} />
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

        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
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
  const { api } = useAdminApi()
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
      return set ? api.fieldSets.update(set.id, payload) : api.fieldSets.create(payload)
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
        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
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
