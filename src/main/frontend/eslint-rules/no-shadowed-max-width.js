import { attributeValue, findAttribute, resolveOnce, stringPartsOf } from './lib/resolve.js'

/**
 * **The `--spacing-*` scale shadows Tailwind's named `max-w-*` widths.**
 *
 * `src/index.css` declares `--spacing-{2xs,xs,sm,md,lg,xl,2xl,3xl}` inside
 * `@theme`. In Tailwind v4 that namespace is what `max-w-<name>` resolves
 * against, so `max-w-xl` is **32px**, not `36rem`. Every one of the eight names
 * is a live trap and no other one is: `--spacing-3xs` is not declared, so
 * `max-w-3xs` still means what Tailwind means (measured 2026-09-11, and the
 * reason this rule's list is eight names and not the nine the trap list reads
 * as). Numeric (`max-w-44`), fractional and `max-w-full`/`-none`/`-min`/`-max`/
 * `-fit`/`-screen*`/`-prose` do not read the namespace and are legal.
 *
 * **Written against the AST, never the file text.** Day-one population is 0,
 * but a `ripgrep` for the same class names returns **ten** hits, and all ten are
 * *comments warning about this trap*. A text-shaped rule would have shipped
 * with ten suppressions on ten correct lines, and the suppressions would have
 * been believed. A comment is not a `className`, so it is invisible here.
 */

const SHADOWED = ['2xs', 'xs', 'sm', 'md', 'lg', 'xl', '2xl', '3xl']
// Anchored on token boundaries: `max-w-xl` fires, `max-w-xl-foo` and
// `hover:max-w-screen-lg` do not (the latter is a different namespace).
const CLASS_RE = new RegExp(String.raw`(?:^|\s)(?:[\w-]+:)*max-w-(${SHADOWED.join('|')})(?=\s|$)`)

export default {
  meta: {
    type: 'problem',
    docs: {
      description:
        'forbid the Tailwind max-w-* names that the @theme --spacing-* scale shadows',
    },
    schema: [],
    messages: {
      shadowed:
        'max-w-{name} reads the @theme --spacing-* scale in Tailwind v4, so "max-w-{{name}}" ' +
        'resolves to {{px}} — not the Tailwind width you meant. Use style={{ maxWidth: n }} ' +
        '(HD-300; the scale is declared in src/index.css @theme).',
    },
  },
  create(context) {
    const sourceCode = context.sourceCode ?? context.getSourceCode()
    const px = {
      '2xs': '2px', xs: '4px', sm: '8px', md: '16px',
      lg: '24px', xl: '32px', '2xl': '48px', '3xl': '64px',
    }
    return {
      JSXOpeningElement(node) {
        const attr = findAttribute(node, 'className')
        const value = attributeValue(attr)
        if (!value) return
        const resolved = resolveOnce(value, sourceCode, sourceCode.getScope(node))
        for (const part of stringPartsOf(resolved)) {
          const m = CLASS_RE.exec(part.value)
          if (m) {
            context.report({
              node: part.node,
              messageId: 'shadowed',
              data: { name: m[1], px: px[m[1]] },
            })
            return
          }
        }
      },
    }
  },
}
