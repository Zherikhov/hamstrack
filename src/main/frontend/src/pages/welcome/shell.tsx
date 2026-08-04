import { useNavigate } from 'react-router'
import type { ReactNode } from 'react'
import { apiLogout } from '../../api'
import { useAuthStore } from '../../auth'

/**
 * Shared layout for the first-login onboarding screens (welcome / create /
 * join). Comfortable, marketing-register spacing per DESIGN.md — warm-neutral
 * surface, Cabinet Grotesk wordmark. A sign-out affordance lets someone who
 * landed on the wrong account back out.
 */
export function WelcomeShell({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const { user, clear } = useAuthStore()

  async function signOut() {
    try { await apiLogout() } catch { /* already gone */ }
    clear()
    navigate('/login')
  }

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--color-surface)' }}>
      <header className="flex items-center justify-between px-6 py-4">
        <span className="font-display font-bold" style={{ fontSize: 20, letterSpacing: '-0.5px', color: 'var(--color-text)' }}>
          Hamstrack
        </span>
        <div className="flex items-center gap-3 text-sm">
          {user && <span style={{ color: 'var(--color-text-muted)' }}>{user.email}</span>}
          <button onClick={signOut} className="cursor-pointer hover:underline"
                  style={{ color: 'var(--color-text-secondary)', background: 'transparent' }}>
            Sign out
          </button>
        </div>
      </header>
      <div className="flex-1 overflow-y-auto flex items-start justify-center px-4"
           style={{ paddingTop: 64, paddingBottom: 48 }}>
        <div className="w-full" style={{ maxWidth: 640 }}>{children}</div>
      </div>
    </div>
  )
}
