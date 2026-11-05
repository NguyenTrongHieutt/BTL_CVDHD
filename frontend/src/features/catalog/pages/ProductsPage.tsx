// Sản phẩm (FR-1, FR-2, FR-3, FR-5). Người giữ: Hiếu — features/catalog (AD-20).
// Nhân viên chỉ xem và tìm kiếm; thêm, sửa, ngừng kinh doanh chỉ Chủ cửa hàng (PRD 4.7).
import { PagePlaceholder } from '../../../shared/components';

export function ProductsPage() {
  return (
    <PagePlaceholder
      title="Sản phẩm"
      frs={['FR-1', 'FR-2', 'FR-3', 'FR-5']}
      phase="GĐ1"
      owner="Hiếu"
      description="Danh sách, Tìm kiếm thường và lọc Sản phẩm; Chủ cửa hàng thêm, sửa, ngừng kinh doanh. Nhập từ Excel (FR-6) và Biến thể (FR-7) làm ở GĐ3."
    />
  );
}
