// Báo cáo tồn kho (FR-33). Chỉ Chủ cửa hàng.
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Col, Flex, Input, Row, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import { ErrorAlert, MoneyText, RecordTable, useDocumentTitle } from '../../../shared/components';
import type { QueryResponse } from '../../../shared/types/reporting';

interface StockRow {
  productId: string;
  productName: string;
  categoryName: string | null;
  variantId: string;
  variantName: string | null;
  sku: string;
  stockQuantity: number;
  avgUnitCost: number;
  stockValue: number;
}

interface StockSummary {
  stockQuantity: number;
  stockValue: number;
  variantCount: number;
  byCategory: { categoryName: string; stockQuantity: number; stockValue: number }[];
  byProduct: {
    productId: string;
    productName: string;
    categoryName: string | null;
    stockQuantity: number;
    stockValue: number;
    variants: { variantId: string; sku: string; variantName: string | null; stockQuantity: number;
      avgUnitCost: number; stockValue: number }[];
  }[];
}

const countFormatter = new Intl.NumberFormat('vi-VN');

export function StockReportPage() {
  useDocumentTitle('Báo cáo tồn kho');
  const [productName, setProductName] = useState('');
  const [searchText, setSearchText] = useState('');
  const query = useQuery({
    queryKey: ['reporting', 'stock', productName],
    queryFn: ({ signal }) => api.get<QueryResponse<StockRow, StockSummary>>('/api/v1/reporting/q/Q5', {
      query: { productName: productName || undefined }, signal,
    }),
  });
  const report = query.data;

  return (
    <Flex vertical gap={16}>
      <div>
        <Typography.Title level={1} style={{ margin: 0, fontSize: 'clamp(23px, 1.8vw, 27px)' }}>
          Báo cáo tồn kho
        </Typography.Title>
        <Typography.Text type="secondary">Giá trị tồn được tính theo giá vốn bình quân hiện tại.</Typography.Text>
      </div>
      <Input.Search
        aria-label="Lọc theo sản phẩm"
        placeholder="Tìm theo tên sản phẩm"
        value={searchText}
        allowClear
        style={{ maxWidth: 360 }}
        onChange={(event) => {
          setSearchText(event.target.value);
          if (!event.target.value) setProductName('');
        }}
        onSearch={(value) => setProductName(value.trim())}
      />
      {query.isError && <ErrorAlert error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />}
      {query.isPending && <Typography.Text role="status">Đang tải báo cáo tồn kho…</Typography.Text>}

      {report && (
        <>
          <Row gutter={[12, 12]}>
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
                <Typography.Text type="secondary">Biến thể đang kinh doanh</Typography.Text>
                <div style={{ fontSize: 22, fontWeight: 700 }}>{countFormatter.format(report.summary.variantCount)}</div>
              </Card>
            </Col>
          </Row>
          <Card title="Tồn kho theo sản phẩm và biến thể">
            <RecordTable<StockRow>
              rowKey="variantId"
              dataSource={report.rows}
              pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100] }}
              scroll={{ x: true }}
              columns={[
                { title: 'Sản phẩm', dataIndex: 'productName' },
                { title: 'Danh mục', dataIndex: 'categoryName', render: (name: string | null) => name ?? '—' },
                { title: 'Phân loại', dataIndex: 'variantName', render: (name: string | null) => name ?? 'Mặc định' },
                { title: 'SKU', dataIndex: 'sku' },
                { title: 'Số lượng tồn', dataIndex: 'stockQuantity', align: 'right',
                  render: (quantity: number) => countFormatter.format(quantity) },
                { title: 'Giá vốn bình quân', dataIndex: 'avgUnitCost', align: 'right',
                  render: (amount: number) => <MoneyText value={amount} /> },
                { title: 'Giá trị tồn', dataIndex: 'stockValue', align: 'right',
                  render: (amount: number) => <MoneyText value={amount} /> },
              ]}
              locale={{ emptyText: 'Không có sản phẩm phù hợp.' }}
            />
          </Card>
        </>
      )}
    </Flex>
  );
}
