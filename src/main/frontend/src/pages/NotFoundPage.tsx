import { Link, Outlet, useLocation } from 'react-router'
import { Compass, EyeOff } from 'lucide-react'
import { useAuthStore } from '../auth'
import { useCurrentProject } from '../hooks/useCurrentProject'
import AppShell from '../components/AppShell'

/**
 * The "nothing here" screens (HD-97).
 *
 * Two DIFFERENT dead ends, deliberately worded differently because the user can
 * act on each in a different way:
 *
 *  - `variant="route"` — the URL matches no screen in the SPA. That is a typo or
 *    a stale link (e.g. `/w/{ws}/p/{id}/board`, where the board actually lives at
 *    `/w/{ws}/p/{id}`). Nothing was even requested from the API. We show the
 *    offending path so the mistake is visible.
 *  - `variant="resource"` — the route exists but the API answered 404 for the
 *    workspace/project/issue behind it. Per the tenancy rule, "gone" and "not
 *    yours" are the SAME answer on purpose, so the copy must cover both without
 *    confirming that anything exists.
 *
 * NEITHER is an error screen. A render crash is a different failure with a
 * different remedy (reload / report), and conflating them is exactly what made
 * the white-page reports expensive to triage. These screens are reached only by
 * routing or by an explicit 404 from a query — they never catch exceptions, and
 * they never say "something went wrong" or offer a reload.
 */

type Variant = 'route' | 'resource'

export function NotFoundScreen({ variant = 'route', noun = 'page', children }: {
  variant?: Variant
  /** What was looked up, for the `resource` variant: "issue", "project", … */
  noun?: string
  /** Extra actions (e.g. "Go to board") rendered next to the built-in ones. */
  children?: React.ReactNode
}) {
  const { pathname } = useLocation()
  const { accessToken, user } = useAuthStore()
  const cur = useCurrentProject()
  const signedIn = !!accessToken

  const Icon = variant === 'route' ? Compass : EyeOff

  return (
    <div
      className="flex-1 h-full flex items-center justify-center p-8 overflow-y-auto"
      style={{ background: 'var(--color-surface)' }}
    >
      <div
        className="w-full text-center"
        // Inline width — the Tailwind `max-w-*` utilities are shadowed by our
        // @theme spacing scale (max-w-xl would be 32px).
        style={{ maxWidth: 480 }}
      >
        <div
          className="rounded-xl border"
          style={{
            background: 'var(--color-card)',
            borderColor: 'var(--color-border)',
            borderRadius: 'var(--radius-xl)',
            boxShadow: 'var(--shadow-card)',
            padding: 32,
          }}
        >
          <span
            className="inline-flex items-center justify-center mb-4"
            style={{
              width: 44, height: 44, borderRadius: 'var(--radius-lg)',
              background: 'var(--color-surface-2)', color: 'var(--color-text-secondary)',
            }}
          >
            <Icon size={22} />
          </span>

          <div
            className="mono mb-2"
            style={{ fontSize: 11, letterSpacing: '0.05em', color: 'var(--color-text-muted)' }}
          >
            404
          </div>

          <h1
            className="font-bold"
            style={{ fontSize: 20, letterSpacing: '-0.02em', color: 'var(--color-text)' }}
          >
            {variant === 'route' ? 'This page doesn’t exist' : `We couldn’t find that ${noun}`}
          </h1>

          <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
            {variant === 'route' ? (
              <>Hamstrack has no screen at this address. Check it for a typo — links inside the app always lead somewhere.</>
            ) : (
              <>It may have been deleted, or it isn’t shared with your account. Ask a workspace admin if you think you should have access.</>
            )}
          </p>

          {variant === 'route' && (
            <div
              className="mono mt-4 truncate"
              title={pathname}
              style={{
                fontSize: 12, padding: '8px 12px', borderRadius: 'var(--radius-md)',
                background: 'var(--color-surface)', border: '1px solid var(--color-border)',
                color: 'var(--color-text-secondary)',
              }}
            >
              {pathname}
            </div>
          )}

          <div className="flex flex-wrap items-center justify-center gap-2 mt-6">
            {children}
            {signedIn && !user?.needsOnboarding && (
              <>
                <NotFoundAction to="/home" primary>Go to Home</NotFoundAction>
                {/* Suppressed when the caller supplied its own way back, which
                    on a project page already points at this same project. */}
                {cur && !children && (
                  <NotFoundAction to={`/w/${cur.wsId}/p/${cur.projectId}`}>
                    {cur.name ? `Open ${cur.name}` : 'Open last project'}
                  </NotFoundAction>
                )}
              </>
            )}
            {signedIn && user?.needsOnboarding && (
              <NotFoundAction to="/welcome" primary>Finish setting up</NotFoundAction>
            )}
            {!signedIn && (
              <>
                <NotFoundAction to="/" primary>Go to the start page</NotFoundAction>
                <NotFoundAction to="/login">Sign in</NotFoundAction>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Absolute-path link (splat-route rule) styled like the app's small buttons.
 * Exported so a page rendering the `resource` variant can add its own way back
 * (e.g. "Go to board") in the same visual language.
 */
export function NotFoundAction({ to, primary, children }: { to: string; primary?: boolean; children: React.ReactNode }) {
  return (
    <Link
      to={to}
      className="inline-flex items-center text-sm font-medium rounded-md border no-underline"
      style={{
        padding: '7px 14px',
        borderRadius: 'var(--radius-md)',
        background: primary ? 'var(--color-brand)' : 'var(--color-card)',
        borderColor: primary ? 'var(--color-brand)' : 'var(--color-border-2)',
        color: primary ? 'white' : 'var(--color-text)',
      }}
    >
      {children}
    </Link>
  )
}

/**
 * Chrome for the catch-all branch. A signed-in, onboarded user keeps the full
 * app shell (rail + top bar) so a mistyped URL is a detour, not a dead end;
 * everyone else — anonymous visitors and users still in onboarding — gets the
 * bare screen.
 *
 * This is why the catch-all lives OUTSIDE `RequireAuth` in `App.tsx`: routing an
 * unknown URL through the auth gate would bounce an anonymous visitor to
 * /login (and after signing in, straight back to the same unknown URL), turning
 * "you typed something wrong" into a confusing sign-in loop.
 */
export default function NotFoundChrome() {
  const { accessToken, user } = useAuthStore()
  const withShell = !!accessToken && !user?.needsOnboarding
  return withShell ? <AppShell /> : <Outlet />
}
