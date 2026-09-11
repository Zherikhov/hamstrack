/**
 * Hand-written declarations for `lint.debt.mjs`, for the same reason
 * `eslint-rules/index.d.ts` exists: the debt record is plain ESM outside
 * `tsc -b`'s inputs, and `src/lint/debt.test.ts` — which *is* inside `tsc -b` —
 * imports it. The extension is `.d.mts`, not `.d.ts`: TypeScript resolves the
 * declarations for an `.mjs` specifier by that name and by no other.
 */
export declare const DECLARED: number
export declare const DECLARED_AS_OF: string
export declare const CLOCK_DAYS: number

/**
 * Every count required, `targets` alone optional — and the optionality is the
 * point of the rest. `verdict` compares with `>` and `<`, both FALSE against
 * `undefined`, so an omitted count silently deletes its own check rather than
 * relaxing it. These declarations do NOT protect the real caller (`lint.mjs` is
 * in no `tsc -b` project); `verdict` refuses a non-finite input at runtime, and
 * this interface only keeps `debt.test.ts`'s fixtures honest about which of
 * those two facts each one is exercising.
 */
export interface LintRun {
  fileCount: number
  floor: number
  targets?: string[]
  errors: number
  warnings: number
  suppressed: number
  unticketed: string[]
}

/** The failure texts a run earns; empty means it asserted what it claims to. */
export declare function verdict(run: LintRun): string[]
