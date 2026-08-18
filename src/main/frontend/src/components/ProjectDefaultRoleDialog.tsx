import { useEffect, useState } from 'react'
import { projectDefaultRoleApi } from '../api'
import { useProjectDefaultRole } from '../hooks/useRoles'
import { Modal } from '../pages/admin/common'
import { Button } from './ui'
import {
  CeilingSummary, ConflictNotice, DefaultRoleSelect, Notice, classifyConflict, resolveDefaultRole,
} from './roles'
import type { ConflictKind } from './roles'
import type { Role } from '../types'

/**
 * **A project's default access, as two distinct choices** (HD-123 S7 · HD-130 §9.2).
 *
 * ## Why two radios and not one dropdown with a blank option
 *
 * The stored value is nullable and `null` means *"follow the workspace default"* —
 * a real, deliberate configuration, not an absence. "This project deliberately
 * follows the workspace" and "this project happens to name the same role the
 * workspace does" are different states that diverge the instant the workspace
 * default moves, and a single dropdown in which `null` is an unlabelled entry
 * cannot express the difference. S6 already shipped the seam that tells them
 * apart — {@link resolveDefaultRole} reports **which link of the chain supplied
 * the value** — so the dialog opens on the choice the project actually made.
 *
 * ## The ceiling is rendered, not discovered
 *
 * `settable` is derived server-side with the same predicate the runtime 403
 * applies, so a role this actor may not make the default is drawn **disabled with
 * the permission they lack** rather than offered and refused on save. It is never
 * re-derived in TypeScript.
 *
 * **The two scopes are not symmetric.** This block is the *project* ceiling — the
 * actor's real effective set in this project, with nobody exempt and no escape
 * hatch: the same person who may promote a colleague to Project admin is refused
 * when they aim that role at the default, because a default's target is everyone
 * including themselves. A role settable on the workspace General page may be
 * refused here and vice versa, so neither block is ever reused for the other
 * picker.
 *
 * ## And it can still refuse
 *
 * Narrowing a default that was the only thing granting anybody `project.member.manage`
 * here answers **409 `STRANDED_BY_INHERITANCE`**, with no adoption path —
 * adopting would *narrow the adopter*, since they hold the wide default and no
 * row. That refusal renders as itself.
 */
export default function ProjectDefaultRoleDialog({
  wsId, projectId, projectName, roles, onClose, onSaved,
}: {
  wsId: string
  projectId: string
  projectName: string
  /** PROJECT-scoped roles of this workspace, already sorted for display. */
  roles: Role[]
  onClose: () => void
  onSaved: () => void | Promise<void>
}) {
  const { data, isLoading } = useProjectDefaultRole(wsId, projectId)
  const [choice, setChoice] = useState<'inherit' | 'override' | null>(null)
  const [roleId, setRoleId] = useState<string>('')
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<ConflictKind | null>(null)

  // Open on what the project actually decided, once the read lands. The origin
  // is the whole reason both links ride the response: an override that happens
  // to name the workspace's role is still an override.
  useEffect(() => {
    if (!data) return
    setChoice(data.projectRoleId ? 'override' : 'inherit')
    setRoleId(data.projectRoleId ?? '')
  }, [data])

  // What "inherit" would actually mean here — the workspace link of the chain,
  // resolved against the same roles list the picker renders.
  const inherited = resolveDefaultRole(roles, { projectRoleId: null, workspaceRoleId: data?.workspaceRoleId ?? null })
  const inheritedName = inherited.unresolvable
    ? 'a role this workspace cannot describe'
    : (inherited.role?.name ?? 'the built-in contributor role')

  const current = data?.projectRoleId ?? null
  const dirty = choice === 'inherit' ? current !== null : (roleId !== '' && roleId !== current)
  const restricted = data?.mode === 'STRICT'

  async function save() {
    if (!choice) return
    setBusy(true)
    setRefusal(null)
    try {
      await projectDefaultRoleApi.set(wsId, projectId,
        choice === 'inherit' ? { inherit: true } : { roleId })
      await onSaved()
      onClose()
    } catch (err) {
      setRefusal(classifyConflict(err))
      setBusy(false)
    }
  }

  return (
    <Modal title="Default access" onClose={onClose} width={560}>
      <div className="flex flex-col gap-4">
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          What everyone in the workspace gets in <b><bdi>{projectName}</bdi></b> without being added
          to it. Anyone added explicitly keeps their own role instead — an explicit membership always
          wins over this.
        </p>

        {isLoading && (
          <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
        )}

        {data && (
          <>
            {restricted && (
              <Notice tone="info">
                <span className="text-sm">
                  Project access is <b>Restricted</b> for this workspace, so nobody is working here
                  through the default right now — only the people listed on the People screen can
                  change anything. What you set here applies again if project access is switched
                  back to Open.
                </span>
              </Notice>
            )}

            <div className="flex flex-col gap-2">
              <Choice
                name="project-default-role"
                checked={choice === 'inherit'}
                disabled={busy}
                onSelect={() => setChoice('inherit')}
                title={`Inherit the workspace default (${inheritedName})`}
                blurb="Follow whatever the workspace sets. If the workspace default changes later, this project follows it."
              />
              <Choice
                name="project-default-role"
                checked={choice === 'override'}
                disabled={busy}
                onSelect={() => setChoice('override')}
                title="Use a different role in this project"
                blurb="Pin a role here regardless of the workspace default. Choose Viewer to make this project read-only for everyone who was not added to it."
              >
                <div className="mt-2" style={{ maxWidth: 320 }}>
                  <DefaultRoleSelect
                    roles={roles}
                    settable={data.settable}
                    value={roleId}
                    ariaLabel="Default role for this project"
                    placeholder="Choose a role…"
                    disabled={busy || choice !== 'override'}
                    onChange={setRoleId} />
                  <CeilingSummary settable={data.settable} />
                </div>
              </Choice>
            </div>
          </>
        )}

        {refusal && <ConflictNotice refusal={refusal} />}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={busy} disabled={!dirty} onClick={save}>Save</Button>
        </div>
      </div>
    </Modal>
  )
}

function Choice({ name, checked, disabled, onSelect, title, blurb, children }: {
  name: string
  checked: boolean
  disabled?: boolean
  onSelect: () => void
  title: string
  blurb: string
  children?: React.ReactNode
}) {
  return (
    <label className="flex items-start gap-3 rounded-lg border p-3 cursor-pointer"
           style={{
             borderColor: checked ? 'var(--color-brand)' : 'var(--color-border)',
             background: checked ? 'color-mix(in srgb, var(--color-brand) 6%, white)' : 'var(--color-card)',
           }}>
      <input type="radio" name={name} checked={checked} disabled={disabled} onChange={onSelect}
             style={{ marginTop: 3, accentColor: 'var(--color-brand)' }} />
      <span className="flex-1 min-w-0">
        <span className="text-sm font-semibold block" style={{ color: 'var(--color-text)' }}>{title}</span>
        <span className="text-sm block mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>{blurb}</span>
        {children}
      </span>
    </label>
  )
}
