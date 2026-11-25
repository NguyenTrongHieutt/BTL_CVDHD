// Trang tổng quan (FR-29). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
import { useQuery } from '@tanstack/react-query';
import { Button, Card, Col, Flex, Row, Typography } from 'antd';
import { api } from '../../../shared/api/client';
import { ErrorAlert, MoneyText, useDocumentTitle } from '../../../shared/components';
import { LowStockPanel } from '../../../shared/components/LowStockPanel';
import type { IsoDate, Money, Uuid } from '../../../shared/types/common';

interface Dashboard {
  date: IsoDate;
  today: { revenue: Money; orderCount: number };
  top5Last7Days: { productId: Uuid; name: string; quantity: number; revenue: Money }[];
}

const dateFormatter = new Intl.DateTimeFormat('vi-VN', {
  day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC',
});
const countFormatter = new Intl.NumberFormat('vi-VN');

export function DashboardPage() {
  useDocumentTitle('Trang tổng quan');
  const query = useQuery({
    queryKey: ['reporting', 'dashboard'],
    queryFn: ({ signal }) => api.get<Dashboard>('/api/v1/reporting/dashboard', { signal }),
  });
  const dashboard = query.data;

  return (
    <Flex vertical gap={16}>
      <div>
        <Typography.Title level={1} style={{ margin: 0, fontSize: 'clamp(23px, 1.8vw, 27px)' }}>
          Trang tổng quan
        </Typography.Title>
        {dashboard && <Typography.Text type="secondary">
          <time dateTime={dashboard.date}>{dateFormatter.format(new Date(`${dashboard.date}T00:00:00Z`))}</time>
        </Typography.Text>}
      </div>

      {query.isError && (
        <div>
          <ErrorAlert error={query.error} />
          <Button loading={query.isFetching} onClick={() => void query.refetch()}>Tải lại tổng quan</Button>
        </div>
      )}
      {query.isPending && <Typography.Text role="status">Đang tải tổng quan…</Typography.Text>}

      {dashboard && (
        <>
          <section aria-labelledby="dashboard-today">
            <Typography.Title level={2} id="dashboard-today" style={{ fontSize: 18, margin: '0 0 12px' }}>
              Kết quả hôm nay
            </Typography.Title>
            <Row gutter={[12, 12]}>
              <Col xs={24} sm={12}>
                <Card size="small">
                  <Typography.Text type="secondary">Doanh thu sau hoàn tiền</Typography.Text>
                  <div style={{ fontSize: 26, fontWeight: 700 }}><MoneyText value={dashboard.today.revenue} /></div>
                </Card>
              </Col>
              <Col xs={24} sm={12}>
                <Card size="small">
                  <Typography.Text type="secondary">Số đơn hàng</Typography.Text>
                  <div style={{ fontSize: 26, fontWeight: 700 }}>{countFormatter.format(dashboard.today.orderCount)}</div>
                </Card>
              </Col>
            </Row>
          </section>

          <section aria-labelledby="dashboard-top-products">
            <Card title={<Typography.Title id="dashboard-top-products" level={2} style={{ margin: 0, fontSize: 18 }}>
              Bán chạy trong 7 ngày
            </Typography.Title>}>
              {dashboard.top5Last7Days.length === 0 ? (
                <Typography.Text type="secondary">Chưa có sản phẩm bán ra trong 7 ngày.</Typography.Text>
              ) : (
                <ol style={{ margin: 0, paddingInlineStart: 20 }}>
                  {dashboard.top5Last7Days.map((product) => (
                    <li key={product.productId} style={{ paddingBlock: 6 }}>
                      <Flex justify="space-between" align="baseline" wrap gap="4px 16px">
                        <Typography.Text strong style={{ overflowWrap: 'anywhere' }}>{product.name}</Typography.Text>
                        <Typography.Text type="secondary">Đã bán {countFormatter.format(product.quantity)}</Typography.Text>
                      </Flex>
                    </li>
                  ))}
                </ol>
              )}
            </Card>
          </section>
        </>
      )}

      <LowStockPanel />
    </Flex>
  );
}
