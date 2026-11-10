/**
 * API Sản phẩm (FR-1, SP-01). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Bảng dùng khóa `['catalog', 'products', params]`. Thêm thành công thì invalidate `['catalog', 'products']`,
 * `['inventory', 'stock']` (Sản phẩm mới có dòng Tồn kho, kèm Tồn kho đầu kỳ nếu có) và `['catalog', 'categories']`
 * (số Sản phẩm của Danh mục đổi). Thêm Sản phẩm là một chứng từ (Tồn kho đầu kỳ `OPENING`) nên có
 * `Idempotency-Key` (AD-7).
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type { CreateProductRequest, Product } from '../../../shared/types/catalog';
import { categoriesQueryKey } from './categories';

export const PRODUCTS_PATH = '/api/v1/catalog/products';

export const productsQueryKey = ['catalog', 'products'] as const;
const stockQueryKey = ['inventory', 'stock'] as const;

export const IDEMPOTENCY_KEY_REUSE = '/errors/common/idempotency-key-reuse';

const ZWNJ = 0x200c;
const ZWJ = 0x200d;

/** Danh sách Sản phẩm, mới nhất trước (tìm kiếm, lọc là SP-05). */
export function fetchProducts(request: PageRequest, signal?: AbortSignal): Promise<PageResponse<Product>> {
  return api.get<PageResponse<Product>>(PRODUCTS_PATH, { query: pageRequestToQuery(request), signal });
}

/**
 * Chuẩn hóa chuỗi như backend (`TextFields`): bỏ ký tự định dạng vô hình (trường nhiều dòng giữ ZWNJ, ZWJ của emoji
 * ghép), đổi khoảng trắng Unicode loại Zs thành dấu cách thường, NFC, cắt khoảng trắng hai đầu; trường nhiều dòng
 * đổi `\r\n`, `\r` thành `\n`. Dùng trước khi kiểm độ dài và trước khi gửi.
 */
export function normalizeProductText(value: string, multiline = false): string {
  const text = value
    .replace(/\p{Cf}/gu, (c) => {
      const code = c.codePointAt(0);
      return multiline && (code === ZWNJ || code === ZWJ) ? c : '';
    })
    .replace(/\p{Zs}/gu, ' ')
    .normalize('NFC');
  return (multiline ? text.replace(/\r\n?/g, '\n') : text).trim();
}

/** Số ký tự đếm như backend: theo code point (emoji là một ký tự), sau khi chuẩn hóa. */
export function productTextLength(value: string, multiline = false): number {
  return [...normalizeProductText(value, multiline)].length;
}

/**
 * Ký tự backend từ chối (sau khi chuẩn hóa): ký tự điều khiển (trường nhiều dòng cho phép xuống dòng, tab),
 * surrogate lẻ, và ở trường một dòng cả ngắt dòng, ngắt đoạn Unicode (U+2028, U+2029).
 */
export function hasInvalidChars(value: string, multiline = false): boolean {
  return [...normalizeProductText(value, multiline)].some((c) => {
    if (/\p{Cs}/u.test(c)) return true;
    if (/[\p{Zl}\p{Zp}]/u.test(c)) return !multiline;
    return /\p{Cc}/u.test(c) && !(multiline && (c === '\n' || c === '\t'));
  });
}

/** Lỗi mà Sản phẩm có thể đã được tạo ở lần gửi trước: mất mạng, 5xx, hoặc khóa cũ gửi lại với nội dung khác. */
export function mayHaveBeenCreated(error: unknown): boolean {
  return shouldReuseIdempotencyKey(error) || (isApiError(error) && error.type === IDEMPOTENCY_KEY_REUSE);
}

/**
 * Thêm Sản phẩm. Giữ một `Idempotency-Key` qua các lần gửi lại cho tới khi thành công; sau lỗi mà
 * `shouldReuseIdempotencyKey` trả `false` (dữ liệu sai, trùng mã vạch…) thì lần gửi sau dùng khóa mới (AD-7).
 * Hook sống cùng hộp thoại thêm: mở hộp thoại mới là khóa mới. Lỗi mà Sản phẩm có thể đã được tạo
 * (`mayHaveBeenCreated`) cũng làm mới bảng, để người dùng thấy Sản phẩm đó trước khi thêm lại.
 */
export function useCreateProduct() {
  const queryClient = useQueryClient();
  const idempotencyKey = useRef<string | null>(null);
  const refreshAfterCreate = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: productsQueryKey }),
      queryClient.invalidateQueries({ queryKey: stockQueryKey }),
      queryClient.invalidateQueries({ queryKey: categoriesQueryKey }),
    ]);
  return useMutation({
    mutationFn: (request: CreateProductRequest) => {
      idempotencyKey.current ??= newIdempotencyKey();
      return api.post<Product>(PRODUCTS_PATH, request, { idempotencyKey: idempotencyKey.current });
    },
    onSuccess: async () => {
      idempotencyKey.current = null;
      await refreshAfterCreate();
    },
    onError: (error) => {
      if (mayHaveBeenCreated(error)) void refreshAfterCreate();
      if (!shouldReuseIdempotencyKey(error)) idempotencyKey.current = null;
    },
  });
}
