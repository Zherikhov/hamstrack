import { useCallback, useState } from 'react'
import { useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, UserPlus } from 'lucide-react'
import {
  ApiResponseError,
  apiGetWorkspace, apiInviteWorkspaceMember, apiListWorkspaceInvites, apiListWorkspaceMembers,
  apiRevokeWorkspaceInvite, apiUpdateWorkspaceMemberRole,
} from '../../api'
import { useAuthStore } from '../../auth'
import { usePermissions } from '../../hooks/usePermissions'
import { useRoleInvalidation, useRoles, sortRoles } from '../../hooks/useRoles'
import { Avatar, Button, Input } from '../../components/ui'
import { Chip, RoleLabel, RoleSelect, SettingsPageHeader, classifyConflict, resolveRoleById } from '../../components/roles'
import RemoveMemberDialog from '../../components/RemoveMemberDialog'
import type { WorkspaceInvite, WorkspaceMember } from '../../types'

/**
 * Workspace settings → **People** (HD-123 S6 · §14.2).
 *
 * Readable by any member who can open workspace settings; every write needs
 * `workspace.member.manage`, and the controls are drawn **disabled** rather than
 * hidden while that answer is loading — a permanent slot never pops in.
 *
 * Three server rules this screen renders rather than re-implements:
 *
 *  • **The grant ceiling.** Nobody may hand out, or act on a member holding, a
 *    role granting a permission they do not hold themselves; the check covers the
 *    target's *current* role as well as the new one. It compares permission sets,
 *    not role names, so no client-side predicate could reproduce it — the refusal
 *    is a 403 whose `detail` **names the offending permission**, and that sentence
 *    is rendered verbatim.
 *  • **The Owner guardrail.** Only an Owner may hand out `OWNER` or administer
 *    somebody who holds it. `OWNER` and `ADMIN` have identical permissions on
 *    purpose, so no permission comparison can express this and the UI does not try.
 *  • **The last Owner.** Demoting or removing the final one is a 409 for anybody,
 *    including that owner acting on themselves.
 *
 * Roles are identified by **id**, never by key: `MEMBER` is a built-in key in
 * both scopes, so a key resolved against a catalog spanning both can name the
 * wrong privilege. A row whose `roleId` is null — or names something this
 * workspace cannot describe — gets a placeholder, never a guess.
 *
 * Every one of those is a message from the server, on a row, after an attempt.
 * Pre-emptively disabling a select for a rule the client cannot evaluate would
 * mean guessing — and guessing narrow hides a legitimate action while guessing
 * wide is the HD-98 class of bug all over again.
 */
export default function WorkspacePeoplePage() {
  const { wsId } = useParams<{ wsId: string }>()
  const me = useAuthStore(s => s.user)
  const permissions = usePermissions(wsId)
  const canManage = permissions.can('workspace.member.manage')
  const invalidate = useRoleInvalidation(wsId)

  const { data: workspace } = useQuery({
    queryKey: ['workspace', wsId],
    queryFn: () => apiGetWorkspace(wsId!),
    enabled: !!wsId,
  })
  const { data: members = [], isLoading } = useQuery({
    queryKey: ['workspace-members', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId,
  })
  // Reading roles needs nothing but membership — role names are rendered for
  // everybody. `includeUsage` is the sensitive half and is not asked for here.
  const { data: roleList = [] } = useRoles(wsId, { scope: 'WORKSPACE' })
  const roles = sortRoles(roleList)

  const [rowError, setRowError] = useState<Record<string, string>>({})
  const [busyRow, setBusyRow] = useState<string | null>(null)
  const [removing, setRemoving] = useState<WorkspaceMember | null>(null)

  const qc = useQueryClient()
  /**
   * The pending list, refreshed. **Deliberately not folded into
   * `useRoleInvalidation`**, which is the list of everything a *permission set*
   * rides on — an invitation is neither a role nor a membership, and widening
   * that hook would make every screen in the feature refetch a list only this
   * one renders.
   *
   * Anything that can change what the list contains moves it, including two
   * that are easy to miss. **Removing a member**: HD-132 deletes that member's
   * unaccepted invitations as a side effect, so without this the screen would
   * keep offering a Withdraw button for rows the server has already deleted.
   * And **an invite that was refused as a duplicate** (HD-133): nothing was
   * written, but the refusal points at a row this list is supposed to be
   * showing, and the surest reason it is absent is that this copy is stale.
   */
  const invalidateInvites = useCallback(
    () => qc.invalidateQueries({ queryKey: invitesKey(wsId) }),
    [qc, wsId],
  )
  const invalidateAll = useCallback(async () => {
    await Promise.all([invalidate(), invalidateInvites()])
  }, [invalidate, invalidateInvites])

  async function changeRole(member: WorkspaceMember, roleId: string) {
    setBusyRow(member.userId)
    setRowError(prev => ({ ...prev, [member.userId]: '' }))
    try {
      await apiUpdateWorkspaceMemberRole(wsId!, member.userId, roleId)
      await invalidate()
    } catch (err) {
      // The server's sentence, verbatim: a ceiling refusal names the permission
      // that blocked it, which is the only actionable part of the message.
      setRowError(prev => ({ ...prev, [member.userId]: classifyConflict(err).detail }))
    } finally {
      setBusyRow(null)
    }
  }

  return (
    <div>
      <SettingsPageHeader
        title="People"
        subtitle="Who is in this workspace, and what each of them may do. A role is a bundle of permissions — there is no ladder, so “bigger” is not a question the product asks."
      />

      {canManage && <InviteRow wsId={wsId!} roles={roles} onInvited={invalidateAll} />}

      <div className="rounded-lg border overflow-hidden"
           style={{ background: 'white', borderColor: 'var(--color-border)' }}>
        {isLoading && (
          <div className="px-4 py-6 mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</div>
        )}
        {members.map(member => {
          const current = resolveRoleById(roles, member.roleId)
          const isMe = me?.id === member.userId
          return (
            <div key={member.userId} className="px-4 py-3 border-b last:border-b-0 flex flex-col gap-2"
                 style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex items-center gap-3">
                <Avatar name={member.displayName} avatarUrl={member.avatarUrl} size={32} />
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium flex items-center gap-2" style={{ color: 'var(--color-text)' }}>
                    <span className="truncate">{member.displayName}</span>
                    {isMe && <Chip>you</Chip>}
                  </div>
                  <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{member.email}</div>
                </div>

                <div style={{ width: 190, flexShrink: 0 }}>
                  {/* A permanent slot: disabled until the answer lands, never absent. */}
                  {current || roles.length === 0 ? (
                    <RoleSelect
                      roles={roles}
                      value={current?.id ?? ''}
                      ariaLabel={`Role for ${member.displayName}`}
                      disabled={!canManage || busyRow === member.userId}
                      onChange={roleId => changeRole(member, roleId)}
                    />
                  ) : (
                    // No role id came back (the server refused to describe this
                    // row and withheld the name and the id together), or the id
                    // matches nothing this workspace can name. Show what we were
                    // told and offer the change without asserting a current value.
                    <div className="flex flex-col gap-1">
                      <RoleLabel role={member.role} />
                      <RoleSelect
                        roles={roles}
                        value="" compact placeholder="Choose a role…"
                        ariaLabel={`Set a role for ${member.displayName}`}
                        disabled={!canManage || busyRow === member.userId}
                        onChange={roleId => changeRole(member, roleId)}
                      />
                    </div>
                  )}
                </div>

                <div style={{ width: 90, flexShrink: 0 }} className="flex justify-end">
                  {canManage && !isMe && (
                    <Button variant="ghost" size="sm" onClick={() => setRemoving(member)}>Remove</Button>
                  )}
                </div>
              </div>
              {rowError[member.userId] && (
                <p className="text-xs" style={{ color: 'var(--color-error)' }}>{rowError[member.userId]}</p>
              )}
            </div>
          )
        })}
      </div>

      <p className="text-xs mt-3" style={{ color: 'var(--color-text-muted)' }}>
        Removing somebody here revokes this workspace only — not their account, and not the other
        workspaces they belong to. Leaving a workspace yourself is a separate feature and is not
        built yet, so you cannot remove your own membership from this screen.
      </p>

      {/* Mounted ONLY for a holder of `workspace.member.manage` — `can()` answers
          false while the permission set is still loading, so this section can pop
          in but can never flash in and vanish. Everything it renders is email
          addresses, so there is no read-only residue worth showing to anybody
          else: not an empty list (a falsehood on their screen), not a count
          (disclosure with no matching ability). */}
      {canManage && wsId && <PendingInvitations wsId={wsId} roles={roles} />}

      {removing && wsId && (
        <RemoveMemberDialog
          wsId={wsId}
          member={removing}
          workspaceName={workspace?.name}
          onClose={() => setRemoving(null)}
          onRemoved={invalidateAll}
        />
      )}
    </div>
  )
}

/** Invite by email. `roleId` names the role — the only form that can address a custom one. */
function InviteRow({ wsId, roles, onInvited }: {
  wsId: string
  roles: ReturnType<typeof sortRoles>
  onInvited: () => Promise<void>
}) {
  const [email, setEmail] = useState('')
  const [roleId, setRoleId] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [sentTo, setSentTo] = useState<string | null>(null)

  // The narrowest built-in — and **nothing** when it is absent. An invite is a
  // grant, so the fallback for "the role I expected is not in this list" has to
  // be no selection: sorted order carries no promise about privilege, so any
  // positional guess (`roles[0]`, `roles[length - 1]`) can land on the widest
  // role there is. An empty picker with Send disabled is the only safe answer,
  // and it says out loud that something is missing rather than hiding it behind
  // a plausible-looking default.
  const fallback = roles.find(r => r.builtIn && r.key === 'MEMBER')
  const selected = roleId || fallback?.id || ''

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setSentTo(null)
    const trimmed = email.trim()
    if (!trimmed) return
    setSending(true)
    try {
      await apiInviteWorkspaceMember(wsId, { email: trimmed, roleId: selected })
      await onInvited()
      setSentTo(trimmed)
      setEmail('')
    } catch (err) {
      // **The typed address survives every refusal.** `setEmail('')` lives on
      // the success path above and must STAY there — nothing in this handler may
      // clear the field. The case that makes it load-bearing is the invite
      // ceilings' 429 (HD-190): that refusal's whole remedy is "send this same
      // address again in X", so emptying the box on the way out would make the
      // admin retype from memory the one thing they were asked to keep. The
      // other refusals want it kept too — a 403 grant-ceiling refusal is cleared
      // by choosing a narrower role, not by retyping the person.
      //
      // `detail` is rendered VERBATIM and no wait is recomputed from
      // `retryAfter`: every throttled refusal here already names the wait in
      // prose ("Try again in 59 minutes"), so a second, independently rounded
      // countdown beside it could only disagree with it. `classifyConflict`
      // reads `Retry-After` before any `errorType`, so a 429 arrives as
      // `kind: 'retry'` — this row wants nothing from it but the sentence.
      const refusal = classifyConflict(err)
      setError(refusal.detail)
      // **A duplicate refusal moves the list below** (HD-133). Its remedy is
      // "withdraw the blocking invitation under Workspace settings → People" —
      // this screen — so the row it names has to be on screen when the sentence
      // is read: a stale list (or one loaded before somebody else invited the
      // same address) is exactly how an administrator concludes the refusal is
      // wrong. Keyed on the kind, never on the status: the "already a member"
      // 409 is also a 409, carries no `errorType`, outranks this one when both
      // apply, and names a row this list does not contain — refreshing for it
      // would only redraw an unchanged list.
      //
      // The sentence is set FIRST and the refetch is not awaited into the
      // message path, so a slow or failing invalidation can never cost the
      // reader the refusal itself.
      if (refusal.kind === 'duplicateInvite') await onInvited()
    } finally {
      setSending(false)
    }
  }

  return (
    <form onSubmit={submit} className="rounded-lg border p-4 mb-4 flex flex-col gap-3"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}>
      <div className="flex items-end gap-3">
        <div className="flex-1 min-w-0">
          <Input label="Invite by email" type="email" value={email} placeholder="name@company.com"
                 onChange={e => { setEmail(e.target.value); setSentTo(null); setError('') }} />
        </div>
        <div style={{ width: 190, flexShrink: 0 }}>
          {/* A placeholder only while nothing is selected — `Select` falls back to
              its FIRST option when no option matches the value, which is how an
              empty selection would otherwise draw itself as a role. */}
          <RoleSelect roles={roles} value={selected} label="Role" onChange={setRoleId}
                      placeholder={selected ? undefined : 'Choose a role…'} />
        </div>
        <Button variant="primary" type="submit" loading={sending} disabled={!email.trim() || !selected}>
          <UserPlus size={14} />
          Send invite
        </Button>
      </div>
      {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
      {sentTo && (
        <p className="text-xs inline-flex items-center gap-1" style={{ color: 'var(--color-brand)' }}>
          <Check size={13} /> Invite sent to {sentTo}
        </p>
      )}
      <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
        An invitation can never carry the Owner role — not even one sent by an owner. Ownership is
        handed over to somebody who has already joined.
      </p>
    </form>
  )
}

// ── Pending invitations (HD-158) ────────────────────────────────────────────

/** Every read of the workspace's own invitations. One place, so the three writes that move it agree. */
export const WORKSPACE_INVITES_KEY_ROOT = 'workspace-invites' as const

export function invitesKey(wsId: string | undefined) {
  return [WORKSPACE_INVITES_KEY_ROOT, wsId] as const
}

/**
 * **Access this workspace has offered and not had taken up** — the section that
 * replaced the "coming soon" stub.
 *
 * Rendered only behind `workspace.member.manage` (the parent decides that; the
 * query is never fired without it, so a member who lacks the permission produces
 * no request at all and therefore no error banner). Four things it does that are
 * easy to get wrong:
 *
 *  • **Expired rows are shown, labelled.** They are returned deliberately:
 *    nothing sweeps them, they stay withdrawable, and HD-133's uniqueness now
 *    refuses a re-invite over one (the index predicate cannot read a clock, so a
 *    lapsed row keeps holding the slot until somebody withdraws it) — a row that
 *    cannot be seen is a row that cannot be cleared and a refusal that cannot be
 *    explained.
 *  • **A degraded role is a placeholder, never a guess** — `role` and `roleId`
 *    are withheld together precisely so a client cannot look the name back up.
 *  • **404 is success**, not an error. See {@link InvitationRow}.
 *  • **The confirmation carries the one fact the admin cannot otherwise know**:
 *    re-inviting the same address may have to wait, because invitations to one
 *    address are rate limited. It names no number — the server owns those.
 */
function PendingInvitations({ wsId, roles }: {
  wsId: string
  roles: ReturnType<typeof sortRoles>
}) {
  const qc = useQueryClient()
  const { data: invites = [], isLoading, error } = useQuery({
    queryKey: invitesKey(wsId),
    queryFn: () => apiListWorkspaceInvites(wsId),
  })
  const refresh = useCallback(
    () => qc.invalidateQueries({ queryKey: invitesKey(wsId) }),
    [qc, wsId],
  )

  return (
    <section className="mt-6">
      <h2 className="text-sm font-semibold mb-1" style={{ color: 'var(--color-text)' }}>
        Pending invitations
      </h2>
      {/* Inline maxWidth: our @theme --spacing-* scale shadows Tailwind's
          max-w-{xs..3xl} sizes (max-w-xl would be 32px) — see CLAUDE.md */}
      <p className="text-xs mb-3" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
        An invitation is a standing offer of access: anyone holding the link and that mailbox can
        join until it lapses. Withdrawing one takes the offer back immediately — the link and the
        invitation both stop working.
      </p>

      <div className="rounded-lg border overflow-hidden"
           style={{ background: 'white', borderColor: 'var(--color-border)' }}>
        {isLoading && (
          <div className="px-4 py-6 mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</div>
        )}
        {!isLoading && error && (
          <div className="px-4 py-3 text-xs" style={{ color: 'var(--color-error)' }}>
            {classifyConflict(error).detail}
          </div>
        )}
        {!isLoading && !error && invites.length === 0 && (
          <div className="px-4 py-4 text-sm" style={{ color: 'var(--color-text-muted)' }}>
            No invitations are waiting for a reply.
          </div>
        )}
        {invites.map(invite => (
          <InvitationRow key={invite.id} wsId={wsId} invite={invite} roles={roles} onChanged={refresh} />
        ))}
      </div>
    </section>
  )
}

/**
 * One invitation, with a two-step inline confirm rather than a modal.
 *
 * **The 404 branch is the load-bearing part.** A withdrawal races two other
 * deletions in this product — another administrator's click, and a member
 * removal, which deletes that address's unaccepted invitations as a side effect
 * — and because withdrawal hard-deletes, "already gone" is physically identical
 * to "never existed". The server therefore answers 404, and the caller's job is
 * to notice that the user's *intent* is already satisfied: refetch, say nothing.
 *
 * It is keyed on `err.status`, and it has to be: the 404's sentence is
 * deliberately the same one the workspace-level 404 uses ("Workspace not
 * found"), because an invite id must not become an existence oracle. Nothing
 * about the wording can tell the two apart, and a client that tried would break
 * on the first copy edit.
 *
 * The 409 is the opposite case and must NOT be swallowed with it: the invitee
 * accepted first, the row is real, and the server's sentence names the member
 * and points at this very screen — a remedy needing exactly the permission the
 * reader just proved. It is rendered verbatim, through `classifyConflict`, which
 * is also what makes any other refusal (a 403 ceiling, a busy-row retry) arrive
 * as its own sentence instead of a bare status.
 */
function InvitationRow({ wsId, invite, roles, onChanged }: {
  wsId: string
  invite: WorkspaceInvite
  roles: ReturnType<typeof sortRoles>
  onChanged: () => Promise<void> | void
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const resolved = resolveRoleById(roles, invite.roleId)
  const expired = invite.status === 'EXPIRED'

  async function withdraw() {
    setBusy(true)
    setError('')
    try {
      await apiRevokeWorkspaceInvite(wsId, invite.id)
      await onChanged()
    } catch (err) {
      // 404 — already withdrawn, or deleted along with its member. The
      // invitation is gone, which is exactly what the click asked for, so this
      // is a success with nothing to report. Keyed on the STATUS, never on the
      // message: the detail is the same string the workspace-level 404 uses.
      if (err instanceof ApiResponseError && err.status === 404) {
        await onChanged()
        return
      }
      // Everything else is the server's sentence, verbatim — the 409 above all,
      // whose whole value is naming the person and the remedy.
      setError(classifyConflict(err).detail)
    } finally {
      setBusy(false)
      setConfirming(false)
    }
  }

  return (
    <div className="px-4 py-3 border-b last:border-b-0 flex flex-col gap-2"
         style={{ borderColor: 'var(--color-border)' }}>
      <div className="flex items-center gap-3">
        <div className="flex-1 min-w-0">
          <div className="text-sm font-medium flex items-center gap-2" style={{ color: 'var(--color-text)' }}>
            {/* An address is bidi-neutral text that neighbouring content can flip around. */}
            <bdi className="truncate">{invite.email}</bdi>
            {expired && (
              <Chip tone="warning" title="This invitation can no longer be accepted. Withdrawing it clears the row — nothing else removes it.">
                expired
              </Chip>
            )}
          </div>
          <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
            invited by {invite.invitedByName}
            {' · '}
            <time dateTime={invite.createdAt} title={absolute(invite.createdAt)}>{relative(invite.createdAt)}</time>
            {' · '}
            <time dateTime={invite.expiresAt} title={absolute(invite.expiresAt)}>
              {expired ? 'expired ' : 'expires '}{relative(invite.expiresAt)}
            </time>
          </div>
        </div>

        <div style={{ width: 190, flexShrink: 0 }}>
          {/* The invited role. A row the server refused to describe withholds the
              name and the id together, so there is nothing to look up — the
              placeholder is the only honest answer. */}
          {resolved
            ? <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>{resolved.name}</span>
            : <RoleLabel role={invite.role} />}
        </div>

        <div style={{ width: 90, flexShrink: 0 }} className="flex justify-end">
          {!confirming && (
            <Button variant="ghost" size="sm" disabled={busy}
                    aria-label={`Withdraw the invitation to ${invite.email}`}
                    onClick={() => { setError(''); setConfirming(true) }}>
              Withdraw
            </Button>
          )}
        </div>
      </div>

      {confirming && (
        <div className="flex flex-col gap-2 rounded-md px-3 py-2"
             style={{ background: 'var(--color-surface-2)' }}>
          <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            Withdraw the invitation to <bdi>{invite.email}</bdi>? The link stops working straight
            away. You can invite this address again later, though a repeat invitation to the same
            address may have to wait — invitations to one address are rate limited.
          </p>
          <div className="flex items-center gap-2">
            <Button variant="danger" size="sm" loading={busy}
                    aria-label={`Confirm withdrawing the invitation to ${invite.email}`}
                    onClick={withdraw}>
              Withdraw invitation
            </Button>
            <Button variant="ghost" size="sm" disabled={busy} onClick={() => setConfirming(false)}>
              Cancel
            </Button>
          </div>
        </div>
      )}

      {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
    </div>
  )
}

/**
 * A coarse relative time ("3 hours ago", "in 6 days"), rendered beside the exact
 * timestamp in a `title`. Presentation only — **`status` decides whether a row is
 * expired**, never this, because expiry is settled by the server clock and a
 * skewed browser must not disagree with the endpoint that will refuse the
 * acceptance.
 */
function relative(iso: string): string {
  const ms = new Date(iso).getTime() - Date.now()
  if (!Number.isFinite(ms)) return ''
  const fmt = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 365 * 86_400_000],
    ['month', 30 * 86_400_000],
    ['day', 86_400_000],
    ['hour', 3_600_000],
    ['minute', 60_000],
  ]
  for (const [unit, span] of units) {
    if (Math.abs(ms) >= span) return fmt.format(Math.round(ms / span), unit)
  }
  return fmt.format(Math.round(ms / 1000), 'second')
}

function absolute(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}
