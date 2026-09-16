import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    restoreMocks: true,
    projects: [
      { extends: true, test: { name: 'unit', environment: 'node', include: ['tests/unit/**/*.test.ts'], exclude: ['tests/unit/monaco-theme.test.ts'] } },
      { extends: true, test: { name: 'components', environment: './tests/unit/support/component-environment.ts', include: ['tests/unit/monaco-theme.test.ts'] } },
    ],
  },
})
