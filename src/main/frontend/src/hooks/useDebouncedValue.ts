import { useEffect, useState } from 'react'

/**
 * The value as it was `delay` ms after the last change. Used by the command
 * palette to throttle its server-backed issue search to one request per idle
 * window (HD-39 §6.5) — the debounced value feeds the TanStack query KEY, so a
 * stale response can never overwrite a newer one.
 */
export function useDebouncedValue<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const handle = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(handle)
  }, [value, delay])

  return debounced
}
