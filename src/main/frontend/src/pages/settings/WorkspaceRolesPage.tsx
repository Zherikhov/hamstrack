import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Copy, Pencil, Trash2 } from 'lucide-react'
import { apiListProjects } from '../../api'
import { deliveryOf } from '../../hooks/useProjectDelivery'
import { usePermissions } from '../../hooks/usePermissions'
import { useRoleInvalidation, useRoles, sortRoles } from '../../hooks/useRoles'
import { Button } from '../../components/ui'
import { Chip, SettingsPageHeader, usageSummary } from '../../components/roles'
import RoleEditorModal from '../../components/RoleEditorModal'
import type { WorkspaceCapabilities } from '../../components/RoleEditorModal'
import { DeleteRoleDialog, DuplicateRoleDialog } from '../../components/RoleDialogs'
import { MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING } from '../../types'
import type { Role, RoleScope } from '../../types'

/**
 * Workspace settings → **Roles** (HD-123 S6 · §14.2).
 *
 * A role is a named bundle of permissions and nothing more: there is no ordering
 * on roles and no rung a custom one occupies. The page is therefore two flat
 * lists — one per scope — rather than a hierarchy, and nothing on it is sorted
 * by "power".
 *
 * **Duplication is the front door.** There is no bare create action, because the
 * grant ceiling is a subset rule and a role composed from an empty checklist can
 * assign nobody. So Duplicate sits on *every* row, built-ins included, and the
 * empty state teaches the recipe rather than apologising for a missing button.
 *
 * Built-in roles are product metadata shared by every workspace: visible and
 * assignable everywhere, and neither editable nor deletable. That is branched on
 * the `builtIn` flag and never on a key — a workspace may legally own a custom
 * role keyed `ADMIN` beside the built-in one, and **that** one is editable.
 */

const SCOPE_BLURB: Record<RoleScope, string> = {
  WORKSPACE: 'Held once per person, for the whole workspace: inviting people, workspace-level settings and taxonomy, labels, and the right to open projects.',
  PROJECT: 'Held per project. A project role decides what somebody may do inside one project — file work, move it, comment, cut releases, run sprints.',
}

export default function WorkspaceRolesPage() {
  const { wsId } = useParams<{ wsId: string }>()
  const permissions = usePermissions(wsId)
  const canManage = permissions.can('workspace.role.manage')
  const invalidate = useRoleInvalidation(wsId)

  // `includeUsage` needs `workspace.role.manage` and 403s without it, so the
  // question is only ever asked by somebody who may hear the answer.
  const { data: roleList = [], isLoading } = useRoles(wsId, { includeUsage: canManage, enabled: !permissions.isLoading })
  const roles = sortRoles(roleList)

  /**
   * Which delivery capabilities any project in this workspace actually uses —
   * the input to the editor's hiding rule. Archived projects count: a frozen
   * project's capability is still a way this workspace works, and hiding more
   * than necessary is the failure direction that loses a permission.
   */
  const { data: projects, isLoading: projectsLoading } = useQuery({
    queryKey: ['projects', wsId, 'all'],
    queryFn: () => apiListProjects(wsId!, true),
    enabled: !!wsId,
  })
  const capabilities: WorkspaceCapabilities = useMemo(() => ({
    BOARD: (projects ?? []).some(p => deliveryOf(p).board === 'SCRUM'),
    RELEASES: (projects ?? []).some(p => deliveryOf(p).releases),
    // Not knowing is not "off": until the list is in, nothing is hidden.
    known: !!projects && !projectsLoading,
  }), [projects, projectsLoading])

  const [editing, setEditing] = useState<Role | null>(null)
  const [duplicating, setDuplicating] = useState<Role | null>(null)
  const [deleting, setDeleting] = useState<Role | null>(null)

  function section(scope: RoleScope) {
    const inScope = roles.filter(r => r.scope === scope)
    const custom = inScope.filter(r => !r.builtIn)
    return (
      <section key={scope} className="mb-8">
        <h2 className="font-display font-bold mb-1" style={{ fontSize: 15 }}>
          {scope === 'WORKSPACE' ? 'Workspace roles' : 'Project roles'}
        </h2>
        <p className="text-sm mb-3" style={{ color: 'var(--color-text-secondary)', maxWidth: 640 }}>
          {SCOPE_BLURB[scope]}
        </p>
        <div className="rounded-lg border overflow-hidden"
             style={{ background: 'white', borderColor: 'var(--color-border)' }}>
          {inScope.map(role => (
            <div key={role.id} className="px-4 py-3 border-b last:border-b-0 flex items-start gap-3"
                 style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>{role.name}</span>
                  {role.builtIn && <Chip title="Product metadata shared by every workspace. Duplicate it to customise.">Built-in</Chip>}
                  <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>{role.key}</span>
                  {role.assignment.warnings.includes(MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING) && (
                    <Chip tone="warning" title="It can add people to a roster but cannot give any of them the ability to do anything.">
                      manages people, hands out nothing
                    </Chip>
                  )}
                </div>
                {role.description && (
                  <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>{role.description}</p>
                )}
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                  {role.permissions.length === 0
                    ? 'Grants nothing'
                    : `${role.permissions.length} permission${role.permissions.length !== 1 ? 's' : ''}`}
                  {usageSummary(role.usage) && <> · {usageSummary(role.usage)}</>}
                </p>
              </div>
              <div className="flex items-center gap-1 flex-shrink-0">
                <Button variant="ghost" size="sm" disabled={!canManage} onClick={() => setDuplicating(role)}>
                  <Copy size={13} /> Duplicate
                </Button>
                {!role.builtIn && (
                  <>
                    <Button variant="ghost" size="sm" disabled={!canManage} onClick={() => setEditing(role)}>
                      <Pencil size={13} /> Edit
                    </Button>
                    <Button variant="ghost" size="sm" disabled={!canManage} onClick={() => setDeleting(role)}>
                      <Trash2 size={13} /> Delete
                    </Button>
                  </>
                )}
              </div>
            </div>
          ))}
          {inScope.length === 0 && !isLoading && (
            <div className="px-4 py-6 text-sm" style={{ color: 'var(--color-text-muted)' }}>No roles here yet.</div>
          )}
        </div>
        {custom.length === 0 && inScope.length > 0 && (
          <div className="rounded-lg border border-dashed px-4 py-3 mt-3"
               style={{ borderColor: 'var(--color-border-2)' }}>
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              No roles of your own here yet.{' '}
              {scope === 'PROJECT'
                ? <>Try the one most teams want first: <b>duplicate Contributor</b>, tick <b>Manage sprints</b>, and call it “Team lead”. Only the people holding it can then start and complete sprints.</>
                : <>Duplicate the closest built-in and adjust it — starting from a template is what makes a role able to hand out anything at all.</>}
            </p>
          </div>
        )}
      </section>
    )
  }

  return (
    <div>
      <SettingsPageHeader
        title="Roles"
        subtitle="Built-in templates, plus any roles this workspace defines. A role is a bundle of permissions — nothing here ranks above anything else."
      />
      {!canManage && !permissions.isLoading && (
        <div className="text-sm rounded-lg px-3 py-2.5 mb-4"
             style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)',
                      color: 'var(--color-text-secondary)' }}>
          You can see which roles exist — every People tab renders their names — but changing what a
          role may do needs the “Manage roles” permission.
        </div>
      )}
      {isLoading && <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>}
      {section('WORKSPACE')}
      {section('PROJECT')}

      {editing && wsId && (
        <RoleEditorModal
          wsId={wsId}
          role={editing}
          capabilities={capabilities}
          onClose={() => setEditing(null)}
          onSaved={async () => { setEditing(null); await invalidate() }}
        />
      )}
      {duplicating && wsId && (
        <DuplicateRoleDialog
          wsId={wsId}
          source={duplicating}
          onClose={() => setDuplicating(null)}
          onCreated={async created => {
            setDuplicating(null)
            await invalidate()
            // Straight into the editor: duplicating is step one of composing a
            // role, and stopping at "created" would leave the user to find it.
            setEditing(created)
          }}
        />
      )}
      {deleting && wsId && (
        <DeleteRoleDialog
          wsId={wsId}
          role={deleting}
          replacements={roles.filter(r => r.scope === deleting.scope && r.id !== deleting.id)}
          onClose={() => setDeleting(null)}
          onDeleted={async () => { setDeleting(null); await invalidate() }}
        />
      )}
    </div>
  )
}
