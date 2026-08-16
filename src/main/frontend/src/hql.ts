// HQL query-string helpers used by the results view. These are string rewrites,
// NOT a parser — the server validates. Sorting is the single source of truth in the
// query's `ORDER BY` clause (Advanced Search §8.1), so clicking a column header
// rewrites that clause and re-runs, rather than sorting client-side.

export type SortDir = 'ASC' | 'DESC'

// Split a query into its WHERE part and the trailing ORDER BY clause (case-
// insensitive; ORDER BY must be last per the grammar). Returns the primary sort
// field/direction when present.
export interface ParsedOrder {
  body: string          // everything before ORDER BY (may be empty)
  field: string | null  // the FIRST sort field, if any
  dir: SortDir
}

const ORDER_BY_RE = /\border\s+by\b/i

export function parseOrderBy(query: string): ParsedOrder {
  const m = query.match(ORDER_BY_RE)
  if (!m || m.index == null) return { body: query.trim(), field: null, dir: 'ASC' }
  const body = query.slice(0, m.index).trim()
  const clause = query.slice(m.index + m[0].length).trim()
  // First term only drives the header state; keep it simple.
  const first = clause.split(',')[0]?.trim() ?? ''
  const parts = first.split(/\s+/)
  const field = parts[0] || null
  const dir: SortDir = (parts[1]?.toUpperCase() === 'DESC') ? 'DESC' : 'ASC'
  return { body, field, dir }
}

// Rewrite (or add) the query's ORDER BY so `field` sorts in `dir`. Any existing
// ORDER BY is replaced entirely — column-header sorting is single-column.
export function setOrderBy(query: string, field: string, dir: SortDir): string {
  const { body } = parseOrderBy(query)
  const order = `ORDER BY ${field} ${dir}`
  return body ? `${body} ${order}` : order
}

// The direction a header click should apply: toggle if it's already the active
// sort field, else default to ASC (DESC for date-ish fields feels more useful, but
// keep it predictable/uniform).
export function nextSortDir(current: ParsedOrder, field: string): SortDir {
  if (current.field?.toLowerCase() === field.toLowerCase()) {
    return current.dir === 'ASC' ? 'DESC' : 'ASC'
  }
  return 'ASC'
}

// ── Emitting HQL from user-typed text (HD-39 §6.6) ───────────────────────────
// MANDATORY for anything that interpolates free text into a query (the command
// palette's `text ~ …` search and its `assignee = …` People rows). The server
// lexer accepts ONLY the `\"`, `\'` and `\\` escapes — every other backslash
// sequence is a parse error (422), so raw interpolation of a title containing a
// quote or a Windows path would break the query. Never build an HQL string
// literal by hand; always go through sanitizeForHql() + hqlQuote().

/** Wrap a value in double quotes, escaping `\` and `"` per the lexer grammar. */
export function hqlQuote(s: string): string {
  return '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"'
}

/** Longest free-text fragment we ever send — matches the /suggest endpoint's cap. */
export const HQL_TEXT_MAX = 100

// ASCII control characters: anything below 0x20, plus DEL (0x7F). Tested by
// code point rather than a character-class regex so no raw control byte ever
// has to appear in this source file.
function isControlChar(code: number): boolean {
  return code < 0x20 || code === 0x7f
}

/**
 * Normalise user-typed text before it becomes an HQL string literal:
 * trim -> replace control characters with a space -> collapse whitespace runs ->
 * truncate. Run this BEFORE hqlQuote(); the pair together makes a title
 * containing quotes or a trailing backslash a valid query instead of a 422.
 */
export function sanitizeForHql(s: string): string {
  let out = ''
  for (const ch of s.trim()) {
    out += isControlChar(ch.charCodeAt(0)) ? ' ' : ch
  }
  return out.replace(/\s+/g, ' ').slice(0, HQL_TEXT_MAX)
}

/** `text ~ "…"` for a user-typed fragment — the palette's issue-search query. */
export function hqlTextContains(text: string): string {
  return `text ~ ${hqlQuote(sanitizeForHql(text))}`
}

/** `assignee = "…"` for a member's email — the palette's People rows. */
export function hqlAssigneeIs(email: string): string {
  return `assignee = ${hqlQuote(sanitizeForHql(email))}`
}
