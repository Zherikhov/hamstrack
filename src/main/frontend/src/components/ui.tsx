import { clsx } from 'clsx'
import { forwardRef, useState, useRef, useEffect, useMemo, Children, isValidElement } from 'react'
import type { ButtonHTMLAttributes, InputHTMLAttributes, TextareaHTMLAttributes, SelectHTMLAttributes, ReactNode } from 'react'
import { ChevronsUp, ChevronUp, Equal, ChevronDown, Minus, CornerDownRight, Check, type LucideIcon } from 'lucide-react'
import type { Priority } from '../types'

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

export function Button({ variant = 'secondary', size = 'md', loading, children, className, style, ...props }: ButtonProps) {
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
      disabled={loading || props.disabled}
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

interface SelectOption { value: string; label: ReactNode; disabled: boolean }

export function Select({ label, className, id, children, value, onChange, disabled, style, compact }: SelectProps) {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-')
  const wrapRef = useRef<HTMLDivElement>(null)
  const btnRef = useRef<HTMLButtonElement>(null)
  const popRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [hi, setHi] = useState(0)
  const [pos, setPos] = useState<{ left: number; top: number; width: number } | null>(null)

  const options: SelectOption[] = useMemo(() =>
    Children.toArray(children)
      .filter(isValidElement)
      .filter(c => (c as { type?: unknown }).type === 'option')
      .map(c => {
        const p = (c as { props: { value?: unknown; children?: ReactNode; disabled?: boolean } }).props
        return { value: String(p.value ?? p.children ?? ''), label: (p.children ?? p.value ?? '') as ReactNode, disabled: !!p.disabled }
      }),
  [children])

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
}

export function Badge({ label, color, className }: BadgeProps) {
  return (
    <span
      className={clsx('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', className)}
      style={{
        background: color ? `${color}20` : 'var(--color-surface-2)',
        color: color ?? 'var(--color-text-secondary)',
        border: `1px solid ${color ? `${color}40` : 'var(--color-border)'}`,
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

export function PriorityIcon({ priority, size = 14 }: { priority: Priority; size?: number }) {
  const Icon = (priority.icon && priorityIcons[priority.icon]) || Minus
  return <Icon size={size} strokeWidth={2.5} style={{ color: priority.color, flexShrink: 0 }} />
}

export function PriorityBadge({ priority }: { priority: Priority }) {
  return (
    <span className="inline-flex items-center gap-1 text-xs" style={{ color: priority.color }}>
      <PriorityIcon priority={priority} size={13} />
      {priority.name}
    </span>
  )
}

// ── StatusBadge ───────────────────────────────────────────────────────────────

const categoryColors: Record<string, string> = {
  TODO:        'var(--color-sandbox)',
  IN_PROGRESS: 'var(--color-pending)',
  DONE:        'var(--color-brand)',
}

export function StatusBadge({ name, category, color }: { name: string; category: string; color?: string }) {
  const c = color ?? categoryColors[category] ?? 'var(--color-text-muted)'
  return <Badge label={name} color={c} />
}

// ── ParentChip ────────────────────────────────────────────────────────────────
// Compact "↳ PARENT-KEY" pill tinted by the parent's issue-type color. The color
// is config-driven (passed in from the resolved issue type) — never hardcoded.

export function ParentChip({
  parentKey, color, title, onClick,
}: { parentKey: string; color?: string; title?: string; onClick?: (e: React.MouseEvent) => void }) {
  const c = color ?? 'var(--color-text-muted)'
  return (
    <button
      type="button"
      onClick={onClick}
      title={title ? `${parentKey} · ${title}` : parentKey}
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full max-w-full cursor-pointer transition-opacity hover:opacity-80"
      style={{ background: `${c}18`, border: `1px solid ${c}40` }}
    >
      <CornerDownRight size={11} style={{ color: c, flexShrink: 0 }} />
      <span className="mono truncate" style={{ fontSize: 11, color: c }}>{parentKey}</span>
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
    const brand = '#0EA5A4'
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
