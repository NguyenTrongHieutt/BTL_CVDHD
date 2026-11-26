import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { DownloadOutlined } from '@ant-design/icons';
import { Button, Card, Checkbox, Col, Flex, Input, Row, Select, Tag, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import { downloadBlob } from '../../../shared/api/downloadBlob';
import { DEFAULT_PAGE_SIZE, pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import { ErrorAlert, MoneyText, RecordTable, fromAntdChange, toAntdPagination, todayInVietnam, useDocumentTitle } from '../../../shared/components';
import type { FilterOption } from '../../../shared/components/RemoteFilterSelect';
import { CategoryFilterSelect } from '../../../shared/components/CategoryFilterSelect';

type StockStatus = 'OUT_OF_STOCK' | 'BELOW_MIN' | 'IN_STOCK';

interface StockRow {
  productId: string;
  productName: string;
  categoryName: string | null;
  variantId: string;
  variantName: string | null;
  sku: string;
  stockQuantity: number;
  minQuantity: number | null;
  avgUnitCost: number;
  stockValue: number;
  isActive: boolean;
}

interface StockReport extends PageResponse<StockRow> {
  asOf: string;
  summary: { stockQuantity: number; stockValue: number; variantCount: number };
}

interface StockFilters {
  q?: string;
  category?: FilterOption;
  stockStatus?: StockStatus;
  includeInactive?: boolean;
}

const STOCK_REPORT = '/api/v1/reporting/stock';
const stockStatuses: { value: StockStatus; label: string }[] = [
  { value: 'OUT_OF_STOCK', label: 'Hết hàng' },
  { value: 'BELOW_MIN', label: 'Dưới ngưỡng' },
  { value: 'IN_STOCK', label: 'Còn hàng đủ ngưỡng' },
];
const countFormatter = new Intl.NumberFormat('vi-VN');
const dateFormatter = new Intl.DateTimeFormat('vi-VN', {
  dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Ho_Chi_Minh',
});

function stockStatusTag({ isActive, stockQuantity, minQuantity }: StockRow) {
  if (!isActive) return <Tag>Ngừng kinh doanh</Tag>;
  if (stockQuantity === 0) return <Tag color="error">Hết hàng</Tag>;
  if (minQuantity !== null && stockQuantity < minQuantity) return <Tag color="warning">Dưới ngưỡng</Tag>;
  return <Tag color="success">Còn hàng</Tag>;
}

export function StockReportPage() {
  useDocumentTitle('Báo cáo tồn kho');
  const [filters, setFilters] = useState<StockFilters>({});
  const [search, setSearch] = useState('');
  const [paging, setPaging] = useState<PageRequest>({ page: 0, size: DEFAULT_PAGE_SIZE });
  const params = {
    q: filters.q,
    categoryId: filters.category?.value,
    stockStatus: filters.stockStatus,
    includeInactive: filters.includeInactive || undefined,
  };

  const query = useQuery({
    queryKey: ['reporting', 'stock', params, paging],
    queryFn: ({ signal }) => api.get<StockReport>(STOCK_REPORT, {
      query: { ...params, ...pageRequestToQuery(paging) }, signal,
    }),
    placeholderData: keepPreviousData,
  });
  const report = query.data;
  const exportCsv = useMutation({
    mutationFn: () => api.get<Blob>(STOCK_REPORT + '/export', { query: params, responseType: 'blob' }),
    onSuccess: (blob) => downloadBlob(blob, `bao-cao-ton-kho-${todayInVietnam()}.csv`),
  });

  function changeFilters(change: Partial<StockFilters>) {
    setFilters(previous => ({ ...previous, ...change }));
    setPaging(previous => ({ ...previous, page: 0 }));
    exportCsv.reset();
  }

  const filtered = Object.values(params).some(Boolean);
  const canExport = Boolean(report?.total && !query.isPlaceholderData && !query.isError);

  return (
    <Flex vertical gap={16}>
      <div>
        <Typography.Title level={1} style={{ margin: 0, fontSize: 'clamp(23px, 1.8vw, 27px)' }}>
          Báo cáo tồn kho
        </Typography.Title>
        <Typography.Text type="secondary">Giá trị tồn tính theo giá vốn bình quân hiện tại.</Typography.Text>
      </div>

      <Flex gap={12} wrap align="end" aria-label="Bộ lọc báo cáo tồn kho" role="group">
        <label className="filter-field" style={{ flex: '1 1 245px', minWidth: 0, maxWidth: 370 }}>
          Tìm hàng
          <Input.Search
            aria-label="Tìm hàng"
            placeholder="Tên, biến thể, SKU hoặc mã vạch"
            value={search}
            maxLength={100}
            allowClear
            onChange={(event) => setSearch(event.target.value)}
            onSearch={(value) => changeFilters({ q: value.trim() || undefined })}
          />
        </label>
        <label className="filter-field" style={{ flex: '1 1 175px', minWidth: 0, maxWidth: 235 }}>
          Danh mục
          <CategoryFilterSelect ariaLabel="Danh mục" value={filters.category}
            onChange={(category) => changeFilters({ category })} style={{ width: '100%' }} />
        </label>
        <label className="filter-field" style={{ flex: '1 1 175px', minWidth: 0, maxWidth: 235 }}>
          Trạng thái tồn
          <Select<StockStatus>
            aria-label="Trạng thái tồn"
            placeholder="Tất cả trạng thái tồn"
            allowClear
            value={filters.stockStatus}
            options={stockStatuses}
            onChange={(stockStatus) => changeFilters({ stockStatus })}
            style={{ width: '100%' }}
          />
        </label>
        <Checkbox style={{ minHeight: 32, alignItems: 'center' }} checked={Boolean(filters.includeInactive)}
          onChange={(event) => changeFilters({ includeInactive: event.target.checked })}>
          Gồm hàng ngừng kinh doanh
        </Checkbox>
        <Button disabled={!filtered && !search} onClick={() => {
          setSearch('');
          changeFilters({ q: undefined, category: undefined, stockStatus: undefined, includeInactive: false });
        }}>Xóa bộ lọc</Button>
        <Button icon={<DownloadOutlined />} disabled={!canExport} loading={exportCsv.isPending}
          onClick={() => exportCsv.mutate()}>Tải CSV</Button>
      </Flex>

      {query.isError && <ErrorAlert error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />}
      {exportCsv.isError && <ErrorAlert error={exportCsv.error} retryLabel="Thử tải lại CSV"
        onRetry={canExport ? () => exportCsv.mutate() : undefined} />}

      {report && (
        <Row gutter={[12, 12]} aria-busy={query.isFetching}>
          <Col xs={24} sm={8}>
            <Card size="small">
              <Typography.Text type="secondary">Tổng số lượng tồn</Typography.Text>
              <div style={{ fontSize: 22, fontWeight: 700 }}>{countFormatter.format(report.summary.stockQuantity)}</div>
            </Card>
          </Col>
          <Col xs={24} sm={8}>
            <Card size="small">
              <Typography.Text type="secondary">Giá trị tồn kho</Typography.Text>
              <div style={{ fontSize: 22, fontWeight: 700 }}><MoneyText value={report.summary.stockValue} /></div>
            </Card>
          </Col>
          <Col xs={24} sm={8}>
            <Card size="small">
              <Typography.Text type="secondary">Biến thể khớp bộ lọc</Typography.Text>
              <div style={{ fontSize: 22, fontWeight: 700 }}>{countFormatter.format(report.summary.variantCount)}</div>
            </Card>
          </Col>
        </Row>
      )}

      <Card title={<span style={{ whiteSpace: 'normal' }}>Tồn kho theo sản phẩm và biến thể</span>}>
        {report && <Typography.Paragraph type="secondary">
          Số liệu lúc {dateFormatter.format(new Date(report.asOf))}. Số tổng và CSV tính trên toàn bộ kết quả đã lọc.
        </Typography.Paragraph>}
        <RecordTable<StockRow>
          rowKey="variantId"
          compactColumns={{ title: 'productName', status: 'status' }}
          dataSource={report?.items}
          loading={query.isFetching}
          pagination={{
            ...toAntdPagination(report, paging),
            showSizeChanger: true,
            pageSizeOptions: [10, 20, 50, 100],
          }}
          onChange={(pagination) => setPaging(fromAntdChange(pagination, undefined, paging))}
          scroll={{ x: true }}
          columns={[
            { title: 'Sản phẩm', dataIndex: 'productName' },
            { title: 'Danh mục', dataIndex: 'categoryName', render: (name: string | null) => name ?? '—' },
            { title: 'Biến thể', dataIndex: 'variantName', render: (name: string | null) => name ?? 'Mặc định' },
            { title: 'SKU', dataIndex: 'sku' },
            { title: 'Trạng thái', key: 'status', render: (_, item) => stockStatusTag(item) },
            { title: 'Số lượng tồn', dataIndex: 'stockQuantity', align: 'right',
              render: (quantity: number) => countFormatter.format(quantity) },
            { title: 'Ngưỡng tối thiểu', dataIndex: 'minQuantity', align: 'right',
              render: (quantity: number | null) => quantity === null ? '—' : countFormatter.format(quantity) },
            { title: 'Giá vốn bình quân', dataIndex: 'avgUnitCost', align: 'right',
              render: (amount: number) => <MoneyText value={amount} /> },
            { title: 'Giá trị tồn', dataIndex: 'stockValue', align: 'right',
              render: (amount: number) => <MoneyText value={amount} /> },
          ]}
          locale={{ emptyText: query.isPending ? 'Đang tải báo cáo tồn kho…'
            : query.isError ? 'Chưa tải được kết quả.' : filtered ? 'Không có hàng khớp bộ lọc.'
              : 'Chưa có dữ liệu tồn kho.' }}
        />
      </Card>
    </Flex>
  );
}
