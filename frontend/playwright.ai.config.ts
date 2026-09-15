import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  testMatch: ['**/ai-e2e/**/*.spec.ts', '**/e2e/**/*.spec.ts'],
  workers: 1, fullyParallel: false, timeout: 60000,
  expect: { timeout: 15000 }, reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5174', actionTimeout: 10000, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: { command: 'node tests/fixtures/ai-stack.mjs', url: 'http://127.0.0.1:5174', reuseExistingServer: false, timeout: 150000 },
})
