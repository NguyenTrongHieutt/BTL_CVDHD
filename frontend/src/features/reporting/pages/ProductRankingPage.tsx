// Sản phẩm bán chạy, bán chậm (FR-32). Chỉ Chủ cửa hàng.
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Flex, Select, Typography } from 'antd';
import dayjs from 'dayjs';
import { api } from '../../../shared/api/client';
import { DateRangePicker, ErrorAlert, MoneyText, RecordTable, todayInVietnam, useDocumentTitle } from '../../../shared/components';
import { CategoryFilterSelect } from '../../../shared/components/CategoryFilterSelect';
import type { FilterOption } from '../../../shared/components/RemoteFilterSelect';
import type { DateRange } from '../../../shared/types/common';
import type { QueryResponse } from '../../../shared/types/reporting';
import { ReportExportButton } from '../ReportExportButton';

interface RankedProduct {
  rank: number;
  productId: string;
  productName: string;
  categoryName: string | null;
  quantity: number;
  revenue: number;
  soldQuantity: number;
  returnedQuantity: number;
}

interface RankingSummary {
  productCount: number;
}

type RankBy = 'QUANTITY' | 'REVENUE';
const rankingMetrics: { value: RankBy; label: string }[] = [
  { value: 'QUANTITY', label: 'Số lượng bán ròng' },
  { value: 'REVENUE', label: 'Doanh thu thuần' },
];
const rankingLimits = [10, 20, 50].map(value => ({ value, label: 'Top ' + value }));

function defaultRange(): DateRange {
  const today = todayInVietnam();
  return { fromDate: dayjs(today).subtract(29, 'day').format('YYYY-MM-DD'), toDate: today };
}

const countFormatter = new Intl.NumberFormat('vi-VN');

export function ProductRankingPage() {
  useDocumentTitle('Sản phẩm bán chạy, bán chậm');
  const [range, setRange] = useState<DateRange>(defaultRange);
  const [category, setCategory] = useState<FilterOption>();
  const [rankBy, setRankBy] = useState<RankBy>('QUANTITY');
  const [limit, setLimit] = useState(10);
  const filters = { ...range, rankBy, limit, categoryId: category?.value };
  const resultsKey = [range.fromDate, range.toDate, rankBy, limit, category?.value].join(':');
  const query = useQuery({
    queryKey: ['reporting', 'product-ranking', filters],
    queryFn: async ({ signal }) => {
      const [best, slow] = await Promise.all([
        api.get<QueryResponse<RankedProduct, RankingSummary>>('/api/v1/reporting/q/Q4', {
          query: { ...filters, direction: 'BEST_SELLING' }, signal,
        }),
        api.get<QueryResponse<RankedProduct, RankingSummary>>('/api/v1/reporting/q/Q4', {
          query: { ...filters, direction: 'SLOW_SELLING' }, signal,
        }),
      ]);
      return { best, slow };
    },
  });

  const columns = [
    { title: 'Hạng', dataIndex: 'rank', width: 72 },
    { title: 'Sản phẩm', dataIndex: 'productName' },
    { title: 'Danh mục', dataIndex: 'categoryName', render: (name: string | null) => name ?? '—' },
    { title: 'Số lượng bán ròng', dataIndex: 'quantity', align: 'right' as const,
      render: (quantity: number) => countFormatter.format(quantity) },
    { title: 'Doanh thu thuần', dataIndex: 'revenue', align: 'right' as const,
      render: (amount: number) => <MoneyText value={amount} /> },
  ];
  const ranking = query.data;

  return (
    <Flex vertical gap={16}>
      <div>
        <Typography.Title level={1} style={{ margin: 0, fontSize: 'clamp(23px, 1.8vw, 27px)' }}>
          Sản phẩm bán chạy, bán chậm
        </Typography.Title>
        <Typography.Text type="secondary">Số lượng bán ròng và doanh thu thuần đã tính phần trả hàng.</Typography.Text>
      </div>
      <Flex gap={12} wrap align="end" role="group" aria-label="Bộ lọc xếp hạng sản phẩm">
        <div className="filter-field" style={{ maxWidth: '100%' }}>
          <Typography.Text>Kỳ báo cáo</Typography.Text>
          <DateRangePicker value={range} onChange={(next) => { if (next) setRange(next); }} />
        </div>
        <label className="filter-field" style={{ width: 195, maxWidth: '100%' }}>
          Danh mục
          <CategoryFilterSelect ariaLabel="Danh mục" value={category}
            onChange={setCategory} style={{ width: '100%' }} />
        </label>
        <label className="filter-field" style={{ width: 190, maxWidth: '100%' }}>
          Xếp theo
          <Select<RankBy> aria-label="Xếp theo" value={rankBy} options={rankingMetrics}
            onChange={setRankBy} style={{ width: '100%' }} />
        </label>
        <label className="filter-field" style={{ width: 105, maxWidth: '100%' }}>
          Số kết quả
          <Select<number> aria-label="Số kết quả" value={limit} options={rankingLimits}
            onChange={setLimit} style={{ width: '100%' }} />
        </label>
        <ReportExportButton
          filename={`bao-cao-xep-hang-${range.fromDate}-${range.toDate}`}
          rows={ranking && !query.isError ? () => [
            ['Nhóm', 'Hạng', 'Sản phẩm', 'Danh mục', 'Số lượng bán ròng', 'Doanh thu thuần'],
            ...ranking.best.rows.map(row => ['Bán chạy nhất', row.rank, row.productName, row.categoryName,
              row.quantity, row.revenue]),
            ...ranking.slow.rows.map(row => ['Bán chậm nhất', row.rank, row.productName, row.categoryName,
              row.quantity, row.revenue]),
          ] : undefined}
        />
      </Flex>
      {query.isError && <ErrorAlert error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />}
      {query.isPending && <Typography.Text role="status">Đang tải xếp hạng sản phẩm…</Typography.Text>}

      {query.data && (
        <>
          <Card title="Bán chạy nhất" extra={`Top ${limit}`}>
            <RecordTable<RankedProduct>
              scrollRows
              key={resultsKey}
              rowKey="productId"
              dataSource={query.data.best.rows}
              columns={columns}
              pagination={{ defaultPageSize: 10, pageSizeOptions: [10, 20, 50], showSizeChanger: true, hideOnSinglePage: true }}
              scroll={{ x: true }}
              compactColumns={{ title: 'productName' }}
              locale={{ emptyText: 'Không có sản phẩm bán chạy khớp bộ lọc.' }}
            />
          </Card>
          <Card title="Bán chậm nhất" extra={`Top ${limit}`}>
            <RecordTable<RankedProduct>
              scrollRows
              key={resultsKey}
              rowKey="productId"
              dataSource={query.data.slow.rows}
              columns={columns}
              pagination={{ defaultPageSize: 10, pageSizeOptions: [10, 20, 50], showSizeChanger: true, hideOnSinglePage: true }}
              scroll={{ x: true }}
              compactColumns={{ title: 'productName' }}
              locale={{ emptyText: 'Không có sản phẩm bán chậm khớp bộ lọc.' }}
            />
          </Card>
          <Typography.Text type="secondary">
            Sản phẩm bán chậm gồm cả sản phẩm đang kinh doanh không phát sinh bán trong kỳ.
          </Typography.Text>
        </>
      )}
    </Flex>
  );
}
