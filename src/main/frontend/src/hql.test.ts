import { describe, it, expect } from 'vitest'
import {
  parseOrderBy, setOrderBy, nextSortDir,
  hqlQuote, sanitizeForHql, hqlTextContains, hqlAssigneeIs,
} from './hql'

// hql.ts is pure string rewriting for the results-view column sort — no I/O,
// no DOM. These lock in the ORDER BY split/rewrite/toggle contract the header
// clicks depend on.

describe('parseOrderBy', () => {
  it('returns the whole query as body with no sort when there is no ORDER BY', () => {
    const r = parseOrderBy('status = "Open"')
    expect(r).toEqual({ body: 'status = "Open"', field: null, dir: 'ASC' })
  })

  it('splits the WHERE body from the trailing ORDER BY and reads the primary field', () => {
    const r = parseOrderBy('status = "Open" ORDER BY priority DESC')
    expect(r.body).toBe('status = "Open"')
    expect(r.field).toBe('priority')
    expect(r.dir).toBe('DESC')
  })

  it('matches ORDER BY case-insensitively and defaults direction to ASC', () => {
    const r = parseOrderBy('assignee = me order by created')
    expect(r.field).toBe('created')
    expect(r.dir).toBe('ASC')
  })

  it('keeps only the first sort term for header state', () => {
    const r = parseOrderBy('ORDER BY priority DESC, created ASC')
    expect(r.body).toBe('')
    expect(r.field).toBe('priority')
    expect(r.dir).toBe('DESC')
  })
})

describe('setOrderBy', () => {
  it('appends an ORDER BY to a body-only query', () => {
    expect(setOrderBy('status = "Open"', 'priority', 'DESC'))
      .toBe('status = "Open" ORDER BY priority DESC')
  })

  it('replaces an existing ORDER BY entirely (single-column sorting)', () => {
    expect(setOrderBy('status = "Open" ORDER BY created ASC', 'priority', 'DESC'))
      .toBe('status = "Open" ORDER BY priority DESC')
  })

  it('produces a bare ORDER BY when the body is empty', () => {
    expect(setOrderBy('ORDER BY created ASC', 'priority', 'ASC'))
      .toBe('ORDER BY priority ASC')
  })
})

describe('nextSortDir', () => {
  it('toggles direction when clicking the already-active sort field', () => {
    const current = parseOrderBy('ORDER BY priority ASC')
    expect(nextSortDir(current, 'priority')).toBe('DESC')
  })

  it('is case-insensitive when comparing the active field', () => {
    const current = parseOrderBy('ORDER BY Priority DESC')
    expect(nextSortDir(current, 'priority')).toBe('ASC')
  })

  it('defaults to ASC when switching to a different field', () => {
    const current = parseOrderBy('ORDER BY priority DESC')
    expect(nextSortDir(current, 'created')).toBe('ASC')
  })
})

// ── HD-39 §6.6: emitting HQL from user-typed text ────────────────────────────
// The server lexer accepts ONLY the \" \' \\ escapes; anything else is a 422.
// sanitizeForHql() + hqlQuote() together must make a 422 impossible by
// construction — this is the contract the command palette relies on.

describe('hqlQuote', () => {
  it('wraps in double quotes and escapes embedded quotes', () => {
    expect(hqlQuote('he said "hi"')).toBe('"he said \\"hi\\""')
  })

  it('doubles backslashes, including a trailing one that would escape the closing quote', () => {
    expect(hqlQuote('C:\\path')).toBe('"C:\\\\path"')
    expect(hqlQuote('trailing\\')).toBe('"trailing\\\\"')
  })

  it('quotes an empty string to a valid empty literal', () => {
    expect(hqlQuote('')).toBe('""')
  })
})

describe('sanitizeForHql', () => {
  it('trims and collapses whitespace runs to a single space', () => {
    expect(sanitizeForHql('   two    words   ')).toBe('two words')
  })

  it('replaces every ASCII control character (and DEL) with a single space', () => {
    const ctrl = (code: number) => String.fromCharCode(code)
    const raw = 'a' + ctrl(0) + 'b' + ctrl(9) + 'c' + ctrl(10) + 'd' + ctrl(31) + 'e' + ctrl(127) + 'f'
    const out = sanitizeForHql(raw)
    expect(out).toBe('a b c d e f')
    // Belt and braces: no control byte survives.
    for (let code = 0; code <= 31; code++) expect(out).not.toContain(ctrl(code))
    expect(out).not.toContain(ctrl(127))
  })

  it('truncates to 100 characters', () => {
    expect(sanitizeForHql('x'.repeat(500))).toHaveLength(100)
  })
})

describe('sanitizeForHql — truncation boundary', () => {
  it('truncates BEFORE escaping, so a backslash left at the cut is still doubled', () => {
    const raw = 'x'.repeat(99) + '\\' + 'tail'
    expect(sanitizeForHql(raw)).toBe('x'.repeat(99) + '\\')
    // The literal must not end with an odd backslash — that would escape the
    // closing quote and produce an unterminated string (parse error, 422).
    expect(hqlQuote(sanitizeForHql(raw))).toBe('"' + 'x'.repeat(99) + '\\\\"')
  })

  it('counts the truncation in characters of the SANITIZED text, not the raw input', () => {
    // 300 chars of whitespace-separated words collapse first, then get cut at 100.
    const raw = Array.from({ length: 60 }, () => 'word').join('   ')
    expect(sanitizeForHql(raw)).toHaveLength(100)
    expect(sanitizeForHql(raw)).not.toMatch(/\s\s/)
  })
})

// The frontend never validates HQL — the server does. This mirrors the server
// lexer's string rule (`Lexer.string()`: ONLY \" \' \\ are legal escapes; any
// other backslash sequence is ILLEGAL_ESCAPE, and an unclosed quote is
// UNTERMINATED_STRING) so we can prove, in the frontend suite, that what we emit
// is always accepted and decodes back to exactly what we sanitized.
function lexStringLiteral(src: string): { value: string; rest: string } {
  if (src[0] !== '"') throw new Error('not a string literal: ' + src)
  let out = ''
  let i = 1
  while (i < src.length) {
    const c = src[i]
    if (c === '\\') {
      const next = src[i + 1]
      if (next === '"' || next === "'" || next === '\\') { out += next; i += 2; continue }
      throw new Error(`ILLEGAL_ESCAPE '\\${next ?? ''}' at ${i}`)
    }
    if (c === '"') return { value: out, rest: src.slice(i + 1) }
    out += c
    i++
  }
  throw new Error('UNTERMINATED_STRING')
}

describe('emitted HQL is accepted by the server lexer (§6.6, cross-check)', () => {
  const ctrl = (code: number) => String.fromCharCode(code)
  const NASTY = [
    'plain text',
    'he said "hi"',
    'quote at end "',
    'backslash \\ mid',
    'trailing backslash \\',
    'double backslash \\\\',
    'escaped-looking \\" pair',
    "single 'quotes' inside",
    'mixed "\\" \\\' soup',
    'C:\\Users\\zheri\\path',
    'newline' + ctrl(10) + 'tab' + ctrl(9) + 'null' + ctrl(0) + 'del' + ctrl(127),
    'unicode — em dash, é, 日本語, 🐹',
    '"'.repeat(120),
    '\\'.repeat(120),
    'x'.repeat(500),
    '  ',
    '',
  ]

  it('never produces an illegal escape or an unterminated string, whatever the user types', () => {
    for (const raw of NASTY) {
      const query = hqlTextContains(raw)
      expect(query.startsWith('text ~ '), raw).toBe(true)
      const lexed = lexStringLiteral(query.slice('text ~ '.length))
      // Nothing dangles after the closing quote: the whole tail is one literal.
      expect(lexed.rest, raw).toBe('')
      // …and the server decodes exactly the sanitized text we intended to send.
      expect(lexed.value, raw).toBe(sanitizeForHql(raw))
      expect(lexed.value.length, raw).toBeLessThanOrEqual(100)
    }
  })

  it('holds for the assignee fragment too (a display-name-shaped email cannot break out)', () => {
    for (const raw of ['ann@example.com', 'we"ird@example.com', 'back\\slash@example.com']) {
      const query = hqlAssigneeIs(raw)
      const lexed = lexStringLiteral(query.slice('assignee = '.length))
      expect(lexed.rest, raw).toBe('')
      expect(lexed.value, raw).toBe(sanitizeForHql(raw))
    }
  })

  it('the mini-lexer itself rejects what the server rejects (guards the guard)', () => {
    expect(() => lexStringLiteral('"bad \\n escape"')).toThrow(/ILLEGAL_ESCAPE/)
    expect(() => lexStringLiteral('"unterminated')).toThrow(/UNTERMINATED_STRING/)
    // A naive, unescaped interpolation — exactly what §6.6 exists to prevent.
    expect(() => lexStringLiteral(`"${'he said "hi"'}"`)).not.toThrow()
    expect(lexStringLiteral(`"${'he said "hi"'}"`).rest).not.toBe('') // …but it breaks out of the literal
    expect(() => lexStringLiteral(`"${'C:\\path'}"`)).toThrow(/ILLEGAL_ESCAPE/)
  })
})

describe('HQL fragment builders', () => {
  it('emits exactly `text ~ "…"` for a plain fragment', () => {
    expect(hqlTextContains('bug')).toBe('text ~ "bug"')
  })

  it('escapes quotes inside the text fragment so the query stays parseable', () => {
    expect(hqlTextContains('he "x"')).toBe('text ~ "he \\"x\\""')
    expect(hqlTextContains('he said "hi"\\')).toBe('text ~ "he said \\"hi\\"\\\\"')
  })

  it('emits `assignee = "…"` for a member email', () => {
    expect(hqlAssigneeIs('ann@example.com')).toBe('assignee = "ann@example.com"')
  })
})
