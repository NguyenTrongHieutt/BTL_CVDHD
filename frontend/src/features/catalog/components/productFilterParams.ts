/**
 * Bộ lọc Sản phẩm (FR-5, SP-05) trên URL query của màn Sản phẩm, để tải lại trang hay gửi link vẫn giữ bộ lọc.
 * Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Khóa trên URL trùng tên tham số của `GET /api/v1/catalog/products`: `q`, `categoryId`, `status`, `minPrice`,
 * `maxPrice`. Giá trị sai trên URL (giá không phải số nguyên, trạng thái lạ, khoảng giá ngược) thì bỏ qua, để link
 * gõ tay không làm bảng báo lỗi.
 */
import type { ProductSearchParams, ProductStatus } from '../../../shared/types/catalog';

export const PRODUCT_FILTER_KEYS = ['q', 'categoryId', 'status', 'minPrice', 'maxPrice'] as const;

const STATUSES: readonly ProductStatus[] = ['ACTIVE', 'INACTIVE'];
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function price(raw: string | null): number | undefined {
  if (raw === null || !/^\d+$/.test(raw.trim())) return undefined;
  const value = Number(raw.trim());
  return Number.isSafeInteger(value) ? value : undefined;
}

export function productFiltersFromSearchParams(params: URLSearchParams): ProductSearchParams {
  const filters: ProductSearchParams = {};
  const q = params.get('q')?.trim();
  if (q) filters.q = q;
  // Backend trả id chữ thường; id viết hoa (gõ tay, công cụ khác) vẫn là cùng Danh mục
  const categoryId = params.get('categoryId')?.trim().toLowerCase();
  if (categoryId && UUID_PATTERN.test(categoryId)) filters.categoryId = categoryId;
  const status = params.get('status');
  if (status && (STATUSES as readonly string[]).includes(status)) filters.status = status as ProductStatus;
  const minPrice = price(params.get('minPrice'));
  const maxPrice = price(params.get('maxPrice'));
  if (minPrice === undefined || maxPrice === undefined || minPrice <= maxPrice) {
    if (minPrice !== undefined) filters.minPrice = minPrice;
    if (maxPrice !== undefined) filters.maxPrice = maxPrice;
  }
  return filters;
}

/** URL query mới: giữ tham số khác, thay các khóa của bộ lọc, bỏ khóa trống. */
export function withProductFilters(params: URLSearchParams, filters: ProductSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const key of PRODUCT_FILTER_KEYS) {
    const value = filters[key];
    if (value === undefined || value === '') next.delete(key);
    else next.set(key, String(value));
  }
  return next;
}

export function hasProductFilters(filters: ProductSearchParams): boolean {
  return PRODUCT_FILTER_KEYS.some((key) => filters[key] !== undefined && filters[key] !== '');
}
