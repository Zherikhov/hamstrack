/**
 * **Comments are dropped before a text assertion about configuration** — the one
 * spelling of that filter, shared by every test that makes one (HD-300, HD-301).
 *
 * Each of the files these tests read DOCUMENTS the mechanism it must not contain:
 * the pom comment names the suppression flag it forbids, `lint.mjs` explains why
 * it takes no cache flag, the flat config discusses its own `ignores`,
 * `vitest.config.ts` quotes an earlier, insufficient spelling of its own
 * `server.fs.allow`. So a naive scan fires on the explanation and teaches the
 * next author to stop explaining. Prose is not configuration.
 *
 * Line-based on purpose: a `/* … *\/` stripper that does not understand string
 * literals eats the config's own `'node_modules/**'` and everything after it,
 * which is a scan that reads nothing while looking like one that read everything.
 *
 * It lives here rather than beside its first caller because the second caller
 * arrived one ticket later (`suiteGuard.test.ts`), and the comment it was copied
 * from ended with the sentence "there is no third spelling of this filter in the
 * repo" — a claim about a *count*, which goes stale one entry before the list
 * does. A shared function keeps that true by construction instead.
 */
export function code(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')                      // XML comments, unambiguous
    .split('\n')
    .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))         // JS/TS comment lines
    .join('\n')
}
