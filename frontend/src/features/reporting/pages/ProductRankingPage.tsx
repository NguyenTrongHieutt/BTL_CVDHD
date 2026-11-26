// Sản phẩm bán chạy, bán chậm (FR-32). Chỉ Chủ cửa hàng.
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Flex, Typography } from 'antd';
import dayjs from 'dayjs';
import { api } from '../../../shared/api/client';
import { DateRangePicker, ErrorAlert, MoneyText, RecordTable, todayInVietnam, useDocumentTitle } from '../../../shared/components';
import type { DateRange } from '../../../shared/types/common';
import type { QueryResponse } from '../../../shared/types/reporting';

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

function defaultRange(): DateRange {
  const today = todayInVietnam();
  return { fromDate: dayjs(today).subtract(29, 'day').format('YYYY-MM-DD'), toDate: today };
}

const countFormatter = new Intl.NumberFormat('vi-VN');

export function ProductRankingPage() {
  useDocumentTitle('Sản phẩm bán chạy, bán chậm');
  const [range, setRange] = useState<DateRange>(defaultRange);
  const query = useQuery({
    queryKey: ['reporting', 'product-ranking', range],
    queryFn: async ({ signal }) => {
      const period = { fromDate: range.fromDate, toDate: range.toDate, rankBy: 'QUANTITY', limit: 10 };
      const [best, slow] = await Promise.all([
        api.get<QueryResponse<RankedProduct, RankingSummary>>('/api/v1/reporting/q/Q4', {
          query: { ...period, direction: 'BEST_SELLING' }, signal,
        }),
        api.get<QueryResponse<RankedProduct, RankingSummary>>('/api/v1/reporting/q/Q4', {
          query: { ...period, direction: 'SLOW_SELLING' }, signal,
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

  return (
    <Flex vertical gap={16}>
      <div>
        <Typography.Title level={1} style={{ margin: 0, fontSize: 'clamp(23px, 1.8vw, 27px)' }}>
          Sản phẩm bán chạy, bán chậm
        </Typography.Title>
        <Typography.Text type="secondary">Xếp hạng theo số lượng bán ròng, đã trừ hàng trả.</Typography.Text>
      </div>
      <DateRangePicker value={range} onChange={(next) => { if (next) setRange(next); }} />
      {query.isError && <ErrorAlert error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />}
      {query.isPending && <Typography.Text role="status">Đang tải xếp hạng sản phẩm…</Typography.Text>}

      {query.data && (
        <>
          <Card title="Bán chạy nhất">
            <RecordTable<RankedProduct>
              rowKey="productId"
              dataSource={query.data.best.rows}
              columns={columns}
              pagination={false}
              scroll={{ x: true }}
              locale={{ emptyText: 'Chưa có sản phẩm bán ra trong kỳ.' }}
            />
          </Card>
          <Card title="Bán chậm nhất">
            <RecordTable<RankedProduct>
              rowKey="productId"
              dataSource={query.data.slow.rows}
              columns={columns}
              pagination={false}
              scroll={{ x: true }}
              locale={{ emptyText: 'Không có dữ liệu sản phẩm trong kỳ.' }}
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
