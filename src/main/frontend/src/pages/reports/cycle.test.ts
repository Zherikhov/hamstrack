import { describe, it, expect } from 'vitest'
import {
  MEASURE_LABEL, PERCENTILE_MIN_SAMPLE, formatDays, formatDaysUnit, measureSample,
  measureValue, percentilesFor, readPercentiles,
} from './cycle'
import type { CycleTimeItem, CycleTimeReport } from '../../types'

/**
 * HD-138 — the arithmetic behind the cycle-time page, pinned away from the DOM.
 *
 * Each of these is a rule that would be invisible if it broke: the numbers would
 * still render, still look plausible, and still be wrong. That is precisely the
 * failure mode the reports epic exists to remove.
 */

function item(over: Partial<CycleTimeItem> = {}): CycleTimeItem {
  return {
    issueId: 'i1', key: 'DEMO-1', title: 't', typeId: 't1',
    startedAt: '2026-08-01T00:00:00Z', closedAt: '2026-08-05T00:00:00Z',
    cycleDays: 4.2, leadDays: 11.7, ...over,
  }
}

describe('measureValue', () => {
  it('reads the duration the measure names', () => {
    expect(measureValue(item(), 'CYCLE')).toBe(4.2)
    expect(measureValue(item(), 'LEAD')).toBe(11.7)
  })

  it('returns null — never the creation date — for an issue with no start', () => {
    // The whole point: substituting createdAt here would turn cycle time into
    // lead time wearing a false name, and nothing on screen would say so.
    const noStart = item({ startedAt: null, cycleDays: null })
    expect(measureValue(noStart, 'CYCLE')).toBeNull()
    expect(measureValue(noStart, 'LEAD')).toBe(11.7)
  })
})

describe('measureSample', () => {
  const report: Pick<CycleTimeReport, 'sampleSize' | 'missingStartCount'> =
    { sampleSize: 940, missingStartCount: 128 }

  it('counts the whole sample for lead time and the started ones for cycle time', () => {
    expect(measureSample(report, 'LEAD')).toBe(940)
    expect(measureSample(report, 'CYCLE')).toBe(812)
  })

  it('never goes negative on a nonsense pair', () => {
    expect(measureSample({ sampleSize: 3, missingStartCount: 9 }, 'CYCLE')).toBe(0)
  })
})

describe('readPercentiles', () => {
  it('passes real numbers through', () => {
    expect(readPercentiles({ p50: 4.1, p85: 12.6 })).toEqual({ p50: 4.1, p85: 12.6 })
  })

  it('treats a missing pair, null members and NaN identically — as "not computed"', () => {
    // A `0` default here would draw a reference line on the axis and label it
    // p85: noise presented as a measurement, which the spec rates as worse than
    // printing nothing.
    expect(readPercentiles(null)).toEqual({ p50: null, p85: null })
    expect(readPercentiles(undefined)).toEqual({ p50: null, p85: null })
    expect(readPercentiles({ p50: null, p85: null })).toEqual({ p50: null, p85: null })
    expect(readPercentiles({ p50: Number.NaN, p85: 12.6 })).toEqual({ p50: null, p85: 12.6 })
  })

  it('keeps a genuine zero, which is a real answer for same-day work', () => {
    expect(readPercentiles({ p50: 0, p85: 1.5 })).toEqual({ p50: 0, p85: 1.5 })
  })
})

describe('percentilesFor', () => {
  const report = {
    percentiles: { cycle: { p50: 4.1, p85: 12.6 }, lead: { p50: 9, p85: 28.4 } },
  } as CycleTimeReport

  it('picks the pair the measure names', () => {
    expect(percentilesFor(report, 'CYCLE')).toEqual({ p50: 4.1, p85: 12.6 })
    expect(percentilesFor(report, 'LEAD')).toEqual({ p50: 9, p85: 28.4 })
  })

  it('answers "not computed" before the report has loaded', () => {
    expect(percentilesFor(undefined, 'CYCLE')).toEqual({ p50: null, p85: null })
  })
})

describe('formatDays', () => {
  it('always prints one decimal, at every magnitude', () => {
    // Uniform on purpose: a rule that dropped the decimal above some threshold
    // would print the same p85 as "12.6" beside the chart and "13" on the line.
    expect(formatDays(4.2)).toBe('4.2')
    expect(formatDays(12.64)).toBe('12.6')
    expect(formatDays(44)).toBe('44.0')
    expect(formatDays(0)).toBe('0.0')
  })

  it('gets the unit right in prose', () => {
    expect(formatDaysUnit(1)).toBe('1.0 day')
    expect(formatDaysUnit(4.2)).toBe('4.2 days')
  })
})

describe('the constants the copy quotes', () => {
  it('keeps the sample floor at the number the sentence prints', () => {
    // "…(need 5, have 3)" — the page prints this constant, so it may not drift
    // away from the server's floor silently.
    expect(PERCENTILE_MIN_SAMPLE).toBe(5)
  })

  it('names both measures', () => {
    expect(MEASURE_LABEL.CYCLE).toBe('Cycle time')
    expect(MEASURE_LABEL.LEAD).toBe('Lead time')
  })
})
