import { clsx } from 'clsx'
import { forwardRef } from 'react'
import type { ButtonHTMLAttributes, InputHTMLAttributes, TextareaHTMLAttributes, SelectHTMLAttributes, ReactNode } from 'react'
import { ChevronsUp, ChevronUp, Equal, ChevronDown, Minus, type LucideIcon } from 'lucide-react'
import type { Priority } from '../types'

// ── Button ────────────────────────────────────────────────────────────────────

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'sm' | 'md'
  loading?: boolean
}

const buttonBase = 'inline-flex items-center gap-1.5 font-medium rounded transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed select-none'

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
      disabled={loading ?? props.disabled}
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
        className={clsx('w-full px-3 py-1.5 text-sm rounded border outline-none transition-colors', className)}
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
          className={clsx('w-full px-3 py-1.5 text-sm rounded border outline-none resize-none transition-colors', className)}
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

// ── Select ────────────────────────────────────────────────────────────────────

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label?: string
}

export function Select({ label, className, id, children, ...props }: SelectProps) {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-')
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={inputId} className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
          {label}
        </label>
      )}
      <select
        id={inputId}
        className={clsx('w-full px-3 py-1.5 text-sm rounded border outline-none cursor-pointer', className)}
        style={{
          background: 'white',
          borderColor: 'var(--color-border-2)',
          color: 'var(--color-text)',
        }}
        {...props}
      >
        {children}
      </select>
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
