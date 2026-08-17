import { useIsProjectCurator } from '../components/sprints'
import type { Project, ProjectDelivery } from '../types'

/**
 * "Does this project do X?" — the SINGLE place any surface may ask (HD-102 §12).
 *
 * Before this hook every surface invented its own rule and inferred the answer
 * from whether the data happened to exist yet (`openSprints.length > 0`,
 * `sprintOptions.length > 0`, `versionOptions.length > 0`). That class of check
 * produced the production dead end this ticket fixes: the Backlog hid BOTH
 * sprint-creation entry points until a sprint existed, so a Kanban project could
 * never create its first one. Rule C (§5.3) removes the whole class — a
 * capability is DECLARED on the project, never inferred.
 *
 * Two rules govern every consumer:
 *
 *  • **Rule A (§5.1)** — capabilities are presentation, never API. `POST /sprints`
 *    succeeds on a Kanban project and `fixVersionIds` applies with releases off.
 *    Nothing here is a security control; a hidden control is not a protected one.
 *  • **Rule B (§5.2)** — controls are gated, values never are. Gate the picker,
 *    the input, the "create" button; render an existing sprint / version chip /
 *    point value whenever the issue carries one, read-only, with a tooltip that
 *    names the reason. A user must never lose sight of their own data because a
 *    toggle was flipped.
 */

/**
 * The preset is DERIVED server-side and exists in exactly ONE place (risk 5,
 * §17). A response without `delivery` carries no preset and this client will not
 * re-derive one — nothing reads the label in that state.
 */
const NO_PRESET = 'CUSTOM' as const

/**
 * A project response that predates HD-102, or a hand-built test fixture: the §7
 * upgrade rule, *an upgrade never takes away a capability a project already has
 * access to*. Every such project could reach the Releases page and the points
 * input, so both stay on; only the board follows the deprecated `boardMode`
 * mirror, whose default has always been Kanban.
 */
const LEGACY_DELIVERY: ProjectDelivery = {
  board: 'KANBAN', releases: true, estimation: true, preset: NO_PRESET,
}

/**
 * Not loaded yet (or the project query failed): we do not KNOW how this team
 * delivers, so no path-specific control is drawn.
 *
 * The direction is deliberate. Guessing "on" would flash a sprint picker or a
 * points input into a form and then pull it out from under the pointer a moment
 * later; guessing "off" only delays a control by one cached round trip — and on
 * every project page the nav rail has already populated this exact cache entry,
 * so in practice there is nothing to delay. Rule B is unaffected either way: a
 * VALUE the issue carries renders whatever this says.
 */
const UNKNOWN_DELIVERY: ProjectDelivery = {
  board: 'KANBAN', releases: false, estimation: false, preset: NO_PRESET,
}

/**
 * The declared capabilities of a loaded project — pure, so surfaces that already
 * hold a `Project` (the create-issue dialog reads the workspace's project list;
 * the nav rail already caches the project) ask without spending a request.
 */
export function deliveryOf(project: Project | null | undefined): ProjectDelivery {
  if (!project) return UNKNOWN_DELIVERY
  if (project.delivery) return project.delivery
  // The deprecated top-level mirror is consulted HERE ONLY, and only when
  // `delivery` is absent — no surface reads both.
  return { ...LEGACY_DELIVERY, board: project.boardMode ?? LEGACY_DELIVERY.board }
}

export interface ProjectDeliveryState extends ProjectDelivery {
  /** `board === 'SCRUM'` — sprints as a planning concept are on. */
  iterations: boolean
  /**
   * Project MANAGER *or* workspace OWNER/ADMIN, mirroring
   * `ScopeResolver.requireProjectCurator`. Only meaningful when the hook was
   * asked for it (`needsRole`), since the workspace half costs a second request.
   */
  isCurator: boolean
  project: Project | undefined
}

/**
 * Reads the already-cached `['project', wsId, projectId]` entry (the nav rail and
 * both settings areas fetch it on every project page), so this costs no request.
 *
 * `needsRole` additionally resolves the workspace role — pass it only where a
 * curator-only control is actually rendered, so a plain member's board issues no
 * extra request.
 */
export function useProjectDelivery(
  wsId: string | undefined,
  projectId: string | undefined,
  options?: { needsRole?: boolean },
): ProjectDeliveryState {
  const { project, isCurator } = useIsProjectCurator(wsId, projectId, options?.needsRole ?? false)
  const delivery = deliveryOf(project)
  return { ...delivery, iterations: delivery.board === 'SCRUM', isCurator, project }
}
