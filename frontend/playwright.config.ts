/**
 * Playwright: smoke test theo Kịch bản demo (AD-21, SM-7). Chưa có test ở GĐ0.
 *
 * Mặc định chạy với `npm run dev` (http://localhost:5173, cần backend và ai-service đang chạy).
 * Chạy với bản Docker: `E2E_BASE_URL=http://localhost npm run e2e`.
 * Lần đầu cài trình duyệt: `npx playwright install chromium`.
 */
import { defineConfig, devices } from '@playwright/test';

const externalBaseUrl = process.env.E2E_BASE_URL;
const baseURL = externalBaseUrl ?? 'http://localhost:5173';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    locale: 'vi-VN',
    timezoneId: 'Asia/Ho_Chi_Minh',
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'desktop-chromium', use: { ...devices['Desktop Chrome'] } },
    // Màn của Chủ cửa hàng phải dùng được từ 360px (Consistency Conventions, dòng "Giao diện").
    { name: 'mobile-chromium', use: { ...devices['Pixel 7'], viewport: { width: 360, height: 780 } } },
  ],
  webServer: externalBaseUrl
    ? undefined
    : {
        command: 'npm run dev',
        url: baseURL,
        reuseExistingServer: true,
        timeout: 60_000,
      },
});
