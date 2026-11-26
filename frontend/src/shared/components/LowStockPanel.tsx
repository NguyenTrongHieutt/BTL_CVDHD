import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Card, Table, Typography } from 'antd';
import { api } from '../api/client';
import type { PageResponse } from '../api/pagination';
import { ErrorAlert } from './ErrorAlert';
import { fromAntdChange, toAntdPagination } from './dataTablePaging';
import { lowStockQueryKey } from './lowStockQueryKey';

interface LowStockItem {
  variantId: string;
  productName: string;
  variantName: string | null;
  quantity: number;
  minQuantity: number;
}

export function LowStockPanel() {
  const [paging, setPaging] = useState({ page: 0, size: 5 });
  const stock = useQuery({
    queryKey: [...lowStockQueryKey, paging],
    queryFn: ({ signal }) => api.get<PageResponse<LowStockItem>>('/api/v1/inventory/low-stock/page', { query: paging, signal }),
    placeholderData: keepPreviousData,
  });

  return (
    <Card title="Cảnh báo tồn thấp">
      {stock.isError ? (
        <ErrorAlert error={stock.error} onRetry={() => void stock.refetch()} retrying={stock.isFetching} />
      ) : (
        <Table<LowStockItem>
          rowKey="variantId"
          dataSource={stock.data?.items}
          loading={stock.isFetching}
          size="small"
          showHeader={false}
          pagination={{ ...toAntdPagination(stock.data, paging), showSizeChanger: false, hideOnSinglePage: true }}
          onChange={(pagination) => setPaging(fromAntdChange(pagination, undefined, paging))}
          locale={{ emptyText: stock.isPending ? 'Đang tải…' : 'Không có hàng dưới ngưỡng.' }}
          columns={[{ key: 'stock', render: (_, item) => (
            <div className="low-stock-item">
              <Typography.Text strong>{[item.productName, item.variantName].filter(Boolean).join(' · ')}</Typography.Text>
              <Typography.Text type="secondary">
                Tồn <strong>{item.quantity.toLocaleString('vi-VN')}</strong> · Ngưỡng {item.minQuantity.toLocaleString('vi-VN')}
              </Typography.Text>
            </div>
          ) }]}
        />
      )}
    </Card>
  );
}
