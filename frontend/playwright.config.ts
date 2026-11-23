import { defineConfig } from '@playwright/test';
import path from 'node:path';
const output = process.env.REVIEW_DIR;
if (!output || !process.env.REVIEW_BASE_URL || !process.env.REVIEW_API_URL) {
  throw new Error(
    'Launch through node scripts/review/run.mjs so browser tests use an isolated database.',
  );
}
export default defineConfig({
  testDir: './e2e',
  testMatch: process.env.REVIEW_DEMO === '1' ? '**/demo.spec.ts' : '**/review.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: process.env.REVIEW_DEMO === '1' ? 900_000 : 90_000,
  globalTimeout: 1_200_000,
  forbidOnly: !!process.env.CI,
  outputDir: path.join(output, 'browser'),
  reporter: [
    ['list'],
    ['json', { outputFile: path.join(output, 'browser-results.json') }],
    ['html', { outputFolder: path.join(output, 'browser-report'), open: 'never' }],
  ],
  use: {
    baseURL: process.env.REVIEW_BASE_URL,
    actionTimeout: 12_000,
    navigationTimeout: 25_000,
    viewport: { width: 1440, height: 960 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined,
      args: [
        '--no-sandbox',
        '--use-gl=angle',
        '--use-angle=swiftshader',
        '--enable-unsafe-swiftshader',
        '--autoplay-policy=no-user-gesture-required',
      ],
    },
  },
});
