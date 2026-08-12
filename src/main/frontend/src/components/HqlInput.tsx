import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { HqlError } from '../api'
import { apiSearchSuggest } from '../api'
import type { SearchSchema, SearchValueOption } from '../types'

// ── Token model ──────────────────────────────────────────────────────────────
// A pragmatic, token-aware suggester — NOT a grammar validator. The parser on the
// server is the source of truth; here we only look at the token under the cursor
// and its immediate left-neighbour to decide what class of thing to offer
// (field → operator → value, plus boolean keywords). Anything the schema doesn't
// know about, we don't invent.

interface Suggestion {
  // What gets inserted in place of the current token.
  insert: string
  // What's shown in the dropdown (label may differ, e.g. a display name).
  label: string
  hint?: string
  kind: 'field' | 'operator' | 'value' | 'keyword' | 'function'
}

// The word being typed at the cursor: its [start,end) span and text. A "word" is a
// run of non-space, non-paren, non-comma chars; quoted strings are treated as one
// word so value suggestions can replace them.
function currentToken(text: string, caret: number): { start: number; end: number; value: string } {
  let start = caret
  // Walk left while inside a word (or an open quote).
  while (start > 0 && !/[\s(),]/.test(text[start - 1])) start--
  let end = caret
  while (end < text.length && !/[\s(),]/.test(text[end])) end++
  return { start, end, value: text.slice(start, caret) }
}

// The most recent "field name" token to the left of the caret that is followed by
// an operator gap — used to know which field's operators/values to offer. Cheap
// heuristic: the last word before the current token that matches a known field.
function fieldContextBefore(text: string, tokenStart: number, fieldNames: Set<string>): string | null {
  const left = text.slice(0, tokenStart)
  const words = left.split(/[\s(),]+/).filter(Boolean)
  // Scan from the right for the nearest known field name.
  for (let i = words.length - 1; i >= 0; i--) {
    const w = words[i].toLowerCase()
    if (fieldNames.has(w)) {
      // If there's a boolean keyword AFTER this field, the field's clause is closed.
      const after = words.slice(i + 1).map(x => x.toUpperCase())
      if (after.some(k => k === 'AND' || k === 'OR' || k === 'NOT')) return null
      return w
    }
    if (['AND', 'OR', 'NOT', '('].includes(words[i].toUpperCase())) return null
  }
  return null
}

// Whether the token immediately before the current one is a known field (→ offer
// operators) vs. whether an operator already sits between field and caret (→ values).
function lastNonSpaceWord(text: string, before: number): string {
  const left = text.slice(0, before).trimEnd()
  const m = left.match(/([^\s(),]+)$/)
  return m ? m[1] : ''
}

const BOOL_KEYWORDS = ['AND', 'OR', 'NOT']

// Friendly, user-facing labels for the internal FieldDataType enum names the
// /schema endpoint sends (com.hamstrack.search.FieldDataType). Covers every enum
// constant; unmapped values fall back to a lowercased, underscore-free form so we
// never surface a raw token like "enum_ref".
const FIELD_TYPE_HINTS: Record<string, string> = {
  ENUM_REF: 'option',
  USER_REF: 'user',
  ISSUE_REF: 'issue',
  TEXT: 'text',
  DATE: 'date',
  TIMESTAMP: 'date & time',
  NUMBER: 'number',
}

function fieldTypeHint(type: string): string {
  return FIELD_TYPE_HINTS[type.toUpperCase()] ?? type.toLowerCase().replace(/_/g, ' ')
}

export interface HqlInputProps {
  wsId: string
  value: string
  onChange: (v: string) => void
  onSubmit?: () => void
  schema?: SearchSchema
  // The 422 highlight (position/length) to underline, cleared as the user types.
  error?: HqlError | null
  placeholder?: string
  autoFocus?: boolean
  // Visual density: 'bar' for the top search bar, 'panel' for the results page input.
  tone?: 'bar' | 'panel'
}

/**
 * HQL text input with token-aware autocomplete and inline error underlining.
 * Reused by the top search bar and the results page. Config-driven: fields,
 * operators and value picklists all come from the search schema; user-valued
 * fields (assignee/reporter) hit the debounced /suggest endpoint.
 */
export default function HqlInput({
  wsId, value, onChange, onSubmit, schema, error, placeholder, autoFocus, tone = 'panel',
}: HqlInputProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [caret, setCaret] = useState(0)
  const [open, setOpen] = useState(false)
  const [hi, setHi] = useState(0)
  const [userSuggest, setUserSuggest] = useState<SearchValueOption[]>([])

  const fields = schema?.fields ?? []
  const fieldNames = useMemo(() => new Set(fields.map(f => f.name.toLowerCase())), [fields])
  const keywords = schema?.keywords ?? []
  const values = schema?.values ?? {}

  const token = useMemo(() => currentToken(value, caret), [value, caret])

  // Decide which class of suggestions applies at the caret.
  const fieldCtxName = useMemo(
    () => fieldContextBefore(value, token.start, fieldNames),
    [value, token.start, fieldNames]
  )
  const fieldCtx = useMemo(
    () => fields.find(f => f.name.toLowerCase() === fieldCtxName),
    [fields, fieldCtxName]
  )
  const prevWord = useMemo(() => lastNonSpaceWord(value, token.start), [value, token.start])

  // If we're in a field's clause, is an operator already present between the field
  // and the caret? (then we suggest values, else operators).
  const clauseAfterField = useMemo(() => {
    if (!fieldCtxName) return ''
    const idx = value.toLowerCase().lastIndexOf(fieldCtxName, token.start)
    return value.slice(idx + fieldCtxName.length, token.start)
  }, [value, fieldCtxName, token.start])

  const opPresent = useMemo(() => {
    if (!fieldCtx) return false
    return fieldCtx.operators.some(op => clauseAfterField.toUpperCase().includes(op.toUpperCase()))
      || /[=<>~!]/.test(clauseAfterField)
  }, [fieldCtx, clauseAfterField])

  // ── Debounced user-field typeahead ──────────────────────────────────────────
  useEffect(() => {
    if (!fieldCtx || fieldCtx.valueSuggest !== 'USER' || !opPresent) {
      setUserSuggest([])
      return
    }
    const q = token.value.replace(/^["']|["']$/g, '')
    const handle = setTimeout(async () => {
      try {
        const res = await apiSearchSuggest(wsId, fieldCtx.name, q.slice(0, 100))
        setUserSuggest(res.suggestions.map(s => ({ label: s.label, value: s.value })))
      } catch { setUserSuggest([]) }
    }, 180)
    return () => clearTimeout(handle)
  }, [wsId, fieldCtx, opPresent, token.value])

  // ── Build the suggestion list for the current context ───────────────────────
  const suggestions = useMemo<Suggestion[]>(() => {
    const q = token.value.toLowerCase().replace(/^["']/, '')
    const pw = prevWord.toUpperCase()

    // After a value/keyword boundary at start, or after a boolean keyword / '(' /
    // nothing → suggest fields (and NOT/booleans as appropriate).
    const wantOperators = !!fieldCtx && !opPresent && fieldNames.has(pw.toLowerCase())
    const wantValues = !!fieldCtx && opPresent

    let out: Suggestion[] = []

    if (wantOperators && fieldCtx) {
      out = fieldCtx.operators.map(op => ({
        insert: op, label: op, kind: 'operator' as const, hint: 'operator',
      }))
    } else if (wantValues && fieldCtx) {
      const vs = fieldCtx.valueSuggest
      if (vs === 'USER') {
        out = userSuggest.map(o => ({
          insert: quoteValue(o.value ?? o.label), label: o.label, hint: o.value ?? undefined, kind: 'value' as const,
        }))
        // Offer currentUser() for user fields.
        for (const fn of fieldCtx.functions) {
          out.unshift({ insert: fn, label: fn, kind: 'function', hint: 'function' })
        }
      } else if (vs && values[vs]) {
        out = values[vs].map(o => ({
          insert: quoteValue(o.value ?? o.label), label: o.label, kind: 'value' as const,
        }))
      } else {
        // No picklist (date/number/text) — offer any zero-arg functions on the field.
        out = fieldCtx.functions.map(fn => ({ insert: fn, label: fn, kind: 'function' as const, hint: 'function' }))
      }
    } else {
      // Field position — offer fields, boolean keywords, NOT and grouping helpers.
      out = fields.map(f => ({
        insert: f.name, label: f.name, kind: 'field' as const, hint: fieldTypeHint(f.type),
      }))
      for (const k of keywords) {
        if (BOOL_KEYWORDS.includes(k.toUpperCase()) || k.toUpperCase() === 'ORDER BY') {
          out.push({ insert: k, label: k, kind: 'keyword' as const, hint: 'keyword' })
        }
      }
    }

    // Prefix-filter by what's typed (empty token → show all, capped).
    const filtered = q
      ? out.filter(s => s.insert.toLowerCase().startsWith(q) || s.label.toLowerCase().startsWith(q))
      : out
    return filtered.slice(0, 40)
  }, [fields, fieldNames, keywords, values, fieldCtx, opPresent, prevWord, token.value, userSuggest])

  useEffect(() => { setHi(0) }, [suggestions.length, token.start])

  const applySuggestion = useCallback((s: Suggestion) => {
    // A function insert (currentUser()) leaves the caret before the closing paren.
    const before = value.slice(0, token.start)
    const after = value.slice(token.end)
    const trailing = s.kind === 'field' || s.kind === 'operator' ? ' ' : ' '
    const next = before + s.insert + trailing + after
    onChange(next)
    const nextCaret = (before + s.insert + trailing).length
    setOpen(true)
    requestAnimationFrame(() => {
      inputRef.current?.focus()
      inputRef.current?.setSelectionRange(nextCaret, nextCaret)
      setCaret(nextCaret)
    })
  }, [value, token.start, token.end, onChange])

  function syncCaret() {
    const el = inputRef.current
    if (el && el.selectionStart != null) setCaret(el.selectionStart)
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    // Explicit "request completion" keys open a closed dropdown (e.g. after
    // landing on the results page with a prefilled query) without hijacking
    // plain focus. ArrowDown or Ctrl/Cmd+Space summon suggestions.
    if (!open && (e.key === 'ArrowDown' || (e.key === ' ' && (e.ctrlKey || e.metaKey)))) {
      e.preventDefault()
      setOpen(true)
      return
    }
    if (open && suggestions.length > 0) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setHi(h => Math.min(suggestions.length - 1, h + 1)); return }
      if (e.key === 'ArrowUp') { e.preventDefault(); setHi(h => Math.max(0, h - 1)); return }
      if (e.key === 'Tab' || (e.key === 'Enter' && suggestions[hi])) {
        // Enter picks a suggestion only when the dropdown is active AND the user is
        // clearly mid-token; otherwise Enter submits. Tab always picks.
        if (e.key === 'Tab') { e.preventDefault(); applySuggestion(suggestions[hi]); return }
      }
      if (e.key === 'Escape') { e.preventDefault(); setOpen(false); return }
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      if (open && suggestions.length > 0 && token.value && suggestions[hi]) {
        applySuggestion(suggestions[hi])
      } else {
        setOpen(false)
        onSubmit?.()
      }
    }
  }

  // ── Error underline overlay ─────────────────────────────────────────────────
  // We can't underline inside a plain <input>, so we mirror the text in an
  // absolutely-positioned layer behind it and draw a wavy underline on the span.
  const underline = useMemo(() => {
    if (!error || error.position == null) return null
    const start = Math.max(0, Math.min(error.position, value.length))
    const len = error.length && error.length > 0 ? error.length : Math.max(1, value.length - start)
    const end = Math.min(value.length, start + len)
    return { pre: value.slice(0, start), mid: value.slice(start, end), post: value.slice(end) }
  }, [error, value])

  const bar = tone === 'bar'
  const padY = bar ? 9 : 11
  const padX = bar ? 14 : 14

  return (
    <div style={{ position: 'relative', flex: 1, minWidth: 0 }}>
      <div style={{ position: 'relative' }}>
        {/* Mirror layer for the wavy underline (behind the input). */}
        {underline && (
          <div
            aria-hidden
            className="mono"
            style={{
              position: 'absolute', inset: 0, padding: `${padY}px ${padX}px`,
              fontSize: 13, whiteSpace: 'pre', overflow: 'hidden',
              color: 'transparent', pointerEvents: 'none',
            }}
          >
            {underline.pre}
            <span style={{ textDecoration: 'underline wavy var(--color-error)', textUnderlineOffset: 3 }}>
              {underline.mid}
            </span>
            {underline.post}
          </div>
        )}
        <input
          ref={inputRef}
          value={value}
          placeholder={placeholder ?? 'Search with HQL — e.g. status = "In Progress"'}
          autoFocus={autoFocus}
          spellCheck={false}
          className="mono w-full outline-none"
          style={{
            position: 'relative', background: 'transparent',
            border: `1px solid ${error ? 'var(--color-error)' : 'var(--color-border-2)'}`,
            borderRadius: 'var(--radius-md)', padding: `${padY}px ${padX}px`,
            fontSize: 13, color: 'var(--color-text)',
          }}
          onChange={e => { onChange(e.target.value); setCaret(e.target.selectionStart ?? e.target.value.length); setOpen(true) }}
          onKeyUp={syncCaret}
          onClick={syncCaret}
          onKeyDown={onKeyDown}
          onBlur={() => setTimeout(() => setOpen(false), 120)}
        />
      </div>

      {open && suggestions.length > 0 && (
        <div
          role="listbox"
          style={{
            position: 'absolute', left: 0, right: 0, top: '100%', marginTop: 4, zIndex: 90,
            background: 'var(--color-card)', border: '1px solid var(--color-border-2)',
            borderRadius: 'var(--radius-md)', boxShadow: 'var(--shadow-lg)', padding: 4,
            maxHeight: 280, overflowY: 'auto',
          }}
        >
          {suggestions.map((s, idx) => (
            <div
              key={s.kind + ':' + s.insert + ':' + idx}
              role="option"
              aria-selected={idx === hi}
              onMouseEnter={() => setHi(idx)}
              onMouseDown={e => { e.preventDefault(); applySuggestion(s) }}
              className="flex items-center gap-2 cursor-pointer"
              style={{
                padding: '7px 10px', borderRadius: 'var(--radius-sm)',
                background: idx === hi ? 'var(--color-surface)' : 'transparent',
              }}
            >
              <span
                className="mono flex-1 min-w-0 truncate"
                style={{ fontSize: 12.5, color: 'var(--color-text)' }}
              >
                {s.label}
              </span>
              {s.hint && (
                <span className="text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)', fontSize: 10.5 }}>
                  {s.hint}
                </span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// Quote a value literal for insertion. HQL requires EVERY string value to be quoted
// (the server parser rejects a bare `Done`), so we always wrap in double quotes and
// escape `\` and `"` per the lexer's string grammar (`\"` / `\\`). Only called on
// value literals (ENUM_REF/USER values); function inserts (currentUser()) take a
// different path and stay unquoted.
function quoteValue(v: string): string {
  return `"${v.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
}
