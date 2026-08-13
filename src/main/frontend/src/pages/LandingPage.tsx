import { useEffect } from 'react'
import { Link } from 'react-router'
import { Columns3, FolderKanban, MessageSquare, SlidersHorizontal, type LucideIcon } from 'lucide-react'
import { useConfigStore } from '../config'
import Footer from '../components/Footer'

// ── Static product-visual data (a stylised mirror of the authenticated /home
//    dashboard). Neutral demo issue keys only — never a real customer's key. ──
const TYPE = { bug: 'var(--color-error)', task: '#3B5BFD', story: '#7C6CF5' }

const STATS = [
  { n: '7', label: 'Assigned to me', tint: '#3B5BFD' },
  { n: '5', label: 'Created by me', tint: 'var(--color-accent-2)' },
  { n: '4', label: 'Planned', tint: 'var(--color-sandbox)' },
  { n: '12', label: 'Completed', tint: 'var(--color-success)' },
]

const ASSIGNED = [
  { badge: 'B', tint: TYPE.bug, title: 'Refresh-token rotation on re-login', meta: 'WEB-142 · Website', pri: 'var(--color-error)' },
  { badge: 'T', tint: TYPE.task, title: 'Onboarding empty states', meta: 'APP-31 · Mobile app', pri: 'var(--color-pending)' },
  { badge: 'S', tint: TYPE.story, title: 'Export board to CSV', meta: 'WEB-138 · Website', pri: 'var(--color-sandbox)' },
  { badge: 'T', tint: TYPE.task, title: 'Nightly backup verification job', meta: 'OPS-88 · Platform', pri: 'var(--color-pending)' },
]

const DUE = [
  { badge: 'B', tint: TYPE.bug, title: 'Fix rate-limit header', meta: 'WEB-140 · Website', due: 'Today', now: true },
  { badge: 'T', tint: TYPE.task, title: 'Draft release notes', meta: 'OPS-84 · Platform', due: '2d', now: false },
]

const PRIORITY = [
  { name: 'Urgent', color: 'var(--color-error)', n: 3, w: 32 },
  { name: 'High', color: 'var(--color-pending)', n: 4, w: 40 },
  { name: 'Medium', color: 'var(--color-sandbox)', n: 2, w: 28 },
]

// Only shipped functionality — no roadmap promises.
const FEATURES: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: Columns3, title: 'Boards & backlog', text: 'Drag-and-drop kanban with workflow-aware columns, plus a flat backlog for planning.' },
  { icon: FolderKanban, title: 'Workspaces & projects', text: 'Member roles, email-bound invitations, and per-project issue numbering.' },
  { icon: MessageSquare, title: 'Comments & attachments', text: 'Threaded discussion and files right on the issue, with a full activity history.' },
  { icon: SlidersHorizontal, title: 'Custom fields & taxonomy', text: 'Global statuses, priorities, types and fields reused across projects via workflows and sets.' },
]

export default function LandingPage() {
  const publicSignupEnabled = useConfigStore((s) => s.config.publicSignupEnabled)

  useEffect(() => {
    document.title = 'Hamstrack — Open-source task tracker'
    return () => { document.title = 'Hamstrack' }
  }, [])

  return (
    <div className="lp min-h-full flex flex-col">
      {/* ── Dark hero stage (echoes the app nav rail) ── */}
      <div className="lp-stage">
        <div className="lp-wrap">
          <header className="lp-nav">
            <span className="lp-brand"><span className="lp-logo">H</span>Hamstrack</span>
            <nav className="lp-navlinks">
              <a href="#features" className="lp-hide-sm">Features</a>
              <a href="#deploy" className="lp-hide-sm">Deploy</a>
              <Link to="/docs" className="lp-hide-sm">Docs</Link>
              <Link to="/login" className="lp-signin">Sign in</Link>
              {publicSignupEnabled && (
                <Link to="/register" className="lp-btn lp-btn-primary">Sign up</Link>
              )}
            </nav>
          </header>

          <section className="lp-hero">
            <span className="lp-eyebrow lp-anim-in" style={{ animationDelay: '.05s' }}>◆ open-source · self-host or cloud</span>
            <h1 className="lp-h1 lp-anim-in" style={{ animationDelay: '.12s' }}>The task tracker you can actually trust</h1>
            <p className="lp-lead lp-anim-in" style={{ animationDelay: '.2s' }}>
              A calm, work-centric home for your whole team — boards, backlog, workspaces and a
              first-class admin console, in a fast instrument that shows its work instead of hiding it.
            </p>
            <div className="lp-cta lp-anim-in" style={{ animationDelay: '.28s' }}>
              {publicSignupEnabled && (
                <Link to="/register" className="lp-btn lp-btn-primary lp-btn-lg">Get started — it's free</Link>
              )}
              <Link to="/login" className={`lp-btn lp-btn-lg ${publicSignupEnabled ? 'lp-btn-ghost-dark' : 'lp-btn-primary'}`}>Sign in</Link>
            </div>

            {/* Safety-state band — the product's trust machine (DESIGN.md) */}
            <div className="lp-states lp-anim-in" style={{ animationDelay: '.36s' }}>
              <StateChip dot="var(--color-sandbox)" bg="rgba(102,112,133,.16)" color="#B7C0CE" name="Sandbox" sub="draft & try" />
              <StateChip dot="var(--color-pending)" bg="rgba(247,144,9,.14)" color="var(--color-pending)" name="Pending" sub="review & approve" />
              <StateChip dot="var(--color-brand)" bg="rgba(14,165,164,.16)" color="var(--color-rail-active)" name="Production" sub="trusted & live" />
            </div>
          </section>

          {/* ── Home-dashboard window, peeking up from behind the panel below ── */}
          <div className="lp-stagewin lp-anim-rise">
            <div className="lp-win lp-anim-float">
              <div className="lp-wintop">
                <span className="lp-tdot" style={{ background: 'var(--color-error)' }} />
                <span className="lp-tdot" style={{ background: 'var(--color-pending)' }} />
                <span className="lp-tdot" style={{ background: 'var(--color-success)' }} />
                <span className="lp-winurl mono">hamstrack.com/home</span>
              </div>
              <div className="lp-winbody">
                <div className="lp-rail">
                  <div className="lp-rbrand"><span className="lp-rlogo">H</span>Hamstrack</div>
                  <div className="lp-rnew">+ New issue</div>
                  <div className="lp-ritem on"><span className="lp-ric" />Home</div>
                  <div className="lp-ritem"><span className="lp-ric" />My work</div>
                  <div className="lp-ritem"><span className="lp-ric" />Search</div>
                  <div className="lp-rsec">Website</div>
                  <div className="lp-ritem"><span className="lp-ric" />Board</div>
                  <div className="lp-ritem"><span className="lp-ric" />Backlog</div>
                </div>

                <div className="lp-dash">
                  <div className="lp-greet">Good morning, Alex 👋</div>
                  <div className="lp-greetsub">You have 7 open issues assigned · 2 due soon</div>

                  <div className="lp-stats">
                    {STATS.map((s) => (
                      <div className="lp-stat" key={s.label}>
                        <span className="lp-sic" style={{ background: s.tint }} />
                        <div className="n">{s.n}</div>
                        <div className="l">{s.label}</div>
                      </div>
                    ))}
                  </div>

                  <div className="lp-cols">
                    <div className="lp-wid">
                      <h4>Assigned to me <span className="lp-hint">7 open</span></h4>
                      {ASSIGNED.map((i) => (
                        <div className="lp-trow" key={i.meta}>
                          <span className="lp-tbadge" style={{ background: i.tint }}>{i.badge}</span>
                          <span className="lp-tmain">
                            <span className="lp-ttitle">{i.title}</span>
                            <span className="lp-tmeta">{i.meta}</span>
                          </span>
                          <span className="lp-pri" style={{ background: i.pri }} />
                          <span className="lp-av" />
                        </div>
                      ))}
                    </div>

                    <div className="lp-rcol">
                      <div className="lp-wid">
                        <h4>Due soon</h4>
                        {DUE.map((i) => (
                          <div className="lp-trow" key={i.meta}>
                            <span className="lp-tbadge" style={{ background: i.tint }}>{i.badge}</span>
                            <span className="lp-tmain">
                              <span className="lp-ttitle">{i.title}</span>
                              <span className="lp-tmeta">{i.meta}</span>
                            </span>
                            <span className={`lp-due ${i.now ? 'now' : 'soon'}`}>{i.due}</span>
                          </div>
                        ))}
                      </div>
                      <div className="lp-wid">
                        <h4>Priority breakdown</h4>
                        <div className="lp-pbar">
                          {PRIORITY.map((p) => (
                            <span key={p.name} style={{ width: `${p.w}%`, background: p.color }} />
                          ))}
                        </div>
                        {PRIORITY.map((p) => (
                          <div className="lp-prow" key={p.name}>
                            <span className="sw" style={{ background: p.color }} />{p.name}
                            <span className="pn">{p.n}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ── Light content panel that rises over the window (the peek) ── */}
      <div className="lp-below">
        <div className="lp-grab" />
        <div className="lp-wrap">
          <section id="features" className="lp-section">
            <div className="lp-sechead">
              <h2>Built for teams who read the source</h2>
              <p>Only shipped features here — no roadmap promises. Everything below runs today.</p>
            </div>
            <div className="lp-grid">
              {FEATURES.map(({ icon: Icon, title, text }) => (
                <div className="lp-fcard" key={title}>
                  <div className="lp-fic"><Icon strokeWidth={2} /></div>
                  <div>
                    <h3>{title}</h3>
                    <p>{text}</p>
                  </div>
                </div>
              ))}
            </div>
          </section>

          <section id="deploy" className="lp-section" style={{ paddingTop: 0 }}>
            <div className="lp-sechead">
              <h2>Your infrastructure, your rules</h2>
              <p>One codebase, two deployment models. The product is identical — you choose where it runs.</p>
            </div>
            <div className="lp-deploy">
              <div className="lp-dcard">
                <span className="lp-tag" style={{ background: 'rgba(102,112,133,.14)', color: 'var(--color-sandbox)' }}>SELF-HOSTED</span>
                <h3>Run it yourself</h3>
                <p>Docker Compose and PostgreSQL. Own every byte of your data.</p>
                <ul>
                  <li>Local or S3-compatible storage</li>
                  <li>No telemetry, no lock-in</li>
                  <li>MIT-licensed core</li>
                </ul>
              </div>
              <div className="lp-dcard brand">
                <span className="lp-tag" style={{ background: 'rgba(14,165,164,.12)', color: 'var(--color-brand)' }}>HOSTED CLOUD</span>
                <h3>Or let us run it</h3>
                <p>Same features, managed and updated for you.</p>
                <ul>
                  <li>Sign up and start in seconds</li>
                  <li>Automatic backups &amp; upgrades</li>
                  <li>Scales with your team</li>
                </ul>
              </div>
            </div>
          </section>

          <section className="lp-final">
            <div className="lp-finalbox">
              <h2>Start tracking in minutes</h2>
              <p>{publicSignupEnabled ? 'Free to start. Open source forever.' : 'Open source forever. Sign in to get to work.'}</p>
              {publicSignupEnabled
                ? <Link to="/register" className="lp-btn lp-btn-primary lp-btn-lg">Get started — it's free</Link>
                : <Link to="/login" className="lp-btn lp-btn-primary lp-btn-lg">Sign in</Link>}
            </div>
          </section>
        </div>

        <Footer />
      </div>
    </div>
  )
}

function StateChip({ dot, bg, color, name, sub }: { dot: string; bg: string; color: string; name: string; sub: string }) {
  return (
    <div className="lp-state">
      <span className="lp-chip" style={{ background: bg, color }}>
        <span className="lp-cdot" style={{ background: dot }} />{name}
      </span>
      <span className="lp-statelbl">{sub}</span>
    </div>
  )
}
