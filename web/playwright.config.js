import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  webServer: process.env.FOLIO_AUTH_PREVIEW === '1' ? {
    command: 'npm run preview -- --port 4873 --strictPort',
    url: 'http://127.0.0.1:4873',
  } : undefined,
  use: {
    baseURL: process.env.FOLIO_TEST_URL || 'http://127.0.0.1:8080',
    channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'chrome',
    viewport: { width: 1440, height: 1100 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  reporter: 'list',
});
