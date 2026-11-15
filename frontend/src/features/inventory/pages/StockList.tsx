import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Card, Checkbox, Input, Select, Space, Table, Tag } from 'antd';
import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { MoneyText } from '../../../shared/components/MoneyText';
import type { Category } from '../../../shared/types/catalog';
import type { StockLevel } from '../../../shared/types/inventory';

interface StockItem extends StockLevel {
  productName: string;
  variantName: string | null;
  sku: string;
  belowMin: boolean;
}

interface StockFilters {
  q: string;
  categoryId: string | undefined;
  belowMin: boolean;
  includeInactive: boolean;
  page: number;
  size: number;
}

export function StockList({ owner }: { owner: boolean }) {
  const [filters, setFilters] = useState<StockFilters>({
    q: '', categoryId: undefined, belowMin: false, includeInactive: false, page: 0, size: 20,
  });
  const categories = useQuery({
    queryKey: ['catalog', 'categories', 'inventory-options'],
    queryFn: async ({ signal }) => {
      const items: Category[] = [];
      for (let page = 0; ; page += 1) {
        const result = await api.get<PageResponse<Category>>('/api/v1/catalog/categories', {
          query: { page, size: 100 }, signal,
        });
        items.push(...result.items);
        if (items.length >= result.total || result.items.length === 0) return items;
      }
    },
  });
  const stocks = useQuery({
    queryKey: ['inventory', 'stock', filters],
    queryFn: ({ signal }) => api.get<PageResponse<StockItem>>('/api/v1/inventory/stock', {
      query: { ...filters, q: filters.q || undefined, belowMin: filters.belowMin || undefined },
      signal,
    }),
  });

  function changeFilters(change: Partial<StockFilters>) {
    setFilters((previous) => ({ ...previous, ...change, page: 0 }));
  }

  return (
    <Card title="Danh sách tồn kho">
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          aria-label="Tìm hàng tồn kho"
          placeholder="Tên, SKU hoặc mã vạch"
          onSearch={(q) => changeFilters({ q: q.trim() })}
          allowClear
        />
        <Select<string>
          aria-label="Danh mục"
          placeholder="Tất cả danh mục"
          allowClear
          loading={categories.isPending}
          style={{ minWidth: 180 }}
          options={categories.data?.map((category) => ({ value: category.id, label: category.name }))}
          onChange={(categoryId) => changeFilters({ categoryId })}
        />
        <Checkbox checked={filters.belowMin} onChange={(event) => changeFilters({ belowMin: event.target.checked })}>
          Chỉ hàng tồn thấp
        </Checkbox>
        <Checkbox
          checked={filters.includeInactive}
          onChange={(event) => changeFilters({ includeInactive: event.target.checked })}
        >
          Gồm hàng ngừng kinh doanh
        </Checkbox>
      </Space>
      {categories.isError && <ErrorAlert error={categories.error} />}
      {stocks.isError ? (
        <>
          <ErrorAlert error={stocks.error} />
          <Button onClick={() => void stocks.refetch()}>Thử lại</Button>
        </>
      ) : (
        <Table<StockItem>
          rowKey="variantId"
          loading={stocks.isPending}
          dataSource={stocks.data?.items ?? []}
          scroll={{ x: true }}
          locale={{ emptyText: stocks.isPending ? 'Đang tải…' : 'Không tìm thấy hàng tồn kho.' }}
          pagination={{
            current: filters.page + 1,
            pageSize: filters.size,
            total: stocks.data?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [20, 50, 100],
            onChange: (page, size) => setFilters((previous) => ({
              ...previous, page: size === previous.size ? page - 1 : 0, size,
            })),
          }}
          columns={[
            {
              title: 'Sản phẩm',
              render: (_, item) => [item.productName, item.variantName].filter(Boolean).join(' · '),
            },
            { title: 'SKU', dataIndex: 'sku' },
            { title: 'Tồn hiện tại', dataIndex: 'quantity', align: 'right' },
            { title: 'Ngưỡng tối thiểu', dataIndex: 'minQuantity', align: 'right' },
            {
              title: 'Trạng thái',
              render: (_, item) => {
                if (!item.isActive) return <Tag>Ngừng kinh doanh</Tag>;
                return item.belowMin ? <Tag color="warning">Tồn thấp</Tag> : 'Đang kinh doanh';
              },
            },
            ...(owner ? [{
              title: 'Giá vốn bình quân',
              align: 'right' as const,
              render: (_: unknown, item: StockItem) =>
                item.avgUnitCost === undefined ? '—' : <MoneyText value={item.avgUnitCost} />,
            }] : []),
          ]}
        />
      )}
    </Card>
  );
}
