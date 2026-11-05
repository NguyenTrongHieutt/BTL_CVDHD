/// <reference types="vitest/config" />
import type { ServerResponse } from 'node:http';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * Khi ai-service tắt, trả cùng Problem Details với nginx (`deploy/nginx.conf`, AD-1) thay cho
 * 502 text của Vite, để frontend xử lý giống hệt bản chạy bằng Docker.
 */
const AI_UNAVAILABLE = JSON.stringify({
  type: '/errors/ai/unavailable',
  title: 'Dịch vụ AI chưa sẵn sàng',
  status: 503,
  detail: 'Tạm thời không dùng được Trợ lý AI và Tìm kiếm ngữ nghĩa. Màn hình bán hàng tự chuyển sang Tìm kiếm thường.',
});

/** Đổi được khi cổng mặc định đang bận, ví dụ `BACKEND_URL=http://localhost:18080 npm run dev`. */
const BACKEND_URL = process.env.BACKEND_URL ?? 'http://localhost:8080';
const AI_SERVICE_URL = process.env.AI_SERVICE_URL ?? 'http://localhost:8000';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Giống nginx (AD-1): /api/ → backend, /ai/ → ai-service. Cùng origin nên cookie HttpOnly đi kèm.
    proxy: {
      '/api/': { target: BACKEND_URL },
      '/ai/': {
        target: AI_SERVICE_URL,
        configure: (proxy) => {
          proxy.on('error', (_error, _request, response) => {
            if (!('writeHead' in response)) return;
            const res = response as ServerResponse;
            if (res.headersSent || res.writableEnded) return;
            res.writeHead(503, { 'Content-Type': 'application/problem+json; charset=utf-8' }).end(AI_UNAVAILABLE);
          });
        },
      },
    },
  },
  build: {
    // Riêng chunk antd đã ~650 kB (gzip ~215 kB); mã của nhóm vẫn bị cảnh báo nếu vượt mức này.
    chunkSizeWarningLimit: 800,
    rolldownOptions: {
      output: {
        // Tách thư viện ra chunk riêng để trình duyệt cache lâu hơn mã của nhóm.
        codeSplitting: {
          groups: [
            { name: 'vendor-react', test: /node_modules[\\/](react|react-dom|react-router|scheduler|@tanstack)[\\/]/ },
            { name: 'vendor-antd', test: /node_modules[\\/](antd|@ant-design|@rc-component|dayjs)[\\/]/ },
          ],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    unstubGlobals: true,
  },
});
