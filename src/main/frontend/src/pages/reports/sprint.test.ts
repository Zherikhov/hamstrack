import { describe, it, expect } from 'vitest'
import {
  MAX_BURNUP_DAYS, burnupRows, changesByDay, formatDelta, formatMeasure, lastMeasuredDay,
  hasPoints, readReviewList, readSprintReportState, resolveSprintChoice, reviewHeadline,
  reviewSummary,
  writeBurnupParams, writeReviewParams,
} from './sprint'
import type {
  ScopeChange, Sprint, SprintBurnupReport, SprintReviewIssue, SprintReviewReport,
} from '../../types'

/**
 * HD-29 — the rules the two sprint reports have to agree on, tested where they
 * are pure rather than through two pages' worth of JSX.
 *
 * Everything here is a claim the reports make about their own numbers:
 *
 *  1. the burn-up's lines **stop at today** — no projection, ever (§2.3 rule 2);
 *  2. the ideal guide is drawn to the scope **committed at the start**, not to
 *     current scope, so widening a sprint never moves the bar it is read against;
 *  3. the x axis is the **sprint**, not the data, so a sprint halfway through
 *     looks halfway through;
 *  4. which sprint a page shows, and that a sprint's absence is never mistaken
 *     for a statement about the project's capabilities;
 *  5. the review's totals survive a response that left them out — the record of
 *     a finished sprint may not shed rows, and a missing count must not take the
 *     rows with it.
 */

function sprint(over: Partial<Sprint> = {}): Sprint {
  return {
    id: 's1', name: 'Sprint 12', state: 'ACTIVE', sequence: 12,
    startAt: '2026-08-14T00:00:00Z', endAt: '2026-08-20T00:00:00Z', completedAt: null,
    daysRemaining: 3, issueCount: 5, doneIssueCount: 2,
    points: null, donePoints: null, unestimatedCount: 0,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-14T00:00:00Z',
    ...over,
  }
}

function change(over: Partial<ScopeChange> = {}): ScopeChange {
  return {
    at: '2026-08-16T10:00:00Z', issueId: 'i7', key: 'PAY-77', event: 'ADDED',
    delta: 1, actorId: 'u1', storyPoints: 3, ...over,
  }
}

function burnup(over: Partial<SprintBurnupReport> = {}): SprintBurnupReport {
  return {
    sprint: { id: 's1', name: 'Sprint 12', state: 'ACTIVE' },
    startAt: '2026-08-14T00:00:00Z',
    endAt: '2026-08-20T00:00:00Z',
    measure: 'COUNT',
    committedAtStart: 6,
    unestimatedCount: 0,
    series: [
      { date: '2026-08-14', scope: 6, completed: 0 },
      { date: '2026-08-15', scope: 6, completed: 1 },
      { date: '2026-08-16', scope: 7, completed: 1 },
      { date: '2026-08-17', scope: 7, completed: 3 },
      { date: '2026-08-18', scope: 7, completed: 4 },
      { date: '2026-08-19', scope: 7, completed: 4 },
      { date: '2026-08-20', scope: 7, completed: 4 },
    ],
    scopeChanges: [change()],
    seriesTruncatedAt: null,
    meta: {
      computedAt: '2026-08-19T09:00:00Z', basedOnIssues: 7, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

describe('resolveSprintChoice — which sprint, and why', () => {
  it('defaults to the ACTIVE sprint', () => {
    const choice = resolveSprintChoice('', [sprint({ id: 'a', state: 'ACTIVE' }), sprint({ id: 'c', state: 'COMPLETED' })])
    expect(choice.sprint?.id).toBe('a')
    expect(choice.reason).toBe('ACTIVE')
  })

  it('falls back to the most recently completed sprint when nothing is running', () => {
    // The server orders COMPLETED descending, so the first one is the latest.
    const choice = resolveSprintChoice('', [
      sprint({ id: 'c9', state: 'COMPLETED' }),
      sprint({ id: 'c8', state: 'COMPLETED' }),
    ])
    expect(choice.sprint?.id).toBe('c9')
    expect(choice.reason).toBe('LATEST_COMPLETED')
  })

  it('falls back to the next planned sprint when nothing has run yet', () => {
    const choice = resolveSprintChoice('', [sprint({ id: 'f1', state: 'FUTURE' })])
    expect(choice.reason).toBe('PLANNED')
  })

  it('reports "none" for a project that has never had a sprint — a fact about DATA', () => {
    // Not a statement about the project's capabilities. Whether this project
    // runs sprints is `delivery.board`, which is declared; inferring it from
    // whether sprints exist is the documented shipped bug.
    const choice = resolveSprintChoice('', [])
    expect(choice.reason).toBe('NONE')
    expect(choice.sprint).toBeNull()
  })

  it('honours a pinned sprint from the URL over the active one', () => {
    const choice = resolveSprintChoice('c8', [
      sprint({ id: 'a', state: 'ACTIVE' }), sprint({ id: 'c8', state: 'COMPLETED' }),
    ])
    expect(choice.sprint?.id).toBe('c8')
    expect(choice.reason).toBe('PINNED')
  })

  it('never substitutes another sprint for an unknown pinned id', () => {
    // Silently swapping in the active sprint would show one report under
    // another one's link — the URL is the report's identity (§4.4).
    const choice = resolveSprintChoice('gone', [sprint({ id: 'a', state: 'ACTIVE' })])
    expect(choice.sprint).toBeNull()
    expect(choice.unknownPinned).toBe(true)
  })
})

describe('URL state', () => {
  it('defaults the measure to COUNT and degrades an unknown one to it', () => {
    expect(readSprintReportState(new URLSearchParams('')).measure).toBe('COUNT')
    expect(readSprintReportState(new URLSearchParams('measure=hours')).measure).toBe('COUNT')
    expect(readSprintReportState(new URLSearchParams('measure=POINTS')).measure).toBe('POINTS')
  })

  it('puts the measure on the burn-up wire — it is a different sum, not a re-render', () => {
    expect(writeBurnupParams({ sprintId: 's1', measure: 'POINTS' }))
      .toEqual({ sprintId: 's1', measure: 'POINTS' })
  })

  it('keeps the measure out of the review, which always reports both', () => {
    expect(writeReviewParams({ sprintId: 's1' })).toEqual({ sprintId: 's1' })
  })
})

describe('burnupRows — the line ends where it ends', () => {
  it('draws nothing after today, however far the sprint runs', () => {
    const rows = burnupRows(burnup(), '2026-08-17')
    const future = rows.filter(r => r.future)
    expect(future.map(r => r.date)).toEqual(['2026-08-18', '2026-08-19', '2026-08-20'])
    // Not zero, not carried forward flat — absent. A flat line to the sprint's
    // end is a prediction, and this report deliberately makes none.
    for (const row of future) {
      expect(row.scope).toBeNull()
      expect(row.completed).toBeNull()
    }
    expect(lastMeasuredDay(rows)?.date).toBe('2026-08-17')
  })

  it('draws the whole sprint once it is over', () => {
    const rows = burnupRows(burnup(), '2026-09-01')
    expect(rows.every(r => !r.future)).toBe(true)
    expect(lastMeasuredDay(rows)?.completed).toBe(4)
  })

  it('spans the sprint, not the data — a sprint halfway through looks halfway through', () => {
    const rows = burnupRows(burnup({
      series: [{ date: '2026-08-14', scope: 6, completed: 0 }],
    }), '2026-08-14')
    expect(rows).toHaveLength(7)
    expect(rows[rows.length - 1].date).toBe('2026-08-20')
  })

  it('draws the ideal guide to the COMMITTED scope, across the whole sprint', () => {
    const rows = burnupRows(burnup(), '2026-08-20')
    expect(rows[0].ideal).toBe(0)
    // Committed 6, not the current scope of 7: adding work does not move the
    // line the sprint is read against.
    expect(rows[rows.length - 1].ideal).toBe(6)
    expect(rows[3].ideal).toBeCloseTo(3, 5)
  })

  it('keeps the ideal guide running through days the measured lines do not reach', () => {
    const rows = burnupRows(burnup(), '2026-08-15')
    const last = rows[rows.length - 1]
    expect(last.future).toBe(true)
    expect(last.scope).toBeNull()
    expect(last.ideal).toBe(6)
  })

  it('hangs every scope change on the UTC day it happened', () => {
    const rows = burnupRows(burnup(), '2026-08-20')
    const step = rows.find(r => r.date === '2026-08-16')!
    expect(step.changes).toHaveLength(1)
    expect(step.changes[0].key).toBe('PAY-77')
    expect(rows.find(r => r.date === '2026-08-15')!.changes).toHaveLength(0)
  })

  it('falls back to the server dates rather than laying out a mistyped century', () => {
    const rows = burnupRows(burnup({ endAt: '2126-08-20T00:00:00Z' }), '2026-08-20')
    expect(rows.length).toBeLessThan(MAX_BURNUP_DAYS)
    expect(rows).toHaveLength(7)
  })

  it('survives a sprint with no end date at all', () => {
    const rows = burnupRows(burnup({ endAt: null }), '2026-08-20')
    expect(rows).toHaveLength(7)
    expect(rows[rows.length - 1].date).toBe('2026-08-20')
  })
})

describe('changesByDay / formatting', () => {
  it('groups by the UTC day, not the reader’s local one', () => {
    const map = changesByDay([
      change({ at: '2026-08-16T23:30:00Z', key: 'A-1' }),
      change({ at: '2026-08-17T00:30:00Z', key: 'A-2' }),
    ])
    expect(map.get('2026-08-16')?.map(c => c.key)).toEqual(['A-1'])
    expect(map.get('2026-08-17')?.map(c => c.key)).toEqual(['A-2'])
  })

  it('prints counts as whole numbers and points the way every sprint section does', () => {
    expect(formatMeasure(7, 'COUNT')).toBe('7')
    expect(formatMeasure(3.0, 'POINTS')).toBe('3')
    expect(formatMeasure(2.5, 'POINTS')).toBe('2.5')
    expect(formatMeasure(null, 'COUNT')).toBe('—')
  })

  it('signs a delta so a removal cannot be read as an addition', () => {
    expect(formatDelta(1, 'COUNT')).toBe('+1')
    expect(formatDelta(-3, 'COUNT')).toBe('−3')
    expect(formatDelta(-2.5, 'POINTS')).toBe('−2.5')
  })
})
// ── The review record ────────────────────────────────────────────────────────

function reviewIssue(over: Partial<SprintReviewIssue> = {}): SprintReviewIssue {
  return {
    issueId: 'i1', key: 'PAY-1', title: 'One', typeId: 't1', assigneeId: 'u1',
    statusId: 'st1', points: 3, closedAt: '2026-08-20T10:00:00Z', deleted: false, ...over,
  }
}

/** The server's own list shape: `points` is NULL when nothing was estimated. */
function list(issues: SprintReviewIssue[]) {
  const estimated = issues.filter(i => typeof i.points === 'number')
  return {
    issues,
    count: issues.length,
    points: estimated.length ? estimated.reduce((n, i) => n + (i.points ?? 0), 0) : null,
    unestimatedCount: issues.length - estimated.length,
  }
}

function reviewReport(over: Partial<SprintReviewReport> = {}): SprintReviewReport {
  const committed = list([reviewIssue(), reviewIssue({ issueId: 'i2', key: 'PAY-2', points: 5 })])
  const completed = list([reviewIssue(), reviewIssue({ issueId: 'i3', key: 'PAY-3', points: 2 })])
  return {
    sprint: { id: 's1', name: 'Sprint 12', state: 'COMPLETED' },
    startAt: '2026-08-14T00:00:00Z', endAt: '2026-08-28T00:00:00Z',
    completedAt: '2026-08-28T09:00:00Z',
    committed,
    addedAfterStart: list([reviewIssue({ issueId: 'i3', key: 'PAY-3', points: 2 })]),
    removedBeforeEnd: list([]),
    completed,
    carriedOver: list([reviewIssue({ issueId: 'i2', key: 'PAY-2', points: 5 })]),
    totals: {
      committedCount: committed.count, committedPoints: committed.points,
      // completed (2) + carried over (1): what the sprint held at its end.
      atEndCount: 3, atEndPoints: 10,
      completedCount: completed.count, completedPoints: completed.points,
      addedAfterStartCount: 1,
    },
    meta: {
      computedAt: '2026-08-29T09:00:00Z', basedOnIssues: 3, truncated: false, cap: 20000,
      firstIssueAt: null, unmatchedFilters: [],
    },
    ...over,
  }
}

describe('readReviewList — the rows are the truth, the totals are a convenience', () => {
  it('derives a count and a point sum the server left out', () => {
    const derived = readReviewList({
      issues: [reviewIssue({ points: 3 }), reviewIssue({ issueId: 'i2', points: 1.5 })],
    } as never)
    expect(derived.count).toBe(2)
    expect(derived.points).toBe(4.5)
  })

  it('degrades a missing list to an empty one instead of throwing the page away', () => {
    expect(readReviewList(undefined).issues).toEqual([])
    expect(readReviewList(undefined).count).toBe(0)
  })
})

describe('hasPoints — a null sum IS "nothing here was estimated"', () => {
  it('is false when every row was unestimated', () => {
    // Round 2 made this structural: the server nulls the sum rather than sending
    // a 0 that reads as "the work added up to nothing", which is a measurement.
    expect(hasPoints(list([reviewIssue({ points: null })]))).toBe(false)
  })

  it('is true as soon as one row carries an estimate', () => {
    expect(hasPoints(list([reviewIssue({ points: null }), reviewIssue({ points: 2 })]))).toBe(true)
  })

  it('is false for an empty list — nothing to measure at all', () => {
    expect(hasPoints(list([]))).toBe(false)
  })
})

describe('reviewSummary / reviewHeadline', () => {
  it('prefers the server’s own totals, so the sentence cannot drift from the lists', () => {
    const s = reviewSummary(reviewReport())
    expect(s.committedCount).toBe(2)
    expect(s.committedPoints).toBe(8)
    expect(s.completedCount).toBe(2)
    expect(s.completedPoints).toBe(5)
    expect(s.addedCount).toBe(1)
  })

  it('measures completion against what the sprint HELD AT ITS END', () => {
    // The denominator is completed + carried over, which completed is a subset
    // of by construction — so the ratio cannot exceed one. Measured against the
    // commitment instead, work added after the start made "25 of 23" reachable.
    const s = reviewSummary(reviewReport())
    expect(s.atEndCount).toBe(3)
    expect(s.atEndPoints).toBe(10)
    expect(s.completedCount).toBeLessThanOrEqual(s.atEndCount)
    expect(s.carriedCount).toBe(1)
  })

  it('falls back to the lists when a response carries no totals', () => {
    const withoutTotals = reviewReport()
    // @ts-expect-error — deliberately modelling an older/partial response.
    delete withoutTotals.totals
    const s = reviewSummary(withoutTotals)
    expect(s.committedCount).toBe(2)
    expect(s.completedPoints).toBe(5)
  })

  it('reads as the one line §2.4 pins', () => {
    const line = reviewHeadline(reviewReport(), '14 Aug – 28 Aug')
    expect(line).toContain('Sprint 12')
    expect(line).toContain('14 Aug – 28 Aug')
    expect(line).toContain('completed 2 of 3 issues (5 of 10 points)')
    expect(line).toContain('1 added after start')
  })

  it('drops the point clause entirely on a sprint nobody estimated', () => {
    const none = list([reviewIssue({ points: null })])
    const empty = list([])
    const line = reviewHeadline(reviewReport({
      committed: none, completed: none, carriedOver: empty,
      addedAfterStart: empty, removedBeforeEnd: empty,
      totals: {
        committedCount: 1, committedPoints: null, atEndCount: 1, atEndPoints: null,
        completedCount: 1, completedPoints: null, addedAfterStartCount: 0,
      },
    }), null)
    expect(line).toContain('completed 1 of 1 issue')
    expect(line).not.toContain('points')
    // Nothing arrived late, so that clause is absent rather than "0 added".
    expect(line).not.toContain('added after start')
  })
})

describe('reviewHeadline — the denominator can never be smaller than the numerator', () => {
  it('counts work added after the start into BOTH sides, not just the numerator', () => {
    // The case that settled this: a sprint that committed to 1, had 4 more
    // arrive, and finished all 5. Against the commitment that reads "5 of 1".
    const only = (n: number) => ({
      issues: Array.from({ length: n }, (_, i) => reviewIssue({ issueId: `x${i}`, key: `PAY-${i}` })),
      count: n, points: 3 * n, unestimatedCount: 0,
    })
    const line = reviewHeadline(reviewReport({
      committed: only(1),
      addedAfterStart: only(4),
      removedBeforeEnd: only(0),
      completed: only(5),
      carriedOver: only(0),
      totals: {
        committedCount: 1, committedPoints: 3, atEndCount: 5, atEndPoints: 15,
        completedCount: 5, completedPoints: 15, addedAfterStartCount: 4,
      },
    }), null)
    expect(line).toContain('completed 5 of 5 issues (15 of 15 points)')
    expect(line).toContain('4 added after start')
  })

  it('falls back to completed + carried over when a response carries no totals', () => {
    const withoutTotals = reviewReport()
    // @ts-expect-error — deliberately modelling an older/partial response.
    delete withoutTotals.totals
    const s = reviewSummary(withoutTotals)
    expect(s.atEndCount).toBe(3)
    expect(s.atEndPoints).toBe(10)
  })
})
