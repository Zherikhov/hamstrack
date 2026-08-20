import { describe, it, expect } from 'vitest'
import { committedY, shortSprintName } from './VelocityChart'

/**
 * HD-139 (R5) — the one piece of pixel arithmetic in the velocity chart.
 *
 * The committed level is drawn by hand rather than as a second bar series, so
 * nothing in Recharts checks it: **a marker at the wrong height is a lie told in
 * pixels, and the table underneath would not contradict it.** Same reasoning as
 * `AgingChart.layout`, which is tested for the same reason.
 *
 * The marker is placed against the plot band Recharts hands to the custom shape
 * (`background`) and the axis maximum the chart fixed — deliberately not against
 * the completed bar's own height, which is zero for a sprint that finished
 * nothing, and that is exactly the sprint whose commitment matters most.
 */

/** A 200px-tall plot band starting 10px down, as Recharts reports it. */
const BAND = { y: 10, height: 200 }

describe('committedY — the committed level, in pixels', () => {
  it('puts a commitment equal to the axis maximum at the top of the plot', () => {
    expect(committedY(BAND, 40, 40)).toBe(10)
  })

  it('puts a commitment of zero on the baseline', () => {
    expect(committedY(BAND, 0, 40)).toBe(210)
  })

  it('places everything in between proportionally', () => {
    expect(committedY(BAND, 20, 40)).toBe(110)
    expect(committedY(BAND, 10, 40)).toBe(160)
  })

  it('marks the commitment of a sprint that completed nothing', () => {
    // The bar has zero height here, so the marker cannot be derived from it —
    // and "committed 21, delivered 0" is the single most important bar on the
    // chart to be able to read.
    expect(committedY(BAND, 21, 42)).toBe(110)
  })

  it('clamps a commitment above the axis rather than drawing off the canvas', () => {
    expect(committedY(BAND, 90, 40)).toBe(10)
  })

  it('draws no marker at all when it cannot be placed', () => {
    // Never a fallback to the baseline: a marker at zero is the claim "this
    // sprint committed to nothing", where no marker is merely an absence.
    expect(committedY(undefined, 20, 40)).toBeNull()
    expect(committedY({ y: null, height: 200 }, 20, 40)).toBeNull()
    expect(committedY(BAND, undefined, 40)).toBeNull()
    expect(committedY(BAND, Number.NaN, 40)).toBeNull()
    expect(committedY(BAND, -3, 40)).toBeNull()
    expect(committedY(BAND, 20, 0)).toBeNull()
  })
})

describe('shortSprintName — the axis label', () => {
  it('leaves an ordinary sprint name alone', () => {
    expect(shortSprintName('Sprint 12')).toBe('Sprint 12')
  })

  it('truncates a name long enough to collide with its neighbour', () => {
    // The full name is still in the tooltip and in the table, which is where a
    // reader looks it up — the axis is a label, not the record.
    expect(shortSprintName('Sprint 12 — payments hardening')).toBe('Sprint 12 — p…')
  })
})
