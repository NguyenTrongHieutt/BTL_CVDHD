import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Input, Pagination, Radio, Space, Table, Tag, Typography } from 'antd';
import { searchSemanticProductIds } from '../api/aiSearch';
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
  onPick, allowOutOfStock, semantic, autoFocus, disabled,
  placeholder = 'Tìm theo tên, SKU hoặc mã vạch',
}: ProductPickerProps) {
  const [q, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [byDescription, setByDescription] = useState(false);
  const useAi = Boolean(semantic && byDescription);
  const suggested = useQuery({
    queryKey: ['sales', 'semantic-products', q],
    queryFn: ({ signal }) => searchSemanticProductIds(q, { signal }),
    enabled: useAi && Boolean(q) && !disabled,
    retry: false,
  });
  const ids = useAi && !suggested.isError ? suggested.data : undefined;
  const products = useQuery({
    queryKey: ['sales', 'sellable', { q, page, size: pageSize, ids }],
    queryFn: ({ signal }) => {
      if (ids?.length === 0) return { items: [], total: 0, page, size: pageSize };
      return api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable', {
        signal, query: { ...(ids ? { ids } : { q }), page, size: pageSize },
      });
    },
    enabled: Boolean(q) && !disabled && (!useAi || suggested.isSuccess || suggested.isError),
  });
  const aiLoading = useAi && Boolean(q) && suggested.isPending && !disabled;

  return (
    <Space orientation="vertical" style={{ width: '100%' }}>
      {semantic && (
        <Radio.Group aria-label="Cách tìm sản phẩm" disabled={disabled} value={byDescription}
          onChange={(event) => { setPage(0); setByDescription(event.target.value === true); }}
          options={[{ label: 'Tên hoặc mã hàng', value: false }, { label: 'Theo mô tả (AI)', value: true }]} />
      )}
      <Input.Search
        aria-label="Tìm hàng"
        allowClear
        autoFocus={autoFocus}
        disabled={disabled}
        placeholder={useAi ? 'Ví dụ: đồ uống ít đường' : placeholder}
        enterButton="Tìm"
        onSearch={(value) => {
          const next = value.trim();
          if (next && next === q && page === 0) {
            if (useAi) void suggested.refetch();
            else void products.refetch();
            return;
          }
          setPage(0);
          setQuery(next);
        }}
      />
      {useAi && q && suggested.isError && (
        <Typography.Text role="status">AI đang gián đoạn. Đang dùng tìm kiếm theo tên hoặc mã hàng.</Typography.Text>
      )}
      {aiLoading && <Typography.Text role="status">Đang tìm theo mô tả…</Typography.Text>}
      {q && products.isError && <ErrorAlert error={products.error} />}
      {q && !products.isError && !aiLoading && (
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
