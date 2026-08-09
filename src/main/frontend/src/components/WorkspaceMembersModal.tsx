import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X, UserPlus, Check } from 'lucide-react'
import { apiListWorkspaceMembers, apiInviteWorkspaceMember } from '../api'
import { Avatar, Badge, Button, Input, Select } from './ui'

interface Props {
  wsId: string
  // Whether the current user may invite (workspace OWNER/ADMIN). The invite form
  // is hidden otherwise; the backend enforces the permission regardless.
  canInvite: boolean
  onClose: () => void
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const roleColors: Record<string, string> = {
  OWNER: 'var(--color-brand)',
  ADMIN: 'var(--color-pending)',
  MEMBER: 'var(--color-sandbox)',
}

export default function WorkspaceMembersModal({ wsId, canInvite, onClose }: Props) {
  const qc = useQueryClient()
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<'MEMBER' | 'ADMIN'>('MEMBER')
  const [error, setError] = useState('')
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  const { data: members = [], isLoading } = useQuery({
    queryKey: ['workspace-members', wsId],
    queryFn: () => apiListWorkspaceMembers(wsId),
  })

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setSentTo(null)
    const trimmed = email.trim()
    if (!EMAIL_RE.test(trimmed)) {
      setError('Enter a valid email address')
      return
    }
    setSending(true)
    try {
      await apiInviteWorkspaceMember(wsId, { email: trimmed, role })
      // Members don't change until the invite is accepted, but refetch keeps the
      // list correct if the invitee already exists / accepts instantly elsewhere.
      await qc.invalidateQueries({ queryKey: ['workspace-members', wsId] })
      setSentTo(trimmed)
      setEmail('')
      setRole('MEMBER')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to send invite')
    } finally {
      setSending(false)
    }
  }

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
    <div style={overlayStyle} onClick={onClose}>
      <div style={panelStyle} onClick={e => e.stopPropagation()}>
        <div
          className="flex items-center justify-between px-5 py-4 border-b flex-shrink-0"
          style={{ borderColor: 'var(--color-border)' }}
        >
          <span className="font-semibold text-sm" style={{ color: 'var(--color-text)' }}>
            Workspace members
          </span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 transition-opacity">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <div className="overflow-y-auto p-5 flex flex-col gap-5">
          {canInvite && (
            <form onSubmit={submit} className="flex flex-col gap-3">
              <div className="flex items-end gap-2">
                <div className="flex-1 min-w-0">
                  <Input
                    label="Invite by email"
                    type="email"
                    value={email}
                    onChange={e => { setEmail(e.target.value); setSentTo(null); setError('') }}
                    placeholder="name@company.com"
                  />
                </div>
                <div style={{ width: 120, flexShrink: 0 }}>
                  <Select
                    label="Role"
                    value={role}
                    onChange={e => setRole(e.target.value as 'MEMBER' | 'ADMIN')}
                  >
                    <option value="MEMBER">Member</option>
                    <option value="ADMIN">Admin</option>
                  </Select>
                </div>
              </div>
              {error && (
                <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>
              )}
              {sentTo && (
                <p className="text-xs inline-flex items-center gap-1" style={{ color: 'var(--color-brand)' }}>
                  <Check size={13} /> Invite sent to {sentTo}
                </p>
              )}
              <div className="flex justify-end">
                <Button variant="primary" size="sm" type="submit" loading={sending} disabled={!email.trim()}>
                  <UserPlus size={14} />
                  Send invite
                </Button>
              </div>
            </form>
          )}

          <div className="flex flex-col gap-2">
            <h3 className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--color-text-muted)' }}>
              Members{members.length > 0 ? ` · ${members.length}` : ''}
            </h3>
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
                    <Badge label={m.role} color={roleColors[m.role]} />
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* TODO: pending-invite listing and member role/removal need new backend
              endpoints (GET workspace invites, PATCH/DELETE members) — follow-up. */}
        </div>
      </div>
    </div>
  )
}
