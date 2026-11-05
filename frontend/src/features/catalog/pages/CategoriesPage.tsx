// Danh mục (FR-4). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function CategoriesPage() {
  return (
    <PagePlaceholder
      title="Danh mục"
      frs={['FR-4']}
      phase="GĐ1"
      owner="Hiếu"
      description="Thêm, sửa, xóa Danh mục. Không xóa được Danh mục còn Sản phẩm."
    />
  );
}
