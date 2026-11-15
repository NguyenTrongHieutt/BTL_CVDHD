import { useQuery } from '@tanstack/react-query';
import { Button, Card, Space, Table } from 'antd';
import { api } from '../api/client';
import { ErrorAlert } from './ErrorAlert';

export const lowStockQueryKey = ['inventory', 'low-stock'] as const;

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
        <Space orientation="vertical">
          <ErrorAlert error={stock.error} />
          <Button onClick={() => void stock.refetch()}>Thử lại</Button>
        </Space>
      ) : (
        <Table<LowStockItem>
          rowKey="variantId"
          dataSource={stock.data ?? []}
          loading={stock.isPending}
          size="small"
          pagination={stock.data && stock.data.length > 5 ? { pageSize: 5, showSizeChanger: false } : false}
          locale={{ emptyText: stock.isPending ? 'Đang tải…' : 'Không có hàng dưới ngưỡng.' }}
          columns={[
            { title: 'Sản phẩm', render: (_, item) => item.variantName
              ? `${item.productName} · ${item.variantName}`
              : item.productName },
            { title: 'Tồn hiện tại', dataIndex: 'quantity', align: 'right' },
            { title: 'Ngưỡng tối thiểu', dataIndex: 'minQuantity', align: 'right' },
          ]}
        />
      )}
    </Card>
  );
}
