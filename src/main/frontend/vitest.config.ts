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
    // The CSS pipeline stays off — with ONE file excused. `css: false` makes a
    // stylesheet import resolve to an empty string even through `?raw`, and
    // `colour.test.ts` reads `index.css` as TEXT to prove that the design tokens
    // `colour.ts` falls back on when no stylesheet is attached still agree with
    // the ones the app ships. No test imports `index.css` as a stylesheet, so
    // nothing here starts asserting on computed styles.
    css: { include: [/index\.css/] },
  },
})
