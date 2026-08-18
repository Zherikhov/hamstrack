import { useState } from 'react'
import { ShieldAlert } from 'lucide-react'
import { apiRemoveWorkspaceMember } from '../api'
import { Modal } from '../pages/admin/common'
import { Button } from './ui'
import { Notice, ProjectRefList, classifyConflict } from './roles'
import type { ConflictKind } from './roles'
import type { ProjectRef, WorkspaceMember } from '../types'

/**
 * Remove someone from a **workspace** (HD-132 / HD-136), and the adoption
 * consent that a refusal can lead to (HD-123 S6).
 *
 * ## The refusals, in the one order they may be read
 *
 * 1. **`Retry-After` present** — a lost row-lock race. Nothing happened; re-send
 *    the *identical* request. It carries **no `errorType`**, which is exactly why
 *    the header is checked first: an absent discriminator otherwise reads as "no
 *    recovery I know about", the one wrong answer for a body that is only asking
 *    to be retried.
 * 2. **`STRANDED_PROJECTS`** — the removal would leave the listed projects with
 *    nobody able to manage who works in them. The retry with
 *    `adoptStrandedProjects=true` clears it.
 * 3. **`ADOPTION_BLOCKED`** — that retry has already been made and cannot work
 *    (taking those projects over would demote the rescuer, and the ceiling would
 *    then refuse to give the missing rights back). **No adopt button**: the flag
 *    is already set and setting it again produces this same refusal.
 * 4. **`ADOPTION_ROLE_UNREADABLE`** — the adoption stopped on a membership row
 *    whose stored role cannot be read. Neither party can clear it; it needs an
 *    operator, so no action is offered at all.
 * 5. **`STRANDED_BY_INHERITANCE`** (HD-130 S7, door 6) — the same shape as (2)
 *    with the opposite remedy: the projects have administrators only *through
 *    the default access*, so there is **no adoption retry** and no button. It is
 *    classified separately from `STRANDED_PROJECTS` for exactly that reason —
 *    treating it as its cousin would offer a "take them over" that cannot work
 *    and would narrow the rescuer if it did.
 * 6. **Neither** — the last-Owner invariant (or the self-removal `422`). Render
 *    `detail`; an unrecognised `errorType` is treated the same way.
 *
 * ## The adoption is consent, not a convenience
 *
 * The second call is not "OK to continue?" — it is *"removing this person leaves
 * these projects unmanageable; do you want to take them over?"*. So the dialog
 * names the projects, says what the caller is taking on (a durable role in
 * somebody else's project, not a formality), and says plainly that the flag is a
 * decision the server re-evaluates: the set adopted is recomputed inside the
 * transaction, so it is exactly what is about to be stranded *now* — a project
 * that gains another administrator in the meantime is silently left alone.
 *
 * ## The 200 body is surfaced, never swallowed
 *
 * A removal that granted nothing answers 204; one that granted the caller a role
 * answers **200 with `adoptedProjects`**. That body exists specifically so a
 * durable grant is visible to the person who caused it — a client that assumed
 * 204 would accumulate project roles for its user with nothing on screen ever
 * saying so. The dialog therefore ends on an acknowledgement step listing what
 * was taken over, rather than closing silently.
 */

type Stage =
  | { at: 'confirm' }
  /** A refusal that has to be read before anything else can happen. */
  | { at: 'refused'; refusal: ConflictKind }
  /** Removed, and the caller was granted a role — this must be acknowledged. */
  | { at: 'adopted'; projects: ProjectRef[] }

export default function RemoveMemberDialog({ wsId, member, workspaceName, onClose, onRemoved }: {
  wsId: string
  member: WorkspaceMember
  workspaceName?: string
  onClose: () => void
  /** Called once the removal has actually happened, for cache invalidation. */
  onRemoved: () => void | Promise<void>
}) {
  const [stage, setStage] = useState<Stage>({ at: 'confirm' })
  const [busy, setBusy] = useState(false)

  async function remove(adopt: boolean) {
    setBusy(true)
    try {
      const result = await apiRemoveWorkspaceMember(wsId, member.userId, adopt)
      await onRemoved()
      // Branch on the RESULT, not on the flag: a flagged removal that stranded
      // nothing grants nothing and answers 204, and the server — not the client —
      // decides which projects were in the set.
      if (result && result.adoptedProjects.length > 0) {
        setStage({ at: 'adopted', projects: result.adoptedProjects })
        setBusy(false)
      } else {
        onClose()
      }
    } catch (err) {
      setStage({ at: 'refused', refusal: classifyConflict(err) })
      setBusy(false)
    }
  }

  const who = member.displayName || member.email

  if (stage.at === 'adopted') {
    return (
      <Modal title="Removed — and you took over some projects" onClose={onClose} width={520}>
        <div className="flex flex-col gap-4">
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            <bdi>{who}</bdi> is no longer a member of <bdi>{workspaceName ?? 'this workspace'}</bdi>.
            Because they were the only person who could manage who works in them,{' '}
            <b>you are now Team lead</b> in:
          </p>
          <ProjectRefList projects={stage.projects} />
          <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            That is a contributor’s everyday rights plus control of the roster — not control of the
            work: no deleting issues, archiving, or changing project settings. It is durable; hand it
            on when someone takes those projects over.
          </p>
          <div className="flex justify-end">
            <Button variant="primary" onClick={onClose}>Done</Button>
          </div>
        </div>
      </Modal>
    )
  }

  if (stage.at === 'refused') {
    const { refusal } = stage
    const canRetryIdentical = refusal.kind === 'retry'
    const canAdopt = refusal.kind === 'stranded'
    return (
      <Modal title={canAdopt ? `Removing ${who} would strand projects` : `Cannot remove ${who}`}
             onClose={onClose} width={560}>
        <div className="flex flex-col gap-4">
          <Notice tone={canAdopt ? 'warning' : 'danger'}>
            <span className="flex gap-2">
              <ShieldAlert size={16} style={{ flexShrink: 0, marginTop: 1,
                             color: canAdopt ? 'var(--color-warning)' : 'var(--color-error)' }} />
              <span>{refusal.detail}</span>
            </span>
          </Notice>

          {'projects' in refusal && refusal.projects.length > 0 && (
            <div className="flex flex-col gap-2">
              <span className="text-xs font-semibold uppercase tracking-wider"
                    style={{ color: 'var(--color-text-muted)' }}>
                {canAdopt ? 'Projects you would take over' : 'Projects in the way'}
              </span>
              <ProjectRefList projects={refusal.projects} />
            </div>
          )}

          {canAdopt && (
            <div className="flex flex-col gap-2 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              <p>
                Nobody else can manage who works in {refusal.projects.length === 1 ? 'it' : 'them'}:
                the permission to do that is not part of what a workspace administrator holds across
                projects, so an admin who is not a member there cannot step in.
              </p>
              <p>
                Continuing gives <b>you</b> the built-in <b>Team lead</b> role in each of them — the
                everyday contributor rights plus control of the roster. It is a durable takeover of
                somebody else’s project, not a formality.
              </p>
              <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                The set is recalculated when you confirm, so a project that gains another
                administrator in the meantime is left alone. You will be told exactly what you were
                given.
              </p>
            </div>
          )}

          {refusal.kind === 'retry' && (
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              Somebody else was changing membership at the same moment, so nothing was changed at
              all. Try again in {refusal.seconds} second{refusal.seconds !== 1 ? 's' : ''}.
            </p>
          )}
          {refusal.kind === 'adoptionUnreadable' && (
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              Nothing was changed, and there is nothing you or <bdi>{who}</bdi> can do about this one — retrying
              fails the same way. It needs a system administrator to repair the stored membership.
            </p>
          )}
          {refusal.kind === 'adoptionBlocked' && (
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              Nothing was changed, and retrying with the same flag fails identically — so no button
              here can fix it.
            </p>
          )}
          {refusal.kind === 'strandedByInheritance' && (
            // Door 6 (HD-130 S7): they administer those projects through the
            // project's DEFAULT access, not through a membership row. There is no
            // adopt button on purpose — adopting writes a Team lead row, and
            // whoever is left inherits a default at least that wide, so taking
            // them over would narrow the rescuer in the project they rescued.
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              Nothing was changed. Add an explicit administrator to each of those projects first —
              the current default still lets you — or ask <bdi>{who}</bdi> to do it while they still
              can. There is no “take them over” option here: it would leave you with less than you
              have now.
            </p>
          )}

          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>Close</Button>
            {canRetryIdentical && (
              <Button variant="secondary" loading={busy} onClick={() => remove(false)}>
                Try again
              </Button>
            )}
            {canAdopt && (
              <Button variant="danger" loading={busy} onClick={() => remove(true)}>
                Take over {refusal.projects.length} project{refusal.projects.length !== 1 ? 's' : ''} and
                remove <bdi>{who}</bdi>
              </Button>
            )}
          </div>
        </div>
      </Modal>
    )
  }

  return (
    <Modal title={`Remove ${who}?`} onClose={onClose} width={520}>
      <div className="flex flex-col gap-4">
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          They lose access to <bdi>{workspaceName ?? 'this workspace'}</bdi> and to every project in it. Their
          account, and any other workspace they belong to, are untouched.
        </p>
        <ul className="text-sm flex flex-col gap-1 m-0 pl-4" style={{ color: 'var(--color-text-secondary)' }}>
          <li>Their project memberships here are deleted.</li>
          <li>Issues assigned to them here become unassigned, and the change shows in each issue’s history.</li>
          <li>Any invitation still waiting for their address here is withdrawn.</li>
          <li>What they wrote stays: reported issues, comments, uploaded files and history keep their name.</li>
        </ul>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={busy} onClick={() => remove(false)}>
            Remove from workspace
          </Button>
        </div>
      </div>
    </Modal>
  )
}
