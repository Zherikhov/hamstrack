import { useState } from 'react'
import { useNavigate, Link } from 'react-router'
import { apiRegister } from '../api'
import { useConfigStore } from '../config'
import { Button, Checkbox, Input } from '../components/ui'

export default function RegisterPage() {
  const navigate = useNavigate()
  const termsRequired = useConfigStore((s) => s.config.termsAcceptanceRequired)
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [termsAccepted, setTermsAccepted] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [done, setDone] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await apiRegister(email, displayName, password, termsAccepted)
      setDone(true)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Registration failed')
    } finally {
      setLoading(false)
    }
  }

  if (done) {
    return (
      <div className="h-full flex items-center justify-center" style={{ background: 'var(--color-surface)' }}>
        <div
          className="rounded-xl border p-8 text-center flex flex-col gap-4"
          style={{ maxWidth: 380, width: '100%', background: 'white', borderColor: 'var(--color-border)' }}
        >
          <div
            className="mx-auto w-10 h-10 rounded-full flex items-center justify-center"
            style={{ background: 'var(--color-brand)', color: 'white', fontSize: 20 }}
          >
            ✓
          </div>
          <h2 className="font-semibold text-lg">Check your email</h2>
          <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
            We sent a verification link to <strong>{email}</strong>. Click it to activate your account.
          </p>
          <Button variant="ghost" onClick={() => navigate('/login')} className="w-full justify-center">
            Back to sign in
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="h-full flex items-center justify-center" style={{ background: 'var(--color-surface)' }}>
      <div className="w-full flex flex-col" style={{ maxWidth: 380, padding: '0 16px' }}>
        <div className="mb-8 text-center">
          <h1 className="font-display font-bold" style={{ fontSize: 28, color: 'var(--color-text)', letterSpacing: '-0.5px' }}>
            Hamstrack
          </h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-muted)' }}>
            Create your account
          </p>
        </div>

        <div
          className="rounded-xl border p-6 flex flex-col gap-4"
          style={{ background: 'white', borderColor: 'var(--color-border)', boxShadow: '0 1px 4px rgba(28,27,25,0.06)' }}
        >
          <form onSubmit={submit} className="flex flex-col gap-3">
            <Input
              label="Display name"
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              placeholder="Your name"
              autoComplete="name"
              required
            />
            <Input
              label="Email"
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@company.com"
              autoComplete="email"
              required
            />
            <Input
              label="Password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="••••••••"
              autoComplete="new-password"
              minLength={8}
              required
            />
            {termsRequired && (
              <Checkbox
                checked={termsAccepted}
                onChange={e => setTermsAccepted(e.target.checked)}
                label={
                  <>
                    I agree to the{' '}
                    <Link to="/terms" target="_blank" style={{ color: 'var(--color-brand)' }} className="font-medium hover:underline">
                      Terms of Service
                    </Link>{' '}
                    and{' '}
                    <Link to="/privacy" target="_blank" style={{ color: 'var(--color-brand)' }} className="font-medium hover:underline">
                      Privacy Policy
                    </Link>
                  </>
                }
              />
            )}
            {error && (
              <p className="text-xs px-1" style={{ color: 'var(--color-error)' }}>{error}</p>
            )}
            <Button
              type="submit"
              variant="primary"
              loading={loading}
              disabled={!email || !displayName || !password || (termsRequired && !termsAccepted)}
              className="w-full justify-center mt-1"
            >
              Create account
            </Button>
          </form>
        </div>

        <p className="text-center text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>
          Already have an account?{' '}
          <Link to="/login" style={{ color: 'var(--color-brand)' }} className="font-medium hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  )
}
