/**
 * **The four Node APIs `suiteRecorder.ts` uses, declared here because this SPA has
 * no `@types/node` (HD-301).**
 *
 * `node_modules/@types/` holds twenty-odd packages and none of them is `node`:
 * `vitest` declares it as an OPTIONAL peer and nothing in this tree installs it,
 * `vite.config.ts` and `vitest.config.ts` touch no Node API, and until HD-301 no
 * file under `src/` did either — `marginReporter.ts`, the other standing reporter,
 * only calls `console.log`. So `tsc -b` reported `Cannot find module 'node:fs'`
 * and three `Cannot find name 'process'` for the recorder (measured 2026-09-11).
 *
 * **Why not `npm i -D @types/node`.** It would put `process`, `Buffer`, `require`
 * and the whole Node global surface into scope for every component in the SPA,
 * which is browser code that must not compile against any of them — a type
 * package that makes wrong code type-check is a worse trade than eleven lines of
 * declaration. It would also be a dependency and a lockfile change inside a
 * build-guard ticket.
 *
 * **Every declaration here is a MODULE declaration, and that is the load-bearing
 * part.** The first version of this file ended in a bare `declare const process`,
 * which is a *global*: this file has no top-level import or export, so TypeScript
 * treats it as a script, and `tsconfig.app.json` includes `src` whole — the
 * `exclude` that now lifts this file out of that program was added by the same
 * fix, and `src/lint/nodeApi.test.ts` is what keeps it there. Both halves are
 * needed and neither is the other: the module declarations are what stop a
 * component reaching `process`, the exclude is what stops one importing
 * `node:fs`. Measured 2026-09-11 — a component doing
 * `import { appendFileSync } from 'node:fs'` and reading `process.env` compiled
 * clean AND linted clean, then threw `ReferenceError: process is not defined` in
 * the browser, and the plausible fix for that (`define: { 'process.env':
 * process.env }` in `vite.config.ts`) inlines `DB_PASSWORD` and `JWT_SECRET` into
 * a public bundle. A module declaration costs an explicit `import` at the one call
 * site and grants nothing to a file that does not write one. The general rule,
 * since the claim above was the one that went wrong: an ambient `.d.ts` under an
 * `include`d root reaches EVERY file under it, so what it declares must be
 * reachable only by naming it.
 *
 * `src/lint/nodeApi.test.ts` holds each of them: that nothing under `src/` but the
 * recorder names a `node:` import or `process.`, that this file declares no
 * global, and that the two node-only files are out of the app program and inside
 * the node one — a file in no project is a file `tsc -b` never reads, measured as
 * exit 0 over a real type error.
 *
 * So: exactly the signatures the recorder calls, nothing more. Add to this file
 * only what a file under `src/` genuinely needs at runtime; if the list ever
 * grows past a handful, that is the signal to take the dependency instead and to
 * bound it with a `types` entry rather than a global.
 */

declare module 'node:fs' {
  export function appendFileSync(path: string, data: string, encoding: 'utf8'): void
  export function mkdirSync(path: string, options: { recursive: boolean }): void
}

declare module 'node:path' {
  export function relative(from: string, to: string): string
  export function resolve(...segments: string[]): string
}

declare module 'node:console' {
  const console: { error(message: string): void }
  export default console
}

declare module 'node:process' {
  const process: {
    cwd(): string
    readonly pid: number
    readonly env: Record<string, string | undefined>
  }
  export default process
}
