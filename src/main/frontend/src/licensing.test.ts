/// <reference types="vite/client" />
import { describe, it, expect } from 'vitest'

/**
 * HD-194 — **no surface of this SPA may call Hamstrack open source.**
 *
 * Hamstrack ships under the **Elastic License 2.0**, which is source-available
 * and *not* OSI-approved: it forbids providing the software to third parties as
 * a hosted or managed service. That is a field-of-use restriction, so the Open
 * Source Definition excludes it. The claim was true once (the project started on
 * Apache 2.0) and survived the relicence in `880c5cd` on five separate surfaces —
 * which is the whole reason this file exists: a phrase is not corrected by
 * correcting it, only by making its return fail.
 *
 * The rule is about a **claim**, not a string. An occurrence may name the phrase when
 * it is *denying* it ("source-available, not open source"), and a true sentence
 * about somebody else's software is not this defect — but nothing in `src/` says
 * that today, so the escape hatch is deliberately narrow: the negation must sit
 * **immediately** before the phrase, or don't write it.
 *
 * Scope is the SPA's own text — every `.ts`/`.tsx`/`.css` under `src/` plus the
 * served `index.html` (title, meta description, Open Graph). The repo's other
 * surfaces (`README.md`, `docs/self-hosting.md`, `pom.xml`, `DESIGN.md`) carry
 * the same phrase and are listed in the failure message below, because a guard
 * that silently covers half a set teaches the next reader that the other half is
 * covered too.
 *
 * `?raw` rather than `node:fs`: `@types/node` is deliberately not a dependency
 * here (same reason as `moduleGraph.test.ts`).
 */

// `import.meta.glob` excludes the importing module, so this file is the one file
// under `src/` that its own rule does not apply to — and that exclusion is
// LOAD-BEARING: four lines here name the phrase without denying it (the javadoc
// above, the CLAIM comment, the checklist's opening line, and the second
// `it(...)` title), so a scan that saw this file would report the seal itself.
// Probed rather than assumed: the identical glob evaluated from a *different*
// module does include `licensing.test.ts`. So do NOT lift these two globs into a
// shared helper module — the day they move, the seal goes red on itself and the
// failure names its own source lines, which reads like a bug in the test.
const SOURCES = import.meta.glob('./**/*.{ts,tsx,css}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const HTML = import.meta.glob('../index.html', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

// ── The rule in two pieces, each written exactly ONCE ───────────────────────────────────
//
// THE CLAIM: "open source", "open-source", "opensource" — any casing, any joiner — plus the
// inflections "open-sourced", "open sources", "open-sourcing". A trailing `\b` after a bare
// `source` made every inflected form invisible, and that hole outlived three rounds of review
// because each round attacked the exemption and none re-read the phrase: the planted footer
// line "Hamstrack — proudly open-sourced since 2026" passed a green suite. Inflection is not a
// loophole a reader honours — "we are open-sourcing Hamstrack" is the same false licence claim
// as "Hamstrack is open source", and ELv2 makes both untrue.
//
// It is defined ONCE and composed into both regexes below because it used to be written out
// twice — once as the claim, once inside the denial. Those two copies drift in the direction
// that fails silently: an inflection added to the claim but not to the denial does not merely
// miss something, it UN-EXEMPTS every lawful denial in the codebase, and an inflection added to
// the denial alone exempts a claim nothing can flag. One definition makes that unrepresentable.
const PHRASE = String.raw`\bopen[\s-]?sourc(?:e|ed|es|ing)\b`

// THE ONE PERMITTED USE: a refusal ATTACHED to the phrase — between the two may sit only
// whitespace, quote characters (straight or typographic) and at most one article.
// e.g. "source-available, not open source" (AboutModal), 'never "open source"'
// (index.html — the gap there is a space *and a double quote*, which is why a
// whitespace-only gap would be too tight).
//
// This replaces a `[^.]{0,60}` window that only asked for a negation somewhere
// earlier on the line, which ordinary prose trips: the planted footer line
// "Hamstrack — not just another tracker, an open-source one" passed the guard —
// a shipped, user-visible false licence claim walking through the check that
// exists to stop exactly it. A false negative in a seal is silent, so the gap is
// now spelled out instead of counted.
//
// CSS `:not(` is a word-boundary match for `not`, so it could buy a stylesheet an
// exemption. The old window blocked that only by accident — `[^.]` forbids a period,
// which most CSS between a `:not(.foo)` and a string is full of — and the accident
// did not hold for dot-free selectors: the rule
// `li:not(:last-child)::after { content: "open source"; }`
// was exempt under it, and is flagged now. Adjacency closes the whole
// class BY CONSTRUCTION rather than by luck, because `(` is never a permitted gap
// character, so no `:not(…)` can reach the phrase however the selector is written.
// Separately it also happens to be unreached today — no line under `src/` pairs the
// two — but that is a fact about today's stylesheets and is not what makes it safe.
const NEG = String.raw`\b(?:not|never|isn't)\b[\s"'“”‘’]*(?:\b(?:an?|the)\b[\s"'“”‘’]*)?`

const PROPAGATION_CHECKLIST = [
  'HD-194: Hamstrack is SOURCE-AVAILABLE (Elastic License 2.0), not open source.',
  'ELv2 is not OSI-approved — it forbids offering the software to third parties as a',
  'hosted or managed service, which the Open Source Definition excludes.',
  '',
  'Use "source-available", or "Elastic License 2.0" immediately followed by what it',
  'permits (self-host, modify, commercial use) and the one thing it does not.',
  'Never name the licence in one place and its permissions in another — that is exactly',
  'how these two halves drifted apart the first time.',
  '',
  'The same phrase lives on surfaces this test cannot see. If you are changing the',
  'wording, change all of them in the same commit:',
  '  - README.md (header under the licence badge, and the ## License section)',
  '  - src/main/frontend/index.html (<title>, meta description, og:title, og:description)',
  '  - src/main/frontend/src/pages/LandingPage.tsx (document.title, hero eyebrow,',
  '    deploy-section licence note, final CTA)',
  '  - src/main/frontend/src/components/AboutModal.tsx (summary + licence line)',
  '  - src/main/frontend/src/components/Footer.tsx',
  '  - src/main/frontend/src/pages/legal/TermsPage.tsx (§1 The Service)',
  '  - docs/self-hosting.md (licence callout above Contents)',
  '  - DESIGN.md (Product Context), docs/design/handover-northstar.md (§1)',
  '  - pom.xml <description>',
  '',
  'And one surface that is not copy but a copy GENERATOR: .claude/agents/systems-analyst.md',
  'described Hamstrack as "an open-source Jira-inspired task tracker" in the prompt that',
  'writes every future spec, so it would have put the phrase back into docs/design/ by',
  'construction — without an edit to any file above. Corrected; read any agent or skill',
  'prompt added later the same way.',
].join('\n')

// Occurrence-scoped forms of the two pieces above. `CLAIM_G` is only ever consumed through
// `String.matchAll`, which iterates over an internal copy, so this shared instance carries no
// `lastIndex` state between lines — do not `exec` it directly. `DENIAL_AT_END` anchors the
// refusal to the END of the text handed to it, which is what makes "attached" mean attached to
// THIS occurrence rather than present somewhere earlier in the text.
const CLAIM_G = new RegExp(PHRASE, 'gi')
const DENIAL_AT_END = new RegExp(NEG + PHRASE + '$', 'i')

// A denial exempts the OCCURRENCE it attaches to, NOT the line it sits on, so a bare second use
// of the phrase beside a lawful first one still offends. Line scope let one attached denial
// clear every other use beside it — and in the copy this file guards, a line is not a sentence.
// Two claims sharing one line is the PREVAILING shape here, not an exotic one, and in
// `index.html` it is structural: a `meta`/`og:` value is a single attribute string that cannot
// be wrapped without changing what is served, so those lines run ~200 characters (198–212
// today) and hold as many sentences as the copy needs. JSX copy in `src/**/*.tsx` does the same
// by hand — long strings past 140 characters with a sentence boundary inside them, in numbers
// that a grep will always find more of than a count written here would admit. The planted
// description `Hamstrack is source-available (not open source). The open source alternative to
// Jira: boards, backlog…` passed under line scope — a shipped, SEO-indexed false licence claim,
// the same silent false negative as the footer line above, one level down.
function bareMatches(text: string): { at: number; len: number }[] {
  return [...text.matchAll(CLAIM_G)]
    .filter((m) => !DENIAL_AT_END.test(text.slice(0, m.index + m[0].length)))
    .map((m) => ({ at: m.index, len: m[0].length }))
}

// PASS 1 reports one entry per offending LINE, naming the column of every offending occurrence
// on it — not one entry per occurrence. Two identical entries would read as two separate lines;
// but a single entry for a doubled offender is the worse failure of the two, because the reader
// fixes the occurrence they were shown, re-runs, and sees the same file:line come back red,
// which reads like a broken test rather than a second offence. Columns are 1-based into the RAW
// line (so they match an editor); the echoed text is trimmed for legibility.
//
// PASS 2 catches the phrase WRAPPED across a line break — the exact mirror of the long-line
// shape argued for above, and just as prevailing: `AboutModal.tsx:101` wraps mid-sentence and
// `TermsPage.tsx:16` wraps "plan and track" / "issues on boards". JSX collapses the break into
// one space, so the reader sees a single string while a line scanner sees two halves and
// neither half is the phrase.
//
// Each non-blank line is paired with the NEXT NON-BLANK line — not with its literal neighbour —
// and the two are joined the way JSX renders them (`a.trimEnd() + ' ' + b.trimStart()`). The
// skip is the whole point: JSX renders adjacent TEXT while a file has adjacent LINES, and the
// two diverge exactly where a blank or whitespace-only line falls between the halves, which JSX
// deletes rather than renders. Pairing literal neighbours missed that, and missed it silently.
// Only matches that STRADDLE the join are reported, so a same-line offender from pass 1 is
// never reported twice — and the pairing stays injective once lines can be skipped, because a
// blank line is never a head, so no line is the partner of two different predecessors. The
// denial test runs on the joined text too, so a refusal ending the first line still exempts a
// phrase completed on the second.
//
// The two numbers in a wrap report are the lines carrying the two HALVES, which are therefore
// not necessarily consecutive — whatever sits between them rendered nothing. The column is into
// the first of the two, and an elided gap is echoed as `⏎…⏎` rather than `⏎`.
//
// KNOWN EDGES — every round of review so far has found this guard blind to something (a
// `[^.]{0,60}` window, then line scope, then inflections, then the wrap, then the wrap's own
// blank-line hole), so it states its limits instead of implying completeness:
//   - Pass 2's reach is a property of what JSX RENDERS, not of how the file is laid out: it
//     pairs text with the next text, so blank space between the halves is invisible to the copy
//     and to the guard alike. Its limit is where the rendered string is ASSEMBLED rather than
//     written — `{'open '}{'source'}`, `'open ' + 'source'`, a template split, a `{' '}` between
//     the halves — because then the halves are not literal text side by side and no pairing of
//     lines reaches them. That limit is structural: closing it needs assertions over RENDERED
//     output, not a text scan.
//   - More breaks do NOT extend the reach, and need not: the phrase has exactly ONE internal
//     separator, so a second break must fall inside a word and renders as "open sourc e", which
//     is not the claim. Same for a break after the joiner — that renders "open- source".
//   - Copy that is not literal text in `src/` (assembled at runtime, or translated) is out of
//     reach by construction, as are the non-SPA surfaces listed in the checklist above.
function offendingLines(id: string, source: string): string[] {
  const lines = source.split(/\r?\n/)
  const out: string[] = []

  lines.forEach((line, i) => {
    const cols = bareMatches(line).map((m) => m.at + 1)
    if (cols.length > 0) {
      const where = cols.length === 1 ? `col ${cols[0]}` : `cols ${cols.join(', ')}`
      out.push(`${id}:${i + 1} (${where}): ${line.trim()}`)
    }

    const head = line.trimEnd()
    if (head === '') return
    let j = i + 1
    while (j < lines.length && lines[j].trim() === '') j++
    if (j >= lines.length) return

    const tail = lines[j]
    const joined = `${head} ${tail.trimStart()}`
    for (const m of bareMatches(joined)) {
      // Straddling = starts before the inserted space and ends after it. Anything wholly
      // inside either half already belongs to that line's own pass-1 entry.
      if (m.at < head.length && m.at + m.len > head.length + 1) {
        out.push(
          `${id}:${i + 1}+${j + 1} (wrapped across the break, col ${m.at + 1}): ` +
            `${line.trim()} ${j === i + 1 ? '⏎' : '⏎…⏎'} ${tail.trim()}`,
        )
      }
    }
  })

  return out
}

describe('licensing copy (HD-194)', () => {
  it('scans a non-trivial number of files', () => {
    // Tripwire: a glob that silently matches nothing passes every assertion below.
    expect(Object.keys(SOURCES).length).toBeGreaterThan(50)
    expect(Object.keys(HTML)).toHaveLength(1)
  })

  it('no SPA surface calls Hamstrack open source', () => {
    const offenders = Object.entries({ ...SOURCES, ...HTML })
      .flatMap(([id, source]) => offendingLines(id, source))

    expect(offenders, `\n${PROPAGATION_CHECKLIST}\n\nFound:\n${offenders.join('\n')}\n`)
      .toEqual([])
  })

  it('index.html states the licence and what it permits together', () => {
    const html = Object.values(HTML)[0]
    // The two halves the ticket exists to keep in one place.
    expect(html).toMatch(/source-available/i)
    expect(html).toMatch(/Elastic License 2\.0/)
  })
})
