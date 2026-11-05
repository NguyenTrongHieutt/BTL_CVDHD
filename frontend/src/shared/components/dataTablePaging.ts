/**
 * Đổi qua lại giữa phân trang của backend (page từ 0, AD-19) và bảng antd (current từ 1).
 * Người giữ: Anh.
 */
import { DEFAULT_PAGE_SIZE, clampPageSize } from '../api/pagination';
import type { PageRequest, PageResponse } from '../api/pagination';

export interface AntdPaginationState {
  /** Trang hiện tại của antd, bắt đầu từ 1. */
  current: number;
  pageSize: number;
  total: number;
}

/** Trang backend (từ 0) → trang antd (từ 1). */
export function toAntdPage(page: number): number {
  return Math.max(0, Math.floor(page)) + 1;
}

/** Trang antd (từ 1) → trang backend (từ 0). */
export function fromAntdPage(current: number | undefined): number {
  if (current === undefined || !Number.isFinite(current)) return 0;
  return Math.max(1, Math.floor(current)) - 1;
}

/** Trạng thái phân trang cho `<Table pagination>`; ưu tiên số trang/kích thước backend trả về. */
export function toAntdPagination(
  response: PageResponse<unknown> | undefined,
  request: PageRequest,
): AntdPaginationState {
  return {
    current: toAntdPage(response?.page ?? request.page),
    pageSize: response?.size ?? request.size,
    total: response?.total ?? 0,
  };
}

/** Sorter của antd (một cột). */
export interface AntdSorterLike {
  field?: unknown;
  columnKey?: unknown;
  order?: 'ascend' | 'descend' | null;
}

type AntdSorterInput = AntdSorterLike | readonly AntdSorterLike[] | undefined;

function isSorterList(sorter: AntdSorterInput): sorter is readonly AntdSorterLike[] {
  return Array.isArray(sorter);
}

/** Sorter antd → tham số `sort=<field>,asc|desc`. Không sắp xếp thì `undefined`. Backend chỉ nhận một cột. */
export function toSortParam(sorter: AntdSorterInput): string | undefined {
  const single = isSorterList(sorter) ? sorter[0] : sorter;
  if (!single || !single.order) return undefined;
  const rawField = single.columnKey ?? single.field;
  const field = Array.isArray(rawField) ? rawField.join('.') : rawField;
  if (typeof field !== 'string' && typeof field !== 'number') return undefined;
  return `${field},${single.order === 'ascend' ? 'asc' : 'desc'}`;
}

/**
 * Sự kiện `onChange` của bảng antd → `PageRequest` mới.
 * Đổi kích thước trang hoặc đổi sắp xếp thì về trang đầu.
 */
export function fromAntdChange(
  pagination: { current?: number; pageSize?: number },
  sorter: AntdSorterInput,
  previous: PageRequest,
): PageRequest {
  const size = clampPageSize(pagination.pageSize ?? previous.size ?? DEFAULT_PAGE_SIZE);
  const sort = sorter === undefined ? previous.sort : toSortParam(sorter);
  const resetPage = size !== previous.size || sort !== previous.sort;
  const next: PageRequest = { page: resetPage ? 0 : fromAntdPage(pagination.current), size };
  if (sort !== undefined) next.sort = sort;
  return next;
}
