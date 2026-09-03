import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import ResetPasswordPage from './ResetPasswordPage'
import ForgotPasswordPage from './ForgotPasswordPage'
import { ApiResponseError } from '../api'

// The route table itself, as text. See "why the route is asserted from source".
import APP_SOURCE from '../App.tsx?raw'

/**
 * HD-183 — **a dead reset link prescribes an action its reader can perform.**
 *
 * ## The claim being pinned
 *
 * A user whose reset link has expired used to be told:
 *
 * > This link is invalid or has expired. Ask your administrator for a new one.
 *
 * which fails this project's rule (HD-151, stated in `GlobalExceptionHandler`'s
 * javadoc) in **both directions at once**:
 *
 *  - **The named person cannot do it.** On Cloud "your administrator" reads as the
 *    workspace owner, who has no mechanism to mint a reset link for anybody. The
 *    only role that can is a *system* administrator (the admin console's
 *    "Regenerate setup link"), and a Cloud tenant cannot reach one.
 *  - **The reader could have done it themselves.** Asking for a fresh link is a
 *    public, unauthenticated endpoint — one click, if the click exists.
 *
 * So the assertions below are about a **category**, not one string: the dead-link
 * copy must offer the reader a control that takes them to the request form, and
 * must not dispatch them to a person. Rewording the diagnosis is free; losing the
 * remedy is the regression.
 *
 * ## Why the route is asserted from source
 *
 * The remedy is a `<Link to="/forgot-password">`, and a link to a path no route
 * matches is not an error — it silently renders the not-found screen, which is a
 * *worse* dead end than the sentence this ticket removed. Rendering the two pages
 * inside a `MemoryRouter` here proves the destination component works, but says
 * nothing about the real router; `App.tsx` is therefore read as text (the
 * `?raw` precedent is `moduleGraph.test.ts`) so that deleting the route reddens
 * this file rather than the user's screen.
 *
 * ## Identical copy in DC and Cloud
 *
 * There is deliberately no mode-branch to test. The sentence is one sentence in
 * both deployment models — the acceptance criterion of the ticket, and the same
 * choice `GlobalExceptionHandler` made for its refusals. A future `if (cloud)`
 * around this copy is the defect coming back wearing a fix's clothes.
 *
 * That is also why the mail-less fallback is asserted here rather than in a
 * DC-only case: **the discriminator is whether an installation sends mail, not
 * which profile it runs.** A self-hosted install with `MAIL_HOST` blank and a
 * Cloud instance with a broken relay fail identically, and neither failure reaches
 * the browser at all — the send is asynchronous, retries, and dead-letters in
 * silence. A remedy that fails identically every time it is followed is the defect
 * this ticket opened with, so "ask for another link" is pinned *out* of the copy
 * while the button that does it stays.
 *
 * ## What the uniform answer actually guarantees
 *
 * The **body** is identical for a registered and an unregistered address, and the
 * per-address ceiling is spent (and recorded) *before* the account lookup, so a
 * refusal cannot be read as "registered" either — which is why that refusal is
 * silent rather than a 429. **The two branches are not equal in duration**: the
 * known one pays a `SecureRandom` token, a SHA-256 hash and a `password_resets`
 * insert, and `FailedEmailWriter`'s javadoc records a longer, address-correlated
 * park on that branch alone which it lists as still open. What makes sampling
 * impractical is therefore the **ceiling**, not equal work. An earlier revision of
 * the failure message below claimed "body and timing"; a claim about a security
 * property, written into a test message, is read by the next person as settled, so
 * it says only what is tested and true.
 *
 * ## Standing caveat — this file protects a local run and a reviewer, not a merge
 *
 * **`npm test` runs on no automated path** (HD-242): CI runs exactly one command,
 * `./mvnw -B verify`, whose frontend executions are `npm ci` and `npm run build`.
 * Nothing invokes vitest. A red result here is seen by whoever types the command
 * and by nobody else until HD-242 lands. (`PublishedClaimsTest`, the repository's
 * JUnit seal on published copy, scans SPA sources but skips `*.test.tsx`, so it
 * does not read this file either.)
 */

const apiResetPasswordMock = vi.fn()
const apiForgotPasswordMock = vi.fn()

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<typeof import('../api')>()),
  // ApiResponseError stays real: both pages branch on `instanceof`, and a
  // look-alike class here would make the branches under test unreachable.
  apiResetPassword: (...args: unknown[]) => apiResetPasswordMock(...args),
  apiForgotPassword: (...args: unknown[]) => apiForgotPasswordMock(...args),
}))

/** The sentence HD-183 deleted, and the shape it belongs to. */
const REFERRAL = /ask (your|an|a) (administrator|admin|workspace owner)/i

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

/** Everything on screen, whitespace-normalised — sentences here span elements. */
function copy(): string {
  return (document.body.textContent ?? '').replace(/\s+/g, ' ').trim()
}

/** Drives the form to a refusal from the server. */
async function submitPassword(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('New password'), 'correct-horse-battery')
  await user.type(screen.getByLabelText('Confirm password'), 'correct-horse-battery')
  await user.click(screen.getByRole('button', { name: 'Set password' }))
}

beforeEach(() => {
  apiResetPasswordMock.mockReset()
  apiForgotPasswordMock.mockReset()
})

describe('Reset password — a refused link points at the remedy, not at a person (HD-183)', () => {
  it('a 400 naming no field says the link is dead and links to the request form', async () => {
    const user = userEvent.setup()
    apiResetPasswordMock.mockRejectedValue(new ApiResponseError(400, 'Invalid or expired token'))
    renderAt('/reset-password?token=dead-token')

    await submitPassword(user)

    expect(await screen.findByText(/invalid or has expired/i)).toBeInTheDocument()

    const remedy = screen.getByRole('link', { name: 'request a new one' })
    expect(
      remedy,
      'the dead-link refusal must offer the reader the action they can perform themselves — '
      + 'asking for another link — and it must go to the form that does it',
    ).toHaveAttribute('href', '/forgot-password')

    expect(
      copy(),
      'HD-183: this page must not dispatch a locked-out user to a person. On Cloud that person '
      + 'is a workspace owner with no mechanism to mint a reset link, and on any install the '
      + 'reader can request one themselves.',
    ).not.toMatch(REFERRAL)
  })

  /**
   * The refusal replaces a confirmation the reader is waiting for and moves no focus, so
   * without a live region a screen-reader user hears the button stop loading and nothing
   * else — and what they miss is the link to the remedy, which is the whole of HD-183's fix.
   * The sibling refusal on ForgotPasswordPage was already an alert; this one was not.
   */
  it('announces the dead-link refusal, with the remedy inside the announcement', async () => {
    const user = userEvent.setup()
    apiResetPasswordMock.mockRejectedValue(new ApiResponseError(400, 'Invalid or expired token'))
    renderAt('/reset-password?token=dead-token')

    await submitPassword(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/invalid or has expired/i)
    expect(
      within(alert).getByRole('link', { name: 'request a new one' }),
      'the remedy must be INSIDE the announced region: an alert that states the problem and '
      + 'leaves its only remedy outside is announced as a dead end.',
    ).toBeInTheDocument()
  })

  it('the remedy actually reaches the request form (the link is not itself a dead end)', async () => {
    const user = userEvent.setup()
    apiResetPasswordMock.mockRejectedValue(new ApiResponseError(400, 'Invalid or expired token'))
    renderAt('/reset-password?token=dead-token')
    await submitPassword(user)

    await user.click(screen.getByRole('link', { name: 'request a new one' }))

    expect(
      screen.getByRole('button', { name: 'Email me a link' }),
      'the destination must be a form that asks for a new link, not a not-found screen',
    ).toBeInTheDocument()
  })

  it('registers /forgot-password in the real router, so the remedy resolves outside this test', () => {
    expect(
      APP_SOURCE,
      'the dead-link copy links to /forgot-password. Without the route, React Router matches '
      + 'nothing and renders the not-found screen — a worse dead end than the sentence HD-183 '
      + 'removed, and one no type error reports.',
    ).toMatch(/path="\/forgot-password"/)
  })

  it('a link with no token at all still names the remedy', () => {
    renderAt('/reset-password')

    expect(screen.getByText(/token is missing/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'request a new one' })).toHaveAttribute(
      'href', '/forgot-password',
    )
    expect(copy()).not.toMatch(REFERRAL)
  })

  it('a 400 that names `newPassword` is still a field error, not a dead link (HD-171 discriminator)', async () => {
    const user = userEvent.setup()
    apiResetPasswordMock.mockRejectedValue(new ApiResponseError(
      400, 'Password is too long', undefined, undefined, undefined,
      { newPassword: 'This password is 91 bytes and the limit is 72.' },
    ))
    renderAt('/reset-password?token=live-token')

    await submitPassword(user)

    expect(await screen.findByText(/91 bytes/)).toBeInTheDocument()
    expect(
      copy(),
      'HD-183 added a second branch to the same status. Telling this user their link expired '
      + 'sends them to fetch a new one that will fail identically — which is the exact defect '
      + 'HD-171 fixed, re-introduced by the fix for the sentence.',
    ).not.toMatch(/invalid or has expired/i)
  })
})

describe('Forgot password — the form the remedy leads to (HD-183)', () => {
  it('posts the address and confirms without revealing whether the account exists', async () => {
    const user = userEvent.setup()
    apiForgotPasswordMock.mockResolvedValue({ message: 'ignored' })
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a link' }))

    expect(apiForgotPasswordMock).toHaveBeenCalledWith('locked-out@example.com')

    const confirmation = copy()
    expect(
      confirmation,
      'the server answers identically for a registered and an unregistered address IN THE BODY, '
      + 'and the per-address ceiling is spent before the account lookup, so a refusal cannot be '
      + 'read as "registered" either. It is NOT equal in duration — the known branch pays a token, '
      + 'a hash and an insert, and FailedEmailWriter records a longer address-correlated park that '
      + 'is still open — so the ceiling, not equal work, is what makes sampling impractical. A '
      + 'confirmation that asserted the account exists would hand back for free what all of that '
      + 'is spent withholding.',
    ).toMatch(/If locked-out@example\.com has an account/)
  })

  it('offers the mail-less fallback after the self-service remedy, not instead of it', async () => {
    const user = userEvent.setup()
    apiForgotPasswordMock.mockResolvedValue({ message: 'ignored' })
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a link' }))

    // The self-service remedy survives: it is the one that usually works.
    expect(await screen.findByRole('button', { name: 'Send another link' })).toBeInTheDocument()

    const confirmation = copy()
    expect(
      confirmation,
      'HD-183: an installation with no SMTP is a supported provisioning model (the admin console '
      + 'hands out setup links and says "no email is sent"; MAIL_HOST ships blank), and the send '
      + 'fails asynchronously with no signal to the browser. Without this fallback the page tells '
      + 'the reader a link is coming and offers only a button that fails identically for ever. One '
      + 'sentence, true in DC and in Cloud — the discriminator is whether this installation sends '
      + 'mail, never which profile it runs.',
    ).toMatch(/may not be set up to send it/)

    expect(
      confirmation,
      'HD-183: "then ask for another link" was the ticket\'s own defect one line down. Reset mail '
      + 'is capped per ADDRESS and refused SILENTLY, so following that instruction five times '
      + 'guarantees no mail for the rest of the window while the page says one is on its way each '
      + 'time. The button may stay; the instruction to repeat may not.',
    ).not.toMatch(/(then|and) ask for another/i)

    expect(
      confirmation,
      'HD-183: this sentence is written to be RELAYED to whoever runs the installation, so it '
      + 'must name the control that person has. The admin console mints a "setup link" '
      + '(Regenerate setup link), and so do docs/self-hosting.md, docs/api-dc.md and '
      + 'openapi.yaml. An earlier draft said "sign-in link", which names a capability this '
      + 'product does not have at all — there is no magic-link sign-in — and sends the reader to '
      + 'ask their operator for something no screen of theirs shows.',
    ).toMatch(/setup link/i)

    expect(
      confirmation,
      'no door in this product signs anybody in from a link. Naming one invents a feature in '
      + 'the copy of the page whose whole subject is how to get back into an account.',
    ).not.toMatch(/(sign[- ]?in|log[- ]?in|magic)[\s-]?link/i)
  })

  /**
   * **The cooldown is an operator setting, so no sentence about it may carry a number.**
   *
   * The wait between two requests for one address is
   * `app.auth-mail.recipient-cooldown-minutes` (`AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES`,
   * `@Min(1) @Max(14)`, default 1). "Give it a minute first" was therefore true only on an
   * install that had left the default alone; on any other, the reader who obeyed it asked
   * again, was refused SILENTLY, and was told a second time that a link was on its way —
   * which is not merely similar to the defect HD-183 opened with, it is that defect, shipped
   * inside its own fix. It happened three times in this ticket, which is why the rule is
   * sealed here rather than left to whoever edits the copy next.
   *
   * **A category, not a string.** Rewording the paragraph is free; what may not appear in it
   * is a *quantity of time*, in any form. The seal is scoped to this paragraph on purpose:
   * the sibling above it says "expires after an hour" and that number is legitimate — it is
   * `AuthService`'s hardcoded `plusSeconds(3600)`, a constant with no property behind it.
   * The line between the two is not "which sentence" but "who owns the number", so the
   * assertion below is anchored to the paragraph that describes the operator-owned one, and
   * the last assertion pins the legitimate number so nobody deletes it thinking it is this.
   */
  it('names no unit of time beside the cooldown, because an operator can move it', async () => {
    const user = userEvent.setup()
    apiForgotPasswordMock.mockResolvedValue({ message: 'ignored' })
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a link' }))

    const paragraph = await screen.findByText(/spaced out/i)
    const sentence = (paragraph.textContent ?? '').replace(/\s+/g, ' ').trim()

    expect(
      sentence,
      'HD-183: AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES is settable from 1 to 14, so ANY duration '
      + 'written here is false on every install that raised it. Say it as a property that '
      + 'holds at every setting ("spaced out", "wait a while"), never as a quantity. This '
      + 'paragraph has acquired a number once already, in the commit that was fixing exactly '
      + 'this.',
    ).not.toMatch(/\b(seconds?|minutes?|hours?|days?|weeks?|moments?)\b/i)

    expect(
      sentence,
      'a bare numeral is the same claim without the unit ("give it 60") — the seal is about '
      + 'quantities, not about the words for them.',
    ).not.toMatch(/\d/)

    expect(
      sentence,
      'the property must still tell the reader that waiting is what helps. Deleting the clause '
      + 'passes a ban trivially and leaves the reader clicking a button that sends nothing.',
    ).toMatch(/(wait|a while|a bit|later|give it)/i)

    expect(
      copy(),
      'the seal is scoped to the cooldown paragraph, not to the screen: the token lifetime IS a '
      + "hardcoded constant (AuthService's plusSeconds(3600)) and saying so is what tells a "
      + 'reader whether the link in their inbox is still worth clicking.',
    ).toMatch(/expires after an hour/i)
  })

  /**
   * The confirmation's link sentence is now a claim about the SERVER, not about a token.
   *
   * `AuthService.resetPassword` retires every other outstanding reset for that user when one is
   * spent (`PasswordResetRepository.invalidateOtherOutstanding`) — including an administrator's
   * seven-day setup link, deliberately, that being the longer-lived instance of the same
   * account-takeover capability. "The link works once and expires after an hour" stayed true of
   * each individual link and said nothing about the set, which is the thing the reader is
   * looking at: the button directly below mints another one, so two clicks make three live
   * links and no sentence told them which of the three would still work afterwards.
   *
   * Pinned as a category, not as a string. Rewording is free; what may not come back is a
   * sentence whose subject is one link, and what may not appear is a DIRECTIONAL version
   * ("older links stop working") — the sweep does not care which link was spent, so a reader
   * who completes the reset with the first mail they opened kills the newer ones too.
   */
  it('says that spending one link retires the whole outstanding set, not just that each works once', async () => {
    const user = userEvent.setup()
    apiForgotPasswordMock.mockResolvedValue({ message: 'ignored' })
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a link' }))

    const confirmation = copy()
    expect(
      confirmation,
      'HD-183: completing a reset now invalidates every OTHER outstanding link for that user, so '
      + 'the copy beside "Send another link" must say what happens to the set. A sentence that '
      + 'only says each link is single-use is true and understates the server, directly under the '
      + 'button that produces the second link.',
    ).toMatch(/turns off all the others/i)

    expect(
      confirmation,
      'the singular claim is what this replaced: "The link works once" describes one token, and '
      + 'the reader who has just clicked "Send another link" is holding several.',
    ).not.toMatch(/\bthe link works once/i)

    expect(
      confirmation,
      'the sweep is blind to which link was spent — it burns every other unused row — so copy '
      + 'promising that only OLDER/PREVIOUS links stop working would be false for the reader who '
      + 'completes the reset from the first mail they opened.',
    ).not.toMatch(/(older|previous|earlier)\s+links?/i)

    // The set exists because of this button; the claim and its cause stay on one screen.
    expect(screen.getByRole('button', { name: 'Send another link' })).toBeInTheDocument()
  })

  it('the confirmation is announced and takes focus, and an error is an alert', async () => {
    const user = userEvent.setup()
    apiForgotPasswordMock.mockResolvedValue({ message: 'ignored' })
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a link' }))

    const status = await screen.findByRole('status')
    expect(
      status,
      'the form unmounts on success, so without a live region a screen-reader user is told nothing '
      + 'happened at all',
    ).toHaveTextContent(/has an account/)
    expect(
      document.activeElement,
      'focus fell to <body> when the form unmounted. It leaks nothing to move it: the transition '
      + 'depends only on the request resolving, never on what the answer was.',
    ).toBe(status)
  })

  it('renders a rate-limit sentence only for a 429 — never for a 503 that carries Retry-After', async () => {
    const user = userEvent.setup()
    apiForgotPasswordMock.mockRejectedValue(new ApiResponseError(
      503, 'Service temporarily unavailable', undefined, undefined, { retryAfter: 30 },
    ))
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a link' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Service temporarily unavailable')
    expect(
      copy(),
      'HD-183: Retry-After is a general HTTP header and a proxy sends it with a 503 during a '
      + 'deploy. Branching on the header alone blamed the reader for an outage, and told them to '
      + 'slow down when the one useful thing they could do was try again.',
    ).not.toMatch(/too many requests/i)
  })

  it('a button that is loading is not clickable, even though the caller also passes disabled', async () => {
    const user = userEvent.setup()
    let resolve = () => {}
    apiForgotPasswordMock.mockImplementation(() => new Promise<void>(r => { resolve = () => r() }))
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    const submit = screen.getByRole('button', { name: 'Email me a link' })
    await user.click(submit)
    await user.click(submit)
    await user.click(submit)

    expect(
      apiForgotPasswordMock,
      'ui.tsx computed disabled={loading || props.disabled} and then re-applied disabled from '
      + 'the prop spread, which wins — so every caller passing BOTH lost the loading guard. This '
      + 'page has no other one, and each extra click spends a request from the per-IP /api/auth/* '
      + 'budget the reader needs for their own next attempt.',
    ).toHaveBeenCalledTimes(1)

    resolve()
    expect(await screen.findByRole('status')).toBeInTheDocument()
  })

  it('a 429 names the wait, because waiting is the whole remedy here', async () => {
    const user = userEvent.setup()
    apiForgotPasswordMock.mockRejectedValue(new ApiResponseError(
      429, 'Too many requests — try again later', undefined, undefined, { retryAfter: 42 },
    ))
    renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'locked-out@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a link' }))

    expect(await screen.findByText(/try again in 42 seconds/i)).toBeInTheDocument()
  })
})
