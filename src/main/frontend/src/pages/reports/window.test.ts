import { describe, it, expect } from 'vitest'
import {
  daysInclusive, historyDays, isoDate, parseIso, presetOf, presetWindow, readFlowState,
  shiftIso, todayIso, writeFlowParams,
} from './window'
import { niceAxis } from './scale'

/**
 * The report window is URL state and UTC arithmetic, which is exactly the pair
 * that produces "the numbers changed under me" bugs nobody can reproduce. Both
 * halves are pure, so both are pinned here rather than implied by a page test.
 */

const TODAY = '2026-08-19' // a Wednesday

describe('window — UTC date arithmetic', () => {
  it('parses and rejects', () => {
    expect(parseIso('2026-08-19')).toBe(Date.parse('2026-08-19T00:00:00Z'))
    expect(parseIso('2026-02-31')).toBeNull()   // Date.parse would roll this over
    expect(parseIso('19-08-2026')).toBeNull()
    expect(parseIso('')).toBeNull()
    expect(parseIso(null)).toBeNull()
  })

  it('shifts whole UTC days across a month boundary', () => {
    expect(shiftIso('2026-03-01', -1)).toBe('2026-02-28')
    expect(shiftIso('2026-08-19', -89)).toBe('2026-05-22')
  })

  it('counts both endpoints — a one-day window is one day, not zero', () => {
    expect(daysInclusive('2026-08-19', '2026-08-19')).toBe(1)
    expect(daysInclusive('2026-05-22', '2026-08-19')).toBe(90)
  })

  it('reads today in UTC, not in the local zone', () => {
    // 2026-08-19T23:30Z is still the 19th in UTC even where local time is the 20th.
    expect(todayIso(new Date('2026-08-19T23:30:00Z'))).toBe('2026-08-19')
    expect(isoDate(Date.parse('2026-01-01T00:00:00Z'))).toBe('2026-01-01')
  })
})

/**
 * There is deliberately no "partial buckets" block here any more. Which end
 * buckets are partial is `buckets[].partial` off the response — the server owns
 * Monday truncation, and a TypeScript test of a TypeScript copy of that rule
 * would have gone on passing while the two implementations drifted apart. The
 * page test asserts the sentence is printed from the FLAG instead.
 */

describe('window — URL state', () => {
  it('leaves an absent window absent, so the SERVER picks one it can serve', () => {
    // Not "the last 90 days computed here": the server derives its default from
    // its own max-window-days, so a request naming no window always succeeds —
    // including on an instance whose operator capped windows below 90, where a
    // client-side 90 would 400 on first load.
    const s = readFlowState(new URLSearchParams())
    expect(s).toEqual({
      from: '', to: '', interval: 'WEEK', typeId: '', componentId: '', labelId: '',
    })
    expect(writeFlowParams(s)).toEqual({ interval: 'WEEK' })
  })

  it('degrades a hand-edited junk window to "unset", never to a blank page', () => {
    const s = readFlowState(new URLSearchParams('from=yesterday&to=&interval=FORTNIGHT'))
    expect(s.from).toBe('')
    expect(s.to).toBe('')
    expect(s.interval).toBe('WEEK')
  })

  it('passes an inverted window through untouched, so the SERVER refuses it', () => {
    // The 400's message names a configured cap this client cannot see; second-
    // guessing the rule here would replace that sentence with a guess.
    const s = readFlowState(new URLSearchParams('from=2026-08-19&to=2026-01-01'))
    expect(s.from).toBe('2026-08-19')
    expect(s.to).toBe('2026-01-01')
  })

  it('round-trips a full state and keeps empty filters out of the URL', () => {
    const s = readFlowState(new URLSearchParams('from=2026-08-01&to=2026-08-19&interval=DAY&typeId=t1'))
    expect(writeFlowParams(s)).toEqual({
      from: '2026-08-01', to: '2026-08-19', interval: 'DAY', typeId: 't1',
    })
  })

  it('recognises the presets, and calls a hand-picked or unset window custom', () => {
    const p90 = presetWindow(90, TODAY)
    expect(presetOf(p90.from, p90.to, TODAY)).toBe(90)
    const p30 = presetWindow(30, TODAY)
    expect(presetOf(p30.from, p30.to, TODAY)).toBe(30)
    // Same length but not ending today — not a preset, because the preset means
    // "the last N days", and a stale end date is a pinned window.
    expect(presetOf('2026-01-01', '2026-01-30', TODAY)).toBe('custom')
    // Nothing chosen yet, and the server has not answered: not a preset either,
    // because naming one would be guessing which window the server will pick.
    expect(presetOf('', '', TODAY)).toBe('custom')
  })
})

describe('window — thin history', () => {
  it('counts from the first issue the report admits, inclusive', () => {
    expect(historyDays('2026-08-15T09:00:00Z', TODAY)).toBe(5)
  })

  it('has no answer when the report admits no issue at all', () => {
    // `meta.firstIssueAt` is null for a project with no issues — or none matching
    // the filters. There is no history to be thin, and the empty-window sentence
    // is the honest thing to print instead.
    expect(historyDays(null, TODAY)).toBeNull()
    expect(historyDays(undefined, TODAY)).toBeNull()
    expect(historyDays('not a date', TODAY)).toBeNull()
  })
})

describe('scale — zero-based, round-ticked axes', () => {
  it('never returns a degenerate axis for an all-zero series', () => {
    expect(niceAxis(0)).toEqual({ max: 4, ticks: [0, 1, 2, 3, 4] })
  })

  it('grows in 1/2/5 steps so two screenshots share a grid', () => {
    expect(niceAxis(7)).toEqual({ max: 8, ticks: [0, 2, 4, 6, 8] })
    expect(niceAxis(23)).toEqual({ max: 30, ticks: [0, 10, 20, 30] })
    expect(niceAxis(1040)).toEqual({ max: 1500, ticks: [0, 500, 1000, 1500] })
  })

  it('always starts at zero — the comparability rule', () => {
    for (const v of [3, 17, 99, 1234]) expect(niceAxis(v).ticks[0]).toBe(0)
  })
})
