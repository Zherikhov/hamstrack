import { createContext, useContext } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { AdminApi } from '../../api'
import type { AdminScopeTag } from '../../types'

/**
 * The admin catalog/set pages are scope-agnostic: they read their API and query
 * namespace from this context, so the same components drive the system console
 * (/admin), a workspace console and a project's Settings. The server enforces the
 * scope; here we only swap the base path and the React Query key prefix.
 */
export type AdminScopeKind = 'global' | 'workspace' | 'project'

export interface AdminScopeValue {
  api: AdminApi
  scope: AdminScopeKind
  eyebrow: string                          // PageHeader kicker
  keyPrefix: readonly unknown[]            // React Query key namespace for this scope
  extraInvalidate?: readonly unknown[][]   // also invalidated on mutations (e.g. projectConfig)
}

const Ctx = createContext<AdminScopeValue | null>(null)

export function AdminApiProvider({ value, children }: { value: AdminScopeValue; children: ReactNode }) {
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAdminApi(): AdminScopeValue {
  const v = useContext(Ctx)
  if (!v) throw new Error('useAdminApi must be used within an AdminApiProvider')
  return v
}

/** The row scope this console owns (and may edit). Other scopes are inherited/read-only. */
export function ownScopeTag(scope: AdminScopeKind): AdminScopeTag {
  return scope === 'global' ? 'GLOBAL' : scope === 'workspace' ? 'WORKSPACE' : 'PROJECT'
}

/** Invalidate this scope's queries (plus any extra keys like projectConfig) after a mutation. */
export function useAdminInvalidate() {
  const { keyPrefix, extraInvalidate } = useAdminApi()
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: keyPrefix as unknown[] })
    extraInvalidate?.forEach(k => qc.invalidateQueries({ queryKey: k as unknown[] }))
  }
}
