// Thông tin Cửa hàng (FR-48). Người giữ: Hiếu — features/platform (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function StoreSettingsPage() {
  return (
    <PagePlaceholder
      title="Thông tin Cửa hàng"
      frs={['FR-48 (TK-05)']}
      phase="GĐ1"
      owner="Hiếu"
      description="Tên, địa chỉ, số điện thoại in trên hóa đơn, tài khoản ngân hàng nhận VietQR; tỷ lệ tích điểm, số ngày cảnh báo hết hạn."
    />
  );
}
