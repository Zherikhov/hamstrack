import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Separate from vite.config.ts (the production build) so test-only settings
// never leak into the JAR bundle. Tailwind is intentionally omitted — the unit
// tests assert DOM/behavior, not computed styles, so we skip the CSS pipeline.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: false,
  },
})
