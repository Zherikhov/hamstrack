/**
 * Deterministic fuzzy matcher for the command palette (HD-39, spec §6.1–§6.3).
 *
 * Greedy-leftmost subsequence scoring: O(|target|), no allocation-heavy DP, and
 * trivially unit-testable. The trade-off is deliberate — it can under-score a
 * candidate whose better alignment sits further right (`"bo"` aligns with the
 * `B…o` of *Backlog* rather than its best run). Do NOT swap in a different
 * algorithm without updating `fuzzy.test.ts`: the palette's row ordering is a
 * muscle-memory surface and the tests pin the exact numbers' relative order.
 *
 * Everything here is pure — no DOM, no clock, no randomness.
 */

/** Separator characters that make the following index a "word boundary". */
const SEPARATORS = new Set([' ', '-', '_', '/', '.', ':', ',', '(', ')', '[', ']'])

const LOWER_OR_DIGIT = /[a-z0-9]/
const UPPER = /[A-Z]/

/**
 * True when `target[i]` starts a new word: it follows a separator, or it is an
 * uppercase letter following a lowercase letter/digit (camelCase step).
 *
 * Tested against the ORIGINAL casing of `target` — the caller lowercases only
 * for the subsequence walk.
 */
export function isBoundary(target: string, i: number): boolean {
  if (i <= 0) return false
  const prev = target[i - 1]
  if (SEPARATORS.has(prev)) return true
  return LOWER_OR_DIGIT.test(prev) && UPPER.test(target[i])
}

/**
 * Score `query` against `target`, or `null` when `query` is not a subsequence of
 * `target` (case-insensitive). Higher is better; scores are only comparable
 * within one run — never persist or display them.
 *
 * Empty query scores `0` for every target (so "no query" keeps everything).
 */
export function fuzzyScore(query: string, target: string): number | null {
  const q = query.toLowerCase()
  const t = target.toLowerCase()
  if (q === '') return 0
  if (q.length > t.length) return null

  let score = 0
  let prev = -1
  for (let k = 0; k < q.length; k++) {
    const i = t.indexOf(q[k], prev + 1) // greedy leftmost
    if (i === -1) return null
    const gap = i - prev - 1
    let bonus = 0
    if (i === 0) bonus += 8
    else if (isBoundary(target, i)) bonus += 6
    if (gap === 0 && prev >= 0) bonus += 4
    score += 1 + bonus - Math.min(gap, 3)
    prev = i
  }

  // Whole-candidate adjustments: prefix > substring > scattered, and a mild
  // preference for short labels so "Board" outranks "Board of directors".
  if (t.startsWith(q)) score += 15
  else if (t.includes(q)) score += 8
  score -= Math.min(target.length, 40) * 0.05
  return score
}

/** The subset of a `Command` that participates in matching (spec §6.2). */
export interface Matchable {
  label: string
  sublabel?: string
  meta?: string
  keywords?: string[]
}

/**
 * Score ONE query token against every matchable field of a candidate (§6.2).
 * `null` when no field matches — the candidate is filtered out.
 *
 * Field weights: `meta` (issue/project key) gets a flat +10 because a short-code
 * hit is a very strong intent signal; keywords count 0.75; sublabel 0.5.
 */
export function candidateScore(token: string, c: Matchable): number | null {
  let best: number | null = null
  const take = (v: number | null) => {
    if (v !== null && (best === null || v > best)) best = v
  }

  take(fuzzyScore(token, c.label))
  if (c.meta) {
    const m = fuzzyScore(token, c.meta)
    take(m === null ? null : m + 10)
  }
  for (const kw of c.keywords ?? []) {
    const k = fuzzyScore(token, kw)
    take(k === null ? null : k * 0.75)
  }
  if (c.sublabel) {
    const s = fuzzyScore(token, c.sublabel)
    take(s === null ? null : s * 0.5)
  }
  return best
}

/**
 * Multi-word query score (§6.3): the trimmed query is split on whitespace and
 * EVERY token must match some field (AND semantics); the candidate's score is
 * the sum. `"pay bo"` therefore matches "Go to Board — Payments" while
 * `"pay zz"` matches nothing.
 *
 * An empty/whitespace-only query scores `0` for everything.
 */
export function queryScore(query: string, c: Matchable): number | null {
  const tokens = query.trim().split(/\s+/).filter(Boolean)
  if (tokens.length === 0) return 0
  let total = 0
  for (const token of tokens) {
    const s = candidateScore(token, c)
    if (s === null) return null
    total += s
  }
  return total
}

/**
 * Filter + order a section's candidates (§6.4). Stable: score DESC, then the
 * caller's section-native tie-break (recency, server order, …), then `id` ASC
 * so the result is fully deterministic.
 */
export function rank<T extends Matchable & { id: string }>(
  query: string,
  items: T[],
  tieBreak?: (a: T, b: T) => number,
): T[] {
  const scored: { item: T; score: number }[] = []
  for (const item of items) {
    const score = queryScore(query, item)
    if (score !== null) scored.push({ item, score })
  }
  scored.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score
    const t = tieBreak ? tieBreak(a.item, b.item) : 0
    if (t !== 0) return t
    return a.item.id < b.item.id ? -1 : a.item.id > b.item.id ? 1 : 0
  })
  return scored.map(s => s.item)
}
