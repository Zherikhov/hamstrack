import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { rolesApi } from '../api'
import { Modal } from '../pages/admin/common'
import { Button, Input, Select, Textarea } from './ui'
import { Notice, ProjectRefList, classifyConflict, usageSummary } from './roles'
import type { Role, RoleUsage } from '../types'

/**
 * The two dialogs that create and destroy a role (HD-123 S6).
 *
 * **Duplication is the front door, not a workaround.** There is no
 * `POST /roles`, because the grant ceiling is a subset rule: a role assembled
 * from an empty checklist can assign nobody — not the contributor role, not
 * Commenter, not even Viewer-plus-anything — and finding that out as a 403 weeks
 * later is precisely what this model exists to prevent. So the copy here names
 * the template being copied and says what the copy starts with, rather than
 * apologising for not having a "create" button.
 */

export function DuplicateRoleDialog({ wsId, source, onClose, onCreated }: {
  wsId: string
  source: Role
  onClose: () => void
  onCreated: (created: Role) => void
}) {
  const [name, setName] = useState(`${source.name} copy`)
  const [description, setDescription] = useState(source.description ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [atCap, setAtCap] = useState(false)
  const [retryIn, setRetryIn] = useState<number | null>(null)

  async function submit() {
    setBusy(true)
    setError(null)
    setAtCap(false)
    setRetryIn(null)
    try {
      onCreated(await rolesApi.duplicate(wsId, source.id, {
        name: name.trim(),
        description: description.trim(),
      }))
    } catch (err) {
      const conflict = classifyConflict(err)
      setError(conflict.detail)
      // The cap and a lost lock race share this endpoint AND this status. Only
      // one of them is worth retrying, and the discriminator is what separates
      // them — never the wording.
      setAtCap(conflict.kind === 'roleLimit')
      setRetryIn(conflict.kind === 'retry' ? conflict.seconds : null)
      setBusy(false)
    }
  }

  return (
    <Modal title={`Duplicate “${source.name}”`} onClose={onClose} width={520}>
      <div className="flex flex-col gap-4">
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          The copy starts with everything <b>{source.name}</b> grants
          {source.permissions.length === 0
            ? ' — which is nothing at all, so this is the "from scratch" starting point.'
            : ` (${source.permissions.length} permission${source.permissions.length !== 1 ? 's' : ''})`}
          , and is a {source.scope === 'WORKSPACE' ? 'workspace' : 'project'} role like its template.
          Edit it afterwards; the template itself never changes.
        </p>
        <Input label="Name" value={name} maxLength={80} onChange={e => setName(e.target.value)} />
        <Textarea label="Description" rows={2} maxLength={500}
                  value={description} onChange={e => setDescription(e.target.value)} />
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          The name must be unique in this workspace and must not shadow a built-in one. A short key
          is generated from it for you.
        </p>
        {error && (
          <Notice tone="danger">
            <span>{error}</span>
            {atCap && (
              <span className="block mt-1 text-xs">
                Delete a role you no longer use, then try again — retrying now will fail identically.
              </span>
            )}
            {retryIn !== null && (
              <span className="block mt-1 text-xs">
                Someone else was changing roles at the same moment. Nothing was created — try again
                in {retryIn} second{retryIn !== 1 ? 's' : ''}.
              </span>
            )}
          </Notice>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={busy} disabled={!name.trim()} onClick={submit}>
            Create role
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/**
 * Delete a role, moving its holders somewhere first.
 *
 * The four refusals are rendered as four different situations, because they have
 * four different remedies and exactly one of them is the caller's to perform in
 * this dialog:
 *
 *  • **`ROLE_IN_USE`** — pick a replacement. The refusal carries the whole usage
 *    object, so the remap select is rendered straight off it.
 *  • **`SELF_HELD_ROLE`** — you hold it. No flag clears this: move yourself to
 *    another role first, or ask another administrator to run the delete.
 *  • **`LAST_PROJECT_ADMIN_BULK`** — the replacement would demote every
 *    administrator of the listed projects at once. There is deliberately no
 *    adoption retry here; `detail` names the constraint, so it is rendered
 *    rather than re-composed.
 *  • **A lost lock race** (`Retry-After`, no discriminator) — re-send unchanged.
 */
export function DeleteRoleDialog({ wsId, role, replacements, onClose, onDeleted }: {
  wsId: string
  role: Role
  /** Assignable roles of the SAME scope, excluding this one. */
  replacements: Role[]
  onClose: () => void
  onDeleted: () => void
}) {
  const [replaceWith, setReplaceWith] = useState('')
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<ReturnType<typeof classifyConflict> | null>(null)

  // Ask up front so the dialog opens already knowing what it is about to move —
  // "used by 4 members in 2 projects" is the difference between a confirmation
  // and a guess. A refusal later carries the same object and overrides this.
  const { data: fetched, isLoading } = useQuery({
    queryKey: ['role-usage', wsId, role.id],
    queryFn: () => rolesApi.usage(wsId, role.id),
  })
  const usage: RoleUsage | null =
    (refusal?.kind === 'roleInUse' ? refusal.usage : undefined) ?? fetched ?? role.usage ?? null
  const inUse = usage?.inUse ?? false

  async function submit() {
    setBusy(true)
    setRefusal(null)
    try {
      await rolesApi.remove(wsId, role.id, replaceWith || undefined)
      onDeleted()
    } catch (err) {
      setRefusal(classifyConflict(err))
      setBusy(false)
    }
  }

  const summary = usageSummary(usage)
  const blocked = refusal?.kind === 'selfHeld' || refusal?.kind === 'lastProjectAdminBulk'

  return (
    <Modal title={`Delete role “${role.name}”?`} onClose={onClose} width={520}>
      <div className="flex flex-col gap-4">
        <Notice tone="warning">
          {isLoading && !usage
            ? 'Checking where this role is in play…'
            : inUse
              ? <>This role is in use: <b>{summary}</b>. Everyone holding it moves to the replacement
                  you pick, in one step.</>
              : <>This role is not in use anywhere. Deleting it changes nobody’s access.</>}
        </Notice>

        {inUse && (
          <Select label="Move everyone holding it to"
                  value={replaceWith}
                  onChange={e => setReplaceWith(e.target.value)}>
            <option value="">Choose a role…</option>
            {replacements.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
          </Select>
        )}

        {refusal && (
          <Notice tone="danger">
            <span>{refusal.detail}</span>
            {refusal.kind === 'retry' && (
              <span className="text-xs">
                Nothing was changed. Try the same delete again in {refusal.seconds} second
                {refusal.seconds !== 1 ? 's' : ''}.
              </span>
            )}
            {refusal.kind === 'selfHeld' && (
              <span className="text-xs">
                Change your own role first, or ask another administrator to run this delete. There is
                no flag that clears it.
              </span>
            )}
            {refusal.kind === 'lastProjectAdminBulk' && (
              <div className="flex flex-col gap-1">
                <span className="text-xs">These projects would be left with no administrator:</span>
                <ProjectRefList projects={refusal.projects} />
              </div>
            )}
          </Notice>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={busy}
                  disabled={blocked || (inUse && !replaceWith)}
                  onClick={submit}>
            {inUse ? 'Delete & move holders' : 'Delete role'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
