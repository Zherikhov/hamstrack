import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGetWorkspaceStorage, apiGetWorkspaceStorageByProject } from '../api'
import type { WorkspaceStorageSummary } from '../types'

/**
 * The workspace storage reads, and the one cache entry both surfaces share
 * (HD-191 §12.1, §12.4).
 *
 * Two surfaces ask the same question — the issue panel, beside the upload
 * control, and Workspace settings → Storage — and they ask it under one key so a
 * user who opens an issue from the settings page pays for one request, not two.
 *
 * ## Why the two reads are nested under one prefix
 *
 * The summary and the breakdown are different value shapes and therefore
 * different entries ({@link workspaceStorageKey} / {@link
 * workspaceStorageProjectsKey}), but they describe the *same* bytes: an upload
 * or a delete moves both. So they sit under one prefix and
 * {@link useInvalidateWorkspaceStorage} refreshes the pair. The breakdown is on
 * the reports budget, and that costs nothing here — an invalidation only refetches
 * entries something is actually rendering.
 *
 * ## `staleTime`, and why it is not zero
 *
 * The counter is exact and cluster-wide, but it is not a live figure: a
 * colleague's upload in another project moves it, and nothing tells this tab.
 * A minute is the same interval the report endpoints cache at, and it is bounded
 * on the side that matters — the refusal a user actually meets is rendered from
 * the **409's own body**, never from this cache, precisely because this cache may
 * be a minute behind (§12.1).
 */

/** Prefix covering every storage read of one workspace — the invalidation unit. */
export function workspaceStorageKeyPrefix(wsId: string | undefined) {
  return ['workspaceStorage', wsId] as const
}

/** The summary; value shape `WorkspaceStorageSummary`. */
export function workspaceStorageKey(wsId: string | undefined) {
  return ['workspaceStorage', wsId, 'summary'] as const
}

/** The per-project breakdown; value shape `WorkspaceStorageByProject`. */
export function workspaceStorageProjectsKey(wsId: string | undefined) {
  return ['workspaceStorage', wsId, 'projects'] as const
}

const STALE_TIME = 60 * 1000

/**
 * The summary, for any member.
 *
 * `enabled` exists for the caller that has no workspace yet, not for a
 * permission: there is no permission to have. A failure is not surfaced as an
 * error anywhere — a storage figure is context, and a panel that replaced the
 * upload control with a fetch error would have made a read failure look like a
 * refusal.
 */
export function useWorkspaceStorage(wsId: string | undefined, enabled = true) {
  return useQuery<WorkspaceStorageSummary>({
    queryKey: workspaceStorageKey(wsId),
    queryFn: () => apiGetWorkspaceStorage(wsId!),
    enabled: !!wsId && enabled,
    staleTime: STALE_TIME,
  })
}

/**
 * The breakdown, for a holder of `workspace.edit`.
 *
 * Held behind `enabled` until the permission answer is in, the way
 * `useProjectAccess` is: firing it for anyone else is a guaranteed 403, and
 * spending a budgeted request on a refusal helps nobody.
 */
export function useWorkspaceStorageByProject(wsId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: workspaceStorageProjectsKey(wsId),
    queryFn: () => apiGetWorkspaceStorageByProject(wsId!),
    enabled: !!wsId && enabled,
    staleTime: STALE_TIME,
  })
}

/**
 * Refetch both reads — what an upload, a delete and a quota refusal each call.
 *
 * A refusal invalidates too, and that is not redundant: being refused is the
 * strongest evidence available that the cached total is out of date.
 */
export function useInvalidateWorkspaceStorage(wsId: string | undefined) {
  const qc = useQueryClient()
  return useCallback(
    () => qc.invalidateQueries({ queryKey: workspaceStorageKeyPrefix(wsId) }),
    [qc, wsId],
  )
}
