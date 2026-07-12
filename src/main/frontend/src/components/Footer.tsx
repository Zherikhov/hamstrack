import { Link } from 'react-router'

// Footer for public pages (landing, legal) — not part of the app shell
export default function Footer() {
  return (
    <footer
      className="border-t"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-surface)' }}
    >
      <div
        className="mx-auto w-full flex flex-wrap items-center justify-between gap-3"
        style={{ maxWidth: 1080, padding: '24px 24px' }}
      >
        <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
          Hamstrack — open-source task tracker © 2026
        </p>
        <nav className="flex items-center gap-4 text-sm">
          <Link to="/terms" className="hover:underline" style={{ color: 'var(--color-text-secondary)' }}>
            Terms
          </Link>
          <Link to="/privacy" className="hover:underline" style={{ color: 'var(--color-text-secondary)' }}>
            Privacy
          </Link>
          <Link to="/cookies" className="hover:underline" style={{ color: 'var(--color-text-secondary)' }}>
            Cookies
          </Link>
          <a
            href="https://github.com/Zherikhov/easyTask"
            target="_blank"
            rel="noreferrer"
            className="hover:underline"
            style={{ color: 'var(--color-text-secondary)' }}
          >
            GitHub
          </a>
        </nav>
      </div>
    </footer>
  )
}
