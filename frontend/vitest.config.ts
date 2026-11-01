import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Unit/component tests run in jsdom. Browser speech APIs do not exist there; the tests install fakes
// (src/test/speechMocks.ts), so these tests prove the page's behaviour, not a real microphone or voice.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },
})
