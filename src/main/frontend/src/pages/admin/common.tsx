import { useState } from 'react'
import type { ReactNode } from 'react'
import type { UsageDetail, UsageInfo } from '../../types'
import { Button, Checkbox, Select } from '../../components/ui'

// Shared building blocks of the admin console (see DESIGN.md: data-dense
// tables, usage-first chips, warm neutrals)

export function PageHeader({ title, subtitle, action }: { title: string; subtitle: string; action?: ReactNode }) {
  return (
    <div className="flex items-start justify-between mb-5">
      <div>
        <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Administration</div>
        <h1 className="font-display font-bold" style={{ fontSize: 24, letterSpacing: '-0.4px' }}>{title}</h1>
        {/* Inline maxWidth: our @theme --spacing-* scale shadows Tailwind's
            max-w-{xs..3xl} sizes (max-w-xl would be 32px!) — see CLAUDE.md */}
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 560 }}>{subtitle}</p>
      </div>
      {action}
    </div>
  )
}

export function AdminTable({ headers, children }: { headers: string[]; children: ReactNode }) {
  return (
    <table className="w-full border-collapse rounded-lg overflow-hidden"
           style={{ background: 'white', border: '1px solid var(--color-border)' }}>
      <thead>
        <tr>
          {headers.map(h => (
            <th key={h}
                className="text-left px-3 py-2 text-xs font-semibold uppercase tracking-wider"
                style={{ color: 'var(--color-text-muted)', background: 'var(--color-surface-2)',
                         borderBottom: '1px solid var(--color-border)' }}>
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>{children}</tbody>
    </table>
  )
}

/**
 * Usage-first UX: nothing in the catalog is edited blind. With `fetchDetail`
 * the chip becomes clickable and expands into a "where exactly?" popover
 * (names of workflows/sets and the projects reached through them).
 */
export function UsageChip({ usage, fetchDetail }: {
  usage: UsageInfo
  fetchDetail?: () => Promise<UsageDetail>
}) {
  const [open, setOpen] = useState(false)
  const [detail, setDetail] = useState<UsageDetail | null>(null)
  const [failed, setFailed] = useState(false)

  const parts: string[] = []
  if (usage.workflows > 0) parts.push(`${usage.workflows} workflow${usage.workflows !== 1 ? 's' : ''}`)
  if (usage.sets > 0) parts.push(`${usage.sets} set${usage.sets !== 1 ? 's' : ''}`)
  if (usage.projects > 0) parts.push(`${usage.projects} project${usage.projects !== 1 ? 's' : ''}`)
  if (usage.issues > 0) parts.push(`${usage.issues} issue${usage.issues !== 1 ? 's' : ''}`)
  const used = parts.length > 0
  const clickable = used && !!fetchDetail

  const chipStyle: React.CSSProperties = used
    ? { color: 'var(--color-brand)', background: '#E7F0EE' }
    : { color: 'var(--color-text-muted)', background: 'var(--color-surface-2)' }

  function toggleOpen() {
    if (!clickable) return
    if (!open && detail === null && !failed) {
      fetchDetail!().then(setDetail).catch(() => setFailed(true))
    }
    setOpen(!open)
  }

  return (
    <span className="relative inline-block">
      <button type="button" onClick={toggleOpen}
              className={`text-xs px-2.5 py-0.5 rounded-full whitespace-nowrap ${clickable ? 'cursor-pointer hover:opacity-80' : 'cursor-default'}`}
              style={chipStyle}>
        {used ? parts.join(' · ') : 'not used'}
      </button>
      {open && (
        <>
          <span style={{ position: 'fixed', inset: 0, zIndex: 29 }} onClick={() => setOpen(false)} />
          <span className="absolute rounded-lg border p-3 text-left"
                style={{ zIndex: 30, top: '100%', right: 0, marginTop: 4, minWidth: 240,
                         background: 'white', borderColor: 'var(--color-border)',
                         boxShadow: '0 8px 30px rgba(0,0,0,0.12)', display: 'block' }}>
            {failed && <span className="text-xs" style={{ color: 'var(--color-error)' }}>Failed to load usage</span>}
            {!failed && detail === null && (
              <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
            )}
            {detail && <UsageDetailBody detail={detail} />}
          </span>
        </>
      )}
    </span>
  )
}

function UsageDetailBody({ detail }: { detail: UsageDetail }) {
  const section = (title: string, items: ReactNode[]) => items.length > 0 && (
    <span className="block mb-2 last:mb-0">
      <span className="block text-xs font-semibold uppercase tracking-wider mb-1"
            style={{ color: 'var(--color-text-muted)' }}>{title}</span>
      {items}
    </span>
  )
  return (
    <>
      {section('Workflows', detail.workflows.map(w =>
        <span key={w} className="block text-xs py-0.5">{w}</span>))}
      {section('Sets', detail.sets.map(s =>
        <span key={s} className="block text-xs py-0.5">{s}</span>))}
      {section('Projects', detail.projects.map(p =>
        <span key={p.id} className="block text-xs py-0.5">
          <span className="mono mr-1.5" style={{ color: 'var(--color-text-muted)' }}>{p.key}</span>{p.name}
        </span>))}
      <span className="block text-xs pt-1 border-t mt-1" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
        <span className="mono">{detail.issues}</span> issue{detail.issues !== 1 ? 's' : ''} reference it
      </span>
    </>
  )
}

/**
 * Impact preview inside set/workflow editors: a shared entry is never edited
 * without seeing how far the change reaches. Rendered only when editing an
 * existing entry that projects actually use.
 */
export function ImpactBanner({ projectsUsing, entity }: { projectsUsing: number; entity: string }) {
  if (projectsUsing === 0) return null
  return (
    <div className="text-xs rounded-lg px-3 py-2"
         style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
      This {entity} is used by <b>{projectsUsing} project{projectsUsing !== 1 ? 's' : ''}</b> — changes apply to all of them immediately.
    </div>
  )
}

/** "Show archived (N)" filter for catalog tables; hidden while nothing is archived. */
export function ArchivedToggle({ archivedCount, value, onChange }: {
  archivedCount: number; value: boolean; onChange: (v: boolean) => void
}) {
  if (archivedCount === 0) return null
  return (
    <div className="flex justify-end mb-2">
      <Checkbox checked={value} onChange={e => onChange(e.target.checked)}
                label={<span className="text-xs">Show archived ({archivedCount})</span>} />
    </div>
  )
}

export function ArchivedBadge() {
  return (
    <span className="text-xs px-2 py-0.5 rounded-full"
          style={{ color: 'var(--color-warning)', background: '#FBF3E8' }}>
      archived
    </span>
  )
}

export function Modal({ title, onClose, children, width = 440 }: {
  title: string; onClose: () => void; children: ReactNode; width?: number
}) {
  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 50, background: 'rgba(28,27,25,0.55)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', backdropFilter: 'blur(2px)' }}
         onClick={onClose}>
      <div style={{ background: 'white', borderRadius: 'var(--radius-lg)', width,
                    border: '1px solid var(--color-border)', boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
                    maxHeight: '85vh', overflowY: 'auto' }}
           onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b"
             style={{ borderColor: 'var(--color-border)' }}>
          <span className="font-semibold text-sm">{title}</span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 text-sm"
                  style={{ color: 'var(--color-text-muted)' }}>✕</button>
        </div>
        <div className="p-5">{children}</div>
      </div>
    </div>
  )
}

/**
 * Impact-preview delete: shows what the deletion touches and, when issues
 * reference the entry, requires choosing a replacement (remap) — or offers
 * archiving instead. Everything Jira hides behind separate screens, in one
 * dialog.
 */
export function DeleteDialog({ entity, name, usage, replacements, onDelete, onArchive, onClose, error }: {
  entity: string
  name: string
  usage: UsageInfo
  replacements: { id: string; name: string }[]
  onDelete: (replaceWithId?: string) => void
  onArchive?: () => void
  onClose: () => void
  error?: string
}) {
  const needsRemap = usage.issues > 0
  return (
    <Modal title={`Delete ${entity} “${name}”?`} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <div className="text-sm rounded-lg px-3 py-2.5"
             style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
          <UsageText usage={usage} />
        </div>
        {needsRemap && (
          <ReplacementSelect replacements={replacements} onConfirm={onDelete} error={error} onArchive={onArchive} onClose={onClose} />
        )}
        {!needsRemap && (
          <>
            {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={onClose}>Cancel</Button>
              {onArchive && <Button variant="secondary" onClick={onArchive}>Archive instead</Button>}
              <Button variant="danger" onClick={() => onDelete()}>Delete</Button>
            </div>
          </>
        )}
      </div>
    </Modal>
  )
}

function UsageText({ usage }: { usage: UsageInfo }) {
  const bits: string[] = []
  if (usage.workflows > 0) bits.push(`${usage.workflows} workflow(s)`)
  if (usage.sets > 0) bits.push(`${usage.sets} priority set(s)`)
  if (usage.projects > 0) bits.push(`${usage.projects} project(s)`)
  if (bits.length === 0 && usage.issues === 0) return <>Not used anywhere — safe to delete.</>
  return (
    <>
      {bits.length > 0 && <>Used in <b>{bits.join(', ')}</b>.<br /></>}
      {usage.issues > 0 && <><span className="mono">{usage.issues}</span> issue(s) currently reference it and must be remapped.</>}
    </>
  )
}

function ReplacementSelect({ replacements, onConfirm, onArchive, onClose, error }: {
  replacements: { id: string; name: string }[]
  onConfirm: (replaceWithId: string) => void
  onArchive?: () => void
  onClose: () => void
  error?: string
}) {
  const [replaceWith, setReplaceWith] = useState(replacements[0]?.id ?? '')
  return (
    <div className="flex flex-col gap-3">
      <Select label="Move affected issues to" value={replaceWith} onChange={e => setReplaceWith(e.target.value)}>
        {replacements.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
      </Select>
      {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        {onArchive && <Button variant="secondary" onClick={onArchive}>Archive instead</Button>}
        <Button variant="danger" disabled={!replaceWith} onClick={() => onConfirm(replaceWith)}>
          Delete &amp; remap
        </Button>
      </div>
    </div>
  )
}
