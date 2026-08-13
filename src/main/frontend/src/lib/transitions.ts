import type { Status, TransitionRule } from '../types'

/**
 * Workflow transition check, mirroring the backend semantics (ProjectConfigService):
 * a status with no source-specific rules is open (any move allowed); once it has
 * at least one rule, only its listed targets — plus wildcard ("from any", i.e.
 * `fromStatusId === null`) targets — are allowed. A no-op move (same status) is
 * never "allowed" here.
 *
 * Shared by the board's drag-drop drop targets (HD-55) and the issue panel's
 * inline status editor (HD-61) so both offer exactly the same set of moves.
 */
export function isMoveAllowed(from: Status, toStatusId: string, transitions: TransitionRule[]): boolean {
  if (from.id === toStatusId) return false
  const restricted = transitions.some(t => t.fromStatusId === from.id)
  if (!restricted) return true
  return transitions.some(t =>
    (t.fromStatusId === from.id || t.fromStatusId === null) && t.toStatusId === toStatusId)
}
