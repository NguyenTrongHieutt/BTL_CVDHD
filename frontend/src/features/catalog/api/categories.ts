/**
 * API Danh mục (FR-4, SP-04). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Bảng dùng khóa `['catalog', 'categories', params]`. Thêm thành công thì invalidate `['catalog', 'categories']`;
 * đổi tên và xóa invalidate khóa này cả khi lỗi (404, 409 cho thấy bảng đang cũ), để bảng tải lại ngay. Đổi tên
 * thành công còn invalidate `['catalog', 'products']` vì Sản phẩm hiện tên Danh mục. Danh mục không phải chứng từ
 * nên không có `Idempotency-Key`.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type { Category, CategoryRequest } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';

export const CATEGORIES_PATH = '/api/v1/catalog/categories';

export const categoriesQueryKey = ['catalog', 'categories'] as const;
const productsQueryKey = ['catalog', 'products'] as const;

export function fetchCategories(request: PageRequest, signal?: AbortSignal): Promise<PageResponse<Category>> {
  return api.get<PageResponse<Category>>(CATEGORIES_PATH, { query: pageRequestToQuery(request), signal });
}

/** Chuẩn hóa tên như backend: NFC rồi cắt khoảng trắng hai đầu. */
export function normalizeCategoryName(name: string): string {
  return name.normalize('NFC').trim();
}

function categoryPath(id: Uuid): string {
  return `${CATEGORIES_PATH}/${encodeURIComponent(id)}`;
}

export function useCreateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CategoryRequest) =>
      api.post<Category>(CATEGORIES_PATH, { name: normalizeCategoryName(request.name) } satisfies CategoryRequest),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: categoriesQueryKey }),
  });
}

export function useRenameCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name }: { id: Uuid; name: string }) =>
      api.put<Category>(categoryPath(id), { name: normalizeCategoryName(name) } satisfies CategoryRequest),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productsQueryKey }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: categoriesQueryKey }),
  });
}

export function useDeleteCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete<void>(categoryPath(id)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: categoriesQueryKey }),
  });
}
