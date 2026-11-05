// Đơn hàng: tra cứu, trạng thái, in lại hóa đơn (FR-13, FR-15, FR-17). Người giữ: Anh — features/sales (AD-20).
// Nhân viên chỉ hủy được Đơn hàng do mình tạo trong ngày; backend kiểm quy tắc này (AD-16).
import { PagePlaceholder } from '../../../shared/components';

export function OrdersPage() {
  return (
    <PagePlaceholder
      title="Đơn hàng"
      frs={['FR-13', 'FR-15', 'FR-17']}
      phase="GĐ2"
      owner="Anh"
      description="Tra cứu theo mã Đơn hàng, số điện thoại Khách hàng, khoảng thời gian, trạng thái, người bán; in lại hóa đơn; hủy đơn; mở Phiếu trả hàng."
    />
  );
}
