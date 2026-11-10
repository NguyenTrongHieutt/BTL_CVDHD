/**
 * API Thông tin Cửa hàng (FR-48, TK-05). Người giữ: Hiếu — features/platform (AD-20). Chỉ Chủ cửa hàng.
 *
 * Khóa `['platform', 'store']`. Lưu thành công thì đặt dữ liệu backend trả về vào khóa này và invalidate
 * `['platform', 'me']`, để tên Cửa hàng ở thanh trên cùng (`Me.storeName`) đổi theo mà không cần đăng nhập lại.
 * `PUT` ghi đè toàn bộ nên không có `Idempotency-Key`.
 *
 * Các hàm chuẩn hóa chữ ở đây làm giống backend (`platform/internal/store/StoreText.java`, `StoreService.java`), để
 * lỗi được báo ngay dưới ô trước khi gửi.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../shared/api/client';
import { meQueryKey } from '../../../shared/api/session';
import type { StoreInfo, UpdateStoreRequest } from '../../../shared/types/platform';

export const STORE_PATH = '/api/v1/platform/store';

export const storeQueryKey = ['platform', 'store'] as const;

export function fetchStore(signal?: AbortSignal): Promise<StoreInfo> {
  return api.get<StoreInfo>(STORE_PATH, { signal });
}

export function useStoreInfo() {
  return useQuery({ queryKey: storeQueryKey, queryFn: ({ signal }) => fetchStore(signal) });
}

export function useUpdateStore() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateStoreRequest) => api.put<StoreInfo>(STORE_PATH, request),
    onSuccess: (store) => {
      queryClient.setQueryData(storeQueryKey, store);
      return queryClient.invalidateQueries({ queryKey: meQueryKey });
    },
  });
}

/**
 * Khoảng trắng hai đầu mà backend cắt (`Character.isWhitespace`/`isSpaceChar` của Java): như `trim()` của JavaScript
 * cộng thêm U+001C–U+001F (dấu phân cách file, nhóm, bản ghi, đơn vị).
 */
function isEdgeSpace(c: string): boolean {
  const code = c.codePointAt(0) ?? 0;
  return (code >= 0x1c && code <= 0x1f) || c.trim() === '';
}

function trimLikeBackend(text: string): string {
  const chars = [...text];
  let start = 0;
  let end = chars.length;
  while (start < end && isEdgeSpace(chars[start])) start++;
  while (end > start && isEdgeSpace(chars[end - 1])) end--;
  return chars.slice(start, end).join('');
}

/**
 * Chuẩn hóa trường văn bản một dòng như backend: bỏ ký tự định dạng vô hình, khoảng trắng Unicode thành dấu cách,
 * NFC, cắt hai đầu.
 */
export function normalizeStoreText(value: string | null | undefined): string {
  return trimLikeBackend(
    (value ?? '')
      .replace(/\p{Cf}/gu, '')
      .replace(/\p{Zs}/gu, ' ')
      .normalize('NFC'),
  );
}

/** Số ký tự đếm như backend: theo code point (emoji là một ký tự), sau khi chuẩn hóa. */
export function storeTextLength(value: string | null | undefined): number {
  return [...normalizeStoreText(value)].length;
}

/** Ký tự backend từ chối sau khi chuẩn hóa: ký tự điều khiển, surrogate lẻ, ngắt dòng, ngắt đoạn Unicode. */
export function hasInvalidStoreChars(value: string | null | undefined): boolean {
  return [...normalizeStoreText(value)].some((c) => /[\p{Cc}\p{Cs}\p{Zl}\p{Zp}]/u.test(c));
}

/**
 * Tên chủ tài khoản khi đang gõ: bỏ dấu tiếng Việt (`đ` thành `D`), đổi sang chữ in hoa. Ký tự khác (chữ số, dấu
 * câu) giữ nguyên để luật kiểm báo lỗi.
 */
export function toBankAccountName(value: string | undefined): string | undefined {
  if (value === undefined) return value;
  return value
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/[đĐ]/g, 'D')
    .toUpperCase();
}

/** Tên chủ tài khoản khi gửi: như `toBankAccountName`, chuẩn hóa, gộp khoảng trắng liền nhau (backend cũng gộp). */
export function normalizeBankAccountName(value: string | null | undefined): string {
  return normalizeStoreText(toBankAccountName(value ?? '')).replace(/ {2,}/g, ' ');
}
