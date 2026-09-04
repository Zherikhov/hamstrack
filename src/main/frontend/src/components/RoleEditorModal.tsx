import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { rolesApi } from '../api'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { usePermissionCatalog, catalogFor } from '../hooks/useRoles'
import { Modal } from '../pages/admin/common'
import { Button, Checkbox, Input, Textarea } from './ui'
import {
  AssignmentPanel, CAPABILITY_TAG, Chip, Notice, classifyConflict, groupPermissions, permissionCopy,
} from './roles'
import type { CapabilityHint, Role, RoleAssignmentView, RolePermissionEntry } from '../types'

/**
 * The **role editor** (HD-123 S6 · §14.2) — a name, a description, and the
 * catalog as a checklist grouped by area.
 *
 * ## Compose-time ceiling feedback is the point of this screen
 *
 * The grant ceiling is a *subset* rule: a role holding only
 * `project.member.manage` can assign nobody, because it is a superset of no
 * other role. Discovering that as a 403 six weeks later is the exact failure
 * this epic was built to delete, so the consequence is shown **while the boxes
 * are being ticked**: every keystroke's worth of change is dry-run through
 * `POST …/roles/preview`, which returns the identical `assignment` block a saved
 * role carries and runs the identical validation a real write runs.
 *
 * It is never re-derived here. The ceiling compares permission sets with
 * per-grant *width* (an unrestricted grant is not covered by an own-only one),
 * and a second implementation of a server predicate in the SPA is the HD-98 /
 * HD-116 bug class by construction — so `canAssign` / `cannotAssign` /
 * `warnings` are rendered exactly as received, `missing` included, which is the
 * same string the runtime 403 quotes.
 *
 * ## Capability hiding is UI, and only UI
 *
 * A permission carrying a delivery capability (`sprint.*` → BOARD,
 * `version.manage` → RELEASES) is hidden when **no project in this workspace
 * uses that capability** — a workspace with no Scrum project should not be
 * offered sprint permissions it has no use for.
 *
 * Three constraints on that, all load-bearing:
 *
 *  • **It never reaches a request.** The editor's state is the full grant map;
 *    hiding changes what is *drawn*, never what is *sent*. Enforcement does not
 *    consult a capability either — a project with `releases` off still accepts
 *    and returns version data — so a hidden permission that is already granted
 *    must survive a save untouched.
 *  • **A granted permission is never hidden.** Hiding a grant the role already
 *    holds would show an admin an incomplete role and invite them to save it, so
 *    the rule is "hide what is not in use *and* not held". The hidden ones are
 *    counted in a notice with a switch to reveal them.
 *  • **Not knowing is not "off".** Until the project list has loaded nothing is
 *    hidden. Guessing "unused" would pull rows out from under a pointer.
 */

/** Which capabilities at least one project in the workspace actually uses. */
export interface WorkspaceCapabilities {
  BOARD: boolean
  RELEASES: boolean
  /** False while the project list is still loading — nothing is hidden until it is true. */
  known: boolean
}

/** `issue.edit` + own-only, in the sorted wire-ish form the preview query is keyed by. */
function serialize(grants: Map<string, boolean>): string {
  return [...grants.entries()]
    .map(([key, ownOnly]) => (ownOnly ? `${key}:own` : key))
    .sort()
    .join(',')
}

function deserialize(serialized: string): RolePermissionEntry[] {
  if (!serialized) return []
  return serialized.split(',').map(token => token.endsWith(':own')
    ? { key: token.slice(0, -':own'.length), ownOnly: true }
    : { key: token, ownOnly: false })
}

export default function RoleEditorModal({ wsId, role, capabilities, onClose, onSaved }: {
  wsId: string
  role: Role
  capabilities: WorkspaceCapabilities
  onClose: () => void
  onSaved: (saved: Role) => void
}) {
  const [name, setName] = useState(role.name)
  const [description, setDescription] = useState(role.description ?? '')
  const [grants, setGrants] = useState<Map<string, boolean>>(
    () => new Map(role.permissions.map(p => [p.key, p.ownOnly])),
  )
  const [showHidden, setShowHidden] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  const { data: catalog, isLoading: catalogLoading } = usePermissionCatalog()
  const entries = useMemo(() => catalogFor(catalog, role.scope), [catalog, role.scope])
  const groups = useMemo(() => groupPermissions(entries, role.scope), [entries, role.scope])

  const serialized = serialize(grants)
  // The debounced value feeds the query KEY, so a slow answer for an older set
  // can never overwrite a newer one.
  const debounced = useDebouncedValue(serialized, 350)
  const preview = useQuery({
    queryKey: ['role-preview', wsId, role.scope, debounced],
    queryFn: () => rolesApi.preview(wsId, {
      scope: role.scope,
      permissions: deserialize(debounced),
    }),
    // A 422 here is a real answer about the set being composed, not a blip.
    retry: false,
  })
  const assignment: RoleAssignmentView | null = preview.data?.assignment
    // Until the first preview lands, show the block the role already carries
    // rather than nothing — it is the answer for the set as saved.
    ?? (preview.isLoading ? role.assignment : null)

  /** Hidden ⇔ belongs to a capability no project uses AND is not already granted. */
  function isHidden(key: string, capability: CapabilityHint | null): boolean {
    if (!capability || !capabilities.known || showHidden) return false
    if (grants.has(key)) return false
    return !capabilities[capability]
  }

  const hiddenCount = entries.filter(e => isHidden(e.key, e.capability)).length
  const hiddenCapabilities = [...new Set(
    entries.filter(e => isHidden(e.key, e.capability)).map(e => CAPABILITY_TAG[e.capability!] ?? e.capability!),
  )]

  function toggle(key: string, ownRequired: boolean) {
    setGrants(prev => {
      const next = new Map(prev)
      if (next.has(key)) next.delete(key)
      // `ownRequired` keys store own-only whatever is sent — the toggle is drawn
      // locked, and the value here matches what the server will store.
      else next.set(key, ownRequired)
      return next
    })
  }

  function setOwnOnly(key: string, ownOnly: boolean) {
    setGrants(prev => {
      const next = new Map(prev)
      if (next.has(key)) next.set(key, ownOnly)
      return next
    })
  }

  async function save() {
    setSaving(true)
    setError(null)
    setStale(false)
    try {
      const saved = await rolesApi.update(wsId, role.id, {
        name: name.trim(),
        description: description.trim(),
        // The FULL map, hidden rows included: hiding is presentation, and a
        // capability never narrows a grant.
        permissions: deserialize(serialize(grants)),
        // Optimistic concurrency: two admins each unticking one box would
        // otherwise silently restore each other's grant.
        version: role.version,
      })
      onSaved(saved)
    } catch (err) {
      const conflict = classifyConflict(err)
      setError(conflict.detail)
      // A stale `version` and a bulk-stranding refusal both need the role
      // re-loaded before another attempt makes sense.
      setStale(conflict.kind === 'plain' || conflict.kind === 'lastProjectAdminBulk')
      setSaving(false)
    }
  }

  return (
    <Modal title={`Edit role “${role.name}”`} onClose={onClose} width={860}>
      <div className="flex flex-col gap-4">
        <div className="flex gap-3">
          <div className="flex-1 min-w-0">
            <Input label="Name" value={name} maxLength={80} onChange={e => setName(e.target.value)} />
          </div>
          <div style={{ width: 150, flexShrink: 0 }} className="flex flex-col gap-1">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Scope</span>
            <div className="flex items-center gap-1.5" style={{ height: 38 }}>
              <Chip tone="brand">{role.scope === 'WORKSPACE' ? 'Workspace role' : 'Project role'}</Chip>
            </div>
          </div>
        </div>
        <Textarea label="Description" rows={2} maxLength={500}
                  value={description} onChange={e => setDescription(e.target.value)} />
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          A role’s scope is fixed when it is created. Duplicate a role of the other scope instead —
          changing it would turn every membership that already holds this role into a mis-scoped one.
        </p>

        <div className="flex gap-4 items-start">
          <div className="flex-1 min-w-0 flex flex-col gap-4">
            {catalogLoading && (
              <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading permissions…</span>
            )}
            {groups.map(group => {
              const visible = group.entries.filter(e => !isHidden(e.key, e.capability))
              if (visible.length === 0) return null
              return (
                <div key={group.id} className="flex flex-col gap-2">
                  <div className="text-xs font-semibold uppercase tracking-wider"
                       style={{ color: 'var(--color-text-muted)' }}>
                    {group.label}
                  </div>
                  {visible.map(entry => {
                    const checked = grants.has(entry.key)
                    const ownOnly = grants.get(entry.key) ?? entry.ownRequired
                    const copy = permissionCopy(entry.key)
                    return (
                      <div key={entry.key}
                           className="rounded-lg border px-3 py-2.5"
                           style={{ borderColor: 'var(--color-border)', background: 'white' }}>
                        <div className="flex items-start justify-between gap-3">
                          <Checkbox
                            checked={checked}
                            onChange={() => toggle(entry.key, entry.ownRequired)}
                            label={
                              <span className="flex flex-col gap-0.5">
                                <span className="flex items-center gap-2">
                                  <span className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>
                                    {copy.title}
                                  </span>
                                  {entry.capability && (
                                    <Chip title="A hint about which way of working this permission is about — never a check.">
                                      {CAPABILITY_TAG[entry.capability] ?? entry.capability}
                                    </Chip>
                                  )}
                                </span>
                                <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                  {copy.blurb}
                                </span>
                                <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                  {entry.key}
                                </span>
                              </span>
                            }
                          />
                          {checked && entry.supportsOwn && (
                            <div className="flex-shrink-0">
                              <Checkbox
                                checked={ownOnly}
                                disabled={entry.ownRequired}
                                onChange={e => setOwnOnly(entry.key, e.target.checked)}
                                label={
                                  <span className="text-xs whitespace-nowrap"
                                        title={entry.ownRequired
                                          ? 'This one can only ever be own-only.'
                                          : 'Narrow this grant to objects this person owns.'}>
                                    Own only{entry.ownRequired ? ' (always)' : ''}
                                  </span>
                                }
                              />
                            </div>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>
              )
            })}

            {hiddenCount > 0 && (
              <button type="button" onClick={() => setShowHidden(true)}
                      className="text-xs text-left inline-flex items-start gap-1.5 cursor-pointer rounded-lg px-3 py-2"
                      style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-secondary)',
                               border: '1px solid var(--color-border)' }}>
                <Info size={13} style={{ marginTop: 1, flexShrink: 0 }} />
                <span>
                  {hiddenCount} permission{hiddenCount !== 1 ? 's are' : ' is'} hidden because no project
                  in this workspace uses {hiddenCapabilities.join(' or ')}.{' '}
                  <b>Show {hiddenCount !== 1 ? 'them' : 'it'}</b> — they still work wherever a project
                  turns the capability on.
                </span>
              </button>
            )}
            {showHidden && (
              <button type="button" onClick={() => setShowHidden(false)}
                      className="text-xs text-left cursor-pointer"
                      style={{ color: 'var(--color-brand-ink)', background: 'none', border: 'none', padding: 0 }}>
                Hide permissions for ways of working this workspace does not use
              </button>
            )}
          </div>

          <div style={{ width: 300, flexShrink: 0, position: 'sticky', top: 0 }}>
            <AssignmentPanel assignment={assignment} pending={preview.isFetching} />
            {preview.error && (
              <p className="text-xs mt-2" style={{ color: 'var(--color-error-ink)' }}>
                {preview.error instanceof Error ? preview.error.message : 'Could not check this set.'}
              </p>
            )}
          </div>
        </div>

        {error && (
          <Notice tone="danger">
            <span>{error}</span>
            {stale && (
              <span className="block mt-1 text-xs">
                Close and reopen the role to load the current version before trying again.
              </span>
            )}
          </Notice>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={saving} disabled={!name.trim()} onClick={save}>
            Save role
          </Button>
        </div>
      </div>
    </Modal>
  )
}
