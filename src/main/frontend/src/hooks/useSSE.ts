import { useEffect, useRef } from 'react'
import { useAuthStore } from '../auth'

type EventHandler = (data: unknown) => void

/**
 * Connects to the workspace SSE stream and dispatches typed events.
 * Automatically reconnects when the connection drops.
 */
export function useSSE(wsId: string | undefined, handlers: Record<string, EventHandler>) {
  const { accessToken } = useAuthStore()
  const handlersRef = useRef(handlers)
  handlersRef.current = handlers

  useEffect(() => {
    if (!wsId || !accessToken) return

    const url = `/api/workspaces/${wsId}/sse?token=${encodeURIComponent(accessToken)}`
    let es: EventSource | null = null
    let closed = false

    function connect() {
      if (closed) return
      es = new EventSource(url)

      Object.keys(handlersRef.current).forEach(event => {
        es!.addEventListener(event, (e: MessageEvent) => {
          try {
            const data = JSON.parse(e.data)
            handlersRef.current[event]?.(data)
          } catch { /* ignore malformed events */ }
        })
      })

      es.onerror = () => {
        es?.close()
        // Reconnect after 3s
        if (!closed) setTimeout(connect, 3000)
      }
    }

    connect()
    return () => {
      closed = true
      es?.close()
    }
  }, [wsId, accessToken])
}
