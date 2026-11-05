// Nhà cung cấp (FR-23). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function SuppliersPage() {
  return (
    <PagePlaceholder
      title="Nhà cung cấp"
      frs={['FR-23']}
      phase="GĐ1"
      owner="Hiếu"
      description="Thêm, sửa Nhà cung cấp: tên, số điện thoại, địa chỉ."
    />
  );
}
