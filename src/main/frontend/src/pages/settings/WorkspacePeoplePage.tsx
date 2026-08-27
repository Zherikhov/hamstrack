import { useState } from 'react'
import { useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Check, UserPlus } from 'lucide-react'
import {
  apiGetWorkspace, apiInviteWorkspaceMember, apiListWorkspaceMembers, apiUpdateWorkspaceMemberRole,
} from '../../api'
import { useAuthStore } from '../../auth'
import { usePermissions } from '../../hooks/usePermissions'
import { useRoleInvalidation, useRoles, sortRoles } from '../../hooks/useRoles'
import { Avatar, Button, Input } from '../../components/ui'
import { Chip, RoleLabel, RoleSelect, SettingsPageHeader, classifyConflict, resolveRoleById } from '../../components/roles'
import RemoveMemberDialog from '../../components/RemoveMemberDialog'
import type { WorkspaceMember } from '../../types'

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

      {canManage && <InviteRow wsId={wsId!} roles={roles} onInvited={invalidate} />}

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

      {/* A visible stub rather than a silent omission: there is no endpoint that
          lists a workspace's pending invitations, so this screen cannot show
          them yet even though an invite it sends is real. */}
      <div className="rounded-lg border border-dashed px-4 py-3 mt-4"
           style={{ borderColor: 'var(--color-border-2)' }}>
        <div className="text-xs font-semibold uppercase tracking-wider mb-1"
             style={{ color: 'var(--color-text-muted)' }}>
          Pending invitations · coming soon
        </div>
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          Invitations you send are delivered and can be accepted — but there is no endpoint yet that
          lists or revokes the ones still outstanding, so they cannot be shown here.
        </p>
      </div>

      {removing && wsId && (
        <RemoveMemberDialog
          wsId={wsId}
          member={removing}
          workspaceName={workspace?.name}
          onClose={() => setRemoving(null)}
          onRemoved={invalidate}
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
      setError(classifyConflict(err).detail)
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
