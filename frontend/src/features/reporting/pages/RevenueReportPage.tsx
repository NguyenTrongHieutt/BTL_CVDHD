// Báo cáo doanh thu (FR-30). Chỉ Chủ cửa hàng.
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Col, Flex, Row, Typography } from 'antd';
import dayjs from 'dayjs';
import { api } from '../../../shared/api/client';
import { DateRangePicker, ErrorAlert, MoneyText, RecordTable, todayInVietnam, useDocumentTitle } from '../../../shared/components';
import type { DateRange, IsoDate } from '../../../shared/types/common';
import type { QueryResponse } from '../../../shared/types/reporting';
import { ReportExportButton } from '../ReportExportButton';

interface RevenueDay {
  date: IsoDate;
  grossSales: number;
  refundTotal: number;
  revenue: number;
  discountTotal: number;
}

interface OrderDay {
  date: IsoDate;
  orderCount: number;
  cancelledCount: number;
  fullyReturnedCount: number;
  returnCount: number;
}

interface RevenueSummary {
  grossSales: number;
  refundTotal: number;
  revenue: number;
  discountTotal: number;
}

interface OrderSummary {
  orderCount: number;
  cancelledCount: number;
  fullyReturnedCount: number;
  returnCount: number;
}

const dateFormatter = new Intl.DateTimeFormat('vi-VN', {
  day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC',
});
const countFormatter = new Intl.NumberFormat('vi-VN');

function defaultRange(): DateRange {
  const today = todayInVietnam();
  return { fromDate: dayjs(today).subtract(29, 'day').format('YYYY-MM-DD'), toDate: today };
}

function formatDate(value: IsoDate): string {
  return dateFormatter.format(new Date(`${value}T00:00:00Z`));
}

export function RevenueReportPage() {
  useDocumentTitle('Báo cáo doanh thu');
  const [range, setRange] = useState<DateRange>(defaultRange);
  const query = useQuery({
    queryKey: ['reporting', 'revenue', range],
    queryFn: async ({ signal }) => {
      const queryParams = { fromDate: range.fromDate, toDate: range.toDate };
      const [revenue, orders] = await Promise.all([
        api.get<QueryResponse<RevenueDay, RevenueSummary>>('/api/v1/reporting/q/Q1', { query: queryParams, signal }),
        api.get<QueryResponse<OrderDay, OrderSummary>>('/api/v1/reporting/q/Q2', { query: queryParams, signal }),
      ]);
      return { revenue, orders };
    },
  });
  const revenue = query.data?.revenue;
  const orders = query.data?.orders;
  const orderCountsByDate = new Map(orders?.rows.map((row) => [row.date, row.orderCount]));

  return (
    <Flex vertical gap={16}>
      <div>
        <Typography.Title level={1} style={{ margin: 0, fontSize: 'clamp(23px, 1.8vw, 27px)' }}>
          Báo cáo doanh thu
        </Typography.Title>
        <Typography.Text type="secondary">Doanh thu được tính sau giảm giá và trừ tiền hoàn theo ngày trả.</Typography.Text>
      </div>
      <Flex gap={8} wrap>
        <DateRangePicker value={range} onChange={(next) => { if (next) setRange(next); }} />
        <ReportExportButton
          filename={`bao-cao-doanh-thu-${range.fromDate}-${range.toDate}`}
          rows={revenue && orders && !query.isError ? () => [
            ['Ngày', 'Doanh thu trước hoàn', 'Tiền hoàn', 'Doanh thu thuần', 'Giảm giá', 'Số đơn'],
            ...revenue.rows.map(row => [row.date, row.grossSales, row.refundTotal, row.revenue,
              row.discountTotal, orderCountsByDate.get(row.date) ?? 0]),
            ['Tổng', revenue.summary.grossSales, revenue.summary.refundTotal, revenue.summary.revenue,
              revenue.summary.discountTotal, orders.summary.orderCount],
          ] : undefined}
        />
      </Flex>

      {query.isError && <ErrorAlert error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />}
      {query.isPending && <Typography.Text role="status">Đang tải báo cáo doanh thu…</Typography.Text>}

      {revenue && orders && (
        <>
          <Row gutter={[12, 12]}>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Typography.Text type="secondary">Doanh thu thuần</Typography.Text>
                <div style={{ fontSize: 22, fontWeight: 700 }}><MoneyText value={revenue.summary.revenue} /></div>
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Typography.Text type="secondary">Tổng doanh thu trước hoàn</Typography.Text>
                <div style={{ fontSize: 22, fontWeight: 700 }}><MoneyText value={revenue.summary.grossSales} /></div>
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Typography.Text type="secondary">Tiền đã hoàn</Typography.Text>
                <div style={{ fontSize: 22, fontWeight: 700 }}><MoneyText value={revenue.summary.refundTotal} /></div>
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Typography.Text type="secondary">Đơn hàng không bị hủy</Typography.Text>
                <div style={{ fontSize: 22, fontWeight: 700 }}>{countFormatter.format(orders.summary.orderCount)}</div>
              </Card>
            </Col>
          </Row>

          <Card title="Doanh thu theo ngày">
            <RecordTable<RevenueDay>
              scrollRows
              key={range.fromDate + ':' + range.toDate}
              rowKey="date"
              dataSource={revenue.rows}
              compactColumns={{ title: 'date' }}
              pagination={{ defaultPageSize: 14, pageSizeOptions: [14, 30, 100], showSizeChanger: true, hideOnSinglePage: true }}
              scroll={{ x: true }}
              columns={[
                { title: 'Ngày', dataIndex: 'date', render: (date: IsoDate) => formatDate(date) },
                { title: 'Doanh thu trước hoàn', dataIndex: 'grossSales', align: 'right',
                  render: (amount: number) => <MoneyText value={amount} /> },
                { title: 'Tiền hoàn', dataIndex: 'refundTotal', align: 'right',
                  render: (amount: number) => <MoneyText value={amount} /> },
                { title: 'Doanh thu thuần', dataIndex: 'revenue', align: 'right',
                  render: (amount: number) => <MoneyText value={amount} strong /> },
                { title: 'Giảm giá', dataIndex: 'discountTotal', align: 'right',
                  render: (amount: number) => <MoneyText value={amount} /> },
                { title: 'Số đơn', key: 'orderCount', align: 'right',
                  render: (_, row) => countFormatter.format(orderCountsByDate.get(row.date) ?? 0) },
              ]}
              locale={{ emptyText: 'Không có dữ liệu trong khoảng ngày đã chọn.' }}
            />
          </Card>
          <Typography.Text type="secondary">
            Khoảng báo cáo: {formatDate(range.fromDate)} – {formatDate(range.toDate)}.
          </Typography.Text>
        </>
      )}
    </Flex>
  );
}
