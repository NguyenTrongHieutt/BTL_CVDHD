// Tài khoản Nhân viên (FR-45). Người giữ: Hiếu — features/platform (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function StaffPage() {
  return (
    <PagePlaceholder
      title="Tài khoản Nhân viên"
      frs={['FR-45 (TK-02)']}
      phase="GĐ2"
      owner="Hiếu"
      description="Tạo, khóa và đặt lại mật khẩu cho Nhân viên. Tên đăng nhập trùng thì báo lỗi login-taken."
    />
  );
}
