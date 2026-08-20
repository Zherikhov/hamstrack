import { describe, it, expect } from 'vitest'
import { niceAxis, windowAxis } from './scale'

/**
 * HD-141 (R7) — **the axis rule**, which is the load-bearing half of export.
 *
 * The rule reads: *never auto-scale an axis to the data range on a report that
 * is comparable across time; zero-based, fixed ticks, and the date range printed
 * inside the exported image.* Its value is entirely in the comparison it makes
 * possible — two exports taken weeks apart are read against the same grid — so
 * the assertions below are about what the axis does **not** do:
 *
 *  1. it never starts above zero, whatever the data's minimum is;
 *  2. it never shrinks to the data, so a quiet window looks quiet;
 *  3. it never leaves a reference line off the top, because a p85 a reader
 *     cannot see is a p85 they silently stop checking;
 *  4. an empty series still gets a readable grid rather than a degenerate one.
 *
 * These were asserted per-chart before this slice; they live here because the
 * rule is one rule, and the two functions below are the only implementations of
 * it that may exist.
 */

describe('niceAxis — the value direction', () => {
  it('is zero-based, always', () => {
    for (const max of [3, 17, 140, 9_999]) {
      expect(niceAxis(max).ticks[0]).toBe(0)
    }
  })

  it('never cuts the data off — the top always clears the maximum', () => {
    for (const max of [1, 7, 23, 118, 1_004]) {
      expect(niceAxis(max).max).toBeGreaterThanOrEqual(max)
    }
  })

  it('steps in round 1/2/5 × 10ⁿ numbers, so two windows share a grid', () => {
    // 16, 18 and 19 are three weeks of the same project and all three are read
    // against ticks of 5 — that is the comparability claim, in one assertion.
    expect(niceAxis(16).ticks).toEqual([0, 5, 10, 15, 20])
    expect(niceAxis(18).ticks).toEqual([0, 5, 10, 15, 20])
    expect(niceAxis(19).ticks).toEqual([0, 5, 10, 15, 20])
    // A busier week escalates — but to the NEXT round step, not to a bespoke one
    // fitted to itself, so the two grids are still multiples of each other.
    expect(niceAxis(21).ticks).toEqual([0, 10, 20, 30])
    expect(niceAxis(140).ticks).toEqual([0, 50, 100, 150])
  })

  it('gives an all-zero series a readable grid, not a flat line on one tick', () => {
    expect(niceAxis(0)).toEqual({ max: 4, ticks: [0, 1, 2, 3, 4] })
    expect(niceAxis(Number.NaN).ticks.length).toBeGreaterThan(1)
    expect(niceAxis(-5).ticks[0]).toBe(0)
  })
})

describe('windowAxis — the time direction', () => {
  const DAY = 86_400_000
  const from = '2026-05-01'
  const to = '2026-05-31'

  it('spans the WINDOW, not the data inside it', () => {
    // One point, in the middle of a month. An auto-scaled axis would collapse
    // onto that day and draw a busy-looking chart of a single issue.
    const mid = Date.parse('2026-05-15T12:00:00Z')
    const axis = windowAxis(from, to, [mid])
    expect(axis.min).toBe(Date.parse('2026-05-01T00:00:00Z'))
    expect(axis.max).toBe(Date.parse('2026-05-31T00:00:00Z') + DAY - 1)
  })

  it('spans the window even with no data at all', () => {
    const axis = windowAxis(from, to, [])
    expect(axis.min).toBe(Date.parse('2026-05-01T00:00:00Z'))
    expect(axis.ticks[0]).toBe(Date.parse('2026-05-01T00:00:00Z'))
  })

  it('WIDENS for a point outside the window, and never narrows', () => {
    // A closure timestamped on the boundary day in another timezone. Clipping the
    // axis would drop the dot silently; the report may omit nothing it computed.
    const late = Date.parse('2026-06-02T00:00:00Z')
    const axis = windowAxis(from, to, [late])
    expect(axis.max).toBe(late)
    expect(axis.min).toBe(Date.parse('2026-05-01T00:00:00Z'))
  })

  it('snaps every tick to UTC midnight — the axis reads as days', () => {
    for (const t of windowAxis(from, to, []).ticks) {
      expect(t % DAY).toBe(0)
    }
  })

  it('falls back to the data, then to a month, rather than collapsing', () => {
    const a = Date.parse('2026-03-02T00:00:00Z')
    const b = Date.parse('2026-03-09T00:00:00Z')
    const fromData = windowAxis('', '', [a, b])
    expect(fromData.min).toBe(a)
    expect(fromData.max).toBeGreaterThan(b)

    const empty = windowAxis('nonsense', 'also-nonsense', [])
    expect(empty.max).toBeGreaterThan(empty.min)
    expect(empty.ticks.length).toBeGreaterThan(1)
  })

  it('ignores a non-finite value instead of poisoning the whole domain', () => {
    const axis = windowAxis(from, to, [Number.NaN, Number.POSITIVE_INFINITY])
    expect(Number.isFinite(axis.min)).toBe(true)
    expect(Number.isFinite(axis.max)).toBe(true)
  })
})
