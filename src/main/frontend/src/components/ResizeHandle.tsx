import { useRef, useState } from 'react'

interface ResizeHandleProps {
  /** Which edge of the parent the handle sits on. Determines drag direction. */
  side: 'left' | 'right'
  /** Current size in px — read at drag start and used as the keyboard base. */
  size: number
  /** Clamp bounds. `max` is a getter so it can track the viewport. */
  min: number
  max: () => number
  /** Parent applies the (already-computed) next size; it may re-clamp too. */
  onResize: (next: number) => void
  /** Fired on drag start/end so the parent can suppress width transitions mid-drag. */
  onDragChange?: (dragging: boolean) => void
  ariaLabel: string
}

const KEY_STEP = 16
const KEY_STEP_LARGE = 48

/**
 * A 6px drag strip straddling one edge of a resizable panel. Reports an
 * absolute, clamped next-size to the parent (direction handled here so both the
 * left nav rail's right edge and the board panel's left edge can share it).
 * Keyboard-accessible as a vertical separator: Arrow nudges, Shift+Arrow jumps,
 * Home/End snap to bounds. The parent must be `position: relative`.
 */
export default function ResizeHandle({ side, size, min, max, onResize, onDragChange, ariaLabel }: ResizeHandleProps) {
  const [active, setActive] = useState(false)
  const [hover, setHover] = useState(false)
  const drag = useRef<{ startX: number; startSize: number } | null>(null)

  // Dragging the right edge outward (+X) grows; the left edge grows on -X.
  const dir = side === 'right' ? 1 : -1

  function clamp(v: number) {
    return Math.max(min, Math.min(max(), v))
  }

  function onPointerDown(e: React.PointerEvent) {
    e.preventDefault()
    drag.current = { startX: e.clientX, startSize: size }
    setActive(true)
    onDragChange?.(true)
    e.currentTarget.setPointerCapture(e.pointerId)
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'
  }

  function onPointerMove(e: React.PointerEvent) {
    if (!drag.current) return
    const delta = (e.clientX - drag.current.startX) * dir
    onResize(clamp(drag.current.startSize + delta))
  }

  function endDrag(e: React.PointerEvent) {
    if (!drag.current) return
    drag.current = null
    setActive(false)
    onDragChange?.(false)
    try { e.currentTarget.releasePointerCapture(e.pointerId) } catch { /* already released */ }
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
  }

  function onKeyDown(e: React.KeyboardEvent) {
    // Arrow toward the grow direction widens; away narrows. Mirrors dragging.
    const grow = side === 'right' ? 'ArrowRight' : 'ArrowLeft'
    const shrink = side === 'right' ? 'ArrowLeft' : 'ArrowRight'
    const step = e.shiftKey ? KEY_STEP_LARGE : KEY_STEP
    if (e.key === grow) { e.preventDefault(); onResize(clamp(size + step)) }
    else if (e.key === shrink) { e.preventDefault(); onResize(clamp(size - step)) }
    else if (e.key === 'Home') { e.preventDefault(); onResize(min) }
    else if (e.key === 'End') { e.preventDefault(); onResize(max()) }
  }

  const lit = active || hover

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={ariaLabel}
      aria-valuenow={Math.round(size)}
      aria-valuemin={min}
      aria-valuemax={Math.round(max())}
      tabIndex={0}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onKeyDown={onKeyDown}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        position: 'absolute',
        top: 0,
        bottom: 0,
        [side]: -3,
        width: 6,
        cursor: 'col-resize',
        zIndex: 20,
        touchAction: 'none',
        // Centred 2px accent line, revealed on hover/drag/focus
        background: 'transparent',
      }}
      onFocus={() => setHover(true)}
      onBlur={() => setHover(false)}
    >
      <div
        style={{
          position: 'absolute',
          top: 0,
          bottom: 0,
          left: 2,
          width: 2,
          background: lit ? 'var(--color-brand)' : 'transparent',
          transition: 'background 120ms ease-out',
        }}
      />
    </div>
  )
}
