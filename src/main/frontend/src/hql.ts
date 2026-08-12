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
