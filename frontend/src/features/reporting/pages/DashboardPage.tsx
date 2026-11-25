// Trang tổng quan (FR-29). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
// Trang chính của Chủ cửa hàng sau khi đăng nhập. Dùng được từ chiều rộng 360px.
import { Card, Flex, Typography } from 'antd';
import { useDocumentTitle } from '../../../shared/components';
import { LowStockPanel } from '../../../shared/components/LowStockPanel';

export function DashboardPage() {
  useDocumentTitle('Trang tổng quan');
  return (
    <Flex vertical gap="middle">
      <Typography.Title level={1} style={{ margin: 0 }}>Trang tổng quan</Typography.Title>
      <LowStockPanel />
      <Card title="Doanh thu và sản phẩm bán chạy">
        <Typography.Text type="secondary">
          Thống kê doanh thu, số đơn hôm nay và sản phẩm bán chạy đang được chuẩn bị.
        </Typography.Text>
      </Card>
    </Flex>
  );
}
