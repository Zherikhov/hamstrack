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
            style={{ color: 'var(--color-brand)' }}
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
                  color: active ? 'var(--color-brand)' : 'var(--color-text-secondary)',
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
