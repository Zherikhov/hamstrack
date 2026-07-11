import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { apiVerifyEmail, apiMe } from '../api'
import { useAuthStore } from '../auth'

export default function VerifyEmailPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const { setToken, setUser } = useAuthStore()
  const [error, setError] = useState('')
  // The token is single-use — StrictMode's double effect run must not POST twice
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true

    const token = params.get('token')
    if (!token) {
      setError('This verification link is incomplete — the token is missing.')
      return
    }

    async function verify(token: string) {
      try {
        const { accessToken } = await apiVerifyEmail(token)
        setToken(accessToken)
        setUser(await apiMe())
        navigate('/workspaces', { replace: true })
      } catch {
        setError('This verification link is invalid or has expired.')
      }
    }
    verify(token)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="h-full flex items-center justify-center" style={{ background: 'var(--color-surface)' }}>
      <div className="w-full flex flex-col" style={{ maxWidth: 380, padding: '0 16px' }}>
        <div className="mb-8 text-center">
          <h1
            className="font-display font-bold"
            style={{ fontSize: 28, color: 'var(--color-text)', letterSpacing: '-0.5px' }}
          >
            Hamstrack
          </h1>
        </div>

        <div
          className="rounded-xl border p-6 text-center"
          style={{
            background: 'white',
            borderColor: 'var(--color-border)',
            boxShadow: '0 1px 4px rgba(28,27,25,0.06)',
          }}
        >
          {error ? (
            <>
              <p className="text-sm" style={{ color: 'var(--color-error)' }}>{error}</p>
              <p className="text-sm mt-3" style={{ color: 'var(--color-text-muted)' }}>
                You can request a fresh link from the{' '}
                <Link to="/login" style={{ color: 'var(--color-brand)' }} className="font-medium hover:underline">
                  sign-in page
                </Link>
                {' '}— try signing in and use “Resend verification email”.
              </p>
            </>
          ) : (
            <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>
              verifying your email…
            </span>
          )}
        </div>
      </div>
    </div>
  )
}
