/**
 * Nối xử lý 401 chung của API client với router và cache (AD-9). Người giữ: Anh.
 *
 * Gặp 401 ở bất kỳ request nào (trừ chính request đăng nhập): chuyển về `/login`, nhớ trang đang
 * mở để đăng nhập xong quay lại, rồi xóa toàn bộ cache TanStack Query. Giỏ hàng ở `localStorage`
 * không bị xóa (FR-9).
 */
import type { QueryClient } from '@tanstack/react-query';
import type { DataRouter } from 'react-router';
import { configureApiClient } from '../shared/api/client';

export const LOGIN_PATH = '/login';

export function installSessionHandlers(router: DataRouter, queryClient: QueryClient): void {
  configureApiClient({
    onUnauthorized: () => {
      const { pathname, search } = router.state.location;
      if (pathname === LOGIN_PATH) {
        queryClient.clear();
        return;
      }
      // Chuyển trang trước rồi mới xóa cache, để màn đang mở không kịp gọi lại `/me`.
      void router
        .navigate(LOGIN_PATH, { replace: true, state: { from: `${pathname}${search}` } })
        .finally(() => queryClient.clear());
    },
  });
}
