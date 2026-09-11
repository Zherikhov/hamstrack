/**
 * **A role name cannot express a custom role, so it cannot carry a gate.**
 *
 * `myRole` is `DISPLAY ONLY` on both `Workspace` and `Project` (`types.ts`,
 * HD-123 §5.3). Authorization is a `PermissionSet`: the server resolves it once
 * and ships it as `myPermissions`, and `usePermissions` is the only reader a
 * component should have. The rail and the command palette each kept their own
 * copy of `myRole === 'MANAGER'` and both were wrong — a workspace admin with
 * the `project.curate.all` bypass still reads `myRole: 'VIEWER'` (HD-98,
 * HD-116).
 *
 * **Scope, measured 2026-09-11 — this rule is deliberately narrow.**
 * A naive role-literal search hits **ten legitimate production sites**:
 * six `user?.systemRole === 'ADMIN'` (a *different axis* — the instance admin
 * flag, which is in no `PermissionSet`), and three `r.builtIn && r.key ===
 * 'MEMBER'` fallback lookups, which `components/roles.tsx` documents as the
 * sanctioned way to find the built-in Contributor. Both are out of scope by
 * name, so this rule ships with **no allow-list and no suppression anywhere**.
 * The one legitimate reader that is in scope — `WorkspacesPage`'s `roleLabel` —
 * *passes* rather than being excused: it takes the role as an argument and
 * switches on that parameter, so there is no `.myRole` in a test position
 * anywhere in it. An exemption that is not needed is an exemption that hides
 * the next real one.
 */

/** `a.myRole` / `a?.myRole` / `a['myRole']` — never the object key in `{ myRole: … }`. */
function isMyRoleRead(node) {
  if (!node || node.type !== 'MemberExpression') return false
  const p = node.property
  if (!node.computed) return p.type === 'Identifier' && p.name === 'myRole'
  return p.type === 'Literal' && p.value === 'myRole'
}

/** Peel the parents that do not change "is this value being tested?". */
function effectiveNode(node) {
  let n = node
  while (
    n.parent &&
    (n.parent.type === 'TSNonNullExpression' ||
      n.parent.type === 'TSAsExpression' ||
      n.parent.type === 'ChainExpression' ||
      (n.parent.type === 'UnaryExpression' && n.parent.operator === '!'))
  ) {
    n = n.parent
  }
  return n
}

export default {
  meta: {
    type: 'problem',
    docs: { description: 'forbid deriving a UI gate from the display-only myRole field' },
    schema: [],
    messages: {
      gate:
        'myRole is DISPLAY ONLY (types.ts, HD-123 §5.3) — a role name cannot express a ' +
        'custom role, and a workspace admin acting through project.curate.all still reads ' +
        'VIEWER. Derive the gate from myPermissions via usePermissions (HD-98, HD-116).',
    },
  },
  create(context) {
    return {
      MemberExpression(node) {
        if (!isMyRoleRead(node)) return
        const self = effectiveNode(node)
        const parent = self.parent
        if (!parent) return
        let offending = false
        if (parent.type === 'BinaryExpression' && ['===', '!==', '==', '!='].includes(parent.operator)) {
          offending = true
        } else if (parent.type === 'SwitchStatement' && parent.discriminant === self) {
          offending = true
        } else if (
          (parent.type === 'ConditionalExpression' || parent.type === 'IfStatement') &&
          parent.test === self
        ) {
          offending = true
        } else if (parent.type === 'LogicalExpression') {
          offending = true
        } else if (parent.type === 'UnaryExpression' && parent.operator === '!') {
          offending = true
        } else if (
          parent.type === 'CallExpression' &&
          parent.callee.type === 'MemberExpression' &&
          parent.callee.property.type === 'Identifier' &&
          ['includes', 'startsWith', 'indexOf'].includes(parent.callee.property.name) &&
          parent.arguments.includes(self)
        ) {
          // `['OWNER','ADMIN'].includes(ws.myRole)` — the same gate, spelled wider.
          offending = true
        }
        if (offending) context.report({ node, messageId: 'gate' })
      },
    }
  },
}
