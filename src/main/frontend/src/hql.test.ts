import { describe, it, expect } from 'vitest'
import { parseOrderBy, setOrderBy, nextSortDir } from './hql'

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
