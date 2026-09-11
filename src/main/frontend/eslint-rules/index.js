import noShadowedMaxWidth from './no-shadowed-max-width.js'
import noNumericFontSize from './no-numeric-font-size.js'
import overlayHasDialogSemantics from './overlay-has-dialog-semantics.js'
import noRoleNameGate from './no-role-name-gate.js'

/**
 * **The `hamstrack` ESLint plugin — one mechanism per mechanical trap.**
 *
 * The frontend trap list lives in `.claude/agents/frontend-builder.md`. Four of
 * its entries have a mechanical form and are executed here; the fifth (a
 * stylesheet token reaching a prop that means "a stored hue") is executed by the
 * `Hex` type in `src/types.ts` and by `tsc -b`, because a type follows a value
 * through a `const` and a lookup table and a lint rule cannot. Two mechanisms
 * for one trap is the duplication this repo treats as a defect, so each trap has
 * exactly one — see `src/lint/eslintRules.test.ts`, which enumerates the set.
 *
 * Written as plain ESM with no build step and no type-aware parsing: none of the
 * four needs a type, and `projectService` would charge a full program build per
 * run for nothing.
 */
export const rules = {
  'no-shadowed-max-width': noShadowedMaxWidth,
  'no-numeric-font-size': noNumericFontSize,
  'overlay-has-dialog-semantics': overlayHasDialogSemantics,
  'no-role-name-gate': noRoleNameGate,
}

/** The names, in one place, so a scan can assert the set rather than a list it retyped. */
export const RULE_NAMES = Object.keys(rules)

export default { rules }
