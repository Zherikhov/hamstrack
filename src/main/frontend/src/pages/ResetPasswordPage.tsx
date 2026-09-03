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
 *
 * <p><strong>A dead link sends the reader to the forgot-password form, not to a
 * person</strong> (HD-183). The expired-link sentence used to end "Ask your
 * administrator for a new one", which failed the project's rule that *a refusal
 * may only prescribe an action its reader can perform* — in both directions at
 * once. On Cloud the reader hears "administrator" as their workspace owner, who
 * has no mechanism to mint a reset link for anybody; the only person who does is
 * a **system** administrator, and a Cloud tenant cannot reach one. And even on a
 * self-hosted install where that person exists, sending the reader to a human is
 * wrong when the remedy is theirs to take. The replacement names that remedy and
 * links straight to it, in one sentence that is true in DC and in Cloud — this
 * page deliberately does **not** branch on deployment mode, for the same reason
 * `GlobalExceptionHandler` ships one mode-agnostic sentence rather than two.
 *
 * <p>The click only became possible with HD-183's other half: `POST
 * /api/auth/forgot-password` had existed since the auth slice shipped and had no
 * UI at all — no form, no route, no link from sign-in — so until
 * {@link ForgotPasswordPage} there was genuinely nothing here to link to, and the
 * old sentence was the more honest half of a product with no self-service reset.
 */
export default function ResetPasswordPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''

  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  // The dead-link refusal is state rather than a string, because its remedy is a
  // link the reader clicks — a sentence that carries an <a> cannot live in the
  // same slot as a plain message.
  const [linkRefused, setLinkRefused] = useState(false)
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
    setLinkRefused(false)
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
      } else if (err instanceof ApiResponseError && err.status === 400) {
        setLinkRefused(true)
      } else {
        setError(err instanceof Error ? err.message : 'Could not set your password')
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

  // The remedy for every dead link on this page, written once: the reader asks
  // for another one themselves. Both callers state what is wrong with the link
  // they arrived on and then end in this clause, so the two refusals differ only
  // in the diagnosis and never in the action.
  const requestNewLink = (
    <Link to="/forgot-password" style={{ color: 'var(--color-brand)' }} className="font-medium hover:underline">
      request a new one
    </Link>
  )

  if (!token) {
    return shell(
      <p className="text-sm text-center" style={{ color: 'var(--color-error)' }}>
        This link is incomplete — the token is missing, so {requestNewLink}.
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
        {/* Both refusals are `role="alert"`, like the sibling on ForgotPasswordPage:
            each appears only after a submit, in place of the confirmation the reader
            was waiting for, and neither moves focus. Without the live region a
            screen-reader user hears the button stop loading and is told nothing else
            — and on the dead-link branch what they miss is the link to the remedy,
            which is the whole of HD-183's fix. The two are mutually exclusive by
            construction (`submit` clears both, then sets exactly one), so there is
            never a second alert competing to be announced. */}
        {linkRefused && (
          <p role="alert" className="text-xs px-1" style={{ color: 'var(--color-error)' }}>
            This link is invalid or has expired — {requestNewLink}.
          </p>
        )}
        {error && <p role="alert" className="text-xs px-1" style={{ color: 'var(--color-error)' }}>{error}</p>}
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
