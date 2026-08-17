import { CREATION_ADD_ONS, LEAN_DELIVERY_CHOICE, type DeliveryChoice } from '../components/delivery'
import type { DeliveryCapability } from '../components/delivery'

/**
 * The last delivery choice this user made **in this workspace**, remembered in
 * localStorage only (HD-105 / §18 open question 1).
 *
 * It is a NUDGE, and the word is load-bearing:
 *
 *  • **Not a stored default.** §2.4 rejected a workspace-level default preset
 *    because it introduces a second source of truth and an inheritance question
 *    ("does changing it retro-change projects?") to save two clicks. Nothing
 *    here is ever sent to the server, nothing here is authoritative, and a
 *    project's delivery is only ever what its own create request said.
 *  • **Never overrides an explicit choice.** It is read ONCE, as the initial
 *    state of the picker, and never again — no effect re-syncs it, so it cannot
 *    reach in after the user has touched a card. A colleague on another device,
 *    or the same user in another workspace, is unaffected.
 *  • **Best-effort.** Storage may be full, blocked or hold a value written by an
 *    older build; every failure path falls back to the lean default (D4) rather
 *    than to a guess.
 *
 * Keyed per user, like `recentProjects` — accounts sharing a browser must never
 * inherit each other's preferences — and per workspace, because the shop running
 * ten Scrum projects is the case this exists for, and their consulting workspace
 * is not it.
 */

const storageKey = (userId: string, wsId: string) => `hamstrack.delivery-choice.${userId}.${wsId}`

/**
 * Anything not written by the current shape of the picker is discarded, not
 * repaired. A stale add-on name (a capability that has since left the picker, or
 * `estimation`, which §18 q7 keeps off it) would otherwise be replayed into a
 * create request as a silent, invisible choice.
 */
function parse(raw: string): DeliveryChoice | null {
  const value = JSON.parse(raw) as Partial<DeliveryChoice>
  if (value?.board !== 'KANBAN' && value?.board !== 'SCRUM') return null
  if (!Array.isArray(value.addOns)) return null
  const addOns = value.addOns.filter(
    (a): a is DeliveryCapability => CREATION_ADD_ONS.includes(a as DeliveryCapability),
  )
  return { board: value.board, addOns }
}

/** The picker's initial state: the last choice here, else the lean default. */
export function readLastDeliveryChoice(
  userId: string | undefined,
  wsId: string | undefined,
): DeliveryChoice {
  if (!userId || !wsId) return LEAN_DELIVERY_CHOICE
  try {
    const raw = localStorage.getItem(storageKey(userId, wsId))
    return (raw && parse(raw)) || LEAN_DELIVERY_CHOICE
  } catch {
    return LEAN_DELIVERY_CHOICE
  }
}

/**
 * Recorded only after the project was actually created — a picker the user
 * fiddled with and then cancelled expressed no preference.
 */
export function rememberDeliveryChoice(
  userId: string | undefined,
  wsId: string | undefined,
  choice: DeliveryChoice,
) {
  if (!userId || !wsId) return
  try {
    localStorage.setItem(storageKey(userId, wsId), JSON.stringify(choice))
  } catch { /* storage full/blocked — the nudge is best-effort */ }
}
