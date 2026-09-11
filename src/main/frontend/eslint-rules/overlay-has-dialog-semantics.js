import { attributeValue, findAttribute, resolveOnce, unwrap } from './lib/resolve.js'

/**
 * **A full-viewport backdrop with content in it is a dialog, and must say so.**
 *
 * The shared `Modal` (`pages/admin/common.tsx`) carries `role="dialog"`,
 * `aria-modal="true"` and an accessible name. Six overlays in this SPA are
 * hand-rolled instead; on 2026-09-11 **three of them carried no dialog
 * semantics at all** — `AboutModal`, `CreateIssueModal`, `CreateProjectModal` —
 * so a screen-reader user got an unannounced, unbounded region and `[role=
 * "dialog"]` found nothing to focus.
 *
 * **Why this is not "no hand-rolled overlay".** That formulation fires on
 * `CommandPalette`, `ShortcutsHelp` and `WorkspaceMembersModal`, which are
 * *correct*, and would have demanded three suppressions on working code — which
 * is how a codebase learns to suppress. This one fires on exactly the sites
 * that are broken, so a suppression is never the right answer to it. Migrating
 * the six onto `Modal` is HD-117 and is not blocked by this rule.
 *
 * **`data-modal-open` is deliberately not part of the test.** `CommandPalette`
 * documents at its own site why it omits it (its flag lives in `uiStore`), and a
 * rule a correct site must argue with is a rule people turn off.
 *
 * **Why a childless overlay is not a dialog.** `pages/admin/common.tsx:89` is a
 * self-closing `<span>` that catches the click that closes a popover. It has no
 * children, so it *cannot* contain a panel, and calling it a dialog would be
 * false. Discriminating on "has at least one element child" rather than
 * allow-listing the file keeps `Modal` itself — in that same file — under the
 * rule, which is where a regression would actually land. Measured: this
 * discriminator excludes exactly that one site out of the eight.
 *
 * Three `position: 'fixed'` sites carry no `inset` and are not overlays —
 * `AppShell` (toast), `NotificationBell` (portal menu), `ui.tsx` (dropdown).
 * They are a `valid` fixture in the rule's own test, not an exemption here.
 *
 * **ITS REACH, stated the way `lib/resolve.js` and `palette.contrast.test.ts`
 * state theirs.** This rule reads an **inline `style` object** — literally on the
 * attribute, or one `const` hop away (`resolveOnce`). It is therefore **blind to
 * a Tailwind-spelled overlay**: `className="fixed inset-0"` is invisible to it,
 * as is a `styled`/CSS-module class, a spread (`{...overlayProps}`), a style
 * built by a function, and any second hop. Population of the blind spelling was
 * **0 on 2026-09-11** — measured, not assumed: no `className` in the SPA carries
 * `fixed` with a full inset (`CookieBanner` is `fixed` without one). So this is
 * a hole in the *future*, not a miss in the present, and the honest summary is
 * that the rule covers how every overlay in this repo is written today and
 * nothing guarantees the next one is written that way. Growing it to read
 * `className` means reusing `stringPartsOf` from `lib/resolve.js` — the same
 * helper `no-shadowed-max-width` already uses — the day the first such overlay
 * exists.
 *
 * **What it checks is the ATTRIBUTES, not the behaviour.** A panel that carries
 * `role="dialog"`, `aria-modal="true"` and a name satisfies this rule whether or
 * not Escape closes it and whether or not focus is trapped in it — neither is
 * decidable from the JSX. HD-300 shipped Escape for the three sites it fixed
 * (`hooks/useCloseOnEscape.ts`, sealed by `components/dialogEscape.test.tsx`);
 * a focus trap exists nowhere in this repo and is a follow-up over the whole
 * dialog category, together with growing this rule to demand the keyboard
 * contract rather than only the attributes.
 */

const SIDES = ['top', 'right', 'bottom', 'left']

function propName(prop) {
  if (prop.type !== 'Property' || prop.computed) return null
  const k = prop.key
  if (k.type === 'Identifier') return k.name
  if (k.type === 'Literal' && typeof k.value === 'string') return k.value
  return null
}

function isZero(node) {
  const n = unwrap(node)
  if (!n) return false
  if (n.type === 'Literal' && (n.value === 0 || n.value === '0')) return true
  return false
}

/** `position: 'fixed'` plus a full inset — either `inset: 0` or all four sides at 0. */
function isFullViewportStyle(objectExpression) {
  if (!objectExpression || objectExpression.type !== 'ObjectExpression') return false
  let fixed = false
  let inset = false
  const zeroSides = new Set()
  for (const prop of objectExpression.properties) {
    const name = propName(prop)
    if (!name) continue
    const value = unwrap(prop.value)
    if (name === 'position' && value.type === 'Literal' && value.value === 'fixed') fixed = true
    if (name === 'inset' && isZero(value)) inset = true
    if (SIDES.includes(name) && isZero(value)) zeroSides.add(name)
  }
  return fixed && (inset || SIDES.every((s) => zeroSides.has(s)))
}

function attrText(attr) {
  const v = attributeValue(attr)
  if (!v) return null
  if (v.type === 'Literal' && typeof v.value === 'string') return v.value
  if (v.type === 'TemplateLiteral' && v.expressions.length === 0) {
    return v.quasis[0]?.value.cooked ?? null
  }
  return null
}

/** A named dialog: role="dialog"/"alertdialog" + aria-modal="true" + a name. */
function isNamedDialog(openingElement) {
  const role = attrText(findAttribute(openingElement, 'role'))
  if (role !== 'dialog' && role !== 'alertdialog') return false
  const modal = findAttribute(openingElement, 'aria-modal')
  const modalText = attrText(modal)
  // `aria-modal` with a non-literal value is accepted; a missing one is not.
  if (!modal || (modalText !== null && modalText !== 'true')) return false
  const named =
    findAttribute(openingElement, 'aria-label') ||
    findAttribute(openingElement, 'aria-labelledby')
  return Boolean(named)
}

/** Does any JSX element in this subtree (excluding the root) name itself a dialog? */
function hasDialogDescendant(root) {
  const stack = [...childrenOf(root)]
  let elements = 0
  let dialog = false
  while (stack.length > 0) {
    const n = stack.pop()
    if (!n) continue
    if (n.type === 'JSXElement') {
      elements += 1
      if (isNamedDialog(n.openingElement)) dialog = true
    }
    stack.push(...childrenOf(n))
  }
  return { dialog, elements }
}

/** JSX children plus the expressions inside `{…}` containers, so `{open && <Panel/>}` is walked. */
function childrenOf(node) {
  const out = []
  if (node.type === 'JSXElement' || node.type === 'JSXFragment') {
    for (const c of node.children) out.push(c)
    return out
  }
  if (node.type === 'JSXExpressionContainer') {
    if (node.expression && node.expression.type !== 'JSXEmptyExpression') out.push(node.expression)
    return out
  }
  if (node.type === 'LogicalExpression') return [node.left, node.right]
  if (node.type === 'ConditionalExpression') return [node.consequent, node.alternate]
  if (node.type === 'ArrayExpression') return node.elements.filter(Boolean)
  return out
}

export default {
  meta: {
    type: 'problem',
    docs: {
      description:
        'a full-viewport fixed overlay containing content must contain a named, modal dialog',
    },
    schema: [],
    messages: {
      undeclared:
        'this full-viewport overlay contains content but no descendant carries role="dialog" ' +
        'with aria-modal="true" and an accessible name, so a screen reader announces nothing ' +
        'and [role="dialog"] finds nothing. Use the shared Modal (pages/admin/common.tsx), or ' +
        'put role="dialog" aria-modal="true" aria-label={…} on the panel (HD-117).',
    },
  },
  create(context) {
    const sourceCode = context.sourceCode ?? context.getSourceCode()
    return {
      JSXElement(node) {
        const styleAttr = findAttribute(node.openingElement, 'style')
        const value = attributeValue(styleAttr)
        if (!value) return
        const resolved = resolveOnce(value, sourceCode, sourceCode.getScope(node))
        if (!isFullViewportStyle(resolved)) return
        // The overlay itself may be the dialog.
        if (isNamedDialog(node.openingElement)) return
        const { dialog, elements } = hasDialogDescendant(node)
        if (elements === 0) return // a click-catcher holds nothing; it is not a dialog
        if (dialog) return
        context.report({ node: node.openingElement, messageId: 'undeclared' })
      },
    }
  },
}
