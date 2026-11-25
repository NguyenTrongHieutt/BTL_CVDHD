import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, App, Button, Card, Input, InputNumber, Pagination, Select, Space, Typography } from 'antd';
import { useSearchParams } from 'react-router';
import { api, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { ErrorAlert, MoneyText, RecordTable, useDocumentTitle } from '../../../shared/components';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import type { IsoDateTime, Money, Uuid } from '../../../shared/types/common';
import type { OrderStatus, OrderSummary, PaymentMethod } from '../../../shared/types/sales';

interface ReturnLine {
  lineNo: number;
  productName: string;
  variantName: string | null;
  quantity: number;
  returnedQuantity: number;
  remainingQuantity: number;
}

interface ReturnReceipt {
  returnId: Uuid;
  refundAmount: Money;
  refundMethod: PaymentMethod;
  reason: string | null;
  createdAt: IsoDateTime;
}

interface ReturnDetails {
  orderCode: string;
  status: OrderStatus;
  lines: ReturnLine[];
  receipts: ReturnReceipt[];
}

interface Selection {
  lineNo: number;
  quantity: number;
}

interface RefundQuote {
  refundAmount: Money;
  lines: { lineNo: number; quantity: number; refundAmount: Money }[];
}

interface ReturnRequest {
  lines: Selection[];
  refundMethod: PaymentMethod;
  reason: string;
  quotedRefundTotal: Money;
}

const statusLabels: Record<OrderStatus, string> = {
  COMPLETED: 'Hoàn thành',
  CANCELLED: 'Đã hủy',
  RETURNED: 'Đã trả hàng',
};
const methodLabels: Record<PaymentMethod, string> = { CASH: 'Tiền mặt', BANK_TRANSFER: 'Chuyển khoản' };
const SEARCH_PAGE_SIZE = 20;
const dateTime = new Intl.DateTimeFormat('vi-VN', {
  timeZone: VIETNAM_TIME_ZONE, dateStyle: 'short', timeStyle: 'short',
});

function ReturnOrder({ orderId }: { orderId: Uuid }) {
  const path = '/api/v1/sales/orders/' + encodeURIComponent(orderId) + '/returns';
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [quantities, setQuantities] = useState<Record<number, number>>({});
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [reason, setReason] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const [pending, setPending] = useState<ReturnRequest | null>(null);
  const details = useQuery({
    queryKey: ['sales', 'return-details', orderId],
    queryFn: ({ signal }) => api.get<ReturnDetails>(path, { signal }),
    retry: false,
  });
  const data = details.data;
  const lines = data?.lines.flatMap(line => {
    const quantity = quantities[line.lineNo] ?? 0;
    return quantity > 0 ? [{ lineNo: line.lineNo, quantity }] : [];
  }) ?? [];
  const ready = data?.status === 'COMPLETED' && lines.length > 0 && lines.every(selection =>
    data.lines.some(line => line.lineNo === selection.lineNo && selection.quantity <= line.remainingQuantity));
  const quote = useQuery({
    queryKey: ['sales', 'return-quote', orderId, lines],
    queryFn: ({ signal }) => api.post<RefundQuote>(path + '/quote', { lines }, { signal }),
    enabled: ready && pending === null,
    retry: false,
  });
  const create = useMutation({
    mutationFn: (request: ReturnRequest) => api.post<ReturnReceipt>(path, request, { idempotencyKey: key }),
    onSuccess: () => {
      setPending(null);
      setQuantities({});
      setMethod('CASH');
      setReason('');
      setKey(newIdempotencyKey());
      void message.success('Đã lưu phiếu trả hàng và cộng lại tồn kho.');
      for (const queryKey of [['sales'], ['inventory'], ['reporting']]) {
        void queryClient.invalidateQueries({ queryKey });
      }
    },
    onError: error => {
      if (shouldReuseIdempotencyKey(error)) return;
      setKey(newIdempotencyKey());
      setPending(null);
      void queryClient.invalidateQueries({ queryKey: ['sales', 'return-details', orderId] });
      void queryClient.invalidateQueries({ queryKey: ['sales', 'return-quote', orderId] });
    },
  });

  function confirmReturn() {
    if (create.isPending) return;
    const request = pending ?? (ready && quote.data && !details.isFetching && !quote.isFetching && !quote.isError
      ? { lines, refundMethod: method, reason: reason.trim(), quotedRefundTotal: quote.data.refundAmount }
      : null);
    if (!request) return;
    setPending(request);
    create.mutate(request);
  }

  const detailsError = details.isError && <ErrorAlert error={details.error} retryLabel="Tải lại thông tin đơn hàng"
    onRetry={() => void details.refetch()} retrying={details.isFetching} />;
  if (!data) return detailsError || <Typography.Text role="status">Đang tải đơn hàng…</Typography.Text>;
  const showForm = data.status === 'COMPLETED' || pending !== null;

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Card title={data.orderCode + ' · ' + statusLabels[data.status]}>
        {detailsError}
        <RecordTable<ReturnLine>
          compactColumns={{ title: 'productName' }}
          rowKey="lineNo"
          dataSource={data.lines}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            { title: 'Hàng hóa', key: 'productName', render: (_, line) => [line.productName, line.variantName].filter(Boolean).join(' · ') },
            { title: 'Đã bán', dataIndex: 'quantity', align: 'right' },
            { title: 'Đã trả', dataIndex: 'returnedQuantity', align: 'right' },
            { title: 'Có thể trả', dataIndex: 'remainingQuantity', align: 'right' },
            ...(showForm ? [{
              title: 'Số lượng trả',
              key: 'returnQuantity',
              render: (_: unknown, line: ReturnLine) => (
                <InputNumber
                  aria-label={'Số lượng trả dòng ' + line.lineNo}
                  inputMode="numeric"
                  min={0}
                  max={line.remainingQuantity}
                  precision={0}
                  value={quantities[line.lineNo] ?? 0}
                  disabled={line.remainingQuantity === 0 || pending !== null}
                  onChange={quantity => {
                    setQuantities(previous => ({ ...previous, [line.lineNo]: quantity ?? 0 }));
                    create.reset();
                  }}
                />
              ),
            }, {
              title: 'Tiền hoàn',
              key: 'refundAmount',
              render: (_: unknown, line: ReturnLine) => (
                <MoneyText value={quote.data?.lines.find(item => item.lineNo === line.lineNo)?.refundAmount} />
              ),
            }] : []),
          ]}
        />
      </Card>

      {showForm && (
        <Card title="Hoàn tiền">
          <Space orientation="vertical" style={{ width: '100%' }}>
            {lines.length > 0 && !ready && pending === null &&
              <Alert type="warning" title="Số lượng trả phải nằm trong giới hạn có thể trả." />}
            {ready && pending === null && quote.isFetching && <Typography.Text role="status">Đang tính tiền hoàn…</Typography.Text>}
            {quote.isError && pending === null && <ErrorAlert error={quote.error} retryLabel="Thử lại tính tiền hoàn"
              onRetry={() => void quote.refetch()} retrying={quote.isFetching} />}
            {create.isError && <ErrorAlert error={create.error} />}
            <Typography.Text strong>
              Tổng tiền hoàn{' '}
              <MoneyText value={pending?.quotedRefundTotal ?? (ready && !quote.isError ? quote.data?.refundAmount : undefined)} />
            </Typography.Text>
            <Space orientation="vertical" size={4} style={{ display: 'flex' }}>
              <label htmlFor="refund-method">Phương thức hoàn tiền</label>
              <Select<PaymentMethod>
                id="refund-method"
                value={method}
                onChange={setMethod}
                disabled={pending !== null}
                style={{ width: '100%', maxWidth: 260 }}
                options={[
                  { value: 'CASH', label: 'Tiền mặt' },
                  { value: 'BANK_TRANSFER', label: 'Chuyển khoản' },
                ]}
              />
            </Space>
            <Space orientation="vertical" size={4} style={{ display: 'flex' }}>
              <label htmlFor="refund-reason">Lý do trả hàng (không bắt buộc)</label>
              <Input id="refund-reason" maxLength={200} value={reason} disabled={pending !== null}
                onChange={event => setReason(event.target.value)} />
            </Space>
            <Typography.Text type="secondary">
              Hoàn tiền cho khách theo phương thức đã chọn rồi xác nhận để lưu phiếu.
            </Typography.Text>
            <Button
              type="primary"
              loading={create.isPending}
              disabled={pending === null && (
                !ready || !quote.data || quote.isFetching || quote.isError || details.isFetching || details.isError
              )}
              onClick={confirmReturn}
            >
              {pending && create.isError ? 'Thử lại lưu phiếu' : 'Xác nhận hoàn tiền'}
            </Button>
          </Space>
        </Card>
      )}

      {data.receipts.length > 0 && (
        <Card title="Phiếu trả đã lập">
          <RecordTable<ReturnReceipt>
            compactColumns={{ title: 'createdAt' }}
            rowKey="returnId"
            pagination={false}
            scroll={{ x: 'max-content' }}
            dataSource={data.receipts}
            columns={[
              { title: 'Thời gian', key: 'createdAt', render: (_, receipt) => dateTime.format(new Date(receipt.createdAt)) },
              { title: 'Phương thức', render: (_, receipt) => methodLabels[receipt.refundMethod] },
              { title: 'Lý do', render: (_, receipt) => receipt.reason || '—' },
              { title: 'Tiền hoàn', align: 'right', render: (_, receipt) => <MoneyText value={receipt.refundAmount} /> },
            ]}
          />
        </Card>
      )}
    </Space>
  );
}

export function ReturnsPage() {
  useDocumentTitle('Phiếu trả hàng');
  const [params, setParams] = useSearchParams();
  const orderId = params.get('orderId');
  const [search, setSearch] = useState({ code: '', page: 0 });
  const orders = useQuery({
    queryKey: ['sales', 'orders', 'return-search', search],
    queryFn: ({ signal }) => api.get<PageResponse<OrderSummary>>('/api/v1/sales/orders', {
      query: { orderCode: search.code, page: search.page, size: SEARCH_PAGE_SIZE }, signal,
    }),
    enabled: Boolean(search.code),
    retry: false,
  });

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Card title={<Typography.Title level={1} style={{ margin: 0 }}>Phiếu trả hàng</Typography.Title>}>
        <Space orientation="vertical" style={{ display: 'flex' }}>
          <label htmlFor="return-order-search">Mã đơn hàng cần trả</label>
          <Input.Search
            id="return-order-search"
            placeholder="Ví dụ DH-000042"
            allowClear
            enterButton="Tìm đơn"
            style={{ maxWidth: 420 }}
            onSearch={value => {
              const code = value.trim();
              if (code && code === search.code && search.page === 0) void orders.refetch();
              else setSearch({ code, page: 0 });
            }}
          />
          {search.code && orders.isError && <ErrorAlert error={orders.error} retryLabel="Thử lại tìm đơn"
            onRetry={() => void orders.refetch()} retrying={orders.isFetching} />}
          {search.code && orders.isFetching && <Typography.Text role="status">Đang tìm đơn hàng…</Typography.Text>}
          {search.code && !orders.isError && orders.data && <>
            <Space wrap>
              {!orders.isFetching && orders.data.items.length === 0 && <Typography.Text role="status">Không tìm thấy đơn hàng.</Typography.Text>}
              {orders.data.items.map(order => (
                <Button key={order.orderId} type={order.orderId === orderId ? 'primary' : 'default'}
                  aria-current={order.orderId === orderId ? 'true' : undefined}
                  onClick={() => setParams({ orderId: order.orderId })}>
                  {order.orderCode} · {statusLabels[order.status]}
                </Button>
              ))}
            </Space>
            {orders.data.total > SEARCH_PAGE_SIZE && <Pagination current={search.page + 1}
              pageSize={SEARCH_PAGE_SIZE} total={orders.data.total} showSizeChanger={false}
              onChange={page => setSearch(current => ({ ...current, page: page - 1 }))} />}
          </>}
        </Space>
      </Card>
      {orderId && <ReturnOrder key={orderId} orderId={orderId} />}
    </Space>
  );
}
