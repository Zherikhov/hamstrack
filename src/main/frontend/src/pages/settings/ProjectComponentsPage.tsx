import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { ApiResponseError, apiListWorkspaceMembers, componentsApi } from '../../api'
import type { Component, WorkspaceMember } from '../../types'
import { Avatar, Button, Checkbox, Input, Select, Textarea } from '../../components/ui'
import { componentsKey } from '../../components/projectComponents'
import { ISSUES_KEY_ROOT } from '../../lib/queryKeys'
import { AdminTable, ArchivedBadge, ArchivedToggle, Modal, PageHeader, UsageChip } from '../admin/common'

/**
 * Project settings → Components (HD-31). The curation surface for one project's
 * modules ("Billing", "iOS app"): rename, describe, set a lead, flip auto-assign,
 * archive and delete.
 *
 * Access: `ProjectSettingsArea` lets a project MANAGER *or* a workspace
 * OWNER/ADMIN in (mirroring the server's `requireProjectCurator`), and the server
 * independently enforces the role on every mutating endpoint — this page adds no
 * guard of its own. While the project is archived every write 409s; the message
 * is surfaced inline rather than pre-disabling the controls.
 *
 * Its list deliberately uses its OWN query key (archived rows + usage counts) so
 * the pickers' lean `componentsKey(wsId, projectId)` entry stays lean; both live
 * under the same prefix, so one invalidation refreshes both.
 */
export default function ProjectComponentsPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const qc = useQueryClient()
  const [showArchived, setShowArchived] = useState(false)
  const [editing, setEditing] = useState<Component | 'new' | null>(null)
  const [deleting, setDeleting] = useState<Component | null>(null)
  const [error, setError] = useState('')

  const curationKey = [...componentsKey(wsId, projectId), 'curation'] as const
  const { data: components = [], isLoading } = useQuery({
    queryKey: curationKey,
    queryFn: () => componentsApi.list(wsId!, projectId!, { includeArchived: true, withUsage: true }),
    enabled: !!wsId && !!projectId,
  })

  // The lead may have left the workspace: the row survives, but auto-assign
  // silently skips them — so the table says so instead of quietly lying.
  const { data: members = [] } = useQuery({
    queryKey: ['wsMembers', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId,
  })
  const memberIds = new Set(members.map(m => m.userId))

  /** Components changed → the pickers' list AND every issue list that renders one. */
  function invalidate() {
    qc.invalidateQueries({ queryKey: componentsKey(wsId, projectId) })
    qc.invalidateQueries({ queryKey: [ISSUES_KEY_ROOT] })
    qc.invalidateQueries({ queryKey: ['issue'] })
  }

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? componentsApi.unarchive(wsId!, projectId!, id) : componentsApi.archive(wsId!, projectId!, id),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Failed'),
  })

  const toggleAutoAssign = useMutation({
    mutationFn: (c: Component) =>
      componentsApi.update(wsId!, projectId!, c.id, { autoAssign: !c.autoAssign }),
    onSuccess: () => { setError(''); invalidate() },
    // 422 when switching it on without a lead — the server message says so.
    onError: e => setError(e instanceof Error ? e.message : 'Failed'),
  })

  const remove = useMutation({
    mutationFn: ({ id, force }: { id: string; force: boolean }) =>
      componentsApi.remove(wsId!, projectId!, id, force),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(e instanceof Error ? e.message : 'Delete failed'),
  })

  const visible = showArchived ? components : components.filter(c => !c.archived)
  const archivedCount = components.filter(c => c.archived).length

  return (
    <>
      <PageHeader
        title="Components"
        subtitle="The parts of this project — a module, a service, an app. One per issue, with an optional lead and an optional “assign new issues to the lead” switch. Archiving keeps a component on the issues that carry it and only hides it from the pickers."
        action={<Button variant="primary" onClick={() => { setError(''); setEditing('new') }}>+ New component</Button>}
      />
      {/* Cross-link (HD-32): versions are working data with a lifecycle, so they
          are curated on the Releases page rather than here. Absolute path — a
          relative one inside this splat route would resolve after the splat. */}
      <p className="text-xs mb-3" style={{ color: 'var(--color-text-muted)' }}>
        Release versions live on the{' '}
        <Link to={`/w/${wsId}/p/${projectId}/releases`} className="hover:underline"
              style={{ color: 'var(--color-brand-ink)' }}>
          Releases page
        </Link>
        , not here — they carry a lifecycle (unreleased → released) rather than being configuration.
      </p>

      <ArchivedToggle archivedCount={archivedCount} value={showArchived} onChange={setShowArchived} />

      {error && !editing && !deleting && (
        <p className="text-xs mb-2" style={{ color: 'var(--color-error-ink)' }}>{error}</p>
      )}

      <AdminTable headers={['Component', 'Description', 'Lead', 'Auto-assign', 'Used on', '']}>
        {visible.map(c => (
          <tr key={c.id} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <td className="px-3 py-2.5">
              <span className="inline-flex items-center gap-2">
                <span className="text-sm font-medium" style={{ opacity: c.archived ? 0.6 : 1 }}>{c.name}</span>
                {c.archived && <ArchivedBadge />}
              </span>
            </td>
            <td className="px-3 py-2.5" style={{ maxWidth: 260 }}>
              <span className="text-sm truncate block" style={{ color: 'var(--color-text-secondary)' }}>
                {c.description || '—'}
              </span>
            </td>
            <td className="px-3 py-2.5">
              {c.leadId ? (
                <span className="inline-flex items-center gap-1.5">
                  <Avatar name={c.leadName ?? '?'} avatarUrl={c.leadAvatarUrl} size={20} />
                  <span className="text-sm truncate" style={{ maxWidth: 140 }}>{c.leadName}</span>
                  {!memberIds.has(c.leadId) && members.length > 0 && (
                    <span className="text-xs whitespace-nowrap" style={{ color: 'var(--color-warning-ink)' }}
                          title="Auto-assign skips a lead who is no longer a workspace member">
                      no longer a member
                    </span>
                  )}
                </span>
              ) : (
                <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>—</span>
              )}
            </td>
            <td className="px-3 py-2.5">
              <button
                type="button"
                aria-pressed={c.autoAssign}
                aria-label={`Auto-assign for ${c.name}`}
                disabled={!c.leadId || toggleAutoAssign.isPending}
                title={c.leadId
                  ? 'New issues with no assignee go to the lead'
                  : 'Set a lead first — auto-assign needs someone to assign to'}
                onClick={() => toggleAutoAssign.mutate(c)}
                className="text-xs px-2.5 py-0.5 rounded-full transition-colors"
                style={{
                  cursor: c.leadId ? 'pointer' : 'not-allowed',
                  color: c.autoAssign ? 'var(--color-brand-ink)' : 'var(--color-text-muted)',
                  background: c.autoAssign ? '#E7F0EE' : 'var(--color-surface-2)',
                  opacity: c.leadId ? 1 : 0.6,
                }}
              >
                {c.autoAssign ? 'on' : 'off'}
              </button>
            </td>
            <td className="px-3 py-2.5">
              <UsageChip usage={{ workflows: 0, sets: 0, projects: 0, issues: c.issueCount ?? 0 }} />
            </td>
            <td className="px-3 py-2.5 text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={() => { setError(''); setEditing(c) }}>Edit</Button>
              <Button variant="ghost" size="sm"
                      onClick={() => archive.mutate({ id: c.id, archived: c.archived })}>
                {c.archived ? 'Unarchive' : 'Archive'}
              </Button>
              <Button variant="ghost" size="sm" style={{ color: 'var(--color-error-ink)' }}
                      onClick={() => { setError(''); setDeleting(c) }}>
                Delete
              </Button>
            </td>
          </tr>
        ))}
        {visible.length === 0 && (
          <tr>
            <td colSpan={6} className="px-3 py-6 text-center text-sm" style={{ color: 'var(--color-text-muted)' }}>
              {isLoading ? 'loading…' : 'No components yet — add the parts this project is built from.'}
            </td>
          </tr>
        )}
      </AdminTable>

      {editing && (
        <ComponentForm
          wsId={wsId!}
          projectId={projectId!}
          component={editing === 'new' ? null : editing}
          members={members}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); invalidate() }}
        />
      )}

      {deleting && (
        <Modal title={`Delete component “${deleting.name}”?`} onClose={() => setDeleting(null)}>
          <div className="flex flex-col gap-4">
            <div className="text-sm rounded-lg px-3 py-2.5"
                 style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
              {(deleting.issueCount ?? 0) > 0 ? (
                <>
                  Used on <span className="mono">{deleting.issueCount}</span> issue
                  {deleting.issueCount === 1 ? '' : 's'} — deleting clears it on all of them.
                  Archiving keeps the component on those issues and only hides it from the pickers.
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

/**
 * Create / edit one component: name, description, lead and auto-assign.
 *
 * Auto-assign without a lead is a 422 server-side ("a switch with nothing to
 * switch to"), so the checkbox is disabled — and un-ticked — while no lead is
 * selected, instead of letting the user submit a payload that can't succeed.
 */
function ComponentForm({ wsId, projectId, component, members, onClose, onSaved }: {
  wsId: string
  projectId: string
  component: Component | null
  members: WorkspaceMember[]
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(component?.name ?? '')
  const [description, setDescription] = useState(component?.description ?? '')
  const [leadId, setLeadId] = useState(component?.leadId ?? '')
  const [autoAssign, setAutoAssign] = useState(component?.autoAssign ?? false)
  const [error, setError] = useState('')

  const effectiveAutoAssign = !!leadId && autoAssign
  // A lead who has left the workspace stays selectable so editing the name
  // doesn't silently drop them (the same handling as a departed assignee).
  const staleLead = component?.leadId && !members.some(m => m.userId === component.leadId)
    ? { id: component.leadId, name: component.leadName ?? 'Former member' }
    : null

  const save = useMutation({
    mutationFn: () => {
      const base = { name: name.trim(), description: description.trim(), autoAssign: effectiveAutoAssign }
      return component
        // Partial PATCH: `clearLead` is the only way to unset a nullable scalar
        // (a plain null can't be told apart from "not sent" server-side).
        ? componentsApi.update(wsId, projectId, component.id,
            leadId ? { ...base, leadId } : { ...base, clearLead: true })
        : componentsApi.create(wsId, projectId, leadId ? { ...base, leadId } : base)
    },
    onSuccess: onSaved,
    onError: (e: unknown) => {
      // 409 duplicate name (including a name held by an ARCHIVED component, and
      // any write while the project is archived) · 422 unknown lead.
      setError(e instanceof ApiResponseError ? e.detail : (e instanceof Error ? e.message : 'Save failed'))
    },
  })

  return (
    <Modal title={component ? `Edit component “${component.name}”` : 'New component'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} maxLength={80} autoFocus />

        <Textarea label="Description" rows={2} value={description} maxLength={500}
                  onChange={e => setDescription(e.target.value)} />

        <Select label="Lead" value={leadId} onChange={e => setLeadId(e.target.value)}>
          <option value="">No lead</option>
          {members.map(m => <option key={m.userId} value={m.userId}>{m.displayName}</option>)}
          {staleLead && <option value={staleLead.id}>{staleLead.name} (no longer a member)</option>}
        </Select>

        <Checkbox
          checked={effectiveAutoAssign}
          disabled={!leadId}
          onChange={e => setAutoAssign(e.target.checked)}
          label={
            <span className="text-sm">
              Assign new issues to the lead
              <span className="block text-xs" style={{ color: 'var(--color-text-muted)' }}>
                {leadId
                  ? 'Only when the issue is created without an assignee. Changing a component later never reassigns.'
                  : 'Pick a lead first.'}
              </span>
            </span>
          }
        />

        {error && <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            {component ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
