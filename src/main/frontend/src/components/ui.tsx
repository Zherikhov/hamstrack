import { clsx } from 'clsx'
import { forwardRef, useState, useRef, useEffect, useMemo, Children, isValidElement, Fragment } from 'react'
import type { ButtonHTMLAttributes, InputHTMLAttributes, TextareaHTMLAttributes, SelectHTMLAttributes, ReactNode } from 'react'
import { ChevronsUp, ChevronUp, Equal, ChevronDown, Minus, CornerDownRight, Check, type LucideIcon } from 'lucide-react'
import type { Priority } from '../types'
import {
  EDGE_WEIGHT, NEUTRAL_EDGE, NEUTRAL_FILL, NEUTRAL_INK, SURFACE, inkOn, tintOf, token,
} from '../colour'

// ── Button ────────────────────────────────────────────────────────────────────

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'sm' | 'md'
  loading?: boolean
}

const buttonBase = 'inline-flex items-center gap-1.5 font-semibold rounded-md transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed select-none'

const buttonVariants: Record<ButtonVariant, string> = {
  primary: 'text-white',
  secondary: 'border',
  ghost: '',
  danger: 'text-white',
}

/**
 * The shared button.
 *
 * **`disabled` is destructured out of `props` on purpose (HD-183).** It used to be
 * left in, so `disabled={loading || props.disabled}` was computed correctly and then
 * overwritten by the very next `{...props}` — last JSX prop wins — so every caller
 * that passes *both* silently lost the loading guard: a spinner on a button that is
 * still clickable. That is a re-entrancy hole, not a cosmetic one. On
 * {@link ForgotPasswordPage}, where this was the only guard, five impatient clicks
 * spend a third of the per-IP `/api/auth/*` budget and earn the user a 429 on their
 * own next attempt; elsewhere it is a second sprint completion, a duplicate comment
 * or a second invitation acceptance. Around forty call sites pass both, so the fix
 * belongs here and nowhere else — a per-caller workaround is one the next caller
 * will not copy.
 *
 * A caller that wants a button clickable *while* it loads must therefore not pass
 * `loading`; there is deliberately no third state.
 */
export function Button({ variant = 'secondary', size = 'md', loading, children, className, style, disabled, ...props }: ButtonProps) {
  const styles: React.CSSProperties = { ...style }

  if (variant === 'primary') {
    styles.background = 'var(--color-brand)'
    styles.border = '1px solid var(--color-brand)'
  } else if (variant === 'secondary') {
    styles.background = 'white'
    styles.borderColor = 'var(--color-border-2)'
    styles.color = 'var(--color-text)'
  } else if (variant === 'ghost') {
    styles.background = 'transparent'
    styles.color = 'var(--color-text-secondary)'
    styles.border = '1px solid transparent'
  } else if (variant === 'danger') {
    styles.background = 'var(--color-error)'
    styles.border = '1px solid var(--color-error)'
  }

  return (
    <button
      className={clsx(
        buttonBase,
        buttonVariants[variant],
        size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3 py-1.5 text-sm',
        className,
      )}
      style={styles}
      disabled={loading || disabled}
      {...props}
    >
      {loading && <span className="mono text-xs">…</span>}
      {children}
    </button>
  )
}

// ── Input ─────────────────────────────────────────────────────────────────────

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  error?: string
}

export function Input({ label, error, className, id, ...props }: InputProps) {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-')
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={inputId} className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
          {label}
        </label>
      )}
      <input
        id={inputId}
        className={clsx('w-full px-3 py-2 text-sm rounded-md border outline-none transition-colors', className)}
        style={{
          background: 'white',
          borderColor: error ? 'var(--color-error)' : 'var(--color-border-2)',
          color: 'var(--color-text)',
        }}
        {...props}
      />
      {error && <span className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</span>}
    </div>
  )
}

// ── Checkbox ──────────────────────────────────────────────────────────────────

interface CheckboxProps extends InputHTMLAttributes<HTMLInputElement> {
  // ReactNode so the label can contain links (e.g. terms/privacy)
  label?: ReactNode
}

export function Checkbox({ label, className, ...props }: CheckboxProps) {
  return (
    <label className={clsx('flex items-start gap-2 text-sm cursor-pointer select-none', className)}>
      <input
        type="checkbox"
        className="mt-0.5 cursor-pointer"
        style={{ accentColor: 'var(--color-brand)', width: 15, height: 15, flexShrink: 0 }}
        {...props}
      />
      {label && <span style={{ color: 'var(--color-text-secondary)' }}>{label}</span>}
    </label>
  )
}

// ── Textarea ──────────────────────────────────────────────────────────────────

interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  function Textarea({ label, className, id, ...props }, ref) {
    const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-')
    return (
      <div className="flex flex-col gap-1">
        {label && (
          <label htmlFor={inputId} className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
            {label}
          </label>
        )}
        <textarea
          ref={ref}
          id={inputId}
          className={clsx('w-full px-3 py-2 text-sm rounded-md border outline-none resize-none transition-colors', className)}
          style={{
            background: 'white',
            borderColor: 'var(--color-border-2)',
            color: 'var(--color-text)',
          }}
          {...props}
        />
      </div>
    )
  }
)

// ── Select (custom, Beacon-styled popover) ────────────────────────────────────
// Native <select> option popups are drawn by the OS and can't be styled to match
// the UI (sharp corners, wrong colours). This keeps the familiar API —
// <Select value onChange><option/></Select> — but renders a styled dropdown.
// onChange receives a { target: { value } } shape, so existing handlers
// (e => setX(e.target.value)) keep working unchanged.

interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'size'> {
  label?: string
  onChange?: React.ChangeEventHandler<HTMLSelectElement>
  /** Inline, content-width, smaller — for filter bars and table cells. */
  compact?: boolean
}

interface SelectOption { value: string; label: ReactNode; disabled: boolean; title?: string }

/**
 * Collect <option> children, descending into fragments. A caller that wraps its
 * options in a `<>…</>` (e.g. the create dialog's Parent picker, which switches
 * between a fixed option and "No parent" + a list) would otherwise render an
 * empty dropdown, since `Children.toArray` keeps the fragment as a single node.
 */
function collectOptions(nodes: ReactNode): SelectOption[] {
  return Children.toArray(nodes)
    .filter(isValidElement)
    .flatMap(c => {
      const el = c as { type?: unknown; props: { value?: unknown; children?: ReactNode; disabled?: boolean; title?: string } }
      if (el.type === Fragment) return collectOptions(el.props.children)
      if (el.type !== 'option') return []
      const p = el.props
      return [{
        value: String(p.value ?? p.children ?? ''),
        label: (p.children ?? p.value ?? '') as ReactNode,
        disabled: !!p.disabled,
        // Carried through so a DISABLED option can say why it is disabled —
        // a greyed-out row with no reason is a dead end (HD-130 §9.1: the
        // ceiling's `missing` permission is rendered, not discovered at save).
        title: p.title,
      }]
    })
}

export function Select({
  label, className, id, children, value, onChange, disabled, style, compact,
  // Filter-bar selects render without a visible <label>; an explicit aria-label
  // keeps them named for screen readers (and findable by role+name).
  'aria-label': ariaLabel, title,
}: SelectProps) {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-')
  const wrapRef = useRef<HTMLDivElement>(null)
  const btnRef = useRef<HTMLButtonElement>(null)
  const popRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [hi, setHi] = useState(0)
  const [pos, setPos] = useState<{ left: number; top: number; width: number } | null>(null)

  const options: SelectOption[] = useMemo(() => collectOptions(children), [children])

  const current = options.find(o => o.value === String(value ?? '')) ?? options[0]

  function place() {
    const r = btnRef.current?.getBoundingClientRect()
    if (!r) return
    const estH = Math.min(options.length * 36 + 8, 264)
    const dropUp = r.bottom + estH > window.innerHeight && r.top > estH
    setPos({ left: r.left, top: dropUp ? r.top - estH - 4 : r.bottom + 4, width: r.width })
  }
  function toggle() {
    if (disabled) return
    if (!open) { place(); setHi(Math.max(0, options.findIndex(o => o.value === current?.value))) }
    setOpen(o => !o)
  }
  function choose(o: SelectOption) {
    if (o.disabled) return
    setOpen(false)
    if (o.value !== String(value ?? '')) onChange?.({ target: { value: o.value } } as unknown as React.ChangeEvent<HTMLSelectElement>)
  }

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (wrapRef.current?.contains(e.target as Node) || popRef.current?.contains(e.target as Node)) return
      setOpen(false)
    }
    function onScroll(e: Event) { if (!popRef.current?.contains(e.target as Node)) setOpen(false) }
    function onResize() { setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onResize)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onResize)
    }
  }, [open])

  function onKey(e: React.KeyboardEvent) {
    if (disabled) return
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle() }
      return
    }
    if (e.key === 'Escape') { e.preventDefault(); setOpen(false) }
    else if (e.key === 'ArrowDown') { e.preventDefault(); setHi(h => Math.min(options.length - 1, h + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setHi(h => Math.max(0, h - 1)) }
    else if (e.key === 'Enter') { e.preventDefault(); const o = options[hi]; if (o) choose(o) }
  }

  return (
    <div className="flex flex-col gap-1" ref={wrapRef}>
      {label && (
        <label htmlFor={inputId} className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
          {label}
        </label>
      )}
      <button
        type="button"
        id={inputId}
        ref={btnRef}
        disabled={disabled}
        onClick={toggle}
        onKeyDown={onKey}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        title={title}
        className={clsx(
          'rounded-md border outline-none cursor-pointer flex items-center gap-2 text-left transition-colors',
          compact ? 'px-2.5 py-1.5 text-xs' : 'w-full px-3 py-2 text-sm',
          className,
        )}
        style={{ background: 'var(--color-card)', borderColor: open ? 'var(--color-brand)' : 'var(--color-border-2)', color: 'var(--color-text)', opacity: disabled ? 0.6 : 1, ...(style as React.CSSProperties) }}
      >
        <span className="flex-1 min-w-0 truncate">{current ? current.label : ''}</span>
        <ChevronDown size={14} style={{ color: 'var(--color-text-muted)', flexShrink: 0, transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 120ms' }} />
      </button>

      {open && pos && (
        <div
          ref={popRef}
          role="listbox"
          style={{
            position: 'fixed', left: pos.left, top: pos.top, minWidth: pos.width, maxWidth: 360,
            maxHeight: 264, overflowY: 'auto', zIndex: 80,
            background: 'var(--color-card)', border: '1px solid var(--color-border-2)',
            borderRadius: 'var(--radius-md)', boxShadow: 'var(--shadow-lg)', padding: 4,
          }}
        >
          {options.map((o, idx) => {
            const selected = o.value === current?.value
            const active = idx === hi
            return (
              <div
                key={o.value + ':' + idx}
                role="option"
                aria-selected={selected}
                aria-disabled={o.disabled || undefined}
                title={o.title}
                onMouseEnter={() => setHi(idx)}
                onClick={() => choose(o)}
                className="flex items-center gap-2 cursor-pointer"
                style={{
                  padding: '8px 10px', borderRadius: 'var(--radius-sm)', fontSize: 13.5,
                  color: o.disabled ? 'var(--color-text-muted)' : 'var(--color-text)',
                  background: selected
                    ? 'color-mix(in srgb, var(--color-brand) 12%, var(--color-card))'
                    : (active ? 'var(--color-surface)' : 'transparent'),
                  opacity: o.disabled ? 0.55 : 1,
                }}
              >
                <span className="flex-1 min-w-0 truncate">{o.label}</span>
                {selected && <Check size={14} style={{ color: 'var(--color-brand)', flexShrink: 0 }} />}
              </div>
            )
          })}
          {options.length === 0 && (
            <div style={{ padding: '8px 10px', fontSize: 13, color: 'var(--color-text-muted)' }}>No options</div>
          )}
        </div>
      )}
    </div>
  )
}

// ── Badge ─────────────────────────────────────────────────────────────────────

interface BadgeProps {
  label: string
  color?: string
  className?: string
  /**
   * The opaque surface the badge itself sits on — what its tint is composited
   * against. The card by default; pass the rail's colour on a dark surface.
   */
  surface?: string
}

/**
 * The tinted badge `DESIGN.md` mandates, with two things now computed rather
 * than layered (HD-176):
 *
 * - **the tint is opaque.** An 8-digit overlay composites over whatever happens
 *   to be behind the element, so the badge's background over a hovered row was
 *   never the colour anybody measured. Compositing it here against a named
 *   surface makes the background a known value in every row state.
 * - **the label is `inkOn` that tint**, not the raw stored hue. Above 4.5:1 the
 *   derivation is the identity and the label is the stored hex byte for byte;
 *   below it the same hue is dimmed until it can be read. Nothing about the
 *   badge's size, padding or radius changes — the box is the one that was
 *   already there.
 */
export function Badge({ label, color, className, surface = SURFACE.card }: BadgeProps) {
  const tint = color ? tintOf(color, surface) : NEUTRAL_FILL
  const edge = color ? tintOf(color, surface, EDGE_WEIGHT) : NEUTRAL_EDGE
  return (
    <span
      className={clsx('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', className)}
      style={{
        background: tint,
        color: color ? inkOn(color, tint) : NEUTRAL_INK,
        border: `1px solid ${edge}`,
      }}
    >
      {label}
    </span>
  )
}

// ── Avatar ────────────────────────────────────────────────────────────────────

export function Avatar({ name, avatarUrl, size = 24 }: { name: string; avatarUrl?: string; size?: number }) {
  if (avatarUrl) {
    return <img src={avatarUrl} alt={name} width={size} height={size} className="rounded-full object-cover" />
  }
  const initials = name.split(' ').map(w => w[0]).join('').toUpperCase().slice(0, 2)
  return (
    <span
      className="rounded-full flex items-center justify-center text-white font-medium flex-shrink-0"
      style={{ width: size, height: size, fontSize: size * 0.4, background: 'var(--color-brand)' }}
    >
      {initials}
    </span>
  )
}

// ── PriorityBadge ─────────────────────────────────────────────────────────────
// Priorities are catalog entries since M1: color comes from the entry, the
// icon field holds a lucide icon name (admin-editable)

const priorityIcons: Record<string, LucideIcon> = {
  'chevrons-up':  ChevronsUp,
  'chevron-up':   ChevronUp,
  'equal':        Equal,
  'chevron-down': ChevronDown,
  'minus':        Minus,
}

/**
 * The stroked glyph is ink, not a fill — a 2.5px stroke is text-weight, so it
 * takes the text threshold. `SURFACE.row` by default because these sit in board
 * cards, backlog rows and table rows that tint on hover: deriving against the
 * darkest state a row can take keeps the ratio true in both, where deriving
 * against the resting card would hold only in the state that was screenshotted.
 */
export function PriorityIcon({ priority, size = 14, surface = SURFACE.row }: {
  priority: Priority; size?: number; surface?: string
}) {
  const Icon = (priority.icon && priorityIcons[priority.icon]) || Minus
  return <Icon size={size} strokeWidth={2.5} style={{ color: inkOn(priority.color, surface), flexShrink: 0 }} />
}

export function PriorityBadge({ priority, surface = SURFACE.row }: { priority: Priority; surface?: string }) {
  return (
    <span className="inline-flex items-center gap-1 text-xs" style={{ color: inkOn(priority.color, surface) }}>
      <PriorityIcon priority={priority} size={13} surface={surface} />
      {priority.name}
    </span>
  )
}

// ── StatusBadge ───────────────────────────────────────────────────────────────

/**
 * The fallback when a status arrives with no colour of its own: the safety-state
 * triple (sandbox slate → pending amber → production teal) `DESIGN.md` declares.
 *
 * Resolved to channels rather than left as `var(...)` strings — as a raw token
 * the badge's tint and hairline were being written as `var(--color-sandbox)20`,
 * which is not a colour any browser parses, so a status without a stored colour
 * had silently lost both. Reading the token through `colour.ts` keeps the value
 * in `index.css` (one file re-skins the app) and gives the badge something it can
 * measure.
 */
const categoryTokens: Record<string, string> = {
  TODO:        '--color-sandbox',
  IN_PROGRESS: '--color-pending',
  DONE:        '--color-brand',
}

export function StatusBadge({ name, category, color, surface }: {
  name: string; category: string; color?: string; surface?: string
}) {
  const fallback = categoryTokens[category]
  return <Badge label={name} color={color ?? (fallback ? token(fallback) : undefined)} surface={surface} />
}

// ── ParentChip ────────────────────────────────────────────────────────────────
// Compact "↳ PARENT-KEY" pill tinted by the parent's issue-type color. The color
// is config-driven (passed in from the resolved issue type) — never hardcoded.

/** The pill's own tint is what its key is painted on, so that is what the key is derived against. */
const PARENT_TINT_WEIGHT = 0x18 / 255

export function ParentChip({
  parentKey, color, title, onClick, surface = SURFACE.row,
}: {
  parentKey: string; color?: string; title?: string
  onClick?: (e: React.MouseEvent) => void; surface?: string
}) {
  const tint = color ? tintOf(color, surface, PARENT_TINT_WEIGHT) : NEUTRAL_FILL
  const edge = color ? tintOf(color, surface, EDGE_WEIGHT) : NEUTRAL_EDGE
  const ink = color ? inkOn(color, tint) : NEUTRAL_INK
  return (
    <button
      type="button"
      onClick={onClick}
      title={title ? `${parentKey} · ${title}` : parentKey}
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full max-w-full cursor-pointer transition-opacity hover:opacity-80"
      style={{ background: tint, border: `1px solid ${edge}` }}
    >
      <CornerDownRight size={11} style={{ color: ink, flexShrink: 0 }} />
      <span className="mono truncate" style={{ fontSize: 11, color: ink }}>{parentKey}</span>
    </button>
  )
}

// ── ChildrenProgress ──────────────────────────────────────────────────────────
// Roll-up bar for a parent issue: "N of M done". Teal fill (production-trusted
// state, DESIGN.md). `compact` renders a slim inline pill for board/backlog cards.

export function ChildrenProgress({
  done, total, compact,
}: { done: number; total: number; compact?: boolean }) {
  if (total <= 0) return null
  const pct = Math.round((done / total) * 100)
  const complete = done >= total

  if (compact) {
    // Teal tint when all children are done (production-trusted state), muted otherwise.
    // Read from the token so the one file that re-skins the app still re-skins this.
    const brand = token('--color-brand')
    return (
      <span
        className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full mono"
        title={`${done} of ${total} sub-issues done`}
        style={{
          fontSize: 10.5,
          background: complete ? `${brand}18` : 'var(--color-surface-2)',
          color: complete ? brand : 'var(--color-text-muted)',
          border: `1px solid ${complete ? `${brand}40` : 'var(--color-border)'}`,
        }}
      >
        {done}/{total}
      </span>
    )
  }

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between">
        <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Sub-issues</span>
        <span className="mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {done} of {total} done
        </span>
      </div>
      <div className="w-full rounded-full overflow-hidden" style={{ height: 6, background: 'var(--color-surface-2)' }}>
        <div
          className="h-full rounded-full transition-all"
          style={{ width: `${pct}%`, background: 'var(--color-brand)' }}
        />
      </div>
    </div>
  )
}
