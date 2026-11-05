// Lịch sử xuất nhập kho (FR-28). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng.
// Gọi `GET /api/v1/inventory/ledger` của Anh qua shared/api, kiểu `StockLedgerEntry` ở
// shared/types/inventory.ts; không import features/inventory.
import { PagePlaceholder } from '../../../shared/components';

export function StockLedgerPage() {
  return (
    <PagePlaceholder
      title="Lịch sử xuất nhập kho"
      frs={['FR-28 (KHO-07)']}
      phase="GĐ2"
      owner="Hiếu"
      description="Lọc theo Sản phẩm, loại giao dịch, người thực hiện và thời gian."
    />
  );
}
