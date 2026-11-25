import { useId, useState } from 'react';
import type { Ref } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Input, Pagination, Space, Tag } from 'antd';
import type { InputRef } from 'antd';
import { api } from '../api/client';
import type { PageResponse } from '../api/client';
import type { SellableItem } from '../types/sales';
import { ErrorAlert } from './ErrorAlert';
import { MoneyText } from './MoneyText';
import { RecordTable } from './RecordTable';

export interface ProductPickerProps {
  onPick: (item: SellableItem) => void;
  allowOutOfStock?: boolean;
  semantic?: boolean;
  autoFocus?: boolean;
  disabled?: boolean;
  placeholder?: string;
  searchRef?: Ref<InputRef>;
}

interface SellableResponse {
  productId: string;
  variantId: string;
  productName: string;
  variantName: string | null;
  barcode: string | null;
  salePrice: number;
  quantity: number;
  belowMin: boolean;
}

const pageSize = 20;

function itemLabel(item: SellableResponse) {
  return [item.productName, item.variantName].filter(Boolean).join(' · ');
}

export function ProductPicker({
  onPick, allowOutOfStock, autoFocus, disabled, searchRef,
  placeholder = 'Tìm theo tên, SKU hoặc mã vạch',
}: ProductPickerProps) {
  const inputId = useId();
  const [q, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const products = useQuery({
    queryKey: ['sales', 'sellable', { q, page, size: pageSize }],
    queryFn: ({ signal }) => api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable', {
      signal, query: { q, page, size: pageSize },
    }),
    enabled: Boolean(q) && !disabled,
  });

  return (
    <Space className="product-picker" orientation="vertical" style={{ width: '100%' }}>
      <div>
        <label className="filter-field-label" htmlFor={inputId}>Tìm hàng</label>
        <Input.Search
          ref={searchRef}
          id={inputId}
          allowClear
          autoFocus={autoFocus}
          disabled={disabled}
          placeholder={placeholder}
          enterButton="Tìm"
          onSearch={(value) => {
            setPage(0);
            setQuery(value.trim());
          }}
        />
      </div>
      {q && products.isError && (
        <ErrorAlert error={products.error} onRetry={() => void products.refetch()}
          retrying={products.isFetching} retryLabel="Thử tìm lại" />
      )}
      {q && !products.isError && (
        <RecordTable<SellableResponse>
          compactColumns={{ title: 'productName', actions: 'actions' }}
          rowKey="variantId"
          size="small"
          loading={products.isFetching}
          dataSource={products.data?.items ?? []}
          pagination={false}
          scroll={{ x: true }}
          locale={{ emptyText: products.isFetching ? 'Đang tìm…' : 'Không tìm thấy sản phẩm.' }}
          columns={[
            { title: 'Sản phẩm', key: 'productName', render: (_, row) => itemLabel(row) },
            { title: 'Giá bán', key: 'salePrice', render: (_, row) => <MoneyText value={row.salePrice} /> },
            { title: 'Tồn hiện tại', key: 'quantity', render: (_, row) => (
              <Space wrap>{row.quantity}{row.belowMin && <Tag color="warning">Tồn thấp</Tag>}</Space>
            ) },
            { title: 'Thao tác', key: 'actions', render: (_, row) => (
              <Button aria-label={`Thêm ${itemLabel(row)}`} disabled={disabled || (!allowOutOfStock && row.quantity <= 0)}
                onClick={() => onPick({
                productId: row.productId,
                variantId: row.variantId,
                name: row.productName,
                variantName: row.variantName,
                barcode: row.barcode,
                unitPrice: row.salePrice,
                stockQuantity: row.quantity,
                lowStock: row.belowMin,
              })}>Thêm</Button>
            ) },
          ]}
        />
      )}
      {q && products.data && products.data.total > pageSize && (
        <Pagination current={page + 1} pageSize={pageSize} total={products.data.total}
          showSizeChanger={false} onChange={(value) => setPage(value - 1)} />
      )}
    </Space>
  );
}
