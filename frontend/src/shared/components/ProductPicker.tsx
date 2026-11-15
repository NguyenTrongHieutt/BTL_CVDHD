import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Input, Pagination, Space, Table, Tag } from 'antd';
import { api } from '../api/client';
import type { PageResponse } from '../api/client';
import type { SellableItem } from '../types/sales';
import { ErrorAlert } from './ErrorAlert';
import { MoneyText } from './MoneyText';

export interface ProductPickerProps {
  onPick: (item: SellableItem) => void;
  allowOutOfStock?: boolean;
  semantic?: boolean;
  autoFocus?: boolean;
  disabled?: boolean;
  placeholder?: string;
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

export function ProductPicker({
  onPick, allowOutOfStock, autoFocus, disabled,
  placeholder = 'Tìm theo tên, SKU hoặc mã vạch',
}: ProductPickerProps) {
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
    <Space orientation="vertical" style={{ width: '100%' }}>
      <Input.Search
        aria-label="Tìm hàng"
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
      {q && products.isError && <ErrorAlert error={products.error} />}
      {q && !products.isError && (
        <Table<SellableResponse>
          rowKey="variantId"
          size="small"
          loading={products.isFetching}
          dataSource={products.data?.items ?? []}
          pagination={false}
          scroll={{ x: true }}
          locale={{ emptyText: products.isFetching ? 'Đang tìm…' : 'Không tìm thấy sản phẩm.' }}
          columns={[
            { title: 'Sản phẩm', render: (_, row) => [row.productName, row.variantName].filter(Boolean).join(' · ') },
            { title: 'Giá bán', render: (_, row) => <MoneyText value={row.salePrice} /> },
            { title: 'Tồn', render: (_, row) => (
              <Space>{row.quantity}{row.belowMin && <Tag color="warning">Tồn thấp</Tag>}</Space>
            ) },
            { title: 'Thao tác', render: (_, row) => (
              <Button disabled={disabled || (!allowOutOfStock && row.quantity <= 0)} onClick={() => onPick({
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
