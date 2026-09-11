import { describe, it, expect } from 'vitest'
import NODE_API_SOURCE from '../test/nodeApi.d.ts?raw'
import TSCONFIG_APP_SOURCE from '../../tsconfig.app.json?raw'
import TSCONFIG_NODE_SOURCE from '../../tsconfig.node.json?raw'
import { code } from './source'

/**
 * **Node APIs are reachable only by the one file that runs in Node (HD-301).**
 *
 * `src/test/suiteRecorder.ts` is a vitest *reporter*: it runs in the Node process
 * that drives the suite, and it writes a file. Everything else under `src/` is
 * browser code, where `process` does not exist and `node:fs` cannot be bundled.
 *
 * **Why this test exists rather than a comment.** The declarations the recorder
 * needs live in `src/test/nodeApi.d.ts`, and the first version of that file ended
 * in a bare `declare const process`. A `.d.ts` with no top-level import or export
 * is a *script*, `tsconfig.app.json` then included all of `src` — the `exclude`
 * that now takes these two files out of it was added in the same ticket, by the
 * fix below — and so that one line put `process` in scope for every component in
 * the SPA. Measured 2026-09-11: a component doing `import { appendFileSync } from
 * 'node:fs'` and reading `process.env.VITE_API_TOKEN` type-checked clean (`tsc -p
 * tsconfig.app.json` exit 0) **and** linted clean (`eslint` exit 0, because the
 * flat config carries four `hamstrack/*` rules and no `no-undef`). It would have
 * thrown `ReferenceError: process is not defined` in the browser, and the
 * plausible fix for that — `define: { 'process.env': process.env }` in
 * `vite.config.ts` — inlines the whole build environment, `DB_PASSWORD` and
 * `JWT_SECRET` included, into a public bundle.
 *
 * **Three halves, not two, and the third was held by nothing.** The `declare
 * module` rewrite closes `process`; the tsconfig re-partition — these two files
 * out of the app program and into the node one — is what closes `node:fs`, and
 * neither substitutes for the other. Nothing read the tsconfigs until round 3.
 * Measured 2026-09-11, with `src/test/suiteRecorder.ts` dropped from
 * `tsconfig.node.json`'s `include` and a real type error planted inside it:
 * `npx tsc -b --force` exited **0** and all four `src/lint/` files, 35 tests,
 * stayed green — a file in no project is a file no gate reads, which is what
 * `tsconfig.node.json`'s own comment says and what nothing enforced.
 */

/** Every `.ts`/`.tsx` source in the SPA, read the way the neighbouring category tests read them. */
const SOURCES = import.meta.glob('../**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

/**
 * The floor on the scan itself: "found no offender" and "looked at no file" print
 * the same green line. The glob matched **222** files on 2026-09-11 — the same
 * tree, the same pattern and the same floor as `debt.test.ts`, which is why both
 * say the same number and why a diff that moves one moves the other. Like every
 * floor in this ticket it catches the collapse — a glob that resolves to nothing
 * after a directory rename — and not the drift.
 */
const FILE_FLOOR = 200

/**
 * **The files under `src/` that run in NODE rather than in the browser** — one
 * enumeration, read by the exemption list below *and* by the tsconfig partition,
 * because those two are the same fact about the same files and a second copy is a
 * drift waiting for the diff that fixes one of them.
 *
 * Repo-relative from the SPA root, the spelling both tsconfigs use.
 */
const NODE_ONLY = ['src/test/suiteRecorder.ts', 'src/test/nodeApi.d.ts']

/**
 * The files that may name a Node API, by identity rather than by pattern, each with
 * the reason it is here — a pattern would quietly cover the next file that matches it.
 *
 * - the two in `NODE_ONLY`: `suiteRecorder.ts` runs in Node (a vitest reporter that
 *   writes a file), and `nodeApi.d.ts` *is* the declarations;
 * - `debt.test.ts` pins `lint.mjs`'s exit line as TEXT (`process.exit(1)` inside a
 *   regex), and `lint.mjs` is a Node script that is not under this scan at all;
 * - this file quotes all of them in prose, and excluding it by name would rot.
 *
 * A scan over text cannot tell a call from a quotation, so it is loud and wrong about
 * four files rather than quiet and wrong about a component — the trade the suite
 * guard's own bound makes. Matched by the glob key exactly, not by a suffix: moving
 * one of these makes it an offender, which is a one-line fix in plain sight rather
 * than an exemption that silently follows the file.
 *
 * **This list is an early warning and not the bound**, which is worth knowing before
 * trusting it: adding a path here turns the seal green for that file even if it then
 * reads `process.pid` for real. What actually refuses such a file is the type gate —
 * `tsc -b` reports `Cannot find module 'node:fs'` and `Cannot find name 'process'`
 * for anything in the app program, because the declarations are module declarations
 * and live outside it (the assertions below hold both of those in place). This scan
 * exists to fail in a sentence, one commit earlier, where `tsc` would fail in a
 * compiler diagnostic.
 */
const ALLOWED = [
  ...NODE_ONLY.map((file) => file.replace('src/', '../')),
  './debt.test.ts',
  './nodeApi.test.ts',
]

/** `node:` imports and `process.` reads — the two spellings that reach a Node global. */
const NODE_API = /from\s+'node:|require\s*\(|\bprocess\s*\./

describe('the SPA scans its own sources before asserting anything about them', () => {
  it('matched enough files that a green line means something', () => {
    expect(
      Object.keys(SOURCES).length,
      'the glob matched almost nothing, so the assertions below are green for free',
    ).toBeGreaterThanOrEqual(FILE_FLOOR)
  })
})

describe('browser code cannot reach a Node API by accident', () => {
  it('names one outside the recorder nowhere under src/', () => {
    const offenders = Object.entries(SOURCES)
      .filter(([file]) => !ALLOWED.includes(file))
      .filter(([, source]) => NODE_API.test(code(source)))
      .map(([file]) => file)

    expect(
      offenders,
      'a file under src/ names a Node API. This is browser code: `process` does not exist there ' +
      'and `node:fs` cannot be bundled, so the page throws `ReferenceError: process is not ' +
      'defined` at module evaluation — a white screen at runtime. `tsc -b` refuses it too, in a ' +
      'compiler diagnostic one commit later (measured: TS2307 on node:fs, TS2591 on process, ' +
      'exit 2); this is the same refusal in a sentence, and it stops being the earlier one if ' +
      'the declarations ever go back to being global. Use `import.meta.env.VITE_*` for ' +
      'configuration; if the file genuinely runs in Node (a vitest reporter, a script), it ' +
      'belongs beside src/test/suiteRecorder.ts, in NODE_ONLY above and therefore in both ' +
      'tsconfigs` partition, in a diff that says why. Do NOT add ' +
      '`define: { "process.env": process.env }` to vite.config.ts: that ' +
      'inlines DB_PASSWORD and JWT_SECRET into a public bundle.',
    ).toEqual([])
  })

  it('keeps nodeApi.d.ts declaring modules and never globals', () => {
    // The file has no top-level import or export, so TypeScript treats it as a
    // script and everything it declares at the top level is GLOBAL — for every
    // file of whichever program includes it. That is tsconfig.node.json today and
    // would be tsconfig.app.json (`include: ["src"]`) the moment the exclude
    // asserted below goes; a module declaration is reachable only by a file that
    // imports it by name, which is what makes this safe under either.
    const globals = code(NODE_API_SOURCE)
      .split('\n')
      .filter((l) => /^(declare|interface|type|const|let|var|function|class|enum|namespace)\b/.test(l))
      .filter((l) => !/^declare module '/.test(l))

    expect(
      globals,
      'src/test/nodeApi.d.ts declares something at the top level that is not a module. That file ' +
      'is an ambient script under tsconfig.app.json`s `include`, so a top-level declaration is ' +
      'in scope for EVERY component in the SPA — which is how `declare const process` once made ' +
      'a component reading process.env compile and lint clean. Wrap it in ' +
      "`declare module 'node:<name>' { … }` and import it at the call site.",
    ).toEqual([])
  })
})

/**
 * The other half of the same design, and the half that closes `node:fs` rather than
 * `process`. Both tsconfigs are read as text and parsed, so this asserts the files
 * `tsc -b` is actually handed — not a sentence about them in a comment.
 */
describe('every file under src/ is in exactly one typescript project', () => {
  const app = JSON.parse(TSCONFIG_APP_SOURCE) as { include?: string[]; exclude?: string[] }
  const node = JSON.parse(TSCONFIG_NODE_SOURCE) as { include?: string[] }

  it('keeps the node-only files out of the app program, where their declarations are ambient', () => {
    for (const file of NODE_ONLY) {
      expect(
        app.exclude ?? [],
        `tsconfig.app.json no longer excludes ${file}. Its \`include\` is ["src"], so this file ` +
        'is back in the browser program: src/test/nodeApi.d.ts is a SCRIPT (no top-level import ' +
        "or export), so its `declare module 'node:fs'` is offered to every component and a " +
        'component importing node:fs type-checks clean, then throws at module evaluation in the ' +
        'browser. The `declare module` rewrite does not cover this — it closes `process` and ' +
        'this closes `node:fs`. Restore the entry; tsconfig.node.json compiles these two.',
      ).toContain(file)
    }
  })

  it('keeps every file the app program excludes inside the node program', () => {
    // The rule, over the excluded set rather than over the two files by name: a
    // file in no project is a file no gate reads. Measured 2026-09-11 — with
    // suiteRecorder.ts removed from this `include` and a real type error inside
    // it, `npx tsc -b --force` exited 0 and the whole src/lint suite stayed green.
    expect(
      app.exclude ?? [],
      'tsconfig.app.json excludes nothing, so the loop below would iterate zero times and pass. ' +
      'The two node-only files are asserted above; this is that assertion`s floor.',
    ).toHaveLength(NODE_ONLY.length)

    for (const file of app.exclude ?? []) {
      expect(
        node.include ?? [],
        `${file} is excluded from tsconfig.app.json and is in no other project, so \`tsc -b\` ` +
        'never reads it: a real type error inside it compiles green (measured — exit 0 with the ' +
        'whole lint suite passing). `npm run typecheck` is the only gate that reads these files ' +
        'at all, since vitest transpiles without checking. Add it to tsconfig.node.json`s ' +
        '`include`, or put it back in the app program.',
      ).toContain(file)
    }
  })
})
