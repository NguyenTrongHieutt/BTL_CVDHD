/**
 * TanStack Query cho cả ứng dụng. Người giữ: Anh.
 *
 * Query key có dạng `[<module>, <entity>, params]` (AD-20). Mỗi mutation tự liệt kê các key cần
 * invalidate, ví dụ bán hàng xong invalidate `['inventory', 'stock']`.
 */
import { QueryClient } from '@tanstack/react-query';
import { isRetryableError } from '../shared/api/client';

export function createAppQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // Chỉ thử lại khi mất mạng hoặc lỗi 5xx; 4xx (gồm 401) không thử lại.
        retry: (failureCount, error) => failureCount < 2 && isRetryableError(error),
      },
      mutations: {
        // POST chứng từ có Idempotency-Key; việc gửi lại do màn hình quyết định (AD-7).
        retry: false,
      },
    },
  });
}
