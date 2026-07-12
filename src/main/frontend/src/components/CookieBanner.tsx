import { useState } from 'react'
import { Link } from 'react-router'

const DISMISSED_KEY = 'hamstrack.cookie-notice-dismissed'

// Informational only: the app sets a single strictly-necessary cookie
// (refresh_token), which needs no consent under ePrivacy — just transparency
export default function CookieBanner() {
  const [dismissed, setDismissed] = useState(() => localStorage.getItem(DISMISSED_KEY) === '1')

  if (dismissed) return null

  function dismiss() {
    localStorage.setItem(DISMISSED_KEY, '1')
    setDismissed(true)
  }

  return (
    <div
      className="fixed flex flex-col gap-3"
      style={{
        left: 16,
        bottom: 16,
        zIndex: 60,
        maxWidth: 380,
        background: 'var(--color-ink)',
        color: 'white',
        borderRadius: 'var(--radius-lg)',
        padding: '16px 18px',
        boxShadow: '0 8px 30px rgba(28,27,25,0.35)',
        animation: 'cookie-banner-in 200ms ease-out',
      }}
    >
      <p className="text-sm" style={{ margin: 0, lineHeight: 1.5 }}>
        Hamstrack only uses strictly necessary cookies — no tracking.{' '}
        <Link to="/cookies" className="underline" style={{ color: 'white' }}>
          Cookie Policy
        </Link>
      </p>
      <button
        onClick={dismiss}
        className="text-sm font-medium px-4 py-1.5 rounded self-start cursor-pointer transition-colors"
        style={{ background: 'var(--color-brand)', color: 'white', border: 'none' }}
      >
        Got it
      </button>
    </div>
  )
}
