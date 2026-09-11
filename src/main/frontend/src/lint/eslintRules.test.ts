import { describe, it, expect } from 'vitest'
import { RuleTester } from 'eslint'
import tsParser from '@typescript-eslint/parser'
import { rules, RULE_NAMES } from '../../eslint-rules/index.js'

/**
 * **The seal on HD-300's rule set: every mechanical trap in the frontend trap
 * list is executed by exactly one mechanism, and each has been watched red.**
 *
 * The category, and why it is a category and not five tickets:
 *
 * | trap | mechanism | day-one population (measured 2026-09-11) |
 * |---|---|---|
 * | `max-w-2xs…3xl` shadowed by `--spacing-*` | `hamstrack/no-shadowed-max-width` | **0** |
 * | numeric `fontSize:` in a style object | `hamstrack/no-numeric-font-size` | `DECLARED` in `lint.debt.mjs` — **204** in 61 files on 2026-09-11 |
 * | a hand-rolled overlay with no dialog semantics | `hamstrack/overlay-has-dialog-semantics` | **3**, all fixed in HD-300 |
 * | a `var(--…)` token into a stored-hue prop | the `Hex` type + `tsc -b` | **0** |
 * | `myRole ===` in a render gate | `hamstrack/no-role-name-gate` | **0** |
 *
 * **Four of the five have population 0, which is the fact that shapes this
 * file.** A rule that can never fire on the tree has no evidence except a
 * planted instance, so every rule here carries `invalid` cases; and — the half
 * that is easier to skip — every rule carries **`valid` cases taken verbatim
 * from the legitimate neighbours measured in the tree**, because a rule that has
 * not been shown to *discriminate* has not been shown to work. Those neighbours
 * are the ten `max-w-xl` comments, the three non-overlay `position:'fixed'`
 * sites, `systemRole === 'ADMIN'` and `key === 'MEMBER'`; each of them is a real
 * line from a real file, not an invented one.
 *
 * The other half of the seal — that the rules cannot be quietly disarmed, and
 * that the debt directives can only shrink — is `./debt.test.ts`, with the
 * `.js`/`.mjs` half of the same count enforced by `lint.mjs` itself.
 */

function tester() {
  return new RuleTester({
    languageOptions: {
      parser: tsParser,
      ecmaVersion: 2023,
      sourceType: 'module',
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
  })
}

const run = (name: string, cases: Parameters<RuleTester['run']>[2]) => {
  const rule = rules[name]
  expect(rule, `${name} is not in the plugin`).toBeDefined()
  tester().run(name, rule as never, cases)
}

describe('the rule set is the trap list, and nothing has quietly left it', () => {
  it('exports exactly the four rules the config turns on', () => {
    expect(
      [...RULE_NAMES].sort(),
      'the plugin no longer exports exactly the four rules this set is defined as. A rule ' +
      'REMOVED from eslint-rules/index.js disarms it everywhere at once — debt.test.ts ' +
      'loops over RULE_NAMES, so its "every rule is at error" check shrinks with the plugin ' +
      'and stays green. A rule ADDED needs its own case in this file plus a line in the ' +
      'trap list it mechanises. Either way: edit this list deliberately, in the same diff.',
    ).toEqual([
      'no-numeric-font-size',
      'no-role-name-gate',
      'no-shadowed-max-width',
      'overlay-has-dialog-semantics',
    ])
  })

  it('gives every rule a message that names an action', () => {
    for (const name of RULE_NAMES) {
      const messages = Object.values(rules[name].meta.messages)
      expect(messages.length, `${name} declares no message`).toBeGreaterThan(0)
      for (const m of messages) {
        // A refusal that does not say what to do instead is a refusal that gets
        // suppressed. Every message in this set names a remedy and a ticket.
        expect(m, `${name}: "${m}" names no ticket`).toMatch(/HD-\d+/)
        expect(m.length, `${name}: message is a stub`).toBeGreaterThan(60)
      }
    }
  })
})

describe('no-shadowed-max-width', () => {
  it('fires on a shadowed name and stays quiet on every legitimate neighbour', () => {
    run('no-shadowed-max-width', {
      valid: [
        // The ten hits a TEXT search reports are all of THIS shape — a comment
        // warning about the trap. `AccountPage.tsx:65` and nine siblings.
        { code: '// keep this off max-w-xl: the --spacing scale shadows it\nconst a = 1' },
        { code: 'const x = <div className="max-w-full truncate" />' },      // ui.tsx, ×6
        { code: 'const x = <div className="max-w-44" />' },                  // ProjectSwitcher.tsx:110
        { code: 'const x = <div className="max-w-24" />' },                  // SearchResultsPage.tsx:491
        { code: 'const x = <div className="max-w-screen-lg" />' },           // a different namespace
        { code: 'const x = <div className="max-w-none" />' },
        { code: 'const x = <div style={{ maxWidth: 640 }} />' },             // the remedy
        { code: 'const s = "max-w-xl"\nconst x = <div title={s} />' },       // not a className
      ],
      invalid: [
        { code: 'const x = <div className="flex max-w-xl mx-auto" />', errors: [{ messageId: 'shadowed' }] },
        { code: 'const x = <div className="max-w-2xl" />', errors: [{ messageId: 'shadowed' }] },
        { code: 'const x = <div className={`p-4 ${on} max-w-md`} />', errors: [{ messageId: 'shadowed' }] },
        { code: 'const x = <div className={clsx("p-2", on && "max-w-sm")} />', errors: [{ messageId: 'shadowed' }] },
        { code: 'const x = <div className={clsx({ "max-w-3xl": wide })} />', errors: [{ messageId: 'shadowed' }] },
        // the one indirection the rules follow — a `const` in scope
        { code: 'const c = "max-w-lg"\nconst x = <div className={c} />', errors: [{ messageId: 'shadowed' }] },
        { code: 'const x = <div className="hover:max-w-xl" />', errors: [{ messageId: 'shadowed' }] },
      ],
    })
  })
})

describe('no-numeric-font-size', () => {
  it('fires on every px spelling and stays quiet on a rem one', () => {
    run('no-numeric-font-size', {
      valid: [
        { code: 'const s = { fontSize: "0.75rem" }' },                       // the remedy
        { code: 'const s = { fontSize: "1em" }' },
        { code: 'const s = { fontSize: scale }' },                           // not decidable here
        { code: 'const s = { fontSize: base * scale }' },                    // ditto: no literal, no units
        { code: 'const s = { fontSize: `${scale}rem` }' },                   // the remedy, computed
        { code: 'const s = { ...base }' },
        { code: 'const s = { lineHeight: 12 }' },
        { code: 'const o = { [fontSize]: 12 }' },                            // computed key
        { code: 'const s = { fontSize: "inherit" }' },
      ],
      invalid: [
        { code: 'const s = { fontSize: 12 }', errors: [{ messageId: 'numeric' }] },
        { code: 'const s = { fontSize: 10.5 }', errors: [{ messageId: 'numeric' }] },
        { code: 'const s = { "fontSize": 11 }', errors: [{ messageId: 'numeric' }] },
        { code: 'const s = { fontSize: "12px" }', errors: [{ messageId: 'numeric' }] },
        { code: 'const s = { fontSize: `13px` }', errors: [{ messageId: 'numeric' }] },
        // recharts' `tick` — the reason the rule is not restricted to a property
        // named `style`. 11 sites wear this spelling (measured 2026-09-11).
        { code: 'const x = <XAxis tick={{ fontSize: 11 }} />', errors: [{ messageId: 'numeric' }] },
        // the sites the ticket's own grep missed: a conditional px pair
        { code: 'const s = { fontSize: compact ? 11 : 12 }', errors: [{ messageId: 'numeric' }] },
        // …and the one the RULE missed until HD-300's fix round: arithmetic over
        // a px prop. `ui.tsx`'s Avatar, verbatim — the smallest text in the
        // product, and the only site in the tree wearing this shape.
        { code: 'const s = { width: size, fontSize: size * 0.4 }', errors: [{ messageId: 'numeric' }] },
        { code: 'const s = { fontSize: base + 2 }', errors: [{ messageId: 'numeric' }] },
        { code: 'const s = { fontSize: -0.5 + base }', errors: [{ messageId: 'numeric' }] },
        { code: "const s = { fontSize: base + '2px' }", errors: [{ messageId: 'numeric' }] },
      ],
    })
  })
})

describe('overlay-has-dialog-semantics', () => {
  // Verbatim shapes from the tree. The first two are the CORRECT overlays the
  // ticketed formulation ("no hand-rolled overlay") would have demanded a
  // suppression on; the next three are non-overlays; the last is the click-catcher.
  const commandPalette = `
    const x = (
      <div style={{ position: 'fixed', inset: 0, zIndex: 60 }} onMouseDown={close}>
        <div role="dialog" aria-modal="true" aria-label="Command palette">{body}</div>
      </div>
    )`
  const workspaceMembers = `
    const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, zIndex: 50 }
    const x = (
      <div data-modal-open="true" style={overlayStyle} onClick={onClose}>
        <div role="dialog" aria-modal="true" aria-label="Workspace members" style={panelStyle}>{body}</div>
      </div>
    )`

  it('fires on an undeclared overlay and stays quiet on the five legitimate neighbours', () => {
    run('overlay-has-dialog-semantics', {
      valid: [
        { code: commandPalette },
        { code: workspaceMembers },
        // AppShell.tsx:59 (toast), NotificationBell.tsx:108 (portal menu),
        // ui.tsx:327 (dropdown) — fixed, but not full-viewport.
        { code: "const x = <div style={{ position: 'fixed', left: 16, bottom: 16, zIndex: 80 }}>{toast}</div>" },
        { code: "const x = <div style={{ position: 'fixed', left: pos.left, top: pos.top }}>{menu}</div>" },
        { code: "const x = <div style={{ position: 'absolute', inset: 0 }}>{fill}</div>" },
        // admin/common.tsx:89 — a click-catcher holds nothing, so it is not a dialog.
        { code: "const x = <span style={{ position: 'fixed', inset: 0, zIndex: 29 }} onClick={close} />" },
        // The overlay may be the dialog itself.
        {
          code: "const x = <div role=\"dialog\" aria-modal=\"true\" aria-label=\"X\" " +
            "style={{ position: 'fixed', inset: 0 }}>{body}</div>",
        },
        // Deep descendant, behind a conditional — the walk goes through `{cond && …}`.
        {
          code: "const x = <div style={{ position: 'fixed', inset: 0 }}><section>{open && " +
            '<div role="dialog" aria-modal="true" aria-labelledby="t">{body}</div>}</section></div>',
        },
      ],
      invalid: [
        // AboutModal / CreateIssueModal / CreateProjectModal, as they were before HD-300:
        // the style behind a `const`, the panel a plain div.
        {
          code: `
            const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, zIndex: 50 }
            const x = (
              <div data-modal-open="true" style={overlayStyle} onClick={onClose}>
                <div style={panelStyle} onClick={stop}>{body}</div>
              </div>
            )`,
          errors: [{ messageId: 'undeclared' }],
        },
        // inline, no semantics at all
        {
          code: "const x = <div style={{ position: 'fixed', inset: 0 }}><div>{body}</div></div>",
          errors: [{ messageId: 'undeclared' }],
        },
        // the four-sided spelling of a full inset
        {
          code: "const x = <div style={{ position: 'fixed', top: 0, right: 0, bottom: 0, left: 0 }}>{body}</div>",
          errors: [{ messageId: 'undeclared' }],
        },
        // role but no accessible name — the half-done version, which is the one
        // that looks finished in a diff
        {
          code: "const x = <div style={{ position: 'fixed', inset: 0 }}>" +
            '<div role="dialog" aria-modal="true">{body}</div></div>',
          errors: [{ messageId: 'undeclared' }],
        },
        // name but no aria-modal
        {
          code: "const x = <div style={{ position: 'fixed', inset: 0 }}>" +
            '<div role="dialog" aria-label="X">{body}</div></div>',
          errors: [{ messageId: 'undeclared' }],
        },
      ],
    })
  })
})

describe('no-role-name-gate', () => {
  it('fires on a role-name gate and stays quiet on the ten legitimate neighbours', () => {
    run('no-role-name-gate', {
      valid: [
        // Six sites, a DIFFERENT axis: the instance admin flag is in no PermissionSet.
        { code: "const x = user?.systemRole === 'ADMIN'" },
        { code: "const x = u.systemRole === 'ADMIN' ? a : b" },
        // Three sites: the sanctioned built-in Contributor lookup (roles.tsx:531).
        { code: "const x = roles.find(r => r.builtIn && r.key === 'MEMBER')" },
        // WorkspacesPage's roleLabel — display only. It PASSES rather than being
        // excused: it switches on its parameter, not on a `.myRole` read.
        {
          code: "const roleLabel = (role: string) => { switch (role) { case 'OWNER': return 'Owner' } }\n" +
            'const x = roleLabel(ws.myRole)',
        },
        { code: 'const x = <span>{ws.myRole}</span>' },                       // rendered, not tested
        { code: "const p = { myRole: 'OWNER' }" },                            // an object KEY
        { code: 'const x = can(p.myPermissions, "project.settings.manage")' }, // the remedy
      ],
      invalid: [
        { code: "const x = project.myRole === 'MANAGER'", errors: [{ messageId: 'gate' }] },
        { code: "const x = ws.myRole !== 'MEMBER'", errors: [{ messageId: 'gate' }] },
        { code: "const x = p?.myRole === 'MANAGER'", errors: [{ messageId: 'gate' }] },
        { code: "const x = p['myRole'] === 'MANAGER'", errors: [{ messageId: 'gate' }] },
        { code: 'const x = p.myRole ? a : b', errors: [{ messageId: 'gate' }] },
        { code: 'const x = p.myRole && canEdit', errors: [{ messageId: 'gate' }] },
        { code: 'if (!p.myRole) { hide() }', errors: [{ messageId: 'gate' }] },
        { code: "switch (p.myRole) { case 'OWNER': break }", errors: [{ messageId: 'gate' }] },
        // the same gate, spelled wider
        { code: "const x = ['OWNER','ADMIN'].includes(ws.myRole)", errors: [{ messageId: 'gate' }] },
      ],
    })
  })
})
