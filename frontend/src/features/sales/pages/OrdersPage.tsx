import { useRef, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Button, Card, Input, Modal, Select, Space, Tag, Typography } from 'antd';
import { DownOutlined, UpOutlined } from '@ant-design/icons';
import { Link } from 'react-router';
import { api, isApiError, newIdempotencyKey } from '../../../shared/api/client';
import type { PageRequest, PageResponse } from '../../../shared/api/client';
import {
  DateRangePicker, ErrorAlert, MoneyText, RecordTable, fromAntdChange, toAntdPagination, useCompactLayout, useDocumentTitle,
} from '../../../shared/components';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import type { DateRange, Uuid } from '../../../shared/types/common';
import type { OrderStatus, OrderSummary } from '../../../shared/types/sales';
import { OrderReceipt } from './OrderReceipt';

const orderStatuses: Record<OrderStatus, { label: string; color: string }> = {
  COMPLETED: { label: 'Hoàn thành', color: 'success' },
  CANCELLED: { label: 'Đã hủy', color: 'error' },
  RETURNED: { label: 'Đã trả hàng', color: 'warning' },
};

const statusOptions = Object.entries(orderStatuses).map(([value, { label }]) => ({ value, label }));

const dateTime = new Intl.DateTimeFormat('vi-VN', {
  timeZone: VIETNAM_TIME_ZONE,
  dateStyle: 'short',
  timeStyle: 'short',
});

interface Filters extends PageRequest {
  orderCode?: string;
  customerPhone?: string;
  sellerId?: Uuid;
  status?: OrderStatus;
  dates?: DateRange;
}

interface Seller {
  sellerId: Uuid;
  sellerName: string;
}

export function OrdersPage() {
  useDocumentTitle('Đơn hàng');
  const compact = useCompactLayout();
  const [showFilters, setShowFilters] = useState(false);
  const [filters, setFilters] = useState<Filters>({ page: 0, size: 20 });
  const [receiptOrder, setReceiptOrder] = useState<Uuid | null>(null);
  const receiptTrigger = useRef<HTMLElement>(null);
  const [cancellation, setCancellation] = useState<{
    order: OrderSummary;
    reason: string;
    key: string;
  } | null>(null);
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const cancelOrder = useMutation({
    mutationFn: (input: NonNullable<typeof cancellation>) =>
      api.post('/api/v1/sales/orders/' + input.order.orderId + '/cancel',
        { reason: input.reason.trim() }, { idempotencyKey: input.key }),
    onSuccess: (_, input) => {
      setCancellation(null);
      void message.success('Đã hủy đơn ' + input.order.orderCode);
      for (const queryKey of [['sales'], ['inventory'], ['reporting']]) {
        void queryClient.invalidateQueries({ queryKey });
      }
    },
    onError: (error) => {
      if (isApiError(error) && [403, 404, 409].includes(error.status)) {
        void queryClient.invalidateQueries({ queryKey: ['sales', 'orders'] });
      }
    },
  });
  const orders = useQuery({
    queryKey: ['sales', 'orders', filters],
    queryFn: ({ signal }) => {
      const { dates, ...query } = filters;
      return api.get<PageResponse<OrderSummary>>('/api/v1/sales/orders', {
        query: { ...query, ...dates },
        signal,
      });
    },
    placeholderData: keepPreviousData,
    staleTime: 0,
  });
  const sellers = useQuery({
    queryKey: ['sales', 'order-sellers'],
    queryFn: ({ signal }) => api.get<Seller[]>('/api/v1/sales/orders/sellers', { signal }),
  });

  function changeFilters(change: Partial<Filters>) {
    setFilters((previous) => ({ ...previous, ...change, page: 0 }));
  }

  const activeFilters = [filters.customerPhone, filters.dates, filters.status, filters.sellerId].filter(Boolean).length;
  const orderList = (
    <Card hidden={receiptOrder !== null} className="no-print"
      title={<Typography.Title level={1} style={{ margin: 0 }}>Đơn hàng</Typography.Title>}>
      <div className="orders-filters">
        <div className="order-filter order-filter-code">
          <label className="filter-field-label" htmlFor="order-code-filter">Mã đơn hàng</label>
          <Input.Search
            id="order-code-filter"
            placeholder="Nhập mã đơn"
            enterButton="Tìm"
            allowClear
            onSearch={(value) => changeFilters({ orderCode: value.trim() || undefined })}
          />
        </div>
        {compact && (
          <Button className="orders-filter-toggle"
            icon={showFilters ? <UpOutlined aria-hidden="true" /> : <DownOutlined aria-hidden="true" />}
            aria-expanded={showFilters} aria-controls="orders-extra-filters"
            onClick={() => setShowFilters(!showFilters)}>
            Bộ lọc{activeFilters > 0 ? ` (${activeFilters})` : ''}
          </Button>
        )}
        <div id="orders-extra-filters" className="orders-extra-filters" hidden={compact && !showFilters}>
          <div className="order-filter">
            <label className="filter-field-label" htmlFor="order-phone-filter">Số điện thoại khách hàng</label>
          <Input.Search
            id="order-phone-filter"
            placeholder="Nhập số điện thoại"
            inputMode="tel"
            allowClear
            onSearch={(value) => changeFilters({ customerPhone: value.trim() || undefined })}
          />
          </div>
          <div className="order-filter order-filter-date">
            <span className="filter-field-label">Khoảng ngày</span>
          <DateRangePicker
            value={filters.dates}
            onChange={(dates) => changeFilters({ dates: dates ?? undefined })}
            allowClear
          />
          </div>
          <div className="order-filter">
            <label className="filter-field-label" htmlFor="order-status-filter">Trạng thái</label>
          <Select<OrderStatus>
            id="order-status-filter"
            placeholder="Tất cả trạng thái"
            options={statusOptions}
            allowClear
            onChange={(status) => changeFilters({ status })}
          />
          </div>
          <div className="order-filter">
            <label className="filter-field-label" htmlFor="order-seller-filter">Người bán</label>
          <Select<Uuid>
            id="order-seller-filter"
            placeholder="Tất cả người bán"
            loading={sellers.isPending}
            options={sellers.data?.map(({ sellerId, sellerName }) => ({
              value: sellerId, label: sellerName,
            }))}
            showSearch={{ optionFilterProp: 'label' }}
            allowClear
            onChange={(sellerId) => changeFilters({ sellerId })}
          />
          </div>
        </div>
      </div>
      {sellers.isError && <ErrorAlert error={sellers.error} onRetry={() => void sellers.refetch()}
        retrying={sellers.isFetching} retryLabel="Tải lại người bán" />}
      {orders.isError ? (
        <ErrorAlert error={orders.error} onRetry={() => void orders.refetch()}
          retrying={orders.isFetching} retryLabel="Tải lại đơn hàng" />
      ) : (
        <RecordTable<OrderSummary>
          compactColumns={{ title: 'orderCode', status: 'status', actions: 'actions' }}
          rowKey="orderId"
          loading={orders.isFetching}
          dataSource={orders.data?.items ?? []}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: orders.isPending ? 'Đang tải…' : 'Không tìm thấy đơn hàng.' }}
          pagination={{
            ...toAntdPagination(orders.data, filters),
            showSizeChanger: true,
            pageSizeOptions: [20, 50, 100],
          }}
          onChange={(pagination) => setFilters((previous) => ({
            ...previous,
            ...fromAntdChange(pagination, undefined, previous),
          }))}
          columns={[
            { title: 'Mã đơn hàng', dataIndex: 'orderCode' },
            { title: 'Thời gian', key: 'createdAt', render: (_, row) => dateTime.format(new Date(row.createdAt)) },
            { title: 'Người bán', key: 'seller', render: (_, row) => row.sellerName ?? '—' },
            {
              title: 'Trạng thái',
              key: 'status',
              render: (_, row) => {
                const { label, color } = orderStatuses[row.status];
                return <Tag color={color}>{label}</Tag>;
              },
            },
            { title: 'Tổng tiền', key: 'total', align: 'right', render: (_, row) => <MoneyText value={row.total} /> },
            {
              title: 'Thao tác',
              key: 'actions',
              render: (_, row) => (
                <Space wrap>
                  <Button size="small" aria-label={`Xem hóa đơn ${row.orderCode}`} disabled={orders.isPlaceholderData}
                    onClick={(event) => {
                      receiptTrigger.current = event.currentTarget;
                      setReceiptOrder(row.orderId);
                    }}>
                    Xem hóa đơn
                  </Button>
                  {row.status !== 'CANCELLED' && (
                    <Link to={'/sales/returns?orderId=' + encodeURIComponent(row.orderId)}>
                      {row.status === 'RETURNED' ? 'Xem phiếu trả' : 'Trả hàng'}
                    </Link>
                  )}
                  {row.canCancel && (
                    <Button danger size="small" disabled={orders.isFetching} onClick={() => {
                      cancelOrder.reset();
                      setCancellation({ order: row, reason: '', key: newIdempotencyKey() });
                    }}>
                      Hủy đơn
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      )}
      <Modal
        open={cancellation !== null}
        title={'Hủy đơn ' + (cancellation?.order.orderCode ?? '')}
        okText="Xác nhận hủy"
        cancelText="Quay lại"
        confirmLoading={cancelOrder.isPending}
        closable={!cancelOrder.isPending}
        keyboard={!cancelOrder.isPending}
        mask={{ closable: !cancelOrder.isPending }}
        cancelButtonProps={{ disabled: cancelOrder.isPending }}
        okButtonProps={{ danger: true, disabled: !cancellation?.reason.trim() }}
        onCancel={() => setCancellation(null)}
        onOk={() => {
          if (cancellation?.reason.trim() && !cancelOrder.isPending) cancelOrder.mutate(cancellation);
        }}
      >
        <Typography.Paragraph>
          Hàng sẽ được cộng lại vào tồn kho. Nếu đã nhận tiền, hãy hoàn tiền cho khách riêng.
        </Typography.Paragraph>
        <label htmlFor="cancel-order-reason">Lý do hủy (bắt buộc)</label>
        <Input
          id="cancel-order-reason"
          maxLength={200}
          value={cancellation?.reason ?? ''}
          disabled={cancelOrder.isPending}
          onChange={(event) => {
            if (!cancellation) return;
            const reason = event.target.value;
            setCancellation({ ...cancellation, reason,
              key: reason.trim() === cancellation.reason.trim() ? cancellation.key : newIdempotencyKey() });
            if (cancelOrder.isError) cancelOrder.reset();
          }}
        />
        {cancelOrder.isError && <ErrorAlert error={cancelOrder.error} style={{ marginTop: 12 }} />}
      </Modal>
    </Card>
  );

  return (
    <>
      {receiptOrder && (
        <OrderReceipt orderId={receiptOrder} backLabel="Quay lại đơn hàng" onBack={() => {
          setReceiptOrder(null);
          requestAnimationFrame(() => receiptTrigger.current?.focus());
        }} />
      )}
      {orderList}
    </>
  );
}
