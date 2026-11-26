import { Space } from 'antd';
import { useId } from 'react';
import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { RemoteFilterSelect } from '../../../shared/components/RemoteFilterSelect';
import type { FilterOption } from '../../../shared/components/RemoteFilterSelect';
import type { SupplierSummary } from '../../../shared/types/catalog';

export type ReceiptSupplier = FilterOption;

interface Props {
  value?: ReceiptSupplier;
  onChange: (supplier: ReceiptSupplier | undefined) => void;
  disabled?: boolean;
}

export function ReceiptSupplierSelect({ value, onChange, disabled }: Props) {
  const id = useId();

  return (
    <Space orientation="vertical" size={4} style={{ display: 'flex' }}>
      <label htmlFor={id}>Nhà cung cấp</label>
      <RemoteFilterSelect
        id={id}
        ariaLabel="Nhà cung cấp"
        aria-required
        placeholder="Tìm theo tên hoặc mã nhà cung cấp"
        style={{ width: '100%' }}
        disabled={disabled}
        value={value}
        onChange={onChange}
        queryKey={['catalog', 'suppliers', 'purchase-receipt-search']}
        fetchOptions={async ({ page, size }, q, signal) => {
          const result = await api.get<PageResponse<SupplierSummary>>('/api/v1/catalog/suppliers', {
            query: { q: q || undefined, page, size }, signal,
          });
          return { ...result, items: result.items.map(({ supplierId, name, code }) => ({
            value: supplierId, label: code ? `${name} · ${code}` : name,
          })) };
        }}
      />
    </Space>
  );
}
