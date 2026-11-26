import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Checkbox, Input, Space, Tag, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { CategoryFilterSelect } from '../../../shared/components/CategoryFilterSelect';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { MoneyText } from '../../../shared/components/MoneyText';
import { RecordTable } from '../../../shared/components/RecordTable';
import type { FilterOption } from '../../../shared/components/RemoteFilterSelect';
import type { StockLevel } from '../../../shared/types/inventory';

export interface StockItem extends StockLevel {
  productName: string;
  variantName: string | null;
  sku: string;
  belowMin: boolean;
}

interface StockFilters {
  q: string;
  category?: FilterOption;
  belowMin: boolean;
  includeInactive: boolean;
  page: number;
  size: number;
}

export function StockList({ owner }: { owner: boolean }) {
  const [filters, setFilters] = useState<StockFilters>({
    q: '', belowMin: false, includeInactive: false, page: 0, size: 20,
  });
  const { category, ...pagingAndFilters } = filters;
  const params = { ...pagingAndFilters, categoryId: category?.value, q: filters.q || undefined, belowMin: filters.belowMin || undefined };
  const stocks = useQuery({
    queryKey: ['inventory', 'stock', params],
    queryFn: ({ signal }) => api.get<PageResponse<StockItem>>('/api/v1/inventory/stock', {
      query: params, signal,
    }),
  });

  function changeFilters(change: Partial<StockFilters>) {
    setFilters((previous) => ({ ...previous, ...change, page: 0 }));
  }

  const items = stocks.data?.items ?? [];
  const inactiveOnPage = items.filter((item) => !item.isActive).length;
  const inactiveSummary = inactiveOnPage > 0 ? `Trang này có ${inactiveOnPage} mặt hàng ngừng kinh doanh.`
    : (stocks.data?.total ?? 0) > filters.size ? 'Trang này không có hàng ngừng kinh doanh.'
      : 'Không có hàng ngừng kinh doanh khớp bộ lọc hiện tại.';

  return (
    <Card title="Danh sách tồn kho">
      <Space wrap align="end" style={{ marginBottom: 16 }}>
        <Space orientation="vertical" size={4}>
          <label htmlFor="stock-search">Tìm hàng tồn kho</label>
          <Input.Search id="stock-search" placeholder="Tên, SKU hoặc mã vạch"
            onSearch={(q) => changeFilters({ q: q.trim() })} allowClear />
        </Space>
        <Space orientation="vertical" size={4}>
          <label htmlFor="stock-category">Danh mục</label>
          <CategoryFilterSelect
            id="stock-category"
            ariaLabel="Danh mục"
            style={{ width: 190, maxWidth: '100%' }}
            value={category}
            onChange={(next) => changeFilters({ category: next })}
          />
        </Space>
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
      {filters.includeInactive && (
        <div style={{ marginBottom: 12 }} role="status">
          {filters.belowMin ? <Typography.Text type="secondary">Bỏ chọn “Chỉ hàng tồn thấp” để xem hàng ngừng kinh doanh.</Typography.Text>
            : stocks.isSuccess && !stocks.isFetching && <Typography.Text type="secondary">{inactiveSummary}</Typography.Text>}
        </div>
      )}
      {stocks.isError ? (
        <ErrorAlert error={stocks.error} retryLabel="Tải lại tồn kho"
          onRetry={() => void stocks.refetch()} retrying={stocks.isFetching} />
      ) : (
        <RecordTable<StockItem>
          scrollRows
          compactColumns={{ title: 'name', status: 'status' }}
          rowKey="variantId"
          loading={stocks.isFetching}
          dataSource={items}
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
              key: 'name',
              render: (_, item) => [item.productName, item.variantName].filter(Boolean).join(' · '),
            },
            { title: 'SKU', dataIndex: 'sku' },
            { title: 'Tồn hiện tại', dataIndex: 'quantity', align: 'right' },
            { title: 'Ngưỡng tối thiểu', dataIndex: 'minQuantity', align: 'right' },
            {
              title: 'Trạng thái',
              key: 'status',
              render: (_, item) => {
                if (!item.isActive) return <Tag>Ngừng kinh doanh</Tag>;
                return item.belowMin ? <Tag color="warning">Tồn thấp</Tag> : <Tag color="success">Đang kinh doanh</Tag>;
              },
            },
            ...(owner ? [{
              title: 'Giá vốn bình quân',
              key: 'avgUnitCost',
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
