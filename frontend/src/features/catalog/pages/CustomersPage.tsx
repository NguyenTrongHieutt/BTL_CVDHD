// Khách hàng (FR-18, FR-19, FR-20). Người giữ: Hiếu — features/catalog (AD-20).
// Tab Lịch sử mua (FR-20) gọi `GET /api/v1/sales/orders?customerId=` của Anh qua shared/api,
// dùng kiểu ở shared/types/sales.ts; không import features/sales.
import { PagePlaceholder } from '../../../shared/components';

export function CustomersPage() {
  return (
    <PagePlaceholder
      title="Khách hàng"
      frs={['FR-18', 'FR-19', 'FR-20']}
      phase="GĐ1"
      owner="Hiếu"
      description="Thêm, sửa, tìm Khách hàng theo tên hoặc số điện thoại (GĐ1). Tab Lịch sử mua (FR-20) làm ở GĐ2."
    />
  );
}
