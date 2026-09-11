/**
 * **A `px` type size ignores the browser's font-size preference.**
 *
 * The type scale in `DESIGN.md` is `rem`-based precisely so that a reader who
 * has set 20px in their browser gets 20px-relative text. A `fontSize: 12` in a
 * style object is an absolute px and silently opts that string out — which is
 * how roughly half the product's text came to ignore the preference (HD-177).
 *
 * **Not restricted to a property named `style`.** `fontSize` as an object key is
 * unambiguous in this codebase, and narrowing to `style={{…}}` would miss
 * recharts' `tick={{ fontSize: 11 }}` — the axis labels, which are read. That
 * spelling is a real and recurring share of the debt, not a hypothetical one.
 *
 * **What "a value" means here, and what it deliberately does not.** Reported: a
 * numeric literal, a signed one, a `px` string or single-quasi template, both
 * arms of a conditional, and **arithmetic one of whose operands is a numeric
 * literal** — `fontSize: size * 0.4`. That last shape entered the rule in
 * HD-300's fix round, because the tests gate found the one site in the tree that
 * wears it and it is the smallest text in the product (`ui.tsx`'s `Avatar`, 7.2
 * to 12px). Reading it as "not decidable" was wrong in exactly the direction
 * that matters: the literal is right there, and scaling a px prop yields px.
 *
 * Not reported, and visible to a reviewer as "the value is not a literal here":
 * a bare identifier, arithmetic over two identifiers (`base * scale` — the units
 * live in whatever `base` is), a spread, a computed key, and a template with
 * expressions in it. A `rem`/`em`/`%` string is the remedy and is always legal.
 */

const PX_STRING = /^\s*-?\d*\.?\d+px\s*$/

/** The operators that scale or offset a size. `**` is not one anybody writes here. */
const ARITHMETIC = new Set(['*', '/', '+', '-', '%'])

/** Does this arithmetic tree put a numeric literal into the result at any depth? */
function hasNumericLiteral(node) {
  if (!node) return false
  if (node.type === 'Literal' && typeof node.value === 'number') return true
  if (node.type === 'UnaryExpression' && (node.operator === '-' || node.operator === '+')) {
    return hasNumericLiteral(node.argument)
  }
  if (node.type === 'BinaryExpression' && ARITHMETIC.has(node.operator)) {
    return hasNumericLiteral(node.left) || hasNumericLiteral(node.right)
  }
  return false
}

function offendingValue(node) {
  if (!node) return null
  if (node.type === 'Literal' && typeof node.value === 'number') return 'number'
  if (node.type === 'UnaryExpression' && (node.operator === '-' || node.operator === '+')) {
    const inner = node.argument
    if (inner && inner.type === 'Literal' && typeof inner.value === 'number') return 'number'
  }
  if (node.type === 'Literal' && typeof node.value === 'string' && PX_STRING.test(node.value)) return 'px'
  if (node.type === 'TemplateLiteral' && node.expressions.length === 0) {
    const raw = node.quasis[0]?.value.cooked ?? ''
    if (PX_STRING.test(raw)) return 'px'
  }
  // `cond ? 12 : 13` — both arms are the same mistake.
  if (node.type === 'ConditionalExpression') {
    return offendingValue(node.consequent) ?? offendingValue(node.alternate)
  }
  // `size * 0.4`, `base + 2`, `size + '2px'` — a px arithmetic result is still px.
  if (node.type === 'BinaryExpression' && ARITHMETIC.has(node.operator)) {
    if (hasNumericLiteral(node)) return 'number'
    return offendingValue(node.left) ?? offendingValue(node.right)
  }
  return null
}

export default {
  meta: {
    type: 'problem',
    docs: { description: 'forbid an absolute px type size in a style object' },
    schema: [],
    messages: {
      numeric:
        'fontSize: {{text}} is an absolute px, so it ignores the browser font-size ' +
        'preference. The type scale is rem-based — use a Tailwind text-* class or a ' +
        'rem string (HD-177).',
    },
  },
  create(context) {
    const sourceCode = context.sourceCode ?? context.getSourceCode()
    return {
      Property(node) {
        if (node.computed) return
        const key = node.key
        const name =
          key.type === 'Identifier' ? key.name
            : key.type === 'Literal' && typeof key.value === 'string' ? key.value
              : null
        if (name !== 'fontSize') return
        if (!offendingValue(node.value)) return
        context.report({
          node,
          messageId: 'numeric',
          data: { text: sourceCode.getText(node.value) },
        })
      },
    }
  },
}
