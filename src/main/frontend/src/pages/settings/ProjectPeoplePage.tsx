import { useState } from 'react'
import { useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { UserPlus, Users } from 'lucide-react'
import { apiGetProject, apiListWorkspaceMembers, projectMembersApi } from '../../api'
import { useAuthStore } from '../../auth'
import { useProjectPermissions } from '../../hooks/usePermissions'
import { projectMembersKey, useRoleInvalidation, useRoles, sortRoles } from '../../hooks/useRoles'
import { Avatar, Button, Select } from '../../components/ui'
import {
  Chip, Notice, RoleLabel, RoleSelect, classifyConflict, resolveDefaultRole, resolveRoleById,
} from '../../components/roles'
import type { ProjectMember } from '../../types'

/**
 * Project settings → **People** (HD-123 S6 · §14.2).
 *
 * ## The default-access card is the more important half of this screen
 *
 * The SPA had never called the project-members endpoint at all before this
 * slice, and that discovery is what shaped the whole data model: almost nobody
 * has a `project_members` row, so the **default role** — not explicit membership
 * — became the primary grant mechanism. For most workspaces the card at the top
 * of this page is the only thing anyone will ever set here, and the member table
 * below it is the exception rather than the rule. It is drawn first, full width,
 * with a live count of who is actually covered by it — never as a footnote under
 * a table.
 *
 * It is also this feature's **Rule C affordance**: the mechanism has to be
 * discoverable from the page it applies to *while it is off*, or nobody who does
 * not already use it will ever find it.
 *
 * **What it says, and what it deliberately does not.** `ProjectResponse.defaultRole`
 * carries both links of the chain — this project's override and the workspace
 * default — so the card names the role people actually get *and* where that came
 * from, which one value alone could never do: "this project overrides the
 * workspace" and "this project inherits" are different facts about the same name.
 *
 * It stays **read-only here, on purpose**. The default hands its role to every
 * workspace member without an explicit row, so a picker unbounded by the grant
 * ceiling is an administrator granting permissions they do not hold, workspace-
 * wide, in one request; S7 ships the picker and that ceiling together. The
 * backend has no write path, and a control that always fails is worse than none —
 * so the affordance is visible and disabled, which is what Rule C asks for.
 *
 * The workspace's project-access mode is still not exposed, for the same reason,
 * so the card describes the mechanism without claiming the workspace lets people
 * in by default. An id the chain names but this workspace cannot describe gets a
 * placeholder: a confidently wrong label on a privilege is worse than an absent one.
 *
 * ## Explicit membership
 *
 * Listing is open to any workspace member; every write needs
 * `project.member.manage` **in this project** — which no workspace-wide role
 * grants, and which is why removing somebody at workspace level has an adoption
 * flow at all. Changing a role is one call rather than remove-then-add, so the
 * project never passes through a moment with no administrator.
 */
/**
 * Where the default came from, as the tail of the sentence "They get <role>…".
 * Naming the link is the whole reason both ids ride the response: the same role
 * name means something different when this project chose it and when it merely
 * inherited it.
 */
const ORIGIN_SUFFIX: Record<'PROJECT' | 'WORKSPACE' | 'BUILT_IN', string> = {
  PROJECT: ' — a default this project sets for itself.',
  WORKSPACE: ' — the workspace’s default, which this project inherits.',
  BUILT_IN: ' — the built-in default, since neither this project nor the workspace sets one.',
}

export default function ProjectPeoplePage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const me = useAuthStore(s => s.user)
  const permissions = useProjectPermissions(wsId, projectId)
  const canManage = permissions.can('project.member.manage')
  const invalidate = useRoleInvalidation(wsId)

  const { data: project } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const { data: members = [], isLoading } = useQuery({
    queryKey: projectMembersKey(wsId, projectId),
    queryFn: () => projectMembersApi.list(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const { data: workspaceMembers = [] } = useQuery({
    queryKey: ['workspace-members', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId!),
    enabled: !!wsId,
  })
  const { data: roleList = [], isLoading: rolesLoading } = useRoles(wsId, { scope: 'PROJECT' })
  const roles = sortRoles(roleList)

  const [rowError, setRowError] = useState<Record<string, string>>({})
  const [busyRow, setBusyRow] = useState<string | null>(null)

  async function changeRole(member: ProjectMember, roleId: string) {
    setBusyRow(member.userId)
    setRowError(prev => ({ ...prev, [member.userId]: '' }))
    try {
      await projectMembersApi.updateRole(wsId!, projectId!, member.userId, roleId)
      await invalidate()
    } catch (err) {
      // 409 here is the last-administrator guard, which a demotion trips just as
      // effectively as a removal — with no row removed at all.
      setRowError(prev => ({ ...prev, [member.userId]: classifyConflict(err).detail }))
    } finally {
      setBusyRow(null)
    }
  }

  async function removeMember(member: ProjectMember) {
    setBusyRow(member.userId)
    setRowError(prev => ({ ...prev, [member.userId]: '' }))
    try {
      await projectMembersApi.remove(wsId!, projectId!, member.userId)
      await invalidate()
    } catch (err) {
      setRowError(prev => ({ ...prev, [member.userId]: classifyConflict(err).detail }))
    } finally {
      setBusyRow(null)
    }
  }

  const explicit = new Set(members.map(m => m.userId))
  const onDefault = workspaceMembers.filter(m => !explicit.has(m.userId))
  // Project override → workspace default → the built-in Contributor. Both links
  // ride the project response, so this costs no request.
  const fallback = resolveDefaultRole(roles, project?.defaultRole)

  return (
    <div>
      <div className="mb-5">
        <h2 className="font-display font-bold" style={{ fontSize: 18, letterSpacing: '-0.2px' }}>People</h2>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 620 }}>
          Almost nobody needs a role of their own here. Everyone in the workspace can already work in
          this project through its default access; add somebody explicitly only to give them
          something different.
        </p>
      </div>

      {/* ── Default access — the primary control on this page ─────────────── */}
      <div className="rounded-lg border p-4 mb-6"
           style={{ background: 'white', borderColor: 'var(--color-border)',
                    boxShadow: 'var(--shadow-card)' }}>
        <div className="flex items-start gap-3">
          <span className="flex items-center justify-center rounded-lg flex-shrink-0"
                style={{ width: 34, height: 34, color: 'var(--color-brand)',
                         background: 'color-mix(in srgb, var(--color-brand) 12%, white)' }}>
            <Users size={17} />
          </span>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>Default access</div>
            <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              Everyone in this workspace can contribute to{' '}
              <b><bdi>{project?.name ?? 'this project'}</bdi></b>{' '}
              without being added to it. Anyone listed below has been given a different role instead.
            </p>
            {rolesLoading ? (
              // Hold the sentence rather than name the built-in fallback and then
              // swap it: the chain cannot be walked until the roles are in, and a
              // privilege that changes its name mid-render reads as a bug.
              <p className="text-sm mt-2 mono" style={{ color: 'var(--color-text-muted)' }}>
                checking what that role is…
              </p>
            ) : fallback.unresolvable ? (
              <div className="mt-2">
                <Notice tone="danger">
                  <span className="text-sm">
                    The role this project hands out by default is stored as something this workspace
                    cannot describe. Nobody’s access has changed, but it cannot be named here — ask a
                    system administrator to check the project’s default role.
                  </span>
                </Notice>
              </div>
            ) : (
              <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                They get <b>{fallback.role?.name ?? 'the built-in contributor role'}</b>
                {ORIGIN_SUFFIX[fallback.origin]}
              </p>
            )}
            <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
              {workspaceMembers.length > 0 ? (
                <>
                  <b>{onDefault.length}</b> of {workspaceMembers.length} workspace{' '}
                  {workspaceMembers.length === 1 ? 'member works' : 'members work'} here through that
                  default today
                  {members.length > 0 && <>, and {members.length} {members.length === 1 ? 'has' : 'have'} a role of their own</>}.
                </>
              ) : (
                <>Everybody in the workspace is covered by it.</>
              )}
            </p>
            <div className="flex items-center gap-2 mt-3">
              <Button variant="secondary" size="sm" disabled
                      title="The default-role picker and the workspace-wide Open/Restricted switch are not built yet.">
                Change default access
              </Button>
              <Chip tone="warning">coming soon</Chip>
            </div>
            <p className="text-xs mt-2" style={{ color: 'var(--color-text-muted)' }}>
              Changing the default hands its role to everybody at once, so it has to be bounded by
              the same rule that bounds handing a role to one person — that rule, and this picker,
              land together. Whether the workspace lets people in by default at all is part of the
              same change.
            </p>
          </div>
        </div>
      </div>

      {/* ── Explicit members ─────────────────────────────────────────────── */}
      <h3 className="text-xs font-semibold uppercase tracking-wider mb-2"
          style={{ color: 'var(--color-text-muted)' }}>
        People with a role of their own{members.length > 0 ? ` · ${members.length}` : ''}
      </h3>

      {canManage && (
        <AddMemberRow wsId={wsId!} projectId={projectId!} roles={roles}
                      candidates={onDefault} onAdded={invalidate} />
      )}

      <div className="rounded-lg border overflow-hidden"
           style={{ background: 'white', borderColor: 'var(--color-border)' }}>
        {isLoading && (
          <div className="px-4 py-6 mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</div>
        )}
        {!isLoading && members.length === 0 && (
          <div className="px-4 py-5 text-sm" style={{ color: 'var(--color-text-muted)' }}>
            Nobody has a role of their own here — everyone works under the default above. That is the
            normal state, not a gap.
          </div>
        )}
        {members.map(member => {
          const current = resolveRoleById(roles, member.roleId)
          return (
            <div key={member.userId} className="px-4 py-3 border-b last:border-b-0 flex flex-col gap-2"
                 style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex items-center gap-3">
                <Avatar name={member.displayName} avatarUrl={member.avatarUrl} size={32} />
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium flex items-center gap-2" style={{ color: 'var(--color-text)' }}>
                    <span className="truncate">{member.displayName}</span>
                    {me?.id === member.userId && <Chip>you</Chip>}
                  </div>
                  <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{member.email}</div>
                </div>
                <div style={{ width: 190, flexShrink: 0 }}>
                  {current || roles.length === 0 ? (
                    <RoleSelect roles={roles} value={current?.id ?? ''}
                                ariaLabel={`Project role for ${member.displayName}`}
                                disabled={!canManage || busyRow === member.userId}
                                onChange={roleId => changeRole(member, roleId)} />
                  ) : (
                    <div className="flex flex-col gap-1">
                      <RoleLabel role={member.role} />
                      <RoleSelect roles={roles} value="" compact placeholder="Choose a role…"
                                  ariaLabel={`Set a project role for ${member.displayName}`}
                                  disabled={!canManage || busyRow === member.userId}
                                  onChange={roleId => changeRole(member, roleId)} />
                    </div>
                  )}
                </div>
                <div style={{ width: 90, flexShrink: 0 }} className="flex justify-end">
                  {canManage && (
                    <Button variant="ghost" size="sm" disabled={busyRow === member.userId}
                            onClick={() => removeMember(member)}>
                      Remove
                    </Button>
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
        Removing somebody here does not remove them from the workspace — they fall back to the
        default access above. A project’s last administrator cannot be removed or demoted: add
        another one first.
      </p>
    </div>
  )
}

function AddMemberRow({ wsId, projectId, roles, candidates, onAdded }: {
  wsId: string
  projectId: string
  roles: ReturnType<typeof sortRoles>
  candidates: { userId: string; displayName: string; email: string }[]
  onAdded: () => Promise<void>
}) {
  const [userId, setUserId] = useState('')
  const [roleId, setRoleId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  // The narrowest built-in, or **no selection** when it is absent — never
  // `roles[0]`, which after `sortRoles` is the built-in at position 0: Project
  // admin, full control of the project. A fallback that exists for the case where
  // the expected role is missing must not resolve to a privilege in that case,
  // least of all the widest one on the list. Add stays disabled instead.
  const fallback = roles.find(r => r.builtIn && r.key === 'MEMBER')
  const selectedRole = roleId || fallback?.id || ''

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!userId || !selectedRole) return
    setBusy(true)
    setError('')
    try {
      await projectMembersApi.add(wsId, projectId, { userId, roleId: selectedRole })
      await onAdded()
      setUserId('')
    } catch (err) {
      setError(classifyConflict(err).detail)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="rounded-lg border p-3 mb-3 flex flex-col gap-2"
          style={{ background: 'var(--color-surface)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-end gap-3">
        <div className="flex-1 min-w-0">
          <Select label="Add someone from the workspace" value={userId}
                  onChange={e => setUserId(e.target.value)}>
            <option value="">Choose a person…</option>
            {candidates.map(c => (
              <option key={c.userId} value={c.userId}>{c.displayName} · {c.email}</option>
            ))}
          </Select>
        </div>
        <div style={{ width: 190, flexShrink: 0 }}>
          {/* Placeholder only while nothing is selected: `Select` renders its
              first option when the value matches none, so an empty selection
              would otherwise be drawn as whichever role sorts first. */}
          <RoleSelect roles={roles} value={selectedRole} label="Project role" onChange={setRoleId}
                      placeholder={selectedRole ? undefined : 'Choose a role…'} />
        </div>
        <Button variant="primary" type="submit" loading={busy} disabled={!userId || !selectedRole}>
          <UserPlus size={14} />
          Add
        </Button>
      </div>
      {candidates.length === 0 && (
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          Everyone in the workspace already has a role of their own here.
        </p>
      )}
      {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
    </form>
  )
}
