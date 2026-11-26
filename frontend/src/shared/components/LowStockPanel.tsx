import { useQuery } from '@tanstack/react-query';
import { Card, Table, Typography } from 'antd';
import { api } from '../api/client';
import { ErrorAlert } from './ErrorAlert';
import { lowStockQueryKey } from './lowStockQueryKey';

interface LowStockItem {
  variantId: string;
  productName: string;
  variantName: string | null;
  quantity: number;
  minQuantity: number;
}

export function LowStockPanel() {
  const stock = useQuery({
    queryKey: lowStockQueryKey,
    queryFn: ({ signal }) => api.get<LowStockItem[]>('/api/v1/inventory/low-stock', { signal }),
  });

  return (
    <Card title="Cảnh báo tồn thấp">
      {stock.isError ? (
        <ErrorAlert error={stock.error} onRetry={() => void stock.refetch()} retrying={stock.isFetching} />
      ) : (
        <Table<LowStockItem>
          rowKey="variantId"
          dataSource={stock.data ?? []}
          loading={stock.isPending}
          size="small"
          showHeader={false}
          pagination={stock.data && stock.data.length > 5 ? { pageSize: 5, showSizeChanger: false } : false}
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
