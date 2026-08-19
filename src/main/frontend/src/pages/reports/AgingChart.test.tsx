import { describe, it, expect } from 'vitest'
import { layout } from './AgingChart'
import type { AgingItem } from '../../types'

/**
 * HD-138 — the aging half's geometry.
 *
 * One invariant carries the whole report: **the dot is the measurement and is
 * never moved.** Labels are nudged down until they stop overlapping (they are
 * the naming this report exists for, and unreadable labels are no naming at
 * all), but a dot pushed off its age would quietly place an item on the wrong
 * side of the p85 line — a lie told in pixels, which no table underneath would
 * contradict.
 */

const HEIGHT = 300
/** The page's scale: 0 at the bottom, `max` days at the top. */
const y = (days: number) => HEIGHT - (days / 60) * HEIGHT

function item(key: string, ageDays: number): AgingItem {
  return { issueId: key, key, title: key, ageDays, assigneeId: null, startedAt: null }
}

describe('AgingChart layout', () => {
  it('orders items oldest first, whatever order they arrived in', () => {
    const placed = layout([item('A', 3), item('B', 40), item('C', 12)], y, HEIGHT)
    expect(placed.map(p => p.item.key)).toEqual(['B', 'C', 'A'])
  })

  it('puts every dot at its own age and never anywhere else', () => {
    const items = [item('A', 3), item('B', 40), item('C', 12)]
    for (const placed of layout(items, y, HEIGHT)) {
      expect(placed.dotTop).toBeCloseTo(y(placed.item.ageDays), 6)
    }
  })

  it('pushes overlapping labels apart while leaving their dots alone', () => {
    // Three items within a day of each other: their dots are ~5px apart, which
    // is unreadable for two lines of text.
    const items = [item('A', 30), item('B', 29.5), item('C', 29)]
    const placed = layout(items, y, HEIGHT)
    expect(placed[1].labelTop - placed[0].labelTop).toBeGreaterThanOrEqual(15)
    expect(placed[2].labelTop - placed[1].labelTop).toBeGreaterThanOrEqual(15)
    // …and the dots stayed exactly where the data put them.
    expect(placed.map(p => p.dotTop)).toEqual(items.map(i => y(i.ageDays)))
  })

  it('keeps the last label inside the plot rather than off the bottom edge', () => {
    const items = Array.from({ length: 40 }, (_, i) => item(`I-${i}`, 60 - i))
    const placed = layout(items, y, HEIGHT)
    for (const p of placed) {
      expect(p.labelTop).toBeGreaterThanOrEqual(0)
      expect(p.labelTop).toBeLessThanOrEqual(HEIGHT - 14)
    }
  })

  it('is a no-op on an empty column', () => {
    expect(layout([], y, HEIGHT)).toEqual([])
  })
})
