// Phiếu nhập (FR-24). Người giữ: Anh — features/inventory (AD-20). Chỉ Chủ cửa hàng.
// POST tạo Phiếu nhập mang Idempotency-Key (AD-7); dòng của hàng ngừng kinh doanh bị từ chối (409 variant-inactive).
import { PagePlaceholder } from '../../../shared/components';

export function PurchaseReceiptsPage() {
  return (
    <PagePlaceholder
      title="Phiếu nhập"
      frs={['FR-24']}
      phase="GĐ2"
      owner="Anh"
      description="Lập Phiếu nhập gồm Nhà cung cấp, Sản phẩm, số lượng và giá nhập. Giá vốn bình quân do backend tính lại."
    />
  );
}
