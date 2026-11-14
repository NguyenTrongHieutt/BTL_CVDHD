import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Form, Input, InputNumber, Pagination, Select, Space, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { useCurrentUser } from '../../../shared/api/session';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { LowStockPanel, lowStockQueryKey } from '../../../shared/components/LowStockPanel';
import { useDocumentTitle } from '../../../shared/components/useDocumentTitle';
import type { Product } from '../../../shared/types/catalog';
import { StockList } from './StockList';

const PAGE_SIZE = 20;
const MAX_THRESHOLD = 2147483647;

interface ThresholdForm {
  variantId: string;
  minQuantity: number;
}

function StockThresholdForm() {
  const [form] = Form.useForm<ThresholdForm>();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const queryClient = useQueryClient();
  const products = useQuery({
    queryKey: ['catalog', 'products', { status: 'ACTIVE', q: search, page, size: PAGE_SIZE }],
    queryFn: ({ signal }) => api.get<PageResponse<Product>>('/api/v1/catalog/products', {
      query: { status: 'ACTIVE', q: search || undefined, page, size: PAGE_SIZE },
      signal,
    }),
  });
  const update = useMutation({
    mutationFn: ({ variantId, minQuantity }: ThresholdForm) =>
      api.put<void>(`/api/v1/inventory/stock/${variantId}/min-quantity`, { minQuantity }),
    onSuccess: async () => {
      form.resetFields();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: lowStockQueryKey }),
        queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] }),
        queryClient.invalidateQueries({ queryKey: ['sales', 'sellable'] }),
      ]);
    },
  });

  const variants = (products.data?.items ?? []).flatMap((product) =>
    product.variants.filter((variant) => variant.status === 'ACTIVE').map((variant) => ({
      value: variant.id,
      label: [product.name, variant.name, variant.sku].filter(Boolean).join(' · '),
    })),
  );

  return (
    <Card title="Đặt ngưỡng tồn tối thiểu">
      <Space orientation="vertical" style={{ width: '100%', maxWidth: 560 }}>
        <Input.Search
          aria-label="Tìm sản phẩm"
          placeholder="Tìm theo tên, SKU hoặc mã vạch"
          enterButton="Tìm"
          disabled={update.isPending}
          onSearch={(value) => {
            setSearch(value.trim());
            setPage(0);
            form.resetFields(['variantId']);
            update.reset();
          }}
        />
        {products.isError && <ErrorAlert error={products.error} />}
        {(products.data?.total ?? 0) > PAGE_SIZE && (
          <Pagination
            size="small"
            current={page + 1}
            pageSize={PAGE_SIZE}
            total={products.data?.total}
            showSizeChanger={false}
            disabled={update.isPending}
            onChange={(nextPage) => {
              setPage(nextPage - 1);
              form.resetFields(['variantId']);
            }}
          />
        )}
        {update.isError && <ErrorAlert error={update.error} onClose={update.reset} />}
        {update.isSuccess && <Alert type="success" showIcon title="Đã lưu ngưỡng tồn tối thiểu." />}
        <Form form={form} layout="vertical" disabled={update.isPending} onFinish={(values) => update.mutate(values)}>
          <Form.Item name="variantId" label="Sản phẩm hoặc biến thể" rules={[{ required: true, message: 'Chọn một biến thể.' }]}>
            <Select
              options={variants}
              loading={products.isPending}
              disabled={products.isPending || products.isError || update.isPending}
              placeholder="Chọn sản phẩm hoặc biến thể"
              notFoundContent="Không tìm thấy biến thể đang kinh doanh."
            />
          </Form.Item>
          <Form.Item
            name="minQuantity"
            label="Ngưỡng tồn tối thiểu"
            rules={[
              { required: true, message: 'Nhập ngưỡng tồn tối thiểu.' },
              { type: 'integer', min: 0, max: MAX_THRESHOLD, message: 'Nhập số nguyên từ 0 đến 2147483647.' },
            ]}
          >
            <InputNumber min={0} max={MAX_THRESHOLD} style={{ width: '100%' }} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={update.isPending}>Lưu ngưỡng</Button>
        </Form>
      </Space>
    </Card>
  );
}

export function StockPage() {
  useDocumentTitle('Tồn kho');
  const user = useCurrentUser();

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Typography.Title level={2} style={{ margin: 0 }}>Tồn kho</Typography.Title>
      <StockList owner={user.role === 'OWNER'} />
      <LowStockPanel />
      {user.role === 'OWNER' && <StockThresholdForm />}
    </Space>
  );
}
