import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Plus, Mail, ChevronRight } from 'lucide-react'
import { apiListInvites, apiOnboardingCreateTeam } from '../../api'
import { useAuthStore } from '../../auth'
import { WelcomeShell } from './shell'

/**
 * First-login choice (Cloud): create your own team or join one you were
 * invited to. "Create a team" provisions a demo starter workspace and drops the
 * user into the workspaces area to make their real team; joining gets no demo.
 */
export default function WelcomePage() {
  const navigate = useNavigate()
  const markOnboarded = useAuthStore(s => s.markOnboarded)
  const user = useAuthStore(s => s.user)
  const [busy, setBusy] = useState(false)

  // Surfaces how many invites are waiting so "Join a team" isn't a dead guess
  const { data: invites = [] } = useQuery({ queryKey: ['invites'], queryFn: apiListInvites })

  // "Create a team": seed the demo starter + complete onboarding, then land on
  // the workspaces area (demo visible + "New workspace"). Completing onboarding
  // is required so the RequireAuth gate lets them onto /workspaces.
  async function createTeam() {
    if (busy) return
    setBusy(true)
    try {
      await apiOnboardingCreateTeam()
      markOnboarded()
      navigate('/workspaces')
    } catch {
      setBusy(false)
    }
  }

  const firstName = user?.displayName?.split(' ')[0]

  return (
    <WelcomeShell>
      <div className="mb-8">
        <h1 className="font-display font-bold" style={{ fontSize: 34, letterSpacing: '-0.6px', color: 'var(--color-text)' }}>
          Welcome{firstName ? `, ${firstName}` : ''}
        </h1>
        <p className="text-base mt-2" style={{ color: 'var(--color-text-secondary)' }}>
          A team (workspace) is where your projects and issues live. Start your own or join one you've been invited to.
        </p>
      </div>

      <div className="flex flex-col gap-3">
        <ChoiceCard
          icon={<Plus size={20} />}
          title="Create a team"
          description="Set up your own workspace — you'll be its owner and can invite others. Comes with a demo project to explore."
          onClick={createTeam}
          disabled={busy}
        />
        <ChoiceCard
          icon={<Mail size={20} />}
          title="Join a team"
          description={
            invites.length > 0
              ? `You have ${invites.length} pending invitation${invites.length !== 1 ? 's' : ''}.`
              : 'Accept an invitation sent to your email address.'
          }
          badge={invites.length > 0 ? invites.length : undefined}
          onClick={() => navigate('/welcome/invites')}
          disabled={busy}
        />
      </div>
    </WelcomeShell>
  )
}

function ChoiceCard({ icon, title, description, onClick, badge, disabled }: {
  icon: React.ReactNode
  title: string
  description: string
  onClick: () => void
  badge?: number
  disabled?: boolean
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="w-full text-left rounded-xl border p-5 flex items-center gap-4 transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
      style={{ background: 'white', borderColor: 'var(--color-border)' }}
      onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--color-brand)')}
      onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--color-border)')}
    >
      <span className="flex items-center justify-center rounded-lg flex-shrink-0"
            style={{ width: 44, height: 44, background: '#E7F0EE', color: 'var(--color-brand-ink)' }}>
        {icon}
      </span>
      <span className="flex-1 min-w-0">
        <span className="flex items-center gap-2">
          <span className="font-semibold" style={{ fontSize: 16, color: 'var(--color-text)' }}>{title}</span>
          {badge !== undefined && (
            <span className="text-xs font-semibold px-2 py-0.5 rounded-full"
                  style={{ background: 'var(--color-brand)', color: 'white' }}>{badge}</span>
          )}
        </span>
        <span className="block text-sm mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>{description}</span>
      </span>
      <ChevronRight size={18} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} />
    </button>
  )
}
