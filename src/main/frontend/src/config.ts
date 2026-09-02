import { create } from 'zustand'
import type { PublicConfig } from './api'

interface ConfigState {
  config: PublicConfig
  setConfig: (config: PublicConfig) => void
}

// Fail-safe defaults: if /api/meta is unreachable we keep the landing and the
// terms checkbox enabled — the backend enforces terms acceptance regardless
export const useConfigStore = create<ConfigState>((set) => ({
  config: {
    publicLandingEnabled: true,
    termsAcceptanceRequired: true,
    publicSignupEnabled: true,
    // Unset is the honest default: with no address the Account page falls back
    // to "handled by its administrator" rather than inventing a mailbox.
    privacyContactEmail: '',
    version: '',
  },
  setConfig: (config) => set({ config }),
}))
