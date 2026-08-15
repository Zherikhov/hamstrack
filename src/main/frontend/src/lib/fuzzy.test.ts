import { describe, it, expect } from 'vitest'
import { fuzzyScore, candidateScore, queryScore, rank, isBoundary } from './fuzzy'

// HD-39 §17.1: the palette's matcher is pure and its RELATIVE ordering is a
// muscle-memory surface, so these lock in the ranking contract (not the exact
// numbers). Changing the algorithm means changing these tests deliberately.

describe('fuzzyScore', () => {
  it('prefers exact prefix over mid-string over a scattered subsequence', () => {
    const board = fuzzyScore('bo', 'Board')!
    const backlog = fuzzyScore('bo', 'Backlog')!
    const bugReport = fuzzyScore('bo', 'Bug report')!
    expect(board).not.toBeNull()
    expect(board).toBeGreaterThan(backlog)
    expect(backlog).toBeGreaterThan(bugReport)
  })

  it('returns null when the query is not a subsequence of the target', () => {
    expect(fuzzyScore('xyz', 'Board')).toBeNull()
    expect(fuzzyScore('ob', 'Board')).toBeNull() // order matters
  })

  it('returns 0 for an empty query against any target', () => {
    expect(fuzzyScore('', 'Board')).toBe(0)
    expect(fuzzyScore('', '')).toBe(0)
    expect(fuzzyScore('', 'anything at all')).toBe(0)
  })

  it('returns null when the query is longer than the target', () => {
    expect(fuzzyScore('boardroom', 'Board')).toBeNull()
  })

  it('awards the word-boundary bonus ("gb" fits "Go Board" better than "Gobbledegook")', () => {
    expect(fuzzyScore('gb', 'Go Board')!).toBeGreaterThan(fuzzyScore('gb', 'Gobbledegook')!)
  })

  it('awards the contiguity bonus (a longer contiguous run outscores a gapped one)', () => {
    expect(fuzzyScore('boar', 'Board')!).toBeGreaterThan(fuzzyScore('bor', 'Board')!)
  })

  it('is case-insensitive in both directions', () => {
    expect(fuzzyScore('BO', 'board')).toBe(fuzzyScore('bo', 'board'))
    expect(fuzzyScore('bo', 'BOARD')).toBe(fuzzyScore('bo', 'board'))
  })

  it('is deterministic — identical inputs give an identical score every time', () => {
    const first = fuzzyScore('bo', 'Go to Board — Payments')
    for (let i = 0; i < 100; i++) {
      expect(fuzzyScore('bo', 'Go to Board — Payments')).toBe(first)
    }
  })
})

describe('isBoundary', () => {
  it('is true after a separator character', () => {
    expect(isBoundary('Go Board', 3)).toBe(true)   // after a space
    expect(isBoundary('my-work', 3)).toBe(true)    // after a hyphen
    expect(isBoundary('a/b', 2)).toBe(true)        // after a slash
  })

  it('is true at a camelCase step and false mid-word', () => {
    expect(isBoundary('myWork', 2)).toBe(true)
    expect(isBoundary('Gobbledegook', 2)).toBe(false)
  })

  it('is false at index 0 (that is the start-of-target bonus instead)', () => {
    expect(isBoundary('Board', 0)).toBe(false)
  })
})

describe('candidateScore (multi-field, §6.2)', () => {
  it('matches on the label', () => {
    expect(candidateScore('boa', { label: 'Board' })).not.toBeNull()
  })

  it('weights a meta (key) hit above an equivalent label hit', () => {
    const viaMeta = candidateScore('hd', { label: 'zzzzzzzz', meta: 'HD-42' })!
    const viaLabel = candidateScore('hd', { label: 'HD-42' })!
    expect(viaMeta).toBeGreaterThan(viaLabel)
  })

  it('matches on keywords and on the sublabel when the label does not match', () => {
    expect(candidateScore('kanban', { label: 'Go to Board', keywords: ['kanban'] })).not.toBeNull()
    expect(candidateScore('payments', { label: 'Go to Board', sublabel: 'Payments' })).not.toBeNull()
  })

  it('returns null when no field matches', () => {
    expect(candidateScore('zzz', { label: 'Board', keywords: ['kanban'], sublabel: 'Payments' })).toBeNull()
  })
})

describe('queryScore (multi-word AND, §6.3)', () => {
  it('ANDs every token — all must match', () => {
    expect(queryScore('pay bo', { label: 'Board — Payments' })).not.toBeNull()
    expect(queryScore('pay zz', { label: 'Board — Payments' })).toBeNull()
  })

  it('matches tokens independently across fields', () => {
    const c = { label: 'Go to Board', sublabel: 'Payments', keywords: ['kanban'] }
    expect(queryScore('board payments', c)).not.toBeNull()
  })

  it('treats a whitespace-only query as empty (score 0)', () => {
    expect(queryScore('   ', { label: 'Board' })).toBe(0)
  })
})

describe('rank', () => {
  const items = [
    { id: 'c', label: 'Bug report' },
    { id: 'a', label: 'Backlog' },
    { id: 'b', label: 'Board' },
  ]

  it('filters out non-matches and orders by score DESC', () => {
    expect(rank('bo', items).map(i => i.id)).toEqual(['b', 'a', 'c'])
    expect(rank('zzz', items)).toEqual([])
  })

  it('breaks ties with the caller tie-break, then by id ASC', () => {
    const same = [
      { id: 'z', label: 'Board' },
      { id: 'a', label: 'Board' },
      { id: 'm', label: 'Board' },
    ]
    expect(rank('bo', same).map(i => i.id)).toEqual(['a', 'm', 'z'])
    // A caller tie-break wins over the id fallback.
    const order = ['m', 'z', 'a']
    const byOrder = (x: { id: string }, y: { id: string }) => order.indexOf(x.id) - order.indexOf(y.id)
    expect(rank('bo', same, byOrder).map(i => i.id)).toEqual(['m', 'z', 'a'])
  })

  it('keeps everything (in tie-break order) for an empty query', () => {
    expect(rank('', items).map(i => i.id)).toEqual(['a', 'b', 'c'])
  })

  it('is order-independent: shuffling the input cannot change the output order', () => {
    // Equal scores everywhere — the only thing left is the deterministic
    // tie-break chain (§6.4), which must not depend on how the caller happened
    // to build the pool (a cache refetch can reorder it at any time).
    const pool = [
      { id: 'p3', label: 'Board' },
      { id: 'p1', label: 'Board' },
      { id: 'p2', label: 'Board' },
      { id: 'p0', label: 'Boardwalk' },
    ]
    const expected = rank('bo', pool).map(i => i.id)
    expect(expected).toEqual(['p1', 'p2', 'p3', 'p0']) // shorter label first, then id ASC
    for (const shuffled of [
      [...pool].reverse(),
      [pool[1], pool[3], pool[0], pool[2]],
      [pool[2], pool[0], pool[3], pool[1]],
    ]) {
      expect(rank('bo', shuffled).map(i => i.id)).toEqual(expected)
    }
  })

  it('returns an identical order on every repeated call (no hidden state)', () => {
    const pool = [
      { id: 'b', label: 'Board', keywords: ['kanban'] },
      { id: 'a', label: 'Backlog', keywords: ['queue'] },
      { id: 'c', label: 'Bug report', sublabel: 'Boats' },
    ]
    const first = rank('bo', pool).map(i => i.id)
    for (let i = 0; i < 50; i++) expect(rank('bo', pool).map(x => x.id)).toEqual(first)
  })

  it('keeps a disabled-looking duplicate label deterministic across sections', () => {
    // Two workspaces genuinely named the same thing (§14 case 23).
    const same = [
      { id: 'workspace:zzz', label: 'Acme' },
      { id: 'workspace:aaa', label: 'Acme' },
    ]
    expect(rank('ac', same).map(i => i.id)).toEqual(['workspace:aaa', 'workspace:zzz'])
  })
})

describe('multi-word semantics (§6.3) in depth', () => {
  const cmd = { id: 'x', label: 'Go to Board', sublabel: 'Payments', meta: 'PAY', keywords: ['kanban'] }

  it('scores the same however the tokens are ordered (the score is a sum)', () => {
    expect(queryScore('board payments', cmd)).toBe(queryScore('payments board', cmd))
  })

  it('ignores surrounding and repeated whitespace between tokens', () => {
    expect(queryScore('  board    payments  ', cmd)).toBe(queryScore('board payments', cmd))
  })

  it('fails the whole candidate as soon as ONE token misses every field', () => {
    expect(queryScore('board kanban', cmd)).not.toBeNull()
    expect(queryScore('board zzqq', cmd)).toBeNull()
    expect(queryScore('zzqq', cmd)).toBeNull()
  })

  it('lets different tokens match different fields (label + sublabel + keyword)', () => {
    expect(queryScore('go pay kanban', cmd)).not.toBeNull()
  })

  it('takes the MAX across fields per token, never the sum of fields', () => {
    const twoFieldHit = candidateScore('pay', cmd)!
    const bestSingle = Math.max(
      // meta hit (+10) is the strongest signal for this token
      candidateScore('pay', { label: cmd.label, meta: cmd.meta })!,
      candidateScore('pay', { label: cmd.label, sublabel: cmd.sublabel })!,
    )
    expect(twoFieldHit).toBe(bestSingle)
  })
})
