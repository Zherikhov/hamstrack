import { useEffect } from 'react'
import { Link } from 'react-router'
import { Kanban, FolderKanban, MessageSquare, Server } from 'lucide-react'
import { useConfigStore } from '../config'
import Footer from '../components/Footer'

// Only shipped functionality here — no roadmap promises
const FEATURES = [
  {
    icon: Kanban,
    title: 'Boards & backlog',
    text: 'Kanban boards with drag-and-drop, workflow-aware columns, and a flat backlog view for planning.',
  },
  {
    icon: FolderKanban,
    title: 'Workspaces & projects',
    text: 'Organize work into workspaces and projects with member roles, invitations, and per-project issue numbering.',
  },
  {
    icon: MessageSquare,
    title: 'Comments & attachments',
    text: 'Discuss issues in threaded comments, mention teammates, and attach files right where the work happens.',
  },
  {
    icon: Server,
    title: 'Self-host or cloud',
    text: 'One open-source codebase, two deployment models: run it on your own server or use the hosted cloud.',
  },
]

export default function LandingPage() {
  const publicSignupEnabled = useConfigStore((s) => s.config.publicSignupEnabled)

  useEffect(() => {
    document.title = 'Hamstrack — Open-source task tracker'
    return () => { document.title = 'Hamstrack' }
  }, [])

  return (
    <div className="min-h-full flex flex-col" style={{ background: 'var(--color-surface)' }}>
      {/* Top bar */}
      <header>
        <div
          className="mx-auto w-full flex items-center justify-between"
          style={{ maxWidth: 1080, padding: '20px 24px' }}
        >
          <span
            className="font-display font-bold"
            style={{ fontSize: 22, color: 'var(--color-text)', letterSpacing: '-0.3px' }}
          >
            Hamstrack
          </span>
          <nav className="flex items-center gap-3">
            <Link
              to="/login"
              className="text-sm font-medium px-3 py-1.5 rounded hover:underline"
              style={{ color: 'var(--color-text-secondary)' }}
            >
              Sign in
            </Link>
            {publicSignupEnabled && (
              <Link
                to="/register"
                className="text-sm font-medium px-4 py-1.5 rounded transition-colors"
                style={{ background: 'var(--color-brand)', color: 'white' }}
              >
                Sign up
              </Link>
            )}
          </nav>
        </div>
      </header>

      <main className="flex-1 mx-auto w-full" style={{ maxWidth: 1080, padding: '0 24px' }}>
        {/* Hero */}
        <section className="text-center" style={{ padding: '64px 0 48px' }}>
          <h1
            className="font-display font-extrabold mx-auto"
            style={{ fontSize: 52, lineHeight: 1.1, letterSpacing: '-1px', color: 'var(--color-text)', maxWidth: 720, margin: 0 }}
          >
            Track work without the weight
          </h1>
          <p
            className="mx-auto"
            style={{ fontSize: 17, lineHeight: 1.6, color: 'var(--color-text-secondary)', maxWidth: 560, marginTop: 16 }}
          >
            Hamstrack is an open-source task tracker: boards, backlog, workspaces, comments and
            attachments. Self-host it or use the cloud — same codebase, your choice.
          </p>
          <div className="flex items-center justify-center gap-3" style={{ marginTop: 32 }}>
            {publicSignupEnabled && (
              <Link
                to="/register"
                className="text-sm font-medium px-5 py-2.5 rounded transition-colors"
                style={{ background: 'var(--color-brand)', color: 'white' }}
              >
                Get started — it's free
              </Link>
            )}
            <Link
              to="/login"
              className="text-sm font-medium px-5 py-2.5 rounded border transition-colors"
              style={{ borderColor: 'var(--color-border-2)', color: 'var(--color-text)' }}
            >
              Sign in
            </Link>
          </div>
        </section>

        {/* Features */}
        <section
          className="grid gap-4"
          style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))', padding: '16px 0 64px' }}
        >
          {FEATURES.map(({ icon: Icon, title, text }) => (
            <div
              key={title}
              className="border flex flex-col gap-3"
              style={{
                background: 'white',
                borderColor: 'var(--color-border)',
                borderRadius: 'var(--radius-md)',
                padding: 24,
              }}
            >
              <Icon size={22} style={{ color: 'var(--color-brand)' }} />
              <h3 className="font-semibold" style={{ fontSize: 16, color: 'var(--color-text)', margin: 0 }}>
                {title}
              </h3>
              <p className="text-sm" style={{ lineHeight: 1.55, color: 'var(--color-text-secondary)', margin: 0 }}>
                {text}
              </p>
            </div>
          ))}
        </section>
      </main>

      <Footer />
    </div>
  )
}
