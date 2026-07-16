import { Check, ExternalLink } from 'lucide-react'
import type { FieldType, FieldValue, ProjectField, WorkspaceMember } from '../types'
import { Checkbox, Input, Select, Textarea } from './ui'

// Custom-field rendering shared by the create modal, the issue side panel and
// the backlog table. Value shapes per field type are documented on FieldValue.

export const FIELD_TYPE_LABELS: Record<FieldType, string> = {
  TEXT: 'Text',
  TEXTAREA: 'Multi-line text',
  NUMBER: 'Number',
  DATE: 'Date',
  SELECT: 'Select',
  MULTI_SELECT: 'Multi-select',
  USER: 'User',
  CHECKBOX: 'Checkbox',
  URL: 'URL',
}

function optionOf(field: ProjectField, id: string) {
  return field.config?.options?.find(o => o.id === id)
}

/** Editor for one custom field. undefined value = not filled. */
export function FieldInput({ field, value, onChange, members = [] }: {
  field: ProjectField
  value: FieldValue | undefined
  onChange: (value: FieldValue | undefined) => void
  /** Workspace members — the option list for USER fields. */
  members?: WorkspaceMember[]
}) {
  const label = field.required ? `${field.name} *` : field.name
  const hint = field.description && (
    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{field.description}</span>
  )

  switch (field.type) {
    case 'TEXT':
    case 'URL':
      return (
        <div className="flex flex-col gap-1">
          <Input label={label} value={(value as string) ?? ''}
                 placeholder={field.type === 'URL' ? 'https://…' : undefined}
                 onChange={e => onChange(e.target.value || undefined)} />
          {hint}
        </div>
      )
    case 'TEXTAREA':
      return (
        <div className="flex flex-col gap-1">
          <Textarea label={label} rows={3} value={(value as string) ?? ''}
                    onChange={e => onChange(e.target.value || undefined)} />
          {hint}
        </div>
      )
    case 'NUMBER':
      return (
        <div className="flex flex-col gap-1">
          <Input label={label} type="number"
                 min={field.config?.min} max={field.config?.max}
                 value={value === undefined ? '' : String(value)}
                 onChange={e => onChange(e.target.value === '' ? undefined : Number(e.target.value))} />
          {hint}
        </div>
      )
    case 'DATE':
      return (
        <div className="flex flex-col gap-1">
          <Input label={label} type="date" value={(value as string) ?? ''}
                 onChange={e => onChange(e.target.value || undefined)} />
          {hint}
        </div>
      )
    case 'CHECKBOX':
      return (
        <div className="flex flex-col gap-1">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
          <Checkbox checked={value === true} label={field.description || field.name}
                    onChange={e => onChange(e.target.checked)} />
        </div>
      )
    case 'SELECT':
      return (
        <div className="flex flex-col gap-1">
          <Select label={label} value={(value as string) ?? ''}
                  onChange={e => onChange(e.target.value || undefined)}>
            <option value="">—</option>
            {field.config?.options?.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
          </Select>
          {hint}
        </div>
      )
    case 'MULTI_SELECT': {
      const selected = Array.isArray(value) ? value : []
      const toggle = (id: string) => {
        const next = selected.includes(id) ? selected.filter(x => x !== id) : [...selected, id]
        onChange(next.length > 0 ? next : undefined)
      }
      return (
        <div className="flex flex-col gap-1">
          <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
          <div className="flex flex-wrap gap-1.5">
            {field.config?.options?.map(o => {
              const on = selected.includes(o.id)
              return (
                <button key={o.id} type="button" onClick={() => toggle(o.id)}
                        className="inline-flex items-center gap-1 text-xs rounded-full border px-2.5 py-1 cursor-pointer transition-colors"
                        style={{
                          borderColor: on ? (o.color ?? 'var(--color-brand)') : 'var(--color-border-2)',
                          color: on ? (o.color ?? 'var(--color-brand)') : 'var(--color-text-secondary)',
                          background: 'white',
                        }}>
                  {on && <Check size={11} />}
                  {o.label}
                </button>
              )
            })}
          </div>
          {hint}
        </div>
      )
    }
    case 'USER':
      return (
        <div className="flex flex-col gap-1">
          <Select label={label} value={(value as string) ?? ''}
                  onChange={e => onChange(e.target.value || undefined)}>
            <option value="">—</option>
            {members.map(m => <option key={m.userId} value={m.userId}>{m.displayName}</option>)}
          </Select>
          {hint}
        </div>
      )
  }
}

/** Read-only value, compact enough for the side panel grid and table cells. */
export function FieldValueDisplay({ field, value, members = [] }: {
  field: ProjectField
  value: FieldValue
  members?: WorkspaceMember[]
}) {
  switch (field.type) {
    case 'CHECKBOX':
      return <span className="text-sm">{value === true ? 'Yes' : 'No'}</span>
    case 'SELECT': {
      const opt = optionOf(field, value as string)
      return (
        <span className="inline-flex items-center gap-1.5 text-sm" style={{ color: opt?.color }}>
          {opt?.color && <span className="rounded-full" style={{ width: 8, height: 8, background: opt.color }} />}
          {opt?.label ?? String(value)}
        </span>
      )
    }
    case 'MULTI_SELECT':
      return (
        <span className="inline-flex flex-wrap gap-1">
          {(Array.isArray(value) ? value : []).map(id => {
            const opt = optionOf(field, id)
            return (
              <span key={id} className="text-xs rounded-full border px-2 py-0.5"
                    style={{ borderColor: 'var(--color-border-2)', color: opt?.color ?? 'var(--color-text-secondary)' }}>
                {opt?.label ?? id}
              </span>
            )
          })}
        </span>
      )
    case 'USER': {
      const member = members.find(m => m.userId === value)
      return <span className="text-sm">{member?.displayName ?? '(former member)'}</span>
    }
    case 'URL':
      return (
        <a href={String(value)} target="_blank" rel="noopener noreferrer"
           className="inline-flex items-center gap-1 text-sm hover:underline"
           style={{ color: 'var(--color-brand)' }}
           onClick={e => e.stopPropagation()}>
          <span className="truncate" style={{ maxWidth: 200 }}>{String(value)}</span>
          <ExternalLink size={11} className="flex-shrink-0" />
        </a>
      )
    case 'NUMBER':
      return <span className="mono text-sm">{String(value)}</span>
    case 'DATE':
      return <span className="mono text-sm">{String(value)}</span>
    default:
      return <span className="text-sm whitespace-pre-wrap">{String(value)}</span>
  }
}
