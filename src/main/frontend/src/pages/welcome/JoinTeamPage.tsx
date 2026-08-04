import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Mail } from 'lucide-react'
import { apiAcceptInvite, apiDeclineInvite, apiListInvites, apiOnboardingCreateTeam } from '../../api'
import { useAuthStore } from '../../auth'
import { Button } from '../../components/ui'
import { WelcomeShell } from './shell'

const ROLE_LABEL: Record<string, string> = { OWNER: 'Owner', ADMIN: 'Admin', MEMBER: 'Member' }

/** Onboarding step: accept a pending invitation addressed to your email. */
export default function JoinTeamPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const markOnboarded = useAuthStore(s => s.markOnboarded)
  const user = useAuthStore(s => s.user)
  const [error, setError] = useState('')

  const { data: invites = [], isLoading } = useQuery({ queryKey: ['invites'], queryFn: apiListInvites })

  const accept = useMutation({
    mutationFn: (id: string) => apiAcceptInvite(id),
    onSuccess: async (ws) => {
      markOnboarded()
      await qc.invalidateQueries({ queryKey: ['workspaces'] })
      navigate(`/w/${ws.id}`)
    },
    onError: e => setError(e instanceof Error ? e.message : 'Could not accept the invitation'),
  })

  const decline = useMutation({
    mutationFn: (id: string) => apiDeclineInvite(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['invites'] }),
    onError: e => setError(e instanceof Error ? e.message : 'Could not decline the invitation'),
  })

  const busyId = accept.isPending ? accept.variables : decline.isPending ? decline.variables : undefined

  // "Create your own team" from the empty state — same as the welcome screen's
  // Create choice: seed the demo starter + complete onboarding, then workspaces
  async function createOwnTeam() {
    try {
      await apiOnboardingCreateTeam()
      markOnboarded()
      navigate('/workspaces')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong')
    }
  }

  return (
    <WelcomeShell>
      <button onClick={() => navigate('/welcome')}
              className="inline-flex items-center gap-1.5 text-sm mb-5 cursor-pointer hover:underline"
              style={{ color: 'var(--color-text-secondary)', background: 'transparent' }}>
        <ArrowLeft size={15} /> Back
      </button>

      <h1 className="font-display font-bold" style={{ fontSize: 28, letterSpacing: '-0.5px', color: 'var(--color-text)' }}>
        Join a team
      </h1>
      <p className="text-base mt-2 mb-6" style={{ color: 'var(--color-text-secondary)' }}>
        Invitations sent to your email address appear here.
      </p>

      {error && <p className="text-sm mb-3" style={{ color: 'var(--color-error)' }}>{error}</p>}

      {isLoading ? (
        <p className="mono text-sm py-8" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
      ) : invites.length === 0 ? (
        <div className="rounded-xl border border-dashed p-10 text-center"
             style={{ borderColor: 'var(--color-border-2)' }}>
          <Mail size={30} className="mx-auto mb-3 opacity-30" style={{ color: 'var(--color-text-muted)' }} />
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            No pending invitations for <strong>{user?.email}</strong>.
          </p>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-muted)' }}>
            Ask a team admin to invite you, or{' '}
            <button onClick={createOwnTeam}
                    className="font-medium cursor-pointer hover:underline"
                    style={{ color: 'var(--color-brand)', background: 'transparent' }}>
              create your own team
            </button>.
          </p>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {invites.map(inv => (
            <div key={inv.id}
                 className="rounded-xl border p-4 flex items-center gap-3"
                 style={{ background: 'white', borderColor: 'var(--color-border)' }}>
              <span className="flex items-center justify-center rounded-lg font-display font-bold text-white flex-shrink-0"
                    style={{ width: 40, height: 40, background: 'var(--color-brand)' }}>
                {inv.workspaceName[0]?.toUpperCase()}
              </span>
              <div className="flex-1 min-w-0">
                <div className="font-medium text-sm truncate" style={{ color: 'var(--color-text)' }}>
                  {inv.workspaceName}
                </div>
                <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-muted)' }}>
                  Invited by {inv.invitedByName} · joining as {ROLE_LABEL[inv.role] ?? inv.role}
                </div>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                <Button variant="ghost" size="sm" disabled={busyId === inv.id}
                        onClick={() => { setError(''); decline.mutate(inv.id) }}>
                  Decline
                </Button>
                <Button variant="primary" size="sm" loading={accept.isPending && accept.variables === inv.id}
                        disabled={busyId === inv.id}
                        onClick={() => { setError(''); accept.mutate(inv.id) }}>
                  Accept
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}
    </WelcomeShell>
  )
}
