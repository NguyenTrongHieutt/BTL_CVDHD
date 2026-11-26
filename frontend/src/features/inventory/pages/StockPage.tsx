import { useDeferredValue, useState } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Form, InputNumber, Select, Space, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { useCurrentUser } from '../../../shared/api/session';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { LowStockPanel } from '../../../shared/components/LowStockPanel';
import { lowStockQueryKey } from '../../../shared/components/lowStockQueryKey';
import { useDocumentTitle } from '../../../shared/components/useDocumentTitle';
import { StockList } from './StockList';
import type { StockItem } from './StockList';

const PAGE_SIZE = 20;
const MAX_THRESHOLD = 2147483647;

interface ThresholdForm {
  variant: { value: string; label: string };
  minQuantity: number;
}

function StockThresholdForm() {
  const [form] = Form.useForm<ThresholdForm>();
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const q = useDeferredValue(search.trim());
  const queryClient = useQueryClient();
  const stock = useInfiniteQuery({
    queryKey: ['inventory', 'stock', 'threshold-options', q],
    enabled: open && q === search.trim(),
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) => api.get<PageResponse<StockItem>>('/api/v1/inventory/stock', {
      query: { q: q || undefined, page: pageParam, size: PAGE_SIZE }, signal,
    }),
    getNextPageParam: ({ page, size, total }) => (page + 1) * size < total ? page + 1 : undefined,
  });
  const update = useMutation({
    mutationFn: ({ variant, minQuantity }: ThresholdForm) =>
      api.put<void>(`/api/v1/inventory/stock/${encodeURIComponent(variant.value)}/min-quantity`, { minQuantity }),
    onSuccess: async () => {
      form.resetFields();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: lowStockQueryKey }),
        queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] }),
        queryClient.invalidateQueries({ queryKey: ['sales', 'sellable'] }),
      ]);
    },
  });

  const searching = q !== search.trim() || stock.isPending;
  const items = searching ? [] : (stock.data?.pages.flatMap((page) => page.items) ?? []);
  const options = items.map((item) => ({
    value: item.variantId, label: [item.productName, item.variantName, item.sku].filter(Boolean).join(' · '),
  }));
  const noOptions = searching ? 'Đang tìm hàng…'
    : stock.isError ? 'Chưa tải được danh sách mặt hàng.' : 'Không tìm thấy hàng đang kinh doanh.';

  return (
    <Card title="Đặt ngưỡng tồn tối thiểu">
      <Space orientation="vertical" style={{ width: '100%', maxWidth: 560 }}>
        {open && stock.isError && <ErrorAlert error={stock.error} retryLabel="Tải lại sản phẩm"
          onRetry={() => void stock.refetch()} retrying={stock.isFetching} />}
        {update.isError && <ErrorAlert error={update.error} onClose={update.reset} />}
        {update.isSuccess && <Alert type="success" showIcon title="Đã lưu ngưỡng tồn tối thiểu." />}
        <Form form={form} layout="vertical" scrollToFirstError={{ focus: true }} disabled={update.isPending}
          onFinish={(values) => update.mutate(values)}>
          <Form.Item name="variant" label="Sản phẩm hoặc biến thể" rules={[{ required: true, message: 'Chọn một mặt hàng.' }]}>
            <Select<ThresholdForm['variant']>
              allowClear
              labelInValue
              virtual={false}
              options={options}
              loading={open && (searching || stock.isFetching)}
              showSearch={{ filterOption: false, searchValue: search, onSearch: setSearch }}
              onOpenChange={(visible) => {
                setOpen(visible);
                if (!visible) setSearch('');
              }}
              onPopupScroll={(event) => {
                const list = event.currentTarget;
                if (stock.hasNextPage && !stock.isFetching && list.scrollHeight - list.scrollTop - list.clientHeight <= 24) {
                  void stock.fetchNextPage();
                }
              }}
              onChange={(selection) => {
                form.setFieldValue('minQuantity', items.find((item) => item.variantId === selection?.value)?.minQuantity ?? undefined);
                update.reset();
              }}
              placeholder="Tìm theo tên, SKU hoặc mã vạch"
              notFoundContent={noOptions}
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
