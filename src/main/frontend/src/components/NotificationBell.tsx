import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { Bell } from 'lucide-react'
import { apiListNotifications, apiMarkNotificationRead, apiMarkAllNotificationsRead } from '../api'
import type { Notification } from '../types'

interface Props {
  /** Injected notification from SSE — added to list in real-time. */
  incoming?: Notification | null
}

export default function NotificationBell({ incoming }: Props) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [notifications, setNotifications] = useState<Notification[]>([])
  const menuRef = useRef<HTMLDivElement>(null)
  const btnRef = useRef<HTMLButtonElement>(null)
  const [dropPos, setDropPos] = useState<{ left: number; bottom: number } | null>(null)

  useEffect(() => {
    apiListNotifications().then(setNotifications).catch(() => {})
  }, [])

  // Prepend real-time notification from SSE
  useEffect(() => {
    if (!incoming) return
    setNotifications(prev => {
      if (prev.some(n => n.id === incoming.id)) return prev
      return [incoming, ...prev]
    })
  }, [incoming])

  // Close on outside click
  useEffect(() => {
    function handle(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    if (open) document.addEventListener('mousedown', handle)
    return () => document.removeEventListener('mousedown', handle)
  }, [open])

  // Compute dropdown position when opening so it renders fixed above the button
  function handleToggle() {
    if (!open && btnRef.current) {
      const r = btnRef.current.getBoundingClientRect()
      setDropPos({ left: r.left, bottom: window.innerHeight - r.top + 4 })
    }
    setOpen(v => !v)
  }

  const unread = notifications.filter(n => !n.read).length

  async function handleClick(n: Notification) {
    if (!n.read) {
      const updated = await apiMarkNotificationRead(n.id)
      setNotifications(prev => prev.map(x => x.id === n.id ? updated : x))
    }
    if (n.link) {
      setOpen(false)
      navigate(n.link)
    }
  }

  async function handleMarkAllRead() {
    await apiMarkAllNotificationsRead()
    setNotifications(prev => prev.map(n => ({ ...n, read: true })))
  }

  return (
    <div ref={menuRef} style={{ position: 'relative' }}>
      <button
        ref={btnRef}
        onClick={handleToggle}
        className="relative flex items-center justify-center cursor-pointer hover:opacity-80 transition-opacity"
        style={{ width: 28, height: 28, color: 'rgba(255,255,255,0.65)' }}
        title="Notifications"
      >
        <Bell size={15} />
        {unread > 0 && (
          <span
            className="absolute flex items-center justify-center font-bold"
            style={{
              top: 0, right: 0,
              width: 14, height: 14,
              background: 'var(--color-error)',
              borderRadius: '50%',
              fontSize: 9,
              color: 'white',
              lineHeight: 1,
            }}
          >
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>

      {open && dropPos && (
        <div
          style={{
            position: 'fixed',
            bottom: dropPos.bottom,
            left: dropPos.left,
            width: 320,
            maxHeight: 400,
            overflowY: 'auto',
            background: '#2a2927',
            border: '1px solid rgba(255,255,255,0.12)',
            borderRadius: 'var(--radius-md)',
            boxShadow: '0 -8px 24px rgba(0,0,0,0.4)',
            zIndex: 20,
            marginBottom: 4,
          }}
        >
          <div
            className="flex items-center justify-between px-3 py-2 border-b"
            style={{ borderColor: 'rgba(255,255,255,0.08)' }}
          >
            <span className="text-xs font-medium" style={{ color: 'white' }}>Notifications</span>
            {unread > 0 && (
              <button
                onClick={handleMarkAllRead}
                className="text-xs cursor-pointer hover:opacity-80"
                style={{ color: 'rgba(255,255,255,0.45)' }}
              >
                Mark all read
              </button>
            )}
          </div>

          {notifications.length === 0 ? (
            <div className="px-3 py-4 text-xs text-center" style={{ color: 'rgba(255,255,255,0.3)' }}>
              No notifications
            </div>
          ) : (
            notifications.map(n => (
              <button
                key={n.id}
                onClick={() => handleClick(n)}
                className="w-full text-left px-3 py-2.5 border-b cursor-pointer transition-colors"
                style={{
                  borderColor: 'rgba(255,255,255,0.06)',
                  background: n.read ? 'transparent' : 'rgba(255,255,255,0.04)',
                }}
                onMouseEnter={e => (e.currentTarget.style.background = 'rgba(255,255,255,0.07)')}
                onMouseLeave={e => (e.currentTarget.style.background = n.read ? 'transparent' : 'rgba(255,255,255,0.04)')}
              >
                <div className="flex items-start gap-2">
                  {!n.read && (
                    <span
                      className="flex-shrink-0 rounded-full mt-1"
                      style={{ width: 6, height: 6, background: 'var(--color-brand)' }}
                    />
                  )}
                  <div className={n.read ? 'pl-[14px]' : ''} style={{ flex: 1, minWidth: 0 }}>
                    <div className="text-xs font-medium truncate" style={{ color: 'rgba(255,255,255,0.9)' }}>
                      {n.title}
                    </div>
                    {n.body && (
                      <div className="text-xs truncate mt-0.5" style={{ color: 'rgba(255,255,255,0.45)' }}>
                        {n.body}
                      </div>
                    )}
                    <div className="text-xs mt-0.5 mono" style={{ color: 'rgba(255,255,255,0.25)' }}>
                      {new Date(n.createdAt).toLocaleDateString()}
                    </div>
                  </div>
                </div>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  )
}
