import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { X, Settings } from 'lucide-react'
import { apiListWorkspaceMembers } from '../api'
import { Avatar } from './ui'
import { RoleLabel } from './roles'

interface Props {
  wsId: string
  /**
   * Whether the current user may act on a member (`workspace.member.manage`).
   * Only decides whether the link to the People screen is offered — the server
   * enforces every write regardless, and the screen itself renders read-only
   * without the permission.
   */
  canManage: boolean
  onClose: () => void
}

/**
 * "Who's in this workspace" — a quick read-only roster off the workspace
 * overview.
 *
 * It used to carry an invite form and a TODO about the endpoints that did not
 * exist yet. They exist now, and managing people is a screen rather than a
 * popover: Workspace settings → **People** does roles, invitations and removal,
 * including the adoption flow a removal can need. Two surfaces that both change
 * a privilege is how a UI gate drifts from a server predicate, so this one only
 * shows and links.
 */
export default function WorkspaceMembersModal({ wsId, canManage, onClose }: Props) {
  const { data: members = [], isLoading } = useQuery({
    queryKey: ['workspace-members', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId),
  })

  const overlayStyle: React.CSSProperties = {
    position: 'fixed', inset: 0, zIndex: 50,
    background: 'rgba(28,27,25,0.55)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    backdropFilter: 'blur(2px)',
  }

  const panelStyle: React.CSSProperties = {
    background: 'white',
    borderRadius: 'var(--radius-xl)',
    border: '1px solid var(--color-border)',
    width: 460,
    maxWidth: 'calc(100vw - 32px)',
    maxHeight: 'calc(100vh - 64px)',
    display: 'flex',
    flexDirection: 'column',
    boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
  }

  return (
    <div data-modal-open="true" style={overlayStyle} onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-label="Workspace members"
           style={panelStyle} onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b flex-shrink-0"
             style={{ borderColor: 'var(--color-border)' }}>
          <span className="font-semibold text-sm" style={{ color: 'var(--color-text)' }}>
            Workspace members
          </span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 transition-opacity">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <div className="overflow-y-auto p-5 flex flex-col gap-4">
          {isLoading ? (
            <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
          ) : (
            <div className="flex flex-col gap-1">
              {members.map(m => (
                <div key={m.userId} className="flex items-center gap-3 px-1 py-1.5">
                  <Avatar name={m.displayName} avatarUrl={m.avatarUrl} size={30} />
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium truncate" style={{ color: 'var(--color-text)' }}>
                      {m.displayName}
                    </div>
                    <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
                      {m.email}
                    </div>
                  </div>
                  <RoleLabel role={m.role} />
                </div>
              ))}
            </div>
          )}

          {canManage && (
            // Absolute path: this modal is rendered from routes at several
            // depths, and a relative one would resolve differently in each.
            <Link to={`/w/${wsId}/settings/people`} onClick={onClose}
                  className="text-sm inline-flex items-center gap-1.5 no-underline"
                  style={{ color: 'var(--color-brand)' }}>
              <Settings size={14} />
              Manage people, roles and invitations
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}
