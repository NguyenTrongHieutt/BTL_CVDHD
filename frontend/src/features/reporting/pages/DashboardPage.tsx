// Trang tổng quan (FR-29). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
// Trang chính của Chủ cửa hàng sau khi đăng nhập. Dùng được từ chiều rộng 360px.
import { PagePlaceholder } from '../../../shared/components';

import { LowStockPanel } from '../../../shared/components/LowStockPanel';

export function DashboardPage() {
  return (
    <PagePlaceholder
      title="Trang tổng quan"
      frs={['FR-29 (BC-01)']}
      phase="GĐ2"
      owner="Lân"
      description="Doanh thu và số Đơn hàng hôm nay, 5 Sản phẩm bán chạy trong 7 ngày, cảnh báo tồn thấp."
    >
      <LowStockPanel />
    </PagePlaceholder>
  );
}
