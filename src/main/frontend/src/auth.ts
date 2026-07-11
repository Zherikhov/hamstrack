import { create } from 'zustand'
import { queryClient } from './queryClient'
import type { User } from './types'

interface AuthState {
  user: User | null
  accessToken: string | null
  initialized: boolean
  setToken: (token: string) => void
  setUser: (user: User) => void
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

  clear: () => {
    sessionStorage.removeItem('accessToken')
    // Cached queries belong to the signed-out user — the next account must not see them
    queryClient.clear()
    set({ user: null, accessToken: null })
  },

  setInitialized: () => set({ initialized: true }),
}))
