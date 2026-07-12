import { useEffect } from 'react'
import { Link } from 'react-router'
import Footer from '../../components/Footer'

interface LegalLayoutProps {
  title: string
  lastUpdated: string
  children: React.ReactNode
}

// Shared wrapper for public legal pages (/terms, /privacy, /cookies):
// minimal top bar, ~720px prose column, footer
export default function LegalLayout({ title, lastUpdated, children }: LegalLayoutProps) {
  useEffect(() => {
    document.title = `${title} — Hamstrack`
    return () => { document.title = 'Hamstrack' }
  }, [title])

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
            to="/login"
            className="text-sm font-medium hover:underline"
            style={{ color: 'var(--color-brand)' }}
          >
            Sign in
          </Link>
        </div>
      </header>

      <main className="flex-1 mx-auto w-full" style={{ maxWidth: 720, padding: '48px 24px 64px' }}>
        <h1
          className="font-display font-bold"
          style={{ fontSize: 34, color: 'var(--color-text)', letterSpacing: '-0.5px', margin: 0 }}
        >
          {title}
        </h1>
        <p className="mono mt-2" style={{ color: 'var(--color-text-muted)' }}>
          Last updated: {lastUpdated}
        </p>
        <div className="legal-prose mt-8">
          {children}
        </div>
      </main>

      <Footer />
    </div>
  )
}
