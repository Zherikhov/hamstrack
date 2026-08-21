/// <reference types="vite/client" />
import { describe, it, expect } from 'vitest'

/**
 * A **static** guard on the import graph around `queryClient.ts`.
 *
 * The hazard it seals is an import cycle:
 *
 *     queryClient.ts → api.ts → auth.ts → queryClient.ts
 *
 * which existed briefly and was harmless only by accident — nothing in it
 * dereferenced an imported binding while the modules were evaluating. The first
 * top-level line that does (a `queryClient.setQueryDefaults(...)` at `auth.ts`
 * module scope, a top-level `ApiResponseError` reference in `queryClient.ts`)
 * makes one of the three see a half-initialised partner. `ApiResponseError`
 * resolves `undefined`, the `instanceof` in `retryQuery` throws, and the
 * behaviour that comes back is **"a 422 is retryable again"** — silently, in the
 * bundle, and in the exact direction `retryQuery` exists to prevent.
 *
 * Why this reads *source text* instead of importing anything: the hazard is
 * about the order modules are evaluated in, and Vitest enters the graph at a
 * different module than the app bundle does. A runtime test that imported
 * `queryClient.ts` and found it healthy would be evidence about this file's
 * entry point, not about the app's. Parsing imports is order-free, so it answers
 * the question that was actually asked. (`?raw` rather than `node:fs` because
 * `@types/node` is deliberately not a dependency here.)
 *
 * Scope of the parse, and why each choice is the safe one:
 *  • Only **static** `import`/`export … from` specifiers count. A dynamic
 *    `import()` is evaluated after its importer has finished, so it cannot hand
 *    anyone a half-initialised binding — a bundling concern, not this one.
 *  • `import type` / `export type` are erased before a bundle exists and carry
 *    no runtime edge. An inline `import { type A, b }` still does (`b`), and is
 *    counted.
 *  • Test files are excluded: nothing in the app imports them, so they cannot be
 *    part of a cycle the app can evaluate.
 */

// Eager + `?raw`: the file contents, never their exports — importing the app's
// modules here is the one thing this test must not do.
const SOURCES = import.meta.glob('./**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const isTestFile = (id: string) =>
  /\.(test|spec)\.tsx?$/.test(id) || /\.d\.ts$/.test(id) || id.startsWith('./test/')

// An import clause is only identifiers, braces, commas, `*`, `as` and
// whitespace. Bounding it that way stops a match from skipping over unrelated
// code to some later `from '…'` and inventing an edge that isn't there.
const FROM_CLAUSE = /^[ \t]*(?:import|export)\s+((?:[\w$*]|[{},\s])+?)\s*from\s*['"]([^'"]+)['"]/gm
const SIDE_EFFECT_ONLY = /^[ \t]*import\s*['"]([^'"]+)['"]/gm

function runtimeSpecifiers(source: string): string[] {
  const specs: string[] = []
  for (const match of source.matchAll(FROM_CLAUSE)) {
    if (/^\s*type[\s{]/.test(match[1])) continue // erased at build time
    specs.push(match[2])
  }
  for (const match of source.matchAll(SIDE_EFFECT_ONLY)) specs.push(match[1])
  return specs
}

/** `./a/b/c.ts` + `../x` → `./a/x` — the glob keys are all `src`-relative. */
function joinFromDir(importerId: string, spec: string): string {
  const parts = importerId.replace(/^\.\//, '').split('/').slice(0, -1)
  for (const segment of spec.split('/')) {
    if (segment === '.' || segment === '') continue
    if (segment === '..') parts.pop()
    else parts.push(segment)
  }
  return `./${parts.join('/')}`
}

/** Resolve a relative specifier the way the bundler does. `null` = not one of ours. */
function resolveSpecifier(importerId: string, spec: string): string | null {
  if (!spec.startsWith('.')) return null
  const base = joinFromDir(importerId, spec)
  for (const candidate of [base, `${base}.ts`, `${base}.tsx`, `${base}/index.ts`, `${base}/index.tsx`]) {
    if (candidate in SOURCES) return candidate
  }
  return null
}

const graph = new Map<string, string[]>()
for (const [id, source] of Object.entries(SOURCES)) {
  if (isTestFile(id)) continue
  graph.set(
    id,
    runtimeSpecifiers(source)
      .map(spec => resolveSpecifier(id, spec))
      .filter((id): id is string => id !== null && !isTestFile(id)),
  )
}

/** The first cycle passing through `entry`, as a printable path, or `null`. */
function cycleThrough(entry: string): string[] | null {
  const path: string[] = []
  // Memo is sound: "reaches `entry`" is a property of a module's reachable set,
  // not of the route taken to it, so a module cleared once stays cleared.
  const cleared = new Set<string>()
  function walk(node: string): string[] | null {
    path.push(node)
    for (const next of graph.get(node) ?? []) {
      if (next === entry) return [...path, entry]
      if (!cleared.has(next) && graph.has(next)) {
        cleared.add(next)
        const found = walk(next)
        if (found) return found
      }
    }
    path.pop()
    return null
  }
  return graph.has(entry) ? walk(entry) : null
}

describe('the import graph around the retry predicate', () => {
  it('sees the modules it claims to check', () => {
    // Guards the parser itself: if the glob or the regexes silently stopped
    // matching, every assertion below would pass by finding nothing at all.
    expect(graph.size, 'no app modules were parsed — the scan is broken, not the graph').toBeGreaterThan(50)
    expect(
      graph.get('./queryClient.ts'),
      'queryClient.ts vanished, or stopped importing the error type it branches on',
    ).toContain('./apiError.ts')
    expect(
      graph.get('./auth.ts'),
      'auth.ts no longer imports the query client — the cycle this file guards may have moved',
    ).toContain('./queryClient.ts')
  })

  it('keeps queryClient.ts out of every import cycle', () => {
    const cycle = cycleThrough('./queryClient.ts')
    expect(
      cycle,
      `queryClient.ts is back in an import cycle:\n\n      ${cycle?.join('\n   →  ')}\n\n`
      + 'A cycle here is benign only while nothing in it touches an imported binding during module '
      + 'evaluation, and it breaks in the worst direction: ApiResponseError resolves undefined, the '
      + 'instanceof in retryQuery throws, and a 422 STATEMENT_BUDGET_EXCEEDED becomes retryable again '
      + '— silently, in the bundle, with the unit tests still green because Vitest enters the graph '
      + 'somewhere else. Import the error type from ./apiError (the leaf), not from ./api.',
    ).toBeNull()
  })

  it('keeps apiError.ts a leaf, which is the whole reason it exists', () => {
    expect(
      graph.get('./apiError.ts'),
      'apiError.ts took on a runtime import. It is the shared leaf that lets queryClient.ts test for '
      + 'ApiResponseError without importing api.ts (→ auth.ts → queryClient.ts), and anything it '
      + 'imports can re-close that cycle from the other side. Type-only imports are fine — erased.',
    ).toEqual([])
  })
})
