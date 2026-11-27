import { useId, useState } from 'react';
import type { Ref } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Empty, Input, Pagination, Segmented, Space, Tag, Typography } from 'antd';
import type { InputRef } from 'antd';
import {
  AI_ALTERNATIVES_LIMIT,
  searchAlternativeProductIds,
  searchSemanticProductIds,
} from '../api/aiSearch';
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

interface AlternativeTarget {
  productId: string;
  label: string;
}

const pageSize = 20;
type LookupMode = 'keyword' | 'description' | 'code';

function itemLabel(item: SellableResponse) {
  return [item.productName, item.variantName].filter(Boolean).join(' · ');
}

function toSellableItem(item: SellableResponse): SellableItem {
  return {
    productId: item.productId,
    variantId: item.variantId,
    name: item.productName,
    variantName: item.variantName,
    barcode: item.barcode,
    unitPrice: item.salePrice,
    stockQuantity: item.quantity,
    lowStock: item.belowMin,
  };
}

export function ProductPicker({
  onPick, allowOutOfStock, semantic, autoFocus, disabled, searchRef, exactCodeLookup,
  placeholder = 'Tìm theo tên, SKU hoặc mã vạch',
}: ProductPickerProps) {
  const inputId = useId();
  const alternativesHeadingId = useId();
  const [mode, setMode] = useState<LookupMode>('keyword');
  const [input, setInput] = useState('');
  const [q, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [alternativeTarget, setAlternativeTarget] = useState<AlternativeTarget | null>(null);
  const [dismissedAlternativeProductId, setDismissedAlternativeProductId] = useState<string | null>(null);
  const byCode = Boolean(exactCodeLookup && mode === 'code');
  const byDescription = Boolean(semantic && mode === 'description');
  const lookupOptions: { value: LookupMode; label: string }[] = [
    { value: 'keyword', label: 'Tìm tên hoặc mã' },
    ...(semantic ? [{ value: 'description' as const, label: 'Theo mô tả (AI)' }] : []),
    ...(exactCodeLookup ? [{ value: 'code' as const, label: 'Quét mã' }] : []),
  ];

  const semanticProducts = useQuery({
    queryKey: ['sales', 'semantic-product-ids', q],
    queryFn: ({ signal }) => searchSemanticProductIds(q, { signal }),
    enabled: byDescription && Boolean(q) && !disabled,
    retry: false,
  });
  const semanticIds = semanticProducts.isSuccess ? semanticProducts.data : undefined;
  const products = useQuery({
    queryKey: byCode
      ? ['sales', 'sellable-exact', { code: q }]
      : byDescription
        ? ['sales', 'sellable-description', {
            q, page, size: pageSize, ids: semanticProducts.isError ? 'normal-fallback' : semanticIds,
          }]
        : ['sales', 'sellable', { q, page, size: pageSize }],
    queryFn: ({ signal }) => {
      if (byCode) {
        return api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable/exact', {
          signal, query: { code: q },
        });
      }
      if (byDescription && semanticProducts.isSuccess) {
        if (semanticProducts.data.length === 0) {
          return { items: [], total: 0, page, size: pageSize };
        }
        return api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable', {
          signal, query: { ids: semanticProducts.data, page, size: pageSize },
        });
      }
      return api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable', {
        signal, query: { q, page, size: pageSize },
      });
    },
    enabled: Boolean(q) && !disabled && (
      !byDescription || semanticProducts.isSuccess || semanticProducts.isError
    ),
  });

  const exactItems = products.data?.items ?? [];
  const firstExactItem = exactItems[0];
  const automaticAlternativeTarget = byCode && !products.isFetching && firstExactItem &&
    dismissedAlternativeProductId !== firstExactItem.productId &&
    exactItems.every((item) => item.productId === firstExactItem.productId && item.quantity <= 0)
    ? {
        productId: firstExactItem.productId,
        label: exactItems.length === 1 ? itemLabel(firstExactItem) : firstExactItem.productName,
      }
    : null;
  const activeAlternativeTarget = alternativeTarget ?? automaticAlternativeTarget;

  const alternativeProductIds = useQuery({
    queryKey: ['sales', 'alternative-product-ids', activeAlternativeTarget?.productId],
    queryFn: ({ signal }) => searchAlternativeProductIds(activeAlternativeTarget?.productId ?? '', { signal }),
    enabled: Boolean(activeAlternativeTarget) && !disabled,
    retry: false,
  });
  const alternativeIds = (alternativeProductIds.data ?? []).filter(
    (productId) => productId !== activeAlternativeTarget?.productId,
  );
  const alternativeProducts = useQuery({
    queryKey: ['sales', 'sellable-alternatives', { productId: activeAlternativeTarget?.productId, ids: alternativeIds }],
    queryFn: ({ signal }) => api.get<PageResponse<SellableResponse>>('/api/v1/sales/sellable', {
      signal,
      query: { ids: alternativeIds, inStock: true, page: 0, size: AI_ALTERNATIVES_LIMIT },
    }),
    enabled: Boolean(activeAlternativeTarget) && alternativeProductIds.isSuccess && alternativeIds.length > 0 && !disabled,
    retry: false,
  });
  const seenAlternativeProducts = new Set<string>();
  const similarItems = (alternativeProducts.data?.items ?? []).filter((item) => {
    if (
      item.quantity <= 0 || item.productId === activeAlternativeTarget?.productId ||
      seenAlternativeProducts.has(item.productId)
    ) return false;
    seenAlternativeProducts.add(item.productId);
    return true;
  }).slice(0, AI_ALTERNATIVES_LIMIT);
  const aiLoading = byDescription && Boolean(q) && semanticProducts.isFetching;
  const alternativesLoading = alternativeProductIds.isFetching || (
    alternativeIds.length > 0 && alternativeProducts.isFetching && !alternativeProducts.data
  );
  const noAlternatives = alternativeProductIds.isSuccess && (
    alternativeIds.length === 0 || (alternativeProducts.isSuccess && similarItems.length === 0)
  );

  function resetSearch(nextMode: LookupMode) {
    setMode(nextMode);
    setInput('');
    setPage(0);
    setQuery('');
    setAlternativeTarget(null);
    setDismissedAlternativeProductId(null);
  }

  function choose(item: SellableResponse) {
    onPick(toSellableItem(item));
    setDismissedAlternativeProductId(activeAlternativeTarget?.productId ?? null);
    setAlternativeTarget(null);
  }

  function actionFor(item: SellableResponse) {
    const label = itemLabel(item);
    if (!allowOutOfStock && item.quantity <= 0 && semantic) {
      return (
        <Button
          aria-label={`Tìm sản phẩm tương tự ${label}`}
          disabled={disabled}
          onClick={() => {
            setDismissedAlternativeProductId(null);
            setAlternativeTarget({ productId: item.productId, label });
          }}
        >Tìm hàng tương tự</Button>
      );
    }
    return (
      <Button aria-label={`Thêm ${label}`} disabled={disabled || (!allowOutOfStock && item.quantity <= 0)}
        onClick={() => choose(item)}>Thêm</Button>
    );
  }

  return (
    <Space className="product-picker" orientation="vertical" style={{ width: '100%' }}>
      {lookupOptions.length > 1 && (
        <Segmented<LookupMode>
          role="group"
          aria-label="Cách tìm hàng"
          value={mode}
          disabled={disabled}
          options={lookupOptions}
          onChange={resetSearch}
        />
      )}
      <div>
        <label className="filter-field-label" htmlFor={inputId}>
          {byCode ? 'SKU hoặc mã vạch' : byDescription ? 'Mô tả sản phẩm' : 'Tìm hàng'}
        </label>
        <Input.Search
          ref={searchRef}
          id={inputId}
          allowClear
          autoFocus={autoFocus}
          disabled={disabled}
          value={input}
          onChange={(event) => {
            setInput(event.target.value);
            setAlternativeTarget(null);
            setDismissedAlternativeProductId((current) => activeAlternativeTarget?.productId ?? current);
          }}
          autoComplete={byCode ? 'off' : undefined}
          placeholder={byCode
            ? 'Quét hoặc nhập mã đầy đủ'
            : byDescription ? 'Ví dụ: đồ uống ít đường, vị thanh' : placeholder}
          enterButton={byCode ? 'Tra mã' : byDescription ? 'Tìm theo mô tả' : 'Tìm'}
          onSearch={(value) => {
            const term = value.trim();
            setPage(0);
            setAlternativeTarget(null);
            setDismissedAlternativeProductId(null);
            if (byCode) setInput('');
            if (term && term === q) {
              if (byDescription) void semanticProducts.refetch();
              else void products.refetch();
              return;
            }
            setQuery(term);
          }}
        />
      </div>
      {byDescription && q && semanticProducts.isError && (
        <Typography.Text type="warning" role="status">
          AI đang gián đoạn hoặc chưa phản hồi sau 1,5 giây. Đang dùng tìm kiếm theo tên hoặc mã hàng.
        </Typography.Text>
      )}
      {aiLoading && <Typography.Text role="status">Đang tìm theo mô tả…</Typography.Text>}
      {q && products.isError && (
        <ErrorAlert error={products.error} onRetry={() => void products.refetch()}
          retrying={products.isFetching} retryLabel="Thử tìm lại" />
      )}
      {q && !products.isError && !aiLoading && (
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
            : byCode ? `Không có hàng đang bán khớp mã ${q}.`
              : byDescription ? 'Không tìm thấy sản phẩm phù hợp với mô tả.' : 'Không tìm thấy sản phẩm.' }}
          columns={[
            { title: 'Sản phẩm', key: 'productName', render: (_, row) => itemLabel(row) },
            ...(byCode ? [{ title: 'SKU', dataIndex: 'sku', key: 'sku' }] : []),
            { title: 'Giá bán', key: 'salePrice', render: (_, row) => <MoneyText value={row.salePrice} /> },
            { title: 'Tồn hiện tại', key: 'quantity', render: (_, row) => (
              <Space wrap>
                {row.quantity}
                {row.quantity <= 0
                  ? <Tag color="error">Hết hàng</Tag>
                  : row.belowMin && <Tag color="warning">Tồn thấp</Tag>}
              </Space>
            ) },
            { title: 'Thao tác', key: 'actions', render: (_, row) => actionFor(row) },
          ]}
        />
      )}
      {q && !byCode && products.data && products.data.total > 0 && !aiLoading && (
        <Pagination current={page + 1} pageSize={pageSize} total={products.data.total}
          showSizeChanger={false} showTotal={(total) => `Trang ${page + 1}/${Math.ceil(total / pageSize)}`}
          onChange={(value) => setPage(value - 1)} />
      )}
      {activeAlternativeTarget && (
        <Card
          size="small"
          title={<Typography.Title id={alternativesHeadingId} level={5} style={{ margin: 0 }}>
            Sản phẩm tương tự còn hàng
          </Typography.Title>}
          extra={<Button type="link" onClick={() => {
            setDismissedAlternativeProductId(activeAlternativeTarget.productId);
            setAlternativeTarget(null);
          }}>Ẩn gợi ý</Button>}
          aria-labelledby={alternativesHeadingId}
        >
          <Space orientation="vertical" style={{ width: '100%' }}>
            <Typography.Text type="secondary">Gợi ý thay cho {activeAlternativeTarget.label}</Typography.Text>
            {alternativesLoading && <Typography.Text role="status">
              Đang tìm sản phẩm tương tự…
            </Typography.Text>}
            {alternativeProductIds.isError && (
              <Alert
                type="info"
                showIcon
                title="Chưa thể tìm sản phẩm tương tự lúc này."
                description="Bạn vẫn có thể tiếp tục tìm theo tên, mã hàng hoặc mô tả."
                action={<Button loading={alternativeProductIds.isFetching}
                  onClick={() => void alternativeProductIds.refetch()}>Thử lại</Button>}
              />
            )}
            {alternativeProducts.isError && (
              <Alert
                type="info"
                showIcon
                title="Chưa thể kiểm tra tồn kho của các gợi ý."
                action={<Button loading={alternativeProducts.isFetching}
                  onClick={() => void alternativeProducts.refetch()}>Thử lại</Button>}
              />
            )}
            {noAlternatives && (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="Chưa có sản phẩm tương tự còn hàng." />
            )}
            {similarItems.length > 0 && (
              <RecordTable<SellableResponse>
                compactColumns={{ title: 'productName', actions: 'actions' }}
                rowKey="variantId"
                size="small"
                dataSource={similarItems}
                pagination={false}
                scroll={{ x: true }}
                columns={[
                  { title: 'Sản phẩm', key: 'productName', render: (_, row) => itemLabel(row) },
                  { title: 'Giá bán', key: 'salePrice', render: (_, row) => <MoneyText value={row.salePrice} /> },
                  { title: 'Tồn hiện tại', key: 'quantity', dataIndex: 'quantity' },
                  { title: 'Thao tác', key: 'actions', render: (_, row) => (
                    <Button aria-label={`Thêm sản phẩm gợi ý ${itemLabel(row)}`} disabled={disabled}
                      onClick={() => choose(row)}>Thêm vào giỏ</Button>
                  ) },
                ]}
              />
            )}
          </Space>
        </Card>
      )}
    </Space>
  );
}
