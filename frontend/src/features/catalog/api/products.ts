/**
 * API Sản phẩm (FR-1, SP-01; FR-3, SP-03; FR-5, SP-05). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Bảng dùng khóa `['catalog', 'products', params]`, `params` gồm bộ lọc và trang. Thêm thành công thì invalidate `['catalog', 'products']`,
 * `['inventory', 'stock']` (Sản phẩm mới có dòng Tồn kho, kèm Tồn kho đầu kỳ nếu có) và `['catalog', 'categories']`
 * (số Sản phẩm của Danh mục đổi). Thêm Sản phẩm là một chứng từ (Tồn kho đầu kỳ `OPENING`) nên có
 * `Idempotency-Key` (AD-7). Ngừng kinh doanh xong (thành công hay lỗi) thì invalidate `['catalog', 'products']`,
 * `['inventory', 'stock']` và `['sales', 'sellable']` (Sản phẩm ẩn khỏi màn bán hàng).
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type { CreateProductRequest, Product, ProductSearchParams } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { categoriesQueryKey } from './categories';

export const PRODUCTS_PATH = '/api/v1/catalog/products';

export const productsQueryKey = ['catalog', 'products'] as const;
const stockQueryKey = ['inventory', 'stock'] as const;
const sellableQueryKey = ['sales', 'sellable'] as const;

export const IDEMPOTENCY_KEY_REUSE = '/errors/common/idempotency-key-reuse';

/**
 * Danh sách Sản phẩm có Tìm kiếm thường và lọc (FR-5, SP-05). Bộ lọc trống không gửi; không có `sort` thì backend
 * sắp theo tên không dấu.
 */
export function fetchProducts(
  request: PageRequest,
  filters: ProductSearchParams = {},
  signal?: AbortSignal,
): Promise<PageResponse<Product>> {
  const { q, categoryId, status, minPrice, maxPrice } = filters;
  return api.get<PageResponse<Product>>(PRODUCTS_PATH, {
    query: { ...pageRequestToQuery(request), q: q || undefined, categoryId, status, minPrice, maxPrice },
    signal,
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

function deactivateProductPath(id: Uuid): string {
  return `${PRODUCTS_PATH}/${encodeURIComponent(id)}/deactivate`;
}

/**
 * Ngừng kinh doanh (FR-3, SP-03), chỉ Chủ cửa hàng: `POST /api/v1/catalog/products/{id}/deactivate`, không có body,
 * trả chi tiết Sản phẩm. Một chiều; gọi lại với Sản phẩm đã ngừng vẫn `200` nên không cần `Idempotency-Key`.
 * Xong, kể cả khi lỗi, thì tải lại bảng Sản phẩm (dòng đổi trạng thái), Tồn kho và hàng bán được (Sản phẩm ẩn khỏi
 * màn bán hàng, tìm kiếm): mất phản hồi có thể là máy chủ đã ngừng rồi, `404` cho thấy bảng đang cũ.
 */
export function useDeactivateProduct() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.post<Product>(deactivateProductPath(id)),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: productsQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockQueryKey }),
        queryClient.invalidateQueries({ queryKey: sellableQueryKey }),
      ]),
  });
}
