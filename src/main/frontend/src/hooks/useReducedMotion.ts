import { useEffect, useState } from 'react'

/**
 * Tracks the user's `prefers-reduced-motion` setting so shell animations
 * (nav-rail collapse, board panel slide/reflow) can fall back to instant.
 * Shared by HD-53/HD-54/HD-56 — honour it wherever a transition would play.
 */
export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() =>
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true,
  )

  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const onChange = () => setReduced(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  return reduced
}
