import { describe, it, expect, beforeEach, afterAll } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import AccountPage from './AccountPage'
import { useAuthStore } from '../auth'
import { useConfigStore } from '../config'
import type { PublicConfig } from '../api'
import type { User } from '../types'

/**
 * HD-193 — **the confirmation-code sentence belongs to the configured branch, and
 * nowhere else.**
 *
 * ## The claim being pinned
 *
 * The Account page tells a user, inside the `privacyContactEmail ?` branch only:
 *
 * > We will email a confirmation code to {account address} before anything is deleted.
 *
 * That sentence reads like a property of deletion, so hoisting it out beside the
 * three mechanism sentences "for symmetry" is the obvious tidy-up — and it is the
 * bug. **There is no `DELETE /api/auth/me` and no confirmation-code mechanism in
 * the product.** The code step is a line in an *operator's runbook*, followed by
 * an operator who went and configured a contact address. On a default self-hosted
 * install (`privacyContactEmail` is `''`, the DC default) "we" is a self-hoster
 * who was never told they had undertaken that promise, and the SPA would be making
 * it on their behalf to their users. That is a milder form of the exact defect this
 * whole ticket exists to close: **published copy describing a capability that is
 * not there.**
 *
 * Until this file existed, the only thing standing between that reasoning and a
 * future refactor was a code comment — and a comment loses to a tidy-up, silently,
 * because both branches still render and nothing turns red.
 *
 * ## Why the section must render in BOTH branches
 *
 * The mirror-image mistake is to "fix" the unconfigured branch by hiding the whole
 * deletion section when no address is set. That is this project's Rule C in its
 * original wording — *a capability needs an enabling affordance visible while it is
 * off* — and here it bites twice as hard: an affordance visible only where somebody
 * remembered to set a property is unreachable for exactly the operators who did not
 * know the property existed. So the section, its mechanism sentences and a "how to
 * ask" block are asserted with the address unset as firmly as with it set; only the
 * *promise about a mechanism* is branch-scoped.
 *
 * ## Standing caveat — this file protects a local run and a reviewer, not a merge
 *
 * **`npm test` runs on no automated path** (HD-242). CI runs exactly one command,
 * `./mvnw -B verify`, and the `frontend-maven-plugin` executions are `npm ci` and
 * `npm run build` — nothing ever invokes `vitest`. So a red result here is seen by
 * whoever types the command, and by nobody else, until HD-242 lands. The
 * repository's other seal on published copy, `PublishedClaimsTest`, is a JUnit test
 * for precisely this reason; it scans SPA sources but deliberately skips `*.test.tsx`,
 * so nothing in this file is read by it.
 *
 * ## What this test cannot see
 *
 * A *reworded* promise. "You will receive a code", "we verify by email first",
 * anything assembled from adjacent JSX children — none of it matches the sentence
 * asserted below. This pins one known string to one branch; it is not a proof that
 * the unconfigured branch promises nothing.
 */

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

/** An operator-configured address. Deliberately not the account's own address. */
const CONTACT = 'privacy@acme.example'

/** The DC default: nobody configured anything, and nobody is running a runbook. */
const UNCONFIGURED = ''

/**
 * The sentence, written once and assembled from the fixture, so that a change to
 * the account address in this file cannot leave a half-updated expectation behind.
 */
const CODE_SENTENCE =
  `We will email a confirmation code to ${ME.email} before anything is deleted.`

const FALLBACK_SENTENCE =
  'Account deletion on this installation is handled by its administrator.'

function config(privacyContactEmail: string): PublicConfig {
  return {
    publicLandingEnabled: true,
    termsAcceptanceRequired: true,
    publicSignupEnabled: true,
    privacyContactEmail,
    version: '0.0.0-test',
  }
}

function renderAccount(privacyContactEmail: string) {
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
  useConfigStore.setState({ config: config(privacyContactEmail) })
  return render(<MemoryRouter><AccountPage /></MemoryRouter>)
}

/**
 * Every rendered paragraph, whitespace-normalised.
 *
 * Asserted this way rather than with `getByText` because the page interpolates the
 * address into a `<span>` mid-sentence: `getNodeText` sees only a node's *direct*
 * text children, so the sentence a user reads as one line is invisible to the
 * default matcher. Reading `textContent` per `<p>` asserts what is on the screen,
 * and a failure prints the page's whole copy — which is the thing a reader of a red
 * run needs to see.
 */
function paragraphs(): string[] {
  return Array.from(document.body.querySelectorAll('p'))
    .map(p => (p.textContent ?? '').replace(/\s+/g, ' ').trim())
    .filter(Boolean)
}

/** Everything the page says, as one normalised string — for absence assertions. */
function allCopy(): string {
  return (document.body.textContent ?? '').replace(/\s+/g, ' ').trim()
}

// The "Email …" control navigates by assigning `window.location.href` (it is a
// <button>, not an <a>, so there is no attribute to read). jsdom refuses real
// navigation, so the setter is captured and restored.
const originalLocation = Object.getOwnPropertyDescriptor(window, 'location')!
let navigatedTo: string[] = []

beforeEach(() => {
  navigatedTo = []
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      ...originalLocation.value,
      get href() { return '' },
      set href(value: string) { navigatedTo.push(value) },
    },
  })
})

afterAll(() => {
  Object.defineProperty(window, 'location', originalLocation)
})

describe('Account page — a confirmation code is promised only where an operator configured the address that receives the request (HD-193)', () => {
  it('configured: promises the code, names the account address, and offers the contact address as text AND as a mailto:', async () => {
    renderAccount(CONTACT)

    expect(
      paragraphs(),
      'the operator configured a contact address, so a request goes somewhere and the '
      + 'runbook that answers it starts with a confirmation code — the sentence belongs here',
    ).toContain(CODE_SENTENCE)

    // The address as plain text: a browser with no mail-client handler turns a bare
    // mailto: into a dead end, and this is the only deletion path the page offers.
    expect(allCopy(), `the configured contact address must be readable on the page`)
      .toContain(CONTACT)

    // …and as a mailto:, addressed to the configured mailbox.
    await userEvent.click(screen.getByRole('button', { name: `Email ${CONTACT}` }))
    expect(navigatedTo, 'the "Email …" control must compose a mailto: to the configured address')
      .toHaveLength(1)
    expect(navigatedTo[0]).toMatch(new RegExp(`^mailto:${CONTACT}\\?subject=`))
  })

  it('unconfigured (the DC default): says nothing about a confirmation code — there is no DELETE /api/auth/me, and "we" would be a self-hoster who never made that promise', () => {
    renderAccount(UNCONFIGURED)

    expect(
      paragraphs(),
      'the confirmation-code sentence was hoisted out of the configured branch. It describes a '
      + 'step in an OPERATOR RUNBOOK, not a product mechanism: with no contact address nobody is '
      + 'running that runbook, so this makes the SPA promise a self-hoster\'s users something the '
      + 'self-hoster never undertook — published copy for a capability that does not exist, which '
      + 'is the defect HD-193 exists to close. Keep it inside the `contactEmail ?` branch.',
    ).not.toContain(CODE_SENTENCE)

    // Broader than the exact sentence: no re-wording of it survives here either.
    expect(allCopy(), 'no variant of the code promise may reach an unconfigured install')
      .not.toMatch(/confirmation code/i)

    expect(
      paragraphs(),
      'with no address configured the page must still say who to ask — the installation\'s '
      + 'administrator — rather than inventing a mailbox or falling silent',
    ).toContain(FALLBACK_SENTENCE)
  })

  it.each([
    ['configured', CONTACT],
    ['unconfigured', UNCONFIGURED],
  ])('%s: still renders the deletion section and its mechanism sentences (Rule C — an affordance visible only where a property was set is unreachable for the operators who did not know it existed)', (_label, address) => {
    renderAccount(address)

    expect(screen.getByRole('heading', { name: 'Delete account' })).toBeInTheDocument()
    expect(screen.getByText('How to ask')).toBeInTheDocument()

    const copy = allCopy()
    expect(copy, 'what deletion removes must be stated in both branches')
      .toContain('Deletion removes the personal data in your account record')
    expect(copy, 'what survives deletion must be stated in both branches')
      .toContain('is re-attributed to "Deleted user"')
    expect(copy, 'the irreversibility warning must be stated in both branches')
      .toContain('This cannot be undone.')
  })
})
