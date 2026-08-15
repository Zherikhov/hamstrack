// Vitest global setup: jest-dom matchers (toBeInTheDocument, toHaveValue, …)
// plus a DOM cleanup between tests so leftover React trees can't cross-talk.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => {
  cleanup()
})
