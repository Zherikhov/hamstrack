import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
// Direct bundle import: the package's main entry pulls in Node's `path`
// (absolute-path.js) and breaks browser builds
import SwaggerUIBundle from 'swagger-ui-dist/swagger-ui-bundle'
import 'swagger-ui-dist/swagger-ui.css'
import './docs.css'
import Footer from '../../components/Footer'
import { useAuthStore } from '../../auth'

// Documentation hub. One tab for now; admin and user guides will be added as
// more tabs, so the tab bar is data-driven from day one.
const TABS = [
  { id: 'rest-api', label: 'REST API' },
] as const

type TabId = (typeof TABS)[number]['id']

function RestApiTab() {
  const nodeRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!nodeRef.current) return
    // The spec is hand-maintained (springdoc doesn't support Boot 4 yet) and
    // served as a static file — also handy for Postman/codegen imports.
    //
    // DO NOT ADD `validatorUrl: null` HERE. Owner decision, 2026-09-05 (HD-264): the
    // Swagger validator badge STAYS LIVE, deliberately, for the duration of the
    // report-only Content-Security-Policy's 14-day observation window.
    //
    // With no `validatorUrl` the bundle defaults to "https://validator.swagger.io/validator"
    // and renders an <img> whose src carries THIS INSTANCE'S spec URL. Under
    // `img-src 'self' data:` that is a violation the design predicted before deployment,
    // which makes it the canary that proves the collection pipeline works: zero reports and
    // a broken sink are the same observation, so until this one is seen, "no violations"
    // means "no evidence". Fixing it first would delete the only pre-declared proof.
    //
    // Two non-obvious halves, so nobody re-opens this from the wrong end:
    //  - IT DOES NOT FIRE ON A DEVELOPER'S MACHINE. The bundle's guard rejects only
    //    "localhost", "127.0.0.1" and "none", so on a local run the badge is never rendered
    //    and the canary looks absent. Its absence locally says nothing.
    //  - WHAT IT DISCLOSES IS REAL AND WAS ACCEPTED, not overlooked: this installation's
    //    public spec URL, plus the viewer's IP and user agent, to validator.swagger.io on
    //    every load of this page. Accepted for a bounded window, not indefinitely.
    //
    // The one-line fix ships with the ENFORCEMENT ticket, after the canary has been
    // observed — at which point the canary disappearing proves the pipeline in the other
    // direction too. See docs/design/content-security-policy-proposal.md §4.4 and §11.
    SwaggerUIBundle({
      url: '/openapi.yaml',
      domNode: nodeRef.current,
      deepLinking: false,
      docExpansion: 'list',
      defaultModelsExpandDepth: 0,
      displayRequestDuration: true,
    })
  }, [])

  return <div ref={nodeRef} />
}

export default function DocsPage() {
  const [tab, setTab] = useState<TabId>('rest-api')
  const { accessToken } = useAuthStore()

  useEffect(() => {
    document.title = 'Documentation — Hamstrack'
    return () => { document.title = 'Hamstrack' }
  }, [])

  return (
    <div className="min-h-full flex flex-col" style={{ background: 'var(--color-surface)' }}>
      <header
        className="border-b"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-surface)' }}
      >
        <div
          className="mx-auto w-full flex items-center justify-between"
          style={{ maxWidth: 1080, padding: '16px 24px' }}
        >
          <Link
            to="/"
            className="font-display font-bold"
            style={{ fontSize: 20, color: 'var(--color-text)', letterSpacing: '-0.3px', textDecoration: 'none' }}
          >
            Hamstrack
          </Link>
          <Link
            to={accessToken ? '/' : '/login'}
            className="text-sm font-medium hover:underline"
            style={{ color: 'var(--color-brand-ink)' }}
          >
            {accessToken ? 'Open app' : 'Sign in'}
          </Link>
        </div>
      </header>

      <main className="flex-1 mx-auto w-full" style={{ maxWidth: 1080, padding: '40px 24px 64px' }}>
        <h1
          className="font-display font-bold"
          style={{ fontSize: 34, color: 'var(--color-text)', letterSpacing: '-0.5px', margin: 0 }}
        >
          Documentation
        </h1>

        <nav
          className="flex items-end gap-1 mt-6 border-b"
          style={{ borderColor: 'var(--color-border)' }}
          aria-label="Documentation sections"
        >
          {TABS.map(t => {
            const active = t.id === tab
            return (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className="text-sm font-medium cursor-pointer transition-colors"
                style={{
                  padding: '8px 16px 10px',
                  color: active ? 'var(--color-brand-ink)' : 'var(--color-text-secondary)',
                  borderBottom: active ? '2px solid var(--color-brand)' : '2px solid transparent',
                  marginBottom: -1,
                  background: 'none',
                }}
              >
                {t.label}
              </button>
            )
          })}
        </nav>

        <div className="mt-4">
          {tab === 'rest-api' && <RestApiTab />}
        </div>
      </main>

      <Footer />
    </div>
  )
}
