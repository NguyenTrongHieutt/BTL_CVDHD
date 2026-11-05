// Thông tin cá nhân, đổi mật khẩu (FR-47). Người giữ: Hiếu — features/platform (AD-20).
import { PagePlaceholder } from '../../../shared/components';

export function ProfilePage() {
  return (
    <PagePlaceholder
      title="Thông tin cá nhân"
      frs={['FR-47 (TK-04)']}
      phase="GĐ2"
      owner="Hiếu"
      description="Chủ cửa hàng và Nhân viên cập nhật thông tin của mình và đổi mật khẩu."
    />
  );
}
