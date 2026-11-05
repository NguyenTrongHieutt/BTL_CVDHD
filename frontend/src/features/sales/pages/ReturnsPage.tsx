// Phiếu trả hàng (FR-16). Người giữ: Anh — features/sales (AD-20).
import { PagePlaceholder } from '../../../shared/components';

export function ReturnsPage() {
  return (
    <PagePlaceholder
      title="Phiếu trả hàng"
      frs={['FR-16']}
      phase="GĐ2"
      owner="Anh"
      description="Tạo Phiếu trả hàng từ một Đơn hàng đã Hoàn thành, cho một phần hoặc toàn bộ hàng, rồi hoàn tiền."
    />
  );
}
