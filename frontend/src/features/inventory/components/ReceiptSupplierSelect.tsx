import { useInfiniteQuery } from '@tanstack/react-query';
import { Select, Space } from 'antd';
import { useDeferredValue, useId, useState } from 'react';
import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components';
import type { SupplierSummary } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';

export interface ReceiptSupplier {
  value: Uuid;
  label: string;
}

interface Props {
  value?: ReceiptSupplier;
  onChange: (supplier: ReceiptSupplier | undefined) => void;
  disabled?: boolean;
}

const PAGE_SIZE = 30;

export function ReceiptSupplierSelect({ value, onChange, disabled }: Props) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const query = useDeferredValue(search.trim());
  const suppliers = useInfiniteQuery({
    queryKey: ['catalog', 'suppliers', 'purchase-receipt-search', query],
    initialPageParam: 0,
    enabled: open && !disabled && search.trim() === query,
    queryFn: ({ pageParam, signal }) => api.get<PageResponse<SupplierSummary>>('/api/v1/catalog/suppliers', {
      signal, query: { q: query || undefined, page: pageParam, size: PAGE_SIZE },
    }),
    getNextPageParam: ({ page, size, total }) => (page + 1) * size < total ? page + 1 : undefined,
  });
  const searching = search.trim() !== query || suppliers.isPending;
  const matches = searching ? [] : suppliers.data?.pages.flatMap(({ items }) => items) ?? [];
  const options = matches.map(({ supplierId, name, code }) => ({
    value: supplierId, label: code ? `${name} · ${code}` : name,
  }));
  const noOptions = searching ? 'Đang tìm nhà cung cấp…'
    : suppliers.isError ? 'Chưa tải được nhà cung cấp.' : 'Không tìm thấy nhà cung cấp.';

  return (
    <Space orientation="vertical" size={4} style={{ display: 'flex' }}>
      <label htmlFor={id}>Nhà cung cấp</label>
      <Select<ReceiptSupplier>
        id={id}
        aria-required="true"
        placeholder="Tìm theo tên hoặc mã nhà cung cấp"
        style={{ width: '100%' }}
        disabled={disabled}
        loading={open && (searching || suppliers.isFetching)}
        notFoundContent={noOptions}
        allowClear
        labelInValue
        virtual={false}
        showSearch={{ searchValue: search, onSearch: setSearch, filterOption: false }}
        onOpenChange={(visible) => {
          setOpen(visible);
          if (!visible) setSearch('');
        }}
        onPopupScroll={(event) => {
          const list = event.currentTarget;
          if (suppliers.hasNextPage && !suppliers.isFetching && list.scrollHeight - list.scrollTop - list.clientHeight <= 24) {
            void suppliers.fetchNextPage();
          }
        }}
        value={value}
        options={options}
        onChange={(selected) => {
          onChange(selected);
          setSearch('');
        }}
      />
      {suppliers.isError && <ErrorAlert error={suppliers.error} retryLabel="Tải lại nhà cung cấp"
        onRetry={() => void suppliers.refetch()} retrying={suppliers.isFetching} />}
    </Space>
  );
}
