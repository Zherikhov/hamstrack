import { Link } from 'react-router'
import { X, BookOpen, Github, ExternalLink } from 'lucide-react'
import { useConfigStore } from '../config'

const REPO_URL = 'https://github.com/Zherikhov/easyTask'

interface Props {
  onClose: () => void
}

export default function AboutModal({ onClose }: Props) {
  const version = useConfigStore(s => s.config.version)

  const overlayStyle: React.CSSProperties = {
    position: 'fixed', inset: 0, zIndex: 50,
    background: 'rgba(28,27,25,0.55)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    backdropFilter: 'blur(2px)',
  }

  const panelStyle: React.CSSProperties = {
    background: 'white',
    borderRadius: 'var(--radius-lg)',
    border: '1px solid var(--color-border)',
    width: 420,
    boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
  }

  const linkRowClass = 'flex items-center gap-2.5 px-3 py-2 rounded text-sm transition-colors hover:bg-gray-50'
  const linkRowStyle: React.CSSProperties = { color: 'var(--color-text)' }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={panelStyle} onClick={e => e.stopPropagation()}>
        <div
          className="flex items-center justify-between px-5 py-4 border-b"
          style={{ borderColor: 'var(--color-border)' }}
        >
          <span className="font-semibold text-sm">About</span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 transition-opacity">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <div className="p-5 flex flex-col gap-4">
          {/* Identity */}
          <div className="flex items-center gap-3">
            <span
              className="flex items-center justify-center rounded font-display font-bold flex-shrink-0"
              style={{ width: 40, height: 40, fontSize: 22, background: 'var(--color-brand)', color: 'white' }}
            >
              H
            </span>
            <div>
              <div className="font-display font-bold" style={{ fontSize: 17 }}>Hamstrack</div>
              <div className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                version {version || 'unknown'}
              </div>
            </div>
          </div>

          {/* Summary */}
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)', lineHeight: 1.5 }}>
            Open-source task tracker for teams — boards, backlog, workflows and
            real-time collaboration. Runs self-hosted or in the cloud from the
            same codebase.
          </p>

          {/* Links */}
          <div className="flex flex-col border-t pt-3" style={{ borderColor: 'var(--color-border)' }}>
            {/* In-app docs hub (/docs) — new tab so the user keeps their board context */}
            <a href="/docs" target="_blank" rel="noreferrer" className={linkRowClass} style={linkRowStyle}>
              <BookOpen size={15} style={{ color: 'var(--color-brand)' }} />
              <span className="flex-1">Documentation</span>
              <ExternalLink size={12} style={{ color: 'var(--color-text-muted)' }} />
            </a>
            <a href={REPO_URL} target="_blank" rel="noreferrer" className={linkRowClass} style={linkRowStyle}>
              <Github size={15} style={{ color: 'var(--color-brand)' }} />
              <span className="flex-1">Source code</span>
              <ExternalLink size={12} style={{ color: 'var(--color-text-muted)' }} />
            </a>
          </div>

          {/* Legal */}
          <div
            className="flex items-center gap-3 text-xs border-t pt-3"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-muted)' }}
          >
            <Link to="/terms" className="hover:underline" onClick={onClose}>Terms</Link>
            <Link to="/privacy" className="hover:underline" onClick={onClose}>Privacy</Link>
            <Link to="/cookies" className="hover:underline" onClick={onClose}>Cookies</Link>
          </div>
        </div>
      </div>
    </div>
  )
}
