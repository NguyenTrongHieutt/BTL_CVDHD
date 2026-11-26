import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Form, InputNumber, Space, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { useCurrentUser } from '../../../shared/api/session';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { LowStockPanel } from '../../../shared/components/LowStockPanel';
import { RemoteFilterSelect } from '../../../shared/components/RemoteFilterSelect';
import type { FilterOption } from '../../../shared/components/RemoteFilterSelect';
import { lowStockQueryKey } from '../../../shared/components/lowStockQueryKey';
import { useDocumentTitle } from '../../../shared/components/useDocumentTitle';
import { StockList } from './StockList';
import type { StockItem } from './StockList';

const MAX_THRESHOLD = 2147483647;

interface ThresholdOption extends FilterOption {
  minQuantity: number | null;
}

interface ThresholdForm {
  variant: ThresholdOption;
  minQuantity: number;
}

function StockThresholdForm() {
  const [form] = Form.useForm<ThresholdForm>();
  const queryClient = useQueryClient();
  const update = useMutation({
    mutationFn: ({ variant, minQuantity }: ThresholdForm) =>
      api.put<void>(`/api/v1/inventory/stock/${encodeURIComponent(variant.value)}/min-quantity`, { minQuantity }),
    onSuccess: async () => {
      form.resetFields();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: lowStockQueryKey }),
        queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] }),
        queryClient.invalidateQueries({ queryKey: ['reporting', 'stock'] }),
        queryClient.invalidateQueries({ queryKey: ['sales', 'sellable'] }),
      ]);
    },
  });

  return (
    <Card title="Đặt ngưỡng tồn tối thiểu">
      <Space orientation="vertical" style={{ width: '100%', maxWidth: 560 }}>
        {update.isError && <ErrorAlert error={update.error} onClose={update.reset} />}
        {update.isSuccess && <Alert type="success" showIcon title="Đã lưu ngưỡng tồn tối thiểu." />}
        <Form form={form} layout="vertical" scrollToFirstError={{ focus: true }} disabled={update.isPending}
          onFinish={(values) => update.mutate(values)}>
          <Form.Item name="variant" label="Sản phẩm hoặc biến thể" rules={[{ required: true, message: 'Chọn một mặt hàng.' }]}>
            <RemoteFilterSelect<ThresholdOption>
              ariaLabel="Sản phẩm hoặc biến thể"
              disabled={update.isPending}
              queryKey={['inventory', 'stock', 'threshold-options']}
              fetchOptions={async ({ page, size }, q, signal) => {
                const result = await api.get<PageResponse<StockItem>>('/api/v1/inventory/stock', {
                  query: { q: q || undefined, page, size }, signal,
                });
                return { ...result, items: result.items.map(({ variantId, productName, variantName, sku, minQuantity }) => ({
                  value: variantId,
                  label: [productName, variantName, sku].filter(Boolean).join(' · '),
                  minQuantity,
                })) };
              }}
              onChange={(selection) => {
                form.setFieldValue('minQuantity', selection?.minQuantity ?? undefined);
                update.reset();
              }}
              placeholder="Tìm theo tên, SKU hoặc mã vạch"
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
            <InputNumber inputMode="numeric" min={0} max={MAX_THRESHOLD} style={{ width: '100%' }} />
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
      <Typography.Title level={1} style={{ margin: 0 }}>Tồn kho</Typography.Title>
      <StockList owner={user.role === 'OWNER'} />
      <LowStockPanel />
      {user.role === 'OWNER' && <StockThresholdForm />}
    </Space>
  );
}
