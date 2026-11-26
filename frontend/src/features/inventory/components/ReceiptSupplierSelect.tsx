import { useQuery } from '@tanstack/react-query';
import { Input, Select, Space } from 'antd';
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
  inline?: boolean;
}

export function ReceiptSupplierSelect({ value, onChange, disabled, inline }: Props) {
  const id = useId();
  const [search, setSearch] = useState('');
  const query = useDeferredValue(search.trim());
  const suppliers = useQuery({
    queryKey: ['catalog', 'suppliers', 'purchase-receipt-options', query],
    queryFn: ({ signal }) => api.get<PageResponse<SupplierSummary>>('/api/v1/catalog/suppliers', {
      signal, query: { q: query || undefined, page: 0, size: 30 },
    }),
  });
  const options = suppliers.data?.items.map(({ supplierId, name, code }) => ({
    value: supplierId, label: code ? `${name} · ${code}` : name,
  })) ?? [];
  const noOptions = suppliers.isFetching ? 'Đang tải nhà cung cấp…'
    : suppliers.isError ? 'Chưa tải được nhà cung cấp.' : 'Không tìm thấy nhà cung cấp.';
  const problem = suppliers.isError && <ErrorAlert error={suppliers.error} retryLabel="Tải lại nhà cung cấp"
    onRetry={() => void suppliers.refetch()} retrying={suppliers.isFetching} />;

  if (inline) {
    const choices = value && !options.some(option => option.value === value.value) ? [value, ...options] : options;
    return (
      <div className="receipt-import-grid">
        <div className="receipt-import-field">
          <label htmlFor={`${id}-query`}>Tìm nhà cung cấp</label>
          <Input id={`${id}-query`} value={search} onChange={event => setSearch(event.target.value)}
            placeholder="Nhập tên hoặc mã" allowClear disabled={disabled} />
        </div>
        <div className="receipt-import-field">
          <label htmlFor={id}>Nhà cung cấp</label>
          <select id={id} className="receipt-import-supplier" aria-required="true" value={value?.value ?? ''}
            disabled={disabled} onChange={(event) => onChange(choices.find(option => option.value === event.target.value))}>
            <option value="">{options.length ? 'Chọn nhà cung cấp' : noOptions}</option>
            {choices.map(choice => <option key={choice.value} value={choice.value}>{choice.label}</option>)}
          </select>
        </div>
        {problem}
      </div>
    );
  }

  return (
    <Space orientation="vertical" size={4} style={{ display: 'flex' }}>
      <label htmlFor={id}>Nhà cung cấp</label>
      <Select<ReceiptSupplier>
        id={id}
        aria-required="true"
        placeholder="Chọn hoặc tìm nhà cung cấp"
        style={{ width: '100%' }}
        disabled={disabled}
        loading={suppliers.isFetching}
        notFoundContent={noOptions}
        allowClear
        labelInValue
        showSearch={{ onSearch: setSearch, filterOption: false }}
        value={value}
        options={options}
        onChange={(selected) => {
          onChange(selected);
          setSearch('');
        }}
      />
      {problem}
    </Space>
  );
}
