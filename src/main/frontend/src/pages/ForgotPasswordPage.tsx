import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { apiForgotPassword, ApiResponseError } from '../api'
import { Button, Input } from '../components/ui'

/**
 * Asks for a password-reset link by email — the one screen where a locked-out
 * user's remedy is a thing they can do themselves.
 *
 * <h2>Why this page exists (HD-183)</h2>
 *
 * `POST /api/auth/forgot-password` has been in the product since the auth slice
 * shipped and had <strong>no UI at all</strong>: no form, no route, and no link
 * from the sign-in page. So the only reachable path back into a locked account
 * was a one-time link somebody else had to mint — which on a self-hosted install
 * means the system administrator's <em>Regenerate setup link</em> control, and on
 * Cloud means nobody the reader can reach. That is what made the expired-link
 * sentence on {@link ResetPasswordPage} a dead end in both directions at once:
 * it named a person who cannot help, for an action the reader could have taken
 * in one click, had the click existed.
 *
 * <h2>The answer is uniform, and this page must not undo that</h2>
 *
 * The server spends its per-address ceiling <em>before</em> the account lookup and
 * records it either way, then answers the same `200` whether or not the address is
 * registered. So the <strong>body is identical</strong>, and so is the sentence
 * below — which is rendered from the submission, never from the response. Any
 * wording that distinguished the two cases ("we couldn't find that account") would
 * rebuild the enumeration oracle the endpoint is built to refuse, and so would
 * rendering the ceiling's refusal — which is why that refusal is silent: it sends
 * no mail and still answers the same 200, so not even "refused" can be read as
 * "registered". The one refusal safe to render is the per-IP `429`, which is about
 * this browser's request rate and not about the address.
 *
 * <strong>The two branches are not equal in duration, and this page does not claim
 * they are.</strong> The known branch pays a `SecureRandom` token, a SHA-256 hash
 * and a `password_resets` insert the unknown branch never does, and
 * `FailedEmailWriter`'s javadoc records an address-correlated park on that branch
 * alone (bounded only by the unset Hikari connection-timeout) which it lists as
 * <em>still open</em>. What bounds sampling here is the <strong>per-address
 * ceiling</strong>, not equal work — a strictly weaker property than constant time,
 * and the one that is true.
 *
 * <h2>Nothing here branches on deployment mode</h2>
 *
 * The endpoint, its wording and its remedy are identical in DC and Cloud — and so
 * is the case where no mail ever arrives, because the discriminator is <em>does
 * this installation send mail</em>, not `dc` versus `cloud`. A self-hosted install
 * with `MAIL_HOST` unset is a supported provisioning model rather than a
 * misconfiguration: the admin console hands out one-time setup links and says so
 * ("no email is sent"). A Cloud instance with a broken relay fails identically. In
 * both, the send fails asynchronously, retries and dead-letters with <strong>no
 * signal to this browser</strong> — so the page cannot detect it, and the copy
 * therefore says it as one sentence true in both modes. Inventing a mode branch in
 * copy is the defect HD-183 exists to close, not a fix for it.
 *
 * <h2>Why "ask for another link" is not the sentence here</h2>
 *
 * It was the ticket's own defect, one line down. On a mail-less install every
 * repeat fails identically; and even with working SMTP, reset mail is capped per
 * address (a cooldown, then a handful per window) and refused <em>silently</em>, so
 * a reader who follows that instruction five times guarantees themselves no mail
 * for the rest of the window while the page says a link is on its way each time.
 * The button stays — it is the self-service remedy, and the first click is the one
 * that usually works — but the sentence beside it no longer prescribes repetition,
 * and the fallback after it names something that can actually be done.
 *
 * <h2>Why the link sentence is plural</h2>
 *
 * Because the button below it mints a <em>set</em>. Each link is single-use and lives an hour,
 * and the per-address ceilings allow several outstanding at once, so a reader who has just
 * clicked <em>Send another link</em> twice holds three of them. Spending any one now retires
 * the whole outstanding set: `AuthService.resetPassword` sweeps every other unused row beside
 * the refresh-token purge, because a sibling copied out of the inbox but never used would
 * otherwise outlive the very reset performed to defeat it. So the sentence states what the
 * server does to the <strong>set</strong>, rather than what is true of one token — "the link
 * works once" was true of each link and understated the rule at precisely the spot where the
 * reader is one click away from owning another.
 *
 * The sweep is blind to which door minted a row, so an administrator's seven-day setup link
 * goes with the rest — deliberately, it being the longer-lived instance of the same
 * account-takeover capability, and sparing it would invert the risk ordering. "All the others"
 * is written wide enough to cover that link too, and claims nothing about how long any of them
 * would otherwise have lasted. Nor is it directional: the one you spend need not be the newest,
 * and whichever it is, the rest stop working.
 *
 * This is a hardcoded server rule with no property behind it and no profile branch, so — like
 * every other sentence on this page — it is one sentence, identical in DC and Cloud.
 *
 * <h2>Why no sentence here names a wait</h2>
 *
 * The token's hour is a constant in `AuthService` (`plusSeconds(3600)`). The gap between two
 * requests for one address is <strong>not</strong>: it is
 * `app.auth-mail.recipient-cooldown-minutes` (`AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES`,
 * `@Min(1) @Max(14)`, default 1), so "give it a minute" was true only on an install that had
 * left the default alone — and on any other, the reader who obeyed it asked again, was refused
 * <em>silently</em>, and was told a second time that a link was on its way. That is not a near
 * miss of this ticket's defect; it is that defect, re-shipped inside its own fix, for the third
 * time. So the rule is mechanical rather than editorial: <strong>a duration may be written on
 * this page only where the number is a constant in the server's source</strong>. Everything an
 * operator can move is stated as a property — "spaced out", "wait a while", "a cooldown, then a
 * handful per window" — which stays true at every setting of it, including settings that do
 * not exist yet. The paragraph itself carries that instruction to whoever edits it next, and
 * `ResetPasswordPage.deadLink.test.tsx` reads the rendered paragraph and fails on a unit of
 * time appearing in it, so the seal does not depend on the comment being read.
 *
 * <h2>The mail-less fallback uses the product's own word: <em>setup link</em></h2>
 *
 * That sentence is written to be <strong>relayed</strong> — the reader forwards it to whoever
 * runs the installation, who then has to find the control it describes. The control is
 * <em>Regenerate setup link</em> in the admin console, and `docs/self-hosting.md`,
 * `docs/api-dc.md` and `openapi.yaml` all call the thing it mints a <em>setup link</em>. An
 * earlier draft said "sign-in link", which is worse than merely off-vocabulary: this product has
 * no magic-link sign-in at all, so it named a capability that does not exist, to an operator
 * whose screen shows nothing by that name. Copy that will be quoted at a second person must use
 * the word that second person can search for.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  // The form unmounts when the request resolves, so without this the focus falls
  // to <body> and a screen-reader user is told nothing happened. Moving it onto
  // the confirmation (which is also a live region) is the whole fix — and it leaks
  // nothing, because the transition depends only on the request resolving, never
  // on what the answer was.
  const confirmationRef = useRef<HTMLDivElement>(null)
  useEffect(() => { if (sent) confirmationRef.current?.focus() }, [sent])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await apiForgotPassword(email)
      setSent(true)
    } catch (err: unknown) {
      // A 429 is the per-IP budget on /api/auth/*: waiting really is the remedy,
      // so the wait is named. Everything else falls back to the server's own
      // detail — never to a sentence claiming anything about the address.
      //
      // Gated on the STATUS, not merely on the presence of `retryAfter` (HD-183):
      // `Retry-After` is a general HTTP header, so a proxy answering 503 with one
      // during a deploy would otherwise render "too many requests from this device"
      // — blaming the reader for an outage they did not cause. A 503 falls through
      // to the server's own detail, which is the honest sentence.
      const seconds = err instanceof ApiResponseError && err.status === 429
        ? err.retryAfter
        : undefined
      setError(
        seconds !== undefined
          ? `Too many requests from this device — try again in ${seconds} second${seconds === 1 ? '' : 's'}.`
          : err instanceof Error ? err.message : 'Could not send a reset link',
      )
    } finally {
      setLoading(false)
    }
  }

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
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-muted)' }}>
            {sent ? 'Check your inbox' : 'Reset your password'}
          </p>
        </div>

        <div
          className="rounded-xl border p-6 flex flex-col gap-4"
          style={{ background: 'white', borderColor: 'var(--color-border)', boxShadow: '0 1px 4px rgba(28,27,25,0.06)' }}
        >
          {sent ? (
            <div
              className="flex flex-col gap-3"
              role="status"
              ref={confirmationRef}
              tabIndex={-1}
              style={{ outline: 'none' }}
            >
              {/* Said about the submission, not about the account: the server
                  answers identically for an address it has never seen — which is
                  also why the second clause owns nothing ("all the others", never
                  "your other links"). */}
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                If <span className="mono" style={{ color: 'var(--color-text)' }}>{email}</span> has an
                account, a link to choose a new password is on its way. Each link works once and
                expires after an hour — and the first one you use turns off all the others.
              </p>
              {/* NO DURATION MAY BE NAMED IN THIS PARAGRAPH. The gap between two
                  requests for one address is an operator setting —
                  `app.auth-mail.recipient-cooldown-minutes`
                  (`AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES`, `@Min(1) @Max(14)`), so any
                  number written here is false on every install that raised it, and the
                  reader who obeys it asks again, receives nothing, and is told once more
                  that a link is on its way — verbatim the defect HD-183 opened with.
                  Both clauses are therefore properties ("spaced out", "wait a while"),
                  never quantities. "An hour" in the paragraph above is a different claim:
                  the token's own lifetime, a hardcoded `plusSeconds(3600)` in
                  `AuthService` with no property behind it — which is why the seal in
                  `ResetPasswordPage.deadLink.test.tsx` reads this paragraph alone rather
                  than the whole screen. */}
              <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                Nothing yet? Check the spam folder. Requests for the same address are spaced out, so
                asking again straight away sends nothing — wait a while before trying again.
              </p>
              <Button variant="secondary" onClick={() => setSent(false)} className="w-full justify-center">
                Send another link
              </Button>
              {/* The fallback, AFTER the self-service remedy rather than instead of
                  it: an installation may not be set up to send mail at all, and a
                  failed send reaches this browser in no way whatsoever. */}
              <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                If no email ever arrives, this installation may not be set up to send it — whoever
                runs it can issue you a setup link directly.
              </p>
            </div>
          ) : (
            <form onSubmit={submit} className="flex flex-col gap-3">
              <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                Enter the address you sign in with and we will email you a link to choose a new
                password.
              </p>
              <Input
                label="Email"
                type="email"
                value={email}
                onChange={e => setEmail(e.target.value)}
                placeholder="you@company.com"
                autoComplete="email"
                maxLength={255}
                required
                autoFocus
              />
              {error && (
                <p role="alert" className="text-xs px-1" style={{ color: 'var(--color-error-ink)' }}>
                  {error}
                </p>
              )}
              <Button
                type="submit"
                variant="primary"
                loading={loading}
                disabled={!email}
                className="w-full justify-center mt-1"
              >
                Email me a link
              </Button>
            </form>
          )}
        </div>

        <p className="text-center text-sm mt-4" style={{ color: 'var(--color-text-muted)' }}>
          <Link to="/login" style={{ color: 'var(--color-brand-ink)' }} className="font-medium hover:underline">
            Back to sign in
          </Link>
        </p>
      </div>
    </div>
  )
}
