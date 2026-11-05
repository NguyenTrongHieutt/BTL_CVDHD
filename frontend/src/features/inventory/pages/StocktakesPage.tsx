// Kiểm kho, điều chỉnh Tồn kho (FR-25). Người giữ: Anh — features/inventory (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function StocktakesPage() {
  return (
    <PagePlaceholder
      title="Kiểm kho"
      frs={['FR-25']}
      phase="GĐ2"
      owner="Anh"
      description="Lập Phiếu kiểm kho với số lượng đếm thực tế; hệ thống ghi chênh lệch, lý do và Nhật ký thao tác."
    />
  );
}
