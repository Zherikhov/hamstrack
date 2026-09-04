import { useState } from 'react'
import { useNavigate, Link } from 'react-router'
import { ApiResponseError, apiRegister } from '../api'
import { useConfigStore } from '../config'
import { passwordLengthError } from '../lib/limits'
import { Button, Checkbox, Input } from '../components/ui'

/** The request fields this form has an inline slot for; anything else falls to the banner. */
const SHOWN_FIELDS = ['displayName', 'email', 'password']

/**
 * <strong>The password field carries no `maxLength`, deliberately</strong>
 * (HD-171 §11).
 *
 * The server's bound is 72 UTF-8 **bytes**, and `maxLength` counts UTF-16 code
 * units — so a `maxLength={72}` is simultaneously too loose (a Cyrillic
 * passphrase of 72 characters is 144 bytes and still refused, with 422) and
 * dangerous: a browser truncates a *paste* to fit `maxLength`, so a 90-character
 * password pasted from a password manager would be silently clipped, invisibly,
 * inside a field that renders dots. The account would then hold a password its
 * owner never chose and cannot see. A password is the one value that may never
 * be quietly shortened.
 *
 * So the value is kept whole and {@link passwordLengthError} refuses it in the
 * right unit, next to the field, before the request is sent. That check is
 * strictly stronger than the server's `@Size(max = 72)` (72 bytes implies at
 * most 72 units), so a submitted password can only fail the server's byte check
 * if this one was bypassed — and if it is, the 422's `detail` says so and the
 * banner below renders it.
 */
export default function RegisterPage() {
  const navigate = useNavigate()
  const termsRequired = useConfigStore((s) => s.config.termsAcceptanceRequired)
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [termsAccepted, setTermsAccepted] = useState(false)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const [done, setDone] = useState(false)

  // Local, in the server's own unit (bytes), so the refusal arrives while typing
  // rather than after submitting. A server-named `password` error is shown in
  // the same place, so one field has one error slot no matter who refused.
  const passwordError = passwordLengthError(password) ?? fieldErrors.password

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setFieldErrors({})
    setLoading(true)
    try {
      await apiRegister(email, displayName, password, termsAccepted)
      setDone(true)
    } catch (err: unknown) {
      // A validation 400 names its fields; render those beside the inputs. The
      // banner still fires for anything this form has no slot for (a class-level
      // rule, `termsAccepted`, a 409, a 422, a network failure) — a refusal with
      // nowhere to land must never become an empty screen.
      const named = err instanceof ApiResponseError ? err.errors : undefined
      setFieldErrors(named ?? {})
      const allShown = named && Object.keys(named).every(f => SHOWN_FIELDS.includes(f))
      setError(allShown ? '' : err instanceof Error ? err.message : 'Registration failed')
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
              maxLength={100}
              required
              error={fieldErrors.displayName}
            />
            <Input
              label="Email"
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@company.com"
              autoComplete="email"
              maxLength={255}
              required
              error={fieldErrors.email}
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
              error={passwordError}
            />
            {termsRequired && (
              <Checkbox
                checked={termsAccepted}
                onChange={e => setTermsAccepted(e.target.checked)}
                label={
                  <>
                    I agree to the{' '}
                    <Link to="/terms" target="_blank" style={{ color: 'var(--color-brand-ink)' }} className="font-medium hover:underline">
                      Terms of Service
                    </Link>{' '}
                    and{' '}
                    <Link to="/privacy" target="_blank" style={{ color: 'var(--color-brand-ink)' }} className="font-medium hover:underline">
                      Privacy Policy
                    </Link>
                  </>
                }
              />
            )}
            {error && (
              <p className="text-xs px-1" style={{ color: 'var(--color-error-ink)' }}>{error}</p>
            )}
            <Button
              type="submit"
              variant="primary"
              loading={loading}
              disabled={!email || !displayName || !password || !!passwordLengthError(password)
                        || (termsRequired && !termsAccepted)}
              className="w-full justify-center mt-1"
            >
              Create account
            </Button>
          </form>
        </div>

        <p className="text-center text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>
          Already have an account?{' '}
          <Link to="/login" style={{ color: 'var(--color-brand-ink)' }} className="font-medium hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  )
}
