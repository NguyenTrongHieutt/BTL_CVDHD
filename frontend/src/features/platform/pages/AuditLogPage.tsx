// Nhật ký thao tác (FR-49, Nên có). Người giữ: Hiếu — features/platform (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function AuditLogPage() {
  return (
    <PagePlaceholder
      title="Nhật ký thao tác"
      frs={['FR-49 (TK-06)']}
      phase="GĐ3"
      owner="Hiếu"
      description="Xem các thao tác nhạy cảm: hủy Đơn hàng, trả hàng, sửa giá, điều chỉnh Tồn kho."
    />
  );
}
