import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import type { PageRequest, PageResponse } from '../../../shared/api/client';
import {
  DateRangePicker, ErrorAlert, MoneyText, fromAntdChange, toAntdPagination, useDocumentTitle,
} from '../../../shared/components';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import type { DateRange, Uuid } from '../../../shared/types/common';
import type { OrderStatus, OrderSummary } from '../../../shared/types/sales';

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
  const [filters, setFilters] = useState<Filters>({ page: 0, size: 20 });
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

  return (
    <Card title={<Typography.Title level={2} style={{ margin: 0 }}>Đơn hàng</Typography.Title>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          aria-label="Mã đơn hàng"
          placeholder="Mã đơn hàng"
          allowClear
          onSearch={(value) => changeFilters({ orderCode: value.trim() || undefined })}
        />
        <Input.Search
          aria-label="Số điện thoại khách hàng"
          placeholder="Số điện thoại khách hàng"
          inputMode="tel"
          allowClear
          onSearch={(value) => changeFilters({ customerPhone: value.trim() || undefined })}
        />
        <DateRangePicker
          value={filters.dates}
          onChange={(dates) => changeFilters({ dates: dates ?? undefined })}
          allowClear
        />
        <Select<OrderStatus>
          aria-label="Trạng thái"
          placeholder="Tất cả trạng thái"
          style={{ minWidth: 165 }}
          options={statusOptions}
          allowClear
          onChange={(status) => changeFilters({ status })}
        />
        <Select<Uuid>
          aria-label="Người bán"
          placeholder="Tất cả người bán"
          style={{ minWidth: 170 }}
          loading={sellers.isPending}
          options={sellers.data?.map(({ sellerId, sellerName }) => ({
            value: sellerId, label: sellerName,
          }))}
          showSearch={{ optionFilterProp: 'label' }}
          allowClear
          onChange={(sellerId) => changeFilters({ sellerId })}
        />
      </Space>
      {sellers.isError && <ErrorAlert error={sellers.error} />}
      {orders.isError ? (
        <>
          <ErrorAlert error={orders.error} />
          <Button onClick={() => void orders.refetch()}>Thử lại</Button>
        </>
      ) : (
        <Table<OrderSummary>
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
            { title: 'Thời gian', render: (_, row) => dateTime.format(new Date(row.createdAt)) },
            { title: 'Người bán', render: (_, row) => row.sellerName ?? '—' },
            {
              title: 'Trạng thái',
              render: (_, row) => {
                const { label, color } = orderStatuses[row.status];
                return <Tag color={color}>{label}</Tag>;
              },
            },
            { title: 'Tổng tiền', align: 'right', render: (_, row) => <MoneyText value={row.total} /> },
          ]}
        />
      )}
    </Card>
  );
}
