/**
 * The one indirection these rules follow, shared by every rule that needs it.
 *
 * **Why it exists (HD-300, measured):** the trap list is written as if the
 * offending value sat literally on the JSX attribute, and for `style` that is
 * true of only four of the eight `position:'fixed', inset:0` sites in this repo.
 * The other four — `AboutModal`, `CreateIssueModal`, `CreateProjectModal`,
 * `WorkspaceMembersModal` — write
 *
 *     const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, … }
 *     …
 *     <div style={overlayStyle}>
 *
 * and **three of those four are the accessibility defects the rule exists to
 * find**. A rule that reads only the attribute's own expression scores a clean
 * pass over all three. So the hop is not a nicety; without it the rule's
 * day-one population would have been 0 instead of 3.
 *
 * **Exactly one hop, and only through a `const`.** No transitive chase, no
 * `let`, no reassignment, no cross-file resolution: those need either a type
 * checker or a whole-program pass, and a rule that follows *some* chains
 * silently is worse than one whose reach is stated. Everything this cannot see
 * is visible to the reviewer as "the value is not a literal here".
 */

/**
 * Resolve an expression to the node that carries its literal shape.
 *
 * Returns `node` unchanged unless it is an `Identifier` bound, in the enclosing
 * scope chain, to exactly one `const` declarator with an initialiser — in which
 * case the initialiser is returned. `x as T` / `<T>x` / `x!` wrappers are
 * unwrapped on both sides, because `React.CSSProperties` annotations and `as
 * const` are how this codebase actually writes these objects.
 */
export function resolveOnce(node, sourceCode, scope) {
  const bare = unwrap(node)
  if (!bare || bare.type !== 'Identifier') return bare
  const variable = findVariable(scope, bare.name)
  if (!variable || variable.defs.length !== 1) return bare
  const def = variable.defs[0]
  if (def.type !== 'Variable') return bare
  if (def.parent && def.parent.kind !== 'const') return bare
  if (!def.node.init) return bare
  // A reassignment anywhere means the initialiser is not the whole story.
  if (variable.references.some((r) => r.isWrite() && r.identifier !== def.name)) return bare
  return unwrap(def.node.init)
}

/** Strip the TS-only expression wrappers so a rule never has to know about them. */
export function unwrap(node) {
  let n = node
  while (
    n &&
    (n.type === 'TSAsExpression' ||
      n.type === 'TSSatisfiesExpression' ||
      n.type === 'TSTypeAssertion' ||
      n.type === 'TSNonNullExpression' ||
      n.type === 'ParenthesizedExpression')
  ) {
    n = n.expression
  }
  return n
}

/** Walk the scope chain for a binding. `sourceCode.getScope(node)` gives the start. */
function findVariable(scope, name) {
  for (let s = scope; s; s = s.upper) {
    const found = s.variables.find((v) => v.name === name)
    if (found) return found
  }
  return null
}

/**
 * The value carried by a `JSXAttribute` — `foo="x"` gives the `Literal`,
 * `foo={expr}` gives `expr` (unwrapped), anything else gives `null`.
 */
export function attributeValue(attr) {
  if (!attr || !attr.value) return null
  if (attr.value.type === 'JSXExpressionContainer') {
    const e = attr.value.expression
    return e && e.type !== 'JSXEmptyExpression' ? unwrap(e) : null
  }
  return unwrap(attr.value)
}

/** The named attribute of a `JSXOpeningElement`, or `null`. Spread attributes are opaque. */
export function findAttribute(openingElement, name) {
  const attrs = openingElement.attributes || []
  for (const a of attrs) {
    if (a.type === 'JSXAttribute' && a.name && a.name.type === 'JSXIdentifier' && a.name.name === name) {
      return a
    }
  }
  return null
}

/**
 * Every string this expression can contribute, as `{ node, value }` pairs.
 *
 * Covers the shapes a `className` is written in here: a literal, a template's
 * static chunks, `clsx(a, b && c, { d: cond })` (the repo's only class helper),
 * a conditional's two arms, an array, and a logical fallback. A comment is
 * never any of these node types, which is the whole difference between this
 * rule and the ten false positives a text search would report.
 */
export function stringPartsOf(node, out = []) {
  if (!node) return out
  const n = unwrap(node)
  switch (n.type) {
    case 'Literal':
      if (typeof n.value === 'string') out.push({ node: n, value: n.value })
      break
    case 'TemplateLiteral':
      for (const q of n.quasis) out.push({ node: q, value: q.value.cooked ?? q.value.raw })
      for (const e of n.expressions) stringPartsOf(e, out)
      break
    case 'ConditionalExpression':
      stringPartsOf(n.consequent, out)
      stringPartsOf(n.alternate, out)
      break
    case 'LogicalExpression':
      stringPartsOf(n.left, out)
      stringPartsOf(n.right, out)
      break
    case 'BinaryExpression':
      if (n.operator === '+') {
        stringPartsOf(n.left, out)
        stringPartsOf(n.right, out)
      }
      break
    case 'ArrayExpression':
      for (const el of n.elements) stringPartsOf(el, out)
      break
    case 'ObjectExpression':
      // clsx({ 'a b': cond }) — the KEY is the class list.
      for (const p of n.properties) {
        if (p.type !== 'Property') continue
        if (p.key.type === 'Literal' && typeof p.key.value === 'string') {
          out.push({ node: p.key, value: p.key.value })
        }
      }
      break
    case 'CallExpression': {
      const callee = unwrap(n.callee)
      const name =
        callee.type === 'Identifier'
          ? callee.name
          : callee.type === 'MemberExpression' && callee.property.type === 'Identifier'
            ? callee.property.name
            : ''
      if (name === 'clsx' || name === 'cx' || name === 'classNames') {
        for (const a of n.arguments) stringPartsOf(a, out)
      }
      break
    }
    default:
      break
  }
  return out
}
