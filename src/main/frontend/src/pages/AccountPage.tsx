import { useState } from 'react'
import { Link } from 'react-router'
import { Check, Copy, Mail } from 'lucide-react'
import { useAuthStore } from '../auth'
import { useConfigStore } from '../config'
import { Avatar, Button } from '../components/ui'

/**
 * Account page (HD-193 §9) — the one deletion path a user can reach.
 *
 * ## Why there is no form and no "Request deletion" button
 *
 * An in-app button that inserts a row into a `deletion_requests` table is this
 * ticket's own defect with a migration attached: nothing watches the table,
 * nothing alerts on it, no SLA governs it, and the user walks away holding a
 * receipt that implies all three. The rule the page keeps instead is **an
 * affordance may only promise what a rehearsed procedure delivers** — an email
 * to an address the Privacy Policy already publishes promises exactly one thing,
 * that the message arrives at an inbox, and that is true today. Everything else
 * waits for `DELETE /api/auth/me`, where the promise becomes mechanical.
 *
 * So the page deliberately says nothing about how long a reply takes, how long
 * deletion takes, or what state a request is in. There is no state.
 *
 * ## Why the section renders even when nothing is configured
 *
 * `privacyContactEmail` is empty on a default self-hosted install. The section is
 * **never hidden** for that (§9.3): a deletion affordance visible only where
 * somebody remembered to set a property is unreachable for exactly the operators
 * who did not know they had to. Unconfigured, the "how to ask" block names the
 * installation's administrator instead — the same reachability rule the delivery
 * capabilities work states as Rule C.
 *
 * ## The copy is shared with the Privacy Policy on purpose
 *
 * The three mechanism sentences below are the same sentences as
 * `pages/legal/PrivacyPage.tsx` §5, not a second wording — the policy describes
 * the procedure in the words the runbook implements, and a product surface that
 * paraphrased it would be a second, unreviewed representation. Edit both
 * together, or neither.
 *
 * Account-scoped, not workspace-scoped: it is outside `/w/{wsId}` because it is
 * about the account, reads nothing tenant-scoped, and must stay reachable for a
 * user who has no workspace at all.
 */
export default function AccountPage() {
  const { user } = useAuthStore()
  // Config-driven, from the same `/api/meta` store that supplies
  // `termsAcceptanceRequired` and `publicSignupEnabled`. Never a build-time
  // constant: the address differs per installation, and the DC default is unset.
  const contactEmail = useConfigStore(s => s.config.privacyContactEmail)

  if (!user) return null

  return (
    <div style={{ flex: 1, overflow: 'auto' }}>
      <div style={{ padding: '20px 26px 0' }}>
        <h1 style={{ fontSize: 22, fontWeight: 800 }}>Account</h1>
        <p style={{ fontSize: 13, color: 'var(--color-text-muted)', fontWeight: 600, marginTop: 4 }}>
          Your sign-in details, and how to have this account deleted.
        </p>
      </div>

      {/* Inline maxWidth: our @theme --spacing-* scale shadows Tailwind's
          max-w-{2xs..3xl} sizes (max-w-xl would resolve to 32px) — see CLAUDE.md */}
      <div className="flex flex-col gap-5" style={{ padding: '20px 26px 40px', maxWidth: 760 }}>
        <IdentityCard
          displayName={user.displayName}
          email={user.email}
          avatarUrl={user.avatarUrl}
        />
        <DeleteAccountCard
          email={user.email}
          userId={user.id}
          contactEmail={contactEmail}
        />
      </div>
    </div>
  )
}

// ── 1. Identity ─────────────────────────────────────────────────────────────

/**
 * Read-only. Renaming, changing the address and changing the password from here
 * are a separate ticket (§2 non-goals) — this page exists for the affordance
 * below it, and a half-built profile editor would be the larger promise.
 *
 * "Member since" is not shown: `GET /api/auth/me` does not carry the account's
 * `createdAt`, and inventing a date from anything else on the client would be a
 * fact the server never stated.
 */
function IdentityCard({ displayName, email, avatarUrl }: {
  displayName: string
  email: string
  avatarUrl?: string
}) {
  return (
    <Card title="Signed in as" blurb="How you appear to everyone you share a workspace with.">
      <div className="flex items-center gap-3">
        <Avatar name={displayName} avatarUrl={avatarUrl} size={44} />
        <div className="min-w-0">
          <div className="truncate" style={{ fontSize: 15, fontWeight: 700, color: 'var(--color-text)' }}>
            {displayName}
          </div>
          <div className="mono truncate" style={{ fontSize: 12.5, color: 'var(--color-text-secondary)' }}>
            {email}
          </div>
        </div>
      </div>
    </Card>
  )
}

// ── 2. Delete account ───────────────────────────────────────────────────────

function DeleteAccountCard({ email, userId, contactEmail }: {
  email: string
  userId: string
  contactEmail: string
}) {
  const subject = 'Account deletion request'
  // The account's own address and id, so the operator can key the runbook's
  // pre-flight off the message without a round trip asking who is writing.
  const body = `I would like to delete my Hamstrack account.\n\nAccount: ${email}\nUser id: ${userId}\n`
  // The address is percent-encoded like every other part of the URL: it arrives from
  // an operator-set property, and a '?' or '&' in it would end the addr-spec early and
  // inject extra mailto headers (cc, bcc) into a message this page composed. '@' is a
  // literal inside an addr-spec (RFC 6068), so it is put back for readability — the
  // encoding is there for the delimiters, not for the shape of an email address.
  const to = encodeURIComponent(contactEmail).replace(/%40/g, '@')
  const mailto = `mailto:${to}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`

  return (
    <Card
      title="Delete account"
      blurb="What deletion does, and how to ask for it."
    >
      {/* The same three sentences as the Privacy Policy, §5 — see the file
          javadoc. Not a paraphrase, and not assembled from parts. */}
      <div className="text-sm flex flex-col gap-2" style={{ color: 'var(--color-text-secondary)' }}>
        <p>
          Deletion removes the personal data in your account record — your email address, display
          name and sign-in credentials — and removes your address from our mail delivery logs.
        </p>
        <p>
          Work you created inside a workspace (issues, comments and uploaded files) stays with
          that workspace and is re-attributed to "Deleted user", so the team's history stays
          readable; text other members wrote is not edited, and your name may remain where they
          typed it.
        </p>
        <p>
          A workspace in which you are the only remaining member is deleted with its contents.
        </p>
        <p style={{ color: 'var(--color-text)', fontWeight: 600 }}>This cannot be undone.</p>
        <p>
          The <Link to="/privacy" style={{ color: 'var(--color-brand-ink)', fontWeight: 600 }}>Privacy
          Policy</Link> covers the rest.
        </p>
      </div>

      <div
        className="mt-4 rounded-lg border p-3.5"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-surface-2)' }}
      >
        <div
          className="text-xs font-bold"
          style={{ letterSpacing: '0.05em', textTransform: 'uppercase', color: 'var(--color-text-muted)' }}
        >
          How to ask
        </div>
        {contactEmail
          ? <HowToAsk contactEmail={contactEmail} mailto={mailto} accountEmail={email} />
          : (
            <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
              Account deletion on this installation is handled by its administrator.
            </p>
          )}
      </div>
    </Card>
  )
}

/**
 * Address as text **and** as a `mailto:`. The plain text plus a copy control is
 * not redundancy: a browser with no mail-client handler turns a bare `mailto:`
 * button into a dead end, and this is the only path the page offers.
 *
 * ## Why the confirmation-code sentence lives in here, and not in the card
 *
 * It reads like a property of deletion, so hoisting it out beside the other
 * mechanism sentences "for symmetry" is the obvious tidy-up — and it is the bug.
 * There is no `DELETE /api/auth/me` and no confirmation-code mechanism in the
 * product: the code step is a line in the **operator's runbook**, followed by an
 * operator who has configured an address. Unconfigured, nobody is running that
 * runbook, and the sentence has our SPA promise a self-hoster's users something
 * the self-hoster was never told they had undertaken. Inside this branch the
 * operator opted in by configuring the address, and it is the only branch where
 * a request goes anywhere — which is also where the sentence earns its keep: it
 * tells a reader that **no account is deleted merely because an email arrived**,
 * so a message forged from someone else's address is neither an oracle nor a
 * weapon.
 */
function HowToAsk({ contactEmail, mailto, accountEmail }: {
  contactEmail: string
  mailto: string
  /** The signed-in account's own address — the inbox the code would go to. */
  accountEmail: string
}) {
  const [copied, setCopied] = useState(false)
  const [copyFailed, setCopyFailed] = useState(false)

  async function copy() {
    setCopyFailed(false)
    try {
      await navigator.clipboard.writeText(contactEmail)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    } catch {
      // Clipboard denied or unavailable (insecure origin, permission policy).
      // Say so rather than showing a tick that copied nothing.
      setCopyFailed(true)
    }
  }

  return (
    <>
      <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
        Write to <span className="mono" style={{ color: 'var(--color-text)' }}>{contactEmail}</span>{' '}
        from this account.
      </p>
      <div className="flex flex-wrap items-center gap-2 mt-3">
        <Button variant="secondary" onClick={() => { window.location.href = mailto }}>
          <Mail size={14} /> Email {contactEmail}
        </Button>
        <Button variant="ghost" onClick={copy} aria-label={`Copy ${contactEmail}`}>
          {copied ? <Check size={14} /> : <Copy size={14} />} {copied ? 'Copied' : 'Copy address'}
        </Button>
      </div>
      {copyFailed && (
        <p className="text-xs mt-2" style={{ color: 'var(--color-text-muted)' }}>
          Your browser did not allow copying — select the address above instead.
        </p>
      )}
      <p className="text-sm mt-3" style={{ color: 'var(--color-text-secondary)' }}>
        We will email a confirmation code to <span className="mono">{accountEmail}</span> before anything
        is deleted.
      </p>
    </>
  )
}

// ── shared ──────────────────────────────────────────────────────────────────

/** Ordinary bordered section, matching the settings cards (DESIGN.md, Beacon). */
function Card({ title, blurb, children }: {
  title: string
  blurb: string
  children: React.ReactNode
}) {
  return (
    <section
      aria-label={title}
      className="rounded-lg border p-4"
      style={{ background: 'var(--color-card)', borderColor: 'var(--color-border)', boxShadow: 'var(--shadow-card)' }}
    >
      <h2 className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>{title}</h2>
      <p className="text-sm mt-1 mb-3" style={{ color: 'var(--color-text-secondary)', maxWidth: 620 }}>
        {blurb}
      </p>
      {children}
    </section>
  )
}
