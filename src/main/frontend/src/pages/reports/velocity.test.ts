import { describe, it, expect } from 'vitest'
import {
  VELOCITY_CAPTION, VELOCITY_DEFAULT_SPRINTS, VELOCITY_MAX_SPRINTS, readVelocityState,
  unestimatedSummary, velocityAxisMax, velocityBand, velocityRows, velocitySentence,
  writeVelocityParams,
} from './velocity'
import type { VelocityReport, VelocitySprint } from '../../types'

/**
 * HD-139 (R5) — the arithmetic and the refusals behind the velocity report,
 * tested where they are pure so that the page cannot quietly re-implement them.
 *
 * The rules pinned here are the ones §1.4 turned into hard design constraints:
 *
 *  1. **The band is withheld below three completed sprints**, and the sample
 *     size is stated in its place. It is checked on the CLIENT and not merely
 *     assumed of the server, because a band over two sprints is the failure
 *     mode this report exists to avoid, not a rounding error.
 *  2. **The sentence is the deliverable** — the observed range, then the two
 *     percentiles, then the sample size, in that order, so the evidence
 *     precedes the projection.
 *  3. **The caption is permanent and verbatim.**
 *  4. **A clamp is stated, never silent** (§1.7 — the "these numbers don't match
 *     what I expected" failure is a report that quietly showed something else).
 */

function sprint(over: Partial<VelocitySprint> = {}): VelocitySprint {
  return {
    sprintId: 's1', name: 'Sprint 7',
    startAt: '2026-07-01T09:00:00Z', completedAt: '2026-07-15T09:00:00Z',
    committed: 21, completed: 18, addedAfterStart: 4, carriedOver: 3,
    unestimatedCount: 0, ...over,
  }
}

function report(over: Partial<VelocityReport> = {}): VelocityReport {
  return {
    measure: 'COUNT',
    sprints: [
      sprint({ sprintId: 's1', name: 'Sprint 7', completed: 14 }),
      sprint({ sprintId: 's2', name: 'Sprint 8', completed: 20 }),
      sprint({ sprintId: 's3', name: 'Sprint 9', completed: 23 }),
      sprint({ sprintId: 's4', name: 'Sprint 10', completed: 18 }),
      sprint({ sprintId: 's5', name: 'Sprint 11', completed: 16 }),
      sprint({ sprintId: 's6', name: 'Sprint 12', completed: 21 }),
    ],
    forecast: { p50: 18.0, p85: 23.0, sampleSize: 6 },
    meta: {
      computedAt: '2026-08-20T09:00:00Z', basedOnIssues: 112, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

describe('readVelocityState — the URL is the report (§4.4)', () => {
  it('defaults to six sprints and issue counts', () => {
    expect(readVelocityState(new URLSearchParams()))
      .toEqual({ sprints: VELOCITY_DEFAULT_SPRINTS, measure: 'COUNT', clampedFrom: null })
  })

  it('reads a count and a measure a link carried', () => {
    expect(readVelocityState(new URLSearchParams('sprints=9&measure=POINTS')))
      .toEqual({ sprints: 9, measure: 'POINTS', clampedFrom: null })
  })

  it('clamps an over-long request AND says what it clamped', () => {
    // Never a silent clamp: the number the link asked for is kept so the page
    // can print it. Forwarding 99 and rendering the endpoint's 400 would replace
    // a chart and a sentence with an error for a link pasted in good faith.
    expect(readVelocityState(new URLSearchParams('sprints=99')))
      .toEqual({ sprints: VELOCITY_MAX_SPRINTS, measure: 'COUNT', clampedFrom: 99 })
  })

  it('clamps a nonsensical count up to one, and says that too', () => {
    expect(readVelocityState(new URLSearchParams('sprints=0')))
      .toEqual({ sprints: 1, measure: 'COUNT', clampedFrom: 0 })
    expect(readVelocityState(new URLSearchParams('sprints=-4')).sprints).toBe(1)
  })

  it('degrades an unparseable parameter to the default without a lecture', () => {
    // "sprints=abc" is not a request this report refused, it is not a request at
    // all — announcing a fallback for a typo teaches nobody anything.
    expect(readVelocityState(new URLSearchParams('sprints=abc')))
      .toEqual({ sprints: VELOCITY_DEFAULT_SPRINTS, measure: 'COUNT', clampedFrom: null })
    expect(readVelocityState(new URLSearchParams('measure=STORY_POINTS')).measure).toBe('COUNT')
  })

  it('always writes both parameters, so a shared link outlives a default change', () => {
    expect(writeVelocityParams({ sprints: 6, measure: 'POINTS' }))
      .toEqual({ sprints: '6', measure: 'POINTS' })
  })
})

describe('velocityRows — the bars, and what they are not', () => {
  it('keeps the server order and carries no person', () => {
    const rows = velocityRows(report())
    expect(rows.map(r => r.name)).toEqual([
      'Sprint 7', 'Sprint 8', 'Sprint 9', 'Sprint 10', 'Sprint 11', 'Sprint 12',
    ])
    // The shape has no assignee, no member and no owner — §1.4 refuses a
    // per-person breakdown, and the cheapest way to keep that is to have no
    // field to render one from.
    expect(Object.keys(rows[0]).sort()).toEqual([
      'addedAfterStart', 'carriedOver', 'committed', 'completed', 'completedDay', 'key', 'name',
      'sprintId', 'startDay', 'unestimatedCount',
    ])
  })

  it('degrades a missing figure to zero rather than to a bar that vanishes', () => {
    const rows = velocityRows({
      sprints: [sprint({ completed: undefined as unknown as number })],
    })
    expect(rows[0].completed).toBe(0)
  })

  it('gives two sprints with the same name distinct keys', () => {
    const rows = velocityRows({
      sprints: [sprint({ sprintId: 'a', name: 'Sprint' }), sprint({ sprintId: 'a', name: 'Sprint' })],
    })
    expect(rows[0].key).not.toBe(rows[1].key)
  })

  it('survives a report that has not arrived', () => {
    expect(velocityRows(undefined)).toEqual([])
  })

  it('carries the chronology the payload states, as UTC days', () => {
    // The order is the server's and now so is the evidence for it: nothing here
    // infers when a sprint ran from what somebody named it.
    const rows = velocityRows({
      sprints: [sprint({ startAt: '2026-07-01T23:30:00Z', completedAt: '2026-07-15T00:30:00Z' })],
    })
    expect(rows[0]).toMatchObject({ startDay: '2026-07-01', completedDay: '2026-07-15' })
  })

  it('degrades an unusable instant to null rather than to a wrong date', () => {
    const rows = velocityRows({ sprints: [sprint({ completedAt: 'not a date' })] })
    expect(rows[0].completedDay).toBeNull()
  })
})

describe('unestimatedSummary — documented failure mode #4, counted', () => {
  it('names both how much and how widespread', () => {
    // "9 issues" alone cannot tell one badly-sized sprint from a habit across
    // the sample, and those call for different reactions.
    const rows = velocityRows({
      sprints: [
        sprint({ sprintId: 'a', unestimatedCount: 4 }),
        sprint({ sprintId: 'b', unestimatedCount: 0 }),
        sprint({ sprintId: 'c', unestimatedCount: 5 }),
      ],
    })
    expect(unestimatedSummary(rows)).toEqual({ issues: 9, sprints: 2 })
  })

  it('is zero for a fully estimated sample, and for an empty one', () => {
    expect(unestimatedSummary(velocityRows(report()))).toEqual({ issues: 0, sprints: 0 })
    expect(unestimatedSummary([])).toEqual({ issues: 0, sprints: 0 })
  })

  it('never counts a missing field as unsized work', () => {
    const rows = velocityRows({
      sprints: [sprint({ unestimatedCount: undefined as unknown as number })],
    })
    expect(rows[0].unestimatedCount).toBe(0)
  })
})

describe('velocityBand — the forecast, and the refusal to make one', () => {
  it('is the sentence §2.5 pins, word for word', () => {
    const r = report()
    const band = velocityBand(r, velocityRows(r))
    expect(band.kind).toBe('BAND')
    expect(velocitySentence(band, 'COUNT')).toBe(
      'Recent sprints delivered between 14 and 23 issues; plan for ~18 (p50) and treat 23 (p85) '
      + 'as a stretch. Based on 6 sprints.',
    )
  })

  it('suppresses the band below three completed sprints, stating the sample size', () => {
    // The wire shape of suppression: the object is present, the percentiles are
    // null, and the sample size still says what there was.
    const r = report({
      sprints: report().sprints.slice(0, 2),
      forecast: { p50: null, p85: null, sampleSize: 2 },
    })
    const band = velocityBand(r, velocityRows(r))
    expect(band).toEqual({ kind: 'SUPPRESSED', sampleSize: 2 })
    expect(velocitySentence(band, 'COUNT')).toContain('2 sprints have finished here')
    expect(velocitySentence(band, 'COUNT')).toContain('at least 3')
  })

  it('suppresses it even when a forecast arrived anyway', () => {
    // The threshold is the client's rule too: whichever way the server signals
    // suppression, a reader must never be shown percentiles over two sprints.
    const r = report({
      sprints: report().sprints.slice(0, 2),
      forecast: { p50: 17, p85: 20, sampleSize: 2 },
    })
    expect(velocityBand(r, velocityRows(r)).kind).toBe('SUPPRESSED')
  })

  it('refuses a forecast whose percentiles are not numbers', () => {
    // "p50 = NaN" renders as a confident-looking dash, which is worse than a
    // stated refusal.
    const r = report({ forecast: { p50: Number.NaN, p85: 23, sampleSize: 6 } })
    expect(velocityBand(r, velocityRows(r)).kind).toBe('SUPPRESSED')
  })

  it('does not contradict itself when a big enough sample came back bandless', () => {
    // Six sprints and no percentiles is not "you need at least 3" — reusing that
    // wording prints a sentence that argues with the number beside it.
    const r = report({ forecast: { p50: null, p85: null, sampleSize: 6 } })
    const band = velocityBand(r, velocityRows(r))
    expect(velocitySentence(band, 'COUNT'))
      .toBe('No forecast came back for the 6 sprints below, so none is shown. '
        + 'The sprints themselves are the record and they are unaffected.')
  })

  it('tolerates a response with no forecast at all', () => {
    // Typed as always present, but a client that throws on a field a server
    // stopped sending turns a degraded report into a blank page.
    const r = { forecast: undefined } as unknown as VelocityReport
    expect(velocityBand(r, velocityRows(report())).kind).toBe('SUPPRESSED')
  })

  it('says the plain thing when no sprint has finished at all', () => {
    const r = report({ sprints: [], forecast: { p50: null, p85: null, sampleSize: 0 } })
    const band = velocityBand(r, velocityRows(r))
    expect(velocitySentence(band, 'COUNT'))
      .toBe('No sprint has finished in this project yet, so there is nothing to forecast from.')
  })

  it('reports the observed range from the bars, not from the percentiles', () => {
    const r = report({ forecast: { p50: 18, p85: 19, sampleSize: 6 } })
    const band = velocityBand(r, velocityRows(r))
    expect(band).toMatchObject({ low: 14, high: 23 })
  })

  it('prints points through the same formatter as every other sprint surface', () => {
    const r = report({
      measure: 'POINTS',
      sprints: [
        sprint({ sprintId: 'a', completed: 34 }),
        sprint({ sprintId: 'b', completed: 55.5 }),
        sprint({ sprintId: 'c', completed: 41 }),
      ],
      forecast: { p50: 41, p85: 55.5, sampleSize: 3 },
    })
    const band = velocityBand(r, velocityRows(r))
    expect(velocitySentence(band, 'POINTS')).toBe(
      'Recent sprints delivered between 34 and 55.5 points; plan for ~41 (p50) and treat 55.5 '
      + '(p85) as a stretch. Based on 3 sprints.',
    )
  })

  it('does not claim a range when every sprint delivered the same', () => {
    const r = report({
      sprints: [sprint({ sprintId: 'a', completed: 12 }), sprint({ sprintId: 'b', completed: 12 }),
        sprint({ sprintId: 'c', completed: 12 })],
      forecast: { p50: 12, p85: 12, sampleSize: 3 },
    })
    const band = velocityBand(r, velocityRows(r))
    expect(velocitySentence(band, 'COUNT')).toContain('Recent sprints each delivered 12 issues')
  })
})

describe('the caption is permanent, and it is this caption', () => {
  it('is verbatim from §2.5', () => {
    expect(VELOCITY_CAPTION)
      .toBe('Story points are team-relative. Velocity is not comparable between teams.')
  })
})

describe('velocityAxisMax — the p85 rule stays on the canvas', () => {
  it('reaches above the tallest bar AND above the forecast', () => {
    const rows = velocityRows(report())
    expect(velocityAxisMax(rows, { kind: 'BAND', p50: 18, p85: 40, sampleSize: 6, low: 14, high: 23 }))
      .toBe(40)
    expect(velocityAxisMax(rows, { kind: 'SUPPRESSED', sampleSize: 6 })).toBe(23)
  })

  it('is zero for an empty report rather than -Infinity', () => {
    expect(velocityAxisMax([], { kind: 'SUPPRESSED', sampleSize: 0 })).toBe(0)
  })
})
