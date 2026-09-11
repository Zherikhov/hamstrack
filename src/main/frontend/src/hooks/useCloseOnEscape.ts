import { useEffect, useRef } from 'react'

/**
 * **Escape dismisses a dialog — the keyboard half of `role="dialog"` (HD-300).**
 *
 * `role="dialog"` + `aria-modal="true"` is a *claim*: it tells assistive
 * technology that this region owns the interaction and that the usual dismissal
 * is available. HD-300 put those attributes on `AboutModal`, `CreateIssueModal`
 * and `CreateProjectModal` — and the ui_qa gate measured, in the signed system
 * Chrome, that none of the three closed on Escape. A claim with no behaviour
 * behind it is the defect class this ticket is about, so the mechanism ships in
 * the same change as the claim.
 *
 * **One implementation, not three.** `ShortcutsHelp` and `CommandPalette` each
 * grew their own copy of this listener; a fourth, fifth and sixth copy is the
 * duplication this repo treats as a defect. Those two are deliberately NOT
 * migrated here (they combine Escape with `?` and with a selection reset, and
 * they were outside HD-300's diff) — the follow-up that gives every dialog the
 * full keyboard contract adopts this hook and deletes the copies.
 *
 * **Three members of the category do not close on Escape at all**, and naming
 * only the two migratable copies above would read as if the rest already did.
 * Measured behaviourally by the ui_qa gate on 2026-09-11 over the **8**
 * production `role="dialog"` elements: five dismiss (the three that call this
 * hook, plus those two copies), and these do not —
 *
 *  * `WorkspaceMembersModal`, which hand-rolls its own overlay;
 *  * the shared `Modal` in `pages/admin/common.tsx`, and therefore **every**
 *    dialog that renders through it — 17 call sites on 2026-09-11, from
 *    `SaveFilterDialog` to the admin set editors and the role dialogs.
 *
 * All three carry `role="dialog"` + `aria-modal`, so all three make the claim
 * this hook exists to honour. Closing them is the follow-up filed for Escape
 * across the dialog category (not only for the focus trap below); it is a known
 * gap stated here rather than an omission, and the count above is dated because
 * it is a measurement and not a standing property.
 *
 * **What this is not.** It is not a focus trap, and there is none anywhere in
 * this repo: Tab still leaves every dialog, and closing returns focus to
 * `<body>` rather than to the trigger. That is a real gap over the whole dialog
 * category, measured by the same gate, and it is a follow-up rather than a
 * silent omission — this hook's name says exactly what it holds.
 *
 * **`defaultPrevented` is the whole compatibility story.** React attaches its
 * handlers at the root container, so a nested widget that owns Escape while it
 * is open — the `Select` popup in `ui.tsx`, `LabelPicker`, `VersionPicker`,
 * every inline editor in `IssueDetail` — has already called `preventDefault()`
 * by the time the event reaches `window`. Checking the flag here is what makes
 * "Escape closes the listbox, Escape again closes the dialog" work, and it is
 * the same clause `ShortcutsHelp` and `shouldHandleGlobalKey` (clause 1) use.
 *
 * Escape is otherwise unconditional: it fires from inside a text input too,
 * because dismissal is what Escape means in a dialog, and the editable-surface
 * gate in `lib/keyboard.ts` exists for *printable* single-key shortcuts.
 *
 * @param onClose called once per Escape; may be a fresh closure on every render
 * @param enabled pass `false` to detach without breaking the hook order
 */
export function useCloseOnEscape(onClose: () => void, enabled = true): void {
  // The listener is registered once and reads the latest callback through a ref,
  // so an inline `onClose={() => setOpen(false)}` — how all three modals are
  // called — does not add and remove a window listener on every render.
  const latest = useRef(onClose)
  useEffect(() => { latest.current = onClose })

  useEffect(() => {
    if (!enabled) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.defaultPrevented) return
      if (e.key !== 'Escape') return
      e.preventDefault()
      latest.current()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [enabled])
}
