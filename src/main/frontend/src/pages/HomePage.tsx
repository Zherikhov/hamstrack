import { useNavigate } from 'react-router'
import { CheckCircle2, Clock, ListChecks, TrendingUp } from 'lucide-react'
import { useAuthStore } from '../auth'
import { useMyWork, dueLabel, daysUntil, type MyIssue } from '../hooks/useMyWork'
import { Avatar, PriorityBadge } from '../components/ui'
import { SURFACE, fillOf, onSolid, ringOn, token } from '../colour'

const CARD: React.CSSProperties = {
  background: 'var(--color-card)', border: '1px solid var(--color-border)',
  borderRadius: 'var(--radius-lg)', boxShadow: 'var(--shadow-card)',
}

function greeting(): string {
  const h = new Date().getHours()
  if (h < 12) return 'Good morning'
  if (h < 18) return 'Good afternoon'
  return 'Good evening'
}

/**
 * The board snapshot's three buckets are **status categories, not statuses**, so
 * their dots are the safety-state triple from `DESIGN.md` and never a colour any
 * workspace stored. The token NAME is what is held here and {@link token} is what
 * resolves it, for the same reason `StatusBadge` does: a `var(...)` string is a
 * value nothing can measure, and the field was called `color` while carrying one,
 * which reads at a glance exactly like the stored hues two widgets below it.
 */
const CATS = [
  { key: 'TODO', name: 'To Do', token: '--color-sandbox' },
  { key: 'IN_PROGRESS', name: 'In Progress', token: '--color-pending' },
  { key: 'DONE', name: 'Done', token: '--color-success' },
]

export default function HomePage() {
  const navigate = useNavigate()
  const { user } = useAuthStore()
  const { data: issues = [], isLoading } = useMyWork(user?.id)

  const open = issues.filter(i => i.status.category !== 'DONE')
  const inProgress = issues.filter(i => i.status.category === 'IN_PROGRESS')
  const done = issues.filter(i => i.status.category === 'DONE')
  const total = issues.length
  const pct = total ? Math.round((done.length / total) * 100) : 0

  const dueSoon = open
    .filter(i => { const d = daysUntil(i.dueDate); return d !== null && d <= 7 })
    .sort((a, b) => (daysUntil(a.dueDate)! - daysUntil(b.dueDate)!))

  // Priority breakdown of open work
  const priCounts = new Map<string, { name: string; color: string; n: number }>()
  for (const i of open) {
    const p = i.priority
    const cur = priCounts.get(p.id) ?? { name: p.name, color: p.color, n: 0 }
    cur.n++; priCounts.set(p.id, cur)
  }
  const priList = [...priCounts.values()].sort((a, b) => b.n - a.n)
  const priTotal = priList.reduce((s, p) => s + p.n, 0) || 1

  function openIssue(i: MyIssue) {
    navigate(`/w/${i._wsId}/p/${i._project.id}`)
  }

  return (
    <div style={{ flex: 1, overflow: 'auto' }}>
      <div style={{ padding: '26px 26px 44px', maxWidth: 1180 }}>
        {/* Greeting */}
        <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: '-0.02em' }}>
          {greeting()}, {user?.displayName?.split(' ')[0] ?? 'there'} 👋
        </h1>
        <p style={{ fontSize: 14.5, color: 'var(--color-text-muted)', fontWeight: 500, marginTop: 3 }}>
          {isLoading ? 'Loading your work…' : `You have ${open.length} open issue${open.length !== 1 ? 's' : ''} assigned · ${dueSoon.length} due soon`}
        </p>

        {/* Stats */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16, margin: '22px 0' }}>
          <Stat icon={<ListChecks size={22} />} tint="#3b5bfd" n={open.length} label="Assigned to me" />
          <Stat icon={<Clock size={22} />} tint="var(--color-warning)" n={inProgress.length} label="In progress" />
          <Stat icon={<CheckCircle2 size={22} />} tint="var(--color-success)" n={done.length} label="Completed" />
          <Stat icon={<TrendingUp size={22} />} tint="var(--color-brand)" n={`${pct}%`} label="Progress" />
        </div>

        {/* Two columns */}
        <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 16, alignItems: 'start' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {/* Assigned to me */}
            <Widget title="Assigned to me">
              {open.length === 0 ? (
                <Empty text={isLoading ? 'Loading…' : 'Nothing assigned to you right now 🎉'} />
              ) : (
                open.slice(0, 6).map(i => <TaskRow key={i.id} i={i} onClick={() => openIssue(i)} />)
              )}
            </Widget>

            {/* Board snapshot — grouped by status category */}
            <Widget title="Board snapshot" hint={`${open.length + done.length} issues`}>
              <div style={{ display: 'flex', gap: 10, overflowX: 'auto' }}>
                {CATS.map(c => {
                  const items = issues.filter(i => i.status.category === c.key)
                  return (
                    <div key={c.key} style={{ flex: '1 1 0', minWidth: 130 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5, fontWeight: 700, color: 'var(--color-text-secondary)', marginBottom: 8 }}>
                        <span style={{ width: 8, height: 8, borderRadius: '50%', background: token(c.token) }} />{c.name}
                        <span style={{ marginLeft: 'auto', color: 'var(--color-text-muted)' }}>{items.length}</span>
                      </div>
                      {items.slice(0, 4).map(i => (
                        <button key={i.id} onClick={() => openIssue(i)}
                          className="w-full text-left cursor-pointer"
                          style={{ background: 'var(--color-surface)', borderRadius: 10, padding: 10, marginBottom: 8, border: 'none', display: 'block' }}
                          onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-surface-2)')}
                          onMouseLeave={e => (e.currentTarget.style.background = 'var(--color-surface)')}>
                          <div className="mono" style={{ fontSize: 10, color: 'var(--color-text-muted)', marginBottom: 4 }}>{i.key}</div>
                          <div style={{ fontSize: 12, fontWeight: 600, lineHeight: 1.35 }}>{i.title}</div>
                        </button>
                      ))}
                      {items.length === 0 && <div style={{ fontSize: 11.5, color: 'var(--color-text-muted)', padding: '6px 2px' }}>—</div>}
                    </div>
                  )
                })}
              </div>
            </Widget>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {/* Due soon */}
            <Widget title="Due soon">
              {dueSoon.length === 0 ? (
                <Empty text="Nothing urgent 🎉" />
              ) : (
                dueSoon.slice(0, 6).map(i => <TaskRow key={i.id} i={i} onClick={() => openIssue(i)} showDue />)
              )}
            </Widget>

            {/* Priority breakdown */}
            <Widget title="Priority breakdown">
              {priList.length === 0 ? <Empty text="No open work" /> : (
                <>
                  <div style={{ height: 12, borderRadius: 8, overflow: 'hidden', display: 'flex', margin: '4px 0 14px' }}>
                    {/* The bar is a fill and keeps every hue at full strength; the
                        segments abut, so each one's edge is the next one's hue and
                        no ring is needed. The legend squares below carry theirs. */}
                    {priList.map(p => <span key={p.name} style={{ width: `${(p.n / priTotal) * 100}%`, background: fillOf(p.color) }} />)}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
                    {priList.map(p => (
                      <div key={p.name} style={{ display: 'flex', alignItems: 'center', gap: 9, fontSize: 13 }}>
                        <span style={{ width: 10, height: 10, borderRadius: 3, background: fillOf(p.color), boxShadow: `inset 0 0 0 1px ${ringOn(p.color, SURFACE.card)}` }} />{p.name}
                        <span style={{ marginLeft: 'auto', fontWeight: 700, color: 'var(--color-text-secondary)' }}>{p.n}</span>
                      </div>
                    ))}
                  </div>
                </>
              )}
            </Widget>

            {/* Recent activity — no feed endpoint yet */}
            <Widget title="Recent activity">
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: '18px 8px', textAlign: 'center' }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-brand-ink)', background: 'color-mix(in srgb, var(--color-brand) 12%, white)', borderRadius: 999, padding: '5px 12px' }}>✦ Coming soon</span>
                <p style={{ fontSize: 12, color: 'var(--color-text-muted)', lineHeight: 1.5, maxWidth: 240 }}>
                  A live feed of comments, moves and completions across your projects will appear here.
                </p>
              </div>
            </Widget>
          </div>
        </div>
      </div>
    </div>
  )
}

function Stat({ icon, tint, n, label }: { icon: React.ReactNode; tint: string; n: number | string; label: string }) {
  return (
    <div style={{ ...CARD, padding: 18, position: 'relative', overflow: 'hidden' }}>
      <span style={{ position: 'absolute', top: 16, right: 16, color: tint, opacity: 0.9 }}>{icon}</span>
      <div style={{ fontSize: 30, fontWeight: 800, letterSpacing: '-0.02em' }}>{n}</div>
      <div style={{ fontSize: 12.5, color: 'var(--color-text-muted)', fontWeight: 600, marginTop: 2 }}>{label}</div>
    </div>
  )
}

function Widget({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section style={{ ...CARD, padding: 18 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 14 }}>
        <h3 style={{ fontSize: 15, fontWeight: 800 }}>{title}</h3>
        {hint && <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--color-text-muted)', fontWeight: 600 }}>{hint}</span>}
      </div>
      {children}
    </section>
  )
}

function Empty({ text }: { text: string }) {
  return <p style={{ fontSize: 13, color: 'var(--color-text-muted)', fontWeight: 500, padding: '8px 2px' }}>{text}</p>
}

function TaskRow({ i, onClick, showDue }: { i: MyIssue; onClick: () => void; showDue?: boolean }) {
  const due = dueLabel(i.dueDate)
  return (
    <button onClick={onClick}
      className="w-full flex items-center gap-3 cursor-pointer text-left"
      style={{ padding: '11px 0', borderTop: '1px solid var(--color-border)', background: 'none', border: 'none', borderTopWidth: 1, borderTopStyle: 'solid', borderTopColor: 'var(--color-border)' }}
      onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-surface)')}
      onMouseLeave={e => (e.currentTarget.style.background = 'none')}>
      {/* The one deliberate SOLID form in the product, so it is the one place
          `onSolid` belongs: black or white over the type's own hue, whichever
          measures higher. It shipped white unconditionally, which measured 1.92
          on the seeded Medium yellow — the tile stays a tile, only its ink moves. */}
      <span style={{ width: 20, height: 20, borderRadius: 6, display: 'grid', placeItems: 'center', fontSize: 10, color: onSolid(i.type.color), fontWeight: 800, flexShrink: 0, background: fillOf(i.type.color) }}>{i.type.name[0]}</span>
      <span style={{ flex: 1, minWidth: 0, overflow: 'hidden' }}>
        <span className="block truncate" style={{ fontSize: 13.5, fontWeight: 600 }}>{i.title}</span>
        <span className="mono" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>{i.key} · {i._project.name}</span>
      </span>
      {showDue && due
        ? <span style={{ fontSize: 12, fontWeight: 700, padding: '3px 9px', borderRadius: 8, flexShrink: 0, color: due.urgent ? 'var(--color-error)' : 'var(--color-text-secondary)', background: due.urgent ? 'color-mix(in srgb, var(--color-error) 12%, white)' : 'var(--color-surface)' }}>{due.text}</span>
        : <PriorityBadge priority={i.priority} />}
      {i.assignee && <Avatar name={i.assignee.displayName} avatarUrl={i.assignee.avatarUrl} size={22} />}
    </button>
  )
}
