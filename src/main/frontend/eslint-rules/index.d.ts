/**
 * Hand-written declarations for the plugin (Q5): the rules are plain ESM with no
 * build step, and `tsconfig.node.json` includes only the two Vite configs, so
 * nothing in `eslint-rules/` enters `tsc -b` on its own. This file is what lets
 * `src/lint/*.test.ts` — which *is* inside `tsc -b` — import them.
 *
 * `Rule` is kept structural rather than importing ESLint's own types: the tests
 * hand these objects straight to `RuleTester`, which takes `any`-shaped rules,
 * and a structural declaration cannot drift out of sync with a version bump.
 */
export interface HamstrackRule {
  meta: {
    type: string
    docs: { description: string }
    schema: unknown[]
    messages: Record<string, string>
  }
  create(context: unknown): Record<string, (node: unknown) => void>
}

export declare const rules: Record<string, HamstrackRule>
export declare const RULE_NAMES: string[]
declare const plugin: { rules: Record<string, HamstrackRule> }
export default plugin
