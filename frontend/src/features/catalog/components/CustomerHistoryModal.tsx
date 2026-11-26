import { useQuery } from '@tanstack/react-query';
import { Button, Card, Descriptions, Flex, Grid, Modal, Spin, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { useCustomerLoyalty } from '../../../shared/api/loyalty';
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/pagination';
import { ErrorAlert, MoneyText } from '../../../shared/components';
import { toAntdPagination, fromAntdChange } from '../../../shared/components/dataTablePaging';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import type { Customer } from '../../../shared/types/catalog';
import type { OrderStatus } from '../../../shared/types/sales';
import { fetchCustomerOrders, fetchPurchaseSummary } from '../api/customerHistory';
import type { CustomerOrder } from '../api/customerHistory';
import { customerLabel } from '../api/customers';

const formatter = new Intl.DateTimeFormat('vi-VN', {
  dateStyle: 'short', timeStyle: 'short', timeZone: VIETNAM_TIME_ZONE,
});

function formatTime(value: string) {
  return formatter.format(new Date(value));
}

const statuses: Record<OrderStatus, string> = {
  COMPLETED: 'Hoàn tất', CANCELLED: 'Đã hủy', RETURNED: 'Đã trả hàng',
};

const statusColors: Record<OrderStatus, string> = {
  COMPLETED: 'success', CANCELLED: 'error', RETURNED: 'warning',
};

const columns: TableColumnsType<CustomerOrder> = [
  { title: 'Mã đơn', dataIndex: 'orderCode' },
  { title: 'Ngày mua', dataIndex: 'createdAt', render: formatTime },
  { title: 'Trạng thái', dataIndex: 'status', render: (status: OrderStatus) => statuses[status] ?? status },
  { title: 'Tổng tiền', dataIndex: 'total', render: (total: number) => <MoneyText value={total} /> },
  { title: 'Người bán', dataIndex: 'sellerName' },
];

const compactColumns: TableColumnsType<CustomerOrder> = [{
  key: 'order',
  render: (_, order) => (
    <Card size="small">
      <Flex justify="space-between" align="center" gap={6} wrap>
        <Typography.Text strong>{order.orderCode}</Typography.Text>
        <Tag color={statusColors[order.status]} style={{ marginInlineEnd: 0 }}>{statuses[order.status] ?? order.status}</Tag>
      </Flex>
      <Descriptions size="small" column={1} style={{ marginBlockStart: 8 }} items={[
        { key: 'time', label: 'Ngày mua', children: formatTime(order.createdAt) },
        { key: 'seller', label: 'Người bán', children: order.sellerName ?? '—' },
        { key: 'total', label: 'Tổng tiền', children: <MoneyText value={order.total} strong /> },
      ]} />
    </Card>
  ),
}];

export function CustomerHistoryModal({ customer, onClosed }: { customer: Customer; onClosed: () => void }) {
  const { customerId } = customer;
  const compact = Grid.useBreakpoint().md === false;
  const [open, setOpen] = useState(true);
  const [request, setRequest] = useState({ page: 0, size: DEFAULT_PAGE_SIZE });
  const loyalty = useCustomerLoyalty(customerId);
  const summary = useQuery({
    queryKey: ['sales', 'purchase-summary', customerId],
    queryFn: ({ signal }) => fetchPurchaseSummary(customerId, signal),
    staleTime: 0,
  });
  const orders = useQuery({
    queryKey: ['sales', 'orders', { customerId, ...request }],
    queryFn: ({ signal }) => fetchCustomerOrders(customerId, request.page, request.size, signal),
    staleTime: 0,
  });

  return (
    <Modal open={open} title={`Lịch sử mua · ${customerLabel(customer)}`} width={800}
      onCancel={() => setOpen(false)} afterOpenChange={(visible) => { if (!visible) onClosed(); }}
      styles={{
        header: { paddingInlineEnd: 28, overflowWrap: 'anywhere' },
        body: { maxHeight: '62dvh', overflowY: 'auto' },
      }}
      footer={<Button onClick={() => setOpen(false)}>Đóng</Button>}>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Flex wrap align="center" justify="space-between" gap={8}>
          <Typography.Text strong>Điểm tích lũy</Typography.Text>
          {loyalty.data ? (
            <Typography.Text strong role="status">{loyalty.data.balance.toLocaleString('vi-VN')} điểm</Typography.Text>
          ) : loyalty.isError ? (
            <Flex wrap align="center" gap={8}>
              <Typography.Text type="secondary">Chưa tải được số dư điểm.</Typography.Text>
              <Button type="link" onClick={() => void loyalty.refetch()} loading={loyalty.isFetching}>Tải lại số dư</Button>
            </Flex>
          ) : <Spin aria-label="Đang tải số dư điểm" />}
        </Flex>
      </Card>
      <Typography.Title level={5}>Tổng quan mua hàng</Typography.Title>
      {summary.isError ? (
        <>
          <ErrorAlert error={summary.error} />
          <Button onClick={() => void summary.refetch()}>Tải lại tổng quan</Button>
        </>
      ) : summary.data ? (
        <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} items={[
          { key: 'count', label: 'Số đơn', children: summary.data.orderCount },
          { key: 'spent', label: 'Tổng chi tiêu', children: <MoneyText value={summary.data.totalSpent} /> },
          { key: 'last', label: 'Lần mua gần nhất', children: summary.data.lastPurchaseAt ? formatTime(summary.data.lastPurchaseAt) : 'Chưa mua hàng' },
        ]} />
      ) : <Spin aria-label="Đang tải tổng quan" />}
      <Typography.Title level={5}>Đơn hàng</Typography.Title>
      {orders.isError ? (
        <>
          <ErrorAlert error={orders.error} />
          <Button onClick={() => void orders.refetch()}>Tải lại đơn hàng</Button>
        </>
      ) : orders.data ? (
        <Table rowKey="orderId" dataSource={orders.data.items}
          columns={compact ? compactColumns : columns} size={compact ? 'small' : 'middle'}
          showHeader={!compact} scroll={compact ? undefined : { x: 640 }}
          pagination={toAntdPagination(orders.data, request)}
          onChange={(pagination) => {
            const { page, size } = fromAntdChange(pagination, undefined, request);
            setRequest({ page, size });
          }}
          locale={{ emptyText: 'Khách hàng chưa có đơn hàng.' }}
        />
      ) : <Spin aria-label="Đang tải đơn hàng" />}
    </Modal>
  );
}
