import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Col, Flex, Row, Typography } from 'antd';
import dayjs from 'dayjs';
import { api } from '../../../shared/api/client';
import { DateRangePicker, ErrorAlert, MoneyText, RecordTable, todayInVietnam, useDocumentTitle } from '../../../shared/components';
import type { DateRange, IsoDate } from '../../../shared/types/common';
import { linkedReportRange } from '../reportLink';
import type { QueryResponse } from '../../../shared/types/reporting';
import { ReportExportButton } from '../ReportExportButton';

interface ProfitSummary {
  revenue: number;
  costOfGoodsSold: number;
  grossProfit: number;
}

interface ProfitDay extends ProfitSummary {
  date: IsoDate;
}

const amountColumns = [
  { key: 'revenue', label: 'Doanh thu thuần' },
  { key: 'costOfGoodsSold', label: 'Giá vốn hàng bán' },
  { key: 'grossProfit', label: 'Lợi nhuận gộp' },
] as const;

function defaultRange(): DateRange {
  const today = todayInVietnam();
  return { fromDate: dayjs(today).subtract(29, 'day').format('YYYY-MM-DD'), toDate: today };
}

export function ProfitReportPage() {
  useDocumentTitle('Báo cáo lợi nhuận');
  const [range, setRange] = useState<DateRange>(() => linkedReportRange(defaultRange));
  const query = useQuery({
    queryKey: ['reporting', 'profit', range],
    queryFn: ({ signal }) => api.get<QueryResponse<ProfitDay, ProfitSummary>>('/api/v1/reporting/q/Q3', {
      query: { fromDate: range.fromDate, toDate: range.toDate }, signal,
    }),
  });
  const report = query.data;

  return (
    <Flex vertical gap={16}>
      <div>
        <Typography.Title level={1} style={{ margin: 0, fontSize: 'clamp(23px, 1.8vw, 27px)' }}>
          Báo cáo lợi nhuận
        </Typography.Title>
        <Typography.Text type="secondary">
          Lợi nhuận gộp bằng doanh thu thuần trừ giá vốn ghi nhận lúc bán. Hàng trả điều chỉnh số liệu theo ngày trả.
        </Typography.Text>
      </div>
      <Flex gap={8} wrap>
        <DateRangePicker value={range} onChange={(next) => { if (next) setRange(next); }} />
        <ReportExportButton
          filename={`bao-cao-loi-nhuan-${range.fromDate}-${range.toDate}`}
          rows={report && !query.isError ? () => [
            ['Ngày', ...amountColumns.map(column => column.label)],
            ...report.rows.map(row => [row.date, ...amountColumns.map(column => row[column.key])]),
            ['Tổng', ...amountColumns.map(column => report.summary[column.key])],
          ] : undefined}
        />
      </Flex>
      {query.isError && <ErrorAlert error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />}
      {query.isPending && <Typography.Text role="status">Đang tải báo cáo lợi nhuận…</Typography.Text>}
      {report && (
        <>
          <Row gutter={[12, 12]}>
            {amountColumns.map(({ key, label }) => (
              <Col key={key} xs={24} sm={8}>
                <Card size="small">
                  <Typography.Text type="secondary">{label}</Typography.Text>
                  <div style={{ fontSize: 22, fontWeight: 700 }}><MoneyText value={report.summary[key]} /></div>
                </Card>
              </Col>
            ))}
          </Row>
          <Card title="Lợi nhuận theo ngày">
            <RecordTable<ProfitDay>
              scrollRows
              rowKey="date"
              dataSource={report.rows}
              pagination={{ pageSize: 14, showSizeChanger: false, hideOnSinglePage: true }}
              compactColumns={{ title: 'date' }}
              scroll={{ x: true }}
              columns={[
                { title: 'Ngày', key: 'date', dataIndex: 'date', render: (date: IsoDate) => dayjs(date).format('DD/MM/YYYY') },
                ...amountColumns.map(({ key, label }) => ({
                  title: label, key, dataIndex: key, align: 'right' as const,
                  render: (amount: number) => <MoneyText value={amount} strong={key === 'grossProfit'} />,
                })),
              ]}
              locale={{ emptyText: 'Không có dữ liệu trong khoảng ngày đã chọn.' }}
            />
          </Card>
        </>
      )}
    </Flex>
  );
}
