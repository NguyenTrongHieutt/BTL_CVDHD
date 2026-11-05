/**
 * Bảng có phân trang phía server (AD-19). Người giữ: Anh.
 *
 * Bảng tự giữ trang, kích thước trang và sắp xếp, gọi `fetchPage` qua TanStack Query với khóa
 * `[module, entity, { ...params, page, size, sort }]` (AD-20). Invalidate `[module, entity]` thì
 * bảng tải lại. Đổi `params` (ví dụ từ khóa tìm kiếm) thì quay về trang đầu.
 *
 * @example
 * <DataTable
 *   rowKey="customerId"
 *   queryKey={['catalog', 'customers', { q }]}
 *   fetchPage={(page, signal) => api.get<PageResponse<CustomerSummary>>('/api/v1/catalog/customers', {
 *     query: { ...pageRequestToQuery(page), q }, signal })}
 *   columns={columns}
 * />
 */
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Table } from 'antd';
import type { TableProps } from 'antd';
import { useState } from 'react';
import { DEFAULT_PAGE_SIZE, clampPageSize } from '../api/pagination';
import type { PageRequest, PageResponse } from '../api/pagination';
import type { ModuleId } from '../types/common';
import { ErrorAlert } from './ErrorAlert';
import { fromAntdChange, toAntdPagination } from './dataTablePaging';

export type DataTableQueryKey = readonly [module: ModuleId, entity: string, params?: Readonly<Record<string, unknown>>];

export interface DataTableProps<T> extends Omit<TableProps<T>, 'dataSource' | 'pagination' | 'loading' | 'onChange'> {
  queryKey: DataTableQueryKey;
  fetchPage: (request: PageRequest, signal: AbortSignal) => Promise<PageResponse<T>>;
  defaultPageSize?: number;
  /** Sắp xếp ban đầu, dạng `<field>,asc|desc`. */
  defaultSort?: string;
}

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

export function DataTable<T extends object>({
  queryKey,
  fetchPage,
  defaultPageSize = DEFAULT_PAGE_SIZE,
  defaultSort,
  ...tableProps
}: DataTableProps<T>) {
  const [module, entity, params] = queryKey;
  const paramsKey = JSON.stringify(params ?? {});
  const initialRequest: PageRequest = { page: 0, size: clampPageSize(defaultPageSize), sort: defaultSort };
  const [state, setState] = useState({ paramsKey, request: initialRequest });
  // Bộ lọc đổi thì về trang đầu, giữ kích thước trang và sắp xếp.
  const request = state.paramsKey === paramsKey ? state.request : { ...state.request, page: 0 };

  const query = useQuery({
    queryKey: [module, entity, { ...params, page: request.page, size: request.size, sort: request.sort }],
    queryFn: ({ signal }) => fetchPage(request, signal),
    placeholderData: keepPreviousData,
  });

  return (
    <>
      {query.isError ? <ErrorAlert error={query.error} /> : null}
      <Table<T>
        {...tableProps}
        dataSource={query.data?.items ?? []}
        loading={query.isFetching}
        pagination={{
          ...toAntdPagination(query.data, request),
          showSizeChanger: true,
          pageSizeOptions: PAGE_SIZE_OPTIONS,
          showTotal: (total) => `Tổng ${total.toLocaleString('vi-VN')} dòng`,
        }}
        onChange={(pagination, _filters, sorter) =>
          setState({ paramsKey, request: fromAntdChange(pagination, sorter, request) })
        }
      />
    </>
  );
}
