/**
 * Phân trang chung (AD-19). Người giữ: Anh.
 *
 * `page` bắt đầu từ 0; `size` mặc định 20, tối đa 100; `sort=<field>,asc|desc` theo danh sách
 * backend cho phép. Bảng antd đếm trang từ 1: đổi qua lại bằng `shared/components/dataTablePaging.ts`.
 */
import type { QueryParams } from './client';

export interface PageResponse<T> {
  items: T[];
  total: number;
  /** Trang hiện tại, bắt đầu từ 0. */
  page: number;
  size: number;
}

export interface PageRequest {
  /** Bắt đầu từ 0. */
  page: number;
  size: number;
  /** Dạng `<field>,asc` hoặc `<field>,desc`. */
  sort?: string;
}

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;

export function clampPageSize(size: number | undefined): number {
  if (size === undefined || !Number.isFinite(size) || size <= 0) return DEFAULT_PAGE_SIZE;
  return Math.min(Math.floor(size), MAX_PAGE_SIZE);
}

/** Đổi `PageRequest` thành tham số query `page`, `size`, `sort`. */
export function pageRequestToQuery(request: PageRequest): QueryParams {
  return {
    page: Math.max(0, Math.floor(request.page)),
    size: clampPageSize(request.size),
    sort: request.sort,
  };
}
