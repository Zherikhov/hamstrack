import { create } from 'zustand'
import { queryClient } from './queryClient'
import type { User } from './types'

interface AuthState {
  user: User | null
  accessToken: string | null
  initialized: boolean
  setToken: (token: string) => void
  setUser: (user: User) => void
  // Optimistically clear the onboarding flag once the user creates/joins/skips,
  // so the global onboarding redirect (RequireAuth) lets them through
  markOnboarded: () => void
  clear: () => void
  setInitialized: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  accessToken: sessionStorage.getItem('accessToken'),
  initialized: false,

  setToken: (token) => {
    sessionStorage.setItem('accessToken', token)
    set({ accessToken: token })
  },

  setUser: (user) => set({ user }),

  markOnboarded: () => set((s) => (s.user ? { user: { ...s.user, needsOnboarding: false } } : s)),

  clear: () => {
    sessionStorage.removeItem('accessToken')
    // Cached queries belong to the signed-out user — the next account must not see them
    queryClient.clear()
    set({ user: null, accessToken: null })
  },

  setInitialized: () => set({ initialized: true }),
}))
