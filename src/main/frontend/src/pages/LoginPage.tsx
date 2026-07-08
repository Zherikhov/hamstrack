import { useState } from 'react'
import { useNavigate, Link } from 'react-router'
import { apiLogin, apiMe } from '../api'
import { useAuthStore } from '../auth'
import { Button, Input } from '../components/ui'

export default function LoginPage() {
  const navigate = useNavigate()
  const { setToken, setUser } = useAuthStore()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { accessToken } = await apiLogin(email, password)
      setToken(accessToken)
      const user = await apiMe()
      setUser(user)
      navigate('/workspaces')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Invalid credentials')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="h-full flex items-center justify-center" style={{ background: 'var(--color-surface)' }}>
      <div
        className="w-full flex flex-col"
        style={{ maxWidth: 380, padding: '0 16px' }}
      >
        {/* Logo */}
        <div className="mb-8 text-center">
          <h1
            className="font-display font-bold"
            style={{ fontSize: 28, color: 'var(--color-text)', letterSpacing: '-0.5px' }}
          >
            Hamstrack
          </h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-muted)' }}>
            Sign in to your workspace
          </p>
        </div>

        {/* Card */}
        <div
          className="rounded-xl border p-6 flex flex-col gap-4"
          style={{
            background: 'white',
            borderColor: 'var(--color-border)',
            boxShadow: '0 1px 4px rgba(28,27,25,0.06)',
          }}
        >
          <form onSubmit={submit} className="flex flex-col gap-3">
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
              autoComplete="current-password"
              required
            />
            {error && (
              <p className="text-xs px-1" style={{ color: 'var(--color-error)' }}>{error}</p>
            )}
            <Button
              type="submit"
              variant="primary"
              loading={loading}
              disabled={!email || !password}
              className="w-full justify-center mt-1"
            >
              Sign in
            </Button>
          </form>
        </div>

        <p className="text-center text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>
          No account?{' '}
          <Link to="/register" style={{ color: 'var(--color-brand)' }} className="font-medium hover:underline">
            Create one
          </Link>
        </p>
      </div>
    </div>
  )
}
