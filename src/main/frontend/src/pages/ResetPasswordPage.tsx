import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { apiResetPassword, ApiResponseError } from '../api'
import { passwordLengthError } from '../lib/limits'
import { Button, Input } from '../components/ui'

/**
 * Sets a password from a one-time token. Serves both the password-reset email
 * link and admin-generated account setup links (both land on
 * /reset-password?token=). After success the user signs in normally.
 *
 * <p><strong>No `maxLength` on either password field</strong>, for the reason
 * spelled out on `RegisterPage`: the server's bound is 72 UTF-8 bytes, which
 * `maxLength` cannot count, and a browser silently truncates a *paste* to fit
 * `maxLength` — inside a field that renders dots, so nothing tells the owner
 * their password manager's value was clipped. {@link passwordLengthError}
 * refuses in bytes instead, next to the field, and never shortens anything.
 *
 * <p><strong>The 400 rewrite below is keyed on which field the server named.</strong>
 * A 400 here used to mean exactly one thing — a bad token — so it was rendered
 * as "this link is invalid or has expired". Since HD-171 the same status also
 * answers an over-long `newPassword`, and telling that user their link expired
 * sends them to fetch a new one that will fail identically.
 */
export default function ResetPasswordPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''

  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [passwordFieldError, setPasswordFieldError] = useState('')
  const [loading, setLoading] = useState(false)
  const [done, setDone] = useState(false)

  const mismatch = confirm.length > 0 && password !== confirm
  const tooLong = passwordLengthError(password)
  const passwordError = tooLong ?? passwordFieldError

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (password !== confirm) {
      setError('Passwords do not match')
      return
    }
    setError('')
    setPasswordFieldError('')
    setLoading(true)
    try {
      await apiResetPassword(token, password)
      setDone(true)
    } catch (err: unknown) {
      // The server names the field it refused. A 400 naming `newPassword` is a
      // property of what was typed and belongs beside the input; a 400 that
      // names nothing (or names `token`) is the expired/garbled link.
      const named = err instanceof ApiResponseError ? err.errors?.newPassword : undefined
      if (named) {
        setPasswordFieldError(named)
      } else {
        setError(
          err instanceof ApiResponseError && err.status === 400
            ? 'This link is invalid or has expired. Ask your administrator for a new one.'
            : err instanceof Error ? err.message : 'Could not set your password',
        )
      }
    } finally {
      setLoading(false)
    }
  }

  const shell = (children: React.ReactNode, subtitle?: string) => (
    <div className="h-full flex items-center justify-center" style={{ background: 'var(--color-surface)' }}>
      <div className="w-full flex flex-col" style={{ maxWidth: 380, padding: '0 16px' }}>
        <div className="mb-8 text-center">
          <h1 className="font-display font-bold" style={{ fontSize: 28, color: 'var(--color-text)', letterSpacing: '-0.5px' }}>
            Hamstrack
          </h1>
          {subtitle && <p className="text-sm mt-1" style={{ color: 'var(--color-text-muted)' }}>{subtitle}</p>}
        </div>
        <div
          className="rounded-xl border p-6 flex flex-col gap-4"
          style={{ background: 'white', borderColor: 'var(--color-border)', boxShadow: '0 1px 4px rgba(28,27,25,0.06)' }}
        >
          {children}
        </div>
      </div>
    </div>
  )

  if (!token) {
    return shell(
      <p className="text-sm text-center" style={{ color: 'var(--color-error)' }}>
        This link is incomplete — the token is missing.
      </p>,
    )
  }

  if (done) {
    return shell(
      <div className="text-center flex flex-col gap-4">
        <div
          className="mx-auto w-10 h-10 rounded-full flex items-center justify-center"
          style={{ background: 'var(--color-brand)', color: 'white', fontSize: 20 }}
        >
          ✓
        </div>
        <h2 className="font-semibold text-lg">Password set</h2>
        <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
          Your password is ready. Sign in to continue.
        </p>
        <Button variant="primary" onClick={() => navigate('/login')} className="w-full justify-center">
          Go to sign in
        </Button>
      </div>,
    )
  }

  return shell(
    <>
      <form onSubmit={submit} className="flex flex-col gap-3">
        <Input
          label="New password"
          type="password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          placeholder="••••••••"
          autoComplete="new-password"
          minLength={8}
          required
          autoFocus
          error={passwordError}
        />
        <Input
          label="Confirm password"
          type="password"
          value={confirm}
          onChange={e => setConfirm(e.target.value)}
          placeholder="••••••••"
          autoComplete="new-password"
          required
          error={mismatch ? 'Passwords do not match' : undefined}
        />
        {error && <p className="text-xs px-1" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <Button
          type="submit"
          variant="primary"
          loading={loading}
          disabled={!password || !confirm || mismatch || !!tooLong}
          className="w-full justify-center mt-1"
        >
          Set password
        </Button>
      </form>
      <p className="text-center text-sm" style={{ color: 'var(--color-text-muted)' }}>
        <Link to="/login" style={{ color: 'var(--color-brand)' }} className="font-medium hover:underline">
          Back to sign in
        </Link>
      </p>
    </>,
    'Choose a password',
  )
}
