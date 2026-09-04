import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useAuthStore } from '../auth'
import { useMyWork, dueLabel, type MyIssue } from '../hooks/useMyWork'
import { PriorityBadge, StatusBadge } from '../components/ui'

/** Cross-project "assigned to me" list. */
export default function MyWorkPage() {
  const navigate = useNavigate()
  const { user } = useAuthStore()
  const { data: issues = [], isLoading } = useMyWork(user?.id)
  const [openOnly, setOpenOnly] = useState(true)

  const rows = issues
    .filter(i => (openOnly ? i.status.category !== 'DONE' : true))
    .sort((a, b) => a._project.name.localeCompare(b._project.name) || a.number - b.number)

  function open(i: MyIssue) {
    navigate(`/w/${i._wsId}/p/${i._project.id}`)
  }

  return (
    <div style={{ flex: 1, overflow: 'auto' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '20px 26px 0' }}>
        <h1 style={{ fontSize: 22, fontWeight: 800 }}>My work</h1>
        <span style={{ fontSize: 13, color: 'var(--color-text-muted)', fontWeight: 600 }}>everything assigned to you across projects</span>
      </div>

      {/* Toolbar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '14px 26px' }}>
        <button
          onClick={() => setOpenOnly(v => !v)}
          className="cursor-pointer"
          style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 13px', borderRadius: 'var(--radius-md)', border: '1px solid var(--color-border-2)', background: 'var(--color-card)', fontSize: 12.5, fontWeight: 600, color: 'var(--color-text-secondary)' }}
        >
          {openOnly ? 'Open issues' : 'All issues'}
        </button>
        <span style={{ marginLeft: 'auto', fontSize: 12.5, color: 'var(--color-text-muted)', fontWeight: 600 }}>
          {isLoading ? 'loading…' : `${rows.length} issue${rows.length !== 1 ? 's' : ''}`}
        </span>
      </div>

      {/* Table */}
      <div style={{ padding: '0 26px 26px' }}>
        {rows.length === 0 && !isLoading ? (
          <div style={{ padding: '48px 0', textAlign: 'center', color: 'var(--color-text-muted)', fontSize: 14 }}>
            {openOnly ? 'No open issues assigned to you 🎉' : 'Nothing assigned to you yet'}
          </div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                {['Key', 'Title', 'Project', 'Status', 'Priority', 'Due'].map(h => (
                  <th key={h} style={{ textAlign: 'left', fontSize: 11.5, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--color-text-muted)', fontWeight: 700, padding: '10px 14px', borderBottom: '1px solid var(--color-border)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map(i => {
                const due = dueLabel(i.dueDate)
                return (
                  <tr key={i.id} onClick={() => open(i)} className="cursor-pointer"
                    style={{ borderBottom: '1px solid var(--color-border)' }}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-card)')}
                    onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                    <td style={{ padding: '12px 14px' }}><span className="mono" style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>{i.key}</span></td>
                    <td style={{ padding: '12px 14px', maxWidth: 360 }}><span className="truncate" style={{ display: 'block', fontSize: 13.5, fontWeight: 500 }}>{i.title}</span></td>
                    <td style={{ padding: '12px 14px' }}><span style={{ fontSize: 12.5, color: 'var(--color-text-secondary)', fontWeight: 600 }}>{i._project.name}</span></td>
                    <td style={{ padding: '12px 14px' }}><StatusBadge name={i.status.name} category={i.status.category} color={i.status.color} /></td>
                    <td style={{ padding: '12px 14px' }}><PriorityBadge priority={i.priority} /></td>
                    <td style={{ padding: '12px 14px' }}>
                      {due
                        ? <span style={{ fontSize: 12, fontWeight: 700, color: due.urgent ? 'var(--color-error-ink)' : 'var(--color-text-secondary)' }}>{due.text}</span>
                        : <span style={{ color: 'var(--color-text-muted)' }}>—</span>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
