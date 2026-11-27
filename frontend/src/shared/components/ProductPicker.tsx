import { useId, useState } from 'react';
import type { Ref } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Input, Pagination, Segmented, Space, Tag } from 'antd';
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
  exactCodeLookup?: boolean;
}

interface SellableResponse {
  productId: string;
  variantId: string;
  productName: string;
  variantName: string | null;
  sku: string;
  barcode: string | null;
  salePrice: number;
  quantity: number;
  belowMin: boolean;
}

const pageSize = 20;
type LookupMode = 'keyword' | 'code';

function itemLabel(item: SellableResponse) {
  return [item.productName, item.variantName].filter(Boolean).join(' · ');
}

export function ProductPicker({
  onPick, allowOutOfStock, autoFocus, disabled, searchRef, exactCodeLookup,
  placeholder = 'Tìm theo tên, SKU hoặc mã vạch',
}: ProductPickerProps) {
  const inputId = useId();
  const [mode, setMode] = useState<LookupMode>('keyword');
  const [input, setInput] = useState('');
  const [q, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const byCode = exactCodeLookup && mode === 'code';
  const products = useQuery({
    queryKey: byCode ? ['sales', 'sellable-exact', { code: q }] : ['sales', 'sellable', { q, page, size: pageSize }],
    queryFn: ({ signal }) => byCode
      ? api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable/exact', { signal, query: { code: q } })
      : api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable', { signal, query: { q, page, size: pageSize } }),
    enabled: Boolean(q) && !disabled,
  });

  return (
    <Space className="product-picker" orientation="vertical" style={{ width: '100%' }}>
      {exactCodeLookup && (
        <Segmented<LookupMode>
          role="group"
          aria-label="Cách tìm hàng"
          value={mode}
          disabled={disabled}
          options={[{ value: 'keyword', label: 'Tìm tên hoặc mã' }, { value: 'code', label: 'Quét mã' }]}
          onChange={(selected) => {
            setMode(selected);
            setInput('');
            setPage(0);
            setQuery('');
          }}
        />
      )}
      <div>
        <label className="filter-field-label" htmlFor={inputId}>{byCode ? 'SKU hoặc mã vạch' : 'Tìm hàng'}</label>
        <Input.Search
          ref={searchRef}
          id={inputId}
          allowClear
          autoFocus={autoFocus}
          disabled={disabled}
          value={input}
          onChange={(event) => setInput(event.target.value)}
          autoComplete={byCode ? 'off' : undefined}
          placeholder={byCode ? 'Quét hoặc nhập mã đầy đủ' : placeholder}
          enterButton={byCode ? 'Tra mã' : 'Tìm'}
          onSearch={(value) => {
            const term = value.trim();
            setPage(0);
            setQuery(term);
            if (byCode) {
              setInput('');
              if (term && term === q) void products.refetch();
            }
          }}
        />
      </div>
      {q && products.isError && (
        <ErrorAlert error={products.error} onRetry={() => void products.refetch()}
          retrying={products.isFetching} retryLabel="Thử tìm lại" />
      )}
      {q && !products.isError && (
        <RecordTable<SellableResponse>
          scrollRows
          compactColumns={{ title: 'productName', actions: 'actions' }}
          rowKey="variantId"
          size="small"
          loading={products.isFetching}
          dataSource={products.data?.items ?? []}
          pagination={false}
          scroll={{ x: true }}
          locale={{ emptyText: products.isFetching ? 'Đang tìm…'
            : byCode ? `Không có hàng đang bán khớp mã ${q}.` : 'Không tìm thấy sản phẩm.' }}
          columns={[
            { title: 'Sản phẩm', key: 'productName', render: (_, row) => itemLabel(row) },
            ...(byCode ? [{ title: 'SKU', dataIndex: 'sku', key: 'sku' }] : []),
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
      {q && !byCode && products.data && products.data.total > 0 && (
        <Pagination current={page + 1} pageSize={pageSize} total={products.data.total}
          showSizeChanger={false} showTotal={(total) => `Trang ${page + 1}/${Math.ceil(total / pageSize)}`}
          onChange={(value) => setPage(value - 1)} />
      )}
    </Space>
  );
}
